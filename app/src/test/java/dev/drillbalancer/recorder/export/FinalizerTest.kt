package dev.drillbalancer.recorder.export

import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.Labeler
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.TakeJournal
import dev.drillbalancer.recorder.TakeSettings
import dev.drillbalancer.recorder.TestStream
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.ReviewSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

class FinalizerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val labelled = ArrayList<FinalizeResult>()
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = Labeler { _, r -> labelled += r }) }
    private val metaSchema = SchemaCheck("meta.v1.schema.json")
    private val eventsSchema = SchemaCheck("events.v1.schema.json")

    private fun files(dir: File): Map<String, ByteArray> = dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }

    private fun meta(id: String) = FinalizerFixtures.parse(File(store.dir(id), Finalizer.META)).jsonObject

    private fun stage(id: String) = store.list().single { it.journal.recordingId == id }.stage

    @Test
    fun theSameJournalGivesTheSameBytes() {
        val j = FinalizerFixtures.install(store, "rich")
        assertEquals(RecordingStage.NEEDS_REVIEW, stage(j.recordingId))
        val first = store.finalize(j.recordingId)!!
        val a = files(store.dir(j.recordingId))
        val second = store.finalize(j.recordingId)!!
        assertEquals(first.copy(labelsKept = true), second)
        val b = files(store.dir(j.recordingId))
        assertEquals(a.keys, b.keys)
        for (k in a.keys) assertArrayEquals(k, a[k], b[k])
        assertFalse("no temporary files left", a.keys.any { it.endsWith(".tmp") })
        assertEquals(
            listOf("audio.flac", "audio_sync.csv", "device_events.csv", "imu.csv.gz", "level.csv.gz"),
            first.files,
        )
        assertTrue(first.hasAudio)
        assertEquals(3.049, first.durationS, 0.0)
        // gzip: no time stamp, no name (RFC 1952 header: MTIME 0, FLG 0)
        for (gz in listOf(Finalizer.IMU, Finalizer.LEVEL)) {
            val h = a[gz]!!
            assertEquals(0x1f, h[0].toInt() and 0xff)
            assertEquals(0, h[3].toInt())
            assertArrayEquals(ByteArray(4), h.copyOfRange(4, 8))
        }
        assertEquals(listOf(first, second), labelled)
    }

    @Test
    fun whatItWritesFitsTheSchemas() {
        for (name in FinalizerFixtures.names) {
            val j = FinalizerFixtures.install(store, name)
            if (j.state == JournalState.RECORDING) store.recover() else store.finalize(j.recordingId)
            val dir = store.dir(j.recordingId)
            assertEquals(name, emptyList<String>(), metaSchema.problems(FinalizerFixtures.parse(File(dir, Finalizer.META))))
            assertEquals(name, emptyList<String>(), eventsSchema.problems(FinalizerFixtures.parse(File(dir, Finalizer.EVENTS))))
        }
        // the schema check finds what is wrong
        val bad = JsonObject(meta(FinalizerFixtures.journal("golden").recordingId) + ("extra" to JsonPrimitive(1)))
        assertTrue(metaSchema.problems(bad).toString(), metaSchema.problems(bad).any { "unexpected extra" in it })
    }

    @Test
    fun labelsWrittenForTheDataAreKept() {
        val j = FinalizerFixtures.install(store, "rich")
        val first = store.finalize(j.recordingId)!!
        val dir = store.dir(j.recordingId)
        val events = FinalizerFixtures.parse(File(dir, Finalizer.EVENTS)).jsonObject
        val cov = events["coverage_us"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
        val labelled = JsonObject(
            events + mapOf(
                "revision" to JsonPrimitive(1),
                "events" to buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "e1")
                            put("type", "motor_on")
                            put("t_us", cov[0] + 100_000)
                            put("source", "user")
                            put("confidence", 1.0)
                        },
                    )
                },
            ),
        )
        val review = buildJsonObject {
            put("status", "reviewed")
            put("detector", "dbauto/1.2.0")
            put("revision", 1)
            put("edited_events", 1)
            put("reviewer", "k7q2")
        }
        val derived = buildJsonObject {
            put("orientation", "floor")
            put("off_angle_deg", 0.4)
            putJsonArray("tags") {}
        }
        val before = files(dir)
        val metaBefore = meta(j.recordingId)
        Finalizer.writeLabels(dir, labelled, derived, review)
        assertEquals(RecordingStage.SAVED, stage(j.recordingId))
        val m = meta(j.recordingId)
        assertEquals(review, m["review"])
        assertEquals(derived, m["derived"])
        assertEquals(PyJson.dump(labelled), File(dir, Finalizer.EVENTS).readText())
        // the data files, their hashes and the rest of meta.json are untouched
        for (f in first.files) assertArrayEquals(f, before[f], File(dir, f).readBytes())
        assertEquals(metaBefore.keys.toList(), m.keys.toList())
        for (k in metaBefore.keys - setOf("derived", "review")) assertEquals(k, metaBefore[k], m[k])
        assertEquals(emptyList<String>(), metaSchema.problems(m))
        assertEquals(emptyList<String>(), eventsSchema.problems(labelled))

        // finalized again (recovery, a second Save): the labels stay
        val again = store.finalize(j.recordingId)!!
        assertTrue(again.labelsKept)
        assertEquals(PyJson.dump(labelled), File(dir, Finalizer.EVENTS).readText())
        assertEquals(review, meta(j.recordingId)["review"])
        assertEquals(RecordingStage.SAVED, stage(j.recordingId))

        // other data (here: the journal cut shorter): an empty label set again
        val stream = File(dir, RecordingStore.STREAM)
        stream.writeBytes(stream.readBytes().copyOf(150_000))
        val cut = store.finalize(j.recordingId)!!
        assertFalse(cut.labelsKept)
        val fresh = FinalizerFixtures.parse(File(dir, Finalizer.EVENTS)).jsonObject
        assertEquals(0, fresh["events"]!!.jsonArray.size)
        assertEquals("auto_only", meta(j.recordingId)["review"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(RecordingStage.NEEDS_REVIEW, stage(j.recordingId))
    }

    private fun journal(id: String, takeId: Long = 7, state: JournalState = JournalState.COMPLETE, app: AppInfo = AppInfo("0.6.0", "Pixel 8", 35)) =
        TakeJournal(
            recordingId = id, sessionId = "20261026T1430_k7q2", takeId = takeId, reviewer = "k7q2", state = state,
            startedUtc = "2026-10-26T14:30:12.418Z", setup = SessionSetup(material = "metal/steel", mode = "drill"),
            take = TakeSettings("RAW448", 0, 6, 300), app = app, link = "lan",
        )

    /** [takes] takes of IMU only (no microphones), 0.2 s each, one after the other. */
    private fun imuOnly(vararg takes: Long): ByteArray {
        var out = ByteArray(0)
        var t = TestStream.T0
        for (take in takes) {
            var seq = 0L
            out += TestStream.session(seq++, take, startUs = t, micMask = 0)
            var ts = 1000L
            repeat(10) {
                out += TestStream.imu(seq++, ts, 9, t + 9 * 2230)
                ts += 9
                t += 9 * 2230
            }
            out += TestStream.end(seq, t, RecReason.USER.wire)
            t += 100_000
        }
        return out
    }

    private fun install(j: TakeJournal, bytes: ByteArray) {
        store.create(j, 1_000) { 0L }.use { it.append(bytes, 0, bytes.size) }
    }

    @Test
    fun aTakeWithoutAudioAndTheRightTakeOfSeveral() {
        val id = "20261026T143012Z_k7q2_aaaa"
        // the journal holds an earlier take too (the take id picks the right one)
        install(journal(id, takeId = 8, app = AppInfo("e2e", "jvm", 0)), imuOnly(7, 8))
        val r = store.finalize(id)!!
        assertFalse(r.hasAudio)
        assertEquals(listOf("imu.csv.gz", "level.csv.gz"), r.files)
        val m = meta(id)
        assertEquals("null", m["audio"].toString())
        assertEquals("null", m["sync"].toString())
        assertEquals(8L, m["take"]!!.jsonObject["take_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("user", m["take"]!!.jsonObject["end_reason"]!!.jsonPrimitive.content)
        // take 8 starts 100 ms after take 7's 90 samples; its first sample one period after that
        assertEquals(TestStream.T0 + 90 * 2230 + 100_000 + 2230, m["device_t0_us"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-10-26T14:30:12.418Z", m["start_utc"]!!.jsonPrimitive.content)
        // an app without an SDK level (a JVM tool): no android_sdk
        assertEquals("{\"version\":\"e2e\",\"phone_model\":\"jvm\"}", m["app"].toString())
        assertEquals(emptyList<String>(), metaSchema.problems(m))
        assertEquals(setOf(Finalizer.META, Finalizer.EVENTS, Finalizer.IMU, Finalizer.LEVEL, RecordingStore.STREAM, RecordingStore.JOURNAL),
            store.dir(id).list()!!.toSet())

        // a file a former finalize wrote that this one does not: removed
        File(store.dir(id), Finalizer.AUDIO).writeText("stale")
        store.finalize(id)
        assertFalse(File(store.dir(id), Finalizer.AUDIO).exists())
    }

    @Test
    fun aTakeThatCannotBeARecordingSaysWhy() {
        // no SESSION: the IMU counts cannot be scaled
        val a = "20261026T143012Z_k7q2_aaaa"
        install(journal(a), TestStream.imu(1, 100, 9, TestStream.T0) + TestStream.imu(2, 109, 9, TestStream.T0 + 20_000))
        assertNull(store.finalize(a))
        assertEquals("the take has no SESSION record: profile and scales are unknown", store.read(a)!!.finalizeError)
        assertEquals(RecordingStage.INTERRUPTED, stage(a))
        assertEquals(setOf(RecordingStore.STREAM, RecordingStore.JOURNAL), store.dir(a).list()!!.toSet())
        // a SESSION and a single sample
        val b = "20261026T143112Z_k7q2_bbbb"
        install(journal(b, takeId = 7), TestStream.session(0, 7) + TestStream.imu(1, 100, 1, TestStream.T0))
        assertNull(store.finalize(b))
        assertEquals("the take has fewer than two IMU samples", store.read(b)!!.finalizeError)
        // nothing at all (an app that died before the first record)
        val c = "20261026T143212Z_k7q2_cccc"
        install(journal(c, state = JournalState.RECORDING), ByteArray(0))
        assertEquals(listOf(c), store.recover())
        assertEquals("the journal holds no records", store.read(c)!!.finalizeError)
        assertEquals(RecordingStage.INTERRUPTED, stage(c))
        // the failures are not tried again at every start
        assertTrue(store.pending().isEmpty())
        assertTrue(labelled.isEmpty())
        // a take being recorded is left alone
        val d = "20261026T143312Z_k7q2_dddd"
        val bytes = imuOnly(7)
        val writer = store.create(journal(d, state = JournalState.RECORDING), 1_000) { 0L }.apply { append(bytes, 0, bytes.size) }
        assertNull(store.finalize(d))
        assertNull(store.read(d)!!.finalizeError)
        assertEquals(RecordingStage.RECORDING, stage(d))
        assertEquals(listOf<String>(), store.recover())
        assertFalse(File(store.dir(d), Finalizer.META).exists())
        writer.close()
    }

    @Test
    fun recoveryFinalizesEveryFinishedTakeLeftWithoutFiles() {
        val a = "20261026T143012Z_k7q2_aaaa"
        val b = "20261026T143112Z_k7q2_bbbb"
        install(journal(a, takeId = 7), imuOnly(7))
        install(journal(b, takeId = 7, state = JournalState.INTERRUPTED), imuOnly(7))
        assertEquals(setOf(a, b), store.pending().toSet())
        assertEquals(listOf<String>(), store.recover())
        assertTrue(File(store.dir(a), Finalizer.META).isFile && File(store.dir(b), Finalizer.META).isFile)
        assertEquals(2, labelled.size)
        assertTrue(store.pending().isEmpty())
        assertEquals(RecordingStage.NEEDS_REVIEW, stage(b))
        assertTrue(store.changes.value >= 2)
        val entry = store.list().first()
        assertTrue(entry.finalized)
        assertTrue(entry.bytes > entry.streamBytes)
    }

    /**
     * A storage error is not the take's fault: it is thrown and kept in memory only (no
     * finalize_error, so it is tried again), recover() finalizes the other takes anyway, and
     * retry() makes the files once storage works again.
     */
    @Test
    fun aStorageErrorIsTriedAgainNotRecorded() {
        val a = "20261026T143012Z_k7q2_aaaa"
        val b = "20261026T143112Z_k7q2_bbbb"
        val c = "20261026T143212Z_k7q2_cccc"
        val failing = HashSet(setOf(b))
        val s = RecordingStore(File(tmp.root, "failing"), finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult =
                if (journal.recordingId in failing) throw java.io.IOException("No space left on device") else super.finalize(dir, journal)
        })
        for (id in listOf(a, b, c)) s.create(journal(id), 1_000) { 0L }.use { w -> imuOnly(7).let { w.append(it, 0, it.size) } }
        val changes = s.changes.value
        val e = assertThrows(java.io.IOException::class.java) { s.recover() }
        assertEquals("No space left on device", e.message)
        assertTrue("the others are finalized", File(s.dir(a), Finalizer.META).isFile && File(s.dir(c), Finalizer.META).isFile)
        assertFalse(File(s.dir(b), Finalizer.META).exists())
        assertNull("not the take's fault", s.read(b)!!.finalizeError)
        assertEquals("No space left on device", s.storageError(b))
        val entry = s.list().single { it.journal.recordingId == b }
        assertEquals("not waiting for a review", RecordingStage.INTERRUPTED, entry.stage)
        assertEquals("No space left on device", entry.storageError)
        assertNull(s.storageError(a))
        assertEquals(listOf(b), s.pending())
        assertEquals("each try counts, the failed one too", changes + 3, s.changes.value)
        failing.clear()
        assertNotNull(s.retry(b))
        assertTrue(File(s.dir(b), Finalizer.META).isFile)
        assertNull(s.storageError(b))
    }

    /** A journal left recording because it could not be closed (storageFailed): retry cuts it, marks it interrupted and finalizes it. */
    @Test
    fun aJournalThatCouldNotBeClosedIsFinalizedOnRetry() {
        val d = "20261026T143312Z_k7q2_dddd"
        install(journal(d, state = JournalState.RECORDING), imuOnly(7))
        assertNull("being recorded: left alone", store.retry(d))
        assertEquals(JournalState.RECORDING, store.read(d)!!.state)
        assertEquals(RecordingStage.RECORDING, stage(d))
        store.storageFailed(d, java.io.IOException("disk full"))
        assertEquals("disk full", store.storageError(d))
        assertEquals("not recording any more", RecordingStage.INTERRUPTED, stage(d))
        assertNotNull(store.retry(d))
        assertEquals(JournalState.INTERRUPTED, store.read(d)!!.state)
        assertTrue(File(store.dir(d), Finalizer.META).isFile)
        assertNull(store.storageError(d))
    }

    /**
     * The finalizer running out of memory is recorded (finalize_error, not tried at every start:
     * recover() goes on with the takes after it, and the next start does not try it again), but
     * as retryable (finalize_retry): retry() makes it, and clears both.
     */
    @Test
    fun runningOutOfMemoryFinalizingIsRecordedAndTheOthersGoOn() {
        val a = "20261026T143012Z_k7q2_aaaa"
        val b = "20261026T143112Z_k7q2_bbbb"
        var tries = 0
        var heap = false
        val finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult {
                if (journal.recordingId == b) tries++
                return if (journal.recordingId == b && !heap) throw OutOfMemoryError("Failed to allocate") else super.finalize(dir, journal)
            }
        }
        val s = RecordingStore(File(tmp.root, "oom"), finalizer = finalizer)
        for (id in listOf(a, b)) s.create(journal(id), 1_000) { 0L }.use { w -> imuOnly(7).let { w.append(it, 0, it.size) } }
        s.recover()          // b first (newest)
        assertTrue(File(s.dir(a), Finalizer.META).isFile)
        assertEquals("the app ran out of memory finalizing it", s.read(b)!!.finalizeError)
        assertTrue("retryable", s.read(b)!!.finalizeRetry)
        assertEquals(RecordingStage.INTERRUPTED, s.list().first { it.journal.recordingId == b }.stage)
        assertEquals(listOf<String>(), s.pending())
        // the next start (another store on the same folder) does not try it again: no crash loop
        RecordingStore(s.root, finalizer = finalizer).recover()
        assertEquals(1, tries)
        assertEquals(true, s.read(b)!!.finalizeRetry)
        assertFalse(File(s.dir(b), Finalizer.META).isFile)
        // Try again (the review's), still out of memory: still retryable
        assertNull(s.retry(b))
        assertEquals(2, tries)
        assertEquals(true, s.read(b)!!.finalizeRetry)
        // with the memory there: made, the marker gone
        heap = true
        assertNotNull(s.retry(b))
        assertTrue(File(s.dir(b), Finalizer.META).isFile)
        assertEquals(null, s.read(b)!!.finalizeError)
        assertFalse(s.read(b)!!.finalizeRetry)
        assertEquals(RecordingStage.NEEDS_REVIEW, s.list().first { it.journal.recordingId == b }.stage)
    }

    /** A bug in the finalizer (or the take) is not retryable: finalize_retry stays false. */
    @Test
    fun aFinalizerBugIsNotRetryable() {
        val a = "20261026T143012Z_k7q2_aaaa"
        val s = RecordingStore(File(tmp.root, "bug"), finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult = throw IllegalStateException("bug")
        })
        s.create(journal(a), 1_000) { 0L }.use { w -> imuOnly(7).let { w.append(it, 0, it.size) } }
        s.recover()
        assertEquals("the app could not finalize it (bug)", s.read(a)!!.finalizeError)
        assertFalse(s.read(a)!!.finalizeRetry)
    }

    /**
     * Out of memory first, then Try again gets further and fails for good (the take, or a bug):
     * not retryable any more, the review shows the real reason and no Try again.
     */
    @Test
    fun aRetryAfterRunningOutOfMemoryThatFailsForGoodIsNotRetryable() {
        val a = "20261026T143012Z_k7q2_aaaa"
        var next: () -> Nothing = { throw OutOfMemoryError("Failed to allocate") }
        val s = RecordingStore(File(tmp.root, "oom_then"), finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult = next()
        })
        s.create(journal(a), 1_000) { 0L }.use { w -> imuOnly(7).let { w.append(it, 0, it.size) } }
        s.recover()
        assertTrue(s.read(a)!!.finalizeRetry)
        for ((fail, reason) in listOf<Pair<() -> Nothing, String>>(
            { throw FinalizeException("no usable IMU data") } to "no usable IMU data",
            { throw IllegalStateException("bug") } to "the app could not finalize it (bug)",
        )) {
            next = { throw OutOfMemoryError("Failed to allocate") }
            assertNull(s.retry(a))
            assertTrue(s.read(a)!!.finalizeRetry)
            next = fail
            assertNull(s.retry(a))
            assertEquals(reason, s.read(a)!!.finalizeError)
            assertFalse(reason, s.read(a)!!.finalizeRetry)
            assertEquals(ReviewSource.Failed(reason, retry = false), ReviewRepository(s, null).source(a))
        }
    }

    /**
     * Out of memory first, then Try again fails on storage: the review shows the storage error
     * (the last try's, kept in memory), not the out-of-memory marker take.json still has.
     */
    @Test
    fun aRetryAfterRunningOutOfMemoryThatFailsOnStorageShowsTheStorageError() {
        val a = "20261026T143012Z_k7q2_aaaa"
        var full = false
        val s = RecordingStore(File(tmp.root, "oom_full"), finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult =
                if (full) throw java.io.IOException("No space left on device") else throw OutOfMemoryError("Failed to allocate")
        })
        s.create(journal(a), 1_000) { 0L }.use { w -> imuOnly(7).let { w.append(it, 0, it.size) } }
        s.recover()
        val repo = ReviewRepository(s, null)
        assertEquals(ReviewSource.Failed(RecordingStore.OUT_OF_MEMORY, retry = true, outOfMemory = true), repo.source(a))
        full = true
        assertThrows(java.io.IOException::class.java) { s.retry(a) }
        assertEquals(ReviewSource.Failed("No space left on device", retry = true), repo.source(a))
    }

    @Test
    fun theLabelsOfTheSetup() {
        fun labels(s: SessionSetup) = Finalizer.sessionLabels(s).toString()
        assertEquals(
            "{\"material\":\"unknown\",\"mode\":\"drill\",\"bit\":{\"type\":\"unknown\",\"diameter_mm\":null}," +
                "\"drill\":{\"type\":\"unknown\",\"motor\":\"unknown\",\"model\":\"\",\"gear\":null,\"speed_setting\":null},\"notes\":\"\"}",
            labels(SessionSetup()),
        )
        // the tool, in dbrec's key order; each "other" text in other_text, in dbrec's order
        assertEquals(
            "{\"material\":\"other\",\"layers\":[{\"material\":\"other\",\"thickness_mm\":3.0}],\"mode\":\"screw\"," +
                "\"bit\":{\"type\":\"other\",\"diameter_mm\":null}," +
                "\"drill\":{\"type\":\"other\",\"motor\":\"other\",\"model\":\"M\",\"gear\":1,\"speed_setting\":null},\"notes\":\"\"," +
                "\"other_text\":{\"material\":\"Cork\",\"layers[0]\":\"Cork\",\"bit_type\":\"PZ2\",\"drill_type\":\"Magnetic drill\",\"motor\":\"Air\"}}",
            labels(
                SessionSetup(
                    material = "other", materialOther = "Cork", thicknessMm = 3.0, mode = "screw", bitType = "other", bitOther = "PZ2",
                    drillType = "other", drillTypeOther = "Magnetic drill", motor = "other", motorOther = "Air", drillModel = "M", gear = 1,
                ),
            ),
        )
        assertTrue("\"drill\":{\"type\":\"impact_driver\",\"motor\":\"universal_ac\"" in labels(SessionSetup(drillType = "impact_driver", motor = "universal_ac")))
        // "other" without its text is unknown, for the tool too (and no other_text)
        val blank = labels(SessionSetup(drillType = "other", drillTypeOther = " ", motor = "other"))
        assertTrue(blank, "\"drill\":{\"type\":\"unknown\",\"motor\":\"unknown\"" in blank && "other_text" !in blank)
        // "other" without its text is unknown; cut to the contract's lengths
        val s = labels(SessionSetup(material = "other", bitType = "other", bitOther = "x".repeat(50), notes = "n".repeat(300), gear = 5, bitDiameterMm = 300.0))
        assertTrue(s, s.startsWith("{\"material\":\"unknown\""))
        assertTrue(s, "\"bit_type\":\"${"x".repeat(40)}\"" in s)
        assertTrue(s, "\"notes\":\"${"n".repeat(200)}\"" in s)
        assertTrue(s, "\"gear\":null" in s && "\"diameter_mm\":null" in s)
    }

    @Test
    fun firmwareTextAndFilters() {
        assertEquals("0.3.0" to "0e5105e", Finalizer.fwParts("0.3.0-7-g0e5105e"))
        assertEquals("0.1.0" to "0e5105e-dirty", Finalizer.fwParts("v0.1.0-g0e5105e-dirty"))
        assertEquals("0.2.0" to "", Finalizer.fwParts("0.2.0"))
        assertEquals("nightly" to "", Finalizer.fwParts("nightly"))
        assertEquals("off", Finalizer.lpfText(0x00, accel = true))
        assertEquals("13.37% of ODR", Finalizer.lpfText(0x07, accel = true))
        assertEquals("2.66% of ODR", Finalizer.lpfText(0x10, accel = false))
        assertTrue(Finalizer.axisMapOk(listOf(1 to 1, 0 to -1, 2 to 1)))
        assertFalse(Finalizer.axisMapOk(listOf(1 to 1, 1 to -1, 2 to 1)))
        assertFalse(Finalizer.axisMapOk(listOf(0 to 2, 1 to 1, 2 to 1)))
    }

    @Test
    fun pythonsFloatText() {
        for ((d, text) in listOf(
            0.0 to "0.0", -0.0 to "-0.0", 1.0 to "1.0", 0.5 to "0.5", 448.4 to "448.4", 1e-05 to "1e-05", 0.0001 to "0.0001",
            6.2e-05 to "6.2e-05", 2000150229.8 to "2000150229.8", 1e16 to "1e+16", 1.5e300 to "1.5e+300", 123456789012345.6 to "123456789012345.6",
            62.498503 to "62.498503", 1.0E15 to "1000000000000000.0", -3.25 to "-3.25", 0.1 + 0.2 to "0.30000000000000004",
        )) assertEquals(text, PyJson.repr(d))
        // Python's repr of the edge cases (subnormals, powers of two, the largest, 2^53 + 1)
        for ((bits, text) in listOf(
            1L to "5e-324", 0x0010000000000000L to "2.2250738585072014e-308", java.lang.Double.doubleToLongBits(Math.pow(2.0, -1000.0)) to "9.332636185032189e-302",
        )) assertEquals(text, PyJson.repr(java.lang.Double.longBitsToDouble(bits)))
        for ((d, text) in listOf(
            Math.pow(2.0, 60.0) to "1.152921504606847e+18", Math.pow(2.0, 100.0) to "1.2676506002282294e+30",
            Double.MAX_VALUE to "1.7976931348623157e+308", 9007199254740993.0 to "9007199254740992.0", 0.1 to "0.1",
            1.0 / 3 to "0.3333333333333333", 2.0 / 3 to "0.6666666666666666", 1e23 to "1e+23", 8.41e21 to "8.41e+21", 5e-310 to "5e-310",
        )) assertEquals(text, PyJson.repr(d))
        // the shortest digits, as the JDK's Double.toString has them since JDK 19
        val rnd = java.util.Random(5)
        repeat(20_000) {
            val d = java.lang.Double.longBitsToDouble(rnd.nextLong()).takeIf { it.isFinite() } ?: 1.5
            assertEquals(java.math.BigDecimal(d.toString()).stripTrailingZeros(), java.math.BigDecimal(PyJson.repr(d)).stripTrailingZeros())
        }
        assertEquals(2.67, pyRound(2.675, 2), 0.0)     // 2.675 is 2.67499999... in binary: Python gives 2.67
        assertEquals(0.12, pyRound(0.125, 2), 0.0)     // exactly half: to even
        assertEquals(2L, pyRoundLong(2.5))
        assertEquals(4L, pyRoundLong(3.5))
    }
}
