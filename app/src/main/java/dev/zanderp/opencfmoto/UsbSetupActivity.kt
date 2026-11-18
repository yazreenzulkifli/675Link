// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Settings for the Perodua / Carbit USB car link ([UsbProbeActivity] keeps the status, the buttons and
 * the log). Every switch is saved straight away in [UsbProbePrefs]; the defaults suit most cars.
 */
class UsbSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.usb_setup_title)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(px(16), bars.top + px(16), px(16), bars.bottom + px(16))
            insets
        }
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun sw(label: String, hint: String, on: Boolean, change: (Boolean) -> Unit) {
            root.addView(Switch(this).apply {
                text = label; isChecked = on; setOnCheckedChangeListener { _, v -> change(v) }
                setPadding(0, px(10), 0, 0)
            })
            root.addView(text(hint, 12f).apply { setPadding(0, 0, 0, px(4)) })
        }

        root.addView(text(getString(R.string.usb_setup_title), 20f, true))
        root.addView(text(getString(R.string.usb_setup_help), 13f).apply { setPadding(0, px(8), 0, px(8)) })

        sw("Enable USB probe", "Watch for a Perodua / Carbit car and open the link when it is plugged in.",
            UsbProbePrefs.enabled(this)) { on ->
            UsbProbePrefs.setEnabled(this, on)
            UsbCarLink.log(if (on) "probe enabled" else "probe disabled")
            if (!on) UsbCarLink.close()
        }
        sw("Answer the car's handshake", "Reply to the car's ADB-style hello. Keep this on.",
            UsbProbePrefs.replyCnxn(this)) { UsbProbePrefs.setReplyCnxn(this, it) }
        sw("Bridge to OpenCfMoto", "Serve ports 10920-10922 to the car so Android Auto reaches its screen. Keep this on.",
            UsbProbePrefs.bridge(this)) { UsbProbePrefs.setBridge(this, it) }
        sw("Get Android Auto ready automatically", "Start Android Auto when the USB page opens and when the car connects.",
            UsbProbePrefs.autoStart(this)) { UsbProbePrefs.setAutoStart(this, it) }

        root.addView(text("Advanced", 14f, true).apply { setPadding(0, px(20), 0, 0) })
        root.addView(text("Reply style (libjadbd guesses). Style 1 works on the Perodua tested.", 12f))
        root.addView(Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@UsbSetupActivity, android.R.layout.simple_spinner_dropdown_item,
                AdbWire.VARIANTS.mapIndexed { i, v -> "${i + 1}/${AdbWire.VARIANTS.size}  ${v.label}" },
            )
            setSelection(UsbProbePrefs.variant(this@UsbSetupActivity))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    UsbProbePrefs.setVariant(this@UsbSetupActivity, pos)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })
        sw("Try the next style on every reconnect", "Only for protocol experiments (needs Bridge off). Leave off.",
            UsbProbePrefs.autoCycle(this)) { UsbProbePrefs.setAutoCycle(this, it) }

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }
}
