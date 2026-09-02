package my.noveldokusha.video_export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import my.noveldokusha.text_to_speech.TtsExportException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Валидатор A/V синхронизации финального MP4 (коррекция #4).
 *
 * Две фазы:
 *  1. [measureAacPrimingOffsetUs] — до кодирования измеряет на реальном AAC
 *     энкодере константную задержку «примирования», чтобы скорректировать
 *     аудио-PTS в [VideoEncoder.encode].
 *  2. [validateSync] — после создания MP4 декодирует AAC обратно в PCM и
 *     кросс-корреляцией с исходным WAV ищет расхождение (лид/лаг). Допуск —
 *     один кадр (33 мс).
 *
 * Кросс-корреляция живёт в чистой функции [findLag] — JVM-тестируема.
 */
object SyncProbe {

    /** Допустимый дрейф A/V — не более одного кадра 30fps. */
    const val ACCEPTABLE_SYNC_DRIFT_US = 33_333L

    /** Результат поиска лага между двумя PCM-последовательностями. */
    data class PcmLag(
        /** Лаг в семплах: >0 — decoded отстаёт от source на столько. */
        val lagSamples: Long,
        /** Нормированная корреляция найденного сдвига (0..1). */
        val correlation: Double,
    )

    /**
     * Ищет сдвиг, при котором [decodedPcm] воспроизводит окно [sourcePcm].
     *
     * Берётся окно речи от первого не-тихого сэмпла [sourcePcm]; поиск ведётся
     * по *относительному* сдвигу σ вокруг этого окна: результат (σ) не зависит
     * от того, когда в source начинается голос — это и есть дрейф в сэмплах.
     * >0 — в decoded речь слышна позже (отстаёт); <0 — раньше (лид).
     *
     * Грубый поиск с шагом ~20 мс и ранний выход при сильной корреляции,
     * затем мелкий по сэмплу вокруг лучшего кандидата.
     *
     * @param maxLagMs максимальный искомый |лаг| в обе стороны
     * @param windowMs длина окна корреляции
     */
    fun findLag(
        sourcePcm: FloatArray,
        decodedPcm: FloatArray,
        sampleRate: Int,
        maxLagMs: Int = 200,
        windowMs: Int = 120,
    ): PcmLag {
        val maxLag = maxLagMs.toLong() * sampleRate / 1000L
        val windowLen = windowMs * sampleRate / 1000
        if (maxLag <= 0 || windowLen <= 0) return PcmLag(0, 0.0)
        if (sourcePcm.isEmpty() || decodedPcm.size < windowLen) return PcmLag(0, 0.0)

        // Окно речи в source: от первого не-тихого сэмпла.
        val onset = firstNonSilentOnset(sourcePcm)
        val windowStart = min(onset, sourcePcm.size - windowLen).coerceAtLeast(0)
        val windowLenEff = min(windowLen, sourcePcm.size - windowStart)

        // Допустимые относительные сдвиги σ: срез decoded[windowStart+σ .. +W)
        // обязан целиком лежать внутри decoded.
        val sigmaLo = -windowStart.toLong()
        val sigmaHi = (decodedPcm.size - (windowStart + windowLenEff)).toLong()

        // Поиск симметричен вокруг 0 (|лаг| ≤ maxLag); сначала явно пробуем σ=0.
        val searchLo = sigmaLo.coerceAtLeast(-maxLag)
        val searchHi = sigmaHi.coerceAtMost(maxLag)

        // Грубый проход.
        val coarseStep = max(1L, maxLag / 5L)
        var bestSigma = 0L
        var bestCorr = correlation(sourcePcm, windowStart, windowLenEff, decodedPcm, windowStart + 0L)
        var sigma = searchLo
        while (sigma <= searchHi) {
            if (sigma != 0L) {
                val c = correlation(sourcePcm, windowStart, windowLenEff, decodedPcm, windowStart + sigma)
                if (c > bestCorr) {
                    bestCorr = c
                    bestSigma = sigma
                }
                if (bestCorr > 0.5) break
            }
            sigma += coarseStep
        }

        // Мелкий проход: вокруг грубого кандидата, если тот сильный; иначе — полная
        // область с шагом 1 (грубый кандидат мог не лечь на сетку и не дать сигнала).
        val tight = bestCorr > 0.5
        val lo = if (tight) max(searchLo, bestSigma - coarseStep) else searchLo
        val hi = if (tight) min(searchHi, bestSigma + coarseStep) else searchHi
        var fineCorr = bestCorr
        var fineSigma = bestSigma
        for (sigma in lo..hi) {
            val c = correlation(sourcePcm, windowStart, windowLenEff, decodedPcm, windowStart + sigma)
            if (c > fineCorr) {
                fineCorr = c
                fineSigma = sigma
            }
        }
        return PcmLag(fineSigma, fineCorr.coerceIn(0.0, 1.0))
    }

    /** Индекс первого сэмпла со значимой энергией. */
    private fun firstNonSilentOnset(pcm: FloatArray): Int {
        var peak = 0f
        for (i in pcm.indices) {
            val a = abs(pcm[i])
            if (a > peak) peak = a
        }
        if (peak <= 0f) return 0
        val threshold = peak * 0.01f
        for (i in pcm.indices) {
            if (abs(pcm[i]) > threshold) return i
        }
        return 0
    }

    /** Нормированная (по энергии) корреляция окна sourcePcm[ws..ws+W] и decodedPcm[offset..]. */
    private fun correlation(sourcePcm: FloatArray, ws: Int, len: Int, d: FloatArray, offset: Long): Double {
        if (offset < 0 || offset + len > d.size) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        var wEn = 0.0
        var dEn = 0.0
        for (i in 0 until len) {
            val wi = sourcePcm[ws + i].toDouble()
            val di = d[(offset + i).toInt()].toDouble()
            dot += wi * di
            wEn += wi * wi
            dEn += di * di
        }
        if (wEn <= 0.0 || dEn <= 0.0) return Double.NEGATIVE_INFINITY
        return dot / (Math.sqrt(wEn) * Math.sqrt(dEn))
    }

    // ── Устройство: примирование AAC ────────────────────────────────────────

    /**
     * Измеряет константную задержку (мкс), которую AAC-энкодер добавляет к
     * PTS выходных буферов: подаётся короткий PCM, первый выходной PTS обычно
     * смещён на -priming относительно входа. Возвращаемое значение — величина,
     * которую нужно добавить к аудио-PTS, чтобы аудио шло точно по графику.
     *
     * Вызывается на реальном устройстве (не в Robolectric).
     */
    fun measureAacPrimingOffsetUs(sampleRate: Int, channels: Int): Long {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VideoEncoder.DEFAULT_AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // 300 мс: тон + тишина (детерминированный PCM).
            val total = sampleRate * 3 / 10
            val frameBytes = channels * 2
            val pcm = ByteBuffer.allocateDirect(total * frameBytes).order(ByteOrder.nativeOrder())
            for (i in 0 until total) {
                val v = if (i < sampleRate / 20) (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 8000.0).toInt() else 0
                for (c in 0 until channels) pcm.putShort(v.toShort())
            }
            pcm.rewind()

            var fed = 0
            var firstOutputPts = Long.MAX_VALUE
            val bufferInfo = MediaCodec.BufferInfo()
            var eos = false

            fun feedOne() {
                val inIdx = codec.dequeueInputBuffer(20_000)
                if (inIdx < 0) return
                val buf = codec.getInputBuffer(inIdx)!!
                val capacity = buf.remaining()
                val n = min(capacity, pcm.remaining())
                val copy = ByteArray(n)
                pcm.get(copy)
                buf.put(copy)
                val ptsUs = fed.toLong() * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIdx, 0, n, ptsUs, if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                fed += n / frameBytes
            }

            while (true) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                if (outIdx >= 0) {
                    if (bufferInfo.size > 0 && firstOutputPts == Long.MAX_VALUE) {
                        firstOutputPts = bufferInfo.presentationTimeUs
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    codec.releaseOutputBuffer(outIdx, false)
                } else if (!eos && pcm.remaining() > 0) {
                    feedOne()
                    if (pcm.remaining() == 0) {
                        feedOne() // EOS-буфер (n == 0)
                        eos = true
                    }
                } else {
                    break
                }
            }

            if (firstOutputPts == Long.MAX_VALUE) {
                throw TtsExportException("AAC priming probe: no output produced")
            }
            // firstOutputPts обычно -priming; задержка должна быть >= 0.
            return (-firstOutputPts).coerceAtLeast(0L)
        } catch (e: Exception) {
            throw if (e is TtsExportException) e else TtsExportException("AAC priming probe failed: ${e.message}", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ── Устройство: финальная валидация MP4 ─────────────────────────────────

    /**
     * Декодирует аудио-дорожку [mp4] в моно-флоаты [-1, 1]. Вызывается на
     * реальном устройстве (MediaExtractor + декодер AAC).
     */
    fun decodeToMonoPcm(mp4: File): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp4.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0) throw TtsExportException("MP4 has no audio track")
            extractor.selectTrack(trackIndex)
            val sampleRate = format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            format.setInteger(
                MediaFormat.KEY_PCM_ENCODING,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
            )
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val decoded = mutableListOf<Float>()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                fun drain() {
                    while (true) {
                        val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 0)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                        if (outIdx >= 0) {
                            val buf = decoder.getOutputBuffer(outIdx)
                            if (buf != null) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                buf.get(bytes)
                                if (bufferInfo.size % (4 * channelCount) == 0) {
                                    // PCM float
                                    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                    val frames = bufferInfo.size / (4 * channelCount)
                                    for (f in 0 until frames) {
                                        var acc = 0f
                                        for (c in 0 until channelCount) acc += fb.float
                                        decoded.add(acc / channelCount)
                                    }
                                } else {
                                    // PCM 16-bit fallback
                                    val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                    val frames = bufferInfo.size / (2 * channelCount)
                                    for (f in 0 until frames) {
                                        var acc = 0
                                        for (c in 0 until channelCount) acc += sb.short.toInt()
                                        decoded.add(acc.toFloat() / (channelCount * 32768f))
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }

                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            val n = extractor.readSampleData(inBuf, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIdx, 0, n, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    } else {
                        drain()
                        break
                    }
                    drain()
                }
                return decoded.toFloatArray() to sampleRate
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Финальная проверка синхронизации: декодирует [mp4], кросс-коррелирует с
     * [sourceWav] и возвращает дрейф в мкс (>0 — аудио отстаёт). Бросает
     * [TtsExportException], если расхождение больше [ACCEPTABLE_SYNC_DRIFT_US].
     */
    fun validateSync(mp4: File, sourceWav: File, maxLagMs: Int = 200): Long {
        val (decoded, decodedRate) = decodeToMonoPcm(mp4)
        val source = WavPcmSource(sourceWav).use { it.readAllMono() }
        if (decoded.isEmpty() || source.isEmpty()) {
            throw TtsExportException("Sync validation: empty PCM")
        }
        val sourceRate = WavPcmSource(sourceWav).use { it.sampleRate }
        val rate = minOf(decodedRate, sourceRate)
        val lag = findLag(source, decoded, rate, maxLagMs = maxLagMs)
        val driftUs = lag.lagSamples * 1_000_000L / rate
        if (kotlin.math.abs(driftUs) > ACCEPTABLE_SYNC_DRIFT_US) {
            throw TtsExportException(
                "A/V sync drift ${driftUs}us (corr=${"%.3f".format(lag.correlation)}) exceeds " +
                    "$ACCEPTABLE_SYNC_DRIFT_US us"
            )
        }
        return driftUs
    }
}