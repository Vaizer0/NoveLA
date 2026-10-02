package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter

/**
 * Joint split of a paragraph pair; both sides split by SentenceSplitter, grouped to equal count.
 *
 * Invariant: concatenating the groups of either side with " " reproduces the full
 * SentenceSplitter output of that side — content is never lost, only regrouped.
 */
fun splitParagraphPair(original: String, translated: String): List<Pair<String, String>> {
    val originalPieces = SentenceSplitter.splitParagraph(original)
    val translatedPieces = SentenceSplitter.splitParagraph(translated)

    if (originalPieces.size == translatedPieces.size) return originalPieces.zip(translatedPieces)

    // Unequal granularity: the finer side is regrouped into min size consecutive
    // non-empty groups, the coarser side passes through element by element.
    val groupCount = minOf(originalPieces.size, translatedPieces.size)
    return if (originalPieces.size > translatedPieces.size) {
        groupPieces(originalPieces, groupCount).zip(translatedPieces)
    } else {
        originalPieces.zip(groupPieces(translatedPieces, groupCount))
    }
}

/**
 * Splits [pieces] into [groupCount] consecutive non-empty groups.
 * Base size is pieces.size / groupCount; the remainder is added to the FIRST groups (+1 each).
 * Groups joined with " " reproduce the full [pieces] content.
 */
private fun groupPieces(pieces: List<String>, groupCount: Int): List<String> {
    // Called only with pieces.size > groupCount, so baseSize >= 1 and every group is non-empty.
    val baseSize = pieces.size / groupCount
    val remainder = pieces.size % groupCount
    val groups = ArrayList<String>(groupCount)
    var start = 0
    for (i in 0 until groupCount) {
        val groupSize = baseSize + if (i < remainder) 1 else 0
        groups.add(pieces.subList(start, start + groupSize).joinToString(" "))
        start += groupSize
    }
    return groups
}
