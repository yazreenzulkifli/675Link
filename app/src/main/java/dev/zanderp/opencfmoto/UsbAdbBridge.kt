// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * EXPERIMENTAL — phone side of the Carbit "Oversea" USB tunnel (what libjadbd does in the Perodua
 * app). The car acts as the ADB host and opens streams `tcp:<port>`; each one is relayed to the
 * matching loopback server on the phone (the PXC / media servers of [EasyConnProber.startLoopback]).
 *
 * Stream flow control follows ADB: every WRTE must be answered by an OKAY before the next WRTE is
 * sent on that stream. Pure JVM (java.net only) so it can be unit-tested against a local ServerSocket.
 *
 * @param send transmits ONE complete ADB message (24-byte header + payload); the caller decides how
 *   to split it into USB writes and must make it thread-safe.
 * @param connect opens the loopback socket for a port, or null if nothing is listening.
 */
class UsbAdbBridge(
    private val send: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val connect: (Int) -> Socket? = { port -> connectLoopback(port) },
    /**
     * How long an OPEN may wait for its local server to come up before being refused. In the real
     * app the PXC servers only start once Android Auto has steady video, which is later than the
     * car's first OPEN; 0 = refuse at once.
     */
    private val connectRetryMs: Long = 0,
) {
    @Volatile private var shutdown = false

    /** Largest WRTE payload we send (the car's CNXN maxdata). */
    @Volatile var maxData: Int = 4096

    private class Stream(val localId: Int, val remoteId: Int, val sock: Socket, val port: Int) {
        val credit = Semaphore(1)
        @Volatile var closed = false
        @Volatile var bytesUp = 0L     // phone → car
        @Volatile var bytesDown = 0L   // car → phone
    }

    /** Lifetime payload totals (phone → car / car → phone) for the periodic link stats. */
    val totalUp = java.util.concurrent.atomic.AtomicLong()
    val totalDown = java.util.concurrent.atomic.AtomicLong()

    private val streams = ConcurrentHashMap<Int, Stream>()   // by our local id
    private val nextId = AtomicInteger(1)

    val openStreams: Int get() = streams.size

    /** Handle one message from the car (everything except CNXN, which the caller answers). */
    fun onMessage(m: AdbWire.Message) {
        when (m.command) {
            AdbWire.A_OPEN -> onOpen(m)
            AdbWire.A_WRTE -> onWrite(m)
            AdbWire.A_OKAY -> streams[m.arg1]?.let { if (it.credit.availablePermits() < 1) it.credit.release() }
            AdbWire.A_CLSE -> streams[m.arg1]?.let { closeStream(it, notifyCar = true) }
            else -> log("bridge: ignoring ${m.name}")
        }
    }

    private fun onOpen(m: AdbWire.Message) {
        val dest = String(m.payload, Charsets.US_ASCII).trimEnd('\u0000')
        val port = Regex("""^tcp:(\d{1,5})$""").matchEntire(dest)?.groupValues?.get(1)?.toIntOrNull()
        if (port == null) {
            log("bridge: OPEN '$dest' unsupported — refusing")
            send(AdbWire.encode(AdbWire.A_CLSE, 0, m.arg0))
            return
        }
        // Off the reader thread: the car only sends data on this stream after our OKAY, so waiting
        // here for the local server (see [connectRetryMs]) cannot reorder anything.
        thread(name = "usb-bridge-open-$port", isDaemon = true) {
            val deadline = System.currentTimeMillis() + connectRetryMs
            var sock: Socket? = null
            var waited = false
            while (true) {
                sock = try { connect(port) } catch (e: Exception) { log("bridge: connect :$port threw $e"); null }
                if (sock != null || shutdown || System.currentTimeMillis() >= deadline) break
                if (!waited) { waited = true; log("bridge: OPEN tcp:$port — waiting for the local server …") }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            if (sock == null || shutdown) {
                try { sock?.close() } catch (_: IOException) {}
                if (!shutdown) {
                    log("bridge: OPEN tcp:$port — nothing listening, refusing")
                    send(AdbWire.encode(AdbWire.A_CLSE, 0, m.arg0))
                }
                return@thread
            }
            val s = Stream(nextId.getAndIncrement(), m.arg0, sock, port)
            streams[s.localId] = s
            log("bridge: stream ${s.localId} <-> car ${s.remoteId} opened to :$port")
            send(AdbWire.encode(AdbWire.A_OKAY, s.localId, s.remoteId))
            pump(s)
        }
    }

    private fun onWrite(m: AdbWire.Message) {
        val s = streams[m.arg1]
        if (s == null) {
            send(AdbWire.encode(AdbWire.A_CLSE, 0, m.arg0))
            return
        }
        try {
            val out = s.sock.getOutputStream()
            out.write(m.payload)
            out.flush()
            s.bytesDown += m.payload.size
            totalDown.addAndGet(m.payload.size.toLong())
        } catch (e: IOException) {
            log("bridge: stream ${s.localId} write failed: ${e.message}")
            closeStream(s, notifyCar = true)
            return
        }
        send(AdbWire.encode(AdbWire.A_OKAY, s.localId, s.remoteId))
    }

    /** Phone → car: relay everything the local server writes, one OKAY-gated WRTE at a time. */
    private fun pump(s: Stream) {
        val buf = ByteArray(maxData.coerceIn(64, 64 * 1024))
        try {
            val input = s.sock.getInputStream()
            while (!s.closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (!s.credit.tryAcquire(10, TimeUnit.SECONDS)) {
                    log("bridge: stream ${s.localId} no OKAY from car for 10 s — closing")
                    break
                }
                if (s.closed) break
                s.bytesUp += n
                totalUp.addAndGet(n.toLong())
                send(AdbWire.encode(AdbWire.A_WRTE, s.localId, s.remoteId, buf.copyOf(n)))
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            closeStream(s, notifyCar = true)
        }
    }

    private fun closeStream(s: Stream, notifyCar: Boolean) {
        if (streams.remove(s.localId) == null) return
        s.closed = true
        try { s.sock.close() } catch (_: IOException) {}
        log("bridge: stream ${s.localId} (:${s.port}) closed — up=${s.bytesUp} B down=${s.bytesDown} B")
        if (notifyCar) {
            try { send(AdbWire.encode(AdbWire.A_CLSE, s.localId, s.remoteId)) } catch (_: Exception) {}
        }
    }

    /** Close every stream without telling the car (the USB link itself is going away). */
    fun closeAll() {
        shutdown = true
        for (s in streams.values.toList()) closeStream(s, notifyCar = false)
    }

    companion object {
        fun connectLoopback(port: Int): Socket? {
            val sock = Socket()
            return try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress("127.0.0.1", port), 2000)
                sock
            } catch (_: IOException) {
                try { sock.close() } catch (_: IOException) {}
                null
            }
        }
    }
}
