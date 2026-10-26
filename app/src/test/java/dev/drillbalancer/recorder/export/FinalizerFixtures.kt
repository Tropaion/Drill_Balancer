package dev.drillbalancer.recorder.export

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.recorder.AppInfo
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.TakeJournal
import dev.drillbalancer.recorder.TakeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * The finalizer's cross-check fixtures (firmware/tools/dataset/fixtures/finalize, written by
 * make_fixtures.py there with dbdata.dbrec): per fixture a stream, the phone side of its take
 * (spec.json) and the recording files the reference exporter wrote from it.
 */
object FinalizerFixtures {
    val dir: File get() = File(FirmwareSources.dir, "tools/dataset/fixtures/finalize")
    val names = listOf("golden", "rich", "rich_cut")

    private val json = Json { ignoreUnknownKeys = true }

    fun spec(name: String): JsonObject = Json.parseToJsonElement(File(dir, "$name/spec.json").readText()).jsonObject

    /** The take's journal bytes as the app would have them (rich_cut: an app that died mid-record). */
    fun stream(name: String): ByteArray {
        val s = spec(name)
        val data = when (s["stream"]!!.jsonPrimitive.content) {
            "golden" -> FirmwareSources.bytes("test_host/recorder/vectors/golden_stream.bin")
            else -> File(dir, "rich.dbr").readBytes()
        }
        val cut = s["cut"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.toDouble()
        return if (cut == null) data else data.copyOf((data.size * cut).toInt())    // Python: int(len(data) * cut)
    }

    /** take.json for the fixture's take: ids, start time and setup from spec.json. */
    fun journal(name: String, state: JournalState = if (spec(name)["cut"] == JsonNull) JournalState.COMPLETE else JournalState.RECORDING): TakeJournal {
        val s = spec(name)
        fun str(k: String) = s[k]!!.jsonPrimitive.content
        return TakeJournal(
            recordingId = str("recording_id"), sessionId = str("session_id"), takeId = s["take_id"]!!.jsonPrimitive.long,
            reviewer = "k7q2", state = state, startedUtc = str("start_utc"),
            setup = json.decodeFromJsonElement(SessionSetup.serializer(), s["setup"]!!),
            take = TakeSettings("RAW448", 3, 6, 300), app = AppInfo("0.6.0", "Pixel 8", 35), link = "lan",
        )
    }

    /** The fixture's take as the app's recording folder in [store] (journal only, not finalized). */
    fun install(store: RecordingStore, name: String): TakeJournal {
        val j = journal(name)
        val bytes = stream(name)
        store.create(j, 1_000) { 0L }.use { it.append(bytes, 0, bytes.size) }
        return j
    }

    fun expected(name: String) = File(dir, name)

    fun parse(f: File): JsonElement = Json.parseToJsonElement(f.readText())
}
