package my.noveldokusha.video_export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Robolectric QA-рендер видео (раздел 13 плана): строит кадры всех сценариев
 * в реальный ARGB Bitmap через android.graphics (настоящий Canvas/StaticLayout/
 * Typeface), сохраняет PNG в $QA_FRAMES_DIR и проверяет инварианты макета.
 *
 * Ассерты:
 *  - слоты не перекрывают карточку; содержимое в колонке x∈[256,1664]
 *  - autofit ≥ 0.72
 *  - word range внутри границ абзаца; absoluteSample монотонен
 *  - RectF корректны (left<right, top<bottom); подсветка выровнена по глифам
 *  - кадр непустой (текст реально отрисован)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoFrameRendererQaTest {

    private val sampleRate = 48_000

    private fun snapshot(
        presetId: String = "paper",
        fontSizeSp: Float = 18f,
        lineHeight: Float = 1.35f,
    ): my.noveldokusha.reader_visuals.ReaderVisualSnapshot {
        val preset = my.noveldokusha.reader_visuals.ReaderBackgroundPresets.first { it.id == presetId }
        return my.noveldokusha.reader_visuals.ReaderVisualSnapshot(
            fontFamily = "serif",
            fontSizeSp = fontSizeSp,
            lineHeight = lineHeight,
            letterSpacing = 0f,
            paragraphSpacing = 8f,
            textColorArgb = null,
            backgroundType = my.noveldokusha.reader_visuals.BackgroundType.PRESET,
            presetId = preset.id,
            presetColorsArgb = preset.colors,
            backgroundFileName = "",
            ttsHighlightColorArgb = 0xFFFF6D00.toInt(),
            derivedBaseFontPx = my.noveldokusha.reader_visuals.ReaderVisualSnapshot
                .computeBaseFontPx(fontSizeSp),
        )
    }

    private fun wordRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            ranges.add(start until i)
        }
        return ranges
    }

    /** Строит таймлайн с равномерным распределением слов по абзацам. */
    private fun buildTimeline(paragraphs: List<String>): VideoExportTimeline {
        var cursor = 100_000L
        val wordDurationUs = 220_000L
        val gapUs = 90_000L
        val timing = paragraphs.map { text ->
            val places = wordRanges(text)
            var s = cursor
            val words = places.mapIndexed { k, r ->
                WordTiming(
                    displayRange = r,
                    samplePosition = s + k * wordDurationUs,
                    isApproximate = false,
                )
            }
            val start = s
            val end = s + (places.size * wordDurationUs)
            cursor = end + gapUs
            ParagraphTiming(
                displayText = text,
                cleanedText = text,
                startSample = start,
                endSample = end,
                wordTimings = words,
            )
        }
        return VideoExportTimeline(
            sampleRate = sampleRate,
            channelCount = 1,
            totalSamples = cursor * sampleRate / 1_000_000L,
            paragraphs = timing,
        )
    }

    private fun renderer(
        paragraphs: List<String>,
        presetId: String = "paper",
        typeface: Typeface = Typeface.create("serif", Typeface.NORMAL),
    ): Pair<VideoFrameRenderer, VideoExportTimeline> {
        val snap = snapshot(presetId = presetId)
        val timeline = buildTimeline(paragraphs)
        val textColor = VideoFrameRenderer.resolveTextColor(snap)
        val renderer = VideoFrameRenderer(
            snapshot = snap,
            timeline = timeline,
            typeface = typeface,
            resolvedTextColorArgb = textColor,
            cardFillArgb = VideoFrameRenderer.CardColors.blueprint().fillArgb,
            cardStrokeArgb = VideoFrameRenderer.CardColors.blueprint().strokeArgb,
            novelTitle = "The Cartographer's Apprentice",
            chapterTitle = "Chapter 12 — The Mud Crossroads",
        )
        return renderer to timeline
    }

    private fun renderFrameToBitmap(
        renderer: VideoFrameRenderer,
        sample: Long,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            VideoLayoutSpec.WIDTH, VideoLayoutSpec.HEIGHT, Bitmap.Config.ARGB_8888,
        )
        renderer.renderFrame(Canvas(bitmap), sample)
        return bitmap
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val dir = System.getenv("QA_FRAMES_DIR")?.let { File(it) }
            ?: File(RuntimeEnvironment.getApplication().filesDir, "tts_video_qa")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun midSample(p: ParagraphTiming): Long {
        val available = (p.endSample - p.startSample - 150_000L).coerceAtLeast(1L)
        return p.startSample + minOf(1_000_000L, available / 2)
    }

    // ── Инварианты макета ──────────────────────────────────────────────────

    private fun assertGeometry(plan: VideoFrameRenderer.FramePlan) {
        assertNotNull("current всегда присутствует", plan.current)
        val slots = listOfNotNull(plan.prev, plan.current, plan.next, plan.fadingOut)
        for (s in slots) {
            assertTrue("left < right", s.rect.left <= s.rect.right)
            assertTrue("top < bottom", s.rect.top <= s.rect.bottom)
            assertTrue("clip left < right", s.clip.left <= s.clip.right)
            assertTrue("clip top < bottom", s.clip.top <= s.clip.bottom)
            assertTrue("alpha in 0..1", s.alpha in 0f..1f)
            assertTrue("scale in 0.5..1.1", s.scale in 0.5f..1.1f)
            // содержимое не выходит за колонку x ∈ [256,1664]
            val left = VideoLayoutSpec.slotContentLeftPx(s.scale)
            val right = VideoLayoutSpec.slotContentRightPx(s.scale)
            assertTrue("glyphs inside column (left=$left)", left >= VideoLayoutSpec.MARGIN_X)
            assertTrue("glyphs inside column (right=$right)", right <= VideoLayoutSpec.MARGIN_X + VideoLayoutSpec.COLUMN_WIDTH)
        }
        // prev/next боксы не перекрывают карточку
        val card = VideoLayoutSpec.cardRect()
        val prev = VideoLayoutSpec.prevSlotRect()
        val next = VideoLayoutSpec.nextSlotRect()
        assertFalse("prev не пересекает карточку", prev.intersect(card))
        assertFalse("next не пересекает карточку", next.intersect(card))
        assertTrue("prev над карточкой", prev.bottom <= card.top)
        assertTrue("next под карточкой", next.top >= card.bottom)
    }

    private fun assertWordTimings(timeline: VideoExportTimeline, sample: Long) {
        var lastSample = -1L
        for (p in timeline.paragraphs) {
            assertNotNull("displayText непустой", p.displayText)
            var prevPos = -1L
            for (w in p.wordTimings) {
                assertTrue("absoluteSample монотонен", w.samplePosition >= prevPos)
                prevPos = w.samplePosition
                assertTrue("range внутри абзаца", w.displayRange.first >= 0)
                assertTrue(
                    "range.end ≤ text.length",
                    w.displayRange.last < p.displayText.length,
                )
                assertTrue(
                    "range непустой (first<=last)",
                    w.displayRange.first <= w.displayRange.last,
                )
            }
            lastSample = maxOf(lastSample, prevPos)
        }
        assertTrue("главный абзац определён", timeline.paragraphAtSample(sample) != null)
    }

    private fun assertAutofitFloor(paragraphs: List<String>) {
        val (renderer, timeline) = renderer(paragraphs)
        val plan = renderer.framePlan(midSample(timeline.paragraphs.first()))
        val scale = plan.current!!.scale
        assertTrue("autofit >= ${VideoLayoutSpec.FONT_MIN_AUTOFIT}", scale >= VideoLayoutSpec.FONT_MIN_AUTOFIT)
        val content = timeline.paragraphs.first().displayText
        val cache = ParagraphLayoutCache(
            snapshot = snapshot(),
            typeface = Typeface.create("serif", Typeface.NORMAL),
            textColorArgb = 0xFF000000.toInt(),
        )
        val entry = cache.layoutFor(0, content)
        assertTrue(
            "autofitScale согласован с высотой",
            entry.autofitScale == VideoLayoutSpec.autofitScale(entry.layout.height.toFloat()),
        )
    }

    /** Подсветка выровнена по глифам того layout, которым рисуется текст. */
    private fun assertHighlightAlignment(
        renderer: VideoFrameRenderer,
        timeline: VideoExportTimeline,
        sample: Long,
    ) {
        val p = timeline.paragraphAtSample(sample)!!
        val word = timeline.wordAtSample(sample, p)!!
        val layout = buildLayoutForTest(p.displayText)
        val rects = my.noveldokusha.reader_visuals.HighlightSpan.wordRects(
            layout, word.displayRange.first, word.displayRange.last + 1, 3f,
        )
        if (rects.isEmpty()) {
            System.err.println(
                "QA-DIAG text='${p.displayText}' len=${p.displayText.length} " +
                    "word=${word.displayRange} sample=$sample " +
                    "lineCount=${layout.lineCount} layoutWidth=${layout.width}"
            )
            for (line in 0 until layout.lineCount) {
                System.err.println(
                    "QA-DIAG serif line=$line start=${layout.getLineStart(line)} " +
                        "end=${layout.getLineEnd(line)} left=${layout.getLineLeft(line)}"
                )
            }
            System.err.println("QA-DIAG serif primaryHorizontal[]=" + (0..p.displayText.length).joinToString(",") { layout.getPrimaryHorizontal(it).toString() })
            val serifPaint = TextPaint().apply {
                isAntiAlias = true
                textSize = layout.paint.textSize
                typeface = Typeface.create("serif", Typeface.NORMAL)
            }
            System.err.println("QA-DIAG serif measureText='cart'=" + serifPaint.measureText("cart") + " 'The '=" + serifPaint.measureText("The "))
            val sansLayout = buildLayoutForTest(p.displayText, typeface = Typeface.create("sans-serif", Typeface.NORMAL))
            System.err.println("QA-DIAG sans lineCount=${sansLayout.lineCount} lineLeft0=${sansLayout.getLineLeft(0)}")
            System.err.println("QA-DIAG sans primaryHorizontal[]=" + (0..p.displayText.length).joinToString(",") { sansLayout.getPrimaryHorizontal(it).toString() })
            val sansPaint = TextPaint().apply {
                isAntiAlias = true
                textSize = layout.paint.textSize
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            System.err.println("QA-DIAG sans measureText='cart'=" + sansPaint.measureText("cart") + " 'The '=" + sansPaint.measureText("The "))
            System.err.println(
                "QA-DIAG serifRects=" +
                    my.noveldokusha.reader_visuals.HighlightSpan.wordRects(
                        layout, word.displayRange.first, word.displayRange.last + 1, 3f,
                    )
            )
            System.err.println(
                "QA-DIAG sansRects=" +
                    my.noveldokusha.reader_visuals.HighlightSpan.wordRects(
                        sansLayout, word.displayRange.first, word.displayRange.last + 1, 3f,
                    )
            )
        }
        val highlightRects = my.noveldokusha.reader_visuals.HighlightSpan.wordRects(
            layout, word.displayRange.first, word.displayRange.last + 1, 3f,
        )
        assertTrue("подсветка даёт rect'ы", highlightRects.isNotEmpty())
        for (r in rects) {
            assertTrue("rect left<right", r.left < r.right)
            assertTrue("rect top<bottom", r.top < r.bottom)
            assertTrue("rect внутри ширины текста", r.right <= layout.width + 1f)
        }
        // Если слово в одной строке — прямоугольник покрывает ровно его глифы.
        val line = layout.getLineForOffset(word.displayRange.first)
        if (layout.getLineForOffset(word.displayRange.last) == line) {
            val expectedLeft =
                layout.getLineLeft(line) + layout.getPrimaryHorizontal(word.displayRange.first)
            val expectedRight =
                layout.getLineLeft(line) + layout.getPrimaryHorizontal(word.displayRange.last + 1)
            val candidate = rects.first { kotlin.math.abs(it.left - expectedLeft) < 2f }
            assertEquals(
                "right совпадает с глифами",
                expectedRight,
                candidate.right,
                2f,
            )
        }
    }

    private fun buildLayoutForTest(text: String, typeface: Typeface = Typeface.create("serif", Typeface.NORMAL)): StaticLayout {
        val paint = TextPaint().apply {
            textSize = snapshot().derivedBaseFontPx
            color = 0xFF000000.toInt()
            this.typeface = typeface
        }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, VideoLayoutSpec.CARD_TEXT_WIDTH)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.35f)
            .build()
    }

    private fun assertFrameNotEmpty(bitmap: Bitmap) {
        val samples = mutableSetOf<Int>()
        var y = 300
        while (y < 800) {
            var x = 300
            while (x < 1620) {
                samples.add(bitmap.getPixel(x, y))
                x += 40
            }
            y += 40
        }
        assertTrue("кадр содержит минимум 3 разных цвета (текст/карточка/фон)", samples.size >= 3)
        // карточка реально нарисована: центр карточки ≠ углу фона
        val corner = bitmap.getPixel(10, 10)
        val center = bitmap.getPixel(960, 540)
        assertTrue("карточка отличается от фона", corner != center)
    }

    // ── Сценарии ───────────────────────────────────────────────────────────

    private val veryShort = "The cart moved."
    private val normal = "The cart rattled down the narrow road, past dim windows and " +
        "shuttered houses, while the boy gripped the reins with numb, trembling fingers. " +
        "Beyond the last lantern a frost settled over the ditches and the puddles turned to glass."
    private val longPara = (1..6).joinToString(" ") { block ->
        "Mist curled between the crooked fence posts and swallowed the distant bell " +
            "tower, whose iron voice had guided travelers for as long as anyone could remember. " +
            "Somewhere ahead the road forked into mud, and beyond that fork no lantern burned."
    }
    private val veryLongLine = "Supercalifragilisticexpialidocious " +
        "Antidisestablishmentarianism " +
        "Floccinaucinihilipilification " +
        "Pneumonoultramicroscopicsilicovolcanoconiosis " +
        "pneumonoultramicroscopicsilicovolcanoconiosis"
    private val dialogue = "“Wait — the watch! Is it still ticking?” she asked, and the old " +
        "man drew back. “It cannot be,” he breathed, “for the spring was wound the night " +
        "the bridge fell. Unless…” — he stopped mid-sentence."
    private val regexModified = "*** A cry broke the silence. ***\n" +
        "--- Not here, not yet, --- whispered the wind.\n" +
        "*** They listened; the cry was a gull. ***"
    private val translated = "[tl]The lantern guttered in the draft.[/tl] " +
        "[tr]Фонарь замигал на сквозняке.[/tr] The flame danced, and the boy saw " +
        "his own pale face reflected in the glass."
    private val decoratedCleaned = "He pulled the bundle closer, heedless of the damp fur. " +
        "The letter said little — but the seal, {{-a black drip of wax bearing five stars-}}, " +
        "was enough. {{He turned the page, and read it a second time.}}"
    private val wordLineBreak = "Beyond the hill the wind carried a name, " +
        "Heteroskedasticity-contravariance-preprocessor, that had no business on any " +
        "weather report, and the boy repeated it to himself until the road bent."

    private val actualChapter = listOf(
        "The cart pulled up at the crossroads just as the last light bled out of the sky, and the boy " +
            "saw the ruined mill glowing faintly against the dark.",
        "— We'll go no further tonight, the driver said, climbing down into the cold puddles. " +
            "He held his lantern so the flame would not gutter, and for a moment the wet stones shone like scales.",
        "The boy counted five windows in the mill, and every one of them was dark; yet a thread of " +
            "smoke rose from a chimney that had no business standing at all.",
        "Inside, the floor sang underfoot, and somewhere above them a door opened and closed " +
            "at the exact instant the clock stopped.",
    )

    private val transitionChapter = listOf(
        normal,
        longPara,
        dialogue,
        actualChapter.last(),
    )

    @Test
    fun generateAndAssertAllQaFrames() {
        scenario("01_very_short", listOf(veryShort))
        scenario("02_normal", listOf(normal))
        scenario("03_long", listOf(longPara))
        scenario("04_very_long_line", listOf(veryLongLine))
        scenario("05_dialogue", listOf(dialogue))
        scenario("06_regex_modified", regexModified.lines())
        scenario("07_translated", listOf(translated))
        scenario("08_decorated_cleaned", listOf(decoratedCleaned))
        scenario("09_word_line_break", listOf(wordLineBreak))

        val (tr, trTimeline) = renderer(transitionChapter)
        for ((label, frac) in listOf("000" to 0f, "025" to 0.25f, "050" to 0.5f, "075" to 0.75f, "100" to 1f)) {
            val sample = trTimeline.paragraphs[1].startSample + (VideoLayoutSpec.TRANSITION_US * frac).toLong()
            val bitmap = renderFrameToBitmap(tr, sample)
            assertGeometry(tr.framePlan(sample))
            assertFrameNotEmpty(bitmap)
            savePng(bitmap, "10_transition_$label.png")
        }

        val (ch, chTimeline) = renderer(actualChapter)
        val chSample = midSample(chTimeline.paragraphs[2])
        val chBitmap = renderFrameToBitmap(ch, chSample)
        assertGeometry(ch.framePlan(chSample))
        assertWordTimings(chTimeline, chSample)
        assertHighlightAlignment(ch, chTimeline, chSample)
        assertFrameNotEmpty(chBitmap)
        savePng(chBitmap, "11_actual_chapter.png")

        val dark = renderer(listOf(normal), presetId = "twilight")
        val darkBitmap = renderFrameToBitmap(dark.first, midSample(dark.second.paragraphs.first()))
        assertFrameNotEmpty(darkBitmap)
        savePng(darkBitmap, "12_dark_preset.png")

        val extraLong = (1..40).joinToString(" ") {
            "The vaulted corridors stretched beyond the lamplight, their stone faces worn " +
                "smooth by the feet of a thousand pilgrims."
        }
        val (af, afTimeline) = renderer(listOf(extraLong))
        val afBitmap = renderFrameToBitmap(af, midSample(afTimeline.paragraphs.first()))
        assertGeometry(af.framePlan(midSample(afTimeline.paragraphs.first())))
        assertAutofitFloor(listOf(extraLong))
        assertFrameNotEmpty(afBitmap)
        savePng(afBitmap, "13_autofit_long.png")

        val (nullC, nullT) = renderer(listOf(actualChapter[0]))
        val nullPlan = nullC.framePlan(nullT.paragraphs[0].startSample + 100L)
        assertGeometry(nullPlan)
    }

    @Test
    fun monotonicTimingInvariant() {
        val (_, timeline) = renderer(actualChapter)
        assertWordTimings(timeline, midSample(timeline.paragraphs[2]))
    }

    private fun scenario(prefix: String, paragraphs: List<String>) {
        val (renderer, timeline) = renderer(paragraphs)
        val sample = midSample(timeline.paragraphs.first())
        val plan = renderer.framePlan(sample)
        assertGeometry(plan)
        assertWordTimings(timeline, sample)
        assertHighlightAlignment(renderer, timeline, sample)
        val bitmap = renderFrameToBitmap(renderer, sample)
        assertFrameNotEmpty(bitmap)
        savePng(bitmap, "$prefix.png")
    }
}