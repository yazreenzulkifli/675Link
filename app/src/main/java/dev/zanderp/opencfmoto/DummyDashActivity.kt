// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * The dummy bike's "dashboard": what the dash would show (decoded from the real PXC stream) plus
 * handlebar buttons that inject the same gestures as a real bike ([MediaButtonBridge]).
 * See [DummyBike]. Debug builds only (entry from Dash view).
 */
class DummyDashActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var powerBtn: MaterialButton
    private val statusListener: (DummyBike.Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101418"))
            addView(col)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        col.addView(TextView(this).apply {
            text = if (intent.getBooleanExtra("car", false)) "Dummy car" else "Dummy bike"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#C8D0D8"))
            setPadding(0, px(4), 0, px(12))
        }
        col.addView(statusView)

        // Fake dash panel, kept at the dash's 800:480 shape.
        val dashFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val dashSurface = SurfaceView(this)
        dashFrame.addView(dashSurface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dashFrame.addView(TextView(this).apply {
            text = "DASH"
            setTextColor(Color.parseColor("#33FFFFFF"))
            textSize = 12f
            setPadding(px(6), px(4), 0, 0)
        })
        col.addView(dashFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(160)))
        dashFrame.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val want = (r - l) * 480 / 800
            if (want > 0 && v.layoutParams.height != want) {
                v.layoutParams = v.layoutParams.apply { height = want }
            }
        }
        dashSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { DummyBike.setSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { DummyBike.setSurface(null) }
        })

        // Handlebar: same three semantic controls as every CFMoto pod.
        col.addView(sectionLabel("Handlebar (tap · double tap · hold)", px(16)))
        val pad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pad.addView(handlebarButton("◀", ButtonGesture.NAV_BACK, ButtonGesture.NAV_BACK_DOUBLE, ButtonGesture.NAV_BACK_LONG), weighted(px(4)))
        pad.addView(handlebarButton("OK", ButtonGesture.SELECT_PRESS, ButtonGesture.SELECT_DOUBLE, ButtonGesture.SELECT_LONG), weighted(px(4)))
        pad.addView(handlebarButton("▶", ButtonGesture.NAV_FWD, ButtonGesture.NAV_FWD_DOUBLE, ButtonGesture.NAV_FWD_LONG), weighted(px(4)))
        col.addView(pad)

        col.addView(sectionLabel("Bike", px(16)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(this).apply {
            text = "Use as my bike"
            setOnClickListener {
                DummyBike.selectAsBike(this@DummyDashActivity)
                DummyBike.ensureListening()
                Toast.makeText(context, "Dummy bike selected — now tap Connect or start a Map ride", Toast.LENGTH_LONG).show()
            }
        }, weighted(px(4)))
        powerBtn = MaterialButton(this).apply {
            setOnClickListener {
                if (DummyBike.status().listening) DummyBike.shutdown() else DummyBike.ensureListening()
            }
        }
        row.addView(powerBtn, weighted(px(4)))
        col.addView(row)

        col.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#8A96A3"))
            setPadding(0, px(16), 0, 0)
            text = "How to test:\n" +
                "1. Tap “Use as my bike”.\n" +
                "2. Go back and tap Connect (Android Auto), or Map → Free ride to the bike.\n" +
                "   The Wi‑Fi join is skipped; the app talks to this dummy over 127.0.0.1.\n" +
                "3. Come back here: the panel shows exactly what the dash would show.\n\n" +
                "The handlebar buttons use your Button mapping, like a real bike. " +
                "To go back to your real bike, pick it in the Garage."
        })
    }

    override fun onStart() {
        super.onStart()
        DummyBike.addListener(statusListener)
    }

    override fun onStop() {
        DummyBike.removeListener(statusListener)
        super.onStop()
    }

    private fun render(s: DummyBike.Status) {
        val video = if (s.videoW > 0) " · ${s.videoW}x${s.videoH}" else ""
        val frames = if (s.framesReceived > 0) "\nFrames: ${s.framesReceived} · ${s.fps} fps$video" else ""
        statusView.text = "${s.stage}$frames"
        powerBtn.text = if (s.listening) "Switch bike off" else "Switch bike on"
    }

    private fun sectionLabel(text: String, top: Int) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(Color.parseColor("#8A96A3"))
        setPadding(0, top, 0, top / 3)
    }

    private fun weighted(margin: Int) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = margin; marginEnd = margin
    }

    /** Tap / double tap / hold → the matching handlebar gesture (same windows as a real pod). */
    private fun handlebarButton(label: String, tap: ButtonGesture, double: ButtonGesture, hold: ButtonGesture) =
        MaterialButton(this).apply {
            text = label
            textSize = 20f
            minHeight = (56 * resources.displayMetrics.density).toInt()
            var pending: Runnable? = null
            setOnClickListener { v ->
                val p = pending
                if (p != null) {
                    v.removeCallbacks(p); pending = null; inject(double)
                } else {
                    val r = Runnable { pending = null; inject(tap) }
                    pending = r
                    v.postDelayed(r, 300L)
                }
            }
            setOnLongClickListener { v ->
                pending?.let { v.removeCallbacks(it) }; pending = null
                inject(hold); true
            }
        }

    private fun inject(g: ButtonGesture) {
        LogBus.log("[DUMMY] handlebar ${g.label}")
        if (MediaButtonBridge.instance != null) {
            MediaButtonBridge.injectGesture(g)
            return
        }
        // No AVRCP bridge yet: apply the mapping directly to whatever is projecting.
        when (val a = ButtonMap.get(this, g)) {
            ButtonAction.KNOB_FORWARD -> scroll(+1)
            ButtonAction.KNOB_BACK -> scroll(-1)
            ButtonAction.SELECT -> key(AaInput.KEY_ENTER)
            ButtonAction.BACK -> key(AaInput.KEY_BACK)
            ButtonAction.HOME -> key(AaInput.KEY_HOME)
            ButtonAction.ASSISTANT -> key(AaInput.KEY_ASSISTANT)
            ButtonAction.DPAD_UP -> key(AaInput.KEY_UP)
            ButtonAction.DPAD_DOWN -> key(AaInput.KEY_DOWN)
            ButtonAction.DPAD_LEFT -> key(AaInput.KEY_LEFT)
            ButtonAction.DPAD_RIGHT -> key(AaInput.KEY_RIGHT)
            ButtonAction.NONE -> {}
            else -> LogBus.log("[DUMMY] ${a.label} needs the media-button bridge (start a session first)")
        }
    }

    private fun key(code: Int) {
        (MapInputBridge.keySink ?: AaVideoBridge.keySink)?.invoke(code)
            ?: Toast.makeText(this, "Nothing is projecting to the dummy bike yet", Toast.LENGTH_SHORT).show()
    }

    private fun scroll(delta: Int) {
        (MapInputBridge.scrollSink ?: AaVideoBridge.scrollSink)?.invoke(delta)
            ?: Toast.makeText(this, "Nothing is projecting to the dummy bike yet", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, DummyDashActivity::class.java))
    }
}
