package my.noveldokusha.text_to_speech

import my.noveldokusha.core.appPreferences.TtsAudioSource
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

class TtsVideoRequestSerializationTest {
    @Test fun roundTripPreservesExportIdentityAndVisualSnapshot() {
        val visual = TtsVideoVisualSettings(
            paragraphMode = ParagraphDisplayMode.CURRENT_WITH_CONTEXT,
            longParagraphMode = LongParagraphMode.SMOOTH_SCROLL,
            width = 1920,
            height = 1080,
            fps = 30,
            artworkMode = ArtworkMode.BOTH,
            artworkUris = listOf("content://art/1", "content://art/2"),
            slideshowEnabled = true,
            slideshowIntervalMode = SlideshowIntervalMode.FIXED_INTERVAL,
            slideshowIntervalMs = 6500L,
            slideshowTransition = SlideshowTransition.SUBTLE_ZOOM,
            slideshowSeed = 123456789L,
        )
        val request = TtsVideoRequest(
            jobId = "job-1",
            novelUrl = "novel",
            novelTitle = "Novel",
            chapterUrl = "chapter",
            chapterIndex = 7,
            chapterTitle = "Chapter",
            source = TtsAudioSource.TRANSLATED,
            translationSourceLang = "zh",
            translationTargetLang = "en",
            enginePackage = "engine",
            voiceId = "voice",
            speed = 1.1f,
            pitch = .9f,
            visual = visual,
            outputDirectoryUri = "content://folder/video",
        )
        val decoded = request.serialize().toTtsVideoRequest()
        assertNotNull(decoded)
        decoded!!
        assertEquals(request.jobId, decoded.jobId)
        assertEquals(request.source, decoded.source)
        assertEquals(request.translationSourceLang, decoded.translationSourceLang)
        assertEquals(request.translationTargetLang, decoded.translationTargetLang)
        assertEquals(request.enginePackage, decoded.enginePackage)
        assertEquals(request.voiceId, decoded.voiceId)
        assertEquals(request.visual.paragraphMode, decoded.visual.paragraphMode)
        assertEquals(request.visual.longParagraphMode, decoded.visual.longParagraphMode)
        assertEquals(request.visual.artworkUris, decoded.visual.artworkUris)
        assertEquals(request.visual.slideshowIntervalMode, decoded.visual.slideshowIntervalMode)
        assertEquals(request.visual.slideshowIntervalMs, decoded.visual.slideshowIntervalMs)
        assertEquals(request.visual.slideshowTransition, decoded.visual.slideshowTransition)
        assertEquals(request.visual.slideshowSeed, decoded.visual.slideshowSeed)
        assertEquals(request.outputDirectoryUri, decoded.outputDirectoryUri)
    }
}
