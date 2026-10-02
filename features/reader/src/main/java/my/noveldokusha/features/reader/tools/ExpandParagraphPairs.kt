package my.noveldokusha.features.reader.tools

import my.noveldokusha.core.text.SentenceSplitter
import my.noveldokusha.features.reader.domain.ReaderItem

/**
 * Expands Body items into sentence-level items after translations are attached.
 *
 * startPosition: chapterItemPosition of the first output item (a chapter Title takes 0).
 *
 * Only Body/Image/Title implement ReaderItem.Position (see TextToItemsConverter), so
 * non-position items (Divider, Progressbar, ...) are skipped and take no slot.
 *
 * Locations: an input FIRST marker moves to the first output position item, an input
 * LAST marker to the last one, everything else is MIDDLE. Markers live only on
 * Body/Image — a Title has no location field, so it cannot carry them.
 *
 * chapterIndex/chapterUrl of every output piece come from its source item.
 */
// internal: ReaderItem itself is internal, a public signature would expose it.
internal fun expandParagraphPairs(items: List<ReaderItem>, startPosition: Int): List<ReaderItem> {
    if (items.isEmpty()) return emptyList()

    val hasFirst = items.any { (it as? ReaderItem.ParagraphLocation)?.location == ReaderItem.Location.FIRST }
    val hasLast = items.any { (it as? ReaderItem.ParagraphLocation)?.location == ReaderItem.Location.LAST }

    val output = ArrayList<ReaderItem>(items.size)
    var position = startPosition

    for (item in items) {
        when (item) {
            is ReaderItem.Body -> {
                val sourceTranslation = item.textTranslated
                val pieces: List<Pair<String, String?>> = if (sourceTranslation != null) {
                    splitParagraphPair(item.text, sourceTranslation)
                } else {
                    SentenceSplitter.splitParagraph(item.text).map { it to null }
                }
                for ((pieceText, pieceTranslation) in pieces) {
                    output.add(
                        item.copy(
                            chapterItemPosition = position++,
                            text = pieceText,
                            textTranslated = pieceTranslation,
                            location = ReaderItem.Location.MIDDLE
                        )
                    )
                }
            }
            is ReaderItem.Image -> output.add(
                item.copy(chapterItemPosition = position++, location = ReaderItem.Location.MIDDLE)
            )
            is ReaderItem.Title -> output.add(item.copy(chapterItemPosition = position++))
            // Non-position items (Divider, BookEnd, ...) keep no slot and are skipped.
            else -> {}
        }
    }

    if (output.isNotEmpty()) {
        if (hasFirst) output[0] = withLocation(output[0], ReaderItem.Location.FIRST)
        if (hasLast) output[output.lastIndex] = withLocation(output[output.lastIndex], ReaderItem.Location.LAST)
    }
    return output
}

private fun withLocation(item: ReaderItem, location: ReaderItem.Location): ReaderItem = when (item) {
    is ReaderItem.Body -> item.copy(location = location)
    is ReaderItem.Image -> item.copy(location = location)
    else -> item
}
