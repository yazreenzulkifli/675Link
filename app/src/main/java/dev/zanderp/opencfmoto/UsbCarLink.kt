// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Synthetic "bike" for a Carbit-USB car head unit (Perodua Smart Link and similar). Mirrors how
 * [DummyBike] plugs a non-Wi-Fi transport into the normal Connect flow: a fake saved QR so profile /
 * memory / UI code keeps working, and [isUsb] so the flow skips Wi-Fi joining.
 */
object UsbCar {
    const val SSID = "OPENCFMOTO-USBCAR"
    const val DISPLAY_NAME = "Car head unit (USB)"
    const val QR_RAW = "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=usbcar&action=1" +
        "&ssid=$SSID&pwd=usbcarusbcar&auth=wpa2-psk&mac=02:00:00:00:00:02&name=$SSID"

    /** Extra on the MainActivity launch intent: start Android Auto over the open USB link. */
    const val EXTRA_START = "dev.zanderp.opencfmoto.EXTRA_START_USB_CAR"

    /** With [EXTRA_START]: bring the USB probe screen back to the front once Android Auto is starting. */
    const val EXTRA_BACK_TO_PROBE = "dev.zanderp.opencfmoto.EXTRA_BACK_TO_PROBE"

    fun isUsb(qr: QrData?): Boolean = qr?.ssid == SSID

    fun ensureSelected(ctx: Context): QrData? {
        val qr = QrData.parse(QR_RAW) ?: run { LogBus.log("[USB-CAR] QR parse failed"); return null }
        BikeMemory.save(ctx, QR_RAW, qr)
        BikeMemory.select(ctx, QR_RAW)
        try { BikeMemory.rename(ctx, QR_RAW, DISPLAY_NAME) } catch (_: Exception) {}
        return qr
    }
}

/**
 * Process-wide owner of the USB accessory link to a Carbit "Oversea" car head unit: opens the
 * accessory pipe, speaks the ADB-style transport (the car is the ADB host, we answer like libjadbd
 * does) and relays the car's `tcp:<port>` streams to the loopback PXC/media servers through
 * [UsbAdbBridge]. Lives here — not in an Activity — so the link survives the probe screen and the
 * main screen coming and going while Android Auto runs.
 */
object UsbCarLink {
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var onChanged: (() -> Unit)? = null
    @Volatile var isOpen = false; private set
    @Volatile var bridge: UsbAdbBridge? = null; private set
    @Volatile var activeVariant: AdbWire.ReplyVariant = AdbWire.VARIANTS[0]; private set

    private var appCtx: Context? = null
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private var testProber: EasyConnProber? = null
    private var detachReceiver: BroadcastReceiver? = null
    private var rxBytes = 0L
    private var nextLocalId = 1
    private val writeLock = Any()
    /** Raw RX / per-message log lines still allowed in bridge mode (video would flood the log). */
    @Volatile private var verboseBudget = Int.MAX_VALUE

    /**
     * True from plug-in until the local PXC servers are up (Android Auto has video). The car re-sends
     * `OPEN` every ~2 s and abandons late answers, so while this is set the CNXN reply is held back
     * (the car tolerates 20 s+ of silence there) instead of letting its OPENs time out.
     */
    @Volatile var awaitingLocal = false; private set

    @Volatile private var autoStartClaimed = false

    /**
     * True only for the first caller per opened link. The permission result, the attach intent and
     * several probe-screen instances can all try to auto-start Android Auto within milliseconds;
     * starting it twice tears the foreground service down mid-start and crashes the app.
     */
    @Synchronized
    fun claimAutoStart(): Boolean {
        if (autoStartClaimed) return false
        autoStartClaimed = true
        return true
    }

    /** Called once the loopback servers are listening (see [BikeLink] / [startTestPattern]). */
    fun localReady() {
        localUp = true
        if (awaitingLocal) log("local servers ready — releasing the held handshake")
        else log("local servers ready — waiting for the car")
        awaitingLocal = false
    }

    /** The loopback servers are up (set by [localReady], cleared when a new hand-off begins). */
    @Volatile private var localUp = false

    fun markLocalDown() { localUp = false }

    /**
     * Android Auto is already decoding and the local servers are up, so a freshly opened link can
     * answer the car at once. The car drops and re-opens the USB link every time its Detect Phone /
     * Retry button is pressed, so being ready BEFORE it re-attaches is what beats its ~20 s timer.
     */
    fun isWarm(): Boolean = localUp && AaVideoBridge.aaDecoding

    /** For the status text: servers up and the Android Auto session alive (decoding flag can lag behind). */
    fun isReadyForCar(): Boolean =
        (localUp || BikeLink.prober?.isLoopbackUp == true) &&
            (AaVideoBridge.aaDecoding || AaVideoBridge.aaSessionLive)

    // ------------------------------------------------------------------ logging

    private val fileLock = Any()
    private val stampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    private fun logFile(ctx: Context) = File(ctx.applicationContext.filesDir, "usb_probe.log")

    fun log(msg: String) {
        LogBus.log("[USB-PROBE] $msg")
        // Own on-disk log, written line by line, so a crash or kill can't lose the tail.
        appCtx?.let { c ->
            try {
                synchronized(fileLock) {
                    val f = logFile(c)
                    if (f.length() > 1024 * 1024) f.delete()
                    f.appendText("${stampFmt.format(Date())}  $msg\n")
                }
            } catch (_: Exception) {}
        }
        try { listener?.invoke(msg) } catch (_: Exception) {}
    }

    fun readLog(ctx: Context): String =
        try { synchronized(fileLock) { logFile(ctx).readText() } } catch (_: Exception) { "" }

    fun clearLog(ctx: Context) {
        try { synchronized(fileLock) { logFile(ctx).delete() } } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ auto-connect watcher

    @Volatile private var watching = false
    val isWatching: Boolean get() = watching
    private var permReceiver: BroadcastReceiver? = null

    private fun permissionAction(app: Context) = "${app.packageName}.USB_PROBE_PERMISSION"

    private fun accessoryFrom(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    /**
     * Process-wide watcher: whenever the probe is enabled and no link is open, look for an attached
     * car accessory and open it by itself (asking for USB permission when needed). This removes the
     * manual "Check now" tap, and keeps working after the car drops and re-attaches the USB link
     * (every Detect Phone / Retry on the car) without the probe screen being open.
     */
    fun startWatching(ctx: Context) {
        init(ctx)
        synchronized(this) { if (watching) return; watching = true }
        val app = ctx.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) { watching = false; return }
        val action = permissionAction(app)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val acc = accessoryFrom(intent)
                log("permission ${if (granted) "granted" else "denied"}")
                if (granted && acc != null && !isOpen) openAndAutoStart(app, acc, usb)
            }
        }
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
        permReceiver = r
        kotlin.concurrent.thread(name = "usb-car-watch", isDaemon = true) {
            var lastAsk = 0L
            var noPermSince = 0L
            while (watching) {
                try { Thread.sleep(800) } catch (_: InterruptedException) { break }
                if (isOpen || !UsbProbePrefs.enabled(app)) { noPermSince = 0L; continue }
                val acc = try { usb.accessoryList?.firstOrNull() } catch (_: Exception) { null }
                if (acc == null) { noPermSince = 0L; continue }
                try {
                    if (usb.hasPermission(acc)) {
                        noPermSince = 0L
                        log("car attached — opening the link")
                        openAndAutoStart(app, acc, usb)
                    } else if (System.currentTimeMillis() - lastAsk > 6000) {
                        lastAsk = System.currentTimeMillis()
                        val pi = android.app.PendingIntent.getBroadcast(
                            app, 0, Intent(action).setPackage(app.packageName),
                            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        log("car attached — asking for USB permission…")
                        usb.requestPermission(acc, pi)
                    }
                } catch (e: Exception) {
                    log("watcher error: $e")
                }
            }
        }
        log("watching for the car — plug in the USB cable any time")
    }

    /** Start the watcher if needed; either way the log ends with the "watching for the car" line. */
    fun ensureWatching(ctx: Context) {
        if (watching) log("watching for the car — plug in the USB cable any time")
        else startWatching(ctx)
    }

    /** Open the link; if Android Auto is not already live, ask the app to start it (best effort). */
    private fun openAndAutoStart(app: Context, acc: UsbAccessory, usb: UsbManager) {
        open(app, acc, usb)
        if (isOpen && UsbProbePrefs.autoStart(app) && UsbProbePrefs.bridge(app) &&
            claimAutoStart() && !isWarm()
        ) {
            val p = ConnectionState.phase
            if (AndroidAutoService.isRunning && p != Phase.ERROR && p != Phase.STOPPED && p != Phase.IDLE) {
                // Restarting here would tear down a handshake that is already in progress.
                log("Android Auto is already starting — waiting for it (not restarting)")
                return
            }
            log("Android Auto is not ready yet — starting it now")
            try {
                UsbCarStart.start(app)
            } catch (e: Exception) {
                log("could not start Android Auto from the background: $e — open the app and tap Get ready")
            }
        }
    }

    // ------------------------------------------------------------------ open / close

    @Synchronized
    fun open(ctx: Context, acc: UsbAccessory, usb: UsbManager?) {
        init(ctx)
        val app = ctx.applicationContext
        if (isOpen) { log("already open"); return }
        log("accessory: manufacturer=${acc.manufacturer} model=${acc.model} version=${acc.version} " +
            "description=${acc.description} uri=${acc.uri}")
        val fd = try { usb?.openAccessory(acc) } catch (e: Exception) { log("openAccessory threw: $e"); null }
        if (fd == null) { log("openAccessory returned null (no permission or accessory gone)"); return }
        pfd = fd
        input = FileInputStream(fd.fileDescriptor)
        output = FileOutputStream(fd.fileDescriptor)
        isOpen = true
        autoStartClaimed = false
        rxBytes = 0
        nextLocalId = 1
        val vi = UsbProbePrefs.variant(app)
        activeVariant = AdbWire.VARIANTS[vi]
        log("accessory open — listening (reply-to-CNXN=${UsbProbePrefs.replyCnxn(app)}, " +
            "style ${vi + 1}/${AdbWire.VARIANTS.size}: ${activeVariant.label})")
        if (UsbProbePrefs.bridge(app)) {
            verboseBudget = 80
            bridge = UsbAdbBridge(send = { sendWire(it) }, log = { log(it) }, connectRetryMs = 30_000)
            log("bridge ON — car streams tcp:10920-10922 are relayed to the local servers")
        } else {
            verboseBudget = Int.MAX_VALUE
        }
        val warm = isWarm()
        awaitingLocal = bridge != null && UsbProbePrefs.autoStart(app) && !warm
        if (warm) log("Android Auto is already live — answering the car at once")
        else if (awaitingLocal) log("auto-start is ON — the handshake reply is held until Android Auto is ready")
        registerDetach(app)
        val t = Thread({ readLoop() }, "usb-car-reader")
        reader = t
        t.start()
        Thread({ statsLoop() }, "usb-car-stats").apply { isDaemon = true }.start()
        onChanged?.invoke()
    }

    /** Every 3 s while streams move data: one summary line (per-message logging is rate-limited). */
    private fun statsLoop() {
        var lastUp = 0L; var lastDown = 0L
        while (isOpen) {
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
            val b = bridge ?: continue
            val up = b.totalUp.get(); val down = b.totalDown.get()
            if (up != lastUp || down != lastDown) {
                log("stats: streams=${b.openStreams} toCar=${up / 1024} KB (+${(up - lastUp) / 1024}) " +
                    "fromCar=${down / 1024} KB (+${(down - lastDown) / 1024})")
                lastUp = up; lastDown = down
            }
        }
    }

    @Synchronized
    fun close() {
        val was = isOpen
        isOpen = false
        awaitingLocal = false
        bridge?.closeAll(); bridge = null
        stopTestPattern()
        val app = appCtx
        if (was && app != null && UsbProbePrefs.autoCycle(app) && !UsbProbePrefs.bridge(app)) {
            val next = (UsbProbePrefs.variant(app) + 1) % AdbWire.VARIANTS.size
            UsbProbePrefs.setVariant(app, next)
            log("next reconnect will use style ${next + 1}/${AdbWire.VARIANTS.size}")
        }
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        input = null; output = null; pfd = null
        unregisterDetach()
        if (was) log("link closed")
        onChanged?.invoke()
    }

    private fun registerDetach(app: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log("accessory detached")
                close()
            }
        }
        val f = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
        detachReceiver = r
    }

    private fun unregisterDetach() {
        val r = detachReceiver ?: return
        detachReceiver = null
        try { appCtx?.unregisterReceiver(r) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ test pattern (no Android Auto)

    /** Serve the PXC/media servers with the app's own test pattern (no Android Auto involved). */
    @Synchronized
    fun startTestPattern(ctx: Context) {
        if (!isOpen) { log("test pattern: no USB link open"); return }
        stopTestPattern()
        val p = EasyConnProber(ctx.applicationContext) { log("[ec] $it") }
        testProber = p
        p.startLoopback()
        localReady()
    }

    @Synchronized
    fun stopTestPattern() {
        try { testProber?.stop() } catch (_: Exception) {}
        testProber = null
    }

    // ------------------------------------------------------------------ I/O

    private fun verbose(): Boolean = verboseBudget-- > 0

    /** Send one complete ADB message to the car as one or two USB writes, per the active style. */
    private fun sendWire(msg: ByteArray, quiet: Boolean = false) {
        val v = activeVariant
        synchronized(writeLock) {
            val out = output ?: return
            try {
                if (!v.split || msg.size <= AdbWire.HEADER_SIZE) {
                    out.write(msg)
                    if (!quiet && verbose()) log("TX ${msg.size} bytes (one write): ${AdbWire.hex(msg.copyOf(minOf(msg.size, 48)))}")
                } else {
                    // The car sends header and payload as separate USB transfers; mirror that.
                    val head = msg.copyOf(AdbWire.HEADER_SIZE)
                    out.write(head)
                    if (v.gapMs > 0) Thread.sleep(v.gapMs)
                    out.write(msg.copyOfRange(AdbWire.HEADER_SIZE, msg.size))
                    if (!quiet && verbose()) log("TX ${AdbWire.hex(head)} + ${msg.size - AdbWire.HEADER_SIZE} B payload")
                }
            } catch (e: IOException) {
                log("TX failed: $e")
            }
        }
    }

    private fun readLoop() {
        val parser = AdbWire.Parser()
        val buf = ByteArray(16 * 1024)
        val stream = input ?: return
        try {
            while (isOpen) {
                val n = stream.read(buf)
                if (n < 0) { log("read returned EOF"); break }
                if (n == 0) continue
                rxBytes += n
                if (bridge == null || verbose()) log("RX $n bytes: ${AdbWire.hex(buf.copyOf(minOf(n, 48)))}")
                for (ev in parser.feed(buf, n)) {
                    when (ev) {
                        is AdbWire.Event.Msg -> onAdbMessage(ev.message)
                        is AdbWire.Event.NotAdb -> log(
                            "not ADB-style framing; first bytes ascii='${AdbWire.ascii(ev.preview)}' (${ev.totalDropped} bytes dropped)",
                        )
                    }
                }
            }
        } catch (e: IOException) {
            if (isOpen) log("read ended: $e")
        } catch (t: Throwable) {
            log("reader thread error: ${t.stackTraceToString().take(2000)}")
        } finally {
            if (isOpen) { log("closing after $rxBytes bytes received"); close() }
        }
    }

    private fun onAdbMessage(m: AdbWire.Message) {
        val app = appCtx ?: return
        val br = bridge
        if (br == null || m.command == AdbWire.A_CNXN || m.command == AdbWire.A_OPEN || verbose()) {
            val payloadInfo = if (m.payload.isEmpty()) "" else " data='${AdbWire.ascii(m.payload.copyOf(minOf(m.payload.size, 48)))}'"
            log("ADB ${m.name} arg0=0x${Integer.toHexString(m.arg0)} arg1=0x${Integer.toHexString(m.arg1)} " +
                "len=${m.payload.size} checksum=${if (m.checksumOk) "ok" else "BAD"}$payloadInfo")
        }
        if (!UsbProbePrefs.replyCnxn(app)) return
        if (m.command == AdbWire.A_CNXN) {
            br?.maxData = m.arg1.coerceIn(256, 64 * 1024)
            val reply = AdbWire.cnxnReply(activeVariant, m.arg0, m.arg1)
            if (awaitingLocal) {
                log("holding the CNXN reply until Android Auto is ready (max 15 s — the car gives up after ~20 s)")
                kotlin.concurrent.thread(name = "usb-car-cnxn-hold", isDaemon = true) {
                    val end = System.currentTimeMillis() + 15_000
                    while (awaitingLocal && isOpen && System.currentTimeMillis() < end) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) { return@thread }
                    }
                    if (isOpen) {
                        if (awaitingLocal) log("hold timed out — answering anyway")
                        awaitingLocal = false
                        sendWire(reply)
                    }
                }
            } else {
                sendWire(reply)
            }
            return
        }
        if (br != null) {
            br.onMessage(m)
        } else {
            AdbWire.streamReply(m, if (m.command == AdbWire.A_OPEN) nextLocalId++ else nextLocalId)?.let { sendWire(it) }
        }
    }
}
