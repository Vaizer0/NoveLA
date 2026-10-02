package my.noveldokusha.core.text

import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.utils.STRIP_HTML_TAGS
import timber.log.Timber

private val IMG_TAG_REGEX = Regex("<img\\b", RegexOption.IGNORE_CASE)
private val COLLAPSE_SPACES = Regex("[ ]+")
private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

/**
 * Converts a raw chapter body (HTML) into the exact paragraph list the reader
 * feeds to live TTS.
 *
 * Mirrors `TextToItemsConverter`: `<img>` tags act as hard paragraph
 * boundaries and are removed, everything else is delegated to
 * [bodyToParagraphs]. This is the single shared source of truth so the
 * audiobook export synthesizes the same text as live reading.
 */
fun htmlToTtsParagraphs(
    html: String,
    userRegexRules: List<RegexRule> = emptyList(),
    sentenceSplittingEnabled: Boolean = false,
): List<String> {
    if (html.isBlank()) return emptyList()

    val result = mutableListOf<String>()
    var remaining = html

    while (true) {
        val match = IMG_TAG_REGEX.find(remaining) ?: break
        val before = remaining.substring(0, match.range.first)
        if (before.isNotBlank()) {
            result += bodyToParagraphs(before, userRegexRules, sentenceSplittingEnabled)
        }
        val afterMatch = remaining.substring(match.range.first)
        val endIdx = afterMatch.indexOf('>')
        remaining = if (endIdx >= 0) afterMatch.substring(endIdx + 1) else ""
    }

    if (remaining.isNotBlank()) {
        result += bodyToParagraphs(remaining, userRegexRules, sentenceSplittingEnabled)
    }
    return result
}

/**
 * Pure body-text conversion shared by the reader and the audiobook export:
 * strip HTML, normalize, apply user regex rules, split into logical blocks and
 * optionally split into sentences. Returns non-blank trimmed paragraphs.
 */
fun bodyToParagraphs(
    text: String,
    userRegexRules: List<RegexRule> = emptyList(),
    sentenceSplittingEnabled: Boolean = false,
): List<String> {
    val cleanText = text
        .replace(STRIP_HTML_TAGS, "")
        .replace("<", "\u27E8")
        .replace(">", "\u27E9")
        .replace("\r\n", "\n")
        .replace("\u00A0", " ")
        .replace(COLLAPSE_SPACES, " ")

    val processedText = applyUserRegexRules(cleanText, userRegexRules)
    val blocks = processTextIntoLogicalBlocks(processedText, splitLongParagraphs = !sentenceSplittingEnabled)
    val paragraphs = if (sentenceSplittingEnabled) {
        blocks.flatMap { SentenceSplitter.splitParagraph(it) }
    } else {
        blocks
    }
    return paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
}

/** Applies every enabled user regex rule in order. */
fun applyUserRegexRules(text: String, rules: List<RegexRule>): String {
    var result = text
    rules.filter { it.isEnabled }.forEach { rule ->
        try {
            val regex = Regex(rule.effectivePattern)
            result = result.replace(regex, rule.replacement)
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply user regex rule: ${rule.effectivePattern}")
        }
    }
    return result
}

/** Splits normalized body text into logical paragraphs. */
fun processTextIntoLogicalBlocks(text: String, splitLongParagraphs: Boolean = true): List<String> {
    val result = mutableListOf<String>()

    var splitResult = text.split(PARAGRAPH_BREAK).filter { it.isNotBlank() }

    if (splitResult.size <= 1 && text.contains("\n")) {
        splitResult = text.split("\n").filter { it.isNotBlank() }
    }

    for (paragraph in splitResult) {
        val trimmedParagraph = paragraph.trim()
        if (trimmedParagraph.isEmpty()) continue

        val firstNonSpace = paragraph.indexOfFirst { !it.isWhitespace() }
        val indentation = if (firstNonSpace > 0) paragraph.substring(0, firstNonSpace) else ""

        val subBlocks = if (splitLongParagraphs) {
            splitParagraphRespectingLogicalBlocks(trimmedParagraph)
        } else {
            listOf(trimmedParagraph)
        }

        if (subBlocks.isNotEmpty()) {
            result.add(indentation + subBlocks[0])
            if (subBlocks.size > 1) {
                result.addAll(subBlocks.subList(1, subBlocks.size))
            }
        }
    }
    return result
}

/** Splits a > 800 char paragraph on sentence/space boundaries without tearing brackets or quotes. */
fun splitParagraphRespectingLogicalBlocks(paragraph: String): List<String> {
    if (paragraph.length <= 800) {
        return listOf(paragraph)
    }

    val result = mutableListOf<String>()
    var currentChunk = StringBuilder()

    var bracketDepth = 0
    var quoteState = false
    var safeSplitIndexInChunk = -1

    // ponytail: removed < > from brackets — HTML tags already stripped, <literal content> isn't bracket pairs
    val openingBrackets = setOf('[', '(', '{')
    val closingBrackets = setOf(']', ')', '}')
    val quotes = setOf('"', '\u00AB', '\u00BB', '\u201C', '\u201D', '\u201E', '\u2018', '\u2019')

    for (char in paragraph) {
        currentChunk.append(char)

        when (char) {
            in openingBrackets -> bracketDepth++
            in closingBrackets -> bracketDepth--
            in quotes -> quoteState = !quoteState
        }

        val isSafeZone = bracketDepth <= 0 && !quoteState

        if (isSafeZone) {
            if (char == '.' || char == '!' || char == '?' || char == ';' || char == ':') {
                safeSplitIndexInChunk = currentChunk.length
            } else if (char == ' ' && currentChunk.length >= 400) {
                safeSplitIndexInChunk = currentChunk.length
            }
        }

        if ((currentChunk.length >= 800 && safeSplitIndexInChunk != -1) || currentChunk.length >= 2000) {
            val splitAt = if (safeSplitIndexInChunk != -1) {
                safeSplitIndexInChunk.coerceAtMost(currentChunk.length)
            } else {
                val lastSpace = currentChunk.lastIndexOf(' ')
                if (lastSpace != -1) (lastSpace + 1).coerceAtMost(currentChunk.length) else currentChunk.length
            }

            val chunkToTake = if (splitAt > 0 && splitAt <= currentChunk.length) {
                currentChunk.substring(0, splitAt).trim()
            } else {
                currentChunk.toString().trim()
            }
            if (chunkToTake.isNotEmpty()) {
                result.add(chunkToTake)
            }

            val remaining = if (splitAt > 0 && splitAt < currentChunk.length) {
                currentChunk.substring(splitAt).trimStart()
            } else if (splitAt >= currentChunk.length) {
                ""
            } else {
                currentChunk.toString().trimStart()
            }

            currentChunk = StringBuilder(remaining)
            bracketDepth = countUnbalancedBrackets(remaining, openingBrackets, closingBrackets)
            quoteState = countQuotes(remaining, quotes) % 2 != 0
            safeSplitIndexInChunk = -1
        }
    }

    if (currentChunk.isNotBlank()) {
        result.add(currentChunk.toString().trim())
    }

    return if (result.isEmpty()) listOf(paragraph) else result
}

private fun countUnbalancedBrackets(str: String, open: Set<Char>, close: Set<Char>): Int {
    var depth = 0
    for (char in str) {
        if (char in open) depth++
        else if (char in close) depth--
    }
    return depth.coerceAtLeast(0)
}

private fun countQuotes(str: String, quotes: Set<Char>): Int = str.count { it in quotes }
