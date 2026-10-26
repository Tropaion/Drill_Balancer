package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.export.FlacReader
import dev.drillbalancer.recorder.export.LongList
import dev.drillbalancer.recorder.export.SyncFit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A recording folder of the dataset read back for the detector, as dbdata.io.read_recording and
 * autodetect.Inputs.from_recording read it: meta.json, events.json (its coverage), imu.csv.gz,
 * level.csv.gz, audio.flac (its first channel) and the sync fit through audio_sync.csv. The same
 * reader serves the app's own recordings and the Python-written golden fixtures.
 */
object RecordingFiles {
    class Recording(val meta: JsonObject, val labels: Labels?, val inputs: DetectInputs)

    fun read(dir: File, withAudio: Boolean = true): Recording {
        val meta = Finalizer.readJson(File(dir, Finalizer.META)) ?: throw IOException("${dir.name}: no meta.json")
        val labels = LabelFiles.readLabels(File(dir, Finalizer.EVENTS))
        val imuMeta = meta["imu"] as? JsonObject ?: throw IOException("${dir.name}: meta.imu missing")
        val imu = readImu(File(dir, Finalizer.IMU))
        if (imu.first.isEmpty()) throw IOException("${dir.name}: no IMU samples")
        val mount = imuMeta["mount"] as? JsonObject
        val axis = if ((mount?.get("valid") as? JsonPrimitive)?.booleanOrNull == true) {
            (mount["drill_axis"] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }?.takeIf { it.size == 3 }?.toDoubleArray()
        } else {
            null
        }
        val level = File(dir, Finalizer.LEVEL).takeIf { it.isFile }?.let(::readLevel)

        var audio: ShortArray? = null
        var rate = 16000.0
        var a = 0.0
        var b = 0.0
        val am = meta["audio"] as? JsonObject
        if (am != null) {
            val delay = num((meta["sync"] as? JsonObject)?.get("adc_delay_us")) ?: 0.0
            val syncFile = File(dir, Finalizer.SYNC)
            var fit: SyncFit? = null
            if (syncFile.isFile) {
                val (frame, t) = readSync(syncFile)
                if (frame.size >= 2) fit = SyncFit.fit(frame, t)
            }
            val sm = meta["sync"] as? JsonObject
            if (fit == null && sm != null) fit = SyncFit(num(sm["a_us"]) ?: 0.0, num(sm["b_us_per_frame"]) ?: 0.0)
            val name = (am["file"] as? JsonPrimitive)?.content ?: Finalizer.AUDIO
            val f = File(dir, name)
            if (withAudio && fit != null && f.isFile && name.endsWith(".flac")) {
                val ch = FlacReader.readChannel(f, 0)
                if (ch.samples.isNotEmpty()) {
                    audio = ch.samples
                    rate = num(am["rate_nominal_hz"]) ?: rate
                    a = fit.a - delay
                    b = fit.b
                }
            }
        }
        val gaps = ((meta["gaps"] as? JsonObject)?.get("audio_gap_frames") as? JsonArray).orEmpty().mapNotNull { g ->
            val r = (g as? JsonArray)?.map { it.jsonPrimitive.longOrNull ?: 0L } ?: return@mapNotNull null
            if (r.size == 2) longArrayOf(r[0], r[1]) else null
        }
        val id = (meta["recording_id"] as? JsonPrimitive)?.content ?: dir.name
        val t = imu.first
        val coverage = labels?.coverage?.toLongArray() ?: longArrayOf(t[0], t[t.size - 1] + 1)
        val tol = tolDeg(meta)
        val inputs = DetectInputs(
            t, imu.second, num(imuMeta["accel_lsb_per_g"]) ?: throw IOException("accel_lsb_per_g"),
            num(imuMeta["gyro_lsb_per_dps"]) ?: throw IOException("gyro_lsb_per_dps"),
            num(imuMeta["odr_nominal_hz"]) ?: throw IOException("odr_nominal_hz"),
            axis, audio, rate, a, b, level, coverage, id, tol, gaps,
        )
        return Recording(meta, labels, inputs)
    }

    /** level.csv.gz alone (the review's derived labels). */
    fun readLevel(f: File): LevelRows {
        val cols = listOf("t_us", "flags", "elevation_cdeg", "yaw_cdeg", "deviation_cdeg", "zero_elevation_cdeg")
        val rows = readCsv(gz(f), cols)
        return LevelRows(
            rows[0], rows[1].toInts(), rows[2].toInts(), rows[3].toInts(), rows[4].toInts(), rows[5].toInts(),
        )
    }

    private fun readImu(f: File): Pair<LongArray, ShortArray> {
        val rows = readCsv(gz(f), listOf("t_us", "ax", "ay", "az", "gx", "gy", "gz"))
        val n = rows[0].size
        val counts = ShortArray(n * 6)
        for (i in 0 until n) for (a in 0 until 6) counts[i * 6 + a] = rows[a + 1][i].toInt().toShort()
        return rows[0] to counts
    }

    private fun readSync(f: File): Pair<LongArray, LongArray> {
        val rows = readCsv(f.inputStream(), listOf("frame", "t_us"))
        return rows[0] to rows[1]
    }

    private fun gz(f: File): InputStream = GZIPInputStream(f.inputStream(), 1 shl 16)

    /** meta.level.tol_deg; infinite when there is none. */
    fun tolDeg(meta: JsonObject): Double = num((meta["level"] as? JsonObject)?.get("tol_deg")) ?: Double.POSITIVE_INFINITY

    private fun LongArray.toInts() = IntArray(size) { this[it].toInt() }

    private fun num(e: kotlinx.serialization.json.JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** The integer columns [want] of a CSV with a header (named columns, any order). */
    private fun readCsv(input: InputStream, want: List<String>): Array<LongArray> = input.use { ins ->
        val buf = ByteArray(1 shl 16)
        var pos = 0
        var limit = 0
        // the next byte (0..255), -1 at the end
        fun next(): Int {
            if (pos == limit) {
                limit = ins.read(buf)
                pos = 0
                if (limit <= 0) {
                    limit = 0
                    return -1
                }
            }
            return buf[pos++].toInt() and 0xFF
        }
        val header = readLine(::next) ?: throw IOException("empty CSV")
        val names = header.split(',').map { it.trim() }
        val idx = want.map { w -> names.indexOf(w).also { if (it < 0) throw IOException("CSV lacks column $w") } }
        val cols = Array(want.size) { LongList() }
        val fields = LongArray(names.size)
        var field = 0
        var v = 0L
        var neg = false
        var any = false
        var lineHas = false
        while (true) {
            val c = next()
            if (c < 0 || c == '\n'.code) {
                if (lineHas) {
                    if (field < fields.size) fields[field] = if (neg) -v else v
                    if (field + 1 != names.size) throw IOException("CSV row with ${field + 1} fields")
                    for (j in idx.indices) cols[j].add(fields[idx[j]])
                }
                if (c < 0) break
                field = 0
                v = 0
                neg = false
                any = false
                lineHas = false
                continue
            }
            when (c) {
                ','.code -> {
                    if (field < fields.size) fields[field] = if (neg) -v else v
                    field++
                    v = 0
                    neg = false
                    any = false
                }
                '-'.code -> {
                    if (any) throw IOException("bad number in CSV")
                    neg = true
                    lineHas = true
                }
                '\r'.code -> Unit
                in '0'.code..'9'.code -> {
                    v = v * 10 + (c - '0'.code)
                    any = true
                    lineHas = true
                }
                else -> throw IOException("not an integer CSV")
            }
        }
        Array(want.size) { cols[it].toArray() }
    }

    private fun readLine(next: () -> Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = next()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
