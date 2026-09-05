package my.noveldokusha.text_to_speech

import timber.log.Timber

/**
 * Builds a timeline from native TTS range callbacks and the exact PCM stream
 * written to the exported WAV.
 *
 * Native range callbacks are optional: paragraph timing must never depend on
 * them. Paragraph boundaries come from the actual sequential PCM bytes, while
 * ranges come only from native TTS timing information.
 */
class TtsTimelineBuilder {

    private val lock = Any()

    private var novelTitle = ""
    private var chapterTitle = ""
    private var chapterIndex = 0
    private var source = ""

    private val paragraphs = mutableListOf<MutableParagraph>()
    private var currentParagraph: MutableParagraph? = null

    private var preparedTextAcc = StringBuilder()
    private var nextParagraphStart = 0

    /** Absolute PCM duration already received from the TTS callbacks. */
    private var chapterAudioDurationMs = 0L

    fun beginChapter(
        novelTitle: String,
        chapterTitle: String,
        chapterIndex: Int,
        source: String,
    ) {
        synchronized(lock) {
            this.novelTitle = novelTitle
            this.chapterTitle = chapterTitle
            this.chapterIndex = chapterIndex
            this.source = source
            paragraphs.clear()
            currentParagraph = null
            preparedTextAcc = StringBuilder()
            nextParagraphStart = 0
            chapterAudioDurationMs = 0L
        }
    }

    fun beginParagraph() {
        synchronized(lock) {
            currentParagraph = MutableParagraph(
                startCharInPrepared = nextParagraphStart,
                startMs = chapterAudioDurationMs,
            )
        }
    }

    fun registerSlice(sliceText: String) {
        synchronized(lock) {
            val para = currentParagraph ?: throw IllegalStateException("No open paragraph")
            para.addSlice(
                sliceText = sliceText,
                startMsAcc = chapterAudioDurationMs,
            )
        }
    }

    fun setSliceFormat(sampleRate: Int, channels: Int) {
        synchronized(lock) {
            currentParagraph?.setSliceFormat(sampleRate, channels)
        }
    }

    fun onAudioAvailable(byteCount: Int) {
        synchronized(lock) {
            if (byteCount <= 0) return
            val para = currentParagraph ?: return
            val addedDurationMs = para.addSliceAudioBytes(byteCount)
            chapterAudioDurationMs = para.currentAudioEndMs(chapterAudioDurationMs)
                .coerceAtLeast(chapterAudioDurationMs)
            if (addedDurationMs <= 0.0) {
                Timber.w("ttsTimeline audio callback received without a valid PCM format")
            }
        }
    }

    fun onRangeStart(start: Int, end: Int, frame: Int) {
        synchronized(lock) {
            currentParagraph?.addRange(start, end, frame)
        }
    }

    fun endParagraph() {
        synchronized(lock) {
            val para = currentParagraph ?: return
            para.endMs = chapterAudioDurationMs.coerceAtLeast(para.startMs)
            if (paragraphs.isNotEmpty()) preparedTextAcc.append(PARAGRAPH_SEPARATOR)
            preparedTextAcc.append(para.text())
            paragraphs += para
            currentParagraph = null
            nextParagraphStart = preparedTextAcc.length + PARAGRAPH_SEPARATOR.length
        }
    }

    fun build(
        audioFileName: String,
        audioSampleRate: Int,
        audioChannels: Int,
        audioDurationMs: Int,
        audioFormat: String = TtsAudioFormat.WAV,
    ): TtsTimeline {
        synchronized(lock) {
            if (currentParagraph != null) {
                throw IllegalStateException("build() called with an open paragraph")
            }
            if (paragraphs.isEmpty()) {
                throw IllegalStateException("Cannot build empty timeline: no text synthesized")
            }

            val preparedText = preparedTextAcc.toString()
            val flat = buildList {
                paragraphs.forEachIndexed { pIdx, p ->
                    p.rangeEntries.forEach { add(pIdx to it) }
                }
            }

            // A range ends at the next native range only when that next range
            // belongs to the same paragraph and slice. The final range of every
            // slice ends at that slice's actual PCM boundary. This is critical:
            // using the whole chapter duration here makes the final word of a
            // slice remain highlighted through the rest of the rendered video.
            val rangeEndMs = LongArray(flat.size) { i ->
                val (p0, r0) = flat[i]
                if (i + 1 < flat.size) {
                    val (p1, r1) = flat[i + 1]
                    if (p0 == p1 && r0.sliceIndex == r1.sliceIndex) {
                        r1.startMs
                    } else {
                        paragraphs[p0].sliceEndMs(r0.sliceIndex)
                    }
                } else {
                    paragraphs[p0].sliceEndMs(r0.sliceIndex)
                }
            }

            val frameEnd = LongArray(flat.size) { -1L }
            for (i in 0 until flat.size - 1) {
                val (p0, r0) = flat[i]
                val (p1, r1) = flat[i + 1]
                if (p0 == p1 && r0.sliceIndex == r1.sliceIndex) {
                    frameEnd[i] = r1.frame.toLong()
                }
            }

            val perParagraph = Array(paragraphs.size) { mutableListOf<Int>() }
            flat.forEachIndexed { i, (pIdx, _) -> perParagraph[pIdx].add(i) }

            val finalized = paragraphs.mapIndexed { pIdx, p ->
                val textStart = p.startCharInPrepared
                val textEnd = p.startCharInPrepared + p.text().length

                val pageRanges = perParagraph[pIdx].mapNotNull { flatIdx ->
                    val raw = flat[flatIdx].second
                    val relStart = raw.offsetInParagraph + raw.start
                    val relEnd = raw.offsetInParagraph + raw.end
                    val startChar = (textStart + relStart).coerceIn(textStart, textEnd)
                    val endChar = (textStart + relEnd).coerceIn(startChar, textEnd)
                    if (endChar <= startChar) return@mapNotNull null

                    val startMs = raw.startMs.coerceIn(0L, audioDurationMs.toLong().coerceAtLeast(0L))
                    val endUpper = audioDurationMs.toLong().coerceAtLeast(startMs)
                    val endMsV = rangeEndMs[flatIdx].coerceIn(startMs, endUpper)
                    TtsTimelineRange(
                        startChar = startChar,
                        endChar = endChar,
                        startMs = startMs.toInt(),
                        endMs = endMsV.toInt(),
                        text = preparedText.substring(startChar, endChar),
                        frameStart = if (raw.frame >= 0) raw.frame else null,
                        frameEnd = if (frameEnd[flatIdx] >= 0) frameEnd[flatIdx].toInt() else null,
                    )
                }

                val paragraphStartMs = p.startMs.coerceIn(0L, audioDurationMs.toLong().coerceAtLeast(0L))
                val paragraphEndMs = p.endMs
                    .coerceIn(paragraphStartMs, audioDurationMs.toLong().coerceAtLeast(paragraphStartMs))
                    .toInt()

                TtsTimelineParagraph(
                    index = pIdx,
                    text = p.text(),
                    startChar = textStart,
                    endChar = textEnd,
                    startMs = paragraphStartMs.toInt(),
                    endMs = paragraphEndMs,
                    ranges = pageRanges,
                )
            }

            if (chapterAudioDurationMs != audioDurationMs.toLong()) {
                Timber.w(
                    "ttsTimeline PCM duration mismatch: callbacks=%dms, WAV=%dms",
                    chapterAudioDurationMs,
                    audioDurationMs,
                )
            }
            validate(preparedText, finalized)

            return TtsTimeline(
                schemaVersion = TtsTimeline.CURRENT_SCHEMA_VERSION,
                chapter = TtsTimelineChapter(
                    novelTitle = novelTitle,
                    chapterTitle = chapterTitle,
                    chapterIndex = chapterIndex,
                    source = source,
                    audioFile = audioFileName,
                ),
                audio = TtsTimelineAudio(
                    format = audioFormat,
                    sampleRate = audioSampleRate,
                    channels = audioChannels,
                    durationMs = audioDurationMs,
                ),
                text = TtsTimelineText(
                    preparedText = preparedText,
                    characterCount = preparedText.length,
                ),
                paragraphs = finalized,
            )
        }
    }

    fun toJson(timeline: TtsTimeline): String = timelineToJson(timeline)

    fun fromJson(json: String): TtsTimeline = timelineFromJson(json)

    private class SliceEntry(
        val sliceText: String,
        val offsetInParagraph: Int,
        val startMsAcc: Long,
    ) {
        var sampleRate: Int = 0
        var channels: Int = 0
        var totalAudioBytes: Long = 0
        var durationMs: Double = 0.0
    }

    private data class RangeEntry(
        val sliceIndex: Int,
        val offsetInParagraph: Int,
        val start: Int,
        val end: Int,
        val frame: Int,
        val startMs: Long,
    )

    private class MutableParagraph(
        val startCharInPrepared: Int,
        val startMs: Long,
    ) {
        private val slices = mutableListOf<SliceEntry>()
        private val ranges = mutableListOf<RangeEntry>()
        private var nextSliceOffset = 0

        var endMs: Long = startMs

        val rangeEntries: List<RangeEntry> get() = ranges

        fun addSlice(sliceText: String, startMsAcc: Long) {
            slices += SliceEntry(
                sliceText = sliceText,
                offsetInParagraph = nextSliceOffset,
                startMsAcc = startMsAcc,
            )
            nextSliceOffset += sliceText.length
        }

        fun setSliceFormat(sampleRate: Int, channels: Int) {
            if (slices.isEmpty()) return
            val last = slices.last()
            if (sampleRate > 0) last.sampleRate = sampleRate
            if (channels > 0) last.channels = channels
        }

        fun addSliceAudioBytes(byteCount: Int): Double {
            if (slices.isEmpty()) return 0.0
            val last = slices.last()
            if (last.sampleRate <= 0 || last.channels <= 0) return 0.0
            last.totalAudioBytes += byteCount
            last.durationMs = last.totalAudioBytes.toDouble() /
                (last.sampleRate.toDouble() * last.channels * 2.0) * 1000.0
            return last.durationMs
        }

        fun currentAudioEndMs(previousChapterEndMs: Long): Long {
            val last = slices.lastOrNull() ?: return previousChapterEndMs
            return (last.startMsAcc + last.durationMs).toLong()
        }

        fun sliceEndMs(sliceIndex: Int): Long {
            val slice = slices.getOrNull(sliceIndex) ?: return startMs
            return (slice.startMsAcc + slice.durationMs).toLong()
        }

        fun addRange(start: Int, end: Int, frame: Int) {
            if (slices.isEmpty()) return
            val slice = slices.last()
            val sliceIdx = slices.size - 1
            val sampleRate = slice.sampleRate
            val startMs = if (sampleRate > 0 && frame >= 0) {
                slice.startMsAcc + (frame.toDouble() * 1000.0 / sampleRate.toDouble()).toLong()
            } else {
                slice.startMsAcc
            }
            ranges += RangeEntry(
                sliceIndex = sliceIdx,
                offsetInParagraph = slice.offsetInParagraph,
                start = start,
                end = end,
                frame = frame,
                startMs = startMs,
            )
        }

        fun text(): String = slices.joinToString("") { it.sliceText }
    }

    private fun validate(preparedText: String, paragraphs: List<TtsTimelineParagraph>) {
        val expectedLength = paragraphs.sumOf { it.text.length } +
            (paragraphs.size - 1) * PARAGRAPH_SEPARATOR.length
        if (preparedText.length != expectedLength) {
            Timber.w(
                "ttsTimeline preparedText length mismatch: %d != %d",
                preparedText.length,
                expectedLength,
            )
        }
        paragraphs.forEach { p ->
            if (p.startChar < 0 || p.endChar < p.startChar || p.endChar > preparedText.length) {
                Timber.w("ttsTimeline paragraph out of bounds: %s", p)
                return
            }
            p.ranges.forEach { r ->
                if (r.text != preparedText.substring(
                        r.startChar.coerceIn(0, preparedText.length),
                        r.endChar.coerceIn(0, preparedText.length),
                    )
                ) {
                    Timber.w("ttsTimeline range text mismatch: %s", r)
                }
            }
        }
    }

    companion object {
        const val PARAGRAPH_SEPARATOR = "\n\n"
    }
}
