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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import timber.log.Timber
import java.io.File

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

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val parentDirectoryUri = inputData.getString(KEY_PARENT_DIRECTORY_URI)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "chapter.wav"
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()

        // Do not hold a foreground service or consume a long-running execution slot while waiting
        // for another chapter's 1080p renderer. Contending workers retry through WorkManager.
        if (!VIDEO_RENDER_MUTEX.tryLock()) {
            TtsAudioQueue.updateState(prefs, jobId) {
                it?.copy(
                    status = TtsAudioJobStatus.QUEUED,
                    phase = "VIDEO",
                    progress = 0,
                    documentUri = "",
                    message = "Waiting for another video export…",
                )
            }
            return Result.retry()
        }

        try {
            setForeground(getForegroundInfo())

            val parentUri = Uri.parse(parentDirectoryUri)
            val videoName = displayName.removeSuffix(".wav") + ".mp4"
            val currentJob = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]

            // Recover a final URI already stored in the durable job state. This avoids rerendering
            // when the process died after publication but before the final preference checkpoint.
            currentJob?.documentUri?.takeIf { it.isNotBlank() }?.let { documentUriString ->
                val documentUri = Uri.parse(documentUriString)
                val sizeBytes = queryDocumentSize(documentUri)
                if (sizeBytes > 0L) {
                    Timber.i("TtsVideo: recovered checkpointed MP4 for $jobId from $documentUri")
                    checkpointVideoSuccess(prefs, jobId, documentUriString, sizeBytes)
                    return Result.success()
                }
            }

            // The staging URI is persisted before copying starts. Only a staging document explicitly
            // marked complete is eligible for recovery; an incomplete one is safely discarded and the
            // video is rendered again from the durable WAV+timeline.
            currentJob?.videoStagingUri?.takeIf { it.isNotBlank() }?.let { stagingUriString ->
                val stagingUri = Uri.parse(stagingUriString)
                if (currentJob.videoStagingComplete && queryDocumentSize(stagingUri) > 0L) {
                    val recovered = runCatching {
                        withContext(Dispatchers.IO) {
                            DocumentsContract.renameDocument(context.contentResolver, stagingUri, videoName)
                        }
                    }.getOrNull()
                    if (recovered != null) {
                        val sizeBytes = queryDocumentSize(recovered)
                        if (sizeBytes > 0L) {
                            Timber.i("TtsVideo: recovered published MP4 for $jobId from staging URI $recovered")
                            checkpointVideoSuccess(prefs, jobId, recovered.toString(), sizeBytes)
                            return Result.success()
                        }
                    }
                }
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, stagingUri) }
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(videoStagingUri = "", videoStagingComplete = false)
                }
            }

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
                        message = "",
                    )
                }
                runCatching {
                    setForegroundAsync(createForegroundInfo(percent))
                }.onFailure { error ->
                    Timber.w(error, "TtsVideo: failed to update notification progress for $jobId")
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
                require(tempVideo.length() > 0L) { "Generated MP4 is empty" }

                val stagingName = buildStagingName(videoName, jobId)
                partUri = withContext(Dispatchers.IO) {
                    DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_MP4, stagingName)
                } ?: throw IllegalStateException("Cannot create MP4 staging document")

                // Persist the staging URI before the potentially long SAF copy so process death can
                // deterministically recover or discard this exact document without touching old MP4s.
                val durablePartUri = partUri!!.toString()
                withContext(NonCancellable) {
                    TtsAudioQueue.updateState(prefs, jobId) {
                        it?.copy(
                            videoStagingUri = durablePartUri,
                            videoStagingComplete = false,
                            videoSizeBytes = 0L,
                        )
                    }
                }

                copyFileToUri(tempVideo, partUri!!)
                ensureActive()

                // A complete staging file may safely survive worker cancellation until publication is
                // retried. This checkpoint intentionally happens before rename.
                withContext(NonCancellable) {
                    TtsAudioQueue.updateState(prefs, jobId) {
                        it?.copy(
                            videoStagingUri = durablePartUri,
                            videoStagingComplete = true,
                            videoSizeBytes = tempVideo.length(),
                        )
                    }
                }

                // Replace an older same-named MP4 only after the new staging file is complete. If the
                // process dies after deletion, the completed staging URI remains durable and recovery
                // can still publish it without rerendering.
                findChildDocument(parentUri, videoName)?.let { existing ->
                    if (existing.toString() != durablePartUri) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, existing) }
                    }
                }

                publishedVideoUri = withContext(Dispatchers.IO) {
                    DocumentsContract.renameDocument(context.contentResolver, partUri!!, videoName)
                } ?: throw IllegalStateException("Cannot publish MP4 document")
                partUri = null

                // Publication is durable before this point. Make the state checkpoint non-cancellable
                // so Android/WorkManager interruption cannot strand a completed MP4 without metadata.
                withContext(NonCancellable) {
                    checkpointVideoSuccess(prefs, jobId, publishedVideoUri.toString(), tempVideo.length())
                }
                return Result.success()
            } catch (e: CancellationException) {
                runCatching { partUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                // Never delete publishedVideoUri: once renameDocument() returned successfully the MP4
                // is durable and must survive system/background interruption.
                renderJson.delete(); tempVideo.delete()
                throw e
            } catch (e: Exception) {
                Timber.e(e, "TtsVideo: video generation failed for $jobId attempt=${runAttemptCount + 1}")
                runCatching { partUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                renderJson.delete(); tempVideo.delete()

                publishedVideoUri?.let { published ->
                    val sizeBytes = queryDocumentSize(published).takeIf { it > 0L } ?: tempVideo.length()
                    runCatching {
                        withContext(NonCancellable) {
                            checkpointVideoSuccess(prefs, jobId, published.toString(), sizeBytes)
                        }
                        return Result.success()
                    }.onFailure { checkpointError ->
                        Timber.e(checkpointError, "TtsVideo: failed to checkpoint published MP4 for $jobId")
                    }
                }

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
        } finally {
            VIDEO_RENDER_MUTEX.unlock()
        }
    }

    private suspend fun checkpointVideoSuccess(
        prefs: AppPreferences,
        jobId: String,
        documentUri: String,
        videoSizeBytes: Long,
    ) {
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "VIDEO",
                progress = 100,
                documentUri = documentUri,
                workRequestId = "",
                videoSizeBytes = videoSizeBytes.coerceAtLeast(0L),
                videoStagingUri = "",
                videoStagingComplete = false,
                videoStopReason = "",
                videoRecoveryAttempts = 0,
                message = "",
            )
        }
        runCatching { setForegroundAsync(createForegroundInfo(100)) }
    }

    private suspend fun findChildDocument(parentUri: Uri, displayName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    parentUri,
                    DocumentsContract.getDocumentId(parentUri),
                ),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(mimeCol) != MIME_MP4) continue
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    if (size > 0L) return@runCatching DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idCol))
                }
            }
            null
        }.getOrNull()
    }

    private suspend fun queryDocumentSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeCol >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun buildStagingName(videoName: String, jobId: String): String {
        val token = jobId.filter { it.isLetterOrDigit() || it == '-' }.takeLast(32).ifBlank { "video" }
        return "$videoName.$token.part"
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

    private fun createForegroundInfo(progressPercent: Int? = null): ForegroundInfo {
        ensureNotificationChannel()
        val hasProgress = progressPercent != null
        val progress = progressPercent?.coerceIn(0, 100) ?: 0
        val notification = NotificationCompat.Builder(context, VIDEO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NoveLA")
            .setContentText(
                if (hasProgress) "Generating chapter video — $progress%"
                else "Generating chapter video…"
            )
            .setSubText(if (hasProgress) "$progress%" else null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, !hasProgress)
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
                videoStagingUri = "",
                videoStagingComplete = false,
                videoStopReason = "",
                videoRecoveryAttempts = 0,
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
        private val VIDEO_RENDER_MUTEX = Mutex()
    }
}