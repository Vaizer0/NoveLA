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
 *   PCM ловится в [UtteranceProgressListener.onAudioAvailable] и потоково пишется в
 *   [WavWriter].
 * - Куски синтезируются строго последовательно (дождавшись предыдущего), что
 *   гарантирует порядок текста и ограничивает память.
 * - В ТОТ ЖЕ сеанс синтеза через [TtsTimelineBuilder] фиксируются native
 *   [UtteranceProgressListener.onRangeStart]/[UtteranceProgressListener.onBeginSynthesis]/
 *   [UtteranceProgressListener.onAudioAvailable] события, из которых строится
 *   синхронизационная временная шкала для РОВНО этого WAV.
 * - Возвращает [TtsAudioExportResult] (WAV-файл + timeline); воркер копирует их
 *   в SAF-папку и удаляет временный файл.
 */
class TtsAudioExporter(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Синтезирует указанные абзацы и возвращает WAV + синхронизационную шкалу.
     * @param request параметры экспорта (в т.ч. метаданные главы для timeline).
     * @param paragraphs уже подготовленные абзацы (см. TtsTextPreparer.paragraphsFromBody).
     * @param destFile временный файл для WAV.
     * @param audioFileName имя, под которым WAV реально будет сохранён (для timeline.chapter.audioFile).
     * @param onProgress колбэк прогресса: доля (0..1) выполненного текста по числу
     *   символов кусков (монотонна, вызывается после каждого синтезированного куска).
     */
    suspend fun exportAudio(
        request: TtsAudioExportRequest,
        paragraphs: List<String>,
        destFile: File,
        audioFileName: String,
        onProgress: (Float) -> Unit = {},
    ): TtsAudioExportResult {
        if (paragraphs.isEmpty()) {
            throw TtsExportException("Chapter has no text to synthesize")
        }

        val throwawaySink = File(appContext.cacheDir, "tts_export_sink.bin")
        val writer = WavWriter(destFile)
        val timelineBuilder = TtsTimelineBuilder()
        timelineBuilder.beginChapter(
            novelTitle = request.novelTitle,
            chapterTitle = request.chapterTitle,
            chapterIndex = request.chapterIndex,
            source = request.source.name,
        )

        val tts = createDedicatedTts(request.enginePackage)
        var finished = false
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

            // Куски собираем заранее (с уже применённой фильтрацией декораторов), чтобы
            // прогресс был честным: вес куска = число его символов (1% ≈ 1% текста).
            // Декораторные абзацы пропускаются — такие же, как и в timeline.
            val paragraphPlans = buildList {
                for (paragraph in paragraphs) {
                    val cleaned = TtsTextPreparer.cleanForTts(paragraph)
                    if (TtsTextPreparer.isOnlyDecorators(cleaned)) continue
                    add(cleaned to TtsTextPreparer.chunkIntoUtterances(cleaned, syntheinputLength))
                }
            }
            val totalChars = paragraphPlans.sumOf { it.first.length }

            var synthesizedAny = false
            var processedChars = 0
            for ((_, slices) in paragraphPlans) {
                timelineBuilder.beginParagraph()
                for (chunk in slices) {
                    timelineBuilder.registerSlice(chunk)
                    synthesizeChunk(
                        tts = tts,
                        text = chunk,
                        throwawaySink = throwawaySink,
                        writer = writer,
                        chapterTitle = request.chapterTitle,
                        timelineBuilder = timelineBuilder,
                    )
                    processedChars += chunk.length
                    if (writer.dataBytesWritten() > 0) synthesizedAny = true
                    onProgress(
                        if (totalChars > 0) processedChars.toFloat() / totalChars.toFloat() else 1f
                    )
                }
                timelineBuilder.endParagraph()
            }

            if (!synthesizedAny) {
                throw TtsExportException(
                    "No audio was produced for chapter '${request.chapterTitle}'. " +
                        "The TTS engine produced an empty result."
                )
            }

            writer.finish()
            finished = true

            val sampleRate = writer.sampleRate()
            val channels = writer.channels()
            val durationMs = if (sampleRate > 0 && channels > 0) {
                (writer.dataBytesWritten() / (sampleRate.toLong() * channels * 2L) * 1000L).toInt()
            } else {
                0
            }
            if (sampleRate <= 0) {
                throw TtsExportException("Chapter '${request.chapterTitle}' has no valid sample rate")
            }

            val timeline = timelineBuilder.build(
                audioFileName = audioFileName,
                audioSampleRate = sampleRate,
                audioChannels = channels,
                audioDurationMs = durationMs,
            )
            return TtsAudioExportResult(audioFile = destFile, timeline = timeline)
        } finally {
            try {
                tts.stop()
            } catch (_: Throwable) {
            }
            runCatching { tts.shutdown() }
            // При ошибке/отмене WavWriter может остаться незакрытым — закрываем,
            // чтобы не утечь fd. При успехе finish() уже закрыл поток.
            if (!finished) runCatching { writer.close() }
            runCatching { throwawaySink.delete() }
        }
    }

    private suspend fun createDedicatedTts(enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { cont ->
            // Локальная var захватывается анонимным OnInitListener, избегая
            // хрупкого class-поля: каждый вызов получает собственный инстанс.
            lateinit var tts: TextToSpeech
            val listener = object : TextToSpeech.OnInitListener {
                override fun onInit(status: Int) {
                    if (cont.isCancelled) return
                    if (status == TextToSpeech.SUCCESS) {
                        if (cont.isActive) cont.resume(tts)
                    } else {
                        cont.resumeWithException(
                            TtsExportException("TTS engine '$enginePackage' init failed: status=$status")
                        )
                    }
                }
            }
            tts = TextToSpeech(
                appContext,
                listener,
                enginePackage.ifBlank { null }
            )
            cont.invokeOnCancellation {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
        }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        throwawaySink: File,
        writer: WavWriter,
        chapterTitle: String,
        timelineBuilder: TtsTimelineBuilder,
    ) {
        val done = CompletableDeferred<Unit>()

        // Сообщает об ошибке построения timeline: любой сбой здесь — сбой экспорта
        // (синхронизированный экспорт без валидной шкалы не допускается).
        fun failTimeline(e: Throwable) {
            Timber.e(e, "ttsExport timeline capture failed for '$chapterTitle'")
            done.completeExceptionally(
                TtsExportException("Failed capturing TTS timeline: ${e.message}", e)
            )
        }

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                Timber.d("ttsExport chunk done id=$utteranceId")
                done.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Timber.w("ttsExport chunk error id=$utteranceId code=$errorCode")
                done.completeExceptionally(
                    TtsExportException("Synthesis failed (error $errorCode) for '$chapterTitle'")
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Timber.w("ttsExport chunk error (deprecated) id=$utteranceId")
                done.completeExceptionally(
                    TtsExportException("Synthesis failed for '$chapterTitle'")
                )
            }

            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (sampleRateInHz <= 0 || channelCount <= 0) {
                    Timber.w("ttsExport invalid synthesis format id=$utteranceId rate=$sampleRateInHz ch=$channelCount")
                    return
                }
                if (!writer.isValidPcmFormat(audioFormat)) {
                    Timber.w("ttsExport unsupported audioFormat=$audioFormat (only PCM16 supported)")
                    return
                }
                try {
                    writer.open(sampleRateInHz, channelCount)
                    timelineBuilder.setSliceFormat(sampleRateInHz, channelCount)
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
                    timelineBuilder.onAudioAvailable(audio.size)
                    writer.writePcm(audio)
                } catch (e: AudioTooLargeException) {
                    Timber.e(e, "ttsExport WAV exceeds 4GB id=$utteranceId")
                    done.completeExceptionally(TtsExportException(e.message ?: "WAV too large", e))
                } catch (e: Exception) {
                    Timber.e(e, "ttsExport writing PCM failed id=$utteranceId")
                    done.completeExceptionally(
                        TtsExportException("Failed writing audio data: ${e.message}", e)
                    )
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                try {
                    timelineBuilder.onRangeStart(start, end, frame)
                } catch (e: Throwable) {
                    failTimeline(e)
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
