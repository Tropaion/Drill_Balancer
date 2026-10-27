package dev.drillbalancer.ui.training.upload

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.training.ScopedUploadSettings
import dev.drillbalancer.ui.training.ScreenStore
import dev.drillbalancer.upload.FakeGitHub
import dev.drillbalancer.upload.FakeScheduler
import dev.drillbalancer.upload.FakeTokenStore
import dev.drillbalancer.upload.FakeUploadPrefs
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadServices
import dev.drillbalancer.upload.UploadSettings
import dev.drillbalancer.upload.UploadTestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upload settings opened without a device (OfflineTraining, no nav entry): their view model
 * lives only while they are open, so closing them stops its collectors and the next open starts
 * afresh (no stale Test connection result or message).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UploadSettingsScopeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun close() = scope.cancel()

    @Test
    fun closingTheScreenClearsItsViewModel() {
        val store = UploadTestData.store(tmp.root)
        val prefs = FakeUploadPrefs()
        val tokens = FakeTokenStore(FakeGitHub.TOKEN)
        val services = UploadServices(UploadQueue(store, FakeScheduler(), prefs, tokens, scope), prefs, tokens) { _, _, _ -> error("no network here") }
        var open by mutableStateOf(true)
        compose.setContent { Themed { if (open) ScopedUploadSettings(services, store) } }
        compose.waitForIdle()
        val holder = ViewModelProvider(compose.activity)["upload-settings", ScreenStore::class.java]
        assertTrue(holder.open)
        val vm = ViewModelProvider(holder.owner)[UploadSettingsViewModel::class.java]
        vm.editRepo("me/typed_not_saved")
        compose.waitForIdle()
        assertSame("the same while it shows", vm, ViewModelProvider(holder.owner)[UploadSettingsViewModel::class.java])

        open = false
        compose.waitForIdle()
        assertFalse("closed: cleared", holder.open)

        open = true
        compose.waitForIdle()
        val again = ViewModelProvider(holder.owner)[UploadSettingsViewModel::class.java]
        assertNotSame(vm, again)
        assertEquals(UploadSettings.DEFAULT_REPO, again.state.value.repoText)
    }
}
