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

        updateState(appPreferences, jobId) {
            TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
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
     * Cancel one chapter export using the persisted WorkRequest ID. This avoids recomputing
     * job identity from mutable settings such as the current translation language pair.
     * The Worker handles deletion of temporary/partial files during coroutine cancellation.
     */
    fun cancel(
        context: Context,
        appPreferences: AppPreferences,
        workRequestId: String,
    ) {
        val cancelled = runCatching { UUID.fromString(workRequestId) }
            .onSuccess { id -> WorkManager.getInstance(context).cancelWorkById(id) }
            .onFailure { Timber.w(it, "TtsAudio: invalid WorkRequest id for cancel: $workRequestId") }
            .isSuccess
        if (!cancelled) return

        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (job.workRequestId != workRequestId || !job.isActive) continue
                current[jobId] = job.copy(
                    status = TtsAudioJobStatus.CANCELLED,
                    message = "Cancelled",
                )
                changed = true
                break
            }
            if (changed) appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    fun cancelAll(context: Context, appPreferences: AppPreferences) {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUDIO_TAG)
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (!job.isActive) continue
                current[jobId] = job.copy(
                    status = TtsAudioJobStatus.CANCELLED,
                    message = "Cancelled",
                )
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
                    current[jobId] = job.copy(
                        status = TtsAudioJobStatus.FAILED,
                        message = "interrupted",
                    )
                    continue
                }

                val repaired = when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> null

                    WorkInfo.State.SUCCEEDED -> job.copy(
                        status = TtsAudioJobStatus.SUCCESS,
                        progress = 100,
                        message = "",
                    )

                    WorkInfo.State.CANCELLED -> job.copy(
                        status = TtsAudioJobStatus.CANCELLED,
                        message = "Cancelled",
                    )

                    WorkInfo.State.FAILED -> job.copy(
                        status = TtsAudioJobStatus.FAILED,
                        message = if (job.message.isBlank()) "failed" else job.message,
                    )
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
