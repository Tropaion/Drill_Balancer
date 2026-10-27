package dev.drillbalancer.upload

import dev.drillbalancer.protocol.toHex
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Git's object ids: a blob's is SHA-1 over "blob <size>\0" and the bytes. */
object GitHash {
    fun blobSha(bytes: ByteArray): String {
        val md = header(bytes.size.toLong())
        md.update(bytes)
        return md.digest().toHex()
    }

    fun blobSha(f: File): String = BlobFile.open(f).use { it.gitSha }

    internal fun header(size: Long): MessageDigest =
        MessageDigest.getInstance("SHA-1").apply { update("blob $size\u0000".toByteArray(Charsets.US_ASCII)) }
}

/**
 * A dataset file opened for an upload: hashed once on open (its git blob sha and its sha256,
 * which meta.files must hold), then read again from the same open file for the blob. The
 * app replaces a recording's files by renaming new ones over them, never by writing into them,
 * so what an open file reads stays what was hashed while a Save or a mute writes the next
 * version. Positional reads: [copyTo] can run any number of times.
 */
class BlobFile private constructor(val name: String, private val raf: RandomAccessFile) : BlobContent, Closeable {
    private val channel: FileChannel = raf.channel
    override val size: Long = channel.size()
    val gitSha: String
    val sha256: String

    init {
        val git = GitHash.header(size)
        val sha = MessageDigest.getInstance("SHA-256")
        read { buf, n ->
            git.update(buf, 0, n)
            sha.update(buf, 0, n)
        }
        gitSha = git.digest().toHex()
        sha256 = sha.digest().toHex()
    }

    /** The bytes to [out]; a read of the file that fails is the phone's storage (UploadException STORAGE), a write to [out] the network's. */
    override fun copyTo(out: OutputStream) = read(storage = true) { buf, n -> out.write(buf, 0, n) }

    private inline fun read(storage: Boolean = false, each: (ByteArray, Int) -> Unit) {
        val arr = ByteArray(1 shl 16)
        val bb = ByteBuffer.wrap(arr)
        var pos = 0L
        while (pos < size) {
            bb.clear()
            val n = try {
                channel.read(bb, pos).also { if (it < 0) throw IOException("$name ended after $pos of $size bytes") }
            } catch (e: IOException) {
                throw if (storage) DatasetUploader.storage(e) else e
            }
            each(arr, n)
            pos += n
        }
    }

    override fun close() = raf.close()

    override fun toString(): String = "BlobFile($name, $size bytes, $gitSha)"

    companion object {
        fun open(f: File): BlobFile {
            val raf = RandomAccessFile(f, "r")
            return try {
                BlobFile(f.name, raf)
            } catch (e: Throwable) {
                raf.close()
                throw e
            }
        }
    }
}
