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
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.tooling.application_workers.video.CinematicVideoExporter
import my.noveldokusha.tooling.application_workers.video.SafMp4Stager
import my.noveldokusha.tooling.application_workers.video.TtsVideoArtifactManifest
import timber.log.Timber
import java.io.File

/**
 * Durable VIDEO stage. The SAF .part document is created before rendering and is preserved until a
 * complete MP4 plus its chapter-identity manifest are validated and published.
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
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val persistedJob = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() }
            ?: persistedJob?.audioUri?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val timelineUri = inputData.getString(KEY_TIMELINE_URI)?.takeIf { it.isNotBlank() }
            ?: persistedJob?.timelineUri?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val parentUri = inputData.getString(KEY_PARENT_DIRECTORY_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: persistedJob?.displayName?.takeIf { it.isNotBlank() }
            ?: "chapter.wav"
        val novelUrl = inputData.getString(KEY_NOVEL_URL)?.takeIf { it.isNotBlank() }
            ?: persistedJob?.novelUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val chapterUrl = inputData.getString(KEY_CHAPTER_URL)?.takeIf { it.isNotBlank() }
            ?: persistedJob?.chapterUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val source = inputData.getString(KEY_SOURCE)?.let { runCatching { TtsAudioSource.valueOf(it) }.getOrNull() }
            ?: persistedJob?.source
            ?: return Result.failure()
        val videoName = displayName.removeSuffix(".wav") + ".mp4"

        val existingFinal = findValidFinal(parentUri, videoName, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)
        if (existingFinal != null) {
            checkpointSuccess(prefs, jobId, existingFinal)
            return Result.success()
        }

        // A legacy or mismatched file must never block publication of the correct chapter video.
        // Preserve it under a non-canonical name rather than deleting or overwriting user data.
        quarantineUntrustedFinal(parentUri, videoName, jobId)

        val existingStagingString = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]?.videoStagingUri?.takeIf { it.isNotBlank() }
        if (existingStagingString != null) {
            val staged = Uri.parse(existingStagingString)
            val stagedManifest = TtsVideoArtifactManifest.findInDirectory(
                context,
                parentUri,
                TtsVideoArtifactManifest.stagingName(videoName, jobId),
            )
            val stagingMatches = stagedManifest != null && TtsVideoArtifactManifest.matches(
                context, stagedManifest, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName,
            )
            if (SafMp4Stager.isValidMp4(context, staged) && stagingMatches) {
                publishStaged(prefs, jobId, parentUri, videoName, staged, stagedManifest!!, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)
                return Result.success()
            }
            SafMp4Stager.delete(context, staged)
            if (stagedManifest != null) TtsVideoArtifactManifestDelete.delete(context, stagedManifest)
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
        var stagingManifestUri: Uri? = null
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
                DocumentsContract.createDocument(context.contentResolver, parentUri, MIME_MP4, buildStagingName(videoName, jobId))
            } ?: throw IllegalStateException("Cannot create SAF staging document")

            stagingManifestUri = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    MIME_JSON,
                    TtsVideoArtifactManifest.stagingName(videoName, jobId),
                )
            } ?: throw IllegalStateException("Cannot create SAF video manifest staging document")
            writeTextToUri(
                TtsVideoArtifactManifest.buildJson(novelUrl, chapterUrl, source, audioUri, timelineUri, displayName),
                stagingManifestUri!!,
            )

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
            require(stagedSize > 0L && SafMp4Stager.isValidMp4(context, stagingUri!!)) { "SAF staging MP4 failed validation" }
            require(
                TtsVideoArtifactManifest.matches(
                    context, stagingManifestUri!!, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName,
                )
            ) { "SAF staging manifest failed identity validation" }

            withContext(NonCancellable) {
                TtsAudioQueue.updateState(prefs, jobId) {
                    it?.copy(videoStagingUri = stagingUri.toString(), videoStagingComplete = true, videoSizeBytes = stagedSize, progress = 99)
                }
            }

            publishedUri = publishStaged(
                prefs,
                jobId,
                parentUri,
                videoName,
                stagingUri!!,
                stagingManifestUri!!,
                novelUrl,
                chapterUrl,
                source,
                audioUri,
                timelineUri,
                displayName,
            )
            stagingUri = null
            stagingManifestUri = null
            withContext(NonCancellable) { checkpointSuccess(prefs, jobId, publishedUri!!) }
            return Result.success()
        } catch (e: CancellationException) {
            renderJson.delete()
            tempVideo.delete()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TtsVideoV2: generation failed for $jobId attempt=${runAttemptCount + 1}")
            renderJson.delete()
            tempVideo.delete()

            if (publishedUri != null && isAuthoritativeFinal(publishedUri!!, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)) {
                checkpointSuccess(prefs, jobId, publishedUri!!)
                return Result.success()
            }

            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                val persistedStagingUri = stagingUri ?: prefs.TTS_AUDIO_DOWNLOAD_JOBS.value[jobId]?.videoStagingUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                val persistedManifest = stagingManifestUri ?: persistedStagingUri?.let {
                    TtsVideoArtifactManifest.findInDirectory(context, parentUri, TtsVideoArtifactManifest.stagingName(videoName, jobId))
                }
                val recoveredStagingComplete = if (persistedStagingUri != null && persistedManifest != null) {
                    SafMp4Stager.isValidMp4(context, persistedStagingUri) && TtsVideoArtifactManifest.matches(
                        context, persistedManifest, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName,
                    )
                } else false
                TtsAudioQueue.updateState(prefs, jobId) { current ->
                    current?.copy(
                        status = TtsAudioJobStatus.QUEUED,
                        phase = "VIDEO",
                        progress = current.progress.coerceIn(0, 99),
                        documentUri = "",
                        workRequestId = id.toString(),
                        videoStagingUri = persistedStagingUri?.toString() ?: current.videoStagingUri,
                        videoStagingComplete = recoveredStagingComplete,
                        message = "Retrying video generation…",
                    )
                }
                return Result.retry()
            }

            restoreAudioState(prefs, jobId, audioUri, timelineUri, displayName, e.message ?: "")
            return Result.failure()
        } finally {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            renderJson.delete()
            tempVideo.delete()
            RENDER_MUTEX.unlock()
        }
    }

    private suspend fun findValidFinal(
        parentUri: Uri,
        videoName: String,
        novelUrl: String,
        chapterUrl: String,
        source: TtsAudioSource,
        audioUri: String,
        timelineUri: String,
        displayName: String,
    ): Uri? {
        val final = findChildDocument(parentUri, videoName) ?: return null
        if (!SafMp4Stager.isValidMp4(context, final)) return null
        val manifest = TtsVideoArtifactManifest.findInDirectory(context, parentUri, TtsVideoArtifactManifest.finalName(videoName)) ?: return null
        return final.takeIf {
            TtsVideoArtifactManifest.matches(context, manifest, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)
        }
    }

    private suspend fun isAuthoritativeFinal(
        uri: Uri,
        novelUrl: String,
        chapterUrl: String,
        source: TtsAudioSource,
        audioUri: String,
        timelineUri: String,
        displayName: String,
    ): Boolean {
        if (!SafMp4Stager.isValidMp4(context, uri)) return false
        val parent = findParentFromUri(uri) ?: return false
        val videoName = queryDisplayName(uri) ?: return false
        val manifest = TtsVideoArtifactManifest.findInDirectory(context, parent, TtsVideoArtifactManifest.finalName(videoName)) ?: return false
        return TtsVideoArtifactManifest.matches(context, manifest, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)
    }

    private suspend fun publishStaged(
        prefs: AppPreferences,
        jobId: String,
        parentUri: Uri,
        videoName: String,
        stagedUri: Uri,
        stagedManifestUri: Uri,
        novelUrl: String,
        chapterUrl: String,
        source: TtsAudioSource,
        audioUri: String,
        timelineUri: String,
        displayName: String,
    ): Uri {
        val existing = findValidFinal(parentUri, videoName, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)
        if (existing != null) {
            SafMp4Stager.delete(context, stagedUri)
            TtsVideoArtifactManifestDelete.delete(context, stagedManifestUri)
            checkpointSuccess(prefs, jobId, existing)
            return existing
        }

        quarantineUntrustedFinal(parentUri, videoName, jobId)

        val renamedVideo = withContext(Dispatchers.IO) {
            DocumentsContract.renameDocument(context.contentResolver, stagedUri, videoName)
        } ?: throw IllegalStateException("Cannot rename staged MP4 to $videoName")
        require(SafMp4Stager.isValidMp4(context, renamedVideo)) { "Published MP4 failed validation" }

        val finalManifestUri = withContext(Dispatchers.IO) {
            DocumentsContract.renameDocument(context.contentResolver, stagedManifestUri, TtsVideoArtifactManifest.finalName(videoName))
        } ?: throw IllegalStateException("Cannot publish video manifest for $videoName")
        require(
            TtsVideoArtifactManifest.matches(
                context, finalManifestUri, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName,
            )
        ) { "Published video manifest failed identity validation" }
        require(isAuthoritativeFinal(renamedVideo, novelUrl, chapterUrl, source, audioUri, timelineUri, displayName)) {
            "Published MP4 is not authoritative for the requested chapter"
        }
        return renamedVideo
    }

    private suspend fun quarantineUntrustedFinal(parentUri: Uri, videoName: String, jobId: String) {
        val existing = findChildDocument(parentUri, videoName) ?: return
        if (!SafMp4Stager.isValidMp4(context, existing)) {
            withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, existing) }
            return
        }
        val stamp = System.currentTimeMillis()
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val legacyName = "$videoName.legacy-$stamp-$safeJobId.mp4"
        runCatching {
            withContext(Dispatchers.IO) { DocumentsContract.renameDocument(context.contentResolver, existing, legacyName) }
        }.onFailure { error -> Timber.w(error, "TtsVideoV2: unable to quarantine legacy video $videoName") }

        val manifest = TtsVideoArtifactManifest.findInDirectory(context, parentUri, TtsVideoArtifactManifest.finalName(videoName))
        if (manifest != null) {
            val legacyManifestName = "$videoName.legacy-$stamp-$safeJobId.manifest.json"
            runCatching {
                withContext(Dispatchers.IO) { DocumentsContract.renameDocument(context.contentResolver, manifest, legacyManifestName) }
            }
        }
    }

    private suspend fun findParentFromUri(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getDocumentId(uri).substringBeforeLast('/')) }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private suspend fun findChildDocument(parentUri: Uri, displayName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri)),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(mimeCol) == MIME_MP4) return@runCatching DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idCol))
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
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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

    private suspend fun restoreAudioState(prefs: AppPreferences, jobId: String, audioUri: String, timelineUri: String, displayName: String, message: String) {
        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.SUCCESS,
                phase = "AUDIO",
                progress = 100,
                documentUri = "",
                audioUri = audioUri,
                timelineUri = timelineUri,
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

    private suspend fun writeTextToUri(text: String, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> out.writer(Charsets.UTF_8).use { it.write(text) } }
            ?: throw IllegalStateException("Cannot write $uri")
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_PARENT_DIRECTORY_URI = "parentDirectoryUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
        const val KEY_DISPLAY_NAME = "displayName"
        const val MIME_MP4 = "video/mp4"
        const val MIME_JSON = "application/json"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_RETRY_ATTEMPTS = 4
        const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
        const val VIDEO_NOTIFICATION_ID = 0x4E56
        const val VIDEO_CHANNEL_ID = "tts_video_generation"
        val RENDER_MUTEX = Mutex()
    }
}

private object TtsVideoArtifactManifestDelete {
    fun delete(context: Context, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}
