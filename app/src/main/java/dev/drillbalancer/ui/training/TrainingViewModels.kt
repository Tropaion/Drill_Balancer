package dev.drillbalancer.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.recorder.RecordPhase
import dev.drillbalancer.recorder.RecordingController
import dev.drillbalancer.recorder.RecordingEntry
import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingState
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.Secure
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.SetupPrefs
import dev.drillbalancer.recorder.Taxonomy
import dev.drillbalancer.ui.PairingCancelled
import dev.drillbalancer.ui.PairingGate
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.formatBytes
import dev.drillbalancer.ui.formatFixed
import dev.drillbalancer.ui.formatMinSec
import dev.drillbalancer.upload.FailureKind
import dev.drillbalancer.upload.LocalUpload
import dev.drillbalancer.upload.RepoCopy
import dev.drillbalancer.upload.TokenState
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadServices
import dev.drillbalancer.upload.UploadStage
import kotlinx.coroutines.CancellationException
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

/** The list's label of where a recording is on the phone. */
val RecordingStage.label: String
    get() = when (this) {
        RecordingStage.RECORDING -> "Recording"
        RecordingStage.INTERRUPTED -> "Interrupted"
        RecordingStage.NEEDS_REVIEW -> "Needs review"
        RecordingStage.SAVED -> "Saved"
    }

/** The list's label of where a recording's upload is. */
val UploadStage.label: String
    get() = when (this) {
        UploadStage.None -> "Not uploaded"
        UploadStage.Queued -> "Queued"
        is UploadStage.Uploading -> pct?.let { "Uploading $it %" } ?: "Uploading"
        UploadStage.Done -> "Uploaded"
        is UploadStage.Failed -> "Upload failed"
    }

/** The upload line of the list: where recordings go, or what is missing. */
data class UploadSummary(val repo: String, val tokenSet: Boolean, val tokenRejected: Boolean, val wifiOnly: Boolean) {
    /** No token, or one GitHub rejected: the settings fix it, not Retry. */
    val tokenProblem: Boolean get() = !tokenSet || tokenRejected

    val text: String
        get() = when {
            !tokenSet -> "$repo · no token set"
            tokenRejected -> "$repo · token rejected"
            else -> "$repo · ${if (wifiOnly) "Wi-Fi only" else "any network"}"
        }
}

data class RecordingRow(
    val id: String,
    val title: String,      // "Wood · softwood 38 mm · drill"
    val detail: String,     // "26 Oct 14:30 · 0:42 · 1.9 MB"
    val stage: RecordingStage,
    val upload: UploadStage = UploadStage.None,
    val repoCopy: RepoCopy = RepoCopy.NONE,   // what a data repo holds of it (upload.json)
    val canOpen: Boolean = false,   // not being recorded: the review opens (it waits for the files, or says why there are none, with Try again)
)

data class TrainingListUiState(
    val rows: List<RecordingRow> = emptyList(),
    val loading: Boolean = true,
    val usedBytes: Long = 0,
    val freeBytes: Long? = null,
    val recordBlocker: String? = null,       // why "New recording" is off; null: it is on
    val session: RecordPhase = RecordPhase.IDLE,
    val error: String? = null,
    val upload: UploadSummary? = null,        // null: no upload in this build (tests)
    val selected: Set<String>? = null,        // null: not selecting; else the rows ticked for Delete
    val deleting: Boolean = false,
    val message: UiMessage? = null,           // what a Delete of several takes could not do
) {
    /** The selected rows, in list order. */
    val selectedRows: List<RecordingRow> get() = selected?.let { s -> rows.filter { it.id in s } }.orEmpty()
}

/**
 * Delete's confirmation for several [rows]: a take a data repo holds keeps that copy (also one
 * uploaded before a relabel, or to the repo set before), the others are gone for good; a waiting
 * or running upload is stopped. One take gets the review's deleteTakeText.
 */
fun deleteTakesText(rows: List<RecordingRow>): String {
    val uploaded = rows.count { it.repoCopy != RepoCopy.NONE }
    val local = rows.size - uploaded
    val sending = rows.any { it.upload.sending }
    return listOfNotNull(
        when (uploaded) {
            0 -> null
            1 -> "1 is uploaded: only the phone's copy is deleted, its data repo keeps it."
            else -> "$uploaded are uploaded: only the phone's copies are deleted, their data repo keeps them."
        },
        when (local) {
            0 -> null
            1 -> "1 is not uploaded: its files are deleted from the phone for good."
            else -> "$local are not uploaded: their files are deleted from the phone for good."
        },
        if (sending) "Their uploads are stopped; one already finishing still reaches the data repo." else null,
    ).joinToString(" ")
}

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
            detail = "$start · ${formatMinSec(seconds)} · ${formatBytes(e.bytes)}",
            stage = e.stage,
            canOpen = e.stage != RecordingStage.RECORDING,
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

/**
 * "Drill/driver · Brushless · Bosch GSR 18V-55", the session's tool (meta.session.drill) in one
 * line as the Finalizer writes it (Taxonomy.stored: "other" without its text is unknown); what is
 * unknown is left out, empty when nothing is known.
 */
fun toolLine(setup: SessionSetup): String {
    val s = setup.sanitized()
    return listOfNotNull(
        Taxonomy.stored(s.drillType, s.drillTypeOther).takeIf { it != Taxonomy.UNKNOWN }?.let { Taxonomy.label(Taxonomy.DRILL_TYPES, it, s.drillTypeOther) },
        Taxonomy.stored(s.motor, s.motorOther).takeIf { it != Taxonomy.UNKNOWN }?.let { Taxonomy.label(Taxonomy.MOTORS, it, s.motorOther) },
        s.drillModel.trim().ifEmpty { null },
    ).joinToString(" · ")
}

class TrainingListViewModel(
    private val store: RecordingStore,
    controller: RecordingController,
    session: DeviceSession?,                 // null: opened from the connect screen
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val uploads: UploadServices? = null,
    private val delete: (id: String) -> Boolean = store::remove,   // TrainingDeps.delete: also stops its upload
) : ViewModel() {
    private data class Loaded(
        val rows: List<RecordingRow>,
        val used: Long,
        val free: Long?,
        val error: String?,
        val review: Map<String, String?> = emptyMap(),        // meta.review.status by id
        val local: Map<String, LocalUpload> = emptyMap(),
    )

    private val loaded = MutableStateFlow<Loaded?>(null)
    private data class Selection(val ids: Set<String>? = null, val deleting: Boolean = false, val message: UiMessage? = null)
    private val selection = MutableStateFlow(Selection())

    // The rows with their upload stages: what upload.json says, the queue, a rejected token.
    private val withUploads = combine(
        loaded, uploads?.queue?.active ?: flowOf(emptyMap()), uploads?.tokens?.state ?: flowOf(TokenState()),
        uploads?.prefs?.settings ?: flowOf(null),
    ) { l, active, token, settings ->
        l?.copy(
            rows = l.rows.map { row ->
                val local = l.local[row.id]
                row.copy(upload = UploadQueue.stage(l.review[row.id], local, active[row.id], token.rejected), repoCopy = local?.repoCopy ?: RepoCopy.NONE)
            },
        ) to settings?.let { UploadSummary(it.repo, token.set, token.rejected, it.wifiOnly) }
    }

    val state: StateFlow<TrainingListUiState> = combine(
        withUploads, controller.phase, session?.connection ?: flowOf(null), session?.recorder ?: flowOf(null), selection,
    ) { (l, summary), phase, c, r, sel ->
        val rows = l?.rows.orEmpty()
        TrainingListUiState(
            rows = rows,
            loading = l == null,
            usedBytes = l?.used ?: 0,
            freeBytes = l?.free,
            recordBlocker = recordBlocker(c, r, phase),
            session = phase,
            error = l?.error,
            upload = summary,
            selected = sel.ids?.intersect(rows.mapTo(mutableSetOf()) { it.id }),   // a row deleted elsewhere drops out
            deleting = sel.deleting,
            message = sel.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TrainingListUiState())

    init {
        reload()
        // A take that ends adds a row; its finalization changes its stage.
        viewModelScope.launch { controller.state.map { it.takes }.distinctUntilChanged().drop(1).collect { reload() } }
        // each take a Delete of several removes counts once: that reloads once, at its end
        viewModelScope.launch { store.changes.drop(1).collect { if (!selection.value.deleting) reload() } }
    }

    fun reload() {
        viewModelScope.launch {
            val repo = uploads?.prefs?.current()?.repo
            loaded.value = withContext(io) {
                try {
                    val entries = store.list()
                    Loaded(
                        recordingRows(entries, zone), entries.sumOf { it.bytes }, store.freeBytes(), null,
                        review = entries.associate { it.journal.recordingId to it.reviewStatus },
                        local = if (uploads != null && repo != null) uploads.queue.local(entries, repo) else emptyMap(),
                    )
                } catch (e: IOException) {
                    Loaded(emptyList(), 0, null, "The recordings could not be read (${e.message}).")
                }
            }
            // nothing left to select: Select ends (a new take must not open in it)
            val s = selection.value
            if (s.ids != null && !s.deleting && loaded.value?.rows.isNullOrEmpty()) selection.value = s.copy(ids = null)
        }
    }

    /** Select: the rows get check boxes, Delete takes the ticked ones. */
    fun startSelecting() {
        selection.value = Selection(ids = emptySet())
    }

    fun cancelSelecting() {
        if (!selection.value.deleting) selection.value = Selection()
    }

    /** Ticks or unticks [id]; a take being recorded cannot be selected. */
    fun toggle(id: String) {
        val s = selection.value
        val ids = s.ids ?: return
        if (s.deleting || state.value.rows.none { it.id == id && it.canOpen }) return
        selection.value = s.copy(ids = if (id in ids) ids - id else ids + id)
    }

    fun selectAll() {
        val s = selection.value
        if (s.ids == null || s.deleting) return
        selection.value = s.copy(ids = state.value.rows.filter { it.canOpen }.mapTo(mutableSetOf()) { it.id })
    }

    /**
     * Deletes the selected takes from the phone, each as the review's Delete take does
     * (TrainingDeps.delete). Says how many could not be.
     */
    fun deleteSelected() {
        val ids = state.value.selected.orEmpty()
        if (ids.isEmpty() || selection.value.deleting) return
        selection.value = selection.value.copy(deleting = true, message = null)
        viewModelScope.launch {
            var failed = 0
            var why: String? = null
            try {
                withContext(io) {
                    for (id in ids) {
                        val gone = try {
                            delete(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            why = why ?: e.message
                            false
                        }
                        if (!gone) failed++
                    }
                }
            } finally {
                selection.value = Selection(
                    message = if (failed == 0) null else UiMessage("$failed of ${ids.size} takes could not be deleted" + (why?.let { " ($it)." } ?: ". Try again.")),
                )
                reload()
            }
        }
    }

    fun dismissMessage() {
        selection.value = selection.value.copy(message = null)
    }

    /** Sends a failed upload again; over the data repo's copy only when the row's failure is a conflict (its text says Retry does that). */
    fun retryUpload(id: String) {
        val q = uploads?.queue ?: return
        val overwrite = (state.value.rows.firstOrNull { it.id == id }?.upload as? UploadStage.Failed)?.kind == FailureKind.CONFLICT
        viewModelScope.launch { q.enqueue(id, overwrite) }
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
