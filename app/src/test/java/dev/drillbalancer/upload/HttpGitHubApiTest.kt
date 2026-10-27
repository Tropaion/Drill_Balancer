package dev.drillbalancer.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.io.OutputStream
import java.util.Base64
import kotlin.random.Random

/** The HTTP side of the upload against a MockWebServer: headers, streamed blobs, PATCH, the error mapping, the token kept out. */
class HttpGitHubApiTest {
    private val server = MockWebServer().apply { start() }
    private val token = FakeGitHub.TOKEN
    private val lines = ArrayList<String>()
    private val api = HttpGitHubApi("Tropaion", "Drill_Balancer_Data", token, server.url("/").toString(), clock = { 1_000_000L }, log = { lines += it })

    @After
    fun close() = server.close()

    private fun ok(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun content(bytes: ByteArray) = object : BlobContent {
        override val size = bytes.size.toLong()
        override fun copyTo(out: OutputStream) {
            var i = 0
            while (i < bytes.size) {                      // in pieces, as a file is read
                val n = minOf(7000, bytes.size - i)
                out.write(bytes, i, n)
                i += n
            }
        }
    }

    @Test
    fun everyRequestCarriesTheHeaders() {
        ok("""{"full_name":"Tropaion/Drill_Balancer_Data","private":true,"size":2048,"default_branch":"main","permissions":{"push":true}}""")
        val r = api.repo()
        assertEquals(RepoInfo("Tropaion/Drill_Balancer_Data", isPrivate = true, canPush = true, sizeKb = 2048, defaultBranch = "main"), r)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/repos/Tropaion/Drill_Balancer_Data", req.url.encodedPath)
        assertEquals("Bearer $token", req.headers["Authorization"])
        assertEquals("2022-11-28", req.headers["X-GitHub-Api-Version"])
        assertEquals("application/vnd.github+json", req.headers["Accept"])
        assertTrue(req.headers["User-Agent"]!!.isNotBlank())
        assertEquals(listOf("GET /repos/Tropaion/Drill_Balancer_Data -> 200"), lines)
        assertFalse(lines.any { token in it })
    }

    @Test
    fun aBlobIsStreamedAsBase64WithAFixedLength() {
        val bytes = Random(7).nextBytes(200_001)            // not a multiple of 3: padding
        ok("""{"sha":"abc","url":"x"}""", 201)
        val seen = ArrayList<Long>()
        assertEquals("abc", api.createBlob(content(bytes)) { seen += it })
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/Tropaion/Drill_Balancer_Data/git/blobs", req.url.encodedPath)
        val body = req.body!!.toByteArray()
        assertEquals("fixed length, not chunked", body.size.toString(), req.headers["Content-Length"])
        assertNull(req.headers["Transfer-Encoding"])
        assertTrue(req.chunkSizes.isNullOrEmpty())
        val o = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("base64", o["encoding"]!!.jsonPrimitive.content)
        assertTrue(Base64.getDecoder().decode(o["content"]!!.jsonPrimitive.content).contentEquals(bytes))
        assertEquals(bytes.size.toLong(), seen.last())
        assertEquals(HttpGitHubApi.base64Length(bytes.size.toLong()), o["content"]!!.jsonPrimitive.content.length.toLong())
        for (n in listOf(0L, 1, 2, 3, 4, 5, 6)) assertEquals(Base64.getEncoder().encodeToString(ByteArray(n.toInt())).length.toLong(), HttpGitHubApi.base64Length(n))
    }

    @Test
    fun anEmptyBlob() {
        ok("""{"sha":"e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"}""", 201)
        api.createBlob(content(ByteArray(0)))
        assertEquals("""{"encoding":"base64","content":""}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun theRefIsUpdatedWithPatchWithoutForce() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ref":"refs/heads/main","object":{"sha":"c2"}}""").build())
        api.updateRef("main", "c2")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/repos/Tropaion/Drill_Balancer_Data/git/refs/heads/main", req.url.encodedPath)
        val o = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("c2", o["sha"]!!.jsonPrimitive.content)
        assertEquals("false", o["force"]!!.jsonPrimitive.content)
    }

    /** The desktop JVM refuses PATCH; HttpGitHubApi sets it on the connection that writes the request (HTTPS wraps one: the live test). */
    @Test
    fun patchOnTheJvmsHttpsConnections() {
        val c = java.net.URL("https://api.github.com/repos/x/y/git/refs/heads/main").openConnection() as javax.net.ssl.HttpsURLConnection
        HttpGitHubApi.setMethod(c, "PATCH")
        assertEquals("PATCH", c.requestMethod)
        val plain = java.net.URL("http://127.0.0.1:9/x").openConnection() as java.net.HttpURLConnection
        HttpGitHubApi.setMethod(plain, "PATCH")
        assertEquals("PATCH", plain.requestMethod)
        HttpGitHubApi.setMethod(plain, "DELETE")
        assertEquals("DELETE", plain.requestMethod)
        try {
            HttpGitHubApi.setMethod(plain, "BREW")
            fail("took BREW")
        } catch (_: java.net.ProtocolException) {
        }
    }

    @Test
    fun aTreeOverItsBaseRemovesWithANullSha() {
        ok("""{"sha":"t2"}""", 201)
        assertEquals("t2", api.createTree("t1", listOf(TreeEntry("recordings/a/meta.json", "s1"), TreeEntry("recordings/a/old.csv", null))))
        val o = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("t1", o["base_tree"]!!.jsonPrimitive.content)
        val t = o["tree"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("100644", "100644"), t.map { it["mode"]!!.jsonPrimitive.content })
        assertEquals(listOf("blob", "blob"), t.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("s1", t[0]["sha"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, t[1]["sha"])
    }

    @Test
    fun aFolderListsItsFilesAndAMissingOneIsNull() {
        ok("""[{"type":"file","name":"meta.json","sha":"m"},{"type":"dir","name":"x","sha":"d"},{"type":"file","name":"events.json","sha":"e"}]""")
        assertEquals(mapOf("meta.json" to "m", "events.json" to "e"), api.listFolder("recordings/2026/2026-10/r", "c1"))
        val req = server.takeRequest()
        assertEquals("/repos/Tropaion/Drill_Balancer_Data/contents/recordings/2026/2026-10/r", req.url.encodedPath)
        assertEquals("c1", req.url.queryParameter("ref"))
        server.enqueue(FakeGitHub.error(404, "Not Found"))
        assertNull(api.listFolder("recordings/x", "c1"))
        server.enqueue(FakeGitHub.error(404, "Not Found"))
        assertFalse(api.fileExists("schema/meta.v1.schema.json"))
        ok("""{"type":"file","name":"meta.v1.schema.json","sha":"s"}""")
        assertTrue(api.fileExists("schema/meta.v1.schema.json"))
    }

    @Test
    fun headAndTree() {
        ok("""{"ref":"refs/heads/feature/x","object":{"sha":"c1","type":"commit"}}""")
        assertEquals("c1", api.headCommit("feature/x"))
        assertEquals("/repos/Tropaion/Drill_Balancer_Data/git/ref/heads/feature/x", server.takeRequest().url.encodedPath)
        ok("""{"sha":"c1","tree":{"sha":"t1"}}""")
        assertEquals("t1", api.commitTree("c1"))
        ok("""{"sha":"c2"}""", 201)
        assertEquals("c2", api.createCommit("Add x", "t1", listOf("c1")))
        server.takeRequest()
        val o = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("Add x", o["message"]!!.jsonPrimitive.content)
        assertEquals(listOf("c1"), o["parents"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    private inline fun <reified T : GitHubException> fails(response: MockResponse, block: () -> Unit = { api.headCommit("main") }): T {
        server.enqueue(response)
        try {
            block()
        } catch (e: GitHubException) {
            assertTrue("${e.javaClass.simpleName}: ${e.message}", e is T)
            assertFalse("no token in the message", token in (e.message ?: ""))
            assertFalse(token in e.toString())
            return e as T
        }
        fail("no ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun errorsBecomeTheirKinds() {
        fails<GitHubException.Unauthorized>(FakeGitHub.error(401, "Bad credentials"))
        fails<GitHubException.NotFound>(FakeGitHub.error(404, "Not Found"))
        assertEquals("Git Repository is empty.", fails<GitHubException.Conflict>(FakeGitHub.error(409, "Git Repository is empty.")).message)
        fails<GitHubException.Unprocessable>(FakeGitHub.error(422, "Update is not a fast forward"))
        assertEquals(502, fails<GitHubException.Server>(FakeGitHub.error(502, "Bad gateway")).status)
        fails<GitHubException.Forbidden>(FakeGitHub.error(403, "Resource not accessible by personal access token"))
        assertEquals(418, fails<GitHubException.Other>(FakeGitHub.error(418, "teapot")).status)
    }

    @Test
    fun rateLimitsSayHowLongToWait() {
        assertEquals(30_000L, fails<GitHubException.RateLimited>(FakeGitHub.error(403, "secondary rate limit", "Retry-After" to "30")).waitMs)
        assertEquals(12_000L, fails<GitHubException.RateLimited>(FakeGitHub.error(429, "slow down", "Retry-After" to "12")).waitMs)
        // the primary limit: reset at 1100 s, now 1000 s, plus a second
        val r = fails<GitHubException.RateLimited>(FakeGitHub.error(403, "API rate limit exceeded", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1100"))
        assertEquals(101_000L, r.waitMs)
        assertEquals(403, r.status)
        assertEquals(60_000L, fails<GitHubException.RateLimited>(FakeGitHub.error(429, "slow down")).waitMs)
        // remaining calls left: a plain 403
        fails<GitHubException.Forbidden>(FakeGitHub.error(403, "no", "x-ratelimit-remaining" to "4999", "x-ratelimit-reset" to "1100"))
        // GitHub's secondary limit without either header: wait a minute (its documented rule), not "the token may not write"
        val secondary = "You have exceeded a secondary rate limit. Please wait a few minutes before you try again."
        assertEquals(60_000L, fails<GitHubException.RateLimited>(FakeGitHub.error(403, secondary)).waitMs)
        assertEquals(60_000L, fails<GitHubException.RateLimited>(FakeGitHub.error(403, secondary, "x-ratelimit-remaining" to "4999")).waitMs)
    }

    /** A 2xx whose body is no JSON (a proxy's or captive portal's page) is an IOException, never a crash of the caller. */
    @Test
    fun anAnswerThatIsNotJsonIsAnIOException() {
        ok("<html><body>Sign in to the Wi-Fi</body></html>")
        try {
            api.repo()
            fail("no IOException")
        } catch (e: IOException) {
            assertEquals("GitHub's answer is not JSON", e.message)
        }
    }

    /**
     * A stopped worker: [stoppable] disconnects the request in flight (its socket read ignores the
     * interrupt; here GitHub would answer only after 60 s) and the call ends at once; the client
     * then sends nothing more.
     */
    @Test
    fun aStoppedUploadEndsItsRequestAtOnce() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(60, TimeUnit.SECONDS).build())
        val ended = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            try {
                stoppable(api) { api.repo() }
                ended.complete(null)
            } catch (e: Throwable) {
                ended.complete(e)
                throw e
            }
        }
        server.takeRequest(10, TimeUnit.SECONDS)!!
        val t0 = System.nanoTime()
        job.cancel()
        val e = withTimeout(10_000) { ended.await() }
        assertTrue("$e", e is CancellationException)
        assertTrue("ended after ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 5_000_000_000L)
        val before = server.requestCount
        try {
            api.repo()
            fail("a request after the stop")
        } catch (_: java.io.InterruptedIOException) {
        }
        assertEquals(before, server.requestCount)
    }

    /** A thread that was interrupted (runInterruptible) starts no request. */
    @Test
    fun anInterruptedThreadSendsNothing() {
        Thread.currentThread().interrupt()
        try {
            api.repo()
            fail("sent")
        } catch (_: java.io.InterruptedIOException) {
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theTokenNeverShows() {
        val e = fails<GitHubException.Unauthorized>(FakeGitHub.error(401, "Bad credentials for $token"))
        assertEquals("Bad credentials for ***", e.message)
        assertFalse(token in api.toString())
        assertEquals("HttpGitHubApi(Tropaion/Drill_Balancer_Data)", api.toString())
        assertFalse(lines.any { token in it })
    }

    @Test
    fun aDroppedConnectionIsAnIOException() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        try {
            api.createCommit("m", "t", emptyList())
            fail("no IOException")
        } catch (e: IOException) {
            assertFalse(token in (e.message ?: ""))
        }
    }

    @Test
    fun repoNames() {
        assertEquals("Tropaion" to "Drill_Balancer_Data", HttpGitHubApi.parseRepo(" Tropaion/Drill_Balancer_Data "))
        for (bad in listOf("", "Tropaion", "a/b/c", "/x", "x/", "-a/b", "a/b c", "a/..", "a/.")) assertNull(bad, HttpGitHubApi.parseRepo(bad))
    }
}
