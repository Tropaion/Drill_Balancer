package dev.drillbalancer.recorder.label

import dev.drillbalancer.protocol.FirmwareSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phases, the grammar state and the derived labels against dbdata.labels itself:
 * firmware/tools/dataset/fixtures/labels/phases.json (make_fixtures.py there) holds the event
 * orders of test_labels.py, the autodetect fixtures' labels and seeded random sequences, valid
 * and not, each with a LEVEL table, and what dbdata.labels computes for them. The port must give
 * the same segments, issues (index, id, message), end state and derived labels, exactly.
 */
class PhasesCrossCheckTest {
    private val cases: List<JsonObject> by lazy {
        Json.parseToJsonElement(FirmwareSources.text("tools/dataset/fixtures/labels/phases.json")).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun everyCase() {
        assertTrue("cases: ${cases.size}", cases.size > 250)
        var issues = 0
        var unknown = 0
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val labels = Labels.fromJson(c["events"]!!.jsonObject)
            val want = c["expected"]!!.jsonObject

            val segs = Phases.segments(labels).map { listOf(it.t0Us.toString(), it.t1Us.toString(), it.phase.id, it.hole.toString()) }
            val wantSegs = want["segments"]!!.jsonArray.map { s -> s.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals("$name segments", wantSegs, segs)
            if (segs.any { it[2] == "unknown" }) unknown++

            val (gi, end) = Phases.grammarState(labels)
            val wantIssues = want["issues"]!!.jsonArray.map { i -> i.jsonArray.let { Triple(it[0].jsonPrimitive.int, it[1].jsonPrimitive.content, it[2].jsonPrimitive.content) } }
            assertEquals("$name issues", wantIssues, gi.map { Triple(it.index, it.event.id, it.message) })
            assertEquals("$name end", want["end"]!!.jsonPrimitive.content, end.name.lowercase())
            issues += gi.size

            val level = (c["level"] as? JsonObject)?.let(::level)
            val tol = ((c["meta"] as? JsonObject)?.get("level") as? JsonObject)?.get("tol_deg")?.jsonPrimitive?.doubleOrNull ?: Double.POSITIVE_INFINITY
            val (derived, holes) = Derived.compute(level, labels, tol)
            assertEquals("$name derived", Derived.fromJson(want["derived"]), derived)
            val wantHoles = want["holes"]!!.jsonObject
            assertEquals("$name holes", wantHoles.keys.map { it.toInt() }.sorted(), holes.keys.sorted())
            for ((h, v) in wantHoles) {
                val o = v.jsonObject
                val got = holes.getValue(h.toInt())
                assertEquals("$name hole $h orientation", o["orientation"]!!.jsonPrimitive.content, got.orientation)
                assertEquals("$name hole $h off angle", (o["off_angle_deg"] as? JsonNull)?.let { null } ?: o["off_angle_deg"]!!.jsonPrimitive.doubleOrNull, got.offAngleDeg)
            }
        }
        assertTrue("the cases hold grammar issues ($issues) and unknown stretches ($unknown)", issues > 50 && unknown > 30)
    }

    private fun level(o: JsonObject): LevelRows {
        val cols = o["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        val rows = (o["rows"] as JsonArray).map { r -> r.jsonArray.map { it.jsonPrimitive.long } }
        fun col(n: String): List<Long> = cols.indexOf(n).let { i -> rows.map { it[i] } }
        fun ints(n: String) = col(n).map { it.toInt() }.toIntArray()
        return LevelRows(col("t_us").toLongArray(), ints("flags"), ints("elevation_cdeg"), ints("yaw_cdeg"), ints("deviation_cdeg"), ints("zero_elevation_cdeg"))
    }
}
