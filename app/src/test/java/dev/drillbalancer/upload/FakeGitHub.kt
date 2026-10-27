package dev.drillbalancer.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64

/**
 * A GitHub repository in memory behind a MockWebServer, for the Git Data API calls the upload
 * makes: refs, commits, trees (kept flat: path to blob sha), blobs, the contents API, the repo.
 * Blob shas are git's (worked out here independently of the app's GitHash). A tree naming a blob
 * it does not hold is refused with 422, as GitHub does; a ref update that is no fast-forward
 * too. Hooks: [intercept] answers a request instead (errors, dropped connections); [beforePatch]
 * runs before a ref update is applied (a concurrent push: [pushOther]); [gc] drops the blobs no
 * commit on a branch holds. Thread-safe.
 */
class FakeGitHub(
    val owner: String = "Tropaion",
    val name: String = "Drill_Balancer_Data",
    val token: String = TOKEN,
    empty: Boolean = false,
) : Closeable {
    class Commit(val tree: Map<String, String>, val parents: List<String>, val message: String)

    private val lock = Any()
    private val blobs = HashMap<String, ByteArray>()
    private val trees = HashMap<String, Map<String, String>>()
    private val commits = HashMap<String, Commit>()
    private val refs = HashMap<String, String>()
    private var counter = 0
    val requests = ArrayList<String>()           // "METHOD /path" in order
    val blobPosts = ArrayList<String>()          // the sha of every blob posted
    var isPrivate = true
    var canPush = true
    var sizeKb = 1234L
    var template = true                          // schema/meta.v1.schema.json is there

    @Volatile var intercept: ((RecordedRequest) -> MockResponse?)? = null
    @Volatile var beforePatch: ((String) -> Unit)? = null

    val server = MockWebServer()

    init {
        if (!empty) {
            val files = mutableMapOf("README.md" to put("# data\n".toByteArray()), "index.csv" to put("recording_id\n".toByteArray()))
            files["schema/meta.v1.schema.json"] = put("{}\n".toByteArray())
            refs["main"] = commit(files, emptyList(), "bootstrap")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
                requests += "${request.method} ${request.url.encodedPath}"
                intercept?.invoke(request) ?: handle(request)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun api(token: String = this.token, log: ((String) -> Unit)? = null) = HttpGitHubApi(owner, name, token, baseUrl, log = log)

    override fun close() = server.close()

    // ---- the repository, for the tests ----

    fun head(branch: String = "main"): String? = synchronized(lock) { refs[branch] }

    fun commitOf(sha: String): Commit = synchronized(lock) { commits.getValue(sha) }

    /** The files at [branch]'s commit, path to blob sha. */
    fun files(branch: String = "main"): Map<String, String> = synchronized(lock) { refs[branch]?.let { commits.getValue(it).tree }.orEmpty() }

    fun blob(sha: String): ByteArray? = synchronized(lock) { blobs[sha] }

    /** Commits on [branch] from its tip back (first parents). */
    fun history(branch: String = "main"): List<Commit> = synchronized(lock) {
        val out = ArrayList<Commit>()
        var c = refs[branch]
        while (c != null) {
            val k = commits.getValue(c)
            out += k
            c = k.parents.firstOrNull()
        }
        out
    }

    /** Someone else pushes a commit to [branch] (another file changes). */
    fun pushOther(branch: String = "main") = synchronized(lock) {
        val head = refs.getValue(branch)
        val files = commits.getValue(head).tree.toMutableMap()
        files["index.csv"] = put("recording_id\nx ${++counter}\n".toByteArray())
        refs[branch] = commit(files, listOf(head), "Update index")
    }

    /** Someone else commits [bytes] as [path] on [branch] (a label edit on the PC). */
    fun pushFile(path: String, bytes: ByteArray, branch: String = "main", message: String = "Relabel on the PC") = synchronized(lock) {
        val head = refs.getValue(branch)
        val files = commits.getValue(head).tree.toMutableMap()
        files[path] = put(bytes)
        refs[branch] = commit(files, listOf(head), message)
    }

    /** Drops every blob no commit on a branch holds (what GitHub does to blobs of an upload that never committed). */
    fun gc() = synchronized(lock) {
        val keep = HashSet<String>()
        for (tip in refs.values) {
            var c: String? = tip
            while (c != null) {
                val k = commits.getValue(c)
                keep += k.tree.values
                c = k.parents.firstOrNull()
            }
        }
        blobs.keys.retainAll(keep)
    }

    // ---- the API ----

    private fun handle(r: RecordedRequest): MockResponse {
        if (r.headers["Authorization"] != "Bearer $token") return error(401, "Bad credentials")
        val path = r.url.encodedPath
        val repoPath = "/repos/$owner/$name"
        if (!path.startsWith(repoPath)) return error(404, "Not Found")
        val rest = path.removePrefix(repoPath)
        val m = r.method
        return when {
            m == "GET" && rest.isEmpty() -> json(
                200,
                buildJsonObject {
                    put("full_name", "$owner/$name")
                    put("private", isPrivate)
                    put("size", sizeKb)
                    put("default_branch", "main")
                    putJsonObject("permissions") {
                        put("admin", false)
                        put("push", canPush)
                        put("pull", true)
                    }
                },
            )
            m == "GET" && rest.startsWith("/git/ref/heads/") -> {
                if (refs.isEmpty()) return error(409, "Git Repository is empty.")
                val b = rest.removePrefix("/git/ref/heads/")
                val sha = refs[b] ?: return error(404, "Not Found")
                json(200, buildJsonObject { put("ref", "refs/heads/$b"); putJsonObject("object") { put("sha", sha); put("type", "commit") } })
            }
            m == "GET" && rest.startsWith("/git/commits/") -> {
                val sha = rest.removePrefix("/git/commits/")
                val c = commits[sha] ?: return error(404, "Not Found")
                json(200, buildJsonObject { put("sha", sha); putJsonObject("tree") { put("sha", treeSha(c.tree)) } })
            }
            m == "GET" && rest.startsWith("/contents/") -> contents(rest.removePrefix("/contents/"), r.url.queryParameter("ref"))
            m == "POST" && rest == "/git/blobs" -> {
                val body = body(r)
                require(body["encoding"]!!.jsonPrimitive.content == "base64")
                val bytes = Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content)
                val sha = put(bytes)
                blobPosts += sha
                json(201, buildJsonObject { put("sha", sha) })
            }
            m == "POST" && rest == "/git/trees" -> {
                val body = body(r)
                val base = trees[body["base_tree"]!!.jsonPrimitive.content] ?: return error(422, "Invalid tree info")
                val out = base.toMutableMap()
                for (e in body["tree"]!!.jsonArray) {
                    val o = e.jsonObject
                    val p = o["path"]!!.jsonPrimitive.content
                    val sha = o["sha"]
                    if (sha == null || sha is JsonNull) {
                        out.remove(p)
                    } else {
                        val s = sha.jsonPrimitive.content
                        if (s !in blobs) return error(422, "GitRPC::BadObjectState")
                        out[p] = s
                    }
                }
                json(201, buildJsonObject { put("sha", treeSha(out)) })
            }
            m == "POST" && rest == "/git/commits" -> {
                val body = body(r)
                val tree = trees[body["tree"]!!.jsonPrimitive.content] ?: return error(422, "Tree SHA does not exist")
                val parents = body["parents"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (parents.any { it !in commits }) return error(422, "Parent SHA does not exist")
                val sha = commit(tree, parents, body["message"]!!.jsonPrimitive.content)
                json(201, buildJsonObject { put("sha", sha) })
            }
            m == "PATCH" && rest.startsWith("/git/refs/heads/") -> {
                val b = rest.removePrefix("/git/refs/heads/")
                val body = body(r)
                beforePatch?.invoke(b)
                val cur = refs[b] ?: return error(422, "Reference does not exist")
                val next = body["sha"]!!.jsonPrimitive.content
                val force = body["force"]?.jsonPrimitive?.content == "true"
                if (!force && !ancestor(cur, next)) return error(422, "Update is not a fast forward")
                refs[b] = next
                json(200, buildJsonObject { put("ref", "refs/heads/$b"); putJsonObject("object") { put("sha", next) } })
            }
            m == "POST" && rest == "/git/refs" -> {
                val body = body(r)
                val ref = body["ref"]!!.jsonPrimitive.content.removePrefix("refs/heads/")
                if (ref in refs) return error(422, "Reference already exists")
                refs[ref] = body["sha"]!!.jsonPrimitive.content
                json(201, buildJsonObject { put("ref", "refs/heads/$ref") })
            }
            m == "DELETE" && rest.startsWith("/git/refs/heads/") -> {
                refs.remove(rest.removePrefix("/git/refs/heads/")) ?: return error(422, "Reference does not exist")
                MockResponse.Builder().code(204).build()
            }
            else -> error(404, "Not Found")
        }
    }

    private fun contents(path: String, ref: String?): MockResponse {
        val c = (ref?.let { refs[it] ?: it } ?: refs["main"])?.let { commits[it] } ?: return error(404, "This repository is empty.")
        if (path == "schema/meta.v1.schema.json" && !template) return error(404, "Not Found")
        c.tree[path]?.let { sha -> return json(200, buildJsonObject { put("type", "file"); put("name", path.substringAfterLast('/')); put("path", path); put("sha", sha) }) }
        val inside = c.tree.filterKeys { it.startsWith("$path/") }
        if (inside.isEmpty()) return error(404, "Not Found")
        val listing = buildJsonArray {
            val dirs = sortedSetOf<String>()
            for ((p, sha) in inside.toSortedMap()) {
                val sub = p.removePrefix("$path/")
                if ('/' in sub) {
                    dirs += sub.substringBefore('/')
                } else {
                    add(buildJsonObject { put("type", "file"); put("name", sub); put("path", p); put("sha", sha); put("size", blobs[sha]?.size ?: 0) })
                }
            }
            for (d in dirs) add(buildJsonObject { put("type", "dir"); put("name", d); put("path", "$path/$d"); put("sha", "0".repeat(40)) })
        }
        return json(200, listing)
    }

    private fun ancestor(old: String, new: String): Boolean {
        var c: String? = new
        while (c != null) {
            if (c == old) return true
            c = commits[c]?.parents?.firstOrNull()
        }
        return false
    }

    private fun put(bytes: ByteArray): String = gitSha(bytes).also { blobs[it] = bytes }

    private fun commit(tree: Map<String, String>, parents: List<String>, message: String): String {
        val sha = sha1("commit ${++counter} ${treeSha(tree)} $parents $message".toByteArray())
        commits[sha] = Commit(tree.toMap(), parents, message)
        return sha
    }

    private fun treeSha(tree: Map<String, String>): String {
        val sha = sha1(tree.toSortedMap().entries.joinToString("\n") { "${it.key} ${it.value}" }.toByteArray())
        trees[sha] = tree.toMap()
        return sha
    }

    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    companion object {
        /** A token shaped like a fine-grained one; the tests look for it in every message and file. */
        const val TOKEN = "fake_token_for_the_tests_0123456789_notasecret"

        fun json(code: Int, body: kotlinx.serialization.json.JsonElement): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body.toString()).build()

        fun error(code: Int, message: String, vararg headers: Pair<String, String>): MockResponse {
            val b = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
                .body(buildJsonObject { put("message", message); put("documentation_url", "https://docs.github.com/rest") }.toString())
            for ((k, v) in headers) b.addHeader(k, v)
            return b.build()
        }

        fun sha1(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

        /** git hash-object of [bytes], written out here (not the app's GitHash). */
        fun gitSha(bytes: ByteArray): String = sha1("blob ${bytes.size}\u0000".toByteArray() + bytes)
    }
}
