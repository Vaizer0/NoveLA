package my.noveldokusha.video_export

import my.noveldokusha.reader_visuals.ReaderVisualSnapshot
import org.json.JSONObject

/**
 * Замороженный слепок визуальных настроек видео на момент постановки экспорта
 * в очередь. Полностью конкретен (без null-«унаследовать»): воркер/рендер
 * ТОЛЬКО его потребляет и никогда не читает текущие настройки читалки.
 *
 * Сериализуется в JSON (org.json, как [ReaderVisualSnapshot]) и передаётся в
 * [TtsVideoExportRequest]; структура версионируется полем [schemaVersion].
 *
 * Отдельный от [ReaderVisualSnapshot] слепок: видео — самостоятельная
 * визуальная система, а не «набор лишних полей читалки». Значения типографики
 * и отступов резолвятся через [VideoStyleSettings.resolve], а цвет текста и
 * карточки приходят из работника (тема приложения/автоцвет по фону).
 */
enum class ParagraphPresentation {
    /** Только текущий абзац, без контекста. */
    CURRENT_ONLY,

    /** Классический конвейер из трёх слотов (prev/current/next) — дефолт. */
    CURRENT_WITH_CONTEXT,

    /**
     * Текущий абзац — фокус. Контекст показывается, только пока текущий
     * текст помещается в карточку целиком; иначе контекст убирается, а не
     * сжимается сам текущий текст. (Читабельность current важнее контекста.)
     */
    DYNAMIC_CONTEXT,
}

data class VideoStyleSnapshot(
    val schemaVersion: Int,
    // Typography
    val fontFamily: String,
    val fontSizeSp: Float,
    /** Производный базовый размер шрифта (px канваса 1920×1080). */
    val fontSizePx: Float,
    val bold: Boolean,
    val italic: Boolean,
    val letterSpacing: Float,
    val lineHeight: Float,
    // Layout
    val marginX: Float,
    val maxTextWidth: Float?,
    val contentOffsetY: Float,
    // Highlight
    val highlightColorArgb: Int,
    val highlightAlpha: Float,
    // Card
    val cardPaddingH: Float,
    val cardPaddingTop: Float,
    val cardPaddingBottom: Float,
    val cardCornerRadius: Float,
    val cardStrokeWidth: Float,
    // Presentation
    val presentation: ParagraphPresentation,
) {

    fun toJson(): String = JSONObject().apply {
        put(KEY_SCHEMA, schemaVersion)
        put(KEY_FONT_FAMILY, fontFamily)
        put(KEY_FONT_SIZE, fontSizeSp.toDouble())
        put(KEY_FONT_SIZE_PX, fontSizePx.toDouble())
        put(KEY_BOLD, bold)
        put(KEY_ITALIC, italic)
        put(KEY_LETTER_SPACING, letterSpacing.toDouble())
        put(KEY_LINE_HEIGHT, lineHeight.toDouble())
        put(KEY_MARGIN_X, marginX.toDouble())
        put(KEY_MAX_TEXT_WIDTH, maxTextWidth?.toDouble() ?: JSONObject.NULL)
        put(KEY_OFFSET_Y, contentOffsetY.toDouble())
        put(KEY_HIGHLIGHT_COLOR, highlightColorArgb)
        put(KEY_HIGHLIGHT_ALPHA, highlightAlpha.toDouble())
        put(KEY_CARD_PAD_H, cardPaddingH.toDouble())
        put(KEY_CARD_PAD_TOP, cardPaddingTop.toDouble())
        put(KEY_CARD_PAD_BOTTOM, cardPaddingBottom.toDouble())
        put(KEY_CARD_RADIUS, cardCornerRadius.toDouble())
        put(KEY_CARD_STROKE, cardStrokeWidth.toDouble())
        put(KEY_PRESENTATION, presentation.name)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        private const val KEY_SCHEMA = "schemaVersion"
        private const val KEY_FONT_FAMILY = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSizeSp"
        private const val KEY_FONT_SIZE_PX = "fontSizePx"
        private const val KEY_BOLD = "bold"
        private const val KEY_ITALIC = "italic"
        private const val KEY_LETTER_SPACING = "letterSpacing"
        private const val KEY_LINE_HEIGHT = "lineHeight"
        private const val KEY_MARGIN_X = "marginX"
        private const val KEY_MAX_TEXT_WIDTH = "maxTextWidth"
        private const val KEY_OFFSET_Y = "contentOffsetY"
        private const val KEY_HIGHLIGHT_COLOR = "highlightColorArgb"
        private const val KEY_HIGHLIGHT_ALPHA = "highlightAlpha"
        private const val KEY_CARD_PAD_H = "cardPaddingH"
        private const val KEY_CARD_PAD_TOP = "cardPaddingTop"
        private const val KEY_CARD_PAD_BOTTOM = "cardPaddingBottom"
        private const val KEY_CARD_RADIUS = "cardCornerRadius"
        private const val KEY_CARD_STROKE = "cardStrokeWidth"
        private const val KEY_PRESENTATION = "presentation"

        /** Стиль по умолчанию: полностью повторяет читалку и дефолтную геометрию. */
        fun defaultFor(reader: ReaderVisualSnapshot): VideoStyleSnapshot =
            VideoStyleSettings().resolve(reader)

        fun fromJson(json: String): VideoStyleSnapshot {
            val obj = JSONObject(json)
            fun optFloat(key: String, def: Float): Float =
                obj.optDouble(key, def.toDouble()).toFloat()
            val maxTextWidth = if (obj.isNull(KEY_MAX_TEXT_WIDTH)) null else optFloat(KEY_MAX_TEXT_WIDTH, 0f)
            val presentation = runCatching {
                ParagraphPresentation.valueOf(obj.getString(KEY_PRESENTATION))
            }.getOrDefault(ParagraphPresentation.DYNAMIC_CONTEXT)
            return VideoStyleSnapshot(
                schemaVersion = obj.optInt(KEY_SCHEMA, SCHEMA_VERSION),
                fontFamily = obj.optString(KEY_FONT_FAMILY, "serif"),
                fontSizeSp = optFloat(KEY_FONT_SIZE, 14f),
                fontSizePx = optFloat(KEY_FONT_SIZE_PX, ReaderVisualSnapshot.computeBaseFontPx(14f)),
                bold = obj.optBoolean(KEY_BOLD, false),
                italic = obj.optBoolean(KEY_ITALIC, false),
                letterSpacing = optFloat(KEY_LETTER_SPACING, 0f),
                lineHeight = optFloat(KEY_LINE_HEIGHT, 1.35f),
                marginX = optFloat(KEY_MARGIN_X, 256f),
                maxTextWidth = maxTextWidth,
                contentOffsetY = optFloat(KEY_OFFSET_Y, 0f),
                highlightColorArgb = obj.optInt(KEY_HIGHLIGHT_COLOR, 0xFFFF6D00.toInt()),
                highlightAlpha = optFloat(KEY_HIGHLIGHT_ALPHA, 0.5019608f),
                cardPaddingH = optFloat(KEY_CARD_PAD_H, 56f),
                cardPaddingTop = optFloat(KEY_CARD_PAD_TOP, 40f),
                cardPaddingBottom = optFloat(KEY_CARD_PAD_BOTTOM, 48f),
                cardCornerRadius = optFloat(KEY_CARD_RADIUS, 20f),
                cardStrokeWidth = optFloat(KEY_CARD_STROKE, 2f),
                presentation = presentation,
            )
        }
    }
}