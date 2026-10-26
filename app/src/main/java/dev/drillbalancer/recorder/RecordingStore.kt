package dev.drillbalancer.recorder

import android.annotation.SuppressLint
import dev.drillbalancer.recorder.export.FinalizeException
import dev.drillbalancer.recorder.export.FinalizeResult
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.stream.StreamDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Where a take's journal stands. */
@Serializable
enum class JournalState {
    @SerialName("recording") RECORDING,       // being written (or the app died while it was)
    @SerialName("complete") COMPLETE,         // the take's END is in the journal
    @SerialName("interrupted") INTERRUPTED,   // no END: the connection or the app went away
}

/** What the app knew about the phone and itself (meta.app). */
@Serializable
data class AppInfo(
    val version: String,
    @SerialName("phone_model") val phoneModel: String,
    @SerialName("android_sdk") val androidSdk: Int,
)

/** TAKE_START's arguments as sent. */
@Serializable
data class TakeSettings(
    val profile: String,
    @SerialName("mic_mask") val micMask: Int,
    @SerialName("gain_db") val gainDb: Int,
    @SerialName("max_s") val maxS: Int,
)

/** The stream counters when the journal was closed. */
@Serializable
data class JournalStats(
    val records: Long = 0,
    @SerialName("missing_records") val missingRecords: Long = 0,
    @SerialName("imu_samples") val imuSamples: Long = 0,
    @SerialName("imu_gap_samples") val imuGapSamples: Long = 0,
    @SerialName("audio_frames") val audioFrames: Long = 0,
    @SerialName("audio_gap_frames") val audioGapFrames: Long = 0,
    @SerialName("skipped_bytes") val skippedBytes: Long = 0,
    @SerialName("device_us") val deviceUs: Long = 0,
)

/**
 * take.json next to a take's stream.dbr: what the finalizer (milestone 6) needs besides the
 * stream to write meta.json, events.json and the data files. The stream holds the device
 * side (SESSION, records, END); this holds the phone side: ids, the session labels, the
 * phone's clock at the start, the app, how the take ended as the app saw it.
 */
@Serializable
data class TakeJournal(
    val schema: String = SCHEMA,
    @SerialName("recording_id") val recordingId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("take_id") val takeId: Long,
    val reviewer: String,                                   // install4, meta.review.reviewer
    val state: JournalState = JournalState.RECORDING,
    @SerialName("started_utc") val startedUtc: String,      // phone clock when the take's SESSION came (TAKE_START's until then)
    val setup: SessionSetup,
    val take: TakeSettings,
    val app: AppInfo,
    val link: String,                                       // "ap" or "lan"
    @SerialName("taxonomy_version") val taxonomyVersion: Int = Taxonomy.VERSION,
    @SerialName("ended_utc") val endedUtc: String? = null,
    @SerialName("end_reason") val endReason: String? = null,   // dbrec REASONS name from END, else unknown / client_lost
    val note: String? = null,                               // how the app ended an interrupted take
    val bytes: Long = 0,
    val stats: JournalStats? = null,
    @SerialName("finalize_error") val finalizeError: String? = null,   // why no dataset files could be made from it
    // finalizeError is not the take's (the app ran out of memory): the start does not try it
    // again (it would fail again, at every start), Try again in the review does
    @SerialName("finalize_retry") val finalizeRetry: Boolean = false,
) {
    companion object {
        const val SCHEMA = "drillbalancer.take/1"
    }
}

/**
 * Where a recording is on the phone. RECORDING: its take runs (or the app died while it ran,
 * until [RecordingStore.recover]); INTERRUPTED: no dataset files could be made from it
 * (TakeJournal.finalizeError, or a storage error that [RecordingStore.retry] tries again: a
 * journal that could not be closed too), or an interrupted take not finalized yet; NEEDS_REVIEW: its
 * dataset files hold the detector's labels only (meta.review auto_only), or a complete take is
 * about to be finalized; SAVED: its labels were reviewed (or the take rejected).
 */
enum class RecordingStage { RECORDING, INTERRUPTED, NEEDS_REVIEW, SAVED }

/**
 * One recording folder as the list shows it: [bytes] of all its files, [reviewStatus]
 * meta.review.status (null: not finalized, no meta.json), [storageError] why the last try to
 * make its dataset files (or to close its journal) failed on storage (RecordingStore.storageError).
 */
data class RecordingEntry(
    val journal: TakeJournal,
    val streamBytes: Long,
    val bytes: Long = streamBytes,
    val reviewStatus: String? = null,
    val storageError: String? = null,
) {
    /** Its dataset files are there (meta.json, which the finalizer writes last). */
    val finalized: Boolean get() = reviewStatus != null
    val stage: RecordingStage get() = RecordingStore.stageOf(journal, reviewStatus, storageError)
}

/**
 * Labels a recording the finalizer just wrote (the auto-detector, which replaces events.json
 * and meta's derived and review through Finalizer.writeLabels). It runs after every finalize,
 * also one that kept the labels written before (FinalizeResult.labelsKept: nothing to do). Its
 * failures are its own: the recording stays with the empty label set.
 */
fun interface Labeler {
    fun label(dir: File, result: FinalizeResult)
}

/**
 * The recordings on the phone: filesDir/recordings/<recording_id>/ with the journal
 * stream.dbr (the raw TCP bytes of the take, unchanged; the source of truth) and take.json,
 * and once the take is finalized ([finalize]) its dataset files (Finalizer.DATASET_FILES).
 * Every change to a finished take's folder runs under that folder's lock ([exclusive]; [finalize]
 * holds it too) or goes through [write] / [changed], so [list] keeps what it read of each folder
 * until then. Blocking file I/O: call it off the main thread.
 */
class RecordingStore(
    val root: File,
    // The app passes StorageManager's allocatable bytes (cached files Android would clear count).
    private val freeSpace: (File) -> Long = ::usableSpace,
    // How a journal goes to storage (tests count the calls).
    private val syncFile: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val finalizer: Finalizer = Finalizer(),
    // Runs after each finalize that wrote the files (the auto-detector).
    private val labeler: Labeler? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val finalizing = Any()                                   // one finalize (and its labelling) at a time
    private val folderLocks = ConcurrentHashMap<String, Any>()       // per recording: its files written by one at a time
    private val _changes = MutableStateFlow(0)

    @Volatile
    private var preparingId: String? = null
    private val storageErrors = ConcurrentHashMap<String, String>()
    private val unclosed = ConcurrentHashMap<String, TakeJournal>()  // how a take ended, when its journal could not be closed

    // What list() read of each finished take's folder (not one being recorded: its stream grows),
    // until [forget]; a read that overlapped a forget is not kept ([generations]).
    private class Folder(val journal: TakeJournal, val streamBytes: Long, val bytes: Long, val reviewStatus: String?)
    private val folders = ConcurrentHashMap<String, Folder>()
    private val generations = ConcurrentHashMap<String, Long>()

    /** How many folders [list] read from storage (not from what it kept): tests. */
    internal val folderReads = AtomicInteger()

    /** Counts the finalizations (and their failures) and saved reviews, so a list can reload. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** [id]'s files changed outside [finalize] (a review saved its labels): [list] reads its folder again, [changes] counts it. */
    fun changed(id: String) {
        forget(id)
        _changes.update { it + 1 }
    }

    /** [id] is being finalized and labelled ([finalize]): its files are not ready to be read yet. */
    fun preparing(id: String): Boolean = preparingId == id

    /**
     * Runs [block] while [id]'s files are not finalized or labelled (the review labelling or
     * saving the recording); other recordings' finalizes go on meanwhile. [list] reads the folder
     * again afterwards.
     */
    fun <T> exclusive(id: String, block: () -> T): T = try {
        synchronized(folderLock(id), block)
    } finally {
        forget(id)
    }

    /** [id]'s files changed: [list] reads its folder again. */
    private fun forget(id: String) {
        generations.merge(id, 1L, Long::plus)
        folders.remove(id)
    }

    private fun folderLock(id: String): Any = folderLocks.computeIfAbsent(id) { Any() }

    /** Why the last try to make [id]'s dataset files failed on storage (null: it did not); [retry] tries again. */
    fun storageError(id: String): String? = storageErrors[id]

    /**
     * A storage error on [id] outside [finalize]: kept for [storageError]. [ended]: the journal
     * as the take ended, when it could not be closed (take.json still says recording); [retry]
     * writes it then.
     */
    fun storageFailed(id: String, e: IOException, ended: TakeJournal? = null) {
        if (ended != null) unclosed[id] = ended
        storageErrors[id] = e.message ?: e.javaClass.simpleName
        _changes.update { it + 1 }
    }

    fun dir(id: String) = File(root, id)

    /**
     * A new take: its folder, take.json, and the journal open for appending, synced every
     * [syncMs] of [elapsedMs] (a monotonic clock: a wall clock set back would hold the syncs).
     */
    fun create(journal: TakeJournal, syncMs: Long, elapsedMs: () -> Long): JournalWriter {
        val d = dir(journal.recordingId)
        if (!d.mkdirs() && !d.isDirectory) throw IOException("cannot create ${d.name}")
        write(journal)
        return JournalWriter(this, journal, File(d, STREAM), elapsedMs, syncMs)
    }

    internal fun sync(out: FileOutputStream) = syncFile(out)

    fun read(id: String): TakeJournal? = try {
        json.decodeFromString(TakeJournal.serializer(), File(dir(id), JOURNAL).readText())
    } catch (_: Exception) {
        null
    }

    /** take.json, replaced atomically ([writeAtomic]). */
    fun write(journal: TakeJournal) {
        val bytes = json.encodeToString(TakeJournal.serializer(), journal).encodeToByteArray()
        try {
            writeAtomic(File(dir(journal.recordingId), JOURNAL), bytes)
        } finally {
            forget(journal.recordingId)
        }
    }

    /**
     * Newest first; folders without a readable take.json are left out. A finished take's folder
     * is read once and kept until its files change ([forget]).
     */
    fun list(): List<RecordingEntry> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { d ->
            val id = d.name
            val f = folders[id] ?: readFolder(d)
            f?.let { RecordingEntry(it.journal, it.streamBytes, it.bytes, it.reviewStatus, storageErrors[id]) }
        }.sortedByDescending { it.journal.recordingId }

    private fun readFolder(d: File): Folder? {
        val id = d.name
        val generation = generations[id]
        folderReads.incrementAndGet()
        val j = read(id) ?: return null
        val meta = File(d, META)
        val files = d.listFiles { f -> f.isFile } ?: emptyArray()
        val f = Folder(j, File(d, STREAM).length(), files.sumOf { it.length() }, if (meta.isFile) reviewStatus(meta) else null)
        if (j.state != JournalState.RECORDING) {
            folders[id] = f
            if (generations[id] != generation) folders.remove(id, f)     // changed while it was read
        }
        return f
    }

    /**
     * Writes the dataset files of a finished take from its journal ([Finalizer]), then has them
     * labelled ([labeler]). Null, with the reason in take.json (finalizeError), when the take
     * cannot become a recording (a bug in the finalizer counts as that too, so it is not tried at
     * every start); null too while it is being recorded. An IOException (storage) is thrown and
     * kept in memory only ([storageError]): it is tried again ([retry], the next start). Running
     * out of memory is kept in take.json as retryable (finalizeRetry): not tried at the next
     * start, only by [retry]. One at
     * a time (and under [id]'s folder lock, see [exclusive]), [preparing] meanwhile; [changes]
     * counts each try. Run again on the same journal it writes the same files (labels written
     * for the same data are kept).
     */
    fun finalize(id: String): FinalizeResult? = synchronized(finalizing) {
        synchronized(folderLock(id)) {
            val j = read(id) ?: return null
            if (j.state == JournalState.RECORDING) return null
            preparingId = id
            try {
                finalizeLocked(id, j).also { storageErrors.remove(id) }
            } catch (e: IOException) {
                storageErrors[id] = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                preparingId = null
                forget(id)
                _changes.update { it + 1 }
            }
        }
    }

    private fun finalizeLocked(id: String, j: TakeJournal): FinalizeResult? {
        val result = try {
            finalizer.finalize(dir(id), j)
        } catch (e: IOException) {
            throw e
        } catch (e: FinalizeException) {
            // finalizeRetry false: a try after running out of memory that fails for good is not retryable
            write(j.copy(finalizeError = e.message ?: "it cannot be finalized", finalizeRetry = false))
            return null
        } catch (e: RuntimeException) {
            write(j.copy(finalizeError = "the app could not finalize it (${e.message ?: e.javaClass.simpleName})", finalizeRetry = false))
            return null
        } catch (e: OutOfMemoryError) {
            // Not retried at every start (it would fail again and hold up the takes after it),
            // but not the take's fault either: the review offers Try again ([retry]).
            write(j.copy(finalizeError = OUT_OF_MEMORY, finalizeRetry = true))
            return null
        }
        if (j.finalizeError != null || j.finalizeRetry) write(j.copy(finalizeError = null, finalizeRetry = false))
        labeler?.label(dir(id), result)
        return result
    }

    /**
     * [id] again after a storage error ([storageError]) or running out of memory
     * (TakeJournal.finalizeRetry): a journal left recording because it
     * could not be closed gets how its take ended ([storageFailed]'s ended), else (that is not
     * known) it is cut and marked interrupted first as [recover] does; then it is finalized.
     * Null while its take is being recorded.
     */
    fun retry(id: String): FinalizeResult? {
        val j = read(id) ?: return null
        if (j.state == JournalState.RECORDING) {
            if (storageErrors[id] == null) return null
            try {
                unclosed[id]?.let(::write) ?: interrupt(j)
                unclosed.remove(id)
            } catch (e: IOException) {
                storageFailed(id, e)
                throw e
            }
        }
        return finalize(id)
    }

    // A journal the app stopped writing while it ran: cut to its last record, marked interrupted.
    private fun interrupt(j: TakeJournal) {
        val f = File(dir(j.recordingId), STREAM)
        val kept = if (f.isFile) truncateToLastRecord(f) else 0L
        write(
            j.copy(
                state = JournalState.INTERRUPTED,
                endedUtc = j.endedUtc ?: RecordingIds.utc(Instant.ofEpochMilli(f.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())),
                endReason = j.endReason ?: "unknown",
                note = j.note ?: "app stopped while recording",
                bytes = kept,
            ),
        )
    }

    /** Finished takes without dataset files that did not fail before ([active]: still being written). */
    fun pending(active: Set<String> = emptySet()): List<String> = list().filter {
        val j = it.journal
        j.state != JournalState.RECORDING && !it.finalized && j.finalizeError == null && j.recordingId !in active
    }.map { it.journal.recordingId }

    /**
     * Journals the app was writing when it died ([active]: the ones it still writes): cut back
     * to the end of their last complete record and marked interrupted. Then every finished take
     * without dataset files is finalized ([finalize]; the interrupted ones up to their last
     * complete record), each on its own: a storage error on one is thrown after the others were
     * tried. The labels of finalized ones the app died while writing are finished
     * (Finalizer.repairLabels). Returns the ids cut.
     */
    fun recover(active: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        var failed: IOException? = null
        for (e in list()) {
            val j = e.journal
            if (e.finalized) {
                try {
                    // the folder is read again only when the repair changed it
                    val id = j.recordingId
                    if (synchronized(folderLock(id)) { Finalizer.repairLabels(dir(id)) }) forget(id)
                } catch (x: IOException) {
                    failed = failed ?: x
                }
            }
            if (j.state != JournalState.RECORDING || j.recordingId in active) continue
            interrupt(j)
            out += j.recordingId
        }
        for (id in pending(active)) {
            try {
                finalize(id)
            } catch (e: IOException) {
                failed = failed ?: e
            }
        }
        failed?.let { throw it }
        return out
    }

    fun delete(id: String): Boolean = try {
        dir(id).deleteRecursively()
    } finally {
        forget(id)
    }

    fun freeBytes(): Long {
        root.mkdirs()
        return freeSpace(root)
    }

    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        @SuppressLint("UsableSpace")   // the plain JVM default (tests); see the constructor
        private fun usableSpace(f: File): Long = f.usableSpace

        const val STREAM = "stream.dbr"
        const val JOURNAL = "take.json"
        const val META = "meta.json"
        const val AUTO_ONLY = "auto_only"
        const val OUT_OF_MEMORY = "the app ran out of memory finalizing it"

        /**
         * The stage of a recording whose meta.review.status is [reviewStatus] (null: not
         * finalized) and whose last try failed on storage with [storageError] (a journal that
         * could not be closed: not recording any more).
         */
        fun stageOf(j: TakeJournal, reviewStatus: String?, storageError: String? = null): RecordingStage = when {
            reviewStatus == null && storageError != null -> RecordingStage.INTERRUPTED
            j.state == JournalState.RECORDING -> RecordingStage.RECORDING
            reviewStatus == AUTO_ONLY -> RecordingStage.NEEDS_REVIEW
            reviewStatus != null -> RecordingStage.SAVED
            j.state == JournalState.INTERRUPTED || j.finalizeError != null -> RecordingStage.INTERRUPTED
            else -> RecordingStage.NEEDS_REVIEW
        }

        /** meta.review.status; auto_only when meta.json cannot be read. */
        private fun reviewStatus(meta: File): String = reviewStatus(Finalizer.readJson(meta))

        /** meta.review.status of [meta]; auto_only when there is none. */
        fun reviewStatus(meta: JsonObject?): String =
            ((meta?.get("review") as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull ?: AUTO_ONLY

        /** [f] replaced atomically: [bytes] written next to it (<name>.tmp), synced, renamed over it. */
        fun writeAtomic(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(f)) throw IOException("cannot replace ${f.name} in ${f.parentFile?.name}")
        }

        /** Cuts [f] after its last complete record (bytes in between stay: the decoder resyncs). */
        fun truncateToLastRecord(f: File): Long {
            val d = StreamDecoder(typed = false)
            f.forEachBlock(64 * 1024) { buf, n -> d.feed(buf, 0, n) }
            val last = d.lastEnd
            if (f.length() != last) RandomAccessFile(f, "rw").use { it.setLength(last) }
            return last
        }
    }
}

/**
 * Appends a take's stream bytes unchanged and syncs them to storage at most once per
 * [syncMs] of the monotonic [clock] (and when finished): after a crash the journal holds the
 * stream up to about a second before it. Thread-safe.
 */
class JournalWriter internal constructor(
    private val store: RecordingStore,
    journal: TakeJournal,
    file: File,
    private val clock: () -> Long,
    private val syncMs: Long,
) : Closeable {
    private val out = FileOutputStream(file, true)
    private var lastSync = clock()
    private var closed = false

    var journal: TakeJournal = journal
        private set

    var bytes: Long = file.length()
        private set

    @Synchronized
    fun append(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("journal closed")
        out.write(b, off, len)
        bytes += len
        val now = clock()
        if (now - lastSync >= syncMs) {
            store.sync(out)
            lastSync = now
        }
    }

    /** Changes take.json while the take runs; nothing once it is finished. */
    @Synchronized
    fun update(change: (TakeJournal) -> TakeJournal) {
        if (closed) return
        journal = change(journal)
        store.write(journal)
    }

    /**
     * Syncs and closes the journal and records how the take ended in take.json. [journal] says
     * so even when that fails on storage (RecordingStore.storageFailed keeps it for the retry).
     */
    @Synchronized
    fun finish(state: JournalState, endReason: String?, note: String?, stats: JournalStats?, ended: Instant) {
        if (closed) return
        journal = journal.copy(
            state = state, endReason = endReason, note = note, stats = stats, bytes = bytes, endedUtc = RecordingIds.utc(ended),
        )
        try {
            store.sync(out)
        } finally {
            out.close()
            closed = true
        }
        store.write(journal)
    }

    /** Closes without touching take.json (a take that never started). */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }
}
