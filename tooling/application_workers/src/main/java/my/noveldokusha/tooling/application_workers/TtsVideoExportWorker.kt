package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Compatibility entry point for WorkManager requests created by older NoveLA builds.
 * Existing requests therefore receive the same durable SAF behavior as new requests.
 */
class TtsVideoExportWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val delegate = TtsVideoExportWorkerV2(context, workerParameters)

    override suspend fun getForegroundInfo(): ForegroundInfo = delegate.getForegroundInfo()

    override suspend fun doWork(): Result = delegate.doWork()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_TIMELINE_URI = "timelineUri"
        const val KEY_PARENT_DIRECTORY_URI = "parentDirectoryUri"
        const val KEY_OUTPUT_DIRECTORY_URI = "outputDirectoryUri"
        const val KEY_CHAPTER_TITLE = "chapterTitle"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
    }
}
