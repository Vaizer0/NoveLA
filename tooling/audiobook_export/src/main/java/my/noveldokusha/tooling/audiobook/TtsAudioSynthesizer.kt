package my.noveldokusha.tooling.audiobook

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/** Ошибка синтеза конкретного фрагмента. */
class TtsSynthesisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Формат аудио, сообщённый движком через `onBeginSynthesis`. */
data class SynthFormat(val sampleRateHz: Int, val channels: Int)

/**
 * Выделенный движок синтеза для экспорта аудиокниг.
 *
 * Намеренно **не** переиспользует `AppTtsEngine`/`TextToSpeechManager`:
 * живая очередь чтения не должна ни страдать, ни мешать экспорту.
 * Экземпляр `TextToSpeech` здесь собственный и принадлежит только этому
 * объекту.
 *
 * Настройки движка/голоса/скорости/тона передаются снимком из
 * `AppPreferences`, чтобы изменение настроек посреди экспорта ничего
 * не сломало.
 */
class TtsAudioSynthesizer(
    private val context: Context,
    private val enginePackage: String,
    private val voiceId: String,
    private val speed: Float,
    private val pitch: Float,
) : AutoCloseable {

    private var tts: TextToSpeech? = null

    /**
     * Ошибка текущей попытки синтеза. Пишется из колбэка движка,
     * который приходит на другой поток, поэтому доступ через @Volatile.
     */
    @Volatile
    private var currentAttempt: AttemptState? = null

    @Volatile
    private var reportedFormat: SynthFormat? = null

    /**
     * Создаёт и настраивает движок. Вызывается один раз перед синтезом.
     *
     * Инициализация приходит из сервиса движка, поэтому готовность
     * ожидается через suspend-функцию, а не через колбэк.
     */
    suspend fun initialize() {
        if (tts != null) return
        val engine = enginePackage.takeIf { it.isNotBlank() }
        val appContext = context.applicationContext

        val instance = suspendCancellableCoroutine<TextToSpeech> { continuation ->
            lateinit var created: TextToSpeech
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    if (continuation.isActive) continuation.resume(created)
                } else if (continuation.isActive) {
                    continuation.resumeWithException(
                        TtsSynthesisException("TTS engine init failed with status $status"),
                    )
                }
            }
            created = if (engine != null) {
                TextToSpeech(appContext, listener, engine)
            } else {
                TextToSpeech(appContext, listener)
            }
            continuation.invokeOnCancellation { runCatching { created.shutdown() } }
        }

        tts = instance
        currentAttempt = AttemptState()
        installProgressListener(instance, currentAttempt!!)
        applySettings(instance)
    }

    /** Применяет голос, скорость и тон, заданные снимком настроек. */
    private fun applySettings(instance: TextToSpeech) {
        val wanted = voiceId.takeIf { it.isNotBlank() }
        if (wanted != null) {
            val voice = instance.voices?.firstOrNull { it.name == wanted }
            if (voice != null) {
                instance.voice = voice
            } else {
                Timber.w("TtsAudioSynthesizer: voice '%s' not found, using engine default", wanted)
            }
        }
        instance.setSpeechRate(speed.coerceIn(0.1f, 5f))
        instance.setPitch(pitch.coerceIn(0.1f, 5f))
    }

    /** Формат, сообщённый движком, либо null, если он его не прислал. */
    fun currentFormat(): SynthFormat? = reportedFormat

    /**
     * Синтезирует [text] в [outputFile] и возвращает разобранный сегмент.
     *
     * Длительность намеренно **не** возвращается отдельным числом: она всегда
     * берётся из фактически записанного файла (см. [WavAudio.readSegment]),
     * иначе таймлайн разошёлся бы с аудио.
     */
    suspend fun synthesizeToFile(text: String, outputFile: File): PcmSegment {
        val instance = tts ?: throw TtsSynthesisException("TTS engine is not initialized")
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        var lastFailure: Exception? = null
        repeat(MAX_SYNTHESIS_ATTEMPTS) { attempt ->
            val state = AttemptState()
            currentAttempt = state
            val utteranceId = "$AUDIOBOOK_UTTERANCE_PREFIX$attempt"
            val queued = queueSynthesis(instance, text, outputFile, utteranceId)

            // Колбэк завершения — best effort: часть движков его не шлёт,
            // и тогда срабатывает проверка стабильности размера файла.
            val usable = when {
                queued != TextToSpeech.SUCCESS -> false
                state.error != null -> false
                else -> waitForStableFile(outputFile, state)
            }

            if (usable) {
                return runCatching { WavAudio.readSegment(outputFile) }.getOrElse {
                    throw TtsSynthesisException("synthesized file is unreadable: ${it.message}", it)
                }
            }

            lastFailure = TtsSynthesisException(
                state.error ?: "TTS produced no audio on attempt ${attempt + 1}",
            )
            runCatching { instance.stop() }
            runCatching { outputFile.delete() }
            applySettings(instance)
            delay(RETRY_DELAY_MS)
        }
        currentAttempt = null
        throw lastFailure ?: TtsSynthesisException("TTS synthesis failed")
    }

    /** Ставит фрагмент в очередь синтеза с записью в файл. */
    private fun queueSynthesis(
        instance: TextToSpeech,
        text: String,
        outputFile: File,
        utteranceId: String,
    ): Int {
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        return instance.synthesizeToFile(text, params, outputFile, utteranceId)
    }

    private fun installProgressListener(instance: TextToSpeech, state: AttemptState) {
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = Unit

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                state.error = "TTS reported an error for $utteranceId"
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                state.error = "TTS error $errorCode for $utteranceId"
            }

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                // Движок сообщает реальный формат синтеза. Если он «прыгнул»
                // посреди книги, склеить сегменты будет нельзя.
                if (sampleRateInHz > 0 && channelCount > 0) {
                    reportedFormat = SynthFormat(sampleRateInHz, channelCount)
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = Unit
        })
    }

    /**
     * Ждёт, пока размер файла перестанет меняться: движки не всегда
     * присылают колбэк завершения, но к этому моменту файл уже записан.
     */
    private suspend fun waitForStableFile(outputFile: File, state: AttemptState): Boolean {
        var lastSize = -1L
        var stableRounds = 0
        val deadline = System.currentTimeMillis() + SYNTHESIS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Движок может не прислать файл, но сообщить об ошибке — не ждём
            // таймаут целиком, иначе экран зависает на 0% на минуты.
            if (state.error != null) return false
            val size = if (outputFile.exists()) outputFile.length() else -1L
            if (size >= WAV_MIN_BYTES && size == lastSize) {
                stableRounds++
                if (stableRounds >= STABLE_ROUNDS_REQUIRED) return true
            } else {
                stableRounds = 0
            }
            lastSize = size
            delay(STABLE_POLL_MS)
        }
        return false
    }

    /**
     * Лимит длины одной TTS-порции, заданный движком.
     *
     * Метод статический, а не экземплярный: часть движков сообщает лимит
     * только через него, поэтому он является безопасным минимумом.
     */
    fun maxChunkLength(): Int =
        TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(MIN_CHUNK)

    override fun close() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    /**
     * Состояние одной попытки синтеза: ошибка приходит асинхронно из
     * колбэка движка, поэтому поле помечено `@Volatile`.
     */
    private class AttemptState {
        @Volatile
        var error: String? = null
    }

    private companion object {
        const val MAX_SYNTHESIS_ATTEMPTS = 3
        const val SYNTHESIS_TIMEOUT_MS = 3L * 60L * 1000L
        const val STABLE_POLL_MS = 120L
        const val STABLE_ROUNDS_REQUIRED = 2
        const val RETRY_DELAY_MS = 60L
        const val WAV_MIN_BYTES = 44L
        const val DEFAULT_MAX_CHUNK = 4000
        const val MIN_CHUNK = 200
        const val AUDIOBOOK_UTTERANCE_PREFIX = "novela_audiobook_"
    }
}
