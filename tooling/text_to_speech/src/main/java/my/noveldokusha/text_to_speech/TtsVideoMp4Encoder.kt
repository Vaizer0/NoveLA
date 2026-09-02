package my.noveldokusha.text_to_speech

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class TtsVideoMp4Encoder {
    suspend fun encode(wavFile: File, outputFile: File, timeline: TtsVideoTimeline, visual: TtsVideoVisualSettings, renderer: TtsVideoCompositionRenderer, snapshot: TtsVideoVisualSnapshot, onProgress: (Float) -> Unit = {}) {
        require(visual.width == 1920 && visual.height == 1080 && visual.fps == 30)
        val wav = WavPcmReader(wavFile)
        val videoInfo = codecInfo(MediaFormat.MIMETYPE_VIDEO_AVC)
        val videoColor = videoInfo?.colorFormats?.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
            ?: videoInfo?.colorFormats?.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar }
            ?: throw IllegalStateException("No YUV420 AVC encoder available")
        val semiPlanar = videoColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1; var audioTrack = -1; var started = false
        var videoInputEos = false; var audioInputEos = false; var videoOutputEos = false; var audioOutputEos = false
        val pending = ArrayList<MuxSample>()
        val frameCount = maxOf(1L, (wav.durationUs * 30L + 999_999L) / 1_000_000L)
        var frameIndex = 0L
        try {
            video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, videoColor); setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audio.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 128_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            video.start(); audio.start()
            while (!videoOutputEos || !audioOutputEos) {
                coroutineContext.ensureActive()
                if (!videoInputEos) {
                    val pts = frameIndex * 1_000_000L / 30L
                    if (frameIndex < frameCount) {
                        val index = video.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = video.getInputBuffer(index) ?: error("Missing AVC input buffer")
                            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                            renderer.render(android.graphics.Canvas(bitmap), timeline, visual, snapshot, pts)
                            input.clear(); encodeYuv420(bitmap, input, semiPlanar); input.flip(); video.queueInputBuffer(index, 0, input.remaining(), pts, 0); bitmap.recycle(); frameIndex++
                            onProgress(.55f * frameIndex.toFloat() / frameCount.toFloat())
                        }
                    } else {
                        val index = video.dequeueInputBuffer(10_000); if (index >= 0) { video.queueInputBuffer(index, 0, 0, ptsUs(wav.durationUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM); videoInputEos = true }
                    }
                }
                if (!audioInputEos) {
                    val index = audio.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = audio.getInputBuffer(index) ?: error("Missing AAC input buffer")
                        val read = wav.readInto(input)
                        if (read > 0) audio.queueInputBuffer(index, 0, read, wav.currentPtsUs(), 0) else { audio.queueInputBuffer(index, 0, 0, wav.currentPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM); audioInputEos = true }
                    }
                }
                val vd = drain(video, true, muxer, started, videoTrack); videoTrack = vd.track; videoOutputEos = videoOutputEos || vd.eos; pending += vd.pending
                val ad = drain(audio, false, muxer, started, audioTrack); audioTrack = ad.track; audioOutputEos = audioOutputEos || ad.eos; pending += ad.pending
                if (!started && videoTrack >= 0 && audioTrack >= 0) {
                    muxer.start(); started = true
                    pending.forEach { muxer.writeSampleData(if (it.video) videoTrack else audioTrack, it.buffer, it.info) }; pending.clear()
                } else if (started && pending.isNotEmpty()) {
                    pending.forEach { muxer.writeSampleData(if (it.video) videoTrack else audioTrack, it.buffer, it.info) }; pending.clear()
                }
                onProgress(.55f + .45f * (if (frameCount == 0L) 1f else frameIndex.toFloat() / frameCount.toFloat()).coerceIn(0f, 1f))
            }
        } finally {
            runCatching { video.stop() }; runCatching { audio.stop() }; runCatching { video.release() }; runCatching { audio.release() }
            if (started) runCatching { muxer.stop() }; runCatching { muxer.release() }; runCatching { wav.close() }
        }
    }

    private fun codecInfo(type: String): MediaCodecInfo? = runCatching {
        (0 until MediaCodecListCompat.count()).asSequence().mapNotNull { i -> MediaCodecListCompat.info(i) }
            .firstOrNull { it.isEncoder && it.supportsType(type) }
    }.getOrNull()

    private fun drain(codec: MediaCodec, video: Boolean, muxer: MediaMuxer, started: Boolean, track: Int): DrainResult {
        var currentTrack = track; var eos = false; val pending = ArrayList<MuxSample>()
        while (true) {
            val info = MediaCodec.BufferInfo(); val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(currentTrack, eos, pending)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> currentTrack = muxer.addTrack(codec.outputFormat)
                index < 0 -> Unit
                else -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0 && info.presentationTimeUs >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val copy = ByteBuffer.allocate(info.size); val dup = output.duplicate(); dup.position(info.offset); dup.limit(info.offset + info.size); copy.put(dup).flip()
                        val copiedInfo = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                        val sample = MuxSample(video, copy, copiedInfo)
                        if (started) muxer.writeSampleData(currentTrack, sample.buffer, sample.info) else pending += sample
                    }
                    eos = eos || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return DrainResult(currentTrack, true, pending)
                }
            }
        }
    }

    private fun encodeYuv420(bitmap: Bitmap, out: ByteBuffer, semiPlanar: Boolean) {
        val w = bitmap.width; val h = bitmap.height; val px = IntArray(w * h); bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) { val c = px[y*w+x]; val r=c shr 16 and 255; val g=c shr 8 and 255; val b=c and 255; out.put(((66*r+129*g+25*b+128)/256+16).coerceIn(0,255).toByte()) }
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            var r=0; var g=0; var b=0; var n=0
            for (dy in 0..1) for (dx in 0..1) { val xx=x+dx; val yy=y+dy; if (xx<w && yy<h) { val c=px[yy*w+xx]; r+=c shr 16 and 255; g+=c shr 8 and 255; b+=c and 255; n++ } }
            r/=n; g/=n; b/=n; val u=(-38*r-74*g+112*b+128)/256+128; val v=(112*r-94*g-18*b+128)/256+128
            if (semiPlanar) { out.put(u.coerceIn(0,255).toByte()); out.put(v.coerceIn(0,255).toByte()) } else { out.put(u.coerceIn(0,255).toByte()) }
        }
        if (!semiPlanar) for (y in 0 until h step 2) for (x in 0 until w step 2) { var r=0; var g=0; var b=0; var n=0; for (dy in 0..1) for (dx in 0..1) { val xx=x+dx; val yy=y+dy; if(xx<w&&yy<h){val c=px[yy*w+xx];r+=c shr 16 and 255;g+=c shr 8 and 255;b+=c and 255;n++}};r/=n;g/=n;b/=n;out.put(((112*r-94*g-18*b+128)/256+128).coerceIn(0,255).toByte()) }
    }

    private fun ptsUs(duration: Long) = duration.coerceAtLeast(0L)
    private data class MuxSample(val video: Boolean, val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)
    private data class DrainResult(val track: Int, val eos: Boolean, val pending: List<MuxSample>)
}

/** API-compatible wrapper avoids depending on codec-list API details in the renderer/worker layer. */
private object MediaCodecListCompat {
    fun count() = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.size
    fun info(i: Int) = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.getOrNull(i)
}

private class WavPcmReader(file: File) {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int; val channels: Int; private val dataSize: Long; private var readBytes = 0L
    init {
        require(raf.readInt() == 0x52494646); raf.skipBytes(4); require(raf.readInt() == 0x57415645)
        raf.seek(22); channels = Integer.reverseBytes(raf.readShort()).toInt(); sampleRate = Integer.reverseBytes(raf.readInt())
        raf.seek(34); require(Integer.reverseBytes(raf.readShort()).toInt() == 16)
        raf.seek(36); var data = -1L; var start = -1L
        while (raf.filePointer + 8 <= raf.length()) { val id=raf.readInt(); val size=Integer.reverseBytes(raf.readInt()).toLong(); if(id==0x64617461){start=raf.filePointer;data=size;break};raf.seek(raf.filePointer+size+(size and 1)) }
        require(start>=0&&data>=0); dataSize=data; raf.seek(start)
    }
    val durationUs get() = dataSize*1_000_000L/(sampleRate.toLong()*channels*2L)
    fun currentPtsUs() = readBytes*1_000_000L/(sampleRate.toLong()*channels*2L)
    fun readInto(buffer: ByteBuffer): Int { if(readBytes>=dataSize)return 0; val n=minOf(buffer.remaining(),(dataSize-readBytes).toInt()); val a=ByteArray(n);raf.readFully(a);buffer.put(a);readBytes+=n;return n }
    fun close()=raf.close()
}
