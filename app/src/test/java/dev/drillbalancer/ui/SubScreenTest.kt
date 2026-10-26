package dev.drillbalancer.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.drillbalancer.recorder.RecordPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SubScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun subScreensHaveTitlesTabsHaveNone() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Dest.Live) {
                composable<Dest.Live> {}
                composable<Dest.Device> {}
                composable<Dest.Firmware> {}
                composable<Dest.Training> {}
                composable<Dest.TrainingSetup> {}
                composable<Dest.TrainingRecord> {}
            }
        }
        compose.runOnIdle { assertNull(subScreenOf(nav.currentDestination)) }
        for ((dest, sub) in listOf(
            Dest.Device to null,
            Dest.Firmware to SubScreen.FIRMWARE,
            Dest.Training to SubScreen.TRAINING,
            Dest.TrainingSetup to SubScreen.TRAINING_SETUP,
            Dest.TrainingRecord to SubScreen.TRAINING_RECORD,
        )) {
            compose.runOnIdle { nav.navigate(dest) }
            compose.runOnIdle { assertEquals("$dest", sub, subScreenOf(nav.currentDestination)) }
        }
        assertNull(subScreenOf(null))
        assertEquals(
            listOf("Firmware update", "Training data", "New recording", "Recording"),
            SubScreen.entries.map { it.title },
        )
        // No tab is a sub-screen.
        for (t in Tab.entries) assertNull(SubScreen.entries.firstOrNull { it.route == t.dest::class })
    }

    @Test
    fun aSessionWithoutTheLinkStaysOnScreenUntilLeft() {
        var opened = false
        fun show(showMain: Boolean, phase: RecordPhase) = offlineTraining(opened, showMain, phase).also { opened = it }
        assertFalse("connected", show(true, RecordPhase.RECORDING))
        assertTrue("link given up", show(false, RecordPhase.RECORDING))
        // The session ends without the link (the device's grace, reconnects that failed): its end is shown.
        assertTrue(show(false, RecordPhase.DONE))
        assertTrue(show(false, RecordPhase.FAILED))
        assertTrue(show(false, RecordPhase.IDLE))
        opened = false   // the user left it
        assertFalse(show(false, RecordPhase.IDLE))
        // Opened from the connect screen; the link coming back ends it.
        opened = true
        assertTrue(show(false, RecordPhase.IDLE))
        assertFalse(show(true, RecordPhase.IDLE))
        assertFalse(show(false, RecordPhase.IDLE))
    }
}
