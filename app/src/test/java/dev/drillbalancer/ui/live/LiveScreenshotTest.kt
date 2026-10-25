package dev.drillbalancer.ui.live

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.protocol.AngleFlags
import dev.drillbalancer.protocol.AnglePacket
import dev.drillbalancer.protocol.LevelMode
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.ShellState
import dev.drillbalancer.ui.Tab
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.components.UiMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug
// Images go to src/test/screenshots/<test class>.<test method>.png
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class LiveScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val base = AngleFlags.IMU_OK or AngleFlags.STILL

    private fun shot(state: LiveUiState, packet: AnglePacket?, fontScale: Float = 1f, shell: ShellState = SHELL) {
        compose.setContent {
            Themed(fontScale) {
                InFrame(Tab.LIVE, shell) { m -> LiveScreen(state, { packet }, {}, {}, {}, {}, m) }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private fun wall(flags: Int, el: Float, yaw: Float = 0f) =
        AnglePacket.of(LevelMode.WALL, base or flags, elevationDeg = el, yawDeg = yaw, deviationDeg = el)

    private val zeroed = LiveUiState(hasAngle = true, zeroSet = true, yawValid = true)

    @Test
    fun wallOnTarget() = shot(zeroed, wall(AngleFlags.ON_TARGET or AngleFlags.ZERO_SET or AngleFlags.YAW_VALID, 0.2f, 0.1f))

    @Test
    fun wallNearYawStale() = shot(
        zeroed.copy(yawStale = true),
        wall(AngleFlags.NEAR or AngleFlags.ZERO_SET or AngleFlags.YAW_VALID or AngleFlags.YAW_STALE, -1.4f, -6.4f),
    )

    @Test
    fun wallOffNoZero() = shot(LiveUiState(hasAngle = true), wall(0, 12.3f), shell = SHELL.copy(batteryPct = 8, charging = true))

    @Test
    fun wallLargeFont() = shot(
        zeroed.copy(yawStale = true),
        wall(AngleFlags.NEAR or AngleFlags.ZERO_SET or AngleFlags.YAW_VALID or AngleFlags.YAW_STALE, -12.3f, -6.4f),
        fontScale = 2f,
    )

    @Test
    fun floorBubbleNear() = shot(
        LiveUiState(mode = LevelMode.FLOOR, hasAngle = true),
        AnglePacket.of(LevelMode.FLOOR, base or AngleFlags.NEAR, bubbleXDeg = 1.1f, bubbleYDeg = 0.8f, deviationDeg = 1.36f),
    )

    @Test
    fun floorOffClampedLargeFont() = shot(
        LiveUiState(mode = LevelMode.FLOOR, hasAngle = true),
        AnglePacket.of(LevelMode.FLOOR, AngleFlags.IMU_OK, bubbleXDeg = -9f, bubbleYDeg = -4f, deviationDeg = 9.85f),
        fontScale = 1.6f,
    )

    @Test
    fun angleNear() = shot(
        LiveUiState(mode = LevelMode.ANGLE, hasAngle = true, targetDeg = 45f),
        AnglePacket.of(LevelMode.ANGLE, base or AngleFlags.NEAR, elevationDeg = 43.8f, deviationDeg = -1.2f),
    )

    @Test
    fun imuNotOk() = shot(
        LiveUiState(hasAngle = true, imuOk = false, zeroSet = true),
        AnglePacket.of(LevelMode.WALL, AngleFlags.ZERO_SET),
    )

    // While reconnecting the session has cleared the angle and battery (BleDeviceSession).
    @Test
    fun reconnectingWithError() = shot(
        LiveUiState(message = UiMessage("The device is busy. Try again.")),
        null,
        shell = SHELL.copy(reconnecting = DisconnectReason.LINK_LOSS, batteryPct = null, charging = false),
    )
}
