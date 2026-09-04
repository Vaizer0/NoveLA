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
 * Each enqueue receives a unique internal generation ID derived from the WorkRequest
 * UUID. A cancelled generation can therefore never share persisted progress or the
 * temporary WAV path with a later regeneration of the same chapter.
 */
object TtsAudioQueue {
    const val MAX_CONCURRENT_EXPORTS = 5
    private const val CHAIN_PREFIX = "tts-audio-download"
    const val AUDIO_TAG = "tts-audio-export"

    private val lock = Any()

    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        val logicalJobId = request.jobId
        val workRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    // Keep the public/logical request identity for chapter/source UI mapping,
                    // but give the Worker a unique generation ID. This ID is only internal:
                    // final audio/timeline filenames remain based on chapter metadata.
                    TtsAudioExportWorker.KEY_JOB_ID to "${logicalJobId}::${UUID.randomUUID()}",
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

        val generationJobId = workRequest.inputData.getString(TtsAudioExportWorker.KEY_JOB_ID)!!

        // Persist the exact Worker generation ID. The WorkRequest UUID itself is also
        // retained separately for cancellation/reconciliation.
        updateState(appPreferences, generationJobId) {
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
                laneName(logicalJobId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            )
            .enqueue()
    }

    /**
     * Cancel one chapter export using its WorkRequest ID.
     *
     * The active persisted generation is removed immediately, so the chapter row returns
     * to its normal download icon instead of displaying a sticky "Cancelled" state.
     * Because the generation ID is unique, any late callback from the old Worker can only
     * address the removed generation and therefore cannot overwrite a newly restarted job.
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
                val (_, job) = iterator.next()
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
     * Repair persisted QUEUED/RUNNING records after process death or force-stop.
     * Cancelled generations are removed rather than exposed as a sticky UI state.
     */
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
