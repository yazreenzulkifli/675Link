// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAdbBridgeTest {

    private val sent = Collections.synchronizedList(ArrayList<AdbWire.Message>())

    private fun parse(b: ByteArray) =
        (AdbWire.Parser().feed(b)[0] as AdbWire.Event.Msg).message

    private fun bridgeTo(server: ServerSocket?) = UsbAdbBridge(
        send = { sent.add(parse(it)) },
        log = {},
        connect = { _ -> server?.let { Socket("127.0.0.1", it.localPort) } },
    )

    private fun msg(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) =
        AdbWire.Message(cmd, a0, a1, data, true)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
        throw AssertionError("timed out waiting for $what; sent=${sent.map { it.name }}")
    }

    @Test fun relays_both_directions_with_okay_flow_control() {
        ServerSocket(0).use { server ->
            val b = bridgeTo(server)
            b.onMessage(msg(AdbWire.A_OPEN, 0x1f1, 0, "tcp:10922\u0000".toByteArray()))
            waitFor("OKAY for OPEN") { sent.any { it.command == AdbWire.A_OKAY } }
            val ok = sent.first { it.command == AdbWire.A_OKAY }
            assertEquals(0x1f1, ok.arg1)
            val local = ok.arg0

            val svc = server.accept()
            // car -> phone service
            b.onMessage(msg(AdbWire.A_WRTE, 0x1f1, local, "hello".toByteArray()))
            val got = ByteArray(5); svc.getInputStream().readNBytes(got, 0, 5)
            assertEquals("hello", String(got))
            waitFor("OKAY for WRTE") { sent.count { it.command == AdbWire.A_OKAY } == 2 }

            // phone service -> car, gated by OKAY
            svc.getOutputStream().write("one".toByteArray()); svc.getOutputStream().flush()
            waitFor("first WRTE") { sent.any { it.command == AdbWire.A_WRTE } }
            val w1 = sent.first { it.command == AdbWire.A_WRTE }
            assertEquals(local, w1.arg0); assertEquals(0x1f1, w1.arg1)
            assertArrayEquals("one".toByteArray(), w1.payload)

            svc.getOutputStream().write("two".toByteArray()); svc.getOutputStream().flush()
            Thread.sleep(150)
            assertEquals("second WRTE must wait for the car's OKAY", 1, sent.count { it.command == AdbWire.A_WRTE })
            b.onMessage(msg(AdbWire.A_OKAY, 0x1f1, local))
            waitFor("second WRTE") { sent.count { it.command == AdbWire.A_WRTE } == 2 }

            // car closes
            b.onMessage(msg(AdbWire.A_CLSE, 0x1f1, local))
            waitFor("stream removed") { b.openStreams == 0 }
            assertTrue(sent.any { it.command == AdbWire.A_CLSE })
            svc.close()
        }
    }

    @Test fun refuses_open_when_nothing_listens() {
        val b = bridgeTo(null)
        b.onMessage(msg(AdbWire.A_OPEN, 7, 0, "tcp:10922\u0000".toByteArray()))
        waitFor("CLSE") { sent.isNotEmpty() }
        val m = sent.single()
        assertEquals("CLSE", m.name); assertEquals(0, m.arg0); assertEquals(7, m.arg1)
    }

    @Test fun open_waits_for_local_server_to_appear() {
        ServerSocket(0).use { server ->
            val up = java.util.concurrent.atomic.AtomicBoolean(false)
            val b = UsbAdbBridge(
                send = { sent.add(parse(it)) }, log = {},
                connect = { _ -> if (up.get()) Socket("127.0.0.1", server.localPort) else null },
                connectRetryMs = 5000,
            )
            b.onMessage(msg(AdbWire.A_OPEN, 5, 0, "tcp:10922\u0000".toByteArray()))
            Thread.sleep(600)
            assertTrue("must not answer while waiting", sent.isEmpty())
            up.set(true)
            waitFor("OKAY after server came up") { sent.any { it.command == AdbWire.A_OKAY } }
            assertEquals(1, b.openStreams)
            b.closeAll()
        }
    }

    @Test fun refuses_unsupported_destination() {
        ServerSocket(0).use { server ->
            val b = bridgeTo(server)
            b.onMessage(msg(AdbWire.A_OPEN, 9, 0, "shell:ls\u0000".toByteArray()))
            assertEquals("CLSE", sent.single().name)
            assertEquals(0, b.openStreams)
        }
    }

    @Test fun service_closing_closes_the_stream_toward_the_car() {
        ServerSocket(0).use { server ->
            val b = bridgeTo(server)
            b.onMessage(msg(AdbWire.A_OPEN, 3, 0, "tcp:10920\u0000".toByteArray()))
            waitFor("OKAY") { sent.isNotEmpty() }
            server.accept().close()
            waitFor("CLSE toward car") { sent.any { it.command == AdbWire.A_CLSE } }
            assertNotNull(sent.first { it.command == AdbWire.A_CLSE })
            assertEquals(0, b.openStreams)
        }
    }
}
