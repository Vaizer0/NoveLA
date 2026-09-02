package my.noveldokusha.video_export

import my.noveldokusha.reader_visuals.ReaderVisualSnapshot

/**
 * Редактируемые пользователем визуальные настройки видео-экспорта (Video Studio).
 *
 * ВСЕ поля опциональны: `null` = унаследовать значение из настроек читалки
 * (Reader Appearance), из реальной темы приложения или из встроенного дефолта
 * видео. Конкретные значения, замороженные на момент постановки экспорта в
 * очередь, резолвятся методом [resolve] в [VideoStyleSnapshot] и
 * сериализуются в JSON. Воркер/рендер читают только [VideoStyleSnapshot].
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
    /** Вертикальный зазор между карточкой current и контекстом (px; null = 0). */
    val paragraphSpacing: Float? = null,
    /** Выравнивание строк абзаца в карточке (null → START). */
    val textAlignment: TextAlignment? = null,
    // ── Colors (null → reader appearance / тема / дефолт блюпринта) ──────────
    /** Цвет основного текста (null → авто по фону или цвет читалки). */
    val textColorArgb: Int? = null,
    val highlightColorArgb: Int? = null,
    /** Прозрачность подсветки 0..1 (null → текущий дефолт 0.5). */
    val highlightAlpha: Float? = null,
    /** Скругление подсветки слова (px; null → 6). */
    val highlightRadius: Float? = null,
    /** Вертикальный запас подсветки слова (px; null → 3). */
    val highlightPadding: Float? = null,
    /** Заливка карточки current (null → тема приложения / блюпринт). */
    val cardFillArgb: Int? = null,
    /** Обводка карточки current (null → тема приложения / блюпринт). */
    val cardStrokeArgb: Int? = null,
    /** Непрозрачность карточки current 0..1 (null → 1). */
    val currentCardAlpha: Float? = null,
    /** Непрозрачность контекстных абзацев prev/next 0..1 (null → 0.45). */
    val contextParagraphOpacity: Float? = null,
    // ── Layout (px канваса 1920×1080; null → дефолтные отступы) ────────────
    val marginX: Float? = null,
    /** Доп. ограничение ширины текста (null = автоматически по колонке). */
    val maxTextWidth: Float? = null,
    /** Вертикальный сдвиг конвейера абзацев вниз (px; null = 0). */
    val contentOffsetY: Float? = null,
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
     * значений + (опционально) реальной темы приложения [appCardColors].
     * Результат — замороженный слепок для постановки в очередь: изменение
     * настроек после enqueue не должно влиять на экспорт.
     */
    fun resolve(
        reader: ReaderVisualSnapshot,
        appCardColors: VideoFrameRenderer.CardColors? = null,
    ): VideoStyleSnapshot {
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
            paragraphSpacing = paragraphSpacing ?: VideoStyleDefaults.PARAGRAPH_SPACING,
            textAlignment = textAlignment ?: TextAlignment.START,
            textColorArgb = textColorArgb
                ?: VideoStyleSnapshot.resolveDefaultTextColor(reader),
            highlightColorArgb = highlightColorArgb ?: reader.ttsHighlightColorArgb,
            highlightAlpha = highlightAlpha ?: VideoStyleDefaults.HIGHLIGHT_ALPHA,
            highlightRadius = highlightRadius ?: VideoStyleDefaults.HIGHLIGHT_RADIUS,
            highlightPadding = highlightPadding ?: VideoStyleDefaults.HIGHLIGHT_PADDING,
            cardFillArgb = cardFillArgb ?: appCardColors?.fillArgb
                ?: VideoStyleSnapshot.DEFAULT_CARD_FILL_ARGB,
            cardStrokeArgb = cardStrokeArgb ?: appCardColors?.strokeArgb
                ?: VideoStyleSnapshot.DEFAULT_CARD_STROKE_ARGB,
            currentCardAlpha = currentCardAlpha ?: VideoStyleDefaults.CARD_ALPHA,
            contextParagraphOpacity = contextParagraphOpacity
                ?: VideoLayoutSpec.PREVIEW_ALPHA,
            marginX = marginX ?: VideoStyleDefaults.MARGIN_X,
            maxTextWidth = maxTextWidth,
            contentOffsetY = contentOffsetY ?: VideoStyleDefaults.CONTENT_OFFSET_Y,
            cardPaddingH = cardPaddingH ?: VideoStyleDefaults.CARD_PAD_H,
            cardPaddingTop = cardPaddingTop ?: VideoStyleDefaults.CARD_PAD_TOP,
            cardPaddingBottom = cardPaddingBottom ?: VideoStyleDefaults.CARD_PAD_BOTTOM,
            cardCornerRadius = cardCornerRadius ?: VideoStyleDefaults.CARD_CORNER_RADIUS,
            cardStrokeWidth = cardStrokeWidth ?: VideoStyleDefaults.CARD_STROKE_WIDTH,
            presentation = presentation,
        )
    }
}

private object VideoStyleDefaults {
    val MARGIN_X = 256f
    val CONTENT_OFFSET_Y = 0f
    val HIGHLIGHT_ALPHA = 0.5019608f // 0x80 / 255
    val HIGHLIGHT_RADIUS = 6f
    val HIGHLIGHT_PADDING = 3f
    val CARD_ALPHA = 1f
    val CARD_PAD_H = 56f
    val CARD_PAD_TOP = 40f
    val CARD_PAD_BOTTOM = 48f
    val CARD_CORNER_RADIUS = 20f
    val CARD_STROKE_WIDTH = 2f
    val PARAGRAPH_SPACING = 0f
}