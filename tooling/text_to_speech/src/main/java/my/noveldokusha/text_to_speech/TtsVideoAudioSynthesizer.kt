package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

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
        val chunkCursors = HashMap<Int, Int>()
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
                coroutineContext.ensureActive()
                val sourceBlock = blocks.getOrNull(blockIndex) ?: text
                val cleanMapped = cleanForTtsMapped(sourceBlock)
                val searchFrom = chunkCursors[blockIndex] ?: 0
                val chunkStart = cleanMapped.text.indexOf(text, searchFrom)
                if (chunkStart < 0) throw TtsExportException("Unable to map TTS chunk $chunkIndex back to source text")
                chunkCursors[blockIndex] = chunkStart + text.length
                val preparedMapped = TtsVideoTextMapper.substring(cleanMapped, chunkStart, chunkStart + text.length)
                val mapping = VideoDisplayMapping(
                    sourceText = sourceBlock,
                    preparedText = preparedMapped,
                    displayText = TtsVideoTextMapper.identity(sourceBlock),
                    blockId = "$blockIndex:$chunkIndex",
                )
                val before = writer.dataBytesWritten()
                val captured = synthesizeChunk(tts, sink, writer, text, request.chapterTitle)
                val bytes = writer.dataBytesWritten() - before
                if (bytes <= 0L) throw TtsExportException("TTS synthesis produced zero PCM for chunk $chunkIndex")
                sampleRate = writer.sampleRate() ?: throw TtsExportException("TTS sample rate unavailable")
                channels = writer.channelCount() ?: throw TtsExportException("TTS channel count unavailable")
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
        val cleaned = TtsTextPreparer.cleanForTts(source)
        if (cleaned.isEmpty()) return MappedText("", emptyList())
        val provenance = ArrayList<TextProvenance>()
        var sourceCursor = 0
        var outputCursor = 0
        var rangeSourceStart = -1
        var rangeOutputStart = -1
        var previousSource = -2
        for (i in cleaned.indices) {
            val target = cleaned[i]
            while (sourceCursor < source.length && source[sourceCursor] != target) sourceCursor++
            if (sourceCursor >= source.length) throw TtsExportException("Unable to preserve TTS cleanup provenance")
            if (rangeSourceStart < 0) {
                rangeSourceStart = sourceCursor
                rangeOutputStart = outputCursor
            } else if (sourceCursor != previousSource + 1) {
                provenance += TextProvenance(rangeOutputStart, outputCursor, rangeSourceStart, previousSource + 1)
                rangeSourceStart = sourceCursor
                rangeOutputStart = outputCursor
            }
            previousSource = sourceCursor
            sourceCursor++
            outputCursor++
        }
        if (rangeSourceStart >= 0) provenance += TextProvenance(rangeOutputStart, outputCursor, rangeSourceStart, previousSource + 1)
        return MappedText(cleaned, provenance)
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
                if (!done.isActive || audio.isEmpty()) return
                runCatching { writer.writePcm(audio) }.onFailure {
                    callbackError = it
                    done.completeExceptionally(it)
                }
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        val descriptor = ParcelFileDescriptor.open(sink, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        try {
            done.invokeOnCompletion { cause -> if (cause is CancellationException) runCatching { tts.stop() } }
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
