package my.noveldokusha.tooling.audiobook

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Проверка подготовленной видеодорожки **до** зацикливания.
 *
 * Ошибку чтения сэмплов, отсутствие ключевых кадров или codec-specific data
 * нужно поймать на коротком сегменте: иначе зацикливание размножит уже
 * битый поток на всю книгу — именно так появлялись «мозаика» и рассыпавшиеся
 * кадры.
 */
internal object EncodedVideoValidator {

    /** Что удалось узнать о дорожке за один потоковый проход. */
    data class Summary(
        val sampleCount: Int,
        val keyframeCount: Int,
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val minSampleBytes: Int,
        val maxSampleBytes: Int,
        /** Сколько раз PTS в порядке декодирования меньше предыдущего (B-кадры). */
        val decodeOrderInversions: Int,
        val hasCsd: Boolean,
    ) {
        val isValid: Boolean
            get() = sampleCount > 0 && keyframeCount > 0 && hasCsd
    }

    /**
     * Размер буфера под один сжатый видеосэмпл.
     *
     * `KEY_MAX_INPUT_SIZE` у извлечённых дорожек часто отсутствует, поэтому
     * берём оценку сверху по полному кадру (`w*h*3` — с запасом на 4:4:4 и
     * 10 бит). Ограничение сверху не даёт 8K-исходнику зарезервировать
     * гигабайты кучи.
     */
    fun sampleBufferSize(format: MediaFormat): Int {
        val declared = optInt(format, MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(0)
        val width = optInt(format, MediaFormat.KEY_WIDTH).coerceAtLeast(0)
        val height = optInt(format, MediaFormat.KEY_HEIGHT).coerceAtLeast(0)
        val frameEstimate = width.toLong() * height.toLong() * 3L
        return maxOf(declared.toLong(), frameEstimate, MIN_SAMPLE_BUFFER_BYTES)
            .coerceAtMost(MAX_SAMPLE_BUFFER_BYTES)
            .toInt()
    }

    /**
     * Флаги сэмпла для [android.media.MediaMuxer].
     *
     * `MediaExtractor` и `MediaCodec` используют разные битовые поля.
     * `SAMPLE_FLAG_PARTIAL_FRAME` (4) совпадает с `BUFFER_FLAG_END_OF_STREAM`
     * (4): если передать флаги как есть, муксер решит, что это конец потока,
     * и оборвёт дорожку посреди книги. Поэтому оставляем только признак
     * ключевого кадра.
     */
    fun muxerFlags(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }

    /**
     * Читает дорожку целиком, считая сэмплы/ключевые кадры и собирая
     * диагностику меток времени. Бросает [IOException], если поток заведомо
     * недекодируем (нет сэмплов, ключевых кадров или CSD).
     */
    fun analyze(file: File, label: String): Summary {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IOException("[$label] no video track in ${file.name}")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)

            val bufferSize = sampleBufferSize(format)
            val buffer = ByteBuffer.allocate(bufferSize)
            var samples = 0
            var keyframes = 0
            var firstPts = Long.MIN_VALUE
            var lastPts = 0L
            var inversions = 0
            var minBytes = Int.MAX_VALUE
            var maxBytes = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (firstPts == Long.MIN_VALUE) firstPts = pts
                if (samples > 0 && pts < lastPts) inversions++
                lastPts = pts
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keyframes++
                if (size > 0) {
                    if (size < minBytes) minBytes = size
                    if (size > maxBytes) maxBytes = size
                }
                samples++
                extractor.advance()
            }

            val summary = Summary(
                sampleCount = samples,
                keyframeCount = keyframes,
                firstPtsUs = if (firstPts == Long.MIN_VALUE) 0L else firstPts,
                lastPtsUs = lastPts,
                minSampleBytes = if (minBytes == Int.MAX_VALUE) 0 else minBytes,
                maxSampleBytes = maxBytes,
                decodeOrderInversions = inversions,
                hasCsd = format.containsKey(CSD_0),
            )
            AudiobookExportDebug.log(
                "[$label] video: mime=${format.getString(MediaFormat.KEY_MIME)} " +
                    "profile=${optInt(format, MediaFormat.KEY_PROFILE)} " +
                    "level=${optInt(format, MediaFormat.KEY_LEVEL)} " +
                    "${optInt(format, MediaFormat.KEY_WIDTH)}x${optInt(format, MediaFormat.KEY_HEIGHT)} " +
                    "fps=${optInt(format, MediaFormat.KEY_FRAME_RATE)} " +
                    "duration=${optLong(format, MediaFormat.KEY_DURATION) / 1000L}ms " +
                    "rotation=${optInt(format, MediaFormat.KEY_ROTATION)} " +
                    "samples=${summary.sampleCount} keyframes=${summary.keyframeCount} " +
                    "firstPts=${summary.firstPtsUs} lastPts=${summary.lastPtsUs} " +
                    "ptsInversions=${summary.decodeOrderInversions} " +
                    "sampleBytes=[${summary.minSampleBytes}..${summary.maxSampleBytes}] " +
                    "buffer=$bufferSize csd=${summary.hasCsd} bytes=${file.length()}",
            )
            if (!summary.isValid) {
                throw IOException(
                    "[$label] undecodable video: samples=$samples " +
                        "keyframes=$keyframes csd=${summary.hasCsd}",
                )
            }
            return summary
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun optInt(format: MediaFormat, key: String): Int =
        runCatching { if (format.containsKey(key)) format.getInteger(key) else FALLBACK_INT }
            .getOrDefault(FALLBACK_INT)

    private fun optLong(format: MediaFormat, key: String): Long =
        runCatching { if (format.containsKey(key)) format.getLong(key) else 0L }.getOrDefault(0L)

    private const val CSD_0 = "csd-0"
    private const val FALLBACK_INT = -1
    private const val MIN_SAMPLE_BUFFER_BYTES = 1L shl 20
    private const val MAX_SAMPLE_BUFFER_BYTES = 64L shl 20
}
