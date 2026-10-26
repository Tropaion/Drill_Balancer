package dev.drillbalancer.ui.training

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.ui.Themed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * The drill type and motor are picked in plain words; hammer mode with a drill/driver shows
     * the validator's warning as a hint, and Start still records.
     */
    @Test
    fun theToolIsPickedAndAContradictingModeIsHinted() {
        var state by mutableStateOf(TrainingSetupUiState(setup = remembered, loaded = true))
        compose.setContent {
            Themed {
                TrainingSetupScreen(state, TrainingSetupActions(change = { state = state.copy(setup = it) }))
            }
        }
        val hint = "Hammer mode does not fit a drill/driver (it has no hammer): check the mode or the drill type. The dataset check will warn about it."
        compose.onNodeWithText("Drill type").performScrollTo().performClick()
        for (label in listOf("Drill/driver", "Combi drill (hammer)", "SDS rotary hammer", "Impact driver", "Unknown", "Other…")) {
            assertTrue(label, compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithText("Drill/driver").performClick()
        assertEquals("drill", state.setup.drillType)
        compose.onNodeWithText(hint).assertDoesNotExist()
        compose.onNodeWithText("Hammer").performScrollTo().performClick()
        assertEquals("hammer", state.setup.mode)
        compose.onNodeWithText(hint).assertExists()
        compose.onNodeWithText("Start recording session").assertIsEnabled()

        compose.onNodeWithText("Motor").performScrollTo().performClick()
        for (label in listOf("Brushed", "Brushless", "Corded (AC)")) compose.onNodeWithText(label).assertExists(label)
        compose.onNodeWithText("Corded (AC)").performClick()
        assertEquals("universal_ac", state.setup.motor)

        // a combi drill hammers: no hint; "other" asks which
        compose.onNodeWithText("Drill type").performScrollTo().performClick()
        compose.onNodeWithText("Combi drill (hammer)").performClick()
        compose.onNodeWithText(hint).assertDoesNotExist()
        compose.onNodeWithText("Drill type").performScrollTo().performClick()
        compose.onNodeWithText("Other…").performClick()
        compose.onNode(hasSetTextAction() and hasText("Which drill type")).performScrollTo().performTextReplacement("Magnetic drill")
        assertEquals("other" to "Magnetic drill", state.setup.drillType to state.setup.drillTypeOther)
    }

    @Test
    fun theHintIsTheValidatorsPairs() {
        val conflicts = listOf("hammer" to "drill", "hammer" to "impact_driver", "screw" to "rotary_hammer")
        for (mode in listOf("drill", "hammer", "screw")) {
            for (type in listOf("drill", "combi_drill", "rotary_hammer", "impact_driver", "unknown", "other")) {
                val hint = modeToolHint(SessionSetup(mode = mode, drillType = type))
                assertEquals("$mode / $type", (mode to type) in conflicts, hint != null)
            }
        }
        assertEquals(
            "Screw mode does not fit an SDS rotary hammer: check the mode or the drill type. The dataset check will warn about it.",
            modeToolHint(SessionSetup(mode = "screw", drillType = "rotary_hammer")),
        )
    }
}
