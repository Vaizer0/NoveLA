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
import my.noveldokusha.text_to_speech.TtsTextPreparer
import my.noveldokusha.text_to_speech.TtsAudioEnginePool
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

    // Keep only one live utterance queued at a time. Android TTS engines may serialize
    // synthesis internally, so a large live prebuffer can starve background file synthesis.
    private val halfBuffer = 1
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

    private val baseCharactersPerSecond = mutableStateOf(13.0f)

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
    )

    val isActive = derivedStateOf { state.isThereActiveItem.value || state.isPlaying.value }
    val isSpeaking = derivedStateOf { state.isThereActiveItem.value && state.isPlaying.value }

    init {
        coroutineScope.launch {
            manager.serviceLoadedFlow.take(1).collect {
                manager.trySetVoicePitch(getPreferredVoicePitch())
                manager.trySetVoiceSpeed(getPreferredVoiceSpeed())

                val preferredVoiceId = getPreferredVoiceId()
                val preferredEngine = getPreferredVoiceEngine()
                val currentEngine = manager.getCurrentEnginePackage()

                val voiceData = manager.availableVoices.find { it.id == preferredVoiceId }
                val targetEngine = voiceData?.enginePackage ?: preferredEngine

                if (targetEngine.isNotEmpty() && targetEngine != currentEngine) {
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

        coroutineScope.launch {
            val paragraphStartTimes = mutableMapOf<String, Long>()
            manager.currentTextSpeakFlow.collect { utterance ->
                val utteranceId = utterance.utteranceId
                when (utterance.playState) {
                    Utterance.PlayState.PLAYING -> {
                        paragraphStartTimes[utteranceId] = System.currentTimeMillis()
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
                            val text = item?.textToDisplay ?: ""
                            val charCount = text.length

                            if (charCount > 10 && durationMs > 200) {
                                val measuredCps = (charCount * 1000.0f) / durationMs
                                val currentSpeed = manager.voiceSpeed.floatValue
                                if (currentSpeed > 0f) {
                                    val baseCps = measuredCps / currentSpeed
                                    if (baseCps in 3.0f..40.0f) {
                                        baseCharactersPerSecond.value = 0.2f * baseCps + 0.8f * baseCharactersPerSecond.value
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
            updateJob?.cancel()
            updateJob = coroutineScope.launch {
                manager
                    .currentTextSpeakFlow
                    .filter { it.playState == Utterance.PlayState.FINISHED }
                    .collect { finishedUtterance ->
                        Timber.d("collect FINISHED queueSize=${manager.queueList.size}")
                        withContext(Dispatchers.Main) {
                            // Do not keep a large live TTS backlog. Once the current item is
                            // finished, enqueue exactly one next item, then yield the engine to
                            // background export synthesis. This prevents live TTS from starving
                            // the export workers when both use the same underlying engine.
                            if (manager.queueList.isEmpty()) {
                                // Android's TTS engine service can serialize synthesis requests.
                                // When an export is active, yield briefly between live utterances
                                // so a queued synthesizeToFile() export can acquire the engine.
                                if (TtsAudioEnginePool.hasActiveExports()) {
                                    delay(150)
                                    if (TtsAudioEnginePool.hasActiveExports()) {
                                        Timber.v("TTS fairness: yielding engine to background export")
                                    }
                                }
                                readChapterNextChunk(
                                    chapterIndex = finishedUtterance.itemPos.chapterIndex,
                                    chapterItemPosition = finishedUtterance.itemPos.chapterItemPosition,
                                    quantity = halfBuffer
                                )
                                onBufferLow?.invoke()

                                if (manager.queueList.isEmpty()) {
                                    Timber.w(
                                        "TTS-JUMP queue empty after refill: emit reachedChapterEnd " +
                                            "finished=(${finishedUtterance.itemPos.chapterIndex},${finishedUtterance.itemPos.chapterItemPosition})"
                                    )
                                    launch {
                                        reachedChapterEndFlowChapterIndex.emit(finishedUtterance.itemPos.chapterIndex)
                                    }
                                }
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
        chapterIndex: Int
    ) = withContext(Dispatchers.Main.immediate) {
        readChapterStartingFromChapterItemPosition(
            chapterIndex = chapterIndex,
            chapterItemPosition = 0
        )
    }

    private suspend fun readChapterStartingFromChapterItemPosition(
        chapterIndex: Int,
        chapterItemPosition: Int,
    ) = withContext(Dispatchers.Main.immediate) {
        Timber.d("TTS-JUMP readChapterStartingFromChapterItemPosition: ($chapterIndex,$chapterItemPosition)")
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition,
            debugSource = "startFromPos"
        )
        if (itemIndex == -1) {
            Timber.w("TTS-JUMP readChapterStartingFromChapterItemPosition: itemIndex==-1 -> emit reachedChapterEnd($chapterIndex)")
            reachedChapterEndFlowChapterIndex.emit(chapterIndex)
            return@withContext
        }
        readChapterStartingFromItemIndex(
            itemIndex = itemIndex,
            chapterIndex = chapterIndex
        )
    }

    suspend fun readChapterStartingFromItemIndex(
        itemIndex: Int,
        chapterIndex: Int,
    ) = withContext(Dispatchers.Main.immediate) {
        // Only queue the bounded live buffer. A larger backlog monopolizes the Android
        // engine's synthesis queue and can starve background file generation.
        val nextItems = getChapterNextItems(
            itemIndex = itemIndex,
            chapterIndex = chapterIndex,
            quantity = halfBuffer
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

    fun getActualPlayingPosition(): ReaderItem.Position? {
        val playingItem = manager.queueList.values
            .firstOrNull { it.playState == Utterance.PlayState.PLAYING }
        if (playingItem != null) {
            return playingItem.itemPos
        }

        val currentState = currentTextPlaying.value
        if (currentState.playState != Utterance.PlayState.FINISHED) {
            return currentState.itemPos
        }

        return null
    }

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
            Timber.d("TTS-JUMP setPlaying($playing) isSystemPauseTrigger=${ReaderTextToSpeech.isSystemPauseTrigger} pausedBySystem=${ReaderTextToSpeech.pausedBySystem}")
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
                        chapterItemPosition = state.itemPos.chapterItemPosition
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
                    debugSource = "playNextItem"
                )
                if (itemIndex <= -1 || itemIndex >= items.lastIndex) {
                    Timber.w("TTS-JUMP playNextItem: early-return itemIndex=$itemIndex lastIndex=${items.lastIndex} current=(${currentItemPos.chapterIndex},${currentItemPos.chapterItemPosition})")
                    return@launch
                }
                val nextItemRelativeIndex = items
                    .subList(itemIndex + 1, items.size)
                    .indexOfFirst { it is ReaderItem.Position }
                if (nextItemRelativeIndex == -1) return@launch
                val nextItemIndex = itemIndex + 1 + nextItemRelativeIndex
                val nextItem = items.getOrNull(nextItemIndex) as? ReaderItem.Position ?: return@launch
                val chapterHasMoreAfter = items
                    .subList(itemIndex + 1, items.size)
                    .any { it is ReaderItem.Position && it.chapterIndex == currentItemPos.chapterIndex }
                Timber.d(
                    "TTS-JUMP playNextItem: current=(${currentItemPos.chapterIndex},${currentItemPos.chapterItemPosition}) " +
                        "itemIndex=$itemIndex nextRel=$nextItemRelativeIndex nextItemIndex=$nextItemIndex " +
                        "next=(${nextItem.chapterIndex},${nextItem.chapterItemPosition}) chapterHasMoreAfter=$chapterHasMoreAfter"
                )
                if (nextItem.chapterIndex != currentItemPos.chapterIndex) {
                    val chapterPositionCount = items.count { it is ReaderItem.Position && it.chapterIndex == currentItemPos.chapterIndex }
                    val nextFew = items
                        .subList(itemIndex + 1, items.size)
                        .filterIsInstance<ReaderItem.Position>()
                        .take(5)
                        .joinToString { "(${it.chapterIndex},${it.chapterItemPosition})" }
                    Timber.w(
                        "TTS-JUMP playNextItem CROSSES CHAPTER: " +
                            "(${currentItemPos.chapterIndex},${currentItemPos.chapterItemPosition}) -> " +
                            "(${nextItem.chapterIndex},${nextItem.chapterItemPosition}) chapterHasMoreAfter=$chapterHasMoreAfter " +
                            "chapterPositions=${currentItemPos.chapterIndex}:$chapterPositionCount nextFew=[$nextFew]"
                    )
                }
                stop()
                start()
                readChapterStartingFromItemIndex(
                    itemIndex = nextItemIndex,
                    chapterIndex = nextItem.chapterIndex
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
                    debugSource = "playPreviousItem"
                )
                if (itemIndex <= 0) {
                    Timber.w("TTS-JUMP playPreviousItem: early-return itemIndex=$itemIndex current=(${currentItemPos.chapterIndex},${currentItemPos.chapterItemPosition})")
                    return@launch
                }
                val previousItemRelativeIndex = items
                    .subList(0, itemIndex)
                    .asReversed()
                    .indexOfFirst { it is ReaderItem.Position }
                if (previousItemRelativeIndex == -1) return@launch
                val previousItemIndex = itemIndex - 1 - previousItemRelativeIndex
                val previousItem = items.getOrNull(previousItemIndex) ?: return@launch
                stop()
                start()
                readChapterStartingFromItemIndex(
                    itemIndex = previousItemIndex,
                    chapterIndex = previousItem.chapterIndex
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
            Timber.d("TTS-JUMP playNextChapter: current=(${currentState.itemPos.chapterIndex},${currentState.itemPos.chapterItemPosition}) next=$nextChapterIndex")
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
                readChapterStartingFromStart(nextChapterIndex)
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
                readChapterStartingFromStart(targetChapterIndex)
                scrollToChapterTop.emit(targetChapterIndex)
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun setVoice(voiceId: String) {
        val voiceData = manager.availableVoices.find { it.id == voiceId }
        val targetEngine = voiceData?.enginePackage ?: manager.getCurrentEnginePackage()
        val currentEngine = manager.getCurrentEnginePackage()

        if (targetEngine.isNotEmpty() && targetEngine != currentEngine) {
            val wasPlaying = state.isPlaying.value
            stop()
            setPreferredVoiceId(voiceId)
            setPreferredVoiceEngine(targetEngine)
            if (wasPlaying) {
                coroutineScope.launch {
                    manager.reinitDoneFlow.take(1).collect()
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
            manager.reinitWithEngine(
                enginePackage = targetEngine,
                voiceId = voiceId,
            )
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
            resumeFromCurrentState()
        }
    }

    private fun resumeFromCurrentState() {
        Timber.d("TTS-JUMP resumeFromCurrentState: isPlaying=${state.isPlaying.value}")
        if (!state.isPlaying.value) return
        stop()
        start()
        val currentState = manager.currentActiveItemState.value
        Timber.d("TTS-JUMP resumeFromCurrentState: current=(${currentState.itemPos.chapterIndex},${currentState.itemPos.chapterItemPosition})")
        if (currentState.itemPos.chapterIndex >= 0) {
            coroutineScope.launch {
                readChapterStartingFromChapterItemPosition(
                    chapterIndex = currentState.itemPos.chapterIndex,
                    chapterItemPosition = currentState.itemPos.chapterItemPosition
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
            chapterItemPosition = chapterItemPosition,
            debugSource = "readChapterNextChunk"
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
        val result = items
            .subList(itemIndex.coerceAtMost(items.lastIndex), items.size)
            .asSequence()
            .filter { it is ReaderItem.Title || it is ReaderItem.Body }
            .filterIsInstance<ReaderItem.Position>()
            .filter { it.chapterIndex == chapterIndex }
            // Skip non-speakable/decorator-only positions so a bounded live buffer of one
            // item never mistakes a formatting-only item for the end of the chapter.
            .filter { position ->
                val textItem = position as? ReaderItem.Text ?: return@filter false
                val displayText = ttsText(textItem)
                !isOnlyDecorators(displayText) && cleanTextForTts(displayText).isNotBlank()
            }
            .take(quantity)
            .toList()
        Timber.d(
            "TTS-JUMP getChapterNextItems: itemIndex=$itemIndex chapter=$chapterIndex q=$quantity -> n=${result.size} " +
                "range=${result.firstOrNull()?.let { "(${it.chapterIndex},${it.chapterItemPosition})" } ?: "empty"}..${result.lastOrNull()?.let { "(${it.chapterIndex},${it.chapterItemPosition})" } ?: "empty"}"
        )
        return result
    }

    private fun isOnlyDecorators(text: String): Boolean =
        TtsTextPreparer.isOnlyDecorators(text)

    private fun cleanTextForTts(text: String): String =
        TtsTextPreparer.cleanForTts(text)

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

                Timber.d("TTS-JUMP speakItem: (${item.chapterIndex},${item.chapterItemPosition}) chars=${cleanText.length}")

                val leadingOffset = TtsTextPreparer.leadingDecoratorLength(displayText)

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
