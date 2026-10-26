package dev.drillbalancer.ui.training.review

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.awaitReal
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
@Config(sdk = [37])
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
        compose.onNode(SemanticsMatcher("handle ${sure.label}") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.endsWith(" ${sure.label}") } == true
        }).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(sure.id, vm.state.value.selected?.marker?.id) }
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
