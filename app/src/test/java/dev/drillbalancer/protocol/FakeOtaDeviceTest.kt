package dev.drillbalancer.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

// FakeOtaDevice against the cases of firmware/test_host/ota/test_ota_proto.c, so the
// OtaClient tests run against a faithful port of ota_proto.c.

class FakeOtaDeviceTest {
    private val ok = OtaStatus.OK.code

    // make_image(): the same LCG bytes as the C test.
    private fun image(n: Int, seed: Int): ByteArray {
        var x = seed
        return ByteArray(n) {
            x = x * 1664525 + 1013904223
            (x ushr 24).toByte()
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    // setUp(): max image 65536, max_chunk 244, ack_every 8, timeout 5 s.
    private fun dev(maxImage: Int = 65536, maxChunk: Int = 244, ackEvery: Int = 8) =
        FakeOtaDevice(maxImage, maxChunk, ackEvery, timeoutMs = 5000)

    private suspend fun FakeOtaDevice.start(size: Int, sha: ByteArray) = writeCtrl(OtaMessage.encodeStart(size, sha))

    private suspend fun FakeOtaDevice.op(op: Int) = writeCtrl(byteArrayOf(op.toByte()))

    private suspend fun FakeOtaDevice.chunk(seq: Int, img: ByteArray, off: Int, len: Int) =
        writeData(OtaMessage.encodeData(seq, img, off, off + len))

    // send_range(): returns the next seq.
    private suspend fun FakeOtaDevice.sendRange(img: ByteArray, off: Int, len: Int, size: Int, seq: Int): Int {
        var o = off
        var s = seq
        while (o < off + len) {
            val n = minOf(size, off + len - o)
            chunk(s, img, o, n)
            s = (s + 1) and 0xFFFF
            o += n
        }
        return s
    }

    private fun FakeOtaDevice.count(type: Class<out OtaMessage>) = sent.count { type.isInstance(it) }

    private fun FakeOtaDevice.assertFlash(img: ByteArray) = assertArrayEquals(img, flash.toByteArray())

    private fun reply(op: Int, st: OtaStatus) = OtaMessage.Reply(op, st.code)

    @Test
    fun happyPathVariousChunkSizes() = runTest {
        for (cs in listOf(1, 17, 100, 182, 244)) {
            val d = dev()
            val img = image(10247, 42 + cs)
            d.start(img.size, sha(img))
            assertEquals(OtaMessage.Reply(OtaOp.START, ok, 244, 8), d.sent[0])
            val seq = d.sendRange(img, 0, img.size, cs, 0)
            assertEquals(0, d.count(OtaMessage.Nack::class.java))
            assertEquals(OtaMessage.Ack(seq, img.size.toLong()), d.sent.last())
            d.op(OtaOp.FINISH)
            assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
            assertEquals(FakeOtaDevice.State.DONE, d.state)
            assertEquals(0, d.abortCalls)
            d.assertFlash(img)
        }
    }

    @Test
    fun ackCadence() = runTest {
        val d = dev(maxChunk = 100, ackEvery = 4)
        val img = image(1050, 1)
        d.start(1050, sha(img))
        d.sent.clear()
        d.sendRange(img, 0, 1050, 100, 0)
        assertEquals(listOf(OtaMessage.Ack(4, 400), OtaMessage.Ack(8, 800), OtaMessage.Ack(11, 1050)), d.sent)

        val e = dev(maxChunk = 100, ackEvery = 4)   // last chunk on the cadence acks once
        val img2 = image(800, 2)
        e.start(800, sha(img2))
        e.sent.clear()
        e.sendRange(img2, 0, 800, 100, 0)
        assertEquals(listOf(OtaMessage.Ack(4, 400), OtaMessage.Ack(8, 800)), e.sent)

        val z = dev(maxChunk = 100, ackEvery = 0)   // ack_every 0 is treated as 1
        val img3 = image(300, 3)
        z.start(300, sha(img3))
        assertEquals(OtaMessage.Reply(OtaOp.START, ok, 100, 1), z.sent[0])
        z.sent.clear()
        z.sendRange(img3, 0, 300, 100, 0)
        assertEquals(listOf(OtaMessage.Ack(1, 100), OtaMessage.Ack(2, 200), OtaMessage.Ack(3, 300)), z.sent)
    }

    @Test
    fun seqGapNacksOnceAndResendRecovers() = runTest {
        val d = dev()
        val img = image(10000, 7)
        d.start(img.size, sha(img))
        d.sent.clear()
        for (s in listOf(0, 1, 3, 4, 5)) d.chunk(s, img, s * 100, 100)
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(2, OtaStatus.SEQ_ERROR.code)), d.sent)
        assertEquals(2, d.writeCalls)
        d.sendRange(img, 200, img.size - 200, 100, 2)
        assertEquals(1, d.count(OtaMessage.Nack::class.java))
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        assertEquals(100, d.writeCalls)
        d.assertFlash(img)
    }

    @Test
    fun secondGapAfterRecoveryNacksAgain() = runTest {
        val d = dev()
        val img = image(1000, 8)
        d.start(1000, sha(img))
        d.sent.clear()
        d.chunk(1, img, 100, 100)
        assertEquals(OtaMessage.Nack(0, OtaStatus.SEQ_ERROR.code), d.sent[0])
        d.chunk(0, img, 0, 100)
        d.chunk(1, img, 100, 100)
        d.chunk(3, img, 300, 100)
        assertEquals(2, d.count(OtaMessage.Nack::class.java))
        assertEquals(OtaMessage.Nack(2, OtaStatus.SEQ_ERROR.code), d.sent.last())
    }

    @Test
    fun duplicateAndOldSeqIgnored() = runTest {
        val d = dev()
        val img = image(500, 9)
        d.start(500, sha(img))
        d.sent.clear()
        d.chunk(0, img, 0, 100)
        d.chunk(1, img, 100, 100)
        d.chunk(1, img, 100, 100)
        d.chunk(0, img, 0, 100)
        d.chunk(0xFFFF, img, 0, 100)   // "before 0" modulo 65536
        d.chunk(2, img, 200, 100)
        assertEquals(0, d.sent.size)
        assertEquals(3, d.writeCalls)
        d.sendRange(img, 300, 200, 100, 3)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)
    }

    @Test
    fun lostAckIsRepeatedOncePerResendRound() = runTest {
        val d = dev()
        val img = image(1000, 12)   // 10 chunks of 100, ack_every 8
        d.start(1000, sha(img))
        d.sent.clear()
        d.sendRange(img, 0, 800, 100, 0)
        assertEquals(listOf<OtaMessage>(OtaMessage.Ack(8, 800)), d.sent)   // lost on the way
        d.sendRange(img, 0, 800, 100, 0)
        assertEquals(2, d.sent.size)
        assertEquals(OtaMessage.Ack(8, 800), d.sent[1])
        d.sendRange(img, 400, 400, 100, 4)
        assertEquals(3, d.sent.size)
        assertEquals(OtaMessage.Ack(8, 800), d.sent[2])
        assertEquals(8, d.writeCalls)
        d.chunk(8, img, 800, 100)
        d.chunk(7, img, 700, 100)
        assertEquals(3, d.sent.size)
        d.chunk(9, img, 900, 100)
        assertEquals(OtaMessage.Ack(10, 1000), d.sent[3])
        d.chunk(9, img, 900, 100)
        assertEquals(5, d.sent.size)
        assertEquals(OtaMessage.Ack(10, 1000), d.sent[4])
        assertEquals(10, d.writeCalls)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)
    }

    @Test
    fun seqWrapsAndGapAcrossWrap() = runTest {
        val d = dev(maxImage = 2 * 100 * 1024, maxChunk = 1, ackEvery = 32)
        val img = image(70000, 10)
        d.start(img.size, sha(img))
        d.sent.clear()
        val seq = d.sendRange(img, 0, img.size, 1, 0)
        assertEquals(70000 and 0xFFFF, seq)
        assertEquals(0, d.count(OtaMessage.Nack::class.java))
        assertEquals(OtaMessage.Ack(seq, 70000), d.sent.last())
        d.chunk(0xFFFF, img, 0xFFFF, 1)   // late pre-wrap retransmission
        assertEquals(70000, d.writeCalls)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)

        val g = dev(maxImage = 2 * 100 * 1024, maxChunk = 1, ackEvery = 1000)
        val img2 = image(65540, 11)
        g.start(img2.size, sha(img2))
        g.sendRange(img2, 0, 65535, 1, 0)
        g.sent.clear()
        g.chunk(0, img2, 65536, 1)        // 65535 lost
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(0xFFFF, OtaStatus.SEQ_ERROR.code)), g.sent)
        g.sendRange(img2, 65535, 5, 1, 0xFFFF)
        g.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), g.sent.last())
        g.assertFlash(img2)
    }

    @Test
    fun maxChunkChangeAppliesFromNextStart() = runTest {
        val d = dev(maxChunk = 100)
        val img = image(400, 21)
        d.start(400, sha(img))
        d.sent.clear()
        d.maxChunk = 20
        d.chunk(0, img, 0, 100)
        assertEquals(0, d.count(OtaMessage.Nack::class.java))
        d.maxChunk = 200
        d.chunk(1, img, 100, 150)
        assertEquals(OtaMessage.Nack(1, OtaStatus.BAD_REQUEST.code), d.sent.last())
        d.sendRange(img, 100, 300, 100, 1)

        d.sent.clear()
        d.start(400, sha(img))
        assertEquals(OtaMessage.Reply(OtaOp.START, ok, 200, 8), d.sent[0])
        d.sendRange(img, 0, 400, 200, 0)
        assertEquals(0, d.count(OtaMessage.Nack::class.java))
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)
    }

    @Test
    fun badChunksNackBadRequestAndTransferContinues() = runTest {
        val d = dev(maxChunk = 100)
        val img = image(300, 12)
        d.start(300, sha(img))
        d.sent.clear()
        d.chunk(0, img, 0, 101)
        d.chunk(0, img, 0, 0)
        d.writeData(byteArrayOf(0))
        d.writeData(ByteArray(0))
        assertEquals(List(4) { OtaMessage.Nack(0, OtaStatus.BAD_REQUEST.code) }, d.sent)
        assertEquals(0, d.writeCalls)
        assertEquals(FakeOtaDevice.State.RECEIVING, d.state)
        assertEquals(0, d.abortCalls)
        d.sendRange(img, 0, 300, 100, 0)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)

        val e = dev(maxChunk = 100)   // chunks in flight after a bad one: no extra NACK
        val img2 = image(400, 13)
        e.start(400, sha(img2))
        e.sent.clear()
        e.chunk(0, img2, 0, 101)
        e.chunk(1, img2, 100, 100)
        e.chunk(2, img2, 200, 100)
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(0, OtaStatus.BAD_REQUEST.code)), e.sent)
    }

    @Test
    fun dataBeyondImageSizeAborts() = runTest {
        val d = dev()
        val img = image(200, 14)
        d.start(150, sha(img.copyOf(150)))
        d.chunk(0, img, 0, 100)
        d.chunk(1, img, 100, 100)
        assertEquals(OtaMessage.Nack(1, OtaStatus.SIZE_MISMATCH.code), d.sent.last())
        assertEquals(1, d.abortCalls)
        assertEquals(1, d.writeCalls)
        assertEquals(FakeOtaDevice.State.IDLE, d.state)
    }

    @Test
    fun finishChecks() = runTest {
        val img = image(1000, 15)
        val wrong = sha(img).also { it[31] = (it[31].toInt() xor 1).toByte() }
        val d = dev()
        d.start(1000, wrong)
        d.sendRange(img, 0, 1000, 200, 0)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.HASH_MISMATCH), d.sent.last())
        assertEquals(1, d.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, d.state)

        val s = dev()
        s.start(1000, sha(img))
        s.sendRange(img, 0, 900, 100, 0)
        s.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.SIZE_MISMATCH), s.sent.last())
        assertEquals(1, s.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, s.state)

        val n = dev()
        n.op(OtaOp.FINISH)
        assertEquals(listOf<OtaMessage>(reply(OtaOp.FINISH, OtaStatus.BAD_STATE)), n.sent)
        assertEquals(0, n.abortCalls)
    }

    @Test
    fun startChecks() = runTest {
        val sha = sha(image(16, 18))
        val d = dev()
        d.start(65537, sha)
        assertEquals(reply(OtaOp.START, OtaStatus.TOO_LARGE), d.sent[0])
        assertEquals(0, d.beginCalls)
        d.start(65536, sha)   // exactly the partition size is fine
        assertEquals(OtaMessage.Reply(OtaOp.START, ok, 244, 8), d.sent[1])
        assertEquals(FakeOtaDevice.State.RECEIVING, d.state)

        val z = dev()
        z.start(0, sha)
        assertEquals(reply(OtaOp.START, OtaStatus.BAD_REQUEST), z.sent[0])
        assertEquals(0, z.beginCalls)
    }

    @Test
    fun malformedRequests() = runTest {
        val d = dev()
        val b = ByteArray(38).also { it[0] = OtaOp.START.toByte(); it[1] = 0x10 }
        d.writeCtrl(b.copyOf(36))
        d.writeCtrl(b.copyOf(38))
        d.writeCtrl(b.copyOf(1))
        d.writeCtrl(byteArrayOf(OtaOp.FINISH.toByte(), 0))
        d.writeCtrl(byteArrayOf(0x7F))
        d.writeCtrl(ByteArray(0))
        assertEquals(
            listOf(
                reply(OtaOp.START, OtaStatus.BAD_REQUEST), reply(OtaOp.START, OtaStatus.BAD_REQUEST),
                reply(OtaOp.START, OtaStatus.BAD_REQUEST), reply(OtaOp.FINISH, OtaStatus.BAD_REQUEST),
                reply(0x7F, OtaStatus.BAD_REQUEST), reply(0, OtaStatus.BAD_REQUEST),
            ),
            d.sent,
        )
        assertEquals(0, d.beginCalls)
        assertEquals(FakeOtaDevice.State.IDLE, d.state)

        val t = dev()   // during a transfer: answered, transfer kept
        val img = image(400, 20)
        t.start(400, sha(img))
        t.sendRange(img, 0, 200, 100, 0)
        t.writeCtrl(byteArrayOf(OtaOp.ABORT.toByte(), 0))
        assertEquals(reply(OtaOp.ABORT, OtaStatus.BAD_REQUEST), t.sent.last())
        assertEquals(FakeOtaDevice.State.RECEIVING, t.state)
        t.sendRange(img, 200, 200, 100, 2)
        t.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), t.sent.last())
    }

    @Test
    fun startWhileReceivingRestarts() = runTest {
        val d = dev()
        val img = image(1000, 21)
        d.start(1000, sha(img))
        d.sendRange(img, 0, 500, 100, 0)
        val img2 = image(800, 22)
        d.start(800, sha(img2))
        assertEquals(OtaMessage.Reply(OtaOp.START, ok, 244, 8), d.sent.last())
        assertEquals(1, d.abortCalls)
        assertEquals(2, d.beginCalls)
        d.sendRange(img2, 0, 800, 100, 0)
        assertEquals(0, d.count(OtaMessage.Nack::class.java))
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img2)
    }

    @Test
    fun requestsInDoneAreBadState() = runTest {
        val d = dev()
        val img = image(100, 23)
        d.start(100, sha(img))
        d.sendRange(img, 0, 100, 100, 0)
        d.op(OtaOp.FINISH)
        assertEquals(FakeOtaDevice.State.DONE, d.state)
        d.start(100, sha(img))
        assertEquals(reply(OtaOp.START, OtaStatus.BAD_STATE), d.sent.last())
        d.op(OtaOp.ABORT)
        assertEquals(reply(OtaOp.ABORT, OtaStatus.BAD_STATE), d.sent.last())
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.BAD_STATE), d.sent.last())
        d.disconnect()
        assertEquals(FakeOtaDevice.State.DONE, d.state)
        assertEquals(0, d.abortCalls)
        assertEquals(1, d.beginCalls)
    }

    @Test
    fun dataWithoutStartNacksOnceUntilReconnect() = runTest {
        val d = dev()
        val img = image(200, 24)
        d.chunk(0, img, 0, 100)
        d.chunk(1, img, 100, 100)
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(0, OtaStatus.BAD_STATE.code)), d.sent)
        assertEquals(0, d.writeCalls)
        d.disconnect()   // a new connection gets the hint again
        d.chunk(0, img, 0, 100)
        assertEquals(2, d.count(OtaMessage.Nack::class.java))
    }

    @Test
    fun abortPaths() = runTest {
        val d = dev()
        val img = image(1000, 25)
        d.start(1000, sha(img))
        d.sendRange(img, 0, 300, 100, 0)
        d.op(OtaOp.ABORT)
        assertEquals(reply(OtaOp.ABORT, OtaStatus.OK), d.sent.last())
        assertEquals(1, d.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, d.state)
        val before = d.sent.size
        d.chunk(3, img, 300, 100)   // in flight when ABORT was sent: dropped silently
        assertEquals(before, d.sent.size)
        assertEquals(3, d.writeCalls)
        d.start(1000, sha(img))
        d.sendRange(img, 0, 1000, 100, 0)
        d.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.OK), d.sent.last())
        d.assertFlash(img)

        val i = dev()
        i.op(OtaOp.ABORT)
        assertEquals(listOf<OtaMessage>(reply(OtaOp.ABORT, OtaStatus.OK)), i.sent)
        assertEquals(0, i.abortCalls)

        val c = dev()
        c.start(1000, sha(img))
        c.sendRange(img, 0, 300, 100, 0)
        val n = c.sent.size
        c.disconnect()
        assertEquals(1, c.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, c.state)
        assertEquals(n, c.sent.size)
        c.disconnect()
        assertEquals(1, c.abortCalls)
    }

    @Test
    fun timeoutViaTick() = runTest {
        var now = 1000L
        val d = FakeOtaDevice(65536, 244, 8, timeoutMs = 5000, clock = { now })
        val img = image(1000, 27)
        d.start(1000, sha(img))
        d.tick(now + 4999)
        assertEquals(FakeOtaDevice.State.RECEIVING, d.state)
        now += 3000
        d.chunk(0, img, 0, 100)   // data restarts the timer
        d.tick(now + 4999)
        assertEquals(FakeOtaDevice.State.RECEIVING, d.state)
        d.sent.clear()
        d.tick(now + 5000)
        assertEquals(FakeOtaDevice.State.IDLE, d.state)
        assertEquals(1, d.abortCalls)
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(1, OtaStatus.TIMEOUT.code)), d.sent)
        d.tick(now + 50_000)
        assertEquals(1, d.sent.size)

        val off = FakeOtaDevice(65536, 244, 8, timeoutMs = 0, clock = { now })
        off.start(1000, sha(img))
        off.tick(now + Int.MAX_VALUE)
        assertEquals(FakeOtaDevice.State.RECEIVING, off.state)
    }

    @Test
    fun sinkFailures() = runTest {
        val img = image(1000, 30)
        val b = dev()
        b.failBegin = true
        b.start(100, sha(img.copyOf(100)))
        assertEquals(listOf<OtaMessage>(reply(OtaOp.START, OtaStatus.FLASH_ERROR)), b.sent)
        assertEquals(FakeOtaDevice.State.IDLE, b.state)
        assertEquals(0, b.abortCalls)

        val w = dev()
        w.failWriteCall = 3
        w.start(1000, sha(img))
        w.sent.clear()
        w.sendRange(img, 0, 300, 100, 0)
        assertEquals(listOf<OtaMessage>(OtaMessage.Nack(2, OtaStatus.FLASH_ERROR.code)), w.sent)
        assertEquals(1, w.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, w.state)
        w.sendRange(img, 300, 300, 100, 3)   // still in flight: dropped silently
        assertEquals(1, w.sent.size)
        assertEquals(3, w.writeCalls)
        w.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.BAD_STATE), w.sent.last())

        val f = dev()
        f.failFinish = true
        f.start(300, sha(img.copyOf(300)))
        f.sendRange(img, 0, 300, 100, 0)
        f.op(OtaOp.FINISH)
        assertEquals(reply(OtaOp.FINISH, OtaStatus.FLASH_ERROR), f.sent.last())
        assertEquals(1, f.abortCalls)
        assertEquals(FakeOtaDevice.State.IDLE, f.state)
    }
}
