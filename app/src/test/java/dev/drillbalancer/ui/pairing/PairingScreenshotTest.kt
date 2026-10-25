package dev.drillbalancer.ui.pairing

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.PairingPrompt
import dev.drillbalancer.ui.PairingPrompt.Stage
import dev.drillbalancer.ui.Themed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class PairingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(prompt: PairingPrompt) {
        compose.setContent { Themed { PairingScreen(prompt, "DrillBalancer-1A2B", {}, {}, {}) } }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun explain() = shot(PairingPrompt(Stage.EXPLAIN))

    @Test
    fun waitingForCode() = shot(PairingPrompt(Stage.PAIRING))

    @Test
    fun failed() = shot(PairingPrompt(Stage.FAILED, DeviceError.PairingFailed().message))

    @Test
    fun staleBond() = shot(PairingPrompt(Stage.STALE_BOND, DeviceError.StaleBond().message))
}
