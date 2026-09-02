package my.noveldokusha.video_export

import my.noveldokusha.reader_visuals.ReaderVisualSnapshot

/**
 * Редактируемые пользователем визуальные настройки видео-экспорта (Video Studio).
 *
 * ВСЕ поля опциональны: `null` = унаследовать значение из настроек читалки
 * (Reader Appearance) или из встроенного дефолта видео. Конкретные значения,
 * замороженные на момент постановки экспорта в очередь, резолвятся методом
 * [resolve] в [VideoStyleSnapshot] и сериализуются в JSON.
 *
 * Отделено от [ReaderVisualSnapshot]: читалка и видео имеют независимые
 * настройки внешнего вида, но видео берёт читалку за основу (defaults) там,
 * где у пользователя нет собственного значения.
 */
data class VideoStyleSettings(
    // ── Typography (null → reader appearance) ──────────────────────────────
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val letterSpacing: Float? = null,
    val lineHeight: Float? = null,
    // ── Layout (px канваса 1920×1080; null → дефолтные отступы) ────────────
    val marginX: Float? = null,
    /** Доп. ограничение ширины текста (null = автоматически по колонке). */
    val maxTextWidth: Float? = null,
    /** Вертикальный сдвиг конвейера абзацев вниз (px; null = 0). */
    val contentOffsetY: Float? = null,
    // ── Word highlight ─────────────────────────────────────────────────────
    val highlightColorArgb: Int? = null,
    /** Прозрачность подсветки 0..1 (null → текущий дефолт 0.5). */
    val highlightAlpha: Float? = null,
    // ── Card (current) ─────────────────────────────────────────────────────
    val cardPaddingH: Float? = null,
    val cardPaddingTop: Float? = null,
    val cardPaddingBottom: Float? = null,
    val cardCornerRadius: Float? = null,
    val cardStrokeWidth: Float? = null,
    // ── Paragraph presentation ─────────────────────────────────────────────
    val presentation: ParagraphPresentation = ParagraphPresentation.CURRENT_WITH_CONTEXT,
) {
    /**
     * Резолвит ПОЛНЫЙ эффективный стиль видео из читалки + собственных
     * значений. Результат — замороженный слепок для постановки в очередь:
     * изменение настроек после enqueue не должно влиять на экспорт.
     */
    fun resolve(reader: ReaderVisualSnapshot): VideoStyleSnapshot {
        val fontSizeSp = fontSizeSp ?: reader.fontSizeSp
        return VideoStyleSnapshot(
            schemaVersion = VideoStyleSnapshot.SCHEMA_VERSION,
            fontFamily = fontFamily ?: reader.fontFamily,
            fontSizeSp = fontSizeSp,
            fontSizePx = ReaderVisualSnapshot.computeBaseFontPx(fontSizeSp),
            bold = bold ?: false,
            italic = italic ?: false,
            letterSpacing = letterSpacing ?: reader.letterSpacing,
            lineHeight = lineHeight ?: reader.lineHeight,
            marginX = marginX ?: VideoStyleDefaults.MARGIN_X,
            maxTextWidth = maxTextWidth,
            contentOffsetY = contentOffsetY ?: VideoStyleDefaults.CONTENT_OFFSET_Y,
            highlightColorArgb = highlightColorArgb ?: reader.ttsHighlightColorArgb,
            highlightAlpha = highlightAlpha ?: VideoStyleDefaults.HIGHLIGHT_ALPHA,
            cardPaddingH = cardPaddingH ?: VideoStyleDefaults.CARD_PAD_H,
            cardPaddingTop = cardPaddingTop ?: VideoStyleDefaults.CARD_PAD_TOP,
            cardPaddingBottom = cardPaddingBottom ?: VideoStyleDefaults.CARD_PAD_BOTTOM,
            cardCornerRadius = cardCornerRadius ?: VideoStyleDefaults.CARD_CORNER_RADIUS,
            cardStrokeWidth = cardStrokeWidth ?: VideoStyleDefaults.CARD_STROKE_WIDTH,
            presentation = presentation,
        )
    }
}

private val VideoStyleDefaults = object {
    const val MARGIN_X = 256f
    const val CONTENT_OFFSET_Y = 0f
    const val HIGHLIGHT_ALPHA = 0.5019608f // 0x80 / 255
    const val CARD_PAD_H = 56f
    const val CARD_PAD_TOP = 40f
    const val CARD_PAD_BOTTOM = 48f
    const val CARD_CORNER_RADIUS = 20f
    const val CARD_STROKE_WIDTH = 2f
}