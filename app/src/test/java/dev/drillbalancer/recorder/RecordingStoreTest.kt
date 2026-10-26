package dev.drillbalancer.recorder

import dev.drillbalancer.recorder.stream.StreamDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

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
        store.open(journal("20261026T143312Z_k7q2_dddd")).use { it.append(whole + half, 0, whole.size + half.size) }

        val recovered = store.recover(active = setOf("20261026T143312Z_k7q2_dddd"))
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
        assertTrue(store.recover(active = setOf("20261026T143312Z_k7q2_dddd")).isEmpty())
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
        assertTrue(store.delete("20261026T143012Z_k7q2_aaaa"))
        assertEquals(1, store.list().size)
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
