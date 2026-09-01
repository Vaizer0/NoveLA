package my.noveldokusha.core.appPreferences

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Статус задачи загрузки аудио главы (см. TTS_AUDIO_DOWNLOAD_JOBS). */
@Immutable
@Serializable
enum class TtsAudioJobStatus {
    /** Поставлена в очередь, ещё не выполнялась. */
    QUEUED,

    /** Выполняется (Worker поднят foreground-сервисом). */
    RUNNING,

    /** Готово: файл создан в выбранной папке. */
    SUCCESS,

    /** Не удалось выполнить. */
    FAILED,

    /** Пользователь отменил задачу (не ошибка). */
    CANCELLED,
}

/**
 * Запись одной задачи загрузки аудио главы (persisted между перезапусками).
 *
 * Хранится в SharedPreferences (TTS_AUDIO_DOWNLOAD_JOBS) как Map<jobId, TtsAudioJobState>.
 * jobId детерминирован (см. TtsAudioExportRequest.makeJobId), поэтому повторный
 * запуск того же (книга, глава, источник) обновляет ту же запись, а UI может
 * показывать «файл уже создан / идёт загрузка / ошибка» для конкретной главы.
 */
@Immutable
@Serializable
data class TtsAudioJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String = "",
    val status: TtsAudioJobStatus,
    /** Причину ошибки (локальная строка) для UI/уведомления. */
    val message: String = "",
    /** Имя созданного файла на успех (из SAF) или null. */
    val displayName: String = "",
    /** content:// URI созданного файла на успех (для «открыть/прослушать»). */
    val documentUri: String = "",
    /** Прогресс 0..100 (персистится воркером для восстановления после перезапуска). */
    val progress: Int = 0,
) {
    val isActive: Boolean
        get() = status == TtsAudioJobStatus.QUEUED ||
            status == TtsAudioJobStatus.RUNNING
}