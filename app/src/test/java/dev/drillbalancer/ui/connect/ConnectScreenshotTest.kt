package dev.drillbalancer.ui.connect

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.ble.BleReadiness
import dev.drillbalancer.ble.ScannedDevice
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.ui.PHONE
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
class ConnectScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val devices = listOf(
        ScannedDevice("F4:12:FA:00:1A:2B", "DrillBalancer-1A2B", -58, bonded = true, lastSeenMs = 0),
        ScannedDevice("F4:12:FA:00:3C:4D", "DrillBalancer-3C4D", -74, bonded = false, lastSeenMs = 0),
        ScannedDevice("F4:12:FA:00:5E:6F", null, -91, bonded = false, lastSeenMs = 0),
    )

    private fun shot(
        state: ConnectUiState,
        readiness: BleReadiness = BleReadiness.READY,
        prompt: PermissionPrompt = PermissionPrompt.ASK,
        legacy: Boolean = false,
    ) {
        compose.setContent { Themed { ConnectScreen(state, readiness, prompt, legacy, ConnectActions()) } }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun deviceList() = shot(ConnectUiState(devices = devices, scanning = true, lastAddress = "F4:12:FA:00:1A:2B"))

    @Test
    fun lastDeviceNotInRange() = shot(
        ConnectUiState(
            scanning = true, lastAddress = "F4:12:FA:00:1A:2B",
            message = UiMessage(DisconnectReason.SLEEP.message, UiMessage.Kind.INFO),
        ),
    )

    @Test
    fun connecting() = shot(
        ConnectUiState(devices = devices, lastAddress = "F4:12:FA:00:1A:2B", connecting = "F4:12:FA:00:3C:4D"),
    )

    @Test
    fun factoryResetRemoveBond() = shot(
        ConnectUiState(
            devices = devices.take(1), scanning = true, lastAddress = "F4:12:FA:00:1A:2B",
            message = UiMessage(DisconnectReason.FACTORY_RESET.message, UiMessage.Kind.WARNING), removeBond = true,
        ),
    )

    @Test
    fun scanFailed() = shot(
        ConnectUiState(scanError = "Scanning was started too often. Wait 30 seconds, then try again."),
    )

    @Test
    fun noPermission() = shot(ConnectUiState(), BleReadiness.MISSING_PERMISSIONS)

    @Test
    fun noPermissionDeniedForGood() = shot(ConnectUiState(), BleReadiness.MISSING_PERMISSIONS, PermissionPrompt.SETTINGS)

    @Test
    fun noPermissionAskOrSettings() = shot(ConnectUiState(), BleReadiness.MISSING_PERMISSIONS, PermissionPrompt.ASK_OR_SETTINGS)

    @Test
    fun noPermissionAndroid11() = shot(ConnectUiState(), BleReadiness.MISSING_PERMISSIONS, legacy = true)

    @Test
    fun bluetoothOff() = shot(ConnectUiState(), BleReadiness.BLUETOOTH_OFF)

    @Test
    fun locationOff() = shot(ConnectUiState(), BleReadiness.LOCATION_OFF, legacy = true)
}
