package my.noveldokusha.video_export

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import my.noveldokusha.text_to_speech.TtsExportException
import my.noveldokusha.text_to_speech.TtsTextPreparer
import my.noveldokusha.text_to_speech.WavWriter
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ОДНОПРОХОДНЫЙ сбор аудио и таймингов для видео-экспорта.
 *
 * За каждый кусок вызывается ровно один synthesizeToFile, и тот же слушатель
 * ловит onAudioAvailable (PCM -> WAV) и onRangeStart (образец-точные тайминги).
 * onBeginSynthesis даёт sampleRate/channels куска.
 *
 * Тайминг семпла точен: frame дан в частоте синтеза, а кодируемый аудио — ровно
 * захваченный PCM. Живая калибровка из читалки не нужна и не копируется.
 */
class TtsTimelineCollector(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Синтезирует [displayParagraphs] и возвращает Pair<tempWav, timeline>.
     *
     * @param displayParagraphs абзацы ТОЧНО так, как они будут рендериться.
     */
    suspend fun collectTimeline(
        displayParagraphs: List<String>,
        enginePackage: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        destFile: File,
        onProgress: (Float) -> Unit = {},
    ): Pair<File, VideoExportTimeline> {
        if (displayParagraphs.isEmpty()) {
            throw TtsExportException("Chapter has no text to synthesize")
        }

        val throwawaySink = File(appContext.cacheDir, "tts_video_sink.bin")
        val writer = WavWriter(destFile)
        val tts = createDedicatedTts(enginePackage)

        val wordTimingsByParagraph = MutableList<MutableList<WordTiming>>(displayParagraphs.size) { mutableListOf() }
        val paragraphStartSample = LongArray(displayParagraphs.size) { -1L }
        val paragraphEndSample = LongArray(displayParagraphs.size) { -1L }

        var sampleRate = 0
        var channelCount = 0
        var properFormatSeen = false
        var finished = false
        try {
            val syntheInputLength = TextToSpeech.getMaxSpeechInputLength()
            val voices = tts.voices ?: emptyList()
            val voice = voices.find { it.name == voiceId }
                ?: throw TtsExportException("Voice '$voiceId' not found in engine '$enginePackage'")
            tts.voice = voice
            tts.setSpeechRate(speed)
            tts.setPitch(pitch)

            // План синтеза: для каждого абзаца — карта char-офсетов и куски. Текст
            // каждого куска запоминаем явно, чтобы не строить по индексам повторно.
            val chunkPlans = buildList {
                for ((paraIndex, display) in displayParagraphs.withIndex()) {
                    val cleanedWithMap = TtsTextPreparer.cleanForTtsWithMap(display)
                    val cleaned = cleanedWithMap.cleaned
                    if (TtsTextPreparer.isOnlyDecorators(cleaned)) continue
                    val chunks = TtsTextPreparer.chunkIntoUtterances(cleaned, syntheInputLength)
                    var offset = 0
                    for (chunk in chunks) {
                        add(
                            ChunkPlan(
                                paraIndex = paraIndex,
                                map = cleanedWithMap.map,
                                text = chunk,
                                cleanedStart = offset,
                                cleanedEnd = offset + chunk.length,
                            )
                        )
                        offset += chunk.length
                    }
                }
            }
            val totalChars = chunkPlans.sumOf { it.text.length }
            var absoluteSample = 0L
            var synthesizedAny = false
            var currentPara = -1
            var processedChars = 0

            for (plan in chunkPlans) {
                if (plan.paraIndex != currentPara) {
                    currentPara = plan.paraIndex
                    paragraphStartSample[currentPara] = absoluteSample
                }
                // Прогресс-вес куска — его необработанная длина на общий объём.
                val afterChars = processedChars + plan.text.length
                synthesizeChunk(
                    tts = tts,
                    text = plan.text,
                    throwawaySink = throwawaySink,
                    writer = writer,
                    chapterTitle = "video export",
                    plan = plan,
                    wordTimings = wordTimingsByParagraph[plan.paraIndex],
                    onFormat = { rate, ch ->
                        if (!properFormatSeen) {
                            sampleRate = rate
                            channelCount = ch
                            properFormatSeen = true
                        }
                    },
                    onChunkAdvanced = { writtenBytes -> absoluteSample = writtenBytes },
                )
                synthesizedAny = if (writer.dataBytesWritten() > 0) true else synthesizedAny
                processedChars = afterChars
                onProgress(
                    if (totalChars > 0) processedChars.toFloat() / totalChars.toFloat() else 1f
                )
            }

            if (!properFormatSeen) {
                throw TtsExportException("Video export: no synthesis format reported")
            }
            if (!synthesizedAny) {
                throw TtsExportException("Video export: no audio was produced")
            }

            writer.finish()
            finished = true
            val totalSamples = writer.dataBytesWritten() / (channelCount * 2L)

            // Закрыть верхние границы абзацев (начало следующего активного абзаца,
            // либо конец всего аудио) — по возрастанию startSample.
            val activeParagraphIndices = displayParagraphs.indices.filter { paragraphStartSample[it] >= 0 }
            for ((k, paraIndex) in activeParagraphIndices.withIndex()) {
                val nextActiveStart = activeParagraphIndices.getOrNull(k + 1)?.let { paragraphStartSample[it] }
                paragraphEndSample[paraIndex] = nextActiveStart ?: totalSamples
            }
            // Для абзацев без синтеза (только декораторы) — пустой диапазон.
            for (i in displayParagraphs.indices) {
                if (paragraphStartSample[i] < 0) {
                    paragraphStartSample[i] = totalSamples
                    paragraphEndSample[i] = totalSamples
                }
            }

            val paragraphs = displayParagraphs.mapIndexed { i, display ->
                ParagraphTiming(
                    displayText = display,
                    cleanedText = TtsTextPreparer.cleanForTts(display),
                    startSample = paragraphStartSample[i],
                    endSample = paragraphEndSample[i],
                    wordTimings = wordTimingsByParagraph[i].sortedBy { it.samplePosition },
                )
            }

            val timeline = VideoExportTimeline(
                sampleRate = sampleRate,
                channelCount = channelCount,
                totalSamples = totalSamples,
                paragraphs = paragraphs,
            )
            return destFile to timeline
        } finally {
            try {
                tts.stop()
            } catch (_: Throwable) {
            }
            runCatching { tts.shutdown() }
            if (!finished) runCatching { writer.close() }
            runCatching { throwawaySink.delete() }
        }
    }

    private data class ChunkPlan(
        val paraIndex: Int,
        val map: IntArray,
        val text: String,
        val cleanedStart: Int,
        val cleanedEnd: Int,
    )

    private suspend fun createDedicatedTts(enginePackage: String): TextToSpeech =
        suspendCancellableCoroutine { cont ->
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
            tts = TextToSpeech(appContext, listener, enginePackage.ifBlank { null })
            cont.invokeOnCancellation {
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
        }

    /**
     * Синтезирует один кусок.
     * [onFormat] сообщает формат при первом onBeginSynthesis (rate/channels).
     * [onChunkAdvanced] вызывается после завершения куска с накопленными записанными
     * байтами — чтобы ведущий поддерживал absoluteSample для следующего куска.
     */
    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        throwawaySink: File,
        writer: WavWriter,
        chapterTitle: String,
        plan: ChunkPlan,
        wordTimings: MutableList<WordTiming>,
        onFormat: (rate: Int, channels: Int) -> Unit,
        onChunkAdvanced: (writtenBytes: Long) -> Unit,
    ) {
        val done = CompletableDeferred<Unit>()
        var chunkStartSample = 0L

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                done.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Timber.w("ttsVideo chunk error id=$utteranceId code=$errorCode")
                done.completeExceptionally(
                    TtsExportException("Video synthesis failed (error $errorCode) for '$chapterTitle'")
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Timber.w("ttsVideo chunk error (deprecated) id=$utteranceId")
                done.completeExceptionally(
                    TtsExportException("Video synthesis failed for '$chapterTitle'")
                )
            }

            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (sampleRateInHz <= 0 || channelCount <= 0) {
                    Timber.w("ttsVideo invalid format id=$utteranceId rate=$sampleRateInHz ch=$channelCount")
                    return
                }
                if (!writer.isValidPcmFormat(audioFormat)) {
                    Timber.w("ttsVideo unsupported audioFormat=$audioFormat (only PCM16)")
                    return
                }
                try {
                    writer.open(sampleRateInHz, channelCount)
                    onFormat(sampleRateInHz, channelCount)
                } catch (e: Exception) {
                    Timber.e(e, "ttsVideo opening WAV failed id=$utteranceId")
                    done.completeExceptionally(TtsExportException("Failed initializing audio writer: ${e.message}", e))
                }
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (audio.isEmpty()) return
                try {
                    writer.writePcm(audio)
                    chunkStartSample = writer.dataBytesWritten() / (writer.channelCount() * 2L)
                } catch (e: Exception) {
                    Timber.e(e, "ttsVideo writing PCM failed id=$utteranceId")
                    done.completeExceptionally(
                        TtsExportException("Failed writing audio data: ${e.message}", e)
                    )
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                try {
                    addWordTiming(plan, wordTimings, chunkStartSample, start, end, frame)
                } catch (e: Exception) {
                    Timber.w(e, "ttsVideo onRangeStart mapping failed id=$utteranceId")
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
            val utteranceId = "${plan.paraIndex}|0|${plan.cleanedStart}"
            val bundle = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = tts.synthesizeToFile(text, bundle, descriptor, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                done.completeExceptionally(
                    TtsExportException("synthesizeToFile rejected input (result=$result)")
                )
            }
            done.await()
        } finally {
            onChunkAdvanced(writer.dataBytesWritten())
            runCatching { descriptor?.close() }
        }
    }

    /** (start, end, frame) уже в координатах очищенного текста куска. */
    private fun addWordTiming(
        plan: ChunkPlan,
        out: MutableList<WordTiming>,
        chunkStartSample: Long,
        start: Int,
        end: Int,
        frame: Int,
    ) {
        val range = TimingMapper.displayRange(plan.map, plan.cleanedStart, start, end) ?: return
        out += WordTiming(
            displayRange = range,
            samplePosition = TimingMapper.absoluteSample(chunkStartSample, frame),
            isApproximate = false,
        )
    }
}
