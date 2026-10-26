package dev.drillbalancer.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The two OTA characteristics, implemented by the BLE layer (and fakes). */
interface OtaTransport {
    /** ATT MTU of the link now; chunks never exceed mtu - OtaOp.DATA_OVERHEAD. */
    val mtu: Int

    /**
     * ota_ctrl indications. Hot (a SharedFlow): indications must be enabled before the
     * client writes START, and nothing is replayed.
     */
    val ctrlIndications: Flow<ByteArray>

    /**
     * Write with response to ota_ctrl. Throws if the write fails (e.g. ATT 0x05, 0x87) or the
     * device has not answered it within [timeout].
     */
    suspend fun writeCtrl(value: ByteArray, timeout: Duration = OtaClient.WRITE_TIMEOUT)

    /**
     * Whether a [writeCtrl] that threw [error] may still have reached the device: the link
     * dropped, or no answer came in time. False when it certainly did not: never sent, or
     * answered by the device with an error. Unknown errors count as delivered.
     */
    fun mayHaveArrived(error: Exception): Boolean = true

    /**
     * Called right before FINISH is written, with nothing in between that can be cancelled:
     * from here on the device may install the image and reboot into it.
     */
    fun beforeFinish() = Unit

    /** Write without response to ota_data; returns once the stack took it. */
    suspend fun writeData(value: ByteArray)

    /**
     * Emits when the link the transfer started on is gone. The device then ends the
     * transfer (ota_update.c ota_update_on_disconnect), so writes on a new link get at most
     * a BAD_STATE NACK (or nothing, if ota_ctrl is not subscribed there).
     */
    val linkLost: Flow<Unit> get() = emptyFlow()
}

data class OtaProgress(
    val phase: Phase,
    val bytesDone: Int,           // acknowledged by the device
    val totalBytes: Int,
    val bytesPerSecond: Float,
) {
    enum class Phase { STARTING, SENDING, VERIFYING, DONE }

    val percent: Int get() = if (totalBytes == 0) 0 else (bytesDone.toLong() * 100 / totalBytes).toInt()
}

/**
 * The update failed; [status] is the device's, if it reported one. [unconfirmed]: FINISH
 * may have reached the device but its answer was lost (with the link, or never came), so
 * the device may have installed the image; the version it reports afterwards tells.
 */
class OtaException(
    message: String,
    val status: OtaStatus? = null,
    val unconfirmed: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Sends an app image over the OTA service, like tools/ble/db_client.py ota_transfer():
 * START -> stream chunks at most 2 * ack_every ahead of the last ACK, rewinding to a
 * NACK's expected_seq or, when the device goes quiet, to the last ACK -> FINISH. On any
 * failure or cancellation it sends ABORT (best effort); a lost link fails the transfer at
 * once, until FINISH goes out. After that only a definite answer fails the update: a
 * status from the device, or a FINISH write that certainly did not reach it
 * ([OtaTransport.mayHaveArrived]). A lost link, a FINISH write that may have arrived or a
 * missing reply ends in an unconfirmed OtaException, without ABORT. After a successful
 * FINISH the device reboots into the new image.
 *
 * The device checks the image inside the FINISH write's callback and indicates its reply
 * before it answers the write (ota_proto.c on_finish), so the write itself gets
 * [verifyTimeout], and after it only [LOST_REPLY_GRACE] for the reply already on its way.
 */
class OtaClient(
    private val transport: OtaTransport,
    private val stallTimeout: Duration = 3.seconds,
    private val maxStalls: Int = 5,
    private val replyTimeout: Duration = 10.seconds,
    private val verifyTimeout: Duration = 30.seconds,   // the ATT transaction limit
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** Cold: each collection runs one transfer; cancelling the collection aborts it. */
    fun upload(image: ByteArray, sha256: ByteArray = image.sha256()): Flow<OtaProgress> =
        flow { send(image, sha256) { emit(it) } }

    /** [sha256]: the image's digest, when the caller has it already (FirmwareImage.sha256). */
    suspend fun send(image: ByteArray, sha256: ByteArray = image.sha256(), onProgress: suspend (OtaProgress) -> Unit = {}) {
        require(image.isNotEmpty()) { "empty image" }
        coroutineScope {
            val inbox = Channel<OtaMessage>(Channel.UNLIMITED)
            var finishing = false
            // Subscribed before START is written, so its reply cannot be missed.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.ctrlIndications.collect { raw ->
                    // Malformed indications are ignored, like db_client.py.
                    OtaMessage.decode(raw).getOrNull()?.let { inbox.trySend(it) }
                }
            }
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.linkLost.collect {
                    if (!finishing) throw OtaException(LINK_LOST)
                    // After FINISH the device may install the image whatever happens to the
                    // link. A reply indicated before the drop may still be on its way here.
                    delay(LOST_REPLY_GRACE)
                    inbox.close(OtaException(UNCONFIRMED, unconfirmed = true))
                }
            }
            try {
                transfer(image, sha256, inbox, onProgress) {
                    finishing = true
                    transport.beforeFinish()
                }
            } catch (e: Throwable) {
                // Not when unconfirmed: the device may be installing, and the link is gone.
                if (!(e is OtaException && e.unconfirmed)) {
                    withContext(NonCancellable) {
                        runCatching { transport.writeCtrl(OtaMessage.encodeAbort(), ABORT_TIMEOUT) }
                    }
                }
                throw e
            } finally {
                listener.cancel()
                watchdog.cancel()
            }
        }
    }

    private suspend fun transfer(
        image: ByteArray,
        sha: ByteArray,
        inbox: Channel<OtaMessage>,
        onProgress: suspend (OtaProgress) -> Unit,
        beforeFinish: () -> Unit,
    ) {
        val size = image.size
        onProgress(OtaProgress(OtaProgress.Phase.STARTING, 0, size, 0f))
        transport.writeCtrl(OtaMessage.encodeStart(size, sha))
        val rep = awaitReply(inbox, OtaOp.START, replyTimeout)
        if (rep.status != OtaStatus.OK.code) {
            throw OtaException("START rejected: ${OtaStatus.name(rep.status)}", OtaStatus.fromCode(rep.status))
        }
        // Never more than the link carries: an older firmware could announce the previous
        // connection's chunk size on a link still at the default MTU.
        val chunk = minOf(rep.maxChunk, maxOf(1, transport.mtu - OtaOp.DATA_OVERHEAD))
        if (chunk < 1) throw OtaException("The device announced max_chunk 0.")
        val window = 2 * maxOf(rep.ackEvery, 1)
        val nchunks = (size + chunk - 1) / chunk
        val t0 = timeSource.markNow()

        var acked = 0       // chunks the device has accepted
        var next = 0        // next chunk to send
        var stalls = 0
        var badIdx = -1     // consecutive BAD_REQUEST NACKs for one chunk
        var badCount = 0
        var reported = -1

        fun handle(msg: OtaMessage) {
            when (msg) {
                is OtaMessage.Ack -> {
                    val done = if (msg.received >= size) nchunks else (msg.received / chunk).toInt()
                    acked = maxOf(acked, done)
                    next = maxOf(next, acked)
                }
                is OtaMessage.Nack -> {
                    val st = msg.status
                    if (st != OtaStatus.SEQ_ERROR.code && st != OtaStatus.BAD_REQUEST.code) {
                        throw OtaException("The device aborted the update: ${OtaStatus.messageFor(st)}", OtaStatus.fromCode(st))
                    }
                    // expected_seq is a 16-bit chunk index at or after the last ACK.
                    val idx = acked + ((msg.expectedSeq - acked) and 0xFFFF)
                    if (st == OtaStatus.BAD_REQUEST.code) {
                        badCount = if (badIdx == idx) badCount + 1 else 1
                        badIdx = idx
                        if (badCount >= MAX_BAD_REQUESTS) {
                            throw OtaException("The device rejects chunk $idx ($chunk bytes) as malformed.", OtaStatus.BAD_REQUEST)
                        }
                    }
                    acked = maxOf(acked, idx)
                    next = idx
                }
                is OtaMessage.Reply -> throw OtaException("Unexpected reply during the transfer: $msg")
            }
        }

        suspend fun report() {
            if (acked == reported) return
            reported = acked
            val done = minOf(acked.toLong() * chunk, size.toLong()).toInt()
            val secs = t0.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000f
            onProgress(OtaProgress(OtaProgress.Phase.SENDING, done, size, done / secs))
        }

        report()
        while (acked < nchunks) {
            while (next < nchunks && next - acked < window) {
                val from = next * chunk
                transport.writeData(OtaMessage.encodeData(next, image, from, minOf(from + chunk, size)))
                next++
                while (true) handle(inbox.tryReceive().getOrNull() ?: break)   // react to a NACK at once
            }
            if (acked >= nchunks) break   // the final ACK was already drained above
            val msg = withTimeoutOrNull(stallTimeout) { inbox.receive() }
            if (msg == null) {
                // Lost writes at the end produce no NACK: resend from the last ACK.
                if (++stalls > maxStalls) throw OtaException("The device stopped acknowledging.")
                next = acked
            } else {
                handle(msg)
                stalls = 0
            }
            report()
        }
        report()

        val rate = size / (t0.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000f)
        onProgress(OtaProgress(OtaProgress.Phase.VERIFYING, size, size, rate))
        beforeFinish()
        val failed = try {
            transport.writeCtrl(OtaMessage.encodeFinish(), verifyTimeout)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (failed != null && !transport.mayHaveArrived(failed)) {
            throw OtaException("The device did not get the request to install the image: ${failed.message}", cause = failed)
        }
        // The reply was indicated before the write's answer: after the write, failed or not,
        // it is here or on its way.
        val fin = withTimeoutOrNull(LOST_REPLY_GRACE) { nextReply(inbox, OtaOp.FINISH) }
            ?: throw OtaException(UNCONFIRMED, unconfirmed = true, cause = failed)
        if (fin.status != OtaStatus.OK.code) {
            throw OtaException("FINISH failed: ${OtaStatus.messageFor(fin.status)}", OtaStatus.fromCode(fin.status))
        }
        onProgress(OtaProgress(OtaProgress.Phase.DONE, size, size, rate))
    }

    private suspend fun awaitReply(inbox: Channel<OtaMessage>, op: Int, timeout: Duration): OtaMessage.Reply =
        withTimeoutOrNull(timeout) { nextReply(inbox, op) } ?: throw OtaException("Timed out waiting for the device.")

    // The next reply to op; ACKs and NACKs in between are skipped.
    private suspend fun nextReply(inbox: Channel<OtaMessage>, op: Int): OtaMessage.Reply {
        while (true) (inbox.receive() as? OtaMessage.Reply)?.takeIf { it.op == op }?.let { return it }
    }

    companion object {
        // NACK BAD_REQUEST for the same chunk this many times in a row: give up instead
        // of resending a chunk the device will never take.
        const val MAX_BAD_REQUESTS = 3
        const val LINK_LOST = "The connection dropped during the transfer, so the device stopped the update. " +
            "Start it again once the app has reconnected."
        const val UNCONFIRMED = "The device's answer was lost while it checked the image, so the app could not " +
            "confirm the update."
        /** A ctrl write the device answers at once (START, ABORT). */
        val WRITE_TIMEOUT = 10.seconds

        /**
         * After an update ends (done, or unconfirmed), the phone sees a device that installs
         * the image drop the link within this long. The device restarts 0.5 s after its FINISH
         * reply is confirmed, at most 3 s after FINISH, plus 0.2 s or, if the control task is
         * busy, up to 5 s (ble_service.c reboot_after, REBOOT_BACKSTOP_US; control.c
         * APP_CMD_REBOOT). It does not end the link first, so the phone notices only after
         * the 5 s supervision timeout (CONN_SUPERVISION_TIMEOUT): 13 s at worst. A link that
         * outlives the window did not reboot.
         */
        val REBOOT_WINDOW = 15.seconds

        /** ABORT, best effort on the way out: the device answers it at once. */
        val ABORT_TIMEOUT = 2.seconds
        private val LOST_REPLY_GRACE = 1.seconds
    }
}
