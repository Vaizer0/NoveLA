package my.noveldokusha.video_export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class EncodeTimingTest {

    @Test
    fun `audio pts follows sample position`() {
        assertEquals(0L, EncodeTiming.audioPtsUs(0, 44100))
        assertEquals(1_000_000L, EncodeTiming.audioPtsUs(44100, 44100))
        assertEquals(23_219L, EncodeTiming.audioPtsUs(1024, 44100))
    }

    @Test
    fun `audio pts applies priming offset and clamps`() {
        assertEquals(1_020_000L, EncodeTiming.audioPtsUs(44100, 44100, offsetUs = 20_000L))
        assertEquals(0L, EncodeTiming.audioPtsUs(44100, 44100, offsetUs = -2_000_000L))
    }

    @Test
    fun `video pts counts frames at 30fps`() {
        assertEquals(0L, EncodeTiming.videoPtsUs(0))
        assertEquals(33_333L, EncodeTiming.videoPtsUs(1))
        assertEquals(1_000_000L, EncodeTiming.videoPtsUs(30))
    }

    @Test
    fun `frame count covers whole audio including tail`() {
        assertEquals(300, EncodeTiming.frameCount(44100 * 10, 44100))
        assertEquals(301, EncodeTiming.frameCount(44100 * 10 + 1, 44100))
        assertEquals(1, EncodeTiming.frameCount(0, 44100))
        assertEquals(1, EncodeTiming.frameCount(44100 / 2, 44100))
    }

    @Test
    fun `sample for frame maps frame time to samples and clips`() {
        assertEquals(0L, EncodeTiming.sampleForFrame(0, 44100, 1_000_000))
        assertEquals(44100L, EncodeTiming.sampleForFrame(30, 44100, 1_000_000))
        assertEquals(1_000_000L, EncodeTiming.sampleForFrame(100_000, 44100, 1_000_000))
    }
}

class WavPcmSourceTest {

    @Test
    fun `reads mono pcm16 header and data`() {
        val rate = 8000
        val samples = intArrayOf(0, 1000, -1000, 16383, -16384)
        val wav = buildWav(rate, 1, samples)
        val tmp = File.createTempFile("wavsrc", ".wav")
        tmp.writeBytes(wav)

        WavPcmSource(tmp).use { src ->
            assertEquals(rate, src.sampleRate)
            assertEquals(1, src.channelCount)
            assertEquals(5L, src.totalSamples)
            val mono = src.readAllMono()
            assertEquals(5, mono.size)
            assertEquals(0f, mono[0], 0.001f)
            assertEquals(1000f / 32768f, mono[1], 0.002f)
            assertEquals(-1000f / 32768f, mono[2], 0.002f)
        }
        tmp.delete()
    }

    @Test
    fun `reads stereo by averaging channels`() {
        val rate = 8000
        // stereo frames: (1000, 3000), (-2000, 0)
        val wav = buildWav(rate, 2, intArrayOf(1000, 3000, -2000, 0))
        val tmp = File.createTempFile("wavst", ".wav")
        tmp.writeBytes(wav)

        WavPcmSource(tmp).use { src ->
            assertEquals(2, src.channelCount)
            assertEquals(2L, src.totalSamples)
            val mono = src.readAllMono()
            assertEquals(2, mono.size)
            assertEquals(2000f / 32768f, mono[0], 0.002f)
            assertEquals(-1000f / 32768f, mono[1], 0.002f)
        }
        tmp.delete()
    }

    @Test
    fun `rejects non-pcm header`() {
        val wav = buildWav(8000, 1, intArrayOf(0, 1)).also { it[20] = 3.toByte() }
        val tmp = File.createTempFile("wavbad", ".wav")
        tmp.writeBytes(wav)
        var threw = false
        try {
            WavPcmSource(tmp).use { }
        } catch (_: my.noveldokusha.text_to_speech.TtsExportException) {
            threw = true
        }
        assertTrue("should throw TtsExportException for non-PCM", threw)
        tmp.delete()
    }

    fun buildWav(sampleRate: Int, channels: Int, samples: IntArray): ByteArray {
        val pcmSize = samples.size * 2
        val header = ByteArray(pcmSize + 44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcmSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcmSize)
        for (s in samples) buf.putShort(s.toShort())
        return header
    }
}

class SyncProbeLagTest {

    private fun tone(samples: Int, rate: Int, freq: Double, amp: Float = 0.5f): FloatArray =
        FloatArray(samples) { i -> (sin(2.0 * Math.PI * freq * i / rate) * amp).toFloat() }

    @Test
    fun `zero lag with identical pcm`() {
        val rate = 8000
        val src = tone(rate, rate, 440.0)
        val lag = SyncProbe.findLag(src, src.copyOf(), rate, maxLagMs = 200, windowMs = 120)
        assertEquals(0L, lag.lagSamples)
        assertTrue(lag.correlation > 0.99)
    }

    @Test
    fun `detects known positive lag`() {
        val rate = 8000
        val src = FloatArray(rate) + tone(rate, rate, 440.0)
        // decoded = 100ms leading silence + src -> lag = 800 samples.
        val silence = FloatArray(800)
        val decoded = silence + src
        val lag = SyncProbe.findLag(src, decoded, rate, maxLagMs = 200, windowMs = 120)
        assertEquals(800L, lag.lagSamples)
        assertTrue(lag.correlation > 0.99)
    }

    @Test
    fun `detects lag under noise`() {
        val rate = 8000
        val src = tone(rate, rate, 330.0, 0.6f)
        val silence = FloatArray(480) // 60ms lag
        val decoded = silence + src
        for (i in decoded.indices) {
            decoded[i] += ((Math.random() - 0.5) * 0.08).toFloat()
        }
        val lag = SyncProbe.findLag(src, decoded, rate, maxLagMs = 200, windowMs = 120)
        assertEquals(480L, lag.lagSamples)
    }

    @Test
    fun `clamps to max lag`() {
        val rate = 8000
        val src = tone(rate, rate, 440.0)
        val decoded = FloatArray(1600) + src // 200ms lag, but search capped at 100ms
        val lag = SyncProbe.findLag(src, decoded, rate, maxLagMs = 100, windowMs = 120)
        assertTrue(lag.lagSamples in 0..800)
        assertTrue(lag.correlation < 0.5)
    }

    @Test
    fun `window measured in 33ms frame equivalence`() {
        assertEquals(
            33_333L,
            SyncProbe.ACCEPTABLE_SYNC_DRIFT_US,
        )
    }
}