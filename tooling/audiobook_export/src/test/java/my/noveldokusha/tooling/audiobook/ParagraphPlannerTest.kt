package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPlannerTest {

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

    @Test
    fun planParagraphs_skipsDecoratorOnlyParagraphs() {
        val plans = planParagraphs(
            paragraphs = listOf("***", "===", "Real text."),
            maxChunkLength = 100,
        )
        assertTrue(plans[0].chunks.isEmpty())
        assertTrue(plans[1].chunks.isEmpty())
        assertEquals(listOf("Real text."), plans[2].chunks)
    }

    @Test
    fun planParagraphs_cleansDecorationsLikeLiveTts() {
        val plans = planParagraphs(
            paragraphs = listOf("=== Chapter One ==="),
            maxChunkLength = 100,
        )
        assertEquals("Chapter One", plans[0].text)
        assertEquals(listOf("Chapter One"), plans[0].chunks)
    }

    @Test
    fun planParagraphs_shortParagraph_keptWhole() {
        val plans = planParagraphs(listOf("short"), maxChunkLength = 100)
        assertEquals(listOf("short"), plans[0].chunks)
    }

    @Test
    fun planParagraphs_longParagraph_chunkedWithoutLosingText() {
        val text = buildString { repeat(40) { append("Sentence number $it ends here. ") } }.trim()
        val plans = planParagraphs(listOf(text), maxChunkLength = 60)
        assertTrue(plans[0].chunks.size > 1)
        assertEquals(text, plans[0].chunks.joinToString(""))
        assertTrue(plans[0].chunks.all { it.isNotBlank() })
    }
}
