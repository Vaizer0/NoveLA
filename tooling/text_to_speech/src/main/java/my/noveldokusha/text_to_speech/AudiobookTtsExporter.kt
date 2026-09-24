package my.noveldokusha.text_to_speech

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedWriter
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
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    override var totalFrames = 0L

    init {
        raf.setLength(0L)
        repeat(44) { raf.write(0) }
    }

    @Synchronized\n    override fun writePcm16(bytes: ByteArray) {
        raf.write(bytes)
        dataBytes += bytes.size
        totalFrames += bytes.size.toLong() / (channels * 2L)
    }

    override fun finish() {
        require(dataBytes <= 0xFFFF_FFFFL - 36L) { "WAV exceeds RIFF size limit" }
        raf.seek(0)
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
        raf.seek(raf.length())
    }

    private fun ascii(value: String) = raf.write(value.toByteArray(Charsets.US_ASCII))
    private fun u16(value: Int) { raf.write(value and 255); raf.write((value ushr 8) and 255) }
    private fun u32(value: Long) {
        raf.write((value and 255).toInt())
        raf.write(((value ushr 8) and 255).toInt())
        raf.write(((value ushr 16) and 255).toInt())
        raf.write(((value ushr 24) and 255).toInt())
    }

    override fun close() { runCatching { raf.close() } }
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

@OptIn(UnstableApi::class)\nclass AudiobookTtsExporter(private val context: Context) {
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

        val tts = createTts(request)
        var sink: AudioSink? = null
        var sampleRate = 0
        var channels = 0
        var currentSliceId = ""
        var currentSliceOffset = 0
        var currentSliceStartMs = 0L
        var currentFrames = 0L
        var sliceRanges = mutableListOf<Pair<IntRange, Long>>()
        var error: Throwable? = null
        var latch = CountDownLatch(0)
        val lastFormat = mutableMapOf<String, Int>()
        val chapterTimings = mutableListOf<ChapterTiming>()
        val segmentFile = File(jsonFile.parentFile ?: context.cacheDir, "segments-" + System.nanoTime() + ".jsonl")

        val listener = object : UtteranceProgressListener() {
            override fun onBeginSynthesis(id: String?, rate: Int, format: Int, count: Int) {
                if (id != currentSliceId) return
                if (rate <= 0 || count <= 0) { error = IllegalStateException("Invalid TTS audio format"); return }
                lastFormat[id] = format
                if (sink == null) {
                    sampleRate = rate
                    channels = count
                    sink = when (request.outputFormat) {
                        OutputFormat.WAV -> WavSink(mediaFile, rate, count)
                        OutputFormat.MP4 -> AacMp4Sink(mediaFile, rate, count)
                    }
                } else if (sampleRate != rate || channels != count) {
                    error = IllegalStateException("TTS audio format changed")
                }
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                if (id != currentSliceId || error != null) return
                val bytes = audio ?: return
                if (bytes.isEmpty()) return
                val format = lastFormat[id] ?: return
                runCatching {
                    sink?.writePcm16(normalizePcm16(bytes, format))
                    currentFrames = sink?.totalFrames ?: currentFrames
                }.onFailure { error = it }
            }

            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                if (id != currentSliceId || sampleRate <= 0 || start < 0 || end <= start) return
                val ms = currentSliceStartMs + frame.toLong() * 1000L / sampleRate
                sliceRanges.add((start + currentSliceOffset until end + currentSliceOffset) to ms)
            }

            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == currentSliceId) latch.countDown() }
            override fun onError(id: String?, code: Int) {
                if (id == currentSliceId) { error = IllegalStateException("TTS error " + code); latch.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == currentSliceId) { error = IllegalStateException("TTS error"); latch.countDown() }
            }
        }
        tts.setOnUtteranceProgressListener(listener)

        var activeChapter = -1
        var chapterStartMs = 0L
        var completedChapters = 0
        BufferedWriter(OutputStreamWriter(FileOutputStream(segmentFile), Charsets.UTF_8), 32768).use { segmentOut ->
            try {
                for (segment in segments) {
                    if (segment.chapterPosition != activeChapter) {
                        if (activeChapter >= 0) {
                            val previous = chapters.first { it.position == activeChapter }
                            val endMs = durationMs(currentFrames, sampleRate)
                            chapterTimings += ChapterTiming(previous, chapterStartMs, endMs)
                            completedChapters++
                            onProgress(AudiobookExportProgress(completedChapters, chapters.size, previous.title))
                        }
                        activeChapter = segment.chapterPosition
                        chapterStartMs = durationMs(currentFrames, sampleRate)
                    }

                    val segmentStartMs = durationMs(currentFrames, sampleRate)
                    val wordTimings = mutableListOf<WordTiming>()
                    val slices = delimiterAwareTextSplitter(
                        fullText = segment.text,
                        maxSliceLength = TextToSpeech.getMaxSpeechInputLength(),
                        charDelimiter = '.',
                    ).filter(String::isNotBlank)
                    var charOffset = 0

                    for ((sliceIndex, slice) in slices.withIndex()) {
                        currentSliceId = "audiobook-" + System.nanoTime() + "-" + sliceIndex
                        currentSliceOffset = charOffset
                        currentSliceStartMs = durationMs(currentFrames, sampleRate)
                        sliceRanges = mutableListOf()
                        error = null
                        latch = CountDownLatch(1)
                        val pfd = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY)
                        try {
                            val result = tts.synthesizeToFile(
                                slice,
                                Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentSliceId) },
                                pfd,
                                currentSliceId,
                            )
                            check(result == TextToSpeech.SUCCESS) { "TTS synthesis failed: " + result }
                            check(latch.await(60, TimeUnit.SECONDS)) { "TTS synthesis timeout" }
                        } finally {
                            pfd.close()
                        }
                        error?.let { throw it }
                        val sliceEndMs = durationMs(currentFrames, sampleRate)
                        val ordered = sliceRanges.sortedBy { it.second }
                        ordered.forEachIndexed { index, entry ->
                            wordTimings += WordTiming(
                                entry.first.first,
                                entry.first.last + 1,
                                entry.second,
                                ordered.getOrNull(index + 1)?.second ?: sliceEndMs,
                            )
                        }
                        charOffset += slice.length
                    }

                    val segmentEndMs = durationMs(currentFrames, sampleRate)
                    segmentOut.write(
                        JSONObject().apply {
                            put("chapterPosition", segment.chapterPosition)
                            put("chapterUrl", segment.chapterUrl)
                            put("type", segment.type)
                            put("text", segment.text)
                            put("startMs", segmentStartMs)
                            put("endMs", segmentEndMs)
                            put("words", JSONArray().apply {
                                wordTimings.forEach { w ->
                                    put(JSONObject().apply {
                                        put("startChar", w.startChar)
                                        put("endChar", w.endChar)
                                        put("startMs", w.startMs)
                                        put("endMs", min(w.endMs, segmentEndMs))
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
                    onProgress(AudiobookExportProgress(completedChapters, chapters.size, last.title))
                }

                sink?.finish()
                val totalDuration = durationMs(currentFrames, sampleRate)
                writeJson(jsonFile, request, chapterTimings, segmentFile, totalDuration, sampleRate, channels)
                totalDuration
            } finally {
                runCatching { sink?.close() }
                runCatching { segmentFile.delete() }
                runCatching { tts.stop(); tts.shutdown() }
            }
        }
    }

    suspend fun muxVisual(
        audioMp4: File,
        outputMp4: File,
        visualUri: Uri?,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val visual = visualUri ?: fallbackVisual()
        val mime = context.contentResolver.getType(visual).orEmpty().lowercase()
        val actualVisual = if (mime == "image/gif") firstGifFrame(visual) else visual
        val actualMime = if (mime == "image/gif") "image/jpeg" else mime

        withContext(Dispatchers.Main.immediate) {
            val editedVideo = if (actualMime.startsWith("image/") || actualMime.isBlank()) {
                EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(actualVisual)
                        .setImageDurationMs(durationMs.coerceAtLeast(1000L))
                        .build()
                ).setFrameRate(1).build()
            } else {
                EditedMediaItem.Builder(MediaItem.fromUri(actualVisual))
                    .setRemoveAudio(true)
                    .build()
            }

            val video = if (actualMime.startsWith("image/") || actualMime.isBlank()) {
                EditedMediaItemSequence.withVideoFrom(listOf(editedVideo))
            } else {
                EditedMediaItemSequence.withVideoFrom(listOf(editedVideo)).buildUpon().setIsLooping(true).build()
            }
            val audio = EditedMediaItemSequence.withAudioFrom(
                listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioMp4))).build())
            )
            val composition = Composition.Builder(video, audio).build()

            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                transformer.start(composition, outputMp4.absolutePath)
            }
        }

        if (actualVisual.toString().startsWith("file:")) {
            actualVisual.path?.let { path ->
                if (path.contains(context.cacheDir.path)) File(path).delete()
            }
        }
    }

    private fun createTts(request: AudiobookExportRequest): TextToSpeech {
        val latch = CountDownLatch(1)
        var result = TextToSpeech.ERROR
        val tts = if (request.enginePackage.isBlank()) {
            TextToSpeech(context) { result = it; latch.countDown() }
        } else {
            TextToSpeech(context, { result = it; latch.countDown() }, request.enginePackage)
        }
        check(latch.await(10, TimeUnit.SECONDS) && result == TextToSpeech.SUCCESS) { "Unable to initialize TTS" }
        if (request.voiceId.isNotBlank()) {
            tts.voice = tts.voices?.firstOrNull { it.name == request.voiceId }
                ?: error("Selected voice is unavailable")
        }
        check(tts.setSpeechRate(request.speed.coerceIn(0.1f, 5f)) == TextToSpeech.SUCCESS)
        check(tts.setPitch(request.pitch.coerceIn(0.1f, 2f)) == TextToSpeech.SUCCESS)
        return tts
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
