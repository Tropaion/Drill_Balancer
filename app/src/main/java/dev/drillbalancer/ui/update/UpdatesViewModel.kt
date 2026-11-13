package dev.drillbalancer.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.update.DownloadException
import dev.drillbalancer.update.MAX_APK_BYTES
import dev.drillbalancer.update.NoteBlock
import dev.drillbalancer.update.ReleaseAsset
import dev.drillbalancer.update.ReleaseVersion
import dev.drillbalancer.update.UpdateDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

enum class UpdateKind(val label: String) { FIRMWARE("Firmware"), APP("App") }

sealed interface UpdateStage {
    data object Available : UpdateStage

    data class Downloading(val percent: Int) : UpdateStage

    /** The APK is here, waiting for Android's installer. */
    data object Ready : UpdateStage

    data class Failed(val message: String) : UpdateStage
}

/** [notes]: the release's, the same for both kinds (one release carries both). */
data class UpdateNotice(val kind: UpdateKind, val version: String, val stage: UpdateStage, val notes: List<NoteBlock> = emptyList())

data class UpdatesUiState(val firmware: UpdateNotice? = null, val app: UpdateNotice? = null)

/** A downloaded image for the Firmware screen, as if the file had been picked. */
class DownloadedFirmware(val name: String, val parsed: FirmwareParseResult)

/** Whether [release] is worth a notice: newer than what runs, and than what the user dismissed. */
internal fun offered(release: ReleaseVersion, current: ReleaseVersion?, dismissed: String?): Boolean {
    if (current == null || release <= current) return false
    val seen = ReleaseVersion.parse(dismissed)
    return seen == null || release > seen
}

/**
 * The update notices, for the whole app (it outlives the connected screens). Checks when the app
 * opens or comes back and when a device connects; a failure shows nothing. [busy]: a recording
 * session, which nothing here interrupts. The Firmware screen shows no notice, so none shows
 * during an update.
 */
class UpdatesViewModel(private val deps: UpdateDeps, session: DeviceSession, busy: Flow<Boolean>) : ViewModel() {
    private val recording = busy.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val firmwareStage = MutableStateFlow<UpdateStage>(UpdateStage.Available)
    private val downloaded = MutableStateFlow<DownloadedFirmware?>(null)
    private var firmwareJob: Job? = null

    // Last start's APK: the update ran, or the user left it. A download waits for this.
    private val cacheCleared = viewModelScope.launch(Dispatchers.IO) { deps.app?.installer?.cacheDir?.listFiles()?.forEach(File::delete) }
    private val appStage = MutableStateFlow<UpdateStage>(UpdateStage.Available)
    private var appJob: Job? = null
    private var appFile: File? = null
    private var awaitingPermission = false

    /** A downloaded image for the Firmware screen, which says [firmwareTaken]. It waits for a recording to end. */
    val pendingFirmware: StateFlow<DownloadedFirmware?> = combine(downloaded, recording) { image, isBusy -> image.takeUnless { isBusy } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val firmwareOffer = combine(deps.checker.latest, session.connection, session.info, deps.store.dismissedFirmware, recording) {
            latest, connection, info, dismissed, isBusy ->
        latest?.takeIf {
            it.firmware != null && connection is ConnectionState.Connected && !isBusy &&
                offered(it.version, ReleaseVersion.parse(info?.firmwareRevision), dismissed)
        }
    }

    private val appOffer = combine(deps.checker.latest, deps.store.dismissedApp, recording) { latest, dismissed, isBusy ->
        latest?.takeIf { deps.app != null && it.apk != null && !isBusy && offered(it.version, deps.app.version, dismissed) }
    }

    val state: StateFlow<UpdatesUiState> = combine(firmwareOffer, firmwareStage, appOffer, appStage) { firmware, firmwareAt, app, appAt ->
        UpdatesUiState(
            firmware = firmware?.let { UpdateNotice(UpdateKind.FIRMWARE, it.version.toString(), firmwareAt, it.notes) },
            app = app?.let { UpdateNotice(UpdateKind.APP, it.version.toString(), appAt, it.notes) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState())

    init {
        viewModelScope.launch { deps.checker.refresh() }
        viewModelScope.launch {
            session.connection.filterIsInstance<ConnectionState.Connected>().collect { deps.checker.refresh() }
        }
        // A link that comes back keeps the download; a session that ended has no use for it.
        viewModelScope.launch {
            session.connection.filterIsInstance<ConnectionState.Disconnected>().collect { if (!it.reconnecting) dropFirmware() }
        }
        // What was fetched belongs to the release it came from.
        viewModelScope.launch {
            deps.checker.latest.map { it?.version }.distinctUntilChanged().drop(1).collect {
                dropFirmware()
                dropApp()
            }
        }
    }

    fun downloadFirmware() {
        val release = deps.checker.latest.value ?: return
        val asset = release.firmware ?: return
        firmwareJob?.cancel()
        firmwareStage.value = UpdateStage.Downloading(0)
        firmwareJob = viewModelScope.launch {
            try {
                val limit = FirmwareImage.OTA_SLOT_SIZE
                val bytes = ByteArrayOutputStream(asset.size.coerceIn(0, limit.toLong()).toInt())
                // Through a child: a cancel must also drop the progress still on its way.
                deps.downloader.fetch(asset, limit.toLong(), bytes) { percent ->
                    launch { firmwareStage.value = UpdateStage.Downloading(percent) }
                }
                val parsed = withContext(Dispatchers.Default) { FirmwareImage.parse(bytes.toByteArray()) }
                val failure = when (parsed) {
                    is FirmwareParseResult.Error -> parsed.message
                    is FirmwareParseResult.Ok -> "This download is not firmware ${release.version}.".takeIf { parsed.image.version != release.version.toString() }
                }
                firmwareStage.value = failure?.let(UpdateStage::Failed) ?: UpdateStage.Available
                if (failure == null) downloaded.value = DownloadedFirmware(asset.name, parsed)
            } catch (e: DownloadException) {
                firmwareStage.value = UpdateStage.Failed(e.message.orEmpty())
            }
        }
    }

    fun dismissFirmware() {
        val version = deps.checker.latest.value?.version ?: return
        dropFirmware()
        viewModelScope.launch { deps.store.dismissFirmware(version.toString()) }
    }

    fun firmwareTaken() {
        downloaded.value = null
    }

    fun downloadApp() {
        val asset = deps.checker.latest.value?.apk ?: return
        val installer = deps.app?.installer ?: return
        appJob?.cancel()
        appStage.value = UpdateStage.Downloading(0)
        appJob = viewModelScope.launch {
            try {
                cacheCleared.join()
                appFile = withContext(Dispatchers.IO) { fetchApk(installer.cacheDir, asset, this@launch) }
                if (!recording.value) installApp()
                appStage.value = UpdateStage.Ready
            } catch (e: DownloadException) {
                appStage.value = UpdateStage.Failed(e.message.orEmpty())
            }
        }
    }

    // Into a part file, so only a whole APK ever has the name the installer gets.
    private suspend fun fetchApk(dir: File, asset: ReleaseAsset, progress: CoroutineScope): File {
        val part = File(dir, "${asset.name}.part")
        try {
            dir.mkdirs()
            part.outputStream().use { out ->
                deps.downloader.fetch(asset, MAX_APK_BYTES, out) { percent ->
                    progress.launch { appStage.value = UpdateStage.Downloading(percent) }
                }
            }
            val apk = File(dir, asset.name)
            if (!part.renameTo(apk)) throw IOException("rename failed")
            return apk
        } catch (_: IOException) {
            throw DownloadException("The download could not be saved.")
        } finally {
            part.delete()
        }
    }

    /** To Android's installer; first to the "Install unknown apps" switch if it is off. */
    fun installApp() {
        val installer = deps.app?.installer ?: return
        val apk = appFile ?: return
        if (!apk.exists()) {            // the cache is Android's to clean
            dropApp()
            return
        }
        awaitingPermission = !installer.canInstall()
        if (awaitingPermission) installer.openInstallPermission() else installer.install(apk)
    }

    /** Back in the app, maybe from the switch. Refused, the notice stays on Install. */
    fun onResume() {
        viewModelScope.launch { deps.checker.refresh() }
        if (awaitingPermission && deps.app?.installer?.canInstall() == true) installApp()
        awaitingPermission = false
    }

    fun dismissApp() {
        val version = deps.checker.latest.value?.version ?: return
        dropApp()
        viewModelScope.launch { deps.store.dismissApp(version.toString()) }
    }

    private fun dropFirmware() {
        firmwareJob?.cancel()
        firmwareStage.value = UpdateStage.Available
        downloaded.value = null
    }

    private fun dropApp() {
        appJob?.cancel()
        appStage.value = UpdateStage.Available
        appFile?.delete()
        appFile = null
        awaitingPermission = false
    }
}
