package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.export.Finalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/** A recording as the review opens it. */
class ReviewData(
    val id: String,
    val labels: Labels,             // what the review starts from: the draft, else events.json
    val saved: Labels,              // events.json
    val auto: Labels?,              // the detector's (reset to auto, suggestions)
    val fromDraft: Boolean,
    val lanes: Lanes,
    val level: LevelRows?,
    val tolDeg: Double,
    val reviewer: String?,          // install4 (meta.review.reviewer)
    val reviewStatus: String,
    val setup: SessionSetup?,
)

/** Where a recording is for the review. */
sealed interface ReviewSource {
    data object NotReady : ReviewSource                     // being recorded, finalized or labelled
    // no dataset files can be made from it; [retry]: storage failed, or the app ran out of memory
    // ([outOfMemory]): ReviewRepository.retry tries again
    data class Failed(val reason: String, val retry: Boolean = false, val outOfMemory: Boolean = false) : ReviewSource
    data object Ready : ReviewSource
}

/**
 * The review's files in a recording folder of [store]: it reads events.json (or the draft) and
 * the signal lanes, keeps the draft (LabelFiles.DRAFT) on every change, and Save writes
 * events.json, meta.review (reviewed) and meta.derived recomputed from LEVEL for the edited
 * labels (the validator re-checks them). Load, Save and the draft run under the recording's folder lock
 * (RecordingStore.exclusive), so they never write its files at the same time as the background
 * finalize and labeller, and do not wait for those of other recordings. Labelling it or making
 * its lanes (the heavy part, rare) waits for the app's one heavy computation at a time
 * (AutoLabeler's compute lock, taken inside the folder lock); opening a labelled recording does
 * not. Blocking file I/O.
 */
class ReviewRepository(private val store: RecordingStore, private val labeler: AutoLabeler?) {
    private fun dir(id: String) = store.dir(id)

    fun source(id: String): ReviewSource {
        val j = store.read(id) ?: return ReviewSource.Failed("The recording is gone.")
        if (store.preparing(id)) return ReviewSource.NotReady
        if (File(dir(id), Finalizer.META).isFile) return ReviewSource.Ready
        // the storage error first: it is the last try's (a retry after running out of memory that
        // failed on storage leaves take.json's marker as it was)
        store.storageError(id)?.let { return ReviewSource.Failed(it, retry = true) }
        j.finalizeError?.let { return ReviewSource.Failed(it, retry = j.finalizeRetry, outOfMemory = j.finalizeRetry) }
        return ReviewSource.NotReady
    }

    /**
     * Makes the dataset files again after a storage error or running out of memory
     * (ReviewSource.Failed.retry); nothing when they are there (a load that ran out of memory:
     * [load] labels it again).
     */
    fun retry(id: String) {
        if (source(id) is ReviewSource.Failed) store.retry(id)
    }

    fun load(id: String): ReviewData = store.exclusive(id) { loadLocked(id) }

    private fun loadLocked(id: String): ReviewData {
        val d = dir(id)
        Finalizer.repairLabels(d)
        val meta = Finalizer.readJson(File(d, Finalizer.META)) ?: throw IOException("the recording is not finalized")
        var saved = LabelFiles.readLabels(File(d, Finalizer.EVENTS)) ?: throw IOException("its events.json cannot be read")
        val status = RecordingStore.reviewStatus(meta)
        var auto = LabelFiles.readLabels(File(d, LabelFiles.AUTO))
        // never labelled (the detector failed after the finalize, the app died before its labels
        // replaced the empty set, or it ran in an older app): now
        if (saved.detector == null && status == RecordingStore.AUTO_ONLY && labeler != null) {
            val det = labeler.relabel(d)
            saved = det.labels
            auto = det.labels
        }
        val draft = LabelFiles.readLabels(File(d, LabelFiles.DRAFT))?.takeIf { it.recordingId == saved.recordingId && it.coverage == saved.coverage }
        val lanes = labeler?.lanes(d) ?: Lanes.read(File(d, LabelFiles.LANES)) ?: throw IOException("no signal lanes")
        val level = File(d, Finalizer.LEVEL).takeIf { it.isFile }?.let { RecordingFiles.readLevel(it) }
        val tol = RecordingFiles.tolDeg(meta)
        val j = store.read(id)
        return ReviewData(id, draft ?: saved, saved, auto, draft != null, lanes, level, tol, j?.reviewer, status, j?.setup)
    }

    fun saveDraft(id: String, labels: Labels) = store.exclusive(id) { LabelFiles.writeLabels(File(dir(id), LabelFiles.DRAFT), labels) }

    fun discardDraft(id: String) {
        store.exclusive(id) { File(dir(id), LabelFiles.DRAFT).delete() }
    }

    /**
     * Saves [labels] as the reviewed labels: sorted, revision + 1 over events.json, the holes'
     * computed off angles and meta.derived recomputed, meta.review reviewed (by [data]'s
     * reviewer) with [edited] changed labels. Returns what was written.
     */
    fun save(data: ReviewData, labels: Labels, edited: Int): Labels = store.exclusive(data.id) { saveLocked(data, labels, edited) }

    private fun saveLocked(data: ReviewData, labels: Labels, edited: Int): Labels {
        val d = dir(data.id)
        val current = LabelFiles.readLabels(File(d, Finalizer.EVENTS)) ?: data.saved
        val sorted = LabelEdits.sorted(labels, current.revision + 1)
        val (derived, holes) = Derived.compute(data.level, sorted, data.tolDeg)
        val out = sorted.copy(holes = sorted.holes.map { h -> if (h.offAngleSource == "computed") h.copy(offAngleDeg = holes[h.hole]?.offAngleDeg) else h })
        val meta = Finalizer.readJson(File(d, Finalizer.META)) ?: throw IOException("the recording is not finalized")
        val oldDetector = ((meta["review"] as? JsonObject)?.get("detector") as? JsonPrimitive)?.content ?: "none"
        val review = Finalizer.review(
            "reviewed", out.detector?.version ?: oldDetector, out.revision, edited, data.reviewer?.takeIf { REVIEWER.matches(it) },
        )
        Finalizer.writeLabels(d, out.toJson(), derived.toJson(), review)
        File(d, LabelFiles.DRAFT).delete()
        store.changed(data.id)
        return out
    }

    private companion object {
        val REVIEWER = Regex("^[a-z2-7]{4}$")
    }
}
