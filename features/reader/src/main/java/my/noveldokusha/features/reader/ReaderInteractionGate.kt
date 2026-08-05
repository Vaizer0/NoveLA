package my.noveldokusha.features.reader

import android.view.MotionEvent

/**
 * Tracks whether the user is currently touching or manually scrolling the reader
 * list. TTS follow-scroll must be suppressed while this returns true, otherwise
 * the reader yanks the view back to the spoken paragraph mid-gesture.
 *
 * Fed from two sources only: raw touch events (via ReaderInteractionListView's
 * dispatch-level hook) and user-initiated scroll-state transitions. Programmatic
 * auto-scrolls never reach the gate, so they cannot re-arm the grace period.
 */
class ReaderInteractionGate {

    var userIsTouching = false
        private set

    private var isScrolling = false
    private var lastInteractionTime: Long? = null

    fun onTouch(actionMasked: Int, pointerCount: Int, now: Long) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> userIsTouching = true

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> userIsTouching = false

            MotionEvent.ACTION_POINTER_UP ->
                if (pointerCount <= 1) userIsTouching = false
        }
        lastInteractionTime = now
    }

    fun onScrollStateChanged(isScrolling: Boolean, now: Long) {
        this.isScrolling = isScrolling
        lastInteractionTime = now
    }

    fun isUserInteracting(now: Long): Boolean =
        userIsTouching ||
            isScrolling ||
            lastInteractionTime?.let { now - it < GRACE_MS } == true

    private companion object {
        // Time after the last touch/scroll event during which auto-scroll stays
        // paused, so a decelerating or interrupted fling cannot be yanked back.
        const val GRACE_MS = 1500L
    }
}
