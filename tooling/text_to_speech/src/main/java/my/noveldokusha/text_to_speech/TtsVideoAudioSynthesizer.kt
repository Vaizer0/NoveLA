package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One dedicated TTS instance per video export; synthesis and range capture happen in one pass. */
class TtsVideoAudioSynthesizer(private val context: Context) {
    suspend fun synthesize(
        request: TtsVideoRequest,
        blocks: List<String>,
        output: File,
        onChunkProgress: (Float) -> Unit = {},
    ): VideoSynthesisResult {
        require(blocks.isNotEmpty())
        val tts = createTts(request.enginePackage)
        val writer = WavWriter(output)
        val sink = File(context.cacheDir, "tts_video_sink_${request.jobId}.bin")
        val maxInput = TextToSpeech.getMaxSpeechInputLength()
        val chunks = blocks.flatMapIndexed { blockIndex, block ->
            val cleaned = TtsTextPreparer.cleanForTts(block)
            if (TtsTextPreparer.isOnlyDecorators(cleaned)) emptyList()
            else TtsTextPreparer.chunkIntoUtterances(cleaned, maxInput).mapIndexed { chunkIndex, text ->
                Triple(blockIndex, chunkIndex, text)
            }
        }
        if (chunks.isEmpty()) throw TtsExportException("No speakable text in chapter")
        val total = chunks.sumOf { it.third.length }.coerceAtLeast(1)
        val timings = ArrayList<TtsChunkTiming>(chunks.size)
        var audioUs = 0L
        var chars = 0
        try {
            val voice = tts.voices?.firstOrNull { it.name == request.voiceId }
                ?: throw TtsExportException("Voice '${request.voiceId}' not found")
            tts.voice = voice; tts.setSpeechRate(request.speed); tts.setPitch(request.pitch)
            for ((blockIndex, chunkIndex, text) in chunks) {
                val mapping = VideoDisplayMapping(text, TtsVideoTextMapper.identity(text), TtsVideoTextMapper.identity(text), "$blockIndex:$chunkIndex")
                val captured = synthesizeChunk(tts, sink, writer, text, request.chapterTitle)
                val sampleRate = writer.sampleRate() ?: throw TtsExportException("TTS did not report a sample rate")
                val start = audioUs
                val duration = writer.dataBytesWritten() * 1_000_000L / (sampleRate.toLong() * (writer.channelCount() ?: 1) * 2L)
                audioUs += maxOf(1L, duration - start.coerceAtMost(duration))
                val end = audioUs
                timings += TtsChunkTiming(blockIndex, chunkIndex, text, mapping, start, end, captured)
                chars += text.length
                onChunkProgress(chars.toFloat() / total)
            }
            writer.finish()
            return VideoSynthesisResult(output, audioUs, timings, writer.sampleRate() ?: 0, writer.channelCount() ?: 0)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            runCatching { writer.close() }
            runCatching { output.delete() }
            throw e
        } finally {
            runCatching { tts.stop() }; runCatching { tts.shutdown() }; runCatching { sink.delete() }
        }
    }

    private suspend fun synthesizeChunk(tts: TextToSpeech, sink: File, writer: WavWriter, text: String, title: String): List<TtsRangeEvent> {
        val done = CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(mutableListOf<TtsRangeEvent>())
        var sampleRate = 0
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                events += TtsRangeEvent(0, 0, start, end, frame)
            }
            override fun onDone(utteranceId: String?) { if (done.isActive) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (done.isActive) done.completeExceptionally(TtsExportException("Synthesis failed for '$title' ($errorCode)")) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (done.isActive) done.completeExceptionally(TtsExportException("Synthesis failed for '$title'")) }
            override fun onBeginSynthesis(utteranceId: String?, rate: Int, audioFormat: Int, channelCount: Int) {
                sampleRate = rate
                if (rate <= 0 || channelCount <= 0 || !writer.isValidPcmFormat(audioFormat)) {
                    done.completeExceptionally(TtsExportException("Unsupported TTS PCM format")); return
                }
                runCatching { writer.open(rate, channelCount) }.onFailure { done.completeExceptionally(it) }
            }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                if (audio.isNotEmpty()) runCatching { writer.writePcm(audio) }.onFailure { done.completeExceptionally(it) }
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        val descriptor = ParcelFileDescriptor.open(sink, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        try {
            val id = "video_${System.identityHashCode(text)}"
            val bundle = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            val result = tts.synthesizeToFile(text, bundle, descriptor, id)
            if (result != TextToSpeech.SUCCESS) throw TtsExportException("synthesizeToFile rejected input")
            done.await()
            return events.map { it.copy(blockIndex = 0, chunkIndex = 0) }
        } finally { runCatching { descriptor.close() } }
    }

    private suspend fun createTts(engine: String): TextToSpeech = suspendCancellableCoroutine { cont ->
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext, { status ->
            if (!cont.isActive) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) cont.resume(tts)
            else cont.resumeWithException(TtsExportException("TTS engine '$engine' init failed"))
        }, engine.ifBlank { null })
        cont.invokeOnCancellation { runCatching { tts.stop() }; runCatching { tts.shutdown() } }
    }
}

data class VideoSynthesisResult(val wavFile: File, val durationUs: Long, val chunks: List<TtsChunkTiming>, val sampleRate: Int, val channelCount: Int)
