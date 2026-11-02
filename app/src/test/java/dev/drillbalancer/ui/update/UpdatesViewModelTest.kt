package dev.drillbalancer.ui.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceInfo
import dev.drillbalancer.device.DisconnectReason
import dev.drillbalancer.device.FakeDeviceSession
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.ui.MainDispatcherRule
import dev.drillbalancer.ui.firmwareBytes
import dev.drillbalancer.update.ApkInstaller
import dev.drillbalancer.update.AppUpdates
import dev.drillbalancer.update.ReleaseAsset
import dev.drillbalancer.update.ReleaseDownloader
import dev.drillbalancer.update.ReleaseInfo
import dev.drillbalancer.update.ReleaseResult
import dev.drillbalancer.update.ReleaseVersion
import dev.drillbalancer.update.UpdateChecker
import dev.drillbalancer.update.UpdateDeps
import dev.drillbalancer.update.UpdateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(state.value).also { state.value = it }
}

class FakeInstaller(override val cacheDir: File) : ApkInstaller {
    var allowed = true
    var permissionOpened = 0
    val installed = ArrayList<File>()

    override fun canInstall() = allowed

    override fun openInstallPermission() {
        permissionOpened++
    }

    override fun install(apk: File) {
        installed += apk
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private var now = 0L
    private var calls = 0
    private var result: ReleaseResult = release("0.2.0")
    private val memory = MemoryDataStore()
    private val store = UpdateStore(memory)
    private val checker = UpdateChecker({ calls++; result }, { now })
    private val session = FakeDeviceSession()
    private val busy = MutableStateFlow(false)
    private val image = firmwareBytes("0.2.0", size = 4096)

    @After
    fun close() = server.close()

    private fun release(version: String, size: Long = 4096) = ReleaseResult.Found(
        ReleaseInfo(ReleaseVersion.parse(version)!!, ReleaseAsset("drillbalancer-$version.bin", server.url("/fw.bin").toString(), size), null),
    )

    private fun vm(device: FakeDeviceSession = session) =
        UpdatesViewModel(UpdateDeps(checker, store, ReleaseDownloader(server.url("/").toString())), device, busy)

    private fun board(version: String, connected: Boolean = true) =
        FakeDeviceSession(info = DeviceInfo("Waveshare", "AMOLED", version), connected = connected)

    private val installer by lazy { FakeInstaller(tmp.newFolder("updates")) }
    private val apk = ByteArray(5000) { it.toByte() }

    private fun appRelease(version: String = "0.2.0", size: Long = apk.size.toLong()) = ReleaseResult.Found(
        ReleaseInfo(
            ReleaseVersion.parse(version)!!, null,
            ReleaseAsset("drillbalancer-$version.apk", server.url("/app.apk").toString(), size),
        ),
    )

    private fun appVm(appVersion: String = "0.1.0") = UpdatesViewModel(
        UpdateDeps(
            checker, store, ReleaseDownloader(server.url("/").toString()),
            app = AppUpdates(ReleaseVersion.parse(appVersion), installer),
        ),
        board("0.2.0", connected = false), busy,
    )

    private suspend fun UpdatesViewModel.settled(check: UpdatesViewModel.() -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5000) { while (!check()) delay(5) }
    }

    private suspend fun UpdatesViewModel.awaitApp(ok: (UpdateNotice?) -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5000) { state.first { ok(it.app) } }
    }

    private fun serve(body: ByteArray) = server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())

    private suspend fun UpdatesViewModel.awaitFirmware(ok: (UpdateNotice?) -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5000) { state.first { ok(it.firmware) } }
    }

    private suspend fun UpdatesViewModel.awaitReady() = withContext(Dispatchers.Default) {
        withTimeout(5000) { pendingFirmware.first { it != null } }
    }

    @Test
    fun showsNoticeWhenTheReleaseIsNewer() = runTest(main.dispatcher) {
        val n = vm().state.value.firmware
        assertEquals(UpdateNotice(UpdateKind.FIRMWARE, "0.2.0", UpdateStage.Available), n)
    }

    @Test
    fun noNoticeForTheSameOrAHigherBoard() = runTest(main.dispatcher) {
        assertNull(vm(board("0.2.0")).state.value.firmware)
        assertNull(vm(board("0.3.0")).state.value.firmware)
    }

    @Test
    fun comparesVersionsAsNumbers() = runTest(main.dispatcher) {
        result = release("0.10.0")
        assertNotNull(vm(board("0.9.0")).state.value.firmware)
    }

    @Test
    fun noNoticeForABoardVersionThatDoesNotParse() = runTest(main.dispatcher) {
        assertNull(vm(board("dev-build")).state.value.firmware)
    }

    @Test
    fun noNoticeWithoutAFirmwareAsset() = runTest(main.dispatcher) {
        result = ReleaseResult.Found(ReleaseInfo(ReleaseVersion(0, 2, 0), null, null))
        assertNull(vm().state.value.firmware)
    }

    @Test
    fun noNoticeWhileDisconnected() = runTest(main.dispatcher) {
        assertNull(vm(board("0.1.0", connected = false)).state.value.firmware)
    }

    @Test
    fun noNoticeDuringARecordingSession() = runTest(main.dispatcher) {
        val vm = vm()
        assertNotNull(vm.state.value.firmware)
        busy.value = true
        assertNull(vm.state.value.firmware)
    }

    @Test
    fun aFailedCheckShowsNothing() = runTest(main.dispatcher) {
        result = ReleaseResult.Failed
        assertEquals(UpdatesUiState(), vm().state.value)
    }

    @Test
    fun checksOnStartAndOnConnect() = runTest(main.dispatcher) {
        val device = board("0.1.0", connected = false)
        vm(device)
        assertEquals(1, calls)
        now += UpdateChecker.TTL_MS + 1
        device.connect("F4:12:FA:00:1A:2B")
        assertEquals(2, calls)
    }

    @Test
    fun dismissHidesThatVersionOnly() = runTest(main.dispatcher) {
        vm().dismissFirmware()
        assertNull(vm().state.value.firmware)
        result = release("0.3.0")
        now += UpdateChecker.TTL_MS + 1
        checker.refresh()
        assertEquals("0.3.0", vm().state.value.firmware?.version)
    }

    @Test
    fun anOlderReleaseThanTheDismissedOneStaysHidden() = runTest(main.dispatcher) {
        store.dismissFirmware("0.3.0")
        assertNull(vm().state.value.firmware)
    }

    @Test
    fun theNoticeGoesAwayAfterTheUpdate() = runTest(main.dispatcher) {
        val vm = vm(board("0.2.0"))
        assertNull(vm.state.value.firmware)
        val old = board("0.1.0")
        val vm2 = vm(old)
        assertNotNull(vm2.state.value.firmware)
        old.setFirmwareRevision("0.2.0")
        assertNull(vm2.state.value.firmware)
    }

    @Test
    fun downloadParsesAndHandsOver() = runTest(main.dispatcher) {
        serve(image)
        val vm = vm()
        vm.downloadFirmware()
        vm.awaitReady()
        val done = vm.pendingFirmware.value!!
        assertEquals("drillbalancer-0.2.0.bin", done.name)
        assertEquals("0.2.0", (done.parsed as FirmwareParseResult.Ok).image.version)
        vm.firmwareTaken()
        vm.settled { pendingFirmware.value == null }
    }

    @Test
    fun anImageOfAnotherVersionFails() = runTest(main.dispatcher) {
        val wrong = firmwareBytes("0.1.9", size = 4096)
        serve(wrong)
        val vm = vm()
        vm.downloadFirmware()
        val n = vm.awaitFirmware { it?.stage is UpdateStage.Failed }
        assertTrue(n.firmware!!.stage.toString(), "0.2.0" in (n.firmware!!.stage as UpdateStage.Failed).message)
        assertNull(vm.pendingFirmware.value)
    }

    @Test
    fun aBodyThatIsNoImageFails() = runTest(main.dispatcher) {
        serve(ByteArray(4096))
        val vm = vm()
        vm.downloadFirmware()
        vm.awaitFirmware { it?.stage is UpdateStage.Failed }
        assertNull(vm.pendingFirmware.value)
    }

    @Test
    fun aFailedDownloadCanBeRetried() = runTest(main.dispatcher) {
        server.enqueue(MockResponse.Builder().code(500).build())
        serve(image)
        val vm = vm()
        vm.downloadFirmware()
        vm.awaitFirmware { it?.stage is UpdateStage.Failed }
        vm.downloadFirmware()
        vm.awaitReady()
    }

    @Test
    fun theSessionsEndStopsTheDownload() = runTest(main.dispatcher) {
        server.enqueue(MockResponse.Builder().body(Buffer().write(image)).throttleBody(1024, 1, TimeUnit.SECONDS).build())
        val vm = vm()
        vm.downloadFirmware()
        vm.awaitFirmware { (it?.stage as? UpdateStage.Downloading)?.percent?.let { p -> p > 0 } == true }
        session.dropLink(null)
        vm.awaitFirmware { it == null }
        assertNull(vm.pendingFirmware.value)
        session.connect("F4:12:FA:00:1A:2B")
        vm.awaitFirmware { it?.stage == UpdateStage.Available }
    }

    @Test
    fun aReconnectKeepsTheDownload() = runTest(main.dispatcher) {
        serve(image)
        val vm = vm()
        vm.downloadFirmware()
        session.dropLink(DeviceError.Link(DisconnectReason.LINK_LOSS), reconnecting = true)
        vm.awaitReady()
        assertNotNull(vm.pendingFirmware.value)
    }

    @Test
    fun appNoticeNeedsNoDevice() = runTest(main.dispatcher) {
        result = appRelease()
        assertEquals(UpdateNotice(UpdateKind.APP, "0.2.0", UpdateStage.Available), appVm().state.value.app)
    }

    @Test
    fun noAppNoticeInDebugBuildsButTheFirmwareNoticeStays() = runTest(main.dispatcher) {
        result = ReleaseResult.Found(release("0.2.0").info.copy(apk = appRelease().info.apk))
        val vm = UpdatesViewModel(UpdateDeps(checker, store, ReleaseDownloader(server.url("/").toString())), session, busy)
        assertNull(vm.state.value.app)
        assertNotNull(vm.state.value.firmware)
    }

    @Test
    fun noAppNoticeForTheSameVersionOrWithoutAnApk() = runTest(main.dispatcher) {
        result = appRelease()
        assertNull(appVm(appVersion = "0.2.0").state.value.app)
        result = release("0.3.0")
        now += UpdateChecker.TTL_MS + 1
        checker.refresh()
        assertNull(appVm().state.value.app)
    }

    @Test
    fun dismissAppIsPerVersion() = runTest(main.dispatcher) {
        result = appRelease()
        appVm().dismissApp()
        assertNull(appVm().state.value.app)
        result = appRelease("0.3.0")
        now += UpdateChecker.TTL_MS + 1
        checker.refresh()
        assertEquals("0.3.0", appVm().state.value.app?.version)
    }

    @Test
    fun downloadThenInstalls() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        assertEquals(listOf(File(installer.cacheDir, "drillbalancer-0.2.0.apk")), installer.installed)
        assertArrayEquals(apk, installer.installed.single().readBytes())
        assertEquals(listOf("drillbalancer-0.2.0.apk"), installer.cacheDir.list()!!.toList())
    }

    @Test
    fun aFailedDownloadLeavesNoFile() = runTest(main.dispatcher) {
        result = appRelease(size = apk.size + 1L)
        serve(apk)
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage is UpdateStage.Failed }
        assertEquals(emptyList<String>(), installer.cacheDir.list()!!.toList())
        assertEquals(emptyList<File>(), installer.installed)
    }

    @Test
    fun aMissingPermissionOpensTheSettingThenInstallsOnReturn() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        installer.allowed = false
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        assertEquals(1, installer.permissionOpened)
        assertEquals(emptyList<File>(), installer.installed)
        installer.allowed = true
        vm.onResume()
        assertEquals(1, installer.installed.size)
        vm.onResume()
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun aRefusedPermissionStaysReady() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        installer.allowed = false
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        vm.onResume()
        assertEquals(UpdateStage.Ready, vm.state.value.app?.stage)
        assertEquals(1, installer.permissionOpened)
        vm.installApp()
        assertEquals(2, installer.permissionOpened)
    }

    @Test
    fun aCancelledInstallStaysReady() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        vm.onResume()
        assertEquals(UpdateStage.Ready, vm.state.value.app?.stage)
        vm.installApp()
        assertEquals(2, installer.installed.size)
    }

    @Test
    fun theCacheIsClearedOnStart() = runTest(main.dispatcher) {
        result = appRelease()
        val old = File(installer.cacheDir, "drillbalancer-0.1.0.apk").apply { writeBytes(apk) }
        val vm = appVm()
        vm.awaitApp { it != null }
        withContext(Dispatchers.Default) { withTimeout(5000) { while (old.exists()) delay(10) } }
    }

    @Test
    fun aDownloadThatEndsDuringARecordingWaitsForItsEnd() = runTest(main.dispatcher) {
        server.enqueue(MockResponse.Builder().body(Buffer().write(image)).throttleBody(2048, 200, TimeUnit.MILLISECONDS).build())
        val vm = vm()
        vm.downloadFirmware()
        busy.value = true
        vm.settled { state.value.firmware == null }
        withContext(Dispatchers.Default) { delay(800) }
        assertNull(vm.pendingFirmware.value)
        busy.value = false
        vm.awaitReady()
    }

    @Test
    fun aRecordingKeepsTheInstallerAway() = runTest(main.dispatcher) {
        result = appRelease()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk)).throttleBody(2048, 200, TimeUnit.MILLISECONDS).build())
        val vm = appVm()
        vm.downloadApp()
        busy.value = true
        vm.settled { state.value.app == null }
        withContext(Dispatchers.Default) { delay(800) }
        assertEquals(emptyList<File>(), installer.installed)
        busy.value = false
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        vm.installApp()
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun aNewerReleaseStartsOver() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        result = appRelease("0.3.0")
        now += UpdateChecker.TTL_MS + 1
        checker.refresh()
        vm.awaitApp { it == UpdateNotice(UpdateKind.APP, "0.3.0", UpdateStage.Available) }
        vm.installApp()
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun anApkAndroidCleanedUpIsOfferedAgain() = runTest(main.dispatcher) {
        result = appRelease()
        serve(apk)
        val vm = appVm()
        vm.downloadApp()
        vm.awaitApp { it?.stage == UpdateStage.Ready }
        installer.installed.single().delete()
        vm.installApp()
        vm.awaitApp { it?.stage == UpdateStage.Available }
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun comingBackChecksAgain() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(1, calls)
        now += UpdateChecker.TTL_MS + 1
        vm.onResume()
        vm.settled { calls == 2 }
    }
}
