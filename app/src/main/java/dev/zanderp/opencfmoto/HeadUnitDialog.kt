package dev.zanderp.opencfmoto

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Frosted-glass "Head unit server is off" dialog (Aero Glaze style) with the animated how-to inside. */
object HeadUnitDialog {
    private const val CYAN = 0xFF00B4D8.toInt()
    private const val EMERALD = 0xFF10B981.toInt()

    /** Head unit server tutorial. */
    fun show(context: Context, onOpenAndroidAuto: () -> Unit) = showTutorial(
        context,
        titleRes = R.string.home_hus_off_title,
        msgRes = R.string.home_hus_off_msg,
        stepNumber = "3",
        stepRes = R.string.home_hus_off_step3,
        openRes = R.string.home_hus_off_open,
        tutorial = HeadUnitTutorialView(context).apply { showCaption = false },
        onOpenAndroidAuto = onOpenAndroidAuto,
    )

    /** Permission tutorial (shown while step 1 is red). */
    fun showPermission(context: Context, onGrant: () -> Unit) = showTutorial(
        context,
        titleRes = R.string.home_perm_title,
        msgRes = R.string.home_perm_msg,
        stepNumber = "✓",
        stepRes = R.string.home_perm_step,
        openRes = R.string.home_perm_grant,
        tutorial = PermissionTutorialView(context).apply { showCaption = false },
        onOpenAndroidAuto = onGrant,
    )

    /** Stop head unit server tutorial (shown when the server is running). */
    fun showStop(context: Context, onOpenAndroidAuto: () -> Unit) = showTutorial(
        context,
        titleRes = R.string.home_hus_stop_title,
        msgRes = R.string.home_hus_stop_msg,
        stepNumber = "■",
        stepRes = R.string.home_hus_stop_step,
        openRes = R.string.home_hus_off_open,
        tutorial = HeadUnitTutorialView(context).apply { showCaption = false; stopMode = true },
        onOpenAndroidAuto = onOpenAndroidAuto,
    )

    /** Server is on but stuck: stop it and start it again. */
    fun showRestart(context: Context, onOpenAndroidAuto: () -> Unit) = showTutorial(
        context,
        titleRes = R.string.home_hus_restart_title,
        msgRes = R.string.home_hus_restart_msg,
        stepNumber = "↻",
        stepRes = R.string.home_hus_restart_step,
        openRes = R.string.home_hus_off_open,
        tutorial = HeadUnitTutorialView(context).apply { showCaption = false; stopMode = true },
        onOpenAndroidAuto = onOpenAndroidAuto,
    )

    /** Developer-mode tutorial (tap Version ~10 times). */
    fun showDevMode(
        context: Context,
        alreadyOn: Boolean,
        onToggle: (Boolean) -> Unit,
        onOpenAndroidAuto: () -> Unit,
    ) = showTutorial(
        context,
        titleRes = R.string.home_dev_title,
        msgRes = R.string.home_dev_msg,
        stepNumber = "✓",
        stepRes = R.string.home_dev_step,
        openRes = R.string.home_hus_off_open,
        tutorial = DevModeTutorialView(context).apply { showCaption = false },
        onOpenAndroidAuto = onOpenAndroidAuto,
        switchLabelRes = R.string.home_dev_switch,
        switchChecked = alreadyOn,
        onSwitch = onToggle,
    )

    private fun showTutorial(
        context: Context,
        titleRes: Int,
        msgRes: Int,
        stepNumber: String,
        stepRes: Int,
        openRes: Int,
        tutorial: View,
        onOpenAndroidAuto: () -> Unit,
        switchLabelRes: Int = 0,
        switchChecked: Boolean = false,
        onSwitch: ((Boolean) -> Unit)? = null,
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        fun roundRect(fill: Int, radius: Float, strokeColor: Int = 0, strokeDp: Float = 1f) =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius * d
                if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
            }

        fun glassButton(label: String, bg: GradientDrawable, textColor: Int, onClick: () -> Unit) =
            TextView(context).apply {
                text = label
                setTextColor(textColor)
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = dp(48f)
                setPadding(dp(16f), 0, dp(16f), 0)
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
                setOnClickListener { onClick() }
            }

        // --- Header: icon pill + title + helper copy
        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2600B4D8)
                setStroke(dp(1f), 0x4D22D3EE)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_cast)
                    imageTintList = ColorStateList.valueOf(0xFF22D3EE.toInt())
                },
                FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER),
            )
        }
        val title = TextView(context).apply {
            setText(titleRes)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pill, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) })
        }
        val sub = TextView(context).apply {
            setText(msgRes)
            setTextColor(0xE6CBD5E1.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.2f)
        }

        // --- Nested glass card with the looping tutorial
        val tutorialCard = FrameLayout(context).apply {
            background = roundRect(0x990F172A.toInt(), 18f, 0x1AFFFFFF)
            setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
            addView(tutorial, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // --- Step 3 pill
        val badge = TextView(context).apply {
            text = stepNumber
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EMERALD) }
        }
        val stepText = TextView(context).apply {
            setText(stepRes)
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val step = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(0x0DFFFFFF, 14f, 0x1AFFFFFF)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addView(badge, LinearLayout.LayoutParams(dp(24f), dp(24f)))
            addView(stepText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10f) })
        }

        // --- Buttons
        val cancel = glassButton(
            context.getString(android.R.string.cancel),
            roundRect(0x0DFFFFFF, 16f, 0x26FFFFFF),
            0xFFCBD5E1.toInt(),
        ) { dialog.dismiss() }
        val open = glassButton(
            context.getString(openRes),
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF22D3EE.toInt(), CYAN, 0xFF0096C7.toInt())).apply {
                cornerRadius = 16f * d
            },
            Color.WHITE,
        ) { dialog.dismiss(); onOpenAndroidAuto() }
        open.elevation = 6f * d
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(open, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply { marginStart = dp(10f) })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            addView(header)
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
            addView(tutorialCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
            addView(step, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
            if (onSwitch != null && switchLabelRes != 0) {
                val sw = android.widget.Switch(context).apply {
                    setText(switchLabelRes)
                    setTextColor(0xFFE2E8F0.toInt())
                    textSize = 13.5f
                    isChecked = switchChecked
                    thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(EMERALD, 0xFFCBD5E1.toInt()),
                    )
                    trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(0x8010B981.toInt(), 0x4D94A3B8),
                    )
                    background = roundRect(0x0DFFFFFF, 14f, 0x1AFFFFFF)
                    setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                    setOnCheckedChangeListener { _, on -> onSwitch(on) }
                }
                addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
            }
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        }
        dialog.setContentView(root)

        dialog.window?.apply {
            // Frosted surface: translucent fill + real blur-behind on Android 12+, a denser fill below.
            setBackgroundDrawable(
                roundRect(if (blurOk) 0xB8111827.toInt() else 0xEB111827.toInt(), 28f, 0x29FFFFFF),
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.65f
                if (blurOk) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    blurBehindRadius = dp(14f)
                }
            }
            if (blurOk) setBackgroundBlurRadius(dp(28f))
            setLayout(context.resources.displayMetrics.widthPixels - dp(40f), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        // Entrance: fade + overshooting scale-up.
        root.alpha = 0f
        root.scaleX = 0.9f
        root.scaleY = 0.9f
        dialog.setOnShowListener {
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320L)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        dialog.show()
    }
}
