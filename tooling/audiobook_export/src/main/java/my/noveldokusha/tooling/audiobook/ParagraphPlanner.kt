package my.noveldokusha.tooling.audiobook

import my.noveldokusha.text_to_speech.cleanTextForTts
import my.noveldokusha.text_to_speech.delimiterAwareTextSplitter
import my.noveldokusha.text_to_speech.isOnlyDecorators
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
    /** Очищенный текст абзаца (тот же, что читает живой TTS) — он попадёт в JSON. */
    val text: String,
    /** TTS-порции в порядке озвучки. */
    val chunks: List<String>,
) {
    val isEmpty: Boolean get() = chunks.isEmpty()
}

/**
 * Планирует озвучку для набора абзацев: отбрасывает декоративные
 * фрагменты, чистит текст ровно теми же правилами, что живой TTS, и делит
 * длинные абзацы тем же сплиттером, что использует движок.
 *
 * Индексы в результате соответствуют исходным логическим абзацам,
 * включая пропущенные, — чтобы таймлайн не «съезжал» относительно текста.
 */
fun planParagraphs(
    paragraphs: List<String>,
    maxChunkLength: Int,
): List<ParagraphPlan> =
    paragraphs.mapIndexed { index, raw ->
        val cleaned = if (isOnlyDecorators(raw)) "" else cleanTextForTts(raw)
        val chunks = if (cleaned.isBlank()) {
            emptyList()
        } else {
            // Тот же сплиттер, что у живого TTS (TextToSpeechManager.speak).
            delimiterAwareTextSplitter(cleaned, maxChunkLength, '.')
        }
        ParagraphPlan(paragraphIndex = index, text = cleaned, chunks = chunks)
    }

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
