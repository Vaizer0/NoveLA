package my.noveldokusha.text_to_speech

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Native MP4 encoder: Surface-fed H.264/AVC + AAC-LC + MediaMuxer. */
class TtsVideoMp4Encoder {
    suspend fun encode(wavFile: File, outputFile: File, timeline: TtsVideoTimeline, visual: TtsVideoVisualSettings, renderer: TtsVideoCompositionRenderer, snapshot: TtsVideoVisualSnapshot, onProgress: (Float) -> Unit = {}) {
        require(visual.width == 1920 && visual.height == 1080 && visual.fps == 30)
        val wav = WavPcmReader(wavFile)
        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var surface: Surface? = null
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var videoOutputEos = false
        var audioOutputEos = false
        var videoInputEos = false
        var audioInputEos = false
        var frameIndex = 0L
        val frameCount = maxOf(1L, (wav.durationUs * visual.fps + 999_999L) / 1_000_000L)
        val pending = ArrayList<PendingSample>()
        try {
            video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, visual.width, visual.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, visual.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video.createInputSurface()
            audio.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            video.start(); audio.start()
            while (!videoOutputEos || !audioOutputEos) {
                coroutineContext.ensureActive()
                if (!videoInputEos) {
                    if (frameIndex < frameCount) {
                        val pts = frameIndex * 1_000_000L / visual.fps
                        val bitmap = Bitmap.createBitmap(visual.width, visual.height, Bitmap.Config.ARGB_8888)
                        renderer.renderFrame(android.graphics.Canvas(bitmap), timeline, visual, snapshot, pts)
                        val canvas = surface.lockCanvas(null) ?: error("Unable to lock encoder surface")
                        try { canvas.drawBitmap(bitmap, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(canvas) }
                        bitmap.recycle()
                        frameIndex++
                        onProgress(.55f * frameIndex.toFloat() / frameCount.toFloat())
                    } else {
                        video.signalEndOfInputStream(); videoInputEos = true
                    }
                }
                if (!audioInputEos) {
                    val index = audio.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = audio.getInputBuffer(index) ?: error("Missing AAC input buffer")
                        val read = wav.readInto(input)
                        if (read > 0) audio.queueInputBuffer(index, 0, read, wav.currentPtsUs(), 0)
                        else { audio.queueInputBuffer(index, 0, 0, wav.currentPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM); audioInputEos = true }
                    }
                }
                videoTrack = drain(video, true, muxer, videoTrack, muxerStarted, pending).also { result -> videoOutputEos = videoOutputEos || result.eos; muxerStarted = result.started }.track
                audioTrack = drain(audio, false, muxer, audioTrack, muxerStarted, pending).also { result -> audioOutputEos = audioOutputEos || result.eos; muxerStarted = result.started }.track
                if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) { muxer.start(); muxerStarted = true; flushPending(muxer, videoTrack, audioTrack, pending) }
                onProgress(.55f + .45f * (frameIndex.toFloat() / frameCount.toFloat()).coerceIn(0f,1f))
            }
            if (!muxerStarted) throw TtsExportException("MediaMuxer never received both tracks")
        } finally {
            runCatching { video.stop() }; runCatching { audio.stop() }; runCatching { video.release() }; runCatching { audio.release() }; runCatching { surface?.release() }
            if (muxerStarted) runCatching { muxer.stop() }; runCatching { muxer.release() }; wav.close()
        }
    }

    private data class PendingSample(val video:Boolean,val bytes:ByteBuffer,val info:MediaCodec.BufferInfo)
    private data class DrainResult(val track:Int,val eos:Boolean,val started:Boolean)

    private fun drain(codec:MediaCodec,isVideo:Boolean,muxer:MediaMuxer,currentTrack:Int,started:Boolean,pending:MutableList<PendingSample>):DrainResult{
        var track=currentTrack;var activeStarted=started;var eos=false
        while(true){
            val info=MediaCodec.BufferInfo();val index=codec.dequeueOutputBuffer(info,0)
            when{
                index==MediaCodec.INFO_TRY_AGAIN_LATER->return DrainResult(track,eos,activeStarted)
                index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{track=muxer.addTrack(codec.outputFormat)}
                index>=0->{val out=codec.getOutputBuffer(index);if(out!=null&&info.size>0&&(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){val copy=ByteBuffer.allocate(info.size);val dup=out.duplicate();dup.position(info.offset);dup.limit(info.offset+info.size);copy.put(dup);copy.flip();val ci=MediaCodec.BufferInfo().also{it.set(0,info.size,info.presentationTimeUs,info.flags)};val sample=PendingSample(isVideo,copy,ci);if(activeStarted)muxer.writeSampleData(track,sample.bytes,sample.info)else pending+=sample};eos=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);if(eos)return DrainResult(track,true,activeStarted)}
            }
        }
    }
    private fun flushPending(m:MediaMuxer,videoTrack:Int,audioTrack:Int,p:MutableList<PendingSample>){p.forEach{s->m.writeSampleData(if(s.video)videoTrack else audioTrack,s.bytes,s.info)};p.clear()}
}

private class WavPcmReader(file:File){
    private val raf=RandomAccessFile(file,"r")
    val sampleRate:Int
    val channels:Int
    private val dataSize:Long
    private var readBytes=0L
    init{
        require(raf.readInt()==0x52494646){"Not RIFF"};raf.skipBytes(4);require(raf.readInt()==0x57415645){"Not WAVE"}
        raf.seek(22);channels=java.lang.Short.reverseBytes(raf.readShort()).toInt();sampleRate=Integer.reverseBytes(raf.readInt());raf.seek(34);require(java.lang.Short.reverseBytes(raf.readShort()).toInt()==16){"Only PCM16 supported"}
        raf.seek(36);var start=-1L;var size=-1L
        while(raf.filePointer+8<=raf.length()){val id=raf.readInt();val n=Integer.reverseBytes(raf.readInt()).toLong();if(id==0x64617461){start=raf.filePointer;size=n;break};raf.seek(raf.filePointer+n+(n and 1))}
        require(start>=0&&size>=0){"WAV data missing"};dataSize=size;raf.seek(start)
    }
    val durationUs:Long get()=dataSize*1_000_000L/(sampleRate.toLong()*channels*2L)
    fun currentPtsUs():Long=readBytes*1_000_000L/(sampleRate.toLong()*channels*2L)
    fun readInto(b:ByteBuffer):Int{if(readBytes>=dataSize)return 0;val n=minOf(b.remaining(),(dataSize-readBytes).toInt());val tmp=ByteArray(n);raf.readFully(tmp);b.put(tmp);readBytes+=n;return n}
    fun close()=runCatching{raf.close()}
}
