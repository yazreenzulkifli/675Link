// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.GnssStatus
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * Full-screen in-app dash: live AA video when connected, or Map / GPX phone preview when
 * [GpxSession] is active without a bike (debug / offline).
 */
class HudViewActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private lateinit var surface: SurfaceView
    private lateinit var gpxHost: FrameLayout
    private lateinit var hint: TextView
    private lateinit var titleView: TextView
    private var attached = false
    private var noSessionToastAt = 0L
    private var fullscreen = false
    private var gpxPreview = false
    private var gpxUi: GpxDashUi? = null
    /** True while this screen started Android Auto without a bike (see [startAaWithoutBike]). */
    private var noBikeSession = false
    private val noBikeWatch = object : Runnable {
        override fun run() {
            updateNoBikeState()
            updateHeader()
            surface.postDelayed(this, 1_000L)
        }
    }
    private var boundSessionKey: String? = null
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dash view opens in a fixed landscape (no sensor); ⟳ in the top bar toggles to portrait.
        requestedOrientation = LANDSCAPE_ORIENTATION
        setContentView(R.layout.activity_hud_view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.hud_surface)
        gpxHost = findViewById(R.id.hud_gpx_host)
        hint = findViewById(R.id.hud_hint)
        titleView = findViewById(R.id.hud_title)

        insetsController = WindowInsetsControllerCompat(window, findViewById(R.id.hud_root)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Window insets (status bar, camera cutout, nav bar) are stored and folded into the header
        // padding, the pad margins and the stage margins by applyDashChrome.
        val topbar = findViewById<View>(R.id.hud_topbar)
        ViewCompat.setOnApplyWindowInsetsListener(topbar) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (leftInset != bars.left || rightInset != bars.right ||
                topInset != bars.top || bottomInset != bars.bottom
            ) {
                leftInset = bars.left; rightInset = bars.right
                topInset = bars.top; bottomInset = bars.bottom
                applyDashChrome()
            }
            insets
        }
        // Round the live Android Auto video to match the dark stage frame.
        surface.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = if (fullscreen) 0f else dp(24).toFloat()
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        surface.clipToOutline = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (gpxPreview) return
                val pipe = AaVideoBridge.pipeline
                if (pipe == null) {
                    LogBus.log("[HUD] no AA pipeline — preview will show once Android Auto is live")
                    return
                }
                if (!attached) {
                    pipe.setPreviewSurface(holder.surface, width, height)
                    attached = true
                    LogBus.log("[HUD] preview surface attached ${width}x$height")
                } else {
                    pipe.updatePreviewSize(width, height)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (attached) {
                    AaVideoBridge.pipeline?.clearPreviewSurface()
                    attached = false
                    LogBus.log("[HUD] preview surface detached")
                }
            }
        })

        surface.setOnTouchListener { _, e -> onSurfaceTouch(e); true }

        findViewById<View>(R.id.hud_dummy_bike).setOnClickListener { DummyDashActivity.start(this) }
        findViewById<View>(R.id.hud_aa_no_bike).setOnClickListener {
            if (noBikeSession && AaVideoBridge.pipeline != null) reconnectAaWithoutBike()
            else startAaWithoutBike()
        }
        findViewById<View>(R.id.hud_close).setOnClickListener { finish() }
        findViewById<View>(R.id.hud_map_hub).setOnClickListener {
            GpxActivity.start(this)
        }
        findViewById<View>(R.id.hud_toggle_controls).setOnClickListener { toggleControls() }
        findViewById<View>(R.id.hud_fullscreen).setOnClickListener { setFullscreen(!fullscreen) }
        findViewById<View>(R.id.hud_theme).setOnClickListener { cycleMapTheme() }
        // Rotate the phone display for this screen only: forces landscape (bigger, touch-friendly
        // view of a landscape dash) or back to portrait, even if system auto-rotate is off.
        findViewById<View>(R.id.hud_rotate).setOnClickListener {
            landscapeUi = !landscapeUi
            requestedOrientation = if (landscapeUi) LANDSCAPE_ORIENTATION else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            applyDashChrome()
            applySystemBars()
        }
        applyDashChrome()

        findViewById<View>(R.id.hud_knob_back).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.hud_knob_fwd).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.hud_left).setOnClickListener { key(AaInput.KEY_LEFT) }
        // Three-button pad (like the handlebar). Android Auto moves focus with the knob, not
        // D-pad ↑/↓, so ▲/▼ turn the knob; ▶ selects, double-tap or hold ▶ = split-screen
        // dashboard (app list: reach the rail's app-list button with ▲/▼, then ▶).
        findViewById<View>(R.id.hud_right).apply {
            // A single tap waits out the double-tap window before sending OK, so a double tap
            // never also selects the item.
            var pendingOk: Runnable? = null
            setOnClickListener { v ->
                val pending = pendingOk
                if (pending != null) {
                    v.removeCallbacks(pending)
                    pendingOk = null
                    goToAaDashboard()
                } else {
                    val r = Runnable { pendingOk = null; key(AaInput.KEY_ENTER) }
                    pendingOk = r
                    v.postDelayed(r, DOUBLE_TAP_MS)
                }
            }
            setOnLongClickListener { v ->
                pendingOk?.let { v.removeCallbacks(it) }
                pendingOk = null
                goToAaDashboard()
                true
            }
        }
        findViewById<View>(R.id.hud_up).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.hud_down).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.hud_ok).setOnClickListener { key(AaInput.KEY_ENTER) }
        findViewById<View>(R.id.hud_back).setOnClickListener { key(AaInput.KEY_BACK) }
        findViewById<View>(R.id.hud_home).setOnClickListener { key(AaInput.KEY_HOME) }
        findViewById<View>(R.id.hud_voice).setOnClickListener {
            ensureMicPermission()
            key(AaInput.KEY_ASSISTANT)
        }
    }

    private var landscapeUi = true
    private var leftInset = 0
    private var rightInset = 0
    private var topInset = 0
    private var bottomInset = 0

    /** Landscape cockpit is immersive (swipe reveals bars); portrait keeps the system bars. */
    private fun applySystemBars() {
        if (landscapeUi && !gpxPreview) insetsController.hide(WindowInsetsCompat.Type.systemBars())
        else if (!gpxPreview) insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Lays out the cockpit chrome around the Android Auto stage.
     * Landscape: glass header strip on top, the ▲ Select ▼ pad as a column bottom-left, and the dark
     * stage (video) filling the rest, so nothing covers Android Auto.
     * Portrait: slimmer header, pad centred along the bottom.
     */
    private fun applyDashChrome() {
        val land = landscapeUi
        val topbar = findViewById<LinearLayout>(R.id.hud_topbar)
        val controls = findViewById<LinearLayout>(R.id.hud_controls)
        val inner = findViewById<LinearLayout>(R.id.hud_controls_inner)
        val topShown = topbar.visibility == View.VISIBLE
        val padShown = controls.visibility == View.VISIBLE

        // Header: inset-aware padding. Portrait drops the centre telemetry and some labels to fit.
        topbar.setPadding(dp(12) + leftInset, dp(6) + topInset, dp(12) + rightInset, dp(6))
        titleView.visibility = if (land) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hud_cluster_center).visibility = if (land) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.hud_gps_chip).visibility = if (land) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hud_toggle_controls).visibility =
            if (gpxPreview || !land) View.GONE else View.VISIBLE
        topbar.elevation = dp(8).toFloat()

        // Pad: bottom-left column (landscape) / bottom-centre row (portrait).
        val gap = dp(10)
        controls.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (land) {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = gap + leftInset
            } else {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            bottomMargin = gap + bottomInset
        }
        inner.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until inner.childCount) {
            val b = inner.getChildAt(i)
            if (b.visibility == View.GONE) continue
            b.layoutParams = LinearLayout.LayoutParams(
                if (land) dp(60) else LinearLayout.LayoutParams.WRAP_CONTENT, dp(52),
            ).apply {
                if (land) { topMargin = dp(4); bottomMargin = dp(4) }
                else { marginStart = dp(4); marginEnd = dp(4) }
            }
            (b as? TextView)?.minWidth = if (land) 0 else dp(72)
        }

        // Stage (frame + video + empty state) sits between the chrome. The Map preview is full-bleed.
        val padW = dp(8 + 60 + 8)
        val stageT = if (fullscreen || !topShown) 0 else dp(56) + topInset
        val stageL = when {
            fullscreen -> 0
            land && padShown -> gap + leftInset + padW + gap
            land -> gap + leftInset
            else -> dp(10)
        }
        val stageR = if (fullscreen) 0 else dp(12) + rightInset
        val stageB = when {
            fullscreen -> 0
            !land && padShown -> gap + bottomInset + dp(52 + 16) + gap
            else -> gap + bottomInset
        }
        for (v in listOf<View>(findViewById(R.id.hud_stage), surface, findViewById(R.id.hud_empty))) {
            v.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(stageL, stageT, stageR, stageB) }
        }
        surface.invalidateOutline()
        gpxHost.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    // ---- Header telemetry (status badge, bike, phone battery, GPS fix) -------------------------

    private var dotPulse: ObjectAnimator? = null
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var gpsSats = -1   // -1 = no data yet / unavailable

    private fun tint(v: View, color: Int) { v.backgroundTintList = ColorStateList.valueOf(color) }

    private fun updateHeader() {
        // Android Auto status badge
        val badge = findViewById<View>(R.id.hud_status_badge)
        val dot = findViewById<View>(R.id.hud_status_dot)
        val text = findViewById<TextView>(R.id.hud_status_text)
        val phase = ConnectionState.phase
        val live = AaVideoBridge.aaSessionLive || phase == Phase.STREAMING || phase == Phase.MIRRORING
        when {
            live -> {
                text.text = "AA Projection Active"; text.setTextColor(Color.parseColor("#047857"))
                tint(badge, Color.parseColor("#DDF5EC")); tint(dot, Color.parseColor("#10B981"))
                if (dotPulse?.isRunning != true) dotPulse?.start()
            }
            AaVideoBridge.pipeline != null || phase.busy -> {
                text.text = getString(phase.labelRes); text.setTextColor(Color.parseColor("#B45309"))
                tint(badge, Color.parseColor("#FEF3C7")); tint(dot, Color.parseColor("#F59E0B"))
                if (dotPulse?.isRunning != true) dotPulse?.start()
            }
            else -> {
                text.text = "AA offline"; text.setTextColor(Color.parseColor("#64748B"))
                tint(badge, Color.parseColor("#EEF2F6")); tint(dot, Color.parseColor("#94A3B8"))
                dotPulse?.cancel(); dot.alpha = 1f
            }
        }

        // Bike link (name comes from the connection detail when there is one)
        val detail = ConnectionState.detail.trim()
        findViewById<TextView>(R.id.hud_bike_chip).text =
            if (detail.isNotEmpty() && (live || phase.busy)) "🏍  $detail" else "🏍  No bike"

        // Phone battery (the bike does not report its own battery over this link)
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        findViewById<TextView>(R.id.hud_batt_chip).text =
            if (level >= 0) "Phone ${if (charging) "⚡" else "●"} ${level * 100 / scale}%" else "Phone —"

        // GPS fix
        findViewById<TextView>(R.id.hud_gps_chip).text = when {
            gnssCallback == null -> "GPS off"
            gpsSats >= 4 -> "📍 3D FIX"
            gpsSats >= 0 -> "GPS searching"
            else -> "GPS —"
        }
    }

    @Suppress("DEPRECATION")
    private fun startGnss() {
        if (gnssCallback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val cb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                    gpsSats = used
                }
                override fun onStopped() { gpsSats = 0 }
            }
            if (lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))) gnssCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun stopGnss() {
        val cb = gnssCallback ?: return
        gnssCallback = null
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).unregisterGnssStatusCallback(cb)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Force rebuild when Hub opens a new GPX / nav session into an existing preview.
        refreshMode(forceReload = true)
    }

    override fun onResume() {
        super.onResume()
        refreshMode(forceReload = false)
        startGnss()
        if (dotPulse == null) {
            dotPulse = ObjectAnimator.ofFloat(findViewById<View>(R.id.hud_status_dot), View.ALPHA, 1f, 0.25f).apply {
                duration = 900; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        surface.removeCallbacks(noBikeWatch)
        surface.post(noBikeWatch)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // We keep configChanges so the map isn't torn down — still reflow the Waze chrome.
        gpxUi?.onConfigurationChanged()
    }

    /** Why a Dash view control did nothing: AA up but its session dropped, or no AA at all. */
    private fun noInputMessage(): String =
        if (AaVideoBridge.pipeline != null) "Android Auto isn't connected right now"
        else "Start Android Auto first"

    override fun onPause() {
        surface.removeCallbacks(noBikeWatch)
        stopGnss()
        dotPulse?.cancel()
        if (isFinishing) stopGpxPreview()
        super.onPause()
    }

    /**
     * No-bike mode: when Android Auto drops its session the compositor keeps the last frame, so
     * the screen looks alive but input goes nowhere. Say so and offer a reconnect.
     */
    private fun updateNoBikeState() {
        if (!noBikeSession || gpxPreview) return
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.hud_aa_no_bike)
        if (AaVideoBridge.pipeline == null) {
            // Stopped (home Stop / notification Stop) — back to the idle Start button.
            noBikeSession = false
            btn.text = "Start Android Auto (no bike)"
            hint.setText(R.string.hud_no_signal)
            refreshMode(forceReload = false)
            return
        }
        val dropped = AaVideoBridge.aaSessionSeen && !AaVideoBridge.aaSessionLive
        if (dropped) {
            AaVideoBridge.aaScreen = AaVideoBridge.AaScreen.UNKNOWN
            hint.text = "Android Auto disconnected.\nIf it asked for a permission, allow it on the phone first."
            hint.visibility = View.VISIBLE
            btn.text = "Reconnect Android Auto"
            btn.visibility = View.VISIBLE
        } else if (AaVideoBridge.aaSessionLive) {
            hint.visibility = View.GONE
            btn.visibility = View.GONE
        }
    }

    /** The receiver is still listening after a drop — just ask Android Auto to project again. */
    private fun reconnectAaWithoutBike() {
        LogBus.log("[AA-NOBIKE] reconnect requested")
        findViewById<View>(R.id.hud_aa_no_bike).visibility = View.GONE
        hint.text = "Reconnecting Android Auto…"
        AndroidAutoService.redialAndroidAuto()
        try { dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = LogBus::log) }
        catch (e: Exception) { LogBus.log("[AA-NOBIKE] reconnect trigger failed: $e") }
    }

    /**
     * Run Google Android Auto into this screen only: the embedded receiver + compositor preview,
     * with no bike Wi‑Fi join and no PXC probe ([BikeLink] is never told AA is steady). Stop it
     * with the home Stop button or the notification's Stop.
     */
    private fun startAaWithoutBike() {
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            Toast.makeText(this, "Google Android Auto isn't installed on this phone", Toast.LENGTH_LONG).show()
            return
        }
        if (ConnectionState.phase.busy || AaVideoBridge.pipeline != null) {
            LogBus.log("[AA-NOBIKE] something is already projecting — ignoring")
            return
        }
        // HUR (START_WIRELESS_PROJECTION) needs Nearby devices on Android 12+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_NEARBY)
            Toast.makeText(this, "Allow Nearby devices, then tap again", Toast.LENGTH_LONG).show()
            return
        }
        // A stale Map session would make this screen show the map instead of AA.
        if (GpxSession.active) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        LogBus.log("[AA-NOBIKE] starting Android Auto on the phone only (no bike)")
        noBikeSession = true
        ConnectionState.set(Phase.STARTING_AA, "Phone only (no bike)")
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            ConnectionState.set(Phase.AA_VIDEO_LIVE, "Phone only (no bike)")
            AndroidAutoService.upgradeForegroundForMicrophone()
            LogBus.log("[AA-NOBIKE] Android Auto video is live (bike link not started)")
        }
        AndroidAutoService.start(this)
        findViewById<View>(R.id.hud_aa_no_bike).visibility = View.GONE
        hint.text = "Starting Android Auto…"
        waitForPipelineThenAttach(System.currentTimeMillis())

        // Same self-mode trigger + one retry as the bike Connect flow, from this foreground screen.
        surface.postDelayed({
            try { dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = LogBus::log) }
            catch (e: Exception) { LogBus.log("[AA-NOBIKE] self-mode trigger failed: $e") }
        }, 900)
        surface.postDelayed({
            if (isFinishing || AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
            if (!AndroidAutoService.isRunning) return@postDelayed
            LogBus.log("[AA-NOBIKE] no video yet — re-triggering self-mode")
            try { dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = LogBus::log) }
            catch (e: Exception) { LogBus.log("[AA-NOBIKE] self-mode re-trigger failed: $e") }
        }, 4_500)
        surface.postDelayed({
            if (isFinishing || AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
            val p = ConnectionState.phase
            if (p != Phase.STARTING_AA) return@postDelayed
            LogBus.log("[AA-NOBIKE] Android Auto never attached")
            ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_aa_not_started))
            hint.text = "Android Auto didn't start. Open Android Auto once on the phone, then try again."
        }, 18_000)
    }

    /** The service publishes its pipeline asynchronously; attach the preview once it exists. */
    private fun waitForPipelineThenAttach(startedAt: Long) {
        if (isFinishing || isDestroyed) return
        val pipe = AaVideoBridge.pipeline
        if (pipe != null) {
            refreshMode(forceReload = false)
            val holder = surface.holder
            if (!attached && holder.surface.isValid && surface.width > 0 && surface.height > 0) {
                pipe.setPreviewSurface(holder.surface, surface.width, surface.height)
                attached = true
                LogBus.log("[HUD] preview surface attached ${surface.width}x${surface.height}")
            }
            return
        }
        if (System.currentTimeMillis() - startedAt > 10_000L) {
            LogBus.log("[AA-NOBIKE] service never published a pipeline")
            return
        }
        surface.postDelayed({ waitForPipelineThenAttach(startedAt) }, 300)
    }

    override fun onDestroy() {
        stopGpxPreview()
        super.onDestroy()
    }

    private fun sessionKey(): String =
        "${GpxSession.mode}|${GpxSession.trackName}|${GpxSession.trackFile?.absolutePath}|${GpxSession.destination?.lat}|${GpxSession.destination?.lon}"

    private fun refreshMode(forceReload: Boolean) {
        // Live Android Auto video always wins: the phone Map preview is only a fallback for when
        // there's no bike pipeline. This keeps a previously-opened preview from sticking over the
        // real HUD once the bike connects.
        val live = AaVideoBridge.pipeline != null
        val wantGpx = !live && GpxSession.active
        if (wantGpx) {
            val key = sessionKey()
            val needReload = forceReload ||
                intent.getBooleanExtra(EXTRA_FORCE_RELOAD, false) ||
                key != boundSessionKey
            intent.removeExtra(EXTRA_FORCE_RELOAD)
            startGpxPreview(reload = needReload)
        } else {
            stopGpxPreview()
            val live = AaVideoBridge.pipeline != null
            hint.visibility = if (live) View.GONE else View.VISIBLE
            findViewById<View>(R.id.hud_aa_no_bike).visibility =
                View.GONE   // NO SIGNAL screen: no start buttons
            val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            findViewById<View>(R.id.hud_dummy_bike).visibility =
                View.GONE
            surface.visibility = View.VISIBLE
            gpxHost.visibility = View.GONE
            findViewById<View>(R.id.hud_topbar).visibility =
                if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.hud_map_hub).visibility = View.GONE
            findViewById<View>(R.id.hud_controls).visibility =
                if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.hud_stage).visibility = View.VISIBLE
            findViewById<View>(R.id.hud_empty).visibility = View.VISIBLE
            applyDashChrome()
            applySystemBars()
        }
    }

    private fun startGpxPreview(reload: Boolean) {
        if (gpxPreview && gpxUi != null && !reload) {
            hint.visibility = View.GONE
            return
        }
        stopGpxPreview()
        gpxPreview = true
        gpxHost.removeAllViews()
        val root = try {
            LayoutInflater.from(this).inflate(R.layout.presentation_gpx, gpxHost, false)
        } catch (e: Exception) {
            gpxPreview = false
            LogBus.log("[HUD] inflate map chrome failed: $e")
            Toast.makeText(this, "Map UI failed to open", Toast.LENGTH_LONG).show()
            return
        }
        gpxHost.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val ui = GpxDashUi(this, root, { LogBus.log(it) }, isAlive = { !isFinishing && gpxPreview })
        val ok = try {
            ui.bind()
        } catch (e: Exception) {
            LogBus.log("[HUD] bind map failed: $e")
            false
        } catch (oom: OutOfMemoryError) {
            LogBus.log("[HUD] bind map OOM: $oom")
            false
        }
        if (!ok) {
            gpxPreview = false
            try { ui.release() } catch (_: Exception) {}
            gpxHost.removeAllViews()
            Toast.makeText(this, "Could not load map — try again from Map hub", Toast.LENGTH_LONG).show()
            return
        }
        gpxUi = ui
        boundSessionKey = sessionKey()
        surface.visibility = View.GONE
        gpxHost.visibility = View.VISIBLE
        hint.visibility = View.GONE
        // Map chrome has its own search / Hub / close — hide Dash topbar so search
        // isn't crushed under the punch-hole + app bar.
        findViewById<View>(R.id.hud_topbar).visibility = View.GONE
        findViewById<View>(R.id.hud_map_hub).visibility = View.GONE
        findViewById<View>(R.id.hud_controls).visibility = View.GONE
        findViewById<View>(R.id.hud_toggle_controls).visibility = View.GONE
        // Waze-style: ride immersive — no system status bar eating the map.
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        findViewById<View>(R.id.hud_stage).visibility = View.GONE
        findViewById<View>(R.id.hud_empty).visibility = View.GONE
        applyDashChrome()
        LogBus.log("[HUD] Map preview bound (${GpxSession.mode} '${GpxSession.trackName}')")
    }

    private fun stopGpxPreview() {
        if (!gpxPreview && gpxUi == null) {
            boundSessionKey = null
            return
        }
        gpxPreview = false
        boundSessionKey = null
        try { gpxUi?.release() } catch (_: Exception) {}
        gpxUi = null
        gpxHost.removeAllViews()
        gpxHost.visibility = View.GONE
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun cycleMapTheme() {
        val theme = NightPrefs.cycle(this)
        val on = NightPrefs.isNightNow(this)
        AaVideoBridge.nightSink?.invoke(on)
        gpxUi?.applyTheme()
        val extra = if (theme == MapTheme.AUTO) " (${if (on) "night" else "day"} now)" else ""
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)) + extra, Toast.LENGTH_SHORT).show()
    }

    private fun toggleControls() {
        if (gpxPreview) return
        val bar = findViewById<View>(R.id.hud_controls)
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        applyDashChrome()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        findViewById<View>(R.id.hud_topbar).visibility = if (on) View.GONE else View.VISIBLE
        if (!gpxPreview) {
            findViewById<View>(R.id.hud_controls).visibility = if (on) View.GONE else View.VISIBLE
        }
        applyDashChrome()
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Fullscreen — press Back to exit", Toast.LENGTH_SHORT).show()
        } else {
            applySystemBars()
        }
    }

    private fun onSurfaceTouch(e: MotionEvent) {
        if (gpxPreview) return
        val sink = AaVideoBridge.previewTouchSink
        if (sink == null) {
            val now = System.currentTimeMillis()
            if (now - noSessionToastAt > 3000) {
                noSessionToastAt = now
                Toast.makeText(this, noInputMessage(), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) AaVideoBridge.aaScreen = AaVideoBridge.AaScreen.UNKNOWN
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_DOWN)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_MOVE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_UP)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_UP)
        }
    }

    private fun forward(
        sink: (Int, Int, Int, Int) -> Unit,
        e: MotionEvent,
        index: Int,
        action: Int,
    ) {
        val src = mapToSource(e.getX(index), e.getY(index)) ?: return
        sink(action, e.getPointerId(index), src.first, src.second)
    }

    private fun mapToSource(vx: Float, vy: Float): Pair<Int, Int>? {
        val vw = surface.width
        val vh = surface.height
        val sw = BikeProfileHolder.aaUsableWidth
        val sh = BikeProfileHolder.aaUsableHeight
        if (vw == 0 || vh == 0 || sw == 0 || sh == 0) return null

        val srcAspect = sw.toFloat() / sh
        val viewAspect = vw.toFloat() / vh
        val rectW: Int
        val rectH: Int
        if (srcAspect < viewAspect) {
            rectH = vh; rectW = Math.round(vh * srcAspect)
        } else {
            rectW = vw; rectH = Math.round(vw / srcAspect)
        }
        val rectX = (vw - rectW) / 2
        val rectY = (vh - rectH) / 2

        val rx = vx - rectX
        val ry = vy - rectY
        if (rx < 0 || ry < 0 || rx >= rectW || ry >= rectH) return null
        val sx = (rx * sw / rectW).toInt().coerceIn(0, sw - 1)
        val sy = (ry * sh / rectH).toInt().coerceIn(0, sh - 1)
        return (sx + BikeProfileHolder.navCropLeft) to sy
    }

    private fun key(code: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            Toast.makeText(this, noInputMessage(), Toast.LENGTH_SHORT).show()
        } else {
            sink(code)   // AaReceiver updates AaVideoBridge.aaScreen from every key
        }
    }

    /**
     * ▶ double tap: land on Android Auto's split-screen dashboard. AA's Home is a toggle
     * (dashboard ⇄ app list), so skip it when the shared guess says we're already there.
     */
    private fun goToAaDashboard() {
        when (AaVideoBridge.aaScreen) {
            AaVideoBridge.AaScreen.DASHBOARD -> if (AaVideoBridge.keySink != null) {
                // Home here would open the app list, so stay put.
                LogBus.log("[HUD] already on the Android Auto dashboard — not sending Home")
                return
            }
            // Home on the app list does nothing (AA 18); Back closes it to the dashboard.
            AaVideoBridge.AaScreen.APP_LIST -> {
                LogBus.log("[HUD] on the app list — sending Back to reach the dashboard")
                key(AaInput.KEY_BACK)
                return
            }
            else -> {}
        }
        key(AaInput.KEY_HOME)
    }

    private fun scroll(delta: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            Toast.makeText(this, noInputMessage(), Toast.LENGTH_SHORT).show()
        } else {
            sink(delta)
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    companion object {
        /** Fixed landscape (not sensor-driven, not reversed). */
        private const val LANDSCAPE_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        private const val REQ_MIC = 72
        private const val REQ_NEARBY = 73
        /** ▶ double-tap window: two taps within this = Home, otherwise one tap = OK. */
        private const val DOUBLE_TAP_MS = 300L
        const val EXTRA_GPX_PREVIEW = "gpx_preview"
        const val EXTRA_FORCE_RELOAD = "gpx_force_reload"

        fun startGpxPreview(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, HudViewActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_GPX_PREVIEW, true)
                    putExtra(EXTRA_FORCE_RELOAD, true)
                },
            )
        }
    }
}
