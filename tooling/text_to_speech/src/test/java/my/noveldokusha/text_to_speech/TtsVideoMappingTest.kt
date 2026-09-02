package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVideoMappingTest {
    @Test fun replacementMapsToOriginalMatch() {
        val source = TtsVideoTextMapper.identity("alpha  beta")
        val replaced = TtsVideoTextMapper.replaceRange(source, 5, 7, " ")
        val trimmed = TtsVideoTextMapper.normalizeWhitespace(replaced)
        assertEquals("alpha beta", trimmed.text)
        val beta = trimmed.sourceForOutput(6, 10)
        assertNotNull(beta)
        assertEquals(6, beta!!.outputStart)
        assertEquals(10, beta.outputEnd)
        assertEquals(7, beta.sourceStart)
        assertEquals(11, beta.sourceEnd)
    }

    @Test fun trimKeepsOutputAndSourceRangesConsistent() {
        val mapped = TtsVideoTextMapper.trim(TtsVideoTextMapper.identity("  hello world  "))
        assertEquals("hello world", mapped.text)
        assertEquals(0, mapped.provenance.first().outputStart)
        assertEquals(mapped.text.length, mapped.provenance.last().outputEnd)
    }

    @Test fun unmappedRangeReturnsNull() {
        val mapped = VideoDisplayMapping("source", TtsVideoTextMapper.identity("tts"), MappedText("display", emptyList()), "0")
        assertNull(mapped.displayRangeForPrepared(0, 2))
    }
}

class TtsVideoTimelineTest {
    @Test fun proportionalFallbackCoversEntireChunk() {
        val mapping = VideoDisplayMapping("hello world", TtsVideoTextMapper.identity("hello world"), TtsVideoTextMapper.identity("hello world"), "0:0")
        val chunk = TtsChunkTiming(0,0,"hello world",mapping,0,2_000_000L,emptyList())
        val timeline = TtsVideoTimelineBuilder.build(listOf(chunk), 16_000)
        assertEquals(TimelineTimingMode.APPROXIMATE, timeline.timingMode)
        assertEquals(2, timeline.paragraphs.single().spokenRanges.size)
        assertEquals(0L, timeline.paragraphs.single().spokenRanges.first().startUs)
        assertEquals(2_000_000L, timeline.paragraphs.single().spokenRanges.last().endUs)
    }

    @Test fun callbackRangeMapsToVisibleRange() {
        val mapping = VideoDisplayMapping("hello", TtsVideoTextMapper.identity("hello"), TtsVideoTextMapper.identity("hello"), "0:0")
        val chunk = TtsChunkTiming(0,0,"hello",mapping,0,1_000_000L,listOf(TtsRangeEvent(0,0,0,5,0)))
        val timeline = TtsVideoTimelineBuilder.build(listOf(chunk), 16_000)
        val r = timeline.paragraphs.single().spokenRanges.single()
        assertEquals(0, r.displayStart); assertEquals(5, r.displayEnd); assertEquals(TimelineTimingMode.EXACT, r.timingMode)
    }
}
