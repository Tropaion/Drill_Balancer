package dev.drillbalancer.ui.training.review

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.TakeJournal
import dev.drillbalancer.recorder.export.FinalizeResult
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.export.SchemaCheck
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.Derived
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.RecordingFiles
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.ReviewSource
import dev.drillbalancer.recorder.label.Source
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.components.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
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

/** The review flow on real recordings (the autodetect fixtures, labelled by the app's detector). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val labeler = AutoLabeler({ TestParams.loaded })
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private val repo by lazy { ReviewRepository(store, labeler) }

    private fun open(name: String): Pair<String, ReviewViewModel> {
        val id = LabelTestData.install(store, name, labeler)
        return id to ReviewViewModel(id, repo, store, io = main.dispatcher)
    }

    private fun ReviewViewModel.marker(type: String) = state.value.markers.first { it.type == type }
    private fun draft(id: String) = LabelFiles.readLabels(File(store.dir(id), LabelFiles.DRAFT))
    private fun saved(id: String) = LabelFiles.readLabels(File(store.dir(id), Finalizer.EVENTS))!!

    @Test
    fun opensTheDetectorsLabels() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_stuck_kick")
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("ON", "C1", "KB", "OFF", "ON", "RT1", "OFF"), s.markers.map { it.label })
        assertEquals(4, s.lanes.size)
        assertEquals(ReviewViewModel.BUCKETS, s.lanes[0].min.size)
        assertTrue(s.bands.isNotEmpty())
        assertEquals(listOf(1), s.holes)
        assertEquals(setOf("bit_stuck", "kickback"), s.tags.filter { it.on }.map { it.tag }.toSet())
        assertTrue(s.tags.first { it.tag == "bit_stuck" }.auto)
        assertEquals(listOf("Stuck · hole 1"), s.suggestions.map { it.label })
        assertEquals(0, s.unconfirmed)
        assertEquals("the session's tool (the golden setup)", "Drill/driver · Brushless · Bosch GSR 18V-55", s.tool)
        assertFalse(s.dirty)
        assertNotNull(s.offAngle)
        assertEquals(listOf<String>(), s.errors)
        assertEquals(s.c0Us, s.fromUs)
        assertNull(draft(id))
    }

    @Test
    fun dragNudgeAndResetKeepADraft() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_stuck_kick")
        val c = vm.marker("contact")
        vm.drag(c.id, c.tUs + 123_456)
        val moved = vm.marker("contact")
        assertEquals(0L, (moved.tUs - vm.state.value.c0Us) % LabelEdits.SNAP_US)
        assertTrue(moved.edited)
        assertEquals(c.id, vm.state.value.selected?.marker?.id)
        assertTrue(vm.state.value.selected!!.canReset)
        assertTrue(vm.state.value.dirty)
        advanceUntilIdle()
        assertEquals(moved.tUs, draft(id)!!.events.first { it.id == c.id }.tUs)
        vm.nudge(10)
        assertEquals(moved.tUs + 10_000, vm.marker("contact").tUs)
        vm.nudge(-100)
        assertEquals(moved.tUs - 90_000, vm.marker("contact").tUs)
        advanceUntilIdle()
        assertEquals(moved.tUs - 90_000, draft(id)!!.events.first { it.id == c.id }.tUs)
        vm.resetMarker()
        assertEquals(c.tUs, vm.marker("contact").tUs)
        assertFalse(vm.marker("contact").edited)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun addDeleteAndResetAll() = runTest(main.dispatcher) {
        val (_, vm) = open("fx_stuck_kick")
        val off = vm.state.value.markers.last { it.type == "motor_off" }
        vm.setCursor(off.tUs + 300_000)
        vm.add("motor_on")
        val added = vm.state.value.selected!!.marker
        assertEquals("motor_on", added.type)
        assertTrue(added.user)
        assertEquals(listOf("The labels end with the motor running (no Motor off)."), vm.state.value.warnings)
        vm.delete()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.markers.none { it.id == added.id })
        vm.tapMarker(vm.marker("kickback").id)
        vm.delete()
        assertTrue(vm.state.value.markers.none { it.type == "kickback" })
        assertTrue(vm.state.value.dirty)
        vm.resetAll()
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.markers.any { it.type == "kickback" })
    }

    @Test
    fun uncertainMarkersNeedATapBeforeSave() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_pulled_open")
        val n = vm.state.value.unconfirmed
        assertTrue(n >= 1)
        val before = File(store.dir(id), Finalizer.EVENTS).readText()
        vm.save()
        assertEquals(UiMessage.Kind.WARNING, vm.state.value.message?.kind)
        assertTrue(vm.state.value.message!!.text, vm.state.value.message!!.text.contains("tap to confirm"))
        assertFalse(vm.state.value.done)
        assertEquals(before, File(store.dir(id), Finalizer.EVENTS).readText())
        // the save selected the first one: tap it
        val first = vm.state.value.selected!!.marker
        assertTrue(first.needsConfirm)
        vm.tapMarker(first.id)
        assertFalse(vm.state.value.markers.first { it.id == first.id }.needsConfirm)
        assertEquals(n - 1, vm.state.value.unconfirmed)
    }

    @Test
    fun acceptAllSavesValidFiles() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_pulled_open")
        val auto = saved(id)
        vm.acceptAll()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        val dir = store.dir(id)
        val events = FinalizerFixtures.parse(File(dir, Finalizer.EVENTS)).jsonObject
        val meta = FinalizerFixtures.parse(File(dir, Finalizer.META)).jsonObject
        assertEquals(listOf<String>(), SchemaCheck("events.v1.schema.json").problems(events))
        assertEquals(listOf<String>(), SchemaCheck("meta.v1.schema.json").problems(meta))
        val l = Labels.fromJson(events)
        assertEquals(auto.revision + 1, l.revision)
        assertTrue(l.events.filter { it.confidence < LabelEdits.LOW_CONFIDENCE }.all { it.confirmed })
        assertEquals(listOf<String>(), LabelCheck.errors(l).map { it.message })
        val review = meta["review"]!!.jsonObject
        assertEquals("{\"status\":\"reviewed\",\"detector\":\"dbauto/1.2.0\",\"revision\":${l.revision},\"edited_events\":0,\"reviewer\":\"k7q2\"}", review.toString())
        assertNull(draft(id))
        assertEquals(RecordingStage.SAVED, store.list().single { it.journal.recordingId == id }.stage)
    }

    /** A count handle's tap: the view on its markers' range and half of it again (at least the shortest view). */
    @Test
    fun aCountHandleZoomsInOnItsMarkers() = runTest(main.dispatcher) {
        val (_, vm) = open("fx_stuck_kick")
        val ms = vm.state.value.markers
        val a = ms[1].tUs
        val b = ms[3].tUs
        vm.zoomTo(a, b)
        val s = vm.state.value
        assertEquals((b - a) * 3 / 2, s.toUs - s.fromUs)
        assertTrue(a in s.fromUs until s.toUs && b in s.fromUs until s.toUs)
        vm.zoomTo(a, a)
        assertEquals(ReviewViewModel.MIN_SPAN_US, vm.state.value.toUs - vm.state.value.fromUs)
    }

    /** After a count handle's zoom the cursor is in the view, and so is a marker added there. */
    @Test
    fun theCursorComesAlongIntoACountsView() = runTest(main.dispatcher) {
        val (_, vm) = open("fx_stuck_kick")
        val ms = vm.state.value.markers
        val a = ms[1].tUs
        val b = ms[3].tUs
        vm.setCursor(ms.last().tUs)
        vm.zoomTo(a, b)
        val s = vm.state.value
        assertEquals((a + b) / 2, s.cursorUs)
        // one already in the view stays where it is
        vm.setCursor(a + 1_000)
        vm.zoomTo(a, b)
        assertEquals(a + 1_000, vm.state.value.cursorUs)
        vm.add("motor_off")
        val added = vm.state.value.selected!!.marker.tUs
        assertTrue(added in vm.state.value.fromUs until vm.state.value.toUs)
    }

    /** A marker added at a cursor a pan left behind brings the view to it. */
    @Test
    fun anAddedMarkerIsInTheView() = runTest(main.dispatcher) {
        val (_, vm) = open("fx_stuck_kick")
        vm.zoomIn()
        vm.zoomIn()
        val cursor = vm.state.value.cursorUs
        repeat(4) { vm.panForward() }
        assertFalse(cursor in vm.state.value.fromUs until vm.state.value.toUs)
        vm.add("kickback")
        val s = vm.state.value
        assertTrue(s.selected!!.marker.tUs in s.fromUs until s.toUs)
    }

    /** A review opened again says it is reviewed, and uploaded: Save then sends a label update. */
    @Test
    fun aReviewOpenedAgainShowsItsState() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_pulled_open")
        assertFalse(vm.state.value.reviewed)
        assertNull("a new review", vm.state.value.status)
        vm.acceptAll()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        val again = ReviewViewModel(id, repo, store, io = main.dispatcher)
        advanceUntilIdle()
        assertTrue(again.state.value.reviewed)
        assertFalse(again.state.value.uploaded)
        assertEquals("Reviewed. Save stores a new revision.", again.state.value.status)
        val up = ReviewViewModel(id, repo, store, io = main.dispatcher, uploaded = { it == id })
        advanceUntilIdle()
        assertTrue(up.state.value.uploaded)
        assertEquals("Reviewed · uploaded. Save sends a label update.", up.state.value.status)
        // an upload state that cannot be read is no upload
        val unread = ReviewViewModel(id, repo, store, io = main.dispatcher, uploaded = { throw IOException("gone") })
        advanceUntilIdle()
        assertEquals("Reviewed. Save stores a new revision.", unread.state.value.status)
    }

    /** An upload that ends while the review is open (or one that starts again): the status follows it. */
    @Test
    fun theUploadStatusFollowsTheUploadWhileOpen() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_pulled_open")
        vm.acceptAll()
        advanceUntilIdle()
        var done = false
        val active = MutableStateFlow(true)        // the queue's: an upload of it running
        val again = ReviewViewModel(id, repo, store, io = main.dispatcher, uploaded = { done }, uploadChanges = active)
        advanceUntilIdle()
        assertEquals("Reviewed. Save stores a new revision.", again.state.value.status)
        done = true
        active.value = false
        advanceUntilIdle()
        assertTrue(again.state.value.uploaded)
        assertEquals("Reviewed · uploaded. Save sends a label update.", again.state.value.status)
        done = false
        active.value = true
        advanceUntilIdle()
        assertFalse(again.state.value.uploaded)
    }

    @Test
    fun saveWritesTheEditsAndTheDerivedLabels() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_stuck_kick")
        val c = vm.marker("contact")
        vm.drag(c.id, c.tUs + 200_000)
        vm.toggleTag("aborted")
        vm.toggleSuggestion(vm.state.value.suggestions.single().id)      // the stuck span off
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        val dir = store.dir(id)
        val l = saved(id)
        val m = l.events.first { it.id == c.id }
        assertTrue(m.edited)
        assertEquals(c.tUs, m.autoTUs)
        assertTrue(l.spans.none { it.type == "stuck" })
        assertEquals(listOf("aborted", "bit_stuck", "kickback").filter { it != "clean" }.toSet(), l.holes.single().outcomeTags.toSet())
        val meta = FinalizerFixtures.parse(File(dir, Finalizer.META)).jsonObject
        assertEquals("2", meta["review"]!!.jsonObject["edited_events"].toString())      // moved + a removed span
        // meta.derived and the hole's off angle are those of the labels as saved
        val rec = RecordingFiles.read(dir)
        val (derived, holes) = Derived.compute(rec.inputs.level, l, rec.inputs.tolDeg)
        assertEquals(derived.toJson(), meta["derived"])
        assertEquals(holes[1]?.offAngleDeg, l.holes.single().offAngleDeg)
    }

    @Test
    fun aGrammarErrorRefusesTheSave() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_stuck_kick")
        val before = File(store.dir(id), Finalizer.EVENTS).readText()
        vm.tapMarker(vm.state.value.markers.first().id)          // the first motor_on
        vm.delete()
        assertTrue(vm.state.value.errors.isNotEmpty())
        vm.save()
        val msg = vm.state.value.message!!
        assertEquals(UiMessage.Kind.ERROR, msg.kind)
        assertTrue(msg.text, msg.text.startsWith("Not saved: Contact at"))
        assertEquals(vm.marker("contact").id, vm.state.value.selected?.marker?.id)
        advanceUntilIdle()
        assertFalse(vm.state.value.done)
        assertEquals(before, File(store.dir(id), Finalizer.EVENTS).readText())
    }

    @Test
    fun aDraftIsRestoredAndDiscarded() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_stuck_kick")
        val c = vm.marker("contact")
        vm.drag(c.id, c.tUs + 50_000)
        advanceUntilIdle()
        val again = ReviewViewModel(id, repo, store, io = main.dispatcher)
        assertEquals(vm.marker("contact").tUs, again.marker("contact").tUs)
        assertTrue(again.state.value.dirty)
        assertEquals(UiMessage.Kind.INFO, again.state.value.message?.kind)
        again.discard()
        advanceUntilIdle()
        assertTrue(again.state.value.done)
        assertNull(draft(id))
        assertEquals(c.tUs, saved(id).events.first { it.id == c.id }.tUs)
        val third = ReviewViewModel(id, repo, store, io = main.dispatcher)
        assertFalse(third.state.value.dirty)
    }

    @Test
    fun zoomAndPan() = runTest(main.dispatcher) {
        val (_, vm) = open("fx_raw897_mount")
        val s0 = vm.state.value
        val total = s0.c1Us - s0.c0Us
        vm.tapMarker(vm.state.value.markers.first { it.type == "contact" }.id)
        vm.zoomIn()
        val s1 = vm.state.value
        assertEquals(total / 2, s1.toUs - s1.fromUs)
        assertTrue(s1.selected!!.marker.tUs in s1.fromUs until s1.toUs)
        repeat(10) { vm.zoomIn() }
        assertEquals(ReviewViewModel.MIN_SPAN_US, vm.state.value.toUs - vm.state.value.fromUs)
        val f = vm.state.value.fromUs
        vm.panForward()
        assertEquals(f + ReviewViewModel.MIN_SPAN_US / 2, vm.state.value.fromUs)
        vm.panBack()
        vm.panBack()
        assertEquals(f - ReviewViewModel.MIN_SPAN_US / 2, vm.state.value.fromUs)
        vm.showAll()
        assertEquals(s0.c0Us to s0.c1Us, vm.state.value.fromUs to vm.state.value.toUs)
        repeat(3) { vm.zoomOut() }
        assertEquals(total, vm.state.value.toUs - vm.state.value.fromUs)
    }

    /** Opened right after the take, before its files exist: it waits, then shows the labels. */
    @Test
    fun waitsForTheFinalize() = runTest(main.dispatcher) {
        val j = FinalizerFixtures.install(store, "rich")
        store.write(store.read(j.recordingId)!!.copy(state = JournalState.COMPLETE))
        val vm = ReviewViewModel(j.recordingId, repo, store, io = main.dispatcher)
        assertTrue(vm.state.value.waiting)
        store.finalize(j.recordingId)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf<String>(), vm.state.value.errors)
    }

    @Test
    fun aTakeThatCannotBeFinalizedSaysWhy() = runTest(main.dispatcher) {
        val j = FinalizerFixtures.install(store, "rich")
        store.write(store.read(j.recordingId)!!.copy(state = JournalState.COMPLETE, finalizeError = "the take has no SESSION record"))
        val vm = ReviewViewModel(j.recordingId, repo, store, io = main.dispatcher)
        assertEquals("This recording cannot be reviewed: the take has no SESSION record", vm.state.value.error)
    }

    /**
     * A draft write still waiting while Save runs (the writer was busy with the one before) does
     * not land after the save: no draft is left over the saved labels. The file I/O runs on its
     * own dispatcher here, released step by step.
     */
    @Test
    fun aLateDraftDoesNotLandAfterSave() = runTest(main.dispatcher) {
        val io = StandardTestDispatcher()
        val id = LabelTestData.install(store, "fx_pulled_open", labeler)
        val vm = ReviewViewModel(id, repo, store, io = io)
        io.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        val c = vm.marker("contact")
        vm.drag(c.id, c.tUs + 40_000)          // its draft write waits for io
        vm.acceptAll()                          // another draft (confirmed), then Save behind the first write
        io.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertNull("no draft after the save", draft(id))
        assertTrue(saved(id).events.first { it.id == c.id }.edited)
    }

    /**
     * The screen goes away (Back, the system) before its draft was written: it is written after
     * it closed, by the writer on io, not on the thread that cleared it (the main thread).
     */
    @Test
    fun aDraftNotWrittenYetIsKeptWhenTheScreenGoesAway() = runTest(main.dispatcher) {
        val io = StandardTestDispatcher()
        val id = LabelTestData.install(store, "fx_stuck_kick", labeler)
        val owner = ViewModelStore()
        val vm = ViewModelProvider.create(owner, viewModelFactory { initializer { ReviewViewModel(id, repo, store, io = io) } })[ReviewViewModel::class]
        io.scheduler.advanceUntilIdle()
        val c = vm.marker("contact")
        vm.drag(c.id, c.tUs + 70_000)
        val moved = vm.marker("contact").tUs
        assertNull("not written yet", draft(id))
        owner.clear()
        assertNull("no file I/O on the thread that cleared it", draft(id))
        io.scheduler.advanceUntilIdle()
        assertEquals(moved, draft(id)!!.events.first { it.id == c.id }.tUs)
        val again = ReviewViewModel(id, repo, store, io = main.dispatcher)
        assertEquals(moved, again.marker("contact").tUs)
        assertEquals("Your unsaved changes were restored.", again.state.value.message?.text)
    }

    /**
     * The finalize after the take failed on storage: the review does not wait for ever, it says
     * why and Try again makes the files (once there is space) and opens the labels.
     */
    @Test
    fun aStorageErrorIsShownWithTryAgain() = runTest(main.dispatcher) {
        var full = true
        val s = RecordingStore(File(tmp.root, "full"), labeler = labeler, finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult =
                if (full) throw IOException("No space left on device") else super.finalize(dir, journal)
        })
        val r = ReviewRepository(s, labeler)
        val j = FinalizerFixtures.install(s, "rich")
        s.write(s.read(j.recordingId)!!.copy(state = JournalState.COMPLETE))
        val vm = ReviewViewModel(j.recordingId, r, s, io = main.dispatcher)
        assertTrue(vm.state.value.waiting)
        assertThrows(IOException::class.java) { s.finalize(j.recordingId) }
        advanceUntilIdle()
        val failed = vm.state.value
        assertFalse(failed.loading)
        assertTrue(failed.canRetry)
        assertTrue(failed.error!!, failed.error!!.contains("No space left on device"))
        full = false
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertTrue(File(s.dir(j.recordingId), Finalizer.META).isFile)
    }

    /** The derived labels (the off angle over LEVEL) are computed once per labels: not again for a zoom, pan, cursor or selection. */
    @Test
    fun viewChangesDoNotRecomputeTheDerivedLabels() = runTest(main.dispatcher) {
        val id = LabelTestData.install(store, "fx_stuck_kick", labeler)
        var derives = 0
        val vm = ReviewViewModel(id, repo, store, io = main.dispatcher, derive = { l, x, t -> derives++; Derived.compute(l, x, t) })
        assertEquals(1, derives)
        val c = vm.marker("contact")
        vm.zoomIn()
        vm.panForward()
        vm.zoomOut()
        vm.showAll()
        vm.setCursor(c.tUs)
        vm.tapMarker(c.id)          // confirmed already: selected only
        vm.select(null)
        vm.selectHole(1)
        assertEquals(1, derives)
        vm.drag(c.id, c.tUs + 30_000)
        assertEquals(2, derives)
        assertNotNull(vm.state.value.offAngle)
    }

    /**
     * A drag moves the marker at once; its derived labels (medians over every LEVEL row) are
     * computed on io, not on the main thread, and shown when they are there.
     */
    @Test
    fun aDragComputesTheDerivedLabelsOffTheMainThread() = runTest(main.dispatcher) {
        val io = StandardTestDispatcher()
        val id = LabelTestData.install(store, "fx_stuck_kick", labeler)
        var derives = 0
        var last: Labels? = null
        val vm = ReviewViewModel(id, repo, store, io = io, derive = { l, x, t -> derives++; last = x; Derived.compute(l, x, t) })
        io.scheduler.advanceUntilIdle()
        assertEquals("with the load, on io", 1, derives)
        val before = vm.state.value.offAngle
        val c = vm.marker("contact")
        // the contact 1.5 s later: the drilling (and its LEVEL rows) is another stretch
        repeat(5) { vm.drag(c.id, c.tUs + (it + 1) * 300_000L) }
        assertEquals(c.tUs + 1_500_000, vm.marker("contact").tUs)
        assertEquals("none on the main thread", 1, derives)
        assertEquals(before, vm.state.value.offAngle)
        io.scheduler.advanceUntilIdle()
        assertTrue("the latest labels, not one per move: $derives", derives in 2..3)
        assertEquals("of the labels shown", c.tUs + 1_500_000, last!!.events.first { it.id == c.id }.tUs)
        assertNotNull(vm.state.value.offAngle)
    }

    /**
     * A take too long for the phone's memory to label: the review says so (it does not end the
     * app) and offers Try again, which labels it again (no new finalize: its files are there).
     */
    @Test
    fun runningOutOfMemoryOpeningAReviewSaysSo() = runTest(main.dispatcher) {
        val j = FinalizerFixtures.install(store, "golden")
        RecordingStore(store.root, labeler = { _, _ -> }).finalize(j.recordingId)          // the empty label set
        var memory = false
        val oom = AutoLabeler({ if (memory) TestParams.loaded else throw OutOfMemoryError("Failed to allocate") })
        val vm = ReviewViewModel(j.recordingId, ReviewRepository(store, oom), store, io = main.dispatcher)
        assertEquals(ReviewViewModel.MEMORY_ERROR, vm.state.value.error)
        assertTrue(vm.state.value.canRetry)
        val changes = store.changes.value
        memory = true
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertEquals("labelled now", "dbauto/1.2.0", saved(j.recordingId).detector?.version)
        assertEquals("not finalized again", changes, store.changes.value)
    }

    /**
     * The finalize ran out of memory (finalize_retry): not a take that cannot be reviewed, the
     * review offers Try again, which finalizes and labels it.
     */
    @Test
    fun aFinalizeThatRanOutOfMemoryCanBeTriedAgain() = runTest(main.dispatcher) {
        val j = FinalizerFixtures.install(store, "rich")
        val id = j.recordingId
        var memory = false
        val s = RecordingStore(store.root, labeler = labeler, finalizer = object : Finalizer() {
            override fun finalize(dir: File, journal: TakeJournal): FinalizeResult =
                if (memory) super.finalize(dir, journal) else throw OutOfMemoryError("Failed to allocate")
        })
        assertNull(s.finalize(id))
        assertEquals(ReviewSource.Failed(RecordingStore.OUT_OF_MEMORY, retry = true, outOfMemory = true), ReviewRepository(s, labeler).source(id))
        val vm = ReviewViewModel(id, ReviewRepository(s, labeler), s, io = main.dispatcher)
        assertEquals(ReviewViewModel.MEMORY_ERROR, vm.state.value.error)
        assertTrue(vm.state.value.canRetry)
        vm.retry()          // still out of memory: still offered
        advanceUntilIdle()
        assertTrue(vm.state.value.canRetry)
        memory = true
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertEquals("finalized and labelled", "dbauto/1.2.0", saved(id).detector?.version)
        assertFalse(s.read(id)!!.finalizeRetry)
        assertEquals(RecordingStage.NEEDS_REVIEW, s.list().first { it.journal.recordingId == id }.stage)
    }

    /**
     * A drag on a 20-minute take (about 750 markers, 190 holes) costs a few milliseconds of the
     * main thread (the derived labels are computed on io). The time is printed (the report of the run).
     */
    @Test
    fun aDragOnALongTakeIsCheap() = runTest(main.dispatcher) {
        val id = LabelTestData.idOf("fx_drill_through")
        val dir = store.dir(id)
        LabelTestData.longTake(dir, 20.0)
        store.write(FinalizerFixtures.journal("golden", JournalState.COMPLETE).copy(recordingId = id, reviewer = "k7q2"))
        labeler.relabel(dir)
        val io = StandardTestDispatcher()          // the derived labels and the drafts: not the main thread's time
        val vm = ReviewViewModel(id, repo, store, io = io)
        io.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.markers.size > 500)
        val c = vm.state.value.markers.filter { it.type == "contact" }[50]
        repeat(20) { vm.drag(c.id, c.tUs + it * 10_000L) }          // warm-up
        val n = 100
        val t0 = System.nanoTime()
        repeat(n) { vm.drag(c.id, c.tUs + (it % 20) * 10_000L) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println(String.format(java.util.Locale.ROOT, "ReviewViewModelTest: a drag on a 20-minute take (%d markers): %.2f ms", vm.state.value.markers.size, ms))
        assertTrue("$ms ms per drag event", ms < 8.0)
    }

    @Test
    fun userAddedLabelsAreSavedAsTheUsers() = runTest(main.dispatcher) {
        val (id, vm) = open("fx_drill_through")
        val bt = vm.marker("breakthrough")
        vm.setCursor(bt.tUs + 300_000)
        vm.add("kickback")
        vm.save()            // fx_drill_through has no uncertain markers
        advanceUntilIdle()
        val l = saved(id)
        val kb = l.events.single { it.type == "kickback" }
        assertEquals(Source.USER, kb.source)
        assertNull(kb.hole)      // 300 ms after the breakthrough: out of the hole
        assertEquals(l.events.sortedBy { it.tUs }, l.events)
    }
}
