package dev.drillbalancer.recorder.label

/** The label ids and phase parameters of taxonomy.v1.json (LabelTaxonomyTest compares them with the file). */
object LabelTaxonomy {
    const val MOTOR_ON = "motor_on"
    const val CONTACT = "contact"
    const val BREAKTHROUGH = "breakthrough"
    const val RETRACT = "retract"
    const val MOTOR_OFF = "motor_off"
    const val KICKBACK = "kickback"

    /** Point events in the taxonomy's order. */
    val POINT_EVENTS = listOf(MOTOR_ON, CONTACT, BREAKTHROUGH, RETRACT, MOTOR_OFF, KICKBACK)

    /** The point events of the grammar (kickback is not). */
    val GRAMMAR_EVENTS = setOf(MOTOR_ON, CONTACT, BREAKTHROUGH, RETRACT, MOTOR_OFF)

    /** Point events that carry a hole. */
    val HOLE_EVENTS = setOf(CONTACT, BREAKTHROUGH, RETRACT)

    val SPAN_EVENTS = listOf("stuck", "wander", "wobble", "handling", "ignore")
    val PROBLEM_SPANS = listOf("stuck", "wander", "wobble")
    val IGNORE_REASONS = listOf("speech", "bump", "other")

    val OUTCOME_TAGS = listOf("clean", "off_angle", "wandered_start", "breakout_chipping", "oversized", "aborted", "bit_stuck", "kickback")

    /** Outcome tags never chosen by a person (derived_rules.off_angle_tag). */
    val DERIVED_TAGS = setOf("off_angle")

    val MATERIALS_AND_SPECIAL: Set<String> get() = dev.drillbalancer.recorder.Taxonomy.MATERIALS.map { it.id }.toSet() + setOf("unknown", "other")

    // phase_params.windows_ms, us
    const val SPINUP_MAX_US = 300_000L
    const val BREAKTHROUGH_PRE_US = 50_000L
    const val BREAKTHROUGH_POST_US = 250_000L
    const val SPINDOWN_MAX_US = 600_000L
    const val GUARD_MS = 50

    const val ORIENTATION_WALL_DEG = 30.0
    const val ORIENTATION_VERTICAL_DEG = 60.0
}

/** The phases of taxonomy.v1.json, in its order. */
enum class Phase(val id: String) {
    OFF("off"), SPINUP("spinup"), FREE_RUN("free_run"), DRILLING("drilling"), BREAKTHROUGH("breakthrough"), SPINDOWN("spindown"), UNKNOWN("unknown"),
}

/**
 * dbdata.labels: the one fixed state machine from events to phases (the same table, the same
 * error rule), so the app's phases and grammar checks are the dataset's. An event the grammar
 * does not allow makes everything from the previous event to the next motor_off unknown.
 */
object Phases {
    enum class State { OFF, ON, HOLE, HOLE_OFF, ERR }

    private val NEXT = mapOf(
        (State.OFF to LabelTaxonomy.MOTOR_ON) to State.ON,
        (State.ON to LabelTaxonomy.CONTACT) to State.HOLE,
        (State.ON to LabelTaxonomy.MOTOR_OFF) to State.OFF,
        (State.HOLE to LabelTaxonomy.BREAKTHROUGH) to State.ON,
        (State.HOLE to LabelTaxonomy.RETRACT) to State.ON,
        (State.HOLE to LabelTaxonomy.MOTOR_OFF) to State.HOLE_OFF,     // the motor stops with the bit in the hole
        (State.HOLE_OFF to LabelTaxonomy.MOTOR_ON) to State.HOLE,      // restart in the hole
        (State.HOLE_OFF to LabelTaxonomy.RETRACT) to State.OFF,        // bit pulled out with the motor off
    )

    data class Segment(val t0Us: Long, val t1Us: Long, val phase: Phase, val hole: Int = 0)

    class Step(val index: Int, val event: Marker, val before: State, val after: State, val allowed: Boolean)

    /** The grammar's point events, stable-sorted by time (kickback and unknown types left out). */
    fun grammarEvents(labels: Labels): List<Marker> =
        labels.events.filter { it.type in LabelTaxonomy.GRAMMAR_EVENTS }.sortedBy { it.tUs }

    /** labels._walk: the machine over time-sorted grammar events, before / after each. */
    fun walk(evs: List<Marker>): List<Step> {
        var state = State.OFF
        return evs.mapIndexed { i, e ->
            var after = if (state == State.ERR) null else NEXT[state to e.type]
            val allowed = after != null
            if (!allowed) after = if (e.type == LabelTaxonomy.MOTOR_OFF) State.OFF else State.ERR
            Step(i, e, state, after!!, allowed).also { state = after }
        }
    }

    /** What a grammar issue is: an event in the wrong order, or a contact / closing event / other event with the wrong hole. */
    enum class GrammarKind { ORDER, CONTACT_NO_HOLE, WRONG_HOLE, HOLE_NOT_ALLOWED }

    /** [message] is dbdata's text; [before] the state the event came in. */
    class GrammarIssue(val index: Int, val event: Marker, val message: String, val kind: GrammarKind, val before: State)

    /** labels.grammar_state: order and hole violations, and the state the labels end in. */
    fun grammarState(labels: Labels): Pair<List<GrammarIssue>, State> {
        val issues = ArrayList<GrammarIssue>()
        var state = State.OFF
        var hole: Int? = null
        for (st in walk(grammarEvents(labels))) {
            state = st.after
            val e = st.event
            if (!st.allowed) {
                if (st.before != State.ERR) {
                    issues += GrammarIssue(st.index, e, "${e.type} at ${e.tUs} not allowed while ${st.before.name.lowercase()}", GrammarKind.ORDER, st.before)
                }
                continue
            }
            when (e.type) {
                LabelTaxonomy.CONTACT -> {
                    hole = e.hole
                    if (hole == null) issues += GrammarIssue(st.index, e, "contact without hole", GrammarKind.CONTACT_NO_HOLE, st.before)
                }
                LabelTaxonomy.BREAKTHROUGH, LabelTaxonomy.RETRACT -> if (e.hole != hole) {
                    issues += GrammarIssue(
                        st.index, e, "${e.type} hole ${e.hole ?: "None"} does not close contact hole ${hole ?: "None"}", GrammarKind.WRONG_HOLE, st.before,
                    )
                }
                else -> if (e.hole != null) issues += GrammarIssue(st.index, e, "${e.type} must not carry a hole", GrammarKind.HOLE_NOT_ALLOWED, st.before)
            }
        }
        return issues to if (state == State.ERR) State.OFF else state
    }

    /** labels.phase_segments: contiguous phase segments over the coverage (clipped to it), merged. */
    fun segments(labels: Labels): List<Segment> {
        val c0 = labels.c0
        val c1 = labels.c1
        val evs = grammarEvents(labels)
        val tLo = (listOf(c0) + evs.map { it.tUs }).min()
        val tHi = (listOf(c1) + evs.map { it.tUs + 1 }).max()
        val segs = ArrayList<Segment>()
        fun add(t0: Long, t1: Long, phase: Phase, hole: Int = 0) {
            if (t1 > t0) segs += Segment(t0, t1, phase, hole)
        }
        fun cut(at: Long) {
            while (segs.isNotEmpty() && segs.last().t0Us >= at) segs.removeAt(segs.size - 1)
            if (segs.isNotEmpty() && segs.last().t1Us > at) {
                val s = segs.removeAt(segs.size - 1)
                segs += Segment(s.t0Us, at, s.phase, s.hole)
            }
        }
        var prevT = tLo
        var errFrom = tLo
        var hole = 0
        var spinEnd = tLo
        var after = State.OFF
        add(tLo, if (evs.isNotEmpty()) evs[0].tUs else tHi, Phase.OFF)
        for (st in walk(evs)) {
            val e = st.event
            after = st.after
            val t = e.tUs
            val nxt = evs.getOrNull(st.index + 1)
            val tNext = nxt?.tUs ?: tHi
            // cutting ends breakthrough_pre before a breakthrough
            val bt = if (nxt != null && nxt.type == LabelTaxonomy.BREAKTHROUGH) maxOf(t, tNext - LabelTaxonomy.BREAKTHROUGH_PRE_US) else tNext
            if (!st.allowed) {
                if (st.before != State.ERR) {
                    cut(prevT)
                    errFrom = prevT
                }
                prevT = t
                if (e.type != LabelTaxonomy.MOTOR_OFF) continue
                add(errFrom, t, Phase.UNKNOWN)       // then the resynchronising motor_off as usual
            }
            prevT = t
            when {
                e.type == LabelTaxonomy.CONTACT -> {
                    hole = e.hole ?: 0
                    add(t, bt, Phase.DRILLING, hole)
                    add(bt, tNext, Phase.BREAKTHROUGH, hole)
                }
                e.type == LabelTaxonomy.MOTOR_ON -> {
                    val inHole = st.before == State.HOLE_OFF
                    val end = minOf(t + LabelTaxonomy.SPINUP_MAX_US, bt)
                    add(t, end, Phase.SPINUP, if (inHole) hole else 0)
                    if (inHole) {          // restart in the hole: cutting again after the spinup
                        add(end, bt, Phase.DRILLING, hole)
                        add(bt, tNext, Phase.BREAKTHROUGH, hole)
                    } else {
                        add(end, tNext, Phase.FREE_RUN)
                    }
                }
                e.type == LabelTaxonomy.BREAKTHROUGH -> {
                    val end = minOf(t + LabelTaxonomy.BREAKTHROUGH_POST_US, tNext)
                    add(t, end, Phase.BREAKTHROUGH, hole)
                    add(end, tNext, Phase.FREE_RUN)
                }
                e.type == LabelTaxonomy.RETRACT && st.before == State.HOLE -> add(t, tNext, Phase.FREE_RUN)
                else -> {       // motor_off, or retract with the motor off: the spindown runs on
                    val h = if (st.after == State.HOLE_OFF) hole else 0
                    if (e.type == LabelTaxonomy.MOTOR_OFF) spinEnd = t + LabelTaxonomy.SPINDOWN_MAX_US
                    add(t, minOf(spinEnd, tNext), Phase.SPINDOWN, h)
                    add(maxOf(t, spinEnd), tNext, Phase.OFF, h)
                }
            }
        }
        if (after == State.ERR) add(errFrom, tHi, Phase.UNKNOWN)    // the labels end inside an invalid stretch

        val out = ArrayList<Segment>()
        for (s in segs) {
            val a = maxOf(s.t0Us, c0)
            val b = minOf(s.t1Us, c1)
            if (b <= a) continue
            val last = out.lastOrNull()
            if (last != null && last.phase == s.phase && last.hole == s.hole && last.t1Us == a) {
                out[out.size - 1] = Segment(last.t0Us, b, s.phase, s.hole)
            } else {
                out += Segment(a, b, s.phase, s.hole)
            }
        }
        return out
    }

    /** The segment holding [tUs], or null (unknown: outside them). */
    fun at(segs: List<Segment>, tUs: Long): Segment? {
        var lo = 0
        var hi = segs.size
        while (lo < hi) {                  // the first segment starting after tUs
            val mid = (lo + hi) ushr 1
            if (segs[mid].t0Us <= tUs) lo = mid + 1 else hi = mid
        }
        val s = segs.getOrNull(lo - 1) ?: return null
        return if (tUs < s.t1Us) s else null
    }
}
