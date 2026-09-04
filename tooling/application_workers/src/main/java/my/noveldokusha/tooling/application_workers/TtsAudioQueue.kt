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
 * Persistent audio-export scheduler with five independent export-only TTS clients.
 *
 * Each logical chapter/source gets its own WorkManager unique-work name, so different
 * chapters never share a serialized chain. The TTS engine pool is the concurrency gate:
 * at most [MAX_CONCURRENT_EXPORTS] exports can synthesize at once.
 *
 * Re-enqueueing the same logical chapter/source replaces only that chapter's previous
 * generation. Every generation has a unique internal job ID, so late callbacks from an
 * older cancellation cannot overwrite the replacement's progress or temporary WAV.
 */
object TtsAudioQueue {
    const val MAX_CONCURRENT_EXPORTS = 5
    private const val WORK_PREFIX = "tts-audio-download"
    const val AUDIO_TAG = "tts-audio-export"

    private val lock = Any()

    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        val logicalJobId = request.jobId
        val generationJobId = "$logicalJobId::${UUID.randomUUID()}"
        val workRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    TtsAudioExportWorker.KEY_JOB_ID to generationJobId,
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

        // One chain per logical export, not one of five hash buckets. This preserves
        // duplicate protection for the same chapter/source without serializing unrelated
        // chapters that happen to hash to the same lane.
        WorkManager.getInstance(context)
            .beginUniqueWork(
                workName(logicalJobId),
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
            .enqueue()
    }

    /** Cancel exactly one WorkRequest generation. */
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

    /** Cancel all current exports (kept for explicit queue-wide cancellation callers). */
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
            val iterator = current.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val (jobId, job) = iterator.next()
                if (!job.isActive) continue

                val info = infoById[job.workRequestId]
                if (info == null) {
                    iterator.remove()
                    changed = true
                    continue
                }

                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> Unit

                    WorkInfo.State.SUCCEEDED -> {
                        val repaired = job.copy(
                            status = TtsAudioJobStatus.SUCCESS,
                            progress = 100,
                            message = "",
                        )
                        if (repaired != job) {
                            current[jobId] = repaired
                            changed = true
                        }
                    }

                    WorkInfo.State.CANCELLED -> {
                        iterator.remove()
                        changed = true
                    }

                    WorkInfo.State.FAILED -> {
                        val repaired = job.copy(
                            status = TtsAudioJobStatus.FAILED,
                            message = if (job.message.isBlank()) "failed" else job.message,
                        )
                        if (repaired != job) {
                            current[jobId] = repaired
                            changed = true
                        }
                    }
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

    private fun workName(logicalJobId: String): String = "$WORK_PREFIX-$logicalJobId"
}
