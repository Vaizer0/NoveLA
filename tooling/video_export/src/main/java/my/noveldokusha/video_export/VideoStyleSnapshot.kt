package my.noveldokusha.video_export

import androidx.annotation.ColorInt
import my.noveldokusha.reader_visuals.BackgroundType
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot
import org.json.JSONObject

/**
 * Замороженный слепок визуальных настроек видео на момент постановки экспорта
 * в очередь. Полностью конкретен (без null-«унаследовать»): воркер/рендер
 * ТОЛЬКО его потребляет и никогда не читает текущие настройки читалки.
 *
 * Сериализуется в JSON (org.json, как [ReaderVisualSnapshot]) и передаётся в
 * воркер через WorkManager input; структура версионируется полем [schemaVersion].
 *
 * Отдельный от [ReaderVisualSnapshot] слепок: видео — самостоятельная
 * визуальная система, а не «набор лишних полей читалки». Значения резолвятся
 * через [VideoStyleSettings.resolve] прямо в слепок; рендер не принимает
 * цветов/параметров «со стороны», обходящими [VideoStyleSnapshot].
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

/** Выравнивание строк абзаца внутри карточки. */
enum class TextAlignment {
    /** Слева (родной вид читалки) — дефолт. */
    START,
    CENTER,
    END,
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
    val paragraphSpacing: Float,
    val textAlignment: TextAlignment,
    // Colors (конкретные, без null)
    @ColorInt val textColorArgb: Int,
    @ColorInt val highlightColorArgb: Int,
    val highlightAlpha: Float,
    val highlightRadius: Float,
    val highlightPadding: Float,
    @ColorInt val cardFillArgb: Int,
    @ColorInt val cardStrokeArgb: Int,
    val currentCardAlpha: Float,
    val contextParagraphOpacity: Float,
    // Layout
    val marginX: Float,
    val maxTextWidth: Float?,
    val contentOffsetY: Float,
    // Card
    val cardPaddingH: Float,
    val cardPaddingTop: Float,
    val cardPaddingBottom: Float,
    val cardCornerRadius: Float,
    val cardStrokeWidth: Float,
    // Side artwork (лёгкие ссылки на изображения + настройки)
    val leftArtwork: VideoArtwork?,
    val rightArtwork: VideoArtwork?,
    // Presentation
    val presentation: ParagraphPresentation,
    // Slide-show (Phase G)
    /** Конфиг показов слайдов; отключён по умолчанию (как Phase F). */
    val slideshowConfig: SlideshowConfig,
    /** Упорядоченные активные слайды (идентичность для детерминированного seed). */
    val slideshowItems: List<ArtworkItem>,
    /** Идентичность главы (для детерминированного seed слайдшоу). */
    val chapterIdentity: String,
    /** Идентичность источника/главы-источника. */
    val sourceId: String,
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
        put(KEY_PARAGRAPH_SPACING, paragraphSpacing.toDouble())
        put(KEY_TEXT_ALIGNMENT, textAlignment.name)
        put(KEY_TEXT_COLOR, textColorArgb)
        put(KEY_HIGHLIGHT_COLOR, highlightColorArgb)
        put(KEY_HIGHLIGHT_ALPHA, highlightAlpha.toDouble())
        put(KEY_HIGHLIGHT_RADIUS, highlightRadius.toDouble())
        put(KEY_HIGHLIGHT_PAD, highlightPadding.toDouble())
        put(KEY_CARD_FILL, cardFillArgb)
        put(KEY_CARD_STROKE, cardStrokeArgb)
        put(KEY_CARD_ALPHA, currentCardAlpha.toDouble())
        put(KEY_CONTEXT_OPACITY, contextParagraphOpacity.toDouble())
        put(KEY_MARGIN_X, marginX.toDouble())
        put(KEY_MAX_TEXT_WIDTH, maxTextWidth?.toDouble() ?: JSONObject.NULL)
        put(KEY_OFFSET_Y, contentOffsetY.toDouble())
        put(KEY_CARD_PAD_H, cardPaddingH.toDouble())
        put(KEY_CARD_PAD_TOP, cardPaddingTop.toDouble())
        put(KEY_CARD_PAD_BOTTOM, cardPaddingBottom.toDouble())
        put(KEY_CARD_RADIUS, cardCornerRadius.toDouble())
        put(KEY_CARD_STROKE_WIDTH, cardStrokeWidth.toDouble())
        put(KEY_LEFT_ARTWORK, leftArtwork?.toJson() ?: JSONObject.NULL)
        put(KEY_RIGHT_ARTWORK, rightArtwork?.toJson() ?: JSONObject.NULL)
        put(KEY_PRESENTATION, presentation.name)
        put(KEY_SLIDESHOW_CONFIG, slideshowConfig.toJson())
        put(KEY_SLIDESHOW_ITEMS, slideshowItems.toJsonArray())
        put(KEY_CHAPTER_IDENTITY, chapterIdentity)
        put(KEY_SOURCE_ID, sourceId)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 2

        private const val KEY_SCHEMA = "schemaVersion"
        private const val KEY_FONT_FAMILY = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSizeSp"
        private const val KEY_FONT_SIZE_PX = "fontSizePx"
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

        val DEFAULT_BACKGROUND_ARGB = 0xFF15181D.toInt()
        val DEFAULT_CARD_FILL_ARGB = 0x332A59B6.toInt()
        val DEFAULT_CARD_STROKE_ARGB = 0x80FFFFFF.toInt()

        /** Стиль по умолчанию: полностью повторяет читалку и дефолтную геометрию. */
        fun defaultFor(reader: ReaderVisualSnapshot): VideoStyleSnapshot =
            VideoStyleSettings().resolve(reader)

        /**
         * Цвет текста по умолчанию для рендера: замороженный
         * [ReaderVisualSnapshot.textColorArgb], либо автоцвет по средней яркости
         * фонового слоя (пресет/дефолт). Совпадает с логикой VideoFrameRenderer.
         */
        fun resolveDefaultTextColor(
            snapshot: ReaderVisualSnapshot,
            defaultBackgroundArgb: Int = DEFAULT_BACKGROUND_ARGB,
        ): Int {
            snapshot.textColorArgb?.let { return it }
            val bgAvg = when (snapshot.backgroundType) {
                BackgroundType.PRESET -> ReaderVisualSnapshot.averageArgb(
                    snapshot.presetColorsArgb.ifEmpty { listOf(defaultBackgroundArgb) }
                )
                else -> defaultBackgroundArgb
            }
            return ReaderVisualSnapshot.autoTextColorForLuminance(bgAvg)
        }

        fun fromJson(json: String): VideoStyleSnapshot {
            val obj = JSONObject(json)
            fun optFloat(key: String, def: Float): Float =
                obj.optDouble(key, def.toDouble()).toFloat()
            val maxTextWidth = if (obj.isNull(KEY_MAX_TEXT_WIDTH)) null else optFloat(KEY_MAX_TEXT_WIDTH, 0f)
            val presentation = runCatching {
                ParagraphPresentation.valueOf(obj.getString(KEY_PRESENTATION))
            }.getOrDefault(ParagraphPresentation.DYNAMIC_CONTEXT)
            val textAlignment = runCatching {
                TextAlignment.valueOf(obj.getString(KEY_TEXT_ALIGNMENT))
            }.getOrDefault(TextAlignment.START)
            val leftArtwork = if (obj.isNull(KEY_LEFT_ARTWORK)) null else {
                VideoArtwork.fromJson(obj.getJSONObject(KEY_LEFT_ARTWORK))
            }
            val rightArtwork = if (obj.isNull(KEY_RIGHT_ARTWORK)) null else {
                VideoArtwork.fromJson(obj.getJSONObject(KEY_RIGHT_ARTWORK))
            }
            val slideshowConfig = SlideshowConfig.fromJson(
                obj.optJSONObject(KEY_SLIDESHOW_CONFIG),
            )
            val slideshowItems = fromArtworkJsonArray(
                obj.optJSONArray(KEY_SLIDESHOW_ITEMS),
            )
            return VideoStyleSnapshot(
                schemaVersion = obj.optInt(KEY_SCHEMA, SCHEMA_VERSION),
                fontFamily = obj.optString(KEY_FONT_FAMILY, "serif"),
                fontSizeSp = optFloat(KEY_FONT_SIZE, 14f),
                fontSizePx = optFloat(KEY_FONT_SIZE_PX, ReaderVisualSnapshot.computeBaseFontPx(14f)),
                bold = obj.optBoolean(KEY_BOLD, false),
                italic = obj.optBoolean(KEY_ITALIC, false),
                letterSpacing = optFloat(KEY_LETTER_SPACING, 0f),
                lineHeight = optFloat(KEY_LINE_HEIGHT, 1.35f),
                paragraphSpacing = optFloat(KEY_PARAGRAPH_SPACING, 0f),
                textAlignment = textAlignment,
                textColorArgb = obj.optInt(KEY_TEXT_COLOR, 0xFF000000.toInt()),
                highlightColorArgb = obj.optInt(KEY_HIGHLIGHT_COLOR, 0xFFFF6D00.toInt()),
                highlightAlpha = optFloat(KEY_HIGHLIGHT_ALPHA, 0.5019608f),
                highlightRadius = optFloat(KEY_HIGHLIGHT_RADIUS, 6f),
                highlightPadding = optFloat(KEY_HIGHLIGHT_PAD, 3f),
                cardFillArgb = obj.optInt(KEY_CARD_FILL, DEFAULT_CARD_FILL_ARGB),
                cardStrokeArgb = obj.optInt(KEY_CARD_STROKE, DEFAULT_CARD_STROKE_ARGB),
                currentCardAlpha = optFloat(KEY_CARD_ALPHA, 1f),
                contextParagraphOpacity = optFloat(KEY_CONTEXT_OPACITY, 0.45f),
                marginX = optFloat(KEY_MARGIN_X, 256f),
                maxTextWidth = maxTextWidth,
                contentOffsetY = optFloat(KEY_OFFSET_Y, 0f),
                cardPaddingH = optFloat(KEY_CARD_PAD_H, 56f),
                cardPaddingTop = optFloat(KEY_CARD_PAD_TOP, 40f),
                cardPaddingBottom = optFloat(KEY_CARD_PAD_BOTTOM, 48f),
                cardCornerRadius = optFloat(KEY_CARD_RADIUS, 20f),
                cardStrokeWidth = optFloat(KEY_CARD_STROKE_WIDTH, 2f),
                leftArtwork = leftArtwork,
                rightArtwork = rightArtwork,
                presentation = presentation,
                slideshowConfig = slideshowConfig,
                slideshowItems = slideshowItems,
                chapterIdentity = obj.optString(KEY_CHAPTER_IDENTITY, ""),
                sourceId = obj.optString(KEY_SOURCE_ID, ""),
            )
        }
    }
}