package dev.zanderp.opencfmoto

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Which bike or car?" picker shown before Connect when more than one device is saved in the Garage
 * (bikes via QR, plus the Perodua / Carbit USB car). Picking one selects it and carries on.
 */
object ConnectTargetDialog {
    private const val CYAN = 0xFF00B4D8.toInt()
    private const val EMERALD = 0xFF10B981.toInt()

    fun show(context: Context, onlyBikes: Boolean = false, onPicked: (SavedBike?) -> Unit) {
        val devices = BikeMemory.devices(context).filter { !onlyBikes || !UsbCar.isUsb(it.qr) }
        val selectedRaw = BikeMemory.selected(context)?.raw
        // Nothing to choose between: carry on with whatever is selected (or the scan flow).
        if (devices.size < 2) {
            onPicked(devices.firstOrNull())
            return
        }
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

        val title = TextView(context).apply {
            setText(if (onlyBikes) R.string.pick_target_title_bike else R.string.pick_target_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sub = TextView(context).apply {
            setText(R.string.pick_target_msg)
            setTextColor(0xE6CBD5E1.toInt())
            textSize = 14f
        }

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (dev in devices) {
            val isCar = UsbCar.isUsb(dev.qr)
            val active = dev.raw == selectedRaw
            val icon = ImageView(context).apply {
                setImageResource(if (isCar) R.drawable.ic_devices else R.drawable.ic_ride)
                imageTintList = ColorStateList.valueOf(0xFF22D3EE.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2600B4D8) }
                setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            }
            val name = TextView(context).apply {
                text = dev.name
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val kind = TextView(context).apply {
                setText(if (isCar) R.string.pick_target_car else R.string.pick_target_bike)
                setTextColor(0xFF94A3B8.toInt())
                textSize = 11.5f
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(kind)
            }
            val tick = TextView(context).apply {
                text = if (active) "✓" else ""
                setTextColor(EMERALD)
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(14f), dp(10f))
                val bg = rect(
                    if (active) 0x2610B981 else 0x0DFFFFFF, 16f,
                    if (active) 0x6610B981 else 0x1AFFFFFF,
                )
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
                addView(icon, LinearLayout.LayoutParams(dp(40f), dp(40f)))
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
                addView(tick)
                setOnClickListener {
                    dialog.dismiss()
                    BikeMemory.select(context, dev.raw)
                    onPicked(dev)
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        }

        val cancel = TextView(context).apply {
            text = context.getString(android.R.string.cancel)
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), rect(0x0DFFFFFF, 16f, 0x26FFFFFF), null,
            )
            setOnClickListener { dialog.dismiss() }
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            addView(title)
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f) })
            addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        }
        dialog.setContentView(root)

        dialog.window?.apply {
            setBackgroundDrawable(
                rect(if (blurOk) 0xB8111827.toInt() else 0xEB111827.toInt(), 28f, 0x29FFFFFF),
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
            val w = context.resources.displayMetrics.widthPixels - dp(40f)
            val maxH = (context.resources.displayMetrics.heightPixels * 0.8f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            // Cap very long garages: the list scrolls inside the card.
            decorView.post { if (decorView.height > maxH) setLayout(w, maxH) }
        }

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
