package my.noveldokusha.core.appPreferences

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Статус задачи видео-экспорта главы (см. VIDEO_EXPORT_JOBS).
 */
@Immutable
@Serializable
enum class VideoExportJobStatus {
    /** Поставлена в очередь, ещё не выполнялась. */
    QUEUED,

    /** Выполняется (Worker поднят foreground-сервисом). */
    RUNNING,

    /** Готово: MP4 создан в выбранной папке. */
    SUCCESS,

    /** Не удалось выполнить. */
    FAILED,

    /** Пользователь отменил задачу (не ошибка). */
    CANCELLED,
}

/**
 * Запись одной задачи видео-экспорта главы (persisted между перезапусками).
 *
 * Хранится в SharedPreferences (VIDEO_EXPORT_JOBS) как Map<jobId, VideoExportJobState>.
 * jobId детерминирован (см. [makeVideoJobId]), поэтому повторный запуск той же
 * (книга, глава) обновляет ту же запись, а UI может показывать
 * «файл уже создан / идёт экспорт / ошибка» для конкретной главы.
 */
@Immutable
@Serializable
data class VideoExportJobState(
    val chapterUrl: String,
    val novelUrl: String,
    val chapterTitle: String = "",
    val status: VideoExportJobStatus,
    val message: String = "",
    val displayName: String = "",
    val documentUri: String = "",
    val progress: Int = 0,
    val workRequestId: String = "",
) {
    val isActive: Boolean
        get() = status == VideoExportJobStatus.QUEUED ||
            status == VideoExportJobStatus.RUNNING
}

/** Детерминированный id задачи видео-экспорта (книга, глава). */
fun makeVideoJobId(novelUrl: String, chapterUrl: String): String {
    val raw = "$novelUrl::$chapterUrl"
    val sha = java.security.MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return "video_$sha"
}