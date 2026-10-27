package dev.drillbalancer.ui.training.review

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.label.AutoDetect
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.HoleLabel
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Lanes
import dev.drillbalancer.recorder.label.Marker
import dev.drillbalancer.recorder.label.RecordingFiles
import dev.drillbalancer.recorder.label.ReviewData
import dev.drillbalancer.recorder.label.Source
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.upload.RepoCopy
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

    private fun data(name: String, setup: SessionSetup = this.setup, status: String = "auto_only"): ReviewData {
        val rec = RecordingFiles.read(LabelTestData.fixture(name))
        val det = AutoDetect.detect(rec.inputs, TestParams.loaded)
        return ReviewData(
            rec.inputs.recordingId, det.labels, det.labels, det.labels, false, Lanes.of(det.features), rec.inputs.level,
            rec.inputs.tolDeg, "k7q2", status, setup,
        )
    }

    // a dialog is a window of its own: the whole screen is captured then
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(
        d: ReviewData,
        labels: Labels = d.labels,
        from: Long = labels.c0,
        to: Long = labels.c1,
        selected: String? = null,
        message: UiMessage? = null,
        cursor: Long = (labels.c0 + labels.c1) / 2,
        repoCopy: RepoCopy = RepoCopy.NONE,
        dialog: RepoCopy? = null,
        uploading: Boolean = false,
    ) {
        val lanes = ReviewViewModel.lanesOf(d.lanes, from, to, d.lanes.values.map { ReviewViewModel.scaleOf(it) })
        val hole = labels.events.firstOrNull { it.id == selected }?.hole
        val state = ReviewViewModel.stateOf(d, labels, lanes, from, to, cursor, selected, hole, message, repoCopy = repoCopy)
        compose.setContent {
            Themed {
                InFrame(null, SHELL, title = "Review") { m -> ReviewScreen(state, ReviewActions(), m) }
                if (dialog != null) DeleteTakeDialog(dialog, uploading, {}, {})
            }
        }
        if (dialog != null) captureScreenRoboImage() else compose.onRoot().captureRoboImage()
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

    /** The whole screen (tall window): the tags of the hole and the detector's suggestions with their auto badge, Delete take at the end. */
    @Test
    @Config(qualifiers = "w360dp-h2000dp-xhdpi")
    fun reviewTagsAndSuggestions() {
        val d = data("fx_suggestions")
        val wob = d.labels.spans.first { it.type == "wobble" }
        shot(d, LabelEdits.setTag(LabelEdits.setSpan(d.labels, wob, false), 1, "breakout_chipping", true))
    }

    /** [d] with [l] as its labels, saved (not changed by the review). */
    private fun saved(d: ReviewData, l: Labels) =
        ReviewData(d.id, l, l, d.auto, false, d.lanes, d.level, d.tolDeg, d.reviewer, d.reviewStatus, d.setup)

    /** Five holes in 10 s (twenty markers) on a take's signals: as crowded as a long take at full zoom. */
    private fun dense(d: ReviewData): Labels {
        val events = (1..5).flatMap { h ->
            val t = d.labels.c0 + 200_000 + (h - 1) * 1_950_000L
            listOf(
                Marker("on$h", "motor_on", t, source = Source.AUTO, confidence = 0.9),
                Marker("c$h", "contact", t + 350_000, h, Source.AUTO, 0.9),
                Marker("bt$h", "breakthrough", t + 1_050_000, h, Source.AUTO, 0.9),
                Marker("off$h", "motor_off", t + 1_400_000, source = Source.AUTO, confidence = 0.9),
            )
        }
        return d.labels.copy(events = events, spans = emptyList(), holes = (1..5).map { HoleLabel.entry(it, true, emptyList()) })
    }

    /** The whole of a crowded take: a count handle where markers crowd (a tap zooms in), a handle with a leader for the rest; the lane titles over the lines. */
    @Test
    fun reviewDenseTake() {
        val d = data("fx_raw897_mount")
        shot(saved(d, dense(d)))
    }

    /** The crowded take zoomed in on two holes: a handle each, stacked where close, BT2 selected. */
    @Test
    fun reviewDenseTakeZoomed() {
        val d = data("fx_raw897_mount")
        val l = dense(d)
        shot(saved(d, l), l, from = l.c0 + 1_900_000, to = l.c0 + 6_000_000, selected = "bt2", cursor = l.c0 + 3_200_000)
    }

    /** A review opened again, uploaded before: its state under the title, Save the main action. */
    @Test
    fun reviewReopenedUploaded() = shot(data("fx_drill_through", status = "reviewed"), repoCopy = RepoCopy.CURRENT)

    /** Delete take's confirmation for a take never uploaded: deleted for good. */
    @Test
    fun reviewDeleteNotUploaded() = shot(data("fx_drill_through"), dialog = RepoCopy.NONE)

    /** For an uploaded take: only the phone's copy goes, the data repo keeps it. */
    @Test
    fun reviewDeleteUploaded() = shot(data("fx_drill_through", status = "reviewed"), repoCopy = RepoCopy.CURRENT, dialog = RepoCopy.CURRENT)

    /** Uploaded, then saved again: the label update not sent yet is dropped too. */
    @Test
    fun reviewDeleteUpdatePending() = shot(data("fx_drill_through", status = "reviewed"), repoCopy = RepoCopy.OLDER, dialog = RepoCopy.OLDER)

    /** Not uploaded yet, its upload under way: it is stopped (one already finishing still lands). */
    @Test
    fun reviewDeleteWhileUploading() = shot(data("fx_drill_through", status = "reviewed"), dialog = RepoCopy.NONE, uploading = true)

    private fun notLoaded(state: ReviewUiState) {
        compose.setContent { Themed { InFrame(null, SHELL, title = "Review") { m -> ReviewScreen(state, ReviewActions(), m) } } }
        compose.onRoot().captureRoboImage()
    }

    /** A take no dataset files can be made from: why, and Delete take (a failed one's message above). */
    @Test
    fun reviewCannotBeReviewed() = notLoaded(
        ReviewUiState(
            loading = false, error = "This recording cannot be reviewed: the take has no SESSION record", canDelete = true,
            message = UiMessage("Deleting failed (Permission denied)."),
        ),
    )

    /** Waiting for its finalize (not running yet): Delete take under it. */
    @Test
    fun reviewWaitingDeletable() = notLoaded(ReviewUiState(loading = true, waiting = true, canDelete = true))
}
