// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A fake CFMoto dashboard (CFDL16 / 675 SR-R dialect) living inside the app, on 127.0.0.1.
 *
 * It plays the bike's half of the EasyConn PXC protocol (docs/01-REVERSE-ENGINEERING.md §§2–4):
 * answers the phone's MDNS_RESPOND probe on :10930, connects back to the phone's 10922 (twice),
 * 10921 and 10920, runs the CLIENT_INFO / SN handshake, negotiates an 800×480 canvas (the rider's 675 dash) and then
 * pulls H.264 frames with REQ_RV_DATA_NEXT exactly like the dash, decoding them onto a Surface
 * ([DummyDashActivity]). The phone side is the unmodified [EasyConnProber]; only the Wi‑Fi join is
 * skipped ([MainActivity.joinWifi] hands loopback IPs to the prober instead).
 *
 * Test tool only: no auth checks, CFDL16 behaviour only, and the frame wire format is the
 * documented "inferred" one — a green dummy proves the app's side, not a real dash.
 */
object DummyBike {
    const val SSID = "OPENCFMOTO-DUMMY"
    const val DISPLAY_NAME = "Dummy bike (test)"
    /** Same query-string shape as a real CFDL16 dash QR (modelid 37416 → CFDL16 profile). */
    const val QR_RAW = "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=dummy&action=1" +
        "&ssid=$SSID&pwd=dummydummy&auth=wpa2-psk&mac=02:00:00:00:00:01&name=$SSID"

    private const val DASH_W = 800
    private const val DASH_H = 480
    private const val HUID = "DUMMY0000000001"
    private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    val loopback: java.net.Inet4Address get() = LOOPBACK as java.net.Inet4Address

    fun isDummy(qr: QrData?): Boolean = qr?.ssid == SSID

    // ---------------- status for the UI ----------------

    data class Status(
        val listening: Boolean,
        val stage: String,
        val framesReceived: Long,
        val fps: Int,
        val videoW: Int,
        val videoH: Int,
    )

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stage = "Off"
    @Volatile private var framesReceived = 0L
    @Volatile private var fps = 0
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    fun status() = Status(server != null, stage, framesReceived, fps, videoW, videoH)

    fun addListener(l: (Status) -> Unit) { listeners.add(l); l(status()) }
    fun removeListener(l: (Status) -> Unit) { listeners.remove(l) }

    private fun publish(newStage: String? = null) {
        if (newStage != null && newStage != stage) {
            stage = newStage
            LogBus.log("[DUMMY] $newStage")
        }
        val s = status()
        main.post { listeners.forEach { it(s) } }
    }

    // ---------------- lifecycle ----------------

    @Volatile private var server: ServerSocket? = null
    @Volatile private var session: Session? = null

    /** Is the dummy bike listed in the Garage? (Off by default: it is never added automatically.) */
    fun inGarage(ctx: Context): Boolean = BikeMemory.devices(ctx).any { it.raw == QR_RAW }

    /**
     * Garage switch (debug builds): list the dummy bike without selecting it, or remove it (which
     * also drops it as the Connect bike if it was picked). Real bikes are never touched.
     */
    fun setInGarage(ctx: Context, show: Boolean) {
        if (!show) { BikeMemory.remove(ctx, QR_RAW); return }
        if (inGarage(ctx)) return
        val keep = BikeMemory.selected(ctx)?.raw
        selectAsBike(ctx)
        if (keep != null) BikeMemory.select(ctx, keep) else BikeMemory.clearSelection(ctx)
    }

    /** Save the dummy as the selected bike so Connect / Map → bike use it. */
    fun selectAsBike(ctx: Context) {
        val qr = QrData.parse(QR_RAW) ?: run { LogBus.log("[DUMMY] QR parse failed"); return }
        BikeMemory.save(ctx, QR_RAW, qr)
        BikeMemory.select(ctx, QR_RAW)
        try { BikeMemory.rename(ctx, QR_RAW, DISPLAY_NAME) } catch (_: Exception) {}
        LogBus.log("[DUMMY] selected '$DISPLAY_NAME' as the bike")
    }

    /** Listen on 127.0.0.1:10930 for the phone's probe (idempotent). */
    @Synchronized
    fun ensureListening() {
        if (server != null) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LOOPBACK, EasyConnProber.BIKE_PROBE_PORT), 4)
            }
        } catch (e: Exception) {
            LogBus.log("[DUMMY] cannot listen on :${EasyConnProber.BIKE_PROBE_PORT}: ${e.message}")
            publish("Error: port ${EasyConnProber.BIKE_PROBE_PORT} busy")
            return
        }
        server = ss
        publish("Waiting for the app to connect")
        thread(name = "dummy-probe", isDaemon = true) { probeLoop(ss) }
    }

    @Synchronized
    fun shutdown() {
        session?.close("dummy bike switched off")
        session = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        publish("Off")
    }

    // ---------------- dash surface ----------------

    @Volatile private var surface: Surface? = null

    fun setSurface(s: Surface?) {
        surface = s
        session?.surfaceChanged()
    }

    // ---------------- probe endpoint (:10930) ----------------

    private fun probeLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (_: Exception) { break }
            try {
                c.soTimeout = 5_000
                val f = PxcFrame.read(c.getInputStream())
                if (f?.cmd == PxcFrame.CMD_MDNS_RESPOND) {
                    LogBus.log("[DUMMY] <- probe ${String(f.payload, Charsets.UTF_8)}")
                    PxcFrame(PxcFrame.CMD_MDNS_RESPOND_ACK, "{\"status\":true}".toByteArray())
                        .write(c.getOutputStream())
                    c.close()
                    startSession()
                } else {
                    LogBus.log("[DUMMY] unexpected first frame on :10930: ${f?.cmdHex()}")
                    c.close()
                }
            } catch (e: Exception) {
                LogBus.log("[DUMMY] probe error: ${e.message}")
                try { c.close() } catch (_: Exception) {}
            }
        }
    }

    /** Dummy car: skip the :10930 probe and connect straight back to the loopback ports. */
    fun connectAsCar() { startSession() }

    @Synchronized
    private fun startSession() {
        session?.close("new probe — restarting session")
        framesReceived = 0
        fps = 0
        session = Session().also { s -> thread(name = "dummy-session", isDaemon = true) { s.run() } }
    }

    // ---------------- one connected session ----------------

    private class Session {
        @Volatile var alive = true
        private val sockets = CopyOnWriteArrayList<Socket>()
        private var decoder: MediaCodec? = null
        private var decoderSurface: Surface? = null
        @Volatile private var needKeyframe = true
        private var csd: ByteArray? = null

        fun run() {
            try {
                publish("Phone accepted — connecting back")
                Thread.sleep(200)
                val ctrl = connect(EasyConnProber.PORT_PXC_CTRL)
                carCtrl(ctrl)
                val data = connect(EasyConnProber.PORT_PXC_CTRL)
                carData(data)
                val mediaCtrl = connect(EasyConnProber.PORT_MEDIA_CTRL)
                negotiateMedia(mediaCtrl)
                val mediaData = connect(EasyConnProber.PORT_MEDIA_DATA)
                publish("Streaming — pulling video")
                pullFrames(mediaData)
            } catch (e: Exception) {
                if (alive) LogBus.log("[DUMMY] session error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                close(null)
            }
        }

        fun close(reason: String?) {
            if (!alive) return
            alive = false
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            releaseDecoder()
            if (reason != null) LogBus.log("[DUMMY] $reason")
            publish(if (DummyBike.server != null) "Disconnected — waiting for the app" else "Off")
        }

        private fun connect(port: Int): Socket {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(LOOPBACK, port), 3_000)
            sockets.add(s)
            LogBus.log("[DUMMY] -> connected to phone :$port")
            return s
        }

        // ---- :10922 #1 — CAR_CTRL + CLIENT_INFO + QUERY_SPEED ----
        private fun carCtrl(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            send(out, PxcFrame.CMD_CHANNEL_CAR_CTRL)
            expect(input, PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, out)
            val info = org.json.JSONObject().apply {
                put("HUID", HUID)
                put("HUName", "CFDL16-DUMMY")
                put("channel", "37416")
                put("flavor", 65540)
                put("sdkVersion", "0.9.29.1")
                put("screenType", 1)
                put("productType", 3)
                put("mirrorMode", 1)
                put("supportScreenMirroring", true)
                put("socketTimeoutPeriodWifi", 9)
                put("package_name", "dev.zanderp.opencfmoto.dummy")
            }
            publish("Handshake: CLIENT_INFO")
            send(out, PxcFrame.CMD_CLIENT_INFO, info.toString())
            val reply = expect(input, PxcFrame.CMD_CLIENT_INFO_RLY, out)
            LogBus.log("[DUMMY] <- phone CLIENT_INFO ${String(reply.payload, Charsets.UTF_8).take(120)}…")
            send(out, PxcFrame.CMD_QUERY_SPEED, "{\"usbSpeed\":0,\"wifiSpeed\":0}")
            expect(input, PxcFrame.CMD_QUERY_SPEED_RLY, out)
            keepControlAlive("ctrl", input, out)
        }

        // ---- :10922 #2 — CAR_DATA + SN check ----
        private fun carData(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            send(out, PxcFrame.CMD_CHANNEL_CAR_DATA)
            expect(input, PxcFrame.CMD_CHANNEL_CAR_DATA + 1, out)
            publish("Handshake: SN check")
            send(out, PxcFrame.CMD_CHECK_SN, "{\"client_set\":\"easy_conn\",\"sn\":\"DUMMYSN0001\"}")
            expect(input, PxcFrame.CMD_CHECK_SN_ACK, out)
            val result = expect(input, PxcFrame.CMD_CHECK_SN_RESULT, out)
            LogBus.log("[DUMMY] <- SN result ${String(result.payload, Charsets.UTF_8)}")
            send(out, PxcFrame.CMD_CHECK_SN_RESULT + 1)
            keepControlAlive("data", input, out)
        }

        /** Answer phone heartbeats and send our own, like the dash (it drops after ~7 s silence). */
        private fun keepControlAlive(label: String, input: InputStream, out: OutputStream) {
            thread(name = "dummy-$label-rx", isDaemon = true) {
                try {
                    while (alive) {
                        val f = PxcFrame.read(input) ?: break
                        when (f.cmd) {
                            PxcFrame.CMD_HEARTBEAT -> send(out, PxcFrame.CMD_HEARTBEAT_ACK)
                            PxcFrame.CMD_HEARTBEAT_ACK -> {}
                            else -> LogBus.log("[DUMMY] $label <- ${f.cmdHex()} len=${f.payload.size}")
                        }
                    }
                } catch (_: Exception) {}
                if (alive) close("phone closed the $label channel")
            }
            thread(name = "dummy-$label-hb", isDaemon = true) {
                try {
                    while (alive) {
                        Thread.sleep(2_000)
                        send(out, PxcFrame.CMD_HEARTBEAT)
                    }
                } catch (_: Exception) {}
            }
        }

        private fun send(out: OutputStream, cmd: Int, json: String? = null) =
            PxcFrame(cmd, json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)).write(out)

        /** Read until [cmd] arrives, answering heartbeats in between. */
        private fun expect(input: InputStream, cmd: Int, out: OutputStream): PxcFrame {
            while (alive) {
                val f = PxcFrame.read(input) ?: throw java.io.EOFException("phone closed (waiting ${hex(cmd)})")
                if (f.cmd == cmd) return f
                if (f.cmd == PxcFrame.CMD_HEARTBEAT) send(out, PxcFrame.CMD_HEARTBEAT_ACK)
                else if (f.cmd != PxcFrame.CMD_HEARTBEAT_ACK) {
                    LogBus.log("[DUMMY] (waiting ${hex(cmd)}) got ${f.cmdHex()} len=${f.payload.size}")
                }
            }
            throw java.io.EOFException("session closed")
        }

        // ---- :10921 — media control ----
        private fun negotiateMedia(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            publish("Media: CONFIG_CAPTURE ${DASH_W}x$DASH_H")
            val cfg = ByteBuffer.allocate(32 + HUID.length).order(ByteOrder.LITTLE_ENDIAN)
            cfg.putShort(0, DASH_W.toShort())
            cfg.putShort(2, DASH_H.toShort())
            cfg.putInt(4, 30)          // wantFps
            cfg.putInt(8, 2)           // wantEncoder = H264
            cfg.putInt(12, 2)          // supportCodec
            cfg.putShort(16, 1)        // minQuality
            cfg.putShort(18, 100)      // maxQuality
            cfg.putInt(20, 2_500_000)  // bitRate
            cfg.put(29, 1)             // supportExtendProtocol
            HUID.toByteArray().forEachIndexed { i, b -> cfg.put(32 + i, b) }
            reqBase(out, 16, cfg.array())
            val rly = readReqBase(input, 17)
            val rb = ByteBuffer.wrap(rly).order(ByteOrder.LITTLE_ENDIAN)
            if (rly.size >= 8) {
                videoW = rb.getShort(4).toInt() and 0xFFFF
                videoH = rb.getShort(6).toInt() and 0xFFFF
            }
            LogBus.log("[DUMMY] <- RLY_CONFIG_CAPTURE ${videoW}x$videoH")
            reqBase(out, 48, null); readReqBase(input, 49)
            reqBase(out, 96, "{}".toByteArray()); readReqBase(input, 97)
            reqBase(out, 112, null); readReqBase(input, 113)
            LogBus.log("[DUMMY] <- RLY_RV_DATA_START — encoder running on the phone")
            // Media heartbeat (64 → 65) keeps this socket warm, like the dash.
            thread(name = "dummy-media-hb", isDaemon = true) {
                try {
                    while (alive) {
                        Thread.sleep(2_000)
                        reqBase(out, 64, null)
                        readReqBase(input, 65)
                    }
                } catch (_: Exception) {
                    if (alive) close("phone closed the media control channel")
                }
            }
        }

        private fun reqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
            val len = body?.size ?: 0
            val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            h.putShort(0, cmdType.toShort()); h.putShort(2, len.toShort()); h.putInt(4, 0)
            synchronized(out) {
                out.write(h.array())
                if (len > 0) out.write(body!!)
                out.flush()
            }
        }

        private fun readReqBase(input: InputStream, wantType: Int): ByteArray {
            val header = ByteArray(8)
            while (alive) {
                if (!PxcFrame.readFully(input, header, 8)) throw java.io.EOFException("media closed")
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val type = b.getShort(0).toInt()
                val len = b.getShort(2).toInt() and 0xFFFF
                val body = ByteArray(len)
                if (len > 0 && !PxcFrame.readFully(input, body, len)) throw java.io.EOFException("media body short")
                if (type == wantType) return body
                LogBus.log("[DUMMY] media (waiting $wantType) got $type len=$len")
            }
            throw java.io.EOFException("session closed")
        }

        // ---- :10920 — media data: REQ_RV_DATA_NEXT lock-step pull ----
        private fun pullFrames(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            // The phone stays silent when it has no frame (logs "no frame ready"), so re-ask on a
            // timeout longer than its 1.5 s poll instead of hanging — the real dash gives up at 9 s.
            s.soTimeout = 3_000
            val sizeBuf = ByteArray(4)
            var windowStart = System.currentTimeMillis()
            var windowFrames = 0
            var silentSince = System.currentTimeMillis()
            while (alive) {
                reqBase(out, 114, null)
                try {
                    if (!PxcFrame.readFully(input, sizeBuf, 4)) throw java.io.EOFException("data closed")
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - silentSince > 9_000) {
                        close("no video for 9 s — a real dash would drop the link here")
                        return
                    }
                    continue
                }
                val size = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                if (size <= 0 || size > 4_000_000) throw IllegalStateException("bad frame size $size")
                val au = ByteArray(size)
                if (!PxcFrame.readFully(input, au, size)) throw java.io.EOFException("frame short")
                silentSince = System.currentTimeMillis()
                framesReceived++
                windowFrames++
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1_000) {
                    fps = windowFrames
                    windowFrames = 0
                    windowStart = now
                    publish()
                }
                if (framesReceived == 1L) LogBus.log("[DUMMY] first frame ${size}b")
                decode(au)
            }
        }

        // ---- decoding onto the dummy dash ----

        fun surfaceChanged() { releaseDecoder(); needKeyframe = true }

        private fun decode(au: ByteArray) {
            val nals = nalTypes(au)
            if (7 in nals) csd = au   // SPS(+PPS) usually rides on the keyframe AU
            val target = surface
            if (target == null) { needKeyframe = true; return }
            if (needKeyframe && 5 !in nals) return
            try {
                val dec = decoder ?: createDecoder(target) ?: return
                if (needKeyframe) {
                    // Make sure the decoder sees SPS/PPS before the first IDR.
                    if (7 !in nals) csd?.let { queue(dec, it, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
                    needKeyframe = false
                }
                queue(dec, au, 0)
                drain(dec)
            } catch (e: Exception) {
                LogBus.log("[DUMMY] decode error: ${e.message} — resetting decoder")
                releaseDecoder()
                needKeyframe = true
            }
        }

        private fun createDecoder(target: Surface): MediaCodec? {
            val w = if (videoW > 0) videoW else DASH_W
            val h = if (videoH > 0) videoH else DASH_H
            return try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { d ->
                    d.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h), target, null, 0)
                    d.start()
                    decoder = d
                    decoderSurface = target
                    LogBus.log("[DUMMY] decoder started ${w}x$h")
                }
            } catch (e: Exception) {
                LogBus.log("[DUMMY] decoder create failed: ${e.message}")
                null
            }
        }

        private fun queue(dec: MediaCodec, data: ByteArray, flags: Int) {
            val idx = dec.dequeueInputBuffer(50_000)
            if (idx < 0) return
            val buf = dec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data, 0, minOf(data.size, buf.capacity()))
            dec.queueInputBuffer(idx, 0, minOf(data.size, buf.capacity()), System.nanoTime() / 1000, flags)
        }

        private fun drain(dec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val idx = dec.dequeueOutputBuffer(info, 0)
                if (idx < 0) break
                dec.releaseOutputBuffer(idx, true)
            }
        }

        private fun releaseDecoder() {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
            decoderSurface = null
        }

        /** NAL unit types present in an Annex-B access unit. */
        private fun nalTypes(au: ByteArray): Set<Int> {
            val out = HashSet<Int>()
            var i = 0
            while (i + 3 < au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0) {
                    val start = when {
                        au[i + 2].toInt() == 1 -> i + 3
                        au[i + 2].toInt() == 0 && au[i + 3].toInt() == 1 -> i + 4
                        else -> -1
                    }
                    if (start in 0 until au.size) {
                        out.add(au[start].toInt() and 0x1F)
                        i = start
                        continue
                    }
                }
                i++
            }
            return out
        }

        private fun hex(cmd: Int) = "0x" + cmd.toUInt().toString(16)
    }
}
