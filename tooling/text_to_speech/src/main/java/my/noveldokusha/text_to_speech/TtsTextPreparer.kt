package my.noveldokusha.text_to_speech

import me.nanihadesuka.algorithms.delimiterAwareTextSplitter
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.utils.STRIP_HTML_TAGS
import my.noveldokusha.core.utils.applyUserRegexRules

/**
 * Общая подготовка текста главы к синтезу речи.
 *
 * Гарантирует, что текст, идущий в аудио-загрузку главы, обрабатывается теми же
 * правилами, что и при живой озвучке/переводе в читалке:
 *  1. Схема разбиения тела главы на логические абзацы — дословно повторяет
 *     buildBodyItems из TextToItemsConverter (стрип HTML, applyUserRegexRules,
 *     processTextIntoLogicalBlocks).
 *  2. Очистка декоративных символов и проверка «только декораторы» — те же
 *     правила, что у ReaderTextToSpeech.
 *  3. Разбиение текста на куски для TextToSpeech.speak/synthesizeToFile —
 *     через общий delimiterAwareTextSplitter (tooling/algorithms).
 */
object TtsTextPreparer {

    // ── 1. Разбиение тела главы на абзацы (параллельно buildBodyItems) ─────────

    private val COLLAPSE_SPACES = Regex("[ ]+")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

    /**
     * Превращает сырое тело главы (HTML) в список очищенных абзацев — точно так же,
     * как читалка разбивает его на ReaderItem.Body. Применяет [userRegexRules].
     */
    fun paragraphsFromBody(body: String, userRegexRules: List<RegexRule> = emptyList()): List<String> {
        val cleanText = body
            .replace(STRIP_HTML_TAGS, "")
            .replace("<", "⟨")
            .replace(">", "⟩")
            .replace("\r\n", "\n")
            .replace("\u00A0", " ")
            .replace(COLLAPSE_SPACES, " ")

        val processedText = applyUserRegexRules(cleanText, userRegexRules)

        return processTextIntoLogicalBlocks(processedText).map { para ->
            para.trim()
        }.filter { it.isNotBlank() }
    }

    private fun processTextIntoLogicalBlocks(text: String): List<String> {
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

            val subBlocks = splitParagraphRespectingLogicalBlocks(trimmedParagraph)

            if (subBlocks.isNotEmpty()) {
                result.add(indentation + subBlocks[0])
                if (subBlocks.size > 1) {
                    result.addAll(subBlocks.subList(1, subBlocks.size))
                }
            }
        }
        return result
    }

    private fun splitParagraphRespectingLogicalBlocks(paragraph: String): List<String> {
        if (paragraph.length <= 800) {
            return listOf(paragraph)
        }

        val result = mutableListOf<String>()
        var currentChunk = StringBuilder()

        var bracketDepth = 0
        var quoteState = false
        var safeSplitIndexInChunk = -1

        val openingBrackets = setOf('[', '(', '{')
        val closingBrackets = setOf(']', ')', '}')
        val quotes = setOf('"', '«', '»', '“', '”', '„', '‘', '’')

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

    // ── 2. Очистка декоративных символов (параллельно ReaderTextToSpeech) ───────

    const val DECORATIVE_CHARS = """\-=*_~+#·•°─-┿"""
    private val SEPARATOR_ONLY = Regex("""^\s*[$DECORATIVE_CHARS]{3,}\s*$""")
    private val LEADING_DECORATIVE = Regex("""^[$DECORATIVE_CHARS]{3,}\s*""")
    private val TRAILING_DECORATIVE = Regex("""\s*[$DECORATIVE_CHARS]{3,}$""")

    /** true, если текст — только пустые строки и декоративные разделители. */
    fun isOnlyDecorators(text: String): Boolean {
        if (text.isBlank()) return true
        return text.lines().all { line ->
            line.isBlank() || SEPARATOR_ONLY.matches(line)
        }
    }

    /** Очищает текст от декоративного мусора по краям строк (для TTS). */
    fun cleanForTts(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replace(LEADING_DECORATIVE, "")
                .replace(TRAILING_DECORATIVE, "")
                .trim()
        }
    }

    /** Длина ведущего декоративного префикса первой строки (для подсветки). */
    fun leadingDecoratorLength(text: String): Int {
        return text.lines().firstOrNull()?.let { line ->
            LEADING_DECORATIVE.find(line)?.value?.length
        } ?: 0
    }

    // ── 3. Разбиение на куски для синтеза ──────────────────────────────────────

    /**
     * Разбивает [text] на куски не длиннее [maxSliceLength] (обычно
     * TextToSpeech.getMaxSpeechInputLength()), стараясь рвать по точке.
     */
    fun chunkIntoUtterances(text: String, maxSliceLength: Int): List<String> =
        delimiterAwareTextSplitter(
            fullText = text,
            maxSliceLength = maxSliceLength,
            charDelimiter = '.'
        )
}