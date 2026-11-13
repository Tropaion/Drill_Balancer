package dev.drillbalancer.ui.update

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.update.releaseNotes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = PHONE)
class UpdateBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(notes: String, stage: UpdateStage = UpdateStage.Available) = compose.setContent {
        Themed { UpdateBanner(UpdateNotice(UpdateKind.FIRMWARE, "0.2.0", stage, releaseNotes(notes)), {}, {}) }
    }

    @Test
    fun whatsNewShowsTheNotes() {
        show("## Firmware\n- One level screen")
        compose.onNodeWithText("What's new").performClick()
        compose.onNode(hasText("Firmware 0.2.0") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("One level screen") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun whatsNewStaysWhileTheImageIsFetched() {
        show("- One level screen", UpdateStage.Downloading(42))
        compose.onNodeWithText("What's new").assertExists()
    }

    @Test
    fun noNotesNoWhatsNew() {
        show(" \n")
        compose.onNodeWithText("What's new").assertDoesNotExist()
    }
}
