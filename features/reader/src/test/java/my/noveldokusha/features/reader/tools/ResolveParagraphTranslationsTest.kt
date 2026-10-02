package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveParagraphTranslationsTest {

    // "Source" and "Target" are both 6 chars, so a paragraph and its aligned
    // translation are identical in length and every length ratio is stable.
    private fun sentence(kind: String, paragraphIndex: Int, sentenceIndex: Int): String =
        "$kind paragraph $paragraphIndex sentence $sentenceIndex brings a long calm description of the morning light over the quiet street."

    private fun paragraph(kind: String, paragraphIndex: Int, sentenceCount: Int = 3): String =
        (0 until sentenceCount).joinToString(" ") { sentence(kind, paragraphIndex, it) }

    @Test
    fun equalSizes_completeWithoutRewrite() {
        val paragraphs = (0 until 3).map { paragraph("Source", it) }
        val entries = (0 until 3).map { paragraph("Target", it) }

        assertEquals(
            ResolveResult.Complete(entries, rewriteNeeded = false),
            resolveParagraphTranslations(entries, paragraphs)
        )
    }

    @Test
    fun fewerEntriesThanParagraphs_partial() {
        val paragraphs = (0 until 3).map { paragraph("Source", it) }
        val entries = (0 until 2).map { paragraph("Target", it) }

        assertEquals(ResolveResult.Partial, resolveParagraphTranslations(entries, paragraphs))
    }

    @Test
    fun garbageTail_truncatedAndRewriteRequested() {
        val paragraphs = (0 until 3).map { paragraph("Source", it) }
        val aligned = (0 until 3).map { paragraph("Target", it) }
        val garbage = (0 until 4).map { "junk$it" }
        val entries = aligned + garbage

        assertEquals(7, entries.size)
        // Hypothesis A passes: first n entries are paragraph-length, tail is discarded.
        assertEquals(
            ResolveResult.Complete(aligned, rewriteNeeded = true),
            resolveParagraphTranslations(entries, paragraphs)
        )
    }

    @Test
    fun sentenceLevelEntries_groupedBackIntoParagraphs() {
        val paragraphs = (0 until 2).map { paragraph("Source", it) }
        // One entry per SENTENCE: each entry matches its sentence length, not the paragraph length.
        val entries = (0 until 2).flatMap { p ->
            (0 until 3).map { s -> sentence("Target", p, s) }
        }

        assertEquals(3, SentenceSplitter.splitParagraph(paragraphs[0]).size)
        assertEquals(3, SentenceSplitter.splitParagraph(paragraphs[1]).size)
        assertEquals(6, entries.size)

        // Hypothesis A fails (ratio ≈ 1/3 < 0.4), hypothesis B passes (sum(k_i) = 6 = entries.size).
        val result = resolveParagraphTranslations(entries, paragraphs)
        assertEquals(
            ResolveResult.Complete((0 until 2).map { paragraph("Target", it) }, rewriteNeeded = true),
            result
        )

        // Invariant: grouped translations reproduce the full cached content when joined.
        val complete = result as ResolveResult.Complete
        assertEquals(entries.joinToString(" "), complete.translations.joinToString(" "))
    }

    @Test
    fun bothHypothesesFail_fallbackWithoutRewrite() {
        val paragraphs = (0 until 2).map { paragraph("Source", it) }
        // 6 entries, but far too short: A's ratios < 0.4, B's grouped ratios < 0.4 too.
        val entries = (0 until 6).map { "t$it" }

        assertEquals(
            ResolveResult.Complete(entries.take(2), rewriteNeeded = false),
            resolveParagraphTranslations(entries, paragraphs)
        )
    }

    // --- M1 counterexample -------------------------------------------------
    // Stale sentence-level cache (cachedSize 3 > paragraphCount 2).
    // P0 = 164 chars, split into two sentences of 100 and 63 chars; the cache
    // holds those two sentences plus a stale 44-char entry.
    // Hypothesis A (head = [s1, s2]) passes the 60% window with SHIFTED pairs
    // (0.61 and 1.43/1.26 — both inside 0.4..2.5), so before the fix it won and
    // the wrong pairs were rewritten to the DB. The structural grouping B must win.
    private val m1FirstSentence =
        "The quiet street outside the window was bathed in pale golden light from the early morning sunlight."
    private val m1SecondSentence =
        "The gentle breeze carried the smell of rain across the rooftop."
    private val m1Paragraph0 = "$m1FirstSentence $m1SecondSentence"
    private val m1StaleEntry = "The old lantern by the gates flickered once."
    private val m1Paragraph2 = "Somewhere beyond the far hill a dog began to bark."

    @Test
    fun m1_staleSentenceCache_structuralGroupingWins_overFuzzyHead() {
        // Second paragraph is exactly the stale 44-char entry's length.
        val paragraphs = listOf(m1Paragraph0, m1StaleEntry)
        val entries = listOf(m1FirstSentence, m1SecondSentence, m1StaleEntry)

        assertEquals(100, m1FirstSentence.length)
        assertEquals(63, m1SecondSentence.length)
        assertEquals(44, m1StaleEntry.length)
        assertEquals(2, SentenceSplitter.splitParagraph(m1Paragraph0).size)

        assertEquals(
            ResolveResult.Complete(listOf(m1Paragraph0, m1StaleEntry), rewriteNeeded = true),
            resolveParagraphTranslations(entries, paragraphs)
        )
    }

    @Test
    fun m1_staleSentenceCache_groupingWins_whenSecondParagraphIs50Chars() {
        // Second paragraph (50 chars) differs from the stale entry (44 chars):
        // structural counts still match (2 + 1 = 3), so B groups while A's head
        // would again pass with shifted pairs.
        val paragraphs = listOf(m1Paragraph0, m1Paragraph2)
        val entries = listOf(m1FirstSentence, m1SecondSentence, m1StaleEntry)

        assertEquals(50, m1Paragraph2.length)
        assertEquals(1, SentenceSplitter.splitParagraph(m1Paragraph2).size)

        assertEquals(
            ResolveResult.Complete(listOf(m1Paragraph0, m1StaleEntry), rewriteNeeded = true),
            resolveParagraphTranslations(entries, paragraphs)
        )
    }
}
