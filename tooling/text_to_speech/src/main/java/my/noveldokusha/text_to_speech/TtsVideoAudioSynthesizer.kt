package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsVideoAudioSynthesizer(private val context: Context) {
    suspend fun synthesize(request: TtsVideoRequest, blocks: List<String>, output: File, onChunkProgress: (Float) -> Unit = {}): VideoSynthesisResult {
        require(blocks.isNotEmpty())
        val tts = createTts(request.enginePackage)
        val writer = WavWriter(output)
        val sink = File(context.cacheDir, "tts_video_sink_${request.jobId}.bin")
        val maxInput = TextToSpeech.getMaxSpeechInputLength()
        val chunks = blocks.flatMapIndexed { blockIndex, block ->
            val cleaned = TtsTextPreparer.cleanForTts(block)
            if (TtsTextPreparer.isOnlyDecorators(cleaned)) emptyList()
            else TtsTextPreparer.chunkIntoUtterances(cleaned, maxInput).mapIndexed { chunkIndex, text -> Triple(blockIndex, chunkIndex, text) }
        }
        if (chunks.isEmpty()) throw TtsExportException("No speakable text in chapter")
        val total = chunks.sumOf { it.third.length }.coerceAtLeast(1)
        val timings = ArrayList<TtsChunkTiming>(chunks.size)
        var audioUs = 0L
        var chars = 0
        var sampleRate = 0
        var channels = 0
        try {
            val voice = tts.voices?.firstOrNull { it.name == request.voiceId } ?: throw TtsExportException("Voice '${request.voiceId}' not found")
            tts.voice = voice
            tts.setSpeechRate(request.speed)
            tts.setPitch(request.pitch)
            for ((blockIndex, chunkIndex, text) in chunks) {
                val sourceBlock = blocks.getOrNull(blockIndex) ?: text
                val cleanMapped = cleanForTtsMapped(sourceBlock)
                val chunkStart = cleanMapped.text.indexOf(text, 0)
                if (chunkStart < 0) throw TtsExportException("Unable to map TTS chunk back to source text")
                val preparedMapped = TtsVideoTextMapper.substring(cleanMapped, chunkStart, chunkStart + text.length)
                val mapping = VideoDisplayMapping(
                    sourceText = sourceBlock,
                    preparedText = preparedMapped,
                    displayText = TtsVideoTextMapper.identity(sourceBlock),
                    blockId = "$blockIndex:$chunkIndex",
                )
                val before = writer.dataBytesWritten()
                val captured = synthesizeChunk(tts, sink, writer, text, request.chapterTitle)
                sampleRate = writer.sampleRate() ?: throw TtsExportException("TTS sample rate unavailable")
                channels = writer.channelCount() ?: throw TtsExportException("TTS channel count unavailable")
                val bytes = writer.dataBytesWritten() - before
                val duration = bytes * 1_000_000L / (sampleRate.toLong() * channels * 2L)
                val start = audioUs
                val end = start + maxOf(1L, duration)
                timings += TtsChunkTiming(blockIndex, chunkIndex, text, mapping, start, end, captured.map { it.copy(blockIndex = blockIndex, chunkIndex = chunkIndex) })
                audioUs = end
                chars += text.length
                onChunkProgress(chars.toFloat() / total)
            }
            writer.finish()
            return VideoSynthesisResult(output, audioUs, timings, sampleRate, channels)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            runCatching { writer.close() }
            runCatching { output.delete() }
            throw e
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
            runCatching { sink.delete() }
        }
    }

    private fun cleanForTtsMapped(source: String): MappedText {
        var mapped = TtsVideoTextMapper.identity(source)
        val lines = source.split("\n", ignoreCase = false, limit = -1)
        var offset = 0
        for (line in lines) {
            var start = offset
            var end = offset + line.length
            val leading = TtsTextPreparer.leadingDecoratorLength(line)
            if (leading > 0) mapped = TtsVideoTextMapper.replaceRange(mapped, start, start + leading, "")
            // Keep the same regex semantics as TtsTextPreparer.cleanForTts for trailing decorators.
            val trailingMatch = Regex("\\s*[-=*_~+#·•°─┿]{3,}$").find(line)
            if (trailingMatch != null && trailingMatch.range.first < line.length) {
                val currentEnd = end - leading
                val trailingStart = offset + trailingMatch.range.first - leading
                val trailingEnd = offset + line.length - leading
                if (trailingStart < trailingEnd) mapped = TtsVideoTextMapper.replaceRange(mapped, trailingStart, trailingEnd, "")
            }
            // Remove whitespace introduced by edge removal only in the same way as the shared cleaner.
            val trimmed = TtsVideoTextMapper.trim(mapped)
            mapped = trimmed
            offset = source.length.coerceAtMost(offset + line.length + 1)
        }
        return TtsVideoTextMapper.trim(mapped)
    }

    private suspend fun synthesizeChunk(tts: TextToSpeech, sink: File, writer: WavWriter, text: String, title: String): List<TtsRangeEvent> {
        val done = CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(mutableListOf<TtsRangeEvent>())
        var callbackError: Throwable? = null
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (start < end && frame >= 0) events += TtsRangeEvent(0, 0, start, end, frame)
            }
            override fun onDone(utteranceId: String?) { if (done.isActive) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                val e = TtsExportException("Synthesis failed for '$title' ($errorCode)")
                callbackError = e
                if (done.isActive) done.completeExceptionally(e)
            }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                val e = TtsExportException("Synthesis failed for '$title'")
                callbackError = e
                if (done.isActive) done.completeExceptionally(e)
            }
            override fun onBeginSynthesis(utteranceId: String?, rate: Int, audioFormat: Int, channelCount: Int) {
                if (rate <= 0 || channelCount <= 0 || !writer.isValidPcmFormat(audioFormat)) {
                    val e = TtsExportException("Unsupported TTS PCM format: rate=$rate channels=$channelCount encoding=$audioFormat")
                    callbackError = e
                    done.completeExceptionally(e)
                    return
                }
                runCatching { writer.open(rate, channelCount) }.onFailure {
                    callbackError = it
                    done.completeExceptionally(it)
                }
            }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (audio.isEmpty()) return
                runCatching { writer.writePcm(audio) }.onFailure {
                    callbackError = it
                    done.completeExceptionally(it)
                }
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        val descriptor = ParcelFileDescriptor.open(
            sink,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        try {
            val id = "video_${System.nanoTime()}"
            val bundle = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            if (tts.synthesizeToFile(text, bundle, descriptor, id) != TextToSpeech.SUCCESS) {
                throw TtsExportException("synthesizeToFile rejected input")
            }
            done.await()
            callbackError?.let { throw it }
            return events.toList()
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private suspend fun createTts(engine: String): TextToSpeech = suspendCancellableCoroutine { cont ->
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext, { status ->
            if (!cont.isActive) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) cont.resume(tts)
            else cont.resumeWithException(TtsExportException("TTS engine '$engine' init failed: status=$status"))
        }, engine.ifBlank { null })
        cont.invokeOnCancellation {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }
}

data class VideoSynthesisResult(val wavFile: File, val durationUs: Long, val chunks: List<TtsChunkTiming>, val sampleRate: Int, val channelCount: Int)
