package dev.drillbalancer.recorder

import dev.drillbalancer.recorder.stream.StreamDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { RecordingStore(File(tmp.root, "recordings")) }

    private fun journal(id: String, state: JournalState = JournalState.RECORDING) = TakeJournal(
        recordingId = id, sessionId = "20261026T1430_k7q2", takeId = 7, reviewer = "k7q2", state = state,
        startedUtc = "2026-10-26T14:30:12.418Z", setup = SessionSetup(material = "wood/softwood"),
        take = TakeSettings("RAW448", 3, 6, 300), app = AppInfo("0.1.0", "Pixel 8", 35), link = "ap",
    )

    private fun RecordingStore.open(j: TakeJournal) = create(j, 1_000) { System.nanoTime() / 1_000_000 }

    @Test
    fun theJournalSyncsOncePerIntervalOfItsClock() {
        var syncs = 0
        val counted = RecordingStore(File(tmp.root, "counted"), syncFile = { syncs++; it.fd.sync() })
        var ms = 0L
        val w = counted.create(journal("20261026T143012Z_k7q2_aaaa"), 1_000) { ms }
        val b = records()
        w.append(b, 0, b.size)
        assertEquals(0, syncs)
        ms = 999
        w.append(b, 0, 10)
        assertEquals(0, syncs)
        ms = 1_000
        w.append(b, 0, 10)
        assertEquals(1, syncs)
        ms = 1_500
        w.append(b, 0, 10)
        assertEquals(1, syncs)
        w.finish(JournalState.COMPLETE, "user", null, null, Instant.EPOCH)
        assertEquals("finish syncs", 2, syncs)
    }

    /** SESSION, three IMU/AUDIO records: whole records of a take. */
    private fun records(): ByteArray =
        TestStream.session(0, 7) + TestStream.imu(1, 100, 5, TestStream.T0 + 10_000) +
            TestStream.audio(2, 0, 160, TestStream.T0 + 10_000) + TestStream.imu(3, 105, 4, TestStream.T0 + 20_000)

    @Test
    fun aJournalTheAppWasWritingIsCutToItsLastRecord() {
        val whole = records()
        val half = TestStream.audio(4, 160, 160, TestStream.T0 + 20_000).copyOf(100)
        store.open(journal("20261026T143012Z_k7q2_aaaa")).use { it.append(whole + half, 0, whole.size + half.size) }
        // Done before the crash: a complete take and a take still being written in this run.
        store.open(journal("20261026T143112Z_k7q2_bbbb", JournalState.COMPLETE)).use { it.append(whole, 0, 50) }
        store.open(journal("20261026T143212Z_k7q2_cccc")).use { it.append(half, 0, half.size) }
        val open = store.open(journal("20261026T143312Z_k7q2_dddd")).apply { append(whole + half, 0, whole.size + half.size) }

        val recovered = store.recover()
        assertEquals(setOf("20261026T143012Z_k7q2_aaaa", "20261026T143212Z_k7q2_cccc"), recovered.toSet())

        val a = store.read("20261026T143012Z_k7q2_aaaa")!!
        assertEquals(JournalState.INTERRUPTED, a.state)
        assertEquals(whole.size.toLong(), a.bytes)
        assertEquals("unknown", a.endReason)
        assertEquals("app stopped while recording", a.note)
        val bytes = File(store.dir(a.recordingId), RecordingStore.STREAM).readBytes()
        assertArrayEquals(whole, bytes)
        val (recs, st) = StreamDecoder.decodeAll(bytes)
        assertEquals(4, recs.size)
        assertEquals(0L, st.skippedBytes)

        // Nothing complete at all: an empty journal.
        assertEquals(0L, File(store.dir("20261026T143212Z_k7q2_cccc"), RecordingStore.STREAM).length())
        // Untouched: the complete one, and the one this run still writes.
        assertEquals(JournalState.COMPLETE, store.read("20261026T143112Z_k7q2_bbbb")!!.state)
        assertEquals(50L, File(store.dir("20261026T143112Z_k7q2_bbbb"), RecordingStore.STREAM).length())
        assertEquals(JournalState.RECORDING, store.read("20261026T143312Z_k7q2_dddd")!!.state)
        // A second run finds nothing more to do.
        assertTrue(store.recover().isEmpty())
        open.close()
    }

    @Test
    fun finishRecordsHowTheTakeEnded() {
        val w = store.open(journal("20261026T143012Z_k7q2_aaaa"))
        val b = records()
        w.append(b, 0, b.size)
        w.finish(JournalState.COMPLETE, "user", null, JournalStats(records = 4), Instant.parse("2026-10-26T14:31:00Z"))
        val j = store.read("20261026T143012Z_k7q2_aaaa")!!
        assertEquals(JournalState.COMPLETE, j.state)
        assertEquals("user", j.endReason)
        assertEquals(b.size.toLong(), j.bytes)
        assertEquals("2026-10-26T14:31:00Z", j.endedUtc)
        assertEquals(4L, j.stats!!.records)
        assertNull(j.note)
        // Finished twice: the first one counts.
        w.finish(JournalState.INTERRUPTED, "x", "y", null, Instant.EPOCH)
        assertEquals(JournalState.COMPLETE, store.read(j.recordingId)!!.state)
        assertFalse(File(store.dir(j.recordingId), "${RecordingStore.JOURNAL}.tmp").exists())
    }

    @Test
    fun listNewestFirstAndSkipsStrayFolders() {
        store.open(journal("20261026T143012Z_k7q2_aaaa")).close()
        store.open(journal("20261027T090000Z_k7q2_bbbb")).close()
        File(store.root, "junk").mkdirs()
        File(store.dir("20261026T143012Z_k7q2_aaaa"), RecordingStore.META).writeText("{}")
        val list = store.list()
        assertEquals(listOf("20261027T090000Z_k7q2_bbbb", "20261026T143012Z_k7q2_aaaa"), list.map { it.journal.recordingId })
        assertEquals(listOf(false, true), list.map { it.finalized })
        assertTrue(store.usedBytes() > 0)
        assertTrue(store.freeBytes() > 0)
        assertTrue(store.remove("20261026T143012Z_k7q2_aaaa"))
        assertEquals(1, store.list().size)
    }

    /**
     * list() reads a finished take's folder once (take.json, meta.json, the files' sizes) and
     * again only after its files changed through the store; a take being recorded every time
     * (its stream grows).
     */
    @Test
    fun listReadsOnlyTheFoldersThatChanged() {
        val ids = (0 until 5).map { "20261026T14301${it}Z_k7q2_aaa$it" }
        for (id in ids) store.open(journal(id, JournalState.COMPLETE)).close()
        val running = store.open(journal("20261027T090000Z_k7q2_bbbb"))
        assertEquals(6, store.list().size)
        val reads = store.folderReads.get()
        assertEquals(6, store.list().size)
        assertEquals("the one being recorded only", reads + 1, store.folderReads.get())
        val b = records()
        running.append(b, 0, b.size)
        assertEquals(b.size.toLong(), store.list().first().streamBytes)
        // a change through the store: that folder alone is read again
        val changed = ids[2]
        File(store.dir(changed), RecordingStore.META).writeText("{\"review\":{\"status\":\"reviewed\"}}")
        store.exclusive(changed) { }
        val before = store.folderReads.get()
        val entry = store.list().single { it.journal.recordingId == changed }
        assertEquals(before + 2, store.folderReads.get())
        assertEquals(RecordingStage.SAVED, entry.stage)
        assertTrue(entry.bytes > 0)
        store.write(store.read(ids[0])!!.copy(finalizeError = "no SESSION"))
        assertEquals(RecordingStage.INTERRUPTED, store.list().single { it.journal.recordingId == ids[0] }.stage)
        running.finish(JournalState.COMPLETE, "user", null, null, Instant.EPOCH)
        assertEquals(JournalState.COMPLETE, store.list().first().journal.state)
        assertTrue(store.remove(ids[4]))
        assertEquals(5, store.list().size)
    }

    /**
     * Delete take (remove): the whole folder, under the folder lock (it waits for a finalize,
     * a review's Save or an upload's snapshot of it); never a take being recorded; the list
     * reloads and no longer shows it.
     */
    @Test
    fun removeDeletesAFinishedTakeUnderItsLock() {
        val recording = "20261027T090000Z_k7q2_bbbb"
        val writer = store.open(journal(recording))
        assertTrue(store.recording(recording))
        assertFalse("being recorded", store.remove(recording))
        assertTrue(store.dir(recording).isDirectory)
        val id = "20261026T143012Z_k7q2_aaaa"
        store.open(journal(id, JournalState.COMPLETE)).close()
        File(store.dir(id), RecordingStore.META).writeText("{\"review\":{\"status\":\"reviewed\"}}")
        File(store.dir(id), "upload.json").writeText("{}")
        assertEquals(2, store.list().size)
        // a holder of its folder lock (a Save, an upload's snapshot): the remove waits for it
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seen = false
        val holder = Thread { store.exclusive(id) { inside.countDown(); release.await(); seen = File(store.dir(id), RecordingStore.META).isFile } }.apply { start() }
        inside.await()
        val changes = store.changes.value
        var removed = false
        val remover = Thread { removed = store.remove(id) }.apply { start() }
        Thread.sleep(200)
        assertTrue("waits for the lock", remover.isAlive)
        release.countDown()
        holder.join(10_000)
        remover.join(10_000)
        assertTrue("the holder read its files whole", seen)
        assertTrue(removed)
        assertFalse(store.dir(id).exists())
        assertTrue(store.changes.value > changes)
        assertEquals(listOf(recording), store.list().map { it.journal.recordingId })
        val after = store.changes.value
        assertFalse("gone already: nothing to do", store.remove(id))
        assertEquals("no change counted", after, store.changes.value)
        writer.close()
        assertFalse(store.recording(recording))
    }

    /**
     * A journal that says recording but that nobody writes is deleted: one whose close failed on
     * storage (the review's error offers Delete take to free that space), one of a run that died.
     */
    @Test
    fun aJournalNobodyWritesIsDeletedThoughItSaysRecording() {
        var full = false
        val failing = RecordingStore(File(tmp.root, "failing"), syncFile = { if (full) throw IOException("No space left on device") else it.fd.sync() })
        val id = "20261026T143012Z_k7q2_aaaa"
        val w = failing.create(journal(id), 1_000) { 0 }
        val b = records()
        w.append(b, 0, b.size)
        full = true
        val e = assertThrows(IOException::class.java) { w.finish(JournalState.COMPLETE, "user", null, null, Instant.EPOCH) }
        failing.storageFailed(id, e, ended = w.journal)          // as the controller does
        assertEquals(JournalState.RECORDING, failing.read(id)!!.state)
        assertFalse("no longer recorded", failing.recording(id))
        assertTrue(failing.remove(id))
        assertFalse(failing.dir(id).exists())
        assertNull(failing.storageError(id))

        val died = "20261026T143112Z_k7q2_bbbb"
        failing.create(journal(died), 1_000) { 0 }.close()
        assertTrue("the app died while it was recorded", failing.remove(died))
        assertFalse(failing.dir(died).exists())
    }

    /**
     * A journal recover() cannot cut (storage) is a storage error of that take only: the others
     * are cut, the error is thrown after them, and the take offers Try again and Delete take.
     */
    @Test
    fun aJournalThatCannotBeCutAtTheStartIsAStorageError() {
        val whole = records()
        val stuck = "20261026T143012Z_k7q2_aaaa"
        val gone = "20261026T143112Z_k7q2_bbbb"
        val fine = "20261026T143212Z_k7q2_cccc"
        for (id in listOf(stuck, gone, fine)) store.open(journal(id)).use { it.append(whole, 0, whole.size) }
        // take.json cannot be replaced: its tmp file is a folder
        val blocked = listOf(stuck, gone).map { File(store.dir(it), "${RecordingStore.JOURNAL}.tmp").apply { mkdirs() } }
        assertThrows(IOException::class.java) { store.recover() }
        assertEquals(JournalState.INTERRUPTED, store.read(fine)!!.state)
        for (id in listOf(stuck, gone)) {
            assertEquals(JournalState.RECORDING, store.read(id)!!.state)
            assertNotNull(store.storageError(id))
            assertEquals(RecordingStage.INTERRUPTED, store.list().first { it.journal.recordingId == id }.stage)
        }
        assertTrue(store.remove(gone))
        assertFalse(store.dir(gone).exists())
        blocked.first().delete()
        store.retry(stuck)
        assertEquals(JournalState.INTERRUPTED, store.read(stuck)!!.state)
        assertNull(store.storageError(stuck))
    }

    /**
     * A delete is whole or nothing: the folder goes into the trash in one rename (gone from the
     * list at once), so one cut short (a file that cannot be deleted, the app killed) leaves no
     * half folder that recover() would finalize again; the next start deletes what is left.
     */
    @Test
    fun aDeleteCutShortLeavesNoTakeBehind() {
        val id = "20261026T143012Z_k7q2_aaaa"
        store.open(journal(id, JournalState.COMPLETE)).close()
        File(store.dir(id), RecordingStore.META).writeText("{\"review\":{\"status\":\"reviewed\"}}")
        val takeJson = File(store.dir(id), RecordingStore.JOURNAL).readText()
        // a file that cannot be deleted (a folder without write permission)
        val stuck = File(store.dir(id), "stuck").apply { mkdirs() }
        File(stuck, "x").writeText("x")
        stuck.setWritable(false)
        val trashed = File(File(store.root, RecordingStore.TRASH), id)
        try {
            assertTrue(store.remove(id))
            assertFalse(store.dir(id).exists())
            assertTrue(store.list().isEmpty())
            assertTrue(store.pending().isEmpty())
        } finally {
            File(trashed, "stuck").setWritable(true)       // moved there whole
        }
        // what a killed delete leaves: a journal in the trash, no meta.json
        trashed.mkdirs()
        File(trashed, RecordingStore.JOURNAL).writeText(takeJson)
        assertTrue(store.list().isEmpty())
        assertTrue(store.recover().isEmpty())
        assertFalse("the next start deletes it", trashed.exists())
        assertFalse("not finalized again", store.dir(id).exists())
        assertTrue(store.list().isEmpty())
    }

    private fun finished(id: String): File {
        store.open(journal(id, JournalState.COMPLETE)).close()
        return File(store.dir(id), RecordingStore.META).apply { writeText("{\"review\":{\"status\":\"reviewed\"}}") }
    }

    /**
     * An upload reading the take (its open snapshot: reader): remove asks it to stop and waits
     * for it to close, so the files are never moved while it reads them; one that opens while
     * the remove waits is stopped too. One that does not stop: the remove gives up, nothing deleted.
     */
    @Test
    fun removeStopsTheTakesReadersAndWaitsForThem() {
        val id = "20261026T143012Z_k7q2_aaaa"
        val meta = finished(id)
        val firstStopped = CountDownLatch(1)
        val first = store.exclusive(id) { store.reader(id) { firstStopped.countDown() } }
        var removed = false
        val remover = Thread { removed = store.remove(id) }.apply { start() }
        assertTrue("stopped", firstStopped.await(10, TimeUnit.SECONDS))
        val secondStopped = CountDownLatch(1)
        val second = store.reader(id) { secondStopped.countDown() }
        assertTrue("one opened meanwhile is stopped too", secondStopped.await(10, TimeUnit.SECONDS))
        first.close()
        Thread.sleep(200)
        assertTrue("waits for both", remover.isAlive)
        assertTrue("its files are read whole", meta.isFile)
        second.close()
        remover.join(10_000)
        assertTrue(removed)
        assertFalse(store.dir(id).exists())

        val other = "20261026T143013Z_k7q2_bbbb"
        val kept = finished(other)
        val stuck = store.reader(other) {}
        assertThrows(IOException::class.java) { store.remove(other, waitMs = 100) }
        assertTrue("nothing deleted", kept.isFile)
        assertTrue(store.list().any { it.journal.recordingId == other })
        stuck.close()
        assertTrue(store.remove(other))
        assertFalse(store.dir(other).exists())
    }

    /**
     * The start's cleanup of the trash (recover) deletes each deleted take under its folder lock,
     * and keeps the trash folder: never under a remove that is moving a take there.
     */
    @Test
    fun recoverClearsTheTrashUnderEachTakesLock() {
        val id = "20261026T143012Z_k7q2_aaaa"
        val trashed = File(File(store.root, RecordingStore.TRASH), id).apply { mkdirs() }
        File(trashed, RecordingStore.JOURNAL).writeText("{}")
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { store.exclusive(id) { inside.countDown(); release.await() } }.apply { start() }
        inside.await()
        val recovering = Thread { store.recover() }.apply { start() }
        Thread.sleep(200)
        assertTrue("it waits for the take's lock", trashed.isDirectory)
        release.countDown()
        holder.join(10_000)
        recovering.join(10_000)
        assertFalse(trashed.exists())
        assertTrue("the trash folder stays (a remove may be about to move into it)", File(store.root, RecordingStore.TRASH).isDirectory)
    }

    @Test
    fun journalJsonKeepsTheContractNames() {
        store.open(journal("20261026T143012Z_k7q2_aaaa")).close()
        val text = File(store.dir("20261026T143012Z_k7q2_aaaa"), RecordingStore.JOURNAL).readText()
        for (key in listOf("\"recording_id\"", "\"session_id\"", "\"take_id\":7", "\"started_utc\"", "\"material\":\"wood/softwood\"",
            "\"mic_mask\":3", "\"phone_model\":\"Pixel 8\"", "\"state\":\"recording\"", "\"schema\":\"drillbalancer.take/1\"")) {
            assertTrue("$key in $text", key in text)
        }
    }
}
