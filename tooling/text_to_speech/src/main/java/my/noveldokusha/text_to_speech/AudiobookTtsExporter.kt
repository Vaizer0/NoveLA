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
import android.os.ParcelFileDescriptor
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
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

private data class WavPcm16(
    val sampleRate: Int,
    val channels: Int,
    val pcm16: ByteArray,
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
        check(started) { "AAC encoder produced no output format/samples" }
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        check(file.exists() && file.length() > 0L) { "AAC/MP4 output is missing or empty" }
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

        // TTS instances and audio sinks are initialized below.
        var currentSliceId = ""
        var currentFrames = 0L
        var sampleRate = 0
        var channels = 0
        var sink: AudioSink? = null
        var error: Throwable? = null
        var latch = CountDownLatch(0)
        // File-backed synthesis is the primary fast path. Callback PCM is kept only as
        // an emergency fallback for engines that return a valid status but do not leave
        // a readable WAV file behind.
        var currentSlicePcmFallback: ByteArrayOutputStream? = null
        val lastFormat = mutableMapOf<String, Int>()
        val chapterTimings = mutableListOf<ChapterTiming>()
        val exportedTimingStore = linkedMapOf<String, MutableList<JSONObject>>()
        val exportedTimingSegments = mutableListOf<JSONObject>()
        val segmentFile = File(jsonFile.parentFile ?: context.cacheDir, "segments-" + System.nanoTime() + ".jsonl")

        // The fast synthesis path renders each slice once to the app cache. We then append
        // the WAV PCM to the final sink. onAudioAvailable is retained only as an engine
        // compatibility fallback; it is never required for the normal export path.
        var audioBytesReceived = 0L
        var sliceAudioBytesReceived = 0L

        val tts = createTts(request)
        val listener = object : UtteranceProgressListener() {
            override fun onBeginSynthesis(
                id: String?,
                rate: Int,
                format: Int,
                count: Int,
            ) {
                if (id != currentSliceId) return
                if (rate <= 0 || count <= 0) {
                    error = IllegalStateException("Invalid TTS audio format: rate=$rate channels=$count")
                    return
                }
                lastFormat[id] = format
                if (sampleRate == 0) {
                    sampleRate = rate
                    channels = count
                } else if (sampleRate != rate || channels != count) {
                    error = IllegalStateException("TTS audio format changed during export")
                }
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                if (id != currentSliceId || error != null) return
                val bytes = audio ?: return
                if (bytes.isEmpty()) return
                val format = lastFormat[id] ?: return
                runCatching {
                    currentSlicePcmFallback?.write(normalizePcm16(bytes, format))
                }.onFailure {
                    error = it
                }
            }

            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == currentSliceId) latch.countDown()
            }

            override fun onError(id: String?, code: Int) {
                if (id == currentSliceId) {
                    error = IllegalStateException("TTS error $code")
                    latch.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == currentSliceId) {
                    error = IllegalStateException("TTS error")
                    latch.countDown()
                }
            }

            // IMPORTANT: do not use synthesizeToFile() onRangeStart(frame) for exported
            // word timing. On Google TTS/Android 16 those frames are not the Reader timing
            // positions; word timings come from the persisted Reader cache instead.
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) = Unit
        }
        withContext(Dispatchers.Main.immediate) {
            tts.setOnUtteranceProgressListener(listener)
        }
        // Audio is generated once with synthesizeToFile(). Word timings come from
        // the Reader's persisted onRangeStart cache, so export never performs real-time playback.
        val ttsMetadata = withContext(Dispatchers.Main.immediate) {
            Triple(
                request.enginePackage.ifBlank { tts.defaultEngine.orEmpty() },
                tts.voice?.name.orEmpty().ifBlank { request.voiceId },
                tts.voice?.locale?.toLanguageTag().orEmpty(),
            )
        }
        val effectiveEnginePackage = ttsMetadata.first
        val effectiveVoiceId = ttsMetadata.second
        val effectiveLocale = ttsMetadata.third
        val effectiveNeedsInternet = withContext(Dispatchers.Main.immediate) {
            tts.voice?.isNetworkConnectionRequired == true
        }
        Timber.d(
            "AudiobookTTS: ready engine=%s voice=%s locale=%s network=%s speed=%.2f pitch=%.2f",
            effectiveEnginePackage,
            effectiveVoiceId,
            effectiveLocale,
            effectiveNeedsInternet,
            request.speed,
            request.pitch,
        )
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
                    generatedAudioMs = durationMs(currentFrames, sampleRate),
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
                            val endMs = durationMs(currentFrames, sampleRate)
                            chapterTimings += ChapterTiming(previous, chapterStartMs, endMs)
                            completedChapters++
                            publishProgress(previous, force = true)
                        }
                        activeChapter = segment.chapterPosition
                        chapterStartMs = durationMs(currentFrames, sampleRate)
                    }

                    val segmentStartMs = durationMs(currentFrames, sampleRate)
                    val slices = delimiterAwareTextSplitter(
                        fullText = segment.text,
                        maxSliceLength = TextToSpeech.getMaxSpeechInputLength(),
                        charDelimiter = '.',
                    ).filter(String::isNotBlank)

                    for ((sliceIndex, slice) in slices.withIndex()) {
                        currentSliceId = "audiobook-" + System.nanoTime() + "-" + sliceIndex
                        error = null
                        sliceAudioBytesReceived = 0L
                        currentSlicePcmFallback = ByteArrayOutputStream(256 * 1024)
                        latch = CountDownLatch(1)

                        val synthesisFile = File(
                            context.cacheDir,
                            "audiobook-tts-slice-" + System.nanoTime() + "-" + sliceIndex + ".wav",
                        )
                        try {
                            Timber.d(
                                "AudiobookTTS: synthesize slice id=%s chars=%d file=%s thread=%s",
                                currentSliceId,
                                slice.length,
                                synthesisFile.name,
                                Thread.currentThread().name,
                            )

                            val synthesisResult = synthesizeSliceWithRecovery(
                                tts = tts,
                                request = request,
                                text = slice,
                                utteranceId = currentSliceId,
                                outputFile = synthesisFile,
                            )
                            Timber.d(
                                "AudiobookTTS: synthesizeToFile returned %d id=%s exists=%s bytes=%d",
                                synthesisResult,
                                currentSliceId,
                                synthesisFile.exists(),
                                synthesisFile.length(),
                            )
                            check(synthesisResult == TextToSpeech.SUCCESS) {
                                "TTS synthesis failed: " + synthesisResult +
                                    " (engine=" + effectiveEnginePackage + ", voice=" + effectiveVoiceId + ")"
                            }

                            val synthesisDeadline = SystemClock.elapsedRealtime() + 10 * 60_000L
                            var lastVerifiedFileSize = -1L
                            var stableFileSince = 0L
                            while (true) {
                                if (latch.await(250L, TimeUnit.MILLISECONDS)) break

                                // Some Android/TTS engine builds can finish the file write even
                                // when the completion callback is delayed/missing. A syntactically
                                // valid RIFF/WAVE with a stable size is a safe completion signal.
                                val fileSize = synthesisFile.length()
                                if (fileSize > 44L) {
                                    val fileReady = runCatching {
                                        readWavPcm16(synthesisFile).pcm16.isNotEmpty()
                                    }.getOrDefault(false)
                                    if (fileReady) {
                                        if (fileSize == lastVerifiedFileSize) {
                                            if (stableFileSince == 0L) stableFileSince = SystemClock.elapsedRealtime()
                                            if (SystemClock.elapsedRealtime() - stableFileSince >= 250L) {
                                                break
                                            }
                                        } else {
                                            lastVerifiedFileSize = fileSize
                                            stableFileSince = SystemClock.elapsedRealtime()
                                        }
                                    }
                                }

                                if (SystemClock.elapsedRealtime() >= synthesisDeadline) {
                                    throw IllegalStateException("TTS synthesis timeout")
                                }
                                val elapsed = SystemClock.elapsedRealtime() - exportStartedAt
                                val completed = completedWorkUnits.toDouble()
                                val total = totalWorkUnits.toDouble().coerceAtLeast(1.0)
                                val basePercent = ((completed * 100.0) / total).toInt()
                                val hasLiveAudio =
                                    synthesisFile.length() > 44L ||
                                        (currentSlicePcmFallback?.size() ?: 0) > 0
                                val livePercent = if (hasLiveAudio) {
                                    maxOf(basePercent, 1)
                                } else {
                                    basePercent
                                }
                                onProgress(
                                    AudiobookExportProgress(
                                        currentChapter = completedChapters,
                                        totalChapters = chapters.size,
                                        chapterTitle = chapters.first { it.position == segment.chapterPosition }.title,
                                        percent = livePercent.coerceIn(0, 100),
                                        elapsedMs = elapsed.coerceAtLeast(0L),
                                        estimatedRemainingMs = null,
                                        generatedAudioMs = durationMs(currentFrames, sampleRate),
                                    )
                                )
                            }

                            error?.let { throw it }

                            val filePcmResult = runCatching { readWavPcm16(synthesisFile) }
                            val pcm16 = filePcmResult.getOrElse {
                                val fallback = currentSlicePcmFallback?.toByteArray() ?: ByteArray(0)
                                if (fallback.isEmpty()) {
                                    throw IllegalStateException(
                                        "TTS produced an unreadable WAV and no PCM callback data: " +
                                            (it.message ?: it::class.java.simpleName),
                                    )
                                }
                                Timber.w(
                                    it,
                                    "AudiobookTTS: using onAudioAvailable PCM fallback for " + currentSliceId,
                                )
                                WavPcm16(
                                    sampleRate = sampleRate,
                                    channels = channels,
                                    pcm16 = fallback,
                                )
                            }

                            if (sampleRate == 0) sampleRate = pcm16.sampleRate
                            if (channels == 0) channels = pcm16.channels
                            check(sampleRate == pcm16.sampleRate && channels == pcm16.channels) {
                                "TTS audio format changed during export: " +
                                    sampleRate + "x" + channels + " -> " +
                                    pcm16.sampleRate + "x" + pcm16.channels
                            }
                            if (sink == null) {
                                sink = when (request.outputFormat) {
                                    OutputFormat.WAV -> WavSink(mediaFile, sampleRate, channels)
                                    OutputFormat.MP4 -> AacMp4Sink(mediaFile, sampleRate, channels)
                                }
                            }

                            sink?.writePcm16(pcm16.pcm16)
                            currentFrames = sink?.totalFrames ?: currentFrames
                            audioBytesReceived += pcm16.pcm16.size.toLong()
                            sliceAudioBytesReceived += pcm16.pcm16.size.toLong()
                        } finally {
                            currentSlicePcmFallback = null
                            runCatching { synthesisFile.delete() }
                        }

                        check(sink != null && sampleRate > 0 && channels > 0) {
                            "TTS produced no audio format"
                        }
                        check(sliceAudioBytesReceived > 0L) {
                            "TTS produced no audio data for the current segment"
                        }
                        completedWorkUnits += slice.length.toLong().coerceAtLeast(1L)
                        val currentElapsed = (SystemClock.elapsedRealtime() - exportStartedAt).coerceAtLeast(0L)
                        if (currentElapsed >= 350L) {
                            publishProgress(
                                chapters.first { it.position == segment.chapterPosition },
                            )
                        }
                    }

                    val segmentEndMs = durationMs(currentFrames, sampleRate)
                    val timingKey = readerWordTimingCacheKey(
                        enginePackage = effectiveEnginePackage,
                        voiceId = effectiveVoiceId,
                        needsInternet = effectiveNeedsInternet,
                        language = effectiveLocale.substringBefore('-').ifBlank { effectiveLocale },
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
                    val endMs = durationMs(currentFrames, sampleRate)
                    chapterTimings += ChapterTiming(last, chapterStartMs, endMs)
                    completedChapters++
                    completedWorkUnits = totalWorkUnits
                    publishProgress(last, force = true)
                }

                sink?.finish()
                val totalDuration = durationMs(currentFrames, sampleRate)
                validateGeneratedMedia(
                    file = mediaFile,
                    format = request.outputFormat,
                    durationMs = totalDuration,
                    sampleRate = sampleRate,
                    channels = channels,
                )
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
                check(jsonFile.exists() && jsonFile.length() > 0L) {
                    "Audiobook metadata JSON is missing or empty"
                }
                runCatching {
                    JSONObject(jsonFile.readText(Charsets.UTF_8))
                }.getOrElse {
                    throw IllegalStateException("Audiobook metadata JSON is invalid: " + (it.message ?: "parse error"))
                }
                totalDuration
            } finally {
                runCatching { sink?.close() }
                runCatching { segmentFile.delete() }
                withContext(Dispatchers.Main.immediate) {
                    runCatching { tts.stop() }
                    runCatching { tts.shutdown() }
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
                transcodeVisualOnce(actualVisual, normalizedVideo!!)
                Uri.fromFile(normalizedVideo!!)
            }
        } else {
            normalizedVideo = File(
                context.cacheDir,
                "audiobook-visual-cycle-" + System.nanoTime() + ".mp4",
            )
            val preparedImage = prepareStaticImage(actualVisual)
            try {
                encodeImageCycle(Uri.fromFile(preparedImage), normalizedVideo!!)
            } finally {
                preparedImage.delete()
            }
            Uri.fromFile(normalizedVideo!!)
        }

        try {
            check(
                fastLoopMuxEncodedVideo(
                    audioMp4 = audioMp4,
                    visualUri = visualForMux,
                    outputMp4 = outputMp4,
                    durationMs = durationMs,
                    onProgress = onProgress,
                )
            ) {
                "Unable to create MP4 using the fast remux path. Use an H.264 MP4 visual or a supported image."
            }
        } finally {
            normalizedVideo?.delete()
            if (actualVisual != visual && actualVisual.toString().startsWith("file:")) {
                actualVisual.path?.let { path ->
                    if (path.contains(context.cacheDir.path)) File(path).delete()
                }
            }
        }
    }

    private suspend fun transcodeVisualOnce(source: Uri, output: File) {
        withContext(Dispatchers.Main.immediate) {
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setRemoveAudio(true)
                .build()

            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                        ) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, output.absolutePath)
            }
        }
    }

    private suspend fun encodeImageCycle(imageUri: Uri, output: File) {
        withContext(Dispatchers.Main.immediate) {
            val edited = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(imageUri)
                    .setImageDurationMs(1000L)
                    .build()
            ).setFrameRate(1).build()

            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                        ) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, output.absolutePath)
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
    ): Boolean {
        val audioExtractor = MediaExtractor()
        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            audioExtractor.setDataSource(audioMp4.absolutePath)
            videoExtractor.setDataSource(context, visualUri, null)

            val audioTrack = findTrack(audioExtractor, "audio/")
            val videoTrack = findTrack(videoExtractor, "video/")
            if (audioTrack < 0 || videoTrack < 0) return false

            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (videoMime != MediaFormat.MIMETYPE_VIDEO_AVC) return false

            audioExtractor.selectTrack(audioTrack)
            videoExtractor.selectTrack(videoTrack)

            val videoDurationUs = videoFormat.getLongOrDefault(
                MediaFormat.KEY_DURATION,
                0L,
            )
            if (videoDurationUs <= 0L) return false

            outputMp4.parentFile?.mkdirs()
            if (outputMp4.exists()) outputMp4.delete()
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
            onProgress(0)
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)

            while (true) {
                val size = audioExtractor.sampleSize
                val time = audioExtractor.sampleTime
                if (size < 0 || time < 0 || time >= targetUs) break
                if (size > buffer.capacity()) {
                    // Audio samples are normally tiny; fail the fast path instead of
                    // repeatedly allocating huge direct buffers.
                    return false
                }
                buffer.clear()
                val read = audioExtractor.readSampleData(buffer, 0)
                if (read <= 0) break
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    this.size = read
                    presentationTimeUs = time
                    flags = audioExtractor.sampleFlags
                }
                muxer.writeSampleData(outAudioTrack, buffer, info)
                audioExtractor.advance()
            }

            // Audio is already 90% of the overall job; this callback reports the video
            // portion from 0..100 without re-encoding the long visual track.
            onProgress(0)
            var videoOffsetUs = 0L
            while (videoOffsetUs < targetUs) {
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var sawSample = false
                while (true) {
                    val time = videoExtractor.sampleTime
                    val size = videoExtractor.sampleSize
                    if (size < 0 || time < 0 || time >= videoDurationUs) break
                    val pts = videoOffsetUs + time
                    if (pts >= targetUs) break
                    if (size > buffer.capacity()) return false
                    buffer.clear()
                    val read = videoExtractor.readSampleData(buffer, 0)
                    if (read <= 0) break
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
                if (!sawSample) return false
                videoOffsetUs += videoDurationUs
            }

            reportVisualProgress(targetUs)
            muxer.stop()
            true
        } catch (e: Throwable) {
            Timber.w(e, "Fast audiobook visual mux failed")
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { audioExtractor.release() }
            runCatching { videoExtractor.release() }
            if (outputMp4.exists() && outputMp4.length() == 0L) outputMp4.delete()
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
            val tts = suspendCancellableCoroutine<TextToSpeech> { continuation ->
                lateinit var instance: TextToSpeech
                val listener = TextToSpeech.OnInitListener { status ->
                    if (continuation.isActive) {
                        if (status == TextToSpeech.SUCCESS) {
                            continuation.resume(instance)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Unable to initialize TTS engine=" +
                                        request.enginePackage.ifBlank { "system-default" } +
                                        " result=" + status,
                                ),
                            )
                        }
                    }
                }
                instance = if (request.enginePackage.isBlank()) {
                    TextToSpeech(context, listener)
                } else {
                    TextToSpeech(context, listener, request.enginePackage)
                }
                continuation.invokeOnCancellation {
                    runCatching { instance.shutdown() }
                }
            }

            if (request.voiceId.isNotBlank()) {
                val voice = tts.voices?.firstOrNull { it.name == request.voiceId }
                    ?: error(
                        "Selected voice is unavailable: " + request.voiceId +
                            " (engine=" +
                            request.enginePackage.ifBlank { tts.defaultEngine.orEmpty() } +
                            ")",
                    )
                tts.voice = voice
            }

            check(
                tts.setSpeechRate(request.speed.coerceIn(0.1f, 5f)) == TextToSpeech.SUCCESS
            ) {
                "Unable to set TTS speech rate " + request.speed
            }
            check(
                tts.setPitch(request.pitch.coerceIn(0.1f, 2f)) == TextToSpeech.SUCCESS
            ) {
                "Unable to set TTS pitch " + request.pitch
            }
            tts
        }

    private suspend fun synthesizeSliceWithRecovery(
        tts: TextToSpeech,
        request: AudiobookExportRequest,
        text: String,
        utteranceId: String,
        outputFile: File,
    ): Int {
        suspend fun queueFile(): Int =
            withContext(Dispatchers.Main.immediate) {
                tts.synthesizeToFile(
                    text,
                    Bundle().apply {
                        putString(
                            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                            utteranceId,
                        )
                    },
                    outputFile,
                    utteranceId,
                )
            }

        fun reapplySettings() {
            if (request.voiceId.isNotBlank()) {
                tts.voices?.firstOrNull { it.name == request.voiceId }?.let {
                    tts.voice = it
                }
            }
            tts.setSpeechRate(request.speed.coerceIn(0.1f, 5f))
            tts.setPitch(request.pitch.coerceIn(0.1f, 2f))
        }

        suspend fun queuePfd(): Int =
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    outputFile.parentFile?.mkdirs()
                    runCatching { outputFile.delete() }
                    ParcelFileDescriptor.open(
                        outputFile,
                        ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE or
                            ParcelFileDescriptor.MODE_WRITE_ONLY,
                    ).use { pfd ->
                        tts.synthesizeToFile(
                            text,
                            Bundle().apply {
                                putString(
                                    TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                                    utteranceId,
                                )
                            },
                            pfd,
                            utteranceId,
                        )
                    }
                }.getOrElse {
                    Timber.w(it, "AudiobookTTS: PFD synthesizeToFile call failed")
                    TextToSpeech.ERROR
                }
            }

        var result = queueFile()
        if (result == TextToSpeech.SUCCESS) return result

        Timber.w(
            "AudiobookTTS: primary synth enqueue failed result=%d; retrying after stop/reapply",
            result,
        )
        withContext(Dispatchers.Main.immediate) {
            runCatching { tts.stop() }
            reapplySettings()
        }
        delay(60L)
        runCatching { outputFile.delete() }
        result = queueFile()
        if (result == TextToSpeech.SUCCESS) return result

        Timber.w(
            "AudiobookTTS: second File synth enqueue failed result=%d; trying PFD file API",
            result,
        )
        withContext(Dispatchers.Main.immediate) {
            runCatching { tts.stop() }
            reapplySettings()
        }
        delay(60L)
        result = queuePfd()
        if (result == TextToSpeech.SUCCESS) return result

        Timber.w(
            "AudiobookTTS: PFD synth enqueue failed result=%d; trying legacy path API",
            result,
        )
        runCatching { outputFile.delete() }
        result = withContext(Dispatchers.Main.immediate) {
            runCatching { tts.stop() }
            reapplySettings()
            @Suppress("DEPRECATION")
            tts.synthesizeToFile(
                text,
                hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to utteranceId),
                outputFile.absolutePath,
            )
        }
        return result
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

    private fun validateGeneratedMedia(
        file: File,
        format: OutputFormat,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
    ) {
        require(file.exists() && file.length() > 0L) {
            "Generated audiobook media is missing or empty"
        }
        require(durationMs > 0L) { "Generated audiobook duration is zero" }
        when (format) {
            OutputFormat.WAV -> {
                val wav = readWavPcm16(file)
                check(wav.sampleRate == sampleRate && wav.channels == channels) {
                    "Generated WAV format mismatch"
                }
            }
            OutputFormat.MP4 -> {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val audioTrack = findTrack(extractor, "audio/")
                    check(audioTrack >= 0) { "Generated MP4 has no audio track" }
                    val audioFormat = extractor.getTrackFormat(audioTrack)
                    val mediaDurationUs = audioFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
                    check(mediaDurationUs > 0L) { "Generated MP4 audio duration is zero" }
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private fun durationMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate > 0) frames * 1000L / sampleRate else 0L

    private fun readWavPcm16(file: File): WavPcm16 {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44L) {
                "WAV file is too small: " + raf.length() + " bytes"
            }

            fun readAscii(length: Int): String =
                ByteArray(length).also(raf::readFully).toString(Charsets.US_ASCII)

            fun readU16(): Int {
                val lo = raf.read()
                val hi = raf.read()
                check(lo >= 0 && hi >= 0) { "Unexpected end of WAV header" }
                return lo or (hi shl 8)
            }

            fun readU32(): Long {
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                check(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) {
                    "Unexpected end of WAV header"
                }
                return b0.toLong() or
                    (b1.toLong() shl 8) or
                    (b2.toLong() shl 16) or
                    (b3.toLong() shl 24)
            }

            require(readAscii(4) == "RIFF") { "Unsupported TTS file container (not RIFF)" }
            readU32()
            require(readAscii(4) == "WAVE") { "Unsupported TTS file container (not WAVE)" }

            var audioFormat = -1
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8L <= raf.length()) {
                val chunkId = readAscii(4)
                val chunkSize = readU32().coerceAtMost(raf.length() - raf.filePointer)
                when (chunkId) {
                    "fmt " -> {
                        audioFormat = readU16()
                        channels = readU16()
                        sampleRate = readU32().toInt()
                        readU32()
                        readU16()
                        bitsPerSample = readU16()
                        val consumed = 16L
                        if (chunkSize > consumed) {
                            raf.skipBytes(
                                (chunkSize - consumed)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt(),
                            )
                        }
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize
                        break
                    }
                    else -> {
                        raf.seek((raf.filePointer + chunkSize).coerceAtMost(raf.length()))
                    }
                }
                if ((chunkSize and 1L) != 0L && raf.filePointer < raf.length()) {
                    raf.skipBytes(1)
                }
            }

            check(audioFormat == 1) {
                "Unsupported TTS WAV encoding: " + audioFormat
            }
            check(channels > 0 && sampleRate > 0) {
                "Invalid TTS WAV format: rate=" + sampleRate + " channels=" + channels
            }
            check(bitsPerSample == 16) {
                "Unsupported TTS WAV bit depth: " + bitsPerSample
            }
            check(dataOffset >= 0L && dataSize > 0L) {
                "TTS WAV contains no audio data"
            }

            raf.seek(dataOffset)
            val pcm = ByteArray(dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            raf.readFully(pcm)
            return WavPcm16(sampleRate, channels, pcm)
        }
    }

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
