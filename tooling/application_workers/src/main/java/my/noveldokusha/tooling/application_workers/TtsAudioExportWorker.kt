package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
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
import my.noveldokusha.text_to_speech.TtsAudioExporter
import my.noveldokusha.text_to_speech.TtsExportException
import my.noveldokusha.text_to_speech.TtsTextPreparer
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/**
 * Синтезирует одну главу в аудиофайл (V1: WAV) в выбранную через SAF папку.
 *
 * Независим от живой озвучки в читалке: использует ВЫДЕЛЕННЫЙ инстанс TTS
 * (движок/голос/скорость/высота из профиля TTS_AUDIO_DOWNLOAD_*), ничего не
 * проигрывает, и по завершении перезаписывает тот же файл при повторной
 * генерации (детерминированный jobId).
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

        val notification = TtsAudioExportNotification(request.chapterTitle, context, notificationsCenter)

        // Временные файлы для WAV — в cacheDir, не трогают user-данные.
        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val tempWav = File(tempDir, "$jobId.wav")
        var createdUri: Uri? = null

        try {
            // Валидация папки до чтения данных из БД: недоступная папка = нет
            // смысла синтезировать. Сброс приводит к повторному запросу выбора.
            if (!isDirectoryAccessible(context, request.outputDirectoryUri)) {
                Timber.e("TtsAudio: directory NOT accessible, clearing TTS_AUDIO_DOWNLOAD_LOCATION_URI")
                appPreferences.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value = ""
                fail(appPreferences, jobId, notification,
                    context.getString(StringsR.string.tts_audio_export_dir_error))
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

            TtsAudioQueue.updateState(appPreferences, jobId) { it!!.copy(status = TtsAudioJobStatus.RUNNING) }

            // ── Текст главы (оригинал или закэшированный перевод) ──────────────
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

            // Правила пользователя применяются ТЕ ЖЕ, что в читалке.
            val regexRules = appPreferences.effectiveRegexRules(request.novelUrl)
            val paragraphs = TtsTextPreparer.paragraphsFromBody(chapterText, regexRules)

            // ── Синтез в temp WAV ───────────────────────────────────────────────
            TtsAudioExporter(context).exportAudio(request, paragraphs, tempWav)

            // ── Копирование в SAF-папку и финализация ──────────────────────────
            val treeUri = Uri.parse(request.outputDirectoryUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileName = "${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)}.${request.format}"
            val mime = if (request.format == "wav") MIME_WAV else "application/octet-stream"
            createdUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, mime, fileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))

            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(createdUri!!)
                    ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
                output.use { os -> tempWav.inputStream().use { it.copyTo(os) } }
            }

            val displayName = queryDisplayName(createdUri!!) ?: fileName

            notification.showComplete(displayName, createdUri)
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it!!.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    displayName = displayName,
                    documentUri = createdUri.toString(),
                )
            }
            tempWav.delete()
            return Result.success()
        } catch (e: CancellationException) {
            // Отмена очереди: убираем временные файлы и активную запись статуса.
            tempWav.delete()
            TtsAudioQueue.updateState(appPreferences, jobId) { it!!.copy(status = TtsAudioJobStatus.FAILED, message = "Cancelled") }
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsAudio: EXPORT FAILED for $jobId")
            // Частично созданный файл в SAF не оставляем.
            createdUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            tempWav.delete()
            val message = context.getString(StringsR.string.tts_audio_export_failure_detail, e.message ?: "")
            notification.showError(message)
            TtsAudioQueue.updateState(appPreferences, jobId) {
                it!!.copy(status = TtsAudioJobStatus.FAILED, message = e.message ?: "")
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
            it!!.copy(status = TtsAudioJobStatus.FAILED, message = message)
        }
    }

    /**
     * Возвращает сырой текст главы по источнику.
     * ORIGINAL: тело из кэша (ChapterBody). TRANSLATED: закэшированный перевод
     * пары языков книги (ChapterTranslation) — тела с невалидным JSON пропускаются.
     * null означает «источник недоступен» (у воркера нет права качать).
     */
    private suspend fun fetchChapterText(
        appDatabase: AppDatabase,
        appPreferences: AppPreferences,
        request: TtsAudioExportRequest,
    ): String? {
        return if (request.source == TtsAudioSource.TRANSLATED) {
            val pair = appPreferences.translationPairForBook(request.novelUrl)
            if (pair.source.isBlank() || pair.target.isBlank()) return null
            val translation = appDatabase.chapterTranslationDao()
                .getTranslations(request.chapterUrl, pair.source, pair.target)
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
        val source = runCatching {
            TtsAudioSource.valueOf(sourceName)
        }.getOrNull() ?: return null
        val enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: ""
        val voiceId = inputData.getString(KEY_VOICE_ID) ?: ""
        val speed = inputData.getFloat(KEY_SPEED, 1f)
        val pitch = inputData.getFloat(KEY_PITCH, 1f)
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null
        val format = inputData.getString(KEY_FORMAT) ?: "wav"
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

    // То же ограничение, что у BookExportWorker: SAF режет длинные имена.
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "chapter" }

    companion object {
        const val TAG = "TtsAudioExport"
        const val MIME_WAV = "audio/wav"

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
    }
}