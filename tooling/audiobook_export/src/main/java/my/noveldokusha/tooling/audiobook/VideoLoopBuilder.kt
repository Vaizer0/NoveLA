package my.noveldokusha.tooling.audiobook

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * Сборка финального MP4: закодированный звук аудиокниги + зацикленный визуал.
 *
 * Главное правило — визуальный ряд **не рендерится** на всю длительность.
 * Короткий нормализованный сегмент читается как готовые сэмплы, его
 * временные метки сдвигаются, и он повторяется до полной длительности
 * аудио. Стоимость пропорциональна размеру исходника, а не длине книги.
 */
class VideoLoopBuilder {

    /**
     * Создаёт MP4 с зацикленным визуалом на всю длительность аудио.
     *
     * @param audioWav уже смёрженный WAV — единственный источник звука,
     *   повторный синтез TTS ради MP4 не выполняется.
     */
    fun build(
        audioWav: File,
        visualSegment: NormalizedVisualSegment,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): Mp4Result {
        if (!audioWav.exists()) throw IOException("audio file is missing: $audioWav")
        val audioDurationMs = WavAudio.durationMs(audioWav)
        if (audioDurationMs <= 0L) throw IOException("audio file is empty: $audioWav")

        // Аудио кодируется в AAC один раз; готовые сэмплы дальше
        // переиспользуются без повторного декодирования.
        AacAudioEncoder().encode(audioWav).use { encodedAudio ->
            buildWithEncodedAudio(encodedAudio.file, visualSegment.file, audioDurationMs, target, onProgress)
        }

        val result = Mp4Result(
            file = target,
            audioDurationMs = audioDurationMs,
            videoDurationMs = probeVideoDurationMs(target),
        )
        return result
    }

    /** Собирает MP4 из уже закодированного AAC и готового визуального сегмента. */
    private fun buildWithEncodedAudio(
        encodedAudioFile: File,
        visualSegmentFile: File,
        audioDurationMs: Long,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        runCatching { target.delete() }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val audioTrack = audioTrackOf(encodedAudioFile, muxer)
            val videoTrack = addVideoTrack(visualSegmentFile, muxer)
            muxer.start()
            started = true
            writeAudioSamples(muxer, audioTrack, encodedAudioFile)
            writeLoopedVideo(visualSegmentFile, muxer, videoTrack, audioDurationMs, onProgress)
        } catch (e: Exception) {
            runCatching { muxer.release() }
            runCatching { target.delete() }
            throw if (e is IOException) e else IOException("MP4 assembly failed: ${e.message}", e)
        } finally {
            // MediaMuxer.stop() обязан вызываться до release(), иначе
            // moov-атом не пишется и файл остаётся нечитаемым.
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        if (target.length() == 0L) {
            runCatching { target.delete() }
            throw IOException("MP4 output is empty")
        }
    }

    /** Добавляет аудиодорожку из закодированного AAC-файла в муксер. */
    private fun audioTrackOf(aacFile: File, muxer: MediaMuxer): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(aacFile.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("encoded audio has no audio track")
            return muxer.addTrack(extractor.getTrackFormat(index))
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Находит видеодорожку сегмента и добавляет её в муксер. */
    private fun addVideoTrack(segmentFile: File, muxer: MediaMuxer): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segmentFile.absolutePath)
            return muxer.addTrack(extractor.getTrackFormat(videoTrackIndexOf(extractor, segmentFile)))
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Записывает видеодорожку, повторяя закодированные сэмплы сегмента со
     * сдвигом временных меток до полной длительности аудио.
     *
     * Это и есть главная оптимизация MP4: стоимость пропорциональна длине
     * сегмента, а не длине аудиокниги.
     */
    private fun writeLoopedVideo(
        segmentFile: File,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segmentFile.absolutePath)
            val videoTrackIndex = videoTrackIndexOf(extractor, segmentFile)
            extractor.selectTrack(videoTrackIndex)
            val frameRate = maxOf(1, extractor.getTrackFormat(videoTrackIndex).frameRateGuess())

            val segmentSamples = readVideoSamples(extractor)
            if (segmentSamples.isEmpty()) throw IOException("visual segment has no samples")

            val frameDurationUs = 1_000_000L / frameRate
            val lastSample = segmentSamples.last()
            // Длительность сегмента = PTS последнего кадра + длительность кадра.
            // Раньше здесь к PTS прибавлялся размер кадра в байтах — формула
            // была размерно неверной и давала произвольный результат.
            val segmentDurationUs = lastSample.presentationTimeUs + frameDurationUs
            if (segmentDurationUs <= 0L) throw IOException("visual segment has zero duration")

            val audioUs = audioDurationMs * 1000L
            val iterations = audioUs / segmentDurationUs + 1
            val bufferInfo = MediaCodec.BufferInfo()
            var written = 0L

            loop@ for (iteration in 0 until iterations) {
                val timeOffsetUs = iteration * segmentDurationUs
                for (sample in segmentSamples) {
                    val targetUs = timeOffsetUs + sample.presentationTimeUs
                    // Последняя итерация обрезается точно по аудио:
                    // видео не должно оказаться длиннее звука.
                    if (targetUs >= audioUs) break@loop
                    bufferInfo.offset = 0
                    bufferInfo.size = sample.size
                    bufferInfo.presentationTimeUs = targetUs
                    bufferInfo.flags = sample.flags
                    muxer.writeSampleData(videoTrack, sample.buffer, bufferInfo)
                    written++
                    if (written % PROGRESS_SAMPLE_INTERVAL == 0L) {
                        onProgress((targetUs.toFloat() / audioUs).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Индекс первой видеодорожки контейнера. */
    private fun videoTrackIndexOf(extractor: MediaExtractor, source: File): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: throw IOException("no video track in ${source.name}")

    /** Один закодированный видеосэмпл сегмента. */
    private class VideoSample(
        val buffer: java.nio.ByteBuffer,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    /**
     * Читает все закодированные видеосэмплы сегмента в память.
     *
     * Это допустимо: сегмент короткий (1–5 секунд), в отличие от аудиокниги.
     * Именно поэтому дальше идёт повторное использование, а не рендеринг.
     */
    private fun readVideoSamples(extractor: MediaExtractor): List<VideoSample> {
        val samples = mutableListOf<VideoSample>()
        val buffer = java.nio.ByteBuffer.allocate(SAMPLE_BUFFER_SIZE)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val duplicated = buffer.duplicate()
            samples += VideoSample(
                buffer = java.nio.ByteBuffer.wrap(
                    java.util.Arrays.copyOfRange(duplicated.array(), duplicated.arrayOffset(), duplicated.arrayOffset() + size),
                ),
                size = size,
                presentationTimeUs = extractor.sampleTime,
                flags = extractor.sampleFlags,
            )
            extractor.advance()
        }
        return samples
    }

    /**
     * Потоково переносит AAC-сэмплы из закодированного файла в муксер.
     *
     * Сэмплы не накапливаются в памяти: в отличие от списка, такое чтение
     * не зависит от длительности книги (несколько часов аудио — это сотни
     * мегабайт, которые незачем держать в куче).
     */
    private fun writeAudioSamples(muxer: MediaMuxer, trackIndex: Int, aacFile: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(aacFile.absolutePath)
            val sourceTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("encoded audio has no audio track")
            extractor.selectTrack(sourceTrack)

            val buffer = java.nio.ByteBuffer.allocate(SAMPLE_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0)
                buffer.limit(size)
                bufferInfo.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun probeVideoDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0L
            extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION) / 1000L
        } catch (e: Exception) {
            Timber.w(e, "VideoLoopBuilder: video duration probe failed")
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.frameRateGuess(): Int =
        if (containsKey(MediaFormat.KEY_FRAME_RATE)) getInteger(MediaFormat.KEY_FRAME_RATE) else TARGET_FPS

    private companion object {
        const val TARGET_FPS = 4
        const val SAMPLE_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_SAMPLE_INTERVAL = 2_000L
    }
}

/** Результат сборки MP4 с фактическими длительностями дорожек. */
data class Mp4Result(
    val file: File,
    val audioDurationMs: Long,
    val videoDurationMs: Long,
) {
    /** Допустимое расхождение видео и аудио — один кадр при TARGET_FPS. */
    fun durationsMatch(toleranceMs: Long = 1_000L): Boolean =
        kotlin.math.abs(audioDurationMs - videoDurationMs) <= toleranceMs
}

/**
 * Кодирование смёрженного WAV в AAC-дорожку для MP4.
 *
 * WAV → AAC выполняется ровно один раз. Дальше AAC-сэмплы переиспользуются
 * как есть, без повторного декодирования.
 */
internal class AacAudioEncoder {

    fun encode(wavFile: File): EncodedAudio {
        val segment = WavAudio.readSegment(wavFile)
        val outputFile = File.createTempFile("novela_aac_", ".aac", wavFile.parentFile)
        val format = MediaFormat.createAudioFormat(MIME_AUDIO_AAC, segment.sampleRateHz, segment.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        val codec = MediaCodec.createEncoderByType(MIME_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var started = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // PCM читается блоками прямо из WAV: полный объём аудиокниги
            // в память не попадает, в отличие от ByteArrayOutputStream.
            RandomAccessFile(wavFile, "r").use { raf ->
                raf.seek(segment.dataOffset)
                val bytesPerFrame = segment.bytesPerFrame
                var bytesSubmitted = 0L
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val toRead = minOf(
                                    segment.dataLength - bytesSubmitted,
                                    inputBuffer.remaining().toLong(),
                                    (PCM_CHUNK_FRAMES * bytesPerFrame).toLong(),
                                ).toInt()
                                if (toRead <= 0) {
                                    codec.queueInputBuffer(
                                        inputIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    val chunk = ByteArray(toRead)
                                    raf.readFully(chunk)
                                    inputBuffer.put(chunk)
                                    // Метки времени идут от реальной позиции в
                                    // аудио, иначе AAC-дорожка получится без
                                    // длительности.
                                    val presentationTimeUs = bytesSubmitted * 1_000_000L / segment.sampleRateHz
                                    codec.queueInputBuffer(inputIndex, 0, toRead, presentationTimeUs, 0)
                                    bytesSubmitted += toRead
                                }
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = codec.getOutputBuffer(outputIndex)
                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (encoded != null && bufferInfo.size > 0 && !isConfig && started) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }

        if (!started) {
            runCatching { outputFile.delete() }
            throw IOException("AAC encoder produced no output")
        }
        return EncodedAudio(outputFile, format)
    }

    /** Закодированная AAC-дорожка во временном файле. */
    class EncodedAudio(val file: File, val format: MediaFormat) : AutoCloseable {
        override fun close() {
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val MIME_AUDIO_AAC = "audio/mp4a-latm"
        const val AAC_BITRATE = 96_000
        const val MAX_INPUT_SIZE = 16 * 1024
        const val TIMEOUT_US = 10_000L
        const val PCM_CHUNK_FRAMES = 8192
    }
}
