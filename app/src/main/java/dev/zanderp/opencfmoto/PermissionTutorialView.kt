package dev.zanderp.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Looping mini-tutorial: Android permission prompt → tap "While using the app" → "Permissions allowed".
 * Canvas-drawn (no GIF asset), same size as the other setup tutorials so they share one dialog.
 */
class PermissionTutorialView(context: Context) : View(context) {
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

        // Faded app content behind the prompt
        fill.color = 0xFF2A2A2A.toInt()
        for (i in 0 until 4) {
            val y = top + 18f * d + i * 22f * d
            rect.set(left + 14 * d, y, left + 14 * d + (120f - i * 10f) * d, y + 7f * d)
            c.drawRoundRect(rect, 4f * d, 4f * d, fill)
        }

        // Permission prompt card (fades out once allowed)
        val cardA = 1f - seg(0.52f, 0.60f)
        val cl = left + 14f * d; val cr = right - 14f * d
        val ct = top + 38f * d; val cb = top + 150f * d
        val btnL = cl + 10f * d; val btnR = cr - 10f * d
        val btn1Top = ct + 56f * d
        val btn1Bot = btn1Top + 22f * d
        if (cardA > 0f) {
            fill.color = alpha(0xFF3C3C3C.toInt(), cardA)
            rect.set(cl, ct, cr, cb)
            c.drawRoundRect(rect, 14f * d, 14f * d, fill)
            text.isFakeBoldText = true; text.textSize = 9.5f * sp; text.color = alpha(0xFFFFFFFF.toInt(), cardA)
            c.drawText("Allow OpenCfMoto to access", cl + 12 * d, ct + 20f * d, text)
            c.drawText("this device's location?", cl + 12 * d, ct + 34f * d, text)
            // Highlighted "While using the app" button
            val press = seg(0.42f, 0.50f)
            fill.color = alpha(if (press > 0f) 0xFF22D3EE.toInt() else 0xFF4A4A4A.toInt(), cardA)
            rect.set(btnL, btn1Top, btnR, btn1Bot)
            c.drawRoundRect(rect, 11f * d, 11f * d, fill)
            text.isFakeBoldText = false; text.textSize = 9f * sp
            text.color = alpha(if (press > 0f) 0xFF00232B.toInt() else 0xFFFFFFFF.toInt(), cardA)
            val s1 = "While using the app"
            c.drawText(s1, (btnL + btnR) / 2f - text.measureText(s1) / 2f, btn1Top + 15f * d, text)
            fill.color = alpha(0xFF4A4A4A.toInt(), cardA)
            rect.set(btnL, btn1Bot + 6 * d, btnR, btn1Bot + 28f * d)
            c.drawRoundRect(rect, 11f * d, 11f * d, fill)
            text.color = alpha(0xFFFFFFFF.toInt(), cardA)
            val s2 = "Don't allow"
            c.drawText(s2, (btnL + btnR) / 2f - text.measureText(s2) / 2f, btn1Bot + 6 * d + 15f * d, text)
        }

        // "Permissions allowed" badge
        val badgeA = seg(0.60f, 0.68f) * (1f - seg(0.94f, 1f))
        if (badgeA > 0f) {
            val bw = 150f * d
            fill.color = alpha(0xFF10B981.toInt(), badgeA)
            rect.set((left + right) / 2f - bw / 2f, top + ph - 44f * d, (left + right) / 2f + bw / 2f, top + ph - 20f * d)
            c.drawRoundRect(rect, 12f * d, 12f * d, fill)
            text.isFakeBoldText = true; text.textSize = 10f * sp; text.color = alpha(0xFFFFFFFF.toInt(), badgeA)
            val s = "✓ Permissions allowed"
            c.drawText(s, (left + right) / 2f - text.measureText(s) / 2f, top + ph - 28f * d, text)
        }

        // Finger → "While using the app"
        val sx = left + pw * 0.5f; val sy = top + ph - 20f * d
        val tx = (btnL + btnR) / 2f + 20f * d; val ty = (btn1Top + btn1Bot) / 2f
        val f = ease(seg(0.05f, 0.38f))
        val fx = lerp(sx, tx, f); val fy = lerp(sy, ty, f)
        val fingerA = seg(0f, 0.08f) * (1f - seg(0.56f, 0.64f))
        if (fingerA > 0f) {
            val r = seg(0.42f, 0.54f)
            if (r > 0f && r < 1f) {
                stroke.color = alpha(0xFF22D3EE.toInt(), (1f - r) * 0.85f)
                c.drawCircle(fx, fy, lerp(8f, 22f, r) * d, stroke)
            }
            fill.color = alpha(0xFFFFFFFF.toInt(), fingerA * 0.75f)
            c.drawCircle(fx, fy, 9f * d, fill)
            stroke.color = alpha(0xFF22D3EE.toInt(), fingerA)
            c.drawCircle(fx, fy, 9f * d, stroke)
        }

        if (!showCaption) return
        val cap = if (t < 0.58f) "Tap Allow on the prompts" else "Permissions allowed"
        text.isFakeBoldText = true; text.textSize = 12f * sp; text.color = 0xFFFFB27A.toInt()
        c.drawText(cap, (width - text.measureText(cap)) / 2f, top + ph + 28f * d, text)
    }
}
