package my.noveldokusha.features.reader

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionGateTest {

    private val gate = ReaderInteractionGate()

    private fun down(now: Long) = gate.onTouch(MotionEvent.ACTION_DOWN, pointerCount = 1, now = now)
    private fun up(now: Long) = gate.onTouch(MotionEvent.ACTION_UP, pointerCount = 1, now = now)

    @Test
    fun `idle after grace is not interacting`() {
        assertFalse(gate.isUserInteracting(now = 0L))
        assertFalse(gate.isUserInteracting(now = 10_000L))
    }

    @Test
    fun `touching blocks auto-scroll even without movement`() {
        down(now = 100L)
        assertTrue(gate.isUserInteracting(now = 100L))
        // Finger held still long after grace would have expired must stay blocking.
        assertTrue(gate.isUserInteracting(now = 10_000L))
        up(now = 10_000L)
        // Grace period keeps the gate closed right after the release...
        assertTrue(gate.isUserInteracting(now = 10_000L))
        // ...and only expires 1500ms after the last interaction event.
        assertFalse(gate.isUserInteracting(now = 11_500L))
    }

    @Test
    fun `scroll state blocks auto-scroll`() {
        gate.onScrollStateChanged(isScrolling = true, now = 0L)
        assertTrue(gate.isUserInteracting(now = 100L))
        gate.onScrollStateChanged(isScrolling = false, now = 100L)
        // Grace period still blocks shortly after the scroll ends.
        assertTrue(gate.isUserInteracting(now = 1_500L))
        assertFalse(gate.isUserInteracting(now = 1_600L))
    }

    @Test
    fun `onScroll refreshes the grace period`() {
        gate.onScrollStateChanged(isScrolling = true, now = 0L)
        gate.onScrollStateChanged(isScrolling = false, now = 0L)
        gate.onScroll(now = 1_000L)
        assertTrue(gate.isUserInteracting(now = 2_400L))
        assertFalse(gate.isUserInteracting(now = 2_600L))
    }

    @Test
    fun `pointer up while another pointer remains keeps touching`() {
        gate.onTouch(MotionEvent.ACTION_POINTER_DOWN, pointerCount = 2, now = 0L)
        gate.onTouch(MotionEvent.ACTION_POINTER_UP, pointerCount = 2, now = 100L)
        // One finger is still down, so the gate must keep blocking.
        assertTrue(gate.isUserInteracting(now = 100L))
        gate.onTouch(MotionEvent.ACTION_UP, pointerCount = 1, now = 200L)
        assertFalse(gate.userIsTouching)
        assertTrue(gate.isUserInteracting(now = 200L))
        assertFalse(gate.isUserInteracting(now = 1_700L))
    }

    @Test
    fun `cancel clears touching`() {
        down(now = 0L)
        gate.onTouch(MotionEvent.ACTION_CANCEL, pointerCount = 1, now = 10L)
        assertFalse(gate.userIsTouching)
    }
}
