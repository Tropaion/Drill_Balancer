package dev.drillbalancer.ui.training

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.ui.Themed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The setup screen shows exactly what Start sends (the remembered setup loads after its first frame). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TrainingSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val remembered = SessionSetup(material = "wood/softwood", thicknessMm = 38.0, bitDiameterMm = 6.5, speedSetting = 2.5)

    @Test
    fun theNumberFieldsShowTheSetupLoadedAfterTheFirstFrame() {
        // As TrainingSetupViewModel: not loaded first (prefs.setup.first() still runs).
        var state by mutableStateOf(TrainingSetupUiState(blocker = "Loading…"))
        compose.setContent {
            Themed {
                TrainingSetupScreen(state, TrainingSetupActions(change = { if (state.loaded) state = state.copy(setup = it) }))
            }
        }
        compose.waitForIdle()
        state = TrainingSetupUiState(setup = remembered, loaded = true)
        compose.waitForIdle()
        for (text in listOf("38", "6.5", "2.5")) {
            compose.onNode(hasSetTextAction() and hasText(text)).assertExists("field with $text")
        }
        compose.onNodeWithText("Start recording session").assertIsEnabled()
    }

    @Test
    fun startWaitsWhileANumberFieldHoldsNoNumber() {
        var state by mutableStateOf(TrainingSetupUiState(setup = remembered, loaded = true))
        compose.setContent {
            Themed {
                TrainingSetupScreen(state, TrainingSetupActions(change = { state = state.copy(setup = it) }))
            }
        }
        val thickness = compose.onNode(hasSetTextAction() and hasText("38"))
        thickness.performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("Start recording session").assertIsNotEnabled()
        compose.onNodeWithText("A number field holds something that is not a number above 0.").assertExists()
        assertNull("not taken", state.setup.thicknessMm)
        compose.onNode(hasSetTextAction() and hasText("0")).performTextReplacement("12,5")
        compose.onNodeWithText("Start recording session").assertIsEnabled()
        assertEquals(12.5, state.setup.thicknessMm!!, 0.0)
        // Above what the setup keeps (sanitized()): it would be sent as unknown.
        compose.onNode(hasSetTextAction() and hasText("6.5")).performScrollTo().performTextReplacement("250")
        compose.onNodeWithText("Start recording session").assertIsNotEnabled()
        assertNull(state.setup.bitDiameterMm)
    }
}
