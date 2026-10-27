package dev.drillbalancer.ui.training.upload

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.training.RecordingRow
import dev.drillbalancer.ui.training.TrainingListActions
import dev.drillbalancer.ui.training.TrainingListScreen
import dev.drillbalancer.ui.training.TrainingListUiState
import dev.drillbalancer.ui.training.UploadSummary
import dev.drillbalancer.upload.FailureKind
import dev.drillbalancer.upload.TokenState
import dev.drillbalancer.upload.UploadCounts
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadSettings
import dev.drillbalancer.upload.UploadStage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class UploadScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** [scrollTo]: the text the screen is scrolled to first (the part below the first screenful). */
    private fun shot(title: String, scrollTo: String? = null, screen: @Composable (Modifier) -> Unit) {
        compose.setContent { Themed { InFrame(null, title = title, screen = screen) } }
        scrollTo?.let { compose.onNodeWithText(it).performScrollTo() }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun uploadNoToken() = shot("Upload settings") { m ->
        UploadSettingsScreen(UploadSettingsUiState(loaded = true, counts = UploadCounts(notQueued = 2, needsReview = 1)), UploadSettingsActions(), m)
    }

    @Test
    fun uploadTestOk() = shot("Upload settings") { m ->
        UploadSettingsScreen(
            UploadSettingsUiState(
                loaded = true,
                token = TokenState(set = true),
                test = ConnectionTest.Done(
                    listOf("Found Tropaion/Drill_Balancer_Data.", "It is private.", "You may write to it.", "The dataset template is there."), emptyList(),
                ),
                counts = UploadCounts(queued = 1, uploading = 1, done = 5),
            ),
            UploadSettingsActions(), m,
        )
    }

    @Test
    fun uploadError() = shot("Upload settings") { m ->
        UploadSettingsScreen(
            UploadSettingsUiState(
                loaded = true,
                settings = UploadSettings(wifiOnly = false, muteAudio = true),
                token = TokenState(set = true, rejected = true),
                test = ConnectionTest.Failed(UploadSettingsViewModel.TEST_TOKEN_REJECTED, tokenRejected = true),
                counts = UploadCounts(failed = 3, done = 5),
            ),
            UploadSettingsActions(), m,
        )
    }

    /** The lower half: the options and the queue with its counts, the review hint and Upload now. */
    @Test
    fun uploadOptionsAndQueue() = shot("Upload settings", scrollTo = "Upload now") { m ->
        UploadSettingsScreen(
            UploadSettingsUiState(
                loaded = true,
                settings = UploadSettings(wifiOnly = true, keepLocal = false, muteAudio = true),
                token = TokenState(set = true),
                counts = UploadCounts(queued = 2, uploading = 1, failed = 1, done = 5, notQueued = 1, needsReview = 2),
            ),
            UploadSettingsActions(), m,
        )
    }

    @Test
    fun uploadTokenDialog() = shot("Upload settings") { m ->
        UploadSettingsScreen(UploadSettingsUiState(loaded = true, tokenDialog = true), UploadSettingsActions(), m)
    }

    @Test
    fun listUploadStages() = shot("Training data") { m ->
        TrainingListScreen(
            TrainingListUiState(
                // the failed row first: its reason and Retry in the frame
                rows = listOf(
                    RecordingRow("4", "Metal · steel 4 mm · drill", "27 Oct 10:20 · 0:23 · 1.0 MB", RecordingStage.SAVED, UploadStage.Failed("No connection to GitHub after 8 tries. Check the network, then retry."), canOpen = true),
                    RecordingRow("1", "Wood · softwood 38 mm · drill", "27 Oct 10:12 · 0:42 · 1.9 MB", RecordingStage.SAVED, UploadStage.Uploading(42), canOpen = true),
                    RecordingRow("3", "Masonry · concrete 120 mm · hammer", "27 Oct 09:40 · 1:58 · 5.3 MB", RecordingStage.SAVED, UploadStage.Queued, canOpen = true),
                    RecordingRow("2", "Wood · plywood 18 mm · drill", "27 Oct 09:58 · 0:31 · 0.4 MB", RecordingStage.SAVED, UploadStage.Done, canOpen = true),
                ),
                loading = false, usedBytes = 9_200_000, freeBytes = 12L shl 30,
                upload = UploadSummary("Tropaion/Drill_Balancer_Data", tokenSet = true, tokenRejected = false, wifiOnly = true),
            ),
            TrainingListActions(), m,
        )
    }

    /** A rejected token: the failed rows lead to the upload settings (a Retry would fail the same way). */
    @Test
    fun listTokenRejected() = shot("Training data") { m ->
        TrainingListScreen(
            TrainingListUiState(
                rows = listOf(
                    RecordingRow("4", "Metal · steel 4 mm · drill", "27 Oct 10:20 · 0:23 · 1.0 MB", RecordingStage.SAVED, UploadStage.Failed(UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED), canOpen = true),
                    RecordingRow("2", "Wood · plywood 18 mm · drill", "27 Oct 09:58 · 0:31 · 0.4 MB", RecordingStage.SAVED, UploadStage.Done, canOpen = true),
                ),
                loading = false, usedBytes = 1_400_000, freeBytes = 12L shl 30,
                upload = UploadSummary("Tropaion/Drill_Balancer_Data", tokenSet = true, tokenRejected = true, wifiOnly = true),
            ),
            TrainingListActions(), m,
        )
    }
}
