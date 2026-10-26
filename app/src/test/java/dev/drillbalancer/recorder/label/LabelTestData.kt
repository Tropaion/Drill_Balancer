package dev.drillbalancer.recorder.label

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.recorder.JournalState
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FinalizerFixtures
import dev.drillbalancer.recorder.export.FlacDecoder
import dev.drillbalancer.recorder.export.FlacEncoder
import dev.drillbalancer.recorder.export.PyJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Recordings for the label and review tests: the autodetect fixtures as app recordings, and long takes. */
object LabelTestData {
    val root: File get() = File(FirmwareSources.dir, "tools/dataset/fixtures/autodetect")

    fun fixture(name: String) = File(root, name)

    /** The recording's id in its meta.json. */
    fun idOf(name: String): String = (meta(fixture(name))["recording_id"] as JsonPrimitive).content

    private fun meta(dir: File): JsonObject = Json.parseToJsonElement(File(dir, Finalizer.META).readText()).jsonObject

    /**
     * The fixture [name] as a finalized recording of [store]: its dataset files and a take.json
     * (complete), labelled by [labeler] when given (else events.json is the fixture's truth).
     */
    fun install(store: RecordingStore, name: String, labeler: AutoLabeler? = null): String {
        val id = idOf(name)
        val dir = store.dir(id)
        dir.mkdirs()
        for (f in Finalizer.DATASET_FILES) File(fixture(name), f).takeIf { it.isFile }?.copyTo(File(dir, f), overwrite = true)
        store.write(FinalizerFixtures.journal("golden", JournalState.COMPLETE).copy(recordingId = id, reviewer = "k7q2"))
        labeler?.relabel(dir)
        return id
    }

    /**
     * A take of about [minutes] minutes in [dir]: the fixture [name]'s recording repeated end to
     * end (IMU, audio, sync blocks and LEVEL rows moved by the audio's length each time), with its
     * meta.json and an events.json covering the whole.
     */
    fun longTake(dir: File, minutes: Double, name: String = "fx_drill_through") {
        val src = fixture(name)
        dir.mkdirs()
        val audio = FlacDecoder.decode(File(src, Finalizer.AUDIO).readBytes())
        val frames = audio.frames
        val periodUs = frames * 62.5      // 16 kHz: the sync fit is 62.5 us per frame exactly
        val reps = maxOf(1, (minutes * 60e6 / periodUs).toInt())
        val imu = gunzipLines(File(src, Finalizer.IMU))
        val level = gunzipLines(File(src, Finalizer.LEVEL))
        val sync = File(src, Finalizer.SYNC).readLines().filter { it.isNotBlank() }
        val imuRows = imu.drop(1)
        val seqSpan = imuRows.last().split(',')[0].toLong() - imuRows.first().split(',')[0].toLong() + 1
        gzip(File(dir, Finalizer.IMU)) { w ->
            w.append(imu[0]).append('\n')
            for (r in 0 until reps) {
                val dt = (r * periodUs).toLong()
                for (line in imuRows) {
                    val c = line.split(',').toMutableList()
                    c[0] = (c[0].toLong() + r * seqSpan).toString()
                    c[1] = (c[1].toLong() + dt).toString()
                    w.append(c.joinToString(",")).append('\n')
                }
            }
        }
        gzip(File(dir, Finalizer.LEVEL)) { w ->
            w.append(level[0]).append('\n')
            for (r in 0 until reps) {
                val dt = (r * periodUs).toLong()
                for (line in level.drop(1)) {
                    val c = line.split(',').toMutableList()
                    c[0] = (c[0].toLong() + dt).toString()
                    w.append(c.joinToString(",")).append('\n')
                }
            }
        }
        File(dir, Finalizer.SYNC).bufferedWriter().use { w ->
            w.append(sync[0]).append('\n')
            for (r in 0 until reps) {
                for (line in sync.drop(1)) {
                    val (f, t) = line.split(',').map { it.toLong() }
                    w.append("${f + r.toLong() * frames},${t + (r * periodUs).toLong()}\n")
                }
            }
        }
        val ch0 = audio.channel(0)
        val samples = ShortArray(ch0.size) { ch0[it].toShort() }
        FlacEncoder.writeFile(File(dir, Finalizer.AUDIO), 16000, 1, frames.toLong() * reps) { e -> repeat(reps) { e.write(samples) } }
        val meta = meta(src)
        File(dir, Finalizer.META).writeText(PyJson.dump(JsonObject(meta + ("gaps" to Json.parseToJsonElement("""{"imu_filled_samples":0,"imu_missing_samples":0,"transport_gaps":[],"audio_missing_frames":0,"audio_gap_frames":[]}""")))))
        val t0 = imuRows.first().split(',')[1].toLong()
        val tLast = imuRows.last().split(',')[1].toLong() + ((reps - 1) * periodUs).toLong()
        val id = (meta["recording_id"] as JsonPrimitive).content
        LabelFiles.writeLabels(File(dir, Finalizer.EVENTS), Labels(id, 0, listOf(t0, tLast + 1)))
    }

    private fun gunzipLines(f: File): List<String> = GZIPInputStream(f.inputStream()).bufferedReader().readLines().filter { it.isNotBlank() }

    private fun gzip(f: File, body: (Appendable) -> Unit) = GZIPOutputStream(f.outputStream(), 1 shl 16).bufferedWriter().use(body)
}
