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

        val s = SessionSetup(
            materialOther = "x".repeat(50), thicknessMm = -1.0, bitDiameterMm = 250.0, drillModel = "m".repeat(80), gear = 4,
            notes = "n".repeat(300), micMask = 7, gainDb = 50, maxS = 5000,
        ).sanitized()
        assertEquals(40, s.materialOther.length)
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
