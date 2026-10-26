package dev.drillbalancer.ui.training.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.label.Derived
import dev.drillbalancer.recorder.label.LabelCheck
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.LabelIssue
import dev.drillbalancer.recorder.label.LabelTaxonomy
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Lanes
import dev.drillbalancer.recorder.label.LevelRows
import dev.drillbalancer.recorder.label.Phase
import dev.drillbalancer.recorder.label.Phases
import dev.drillbalancer.recorder.label.ReviewData
import dev.drillbalancer.recorder.label.ReviewRepository
import dev.drillbalancer.recorder.label.ReviewSource
import dev.drillbalancer.recorder.label.Source
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.ui.training.setupTitle
import dev.drillbalancer.ui.training.toolLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.floor

/** What Derived.compute gives: meta.derived, and orientation and off angle per hole. */
typealias DerivedLabels = Pair<Derived, Map<Int, Derived.Companion.HoleDerived>>

/** One signal lane over the visible range: min and max per bucket (NaN: no value), scaled to [lo, hi]. */
class LaneView(val name: String, val unit: String, val min: FloatArray, val max: FloatArray, val lo: Float, val hi: Float)

/** A marker as the plot draws it. */
data class MarkerView(
    val id: String,
    val type: String,
    val tUs: Long,
    val label: String,          // "C1", "BT1", "ON"
    val needsConfirm: Boolean,  // low confidence, not confirmed: dashed amber
    val user: Boolean,
    val edited: Boolean,
)

data class BandView(val t0Us: Long, val t1Us: Long, val phase: Phase)

data class SpanView(val id: String, val type: String, val t0Us: Long, val t1Us: Long)

data class TagChip(val tag: String, val label: String, val on: Boolean, val auto: Boolean)

data class SuggestionChip(val id: String, val label: String, val on: Boolean, val auto: Boolean)

/** The selected marker's panel. */
data class SelectedMarker(
    val marker: MarkerView,
    val title: String,          // "Contact · hole 1"
    val detail: String,         // "3.42 s · auto 0.74 · moved 30 ms"
    val canReset: Boolean,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val waiting: Boolean = false,          // the recording is still being finalized and labelled
    val error: String? = null,             // it cannot be reviewed
    val canRetry: Boolean = false,         // the error was storage or memory: Try again makes its files again, or loads it again
    val title: String = "",
    val tool: String = "",                 // the session's drill type, motor and model (toolLine)
    val c0Us: Long = 0,
    val c1Us: Long = 1,
    val fromUs: Long = 0,
    val toUs: Long = 1,
    val cursorUs: Long = 0,
    val lanes: List<LaneView> = emptyList(),
    val bands: List<BandView> = emptyList(),
    val markers: List<MarkerView> = emptyList(),
    val spans: List<SpanView> = emptyList(),
    val selected: SelectedMarker? = null,
    val holes: List<Int> = emptyList(),
    val hole: Int? = null,
    val tags: List<TagChip> = emptyList(),
    val suggestions: List<SuggestionChip> = emptyList(),
    val offAngle: String? = null,          // "Off angle 0.32° (tolerance 0.5°) · wall"
    val offAngleTag: Boolean = false,
    val errors: List<String> = emptyList(),     // the grammar: Save is refused while there are any
    val warnings: List<String> = emptyList(),
    val unconfirmed: Int = 0,
    val dirty: Boolean = false,
    val reviewed: Boolean = false,         // saved as reviewed before
    val busy: Boolean = false,
    val message: UiMessage? = null,
    val done: Boolean = false,             // saved or discarded: the screen closes
)

/**
 * The review of one recording: its labels on the signal lanes, edited with markers, nudges,
 * add / delete / reset, confirmations and tags (LabelEdits), a draft kept on every change, and
 * Save after the label check (LabelCheck) or Discard. Holds no Android types. [derive]: the
 * derived labels (off angle, orientation) of the labels, computed once per labels value on [io]
 * (a drag shows the ones before until they are there). Drafts are written on [io] by a writer
 * that outlives the view model: one not written yet when it is cleared is written after.
 */
class ReviewViewModel(
    private val id: String,
    private val repo: ReviewRepository,
    private val store: RecordingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val derive: (LevelRows?, Labels, Double) -> DerivedLabels = { level, l, tol -> Derived.compute(level, l, tol) },
) : ViewModel() {
    private data class Model(
        val data: ReviewData,
        val labels: Labels,
        val fromUs: Long,
        val toUs: Long,
        val cursorUs: Long,
        val selected: String? = null,
        val hole: Int? = null,
        val message: UiMessage? = null,
        val busy: Boolean = false,
        val done: Boolean = false,
    )

    private val model = MutableStateFlow<Model?>(null)
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private val drafts = Channel<Labels>(Channel.CONFLATED)
    private val files = ReentrantLock()  // the draft, Save and Discard: a draft write never lands after Save or Discard
    private var closed = false           // under files
    private var written: Labels? = null  // under files: the draft on storage
    private val toDerive = MutableStateFlow<Labels?>(null)
    private var derivedOf: Labels? = null    // main thread: the labels [derived] is of
    private var derived: DerivedLabels? = null
    private var envelopeKey: Pair<Long, Long>? = null
    private var envelopes: List<LaneView> = emptyList()
    private var scales: List<Pair<Float, Float>> = emptyList()

    init {
        viewModelScope.launch { load() }
        // Not viewModelScope: the last draft is still written once the screen went away
        // (onCleared closes the channel, the loop ends after it).
        CoroutineScope(SupervisorJob() + io).launch {
            for (l in drafts) {
                try {
                    writeDraft(l)
                } catch (_: Exception) {
                    // the draft is a convenience: the labels on screen are kept
                }
            }
        }
        // The derived labels of the latest labels, off the main thread (a drag sends many).
        viewModelScope.launch {
            toDerive.filterNotNull().collect { l ->
                if (l == derivedOf) return@collect
                val data = model.value?.data ?: return@collect
                val d = withContext(io) { derive(data.level, l, data.tolDeg) }
                derived = d
                derivedOf = l
                publish()
            }
        }
    }

    private fun writeDraft(l: Labels) = files.withLock {
        if (!closed && l !== written) {
            repo.saveDraft(id, l)
            written = l
        }
    }

    /**
     * The review closed (Back, or the system): a draft not written yet is still written by the
     * writer, off the main thread, so the review opened again restores it.
     */
    override fun onCleared() {
        drafts.close()
    }

    private suspend fun load() {
        while (true) {
            val seen = store.changes.value          // before the look: a change while it looks is not missed
            when (val src = withContext(io) { repo.source(id) }) {
                ReviewSource.Ready -> break
                is ReviewSource.Failed -> {
                    _state.value = if (src.outOfMemory) {
                        ReviewUiState(loading = false, canRetry = true, error = MEMORY_ERROR)
                    } else if (src.retry) {
                        ReviewUiState(
                            loading = false, canRetry = true,
                            error = "The recording could not be prepared (${src.reason}). Free some space on the phone, then try again.",
                        )
                    } else {
                        ReviewUiState(loading = false, error = "This recording cannot be reviewed: ${src.reason}")
                    }
                    return
                }
                ReviewSource.NotReady -> {
                    _state.value = ReviewUiState(loading = true, waiting = true)
                    store.changes.first { it != seen }
                }
            }
        }
        val (data, d) = try {
            withContext(io) { repo.load(id).let { it to derive(it.level, it.labels, it.tolDeg) } }
        } catch (e: Exception) {
            _state.value = ReviewUiState(loading = false, error = "The recording could not be read (${e.message ?: e.javaClass.simpleName}).")
            return
        } catch (_: OutOfMemoryError) {
            // labelling a long take on a small heap: not the take's fault, Try again labels it again
            _state.value = ReviewUiState(loading = false, canRetry = true, error = MEMORY_ERROR)
            return
        }
        scales = data.lanes.values.map { scaleOf(it) }
        val l = data.labels
        derived = d
        derivedOf = l
        model.value = Model(
            data, l, l.c0, l.c1, l.c0 + (l.c1 - l.c0) / 2,
            hole = l.holes.firstOrNull()?.hole,
            message = if (data.fromDraft) UiMessage("Your unsaved changes were restored.", UiMessage.Kind.INFO) else null,
        )
        publish()
    }

    // ---- actions ----

    /** After a storage or memory error (canRetry): makes the recording's files again if they are not there, then opens it. */
    fun retry() {
        if (!_state.value.canRetry) return
        _state.value = ReviewUiState(loading = true, waiting = true)
        viewModelScope.launch {
            try {
                withContext(io) { repo.retry(id) }
            } catch (_: Exception) {
                // the source says why
            }
            load()
        }
    }

    /** A tap on a marker's handle: selects it, and confirms it when it needs a confirmation. */
    fun tapMarker(markerId: String) = edit(remember = true) { m ->
        val e = m.labels.events.firstOrNull { it.id == markerId } ?: return@edit m
        val labels = if (LabelEdits.needsConfirm(e)) LabelEdits.confirm(m.labels, markerId) else m.labels
        m.copy(labels = labels, selected = markerId, hole = e.hole ?: m.hole)
    }

    fun select(markerId: String?) = edit(remember = false) { it.copy(selected = markerId) }

    /** The handle dragged to [tUs] (snapped to 10 ms). */
    fun drag(markerId: String, tUs: Long) = edit(remember = true) { m ->
        val l = LabelEdits.move(m.labels, markerId, tUs)
        if (l === m.labels && m.selected == markerId) m else m.copy(labels = l, selected = markerId)      // within the same 10 ms: nothing to do
    }

    fun nudge(deltaMs: Int) = edit(remember = true) { m ->
        val sel = m.selected ?: return@edit m
        val e = m.labels.events.firstOrNull { it.id == sel } ?: return@edit m
        val moved = LabelEdits.move(m.labels, sel, e.tUs + deltaMs * 1000L)
        val t = moved.events.first { it.id == sel }.tUs
        m.copy(labels = moved).keepVisible(t)
    }

    fun setCursor(tUs: Long) = edit(remember = false) { m -> m.copy(cursorUs = tUs.coerceIn(m.labels.c0, m.labels.c1 - 1), selected = null) }

    fun add(type: String) = edit(remember = true) { m ->
        val (labels, newId) = LabelEdits.add(m.labels, type, m.cursorUs)
        m.copy(labels = labels, selected = newId, hole = labels.events.first { it.id == newId }.hole ?: m.hole)
    }

    fun delete() = edit(remember = true) { m ->
        val sel = m.selected ?: return@edit m
        m.copy(labels = LabelEdits.delete(m.labels, sel), selected = null)
    }

    fun resetMarker() = edit(remember = true) { m ->
        val sel = m.selected ?: return@edit m
        m.copy(labels = LabelEdits.resetMarker(m.labels, sel))
    }

    /** Every label back to what the detector found. */
    fun resetAll() = edit(remember = true) { m ->
        val auto = m.data.auto ?: m.data.saved
        m.copy(labels = auto.copy(revision = m.labels.revision), selected = null, hole = auto.holes.firstOrNull()?.hole)
    }

    fun selectHole(hole: Int) = edit(remember = false) { it.copy(hole = hole) }

    fun toggleTag(tag: String) = edit(remember = true) { m ->
        val h = m.hole ?: return@edit m
        val on = m.labels.holes.firstOrNull { it.hole == h }?.outcomeTags?.contains(tag) == true
        m.copy(labels = LabelEdits.setTag(m.labels, h, tag, !on))
    }

    fun toggleSuggestion(spanId: String) = edit(remember = true) { m ->
        val present = m.labels.spans.firstOrNull { it.id == spanId }
        val span = present ?: m.data.auto?.spans?.firstOrNull { it.id == spanId } ?: return@edit m
        m.copy(labels = LabelEdits.setSpan(m.labels, span, present == null))
    }

    fun zoomIn() = view { m ->
        val span = maxOf(MIN_SPAN_US, (m.toUs - m.fromUs) / 2)
        val centre = m.selected?.let { s -> m.labels.events.firstOrNull { it.id == s }?.tUs }?.takeIf { it in m.fromUs until m.toUs } ?: m.cursorUs
        m.window(centre - span / 2, span)
    }

    fun zoomOut() = view { m -> m.window((m.fromUs + m.toUs) / 2 - (m.toUs - m.fromUs), 2 * (m.toUs - m.fromUs)) }

    fun panBack() = view { m -> m.window(m.fromUs - (m.toUs - m.fromUs) / 2, m.toUs - m.fromUs) }

    fun panForward() = view { m -> m.window(m.fromUs + (m.toUs - m.fromUs) / 2, m.toUs - m.fromUs) }

    fun showAll() = view { m -> m.copy(fromUs = m.labels.c0, toUs = m.labels.c1) }

    fun dismissMessage() = edit(remember = false) { it.copy(message = null) }

    /** Confirms every uncertain marker as it is, then saves. */
    fun acceptAll() {
        edit(remember = true) { m -> m.copy(labels = LabelEdits.confirmAll(m.labels)) }
        save()
    }

    /** Checks the labels and saves them as reviewed; the screen closes when it is done. */
    fun save() {
        val m = model.value ?: return
        if (m.busy) return
        val errors = LabelCheck.errors(m.labels)
        if (errors.isNotEmpty()) {
            val first = errors.first()
            update(m.copy(message = UiMessage("Not saved: ${first.message}"), selected = first.markerId ?: m.selected).let { x ->
                first.markerId?.let { mid -> x.labels.events.firstOrNull { it.id == mid } }?.let { x.keepVisible(it.tUs) } ?: x
            })
            return
        }
        val open = m.labels.events.filter { LabelEdits.needsConfirm(it) }
        if (open.isNotEmpty()) {
            val n = open.size
            update(
                m.copy(
                    message = UiMessage(
                        "$n uncertain label${if (n == 1) "" else "s"} (dashed) need${if (n == 1) "s" else ""} a tap to confirm, or use Accept all.",
                        UiMessage.Kind.WARNING,
                    ),
                    selected = open.first().id,
                ).keepVisible(open.first().tUs),
            )
            return
        }
        update(m.copy(busy = true, message = null))
        viewModelScope.launch {
            try {
                withContext(io) {
                    files.withLock {
                        repo.save(m.data, m.labels, LabelEdits.editedCount(m.labels, m.data.auto))
                        closed = true
                    }
                }
                model.value?.let { update(it.copy(busy = false, done = true)) }
            } catch (e: Exception) {
                model.value?.let { update(it.copy(busy = false, message = UiMessage("Saving failed (${e.message ?: e.javaClass.simpleName}). The draft is kept."))) }
            }
        }
    }

    /** Drops the unsaved changes; the screen closes. */
    fun discard() {
        val m = model.value
        viewModelScope.launch {
            try {
                withContext(io) {
                    files.withLock {
                        closed = true
                        repo.discardDraft(id)
                    }
                }
            } catch (_: Exception) {
            }
            if (m != null) update(m.copy(done = true)) else _state.update { it.copy(done = true) }
        }
    }

    // ---- state ----

    private fun Model.window(from: Long, span: Long): Model {
        val total = labels.c1 - labels.c0
        val s = span.coerceIn(minOf(MIN_SPAN_US, total), total)
        val f = from.coerceIn(labels.c0, labels.c1 - s)
        return copy(fromUs = f, toUs = f + s)
    }

    private fun Model.keepVisible(t: Long): Model =
        if (t in fromUs until toUs) this else window(t - (toUs - fromUs) / 2, toUs - fromUs)

    private fun view(change: (Model) -> Model) = edit(remember = false, change)

    private inline fun edit(remember: Boolean, change: (Model) -> Model) {
        val m = model.value ?: return
        if (m.busy || m.done) return
        val n = change(m)
        if (n === m) return
        update(n)
        if (remember && n.labels != m.labels) drafts.trySend(n.labels)
    }

    private fun update(m: Model) {
        model.value = m
        publish()
    }

    private fun publish() {
        val m = model.value ?: return
        val key = m.fromUs to m.toUs
        if (key != envelopeKey) {
            envelopes = lanesOf(m.data.lanes, m.fromUs, m.toUs, scales)
            envelopeKey = key
        }
        if (m.labels !== derivedOf) toDerive.value = m.labels
        _state.value = stateOf(m.data, m.labels, envelopes, m.fromUs, m.toUs, m.cursorUs, m.selected, m.hole, m.message, m.busy, m.done, derived)
    }

    companion object {
        const val MIN_SPAN_US = 200_000L
        const val BUCKETS = 240
        const val MEMORY_ERROR = "The phone ran out of memory preparing this recording. Close other apps, then try again."
        val LANE_NAMES = listOf("IMU energy" to "dB", "Sound" to "dBFS", "Motor pitch" to "Hz", "Cutting noise" to "dB")

        /** A lane's fixed scale: its finite range over the whole take (f0 from 0). */
        fun scaleOf(v: FloatArray): Pair<Float, Float> {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (x in v) if (x.isFinite()) {
                lo = minOf(lo, x)
                hi = maxOf(hi, x)
            }
            if (!lo.isFinite()) return 0f to 1f
            if (hi - lo < 1e-3f) return lo - 1f to hi + 1f
            val pad = (hi - lo) * 0.05f
            return lo - pad to hi + pad
        }

        /** Min/max envelopes of the lanes for [from, to): [BUCKETS] buckets (the frame at a bucket's middle when none starts in it). */
        fun lanesOf(lanes: Lanes, from: Long, to: Long, scales: List<Pair<Float, Float>>, buckets: Int = BUCKETS): List<LaneView> =
            lanes.values.mapIndexed { i, v ->
                val mn = FloatArray(buckets) { Float.NaN }
                val mx = FloatArray(buckets) { Float.NaN }
                val span = (to - from).toDouble()
                for (b in 0 until buckets) {
                    val ta = from + span * b / buckets
                    val tb = from + span * (b + 1) / buckets
                    var ka = ceil((ta - lanes.t0Us) / lanes.frameUs).toInt()
                    var kb = ceil((tb - lanes.t0Us) / lanes.frameUs).toInt()
                    if (kb <= ka) {
                        ka = floor(((ta + tb) / 2 - lanes.t0Us) / lanes.frameUs).toInt()
                        kb = ka + 1
                    }
                    for (k in maxOf(0, ka) until minOf(v.size, kb)) {
                        val x = v[k]
                        if (!x.isFinite()) continue
                        if (mn[b].isNaN() || x < mn[b]) mn[b] = x
                        if (mx[b].isNaN() || x > mx[b]) mx[b] = x
                    }
                }
                val (lo, hi) = scales.getOrElse(i) { scaleOf(v) }
                val (name, unit) = LANE_NAMES.getOrElse(i) { "Lane $i" to "" }
                LaneView(name, unit, mn, mx, lo, hi)
            }

        /** The screen's state from the labels being edited (pure: the screenshots build it so too). */
        fun stateOf(
            data: ReviewData,
            labels: Labels,
            lanes: List<LaneView>,
            fromUs: Long,
            toUs: Long,
            cursorUs: Long,
            selected: String?,
            hole: Int?,
            message: UiMessage? = null,
            busy: Boolean = false,
            done: Boolean = false,
            derivedLabels: DerivedLabels? = null,      // Derived.compute of labels; null: computed here
        ): ReviewUiState {
            val markers = labels.events.sortedBy { it.tUs }.map { e ->
                MarkerView(e.id, e.type, e.tUs, shortLabel(e.type, e.hole), LabelEdits.needsConfirm(e), e.source == Source.USER, e.edited)
            }
            val sel = labels.events.firstOrNull { it.id == selected }?.let { e ->
                val mv = markers.first { it.id == e.id }
                val parts = ArrayList<String>()
                parts += LabelEdits.formatTime(labels, e.tUs)
                parts += if (e.source == Source.USER) "added by you" else "auto ${fmt2(e.confidence)}"
                e.autoTUs?.let { a -> parts += "moved ${signedMs(e.tUs - a)}" }
                if (e.confirmed) parts += "confirmed"
                SelectedMarker(mv, LabelCheck.name(e.type) + (e.hole?.let { " · hole $it" } ?: ""), parts.joinToString(" · "), e.autoTUs != null)
            }
            val holes = labels.holes.map { it.hole }
            val h = hole?.takeIf { it in holes } ?: holes.firstOrNull()
            val entry = labels.holes.firstOrNull { it.hole == h }
            val autoTags = data.auto?.holes?.firstOrNull { it.hole == h }?.outcomeTags.orEmpty().toSet()
            val tags = if (entry == null) emptyList() else LabelTaxonomy.OUTCOME_TAGS.filter { it !in LabelTaxonomy.DERIVED_TAGS }.map { t ->
                TagChip(t, tagLabel(t), t in entry.outcomeTags, t in autoTags)
            }
            val autoSpans = data.auto?.spans.orEmpty().filter { it.type in LabelEdits.SUGGESTION_SPANS }
            val current = labels.spans.filter { it.type in LabelEdits.SUGGESTION_SPANS }
            val suggestions = (current + autoSpans.filter { a -> current.none { it.id == a.id } }).sortedBy { it.t0Us }.map { s ->
                val what = if (s.type == "ignore") (s.reason?.replaceFirstChar { it.uppercase() } ?: "Ignored") else LabelCheck.name(s.type)
                val where = s.hole?.let { "hole $it" } ?: LabelEdits.formatTime(labels, s.t0Us)
                SuggestionChip(s.id, "$what · $where", current.any { it.id == s.id }, s.source == Source.AUTO)
            }
            val (derived, holeDerived) = derivedLabels ?: Derived.compute(data.level, labels, data.tolDeg)
            val off = (if (h != null) holeDerived[h]?.offAngleDeg else null) ?: derived.offAngleDeg
            val offText = off?.let { o ->
                buildString {
                    append("Off angle ").append(fmt2(o)).append("°")
                    if (data.tolDeg.isFinite()) append(" (tolerance ").append(fmt2(data.tolDeg)).append("°)")
                    if (derived.orientation != "unknown") append(" · ").append(derived.orientation)
                }
            }
            val issues: List<LabelIssue> = LabelCheck.check(labels)
            val bands = Phases.segments(labels).filter { it.t1Us > fromUs && it.t0Us < toUs }.map { BandView(it.t0Us, it.t1Us, it.phase) }
            return ReviewUiState(
                loading = false,
                title = data.setup?.let(::setupTitle).orEmpty(),
                tool = data.setup?.let(::toolLine).orEmpty(),
                c0Us = labels.c0, c1Us = labels.c1, fromUs = fromUs, toUs = toUs, cursorUs = cursorUs,
                lanes = lanes, bands = bands, markers = markers,
                spans = labels.spans.map { SpanView(it.id, it.type, it.t0Us, it.t1Us) },
                selected = sel, holes = holes, hole = h, tags = tags, suggestions = suggestions,
                offAngle = offText, offAngleTag = off != null && off > data.tolDeg,
                errors = issues.filter { it.error }.map { it.message },
                warnings = issues.filter { !it.error }.map { it.message },
                unconfirmed = labels.events.count { LabelEdits.needsConfirm(it) },
                dirty = labels != data.saved, reviewed = data.reviewStatus != RecordingStore.AUTO_ONLY,
                busy = busy, message = message, done = done,
            )
        }

        fun shortLabel(type: String, hole: Int?): String {
            val base = when (type) {
                "motor_on" -> "ON"
                "motor_off" -> "OFF"
                "contact" -> "C"
                "breakthrough" -> "BT"
                "retract" -> "RT"
                "kickback" -> "KB"
                else -> type.take(3).uppercase()
            }
            return if (hole != null && type != "kickback") "$base$hole" else base
        }

        fun tagLabel(tag: String): String = when (tag) {
            "clean" -> "Clean"
            "wandered_start" -> "Wandered at start"
            "breakout_chipping" -> "Breakout chipping"
            "oversized" -> "Oversized"
            "aborted" -> "Aborted"
            "bit_stuck" -> "Bit stuck"
            "kickback" -> "Kickback"
            "off_angle" -> "Off angle"
            else -> tag
        }

        private fun fmt2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

        private fun signedMs(us: Long) = String.format(Locale.ROOT, "%+d ms", us / 1000)
    }
}
