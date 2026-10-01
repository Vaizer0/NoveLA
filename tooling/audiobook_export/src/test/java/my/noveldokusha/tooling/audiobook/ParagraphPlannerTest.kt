package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphPlannerTest {
    @Test
    fun splitChapterIntoParagraphs_dividesOnLineBreaks() {
        val text = "First paragraph.\n\nSecond paragraph.\nThird line of second."
        val result = splitChapterIntoParagraphs(text)
        assertEquals(listOf("First paragraph.", "Second paragraph. Third line of second."), result)
    }

    @Test
    fun splitChapterIntoParagraphs_ignoresEmptyLines() {
        val text = "\n\n  \nParagraph A\n\n\nParagraph B  \n"
        val result = splitChapterIntoParagraphs(text)
        assertEquals(listOf("Paragraph A", "Paragraph B"), result)
    }

    @Test
    fun splitIntoSpeechChunks_respectsMaxLength() {
        val text = "one two three four five"
        val chunks = splitIntoSpeechChunks(text, maxChunkLength = 6)
        assertEquals(listOf("one two", "three", "four", "five"), chunks)
    }
}
