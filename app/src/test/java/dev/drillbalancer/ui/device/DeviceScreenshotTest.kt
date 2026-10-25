package dev.drillbalancer.ui.device

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.PowerState
import dev.drillbalancer.protocol.StatusFlags
import dev.drillbalancer.protocol.StatusPacket
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.Tab
import dev.drillbalancer.ui.Themed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class DeviceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = DeviceUiState(
        name = "DrillBalancer-1A2B",
        address = "F4:12:FA:00:1A:2B",
        bonded = true,
        info = FakeDeviceSession.DEFAULT_INFO,
        status = StatusPacket(
            batteryPct = 64, batteryMv = 3912,
            flags = StatusFlags.BATTERY_VALID or StatusFlags.CHARGING or StatusFlags.VBUS or StatusFlags.ACCEL_CAL,
            powerState = PowerState.ACTIVE, calKind = CalKind.IDLE, calSamples = 0, calCoverage = 0, calLastResult = -1,
        ),
        batteryPct = 64,
    )

    private fun show() {
        compose.setContent { Themed { InFrame(Tab.DEVICE) { m -> DeviceScreen(state, DeviceActions(), m) } } }
    }

    @Test
    fun info() {
        show()
        compose.onRoot().captureRoboImage()
    }

    @Test
    @Config(qualifiers = "w360dp-h1300dp-xhdpi")
    fun allActions() {
        show()
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun batteryGaugeInvalid() {
        // The gauge reading is invalid: the status packet says so, and Battery Service
        // then reads 0 (ble_codec_battery_pct). Not a red "0 %".
        val invalid = state.copy(
            status = state.status!!.copy(batteryPct = 0, batteryMv = 0, flags = StatusFlags.ACCEL_CAL),
            batteryPct = 0,
        )
        // The top bar uses the same rule (displayBatteryPct).
        val shell = SHELL.copy(batteryPct = null)
        compose.setContent { Themed { InFrame(Tab.DEVICE, shell) { m -> DeviceScreen(invalid, DeviceActions(), m) } } }
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("0 %").assertDoesNotExist()
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun voltageHasADecimalPointInAnyLocale() {
        // Like every other number in the app and on the device: "3.91 V", not "3,91 V".
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("de-AT"))
        try {
            show()
            compose.onNodeWithText("3.91 V").assertExists()
        } finally {
            Locale.setDefault(before)
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun factoryResetConfirm() {
        // The dialog itself, not a tap on the button: a pressed ripple would not render the same twice.
        compose.setContent {
            Themed {
                InFrame(Tab.DEVICE) { m -> DeviceScreen(state, DeviceActions(), m) }
                FactoryResetDialog({}, {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("reset")
        captureScreenRoboImage()
    }
}
