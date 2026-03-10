package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import me.nanihadesuka.algorithms.delimiterAwareTextSplitter

interface Utterance<T : Utterance<T>> {
    enum class PlayState { PLAYING, FINISHED, LOADING }

    val utteranceId: String
    val playState: PlayState
    fun copyWithState(playState: PlayState): T
}

data class VoiceData(
    val id: String,
    val language: String,
    val needsInternet: Boolean,
    val quality: Int,
    val enginePackage: String,
)

class TextToSpeechManager<T : Utterance<T>>(
    private val context: Context,
    initialItemState: T,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _queueList = mutableMapOf<String, T>()
    private val _queueListItemSize = mutableMapOf<String, Int>()
    private val _currentTextSpeakFlow = MutableSharedFlow<T>()

    val availableVoices = mutableStateListOf<VoiceData>()
    val voiceSpeed = mutableFloatStateOf(1f)
    val voicePitch = mutableFloatStateOf(1f)
    val activeVoice = mutableStateOf<VoiceData?>(null)
    val serviceLoadedFlow = MutableSharedFlow<Unit>(replay = 0)
    val queueList = _queueList as Map<String, T>
    val currentTextSpeakFlow = _currentTextSpeakFlow.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed()
    )

    private val auxiliaryServices = mutableListOf<TextToSpeech>()

    // Храним enginePackage сами — service.defaultEngine всегда возвращает системный дефолт,
    // независимо от того с каким enginePackage был создан этот конкретный service объект.
    private var currentEnginePackage: String = ""

    var service: TextToSpeech = createService(enginePackage = null, onReady = ::onServiceReady)
        private set

    val currentActiveItemState = mutableStateOf(initialItemState)

    private fun onServiceReady() {
        currentEnginePackage = service.defaultEngine ?: ""
        listenToUtterances()
        updateActiveVoice()
        collectVoicesFromAllEngines()
    }

    private fun createService(enginePackage: String?, onReady: () -> Unit): TextToSpeech {
        val init: (Int) -> Unit = { status ->
            if (status == TextToSpeech.SUCCESS) onReady()
        }
        return if (enginePackage.isNullOrEmpty()) {
            TextToSpeech(context, init)
        } else {
            TextToSpeech(context, init, enginePackage)
        }
    }

    fun getCurrentEnginePackage(): String = currentEnginePackage

    fun reinitWithEngine(enginePackage: String, voiceId: String) {
        auxiliaryServices.forEach { runCatching { it.shutdown() } }
        auxiliaryServices.clear()

        service.stop()
        service.shutdown()

        val savedSpeed = voiceSpeed.floatValue
        val savedPitch = voicePitch.floatValue

        service = createService(enginePackage = enginePackage) {
            // Запоминаем реальный движок этого service объекта
            currentEnginePackage = enginePackage
            service.setSpeechRate(savedSpeed)
            service.setPitch(savedPitch)
            val voice = service.voices?.find { it.name == voiceId }
            if (voice != null) {
                service.voice = voice
                updateActiveVoice()
            }
            listenToUtterances()
            scope.launch { serviceLoadedFlow.emit(Unit) }
        }
    }

    private fun collectVoicesFromAllEngines() {
        val engines = service.engines
        var pending = engines.size

        if (engines.isEmpty()) {
            scope.launch { serviceLoadedFlow.emit(Unit) }
            return
        }

        engines.forEach { engineInfo ->
            if (engineInfo.name == service.defaultEngine) {
                // Голоса дефолтного движка уже доступны через текущий service
                val voices = service.voices
                    ?.map { it.toVoiceData(engineInfo.name) }
                    ?: emptyList()
                availableVoices.addAll(voices)
                if (--pending == 0) scope.launch { serviceLoadedFlow.emit(Unit) }
            } else {
                var aux: TextToSpeech? = null
                aux = TextToSpeech(context, { auxStatus ->
                    if (auxStatus == TextToSpeech.SUCCESS) {
                        val voices = aux?.voices
                            ?.map { it.toVoiceData(engineInfo.name) }
                            ?: emptyList()
                        availableVoices.addAll(voices)
                    }
                    runCatching { aux?.shutdown() }
                    auxiliaryServices.remove(aux)
                    if (--pending == 0) scope.launch { serviceLoadedFlow.emit(Unit) }
                }, engineInfo.name)
                auxiliaryServices.add(aux)
            }
        }
    }

    fun stop() {
        service.stop()
        _queueList.clear()
        _queueListItemSize.clear()
    }

    fun speak(text: String, textSynthesis: T) {
        val subItems = delimiterAwareTextSplitter(
            fullText = text,
            maxSliceLength = maxStringLengthPerTextUnit(),
            charDelimiter = '.'
        )
        _queueList[textSynthesis.utteranceId] = textSynthesis
        _queueListItemSize[textSynthesis.utteranceId] = subItems.size

        subItems.forEachIndexed { index, textSlice ->
            val uniqueID = "$index|${textSynthesis.utteranceId}"
            val bundle = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uniqueID)
            }
            service.speak(textSlice, TextToSpeech.QUEUE_ADD, bundle, uniqueID)
        }
    }

    fun setCurrentSpeakState(textSynthesis: T) {
        currentActiveItemState.value = textSynthesis
        scope.launch { _currentTextSpeakFlow.emit(textSynthesis) }
    }

    fun trySetVoiceById(id: String): Boolean {
        val voice = service.voices?.find { it.name == id } ?: return false
        service.voice = voice
        updateActiveVoice()
        return true
    }

    fun trySetVoicePitch(value: Float): Boolean {
        if (value < 0.1 || value > 5) return false
        val result = service.setPitch(value)
        if (result == TextToSpeech.SUCCESS) {
            voicePitch.floatValue = value
            return true
        }
        return false
    }

    fun trySetVoiceSpeed(value: Float): Boolean {
        if (value < 0.1 || value > 5) return false
        val result = service.setSpeechRate(value)
        if (result == TextToSpeech.SUCCESS) {
            voiceSpeed.floatValue = value
            return true
        }
        return false
    }

    private fun maxStringLengthPerTextUnit() = TextToSpeech.getMaxSpeechInputLength()

    private fun updateActiveVoice() {
        activeVoice.value = service.voice?.toVoiceData(currentEnginePackage)
    }

    private fun Voice.toVoiceData(enginePackage: String) = VoiceData(
        id = name,
        language = locale.displayLanguage,
        needsInternet = isNetworkConnectionRequired,
        quality = quality,
        enginePackage = enginePackage,
    )

    private fun listenToUtterances() {
        service.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null) return
                val itemUtteranceIndex = utteranceId
                    .substringBefore('|', "")
                    .toIntOrNull() ?: return
                if (itemUtteranceIndex != 0) return

                val itemUtteranceId = utteranceId.substringAfter('|')
                val res: T = _queueList[itemUtteranceId]
                    ?.copyWithState(playState = Utterance.PlayState.PLAYING)
                    ?: return

                currentActiveItemState.value = res
                scope.launch { _currentTextSpeakFlow.emit(res) }
            }

            override fun onDone(utteranceId: String?) = onFinished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onFinished(utteranceId)

            private fun onFinished(utteranceId: String?) {
                if (utteranceId == null) return
                val subItemUtteranceIndex = utteranceId
                    .substringBefore('|', "")
                    .toIntOrNull() ?: return
                val itemUtteranceId = utteranceId.substringAfter('|')

                val itemSize = _queueListItemSize[itemUtteranceId]?.minus(1) ?: return
                if (itemSize != subItemUtteranceIndex) return

                val res: T = _queueList[itemUtteranceId]
                    ?.copyWithState(playState = Utterance.PlayState.FINISHED)
                    ?: return

                _queueList.remove(itemUtteranceId)
                _queueListItemSize.remove(itemUtteranceId)

                currentActiveItemState.value = res
                scope.launch { _currentTextSpeakFlow.emit(res) }
            }
        })
    }
}