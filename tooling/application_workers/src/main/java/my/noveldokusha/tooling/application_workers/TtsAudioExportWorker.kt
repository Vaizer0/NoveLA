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
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/** Exports chapter audio + timeline and then builds a cinematic MP4 from those exact artifacts. */
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
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, TtsAudioEntryPoint::class.java)
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
            val msg = context.getString(StringsR.string.tts_audio_export_unsupported_format, request.format)
            fail(appPreferences, jobId, notification, msg)
            return Result.failure()
        }

        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val tempWav = File(tempDir, "$jobId.wav")
        val tempVideo = File(tempDir, "$jobId.mp4.tmp")
        var createdUri: Uri? = null
        var timelineUri: Uri? = null
        var videoUri: Uri? = null

        try {
            if (!isDirectoryAccessible(context, request.outputDirectoryUri)) {
                appPreferences.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value = ""
                fail(appPreferences, jobId, notification, context.getString(StringsR.string.tts_audio_export_dir_error))
                return Result.failure()
            }
            val novelFolderUri = resolveNovelFolder(context, request)
                ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_folder_error))

            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            runCatching {
                setForeground(ForegroundInfo(notification.notificationId, notification.foregroundNotification(), foregroundType))
            }.onFailure { Timber.w(it, "TtsAudio: setForeground failed") }

            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(status = TtsAudioJobStatus.RUNNING, phase = "AUDIO", videoSizeBytes = 0L, progress = 0)
            }
            val chapterText = withContext(Dispatchers.IO) { fetchChapterText(appDatabase, appPreferences, request) }
            if (chapterText == null) {
                val msg = context.getString(
                    if (request.source == TtsAudioSource.TRANSLATED) StringsR.string.tts_audio_export_no_translation
                    else StringsR.string.tts_audio_export_no_download
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
            val fileName = if (sourceSuffix.isBlank()) "$baseName.${request.format}" else "$baseName $sourceSuffix.${request.format}"
            val timelineFileName = "${fileName.removeSuffix(".${request.format}")}.timeline.json"
            val reportAudio = progressReporter(appPreferences, jobId, notification)

            val exportResult = TtsAudioExporter(context).exportAudio(
                request = request,
                paragraphs = paragraphs,
                destFile = tempWav,
                audioFileName = fileName,
            ) { fraction -> reportAudio((fraction * 82f).toInt().coerceIn(0, 82)) }
            require(tempWav.length() > 44L) { "Generated WAV is empty for '${request.chapterTitle}'" }

            createdUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, novelFolderUri, MIME_WAV, fileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
            copyFileToUri(tempWav, createdUri!!)

            timelineUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, novelFolderUri, MIME_JSON, timelineFileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
            writeTextToUri(timelineToJson(exportResult.timeline), timelineUri!!)

            val renderWav = File(tempDir, "$jobId-render.wav")
            val renderJson = File(tempDir, "$jobId-render.timeline.json")
            try {
                // Render from the exact documents that were persisted to the user's SAF directory.
                copyUriToFile(createdUri!!, renderWav)
                copyUriToFile(timelineUri!!, renderJson)
                TtsAudioQueue.updateState(appPreferences, jobId) {
                    it?.copy(phase = "VIDEO", progress = 82, videoSizeBytes = 0L)
                }
                CinematicVideoExporter(context).export(
                    wavFile = renderWav,
                    timelineFile = renderJson,
                    outputFile = tempVideo,
                    onProgress = { fraction ->
                        val percent = (82f + fraction * 17f).toInt().coerceIn(82, 99)
                        TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(progress = percent, phase = "VIDEO") }
                    },
                    onSizeBytes = { bytes ->
                        TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(phase = "VIDEO", videoSizeBytes = bytes) }
                    },
                )

                val videoFileName = "${fileName.removeSuffix(".${request.format}")}.mp4"
                videoUri = withContext(Dispatchers.IO) {
                    DocumentsContract.createDocument(context.contentResolver, novelFolderUri, MIME_MP4, videoFileName)
                } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
                copyFileToUri(tempVideo, videoUri!!)
                TtsAudioQueue.updateState(appPreferences, jobId) {
                    it?.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        displayName = videoFileName,
                        documentUri = videoUri.toString(),
                        progress = 100,
                        phase = "VIDEO",
                        videoSizeBytes = tempVideo.length(),
                    )
                }
                notification.updateProgress(100)
                notification.showComplete(videoFileName, videoUri)
            } finally {
                renderWav.delete()
                renderJson.delete()
                tempVideo.delete()
            }

            tempWav.delete()
            return Result.success()
        } catch (e: CancellationException) {
            cleanupUri(context, videoUri)
            if (videoUri == null) cleanupUri(context, createdUri)
            cleanupUri(context, timelineUri)
            tempWav.delete()
            tempVideo.delete()
            TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(status = TtsAudioJobStatus.CANCELLED, message = "Cancelled") }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsAudio: export/video FAILED for $jobId")
            // Preserve the already-saved WAV + timeline if the cinematic stage fails.
            cleanupUri(context, videoUri)
            tempWav.delete()
            tempVideo.delete()
            val message = e.message ?: ""
            notification.showError(context.getString(StringsR.string.tts_audio_export_failure_detail, message))
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it?.copy(status = TtsAudioJobStatus.FAILED, message = message, phase = if (timelineUri != null) "VIDEO" else "AUDIO")
            }
            return Result.failure()
        }
    }

    private fun fail(appPreferences: AppPreferences, jobId: String, notification: TtsAudioExportNotification, message: String) {
        notification.showError(message)
        TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(status = TtsAudioJobStatus.FAILED, message = message) }
    }

    private fun progressReporter(appPreferences: AppPreferences, jobId: String, notification: TtsAudioExportNotification): (Int) -> Unit {
        var lastReported = -1
        var lastNotifyMs = 0L
        return { value ->
            val percent = value.coerceIn(0, 100)
            if (percent != lastReported) {
                lastReported = percent
                TtsAudioQueue.updateState(appPreferences, jobId) { it?.copy(progress = percent, phase = "AUDIO") }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifyMs >= 1000L || percent == 100) {
                    notification.updateProgress(percent)
                    lastNotifyMs = now
                }
            }
        }
    }

    private suspend fun fetchChapterText(appDatabase: AppDatabase, appPreferences: AppPreferences, request: TtsAudioExportRequest): String? {
        return if (request.source == TtsAudioSource.TRANSLATED) {
            val sourceLang = request.translationSourceLang.ifBlank { appPreferences.translationPairForBook(request.novelUrl).source }
            val targetLang = request.translationTargetLang.ifBlank { appPreferences.translationPairForBook(request.novelUrl).target }
            if (sourceLang.isBlank() || targetLang.isBlank()) return null
            val translation = appDatabase.chapterTranslationDao().getTranslations(request.chapterUrl, sourceLang, targetLang) ?: return null
            if (translation.translatedParagraphs.isBlank()) return null
            runCatching {
                val paragraphs = JSONArray(translation.translatedParagraphs)
                if (paragraphs.length() == 0) null else (0 until paragraphs.length()).joinToString("\n\n") { paragraphs.getString(it) }
            }.onFailure { Timber.e(it, "TtsAudio: invalid translation JSON") }.getOrNull()
        } else {
            appDatabase.chapterBodyDao().get(request.chapterUrl)?.body?.takeIf { it.isNotBlank() }
        }
    }

    private fun readRequest(): TtsAudioExportRequest? {
        val novelTitle = inputData.getString(KEY_NOVEL_TITLE) ?: return null
        val novelUrl = inputData.getString(KEY_NOVEL_URL) ?: return null
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL) ?: return null
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: return null
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, 0)
        val source = runCatching { TtsAudioSource.valueOf(inputData.getString(KEY_SOURCE) ?: return null) }.getOrNull() ?: return null
        if (source == TtsAudioSource.ASK_EVERY_TIME) return null
        return TtsAudioExportRequest(
            jobId = inputData.getString(KEY_JOB_ID) ?: return null,
            novelTitle = novelTitle,
            novelUrl = novelUrl,
            chapterUrl = chapterUrl,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            source = source,
            enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: "",
            voiceId = inputData.getString(KEY_VOICE_ID) ?: "",
            speed = inputData.getFloat(KEY_SPEED, 1f),
            pitch = inputData.getFloat(KEY_PITCH, 1f),
            outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null,
            format = inputData.getString(KEY_FORMAT) ?: TtsAudioFormat.WAV,
            translationSourceLang = inputData.getString(KEY_TRANSLATION_SOURCE_LANG) ?: "",
            translationTargetLang = inputData.getString(KEY_TRANSLATION_TARGET_LANG) ?: "",
        )
    }

    private fun isDirectoryAccessible(context: Context, directoryUri: String): Boolean = try {
        val treeUri = Uri.parse(directoryUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
    } catch (e: Exception) {
        Timber.e(e, "TtsAudio: directory access check failed")
        false
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(name: String, fallback: String = "chapter"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    private suspend fun resolveNovelFolder(context: Context, request: TtsAudioExportRequest): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = Uri.parse(request.outputDirectoryUri)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val wrapperDocId = findOrCreateDirectoryDocId(context, treeUri, rootDocId, WRAPPER_FOLDER_NAME) ?: return@runCatching null
            val novelDocId = findOrCreateDirectoryDocId(context, treeUri, wrapperDocId, sanitize(request.novelTitle, "novel")) ?: return@runCatching null
            DocumentsContract.buildDocumentUriUsingTree(treeUri, novelDocId)
        }.getOrNull()
    }

    private fun findOrCreateDirectoryDocId(context: Context, treeUri: Uri, parentDocId: String, folderName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val existing = queryDirectoryId(context, childrenUri, folderName)
        if (existing != null) return existing
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName,
            )?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()
        return created ?: queryDirectoryId(context, childrenUri, folderName)
    }

    private fun queryDirectoryId(context: Context, childrenUri: Uri, folderName: String): String? =
        context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                if (name.equals(folderName, ignoreCase = true)) return@use cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            }
            null
        }

    private suspend fun copyUriToFile(uri: Uri, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw TtsExportException("Unable to read exported document")
    }

    private suspend fun copyFileToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { it.copyTo(output, 64 * 1024) }
        } ?: throw TtsExportException("Unable to write exported document")
    }

    private suspend fun writeTextToUri(text: String, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw TtsExportException("Unable to write timeline document")
    }

    private fun cleanupUri(context: Context, uri: Uri?) {
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        const val TAG = "TtsAudioExport"
        const val MIME_WAV = "audio/wav"
        const val MIME_JSON = "application/json"
        const val MIME_MP4 = "video/mp4"
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
