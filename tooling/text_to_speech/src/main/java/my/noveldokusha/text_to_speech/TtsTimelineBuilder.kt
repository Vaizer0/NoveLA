package my.noveldokusha.text_to_speech

import timber.log.Timber

/**
 * Builds a timeline from native TTS range callbacks and the exact PCM stream written to the WAV.
 * JSON offsets are emitted as Unicode code-point offsets because the cinematic renderer
 * uses code points for its text tokenizer.
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
    private var nextParagraphStartUtf16 = 0
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
            nextParagraphStartUtf16 = 0
            chapterAudioDurationMs = 0L
        }
    }

    fun beginParagraph() {
        synchronized(lock) {
            currentParagraph = MutableParagraph(
                startUtf16InPrepared = nextParagraphStartUtf16,
                startMs = chapterAudioDurationMs,
            )
        }
    }

    fun registerSlice(sliceText: String) {
        synchronized(lock) {
            val para = currentParagraph ?: throw IllegalStateException("No open paragraph")
            para.addSlice(sliceText, chapterAudioDurationMs)
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
            nextParagraphStartUtf16 = preparedTextAcc.length + PARAGRAPH_SEPARATOR.length
        }
    }

    fun build(
        audioFileName: String,
        audioSampleRate: Int,
        audioChannels: Int,
        audioDurationMs: Int,
        audioFormat: String = TtsAudioFormat.WAV,
    ): TtsTimeline = synchronized(lock) {
        check(currentParagraph == null) { "build() called with an open paragraph" }
        check(paragraphs.isNotEmpty()) { "Cannot build empty timeline" }

        val preparedText = preparedTextAcc.toString()
        val flat = buildList {
            paragraphs.forEachIndexed { pIdx, p ->
                p.rangeEntries.forEach { add(pIdx to it) }
            }
        }

        // Each range ends at the next native range within the same TTS slice. The final
        // native range of a slice ends at that slice's actual PCM boundary. Using the
        // entire chapter duration here would leave the last word highlighted until EOF.
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
            if (p0 == p1 && r0.sliceIndex == r1.sliceIndex && r1.frame >= 0) {
                frameEnd[i] = r1.frame.toLong()
            }
        }

        val paragraphRanges = Array(paragraphs.size) { mutableListOf<Int>() }
        flat.forEachIndexed { i, (pIdx, _) -> paragraphRanges[pIdx].add(i) }

        val finalized = paragraphs.mapIndexed { pIdx, p ->
            val paragraphUtf16Start = p.startUtf16InPrepared
            val paragraphUtf16End = paragraphUtf16Start + p.text().length
            val paragraphCodePointStart = preparedText.codePointCount(0, paragraphUtf16Start)
            val paragraphCodePointEnd = preparedText.codePointCount(0, paragraphUtf16End)

            val ranges = paragraphRanges[pIdx].mapNotNull { flatIdx ->
                val raw = flat[flatIdx].second
                val utf16Start = (paragraphUtf16Start + raw.offsetInParagraph + raw.start)
                    .coerceIn(paragraphUtf16Start, paragraphUtf16End)
                val utf16End = (paragraphUtf16Start + raw.offsetInParagraph + raw.end)
                    .coerceIn(utf16Start, paragraphUtf16End)
                if (utf16End <= utf16Start) return@mapNotNull null

                val startChar = preparedText.codePointCount(0, utf16Start)
                val endChar = preparedText.codePointCount(0, utf16End)
                if (endChar <= startChar) return@mapNotNull null

                val startMs = raw.startMs.coerceIn(0L, audioDurationMs.toLong().coerceAtLeast(0L))
                val endMs = rangeEndMs[flatIdx]
                    .coerceIn(startMs, audioDurationMs.toLong().coerceAtLeast(startMs))

                TtsTimelineRange(
                    startChar = startChar,
                    endChar = endChar,
                    startMs = startMs.toInt(),
                    endMs = endMs.toInt(),
                    text = preparedText.substring(utf16Start, utf16End),
                    frameStart = raw.frame.takeIf { it >= 0 },
                    frameEnd = frameEnd[flatIdx].takeIf { it >= 0 }?.toInt(),
                )
            }

            TtsTimelineParagraph(
                index = pIdx,
                text = p.text(),
                startChar = paragraphCodePointStart,
                endChar = paragraphCodePointEnd,
                startMs = p.startMs
                    .coerceIn(0L, audioDurationMs.toLong().coerceAtLeast(0L))
                    .toInt(),
                endMs = p.endMs
                    .coerceIn(p.startMs, audioDurationMs.toLong().coerceAtLeast(p.startMs))
                    .toInt(),
                ranges = ranges,
            )
        }

        validate(preparedText, finalized)

        if (chapterAudioDurationMs != audioDurationMs.toLong()) {
            Timber.w(
                "ttsTimeline PCM duration mismatch: callbacks=%dms, WAV=%dms",
                chapterAudioDurationMs,
                audioDurationMs,
            )
        }

        TtsTimeline(
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
                characterCount = preparedText.codePointCount(0, preparedText.length),
            ),
            paragraphs = finalized,
        )
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
        val startUtf16InPrepared: Int,
        val startMs: Long,
    ) {
        private val slices = mutableListOf<SliceEntry>()
        private val ranges = mutableListOf<RangeEntry>()
        private var nextSliceOffsetUtf16 = 0

        var endMs: Long = startMs

        val rangeEntries: List<RangeEntry> get() = ranges

        fun addSlice(sliceText: String, startMsAcc: Long) {
            slices += SliceEntry(sliceText, nextSliceOffsetUtf16, startMsAcc)
            nextSliceOffsetUtf16 += sliceText.length
        }

        fun setSliceFormat(sampleRate: Int, channels: Int) {
            slices.lastOrNull()?.let {
                if (sampleRate > 0) it.sampleRate = sampleRate
                if (channels > 0) it.channels = channels
            }
        }

        fun addSliceAudioBytes(byteCount: Int): Double {
            val last = slices.lastOrNull() ?: return 0.0
            if (last.sampleRate <= 0 || last.channels <= 0) return 0.0
            last.totalAudioBytes += byteCount
            last.durationMs = last.totalAudioBytes.toDouble() /
                (last.sampleRate.toDouble() * last.channels.toDouble() * 2.0) * 1000.0
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
            val sliceIdx = slices.lastIndex
            if (sliceIdx < 0) return
            val slice = slices[sliceIdx]
            val startMs = if (slice.sampleRate > 0 && frame >= 0) {
                slice.startMsAcc + (frame.toDouble() * 1000.0 / slice.sampleRate.toDouble()).toLong()
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
        val preparedCodePointCount = preparedText.codePointCount(0, preparedText.length)
        paragraphs.forEach { p ->
            if (p.startChar < 0 || p.endChar < p.startChar || p.endChar > preparedCodePointCount) {
                throw IllegalStateException("Timeline paragraph character bounds are invalid: ${p.index}")
            }
            if (p.endMs < p.startMs) {
                throw IllegalStateException("Timeline paragraph time bounds are invalid: ${p.index}")
            }
            p.ranges.forEach { r ->
                if (r.startChar < p.startChar || r.endChar > p.endChar || r.endChar <= r.startChar) {
                    throw IllegalStateException("Timeline range character bounds are invalid in paragraph ${p.index}")
                }
                if (r.endMs < r.startMs) {
                    throw IllegalStateException("Timeline range time bounds are invalid in paragraph ${p.index}")
                }
                val startUtf16 = preparedText.offsetByCodePoints(0, r.startChar)
                val endUtf16 = preparedText.offsetByCodePoints(0, r.endChar)
                if (r.text != preparedText.substring(startUtf16, endUtf16)) {
                    throw IllegalStateException("Timeline range text does not match preparedText in paragraph ${p.index}")
                }
            }
        }
    }

    companion object {
        const val PARAGRAPH_SEPARATOR = "\n\n"
    }
}
