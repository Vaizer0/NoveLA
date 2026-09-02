package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoRequest
import my.noveldokusha.text_to_speech.serialize
import my.noveldokusha.text_to_speech.toTtsVideoRequest

object TtsVideoQueue {
    const val CHAIN_NAME = "tts-video-download"
    private val lock = Any()

    fun enqueue(context: Context, request: TtsVideoRequest): Boolean {
        val prefs = TtsVideoPreferences(context)
        synchronized(lock) {
            val jobs = prefs.jobs().toMutableMap()
            val existing = jobs[request.jobId]
            if (existing != null && existing.status in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) return false
            val work = buildWork(request)
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
            WorkManager.getInstance(context).beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work).enqueue()
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
            runCatching { WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(id)) }
        }
        prefs.saveJobs(prefs.jobs().toMutableMap().apply {
            put(jobId, current.copy(status = TtsVideoJobStatus.CANCELLED, progress = 0, message = "cancelled"))
        })
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }

    private fun buildWork(request: TtsVideoRequest) = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
        .setInputData(workDataOf(TtsVideoExportWorker.KEY_REQUEST_JSON to request.serialize()))
        .build()
}
