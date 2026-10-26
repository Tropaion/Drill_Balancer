package dev.drillbalancer.recorder

import dev.drillbalancer.recorder.stream.StreamDecoder
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant

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
) {
    companion object {
        const val SCHEMA = "drillbalancer.take/1"
    }
}

/** One recording folder as the list shows it. */
data class RecordingEntry(val journal: TakeJournal, val streamBytes: Long, val finalized: Boolean)

/**
 * The recordings on the phone: filesDir/recordings/<recording_id>/ with the journal
 * stream.dbr (the raw TCP bytes of the take, unchanged; the source of truth) and take.json.
 * Milestone 6 adds the dataset files to the same folder. Blocking file I/O: call it off the
 * main thread.
 */
class RecordingStore(
    val root: File,
    // The app passes StorageManager's allocatable bytes (cached files Android would clear count).
    private val freeSpace: (File) -> Long = ::usableSpace,
    // How a journal goes to storage (tests count the calls).
    private val syncFile: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    /** take.json, replaced atomically (written next to it, synced, renamed). */
    fun write(journal: TakeJournal) {
        val d = dir(journal.recordingId)
        val tmp = File(d, "$JOURNAL.tmp")
        FileOutputStream(tmp).use {
            it.write(json.encodeToString(TakeJournal.serializer(), journal).encodeToByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(File(d, JOURNAL))) throw IOException("cannot replace $JOURNAL in ${d.name}")
    }

    /** Newest first; folders without a readable take.json are left out. */
    fun list(): List<RecordingEntry> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { d ->
            read(d.name)?.let { RecordingEntry(it, File(d, STREAM).length(), File(d, META).isFile) }
        }.sortedByDescending { it.journal.recordingId }

    /**
     * Journals the app was writing when it died ([active]: the ones it still writes): cut back
     * to the end of their last complete record and marked interrupted. Returns their ids.
     */
    fun recover(active: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        for (e in list()) {
            val j = e.journal
            if (j.state != JournalState.RECORDING || j.recordingId in active) continue
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
            out += j.recordingId
        }
        return out
    }

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

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

        /** Cuts [f] after its last complete record (bytes in between stay: the decoder resyncs). */
        fun truncateToLastRecord(f: File): Long {
            val d = StreamDecoder(typed = false)
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.feed(buf, 0, n)
                }
            }
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

    /** Syncs and closes the journal and records how the take ended in take.json. */
    @Synchronized
    fun finish(state: JournalState, endReason: String?, note: String?, stats: JournalStats?, ended: Instant) {
        if (closed) return
        try {
            store.sync(out)
        } finally {
            out.close()
            closed = true
        }
        journal = journal.copy(
            state = state, endReason = endReason, note = note, stats = stats, bytes = bytes, endedUtc = RecordingIds.utc(ended),
        )
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
