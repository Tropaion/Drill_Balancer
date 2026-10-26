package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.Taxonomy
import dev.drillbalancer.recorder.export.IntList
import dev.drillbalancer.recorder.export.pyRound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * events.json (schema drillbalancer.events/1) as values the review edits: point events
 * (markers), spans and holes. toJson writes the keys in the order dbdata writes them
 * (contract.events_document, autodetect.events_doc), so an untouched detection is the same text
 * as the Python detector's.
 */

/** Who made a label. */
enum class Source(val id: String) {
    AUTO("auto"),
    USER("user");

    companion object {
        fun of(id: String?): Source = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** A point event. [autoTUs]: the detector's time before a person moved it. */
data class Marker(
    val id: String,
    val type: String,
    val tUs: Long,
    val hole: Int? = null,
    val source: Source = Source.USER,
    val confidence: Double = 1.0,
    val edited: Boolean = false,
    val confirmed: Boolean = false,
    val autoTUs: Long? = null,
) {
    /** A label a person made or changed (labels.touched_by_person). */
    val touched: Boolean get() = source == Source.USER || edited || confirmed

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("t_us", tUs)
        put("source", source.id)
        put("confidence", confidence)
        hole?.let { put("hole", it) }
        if (edited) put("edited", true)
        if (confirmed) put("confirmed", true)
        autoTUs?.let { put("auto_t_us", it) }
    }
}

/** A span event [t0Us, t1Us). */
data class SpanLabel(
    val id: String,
    val type: String,
    val t0Us: Long,
    val t1Us: Long,
    val hole: Int? = null,
    val source: Source = Source.USER,
    val confidence: Double = 1.0,
    val reason: String? = null,
    val edited: Boolean = false,
    val confirmed: Boolean = false,
    val autoT0Us: Long? = null,
    val autoT1Us: Long? = null,
) {
    val touched: Boolean get() = source == Source.USER || edited || confirmed

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("t0_us", t0Us)
        put("t1_us", t1Us)
        put("source", source.id)
        put("confidence", confidence)
        reason?.let { put("reason", it) }
        hole?.let { put("hole", it) }
        if (edited) put("edited", true)
        if (confirmed) put("confirmed", true)
        autoT0Us?.let { put("auto_t0_us", it) }
        autoT1Us?.let { put("auto_t1_us", it) }
    }
}

/** One hole of holes[]: through (null: the labels end in it), material (null: the session's). */
data class HoleLabel(
    val hole: Int,
    val through: Boolean?,
    val material: String? = null,
    val outcomeTags: List<String> = emptyList(),
    val offAngleDeg: Double? = null,
    val offAngleSource: String = "computed",
    val orientation: String? = null,
    val otherText: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("hole", hole)
        put("through", through?.let { JsonPrimitive(it) } ?: JsonNull)
        put("material", material?.let { JsonPrimitive(it) } ?: JsonNull)
        otherText?.let { put("other_text", it) }
        putJsonArray("outcome_tags") { outcomeTags.forEach { add(JsonPrimitive(it)) } }
        put("off_angle_deg", offAngleDeg?.let { JsonPrimitive(it) } ?: JsonNull)
        put("off_angle_source", offAngleSource)
        orientation?.let { put("orientation", it) }
    }

    companion object {
        /** contract.hole_entry: tags, or clean for a hole that ended without a problem, none while open. */
        fun entry(hole: Int, through: Boolean?, tags: List<String>): HoleLabel =
            HoleLabel(hole, through, null, tags.ifEmpty { if (through != null) listOf("clean") else emptyList() })
    }
}

/** events.json detector: the version and the sha256 of its parameters. */
data class DetectorInfo(val version: String, val paramsSha256: String? = null)

/** A whole events.json. */
data class Labels(
    val recordingId: String,
    val revision: Int,
    val coverage: List<Long>,
    val detector: DetectorInfo? = null,
    val events: List<Marker> = emptyList(),
    val spans: List<SpanLabel> = emptyList(),
    val holes: List<HoleLabel> = emptyList(),
    val taxonomyVersion: Int = Taxonomy.VERSION,
) {
    val c0: Long get() = coverage[0]
    val c1: Long get() = coverage[1]

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("taxonomy_version", taxonomyVersion)
        put("recording_id", recordingId)
        put("revision", revision)
        putJsonArray("coverage_us") { coverage.forEach { add(JsonPrimitive(it)) } }
        detector?.let { d ->
            putJsonObject("detector") {
                put("version", d.version)
                d.paramsSha256?.let { put("params_sha256", it) }
            }
        }
        putJsonArray("events") { events.forEach { add(it.toJson()) } }
        putJsonArray("spans") { spans.forEach { add(it.toJson()) } }
        putJsonArray("holes") { holes.forEach { add(it.toJson()) } }
    }

    companion object {
        const val SCHEMA = "drillbalancer.events/1"

        /** An events.json; IllegalArgumentException when it is not one. */
        fun fromJson(o: JsonObject): Labels {
            require((o["schema"] as? JsonPrimitive)?.content == SCHEMA) { "not a $SCHEMA document" }
            val cov = o.getValue("coverage_us").jsonArray.map { it.jsonPrimitive.longOrNull ?: error("coverage_us") }
            require(cov.size == 2) { "coverage_us needs two values" }
            val det = (o["detector"] as? JsonObject)?.let { DetectorInfo(it.str("version") ?: "", it.str("params_sha256")) }
            return Labels(
                recordingId = o.str("recording_id") ?: "",
                revision = o.int("revision") ?: 0,
                coverage = cov,
                detector = det,
                events = (o["events"] as? JsonArray).orEmpty().map { it.jsonObject }.map { e ->
                    Marker(
                        id = e.str("id") ?: "", type = e.str("type") ?: "", tUs = e.long("t_us") ?: 0, hole = e.int("hole"),
                        source = Source.of(e.str("source")), confidence = e.double("confidence") ?: 1.0,
                        edited = e.bool("edited"), confirmed = e.bool("confirmed"), autoTUs = e.long("auto_t_us"),
                    )
                },
                spans = (o["spans"] as? JsonArray).orEmpty().map { it.jsonObject }.map { s ->
                    SpanLabel(
                        id = s.str("id") ?: "", type = s.str("type") ?: "", t0Us = s.long("t0_us") ?: 0, t1Us = s.long("t1_us") ?: 0,
                        hole = s.int("hole"), source = Source.of(s.str("source")), confidence = s.double("confidence") ?: 1.0,
                        reason = s.str("reason"), edited = s.bool("edited"), confirmed = s.bool("confirmed"),
                        autoT0Us = s.long("auto_t0_us"), autoT1Us = s.long("auto_t1_us"),
                    )
                },
                holes = (o["holes"] as? JsonArray).orEmpty().map { it.jsonObject }.map { h ->
                    HoleLabel(
                        hole = h.int("hole") ?: 0,
                        through = (h["through"] as? JsonPrimitive)?.booleanOrNull,
                        material = h.str("material"),
                        outcomeTags = (h["outcome_tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                        offAngleDeg = h.double("off_angle_deg"),
                        offAngleSource = h.str("off_angle_source") ?: "computed",
                        orientation = h.str("orientation"),
                        otherText = h["other_text"] as? JsonObject,
                    )
                },
                taxonomyVersion = o.int("taxonomy_version") ?: Taxonomy.VERSION,
            )
        }

        private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        private fun JsonObject.str(k: String): String? = prim(k)?.takeIf { it.isString }?.content
        private fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull
        private fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull
        private fun JsonObject.double(k: String): Double? = prim(k)?.doubleOrNull
        private fun JsonObject.bool(k: String): Boolean = prim(k)?.booleanOrNull == true
    }
}

/** meta.derived: orientation and off angle from LEVEL during drilling, and the off_angle tag. */
data class Derived(val orientation: String, val offAngleDeg: Double?, val tags: List<String>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("orientation", orientation)
        put("off_angle_deg", offAngleDeg?.let { JsonPrimitive(it) } ?: JsonNull)
        putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
    }

    companion object {
        val EMPTY = Derived(Taxonomy.UNKNOWN, null, emptyList())

        fun fromJson(e: JsonElement?): Derived? {
            val o = e as? JsonObject ?: return null
            return Derived(
                (o["orientation"] as? JsonPrimitive)?.content ?: Taxonomy.UNKNOWN,
                (o["off_angle_deg"] as? JsonPrimitive)?.doubleOrNull,
                (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }

        /** Per hole: orientation and off angle over its drilling. */
        class HoleDerived(val orientation: String, val offAngleDeg: Double?)

        /**
         * labels.compute_derived: LEVEL rows with IMU_OK during the drilling phase; orientation from
         * the median absolute elevation, the off angle the median |deviation| rounded to 0.01 deg;
         * the off_angle tag above [tolDeg]. Also per hole. One pass over the rows (the review
         * recomputes it on every edit of a long take).
         */
        fun compute(level: LevelRows?, labels: Labels, tolDeg: Double): Pair<Derived, Map<Int, HoleDerived>> {
            val segs = Phases.segments(labels)
            // the drilling rows with IMU_OK, in order: all of them, and per hole
            val all = IntList()
            val perHole = HashMap<Int, IntList>()
            if (level != null) {
                for (r in 0 until level.size) {
                    if (!level.imuOk(r)) continue
                    val seg = Phases.at(segs, level.tUs[r]) ?: continue
                    if (seg.phase != Phase.DRILLING) continue
                    all.add(r)
                    perHole.getOrPut(seg.hole) { IntList() }.add(r)
                }
            }

            fun summary(rows: IntList?): HoleDerived {
                if (level == null || rows == null || rows.size == 0) return HoleDerived(Taxonomy.UNKNOWN, null)
                val e = npMedian(DoubleArray(rows.size) { level.absoluteElevationDeg(rows[it]) })
                val d = npMedian(DoubleArray(rows.size) { kotlin.math.abs(level.deviationCdeg[rows[it]].toDouble()) / 100.0 })
                return HoleDerived(orientationOf(e), pyRound(d, 2))
            }

            val s = summary(all)
            val holes = LinkedHashMap<Int, HoleDerived>()
            for (h in labels.events.mapNotNull { it.hole }.filter { it != 0 }.toSortedSet()) holes[h] = summary(perHole[h])
            val tags = if (s.offAngleDeg != null && s.offAngleDeg > tolDeg) listOf("off_angle") else emptyList()
            return Derived(s.orientation, s.offAngleDeg, tags) to holes
        }

        /** labels.orientation_of (taxonomy derived_rules.orientation_limits_deg). */
        fun orientationOf(elevationDeg: Double?): String = when {
            elevationDeg == null || !elevationDeg.isFinite() -> Taxonomy.UNKNOWN
            kotlin.math.abs(elevationDeg) < LabelTaxonomy.ORIENTATION_WALL_DEG -> "wall"
            elevationDeg < -LabelTaxonomy.ORIENTATION_VERTICAL_DEG -> "floor"
            elevationDeg > LabelTaxonomy.ORIENTATION_VERTICAL_DEG -> "ceiling"
            else -> "angled"
        }
    }
}
