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
 * Нормализованный короткий видеосегмент — «сырьё» для последующего зацикливания.
 *
 * Ключевая идея фичи: визуал готовится **один раз**, а на всю длительность
 * аудиокниги переиспользуются уже закодированные сэмплы. Стоимость
 * визуального пайплайна зависит от размера исходника, а не от длины книги.
 */
data class NormalizedVisualSegment(
    val file: File,
    val info: VisualSegmentInfo,
) : AutoCloseable {
    override fun close() {
        runCatching { file.delete() }
    }
}

/**
 * Приводит изображение / короткое видео / GIF к одному короткому
 * H.264-сегменту фиксированного размера.
 *
 * Строгая дисциплина: декодирование, масштабирование и кодирование
 * выполняются ровно один раз на источник. Дальше сегмент только зацикливается.
 */
class VisualSourceProcessor(private val context: Context) {

    /**
     * Готовит нормализованный сегмент из выбранного пользователем источника.
     *
     * @param targetDurationMs длина сегмента; ограничена [MAX_SEGMENT_MS],
     *   чтобы не дать источнику видео раздуть подготовительный этап.
     */
    fun prepare(
        source: VisualSource,
        uri: Uri,
        sourceName: String,
        targetDurationMs: Long = DEFAULT_SEGMENT_MS,
    ): NormalizedVisualSegment {
        val segmentFile = File.createTempFile("novela_visual_", ".mp4", context.cacheDir)
        val boundedMs = targetDurationMs.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)

        return when (source) {
            VisualSource.IMAGE -> prepareImage(uri, sourceName, segmentFile)
            VisualSource.VIDEO -> prepareVideo(uri, sourceName, segmentFile)
            VisualSource.GIF -> prepareGif(uri, sourceName, segmentFile)
        }.also { Timber.d("VisualSourceProcessor: prepared %s from %s", source, sourceName) }
    }

    /** Картинка: один кадр, закодированный в короткий сегмент. */
    private fun prepareImage(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val bitmap = decodeScaledBitmap(uri, TARGET_WIDTH, TARGET_HEIGHT)
            ?: throw VisualProcessingException("Unable to decode image: $sourceName")
        val targetFrameCount = frameCountFor(MIN_SEGMENT_MS)
        return encodeBitmapSequence(
            bitmaps = List(targetFrameCount) { bitmap },
            source = VisualSource.IMAGE,
            sourceName = sourceName,
            target = target,
        )
    }

    /**
     * Короткое видео: берём начальный фрагмент и нормализуем один раз.
     *
     * Декодируется только начало исходника, а не все 10 секунд, и уж тем более
     * не длительность аудиокниги.
     */
    private fun prepareVideo(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val frames = decodeVideoFrames(uri, maxFrames = frameCountFor(MAX_SEGMENT_MS))
        if (frames.isEmpty()) {
            throw VisualProcessingException("Unable to decode video frames: $sourceName")
        }
        val cycle = List(frames.size) { frames[it % frames.size] }
        return encodeBitmapSequence(cycle, VisualSource.VIDEO, sourceName, target)
    }

    /** GIF: декодируется один раз, затем его кадры зацикливаются в сегменте. */
    private fun prepareGif(uri: Uri, sourceName: String, target: File): NormalizedVisualSegment {
        val frames = decodeGifFrames(uri)
        if (frames.isEmpty()) {
            throw VisualProcessingException("Unable to decode GIF frames: $sourceName")
        }
        return encodeBitmapSequence(frames, VisualSource.GIF, sourceName, target)
    }

    /** Число кадров в сегменте для заданной длительности. */
    private fun frameCountFor(durationMs: Long): Int =
        ((durationMs * TARGET_FPS) / 1000L).toInt().coerceAtLeast(1)

    /**
     * Кодирует последовательность кадров в короткий MP4 через
     * MediaCodec + MediaMuxer.
     */
    private fun encodeBitmapSequence(
        bitmaps: List<Bitmap>,
        source: VisualSource,
        sourceName: String,
        target: File,
    ): NormalizedVisualSegment {
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, TARGET_WIDTH, TARGET_HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
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
            bitmaps.forEach { bitmap -> drawBitmapToSurface(bitmap, surface) }
            drainEncoder(codec, outputMuxer, endOfStream = true) { format ->
                val track = outputMuxer.addTrack(format)
                outputMuxer.start()
                muxerStarted = true
                track
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
        return NormalizedVisualSegment(
            file = target,
            info = VisualSegmentInfo(
                type = source,
                sourceName = sourceName,
                loop = true,
                durationMs = durationMs,
                width = TARGET_WIDTH,
                height = TARGET_HEIGHT,
            ),
        )
    }

    private fun drawBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            drawBitmapCenterCrop(canvas, bitmap)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Вписывает кадр с сохранением пропорций и обрезкой лишнего (cover-fit). */
    private fun drawBitmapCenterCrop(canvas: Canvas, bitmap: Bitmap) {
        val canvasRatio = canvas.width.toFloat() / canvas.height.toFloat()
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dest: Rect = if (bitmapRatio > canvasRatio) {
            val destWidth = (bitmap.height * canvasRatio).toInt()
            val left = (bitmap.width - destWidth) / 2
            Rect(left, 0, left + destWidth, bitmap.height)
        } else {
            val destHeight = (bitmap.width / canvasRatio).toInt()
            val top = (bitmap.height - destHeight) / 2
            Rect(0, top, bitmap.width, top + destHeight)
        }
        canvas.drawBitmap(bitmap, null, dest, null)
    }

    /**
     * Дренирует энкодер, добавляя дорожку в муксер по её выходному формату.
     *
     * [onOutputFormat] вызывается один раз, когда формат дорожки известен, и
     * должен вернуть индекс добавленной дорожки — писать сэмплы можно только
     * в неё, а не в предположительный индекс 0.
     */
    private inline fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        endOfStream: Boolean,
        onOutputFormat: (MediaFormat) -> Int,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var currentTrack = -1
        var signalled = false
        while (true) {
            if (endOfStream && !signalled) {
                codec.signalEndOfInputStream()
                signalled = true
            }
            val status = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> if (signalled && muxerStarted) return else Unit
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    currentTrack = onOutputFormat(codec.outputFormat)
                    muxerStarted = true
                }
                status >= 0 -> {
                    val encoded = codec.getOutputBuffer(status)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEndOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (encoded != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(currentTrack, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(status, false)
                    if (isEndOfStream) return
                }
            }
        }
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
        return scaleCenterCrop(decoded, width, height)
    }

    private fun decodeVideoFrames(uri: Uri, maxFrames: Int): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(trackIndex)
            frames += decodeTrackFrames(extractor, maxFrames)
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: video decode failed")
            return frames
        } finally {
            runCatching { extractor.release() }
        }
        return frames
    }

    /** Раскладывает видеодорожку на кадры, декодируя не больше [maxFrames]. */
    private fun decodeTrackFrames(extractor: android.media.MediaExtractor, maxFrames: Int): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var codec: MediaCodec? = null
        // MediaCodec.BufferInfo не несёт размеров кадра: они приходят в
        // MediaFormat, который нужно получить до конвертации пикселей.
        var frameWidth = 0
        var frameHeight = 0
        var cropLeft = 0
        var cropTop = 0
        var cropRight = 0
        var cropBottom = 0

        try {
            val trackIndex = videoTrackIndexOf(extractor)
            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("video track has no MIME type")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()
            while (!outputDone && frames.size < maxFrames) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Размеры и кроп приходят только здесь.
                        val outputFormat = codec.outputFormat
                        frameWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                        frameHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        if (outputFormat.containsKey(MediaFormat.KEY_CROP_LEFT)) {
                            cropLeft = outputFormat.getInteger(MediaFormat.KEY_CROP_LEFT)
                            cropTop = outputFormat.getInteger(MediaFormat.KEY_CROP_TOP)
                            cropRight = outputFormat.getInteger(MediaFormat.KEY_CROP_RIGHT)
                            cropBottom = outputFormat.getInteger(MediaFormat.KEY_CROP_BOTTOM)
                        } else {
                            cropRight = frameWidth
                            cropBottom = frameHeight
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0 && buffer != null) {
                            val bitmap = buffer.toBitmap(
                                frameWidth,
                                frameHeight,
                                cropLeft,
                                cropTop,
                                cropRight,
                                cropBottom,
                            )
                            if (bitmap != null) {
                                frames += scaleCenterCrop(bitmap, TARGET_WIDTH, TARGET_HEIGHT)
                                bitmap.recycle()
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: video frame decode failed")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        return frames
    }

    /** Индекс первой видеодорожки контейнера. */
    private fun videoTrackIndexOf(extractor: android.media.MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: throw IOException("no video track")

    /**
     * Конвертирует видеокадр из YUV (semi-planar) в Bitmap.
     *
     * Размеры и кроп передаются отдельно: у [MediaCodec.BufferInfo] таких
     * полей нет, они живут в [MediaFormat].
     */
    private fun ByteBuffer.toBitmap(
        frameWidth: Int,
        frameHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ): Bitmap? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val ySize = frameWidth * frameHeight
        val chromaHeight = frameHeight / 2
        val chromaWidth = frameWidth / 2
        val chromaSize = chromaWidth * chromaHeight
        if (remaining() < ySize + chromaSize * 2) return null

        // absolute get(byte[], offset) доступен только начиная с Java 13,
        // поэтому позиция двигается вручную.
        val yPlane = ByteArray(ySize)
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        get(yPlane)
        get(uPlane)
        get(vPlane)

        val visibleWidth = (cropRight - cropLeft).coerceAtLeast(1)
        val visibleHeight = (cropBottom - cropTop).coerceAtLeast(1)
        val pixels = IntArray(visibleWidth * visibleHeight)
        for (row in 0 until visibleHeight) {
            val sourceRow = (row + cropTop).coerceAtMost(frameHeight - 1)
            for (col in 0 until visibleWidth) {
                val sourceCol = (col + cropLeft).coerceAtMost(frameWidth - 1)
                val yIndex = sourceRow * frameWidth + sourceCol
                val uvIndex = (sourceRow / 2) * chromaWidth + (sourceCol / 2)
                val y = yPlane[yIndex].toInt() and 0xFF
                val u = (uPlane[uvIndex].toInt() and 0xFF) - 128
                val v = (vPlane[uvIndex].toInt() and 0xFF) - 128
                pixels[row * visibleWidth + col] = yuvToColor(y, u, v)
            }
        }
        return Bitmap.createBitmap(pixels, visibleWidth, visibleHeight, Bitmap.Config.ARGB_8888)
    }

    private fun yuvToColor(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        val r = (298 * c + 409 * e + 128) shr 8
        val g = (298 * c - 100 * d - 208 * e + 128) shr 8
        val b = (298 * c + 516 * d + 128) shr 8
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Декодирует GIF через [android.graphics.Movie] — один проход. */
    private fun decodeGifFrames(uri: Uri): List<Bitmap> {
        val movie = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.Movie.decodeStream(stream)
        } ?: return emptyList()

        val durationMs = movie.duration().coerceAtLeast(1)
        val frames = mutableListOf<Bitmap>()
        val frameCount = frameCountFor(durationMs.toLong().coerceAtMost(MAX_SEGMENT_MS)).coerceAtMost(MAX_GIF_FRAMES)
        for (index in 0 until frameCount) {
            val timeMs = (durationMs.toLong() * index / frameCount).toInt()
            val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            movie.setTime(timeMs)
            val scaled = android.graphics.Matrix()
            scaled.postScale(
                TARGET_WIDTH.toFloat() / movie.width(),
                TARGET_HEIGHT.toFloat() / movie.height(),
            )
            // minSdk проекта — 26, поэтому drawBitmap(Movie, Matrix, Paint)
            // доступна без проверки версии.
            @Suppress("DEPRECATION")
            movie.draw(canvas, scaled, null)
            frames += bitmap
        }
        return frames
    }

    /** Масштабирует по меньшей стороне с центральной обрезкой под целевой размер. */
    private fun scaleCenterCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val left = (scaledWidth - width) / 2f
        val top = (scaledHeight - height) / 2f
        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(-left, -top)
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
            extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION)
        } catch (e: Exception) {
            Timber.w(e, "VisualSourceProcessor: duration probe failed")
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val MIME_VIDEO = "video/avc"
        // Лёгкие audiobook-настройки: небольшое разрешение, скромный битрейт,
        // низкий FPS. Цель — маленький файл и минимум нагрева.
        const val TARGET_WIDTH = 640
        const val TARGET_HEIGHT = 360
        const val TARGET_FPS = 4
        const val TARGET_BITRATE = 400_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val TIMEOUT_US = 10_000L
        const val DEFAULT_SEGMENT_MS = 2_000L
        const val MIN_SEGMENT_MS = 1_000L
        const val MAX_SEGMENT_MS = 5_000L
        const val MAX_GIF_FRAMES = 20
    }
}

/** Не удалось подготовить визуальный источник. */
class VisualProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)
