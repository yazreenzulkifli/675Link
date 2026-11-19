package dev.zanderp.opencfmoto

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.CompoundButton
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * Wraps one card (its first child) so the WHOLE card can be swiped left / right like pages: the card
 * follows the finger, then slides out, [onCommit] swaps what it shows, and it slides back in from the
 * other side. Sliders and switches keep their own horizontal drags; vertical scrolling is untouched.
 */
class SwipeCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** dir = +1 (swipe left, next box) or -1 (swipe right, previous box): is there a box that way? */
    var canMove: (Int) -> Boolean = { false }

    /** Called while the card is off-screen: show the next / previous box's content. */
    var onCommit: (Int) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6
    private var downX = 0f
    private var downY = 0f
    private var ignore = false
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private val card: View? get() = if (childCount > 0) getChildAt(0) else null

    /** True when the touch lands on a control that needs its own horizontal drag. */
    private fun hitsHorizontalControl(root: View, x: Float, y: Float): Boolean {
        if (root is AbsSeekBar || root is CompoundButton) return true
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val c = root.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                val cx = x + root.scrollX - c.left
                val cy = y + root.scrollY - c.top
                if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && hitsHorizontalControl(c, cx, cy)) return true
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                ignore = hitsHorizontalControl(this, ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> if (!ignore && !dragging) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    velocity = VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        val c = card ?: return false
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dir = if (dx < 0) 1 else -1
                val shown = if (canMove(dir)) dx else dx * 0.25f   // rubber-band at the ends
                c.translationX = shown
                c.alpha = 1f - 0.5f * min(1f, abs(shown) / width)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - downX
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                dragging = false
                val dir = if (dx < 0) 1 else -1
                val far = abs(dx) > width * 0.25f || (abs(vx) > minFling && (vx < 0) == (dx < 0))
                if (ev.actionMasked == MotionEvent.ACTION_UP && far && canMove(dir)) commit(c, dir)
                else c.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }
        }
        return true
    }

    /** Same slide as a swipe, started from code (e.g. tapping a tab). */
    fun slide(dir: Int) {
        val c = card ?: return
        if (canMove(dir)) commit(c, dir)
    }

    private fun commit(c: View, dir: Int) {
        val out = -dir * width.toFloat()
        c.animate().translationX(out).alpha(0f).setDuration(140).withEndAction {
            onCommit(dir)
            c.translationX = -out
            c.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }.start()
    }
}
