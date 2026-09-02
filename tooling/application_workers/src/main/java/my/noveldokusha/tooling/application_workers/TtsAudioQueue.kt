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
 * beginUniqueWork(CHAIN_NAME, APPEND_OR_REPLACE) в единую цепочку, поэтому при
 * клике на несколько глав они не синтезируются параллельно (голос/CPU/память общие),
 * а «мёртвая» цепочка (после kill/force-stop) заменяется, а не «отравляет» новые
 * задачи вечным QUEUED.
 *
 * Статус каждой главы персистится в SharedPreferences (TTS_AUDIO_DOWNLOAD_JOBS)
 * и наблюдается UI через [observeJobs]; воркер переводит состояние
 * QUEUED → RUNNING → SUCCESS/FAILED. Каждая запись хранит WorkRequest UUID
 * (TtsAudioJobState.workRequestId), чтобы [reconcile] мог сверять её с реальным
 * состоянием WorkManager.
 */
object TtsAudioQueue {
    const val CHAIN_NAME = "tts-audio-download"

    private val lock = Any()

    /** Ставлю задачу в конец очереди и фиксирую состояние QUEUED. */
    fun enqueue(context: Context, appPreferences: AppPreferences, request: TtsAudioExportRequest) {
        val jobId = request.jobId

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
                    TtsAudioExportWorker.KEY_TRANSLATION_SOURCE_LANG to request.translationSourceLang,
                    TtsAudioExportWorker.KEY_TRANSLATION_TARGET_LANG to request.translationTargetLang,
                )
            )
            .build()

        // Сохраняем состояние с реальным UUID WorkRequest ДО постановки в очередь,
        // чтобы UI с первой секунды мог сверять его с WorkManager (см. [reconcile]).
        updateState(appPreferences, jobId) {
            TtsAudioJobState(
                chapterUrl = request.chapterUrl,
                novelUrl = request.novelUrl,
                chapterTitle = request.chapterTitle,
                source = request.source,
                status = TtsAudioJobStatus.QUEUED,
                workRequestId = workRequest.id.toString(),
            )
        }

        // APPEND_OR_REPLACE: если предыдущая цепочка уже завершилась/отменена
        // (например из-за kill/force-stop), она заменяется новой — «мёртвая»
        // цепочка не может навсегда заблокировать новые экспорты в QUEUED.
        // Пока цепочка действительно выполняется, новый ворк просто дописывается
        // в конец (sequential V1 сохраняется).
        WorkManager.getInstance(context)
            .beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
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

    /**
     * Сверяет персистентные активные записи (QUEUED/RUNNING) с реальным состоянием
     * WorkManager. После kill/force-stop процесса воркер мог не успеть донести свой
     * финальный статус — запись остаётся на 0..N% «навсегда» и UI, видя isActive==true,
     * не даёт её перезапустить. Здесь такие «зомби» переводятся в FAILED (перезапускаемы),
     * либо в CANCELLED, если соответствующий WorkManager действительно был отменён.
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
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            var changed = false
            for ((jobId, job) in current) {
                if (!job.isActive) continue
                val wid = job.workRequestId
                // Без UUID (старые записи до этого патча) или с UUID, которого больше
                // нет в "живой" цепочке WorkManager → запись больше не выполняется.
                val alive = wid.isNotBlank() && runningIds.contains(wid)
                if (alive) continue
                changed = true
                current[jobId] = job.copy(
                    status = if (wid.isNotBlank() && cancelledIds.contains(wid)) {
                        TtsAudioJobStatus.CANCELLED
                    } else {
                        TtsAudioJobStatus.FAILED
                    },
                    message = "interrupted",
                )
            }
            if (changed) {
                Timber.w("TtsAudio: reconciled persisted jobs; repaired stale active ones")
                appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
            }
        }
    }

    /**
     * Атомарное обновление состояния [jobId].
     *
     * [transform] получает текущую запись (null, если её уже нет — например, после
     * [cancelAll], удаляющей активные записи) и возвращает новое значение. Возврат
     * null означает «ничего не писать»: запись уже убрана извне, воркер не должен
     * ни воскрешать её, ни падать на `it!!` в cancel-пути после отмены очереди.
     */
    fun updateState(
        appPreferences: AppPreferences,
        jobId: String,
        transform: (TtsAudioJobState?) -> TtsAudioJobState?,
    ) {
        synchronized(lock) {
            val current = appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value.toMutableMap()
            val updated = transform(current[jobId]) ?: return@synchronized
            // Завершённые записи (SUCCESS/FAILED/CANCELLED) сохраняем: UI по ним
            // показывает «файл уже создан»/«ошибка»/«отменено» для соответствующих глав.
            current[jobId] = updated
            appPreferences.TTS_AUDIO_DOWNLOAD_JOBS.value = current
        }
    }
}