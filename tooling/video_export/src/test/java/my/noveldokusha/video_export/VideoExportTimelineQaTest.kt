package my.noveldokusha.video_export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-регрессия единиц таймлайн-координат (баг «байты вместо семплов» в
 * TtsTimelineCollector): все границы (title/paragraph) должны лежать в той же
 * шкале семплов, что totalSamples/wordTimings. Если кто-то вернёт байтовую шкалу
 * (≈channelCount*2 больше), paragraphAtSample тут же сломается — тест красный.
 */
class VideoExportTimelineQaTest {

    private val rate = 48_000

    private fun word(samplePosition: Long): WordTiming = WordTiming(
        displayRange = 0..0,
        samplePosition = samplePosition,
    )

    private fun paragraph(text: String, start: Long, end: Long, words: List<Long>): ParagraphTiming =
        ParagraphTiming(
            displayText = text,
            cleanedText = text,
            startSample = start,
            endSample = end,
            wordTimings = words.map(::word).sortedBy { it.samplePosition },
        )

    @Test
    fun `paragraph and title boundaries stay in sample coordinates`() {
        // 10s stereo audio (channelCount=2 → байты были бы в 4 раза больше).
        val totalSamples = 10L * rate
        val para0 = paragraph("first paragraph", start = 2L * rate, end = 4L * rate, words = listOf(2L * rate, 3L * rate))
        val para1 = paragraph("second paragraph", start = 4L * rate, end = 8L * rate, words = listOf(5L * rate, 7L * rate))
        val title = TitleTiming(
            displayText = "Chapter",
            startSample = 0L,
            endSample = 2L * rate,
            wordTimings = listOf(word(1L * rate)),
        )
        val timeline = VideoExportTimeline(
            sampleRate = rate,
            channelCount = 2,
            totalSamples = totalSamples,
            paragraphs = listOf(para0, para1),
            title = title,
        )

        // Инвариант монотонности в шкале семплов.
        assertTrue(title.startSample <= title.endSample)
        assertTrue(title.endSample <= para0.startSample)
        assertTrue(para0.startSample < para0.endSample)
        assertTrue(para0.endSample <= para1.startSample)
        assertTrue(para1.startSample < para1.endSample)
        assertTrue(para1.endSample <= totalSamples)

        // Пока идёт титул — активен первый абзац (интро заканчивается на title.endSample).
        assertSame(para0, timeline.paragraphAtSample(1L * rate))
        // После титула — дефолт первый абзац.
        assertSame(para0, timeline.paragraphAtSample(2L * rate))
        // Переход между абзацами должен происходить строго по границе в семплах.
        assertSame(para0, timeline.paragraphAtSample(4L * rate - 1))
        assertSame(para1, timeline.paragraphAtSample(4L * rate))
        assertSame(para1, timeline.paragraphAtSample(7L * rate))
        // После конца — дефолт последний абзац.
        assertSame(para1, timeline.paragraphAtSample(totalSamples))
    }

    @Test
    fun `wordAtSample honors paragraph-local sample boundaries`() {
        val para = paragraph("word one two", start = 0L, end = 6L * rate, words = listOf(1L * rate, 2L * rate, 4L * rate))
        assertEquals(1L * rate, para.wordTimings[0].samplePosition)
        assertEquals(4L * rate, para.wordTimings[2].samplePosition)

        val timeline = VideoExportTimeline(
            sampleRate = rate,
            channelCount = 2,
            totalSamples = 6L * rate,
            paragraphs = listOf(para),
        )
        val p = timeline.paragraphs[0]
        assertEquals(para.wordTimings[0], timeline.wordAtSample(1L * rate, p))
        assertEquals(para.wordTimings[2], timeline.wordAtSample(4L * rate, p))
    }
}
