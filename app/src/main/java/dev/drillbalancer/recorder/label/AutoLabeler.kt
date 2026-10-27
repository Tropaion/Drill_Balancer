package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.Labeler
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.FinalizeResult
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.PyJson
import kotlinx.serialization.json.JsonObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The phone-only files next to a recording's dataset files (never uploaded: not in
 * Finalizer.DATASET_FILES): the detector's own labels (the review's "reset to auto"), the
 * review's unsaved draft, and the review's signal lanes.
 */
object LabelFiles {
    const val AUTO = "events.auto.json"
    const val DRAFT = "events.draft.json"
    const val LANES = "lanes.bin"

    fun readLabels(f: File): Labels? = Finalizer.readJson(f)?.let(::labelsOf)

    /** [o] as labels; null when it holds none. */
    fun labelsOf(o: JsonObject): Labels? = try {
        Labels.fromJson(o)
    } catch (_: Exception) {
        null
    }

    /** [labels] as dbdata.io.dump_json writes it, replaced atomically. */
    fun writeLabels(f: File, labels: Labels) = RecordingStore.writeAtomic(f, PyJson.dump(labels.toJson()).toByteArray())
}

/**
 * The review's four signal lanes per 10 ms frame (E_imu dB, E_aud dBFS, f0 Hz, E_cut dB), the
 * detector's features as floats (NaN: no value), kept in lanes.bin so the review opens at once.
 */
class Lanes(val t0Us: Long, val frameUs: Long, val values: List<FloatArray>) {
    val n: Int get() = values.firstOrNull()?.size ?: 0

    /** lanes.bin (big-endian: header, then each lane's floats), replaced atomically (RecordingStore.writeAtomic). */
    fun write(f: File) {
        val b = ByteBuffer.allocate(Math.toIntExact(HEADER + 4L * n * values.size))
        b.putInt(MAGIC).putInt(VERSION).putLong(t0Us).putLong(frameUs).putInt(n).putInt(values.size)
        for (v in values) for (x in v) b.putFloat(x)
        RecordingStore.writeAtomic(f, b.array())
    }

    companion object {
        private const val MAGIC = 0x44424C4E     // "DBLN"
        private const val VERSION = 1
        private const val HEADER = 32L
        val FEATURES = listOf(Feature.E_IMU, Feature.E_AUD, Feature.F0, Feature.E_CUT)

        fun of(f: Features): Lanes = Lanes(f.t0Us, f.frameUs, FEATURES.map { c -> f[c].let { a -> FloatArray(a.size) { a[it].toFloat() } } })

        /**
         * lanes.bin, or null when it is missing, not this version, or not whole (its header's
         * sizes are checked against the file's length before anything is allocated).
         */
        fun read(f: File): Lanes? = try {
            val length = f.length()
            DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                val t0 = i.readLong()
                val frame = i.readLong()
                val n = i.readInt()
                val k = i.readInt()
                if (n < 0 || k != FEATURES.size || frame <= 0 || length != HEADER + 4L * n * k) return null
                val body = ByteArray(Math.toIntExact(4L * n * k))
                i.readFully(body)
                val floats = ByteBuffer.wrap(body).asFloatBuffer()       // big-endian, as written
                Lanes(t0, frame, List(k) { FloatArray(n).also { floats.get(it) } })
            }
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * The app's auto-labeller ([Labeler]): after a finalize that wrote new data files it runs
 * [AutoDetect] on the recording folder and writes its labels as `dbdata.autodetect --write`
 * does: events.json (revision + 1, source auto), meta.review auto_only with this detector, and
 * meta.derived. The detector's labels are kept apart too (LabelFiles.AUTO), with the review's
 * lanes, and an old review draft is dropped (it belonged to other data). Its failures (running
 * out of memory too) are logged through [onError]: the recording keeps the empty label set and
 * the review labels it again.
 *
 * Its heavy work (a recording's data read whole, [AutoDetect], [FeatureExtractor]) runs one at
 * a time for the whole app under [compute], whoever asks (the background labeller after a
 * finalize, a review that labels or makes its lanes), so no two detector or feature
 * computations are in memory at once. The finalize is not under [compute]: another take's
 * [dev.drillbalancer.recorder.export.Finalizer] (stream decode, FLAC, tracks) can run beside a
 * review's labelling or lanes (it holds the finalize lock and its own folder's, not this one).
 * [compute] is the innermost lock: finalize lock, then the folder lock, then it (the review:
 * folder lock, then it); nothing is locked while it is held. A review that needs none of it (an
 * already labelled recording with its lanes.bin) never takes it.
 */
class AutoLabeler(
    private val params: () -> LoadedParams,
    private val compute: ReentrantLock = ReentrantLock(),
    private val onError: (File, Throwable) -> Unit = { _, _ -> },
) : Labeler {
    override fun label(dir: File, result: FinalizeResult) {
        if (result.labelsKept) return
        try {
            relabel(dir)
        } catch (e: Exception) {
            onError(dir, e)
        } catch (e: OutOfMemoryError) {
            // A long take on a small heap: its arrays are garbage now. The finalize (and the
            // recovery of the takes after it) goes on; the review says so when it labels it.
            onError(dir, e)
        }
    }

    /** Labels [dir] from its dataset files; returns the detection. Blocking (and waits for [compute]). */
    fun relabel(dir: File): Detection = compute.withLock { relabelLocked(dir) }

    private fun relabelLocked(dir: File): Detection {
        val rec = RecordingFiles.read(dir)
        val p = params()
        val det = AutoDetect.detect(rec.inputs, p)
        val old = LabelFiles.readLabels(File(dir, Finalizer.EVENTS))
        val labels = det.labels.copy(revision = (old?.revision ?: 0) + 1)
        LabelFiles.writeLabels(File(dir, LabelFiles.AUTO), labels)
        Lanes.of(det.features).write(File(dir, LabelFiles.LANES))
        File(dir, LabelFiles.DRAFT).delete()
        Finalizer.writeLabels(dir, labels.toJson(), det.derived.toJson(), autoReview(p.p.detector, labels.revision))
        return Detection(labels, det.derived, det.features)
    }

    /** The lanes of [dir]: lanes.bin, or the features computed again (and kept, under [compute]). */
    fun lanes(dir: File): Lanes {
        Lanes.read(File(dir, LabelFiles.LANES))?.let { return it }
        return compute.withLock {
            val rec = RecordingFiles.read(dir)
            val lanes = Lanes.of(FeatureExtractor.compute(rec.inputs, params().p))
            lanes.write(File(dir, LabelFiles.LANES))
            lanes
        }
    }

    companion object {
        /** meta.review of labels the detector wrote and nobody reviewed. */
        fun autoReview(detector: String, revision: Int): JsonObject = Finalizer.review(RecordingStore.AUTO_ONLY, detector, revision)
    }
}
