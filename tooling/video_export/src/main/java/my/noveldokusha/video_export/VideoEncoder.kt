package my.noveldokusha.video_export

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import my.noveldokusha.text_to_speech.TtsExportException
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Чистая математика тайм-координат кодирования (JVM-тестируемая, без codec'ов).
 *
 * Базовая шкала — микросекунды обеих дорожек:
 *   audio: samplePosition * 1e6 / sampleRate  (+ поправка примирования AAC)
 *   video: frameIndex * 1e6 / FPS
 */
object EncodeTiming {

    /** Позиция аудио в мкс; [offsetUs] — измеренная SyncProbe поправка примирования. */
    fun audioPtsUs(samplePosition: Long, sampleRate: Int, offsetUs: Long = 0L): Long =
        (samplePosition * 1_000_000L / sampleRate + offsetUs).coerceAtLeast(0L)

    /** Позиция кадра в мкс. */
    fun videoPtsUs(frameIndex: Long): Long = frameIndex * 1_000_000L / VideoLayoutSpec.FPS

    /** Длительность аудио в мкс. */
    fun durationUs(totalSamples: Long, sampleRate: Int): Long =
        totalSamples * 1_000_000L / sampleRate

    /**
     * Число кадров 30fps, полностью покрывающих аудио (включая хвост-остаток),
     * но минимум один кадр. Точная целочисленная арифметика: никаких плавающих
     * ошибок (ровно 300 кадров на 10с, а не ceil(300.000003)).
     */
    fun frameCount(totalSamples: Long, sampleRate: Int): Int {
        if (totalSamples <= 0) return 1
        val frames = (totalSamples * VideoLayoutSpec.FPS + sampleRate - 1) / sampleRate
        return frames.toInt().coerceIn(1, Int.MAX_VALUE)
    }

    /** Семпл аудио на начало кадра [frameIndex] (не выходит за [totalSamples]). */
    fun sampleForFrame(frameIndex: Long, sampleRate: Int, totalSamples: Long): Long =
        (frameIndex * sampleRate / VideoLayoutSpec.FPS.toLong())
            .coerceIn(0L, totalSamples)
}

/**
 * Потоковый читатель 16-bit PCM WAV (44-байтовый заголовок от WavWriter).
 * Отдаёт сырые байты кадрами (channels*2 байта), формат ровно как в RIFF.
 */
class WavPcmSource(file: File) : Closeable {

    val sampleRate: Int
    val channelCount: Int
    val totalSamples: Long

    private val input = FileInputStream(file)

    init {
        val header = ByteArray(44)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < header.size) throw TtsExportException("WAV too short (no full header)")
        if (header[0] != 'R'.code.toByte() || header[8] != 'W'.code.toByte()) {
            throw TtsExportException("Not a RIFF WAV file")
        }
        val sampleFormat = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
        if (sampleFormat != 1) throw TtsExportException("WAV is not PCM16 (format=$sampleFormat)")
        channelCount = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
        sampleRate = le32(header, 24)
        val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        if (bits != 16 || channelCount <= 0 || sampleRate <= 0) {
            throw TtsExportException("Unsupported WAV params (bits=$bits ch=$channelCount rate=$sampleRate)")
        }
        val dataSize = le32(header, 40).toLong()
        totalSamples = dataSize / (channelCount * 2L)
    }

    private fun le32(h: ByteArray, off: Int): Int =
        (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8) or
            ((h[off + 2].toInt() and 0xFF) shl 16) or ((h[off + 3].toInt() and 0xFF) shl 24)

    /** Байт данных на один семпл-кадр (channels*2). */
    val frameBytes: Int get() = channelCount * 2

    /** Читает до [count] байт данных (кратно [frameBytes]); 0 на конце. */
    fun readBytes(out: ByteArray, count: Int): Int {
        val usable = (count / frameBytes) * frameBytes
        if (usable == 0) return 0
        var total = 0
        while (total < usable) {
            val n = input.read(out, total, usable - total)
            if (n <= 0) break
            total += n
        }
        return (total / frameBytes) * frameBytes
    }

    /** Возвращает моно-флоаты PCM [-1, 1] (среднее каналов) — для SyncProbe. */
    fun readAllMono(): FloatArray {
        val bytes = ByteArray(frameBytes)
        val capacity = totalSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = FloatArray(capacity)
        var i = 0
        while (i < capacity) {
            val n = readBytes(bytes, bytes.size)
            if (n < bytes.size) break
            var acc = 0
            for (c in 0 until channelCount) {
                acc += (bytes[c * 2].toInt() and 0xFF) or ((bytes[c * 2 + 1].toInt()) shl 8)
            }
            out[i++] = acc.toFloat() / (channelCount * 32768f)
        }
        return out.copyOf(i)
    }

    override fun close() {
        runCatching { input.close() }
    }
}

/**
 * Кодирует готовый WAV (stage 1: синтез + тайминги) в финальный MP4:
 *   H.264 1920x1080 8Mbps 30fps, I-frame 1s, входной Surface;
 *   AAC-LC от битрейта и формата синтеза;
 *   MediaMuxer MPEG_4.
 *
 * Каждый кадр рисуется [VideoFrameRenderer.renderFrame] на свап-сёрфейс кодека
 * (без промежуточного Bitmap). Аудио подкармливается с лидом ~250 мс — кадровый
 * график задаётся видео, аудио-PTS скорректирован поправкой примирования AAC.
 */
class VideoEncoder(
    private val videoBitRate: Int = DEFAULT_VIDEO_BIT_RATE,
    private val audioBitRate: Int = DEFAULT_AUDIO_BIT_RATE,
) {

    private val audioChunkBytes = 4096

    fun encode(
        wav: File,
        timeline: VideoExportTimeline,
        renderer: VideoFrameRenderer,
        output: File,
        aacPrimingOffsetUs: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ) {
        val sampleRate = timeline.sampleRate
        val channels = timeline.channelCount

        // Ground truth of what is actually on disk (WAV header), not just the
        // timeline estimate. Every frame count / feed target below uses this so
        // the video track covers exactly the real audio and a tail is never
        // truncated or hung far past the last real sample.
        if (output.exists()) runCatching { output.delete() }

        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var surface: Surface? = null
        WavPcmSource(wav).use { source ->
            val audioTotalSamples = source.totalSamples
            val totalFrames = EncodeTiming.frameCount(audioTotalSamples, sampleRate)
            val audioLeadSamples = sampleRate / 4L
            val muxer = MuxerSink(output.absolutePath)
            try {
                // Diagnostics: capture the real audio length and target frame
                // count so a bad build is identifiable from logcat without a
                // debugging session.
                android.util.Log.i(
                    "VideoExport", "encode start: timeline.totalSamples=${timeline.totalSamples} " +
                        "wav.totalSamples=$audioTotalSamples sampleRate=$sampleRate ch=$channels " +
                        "totalFrames=$totalFrames aacPrimingUs=$aacPrimingOffsetUs " +
                        "timelineAudioMs=${timeline.totalSamples * 1000L / sampleRate}"
                )
                videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(videoFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                surface = videoCodec!!.createInputSurface()
                videoCodec!!.start()

                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(audioFormat(sampleRate, channels), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                audioCodec!!.start()

                var nextAudioSample = 0L
                var audioEos = false
                val audioBuffer = ByteArray(audioChunkBytes)

                for (frame in 0L until totalFrames) {
                    val frameSample = EncodeTiming.sampleForFrame(frame, sampleRate, audioTotalSamples)

                    // Подкармливаем аудио с лидом, чтобы буфер кодека не пустел.
                    while (!audioEos && nextAudioSample < frameSample + audioLeadSamples) {
                        val n = source.readBytes(audioBuffer, audioBuffer.size)
                        if (n <= 0) {
                            audioEos = true
                            break
                        } else {
                            queueAac(
                                audioCodec!!, audioBuffer, n, sampleRate, nextAudioSample, aacPrimingOffsetUs,
                            )
                            nextAudioSample += (n / (channels * 2L)).coerceAtLeast(1)
                        }
                        drainAudio(audioCodec!!, muxer)
                    }

                    // Кадр: рендер на свап-сёрфейс и сразу дренав H.264.
                    val canvas = surface!!.lockCanvas(null)
                        ?: throw TtsExportException("Video encoder surface lock failed")
                    renderer.renderFrame(canvas, frameSample)
                    surface!!.unlockCanvasAndPost(canvas)
                    drainVideo(videoCodec!!, muxer)
                    onProgress((frame + 1).toFloat() / totalFrames.toFloat())
                }

                surface?.let {
                    videoCodec!!.signalEndOfInputStream()
                    drainVideo(videoCodec!!, muxer)
                }

                // Доливаем ВЕСЬ остаток аудио до конца реального WAV, а не просто
                // ставим EOS. Раньше хвост после (lastFrameSample + lead) молча
                // обрезался: кадры покрывали totalSamples, а аудио нет — A/V не
                // сходилось, и MP4-дорожки могли расходиться по длительности.
                while (!audioEos && nextAudioSample < audioTotalSamples) {
                    val n = source.readBytes(audioBuffer, audioBuffer.size)
                    if (n <= 0) {
                        audioEos = true
                        break
                    } else {
                        queueAac(
                            audioCodec!!, audioBuffer, n, sampleRate, nextAudioSample, aacPrimingOffsetUs,
                        )
                        nextAudioSample += (n / (channels * 2L)).coerceAtLeast(1)
                    }
                    drainAudio(audioCodec!!, muxer)
                }
                if (!audioEos) queueAacEnd(audioCodec!!, sampleRate, nextAudioSample, aacPrimingOffsetUs)
                drainAudio(audioCodec!!, muxer)

                android.util.Log.i(
                    "VideoExport",
                    "encode end: frames=$totalFrames audioSamplesFed=$nextAudioSample " +
                        "wavTotalSamples=$audioTotalSamples " +
                        "aacEndPtsUs=${EncodeTiming.audioPtsUs(nextAudioSample, sampleRate, aacPrimingOffsetUs)} " +
                        "muxerMaxPtsUs=${muxer.maxPresentTimeUs()} " +
                        "muxerMaxSec=${muxer.maxPresentTimeUs() / 1_000_000L}"
                )

                muxer.requireStarted()
            } catch (e: Exception) {
                throw if (e is TtsExportException) e else TtsExportException("Video encoding failed: ${e.message}", e)
            } finally {
                runCatching { audioCodec?.stop() }
                runCatching { audioCodec?.release() }
                runCatching { videoCodec?.stop() }
                runCatching { videoCodec?.release() }
                surface?.let { runCatching { it.release() } }
                runCatching { muxer.finish() }
            }
        }
    }

    private fun videoFormat(): MediaFormat = MediaFormat.createVideoFormat(
        MediaFormat.MIMETYPE_VIDEO_AVC, VideoLayoutSpec.WIDTH, VideoLayoutSpec.HEIGHT,
    ).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, VideoLayoutSpec.FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }

    private fun audioFormat(sampleRate: Int, channels: Int): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }

    private fun queueAac(
        codec: MediaCodec,
        data: ByteArray,
        size: Int,
        sampleRate: Int,
        samplePosition: Long,
        primingUs: Long,
    ) {
        val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIdx < 0) throw TtsExportException("AAC input buffer timed out")
        val buf = codec.getInputBuffer(inIdx)!!
        buf.clear()
        buf.put(data, 0, size)
        codec.queueInputBuffer(
            inIdx, 0, size,
            EncodeTiming.audioPtsUs(samplePosition, sampleRate, primingUs), 0,
        )
    }

    private fun queueAacEnd(codec: MediaCodec, sampleRate: Int, samplePosition: Long, primingUs: Long) {
        val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIdx < 0) throw TtsExportException("AAC input buffer timed out on EOS")
        codec.queueInputBuffer(
            inIdx, 0, 0,
            EncodeTiming.audioPtsUs(samplePosition, sampleRate, primingUs),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    private fun drainAudio(codec: MediaCodec, muxer: MuxerSink) {
        drain(codec, muxer, MuxerSink.Track.AUDIO)
    }

    private fun drainVideo(codec: MediaCodec, muxer: MuxerSink) {
        drain(codec, muxer, MuxerSink.Track.VIDEO)
    }

    private fun drain(codec: MediaCodec, muxer: MuxerSink, track: MuxerSink.Track) {
        val info = BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.addTrack(track, codec.outputFormat)
                idx > 0 -> {
                    val buffer = codec.getOutputBuffer(idx)
                    if (buffer != null && info.size > 0) muxer.write(track, buffer, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        const val DEFAULT_VIDEO_BIT_RATE = 8_000_000
        const val DEFAULT_AUDIO_BIT_RATE = 192_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

/**
 * Обёртка MediaMuxer: регистрирует дорожку на первый FORMAT_CHANGED, стартует
 * мьюксер, когда готовы ОБЕ дорожки; выборки, пришедшие до старта, копируются в
 * постоянные буферы и записываются после старта (ничего не теряется).
 */
private class MuxerSink(path: String) {

    enum class Track { VIDEO, AUDIO }

    var started = false
        private set

    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private val pending = ArrayDeque<Pair<Track, Pair<ByteBuffer, BufferInfo>>>()

    /** Наибольший PresentationTimeUs среди записанных выборок (≈ mvhd duration). */
    private var maxPtsUs: Long = 0L

    fun addTrack(track: Track, format: MediaFormat) {
        if (trackIndex(track) >= 0) return
        val idx = muxer.addTrack(format)
        when (track) {
            Track.VIDEO -> videoTrack = idx
            Track.AUDIO -> audioTrack = idx
        }
        tryStart()
        flushPending()
    }

    fun write(track: Track, buffer: ByteBuffer, info: BufferInfo) {
        if (info.presentationTimeUs > maxPtsUs) maxPtsUs = info.presentationTimeUs
        if (!started) {
            // Копируем: буфер кодека реиспользуется после releaseOutputBuffer.
            val src = buffer.duplicate()
            src.position(info.offset)
            src.limit(info.offset + info.size)
            val copy = ByteBuffer.allocate(info.size)
            copy.put(src)
            copy.flip()
            pending.addLast(track to (copy to copyOfInfo(info)))
            return
        }
        muxer.writeSampleData(trackIndex(track), buffer, info)
    }

    private fun flushPending() {
        if (!started) return
        while (pending.isNotEmpty()) {
            val (track, pair) = pending.removeFirst()
            muxer.writeSampleData(trackIndex(track), pair.first, pair.second)
        }
    }

    private fun copyOfInfo(info: BufferInfo): BufferInfo = BufferInfo().apply {
        offset = 0
        size = info.size
        presentationTimeUs = info.presentationTimeUs
        flags = info.flags
    }

    private fun tryStart() {
        if (started || videoTrack < 0 || audioTrack < 0) return
        muxer.start()
        started = true
    }

    fun requireStarted() {
        if (!started) throw TtsExportException("MediaMuxer: tracks not ready (video=$videoTrack audio=$audioTrack)")
    }

    /** Наибольший PTS среди записанных выборок (для диагностики длительности). */
    fun maxPresentTimeUs(): Long = maxPtsUs

    fun finish() {
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun trackIndex(track: Track): Int = when (track) {
        Track.VIDEO -> videoTrack
        Track.AUDIO -> audioTrack
    }
}