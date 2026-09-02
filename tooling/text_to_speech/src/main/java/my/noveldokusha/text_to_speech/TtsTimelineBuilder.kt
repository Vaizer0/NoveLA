package my.noveldokusha.text_to_speech

/**
 * Собирает [TtsTimeline] из native TTS-событий В ТОТ ЖЕ сеанс синтеза, который
 * пишет WAV.
 *
 * Координаты native колбэков:
 *  - [android.speech.tts.UtteranceProgressListener.onRangeStart] (start, end)
 *    смещены ОТНОСИТЕЛЬНО ТЕКУЩЕГО TTS-куска (slice), а не главы/абзаца.
 *    Куски партиционируют текст абзаца без потерь (см. [TtsTextPreparer]),
 *    поэтому у каждого куска есть детерминированное смещение внутри абзаца.
 *    Абсолютное смещение в preparedText = startParagraph + sliceOffset + rangeStart.
 *  - [android.speech.tts.UtteranceProgressListener.onRangeStart] frame отсчитан в
 *    реальной частоте синтеза куска ([...onBeginSynthesis]) —
 *    timeMs = sliceStartMs + frame / sampleRate * 1000, где sliceStartMs
 *    накапливается из фактических PCM-байт ([...onAudioAvailable]) каждого куска.
 *
 * Все времена — абсолютные монотонные мс от начала аудиофайла.
 * Состояние защищено mutex: пишется из треда TTS-колбэка, читается после синтеза.
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

    /** Точка входа: начать главу (перед синтезом). */
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
        }
    }

    /** Начать новый абзац в порядке главы (перед его кусками). */
    fun beginParagraph() {
        synchronized(lock) {
            currentParagraph = MutableParagraph(startCharInPrepared = nextParagraphStart)
        }
    }

    /** Зарегистрировать TTS-кусок (slice) перед синтезом. */
    fun registerSlice(sliceText: String) {
        synchronized(lock) {
            val para = currentParagraph ?: throw IllegalStateException("No open paragraph")
            para.addSlice(sliceText)
        }
    }

    /** Формат синтеза текущего куска (из onBeginSynthesis). */
    fun setSliceFormat(sampleRate: Int, channels: Int) {
        synchronized(lock) {
            currentParagraph?.setSliceFormat(sampleRate, channels)
        }
    }

    /** Фактические PCM-байты текущего куска (из onAudioAvailable) — для длительности куска. */
    fun onAudioAvailable(byteCount: Int) {
        synchronized(lock) {
            if (byteCount <= 0) return
            currentParagraph?.addSliceAudioBytes(byteCount)
        }
    }

    /** Записать native range-событие (из onRangeStart) текущего куска. */
    fun onRangeStart(start: Int, end: Int, frame: Int) {
        synchronized(lock) {
            currentParagraph?.addRange(start, end, frame)
        }
    }

    /** Завершить текущий абзац после синтеза всех его кусков. */
    fun endParagraph() {
        synchronized(lock) {
            val para = currentParagraph ?: return
            if (paragraphs.isNotEmpty()) preparedTextAcc.append(PARAGRAPH_SEPARATOR)
            preparedTextAcc.append(para.text())
            paragraphs += para
            currentParagraph = null
            // Следующий абзац начинается ПОСЛЕ разделителя.
            nextParagraphStart = preparedTextAcc.length + PARAGRAPH_SEPARATOR.length
        }
    }

    /**
     * Финализировать timeline по завершении синтеза и записи WAV.
     * @param audioFileName имя сгенерированного аудиофайла (как сохраняется воркером).
     * @param audioSampleRate частота готового WAV.
     * @param audioChannels число каналов готового WAV.
     * @param audioDurationMs фактическая длительность WAV (из метаданных файла).
     */
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

            // flat-список всех диапазонов в порядке главы (абзацы и их ranges уже упорядочены).
            val flat = buildList {
                paragraphs.forEachIndexed { pIdx, p -> p.rangeEntries.forEach { add(pIdx to it) } }
            }

            // endMs = старт следующего диапазона; последний диапазон оканчивается на длительности аудио.
            val endMs = LongArray(flat.size) { i ->
                if (i + 1 < flat.size) flat[i + 1].second.startMs
                else audioDurationMs.toLong()
            }

            // frameEnd = frameStart следующего диапазона в ТОМ ЖЕ куске, иначе null.
            val frameEnd = LongArray(flat.size) { -1L }
            for (i in 0 until flat.size - 1) {
                if (flat[i].second.sliceIndex == flat[i + 1].second.sliceIndex) {
                    frameEnd[i] = flat[i + 1].second.frame.toLong()
                }
            }

            // Позиция диапазона в flat для каждого абзаца (для присвоения текста/времён).
            val perParagraph = Array(paragraphs.size) { mutableListOf<Int>() }
            flat.forEachIndexed { i, (pIdx, _) -> perParagraph[pIdx].add(i) }

            val finalized = paragraphs.mapIndexed { pIdx, p ->
                val rangeIndices = perParagraph[pIdx]
                val pageRanges = rangeIndices.mapIndexed { pos, flatIdx ->
                    val raw = flat[flatIdx].second
                    val startChar = p.startCharInPrepared + raw.offsetInParagraph + raw.start
                    val endChar = p.startCharInPrepared + raw.offsetInParagraph + raw.end
                    TtsTimelineRange(
                        startChar = startChar,
                        endChar = endChar,
                        startMs = raw.startMs.toInt().coerceAtLeast(0),
                        endMs = endMs[flatIdx].toInt().coerceAtLeast(raw.startMs.toInt().coerceAtLeast(0)),
                        text = preparedText.substring(startChar, endChar),
                        frameStart = if (raw.frame >= 0) raw.frame else null,
                        frameEnd = if (frameEnd[flatIdx] >= 0) frameEnd[flatIdx].toInt() else null,
                    )
                }
                val startMs = pageRanges.firstOrNull()?.startMs ?: 0
                val endMsVal = pageRanges.lastOrNull()?.endMs?.coerceAtLeast(startMs) ?: startMs
                TtsTimelineParagraph(
                    index = pIdx,
                    text = p.text(),
                    startChar = p.startCharInPrepared,
                    endChar = p.startCharInPrepared + p.text().length,
                    startMs = startMs,
                    endMs = endMsVal,
                    ranges = pageRanges,
                )
            }

            validate(preparedText, finalized, audioDurationMs)

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

    /** Сериализация в детерминированный UTF-8 JSON (схема версионируется). */
    fun toJson(timeline: TtsTimeline): String = timelineToJson(timeline)

    /** Десериализация (для round-trip тестов и внешних потребителей). */
    fun fromJson(json: String): TtsTimeline = timelineFromJson(json)

    // ── Внутренние структуры ──────────────────────────────────────────────

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

    private class MutableParagraph(val startCharInPrepared: Int) {
        private val slices = mutableListOf<SliceEntry>()
        private val ranges = mutableListOf<RangeEntry>()
        private var nextSliceOffset = 0
        private var sliceStartAcc = 0L

        val rangeEntries: List<RangeEntry> get() = ranges

        fun addSlice(sliceText: String) {
            // Финализируем длительность ПРЕДЫДУЩЕГО куска в накопленный старт:
            // onAudioAvailable может приходить несколько раз за кусок, поэтому старт
            // следующего куска считается по ПОЛНОЙ длительности предыдущего.
            val prev = slices.lastOrNull()
            if (prev != null) {
                sliceStartAcc += prev.durationMs.toLong()
            }
            slices += SliceEntry(
                sliceText = sliceText,
                offsetInParagraph = nextSliceOffset,
                startMsAcc = sliceStartAcc,
            )
            nextSliceOffset += sliceText.length
        }

        fun setSliceFormat(sampleRate: Int, channels: Int) {
            if (slices.isEmpty()) return
            val last = slices.last()
            if (sampleRate > 0) last.sampleRate = sampleRate
            if (channels > 0) last.channels = channels
        }

        fun addSliceAudioBytes(byteCount: Int) {
            if (slices.isEmpty()) return
            val last = slices.last()
            if (last.sampleRate > 0 && last.channels > 0) {
                // Накопление: один кусок может прийти несколькими onAudioAvailable.
                last.totalAudioBytes += byteCount
                last.durationMs = last.totalAudioBytes.toDouble() /
                    (last.sampleRate.toDouble() * last.channels * 2.0) * 1000.0
            }
        }

        fun addRange(start: Int, end: Int, frame: Int) {
            if (slices.isEmpty()) return
            val slice = slices.last()
            val sliceIdx = slices.size - 1
            val sampleRate = slice.sampleRate
            val startMs = if (sampleRate > 0) {
                slice.startMsAcc + (frame.toLong() * 1000L) / sampleRate
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

    // Валидация инвариантов (требование 14).
    private fun validate(preparedText: String, paragraphs: List<TtsTimelineParagraph>, durationMs: Int) {
        val expectedLength = paragraphs.sumOf { it.text.length } +
            (paragraphs.size - 1) * PARAGRAPH_SEPARATOR.length
        require(preparedText.length == expectedLength) {
            "preparedText length mismatch: ${preparedText.length} != $expectedLength"
        }
        paragraphs.forEach { p ->
            require(p.startChar >= 0 && p.endChar >= 0) { "negative paragraph char offsets" }
            require(p.endChar >= p.startChar) { "paragraph endChar < startChar" }
            require(p.endChar <= preparedText.length) { "paragraph endChar out of bounds" }
            require(p.startMs >= 0) { "negative paragraph startMs" }
            require(p.endMs >= p.startMs) { "paragraph endMs < startMs" }
            p.ranges.forEach { r ->
                require(r.startChar >= p.startChar && r.endChar <= p.endChar) {
                    "range outside its paragraph"
                }
                require(r.endChar > r.startChar) { "range empty or reversed" }
                require(r.startMs in 0..durationMs) { "range startMs out of audio" }
                require(r.endMs in 0..durationMs) { "range endMs out of audio" }
                require(r.endMs >= r.startMs) { "range endMs < startMs" }
                require(r.text == preparedText.substring(r.startChar, r.endChar)) {
                    "range text mismatch"
                }
            }
            var prev = -1
            p.ranges.forEach { r ->
                require(r.startMs >= prev) { "non-monotonic range startMs" }
                prev = r.startMs
            }
        }
    }

    companion object {
        /** Разделитель между абзацами в preparedText. */
        const val PARAGRAPH_SEPARATOR = "\n\n"
    }
}
