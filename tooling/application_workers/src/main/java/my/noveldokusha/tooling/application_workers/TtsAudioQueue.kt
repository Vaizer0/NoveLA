package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import timber.log.Timber
import java.util.UUID

/**
 * Persistent audio-export scheduler with five independent lanes.
 *
 * Each lane corresponds to one export-only TTS slot. This allows up to five chapter
 * exports to run concurrently while keeping each TTS client isolated from the reader
 * and from the other export jobs. WorkManager persists each lane across process death
 * and app restarts; [reconcile] repairs stale persisted job records on startup.
 *
 * A logical chapter/source can be regenerated after cancellation. Every WorkRequest
 * UUID is treated as a separate generation so an old cancelled worker can never update
 * the state/progress belonging to a newer generation.
 */
object TtsAudioQueue {
    const val MAX_CONCURRENT_EXPORTS = 5
    private const val CHAIN_PREFIX = "tts-audio-download"
    const val AUDIO_TAG = "tts-audio-export"

    private val lock = Any()

    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        val jobId = request.jobId
        val workRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    TtsAudioExportWorker.KEY_JOB_ID to jobId,
                    TtsAudioExportWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    TtsAudioExportWorker.KEY_NOVEL_URL to request.novelUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_URL to request.chapterUrl,
                    TtsAudioExportWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    TtsAudioExportWorker.KEY_CHAPTER_INDEX to request.chapterIndex,
                    TtsAudioExportWorker.KEY_SOURCE to request.source.name,
                    TtsAudioExportWorker.KEY_ENGINE_PACKAGE to request.enginePackage,
                    TtsAudioExportWorker.KEY_VOICE_ID to request.voiceId,
                    TtsAudioExportWorker.KEY_SPEED to request.speed,
                    TtsAudioExportWorker.KEY_PITCH to request.pitch,
                    TtsAudioExportWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                    TtsAudioExportWorker.KEY_FORMAT to request.format,
                    TtsAudioExportWorker.KEY_TRANSLATION_SOURCE_LANG to request.translationSourceLang,
                    TtsAudioExportWorker.KEY_TRANSLATION_TARGET_LANG to request.translationTargetLang,
                )
            )
            .addTag(AUDIO_TAG)
            .build()

        // The generated WorkRequest UUID is the generation identity. Re-enqueueing the
        // same logical chapter therefore replaces the persisted generation before the
        // new WorkManager request starts publishing progress.
        updateState(appPreferences, jobId) {
            TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
                message = "",
                displayName = "",
                documentUri = "",
                progress = 0,
                workRequestId = workRequest.id.toString(),
            )
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(
                laneName(jobId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            )
            .enqueue()
    }

    /**
     * Cancel one chapter export using the persisted WorkRequest ID. The persisted
     * generation is removed immediately, so the UI returns to its normal download
     * state instead of displaying a user-visible CANCELLED state. The worker's
     * cancellation cleanup is guarded by the same WorkRequest ID and therefore cannot
     * resurrect the removed state.
     */
    fun cancel(
        context: Context,
        appPreferences: AppPreferences,
        workRequestId: String,
    ) {
        val id = runCatching { UUID.fromString(workRequestId) }
            .onFailure { Timber.w(it, "TtsAudio: invalid WorkRequest id for cancel: $workRequestId") }
            .getOrNull() ?: return

        WorkManager.getInstance(context).cancelWorkById(id)

        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val iterator = current.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val (jobId, job) = iterator.next()
                if (job.workRequestId == workRequestId && job.isActive) {
                    iterator.remove()
                    changed = true
                    break
                }
            }
            if (changed) appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    fun cancelAll(context: Context, appPreferences: AppPreferences) {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUDIO_TAG)
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val iterator = current.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val (_, job) = iterator.next()
                if (!job.isActive) continue
                iterator.remove()
                changed = true
            }
            if (changed) appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    fun cancelAllReactive(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUDIO_TAG)
    }

    fun observeJobs(appPreferences: AppPreferences): Flow<Map<String, TtsAudioJobState>> =
        appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.flow()

    /**
     * Update a job only when [workRequestId] is still the active generation stored for
     * [jobId]. Old workers therefore become write-inert immediately after a restart.
     */
    fun updateStateForWork(
        appPreferences: AppPreferences,
        jobId: String,
        workRequestId: String,
        transform: (TtsAudioJobState) -> TtsAudioJobState,
    ) {
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val existing = current[jobId] ?: return@synchronized
            if (existing.workRequestId != workRequestId) return@synchronized
            current[jobId] = transform(existing)
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    /** Repair persisted QUEUED/RUNNING records after process death or force-stop. */
    suspend fun reconcile(context: Context, appPreferences: AppPreferences) {
        val workInfos = runCatching {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosByTag(AUDIO_TAG).get()
            }
        }.getOrNull() ?: return

        val infoById = HashMap<String, WorkInfo>(workInfos.size)
        for (info in workInfos) {
            infoById[info.id.toString()] = info
        }

        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (!job.isActive) continue

                val wid = job.workRequestId
                val info = infoById[wid]
                if (info == null) {
                    changed = true
                    current.remove(jobId)
                    continue
                }

                val repaired = when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> null

                    // Terminal state means the persisted active record can be resolved
                    // here; cancellation is not exposed as a sticky UI state.
                    WorkInfo.State.SUCCEEDED -> job.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        progress = 100,
                        message = "",
                    )

                    WorkInfo.State.CANCELLED -> null

                    WorkInfo.State.FAILED -> job.copy(
                        status = TtsAudioJobStatus.FAILED,
                        message = if (job.message.isBlank()) "failed" else job.message,
                    )
                }

                if (info.state == WorkInfo.State.CANCELLED) {
                    changed = true
                    current.remove(jobId)
                    continue
                }

                if (repaired != null && repaired != job) {
                    changed = true
                    current[jobId] = repaired
                }
            }

            if (changed) {
                Timber.w("TtsAudio: reconciled persisted export jobs after process restart")
                appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
            }
        }
    }

    fun updateState(
        appPreferences: AppPreferences,
        jobId: String,
        transform: (TtsAudioJobState?) -> TtsAudioJobState?,
    ) {
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val updated = transform(current[jobId]) ?: return@synchronized
            current[jobId] = updated
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    private fun laneName(jobId: String): String {
        val lane = (jobId.hashCode() and Int.MAX_VALUE) % MAX_CONCURRENT_EXPORTS
        return "$CHAIN_PREFIX-$lane"
    }
}
