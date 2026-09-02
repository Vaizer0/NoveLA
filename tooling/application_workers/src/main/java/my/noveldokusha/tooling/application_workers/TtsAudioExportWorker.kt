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
import androidx.work.workDataOf
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
import kotlin.io.DEFAULT_BUFFER_SIZE

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

        // V1 умеет генерировать только WAV/PCM: любой другой формат (например
        // "m4a") отклоняется явно ДО синтеза — никогда не создаём файл с чужим
        // расширением (.m4a), содержащий WAV-данные. M4A остаётся опцией V2.
        if (request.format != TtsAudioFormat.WAV) {
            Timber.e("TtsAudio: unsupported format '${request.format}' for job $jobId (V1 supports WAV only)")
            fail(appPreferences, jobId, notification,
                context.getString(StringsR.string.tts_audio_export_unsupported_format, request.format))
            return Result.failure()
        }

        // Временные файлы для WAV — в cacheDir, не трогают user-данные.
        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val tempWav = File(tempDir, "$jobId.wav")
        var createdUri: Uri? = null
        var timelineUri: Uri? = null

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

            // Per-novel подпапка внутри постоянной обёртки "NoveLA Audio" в выбранном
            // корне: <root>/NoveLA Audio/<Novel Title>/. Не смогли создать — ошибка.
            val treeUri = Uri.parse(request.outputDirectoryUri)
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

            // ── Имена файлов ─────────────────────────────────────────────────────
            // fileName снабжается суффиксом источника, чтобы Original и Translated
            // одной главы не коллизировали в одной папке (иначе SAF добавит "(1)").
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
            // Парный timeline-файл: "<audio-basename>.timeline.json".
            val timelineFileName = "${fileName.removeSuffix(".${request.format}")}.timeline.json"

            // ── Синтез в temp WAV ───────────────────────────────────────────────
            // Диапазон прогресса: синтез занимает 0..89%, копирование в SAF — 90..99%,
            // финал — 100%. Синтез взвешен по символам текста. Репортим только при
            // смене процента (≤100 записей), уведомление троттлим ≥1с.
            val report = progressReporter(appPreferences, jobId, notification)
            val result = TtsAudioExporter(context).exportAudio(
                request = request,
                paragraphs = paragraphs,
                destFile = tempWav,
                // Имя, под которым WAV реально будет сохранён (для timeline.chapter.audioFile).
                audioFileName = fileName,
            ) { fraction ->
                report(89 + (fraction * 10).toInt().coerceIn(0, 9))
            }

            val tempWavSize = tempWav.length()

            // ── Копирование в SAF-папку и финализация ──────────────────────────
            val mime = if (request.format == TtsAudioFormat.WAV) MIME_WAV else "application/octet-stream"
            val parentUri = novelFolderUri
            createdUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, mime, fileName)
            } ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))

            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(createdUri!!)
                    ?: throw TtsExportException(context.getString(StringsR.string.tts_audio_export_file_error))
                output.use { os ->
                    tempWav.inputStream().use { input ->
                        // Копия больших файлов может быть долгой — ведём её прогрессом
                        // 90..99 по байтам, чтобы индикатор не «зависал» на 89%.
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            os.write(buffer, 0, read)
                            copied += read
                            if (tempWavSize > 0) {
                                report(
                                    90 + ((copied * 10) / tempWavSize).toInt().coerceIn(0, 9)
                                )
                            }
                        }
                    }
                }
            }

            // ── Парный timeline рядом с аудио ─────────────────────────────────────
            // Успешный синхронизированный экспорт обязан содержать timeline; если
            // записать его не удалось — это провал синхронизированного экспорта.
            val timelineJson = timelineToJson(result.timeline)
            timelineUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    MIME_JSON,
                    timelineFileName,
                )
            } ?: throw TtsExportException(
                context.getString(StringsR.string.tts_audio_export_file_error)
            )
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
            // Отмена очереди (пользователь): это не ошибка. Убираем временные файлы,
            // помечаем запись CANCELLED (non-error) и закрываем уведомление.
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
            // Частично созданные файлы в SAF не оставляем (и WAV, и парный timeline).
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
     * Троттлит публикацию прогресса: персистит в состояние и WorkManager только
     * при смене процента (≤100 записей), уведомление — не чаще раза в секунду.
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
            Timber.d("TtsAudio progress $clamped%")
            runCatching { setProgressAsync(workDataOf(KEY_PROGRESS to clamped)) }
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
        // ASK_EVERY_TIME — только «дефолтная» настройка; в воркере это не источник.
        if (source == TtsAudioSource.ASK_EVERY_TIME) return null
        val enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: ""
        val voiceId = inputData.getString(KEY_VOICE_ID) ?: ""
        val speed = inputData.getFloat(KEY_SPEED, 1f)
        val pitch = inputData.getFloat(KEY_PITCH, 1f)
        val outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI) ?: return null
        val format = inputData.getString(KEY_FORMAT) ?: TtsAudioFormat.WAV
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
    private fun sanitize(name: String, fallback: String = "chapter"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { fallback }

    /**
     * Возвращает document URI папки книги: <root>/NoveLA Audio/<Novel Title>/.
     * Папка и обёртка создаются при отсутствии; null — если недоступна/не создаётся.
     * Ищем по имени сгенерированную физически (SAF не даёт «find», только листинг).
     */
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

    /** Ищет папку [folderName] среди детей [parentDocId] или создаёт её. */
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

        // Пытаемся создать. Если create вернул null или документ уже существует
        // (гоночный случай — e.g. другой воркер той же очереди), перечитываем
        // листинг один раз, прежде чем объявлять неудачу.
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

        // Ретрай по листингу: папку мог создать параллельный экспорт этого же романа.
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

        /** Постоянная папка-обёртка для аудио внутри выбранного корня. */
        const val WRAPPER_FOLDER_NAME = "NoveLA Audio"

        /** Ключ прогресса (0..100) в WorkManager Data. */
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
    }
}