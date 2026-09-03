package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTimelineParagraphTimingTest {

    private fun build(
        audioDurationMs: Int,
        paragraphs: List<List<Pair<String, Int>>>,
        withRanges: Boolean = false,
    ): TtsTimeline {
        val builder = TtsTimelineBuilder()
        builder.beginChapter("Novel", "Chapter", 0, "ORIGINAL")

        paragraphs.forEach { slices ->
            builder.beginParagraph()
            slices.forEachIndexed { sliceIndex, (text, audioMs) ->
                builder.registerSlice(text)
                builder.setSliceFormat(1000, 1)
                builder.onAudioAvailable(audioMs * 2)
                if (withRanges) {
                    builder.onRangeStart(0, text.length, 0)
                    if (text.length > 1) {
                        builder.onRangeStart(1, text.length, audioMs)
                    }
                }
                assertTrue(sliceIndex >= 0)
            }
            builder.endParagraph()
        }

        return builder.build(
            audioFileName = "chapter.wav",
            audioSampleRate = 1000,
            audioChannels = 1,
            audioDurationMs = audioDurationMs,
        )
    }

    @Test
    fun `paragraph times come from PCM even when ranges are absent`() {
        val timeline = build(
            audioDurationMs = 3000,
            paragraphs = listOf(
                listOf("first" to 1200),
                listOf("second" to 800),
                listOf("third" to 1000),
            ),
            withRanges = false,
        )

        assertEquals(0, timeline.paragraphs[0].startMs)
        assertEquals(1200, timeline.paragraphs[0].endMs)
        assertEquals(1200, timeline.paragraphs[1].startMs)
        assertEquals(2000, timeline.paragraphs[1].endMs)
        assertEquals(2000, timeline.paragraphs[2].startMs)
        assertEquals(3000, timeline.paragraphs[2].endMs)
        assertTrue(timeline.paragraphs.all { it.ranges.isEmpty() })
    }

    @Test
    fun `range timing remains tied to exact slice audio position`() {
        val timeline = build(
            audioDurationMs = 2000,
            paragraphs = listOf(
                listOf("first" to 1000, "second" to 1000),
            ),
            withRanges = true,
        )

        val ranges = timeline.paragraphs.single().ranges
        assertEquals(4, ranges.size)
        assertEquals(0, ranges[0].startMs)
        assertEquals(1000, ranges[2].startMs)
        assertEquals("first", ranges[0].text)
        assertEquals("second", ranges[2].text)
    }

    @Test
    fun `paragraph timing does not expand to later paragraph when last range is early`() {
        val timeline = build(
            audioDurationMs = 2000,
            paragraphs = listOf(
                listOf("first" to 1000),
                listOf("second" to 1000),
            ),
            withRanges = true,
        )

        assertEquals(1000, timeline.paragraphs[0].endMs)
        assertEquals(1000, timeline.paragraphs[1].startMs)
        assertEquals(2000, timeline.paragraphs[1].endMs)
    }
}
