package dev.drillbalancer.ui.calibration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.protocol.CalKind
import dev.drillbalancer.protocol.CalResult
import dev.drillbalancer.protocol.CoverageDir
import dev.drillbalancer.protocol.MountCalResult
import dev.drillbalancer.protocol.SensorCalResult
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Tab
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
class CalibrationScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val idle = CalibrationUiState(known = true, sensorDone = true, mountDone = false)

    private fun shot(state: CalibrationUiState) {
        compose.setContent {
            Themed { InFrame(Tab.CALIBRATE) { m -> CalibrationScreen(state, CalibrationActions(), m) } }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun overview() = shot(idle.copy(sensorDone = false))

    @Test
    fun sensorMidway() = shot(
        idle.copy(
            running = CalKind.ACCEL, samples = 4,
            coverage = setOf(CoverageDir.POS_Z, CoverageDir.NEG_Y, CoverageDir.POS_X),
        ),
    )

    @Test
    fun sensorAllSides() = shot(idle.copy(running = CalKind.ACCEL, samples = 9, coverage = CoverageDir.entries.toSet()))

    @Test
    fun sensorOkMountReset() = shot(idle.copy(finished = FinishedCal(CalKind.ACCEL, CalResult.SensorOkMountReset)))

    @Test
    fun sensorOkFirst() = shot(idle.copy(finished = FinishedCal(CalKind.ACCEL, CalResult.Sensor(SensorCalResult.OK))))

    @Test
    fun sensorOutOfRange() = shot(
        idle.copy(finished = FinishedCal(CalKind.ACCEL, CalResult.Sensor(SensorCalResult.OUT_OF_RANGE))),
    )

    @Test
    fun mountCollecting() = shot(idle.copy(running = CalKind.MOUNT, samples = 143))

    @Test
    fun mountSolving() = shot(idle.copy(running = CalKind.MOUNT, samples = 255, finishing = true))

    @Test
    fun mountOk() = shot(idle.copy(mountDone = true, finished = FinishedCal(CalKind.MOUNT, CalResult.Mount(MountCalResult.OK))))

    @Test
    fun mountTooLittleRoll() = shot(
        idle.copy(finished = FinishedCal(CalKind.MOUNT, CalResult.Mount(MountCalResult.TOO_LITTLE_ROLL))),
    )

    @Test
    fun mountNotStored() = shot(idle.copy(finished = FinishedCal(CalKind.MOUNT, CalResult.NotStored)))
}
