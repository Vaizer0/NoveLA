package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedVideoValidatorTest {

    @Test
    fun syncSampleBecomesKeyframe() {
        assertEquals(1, EncodedVideoValidator.muxerFlags(1))
    }

    @Test
    fun noFlagsStaysEmpty() {
        assertEquals(0, EncodedVideoValidator.muxerFlags(0))
    }

    @Test
    fun partialFrameIsNotWrittenAsEndOfStream() {
        // SAMPLE_FLAG_PARTIAL_FRAME = 4 совпадает с BUFFER_FLAG_END_OF_STREAM.
        // Если передать флаг как есть, муксер оборвёт дорожку.
        assertEquals(0, EncodedVideoValidator.muxerFlags(4))
    }

    @Test
    fun combinedFlagsKeepOnlyKeyframeBit() {
        assertEquals(1, EncodedVideoValidator.muxerFlags(1 or 4))
    }
}
