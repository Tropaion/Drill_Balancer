package dev.drillbalancer.recorder.export

import dev.drillbalancer.protocol.toHex
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.SessionSetup
import dev.drillbalancer.recorder.TakeJournal
import dev.drillbalancer.recorder.Taxonomy
import dev.drillbalancer.recorder.label.Derived
import dev.drillbalancer.recorder.label.Labels
import dev.drillbalancer.recorder.stream.RecStream
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** A take that cannot become a recording (no SESSION, too few IMU samples, bad scales). */
class FinalizeException(message: String) : Exception(message)

/** What [Finalizer.finalize] wrote. */
data class FinalizeResult(
    val recordingId: String,
    val durationS: Double,
    val hasAudio: Boolean,
    val files: List<String>,           // the data files of meta.files
    val labelsKept: Boolean,           // events.json (and meta's derived/review) from before were kept
)

/**
 * Rebuilds a take's dataset files (the dataset contract: imu.csv.gz, level.csv.gz, audio.flac,
 * audio_sync.csv, device_events.csv, meta.json, events.json) in its recording folder from the
 * journal: stream.dbr and take.json. It is the reference exporter's algorithm
 * (`dbdata.dbrec export`, FinalizerCrossCheckTest compares the output): the take whose SESSION
 * has take.json's take_id, IMU and audio cut to the time both cover, the device's settings and
 * calibration from SESSION, the phone side (ids, start time, app, session labels) from take.json.
 *
 * Each file is written next to its place and renamed over it (meta.json last, so its presence
 * means finished), and the same journal gives the same bytes (gzip without a time stamp, no
 * clock read), so running it again is harmless. Labels already there for the same coverage
 * (events.json, meta's derived and review) are kept; otherwise events.json is an empty label set
 * (review auto_only) for the auto-detector to fill. Reads the journal twice (the second time for
 * the audio samples, which are never all in memory). Blocking.
 */
open class Finalizer {
    open fun finalize(dir: File, journal: TakeJournal): FinalizeResult {
        val stream = File(dir, RecordingStore.STREAM)
        if (!stream.isFile) throw FinalizeException("the take has no journal")
        val take = pickTake(readTakes(stream), journal.takeId) ?: throw FinalizeException("the journal holds no records")
        val s = take.session ?: throw FinalizeException("the take has no SESSION record: profile and scales are unknown")
        if (s.accelLsbPerG == 0 || s.gyroLsbPerDps == 0) {
            throw FinalizeException("SESSION scales ${s.accelLsbPerG} LSB/g, ${s.gyroLsbPerDps} LSB/dps: the counts cannot be scaled")
        }
        if (!axisMapOk(s.axis)) throw FinalizeException("SESSION axis map ${s.axis} is not a chip-to-board map")
        val track = imuTrack(take)
        if (track == null || track.size < 2) throw FinalizeException("the take has fewer than two IMU samples")
        val (imu, audio) = commonSpan(track, audioPlan(take), s)
        val withAudio = audio != null && audio.exported
        if (withAudio && s.audioRateHz <= 0) throw FinalizeException("SESSION audio rate ${s.audioRateHz}")

        repairLabels(dir)          // a label write the app died in is finished before its labels are kept
        val out = Staged(dir)
        try {
            out.write(IMU) { writeGz(it) { w -> imuCsv(w, imu) } }
            out.write(LEVEL) { writeGz(it) { w -> levelCsv(w, take.of()) } }
            var clipped = 0L
            if (withAudio) {
                out.writeFile(AUDIO) { f -> clipped = writeFlac(f, stream, audio, s.audioRateHz.toInt()) }
                out.write(SYNC) { o -> o.write(syncCsv(audio).toByteArray(Charsets.US_ASCII)) }
            }
            val events = take.of<StreamRecord.Event>().filter { it.codeName != null }
            if (events.isNotEmpty()) out.write(DEVICE_EVENTS) { o -> o.write(deviceEventsCsv(events).toByteArray(Charsets.UTF_8)) }

            val coverage = longArrayOf(imu.tUs[0], imu.tUs[imu.size - 1] + 1)
            val old = keptLabels(dir, journal.recordingId, coverage)
            val meta = buildMeta(journal, take, s, imu, if (withAudio) audio else null, clipped, old, out.digests())
            if (old == null) out.write(EVENTS) { o -> o.write(PyJson.dump(emptyEvents(journal.recordingId, coverage)).toByteArray()) }
            out.write(META) { o -> o.write(PyJson.dump(meta).toByteArray()) }
            val files = out.dataFiles()
            out.commit(stale = OPTIONAL_FILES - files.toSet())
            return FinalizeResult(journal.recordingId, pyRound((imu.tUs[imu.size - 1] - imu.tUs[0]).toDouble() / 1e6, 3), withAudio, files, old != null)
        } finally {
            out.discard()
        }
    }

    // ---- the journal ----

    /** The journal's takes; AUDIO records without their samples (the second pass reads them). */
    private fun readTakes(stream: File): List<Take> {
        val records = ArrayList<StreamRecord>()
        forEachRecord(stream, StreamDecoder(audioSamples = false)) { records += it }
        return splitTakes(records)
    }

    private fun forEachRecord(stream: File, d: StreamDecoder = StreamDecoder(), each: (StreamRecord) -> Unit) {
        stream.forEachBlock(1 shl 16) { buf, n -> d.feed(buf, 0, n).forEach(each) }
        d.finish().forEach(each)
    }

    // ---- data files ----

    private fun writeGz(o: OutputStream, body: (Appendable) -> Unit) {
        // GZIPOutputStream writes no time stamp or file name: equal data, equal bytes. Closing it
        // ends its Deflater; the file itself is closed by Staged.
        GZIPOutputStream(KeepOpen(o), 1 shl 16).bufferedWriter(Charsets.US_ASCII).use { body(it) }
    }

    private fun imuCsv(w: Appendable, imu: ImuTrack) {
        w.append("seq,t_us,ax,ay,az,gx,gy,gz,flags\n")
        val sb = StringBuilder(64)
        for (i in 0 until imu.size) {
            sb.setLength(0)
            sb.append(imu.seq[i]).append(',').append(imu.tUs[i])
            for (a in 0 until 6) sb.append(',').append(imu.counts[i * 6 + a].toInt())
            sb.append(',').append(imu.flags[i]).append('\n')
            w.append(sb)
        }
    }

    private fun levelCsv(w: Appendable, rows: List<StreamRecord.Level>) {
        w.append(LEVEL_COLUMNS.joinToString(",")).append('\n')
        for (l in rows) {
            w.append(
                listOf(
                    l.hostUs, l.mode, l.flags, l.elevationCdeg, l.yawCdeg, l.bubbleXCdeg, l.bubbleYCdeg, l.deviationCdeg,
                    l.zeroElevationCdeg, l.zeroBubbleXCdeg, l.zeroBubbleYCdeg,
                ).joinToString(","),
            ).append('\n')
        }
    }

    private fun syncCsv(a: AudioPlan): String = buildString {
        append("frame,t_us\n")
        for (i in a.syncFrame.indices) append(a.syncFrame[i]).append(',').append(a.syncT[i]).append('\n')
    }

    private fun deviceEventsCsv(events: List<StreamRecord.Event>): String = buildString {
        append("t_us,code,arg,text\n")
        for (e in events) {
            append(e.hostUs).append(',').append(e.codeName).append(',').append(e.arg).append(',')
            append(csvField(takeCodePoints(e.text, RecStream.EVENT_TEXT_MAX))).append('\n')
        }
    }

    /**
     * audio.flac: the kept frames [AudioPlan.from, to) of the take, silence where blocks are
     * missing, the samples read from the journal again. Returns the samples at full scale.
     */
    private fun writeFlac(file: File, stream: File, a: AudioPlan, rate: Int): Long {
        var clipped = 0L
        FlacEncoder.writeFile(file, rate, a.channels, a.frames) { enc ->
            var cursor = a.from
            forEachRecord(stream) { r ->
                val p = (r as? StreamRecord.Audio)?.let { a.placements[it.offset] } ?: return@forEachRecord
                val start = p.at
                val end = minOf(p.at + p.count, a.to)
                if (end <= cursor) return@forEachRecord
                if (start > cursor) {
                    val gap = minOf(start, a.to) - cursor
                    enc.writeSilence(gap)
                    cursor += gap
                }
                if (end > cursor) {
                    val first = p.skip + (cursor - start).toInt()
                    val frames = (end - cursor).toInt()
                    val from = first * a.channels
                    for (i in from until from + frames * a.channels) {
                        val v = r.samples[i].toInt()
                        if (v >= 32767 || v <= -32767) clipped++
                    }
                    enc.write(r.samples, from, frames)
                    cursor = end
                }
            }
            if (cursor < a.to) enc.writeSilence(a.to - cursor)
        }
        return clipped
    }

    // ---- meta.json ----

    /** Kept from a previous run: events.json for the same coverage, and meta's derived and review. */
    private class Kept(val derived: JsonElement, val review: JsonElement)

    private fun keptLabels(dir: File, id: String, coverage: LongArray): Kept? {
        val events = readJson(File(dir, EVENTS)) ?: return null
        val cov = (events["coverage_us"] as? JsonArray)?.map { it.jsonPrimitive.longOrNull }
        if ((events["recording_id"] as? JsonPrimitive)?.content != id || cov != coverage.toList()) return null
        val meta = readJson(File(dir, META)) ?: return null
        return Kept(meta["derived"] ?: return null, meta["review"] ?: return null)
    }

    private fun buildMeta(
        j: TakeJournal,
        take: Take,
        s: StreamRecord.Session,
        imu: ImuTrack,
        audio: AudioPlan?,
        clipped: Long,
        kept: Kept?,
        files: Map<String, Pair<String, Long>>,
    ): JsonObject {
        val (fwVersion, fwGit) = fwParts(s.fw)
        val tail = try {
            Json.parseToJsonElement(s.tail) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val cal = tail?.get("cal") as? JsonObject
        val seq = imu.seq
        val t = imu.tUs
        val last = imu.size - 1
        val odr = if (imu.size > 1 && t[last] > t[0]) {
            (seq[last] - seq[0]).toDouble() / ((t[last] - t[0]).toDouble() / 1e6)
        } else {
            s.odrMhz.toDouble() / 1000
        }
        val missingFrames = audio?.gapFrames?.sumOf { it[1] - it[0] } ?: 0L
        val clippedFrac = if (audio != null) pyRound(clipped.toDouble() / (audio.frames * audio.channels).toDouble(), 6) else 0.0

        val settings = tail?.get("settings") as? JsonObject
        val levels = take.of<StreamRecord.Level>()
        val stats = take.of<StreamRecord.Stats>()
        val end = take.end
        val reason = end?.let { RECORD_REASONS.getOrNull(it.reason) ?: "unknown_${it.reason}" } ?: "unknown"
        val filled = imu.flags.count { it and ImuFlags.FILLED != 0 }
        val missing = seq[last] - seq[0] + 1 - imu.size
        val quality = buildList {
            if (missing != 0L || imu.transportGaps.isNotEmpty()) add("imu_gap")
            if (missingFrames != 0L) add("audio_gap")
            if (audio != null && clippedFrac > 0.001) add("audio_clip")
            if (end == null || reason !in listOf("user", "max_s")) add("truncated")
        }

        return buildJsonObject {
            put("schema", "drillbalancer.meta/1")
            put("taxonomy_version", Taxonomy.VERSION)
            put("recording_id", j.recordingId)
            put("session_id", j.sessionId)
            put("start_utc", j.startedUtc)
            put("device_t0_us", t[0])
            put("duration_s", pyRound((t[last] - t[0]).toDouble() / 1e6, 3))
            putJsonObject("device") {
                put("board", BOARD)
                put("fw_version", fwVersion)
                put("fw_git", fwGit)
                put("board_id", s.boardId)
            }
            putJsonObject("app") {
                put("version", takeCodePoints(j.app.version, 32))
                put("phone_model", takeCodePoints(j.app.phoneModel, 64))
                if (j.app.androidSdk >= 1) put("android_sdk", j.app.androidSdk)
            }
            putJsonObject("imu") {
                put("chip", "QMI8658")
                put("profile", PROFILES.getOrNull(s.profile) ?: "UNKNOWN_${s.profile}")
                put("odr_nominal_hz", s.odrMhz.toDouble() / 1000)
                put("odr_measured_hz", pyRound(odr, 3))
                put("accel_range_g", 32768 / s.accelLsbPerG)
                put("gyro_range_dps", 32768 / s.gyroLsbPerDps)
                put("accel_lsb_per_g", s.accelLsbPerG)
                put("gyro_lsb_per_dps", s.gyroLsbPerDps)
                put("accel_lpf", lpfText(s.ctrl5, accel = true))
                put("gyro_lpf", lpfText(s.ctrl5, accel = false))
                put("frame", IMU_FRAME_TEXT)
                put("axis_map", s.axis.zip("xyz".toList()).joinToString(",") { (a, n) -> "$n=${if (a.second > 0) '+' else '-'}c${"xyz"[a.first]}" })
                put("accel_cal", accelCal(cal?.get("accel")))
                put("mount", mountCal(cal?.get("mount")))
                put("gyro_bias_rad_s", (cal?.get("gyro_bias_rad_s") as? JsonArray)?.takeIf { it.size == 3 } ?: JsonNull)
                putJsonObject("registers") {
                    put("ctrl2", s.ctrl2)
                    put("ctrl3", s.ctrl3)
                    put("ctrl5", s.ctrl5)
                }
            }
            putJsonObject("take") {
                put("take_id", s.takeId)
                put("end_reason", if (reason in TAKE_REASONS) reason else "unknown")
                putJsonObject("stats") {
                    if (stats.isNotEmpty() && stats.last().ringSize != 0L) {
                        put("ring_high_water_pct", pyRound(100.0 * stats.maxOf { it.ringHighWater }.toDouble() / stats.last().ringSize.toDouble(), 2))
                    }
                    if (end != null) {
                        put("dropped_records", end.droppedRecords)
                        put("dropped_bytes", end.droppedBytes)
                        put("audio_overflows", end.audioOverflows)
                    } else if (stats.isNotEmpty()) {
                        put("dropped_records", stats.last().droppedRecords)
                        put("dropped_bytes", stats.last().droppedBytes)
                        put("audio_overflows", stats.last().audioOverflows)
                    }
                    val batt = stats.filter { it.powerFlags and RecStream.STATS_BATTERY_VALID != 0 }.map { it.batteryPct }
                    if (batt.isNotEmpty()) {
                        put("battery_pct_start", batt.first())
                        put("battery_pct_end", batt.last())
                    }
                }
            }
            if (audio == null) {
                put("audio", JsonNull)
                put("sync", JsonNull)
            } else {
                val fit = audioFit(audio, s)
                putJsonObject("audio") {
                    put("codec", "flac")
                    put("file", AUDIO)
                    put("rate_nominal_hz", s.audioRateHz)
                    put("rate_measured_hz", pyRound(fit.rateHz, 3))
                    put("bits", 16)
                    putJsonArray("channels") {
                        val mics = MICS.indices.filter { s.micMask and (1 shl it) != 0 }.map { MICS[it] }
                        val names = if (mics.size == audio.channels) mics else (1..audio.channels).map { "mic$it" }
                        names.forEach { add(JsonPrimitive(it)) }
                    }
                    put("adc", "ES7210")
                    put("gain_db", s.gainDb)
                    putJsonArray("muted_spans_us") {}
                    put("clipped_frac", clippedFrac)
                    if (s.audioBlockFrames != 0) put("block_frames", s.audioBlockFrames)
                }
                putJsonObject("sync") {
                    put("method", "esp_timer@dma_block")
                    put("fit", "t_us = a + b*frame")
                    put("a_us", pyRound(fit.a, 1))
                    put("b_us_per_frame", pyRound(fit.b, 6))
                    put("residual_rms_us", pyRound(fit.residualRms, 1))
                }
            }
            putJsonObject("gaps") {
                put("imu_filled_samples", filled)
                put("imu_missing_samples", missing)
                putJsonArray("transport_gaps") { imu.transportGaps.forEach { g -> addJsonArray { add(JsonPrimitive(g[0])); add(JsonPrimitive(g[1])) } } }
                put("audio_missing_frames", missingFrames)
                putJsonArray("audio_gap_frames") { audio?.gapFrames?.forEach { g -> addJsonArray { add(JsonPrimitive(g[0])); add(JsonPrimitive(g[1])) } } }
            }
            putJsonObject("level") {
                val mode = (settings?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content
                put(
                    "mode",
                    if (mode in LEVEL_MODES) mode else levels.firstOrNull()?.mode?.let { LEVEL_MODES.getOrNull(it) } ?: "wall",
                )
                put("target_deg", number(settings?.get("target")) ?: 0.0)
                put("tol_deg", number(settings?.get("tol")) ?: 0.5)
                if (settings?.containsKey("near") == true) number(settings["near"])?.let { put("near_deg", it) }
            }
            put("session", sessionLabels(j.setup))
            put("derived", kept?.derived ?: EMPTY_DERIVED)
            put("review", kept?.review ?: AUTO_REVIEW)
            putJsonObject("quality") { putJsonArray("flags") { quality.forEach { add(JsonPrimitive(it)) } } }
            putJsonObject("files") {
                for ((name, d) in files.toSortedMap()) {
                    putJsonObject(name) {
                        put("sha256", d.first)
                        put("bytes", d.second)
                    }
                }
            }
        }
    }

    companion object {
        const val META = RecordingStore.META
        const val EVENTS = "events.json"
        const val IMU = "imu.csv.gz"
        const val LEVEL = "level.csv.gz"
        const val AUDIO = "audio.flac"
        const val SYNC = "audio_sync.csv"
        const val DEVICE_EVENTS = "device_events.csv"

        /** The files of a recording in the dataset (what is uploaded). */
        val DATASET_FILES = listOf(META, EVENTS, IMU, LEVEL, AUDIO, SYNC, DEVICE_EVENTS)
        private val OPTIONAL_FILES = setOf(AUDIO, SYNC, DEVICE_EVENTS)

        val LEVEL_COLUMNS = listOf(
            "t_us", "mode", "flags", "elevation_cdeg", "yaw_cdeg", "bubble_x_cdeg", "bubble_y_cdeg", "deviation_cdeg",
            "zero_elevation_cdeg", "zero_bubble_x_cdeg", "zero_bubble_y_cdeg",
        )
        private val LEVEL_MODES = listOf("wall", "floor", "angle")          // level_mode_t order
        private val PROFILES = listOf("LEVEL", "RAW448", "RAW448_16G", "RAW897")
        private val MICS = listOf("mic1", "mic2")
        // REC_REASON_* in order; meta.take.end_reason takes the take reasons, the rest is "unknown"
        private val RECORD_REASONS = listOf("none", "user", "max_s", "client_lost", "ble_lost", "battery", "imu", "shutdown", "ap_fail", "no_client", "idle")
        private val TAKE_REASONS = setOf("user", "max_s", "client_lost", "ble_lost", "battery", "imu", "shutdown")
        private val LPF_PCT = listOf("2.66", "3.63", "5.39", "13.37")     // QMI8658 CTRL5 LPF modes, % of ODR
        const val BOARD = "waveshare-esp32-s3-touch-amoled-1.75"
        const val IMU_FRAME_TEXT = "board: X right, Y up the screen, Z out of the screen; bit along -Z"
        private val FW_RE = Regex("""^v?(\d+\.\d+\.\d+)(?:-(\d+))?(?:-g([0-9a-f]{4,40}))?(-dirty)?""")

        /** meta.derived before any labels. */
        val EMPTY_DERIVED: JsonObject = Derived.EMPTY.toJson()

        /** meta.review before the detector ran or anybody looked. */
        val AUTO_REVIEW: JsonObject = review(RecordingStore.AUTO_ONLY, "none", 0)

        /** meta.review, its keys in dbdata's order. */
        fun review(status: String, detector: String, revision: Int, editedEvents: Int = 0, reviewer: String? = null): JsonObject =
            buildJsonObject {
                put("status", status)
                put("detector", detector)
                put("revision", revision)
                put("edited_events", editedEvents)
                put("reviewer", reviewer?.let { JsonPrimitive(it) } ?: JsonNull)
            }

        /** The take whose SESSION has [takeId]; else the first with a SESSION; else the first. */
        internal fun pickTake(takes: List<Take>, takeId: Long): Take? =
            takes.firstOrNull { it.session?.takeId == takeId } ?: takes.firstOrNull { it.session != null } ?: takes.firstOrNull()

        /** SESSION.fw ("0.1.0-g0e5105e-dirty", or git describe "0.3.0-7-g0e5105e") -> (fw_version, fw_git). */
        fun fwParts(fw: String): Pair<String, String> {
            val m = FW_RE.find(fw) ?: return takeCodePoints(fw, 32) to ""
            val git = m.groups[3]?.value
            return m.groupValues[1] to if (git != null) git + (m.groups[4]?.value ?: "") else ""
        }

        fun lpfText(ctrl5: Int, accel: Boolean): String {
            val en = if (accel) ctrl5 and 1 else (ctrl5 shr 4) and 1
            val mode = if (accel) (ctrl5 shr 1) and 3 else (ctrl5 shr 5) and 3
            return if (en != 0) "${LPF_PCT[mode]}% of ODR" else "off"
        }

        /** Every chip axis once, each with sign +-1. */
        fun axisMapOk(axis: List<Pair<Int, Int>>): Boolean =
            axis.size == 3 && axis.all { it.second == 1 || it.second == -1 } && axis.map { it.first }.sorted() == listOf(0, 1, 2)

        /** meta.session from the take's setup (taxonomy ids; "other" without its text counts as unknown). */
        fun sessionLabels(setup: SessionSetup): JsonObject {
            val s = setup.sanitized()
            val material = Taxonomy.stored(s.material, s.materialOther)
            val bit = Taxonomy.stored(s.bitType, s.bitOther)
            val drillType = Taxonomy.stored(s.drillType, s.drillTypeOther)
            val motor = Taxonomy.stored(s.motor, s.motorOther)
            return buildJsonObject {
                put("material", material)
                s.thicknessMm?.let { mm ->
                    putJsonArray("layers") { add(buildJsonObject { put("material", material); put("thickness_mm", mm) }) }
                }
                put("mode", s.mode)
                putJsonObject("bit") {
                    put("type", bit)
                    put("diameter_mm", s.bitDiameterMm)
                }
                putJsonObject("drill") {
                    put("type", drillType)
                    put("motor", motor)
                    put("model", s.drillModel)
                    put("gear", s.gear)
                    put("speed_setting", s.speedSetting)
                }
                put("notes", s.notes)
                val other = buildJsonObject {
                    if (material == Taxonomy.OTHER) {
                        put("material", s.materialOther)
                        if (s.thicknessMm != null) put("layers[0]", s.materialOther)
                    }
                    if (bit == Taxonomy.OTHER) put("bit_type", s.bitOther)
                    if (drillType == Taxonomy.OTHER) put("drill_type", s.drillTypeOther)
                    if (motor == Taxonomy.OTHER) put("motor", s.motorOther)
                }
                if (other.isNotEmpty()) put("other_text", other)
            }
        }

        fun emptyEvents(recordingId: String, coverage: LongArray): JsonObject = Labels(recordingId, 0, coverage.toList()).toJson()

        /**
         * New labels for a finalized recording: events.json, and meta.json's derived and review
         * (the data files and their hashes are unchanged). Both are written and synced, then
         * renamed: events.json, meta.json. meta.review.revision is events.json's revision, so a
         * pair the app died between the renames of is seen, and finished by [repairLabels].
         */
        fun writeLabels(dir: File, events: JsonObject, derived: JsonObject, review: JsonObject) {
            val meta = readJson(File(dir, META)) ?: throw IOException("${dir.name} is not finalized")
            val updated = JsonObject(meta.mapValues { (k, v) -> if (k == "derived") derived else if (k == "review") review else v })
            val out = Staged(dir)
            try {
                out.write(EVENTS) { it.write(PyJson.dump(events).toByteArray()) }
                out.write(META) { it.write(PyJson.dump(updated).toByteArray()) }
                out.commit(emptySet())
            } finally {
                out.discard()
            }
        }

        /**
         * Finishes a [writeLabels] the app died in between its renames: events.json holds the new
         * labels, meta.json the derived and review from before (its meta.review.revision is not
         * events.json's), and meta.json.tmp the new meta.json, complete and synced before either
         * rename. That is renamed into place when it is the same meta.json but for derived and
         * review, with events.json's revision. Else a mute stopped the same way is finished
         * (AudioMute.finish: the muted audio.flac under the meta.json before it). True when it
         * repaired the pair. Blocking.
         */
        fun repairLabels(dir: File): Boolean {
            val tmp = File(dir, "$META.tmp")
            if (!tmp.isFile) return false          // nothing to finish (no file read: recover() runs it on every recording)
            return repairLabelPair(dir, tmp) || AudioMute.finish(dir)
        }

        private fun repairLabelPair(dir: File, tmp: File): Boolean {
            val events = readJson(File(dir, EVENTS)) ?: return false
            val meta = readJson(File(dir, META)) ?: return false
            val revision = events["revision"] as? JsonPrimitive ?: return false
            if (reviewRevision(meta) == revision) return false
            val next = readJson(tmp) ?: return false
            fun rest(o: JsonObject) = o.filterKeys { it != "derived" && it != "review" }
            if (reviewRevision(next) != revision || rest(next) != rest(meta)) return false
            if (!tmp.renameTo(File(dir, META))) throw IOException("cannot replace $META in ${dir.name}")
            return true
        }

        private fun reviewRevision(meta: JsonObject): JsonPrimitive? = (meta["review"] as? JsonObject)?.get("revision") as? JsonPrimitive

        internal fun readJson(f: File): JsonObject? = try {
            if (f.isFile) Json.parseToJsonElement(f.readText()) as? JsonObject else null
        } catch (_: Exception) {
            null
        }

        /** [f] as a JSON object; null when it is missing or holds none. A read that fails is an IOException (the storage's), unlike [readJson]. */
        internal fun readJsonStrict(f: File): JsonObject? {
            if (!f.isFile) return null
            val text = f.readText()
            return try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            }
        }

        /** meta.files' entry of [name]: its sha256 and bytes (null when it lists none). */
        internal fun fileEntry(meta: JsonObject, name: String): Pair<String, Long>? {
            val e = (meta["files"] as? JsonObject)?.get(name) as? JsonObject ?: return null
            val sha = (e["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val bytes = (e["bytes"] as? JsonPrimitive)?.longOrNull ?: return null
            return sha to bytes
        }

        /** [f]'s sha256, hex (meta.files' sha256). */
        internal fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.forEachBlock(1 shl 16) { buf, n -> md.update(buf, 0, n) }
            return md.digest().toHex()
        }

        private fun takeCodePoints(s: String, n: Int): String =
            if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

        private fun truthy(e: JsonElement?): Boolean = when (e) {
            null, JsonNull -> false
            is JsonPrimitive -> e.booleanOrNull ?: if (e.isString) e.content.isNotEmpty() else (e.doubleOrNull ?: 0.0) != 0.0
            is JsonArray -> e.isNotEmpty()
            is JsonObject -> e.isNotEmpty()
        }

        private fun number(e: JsonElement?): Double? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return p.content.trim().toDoubleOrNull()
            return p.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: p.doubleOrNull
        }

        private fun accelCal(e: JsonElement?): JsonElement {
            val acc = e as? JsonObject ?: return JsonNull
            val bias = acc["bias_g"] as? JsonArray ?: return JsonNull
            val scale = acc["scale"] as? JsonArray ?: return JsonNull
            return buildJsonObject {
                put("valid", truthy(acc["valid"]))
                put("bias_g", bias)
                put("scale", scale)
            }
        }

        private fun mountCal(e: JsonElement?): JsonElement {
            val m = e as? JsonObject ?: return JsonNull
            val axis = m["drill_axis"] as? JsonArray ?: return JsonNull
            return buildJsonObject {
                put("valid", truthy(m["valid"]))
                put("drill_axis", axis)
            }
        }
    }
}

/**
 * Files written next to their place (name.tmp, synced) and renamed over it on [commit], in
 * the order written; [discard] removes what was not committed, unless a commit renamed some:
 * the rest stay, complete, for Finalizer.repairLabels (or the next finalize) to finish. sha256
 * and size of each data file come along for meta.files.
 */
private class Staged(private val dir: File) {
    private val order = ArrayList<String>()
    private val digests = LinkedHashMap<String, Pair<String, Long>>()
    private var renamed = false

    private fun tmp(name: String) = File(dir, "$name.tmp")

    /** [name] written through [body], hashed as it is written. */
    fun write(name: String, body: (OutputStream) -> Unit) {
        val f = tmp(name)
        val md = MessageDigest.getInstance("SHA-256")
        FileOutputStream(f).use { o ->
            val b = DigestOutputStream(o, md).buffered(1 shl 16)
            body(b)
            b.flush()
            o.fd.sync()
        }
        staged(name, f) { md.digest().toHex() }
    }

    /** [name] written by [body] to the file itself (it may seek back), hashed by reading it again. */
    fun writeFile(name: String, body: (File) -> Unit) {
        val f = tmp(name)
        body(f)
        FileOutputStream(f, true).use { it.fd.sync() }
        staged(name, f) { Finalizer.sha256(f) }
    }

    private fun staged(name: String, f: File, sha: () -> String) {
        if (name != Finalizer.META && name != Finalizer.EVENTS) digests[name] = sha() to f.length()
        order += name
    }

    fun digests(): Map<String, Pair<String, Long>> = digests

    fun dataFiles(): List<String> = digests.keys.sorted()

    /** Renames the files into place (meta.json is written last), then removes [stale] ones. */
    fun commit(stale: Set<String>) {
        while (order.isNotEmpty()) {
            val n = order.first()
            if (!tmp(n).renameTo(File(dir, n))) {
                if (renamed) order.clear()
                throw IOException("cannot replace $n in ${dir.name}")
            }
            order.removeAt(0)
            renamed = true
        }
        for (n in stale) File(dir, n).delete()
    }

    fun discard() {
        for (n in order) tmp(n).delete()
        order.clear()
    }
}

/** [o] for a stream that closes what it wraps: close only flushes. */
internal class KeepOpen(private val o: OutputStream) : OutputStream() {
    override fun write(b: Int) = o.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
    override fun flush() = o.flush()
    override fun close() = o.flush()
}
