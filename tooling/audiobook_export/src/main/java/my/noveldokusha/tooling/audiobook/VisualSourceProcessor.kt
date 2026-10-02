package my.noveldokusha.tooling.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * Нормализованный визуальный сегмент — «сырьё» для последующего зацикливания.
 *
 * Ключевая идея фичи: визуал готовится **один раз**, а на всю длительность
 * аудиокниги переиспользуются уже закодированные сэмплы. Для видео это весь
 * исходник (после побайтового переноса), для картинки/GIF — короткий ролик,
 * повторяемый с большим шагом.
 */
data class NormalizedVisualSegment(
    val file: File,
    val info: VisualSegmentInfo,
    /** Расчётная длительность сегмента (кадры / FPS) — надёжнее, чем PTS контейнера. */
    val expectedDurationMs: Long = 0L,
    val frameRate: Int = 0,
    /**
     * Шаг, через который сегмент повторяется при зацикливании. `0` — сегмент
     * повторяется встык (как видео/GIF). Для статичной картинки шаг длиннее
     * самого сегмента: одни и те же сэмплы вставляются реже, и размер файла
     * падает пропорционально длительности книги, а не её полному битрейту.
     */
    val loopPeriodMs: Long = 0L,
    /**
     * Размер самого крупного сжатого сэмпла сегмента (в байтах), замеренный
     * при подготовке. По нему выделяется буфер зацикливания: если он меньше
     * ключевого кадра, [android.media.MediaExtractor.readSampleData] бросает
     * исключение или читает битый сэмпл — источник рассыпавшихся кадров.
     * `0` — размер неизвестен, берётся оценка по формату.
     */
    val maxSampleBytes: Int = 0,
) : AutoCloseable {
    override fun close() {
        runCatching { file.delete() }
    }
}

/**
 * Готовит визуальный источник к зацикливанию.
 *
 * Видео переносится в MP4 побайтово, целиком и в оригинальном качестве;
 * картинка/GIF один раз декодируются и кодируются в короткий дешёвый
 * H.264-ролик. Дальше сегмент только зацикливается.
 */
class VisualSourceProcessor(private val context: Context) {

    /**
     * Готовит нормализованный сегмент из выбранного пользователем источника.
     *
     * Для видео сегментом становится **весь исходник** (без обрезки), для
     * картинки/GIF — короткий дешёвый ролик, повторяемый с большим шагом.
     */
    fun prepare(
        source: VisualSource,
        uri: Uri,
        sourceName: String,
    ): NormalizedVisualSegment {
        val segmentFile = File.createTempFile("novela_visual_", ".mp4", context.cacheDir)
        return when (source) {
            VisualSource.IMAGE -> prepareImage(uri, sourceName, segmentFile)
            VisualSource.VIDEO -> prepareVideo(uri, sourceName, segmentFile)
            VisualSource.GIF -> prepareGif(uri, sourceName, segmentFile)
        }.also { Timber.d("VisualSourceProcessor: prepared %s from %s", source, sourceName) }
    }

    /**
     * Картинка: короткий сегмент из пары кадров.
     *
     * В отличие от видео/GIF, картинка не повторяется встык: сегмент один раз
     * кодируется дешёвым битрейтом и вставляется с большим шагом
     * ([IMAGE_LOOP_PERIOD_MS]). Именно поэтому двадцатиминутный MP4 со
     * статичной обложкой занимает единицы мегабайт, а не сотни.
     */
    private fun prepareImage(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val bitmap = decodeScaledBitmap(uri, TARGET_WIDTH, TARGET_HEIGHT)
            ?: throw VisualProcessingException("Unable to decode image: $sourceName")
        val targetFrameCount = frameCountFor(IMAGE_SEGMENT_MS, IMAGE_FPS)
        return try {
            encodeBitmapSequence(
                bitmaps = List(targetFrameCount) { bitmap },
                source = VisualSource.IMAGE,
                sourceName = sourceName,
                target = target,
                frameRate = IMAGE_FPS,
                bitRate = IMAGE_BITRATE,
                loopPeriodMs = IMAGE_LOOP_PERIOD_MS,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Полное видео: сохраняем **весь исходник** без обрезки и перекодирования.
     *
     * Раньше здесь декодировались первые 5 секунд и заново кодировались в
     * 1280x720@4fps — терялись длительность, скорость, FPS, разрешение и
     * качество. Теперь видеодорожка переносится побайтово (remux) вместе с
     * оригинальными PTS, а зацикливание до конца аудио со сдвигом меток
     * делает [VideoLoopBuilder]. Перекодирование — только аварийный путь для
     * кодеков, которые муксер MP4 не принимает.
     */
    private fun prepareVideo(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val meta = readVideoMeta(uri)
            ?: throw VisualProcessingException("Unable to read video track: $sourceName")
        // Проверяем подготовленный короткий сегмент, пока он ещё мал: битый
        // источник нужно поймать здесь, а не после зацикливания на всю книгу.
        // Если remux формально прошёл, но дал недекодируемый поток, он тоже
        // считается провалом и уходит в аварийный транскод.
        val summary = try {
            remuxFullVideo(uri, target, meta)
            EncodedVideoValidator.analyze(target, "prepared-remux")
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: remux failed, transcoding full video")
            runCatching { target.delete() }
            transcodeFullVideo(uri, target, meta)
            EncodedVideoValidator.analyze(target, "prepared-transcode")
        }
        val durationMs = probeDurationMs(target).takeIf { it > 0L } ?: meta.durationMs
        if (durationMs <= 0L) {
            throw VisualProcessingException("Video has no measurable duration: $sourceName")
        }
        AudiobookExportDebug.log(
            "VisualSourceProcessor: video ready ${meta.width}x${meta.height} " +
                "fps=${meta.frameRate} duration=${durationMs}ms codec=${meta.mime} " +
                "samples=${summary.sampleCount} keyframes=${summary.keyframeCount} " +
                "maxSample=${summary.maxSampleBytes}B",
        )
        return NormalizedVisualSegment(
            file = target,
            info = VisualSegmentInfo(
                type = VisualSource.VIDEO,
                sourceName = sourceName,
                loop = true,
                durationMs = durationMs,
                width = meta.width,
                height = meta.height,
            ),
            expectedDurationMs = durationMs,
            frameRate = meta.frameRate,
            loopPeriodMs = 0L,
            maxSampleBytes = summary.maxSampleBytes,
        )
    }

    /** Метаданные видеодорожки исходника. */
    private class VideoMeta(
        val trackIndex: Int,
        val mime: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val durationMs: Long,
        val rotation: Int,
    )

    /** Читает метаданные первой видеодорожки. */
    private fun readVideoMeta(uri: Uri): VideoMeta? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val index = videoTrackIndexOf(extractor)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            VideoMeta(
                trackIndex = index,
                mime = mime,
                width = format.optInt(MediaFormat.KEY_WIDTH, 0),
                height = format.optInt(MediaFormat.KEY_HEIGHT, 0),
                frameRate = format.optInt(MediaFormat.KEY_FRAME_RATE, 0),
                durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else {
                    0L
                },
                rotation = format.optInt(MediaFormat.KEY_ROTATION, 0),
            )
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: video meta read failed")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Побайтово переносит видеодорожку исходника в MP4 без перекодирования.
     *
     * [MediaMuxer.addTrack] служит пробой совместимости: если контейнер не
     * умеет хранить этот кодек/CSD, он бросит исключение и сработает
     * аварийное перекодирование.
     */
    private fun remuxFullVideo(uri: Uri, target: File, meta: VideoMeta) {
        val extractor = android.media.MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(meta.trackIndex)
            val format = extractor.getTrackFormat(meta.trackIndex)
            if (meta.rotation != 0) runCatching { muxer.setOrientationHint(meta.rotation) }
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(EncodedVideoValidator.sampleBufferSize(format))
            val bufferInfo = MediaCodec.BufferInfo()
            var originUs = -1L
            var samples = 0
            var keyframes = 0
            var minBytes = Int.MAX_VALUE
            var maxBytes = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (originUs < 0L) originUs = sampleTimeUs
                if (size > 0) {
                    // Сэмплы пишутся в порядке декодирования с исходными
                    // относительными PTS: B-кадры не отбрасываются, иначе
                    // ссылающиеся на них кадры рассыпаются в «мозаику».
                    // Флаги маскируются: SAMPLE_FLAG_PARTIAL_FRAME совпадает
                    // с BUFFER_FLAG_END_OF_STREAM.
                    buffer.position(0)
                    buffer.limit(size)
                    bufferInfo.set(
                        0,
                        size,
                        (sampleTimeUs - originUs).coerceAtLeast(0L),
                        EncodedVideoValidator.muxerFlags(extractor.sampleFlags),
                    )
                    muxer.writeSampleData(track, buffer, bufferInfo)
                    samples++
                    if (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) keyframes++
                    if (size < minBytes) minBytes = size
                    if (size > maxBytes) maxBytes = size
                }
                extractor.advance()
            }
            AudiobookExportDebug.log(
                "VisualSourceProcessor: remux samples=$samples keyframes=$keyframes " +
                    "sampleBytes=[${if (minBytes == Int.MAX_VALUE) 0 else minBytes}..$maxBytes]",
            )
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
        if (!started) throw VisualProcessingException("Unable to remux video track")
    }

    /**
     * Аварийный полный транскод: декодер рендерит кадры прямо на входную
     * поверхность энкодера (surface-to-surface), поэтому исходные разрешение,
     * пропорции и метки времени сохраняются, а кадры не проходят через
     * Bitmap-конвейер.
     */
    private fun transcodeFullVideo(uri: Uri, target: File, meta: VideoMeta) {
        val extractor = android.media.MediaExtractor()
        val encoder = MediaCodec.createEncoderByType(MIME_VIDEO)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var decoder: MediaCodec? = null
        var surface: Surface? = null
        var encoderStarted = false
        var decoderStarted = false
        var muxerStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(meta.trackIndex)
            val inputFormat = extractor.getTrackFormat(meta.trackIndex)
            val width = meta.width.takeIf { it > 0 } ?: TARGET_WIDTH
            val height = meta.height.takeIf { it > 0 } ?: TARGET_HEIGHT
            val frameRate = meta.frameRate.takeIf { it > 0 } ?: TARGET_FPS
            val format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
            encoderStarted = true
            val decoderCodec = MediaCodec.createDecoderByType(meta.mime)
            decoder = decoderCodec
            decoderCodec.configure(inputFormat, surface, null, 0)
            decoderCodec.start()
            decoderStarted = true
            if (meta.rotation != 0) runCatching { muxer.setOrientationHint(meta.rotation) }

            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrack = -1
            var inputDone = false
            var decoderDone = false
            var encoderSignaled = false
            var encoderDone = false
            val deadline = System.currentTimeMillis() + transcodeTimeoutMs(meta.durationMs)

            while (!encoderDone) {
                if (System.currentTimeMillis() > deadline) {
                    throw VisualProcessingException("Video transcode timed out")
                }
                if (!inputDone) {
                    val inIndex = decoderCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoderCodec.getInputBuffer(inIndex)
                        if (inBuffer == null) {
                            decoderCodec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoderCodec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoderCodec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                if (!decoderDone) {
                    when (val outIndex = decoderCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            decoderCodec.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decoderDone = true
                            }
                        }
                    }
                }
                if (decoderDone && !encoderSignaled) {
                    encoder.signalEndOfInputStream()
                    encoderSignaled = true
                }
                when (val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (videoTrack < 0) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (encoded != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }
            if (!muxerStarted) throw VisualProcessingException("Transcoder produced no video samples")
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { surface?.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    /** GIF: декодируется один раз, затем его кадры зацикливаются в сегменте. */
    private fun prepareGif(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val frames = decodeGifFrames(uri)
        if (frames.isEmpty()) {
            throw VisualProcessingException("Unable to decode GIF frames: $sourceName")
        }
        return try {
            encodeBitmapSequence(
                bitmaps = frames,
                source = VisualSource.GIF,
                sourceName = sourceName,
                target = target,
                frameRate = TARGET_FPS,
                bitRate = TARGET_BITRATE,
                loopPeriodMs = 0L,
            )
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    /** Число кадров в сегменте для заданной длительности и частоты. */
    private fun frameCountFor(durationMs: Long, fps: Int): Int =
        ((durationMs * fps) / 1000L).toInt().coerceAtLeast(1)

    /**
     * Кодирует последовательность кадров в короткий MP4 через
     * MediaCodec + MediaMuxer.
     */
    private fun encodeBitmapSequence(
        bitmaps: List<Bitmap>,
        source: VisualSource,
        sourceName: String,
        target: File,
        frameRate: Int,
        bitRate: Int,
        loopPeriodMs: Long,
    ): NormalizedVisualSegment {
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, TARGET_WIDTH, TARGET_HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

        val codec = MediaCodec.createEncoderByType(MIME_VIDEO)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val outputMuxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outputMuxer

            // Кадры рисуются на surface входного энкодера: это ровно одна
            // отрисовка на кадр сегмента, а не на весь audiobook.
            //
            // Пауза между кадрами выдерживает реальный интервал: метки времени
            // surface-входа берутся из момента post, поэтому без паузы кадры
            // получили бы почти одинаковые PTS и сегмент оказался бы короче
            // задуманного — а зацикливание выродилось бы в миллионы итераций.
            //
            // Выходные буферы дренируются после каждого кадра: очередь входа
            // энкодера ограничена, и без этого unlockCanvasAndPost завис бы на
            // сегментах длиннее нескольких кадров.
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            fun drainOutput(): Boolean {
                while (true) {
                    when (val status = codec.dequeueOutputBuffer(bufferInfo, 0L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (trackIndex < 0) {
                                trackIndex = outputMuxer.addTrack(codec.outputFormat)
                                outputMuxer.start()
                                muxerStarted = true
                            }
                        }
                        else -> if (status >= 0) {
                            val encoded = codec.getOutputBuffer(status)
                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val isEnd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (encoded != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                outputMuxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(status, false)
                            if (isEnd) return true
                        }
                    }
                }
            }

            bitmaps.forEachIndexed { index, bitmap ->
                drawBitmapToSurface(bitmap, surface)
                drainOutput()
                if (index != bitmaps.lastIndex) Thread.sleep(1000L / frameRate.coerceAtLeast(1))
            }
            codec.signalEndOfInputStream()
            var reachedEos = false
            val deadline = System.currentTimeMillis() + EOS_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (drainOutput()) {
                    reachedEos = true
                    break
                }
                Thread.sleep(EOS_POLL_MS)
            }
            if (!reachedEos) {
                throw VisualProcessingException("Encoder did not finish the visual segment")
            }

            if (!muxerStarted) throw VisualProcessingException("Encoder produced no samples")
            // Без stop() не пишется moov-атом: файл останется нечитаемым.
            outputMuxer.stop()
            muxerStopped = true
        } catch (e: VisualProcessingException) {
            throw e
        } catch (e: Exception) {
            throw VisualProcessingException("Failed to encode visual segment: ${e.message}", e)
        } finally {
            if (muxerStarted && !muxerStopped) runCatching { muxer?.stop() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.release() }
            if (!muxerStopped) runCatching { target.delete() }
        }

        val durationMs = probeDurationMs(target)
        // Длительность известна точно из числа кадров: PTS контейнера
        // зависит от планировщика surface и иногда врёт (старый баг
        // зацикливания на 88%).
        val expectedDurationMs = bitmaps.size * 1000L / frameRate
        return NormalizedVisualSegment(
            file = target,
            info = VisualSegmentInfo(
                type = source,
                sourceName = sourceName,
                loop = true,
                durationMs = expectedDurationMs.takeIf { it > 0L } ?: durationMs,
                width = TARGET_WIDTH,
                height = TARGET_HEIGHT,
            ),
            expectedDurationMs = expectedDurationMs,
            frameRate = frameRate,
            loopPeriodMs = loopPeriodMs,
        )
    }

    private fun drawBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            drawBitmapCenterFit(canvas, bitmap)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Вписывает кадр целиком с сохранением пропорций (contain-fit):
     * обложка не обрезается, свободное место залито чёрным.
     */
    private fun drawBitmapCenterFit(canvas: Canvas, bitmap: Bitmap) {
        val scale = minOf(
            canvas.width.toFloat() / bitmap.width.toFloat(),
            canvas.height.toFloat() / bitmap.height.toFloat(),
        )
        val destWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val destHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val left = (canvas.width - destWidth) / 2
        val top = (canvas.height - destHeight) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + destWidth, top + destHeight), null)
    }

    /** Декодирует картинку сразу в целевом разрешении — полный размер не нужен. */
    private fun decodeScaledBitmap(uri: Uri, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, width, height)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return scaleCenterFit(decoded, width, height)
    }

    /** Индекс первой видеодорожки контейнера. */
    private fun videoTrackIndexOf(extractor: android.media.MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: throw IOException("no video track")

    /** Декодирует GIF через [android.graphics.Movie] — один проход. */
    private fun decodeGifFrames(uri: Uri): List<Bitmap> {
        val movie = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.Movie.decodeStream(stream)
        } ?: return emptyList()

        val durationMs = movie.duration().coerceAtLeast(1)
        val frames = mutableListOf<Bitmap>()
        val frameCount = frameCountFor(
            durationMs.toLong().coerceAtMost(MAX_SEGMENT_MS),
            TARGET_FPS,
        ).coerceAtMost(MAX_GIF_FRAMES)
        for (index in 0 until frameCount) {
            val timeMs = (durationMs.toLong() * index / frameCount).toInt()
            movie.setTime(timeMs)
            // Кадр рисуется в натуральном размере Movie: перегрузка
            // drawBitmap(Movie, Matrix, Paint) недоступна в android.jar,
            // а масштабирование выполняется один раз при нормализации.
            val sourceWidth = movie.width().coerceAtLeast(1)
            val sourceHeight = movie.height().coerceAtLeast(1)
            val frame = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
            @Suppress("DEPRECATION")
            movie.draw(Canvas(frame), 0f, 0f)
            frames += scaleCenterFit(frame, TARGET_WIDTH, TARGET_HEIGHT)
        }
        return frames
    }

    /**
     * Вписывает изображение целиком (contain-fit): ничего не обрезается,
     * оставшееся место залито чёрным. Именно так обложка сохраняется
     * полностью на 16:9-канве.
     */
    private fun scaleCenterFit(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        val scale = minOf(
            width.toFloat() / source.width.toFloat(),
            height.toFloat() / source.height.toFloat(),
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(source, matrix, null)
        if (source != output) source.recycle()
        return output
    }

    private fun sampleSizeFor(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    private fun probeDurationMs(file: File): Long {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0L
            extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION) / 1000L
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: duration probe failed")
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    /** Битрейт аварийного транскода: ~ w*h*fps/8, в разумных пределах. */
    private fun bitrateFor(width: Int, height: Int, frameRate: Int): Int {
        val bits = width.toLong() * height.toLong() * frameRate.coerceAtLeast(1) / 8L
        return bits.coerceIn(MIN_TRANSCODE_BITRATE, MAX_TRANSCODE_BITRATE).toInt()
    }

    /** Таймаут транскода: с запасом на медленный декодер, но не бесконечный. */
    private fun transcodeTimeoutMs(durationMs: Long): Long =
        TRANSCODE_TIMEOUT_BASE_MS + durationMs.coerceAtLeast(0L) * 10L

    private companion object {
        const val MIME_VIDEO = "video/avc"
        // Выход картинки/GIF — YouTube-формат 16:9 (1280x720 = 720p HD).
        // Низкий FPS и умеренный битрейт: зацикленный визуал audiobook'а не
        // требует много движения, а кадры переиспользуются как готовые сэмплы.
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        const val TARGET_FPS = 4
        const val TARGET_BITRATE = 1_200_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val TIMEOUT_US = 10_000L
        const val EOS_TIMEOUT_MS = 30_000L
        const val EOS_POLL_MS = 5L
        const val MIN_SEGMENT_MS = 1_000L
        const val MAX_SEGMENT_MS = 5_000L
        const val MAX_GIF_FRAMES = 20

        // Границы битрейта аварийного транскода.
        const val MIN_TRANSCODE_BITRATE = 2_000_000L
        const val MAX_TRANSCODE_BITRATE = 40_000_000L
        const val TRANSCODE_TIMEOUT_BASE_MS = 60_000L

        // Статичная обложка: дешёвый сегмент (низкий FPS и битрейт) плюс
        // большой шаг зацикливания. Стоимость визуальной части падает
        // пропорционально длительности книги, а не её полному хронометражу.
        const val IMAGE_FPS = 2
        const val IMAGE_BITRATE = 250_000
        const val IMAGE_SEGMENT_MS = MIN_SEGMENT_MS
        const val IMAGE_LOOP_PERIOD_MS = 10_000L
    }
}

/** Не удалось подготовить визуальный источник. */
class VisualProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)
