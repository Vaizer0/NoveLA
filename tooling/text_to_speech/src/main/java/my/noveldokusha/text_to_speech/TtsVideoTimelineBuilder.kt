package my.noveldokusha.text_to_speech

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
    fun build(chunks: List<TtsChunkTiming>, sampleRate: Int): TtsVideoTimeline {
        if (chunks.isEmpty()) return TtsVideoTimeline(emptyList(), 0L, TimelineTimingMode.APPROXIMATE)
        require(sampleRate > 0)
        val paragraphs = chunks.map { chunk ->
            val duration = (chunk.endUs - chunk.startUs).coerceAtLeast(1L)
            val valid = chunk.rangeEvents.filter { it.end > it.start && it.start >= 0 && it.end <= chunk.preparedText.length && it.frame >= 0 }
            val ranges = if (valid.isNotEmpty()) {
                val sorted = valid.sortedBy { it.frame }
                sorted.mapIndexedNotNull { index, event ->
                    val mapped = chunk.displayMapping.displayRangeForPrepared(event.start, event.end) ?: return@mapIndexedNotNull null
                    val start = (chunk.startUs + event.frame.toLong() * 1_000_000L / sampleRate).coerceIn(chunk.startUs, chunk.endUs - 1)
                    val next = sorted.getOrNull(index + 1)?.frame
                    val endFromFrame = if (next != null && next >= event.frame) chunk.startUs + next.toLong() * 1_000_000L / sampleRate else chunk.endUs
                    val end = maxOf(start + 1, endFromFrame.coerceAtMost(chunk.endUs))
                    VideoSpokenRange(start, end, event.start, event.end, mapped.first, mapped.last + 1, TimelineTimingMode.EXACT)
                }
            } else emptyList()
            val finalRanges = ranges.ifEmpty { proportionalRanges(chunk, duration) }
            VideoParagraph("${chunk.blockIndex}:${chunk.chunkIndex}", chunk.displayMapping.displayText.text, chunk.preparedText, chunk.startUs, chunk.endUs, chunk.blockIndex, chunk.chunkIndex, if (ranges.isNotEmpty()) TimelineTimingMode.EXACT else TimelineTimingMode.APPROXIMATE, finalRanges)
        }
        return TtsVideoTimeline(paragraphs, chunks.maxOf { it.endUs }, if (paragraphs.any { it.timingMode == TimelineTimingMode.APPROXIMATE }) TimelineTimingMode.APPROXIMATE else TimelineTimingMode.EXACT)
    }

    private fun proportionalRanges(chunk: TtsChunkTiming, duration: Long): List<VideoSpokenRange> {
        val words = Regex("\\S+").findAll(chunk.displayMapping.displayText.text).toList()
        if (words.isEmpty()) return emptyList()
        return words.mapIndexed { i, word ->
            val start = chunk.startUs + duration * i / words.size
            val end = maxOf(start + 1, chunk.startUs + duration * (i + 1) / words.size)
            VideoSpokenRange(start, end, 0, chunk.preparedText.length, word.range.first, word.range.last + 1, TimelineTimingMode.APPROXIMATE)
        }
    }
}
