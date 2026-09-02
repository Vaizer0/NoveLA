package my.noveldokusha.text_to_speech

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Lightweight Android-native MP4 encoder: H.264/AVC + AAC-LC, no FFmpeg/native multimedia dependency. */
class TtsVideoMp4Encoder {
    suspend fun encode(
        wavFile: File,
        outputFile: File,
        timeline: TtsVideoTimeline,
        visual: TtsVideoVisualSettings,
        renderer: TtsVideoCompositionRenderer,
        snapshot: TtsVideoVisualSnapshot,
        onProgress: (Float) -> Unit = {},
    ) {
        require(visual.width == 1920 && visual.height == 1080) { "Video export requires 1920x1080" }
        require(visual.fps == 30) { "Video export requires 30 FPS" }
        val wav = WavPcmReader(wavFile)
        val video = createVideoEncoder(visual)
        val audio = createAudioEncoder(wav.sampleRate, wav.channels)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var started = false
        var videoInputEos = false
        var audioInputEos = false
        var videoOutputEos = false
        var audioOutputEos = false
        val pending = ArrayList<MuxSample>()
        var frameIndex = 0L
        val frameCount = maxOf(1L, (wav.durationUs * visual.fps + 999_999L) / 1_000_000L)
        val frameDurationUs = 1_000_000L / visual.fps
        try {
            video.start(); audio.start()
            while (!videoOutputEos || !audioOutputEos) {
                coroutineContext.ensureActive()
                if (!videoInputEos) {
                    val pts = frameIndex * frameDurationUs
                    if (frameIndex < frameCount && pts < wav.durationUs) {
                        val bitmap = Bitmap.createBitmap(visual.width, visual.height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        renderer.render(canvas, timeline, visual, snapshot, pts)
                        feedVideoFrame(video, bitmap, pts)
                        bitmap.recycle()
                        frameIndex++
                        onProgress((frameIndex.toFloat() / frameCount.toFloat()).coerceAtMost(1f) * .55f)
                    } else {
                        feedVideoEos(video)
                        videoInputEos = true
                    }
                }
                if (!audioInputEos) {
                    if (!feedAudio(audio, wav)) audioInputEos = true
                }
                videoTrack = drain(video, true, videoTrack, muxer) { sample -> if (!started) pending += sample else muxer.writeSampleData(videoTrack, sample.buffer, sample.info) }
                audioTrack = drain(audio, false, audioTrack, muxer) { sample -> if (!started) pending += sample else muxer.writeSampleData(audioTrack, sample.buffer, sample.info) }
                if (!started && videoTrack >= 0 && audioTrack >= 0) {
                    muxer.start(); started = true
                    pending.forEach { sample ->
                        val track = if (sample.video) videoTrack else audioTrack
                        muxer.writeSampleData(track, sample.buffer, sample.info)
                    }
                    pending.clear()
                }
                videoOutputEos = videoOutputEos || videoOutputEosReached(video)
                audioOutputEos = audioOutputEos || audioOutputEosReached(audio)
                onProgress(.55f + .45f * minOf(1f, frameIndex.toFloat() / frameCount.toFloat()))
            }
            if (!started) throw IllegalStateException("MediaMuxer never received both tracks")
        } finally {
            runCatching { video.stop() }; runCatching { audio.stop() }
            runCatching { video.release() }; runCatching { audio.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { wav.close() }
        }
    }

    private fun createVideoEncoder(s: TtsVideoVisualSettings): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, s.width, s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }

    private fun createAudioEncoder(rate: Int, channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }

    private fun feedVideoFrame(codec: MediaCodec, bitmap: Bitmap, ptsUs: Long) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val required = bitmap.width * bitmap.height * 3 / 2
        if (buffer.capacity() < required) throw IllegalStateException("H.264 input buffer too small: ${buffer.capacity()} < $required")
        buffer.clear()
        argbToNv12(bitmap, buffer)
        buffer.flip()
        codec.queueInputBuffer(index, 0, buffer.remaining(), ptsUs, 0)
    }

    private fun feedVideoEos(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun feedAudio(codec: MediaCodec, wav: WavPcmReader): Boolean {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return true
        val buffer = codec.getInputBuffer(index) ?: return true
        val read = wav.readInto(buffer)
        if (read > 0) codec.queueInputBuffer(index, 0, read, wav.currentPtsUs(), 0)
        else codec.queueInputBuffer(index, 0, 0, wav.currentPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return read > 0
    }

    private fun drain(codec: MediaCodec, video: Boolean, track: Int, muxer: MediaMuxer, sink: (MuxSample) -> Unit): Int {
        var currentTrack = track
        while (true) {
            val info = MediaCodec.BufferInfo()
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return currentTrack
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    currentTrack = muxer.addTrack(codec.outputFormat); if (!video) { /* no-op */ }
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val copy = ByteBuffer.allocate(info.size)
                        val oldPos = output.position(); output.position(info.offset); val dup = output.slice(); dup.limit(info.size); copy.put(dup); copy.flip(); output.position(oldPos)
                        sink(MuxSample(video, copy, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }))
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return currentTrack
                }
            }
        }
    }

    private fun videoOutputEosReached(codec: MediaCodec): Boolean = outputEos.getOrPut(codec) { false }
    private fun audioOutputEosReached(codec: MediaCodec): Boolean = outputEos.getOrPut(codec) { false }

    private val outputEos = java.util.WeakHashMap<MediaCodec, Boolean>()

    private data class MuxSample(val video: Boolean, val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)

    private fun argbToNv12(bitmap: Bitmap, out: ByteBuffer) {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            out.put(((66 * r + 129 * g + 25 * b + 128) shr 8 + 16).coerceIn(0,255).toByte()); i++
        }
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            var r = 0; var g = 0; var b = 0; var count = 0
            for (dy in 0..1) for (dx in 0..1) { val xx=x+dx; val yy=y+dy; if (xx<w && yy<h) { val c=pixels[yy*w+xx]; r+=(c shr 16) and 255; g+=(c shr 8) and 255; b+=c and 255; count++ } }
            r/=count; g/=count; b/=count
            val u = ((-38*r - 74*g + 112*b + 128) shr 8 + 128).coerceIn(0,255)
            val v = ((112*r - 94*g - 18*b + 128) shr 8 + 128).coerceIn(0,255)
            out.put(u.toByte()); out.put(v.toByte())
        }
    }
}

private class WavPcmReader(private val file: File) {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    private val dataStart: Long
    private val dataSize: Long
    private var readBytes = 0L
    init {
        require(raf.readInt() == 0x52494646) { "Not a RIFF WAV" }
        raf.skipBytes(4); require(raf.readInt() == 0x57415645) { "Not a WAVE file" }
        raf.seek(22); channels = readShortLE(); sampleRate = readIntLE(); raf.seek(34); require(readShortLE() == 16) { "Only PCM16 WAV is supported" }
        raf.seek(36); var data = -1L; var start = -1L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = raf.readInt(); val size = readIntLE().toLong();
            if (id == 0x64617461) { start = raf.filePointer; data = size; break }
            raf.seek(raf.filePointer + size + (size and 1L))
        }
        require(start >= 0 && data >= 0) { "WAV data chunk missing" }; dataStart = start; dataSize = data
    }
    val durationUs: Long get() = dataSize * 1_000_000L / (sampleRate.toLong() * channels.toLong() * 2L)
    fun currentPtsUs(): Long = readBytes * 1_000_000L / (sampleRate.toLong() * channels.toLong() * 2L)
    fun readInto(buffer: ByteBuffer): Int {
        if (readBytes >= dataSize) return 0
        val max = minOf(buffer.remaining(), (dataSize - readBytes).toInt())
        val tmp = ByteArray(max); raf.readFully(tmp); buffer.put(tmp); readBytes += max; return max
    }
    fun close() = raf.close()
    private fun readIntLE(): Int = Integer.reverseBytes(raf.readInt())
    private fun readShortLE(): Int = java.lang.Short.reverseBytes(raf.readShort()).toInt()
}
