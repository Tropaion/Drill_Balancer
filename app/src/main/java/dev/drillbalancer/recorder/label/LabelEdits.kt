package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.label.LabelTaxonomy.BREAKTHROUGH
import dev.drillbalancer.recorder.label.LabelTaxonomy.CONTACT
import dev.drillbalancer.recorder.label.LabelTaxonomy.KICKBACK
import dev.drillbalancer.recorder.label.LabelTaxonomy.RETRACT
import java.util.Locale

/**
 * The review's edits as pure functions over [Labels]: each returns the new labels in their
 * normal form ([normalize]: holes numbered from 1 by contact, breakthrough / retract in the
 * hole of the contact before them, kickbacks in the hole the bit is in or just left, holes[]
 * rebuilt with what the person set on them kept).
 */
object LabelEdits {
    /** Moved and added markers land on the 10 ms frame grid from the coverage start. */
    const val SNAP_US = 10_000L

    /** autodetect_params kickback.hole_after_ms in us (LabelContractTest compares them). */
    const val HOLE_AFTER_US = 250_000L

    /** Below this an auto label needs a tap to confirm (dashed amber in the review). */
    const val LOW_CONFIDENCE = 0.6

    /** Tags that say the hole had a problem: "clean" excludes them, and they exclude "clean". */
    val PROBLEM_TAGS: Set<String> = LabelTaxonomy.OUTCOME_TAGS.toSet() - "clean" - LabelTaxonomy.DERIVED_TAGS

    /** Span types the review lists as suggestions (chips, an auto badge when the detector made them). */
    val SUGGESTION_SPANS = listOf("stuck", "wobble", "wander", "ignore")

    fun needsConfirm(m: Marker): Boolean = m.source == Source.AUTO && m.confidence < LOW_CONFIDENCE && !m.confirmed && !m.edited

    /** [t] on the frame grid inside the coverage. */
    fun snap(l: Labels, t: Long): Long {
        val last = l.c0 + Math.floorDiv(l.c1 - 1 - l.c0, SNAP_US) * SNAP_US
        val k = Math.floorDiv(t - l.c0 + SNAP_US / 2, SNAP_US)
        return (l.c0 + k * SNAP_US).coerceIn(l.c0, maxOf(l.c0, last))
    }

    fun move(l: Labels, id: String, t: Long): Labels {
        val m = l.events.firstOrNull { it.id == id } ?: return l
        val nt = snap(l, t)
        if (nt == m.tUs) return l
        val moved = if (m.source == Source.AUTO) {
            val auto = m.autoTUs ?: m.tUs
            m.copy(tUs = nt, edited = nt != auto, autoTUs = auto.takeIf { nt != auto })
        } else {
            m.copy(tUs = nt)
        }
        return normalize(l.copy(events = l.events.map { if (it.id == id) moved else it }), l)
    }

    /** A new marker of [type] at [t] (snapped); returns the labels and its id. */
    fun add(l: Labels, type: String, t: Long): Pair<Labels, String> {
        val id = nextId(l, "e")
        val m = Marker(id, type, snap(l, t), source = Source.USER, confidence = 1.0)
        return normalize(l.copy(events = l.events + m), l) to id
    }

    fun delete(l: Labels, id: String): Labels =
        if (l.events.none { it.id == id }) l else normalize(l.copy(events = l.events.filter { it.id != id }), l)

    /** The marker back at the detector's time (a moved auto marker); unchanged otherwise. */
    fun resetMarker(l: Labels, id: String): Labels {
        val m = l.events.firstOrNull { it.id == id } ?: return l
        val auto = m.autoTUs ?: return l
        val back = m.copy(tUs = auto, edited = false, autoTUs = null)
        return normalize(l.copy(events = l.events.map { if (it.id == id) back else it }), l)
    }

    fun confirm(l: Labels, id: String): Labels = l.copy(events = l.events.map { if (it.id == id && needsConfirm(it)) it.copy(confirmed = true) else it })

    fun confirmAll(l: Labels): Labels = l.copy(events = l.events.map { if (needsConfirm(it)) it.copy(confirmed = true) else it })

    /** An outcome tag of [hole] on or off; "clean" and the problem tags exclude each other. */
    fun setTag(l: Labels, hole: Int, tag: String, on: Boolean): Labels = l.copy(
        holes = l.holes.map { h ->
            if (h.hole != hole) return@map h
            val tags = h.outcomeTags.toMutableList()
            if (on) {
                if (tag !in tags) tags += tag
                if (tag == "clean") tags.removeAll(PROBLEM_TAGS) else if (tag in PROBLEM_TAGS) tags.remove("clean")
            } else {
                tags.remove(tag)
            }
            h.copy(outcomeTags = LabelTaxonomy.OUTCOME_TAGS.filter { it in tags } + tags.filter { it !in LabelTaxonomy.OUTCOME_TAGS })
        },
    )

    /** A suggested span off (removed), or on again: [span] as the detector had it, confirmed by the person. */
    fun setSpan(l: Labels, span: SpanLabel, on: Boolean): Labels {
        val present = l.spans.any { it.id == span.id }
        return when {
            on && !present -> normalize(l.copy(spans = l.spans + span.copy(confirmed = span.source == Source.AUTO)), l)
            !on && present -> normalize(l.copy(spans = l.spans.filter { it.id != span.id }), l)
            else -> l
        }
    }

    /** As saved: events by time (then type), spans by start (then type), the revision given. */
    fun sorted(l: Labels, revision: Int): Labels = l.copy(
        revision = revision,
        events = l.events.sortedWith(compareBy<Marker> { it.tUs }.thenBy { it.type }),
        spans = l.spans.sortedWith(compareBy<SpanLabel> { it.t0Us }.thenBy { it.type }),
    )

    /** How many labels the person changed: moved or added, plus the detector's that were removed ([auto]). */
    fun editedCount(l: Labels, auto: Labels?): Int {
        val changed = l.events.count { it.edited || it.source == Source.USER } + l.spans.count { it.edited || it.source == Source.USER }
        val ids = (l.events.map { it.id } + l.spans.map { it.id }).toSet()
        val removed = auto?.let { a -> a.events.count { it.id !in ids } + a.spans.count { it.id !in ids } } ?: 0
        return changed + removed
    }

    /**
     * The normal form: holes renumbered 1.. in contact order; each breakthrough / retract in the
     * hole of the contact before it; kickbacks in the hole the bit is in or left at most
     * kickback.hole_after_ms (250 ms) before; problem spans follow their hole's renumbering (or
     * the hole at their start); holes[] rebuilt for the holes in use, through from the event that
     * closes each, the tags and material of [prev]'s entry kept ("clean" dropped while a hole is
     * open, given to one that newly ended without tags).
     */
    fun normalize(l: Labels, prev: Labels = l, holeAfterUs: Long = HOLE_AFTER_US): Labels {
        val order = Phases.grammarEvents(l)
        val newHole = HashMap<String, Int?>()
        val oldOf = HashMap<Int, Int?>()          // new hole -> the old number of its contact
        val closer = HashMap<Int, String>()       // new hole -> the type of the event that closed it
        var n = 0
        var cur: Int? = null
        var last: Int? = null
        for (e in order) {
            when (e.type) {
                CONTACT -> {
                    n++
                    newHole[e.id] = n
                    oldOf[n] = e.hole
                    cur = n
                    last = n
                }
                BREAKTHROUGH, RETRACT -> {
                    val h = cur ?: last
                    newHole[e.id] = h
                    if (cur != null) closer[cur] = e.type
                    cur = null
                }
                else -> newHole[e.id] = null
            }
        }
        val grammar = l.events.map { e -> if (e.type in LabelTaxonomy.GRAMMAR_EVENTS) e.copy(hole = newHole[e.id]) else e }
        val newOfOld = HashMap<Int, Int>()
        for ((h, o) in oldOf.entries.sortedBy { it.key }) if (o != null) newOfOld.putIfAbsent(o, h)
        val placed = Phases.grammarEvents(l.copy(events = grammar))
        fun holeAt(t: Long): Int? {
            var h: Int? = null
            var left: Long? = null
            for (e in placed) {
                if (e.tUs > t) break
                if (e.type == CONTACT) {
                    h = e.hole
                    left = null
                } else if ((e.type == BREAKTHROUGH || e.type == RETRACT) && h != null) {
                    left = e.tUs
                }
            }
            if (h == null || (left != null && t - left > holeAfterUs)) return null
            return h
        }
        val events = grammar.map { e -> if (e.type == KICKBACK) e.copy(hole = holeAt(e.tUs)) else e }
        val spans = l.spans.map { s ->
            if (s.type !in LabelTaxonomy.PROBLEM_SPANS) s else s.copy(hole = s.hole?.let { newOfOld[it] } ?: holeAt(s.t0Us))
        }
        val used = (events.mapNotNull { it.hole } + spans.mapNotNull { it.hole }).toSortedSet()
        val before = prev.holes.associateBy { it.hole }
        val holes = used.map { h ->
            val through = when (closer[h]) {
                BREAKTHROUGH -> true
                RETRACT -> false
                else -> null
            }
            val old = oldOf[h]?.let { before[it] }
            if (old == null) {
                HoleLabel.entry(h, through, emptyList())
            } else {
                var tags = old.outcomeTags
                if (through == null) tags = tags - "clean"
                if (old.through == null && through != null && tags.isEmpty()) tags = listOf("clean")
                old.copy(hole = h, through = through, outcomeTags = tags)
            }
        }
        return l.copy(events = events, spans = spans, holes = holes)
    }

    private fun nextId(l: Labels, prefix: String): String {
        val re = Regex("^$prefix(\\d+)$")
        val max = (l.events.map { it.id } + l.spans.map { it.id }).mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "$prefix${max + 1}"
    }

    /** "3.42 s" from the coverage start. */
    fun formatTime(l: Labels, t: Long): String = formatTime(l.c0, t)

    /** "3.42 s" from [c0Us]. */
    fun formatTime(c0Us: Long, t: Long): String = String.format(Locale.ROOT, "%.2f s", (t - c0Us) / 1e6)
}

/** A problem the label check found; [markerId] names the label to show. */
data class LabelIssue(val code: String, val error: Boolean, val message: String, val markerId: String? = null)

/**
 * The event checks of dbdata.validate (_check_events) on the review's labels, with messages for
 * the person: an error blocks Save (the validator would mark the recording invalid), a warning
 * does not (W_OPEN_END: a take that ends with the motor on or the bit in a hole is kept so).
 */
object LabelCheck {
    fun check(l: Labels): List<LabelIssue> {
        val out = ArrayList<LabelIssue>()
        if (l.c1 <= l.c0) out += LabelIssue("E_COVERAGE", true, "The recording has no time range.")
        val ids = l.events.map { it.id } + l.spans.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.sorted().forEach { out += LabelIssue("E_EVENT_ID", true, "Two labels share the id $it.") }
        for (e in l.events) {
            if (e.type !in LabelTaxonomy.POINT_EVENTS) out += LabelIssue("E_TAXONOMY", true, "${e.id}: ${e.type} is no label type.", e.id)
            if (e.tUs !in l.c0 until l.c1) out += LabelIssue("E_EVENT_RANGE", true, "${name(e.type)} at ${LabelEdits.formatTime(l, e.tUs)} is outside the recording.", e.id)
            if (e.type in LabelTaxonomy.HOLE_EVENTS && e.hole == null) {
                out += LabelIssue("E_HOLE", true, "${name(e.type)} at ${LabelEdits.formatTime(l, e.tUs)} has no hole: add a Contact before it.", e.id)
            }
        }
        for (s in l.spans) {
            if (s.type !in LabelTaxonomy.SPAN_EVENTS) out += LabelIssue("E_TAXONOMY", true, "${s.id}: ${s.type} is no span type.")
            if (s.t1Us <= s.t0Us) out += LabelIssue("E_SPAN", true, "${name(s.type)} ends before it starts.")
            if (s.t0Us < l.c0 || s.t1Us > l.c1) out += LabelIssue("E_EVENT_RANGE", true, "${name(s.type)} reaches outside the recording.")
            if (s.type == "ignore" && s.reason !in LabelTaxonomy.IGNORE_REASONS) out += LabelIssue("E_SPAN", true, "An ignored stretch needs a reason.")
            if (s.type != "ignore" && s.reason != null) out += LabelIssue("E_SPAN", true, "Only ignored stretches carry a reason.")
        }
        val (issues, end) = Phases.grammarState(l)
        for (g in issues) out += LabelIssue("E_GRAMMAR", true, grammarText(l, g), g.event.id)
        if (end != Phases.State.OFF) {
            out += LabelIssue(
                "W_OPEN_END", false,
                if (end == Phases.State.ON) "The labels end with the motor running (no Motor off)." else "The labels end with the bit in a hole.",
            )
        }
        val segs = Phases.segments(l)
        for (e in l.events) {
            if (e.type == KICKBACK && Phases.at(segs, e.tUs)?.phase.let { it == null || it == Phase.OFF || it == Phase.UNKNOWN }) {
                out += LabelIssue("W_KICKBACK_OFF", false, "Kickback at ${LabelEdits.formatTime(l, e.tUs)} while the motor is off.", e.id)
            }
        }
        val used = l.events.mapNotNull { it.hole }.toSet()
        val listed = l.holes.map { it.hole }
        if (listed.size != listed.toSet().size) out += LabelIssue("E_HOLE", true, "A hole is listed twice.")
        for (h in (used - listed.toSet()).sorted()) out += LabelIssue("E_HOLE", true, "Hole $h has no entry.")
        for (h in l.holes) for (t in h.outcomeTags) if (t !in LabelTaxonomy.OUTCOME_TAGS) out += LabelIssue("E_TAXONOMY", true, "Hole ${h.hole}: $t is no outcome tag.")
        return out
    }

    fun errors(l: Labels): List<LabelIssue> = check(l).filter { it.error }

    /** What is wrong with the order, and what fixes it. */
    private fun grammarText(l: Labels, g: Phases.GrammarIssue): String {
        val e = g.event
        val at = "${name(e.type)} at ${LabelEdits.formatTime(l, e.tUs)}"
        return when (g.kind) {
            Phases.GrammarKind.CONTACT_NO_HOLE -> "$at has no hole."
            Phases.GrammarKind.WRONG_HOLE -> "$at is in another hole than its Contact."
            Phases.GrammarKind.HOLE_NOT_ALLOWED -> "$at must not belong to a hole."
            Phases.GrammarKind.ORDER -> when (g.before) {
                Phases.State.OFF -> "$at: the motor is off there. Add Motor on before it, or move it."
                Phases.State.ON -> if (e.type == LabelTaxonomy.MOTOR_ON) {
                    "$at: the motor is already on. Add Motor off before it, or delete it."
                } else {
                    "$at: the bit is in no hole there. Add Contact before it, or move it."
                }
                Phases.State.HOLE -> "$at: the bit is still in the hole. Add Breakthrough or Retract before it, or move it."
                Phases.State.HOLE_OFF -> "$at: the motor stopped with the bit in the hole. Add Motor on or Retract before it."
                Phases.State.ERR -> "$at is not allowed there."
            }
        }
    }

    /** A label type for people: "Motor on", "Breakthrough". */
    fun name(type: String): String = when (type) {
        "motor_on" -> "Motor on"
        "motor_off" -> "Motor off"
        "contact" -> "Contact"
        "breakthrough" -> "Breakthrough"
        "retract" -> "Retract"
        "kickback" -> "Kickback"
        "stuck" -> "Stuck"
        "wobble" -> "Wobble"
        "wander" -> "Wander"
        "ignore" -> "Ignored"
        "handling" -> "Handling"
        else -> type
    }
}
