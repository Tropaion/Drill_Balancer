package dev.drillbalancer.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpReleaseSourceTest {
    private val server = MockWebServer().apply { start() }
    private val source = HttpReleaseSource(server.url("/").toString())

    @After
    fun close() = server.close()

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun release(tag: String = "v0.2.0", vararg assets: String, body: String = "null") =
        """{"tag_name":"$tag","draft":false,"body":$body,"assets":[${assets.joinToString(",") { asset(it) }}]}"""

    private fun asset(name: String) =
        """{"name":"$name","size":1234,"browser_download_url":"https://github.com/x/$name","extra":1}"""

    @Test
    fun findsBothAssets() = runTest {
        reply(200, release("v0.2.0", "drillbalancer-0.2.0.bin", "drillbalancer-0.2.0.apk", "notes.txt"))
        val info = (source.latest() as ReleaseResult.Found).info
        assertEquals(ReleaseVersion(0, 2, 0), info.version)
        assertEquals(ReleaseAsset("drillbalancer-0.2.0.bin", "https://github.com/x/drillbalancer-0.2.0.bin", 1234), info.firmware)
        assertEquals("drillbalancer-0.2.0.apk", info.apk?.name)
    }

    @Test
    fun readsTheNotes() = runTest {
        reply(200, release(body = """"## Firmware\n- One level screen""""))
        assertEquals(
            listOf(NoteBlock.Heading("Firmware"), NoteBlock.Bullet("One level screen")),
            (source.latest() as ReleaseResult.Found).info.notes,
        )
    }

    @Test
    fun noNotesAreEmpty() = runTest {
        reply(200, release())
        assertEquals(emptyList<NoteBlock>(), (source.latest() as ReleaseResult.Found).info.notes)
        reply(200, """{"tag_name":"v0.2.0","assets":[]}""")
        assertEquals(emptyList<NoteBlock>(), (source.latest() as ReleaseResult.Found).info.notes)
    }

    @Test
    fun aMissingAssetIsNull() = runTest {
        reply(200, release("v0.2.0", "drillbalancer-0.2.0.apk"))
        val info = (source.latest() as ReleaseResult.Found).info
        assertNull(info.firmware)
        assertEquals("drillbalancer-0.2.0.apk", info.apk?.name)
    }

    @Test
    fun anAssetOfAnotherVersionDoesNotCount() = runTest {
        reply(200, release("v0.2.0", "drillbalancer-0.1.0.bin"))
        assertNull((source.latest() as ReleaseResult.Found).info.firmware)
    }

    @Test
    fun noReleaseIsNone() = runTest {
        reply(404, """{"message":"Not Found"}""")
        assertEquals(ReleaseResult.None, source.latest())
    }

    @Test
    fun aTagThatIsNoVersionIsNone() = runTest {
        reply(200, release("dataset-v1"))
        assertEquals(ReleaseResult.None, source.latest())
    }

    @Test
    fun rateLimitFails() = runTest {
        reply(403, """{"message":"API rate limit exceeded"}""")
        assertEquals(ReleaseResult.Failed, source.latest())
    }

    @Test
    fun malformedJsonFails() = runTest {
        reply(200, "<html>")
        assertEquals(ReleaseResult.Failed, source.latest())
    }

    @Test
    fun missingFieldsFail() = runTest {
        reply(200, """{"assets":[]}""")
        assertEquals(ReleaseResult.Failed, source.latest())
    }

    @Test
    fun noConnectionFails() = runTest {
        server.close()
        assertEquals(ReleaseResult.Failed, source.latest())
    }

    @Test
    fun asksForTheLatestReleaseWithoutCredentials() = runTest {
        reply(404, "{}")
        source.latest()
        val request = server.takeRequest()
        assertEquals("/repos/Tropaion/Drill_Balancer/releases/latest", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
    }
}
