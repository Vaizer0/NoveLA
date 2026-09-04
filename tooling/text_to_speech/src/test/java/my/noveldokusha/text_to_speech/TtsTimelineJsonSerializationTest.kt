package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTimelineJsonSerializationTest {
    @Test
    fun `native word ranges are present in serialized json`() {
        val timeline = TtsTimeline(
            chapter = TtsTimelineChapter(
                novelTitle = "Novel",
                chapterTitle = "Chapter 1",
                chapterIndex = 0,
                source = "ORIGINAL",
                audioFile = "1 - Chapter 1.wav",
            ),
            audio = TtsTimelineAudio(
                format = TtsAudioFormat.WAV,
                sampleRate = 48000,
                channels = 1,
                durationMs = 1000,
            ),
            text = TtsTimelineText(
                preparedText = "Hello world",
                characterCount = 11,
            ),
            paragraphs = listOf(
                TtsTimelineParagraph(
                    index = 0,
                    text = "Hello world",
                    startChar = 0,
                    endChar = 11,
                    startMs = 0,
                    endMs = 1000,
                    ranges = listOf(
                        TtsTimelineRange(
                            startChar = 0,
                            endChar = 5,
                            startMs = 0,
                            endMs = 500,
                            text = "Hello",
                            frameStart = 0,
                            frameEnd = 24000,
                        ),
                        TtsTimelineRange(
                            startChar = 6,
                            endChar = 11,
                            startMs = 500,
                            endMs = 1000,
                            text = "world",
                            frameStart = 24000,
                            frameEnd = null,
                        ),
                    ),
                )
            ),
        )

        val json = timelineToJson(timeline)
        val decoded = timelineFromJson(json)

        assertTrue(json.contains("\"ranges\""))
        assertTrue(json.contains("\"text\":\"Hello\""))
        assertTrue(json.contains("\"startMs\":500"))
        assertEquals(2, decoded.paragraphs.single().ranges.size)
        assertEquals("Hello", decoded.paragraphs.single().ranges[0].text)
        assertEquals(0, decoded.paragraphs.single().ranges[0].startMs)
        assertEquals(500, decoded.paragraphs.single().ranges[0].endMs)
        assertEquals("world", decoded.paragraphs.single().ranges[1].text)
        assertEquals(500, decoded.paragraphs.single().ranges[1].startMs)
        assertEquals(1000, decoded.paragraphs.single().ranges[1].endMs)
    }
}
