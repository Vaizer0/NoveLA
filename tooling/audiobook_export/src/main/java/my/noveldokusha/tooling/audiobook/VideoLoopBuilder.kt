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
     * Создаёт MP4 из готового смёрженного WAV.
     *
     * Оставлено для случаев, когда звук уже лежит единым WAV. Экспорт
     * аудиокниги этим путём не пользуется: он кодирует PCM в AAC прямо во
     * время синтеза через [StreamingAacEncoder] и зовёт
     * [buildFromEncodedAudio], чтобы не писать и не перечитывать
     * многогигабайтный промежуточный WAV.
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
        AacAudioEncoder().encode(audioWav).use { encodedAudio ->
            return buildFromEncodedAudio(
                encodedAudioFile = encodedAudio.file,
                audioDurationMs = audioDurationMs,
                visualSegment = visualSegment,
                target = target,
                onProgress = onProgress,
            )
        }
    }

    /**
     * Собирает MP4 из уже закодированной AAC-дорожки и зацикленного визуала.
     *
     * Это основной путь экспорта: AAC приходит потоком из синтеза, WAV не
     * создаётся вовсе. Стоимость визуальной части пропорциональна длине
     * исходного сегмента, а не длительности книги.
     */
    fun buildFromEncodedAudio(
        encodedAudioFile: File,
        audioDurationMs: Long,
        visualSegment: NormalizedVisualSegment,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): Mp4Result {
        if (!encodedAudioFile.exists() || encodedAudioFile.length() == 0L) {
            throw IOException("encoded audio is missing or empty: $encodedAudioFile")
        }
        if (audioDurationMs <= 0L) throw IOException("audio duration is not positive: $audioDurationMs")

        AudiobookExportDebug.log(
            "MP4 build start: audio=${audioDurationMs}ms visual=${visualSegment.file.name} " +
                "expectedVisual=${visualSegment.expectedDurationMs}ms",
        )
        val startedAtMs = System.currentTimeMillis()
        buildWithEncodedAudio(encodedAudioFile, visualSegment, audioDurationMs, target, onProgress)
        AudiobookExportDebug.log(
            "MP4 build done in ${System.currentTimeMillis() - startedAtMs}ms size=${target.length()}",
        )

        val durations = probeTrackDurations(target)
        AudiobookExportDebug.log(
            "MP4 tracks: audio=${durations.audioMs}ms video=${durations.videoMs}ms " +
                "container=${durations.containerMs}ms requestedAudio=${audioDurationMs}ms " +
                "size=${target.length()}",
        )
        return Mp4Result(
            file = target,
            audioDurationMs = durations.audioMs.takeIf { it > 0L } ?: audioDurationMs,
            videoDurationMs = durations.videoMs,
            containerDurationMs = durations.containerMs,
        )
    }

    /** Собирает MP4 из уже закодированного AAC и готового визуального сегмента. */
    private fun buildWithEncodedAudio(
        encodedAudioFile: File,
        visualSegment: NormalizedVisualSegment,
        audioDurationMs: Long,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        runCatching { target.delete() }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val audioTrack = audioTrackOf(encodedAudioFile, muxer)
            val videoTrack = addVideoTrack(visualSegment.file, muxer)
            muxer.start()
            started = true
            writeAudioSamples(muxer, audioTrack, encodedAudioFile)
            writeLoopedVideo(visualSegment, muxer, videoTrack, audioDurationMs, onProgress)
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
     * Сэмплы читаются потоково (один переиспользуемый буфер), а их
     * относительные метки времени сохраняются как в исходнике: качество,
     * FPS, скорость и порядок кадров не меняются. Стоимость пропорциональна
     * длине сегмента, а не длительности книги, но и не держит весь сегмент
     * в куче.
     */
    private fun writeLoopedVideo(
        visualSegment: NormalizedVisualSegment,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) {
        val segmentFile = visualSegment.file
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segmentFile.absolutePath)
            val videoTrackIndex = videoTrackIndexOf(extractor, segmentFile)
            extractor.selectTrack(videoTrackIndex)
            val trackFormat = extractor.getTrackFormat(videoTrackIndex)

            val audioUs = audioDurationMs * 1000L
            val nominalUs = visualSegment.expectedDurationMs * 1000L
            val formatUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val segmentDurationUs = maxOf(nominalUs, formatUs)
            if (segmentDurationUs < MIN_SEGMENT_US) {
                throw IOException("visual segment too short: ${segmentDurationUs}us")
            }
            // Шаг зацикливания. Для статичной картинки он заметно длиннее самого
            // сегмента: одни и те же дешёвые сэмплы вставляются реже, и размер
            // файла растёт по числу повторов, а не по секундам битрейта.
            val loopPeriodUs = maxOf(
                if (visualSegment.loopPeriodMs > 0L) visualSegment.loopPeriodMs * 1000L else 0L,
                segmentDurationUs,
            )
            val hasGaps = loopPeriodUs > segmentDurationUs
            val bufferSize = maxOf(
                EncodedVideoValidator.sampleBufferSize(trackFormat),
                visualSegment.maxSampleBytes,
            )
            AudiobookExportDebug.log(
                "video loop: segment=${segmentDurationUs}us loop=${loopPeriodUs}us " +
                    "audio=${audioUs}us gaps=$hasGaps buffer=${bufferSize}B " +
                    "frameRate=${visualSegment.frameRate}",
            )

            val buffer = java.nio.ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var written = 0L
            var maxWrittenUs = -1L

            fun writeSample(size: Int, targetUs: Long) {
                buffer.position(0)
                buffer.limit(size)
                bufferInfo.set(
                    0,
                    size,
                    targetUs,
                    EncodedVideoValidator.muxerFlags(extractor.sampleFlags),
                )
                muxer.writeSampleData(videoTrack, buffer, bufferInfo)
                written++
                if (targetUs > maxWrittenUs) maxWrittenUs = targetUs
                if (written % PROGRESS_SAMPLE_INTERVAL == 0L) {
                    onProgress((targetUs.toFloat() / audioUs).coerceIn(0f, 1f))
                }
            }

            // Проигрывает сегмент от seekTo(0). originUs — PTS первого сэмпла
            // после сик-точки: он вычитается, чтобы каждый повтор начинался с
            // нуля, а внутренние интервалы сохранялись один в один.
            //
            // ВАЖНО: пишутся ВСЕ сэмплы в порядке декодирования. Раньше сэмпл
            // пропускался, если его PTS не больше предыдущего, но с B-кадрами
            // PTS в порядке декодирования немонотонны — B-кадры терялись, а
            // ссылающиеся на них кадры рассыпались в «мозаику». Муксер сам
            // считает DTS и строит ctts по PTS.
            //
            // Возвращает число записанных сэмплов (0 — сегмент не влез).
            fun writeCycle(offsetUs: Long, stopAtUs: Long): Int {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var originUs = -1L
                var produced = 0
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTimeUs = extractor.sampleTime
                    if (originUs < 0L) originUs = sampleTimeUs
                    val targetUs = offsetUs + (sampleTimeUs - originUs).coerceAtLeast(0L)
                    if (targetUs >= stopAtUs) break
                    if (size > 0) {
                        writeSample(size, targetUs)
                        produced++
                    }
                    extractor.advance()
                }
                return produced
            }

            var loopStartUs = 0L
            var loops = 0L
            while (loopStartUs < audioUs) {
                val produced = writeCycle(loopStartUs, audioUs)
                if (produced == 0) break
                loops++
                if (loops == 1L) {
                    AudiobookExportDebug.log(
                        "video loop: first cycle samples=$produced last=${maxWrittenUs}us",
                    )
                }
                loopStartUs += loopPeriodUs
            }

            // Хвост нужен только при разрывах между повторами (статичная
            // картинка): без него видеодорожка оборвалась бы посреди книги.
            // Для непрерывного видео основной цикл уже дошёл до конца аудио.
            if (hasGaps && maxWrittenUs < audioUs - 1L) {
                val tailStartUs = (audioUs - segmentDurationUs).coerceAtLeast(0L)
                writeCycle(tailStartUs, audioUs)
            }

            // Страховка для очень короткого аудио: хотя бы один кадр.
            if (written == 0L) {
                writeCycle(0L, audioUs)
            }
            if (written == 0L) throw IOException("visual segment produced no samples")
            AudiobookExportDebug.log(
                "video loop done: samples=$written loops=$loops last=${maxWrittenUs}us " +
                    "audio=${audioUs}us duration=${maxWrittenUs.coerceAtLeast(0L) / 1000L}ms",
            )
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

            val buffer = java.nio.ByteBuffer.allocate(AUDIO_SAMPLE_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0)
                buffer.limit(size)
                // Флаги не переносятся: у аудио нет ключевых кадров, а
                // SAMPLE_FLAG_PARTIAL_FRAME совпал бы с END_OF_STREAM.
                bufferInfo.set(0, size, extractor.sampleTime, 0)
                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Фактические длительности дорожек и контейнера собранного MP4. */
    private class TrackDurations(val audioMs: Long, val videoMs: Long) {
        val containerMs: Long get() = maxOf(audioMs, videoMs)
    }

    /**
     * Читает из готового MP4 фактические длительности аудио- и видеодорожки.
     *
     * Это единственный способ узнать, что реально оказалось в контейнере:
     * AAC добавляет задержку кодера, а длительность видеодорожки выводится
     * из меток кадров. Именно эти числа, а не расчётные, должны попадать
     * в JSON и таймлайн.
     */
    private fun probeTrackDurations(file: File): TrackDurations {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audio = 0L
            var video = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!format.containsKey(MediaFormat.KEY_DURATION)) continue
                val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                when {
                    mime.startsWith("audio/") -> audio = maxOf(audio, durationMs)
                    mime.startsWith("video/") -> video = maxOf(video, durationMs)
                }
            }
            TrackDurations(audio, video)
        } catch (e: Exception) {
            Timber.w(e, "VideoLoopBuilder: duration probe failed")
            TrackDurations(0L, 0L)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        // Размер буфера видеосэмпла считается по формату и замеру максимума
        // (см. EncodedVideoValidator): ключевой кадр у 4K-источника не влез
        // бы в фиксированные 8 МБ и поток бы развалился.
        const val AUDIO_SAMPLE_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_SAMPLE_INTERVAL = 2_000L
        // Сегмент короче 100 мс означает битые метки времени: лучше упасть
        // с внятной ошибкой, чем зацикливаться миллионы раз.
        const val MIN_SEGMENT_US = 100_000L
    }
}

/** Результат сборки MP4 с фактическими длительностями дорожек. */
data class Mp4Result(
    val file: File,
    val audioDurationMs: Long,
    val videoDurationMs: Long,
    val containerDurationMs: Long = maxOf(audioDurationMs, videoDurationMs),
) {
    /** Допустимое расхождение видео и аудио — один кадр при 2 FPS. */
    fun durationsMatch(toleranceMs: Long = 500L): Boolean =
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
            AudiobookExportDebug.log(
                "AAC encode start: ${segment.sampleRateHz}Hz/${segment.channels}ch " +
                    "frames=${segment.frameCount} bytes=${segment.dataLength}",
            )
            val startedAtMs = System.currentTimeMillis()
            RandomAccessFile(wavFile, "r").use { raf ->
                raf.seek(segment.dataOffset)
                val bytesPerFrame = segment.bytesPerFrame.coerceAtLeast(1)
                var bytesSubmitted = 0L
                var framesSubmitted = 0L
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val rawRead = minOf(
                                    segment.dataLength - bytesSubmitted,
                                    inputBuffer.remaining().toLong(),
                                    (PCM_CHUNK_FRAMES * bytesPerFrame).toLong(),
                                )
                                // Подаём целое число кадров: иначе PTS считался
                                // бы от неполного кадра и звук уезжал бы.
                                val toRead = (rawRead / bytesPerFrame * bytesPerFrame).toInt()
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
                                    // PTS считается по НОМЕРУ КАДРА, а не по числу
                                    // байт: для моно 16 бит байтов вдвое больше
                                    // кадров, и старая формула давала PTS вдвое
                                    // быстрее реального — AAC-дорожка «съезжала».
                                    val presentationTimeUs =
                                        framesSubmitted * 1_000_000L / segment.sampleRateHz
                                    codec.queueInputBuffer(inputIndex, 0, toRead, presentationTimeUs, 0)
                                    bytesSubmitted += toRead
                                    framesSubmitted += toRead / bytesPerFrame
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
                AudiobookExportDebug.log(
                    "AAC encode done: frames=$framesSubmitted " +
                        "in ${System.currentTimeMillis() - startedAtMs}ms",
                )
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

/**
 * Потоковый кодировщик PCM → AAC для MP4.
 *
 * В отличие от [AacAudioEncoder], который читает готовый WAV целиком,
 * этот класс получает сегменты по мере синтеза. Состояние кодека и счётчик
 * кадров живут между [append], поэтому PTS идут непрерывно, а промежуточный
 * WAV на диске не создаётся. Стоимость и место пропорциональны аудио, а не
 * удваиваются на лишний проход.
 */
internal class StreamingAacEncoder(private val outputFile: File) {

    private val codec = MediaCodec.createEncoderByType(MIME_AUDIO_AAC)
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var started = false
    private var configured = false
    private var released = false
    private var sampleRate = 0
    private var channels = 1
    private var bitsPerSample = 16
    private var bytesPerFrame = 1

    /** Суммарно поданные кадры — источник длительности аудио. */
    var totalFrames: Long = 0L
        private set

    /**
     * Фактическая длительность записанной AAC-дорожки в мс, прочитанная из
     * контейнера после [finish]. Может отличаться от `totalFrames / sampleRate`
     * на задержку кодера — именно её обязан видеть таймлайн и JSON.
     */
    var durationMs: Long = 0L
        private set

    /** Инициализирован ли кодек (был хотя бы один непустой сегмент). */
    fun isConfigured(): Boolean = configured

    /** Дошло ли дело до реального муксирования дорожки. */
    fun isUsable(): Boolean = started

    /** Добавляет PCM-данные одного wav-сегмента TTS в общий AAC-поток. */
    fun append(segmentFile: File) {
        append(segmentFile, WavAudio.readSegment(segmentFile))
    }

    /**
     * Вариант для вызывающего, у которого уже есть разобранный заголовок, —
     * экономит повторный разбор сегмента на каждом куске.
     */
    fun append(segmentFile: File, segment: PcmSegment) {
        check(!released) { "encoder is already released" }
        if (!configured) configure(segment)
        requireCompatible(segment)

        RandomAccessFile(segmentFile, "r").use { raf ->
            raf.seek(segment.dataOffset)
            var remaining = segment.dataLength
            while (remaining > 0) {
                drain()
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex < 0) continue
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                val rawRead = minOf(
                    remaining,
                    inputBuffer.remaining().toLong(),
                    (PCM_CHUNK_FRAMES * bytesPerFrame).toLong(),
                )
                val toRead = (rawRead / bytesPerFrame * bytesPerFrame).toInt()
                if (toRead <= 0) break
                val chunk = ByteArray(toRead)
                raf.readFully(chunk)
                inputBuffer.put(chunk)
                val presentationTimeUs = totalFrames * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, toRead, presentationTimeUs, 0)
                totalFrames += toRead / bytesPerFrame
                remaining -= toRead
            }
            drain()
        }
    }

    /** Завершает поток (EOS), дописывает хвост и закрывает кодек/муксер. */
    fun finish() {
        if (released) return
        if (configured) {
            var inputDone = false
            while (!inputDone) {
                drain()
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val eosPts = totalFrames * 1_000_000L / sampleRate.coerceAtLeast(1)
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputDone = true
                }
            }
            var outputDone = false
            while (!outputDone) {
                outputDone = drain() == DrainResult.END_OF_STREAM
            }
        }
        closeQuietly()
        durationMs = probeDurationMs(outputFile)
        AudiobookExportDebug.log(
            "AAC stream done: frames=$totalFrames started=$started " +
                "duration=${durationMs}ms " +
                "size=${runCatching { outputFile.length() }.getOrDefault(0L)}",
        )
    }

    /** Читает длительность аудиодорожки готового файла. */
    private fun probeDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.w(e, "StreamingAacEncoder: duration probe failed")
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Аварийно освобождает ресурсы без финализации контейнера. */
    fun abort() {
        closeQuietly()
    }

    private enum class DrainResult { NONE, PROGRESS, END_OF_STREAM }

    /**
     * Забирает все доступные выходные буферы. Возвращает [DrainResult],
     * чтобы [finish] понимал, когда пришёл EOS.
     */
    private fun drain(): DrainResult {
        var result = DrainResult.NONE
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return result
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                    result = DrainResult.PROGRESS
                }
                outputIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outputIndex)
                    val isConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && bufferInfo.size > 0 && !isConfig && started) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return DrainResult.END_OF_STREAM
                    }
                    result = DrainResult.PROGRESS
                }
            }
        }
    }

    private fun configure(segment: PcmSegment) {
        sampleRate = segment.sampleRateHz
        channels = segment.channels
        bitsPerSample = segment.bitsPerSample
        bytesPerFrame = segment.bytesPerFrame.coerceAtLeast(1)
        val format = MediaFormat.createAudioFormat(MIME_AUDIO_AAC, sampleRate, segment.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        configured = true
        AudiobookExportDebug.log(
            "AAC stream start: ${sampleRate}Hz/${channels}ch bits=$bitsPerSample",
        )
    }

    private fun requireCompatible(segment: PcmSegment) {
        if (segment.sampleRateHz != sampleRate ||
            segment.bitsPerSample != bitsPerSample ||
            segment.channels != channels
        ) {
            throw IncompatibleAudioFormatException(
                "segment ${segment.sampleRateHz}Hz/${segment.channels}ch/${segment.bitsPerSample}bit " +
                    "cannot be appended to ${sampleRate}Hz/${channels}ch/${bitsPerSample}bit AAC stream",
            )
        }
    }

    private fun closeQuietly() {
        if (released) return
        released = true
        if (started) runCatching { muxer.stop() }
        if (configured) runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val MIME_AUDIO_AAC = "audio/mp4a-latm"
        const val AAC_BITRATE = 96_000
        const val MAX_INPUT_SIZE = 16 * 1024
        const val TIMEOUT_US = 10_000L
        const val PCM_CHUNK_FRAMES = 8192
    }
}
