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
import my.noveldokusha.text_to_speech.TtsSynthesisCoordinator
import timber.log.Timber

/** Ошибка синтеза конкретного фрагмента. */
class TtsSynthesisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Формат аудио, сообщённый движком через `onBeginSynthesis`. */
data class SynthFormat(
    val sampleRateHz: Int,
    val channels: Int,
    /** `AudioFormat.ENCODING_*`; 0 — движок не сообщил (считаем PCM 16 бит). */
    val encoding: Int = 0,
)

/**
 * Формат мердж-потока: с ним обязан совпасть сегмент, взятый из кэша,
 * иначе его нельзя дописать к уже начатому файлу.
 */
data class PcmFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int = 16,
) {
    fun matches(segment: PcmSegment): Boolean =
        segment.sampleRateHz == sampleRateHz &&
            segment.channels == channels &&
            segment.bitsPerSample == bitsPerSample
}

/**
 * Движок синтеза для экспорта аудиокниг.
 *
 * Экземпляр `TextToSpeech` здесь собственный и не переиспользует
 * `AppTtsEngine`/`TextToSpeechManager`. Но сам сервис TTS у Android один
 * на устройство, и его поток синтеза общий: поэтому на время экспорта
 * читалка через [TtsSynthesisCoordinator] снижает свой запас в очереди,
 * чтобы экспорт не голодал, а воспроизведение не прерывалось.
 *
 * Отдельный процесс/сервис не решает эту проблему: запросы всех клиентов
 * всё равно приходят в один процесс TTS-движка через Binder и там
 * выстраиваются в одну очередь. Поэтому изоляция достигается отдельным
 * клиентом `TextToSpeech` (свой `stop`/`shutdown`, своя очередь-подписка),
 * а не общим экземпляром читалки, плюс кэш PCM, который убирает
 * дублирующий синтез между WAV- и MP4-экспортами.
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
    /** Постоянный кэш PCM. `null` — синтезировать всегда заново. */
    private val cache: TtsSynthesisCache? = null,
) : AutoCloseable {

    private var tts: TextToSpeech? = null

    /** Зарегистрирован ли этот синтезатор как активный пакетный потребитель движка. */
    private var batchRegistered = false

    /**
     * Фактические движок и голос, участвующие в ключе кэша. Движок может
     * быть выбран системой по умолчанию, а голос — подставлен из движка,
     * поэтому одного снимка настроек недостаточно.
     */
    private var resolvedEnginePackage = enginePackage
    private var resolvedVoiceId = voiceId

    /**
     * Ошибка текущей попытки синтеза. Пишется из колбэка движка,
     * который приходит на другой поток, поэтому доступ через @Volatile.
     */
    @Volatile
    private var currentAttempt: AttemptState? = null

    @Volatile
    private var reportedFormat: SynthFormat? = null

    /** Уникальные utteranceId, чтобы поздние колбэки не влияли на новые попытки. */
    private val utteranceCounter = java.util.concurrent.atomic.AtomicInteger(0)

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
        installProgressListener(instance)
        resolvedEnginePackage = engine
            ?: runCatching { instance.defaultEngine }.getOrNull().orEmpty()
        applySettings(instance)
        // Пока экспорт синтезирует, читалка снижает свой запас в очереди
        // движка: у него один поток синтеза, и иначе экспорт голодает.
        TtsSynthesisCoordinator.beginBatch()
        batchRegistered = true
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
        // Фактически выбранный голос (учитывая подстановку движком) — часть
        // ключа кэша: смена голоса обязана инвалидировать старые записи.
        resolvedVoiceId = runCatching { instance.voice?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: voiceId
    }

    /** Формат, сообщённый движком, либо null, если он его не прислал. */
    fun currentFormat(): SynthFormat? = reportedFormat

    /**
     * Синтезирует [text] в [outputFile] и возвращает разобранный сегмент.
     *
     * Сначала пробует постоянный кэш: он делает повторную озвучку того же
     * текста (в том числе в отдельном WAV/MP4-экспорте) байт-в-байт
     * одинаковой. При промахе синтезирует и кладёт результат в кэш.
     *
     * @param expected формат уже начатого мердж-потока; кэшевый сегмент
     *   другого формата игнорируется, чтобы не сломать склейку.
     *
     * Длительность намеренно **не** возвращается отдельным числом: она всегда
     * берётся из фактически записанного файла (см. [WavAudio.readSegment]),
     * иначе таймлайн разошёлся бы с аудио.
     */
    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        expected: PcmFormat? = null,
    ): PcmSegment {
        val instance = tts ?: throw TtsSynthesisException("TTS engine is not initialized")
        outputFile.parentFile?.mkdirs()

        // Локальные ссылки: на член класса smart-cast внутри лямбды не
        // распространяется, а `runCatching` — именно лямбда.
        val pcmCache = cache

        // 1. Кэш: одинаковый текст + движок + голос + темп + тон обязаны
        //    давать то же аудио, иначе WAV/MP4 одного текста разойдутся.
        val cacheKey = pcmCache?.let {
            TtsSynthesisCache.key(
                enginePackage = resolvedEnginePackage,
                voiceId = resolvedVoiceId,
                speed = speed,
                pitch = pitch,
                text = text,
            )
        }
        if (pcmCache != null && cacheKey != null) {
            val cached = pcmCache.get(cacheKey)
            if (cached != null) {
                val segment = runCatching { WavAudio.readSegment(cached) }.getOrNull()
                if (segment != null && (expected == null || expected.matches(segment))) {
                    cached.copyTo(outputFile, overwrite = true)
                    return segment
                }
                // Формат не совпал с потоком — синтезируем и перезапишем кэш.
            }
        }

        if (outputFile.exists()) outputFile.delete()

        var lastFailure: Exception? = null
        repeat(MAX_SYNTHESIS_ATTEMPTS) { attempt ->
            val utteranceId = "$AUDIOBOOK_UTTERANCE_PREFIX${utteranceCounter.incrementAndGet()}"
            val state = AttemptState(utteranceId)
            currentAttempt = state
            val queued = queueSynthesis(instance, text, outputFile, utteranceId)

            // Колбэк завершения — best effort: часть движков его не шлёт,
            // и тогда срабатывает проверка стабильности размера файла.
            val usable = queued == TextToSpeech.SUCCESS && waitForStableFile(instance, outputFile, state)

            if (usable) {
                if (!AudioDecoder.ensurePcmWav(outputFile, reportedFormat)) {
                    throw TtsSynthesisException("TTS produced an unsupported audio format")
                }
                val segment = runCatching { WavAudio.readSegment(outputFile) }.getOrElse {
                    throw TtsSynthesisException("synthesized file is unreadable: ${it.message}", it)
                }
                if (pcmCache != null && cacheKey != null) {
                    runCatching { pcmCache.put(cacheKey, outputFile) }
                }
                currentAttempt = null
                return segment
            }

            val reason = when {
                queued != TextToSpeech.SUCCESS -> "queue returned $queued"
                state.error != null -> state.error!!
                else -> "no audio produced on attempt ${attempt + 1}"
            }
            Timber.w("TtsAudioSynthesizer: TTS attempt %d failed: %s", attempt + 1, reason)
            lastFailure = TtsSynthesisException(reason)
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

    private fun installProgressListener(instance: TextToSpeech) {
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                currentAttempt?.takeIf { it.matches(utteranceId) }?.done = true
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                currentAttempt?.takeIf { it.matches(utteranceId) }?.error =
                    "TTS reported an error for $utteranceId"
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                currentAttempt?.takeIf { it.matches(utteranceId) }?.error =
                    "TTS error $errorCode for $utteranceId"
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
                    reportedFormat = SynthFormat(sampleRateInHz, channelCount, audioFormat)
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = Unit
        })
    }

    /**
     * Ждёт, пока размер файла перестанет меняться: движки не всегда
     * присылают колбэк завершения, но к этому моменту файл уже записан.
     */
    private suspend fun waitForStableFile(
        instance: TextToSpeech,
        outputFile: File,
        state: AttemptState,
    ): Boolean {
        var lastSize = -1L
        var stableRounds = 0
        val startedAt = System.currentTimeMillis()
        var deadline = startedAt + SYNTHESIS_TIMEOUT_MS
        while (true) {
            // Движок может не прислать файл, но сообщить об ошибке — не ждём
            // таймаут целиком, иначе экран зависает на 0% на минуты.
            if (state.error != null) return false
            val size = if (outputFile.exists()) outputFile.length() else -1L
            // Готовый файл: колбэк onDone либо стабильный размер с реальными
            // данными (не только 44-байтовый заголовок WAV).
            if (state.done && size > WAV_MIN_BYTES) return true
            if (size > WAV_MIN_BYTES && size == lastSize) {
                stableRounds++
                if (stableRounds >= STABLE_ROUNDS_REQUIRED) return true
            } else {
                stableRounds = 0
            }
            lastSize = size
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                // Движок занят (например, читалка озвучивает книгу): наш
                // запрос всё ещё стоит в общей очереди синтеза, это не
                // ошибка. Иначе экспорт уходил в бесконечные ретраи и
                // выглядел «зависшим» до паузы читалки.
                val engineBusy = runCatching { instance.isSpeaking }.getOrDefault(false)
                if (engineBusy && now < startedAt + HARD_TIMEOUT_MS) {
                    deadline = now + SYNTHESIS_TIMEOUT_MS
                } else {
                    return false
                }
            }
            delay(STABLE_POLL_MS)
        }
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
        if (batchRegistered) {
            batchRegistered = false
            TtsSynthesisCoordinator.endBatch()
        }
    }

    /**
     * Состояние одной попытки синтеза: ошибка приходит асинхронно из
     * колбэка движка, поэтому поле помечено `@Volatile`.
     */
    private class AttemptState(val utteranceId: String) {
        @Volatile
        var error: String? = null

        @Volatile
        var done: Boolean = false

        /** Относится ли колбэк к этой попытке (null-идентификатор считаем своим). */
        fun matches(other: String?): Boolean = other == null || other == utteranceId
    }

    private companion object {
        const val MAX_SYNTHESIS_ATTEMPTS = 3
        const val SYNTHESIS_TIMEOUT_MS = 3L * 60L * 1000L
        // Пока движок занят чужим синтезом, ждём дольше (но не бесконечно):
        // иначе один долгий сеанс чтения «ронял» экспорт в ретраи.
        const val HARD_TIMEOUT_MS = 30L * 60L * 1000L
        const val STABLE_POLL_MS = 120L
        const val STABLE_ROUNDS_REQUIRED = 2
        const val RETRY_DELAY_MS = 60L
        const val WAV_MIN_BYTES = 44L
        const val DEFAULT_MAX_CHUNK = 4000
        const val MIN_CHUNK = 200
        const val AUDIOBOOK_UTTERANCE_PREFIX = "novela_audiobook_"
    }
}
