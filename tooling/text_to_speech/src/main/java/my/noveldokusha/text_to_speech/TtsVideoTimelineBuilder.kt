package my.noveldokusha.text_to_speech

/** Raw range event captured during a single TTS synthesis pass. */
data class TtsRangeEvent(val blockIndex: Int, val chunkIndex: Int, val start: Int, val end: Int, val frame: Int)

data class TtsChunkTiming(
    val blockIndex: Int,
    val chunkIndex: Int,
    val preparedText: String,
    val displayMapping: VideoDisplayMapping,
    val startUs: Long,
    val endUs: Long,
    val rangeEvents: List<TtsRangeEvent>,
)

object TtsVideoTimelineBuilder {
    fun build(chunks: List<TtsChunkTiming>): TtsVideoTimeline {
        if (chunks.isEmpty()) return TtsVideoTimeline(emptyList(), 0L, TimelineTimingMode.APPROXIMATE)
        val paragraphs = chunks.map { chunk ->
            val duration = (chunk.endUs - chunk.startUs).coerceAtLeast(1L)
            val valid = chunk.rangeEvents.filter { it.end > it.start && it.start >= 0 && it.end <= chunk.preparedText.length }
            val ranges = if (valid.isNotEmpty()) {
                val sorted = valid.sortedBy { it.start }
                sorted.mapIndexedNotNull { index, event ->
                    val mapped = chunk.displayMapping.displayRangeForPrepared(event.start, event.end) ?: return@mapIndexedNotNull null
                    val nextFrame = sorted.getOrNull(index + 1)?.frame
                    val startRatio = if (event.frame >= 0) event.frame.toDouble() / maxOf(1, event.frame + duration.toInt()) else index.toDouble() / sorted.size
                    val endRatio = if (nextFrame != null && nextFrame >= event.frame) 1.0 else (index + 1).toDouble() / sorted.size
                    val start = chunk.startUs + (duration * startRatio.coerceIn(0.0, 1.0)).toLong()
                    val end = maxOf(start + 1, chunk.startUs + (duration * endRatio.coerceIn(0.0, 1.0)).toLong())
                    VideoSpokenRange(start, end.coerceAtMost(chunk.endUs), event.start, event.end, mapped.first, mapped.last + 1, TimelineTimingMode.EXACT)
                }
            } else emptyList()
            val finalRanges = if (ranges.isNotEmpty()) ranges else proportionalRanges(chunk, duration)
            VideoParagraph(
                id = "${chunk.blockIndex}:${chunk.chunkIndex}",
                displayText = chunk.displayMapping.displayText.text,
                preparedText = chunk.preparedText,
                startUs = chunk.startUs,
                endUs = chunk.endUs,
                blockIndex = chunk.blockIndex,
                chunkIndex = chunk.chunkIndex,
                timingMode = if (valid.isNotEmpty() && finalRanges.isNotEmpty()) TimelineTimingMode.EXACT else TimelineTimingMode.APPROXIMATE,
                spokenRanges = finalRanges,
            )
        }
        return TtsVideoTimeline(paragraphs, chunks.maxOf { it.endUs }, if (paragraphs.any { it.timingMode == TimelineTimingMode.APPROXIMATE }) TimelineTimingMode.APPROXIMATE else TimelineTimingMode.EXACT)
    }

    private fun proportionalRanges(chunk: TtsChunkTiming, duration: Long): List<VideoSpokenRange> {
        val words = Regex("\\S+").findAll(chunk.displayMapping.displayText.text).toList()
        if (words.isEmpty()) return emptyList()
        return words.mapIndexed { index, word ->
            val start = chunk.startUs + duration * index / words.size
            val end = maxOf(start + 1, chunk.startUs + duration * (index + 1) / words.size)
            VideoSpokenRange(start, end, 0, chunk.preparedText.length, word.range.first, word.range.last + 1, TimelineTimingMode.APPROXIMATE)
        }
    }
}
