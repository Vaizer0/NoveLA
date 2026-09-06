package my.noveldokusha.tooling.application_workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/** Long-running, foreground-backed VIDEO stage. It consumes durable WAV+timeline and never deletes them. */
class TtsVideoExportWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val parentDirectoryUri = inputData.getString(KEY_PARENT_DIRECTORY_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "chapter.wav"
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()

        // WorkManager's CoroutineWorker must explicitly become foreground work; otherwise
        // the OS may stop a multi-minute/hour video encode once the app is backgrounded.
        setForeground(createForegroundInfo())

        val wakeLock = acquireVideoWakeLock(jobId)
        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val renderJson = File(tempDir, "$jobId-video.timeline.json")
        val tempVideo = File(tempDir, "$jobId-video.mp4.tmp")
        var partUri: Uri? = null
        var publishedVideoUri: Uri? = null

        var lastProgressPercent = -1
        var lastProgressAtMs = 0L

        fun publishProgress(percent: Int, videoSizeBytes: Long? = null, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val shouldPublish = force ||
                percent == 100 ||
                lastProgressPercent < 0 ||
                percent != lastProgressPercent && now - lastProgressAtMs >= PROGRESS_UPDATE_INTERVAL_MS
            if (!shouldPublish) return
            lastProgressPercent = percent
            lastProgressAtMs = now
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.RUNNING,
                    phase = "VIDEO",
                    progress = percent,
                    documentUri = "",
                    videoSizeBytes = videoSizeBytes ?: it.videoSizeBytes,
                )
            }
        }

        try {
            publishProgress(0, 0L, force = true)

            copyUriToFile(Uri.parse(timelineUri), renderJson)
            ensureActive()

            val wavUri = Uri.parse(audioUri)
            context.contentResolver.openInputStream(wavUri)?.use { wavInput ->
                CinematicVideoExporter(context).export(
                    wavInput = wavInput,
                    timelineFile = renderJson,
                    outputFile = tempVideo,
                    onProgress = { fraction ->
                        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
                        publishProgress(percent, force = percent == 100)
                    },
                    onSizeBytes = { bytes ->
                        publishProgress(lastProgressPercent.coerceAtLeast(0), bytes)
                    },
                )
            } ?: throw IllegalStateException("Cannot read $wavUri")
            ensureActive()

            val parentUri = Uri.parse(parentDirectoryUri)
            val videoName = displayName.removeSuffix(".wav") + ".mp4"
            val partName = "$videoName.part"
            partUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_MP4, partName)
            } ?: throw IllegalStateException("Cannot create MP4 staging document")

            copyFileToUri(tempVideo, partUri!!)
            ensureActive()

            publishedVideoUri = withContext(Dispatchers.IO) {
                DocumentsContract.renameDocument(context.contentResolver, partUri!!, videoName)
            } ?: throw IllegalStateException("Cannot publish MP4 document")
            partUri = null
            ensureActive()

            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.SUCCESS,
                    phase = "VIDEO",
                    progress = 100,
                    documentUri = publishedVideoUri.toString(),
                    workRequestId = "",
                    videoSizeBytes = tempVideo.length(),
                    message = "",
                )
            }
            return Result.success()
        } catch (e: CancellationException) {
            runCatching { partUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            runCatching { publishedVideoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderJson.delete(); tempVideo.delete()
            restoreAudioState(prefs, jobId, audioUri, displayName)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsVideo: video generation failed for $jobId attempt=${runAttemptCount + 1}")
            runCatching { partUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            runCatching { publishedVideoUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderJson.delete(); tempVideo.delete()
            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(
                        status = TtsAudioJobStatus.QUEUED,
                        phase = "VIDEO",
                        progress = 0,
                        documentUri = "",
                        workRequestId = id.toString(),
                        videoSizeBytes = 0L,
                        message = "Retrying video generation…",
                    )
                }
                return Result.retry()
            }
            restoreAudioState(prefs, jobId, audioUri, displayName, e.message ?: "")
            return Result.failure()
        } finally {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            runCatching { partUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            renderJson.delete(); tempVideo.delete()
        }
    }

    private fun acquireVideoWakeLock(jobId: String): PowerManager.WakeLock? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${context.packageName}:tts-video:$jobId",
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        }.onFailure { Timber.w(it, "TtsVideo: unable to acquire video wake lock for $jobId") }.getOrNull()
    }

    private suspend fun createForegroundInfo(): ForegroundInfo = withContext(Dispatchers.Default) {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(context, VIDEO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NoveLA")
            .setContentText("Generating chapter video…")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ForegroundInfo(VIDEO_NOTIFICATION_ID, notification, serviceType)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(VIDEO_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    VIDEO_CHANNEL_ID,
                    "Video generation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Required while NoveLA generates a chapter video in the background"
                    setShowBadge(false)
                },
            )
        }
    }

    private suspend fun restoreAudioState(
        prefs: AppPreferences,
        jobId: String,
        audioUri: String,
        displayName: String,
        message: String = "",
    ) {
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "AUDIO",
                progress = 100,
                documentUri = "",
                audioUri = audioUri,
                workRequestId = "",
                videoSizeBytes = 0L,
                displayName = displayName,
                message = message,
            )
        }
    }

    private suspend fun copyUriToFile(uri: Uri, target: File) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
        } ?: throw IllegalStateException("Cannot read $uri")
    }

    private suspend fun copyFileToUri(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_SIZE) }
        } ?: throw IllegalStateException("Cannot write $uri")
    }

    private suspend fun ensureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_PARENT_DIRECTORY_URI = "parentDirectoryUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
        private const val MIME_MP4 = "video/mp4"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val MAX_RETRY_ATTEMPTS = 4
        private const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
        private const val VIDEO_NOTIFICATION_ID = 0x4E56
        private const val VIDEO_CHANNEL_ID = "tts_video_generation"
    }
}
