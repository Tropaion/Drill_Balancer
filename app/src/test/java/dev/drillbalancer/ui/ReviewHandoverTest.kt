package dev.drillbalancer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.LabelTestData
import dev.drillbalancer.recorder.label.TestParams
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.ui.training.OfflineScreens
import dev.drillbalancer.ui.training.ReviewStores
import dev.drillbalancer.ui.training.TrainingDeps
import dev.drillbalancer.ui.training.setupTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A review does not need the device: the Bluetooth link ending does not close it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReviewHandoverTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private object Prefs : SetupPrefs {
        override val setup = MutableStateFlow(SessionSetup())
        override suspend fun save(setup: SessionSetup) = Unit
    }

    private val fake = FakeDeviceSession()
    private val labeler = AutoLabeler({ TestParams.loaded })
    private val store by lazy { RecordingStore(File(tmp.root, "recordings"), labeler = labeler) }
    private val id by lazy { LabelTestData.install(store, "fx_stuck_kick", labeler) }
    private val deps by lazy {
        val controller = RecordingController(
            fake, store, StreamLinkFactory { throw LinkException("no network in this test", LinkException.Kind.CONNECT) },
            { "k7q2abcdefghijkl" }, AppInfo("0.1.0", "t", 37), scope,
        )
        TrainingDeps(controller, store, Prefs, labeler)
    }

    private val ui: @Composable () -> Unit = { Themed { AppRoot(fake, { flowOf(emptyList()) }, flowOf(null), deps) } }

    private fun has(text: String, substring: Boolean = false) = compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun draft() = LabelFiles.readLabels(File(store.dir(id), LabelFiles.DRAFT))

    /** The review open on the tabs, Contact C1 selected and moved by 100 ms. */
    private fun openAndEdit() {
        val title = setupTitle(store.read(id)!!.setup)          // installed before the list loads
        compose.setContent(ui)
        // Device tab, Recordings, the recording: its review
        compose.onNode(hasText("Device") and hasClickAction()).performClick()
        compose.onNodeWithText("Recordings").performScrollTo().performClick()
        compose.awaitReal { has(title) }
        compose.onNodeWithText(title).performClick()
        compose.awaitReal { has("Accept all") }
        // an edit: Contact C1 selected, nudged
        compose.onNodeWithContentDescription("Contact C1").performClick()
        compose.onNodeWithText("+100").performScrollTo().performClick()
        compose.awaitReal { has(MOVED, substring = true) }
    }

    @Test
    fun aReviewGoesOnWithoutTheLink() {
        openAndEdit()

        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))          // the device was switched off

        compose.awaitReal { has("Accept all") }
        compose.onNodeWithText("Review").assertExists()
        // the same review: the edit and its selection are there, nothing had to be restored
        compose.onNodeWithText(MOVED, substring = true).assertExists()
        compose.onNodeWithText("Your unsaved changes were restored.").assertDoesNotExist()
        compose.awaitReal(what = "the draft") { draft() != null }
        // Back: the training data list, not the connect screen
        compose.onNodeWithContentDescription("Back").performClick()
        compose.awaitReal { has(setupTitle(store.read(id)!!.setup)) }
        compose.onNodeWithText("Accept all").assertDoesNotExist()
    }

    /**
     * Save waits (another write of the recording's files holds them) while the link ends and
     * comes back: the review shown on the tabs again is the one saving, and it closes when the
     * labels are written instead of showing them again.
     */
    @Test
    fun aSaveInFlightAcrossTheLinkClosesTheReview() {
        openAndEdit()
        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))
        compose.awaitReal { has("Accept all") }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { store.exclusive(id) { held.countDown(); release.await(30, TimeUnit.SECONDS) } }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        compose.onNodeWithText("Accept all").performClick()          // confirms, saves: waits
        runBlocking { fake.connect("F4:12:FA:00:1A:2B") }
        compose.awaitReal(what = "the tabs, the review on them") { has("Connected") && has("Accept all") }
        release.countDown()
        holder.join(10_000)
        compose.awaitReal(what = "the review closed after its save") { !has("Accept all") }
        compose.onNodeWithText("Connected").assertExists()
        val review = FinalizerFixtures.parse(File(store.dir(id), Finalizer.META)).jsonObject["review"]!!.jsonObject
        assertEquals("\"reviewed\"", review["status"].toString())
        compose.waitForIdle()
        compose.onNodeWithText("Accept all").assertDoesNotExist()
        // and it is not handed over again when the link ends next
        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))
        compose.waitForIdle()
        compose.onNodeWithText("Accept all").assertDoesNotExist()
    }

    /** A configuration change (rotation, theme, locale) keeps the review opened without the link: the same edit, no restore. */
    @Test
    fun aConfigurationChangeKeepsTheOfflineReview() {
        openAndEdit()
        fake.dropLink(DeviceError.Link(DisconnectReason.SLEEP))
        compose.awaitReal { has("Accept all") }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = ui) }
        compose.awaitReal(what = "the review again") { has("Accept all") }
        compose.onNodeWithText(MOVED, substring = true).assertExists()
        compose.onNodeWithText("Your unsaved changes were restored.").assertDoesNotExist()
    }

    /**
     * Back from the review on the tabs: its view model is cleared once the screen's exit
     * transition is over, and not made again while it runs (it would load the recording for
     * nothing and come back with what it read then when the review opens again).
     */
    @Test
    fun aReviewClosedOnTheTabsKeepsNoViewModel() {
        openAndEdit()
        compose.awaitReal(what = "the draft") { draft() != null }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.awaitReal { !has("Accept all") }
        compose.waitForIdle()
        var open: Set<String>? = null
        compose.activityRule.scenario.onActivity { open = ViewModelProvider(it)[ReviewStores::class.java].open }
        assertEquals(emptySet<String>(), open)
        // opened again: the recording read again, the edit restored from its draft
        compose.onNodeWithText(setupTitle(store.read(id)!!.setup)).performClick()
        compose.awaitReal { has("Accept all") }
        compose.awaitReal(what = "the restored draft") { has("Your unsaved changes were restored.") }
    }

    private companion object {
        const val MOVED = "moved +100 ms"
    }

    @Test
    fun closingAReviewShowsWhatItWasOpenedFrom() {
        val fromSession = OfflineScreens(session = true).openReview("a")
        assertEquals(OfflineScreens(session = true), fromSession.closeReview())
        assertEquals(OfflineScreens(session = false), OfflineScreens().openReview("a").closeReview())
    }
}
