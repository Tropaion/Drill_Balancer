package dev.drillbalancer.ui.training

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drillbalancer.ble.BlePermissions
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.label.AutoLabeler
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.net.WifiBlock
import dev.drillbalancer.recorder.net.WifiPermissions
import dev.drillbalancer.ui.components.ConfirmDialog
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.KeepScreenOn
import dev.drillbalancer.ui.components.TouchHeight
import dev.drillbalancer.ui.components.startActivitySafely
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.training.review.ReviewActions
import dev.drillbalancer.ui.training.review.ReviewScreen
import dev.drillbalancer.ui.training.review.ReviewViewModel
import dev.drillbalancer.ui.training.upload.UploadSettingsActions
import dev.drillbalancer.ui.training.upload.UploadSettingsScreen
import dev.drillbalancer.ui.training.upload.UploadSettingsViewModel
import dev.drillbalancer.upload.UploadServices

/** The process-wide recorder parts the training screens use (DrillBalancerApp); [uploads] null: no upload (tests). */
class TrainingDeps(
    val controller: RecordingController,
    val store: RecordingStore,
    val prefs: SetupPrefs,
    labeler: AutoLabeler? = null,
    val uploads: UploadServices? = null,
) {
    // a saved review goes into the upload queue
    val review = ReviewRepository(store, labeler) { id -> uploads?.queue?.saved(id) }

    fun listViewModel(session: DeviceSession?) = TrainingListViewModel(store, controller, session, uploads = uploads)
}

@Composable
fun TrainingListRoute(
    vm: TrainingListViewModel,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
    onNew: () -> Unit = {},
    onOpenRecording: (id: String) -> Unit = {},
    onOpenUploadSettings: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.reload()
        onPauseOrDispose {}
    }
    TrainingListScreen(
        state,
        TrainingListActions(
            newRecording = onNew, openSession = onOpenSession, reload = vm::reload, openRecording = onOpenRecording,
            openUploadSettings = onOpenUploadSettings, retryUpload = vm::retryUpload,
        ),
        modifier,
    )
}

/**
 * One screen's view model store without a nav entry (OfflineTraining's upload settings): it
 * lives in the activity's store, so a configuration change keeps it, and [ScopedUploadSettings]
 * clears it when the screen closes (its collectors stop; the next open starts afresh).
 */
class ScreenStore : ViewModel() {
    private var store: ViewModelStore? = null

    val owner: ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = store ?: ViewModelStore().also { store = it }
    }

    /** Whether a view model store is there now (tests). */
    internal val open: Boolean get() = store != null

    fun clear() {
        store?.clear()
        store = null
    }

    override fun onCleared() = clear()
}

/** The upload settings without a device, their view model only while they are open (or the activity is recreated). */
@Composable
fun ScopedUploadSettings(services: UploadServices, store: RecordingStore, modifier: Modifier = Modifier) {
    val holder = viewModel(key = "upload-settings") { ScreenStore() }
    val activity = LocalActivity.current
    DisposableEffect(holder) { onDispose { if (activity?.isChangingConfigurations != true) holder.clear() } }
    UploadSettingsRoute(viewModel(viewModelStoreOwner = holder.owner) { UploadSettingsViewModel(services, store) }, modifier)
}

/** The upload settings (Dest.TrainingUpload); the counts are read again whenever it shows. */
@Composable
fun UploadSettingsRoute(vm: UploadSettingsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.reload()
        onPauseOrDispose {}
    }
    UploadSettingsScreen(
        state,
        UploadSettingsActions(
            editRepo = vm::editRepo, saveRepo = vm::saveRepo, openTokenDialog = vm::openTokenDialog, closeTokenDialog = vm::closeTokenDialog,
            setToken = vm::setToken, removeToken = vm::removeToken, setWifiOnly = vm::setWifiOnly, setKeepLocal = vm::setKeepLocal,
            setMuteAudio = vm::setMuteAudio, testConnection = vm::testConnection, uploadNow = vm::uploadNow, dismissMessage = vm::dismissMessage,
        ),
        modifier,
    )
}

/**
 * The review of a recording's labels (Dest.TrainingReview). Back leaves it with the draft kept
 * (it opens there again); Discard asks first when something was changed, Reset all to auto
 * when it would drop edits (changed, or reviewed before); Save and Accept all close it once the
 * labels are written.
 */
@Composable
fun TrainingReviewRoute(vm: ReviewViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) { if (state.done) done() }
    ReviewScreen(
        state,
        ReviewActions(
            tapMarker = vm::tapMarker, drag = vm::drag, nudge = vm::nudge, setCursor = vm::setCursor, add = vm::add,
            delete = vm::delete, resetMarker = vm::resetMarker,
            resetAll = { if (state.dirty || state.reviewed) confirmReset = true else vm.resetAll() },
            selectHole = vm::selectHole,
            toggleTag = vm::toggleTag, toggleSuggestion = vm::toggleSuggestion, zoomIn = vm::zoomIn, zoomOut = vm::zoomOut,
            panBack = vm::panBack, panForward = vm::panForward, showAll = vm::showAll, acceptAll = vm::acceptAll,
            save = vm::save, discard = { if (state.dirty) confirm = true else vm.discard() }, dismissMessage = vm::dismissMessage,
            retry = vm::retry,
        ),
        modifier,
    )
    if (confirm) {
        ConfirmDialog(
            title = "Discard your changes?",
            text = "The labels go back to how they were saved last.",
            confirmLabel = "Discard",
            confirmColor = Red,
            onConfirm = vm::discard,
            onDismiss = { confirm = false },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset all labels to auto?",
            text = "Every marker, tag and suggestion goes back to what the detector found. Discard still brings back the labels saved last.",
            confirmLabel = "Reset all",
            confirmColor = Red,
            onConfirm = vm::resetAll,
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * The open review's view model (AppRoot keeps this in the activity's store): one store per
 * recording, so the tabs and the training screens without the device show the same
 * ReviewViewModel while the review is open on either side, across the link's end or return
 * (an edit, a Save in flight and the view stay) and configuration changes. [keepOnly] clears
 * the others when the review closes, each once no screen shows it any more (the tabs' review
 * stays composed while its exit transition runs: it would make a new view model). Main thread.
 */
class ReviewStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()
    private val shown = HashMap<String, Int>()      // the review screens composed per id ([shows])
    private var kept: String? = null

    /** [id]'s store, made again when it was cleared. */
    fun owner(id: String): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = stores.getOrPut(id) { ViewModelStore() }
    }

    /** A review screen shows [id] until the returned function is called. */
    fun shows(id: String): () -> Unit {
        shown.merge(id, 1, Int::plus)
        return {
            if (shown.merge(id, -1, Int::plus) == 0) shown.remove(id)
            keepOnly(kept)
        }
    }

    /** Clears every review's store but [id]'s (null: all) that no screen shows. */
    fun keepOnly(id: String?) {
        kept = id
        for (k in stores.keys.filter { it != id && it !in shown }) stores.remove(k)?.clear()
    }

    /** The reviews with a view model store (tests). */
    internal val open: Set<String> get() = stores.keys.toSet()

    override fun onCleared() {
        shown.clear()
        keepOnly(null)
    }
}

/** The review of [id] in [stores]. */
@Composable
fun reviewViewModel(id: String, deps: TrainingDeps, stores: ReviewStores): ReviewViewModel {
    DisposableEffect(id, stores) { onDispose(stores.shows(id)) }
    return viewModel(viewModelStoreOwner = remember(id, stores) { stores.owner(id) }) { ReviewViewModel(id, deps.review, deps.store) }
}

/** The review of [id] (its view model in [stores], kept while the review is open). */
@Composable
fun ScopedReview(id: String, deps: TrainingDeps, stores: ReviewStores, onDone: () -> Unit, modifier: Modifier = Modifier) {
    TrainingReviewRoute(reviewViewModel(id, deps, stores), onDone, modifier)
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
fun TrainingRecordRoute(vm: RecordViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, onReview: (id: String) -> Unit = {}) {
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
            review = onReview,
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
 * Which training screen shows without the device: a review over the session over the list, or
 * the upload settings over the list. Closing a review shows what is under it, the session it was
 * opened from or the list.
 */
data class OfflineScreens(val session: Boolean = false, val review: String? = null, val upload: Boolean = false) {
    fun openReview(id: String) = copy(review = id)

    fun closeReview() = copy(review = null)

    companion object {
        val Saver = listSaver<OfflineScreens, Any?>(
            { listOf(it.session, it.review, it.upload) },
            { OfflineScreens(it[0] as Boolean, it[1] as String?, it[2] as Boolean) },
        )
    }
}

/**
 * The training data opened from the connect screen (no device): the list, or the session
 * that still runs after the Bluetooth link was given up (the take goes on over Wi-Fi), until
 * the user leaves its end (DONE, FAILED). [review]: a review left open when the link ended,
 * shown first (its view model in [stores]); [onReview] hears which review is open (AppRoot
 * hands it over when the link is back, and clears the others' view models).
 */
@Composable
fun OfflineTraining(
    deps: TrainingDeps,
    stores: ReviewStores,
    onClose: () -> Unit,
    review: String? = null,
    onReview: (String?) -> Unit = {},
) {
    val phase by deps.controller.phase.collectAsStateWithLifecycle()
    var screens by rememberSaveable(stateSaver = OfflineScreens.Saver) { mutableStateOf(OfflineScreens(phase.active, review)) }
    LaunchedEffect(phase.active) { if (phase.active) screens = screens.copy(session = true) }
    val reported by rememberUpdatedState(onReview)
    LaunchedEffect(screens.review) { reported(screens.review) }
    val closeReview = { screens = screens.closeReview() }
    val closeUpload = { screens = screens.copy(upload = false) }
    // review over session over upload settings over the list, in the title, Back and the body alike
    val uploads = deps.uploads
    val upload = screens.upload && uploads != null
    val title = when {
        screens.review != null -> "Review"
        screens.session -> "Recording"
        upload -> "Upload settings"
        else -> "Training data"
    }
    val back = when {
        screens.review != null -> closeReview
        // The record screen's own Back handling (finish?) runs through the dispatcher: no arrow there.
        screens.session -> null
        upload -> closeUpload
        else -> onClose
    }
    OfflineFrame(title, onBack = back) { m ->
        val id = screens.review
        if (id != null) {
            BackHandler(onBack = closeReview)
            ScopedReview(id, deps, stores, onDone = closeReview, m)
        } else if (screens.session) {
            TrainingRecordRoute(
                viewModel { RecordViewModel(deps.controller, null) }, onBack = { screens = screens.copy(session = false) }, m,
                onReview = { screens = screens.openReview(it) },
            )
        } else if (upload) {
            BackHandler(onBack = closeUpload)
            ScopedUploadSettings(uploads, deps.store, m)
        } else {
            BackHandler(onBack = onClose)
            TrainingListRoute(
                viewModel { deps.listViewModel(null) },
                onOpenSession = { screens = screens.copy(session = true) }, m, onOpenRecording = { screens = screens.openReview(it) },
                onOpenUploadSettings = { screens = screens.copy(upload = true) },
            )
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
