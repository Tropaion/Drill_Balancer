package dev.drillbalancer.upload

import dev.drillbalancer.recorder.RecordingEntry
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.Finalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where a recording's upload is, as the list shows it. */
sealed interface UploadStage {
    data object None : UploadStage                              // not uploaded (or not reviewed yet: the local stage says so)
    data object Queued : UploadStage                            // waiting for its turn or the network
    data class Uploading(val pct: Int?) : UploadStage
    data object Done : UploadStage                              // the data repo holds these files
    data class Failed(val message: String, val kind: FailureKind? = null) : UploadStage   // Retry sends it again (or the settings fix it: kind.settings)
}

/** An upload WorkManager has: waiting, or running with its progress. */
sealed interface ActiveUpload {
    data object Queued : ActiveUpload
    data class Running(val pct: Int?) : ActiveUpload
}

/** Runs the uploads (WorkManager in the app: WorkUploadScheduler; a fake in tests). */
interface UploadScheduler {
    /** Recording id to its upload that is waiting or running. */
    val active: Flow<Map<String, ActiveUpload>>

    /**
     * Uploads [id] (a worker that reads the files when it runs). One already waiting is
     * replaced; one running gets this one after it, so a Save during an upload is uploaded too.
     * [delayMs]: not before then (a rate limit). [overwrite]: over a copy in the data repo that
     * changed since this phone uploaded it (the user's Retry). [restart]: one running is
     * replaced too, stopped (Wi-Fi only switched on; it resumes from upload.json).
     */
    suspend fun enqueue(id: String, wifiOnly: Boolean, delayMs: Long = 0, overwrite: Boolean = false, restart: Boolean = false)

    /** Stops every upload (the token was rejected). */
    fun cancelAll()
}

/** What the phone knows of [dir]'s upload without asking GitHub ([waiting]: for the app to be open, UploadRecord.waiting; [kind]: the error's). */
data class LocalUpload(val done: Boolean = false, val error: String? = null, val waiting: Boolean = false, val kind: FailureKind? = null) {
    companion object {
        /**
         * [dir]'s upload.json against its files: done when its last commit to [repo] holds the
         * meta.json there is now (a Save changes meta.json: review revision; a mute changes
         * meta.files).
         */
        fun of(dir: File, repo: String): LocalUpload {
            val r = UploadRecord.read(dir) ?: return LocalUpload()
            val meta = File(dir, Finalizer.META)
            val committed = r.committed[Finalizer.META]
            val done = r.repo == repo && r.branch == DatasetUploader.DEFAULT_BRANCH && r.commit != null && committed != null && meta.isFile &&
                try {
                    GitHash.blobSha(meta.readBytes()) == committed
                } catch (_: IOException) {
                    false
                }
            return LocalUpload(done, r.error, !done && r.waiting, r.failure)
        }
    }
}

/** Counts of the reviewed recordings by upload stage (the upload settings screen). */
data class UploadCounts(
    val queued: Int = 0,
    val uploading: Int = 0,
    val failed: Int = 0,
    val done: Int = 0,
    val notQueued: Int = 0,          // reviewed, not uploaded, not in the queue (Upload now sends them)
    val needsReview: Int = 0,
) {
    /** What Upload now has to send: the reviewed recordings not uploaded and not uploading now. */
    val toSend: Int get() = queued + failed + notQueued
}

/**
 * The app's upload queue over [scheduler]: a Save enqueues the recording, Upload now and a
 * new token every reviewed recording not uploaded yet, the app's start into view the ones that
 * wait for it ([appVisible]). Stages: a recording whose labels are not reviewed waits (None);
 * an upload waiting or running shows as such; then upload.json says Done, Failed or waiting
 * for the app (Queued); a rejected token fails all that are not done until it is replaced.
 */
class UploadQueue(
    private val store: RecordingStore,
    private val scheduler: UploadScheduler,
    private val prefs: UploadPrefs,
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val active: Flow<Map<String, ActiveUpload>> get() = scheduler.active

    /** A review of [id] was saved (reviewed): it goes into the queue. */
    fun saved(id: String) {
        scope.launch { enqueue(id) }
    }

    /** [id] into the queue; [overwrite]: the user's Retry after a CONFLICT (its message says so), over the data repo's copy that changed since this phone uploaded it. */
    suspend fun enqueue(id: String, overwrite: Boolean = false) = scheduler.enqueue(id, prefs.current().wifiOnly, overwrite = overwrite)

    /** Every reviewed recording not uploaded to the current repository yet; returns how many. */
    suspend fun uploadNow(): Int {
        val s = prefs.current()
        val ids = withContext(io) { local(store.list(), s.repo).filterValues { !it.done }.keys }
        for (id in ids) scheduler.enqueue(id, s.wifiOnly)
        return ids.size
    }

    /**
     * The uploads enqueued again with the settings of now (Wi-Fi only changed): every one, a
     * running one (and the one appended after it, a Save during it) stopped and replaced too, by
     * one under the new rule. Switched on, none goes on over mobile data; switched off, neither the
     * running one (its next run) nor the one after it waits for Wi-Fi. A stopped one resumes from
     * upload.json (the blob in flight sent again).
     */
    suspend fun requeue() {
        val wifi = prefs.current().wifiOnly
        for (id in scheduler.active.first().keys.sorted()) scheduler.enqueue(id, wifi, restart = true)
    }

    /**
     * The app came into view: the recordings whose upload waits for it (too much to send
     * without the foreground service, which a worker started in the background may not have)
     * are enqueued; they run now, with it.
     */
    fun appVisible() {
        scope.launch {
            val repo = prefs.current().repo
            val ids = withContext(io) {
                try {
                    // only a record that waits can be: the others need no hash of meta.json
                    val waits = store.list().filter { UploadRecord.read(store.dir(it.journal.recordingId))?.waiting == true }
                    local(waits, repo).filterValues { it.waiting }.keys
                } catch (_: IOException) {
                    emptySet()
                }
            }
            if (ids.isEmpty() || tokens.state.first().rejected) return@launch
            val active = scheduler.active.first()
            val wifi = prefs.current().wifiOnly
            for (id in ids) if (active[id] == null) scheduler.enqueue(id, wifi)
        }
    }

    /** Each entry's local upload state (blocking: reads upload.json and hashes meta.json). */
    fun local(entries: List<RecordingEntry>, repo: String): Map<String, LocalUpload> =
        entries.filter { it.reviewStatus == RecordingStore.REVIEWED }.associate { e ->
            e.journal.recordingId to LocalUpload.of(store.dir(e.journal.recordingId), repo)
        }

    companion object {
        const val TOKEN_REJECTED = "Token rejected. Replace the token in Upload settings."
        const val NO_TOKEN = "No upload token. Set one in Upload settings."

        /** The list's stage of a recording ([reviewStatus] meta.review.status; null: not finalized). */
        fun stage(reviewStatus: String?, local: LocalUpload?, active: ActiveUpload?, rejected: Boolean): UploadStage = when {
            active is ActiveUpload.Running -> UploadStage.Uploading(active.pct)
            active is ActiveUpload.Queued -> UploadStage.Queued
            reviewStatus != RecordingStore.REVIEWED -> UploadStage.None
            local?.done == true -> UploadStage.Done
            rejected -> UploadStage.Failed(TOKEN_REJECTED, FailureKind.TOKEN_REJECTED)
            local?.waiting == true -> UploadStage.Queued
            local?.error != null -> UploadStage.Failed(local.error, local.kind)
            else -> UploadStage.None
        }

        fun counts(entries: List<RecordingEntry>, local: Map<String, LocalUpload>, active: Map<String, ActiveUpload>, rejected: Boolean): UploadCounts {
            var c = UploadCounts()
            for (e in entries) {
                val id = e.journal.recordingId
                if (e.reviewStatus == RecordingStore.AUTO_ONLY) {
                    c = c.copy(needsReview = c.needsReview + 1)
                    continue
                }
                c = when (stage(e.reviewStatus, local[id], active[id], rejected)) {
                    is UploadStage.Queued -> c.copy(queued = c.queued + 1)
                    is UploadStage.Uploading -> c.copy(uploading = c.uploading + 1)
                    is UploadStage.Failed -> c.copy(failed = c.failed + 1)
                    is UploadStage.Done -> c.copy(done = c.done + 1)
                    is UploadStage.None -> if (e.reviewStatus == RecordingStore.REVIEWED) c.copy(notQueued = c.notQueued + 1) else c
                }
            }
            return c
        }
    }
}

/** The upload parts the training screens use (DrillBalancerApp); [api] makes a client for owner, name, token. */
class UploadServices(
    val queue: UploadQueue,
    val prefs: UploadPrefs,
    val tokens: TokenStore,
    val api: GitHubApiFactory = ::HttpGitHubApi,
)
