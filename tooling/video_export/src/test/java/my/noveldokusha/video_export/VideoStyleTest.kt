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
 * Фазы A–C: VideoStyleSettings → VideoStyleSnapshot (резолв + JSON),
 * VideoLayoutConfig (геометрия) и вынесение цветов/подсветки в слепок.
 * Дефолты обязаны повторять старые константы/логику VideoLayoutSpec один в
 * один, чтобы существующие кадры/QA не изменились.
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
        // Типографика из читалки.
        assertEquals("шрифт из читалки", "lora", s.fontFamily)
        assertEquals("fontSizeSp из читалки", 22f, s.fontSizeSp, 1e-4f)
        assertEquals(
            "fontSizePx == derivedBaseFontPx читалки",
            reader.derivedBaseFontPx, s.fontSizePx, 1e-3f,
        )
        assertEquals("lineHeight из читалки", 1.4f, s.lineHeight, 1e-4f)
        assertEquals("letterSpacing из читалки", 0.02f, s.letterSpacing, 1e-4f)
        assertEquals("highlight из читалки", 0xFFE53935.toInt(), s.highlightColorArgb)
        // Новые поля Phase C: консервативные дефолты.
        assertEquals("textAlignment", TextAlignment.START, s.textAlignment)
        assertEquals("paragraphSpacing", 0f, s.paragraphSpacing, 1e-4f)
        assertEquals(
            "textColorArgb == логике рендера",
            VideoFrameRenderer.resolveTextColor(reader), s.textColorArgb,
        )
        assertEquals("highlightRadius", 6f, s.highlightRadius, 1e-4f)
        assertEquals("highlightPadding", 3f, s.highlightPadding, 1e-4f)
        assertEquals("cardFill == блюпринт", VideoFrameRenderer.CardColors.blueprint().fillArgb, s.cardFillArgb)
        assertEquals("cardStroke == блюпринт", VideoFrameRenderer.CardColors.blueprint().strokeArgb, s.cardStrokeArgb)
        assertEquals("currentCardAlpha", 1f, s.currentCardAlpha, 1e-4f)
        assertEquals("contextParagraphOpacity", VideoLayoutSpec.PREVIEW_ALPHA, s.contextParagraphOpacity, 1e-4f)
        assertEquals("дефолтный режим", ParagraphPresentation.CURRENT_WITH_CONTEXT, s.presentation)
        assertNull("арт по умолчанию выключен (left)", s.leftArtwork)
        assertNull("арт по умолчанию выключен (right)", s.rightArtwork)

        val cfg = VideoLayoutConfig.from(s)
        assertEquals("marginX == старая константа", 256f, cfg.marginX, 1e-3f)
        assertEquals("cardTextWidth == CARD_TEXT_WIDTH", 1296.0, cfg.cardTextWidth().toDouble(), 1e-3)
        assertEquals("textX0 == MARGIN_X + CARD_PAD_H", 312.0, cfg.textX0().toDouble(), 1e-3)
        assertEquals("cardTop == CARD_TOP", 290f, cfg.cardTop(), 1e-3f)
        assertEquals("cardCapBottom == CARD_CAP_BOTTOM", 810f, cfg.cardCapBottom(), 1e-3f)
        assertEquals("paragraphSpacing=0 → prev как раньше", 270f, cfg.prevBottom(), 1e-3f)
        assertEquals("paragraphSpacing=0 → next как раньше", 830f, cfg.nextTop(), 1e-3f)
        assertEquals("previewAlpha == PREVIEW_ALPHA", VideoLayoutSpec.PREVIEW_ALPHA, cfg.previewAlpha, 1e-4f)
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
            paragraphSpacing = 24f,
            textAlignment = TextAlignment.CENTER,
            textColorArgb = 0xFFF0FF0F.toInt(),
            highlightColorArgb = 0xFF00FF00.toInt(),
            highlightAlpha = 0.25f,
            highlightRadius = 12f,
            highlightPadding = 7f,
            cardFillArgb = 0xFF112233.toInt(),
            cardStrokeArgb = 0xFFAABBCC.toInt(),
            currentCardAlpha = 0.6f,
            contextParagraphOpacity = 0.3f,
            marginX = 400f,
            maxTextWidth = 700f,
            contentOffsetY = 20f,
            presentation = ParagraphPresentation.DYNAMIC_CONTEXT,
        ).resolve(reader)

        assertEquals("sans-serif", s.fontFamily)
        assertEquals(30f, s.fontSizeSp, 1e-4f)
        assertTrue(s.bold)
        assertTrue(s.italic)
        assertEquals(1.6f, s.lineHeight, 1e-4f)
        assertEquals(0.05f, s.letterSpacing, 1e-4f)
        assertEquals(24f, s.paragraphSpacing, 1e-4f)
        assertEquals(TextAlignment.CENTER, s.textAlignment)
        assertEquals(0xFFF0FF0F.toInt(), s.textColorArgb)
        assertEquals(0xFF00FF00.toInt(), s.highlightColorArgb)
        assertEquals(0.25f, s.highlightAlpha, 1e-4f)
        assertEquals(12f, s.highlightRadius, 1e-4f)
        assertEquals(7f, s.highlightPadding, 1e-4f)
        assertEquals(0xFF112233.toInt(), s.cardFillArgb)
        assertEquals(0xFFAABBCC.toInt(), s.cardStrokeArgb)
        assertEquals(0.6f, s.currentCardAlpha, 1e-4f)
        assertEquals(0.3f, s.contextParagraphOpacity, 1e-4f)
        assertEquals(400f, s.marginX, 1e-3f)
        assertEquals(700f, s.maxTextWidth!!, 1e-3f)
        assertEquals(20f, s.contentOffsetY, 1e-3f)
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
        assertEquals("previewAlpha из контекст-opacity", 0.3f, cfg.previewAlpha, 1e-4f)
        assertEquals("prev приподнят на paragraphSpacing", 266f, cfg.prevBottom(), 1e-3f)
        assertEquals("next опущен на paragraphSpacing", 874f, cfg.nextTop(), 1e-3f)
        assertEquals("конвейер сдвинут вниз", 310f, cfg.cardTop(), 1e-3f)
    }

    @Test
    fun artworkResolveAndSafeGeometry() {
        // Левый арт 0.2 от ширины → колонка сдвигается вправо от арта.
        val left = VideoStyleSettings(
            leftArtwork = VideoArtworkSettings(
                fileName = "art_left.png",
                widthFraction = 0.2f,
                borderWidth = 3f,
                cornerRadius = 12f,
            ),
        ).resolve(reader)
        assertNotNull("leftArtwork присутствует", left.leftArtwork)
        assertEquals("art_left.png", left.leftArtwork!!.fileName)
        assertEquals(0.2f, left.leftArtwork!!.widthFraction, 1e-4f)
        assertEquals(3f, left.leftArtwork!!.borderWidth, 1e-4f)
        assertNull(left.rightArtwork)

        val cfgLeft = VideoLayoutConfig.from(left)
        val laRect = cfgLeft.leftArtworkRect()!!
        assertEquals("левый арт прижат к краю", 0f, laRect.left, 1e-3f)
        assertEquals("ширина арта 0.2*1920", 384f, cfgLeft.leftArtworkX(), 1e-3f)
        assertEquals("safeTextLeft = арт+zазор", 408f, cfgLeft.safeTextLeft(), 1e-3f)
        assertEquals("columnLeft упирается в safety", 408f, cfgLeft.columnLeft(), 1e-3f)
        assertEquals("правый край без арта", 1664f, cfgLeft.columnRight(), 1e-3f)
        assertTrue(
            "арт не пересекает карточку по X",
            laRect.right <= cfgLeft.cardRect().left + 1f,
        )

        // Левый + правый арт.
        val both = VideoStyleSettings(
            leftArtwork = VideoArtworkSettings("l.png", widthFraction = 0.15f),
            rightArtwork = VideoArtworkSettings("r.png", widthFraction = 0.15f),
        ).resolve(reader)
        val cfgBoth = VideoLayoutConfig.from(both)
        assertEquals("левая безопасная", 312f, cfgBoth.safeTextLeft(), 1e-3f)
        assertEquals("правая безопасная", 1920f - 288f - 24f, cfgBoth.safeTextRight(), 1e-3f)
        assertEquals("колонка сужена обоими артами", cfgBoth.safeTextRight() - cfgBoth.safeTextLeft(), cfgBoth.columnWidth(), 1e-3f)
        assertTrue("правая граница > левой", cfgBoth.columnRight() > cfgBoth.columnLeft())

        // Чрезмерная ширина обрезается (reduce artwork before shrinking text).
        val clamped = VideoStyleSettings(
            leftArtwork = VideoArtworkSettings("x.png", widthFraction = 0.9f),
        ).resolve(reader)
        assertEquals(
            "ширина арта ограничена 0.3",
            VideoArtwork.MAX_WIDTH_FRACTION, clamped.leftArtwork!!.widthFraction, 1e-4f,
        )

        // Пустое имя файла → арт выключен.
        val disabled = VideoStyleSettings(
            leftArtwork = VideoArtworkSettings(fileName = "  "),
        ).resolve(reader)
        assertNull("пустой файл = нет арта", disabled.leftArtwork)
    }

    @Test
    fun resolveUsesAppCardColorsAsFallback() {
        val appColors = VideoFrameRenderer.CardColors(fillArgb = 0x22FF0000.toInt(), strokeArgb = 0x99FFFFFF.toInt())
        val s = VideoStyleSettings().resolve(reader, appCardColors = appColors)
        assertEquals("заливка из темы приложения", appColors.fillArgb, s.cardFillArgb)
        assertEquals("обводка из темы приложения", appColors.strokeArgb, s.cardStrokeArgb)

        // Явная настройка пользователя побеждает тему приложения.
        val overridden = VideoStyleSettings(
            cardFillArgb = 0xFF010203.toInt(),
            cardStrokeArgb = 0xFF040506.toInt(),
        ).resolve(reader, appCardColors = appColors)
        assertEquals(0xFF010203.toInt(), overridden.cardFillArgb)
        assertEquals(0xFF040506.toInt(), overridden.cardStrokeArgb)
    }

    @Test
    fun jsonRoundTrip() {
        val s = VideoStyleSettings(
            fontFamily = "merriweather",
            fontSizeSp = 20f,
            bold = false,
            italic = true,
            lineHeight = 1.5f,
            paragraphSpacing = 12f,
            textAlignment = TextAlignment.END,
            textColorArgb = 0xFF010101.toInt(),
            highlightColorArgb = 0xFFABCDEF.toInt(),
            highlightAlpha = 0.4f,
            highlightRadius = 9f,
            highlightPadding = 5f,
            cardFillArgb = 0xFF321321.toInt(),
            cardStrokeArgb = 0xFF999999.toInt(),
            currentCardAlpha = 0.8f,
            contextParagraphOpacity = 0.35f,
            marginX = 300f,
            maxTextWidth = 800f,
            leftArtwork = VideoArtworkSettings("art_l.png", widthFraction = 0.18f, verticalAlignment = ArtworkVerticalAlignment.TOP),
            rightArtwork = VideoArtworkSettings("art_r.png", widthFraction = 0.1f, borderWidth = 4f),
            presentation = ParagraphPresentation.DYNAMIC_CONTEXT,
        ).resolve(reader)

        val json = s.toJson()
        val restored = VideoStyleSnapshot.fromJson(json)

        assertEquals(s, restored)
        assertEquals(ParagraphPresentation.DYNAMIC_CONTEXT, restored.presentation)
        assertEquals(TextAlignment.END, restored.textAlignment)
        assertEquals(800f, restored.maxTextWidth!!, 1e-3f)
        assertEquals(0xFF321321.toInt(), restored.cardFillArgb)
        assertEquals(0.35f, restored.contextParagraphOpacity, 1e-4f)
        assertEquals("арт слева проезжает JSON", "art_l.png", restored.leftArtwork!!.fileName)
        assertEquals(ArtworkVerticalAlignment.TOP, restored.leftArtwork!!.verticalAlignment)
        assertEquals(0.18f, restored.leftArtwork!!.widthFraction, 1e-4f)
        assertEquals("арт справа проезжает JSON", 4f, restored.rightArtwork!!.borderWidth, 1e-4f)
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
        assertFalse("цвет текста не обязан быть null", s.textColorArgb == 0)
    }
}