package my.noveldokusha.features.reader.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.features.reader.domain.ImgEntry
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.text_to_speech.TtsTextPreparer
import org.jsoup.Jsoup
import timber.log.Timber

private val IMG_TAG_REGEX = Regex("<img\\b", RegexOption.IGNORE_CASE)

internal suspend fun textToItemsConverter(
    chapterUrl: String,
    chapterIndex: Int,
    chapterItemPositionDisplacement: Int,
    text: String,
    userRegexRules: List<RegexRule> = emptyList()
): List<ReaderItem> = withContext(Dispatchers.Default) {
    Timber.d("convert[%d] start: text.length=%d, displacement=%d", chapterIndex, text.length, chapterItemPositionDisplacement)

    val items = mutableListOf<ReaderItem>()
    var itemPosition = chapterItemPositionDisplacement
    var remaining = text
    var imgMatchCount = 0

    while (true) {
        val match = IMG_TAG_REGEX.find(remaining) ?: break
        imgMatchCount++
        val before = remaining.substring(0, match.range.first)
        if (before.isNotBlank()) {
            val bodyItems = buildBodyItems(before, chapterUrl, chapterIndex, itemPosition, userRegexRules)
            items.addAll(bodyItems)
            itemPosition += bodyItems.size
        }

        val afterMatch = remaining.substring(match.range.first)
        val imgTag = extractImgTag(afterMatch)
        if (imgTag != null) {
            val (src, yrel) = imgTag
            items.add(ReaderItem.Image(
                chapterUrl = chapterUrl,
                chapterIndex = chapterIndex,
                chapterItemPosition = itemPosition++,
                location = ReaderItem.Location.MIDDLE,
                text = "<img src=\"$src\" yrel=\"${"%.2f".format(yrel)}\">",
                image = ImgEntry(path = src, yrel = yrel)
            ))
        }

        val endIdx = afterMatch.indexOf('>')
        remaining = if (endIdx >= 0) afterMatch.substring(endIdx + 1) else ""
    }

    Timber.d("convert[%d] after loop: remaining.length=%d, items.size=%d, imgMatchCount=%d", chapterIndex, remaining.length, items.size, imgMatchCount)
    if (remaining.isNotBlank()) {
        val bodyItems = buildBodyItems(remaining, chapterUrl, chapterIndex, itemPosition, userRegexRules)
        items.addAll(bodyItems)
    }

    if (items.isNotEmpty()) {
        val firstType = items[0]::class.simpleName
        items[0] = items[0].let {
            when (it) {
                is ReaderItem.Body -> it.copy(location = ReaderItem.Location.FIRST)
                is ReaderItem.Image -> it.copy(location = ReaderItem.Location.FIRST)
                else -> it
            }
        }
        val lastIdx = items.lastIndex
        val lastType = items[lastIdx]::class.simpleName
        val lastBodyIdx = items.indexOfLast { it is ReaderItem.Body }
        items[lastIdx] = items[lastIdx].let {
            when (it) {
                is ReaderItem.Body -> it.copy(location = ReaderItem.Location.LAST)
                is ReaderItem.Image -> it.copy(location = ReaderItem.Location.LAST)
                else -> it
            }
        }
        Timber.d("convert[%d] locations: items.size=%d, first=%s, lastIdx=%d lastType=%s, lastBodyIdx=%d", chapterIndex, items.size, firstType, lastIdx, lastType, lastBodyIdx)
    } else {
        Timber.d("convert[%d] locations: items IS EMPTY", chapterIndex)
    }

    items
}

private fun extractImgTag(text: String): Pair<String, Float>? {
    val endIdx = text.indexOf('>')
    if (endIdx < 0) return null
    val tag = text.substring(0, endIdx + 1)
    return try {
        val img = Jsoup.parse(tag).selectFirst("img") ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }.takeIf { it.isNotBlank() } ?: return null
        val yrel = img.attr("yrel").toFloatOrNull()?.takeIf { it >= 0.01f } ?: 1.45f
        Pair(src, yrel)
    } catch (_: Exception) {
        null
    }
}

private fun buildBodyItems(
    text: String,
    chapterUrl: String,
    chapterIndex: Int,
    startPosition: Int,
    userRegexRules: List<RegexRule>
): List<ReaderItem.Body> = TtsTextPreparer.paragraphsFromBody(text, userRegexRules)
    .mapIndexed { i, paragraph ->
        ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            chapterItemPosition = startPosition + i,
            text = paragraph,
            location = ReaderItem.Location.MIDDLE,
        )
    }
