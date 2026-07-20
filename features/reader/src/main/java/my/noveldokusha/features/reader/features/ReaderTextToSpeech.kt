package my.noveldokusha.features.reader.features

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import my.noveldokusha.features.reader.services.NarratorMediaControlsService
import timber.log.Timber
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.VoicePredefineState
import my.noveldokusha.features.reader.domain.ChapterIndex
import my.noveldokusha.features.reader.domain.ChapterLoaded
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.features.reader.domain.indexOfReaderItem
import my.noveldokusha.text_to_speech.AppTtsEngine
import my.noveldokusha.text_to_speech.TextToSpeechManager
import my.noveldokusha.text_to_speech.Utterance
import my.noveldokusha.text_to_speech.VoiceData

@Stable
internal data class TextToSpeechSettingData(
    val isPlaying: MutableState<Boolean>,
    val isLoadingChapter: MutableState<Boolean>,
    val activeVoice: State<VoiceData?>,
    val voiceSpeed: State<Float>,
    val voicePitch: State<Float>,
    val availableVoices: SnapshotStateList<VoiceData>,
    val currentActiveItemState: State<TextSynthesis>,
    val isThereActiveItem: State<Boolean>,
    val customSavedVoices: State<List<VoicePredefineState>>,
    val setCustomSavedVoices: (List<VoicePredefineState>) -> Unit,
    val setPlaying: (Boolean) -> Unit,
    val playFirstVisibleItem: () -> Unit,
    val playPreviousItem: () -> Unit,
    val playPreviousChapter: () -> Unit,
    val playNextItem: () -> Unit,
    val playNextChapter: () -> Unit,
    val scrollToActiveItem: () -> Unit,
    val setVoiceId: (voiceId: String) -> Unit,
    val setVoiceSpeed: (Float) -> Unit,
    val setVoicePitch: (Float) -> Unit,
    val chapterWordCount: State<Int>,
    val remainingWordCount: State<Int>,
    val estimatedWpm: State<Int>,
    val estimatedTotalSeconds: State<Int>,
    val estimatedRemainingSeconds: State<Int>,
    val currentParagraphText: State<String>,
    val alternateParagraphText: State<String>,
    val parallelEnabled: State<Boolean>,
    val originalVoiceId: State<String>,
    val setOriginalVoiceId: (String) -> Unit,
    val spokenWordRange: State<IntRange?>,
    val ttsElapsedSeconds: State<Int>,
    val ttsTotalSeconds: State<Int>,
    val ttsSeekEnabled: State<Boolean>,
    val onSeekToPosition: (Float) -> Unit,
)

internal data class TextSynthesis(
    val itemPos: ReaderItem.Position,
    override val playState: Utterance.PlayState
) : Utterance<TextSynthesis> {
    override val utteranceId = "${itemPos.chapterItemPosition}-${itemPos.chapterIndex}"
    override fun copyWithState(playState: Utterance.PlayState) = copy(playState = playState)
}

internal class ReaderTextToSpeech(
    private val coroutineScope: CoroutineScope,
    private val context: Context,
    private val items: List<ReaderItem>,
    private val chapterLoadedFlow: Flow<ChapterLoaded>,
    customSavedVoices: State<List<VoicePredefineState>>,
    setCustomSavedVoices: (List<VoicePredefineState>) -> Unit,
    private val isChapterIndexValid: (chapterIndex: Int) -> Boolean,
    private val isChapterIndexLoaded: (chapterIndex: Int) -> Boolean,
    private val tryLoadPreviousChapter: () -> Unit,
    private val loadNextChapter: () -> Unit,
    private val getPreferredVoiceId: () -> String,
    private val setPreferredVoiceId: (voiceId: String) -> Unit,
    private val getPreferredVoiceEngine: () -> String,
    private val setPreferredVoiceEngine: (enginePackage: String) -> Unit,
    private val getPreferredVoicePitch: () -> Float,
    private val setPreferredVoicePitch: (voiceId: Float) -> Unit,
    private val getPreferredVoiceSpeed: () -> Float,
    private val setPreferredVoiceSpeed: (voiceId: Float) -> Unit,
    private val getPreferredVoiceIdForOriginal: () -> String,
    private val setPreferredVoiceIdForOriginal: (voiceId: String) -> Unit,
    private val onBufferLow: (() -> Unit)? = null,
    private val getParallelEnabled: () -> Boolean,
    private val getParallelOrder: () -> String,
) {
    companion object {
        @Volatile
        var pausedBySystem: Boolean = false

        @Volatile
        var isSystemPauseTrigger: Boolean = false

        @Volatile
        var userPaused: Boolean = false
    }

    private val DECORATIVE_CHARS = """\-=*_~+#·•°─-┿"""
    private val SEPARATOR_ONLY = Regex("""^\s*[$DECORATIVE_CHARS]{3,}\s*$""")
    private val LEADING_DECORATIVE = Regex("""^[$DECORATIVE_CHARS]{3,}\s*""")
    private val TRAILING_DECORATIVE = Regex("""\s*[$DECORATIVE_CHARS]{3,}$""")

    private val halfBuffer = 5
    private val _originalVoiceId = mutableStateOf(getPreferredVoiceIdForOriginal())
    private var updateJob: Job? = null
    private val manager = TextToSpeechManager(
        context = context,
        appTtsEngine = AppTtsEngine.getInstance(context),
        initialItemState = TextSynthesis(
            itemPos = ReaderItem.Title(
                chapterUrl = "",
                chapterIndex = -1,
                chapterItemPosition = 0,
                text = ""
            ),
            playState = Utterance.PlayState.FINISHED
        )
    )

    val scrolledToTheTop = MutableSharedFlow<Unit>()
    val scrolledToTheBottom = MutableSharedFlow<Unit>()
    val currentReaderItem = manager.currentTextSpeakFlow
    val currentTextPlaying = manager.currentActiveItemState as State<TextSynthesis>
    val reachedChapterEndFlowChapterIndex = MutableSharedFlow<ChapterIndex>()
    val startReadingFromFirstVisibleItem = MutableSharedFlow<Unit>()
    val scrollToReaderItem = MutableSharedFlow<ReaderItem>()
    val scrollToChapterTop = MutableSharedFlow<ChapterIndex>()

    // Initial speech rate in "duration units" per second (words/pauses), before the
    // live calibration locks in a measured rate. ~3.0 matches typical English TTS
    // (≈150 wpm) including punctuation pauses; recalibrated after the first chapters.
    private val baseCharactersPerSecond = mutableStateOf(3.0f)

    val chapterWordCount = derivedStateOf {
        val currentChapterIndex = currentTextPlaying.value.itemPos.chapterIndex
        if (isChapterIndexValid(currentChapterIndex)) {
            items.filterIsInstance<ReaderItem.Text>()
                .filter { it.chapterIndex == currentChapterIndex }
                .sumOf { it.textToDisplay.wordCount() }
        } else 0
    }

    val remainingWordCount = derivedStateOf {
        val currentChapterIndex = currentTextPlaying.value.itemPos.chapterIndex
        val currentItemPos = currentTextPlaying.value.itemPos.chapterItemPosition
        if (isChapterIndexValid(currentChapterIndex)) {
            items.filterIsInstance<ReaderItem.Text>()
                .filter { it.chapterIndex == currentChapterIndex && it.chapterItemPosition >= currentItemPos }
                .sumOf { it.textToDisplay.wordCount() }
        } else 0
    }

    val chapterCharacterCount = derivedStateOf {
        val currentChapterIndex = currentTextPlaying.value.itemPos.chapterIndex
        if (isChapterIndexValid(currentChapterIndex)) {
            items.filterIsInstance<ReaderItem.Text>()
                .filter { it.chapterIndex == currentChapterIndex }
                .sumOf { it.textToDisplay.length }
        } else 0
    }

    val remainingCharacterCount = derivedStateOf {
        val currentChapterIndex = currentTextPlaying.value.itemPos.chapterIndex
        val currentItemPos = currentTextPlaying.value.itemPos.chapterItemPosition
        if (isChapterIndexValid(currentChapterIndex)) {
            items.filterIsInstance<ReaderItem.Text>()
                .filter { it.chapterIndex == currentChapterIndex && it.chapterItemPosition >= currentItemPos }
                .sumOf { it.textToDisplay.length }
        } else 0
    }

    val estimatedWpm = derivedStateOf {
        val currentSpeed = manager.voiceSpeed.floatValue
        (baseCharactersPerSecond.value * currentSpeed * 12.0f).toInt().coerceAtLeast(30)
    }

    val estimatedTotalSeconds = derivedStateOf {
        val currentSpeed = manager.voiceSpeed.floatValue
        val cps = baseCharactersPerSecond.value * currentSpeed
        if (cps > 0f) (chapterCharacterCount.value / cps).toInt() else 0
    }

    val estimatedRemainingSeconds = derivedStateOf {
        val currentSpeed = manager.voiceSpeed.floatValue
        val cps = baseCharactersPerSecond.value * currentSpeed
        if (cps > 0f) (remainingCharacterCount.value / cps).toInt() else 0
    }

    val currentParagraphText = derivedStateOf {
        val itemPos = manager.currentActiveItemState.value.itemPos
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = itemPos.chapterIndex,
            chapterItemPosition = itemPos.chapterItemPosition,
        )
        val item = items.getOrNull(itemIndex)
        when (item) {
            is ReaderItem.Text -> ttsText(item)
            else -> ""
        }
    }

    val alternateParagraphText = derivedStateOf {
        val itemPos = manager.currentActiveItemState.value.itemPos
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = itemPos.chapterIndex,
            chapterItemPosition = itemPos.chapterItemPosition,
        )
        val item = items.getOrNull(itemIndex)
        when {
            item is ReaderItem.Text && getParallelEnabled() && item.textTranslated != null -> {
                if (getParallelOrder() == "ORIGINAL_FIRST") item.textToDisplay
                else item.text
            }
            else -> ""
        }
    }

    val parallelEnabled = derivedStateOf { getParallelEnabled() }

    // ===== Duration / seek system =====
    // Locked-at-start total duration estimate (seconds). Recomputed ONLY when the
    // chapter (re)starts or the voice speed changes — never while playing.
    private val _ttsTotalSeconds = mutableStateOf(0)
    // Live count-up elapsed timer (seconds). Advances only while actively playing.
    private val _ttsElapsedSeconds = mutableStateOf(0)
    // Whether seeking is currently allowed (chapter has been started & has a duration).
    private val _ttsSeekEnabled = mutableStateOf(false)

    // Pre-computed paragraph start times (ms, relative to chapter start) and the
    // cleaned character count per paragraph for the active chapter.
    private data class ParagraphTiming(
        val itemPos: ReaderItem.Position,
        val startMs: Long,
        val cleanedCharCount: Int,
    )
    private var activeTimings: List<ParagraphTiming> = emptyList()
    private var activeCps: Float = 13.0f
    private var timingsChapterIndex: Int = -1
    // Wall-clock anchor used to drive the elapsed timer tick.
    private var elapsedAnchorMs: Long = 0L
    private var elapsedAnchorSeconds: Int = 0

    // Base pause between paragraphs at 1.0x TTS speed; scaled by current speed.
    private val BASE_PARAGRAPH_PAUSE_MS = 400.0

    val ttsTotalSeconds: State<Int> = _ttsTotalSeconds
    val ttsElapsedSeconds: State<Int> = _ttsElapsedSeconds
    val ttsSeekEnabled: State<Boolean> = _ttsSeekEnabled

    // Invisible Unicode characters: silent, contribute no speech duration.
    private val INVISIBLE_CHARS = setOf(
        '\u200B', // zero width space
        '\u200C', // zero width non-joiner
        '\u200D', // zero width joiner
        '\u2060', // word joiner
        '\u2063', // invisible separator
        '\uFEFF', // zero width no-break space / BOM
        '\u00AD', // soft hyphen
    )

    // Symbols Android Google TTS reads aloud individually (independent of position).
    private val SPOKEN_SYMBOLS = setOf(
        '~', // tilde
        '*', // asterisk
        '+', // plus
        '\u2212', // minus (Unicode minus sign)
    )

    // Normal sentence punctuation that is NOT spoken (only a pause). Excludes the
    // spoken symbols above and the leading-run symbols handled separately.
    private val SILENT_PUNCTUATION = setOf(
        ',', '.', '?', '!', ':', ';',
        '(', ')', '[', ']', '{', '}',
        '"', '\'', '`',
        '\u2018', '\u2019', '\u201C', '\u201D', // ‘ ’ “ ”
        '\u2026', // ellipsis …
        '-', // ASCII hyphen (word-word, E-)
        '\u2010', // non-breaking hyphen
        '\u2013', // en dash
        '\u2014', // em dash — (attached to words => 0 units)
        '=', '_', '#', '@', '%', '&', '/', '\\', '|', '<', '>', '^',
    )

    /**
     * Speech "units" Android Google TTS actually utters, following its parsing rules:
     *  1. start from the exact final text sent to TTS;
     *  2. strip invisible Unicode (U+2060 U+2063 U+200B U+200C U+200D);
     *  3. a leading run of punctuation/symbols (before first letter/digit) is read
     *     aloud symbol-by-symbol, each counting as one unit;
     *  4. inside normal text, punctuation counts as 0 unless it is a spoken symbol;
     *  5. a normal word = 1 unit, a number chunk = 1 unit;
     *  6. ~ * + − count as 1 unit (between letters/numbers too); ASCII hyphen - and
     *     em dash — attached to words count as 0.
     */
    private fun spokenUnitCount(text: String): Int {
        val cleaned = buildString {
            for (ch in text) if (ch !in INVISIBLE_CHARS) append(ch)
        }
        if (cleaned.isEmpty()) return 0

        // Split into whitespace-delimited tokens (words / number chunks / symbol runs).
        val tokens = cleaned.split(WHITESPACE).filter { it.isNotEmpty() }

        var count = 0
        var firstContentTokenSeen = false
        for (token in tokens) {
            if (!firstContentTokenSeen) {
                // Leading run: every symbol is spoken individually.
                var allPunct = true
                for (ch in token) {
                    if (ch.isLetterOrDigit()) {
                        allPunct = false
                        break
                    }
                }
                if (allPunct) {
                    for (ch in token) count++
                    continue
                }
                firstContentTokenSeen = true
            }
            // Regular token.
            count += countToken(token)
        }
        return count
    }

    private fun countToken(token: String): Int {
        // A token made entirely of digits => one number chunk.
        if (token.all { it.isDigit() }) return 1
        // Count letters and spoken symbols; everything else in the token is 0.
        var units = 0
        for (ch in token) {
            when {
                ch.isLetter() -> units++
                ch in SPOKEN_SYMBOLS -> units++
                // ASCII hyphen / em dash / silent punctuation attached => 0.
                else -> Unit
            }
        }
        return if (units > 0) units else 0
    }

    /**
     * Extra "units" contributed by the pauses TTS inserts at punctuation. Punctuation
     * is not spoken (0 units), but it DOES consume real time; folding the pause into the
     * same unit basis keeps the live CPS calibration consistent with the estimate and
     * prevents the timer from finishing before the audio.
     */
    private fun punctuationPauseUnits(text: String): Double {
        var units = 0.0
        for (ch in text) {
            when (ch) {
                '.', '!', '?' -> units += 0.45
                ',', ';', ':' -> units += 0.18
                '\u2026' -> units += 0.35 // …
                '\u2014', '\u2013' -> units += 0.25 // — –
                '"', '\'', '\u2018', '\u2019', '\u201C', '\u201D' -> units += 0.1
                else -> Unit
            }
        }
        return units
    }

    /** Total duration units for a paragraph: spoken content + punctuation pauses. */
    private fun durationUnits(text: String): Double {
        return spokenUnitCount(text).toDouble() + punctuationPauseUnits(text)
    }

    private fun recomputeChapterTimings(chapterIndex: Int) {
        val speed = manager.voiceSpeed.floatValue
        val cps = (baseCharactersPerSecond.value * speed).coerceAtLeast(0.1f)
        activeCps = cps
        val paragraphs = items
            .filterIsInstance<ReaderItem.Text>()
            .filter { it !is ReaderItem.Title } // chapter titles are not spoken body
            .filter { it.chapterIndex == chapterIndex }
            .filter { !isOnlyDecorators(ttsText(it)) }
        if (paragraphs.isEmpty()) {
            activeTimings = emptyList()
            _ttsTotalSeconds.value = 0
            _ttsSeekEnabled.value = false
            return
        }
        val timings = ArrayList<ParagraphTiming>(paragraphs.size)
        var accMs = 0L
        paragraphs.forEachIndexed { index, item ->
            val units = durationUnits(ttsText(item))
            timings.add(
                ParagraphTiming(
                    itemPos = item,
                    startMs = accMs,
                    cleanedCharCount = units.toInt(),
                )
            )
            val durationMs = if (units > 0.0) (units * 1000.0 / cps).toLong() else 0L
            accMs += durationMs
            // Pause between paragraphs scales with TTS speed (≈400ms at 1.0x).
            if (index < paragraphs.lastIndex) {
                val paragraphPauseMs = (BASE_PARAGRAPH_PAUSE_MS / speed).toLong().coerceAtLeast(0L)
                accMs += paragraphPauseMs
            }
        }
        activeTimings = timings
        _ttsTotalSeconds.value = (accMs / 1000).toInt().coerceAtLeast(0)
        _ttsSeekEnabled.value = true
    }

    private fun startElapsedTimer() {
        elapsedAnchorMs = System.currentTimeMillis()
        elapsedAnchorSeconds = _ttsElapsedSeconds.value
        if (elapsedTickJob?.isActive != true) {
            elapsedTickJob = coroutineScope.launch {
                while (true) {
                    delay(250)
                    if (!state.isPlaying.value) continue
                    val total = _ttsTotalSeconds.value
                    if (total <= 0) continue
                    val deltaSec =
                        ((System.currentTimeMillis() - elapsedAnchorMs) / 1000).toInt()
                    val newElapsed = (elapsedAnchorSeconds + deltaSec).coerceAtMost(total)
                    _ttsElapsedSeconds.value = newElapsed
                    if (newElapsed >= total) break
                }
            }
        }
    }

    private var elapsedTickJob: Job? = null

    private fun pauseElapsedTimer() {
        // Freeze the elapsed counter at its current value.
        _ttsElapsedSeconds.value = _ttsElapsedSeconds.value.coerceAtMost(_ttsTotalSeconds.value)
    }

    private fun resetElapsedTimer() {
        elapsedTickJob?.cancel()
        elapsedTickJob = null
        _ttsElapsedSeconds.value = 0
    }

    /**
     * When playback starts at a paragraph other than the chapter's first one (e.g. the
     * "Start Here" action), the elapsed counter must begin at that paragraph's start time
     * rather than 0, otherwise the progress bar shows 0:00 of the full chapter while the
     * audio is already partway through. Looks up the paragraph's startMs in [activeTimings].
     */
    private fun syncElapsedToActiveParagraph() {
        if (activeTimings.isEmpty()) return
        val itemPos = state.currentActiveItemState.value.itemPos
        val match = activeTimings.firstOrNull { it.itemPos == itemPos }
            ?: activeTimings.firstOrNull { it.itemPos.chapterIndex == itemPos.chapterIndex }
        val startMs = match?.startMs ?: 0L
        _ttsElapsedSeconds.value = (startMs / 1000).toInt()
        // Re-anchor the wall-clock timer so the counter continues from this position.
        elapsedAnchorMs = System.currentTimeMillis()
        elapsedAnchorSeconds = _ttsElapsedSeconds.value
    }

    /**
     * Seek to a fraction (0f..1f) of the chapter duration. Finds the target paragraph
     * via binary search over pre-computed start times, scrolls the reader to it, and
     * sets a spoken-word-range hint so the correct word position is shown visually.
     */
    private fun seekToFraction(fraction: Float) {
        val total = _ttsTotalSeconds.value
        if (total <= 0 || activeTimings.isEmpty()) return
        val targetMs = (fraction.coerceIn(0f, 1f) * total * 1000L).toLong()
        seekToMs(targetMs)
    }

    private fun seekToMs(targetMs: Long) {
        if (activeTimings.isEmpty()) return
        // Binary search for the last paragraph whose startMs <= targetMs.
        var lo = 0
        var hi = activeTimings.lastIndex
        var target = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (activeTimings[mid].startMs <= targetMs) {
                target = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val timing = activeTimings[target]
        val nextStart = activeTimings.getOrNull(target + 1)?.startMs ?: (_ttsTotalSeconds.value * 1000L)
        val paragraphElapsedMs = (targetMs - timing.startMs).coerceAtLeast(0L)
        val paragraphDurationMs = (nextStart - timing.startMs).coerceAtLeast(1L)
        // Approximate character offset within the paragraph for visual highlight.
        val charOffset = if (timing.cleanedCharCount > 0) {
            ((paragraphElapsedMs.toFloat() / paragraphDurationMs) * timing.cleanedCharCount)
                .toInt()
                .coerceIn(0, timing.cleanedCharCount)
        } else 0

        // Update the elapsed counter to reflect the seek position.
        _ttsElapsedSeconds.value = (targetMs / 1000).toInt().coerceAtMost(_ttsTotalSeconds.value)

        // Scroll reader to show the target paragraph in view.
        coroutineScope.launch {
            scrollToReaderItem.emit(timing.itemPos)
        }

        // Set a spoken-word-range hint so the existing highlight shows the position.
        // TTS still starts from the paragraph beginning (paragraph granularity).
        val displayText = ttsText(timing.itemPos as? ReaderItem.Text ?: return)
        if (displayText.isNotEmpty() && charOffset < displayText.length) {
            manager.spokenWordRange.value = charOffset until (charOffset + 1)
        }

        // Restart TTS playback from the target paragraph.
        lifecycleLock.lock()
        try {
            stop()
            start()
            coroutineScope.launch {
                val itemIndex = indexOfReaderItem(
                    list = items,
                    chapterIndex = timing.itemPos.chapterIndex,
                    chapterItemPosition = timing.itemPos.chapterItemPosition,
                )
                if (itemIndex != -1) {
                    readChapterStartingFromItemIndex(
                        itemIndex = itemIndex,
                        chapterIndex = timing.itemPos.chapterIndex,
                    )
                }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    val onSeekToPosition: (Float) -> Unit = { fraction -> seekToFraction(fraction) }

    /** Seek to an absolute position (ms) — used by the lockscreen/notification scrubber. */
    fun seekToPositionMs(positionMs: Long) {
        val total = _ttsTotalSeconds.value
        if (total <= 0) return
        seekToFraction((positionMs.coerceAtLeast(0L) / (total * 1000L).toFloat()).coerceIn(0f, 1f))
    }

    val state = TextToSpeechSettingData(
        isPlaying = mutableStateOf(false),
        isLoadingChapter = mutableStateOf(false),
        activeVoice = manager.activeVoice as State<VoiceData?>,
        availableVoices = manager.availableVoices,
        currentActiveItemState = manager.currentActiveItemState,
        isThereActiveItem = derivedStateOf {
            isChapterIndexValid(manager.currentActiveItemState.value.itemPos.chapterIndex)
        },
        voicePitch = manager.voicePitch,
        voiceSpeed = manager.voiceSpeed,
        customSavedVoices = customSavedVoices,
        setCustomSavedVoices = setCustomSavedVoices,
        setVoiceId = ::setVoice,
        playFirstVisibleItem = ::playFirstVisibleItem,
        playNextChapter = ::playNextChapter,
        playPreviousChapter = ::playPreviousChapter,
        playNextItem = ::playNextItem,
        playPreviousItem = ::playPreviousItem,
        setPlaying = ::setPlaying,
        scrollToActiveItem = ::scrollToActiveItem,
        setVoicePitch = ::setVoicePitch,
        setVoiceSpeed = ::setVoiceSpeed,
        chapterWordCount = chapterWordCount,
        remainingWordCount = remainingWordCount,
        estimatedWpm = estimatedWpm,
        estimatedTotalSeconds = estimatedTotalSeconds,
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        currentParagraphText = currentParagraphText,
        alternateParagraphText = alternateParagraphText,
        parallelEnabled = parallelEnabled,
        originalVoiceId = _originalVoiceId,
        setOriginalVoiceId = { voiceId ->
            setPreferredVoiceIdForOriginal(voiceId)
            _originalVoiceId.value = voiceId
            onOriginalVoiceChanged()
        },
        spokenWordRange = manager.spokenWordRange,
        ttsElapsedSeconds = ttsElapsedSeconds,
        ttsTotalSeconds = ttsTotalSeconds,
        ttsSeekEnabled = ttsSeekEnabled,
        onSeekToPosition = onSeekToPosition,
    )

    val isActive = derivedStateOf { state.isThereActiveItem.value || state.isPlaying.value }
    val isSpeaking = derivedStateOf { state.isThereActiveItem.value && state.isPlaying.value }

    init {
        coroutineScope.launch {
            // Ждём пока все голоса собраны (один раз при старте)
            manager.serviceLoadedFlow.take(1).collect {
                manager.trySetVoicePitch(getPreferredVoicePitch())
                manager.trySetVoiceSpeed(getPreferredVoiceSpeed())

                val preferredVoiceId = getPreferredVoiceId()
                val preferredEngine = getPreferredVoiceEngine()
                val defaultEngine = manager.service.defaultEngine ?: ""

                // Ищем голос в availableVoices — там все движки с правильным enginePackage
                val voiceData = manager.availableVoices.find { it.id == preferredVoiceId }
                val targetEngine = voiceData?.enginePackage ?: preferredEngine

                if (targetEngine.isNotEmpty() && targetEngine != defaultEngine) {
                    // Голос из другого движка — переключаем service для воспроизведения
                    manager.reinitWithEngine(
                        enginePackage = targetEngine,
                        voiceId = preferredVoiceId
                    )
                } else {
                    manager.trySetVoiceById(preferredVoiceId)
                }
            }
        }

        manager.init()

        // Калибровка скорости чтения в реальном времени
        coroutineScope.launch {
            val paragraphStartTimes = mutableMapOf<String, Long>()
            manager.currentTextSpeakFlow.collect { utterance ->
                val utteranceId = utterance.utteranceId
                when (utterance.playState) {
                    Utterance.PlayState.PLAYING -> {
                        paragraphStartTimes[utteranceId] = System.currentTimeMillis()
                        // When a new chapter starts playing, lock in its timings.
                        val ci = utterance.itemPos.chapterIndex
                        if (isChapterIndexValid(ci) && ci != timingsChapterIndex && state.isPlaying.value) {
                            recomputeChapterTimings(ci)
                            timingsChapterIndex = ci
                            _ttsElapsedSeconds.value = 0
                            startElapsedTimer()
                        }
                    }
                    Utterance.PlayState.FINISHED -> {
                        val startTime = paragraphStartTimes.remove(utteranceId)
                        if (startTime != null) {
                            val durationMs = System.currentTimeMillis() - startTime
                            val currentChapterIndex = utterance.itemPos.chapterIndex
                            val currentItemPos = utterance.itemPos.chapterItemPosition

                            val itemIndex = indexOfReaderItem(
                                list = items,
                                chapterIndex = currentChapterIndex,
                                chapterItemPosition = currentItemPos,
                            )
                            val item = items.getOrNull(itemIndex) as? ReaderItem.Text
                            val charCount = if (item != null) durationUnits(ttsText(item)) else 0.0

                            if (charCount > 10.0 && durationMs > 200) {
                                val measuredCps = (charCount * 1000.0f) / durationMs
                                val currentSpeed = manager.voiceSpeed.floatValue
                                if (currentSpeed > 0f) {
                                    val baseCps = measuredCps / currentSpeed
                                    if (baseCps in 3.0f..40.0f) {
                                        baseCharactersPerSecond.value = (0.2f * baseCps + 0.8f * baseCharactersPerSecond.value).toFloat()
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun switchVoiceForMode() {
        if (!getParallelEnabled()) return
        val targetVoiceId = when (getParallelOrder()) {
            "ORIGINAL_FIRST" -> getPreferredVoiceIdForOriginal()
            else -> getPreferredVoiceId()
        }
        if (targetVoiceId.isNotBlank() && targetVoiceId != manager.activeVoice.value?.id) {
            manager.trySetVoiceById(targetVoiceId)
            setPreferredVoiceId(targetVoiceId)
            val voiceData = manager.availableVoices.find { it.id == targetVoiceId }
            if (voiceData != null) setPreferredVoiceEngine(voiceData.enginePackage)
        }
    }

    fun onParallelModeOrderChanged() {
        if (state.isPlaying.value) {
            switchVoiceForMode()
            resumeFromCurrentState()
        }
    }

    private fun onOriginalVoiceChanged() {
        if (state.isPlaying.value && getParallelEnabled() && getParallelOrder() == "ORIGINAL_FIRST") {
            switchVoiceForMode()
            resumeFromCurrentState()
        }
    }

    @Volatile
    private var activeClaimTrack: AudioTrack? = null

    private val lifecycleLock = java.util.concurrent.locks.ReentrantLock()

    private fun claimMediaSession() {
        try {
            // Освобождаем предыдущий трек, если он ещё не был релизнут
            // (защита от утечки при множественных вызовах start())
            activeClaimTrack?.let { runCatching { it.release() } }
            activeClaimTrack = null

            val sampleRate = 44100
            val durationSec = 0.1
            val bufferSize = (sampleRate * durationSec).toInt()
            val audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            activeClaimTrack = audioTrack
            val silence = ShortArray(bufferSize)
            audioTrack.write(silence, 0, silence.size)
            audioTrack.play()
            // NonCancellable гарантирует, что release() выполнится даже при отмене coroutineScope
            coroutineScope.launch(NonCancellable) {
                try {
                    delay(200)
                } finally {
                    runCatching { audioTrack.stop() }
                    runCatching { audioTrack.release() }
                    if (activeClaimTrack === audioTrack) activeClaimTrack = null
                }
            }
            Timber.v("claimMediaSession OK")
        } catch (e: Exception) {
            Timber.w(e, "claimMediaSession failed")
        }
    }

    fun start() {
        lifecycleLock.lock()
        try {
            Timber.d("start()")
            claimMediaSession()
            NarratorMediaControlsService.reacquireFocus()
            switchVoiceForMode()
            state.isPlaying.value = true
            // Lock in the total duration for the current chapter (recompute only if
            // we have not already done so for this chapter index or after a speed change).
            val chapterIndex = state.currentActiveItemState.value.itemPos.chapterIndex
            if (isChapterIndexValid(chapterIndex) && chapterIndex != timingsChapterIndex) {
                recomputeChapterTimings(chapterIndex)
                timingsChapterIndex = chapterIndex
            }
            startElapsedTimer()
            updateJob?.cancel()
            updateJob = coroutineScope.launch {
                manager
                    .currentTextSpeakFlow
                    .filter { it.playState == Utterance.PlayState.FINISHED }
                    .collect {
                        Timber.d("collect FINISHED queueSize=${manager.queueList.size}")
                        withContext(Dispatchers.Main) {
                            when (manager.queueList.size) {
                                halfBuffer -> {
                                    val lastUtterance = manager
                                        .queueList
                                        .asSequence()
                                        .last().value
                                    readChapterNextChunk(
                                        chapterIndex = lastUtterance.itemPos.chapterIndex,
                                        chapterItemPosition = lastUtterance.itemPos.chapterItemPosition,
                                        quantity = halfBuffer
                                    )
                                    onBufferLow?.invoke()
                                }
                                0 -> {
                                    launch {
                                        reachedChapterEndFlowChapterIndex.emit(it.itemPos.chapterIndex)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    fun stop() {
        lifecycleLock.lock()
        try {
            Timber.d("stop()")
            state.isPlaying.value = false
            pauseElapsedTimer()
            updateJob?.cancel()
            manager.stop()
        } finally {
            lifecycleLock.unlock()
        }
    }

    fun shutdownTts() {
        runCatching { manager.shutdown() }
    }

    fun forceResetState(itemPos: ReaderItem.Position?) {
        if (itemPos == null) return
        state.isPlaying.value = false
        manager.setCurrentSpeakState(
            TextSynthesis(itemPos, Utterance.PlayState.FINISHED)
        )
    }

    suspend fun readChapterStartingFromStart(
        chapterIndex: Int,
        syncElapsedToStart: Boolean = false,
    ) = withContext(Dispatchers.Main.immediate) {
        readChapterStartingFromChapterItemPosition(
            chapterIndex = chapterIndex,
            chapterItemPosition = 0,
            syncElapsedToStart = syncElapsedToStart
        )
    }

    private suspend fun readChapterStartingFromChapterItemPosition(
        chapterIndex: Int,
        chapterItemPosition: Int,
        syncElapsedToStart: Boolean = false,
    ) = withContext(Dispatchers.Main.immediate) {
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        if (itemIndex == -1) {
            reachedChapterEndFlowChapterIndex.emit(chapterIndex)
            return@withContext
        }
        readChapterStartingFromItemIndex(
            itemIndex = itemIndex,
            chapterIndex = chapterIndex,
            syncElapsedToStart = syncElapsedToStart
        )
    }

    suspend fun readChapterStartingFromItemIndex(
        itemIndex: Int,
        chapterIndex: Int,
        syncElapsedToStart: Boolean = false,
    ) = withContext(Dispatchers.Main.immediate) {
        val nextItems = getChapterNextItems(
            itemIndex = itemIndex,
            chapterIndex = chapterIndex,
            quantity = halfBuffer * 2
        )

        if (nextItems.isEmpty()) {
            reachedChapterEndFlowChapterIndex.emit(chapterIndex)
            return@withContext
        }

        val firstItem = nextItems.first()
        manager.setCurrentSpeakState(
            TextSynthesis(
                itemPos = firstItem,
                playState = Utterance.PlayState.LOADING
            )
        )

        if (syncElapsedToStart) syncElapsedToActiveParagraph()

        nextItems.forEach(::speakItem)
    }

    private fun scrollToActiveItem() {
        lifecycleLock.lock()
        try {
            coroutineScope.launch {
                val currentItemPos = state.currentActiveItemState.value.itemPos
                val itemIndex = indexOfReaderItem(
                    list = items,
                    chapterIndex = currentItemPos.chapterIndex,
                    chapterItemPosition = currentItemPos.chapterItemPosition,
                )
                val item = items.getOrNull(itemIndex) ?: return@launch
                scrollToReaderItem.emit(item)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    fun scrollToCurrentSpeakingItem() {
        lifecycleLock.lock()
        try {
            coroutineScope.launch {
                val currentItemPos = currentTextPlaying.value.itemPos
                val itemIndex = indexOfReaderItem(
                    list = items,
                    chapterIndex = currentItemPos.chapterIndex,
                    chapterItemPosition = currentItemPos.chapterItemPosition,
                )
                val item = items.getOrNull(itemIndex) ?: return@launch
                scrollToReaderItem.emit(item)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    /**
     * Returns the actual current playing position directly from the TTS manager.
     * Unlike currentTextPlaying.value.itemPos which may be stale when app is backgrounded,
     * this always returns the up-to-date position from the underlying utterance state.
     *
     * Returns null if there is no actively playing/loading item (i.e. TTS is
     * between chapters or has finished all content). Callers should fall back
     * to waiting for the next currentReaderItem emission.
     */
    fun getActualPlayingPosition(): ReaderItem.Position? {
        // All items in the queue are non-finished — the first one is the current
        val playingItem = manager.queueList.values
            .firstOrNull { it.playState == Utterance.PlayState.PLAYING }
        if (playingItem != null) {
            return playingItem.itemPos
        }

        // Queue is empty — check if currentActiveItemState is still active
        val currentState = currentTextPlaying.value
        if (currentState.playState != Utterance.PlayState.FINISHED) {
            return currentState.itemPos
        }

        // Queue empty and state is FINISHED: TTS is between items/chapters.
        // Don't return a stale position — let the caller wait for the next emission.
        return null
    }

    /**
     * Forces the currentActiveItemState to reflect the actual playing position
     * from the TTS queue. This ensures that after screen unlock, the LiveData
     * observer receives the up-to-date position instead of a stale one.
     */
    fun forceUpdateCurrentItemState() {
        val playingItem = manager.queueList.values
            .firstOrNull { it.playState == Utterance.PlayState.PLAYING }
            ?: manager.queueList.values
                .firstOrNull { it.playState == Utterance.PlayState.LOADING }
        if (playingItem != null) {
            manager.setCurrentSpeakState(playingItem)
        }
    }

    private fun playFirstVisibleItem() {
        lifecycleLock.lock()
        try {
            stop()
            start()
            coroutineScope.launch {
                startReadingFromFirstVisibleItem.emit(Unit)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun setPlaying(playing: Boolean) {
        lifecycleLock.lock()
        try {
            if (!playing) {
                if (!ReaderTextToSpeech.isSystemPauseTrigger) {
                    ReaderTextToSpeech.pausedBySystem = false
                    ReaderTextToSpeech.userPaused = true
                }
                stop()
                return
            }
            ReaderTextToSpeech.pausedBySystem = false
            ReaderTextToSpeech.userPaused = false
            start()
            val state = state.currentActiveItemState.value

                if (isChapterIndexValid(state.itemPos.chapterIndex)) {
                coroutineScope.launch {
                    readChapterStartingFromChapterItemPosition(
                        chapterIndex = state.itemPos.chapterIndex,
                        chapterItemPosition = state.itemPos.chapterItemPosition,
                        syncElapsedToStart = true
                    )
                }
            } else {
                coroutineScope.launch {
                    startReadingFromFirstVisibleItem.emit(Unit)
                }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun playNextItem() {
        lifecycleLock.lock()
        try {
            if (!state.isThereActiveItem.value) return

            coroutineScope.launch {
                val currentItemPos = state.currentActiveItemState.value.itemPos
                val itemIndex = indexOfReaderItem(
                    list = items,
                    chapterIndex = currentItemPos.chapterIndex,
                    chapterItemPosition = currentItemPos.chapterItemPosition,
                )
                if (itemIndex <= -1 || itemIndex >= items.lastIndex) return@launch
                // Jump to the next spoken body paragraph (titles are not in the timings).
                val nextItemRelativeIndex = items
                    .subList(itemIndex + 1, items.size)
                    .indexOfFirst { it is ReaderItem.Body }
                if (nextItemRelativeIndex == -1) return@launch
                val nextItemIndex = itemIndex + 1 + nextItemRelativeIndex
                val nextItem = items.getOrNull(nextItemIndex) as? ReaderItem.Body ?: return@launch
                stop()
                start()
                readChapterStartingFromItemIndex(
                    itemIndex = nextItemIndex,
                    chapterIndex = nextItem.chapterIndex,
                    syncElapsedToStart = true
                )
                scrollToReaderItem.emit(nextItem)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun playPreviousItem() {
        lifecycleLock.lock()
        try {
            if (!state.isThereActiveItem.value) return

            coroutineScope.launch {
                val currentItemPos = state.currentActiveItemState.value.itemPos
                val itemIndex = indexOfReaderItem(
                    list = items,
                    chapterIndex = currentItemPos.chapterIndex,
                    chapterItemPosition = currentItemPos.chapterItemPosition,
                )
                if (itemIndex <= 0) return@launch
                // Jump to the previous spoken body paragraph (titles are not in the timings).
                val previousItemRelativeIndex = items
                    .subList(0, itemIndex)
                    .asReversed()
                    .indexOfFirst { it is ReaderItem.Body }
                if (previousItemRelativeIndex == -1) return@launch
                val previousItemIndex = itemIndex - 1 - previousItemRelativeIndex
                val previousItem = items.getOrNull(previousItemIndex) as? ReaderItem.Body
                    ?: return@launch
                stop()
                start()
                readChapterStartingFromItemIndex(
                    itemIndex = previousItemIndex,
                    chapterIndex = previousItem.chapterIndex,
                    syncElapsedToStart = true
                )
                scrollToReaderItem.emit(previousItem)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun playNextChapter() {
        lifecycleLock.lock()
        try {
            if (!state.isThereActiveItem.value) return

            val currentState = state.currentActiveItemState.value
            val nextChapterIndex = currentState.itemPos.chapterIndex + 1
            stop()
            if (!isChapterIndexValid(nextChapterIndex)) {
                coroutineScope.launch {
                    val item = items.findLast {
                        it is ReaderItem.Position && it.chapterIndex == currentState.itemPos.chapterIndex
                    } as? ReaderItem.Position ?: return@launch

                    manager.currentActiveItemState.value = currentState.copy(
                        playState = Utterance.PlayState.FINISHED,
                        itemPos = item
                    )
                    scrolledToTheBottom.emit(Unit)
                }
                return
            }
            start()
            coroutineScope.launch {
                if (!isChapterIndexLoaded(nextChapterIndex)) {
                    state.isLoadingChapter.value = true
                    loadNextChapter()
                    chapterLoadedFlow
                        .filter { it.chapterIndex == nextChapterIndex }
                        .take(1)
                        .collect()
                    state.isLoadingChapter.value = false
                }
                readChapterStartingFromStart(nextChapterIndex, syncElapsedToStart = true)
                scrollToChapterTop.emit(nextChapterIndex)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun playPreviousChapter() {
        lifecycleLock.lock()
        try {
            if (!state.isThereActiveItem.value) return

            val currentItemState = state.currentActiveItemState.value
            val targetChapterIndex = when (currentItemState.itemPos is ReaderItem.Title) {
                true -> currentItemState.itemPos.chapterIndex - 1
                false -> currentItemState.itemPos.chapterIndex
            }
            stop()
            if (!isChapterIndexValid(targetChapterIndex)) {
                coroutineScope.launch {
                    manager.currentActiveItemState.value = currentItemState.copy(
                        playState = Utterance.PlayState.FINISHED
                    )
                    scrolledToTheTop.emit(Unit)
                }
                return
            }
            start()
            coroutineScope.launch {
                if (!isChapterIndexLoaded(targetChapterIndex)) {
                    state.isLoadingChapter.value = true
                    tryLoadPreviousChapter()
                    chapterLoadedFlow
                        .filter { it.chapterIndex == targetChapterIndex }
                        .take(1)
                        .collect()
                    state.isLoadingChapter.value = false
                }
                readChapterStartingFromStart(targetChapterIndex, syncElapsedToStart = true)
                scrollToChapterTop.emit(targetChapterIndex)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun setVoice(voiceId: String) {
        val voiceData = manager.availableVoices.find { it.id == voiceId }
        // Берём движок из найденного голоса, иначе из текущего service
        val targetEngine = voiceData?.enginePackage ?: (manager.service.defaultEngine ?: "")
        val currentEngine = manager.getCurrentEnginePackage()

        if (targetEngine.isNotEmpty() && targetEngine != currentEngine) {
            // Голос из другого движка — пересоздаём service для воспроизведения
            val wasPlaying = state.isPlaying.value
            stop()
            setPreferredVoiceId(voiceId)
            setPreferredVoiceEngine(targetEngine)
            manager.reinitWithEngine(
                enginePackage = targetEngine,
                voiceId = voiceId,
            )
            if (wasPlaying) {
                coroutineScope.launch {
                    manager.serviceLoadedFlow.take(1).collect()
                    start()
                    val currentState = manager.currentActiveItemState.value
                    if (isChapterIndexValid(currentState.itemPos.chapterIndex)) {
                        readChapterStartingFromChapterItemPosition(
                            chapterIndex = currentState.itemPos.chapterIndex,
                            chapterItemPosition = currentState.itemPos.chapterItemPosition
                        )
                    }
                }
            }
        } else {
            val success = manager.trySetVoiceById(id = voiceId)
            if (success) {
                setPreferredVoiceId(voiceId)
                if (voiceData != null) setPreferredVoiceEngine(voiceData.enginePackage)
                resumeFromCurrentState()
            }
        }
    }

    private fun setVoicePitch(value: Float) {
        Timber.d("setVoicePitch($value)")
        val success = manager.trySetVoicePitch(value)
        if (success) {
            setPreferredVoicePitch(value)
            resumeFromCurrentState()
        }
    }

    private fun setVoiceSpeed(value: Float) {
        Timber.d("setVoiceSpeed($value)")
        val success = manager.trySetVoiceSpeed(value)
        if (success) {
            setPreferredVoiceSpeed(value)
            // Speed change invalidates the cached timings: recompute the locked total
            // for the current chapter at the new rate.
            val chapterIndex = state.currentActiveItemState.value.itemPos.chapterIndex
            if (isChapterIndexValid(chapterIndex)) {
                recomputeChapterTimings(chapterIndex)
                timingsChapterIndex = chapterIndex
            }
            resumeFromCurrentState()
        }
    }

    private fun resumeFromCurrentState() {
        Timber.d("resumeFromCurrentState isPlaying=${state.isPlaying.value}")
        if (!state.isPlaying.value) return
        stop()
        start()
        val currentState = manager.currentActiveItemState.value
        Timber.d("resumeFromCurrentState chapterIndex=${currentState.itemPos.chapterIndex}")
        if (currentState.itemPos.chapterIndex >= 0) {
            coroutineScope.launch {
                readChapterStartingFromChapterItemPosition(
                    chapterIndex = currentState.itemPos.chapterIndex,
                    chapterItemPosition = currentState.itemPos.chapterItemPosition,
                    syncElapsedToStart = true
                )
            }
        }
    }

    private fun readChapterNextChunk(
        chapterIndex: Int,
        chapterItemPosition: Int,
        quantity: Int
    ) {
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        if (itemIndex == -1) return
        val nextItems = getChapterNextItems(
            itemIndex = itemIndex + 1,
            chapterIndex = chapterIndex,
            quantity = quantity
        )
        if (nextItems.isEmpty()) return
        nextItems.forEach(::speakItem)
    }

    private fun getChapterNextItems(
        itemIndex: Int,
        chapterIndex: Int,
        quantity: Int
    ): List<ReaderItem.Position> {
        return items
            .subList(itemIndex.coerceAtMost(items.lastIndex), items.size)
            .asSequence()
            .filter { it is ReaderItem.Title || it is ReaderItem.Body }
            .filterIsInstance<ReaderItem.Position>()
            .takeWhile { it.chapterIndex == chapterIndex }
            .take(quantity)
            .toList()
    }

    private fun isOnlyDecorators(text: String): Boolean {
        if (text.isBlank()) return true
        return text.lines().all { line ->
            line.isBlank() || SEPARATOR_ONLY.matches(line)
        }
    }

    private fun cleanTextForTts(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replace(LEADING_DECORATIVE, "")
                .replace(TRAILING_DECORATIVE, "")
                .trim()
        }
    }

    private fun ttsText(item: ReaderItem.Text): String {
        return when {
            getParallelEnabled() && item.textTranslated != null && getParallelOrder() == "ORIGINAL_FIRST" -> item.text
            else -> item.textToDisplay
        }
    }

    private fun speakItem(item: ReaderItem) {
        when (item) {
            is ReaderItem.Text -> {
                val displayText = ttsText(item)
                if (isOnlyDecorators(displayText)) return

                val cleanText = cleanTextForTts(displayText)
                if (cleanText.isBlank()) return

                val leadingOffset = displayText.lines().firstOrNull()?.let { line ->
                    LEADING_DECORATIVE.find(line)?.value?.length
                } ?: 0

                manager.speak(
                    text = cleanText,
                    textSynthesis = TextSynthesis(
                        itemPos = item,
                        playState = Utterance.PlayState.PLAYING
                    ),
                    leadingOffset = leadingOffset
                )
            }
            else -> Unit
        }
    }
}

private val WHITESPACE = Regex("\\s+")

private fun String.wordCount(): Int {
    if (this.isEmpty()) return 0
    return this.split(WHITESPACE).count { it.isNotEmpty() }
}