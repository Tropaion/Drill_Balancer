package dev.drillbalancer

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import dev.drillbalancer.ble.BleDeviceSession
import dev.drillbalancer.ble.DeviceScanner
import dev.drillbalancer.ble.LastDeviceStore
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.RecorderService
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SetupStore
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.DetectParams
import dev.drillbalancer.recorder.label.LoadedParams
import dev.drillbalancer.recorder.net.ApLink
import dev.drillbalancer.recorder.net.LanLink
import dev.drillbalancer.recorder.net.StreamEndpoint
import dev.drillbalancer.recorder.net.StreamLink
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.recorder.net.WifiPermissions
import dev.drillbalancer.ui.training.TrainingDeps
import dev.drillbalancer.update.AndroidApkInstaller
import dev.drillbalancer.update.AppUpdates
import dev.drillbalancer.update.HttpReleaseSource
import dev.drillbalancer.update.ReleaseDownloader
import dev.drillbalancer.update.ReleaseVersion
import dev.drillbalancer.update.UpdateChecker
import dev.drillbalancer.update.UpdateDeps
import dev.drillbalancer.update.UpdateStore
import dev.drillbalancer.update.updateDataStore
import dev.drillbalancer.upload.GcmCipher
import dev.drillbalancer.upload.KeystoreKey
import dev.drillbalancer.upload.TokenVault
import dev.drillbalancer.upload.UploadDeps
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadScheduler
import dev.drillbalancer.upload.UploadServices
import dev.drillbalancer.upload.UploadSettingsStore
import dev.drillbalancer.upload.WorkUploadScheduler
import dev.drillbalancer.upload.uploadDataStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Process-wide singletons live here (manual DI, no framework). */
class DrillBalancerApp : Application(), UploadDeps.Provider {
    val scanner: DeviceScanner by lazy { DeviceScanner(this) }
    val lastDevice: LastDeviceStore by lazy { LastDeviceStore(this) }
    val session: BleDeviceSession by lazy { BleDeviceSession(this, scanner, lastDevice) }

    /**
     * The recording engine and its files outlive the screens (and a BLE drop). The engine
     * turns its own failures into FAILED; anything else that escapes here is logged, so it
     * cannot end the process in the middle of a recording.
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("DrillBalancerApp", "app scope", e) },
    )
    val setupStore: SetupStore by lazy { SetupStore(this) }

    // The auto-detector's parameters: the dataset contract's autodetect_params.json, shipped as an asset.
    private val detectParams: LoadedParams by lazy { DetectParams.load(assets.open(DetectParams.ASSET).use { it.readBytes() }) }
    val labeler: AutoLabeler by lazy {
        AutoLabeler({ detectParams }) { dir, e -> Log.w("DrillBalancerApp", "labelling ${dir.name} failed", e) }
    }
    val recordings: RecordingStore by lazy {
        RecordingStore(
            File(filesDir, "recordings"),
            freeSpace = { dir ->
                val sm = getSystemService(StorageManager::class.java)
                try {
                    sm.getAllocatableBytes(sm.getUuidForPath(dir))
                } catch (_: IOException) {
                    0L
                }
            },
            labeler = labeler,
        )
    }
    val recorder: RecordingController by lazy {
        RecordingController(
            session, recordings, StreamLinkFactory(::streamLink), setupStore::installId, appInfo(), appScope,
            takeFinished = ::finalizeLater,
        ).also {
            it.recover()
            RecorderService.follow(this, it, appScope)
        }
    }
    // The upload: the token sealed under a key in the AndroidKeyStore, the settings next to it
    // (DataStore "upload"), one WorkManager job per recording.
    val vault: TokenVault by lazy { TokenVault(uploadDataStore, GcmCipher { KeystoreKey.get() }) }
    val uploadSettings: UploadSettingsStore by lazy { UploadSettingsStore(uploadDataStore) }
    private val uploadScheduler: UploadScheduler by lazy { WorkUploadScheduler(this) }
    val uploads: UploadQueue by lazy { UploadQueue(recordings, uploadScheduler, uploadSettings, vault, appScope) }
    override val uploadDeps: UploadDeps by lazy { UploadDeps(recordings, uploadSettings, vault, uploadScheduler) }

    val training: TrainingDeps by lazy {
        TrainingDeps(recorder, recordings, setupStore, labeler, UploadServices(uploads, uploadSettings, vault))
    }

    // Update notices: elapsed time, so a corrected phone clock cannot stretch the 10 minutes.
    val updates: UpdateDeps by lazy {
        UpdateDeps(
            UpdateChecker(HttpReleaseSource(), SystemClock::elapsedRealtime), UpdateStore(updateDataStore), ReleaseDownloader(),
            app = if (BuildConfig.DEBUG) null else AppUpdates(ReleaseVersion.parse(BuildConfig.VERSION_NAME), AndroidApkInstaller(this)),
        )
    }

    // A take that ended gets its dataset files (a storage error leaves it to the next start's recovery).
    private fun finalizeLater(id: String) {
        appScope.launch(Dispatchers.IO) {
            try {
                recordings.finalize(id)
            } catch (e: IOException) {
                Log.w("DrillBalancerApp", "finalizing $id failed", e)
            }
        }
    }

    // The simulator on the LAN; the device's soft-AP joined for the app (API 29+), or joined by
    // the user in the system settings before.
    private fun streamLink(ep: StreamEndpoint): StreamLink = when {
        ep.noAp -> LanLink(ep)
        WifiPermissions.canRequestNetwork() -> ApLink(this, ep)
        else -> LanLink(ep, manualJoin = true, factory = { WifiPermissions.wifiSocketFactory(this) })
    }

    private fun appInfo() = AppInfo(BuildConfig.VERSION_NAME, Build.MODEL ?: "unknown", Build.VERSION.SDK_INT)
}
