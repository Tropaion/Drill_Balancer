package dev.drillbalancer.recorder.export

import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Marker
import dev.drillbalancer.upload.UploadTestData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor

/** "Mute audio while the motor is off": the spans, the muted audio.flac and meta.json, determinism. */
class AudioMuteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val c0 = 1_000_000L
    private val c1 = 11_000_000L
    private fun s(x: Double) = c0 + (x * 1e6).toLong()
    private fun labels(vararg ev: Pair<String, Double>) =
        Labels("20261026T143012Z_k7q2_9f3a", 1, listOf(c0, c1), events = ev.mapIndexed { i, (t, at) -> Marker("e$i", t, s(at)) })

    private fun spans(l: Labels) = AudioMute.spans(l, c0, c1).map { it.toList() }

    @Test
    fun withoutTheMotorEverythingIsMuted() {
        assertEquals(listOf(listOf(c0, c1)), spans(labels()))
    }

    @Test
    fun aMotorRunKeepsItsSpinDownAndTheGuard() {
        // spin-up 2.0-2.3, free run to 5.0, spin-down to 5.6; 50 ms guard on both sides
        assertEquals(listOf(listOf(c0, s(1.95)), listOf(s(5.65), c1)), spans(labels("motor_on" to 2.0, "motor_off" to 5.0)))
        // a restart before the spin-down ends: one audible stretch
        assertEquals(
            listOf(listOf(c0, s(1.95)), listOf(s(8.65), c1)),
            spans(labels("motor_on" to 2.0, "motor_off" to 5.0, "motor_on" to 5.3, "motor_off" to 8.0)),
        )
        // two runs apart: the gap between them muted
        assertEquals(
            listOf(listOf(c0, s(0.95)), listOf(s(2.65), s(5.95)), listOf(s(7.65), c1)),
            spans(labels("motor_on" to 1.0, "motor_off" to 2.0, "motor_on" to 6.0, "motor_off" to 7.0)),
        )
        // a take that ends with the motor on: audible to its end
        assertEquals(listOf(listOf(c0, s(7.95))), spans(labels("motor_on" to 8.0)))
        // a hole in between is part of the run
        assertEquals(
            listOf(listOf(c0, s(1.95)), listOf(s(5.65), c1)),
            spans(Labels("x", 1, listOf(c0, c1), events = listOf(
                Marker("e1", "motor_on", s(2.0)), Marker("e2", "contact", s(3.0), hole = 1),
                Marker("e3", "breakthrough", s(4.0), hole = 1), Marker("e4", "motor_off", s(5.0)),
            ))),
        )
        // the guard reaches before the audio: clipped
        assertEquals(listOf(listOf(s(5.65), c1)), spans(labels("motor_on" to 0.01, "motor_off" to 5.0)))
    }

    @Test
    fun spansMerge() {
        assertEquals(
            listOf(listOf(1L, 5L), listOf(7L, 9L)),
            AudioMute.merge(listOf(longArrayOf(7, 8), longArrayOf(1, 3), longArrayOf(3, 5), longArrayOf(8, 9), longArrayOf(4, 4))).map { it.toList() },
        )
    }

    private fun mutedRecording(root: File, on: Double = 0.5, off: Double = 1.0): Pair<File, String> {
        val labeler = UploadTestData.labeler()
        val store = UploadTestData.store(root, labeler)
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, on, off) }
        return store.dir(id) to id
    }

    @Test
    fun theAudioIsZeroExactlyWhereMuted() {
        val (dir, _) = mutedRecording(tmp.root)
        val flac = File(dir, Finalizer.AUDIO)
        val before = FlacDecoder.decode(flac.readBytes())
        val metaBefore = Finalizer.readJson(File(dir, Finalizer.META))!!
        assertTrue(AudioMute.mutedSpans(metaBefore).isEmpty())

        assertTrue(AudioMute.apply(dir))
        val after = FlacDecoder.decode(flac.readBytes())          // CRCs and the STREAMINFO MD5 checked
        val meta = Finalizer.readJson(File(dir, Finalizer.META))!!
        val sync = meta["sync"]!!.jsonObject
        val fit = AudioMute.Fit(sync["a_us"]!!.jsonPrimitive.content.toDouble(), sync["b_us_per_frame"]!!.jsonPrimitive.content.toDouble())
        val n = before.frames.toLong()
        val labels = LabelFiles.readLabels(File(dir, Finalizer.EVENTS))!!
        val want = AudioMute.spans(labels, floor(fit.tOf(0)).toLong(), ceil(fit.tOf(n)).toLong())
        val muted = AudioMute.mutedSpans(meta)
        assertEquals(want.map { it.toList() }, muted.map { it.toList() })
        assertEquals(2, muted.size)                               // before the run and after its spin-down

        assertEquals(before.frames, after.frames)
        assertEquals(before.info.channels, after.info.channels)
        var zeroed = 0
        for (f in 0 until before.frames) {
            val t = fit.tOf(f.toLong())
            val inMuted = muted.any { t >= it[0] && t < it[1] }
            for (c in 0 until before.info.channels) {
                val i = f * before.info.channels + c
                if (inMuted) assertEquals("frame $f", 0, after.samples[i]) else assertEquals("frame $f", before.samples[i], after.samples[i])
            }
            if (inMuted) zeroed++
        }
        assertTrue(zeroed > 0 && zeroed < before.frames)

        // meta.json: the spans and the new audio.flac's hash and size, all else as it was
        val files = meta["files"]!!.jsonObject
        assertEquals(sha256(flac), files[Finalizer.AUDIO]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals(flac.length(), files[Finalizer.AUDIO]!!.jsonObject["bytes"]!!.jsonPrimitive.long)
        fun rest(o: JsonObject) = JsonObject(
            o.mapValues { (k, v) ->
                when (k) {
                    "audio" -> JsonObject(v.jsonObject - "muted_spans_us")
                    "files" -> JsonObject(v.jsonObject - Finalizer.AUDIO)
                    else -> v
                }
            },
        )
        assertEquals(rest(metaBefore), rest(meta))
        assertEquals(emptyList<String>(), SchemaCheck("meta.v1.schema.json").problems(meta))
        assertFalse(File(dir, "${Finalizer.AUDIO}.tmp").exists() || File(dir, "${Finalizer.META}.tmp").exists())
    }

    @Test
    fun mutingIsDeterministicAndDoneOnce() {
        val (a, _) = mutedRecording(File(tmp.root, "a"))
        val (b, _) = mutedRecording(File(tmp.root, "b"))
        assertTrue(AudioMute.apply(a))
        assertTrue(AudioMute.apply(b))
        for (f in listOf(Finalizer.AUDIO, Finalizer.META)) assertArrayEquals(f, File(a, f).readBytes(), File(b, f).readBytes())
        val bytes = File(a, Finalizer.AUDIO).readBytes()
        val meta = File(a, Finalizer.META).readBytes()
        assertFalse("muted already: nothing changes", AudioMute.apply(a))
        assertArrayEquals(bytes, File(a, Finalizer.AUDIO).readBytes())
        assertArrayEquals(meta, File(a, Finalizer.META).readBytes())
    }

    /** Muted audio stays muted: labels moved later only add muted spans, never claim back what the file lost. */
    @Test
    fun theMutedSpansOnlyGrow() {
        val labeler = UploadTestData.labeler()
        val store = UploadTestData.store(tmp.root, labeler)
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val dir = store.dir(id)
        assertTrue(AudioMute.apply(dir))
        val first = AudioMute.mutedSpans(Finalizer.readJson(File(dir, Finalizer.META))).map { it.toList() }
        // a longer run: nothing comes back
        UploadTestData.save(store, labeler, id) { l -> UploadTestData.motorRun(l, 0.3, 1.3) }
        assertFalse(AudioMute.apply(dir))
        assertEquals(first, AudioMute.mutedSpans(Finalizer.readJson(File(dir, Finalizer.META))).map { it.toList() })
        // a shorter one: more is muted, the first spans stay inside
        UploadTestData.save(store, labeler, id) { l -> UploadTestData.motorRun(l, 0.6, 0.8) }
        assertTrue(AudioMute.apply(dir))
        val second = AudioMute.mutedSpans(Finalizer.readJson(File(dir, Finalizer.META)))
        assertTrue(second.sumOf { it[1] - it[0] } > first.sumOf { it[1] - it[0] })
        for (s in first) assertTrue(second.any { it[0] <= s[0] && it[1] >= s[1] })
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
