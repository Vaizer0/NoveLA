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
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import timber.log.Timber

/**
 * Очередь загрузки аудио глав.
 *
 * Все задачи выполняются СТРОГО ПОСЛЕДОВАТЕЛЬНО: каждая добавляется через
 * beginUniqueWork(CHAIN_NAME, APPEND_OR_REPLACE) в единую цепочку. Повторная
 * постановка ТОГО ЖЕ jobId, пока запись активна, игнорируется до создания нового
 * WorkRequest, поэтому повторный клик/повторная композиция UI не создаёт дубликат.
 *
 * Статус каждой главы персистится в SharedPreferences (TTS_AUDIO_DOWNLOAD_JOBS)
 * и наблюдается UI через [observeJobs].
 */
object TtsAudioQueue {
    const val CHAIN_NAME = "tts-audio-download"
    private val lock = Any()

    /** Идемпотентно ставит задачу в конец очереди. */
    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        synchronized(lock) {
            val persisted = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value
            val existing = persisted[request.jobId]
            if (existing != null && existing.isActive) {
                Timber.d("TtsAudio: duplicate enqueue ignored for active job ${request.jobId}")
                return
            }

            val workRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
                .setInputData(
                    workDataOf(
                        TtsAudioExportWorker.KEY_JOB_ID to request.jobId,
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
                    )
                )
                .build()

            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            current[request.jobId] = TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
                workRequestId = workRequest.id.toString(),
            )
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current

            WorkManager.getInstance(context)
                .beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
                .enqueue()
        }
    }

    /** Отмена всей очереди (V1: отмена по одной задаче не поддерживается). */
    fun cancelAll(context: Context, appPreferences: AppPreferences) {
        cancelAllReactive(context)
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            current.replaceAll { _, job ->
                if (job.isActive) job.copy(status = TtsAudioJobStatus.CANCELLED, message = "cancelled") else job
            }
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    fun cancelAllReactive(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }

    fun observeJobs(appPreferences: AppPreferences): Flow<Map<String, TtsAudioJobState>> =
        appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.flow()

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
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (!job.isActive) continue
                val wid = job.workRequestId
                val alive = wid.isNotBlank() && runningIds.contains(wid)
                if (alive) continue
                changed = true
                current[jobId] = job.copy(
                    status = if (wid.isNotBlank() && cancelledIds.contains(wid)) TtsAudioJobStatus.CANCELLED else TtsAudioJobStatus.FAILED,
                    message = "interrupted",
                )
            }
            if (changed) {
                Timber.w("TtsAudio: reconciled persisted jobs; repaired stale active ones")
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
}
