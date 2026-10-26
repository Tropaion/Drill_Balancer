package dev.drillbalancer.recorder.label

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The review's edit operations and the label check. */
class LabelEditsTest {
    private fun expected(name: String): Labels =
        Labels.fromJson(Json.parseToJsonElement(File(LabelTestData.fixture(name), "expected.json").readText()).jsonObject["events"]!!.jsonObject)

    private val names = LabelTestData.root.listFiles { f -> File(f, "expected.json").isFile }.orEmpty().map { it.name }.sorted()

    /** The detector's labels are in the normal form already: an untouched review changes nothing. */
    @Test
    fun detectorLabelsAreNormal() {
        for (n in names) {
            val l = expected(n)
            assertEquals(n, l, LabelEdits.normalize(l))
            assertEquals(n, listOf<String>(), LabelCheck.errors(l).map { it.message })
        }
    }

    private val drill get() = expected("fx_drill_through")      // motor_on, contact 1, breakthrough 1, motor_off
    private fun Labels.of(type: String) = events.first { it.type == type }

    @Test
    fun moveSnapsAndRemembersTheDetectorsTime() {
        val l = drill
        val c = l.of("contact")
        val moved = LabelEdits.move(l, c.id, c.tUs + 12_345)
        val m = moved.of("contact")
        assertEquals(0L, (m.tUs - l.c0) % LabelEdits.SNAP_US)
        // to the nearest frame, not the one before
        assertEquals(l.c0 + 30_000, LabelEdits.snap(l, l.c0 + 25_001))
        assertEquals(l.c0 + 20_000, LabelEdits.snap(l, l.c0 + 24_999))
        assertEquals(l.c0 + 20_000, LabelEdits.snap(l, l.c0 + 15_000))
        assertTrue(m.edited)
        assertEquals(c.tUs, m.autoTUs)
        // moved again: the detector's time stays the first one
        val again = LabelEdits.move(moved, c.id, m.tUs + 50_000).of("contact")
        assertEquals(c.tUs, again.autoTUs)
        // reset: back at the detector's time, not edited
        val back = LabelEdits.resetMarker(moved, c.id).of("contact")
        assertEquals(c, back)
        // clamped into the coverage
        assertEquals(l.c0, LabelEdits.move(l, l.of("motor_on").id, l.c0 - 9_000_000).of("motor_on").tUs)
        assertTrue(LabelEdits.move(l, l.of("motor_off").id, l.c1 + 9_000_000).of("motor_off").tUs < l.c1)
    }

    @Test
    fun aContactAddedBeforeRenumbersTheHoles() {
        val l = LabelEdits.setTag(drill, 1, "breakout_chipping", true)
        val on = l.of("motor_on")
        val first = l.of("contact")
        // a second hole before the first: contact + retract between motor_on and the old contact
        var (x, cId) = LabelEdits.add(l, "contact", on.tUs + 400_000)
        x = LabelEdits.add(x, "retract", on.tUs + 600_000).first
        assertTrue(first.tUs > on.tUs + 600_000)
        assertEquals(1, x.events.first { it.id == cId }.hole)
        assertEquals(2, x.events.first { it.id == first.id }.hole)
        assertEquals(listOf(1, 2), x.holes.map { it.hole })
        assertEquals(listOf("clean"), x.holes[0].outcomeTags)          // a new hole that ended
        assertEquals(false, x.holes[0].through)
        assertEquals(listOf("breakout_chipping"), x.holes[1].outcomeTags)   // the old hole's tags followed it
        assertEquals(true, x.holes[1].through)
        assertEquals(listOf<String>(), LabelCheck.errors(x).map { it.message })
        // deleted again: as before
        val back = LabelEdits.delete(LabelEdits.delete(x, cId), x.events.first { it.type == "retract" }.id)
        assertEquals(l, back)
    }

    @Test
    fun deletingTheBreakthroughLeavesTheHoleOpen() {
        val l = drill
        val x = LabelEdits.delete(l, l.of("breakthrough").id)
        assertNull(x.holes.single().through)
        assertEquals(listOf<String>(), x.holes.single().outcomeTags)
        assertEquals(listOf<String>(), LabelCheck.errors(x).map { it.message })
        assertEquals(listOf("W_OPEN_END"), LabelCheck.check(x).map { it.code })
    }

    @Test
    fun aGrammarErrorNamesTheLabelAndTheFix() {
        val l = drill
        val x = LabelEdits.delete(l, l.of("motor_on").id)
        val e = LabelCheck.errors(x)
        assertEquals(listOf("E_GRAMMAR"), e.map { it.code })
        assertEquals(l.of("contact").id, e.single().markerId)
        assertTrue(e.single().message, e.single().message.startsWith("Contact at "))
        assertTrue(e.single().message, e.single().message.contains("the motor is off there. Add Motor on before it"))
        // a breakthrough with the bit out of the hole
        val y = LabelEdits.add(l, "breakthrough", l.of("motor_off").tUs - 100_000).first
        assertTrue(LabelCheck.errors(y).single().message.contains("the bit is in no hole there"))
    }

    @Test
    fun cleanAndProblemTagsExcludeEachOther() {
        val l = drill
        assertEquals(listOf("clean"), l.holes.single().outcomeTags)
        val stuck = LabelEdits.setTag(l, 1, "bit_stuck", true)
        assertEquals(listOf("bit_stuck"), stuck.holes.single().outcomeTags)
        val both = LabelEdits.setTag(stuck, 1, "kickback", true)
        assertEquals(listOf("bit_stuck", "kickback"), both.holes.single().outcomeTags)
        assertEquals(listOf("clean"), LabelEdits.setTag(both, 1, "clean", true).holes.single().outcomeTags)
        assertEquals(listOf("kickback"), LabelEdits.setTag(both, 1, "bit_stuck", false).holes.single().outcomeTags)
    }

    @Test
    fun confirmOnlyTheUncertain() {
        val l = expected("fx_pulled_open")          // its restart retract has confidence 0.3
        val open = l.events.filter { LabelEdits.needsConfirm(it) }
        assertTrue(open.isNotEmpty())
        val one = LabelEdits.confirm(l, open.first().id)
        assertTrue(one.events.first { it.id == open.first().id }.confirmed)
        assertEquals(open.size - 1, one.events.count { LabelEdits.needsConfirm(it) })
        val all = LabelEdits.confirmAll(l)
        assertEquals(0, all.events.count { LabelEdits.needsConfirm(it) })
        assertEquals(l.events.count { it.confidence >= LabelEdits.LOW_CONFIDENCE }, all.events.count { !it.confirmed })
        // an edited marker needs no confirmation
        assertFalse(LabelEdits.needsConfirm(LabelEdits.move(l, open.first().id, open.first().tUs + 30_000).events.first { it.id == open.first().id }))
    }

    @Test
    fun suggestionsGoAndComeBack() {
        val l = expected("fx_suggestions")
        val s = l.spans.first { it.type == "wobble" }
        val off = LabelEdits.setSpan(l, s, false)
        assertTrue(off.spans.none { it.id == s.id })
        val on = LabelEdits.setSpan(off, s, true)
        assertEquals(s.copy(confirmed = true), on.spans.first { it.id == s.id })
        assertEquals(LabelEdits.editedCount(off, l), 1)
        assertEquals(LabelEdits.editedCount(on, l), 0)
    }

    @Test
    fun savedOrder() {
        val l = drill
        val x = LabelEdits.add(l, "kickback", l.of("contact").tUs + 100_000).first
        val s = LabelEdits.sorted(x, 7)
        assertEquals(7, s.revision)
        assertEquals(s.events.sortedBy { it.tUs }, s.events)
        assertEquals(1, s.events.first { it.type == "kickback" }.hole)
    }
}
