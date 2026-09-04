package my.noveldokusha.text_to_speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsNativeRangeNormalizerTest {

    @Test
    fun `normal android callback stays unchanged`() {
        val normalizer = TtsNativeRangeNormalizer(textLength = 20)

        val range = normalizer.normalize(0, 5, 360)

        assertEquals(NormalizedTtsRange(0, 5, 360), range)
    }

    @Test
    fun `reversed synth callback is normalized`() {
        val normalizer = TtsNativeRangeNormalizer(textLength = 20)

        val range = normalizer.normalize(360, 0, 5)

        assertEquals(NormalizedTtsRange(0, 5, 360), range)
    }

    @Test
    fun `reversed callback detection persists for later ranges`() {
        val normalizer = TtsNativeRangeNormalizer(textLength = 71)

        assertEquals(NormalizedTtsRange(0, 5, 360), normalizer.normalize(360, 0, 5))
        assertEquals(NormalizedTtsRange(65, 71, 107941), normalizer.normalize(107941, 65, 71))
    }

    @Test
    fun `invalid callback is rejected`() {
        val normalizer = TtsNativeRangeNormalizer(textLength = 20)

        assertNull(normalizer.normalize(20, 0, 5))
        assertNull(normalizer.normalize(-1, 2, 10))
    }

    @Test
    fun `standard callback with small frame is not swapped`() {
        val normalizer = TtsNativeRangeNormalizer(textLength = 20)

        val range = normalizer.normalize(0, 2, 5)

        assertEquals(NormalizedTtsRange(0, 2, 5), range)
    }
}
