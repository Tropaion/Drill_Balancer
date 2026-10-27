package dev.drillbalancer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.drillbalancer.ble.BlePermissions
import dev.drillbalancer.ble.ScannedDevice
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.ui.calibration.CalibrationActions
import dev.drillbalancer.ui.calibration.CalibrationScreen
import dev.drillbalancer.ui.calibration.CalibrationViewModel
import dev.drillbalancer.ui.components.BatteryIndicator
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.components.startActivitySafely
import dev.drillbalancer.ui.connect.ConnectRoute
import dev.drillbalancer.ui.connect.ConnectViewModel
import dev.drillbalancer.ui.device.DeviceActions
import dev.drillbalancer.ui.device.DeviceScreen
import dev.drillbalancer.ui.device.DeviceViewModel
import dev.drillbalancer.ui.firmware.FirmwareRoute
import dev.drillbalancer.ui.firmware.FirmwareViewModel
import dev.drillbalancer.ui.live.LiveScreen
import dev.drillbalancer.ui.live.LiveViewModel
import dev.drillbalancer.ui.pairing.PairingScreen
import dev.drillbalancer.ui.settings.SettingsActions
import dev.drillbalancer.ui.settings.SettingsScreen
import dev.drillbalancer.ui.settings.SettingsViewModel
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.training.OfflineTraining
import dev.drillbalancer.ui.training.RecordViewModel
import dev.drillbalancer.ui.training.TrainingDeps
import dev.drillbalancer.ui.training.TrainingListRoute
import dev.drillbalancer.ui.training.TrainingRecordRoute
import dev.drillbalancer.ui.training.ReviewStores
import dev.drillbalancer.ui.training.TrainingReviewRoute
import dev.drillbalancer.ui.training.TrainingSetupRoute
import dev.drillbalancer.ui.training.TrainingSetupViewModel
import dev.drillbalancer.ui.training.UploadSettingsRoute
import dev.drillbalancer.ui.training.upload.UploadSettingsViewModel
import dev.drillbalancer.ui.training.reviewViewModel
import dev.drillbalancer.ui.theme.Green
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/** Navigation destinations of the connected app. */
@Serializable
sealed interface Dest {
    @Serializable data object Live : Dest
    @Serializable data object Settings : Dest
    @Serializable data object Calibrate : Dest
    @Serializable data object Device : Dest
    @Serializable data object Firmware : Dest
    @Serializable data object Training : Dest
    @Serializable data object TrainingSetup : Dest
    @Serializable data object TrainingRecord : Dest
    @Serializable data class TrainingReview(val id: String) : Dest
    @Serializable data object TrainingUpload : Dest
}

/** Screens below the tabs: a back arrow and their title instead of the tab bar. */
enum class SubScreen(val route: KClass<out Dest>, val title: String) {
    FIRMWARE(Dest.Firmware::class, "Firmware update"),
    TRAINING(Dest.Training::class, "Training data"),
    TRAINING_SETUP(Dest.TrainingSetup::class, "New recording"),
    TRAINING_RECORD(Dest.TrainingRecord::class, "Recording"),
    TRAINING_REVIEW(Dest.TrainingReview::class, "Review"),
    TRAINING_UPLOAD(Dest.TrainingUpload::class, "Upload settings"),
}

/**
 * Whether the connect screen gives way to the training screens: opened from it ([opened]),
 * or a session that runs without the link. It stays (the session's end, DONE or FAILED,
 * included) until the user leaves it; a link that is back ends it.
 */
fun offlineTraining(opened: Boolean, showMain: Boolean, phase: RecordPhase): Boolean = !showMain && (opened || phase.active)

/** The sub-screen [d] shows, or null for a tab. */
fun subScreenOf(d: NavDestination?): SubScreen? = if (d == null) null else SubScreen.entries.firstOrNull { d.hasRoute(it.route) }

enum class Tab(val dest: Dest, val label: String, val icon: ImageVector) {
    LIVE(Dest.Live, "Live", DbIcons.Live),
    SETTINGS(Dest.Settings, "Settings", DbIcons.Settings),
    CALIBRATE(Dest.Calibrate, "Calibrate", DbIcons.Calibrate),
    DEVICE(Dest.Device, "Device", DbIcons.Device),
}

/**
 * The whole UI: the connect screen until a device is connected, then the tabs. The
 * pairing screen covers everything while an action waits for a bond. The training data
 * opens from the connect screen too (the list works without a device), and a recording
 * session that outlives the Bluetooth link stays on screen.
 */
@Composable
fun AppRoot(session: DeviceSession, scan: () -> Flow<List<ScannedDevice>>, lastAddress: Flow<String?>, training: TrainingDeps) {
    val sessionVm = viewModel { SessionViewModel(session) }
    val shell by sessionVm.shell.collectAsStateWithLifecycle()
    val prompt by sessionVm.gate.prompt.collectAsStateWithLifecycle()
    // The phase alone: the whole state changes up to 10 times a second during a take.
    val phase by training.controller.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var offlineOpen by rememberSaveable { mutableStateOf(false) }
    // The review open now, on either side: the link's end (or return) does not close it, the
    // other side shows it (the same view model, see ReviewStores; it needs no device).
    var review by rememberSaveable { mutableStateOf<String?>(null) }
    // Its view model, the same on both sides (and over configuration changes) until it closes.
    val reviews = viewModel { ReviewStores() }
    LaunchedEffect(review) { reviews.keepOnly(review) }
    val offline = offlineTraining(offlineOpen || review != null, shell.showMain, phase)
    if (offline != offlineOpen) SideEffect { offlineOpen = offline }

    if (!shell.showMain) {
        if (offline) {
            OfflineTraining(training, reviews, onClose = { offlineOpen = false }, review = review, onReview = { review = it })
        } else {
            ConnectRoute(viewModel { ConnectViewModel(session, scan, lastAddress) }, openTrainingData = { offlineOpen = true })
        }
        return
    }
    val screens = remember(sessionVm.screens) {
        object : ViewModelStoreOwner {
            override val viewModelStore = sessionVm.screens
        }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides screens) {
            ConnectedApp(sessionVm, shell, training, reviews, review, onReview = { review = it })
        }
        prompt?.let { p ->
            BackHandler { sessionVm.gate.cancel() }
            PairingScreen(
                p, shell.deviceName,
                onPair = sessionVm.gate::pair,
                onCancel = sessionVm.gate::cancel,
                onOpenBluetoothSettings = { context.startActivitySafely(BlePermissions.bluetoothSettingsIntent()) },
            )
        }
    }
}

/**
 * The tabs and their sub-screens; [review] a review open when the link came (shown again, its
 * view model in [reviews]), [onReview] hears the one shown.
 */
@Composable
private fun ConnectedApp(
    sessionVm: SessionViewModel,
    shell: ShellState,
    training: TrainingDeps,
    reviews: ReviewStores,
    review: String?,
    onReview: (String?) -> Unit,
) {
    val session = sessionVm.session
    val gate = sessionVm.gate
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val sub = subScreenOf(entry?.destination)
    val startReview = remember { review }
    val reported by rememberUpdatedState(onReview)
    val tab = if (sub != null) null else Tab.entries.firstOrNull { t -> entry?.destination?.hasRoute(t.dest::class) == true } ?: Tab.LIVE
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Saves the state of the tab it leaves, so for example unsaved settings edits stay.
    val goToTab: (Tab) -> Unit = { t ->
        nav.navigate(t.dest) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ConnectedFrame(
        shell = shell,
        tab = tab,
        onTab = goToTab,
        // Sub-screens: back arrow instead of the tabs. It goes the way of system Back, so a
        // screen's own back handling (stop an update? finish the recording?) applies.
        onBack = if (sub != null) ({ if (backDispatcher != null) backDispatcher.onBackPressed() else nav.popBackStack() }) else null,
        title = sub?.title,
        onStopReconnect = sessionVm::disconnect,
    ) { padding ->
        NavHost(nav, startDestination = Dest.Live, modifier = Modifier.padding(padding)) {
            composable<Dest.Live> {
                val vm = viewModel { LiveViewModel(session, gate) }
                val state by vm.state.collectAsStateWithLifecycle()
                val angle = vm.angle.collectAsStateWithLifecycle()
                val read = remember(angle) { { angle.value } }
                LiveScreen(state, read, vm::setMode, vm::zero, vm::clearZero, vm::dismissMessage)
            }
            composable<Dest.Settings> {
                val vm = viewModel { SettingsViewModel(session, gate) }
                val state by vm.state.collectAsStateWithLifecycle()
                // The config is not notified: read it again whenever the tab shows (edits stay).
                LifecycleResumeEffect(vm) {
                    vm.reload()
                    onPauseOrDispose {}
                }
                // A plain Back would drop the entry with its unsaved edits; the tab bar keeps them.
                BackHandler(enabled = state.changed.isNotEmpty()) { goToTab(Tab.LIVE) }
                SettingsScreen(
                    state,
                    SettingsActions(
                        step = vm::step, set = vm::set, setMode = vm::setMode, setBle = vm::setBle,
                        save = vm::save, discard = vm::discard, reload = vm::reload, dismissMessage = vm::dismissMessage,
                    ),
                )
            }
            composable<Dest.Calibrate> {
                val vm = viewModel { CalibrationViewModel(session, gate) }
                val state by vm.state.collectAsStateWithLifecycle()
                CalibrationScreen(
                    state,
                    CalibrationActions(
                        startSensor = vm::startSensor, startMount = vm::startMount, finish = vm::finish,
                        cancel = vm::cancel, done = vm::done, dismissMessage = vm::dismissMessage,
                    ),
                )
            }
            composable<Dest.Device> {
                val vm = viewModel { DeviceViewModel(session, gate) }
                val state by vm.state.collectAsStateWithLifecycle()
                DeviceScreen(
                    state,
                    DeviceActions(
                        send = vm::send,
                        disconnect = sessionVm::disconnect,
                        openFirmwareUpdate = { nav.navigate(Dest.Firmware) },
                        openTrainingData = { nav.navigate(Dest.Training) },
                        dismissMessage = vm::dismissMessage,
                    ),
                )
            }
            composable<Dest.Firmware> {
                FirmwareRoute(viewModel { FirmwareViewModel(session, gate) }, onDone = { nav.popBackStack() })
            }
            composable<Dest.Training> {
                TrainingListRoute(
                    viewModel { training.listViewModel(session) },
                    onNew = { nav.navigate(Dest.TrainingSetup) },
                    onOpenSession = { nav.navigate(Dest.TrainingRecord) { launchSingleTop = true } },
                    onOpenRecording = { nav.navigate(Dest.TrainingReview(it)) },
                    onOpenUploadSettings = { nav.navigate(Dest.TrainingUpload) { launchSingleTop = true } },
                )
            }
            composable<Dest.TrainingUpload> {
                val uploads = training.uploads
                if (uploads != null) UploadSettingsRoute(viewModel { UploadSettingsViewModel(uploads, training.store) })
            }
            composable<Dest.TrainingReview> { entry ->
                val id = entry.toRoute<Dest.TrainingReview>().id
                TrainingReviewRoute(reviewViewModel(id, training, reviews), onDone = { nav.popBackStack() })
            }
            composable<Dest.TrainingSetup> {
                TrainingSetupRoute(viewModel { TrainingSetupViewModel(training.prefs, session, training.controller, gate) }) {
                    nav.navigate(Dest.TrainingRecord) { popUpTo<Dest.Training>() }
                }
            }
            composable<Dest.TrainingRecord> {
                TrainingRecordRoute(
                    viewModel { RecordViewModel(training.controller, gate) }, onBack = { nav.popBackStack() },
                    onReview = { nav.navigate(Dest.TrainingReview(it)) },
                )
            }
        }
    }
    // Back on the tabs after the link was given up while a session ran: show the session again,
    // and a review left open without the link over it.
    var resumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resumed) {
            if (training.controller.phase.value.active) nav.navigate(Dest.TrainingRecord) { launchSingleTop = true }
            startReview?.let { nav.navigate(Dest.TrainingReview(it)) }
            resumed = true
        }
        // The review shown, from the back stack itself (after the navigation above, never a frame before it).
        nav.currentBackStackEntryFlow.collect { e ->
            reported(e.takeIf { it.destination.hasRoute(Dest.TrainingReview::class) }?.toRoute<Dest.TrainingReview>()?.id)
        }
    }
}

/** Top bar (device, link, battery), reconnect bar and bottom tabs around a connected screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedFrame(
    shell: ShellState,
    tab: Tab?,
    onTab: (Tab) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onStopReconnect: () -> Unit = {},
    title: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                title ?: shell.deviceName,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            LinkLine(shell)
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onBack, Modifier.size(TouchHeight)) { Icon(DbIcons.Back, contentDescription = "Back") }
                        }
                    },
                    actions = {
                        BatteryIndicator(
                            shell.batteryPct, shell.charging, shell.chargeDone,
                            Modifier.padding(end = 16.dp),
                        )
                    },
                )
                if (shell.reconnecting != null) ReconnectBar(shell, onStopReconnect)
            }
        },
        bottomBar = {
            if (tab != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { onTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = {
                                Text(
                                    t.label, maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
        content = content,
    )
}

@Composable
private fun LinkLine(shell: ShellState) {
    val (text, color) = when {
        shell.reconnecting != null -> "Reconnecting…" to Amber
        !shell.bonded -> "Connected · not paired" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Connected" to Green
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
}

@Composable
private fun ReconnectBar(shell: ShellState, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, Amber)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Amber, strokeWidth = 2.dp)
            Text(
                shell.reconnecting?.message.orEmpty(), Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onStop, Modifier.heightIn(min = TouchHeight)) { Text("Stop") }
        }
    }
}
