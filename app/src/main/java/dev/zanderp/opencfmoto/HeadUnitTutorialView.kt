package dev.zanderp.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.animation.LinearInterpolator
import android.view.View

/**
 * Looping mini-tutorial: Android Auto settings → tap ⋮ → tap "Start head unit server" → running.
 * Drawn on a Canvas so it needs no GIF/video asset and stays crisp in light and dark.
 */
class HeadUnitTutorialView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()

    /** Show the Stop flow (menu item "Stop head unit server" → red "Server stopped") instead of Start. */
    var stopMode = false

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    /** Draw the "1 / 2 / 3" caption under the phone (off when the host dialog shows its own step pill). */
    var showCaption = true

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((260 * d).toInt(), w), ((if (showCaption) 250 else 214) * d).toInt())
    }

    private fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun ease(f: Float) = f * f * (3 - 2 * f)

    override fun onDraw(c: Canvas) {
        val pw = 190f * d
        val ph = 205f * d
        val left = (width - pw) / 2f
        val top = 4f * d
        val right = left + pw

        // Phone body
        fill.color = 0xFF1F1F1F.toInt()
        rect.set(left, top, right, top + ph)
        c.drawRoundRect(rect, 18f * d, 18f * d, fill)
        stroke.color = 0xFF6B6B6B.toInt()
        c.drawRoundRect(rect, 18f * d, 18f * d, stroke)

        // App bar
        val barH = 36f * d
        c.save()
        c.clipRect(left + 2 * d, top + 2 * d, right - 2 * d, top + barH)
        fill.color = 0xFF2E2E2E.toInt()
        c.drawRect(left, top, right, top + barH, fill)
        c.restore()
        text.color = 0xFFFFFFFF.toInt(); text.textSize = 11f * sp; text.isFakeBoldText = true
        c.drawText("Android Auto", left + 12 * d, top + barH / 2f + 4 * d, text)

        // ⋮ button
        val dx = right - 16f * d
        val dy = top + barH / 2f
        fill.color = 0xFFFFFFFF.toInt()
        for (i in -1..1) c.drawCircle(dx, dy + i * 5f * d, 1.8f * d, fill)

        // Placeholder settings rows
        fill.color = 0xFF3A3A3A.toInt()
        for (i in 0 until 4) {
            val y = top + barH + 20f * d + i * 26f * d
            rect.set(left + 14 * d, y, left + 14 * d + (110f - i * 12f) * d, y + 7f * d)
            c.drawRoundRect(rect, 4f * d, 4f * d, fill)
        }

        // Menu (fades in after tapping ⋮, out after choosing the item)
        val menuA = seg(0.30f, 0.38f) * (1f - seg(0.64f, 0.70f))
        val mw = 150f * d
        val mh = 56f * d
        val ml = right - mw - 8f * d
        val mt = top + barH - 2f * d
        val itemY = mt + 17f * d
        if (menuA > 0f) {
            fill.color = (0xFF3C3C3C.toInt() and 0x00FFFFFF) or ((menuA * 255).toInt() shl 24)
            rect.set(ml, mt, ml + mw, mt + mh)
            c.drawRoundRect(rect, 8f * d, 8f * d, fill)
            val hi = seg(0.54f, 0.58f)
            if (hi > 0f) {
                fill.color = (0xFF3DDC84.toInt() and 0x00FFFFFF) or ((hi * menuA * 90).toInt() shl 24)
                rect.set(ml + 4 * d, mt + 4 * d, ml + mw - 4 * d, mt + 30f * d)
                c.drawRoundRect(rect, 6f * d, 6f * d, fill)
            }
            text.isFakeBoldText = false; text.textSize = 9.5f * sp
            text.color = (0xFFFFFFFF.toInt() and 0x00FFFFFF) or ((menuA * 255).toInt() shl 24)
            c.drawText(if (stopMode) "Stop head unit server" else "Start head unit server", ml + 10 * d, mt + 21f * d, text)
            text.color = (0xFF9A9A9A.toInt() and 0x00FFFFFF) or ((menuA * 255).toInt() shl 24)
            c.drawText("Application mode", ml + 10 * d, mt + 45f * d, text)
        }

        // "Server running" badge
        val badgeA = seg(0.68f, 0.76f) * (1f - seg(0.94f, 1f))
        if (badgeA > 0f) {
            val bw = 128f * d
            fill.color = ((if (stopMode) 0xFFEF4444.toInt() else 0xFF10B981.toInt()) and 0x00FFFFFF) or ((badgeA * 255).toInt() shl 24)
            rect.set((left + right) / 2f - bw / 2f, top + ph - 44f * d, (left + right) / 2f + bw / 2f, top + ph - 20f * d)
            c.drawRoundRect(rect, 12f * d, 12f * d, fill)
            text.isFakeBoldText = true; text.textSize = 10f * sp
            text.color = (0xFFFFFFFF.toInt() and 0x00FFFFFF) or ((badgeA * 255).toInt() shl 24)
            val s = if (stopMode) "■ Server stopped" else "✓ Server running"
            c.drawText(s, (left + right) / 2f - text.measureText(s) / 2f, top + ph - 28f * d, text)
        }

        // Finger: bottom → ⋮ → menu item → fade away
        val sx = left + pw * 0.45f; val sy = top + ph - 30f * d
        val tx = ml + mw * 0.55f
        val fx: Float; val fy: Float
        when {
            t < 0.20f -> { val f = ease(seg(0f, 0.20f)); fx = lerp(sx, dx, f); fy = lerp(sy, dy, f) }
            t < 0.40f -> { fx = dx; fy = dy }
            t < 0.52f -> { val f = ease(seg(0.40f, 0.52f)); fx = lerp(dx, tx, f); fy = lerp(dy, itemY, f) }
            else -> { fx = tx; fy = itemY }
        }
        val fingerA = seg(0f, 0.05f) * (1f - seg(0.62f, 0.70f))
        if (fingerA > 0f) {
            // tap ripples at ⋮ (0.22–0.32) and at the menu item (0.54–0.62)
            for ((a, b) in listOf(0.22f to 0.32f, 0.54f to 0.62f)) {
                val r = seg(a, b)
                if (r > 0f && r < 1f) {
                    stroke.color = (0xFF22D3EE.toInt() and 0x00FFFFFF) or (((1f - r) * 220).toInt() shl 24)
                    c.drawCircle(fx, fy, lerp(8f, 22f, r) * d, stroke)
                }
            }
            fill.color = (0xFFFFFFFF.toInt() and 0x00FFFFFF) or ((fingerA * 190).toInt() shl 24)
            c.drawCircle(fx, fy, 9f * d, fill)
            stroke.color = (0xFF22D3EE.toInt() and 0x00FFFFFF) or ((fingerA * 255).toInt() shl 24)
            c.drawCircle(fx, fy, 9f * d, stroke)
        }

        if (!showCaption) return
        // Step caption
        val cap = when {
            t < 0.30f -> "1  Open Android Auto, tap ⋮"
            t < 0.66f -> "2  Tap Start head unit server"
            else -> "3  Come back here — we connect"
        }
        text.isFakeBoldText = true; text.textSize = 12f * sp
        text.color = 0xFFFFB27A.toInt()
        c.drawText(cap, (width - text.measureText(cap)) / 2f, top + ph + 28f * d, text)
    }
}
