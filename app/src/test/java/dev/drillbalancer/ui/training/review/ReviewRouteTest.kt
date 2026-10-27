package dev.drillbalancer.ui.training.review

import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.awaitReal
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.training.TrainingReviewRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The review route as the user meets it: what asks first, and what TalkBack and Switch Access can reach. */
@RunWith(RobolectricTestRunner::class)
// the screenshots' phone: at full zoom the fixture's markers have a handle each (MarkerLayout)
@Config(sdk = [37], qualifiers = PHONE)
class ReviewRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val labeler = AutoLabeler({ TestParams.loaded })
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private var done = false
    private lateinit var id: String

    private fun open(name: String = "fx_stuck_kick"): ReviewViewModel {
        id = LabelTestData.install(store, name, labeler)
        val vm = ReviewViewModel(id, ReviewRepository(store, labeler), store)
        compose.setContent { Themed { TrainingReviewRoute(vm, onDone = { done = true }) } }
        compose.awaitReal(what = "loaded") { !vm.state.value.loading }
        return vm
    }

    private fun ReviewViewModel.contact() = state.value.markers.first { it.type == "contact" }

    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** The handle that says [name] (an uncertain one adds how to confirm it). */
    private fun isHandle(name: String) = SemanticsMatcher("handle $name") { h ->
        h.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == name || it.startsWith("$name,") } == true
    }.and(hasClickAction())

    private fun handleOf(m: MarkerView) = compose.onNode(isHandle(m.spokenName()))

    @Test
    fun discardAsksFirstWhenSomethingChanged() {
        val vm = open()
        val c = vm.contact()
        compose.runOnIdle { vm.drag(c.id, c.tUs + 50_000) }
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Discard your changes?").assertExists()
        dialogButton("Cancel").performClick()
        compose.onNodeWithText("Discard your changes?").assertDoesNotExist()
        assertTrue(vm.state.value.dirty)
        assertFalse(done)
        compose.onNodeWithText("Discard").performClick()
        dialogButton("Discard").performClick()
        compose.awaitReal(what = "closed") { done }
        assertEquals(null, LabelFiles.readLabels(File(store.dir(id), LabelFiles.DRAFT)))
    }

    @Test
    fun resetAllAsksFirstWhenItWouldDropEdits() {
        val vm = open()
        // nothing changed: it runs at once
        compose.onNodeWithText("Reset all to auto").performScrollTo().performClick()
        compose.onNodeWithText("Reset all labels to auto?").assertDoesNotExist()
        val c = vm.contact()
        compose.runOnIdle { vm.drag(c.id, c.tUs + 50_000) }
        compose.onNodeWithText("Reset all to auto").performScrollTo().performClick()
        compose.onNodeWithText("Reset all labels to auto?").assertExists()
        dialogButton("Cancel").performClick()
        assertTrue("kept", vm.state.value.dirty)
        compose.onNodeWithText("Reset all to auto").performScrollTo().performClick()
        dialogButton("Reset all").performClick()
        compose.runOnIdle { assertFalse(vm.state.value.dirty) }
        assertEquals(c.tUs, vm.contact().tUs)
    }

    /** A marker's handle is a button with a click action (select, or confirm an uncertain one). */
    @Test
    fun handlesAreButtonsForTalkBack() {
        val vm = open("fx_pulled_open")
        val markers = vm.state.value.markers
        fun unique(m: MarkerView) = markers.count { it.label == m.label } == 1
        val uncertain = markers.first { it.needsConfirm && unique(it) }
        val sure = markers.first { !it.needsConfirm && unique(it) }
        compose.onNodeWithContentDescription(" ${uncertain.label}, uncertain, tap to confirm", substring = true)
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("labelled Confirm") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Confirm" })
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(uncertain.id, vm.state.value.selected?.marker?.id)
            assertFalse(vm.state.value.markers.first { it.id == uncertain.id }.needsConfirm)
        }
        handleOf(sure).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(sure.id, vm.state.value.selected?.marker?.id) }
    }

    /**
     * On a narrow phone the whole take crowds the markers: a count handle stands for them, a
     * button read once with their names as their handles say them, that zooms in on them,
     * after which each has its own handle.
     */
    @Test
    @Config(qualifiers = "w320dp-h780dp-xhdpi")
    fun aCountHandleZoomsInOnItsMarkers() {
        val vm = open()
        val count = compose.onNode(SemanticsMatcher("a count handle") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.matches(Regex("\\d+ markers \\(.*\\)")) } == true
        }.and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)))
        val names = count.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().substringAfter("(").removeSuffix(")").split(", ")
        val handles = vm.state.value.markers.map { it.spokenName() }
        assertTrue("$names by their names", names.all { it in handles })
        compose.onAllNodesWithText("zoom").assertCountEquals(0)          // not a stop of its own
        val before = vm.state.value.toUs - vm.state.value.fromUs
        count.assert(SemanticsMatcher("labelled Zoom in on them") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Zoom in on them" })
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertTrue(vm.state.value.toUs - vm.state.value.fromUs < before) }
        for (n in names) {
            val handles = compose.onAllNodes(isHandle(n)).fetchSemanticsNodes()
            assertTrue("a handle of $n", handles.isNotEmpty())
        }
    }

    /**
     * A handle dragged past its neighbours keeps the drag: its place among the handles changes
     * (it joins their column, which counts them) but it stays the same handle, in the first row.
     */
    @Test
    fun aHandleDraggedPastItsNeighbourKeepsTheDrag() {
        val vm = open()
        val s = vm.state.value
        val markers = s.markers.filter { it.tUs in s.fromUs until s.toUs }.sortedBy { it.tUs }
        val plot = compose.onNodeWithContentDescription("Signals from", substring = true).fetchSemanticsNode()
        val usPerPx = (s.toUs - s.fromUs).toDouble() / plot.size.width
        val handlePx = with(compose.density) { TouchHeight.toPx() }
        // the retract, dragged to a handle before the marker before it
        val a = markers.single { it.type == "retract" }
        val b = markers[markers.indexOf(a) - 1]
        val node = handleOf(a)
        val top = node.fetchSemanticsNode().boundsInRoot.top
        val dx = ((a.tUs - b.tUs) / usPerPx + handlePx).toFloat()
        val steps = (dx / 8).toInt()
        node.performTouchInput { down(center) }
        repeat(steps) {
            node.performTouchInput { moveBy(Offset(-dx / steps, 0f)) }
            compose.runOnIdle { assertEquals("in the first row", top, node.fetchSemanticsNode().boundsInRoot.top) }
        }
        node.performTouchInput { up() }
        compose.runOnIdle {
            val t = vm.state.value.markers.first { it.id == a.id }.tUs
            assertTrue("past ${b.label}", t < b.tUs)
            // the whole drag less the touch slop (the drag starts past it), give or take a pixel and the 10 ms snap
            val slop = ViewConfiguration.get(ApplicationProvider.getApplicationContext()).scaledTouchSlop
            val tolerance = 2 * usPerPx + 10_000
            assertEquals("moved by the whole drag", a.tUs - (dx - slop) * usPerPx, t.toDouble(), tolerance)
        }
    }

    /**
     * A handle dragged through its neighbour's column (the neighbour drops to the second row)
     * and out of it again: the lanes below move down once and stay there until the drag ends.
     */
    @Test
    fun theLanesStayPutDuringADrag() {
        val vm = open()
        val all = vm.state.value.markers.sortedBy { it.tUs }
        val on = all.last { it.type == "motor_on" }
        val off = all[all.indexOf(on) - 1]
        // the two alone in the view, at a quarter and three quarters of it
        val gap = on.tUs - off.tUs
        compose.runOnIdle { vm.zoomTo((off.tUs + on.tUs) / 2 - gap * 2 / 3, (off.tUs + on.tUs) / 2 + gap * 2 / 3) }
        val s = vm.state.value
        assertEquals(listOf(off.id, on.id), s.markers.filter { it.tUs in s.fromUs until s.toUs }.map { it.id })
        val plot = compose.onNodeWithContentDescription("Signals from", substring = true)
        fun plotTop() = plot.fetchSemanticsNode().boundsInRoot.top
        val start = plotTop()
        val node = handleOf(on)
        // from three quarters of the view to an eighth: through the off's column and out of it
        val dx = plot.fetchSemanticsNode().size.width * 5 / 8f
        val steps = (dx / 8).toInt()
        val tops = ArrayList<Float>()
        node.performTouchInput { down(center) }
        repeat(steps) {
            node.performTouchInput { moveBy(Offset(-dx / steps, 0f)) }
            compose.runOnIdle { tops += plotTop() }
        }
        assertTrue("the off dropped to the second row", tops.max() > start)
        assertEquals("and the lanes stayed down", tops.max(), tops.last())
        node.performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("past the off", vm.state.value.markers.first { it.id == on.id }.tUs < off.tUs)
            assertEquals("back up after the drag", start, plotTop())
        }
    }

    /** The cursor (where Add puts a marker) moves without a tap on a position. */
    @Test
    fun theCursorStepsForTalkBack() {
        val vm = open()
        val before = vm.state.value.cursorUs
        val plot = compose.onNodeWithContentDescription("Signals from", substring = true)
        val forward = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Cursor forward" }
        compose.runOnIdle { forward.action() }
        compose.runOnIdle { assertTrue(vm.state.value.cursorUs > before) }
        val back = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Cursor back" }
        compose.runOnIdle { back.action() }
        compose.runOnIdle { assertEquals(before, vm.state.value.cursorUs) }
    }
}
