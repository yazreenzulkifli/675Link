// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Saved settings for the (experimental) Perodua / Carbit USB car link. */
object UsbProbePrefs {
    private const val PREF = "usb_probe"
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun replyCnxn(ctx: Context) = prefs(ctx).getBoolean("reply_cnxn", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun setReplyCnxn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("reply_cnxn", on).apply()
    fun variant(ctx: Context) = prefs(ctx).getInt("variant", 0).coerceIn(0, AdbWire.VARIANTS.lastIndex)
    fun setVariant(ctx: Context, i: Int) = prefs(ctx).edit().putInt("variant", i).apply()
    fun bridge(ctx: Context) = prefs(ctx).getBoolean("bridge", true)
    fun setBridge(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("bridge", on).apply()
    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("auto_start", true)
    fun setAutoStart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_start", on).apply()
    fun autoCycle(ctx: Context) = prefs(ctx).getBoolean("auto_cycle", false)
    fun setAutoCycle(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_cycle", on).apply()
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

/**
 * EXPERIMENTAL screen for car head units that use the Carbit "Oversea" USB accessory (Perodua Smart
 * Link and similar). Android starts it when a USB host announces itself as manufacturer "Carbit" /
 * model "CarbitDriverOversea" / version "1" (see res/xml/carbit_accessory_filter.xml). The link
 * itself lives in [UsbCarLink]; this screen asks for USB permission, shows the log and offers
 * "Start Android Auto over USB" or the app's own test pattern.
 */
class UsbProbeActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var variantSpinner: android.widget.Spinner

    private var usb: UsbManager? = null
    private val actionPermission get() = "$packageName.USB_PROBE_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionPermission) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val acc = accessoryFrom(intent)
                UsbCarLink.log("permission ${if (granted) "granted" else "denied"}")
                if (granted && acc != null) openAndMaybeStart(acc)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        UsbCarLink.init(this)
        usb = getSystemService(Context.USB_SERVICE) as? UsbManager
        buildUi()
        val f = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
        // Replay what the on-disk log already holds (this screen may be re-created mid-session).
        logView.text = UsbCarLink.readLog(this).takeLast(20_000)
        UsbCarLink.listener = { msg ->
            ui.post {
                logView.append(msg + "\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        UsbCarLink.onChanged = { ui.post { refreshStatus() } }
        refreshStatus()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        UsbCarLink.listener = null
        UsbCarLink.onChanged = null
        // The link deliberately stays open: Android Auto may be running on it.
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
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
        fun sw(label: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
            text = label; isChecked = on; setOnCheckedChangeListener { _, v -> change(v) }
        }
        root.addView(text(getString(R.string.usb_probe_title), 20f, true))
        root.addView(text(getString(R.string.usb_probe_help), 13f).apply { setPadding(0, px(8), 0, px(8)) })

        root.addView(sw(getString(R.string.usb_probe_enable), UsbProbePrefs.enabled(this)) { on ->
            UsbProbePrefs.setEnabled(this, on)
            UsbCarLink.log(if (on) "probe enabled" else "probe disabled")
            if (!on) UsbCarLink.close()
        })
        root.addView(sw(getString(R.string.usb_probe_reply), UsbProbePrefs.replyCnxn(this)) {
            UsbProbePrefs.setReplyCnxn(this, it)
        })

        root.addView(text("Reply style (libjadbd guesses)", 13f, true).apply { setPadding(0, px(8), 0, 0) })
        variantSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@UsbProbeActivity, android.R.layout.simple_spinner_dropdown_item,
                AdbWire.VARIANTS.mapIndexed { i, v -> "${i + 1}/${AdbWire.VARIANTS.size}  ${v.label}" },
            )
            setSelection(UsbProbePrefs.variant(this@UsbProbeActivity))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    UsbProbePrefs.setVariant(this@UsbProbeActivity, pos)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        root.addView(variantSpinner)
        root.addView(sw("Bridge to OpenCfMoto (serve ports 10920-10922 to the car)", UsbProbePrefs.bridge(this)) {
            UsbProbePrefs.setBridge(this, it)
        })
        root.addView(sw("Auto-start Android Auto when the car connects", UsbProbePrefs.autoStart(this)) {
            UsbProbePrefs.setAutoStart(this, it)
        })
        root.addView(sw("Try the next style on every reconnect (probe only, bridge off)", UsbProbePrefs.autoCycle(this)) {
            UsbProbePrefs.setAutoCycle(this, it)
        })

        statusView = text(getString(R.string.usb_probe_status_idle), 14f, true).apply { setPadding(0, px(8), 0, px(8)) }
        root.addView(statusView)

        fun row(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((label, click) in buttons) {
                addView(Button(this@UsbProbeActivity).apply { text = label; setOnClickListener { click() } },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(row(
            "1. Get Android Auto ready (before Detect Phone)" to { startAndroidAuto() },
            "Test pattern only" to { UsbCarLink.startTestPattern(this) },
        ))
        root.addView(row(
            getString(R.string.usb_probe_check_now) to { checkNow() },
            getString(R.string.usb_probe_share) to { shareLog() },
            getString(R.string.usb_probe_clear) to {
                logView.text = ""; LogBus.clear(); UsbCarLink.clearLog(this); CrashGuard.clearCrash(this)
            },
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            gravity = Gravity.START
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refreshStatus() {
        statusView.text = getString(
            if (UsbCarLink.isOpen) R.string.usb_probe_status_open else R.string.usb_probe_status_idle,
        )
        variantSpinner.setSelection(UsbProbePrefs.variant(this))
    }

    private fun startAndroidAuto() {
        UsbCarLink.log(
            if (UsbCarLink.isOpen) "starting Android Auto over the USB link …"
            else "getting Android Auto ready before the car attaches …",
        )
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(UsbCar.EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun shareLog() {
        // Prefer sharing the log as a FILE (Drive / Files / mail attachment): long text gets cut off
        // by chat apps. Falls back to plain text below if there is nothing on disk yet.
        try {
            // The app-wide session log holds the PXC / media / Android Auto lines (negotiated screen
            // size, video, CLIENT_INFO) that the probe's own file does not.
            val sessionLog = java.io.File(java.io.File(cacheDir, "logs").also { it.mkdirs() }, "usb_session.log")
            try { sessionLog.writeText(LogBus.snapshot().takeLast(400_000)) } catch (_: Exception) {}
            val files = listOf(
                java.io.File(filesDir, "usb_probe.log"),
                sessionLog,
                CrashGuard.crashFile(this),
            ).filter { it.exists() && it.length() > 0L }
            if (files.isNotEmpty()) {
                val uris = ArrayList(files.map {
                    androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
                })
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("log", uris[0]).also { cd ->
                        for (i in 1 until uris.size) cd.addItem(android.content.ClipData.Item(uris[i]))
                    }
                }
                startActivity(Intent.createChooser(send, getString(R.string.usb_probe_share)))
                return
            }
        } catch (e: Exception) {
            UsbCarLink.log("share as file failed ($e) — sharing as text")
        }
        val text = StringBuilder(UsbCarLink.readLog(this).takeLast(200_000))
        CrashGuard.pendingCrashText(this)?.let {
            text.append("\n--- last crash report (files/last_crash.txt) ---\n").append(it.take(30_000))
        }
        if (text.isEmpty()) text.append(LogBus.snapshot().takeLast(200_000))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text.toString())
        }
        startActivity(Intent.createChooser(send, getString(R.string.usb_probe_share)))
    }

    // ------------------------------------------------------------------ USB

    private fun accessoryFrom(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private fun handleIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val acc = accessoryFrom(intent)
        if (acc == null) { UsbCarLink.log("attach intent without accessory"); return }
        UsbCarLink.log("USB accessory attached")
        if (!UsbProbePrefs.enabled(this)) {
            UsbCarLink.log("probe is OFF — turn it on above, then unplug and replug (or tap Check now)")
            return
        }
        openAndMaybeStart(acc)
    }

    /**
     * Open the link, then (if enabled) start Android Auto right away. Doing this while this screen
     * is in the foreground keeps Google AA's launch allowed on Android 12+, and the CNXN reply is
     * held (see [UsbCarLink.awaitingLocal]) until AA is ready, so the car never times out waiting.
     */
    private fun openAndMaybeStart(acc: UsbAccessory) {
        UsbCarLink.open(this, acc, usb)
        if (UsbCarLink.isOpen && UsbProbePrefs.autoStart(this) && UsbProbePrefs.bridge(this) &&
            UsbCarLink.claimAutoStart() && !UsbCarLink.isWarm()) startAndroidAuto()
    }

    private fun checkNow() {
        val list = usb?.accessoryList
        if (list == null || list.isEmpty()) {
            UsbCarLink.log("no USB accessory connected right now")
            return
        }
        UsbCarLink.log("${list.size} accessory connected")
        if (!UsbProbePrefs.enabled(this)) { UsbCarLink.log("probe is OFF — turn it on first"); return }
        val acc = list[0]
        if (usb?.hasPermission(acc) == true) {
            openAndMaybeStart(acc)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPermission).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            UsbCarLink.log("asking for USB permission…")
            usb?.requestPermission(acc, pi)
        }
    }
}
