package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.isCoverValid
import my.noveldokusha.core.isHttpsUrl
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.data.CoverRepository
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.tooling.audiobook.AudiobookContentMode
import my.noveldokusha.tooling.audiobook.AudiobookExportRequest
import my.noveldokusha.tooling.audiobook.AudiobookExporter
import my.noveldokusha.tooling.audiobook.AudiobookExportDebug
import my.noveldokusha.tooling.audiobook.AudiobookExportProgressBus
import my.noveldokusha.tooling.audiobook.AudiobookFormat
import my.noveldokusha.tooling.audiobook.AudiobookStage
import my.noveldokusha.tooling.audiobook.ChapterContentProvider
import my.noveldokusha.tooling.audiobook.SafAudiobookStorage
import my.noveldokusha.tooling.audiobook.SafDocument
import my.noveldokusha.tooling.audiobook.VisualSource
import my.noveldokusha.tooling.audiobook.jobId
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Фоновый экспорт аудиокниги (WAV+JSON или MP4+JSON).
 *
 * TTS синтезирует главы один раз, PCM потоково склеивается в единый WAV,
 * а MP4 собирается повторным использованием короткого видеосегмента.
 * Итоговые файлы кладутся в `<выбранная папка>/Audiobooks/<Книга>/`.
 */
class AudiobookExportWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    private var wakeLock: PowerManager.WakeLock? = null

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AudiobookExportEntryPoint {
        fun appDatabase(): AppDatabase
        fun appPreferences(): AppPreferences
        fun notificationsCenter(): NotificationsCenter
        fun appFileResolver(): AppFileResolver
        fun coverRepository(): CoverRepository
    }

    companion object {
        const val TAG = "AudiobookExport"

        /** Ключ прогресса в `Data` (0..100) для наблюдения из UI. */
        const val KEY_PROGRESS = "audiobook_progress_percent"

        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
        private const val KEY_BOOK_URL = "book_url"
        private const val KEY_BOOK_TITLE = "book_title"
        private const val KEY_FORMAT = "format"
        private const val KEY_CONTENT_MODE = "content_mode"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_START_POSITION = "start_position"
        private const val KEY_END_POSITION = "end_position"
        private const val KEY_ENGINE_PACKAGE = "engine_package"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_SPEED = "speed"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VISUAL_URI = "visual_uri"
        private const val KEY_VISUAL_SOURCE = "visual_source"
        private const val KEY_VISUAL_SOURCE_NAME = "visual_source_name"
        private const val KEY_TREE_URI = "tree_uri"

        fun cancelTask(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        fun enqueue(context: Context, request: AudiobookExportRequest) {
            val data = Data.Builder()
                .putString(KEY_BOOK_URL, request.bookUrl)
                .putString(KEY_BOOK_TITLE, request.bookTitle)
                .putString(KEY_FORMAT, request.format.name)
                .putString(KEY_CONTENT_MODE, request.contentMode.name)
                .putString(KEY_SOURCE_LANG, request.sourceLang)
                .putString(KEY_TARGET_LANG, request.targetLang)
                .putInt(KEY_START_POSITION, request.startPosition)
                .putInt(KEY_END_POSITION, request.endPosition)
                .putString(KEY_ENGINE_PACKAGE, request.enginePackage)
                .putString(KEY_VOICE_ID, request.voiceId)
                .putFloat(KEY_SPEED, request.speed)
                .putFloat(KEY_PITCH, request.pitch)
                .putString(KEY_TREE_URI, request.treeUri)
                .apply {
                    request.visualUri?.let { putString(KEY_VISUAL_URI, it) }
                    request.visualSource?.let { putString(KEY_VISUAL_SOURCE, it.name) }
                    request.visualSourceName?.let { putString(KEY_VISUAL_SOURCE_NAME, it) }
                }
                .build()
            val work = OneTimeWorkRequestBuilder<AudiobookExportWorker>()
                .setInputData(data)
                .addTag(TAG)
                .build()
            // Уникальное имя по job'у: экспорт другой книги или диапазона
            // не должен отменять уже запущенный экспорт (REPLACE иначе его убьёт).
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$TAG-${request.jobId()}", ExistingWorkPolicy.REPLACE, work)
        }
    }

    override suspend fun doWork(): Result {
        AudiobookExportDebug.init(context)
        AudiobookExportDebug.log("doWork started (attempt=$runAttemptCount, id=$id)")
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AudiobookExportEntryPoint::class.java,
        )
        val appDatabase = entryPoint.appDatabase()
        val notificationsCenter = entryPoint.notificationsCenter()
        val appFileResolver = entryPoint.appFileResolver()
        val coverRepository = entryPoint.coverRepository()

        val storedRequest = readRequest()
        if (storedRequest == null) {
            AudiobookExportDebug.log("readRequest returned null; aborting")
            AudiobookExportProgressBus.reportError("Invalid audiobook export request")
            return Result.failure()
        }
        val notification = AudiobookExportNotification(
            storedRequest.bookTitle,
            context,
            notificationsCenter,
            storedRequest.format,
        )
        val storage = SafAudiobookStorage(context)

        // Foreground поднимается сразу: длинный синтез не должен ждать
        // первого процента, иначе система может остановить воркер.
        runCatching { setForeground(buildForegroundInfo(notification)) }
            .onFailure {
                AudiobookExportDebug.log("early setForeground failed", it)
                Timber.w(it, "AudiobookExport: early setForeground failed")
            }
        acquireWakeLock()
        AudiobookExportDebug.log(
            "request: book='${storedRequest.bookTitle}' format=${storedRequest.format} " +
                "chapters=${storedRequest.startPosition}..${storedRequest.endPosition} " +
                "engine='${storedRequest.enginePackage}' voice='${storedRequest.voiceId}'",
        )

        // Показываем «0%» сразу, чтобы экран не выглядел зависшим до первого абзаца.
        AudiobookExportProgressBus.publish(
            percent = 0,
            format = storedRequest.format,
            stage = AudiobookStage.PREPARING,
        )

        // MP4 без выбранного визуала: подставляем обложку книги.
        val request = if (
            storedRequest.format == AudiobookFormat.MP4 &&
            storedRequest.visualUri.isNullOrBlank()
        ) {
            val coverUri = withContext(Dispatchers.IO) {
                resolveCoverImageUri(
                    appDatabase = appDatabase,
                    appFileResolver = appFileResolver,
                    coverRepository = coverRepository,
                    bookUrl = storedRequest.bookUrl,
                )
            }
            if (coverUri == null) {
                // Обложки нет — собрать визуальный ряд не из чего.
                val message = context.getString(StringsR.string.audiobook_export_mp4_needs_visual)
                AudiobookExportDebug.log("MP4 export aborted: no cover and no visual source")
                notification.showError(message)
                AudiobookExportProgressBus.reportError(message)
                return Result.failure()
            }
            storedRequest.copy(
                visualUri = coverUri.toString(),
                visualSource = VisualSource.IMAGE,
                visualSourceName = "cover",
            )
        } else {
            storedRequest
        }

        // Директория проверяется до тяжёлого синтеза: недоступный SAF не должен
        // стоить пользователю минут TTS.
        if (!withContext(Dispatchers.IO) { storage.isAccessible(request.treeUri) }) {
            AudiobookExportDebug.log("SAF tree not accessible: ${request.treeUri}")
            val message = context.getString(StringsR.string.audiobook_export_failed)
            notification.showError(message)
            AudiobookExportProgressBus.reportError(message)
            return Result.failure()
        }

        val chapters = withContext(Dispatchers.IO) {
            ChapterContentProvider(appDatabase).loadChapters(
                bookUrl = request.bookUrl,
                startPosition = request.startPosition,
                endPosition = request.endPosition,
                contentMode = request.contentMode,
                sourceLang = request.sourceLang,
                targetLang = request.targetLang,
            )
        }
        if (chapters.isEmpty()) {
            AudiobookExportDebug.log("no chapters to export for ${request.bookUrl}")
            val message = context.getString(StringsR.string.audiobook_export_no_chapters)
            notification.showError(message)
            AudiobookExportProgressBus.reportError(message)
            return Result.failure()
        }

        // ВАЖНО: папка не должна совпадать с tempDir экспортёра
        // (`cacheDir/audiobook_export/<job>`), иначе экспортёр удалит
        // готовые файлы вместе со своей временной папкой.
        val outputDir = File(context.cacheDir, "audiobook_output/${request.jobId()}")
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        var lastNotifyAt = 0L
        val exporter = AudiobookExporter(context)

        return try {
            val result = withContext(Dispatchers.IO) {
                exporter.export(
                    request = request,
                    chapters = chapters,
                    outputDir = outputDir,
                    onProgress = { progress ->
                        coroutineContext.ensureActive()
                        // Живой прогресс экрана — сразу, без троттлинга, чтобы
                        // процент не «залипал» на 0%.
                        AudiobookExportProgressBus.publish(
                            percent = progress.percent,
                            format = request.format,
                            stage = progress.stage,
                        )
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotifyAt >= PROGRESS_INTERVAL_MS || progress.percent >= 100) {
                            lastNotifyAt = now
                            notification.showProgress(progress.percent)
                            runCatching {
                                setProgress(Data.Builder().putInt(KEY_PROGRESS, progress.percent).build())
                            }.onFailure {
                                AudiobookExportDebug.log("setProgress failed", it)
                                Timber.w(it, "AudiobookExport: setProgress failed")
                            }
                        }
                    },
                )
            }

            coroutineContext.ensureActive()
            val copied = withContext(Dispatchers.IO) {
                copyResultToSaf(storage, request, result.audioFile, result.jsonFile)
            }
            notification.showComplete(result.audioFile.name, copied.audioUri)
            Result.success()
        } catch (e: CancellationException) {
            AudiobookExportDebug.log("export cancelled")
            notification.close()
            throw e
        } catch (e: Exception) {
            AudiobookExportDebug.log("export failed", e)
            Timber.e(e, "AudiobookExport failed")
            val reason = e.message?.takeIf { it.isNotBlank() }
            val message = if (reason != null) {
                "${context.getString(StringsR.string.audiobook_export_failed)}: $reason"
            } else {
                context.getString(StringsR.string.audiobook_export_failed)
            }
            notification.showError(message)
            AudiobookExportProgressBus.reportError(message)
            Result.failure()
        } finally {
            outputDir.deleteRecursively()
            AudiobookExportProgressBus.clear()
            releaseWakeLock()
            AudiobookExportDebug.log("doWork finished")
        }
    }

    /**
     * Держим CPU в бодрствовании на время экспорта: foreground-сервис
     * WorkManager не удерживает wakelock, и при выключенном экране
     * синтез может «заснуть» посреди книги.
     */
    private fun acquireWakeLock() {
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoveLA:AudiobookExport")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_LOCK_MS)
                }
            AudiobookExportDebug.log("wake lock acquired")
        }.onFailure {
            AudiobookExportDebug.log("wake lock acquire failed", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            AudiobookExportDebug.log("wake lock release failed", it)
        }
        wakeLock = null
    }

    private fun buildForegroundInfo(notification: AudiobookExportNotification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notification.notificationId,
                notification.foregroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notification.notificationId, notification.foregroundNotification())
        }

    private data class CopiedFiles(val audioUri: Uri?, val jsonUri: Uri?)

    private fun copyResultToSaf(
        storage: SafAudiobookStorage,
        request: AudiobookExportRequest,
        audioFile: File,
        jsonFile: File,
    ): CopiedFiles {
        val folder = storage.ensureNovelFolder(request.treeUri, request.bookTitle)
        val audioMime = if (request.format == AudiobookFormat.MP4) {
            SafAudiobookStorage.MP4_MIME
        } else {
            SafAudiobookStorage.WAV_MIME
        }
        val audioDocument = storage.createDocument(folder, audioFile.name, audioMime)
        var jsonDocument: SafDocument? = null
        try {
            storage.copyToSaf(audioFile, audioDocument.uri)
            jsonDocument = storage.createDocument(folder, jsonFile.name, SafAudiobookStorage.JSON_MIME)
            storage.copyToSaf(jsonFile, jsonDocument.uri)
        } catch (e: Exception) {
            // Удаляем только то, что создали сами: переиспользованный документ
            // мог принадлежать прошлому удачному экспорту.
            if (jsonDocument?.created == true) storage.deleteDocument(jsonDocument.uri)
            if (audioDocument.created) storage.deleteDocument(audioDocument.uri)
            throw e
        }
        return CopiedFiles(audioDocument.uri, jsonDocument.uri)
    }

    /**
     * Локальный `file://`-Uri обложки книги для визуала MP4.
     *
     * Приоритет — уже скачанный кэш-файл; если его нет, обложка докачивается
     * через [CoverRepository]. Возвращает null, если обложки нет вовсе.
     */
    private suspend fun resolveCoverImageUri(
        appDatabase: AppDatabase,
        appFileResolver: AppFileResolver,
        coverRepository: CoverRepository,
        bookUrl: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val coverFile = appFileResolver.getStorageBookCoverImageFile(
            appFileResolver.getLocalBookFolderName(bookUrl),
        )
        if (isCoverValid(coverFile)) return@withContext Uri.fromFile(coverFile)

        val remoteUrl = appDatabase.libraryDao().get(bookUrl)
            ?.coverImageUrl
            ?.takeIf { it.isHttpsUrl }
        if (remoteUrl != null) {
            coverRepository.ensureCover(coverFile, remoteUrl)
            if (isCoverValid(coverFile)) return@withContext Uri.fromFile(coverFile)
        }
        null
    }

    private fun readRequest(): AudiobookExportRequest? {
        val bookUrl = inputData.getString(KEY_BOOK_URL) ?: return null
        val bookTitle = inputData.getString(KEY_BOOK_TITLE) ?: return null
        val format = runCatching {
            AudiobookFormat.valueOf(inputData.getString(KEY_FORMAT) ?: AudiobookFormat.WAV.name)
        }.getOrDefault(AudiobookFormat.WAV)
        val contentMode = runCatching {
            AudiobookContentMode.valueOf(
                inputData.getString(KEY_CONTENT_MODE) ?: AudiobookContentMode.ORIGINAL.name,
            )
        }.getOrDefault(AudiobookContentMode.ORIGINAL)
        val treeUri = inputData.getString(KEY_TREE_URI) ?: return null
        val visualSource = inputData.getString(KEY_VISUAL_SOURCE)?.let {
            runCatching { VisualSource.valueOf(it) }.getOrNull()
        }

        return AudiobookExportRequest(
            bookUrl = bookUrl,
            bookTitle = bookTitle,
            format = format,
            contentMode = contentMode,
            sourceLang = inputData.getString(KEY_SOURCE_LANG) ?: "",
            targetLang = inputData.getString(KEY_TARGET_LANG) ?: "",
            startPosition = inputData.getInt(KEY_START_POSITION, 0),
            endPosition = inputData.getInt(KEY_END_POSITION, 0),
            enginePackage = inputData.getString(KEY_ENGINE_PACKAGE) ?: "",
            voiceId = inputData.getString(KEY_VOICE_ID) ?: "",
            speed = inputData.getFloat(KEY_SPEED, 1f),
            pitch = inputData.getFloat(KEY_PITCH, 1f),
            visualUri = inputData.getString(KEY_VISUAL_URI),
            visualSource = visualSource,
            visualSourceName = inputData.getString(KEY_VISUAL_SOURCE_NAME),
            treeUri = treeUri,
        )
    }
}
