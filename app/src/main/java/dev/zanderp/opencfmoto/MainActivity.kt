// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: View
    private lateinit var tipsPanel: View
    private lateinit var statusView: TextView
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusProgress: View
    private lateinit var bikeView: TextView
    private lateinit var connectBtn: Button
    private lateinit var toggleLogBtn: Button
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** True when the pending QR scan should kick off the Android Auto flow (vs the mirror path). */
    private var pendingAaStart = false
    /** Guards the "close the official CFMoto app" prompt so it shows once per error, not every redraw. */
    private var rivalPromptShown = false
    /** Guards the VPN kill-switch prompt so it shows once per error, not every redraw. */
    private var vpnPromptShown = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        log(
            "QR shape: len=${raw.length} prefix='${raw.take(12)}' " +
                "hasSsid=${raw.contains("ssid=", true) || raw.startsWith("WIFI:", true)} " +
                "bytes=${raw.take(24).map { it.code }.joinToString(",")}"
        )
        val qr = QrData.parse(raw)
        if (qr == null) {
            val hint = QrData.parseFailureHint(raw)
            log("QR parse FAILED — missing ssid/pwd?" + (hint?.let { " ($it)" } ?: ""))
            Toast.makeText(
                this,
                hint ?: getString(R.string.main_invalid_qr),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}, hotspot=${qr.supportsPhoneHotspot}) " +
                "modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        BikeMemory.save(this, raw, qr)
        refreshBikeLabel()

        if (pendingAaStart) {
            pendingAaStart = false
            startAaFlow(qr)
        } else {
            // Mirror path (screen projection already armed): connect straight away.
            applyProfile(qr)
            MirrorOrientationLock.apply(this)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: qr.ssid)
            joinWifi(qr, gateOnAaSteady = false)
            // Same as startMirrorLink: single-app capture needs the shared app visible.
            moveTaskToBack(true)
            Toast.makeText(
                this,
                getString(R.string.main_mirror_leave_shared_app),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Pick the bike profile from the QR up front — it drives the Android Auto resolution/orientation,
     *  which must be set before AA starts. CLIENT_INFO refines it later during the PXC handshake. */
    private fun applyProfile(qr: QrData) = BikeProfileApply.apply(this, qr, ::log)

    /**
     * Stop the current dash projection so the next mode can open a clean PXC session.
     * Keeps bike Wi‑Fi (fast re-probe). Optionally preserves an armed Map session / mirror token.
     */
    private fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) {
        log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { log("prober stop: $e") }
        try {
            if (::prober.isInitialized && BikeLink.prober !== prober) prober.stop()
        } catch (_: Exception) {}
        if (clearMirror) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(this) } catch (_: Exception) {}
            MirrorOrientationLock.clear(this)
        }
        if (clearMap) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        BikeLink.beginHandoff(this)
    }

    /** Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect
     *  and a fresh scan, so both paths behave identically. */
    private fun startAaFlow(qr: QrData) {
        try {
            val dummy = DummyBike.isDummy(qr) || UsbCar.isUsb(qr)   // neither needs Wi‑Fi / Bluetooth
            if (!dummy && !WifiGate.ensureEnabledOrPrompt(this)) return
            // AA Connect needs Nearby + Bluetooth so HUR (START_WIRELESS_PROJECTION) can run when
            // WirelessStartupActivity is a no-op (common on AA 16.4+/17.4+). Mirror/Map unchanged.
            // The dummy bike (emulators, no BT) relies on the head-unit-server dial-out instead.
            if (!dummy && !ensureNearbyBtForAaConnect()) return
            if (!dummy) maybeWarnTftBluetoothAudio(qr)   // non-blocking: Connect carries on
            // Map / Mirror / stale PXC must die first — half-switches leave framesSent=0.
            tearDownForModeSwitch(clearMap = true, clearMirror = true)
            applyProfile(qr)
            BikeProfileHolder.carUsbTouch = UsbCar.isUsb(qr)
            val bikeName = BikeMemory.lastBikeName(this) ?: qr.ssid
            ConnectionState.set(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            // Parallel startup: the two slow steps (AA reaching steady video, and the user accepting the
            // bike Wi-Fi dialog) now overlap. [BikeLink] gates the actual bike probe until BOTH complete,
            // so the bike is never contacted before AA has frames to serve. These callbacks fire against
            // process-global state (applicationContext + BikeLink.prober), NOT this activity: launching
            // Google AA can destroy/recreate MainActivity mid-startup and the hand-off must still finish.
            BikeLink.beginHandoff(this)
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                AndroidAutoService.markDevModeSeen(applicationContext)
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                AndroidAutoService.upgradeForegroundForMicrophone()
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            // AA 16.4+/17.4+ often need the broadcast fallbacks; retry once if video never arrives
            // (common when the first WirelessStartupReceiver is ignored).
            logView.postDelayed({
                try {
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }, 900)
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if
            // a session is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            logView.postDelayed({
                if (aaAlreadyLiveOrTerminal()) {
                    if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) {
                        log("[AA] skip re-trigger — AA already connected/decoding")
                    }
                    return@postDelayed
                }
                try {
                    log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode re-trigger failed: $e")
                }
            }, 4_500)
            // Give up the silent black/QR reconnect loop — surface a clear error after several tries.
            logView.postDelayed({
                val p = ConnectionState.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
                // Still no AA after retries — stop thrashing (bike SoftAP may already be up).
                log("[AA] Android Auto never attached — stopping silent retry")
                val detail = if (AaVideoBridge.aaSessionSeen) {
                    getString(R.string.conn_detail_aa_dropped)
                } else {
                    getString(R.string.conn_detail_aa_not_started)
                }
                ConnectionState.set(Phase.ERROR, detail)
            }, 18_000)
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            joinWifi(qr, gateOnAaSteady = true)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            CrashGuard.persistSession(this)
            ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_connect_failed))
        }
    }

    private fun aaAlreadyLiveOrTerminal(): Boolean {
        val p = ConnectionState.phase
        return AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
            p == Phase.MIRRORING
    }

    /**
     * Block AA Connect until Nearby (API 31+) is granted and Bluetooth is on — otherwise HUR
     * never runs and Activity/Receiver alone often leave SoftAP up with no AA on the phone.
     */
    /**
     * "TFT on Wi‑Fi only": Android Auto reaches the dash over Wi‑Fi, so the dash needs no Bluetooth
     * audio. When it has it, it grabs music/Maps sound from the intercom and sets the phone's volume.
     * Apps can't change another device's Bluetooth profiles, so point the rider at the switch.
     * Android Auto's start still works with Bluetooth audio off — any paired device's MAC (the
     * intercom) is enough for START_WIRELESS_PROJECTION.
     */
    private fun maybeWarnTftBluetoothAudio(qr: QrData) {
        val prefs = getSharedPreferences("opencfmoto_home", MODE_PRIVATE)
        if (prefs.getBoolean("tft_bt_audio_tip_off", false)) return
        val names = listOfNotNull(qr.name, qr.ssid, BikeMemory.lastBikeName(this))
        BluetoothHelper.tftAudioConnections(this, names) { tfts ->
            runOnUiThread {
                if (tfts.isEmpty() || isFinishing || isDestroyed) return@runOnUiThread
                val tft = tfts.joinToString()
                log("→ TFT '$tft' is on Bluetooth audio — suggesting Wi‑Fi only (Media audio + Calls off)")
                GlassAlertBuilder(this)
                    .setTitle(R.string.tft_bt_audio_title)
                    .setMessage(getString(R.string.tft_bt_audio_message, tft))
                    .setPositiveButton(R.string.tft_bt_audio_open) { _, _ -> BluetoothHelper.openBluetoothSettings(this) }
                    .setNeutralButton(R.string.tft_bt_audio_never) { _, _ ->
                        prefs.edit().putBoolean("tft_bt_audio_tip_off", true).apply()
                    }
                    .setNegativeButton(R.string.tft_bt_audio_later, null)
                    .show()
            }
        }
    }

    private fun ensureNearbyBtForAaConnect(): Boolean {
        if (dev.zanderp.opencfmoto.aa.AaSelfMode.canAttemptHur(this)) return true
        val needNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needNearby) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
                REQ_BT_FOR_AA,
            )
            log("→ Nearby/Bluetooth required for AA Connect — waiting for grant + BT on")
        } else {
            log("→ Bluetooth is off — turn it on, then tap Connect again")
        }
        ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_need_bluetooth))
        showNearbyBtForAaDialog(needNearby)
        return false
    }

    private fun showNearbyBtForAaDialog(needNearby: Boolean) {
        if (isFinishing || isDestroyed) return
        try {
            val msg = if (needNearby) {
                getString(R.string.main_aa_need_nearby_message)
            } else {
                getString(R.string.main_aa_need_bt_on_message)
            }
            GlassAlertBuilder(this)
                .setTitle(R.string.main_aa_need_nearby_title)
                .setMessage(msg)
                .setPositiveButton(R.string.main_aa_need_nearby_settings) { _, _ ->
                    try {
                        if (needNearby) {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", packageName, null)),
                            )
                        } else {
                            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            log("Nearby/BT dialog failed: $e")
        }
    }

    /** After a fatal crash, tell the rider logs survived and keep Share Logs useful. */
    private fun maybeShowCrashRecovery() {
        if (CrashGuard.pendingCrashText(this) == null) return
        try {
            GlassAlertBuilder(this)
                .setTitle(R.string.main_crash_title)
                .setMessage(
                    "OpenCfMoto closed unexpectedly last time. The log and crash report were saved — " +
                        "open Logs and tap Share Logs so we can see what happened.",
                )
                .setPositiveButton("Share Logs") { _, _ -> shareLog() }
                .setNegativeButton("Keep logs") { _, _ -> }
                .setNeutralButton("Dismiss") { _, _ -> CrashGuard.clearCrash(this) }
                .show()
        } catch (e: Exception) {
            log("crash recovery UI failed: $e")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            MirrorOrientationLock.clear(this)
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        GpxSession.clear()
                        log("screen-capture armed (FGS up after ${tries * 100}ms) — pick Entire screen or a single app (Android 14+)")
                        GlassAlertBuilder(this@MainActivity)
                            .setTitle(R.string.main_mirror_ready_title)
                            .setMessage(
                                "Entire screen — best for riding. " +
                                    "Uses Setup ▸ Screen fit and Setup ▸ Mirror orientation " +
                                    "(Match dash rotates the phone so the TFT fills).\n\n" +
                                    "Single app — that app must stay on screen; Android sends no frames " +
                                    "in the background. Prefer GPX / Tracks or Android Auto for pocket use.\n\n" +
                                    "Bike touch does not drive mirrored apps. Continue connects and " +
                                    "sends OpenCfMoto to the background.",
                            )
                            .setPositiveButton("Continue") { _, _ -> startMirrorLink() }
                            .setCancelable(false)
                            .show()
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                        MirrorOrientationLock.clear(this@MainActivity)
                    }
                } else if (tries++ < maxTries) {
                    logView.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                    MirrorOrientationLock.clear(this@MainActivity)
                }
            }
        }
        logView.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyToHolder(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        window.decorView.post { if (!isFinishing) FollowDialog.showOnce(this) }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logPanel = findViewById(R.id.log_panel)
        tipsPanel = findViewById(R.id.tips_panel)
        statusView = findViewById(R.id.status_view)
        statusIcon = findViewById(R.id.status_icon)
        statusProgress = findViewById(R.id.status_progress)
        bikeView = findViewById(R.id.bike_view)
        connectBtn = findViewById(R.id.btn_connect)
        findViewById<android.widget.SeekBar>(R.id.home_seek_volume)?.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    findViewById<TextView>(R.id.home_volume_value)?.text = progress.toString()
                    if (fromUser) MediaButtonBridge.setVolume(this@MainActivity, progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
            },
        )
        toggleLogBtn = findViewById(R.id.btn_toggle_log)
        logView.movementMethod = ScrollingMovementMethod()

        // Icons are set here rather than in XML: in this AGP/compileSdk setup, library (res-auto)
        // attributes like app:icon don't resolve in layouts, so we assign them programmatically.
        (connectBtn as? MaterialButton)?.setIconResource(R.drawable.ic_cast)
        // Scan / Map / Mirror: icon on top (Tile style). maxLines=1 + autoSize keeps labels
        // horizontal on narrow phones / large system font (Samsung S22 report).
        fun MaterialButton.asIconTopTile(iconRes: Int) {
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = resources.getDimensionPixelSize(R.dimen.btn_tile_icon_padding)
            maxLines = 1
            isSingleLine = true
        }
        // Bottom dock: Dash view / Controls / Map / Mirror, icon above label.
        (findViewById<View>(R.id.btn_hud_view) as? MaterialButton)?.asIconTopTile(R.drawable.ic_speed)
        (findViewById<View>(R.id.btn_devices) as? MaterialButton)?.asIconTopTile(R.drawable.ic_devices)
        (findViewById<View>(R.id.btn_gpx) as? MaterialButton)?.asIconTopTile(R.drawable.ic_place)
        (findViewById<View>(R.id.btn_mirror_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_cast)
        (findViewById<View>(R.id.btn_usb_car) as? MaterialButton)?.asIconTopTile(R.drawable.ic_usb)
        (findViewById<View>(R.id.btn_aa_stop) as? MaterialButton)?.setIconResource(R.drawable.ic_stop)
        // Pair TFT card "+ Scan QR" and field-guide utility buttons.
        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.isSingleLine = true
        (findViewById<View>(R.id.btn_about_page) as? MaterialButton)?.setIconResource(R.drawable.ic_info)
        (findViewById<View>(R.id.btn_check_update) as? MaterialButton)?.setIconResource(R.drawable.ic_sync)
        (findViewById<View>(R.id.btn_revamp_yz) as? MaterialButton)?.setIconResource(R.drawable.ic_about_heart)
        (findViewById<View>(R.id.btn_donate) as? MaterialButton)?.setIconResource(R.drawable.ic_about_bolt)
        // Light home screen → dark status / nav bar icons.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        findViewById<View>(R.id.btn_header_settings).setOnClickListener { SetupActivity.start(this) }
        findViewById<View>(R.id.btn_header_about).setOnClickListener { AboutActivity.start(this) }
        // Field guide is collapsed by default; "Quick help" expands it.
        findViewById<TextView>(R.id.home_guide_toggle).setOnClickListener { toggle ->
            val steps = findViewById<View>(R.id.home_guide_steps)
            val show = steps.visibility != View.VISIBLE
            steps.visibility = if (show) View.VISIBLE else View.GONE
            (toggle as TextView).setText(if (show) R.string.home_guide_hide else R.string.home_guide_help)
        }
        // Quick utility pills — icon + label.
        fun MaterialButton.asFooterLink(iconRes: Int) {
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (5 * resources.displayMetrics.density).toInt()
            iconSize = (16 * resources.displayMetrics.density).toInt()
            setIconTintResource(R.color.home_cyan)
            maxLines = 1
            isSingleLine = true
        }
        (findViewById<View>(R.id.btn_controls) as? MaterialButton)?.asFooterLink(R.drawable.ic_devices)
        (findViewById<View>(R.id.btn_trip) as? MaterialButton)?.asFooterLink(R.drawable.ic_ride)
        (toggleLogBtn as? MaterialButton)?.asFooterLink(R.drawable.ic_logs)

        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        // Application already hydrated a prior session/crash into LogBus — show it in the view.
        val prior = LogBus.snapshot()
        if (prior.isNotBlank()) {
            logView.text = prior
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        maybeShowCrashRecovery()
        // Soft dep check after auto-connect has had time to start — never race Connect.
        logView.postDelayed({ DependencyPrompt.showOnLaunchIfNeeded(this) }, 2500)
        // Opening the home screen must not resume a killed NAV_TO (looks like "Arrived at X").
        val startingGpx = intent?.getBooleanExtra(EXTRA_START_GPX, false) == true
        val projecting = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        if (!startingGpx && !projecting) {
            GpxSession.abandonStaleNavigation("app open")
        }
        if (startingGpx) {
            intent.removeExtra(EXTRA_START_GPX)
            logView.post { beginGpxProjection() }
        }

        // Reflect the coarse connection state in the big status header (so users don't read the log).
        ConnectionState.listener = { phase, detail ->
            runOnUiThread { renderStatus(phase, detail) }
        }
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        refreshBikeLabel()

        // Reuse the process-global prober if one already exists (e.g. this activity was recreated
        // while the Android Auto receiver kept running in the foreground service). Constructing a
        // fresh one here would orphan the running instance — leaking its sockets/threads and making
        // the Stop button operate on the wrong object. See [BikeLink].
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }
        // Launched from the USB probe screen: start Android Auto over the open USB car link.
        logView.post { maybeStartFromUsbCar(intent) }

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Car page: two steps. 1 = get Android Auto ready (Stop while it runs), 2 = connect to the car.
        findViewById<Button>(R.id.btn_car_ready)?.setOnClickListener { onCarReadyPressed() }

        // Connect while idle; Stop while busy / projecting (merged former Stop button).
        connectBtn.setOnClickListener {
            if (heroIsCar()) { onCarConnectPressed(); return@setOnClickListener }
            val phase = ConnectionState.phase
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                stopEverything()
                return@setOnClickListener
            }
            if (DependencyPrompt.showForConnect(this, forScan = false)) {
                log("→ Connect blocked — missing dependencies (see dialog)")
                return@setOnClickListener
            }
            // Not ready: open the guide for the first red step (1 Permission, 2 Dev mode, 3 Head unit).
            if (!permOk) {
                showPermissionGuide()
                return@setOnClickListener
            }
            if (!devOn) {
                showDevModeGuide()
                return@setOnClickListener
            }
            if (!headUnitServerUp) {
                showHeadUnitGuide()
                return@setOnClickListener
            }
            if (heroIsCar()) {
                // Car box: start the watcher first so the log says "watching for the car — plug in
                // the USB cable any time", then carry on getting Android Auto ready.
                UsbCar.ensureSelected(this)
                UsbCarLink.ensureWatching(this)
                Toast.makeText(this, R.string.pick_target_car_watching, Toast.LENGTH_LONG).show()
                doConnect()
            } else if (BikeMemory.devices(this).none { !UsbCar.isUsb(it.qr) }) {
                // Bike box but no bike saved yet: scan the dash QR.
                startAaScan()
            } else {
                // Bike box: with several bikes saved, ask which one.
                ConnectTargetDialog.show(this, onlyBikes = true) { picked ->
                    val bike = picked ?: BikeMemory.devices(this).firstOrNull { !UsbCar.isUsb(it.qr) }
                    bike?.let { BikeMemory.select(this, it.raw) }
                    refreshBikeLabel()
                    refreshReady()
                    doConnect()
                }
            }
        }

        findViewById<Button>(R.id.btn_aa_start).setOnClickListener { startAaScan() }

        findViewById<Button>(R.id.btn_mirror_start).setOnClickListener { onMirrorPressed() }
        // USB car mode (Perodua / Carbit head units): opens the USB page, which gets Android Auto ready.
        findViewById<View>(R.id.btn_usb_car).setOnClickListener {
            startActivity(Intent(this, UsbProbeActivity::class.java))
        }
        // Tap → Google Maps (what Android Auto shows on the dash); long-press → OpenCfMoto's own map hub.
        findViewById<View>(R.id.btn_gpx).setOnClickListener {
            log("→ Map: open ${NavLauncher.currentAppName(this)}")
            if (!NavLauncher.openMaps(this)) onMapPressed()
        }
        findViewById<View>(R.id.btn_gpx).setOnLongClickListener {
            onMapPressed()
            true
        }
        // Pull down on the home screen to refresh the status cards (head unit, destination, permissions).
        findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.home_refresh)?.apply {
            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.home_cyan))
            setOnRefreshListener {
                refreshBikeLabel()
                probeHeadUnitServer()
                renderHome(ConnectionState.phase)
                // Give the head unit probe a moment, then re-draw with its result.
                postDelayed({
                    if (!isFinishing) {
                        renderHome(ConnectionState.phase)
                        isRefreshing = false
                    }
                }, 700)
            }
        }
        findViewById<View>(R.id.tel_perm_card)?.apply {
            isClickable = true
            setOnClickListener { showPermissionGuide() }
        }
        // Destination card: tap → Google Maps to pick a destination; long-press → our own map hub.
        findViewById<View>(R.id.tel_dest_card)?.apply {
            isClickable = true
            setOnClickListener {
                if (heroIsCar()) {
                    // Can't toggle it from an app — open Developer options where USB debugging lives.
                    val devOn = android.provider.Settings.Global.getInt(
                        contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 ||
                        android.provider.Settings.Global.getInt(
                            contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
                    val tries = if (devOn) listOf(
                        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
                        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        Intent(android.provider.Settings.ACTION_SETTINGS),
                    ) else {
                        Toast.makeText(this@MainActivity, "Turn on Developer options first: tap Build number 7 times", Toast.LENGTH_LONG).show()
                        listOf(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS))
                    }
                    for (i in tries) { try { startActivity(i); break } catch (_: Exception) { } }
                } else {
                    log("→ Destination card: open ${NavLauncher.currentAppName(this@MainActivity)}")
                    if (!NavLauncher.openMaps(this@MainActivity)) onMapPressed()
                }
            }
            setOnLongClickListener {
                if (!heroIsCar()) onMapPressed()
                true
            }
        }
        findViewById<Button>(R.id.btn_aa_stop).setOnClickListener { stopEverything() }

        // Tapping the status pill on the hero opens the log drawer and scrolls straight to it.
        (statusView.parent as? View)?.setOnClickListener {
            if (logPanel.visibility != View.VISIBLE) toggleLogBtn.performClick()
            findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll).postDelayed({
                val sv = findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll)
                val r = android.graphics.Rect(0, 0, logPanel.width, logPanel.height)
                sv.offsetDescendantRectToMyCoords(logPanel, r)
                sv.smoothScrollTo(0, (r.top - 16).coerceAtLeast(0))
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }, 120)
        }
        toggleLogBtn.setOnClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            // The tips panel and the log panel share the flexible space, so only one shows at a time.
            tipsPanel.visibility = if (show) View.GONE else View.VISIBLE
            toggleLogBtn.text = if (show) getString(R.string.main_hide_logs) else getString(R.string.main_logs)
        }

        findViewById<View>(R.id.btn_hud_view).setOnClickListener { startActivity(Intent(this, HudViewActivity::class.java)) }
        findViewById<View>(R.id.btn_controls).setOnClickListener { startActivity(Intent(this, ControlsActivity::class.java)) }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.home_switch_free_volume)?.apply {
            isChecked = ButtonPresencePrefs.freeVolume(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                ButtonPresencePrefs.setFreeVolume(this@MainActivity, on)
                // Apply now if the bridge is running: unpin (on) or re-pin (off).
                MediaButtonBridge.instance?.refreshVolumePresencePolicy()
            }
        }
        findViewById<View>(R.id.btn_open_aa)?.setOnClickListener { openAndroidAuto() }
        // Head unit card: tap → Android Auto (⋮ → Start/Stop head unit server lives there).
        findViewById<View>(R.id.tel_unit_card)?.apply {
            isClickable = true
            // Red → the Start guide Connect shows; green → a Stop guide.
            setOnClickListener {
                if (headUnitServerUp && AaVideoBridge.headUnitStuck) {
                    HeadUnitDialog.showRestart(this@MainActivity) { openAndroidAuto() }
                } else if (headUnitServerUp) {
                    HeadUnitDialog.showStop(this@MainActivity) { openAndroidAuto() }
                } else {
                    showHeadUnitGuide()
                }
            }
        }
        // Setup order, top-left → bottom-right: 1 Permission, 2 Dev mode, 3 Head unit, 4 Destination.
        listOf(
            R.id.tel_perm_label to R.string.home_tel_perm,
            R.id.tel_dev_label to R.string.home_tel_dev,
            R.id.tel_unit_label to R.string.home_tel_unit,
            R.id.tel_dest_label to R.string.home_tel_dest,
        ).forEachIndexed { i, (id, res) ->
            findViewById<TextView>(id)?.text = "${i + 1}  ${getString(res)}"
        }
        findViewById<View>(R.id.tel_dev_card)?.apply {
            isClickable = true
            setOnClickListener { showDevModeGuide() }
        }
        // Search bar button (and keyboard Go) → Google Maps search with what was typed.
        findViewById<Button>(R.id.btn_navigate).setOnClickListener { searchTypedInMaps() }
        val destField = findViewById<android.widget.EditText>(R.id.et_destination)
        destField?.setOnEditorActionListener { _, _, _ ->
            searchTypedInMaps(); true
        }
        // Bike Search tap → focus this field + show the phone keyboard (Presentation has no IME).
        DashRemote.setTypeOnPhoneHandler {
            runOnUiThread {
                val field = findViewById<android.widget.EditText>(R.id.et_destination)
                    ?: return@runOnUiThread
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                            ),
                    )
                } catch (_: Exception) {}
                // Make the phone field scream "this is the bike search box".
                field.hint = getString(R.string.main_bike_search_hint)
                field.setText("")
                field.setSelection(0)
                try {
                    field.setBackgroundColor(0x33FF9800)
                    field.postDelayed({
                        try { field.background = null } catch (_: Exception) {}
                    }, 2500)
                } catch (_: Exception) {}
                field.requestFocus()
                field.post {
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(
                        field,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    )
                }
                try {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                        ?.vibrate(40)
                } catch (_: Exception) {}
                Toast.makeText(
                    this,
                    "Bike search open — type here. Results appear on the dash.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Live suggestions on the dash while typing on the phone.
        destField?.addTextChangedListener(object : android.text.TextWatcher {
            private val debounce = android.os.Handler(android.os.Looper.getMainLooper())
            private val run = Runnable {
                val q = destField.text?.toString()?.trim().orEmpty()
                // Live suggestions only while OUR map is on the bike — not while Android Auto is live.
                if (q.length >= 2 && GpxSession.active && DashRemote.isAvailable) DashRemote.submit(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                debounce.removeCallbacks(run)
                debounce.postDelayed(run, 350)
            }
        })
        findViewById<View>(R.id.btn_devices).setOnClickListener { GarageActivity.start(this) }

        findViewById<View>(R.id.btn_trip).setOnClickListener { TripsListActivity.start(this) }

        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        setupHeroPager()
        findViewById<android.widget.ImageView>(R.id.brand_logo).apply {
            setOnClickListener { GarageActivity.start(this@MainActivity) }
            try {
                val drawable = android.graphics.ImageDecoder.decodeDrawable(
                    android.graphics.ImageDecoder.createSource(resources, R.raw.home_logo_675),
                )
                setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.apply {
                    repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            } catch (e: Exception) {
                log("logo gif failed: $e")
            }
        }
        findViewById<View>(R.id.btn_about_page).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.btn_check_update).setOnClickListener { checkUpdateManual() }
        findViewById<View>(R.id.btn_revamp_yz).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@yzrn94")))
            } catch (_: Exception) {
            }
        }
        findViewById<View>(R.id.btn_donate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.URL_KOFI)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.main_donate_failed, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            CrashGuard.clearSession(this)
            logView.text = ""
            LogBus.logSessionBanner()
        }

        log("Ready. Tap Connect to project Android Auto to your dash.")

        // Always open on the home screen — Setup is reachable from the Setup button / ⚙ gear, and the
        // Connect flow still prompts for anything missing. Mark it seen so auto-connect isn't held back.
        try {
            if (!SetupActivity.hasSeen(this)) SetupActivity.markSeen(this)
            maybeAutoConnect()
            maybeResumeFromParked(intent)
            maybeStartFromBtTrigger(intent)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            CrashGuard.persistSession(this)
        }
    }

    /** Live trip stats → hero speed page while the home screen is in front. */
    private val heroStatsListener: (TripRecorder.Stats) -> Unit = { s -> runOnUiThread { renderHeroSpeed(s) } }

    override fun onPause() {
        CrashGuard.persistSession(this)
        TripLogger.get(this).let { if (it.onStats === heroStatsListener) it.onStats = null }
        logView.removeCallbacks(carStatusTick)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeResumeFromParked(intent)
        maybeStartFromBtTrigger(intent)
        maybeStartFromUsbCar(intent)
        if (intent.getBooleanExtra(EXTRA_START_GPX, false)) {
            intent.removeExtra(EXTRA_START_GPX)
            beginGpxProjection()
        }
    }

    /** Re-render the Destination card the moment Google Maps starts/stops navigating. */
    private val gmapsListener: (GmapsNav.Turn?) -> Unit = {
        runOnUiThread { if (!isFinishing) renderHome(ConnectionState.phase) }
    }

    private val aaNavListener: () -> Unit = {
        runOnUiThread { if (!isFinishing) renderHome(ConnectionState.phase) }
    }

    override fun onStart() {
        super.onStart()
        GmapsNav.addListener(gmapsListener)
        dev.zanderp.opencfmoto.aa.AaNavState.addListener(aaNavListener)
    }

    override fun onStop() {
        GmapsNav.removeListener(gmapsListener)
        dev.zanderp.opencfmoto.aa.AaNavState.removeListener(aaNavListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The active bike (and its name) may have changed in the Garage — reflect it on the label and
        // the Connect button, and move the hero to the matching box.
        BikeMemory.selected(this)?.raw.let { cur -> if (cur != lastSelRaw) { lastSelRaw = cur; syncHeroPage() } }
        refreshBikeLabel()
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        TripLogger.get(this).let { it.onStats = heroStatsListener; renderHeroSpeed(it.snapshot()) }
        logView.removeCallbacks(homeTick)
        logView.postDelayed(homeTick, 30_000L)
        logView.removeCallbacks(carStatusTick)
        logView.post(carStatusTick)
        if (WifiGate.isWifiEnabled(this)) WifiGate.cancelNotification(this)
        // Retry auto-connect on resume: after finishing first-run setup, or once the bike's Wi-Fi
        // comes into range shortly after launch. Guarded so it only ever starts one attempt.
        if (SetupActivity.hasSeen(this)) maybeAutoConnect()
        maybeCheckUpdate()
        maybeShowHeadUnitStopFromReminder(intent)
        maybeResumeFromParked(intent)
        resumeConnectIfHeadUnitUp()
    }

    /** Ride-finished reminder tapped → show the "Stop head unit server" guide (once per tap). */
    private fun maybeShowHeadUnitStopFromReminder(intent: Intent?) {
        if (intent?.getBooleanExtra(AndroidAutoService.EXTRA_SHOW_HU_STOP, false) != true) return
        if (intent.getBooleanExtra(EXTRA_HU_STOP_SHOWN, false)) return
        intent.putExtra(EXTRA_HU_STOP_SHOWN, true)
        log("→ Ride-finished reminder tapped: showing how to stop the head unit server")
        logView.post { if (!isFinishing) HeadUnitDialog.showStop(this) { openAndroidAuto() } }
    }

    /**
     * Guaranteed, BAL-safe resume after the service parked Android Auto (long bike outage). Re-launching
     * Google AA needs a foreground Activity, so when the rider taps the "Bike reconnected" notification
     * (or just opens the app while parked and the bike looks in range) we finish the resume here — this
     * `startActivity(gearhead)` is allowed because we're in the foreground.
     */
    private fun maybeResumeFromParked(intent: Intent?) {
        // Opened from the ride-finished reminder: the rider is done riding — never auto-resume here.
        if (intent?.getBooleanExtra(AndroidAutoService.EXTRA_SHOW_HU_STOP, false) == true) return
        val explicit = intent?.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false) == true
        if (!explicit && !AndroidAutoService.isParked && ConnectionState.phase != Phase.WAITING_FOR_BIKE) return
        val saved = BikeMemory.lastQr(this) ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && BikeWifi.isSsidInRange(this, saved.ssid) == false) return
        intent?.removeExtra(AndroidAutoService.EXTRA_RESUME)
        log("→ Resuming projection to '${BikeMemory.lastBikeName(this)}' from the foreground")
        autoConnectStarted = true
        AndroidAutoService.notifyForegroundResuming()
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /** USB car (Perodua etc.): the probe screen asked us to start Android Auto over its USB link. */
    private fun maybeStartFromUsbCar(intent: Intent?) {
        if (intent?.getBooleanExtra(UsbCar.EXTRA_START, false) != true) return
        intent.removeExtra(UsbCar.EXTRA_START)
        val backToProbe = intent.getBooleanExtra(UsbCar.EXTRA_BACK_TO_PROBE, false)
        intent.removeExtra(UsbCar.EXTRA_BACK_TO_PROBE)
        if (backToProbe) {
            // Started from the USB probe screen: give it back once Android Auto is on its way, so
            // the rider isn't left on the home screen.
            logView.postDelayed({
                try {
                    startActivity(
                        Intent(this, UsbProbeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
                    )
                } catch (_: Exception) {}
            }, 600L)
        }
        if (!UsbCarLink.isOpen) {
            log("→ USB car: no USB link yet — getting Android Auto ready first; the car link attaches when you press Detect Phone")
        }
        val qr = UsbCar.ensureSelected(this) ?: return
        log("→ USB car: starting Android Auto over the USB link")
        autoConnectStarted = true
        ProjectionHolder.projection = null
        refreshBikeLabel()
        startAaFlow(qr)
    }

    /** Helmet remote / watch ACL → same one-tap Connect as a saved-QR tap. */
    private fun maybeStartFromBtTrigger(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BT_TRIGGER, false) != true) return
        intent.removeExtra(EXTRA_BT_TRIGGER)
        if (AndroidAutoService.isRunning) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        ) return
        val saved = BikeMemory.lastQr(this) ?: return
        log("→ Bluetooth trigger: starting Connect for '${BikeMemory.lastBikeName(this)}'")
        autoConnectStarted = true
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /**
     * Auto-connect on launch: if the rider left the feature on, a bike is paired, Android Auto is
     * installed, nothing is already running, and the bike's Wi-Fi looks in range, kick off the same
     * one-tap Connect flow automatically. Fires at most once per process ([autoConnectStarted]) — but
     * only that success latches it, so a "not in range yet" launch is retried from [onResume] once the
     * bike's Wi-Fi appears. Never interrupts an active session or the setup wizard.
     */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!AppSettings.autoConnect(this)) return
        if (AndroidAutoService.isRunning) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING) return
        val saved = BikeMemory.lastQr(this)
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!WifiGate.isWifiEnabled(this)) {
            // Don't latch autoConnectStarted — retry once the rider turns Wi‑Fi back on.
            WifiGate.ensureEnabledOrPrompt(this)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }

        val inRange = BikeWifi.isSsidInRange(this, saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${BikeMemory.lastBikeName(this)}' not in range — will retry when its Wi-Fi appears")
            return
        }

        // Committed to connecting — latch so an activity recreation (Google AA foregrounding) or a
        // later onResume doesn't fire a second attempt. Use the main looper (not the view) so a
        // dependency dialog can't cancel the delayed start with the view.
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${BikeMemory.lastBikeName(this)}' ($why). Disable in Setup ▸ Startup.")
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        val activity = this
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    // Recreated before we fired — let the new instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (!AndroidAutoService.isRunning && !ConnectionState.phase.busy) {
                    activity.startAaFlow(saved)
                }
            } catch (e: Exception) {
                LogBus.log("auto-connect failed (app stays open): $e")
                CrashGuard.persistSession(activity)
                ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_auto_connect_failed))
            }
        }, 1200)
    }

    /** Log an auto-connect skip reason at most once per process, so onResume retries don't spam. */
    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    override fun onDestroy() {
        heroPulse?.cancel()
        heroPulse = null
        DashRemote.setTypeOnPhoneHandler(null)
        LogBus.listener = null
        ConnectionState.listener = null
        // When the Android Auto receiver service is running, the whole AA→bike chain (receiver +
        // encoder in the FGS, plus the Wi-Fi + prober in process globals) must OUTLIVE this activity:
        // launching Google Android Auto can destroy/recreate MainActivity mid-hand-off, and tearing
        // the bike down here is exactly what left the dash on a black screen (the pending
        // onSteadyVideo hand-off was cancelled before it could fire). Only tear down when AA is NOT
        // running — i.e. the mirror path or a genuine exit. Full teardown is the "Stop" button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
            // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
            BikeWifi.leave(this, ::log)
            BikeWifiP2p.stop(::log)
        }
        super.onDestroy()
    }

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    /** Set when we sent the rider to Android Auto to start the head unit server; Connect resumes on return. */
    private var awaitingHeadUnit = false

    /** Car page: Android Auto is up and the servers are listening, only the car is missing. */
    private fun carStandby(): Boolean =
        heroIsCar() && UsbCarLink.isReadyForCar() && !UsbCarLink.isOpen &&
            ConnectionState.phase != Phase.ERROR

    private var carStandbyShown = false

    private fun carBusy(): Boolean {
        val p = ConnectionState.phase
        return p.busy || p == Phase.STREAMING || p == Phase.MIRRORING
    }

    /** Same setup checks the old single Connect button ran; false = a dialog was shown. */
    private fun carGuardsOk(): Boolean {
        if (DependencyPrompt.showForConnect(this, forScan = false)) {
            log("→ Connect blocked — missing dependencies (see dialog)")
            return false
        }
        if (!permOk) { showPermissionGuide(); return false }
        if (!devOn) { showDevModeGuide(); return false }
        if (!headUnitServerUp) { showHeadUnitGuide(); return false }
        return true
    }

    /** Car step 1: get Android Auto ready (Stop while it is running). */
    private fun onCarReadyPressed() {
        if (carBusy()) { stopEverything(); return }
        if (!carGuardsOk()) return
        UsbCar.ensureSelected(this)
        UsbCarLink.ensureWatching(this)
        Toast.makeText(this, R.string.pick_target_car_watching, Toast.LENGTH_LONG).show()
        doConnect()
    }

    /** Car step 2: connect to the car (the watcher opens the USB link when the car attaches). */
    private fun onCarConnectPressed() {
        if (!carGuardsOk()) return
        UsbCar.ensureSelected(this)
        UsbCarLink.ensureWatching(this)
        when {
            UsbCarLink.isOpen -> Toast.makeText(this, R.string.car_step2_sub_connected, Toast.LENGTH_SHORT).show()
            !carBusy() -> {
                // Step 1 was skipped: do it now so the car has something to connect to.
                Toast.makeText(this, R.string.pick_target_car_watching, Toast.LENGTH_LONG).show()
                doConnect()
            }
            else -> Toast.makeText(this, R.string.car_step2_sub_ready, Toast.LENGTH_LONG).show()
        }
    }

    private fun doConnect() {
        val saved = BikeMemory.lastQr(this)
        if (saved != null) {
            log("→ Connect: reusing saved bike '${BikeMemory.lastBikeName(this)}' (no scan needed)")
            ProjectionHolder.projection = null
            GpxSession.clear()
            ensureLocationPermission()
            startAaFlow(saved)
        } else {
            log("→ Connect: no saved bike — scan the dash QR.")
            startAaScan()
        }
    }

    /** Back from Android Auto: if the head unit server is now listening, carry on with Connect. */
    private fun resumeConnectIfHeadUnitUp() {
        if (!awaitingHeadUnit) return
        awaitingHeadUnit = false
        Thread {
            val up = try {
                java.net.ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(java.net.InetSocketAddress("127.0.0.1", dev.zanderp.opencfmoto.aa.AaReceiver.HEADUNIT_SERVER_PORT))
                }
                false
            } catch (_: java.net.BindException) {
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (up) {
                    headUnitServerUp = true
                    doConnect()
                } else {
                    Toast.makeText(this, R.string.home_hus_still_off, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startAaScan() {
        if (DependencyPrompt.showForConnect(this, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        pendingAaStart = true
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        try {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } catch (e: Exception) {
            log("scan launch failed ($e)")
            pendingAaStart = false
        }
    }

    /** AA 17.4 HUS banner — riders see it often; don't flood telemetry. */
    private fun isHusConnectNoise(detail: String): Boolean {
        if (detail == getString(R.string.conn_detail_aa_not_started)) return true
        val d = detail.lowercase()
        return d.contains("head unit server") || d.contains("start head unit")
    }

    /** Update the big status header + Connect button label from a [ConnectionState] transition. */
    private fun renderStatus(phase: Phase, detail: String) {
        statusView.text = getString(phase.labelRes)
        bikeView.text = if (detail.isNotBlank()) detail else bikeLabelText()
        if (phase == Phase.ERROR && detail.isNotBlank() && !isHusConnectNoise(detail)) {
            try {
                AnonymousTelemetry.reportError(this, detail)
            } catch (_: Exception) {
            }
        }
        val color = when (phase) {
            Phase.STREAMING, Phase.MIRRORING -> ContextCompat.getColor(this, R.color.status_live)
            Phase.ERROR -> ContextCompat.getColor(this, R.color.status_error)
            else -> if (phase.busy) ContextCompat.getColor(this, R.color.status_busy)
                    else ContextCompat.getColor(this, R.color.status_idle)
        }
        statusView.setTextColor(ContextCompat.getColor(this, R.color.home_text))
        statusIcon.setColorFilter(color)
        statusProgress.visibility = if (phase.busy) View.VISIBLE else View.GONE

        // The bike's link ports are held by the official CFMoto app — offer to close it (see
        // EasyConnProber's bind-conflict path). Show once per error so we don't nag on every redraw.
        if (phase == Phase.ERROR && detail.contains("CFMoto app", ignoreCase = true)) {
            if (!rivalPromptShown) { rivalPromptShown = true; promptCloseRival() }
        } else {
            rivalPromptShown = false
        }
        // Only a confirmed kill-switch with a live internet VPN — Map↔AA blips can leave a stale
        // "kill-switch" detail without an actual VPN; don't nag in that case.
        if (phase == Phase.ERROR &&
            detail.contains("kill-switch", ignoreCase = true) &&
            BikeWifi.isVpnActive(this)
        ) {
            if (!vpnPromptShown) { vpnPromptShown = true; promptVpnKillSwitch() }
        } else {
            vpnPromptShown = false
        }
        connectBtn.text = connectLabel(phase)
        (connectBtn as? MaterialButton)?.setIconResource(
            if (!heroIsCar() && (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING)) {
                R.drawable.ic_stop
            } else {
                R.drawable.ic_cast
            },
        )
        updateButtonStates(phase)
        renderHome(phase)
    }

    /**
     * Home-screen chrome that mirrors [ConnectionState]: header title + status line, status pill,
     * Connect colour, dock highlight and the glance tiles. Everything shown here is real data — the
     * bike doesn't report battery voltage or headlight state over this link, so the battery tile
     * shows the phone's own battery. To feed new live values (e.g. bike telemetry), extend
     * [HomeTelemetry] and set them here.
     */
    /** Status pill: error / live / linking win; otherwise "Not ready" (red) until steps 1–3 are green. */
    private fun applyStatusBadge(phase: Phase) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        val saved = BikeMemory.hasSaved(this)
        val ready = permOk && devOn && unitOnline
        applyHeroTint()
        val (badgeRes, badgeColorRes) = when {
            phase == Phase.ERROR -> R.string.home_badge_error to R.color.home_red
            live -> R.string.home_badge_live to R.color.home_mint
            carStandby() -> R.string.home_badge_standby to R.color.home_mint
            phase.busy -> R.string.home_badge_linking to R.color.home_amber
            !ready -> R.string.home_badge_not_ready to R.color.home_red
            saved -> R.string.home_badge_paired to R.color.home_mint
            else -> R.string.home_badge_ready to R.color.home_cyan
        }
        val badgeColor = ContextCompat.getColor(this, badgeColorRes)
        findViewById<TextView>(R.id.home_status_badge)?.apply {
            setText(badgeRes)
            setTextColor(badgeColor)
        }
        val tint = android.content.res.ColorStateList.valueOf(badgeColor)
        findViewById<View>(R.id.home_status_dot)?.backgroundTintList = tint
        // Pill background = the badge colour at ~15% opacity.
        findViewById<View>(R.id.home_status_badge_wrap)?.backgroundTintList =
            android.content.res.ColorStateList.valueOf((badgeColor and 0x00FFFFFF) or 0x26000000)
    }

    private fun renderHome(phase: Phase) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        if (live) lastLiveAt = System.currentTimeMillis()
        applyStatusBadge(phase)


        // Connect & Launch: cyan when ready, red while it acts as Stop.
        applyConnectBackground(phase)

        // Dock: highlight the tab that matches what the dash is showing right now.
        val activeDock = when {
            phase == Phase.MIRRORING -> R.id.btn_mirror_start
            GpxSession.active -> R.id.btn_gpx
            else -> 0
        }
        val cyan = ContextCompat.getColor(this, R.color.home_cyan)
        val idle = ContextCompat.getColor(this, R.color.home_text_2)
        for (id in intArrayOf(R.id.btn_hud_view, R.id.btn_devices, R.id.btn_gpx, R.id.btn_mirror_start, R.id.btn_usb_car)) {
            (findViewById<View>(id) as? MaterialButton)?.apply {
                val on = id == activeDock
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (on) ContextCompat.getColor(context, R.color.home_cyan_soft) else android.graphics.Color.TRANSPARENT,
                )
                setTextColor(if (on) cyan else idle)
                iconTint = android.content.res.ColorStateList.valueOf(if (on) cyan else idle)
            }
        }

        renderTelemetry(
            HomeTelemetry(
                phoneBatteryPct = phoneBatteryPct(),
                linkRes = when (phase) {
                    Phase.STREAMING, Phase.STARTING_AA, Phase.AA_VIDEO_LIVE -> R.string.home_link_aa
                    Phase.MIRRORING -> R.string.home_link_mirror
                    Phase.JOINING_WIFI, Phase.PXC_CONNECTING, Phase.RECONNECTING,
                    Phase.WAITING_FOR_BIKE -> R.string.home_link_wifi
                    else -> R.string.home_link_off
                },
                lastSyncAt = lastLiveAt,
                live = live,
            ),
        )
    }

    /** Values for the telemetry mini-grid; hook a real state provider in by building one of these. */
    private data class HomeTelemetry(
        val phoneBatteryPct: Int?,
        @androidx.annotation.StringRes val linkRes: Int,
        val lastSyncAt: Long,
        val live: Boolean,
    )

    private fun renderTelemetry(t: HomeTelemetry) {
        renderPermissions()
        findViewById<android.widget.SeekBar>(R.id.home_seek_volume)?.let { seek ->
            // Don't fight the rider's finger while they drag.
            if (!seek.isPressed) {
                val (now, max) = MediaButtonBridge.volumeLevels(this)
                if (seek.max != max) seek.max = max
                seek.progress = now
            }
        }
        // Destination: set (green) when our map has one or Google Maps is actively navigating.
        // Car box: card 4 becomes "USB debugging" on/off instead (the car link needs adb enabled).
        val carMode = heroIsCar()
        val adbOn = try {
            android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        val destSet = if (carMode) adbOn else
            (dev.zanderp.opencfmoto.aa.AaNavState.active && AaVideoBridge.pipeline != null) ||
            GpxSession.destination != null || GmapsNav.current != null
        findViewById<TextView>(R.id.tel_dest_label)?.text =
            "4  " + getString(if (carMode) R.string.home_tel_usbdebug else R.string.home_tel_dest)
        val destBg = ContextCompat.getColor(this, if (destSet) R.color.home_mint else R.color.home_red)
        val destFg = android.graphics.Color.WHITE
        findViewById<View>(R.id.tel_dest_card)?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(destBg)
        }
        findViewById<TextView>(R.id.tel_phone)?.apply {
            text = getString(
                if (carMode) (if (destSet) R.string.home_usbdebug_on else R.string.home_usbdebug_off)
                else if (destSet) R.string.home_dest_set else R.string.home_dest_unset,
            )
            setTextColor(destFg)
        }
        findViewById<TextView>(R.id.tel_dest_label)?.setTextColor(destFg)
        findViewById<android.widget.ImageView>(R.id.tel_dest_icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(destFg)
        // Head unit status = Android Auto's "head unit server" (the one behind ⋮ → Start/Stop head
        // unit server) is listening. Only a LIVE AA session also counts — "Starting Android Auto…"
        // proves nothing (pressing Connect used to turn this green with the server off). The bind
        // probe never touches AA, so it is safe to run in every phase.
        val aaLive = ConnectionState.phase.let { it == Phase.STREAMING || it == Phase.AA_VIDEO_LIVE }
        probeHeadUnitServer()
        // Server is on but not answering (handshake failed / never answered): needs a restart.
        val stuck = headUnitServerUp && AaVideoBridge.headUnitStuck && !aaLive
        val online = (aaLive || headUnitServerUp) && !stuck
        // Card background goes green (black text) or red (white text).
        val bg = ContextCompat.getColor(this, if (online) R.color.home_mint else R.color.home_red)
        val fg = android.graphics.Color.WHITE
        findViewById<View>(R.id.tel_unit_card)?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(bg)
        }
        findViewById<TextView>(R.id.tel_sync)?.apply {
            text = getString(when {
                stuck -> R.string.home_unit_restart
                online -> R.string.home_unit_online
                else -> R.string.home_unit_offline
            })
            setTextColor(fg)
        }
        findViewById<TextView>(R.id.tel_unit_label)?.setTextColor(fg)
        findViewById<android.widget.ImageView>(R.id.tel_unit_dot)?.imageTintList =
            android.content.res.ColorStateList.valueOf(fg)
        renderDevMode(online)
        unitOnline = online
        refreshReady()
    }

    /** Step 1: red → animated guide, then the real permission prompts; green → app settings as before. */
    private fun showPermissionGuide() {
        if (permOk) {
            onPermissionCardTapped()
        } else {
            HeadUnitDialog.showPermission(this) { onPermissionCardTapped() }
        }
    }

    /** "Head unit server is off" popup; on return from Android Auto, Connect continues if it's running. */
    private fun showHeadUnitGuide() {
        HeadUnitDialog.show(this) {
            awaitingHeadUnit = true
            openAndroidAuto()
        }
    }

    /** Dev-mode tutorial popup with a switch for riders who already turned developer mode on. */
    private fun showDevModeGuide() {
        HeadUnitDialog.showDevMode(
            this,
            alreadyOn = devOn,
            onToggle = { on ->
                getSharedPreferences("home_prefs", MODE_PRIVATE).edit().putBoolean("aa_dev_mode_seen", on).apply()
                renderDevMode(unitOnline && on)
                refreshReady()
            },
            onOpenAndroidAuto = { openAndroidAuto() },
        )
    }

    private var permOk = false
    private var devOn = false
    private var unitOnline = false

    /** Steps 1–3 (permission, dev mode, head unit) all green → Connect button says "Ready to connect". */
    private fun refreshReady() {
        if (!::connectBtn.isInitialized) return
        connectBtn.text = connectLabel(ConnectionState.phase)
        applyConnectBackground(ConnectionState.phase)
        applyStatusBadge(ConnectionState.phase)
    }

    /** Cyan when ready to connect; red while acting as Stop or while setup steps 1–3 aren't all green. */
    private fun applyConnectBackground(phase: Phase) {
        val stopping = !heroIsCar() && (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING)
        // Car page: step 2 is red until Android Auto is ready (or the car is already linked).
        val carNotReady = heroIsCar() && !UsbCarLink.isOpen && !UsbCarLink.isReadyForCar()
        val ready = permOk && devOn && unitOnline && !carNotReady
        (connectBtn as? MaterialButton)?.apply {
            backgroundTintList = null
            setBackgroundResource(
                if (stopping || !ready) R.drawable.home_cta_stop else R.drawable.home_cta_gradient,
            )
        }
    }

    private fun connectLabel(phase: Phase): CharSequence {
        if (heroIsCar()) {
            // Car page: this is step 2 (step 1 has its own button, which also acts as Stop).
            connectBtn.isSingleLine = false
            connectBtn.maxLines = 2
            val sub = getString(when {
                UsbCarLink.isOpen -> R.string.car_step2_sub_connected
                UsbCarLink.isReadyForCar() -> R.string.car_step2_sub_ready
                else -> R.string.car_step2_sub_first
            })
            return android.text.SpannableStringBuilder(getString(R.string.car_step2_connect)).apply {
                append('\n')
                val start = length
                append(sub)
                setSpan(android.text.style.RelativeSizeSpan(0.75f), start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val stopping = phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING
        val base = when {
            stopping -> getString(R.string.main_stop)
            heroIsCar() -> getString(R.string.main_connect_to, UsbCar.DISPLAY_NAME)
            realBikeSelected() != null ->
                getString(R.string.main_connect_to, realBikeSelected()?.name ?: "")
            else -> getString(R.string.home_connect_launch)
        }
        if (stopping) return base
        // Second, smaller line inside the button: ready when setup steps 1–3 are all green.
        connectBtn.isSingleLine = false
        connectBtn.maxLines = 2
        val sub = getString(if (permOk && devOn && unitOnline) R.string.home_ready else R.string.home_not_ready)
        return android.text.SpannableStringBuilder(base).apply {
            append('\n')
            val start = length
            append(sub)
            setSpan(android.text.style.RelativeSizeSpan(0.75f), start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Android Auto keeps its developer-mode flag private, so it can't be read directly. The head unit
     * server menu only exists with developer mode on, so a listening server proves it; we remember that.
     */
    private fun renderDevMode(serverUp: Boolean) {
        val prefs = getSharedPreferences("home_prefs", MODE_PRIVATE)
        if (serverUp && !prefs.getBoolean("aa_dev_mode_seen", false)) {
            prefs.edit().putBoolean("aa_dev_mode_seen", true).apply()
        }
        val on = prefs.getBoolean("aa_dev_mode_seen", false)
        devOn = on
        val bg = ContextCompat.getColor(this, if (on) R.color.home_mint else R.color.home_red)
        val fg = android.graphics.Color.WHITE
        findViewById<View>(R.id.tel_dev_card)?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(bg)
        }
        findViewById<TextView>(R.id.tel_dev_value)?.apply {
            text = getString(if (on) R.string.home_dev_on else R.string.home_dev_unknown)
            setTextColor(fg)
        }
        findViewById<TextView>(R.id.tel_dev_label)?.setTextColor(fg)
        findViewById<android.widget.ImageView>(R.id.tel_dev_icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(fg)
    }

    private fun openAndroidAuto() {
        // Android 10+ ships Android Auto without a launcher icon, so try its settings screen first
        // (that's where the Version-tap → ⋮ → head unit server menu lives), then a launcher intent.
        val pkg = "com.google.android.projection.gearhead"
        val attempts = listOf(
            { Intent("android.settings.ANDROID_AUTO_SETTINGS").setPackage(pkg) },
            {
                Intent(Intent.ACTION_MAIN).setClassName(
                    pkg, "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity",
                )
            },
            { packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("no launcher") },
        )
        for (build in attempts) {
            try {
                startActivity(build())
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.home_open_android_auto_missing, Toast.LENGTH_SHORT).show()
    }

    /** Home hero photo variants; tapping the photo cycles through them (choice is remembered). */
    private val bikePhotos = intArrayOf(R.drawable.home_bike_photo, R.drawable.home_bike_photo_srr)
    private val bikePhotoNames = arrayOf("675NK", "675SR-R")
    private var bikePhotoIndex = 0
    private var heroPager: androidx.viewpager2.widget.ViewPager2? = null
    private var lastSelRaw: String? = null

    /** Car box status line: connected / waiting for the cable (after Connect) / not connected to USB. */
    private fun updateCarStatus() {
        val tv = findViewById<TextView>(R.id.car_usb_status) ?: return
        findViewById<TextView>(R.id.home_cta_sub)?.setText(
            if (heroIsCar()) R.string.home_cta_sub_car else R.string.home_cta_sub,
        )
        val car = heroIsCar()
        findViewById<MaterialButton>(R.id.btn_car_ready)?.let { b ->
            b.visibility = if (car) View.VISIBLE else View.GONE
            if (car) {
                val busy = carBusy()
                b.text = getString(if (busy) R.string.car_step1_stop else R.string.car_step1_ready)
                b.setIconResource(if (busy) R.drawable.ic_stop else R.drawable.ic_sync)
                b.backgroundTintList = null
                b.setBackgroundResource(if (busy) R.drawable.home_cta_stop else R.drawable.home_cta_gradient)
            }
        }
        if (car && ::connectBtn.isInitialized) {
            connectBtn.text = connectLabel(ConnectionState.phase)
            applyConnectBackground(ConnectionState.phase)
        }
        // "Starting Android Auto…" would otherwise stay on screen while it is really standing by.
        val standby = carStandby()
        if (standby) {
            statusView.setText(R.string.car_standby)
            statusProgress.visibility = View.GONE
            applyStatusBadge(ConnectionState.phase)
        } else if (carStandbyShown) {
            val ph = ConnectionState.phase
            statusView.text = getString(ph.labelRes)
            statusProgress.visibility = if (ph.busy) View.VISIBLE else View.GONE
            applyStatusBadge(ph)
        }
        carStandbyShown = standby
        if (!car) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        val (text, color) = when {
            UsbCarLink.isOpen -> getString(R.string.car_usb_connected) to 0xFF10B981.toInt()
            UsbCarLink.isWatching && UsbCarLink.isReadyForCar() -> getString(R.string.car_usb_ready) to 0xFF10B981.toInt()
            UsbCarLink.isWatching && AndroidAutoService.isRunning -> getString(R.string.car_usb_waiting) to 0xFFF59E0B.toInt()
            else -> getString(R.string.car_usb_off) to 0xFFEF4444.toInt()
        }
        tv.text = text
        tv.setTextColor(color)
    }

    private val carStatusTick = object : Runnable {
        override fun run() {
            updateCarStatus()
            logView.postDelayed(this, 1_000L)
        }
    }

    /** Which box is showing: the car (page 2) or the bike (page 1). Falls back to the selection before the pager exists. */
    private fun heroIsCar(): Boolean =
        heroPager?.let { it.currentItem == 1 } ?: UsbCar.isUsb(BikeMemory.selected(this)?.qr)

    /** The selected device when it is a real bike (never the USB car). */
    private fun realBikeSelected(): SavedBike? =
        BikeMemory.selected(this)?.takeIf { !UsbCar.isUsb(it.qr) }
    private var heroSyncing = false
    private val heroPhotos = mutableListOf<android.widget.ImageView>()
    private fun setHeroFilter(cf: android.graphics.ColorFilter?) { heroPhotos.forEach { it.colorFilter = cf } }

    /** Move the hero to the page of the selected device (car → box 2, otherwise the bike box). */
    private fun syncHeroPage() {
        val pager = heroPager ?: return
        val want = if (UsbCar.isUsb(BikeMemory.selected(this)?.qr)) 1 else 0
        if (pager.currentItem == want) return
        heroSyncing = true
        pager.setCurrentItem(want, true)
        pager.postDelayed({ heroSyncing = false }, 400)
    }

    /** Speed page of the home hero (null until [setupHeroPager]). */
    private var heroSpeedPage: View? = null

    /**
     * Home hero is a 2-page swipe: the bike photo, then live trip speed from the shared
     * [TripRecorder] (same numbers as the Trip page). Tapping the speed page opens the Trip page.
     */
    private fun setupHeroPager() {
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.home_hero_pager) ?: return
        fun photoView() = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        }
        // Two swipeable boxes: page 0 = the bike, page 1 = the USB car. The page is the selected target.
        val bikePhoto = photoView().apply { isClickable = true; isFocusable = true }
        val carPhoto = photoView().apply {
            setImageResource(R.drawable.home_car_photo)
            contentDescription = "Perodua Myvi"
        }
        heroPhotos.clear()
        heroPhotos += bikePhoto
        heroPhotos += carPhoto
        setupBikePhoto(bikePhoto)
        val pages = listOf<View>(bikePhoto, carPhoto)
        heroPager = pager
        // The whole card swipes (see SwipeCardLayout); the inner pager only holds the two photos.
        pager.isUserInputEnabled = false
        findViewById<SwipeCardLayout>(R.id.home_hero_swipe)?.apply {
            canMove = { dir -> (pager.currentItem + dir) in 0..1 }
            onCommit = { dir -> pager.setCurrentItem(pager.currentItem + dir, false) }
        }
        findViewById<android.widget.LinearLayout>(R.id.home_hero_dots)?.visibility = View.VISIBLE

        pager.offscreenPageLimit = 1
        pager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    pages[viewType].also { v ->
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        v.layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    },
                ) {}
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {}
        }

        // Page dots.
        val dots = findViewById<android.widget.LinearLayout>(R.id.home_hero_dots)
        val d = resources.displayMetrics.density
        // Bike | Car tabs (replace the two page dots): tap to switch, stays in sync with swiping.
        val tabLabels = listOf(getString(R.string.home_tab_bike), getString(R.string.home_tab_car))
        dots?.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(0xFFEEF2F6.toInt())
            }
            setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            (layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin = (2 * d).toInt()
        }
        val swipeCard = findViewById<SwipeCardLayout>(R.id.home_hero_swipe)
        val tabViews = tabLabels.mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                minWidth = (64 * d).toInt()
                setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
                setOnClickListener {
                    val dir = i - pager.currentItem
                    if (dir != 0) swipeCard?.slide(dir)
                }
            }.also { dots?.addView(it) }
        }
        fun paintTabs(sel: Int) = tabViews.forEachIndexed { i, v ->
            val on = i == sel
            v.setTextColor(if (on) android.graphics.Color.WHITE else ContextCompat.getColor(this, R.color.home_text_2))
            v.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ContextCompat.getColor(this@MainActivity, R.color.home_cyan))
            } else null
        }
        fun paintDots(sel: Int) {
            // The car box has no handlebar buttons, so the "Don't lock volume" switch is bike-only.
            findViewById<View>(R.id.home_switch_free_volume)?.visibility = if (sel == 1) View.GONE else View.VISIBLE
            findViewById<View>(R.id.car_usb_status)?.visibility = if (sel == 1) View.VISIBLE else View.GONE
            // Pairing by dash QR is for bikes; a car head unit has no QR.
            findViewById<View>(R.id.home_pair_card)?.visibility = if (sel == 1) View.GONE else View.VISIBLE
            paintTabs(sel)
            // Card 4 differs per box (Destination for the bike, USB debugging for the car).
            pager.post { if (!isFinishing) renderHome(ConnectionState.phase) }
        }
        val startPage = if (UsbCar.isUsb(BikeMemory.selected(this)?.qr)) 1 else 0
        pager.setCurrentItem(startPage, false)
        lastSelRaw = BikeMemory.selected(this)?.raw
        paintDots(startPage)
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                paintDots(position)
                if (heroSyncing) return
                // Swiping picks the target: the car, or the most recent real bike (none → scan a QR).
                if (position == 1) {
                    UsbCar.ensureSelected(this@MainActivity)
                } else {
                    val bike = BikeMemory.devices(this@MainActivity).firstOrNull { !UsbCar.isUsb(it.qr) }
                    if (bike != null) BikeMemory.select(this@MainActivity, bike.raw)
                    else BikeMemory.clearSelection(this@MainActivity)
                }
                lastSelRaw = BikeMemory.selected(this@MainActivity)?.raw
                refreshBikeLabel()
                refreshReady()
            }
        })
    }

    /** Live trip numbers on the hero's speed page (same maths as [TripActivity]). */
    private fun renderHeroSpeed(s: TripRecorder.Stats) {
        val page = heroSpeedPage ?: return
        page.findViewById<TextView>(R.id.home_speed).text = s.speedKmh.toString()
        page.findViewById<TextView>(R.id.home_speed_gps).text = when {
            !s.recording -> "Not recording — tap to open Trip"
            !s.hasFix -> getString(R.string.trip_waiting_for_gps)
            else -> "GPS fix" + if (s.accuracyM > 0) " ±${s.accuracyM}m" else ""
        }
        page.findViewById<TextView>(R.id.home_speed_distance).text =
            String.format(java.util.Locale.US, "%.2f km", s.distanceMeters / 1000.0)
        val sec = s.movingTimeMs.coerceAtLeast(0) / 1000
        page.findViewById<TextView>(R.id.home_speed_duration).text =
            String.format(java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        page.findViewById<TextView>(R.id.home_speed_max).text = "${(s.maxSpeedMs * 3.6f).toInt()} km/h"
        val avg = if (s.movingTimeMs > 0) (s.distanceMeters / (s.movingTimeMs / 1000.0)) * 3.6 else 0.0
        page.findViewById<TextView>(R.id.home_speed_avg).text = "${avg.toInt()} km/h"
    }


    /** Red wash over the bike photo while setup steps 1–3 aren't all green (transparent pixels stay clear). */
    private fun applyHeroTint() {
        val ready = permOk && devOn && unitOnline
        if (ready) {
            heroPulse?.cancel()
            heroPulse = null
            setHeroFilter(null)
            return
        }
        if (heroPulse?.isRunning == true) return
        // Pulse the red wash in and out (≈ 12% ↔ 45% opacity) until the setup steps are green.
        heroPulse = android.animation.ValueAnimator.ofInt(0x20, 0x73).apply {
            duration = 900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val a = it.animatedValue as Int
                setHeroFilter(android.graphics.PorterDuffColorFilter(
                    (a shl 24) or 0x00EF4444, android.graphics.PorterDuff.Mode.SRC_ATOP,
                ))
            }
            start()
        }
    }

    private var heroPulse: android.animation.ValueAnimator? = null

    private fun setupBikePhoto(photo: android.widget.ImageView) {
        applyHeroTint()
        val prefs = getSharedPreferences("opencfmoto_home", MODE_PRIVATE)
        bikePhotoIndex = prefs.getInt("bike_photo", 0).coerceIn(0, bikePhotos.lastIndex)
        photo.setImageResource(bikePhotos[bikePhotoIndex])
        photo.contentDescription = bikePhotoNames[bikePhotoIndex]
        photo.setOnClickListener {
            // Tapping the bike box cycles the bike variants.
            bikePhotoIndex = (bikePhotoIndex + 1) % bikePhotos.size
            prefs.edit().putInt("bike_photo", bikePhotoIndex).apply()
            photo.animate().cancel()
            photo.animate().alpha(0f).setDuration(120).withEndAction {
                photo.setImageResource(bikePhotos[bikePhotoIndex])
                photo.contentDescription = bikePhotoNames[bikePhotoIndex]
                photo.animate().alpha(1f).setDuration(180).start()
            }.start()
        }
    }

    @Volatile private var headUnitServerUp = false
    @Volatile private var probingServer = false

    /**
     * Is Android Auto's head unit server listening? Updates the tile when it flips.
     *
     * Never CONNECT to find out: AA's developer head unit server serves one connection, and a
     * connection that closes without the AAP handshake leaves it never accepting again (until it is
     * stopped/started in AA) — every real session then hangs on "Starting Android Auto…". Instead try
     * to BIND the port: "address in use" means AA is listening; success means it isn't (closed at once).
     */
    private fun probeHeadUnitServer() {
        if (probingServer) return
        probingServer = true
        Thread {
            val up = try {
                java.net.ServerSocket().use {
                    // Ignore TIME_WAIT leftovers; a LISTENing socket still blocks the bind.
                    it.reuseAddress = true
                    it.bind(
                        java.net.InetSocketAddress("127.0.0.1", dev.zanderp.opencfmoto.aa.AaReceiver.HEADUNIT_SERVER_PORT),
                    )
                }
                false
            } catch (_: java.net.BindException) {
                true
            } catch (_: Exception) {
                headUnitServerUp
            }
            probingServer = false
            if (up != headUnitServerUp) {
                headUnitServerUp = up
                runOnUiThread { if (!isFinishing) renderHome(ConnectionState.phase) }
            }
        }.start()
    }

    /** Permission card: green when every permission the app asks for is granted, red otherwise. */
    private fun renderPermissions() {
        val ok = SetupHelper.permissionsGranted(this)
        permOk = ok
        refreshReady()
        val bg = ContextCompat.getColor(this, if (ok) R.color.home_mint else R.color.home_red)
        val fg = android.graphics.Color.WHITE
        findViewById<View>(R.id.tel_perm_card)?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(bg)
        }
        findViewById<TextView>(R.id.tel_top_speed)?.apply {
            text = getString(if (ok) R.string.home_perm_ok else R.string.home_perm_missing)
            setTextColor(fg)
        }
        findViewById<TextView>(R.id.tel_perm_label)?.setTextColor(fg)
        findViewById<android.widget.ImageView>(R.id.tel_perm_icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(fg)
    }

    private fun phoneBatteryPct(): Int? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Exception) {
        null
    }

    /** Soft breathing pulse for the header link dot while linking / live. */
    private fun pulse(v: View) {
        v.animate().alpha(0.35f).setDuration(700).withEndAction {
            v.animate().alpha(1f).setDuration(700).withEndAction {
                val p = ConnectionState.phase
                if (v.isAttachedToWindow && (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING)) pulse(v)
            }.start()
        }.start()
    }

    /** Refresh "Last sync" / battery while the home screen is visible. */
    private val homeTick = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
            renderHome(ConnectionState.phase)
            logView.postDelayed(this, 30_000L)
        }
    }

    /** Enable actions that make sense in the current [phase]. */
    private fun updateButtonStates(phase: Phase) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        val busy = phase.busy
        // Connect/Stop always tappable except pure idle race — Stop while busy/live, Connect otherwise.
        connectBtn.isEnabled = true
        // Scan only when not mid-flight.
        setEnabled(R.id.btn_aa_start, !busy)
        // Map / Mirror stay available while live so the rider can switch dash content.
        setEnabled(R.id.btn_mirror_start, !busy || live)
        setEnabled(R.id.btn_gpx, !busy || live)
        setEnabled(R.id.btn_aa_stop, busy || live)
    }

    private fun setEnabled(id: Int, enabled: Boolean) {
        findViewById<View>(id)?.let {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    /** Show which bike (if any) is remembered, under the status header. */
    private fun refreshBikeLabel() {
        bikeView.text = bikeLabelText()
    }

    /**
     * The official CFMoto app is holding the mirroring-link ports. Android won't let us silently
     * force-stop another app, so offer a best-effort background kill and a one-tap jump to its
     * App-info screen (guaranteed Force stop), then reconnect.
     */
    private fun promptCloseRival() {
        val installed = RivalClient.isInstalled(this)
        GlassAlertBuilder(this)
            .setTitle(R.string.main_rival_title)
            .setMessage(
                "The official CFMoto/EasyConnect app is running and holding the bike's link ports, so " +
                    "the dash stays blank. Close it, then reconnect.\n\n" +
                    "\"Close & retry\" tries to stop it for you; if the dash is still blank, use " +
                    "\"App settings\" and tap Force stop."
            )
            .setPositiveButton("Close & retry") { _, _ ->
                val killed = RivalClient.closeBestEffort(this)
                log(if (killed) "→ asked Android to close the official CFMoto app; retrying…"
                    else "→ couldn't auto-close the official CFMoto app — open its settings to Force stop.")
                connectBtn.postDelayed({ reconnectSavedBike() }, 1500)
            }
            .setNeutralButton("App settings") { _, _ ->
                if (!RivalClient.openAppInfo(this)) {
                    Toast.makeText(this, R.string.main_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(true)
            .apply { if (!installed) setMessage("Something is holding the bike's link ports (10920-10922). Close any other CFMoto/EasyConnect app and reconnect.") }
            .show()
    }

    /**
     * Always-on VPN with connection blocking prevents pinning sockets to bike Wi-Fi (EPERM).
     * Offer a shortcut into system VPN settings; the user must disable the kill-switch or VPN.
     */
    private fun promptVpnKillSwitch() {
        GlassAlertBuilder(this)
            .setTitle("VPN is blocking the bike")
            .setMessage(
                "Android Auto reached the phone, but a VPN kill-switch is blocking the bike Wi‑Fi " +
                    "(Network.bindSocket → EPERM).\n\n" +
                    "Fix (any one):\n" +
                    "• Turn the VPN off for this ride\n" +
                    "• Disable Always-on VPN → \"Block connections without VPN\"\n" +
                    "• Allow LAN / local network in the VPN app (PCAPdroid, AdGuard, etc.)\n\n" +
                    "Then tap Connect again."
            )
            .setPositiveButton("VPN settings") { _, _ ->
                try {
                    startActivity(Intent("android.net.vpn.SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Open Settings ▸ Network ▸ VPN", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(true)
            .show()
    }

    /** Re-run the one-tap Connect for the saved bike (used after closing the rival app). */
    private fun reconnectSavedBike() {
        val saved = BikeMemory.lastQr(this) ?: return
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    private fun bikeLabelText(): String {
        // The car head unit has no QR: this card is about pairing a bike, so don't call the car "paired" here.
        if (heroIsCar()) return getString(R.string.home_pair_hint_car)
        val name = realBikeSelected()?.name
        return if (name != null) getString(R.string.main_paired, name)
        else getString(R.string.main_no_bike_paired)
    }

    /**
     * Join the bike Wi-Fi and start the PXC prober.
     *
     * When [gateOnAaSteady] is true (Android Auto path), the prober start is handed to [BikeLink],
     * which waits until AA video is also steady — so this Wi-Fi join can run in parallel with AA
     * boot. When false (mirror path), the prober starts as soon as Wi-Fi is bound.
     *
     * Uses applicationContext + the process-global prober (not this activity) so the hand-off
     * completes even if the activity is destroyed/recreated after it was armed.
     */
    private fun joinWifi(qr: QrData, gateOnAaSteady: Boolean) {
        if (DummyBike.isDummy(qr)) {
            // Test tool: no Wi‑Fi to join — the fake dash lives on 127.0.0.1 inside this app.
            DummyBike.ensureListening()
            LogBus.log("→ dummy bike: skipping Wi‑Fi join, linking over 127.0.0.1")
            linkDummy(DummyBike.loopback, gateOnAaSteady)
            return
        }
        if (UsbCar.isUsb(qr)) {
            linkUsb(gateOnAaSteady)
            return
        }
        joinRealBikeWifi(qr, gateOnAaSteady)
    }

    /** USB car: the transport is the already-open [UsbCarLink]; no Wi‑Fi join, no probe. */
    private fun linkUsb(gateOnAaSteady: Boolean) {
        log("→ USB car: skipping Wi‑Fi, serving the car over the USB bridge" +
            if (UsbCarLink.isOpen) "" else " (car not attached yet)")
        if (gateOnAaSteady) {
            BikeLink.markUsbReady()
            // Same relaxed gate as the dummy bike: any decoded AA video is enough to start serving.
            fun waitForAaVideo(triesLeft: Int) {
                logView.postDelayed({
                    when {
                        AaVideoBridge.aaDecoding -> {
                            LogBus.log("→ USB car: AA video is flowing — starting the link")
                            BikeLink.markAaVideoSteady()
                        }
                        triesLeft > 0 && AndroidAutoService.isRunning -> waitForAaVideo(triesLeft - 1)
                        else -> LogBus.log("→ USB car: Android Auto never sent video")
                    }
                }, 1_000L)
            }
            waitForAaVideo(60)
        } else {
            ConnectionState.set(Phase.PXC_CONNECTING, UsbCar.DISPLAY_NAME)
            try {
                (BikeLink.prober ?: prober).startLoopback()
            } catch (e: Exception) {
                LogBus.log("prober (USB) start failed: $e")
            }
        }
    }

    /** Start the PXC link to the in-app fake dash on 127.0.0.1. */
    private fun linkDummy(lo: java.net.Inet4Address, gateOnAaSteady: Boolean) {
            if (gateOnAaSteady) {
                BikeLink.markP2pReady(lo, lo)
                // Emulators rarely reach AA's 25 fps "steady" mark (static screens idle near
                // 10 fps), so the real-bike gate would never open. For the dummy, any decoded
                // AA video is enough to start the link.
                fun waitForAaVideo(triesLeft: Int) {
                    logView.postDelayed({
                        when {
                            AaVideoBridge.aaDecoding -> {
                                LogBus.log("→ dummy bike: AA video is flowing — starting the link without the 25 fps gate")
                                BikeLink.markAaVideoSteady()
                            }
                            triesLeft > 0 && AndroidAutoService.isRunning -> waitForAaVideo(triesLeft - 1)
                            else -> LogBus.log("→ dummy bike: Android Auto never sent video")
                        }
                    }, 1_000L)
                }
                waitForAaVideo(30)
            } else {
                ConnectionState.set(Phase.PXC_CONNECTING, DummyBike.DISPLAY_NAME)
                try {
                    (BikeLink.prober ?: prober).start(null, gatewayOverride = lo, bindIpOverride = lo)
                } catch (e: Exception) {
                    LogBus.log("prober start failed: $e")
                }
            }
    }

    private fun joinRealBikeWifi(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        ConnectionState.set(Phase.JOINING_WIFI)
        val transport = AppSettings.transport(this)
        // Phone-hosts-hotspot (Zontes action=128 / no SoftAP pwd): dash joins the phone.
        if (qr.supportsPhoneHotspot && qr.pwd.isEmpty()) {
            joinPhoneHotspot(qr, gateOnAaSteady)
            return
        }
        // AUTO: P2P when the QR is P2P-only (incl. non-DIRECT SSIDs — join by MAC), or DIRECT-*.
        // SoftAP fallback remains in joinWifiP2p.onFailed for SoftAP-capable units.
        // Never force P2P when the QR is SoftAP-only (action bit3 clear) — Setup→P2P on those
        // bikes only burns 25s then falls back (Benelli TRK / bj* SSIDs in the field).
        val useP2p = when (transport) {
            WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
            WifiTransport.AP -> false
            WifiTransport.AUTO ->
                (qr.supportsP2p && !qr.supportsAp) ||
                    (qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                        (qr.supportsP2p || !qr.supportsAp))
        }
        // Per-bike memory refines AUTO only (an explicit P2P/AP setting is the rider's call): once a
        // transport has produced a live link for THIS bike, use it and skip the dead path. Some dashes
        // advertise DIRECT-* + SoftAP, so AUTO tries P2P first and burns the whole timeout on every
        // connect even when P2P never forms a group on this phone, while SoftAP connects in seconds.
        val remembered = if (transport == WifiTransport.AUTO) BikeMemory.winningTransport(this, qr.ssid) else null
        val effUseP2p = when {
            remembered == "AP" && qr.supportsAp -> false
            remembered == "P2P" && qr.supportsP2p -> true
            else -> useP2p
        }
        if (remembered != null && effUseP2p != useP2p) {
            log("→ transport memory: '$remembered' connected before for '${qr.ssid}' — using it, skipping the dead path")
        }
        if (transport == WifiTransport.P2P && !useP2p) {
            log(
                "→ Setup Wi‑Fi transport is P2P but QR is SoftAP-only " +
                    "(action=${qr.action} ssid=${qr.ssid}) — joining SoftAP instead",
            )
        }
        if (effUseP2p) {
            val isDirect = qr.ssid.startsWith("DIRECT-", ignoreCase = true)
            // P2P-only QRs with a SoftAP-style SSID but no MAC burn ~40s on MAC ERROR
            // (Voge-5G / ZT5G / etc.). Prefer SoftAP immediately when we have a password.
            if (!isDirect && qr.mac.isNullOrBlank() && qr.pwd.isNotEmpty()) {
                log(
                    "→ QR says P2P-only; SSID '${qr.ssid}' has no MAC — " +
                        "joining SoftAP first (skip Wi‑Fi Direct by MAC)",
                )
            } else {
                if (!isDirect) {
                    log(
                        "→ QR says P2P-only; SSID '${qr.ssid}' is not DIRECT-* — " +
                            "trying Wi‑Fi Direct by MAC (then SoftAP fallback)",
                    )
                }
                joinWifiP2p(qr, gateOnAaSteady)
                return
            }
        }
        BikeWifi.reuseOrJoin(
            context = applicationContext,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = { network ->
                WifiGate.cancelNotification(applicationContext)
                // SoftAP got Wi-Fi up for this bike → remember it so AUTO skips P2P next time.
                BikeMemory.setWinningTransport(applicationContext, qr.ssid, "AP")
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // Map / Mirror: no AA gating — rebuild PXC immediately on the bound network.
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(network ?: BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    /**
     * Dash is the Wi‑Fi client (Carbit `action=128`). Android cannot create a hotspot with the
     * dash SSID/password for us — show assist dialog, open tether settings, then poll for EasyConn.
     */
    private fun joinPhoneHotspot(qr: QrData, gateOnAaSteady: Boolean) {
        LogBus.log(
            "→ phone-hotspot mode (action=${qr.action} mac=${qr.mac}) — " +
                "assist dialog (app cannot create AP; set dash SSID/pwd in system hotspot)",
        )
        ConnectionState.set(Phase.JOINING_WIFI, getString(R.string.main_phone_hotspot_status))
        PhoneHotspotAssist.showSetupDialog(
            activity = this,
            qr = qr,
            onContinue = { startPhoneHotspotScan(gateOnAaSteady) },
            onCancel = {
                ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_cancelled))
            },
        )
    }

    /** Poll for tether iface (~45s) then NSD / subnet EasyConn scan. */
    private fun startPhoneHotspotScan(gateOnAaSteady: Boolean) {
        val creds = PhoneHotspotAssist.loadCreds(this)
        if (creds.ssid.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.main_phone_hotspot_waiting_named, creds.ssid),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, R.string.main_phone_hotspot_hint, Toast.LENGTH_LONG).show()
        }
        ConnectionState.set(Phase.JOINING_WIFI, getString(R.string.main_phone_hotspot_status))
        Thread({
            try {
                var subnets = emptyList<PhoneHotspotScan.Subnet>()
                val pollRounds = 30 // ~45s at 1.5s
                for (i in 0 until pollRounds) {
                    if (ConnectionState.phase == Phase.STOPPED) return@Thread
                    subnets = PhoneHotspotScan.tetheringSubnets()
                    if (subnets.isNotEmpty()) break
                    if (i == 0 || i % 5 == 0) {
                        LogBus.log(
                            "[HOTSPOT] waiting for tether interface… " +
                                "(${i + 1}/$pollRounds) set Android hotspot" +
                                creds.ssid.takeIf { it.isNotEmpty() }?.let { " SSID='$it'" }.orEmpty(),
                        )
                    }
                    try {
                        Thread.sleep(1_500)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (subnets.isEmpty()) {
                    LogBus.log(
                        "[HOTSPOT] no tether interface after wait — open hotspot settings " +
                            "and set the dash SSID/password, then Connect again",
                    )
                    runOnUiThread {
                        ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_no_iface))
                        GlassAlertBuilder(this)
                            .setTitle(R.string.main_phone_hotspot_dialog_title)
                            .setMessage(R.string.main_phone_hotspot_no_iface)
                            .setPositiveButton(R.string.main_phone_hotspot_open_settings) { _, _ ->
                                PhoneHotspotAssist.openHotspotSettings(this)
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    }
                    return@Thread
                }
                val subnet = subnets.first()
                LogBus.log(
                    "[HOTSPOT] using ${subnet.interfaceName} " +
                        "${subnet.localAddress.hostAddress}/${subnet.prefixLength}",
                )
                // Prefer NSD first (works when multicast reaches the tether iface).
                val nsd = EasyConnDiscovery.discoverNsd(applicationContext, LogBus::log)
                val peer = nsd?.host
                    ?: PhoneHotspotScan.findEasyConnPeer(subnet, LogBus::log)
                if (peer == null) {
                    runOnUiThread {
                        ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_no_peer))
                        Toast.makeText(
                            this,
                            getString(R.string.main_phone_hotspot_no_peer),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@Thread
                }
                val bindIp = subnet.localAddress
                runOnUiThread {
                    WifiGate.cancelNotification(applicationContext)
                    if (gateOnAaSteady) {
                        LogBus.log(
                            "→ hotspot peer ${peer.hostAddress} (waiting for AA); " +
                                "bind=${bindIp.hostAddress}",
                        )
                        BikeLink.markP2pReady(bindIp, peer)
                    } else {
                        ConnectionState.set(Phase.PXC_CONNECTING)
                        LogBus.log("→ hotspot peer ${peer.hostAddress}; starting EasyConn PXC …")
                        try {
                            (BikeLink.prober ?: prober).start(
                                network = null,
                                gatewayOverride = peer,
                                bindIpOverride = bindIp,
                            )
                        } catch (e: Exception) {
                            LogBus.log("prober start failed: $e")
                        }
                    }
                }
            } catch (e: Exception) {
                LogBus.log("[HOTSPOT] failed: $e")
                runOnUiThread {
                    ConnectionState.set(Phase.ERROR, "hotspot scan failed")
                }
            }
        }, "phone-hotspot-scan").apply { isDaemon = true }.start()
    }

    /** Wi‑Fi Direct Group Owner path (CL‑C450 / some DIRECT- SSIDs / Zontes MAC join). */
    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        // How long to wait for a P2P group before failing over to SoftAP:
        //  - "P2P" remembered  → proven P2P device on this phone; give it the full window.
        //  - SoftAP advertised → a working fallback exists, so bail (12s) when P2P is hopeless
        //    (some phones ERROR on every join attempt in <1s but the group never forms).
        //  - otherwise (P2P-only) → full window; there is nothing to fall back to.
        val known = BikeMemory.winningTransport(this, qr.ssid)
        val p2pTimeout = when {
            known == "P2P" -> BikeWifiP2p.CONNECT_TIMEOUT_MS
            qr.supportsAp && qr.pwd.isNotEmpty() -> 12_000L
            else -> BikeWifiP2p.CONNECT_TIMEOUT_MS
        }
        BikeWifiP2p.connect(
            context = applicationContext,
            qr = qr,
            timeoutMs = p2pTimeout,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(applicationContext)
                // P2P actually formed a group for this bike → remember it (some DIRECT-* bikes need P2P).
                BikeMemory.setWinningTransport(applicationContext, qr.ssid, "P2P")
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                if (qr.pwd.isEmpty() || qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                    LogBus.log("P2P join failed: $reason — no SoftAP credentials to fall back to")
                    ConnectionState.set(Phase.ERROR, "Wi‑Fi Direct failed")
                    return@connect
                }
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(applicationContext)) return@connect
                BikeWifi.join(
                    context = applicationContext,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(applicationContext)
                        // SoftAP fallback connected → remember AP so AUTO goes straight here next time.
                        BikeMemory.setWinningTransport(applicationContext, qr.ssid, "AP")
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                (BikeLink.prober ?: prober).start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    /** Permission card tap: ask for whatever is missing; if Android won't ask again, open app settings. */
    private fun onPermissionCardTapped() {
        val missing = SetupHelper.missingPermissions(this)
        if (missing.isEmpty()) {
            // Everything granted: same as Setup's "App settings" button.
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", packageName, null)),
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.home_perm_ok, Toast.LENGTH_SHORT).show()
            }
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_HOME_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_HOME_PERMS) return
        renderHome(ConnectionState.phase)
        // Denied with "don't ask again" (or the system showed no dialog): only Settings can fix it.
        val blocked = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])
        }
        if (blocked) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", packageName, null)),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    /**
     * Home search bar — two modes depending on what's on the bike:
     *  1) Our Map projected → Nominatim search on the dash ([DashRemote])
     *  2) Android Auto live → hand off to Google Maps (route should appear on the dash)
     *  3) Nothing projected → open Map hub search on the phone
     */
    private fun searchTypedInMaps() {
        val field = findViewById<android.widget.EditText>(R.id.et_destination)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        if (!NavLauncher.searchInMaps(this, field.text?.toString().orEmpty())) {
            Toast.makeText(this, R.string.home_search_maps_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToTyped() {
        val field = findViewById<android.widget.EditText>(R.id.et_destination)
        val dest = field.text?.toString()?.trim().orEmpty()
        if (dest.isEmpty()) {
            Toast.makeText(this, R.string.main_type_place, Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)

        val aaLive = AaVideoBridge.pipeline != null
        val ourMapOnBike = GpxSession.active && DashRemote.isAvailable

        // 1) Our own map is on the bike — never send AA intents (would crash/steal Maps).
        if (ourMapOnBike && DashRemote.submit(dest)) {
            field.setText("")
            Toast.makeText(this, getString(R.string.main_searching_dash, dest), Toast.LENGTH_SHORT).show()
            return
        }
        // 2) Android Auto is live — resolve place then navigate (free-text google.navigation often
        //    crashes or won't let the rider pick a route on newer Maps / AA).
        if (aaLive && !GpxSession.active) {
            field.setText("")
            sendDestinationToAndroidAuto(dest)
            return
        }
        // Stale DashRemote with no map session: ignore and fall through.
        if (!aaLive && DashRemote.submit(dest)) {
            field.setText("")
            Toast.makeText(this, getString(R.string.main_searching_dash, dest), Toast.LENGTH_SHORT).show()
            return
        }
        // 3) Nothing projected → Map hub on the phone.
        GpxActivity.startSearch(this, dest)
    }

    /**
     * Look up [dest] (location-biased when possible), then fire Google Maps navigation with
     * lat/lng so Android Auto gets a clean destination instead of an ambiguous free-text query.
     */
    private fun sendDestinationToAndroidAuto(dest: String) {
        Toast.makeText(this, getString(R.string.main_sent_aa, dest), Toast.LENGTH_SHORT).show()
        log("[search] AA: looking up \"$dest\"…")
        val near = lastKnownLatLon()
        NominatimSearch.searchAsync(
            query = dest,
            nearLat = near?.first,
            nearLon = near?.second,
            onResult = { places ->
                runOnUiThread {
                    val best = places.firstOrNull()
                    if (best != null) {
                        // Prefer the place name (city) so Maps starts guidance to that place;
                        // bare lat/lon with a "(label)" suffix was opening Maps with an empty search.
                        val place = best.name.ifBlank { dest }
                        log("[search] AA: resolved → $place (${best.lat},${best.lon})")
                        if (!NavLauncher.navigate(this, place, ::log)) {
                            NavLauncher.navigateLatLon(this, best.lat, best.lon, place, ::log)
                        }
                    } else {
                        log("[search] AA: no geocode hit — free-text navigate \"$dest\"")
                        NavLauncher.navigate(this, dest, ::log)
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    log("[search] AA: geocode failed ($err) — free-text navigate \"$dest\"")
                    NavLauncher.navigate(this, dest, ::log)
                }
            },
        )
    }

    private fun lastKnownLatLon(): Pair<Double, Double>? {
        return try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) loc.latitude to loc.longitude else null
        } catch (_: Exception) {
            null
        }
    }

    private fun stopEverything() {
        log("→ stopping everything (Android Auto + bike)")
        dev.zanderp.opencfmoto.aa.AaNavState.set(false)
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { if (::prober.isInitialized) prober.stop() } catch (e: Exception) { log("prober stop: $e") }
        try { bleWakeUp?.stop() } catch (_: Exception) {}
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(this) } catch (e: Exception) { log("projection stop: $e") }
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(this, ::log) } catch (e: Exception) { log("wifi leave: $e") }
        ConnectionState.set(Phase.STOPPED, "")
    }

    /**
     * Map button always opens the Hub — pick free ride / route / search / offline there.
     * Starting a ride projects to the bike (no AA); a "See map on phone" button covers
     * off-bike navigation (e.g. walking back to a parked bike).
     */
    private fun onMapPressed() {
        // Don't carry a killed Campina/etc. NAV_TO into the hub → phone map.
        if (ConnectionState.phase != Phase.STREAMING && ConnectionState.phase != Phase.MIRRORING) {
            GpxSession.abandonStaleNavigation("map hub")
        }
        log("→ Map: open Hub")
        GpxActivity.start(this)
    }

    /**
     * Mirror button: if already projecting, arm screen capture and re-link as mirror;
     * otherwise start mirror flow (consent then connect).
     */
    private fun onMirrorPressed() {
        // parsec82 TRK 702 X (2026-07-30): tapping Mirror again while frames were still flowing
        // tore down a live PXC session (freeze → second tap recovery). Ignore duplicate taps
        // when mirroring and we sent a frame recently; allow retry when stalled/frozen.
        val mirroring =
            ConnectionState.phase == Phase.MIRRORING || ConnectionState.phase == Phase.RECONNECTING
        val ms = BikeLink.prober?.msSinceLastFrame() ?: Long.MAX_VALUE
        if (mirroring && ms < 15_000L) {
            log("→ Mirror: already mirroring (last frame ${ms}ms ago) — ignore duplicate tap")
            Toast.makeText(
                this,
                "Already mirroring — wait for the dash, or tap Stop first",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        log("→ Mirror: cast phone screen to dash")
        pendingAaStart = false
        MirrorOrientationLock.apply(this)
        ensureLocationPermission()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                Toast.makeText(
                    this,
                    "Single-app mirror needs Android 14+. Whole screen will be used.",
                    Toast.LENGTH_LONG,
                ).show()
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            log("mirror start failed ($e)")
        }
    }

    private fun shareLog() {
        try {
            CrashGuard.persistSession(this)
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))

            val crash = CrashGuard.crashFile(this)
            if (crash.exists() && crash.length() > 0L) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", crash))
                log("attaching crash report: ${crash.name} (${crash.length()} bytes)")
            }

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", d))
                log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun maybeCheckUpdate() {
        Thread {
            val release = try {
                UpdateChecker.check(this, manual = false)
            } catch (_: Exception) {
                null
            } ?: return@Thread
            runOnUiThread { showUpdateDialog(release) }
        }.start()
    }

    private fun checkUpdateManual() {
        Toast.makeText(this, R.string.main_checking_update, Toast.LENGTH_SHORT).show()
        Thread {
            val release = UpdateChecker.check(this, manual = true)
            runOnUiThread {
                if (release == null) {
                    Toast.makeText(this, R.string.main_up_to_date, Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateDialog(release)
                }
            }
        }.start()
    }

    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val dialog = GlassAlertBuilder(this)
            .setTitle("Update ${release.version}")
            .setMessage(ReleaseNotes.toSpanned(release.notes))
            .setPositiveButton("Download") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.main_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Skip") { _, _ -> UpdateChecker.skip(this, release.version) }
            .setNeutralButton("Later", null)
            .show()
        // Headings/bullets/bold come from [ReleaseNotes]; make markdown links tappable too.
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun reportProblem() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val problem = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_problem)
            minLines = 3
            setPadding(pad, pad, pad, pad)
        }
        val model = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_bike_model)
            setText(BikeMemory.lastBikeName(this@MainActivity).orEmpty())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val year = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_year)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(problem)
            addView(model)
            addView(year)
        }
        GlassAlertBuilder(this)
            .setTitle(R.string.main_report_a_problem)
            .setMessage(R.string.main_report_problem_message)
            .setView(box)
            .setPositiveButton(R.string.main_share) { _, _ ->
                shareProblemReport(
                    problem.text.toString(),
                    model.text.toString(),
                    year.text.toString(),
                )
            }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    private fun shareProblemReport(problem: String, model: String, year: String) {
        try {
            val diagnostics = buildString {
                appendLine(
                    "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                        "git=${BuildConfig.GIT_HASH}",
                )
                appendLine("profile=${BikeProfileHolder.active.name}")
                appendLine("override=${BikeProfileHolder.profileOverride}")
                appendLine("transport=${AppSettings.transport(this@MainActivity).label}")
                appendLine("margins=${ScreenMargins.summary()}")
                appendLine("forceNonTouch=${BikeProfileHolder.forceNonTouch}")
                appendLine("forceTouch=${BikeProfileHolder.forceTouch}")
                appendLine("ssid=${BikeMemory.lastQr(this@MainActivity)?.ssid ?: "—"}")
                appendLine("phase=${ConnectionState.phase} ${ConnectionState.detail}")
            }
            val text = ProblemReport.file(
                problem = problem.ifBlank { "(not specified)" },
                model = model,
                year = year,
                diagnostics = diagnostics,
                log = LogBus.snapshot(),
            )
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "opencfmoto-report.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ProblemReport.subject(model, BuildConfig.VERSION_NAME))
                putExtra(Intent.EXTRA_TEXT, ProblemReport.body(problem, model, year, diagnostics))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.main_share_problem_report)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.main_share_report_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun log(msg: String) = LogBus.log(msg)

    /** After screen-capture consent: connect using saved bike or scan. */
    private fun startMirrorLink() {
        val saved = BikeMemory.lastQr(this)
        if (saved != null) {
            log("→ Mirror: reusing saved bike '${BikeMemory.lastBikeName(this)}'")
            // Drop AA / Map / old PXC; keep the MediaProjection token we just armed.
            tearDownForModeSwitch(clearMap = true, clearMirror = false)
            applyProfile(saved)
            MirrorOrientationLock.apply(this)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            // Single-app MediaProjection only emits while the shared app is visible. Leaving this
            // Activity on top → capture visible=false → black dash / framesSent=0.
            moveTaskToBack(true)
            Toast.makeText(
                this,
                getString(R.string.main_mirror_leave_shared_app),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            log("→ Mirror: scan the dash QR…")
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
    }

    /**
     * Map / GPX session ready.
     * Always hard-switches: stop previous projection, reopen PXC with Map video (keep Wi‑Fi).
     * Phone Dash preview only when not sending to the bike.
     */
    private fun beginGpxProjection() {
        if (!GpxSession.active) {
            log("→ Map: nothing prepared — open Map / GPX first")
            GpxActivity.start(this)
            return
        }
        pendingAaStart = false
        ensureLocationPermission()
        val live = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING ||
            ConnectionState.phase == Phase.RECONNECTING ||
            ConnectionState.phase == Phase.PXC_CONNECTING ||
            ConnectionState.phase == Phase.STARTING_AA ||
            ConnectionState.phase == Phase.AA_VIDEO_LIVE ||
            ConnectionState.phase == Phase.JOINING_WIFI
        val wantBike = intent?.getBooleanExtra(EXTRA_GPX_TO_BIKE, false) == true || live
        if (wantBike) intent?.removeExtra(EXTRA_GPX_TO_BIKE)
        val saved = BikeMemory.lastQr(this)
        if (wantBike && saved != null) {
            log(
                "→ Map: ${GpxSession.mode} '${GpxSession.trackName}' → " +
                    "'${BikeMemory.lastBikeName(this)}' (hard switch)",
            )
            // Stop AA / mirror / old sockets, keep Wi‑Fi + GpxSession, then fresh PXC.
            tearDownForModeSwitch(clearMap = false, clearMirror = true)
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            return
        }
        log("→ Map: phone Dash preview (${GpxSession.mode} '${GpxSession.trackName}')")
        HudViewActivity.startGpxPreview(this)
    }

    companion object {
        const val EXTRA_START_GPX = "start_gpx"
        private const val EXTRA_HU_STOP_SHOWN = "hu_stop_shown"
        const val EXTRA_BT_TRIGGER = "bt_trigger"
        /** When set with [EXTRA_START_GPX], join the bike even if not already live. */
        const val EXTRA_GPX_TO_BIKE = "gpx_to_bike"
        private const val REQ_BT_FOR_AA = 4
        private const val REQ_HOME_PERMS = 77
        /** Latched once an auto-connect attempt actually starts, so it fires only once per process. */
        @Volatile private var autoConnectStarted = false
        /** So the "idle/not-in-range" reason is logged once, not on every onResume retry. */
        @Volatile private var autoConnectSkipLogged = false
        /** Last time the dash was live (projecting/mirroring) — feeds the "Last sync" tile. */
        @Volatile private var lastLiveAt = 0L
    }
}
