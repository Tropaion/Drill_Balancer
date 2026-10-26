package dev.drillbalancer.ui.training.review

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.label.AutoDetect
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Lanes
import dev.drillbalancer.recorder.label.RecordingFiles
import dev.drillbalancer.recorder.label.ReviewData
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.components.UiMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The review screen on the detector's labels of the autodetect fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class ReviewScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val setup = SessionSetup(material = "wood/softwood", thicknessMm = 38.0, mode = "drill", bitType = "brad_point", bitDiameterMm = 8.0)

    private fun data(name: String, setup: SessionSetup = this.setup): ReviewData {
        val rec = RecordingFiles.read(LabelTestData.fixture(name))
        val det = AutoDetect.detect(rec.inputs, TestParams.loaded)
        return ReviewData(
            rec.inputs.recordingId, det.labels, det.labels, det.labels, false, Lanes.of(det.features), rec.inputs.level,
            rec.inputs.tolDeg, "k7q2", "auto_only", setup,
        )
    }

    private fun shot(
        d: ReviewData,
        labels: Labels = d.labels,
        from: Long = labels.c0,
        to: Long = labels.c1,
        selected: String? = null,
        message: UiMessage? = null,
        cursor: Long = (labels.c0 + labels.c1) / 2,
    ) {
        val lanes = ReviewViewModel.lanesOf(d.lanes, from, to, d.lanes.values.map { ReviewViewModel.scaleOf(it) })
        val hole = labels.events.firstOrNull { it.id == selected }?.hole
        val state = ReviewViewModel.stateOf(d, labels, lanes, from, to, cursor, selected, hole, message)
        compose.setContent { Themed { InFrame(null, SHELL, title = "Review") { m -> ReviewScreen(state, ReviewActions(), m) } } }
        compose.onRoot().captureRoboImage()
    }

    /** A take as the detector labelled it: one hole, a stall, a kickback, a restart, the retract (the tool under the title). */
    @Test
    fun reviewAuto() = shot(data("fx_stuck_kick", setup.copy(drillType = "drill", motor = "brushless", drillModel = "Bosch GSR 18V-55")))

    /** The contact moved by the person and selected: nudge, reset and delete (a taller window: the panel is below the plot). */
    @Test
    @Config(qualifiers = "w360dp-h1100dp-xhdpi")
    fun reviewSelectedMarker() {
        val d = data("fx_stuck_kick")
        val c = d.labels.events.first { it.type == "contact" }
        val moved = LabelEdits.move(d.labels, c.id, c.tUs - 120_000)
        shot(d, moved, selected = c.id, cursor = c.tUs)
    }

    /** Zoomed in to 2 s around the first contact of a two-hole take (RAW897). */
    @Test
    fun reviewZoomed() {
        val d = data("fx_raw897_mount")
        val c = d.labels.events.first { it.type == "contact" }
        shot(d, from = c.tUs - 1_000_000, to = c.tUs + 1_000_000, selected = c.id, cursor = c.tUs)
    }

    /** An uncertain marker (dashed amber) that Save asked to confirm. */
    @Test
    @Config(qualifiers = "w360dp-h1100dp-xhdpi")
    fun reviewLowConfidence() {
        val d = data("fx_pulled_open")
        val open = d.labels.events.first { LabelEdits.needsConfirm(it) }
        shot(
            d, from = open.tUs - 1_200_000, to = d.labels.c1, cursor = open.tUs - 400_000,
            message = UiMessage("1 uncertain label (dashed) needs a tap to confirm, or use Accept all.", UiMessage.Kind.WARNING),
        )
    }

    /** The first motor_on deleted: the order is invalid and Save says what to fix. */
    @Test
    fun reviewGrammarError() {
        val d = data("fx_stuck_kick")
        val bad = LabelEdits.delete(d.labels, d.labels.events.first { it.type == "motor_on" }.id)
        val e = LabelCheck.errors(bad).first()
        shot(d, bad, selected = e.markerId, message = UiMessage("Not saved: ${e.message}"))
    }

    /** The whole screen (tall window): the tags of the hole and the detector's suggestions with their auto badge. */
    @Test
    @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun reviewTagsAndSuggestions() {
        val d = data("fx_suggestions")
        val wob = d.labels.spans.first { it.type == "wobble" }
        shot(d, LabelEdits.setTag(LabelEdits.setSpan(d.labels, wob, false), 1, "breakout_chipping", true))
    }
}
