package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.RecordingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The review's files and what the recordings list keeps of their folder. */
class ReviewRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val labeler = AutoLabeler({ TestParams.loaded })
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private val repo by lazy { ReviewRepository(store, labeler) }

    private fun listed(id: String) = store.list().first { it.journal.recordingId == id }.bytes

    /**
     * A draft written (and dropped) changes the recording's size in the list: saveDraft and
     * discardDraft drop what the list kept of its folder (RecordingStore.exclusive), so the row
     * never shows the size from before.
     */
    @Test
    fun aDraftChangesTheListedSize() {
        val id = LabelTestData.install(store, "fx_gap", labeler)
        val data = repo.load(id)
        val before = listed(id)
        val reads = store.folderReads.get()
        assertEquals(before, listed(id))
        assertEquals("the folder is kept, not read again", reads, store.folderReads.get())

        repo.saveDraft(id, LabelEdits.confirmAll(data.labels))
        val draft = File(store.dir(id), LabelFiles.DRAFT).length()
        assertTrue(draft > 0)
        assertEquals("the draft counts", before + draft, listed(id))
        assertEquals(reads + 1, store.folderReads.get())

        // a second draft of another size: read again too
        val c = data.labels.events.first()
        repo.saveDraft(id, LabelEdits.move(data.labels, c.id, c.tUs + 12_345))
        assertEquals(before + File(store.dir(id), LabelFiles.DRAFT).length(), listed(id))

        repo.discardDraft(id)
        assertEquals(before, listed(id))
    }
}
