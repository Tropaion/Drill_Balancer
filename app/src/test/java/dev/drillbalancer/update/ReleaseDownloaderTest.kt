package dev.drillbalancer.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ReleaseDownloaderTest {
    private val server = MockWebServer().apply { start() }
    private val downloader = ReleaseDownloader(server.url("/").toString())
    private val body = ByteArray(100_000) { it.toByte() }
    private val out = ByteArrayOutputStream()

    @After
    fun close() = server.close()

    private fun asset(size: Long = body.size.toLong(), path: String = "/a.bin") = ReleaseAsset("a.bin", server.url(path).toString(), size)

    private fun fetch(asset: ReleaseAsset, maxBytes: Long = 1_000_000, onProgress: (Int) -> Unit = {}) = runBlocking {
        downloader.fetch(asset, maxBytes, out, onProgress)
    }

    @Test
    fun writesTheBody() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        val seen = ArrayList<Int>()
        fetch(asset()) { seen += it }
        assertArrayEquals(body, out.toByteArray())
        assertEquals(100, seen.last())
        assertEquals(seen.distinct(), seen)
    }

    @Test
    fun refusesAnAssetLargerThanTheLimit() {
        assertThrows(DownloadException::class.java) { fetch(asset(size = 2000), maxBytes = 1000) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun stopsWhenTheBodyGrowsPastItsSize() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        assertThrows(DownloadException::class.java) { fetch(asset(size = 1000), maxBytes = 1000) }
        assertTrue(out.size() <= 1000)
    }

    @Test
    fun aShortBodyFails() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        assertThrows(DownloadException::class.java) { fetch(asset(size = body.size + 1L)) }
    }

    @Test
    fun aServerErrorFails() {
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        val e = assertThrows(DownloadException::class.java) { fetch(asset()) }
        assertTrue(e.message, "500" in e.message!!)
    }

    @Test
    fun refusesAnotherHost() {
        val outside = ReleaseAsset("a.bin", "https://example.com/a.bin", 10)
        assertThrows(DownloadException::class.java) { fetch(outside) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusesDotSegmentsOutOfThePrefix() {
        val inside = ReleaseDownloader(server.url("/releases/").toString())
        val outside = ReleaseAsset("a.bin", server.url("/releases/").toString() + "../other/a.bin", 10)
        assertThrows(DownloadException::class.java) { runBlocking { inside.fetch(outside, 100, out) {} } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusesAnEmptyAsset() {
        assertThrows(DownloadException::class.java) { fetch(asset(size = 0)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFullDiskIsNotAConnectionProblem() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("ENOSPC")
        }
        val e = assertThrows(DownloadException::class.java) { runBlocking { downloader.fetch(asset(), 1_000_000, full) {} } }
        assertTrue(e.message, "storage" in e.message!!)
    }

    @Test
    fun followsARedirectWithoutCredentials() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/storage/a.bin")).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        fetch(asset())
        assertArrayEquals(body, out.toByteArray())
        repeat(2) { assertNull(server.takeRequest().headers["Authorization"]) }
    }

    @Test
    fun cancellingStopsReading() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).throttleBody(10_000, 1, TimeUnit.SECONDS).build())
        val started = CompletableDeferred<Unit>()
        val fetching = async(Dispatchers.Default) { downloader.fetch(asset(), 1_000_000, out) { started.complete(Unit) } }
        withTimeout(5000) { started.await() }
        fetching.cancel()
        val failure = runCatching { fetching.await() }.exceptionOrNull()
        assertTrue("$failure", failure is CancellationException)
        assertTrue(out.size() < body.size)
    }
}
