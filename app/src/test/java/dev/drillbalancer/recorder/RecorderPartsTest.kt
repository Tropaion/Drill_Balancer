package dev.drillbalancer.recorder

import android.Manifest
import dev.drillbalancer.ble.GattErrors
import dev.drillbalancer.ble.GattOp
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.RecFlags
import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.protocol.put16
import dev.drillbalancer.recorder.net.LanLink
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.LinkPhase
import dev.drillbalancer.recorder.net.StreamEndpoint
import dev.drillbalancer.recorder.net.WifiBlock
import dev.drillbalancer.recorder.net.WifiPermissions
import dev.drillbalancer.recorder.stream.RecStream
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamStatsTracker
import dev.drillbalancer.ui.formatMinSec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class RecorderPartsTest {
    @Test
    fun wifiPermissionsPerApiLevel() {
        assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), WifiPermissions.required(33))
        assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), WifiPermissions.required(36))
        // API 37: the stream's TCP connection is local-network access.
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK), WifiPermissions.required(37),
        )
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), WifiPermissions.required(32))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), WifiPermissions.required(30))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), WifiPermissions.required(29))
        assertEquals(emptyList<String>(), WifiPermissions.required(28))
        assertEquals(emptyList<String>(), WifiPermissions.required(26))
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), WifiPermissions.optional(33))
        assertEquals(emptyList<String>(), WifiPermissions.optional(32))
        assertTrue(WifiPermissions.canRequestNetwork(29))
        assertFalse(WifiPermissions.canRequestNetwork(28))
        assertEquals(listOf(false, true, true, false), listOf(28, 29, 32, 33).map { WifiPermissions.needsLocationServices(it) })
    }

    @Test
    fun startAsksOnlyWhileARequiredPermissionIsMissing() {
        val nearby = Manifest.permission.NEARBY_WIFI_DEVICES
        val notify = Manifest.permission.POST_NOTIFICATIONS
        // First Start: the notification permission comes with the required one.
        assertEquals(listOf(nearby, notify), WifiPermissions.toAsk(listOf(nearby), listOf(notify)))
        // Notifications refused, Nearby devices granted: Start goes on without a dialog.
        assertEquals(emptyList<String>(), WifiPermissions.toAsk(emptyList(), listOf(notify)))
    }

    @Test
    fun manualJoinGivesUpAfterItsTimeConnectTimeoutsIncluded() {
        var attempts = 0
        // Each attempt hangs like a connect to an address the phone's network does not have.
        val slow = object : SocketFactory() {
            override fun createSocket() = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    attempts++
                    Thread.sleep(300)
                    throw SocketTimeoutException("connect timed out")
                }
            }

            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        }
        val ep = StreamEndpoint("192.168.4.1", 47100, ByteArray(16), "DB-1a2b-k7q2", "p".repeat(20), noAp = false)
        val link = LanLink(ep, manualJoin = true, factory = { slow }, manualJoinMs = 1_000, retryMs = 100)
        val phases = mutableListOf<LinkPhase>()
        val t0 = System.nanoTime()
        val e = runCatching { runBlocking { link.connect { phases += it } } }.exceptionOrNull()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(e.toString(), e is LinkException && e.kind == LinkException.Kind.CONNECT)
        assertTrue("gave up after $ms ms", ms < 2_000)
        assertTrue("attempts $attempts", attempts >= 2)
        assertTrue(LinkPhase.MANUAL_JOIN in phases)
    }

    @Test
    fun aRefusedPermissionOrLocationOffBlocksTheJoin() {
        assertEquals(WifiBlock.PERMISSION, WifiPermissions.block(missingRequired = true, locationOn = true, sdk = 34))
        assertEquals(WifiBlock.PERMISSION, WifiPermissions.block(missingRequired = true, locationOn = false, sdk = 30))
        // API 29-32 (31-32 connect over BLE with location off): the request would find nothing.
        assertEquals(WifiBlock.LOCATION_OFF, WifiPermissions.block(missingRequired = false, locationOn = false, sdk = 31))
        assertEquals(WifiBlock.LOCATION_OFF, WifiPermissions.block(missingRequired = false, locationOn = false, sdk = 29))
        assertNull(WifiPermissions.block(missingRequired = false, locationOn = true, sdk = 31))
        assertNull(WifiPermissions.block(missingRequired = false, locationOn = false, sdk = 33))
        assertNull("API < 29 joins in the settings", WifiPermissions.block(missingRequired = false, locationOn = false, sdk = 28))
    }

    @Test
    fun recorderCodesOnRecCtrlBeforeTheStacksMeaning() {
        // 0x88..0x8A are also Android's GATT_PENDING / GATT_AUTH_FAIL / GATT_MORE.
        for (code in listOf(AttError.REC_STATE, AttError.REC_RADIO, AttError.REC_BATTERY)) {
            val e = GattErrors.requestError(code.code, GattOp.REC_CTRL, linkUp = true, phoneBonded = true)
            assertEquals(code, (e as DeviceError.Att).error)
            assertFalse(e.needsPairing)
            assertTrue(GattErrors.answered(e))
        }
        // Elsewhere 0x89 stays a failed pairing / stale bond.
        assertTrue(GattErrors.requestError(0x89, GattOp.COMMAND, linkUp = true, phoneBonded = true) is DeviceError.StaleBond)
        assertTrue(GattErrors.requestError(0x89, GattOp.REC_READ, linkUp = true, phoneBonded = false) is DeviceError.PairingFailed)
        // Not answered: the link went.
        assertTrue(GattErrors.requestError(0x89, GattOp.REC_CTRL, linkUp = false, phoneBonded = true) is DeviceError.StaleBond)
        // Busy and unknown op as on the command characteristic; 0x85 likely Android's GATT_ERROR.
        assertEquals(AttError.BUSY, (GattErrors.requestError(0x86, GattOp.REC_CTRL, true, true) as DeviceError.Att).error)
        assertEquals(AttError.UNKNOWN_CMD, (GattErrors.requestError(0x84, GattOp.REC_CTRL, true, true) as DeviceError.Att).error)
        assertTrue(GattErrors.requestError(0x85, GattOp.REC_CTRL, true, true) is DeviceError.Link)
        // The secured read without a bond asks for pairing.
        assertTrue((GattErrors.requestError(0x05, GattOp.REC_READ, true, false) as DeviceError.Att).needsPairing)
        assertFalse(GattErrors.mayHaveArrived(DeviceError.NoRecorder()))
    }

    @Test
    fun endpointFromRecState() {
        val st = RecorderStatus.NOT_READ.copy(
            read = true, state = RecState.WAIT_CLIENT, port = 47100, ip = "192.168.4.1", ssid = "DB-1a2b-k7q2", pass = "p".repeat(20),
            token = "a0".repeat(16),
        )
        val ep = StreamEndpoint.of(st)!!
        assertEquals("192.168.4.1" to 47100, ep.ip to ep.port)
        assertFalse(ep.noAp)
        assertEquals(16, ep.token.size)
        assertNull("AP without SSID", StreamEndpoint.of(st.copy(ssid = "")))
        assertNotNull("the simulator: no SSID", StreamEndpoint.of(st.copy(ssid = "", flags = RecFlags.NO_AP)))
        assertNull("not reachable", StreamEndpoint.of(st.copy(state = RecState.AP_STARTING)))
        assertNull(StreamEndpoint.of(st.copy(port = 0)))
    }

    @Test
    fun endCountsTheRecordsThatNeverCame() {
        // seq 0..3 received, then END (seq 6) says 9 were sent: seq 4, 5 (a jump) and 7, 8 (after END's seq) are missing.
        val bytes = TestStream.session(0, 7) + TestStream.imu(1, 100, 5, TestStream.T0 + 10_000) +
            TestStream.audio(2, 0, 160, TestStream.T0 + 10_000) + TestStream.imu(3, 105, 4, TestStream.T0 + 20_000) +
            TestStream.end(6, TestStream.T0 + 30_000, records = 9)
        val t = StreamStatsTracker()
        StreamDecoder.decodeAll(bytes).first.forEach(t::add)
        assertEquals(5L, t.stats.records)
        assertEquals(4L, t.stats.missingRecords)
    }

    @Test
    fun livePreview() {
        val p = LivePreview()
        val recs = StreamDecoder.decodeAll(
            TestStream.session(0, 1) + (1..40).fold(ByteArray(0)) { acc, i ->
                acc + TestStream.imu(i.toLong(), 1000L + 9 * i, 9, TestStream.T0 + 20_000L * i, amplitudeG = 0.5)
            } + TestStream.audio(41, 0, 160, TestStream.T0, amplitude = 32767),
        ).first
        recs.forEach(p::add)
        val spark = p.sparkline()
        assertEquals("360 samples at 17 per point", 360 / 17, spark.size)
        assertTrue(spark.all { it in 1.0f..1.2f })
        val levels = p.takeLevels()
        assertEquals(-3.0f, levels.mic1Db!!, 0.3f)          // a full-scale sine: -3 dBFS
        assertEquals(-9.0f, levels.mic2Db!!, 0.3f)          // half of it
        assertFalse(levels.mic1Clipped || levels.mic2Clipped)
        assertNull("taken", p.takeLevels().mic1Db)
        // One sample at -32768 clips, on its own microphone.
        val clip = ByteArray(RecStream.AUDIO_LEN + 4).also { it.put16(16, 1); it.put16(20, -32768) }
        StreamDecoder.decodeAll(RecStream.encode(RecStream.T_AUDIO, 1, 42, clip)).first.forEach(p::add)
        val clipped = p.takeLevels()
        assertTrue(clipped.mic1Clipped)
        assertFalse(clipped.mic2Clipped)
        // A new SESSION starts over.
        p.add(recs.first())
        assertTrue(p.sparkline().isEmpty())
    }

    @Test
    fun livePreviewOfMic2Alone() {
        // mic_mask 2: one channel, and it is Mic 2 (channel order = REC_MIC_* bit order).
        val n = 160
        val mono = ByteArray(RecStream.AUDIO_LEN + 2 * n).also {
            it.put16(16, n)
            for (i in 0 until n) it.put16(RecStream.AUDIO_LEN + 2 * i, (32767 * kotlin.math.sin(i * 2 * Math.PI * 233 / 16000)).toInt())
            it.put16(RecStream.AUDIO_LEN, -32768)
        }
        val p = LivePreview()
        StreamDecoder.decodeAll(TestStream.session(0, 1, micMask = 2) + RecStream.encode(RecStream.T_AUDIO, 1, 1, mono)).first.forEach(p::add)
        val levels = p.takeLevels()
        assertNull("no Mic 1", levels.mic1Db)
        assertEquals(-3.0f, levels.mic2Db!!, 0.5f)
        assertFalse(levels.mic1Clipped)
        assertTrue(levels.mic2Clipped)
        assertTrue(levels.any)
        assertEquals(listOf(1), LivePreview.micsOf(2).toList())
        assertEquals(listOf(0, 1), LivePreview.micsOf(3).toList())
    }

    @Test
    fun notificationText() {
        val rec = RecordingState(phase = RecordPhase.RECORDING, ssid = "DB-1a2b-k7q2", live = LiveView(elapsedS = 83.7))
        assertEquals(RecorderNotice("Recording 01:23", "On DB-1a2b-k7q2", "Stop"), recorderNotice(rec))
        assertEquals(RecorderNotice("Recorder ready", "2 takes this session", "Finish"), recorderNotice(RecordingState(phase = RecordPhase.READY, takes = 2)))
        assertEquals("Connecting to the recorder…", recorderNotice(RecordingState(phase = RecordPhase.JOINING_WIFI)).title)
        // One formatter for the list, the record screen's clock and the notification.
        assertEquals(listOf("0:42", "12:05", "1:02:03"), listOf(42L, 725L, 3723L).map { formatMinSec(it) })
        assertEquals(listOf("00:42", "12:05", "1:02:03"), listOf(42L, 725L, 3723L).map { formatMinSec(it, padMinutes = true) })
        assertTrue(RecordPhase.RECORDING.active && RecordPhase.READY.active && RecordPhase.STARTING_DEVICE.active)
        assertFalse(RecordPhase.IDLE.active || RecordPhase.DONE.active || RecordPhase.FAILED.active)
    }
}
