package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsAudioFormat
import my.noveldokusha.text_to_speech.TtsAudioExporter
import my.noveldokusha.text_to_speech.TtsExportException
import my.noveldokusha.text_to_speech.TtsTextPreparer
import my.noveldokusha.text_to_speech.timelineToJson
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/**
 * Синтезирует одну главу в аудиофайл (V1: WAV) в выбранную через SAF папку.
 *
 * Независим от живой озвучки в читалке: использует ВЫДЕЛЕННЫЕ export-only TTS
 * clients и динамически оставляет один TTS concurrency slot для live reader
 * playback when both use the same engine.
 *
 * Источник текста: ORIGINAL — кэш тела главы; TRANSLATED — ТОЛЬКО закэшированный
 * перевод пары языков книги (при отсутствии — явная ошибка, без скачивания
 * перевода и без тихого отката на оригинал).
 */
class TtsAudioExportWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TtsAudioEntryPoint {
        fun appPreferences(): AppPreferences
        fun appDatabase(): AppDatabase
        fun notificationsCenter(): NotificationsCenter
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val request = readRequest() ?: return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            TtsAudioEntryPoint::class.java
        )
        val appPreferences = entryPoint.appPreferences()
        val appDatabase = entryPoint.appDatabase()
        val notificationsCenter = entryPoint.notificationsCenter()

        val notification = TtsAudioExportNotification(
            chapterTitle = request.chapterTitle,
            workRequestId = id.toString(),
            context = context,
            notificationsCenter = notificationsCenter,
        )

        if (request.format != TtsAudioFormat.WAV) {
            Timber.e("TtsAudio: unsupported format '${request.format}' for job $jobId (V1 supports WAV only)")
            fail(appPreferences, jobId, notification,
                context.getString(StringsR.string.tts_audio_export_unsupported_format, request.format))
            return Result.failure()
        }

        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        // jobId contains the generation UUID, so retries/re-enqueues cannot share a temporary
        // WAV with an older cancelled generation.
        val tempWav = File(tempDir, "$jobId.wav")
        var createdUri: Uri? = null
        var timelineUri: Uri? = null

        try {
            if (!isDirectoryAccessible(context, request.outputDirectoryUri)) {
                Timber.e("TtsAudio: directory NOT accessible, clearing TTS_AUDIO_DOWNLOAD_LOCATION_URI")
                appPreferences.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value = ""
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_audio_export_dir_error))
                return Result.failure()
            }

            val novelFolderUri = resolveNovelFolder(context, request)
            if (novelFolderUri == null) {
                Timber.e("TtsAudio: could not create novel folder for $jobId")
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_audio_export_folder_error))
                return Result.failure()
            }

            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            try {
                setForeground(
                    ForegroundInfo(notification.notificationId, notification.foregroundNotification(), foregroundType)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "TtsAudio: setForeground failed, continuing as background worker")
            }

            TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(status = TtsAudioJobStatus.RUNNING) }

            val chapterText = withContext(Dispatchers.IO) {
                fetchChapterText(appDatabase, appPreferences, request)
            }
            if (chapterText == null) {
                val msg = context.getString(
                    if (request.source == TtsAudioSource.TRANSLATED)
                        StringsR.string.tts_audio_export_no_translation
                    else
                        StringsR.string.tts_audio_export_no_download
                )
                fail(appPreferences, jobId, notification, msg)
                return Result.failure()
            }

            val regexRules = appPreferences.effectiveRegexRules(request.novelUrl)
            val paragraphs = TtsTextPreparer.paragraphsFromBody(chapterText, regexRules)

            val sourceSuffix = when (request.source) {
                TtsAudioSource.ORIGINAL -> context.getString(StringsR.string.tts_audio_file_suffix_original)
                TtsAudioSource.TRANSLATED -> context.getString(StringsR.string.tts_audio_file_suffix_translated)
                TtsAudioSource.ASK_EVERY_TIME -> ""
            }
            val baseName = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}"
            val fileName = if (sourceSuffix.isBlank())
                "$baseName.${request.format}"
            else
                "$baseName $sourceSuffix.${request.format}"
            val timelineFileName = "${fileName.removeSuffix(".${request.format}")}.timeline.json"

            val report = progressReporter(appPreferences, jobId, notification)
            val result = TtsAudioExporter(context).exportAudio(
                request = request,
                paragraphs = paragraphs,
                destFile = tempWav,
                audioFileName = fileName,
            ) { fraction ->
                report((fraction * 89).toInt().coerceIn(0, 89))
            }

            val tempWavSize = tempWav.length()
            if (tempWavSize <= 0L) {
                throw TtsExportException("Generated WAV is empty for '${request.chapterTitle}'")
            }

            val parentUri = novelFolderUri
            createdUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_WAV, fileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))

            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(createdUri!!)
                    ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
                output.use { os ->
                    tempWav.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            os.write(buffer, 0, read)
                            copied += read
                            report(90 + ((copied * 10) / tempWavSize).toInt().coerceIn(0, 9))
                        }
                    }
                }
            }

            // Audio and timeline are an atomic logical result: if JSON cannot be created or
            // written, do not report success with an audio-only artifact.
            val timelineJson = timelineToJson(result.timeline)
            timelineUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    MIME_JSON,
                    timelineFileName,
                )
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))

            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(timelineUri!!)
                    ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
                output.use { os ->
                    os.write(timelineJson.toByteArray(Charsets.UTF_8))
                }
            }

            val displayName = queryDisplayName(createdUri!!) ?: fileName
            notification.updateProgress(100)
            notification.showComplete(displayName, createdUri)
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    displayName = displayName,
                    documentUri = createdUri.toString(),
                    progress = 100,
                )
            }
            tempWav.delete()
            return Result.success()
        } catch (e: CancellationException) {
            createdUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            timelineUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            tempWav.delete()
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(status = TtsAudioJobStatus.CANCELLED, message = "Cancelled")
            }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsAudio: EXPORT FAILED for $jobId")
            createdUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            timelineUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            tempWav.delete()
            val message = context.getString(StringsR.string.tts_audio_export_failure_detail, e.message ?: "")
            notification.showError(message)
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(status = TtsAudioJobStatus.FAILED, message = e.message ?: "")
            }
            return Result.failure()
        }
    }

    private fun fail(
        appPreferences: AppPreferences,
        jobId: String,
        notification: TtsAudioExportNotification,
        message: String,
    ) {
        notification.showError(message)
        TtsAudioQueue.updateState(appPreferences, jobId) {
            it?.copy(status = TtsAudioJobStatus.FAILED, message = message)
        }
    }

    /**
     * Троттлит публикацию прогресса: персистит только в AppPreferences при смене
     * процента; уведомление — не чаще раза в секунду. WorkManager progress storage
     * здесь не используется: UI получает состояние из AppPreferences.
     */
    private fun progressReporter(
        appPreferences: AppPreferences,
        jobId: String,
        notification: TtsAudioExportNotification,
    ): (Int) -> Unit {
        var lastReported = -1
        var lastNotifyMs = 0L
        return report@{ percent ->
            val clamped = percent.coerceIn(0, 100)
            if (clamped == lastReported) return@report
            lastReported = clamped
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(progress = clamped)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifyMs >= 1_000 || clamped == 100) {
                notification.updateProgress(clamped)
                lastNotifyMs = now
            }
        }
    }

    private suspend fun fetchChapterText(
        appDatabase: AppDatabase,
        appPreferences: AppPreferences,
        request: TtsAudioExportRequest,
    ): String? {
        return if (request.source == TtsAudioSource.TRANSLATED) {
            val sourceLang = request.translationSourceLang.ifBlank {
                appPreferences.translationPairForBook(request.novelUrl).source
            }
            val targetLang = request.translationTargetLang.ifBlank {
                appPreferences.translationPairForBook(request.novelUrl).target
            }
            if (sourceLang.isBlank() || targetLang.isBlank()) return null
            val translation = appDatabase.chapterTranslationDao()
                .getTranslations(request.chapterUrl, sourceLang, targetLang)
                ?: return null
            if (translation.translatedParagraphs.isBlank()) return null
            try {
                val paragraphs = JSONArray(translation.translatedParagraphs)
                if (paragraphs.length() == 0) {
                    null
                } else {
                    (0 until paragraphs.length()).joinToString("\n\n") { paragraphs.getString(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "TtsAudio: invalid translation JSON for ${request.chapterUrl}")
                null
            }
        } else {
            appDatabase.chapterBodyDao().get(request.chapterUrl)?.body?.takeIf { it.isNotBlank() }
        }
    }

    private fun readRequest(): TtsAudioExportRequest? {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return null
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: return null
        val novelUrl = inputData.getString(KEY_NOVEL_URL) ?: return null
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return null
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: return null
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, 0)
        val sourceName = inputData.getString(KEY_SOURCE) ?: return null
        val source = runCatching { TtsAudioSource.valueOf(sourceName) }.getOrNull() ?: return null
        if (source == TtsAudioSource.ASK_EVERY_TIME) return null
        val enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: ""
        val voiceId = inputData.getString(KEY_VOICE_ID) ?: ""
        val speed = inputData.getFloat(KEY_SPEED, 1f)
        val pitch = inputData.getFloat(KEY_PITCH, 1f)
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null
        val format = inputData.getString(KEY_FORMAT) ?: TtsAudioFormat.WAV
        val translationSourceLang = inputData.getString(KEY_TRANSLATION_SOURCE_LANG) ?: ""
        val translationTargetLang = inputData.getString(KEY_TRANSLATION_TARGET_LANG) ?: ""
        return TtsAudioExportRequest(
            jobId = jobId,
            novelTitle = novelTitle,
            novelUrl = novelUrl,
            chapterUrl = chapterUrl,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            source = source,
            enginePackage = enginePackage,
            voiceId = voiceId,
            speed = speed,
            pitch = pitch,
            outputDirectoryUri = outputDirectoryUri,
            format = format,
            translationSourceLang = translationSourceLang,
            translationTargetLang = translationTargetLang,
        )
    }

    private fun isDirectoryAccessible(context: Context, directoryUri: String): Boolean {
        return try {
            val treeUri = Uri.parse(directoryUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { true } ?: false
        } catch (e: Exception) {
            Timber.e(e, "TtsAudio: isDirectoryAccessible FAILED")
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(name: String, fallback: String = "chapter"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    private suspend fun resolveNovelFolder(
        context: Context,
        request: TtsAudioExportRequest,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = Uri.parse(request.outputDirectoryUri)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val wrapperDocId = findOrCreateDirectoryDocId(
                context = context,
                treeUri = treeUri,
                parentDocId = rootDocId,
                folderName = WRAPPER_FOLDER_NAME,
            ) ?: return@runCatching null

            val novelFolderName = sanitize(request.novelTitle, fallback = "novel")
            val novelDocId = findOrCreateDirectoryDocId(
                context = context,
                treeUri = treeUri,
                parentDocId = wrapperDocId,
                folderName = novelFolderName,
            ) ?: return@runCatching null

            DocumentsContract.buildDocumentUriUsingTree(treeUri, novelDocId)
        }.getOrNull()
    }

    private fun findOrCreateDirectoryDocId(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        folderName: String,
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val existing = context.contentResolver.query(childrenUri, null, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    )
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    )
                    if (displayName.equals(folderName, ignoreCase = true)) {
                        return@use cursor.getString(
                            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        )
                    }
                }
                null
            }
        if (existing != null) return existing

        val createdDocId = runCatching {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName,
            )?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()

        if (createdDocId != null) return createdDocId

        return context.contentResolver.query(childrenUri, null, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    )
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    )
                    if (displayName.equals(folderName, ignoreCase = true)) {
                        return@use cursor.getString(
                            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        )
                    }
                }
                null
            }
    }

    companion object {
        const val TAG = "TtsAudioExport"
        const val MIME_WAV = "audio/wav"
        const val MIME_JSON = "application/json"
        const val WRAPPER_FOLDER_NAME = "NoveLA Audio"
        const val KEY_PROGRESS = "progress"
        const val KEY_JOB_ID = "job_id"
        const val KEY_NOVEL_TITLE = "novel_title"
        const val KEY_NOVEL_URL = "novel_url"
        const val KEY_CHAPTER_URL = "chapter_url"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_SOURCE = "source"
        const val KEY_ENGINE_PACKAGE = "engine_package"
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
        const val KEY_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val KEY_FORMAT = "format"
        const val KEY_TRANSLATION_SOURCE_LANG = "translation_source_lang"
        const val KEY_TRANSLATION_TARGET_LANG = "translation_target_lang"
    }
}