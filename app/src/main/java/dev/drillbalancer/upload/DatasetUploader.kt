package dev.drillbalancer.upload

import dev.drillbalancer.recorder.RecordingIds
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.AudioMute
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.MuteException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToLong

/** A recording that cannot be uploaded as it is ([retryable]: it may work later). */
class UploadException(val kind: Kind, message: String, val retryable: Boolean = false) : Exception(message) {
    enum class Kind {
        NOT_FINALIZED, TOO_LARGE, FILES_CHANGED, STORAGE, REPO_EMPTY, BRANCH_MISSING, BRANCH_MOVING, BLOB_MISMATCH,

        /** The data repo's meta.json of the recording is one this phone never uploaded (changed there, on the PC): not overwritten unless asked. */
        CONFLICT,

        /** More to send than [DatasetUploader]'s sendLimit (a worker without its foreground service): later, with the app open. */
        NEEDS_FOREGROUND,

        /** Mute audio is on and the audio cannot be muted as the recording's files are (AudioMute's MuteException). */
        CANNOT_MUTE,

        /** GitHub refuses to move the branch at all (422 while it did not move: a protected branch). */
        BRANCH_REFUSED,
    }
}

/**
 * A Delete take of the recording stopped the upload (RecordingStore.reader), and that delete has
 * ended: the recording is gone, or the delete failed and it is there as it was (UploadWorker).
 */
internal class StoppedByDelete : Exception("a Delete take stopped it")

/**
 * Uploads one reviewed recording to the data repo as one commit through the Git Data API
 * (dataset contract: the phone never writes a dataset-level file):
 *
 * 1. Under the recording's lock (RecordingStore.exclusive, so never while it is finalized,
 *    labelled or saved): the label pair a crash left half written is finished, the audio is
 *    muted when [mute] is on (AudioMute), and the files are opened and hashed (BlobFile:
 *    the git blob sha, and the sha256 meta.files must hold). meta.json, events.json and the
 *    data files meta.files lists are sent, nothing else; a file over [maxFileBytes] is refused
 *    before anything is sent. The upload then reads the open files, so a Save meanwhile does not
 *    wait for it, and the next upload sends what it changed. A Delete take meanwhile stops it
 *    ([api]'s abort; RecordingStore.remove): whatever the cut request threw is then
 *    [StoppedByDelete], thrown once that delete ended, never a network error.
 * 2. GET the branch's commit (an empty repository: the template is pushed from the PC first)
 *    and the recording's folder there: when its meta.json has the same blob sha the recording
 *    is there already (no commit).
 * 3. POST every blob that neither upload.json (an upload cut short) nor the folder holds, as
 *    base64 streamed from the file; upload.json is written after each.
 * 4. POST a tree over the commit's tree with the folder's files (recordings/YYYY/YYYY-MM/<id>/;
 *    files this phone committed there that the recording no longer has are removed, files it
 *    never committed stay), a commit "Add <id>: wood/softwood 38 mm, drill, 42 s" or "Update
 *    labels of <id>", and PATCH the branch (force false). When the branch moved meanwhile (422)
 *    all of 2.-4. again on its new commit, blobs reused, up to [MAX_TRIES] times; a 422 while it
 *    did not move is GitHub refusing to move it (a protected branch: BRANCH_REFUSED, no redo). When the tree is refused (422) while it names blobs upload.json kept
 *    (GitHub drops blobs no commit holds), those are forgotten and sent again, once.
 *
 * Over a folder that is there already it commits only when the folder's meta.json is one this
 * phone uploaded (upload.json's committed or blobs): one changed in the data repo since (labels
 * edited on the PC, dbdata.autodetect --write --force) stops it with CONFLICT, unless
 * [overwrite] (the user's Retry after that message). The same check stops an upload WorkManager
 * gave up on that is still running (its 422 redo would commit an older state over a newer upload
 * of the same recording). More blob bytes to send than [sendLimit] stop it before the first
 * (NEEDS_FOREGROUND). upload.json writes and the file reads while a blob streams are the phone's
 * storage (STORAGE, retryable), never the network; audio that cannot be muted as it is
 * (CANNOT_MUTE, with AudioMute's reason) is not retried.
 *
 * Blocking. Errors: [GitHubException] (GitHub's answer), IOException (the network), [UploadException].
 */
class DatasetUploader(
    private val api: GitHubApi,
    private val store: RecordingStore,
    private val repo: String,                 // owner/name, as upload.json says
    private val branch: String = DEFAULT_BRANCH,
    private val mute: Boolean = false,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val overwrite: Boolean = false,
    private val sendLimit: Long = Long.MAX_VALUE,
) {
    sealed interface Result {
        /** A new commit holds the recording; [uploaded] the blobs sent for it. */
        data class Committed(val commit: String, val uploaded: List<String>, val update: Boolean, val message: String) : Result

        /** The branch's commit [commit] holds this meta.json already. */
        data class AlreadyThere(val commit: String) : Result

        /** Its labels are not reviewed (meta.review): nothing to upload. */
        data object NotReviewed : Result
    }

    // the store's reader of the recording until the upload ends (RecordingStore.reader)
    private class Snapshot(val files: List<BlobFile>, val meta: JsonObject, val reader: RecordingStore.Reader) : Closeable {
        val shas: Map<String, String> = files.associate { it.name to it.gitSha }

        override fun close() = try {
            files.forEach { it.close() }
        } finally {
            reader.close()
        }
    }

    /** Uploads [id]; [progress] hears the blob bytes sent of those to send. */
    fun upload(id: String, progress: (sent: Long, total: Long) -> Unit = { _, _ -> }): Result {
        require(RecordingIds.RECORDING_ID.matches(id)) { "not a recording id" }
        val snap = snapshot(id) ?: return Result.NotReviewed
        try {
            snap.use { return push(id, it, progress) }
        } catch (e: Exception) {
            if (!snap.reader.stopped) throw e
            store.awaitRemoved(id)
            throw StoppedByDelete()
        }
    }

    // A storage error here (reading, or muting) is the phone's, not the network's: retryable, told apart.
    // Audio that cannot be muted as it is (no sync fit, no labels, no audio.flac) stays so: not retried.
    private fun snapshot(id: String): Snapshot? = try {
        snapshotLocked(id)
    } catch (e: MuteException) {
        throw UploadException(UploadException.Kind.CANNOT_MUTE, "the audio cannot be muted (${e.message})")
    } catch (e: IOException) {
        throw storage(e)
    }

    private fun snapshotLocked(id: String): Snapshot? = store.exclusive(id) {
        val dir = store.dir(id)
        Finalizer.repairLabels(dir)
        var meta = Finalizer.readJsonStrict(File(dir, Finalizer.META)) ?: throw UploadException(UploadException.Kind.NOT_FINALIZED, "the recording has no dataset files")
        if (RecordingStore.reviewStatus(meta) != RecordingStore.REVIEWED) return@exclusive null
        if (mute && AudioMute.apply(dir)) meta = Finalizer.readJsonStrict(File(dir, Finalizer.META)) ?: throw UploadException(UploadException.Kind.NOT_FINALIZED, "meta.json cannot be read")
        val listed = (meta["files"] as? JsonObject).orEmpty()
        val names = listOf(Finalizer.META, Finalizer.EVENTS) + Finalizer.DATASET_FILES.filter { it in listed }
        for (n in names) {
            val f = File(dir, n)
            if (!f.isFile) throw UploadException(UploadException.Kind.FILES_CHANGED, "$n is missing", retryable = false)
            if (f.length() > maxFileBytes) {
                throw UploadException(UploadException.Kind.TOO_LARGE, "$n is ${mb(f.length())} MB, over the data repo's limit of ${mb(maxFileBytes)} MB")
            }
        }
        val open = ArrayList<BlobFile>()
        try {
            for (n in names) open += BlobFile.open(File(dir, n))
            for (f in open) {
                if (f.name in listed && Finalizer.fileEntry(meta, f.name) != (f.sha256 to f.size)) {
                    throw UploadException(UploadException.Kind.FILES_CHANGED, "${f.name} does not match its sha256 in meta.json")
                }
            }
            Snapshot(open, meta, store.reader(id, api::abort))
        } catch (e: Throwable) {
            open.forEach { it.close() }
            throw e
        }
    }

    private fun push(id: String, snap: Snapshot, progress: (Long, Long) -> Unit): Result {
        val dir = store.dir(id)
        val path = UploadRecord.repoPath(id)
        var rec = UploadRecord.load(dir, repo, branch, path).target(repo, branch, path)
        fun save(r: UploadRecord) {
            rec = r
            try {
                UploadRecord.write(dir, r)
            } catch (e: IOException) {
                throw storage(e)
            }
        }

        // [commit] holds the recording as it is now
        fun finished(commit: String) =
            save(rec.copy(commit = commit, committed = snap.shas, blobs = rec.blobs + snap.shas, uploadedUtc = utc(), error = null, errorKind = null, waiting = false))

        val sent = ArrayList<String>()
        var forgotten = false

        fun send(files: List<BlobFile>) {
            val total = files.sumOf { it.size }
            if (total > sendLimit) {
                throw UploadException(UploadException.Kind.NEEDS_FOREGROUND, "${mb(total)} MB to send, more than an upload in the background may", retryable = true)
            }
            var done = 0L
            progress(0, total)
            for (f in files) {
                val sha = api.createBlob(f) { n -> progress(done + n, total) }
                if (sha != f.gitSha) {
                    throw UploadException(UploadException.Kind.BLOB_MISMATCH, "GitHub stored ${f.name} as $sha, not ${f.gitSha}", retryable = true)
                }
                done += f.size
                sent += f.name
                save(rec.copy(blobs = rec.blobs + (f.name to sha)))
            }
        }

        var head = branchHead()
        for (attempt in 1..MAX_TRIES) {
            val remote = api.listFolder(path, head)
            if (remote != null && remote[Finalizer.META] == snap.shas[Finalizer.META]) {
                finished(head)
                return Result.AlreadyThere(head)
            }
            // not over a meta.json this phone did not upload (last writer would win)
            if (remote != null && !overwrite && remote[Finalizer.META] !in setOfNotNull(rec.committed[Finalizer.META], rec.blobs[Finalizer.META])) {
                throw UploadException(UploadException.Kind.CONFLICT, "the data repo's copy changed since this phone uploaded it")
            }
            val baseTree = api.commitTree(head)
            fun inRemote(f: BlobFile) = remote?.get(f.name) == f.gitSha
            send(snap.files.filter { !inRemote(it) && rec.blobs[it.name] != it.gitSha })

            // only files this phone committed there: one a PC tool added to the folder stays
            val gone = (remote?.keys.orEmpty().intersect(rec.committed.keys) - snap.shas.keys).sorted()
            val entries = snap.files.map { TreeEntry("$path/${it.name}", it.gitSha) } + gone.map { TreeEntry("$path/$it", null) }
            val tree = try {
                api.createTree(baseTree, entries)
            } catch (e: GitHubException.Unprocessable) {
                // a blob upload.json remembers may be gone: GitHub drops blobs no commit holds
                val kept = snap.files.filter { !inRemote(it) && it.name !in sent }
                if (forgotten || kept.isEmpty()) throw e
                forgotten = true
                save(rec.copy(blobs = emptyMap()))
                send(kept)
                api.createTree(baseTree, entries)
            }
            val update = remote != null
            val message = if (update) "Update labels of $id" else "Add $id: ${summary(snap.meta)}"
            val commit = api.createCommit(message, tree, listOf(head))
            try {
                api.updateRef(branch, commit)
            } catch (e: GitHubException.Unprocessable) {
                // the branch moved: again on its new commit; it did not: GitHub will not move it (a protected branch)
                val now = branchHead()
                if (now == head) throw UploadException(UploadException.Kind.BRANCH_REFUSED, "GitHub refused to move the branch $branch (${e.message})")
                if (attempt == MAX_TRIES) break
                head = now
                continue
            }
            finished(commit)
            return Result.Committed(commit, sent.toList(), update, message)
        }
        throw UploadException(UploadException.Kind.BRANCH_MOVING, "the branch $branch kept moving ($MAX_TRIES tries)", retryable = true)
    }

    // The branch's commit; a missing branch is told apart from a missing repository.
    private fun branchHead(): String = try {
        api.headCommit(branch)
    } catch (e: GitHubException.Conflict) {
        throw UploadException(UploadException.Kind.REPO_EMPTY, "the repository is empty")
    } catch (e: GitHubException.NotFound) {
        api.repo()                                   // NotFound again: the repository itself
        throw UploadException(UploadException.Kind.BRANCH_MISSING, "the repository has no branch $branch")
    }

    private fun utc(): String = RecordingIds.utc(Instant.now())

    companion object {
        const val DEFAULT_BRANCH = "main"
        private const val MAX_TRIES = 5

        /** dbdata.validate's MAX_FILE_BYTES (95 MiB, under GitHub's 100 MB per file): larger files are not sent. */
        const val MAX_FILE_BYTES = 95L * 1024 * 1024

        private fun mb(b: Long): String = String.format(java.util.Locale.ROOT, "%.1f", b / 1e6)

        /** A storage error on the phone (reading, writing or muting the recording's files): retryable, told apart from the network. */
        fun storage(e: IOException) =
            UploadException(UploadException.Kind.STORAGE, "the recording's files could not be read or written (${e.message ?: e.javaClass.simpleName})", retryable = true)

        /** "wood/softwood 38 mm, drill, 42 s" from meta.json (session material, thickness, mode, duration). */
        fun summary(meta: JsonObject): String {
            val s = meta["session"] as? JsonObject
            val material = (s?.get("material") as? JsonPrimitive)?.content ?: "unknown"
            val thickness = ((s?.get("layers") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("thickness_mm")
                ?.let { (it as? JsonPrimitive)?.doubleOrNull }
                ?.let { " ${if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()} mm" }.orEmpty()
            val mode = (s?.get("mode") as? JsonPrimitive)?.content ?: "unknown"
            val seconds = (meta["duration_s"] as? JsonPrimitive)?.doubleOrNull?.roundToLong() ?: 0
            return "$material$thickness, $mode, $seconds s"
        }
    }
}
