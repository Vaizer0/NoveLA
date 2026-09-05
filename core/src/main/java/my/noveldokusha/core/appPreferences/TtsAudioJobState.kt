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

@Immutable
@Serializable
data class TtsAudioJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String = "",
    val source: TtsAudioSource = TtsAudioSource.ORIGINAL,
    val status: TtsAudioJobStatus,
    val message: String = "",
    val displayName: String = "",
    val documentUri: String = "",
    val progress: Int = 0,
    /** Current export phase: AUDIO while creating WAV/JSON, VIDEO while rendering MP4. */
    val phase: String = "AUDIO",
    /** Current temporary/generated MP4 byte size during VIDEO phase. */
    val videoSizeBytes: Long = 0L,
    val workRequestId: String = "",
) {
    val isActive: Boolean
        get() = status == TtsAudioJobStatus.QUEUED || status == TtsAudioJobStatus.RUNNING
}
