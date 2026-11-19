package dev.zanderp.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Looping mini-tutorial: Android Auto settings → tap "Version" ~10 times → "Developer settings unlocked".
 * Canvas-drawn (no GIF asset), sized like [HeadUnitTutorialView] so both fit the same dialog.
 */
class DevModeTutorialView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var showCaption = true

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((260 * d).toInt(), w), ((if (showCaption) 250 else 214) * d).toInt())
    }

    private fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun ease(f: Float) = f * f * (3 - 2 * f)
    private fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onDraw(c: Canvas) {
        val pw = 190f * d
        val ph = 205f * d
        val left = (width - pw) / 2f
        val top = 4f * d
        val right = left + pw

        fill.color = 0xFF1F1F1F.toInt()
        rect.set(left, top, right, top + ph)
        c.drawRoundRect(rect, 18f * d, 18f * d, fill)
        stroke.color = 0xFF6B6B6B.toInt()
        c.drawRoundRect(rect, 18f * d, 18f * d, stroke)

        val barH = 36f * d
        c.save()
        c.clipRect(left + 2 * d, top + 2 * d, right - 2 * d, top + barH)
        fill.color = 0xFF2E2E2E.toInt()
        c.drawRect(left, top, right, top + barH, fill)
        c.restore()
        text.color = 0xFFFFFFFF.toInt(); text.textSize = 11f * sp; text.isFakeBoldText = true
        c.drawText("Android Auto settings", left + 12 * d, top + barH / 2f + 4 * d, text)

        // Placeholder rows, then the highlighted "Version" row
        fill.color = 0xFF3A3A3A.toInt()
        for (i in 0 until 3) {
            val y = top + barH + 16f * d + i * 24f * d
            rect.set(left + 14 * d, y, left + 14 * d + (112f - i * 14f) * d, y + 7f * d)
            c.drawRoundRect(rect, 4f * d, 4f * d, fill)
        }
        val vy = top + barH + 16f * d + 3 * 24f * d      // row top
        val tapT = seg(0.12f, 0.62f)
        val pressed = tapT > 0f && tapT < 1f
        fill.color = if (pressed) 0xFF2F3B45.toInt() else 0xFF2A2A2A.toInt()
        rect.set(left + 8 * d, vy - 6 * d, right - 8 * d, vy + 28f * d)
        c.drawRoundRect(rect, 10f * d, 10f * d, fill)
        text.isFakeBoldText = true; text.textSize = 10.5f * sp; text.color = 0xFFFFFFFF.toInt()
        c.drawText("Version", left + 16 * d, vy + 8f * d, text)
        text.isFakeBoldText = false; text.textSize = 9f * sp; text.color = 0xFF9A9A9A.toInt()
        c.drawText("Android Auto 17.4", left + 16 * d, vy + 21f * d, text)

        // Tap counter chip (counts 1 → 10)
        val taps = if (tapT <= 0f) 0 else minOf(10, (tapT * 10f).toInt() + 1)
        if (taps > 0) {
            val chipA = 1f - seg(0.64f, 0.70f)
            fill.color = alpha(0xFF22D3EE.toInt(), chipA)
            val cx = right - 34f * d
            rect.set(cx - 18 * d, vy + 2 * d, cx + 18 * d, vy + 20f * d)
            c.drawRoundRect(rect, 9f * d, 9f * d, fill)
            text.isFakeBoldText = true; text.textSize = 10f * sp; text.color = alpha(0xFF00232B.toInt(), chipA)
            val s = "×$taps"
            c.drawText(s, cx - text.measureText(s) / 2f, vy + 15f * d, text)
        }

        // "Developer settings unlocked" pill
        val badgeA = seg(0.68f, 0.76f) * (1f - seg(0.94f, 1f))
        if (badgeA > 0f) {
            val bw = 160f * d
            fill.color = alpha(0xFF10B981.toInt(), badgeA)
            rect.set((left + right) / 2f - bw / 2f, top + ph - 40f * d, (left + right) / 2f + bw / 2f, top + ph - 16f * d)
            c.drawRoundRect(rect, 12f * d, 12f * d, fill)
            text.isFakeBoldText = true; text.textSize = 9.5f * sp; text.color = alpha(0xFFFFFFFF.toInt(), badgeA)
            val s = "✓ Developer settings unlocked"
            c.drawText(s, (left + right) / 2f - text.measureText(s) / 2f, top + ph - 24f * d, text)
        }

        // Finger: slides to the Version row, taps rhythmically, then fades
        val sx = left + pw * 0.5f; val sy = top + ph - 20f * d
        val tx = right - 34f * d; val ty = vy + 11f * d
        val f = ease(seg(0f, 0.12f))
        val bob = if (pressed) (if ((tapT * 10f) % 1f < 0.5f) 2f * d else 0f) else 0f
        val fx = lerp(sx, tx, f)
        val fy = lerp(sy, ty, f) + bob
        val fingerA = seg(0f, 0.04f) * (1f - seg(0.62f, 0.70f))
        if (fingerA > 0f) {
            if (pressed) {
                val r = (tapT * 10f) % 1f
                stroke.color = alpha(0xFF22D3EE.toInt(), (1f - r) * 0.85f)
                c.drawCircle(fx, fy, lerp(8f, 20f, r) * d, stroke)
            }
            fill.color = alpha(0xFFFFFFFF.toInt(), fingerA * 0.75f)
            c.drawCircle(fx, fy, 9f * d, fill)
            stroke.color = alpha(0xFF22D3EE.toInt(), fingerA)
            c.drawCircle(fx, fy, 9f * d, stroke)
        }

        if (!showCaption) return
        val cap = if (t < 0.66f) "Tap Version about 10 times" else "Developer settings unlocked"
        text.isFakeBoldText = true; text.textSize = 12f * sp; text.color = 0xFFFFB27A.toInt()
        c.drawText(cap, (width - text.measureText(cap)) / 2f, top + ph + 28f * d, text)
    }
}
