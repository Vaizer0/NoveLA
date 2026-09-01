package my.noveldokusha.tooling.application_workers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioJobStatus
import my.noveldokusha.text_to_speech.TtsAudioExportRequest

/**
 * Очередь загрузки аудио глав.
 *
 * Все задачи выполняются СТРОГО ПОСЛЕДОВАТЕЛЬНО: каждая добавляется через
 * beginUniqueWork(CHAIN_NAME, APPEND) в единую цепочку, поэтому при клике на
 * несколько глав они не синтезируются параллельно (голос/CPU/память общие).
 *
 * Статус каждой главы персистится в SharedPreferences (TTS_AUDIO_DOWNLOAD_JOBS)
 * и наблюдается UI через [observeJobs]; воркер переводит состояние
 * QUEUED → RUNNING → SUCCESS/FAILED.
 */
object TtsAudioQueue {
    const val CHAIN_NAME = "tts-audio-download"

    private val lock = Any()

    /** Ставлю задачу в конец очереди и фиксирую состояние QUEUED. */
    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        val jobId = request.jobId
        updateState(appPreferences, jobId) {
            TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                status = TtsAudioJobStatus.QUEUED,
            )
        }

        val workRequest = OneTimeWorkRequestBuilder<TtsAudioExportWorker>()
            .setInputData(
                workDataOf(
                    TtsAudioExportWorker.KEY_JOB_ID to jobId,
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

        WorkManager.getInstance(context)
            .beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND, workRequest)
            .enqueue()
    }

    /** Отмена всей очереди (V1: отмена по одной задаче не поддерживается). */
    fun cancelAll(context: Context, appPreferences: AppPreferences) {
        cancelAllReactive(context)
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            current.entries.removeAll { it.value.isActive }
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }

    /**
     * Отмена через BroadcastReceiver: только снимает цепочку WorkManager;
     * активные записи состояний почистят сами воркеры в своём кансель-пути.
     */
    fun cancelAllReactive(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN_NAME)
    }

    /** Поток статусов всех задач загрузки аудио (jobId → состояние). */
    fun observeJobs(appPreferences: AppPreferences): Flow<Map<String, TtsAudioJobState>> =
        appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.flow()

    /** Атомарное обновление состояния [jobId]. */
    fun updateState(
        appPreferences: AppPreferences,
        jobId: String,
        transform: (TtsAudioJobState?) -> TtsAudioJobState,
    ) {
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val updated = transform(current[jobId])
            if (updated.isActive) {
                current[jobId] = updated
            } else {
                // Завершённые записи (SUCCESS/FAILED) сохраняем: UI по ним показывает
                // «файл уже создан»/«ошибка» для соответствующих глав.
                current[jobId] = updated
            }
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }
}