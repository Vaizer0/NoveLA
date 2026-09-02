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
import java.util.concurrent.TimeUnit

object TtsVideoQueue {
    const val CHAIN_NAME = "tts-video-download"
    private val lock = Any()

    fun enqueue(context: Context, request: TtsVideoRequest) {
        val prefs = TtsVideoPreferences(context)
        val work = OneTimeWorkRequestBuilder<TtsVideoExportWorker>()
            .setInputData(workDataOf(TtsVideoExportWorker.KEY_REQUEST_JSON to request.serialize()))
            .build()
        synchronized(lock) {
            val jobs = prefs.jobs().toMutableMap()
            jobs[request.jobId] = TtsVideoJobState(request.chapterUrl, request.novelUrl, request.chapterTitle, request.source, TtsVideoJobStatus.QUEUED, work.id.toString())
            prefs.saveJobs(jobs)
            WorkManager.getInstance(context).beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work).enqueue()
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }
}
