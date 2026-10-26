package dev.drillbalancer.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingEntry
import dev.drillbalancer.recorder.RecordingState
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.Secure
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.Taxonomy
import dev.drillbalancer.ui.PairingCancelled
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.formatBytes
import dev.drillbalancer.ui.formatFixed
import dev.drillbalancer.ui.formatMinSec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The recorder's pairing: the screen's gate, with its cancel as Secure.Cancelled. */
fun PairingGate.asSecure(): Secure = Secure { block ->
    try {
        secure { block() }
    } catch (_: PairingCancelled) {
        throw Secure.Cancelled()
    }
}

/** Why a new recording cannot start now, or null when it can. */
fun recordBlocker(connection: ConnectionState?, recorder: RecorderStatus?, phase: RecordPhase): String? = when {
    phase.active -> null   // the open session is shown instead
    connection !is ConnectionState.Connected -> "Connect to the DrillBalancer to record."
    recorder == null -> "The device's firmware has no recorder. Update its firmware."
    else -> null
}

// ---- list ----

/** Where a recording is on the phone. NEEDS_REVIEW and SAVED come with milestone 6 (finalize, review). */
enum class LocalStage(val label: String) {
    RECORDING("Recording"),
    INTERRUPTED("Interrupted"),
    NEEDS_REVIEW("Needs review"),
    SAVED("Saved"),
}

/** Where its upload is (milestone 7; until then every recording stays on the phone). */
enum class UploadStage(val label: String) {
    NONE("Not uploaded"),
}

data class RecordingRow(
    val id: String,
    val title: String,      // "Wood · softwood 38 mm · drill"
    val detail: String,     // "26 Oct 14:30 · 0:42 · 1.9 MB"
    val stage: LocalStage,
    val upload: UploadStage = UploadStage.NONE,
)

data class TrainingListUiState(
    val rows: List<RecordingRow> = emptyList(),
    val loading: Boolean = true,
    val usedBytes: Long = 0,
    val freeBytes: Long? = null,
    val recordBlocker: String? = null,       // why "New recording" is off; null: it is on
    val session: RecordPhase = RecordPhase.IDLE,
    val error: String? = null,
)

/** The list rows of [entries], times in [zone]. */
fun recordingRows(entries: List<RecordingEntry>, zone: ZoneId = ZoneId.systemDefault()): List<RecordingRow> {
    val fmt = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone)
    return entries.map { e ->
        val j = e.journal
        val start = try {
            fmt.format(Instant.parse(j.startedUtc))
        } catch (_: Exception) {
            j.startedUtc
        }
        val seconds = (j.stats?.deviceUs ?: 0L) / 1_000_000
        RecordingRow(
            id = j.recordingId,
            title = setupTitle(j.setup),
            detail = "$start · ${formatMinSec(seconds)} · ${formatBytes(e.streamBytes)}",
            stage = when {
                e.finalized -> LocalStage.SAVED
                j.state == JournalState.RECORDING -> LocalStage.RECORDING
                j.state == JournalState.INTERRUPTED -> LocalStage.INTERRUPTED
                else -> LocalStage.NEEDS_REVIEW
            },
        )
    }
}

/** "Wood · softwood 38 mm · drill", the session's labels in one line. */
fun setupTitle(s: SessionSetup): String {
    val material = Taxonomy.label(Taxonomy.MATERIALS, s.material, s.materialOther)
    val thickness = s.thicknessMm?.let { " ${formatFixed(it.toFloat(), if (it % 1.0 == 0.0) 0 else 1)} mm" }.orEmpty()
    val mode = Taxonomy.label(Taxonomy.MODES, s.mode).lowercase()
    return "$material$thickness · $mode"
}

class TrainingListViewModel(
    private val store: RecordingStore,
    controller: RecordingController,
    session: DeviceSession?,                 // null: opened from the connect screen
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private data class Loaded(val rows: List<RecordingRow>, val used: Long, val free: Long?, val error: String?)

    private val loaded = MutableStateFlow<Loaded?>(null)

    val state: StateFlow<TrainingListUiState> = combine(
        loaded, controller.phase, session?.connection ?: flowOf(null), session?.recorder ?: flowOf(null),
    ) { l, phase, c, r ->
        TrainingListUiState(
            rows = l?.rows.orEmpty(),
            loading = l == null,
            usedBytes = l?.used ?: 0,
            freeBytes = l?.free,
            recordBlocker = recordBlocker(c, r, phase),
            session = phase,
            error = l?.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TrainingListUiState())

    init {
        reload()
        // A take that ends adds a row.
        viewModelScope.launch { controller.state.map { it.takes }.distinctUntilChanged().drop(1).collect { reload() } }
    }

    fun reload() {
        viewModelScope.launch {
            loaded.value = withContext(io) {
                try {
                    Loaded(recordingRows(store.list(), zone), store.usedBytes(), store.freeBytes(), null)
                } catch (e: IOException) {
                    Loaded(emptyList(), 0, null, "The recordings could not be read (${e.message}).")
                }
            }
        }
    }
}

// ---- setup ----

data class TrainingSetupUiState(
    val setup: SessionSetup = SessionSetup(),
    val loaded: Boolean = false,
    val blocker: String? = null,             // why Start is off
    val session: RecordPhase = RecordPhase.IDLE,
)

class TrainingSetupViewModel(
    private val prefs: SetupPrefs,
    session: DeviceSession,
    private val controller: RecordingController,
    private val gate: PairingGate,
) : ViewModel() {
    private val edited = MutableStateFlow<SessionSetup?>(null)

    val state: StateFlow<TrainingSetupUiState> = combine(
        edited, session.connection, session.recorder, controller.phase,
    ) { s, c, r, phase ->
        TrainingSetupUiState(
            setup = s ?: SessionSetup(),
            loaded = s != null,
            blocker = recordBlocker(c, r, phase) ?: if (s == null) "Loading…" else null,
            session = phase,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TrainingSetupUiState())

    init {
        viewModelScope.launch { edited.value = prefs.setup.first() }
    }

    fun change(setup: SessionSetup) {
        if (edited.value != null) edited.value = setup
    }

    /** Remembers the setup and opens the session; false if it cannot start now. */
    fun start(): Boolean {
        val s = state.value
        val setup = edited.value ?: return false
        if (s.blocker != null || s.session.active) return false
        val clean = setup.sanitized()
        viewModelScope.launch { prefs.save(clean) }
        controller.open(clean, gate.asSecure())
        return true
    }
}

// ---- record ----

data class RecordUiState(
    val rec: RecordingState = RecordingState(),
    val title: String = "",                  // the session's labels
) {
    constructor(rec: RecordingState) : this(rec, rec.setup?.let(::setupTitle).orEmpty())
}

/** The record screen of the controller's session, whose own setup gives the title. */
class RecordViewModel(
    private val controller: RecordingController,
    private val gate: PairingGate?,          // null: opened from the connect screen (no link)
) : ViewModel() {
    val state: StateFlow<RecordUiState> = controller.state.map(::RecordUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState(controller.state.value))

    private val secure: Secure get() = gate?.asSecure() ?: Secure.DIRECT

    fun startTake() = controller.startTake(secure)
    fun stopTake() = controller.stopTake()
    fun finish() = controller.close()
    fun retry() = controller.retry(secure)
    fun dismiss() = controller.dismiss()
}
