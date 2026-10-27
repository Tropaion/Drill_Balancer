package dev.drillbalancer.ui.theme

import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.drillbalancer.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app is dark only: its status and navigation bar icons are light, in the system's light theme too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SystemBarsTest {
    @Test
    fun lightIconsOnTheDarkAppInTheLightSystemTheme() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            val a = c.setup().get()
            val bars = WindowCompat.getInsetsController(a.window, a.window.decorView)
            assertFalse("status bar icons light", bars.isAppearanceLightStatusBars)
            assertFalse("navigation bar icons light", bars.isAppearanceLightNavigationBars)
        }
    }
}
