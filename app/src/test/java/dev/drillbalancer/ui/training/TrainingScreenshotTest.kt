package dev.drillbalancer.ui.training

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.drillbalancer.recorder.AudioLevels
import dev.drillbalancer.recorder.LiveView
import dev.drillbalancer.recorder.Notice
import dev.drillbalancer.recorder.RecordError
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingState
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.TakeSummary
import dev.drillbalancer.recorder.net.WifiBlock
import dev.drillbalancer.ui.InFrame
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.SHELL
import dev.drillbalancer.ui.ShellState
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.ui.Themed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class TrainingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(title: String, shell: ShellState = SHELL, screen: @Composable (Modifier) -> Unit) {
        compose.setContent { Themed { InFrame(null, shell, title = title, screen = screen) } }
        compose.onRoot().captureRoboImage()
    }

    // ---- list ----

    private val rows = listOf(
        RecordingRow("1", "Wood · softwood 38 mm · drill", "26 Oct 14:52 · 0:42 · 1.9 MB", RecordingStage.NEEDS_REVIEW, canOpen = true),
        RecordingRow("2", "Masonry · concrete 120 mm · hammer", "26 Oct 14:31 · 1:58 · 5.3 MB", RecordingStage.INTERRUPTED, canOpen = true),
        RecordingRow("3", "Metal · steel 4 mm · drill", "25 Oct 17:05 · 0:23 · 1.0 MB", RecordingStage.SAVED, canOpen = true),
    )

    @Test
    fun listEmpty() = shot("Training data") { m ->
        TrainingListScreen(TrainingListUiState(loading = false, freeBytes = 12L shl 30), TrainingListActions(), m)
    }

    @Test
    fun listMixed() = shot("Training data") { m ->
        TrainingListScreen(TrainingListUiState(rows = rows, loading = false, usedBytes = 8_600_000, freeBytes = 12L shl 30), TrainingListActions(), m)
    }

    /** Opened from the connect screen: the plain top bar of OfflineTraining, no device. */
    @Test
    fun listWithoutDevice() {
        compose.setContent {
            Themed {
                OfflineFrame("Training data", onBack = {}) { m ->
                    TrainingListScreen(
                        TrainingListUiState(
                            rows = rows.take(1), loading = false, usedBytes = 1_950_000, freeBytes = 12L shl 30,
                            recordBlocker = "Connect to the DrillBalancer to record.",
                        ),
                        TrainingListActions(), m,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun listWhileRecording() = shot("Training data") { m ->
        TrainingListScreen(TrainingListUiState(rows = rows, loading = false, usedBytes = 8_600_000, session = RecordPhase.RECORDING), TrainingListActions(), m)
    }

    // ---- setup ----

    @Test
    fun setup() = shot("New recording") { m ->
        TrainingSetupScreen(
            TrainingSetupUiState(
                SessionSetup(
                    material = "wood/softwood", thicknessMm = 38.0, bitType = "brad_point", bitDiameterMm = 8.0, drillType = "drill", motor = "brushless",
                    drillModel = "Bosch GSR 18V-55", gear = 2,
                ),
                loaded = true,
            ),
            TrainingSetupActions(), m,
            permissionNote = "Start asks for the Nearby devices permission to join the device's Wi-Fi, and to show the recording's notification.",
        )
    }

    // Hammer mode with a drill/driver: the validator's W_MODE_TOOL as a hint, Start still on.
    @Test
    fun setupModeContradictsTheTool() = shot("New recording") { m ->
        TrainingSetupScreen(
            TrainingSetupUiState(
                SessionSetup(material = "masonry/concrete", mode = "hammer", bitType = "masonry_cyl", drillType = "drill", motor = "brushed"),
                loaded = true,
            ),
            TrainingSetupActions(), m,
        )
    }

    // The link is gone and comes back by itself: the shell says so, Start waits for it.
    @Test
    fun setupOtherMaterialHammerNoDevice() = shot("New recording", SHELL.copy(reconnecting = DisconnectReason.LINK_LOSS, batteryPct = null)) { m ->
        TrainingSetupScreen(
            TrainingSetupUiState(
                SessionSetup(material = "other", materialOther = "Cork board", mode = "hammer", micMask = 1),
                loaded = true, blocker = "Connect to the DrillBalancer to record.",
            ),
            TrainingSetupActions(), m,
        )
    }

    @Test
    fun setupLocationOff() = shot("New recording") { m ->
        TrainingSetupScreen(
            TrainingSetupUiState(SessionSetup(material = "wood/softwood", mode = "drill"), loaded = true),
            TrainingSetupActions(), m, wifiBlock = WifiBlock.LOCATION_OFF,
        )
    }

    @Test
    fun setupPermissionRefused() = shot("New recording") { m ->
        TrainingSetupScreen(
            TrainingSetupUiState(SessionSetup(material = "wood/softwood", mode = "drill"), loaded = true),
            TrainingSetupActions(), m, wifiBlock = WifiBlock.PERMISSION,
        )
    }

    // ---- record ----

    private val title = "Wood · softwood 38 mm · drill"
    private val last = TakeSummary("20261026T145212Z_k7q2_9f3a", 42.3, 1_950_000, complete = true, endText = "stopped")
    private val live = LiveView(
        elapsedS = 83.4,
        sparkline = List(125) { 1f + 0.35f * abs(sin(it * 0.9f)) * (if (it in 60..90) 3f else 1f) },
        levels = AudioLevels(-18f, -21.5f),
        kbPerS = 71f, bytes = 5_900_000, imuHz = 448.12, audioHz = 16000.4,
        missingRecords = 0, imuGapSamples = 4211, audioGapFrames = 0, deviceDropped = 0, quietMs = 40,
    )

    private fun record(rec: RecordingState, shell: ShellState = SHELL) = shot("Recording", shell) { m ->
        TrainingRecordScreen(RecordUiState(rec, title), RecordActions(), m)
    }

    private val ap = RecordingState(
        ssid = "DB-1a2b-k7q2", setup = SessionSetup(material = "wood/softwood", thicknessMm = 38.0, mode = "drill"), sessionId = "20261026T1452_k7q2",
    )

    @Test
    fun startingDevice() = record(ap.copy(phase = RecordPhase.STARTING_DEVICE, ssid = null))

    @Test
    fun joiningWifi() = record(ap.copy(phase = RecordPhase.JOINING_WIFI))

    @Test
    fun waitingApproval() = record(ap.copy(phase = RecordPhase.WAITING_APPROVAL))

    @Test
    fun manualJoin() = record(ap.copy(phase = RecordPhase.JOINING_WIFI, pass = "k7q2mz5tw3bdx9ac4hnr"))

    @Test
    fun connecting() = record(ap.copy(phase = RecordPhase.CONNECTING))

    @Test
    fun ready() = record(ap.copy(phase = RecordPhase.READY, takes = 2, lastTake = last))

    @Test
    fun recording() = record(ap.copy(phase = RecordPhase.RECORDING, takes = 2, live = live))

    @Test
    fun recordingWithoutBluetoothAndStalled() = record(
        ap.copy(
            phase = RecordPhase.RECORDING, live = live.copy(quietMs = 4_200, missingRecords = 3, audioGapFrames = 512),
            stalled = true, bleLinked = false,
        ),
        // The session reconnects meanwhile (no battery reading without the link).
        SHELL.copy(reconnecting = DisconnectReason.LINK_LOSS, batteryPct = null),
    )

    @Test
    fun stopping() = record(ap.copy(phase = RecordPhase.STOPPING, live = live))

    @Test
    fun readyAfterAnInterruptedTake() = record(
        ap.copy(
            phase = RecordPhase.READY, takes = 3,
            lastTake = TakeSummary("x", 17.0, 640_000, complete = false, endText = "interrupted: the connection dropped", missingRecords = 12),
            notice = Notice("The connection to the device dropped. The take was cut short and kept as interrupted.", error = true),
        ),
    )

    @Test
    fun done() = record(ap.copy(phase = RecordPhase.DONE, takes = 3, lastTake = last))

    @Test
    fun failed() = record(
        ap.copy(
            phase = RecordPhase.FAILED,
            error = RecordError(
                "Could not start the recording",
                "The phone did not join the device's Wi-Fi DB-1a2b-k7q2: the request was declined, or the network was not found. " +
                    "Keep the phone near the device and try again.",
            ),
        ),
    )
}
