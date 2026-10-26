package dev.drillbalancer.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// OtaClient against FakeOtaDevice (a port of ota_proto.c), covering the cases of
// tools/ble/test_db_client.py and the corner cases of ota_proto.h.

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler time
class OtaClientTest {
    private fun image(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    private val verifyTime = 15.seconds   // longer than an ordinary write may take

    private fun TestScope.device(
        maxChunk: Int = 20,
        ackEvery: Int = 4,
        maxImageSize: Int = 1 shl 20,
        timeoutMs: Long = 10_000,
        latencyMs: Long = 0,
    ) = FakeOtaDevice(maxImageSize, maxChunk, ackEvery, timeoutMs, clock = { testScheduler.currentTime }, scope = this, latencyMs = latencyMs)

    private fun TestScope.client(dev: OtaTransport, stall: Long = 3000, maxStalls: Int = 5) =
        OtaClient(dev, stallTimeout = stall.milliseconds, maxStalls = maxStalls, timeSource = testScheduler.timeSource)

    private suspend fun TestScope.expectFailure(dev: OtaTransport, img: ByteArray, stall: Long = 3000, maxStalls: Int = 5): OtaException {
        try {
            client(dev, stall, maxStalls).send(img)
        } catch (e: OtaException) {
            return e
        }
        fail("transfer should have failed")
        throw AssertionError()
    }

    private fun FakeOtaDevice.assertReceived(img: ByteArray) {
        assertEquals(FakeOtaDevice.State.DONE, state)
        assertArrayEquals(img, flash.toByteArray())
    }

    private fun FakeOtaDevice.nacks() = sent.filterIsInstance<OtaMessage.Nack>()

    @Test
    fun happyPathVariousChunkSizes() = runTest {
        for (chunk in listOf(1, 17, 20, 100, 182, 242)) {
            val dev = device(maxChunk = chunk, ackEvery = 8)
            val img = image(10240 + 7, chunk)
            client(dev).send(img)
            dev.assertReceived(img)
            assertEquals("chunk $chunk", 0, dev.nacks().size)
            assertEquals((img.size + chunk - 1) / chunk, dev.writeCalls)
            assertEquals(OtaMessage.Ack(((img.size + chunk - 1) / chunk) and 0xFFFF, img.size.toLong()), dev.sent.last { it is OtaMessage.Ack })
        }
    }

    @Test
    fun exactMultipleAndSingleByte() = runTest {
        val dev = device(maxChunk = 25, ackEvery = 4)
        val img = image(25 * 8)
        client(dev).send(img)
        dev.assertReceived(img)

        val one = device()
        client(one).send(byteArrayOf(0xE9.toByte()))
        one.assertReceived(byteArrayOf(0xE9.toByte()))
    }

    @Test
    fun cleanTransferNeverStalls() = runTest {
        for (size in listOf(1, 20, 100, 1000)) {
            val dev = device()
            val t0 = testScheduler.currentTime
            client(dev, stall = 2000, maxStalls = 0).send(image(size))
            assertEquals("size $size", t0, testScheduler.currentTime)
        }
    }

    @Test
    fun progressFlow() = runTest {
        val dev = device(maxChunk = 100, ackEvery = 2)
        val img = image(1050)
        val events = client(dev).upload(img).toList()
        assertEquals(OtaProgress.Phase.STARTING, events.first().phase)
        assertEquals(OtaProgress.Phase.DONE, events.last().phase)
        assertEquals(OtaProgress.Phase.VERIFYING, events[events.size - 2].phase)
        val sending = events.filter { it.phase == OtaProgress.Phase.SENDING }
        assertTrue(sending.zipWithNext().all { (a, b) -> b.bytesDone > a.bytesDone })
        assertEquals(1050, sending.last().bytesDone)
        assertEquals(100, events.last().percent)
        assertTrue(events.all { it.totalBytes == 1050 })
        dev.assertReceived(img)
    }

    @Test
    fun droppedChunksAreResentAfterNack() = runTest {
        val dev = device()
        dev.drop = { seq, n -> seq in setOf(3, 17, 30) && n == 0 }
        val img = image(1000)
        client(dev).send(img)
        dev.assertReceived(img)
        assertEquals(listOf(3, 17, 30), dev.nacks().map { it.expectedSeq })
        assertTrue(dev.nacks().all { it.status == OtaStatus.SEQ_ERROR.code })
        assertEquals("recovered by NACK, not by a stall", 0, testScheduler.currentTime)
    }

    @Test
    fun droppedLastChunkRecoversByStallTimeout() = runTest {
        val img = image(205)   // 11 chunks of 20; the lost last one gets no NACK
        val dev = device()
        dev.drop = { seq, n -> seq == 10 && n == 0 }
        val t0 = testScheduler.currentTime
        client(dev, stall = 50).send(img)
        dev.assertReceived(img)
        assertEquals(50, testScheduler.currentTime - t0)
    }

    @Test
    fun lostFinalAckIsRepeated() = runTest {
        // Every chunk arrived but the final ACK did not: the client resends the tail
        // after its stall timeout, and the device answers that with the ACK again.
        val dev = device(maxChunk = 20, ackEvery = 4)
        val img = image(20 * 10 + 3)   // 11 chunks, the last ACK off the cadence
        var lost = 0
        dev.loseAck = { it.received == img.size.toLong() && lost++ < 2 }   // the repeat too, once
        client(dev).send(img)
        dev.assertReceived(img)
        assertEquals(3, lost)
        assertEquals(11, dev.writeCalls)
        assertEquals(0, dev.nacks().size)
    }

    @Test
    fun lostAcksAtTheWindowEdgeAreRepeated() = runTest {
        // Both ACKs of a full window lost: the client stops 2 * ack_every ahead and goes
        // back to its last ACK; the old chunks bring the newest ACK again, once per round.
        val dev = device(maxChunk = 20, ackEvery = 4)
        val img = image(20 * 40)
        val lost = mutableSetOf<Int>()
        dev.loseAck = { (it.nextSeq == 4 || it.nextSeq == 8) && lost.add(it.nextSeq) }
        client(dev).send(img)
        dev.assertReceived(img)
        assertEquals(40, dev.writeCalls)
        val repeats = dev.sent.filterIsInstance<OtaMessage.Ack>().count { it.nextSeq == 8 }
        assertEquals(2, repeats)
    }

    @Test
    fun randomLoss() = runTest {
        val rnd = Random(7)
        val dev = device(maxChunk = 16, ackEvery = 8)
        dev.drop = { _, _ -> rnd.nextDouble() < 0.1 }
        val img = image(5000)
        client(dev, stall = 50, maxStalls = 50).send(img)
        dev.assertReceived(img)
    }

    @Test
    fun duplicateChunksAreWrittenOnce() = runTest {
        val dev = device()
        dev.duplicate = { seq, _ -> seq % 3 == 0 }
        val img = image(1000)
        client(dev).send(img)
        dev.assertReceived(img)
        assertEquals(50, dev.writeCalls)
        assertEquals(0, dev.nacks().size)
    }

    @Test
    fun seqWrapsPast65535() = runTest {
        val dev = device(maxChunk = 1, ackEvery = 64, maxImageSize = 1 shl 17)
        dev.drop = { seq, n -> seq in setOf(0xFFFF, 2) && n == 0 }
        val img = image(70000)
        client(dev, stall = 50).send(img)
        dev.assertReceived(img)
        assertEquals(70000, dev.writeCalls)
        assertEquals(OtaMessage.Ack(70000 and 0xFFFF, 70000), dev.sent.last { it is OtaMessage.Ack })
        assertEquals(listOf(2, 0xFFFF), dev.nacks().map { it.expectedSeq })
        assertEquals("recovered by NACK, not by a stall", 0, testScheduler.currentTime)
    }

    @Test
    fun windowNeverExceedsTwoAckPeriods() = runTest {
        // Indications arrive 20 ms late, so the client always runs into the window.
        for (ackEvery in listOf(1, 4, 16)) {
            val dev = device(maxChunk = 10, ackEvery = ackEvery, latencyMs = 20)
            val img = image(2000)
            client(dev).send(img)
            dev.assertReceived(img)
            assertEquals("ack_every $ackEvery", 2 * ackEvery, dev.peakAheadOfAck)
        }
    }

    @Test
    fun lossWithLinkLatency() = runTest {
        val dev = device(ackEvery = 16, latencyMs = 30)
        dev.drop = { seq, n -> seq % 37 == 5 && n == 0 }
        val img = image(20000)
        client(dev, stall = 500).send(img)
        dev.assertReceived(img)
    }

    @Test
    fun deviceErrorNackAborts() = runTest {
        val dev = device()
        dev.failWriteCall = 5
        val e = expectFailure(dev, image(1000))
        assertEquals(OtaStatus.FLASH_ERROR, e.status)
        assertEquals(OtaMessage.Nack(4, OtaStatus.FLASH_ERROR.code), dev.nacks().single())
        assertArrayEquals(OtaMessage.encodeAbort(), dev.ctrlWrites.last())
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)
        assertEquals(1, dev.abortCalls)
    }

    @Test
    fun deviceTimeoutNackAborts() = runTest {
        // The link dies after chunk 5: the client resends every 3 s, the device gives up after 10 s.
        val dev = device()
        dev.drop = { seq, _ -> seq >= 5 }
        val e = expectFailure(dev, image(1000))
        assertEquals(OtaStatus.TIMEOUT, e.status)
        assertTrue(testScheduler.currentTime in 10_000..15_000)
    }

    @Test
    fun stallGivesUpAfterMaxStalls() = runTest {
        val dev = device(timeoutMs = 0)
        dev.drop = { seq, _ -> seq >= 5 }
        val e = expectFailure(dev, image(1000), stall = 100, maxStalls = 3)
        assertEquals(null, e.status)
        assertTrue(e.message!!, "stopped acknowledging" in e.message!!)
        assertEquals(400, testScheduler.currentTime)
        assertArrayEquals(OtaMessage.encodeAbort(), dev.ctrlWrites.last())
    }

    @Test
    fun lostLinkFailsAtOnce() = runTest {
        // Without the watchdog the chunks after a reconnect reach an idle device: it NACKs
        // BAD_STATE, which is misleading.
        val dev = device()
        dev.drop = { seq, n -> if (seq == 20 && n == 0) dev.loseLink(); false }
        val e = expectFailure(dev, image(1000))
        assertEquals(null, e.status)
        assertEquals(OtaClient.LINK_LOST, e.message)
        assertEquals(0, testScheduler.currentTime)
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)
        assertTrue(dev.writeCalls <= 21)
        assertArrayEquals(OtaMessage.encodeAbort(), dev.ctrlWrites.last())
    }

    @Test
    fun lostLinkAfterFinishIsLeftToTheReply() = runTest {
        // Once FINISH is out the device may install the image: its reply decides.
        val dev = device()
        val lost = MutableSharedFlow<Unit>(replay = 1)
        val link = object : OtaTransport by dev {
            override val linkLost: Flow<Unit> = lost
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                if (value[0].toInt() == OtaOp.FINISH) lost.emit(Unit)
                dev.writeCtrl(value, timeout)
            }
        }
        val img = image(1000)
        client(link).send(img)
        dev.assertReceived(img)
    }

    @Test
    fun replyToFinishLostWithTheLinkIsUnconfirmed() = runTest {
        // The link drops while the device verifies, and the reply goes with it. The device
        // installs the image anyway: no 30 s wait for a failure, and no ABORT.
        val dev = device()
        val lost = MutableSharedFlow<Unit>(replay = 1)
        var gone = false
        val link = object : OtaTransport by dev {
            override val ctrlIndications: Flow<ByteArray> = dev.ctrlIndications.filter { !gone }
            override val linkLost: Flow<Unit> = lost
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                if (value[0].toInt() == OtaOp.FINISH) {
                    gone = true
                    lost.emit(Unit)
                }
                dev.writeCtrl(value, timeout)
            }
        }
        val e = expectUnconfirmed(link, image(1000))
        assertEquals(OtaClient.UNCONFIRMED, e.message)
        assertTrue(testScheduler.currentTime.toString(), testScheduler.currentTime <= 1_000)
        assertEquals(FakeOtaDevice.State.DONE, dev.state)
        assertEquals(OtaOp.FINISH, dev.ctrlWrites.last()[0].toInt())
    }

    // FINISH reaches the device, then the link drops before the write's answer; [replyLost]:
    // the reply indication goes with it.
    private fun lostAfterFinish(dev: FakeOtaDevice, replyLost: Boolean) = object : OtaTransport by dev {
        var gone = false
        override val ctrlIndications: Flow<ByteArray> = dev.ctrlIndications.filter { !gone }
        override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
            if (value[0].toInt() != OtaOp.FINISH) return dev.writeCtrl(value, timeout)
            gone = replyLost
            dev.writeCtrl(value, timeout)
            dev.loseLink()
            throw IOException("The connection was lost during the firmware update.")
        }
    }

    @Test
    fun finishWriteFailedWithTheLinkIsUnconfirmed() = runTest {
        // The device may have the FINISH anyway.
        val dev = device()
        val e = expectUnconfirmed(lostAfterFinish(dev, replyLost = true), image(1000))
        assertTrue(e.cause is IOException)
        assertTrue(testScheduler.currentTime.toString(), testScheduler.currentTime <= 1_000)
        assertEquals(FakeOtaDevice.State.DONE, dev.state)
        assertEquals(OtaOp.FINISH, dev.ctrlWrites.last()[0].toInt())
    }

    @Test
    fun replyIndicatedBeforeTheFailedFinishWriteCounts() = runTest {
        // The firmware indicates its reply inside the write callback, before the write's
        // answer: a drop in between leaves the reply here, and it confirms the update.
        val dev = device()
        val img = image(1000)
        val phases = client(lostAfterFinish(dev, replyLost = false)).upload(img).toList().map { it.phase }.distinct()
        assertEquals(OtaProgress.Phase.DONE, phases.last())
        assertEquals(OtaMessage.Reply(OtaOp.FINISH, 0), dev.sent.last())
        assertEquals(OtaOp.FINISH, dev.ctrlWrites.last()[0].toInt())
        dev.assertReceived(img)
    }

    @Test
    fun finishWriteRefusedOnALiveLinkFails() = runTest {
        // Certainly not installing (e.g. never sent, or the device answered with an error): a
        // plain failure, at once, and ABORT, instead of waiting for a reboot that cannot come.
        val dev = device()
        val link = object : OtaTransport by dev {
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                if (value[0].toInt() == OtaOp.FINISH) throw IOException("The firmware update was refused.")
                dev.writeCtrl(value, timeout)
            }
            override fun mayHaveArrived(error: Exception) = false
        }
        val e = expectFailure(link, image(1000))
        assertTrue(e.message!!, !e.unconfirmed && "refused" in e.message!!)
        assertEquals(0, testScheduler.currentTime)
        assertArrayEquals(OtaMessage.encodeAbort(), dev.ctrlWrites.last())
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)
    }

    @Test
    fun finishWithoutReplyIsUnconfirmed() = runTest {
        // The device took the FINISH write but its reply never came: it may install the
        // image, so no ABORT and no "failed". The reply goes out before the write's answer,
        // so a missing one is clear after a moment, not after the verification timeout.
        val dev = device()
        var finished = false
        val link = object : OtaTransport by dev {
            override val ctrlIndications: Flow<ByteArray> = dev.ctrlIndications.filter { !finished }
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                finished = finished || value[0].toInt() == OtaOp.FINISH
                dev.writeCtrl(value, timeout)
            }
        }
        expectUnconfirmed(link, image(1000))
        assertEquals(1_000, testScheduler.currentTime)
        assertEquals(OtaOp.FINISH, dev.ctrlWrites.last()[0].toInt())
        assertEquals(FakeOtaDevice.State.DONE, dev.state)
    }

    @Test
    fun slowVerificationIsWaitedForByTheFinishWrite() = runTest {
        // The device checks the image inside the FINISH write's callback: the write, bounded
        // by the BLE layer's per-request timeout, must outlast a slow check, and the definite
        // answer then counts (here a rejected image: a failure, not "unconfirmed").
        val dev = device(timeoutMs = 0)   // the fake would count the check as a data timeout
        dev.failFinish = true
        val timeouts = mutableListOf<Duration>()
        val link = object : OtaTransport by dev {
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                timeouts += timeout
                if (value[0].toInt() == OtaOp.FINISH) {
                    // Like Nordic's .timeout(): the write fails once the device took too long.
                    if (timeout < verifyTime) {
                        delay(timeout)
                        throw IOException("timed out")
                    }
                    delay(verifyTime)
                }
                dev.writeCtrl(value, timeout)
            }
        }
        val e = expectFailure(link, image(1000))
        assertEquals(OtaStatus.FLASH_ERROR, e.status)
        assertEquals(listOf(OtaClient.WRITE_TIMEOUT, 30.seconds, OtaClient.ABORT_TIMEOUT), timeouts)   // START, FINISH, ABORT
    }

    private suspend fun TestScope.expectUnconfirmed(link: OtaTransport, img: ByteArray): OtaException {
        try {
            client(link).send(img)
        } catch (e: OtaException) {
            assertTrue(e.message!!, e.unconfirmed)
            assertEquals(null, e.status)
            return e
        }
        fail("transfer should have been unconfirmed")
        throw AssertionError()
    }

    @Test
    fun hashMismatchOnFinish() = runTest {
        val dev = device()
        dev.corrupt = { seq, n -> seq == 3 && n == 0 }
        val e = expectFailure(dev, image(1000))
        assertEquals(OtaStatus.HASH_MISMATCH, e.status)
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)
        assertEquals(OtaOp.FINISH, dev.ctrlWrites[dev.ctrlWrites.size - 2][0].toInt())
    }

    @Test
    fun finishFlashError() = runTest {
        val dev = device()
        dev.failFinish = true
        assertEquals(OtaStatus.FLASH_ERROR, expectFailure(dev, image(100)).status)
    }

    @Test
    fun startRejected() = runTest {
        val dev = device(maxImageSize = 100)
        assertEquals(OtaStatus.TOO_LARGE, expectFailure(dev, image(101)).status)
        assertEquals(0, dev.writeCalls)

        val busy = device()
        busy.failBegin = true
        assertEquals(OtaStatus.FLASH_ERROR, expectFailure(busy, image(100)).status)
    }

    @Test
    fun cancellationSendsAbort() = runTest {
        val dev = device()
        dev.drop = { seq, _ -> seq >= 20 }   // keeps the transfer running
        val job = launch {
            client(dev).upload(image(1000)).collect { if (it.bytesDone >= 200) cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertArrayEquals(OtaMessage.encodeAbort(), dev.ctrlWrites.last())
        assertEquals(FakeOtaDevice.State.IDLE, dev.state)
        assertEquals(1, dev.abortCalls)
        assertEquals(OtaMessage.Reply(OtaOp.ABORT, 0), dev.sent.last())
    }

    @Test
    fun chunkClampedToLinkMtu() = runTest {
        // START announces a chunk the link cannot carry (left over from another link).
        val dev = device(maxChunk = 242)
        dev.mtu = 23
        dev.acceptMax = 18
        val img = image(1000)
        client(dev).send(img)
        dev.assertReceived(img)
        assertEquals(0, dev.nacks().size)
        assertEquals((1000 + 17) / 18, dev.writeCalls)
    }

    @Test
    fun repeatedBadRequestAborts() = runTest {
        val dev = device(maxChunk = 100)
        dev.acceptMax = 50
        val e = expectFailure(dev, image(1000), stall = 50)
        assertTrue(e.message!!, "rejects chunk 0" in e.message!!)
        assertTrue(dev.nacks().size <= OtaClient.MAX_BAD_REQUESTS + 2)
    }

    @Test
    fun noReplyTimesOut() = runTest {
        val silent = object : OtaTransport {
            override val mtu = 247
            override val ctrlIndications: Flow<ByteArray> = emptyFlow()
            val writes = mutableListOf<ByteArray>()
            override suspend fun writeCtrl(value: ByteArray, timeout: Duration) {
                writes += value
            }
            override suspend fun writeData(value: ByteArray) = Unit
        }
        try {
            OtaClient(silent, replyTimeout = 1.seconds).send(image(10))
            fail()
        } catch (e: OtaException) {
            assertTrue("Timed out" in e.message!!)
        }
        assertEquals(listOf(OtaOp.START, OtaOp.ABORT), silent.writes.map { it[0].toInt() })
    }
}
