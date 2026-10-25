package dev.drillbalancer.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The device side for OtaClient tests: a port of firmware/components/ota/src/ota_proto.c
 * (state machine, ACK cadence, one NACK per gap, old seq ignored, data-path errors as
 * NACK + end of transfer) with an in-memory sink, behind an OtaTransport whose link can
 * drop, duplicate or corrupt writes, lose ACKs, and deliver indications [latencyMs] late
 * (in [scope]).
 */
class FakeOtaDevice(
    private val maxImageSize: Int = 1 shl 20,
    var maxChunk: Int = 20,                  // announced at START (ota_proto_set_max_chunk)
    ackEvery: Int = 4,
    private val timeoutMs: Long = 10_000,
    override var mtu: Int = 247,
    private val clock: () -> Long = { 0L },
    private val scope: CoroutineScope? = null,
    private val latencyMs: Long = 0,
) : OtaTransport {
    enum class State { IDLE, RECEIVING, DONE }

    // Link hooks: seq and how often that seq was written before.
    var drop: (seq: Int, n: Int) -> Boolean = { _, _ -> false }
    var duplicate: (seq: Int, n: Int) -> Boolean = { _, _ -> false }
    var corrupt: (seq: Int, n: Int) -> Boolean = { _, _ -> false }
    // An ACK the device sent but the client never gets (a full indication queue).
    var loseAck: (OtaMessage.Ack) -> Boolean = { false }
    // A longer payload is NACKed BAD_REQUEST (an older firmware whose limit shrank).
    var acceptMax: Int? = null

    // Sink faults, as in test_ota_proto.c.
    var failBegin = false
    var failWriteCall = 0                    // 1-based sink write that fails, 0 = never
    var failFinish = false

    private val ackEvery = if (ackEvery == 0) 1 else ackEvery
    private val indications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1 shl 20)
    override val ctrlIndications: Flow<ByteArray> = indications

    var state = State.IDLE
        private set
    private var imageSize = 0L
    private var expectedSha = ByteArray(32)
    private var received = 0L
    private var chunkLimit = 0
    private var nextSeq = 0
    private var sinceAck = 0
    private var nackSent = false
    private var dupSeen = false
    private var dupSeq = 0
    private var lastRxMs = 0L
    private var sha = MessageDigest.getInstance("SHA-256")

    val flash = ByteArrayOutputStream()
    var beginCalls = 0; private set
    var writeCalls = 0; private set
    var abortCalls = 0; private set
    val ctrlWrites = mutableListOf<ByteArray>()
    val sent = mutableListOf<OtaMessage>()
    private val writesPerSeq = HashMap<Int, Int>()

    // Largest distance, in chunks, of a write from the next_seq of the last ACK delivered.
    var peakAheadOfAck = 0; private set
    private var lastAckSeq = 0

    override suspend fun writeCtrl(value: ByteArray) {
        tick(clock())
        ctrlWrites += value.copyOf()
        onCtrl(value)
        yield()
    }

    override suspend fun writeData(value: ByteArray) {
        tick(clock())
        val seq = if (value.size >= 2) value.u16(0) else -1
        val n = writesPerSeq.getOrDefault(seq, 0)
        writesPerSeq[seq] = n + 1
        if (state == State.RECEIVING && seq >= 0) {
            peakAheadOfAck = maxOf(peakAheadOfAck, ((seq - lastAckSeq) and 0xFFFF) + 1)
        }
        if (!drop(seq, n)) {
            var data = value
            if (corrupt(seq, n)) data = value.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
            onData(data)
            if (duplicate(seq, n)) onData(data)
        }
        yield()
    }

    fun disconnect() {
        if (state == State.RECEIVING) endTransfer()
        if (state == State.IDLE) nackSent = false
    }

    // ---- ota_proto.c ----

    private fun send(m: OtaMessage) {
        sent += m
        if (latencyMs == 0L) {
            deliver(m)
        } else {
            checkNotNull(scope).launch {
                delay(latencyMs)
                deliver(m)
            }
        }
    }

    private fun deliver(m: OtaMessage) {
        if (m is OtaMessage.Ack) {
            if (loseAck(m)) return
            lastAckSeq = m.nextSeq
        }
        indications.tryEmit(m.encode())
    }

    private fun reply(op: Int, st: OtaStatus) = send(OtaMessage.Reply(op, st.code))

    private fun nack(st: OtaStatus) = send(OtaMessage.Nack(nextSeq, st.code))

    private fun endTransfer() {
        if (state == State.RECEIVING) abortCalls++
        state = State.IDLE
        nackSent = true
    }

    private fun onCtrl(d: ByteArray) {
        if (d.isEmpty()) return reply(0, OtaStatus.BAD_REQUEST)
        val op = d.u8(0)
        when (op) {
            OtaOp.START -> onStart(d)
            OtaOp.FINISH -> if (d.size != 1) reply(op, OtaStatus.BAD_REQUEST) else onFinish()
            OtaOp.ABORT -> if (d.size != 1) reply(op, OtaStatus.BAD_REQUEST) else onAbort()
            else -> reply(op, OtaStatus.BAD_REQUEST)
        }
    }

    private fun onStart(d: ByteArray) {
        if (d.size != OtaOp.START_LEN) return reply(OtaOp.START, OtaStatus.BAD_REQUEST)
        if (state == State.DONE) return reply(OtaOp.START, OtaStatus.BAD_STATE)
        endTransfer()   // a retry while receiving drops the old transfer first
        val size = d.u32(1)
        if (size == 0L) return reply(OtaOp.START, OtaStatus.BAD_REQUEST)
        if (size > maxImageSize) return reply(OtaOp.START, OtaStatus.TOO_LARGE)
        beginCalls++
        if (failBegin) return reply(OtaOp.START, OtaStatus.FLASH_ERROR)
        flash.reset()
        sha = MessageDigest.getInstance("SHA-256")
        state = State.RECEIVING
        imageSize = size
        expectedSha = d.copyOfRange(5, 37)
        received = 0
        chunkLimit = maxChunk
        nextSeq = 0
        sinceAck = 0
        lastAckSeq = 0
        nackSent = false
        dupSeen = false
        lastRxMs = clock()
        send(OtaMessage.Reply(OtaOp.START, OtaStatus.OK.code, chunkLimit, ackEvery))
    }

    private fun onFinish() {
        if (state != State.RECEIVING) return reply(OtaOp.FINISH, OtaStatus.BAD_STATE)
        val st = when {
            received != imageSize -> OtaStatus.SIZE_MISMATCH
            !sha.digest().contentEquals(expectedSha) -> OtaStatus.HASH_MISMATCH
            failFinish -> OtaStatus.FLASH_ERROR
            else -> OtaStatus.OK
        }
        if (st == OtaStatus.OK) {
            state = State.DONE
            nackSent = true
        } else {
            endTransfer()
        }
        reply(OtaOp.FINISH, st)
    }

    private fun onAbort() {
        if (state == State.DONE) return reply(OtaOp.ABORT, OtaStatus.BAD_STATE)
        if (state == State.RECEIVING) endTransfer()
        reply(OtaOp.ABORT, OtaStatus.OK)
    }

    private fun onData(d: ByteArray) {
        if (state != State.RECEIVING) {
            if (!nackSent && state == State.IDLE) {
                nack(OtaStatus.BAD_STATE)
                nackSent = true
            }
            return
        }
        if (d.size < 2) {
            nack(OtaStatus.BAD_REQUEST)
            nackSent = true
            return
        }
        val seq = d.u16(0)
        val ahead = (seq - nextSeq) and 0xFFFF
        if (ahead >= 0x8000) {
            // Retransmission of an accepted chunk. Right after an ACK: the client lost it,
            // so it goes again, once per resend round.
            if (sinceAck == 0) {
                val step = (seq - dupSeq) and 0xFFFF
                if (!dupSeen || step == 0 || step >= 0x8000) send(OtaMessage.Ack(nextSeq, received))
                dupSeen = true
                dupSeq = seq
            }
            return
        }
        if (ahead != 0) {
            if (!nackSent) {
                nack(OtaStatus.SEQ_ERROR)
                nackSent = true
            }
            return
        }
        val n = d.size - 2
        if (n == 0 || n > chunkLimit || n > (acceptMax ?: Int.MAX_VALUE)) {
            nack(OtaStatus.BAD_REQUEST)
            nackSent = true
            return
        }
        if (n > imageSize - received) {
            nack(OtaStatus.SIZE_MISMATCH)
            endTransfer()
            return
        }
        writeCalls++
        if (writeCalls == failWriteCall) {
            nack(OtaStatus.FLASH_ERROR)
            endTransfer()
            return
        }
        flash.write(d, 2, n)
        sha.update(d, 2, n)
        received += n
        nextSeq = (nextSeq + 1) and 0xFFFF
        nackSent = false
        dupSeen = false
        lastRxMs = clock()
        sinceAck++
        if (sinceAck >= ackEvery || received == imageSize) {
            send(OtaMessage.Ack(nextSeq, received))
            sinceAck = 0
        }
    }

    // ota_proto_tick(): the device timer, run here on every write.
    fun tick(nowMs: Long) {
        if (state != State.RECEIVING || timeoutMs == 0L) return
        if (nowMs - lastRxMs >= timeoutMs) {
            nack(OtaStatus.TIMEOUT)
            endTransfer()
        }
    }
}
