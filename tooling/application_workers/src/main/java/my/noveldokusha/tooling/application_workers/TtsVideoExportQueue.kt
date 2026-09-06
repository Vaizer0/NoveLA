package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
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
import java.util.concurrent.TimeUnit

object TtsVideoExportQueue {
    const val VIDEO_TAG = "tts-video-export"
    private const val WORK_PREFIX = "tts-video-export"
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_VIDEO_RECOVERY_ATTEMPTS = 3
    private const val NORMAL_RECOVERY_DELAY_MS = 30_000L
    private const val QUOTA_RECOVERY_DELAY_MS = 5 * 60_000L
    private const val FGS_TIMEOUT_RECOVERY_DELAY_MS = 15 * 60_000L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun appPreferences(): AppPreferences
    }

    suspend fun enqueueFromJob(context: Context, job: TtsAudioJobState, initialDelayMs: Long = 0L) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, EntryPointAccess::class.java).appPreferences()
        val entry = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.entries.firstOrNull { (_, value) ->
            value.chapterUrl == job.chapterUrl && value.novelUrl == job.novelUrl && value.source == job.source &&
                value.audioUri == job.audioUri && value.timelineUri == job.timelineUri
        } ?: return
        val parentDirectoryUri = findParentDirectoryUri(context, Uri.parse(entry.value.outputDirectoryUri), Uri.parse(entry.value.audioUri)) ?: return
        enqueue(context, prefs, entry.key, entry.value, parentDirectoryUri.toString(), initialDelayMs)
    }

    /** Enqueue one logical chapter/source using replace semantics; the worker serializes actual VIDEO rendering. */
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

        val requestBuilder = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
            .setInputData(workDataOf(
                TtsVideoExportWorker.KEY_JOB_ID to jobId,
                TtsVideoExportWorker.KEY_AUDIO_URI to job.audioUri,
                TtsVideoExportWorker.KEY_TIMELINE_URI to job.timelineUri,
                TtsVideoExportWorker.KEY_PARENT_DIRECTORY_URI to parentDirectoryUri,
                TtsVideoExportWorker.KEY_OUTPUT_DIRECTORY_URI to job.outputDirectoryUri,
                TtsVideoExportWorker.KEY_CHAPTER_TITLE to job.chapterTitle,
                TtsVideoExportWorker.KEY_DISPLAY_NAME to job.displayName,
            ))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        if (initialDelayMs > 0L) requestBuilder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
        val request = requestBuilder.addTag(VIDEO_TAG).build()

        TtsAudioQueue.updateState(prefs, jobId) {
            it?.copy(
                status = TtsAudioJobStatus.QUEUED,
                phase = "VIDEO",
                progress = 0,
                documentUri = "",
                workRequestId = request.id.toString(),
                videoSizeBytes = 0L,
                message = if (initialDelayMs > 0L) "Video recovery scheduled…" else "",
            )
        }
        WorkManager.getInstance(context).beginUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, request).enqueue()
    }

    suspend fun reconcile(context: Context, prefs: AppPreferences) {
        val infos = runCatching { withContext(Dispatchers.IO) { WorkManager.getInstance(context).getWorkInfosByTag(VIDEO_TAG).get() } }.getOrNull() ?: return
        val byId = infos.associateBy { it.id.toString() }
        val current = prefs.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
        var changed = false
        val restartJobs = mutableListOf<Pair<TtsAudioJobState, Long>>()

        for ((jobId, job) in current.toList()) {
            if (!job.phase.equals("VIDEO", true)) continue

            // A successfully checkpointed MP4 is authoritative even when WorkManager reports a stop
            // immediately after the checkpoint. Validate the document before touching the state.
            if (job.status == TtsAudioJobStatus.SUCCESS && job.documentUri.isNotBlank()) {
                val sizeBytes = queryDocumentSize(context, Uri.parse(job.documentUri))
                if (sizeBytes > 0L) {
                    if (job.workRequestId.isNotBlank() || job.progress != 100 || job.videoSizeBytes != sizeBytes) {
                        current[jobId] = job.copy(workRequestId = "", progress = 100, videoSizeBytes = sizeBytes, message = "")
                        changed = true
                    }
                    continue
                }
            }

            // A blank request id means this job is intentionally waiting behind another VIDEO worker.
            // Do not count that normal queue state as a failed/system-interrupted generation.
            if (job.workRequestId.isBlank() && job.status == TtsAudioJobStatus.QUEUED) {
                restartJobs += job to 0L
                continue
            }

            val info = byId[job.workRequestId]
            when (info?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> Unit
                WorkInfo.State.SUCCEEDED -> {
                    restartJobs += job to NORMAL_RECOVERY_DELAY_MS
                }
                WorkInfo.State.CANCELLED, WorkInfo.State.FAILED, null -> {
                    val stopReason = info?.stopReason ?: WorkInfo.STOP_REASON_UNKNOWN
                    val reasonName = stopReasonName(stopReason)
                    val canRecover = stopReason == WorkInfo.STOP_REASON_UNKNOWN ||
                        stopReason == WorkInfo.STOP_REASON_QUOTA ||
                        stopReason == WorkInfo.STOP_REASON_TIMEOUT ||
                        stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                        stopReason == WorkInfo.STOP_REASON_SYSTEM_PROCESSING ||
                        stopReason == WorkInfo.STOP_REASON_DEVICE_STATE ||
                        stopReason == WorkInfo.STOP_REASON_PREEMPT ||
                        stopReason == WorkInfo.STOP_REASON_USER
                    val attempts = job.videoRecoveryAttempts + 1

                    if (canRecover && attempts <= MAX_VIDEO_RECOVERY_ATTEMPTS && job.audioUri.isNotBlank() && job.timelineUri.isNotBlank()) {
                        val delay = recoveryDelayMs(stopReason, attempts)
                        val updated = job.copy(
                            status = TtsAudioJobStatus.QUEUED,
                            phase = "VIDEO",
                            progress = 0,
                            documentUri = "",
                            workRequestId = "",
                            videoSizeBytes = 0L,
                            videoStopReason = reasonName,
                            videoRecoveryAttempts = attempts,
                            message = "Video interrupted ($reasonName); recovery queued (${attempts}/$MAX_VIDEO_RECOVERY_ATTEMPTS).",
                        )
                        current[jobId] = updated
                        changed = true
                        restartJobs += updated to delay
                    } else {
                        val u = job.copy(
                            status = TtsAudioJobStatus.SUCCESS,
                            phase = "AUDIO",
                            progress = 100,
                            documentUri = "",
                            workRequestId = "",
                            videoSizeBytes = 0L,
                            videoStagingUri = "",
                            videoStagingComplete = false,
                            videoStopReason = reasonName,
                            videoRecoveryAttempts = attempts,
                            message = "Video export stopped after recovery limit; WAV + timeline are preserved.",
                        )
                        if (u != job) { current[jobId] = u; changed = true }
                    }
                }
            }
        }
        if (changed) prefs.TTS_AUDIO_DOWNLOAD_JOBS.value = current

        for ((job, delay) in restartJobs) enqueueFromJob(context, job, delay)
    }

    private fun recoveryDelayMs(stopReason: Int, attempts: Int): Long {
        val base = when (stopReason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> FGS_TIMEOUT_RECOVERY_DELAY_MS
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_PREEMPT -> QUOTA_RECOVERY_DELAY_MS
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
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage_constraint"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_QUOTA -> "quota"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background_restriction"
        WorkInfo.STOP_REASON_USER -> "user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system_processing"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground_service_timeout"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "launch_time_changed"
        else -> "reason_$stopReason"
    }

    private suspend fun queryDocumentSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
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
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
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
