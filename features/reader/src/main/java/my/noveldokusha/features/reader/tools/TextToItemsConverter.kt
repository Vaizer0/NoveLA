package my.noveldokusha.features.reader.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import my.noveldokusha.core.BookTextMapper
import my.noveldokusha.features.reader.domain.ImgEntry
import my.noveldokusha.features.reader.domain.ReaderItem

internal suspend fun textToItemsConverter(
    chapterUrl: String,
    chapterIndex: Int,
    chapterItemPositionDisplacement: Int,
    text: String
): List<ReaderItem> = withContext(Dispatchers.Default) {

    // 1. Очистка текста.
    // Удаляем HTML теги, но НЕ нормализуем все пробелы в один,
    // чтобы не убить \n\n (границы абзацев).
    val cleanText = text
        .replace(Regex("<(?!(img|/img))[^>]*>"), "") // Удаляем все теги, кроме img
        .replace(Regex("[\\t\\p{Z}]+"), " ")      // Чистим только горизонтальные пробелы

    // 2. Делим текст на логические блоки (абзацы и части длинных абзацев)
    val paragraphs = processTextIntoLogicalBlocks(cleanText)

    // 3. Превращаем блоки в ReaderItem
    paragraphs.mapIndexed { position, paragraph ->
        async {
            generateITEM(
                chapterUrl = chapterUrl,
                chapterIndex = chapterIndex,
                chapterItemPosition = position + chapterItemPositionDisplacement,
                text = paragraph,
                location = when (position) {
                    0 -> ReaderItem.Location.FIRST
                    paragraphs.lastIndex -> ReaderItem.Location.LAST
                    else -> ReaderItem.Location.MIDDLE
                }
            )
        }
    }.awaitAll()
}

/**
 * Разбивает текст на абзацы, а затем на части, соблюдая логические границы
 */
private fun processTextIntoLogicalBlocks(text: String): List<String> {
    val result = mutableListOf<String>()
    // Делим по двойному переносу (стандарт для абзацев)
    val paragraphs = text.split(Regex("\\n\\n+")).filter { it.isNotBlank() }

    for (paragraph in paragraphs) {
        val trimmedParagraph = paragraph.trim()
        if (trimmedParagraph.isEmpty()) continue

        // Сохраняем отступ (indentation), если он был
        val firstNonSpace = paragraph.indexOfFirst { !it.isWhitespace() }
        val indentation = if (firstNonSpace > 0) paragraph.substring(0, firstNonSpace) else ""

        val subBlocks = splitParagraphRespectingLogicalBlocks(trimmedParagraph)

        if (subBlocks.isNotEmpty()) {
            // Добавляем отступ только к первому под-блоку разделившегося абзаца
            result.add(indentation + subBlocks[0])
            if (subBlocks.size > 1) {
                result.addAll(subBlocks.subList(1, subBlocks.size))
            }
        }
    }
    return result
}

/**
 * Умное разделение длинного абзаца.
 * Гарантирует, что кавычки и скобки не будут разорваны.
 */
private fun splitParagraphRespectingLogicalBlocks(paragraph: String): List<String> {
    // Если абзац короткий или это техническая запись изображения — не трогаем
    if (paragraph.length <= 512 || paragraph.contains("imgEntry")) {
        return listOf(paragraph)
    }

    val result = mutableListOf<String>()
    var currentChunk = StringBuilder()

    var bracketDepth = 0
    var quoteState = false
    var safeSplitIndexInChunk = -1

    val openingBrackets = setOf('[', '(', '{', '<')
    val closingBrackets = setOf(']', ')', '}', '>')
    val quotes = setOf('"', '«', '»', '“', '”', '„', '‘', '’')

    for (char in paragraph) {
        currentChunk.append(char)

        // Обновляем состояние контекста
        when (char) {
            in openingBrackets -> bracketDepth++
            in closingBrackets -> bracketDepth--
            in quotes -> quoteState = !quoteState
        }

        // Мы в "безопасной зоне", если все скобки закрыты и кавычки парные
        val isSafeZone = bracketDepth <= 0 && !quoteState

        if (isSafeZone) {
            // Приоритет 1: знаки препинания
            if (char == '.' || char == '!' || char == '?' || char == ';') {
                safeSplitIndexInChunk = currentChunk.length
            }
            // Приоритет 2: пробел после длинного текста
            else if (char == ' ' && currentChunk.length >= 350) {
                safeSplitIndexInChunk = currentChunk.length
            }
        }

        // Лимиты: 512 для нормального сплита, 1500 для аварийного (если кавычка не закрылась)
        val isTooLong = currentChunk.length >= 1500

        if ((currentChunk.length >= 512 && safeSplitIndexInChunk != -1) || isTooLong) {
            val splitAt = if (safeSplitIndexInChunk != -1) safeSplitIndexInChunk else currentChunk.length

            val chunkToTake = currentChunk.substring(0, splitAt).trim()
            if (chunkToTake.isNotEmpty()) {
                result.add(chunkToTake)
            }

            // Создаем новый чанк из остатка
            val remaining = if (splitAt < currentChunk.length) {
                currentChunk.substring(splitAt).trimStart()
            } else ""

            currentChunk = StringBuilder(remaining)

            // Пересчитываем глубину для остатка (важно для корректного продолжения цикла)
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

private fun generateITEM(
    chapterUrl: String,
    chapterIndex: Int,
    chapterItemPosition: Int,
    text: String,
    location: ReaderItem.Location
): ReaderItem = try {
    when (val imgEntry = BookTextMapper.ImgEntry.fromXMLString(text)) {
        null -> ReaderItem.Body(
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition,
            text = text,
            location = location
        )
        else -> ReaderItem.Image(
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition,
            text = text,
            location = location,
            image = ImgEntry(path = imgEntry.path, yrel = imgEntry.yrel)
        )
    }
} catch (e: Exception) {
    ReaderItem.Body(chapterUrl, chapterIndex, chapterItemPosition, text, location)
}