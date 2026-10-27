package dev.drillbalancer.recorder.export

import dev.drillbalancer.upload.UploadTestData
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opt-in: DB_MUTE_OUT=<dir> leaves the finalizer's fixture takes reviewed and muted ("mute audio
 * while the motor is off") there as a data repo has them (recordings/YYYY/YYYY-MM/<id>/), for
 * `.venv/bin/python -m dbdata.validate <dir>` and dbdata.windows (whose windows over the
 * muted spans are audio_muted; app/README.md, Upload). rich: one motor run 0.5-1.0 s; rich_cut
 * and golden: the detector's labels.
 */
class MuteValidatorExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun mutedRecordingsForTheValidator() {
        val out = System.getenv("DB_MUTE_OUT")
        assumeTrue("DB_MUTE_OUT not set: the dbdata.validate check is opt-in", !out.isNullOrBlank())
        val labeler = UploadTestData.labeler()
        val store = UploadTestData.store(tmp.root, labeler)
        for (name in FinalizerFixtures.names) {
            val id = UploadTestData.reviewed(store, labeler, name) { l -> if (name == "rich") UploadTestData.motorRun(l, 0.5, 1.0) else l }
            assertTrue(name, AudioMute.apply(store.dir(id)))
            UploadTestData.exportToRepo(store.dir(id), File(out))
        }
    }
}
