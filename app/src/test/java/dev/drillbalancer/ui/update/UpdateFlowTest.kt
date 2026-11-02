package dev.drillbalancer.ui.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.ui.AppRoot
import dev.drillbalancer.ui.PHONE
import dev.drillbalancer.ui.Themed
import dev.drillbalancer.ui.awaitReal
import dev.drillbalancer.ui.firmwareBytes
import dev.drillbalancer.ui.training.TrainingDeps
import dev.drillbalancer.update.AppUpdates
import dev.drillbalancer.update.ReleaseAsset
import dev.drillbalancer.update.ReleaseDownloader
import dev.drillbalancer.update.ReleaseInfo
import dev.drillbalancer.update.ReleaseResult
import dev.drillbalancer.update.ReleaseVersion
import dev.drillbalancer.update.UpdateChecker
import dev.drillbalancer.update.UpdateDeps
import dev.drillbalancer.update.UpdateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** The path through the real screens: connected, a notice, Download, the Firmware screen with the image. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = PHONE)
class UpdateFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private object Prefs : SetupPrefs {
        override val setup = MutableStateFlow(SessionSetup())
        override suspend fun save(setup: SessionSetup) = Unit
    }

    private val fake = FakeDeviceSession()
    private val labeler = AutoLabeler({ TestParams.loaded })
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private val training by lazy {
        val controller = RecordingController(
            fake, store, StreamLinkFactory { throw LinkException("no network in this test", LinkException.Kind.CONNECT) },
            { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), scope,
        )
        TrainingDeps(controller, store, Prefs, labeler)
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun downloadOpensTheFirmwareScreenWithTheImage() {
        val image = firmwareBytes("0.2.0", size = 4096)
        server.enqueue(MockResponse.Builder().body(Buffer().write(image)).build())
        val asset = ReleaseAsset("drillbalancer-0.2.0.bin", server.url("/fw.bin").toString(), image.size.toLong())
        val found = ReleaseResult.Found(ReleaseInfo(ReleaseVersion(0, 2, 0), asset, null))
        val updates = UpdateDeps(UpdateChecker({ found }), UpdateStore(MemoryDataStore()), ReleaseDownloader(server.url("/").toString()))

        compose.setContent { Themed { AppRoot(fake, { flowOf(emptyList()) }, flowOf(null), training, updates) } }
        compose.awaitReal { has("Firmware 0.2.0 available") }
        compose.onNodeWithText("Download").performClick()
        compose.awaitReal { has("drillbalancer-0.2.0.bin") }
        assertTrue(has("Firmware update"))
    }

    private fun updates(info: ReleaseInfo) =
        UpdateDeps(UpdateChecker({ ReleaseResult.Found(info) }), UpdateStore(MemoryDataStore()), ReleaseDownloader(server.url("/").toString()), AppUpdates(ReleaseVersion(0, 1, 0), FakeInstaller(tmp.newFolder())))

    @Test
    fun theConnectScreenShowsTheAppNotice() {
        val asset = ReleaseAsset("drillbalancer-0.2.0.apk", server.url("/app.apk").toString(), 1000)
        val updates = updates(ReleaseInfo(ReleaseVersion(0, 2, 0), null, asset))
        val session = FakeDeviceSession(connected = false)
        compose.setContent { Themed { AppRoot(session, { flowOf(emptyList()) }, flowOf(null), training, updates) } }
        compose.awaitReal { has("App 0.2.0 available") }
    }

    @Test
    fun aDownloadThatEndsOnTheFirmwareScreenLoadsThere() {
        val image = firmwareBytes("0.2.0", size = 4096)
        server.enqueue(MockResponse.Builder().body(Buffer().write(image)).throttleBody(1024, 500, TimeUnit.MILLISECONDS).build())
        val asset = ReleaseAsset("drillbalancer-0.2.0.bin", server.url("/fw.bin").toString(), image.size.toLong())
        val updates = updates(ReleaseInfo(ReleaseVersion(0, 2, 0), asset, null))
        compose.setContent { Themed { AppRoot(fake, { flowOf(emptyList()) }, flowOf(null), training, updates) } }
        compose.awaitReal { has("Firmware 0.2.0 available") }
        compose.onNodeWithText("Download").performClick()
        compose.onNode(hasText("Device") and hasClickAction()).performClick()
        compose.onNodeWithText("Firmware update").performScrollTo().performClick()
        compose.awaitReal { has("drillbalancer-0.2.0.bin") }
    }
}
