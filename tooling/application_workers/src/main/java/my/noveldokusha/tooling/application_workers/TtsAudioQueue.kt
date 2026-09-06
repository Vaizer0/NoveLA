package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

object TtsAudioQueue {
    const val MAX_CONCURRENT_EXPORTS = 5
    private const val WORK_PREFIX = "tts-audio-download"
    const val AUDIO_TAG = "tts-audio-export"
    private const val RECONCILE_MIN_INTERVAL_MS = 10_000L
    private val lock = Any()
    private val lastReconcileAtMs = java.util.concurrent.atomic.AtomicLong(0L)

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

        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            current.entries.removeAll { (_, value) ->
                value.chapterUrl == request.chapterUrl && value.novelUrl == request.novelUrl && value.source == request.source
            }
            current[generationJobId] = TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
                workRequestId = workRequest.id.toString(),
                outputDirectoryUri = request.outputDirectoryUri,
            )
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(workName(logicalJobId), ExistingWorkPolicy.REPLACE, workRequest)
            .enqueue()
    }

    fun cancel(context: Context, appPreferences: AppPreferences, workRequestId: String) {
        val id = runCatching { UUID.fromString(workRequestId) }.getOrNull() ?: return
        synchronized(lock) {
            val job = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.values.firstOrNull { it.workRequestId == workRequestId }
            if (job != null && job.phase.equals("VIDEO", true)) return
        }
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    fun cancelAll(context: Context, appPreferences: AppPreferences) {
        WorkManager.getInstance(context).cancelAllWorkByTag(AUDIO_TAG)
    }

    fun cancelAllReactive(context: Context) =
        WorkManager.getInstance(context).cancelAllWorkByTag(AUDIO_TAG)

    fun observeJobs(appPreferences: AppPreferences): Flow<Map<String, TtsAudioJobState>> =
        appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.flow()

    suspend fun reconcile(context: Context, appPreferences: AppPreferences) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastReconcileAtMs.get()
        if (now - last < RECONCILE_MIN_INTERVAL_MS) return
        if (!lastReconcileAtMs.compareAndSet(last, now)) return

        val workInfos = runCatching {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosByTag(AUDIO_TAG).get()
            }
        }.getOrNull() ?: return
        val byId = workInfos.associateBy { it.id.toString() }
        val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
        var changed = false

        for ((jobId, job) in current.toList()) {
            // A completed AUDIO checkpoint is durable independently of the original
            // TTS WorkRequest. The VIDEO worker intentionally clears workRequestId,
            // and WorkManager may later prune the old request. Never delete the job
            // while the WAV + timeline artifacts still exist; they are exactly what
            // the UI needs to offer direct VIDEO generation without rerunning TTS.
            if (job.status == TtsAudioJobStatus.SUCCESS &&
                job.phase.equals("AUDIO", true) &&
                job.audioUri.isNotBlank() &&
                job.timelineUri.isNotBlank() &&
                audioArtifactsExist(context, job.audioUri, job.timelineUri)
            ) {
                if (job.workRequestId.isNotBlank()) {
                    current[jobId] = job.copy(workRequestId = "")
                    changed = true
                }
                continue
            }

            if (job.phase.equals("VIDEO", true)) continue
            val info = byId[job.workRequestId]
            when (info?.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED -> Unit
                WorkInfo.State.SUCCEEDED -> Unit
                WorkInfo.State.CANCELLED,
                WorkInfo.State.FAILED,
                null -> {
                    deleteLocalAudioTemp(context, jobId)
                    current.remove(jobId)
                    changed = true
                }
            }
        }
        if (changed) appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current

        TtsVideoExportQueue.reconcile(context, appPreferences)
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

    private suspend fun audioArtifactsExist(context: Context, audioUri: String, timelineUri: String): Boolean =
        withContext(Dispatchers.IO) {
            documentExists(context, audioUri) && documentExists(context, timelineUri)
        }

    private fun documentExists(context: Context, uriString: String): Boolean = runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun deleteLocalAudioTemp(context: Context, jobId: String) {
        runCatching { java.io.File(context.cacheDir, "tts_audio/$jobId.wav").delete() }
            .onFailure { Timber.w(it, "TtsAudio: temp cleanup failed for $jobId") }
    }

    private fun workName(logicalJobId: String): String = "$WORK_PREFIX-$logicalJobId"
}
