package my.noveldokusha.video_export

import org.json.JSONObject
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
    // ── Side artwork ───────────────────────────────────────────────────────
    /** Левая иллюстрация (null/без файла — выключена). */
    val leftArtwork: VideoArtworkSettings? = null,
    /** Правая иллюстрация (null/без файла — выключена). */
    val rightArtwork: VideoArtworkSettings? = null,
    // ── Paragraph presentation ─────────────────────────────────────────────
    val presentation: ParagraphPresentation = ParagraphPresentation.CURRENT_WITH_CONTEXT,
    // ── Slide-show (Phase G) ───────────────────────────────────────────────
    /** Конфиг показа слайдов (null — отключено, как Phase F). */
    val slideshowConfig: SlideshowConfig? = null,
    /** Активные слайды (порядок = порядок показа). */
    val slideshowItems: List<ArtworkItem> = emptyList(),
    /** Идентичность главы (для детерминированного seed). */
    val chapterIdentity: String = "",
    /** Идентичность источника. */
    val sourceId: String = "",
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
            leftArtwork = leftArtwork?.let { resolveArtwork(it) },
            rightArtwork = rightArtwork?.let { resolveArtwork(it) },
            presentation = presentation,
            slideshowConfig = slideshowConfig
                ?: SlideshowConfig.disabled().copy(
                    randomSeed = SlideshowScheduler.stableHash(
                        chapterIdentity, sourceId, "", slideshowItems.joinToString { it.fileName },
                    ),
                ),
            slideshowItems = slideshowItems.filter { it.enabled && it.fileName.isNotBlank() },
            chapterIdentity = chapterIdentity,
            sourceId = sourceId,
        )
    }

    /** Арт активен только при непустом файле-ссылке; иначе — ничего не рисуем. */
    private fun resolveArtwork(s: VideoArtworkSettings): VideoArtwork? {
        val fileName = s.fileName?.takeIf { it.isNotBlank() } ?: return null
        val defaults = VideoArtwork.DEFAULTS
        return VideoArtwork(
            fileName = fileName,
            widthFraction = (s.widthFraction ?: defaults.widthFraction)
                .coerceIn(0.02f, VideoArtwork.MAX_WIDTH_FRACTION),
            heightCapFraction = (s.heightCapFraction ?: defaults.heightCapFraction)
                .coerceIn(0.1f, 1f),
            verticalAlignment = s.verticalAlignment ?: ArtworkVerticalAlignment.CENTER,
            opacity = (s.opacity ?: defaults.opacity).coerceIn(0f, 1f),
            fitMode = s.fitMode ?: ArtworkFitMode.COVER,
            cornerRadius = (s.cornerRadius ?: defaults.cornerRadius).coerceIn(0f, 200f),
            borderWidth = (s.borderWidth ?: defaults.borderWidth).coerceIn(0f, 20f),
            borderColorArgb = s.borderColorArgb ?: defaults.borderColorArgb,
        )
    }

    /** Сериализация редактируемых настроек для хранения/передачи (Settings → export). */
    fun toJson(): JSONObject = JSONObject().apply {
        putNullIf(KEY_FONT_FAMILY, fontFamily)
        putNullIf(KEY_FONT_SIZE, fontSizeSp)
        putNullIf(KEY_BOLD, bold)
        putNullIf(KEY_ITALIC, italic)
        putNullIf(KEY_LETTER_SPACING, letterSpacing)
        putNullIf(KEY_LINE_HEIGHT, lineHeight)
        putNullIf(KEY_PARAGRAPH_SPACING, paragraphSpacing)
        put(KEY_TEXT_ALIGNMENT, textAlignment.name)
        putNullIf(KEY_TEXT_COLOR, textColorArgb)
        putNullIf(KEY_HIGHLIGHT_COLOR, highlightColorArgb)
        putNullIf(KEY_HIGHLIGHT_ALPHA, highlightAlpha)
        putNullIf(KEY_HIGHLIGHT_RADIUS, highlightRadius)
        putNullIf(KEY_HIGHLIGHT_PAD, highlightPadding)
        putNullIf(KEY_CARD_FILL, cardFillArgb)
        putNullIf(KEY_CARD_STROKE, cardStrokeArgb)
        putNullIf(KEY_CARD_ALPHA, currentCardAlpha)
        putNullIf(KEY_CONTEXT_OPACITY, contextParagraphOpacity)
        putNullIf(KEY_MARGIN_X, marginX)
        putNullIf(KEY_MAX_TEXT_WIDTH, maxTextWidth)
        putNullIf(KEY_OFFSET_Y, contentOffsetY)
        putNullIf(KEY_CARD_PAD_H, cardPaddingH)
        putNullIf(KEY_CARD_PAD_TOP, cardPaddingTop)
        putNullIf(KEY_CARD_PAD_BOTTOM, cardPaddingBottom)
        putNullIf(KEY_CARD_RADIUS, cardCornerRadius)
        putNullIf(KEY_CARD_STROKE_WIDTH, cardStrokeWidth)
        put(KEY_LEFT_ARTWORK, leftArtwork?.toJson() ?: JSONObject.NULL)
        put(KEY_RIGHT_ARTWORK, rightArtwork?.toJson() ?: JSONObject.NULL)
        put(KEY_PRESENTATION, presentation.name)
        put(KEY_SLIDESHOW_CONFIG, slideshowConfig?.toJson() ?: JSONObject.NULL)
        put(KEY_SLIDESHOW_ITEMS, slideshowItems.toJsonArray())
        put(KEY_CHAPTER_IDENTITY, chapterIdentity)
        put(KEY_SOURCE_ID, sourceId)
    }

    private fun JSONObject.putNullIf(key: String, value: Float?) {
        put(key, value?.toDouble() ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullIf(key: String, value: Int?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullIf(key: String, value: Boolean?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullIf(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_FONT_FAMILY = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSizeSp"
        private const val KEY_BOLD = "bold"
        private const val KEY_ITALIC = "italic"
        private const val KEY_LETTER_SPACING = "letterSpacing"
        private const val KEY_LINE_HEIGHT = "lineHeight"
        private const val KEY_PARAGRAPH_SPACING = "paragraphSpacing"
        private const val KEY_TEXT_ALIGNMENT = "textAlignment"
        private const val KEY_TEXT_COLOR = "textColorArgb"
        private const val KEY_HIGHLIGHT_COLOR = "highlightColorArgb"
        private const val KEY_HIGHLIGHT_ALPHA = "highlightAlpha"
        private const val KEY_HIGHLIGHT_RADIUS = "highlightRadius"
        private const val KEY_HIGHLIGHT_PAD = "highlightPadding"
        private const val KEY_CARD_FILL = "cardFillArgb"
        private const val KEY_CARD_STROKE = "cardStrokeArgb"
        private const val KEY_CARD_ALPHA = "currentCardAlpha"
        private const val KEY_CONTEXT_OPACITY = "contextParagraphOpacity"
        private const val KEY_MARGIN_X = "marginX"
        private const val KEY_MAX_TEXT_WIDTH = "maxTextWidth"
        private const val KEY_OFFSET_Y = "contentOffsetY"
        private const val KEY_CARD_PAD_H = "cardPaddingH"
        private const val KEY_CARD_PAD_TOP = "cardPaddingTop"
        private const val KEY_CARD_PAD_BOTTOM = "cardPaddingBottom"
        private const val KEY_CARD_RADIUS = "cardCornerRadius"
        private const val KEY_CARD_STROKE_WIDTH = "cardStrokeWidth"
        private const val KEY_LEFT_ARTWORK = "leftArtwork"
        private const val KEY_RIGHT_ARTWORK = "rightArtwork"
        private const val KEY_PRESENTATION = "presentation"
        private const val KEY_SLIDESHOW_CONFIG = "slideshowConfig"
        private const val KEY_SLIDESHOW_ITEMS = "slideshowItems"
        private const val KEY_CHAPTER_IDENTITY = "chapterIdentity"
        private const val KEY_SOURCE_ID = "sourceId"

        val DEFAULT = VideoStyleSettings()

        /** Десериализует настройки из JSON (созданного [toJson]); null при ошибке. */
        fun fromJson(json: String?): VideoStyleSettings? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                VideoStyleSettings(
                    fontFamily = obj.optStringOrNull(KEY_FONT_FAMILY),
                    fontSizeSp = obj.optFloatOrNull(KEY_FONT_SIZE),
                    bold = obj.optBooleanOrNull(KEY_BOLD),
                    italic = obj.optBooleanOrNull(KEY_ITALIC),
                    letterSpacing = obj.optFloatOrNull(KEY_LETTER_SPACING),
                    lineHeight = obj.optFloatOrNull(KEY_LINE_HEIGHT),
                    paragraphSpacing = obj.optFloatOrNull(KEY_PARAGRAPH_SPACING),
                    textAlignment = runCatching {
                        TextAlignment.valueOf(obj.getString(KEY_TEXT_ALIGNMENT))
                    }.getOrDefault(TextAlignment.START),
                    textColorArgb = obj.optIntOrNull(KEY_TEXT_COLOR),
                    highlightColorArgb = obj.optIntOrNull(KEY_HIGHLIGHT_COLOR),
                    highlightAlpha = obj.optFloatOrNull(KEY_HIGHLIGHT_ALPHA),
                    highlightRadius = obj.optFloatOrNull(KEY_HIGHLIGHT_RADIUS),
                    highlightPadding = obj.optFloatOrNull(KEY_HIGHLIGHT_PAD),
                    cardFillArgb = obj.optIntOrNull(KEY_CARD_FILL),
                    cardStrokeArgb = obj.optIntOrNull(KEY_CARD_STROKE),
                    currentCardAlpha = obj.optFloatOrNull(KEY_CARD_ALPHA),
                    contextParagraphOpacity = obj.optFloatOrNull(KEY_CONTEXT_OPACITY),
                    marginX = obj.optFloatOrNull(KEY_MARGIN_X),
                    maxTextWidth = obj.optFloatOrNull(KEY_MAX_TEXT_WIDTH),
                    contentOffsetY = obj.optFloatOrNull(KEY_OFFSET_Y),
                    cardPaddingH = obj.optFloatOrNull(KEY_CARD_PAD_H),
                    cardPaddingTop = obj.optFloatOrNull(KEY_CARD_PAD_TOP),
                    cardPaddingBottom = obj.optFloatOrNull(KEY_CARD_PAD_BOTTOM),
                    cardCornerRadius = obj.optFloatOrNull(KEY_CARD_RADIUS),
                    cardStrokeWidth = obj.optFloatOrNull(KEY_CARD_STROKE_WIDTH),
                    leftArtwork = VideoArtworkSettings.fromJson(obj.optJSONObject(KEY_LEFT_ARTWORK)),
                    rightArtwork = VideoArtworkSettings.fromJson(obj.optJSONObject(KEY_RIGHT_ARTWORK)),
                    presentation = runCatching {
                        ParagraphPresentation.valueOf(obj.getString(KEY_PRESENTATION))
                    }.getOrDefault(ParagraphPresentation.CURRENT_WITH_CONTEXT),
                    slideshowConfig = SlideshowConfig.fromJson(obj.optJSONObject(KEY_SLIDESHOW_CONFIG)),
                    slideshowItems = fromArtworkJsonArray(obj.optJSONArray(KEY_SLIDESHOW_ITEMS)),
                    chapterIdentity = obj.optString(KEY_CHAPTER_IDENTITY),
                    sourceId = obj.optString(KEY_SOURCE_ID),
                )
            }.getOrNull()
        }

        private fun JSONObject.optFloatOrNull(key: String): Float? {
            val v = opt(key)
            return if (v is Number) v.toFloat() else null
        }

        private fun JSONObject.optIntOrNull(key: String): Int? {
            val v = opt(key)
            return if (v is Number) v.toInt() else null
        }

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
            val v = opt(key)
            return if (v == JSONObject.NULL || v == null) null else v as? Boolean ?: optBoolean(key)
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            val v = opt(key)
            return if (v == JSONObject.NULL || v == null) null else v.toString()
        }
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