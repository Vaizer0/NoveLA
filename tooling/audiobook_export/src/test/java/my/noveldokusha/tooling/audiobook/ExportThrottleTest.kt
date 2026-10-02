package my.noveldokusha.tooling.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportThrottleTest {

    @Test
    fun thermalDelayIsZeroBelowModerate() {
        assertEquals(0L, thermalDelayMs(0))
        assertEquals(0L, thermalDelayMs(1))
    }

    @Test
    fun thermalDelayGrowsWithSeverity() {
        val moderate = thermalDelayMs(2)
        val severe = thermalDelayMs(3)
        val critical = thermalDelayMs(4)
        val emergency = thermalDelayMs(5)
        val shutdown = thermalDelayMs(6)

        assertTrue(moderate < severe)
        assertTrue(severe < critical)
        assertTrue(critical < emergency)
        assertTrue(emergency < shutdown)
    }

    @Test
    fun unknownStatusIsNotThrottled() {
        assertEquals(0L, thermalDelayMs(Int.MAX_VALUE))
    }
}
