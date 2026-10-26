package dev.drillbalancer.ble

import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.OtaClient
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The drops the current link is expected to end with (a command, a settings write or an
 * update closes it on purpose), and whether its close clears the GATT cache. Every link has
 * its own identity, [link], new with every connect and every close: an expectation is made
 * for one link and ignored on any other, so nothing outlives the link it was made for.
 * Expectations of one link stack: taking one back leaves the others, in either order, and
 * the latest still standing names the drop.
 * Thread-safe: links close on the BLE thread, expectations come from coroutines.
 */
internal class ExpectedDrops {
    private class Drop(val reason: DisconnectReason, val clearCache: Boolean)

    private var id = 0
    private val expected = ArrayList<Drop>()   // of link id, latest last

    val link: Int
        @Synchronized get() = id

    /** Asked by Nordic as the link closes, before it fails the request in flight. */
    val clearsCache: Boolean
        @Synchronized get() = expected.any { it.clearCache }

    /** A connect starts: nothing expected of an earlier link carries over. */
    @Synchronized
    fun open() = next()

    /** The link closed: the drop it was expected to end with, if any. */
    @Synchronized
    fun closed(): DisconnectReason? = expected.lastOrNull()?.reason.also { next() }

    /**
     * [link] is to close for [reason], and with [clearCache] to clear the GATT cache as well.
     * False, and nothing changes, if [link] is gone.
     */
    @Synchronized
    fun expect(link: Int, reason: DisconnectReason, clearCache: Boolean = false): Boolean {
        if (link != id) return false
        expected += Drop(reason, clearCache)
        return true
    }

    /**
     * Takes back the latest [expect] of [reason] for [link], with its cache clearing. False,
     * and nothing changes, if [link] is gone.
     */
    @Synchronized
    fun withdraw(link: Int, reason: DisconnectReason): Boolean {
        if (link != id) return false
        val i = expected.indexOfLast { it.reason == reason }
        if (i >= 0) expected.removeAt(i)
        return true
    }

    private fun next() {
        id++
        expected.clear()
    }
}

/**
 * The link's side of firmware updates (BleDeviceSession.startOta), on the link each update
 * started on. From right before FINISH is written ([OtaTransport.beforeFinish]) the device
 * may install the image and reboot into it about 0.5 s later, even when its answer is lost,
 * so that link is then expected to close for FIRMWARE_UPDATED and to clear the GATT cache.
 * Not later: Nordic decides about the cache as the link closes, before it fails the FINISH
 * write in flight. Not earlier: a drop before FINISH is a plain link loss.
 *
 * A definite failure takes both back and [Gatt.restore]s the link at once, as does any end
 * before FINISH, a failed [Gatt.prepare] included. Otherwise (done, unconfirmed, cancelled
 * after FINISH) the link stays as it is: if it is still up [OtaClient.REBOOT_WINDOW] later
 * the device did not reboot, and it is restored then. One update at a time, clean-up
 * included.
 */
internal class OtaLink(
    private val drops: ExpectedDrops,
    private val transport: OtaTransport,
    private val gatt: Gatt,
    private val scope: CoroutineScope,   // the wait for the reboot outlives the update
    private val client: (OtaTransport) -> OtaClient = { OtaClient(it) },
) {
    interface Gatt {
        val connected: Boolean

        /** Before START: indications on (throws if that fails), high connection priority. */
        suspend fun prepare()

        /** Indications off, normal priority again; best effort. */
        suspend fun restore()
    }

    private val lock = Mutex()
    private var watch: Job? = null   // under lock

    /** Cold, like OtaClient.upload; [begin] checks the link before anything is sent. */
    fun update(image: ByteArray, sha256: ByteArray, begin: () -> Unit): Flow<OtaProgress> = flow {
        lock.withLock {
            begin()
            // A previous update this link outlived, within the window: this one takes over
            // the link, and its drop is no longer that one's reboot.
            watch?.cancel()
            val link = drops.link
            drops.withdraw(link, DisconnectReason.FIRMWARE_UPDATED)
            var armed = false
            val finishing = object : OtaTransport by transport {
                // Nordic marks the link down before it reports the close: a drop under way is
                // not armed, and FINISH then fails before it goes out.
                override fun beforeFinish() {
                    armed = gatt.connected && drops.expect(link, DisconnectReason.FIRMWARE_UPDATED, clearCache = true)
                }
            }
            try {
                gatt.prepare()
                client(finishing).upload(image, sha256).collect { emit(it) }
            } catch (e: OtaException) {
                if (armed && !e.unconfirmed) {
                    drops.withdraw(link, DisconnectReason.FIRMWARE_UPDATED)
                    armed = false
                }
                throw e
            } finally {
                if (armed) {
                    awaitReboot(link)
                } else if (drops.link == link && gatt.connected) {
                    withContext(NonCancellable) { gatt.restore() }
                }
            }
        }
    }

    // Under lock.
    private fun awaitReboot(link: Int) {
        watch = scope.launch {
            delay(OtaClient.REBOOT_WINDOW)
            lock.withLock {
                if (drops.withdraw(link, DisconnectReason.FIRMWARE_UPDATED) && gatt.connected) gatt.restore()
            }
        }
    }
}
