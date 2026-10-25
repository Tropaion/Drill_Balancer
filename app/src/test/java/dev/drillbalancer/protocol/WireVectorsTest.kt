package dev.drillbalancer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

// Byte-exact vectors, cross-checked against firmware/test_host/ble/test_ble_codec.c,
// test_host/ota/test_ota_proto.c and tools/ble/db_client.py (self_check).

internal fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun <T> Decoded<T>.ok(): T = (this as? Decoded.Ok)?.value ?: throw AssertionError("not decoded: $this")

class WireVectorsTest {
    // ---- angle packet ----

    @Test
    fun angleVectorFromDbClient() {
        // Angle(1, 2, imu_ok|near, 255, -12.34, 179.99, 0.01, -0.01, 327.67)
        val p = AnglePacket.of(LevelMode.ANGLE, AngleFlags.IMU_OK or AngleFlags.NEAR, 255, -12.34f, 179.99f, 0.01f, -0.01f, 327.67f)
        val raw = hex("01 02 42 ff 2efb 4f46 0100 ffff ff7f")
        assertArrayEquals(raw, p.encode())
        val d = AnglePacket.decode(raw).ok()
        assertEquals(p, d)
        assertEquals(-12.34f, d.elevationDeg, 0f)
        assertEquals(179.99f, d.yawDeg, 0f)
        assertEquals(327.67f, d.deviationDeg, 0f)
        assertEquals(LevelStatus.NEAR, d.status)
    }

    @Test
    fun angleLittleEndianBytesFromCodecTest() {
        // elevation -1.0 = 0xFF9C at [4..5], deviation 2.56 = 0x0100 at [12..13]
        val b = AnglePacket.of(LevelMode.WALL, AngleFlags.IMU_OK, elevationDeg = -1.0f, deviationDeg = 2.56f).encode()
        assertEquals(0x9C.toByte(), b[4])
        assertEquals(0xFF.toByte(), b[5])
        assertEquals(0x00.toByte(), b[12])
        assertEquals(0x01.toByte(), b[13])
    }

    @Test
    fun angleFieldsFromCodecTest() {
        val p = AnglePacket.of(LevelMode.ANGLE, 0, 42, 45.5f, -3.21f, 1.0f, -2.0f, 0.5f)
        assertEquals(42, p.seq)
        assertEquals(4550, p.elevationCdeg)
        assertEquals(-321, p.yawCdeg)
        assertEquals(100, p.bubbleXCdeg)
        assertEquals(-200, p.bubbleYCdeg)
        assertEquals(50, p.deviationCdeg)
        assertEquals(1, p.encode()[0].toInt())
    }

    @Test
    fun cdegRoundsAndSaturatesLikeTheFirmware() {
        // test_cdeg_rounds_to_nearest / saturates / nan_is_zero
        val cases = mapOf(
            0.0f to 0, 12.34f to 1234, -12.34f to -1234, 0.006f to 1, -0.006f to -1, 0.004f to 0,
            180.0f to 18000, -180.0f to -18000,
            327.67f to 32767, 327.68f to 32767, 1000.0f to 32767, 1e30f to 32767, Float.POSITIVE_INFINITY to 32767,
            -327.68f to -32768, -327.69f to -32768, -1e30f to -32768, Float.NEGATIVE_INFINITY to -32768,
            Float.NaN to 0, 0.005f to 1, -0.005f to -1,   // roundf: half away from zero
        )
        for ((deg, cdeg) in cases) assertEquals("cdeg($deg)", cdeg, AnglePacket.cdeg(deg))
    }

    @Test
    fun angleFlags() {
        val z = AnglePacket.decode(AnglePacket.of(LevelMode.FLOOR, AngleFlags.ZERO_SET).encode()).ok()
        assertTrue(z.zeroSet)
        assertFalse(z.yawValid)
        assertNull("no IMU_OK: no status, never green", z.status)
        val all = AnglePacket.of(LevelMode.WALL, 0x7F)
        assertTrue(all.onTarget && all.near && all.yawValid && all.yawStale && all.zeroSet && all.still && all.imuOk)
        assertEquals(LevelStatus.ON_TARGET, all.status)
        assertEquals(LevelStatus.OFF, AnglePacket.of(LevelMode.WALL, AngleFlags.IMU_OK).status)
    }

    @Test
    fun angleRejectsBadPackets() {
        val good = AnglePacket.of(LevelMode.WALL, 0).encode()
        assertTrue(AnglePacket.decode(good.copyOf(13)) is Decoded.Invalid)
        assertTrue(AnglePacket.decode(good.copyOf(15)) is Decoded.Invalid)
        assertTrue(AnglePacket.decode(ByteArray(0)) is Decoded.Invalid)
        assertTrue(AnglePacket.decode(good.copyOf().also { it[0] = 2 }) is Decoded.Invalid)
        assertTrue(AnglePacket.decode(good.copyOf().also { it[1] = 3 }) is Decoded.Invalid)
        val msg = (AnglePacket.decode(good.copyOf(13)) as Decoded.Invalid).reason
        assertTrue(msg, "13 bytes" in msg)
    }

    // ---- status packet ----

    @Test
    fun statusVectorFromDbClient() {
        // Status(1, 87, 4012, charging|vbus, 1, 2, 255, 0x3F, -1)
        val s = StatusPacket(87, 4012, StatusFlags.CHARGING or StatusFlags.VBUS, PowerState.DIM, CalKind.ACCEL, 255, 0x3F, -1)
        val raw = hex("01 57 ac0f 0a 01 02 ff 3f ff")
        assertArrayEquals(raw, s.encode())
        val d = StatusPacket.decode(raw).ok()
        assertEquals(s, d)
        assertEquals(-1, d.calLastResult)
        assertTrue(d.charging && d.vbus && !d.batteryValid && !d.calibrating)
        assertEquals(CoverageDir.entries.toSet(), d.coverage)
        assertEquals(CalResult.None, d.lastResult(CalKind.ACCEL))
    }

    @Test
    fun statusFieldsFromCodecTest() {
        // test_status_packet_fields: flags BATTERY_VALID|CHARGING|VBUS|MOUNT_CAL|CALIBRATING, last_result 2
        val raw = hex("01 57 ac0f 5b 01 02 05 2b 02")
        val d = StatusPacket.decode(raw).ok()
        assertEquals(87, d.batteryPct)
        assertEquals(4012, d.batteryMv)
        assertTrue(d.batteryValid && d.charging && d.vbus && d.mountCalValid && d.calibrating)
        assertFalse(d.chargeDone || d.accelCalValid)
        assertEquals(PowerState.DIM, d.powerState)
        assertEquals(CalKind.ACCEL, d.calKind)
        assertEquals(5, d.calSamples)
        assertEquals(setOf(CoverageDir.POS_X, CoverageDir.NEG_X, CoverageDir.NEG_Y, CoverageDir.NEG_Z), d.coverage)
        assertEquals(CalResult.Mount(MountCalResult.TOO_LITTLE_ROLL), d.lastResult(CalKind.MOUNT))
        assertEquals(CalResult.Sensor(SensorCalResult.FIT_FAILED), d.lastResult(CalKind.ACCEL))
    }

    @Test
    fun statusRejectsBadPackets() {
        val good = StatusPacket(0, 0, 0, PowerState.ACTIVE, CalKind.IDLE, 0, 0, -1).encode()
        assertTrue(StatusPacket.decode(good.copyOf(11)) is Decoded.Invalid)
        assertTrue(StatusPacket.decode(good.copyOf(9)) is Decoded.Invalid)
        assertTrue(StatusPacket.decode(good.copyOf().also { it[0] = 0 }) is Decoded.Invalid)
        assertTrue(StatusPacket.decode(good.copyOf().also { it[5] = 4 }) is Decoded.Invalid)
        assertTrue(StatusPacket.decode(good.copyOf().also { it[6] = 3 }) is Decoded.Invalid)
    }

    @Test
    fun calLastResultDependsOnKind() {
        assertEquals(CalResult.None, CalResult.decode(-1, CalKind.MOUNT))
        assertEquals(CalResult.NotStored, CalResult.decode(-2, CalKind.MOUNT))
        assertEquals(CalResult.NotStored, CalResult.decode(-2, CalKind.ACCEL))
        assertEquals(CalResult.SensorOkMountReset, CalResult.decode(-3, CalKind.ACCEL))
        assertTrue(CalResult.decode(-3, CalKind.ACCEL).success)
        // 0 for a sensor cal: OK, no mount cal to reset; for a mount cal: mount OK
        assertEquals(CalResult.Sensor(SensorCalResult.OK), CalResult.decode(0, CalKind.ACCEL))
        assertEquals(CalResult.Mount(MountCalResult.OK), CalResult.decode(0, CalKind.MOUNT))
        assertEquals(CalResult.Mount(MountCalResult.OFFSET_TOO_LARGE), CalResult.decode(4, CalKind.MOUNT))
        assertEquals(CalResult.Unknown(4), CalResult.decode(4, CalKind.ACCEL))
        assertEquals(CalResult.Unknown(0), CalResult.decode(0, CalKind.IDLE))
        assertEquals(CalResult.Unknown(-4), CalResult.decode(-4, CalKind.ACCEL))
        assertFalse(CalResult.decode(1, CalKind.ACCEL).success)
    }

    @Test
    fun batteryLevel() {
        assertEquals(63, decodeBatteryLevel(byteArrayOf(63)).ok())
        assertTrue(decodeBatteryLevel(byteArrayOf(101)) is Decoded.Invalid)
        assertTrue(decodeBatteryLevel(byteArrayOf(1, 2)) is Decoded.Invalid)
    }

    // ---- commands ----

    @Test
    fun commandBytes() {
        val cases = mapOf(
            Command.Zero to "01", Command.ClearZero to "02",
            Command.SetMode(LevelMode.WALL) to "0300", Command.SetMode(LevelMode.FLOOR) to "0301",
            Command.SetMode(LevelMode.ANGLE) to "0302",
            Command.MountCalStart to "10", Command.MountCalFinish to "11",
            Command.AccelCalStart to "12", Command.AccelCalFinish to "13", Command.CalCancel to "14",
            Command.Sleep to "20", Command.PowerOff to "21", Command.Reboot to "22",
            Command.FactoryReset to "23a5",
        )
        for ((cmd, bytes) in cases) assertArrayEquals(cmd.toString(), hex(bytes), cmd.encode())
    }

    @Test
    fun attErrors() {
        assertTrue(AttError.fromCode(0x05)!!.needsPairing)
        assertTrue(AttError.fromCode(0x0F)!!.needsPairing)
        assertTrue(AttError.fromCode(0x0C)!!.needsPairing)
        assertFalse(AttError.fromCode(0x0D)!!.needsPairing)
        for (code in 0x80..0x87) assertTrue(AttError.fromCode(code) != null)
        assertEquals("Bluetooth error 0x8F.", AttError.messageFor(0x8F))
        assertEquals(AttError.BUSY.message, AttError.messageFor(0x86))
    }

    // ---- OTA messages ----

    @Test
    fun otaStartVector() {
        val sha = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha.toHex())
        val start = OtaMessage.encodeStart(0x01020304, sha)
        assertEquals(37, start.size)
        assertArrayEquals(hex("01 04030201"), start.copyOf(5))
        assertArrayEquals(sha, start.copyOfRange(5, 37))
    }

    @Test
    fun otaRequestsAndData() {
        assertArrayEquals(hex("02"), OtaMessage.encodeFinish())
        assertArrayEquals(hex("03"), OtaMessage.encodeAbort())
        assertArrayEquals(hex("0200") + "xy".toByteArray(), OtaMessage.encodeData(0x10002, "xy".toByteArray()))
        assertArrayEquals(hex("ffff 61"), OtaMessage.encodeData(0xFFFF, "abc".toByteArray(), 0, 1))
        assertArrayEquals(hex("0100 6263"), OtaMessage.encodeData(1, "abc".toByteArray(), 1, 3))
    }

    @Test
    fun otaIndicationVectors() {
        assertEquals(OtaMessage.Reply(1, 0, 242, 16), OtaMessage.decode(hex("80 01 00 f200 1000")).ok())
        assertEquals(OtaMessage.Reply(2, 6), OtaMessage.decode(hex("80 02 06")).ok())
        assertEquals(OtaMessage.Reply(1, 3), OtaMessage.decode(hex("80 01 03")).ok())     // START TOO_LARGE
        assertEquals(OtaMessage.Reply(0, 1), OtaMessage.decode(hex("80 00 01")).ok())     // empty write
        assertEquals(OtaMessage.Ack(0x1234, 0x12345678), OtaMessage.decode(hex("81 3412 78563412")).ok())
        assertEquals(OtaMessage.Ack(0xFFFF, 0xFFFFFFFFL), OtaMessage.decode(hex("81 ffff ffffffff")).ok())
        assertEquals(OtaMessage.Nack(5, 7), OtaMessage.decode(hex("82 0500 07")).ok())
        for (m in listOf(OtaMessage.Reply(1, 0, 242, 16), OtaMessage.Reply(3, 2), OtaMessage.Ack(70000 and 0xFFFF, 70000), OtaMessage.Nack(0xFFFF, 8))) {
            assertEquals(m, OtaMessage.decode(m.encode()).ok())
        }
    }

    @Test
    fun otaRejectsMalformedIndications() {
        for (bad in listOf("", "80", "80 01", "80 01 00", "80 01 00 f200 10", "80 02 00 00", "81 0000", "82 0000", "83 000000", "82 0500 07 00")) {
            assertTrue(bad, OtaMessage.decode(hex(bad)) is Decoded.Invalid)
        }
    }

    @Test
    fun uuids() {
        assertEquals("d71b0101-8a3e-4f6c-b5d2-1c9e0a7f4b63", Uuids.CHR_OTA_CTRL.toString())
        assertEquals("d71b0001-8a3e-4f6c-b5d2-1c9e0a7f4b63", Uuids.SVC_MAIN.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", Uuids.CHR_BATTERY_LEVEL.toString())
        assertEquals("0000180a-0000-1000-8000-00805f9b34fb", Uuids.SVC_DEVICE_INFO.toString())
    }
}
