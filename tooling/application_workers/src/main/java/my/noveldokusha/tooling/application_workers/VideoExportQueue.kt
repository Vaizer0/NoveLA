package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.VideoExportJobState
import my.noveldokusha.core.appPreferences.VideoExportJobStatus
import timber.log.Timber

/**
 * Очередь видео-экспорта глав (MP4).
 *
 * Все задачи выполняются СТРОГО ПОСЛЕДОВАТЕЛЬНО: каждая добавляется через
 * beginUniqueWork(CHAIN_NAME, APPEND_OR_REPLACE) в единую цепочку.
 *
 * Статус каждой главы персистится в SharedPreferences (VIDEO_EXPORT_JOBS)
 * и наблюдается UI через [observeJobs]; воркер переводит состояние
 * QUEUED → RUNNING → SUCCESS/FAILED.
 */
object VideoExportQueue {
    const val CHAIN_NAME = "tts-video-export"

    private val lock = Any()

    fun enqueue(context: Context, appPreferences: AppPreferences, request: VideoExportWorkRequest) {
        val jobId = request.jobId

        val workRequest = OneTimeWorkRequestBuilder<VideoExportWorker>()
            .setInputData(
                workDataOf(
                    VideoExportWorker.KEY_JOB_ID to jobId,
                    VideoExportWorker.KEY_CHAPTER_TITLE to request.chapterTitle,
                    VideoExportWorker.KEY_NOVEL_TITLE to request.novelTitle,
                    VideoExportWorker.KEY_CHAPTER_URL to request.chapterUrl,
                    VideoExportWorker.KEY_SOURCE_ID to request.sourceId,
                    VideoExportWorker.KEY_PARAGRAPHS_JSON to request.paragraphsJson,
                    VideoExportWorker.KEY_SNAPSHOT_JSON to request.snapshotJson,
                    VideoExportWorker.KEY_ENGINE_PACKAGE to request.enginePackage,
                    VideoExportWorker.KEY_VOICE_ID to request.voiceId,
                    VideoExportWorker.KEY_SPEED to request.speed,
                    VideoExportWorker.KEY_PITCH to request.pitch,
                    VideoExportWorker.KEY_OUTPUT_DIRECTORY_URI to request.outputDirectoryUri,
                )
            )
            .build()

        updateState(appPreferences, jobId) {
            VideoExportJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                status = VideoExportJobStatus.QUEUED,
                workRequestId = workRequest.id.toString(),
            )
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
            .enqueue()
    }

    fun cancelAllReactive(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }

    fun observeJobs(appPreferences: AppPreferences): Flow<Map<String, VideoExportJobState>> =
        appPreferences.VIDEO_EXPORT_JOBS.flow()

    /**
     * Сверяет персистентные активные записи (QUEUED/RUNNING) с реальным состоянием
     * WorkManager. «Зомби»-записи (после kill/force-stop) переводятся в FAILED.
     */
    suspend fun reconcile(context: Context, appPreferences: AppPreferences) {
        val workManager = WorkManager.getInstance(context)
        val chainStates = runCatching {
            workManager.getWorkInfosForUniqueWorkFlow(CHAIN_NAME).first()
        }.getOrNull() ?: return

        val runningIds = chainStates.asSequence()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            .map { it.id.toString() }
            .toSet()
        val cancelledIds = chainStates.asSequence()
            .filter { it.state == WorkInfo.State.CANCELLED }
            .map { it.id.toString() }
            .toSet()

        synchronized(lock) {
            val current = appPreferences.VIDEO_EXPORT_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (!job.isActive) continue
                val wid = job.workRequestId
                val alive = wid.isNotBlank() && runningIds.contains(wid)
                if (alive) continue
                changed = true
                current[jobId] = job.copy(
                    status = if (wid.isNotBlank() && cancelledIds.contains(wid)) {
                        VideoExportJobStatus.CANCELLED
                    } else {
                        VideoExportJobStatus.FAILED
                    },
                    message = "interrupted",
                )
            }
            if (changed) {
                Timber.w("VideoExport: reconciled persisted jobs; repaired stale active ones")
                appPreferences.VIDEO_EXPORT_JOBS.value = current
            }
        }
    }

    fun updateState(
        appPreferences: AppPreferences,
        jobId: String,
        transform: (VideoExportJobState?) -> VideoExportJobState,
    ) {
        synchronized(lock) {
            val current = appPreferences.VIDEO_EXPORT_JOBS.value.toMutableMap()
            current[jobId] = transform(current[jobId])
            appPreferences.VIDEO_EXPORT_JOBS.value = current
        }
    }
}

/** Flat request passed to [VideoExportQueue.enqueue]. Not [TtsVideoExportRequest]. */
data class VideoExportWorkRequest(
    val jobId: String,
    val novelTitle: String,
    val novelUrl: String,
    val chapterUrl: String,
    val chapterTitle: String,
    val sourceId: String,
    val paragraphsJson: String,
    val snapshotJson: String,
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    val outputDirectoryUri: String,
)