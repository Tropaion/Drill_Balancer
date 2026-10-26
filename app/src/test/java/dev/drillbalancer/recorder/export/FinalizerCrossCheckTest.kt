package dev.drillbalancer.recorder.export

import dev.drillbalancer.recorder.RecordingStage
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.TestStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The finalizer against the reference exporter: the takes of fixtures/finalize (the firmware's
 * golden stream, a rich synthetic take and the same take cut off mid-record, as an app that
 * died leaves it) go through RecordingStore like the app's, and every file must be what
 * `dbrec.export_take` wrote from the same bytes:
 * - imu.csv.gz, level.csv.gz: the same CSV bytes once decompressed (the gzip streams of zlib
 *   and java.util.zip may differ);
 * - audio_sync.csv, device_events.csv, events.json: the same bytes;
 * - audio.flac: the same samples (decoded by FlacDecoder; libFLAC and FlacEncoder encode
 *   differently);
 * - meta.json: the same text, except app (the phone, here; dbdata.dbrec's tool name there) and
 *   the sha256 / size of the files above whose bytes may differ (both are checked against the
 *   files written). start_utc is the phone's clock at the take start in both (take.json; the
 *   exporter gets the same value).
 */
class FinalizerCrossCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { RecordingStore(File(tmp.root, "recordings")) }

    private fun finalized(name: String): File {
        val j = FinalizerFixtures.install(store, name)
        if (FinalizerFixtures.spec(name)["cut"]!!.toString() == "null") {
            assertTrue(store.finalize(j.recordingId) != null)
        } else {
            // the app died while writing: recovery cuts the journal and finalizes it
            assertEquals(listOf(j.recordingId), store.recover())
        }
        assertEquals(null, store.read(j.recordingId)!!.finalizeError)
        assertEquals(RecordingStage.NEEDS_REVIEW, store.list().single { it.journal.recordingId == j.recordingId }.stage)
        return store.dir(j.recordingId)
    }

    @Test fun golden() = check("golden")

    @Test fun rich() = check("rich")

    @Test fun richCutOff() = check("rich_cut")

    private fun check(name: String) {
        val ours = finalized(name)
        val want = FinalizerFixtures.expected(name)
        val dataset = (want.list().orEmpty().toSet() - "spec.json").sorted()
        assertEquals(dataset, (ours.list().orEmpty().toSet() - RecordingStore.STREAM - RecordingStore.JOURNAL).sorted())
        for (f in dataset) {
            val a = File(want, f)
            val b = File(ours, f)
            when {
                f.endsWith(".gz") -> assertArrayEquals("$name/$f", gunzip(a), gunzip(b))
                f.endsWith(".flac") -> {
                    val x = FlacDecoder.decode(a.readBytes())
                    val y = FlacDecoder.decode(b.readBytes())
                    assertEquals("$name/$f channels", x.info.channels, y.info.channels)
                    assertEquals("$name/$f rate", x.info.sampleRate, y.info.sampleRate)
                    assertArrayEquals("$name/$f samples", x.samples, y.samples)
                    assertArrayEquals("$name/$f MD5", x.info.md5, y.info.md5)
                }
                f == Finalizer.META -> checkMeta(name, a, b)
                else -> assertEquals("$name/$f", a.readText(), b.readText())
            }
        }
    }

    private fun checkMeta(name: String, want: File, ours: File) {
        val w = FinalizerFixtures.parse(want).jsonObject
        val o = FinalizerFixtures.parse(ours).jsonObject
        // PyJson writes what Python's json.dump writes
        assertEquals("$name: PyJson reproduces the exporter's text", want.readText(), PyJson.dump(w))
        // the hashes of the files whose bytes differ: those of the files written
        val files = o["files"]!!.jsonObject
        for ((f, e) in files) {
            val d = File(ours.parentFile, f)
            assertEquals("$name/$f bytes", d.length().toString(), e.jsonObject["bytes"].toString())
            assertEquals("$name/$f sha256", "\"${sha256(d)}\"", e.jsonObject["sha256"].toString())
        }
        val same = JsonObject(
            w.mapValues { (k, v) ->
                when (k) {
                    "app" -> o["app"]!!
                    "files" -> JsonObject(v.jsonObject.mapValues { (f, e) -> if (f.endsWith(".gz") || f.endsWith(".flac")) files[f]!! else e })
                    else -> v
                }
            },
        )
        assertEquals("$name: meta.json", PyJson.dump(same), ours.readText())
        assertEquals(
            "{\"version\":\"0.6.0\",\"phone_model\":\"Pixel 8\",\"android_sdk\":35}",
            o["app"].toString(),
        )
    }

    /**
     * Opt-in: DB_FINALIZE_OUT=<dir> leaves the finalized fixtures there as a data repo has them
     * (recordings/YYYY/YYYY-MM/<id>/, the dataset files only) for
     * `.venv/bin/python -m dbdata.validate <dir>` (firmware/tools/dataset/fixtures/finalize/README.md).
     */
    @Test
    fun recordingsForTheValidator() {
        val out = System.getenv("DB_FINALIZE_OUT")
        assumeTrue("DB_FINALIZE_OUT not set: the dbdata.validate check is opt-in", !out.isNullOrBlank())
        val clean = FinalizerFixtures.journal("golden").copy(recordingId = "20261026T150000Z_k7q2_c1ea", takeId = 9, startedUtc = "2026-10-26T15:00:00.120Z")
        val bytes = cleanTake(9)
        store.create(clean, 1_000) { 0L }.use { it.append(bytes, 0, bytes.size) }
        assertTrue(store.finalize(clean.recordingId) != null)
        for (src in FinalizerFixtures.names.map { finalized(it) } + store.dir(clean.recordingId)) {
            val id = src.name
            val dest = File(out, "recordings/${id.substring(0, 4)}/${id.substring(0, 4)}-${id.substring(4, 6)}/$id")
            dest.deleteRecursively()
            dest.mkdirs()
            for (f in Finalizer.DATASET_FILES) File(src, f).takeIf { it.isFile }?.copyTo(File(dest, f))
        }
    }

    /** A take without losses: 3 s of IMU reads of 9 samples and 256-frame audio blocks, END user. */
    private fun cleanTake(takeId: Long): ByteArray {
        val t0 = TestStream.T0
        val recs = ArrayList<Pair<Long, (Long) -> ByteArray>>()
        for (r in 0 until 149) recs += t0 + (r * 9 + 8) * 2230L to { seq -> TestStream.imu(seq, 1000L + r * 9, 9, t0 + (r * 9 + 8) * 2230L) }
        for (k in 0 until 187) recs += t0 + (k + 1) * 16_000L to { seq -> TestStream.audio(seq, k * 256L, 256, t0 + (k + 1) * 16_000L) }
        var seq = 0L
        var out = TestStream.session(seq++, takeId, startUs = t0)
        for ((_, rec) in recs.sortedBy { it.first }) out += rec(seq++)
        return out + TestStream.end(seq, t0 + 3_000_000)
    }

    private fun gunzip(f: File): ByteArray = GZIPInputStream(f.inputStream()).use { it.readBytes() }

    private fun sha256(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
