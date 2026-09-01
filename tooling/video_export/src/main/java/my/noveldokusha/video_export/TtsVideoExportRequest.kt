package my.noveldokusha.video_export

/**
 * Запрос на экспорт главы в видео. Помимо текстовой задачи несёт ЗАМОРОЖЕННЫЙ
 * на момент постановки в очередь слепок визуальных настроек читалки
 * ([ReaderVisualSnapshot]), чтобы последующие изменения настроек не влияли на
 * уже поставленные/выполняющиеся задачи.
 */
data class TtsVideoExportRequest(
    val chapterTitle: String,
    val novelTitle: String,
    val chapterUrl: String,
    val sourceId: String,
    /** Чистый (уже подготовленный) текст абзацев для рендера и синтеза. */
    val paragraphs: List<String>,
    /** JSON слепка визуальных настроек (см. my.noveldokusha.reader_visuals). */
    val snapshotJson: String,
    /** Движок TTS, из которого берём голос (переиспользуется профиль аудио). */
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
)
