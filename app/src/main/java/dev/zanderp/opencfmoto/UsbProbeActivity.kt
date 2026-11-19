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
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun replyCnxn(ctx: Context) = prefs(ctx).getBoolean("reply_cnxn", true)
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
        // Hands-free: keep watching for the car in the background, and get Android Auto ready as soon
        // as this screen opens so the car's ~20 s timer never waits for it.
        UsbCarLink.startWatching(this)
        handleIntent(intent)
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED &&
            UsbProbePrefs.enabled(this) && UsbProbePrefs.autoStart(this) && UsbProbePrefs.bridge(this) &&
            !UsbCarLink.isOpen && !UsbCarLink.isWarm() && !androidAutoAlreadyStarting()
        ) {
            ui.postDelayed({ if (!isFinishing) startAndroidAuto() }, 600)
        }
    }

    /** Android Auto is already coming up (or up): don't tear it down and start over. */
    private fun androidAutoAlreadyStarting(): Boolean {
        if (!AndroidAutoService.isRunning) return false
        val p = ConnectionState.phase
        return p != Phase.ERROR && p != Phase.STOPPED && p != Phase.IDLE
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
        root.addView(row(
            getString(R.string.usb_probe_settings) to { startActivity(Intent(this, UsbSetupActivity::class.java)) },
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
            when {
                UsbCarLink.isOpen -> R.string.usb_probe_status_open
                UsbCarLink.isReadyForCar() -> R.string.usb_probe_status_ready
                AndroidAutoService.isRunning -> R.string.usb_probe_status_getting_ready
                else -> R.string.usb_probe_status_idle
            },
        )
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (isFinishing) return
            refreshStatus()
            ui.postDelayed(this, 1_000L)
        }
    }

    override fun onResume() { super.onResume(); ui.post(statusTick) }
    override fun onPause() { ui.removeCallbacks(statusTick); super.onPause() }

    private fun startAndroidAuto() {
        UsbCarLink.log(
            if (UsbCarLink.isOpen) "starting Android Auto over the USB link …"
            else "getting Android Auto ready before the car attaches …",
        )
        // Headless: runs the start logic without opening the main screen, so this screen stays put.
        UsbCarStart.start(this)
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
