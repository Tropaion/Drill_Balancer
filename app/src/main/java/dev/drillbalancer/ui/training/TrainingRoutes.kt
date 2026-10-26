package dev.drillbalancer.ui.training

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drillbalancer.ble.BlePermissions
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.net.WifiBlock
import dev.drillbalancer.recorder.net.WifiPermissions
import dev.drillbalancer.ui.components.ConfirmDialog
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.KeepScreenOn
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.components.startActivitySafely
import dev.drillbalancer.ui.theme.Red

/** The process-wide recorder parts the training screens use (DrillBalancerApp). */
class TrainingDeps(val controller: RecordingController, val store: RecordingStore, val prefs: SetupPrefs)

@Composable
fun TrainingListRoute(vm: TrainingListViewModel, onOpenSession: () -> Unit, modifier: Modifier = Modifier, onNew: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.reload()
        onPauseOrDispose {}
    }
    TrainingListScreen(state, TrainingListActions(newRecording = onNew, openSession = onOpenSession, reload = vm::reload), modifier)
}

/**
 * Start asks for the Wi-Fi permissions first (the notification's with them, once). A refused
 * permission or (API 29-32) location services off stop it with the way to fix that, since
 * the join cannot succeed without them.
 */
@Composable
fun TrainingSetupRoute(vm: TrainingSetupViewModel, onStarted: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var block by rememberSaveable { mutableStateOf<WifiBlock?>(null) }
    val check = {
        WifiPermissions.block(
            missingRequired = WifiPermissions.missing(context, WifiPermissions.required()).isNotEmpty(),
            locationOn = BlePermissions.isLocationEnabled(context),
        )
    }
    val start = {
        block = check()
        if (block == null && vm.start()) onStarted()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }
    // Back from the settings: the fix may be done.
    LifecycleResumeEffect(Unit) {
        if (block != null) block = check()
        onPauseOrDispose {}
    }
    val ask = WifiPermissions.toAsk(
        WifiPermissions.missing(context, WifiPermissions.required()),
        WifiPermissions.missing(context, WifiPermissions.optional()),
    )
    val note = when {
        ask.isEmpty() || block != null -> null
        WifiPermissions.needsLocationServices() ->
            "Start asks for the Location permission: Android 10 to 12 needs it to join the device's Wi-Fi. " +
                "Location services must be on. The app does not use your location."
        else -> "Start asks for the Nearby devices permission to reach the device over its Wi-Fi, and to show the recording's notification."
    }
    TrainingSetupScreen(
        state,
        TrainingSetupActions(
            change = vm::change,
            start = { if (ask.isEmpty()) start() else permissions.launch(ask.toTypedArray()) },
            fixBlock = {
                when (block) {
                    WifiBlock.PERMISSION -> context.startActivitySafely(BlePermissions.appSettingsIntent(context))
                    WifiBlock.LOCATION_OFF -> context.startActivitySafely(BlePermissions.locationSettingsIntent())
                    null -> Unit
                }
            },
        ),
        permissionNote = note,
        wifiBlock = block,
    )
}

/**
 * The record screen. Back during a session asks first: it ends the session (and the take).
 * The screen stays on while a session runs.
 */
@Composable
fun TrainingRecordRoute(vm: RecordViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val phase = state.rec.phase
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (phase.active) KeepScreenOn()
    BackHandler {
        if (phase.active) confirm = true else {
            vm.dismiss()
            onBack()
        }
    }
    TrainingRecordScreen(
        state,
        RecordActions(
            startTake = vm::startTake,
            stopTake = vm::stopTake,
            finish = vm::finish,
            retry = vm::retry,
            done = {
                vm.dismiss()
                onBack()
            },
            openWifiSettings = { context.startActivitySafely(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        ),
        modifier,
    )
    if (confirm) {
        val recording = phase == RecordPhase.RECORDING || phase == RecordPhase.STOPPING
        ConfirmDialog(
            title = if (recording) "Stop and finish?" else "Finish the session?",
            text = if (recording) {
                "The take is stopped and kept, and the device's Wi-Fi goes off."
            } else {
                "The device's Wi-Fi goes off. Recorded takes stay on the phone."
            },
            confirmLabel = "Finish",
            confirmColor = Red,
            onConfirm = {
                vm.finish()
                onBack()
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * The training data opened from the connect screen (no device): the list, or the session
 * that still runs after the Bluetooth link was given up (the take goes on over Wi-Fi), until
 * the user leaves its end (DONE, FAILED).
 */
@Composable
fun OfflineTraining(deps: TrainingDeps, onClose: () -> Unit) {
    val phase by deps.controller.phase.collectAsStateWithLifecycle()
    var showSession by rememberSaveable { mutableStateOf(phase.active) }
    LaunchedEffect(phase.active) { if (phase.active) showSession = true }
    // The record screen's own Back handling (finish?) runs through the dispatcher: no arrow there.
    OfflineFrame(if (showSession) "Recording" else "Training data", onBack = if (showSession) null else onClose) { m ->
        if (showSession) {
            TrainingRecordRoute(viewModel { RecordViewModel(deps.controller, null) }, onBack = { showSession = false }, m)
        } else {
            BackHandler(onBack = onClose)
            TrainingListRoute(viewModel { TrainingListViewModel(deps.store, deps.controller, null) }, onOpenSession = { showSession = true }, m)
        }
    }
}

/** The plain top bar of the training screens without a device (no link, no battery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineFrame(title: String, onBack: (() -> Unit)?, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onBack, Modifier.size(TouchHeight)) { Icon(DbIcons.Back, contentDescription = "Back") }
                },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}
