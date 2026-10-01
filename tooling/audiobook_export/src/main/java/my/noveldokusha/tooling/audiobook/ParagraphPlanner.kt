package my.noveldokusha.tooling.audiobook

import java.io.File
import java.io.RandomAccessFile

/**
 * Логический абзац главы, разбитый на TTS-порции.
 *
 * Внутренние границы порций пользователю не видны: в JSON попадает
 * один тайминг на весь абзац, равный сумме длительностей его порций.
 */
data class ParagraphPlan(
    val paragraphIndex: Int,
    /** Исходный очищенный текст абзаца — именно он попадёт в JSON. */
    val text: String,
    /** TTS-порции в порядке озвучки. */
    val chunks: List<String>,
) {
    val isEmpty: Boolean get() = chunks.isEmpty()
}

/**
 * Разбивает текст главы на логические абзацы.
 *
 * Границы абзацев соответствуют пустой строке — так же, как в ридере и
 * в EPUB-экспорте, чтобы JSON-таймлайн совпадал с визуальным текстом.
 */
fun splitChapterIntoParagraphs(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return PARAGRAPH_BREAK.split(body)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Планирует озвучку для набора абзацев: чистит текст, отбрасывает
 * декоративные фрагменты и делит длинные абзацы до лимита движка.
 *
 * Индексы в результате соответствуют исходным логическим абзацам,
 * включая пропущенные, — чтобы таймлайн не «съезжал» относительно текста.
 */
fun planParagraphs(
    paragraphs: List<String>,
    maxChunkLength: Int,
): List<ParagraphPlan> =
    paragraphs.mapIndexed { index, raw ->
        val cleaned = raw.cleanSpeechText()
        val chunks = if (cleaned.isEmpty()) {
            emptyList()
        } else {
            // Делим только по границам предложений/пробелов, чтобы не
            // рвать слова; splitter сам не знает про пробелы.
            splitIntoSpeechChunks(cleaned, maxChunkLength)
        }
        ParagraphPlan(paragraphIndex = index, text = cleaned, chunks = chunks)
    }

/**
 * Делит очищенный текст на части не длиннее [maxChunkLength],
 * предпочитая границы предложений, затем пробелов.
 */
internal fun splitIntoSpeechChunks(text: String, maxChunkLength: Int): List<String> {
    if (maxChunkLength <= 0) return listOf(text)
    if (text.length <= maxChunkLength) return listOf(text)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.length - start
        if (remaining <= maxChunkLength) {
            chunks += text.substring(start)
            break
        }
        val hardEnd = start + maxChunkLength
        // Ищем последнюю границу предложения внутри окна, иначе — последний пробел.
        val sentenceEnd = text.lastIndexOfAny(SENTENCE_ENDS, hardEnd - 1).let { if (it > start) it + 1 else -1 }
        val boundary = if (sentenceEnd > start) {
            sentenceEnd
        } else {
            val space = text.lastIndexOf(' ', hardEnd - 1)
            if (space > start) space + 1 else hardEnd
        }
        val end = boundary.coerceIn(start + 1, text.length)
        val piece = text.substring(start, end)
        if (piece.isNotBlank()) chunks += piece
        start = end
    }
    return chunks.ifEmpty { listOf(text) }
}

private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '…', ';', ':')

/**
 * Копирует PCM-данные набора сегментов в единый WAV, возвращая
 * суммарную длительность в мс.
 *
 * Тайминг каждого сегмента известен заранее (см. `PcmSegment.durationMs`),
 * поэтому суммирование выполняется в том же проходе, что и копирование.
 */
fun mergeSegments(
    target: File,
    segments: List<PcmSegment>,
    sources: List<RandomAccessFile>,
    sampleRateHz: Int,
    channels: Int,
): Long {
    require(segments.size == sources.size) { "segments and sources must have the same size" }
    var totalMs = 0L
    WavAudio.StreamingWavWriter(target, sampleRateHz, channels).use { writer ->
        segments.forEachIndexed { index, segment ->
            writer.append(segment, sources[index])
            totalMs += segment.durationMs()
        }
        writer.finish()
    }
    return totalMs
}
