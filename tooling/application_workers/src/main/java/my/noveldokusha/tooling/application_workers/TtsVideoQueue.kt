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

    /** Idempotent for an exact immutable request while its persisted job is active. */
    fun enqueue(context: Context, request: TtsVideoRequest): Boolean {
        val prefs = TtsVideoPreferences(context)
        synchronized(lock) {
            val jobs = prefs.jobs().toMutableMap()
            val existing = jobs[request.jobId]
            if (existing != null && existing.status in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return false

            val work = buildWork(request)
            val workManager = WorkManager.getInstance(context)
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
            workManager.beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work).enqueue()
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
        synchronized(lock) {
            val current = prefs.jobs()[jobId] ?: return
            current.workRequestId.takeIf(String::isNotBlank)?.let { id ->
                runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id)) }
            }
            prefs.saveJobs(prefs.jobs().toMutableMap().apply {
                put(jobId, current.copy(status = TtsVideoJobStatus.CANCELLED, progress = 0, message = "cancelled"))
            })
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
        synchronized(lock) {
            val prefs = TtsVideoPreferences(context)
            val current = prefs.jobs().toMutableMap()
            current.replaceAll { _, job ->
                if (job.isActive) job.copy(status = TtsVideoJobStatus.CANCELLED, progress = 0, message = "cancelled") else job
            }
            prefs.saveJobs(current)
        }
    }

    /**
     * Repairs persisted active jobs whose WorkManager request disappeared or was cancelled by
     * process death/force-stop/system cleanup. Intentional user cancellation is not recovered
     * because cancel() changes the persisted state to CANCELLED before cancelling WorkManager.
     */
    suspend fun recoverOrphanedJobs(context: Context) = withContext(Dispatchers.IO) {
        val prefs = TtsVideoPreferences(context)
        val workManager = WorkManager.getInstance(context)
        val jobs = prefs.jobs()
        jobs.forEach { (jobId, job) ->
            if (job.status !in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return@forEach
            val request = job.requestJson.toTtsVideoRequest() ?: run {
                synchronized(lock) {
                    prefs.saveJobs(prefs.jobs().toMutableMap().apply {
                        put(jobId, job.copy(status = TtsVideoJobStatus.FAILED, progress = 0, message = "Persisted video request is invalid"))
                    })
                }
                return@forEach
            }
            val workId = runCatching { UUID.fromString(job.workRequestId) }.getOrNull()
            val state = workId?.let { id -> runCatching { workManager.getWorkInfoById(id).get() }.getOrNull() }
            val alive = state?.state == androidx.work.WorkInfo.State.ENQUEUED ||
                state?.state == androidx.work.WorkInfo.State.RUNNING ||
                state?.state == androidx.work.WorkInfo.State.BLOCKED
            if (alive) return@forEach

            synchronized(lock) {
                val current = prefs.jobs()[jobId] ?: return@synchronized
                if (current.status !in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return@synchronized
                // Release the idempotency guard for the dead WorkManager request. enqueue()
                // then creates one replacement request with the same immutable jobId/snapshot.
                prefs.saveJobs(prefs.jobs().toMutableMap().apply {
                    put(jobId, current.copy(status = TtsVideoJobStatus.FAILED, progress = 0, message = "interrupted; recovered"))
                })
            }
            enqueue(context, request)
        }
    }

    private fun buildWork(request: TtsVideoRequest) = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
        .setInputData(workDataOf(TtsVideoExportWorker.KEY_REQUEST_JSON to request.serialize()))
        .addTag("tts-video-job-${request.jobId}")
        .build()
}
