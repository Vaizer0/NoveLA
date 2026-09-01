package my.noveldokusha.video_export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimingMapperTest {

    @Test
    fun `null when map is empty`() {
        assertNull(TimingMapper.displayRange(IntArray(0), 0, 0, 2))
    }

    @Test
    fun `identity map keeps display range`() {
        val map = intArrayOf(0, 1, 2, 3, 4)
        // "hello"
        assertEquals(0 until 3, TimingMapper.displayRange(map, 0, 0, 2))
    }

    @Test
    fun `offset accumulates chunk start`() {
        // display = "XXXXABCzz" -> cleaned = "ABC" at cleaned indices 0..2
        val map = intArrayOf(4, 5, 6)
        assertEquals(4 until 7, TimingMapper.displayRange(map, 0, 0, 2))
        // chunk offset 1 within cleaned -> maps to display 5..7
        assertEquals(5 until 7, TimingMapper.displayRange(map, 1, 0, 1))
    }

    @Test
    fun `clamps out of range into map bounds`() {
        val map = intArrayOf(10, 11, 12)
        // start за пределами -> клэмпится к последнему
        assertEquals(12 until 13, TimingMapper.displayRange(map, 0, 99, 99))
    }

    @Test
    fun `absolute sample uses non negative frame`() {
        assertEquals(1000L, TimingMapper.absoluteSample(1000L, 0))
        assertEquals(1500L, TimingMapper.absoluteSample(1000L, 500))
        // отрицательный frame -> 0
        assertEquals(1000L, TimingMapper.absoluteSample(1000L, -5))
    }
}

class VideoExportTimelineTest {

    private fun para(text: String, start: Long, end: Long, words: List<WordTiming>) =
        ParagraphTiming(text, text, start, end, words)

    @Test
    fun `paragraphAtSample finds active paragraph`() {
        val timeline = VideoExportTimeline(
            sampleRate = 44100,
            channelCount = 1,
            totalSamples = 1000,
            paragraphs = listOf(
                para("a", 0, 300, emptyList()),
                para("b", 300, 600, emptyList()),
                para("c", 600, 1000, emptyList()),
            ),
        )
        assertEquals("a", timeline.paragraphAtSample(150)?.displayText)
        assertEquals("b", timeline.paragraphAtSample(300)?.displayText)
        assertEquals("b", timeline.paragraphAtSample(599)?.displayText)
        assertEquals("c", timeline.paragraphAtSample(900)?.displayText)
    }

    @Test
    fun `paragraphAtSample clamps before first and after last`() {
        val timeline = VideoExportTimeline(
            44100, 1, 1000,
            listOf(para("a", 100, 200, emptyList()), para("b", 300, 500, emptyList())),
        )
        assertEquals("a", timeline.paragraphAtSample(0)?.displayText)
        assertEquals("b", timeline.paragraphAtSample(9999)?.displayText)
    }

    @Test
    fun `wordAtSample returns word at or before sample`() {
        val p = para(
            "hello world",
            0, 100,
            listOf(
                WordTiming(0 until 5, 10L),
                WordTiming(6 until 11, 60L),
            ),
        )
        assertEquals(0 until 5, timelineWith(p).wordAtSample(10, p)?.displayRange)
        assertEquals(0 until 5, timelineWith(p).wordAtSample(59, p)?.displayRange)
        assertEquals(6 until 11, timelineWith(p).wordAtSample(60, p)?.displayRange)
        assertEquals(6 until 11, timelineWith(p).wordAtSample(99, p)?.displayRange)
    }

    @Test
    fun `wordAtSample returns null when no words`() {
        val p = para("x", 0, 100, emptyList())
        assertEquals(null, timelineWith(p).wordAtSample(10, p))
    }

    private fun timelineWith(p: ParagraphTiming) = VideoExportTimeline(44100, 1, 1000, listOf(p))
}
