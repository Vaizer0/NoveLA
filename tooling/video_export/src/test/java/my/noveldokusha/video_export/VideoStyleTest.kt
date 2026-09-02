package my.noveldokusha.video_export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import my.noveldokusha.reader_visuals.BackgroundType
import my.noveldokusha.reader_visuals.ReaderVisualSnapshot

/**
 * Фазы A/B: VideoStyleSettings → VideoStyleSnapshot (резолв + JSON) и
 * VideoLayoutConfig (геометрия). Дефолты обязаны повторять старые константы
 * VideoLayoutSpec один в один, чтобы существующие кадры/QA не изменились.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoStyleTest {

    private val reader = ReaderVisualSnapshot(
        fontFamily = "lora",
        fontSizeSp = 22f,
        lineHeight = 1.4f,
        letterSpacing = 0.02f,
        paragraphSpacing = 10f,
        textColorArgb = null,
        backgroundType = BackgroundType.PRESET,
        presetId = "paper",
        presetColorsArgb = listOf(0xFFF5EFE0.toInt(), 0xFFE8DFC8.toInt()),
        backgroundFileName = "",
        ttsHighlightColorArgb = 0xFFE53935.toInt(),
        derivedBaseFontPx = ReaderVisualSnapshot.computeBaseFontPx(22f),
    )

    @Test
    fun defaultsPreserveReaderAndLegacyGeometry() {
        val s = VideoStyleSnapshot.defaultFor(reader)
        assertEquals("шрифт из читалки", "lora", s.fontFamily)
        assertEquals("fontSizeSp из читалки", 22f, s.fontSizeSp, 1e-4f)
        assertEquals(
            "fontSizePx == derivedBaseFontPx читалки",
            reader.derivedBaseFontPx, s.fontSizePx, 1e-3f,
        )
        assertEquals("lineHeight из читалки", 1.4f, s.lineHeight, 1e-4f)
        assertEquals("letterSpacing из читалки", 0.02f, s.letterSpacing, 1e-4f)
        assertEquals("highlight из читалки", 0xFFE53935.toInt(), s.highlightColorArgb)
        assertEquals("дефолтный режим", ParagraphPresentation.CURRENT_WITH_CONTEXT, s.presentation)

        val cfg = VideoLayoutConfig.from(s)
        assertEquals("marginX == старая константа", 256f, cfg.marginX, 1e-3f)
        assertEquals("cardTextWidth == CARD_TEXT_WIDTH", 1296.0, cfg.cardTextWidth().toDouble(), 1e-3)
        assertEquals("textX0 == MARGIN_X + CARD_PAD_H", 312.0, cfg.textX0().toDouble(), 1e-3)
        assertEquals("cardTop == CARD_TOP", 290f, cfg.cardTop(), 1e-3f)
        assertEquals("cardCapBottom == CARD_CAP_BOTTOM", 810f, cfg.cardCapBottom(), 1e-3f)
        assertEquals(
            "prevSlotRect == старому прямоугольнику",
            VideoLayoutSpec.prevSlotRect(),
            cfg.prevSlotRect(),
        )
        assertEquals("cardRect == старому", VideoLayoutSpec.cardRect(), cfg.cardRect())
        assertEquals(
            "autofitScale == старой функции",
            VideoLayoutSpec.autofitScale(2000f),
            cfg.autofitScale(2000f),
            1e-4f,
        )
    }

    @Test
    fun resolveHonorsOverrides() {
        val s = VideoStyleSettings(
            fontFamily = "sans-serif",
            fontSizeSp = 30f,
            bold = true,
            italic = true,
            lineHeight = 1.6f,
            letterSpacing = 0.05f,
            marginX = 400f,
            maxTextWidth = 700f,
            contentOffsetY = 20f,
            highlightColorArgb = 0xFF00FF00.toInt(),
            highlightAlpha = 0.25f,
            presentation = ParagraphPresentation.DYNAMIC_CONTEXT,
        ).resolve(reader)

        assertEquals("sans-serif", s.fontFamily)
        assertEquals(30f, s.fontSizeSp, 1e-4f)
        assertTrue(s.bold)
        assertTrue(s.italic)
        assertEquals(1.6f, s.lineHeight, 1e-4f)
        assertEquals(0.05f, s.letterSpacing, 1e-4f)
        assertEquals(400f, s.marginX, 1e-3f)
        assertEquals(700f, s.maxTextWidth!!, 1e-3f)
        assertEquals(20f, s.contentOffsetY, 1e-3f)
        assertEquals(0xFF00FF00.toInt(), s.highlightColorArgb)
        assertEquals(0.25f, s.highlightAlpha, 1e-4f)
        assertEquals(ParagraphPresentation.DYNAMIC_CONTEXT, s.presentation)
        assertEquals(
            "fontSizePx пересчитан",
            ReaderVisualSnapshot.computeBaseFontPx(30f), s.fontSizePx, 1e-3f,
        )

        val cfg = VideoLayoutConfig.from(s)
        assertEquals("колонка сжата", 1120f, cfg.columnWidth(), 1e-3f)
        assertEquals(
            "текст центрирован при maxTextWidth",
            610f, cfg.textX0(), 1e-3f,
        )
        assertEquals(700f, cfg.cardTextWidth(), 1e-3f)
        assertEquals("конвейер сдвинут вниз", 310f, cfg.cardTop(), 1e-3f)
    }

    @Test
    fun jsonRoundTrip() {
        val s = VideoStyleSettings(
            fontFamily = "merriweather",
            fontSizeSp = 20f,
            bold = false,
            italic = true,
            lineHeight = 1.5f,
            marginX = 300f,
            maxTextWidth = 800f,
            highlightAlpha = 0.4f,
            letterSpacing = 0.0f,
            presentation = ParagraphPresentation.DYNAMIC_CONTEXT,
        ).resolve(reader)

        val json = s.toJson()
        val restored = VideoStyleSnapshot.fromJson(json)

        assertEquals(s, restored)
        assertEquals(ParagraphPresentation.DYNAMIC_CONTEXT, restored.presentation)
        assertEquals(800f, restored.maxTextWidth!!, 1e-3f)
    }

    @Test
    fun jsonHandlesNullMaxTextWidth() {
        val s = VideoStyleSnapshot.defaultFor(reader)
        val restored = VideoStyleSnapshot.fromJson(s.toJson())
        assertNull("maxTextWidth null переносится", restored.maxTextWidth)
        assertEquals(s, restored)
    }

    @Test
    fun defaultConfigKeepsTitleIntroWidth() {
        val cfg = VideoLayoutConfig.from(VideoStyleSnapshot.defaultFor(reader))
        assertEquals(
            "титул вступления использует ту же текстовую ширину",
            VideoLayoutSpec.CARD_TEXT_WIDTH.toFloat(), cfg.cardTextWidth(), 1e-3f,
        )
    }

    @Test
    fun geometryClippingSafe() {
        // Слишком широкие поля не делают ширину текста отрицательной.
        val s = VideoStyleSettings(
            marginX = 990f,
            presentation = ParagraphPresentation.DYNAMIC_CONTEXT,
        ).resolve(reader)
        val cfg = VideoLayoutConfig.from(s)
        assertTrue(cfg.cardTextWidth() >= 0f)
        assertTrue(cfg.textX0() <= cfg.columnRight())
    }
}