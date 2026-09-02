package my.noveldokusha.features.reader.tools

import kotlinx.coroutines.runBlocking
import my.noveldokusha.text_to_speech.TtsTextPreparer
import org.junit.Assert.assertEquals
import org.junit.Test

class TextToItemsConverterParityTest {
    @Test
    fun readerBodyTextUsesCanonicalTtsPreparation() = runBlocking {
        val source = "<p>  First   paragraph&nbsp;with spaces.  </p>\n\n<div>Second paragraph.</div>"
        val expected = TtsTextPreparer.paragraphsFromBody(source)
        val actual = textToItemsConverter(
            chapterUrl = "chapter",
            chapterIndex = 0,
            chapterItemPositionDisplacement = 0,
            text = source,
        ).filterIsInstance<my.noveldokusha.features.reader.domain.ReaderItem.Body>()
            .map { it.text }
        assertEquals(expected, actual)
    }

    @Test
    fun readerAndCanonicalPipelineStayIdenticalForLongLogicalBlocks() = runBlocking {
        val long = "A ".repeat(500) + "sentence. " + "B ".repeat(500)
        val expected = TtsTextPreparer.paragraphsFromBody(long)
        val actual = textToItemsConverter("chapter", 0, 0, long)
            .filterIsInstance<my.noveldokusha.features.reader.domain.ReaderItem.Body>()
            .map { it.text }
        assertEquals(expected, actual)
    }
}
