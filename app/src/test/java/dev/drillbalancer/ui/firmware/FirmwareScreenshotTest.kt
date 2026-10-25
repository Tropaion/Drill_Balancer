package dev.drillbalancer.ui.firmware

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.protocol.OtaException
import dev.drillbalancer.protocol.OtaProgress
import dev.drillbalancer.protocol.OtaStatus
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.ShellState
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.firmware
import dev.drillbalancer.ui.firmwareBytes
import dev.drillbalancer.ui.firmware.FirmwareUiState.Phase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class FirmwareScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val image = firmware("0.2.0")
    private val picked = FirmwareUiState(deviceVersion = "0.1.0", fileName = "drillbalancer.bin", image = image)

    private fun shot(state: FirmwareUiState, shell: ShellState = SHELL) {
        compose.setContent {
            Themed { InFrame(null, shell, title = "Firmware update") { m -> FirmwareScreen(state, FirmwareActions(), m) } }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun noFile() = shot(FirmwareUiState(deviceVersion = "0.1.0"))

    @Test
    fun picked() = shot(picked)

    @Test
    fun sameVersion() = shot(picked.copy(deviceVersion = "0.2.0"))

    @Test
    fun wrongProject() {
        val error = FirmwareImage.parse(firmwareBytes(project = "blink")) as FirmwareParseResult.Error
        shot(FirmwareUiState(deviceVersion = "0.1.0", fileName = "blink.bin", fileError = error.message))
    }

    @Test
    fun sending() = shot(
        picked.copy(phase = Phase.TRANSFERRING, progress = OtaProgress(OtaProgress.Phase.SENDING, 562_000, image.size, 21_300f)),
    )

    @Test
    fun verifying() = shot(
        picked.copy(phase = Phase.TRANSFERRING, progress = OtaProgress(OtaProgress.Phase.VERIFYING, image.size, image.size, 21_300f)),
    )

    @Test
    fun rebooting() = shot(
        picked.copy(phase = Phase.REBOOTING),
        // While reconnecting the session has no battery reading (SessionViewModel).
        SHELL.copy(reconnecting = DisconnectReason.FIRMWARE_UPDATED, batteryPct = null, charging = false),
    )

    @Test
    fun done() = shot(picked.copy(deviceVersion = "0.2.0", phase = Phase.DONE, newVersion = "0.2.0"))

    @Test
    fun rolledBack() = shot(picked.copy(phase = Phase.DONE, newVersion = "0.1.0"))

    @Test
    fun hashMismatch() = shot(
        picked.copy(
            phase = Phase.FAILED,
            error = otaError(OtaException("FINISH failed: ${OtaStatus.HASH_MISMATCH.message}", OtaStatus.HASH_MISMATCH)),
        ),
    )
}
