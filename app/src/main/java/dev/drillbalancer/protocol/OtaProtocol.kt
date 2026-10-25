package dev.drillbalancer.protocol

// BLE OTA wire format (ota_proto.h). The client writes requests to ota_ctrl and
// gets replies, ACKs and NACKs as indications on it; chunks go to ota_data as
// writes without response: u16 seq | payload, seq wrapping at 65536.

object OtaOp {
    const val START = 0x01        // | u32 image_size | u8 sha256[32]   (37 bytes)
    const val FINISH = 0x02
    const val ABORT = 0x03
    const val MSG_REPLY = 0x80    // | u8 request_opcode | u8 status | (START OK: u16 max_chunk | u16 ack_every)
    const val MSG_ACK = 0x81      // | u16 next_seq | u32 bytes_received
    const val MSG_NACK = 0x82     // | u16 expected_seq | u8 status

    const val START_LEN = 37

    // ATT write header (3) + u16 seq: max_chunk = ATT MTU - OTA_DATA_OVERHEAD.
    const val DATA_OVERHEAD = 5
}

/** ota_status_t */
enum class OtaStatus(val code: Int, val message: String) {
    OK(0, "OK"),
    BAD_REQUEST(1, "The device rejected a malformed request."),
    BAD_STATE(2, "The device is not ready for this step (an update may already be waiting for its reboot)."),
    TOO_LARGE(3, "The image is larger than the device's update partition."),
    FLASH_ERROR(4, "The device could not write the image (flash error, or an image for another project)."),
    SIZE_MISMATCH(5, "The device received a different number of bytes than announced."),
    HASH_MISMATCH(6, "The image was corrupted in transfer (SHA-256 mismatch)."),
    SEQ_ERROR(7, "Chunks were lost in transfer."),
    TIMEOUT(8, "The device stopped waiting for data.");

    companion object {
        fun fromCode(code: Int): OtaStatus? = entries.firstOrNull { it.code == code }

        fun name(code: Int): String = fromCode(code)?.name ?: "status $code"

        /** The user-facing text for [code], or its name if the app does not know it. */
        fun messageFor(code: Int): String = fromCode(code)?.message ?: name(code)
    }
}

/** An ota_ctrl indication. */
sealed interface OtaMessage {
    fun encode(): ByteArray

    /** Reply to a request; [maxChunk] and [ackEvery] only in a START OK reply. */
    data class Reply(val op: Int, val status: Int, val maxChunk: Int = 0, val ackEvery: Int = 0) : OtaMessage {
        override fun encode(): ByteArray =
            if (op == OtaOp.START && status == OtaStatus.OK.code) {
                ByteArray(7).also {
                    it[0] = OtaOp.MSG_REPLY.toByte(); it[1] = op.toByte(); it[2] = status.toByte()
                    it.put16(3, maxChunk)
                    it.put16(5, ackEvery)
                }
            } else {
                byteArrayOf(OtaOp.MSG_REPLY.toByte(), op.toByte(), status.toByte())
            }
    }

    /** Sent every ack_every accepted chunks and after the last one. */
    data class Ack(val nextSeq: Int, val received: Long) : OtaMessage {
        override fun encode(): ByteArray = ByteArray(7).also {
            it[0] = OtaOp.MSG_ACK.toByte()
            it.put16(1, nextSeq)
            it.put32(3, received)
        }
    }

    /** A gap (SEQ_ERROR), a malformed chunk (BAD_REQUEST), or a data-path error that ended the transfer. */
    data class Nack(val expectedSeq: Int, val status: Int) : OtaMessage {
        override fun encode(): ByteArray = ByteArray(4).also {
            it[0] = OtaOp.MSG_NACK.toByte()
            it.put16(1, expectedSeq)
            it[3] = status.toByte()
        }
    }

    companion object {
        fun decode(value: ByteArray): Decoded<OtaMessage> {
            if (value.isEmpty()) return Decoded.Invalid("empty OTA message")
            val type = value.u8(0)
            return when {
                type == OtaOp.MSG_REPLY && value.size >= 3 -> {
                    val op = value.u8(1)
                    val status = value.u8(2)
                    when {
                        op == OtaOp.START && status == OtaStatus.OK.code ->
                            if (value.size == 7) Decoded.Ok(Reply(op, status, value.u16(3), value.u16(5)))
                            else Decoded.Invalid("START reply is ${value.size} bytes, expected 7")
                        value.size == 3 -> Decoded.Ok(Reply(op, status))
                        else -> Decoded.Invalid("reply is ${value.size} bytes, expected 3")
                    }
                }
                type == OtaOp.MSG_ACK && value.size == 7 -> Decoded.Ok(Ack(value.u16(1), value.u32(3)))
                type == OtaOp.MSG_NACK && value.size == 4 -> Decoded.Ok(Nack(value.u16(1), value.u8(3)))
                else -> Decoded.Invalid("malformed OTA message ${value.toHex()}")
            }
        }

        fun encodeStart(imageSize: Int, sha256: ByteArray): ByteArray {
            require(sha256.size == 32) { "sha256 must be 32 bytes" }
            return ByteArray(OtaOp.START_LEN).also {
                it[0] = OtaOp.START.toByte()
                it.put32(1, imageSize.toLong())
                sha256.copyInto(it, 5)
            }
        }

        fun encodeFinish() = byteArrayOf(OtaOp.FINISH.toByte())

        fun encodeAbort() = byteArrayOf(OtaOp.ABORT.toByte())

        /** An ota_data write; [seq] is the chunk index, sent modulo 65536. */
        fun encodeData(seq: Int, payload: ByteArray, from: Int = 0, to: Int = payload.size): ByteArray =
            ByteArray(2 + to - from).also {
                it.put16(0, seq and 0xFFFF)
                payload.copyInto(it, 2, from, to)
            }
    }
}
