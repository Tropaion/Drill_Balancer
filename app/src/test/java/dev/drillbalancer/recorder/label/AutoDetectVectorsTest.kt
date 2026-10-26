package dev.drillbalancer.recorder.label

import dev.drillbalancer.protocol.FirmwareSources
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FlacDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The Kotlin detector against dbdata.autodetect's golden fixtures
 * (firmware/tools/dataset/fixtures/autodetect, README there): every fixture's recording files go
 * through RecordingFiles and AutoDetect, and the result must be the fixture's expected output
 * within the documented tolerance: features within 1e-6 relative + 1e-6 absolute and NaN where
 * NaN; the same events, spans and holes (types, holes, outcome tags, order), times within one
 * frame, confidences within 0.01; the same derived labels.
 */
class AutoDetectVectorsTest {
    private val root = File(FirmwareSources.dir, "tools/dataset/fixtures/autodetect")
    private val fixtures = root.listFiles { f -> File(f, "expected.json").isFile }.orEmpty().map { it.name }.sorted()

    @Test
    fun eightFixtures() {
        assertEquals(
            listOf("fx_drill_through", "fx_gap", "fx_hammer_retract", "fx_noaudio", "fx_pulled_open", "fx_raw897_mount", "fx_stuck_kick", "fx_suggestions"),
            fixtures,
        )
    }

    @Test fun drillThrough() = check("fx_drill_through")

    @Test fun gap() = check("fx_gap")

    @Test fun hammerRetract() = check("fx_hammer_retract")

    @Test fun noAudio() = check("fx_noaudio")

    @Test fun pulledOpen() = check("fx_pulled_open")

    @Test fun raw897Mount() = check("fx_raw897_mount")

    @Test fun stuckKick() = check("fx_stuck_kick")

    @Test fun suggestions() = check("fx_suggestions")

    /** The app's FLAC reader gives the first channel exactly as the tests' full decoder. */
    @Test
    fun flacReaderMatchesTheDecoder() {
        for (name in fixtures) {
            val f = File(root, "$name/${Finalizer.AUDIO}")
            if (!f.isFile) continue
            val want = FlacDecoder.decode(f.readBytes())
            val got = dev.drillbalancer.recorder.export.FlacReader.readChannel(f, 0)
            assertEquals(name, want.info.sampleRate, got.sampleRate)
            assertArrayEquals(name, want.channel(0), IntArray(got.samples.size) { got.samples[it].toInt() })
        }
    }

    private fun check(name: String) {
        val dir = File(root, name)
        val expected = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject
        val rec = RecordingFiles.read(dir)
        val det = AutoDetect.detect(rec.inputs, TestParams.loaded)
        val f = det.features

        assertEquals("$name frames", expected["frames"].toString().toInt(), f.n)
        assertEquals("$name t0", expected["t0_us"].toString().toLong(), f.t0Us)
        checkFeatures(name, dir, f)

        val want = Labels.fromJson(expected["events"]!!.jsonObject)
        val got = det.labels
        assertEquals("$name detector", want.detector, got.detector)
        assertEquals("$name coverage", want.coverage, got.coverage)
        assertEquals("$name event types", want.events.map { it.type to it.hole }, got.events.map { it.type to it.hole })
        for ((a, b) in want.events.zip(got.events)) {
            assertTrue("$name ${a.id} ${a.type}: t ${b.tUs} vs ${a.tUs}", abs(a.tUs - b.tUs) <= FRAME_US)
            assertTrue("$name ${a.id} ${a.type}: confidence ${b.confidence} vs ${a.confidence}", abs(a.confidence - b.confidence) <= 0.01)
            assertEquals("$name ${a.id}", a.id, b.id)
        }
        assertEquals("$name span types", want.spans.map { Triple(it.type, it.hole, it.reason) }, got.spans.map { Triple(it.type, it.hole, it.reason) })
        for ((a, b) in want.spans.zip(got.spans)) {
            assertTrue("$name ${a.id} ${a.type}: t0 ${b.t0Us} vs ${a.t0Us}", abs(a.t0Us - b.t0Us) <= FRAME_US)
            assertTrue("$name ${a.id} ${a.type}: t1 ${b.t1Us} vs ${a.t1Us}", abs(a.t1Us - b.t1Us) <= FRAME_US)
            assertTrue("$name ${a.id} ${a.type}: confidence ${b.confidence} vs ${a.confidence}", abs(a.confidence - b.confidence) <= 0.01)
        }
        assertEquals("$name holes", want.holes, got.holes)
        assertEquals("$name derived", Derived.fromJson(expected["derived"]), det.derived)
    }

    private fun checkFeatures(name: String, dir: File, f: Features) {
        val lines = GZIPInputStream(File(dir, "features.csv.gz").inputStream()).bufferedReader().readLines()
        val header = lines[0].split(',')
        assertEquals("$name feature rows", f.n, lines.size - 1)
        val bad = HashMap<String, Int>()
        val first = HashMap<String, String>()
        for ((r, line) in lines.drop(1).withIndex()) {
            val cells = line.split(',')
            assertEquals("$name row $r t_us", f.t(r).toString(), cells[1])
            for (feat in Feature.entries) {
                val c = header.indexOf(feat.column)
                val want = cells[c].takeIf { it.isNotEmpty() }?.toDouble() ?: Double.NaN
                val got = f[feat][r]
                val ok = if (want.isNaN()) got.isNaN() else got.isFinite() && abs(got - want) <= 1e-6 + 1e-6 * abs(want)
                if (!ok) {
                    bad[feat.column] = (bad[feat.column] ?: 0) + 1
                    first.putIfAbsent(feat.column, "frame $r: $got vs $want")
                }
            }
        }
        assertTrue("$name features differ: ${bad.keys.sorted().map { "$it ${bad[it]} (${first[it]})" }}", bad.isEmpty())
    }

    companion object {
        const val FRAME_US = 10_000L
    }
}

/** The parameters as the app ships them (src/main/assets). */
object TestParams {
    val file: File get() = File(System.getProperty("user.dir"), "src/main/assets/${DetectParams.ASSET}")
    val loaded: LoadedParams by lazy { DetectParams.load(file.readBytes()) }
}
