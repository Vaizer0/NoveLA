package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter

/**
 * Resolution of a historical positional translation cache (index = chapter paragraph)
 * against the current paragraph list, when the cached array size may differ from
 * the current paragraph count.
 */
sealed interface ResolveResult {
    /**
     * translations aligned with paragraphs (i-th translation = i-th paragraph).
     * rewriteNeeded → overwrite the DB row with this list.
     */
    data class Complete(val translations: List<String>, val rewriteNeeded: Boolean) : ResolveResult

    /** cachedEntries.size < paragraphs.size — the caller translates the missing tail (existing partial flow). */
    data object Partial : ResolveResult
}

// Length-ratio window: covers natural expansion/contraction between languages
// (~0.7–1.5x) with slack for punctuation and tokenization differences.
private const val LENGTH_RATIO_MIN = 0.4
private const val LENGTH_RATIO_MAX = 2.5
// Required share of aligned pairs inside the window for a hypothesis to pass.
private const val LENGTH_RATIO_PASS_SHARE = 0.6

/**
 * Resolves a positional translation cache against [paragraphs].
 *
 * Both hypotheses are local (no API calls), B is checked first because it is
 * structural while A is fuzzy (see M1):
 * B — every paragraph was cached sentence-wise, so the entries are grouped back
 * by SentenceSplitter sentence counts;
 * A — the first n cached entries are paragraph translations, the tail is garbage.
 */
fun resolveParagraphTranslations(cachedEntries: List<String>, paragraphs: List<String>): ResolveResult {
    val cachedSize = cachedEntries.size
    val paragraphCount = paragraphs.size

    if (cachedSize == paragraphCount) return ResolveResult.Complete(cachedEntries, rewriteNeeded = false)
    if (cachedSize < paragraphCount) return ResolveResult.Partial

    val head = cachedEntries.take(paragraphCount)

    // Hypothesis B first: exact sentence-count grouping. It is structural, while
    // the fuzzy head window below can accept shifted pairs from a stale
    // sentence-level cache (cachedSize > paragraphCount) and rewrite them to DB.
    val grouped = groupBySentenceCounts(cachedEntries, paragraphs)
    if (grouped != null && lengthRatiosOk(grouped, paragraphs)) {
        return ResolveResult.Complete(grouped, rewriteNeeded = true)
    }

    // Hypothesis A: paragraph prefix (fuzzy length window).
    if (lengthRatiosOk(head, paragraphs)) return ResolveResult.Complete(head, rewriteNeeded = true)

    // Neither hypothesis passed: keep current behavior, no rewrite.
    return ResolveResult.Complete(head, rewriteNeeded = false)
}

/** True when at least [LENGTH_RATIO_PASS_SHARE] of the pairs fall into [LENGTH_RATIO_MIN]..[LENGTH_RATIO_MAX]. */
private fun lengthRatiosOk(candidates: List<String>, paragraphs: List<String>): Boolean {
    if (candidates.size != paragraphs.size) return false
    if (paragraphs.isEmpty()) return true
    var passed = 0
    for (i in paragraphs.indices) {
        if (lengthRatioOk(candidates[i], paragraphs[i])) passed++
    }
    return passed.toDouble() / paragraphs.size >= LENGTH_RATIO_PASS_SHARE
}

private fun lengthRatioOk(candidate: String, paragraph: String): Boolean {
    if (paragraph.isEmpty()) return candidate.isEmpty()
    val ratio = candidate.length.toDouble() / paragraph.length
    return ratio in LENGTH_RATIO_MIN..LENGTH_RATIO_MAX
}

/**
 * Hypothesis B: groups consecutive entries per paragraph by its sentence count.
 * Returns null when sum(sentence counts) != cachedEntries.size (count mismatch).
 */
private fun groupBySentenceCounts(cachedEntries: List<String>, paragraphs: List<String>): List<String>? {
    val sentenceCounts = paragraphs.map { SentenceSplitter.splitParagraph(it).size }
    if (sentenceCounts.sum() != cachedEntries.size) return null

    val grouped = ArrayList<String>(paragraphs.size)
    var start = 0
    for (count in sentenceCounts) {
        grouped.add(cachedEntries.subList(start, start + count).joinToString(" "))
        start += count
    }
    return grouped
}
