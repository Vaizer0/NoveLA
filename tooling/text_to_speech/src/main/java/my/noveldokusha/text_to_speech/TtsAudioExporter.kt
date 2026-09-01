package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Независимый экспорт главы в WAV через ВЫДЕЛЕННЫЙ инстанс [TextToSpeech].
 *
 * - Использует собственную копию движка (не общий AppTtsEngine) с движком
 *   [TtsAudioExportRequest.enginePackage]: живая озвучка в читалке (общий
 *   инстанс) никогда не перезапускается и не сбивается.
 * - Ничего не проигрывает: каждый кусок уходит в [TextToSpeech.synthesizeToFile],
 *   PCM ловится в [onAudioAvailable] и потоково пишется в [WavWriter].
 * - Куски синтезируются строго последовательно (дождавшись предыдущего), что
 *   гарантирует порядок текста и ограничивает память.
 * - Возвращает готовый (с «латаным» заголовком) временный WAV-файл; воркер
 *   копирует его в SAF-папку и удаляет.
 */
class TtsAudioExporter(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Синтезирует указанные абзацы и возвращает готовый WAV-файл.
     * @param paragraphs уже подготовленные абзацы (см. TtsTextPreparer.paragraphsFromBody).
     * @param destFile временный файл для WAV.
     */
    suspend fun exportAudio(
        request: TtsAudioExportRequest,
        paragraphs: List<String>,
        destFile: File,
    ): File {
        if (paragraphs.isEmpty()) {
            throw TtsExportException("Chapter has no text to synthesize")
        }

        val throwawaySink = File(appContext.cacheDir, "tts_export_sink.bin")
        val writer = WavWriter(destFile)
        val tts = createDedicatedTts(request.enginePackage)
        try {
            val syntheinputLength = TextToSpeech.getMaxSpeechInputLength()
            val voices = tts.voices ?: emptyList()
            val voice = voices.find { it.name == request.voiceId }
                ?: throw TtsExportException(
                    "Voice '${request.voiceId}' not found in engine '${request.enginePackage}'"
                )
            tts.voice = voice
            tts.setSpeechRate(request.speed)
            tts.setPitch(request.pitch)

            var synthesizedAny = false
            for ((paraIndex, paragraph) in paragraphs.withIndex()) {
                val cleaned = TtsTextPreparer.cleanForTts(paragraph)
                if (TtsTextPreparer.isOnlyDecorators(cleaned)) continue

                val chunks = TtsTextPreparer.chunkIntoUtterances(cleaned, syntheinputLength)
                for (chunk in chunks) {
                    synthesizeChunk(
                        tts = tts,
                        text = chunk,
                        throwawaySink = throwawaySink,
                        writer = writer,
                        chapterTitle = request.chapterTitle,
                    )
                    if (writer.dataBytesWritten() > 0) synthesizedAny = true
                }
            }

            if (!synthesizedAny) {
                throw TtsExportException(
                    "No audio was produced for chapter '${request.chapterTitle}'. " +
                        "The TTS engine produced an empty result."
                )
            }
        } finally {
            try {
                tts.stop()
            } catch (_: Throwable) {
            }
            runCatching { tts.shutdown() }
            runCatching { throwawaySink.delete() }
        }

        writer.finish()
        return destFile
    }

    private suspend fun createDedicatedTts(enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { cont ->
            val listener = object : TextToSpeech.OnInitListener {
                override fun onInit(status: Int) {
                    if (cont.isCancelled) return
                    if (status == TextToSpeech.SUCCESS) {
                        val instance = ttsRef
                        if (instance != null) cont.resume(instance)
                    } else {
                        cont.resumeWithException(
                            TtsExportException("TTS engine '$enginePackage' init failed: status=$status")
                        )
                    }
                }
            }
            val tts = TextToSpeech(
                appContext,
                listener,
                enginePackage.ifBlank { null }
            )
            ttsRef = tts
            cont.invokeOnCancellation {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
        }

    // Вспомогательное поле для передачи инстанса из колбэка init (Kotlin 2.x строгость
    // присваивания final локальных переменных из лямбды обходится через var класса).
    private var ttsRef: TextToSpeech? = null

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        throwawaySink: File,
        writer: WavWriter,
        chapterTitle: String,
    ) {
        val done = CompletableDeferred<Unit>()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                Timber.d("ttsExport chunk done id=$utteranceId")
                done.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Timber.w( "ttsExport chunk error id=$utteranceId code=$errorCode")
                done.completeExceptionally(
                    TtsExportException("Synthesis failed (error $errorCode) for '$chapterTitle'")
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Timber.w( "ttsExport chunk error (deprecated) id=$utteranceId")
                done.completeExceptionally(
                    TtsExportException("Synthesis failed for '$chapterTitle'")
                )
            }

            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (sampleRateInHz <= 0 || channelCount <= 0) {
                    Timber.w( "ttsExport invalid synthesis format id=$utteranceId rate=$sampleRateInHz ch=$channelCount")
                    return
                }
                if (!writer.isValidPcmFormat(audioFormat)) {
                    Timber.w( "ttsExport unsupported audioFormat=$audioFormat (only PCM16 supported)")
                    return
                }
                try {
                    writer.open(sampleRateInHz, channelCount)
                } catch (e: Exception) {
                    Timber.e(e, "ttsExport opening WAV failed id=$utteranceId")
                    done.completeExceptionally(
                        TtsExportException("Failed initializing audio writer: ${e.message}", e)
                    )
                }
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (audio.isEmpty()) return
                try {
                    writer.writePcm(audio)
                } catch (e: Exception) {
                    Timber.e(e, "ttsExport writing PCM failed id=$utteranceId")
                    done.completeExceptionally(
                        TtsExportException("Failed writing audio data: ${e.message}", e)
                    )
                }
            }
        }
        tts.setOnUtteranceProgressListener(listener)

        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = ParcelFileDescriptor.open(
                throwawaySink,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
            val bundle = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_chunk")
            }
            val result = tts.synthesizeToFile(text, bundle, descriptor, "tts_chunk")
            if (result != TextToSpeech.SUCCESS) {
                done.completeExceptionally(
                    TtsExportException("synthesizeToFile rejected input (result=$result)")
                )
            }
            done.await()
        } finally {
            runCatching { descriptor?.close() }
        }
    }
}

/** Ошибка экспорта аудио главы (отображается пользователю в уведомлении/логе). */
class TtsExportException(message: String, cause: Throwable? = null) : Exception(message, cause)