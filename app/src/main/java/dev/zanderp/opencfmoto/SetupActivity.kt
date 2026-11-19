// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * One-time guided setup + persistent settings. Shows a live checklist of the prerequisites for
 * Android Auto projection (install, permissions, head-unit mode) and the display/battery options.
 * Each selector reflects the saved choice with a highlighted segment so the current setting is
 * always visible. Status is re-evaluated in [onResume] so returning from a permission prompt or the
 * Play Store re-ticks the steps.
 */
class SetupActivity : AppCompatActivity() {

    private var currentTab = R.id.tab_setup
    private lateinit var step1Title: TextView
    private lateinit var step1Btn: MaterialButton
    private lateinit var step2Title: TextView
    private lateinit var step2Btn: MaterialButton
    private lateinit var qualityDesc: TextView
    private lateinit var fitDesc: TextView
    private lateinit var mirrorOrientDesc: TextView
    private lateinit var powerDesc: TextView
    private lateinit var resDesc: TextView
    private lateinit var dpiDesc: TextView
    private lateinit var themeDesc: TextView
    private lateinit var dblTapDesc: TextView
    private lateinit var holdsDesc: TextView
    private lateinit var holdDesc: TextView
    private lateinit var nonTouchDesc: TextView
    private lateinit var forceTouchDesc: TextView
    private lateinit var btStatus: TextView
    private lateinit var resumeBtn: MaterialButton

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importSettingsFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Category tabs — one group of cards visible at a time.
        val tabGroups = linkedMapOf(
            R.id.tab_setup to listOf(R.id.card_start, R.id.card_bikes),
            R.id.tab_display to listOf(R.id.card_display, R.id.card_battery),
            R.id.tab_controls to listOf(R.id.card_handlebar, R.id.card_bluetooth),
            R.id.tab_connection to listOf(R.id.card_startup, R.id.card_wifi),
            R.id.tab_notifications to listOf(R.id.card_notifications),
        )
        val allCards = tabGroups.values.flatten()
        fun selectTab(tabId: Int) {
            currentTab = tabId
            allCards.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
            tabGroups[tabId]?.forEach { findViewById<android.view.View>(it).visibility = android.view.View.VISIBLE }
            for (id in tabGroups.keys) {
                val on = id == tabId
                findViewById<MaterialButton>(id).apply {
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@SetupActivity, if (on) R.color.home_cyan else R.color.home_card))
                    setTextColor(ContextCompat.getColor(this@SetupActivity, if (on) R.color.white else R.color.home_text))
                }
            }
            findViewById<android.widget.ScrollView>(R.id.setup_root).scrollTo(0, 0)
        }
        for (id in tabGroups.keys) findViewById<MaterialButton>(id).setOnClickListener { selectTab(id) }
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_setup)

        // Draw content clear of the status/navigation bars (edge-to-edge is enforced on newer Android).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        // Light "glaze" screen → dark status/nav icons, like Home.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        findViewById<android.view.View>(R.id.setup_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.setup_refresh).setOnClickListener {
            refresh(); refreshOptions(); refreshBluetooth()
        }

        step1Title = findViewById(R.id.step1_title)
        step1Btn = findViewById(R.id.step1_btn)
        step2Title = findViewById(R.id.step2_title)
        step2Btn = findViewById(R.id.step2_btn)
        qualityDesc = findViewById(R.id.quality_desc)
        fitDesc = findViewById(R.id.fit_desc)
        mirrorOrientDesc = findViewById(R.id.mirror_orient_desc)
        powerDesc = findViewById(R.id.power_desc)
        resDesc = findViewById(R.id.res_desc)
        dpiDesc = findViewById(R.id.dpi_desc)
        themeDesc = findViewById(R.id.theme_desc)
        dblTapDesc = findViewById(R.id.dbltap_desc)
        holdsDesc = findViewById(R.id.holds_desc)
        holdDesc = findViewById(R.id.hold_desc)
        nonTouchDesc = findViewById(R.id.nontouch_desc)
        forceTouchDesc = findViewById(R.id.forcetouch_desc)
        btStatus = findViewById(R.id.bt_status)
        resumeBtn = findViewById(R.id.resume_perm_btn)

        step1Btn.setOnClickListener { openAndroidAuto() }
        step2Btn.setOnClickListener { requestMissingPermissions() }
        findViewById<MaterialButton>(R.id.step2_settings_btn).setOnClickListener { openAppSettings() }
        findViewById<MaterialButton>(R.id.step3_btn).setOnClickListener { openAndroidAutoSettings() }

        findViewById<MaterialButton>(R.id.quality_smooth).setOnClickListener { setQuality(VideoQuality.SMOOTH) }
        findViewById<MaterialButton>(R.id.quality_balanced).setOnClickListener { setQuality(VideoQuality.BALANCED) }
        findViewById<MaterialButton>(R.id.quality_sharp).setOnClickListener { setQuality(VideoQuality.SHARP) }
        findViewById<MaterialButton>(R.id.fit_fill).setOnClickListener { setFit(ScreenFit.FILL) }
        findViewById<MaterialButton>(R.id.fit_fit).setOnClickListener { setFit(ScreenFit.FIT) }
        findViewById<MaterialButton>(R.id.fit_stretch).setOnClickListener { setFit(ScreenFit.STRETCH) }
        findViewById<MaterialButton>(R.id.mirror_orient_dash).setOnClickListener {
            setMirrorOrientation(MirrorOrientation.MATCH_DASH)
        }
        findViewById<MaterialButton>(R.id.mirror_orient_follow).setOnClickListener {
            setMirrorOrientation(MirrorOrientation.FOLLOW)
        }
        findViewById<MaterialButton>(R.id.mirror_orient_land).setOnClickListener {
            setMirrorOrientation(MirrorOrientation.LANDSCAPE)
        }
        findViewById<MaterialButton>(R.id.mirror_orient_port).setOnClickListener {
            setMirrorOrientation(MirrorOrientation.PORTRAIT)
        }
        findViewById<MaterialButton>(R.id.power_auto).setOnClickListener { setPower(PowerMode.AUTO) }
        findViewById<MaterialButton>(R.id.power_smooth).setOnClickListener { setPower(PowerMode.SMOOTH) }
        findViewById<MaterialButton>(R.id.power_balanced).setOnClickListener { setPower(PowerMode.BALANCED) }
        findViewById<MaterialButton>(R.id.power_saver).setOnClickListener { setPower(PowerMode.SAVER) }
        findViewById<MaterialButton>(R.id.res_auto).setOnClickListener { setResolution(ResolutionMode.AUTO) }
        findViewById<MaterialButton>(R.id.res_land_sd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_SD) }
        findViewById<MaterialButton>(R.id.res_land_hd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_HD) }
        findViewById<MaterialButton>(R.id.res_port_sd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_SD) }
        findViewById<MaterialButton>(R.id.res_port_hd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_HD) }
        findViewById<MaterialButton>(R.id.dpi_auto).setOnClickListener { setAaDpi(null) }
        findViewById<MaterialButton>(R.id.dpi_160).setOnClickListener { setAaDpi(160) }
        findViewById<MaterialButton>(R.id.dpi_180).setOnClickListener { setAaDpi(180) }
        findViewById<MaterialButton>(R.id.dpi_240).setOnClickListener { setAaDpi(240) }
        findViewById<MaterialButton>(R.id.dpi_320).setOnClickListener { setAaDpi(320) }
        findViewById<MaterialButton>(R.id.btn_bt_trigger).setOnClickListener { pickBtTrigger() }
        findViewById<MaterialButton>(R.id.theme_auto).setOnClickListener { setMapTheme(MapTheme.AUTO) }
        findViewById<MaterialButton>(R.id.theme_day).setOnClickListener { setMapTheme(MapTheme.DAY) }
        findViewById<MaterialButton>(R.id.theme_night).setOnClickListener { setMapTheme(MapTheme.NIGHT) }
        setupHandlebarToggle()
        findViewById<MaterialButton>(R.id.dbltap_fast).setOnClickListener { setDoubleTap(DoubleTapDelay.FAST) }
        findViewById<MaterialButton>(R.id.dbltap_normal).setOnClickListener { setDoubleTap(DoubleTapDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.dbltap_slow).setOnClickListener { setDoubleTap(DoubleTapDelay.SLOW) }
        findViewById<MaterialButton>(R.id.dbltap_very_slow).setOnClickListener {
            setDoubleTap(DoubleTapDelay.VERY_SLOW)
        }
        findViewById<MaterialButton>(R.id.holds_on).setOnClickListener { setHoldsEnabled(true) }
        findViewById<MaterialButton>(R.id.holds_off).setOnClickListener { setHoldsEnabled(false) }
        findViewById<MaterialButton>(R.id.hold_short).setOnClickListener { setLongPress(LongPressDelay.SHORT) }
        findViewById<MaterialButton>(R.id.hold_normal).setOnClickListener { setLongPress(LongPressDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.hold_long).setOnClickListener { setLongPress(LongPressDelay.LONG) }
        findViewById<MaterialButton>(R.id.autoconnect_on).setOnClickListener { setAutoConnect(true) }
        findViewById<MaterialButton>(R.id.autoconnect_off).setOnClickListener { setAutoConnect(false) }
        findViewById<MaterialButton>(R.id.recovery_on).setOnClickListener { setAutoRecovery(true) }
        findViewById<MaterialButton>(R.id.recovery_off).setOnClickListener { setAutoRecovery(false) }
        findViewById<MaterialButton>(R.id.idlereminder_on).setOnClickListener { setIdleReminder(true) }
        findViewById<MaterialButton>(R.id.idlereminder_off).setOnClickListener { setIdleReminder(false) }
        findViewById<MaterialButton>(R.id.idlereminder_test).setOnClickListener {
            // Fires the real reminder right away (works even when the reminder is set Off), so riders
            // can check sound / pop-up. Its Turn off button really stops projection if it's running.
            val ok = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled() &&
                AndroidAutoService.postIdleReminder(this)
            Toast.makeText(this,
                if (ok) R.string.setup_idle_reminder_test_sent else R.string.setup_idle_reminder_test_blocked,
                Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.notif_system_btn).setOnClickListener {
            val i = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            try { startActivity(i) } catch (_: Exception) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)))
            }
        }
        findViewById<MaterialButton>(R.id.logtrips_on).setOnClickListener { setLogTrips(true) }
        findViewById<MaterialButton>(R.id.logtrips_off).setOnClickListener { setLogTrips(false) }
        findViewById<MaterialButton>(R.id.nontouch_on).setOnClickListener { setForceNonTouch(true) }
        findViewById<MaterialButton>(R.id.nontouch_off).setOnClickListener { setForceNonTouch(false) }
        findViewById<MaterialButton>(R.id.forcetouch_on).setOnClickListener { setForceTouch(true) }
        findViewById<MaterialButton>(R.id.forcetouch_off).setOnClickListener { setForceTouch(false) }
        findViewById<MaterialButton>(R.id.btn_screen_margins).setOnClickListener { ScreenMarginsActivity.start(this) }
        findViewById<MaterialButton>(R.id.btn_custom_resolution).setOnClickListener { CustomResolutionActivity.start(this) }
        findViewById<MaterialButton>(R.id.btn_usb_probe).setOnClickListener {
            startActivity(android.content.Intent(this, UsbProbeActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.transport_auto).setOnClickListener { setTransport(WifiTransport.AUTO) }
        findViewById<MaterialButton>(R.id.transport_ap).setOnClickListener { setTransport(WifiTransport.AP) }
        findViewById<MaterialButton>(R.id.transport_p2p).setOnClickListener { setTransport(WifiTransport.P2P) }
        findViewById<MaterialButton>(R.id.secrets_on).setOnClickListener { setSecrets(true) }
        findViewById<MaterialButton>(R.id.secrets_off).setOnClickListener { setSecrets(false) }
        findViewById<MaterialButton>(R.id.telemetry_on).setOnClickListener { setTelemetry(true) }
        findViewById<MaterialButton>(R.id.telemetry_off).setOnClickListener { setTelemetry(false) }
        findViewById<MaterialButton>(R.id.settings_share).setOnClickListener { shareSettingsJson() }
        findViewById<MaterialButton>(R.id.settings_import).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.bt_settings_btn).setOnClickListener { BluetoothHelper.openBluetoothSettings(this) }
        resumeBtn.setOnClickListener { requestOverlayPermission() }

        findViewById<MaterialButton>(R.id.setup_done_btn).setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    /** Write the portable settings JSON and open the system share sheet (Discord, Drive, …). */
    private fun shareSettingsJson() {
        try {
            val json = SettingsBackup.exportJson(this)
            val dir = File(cacheDir, "settings").apply { mkdirs() }
            val file = File(dir, SettingsBackup.suggestedFileName(this))
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val profile = ProfilePrefs.get(this).shortLabel
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "OpenCfMoto bike tuning — $profile")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "OpenCfMoto bike tuning ($profile) — profile / resolution / margins / buttons " +
                        "(no passwords, no bike name/SSID).\n" +
                        "Import via Setup → Wi‑Fi & logs → Import…",
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, "Share settings JSON"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Empty file", Toast.LENGTH_SHORT).show()
                return
            }
            val onto = BikeMemory.lastBikeName(this) ?: "the selected bike"
            AlertDialog.Builder(this)
                .setTitle("Import settings?")
                .setMessage(
                    "Replace bike tuning for $onto — profile, resolution, fit, power, margins, " +
                        "handlebar buttons, Control AA, non-touch, Wi‑Fi transport?\n\n" +
                        "Personal prefs (map theme, saved places, auto-connect, …) are left alone. " +
                        "Wi‑Fi passwords are never imported."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Import") { _, _ ->
                    val result = SettingsBackup.importJson(this, text)
                    refreshOptions()
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun setQuality(q: VideoQuality) {
        VideoPrefs.set(this, q)
        refreshOptions()
        toast(getString(R.string.setup_toast_video_quality, getString(q.labelRes)))
    }

    private fun setFit(f: ScreenFit) {
        VideoPrefs.setFit(this, f)
        refreshOptions()
        toast(getString(R.string.setup_toast_screen_fit, getString(f.labelRes)))
    }

    private fun setMirrorOrientation(m: MirrorOrientation) {
        VideoPrefs.setMirrorOrientation(this, m)
        refreshOptions()
        toast(getString(R.string.setup_toast_mirror_orient, getString(m.labelRes)))
    }

    private fun setPower(m: PowerMode) {
        VideoPrefs.setPower(this, m)
        refreshOptions()
        toast(getString(R.string.setup_toast_power_mode, getString(m.labelRes)))
    }

    private fun setResolution(m: ResolutionMode) {
        VideoPrefs.setResolution(this, m)
        refreshOptions()
        toast(getString(R.string.setup_toast_resolution, getString(m.labelRes)))
    }

    private fun setAaDpi(dpi: Int?) {
        VideoPrefs.setDpiOverride(this, dpi)
        refreshOptions()
        toast(
            if (dpi == null) getString(R.string.setup_aa_dpi_auto)
            else getString(R.string.setup_aa_dpi) + ": $dpi",
        )
    }

    private fun pickBtTrigger() {
        val bonded = BluetoothHelper.bondedDevices(this)
        val labels = mutableListOf(getString(R.string.setup_bt_trigger_none))
        val macs = mutableListOf<String?>(null)
        val names = mutableListOf<String?>(null)
        for ((mac, name) in bonded) {
            labels.add("$name ($mac)")
            macs.add(mac)
            names.add(name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_bt_trigger_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                AppSettings.setBtTrigger(this, macs[which], names[which])
                refreshOptions()
                val toast = if (macs[which] == null) getString(R.string.setup_bt_trigger_none)
                else getString(R.string.setup_bt_trigger_set, names[which])
                Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Map day/night applies live (no reconnect needed) — push it to any running AA session. */
    /** Handlebar-button mode toggle + "Customize buttons" (moved here from the Controls page). */
    private fun setupHandlebarToggle() {
        AppSettings.applyToHolder(this)
        val live = BikeProfileHolder.active
        val touchDash: Boolean? = if (live !== BikeProfiles.legacy) {
            DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
            BikeProfileHolder.advertisesScreenTouch
        } else {
            DashMemory.lastDashTouch(this)
        }
        if (touchDash == true) {
            findViewById<android.view.View>(R.id.tv_touch_hint).visibility = android.view.View.VISIBLE
        }

        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_control_aa)
        sw.isChecked = ButtonMode.isControlAa(this)
        sw.setOnCheckedChangeListener { _, checked ->
            ButtonMode.set(this, checked)
            MediaButtonBridge.instance?.setCaptureActive(checked)
            LogBus.log("→ handlebar buttons ${if (checked) "control Android Auto" else "control media"}")
            if (checked &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 71)
            }
        }
        findViewById<MaterialButton>(R.id.btn_customize).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }
    }

    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)), Toast.LENGTH_SHORT).show()
    }

    /** Button timing applies live — the next press uses the new window. */
    private fun setDoubleTap(delay: DoubleTapDelay) {
        ButtonTimingPrefs.setDoubleTap(this, delay)
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_dbl_tap, getString(delay.labelRes)), Toast.LENGTH_SHORT).show()
    }

    private fun setLongPress(delay: LongPressDelay) {
        ButtonTimingPrefs.setLongPress(this, delay)
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_hold, getString(delay.labelRes)), Toast.LENGTH_SHORT).show()
    }

    private fun setHoldsEnabled(on: Boolean) {
        ButtonTimingPrefs.setHoldsEnabled(this, on)
        refreshOptions()
        Toast.makeText(
            this,
            if (on) "Hold detection on"
            else "Hold detection off — long presses count as taps / ×2",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun setAutoConnect(on: Boolean) {
        AppSettings.setAutoConnect(this, on)
        refreshOptions()
        Toast.makeText(this, "Auto-connect ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setAutoRecovery(on: Boolean) {
        AppSettings.setAutoRecovery(this, on)
        refreshOptions()
        Toast.makeText(this, "Auto-recovery ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setIdleReminder(on: Boolean) {
        AppSettings.setIdleReminder(this, on)
        refreshOptions()
        Toast.makeText(this, "Ride finished reminder ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setLogTrips(on: Boolean) {
        AppSettings.setLogTrips(this, on)
        refreshOptions()
        Toast.makeText(this, "Trip logging ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }

    private fun setForceNonTouch(on: Boolean) {
        AppSettings.setForceNonTouch(this, on)
        refreshOptions()
        toast("Disable touchscreen: ${if (on) "on" else "off"}")
    }

    private fun setForceTouch(on: Boolean) {
        AppSettings.setForceTouch(this, on)
        refreshOptions()
        toast("Force touchscreen: ${if (on) "on" else "off"}")
    }

    private fun setProfileOverride(ov: ProfileOverride) {
        ProfilePrefs.set(this, ov)
        refreshOptions()
        toast("Bike profile: ${ov.shortLabel}")
    }

    private fun setTransport(t: WifiTransport) {
        AppSettings.setTransport(this, t)
        refreshOptions()
        toast(getString(R.string.setup_toast_transport, t.label))
    }

    private fun setSecrets(on: Boolean) {
        AppSettings.setIncludeSecretsInLogs(this, on)
        refreshOptions()
        Toast.makeText(
            this,
            if (on) "Shared logs will include secrets — turn off before posting publicly"
            else "Log redaction on",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun setTelemetry(on: Boolean) {
        AppSettings.setAnonymousTelemetry(this, on)
        refreshOptions()
        if (on) AnonymousTelemetry.onAppStart(this)
        Toast.makeText(
            this,
            if (on) getString(R.string.setup_anonymous_telemetry_on)
            else getString(R.string.setup_anonymous_telemetry_off),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, "$msg (applies next connect)", Toast.LENGTH_SHORT).show()

    /** Update every selector's description + highlight the active segment. */
    private fun refreshOptions() {
        val quality = VideoPrefs.get(this)
        val fit = VideoPrefs.fit(this)
        val mirrorOrient = VideoPrefs.mirrorOrientation(this)
        val power = VideoPrefs.power(this)
        val res = VideoPrefs.resolution(this)
        val theme = NightPrefs.theme(this)
        val dbl = ButtonTimingPrefs.doubleTap(this)
        val holdsOn = ButtonTimingPrefs.holdsEnabled(this)
        val hold = ButtonTimingPrefs.longPress(this)
        qualityDesc.text = getString(quality.labelRes)
        fitDesc.text = getString(fit.labelRes)
        mirrorOrientDesc.text = getString(R.string.setup_mirror_orientation_desc) +
            "\n" + getString(mirrorOrient.labelRes)
        powerDesc.text = getString(power.labelRes)
        resDesc.text = getString(res.labelRes)
        val dpi = VideoPrefs.dpiOverride(this)
        dpiDesc.text = if (dpi == null) getString(R.string.setup_aa_dpi_desc)
        else getString(R.string.setup_aa_dpi) + ": $dpi"
        val trigName = AppSettings.btTriggerName(this)
        findViewById<MaterialButton>(R.id.btn_bt_trigger).text =
            if (trigName != null) getString(R.string.setup_bt_trigger_set, trigName)
            else getString(R.string.setup_bt_trigger_none)
        themeDesc.text = getString(theme.labelRes)
        dblTapDesc.text = getString(dbl.labelRes)
        holdsDesc.text = if (holdsOn) {
            getString(R.string.setup_on_press_and_hold_can_fire_hold_gestures)
        } else {
            getString(R.string.setup_holds_off_desc)
        }
        holdDesc.text = if (holdsOn) getString(hold.labelRes) else getString(R.string.pref_hold_ignored)
        nonTouchDesc.text = if (AppSettings.forceNonTouch(this))
            getString(R.string.setup_nontouch_on_desc)
        else
            getString(R.string.setup_off_use_the_bike_profile_touch_dashes_stay_touch)
        forceTouchDesc.text = if (AppSettings.forceTouch(this))
            getString(R.string.setup_forcetouch_on_desc)
        else
            getString(R.string.setup_off_use_the_bike_profile_1000_mt_x_stays_focus_k)

        highlight(quality,
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)
        highlight(fit,
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)
        highlight(mirrorOrient,
            R.id.mirror_orient_dash to MirrorOrientation.MATCH_DASH,
            R.id.mirror_orient_follow to MirrorOrientation.FOLLOW,
            R.id.mirror_orient_land to MirrorOrientation.LANDSCAPE,
            R.id.mirror_orient_port to MirrorOrientation.PORTRAIT)
        highlight(power,
            R.id.power_auto to PowerMode.AUTO,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)
        highlight(res,
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)
        highlight<Int?>(
            dpi,
            R.id.dpi_auto to null,
            R.id.dpi_160 to 160,
            R.id.dpi_180 to 180,
            R.id.dpi_240 to 240,
            R.id.dpi_320 to 320,
        )
        highlight(theme,
            R.id.theme_auto to MapTheme.AUTO,
            R.id.theme_day to MapTheme.DAY,
            R.id.theme_night to MapTheme.NIGHT)
        highlight(dbl,
            R.id.dbltap_fast to DoubleTapDelay.FAST,
            R.id.dbltap_normal to DoubleTapDelay.NORMAL,
            R.id.dbltap_slow to DoubleTapDelay.SLOW,
            R.id.dbltap_very_slow to DoubleTapDelay.VERY_SLOW)
        highlight(holdsOn,
            R.id.holds_on to true,
            R.id.holds_off to false)
        highlight(hold,
            R.id.hold_short to LongPressDelay.SHORT,
            R.id.hold_normal to LongPressDelay.NORMAL,
            R.id.hold_long to LongPressDelay.LONG)

        highlight(AppSettings.autoConnect(this),
            R.id.autoconnect_on to true,
            R.id.autoconnect_off to false)
        highlight(AppSettings.autoRecovery(this),
            R.id.recovery_on to true,
            R.id.recovery_off to false)
        highlight(AppSettings.idleReminder(this),
            R.id.idlereminder_on to true,
            R.id.idlereminder_off to false)
        // Warn when Android has notifications blocked for the app — the reminder can't show then.
        val notifOk = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        findViewById<android.widget.TextView>(R.id.notif_system_desc).apply {
            text = getString(if (notifOk) R.string.setup_notif_system_desc else R.string.setup_notif_system_off)
            setTextColor(ContextCompat.getColor(this@SetupActivity,
                if (notifOk) R.color.home_text_2 else android.R.color.holo_red_dark))
        }
        highlight(AppSettings.logTrips(this),
            R.id.logtrips_on to true,
            R.id.logtrips_off to false)
        highlight(AppSettings.forceNonTouch(this),
            R.id.nontouch_on to true,
            R.id.nontouch_off to false)
        highlight(AppSettings.forceTouch(this),
            R.id.forcetouch_on to true,
            R.id.forcetouch_off to false)
        val transport = AppSettings.transport(this)
        findViewById<android.widget.TextView>(R.id.transport_desc).text = transport.label
        highlight(transport,
            R.id.transport_auto to WifiTransport.AUTO,
            R.id.transport_ap to WifiTransport.AP,
            R.id.transport_p2p to WifiTransport.P2P)
        val secrets = AppSettings.includeSecretsInLogs(this)
        findViewById<android.widget.TextView>(R.id.secrets_desc).text =
            if (secrets) getString(R.string.setup_secrets_on_desc)
            else getString(R.string.setup_off_passwords_and_serials_are_redacted_recommend)
        highlight(secrets, R.id.secrets_on to true, R.id.secrets_off to false)
        val telemetry = AppSettings.anonymousTelemetry(this)
        findViewById<android.widget.TextView>(R.id.telemetry_desc).text =
            getString(R.string.setup_anonymous_telemetry_desc)
        findViewById<android.widget.TextView>(R.id.telemetry_id).text =
            getString(R.string.setup_anonymous_id, AnonymousTelemetry.anonUuidForDisplay(this))
        highlight(telemetry, R.id.telemetry_on to true, R.id.telemetry_off to false)
    }

    /** Refresh the Bluetooth pairing status line shown in the helper card. */
    private fun refreshBluetooth() {
        btStatus.text = BluetoothHelper.status(this).describe(this)
    }

    /** Paint the segment matching [selected] in brand color; the rest stay neutral tonal. */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.home_cyan)
        val onText = ContextCompat.getColor(this, R.color.white)
        val offColor = ContextCompat.getColor(this, R.color.home_card_soft)
        val offText = ContextCompat.getColor(this, R.color.home_text)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshOptions()
        refreshBluetooth()
    }

    private fun refresh() {
        val aaOk = SetupHelper.isAndroidAutoInstalled(this)
        step1Title.text = getString(R.string.setup_1_android_auto)
        stepBadge(R.id.step1_badge, aaOk, "1")
        step1Btn.text = if (aaOk) getString(R.string.setup_open_android_auto) else getString(R.string.setup_install_android_auto)

        val permsOk = SetupHelper.permissionsGranted(this)
        step2Title.text = getString(R.string.setup_2_permissions)
        stepBadge(R.id.step2_badge, permsOk, "2")
        step2Btn.text = if (permsOk) tick(true) + " " + getString(R.string.setup_all_granted)
            else getString(R.string.setup_grant_permissions)
        step2Btn.isEnabled = !permsOk
        val mint = ContextCompat.getColor(this, R.color.home_mint)
        step2Btn.setTextColor(if (permsOk) mint else ContextCompat.getColor(this, R.color.home_text))
        step2Btn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (permsOk) R.color.home_mint_soft else R.color.home_card))

        val ready = aaOk && permsOk
        val statusColor = ContextCompat.getColor(this, if (ready) R.color.home_mint else R.color.home_amber)
        findViewById<TextView>(R.id.setup_status_text).apply {
            text = getString(if (ready) R.string.setup_status_ready else R.string.setup_status_setup)
            setTextColor(statusColor)
        }
        findViewById<android.view.View>(R.id.setup_status_dot).backgroundTintList = ColorStateList.valueOf(statusColor)
        findViewById<android.view.View>(R.id.setup_status_pill).backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ready) R.color.home_mint_soft else R.color.home_cyan_soft))

        val resumeOk = SetupHelper.canAutoResume(this)
        resumeBtn.text = if (resumeOk) getString(R.string.setup_seamless_resume_enabled)
            else getString(R.string.setup_enable_seamless_resume)
        resumeBtn.isEnabled = !resumeOk
    }

    /** Deep-link to the "Display over other apps" screen for this app (overlay = seamless auto-resume). */
    private fun requestOverlayPermission() {
        if (SetupHelper.canAutoResume(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, getString(R.string.setup_overlay_for_seamless_resume),
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_overlay_permission_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Numbered cyan badge, or a mint ring with a tick once the step is complete. */
    private fun stepBadge(id: Int, done: Boolean, number: String) {
        findViewById<TextView>(id).apply {
            if (done) {
                text = "✓"
                setBackgroundResource(R.drawable.setup_badge_done)
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.home_mint))
            } else {
                text = number
                setBackgroundResource(R.drawable.setup_badge_todo)
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.white))
            }
        }
    }

    private fun tick(ok: Boolean): String = if (ok) "\u2713" else "\u2022"

    private fun requestMissingPermissions() {
        val missing = SetupHelper.missingPermissions(this)
        if (missing.isEmpty()) { refresh(); return }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // If anything is still missing after the prompt, the user likely hit "Don't allow" — point
        // them at the system settings where it can still be granted.
        if (!SetupHelper.permissionsGranted(this)) {
            Toast.makeText(this,
                getString(R.string.setup_some_permissions_still_off),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.main_settings_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAndroidAuto() {
        if (SetupHelper.isAndroidAutoInstalled(this)) {
            openAndroidAutoSettings()
            return
        }
        val market = Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${SetupHelper.GEARHEAD_PACKAGE}"))
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${SetupHelper.GEARHEAD_PACKAGE}")))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_play_store_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAndroidAutoSettings() {
        try {
            startActivity(Intent("android.settings.ANDROID_AUTO_SETTINGS")
                .setPackage(SetupHelper.GEARHEAD_PACKAGE))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setClassName(
                    SetupHelper.GEARHEAD_PACKAGE,
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_android_auto_settings_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 42
        private const val STATE_TAB = "setup_tab"
        private const val PREFS = "opencfmoto_bike"
        private const val KEY_SEEN = "setup_seen"

        fun hasSeen(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: android.content.Context) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        }

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java))
        }
    }
}
