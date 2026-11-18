// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Starts the Android Auto → USB car projection WITHOUT opening the main screen. It repeats the USB
 * branch of `MainActivity.startAaFlow` (tear down, apply the profile, start the Android Auto
 * service, trigger Google AA, then mark the USB transport ready), so the rider can stay on the USB
 * probe screen. Everything it touches is process-global ([BikeLink], [AaVideoBridge],
 * [AndroidAutoService]) — the same state the main screen drives.
 */
object UsbCarStart {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var lastStartAt = 0L

    /** Idempotent within a few seconds: the permission result, attach intent and watcher can race. */
    @Synchronized
    fun start(ctx: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStartAt < 4_000) { LogBus.log("→ USB car: start already in progress"); return }
        lastStartAt = now
        val app = ctx.applicationContext
        val qr = UsbCar.ensureSelected(app) ?: return
        try {
            LogBus.log("→ USB car: getting Android Auto ready (the screen stays where it is)")
            // Same tear-down as a mode switch, minus the Activity-only parts.
            AaVideoBridge.onSteadyVideo = null
            AaVideoBridge.pipeline = null
            AaVideoBridge.touchSink = null
            AaVideoBridge.keySink = null
            AaVideoBridge.scrollSink = null
            AaVideoBridge.previewTouchSink = null
            AaVideoBridge.nightSink = null
            try { AndroidAutoService.stop(app) } catch (e: Exception) { LogBus.log("AA stop: $e") }
            try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(app) } catch (_: Exception) {}
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
            BikeLink.beginHandoff(app)

            BikeProfileApply.apply(app, qr) { LogBus.log(it) }
            BikeProfileHolder.carUsbTouch = true
            if (BikeLink.prober == null) BikeLink.prober = EasyConnProber(app, LogBus::log)
            ConnectionState.set(Phase.STARTING_AA, UsbCar.DISPLAY_NAME)
            LogBus.log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            BikeLink.beginHandoff(app)
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                AndroidAutoService.markDevModeSeen(app)
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                AndroidAutoService.upgradeForegroundForMicrophone()
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(app)
            ui.postDelayed({
                try { dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(app, log = LogBus::log) }
                catch (e: Exception) { LogBus.log("AA self-mode trigger failed: $e") }
            }, 900)
            ui.postDelayed({
                if (liveOrTerminal()) {
                    if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) {
                        LogBus.log("[AA] skip re-trigger — AA already connected/decoding")
                    }
                    return@postDelayed
                }
                try {
                    LogBus.log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(app, log = LogBus::log)
                } catch (e: Exception) { LogBus.log("AA self-mode re-trigger failed: $e") }
            }, 4_500)
            ui.postDelayed({
                val p = ConnectionState.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
                LogBus.log("[AA] Android Auto never attached — stopping silent retry")
                ConnectionState.set(
                    Phase.ERROR,
                    app.getString(
                        if (AaVideoBridge.aaSessionSeen) R.string.conn_detail_aa_dropped
                        else R.string.conn_detail_aa_not_started,
                    ),
                )
            }, 18_000)

            // USB transport: the link is the already-open accessory (or the watcher opens it later).
            LogBus.log("→ USB car: skipping Wi‑Fi, serving the car over the USB bridge" +
                if (UsbCarLink.isOpen) "" else " (car not attached yet)")
            BikeLink.markUsbReady()
            waitForAaVideo(app, 60)
        } catch (e: Exception) {
            LogBus.log("USB car start failed: $e")
            ConnectionState.set(Phase.ERROR, app.getString(R.string.conn_detail_connect_failed))
        }
    }

    private fun waitForAaVideo(app: Context, triesLeft: Int) {
        ui.postDelayed({
            when {
                AaVideoBridge.aaDecoding -> {
                    LogBus.log("→ USB car: AA video is flowing — starting the link")
                    BikeLink.markAaVideoSteady()
                }
                triesLeft > 0 && AndroidAutoService.isRunning -> waitForAaVideo(app, triesLeft - 1)
                else -> LogBus.log("→ USB car: Android Auto never sent video")
            }
        }, 1_000L)
    }

    private fun liveOrTerminal(): Boolean {
        val p = ConnectionState.phase
        return AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR || p == Phase.MIRRORING
    }
}
