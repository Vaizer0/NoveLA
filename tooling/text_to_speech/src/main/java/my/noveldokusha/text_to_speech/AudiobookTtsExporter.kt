package my.noveldokusha.text_to_speech

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

data class AudiobookExportRequest(
    val bookTitle: String,
    val contentMode: String,
    val sourceLang: String,
    val targetLang: String,
    val startPosition: Int,
    val endPosition: Int,
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    val outputFormat: OutputFormat,
    val visualUri: Uri?,
)

enum class OutputFormat { WAV, MP4 }

data class AudiobookChapterData(
    val position: Int,
    val url: String,
    val title: String,
    val speechTitle: String,
    val paragraphs: List<String>,
)

data class AudiobookExportProgress(
    val currentChapter: Int,
    val totalChapters: Int,
    val chapterTitle: String,
    val percent: Int,
    val elapsedMs: Long,
    val estimatedRemainingMs: Long?,
    val generatedAudioMs: Long,
)

private data class SpeechSegment(
    val chapterPosition: Int,
    val chapterUrl: String,
    val type: String,
    val text: String,
)

private data class WordTiming(
    val startChar: Int,
    val endChar: Int,
    val startMs: Long,
    val endMs: Long,
)

private data class CachedReaderWordTiming(
    val start: Int,
    val end: Int,
    val startMs: Long,
    val durationMs: Long,
    val speed: Float,
)

private const val READER_TIMING_PREFS = "tts_preferences"
private const val READER_TIMING_STORE = "tts_word_highlight_timing_json_v2"

private fun readerWordTimingCacheKey(
    enginePackage: String,
    voiceId: String,
    needsInternet: Boolean,
    language: String,
    pitch: Float,
    text: String,
): String {
    val material = buildString {
        append("word_timing_v2|")
        append(enginePackage).append('|')
        append(voiceId).append('|')
        append(needsInternet).append('|')
        append(language).append('|')
        append(pitch).append('|')
        append(text)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
    return "word_timing_v2_" + digest.joinToString("") { "%02x".format(it) }
}

private fun readReaderWordTimings(
    context: Context,
    cacheKey: String,
): List<CachedReaderWordTiming> =
    runCatching {
        val prefs = context.getSharedPreferences(READER_TIMING_PREFS, Context.MODE_PRIVATE)
        val root = JSONObject(prefs.getString(READER_TIMING_STORE, "{}") ?: "{}")
        val array = root.optJSONArray(cacheKey)
            ?: root.optJSONObject(cacheKey)?.optJSONArray("timings")
            ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optInt("start", -1)
                val end = item.optInt("end", -1)
                val startMs = item.optLong("start_ms", -1L)
                val durationMs = item.optLong("duration_ms", -1L)
                val speed = item.optDouble("speed", Double.NaN).toFloat()
                if (start >= 0 && end > start && startMs >= 0L &&
                    durationMs in 40L..15_000L && speed.isFinite() && speed > 0f
                ) {
                    add(CachedReaderWordTiming(start, end, startMs, durationMs, speed))
                }
            }
        }
    }.getOrDefault(emptyList())

private data class ChapterTiming(
    val chapter: AudiobookChapterData,
    val startMs: Long,
    val endMs: Long,
)

private class ExportSliceState(
    val id: String,
) {
    val pcmQueue = Channel<ByteArray>(Channel.UNLIMITED)
    val formatReady = CountDownLatch(1)
    val done = CountDownLatch(1)
    val error = AtomicReference<Throwable?>(null)
    val audioBytesReceived = AtomicLong(0L)

    @Volatile var sampleRate: Int = 0
    @Volatile var audioFormat: Int = 0
    @Volatile var channels: Int = 0
}

private interface AudioSink : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    var totalFrames: Long
    fun writePcm16(bytes: ByteArray)
    fun finish()
}

private class WavSink(
    private val file: File,
    override val sampleRate: Int,
    override val channels: Int,
) : AudioSink {
    private val out = java.io.BufferedOutputStream(FileOutputStream(file, false), 256 * 1024)
    private var dataBytes = 0L
    override var totalFrames = 0L
    private var finished = false

    init {
        repeat(44) { out.write(0) }
    }

    @Synchronized
    override fun writePcm16(bytes: ByteArray) {
        check(!finished)
        out.write(bytes)
        dataBytes += bytes.size
        totalFrames += bytes.size.toLong() / (channels * 2L)
    }

    @Synchronized
    override fun finish() {
        if (finished) return
        require(dataBytes <= 0xFFFF_FFFFL - 36L) { "WAV exceeds RIFF size limit" }
        out.flush()
        out.close()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            fun ascii(value: String) = raf.write(value.toByteArray(Charsets.US_ASCII))
            fun u16(value: Int) {
                raf.write(value and 255)
                raf.write((value ushr 8) and 255)
            }
            fun u32(value: Long) {
                raf.write((value and 255).toInt())
                raf.write(((value ushr 8) and 255).toInt())
                raf.write(((value ushr 16) and 255).toInt())
                raf.write(((value ushr 24) and 255).toInt())
            }
            ascii("RIFF")
            u32(36L + dataBytes)
            ascii("WAVEfmt ")
            u32(16)
            u16(1)
            u16(channels)
            u32(sampleRate.toLong())
            u32(sampleRate.toLong() * channels * 2L)
            u16(channels * 2)
            u16(16)
            ascii("data")
            u32(dataBytes)
        }
        finished = true
    }

    override fun close() {
        runCatching { finish() }
        runCatching { out.close() }
    }
}

private class AacMp4Sink(
    private val file: File,
    override val sampleRate: Int,
    override val channels: Int,
) : AudioSink {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var started = false
    private var track = -1
    private var inputFrames = 0L
    override var totalFrames = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    @Synchronized
    override fun writePcm16(bytes: ByteArray) {
        check(!closed)
        var offset = 0
        val frameSize = channels * 2
        while (offset < bytes.size) {
            drain(false)
            val inputIndex = codec.dequeueInputBuffer(10000L)
            if (inputIndex < 0) continue
            val input = codec.getInputBuffer(inputIndex) ?: error("AAC input buffer unavailable")
            input.clear()
            val size = min(input.remaining(), bytes.size - offset)
            input.put(bytes, offset, size)
            val frames = size.toLong() / frameSize.toLong()
            val ptsUs = inputFrames * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
            inputFrames += frames
            totalFrames += frames
            offset += size
        }
    }

    @Synchronized
    override fun finish() {
        if (closed) return
        while (true) {
            drain(false)
            val inputIndex = codec.dequeueInputBuffer(10000L)
            if (inputIndex >= 0) {
                val ptsUs = inputFrames * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
        }
        while (!drain(true)) { }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        closed = true
    }

    override fun close() { runCatching { finish() } }

    private fun drain(waitForEos: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (waitForEos) 10000L else 0L)
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        check(started)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return true
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
class AudiobookTtsExporter(private val context: Context) {
    @WorkerThread
    suspend fun export(
        request: AudiobookExportRequest,
        chapters: List<AudiobookChapterData>,
        mediaFile: File,
        jsonFile: File,
        onProgress: suspend (AudiobookExportProgress) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        coroutineScope {
            require(chapters.isNotEmpty())
        val segments = chapters.flatMap { chapter ->
            buildList {
                add(SpeechSegment(chapter.position, chapter.url, "title", chapter.speechTitle))
                chapter.paragraphs.map(::cleanAudiobookText).filter(String::isNotBlank).forEach {
                    add(SpeechSegment(chapter.position, chapter.url, "paragraph", it))
                }
            }
        }
        require(segments.isNotEmpty()) { "No spoken text" }

        // TTS callbacks only enqueue/copy PCM. Encoding happens off the callback thread.
        val activeSlice = AtomicReference<ExportSliceState?>(null)
        val chapterTimings = mutableListOf<ChapterTiming>()
        val exportedTimingStore = linkedMapOf<String, MutableList<JSONObject>>()
        val exportedTimingSegments = mutableListOf<JSONObject>()
        val segmentFile = File(
            jsonFile.parentFile ?: context.cacheDir,
            "segments-" + System.nanoTime() + ".jsonl",
        )
        val totalAudioBytesReceived = AtomicLong(0L)
        val totalFramesWritten = AtomicLong(0L)

        var sampleRate = 0
        var channels = 0
        var sink: AudioSink? = null

        val tts = createTts(request)
        val listener = object : UtteranceProgressListener() {
            private fun stateFor(id: String?): ExportSliceState? {
                val state = activeSlice.get() ?: return null
                return if (id != null && id == state.id) state else null
            }

            override fun onBeginSynthesis(
                id: String?,
                rate: Int,
                format: Int,
                count: Int,
            ) {
                val state = stateFor(id) ?: return
                if (rate <= 0 || count <= 0) {
                    state.error.compareAndSet(
                        null,
                        IllegalStateException("Invalid TTS audio format: rate=$rate channels=$count"),
                    )
                } else {
                    state.sampleRate = rate
                    state.audioFormat = format
                    state.channels = count
                }
                state.formatReady.countDown()
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                val state = stateFor(id) ?: return
                if (state.error.get() != null) return
                val bytes = audio ?: return
                if (bytes.isEmpty()) return

                // Never block the TTS callback on disk or MediaCodec.
                val copy = bytes.copyOf()
                if (state.pcmQueue.trySend(copy).isSuccess) {
                    state.audioBytesReceived.addAndGet(copy.size.toLong())
                    totalAudioBytesReceived.addAndGet(copy.size.toLong())
                } else {
                    state.error.compareAndSet(
                        null,
                        IllegalStateException("Unable to queue TTS PCM for slice " + state.id),
                    )
                }
            }

            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                stateFor(id)?.done?.countDown()
            }

            override fun onError(id: String?, code: Int) {
                stateFor(id)?.let {
                    it.error.compareAndSet(null, IllegalStateException("TTS error $code"))
                    it.formatReady.countDown()
                    it.done.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                stateFor(id)?.let {
                    it.error.compareAndSet(null, IllegalStateException("TTS error"))
                    it.formatReady.countDown()
                    it.done.countDown()
                }
            }

            // synthesizeToFile() range callbacks are intentionally not used as authoritative
            // exported word timings. Reader-persisted timing data is used when available.
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) = Unit
        }
        tts.setOnUtteranceProgressListener(listener)
        // Audio is generated once with synthesizeToFile(). Word timings come from
        // the Reader's persisted onRangeStart cache, so export never performs real-time playback.
        val effectiveEnginePackage = request.enginePackage.ifBlank { tts.defaultEngine.orEmpty() }
        val effectiveVoiceId = tts.voice?.name.orEmpty().ifBlank { request.voiceId }

        var activeChapter = -1
        var chapterStartMs = 0L
        var completedChapters = 0
        val totalWorkUnits = segments.sumOf { it.text.length.coerceAtLeast(1) }.toLong()
        var completedWorkUnits = 0L
        val exportStartedAt = SystemClock.elapsedRealtime()

        suspend fun publishProgress(chapter: AudiobookChapterData, force: Boolean = false) {
            val elapsed = (SystemClock.elapsedRealtime() - exportStartedAt).coerceAtLeast(0L)
            val percent = ((completedWorkUnits * 100L) / totalWorkUnits.coerceAtLeast(1L))
                .toInt()
                .coerceIn(0, 100)
            val eta = if (completedWorkUnits > 0L && percent < 100) {
                (elapsed.toDouble() * (totalWorkUnits - completedWorkUnits).toDouble() /
                    completedWorkUnits.toDouble()).toLong().coerceAtLeast(0L)
            } else null
            onProgress(
                AudiobookExportProgress(
                    currentChapter = completedChapters,
                    totalChapters = chapters.size,
                    chapterTitle = chapter.title,
                    percent = percent,
                    elapsedMs = elapsed,
                    estimatedRemainingMs = eta,
                    generatedAudioMs = durationMs(totalFramesWritten.get(), sampleRate),
                )
            )
        }

        // Initial progress is emitted before the first TTS synthesis.
        onProgress(
            AudiobookExportProgress(
                currentChapter = 0,
                totalChapters = chapters.size,
                chapterTitle = chapters.first().title,
                percent = 0,
                elapsedMs = 0L,
                estimatedRemainingMs = null,
                generatedAudioMs = 0L,
            )
        )

        BufferedWriter(OutputStreamWriter(FileOutputStream(segmentFile), Charsets.UTF_8), 32768).use { segmentOut ->
            try {
                for (segment in segments) {
                    if (segment.chapterPosition != activeChapter) {
                        if (activeChapter >= 0) {
                            val previous = chapters.first { it.position == activeChapter }
                            val endMs = durationMs(totalFramesWritten.get(), sampleRate)
                            chapterTimings += ChapterTiming(previous, chapterStartMs, endMs)
                            completedChapters++
                            publishProgress(previous, force = true)
                        }
                        activeChapter = segment.chapterPosition
                        chapterStartMs = durationMs(totalFramesWritten.get(), sampleRate)
                    }

                    val segmentStartMs = durationMs(totalFramesWritten.get(), sampleRate)
                    val slices = delimiterAwareTextSplitter(
                        fullText = segment.text,
                        maxSliceLength = TextToSpeech.getMaxSpeechInputLength(),
                        charDelimiter = '.',
                    ).filter(String::isNotBlank)

                    for ((sliceIndex, slice) in slices.withIndex()) {
                        val state = ExportSliceState(
                            "audiobook-" + System.nanoTime() + "-" + sliceIndex,
                        )
                        check(activeSlice.compareAndSet(null, state)) {
                            "TTS exporter internal error: another slice is active"
                        }

                        // Use the same regular File overload that the device diagnostic
                        // proved successful. The file is disposable; PCM callbacks feed
                        // the fast WAV/AAC sink, so export never uses real-time playback.
                        val synthesisFile = File(
                            context.cacheDir,
                            "audiobook-synthesis-" + System.nanoTime() + ".wav",
                        )
                        synthesisFile.delete()
                        var writerJob: kotlinx.coroutines.Job? = null
                        try {
                            Timber.d(
                                "AudiobookTTS: synthesize slice id=%s chars=%d format=%s engine=%s voice=%s file=%s",
                                state.id,
                                slice.length,
                                request.outputFormat,
                                effectiveEnginePackage,
                                effectiveVoiceId,
                                synthesisFile.absolutePath,
                            )

                            val synthesisResult = withContext(Dispatchers.Main.immediate) {
                                tts.synthesizeToFile(
                                    slice,
                                    Bundle(),
                                    synthesisFile,
                                    state.id,
                                )
                            }
                            Timber.d(
                                "AudiobookTTS: File synthesis queued result=%d exists=%s length=%d id=%s",
                                synthesisResult,
                                synthesisFile.exists(),
                                synthesisFile.length(),
                                state.id,
                            )
                            check(synthesisResult == TextToSpeech.SUCCESS) {
                                "TTS synthesis request failed: " + synthesisResult +
                                    " (engine=" + effectiveEnginePackage +
                                    " voice=" + effectiveVoiceId +
                                    " chars=" + slice.length + ")"
                            }
                        check(state.formatReady.await(10, TimeUnit.SECONDS)) {
                                "TTS synthesis did not report its audio format"
                            }
                            state.error.get()?.let { throw it }
                            check(state.sampleRate > 0 && state.channels > 0) {
                                "TTS produced an invalid audio format"
                            }

                            if (sink == null) {
                                sampleRate = state.sampleRate
                                channels = state.channels
                                sink = when (request.outputFormat) {
                                    OutputFormat.WAV -> WavSink(mediaFile, sampleRate, channels)
                                    OutputFormat.MP4 -> AacMp4Sink(mediaFile, sampleRate, channels)
                                }
                            } else {
                                check(sampleRate == state.sampleRate && channels == state.channels) {
                                    "TTS audio format changed during export"
                                }
                            }

                            // Consume queued PCM on a coroutine. TTS callback threads never touch
                            // the actual file writer or MediaCodec.
                            writerJob = launch(Dispatchers.IO) {
                                try {
                                    for (pcm in state.pcmQueue) {
                                        val pcm16 = normalizePcm16(pcm, state.audioFormat)
                                        val audioSink = sink ?: error("Audio sink was not initialized")
                                        audioSink.writePcm16(pcm16)
                                        totalFramesWritten.set(audioSink.totalFrames)
                                    }
                                } catch (t: Throwable) {
                                    state.error.compareAndSet(null, t)
                                }
                            }

                            val synthesisDeadline = SystemClock.elapsedRealtime() + 10 * 60_000L
                            while (!state.done.await(500L, TimeUnit.MILLISECONDS)) {
                                if (SystemClock.elapsedRealtime() >= synthesisDeadline) {
                                    throw IllegalStateException("TTS synthesis timeout")
                                }

                                val elapsed = SystemClock.elapsedRealtime() - exportStartedAt
                                val completed = completedWorkUnits.toDouble()
                                val total = totalWorkUnits.toDouble().coerceAtLeast(1.0)
                                val basePercent = ((completed * 100.0) / total).toInt()
                                val livePercent = if (totalAudioBytesReceived.get() > 0L) {
                                    maxOf(basePercent, 1)
                                } else {
                                    basePercent
                                }
                                onProgress(
                                    AudiobookExportProgress(
                                        currentChapter = completedChapters,
                                        totalChapters = chapters.size,
                                        chapterTitle = chapters.first {
                                            it.position == segment.chapterPosition
                                        }.title,
                                        percent = livePercent.coerceIn(0, 100),
                                        elapsedMs = elapsed.coerceAtLeast(0L),
                                        estimatedRemainingMs = null,
                                        generatedAudioMs = durationMs(
                                            totalFramesWritten.get(),
                                            sampleRate,
                                        ),
                                    )
                                )
                            }

                            state.pcmQueue.close()
                            writerJob.join()
                            state.error.get()?.let { throw it }

                            check(state.audioBytesReceived.get() > 0L) {
                                "TTS produced no audio data for the current slice"
                            }
                        } finally {
                            runCatching { synthesisFile.delete() }
                            runCatching { state.pcmQueue.close() }
                            if (writerJob != null) {
                                try {
                                    writerJob!!.join()
                                } catch (_: Throwable) {
                                }
                            }
                            synthesisFile.delete()
                            activeSlice.compareAndSet(state, null)
                        }

                        val currentElapsed = (SystemClock.elapsedRealtime() - exportStartedAt).coerceAtLeast(0L)
                        if (currentElapsed >= 350L) {
                            publishProgress(
                                chapters.first { it.position == segment.chapterPosition },
                            )
                        }
                    }

                    val segmentEndMs = durationMs(totalFramesWritten.get(), sampleRate)
                    val timingKey = readerWordTimingCacheKey(
                        enginePackage = effectiveEnginePackage,
                        voiceId = effectiveVoiceId,
                        needsInternet = tts.voice?.isNetworkConnectionRequired == true,
                        language = tts.voice?.locale?.displayLanguage.orEmpty(),
                        pitch = request.pitch,
                        text = segment.text,
                    )
                    val cachedTimings = readReaderWordTimings(context, timingKey)
                    // Reader speaks ReaderItem.Text paragraphs, not the synthetic audiobook
                    // intro line ("book title + chapter title"). Keep the title audio in the
                    // export, but do not invent false word timings for it when no Reader cache
                    // exists. Paragraphs remain strict: exported word timings are exact cached
                    // Reader onRangeStart timings, not estimates.
                    val requestedSpeed = request.speed.coerceIn(0.1f, 5f)
                    val speedScaledTimings = cachedTimings.map { timing ->
                        val speedScale = timing.speed.toDouble() / requestedSpeed.toDouble()
                        val relativeStartMs = (timing.startMs.toDouble() * speedScale)
                            .toLong().coerceAtLeast(0L)
                        val durationMs = (timing.durationMs.toDouble() * speedScale)
                            .toLong().coerceAtLeast(1L)
                        timing to (relativeStartMs to durationMs)
                    }
                    val cachedTimelineEndMs = speedScaledTimings.maxOfOrNull {
                        it.second.first + it.second.second
                    }?.coerceAtLeast(1L) ?: 1L
                    // Reader timing is learned from the reader's onRangeStart playback timeline.
                    // The exported WAV/AAC has its own measured audio timeline, so normalize the
                    // cached schedule to the exact duration that was actually written. This keeps
                    // the fast cached timing path aligned even for engines where playback markers
                    // and synthesized-file duration differ.
                    val audioTimelineMs = (segmentEndMs - segmentStartMs).coerceAtLeast(1L)
                    val audioFitScale = audioTimelineMs.toDouble() / cachedTimelineEndMs.toDouble()
                    val wordTimings = speedScaledTimings
                        .map { (timing, relative) ->
                            val startMs = segmentStartMs +
                                (relative.first.toDouble() * audioFitScale).toLong().coerceAtLeast(0L)
                            val durationMs = (relative.second.toDouble() * audioFitScale)
                                .toLong().coerceAtLeast(1L)
                            WordTiming(
                                startChar = timing.start,
                                endChar = timing.end,
                                startMs = startMs,
                                endMs = startMs + durationMs,
                            )
                        }
                        .filter {
                            it.startChar >= 0 &&
                                it.endChar <= segment.text.length &&
                                it.endChar > it.startChar &&
                                it.endMs >= it.startMs
                        }
                        .sortedBy { it.startMs }

                    val timingEntries = exportedTimingStore.getOrPut(timingKey) { mutableListOf() }
                    wordTimings.forEach { w ->
                        val safeEndMs = min(w.endMs, segmentEndMs)
                        // Reader's persisted timing store is relative to the text item.
                        // The merged audiobook timeline is kept separately in segments/words.
                        val relativeStartMs = (w.startMs - segmentStartMs).coerceAtLeast(0L)
                        timingEntries += JSONObject().apply {
                            put("start", w.startChar)
                            put("end", w.endChar)
                            put("start_ms", relativeStartMs)
                            put("duration_ms", (safeEndMs - w.startMs).coerceAtLeast(1L))
                            put("speed", request.speed.toDouble())
                        }
                    }

                    exportedTimingSegments += JSONObject().apply {
                        put("cacheKey", timingKey)
                        put("chapterPosition", segment.chapterPosition)
                        put("chapterUrl", segment.chapterUrl)
                        put("type", segment.type)
                        put("text", segment.text)
                        put("timingAvailable", wordTimings.isNotEmpty())
                        put("startMs", segmentStartMs)
                        put("endMs", segmentEndMs)
                    }

                    segmentOut.write(
                        JSONObject().apply {
                            put("chapterPosition", segment.chapterPosition)
                            put("chapterUrl", segment.chapterUrl)
                            put("type", segment.type)
                            put("text", segment.text)
                            put("timingAvailable", wordTimings.isNotEmpty())
                            put("startMs", segmentStartMs)
                            put("endMs", segmentEndMs)
                            put("words", JSONArray().apply {
                                wordTimings.forEach { w ->
                                    val safeEndMs = min(w.endMs, segmentEndMs)
                                    put(JSONObject().apply {
                                        // Same range/timing field names used by the Reader's
                                        // persisted TTS word-timing data, plus the existing
                                        // camelCase fields retained for audiobook consumers.
                                        put("start", w.startChar)
                                        put("end", w.endChar)
                                        // snake_case fields mirror the Reader timing store
                                        // and are relative to this text segment.
                                        put("start_ms", (w.startMs - segmentStartMs).coerceAtLeast(0L))
                                        put("duration_ms", (safeEndMs - w.startMs).coerceAtLeast(1L))
                                        put("speed", request.speed.toDouble())
                                        put("startChar", w.startChar)
                                        put("endChar", w.endChar)
                                        // camelCase timestamps are absolute positions in the
                                        // merged audiobook timeline.
                                        put("startMs", w.startMs)
                                        put("endMs", safeEndMs)
                                    })
                                }
                            })
                        }.toString()
                    )
                    segmentOut.newLine()
                }

                if (activeChapter >= 0) {
                    val last = chapters.first { it.position == activeChapter }
                    val endMs = durationMs(totalFramesWritten.get(), sampleRate)
                    chapterTimings += ChapterTiming(last, chapterStartMs, endMs)
                    completedChapters++
                    completedWorkUnits = totalWorkUnits
                    publishProgress(last, force = true)
                }

                sink?.finish()
                val totalDuration = durationMs(totalFramesWritten.get(), sampleRate)
                writeJson(
                    jsonFile,
                    request,
                    chapterTimings,
                    segmentFile,
                    totalDuration,
                    sampleRate,
                    channels,
                    exportedTimingStore,
                    exportedTimingSegments,
                )
                validateExportJson(jsonFile, chapterTimings, totalDuration)
                totalDuration
            } finally {
                runCatching { sink?.close() }
                runCatching { segmentFile.delete() }
                runCatching { tts.stop(); tts.shutdown() }
            }
            }
        }
    }

    suspend fun muxVisual(
        audioMp4: File,
        outputMp4: File,
        visualUri: Uri?,
        durationMs: Long,
        onProgress: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val visual = visualUri ?: fallbackVisual()
        val mime = context.contentResolver.getType(visual).orEmpty().lowercase()
        val actualVisual = if (mime == "image/gif") firstGifFrame(visual) else visual
        val actualMime = if (mime == "image/gif") "image/jpeg" else mime

        var normalizedVideo: File? = null
        val visualForMux = if (actualMime.startsWith("video/")) {
            val sourceIsAvc = runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, actualVisual, null)
                    val track = findTrack(extractor, "video/")
                    track >= 0 &&
                        extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ==
                        MediaFormat.MIMETYPE_VIDEO_AVC
                } finally {
                    extractor.release()
                }
            }.getOrDefault(false)

            if (sourceIsAvc) {
                actualVisual
            } else {
                normalizedVideo = File(
                    context.cacheDir,
                    "audiobook-visual-normalized-" + System.nanoTime() + ".mp4",
                )
                // Transcode only the short visual once. Never transcode to the audiobook length.
                transcodeVisualOnce(
                    source = actualVisual,
                    output = normalizedVideo!!,
                    onProgress = { percent -> onProgress(percent / 2) },
                )
                Uri.fromFile(normalizedVideo!!)
            }
        } else {
            normalizedVideo = File(
                context.cacheDir,
                "audiobook-visual-cycle-" + System.nanoTime() + ".mp4",
            )
            val preparedImage = prepareStaticImage(actualVisual)
            try {
                encodeImageCycle(
                    imageUri = Uri.fromFile(preparedImage),
                    output = normalizedVideo!!,
                    onProgress = { percent -> onProgress(percent / 2) },
                )
            } finally {
                preparedImage.delete()
            }
            Uri.fromFile(normalizedVideo!!)
        }

        try {
            fastLoopMuxEncodedVideo(
                audioMp4 = audioMp4,
                visualUri = visualForMux,
                outputMp4 = outputMp4,
                durationMs = durationMs,
                onProgress = { percent ->
                    onProgress(50 + percent / 2)
                },
            )
            validateMp4Output(outputMp4, durationMs)
        } finally {
            normalizedVideo?.delete()
            if (actualVisual != visual && actualVisual.toString().startsWith("file:")) {
                actualVisual.path?.let { path ->
                    if (path.contains(context.cacheDir.path)) File(path).delete()
                }
            }
        }
    }

    private suspend fun transcodeVisualOnce(
        source: Uri,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setRemoveAudio(true)
                .build()

            suspendCancellableCoroutine<Unit> { cont ->
                val progressHolder = ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                lateinit var progressRunnable: Runnable
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                        ) {
                            handler.removeCallbacks(progressRunnable)
                            onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) {
                            handler.removeCallbacks(progressRunnable)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                progressRunnable = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 100))
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            handler.postDelayed(this, 500L)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    handler.removeCallbacks(progressRunnable)
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, output.absolutePath)
                handler.post(progressRunnable)
            }
        }
    }

    private suspend fun encodeImageCycle(
        imageUri: Uri,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val edited = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(imageUri)
                    .setImageDurationMs(1000L)
                    .build()
            ).setFrameRate(1).build()

            suspendCancellableCoroutine<Unit> { cont ->
                val progressHolder = ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                lateinit var progressRunnable: Runnable
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                        ) {
                            handler.removeCallbacks(progressRunnable)
                            onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) {
                            handler.removeCallbacks(progressRunnable)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                progressRunnable = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 100))
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            handler.postDelayed(this, 500L)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    handler.removeCallbacks(progressRunnable)
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, output.absolutePath)
                handler.post(progressRunnable)
            }
        }
    }

    private fun prepareStaticImage(source: Uri): File {
        val bitmap = context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode visual image" }
        }
        val maxWidth = 1280
        val maxHeight = 720
        val scale = min(
            1f,
            min(
                maxWidth.toFloat() / bitmap.width.toFloat(),
                maxHeight.toFloat() / bitmap.height.toFloat(),
            )
        )
        val prepared = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        val file = File(context.cacheDir, "audiobook-image-" + System.nanoTime() + ".jpg")
        FileOutputStream(file).use {
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 88, it)) {
                "Unable to prepare visual image"
            }
        }
        if (prepared !== bitmap) prepared.recycle()
        bitmap.recycle()
        return file
    }

    private fun fastLoopMuxEncodedVideo(
        audioMp4: File,
        visualUri: Uri,
        outputMp4: File,
        durationMs: Long,
        onProgress: (Int) -> Unit = {},
    ) {
        val audioExtractor = MediaExtractor()
        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var stage = "OPEN_INPUTS"
        var success = false

        try {
            audioExtractor.setDataSource(audioMp4.absolutePath)

            stage = "OPEN_VISUAL"
            videoExtractor.setDataSource(context, visualUri, null)

            stage = "FIND_TRACKS"
            val audioTrack = findTrack(audioExtractor, "audio/")
            val videoTrack = findTrack(videoExtractor, "video/")
            check(audioTrack >= 0) { "AAC audio track not found" }
            check(videoTrack >= 0) { "H264 visual track not found" }

            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            check(videoMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                "Fast mux requires H264/AVC visual, got $videoMime"
            }

            val videoDurationUs = videoFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            check(videoDurationUs > 0L) {
                "Visual video duration is unavailable"
            }

            audioExtractor.selectTrack(audioTrack)
            videoExtractor.selectTrack(videoTrack)

            stage = "CREATE_MUXER"
            outputMp4.parentFile?.mkdirs()
            outputMp4.delete()
            muxer = MediaMuxer(
                outputMp4.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val outAudioTrack = muxer.addTrack(audioFormat)
            val outVideoTrack = muxer.addTrack(videoFormat)
            muxer.start()

            val targetUs = durationMs.coerceAtLeast(1L) * 1000L
            var lastProgress = -1
            fun reportVisualProgress(completedUs: Long) {
                val percent = ((completedUs.coerceIn(0L, targetUs) * 100L) / targetUs)
                    .toInt()
                    .coerceIn(0, 100)
                if (percent != lastProgress) {
                    lastProgress = percent
                    onProgress(percent)
                }
            }

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)

            stage = "COPY_AUDIO"
            while (true) {
                val size = audioExtractor.sampleSize
                val time = audioExtractor.sampleTime
                if (size < 0 || time < 0 || time >= targetUs) break
                check(size <= buffer.capacity()) {
                    "Audio sample exceeds mux buffer: $size bytes"
                }
                buffer.clear()
                val read = audioExtractor.readSampleData(buffer, 0)
                check(read > 0) { "Unable to read AAC audio sample" }
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    this.size = read
                    presentationTimeUs = time
                    flags = audioExtractor.sampleFlags
                }
                muxer.writeSampleData(outAudioTrack, buffer, info)
                audioExtractor.advance()
            }

            stage = "LOOP_VIDEO"
            var videoOffsetUs = 0L
            reportVisualProgress(0L)
            while (videoOffsetUs < targetUs) {
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var sawSample = false
                var lastPtsUs = Long.MIN_VALUE

                while (true) {
                    val time = videoExtractor.sampleTime
                    val size = videoExtractor.sampleSize
                    if (size < 0 || time < 0 || time >= videoDurationUs) break

                    val pts = videoOffsetUs + time
                    if (pts >= targetUs) break
                    check(pts >= lastPtsUs) { "Visual timestamps are not monotonic" }
                    lastPtsUs = pts
                    check(size <= buffer.capacity()) {
                        "Video sample exceeds mux buffer: $size bytes"
                    }

                    buffer.clear()
                    val read = videoExtractor.readSampleData(buffer, 0)
                    check(read > 0) { "Unable to read H264 video sample" }
                    val info = MediaCodec.BufferInfo().apply {
                        offset = 0
                        this.size = read
                        presentationTimeUs = pts
                        flags = videoExtractor.sampleFlags
                    }
                    muxer.writeSampleData(outVideoTrack, buffer, info)
                    sawSample = true
                    reportVisualProgress(pts)
                    videoExtractor.advance()
                }

                check(sawSample) { "Visual loop iteration produced no samples" }
                videoOffsetUs += videoDurationUs
            }

            reportVisualProgress(targetUs)
            stage = "STOP_MUXER"
            muxer.stop()
            success = true
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Fast MP4 mux failed at $stage: ${e.message}",
                e,
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { audioExtractor.release() }
            runCatching { videoExtractor.release() }
            if (!success) outputMp4.delete()
        }
    }

    private fun validateMp4Output(file: File, audioDurationMs: Long) {
        check(file.exists() && file.length() > 0L) {
            "Final MP4 does not exist or is empty"
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = findTrack(extractor, "audio/")
            val videoTrack = findTrack(extractor, "video/")
            check(audioTrack >= 0) { "Final MP4 has no audio track" }
            check(videoTrack >= 0) { "Final MP4 has no video track" }

            val audioDurationUs = extractor.getTrackFormat(audioTrack)
                .getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            val videoDurationUs = extractor.getTrackFormat(videoTrack)
                .getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            check(audioDurationUs > 0L && videoDurationUs > 0L) {
                "Final MP4 has invalid track duration"
            }

            val outputDurationMs = maxOf(audioDurationUs, videoDurationUs) / 1000L
            val toleranceMs = maxOf(2000L, audioDurationMs / 50L)
            check(kotlin.math.abs(outputDurationMs - audioDurationMs) <= toleranceMs) {
                "Final MP4 duration mismatch: output=${outputDurationMs}ms audio=${audioDurationMs}ms tolerance=${toleranceMs}ms"
            }
        } finally {
            extractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
        } ?: -1

    private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long =
        if (containsKey(key)) getLong(key) else defaultValue

    private suspend fun createTts(request: AudiobookExportRequest): TextToSpeech =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                try {
                    val listener = TextToSpeech.OnInitListener { result ->
                        val instance = tts
                        if (result == TextToSpeech.SUCCESS && instance != null) {
                            try {
                                if (request.voiceId.isNotBlank()) {
                                    val voice = instance.voices?.firstOrNull {
                                        it.name == request.voiceId
                                    } ?: error("Selected voice is unavailable")
                                    check(instance.setVoice(voice) == TextToSpeech.SUCCESS) {
                                        "Unable to set selected TTS voice"
                                    }
                                }
                                check(
                                    instance.setSpeechRate(
                                        request.speed.coerceIn(0.1f, 5f),
                                    ) == TextToSpeech.SUCCESS
                                ) {
                                    "Unable to set TTS speech rate"
                                }
                                check(
                                    instance.setPitch(
                                        request.pitch.coerceIn(0.1f, 2f),
                                    ) == TextToSpeech.SUCCESS
                                ) {
                                    "Unable to set TTS pitch"
                                }
                                cont.resume(instance)
                            } catch (t: Throwable) {
                                runCatching { instance.shutdown() }
                                if (cont.isActive) cont.resumeWithException(t)
                            }
                        } else if (cont.isActive) {
                            tts?.shutdown()
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Unable to initialize TTS: result=$result",
                                )
                            )
                        }
                    }
                    tts = if (request.enginePackage.isBlank()) {
                        TextToSpeech(context, listener)
                    } else {
                        TextToSpeech(context, listener, request.enginePackage)
                    }
                    cont.invokeOnCancellation {
                        runCatching { tts?.stop() }
                        runCatching { tts?.shutdown() }
                    }
                } catch (t: Throwable) {
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }

    private fun normalizePcm16(audio: ByteArray, format: Int): ByteArray = when (format) {
        android.media.AudioFormat.ENCODING_PCM_16BIT -> audio
        android.media.AudioFormat.ENCODING_PCM_8BIT -> ByteArray(audio.size * 2).also { out ->
            audio.forEachIndexed { i, value ->
                val sample = ((value.toInt() and 255) - 128) shl 8
                out[i * 2] = sample.toByte()
                out[i * 2 + 1] = (sample shr 8).toByte()
            }
        }
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
            val input = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocate(audio.size / 2).order(ByteOrder.LITTLE_ENDIAN)
            while (input.remaining() >= 4) {
                output.putShort((input.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            output.array()
        }
        android.media.AudioFormat.ENCODING_PCM_32BIT -> {
            val input = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocate(audio.size / 2).order(ByteOrder.LITTLE_ENDIAN)
            while (input.remaining() >= 4) output.putShort((input.int shr 16).toShort())
            output.array()
        }
        else -> error("Unsupported PCM format " + format)
    }

    private fun writeJson(
        file: File,
        request: AudiobookExportRequest,
        chapters: List<ChapterTiming>,
        segmentFile: File,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
        exportedTimingStore: Map<String, List<JSONObject>>,
        exportedTimingSegments: List<JSONObject>,
    ) {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 32768).use { out ->
            out.write("{\n")
            out.write("  \"schemaVersion\": 1,\n")
            out.write("  \"bookTitle\": " + JSONObject.quote(request.bookTitle) + ",\n")
            out.write("  \"export\": {\"mode\": " + JSONObject.quote(request.contentMode) +
                ",\"sourceLang\": " + JSONObject.quote(request.sourceLang) +
                ",\"targetLang\": " + JSONObject.quote(request.targetLang) +
                ",\"startPosition\": " + request.startPosition +
                ",\"endPosition\": " + request.endPosition +
                ",\"format\": " + JSONObject.quote(request.outputFormat.name.lowercase()) + "},\n")
            out.write("  \"tts\": {\"enginePackage\": " + JSONObject.quote(request.enginePackage) +
                ",\"voiceId\": " + JSONObject.quote(request.voiceId) +
                ",\"speed\": " + request.speed +
                ",\"pitch\": " + request.pitch + "},\n")
            out.write("  \"audio\": {\"sampleRate\": " + sampleRate +
                ",\"channels\": " + channels + ",\"durationMs\": " + durationMs + "},\n")
            val timingSegmentsJson = JSONArray()
            exportedTimingSegments.forEach(timingSegmentsJson::put)
            out.write("  \"wordTiming\": {\"format\": \"tts_word_highlight_timing_json_v2\",\"rangeEndExclusive\": true,\"units\": \"ms\",\"timingSource\": \"reader_persisted_onRangeStart\",\"segments\": ")
            out.write(timingSegmentsJson.toString())
            out.write("},\n")
            out.write("  \"tts_word_highlight_timing_json_v2\": {\n")
            val timingKeys = exportedTimingStore.keys.toList()
            timingKeys.forEachIndexed { index, key ->
                val entriesJson = JSONArray()
                exportedTimingStore[key].orEmpty().forEach(entriesJson::put)
                out.write("    " + JSONObject.quote(key) + ": " + entriesJson.toString())
                if (index != timingKeys.lastIndex) out.write(",")
                out.write("\n")
            }
            out.write("  },\n")
            out.write("  \"chapters\": [\n")
            chapters.forEachIndexed { index, c ->
                out.write("    " + JSONObject().apply {
                    put("position", c.chapter.position)
                    put("url", c.chapter.url)
                    put("title", c.chapter.title)
                    put("startMs", c.startMs)
                    put("endMs", c.endMs)
                    put("durationMs", c.endMs - c.startMs)
                }.toString() + if (index == chapters.lastIndex) "\n" else ",\n")
            }
            out.write("  ],\n  \"segments\": [\n")
            var first = true
            segmentFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                if (!first) out.write(",\n")
                first = false
                out.write("    " + line)
            }
            out.write("\n  ],\n  \"summary\": {\"durationMs\": " + durationMs +
                ",\"sampleRate\": " + sampleRate + ",\"channels\": " + channels + "}\n}\n")
        }
    }


    private fun validateExportJson(
        file: File,
        chapters: List<ChapterTiming>,
        durationMs: Long,
    ) {
        check(file.exists() && file.length() > 0L) { "Audiobook JSON is missing or empty" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        check(root.optInt("schemaVersion", 0) == 1) { "Unsupported audiobook JSON schema" }
        val audio = root.optJSONObject("audio") ?: error("JSON audio metadata missing")
        check(audio.optLong("durationMs", -1L) == durationMs) { "JSON duration does not match exported audio" }
        check(audio.optInt("sampleRate", 0) > 0) { "JSON sample rate is invalid" }
        check(audio.optInt("channels", 0) > 0) { "JSON channel count is invalid" }
        val chapterArray = root.optJSONArray("chapters") ?: error("JSON chapters missing")
        check(chapterArray.length() == chapters.size) { "JSON chapter count mismatch" }
        var previousChapterEnd = 0L
        chapters.forEachIndexed { index, chapter ->
            val item = chapterArray.getJSONObject(index)
            check(item.optInt("position", -1) == chapter.chapter.position) { "JSON chapter position mismatch" }
            val startMs = item.optLong("startMs", -1L)
            val endMs = item.optLong("endMs", -1L)
            check(startMs >= previousChapterEnd && endMs >= startMs && endMs <= durationMs) { "Invalid chapter timeline" }
            previousChapterEnd = endMs
        }
        val segments = root.optJSONArray("segments") ?: error("JSON segments missing")
        val wordTiming = root.optJSONObject("wordTiming") ?: error("JSON wordTiming missing")
        val timingSegments = wordTiming.optJSONArray("segments") ?: error("JSON wordTiming segments missing")
        check(timingSegments.length() == segments.length()) { "JSON wordTiming segment count mismatch" }
        for (i in 0 until segments.length()) {
            val segment = segments.getJSONObject(i)
            val textValue = segment.optString("text", "")
            val startMs = segment.optLong("startMs", -1L)
            val endMs = segment.optLong("endMs", -1L)
            check(startMs >= 0L && endMs >= startMs && endMs <= durationMs) { "Invalid segment timeline at index " + i }
            val words = segment.optJSONArray("words") ?: JSONArray()
            var previousWordStart = startMs
            for (j in 0 until words.length()) {
                val word = words.getJSONObject(j)
                val startChar = word.optInt("startChar", -1)
                val endChar = word.optInt("endChar", -1)
                val wordStart = word.optLong("startMs", -1L)
                val wordEnd = word.optLong("endMs", -1L)
                check(startChar >= 0 && endChar > startChar && endChar <= textValue.length) { "Invalid word character range" }
                check(wordStart >= startMs && wordEnd >= wordStart && wordEnd <= endMs && wordStart >= previousWordStart) { "Invalid word timeline" }
                previousWordStart = wordStart
            }
        }
    }
    private fun durationMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate > 0) frames * 1000L / sampleRate else 0L

    private fun firstGifFrame(uri: Uri): Uri {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode GIF" }
        }
        val file = File(context.cacheDir, "audiobook-gif-" + System.nanoTime() + ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun fallbackVisual(): Uri {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        val file = File(context.cacheDir, "audiobook-bg-" + System.nanoTime() + ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}

private val leadDecorative = Regex("^[-\\=*_~+#·•°─┿]{3,}\\s*")
private val trailDecorative = Regex("\\s*[-\\=*_~+#·•°─┿]{3,}$")

private fun cleanAudiobookText(text: String): String =
    text.lines().joinToString("\n") { line ->
        line.replace(leadDecorative, "").replace(trailDecorative, "").trim()
    }

fun buildOriginalAudiobookChapter(
    position: Int,
    url: String,
    title: String,
    body: String,
    bookTitle: String,
): AudiobookChapterData = AudiobookChapterData(
    position = position,
    url = url,
    title = title,
    speechTitle = bookTitle + ". " + title + ".",
    paragraphs = bodyToParagraphs(body),
)

fun buildTranslatedAudiobookChapter(
    position: Int,
    url: String,
    originalTitle: String,
    translatedTitle: String,
    translatedParagraphs: List<String>,
    bookTitle: String,
): AudiobookChapterData = AudiobookChapterData(
    position = position,
    url = url,
    title = translatedTitle.ifBlank { originalTitle },
    speechTitle = bookTitle + ". " + translatedTitle.ifBlank { originalTitle } + ".",
    paragraphs = translatedParagraphs.map(::cleanAudiobookText).filter(String::isNotBlank),
)

private fun bodyToParagraphs(body: String): List<String> {
    val html = body
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n")
    val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    return plain.replace("\r\n", "\n")
        .split(Regex("\n\\s*\n+"))
        .flatMap { it.split("\n") }
        .map(::cleanAudiobookText)
        .filter(String::isNotBlank)
}
