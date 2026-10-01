package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPlannerTest {
    @Test
    fun splitChapterIntoParagraphs_splitsOnBlankLine() {
        val text = "First paragraph.\n\nSecond paragraph.\nThird line of second."
        val result = splitChapterIntoParagraphs(text)
        assertEquals(listOf("First paragraph.", "Second paragraph.\nThird line of second."), result)
    }

    @Test
    fun splitChapterIntoParagraphs_ignoresEmptyLines() {
        val text = "\n\n  \nParagraph A\n\n\nParagraph B  \n"
        val result = splitChapterIntoParagraphs(text)
        assertEquals(listOf("Paragraph A", "Paragraph B"), result)
    }

    @Test
    fun splitIntoSpeechChunks_respectsMaxLengthAndKeepsAllText() {
        val text = "one two three four five"
        val chunks = splitIntoSpeechChunks(text, maxChunkLength = 6)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue("chunk '$chunk' is longer than 6", chunk.length <= 6)
            assertTrue("chunk '$chunk' is blank", chunk.isNotBlank())
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun splitIntoSpeechChunks_keepsShortTextWhole() {
        val text = "short"
        assertEquals(listOf("short"), splitIntoSpeechChunks(text, maxChunkLength = 100))
    }

    @Test
    fun planParagraphs_preservesOriginalParagraphIndices() {
        val plans = planParagraphs(
            paragraphs = listOf("first", "   ", "second"),
            maxChunkLength = 100,
        )
        assertEquals(3, plans.size)
        assertEquals(0, plans[0].paragraphIndex)
        assertTrue(plans[1].chunks.isEmpty())
        assertEquals("second", plans[2].text)
    }
}
