package dev.drillbalancer.upload

import java.io.OutputStream

/** GET /repos/{owner}/{repo}: what Test connection reports. */
data class RepoInfo(
    val fullName: String,
    val isPrivate: Boolean,
    val canPush: Boolean,
    val sizeKb: Long,
    val defaultBranch: String,
)

/** One file of a tree (mode 100644, a blob); [sha] null removes [path] from the base tree. */
data class TreeEntry(val path: String, val sha: String?)

/** A blob's bytes, written by [copyTo] exactly [size] of them (the upload streams them). */
interface BlobContent {
    val size: Long
    fun copyTo(out: OutputStream)
}

/**
 * The part of GitHub's REST API the upload uses (the Git Data API: one commit per recording,
 * never a dataset-level file). Every call blocks; a network failure is an IOException, an
 * answer GitHub refused a [GitHubException]. Implementations never put the token into a
 * message, a log line or toString.
 */
interface GitHubApi {
    /** GET /repos/{o}/{r}. */
    fun repo(): RepoInfo

    /** The commit [branch] points at (GET git/ref/heads/[branch]); an empty repository is [GitHubException.Conflict]. */
    fun headCommit(branch: String): String

    /** The tree of [commit] (GET git/commits/[commit]). */
    fun commitTree(commit: String): String

    /** The files of the folder [path] at [ref], name to blob sha (GET contents); null when there is no such folder. */
    fun listFolder(path: String, ref: String): Map<String, String>?

    /** Whether the file [path] exists at [ref] (the default branch when null). */
    fun fileExists(path: String, ref: String? = null): Boolean

    /** POST git/blobs with [content] as base64, streamed; [sent] hears the file's bytes as they go. Returns the blob sha. */
    fun createBlob(content: BlobContent, sent: (Long) -> Unit = {}): String

    /** POST git/trees over [baseTree]; returns the new tree's sha. */
    fun createTree(baseTree: String, entries: List<TreeEntry>): String

    /** POST git/commits; returns its sha. */
    fun createCommit(message: String, tree: String, parents: List<String>): String

    /** PATCH git/refs/heads/[branch] to [commit] with force false: [GitHubException.Unprocessable] when it is no fast-forward. */
    fun updateRef(branch: String, commit: String)

    /** From another thread: stop the request in flight and every later one (a stopped worker). */
    fun abort() {}
}

/** Makes a client for owner, name, token (HttpGitHubApi in the app). */
typealias GitHubApiFactory = (owner: String, name: String, token: String) -> GitHubApi

/** GitHub answered with an error. [status] is the HTTP status; the message is GitHub's (never the token). */
sealed class GitHubException(val status: Int, message: String) : Exception(message) {
    class Unauthorized(message: String) : GitHubException(401, message)
    class Forbidden(message: String) : GitHubException(403, message)
    class NotFound(message: String) : GitHubException(404, message)
    class Conflict(message: String) : GitHubException(409, message)
    class Unprocessable(message: String) : GitHubException(422, message)

    /** 403 or 429 with Retry-After or an exhausted rate limit, a 429 or a 403 naming a (secondary) rate limit without either: try again after [waitMs]. */
    class RateLimited(status: Int, val waitMs: Long, message: String) : GitHubException(status, message)

    /** 5xx: GitHub's trouble, worth another try later. */
    class Server(status: Int, message: String) : GitHubException(status, message)

    class Other(status: Int, message: String) : GitHubException(status, message)
}
