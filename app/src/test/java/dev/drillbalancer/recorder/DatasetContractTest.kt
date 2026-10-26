package dev.drillbalancer.recorder

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.protocol.RecProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

// The app's ids and labels against the dataset contract (firmware/tools/dataset/dbdata/contract).

class DatasetContractTest {
    private val contract = "tools/dataset/dbdata/contract"
    private fun json(name: String) = Json.parseToJsonElement(FirmwareSources.text("$contract/$name")).jsonObject

    private fun ids(field: String): List<String> =
        json("taxonomy.v1.json")["fields"]!!.jsonObject[field]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun taxonomyIdsAreTheContracts() {
        val t = json("taxonomy.v1.json")
        assertEquals(Taxonomy.VERSION, t["version"]!!.jsonPrimitive.int)
        assertEquals(Taxonomy.OTHER_TEXT_MAX, t["other_text_max"]!!.jsonPrimitive.int)
        assertEquals(listOf(Taxonomy.UNKNOWN, Taxonomy.OTHER), t["special"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ids("material"), Taxonomy.MATERIALS.map { it.id })
        assertEquals(ids("mode"), Taxonomy.MODES.map { it.id })
        assertEquals(ids("bit_type"), Taxonomy.BIT_TYPES.map { it.id })
        assertEquals(ids("drill_type"), Taxonomy.DRILL_TYPES.map { it.id })
        assertEquals(ids("motor"), Taxonomy.MOTORS.map { it.id })
    }

    /** The setup's hint uses the validator's table (W_MODE_TOOL): the same pairs, unknown and other never. */
    @Test
    fun modeToolConflictsAreTheContracts() {
        val table = json("taxonomy.v1.json")["mode_tool_conflicts"]!!.jsonObject["conflicts"]!!.jsonObject
            .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        assertEquals(table, Taxonomy.MODE_TOOL_CONFLICTS)
        val types = Taxonomy.choices(Taxonomy.DRILL_TYPES).map { it.id }
        val pairs = Taxonomy.MODES.flatMap { m -> types.filter { Taxonomy.modeToolConflict(m.id, it) }.map { m.id to it } }
        assertEquals(listOf("hammer" to "drill", "hammer" to "impact_driver", "screw" to "rotary_hammer"), pairs)
        for (special in listOf(Taxonomy.UNKNOWN, Taxonomy.OTHER)) {
            for (m in Taxonomy.MODES) assertEquals(false, Taxonomy.modeToolConflict(m.id, special))
        }
    }

    /** A setup kept by an app from before drill_type and motor loads them as unknown; a new one keeps them. */
    @Test
    fun anOlderKeptSetupLoadsTheToolAsUnknown() {
        val old = "{\"material\":\"metal/steel\",\"material_other\":\"\",\"thickness_mm\":4.0,\"mode\":\"drill\",\"bit_type\":\"twist_hss\"," +
            "\"bit_other\":\"\",\"bit_diameter_mm\":6.0,\"drill_model\":\"Bosch\",\"gear\":2,\"speed_setting\":null,\"notes\":\"\"," +
            "\"mic_mask\":3,\"gain_db\":6,\"max_s\":300}"
        val s = SetupStore.decode(old)
        assertEquals(SessionSetup(material = "metal/steel", thicknessMm = 4.0, bitType = "twist_hss", bitDiameterMm = 6.0, drillModel = "Bosch", gear = 2, maxS = 300), s)
        assertEquals(listOf(Taxonomy.UNKNOWN, "", Taxonomy.UNKNOWN, ""), listOf(s.drillType, s.drillTypeOther, s.motor, s.motorOther))
        val tool = SessionSetup(drillType = "other", drillTypeOther = "Magnetic drill", motor = "universal_ac")
        val text = SetupStore.encode(tool)
        assertTrue(text, "\"drill_type\":\"other\",\"drill_type_other\":\"Magnetic drill\",\"motor\":\"universal_ac\",\"motor_other\":\"\"" in text)
        assertEquals(tool, SetupStore.decode(text))
        assertEquals(SessionSetup(), SetupStore.decode("not json"))
        assertEquals(SessionSetup(), SetupStore.decode(null))
    }

    @Test
    fun idsMatchTheSchema() {
        val defs = json("meta.v1.schema.json")["\$defs"]!!.jsonObject
        assertEquals(RecordingIds.RECORDING_ID.pattern, (defs["recording_id"] as JsonObject)["pattern"]!!.jsonPrimitive.content)
        val session = json("meta.v1.schema.json")["properties"]!!.jsonObject["session_id"]!!.jsonObject
        assertEquals(RecordingIds.SESSION_ID.pattern, session["pattern"]!!.jsonPrimitive.content)

        val install = RecordingIds.newInstallId(Random(1))
        assertTrue(install, Regex("[a-z2-7]{16}").matches(install))
        val now = Instant.parse("2026-10-26T14:30:12.418Z")
        val rid = RecordingIds.recordingId(now, install, Random(2))
        assertTrue(rid, RecordingIds.RECORDING_ID.matches(rid))
        assertTrue(rid.startsWith("20261026T143012Z_${install.take(4)}_"))
        assertEquals("20261026T1430_${install.take(4)}", RecordingIds.sessionId(now, install))
        assertTrue(RecordingIds.SESSION_ID.matches(RecordingIds.sessionId(now, install)))
        assertEquals("2026-10-26T14:30:12.418Z", RecordingIds.utc(now))
        repeat(1000) { val id = RecordingIds.takeId(Random(it)); assertTrue(id in 1..0xFFFF_FFFFL) }
    }

    @Test
    fun setupFollowsTheSchemaLimits() {
        val session = json("meta.v1.schema.json")["properties"]!!.jsonObject["session"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(SessionSetup.NOTES_MAX, session["notes"]!!.jsonObject["maxLength"]!!.jsonPrimitive.int)
        val drill = session["drill"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(SessionSetup.DRILL_MODEL_MAX, drill["model"]!!.jsonObject["maxLength"]!!.jsonPrimitive.int)
        // the tool: taxonomy ids, required (unknown when not known)
        val required = session["drill"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("type", "motor", "model", "gear", "speed_setting"), required)
        assertEquals(listOf(Taxonomy.UNKNOWN, Taxonomy.UNKNOWN), SessionSetup().let { listOf(it.drillType, it.motor) })

        val s = SessionSetup(
            materialOther = "x".repeat(50), thicknessMm = -1.0, bitDiameterMm = 250.0, drillModel = "m".repeat(80), gear = 4,
            notes = "n".repeat(300), micMask = 7, gainDb = 50, maxS = 5000, drillTypeOther = "t".repeat(41), motorOther = "m".repeat(60),
        ).sanitized()
        assertEquals(40, s.materialOther.length)
        assertEquals(listOf(40, 40), listOf(s.drillTypeOther.length, s.motorOther.length))
        assertEquals(null, s.thicknessMm)
        assertEquals(null, s.bitDiameterMm)
        assertEquals(64, s.drillModel.length)
        assertEquals(null, s.gear)
        assertEquals(200, s.notes.length)
        assertEquals(listOf(3, 37, 1200), listOf(s.micMask, s.gainDb, s.maxS))
        // The profile follows the mode: hammer blows need ±16 g.
        assertEquals(RecProfile.RAW448_16G, SessionSetup(mode = "hammer").profile)
        assertEquals(RecProfile.RAW448, SessionSetup(mode = "drill").profile)
        assertEquals(RecProfile.RAW448, SessionSetup(mode = "screw").profile)
    }
}
