package dev.zanderp.opencfmoto

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One-time "Follow Yaz on TikTok" popup (Aero Glaze: light frosted card) shown on first launch. */
object FollowDialog {
    private const val TIKTOK_URL = "https://www.tiktok.com/@yzrn94"
    private const val PREFS = "home_prefs"
    private const val KEY = "follow_tiktok_shown"
    private const val CYAN = 0xFF00F2FE.toInt()
    private const val PINK = 0xFFFE2C55.toInt()

    fun showOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        prefs.edit().putBoolean(KEY, true).apply()
        show(context)
    }

    fun show(context: Context) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        fun rect(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * d
            if (stroke != 0) setStroke(dp(1f), stroke)
        }

        fun note(color: Int, sizeSp: Float, dx: Float = 0f) = TextView(context).apply {
            text = "♪"
            setTextColor(color)
            textSize = sizeSp
            gravity = Gravity.CENTER
            includeFontPadding = false
            translationX = dx * d
        }

        // 1. TikTok badge: dark squircle, note with cyan/magenta split.
        val badge = FrameLayout(context).apply {
            background = rect(0xFF0D0E12.toInt(), 16f, 0x1AFFFFFF)
            elevation = 4f * d
            addView(note(CYAN, 26f, -1.5f), FrameLayout.LayoutParams(-1, -1))
            addView(note(PINK, 26f, 1.5f), FrameLayout.LayoutParams(-1, -1))
            addView(note(Color.WHITE, 26f), FrameLayout.LayoutParams(-1, -1))
        }

        // 2. Creator pill: ● @yzrn94 ✓ | Creator
        fun label(t: String, color: Int, sp: Float, bold: Boolean) = TextView(context).apply {
            text = t
            setTextColor(color)
            textSize = sp
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PINK) } }
        val divider = View(context).apply { setBackgroundColor(0xFFCBD5E1.toInt()) }
        val creator = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rect(0xE6F1F5F9.toInt(), 50f, 0x80E2E8F0.toInt())
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            addView(dot, LinearLayout.LayoutParams(dp(5f), dp(5f)))
            addView(label("@yzrn94", 0xFF1E293B.toInt(), 12f, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
            addView(label("✓", 0xFF0EA5E9.toInt(), 12f, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4f) })
            addView(divider, LinearLayout.LayoutParams(dp(1f), dp(10f)).apply { marginStart = dp(8f); marginEnd = dp(8f) })
            addView(label("Creator", 0xFF94A3B8.toInt(), 10f, false))
        }

        // 3. Title
        val title = label(context.getString(R.string.follow_title), 0xFF0F172A.toInt(), 9f, true).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
        }

        // 4. Buttons
        fun button(text: String, bg: GradientDrawable, color: Int, heightDp: Float, ripple: Int, onClick: () -> Unit) =
            TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = dp(heightDp)
                background = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)
                setOnClickListener { onClick() }
            }

        val follow = button(
            "♪  " + context.getString(R.string.follow_button),
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PINK, 0xFFFF0050.toInt()))
                .apply { cornerRadius = 12f * d },
            Color.WHITE, 44f, 0x33FFFFFF,
        ) {
            dialog.dismiss()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TIKTOK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }.apply { elevation = 4f * d }
        val later = button(
            context.getString(R.string.follow_later),
            rect(0xCCF1F5F9.toInt(), 12f),
            0xFF334155.toInt(), 44f, 0x22000000,
        ) { dialog.dismiss() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(18f), dp(16f), dp(16f))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            addView(follow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
            addView(later, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        }
        dialog.setContentView(root)

        dialog.window?.apply {
            setBackgroundDrawable(rect(if (blurOk) 0xE0FFFFFF.toInt() else 0xF5FFFFFF.toInt(), 24f, 0xCCFFFFFF.toInt()))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.3f
                if (blurOk) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    blurBehindRadius = dp(8f)
                }
            }
            if (blurOk) setBackgroundBlurRadius(dp(20f))
            setLayout(dp(280f), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        // Pop-out: scale bounce + fade; note badge wiggles after landing.
        root.alpha = 0f
        root.scaleX = 0.3f
        root.scaleY = 0.3f
        dialog.setOnShowListener {
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520L)
                .setInterpolator(OvershootInterpolator(2.6f))
                .start()
        }
        dialog.show()
    }
}
