package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Export-only TTS synthesis. Cancellation stops the leased export client immediately;
 * reader/live TTS uses a separate client and is unaffected.
 */
class TtsAudioExporter(private val context: Context) {
    suspend fun exportAudio(
        request: TtsAudioExportRequest,
        paragraphs: List<String>,
        destFile: File,
        audioFileName: String,
        onProgress: (Float) -> Unit = {},
    ): TtsAudioExportResult {
        if (paragraphs.isEmpty()) throw TtsExportException("Chapter has no text to synthesize")
        val writer = WavWriter(destFile)
        val timelineBuilder = TtsTimelineBuilder()
        timelineBuilder.beginChapter(request.novelTitle, request.chapterTitle, request.chapterIndex, request.source.name)
        val liveEnginePackage = AppTtsEngine.getInstance(context).getBoundEnginePackage().orEmpty()
        val resolvedEngine = TtsEngineCatalog.resolveForExport(context, request.enginePackage, request.voiceId, liveEnginePackage)
        val effectiveRequest = request.copy(enginePackage = resolvedEngine.enginePackage, voiceId = resolvedEngine.voiceId)
        val lease = TtsAudioEnginePool.acquire(context, effectiveRequest.enginePackage)
        var finished = false
        try {
            val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
            val novelTitle = TtsTextPreparer.cleanForTts(effectiveRequest.novelTitle).trim()
            val chapterTitle = TtsTextPreparer.cleanForTts(effectiveRequest.chapterTitle).trim()
            val paragraphPlans = buildList {
                if (novelTitle.isNotBlank() && !TtsTextPreparer.isOnlyDecorators(novelTitle)) add(novelTitle to TtsTextPreparer.chunkIntoUtterances(novelTitle, maxInputLength))
                if (chapterTitle.isNotBlank() && !TtsTextPreparer.isOnlyDecorators(chapterTitle)) add(chapterTitle to TtsTextPreparer.chunkIntoUtterances(chapterTitle, maxInputLength))
                for (paragraph in paragraphs) {
                    val cleaned = TtsTextPreparer.cleanForTts(paragraph)
                    if (!TtsTextPreparer.isOnlyDecorators(cleaned)) add(cleaned to TtsTextPreparer.chunkIntoUtterances(cleaned, maxInputLength))
                }
            }
            if (paragraphPlans.isEmpty()) throw TtsExportException("Chapter has no text to synthesize")
            val totalChars = paragraphPlans.sumOf { it.first.length }
            var processedChars = 0
            var chunkIndex = 0
            var synthesizedAny = false
            for ((_, slices) in paragraphPlans) {
                timelineBuilder.beginParagraph()
                for (chunk in slices) {
                    configureTts(lease.tts, effectiveRequest)
                    timelineBuilder.registerSlice(chunk)
                    synthesizeChunk(
                        tts = lease.tts,
                        text = chunk,
                        writer = writer,
                        chapterTitle = effectiveRequest.chapterTitle,
                        timelineBuilder = timelineBuilder,
                        utteranceId = "tts_export_${effectiveRequest.jobId}_$chunkIndex",
                    )
                    chunkIndex++
                    processedChars += chunk.length
                    synthesizedAny = synthesizedAny || writer.dataBytesWritten() > 0
                    onProgress(if (totalChars > 0) processedChars.toFloat() / totalChars else 1f)
                }
                timelineBuilder.endParagraph()
            }
            if (!synthesizedAny) throw TtsExportException("No audio was produced for chapter '${effectiveRequest.chapterTitle}'")
            writer.finish()
            val sampleRate = writer.sampleRate()
            val channels = writer.channels()
            if (sampleRate <= 0 || channels <= 0) throw TtsExportException("Chapter '${effectiveRequest.chapterTitle}' has no valid audio format")
            val durationMs = ((writer.dataBytesWritten().toDouble() * 1000.0) / (sampleRate.toDouble() * channels.toDouble() * 2.0)).toInt()
            val timeline = timelineBuilder.build(audioFileName, sampleRate, channels, durationMs)
            val rangeCount = timeline.paragraphs.sumOf { it.ranges.size }
            if (rangeCount == 0) throw TtsExportException("TTS engine '${effectiveRequest.enginePackage}' produced audio but no native range timing data")
            finished = true
            return TtsAudioExportResult(destFile, timeline)
        } finally {
            lease.close()
            if (!finished) runCatching { writer.close() }
        }
    }

    private fun configureTts(tts: TextToSpeech, request: TtsAudioExportRequest) {
        val voice = tts.voices?.find { it.name == request.voiceId }
            ?: throw TtsExportException("Voice '${request.voiceId}' not found in engine '${request.enginePackage}'")
        tts.voice = voice
        if (tts.setSpeechRate(request.speed) != TextToSpeech.SUCCESS) throw TtsExportException("Failed to set TTS speech rate")
        if (tts.setPitch(request.pitch) != TextToSpeech.SUCCESS) throw TtsExportException("Failed to set TTS pitch")
    }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        writer: WavWriter,
        chapterTitle: String,
        timelineBuilder: TtsTimelineBuilder,
        utteranceId: String,
    ) {
        val done = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val normalizer = TtsNativeRangeNormalizer(text.length)
        val scratchFile = File.createTempFile("tts_export_", ".wav", context.cacheDir)
        fun failOnce(error: Throwable) { if (!done.isCompleted) done.completeExceptionally(error) }
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (!cancelled.get()) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (!cancelled.get()) failOnce(TtsExportException("Synthesis failed (error $errorCode) for '$chapterTitle'")) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (!cancelled.get()) failOnce(TtsExportException("Synthesis failed for '$chapterTitle'")) }
            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (cancelled.get()) return
                if (sampleRateInHz <= 0 || channelCount <= 0) return failOnce(TtsExportException("TTS engine returned invalid audio format"))
                if (!writer.isValidPcmFormat(audioFormat)) return failOnce(TtsExportException("TTS engine returned unsupported audio format"))
                runCatching { writer.open(sampleRateInHz, channelCount); timelineBuilder.setSliceFormat(sampleRateInHz, channelCount) }
                    .onFailure { failOnce(TtsExportException("Failed initializing audio writer: ${it.message}", it)) }
            }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (cancelled.get() || audio.isEmpty()) return
                runCatching { timelineBuilder.onAudioAvailable(audio.size); writer.writePcm(audio) }
                    .onFailure { failOnce(TtsExportException("Failed writing audio data: ${it.message}", it)) }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (cancelled.get()) return
                runCatching {
                    val r = normalizer.normalize(start, end, frame) ?: throw TtsExportException("Invalid native TTS range callback")
                    timelineBuilder.onRangeStart(r.start, r.end, r.frame)
                }.onFailure { failOnce(TtsExportException("Failed capturing native TTS timing: ${it.message}", it)) }
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        if (tts.synthesizeToFile(text, Bundle(), scratchFile, utteranceId) != TextToSpeech.SUCCESS) {
            failOnce(TtsExportException("synthesizeToFile rejected input"))
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            cancelled.set(true)
            runCatching { tts.stop() }
            throw e
        } finally {
            runCatching { scratchFile.delete() }
        }
    }
}

internal data class NormalizedTtsRange(val start: Int, val end: Int, val frame: Int)

internal class TtsNativeRangeNormalizer(private val textLength: Int) {
    private enum class Order { UNKNOWN, NORMAL, SWAPPED }
    private var order = Order.UNKNOWN
    fun normalize(start: Int, end: Int, frame: Int): NormalizedTtsRange? {
        val normalValid = valid(start, end)
        val swappedValid = valid(end, frame)
        val swappedClearly = swappedValid && (start >= textLength || frame > textLength || !normalValid)
        if (order == Order.UNKNOWN) order = when {
            swappedClearly -> Order.SWAPPED
            normalValid -> Order.NORMAL
            swappedValid -> Order.SWAPPED
            else -> Order.UNKNOWN
        }
        return when (order) {
            Order.NORMAL -> if (normalValid) NormalizedTtsRange(start, end, frame) else if (swappedValid) { order = Order.SWAPPED; NormalizedTtsRange(end, frame, start) } else null
            Order.SWAPPED -> if (swappedValid) NormalizedTtsRange(end, frame, start) else if (normalValid) { order = Order.NORMAL; NormalizedTtsRange(start, end, frame) } else null
            Order.UNKNOWN -> null
        }
    }
    private fun valid(start: Int, end: Int) = start >= 0 && start < end && end <= textLength
}

class TtsExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
