package dev.zanderp.opencfmoto

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Drop-in for MaterialAlertDialogBuilder / AlertDialog.Builder that gives every popup the same
 * Aero Glaze look as [HeadUnitDialog]: dark frosted card with a glass edge, blur behind (Android 12+),
 * cyan main button, glass Cancel button and the overshoot entrance. Colours, buttons and animation
 * come from ThemeOverlay.OpenCfMoto.GlassDialog; this adds the card surface.
 */
class GlassAlertBuilder(context: Context) :
    MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_OpenCfMoto_GlassDialog) {
    init {
        val d = context.resources.displayMetrics.density
        setBackground(ContextCompat.getDrawable(context, R.drawable.glass_dialog_bg))
        setBackgroundInsetStart((20 * d).toInt())
        setBackgroundInsetEnd((20 * d).toInt())
        setBackgroundInsetTop((24 * d).toInt())
        setBackgroundInsetBottom((24 * d).toInt())
    }
}
