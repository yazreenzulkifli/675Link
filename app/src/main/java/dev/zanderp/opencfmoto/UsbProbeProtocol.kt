// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EXPERIMENTAL — Perodua Smart Link / Carbit USB probe.
 *
 * The Carbit "Oversea" driver apps (CarbitLink, Perodua Smart Link) register for the USB accessory
 * manufacturer "Carbit", model "CarbitDriverOversea", version "1" and bundle an ADB-style daemon
 * library. The probe therefore *guesses* that the car talks ADB-style framing over the accessory
 * pipe. This file only parses and builds that framing so the probe can log what the car really
 * sends; nothing here is confirmed protocol knowledge. Pure Kotlin (no Android types) so it is
 * unit-testable on the JVM.
 */
object AdbWire {
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 1 shl 20          // sanity bound; real ADB payloads are far smaller

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    fun commandName(cmd: Int): String = when (cmd) {
        A_SYNC -> "SYNC"
        A_CNXN -> "CNXN"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_AUTH -> "AUTH"
        else -> String.format("0x%08x", cmd)
    }

    /** Sum of payload bytes (the "data_check" field used by protocol version 0x01000000). */
    fun checksum(data: ByteArray): Int {
        var s = 0
        for (b in data) s += b.toInt() and 0xff
        return s
    }

    fun encode(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(checksum(data)).putInt(cmd xor -1)
        bb.put(data)
        return bb.array()
    }

    /**
     * CNXN reply for the optional "reply to handshake" probe mode. Real-car capture showed the head
     * unit opens with version 0x01000000 / maxdata 0x1000 and banner "host::"; the phone-side
     * daemon (libjadbd) has separate "old"/"new" version paths, so we echo the host's values
     * instead of advertising a newer version, with a minimal "device::" banner.
     */
    fun cnxnReply(hostVersion: Int = 0x01000000, hostMaxData: Int = 4096): ByteArray {
        val banner = "device::\u0000".toByteArray(Charsets.US_ASCII)
        return encode(A_CNXN, hostVersion, hostMaxData, banner)
    }

    /**
     * One way of answering the car's CNXN. [version]/[maxData] null = echo the host's value.
     * [split] sends header and payload as two USB writes (like the car does), [gapMs] pauses
     * between them. Lets one trip to the car try several guesses about what the car accepts.
     */
    data class ReplyVariant(
        val label: String,
        val version: Int? = null,
        val maxData: Int? = null,
        val banner: String = "device::\u0000",
        val split: Boolean = true,
        val gapMs: Long = 0,
    )

    val VARIANTS: List<ReplyVariant> = listOf(
        ReplyVariant("Echo version, minimal banner, split writes"),
        ReplyVariant("Echo version, minimal banner, ONE write", split = false),
        ReplyVariant("Echo version, full props banner, split",
            banner = "device::ro.product.name=probe;ro.product.model=probe;ro.product.device=probe;features=\u0000"),
        ReplyVariant("New version 0x01000001, minimal banner, split", version = 0x01000001),
        ReplyVariant("Echo version, maxdata 256K, split", maxData = 256 * 1024),
        ReplyVariant("Echo version, split with 100 ms gap", gapMs = 100),
        ReplyVariant("Echo version, banner without NUL, split", banner = "device::"),
        ReplyVariant("Echo version, EMPTY payload", banner = ""),
    )

    fun cnxnReply(v: ReplyVariant, hostVersion: Int, hostMaxData: Int): ByteArray =
        encode(A_CNXN, v.version ?: hostVersion, v.maxData ?: hostMaxData, v.banner.toByteArray(Charsets.US_ASCII))

    /**
     * Probe-only stream responder so we can see what the car asks for after the handshake: accept
     * every OPEN, acknowledge every WRTE, mirror CLSE. Returns the bytes to send back, or null.
     */
    fun streamReply(m: Message, localId: Int): ByteArray? = when (m.command) {
        A_OPEN -> encode(A_OKAY, localId, m.arg0)
        A_WRTE -> encode(A_OKAY, m.arg1, m.arg0)
        A_CLSE -> encode(A_CLSE, m.arg1, m.arg0)
        else -> null
    }

    /** One parsed message. [payload] is empty for header-only messages. */
    data class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray, val checksumOk: Boolean) {
        val name: String get() = commandName(command)
        override fun equals(other: Any?): Boolean =
            other is Message && command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
                payload.contentEquals(other.payload) && checksumOk == other.checksumOk
        override fun hashCode(): Int =
            (((command * 31 + arg0) * 31 + arg1) * 31 + payload.contentHashCode()) * 31 + checksumOk.hashCode()
    }

    sealed class Event {
        data class Msg(val message: Message) : Event()
        /** Bytes that do not look like ADB framing; the parser dropped its buffer. */
        data class NotAdb(val preview: ByteArray, val totalDropped: Int) : Event() {
            override fun equals(other: Any?): Boolean =
                other is NotAdb && preview.contentEquals(other.preview) && totalDropped == other.totalDropped
            override fun hashCode(): Int = preview.contentHashCode() * 31 + totalDropped
        }
    }

    /** Incremental stream parser: feed it whatever the USB pipe returns, get complete messages back. */
    class Parser {
        private val buf = ByteArrayOutputStream()

        fun feed(bytes: ByteArray, len: Int = bytes.size): List<Event> {
            buf.write(bytes, 0, len)
            val out = ArrayList<Event>()
            while (true) {
                val all = buf.toByteArray()
                if (all.size < HEADER_SIZE) break
                val h = ByteBuffer.wrap(all, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = h.int; val a0 = h.int; val a1 = h.int; val dlen = h.int; val dchk = h.int; val magic = h.int
                val valid = magic == (cmd xor -1) && dlen in 0..MAX_PAYLOAD
                if (!valid) {
                    val preview = all.copyOfRange(0, minOf(all.size, 64))
                    out.add(Event.NotAdb(preview, all.size))
                    buf.reset()
                    break
                }
                val total = HEADER_SIZE + dlen
                if (all.size < total) break      // wait for the rest of the payload
                val payload = all.copyOfRange(HEADER_SIZE, total)
                out.add(Event.Msg(Message(cmd, a0, a1, payload, checksum(payload) == dchk)))
                buf.reset()
                if (all.size > total) buf.write(all, total, all.size - total)
            }
            return out
        }
    }

    fun hex(bytes: ByteArray, max: Int = 64): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) { if (i > 0) sb.append(' '); sb.append(String.format("%02x", bytes[i].toInt() and 0xff)) }
        if (bytes.size > n) sb.append(" …(+").append(bytes.size - n).append(')')
        return sb.toString()
    }

    /** Printable-ASCII view of up to [max] bytes (non-printables become '.'). */
    fun ascii(bytes: ByteArray, max: Int = 96): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n)
        for (i in 0 until n) { val c = bytes[i].toInt() and 0xff; sb.append(if (c in 32..126) c.toChar() else '.') }
        return sb.toString()
    }
}
