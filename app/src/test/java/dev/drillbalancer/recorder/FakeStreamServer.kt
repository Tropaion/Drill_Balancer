package dev.drillbalancer.recorder

import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.device.FakeRecorderEndpoint
import dev.drillbalancer.protocol.RecCtrl
import dev.drillbalancer.protocol.RecProfile
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.put16
import dev.drillbalancer.protocol.put32
import dev.drillbalancer.protocol.put64
import dev.drillbalancer.recorder.stream.RecStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.math.sin

/** Records of a synthetic take: the payload layouts of rec_stream.h. */
object TestStream {
    const val T0 = 5_000_000L               // device µs at the take start
    const val ODR_MHZ = 448_400L

    fun session(seq: Long, takeId: Long, startUs: Long = T0, profile: Int = RecProfile.RAW448.wire, tail: String = "{}", micMask: Int = 3): ByteArray {
        val p = ByteArray(RecStream.SESSION_LEN)
        p.put32(0, takeId)
        p.put64(4, startUs)
        byteArrayOf(0xB3.toByte(), 0xC9.toByte(), 0xE1.toByte(), 0xA2.toByte()).copyInto(p, 12)
        "0.1.0-gtest".encodeToByteArray().copyInto(p, 16)
        p[48] = profile.toByte()
        p.put32(52, ODR_MHZ)
        p.put16(56, 8192)
        p.put16(58, 64)
        byteArrayOf(1, 1, 0, -1, 2, 1).copyInto(p, 60)
        p.put32(66, 16000)
        p[70] = Integer.bitCount(micMask).toByte()
        p[71] = 16
        p[72] = micMask.toByte()
        p[73] = 6
        p.put16(74, 256)
        p.put16(76, 300)
        return RecStream.encode(RecStream.T_SESSION, 1, seq, p + tail.encodeToByteArray())
    }

    /** n samples from chip timestamp [ts]: 1 g on Z plus a 200 Hz wobble of [amplitudeG]. */
    fun imu(seq: Long, ts: Long, n: Int, hostUs: Long, amplitudeG: Double = 0.3, flags: Int = 0): ByteArray {
        val p = ByteArray(RecStream.IMU_LEN + 12 * n)
        p.put32(0, ts)
        p.put32(4, ts + n - 1)
        p.put64(8, hostUs)
        p.put16(16, n)
        p.put16(18, flags)
        for (i in 0 until n) {
            val w = (amplitudeG * 8192 * sin((ts + i) * 2 * Math.PI * 200 / 448.4)).toInt()
            p.put16(20 + 12 * i, w)
            p.put16(20 + 12 * i + 4, 8192)
        }
        return RecStream.encode(RecStream.T_IMU, 1, seq, p)
    }

    fun audio(seq: Long, index: Long, n: Int, hostUs: Long, amplitude: Int = 3000, flags: Int = 0): ByteArray {
        val p = ByteArray(RecStream.AUDIO_LEN + 4 * n)
        p.put64(0, index)
        p.put64(8, hostUs)
        p.put16(16, n)
        p.put16(18, flags)
        for (i in 0 until n) {
            val v = (amplitude * sin((index + i) * 2 * Math.PI * 233 / 16000)).toInt()
            p.put16(20 + 4 * i, v)
            p.put16(22 + 4 * i, v / 2)
        }
        return RecStream.encode(RecStream.T_AUDIO, 1, seq, p)
    }

    fun stats(seq: Long, hostUs: Long, imuFrames: Long, dropped: Long = 0): ByteArray {
        val p = ByteArray(RecStream.STATS_LEN)
        p.put64(0, hostUs)
        p.put32(8, imuFrames)
        p.put32(16, 2_230_150)            // 448.4 Hz
        p.put32(28, 16_000_200)           // 16000.2 Hz
        p.put32(44, dropped)
        p.put16(52, 3987)
        p[54] = 76
        return RecStream.encode(RecStream.T_STATS, 1, seq, p)
    }

    /** [records]: how many the device sent for the take (END included). */
    fun end(seq: Long, hostUs: Long, reason: Int = RecReason.USER.wire, records: Long = seq + 1): ByteArray {
        val p = ByteArray(RecStream.END_LEN)
        p.put64(0, hostUs)
        p[8] = reason.toByte()
        p.put32(9, records)
        return RecStream.encode(RecStream.T_END, 1, seq, p)
    }
}

/**
 * The device's end of the stream for [fake]: a TCP server on 127.0.0.1 that takes HELLO,
 * answers HELLO_OK once it takes the client, then sends a synthetic take from TAKE_START until
 * STOP (END after it), and ends the stream on CLOSE, as the firmware does. Everything sent for a take is kept in [sent] to
 * compare with the journal. Knobs: [endOnStop], [dropRecordSeqs], [periodMs],
 * [clientLostDelayMs], [garbage], [closeAfterEnd], [afterEnd], [drainMs], [startDelayMs].
 */
class FakeStreamServer(private val fake: FakeDeviceSession) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val endpoint = FakeRecorderEndpoint(ip = "127.0.0.1", port = server.localPort)

    @Volatile var periodMs = 10L                    // one tick: 4-5 IMU samples, part of an audio block
    @Volatile var endOnStop = true                  // false: STOP gets no END (the stop timeout)
    @Volatile var dropRecordSeqs: Set<Long> = emptySet()   // dropped on the "device" (seq used)
    @Volatile var acceptClients = true
    /** false: a device that takes the client without HELLO_OK (the app must not start then). */
    @Volatile var sendHelloOk = true
    /** The device notices a client the phone closed this late (keepalive, a blocked send) and takes the next one only then. */
    @Volatile var clientLostDelayMs = 0L
    /** Sent once at the next tick between two records (kept in [sent]): the decoder skips it. */
    @Volatile var garbage: ByteArray? = null
    /** The device ends the stream (FIN) after the take's END: the session ended without CLOSE (BLE grace, shutdown). */
    @Volatile var closeAfterEnd = false
    /** Sent in the same write right after a take's END (not part of the take, not in [sent]). */
    @Volatile var afterEnd: ByteArray? = null
    /** TAKE_START: how long after its answer the take's first record (SESSION) goes out. */
    @Volatile var startDelayMs = 0L
    /** CLOSE: how long the device drains before its FIN. */
    @Volatile var drainMs = 0L
    /** When the FIN of CLOSE went out (System.nanoTime), 0 before. */
    @Volatile var finSentNs = 0L
        private set
    @Volatile var client: Socket? = null
        private set
    @Volatile var hellos = 0
        private set
    @Volatile var badHellos = 0
        private set

    private val lock = Object()
    private val takes = mutableListOf<ByteArrayOutputStream>()
    @Volatile private var writer: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var closed = false

    /** The bytes sent for each take so far (one entry per TAKE_START). */
    val sent: List<ByteArray> get() = synchronized(lock) { takes.map { it.toByteArray() } }

    init {
        fake.recorderEndpoint = endpoint
        fake.onRecorderOp = ::onOp
        thread(isDaemon = true, name = "fake-stream-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (!acceptClients) {
                s.close()
                continue
            }
            thread(isDaemon = true, name = "fake-stream-hello") { hello(s) }
        }
    }

    private fun hello(s: Socket) {
        try {
            s.soTimeout = 5_000
            val got = ByteArray(RecStream.HELLO_LEN)
            var n = 0
            while (n < got.size) {
                val r = s.getInputStream().read(got, n, got.size - n)
                if (r < 0) throw IOException("closed before HELLO")
                n += r
            }
            if (!got.contentEquals(RecStream.hello(endpoint.token))) {
                badHellos++
                s.close()
                return
            }
            // One client at a time, as on the device: the next is taken once the old one is let
            // go (its take stopped; clientLostDelayMs after it closed), then READY, then HELLO_OK.
            synchronized(clientLock) {
                while (client != null && !closed) clientLock.wait(100)
                hellos++
                client = s
                fake.recorderClientConnected()
                if (sendHelloOk) s.getOutputStream().write(RecStream.encode(RecStream.T_HELLO_OK, 1, 0, ByteArray(0)))
            }
            // Read until the client closes (it never sends after HELLO); EOF ends the client.
            s.soTimeout = 0
            while (s.getInputStream().read() >= 0) Unit
        } catch (_: SocketTimeoutException) {
            s.close()
        } catch (_: IOException) {
        }
        if (client === s) {
            if (clientLostDelayMs > 0) Thread.sleep(clientLostDelayMs)
            stopTake(sendEnd = false)
            if (!closed && !ending) fake.recorderClientLost()
            synchronized(clientLock) {
                if (client === s) client = null
                clientLock.notifyAll()
            }
        }
    }

    private val clientLock = Object()

    private fun onOp(op: RecCtrl) {
        when (op) {
            is RecCtrl.TakeStart -> startTake(op.request.takeId)
            RecCtrl.Stop -> if (endOnStop) stopTake(sendEnd = true)
            RecCtrl.Close -> thread(isDaemon = true) { endSession() }
            else -> Unit
        }
    }

    private fun startTake(takeId: Long) {
        val out = ByteArrayOutputStream()
        synchronized(lock) { takes += out }
        stopping = false
        writer = thread(isDaemon = true, name = "fake-stream-take") { runTake(takeId, out) }
    }

    private fun runTake(takeId: Long, out: ByteArrayOutputStream) {
        var seq = 0L
        var host = TestStream.T0
        var ts = 1000L
        var audioIndex = 0L
        var imuFrames = 0L
        var tick = 0
        fun send(b: ByteArray, s: Long) {
            if (s in dropRecordSeqs) return
            val c = client ?: throw IOException("no client")
            synchronized(lock) { out.write(b) }
            c.getOutputStream().write(b)
        }
        try {
            if (startDelayMs > 0) Thread.sleep(startDelayMs)
            send(TestStream.session(seq, takeId), seq++)
            while (!stopping) {
                Thread.sleep(periodMs)
                if (stalled) continue
                tick++
                garbage?.let { g ->
                    garbage = null
                    val c = client ?: throw IOException("no client")
                    synchronized(lock) { out.write(g) }
                    c.getOutputStream().write(g)
                }
                val n = if (tick % 2 == 0) 4 else 5        // ~448 Hz at 10 ms ticks
                host += 10_000
                send(TestStream.imu(seq, ts, n, host), seq++)
                ts += n
                imuFrames += n
                send(TestStream.audio(seq, audioIndex, 160, host), seq++)
                audioIndex += 160
                if (tick % 100 == 0) send(TestStream.stats(seq, host, imuFrames), seq++)
            }
            if (sendEnd) {
                val end = TestStream.end(seq, host + 1000, endReason)
                val extra = afterEnd
                if (extra == null) {
                    send(end, seq)
                } else {
                    val c = client ?: throw IOException("no client")
                    synchronized(lock) { out.write(end) }
                    c.getOutputStream().write(end + extra)
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }

    @Volatile private var sendEnd = true
    @Volatile private var endReason = RecReason.USER.wire

    private fun stopTake(sendEnd: Boolean, reason: Int = RecReason.USER.wire) {
        val w = writer ?: return
        this.sendEnd = sendEnd
        endReason = reason
        stopping = true
        w.join(2_000)
        writer = null
        if (sendEnd && closeAfterEnd) {
            // The session is over: rec_state IDLE, then the FIN; no client is taken any more.
            acceptClients = false
            ending = true
            fake.recorderSessionEnded(RecReason.fromWire(reason) ?: RecReason.USER)
            client?.shutdownOutput()
        } else if (sendEnd) {
            fake.recorderTakeEnded(RecReason.fromWire(reason) ?: RecReason.USER)
        }
    }

    /** The device ends the take by itself (max_s, battery, ...): END with [reason]. */
    fun endTake(reason: Int) = stopTake(sendEnd = true, reason = reason)

    /** CLOSE: the take's END, then a FIN; the client gets 1 s to close its side. */
    private fun endSession() {
        ending = true
        stopTake(sendEnd = true)
        if (drainMs > 0) Thread.sleep(drainMs)
        val c = client
        if (c != null) {
            try {
                finSentNs = System.nanoTime()
                c.shutdownOutput()
                c.soTimeout = 1_000
                while (c.getInputStream().read() >= 0) Unit
            } catch (_: IOException) {
            }
            c.close()
        }
        client = null
        fake.recorderSessionEnded(RecReason.USER)
        ending = false
    }

    /** The device loses the client (as after a blocked send): the socket closes at once, no END. */
    fun dropClient() {
        client?.close()
    }

    /** Sends nothing while set, the socket stays open (a stalled sender). */
    @Volatile var stalled = false

    @Volatile private var ending = false

    override fun close() {
        closed = true
        stopping = true
        client?.close()
        server.close()
    }
}
