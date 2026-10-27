package dev.drillbalancer.upload

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Git's blob ids as `git hash-object` gives them, and the open file the upload reads. */
class GitBlobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun blobShasAreGits() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHash.blobSha("hello\n".toByteArray()))     // git hash-object
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitHash.blobSha(ByteArray(0)))
        val big = Random(3).nextBytes(300_000)
        val f = File(tmp.root, "x.bin").apply { writeBytes(big) }
        assertEquals(FakeGitHub.gitSha(big), GitHash.blobSha(f))
    }

    /** The open file keeps what was hashed when a new version is renamed over it (how every writer of the app replaces files). */
    @Test
    fun anOpenFileReadsWhatWasHashed() {
        val f = File(tmp.root, "meta.json").apply { writeText("old\n") }
        BlobFile.open(f).use { b ->
            val next = File(tmp.root, "meta.json.tmp").apply { writeText("new contents\n") }
            check(next.renameTo(f))
            val out = ByteArrayOutputStream()
            b.copyTo(out)
            b.copyTo(out)                                   // positional reads: again from the start
            assertEquals("old\nold\n", out.toString())
            assertEquals(GitHash.blobSha("old\n".toByteArray()), b.gitSha)
            assertEquals(4L, b.size)
            assertEquals("meta.json", b.name)
        }
        assertEquals("new contents\n", f.readText())
    }

    /** A file read that fails while its blob streams is the phone's storage (STORAGE, retryable), not a network error. */
    @Test
    fun aFailedReadWhileStreamingIsStorage() {
        val f = File(tmp.root, "audio.flac").apply { writeBytes(Random(5).nextBytes(200_000)) }
        BlobFile.open(f).use { b ->
            java.io.RandomAccessFile(f, "rw").use { it.setLength(10) }          // the file shrank under it
            try {
                b.copyTo(ByteArrayOutputStream())
                org.junit.Assert.fail("read past the end")
            } catch (e: UploadException) {
                assertEquals(UploadException.Kind.STORAGE, e.kind)
                org.junit.Assert.assertTrue(e.retryable)
            }
        }
    }
}
