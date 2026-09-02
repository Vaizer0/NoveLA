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
import kotlin.math.min

/** Native MP4 encoder: Surface-fed H.264/AVC + AAC-LC + MediaMuxer. */
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
        require(visual.width == 1920 && visual.height == 1080)
        require(visual.fps == 30)
        require(timeline.durationUs > 0L)

        val wav = WavPcmReader(wavFile)
        require(wav.durationUs > 0L)
        require(timeline.durationUs <= wav.durationUs + 50_000L) {
            "Timeline duration ${timeline.durationUs} exceeds WAV duration ${wav.durationUs}"
        }
        val durationUs = min(timeline.durationUs, wav.durationUs)
        val frameCount = maxOf(1L, ((durationUs - 1L) * visual.fps) / 1_000_000L + 1L)

        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var surface: Surface? = null
        var eglRenderer: TtsVideoEglSurfaceRenderer? = null
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var videoOutputEos = false
        var audioOutputEos = false
        var videoInputEos = false
        var audioInputEos = false
        var frameIndex = 0L
        var encodedVideoSampleIndex = 0L
        val pending = ArrayList<PendingSample>()

        try {
            video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, visual.width, visual.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, visual.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (android.os.Build.VERSION.SDK_INT >= 29) setLong(MediaFormat.KEY_MAX_PTS_GAP_TO_ENCODER, 33_334L)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video.createInputSurface()
            audio.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            video.start()
            audio.start()
            eglRenderer = TtsVideoEglSurfaceRenderer(surface!!)

            while (!videoOutputEos || !audioOutputEos) {
                coroutineContext.ensureActive()
                if (!videoInputEos) {
                    if (frameIndex < frameCount) {
                        val pts = frameIndex * 1_000_000L / visual.fps
                        val bitmap = Bitmap.createBitmap(visual.width, visual.height, Bitmap.Config.ARGB_8888)
                        try {
                            renderer.render(android.graphics.Canvas(bitmap), timeline, visual, snapshot, pts)
                            eglRenderer!!.draw(bitmap, pts)
                        } finally { bitmap.recycle() }
                        frameIndex++
                    } else {
                        video.signalEndOfInputStream()
                        videoInputEos = true
                    }
                }
                if (!audioInputEos) {
                    val index = audio.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = audio.getInputBuffer(index) ?: error("Missing AAC input buffer")
                        input.clear()
                        val read = wav.readInto(input)
                        if (read > 0) {
                            audio.queueInputBuffer(index, 0, read, wav.currentPtsUs(), 0)
                        } else {
                            audio.queueInputBuffer(index, 0, 0, wav.currentPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputEos = true
                        }
                    }
                }

                val vResult = drain(video, true, muxer, videoTrack, muxerStarted, pending, visual.fps, encodedVideoSampleIndex)
                videoTrack = vResult.track
                videoOutputEos = videoOutputEos || vResult.eos
                muxerStarted = vResult.started
                encodedVideoSampleIndex = vResult.nextSampleIndex

                val aResult = drain(audio, false, muxer, audioTrack, muxerStarted, pending, visual.fps, encodedVideoSampleIndex)
                audioTrack = aResult.track
                audioOutputEos = audioOutputEos || aResult.eos
                muxerStarted = aResult.started

                if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                    muxer.start()
                    muxerStarted = true
                    flushPending(muxer, videoTrack, audioTrack, pending)
                }
                onProgress(frameIndex.toFloat() / frameCount.toFloat())
            }
            if (!muxerStarted || videoTrack < 0 || audioTrack < 0) {
                throw TtsExportException("MediaMuxer did not receive both encoded tracks")
            }
        } catch (e: Throwable) {
            runCatching { outputFile.delete() }
            throw e
        } finally {
            runCatching { eglRenderer?.close() }
            runCatching { video.stop() }
            runCatching { audio.stop() }
            runCatching { video.release() }
            runCatching { audio.release() }
            runCatching { surface?.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            wav.close()
        }
    }

    private data class PendingSample(val video: Boolean, val bytes: ByteBuffer, val info: MediaCodec.BufferInfo)
    private data class DrainResult(val track: Int, val eos: Boolean, val started: Boolean, val nextSampleIndex: Long)

    private fun drain(
        codec: MediaCodec,
        isVideo: Boolean,
        muxer: MediaMuxer,
        currentTrack: Int,
        started: Boolean,
        pending: MutableList<PendingSample>,
        fps: Int,
        videoSampleIndex: Long,
    ): DrainResult {
        var track = currentTrack
        var activeStarted = started
        var eos = false
        var nextVideoSampleIndex = videoSampleIndex
        while (true) {
            val info = MediaCodec.BufferInfo()
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(track, eos, activeStarted, nextVideoSampleIndex)
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track < 0) track = muxer.addTrack(codec.outputFormat)
                }
                else -> if (index >= 0) {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (track < 0) throw TtsExportException("Encoded sample arrived before output format")
                        val copy = ByteBuffer.allocate(info.size)
                        val dup = out.duplicate()
                        dup.position(info.offset.coerceAtLeast(0))
                        dup.limit((info.offset + info.size).coerceAtMost(dup.capacity()))
                        copy.put(dup).flip()
                        val ptsUs = if (isVideo) {
                            nextVideoSampleIndex * 1_000_000L / fps
                        } else {
                            info.presentationTimeUs
                        }
                        val ci = MediaCodec.BufferInfo().also { it.set(0, info.size, ptsUs, info.flags) }
                        val sample = PendingSample(isVideo, copy, ci)
                        if (activeStarted) muxer.writeSampleData(track, sample.bytes, sample.info) else pending += sample
                        if (isVideo) nextVideoSampleIndex++
                    }
                    eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return DrainResult(track, true, activeStarted, nextVideoSampleIndex)
                }
            }
        }
    }

    private fun flushPending(muxer: MediaMuxer, videoTrack: Int, audioTrack: Int, pending: MutableList<PendingSample>) {
        pending.forEach { sample ->
            muxer.writeSampleData(if (sample.video) videoTrack else audioTrack, sample.bytes, sample.info)
        }
        pending.clear()
    }
}

private class WavPcmReader(file: File) {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    private val dataSize: Long
    private var readBytes = 0L

    init {
        require(raf.readInt() == 0x52494646) { "Not RIFF" }
        raf.skipBytes(4)
        require(raf.readInt() == 0x57415645) { "Not WAVE" }
        raf.seek(22)
        channels = java.lang.Short.reverseBytes(raf.readShort()).toInt()
        sampleRate = Integer.reverseBytes(raf.readInt())
        raf.seek(34)
        require(java.lang.Short.reverseBytes(raf.readShort()).toInt() == 16) { "Only PCM16 supported" }
        raf.seek(36)
        var dataStart = -1L
        var size = -1L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = raf.readInt()
            val n = Integer.reverseBytes(raf.readInt()).toLong()
            if (id == 0x64617461) {
                dataStart = raf.filePointer
                size = n.coerceAtMost(raf.length() - raf.filePointer)
                break
            }
            raf.seek(raf.filePointer + n + (n and 1L))
        }
        require(dataStart >= 0 && size >= 0) { "WAV data missing" }
        dataSize = size
        raf.seek(dataStart)
    }

    val durationUs: Long get() = dataSize * 1_000_000L / (sampleRate.toLong() * channels * 2L)
    fun currentPtsUs(): Long = readBytes * 1_000_000L / (sampleRate.toLong() * channels * 2L)
    fun readInto(buffer: ByteBuffer): Int {
        if (readBytes >= dataSize) return 0
        val n = min(buffer.remaining().toLong(), dataSize - readBytes).toInt()
        val temp = ByteArray(n)
        raf.readFully(temp)
        buffer.put(temp)
        readBytes += n
        return n
    }
    fun close() = runCatching { raf.close() }
}
