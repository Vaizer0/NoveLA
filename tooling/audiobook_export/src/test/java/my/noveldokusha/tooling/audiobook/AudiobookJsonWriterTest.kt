package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookJsonWriterTest {
    private fun sampleTimeline(): List<AudiobookChapterTiming> {
        val builder = TimelineBuilder()
        builder.beginChapter(chapterIndex = 0, title = "Chapter 1", novelTitle = "Novel", chapterTitle = "Chapter 1")
        builder.endIntro(actualDurationMs = 1000)
        builder.addParagraph(paragraphIndex = 0, text = "First", actualDurationMs = 500)
        builder.addParagraph(paragraphIndex = 1, text = "Second", actualDurationMs = 500)
        builder.endChapter()
        return builder.build()
    }

    @Test
    fun buildDocument_containsChapterAndParagraphTimings() {
        val json = AudiobookJsonWriter.buildDocument(
            novelTitle = "Novel",
            novelUrl = "https://example.com/novel",
            format = AudiobookFormat.WAV,
            contentMode = AudiobookContentMode.ORIGINAL,
            sourceLanguage = "en",
            targetLanguage = null,
            startPosition = 0,
            endPosition = 0,
            totalDurationMs = 2000L,
            sampleRateHz = 22050,
            channels = 1,
            timeline = sampleTimeline(),
            visual = null,
        )
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"durationMs\": 2000"))
        assertTrue(json.contains("Chapter 1"))
        assertTrue(json.contains("\"start\": 1"))
        assertTrue(json.contains("\"end\": 1"))
    }

    @Test
    fun buildDocument_marksMp4VisualWhenPresent() {
        val json = AudiobookJsonWriter.buildDocument(
            novelTitle = "Novel",
            novelUrl = "url",
            format = AudiobookFormat.MP4,
            contentMode = AudiobookContentMode.TRANSLATION,
            sourceLanguage = "en",
            targetLanguage = "es",
            startPosition = 1,
            endPosition = 2,
            totalDurationMs = 2000L,
            sampleRateHz = 22050,
            channels = 1,
            timeline = sampleTimeline(),
            visual = VisualSegmentInfo(
                type = VisualSource.GIF,
                sourceName = "cover.gif",
                loop = true,
                durationMs = 3000L,
                width = 720,
                height = 1280,
            ),
        )
        assertTrue(json.contains("\"format\": \"mp4\""))
        assertTrue(json.contains("\"mode\": \"translation\""))
        assertTrue(json.contains("\"type\": \"gif\""))
        assertTrue(json.contains("\"loop\": true"))
    }

    @Test
    fun chapterRangeDisplayPositionsAreOneBased() {
        val range = ChapterRange(startPosition = 0, endPosition = 4)
        assertEquals(1, range.displayStart)
        assertEquals(5, range.displayEnd)
        assertEquals(5, range.count)
    }
}
