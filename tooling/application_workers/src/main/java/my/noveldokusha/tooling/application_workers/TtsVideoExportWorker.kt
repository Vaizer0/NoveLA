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
        const val KEY_JOB_ID = TtsVideoExportWorkerV2.KEY_JOB_ID
        const val KEY_AUDIO_URI = TtsVideoExportWorkerV2.KEY_AUDIO_URI
        const val KEY_TIMELINE_URI = TtsVideoExportWorkerV2.KEY_TIMELINE_URI
        const val KEY_PARENT_DIRECTORY_URI = TtsVideoExportWorkerV2.KEY_PARENT_DIRECTORY_URI
        const val KEY_OUTPUT_DIRECTORY_URI = TtsVideoExportWorkerV2.KEY_OUTPUT_DIRECTORY_URI
        const val KEY_CHAPTER_TITLE = TtsVideoExportWorkerV2.KEY_CHAPTER_TITLE
        const val KEY_DISPLAY_NAME = TtsVideoExportWorkerV2.KEY_DISPLAY_NAME
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_CHAPTER_URL = "chapterUrl"
        const val KEY_SOURCE = "source"
    }
}
