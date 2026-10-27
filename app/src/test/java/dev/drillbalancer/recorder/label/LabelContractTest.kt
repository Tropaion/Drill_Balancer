package dev.drillbalancer.recorder.label

import dev.drillbalancer.protocol.FirmwareSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** The detector's parameters and the label ids against the dataset contract (dbdata/contract). */
class LabelContractTest {
    private val contractParams = File(FirmwareSources.dir, "tools/dataset/dbdata/contract/autodetect_params.json")

    /** The asset the app ships is a byte copy of the contract's file, so params_sha256 is the Python one. */
    @Test
    fun paramsAssetIsTheContractFile() {
        assertArrayEquals(contractParams.readBytes(), TestParams.file.readBytes())
        assertEquals(DetectParams.sha256(contractParams.readBytes()), TestParams.loaded.sha256)
        // the hash dbdata wrote into the golden fixtures
        val expected = Json.parseToJsonElement(File(FirmwareSources.dir, "tools/dataset/fixtures/autodetect/fx_drill_through/expected.json").readText()).jsonObject
        val det = expected["detector"]!!.jsonObject
        assertEquals(det["params_sha256"]!!.jsonPrimitive.content, TestParams.loaded.sha256)
        assertEquals(det["version"]!!.jsonPrimitive.content, TestParams.loaded.p.detector)
        assertEquals("dbauto/1.2.0", TestParams.loaded.p.detector)
        // the review renumbers kickbacks with the detector's hole_after_ms
        assertEquals(TestParams.loaded.p.kickback.holeAfterMs * 1000, LabelEdits.HOLE_AFTER_US.toDouble(), 0.0)
    }

    @Test
    fun sha256NormalisesCrlf() {
        val lf = "{\n  \"a\": 1\n}\n".toByteArray()
        val crlf = "{\r\n  \"a\": 1\r\n}\r\n".toByteArray()
        assertEquals(DetectParams.sha256(lf), DetectParams.sha256(crlf))
    }

    /** Every key of the file has its field: an unknown one fails to load instead of being skipped. */
    @Test
    fun unknownParameterFailsToLoad() {
        val text = contractParams.readText().replaceFirst("\"hp_hz\": 20.0,", "\"hp_hz\": 20.0, \"new_key\": 1,")
        assertThrows(Exception::class.java) { DetectParams.load(text.toByteArray()) }
        val wrong = contractParams.readText().replaceFirst("dbauto.params/1", "dbauto.params/2")
        assertThrows(IllegalArgumentException::class.java) { DetectParams.load(wrong.toByteArray()) }
    }

    @Test
    fun labelIdsAreTheTaxonomys() {
        val tax = Json.parseToJsonElement(FirmwareSources.text("tools/dataset/dbdata/contract/taxonomy.v1.json")).jsonObject
        val points = tax["point_events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(points.map { it.id }, LabelTaxonomy.POINT_EVENTS)
        assertEquals(points.filter { (it["grammar"]?.jsonPrimitive?.boolean ?: true) }.map { it.id }.toSet(), LabelTaxonomy.GRAMMAR_EVENTS)
        assertEquals(points.filter { it["hole"]?.jsonPrimitive?.boolean == true }.map { it.id }.toSet(), LabelTaxonomy.HOLE_EVENTS)
        val spans = tax["span_events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(spans.map { it.id }, LabelTaxonomy.SPAN_EVENTS)
        assertEquals(spans.filter { it["group"]?.jsonPrimitive?.content == "problem" }.map { it.id }, LabelTaxonomy.PROBLEM_SPANS)
        assertEquals(spans.first { it.id == "ignore" }["reasons"]!!.jsonArray.map { it.jsonPrimitive.content }, LabelTaxonomy.IGNORE_REASONS)
        val fields = tax["fields"]!!.jsonObject
        val tags = fields["outcome_tag"]!!.jsonArray.map { it.jsonObject }
        assertEquals(tags.map { it.id }, LabelTaxonomy.OUTCOME_TAGS)
        assertEquals(tags.filter { it["derived"]?.jsonPrimitive?.boolean == true }.map { it.id }.toSet(), LabelTaxonomy.DERIVED_TAGS)
        assertEquals(tax["phases"]!!.jsonArray.map { it.jsonObject.id }, Phase.entries.map { it.id })
        val pp = tax["phase_params"]!!.jsonObject
        val w = pp["windows_ms"]!!.jsonObject
        assertEquals(w["spinup_max"]!!.jsonPrimitive.double * 1000, LabelTaxonomy.SPINUP_MAX_US.toDouble(), 0.0)
        assertEquals(w["breakthrough_pre"]!!.jsonPrimitive.double * 1000, LabelTaxonomy.BREAKTHROUGH_PRE_US.toDouble(), 0.0)
        assertEquals(w["breakthrough_post"]!!.jsonPrimitive.double * 1000, LabelTaxonomy.BREAKTHROUGH_POST_US.toDouble(), 0.0)
        assertEquals(w["spindown_max"]!!.jsonPrimitive.double * 1000, LabelTaxonomy.SPINDOWN_MAX_US.toDouble(), 0.0)
        assertEquals(pp["guard_ms"]!!.jsonPrimitive.double, LabelTaxonomy.GUARD_MS.toDouble(), 0.0)
        val lim = tax["derived_rules"]!!.jsonObject["orientation_limits_deg"]!!.jsonObject
        assertEquals(lim["wall"]!!.jsonPrimitive.double, LabelTaxonomy.ORIENTATION_WALL_DEG, 0.0)
        assertEquals(lim["vertical"]!!.jsonPrimitive.double, LabelTaxonomy.ORIENTATION_VERTICAL_DEG, 0.0)
    }

    private val JsonObject.id: String get() = this["id"]!!.jsonPrimitive.content
}
