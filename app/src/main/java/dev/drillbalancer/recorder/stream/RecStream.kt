package dev.drillbalancer.recorder.stream

import dev.drillbalancer.protocol.put16
import dev.drillbalancer.protocol.put32
import java.util.zip.CRC32

/**
 * The recorder's TCP stream (firmware/components/recorder/include/rec_stream.h, the
 * contract; RecStreamConformanceTest compares every constant). Records, little-endian:
 * header (u8 type, u8 ver, u16 len, u32 seq) | payload | CRC-32 over header and payload.
 * The client sends HELLO (16-byte token, u16 version) first; after that only the device sends,
 * beginning with HELLO_OK once it holds the connection. Reasons, profiles and microphones are
 * RecReason, RecProfile and RecOpcode (the same numbers as rec_state and rec_ctrl).
 */
object RecStream {
    const val PORT = 47100
    const val VERSION = 1
    const val HELLO_TOKEN_LEN = 16
    const val HELLO_LEN = 18
    const val HDR_LEN = 8
    const val CRC_LEN = 4
    const val MAX_PAYLOAD = 4096

    const val T_SESSION = 0x01
    const val T_IMU = 0x02
    const val T_AUDIO = 0x03
    const val T_EVENT = 0x04
    const val T_STATS = 0x05
    const val T_LEVEL = 0x06
    const val T_HELLO_OK = 0x07     // no payload: the device took the connection (not part of a take)
    const val T_END = 0x0F

    /** The payload version this decoder reads, per type; other versions pass through. */
    val VERSIONS = mapOf(T_SESSION to 1, T_IMU to 1, T_AUDIO to 1, T_EVENT to 1, T_STATS to 1, T_LEVEL to 1, T_HELLO_OK to 1, T_END to 1)

    // Fixed payload parts (rec_stream.c _Static_asserts).
    const val SESSION_LEN = 78       // then the JSON tail
    const val IMU_LEN = 20           // then n x 12 B
    const val AUDIO_LEN = 20         // then n x channels x 2 B
    const val EVENT_LEN = 12         // then the text
    const val LEVEL_LEN = 26
    const val STATS_LEN = 56
    const val END_LEN = 37
    const val FW_LEN = 32

    const val IMU_DISCONT = 1 shl 0
    const val IMU_EPOCH = 1 shl 1
    const val IMU_MAX_FRAMES = 128
    const val IMU_FRAME_LEN = 12
    const val AUDIO_OVF_BEFORE = 1 shl 0

    const val EV_MARK = 1
    const val EV_TOUCH = 2
    const val EV_BLE_LOST = 3
    const val EV_BLE_BACK = 4
    const val EV_IMU_LOST = 5
    const val EV_IMU_BACK = 6
    const val EV_VIB_START = 7
    const val EV_VIB_STOP = 8
    const val EV_PROFILE = 9
    const val EVENT_TEXT_MAX = 64

    const val STATS_BATTERY_VALID = 1 shl 0
    const val STATS_VBUS = 1 shl 1
    const val STATS_CHARGING = 1 shl 2

    /** EVENT codes 1.. by name (device_events.csv). */
    val EVENT_NAMES = listOf("MARK", "TOUCH", "BLE_LOST", "BLE_BACK", "IMU_LOST", "IMU_BACK", "VIB_START", "VIB_STOP", "PROFILE")

    /** HELLO: the session token from rec_state, then u16 VERSION. */
    fun hello(token: ByteArray): ByteArray {
        require(token.size == HELLO_TOKEN_LEN) { "token is ${token.size} bytes" }
        return token + byteArrayOf(VERSION.toByte(), (VERSION shr 8).toByte())
    }

    fun crc32(b: ByteArray, off: Int, len: Int): Long = CRC32().apply { update(b, off, len) }.value

    /** One record (header, payload, CRC); for tests and fakes that write streams. */
    fun encode(type: Int, ver: Int, seq: Long, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload of ${payload.size} bytes" }
        val out = ByteArray(HDR_LEN + payload.size + CRC_LEN)
        out[0] = type.toByte()
        out[1] = ver.toByte()
        out.put16(2, payload.size)
        out.put32(4, seq)
        payload.copyInto(out, HDR_LEN)
        out.put32(HDR_LEN + payload.size, crc32(out, 0, HDR_LEN + payload.size))
        return out
    }
}

