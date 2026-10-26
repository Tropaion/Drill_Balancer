package dev.drillbalancer.ui.training.review

import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opt-in: DB_REVIEW_OUT=<dir> runs the whole phone path on the finalizer's fixture takes
 * (finalize, auto-label) and on the autodetect fixtures (auto-label), reviews each with the
 * ViewModel (the first contact moved by 20 ms, a kickback added after the last motor_on, the
 * uncertain markers confirmed, Accept all) and leaves the saved recordings there as a data repo
 * has them, for `.venv/bin/python -m dbdata.validate <dir>` (app/README.md, Training data).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewValidatorExportTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun reviewedRecordingsForTheValidator() = runTest(main.dispatcher) {
        val out = System.getenv("DB_REVIEW_OUT")
        assumeTrue("DB_REVIEW_OUT not set: the dbdata.validate check is opt-in", !out.isNullOrBlank())
        val labeler = AutoLabeler({ TestParams.loaded })
        val store = RecordingStore(File(tmp.root, "recordings"), labeler = labeler)
        val repo = ReviewRepository(store, labeler)
        val ids = ArrayList<String>()
        for (name in FinalizerFixtures.names) {
            val j = FinalizerFixtures.install(store, name)
            if (FinalizerFixtures.spec(name)["cut"].toString() == "null") store.finalize(j.recordingId) else store.recover()
            ids += j.recordingId
        }
        for (name in LabelTestData.root.listFiles { f -> File(f, "expected.json").isFile }.orEmpty().map { it.name }.sorted()) {
            ids += LabelTestData.install(store, name, labeler)
        }
        for (id in ids) {
            val vm = ReviewViewModel(id, repo, store, io = main.dispatcher)
            val s = vm.state.value
            s.markers.firstOrNull { it.type == "contact" }?.let { vm.drag(it.id, it.tUs + 20_000) }
            s.markers.lastOrNull { it.type == "motor_on" }?.let { on ->
                vm.setCursor(on.tUs + 400_000)
                vm.add("kickback")
            }
            vm.acceptAll()
            advanceUntilIdle()
            assertTrue("$id: ${vm.state.value.message?.text}", vm.state.value.done)
            val dest = File(out, "recordings/${id.substring(0, 4)}/${id.substring(0, 4)}-${id.substring(4, 6)}/$id")
            dest.deleteRecursively()
            dest.mkdirs()
            for (f in Finalizer.DATASET_FILES) File(store.dir(id), f).takeIf { it.isFile }?.copyTo(File(dest, f))
        }
    }
}
