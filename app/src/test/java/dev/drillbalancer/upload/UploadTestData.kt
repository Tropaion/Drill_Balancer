package dev.drillbalancer.upload

import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Marker
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.Source
import dev.drillbalancer.recorder.label.TestParams
import java.io.File

/** Reviewed recordings for the upload tests: the finalizer's fixture takes, finalized, labelled and saved through the review. */
object UploadTestData {
    fun labeler() = AutoLabeler({ TestParams.loaded })

    fun store(root: File, labeler: AutoLabeler = labeler()) = RecordingStore(File(root, "recordings"), labeler = labeler)

    /** The fixture [name] (golden: tiny; rich: a few seconds with audio) finalized, labelled and saved: reviewed. */
    fun reviewed(store: RecordingStore, labeler: AutoLabeler, name: String = "rich", change: (Labels) -> Labels = { it }): String {
        val j = FinalizerFixtures.install(store, name)
        if (FinalizerFixtures.spec(name)["cut"].toString() == "null") store.finalize(j.recordingId) else store.recover()
        save(store, labeler, j.recordingId, change)
        return j.recordingId
    }

    /** Another Save of [id]'s review with [change] applied to its labels. */
    fun save(store: RecordingStore, labeler: AutoLabeler, id: String, change: (Labels) -> Labels = { it }) {
        val repo = ReviewRepository(store, labeler)
        val data = repo.load(id)
        repo.save(data, change(LabelEdits.confirmAll(data.labels)), 0)
    }

    /** [l] with one motor run from [onS] to [offS] seconds after the coverage start, nothing else. */
    fun motorRun(l: Labels, onS: Double, offS: Double): Labels = l.copy(
        events = listOf(
            Marker("e1", "motor_on", l.c0 + (onS * 1e6).toLong(), source = Source.USER),
            Marker("e2", "motor_off", l.c0 + (offS * 1e6).toLong(), source = Source.USER),
        ),
        spans = emptyList(),
        holes = emptyList(),
    )

    /** Every file under [root] whose bytes hold [secret] (the token must be in none). */
    fun filesHolding(root: File, secret: String): List<File> {
        val needle = secret.toByteArray()
        return root.walkTopDown().filter { it.isFile }.filter { f ->
            val b = f.readBytes()
            (0..b.size - needle.size).any { i -> needle.indices.all { b[i + it] == needle[it] } }
        }.toList()
    }
}
