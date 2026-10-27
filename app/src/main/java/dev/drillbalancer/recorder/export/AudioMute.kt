package dev.drillbalancer.recorder.export

import dev.drillbalancer.recorder.label.LabelFiles
import dev.drillbalancer.recorder.label.LabelTaxonomy
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.label.Phase
import dev.drillbalancer.recorder.label.Phases
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** The recording's audio cannot be muted as its files are (no usable sync fit, events.json missing or without labels, audio.flac missing): trying again does not help. */
class MuteException(message: String) : IOException(message)

/**
 * The upload setting "mute audio while the motor is off" (dataset contract, privacy): audio.flac
 * with every sample zero outside the motor's runs, the spans listed in meta.audio.muted_spans_us
 * (t_us, [t0, t1)), and meta.files' sha256 and bytes of audio.flac updated. A motor run is
 * every phase of the saved events.json but off and unknown, i.e. motor_on to the end of its
 * spin-down (the motor still turns there; [AUDIBLE]), each widened by the taxonomy's boundary
 * guard (guard_ms, 50 ms) on both sides. dbdata.windows marks the windows over muted spans
 * audio_muted.
 *
 * Applied to the recording folder itself before an upload (so the phone and the data repo hold
 * the same files), under the recording's lock. Deterministic: the same files and labels give the
 * same bytes; done again for the same labels it changes nothing. Muting cannot be undone from
 * the muted file: the spans muted before stay muted (the union is kept), so labels moved later
 * never claim audio the file no longer has (a finalize again from the journal, while it is
 * kept, writes the full audio back with no muted spans).
 */
object AudioMute {
    val GUARD_US = LabelTaxonomy.GUARD_MS * 1000L

    /** The phases whose audio is kept. */
    val AUDIBLE = setOf(Phase.SPINUP, Phase.FREE_RUN, Phase.DRILLING, Phase.BREAKTHROUGH, Phase.SPINDOWN)

    /** The time an audio frame was taken (dbdata.io.SyncFit.t_of). */
    class Fit(val aUs: Double, val bUsPerFrame: Double, val adcDelayUs: Double = 0.0) {
        fun tOf(frame: Long): Double = aUs + bUsPerFrame * frame - adcDelayUs
        fun frameOf(tUs: Long): Double = (tUs + adcDelayUs - aUs) / bUsPerFrame

        /** The frames whose time lies in [t0, t1), clipped to [0, frames). */
        fun frames(t0: Long, t1: Long, frames: Long): LongRange {
            val f0 = ceil(frameOf(t0)).toLong().coerceIn(0, frames)
            val f1 = ceil(frameOf(t1)).toLong().coerceIn(0, frames)
            return f0 until f1
        }
    }

    /** Muted spans for [labels] over the audio's time [ta0, ta1): everything outside the guarded motor runs. */
    fun spans(labels: Labels, ta0: Long, ta1: Long): List<LongArray> {
        val keep = merge(Phases.segments(labels).filter { it.phase in AUDIBLE }.map { longArrayOf(it.t0Us - GUARD_US, it.t1Us + GUARD_US) })
        val out = ArrayList<LongArray>()
        var t = ta0
        for (k in keep) {
            if (k[0] > t) out += longArrayOf(t, minOf(k[0], ta1))
            t = maxOf(t, k[1])
            if (t >= ta1) break
        }
        if (t < ta1) out += longArrayOf(t, ta1)
        return out.filter { it[1] > it[0] }
    }

    /** [spans] sorted, touching or overlapping ones joined. */
    fun merge(spans: List<LongArray>): List<LongArray> {
        val out = ArrayList<LongArray>()
        for (s in spans.filter { it[1] > it[0] }.sortedWith(compareBy({ it[0] }, { it[1] }))) {
            val last = out.lastOrNull()
            if (last != null && s[0] <= last[1]) last[1] = maxOf(last[1], s[1]) else out += longArrayOf(s[0], s[1])
        }
        return out
    }

    /**
     * Mutes [dir]'s audio.flac for its saved events.json. False when nothing changed (no audio,
     * or already muted for these labels). Writes audio.flac.tmp and meta.json.tmp, synced, then
     * renames audio.flac, meta.json. Between the two renames (the app dies, or the second rename
     * fails) the muted audio is under the old meta.json and meta.json.tmp, the one for it, stays:
     * [finish] renames it into place, whether muting is still on or not (Finalizer.repairLabels
     * runs it: at the app's start, before a review loads or saves, before an upload). Blocking;
     * the caller holds the recording's lock. [MuteException] when the files cannot be muted as
     * they are; another IOException is the storage's.
     */
    fun apply(dir: File): Boolean {
        val metaFile = File(dir, Finalizer.META)
        val meta = Finalizer.readJsonStrict(metaFile) ?: throw MuteException("${dir.name} is not finalized")
        val audio = meta["audio"] as? JsonObject ?: return false
        if ((audio["file"] as? JsonPrimitive)?.content != Finalizer.AUDIO) return false
        val sync = meta["sync"] as? JsonObject ?: throw MuteException("${dir.name}: audio without a sync fit")
        val fit = Fit(num(sync["a_us"]) ?: badSync(dir), num(sync["b_us_per_frame"])?.takeIf { it > 0 } ?: badSync(dir), num(sync["adc_delay_us"]) ?: 0.0)
        val events = File(dir, Finalizer.EVENTS)
        // a read that fails is the storage's (IOException); one that works may hold no labels
        val labels = Finalizer.readJsonStrict(events)?.let(LabelFiles::labelsOf)
            ?: throw MuteException("${dir.name}: events.json is missing or holds no labels")
        val flac = File(dir, Finalizer.AUDIO)
        if (!flac.isFile) throw MuteException("${dir.name}: ${Finalizer.AUDIO} is missing")
        val info = FlacReader.info(flac)
        val n = info.totalFrames
        if (n <= 0) return false

        val ta0 = floor(fit.tOf(0)).toLong()
        val ta1 = ceil(fit.tOf(n)).toLong()
        val old = spansOf(audio["muted_spans_us"])
        val muted = merge(old + spans(labels, ta0, ta1))
        if (same(muted, old)) return false

        val ranges = muted.map { fit.frames(it[0], it[1], n) }.filter { !it.isEmpty() }
        val tmp = File(dir, "${Finalizer.AUDIO}.tmp")
        val metaTmp = File(dir, "${Finalizer.META}.tmp")
        var audioReplaced = false
        try {
            writeMuted(flac, tmp, info, ranges)
            FileOutputStream(tmp, true).use { it.fd.sync() }
            val newMeta = JsonObject(
                meta.mapValues { (k, v) ->
                    when (k) {
                        "audio" -> JsonObject(audio.mapValues { (ak, av) -> if (ak == "muted_spans_us") toJson(muted) else av })
                        "files" -> filesWith(v as? JsonObject ?: JsonObject(emptyMap()), Finalizer.AUDIO, Finalizer.sha256(tmp), tmp.length())
                        else -> v
                    }
                },
            )
            FileOutputStream(metaTmp).use {
                it.write(PyJson.dump(newMeta).toByteArray())
                it.fd.sync()
            }
            if (!tmp.renameTo(flac)) throw IOException("cannot replace ${Finalizer.AUDIO} in ${dir.name}")
            audioReplaced = true
            if (!metaTmp.renameTo(metaFile)) throw IOException("cannot replace ${Finalizer.META} in ${dir.name}")
            return true
        } finally {
            tmp.delete()
            if (!audioReplaced) metaTmp.delete()          // after it: kept for [finish]
        }
    }

    /**
     * Finishes an [apply] stopped between its renames: audio.flac is the muted one, meta.json
     * the one before, meta.json.tmp the one for the muted audio (synced before either rename).
     * It is renamed into place when it differs from meta.json only in audio.muted_spans_us and
     * files' audio.flac, and that entry's sha256 and bytes are audio.flac's (and meta.json's are
     * not). True when it repaired the pair. Blocking; the caller holds the recording's lock.
     */
    fun finish(dir: File): Boolean {
        val metaTmp = File(dir, "${Finalizer.META}.tmp")
        if (!metaTmp.isFile) return false
        val metaFile = File(dir, Finalizer.META)
        val meta = Finalizer.readJson(metaFile) ?: return false
        val next = Finalizer.readJson(metaTmp) ?: return false
        val want = audioEntry(next) ?: return false
        if (want == audioEntry(meta) || withoutMute(next) != withoutMute(meta)) return false
        val flac = File(dir, Finalizer.AUDIO)
        if (!flac.isFile || flac.length() != want.second || Finalizer.sha256(flac) != want.first) return false
        if (!metaTmp.renameTo(metaFile)) throw IOException("cannot replace ${Finalizer.META} in ${dir.name}")
        return true
    }

    // meta.files' audio.flac: sha256 and bytes.
    private fun audioEntry(meta: JsonObject): Pair<String, Long>? = Finalizer.fileEntry(meta, Finalizer.AUDIO)

    // meta.json but for what a mute changes.
    private fun withoutMute(meta: JsonObject): JsonObject = JsonObject(
        meta.mapValues { (k, v) ->
            when (k) {
                "audio" -> (v as? JsonObject)?.let { JsonObject(it - "muted_spans_us") } ?: v
                "files" -> (v as? JsonObject)?.let { JsonObject(it - Finalizer.AUDIO) } ?: v
                else -> v
            }
        },
    )

    /** The muted spans meta.audio lists (empty without audio). */
    fun mutedSpans(meta: JsonObject?): List<LongArray> = spansOf((meta?.get("audio") as? JsonObject)?.get("muted_spans_us"))

    private fun writeMuted(src: File, dst: File, info: FlacInfo, ranges: List<LongRange>) {
        val ch = info.channels
        FlacEncoder.writeFile(dst, info.sampleRate, ch, info.totalFrames) { enc ->
            var frame = 0L
            var r = 0
            var buf = ShortArray(0)
            src.inputStream().use { input ->
                FlacReader.decode(input) { x, n ->
                    if (buf.size < n * ch) buf = ShortArray(n * ch)
                    for (i in 0 until n) {
                        val f = frame + i
                        while (r < ranges.size && ranges[r].last < f) r++
                        val muted = r < ranges.size && f >= ranges[r].first
                        for (c in 0 until ch) buf[i * ch + c] = if (muted) 0 else x[c][i].toShort()
                    }
                    enc.write(buf, 0, n)
                    frame += n
                }
            }
        }
    }

    private fun filesWith(files: JsonObject, name: String, sha: String, bytes: Long): JsonObject =
        JsonObject(files.mapValues { (k, v) -> if (k == name) buildJsonObject { put("sha256", sha); put("bytes", bytes) } else v })

    private fun spansOf(e: JsonElement?): List<LongArray> = (e as? JsonArray).orEmpty().mapNotNull { s ->
        val a = (s as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return@mapNotNull null
        if (a.size == 2) longArrayOf(a[0], a[1]) else null
    }

    private fun toJson(spans: List<LongArray>) = JsonArray(spans.map { JsonArray(listOf(JsonPrimitive(it[0]), JsonPrimitive(it[1]))) })

    private fun same(a: List<LongArray>, b: List<LongArray>) = a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun badSync(dir: File): Nothing = throw MuteException("${dir.name}: meta.sync has no usable fit")
}
