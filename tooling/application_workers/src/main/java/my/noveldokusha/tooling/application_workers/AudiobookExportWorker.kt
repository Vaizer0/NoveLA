package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.tooling.audiobook.AudiobookContentMode
import my.noveldokusha.tooling.audiobook.AudiobookExportRequest
import my.noveldokusha.tooling.audiobook.AudiobookExporter
import my.noveldokusha.tooling.audiobook.AudiobookFormat
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AudiobookExportEntryPoint {
        fun appDatabase(): AppDatabase
        fun appPreferences(): AppPreferences
        fun notificationsCenter(): NotificationsCenter
    }

    companion object {
        const val TAG = "AudiobookExport"

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
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        fun enqueue(context: Context, request: AudiobookExportRequest) {
            val data = workDataOf(
                KEY_BOOK_URL to request.bookUrl,
                KEY_BOOK_TITLE to request.bookTitle,
                KEY_FORMAT to request.format.name,
                KEY_CONTENT_MODE to request.contentMode.name,
                KEY_SOURCE_LANG to request.sourceLang,
                KEY_TARGET_LANG to request.targetLang,
                KEY_START_POSITION to request.startPosition,
                KEY_END_POSITION to request.endPosition,
                KEY_ENGINE_PACKAGE to request.enginePackage,
                KEY_VOICE_ID to request.voiceId,
                KEY_SPEED to request.speed,
                KEY_PITCH to request.pitch,
                KEY_VISUAL_URI to request.visualUri,
                KEY_VISUAL_SOURCE to request.visualSource?.name,
                KEY_VISUAL_SOURCE_NAME to request.visualSourceName,
                KEY_TREE_URI to request.treeUri,
            )
            val work = OneTimeWorkRequestBuilder<AudiobookExportWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, work)
        }
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AudiobookExportEntryPoint::class.java,
        )
        val appDatabase = entryPoint.appDatabase()
        val notificationsCenter = entryPoint.notificationsCenter()

        val request = readRequest() ?: return Result.failure()
        val notification = AudiobookExportNotification(request.bookTitle, context, notificationsCenter)
        val storage = SafAudiobookStorage(context)

        // Директория проверяется до тяжёлого синтеза: недоступный SAF не должен
        // стоить пользователю минут TTS.
        if (!withContext(Dispatchers.IO) { storage.isAccessible(request.treeUri) }) {
            Timber.e("AudiobookExport: SAF tree not accessible")
            notification.showError(context.getString(StringsR.string.audiobook_export_failed))
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
            notification.showError(context.getString(StringsR.string.audiobook_export_no_chapters))
            return Result.failure()
        }

        val outputDir = File(context.cacheDir, "audiobook_export/${request.jobId()}")
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        var foregroundSet = false
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
                        // Foreground поднимается при первых процентах, чтобы
                        // WorkManager не убил длинный синтез.
                        if (!foregroundSet) {
                            foregroundSet = true
                            runCatching { setForeground(buildForegroundInfo(notification)) }
                                .onFailure { Timber.w(it, "AudiobookExport: setForeground failed") }
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotifyAt >= PROGRESS_INTERVAL_MS || progress.percent >= 100) {
                            lastNotifyAt = now
                            notification.showProgress(progress.percent)
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
            notification.close()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AudiobookExport failed")
            notification.showError(context.getString(StringsR.string.audiobook_export_failed))
            Result.failure()
        } finally {
            outputDir.deleteRecursively()
        }
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
            // Частично записанные файлы не оставляем в папке пользователя.
            storage.deleteDocument(jsonDocument?.uri)
            storage.deleteDocument(audioDocument.uri)
            throw e
        }
        return CopiedFiles(audioDocument.uri, jsonDocument.uri)
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

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
