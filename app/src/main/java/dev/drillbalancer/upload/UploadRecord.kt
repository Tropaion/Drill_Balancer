package dev.drillbalancer.upload

import dev.drillbalancer.recorder.RecordingIds
import dev.drillbalancer.recorder.RecordingStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * upload.json in a recording folder (phone-only, never uploaded): where its upload stands.
 * [blobs]: per file the git blob sha GitHub holds for it (made by this upload, or seen in a
 * commit), so a resumed upload sends only the rest; [committed]: the files as the last commit
 * that holds the recording has them ([commit]); [error]: why the last try failed for good (null:
 * it did not), [errorKind] its [FailureKind] (key; none in an upload.json written before kinds); [waiting]: it waits for the app to be open (too much to send without the
 * foreground service; UploadQueue.appVisible sends it); [committedRepos]: the data repos a commit
 * of it went to before the record moved to another one ([target]; read through [repos]), so Delete
 * take can say that one set before keeps it. Replaced atomically (RecordingStore.writeAtomic) after every blob and commit.
 */
@Serializable
data class UploadRecord(
    val schema: String = SCHEMA,
    val repo: String,
    val branch: String,
    val path: String,
    val blobs: Map<String, String> = emptyMap(),
    val commit: String? = null,
    val committed: Map<String, String> = emptyMap(),
    @SerialName("uploaded_utc") val uploadedUtc: String? = null,
    val error: String? = null,
    @SerialName("error_kind") val errorKind: String? = null,
    val waiting: Boolean = false,
    @SerialName("committed_repos") val committedRepos: List<String> = emptyList(),
) {
    /** [errorKind] as a kind. */
    val failure: FailureKind? get() = FailureKind.of(errorKind)

    /** Every data repo that holds a commit of it ([committedRepos], and this record's: one from before the list). */
    val repos: Set<String> get() = committedRepos.toSet() + listOfNotNull(repo.takeIf { commit != null })

    /** For [repo] / [branch]: a record of another target counts for nothing (its blobs are elsewhere), but for [repos]. */
    fun target(repo: String, branch: String, path: String): UploadRecord =
        if (this.repo == repo && this.branch == branch && this.path == path) this else UploadRecord(repo = repo, branch = branch, path = path, committedRepos = repos.sorted())

    companion object {
        const val SCHEMA = "drillbalancer.upload/1"
        const val FILE = "upload.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun read(dir: File): UploadRecord? = try {
            File(dir, FILE).takeIf { it.isFile }?.let { json.decodeFromString(serializer(), it.readText()) }
        } catch (_: Exception) {
            null
        }

        /** [dir]'s record, or a new one for [repo] / [branch] / [path] when it has none. */
        fun load(dir: File, repo: String, branch: String, path: String): UploadRecord =
            read(dir) ?: UploadRecord(repo = repo, branch = branch, path = path)

        fun write(dir: File, r: UploadRecord) =
            RecordingStore.writeAtomic(File(dir, FILE), (json.encodeToString(serializer(), r) + "\n").toByteArray())

        /** The data repo's folder of [id] (YYYYMMDDTHHMMSSZ_...): recordings/YYYY/YYYY-MM/<id>. */
        fun repoPath(id: String): String {
            require(RecordingIds.RECORDING_ID.matches(id)) { "not a recording id: $id" }
            return "recordings/${id.substring(0, 4)}/${id.substring(0, 4)}-${id.substring(4, 6)}/$id"
        }
    }
}

/**
 * Why an upload failed for good, kept in upload.json (error_kind) next to its words. The list
 * offers the upload settings for a kind only they fix ([settings]: Retry would fail the same
 * way) and Retry for the others; Retry overwrites the data repo's copy only after a [CONFLICT].
 */
enum class FailureKind(val key: String, val settings: Boolean = false) {
    TOKEN_REJECTED("token_rejected", settings = true),
    NO_TOKEN("no_token", settings = true),
    BAD_REPO("bad_repo", settings = true),              // the repository setting is not owner/name
    TOKEN_UNREADABLE("token_unreadable"),               // the keystore failed
    CONFLICT("conflict"),                               // the data repo's copy changed since this phone uploaded it
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    REPO_SETUP("repo_setup"),                           // an empty repository, a missing branch
    REFUSED("refused"),                                 // GitHub refused the request (409, 422, other 4xx, a protected branch)
    RATE_LIMITED("rate_limited"),
    SERVER("server"),
    NETWORK("network"),
    STORAGE("storage"),
    RECORDING("recording"),                             // its files: too large, not matching meta.json, audio that cannot be muted
    OTHER("other");

    companion object {
        /** The kind of [key]; null for none (an upload.json from before kinds), OTHER for one this version does not know. */
        fun of(key: String?): FailureKind? = key?.let { k -> entries.firstOrNull { it.key == k } ?: OTHER }
    }
}
