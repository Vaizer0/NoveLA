package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.tooling.application_workers.video.SafMp4Stager
import my.noveldokusha.tooling.application_workers.video.TtsVideoArtifactManifest
import java.util.concurrent.TimeUnit

object TtsVideoExportQueue {
    const val VIDEO_TAG = "tts-video-export"
    private const val WORK_PREFIX = "tts-video-export"
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_VIDEO_RECOVERY_ATTEMPTS = 3
    private const val NORMAL_RECOVERY_DELAY_MS = 30_000L
    private const val QUOTA_RECOVERY_DELAY_MS = 5 * 60_000L
    private const val FGS_TIMEOUT_RECOVERY_DELAY_MS = 15 * 60_000L
    private const val MIME_MP4 = "video/mp4"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    suspend fun enqueueFromJob(context: Context, job: TtsAudioJobState, initialDelayMs: Long = 0L) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val entry = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.entries.firstOrNull { (_, value) ->
            value.chapterUrl == job.chapterUrl &&
                value.novelUrl == job.novelUrl &&
                value.source == job.source &&
                value.audioUri == job.audioUri &&
                value.timelineUri == job.timelineUri
        } ?: return
        val parentDirectoryUri = findParentDirectoryUri(
            context,
            Uri.parse(entry.value.outputDirectoryUri),
            Uri.parse(entry.value.audioUri),
        ) ?: return
        enqueue(context, prefs, entry.key, entry.value, parentDirectoryUri.toString(), initialDelayMs)
    }

    suspend fun enqueue(
        context: Context,
        prefs: AppPreferences,
        jobId: String,
        job: TtsAudioJobState,
        parentDirectoryUri: String,
        initialDelayMs: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        if (job.status != TtsAudioJobStatus.SUCCESS && job.status != TtsAudioJobStatus.QUEUED) return@withContext
        if (!job.phase.equals("AUDIO", true) && !job.phase.equals("VIDEO", true)) return@withContext
        if (job.audioUri.isBlank() || job.timelineUri.isBlank() || job.outputDirectoryUri.isBlank() || parentDirectoryUri.isBlank()) return@withContext

        val workManager = WorkManager.getInstance(context)
        val uniqueName = workName(jobId)
        val existing = runCatching { workManager.getWorkInfosForUniqueWork(uniqueName).get() }.getOrNull().orEmpty()
        val active = existing.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
        }
        if (active != null) {
            if (job.workRequestId != active.id.toString() || job.status != TtsAudioJobStatus.RUNNING || !job.phase.equals("VIDEO", true)) {
                TtsAudioQueue.updateState(prefs, jobId) { current ->
                    current?.copy(
                        workRequestId = active.id.toString(),
                        status = TtsAudioJobStatus.RUNNING,
                        phase = "VIDEO",
                    )
                }
            }
            return@withContext
        }

        val requestBuilder = OneTimeWorkRequestBuilder<TtsVideoExportWorkerV2>()
            .setInputData(
                workDataOf(
                    TtsVideoExportWorkerV2.KEY_JOB_ID to jobId,
                    TtsVideoExportWorkerV2.KEY_AUDIO_URI to job.audioUri,
                    TtsVideoExportWorkerV2.KEY_TIMELINE_URI to job.timelineUri,
                    TtsVideoExportWorkerV2.KEY_PARENT_DIRECTORY_URI to parentDirectoryUri,
                    TtsVideoExportWorkerV2.KEY_OUTPUT_DIRECTORY_URI to job.outputDirectoryUri,
                    TtsVideoExportWorkerV2.KEY_CHAPTER_TITLE to job.chapterTitle,
                    TtsVideoExportWorkerV2.KEY_NOVEL_URL to job.novelUrl,
                    TtsVideoExportWorkerV2.KEY_CHAPTER_URL to job.chapterUrl,
                    TtsVideoExportWorkerV2.KEY_SOURCE to job.source.name,
                    TtsVideoExportWorkerV2.KEY_DISPLAY_NAME to job.displayName,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        if (initialDelayMs > 0L) requestBuilder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
        val request = requestBuilder.addTag(VIDEO_TAG).build()

        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.QUEUED,
                phase = "VIDEO",
                progress = if (job.videoStagingUri.isNotBlank()) job.progress.coerceIn(0, 99) else 0,
                documentUri = "",
                workRequestId = request.id.toString(),
                videoSizeBytes = job.videoSizeBytes,
                message = if (initialDelayMs > 0L) "Video recovery scheduled…" else "",
            )
        }
        workManager.beginUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request).enqueue()
    }

    suspend fun reconcile(context: Context, prefs: AppPreferences) {
        val workManager = WorkManager.getInstance(context)
        val infos = runCatching {
            withContext(Dispatchers.IO) { workManager.getWorkInfosByTag(VIDEO_TAG).get() }
        }.getOrNull() ?: emptyList()
        val byId = infos.associateBy { it.id.toString() }
        val current = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
        var changed = false
        val restartJobs = mutableListOf<Pair<TtsAudioJobState, Long>>()

        for ((jobId, job) in current.toList()) {
            if (!job.phase.equals("VIDEO", true)) continue

            val parentUri = if (job.outputDirectoryUri.isNotBlank() && job.audioUri.isNotBlank()) {
                findParentDirectoryUri(context, Uri.parse(job.outputDirectoryUri), Uri.parse(job.audioUri))
            } else null

            if (parentUri != null) {
                val videoName = job.displayName.removeSuffix(".wav") + ".mp4"
                val finalUri = findChildDocument(context, parentUri, videoName)
                val manifestUri = TtsVideoArtifactManifest.findInDirectory(
                    context,
                    parentUri,
                    TtsVideoArtifactManifest.finalName(videoName),
                )
                val authoritativeFinal = finalUri != null &&
                    manifestUri != null &&
                    SafMp4Stager.isValidMp4(context, finalUri) &&
                    TtsVideoArtifactManifest.matches(
                        context,
                        manifestUri,
                        job.novelUrl,
                        job.chapterUrl,
                        job.source,
                        job.audioUri,
                        job.timelineUri,
                        job.displayName,
                    )

                if (authoritativeFinal) {
                    val size = SafMp4Stager.querySize(context, finalUri!!)
                    current[jobId] = job.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        phase = "VIDEO",
                        progress = 100,
                        documentUri = finalUri.toString(),
                        workRequestId = "",
                        videoSizeBytes = size,
                        videoStagingUri = "",
                        videoStagingComplete = false,
                        videoRecoveryAttempts = 0,
                        videoStopReason = "",
                        message = "",
                    )
                    changed = true
                    continue
                }

                // Never treat a same-named MP4 as belonging to this chapter without its identity manifest.
                // V2 will quarantine an untrusted exact-name MP4 before publishing the correct artifact.
                val stagedUri = job.videoStagingUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                val stagedManifest = TtsVideoArtifactManifest.findInDirectory(
                    context,
                    parentUri,
                    TtsVideoArtifactManifest.stagingName(videoName, jobId),
                )
                val authoritativeStaging = stagedUri != null &&
                    stagedManifest != null &&
                    SafMp4Stager.isValidMp4(context, stagedUri) &&
                    TtsVideoArtifactManifest.matches(
                        context,
                        stagedManifest,
                        job.novelUrl,
                        job.chapterUrl,
                        job.source,
                        job.audioUri,
                        job.timelineUri,
                        job.displayName,
                    )
                if (authoritativeStaging) {
                    val actualUnique = runCatching {
                        withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(workName(jobId)).get() }
                    }.getOrNull().orEmpty()
                    val activeUnique = actualUnique.firstOrNull {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                    }
                    if (activeUnique == null) restartJobs += job.copy(videoStagingComplete = true) to 0L
                    else if (job.workRequestId != activeUnique.id.toString() || job.status != TtsAudioJobStatus.RUNNING) {
                        current[jobId] = job.copy(workRequestId = activeUnique.id.toString(), status = TtsAudioJobStatus.RUNNING, phase = "VIDEO", videoStagingComplete = true)
                        changed = true
                    }
                    continue
                }

                val actualUnique = runCatching {
                    withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(workName(jobId)).get() }
                }.getOrNull().orEmpty()
                val activeUnique = actualUnique.firstOrNull {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                }
                if (activeUnique != null) {
                    if (job.workRequestId != activeUnique.id.toString() || job.status != TtsAudioJobStatus.RUNNING) {
                        current[jobId] = job.copy(
                            workRequestId = activeUnique.id.toString(),
                            status = TtsAudioJobStatus.RUNNING,
                            phase = "VIDEO",
                        )
                        changed = true
                    }
                    continue
                }

                // SUCCESS/VIDEO without an authoritative final artifact is stale. Do not let it paint
                // an orange tick; the durable WAV+timeline checkpoint remains available as blue Audio ✓.
                if (job.status == TtsAudioJobStatus.SUCCESS &&
                    job.audioUri.isNotBlank() &&
                    job.timelineUri.isNotBlank()
                ) {
                    current[jobId] = job.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        phase = "AUDIO",
                        progress = 100,
                        documentUri = "",
                        workRequestId = "",
                        videoSizeBytes = 0L,
                        videoStagingUri = "",
                        videoStagingComplete = false,
                        message = "",
                    )
                    changed = true
                    continue
                }
            }

            if (job.workRequestId.isBlank()) {
                if (job.status == TtsAudioJobStatus.QUEUED && job.audioUri.isNotBlank() && job.timelineUri.isNotBlank()) {
                    restartJobs += job to 0L
                }
                continue
            }

            val info = byId[job.workRequestId]
            val actualUnique = runCatching {
                withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(workName(jobId)).get() }
            }.getOrNull().orEmpty()
            val activeUnique = actualUnique.firstOrNull {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
            }
            if (activeUnique != null) {
                if (job.workRequestId != activeUnique.id.toString() || job.status != TtsAudioJobStatus.RUNNING) {
                    current[jobId] = job.copy(
                        workRequestId = activeUnique.id.toString(),
                        status = TtsAudioJobStatus.RUNNING,
                        phase = "VIDEO",
                    )
                    changed = true
                }
                continue
            }

            when (info?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> Unit
                WorkInfo.State.SUCCEEDED -> {
                    val attempts = job.videoRecoveryAttempts + 1
                    if (attempts <= MAX_VIDEO_RECOVERY_ATTEMPTS) {
                        val updated = job.copy(
                            status = TtsAudioJobStatus.QUEUED,
                            phase = "VIDEO",
                            progress = job.progress.coerceIn(0, 99),
                            documentUri = "",
                            workRequestId = "",
                            videoRecoveryAttempts = attempts,
                            message = "Video checkpoint missing; recovery queued ($attempts/$MAX_VIDEO_RECOVERY_ATTEMPTS).",
                        )
                        current[jobId] = updated
                        changed = true
                        restartJobs += updated to NORMAL_RECOVERY_DELAY_MS
                    } else {
                        current[jobId] = job.copy(
                            status = TtsAudioJobStatus.SUCCESS,
                            phase = "AUDIO",
                            progress = 100,
                            documentUri = "",
                            workRequestId = "",
                            videoSizeBytes = 0L,
                            videoRecoveryAttempts = attempts,
                            message = "Video checkpoint could not be recovered; WAV + timeline are preserved.",
                        )
                        changed = true
                    }
                }
                WorkInfo.State.CANCELLED, WorkInfo.State.FAILED, null -> {
                    val stopReason = readStopReason(info)
                    val reasonName = stopReasonName(stopReason)
                    val canRecover = stopReason == WorkInfo.STOP_REASON_UNKNOWN ||
                        stopReason == WorkInfo.STOP_REASON_QUOTA ||
                        stopReason == WorkInfo.STOP_REASON_TIMEOUT ||
                        stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                        stopReason == WorkInfo.STOP_REASON_SYSTEM_PROCESSING ||
                        stopReason == WorkInfo.STOP_REASON_DEVICE_STATE ||
                        stopReason == WorkInfo.STOP_REASON_PREEMPT ||
                        stopReason == WorkInfo.STOP_REASON_USER ||
                        stopReason == WorkInfo.STOP_REASON_APP_STANDBY ||
                        stopReason == WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION ||
                        stopReason == WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED
                    val attempts = job.videoRecoveryAttempts + 1
                    if (canRecover && attempts <= MAX_VIDEO_RECOVERY_ATTEMPTS && job.audioUri.isNotBlank() && job.timelineUri.isNotBlank()) {
                        val updated = job.copy(
                            status = TtsAudioJobStatus.QUEUED,
                            phase = "VIDEO",
                            progress = job.progress.coerceIn(0, 99),
                            documentUri = "",
                            workRequestId = "",
                            videoStopReason = reasonName,
                            videoRecoveryAttempts = attempts,
                            message = "Video interrupted ($reasonName); recovery queued (${attempts}/$MAX_VIDEO_RECOVERY_ATTEMPTS).",
                        )
                        current[jobId] = updated
                        changed = true
                        restartJobs += updated to recoveryDelayMs(stopReason, attempts)
                    } else {
                        current[jobId] = job.copy(
                            status = TtsAudioJobStatus.SUCCESS,
                            phase = "AUDIO",
                            progress = 100,
                            documentUri = "",
                            workRequestId = "",
                            videoSizeBytes = 0L,
                            videoStopReason = reasonName,
                            videoRecoveryAttempts = attempts,
                            message = "Video export stopped after recovery limit; WAV + timeline are preserved.",
                        )
                        changed = true
                    }
                }
            }
        }

        if (changed) prefs.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        val seen = HashSet<String>()
        for ((job, delay) in restartJobs) {
            val key = "${job.novelUrl}\u0000${job.chapterUrl}\u0000${job.source}\u0000${job.audioUri}\u0000${job.timelineUri}"
            if (seen.add(key)) enqueueFromJob(context, job, delay)
        }
    }

    private fun readStopReason(info: WorkInfo?): Int {
        if (info == null) return WorkInfo.STOP_REASON_UNKNOWN
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.stopReason else WorkInfo.STOP_REASON_UNKNOWN
    }

    private fun recoveryDelayMs(stopReason: Int, attempts: Int): Long {
        val base = when (stopReason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> FGS_TIMEOUT_RECOVERY_DELAY_MS
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_PREEMPT,
            WorkInfo.STOP_REASON_APP_STANDBY,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> QUOTA_RECOVERY_DELAY_MS
            else -> NORMAL_RECOVERY_DELAY_MS
        }
        return base * (1L shl (attempts - 1).coerceIn(0, 2))
    }

    private fun stopReasonName(stopReason: Int): String = when (stopReason) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
        WorkInfo.STOP_REASON_UNKNOWN -> "unknown"
        WorkInfo.STOP_REASON_PREEMPT -> "preempted"
        WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "device_state"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery_constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "charging_constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity_constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "idle_constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage_constraint"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_QUOTA -> "quota"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background_restriction"
        WorkInfo.STOP_REASON_USER -> "user"
        WorkInfo.STOP_REASON_APP_STANDBY -> "app_standby"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system_processing"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground_service_timeout"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "launch_time_changed"
        else -> "reason_$stopReason"
    }

    private suspend fun findChildDocument(context: Context, parentUri: Uri, displayName: String): Uri? = withContext(Dispatchers.IO) {
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

    private suspend fun findParentDirectoryUri(context: Context, treeUri: Uri, targetUri: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val targetId = DocumentsContract.getDocumentId(targetUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val stack = ArrayDeque<Pair<String, Int>>()
            val visited = HashSet<String>()
            stack.add(rootId to 0)
            while (stack.isNotEmpty()) {
                val (parentId, depth) = stack.removeLast()
                if (depth > 8 || !visited.add(parentId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idCol)
                        if (childId == targetId) return@runCatching DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
                        if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(childId to depth + 1)
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun workName(jobId: String) = "$WORK_PREFIX-$jobId"
}
