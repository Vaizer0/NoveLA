package my.noveldokusha.features.reader

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListView

/**
 * A [ListView] that reports every raw touch event before child views see it.
 * A plain `setOnTouchListener` only fires when no child consumes the event, so
 * touches on clickable body text would be invisible to the interaction gate.
 * Dispatch-level reporting has no such hole and never consumes the event.
 */
class ReaderInteractionListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ListView(context, attrs, defStyleAttr) {

    var interactionTouchListener: ((actionMasked: Int, pointerCount: Int, now: Long) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        interactionTouchListener?.invoke(
            ev.actionMasked,
            ev.pointerCount,
            SystemClock.elapsedRealtime()
        )
        return super.dispatchTouchEvent(ev)
    }
}
