package dev.drillbalancer.protocol

import java.security.MessageDigest

// Little-endian helpers for the wire formats (all integers are LE).

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.s8(i: Int): Int = this[i].toInt()

internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()

internal fun ByteArray.u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

internal fun ByteArray.put16(i: Int, v: Int) {
    this[i] = v.toByte()
    this[i + 1] = (v shr 8).toByte()
}

internal fun ByteArray.put32(i: Int, v: Long) {
    put16(i, (v and 0xFFFF).toInt())
    put16(i + 2, ((v shr 16) and 0xFFFF).toInt())
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** A status byte as users see it, e.g. "0x0D". */
internal fun hexByte(v: Int): String = "0x" + (v and 0xFF).toString(16).padStart(2, '0').uppercase()

/** The image digest the device checks at FINISH. */
internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

/** Result of decoding bytes from the device. Bad input is reported, never thrown. */
sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    data class Invalid(val reason: String) : Decoded<Nothing>
}

fun <T> Decoded<T>.getOrNull(): T? = (this as? Decoded.Ok)?.value
