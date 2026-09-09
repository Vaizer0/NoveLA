package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Test

private fun durationMs(frameCount: Long, sampleRate: Int): Long {
    if (frameCount <= 0L) throw IllegalArgumentException("frameCount must be positive")
    if (sampleRate <= 0) throw IllegalArgumentException("sampleRate must be positive")
    return (frameCount * 1000L) / sampleRate.toLong()
}

public class ChapterTtsDurationNormalizerTest {
    @Test
    public fun pcm48kOneSecond() {
        assertEquals(1000L, durationMs(48_000L, 48_000))
    }

    @Test
    public fun pcm48kTwoSeconds() {
        assertEquals(2000L, durationMs(96_000L, 48_000))
    }

    @Test
    public fun pcm44kOneSecond() {
        assertEquals(1000L, durationMs(44_100L, 44_100))
    }

    @Test(expected = IllegalArgumentException::class)
    public fun zeroFramesRejected() {
        durationMs(0L, 48_000)
    }

    @Test(expected = IllegalArgumentException::class)
    public fun zeroRateRejected() {
        durationMs(1_000L, 0)
    }
}
