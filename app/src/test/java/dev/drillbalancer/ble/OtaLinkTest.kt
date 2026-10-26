package dev.drillbalancer.ble

import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.FakeOtaDevice
import dev.drillbalancer.protocol.OtaClient
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaOp
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaTransport
import dev.drillbalancer.protocol.sha256
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration

// BleDeviceSession.startOta's link side (OtaLink) with OtaClient against FakeOtaDevice,
// driven by the link events the session reports to ExpectedDrops.
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy
class OtaLinkTest {
    private val image = Random(1).nextBytes(1000)
    private val drops = ExpectedDrops().apply { open() }
    private val gatt = object : OtaLink.Gatt {
        override var connected = true
        var restores = 0
        var prepareError: DeviceError? = null
        override suspend fun prepare() {
            prepareError?.let { throw it }
        }
        override suspend fun restore() {
            restores++
        }
    }
    private val window = OtaClient.REBOOT_WINDOW.inWholeMilliseconds

    /**
     * The update's link as the session sees it: a write on a closed link fails before it
     * goes out. FINISH: [finishLost] it never reaches the device (which then does not
     * reboot), [replyLost] the device's reply does not come back, [dropAtFinish] the link
     * drops while the device checks the image, [checkMs] the device takes that long to
     * check it. [closeAtWrite]: the link is marked down and Nordic closes it as the next
     * write fails.
     */
    private inner class Link(
        val dev: FakeOtaDevice = FakeOtaDevice(),
        var finishLost: Boolean = false,
        val replyLost: Boolean = false,
        val dropAtFinish: Boolean = false,
        var checkMs: Long = 0,
    ) : OtaTransport by dev {
        var cacheAtFinish: Boolean? = null
        var dropped: Pair<DisconnectReason?, Boolean>? = null
        var closeAtWrite = false
        private var deaf = false
        override val ctrlIndications: Flow<ByteArray> = dev.ctrlIndications.filter { !deaf }

        override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
            if (!gatt.connected) {
                if (closeAtWrite) dropped = close()
                closeAtWrite = false
                throw DeviceError.NotConnected()
            }
            if (value[0].toInt() != OtaOp.FINISH) return dev.writeCtrl(value, timeout)
            cacheAtFinish = drops.clearsCache
            if (finishLost) return
            deaf = replyLost || dropAtFinish
            delay(checkMs)
            dev.writeCtrl(value, timeout)
            if (dropAtFinish) {
                dropped = drop(this)
                throw DeviceError.Link(DisconnectReason.LINK_LOSS)
            }
        }

        override fun mayHaveArrived(error: Exception) = GattErrors.mayHaveArrived(error)
    }

    // The link drops: Nordic marks it down, asks about the cache as it closes, then the
    // session's onLinkClosed takes the expected reason; the transfer sees the drop.
    private fun drop(link: Link? = null): Pair<DisconnectReason?, Boolean> {
        gatt.connected = false
        return close(link)
    }

    private fun close(link: Link? = null): Pair<DisconnectReason?, Boolean> {
        val cache = drops.clearsCache
        val reason = drops.closed()
        link?.dev?.loseLink()
        return reason to cache
    }

    // BleDeviceSession.withExpectedDrop for a command the device answers with an error.
    private fun failedCommand(reason: DisconnectReason) {
        val link = drops.link
        assertTrue(drops.expect(link, reason))
        assertTrue(drops.withdraw(link, reason))
    }

    private fun reconnect() {
        drops.open()
        gatt.connected = true
    }

    private fun TestScope.otaLink(link: Link) =
        OtaLink(drops, link, gatt, backgroundScope, client = { OtaClient(it, timeSource = testScheduler.timeSource) })

    private suspend fun OtaLink.run(onProgress: (OtaProgress) -> Unit = {}): Exception? = try {
        update(image, image.sha256()) {}.collect { onProgress(it) }
        null
    } catch (e: OtaException) {
        e
    } catch (e: DeviceError) {
        e
    }

    private val Exception?.unconfirmed get() = (this as OtaException).unconfirmed

    @Test
    fun rebootAfterASuccessfulUpdateIsAnnouncedOnItsLinkOnly() = runTest {
        // The device reboots and the app is back on a new link within the window; a Reboot
        // the user sends there is its own drop, whatever the update's wait does meanwhile.
        val link = Link()
        val ota = otaLink(link)
        assertNull(ota.run())
        assertEquals(true, link.cacheAtFinish)
        // Restart 0.7 s after the reply; the phone notices after the 5 s supervision timeout.
        advanceTimeBy(5_700)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED to true, drop(link))
        advanceTimeBy(3_500)
        reconnect()
        advanceTimeBy(4_000)
        assertTrue(drops.expect(drops.link, DisconnectReason.REBOOT))
        advanceTimeBy(window)
        assertEquals(DisconnectReason.REBOOT to false, drop())
        assertEquals(0, gatt.restores)
    }

    @Test
    fun rebootThroughTheBackstopIsStillTheUpdate() = runTest {
        // No confirmation of the reply (3 s), the control task does not restart (5 s more),
        // and the phone notices the silent link after its 5 s supervision timeout.
        val link = Link()
        assertNull(otaLink(link).run())
        advanceTimeBy(13_000)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED to true, drop(link))
        assertEquals(0, gatt.restores)
    }

    @Test
    fun failedCommandInTheWindowKeepsTheUpdatesReboot() = runTest {
        // FINISH reached the device (it installs), its reply was lost; the user taps Reboot
        // and the device answers BUSY. Its reboot into the new image is still the update.
        val link = Link(replyLost = true)
        assertTrue(otaLink(link).run().unconfirmed)
        advanceTimeBy(1_000)
        failedCommand(DisconnectReason.REBOOT)
        advanceTimeBy(5_000)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED to true, drop(link))
    }

    @Test
    fun linkLostWhileTheDeviceChecksIsTheReboot() = runTest {
        // Nordic decides about the cache as the link closes, before the FINISH write fails.
        val link = Link(dropAtFinish = true)
        val e = otaLink(link).run()
        assertTrue(e.toString(), e.unconfirmed)
        assertEquals(FakeOtaDevice.State.DONE, link.dev.state)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED to true, link.dropped)
        reconnect()
        advanceTimeBy(window + 1)
        assertEquals(0, gatt.restores)
        assertNull(drop().first)
    }

    @Test
    fun unconfirmedUpdateOnALinkThatStaysUpIsUndoneAfterTheWindow() = runTest {
        // FINISH went out but never reached the device: no reply, no reboot. The link gets
        // its normal state back and its next drop is a plain one.
        val ota = otaLink(Link(finishLost = true))
        assertTrue(ota.run().unconfirmed)
        assertEquals(0, gatt.restores)
        assertTrue(drops.clearsCache)
        advanceTimeBy(window - 100)
        assertEquals(0, gatt.restores)
        advanceTimeBy(200)
        assertEquals(1, gatt.restores)
        assertEquals(null to false, drop())
    }

    @Test
    fun nextUpdateOnTheSameLinkDropsTheLastOnesExpectation() = runTest {
        // Unconfirmed, then a new update within the window: a drop during it is a link loss,
        // not the last update's reboot.
        val link = Link(finishLost = true)
        val ota = otaLink(link)
        assertTrue(ota.run().unconfirmed)
        advanceTimeBy(2_000)
        var dropped: Pair<DisconnectReason?, Boolean>? = null
        val e = ota.run { if (it.phase == OtaProgress.Phase.SENDING && dropped == null) dropped = drop(link) }
        assertEquals(OtaClient.LINK_LOST, e?.message)
        assertEquals(null to false, dropped)
    }

    @Test
    fun nextUpdateOnTheSameLinkOutlastsTheLastOnesWindow() = runTest {
        // Unconfirmed, then a successful update on the same link that ends after the last
        // one's window: that wait is over, and does not undo this update.
        val link = Link(finishLost = true)
        val ota = otaLink(link)
        assertTrue(ota.run().unconfirmed)
        advanceTimeBy(2_000)
        link.finishLost = false
        link.checkMs = window
        assertNull(ota.run())
        advanceTimeBy(500)
        assertEquals(0, gatt.restores)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED to true, drop(link))
    }

    @Test
    fun nextUpdateWhosePrepareFailsStillRestoresTheLink() = runTest {
        // Unconfirmed on a link that stays up, then a new update fails to subscribe: it took
        // the link over, so it puts it back to normal.
        val ota = otaLink(Link(finishLost = true))
        assertTrue(ota.run().unconfirmed)
        advanceTimeBy(2_000)
        gatt.prepareError = DeviceError.Timeout("subscribe")
        assertTrue(ota.run() is DeviceError.Timeout)
        assertEquals(1, gatt.restores)
        advanceTimeBy(window)
        assertEquals(1, gatt.restores)
        assertEquals(null to false, drop())
    }

    @Test
    fun stopDuringTheCheckKeepsTheReboot() = runTest {
        // FINISH is out: the device may install whatever the app does now.
        val link = Link(checkMs = 20_000)
        val ota = otaLink(link)
        val job = backgroundScope.launch { ota.run() }
        advanceTimeBy(1_000)
        assertEquals(true, link.cacheAtFinish)
        job.cancel()
        runCurrent()
        assertEquals(0, gatt.restores)
        assertTrue(drops.clearsCache)
        // Still up after the window: the device did not reboot.
        advanceTimeBy(window + 1)
        assertEquals(1, gatt.restores)
        assertEquals(null to false, drop())
    }

    @Test
    fun dropRightBeforeFinishIsALinkLoss() = runTest {
        // The last ACK came in, the link drops before FINISH is written: the device ends
        // the transfer, so nothing is announced and the update fails for good.
        val link = Link()
        var dropped: Pair<DisconnectReason?, Boolean>? = null
        val e = otaLink(link).run { if (it.phase == OtaProgress.Phase.VERIFYING) dropped = drop(link) }
        assertFalse(e.toString(), e.unconfirmed)
        assertEquals(null to false, dropped)
        assertNull(link.cacheAtFinish)
        reconnect()
        assertEquals(null to false, drop())
    }

    @Test
    fun linkClosingAtFinishIsALinkLoss() = runTest {
        // Nordic has marked the link down, but not closed it yet, as FINISH is about to go
        // out: nothing is armed for the close, and FINISH fails before it goes out.
        val link = Link()
        val e = otaLink(link).run {
            if (it.phase == OtaProgress.Phase.VERIFYING) {
                gatt.connected = false
                link.closeAtWrite = true
            }
        }
        assertFalse(e.toString(), e.unconfirmed)
        assertEquals(null to false, link.dropped)
        assertNull(link.cacheAtFinish)
    }

    @Test
    fun definiteFailureAfterFinishRestoresAtOnce() = runTest {
        val link = Link(FakeOtaDevice().apply { failFinish = true })
        val e = otaLink(link).run()
        assertFalse(e.unconfirmed)
        assertEquals(true, link.cacheAtFinish)
        assertEquals(1, gatt.restores)
        assertEquals(null to false, drop())
    }

    @Test
    fun stopBeforeFinishRestoresAtOnce() = runTest {
        val link = Link()
        link.dev.drop = { seq, _ -> seq >= 20 }   // keeps the transfer running
        val ota = otaLink(link)
        val job = backgroundScope.launch { ota.run() }
        advanceTimeBy(100)
        job.cancel()
        runCurrent()
        assertEquals(1, gatt.restores)
        assertNull(link.cacheAtFinish)
        assertEquals(null to false, drop())
    }

    @Test
    fun expectationsBelongToOneLink() {
        val d = ExpectedDrops()
        d.open()
        val first = d.link
        assertTrue(d.expect(first, DisconnectReason.REBOOT, clearCache = true))
        assertTrue(d.clearsCache)
        assertEquals(DisconnectReason.REBOOT, d.closed())
        // Gone with its link: nothing carries over, and a late call for it changes nothing.
        d.open()
        assertFalse(d.clearsCache)
        assertFalse(d.expect(first, DisconnectReason.SLEEP, clearCache = true))
        assertFalse(d.clearsCache)
        assertNull(d.closed())

        // Taking one back leaves the others in place, in either order.
        var link = d.link
        d.expect(link, DisconnectReason.FIRMWARE_UPDATED, clearCache = true)
        d.expect(link, DisconnectReason.REBOOT)
        assertTrue(d.withdraw(link, DisconnectReason.FIRMWARE_UPDATED))
        assertFalse(d.clearsCache)
        assertEquals(DisconnectReason.REBOOT, d.closed())
        link = d.link
        d.expect(link, DisconnectReason.FIRMWARE_UPDATED, clearCache = true)
        d.expect(link, DisconnectReason.REBOOT)
        assertTrue(d.withdraw(link, DisconnectReason.REBOOT))
        assertTrue(d.clearsCache)
        assertEquals(DisconnectReason.FIRMWARE_UPDATED, d.closed())
    }
}
