package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.Labeler
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.export.PyJson
import dev.drillbalancer.recorder.export.SchemaCheck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** The app's labeller: after a finalize, through RecordingStore, and its determinism and speed. */
class AutoLabelerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val errors = ArrayList<Throwable>()
    private val labeler = AutoLabeler({ TestParams.loaded }) { _, e -> errors += e }
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private val metaSchema = SchemaCheck("meta.v1.schema.json")
    private val eventsSchema = SchemaCheck("events.v1.schema.json")

    private fun json(f: File): JsonObject = FinalizerFixtures.parse(f).jsonObject

    /** A finalized take gets the detector's labels, meta.review auto_only by this detector, and the review's files. */
    @Test
    fun finalizeLabelsTheTake() {
        for (name in listOf("golden", "rich")) {
            val j = FinalizerFixtures.install(store, name)
            val r = store.finalize(j.recordingId)!!
            val dir = store.dir(j.recordingId)
            val events = json(File(dir, Finalizer.EVENTS))
            val meta = json(File(dir, Finalizer.META))
            assertEquals(name, listOf<String>(), eventsSchema.problems(events))
            assertEquals(name, listOf<String>(), metaSchema.problems(meta))
            val labels = Labels.fromJson(events)
            assertEquals(DetectorInfo("dbauto/1.1.0", TestParams.loaded.sha256), labels.detector)
            assertEquals(1, labels.revision)
            assertEquals(listOf<String>(), LabelCheck.errors(labels).map { it.message })
            val review = meta["review"]!!.jsonObject
            assertEquals("\"auto_only\"", review["status"].toString())
            assertEquals("\"dbauto/1.1.0\"", review["detector"].toString())
            assertEquals("1", review["revision"].toString())
            // meta.derived is what the labels give
            val (derived, _) = Derived.compute(RecordingFiles.readLevel(File(dir, Finalizer.LEVEL)), labels, RecordingFiles.read(dir).inputs.tolDeg)
            assertEquals(derived.toJson(), meta["derived"])
            assertEquals(labels, LabelFiles.readLabels(File(dir, LabelFiles.AUTO)))
            assertNotNull(Lanes.read(File(dir, LabelFiles.LANES)))
            assertEquals(RecordingStage.NEEDS_REVIEW, store.list().first { it.journal.recordingId == j.recordingId }.stage)
            assertTrue(r.files.isNotEmpty())
        }
        assertEquals(listOf<Throwable>(), errors)
    }

    /** A second finalize of the same journal keeps the labels: the labeller does nothing. */
    @Test
    fun keptLabelsAreNotRelabelled() {
        val j = FinalizerFixtures.install(store, "rich")
        store.finalize(j.recordingId)
        val dir = store.dir(j.recordingId)
        val before = File(dir, Finalizer.EVENTS).readBytes()
        File(dir, LabelFiles.DRAFT).writeText("{}")
        val r = store.finalize(j.recordingId)!!
        assertTrue(r.labelsKept)
        assertArrayEquals(before, File(dir, Finalizer.EVENTS).readBytes())
        assertTrue("a kept label set keeps its draft", File(dir, LabelFiles.DRAFT).isFile)
    }

    /** The same files give the same labels, to the byte, and the same features. */
    @Test
    fun deterministic() {
        val id = LabelTestData.install(store, "fx_stuck_kick")
        val dir = store.dir(id)
        val a = labeler.relabel(dir)
        val bytesA = File(dir, LabelFiles.AUTO).readBytes()
        val b = labeler.relabel(dir)
        assertEquals(a.labels.copy(revision = 0), b.labels.copy(revision = 0))
        assertEquals(a.derived, b.derived)
        for (f in Feature.entries) assertArrayEquals(f.name, a.features[f], b.features[f], 0.0)
        assertEquals(PyJson.dump(a.labels.copy(revision = b.labels.revision).toJson()), File(dir, LabelFiles.AUTO).readText())
        assertTrue(bytesA.isNotEmpty())
        val inputs = RecordingFiles.read(dir).inputs
        assertEquals(AutoDetect.detect(inputs, TestParams.loaded).labels, AutoDetect.detect(inputs, TestParams.loaded).labels)
    }

    /** A recording that cannot be read keeps its empty labels; the failure goes to onError. */
    @Test
    fun failureIsReported() {
        val j = FinalizerFixtures.install(store, "golden")
        val dir = store.dir(j.recordingId)
        val bad = AutoLabeler({ error("no params") }) { _, e -> errors += e }
        val s = RecordingStore(store.root, labeler = bad)
        assertNotNull(s.finalize(j.recordingId))
        assertEquals(1, errors.size)
        val labels = LabelFiles.readLabels(File(dir, Finalizer.EVENTS))!!
        assertEquals(null, labels.detector)
        assertEquals(listOf<Marker>(), labels.events)
        // the review labels it then
        val data = ReviewRepository(store, labeler).load(j.recordingId)
        assertEquals("dbauto/1.1.0", data.saved.detector?.version)
        assertEquals("\"dbauto/1.1.0\"", json(File(dir, Finalizer.META))["review"]!!.jsonObject["detector"].toString())
    }

    /**
     * Labelling a take too long for the heap runs out of memory: reported like any failure, the
     * finalize returns, and the recovery goes on with the takes after it.
     */
    @Test
    fun runningOutOfMemoryLabellingDoesNotStopTheRecovery() {
        val oom = AutoLabeler({ throw OutOfMemoryError("Failed to allocate") }) { _, e -> errors += e }
        val s = RecordingStore(File(tmp.root, "oom"), labeler = oom)
        val ids = listOf("golden", "rich").map { FinalizerFixtures.install(s, it).recordingId }
        s.recover()
        for (id in ids) assertTrue(id, File(s.dir(id), Finalizer.META).isFile)
        assertEquals(2, errors.size)
        assertTrue(errors.all { it is OutOfMemoryError })
        assertEquals(listOf<String>(), s.pending())
    }

    /**
     * lanes.bin whose header does not fit the file (the power went after its rename, before its
     * data was on storage): computed again, not read (a huge frame count would not even allocate).
     */
    @Test
    fun aBrokenLanesFileIsComputedAgain() {
        val j = FinalizerFixtures.install(store, "rich")
        store.finalize(j.recordingId)
        val dir = store.dir(j.recordingId)
        val f = File(dir, LabelFiles.LANES)
        val good = f.readBytes()
        val lanes = Lanes.read(f)!!
        assertEquals(good.size.toLong(), 32L + 4L * lanes.n * Lanes.FEATURES.size)
        for (n in listOf(Int.MAX_VALUE, 0x40000000, lanes.n + 1, lanes.n - 1)) {
            val bad = good.copyOf()
            java.nio.ByteBuffer.wrap(bad).putInt(24, n)
            f.writeBytes(bad)
            assertEquals("n = $n", null, Lanes.read(f))
            assertEquals("n = $n", lanes.n, labeler.lanes(dir).n)
            assertArrayEquals("n = $n: written again", good, f.readBytes())
        }
        f.writeBytes(good.copyOf(good.size - 3))
        assertEquals("cut", null, Lanes.read(f))
        assertFalse(File(dir, "${LabelFiles.LANES}.tmp").exists())
    }

    /**
     * The review of a recording finalized before opens (and saves) while the background
     * finalize and labeller work on another take: they hold that take's files, not every
     * recording's.
     */
    @Test
    fun aReviewDoesNotWaitForAnotherTakesFinalize() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = Labeler { dir, r ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            labeler.label(dir, r)
        }
        val s = RecordingStore(File(tmp.root, "two"), labeler = blocking)
        val old = FinalizerFixtures.install(s, "golden").recordingId
        RecordingStore(s.root, labeler = labeler).finalize(old)
        val busy = FinalizerFixtures.install(s, "rich").recordingId
        val background = thread { s.finalize(busy) }
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS))
            val repo = ReviewRepository(s, labeler)
            var saved: Labels? = null
            val review = thread {
                val data = repo.load(old)
                saved = repo.save(data, LabelEdits.confirmAll(data.labels), 0)
            }
            review.join(10_000)
            assertFalse("load and save waited for the other take", review.isAlive)
            assertEquals(2, saved!!.revision)
            assertEquals(ReviewSource.NotReady, repo.source(busy))
        } finally {
            release.countDown()
            background.join(30_000)
        }
        assertEquals(listOf<Throwable>(), errors)
    }

    /**
     * The heavy computations (a recording read whole, the detector, the features) of the
     * background labeller and of reviews run one at a time, whoever asks: each under the shared
     * compute lock, never two at once. Four at the same moment: the background labelling a take,
     * a review labelling an unlabelled one, two reviews computing lost lanes again.
     */
    @Test
    fun heavyComputationsNeverOverlap() {
        val compute = ReentrantLock()
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val unlocked = AtomicInteger()
        val calls = AtomicInteger()
        val counting = AutoLabeler({
            calls.incrementAndGet()
            if (!compute.isHeldByCurrentThread) unlocked.incrementAndGet()
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            Thread.sleep(50)
            inside.decrementAndGet()
            TestParams.loaded
        }, compute) { _, e -> errors += e }
        val s = RecordingStore(File(tmp.root, "serial"), labeler = counting)
        val unlabelled = FinalizerFixtures.install(s, "golden").recordingId.also { RecordingStore(s.root, labeler = { _, _ -> }).finalize(it) }
        val noLanes = listOf("fx_gap", "fx_noaudio").map { name ->
            LabelTestData.install(s, name, labeler).also { assertTrue(File(s.dir(it), LabelFiles.LANES).delete()) }
        }
        val busy = FinalizerFixtures.install(s, "rich").recordingId
        val repo = ReviewRepository(s, counting)
        val go = CountDownLatch(1)
        val jobs = listOf<() -> Unit>({ s.finalize(busy) }, { repo.load(unlabelled) }) + noLanes.map { id -> { repo.load(id); Unit } }
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val threads = jobs.map { job ->
            thread {
                go.await()
                try {
                    job()
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        go.countDown()
        threads.forEach { it.join(60_000) }
        assertEquals(listOf<Throwable>(), failures.toList())
        assertEquals("every one computed", 4, calls.get())
        assertEquals("never two at once", 1, most.get())
        assertEquals("all under the compute lock", 0, unlocked.get())
        assertFalse(compute.isLocked)
        for (id in noLanes) assertNotNull(Lanes.read(File(s.dir(id), LabelFiles.LANES)))
        assertEquals("dbauto/1.1.0", LabelFiles.readLabels(File(s.dir(busy), Finalizer.EVENTS))!!.detector?.version)
        assertEquals(listOf<Throwable>(), errors)
    }

    /**
     * While the background labeller of one take runs the detector (it holds the compute lock),
     * a review that has to label another take, or compute its lanes again, waits for it; the
     * review of a labelled take with its lanes needs no heavy computation and does not wait.
     */
    @Test
    fun aReviewWaitsForTheHeavyComputationOnlyWhenItNeedsOne() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val compute = ReentrantLock()
        val gated = AutoLabeler({
            if (first.getAndSet(false)) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            TestParams.loaded
        }, compute) { _, e -> errors += e }
        val s = RecordingStore(File(tmp.root, "gated"), labeler = gated)
        val labelled = LabelTestData.install(s, "fx_gap", labeler)
        val noLanes = LabelTestData.install(s, "fx_noaudio", labeler).also { assertTrue(File(s.dir(it), LabelFiles.LANES).delete()) }
        val unlabelled = FinalizerFixtures.install(s, "golden").recordingId.also { RecordingStore(s.root, labeler = { _, _ -> }).finalize(it) }
        val busy = FinalizerFixtures.install(s, "rich").recordingId
        val background = thread { s.finalize(busy) }
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS))
            assertTrue("the background labeller holds the compute lock", compute.isLocked)
            val repo = ReviewRepository(s, gated)
            var saved: Labels? = null
            var before = -1
            val quick = thread {
                val data = repo.load(labelled)
                before = data.saved.revision
                saved = repo.save(data, LabelEdits.confirmAll(data.labels), 0)
            }
            quick.join(10_000)
            assertFalse("a labelled review waited for another take's detector", quick.isAlive)
            assertEquals(before + 1, saved!!.revision)
            var labelledNow: ReviewData? = null
            var lanes: ReviewData? = null
            val labelling = thread { labelledNow = repo.load(unlabelled) }
            val computing = thread { lanes = repo.load(noLanes) }
            labelling.join(300)
            computing.join(1)
            assertTrue("a review that labels waits", labelling.isAlive)
            assertTrue("a review that computes its lanes waits", computing.isAlive)
            release.countDown()
            labelling.join(30_000)
            computing.join(30_000)
            assertEquals("dbauto/1.1.0", labelledNow!!.saved.detector?.version)
            assertTrue(lanes!!.lanes.n > 0)
        } finally {
            release.countDown()
            background.join(30_000)
        }
        assertEquals(listOf<Throwable>(), errors)
    }

    /**
     * The app died between a label write's renames (Finalizer.writeLabels: events.json, then
     * meta.json): events.json holds the review's labels, meta.json the derived and review from
     * before. A finalize again, the start's recovery and the review all finish the pair from the
     * complete meta.json.tmp, so meta.review and meta.derived are those of events.json.
     */
    @Test
    fun aLabelWriteCutBetweenItsRenamesIsFinished() {
        val j = FinalizerFixtures.install(store, "rich")
        val id = j.recordingId
        store.finalize(id)
        val dir = store.dir(id)
        val meta = File(dir, Finalizer.META)
        val before = meta.readBytes()
        val repo = ReviewRepository(store, labeler)
        val data = repo.load(id)
        val saved = repo.save(data, LabelEdits.confirmAll(data.labels), 0)
        assertEquals(2, saved.revision)
        val after = meta.readBytes()
        fun cut() {
            meta.copyTo(File(dir, "${Finalizer.META}.tmp"), overwrite = true)
            meta.writeBytes(before)
        }
        fun assertWhole(what: String) {
            assertArrayEquals(what, after, meta.readBytes())
            assertTrue(what, File(dir, "${Finalizer.META}.tmp").exists().not())
            val m = json(meta)
            assertEquals(what, "2", m["review"]!!.jsonObject["revision"].toString())
            assertEquals(what, listOf<String>(), metaSchema.problems(m))
        }
        cut()
        assertTrue(store.finalize(id)!!.labelsKept)
        assertWhole("finalized again")
        cut()
        store.recover()
        assertWhole("recovered")
        cut()
        assertEquals("reviewed", repo.load(id).reviewStatus)
        assertWhole("opened by the review")
        // a meta.json.tmp that is not this pair's (another meta, its derived and review left aside) is not taken
        File(dir, "${Finalizer.META}.tmp").writeText("{\"recording_id\":\"other\"}")
        meta.writeBytes(before)
        assertFalse(Finalizer.repairLabels(dir))
        assertArrayEquals(before, meta.readBytes())
    }

    /**
     * The app died after the labeller kept the detector's labels (events.auto.json) but before
     * they replaced the empty set: the review labels the recording when it opens, not the empty set.
     */
    @Test
    fun anEmptyLabelSetIsLabelledEvenWithTheDetectorsCopyThere() {
        val j = FinalizerFixtures.install(store, "golden")
        val dir = store.dir(j.recordingId)
        RecordingStore(store.root, labeler = { _, _ -> }).finalize(j.recordingId)
        val empty = LabelFiles.readLabels(File(dir, Finalizer.EVENTS))!!
        assertEquals(null, empty.detector)
        LabelFiles.writeLabels(File(dir, LabelFiles.AUTO), empty.copy(revision = 1, detector = DetectorInfo("dbauto/1.1.0")))
        val data = ReviewRepository(store, labeler).load(j.recordingId)
        assertEquals("dbauto/1.1.0", data.saved.detector?.version)
        assertEquals(data.saved, LabelFiles.readLabels(File(dir, Finalizer.EVENTS)))
    }

    /**
     * Opened while the background labeller still runs (a few seconds after the take): the review
     * waits (NotReady, and load waits for it) instead of running the detector a second time on
     * the same files at once.
     */
    @Test
    fun theReviewWaitsForTheBackgroundLabeller() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = Labeler { dir, r ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            labeler.label(dir, r)
        }
        val s = RecordingStore(File(tmp.root, "racing"), labeler = blocking)
        val j = FinalizerFixtures.install(s, "rich")
        val id = j.recordingId
        val background = thread { s.finalize(id) }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        val repo = ReviewRepository(s, labeler)
        assertEquals(ReviewSource.NotReady, repo.source(id))
        var loaded: ReviewData? = null
        val review = thread { loaded = repo.load(id) }
        review.join(300)
        assertTrue("load waits for the labeller", review.isAlive)
        release.countDown()
        background.join(30_000)
        review.join(30_000)
        assertEquals(ReviewSource.Ready, repo.source(id))
        assertEquals("one detector pass", 1, loaded!!.saved.revision)
        assertEquals(1, LabelFiles.readLabels(File(s.dir(id), Finalizer.EVENTS))!!.revision)
        assertEquals(listOf<Throwable>(), errors)
    }

    /**
     * A 20-minute take (fx_drill_through end to end, about 150 holes) is labelled in seconds on
     * the JVM: read, detect and written. The time is printed (the report of the run).
     */
    @Test
    fun twentyMinuteTakeIsFast() {
        val dir = File(tmp.root, "long")
        LabelTestData.longTake(dir, 20.0)
        val rec = RecordingFiles.read(dir)
        val minutes = (rec.inputs.tUs.last() - rec.inputs.tUs.first()) / 60e6
        assertTrue("$minutes min", minutes > 19.5)
        val t0 = System.nanoTime()
        val det = labeler.relabel(dir)
        val s = (System.nanoTime() - t0) / 1e9
        val holes = det.labels.holes.size
        println(String.format(java.util.Locale.ROOT, "AutoLabelerTest: a %.1f-minute take (%d IMU samples, %d audio frames) labelled in %.1f s: %d events, %d holes",
            minutes, rec.inputs.n, rec.inputs.audio!!.size, s, det.labels.events.size, holes,
        ))
        assertTrue("$holes holes", holes > 100)
        assertTrue("took $s s", s < 60.0)
        assertTrue((json(File(dir, Finalizer.META))["review"] as JsonObject)["status"] == JsonPrimitive("auto_only"))
    }
}
