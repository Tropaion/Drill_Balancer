package dev.drillbalancer.ui.training.upload

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.settings.SettingsActions
import dev.drillbalancer.ui.settings.SettingsScreen
import dev.drillbalancer.ui.settings.SettingsUiState
import dev.drillbalancer.ui.settings.help
import dev.drillbalancer.ui.settings.label
import dev.drillbalancer.ui.training.RecordingRow
import dev.drillbalancer.ui.training.TrainingListActions
import dev.drillbalancer.ui.training.TrainingListScreen
import dev.drillbalancer.ui.training.TrainingListUiState
import dev.drillbalancer.ui.training.UploadSummary
import dev.drillbalancer.upload.FailureKind
import dev.drillbalancer.upload.TokenState
import dev.drillbalancer.upload.UploadCounts
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The upload settings screen's controls and the list's upload line and Retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UploadScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTokenIsTypedIntoTheDialogOnly() {
        val set = ArrayList<String>()
        var opened = 0
        var state by mutableStateOf(UploadSettingsUiState(loaded = true))
        compose.setContent {
            Themed {
                UploadSettingsScreen(
                    state,
                    UploadSettingsActions(openTokenDialog = { opened++; state = state.copy(tokenDialog = true) }, setToken = { set += it }),
                )
            }
        }
        compose.onNodeWithText("No token set").assertExists()
        compose.onNodeWithText("Set token").performScrollTo().performClick()
        assertEquals(1, opened)
        val field = compose.onNode(hasText("Token") and hasAnyAncestor(isDialog()))
        field.performTextInput("fake_token_abc123")
        // typed hidden: a password field that shows bullets, not the token
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("\u2022".repeat(17))))
        compose.onNode(hasText("Set") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("fake_token_abc123"), set)
    }

    @Test
    fun removeAsksFirst() {
        var removed = 0
        compose.setContent {
            Themed { UploadSettingsScreen(UploadSettingsUiState(loaded = true, token = TokenState(set = true)), UploadSettingsActions(removeToken = { removed++ })) }
        }
        compose.onNodeWithText("Token set").assertExists()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        assertEquals(0, removed)
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun aFailedUploadSaysWhyWithRetry() {
        val retried = ArrayList<String>()
        var opened = 0
        val rows = listOf(
            RecordingRow("a", "Wood · softwood 38 mm · drill", "26 Oct 14:52 · 0:42 · 1.9 MB", RecordingStage.SAVED, UploadStage.Failed("Repo not found or no access."), canOpen = true),
        )
        compose.setContent {
            Themed {
                TrainingListScreen(
                    TrainingListUiState(rows = rows, loading = false, upload = UploadSummary("Tropaion/Drill_Balancer_Data", true, false, true)),
                    TrainingListActions(retryUpload = { retried += it }, openUploadSettings = { opened++ }),
                )
            }
        }
        compose.onNodeWithText("Repo not found or no access.").assertExists()
        compose.onNodeWithText("Retry upload").performClick()
        assertEquals(listOf("a"), retried)
        compose.onNodeWithText("Tropaion/Drill_Balancer_Data · Wi-Fi only").performClick()
        assertEquals(1, opened)
    }

    /** A failure only a token fixes (missing, rejected) offers the upload settings, not a Retry that would fail the same way. */
    @Test
    fun aTokenFailureOffersTheSettingsNotRetry() {
        val retried = ArrayList<String>()
        var opened = 0
        var summary by mutableStateOf(UploadSummary("Tropaion/Drill_Balancer_Data", tokenSet = true, tokenRejected = true, wifiOnly = true))
        val rows = listOf(
            RecordingRow("a", "Wood · softwood 38 mm · drill", "26 Oct 14:52 · 0:42 · 1.9 MB", RecordingStage.SAVED, UploadStage.Failed(UploadQueue.TOKEN_REJECTED, FailureKind.TOKEN_REJECTED), canOpen = true),
        )
        compose.setContent {
            Themed {
                TrainingListScreen(
                    TrainingListUiState(rows = rows, loading = false, upload = summary),
                    TrainingListActions(retryUpload = { retried += it }, openUploadSettings = { opened++ }),
                )
            }
        }
        compose.onNodeWithText("Retry upload").assertDoesNotExist()
        compose.onNode(hasText("Upload settings") and hasClickAction() and !hasText("Tropaion", substring = true)).performClick()
        assertEquals(1, opened)
        // no token set: the same, whatever the row's message
        summary = summary.copy(tokenSet = false, tokenRejected = false)
        compose.onNodeWithText("Retry upload").assertDoesNotExist()
        assertTrue(retried.isEmpty())
    }

    /** With the token fine, the failure's kind decides: a setting only the upload settings fix offers them, the others Retry. */
    @Test
    fun theFailureKindDecidesRetryOrSettings() {
        val retried = ArrayList<String>()
        var opened = 0
        val rows = listOf(
            RecordingRow("a", "Wood · softwood 38 mm · drill", "26 Oct 14:52 · 0:42 · 1.9 MB", RecordingStage.SAVED, UploadStage.Failed("The repository \"x\" is not owner/name. Fix it in Upload settings.", FailureKind.BAD_REPO), canOpen = true),
            RecordingRow("b", "Wood · plywood 18 mm · drill", "26 Oct 14:58 · 0:31 · 0.4 MB", RecordingStage.SAVED, UploadStage.Failed("Not uploaded: the data repo's copy was changed.", FailureKind.CONFLICT), canOpen = true),
        )
        compose.setContent {
            Themed {
                TrainingListScreen(
                    TrainingListUiState(rows = rows, loading = false, upload = UploadSummary("Tropaion/Drill_Balancer_Data", true, false, true)),
                    TrainingListActions(retryUpload = { retried += it }, openUploadSettings = { opened++ }),
                )
            }
        }
        compose.onNode(hasText("Upload settings") and hasClickAction() and !hasText("Tropaion", substring = true)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Retry upload").performScrollTo().performClick()
        assertEquals(listOf("b"), retried)
    }

    /** Until DataStore answers: neither the defaults (no token, Wi-Fi only) nor controls that would change them. */
    @Test
    fun nothingShowsBeforeTheSettingsAreRead() {
        var state by mutableStateOf(UploadSettingsUiState(loaded = false, token = TokenState()))
        compose.setContent { Themed { UploadSettingsScreen(state, UploadSettingsActions()) } }
        compose.onNodeWithText("Reading the upload settings…").assertExists()
        compose.onNodeWithText("No token set").assertDoesNotExist()
        compose.onNodeWithText("Set token").assertDoesNotExist()
        compose.onNodeWithText("Wi-Fi only").assertDoesNotExist()
        state = state.copy(loaded = true, token = TokenState(set = true))
        compose.onNodeWithText("Token set").assertExists()
    }

    /**
     * Test connection is off while the field holds an unsaved repository (it tests the saved
     * one); Upload now while the token is rejected, and while nothing waits to be sent.
     */
    @Test
    fun testAndUploadNowAreOffWhenTheyCannotWork() {
        var state by mutableStateOf(UploadSettingsUiState(loaded = true, token = TokenState(set = true), counts = UploadCounts(notQueued = 1)))
        compose.setContent { Themed { UploadSettingsScreen(state, UploadSettingsActions()) } }
        compose.onNodeWithText("Test connection").performScrollTo().assertIsEnabled()
        state = state.copy(repoText = "me/other")
        compose.onNodeWithText("Test connection").assertIsNotEnabled()
        compose.onNodeWithText("Save the repository first: the test asks GitHub about the saved one.").assertExists()
        compose.onNodeWithText("Upload now").performScrollTo().assertIsEnabled()
        state = state.copy(repoText = state.settings.repo, token = TokenState(set = true, rejected = true))
        compose.onNodeWithText("Upload now").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Replace the token first: GitHub rejected it.").assertExists()
        // every reviewed recording uploaded (or uploading): nothing for Upload now to send
        state = state.copy(token = TokenState(set = true), counts = UploadCounts(uploading = 1, done = 4, needsReview = 1))
        compose.onNodeWithText("Upload now").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Nothing waits to be sent.").assertExists()
        for (c in listOf(UploadCounts(queued = 1), UploadCounts(failed = 1), UploadCounts(notQueued = 1))) {
            state = state.copy(counts = c)
            compose.onNodeWithText("Upload now").performScrollTo().assertIsEnabled()
        }
        // not counted yet (or the count failed): not known to be nothing, so it stays on
        state = state.copy(counts = null)
        compose.onNodeWithText("Upload now").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Nothing waits to be sent.").assertDoesNotExist()
    }

    /** The token's creation steps show until a token is set (and again when GitHub rejects it). */
    @Test
    fun theTokenHelpGoesOnceATokenIsSet() {
        var state by mutableStateOf(UploadSettingsUiState(loaded = true, token = TokenState()))
        compose.setContent { Themed { UploadSettingsScreen(state, UploadSettingsActions()) } }
        compose.onNodeWithText(TOKEN_HELP).assertExists()
        state = state.copy(token = TokenState(set = true))
        compose.onNodeWithText("Token set").assertExists()
        compose.onNodeWithText(TOKEN_HELP).assertDoesNotExist()
        state = state.copy(token = TokenState(set = true, rejected = true))
        compose.onNodeWithText(TOKEN_HELP).assertExists()
    }

    /** Each option is one switch node with its title for TalkBack, and the whole row toggles it (not only the switch). */
    @Test
    fun theOptionsAreLabelledSwitchRows() {
        val wifi = ArrayList<Boolean>()
        val mute = ArrayList<Boolean>()
        compose.setContent {
            Themed { UploadSettingsScreen(UploadSettingsUiState(loaded = true), UploadSettingsActions(setWifiOnly = { wifi += it }, setMuteAudio = { mute += it })) }
        }
        val row = compose.onNode(isToggleable() and hasText("Wi-Fi only"))
        row.performScrollTo().assertIsOn()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithText("Wi-Fi only").performClick()
        assertEquals(listOf(false), wifi)
        compose.onNodeWithText("Uploads wait for Wi-Fi (no mobile data).").performClick()
        assertEquals(listOf(false, false), wifi)
        compose.onNode(isToggleable() and hasText("Mute audio while the motor is off")).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), mute)
        // one switch per option: the Switch is no node of its own
        assertEquals(3, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    /** The device settings' switch rows (invert dot, Bluetooth) the same way. */
    @Test
    fun theSettingsSwitchesAreLabelledRows() {
        val ble = ArrayList<Boolean>()
        val dot = ArrayList<Boolean>()
        val d = DeviceSettings.DEFAULTS
        compose.setContent {
            Themed {
                SettingsScreen(
                    SettingsUiState(base = d, edited = d),
                    SettingsActions(setBle = { ble += it }, setDotInvert = { dot += it }),
                )
            }
        }
        compose.onNode(isToggleable() and hasText(SettingsField.DOT_INV.label, substring = true))
            .performScrollTo().assertIsOff()
        compose.onNodeWithText(SettingsField.DOT_INV.help).performClick()
        assertEquals(listOf(true), dot)
        val row = compose.onNode(isToggleable() and hasText(SettingsField.BLE.label, substring = true))
        row.performScrollTo().assertIsOn()
        compose.onNodeWithText("Lets this app connect.").performClick()
        assertEquals(listOf(false), ble)
        assertEquals(2, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }
}
