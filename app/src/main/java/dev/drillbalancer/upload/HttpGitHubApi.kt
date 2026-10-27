package dev.drillbalancer.upload

import dev.drillbalancer.recorder.export.KeepOpen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * [GitHubApi] on HttpURLConnection (no HTTP library): JSON through kotlinx-serialization,
 * blobs streamed as base64 with a fixed length (the file is never held in memory), the
 * headers Authorization: Bearer, X-GitHub-Api-Version 2022-11-28 and a User-Agent. [baseUrl]
 * is GitHub's API (tests: a MockWebServer). [log] hears one line per request (method, path,
 * status; never a header). The token is held here only
 * and is never part of a message, a log line or [toString]; an error message from GitHub that
 * contained it would have it replaced by "***".
 */
class HttpGitHubApi(
    private val owner: String,
    private val name: String,
    token: String,
    private val baseUrl: String = GITHUB_API,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ((String) -> Unit)? = null,
) : GitHubApi {
    private val secret: String = token

    // The connection in flight ([abort] disconnects it); [stopped]: aborted, no request after it.
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var stopped = false

    init {
        require(OWNER.matches(owner) && NAME.matches(name)) { "not a GitHub repository name" }
    }

    override fun toString(): String = "HttpGitHubApi($owner/$name)"

    private val repoPath get() = "/repos/${seg(owner)}/${seg(name)}"

    // ---- GitHubApi ----

    override fun repo(): RepoInfo {
        val o = call("GET", repoPath).obj()
        val perms = o["permissions"] as? JsonObject
        return RepoInfo(
            fullName = o.str("full_name") ?: "$owner/$name",
            isPrivate = (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
            canPush = (perms?.get("push") as? JsonPrimitive)?.booleanOrNull ?: false,
            sizeKb = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            defaultBranch = o.str("default_branch") ?: "main",
        )
    }

    override fun headCommit(branch: String): String {
        val o = call("GET", "$repoPath/git/ref/heads/${path(branch)}").obj()
        return (o["object"] as? JsonObject)?.str("sha") ?: throw IOException("the ref answer has no sha")
    }

    override fun commitTree(commit: String): String {
        val o = call("GET", "$repoPath/git/commits/${seg(commit)}").obj()
        return (o["tree"] as? JsonObject)?.str("sha") ?: throw IOException("the commit answer has no tree")
    }

    override fun listFolder(path: String, ref: String): Map<String, String>? {
        val e = try {
            call("GET", "$repoPath/contents/${path(path)}?ref=${seg(ref)}")
        } catch (_: GitHubException.NotFound) {
            return null
        }
        val files = e as? JsonArray ?: return null        // a file there, not a folder
        return files.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "file") return@mapNotNull null
            val n = o.str("name") ?: return@mapNotNull null
            val sha = o.str("sha") ?: return@mapNotNull null
            n to sha
        }.toMap()
    }

    override fun fileExists(path: String, ref: String?): Boolean = try {
        call("GET", "$repoPath/contents/${path(path)}" + (ref?.let { "?ref=${seg(it)}" } ?: ""))
        true
    } catch (_: GitHubException.NotFound) {
        false
    }

    override fun createBlob(content: BlobContent, sent: (Long) -> Unit): String {
        val o = call("POST", "$repoPath/git/blobs", body = BlobBody(content, sent)).obj()
        return o.str("sha") ?: throw IOException("the blob answer has no sha")
    }

    override fun createTree(baseTree: String, entries: List<TreeEntry>): String {
        val body = buildJsonObject {
            put("base_tree", baseTree)
            putJsonArray("tree") {
                for (e in entries) {
                    add(
                        buildJsonObject {
                            put("path", e.path)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", e.sha?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                }
            }
        }
        return call("POST", "$repoPath/git/trees", json = body).obj().str("sha") ?: throw IOException("the tree answer has no sha")
    }

    override fun createCommit(message: String, tree: String, parents: List<String>): String {
        val body = buildJsonObject {
            put("message", message)
            put("tree", tree)
            putJsonArray("parents") { parents.forEach { add(JsonPrimitive(it)) } }
        }
        return call("POST", "$repoPath/git/commits", json = body).obj().str("sha") ?: throw IOException("the commit answer has no sha")
    }

    override fun updateRef(branch: String, commit: String) {
        call("PATCH", "$repoPath/git/refs/heads/${path(branch)}", json = buildJsonObject { put("sha", commit); put("force", false) })
    }

    /**
     * Stops this client from another thread: the request in flight is disconnected (its blocked
     * socket read or write fails at once; Thread.interrupt does not reach it) and every later
     * call fails with InterruptedIOException before it connects.
     */
    override fun abort() {
        stopped = true
        current?.disconnect()
    }

    // ---- not used by the app: the opt-in live test makes and removes its own branch ----

    /** POST git/refs: a new branch [branch] at [commit]. */
    fun createBranch(branch: String, commit: String) {
        call("POST", "$repoPath/git/refs", json = buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", commit) })
    }

    /** DELETE git/refs/heads/[branch]. */
    fun deleteBranch(branch: String) {
        call("DELETE", "$repoPath/git/refs/heads/${path(branch)}")
    }

    /** Whether [branch] exists. */
    fun branchExists(branch: String): Boolean = try {
        headCommit(branch)
        true
    } catch (_: GitHubException.NotFound) {
        false
    }

    // ---- HTTP ----

    private class BlobBody(val content: BlobContent, val sent: (Long) -> Unit)

    private fun call(method: String, path: String, json: JsonElement? = null, body: BlobBody? = null): JsonElement? {
        // a worker WorkManager stopped sends nothing more (runInterruptible interrupts its thread)
        if (stopped || Thread.currentThread().isInterrupted) throw InterruptedIOException("the upload was stopped")
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        current = c
        if (stopped) throw InterruptedIOException("the upload was stopped")       // aborted between the check and here
        try {
            setMethod(c, method)
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $secret")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            c.setRequestProperty("User-Agent", USER_AGENT)
            when {
                body != null -> writeBlob(c, body)
                json != null -> {
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            log?.invoke("$method ${path.substringBefore('?')} -> $code")
            if (code in 200..299) return if (text.isBlank()) null else answer(text)
            throw error(code, text, c)
        } catch (e: IOException) {
            // the JDK's messages name the URL, never a header; scrubbed all the same
            throw if (e.message?.contains(secret) == true) IOException(scrub(e.message!!)) else e
        } finally {
            current = null
            c.disconnect()
        }
    }

    // A 2xx that is not JSON (a proxy's or captive portal's page): the network's trouble, not a crash.
    private fun answer(text: String): JsonElement = try {
        parse(text)
    } catch (e: IllegalArgumentException) {           // kotlinx' SerializationException
        throw IOException("GitHub's answer is not JSON")
    }

    private fun writeBlob(c: HttpURLConnection, b: BlobBody) {
        val prefix = BLOB_PREFIX.toByteArray(Charsets.US_ASCII)
        val suffix = BLOB_SUFFIX.toByteArray(Charsets.US_ASCII)
        val n = b.content.size
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setFixedLengthStreamingMode(prefix.size + base64Length(n) + suffix.size)
        c.outputStream.buffered(1 shl 16).use { out ->
            out.write(prefix)
            val enc = Base64.getEncoder().wrap(KeepOpen(out))
            var count = 0L
            b.content.copyTo(object : FilterOutputStream(enc) {
                override fun write(buf: ByteArray, off: Int, len: Int) {
                    enc.write(buf, off, len)
                    count += len
                    b.sent(count)
                }

                override fun write(v: Int) {
                    enc.write(v)
                    count++
                }
            })
            if (count != n) throw IOException("the blob had $count bytes, $n announced")
            enc.close()                 // the padding; KeepOpen leaves `out` open
            out.write(suffix)
        }
    }

    private fun error(code: Int, text: String, c: HttpURLConnection): GitHubException {
        val msg = scrub(
            (runCatching { parse(text) }.getOrNull() as? JsonObject)?.str("message")?.take(200)
                ?: text.take(200).ifBlank { "HTTP $code" },
        )
        val retryAfter = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        val remaining = c.getHeaderField("x-ratelimit-remaining")?.trim()
        val reset = c.getHeaderField("x-ratelimit-reset")?.trim()?.toLongOrNull()
        val wait = when {
            retryAfter != null -> retryAfter * 1000
            remaining == "0" && reset != null -> maxOf(0L, reset * 1000 - clock()) + 1000
            else -> null
        }
        // GitHub's secondary rate limit may come as a 403 with neither header ("You have exceeded a
        // secondary rate limit"); its rule then: wait at least a minute.
        val limit = msg.contains("rate limit", ignoreCase = true) || msg.contains("abuse", ignoreCase = true)
        return when {
            (code == 403 || code == 429) && wait != null -> GitHubException.RateLimited(code, wait, msg)
            code == 429 || (code == 403 && limit) -> GitHubException.RateLimited(code, DEFAULT_WAIT_MS, msg)
            code == 401 -> GitHubException.Unauthorized(msg)
            code == 403 -> GitHubException.Forbidden(msg)
            code == 404 -> GitHubException.NotFound(msg)
            code == 409 -> GitHubException.Conflict(msg)
            code == 422 -> GitHubException.Unprocessable(msg)
            code >= 500 -> GitHubException.Server(code, msg)
            else -> GitHubException.Other(code, msg)
        }
    }

    private fun scrub(s: String): String = if (secret.isNotEmpty()) s.replace(secret, "***") else s

    companion object {
        const val GITHUB_API = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val DEFAULT_WAIT_MS = 60_000L
        private const val USER_AGENT = "DrillBalancer-app"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BLOB_PREFIX = "{\"encoding\":\"base64\",\"content\":\""
        private const val BLOB_SUFFIX = "\"}"
        private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val json = Json { ignoreUnknownKeys = true }

        /** "owner/name" split, or null when it is not a repository name. */
        fun parseRepo(s: String): Pair<String, String>? {
            val parts = s.trim().split('/')
            if (parts.size != 2 || !OWNER.matches(parts[0]) || !NAME.matches(parts[1]) || parts[1] == "." || parts[1] == "..") return null
            return parts[0] to parts[1]
        }

        /** Base64 characters of [n] bytes (padded, no line breaks). */
        fun base64Length(n: Long): Long = 4 * ((n + 2) / 3)

        /**
         * Sets [m] on [c]. Android's HttpURLConnection takes PATCH; the desktop JVM's (the unit
         * tests, the opt-in live test) refuses it, so there its method field is set directly.
         */
        internal fun setMethod(c: HttpURLConnection, m: String) {
            try {
                c.requestMethod = m
            } catch (e: ProtocolException) {
                if (m != "PATCH") throw e
                try {
                    val field = HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }
                    field.set(c, m)
                    // HTTPS on the JVM wraps the connection that writes the request
                    var k: Class<*>? = c.javaClass
                    while (k != null && k != HttpURLConnection::class.java) {
                        k.declaredFields.firstOrNull { it.name == "delegate" }?.let { d ->
                            d.isAccessible = true
                            field.set(d.get(c), m)
                        }
                        k = k.superclass
                    }
                } catch (x: Exception) {
                    throw ProtocolException("this JVM's HttpURLConnection cannot send PATCH ($x)")
                }
            }
        }

        private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun path(p: String): String = p.split('/').joinToString("/") { seg(it) }
        private fun parse(text: String): JsonElement = json.parseToJsonElement(text)
        private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw IOException("GitHub's answer is not a JSON object")
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
