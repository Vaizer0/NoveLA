package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoRequest
import my.noveldokusha.text_to_speech.serialize
import my.noveldokusha.text_to_speech.toTtsVideoRequest
import java.util.UUID

object TtsVideoQueue {
    const val CHAIN_NAME = "tts-video-download"
    private val lock = Any()

    /**
     * Idempotent for an exact immutable request: repeated enqueue calls for the same jobId
     * while that request is queued/running are ignored. Failed/cancelled jobs may be retried.
     */
    fun enqueue(context: Context, request: TtsVideoRequest): Boolean {
        val prefs = TtsVideoPreferences(context)
        synchronized(lock) {
            val jobs = prefs.jobs().toMutableMap()
            val existing = jobs[request.jobId]
            if (existing != null && existing.status in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return false

            val work = buildWork(request)
            val wm = WorkManager.getInstance(context)
            // APPEND_OR_REPLACE preserves the single sequential export chain. The persisted
            // request snapshot is written before enqueue so the UI has a durable QUEUED record.
            jobs[request.jobId] = TtsVideoJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsVideoJobStatus.QUEUED,
                workRequestId = work.id.toString(),
                requestJson = request.serialize(),
            )
            prefs.saveJobs(jobs)
            wm.beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work).enqueue()
            return true
        }
    }

    fun retry(context: Context, jobId: String): Boolean {
        val prefs = TtsVideoPreferences(context)
        synchronized(lock) {
            val current = prefs.jobs()[jobId] ?: return false
            if (current.status !in setOf(TtsVideoJobStatus.FAILED, TtsVideoJobStatus.CANCELLED)) return false
            val request = current.requestJson.toTtsVideoRequest() ?: return false
            if (request.jobId != jobId) return false
            return enqueue(context, request)
        }
    }

    fun cancel(context: Context, jobId: String) {
        val prefs = TtsVideoPreferences(context)
        val current = prefs.jobs()[jobId] ?: return
        current.workRequestId.takeIf(String::isNotBlank)?.let { id ->
            runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id)) }
        }
        prefs.saveJobs(prefs.jobs().toMutableMap().apply {
            put(jobId, current.copy(status = TtsVideoJobStatus.CANCELLED, progress = 0, message = "cancelled"))
        })
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }

    /** Re-enqueues persisted QUEUED/RUNNING jobs whose WorkManager item disappeared after process death. */
    suspend fun recoverOrphanedJobs(context: Context) = withContext(Dispatchers.IO) {
        val prefs = TtsVideoPreferences(context)
        val jobs = prefs.jobs()
        jobs.forEach { (jobId, job) ->
            if (job.status !in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return@forEach
            val request = job.requestJson.toTtsVideoRequest() ?: run {
                prefs.saveJobs(prefs.jobs().toMutableMap().apply {
                    put(jobId, job.copy(status = TtsVideoJobStatus.FAILED, progress = 0, message = "Persisted video request is invalid"))
                })
                return@forEach
            }
            val workId = runCatching { UUID.fromString(job.workRequestId) }.getOrNull()
            val state = workId?.let { runCatching { WorkManager.getInstance(context).getWorkInfoById(it).get() }.getOrNull() }
            if (state == null) {
                enqueue(context, request)
            }
        }
    }

    private fun buildWork(request: TtsVideoRequest) = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
        .setInputData(workDataOf(TtsVideoExportWorker.KEY_REQUEST_JSON to request.serialize()))
        .addTag("tts-video-job-${request.jobId}")
        .build()
}
