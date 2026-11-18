package dev.zanderp.opencfmoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbWireTest {

    private fun cnxn() = AdbWire.encode(AdbWire.A_CNXN, 0x01000001, 4096, "host::features=x\u0000".toByteArray())

    @Test fun cnxn_roundtrip() {
        val events = AdbWire.Parser().feed(cnxn())
        assertEquals(1, events.size)
        val m = (events[0] as AdbWire.Event.Msg).message
        assertEquals("CNXN", m.name)
        assertEquals(0x01000001, m.arg0)
        assertEquals(4096, m.arg1)
        assertTrue(m.checksumOk)
        assertEquals("host::features=x\u0000", String(m.payload))
    }

    @Test fun header_bytes_are_little_endian_with_inverted_magic() {
        val raw = AdbWire.encode(AdbWire.A_OKAY, 1, 2)
        assertEquals(24, raw.size)
        // "OKAY" little-endian command, then magic = ~command
        assertArrayEquals(byteArrayOf(0x4f, 0x4b, 0x41, 0x59), raw.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0xb0.toByte(), 0xb4.toByte(), 0xbe.toByte(), 0xa6.toByte()), raw.copyOfRange(20, 24))
    }

    @Test fun split_delivery_is_reassembled() {
        val raw = cnxn()
        val p = AdbWire.Parser()
        assertTrue(p.feed(raw, 10).isEmpty())
        val rest = raw.copyOfRange(10, raw.size)
        val events = p.feed(rest)
        assertEquals(1, events.size)
    }

    @Test fun two_messages_in_one_read() {
        val a = AdbWire.encode(AdbWire.A_OPEN, 7, 0, "shell:\u0000".toByteArray())
        val b = AdbWire.encode(AdbWire.A_OKAY, 7, 8)
        val events = AdbWire.Parser().feed(a + b)
        assertEquals(2, events.size)
        assertEquals("OPEN", (events[0] as AdbWire.Event.Msg).message.name)
        assertEquals("OKAY", (events[1] as AdbWire.Event.Msg).message.name)
    }

    @Test fun non_adb_bytes_are_reported_and_buffer_reset() {
        val p = AdbWire.Parser()
        val junk = ByteArray(40) { it.toByte() }
        val events = p.feed(junk)
        assertEquals(1, events.size)
        assertTrue(events[0] is AdbWire.Event.NotAdb)
        // parser recovers: a following valid message still parses
        assertEquals(1, p.feed(cnxn()).size)
    }

    @Test fun bad_checksum_is_flagged_not_dropped() {
        val raw = cnxn()
        raw[24] = (raw[24].toInt() xor 0x55).toByte()          // corrupt a payload byte
        val m = (AdbWire.Parser().feed(raw)[0] as AdbWire.Event.Msg).message
        assertFalse(m.checksumOk)
    }

    @Test fun oversized_length_is_not_adb() {
        val raw = AdbWire.encode(AdbWire.A_WRTE, 1, 2)
        // overwrite data_length with a huge value; magic still valid for the command
        raw[12] = 0xff.toByte(); raw[13] = 0xff.toByte(); raw[14] = 0xff.toByte(); raw[15] = 0x7f
        assertTrue(AdbWire.Parser().feed(raw)[0] is AdbWire.Event.NotAdb)
    }

    @Test fun cnxn_reply_parses_back() {
        val m = (AdbWire.Parser().feed(AdbWire.cnxnReply())[0] as AdbWire.Event.Msg).message
        assertEquals("CNXN", m.name)
        assertTrue(m.checksumOk)
    }

    @Test fun cnxn_reply_echoes_host_version() {
        val m = (AdbWire.Parser().feed(AdbWire.cnxnReply(0x01000000, 0x1000))[0] as AdbWire.Event.Msg).message
        assertEquals(0x01000000, m.arg0)
        assertEquals(0x1000, m.arg1)
    }

    @Test fun stream_reply_open_write_close() {
        fun msg(b: ByteArray) = (AdbWire.Parser().feed(b)[0] as AdbWire.Event.Msg).message
        val open = AdbWire.Message(AdbWire.A_OPEN, 7, 0, "tcp:1\u0000".toByteArray(), true)
        val ok = msg(AdbWire.streamReply(open, 1)!!)
        assertEquals("OKAY", ok.name); assertEquals(1, ok.arg0); assertEquals(7, ok.arg1)
        val wrte = AdbWire.Message(AdbWire.A_WRTE, 7, 1, byteArrayOf(1), true)
        val ack = msg(AdbWire.streamReply(wrte, 1)!!)
        assertEquals("OKAY", ack.name); assertEquals(1, ack.arg0); assertEquals(7, ack.arg1)
        assertEquals(null, AdbWire.streamReply(AdbWire.Message(AdbWire.A_OKAY, 1, 7, ByteArray(0), true), 1))
    }

    @Test fun hex_and_ascii_previews() {
        assertEquals("41 42 43", AdbWire.hex(byteArrayOf(0x41, 0x42, 0x43)))
        assertEquals("41 42 …(+1)", AdbWire.hex(byteArrayOf(0x41, 0x42, 0x43), max = 2))
        assertEquals("AB.", AdbWire.ascii(byteArrayOf(0x41, 0x42, 0x00)))
    }
}
