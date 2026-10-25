package dev.drillbalancer.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.CalState
import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsField
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Tab
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.components.UiMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val device = DeviceSettings.DEFAULTS.copy(cal = CalState(mount = false, accel = true))

    private fun show(state: SettingsUiState) {
        compose.setContent { Themed { InFrame(Tab.SETTINGS) { m -> SettingsScreen(state, SettingsActions(), m) } } }
    }

    @Test
    fun loaded() {
        show(SettingsUiState(base = device, edited = device))
        compose.onRoot().captureRoboImage()
    }

    // Tall window: every field at once, with three edits pending
    @Test
    @Config(qualifiers = "w360dp-h2300dp-xhdpi")
    fun allFieldsEdited() {
        show(SettingsUiState(base = device, edited = device.copy(toleranceDeg = 0.25f, sleepAfterS = 300, bleEnabled = false)))
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun saveRejected() {
        val edited = device.copy(toleranceDeg = 1.5f, nearDeg = 3f)
        show(SettingsUiState(base = device, edited = edited, message = UiMessage(AttError.JSON_RANGE.message)))
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun saved() {
        show(SettingsUiState(base = device, edited = device, message = UiMessage("Saved.", UiMessage.Kind.SUCCESS)))
        compose.onRoot().captureRoboImage()
    }

    // Typed values; the dialog is shown directly (a tapped field's ripple does not render the same twice).
    @OptIn(ExperimentalRoborazziApi::class)
    private fun entry(text: String) {
        compose.setContent {
            Themed {
                InFrame(Tab.SETTINGS) { m -> SettingsScreen(SettingsUiState(base = device, edited = device), SettingsActions(), m) }
                ValueEntryDialog(SettingsField.TOL, device, {}, {}, initialText = text)
            }
        }
        captureScreenRoboImage()
    }

    // Out of range: the dialog says what the firmware accepts.
    @Test
    fun entryOutOfRange() = entry("7,5")

    // Valid, but it moves a dependent field: the dialog announces it.
    @Test
    fun entryMovesNearBand() = entry("3")
}
