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
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import my.noveldokusha.tooling.application_workers.video.SafMp4Stager
import timber.log.Timber
import java.io.File

/**
 * Durable VIDEO stage. The SAF .part document is created before rendering and is preserved until a
 * complete MP4 is validated and renamed to its final .mp4 name.
 */
@RequiresApi(Build.VERSION_CODES.O)
class TtsVideoExportWorkerV2(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val parentUri = inputData.getString(KEY_PARENT_DIRECTORY_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "chapter.wav"
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val videoName = displayName.removeSuffix(".wav") + ".mp4"

        findValidFinal(parentUri, videoName)?.let { finalUri ->
            checkpointSuccess(prefs, jobId, finalUri)
            return Result.success()
        }

        val existingJob = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]
        existingJob?.videoStagingUri?.takeIf { it.isNotBlank() }?.let { stagedString ->
            val staged = Uri.parse(stagedString)
            if (SafMp4Stager.isValidMp4(context, staged)) {
                publishStaged(prefs, jobId, parentUri, videoName, staged)
                return Result.success()
            }
            SafMp4Stager.delete(context, staged)
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(videoStagingUri = "", videoStagingComplete = false, videoSizeBytes = 0L)
            }
        }

        if (!RENDER_MUTEX.tryLock()) return Result.retry()

        val wakeLock = acquireWakeLock(jobId)
        val tempDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
        val renderJson = File(tempDir, "$jobId-video.timeline.json")
        val tempVideo = File(tempDir, "$jobId-video.mp4.tmp")
        var stagingUri: Uri? = null
        var publishedUri: Uri? = null
        var lastPercent = -1
        var lastProgressAt = 0L

        fun publishProgress(percent: Int, sizeBytes: Long? = null, force: Boolean = false) {
            val p = percent.coerceIn(0, 100)
            val now = SystemClock.elapsedRealtime()
            val shouldPublish = force || p == 100 || lastPercent < 0 || (p != lastPercent && now - lastProgressAt >= PROGRESS_INTERVAL_MS)
            if (!shouldPublish) return
            lastPercent = p
            lastProgressAt = now
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.RUNNING,
                    phase = "VIDEO",
                    progress = p,
                    documentUri = "",
                    videoSizeBytes = sizeBytes ?: it.videoSizeBytes,
                    videoStagingUri = stagingUri?.toString() ?: it.videoStagingUri,
                    videoStagingComplete = false,
                    message = "",
                )
            }
            runCatching { setForegroundAsync(createForegroundInfo(p)) }
                .onFailure { Timber.w(it, "TtsVideoV2: notification update failed for $jobId") }
        }

        try {
            setForeground(getForegroundInfo())
            publishProgress(0, 0L, force = true)

            stagingUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    MIME_MP4,
                    buildStagingName(videoName, jobId),
                )
            } ?: throw IllegalStateException("Cannot create SAF staging document")

            withContext(NonCancellable) {
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(
                        status = TtsAudioJobStatus.RUNNING,
                        phase = "VIDEO",
                        progress = 0,
                        documentUri = "",
                        videoStagingUri = stagingUri.toString(),
                        videoStagingComplete = false,
                        videoSizeBytes = 0L,
                        workRequestId = id.toString(),
                        message = "",
                    )
                }
            }

            copyUriToFile(Uri.parse(timelineUri), renderJson)
            currentCoroutineContext().ensureActive()

            context.contentResolver.openInputStream(Uri.parse(audioUri))?.use { wavInput ->
                CinematicVideoExporter(context).export(
                    wavInput = wavInput,
                    timelineFile = renderJson,
                    outputFile = tempVideo,
                    onProgress = { fraction ->
                        val percent = (fraction * 100f).toInt().coerceIn(0, 99)
                        publishProgress(percent, tempVideo.length(), percent == 0)
                    },
                    onSizeBytes = { bytes -> publishProgress(lastPercent.coerceAtLeast(0), bytes) },
                )
            } ?: throw IllegalStateException("Cannot read $audioUri")
            currentCoroutineContext().ensureActive()
            require(tempVideo.isFile && tempVideo.length() > 0L) { "Generated MP4 is empty" }

            SafMp4Stager.remuxLocalMp4ToSaf(context, tempVideo, stagingUri!!)
            currentCoroutineContext().ensureActive()
            val stagedSize = SafMp4Stager.querySize(context, stagingUri!!)
            require(stagedSize > 0L) { "SAF staging MP4 is empty" }

            withContext(NonCancellable) {
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(videoStagingUri = stagingUri.toString(), videoStagingComplete = true, videoSizeBytes = stagedSize, progress = 99)
                }
            }

            publishedUri = publishStaged(prefs, jobId, parentUri, videoName, stagingUri!!)
            stagingUri = null
            withContext(NonCancellable) {
                checkpointSuccess(prefs, jobId, publishedUri!!)
            }
            return Result.success()
        } catch (e: CancellationException) {
            renderJson.delete()
            tempVideo.delete()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsVideoV2: generation failed for $jobId attempt=${runAttemptCount + 1}")
            renderJson.delete()
            tempVideo.delete()

            if (publishedUri != null && SafMp4Stager.isValidMp4(context, publishedUri!!)) {
                checkpointSuccess(prefs, jobId, publishedUri!!)
                return Result.success()
            }

            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                val persistedStagingUri = stagingUri ?: prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]
                    ?.videoStagingUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Uri::parse)
                val recoveredStagingComplete = stagingUri?.let { SafMp4Stager.isValidMp4(context, it) }
                TtsAudioQueue.updateState(prefs, jobId) { current ->
                    current?.copy(
                        status = TtsAudioJobStatus.QUEUED,
                        phase = "VIDEO",
                        progress = current.progress.coerceIn(0, 99),
                        documentUri = "",
                        workRequestId = id.toString(),
                        videoStagingUri = persistedStagingUri?.toString() ?: current.videoStagingUri,
                        videoStagingComplete = recoveredStagingComplete ?: current.videoStagingComplete,
                        message = "Retrying video generation…",
                    )
                }
                return Result.retry()
            }

            restoreAudioState(prefs, jobId, audioUri, displayName, e.message ?: "")
            return Result.failure()
        } finally {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            renderJson.delete()
            tempVideo.delete()
            RENDER_MUTEX.unlock()
        }
    }

    private suspend fun findValidFinal(parentUri: Uri, videoName: String): Uri? {
        val final = findChildDocument(parentUri, videoName) ?: return null
        return if (SafMp4Stager.isValidMp4(context, final)) final else null
    }

    private suspend fun publishStaged(
        prefs: AppPreferences,
        jobId: String,
        parentUri: Uri,
        videoName: String,
        stagedUri: Uri,
    ): Uri {
        val existing = findValidFinal(parentUri, videoName)
        if (existing != null) {
            SafMp4Stager.delete(context, stagedUri)
            checkpointSuccess(prefs, jobId, existing)
            return existing
        }
        val renamed = withContext(Dispatchers.IO) {
            DocumentsContract.renameDocument(context.contentResolver, stagedUri, videoName)
        } ?: throw IllegalStateException("Cannot rename staged MP4 to $videoName")
        require(SafMp4Stager.isValidMp4(context, renamed)) { "Published MP4 failed validation" }
        return renamed
    }

    private suspend fun checkpointSuccess(prefs: AppPreferences, jobId: String, uri: Uri) {
        val size = SafMp4Stager.querySize(context, uri)
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "VIDEO",
                progress = 100,
                documentUri = uri.toString(),
                workRequestId = "",
                videoSizeBytes = size,
                videoStagingUri = "",
                videoStagingComplete = false,
                videoStopReason = "",
                videoRecoveryAttempts = 0,
                message = "",
            )
        }
        try {
            setForegroundAsync(createForegroundInfo(100))
        } catch (error: Exception) {
            Timber.w(error, "TtsVideoV2: final notification update failed for $jobId")
        }
    }

    private suspend fun findChildDocument(parentUri: Uri, displayName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri)),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(mimeCol) == MIME_MP4) {
                        return@runCatching DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idCol))
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun buildStagingName(videoName: String, jobId: String): String {
        val base = videoName.removeSuffix(".mp4")
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$base.$safeJobId.mp4.part"
    }

    private fun acquireWakeLock(jobId: String): PowerManager.WakeLock? {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:tts-video-v2:$jobId").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        }.onFailure { Timber.w(it, "TtsVideoV2: wake lock unavailable for $jobId") }.getOrNull()
    }

    private fun createForegroundInfo(progressPercent: Int? = null): ForegroundInfo {
        ensureNotificationChannel()
        val determinate = progressPercent != null
        val progress = progressPercent?.coerceIn(0, 100) ?: 0
        val notification = NotificationCompat.Builder(context, VIDEO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NoveLA")
            .setContentText(if (determinate) "Generating chapter video — $progress%" else "Generating chapter video…")
            .setSubText(if (determinate) "$progress%" else null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, !determinate)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(VIDEO_NOTIFICATION_ID, notification, serviceType)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(VIDEO_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(VIDEO_CHANNEL_ID, "Video generation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Required while NoveLA generates a chapter video in the background"
                    setShowBadge(false)
                },
            )
        }
    }

    private suspend fun restoreAudioState(prefs: AppPreferences, jobId: String, audioUri: String, displayName: String, message: String) {
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
                videoStagingUri = "",
                videoStagingComplete = false,
                message = message,
            )
        }
    }

    private suspend fun copyUriToFile(uri: Uri, target: File) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
        } ?: throw IllegalStateException("Cannot read $uri")
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_PARENT_DIRECTORY_URI = "parentDirectoryUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_DISPLAY_NAME = "displayName"
        const val MIME_MP4 = "video/mp4"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_RETRY_ATTEMPTS = 4
        const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
        const val VIDEO_NOTIFICATION_ID = 0x4E56
        const val VIDEO_CHANNEL_ID = "tts_video_generation"
        val RENDER_MUTEX = Mutex()
    }
}
