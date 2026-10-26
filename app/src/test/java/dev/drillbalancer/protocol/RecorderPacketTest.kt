package dev.drillbalancer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// rec_ctrl and rec_state vectors, cross-checked against firmware/test_host/ble/test_ble_codec.c
// (test_rec_*) and tools/ble/db_client.py (self_check).

private fun <T> Decoded<T>.ok(): T = (this as? Decoded.Ok)?.value ?: throw AssertionError("not decoded: $this")

class RecorderPacketTest {
    @Test
    fun takeStartBytes() {
        // profile 2, mics 3, 6 dB, max_s 1200 (0x04B0), take 0x12345678
        val w = RecCtrl.TakeStart(TakeRequest(RecProfile.RAW448_16G, RecOpcode.MIC_ALL, 6, 1200, 0x12345678)).encode()
        assertArrayEquals(hex("03 02 03 06 b004 78563412"), w)
        assertEquals(RecOpcode.TAKE_START_LEN, w.size)
        assertArrayEquals(byteArrayOf(1), RecCtrl.Open.encode())
        assertArrayEquals(byteArrayOf(2), RecCtrl.Close.encode())
        assertArrayEquals(byteArrayOf(4), RecCtrl.Stop.encode())
        // The largest take id is unsigned on the wire.
        assertArrayEquals(hex("03 01 00 00 0000 ffffffff"), RecCtrl.TakeStart(TakeRequest(RecProfile.RAW448, 0, 0, 0, 0xFFFF_FFFFL)).encode())
    }

    @Test
    fun takeStartChecksItsArguments() {
        for (bad in listOf<() -> Unit>(
            { TakeRequest(RecProfile.RAW448, micMask = 4, takeId = 1) },
            { TakeRequest(RecProfile.RAW448, gainDb = 38, takeId = 1) },
            { TakeRequest(RecProfile.RAW448, maxS = 1201, takeId = 1) },
            { TakeRequest(RecProfile.RAW448, takeId = 1L shl 32) },
        )) {
            try {
                bad()
                throw AssertionError("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun markBytesAndText() {
        assertArrayEquals(byteArrayOf(5, 0xEF.toByte(), 0xBE.toByte()) + "hole 1".encodeToByteArray(), RecCtrl.Mark(0xBEEF, "hole 1").encode())
        assertArrayEquals(hex("05 efbe") + "hole 1 – oak".encodeToByteArray(), RecCtrl.Mark(0xBEEF, "hole 1 – oak").encode())
        assertTrue(RecCtrl.markTextOk("x".repeat(RecOpcode.MARK_TEXT_MAX)))
        assertFalse(RecCtrl.markTextOk("x".repeat(RecOpcode.MARK_TEXT_MAX + 1)))
        // test_rec_mark_text_must_be_printable_utf8: controls, DEL, C1, a lone surrogate.
        for (bad in listOf("a\nb", "tab\t", "nul\u0000x", "del\u007F", "\u0080", "\u0085", "\u009F", "\uD800", "x\uDC00", "\uD83D")) {
            assertFalse(bad, RecCtrl.markTextOk(bad))
        }
        // Two-, three- and four-byte characters, and the edges of each range.
        assertTrue(RecCtrl.markTextOk("ä€🔩"))
        assertTrue(RecCtrl.markTextOk(" ࠀ퟿𐀀􏿿"))
    }

    private fun status(state: RecState) = RecorderStatus(
        state = state, reasonCode = RecReason.MAX_S.wire, flags = RecFlags.CLIENT or RecFlags.AUDIO, takeId = 0xA1B2C3D4,
        elapsedS = 83, maxS = 300, sentKib = 5120, dropped = 7, ringPct = 12, profileCode = RecProfile.RAW448.wire,
        channel = 6, port = 47100, ip = "192.168.4.1", ssid = "DB-1a2b-k7q2", pass = "abcdefghijklmnopqrst",
        token = (0xF0..0xFF).joinToString("") { "%02x".format(it) },
    )

    @Test
    fun recStateVector() {
        // test_rec_state_packet / db_client self_check
        val p = status(RecState.RECORDING)
        val raw = p.encode()
        assertEquals(RecorderStatus.SIZE, raw.size)
        assertArrayEquals(hex("01 04 02 06 d4c3b2a1 5300 2c01 00140000 07000000 0c 01 06 00"), raw.copyOf(24))
        assertArrayEquals(hex("fcb7 c0a80401"), raw.copyOfRange(24, 30))
        assertArrayEquals("DB-1a2b-k7q2".encodeToByteArray().copyOf(16), raw.copyOfRange(30, 46))
        assertArrayEquals("abcdefghijklmnopqrst".encodeToByteArray().copyOf(24), raw.copyOfRange(46, 70))
        assertArrayEquals(ByteArray(16) { (0xF0 + it).toByte() }, raw.copyOfRange(70, 86))
        val d = RecorderStatus.decode(raw).ok()
        assertEquals(p, d)
        assertEquals(RecReason.MAX_S, d.reason)
        assertEquals(RecProfile.RAW448, d.profile)
        assertTrue(d.clientConnected && d.audio && !d.noAp && !d.imuLost && !d.batteryLow)
        assertTrue(d.reachable)
        assertArrayEquals(ByteArray(16) { (0xF0 + it).toByte() }, d.tokenBytes())
    }

    @Test
    fun fullLengthStringsHaveNoNul() {
        // test_rec_state_full_length_strings
        val p = status(RecState.WAIT_CLIENT).copy(ssid = "S".repeat(16), pass = "P".repeat(24))
        val d = RecorderStatus.decode(p.encode()).ok()
        assertEquals("S".repeat(16), d.ssid)
        assertEquals("P".repeat(24), d.pass)
    }

    @Test
    fun hiddenCredentialsAreNotReachable() {
        // test_rec_state_hides_credentials_when_not_joinable: bytes 24..85 zero
        for (state in listOf(RecState.IDLE, RecState.AP_STARTING, RecState.CLOSING)) {
            val raw = status(state).encode().also { it.fill(0, 24, 86) }
            val d = RecorderStatus.decode(raw).ok()
            assertEquals(Triple(0, "", ""), Triple(d.port, d.ip, d.ssid))
            assertEquals("0".repeat(32), d.token)
            assertFalse(d.reachable)
            assertEquals(0xA1B2C3D4L, d.takeId)
        }
        // The simulator on the LAN: NO_AP, no SSID.
        val lan = RecorderStatus.decode(status(RecState.WAIT_CLIENT).copy(flags = RecFlags.NO_AP, ssid = "", pass = "", ip = "127.0.0.1").encode()).ok()
        assertTrue(lan.noAp && lan.reachable)
        assertEquals("127.0.0.1" to "", lan.ip to lan.ssid)
        assertFalse(RecorderStatus.NOT_READ.read)
        assertFalse(RecorderStatus.NOT_READ.reachable)
    }

    @Test
    fun badPackets() {
        val raw = status(RecState.READY).encode()
        assertTrue(RecorderStatus.decode(raw.copyOf(85)) is Decoded.Invalid)
        assertTrue(RecorderStatus.decode(raw.copyOf().also { it[0] = 2 }) is Decoded.Invalid)
        assertTrue(RecorderStatus.decode(raw.copyOf().also { it[1] = 7 }) is Decoded.Invalid)
        // An unknown reason or profile still decodes.
        val odd = RecorderStatus.decode(raw.copyOf().also { it[2] = 99; it[21] = 9 }).ok()
        assertEquals(null, odd.reason)
        assertEquals(null, odd.profile)
    }
}
