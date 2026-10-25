package dev.drillbalancer.ble

import dev.drillbalancer.protocol.FakeOtaDevice
import dev.drillbalancer.protocol.OtaClient
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaOp
import dev.drillbalancer.protocol.OtaTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

// BleDeviceSession.startOta's reboot handling, with OtaClient against FakeOtaDevice.
class OtaRebootTest {
    private val image = Random(1).nextBytes(1000)
    private var armed = false
    private var armedAtFinish: Boolean? = null
    private val reboot = OtaReboot { armed = it }

    // FINISH reaches the device; [gone]: the link drops with it, and the reply too;
    // [silent]: only the reply is lost.
    private fun link(dev: FakeOtaDevice, gone: Boolean = false, refused: Boolean = false, silent: Boolean = false) =
        object : OtaTransport by dev {
            var lost = false
            override val ctrlIndications: Flow<ByteArray> = dev.ctrlIndications.filter { !lost }
            override suspend fun writeCtrl(value: ByteArray) {
                if (value[0].toInt() != OtaOp.FINISH) return dev.writeCtrl(value)
                armedAtFinish = armed
                if (refused) throw IOException("refused")
                lost = gone || silent
                dev.writeCtrl(value)
                if (gone) {
                    dev.loseLink()
                    throw IOException("The connection was lost during the firmware update.")
                }
            }
            override fun mayHaveArrived(error: Exception) = !refused
        }

    private suspend fun TestScope.update(link: OtaTransport): OtaException? = try {
        OtaClient(link, timeSource = testScheduler.timeSource).upload(image).collect { reboot.onProgress(it) }
        null
    } catch (e: OtaException) {
        reboot.onFailure(e)
        e
    }

    @Test
    fun armedBeforeFinishGoesOut() = runTest {
        assertEquals(null, update(link(FakeOtaDevice())))
        assertEquals(true, armedAtFinish)
        assertTrue(armed)
        assertTrue(reboot.mayInstall)
    }

    @Test
    fun unconfirmedUpdateStaysArmed() = runTest {
        // The link drops while the device verifies: Nordic decides about the GATT cache as
        // the link closes, before the FINISH write fails, so it must be armed by then. The
        // device installs the image and reboots.
        val dev = FakeOtaDevice()
        val e = update(link(dev, gone = true))
        assertTrue(e.toString(), e!!.unconfirmed)
        assertEquals(FakeOtaDevice.State.DONE, dev.state)
        assertEquals(true, armedAtFinish)
        assertTrue(armed)
        assertTrue(reboot.mayInstall)
    }

    @Test
    fun linkThatOutlivesTheRebootDisarms() = runTest {
        // FINISH went out but its answer never came, and the link stayed up: no reboot.
        val e = update(link(FakeOtaDevice(), silent = true))
        assertTrue(e.toString(), e!!.unconfirmed)
        assertTrue(armed)
        reboot.linkOutlived()
        assertFalse(armed)
        assertFalse(reboot.armed)
    }

    @Test
    fun rejectedImageDisarms() = runTest {
        val dev = FakeOtaDevice().apply { failFinish = true }
        assertFalse(update(link(dev))!!.unconfirmed)
        assertEquals(true, armedAtFinish)
        assertFalse(armed)
        assertFalse(reboot.mayInstall)
    }

    @Test
    fun finishThatNeverWentOutDisarms() = runTest {
        val dev = FakeOtaDevice()
        assertFalse(update(link(dev, refused = true))!!.unconfirmed)
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)   // ABORT
        assertFalse(armed)
        assertFalse(reboot.mayInstall)
    }
}
