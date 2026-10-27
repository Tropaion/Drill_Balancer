package dev.drillbalancer.upload

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.drillbalancer.MainActivity
import dev.drillbalancer.R
import dev.drillbalancer.recorder.RecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What an upload worker needs (DrillBalancerApp is the [Provider]; tests pass their own). */
class UploadDeps(
    val store: RecordingStore,
    val prefs: UploadPrefs,
    val tokens: TokenStore,
    val scheduler: UploadScheduler,
    val api: GitHubApiFactory = ::HttpGitHubApi,
    val backgroundMaxBytes: Long = UploadWorker.BACKGROUND_MAX_BYTES,
    /** Milliseconds of a monotonic clock: the progress notification's throttle. */
    val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    interface Provider {
        val uploadDeps: UploadDeps
    }
}

/**
 * Uploads one recording (input [KEY_ID]; [KEY_OVERWRITE]: the user's Retry, over a copy the data
 * repo changed) with DatasetUploader, as foreground work (type dataSync, a progress
 * notification) so an upload of tens of MB is not stopped after WorkManager's 10 minutes.
 *
 * One upload at a time in the process ([UPLOADS]): the others wait (shown as Queued, their
 * notification says waiting), so a backlog sent by Upload now neither trips GitHub's secondary
 * rate limit (content-creating requests per minute) nor races itself on the branch (422 redos),
 * and an upload WorkManager stopped has ended before the next one of it starts. The settings
 * (repository, mute, keep local) and the token are read after that wait: the ones of now.
 *
 * When the foreground service may not start (a worker started with the app in the background,
 * Android 12+), it goes on as plain work only while there is little to send (at most
 * [BACKGROUND_MAX_BYTES] of blobs: a big blob cut short is sent again from its first byte);
 * else upload.json says it waits for the app, and UploadQueue.appVisible sends it when the app
 * comes into view.
 *
 * Results: done (and stream.dbr deleted unless "keep local copies"); a network, storage or
 * server error is retried with WorkManager's exponential backoff, at most [MAX_ATTEMPTS] runs; a
 * rate limit is waited out (here up to [WAIT_HERE_MS], else by a delayed upload after this one);
 * a rejected token (401) stops the whole queue until it is replaced (unless it was replaced
 * meanwhile: then once more with the new one); anything else ends with its reason in upload.json,
 * which the list shows with Retry (its [FailureKind] beside it). Every end but a retry is Result.success (the reason in
 * upload.json and the output data): a failure would fail the upload appended after this one
 * without running it (a Save during this upload) and that Save's state would never be sent.
 * A stopped worker's HTTP request is disconnected ([stoppable]).
 */
class UploadWorker(context: Context, params: WorkerParameters, private val deps: UploadDeps) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, (context.applicationContext as UploadDeps.Provider).uploadDeps)

    private val recordingId: String? get() = inputData.getString(KEY_ID)

    // this run is not the last WorkManager gives it
    private val mayRetry: Boolean get() = runAttemptCount + 1 < MAX_ATTEMPTS

    override suspend fun getForegroundInfo(): ForegroundInfo = UploadNotifications.info(applicationContext, recordingId.orEmpty(), null)

    override suspend fun doWork(): Result {
        val id = recordingId ?: return Result.failure()
        var held = UPLOADS.tryLock()
        try {
            val foreground = try {
                setForeground(UploadNotifications.info(applicationContext, id, null, waiting = !held))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "uploading without a foreground service: ${e.javaClass.simpleName}")
                false
            }
            if (!held) {
                setProgress(workDataOf(KEY_WAITING to true))
                UPLOADS.lock()
                held = true
                setProgress(Data.EMPTY)
                if (foreground) setForegroundAsync(UploadNotifications.info(applicationContext, id, null))
            }
            // the settings and the token of now, after the wait (the uploads before it can take minutes)
            val settings = deps.prefs.current()
            val repo = HttpGitHubApi.parseRepo(settings.repo)
                ?: return fail(id, settings, "The repository \"${settings.repo}\" is not owner/name. Fix it in Upload settings.", FailureKind.BAD_REPO)
            if (deps.tokens.state.first().rejected) return fail(id, settings, UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED)
            val token = when (val t = deps.tokens.read()) {
                TokenRead.None -> return fail(id, settings, UploadQueue.NO_TOKEN, FailureKind.NO_TOKEN)
                TokenRead.Unreadable -> return if (mayRetry) Result.retry() else fail(id, settings, TOKEN_UNREADABLE, FailureKind.TOKEN_UNREADABLE)
                is TokenRead.Ok -> t
            }
            return upload(id, settings, repo, token, foreground)
        } finally {
            if (held) UPLOADS.unlock()
        }
    }

    // the progress notification's throttle lives as long as the upload: a held-back update never comes after it
    private suspend fun upload(id: String, settings: UploadSettings, repo: Pair<String, String>, first: TokenRead.Ok, foreground: Boolean): Result =
        coroutineScope {
            val notify = UploadNotifications.Throttle(this, deps.clock) { pct -> setForegroundAsync(UploadNotifications.info(applicationContext, id, pct)) }
            try {
                send(id, settings, repo, first, foreground, notify)
            } finally {
                notify.cancel()
            }
        }

    private suspend fun send(
        id: String,
        settings: UploadSettings,
        repo: Pair<String, String>,
        first: TokenRead.Ok,
        foreground: Boolean,
        notify: UploadNotifications.Throttle,
    ): Result {
        val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)
        var token = first
        var api = deps.api(repo.first, repo.second, token.token)
        fun uploader() = DatasetUploader(
            api, deps.store, settings.repo, BRANCH, settings.muteAudio,
            overwrite = overwrite, sendLimit = if (foreground) Long.MAX_VALUE else deps.backgroundMaxBytes,
        )
        var uploader = uploader()
        var waits = 0
        var lastPct = -1
        var swapped = false
        while (true) {
            try {
                val result = stoppable(api) {
                    uploader.upload(id) { sent, total ->
                        val pct = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 99) else 0
                        val changed = pct != lastPct
                        if (changed) {
                            lastPct = pct
                            setProgressAsync(workDataOf(KEY_PCT to pct))
                        }
                        // Android drops a package's notification updates beyond 5 a second
                        if (foreground && (changed || sent >= total)) notify.offer(pct, final = sent >= total)
                    }
                }
                if (result !is DatasetUploader.Result.NotReviewed) finished(id, settings)
                return Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException.RateLimited) {
                if (e.waitMs <= WAIT_HERE_MS && waits < 3) {
                    waits++
                    delay(e.waitMs)
                    continue
                }
                deps.scheduler.enqueue(id, settings.wifiOnly, e.waitMs, overwrite)
                return Result.success()
            } catch (e: GitHubException.Unauthorized) {
                if (deps.tokens.reject(token.id)) {
                    val r = fail(id, settings, UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED)
                    deps.scheduler.cancelAll()
                    return r
                }
                // the token was replaced while this upload used the one before: once more with the new one
                when (val now = deps.tokens.read()) {
                    TokenRead.None -> return fail(id, settings, UploadQueue.NO_TOKEN, FailureKind.NO_TOKEN)
                    is TokenRead.Ok -> if (!swapped) {
                        swapped = true
                        token = now
                        api = deps.api(repo.first, repo.second, token.token)
                        uploader = uploader()
                        continue
                    }
                    TokenRead.Unreadable -> Unit
                }
                return if (mayRetry) Result.retry() else fail(id, settings, UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED)
            } catch (e: UploadException) {
                if (e.kind == UploadException.Kind.NEEDS_FOREGROUND) return waitForApp(id, settings)
                if (e.retryable && mayRetry) return Result.retry()
                return fail(id, settings, UploadMessages.of(e, settings.repo, BRANCH, tries = if (e.retryable) MAX_ATTEMPTS else 0), UploadMessages.kind(e))
            } catch (e: Exception) {
                val retry = e is GitHubException.Server || e is IOException       // a GitHubException is no IOException
                if (retry && mayRetry) return Result.retry()
                if (e !is IOException && e !is GitHubException) Log.e(TAG, "upload of $id failed", e)
                return fail(id, settings, UploadMessages.of(e, settings.repo, BRANCH, tries = if (retry) MAX_ATTEMPTS else 0), UploadMessages.kind(e))
            }
        }
    }

    // Uploaded: the journal goes unless it is to be kept (the dataset files stay: labels can still be edited).
    private fun finished(id: String, settings: UploadSettings) {
        if (!settings.keepLocal) deps.store.exclusive(id) { File(deps.store.dir(id), RecordingStore.STREAM).delete() }
        deps.store.changed(id)
    }

    private fun fail(id: String, settings: UploadSettings, message: String, kind: FailureKind): Result {
        record(id, settings) { it.copy(error = message, errorKind = kind.key, waiting = false) }
        return Result.success(workDataOf(KEY_ERROR to message))
    }

    // Too much to send without the foreground service: when the app is open (UploadQueue.appVisible).
    private fun waitForApp(id: String, settings: UploadSettings): Result {
        Log.i(TAG, "$id waits for the app to be open")
        record(id, settings) { it.copy(error = null, errorKind = null, waiting = true) }
        return Result.success()
    }

    private fun record(id: String, settings: UploadSettings, change: (UploadRecord) -> UploadRecord) {
        try {
            val dir = deps.store.dir(id)
            if (dir.isDirectory) {
                UploadRecord.write(dir, change(UploadRecord.load(dir, settings.repo, BRANCH, UploadRecord.repoPath(id))))
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not keep the upload state of $id: $e")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a recording id: $id")
        }
        deps.store.changed(id)
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_ID = "recording_id"
        const val KEY_OVERWRITE = "overwrite"
        const val KEY_PCT = "pct"
        const val KEY_WAITING = "waiting"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 8
        const val WAIT_HERE_MS = 60_000L
        private const val BRANCH = DatasetUploader.DEFAULT_BRANCH

        /** Blob bytes a worker without its foreground service sends (about 2 minutes at 2 Mbit/s with base64's third). */
        const val BACKGROUND_MAX_BYTES = 24_000_000L
        const val TOKEN_UNREADABLE = "The phone could not read the upload token (its keystore failed). Retry later; if it keeps failing, set the token again."

        /** One upload at a time in the process. */
        internal val UPLOADS = Mutex()
    }
}

/**
 * Runs the blocking [block] (a DatasetUploader.upload on [api]) on the IO dispatcher so that a
 * cancellation (WorkManager stopped the worker) ends it: the thread is interrupted
 * (runInterruptible: file reads, the next request) and [api]'s request in flight is
 * disconnected (a blocked socket read or write ignores the interrupt). Returns only when
 * [block] has; once stopped it ends in CancellationException whatever [block] threw.
 */
internal suspend fun <T> stoppable(api: GitHubApi, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) api.abort()
        }
    }
    try {
        withContext(Dispatchers.IO) { runInterruptible(block = block) }
    } catch (e: Throwable) {
        // stopped: whatever the aborted request threw (a SocketException) is the stop, never an
        // upload error the worker would write to upload.json
        ensureActive()
        throw e
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}

/** The words of an upload failure for the list (plain language, never the token). */
object UploadMessages {
    fun of(e: Throwable, repo: String, branch: String, tries: Int = 0): String = when (e) {
        is GitHubException.Unauthorized -> UploadQueue.TOKEN_REJECTED
        is GitHubException.NotFound -> "Repo not found or no access: check $repo and the token's repository access."
        is GitHubException.Forbidden -> "GitHub refused it: the token may not write to $repo. Give it Contents read and write (${e.message})."
        is GitHubException.Conflict -> "GitHub refused it: ${e.message}"
        is GitHubException.Unprocessable -> "GitHub refused the commit: ${e.message}"
        is GitHubException.RateLimited -> "GitHub's rate limit: try again later."
        is GitHubException.Server -> "GitHub had a problem (HTTP ${e.status}), $tries tries."
        is GitHubException.Other -> "GitHub answered HTTP ${e.status}: ${e.message}"
        is UploadException -> when (e.kind) {
            UploadException.Kind.REPO_EMPTY -> "The repository $repo is empty: push the dataset template from the PC first (bootstrap_repo.py)."
            UploadException.Kind.BRANCH_MISSING -> "The repository $repo has no branch $branch."
            UploadException.Kind.TOO_LARGE -> "Not uploaded: ${e.message}."
            UploadException.Kind.FILES_CHANGED -> "The recording's files do not match its meta.json (${e.message})."
            UploadException.Kind.NOT_FINALIZED -> "The recording has no dataset files yet."
            UploadException.Kind.STORAGE -> "Not uploaded: ${e.message}."
            UploadException.Kind.BRANCH_MOVING, UploadException.Kind.BLOB_MISMATCH -> "The upload did not go through (${e.message}). Try again."
            UploadException.Kind.CONFLICT ->
                "Not uploaded: the data repo's copy was changed since this phone uploaded it (labels edited on the PC?). Retry upload overwrites the data repo's labels with the phone's."
            UploadException.Kind.NEEDS_FOREGROUND -> "Waiting for the app to be open: too much to upload in the background."
            UploadException.Kind.CANNOT_MUTE -> "Not uploaded: ${e.message}."
            UploadException.Kind.BRANCH_REFUSED -> "Not uploaded: ${e.message}. Is the branch protected?"
        }
        is IOException -> "No connection to GitHub" + (if (tries > 0) " after $tries tries" else "") + ". Check the network, then retry."
        else -> "The upload stopped (${e.javaClass.simpleName})."
    }

    /** The [FailureKind] upload.json keeps for [e] (the list's Retry or Upload settings). */
    fun kind(e: Throwable): FailureKind = when (e) {
        is GitHubException.Unauthorized -> FailureKind.TOKEN_REJECTED
        is GitHubException.NotFound -> FailureKind.NOT_FOUND
        is GitHubException.Forbidden -> FailureKind.FORBIDDEN
        is GitHubException.RateLimited -> FailureKind.RATE_LIMITED
        is GitHubException.Server -> FailureKind.SERVER
        is GitHubException -> FailureKind.REFUSED
        is UploadException -> when (e.kind) {
            UploadException.Kind.CONFLICT -> FailureKind.CONFLICT
            UploadException.Kind.STORAGE -> FailureKind.STORAGE
            UploadException.Kind.REPO_EMPTY, UploadException.Kind.BRANCH_MISSING -> FailureKind.REPO_SETUP
            UploadException.Kind.BRANCH_REFUSED -> FailureKind.REFUSED
            UploadException.Kind.TOO_LARGE, UploadException.Kind.FILES_CHANGED, UploadException.Kind.NOT_FINALIZED, UploadException.Kind.CANNOT_MUTE -> FailureKind.RECORDING
            UploadException.Kind.BRANCH_MOVING, UploadException.Kind.BLOB_MISMATCH, UploadException.Kind.NEEDS_FOREGROUND -> FailureKind.OTHER
        }
        is IOException -> FailureKind.NETWORK
        else -> FailureKind.OTHER
    }
}

/** The upload's foreground notification (channel "uploads"). */
object UploadNotifications {
    private const val CHANNEL = "uploads"

    /**
     * The progress percents [show]n: at most one per [intervalMs] of [clock] (milliseconds, the
     * clock [scope]'s delays run on), the latest one offered meanwhile shown when the interval
     * is over (a pause in the progress leaves no older percent shown), the final one at once;
     * each percent once. [offer] may come from any thread; [cancel] drops one held back.
     */
    class Throttle(
        private val scope: CoroutineScope,
        private val clock: () -> Long,
        private val intervalMs: Long = INTERVAL_MS,
        private val show: (pct: Int) -> Unit,
    ) {
        private val lock = Any()
        private var last: Long? = null
        private var shown: Int? = null
        private var pending: Int? = null
        private var trailing: Job? = null

        fun offer(pct: Int, final: Boolean = false) = synchronized(lock) {
            val now = clock()
            val prev = last
            if (final || prev == null || now - prev >= intervalMs) {
                drop()
                emit(pct, now)
                return@synchronized
            }
            pending = pct
            if (trailing == null) {
                trailing = scope.launch {
                    delay(prev + intervalMs - now)
                    synchronized(lock) {
                        if (trailing !== coroutineContext[Job]) return@launch      // replaced meanwhile
                        trailing = null
                        pending?.let { emit(it, clock()) }
                        pending = null
                    }
                }
            }
        }

        fun cancel() = synchronized(lock) { drop() }

        private fun drop() {
            trailing?.cancel()
            trailing = null
            pending = null
        }

        private fun emit(pct: Int, now: Long) {
            if (pct == shown) return
            shown = pct
            last = now
            show(pct)
        }
    }

    /** About one progress update a second: Android sheds a package's updates beyond 5 a second. */
    const val INTERVAL_MS = 1000L

    /** [waiting]: for the upload before it (one at a time). */
    fun info(context: Context, id: String, pct: Int?, waiting: Boolean = false): ForegroundInfo {
        if (!channelMade) createChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(if (waiting) "Waiting to upload a recording" else "Uploading a recording")
            .setContentText(if (pct != null) "$id · $pct %" else id)
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .build()
        val nid = NOTIFICATION_BASE + Math.floorMod(id.hashCode(), 10_000)
        // the type is API 29+; dataSync needs FOREGROUND_SERVICE_DATA_SYNC (API 34+)
        @SuppressLint("InlinedApi")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nid, n)
        }
        return info
    }

    // Made once per process: info runs on every percent of an upload, each a call to the system.
    @Volatile private var channelMade = false
    @Volatile private var content: PendingIntent? = null

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a recording is uploaded to the data repository"
                },
            )
        }
        channelMade = true
    }

    private fun openApp(context: Context): PendingIntent = content ?: PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    ).also { content = it }

    private const val NOTIFICATION_BASE = 1000
}

/**
 * [UploadScheduler] on WorkManager: one unique work per recording ("upload:<id>", tags
 * [TAG] and "rec:<id>"), expedited where the quota allows, exponential backoff from 30 s, a
 * network constraint UNMETERED (Wi-Fi only) or CONNECTED. A new upload of a recording whose
 * upload is waiting replaces it (REPLACE: the new one reads the files when it runs, and takes
 * the settings of now); of one that runs, it is appended (APPEND_OR_REPLACE), so a Save during
 * an upload leads to one more upload of the new state: the worker never ends in failure
 * (UploadWorker), so the appended one runs whatever became of the first. restart: REPLACE even
 * while one runs (it is stopped). What REPLACE drops is carried over: an overwrite (tag
 * [OVERWRITE], the user's Retry after a conflict) stays one, and a rate limit's wait (tag
 * not_before:<epoch ms>) is not cut short.
 */
class WorkUploadScheduler(context: Context) : UploadScheduler {
    private val wm = WorkManager.getInstance(context.applicationContext)

    override val active: Flow<Map<String, ActiveUpload>> = wm.getWorkInfosByTagFlow(TAG).map(::activeOf)

    override suspend fun enqueue(id: String, wifiOnly: Boolean, delayMs: Long, overwrite: Boolean, restart: Boolean) {
        val name = name(id)
        val live = withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork(name).get() }.filter { !it.state.isFinished }
        if (!restart && live.any { it.state == WorkInfo.State.RUNNING }) {
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request(id, wifiOnly, delayMs, overwrite))
            return
        }
        val now = System.currentTimeMillis()
        val notBefore = (live.mapNotNull { notBefore(it.tags) } + (now + delayMs)).max()
        val keep = overwrite || live.any { OVERWRITE in it.tags }
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request(id, wifiOnly, notBefore - now, keep, now))
    }

    override fun cancelAll() {
        wm.cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "upload"
        const val OVERWRITE = "overwrite"
        private const val REC = "rec:"
        private const val NOT_BEFORE = "not_before:"

        fun name(id: String) = "upload:$id"

        /** [delayMs]: not before then (from [nowMs]), tagged so a REPLACE keeps it. */
        fun request(id: String, wifiOnly: Boolean, delayMs: Long = 0, overwrite: Boolean = false, nowMs: Long = System.currentTimeMillis()): OneTimeWorkRequest {
            val b = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(UploadWorker.KEY_ID, id).putBoolean(UploadWorker.KEY_OVERWRITE, overwrite).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(REC + id)
            if (overwrite) b.addTag(OVERWRITE)
            // expedited work cannot wait: a delayed one (a rate limit) is plain work
            if (delayMs > 0) {
                b.setInitialDelay(delayMs, TimeUnit.MILLISECONDS).addTag(NOT_BEFORE + (nowMs + delayMs))
            } else {
                b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return b.build()
        }

        /** When a request's rate-limit wait ends (epoch ms), from its [tags]; null without one. */
        fun notBefore(tags: Set<String>): Long? = tags.firstNotNullOfOrNull { if (it.startsWith(NOT_BEFORE)) it.removePrefix(NOT_BEFORE).toLongOrNull() else null }

        /** The waiting and running uploads among [infos] by recording (running wins; one running that waits for the upload before it is waiting). */
        fun activeOf(infos: List<WorkInfo>): Map<String, ActiveUpload> {
            val out = HashMap<String, ActiveUpload>()
            for (w in infos) {
                val id = w.tags.firstOrNull { it.startsWith(REC) }?.removePrefix(REC) ?: continue
                val a = when (w.state) {
                    WorkInfo.State.RUNNING ->
                        if (w.progress.getBoolean(UploadWorker.KEY_WAITING, false)) ActiveUpload.Queued else ActiveUpload.Running(w.progress.getInt(UploadWorker.KEY_PCT, -1).takeIf { it >= 0 })
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ActiveUpload.Queued
                    else -> continue
                }
                if (out[id] !is ActiveUpload.Running) out[id] = a
            }
            return out
        }
    }
}
