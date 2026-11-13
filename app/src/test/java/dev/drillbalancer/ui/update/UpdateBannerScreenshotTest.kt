package dev.drillbalancer.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.update.releaseNotes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PHONE)
class UpdateBannerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stages() {
        compose.setContent {
            Themed {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (stage in listOf(UpdateStage.Available, UpdateStage.Downloading(42), UpdateStage.Failed("The download was cut off. Please try again."))) {
                        UpdateBanner(UpdateNotice(UpdateKind.FIRMWARE, "0.2.0", stage), {}, {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun appReady() {
        compose.setContent {
            Themed { UpdateBanner(UpdateNotice(UpdateKind.APP, "0.2.0", UpdateStage.Ready), {}, {}, Modifier.padding(16.dp)) }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun availableWithNotes() {
        compose.setContent {
            Themed { UpdateBanner(UpdateNotice(UpdateKind.FIRMWARE, "0.2.0", UpdateStage.Available, releaseNotes(NOTES)), {}, {}, Modifier.padding(16.dp)) }
        }
        compose.onRoot().captureRoboImage()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun notesDialog() {
        compose.setContent { Themed { ReleaseNotesDialog("Firmware 0.2.0", releaseNotes(NOTES), {}) } }
        captureScreenRoboImage()
    }

    private companion object {
        val NOTES = """
            ## Firmware

            - One level screen: the device shows the bubble or the crosshair by how it sits.
            - Zero only in the wall view.

            ## App

            - Shows **Wall** or **Level** instead of the mode tabs.
            - Update the app first, then the firmware.
        """.trimIndent()
    }
}
