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

    @Test
    fun policyRaisesImmediately() {
        val policy = ThermalThrottlePolicy()
        assertEquals(0L, policy.delayFor(0))
        assertEquals(100L, policy.delayFor(2))
        assertEquals(250L, policy.delayFor(3))
    }

    @Test
    fun policyHoldsThroughBriefCooling() {
        val policy = ThermalThrottlePolicy(coolDownReadings = 3)
        assertEquals(100L, policy.delayFor(2))
        // Одиночные холодные замеры не снимают паузу (гистерезис).
        assertEquals(100L, policy.delayFor(0))
        assertEquals(100L, policy.delayFor(0))
        // После серии холодных замеров уровень всё же падает.
        assertEquals(0L, policy.delayFor(0))
    }

    @Test
    fun policyStepsDownOneLevelAtATime() {
        val policy = ThermalThrottlePolicy(coolDownReadings = 1)
        assertEquals(2_000L, policy.delayFor(6))
        assertEquals(1_000L, policy.delayFor(0))
        assertEquals(500L, policy.delayFor(0))
    }

    @Test
    fun policyHeatsBackUpImmediately() {
        val policy = ThermalThrottlePolicy(coolDownReadings = 5)
        policy.delayFor(0)
        assertEquals(500L, policy.delayFor(4))
    }
}
