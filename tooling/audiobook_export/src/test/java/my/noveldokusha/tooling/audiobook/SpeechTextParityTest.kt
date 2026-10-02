package my.noveldokusha.tooling.audiobook

import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.text.htmlToTtsParagraphs
import my.noveldokusha.text_to_speech.cleanTextForTts
import my.noveldokusha.text_to_speech.delimiterAwareTextSplitter
import my.noveldokusha.text_to_speech.isOnlyDecorators
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the invariant that the export synthesizes exactly the text live TTS
 * reads: both must run the shared [htmlToTtsParagraphs] (paragraph building),
 * [isOnlyDecorators]/[cleanTextForTts] (cleaning) and
 * [delimiterAwareTextSplitter] (chunking).
 */
class SpeechTextParityTest {

    private val maxChunk = 12_000

    @Test
    fun exportChunks_matchLiveTtsProcessing() {
        val html = """
            <p>=== Chapter One ===</p>
            <p>Hello <b>world</b>. This is the first sentence and it is long enough to matter.</p>
            <p>***</p>
            <p>Second paragraph.</p>
        """.trimIndent()

        val rules = listOf(RegexRule(pattern = "\\[note\\]", replacement = ""))

        val paragraphs = htmlToTtsParagraphs(html, rules, sentenceSplittingEnabled = false)

        // The exact per-item path live TTS takes: skip decorators, clean, split.
        val liveChunks = paragraphs.flatMap { raw ->
            if (isOnlyDecorators(raw)) {
                emptyList()
            } else {
                delimiterAwareTextSplitter(cleanTextForTts(raw), maxChunk, '.')
            }
        }

        val exportChunks = planParagraphs(paragraphs, maxChunk)
            .filter { !it.isEmpty }
            .flatMap { it.chunks }

        assertEquals(liveChunks, exportChunks)
        assertEquals(liveChunks.joinToString(""), exportChunks.joinToString(""))
    }
}
