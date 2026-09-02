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

object TtsVideoQueue {
    const val CHAIN_NAME = "tts-video-download"
    private val lock = Any()

    fun enqueue(context: Context, request: TtsVideoRequest): Boolean {
        val prefs = TtsVideoPreferences(context)
        synchronized(lock) {
            val jobs = prefs.jobs().toMutableMap()
            val existing = jobs[request.jobId]
            if (existing != null && existing.status in setOf(TtsVideoJobStatus.QUEUED, TtsVideoJobStatus.RUNNING)) {
                return false
            }
            val work = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
                .setInputData(workDataOf(TtsVideoExportWorker.KEY_REQUEST_JSON to request.serialize()))
                .build()
            jobs[request.jobId] = TtsVideoJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsVideoJobStatus.QUEUED,
                workRequestId = work.id.toString(),
            )
            prefs.saveJobs(jobs)
            WorkManager.getInstance(context).beginUniqueWork(
                CHAIN_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            ).enqueue()
            return true
        }
    }

    fun cancel(context: Context, jobId: String) {
        val prefs = TtsVideoPreferences(context)
        val workId = prefs.jobs()[jobId]?.workRequestId?.takeIf(String::isNotBlank)
        if (workId != null) runCatching { WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(workId)) }
        else WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
        prefs.jobs()[jobId]?.let { current ->
            prefs.saveJobs(prefs.jobs().toMutableMap().apply {
                put(jobId, current.copy(status = TtsVideoJobStatus.CANCELLED, progress = 0, message = "cancelled"))
            })
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }
}
