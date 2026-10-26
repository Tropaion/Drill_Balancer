package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.export.lowerBound
import dev.drillbalancer.recorder.export.pyRound
import kotlin.math.abs
import kotlin.math.sqrt

/** A point event the detector found (time in device us). */
data class DetEvent(val type: String, val tUs: Long, val confidence: Double, val hole: Int? = null)

/** A span the detector found. */
data class DetSpan(val type: String, val t0Us: Long, var t1Us: Long, val confidence: Double, val hole: Int? = null, val reason: String? = null)

/** The detector's hole: through (null: the take ends in it), its outcome tags. */
class DetHole(var through: Boolean? = null, val tags: MutableList<String> = ArrayList(), var ham: Double? = null)

/** What [AutoDetect.detect] found: events.json's labels, meta.derived, and the features (the review's lanes). */
class Detection(val labels: Labels, val derived: Derived, val features: Features)

/**
 * The auto-detector, a one-to-one port of dbdata.autodetect (detector 1.1.0): its module
 * docstring is the specification, and AutoDetectVectorsTest replays the golden fixtures
 * (firmware/tools/dataset/fixtures/autodetect). Pure and deterministic: the same inputs and
 * parameters give the same labels.
 */
object AutoDetect {
    fun detect(inp: DetectInputs, params: LoadedParams): Detection {
        val f = FeatureExtractor.compute(inp, params.p)
        val det = Rules(inp, f, params.p)
        det.run()
        val labels = labelsOf(det, inp, params)
        var out = labels
        var derived = Derived.EMPTY
        if (inp.level != null) {
            val (d, holes) = Derived.compute(inp.level, labels, inp.tolDeg)
            derived = d
            out = labels.copy(holes = labels.holes.map { h -> holes[h.hole]?.let { h.copy(offAngleDeg = it.offAngleDeg) } ?: h })
        }
        return Detection(out, derived, f)
    }

    /** events.json of the detection: ids e1.. / s1.. in time order, holes numbered by contact. */
    private fun labelsOf(det: Rules, inp: DetectInputs, params: LoadedParams): Labels {
        val c0 = inp.coverage[0]
        val c1 = inp.coverage[1]
        val evs = det.events.filter { it.tUs in c0 until c1 }.sortedWith(compareBy<DetEvent> { it.tUs }.thenBy { it.type })
        val events = evs.mapIndexed { i, e ->
            Marker("e${i + 1}", e.type, e.tUs, e.hole?.takeIf { it != 0 }, Source.AUTO, conf(e.confidence))
        }
        val spans = ArrayList<SpanLabel>()
        for (s in det.spans.sortedWith(compareBy<DetSpan> { it.t0Us }.thenBy { it.type })) {
            val a = maxOf(c0, s.t0Us)
            val b = minOf(c1, s.t1Us)
            if (b <= a) continue
            spans += SpanLabel("s${spans.size + 1}", s.type, a, b, s.hole?.takeIf { it != 0 }, Source.AUTO, conf(s.confidence), reason = s.reason)
        }
        val used = (events.mapNotNull { it.hole } + spans.mapNotNull { it.hole }).toSortedSet()
        val holes = used.map { h ->
            val info = det.holes[h] ?: DetHole()
            HoleLabel.entry(h, info.through, info.tags.toSortedSet().toList())
        }
        return Labels(
            recordingId = inp.recordingId, revision = 1, coverage = longArrayOf(c0, c1).toList(),
            detector = DetectorInfo(params.p.detector, params.sha256), events = events, spans = spans, holes = holes,
        )
    }

    /** round(min(1, max(0, x)), 3) with Python's min/max (NaN gives 0). */
    private fun conf(x: Double): Double {
        val a = if (x > 0.0) x else 0.0
        return pyRound(if (a < 1.0) a else 1.0, 3)
    }
}

private const val OFF = 0
private const val ON = 1
private const val HOLE = 2
private const val HOLE_OFF = 3

/** A motor run: frames [on, off) (off null: still running at the end). */
private class Run(val on: Int, var off: Int?, val onConf: Double, var offConf: Double = 0.0)

private class Ref(val f: Double, val c: Double, val e: Double, val t: Double, val h: Double)

private class Level(val f: Double, val c: Double, val h: Double, val e: Double)

/** The contact ratios of one frame (absent: not available). */
private class Ratios {
    var f0 = Double.NaN
    var cut = Double.NaN
    var imu = Double.NaN
    var thrust = Double.NaN
    var hammer = Double.NaN
}

/** autodetect._Detector: sections 5-12 over the features. */
private class Rules(val inp: DetectInputs, val f: Features, val p: DetectParams) {
    val fm = p.frameMs
    val k = f.n
    val events = ArrayList<DetEvent>()
    val spans = ArrayList<DetSpan>()
    val holes = HashMap<Int, DetHole>()
    val hasAudio = f.hasAudio
    val s = f[Feature.S]
    val f0 = f[Feature.F0]
    val voiced = BooleanArray(k) { f0[it].isFinite() }
    val eImuS = centredMedian(f[Feature.E_IMU], p.features.smoothEFrames)
    val eAudS = centredMedian(f[Feature.E_AUD], p.features.smoothEFrames)
    val eStall = centredMedian(f[Feature.E_IMU], p.stuck.smoothFrames)
    val ziS = centredMedian(f[Feature.Z_IMU], p.features.smoothZFrames)

    fun nf(ms: Double) = msFrames(ms, fm)

    // ---- 5. motor runs ----
    fun motorRuns(): List<Run> {
        val mp = p.motor
        val nOn = nf(mp.onMs)
        val nOff = nf(mp.offMs)
        val runs = ArrayList<Run>()
        var on = false
        var cnt = 0
        var lower = 0
        var cur: Run? = null
        for (i in 0 until k) {
            if (!s[i].isFinite()) continue           // no data (a transport gap): the counts stand
            if (!on) {
                cnt = if (s[i] > mp.onZ) cnt + 1 else 0
                if (cnt == nOn) {
                    val kd = i - nOn + 1
                    var o = kd
                    while (o > lower && s[o - 1] > mp.backZ) o--
                    var conf = sigmoid((med(s, kd, kd + nOn) - mp.onZ) / mp.confScaleZ)
                    if (o == 0) conf = p.confidence.openMotorOn
                    cur = Run(o, null, conf)
                    on = true
                    cnt = 0
                }
            } else {
                cnt = if (s[i] < mp.offZ) cnt + 1 else 0
                if (cnt == nOff) {
                    val kd = i - nOff + 1
                    val r = cur!!
                    r.off = kd
                    r.offConf = sigmoid((med(s, maxOf(r.on, kd - nf(mp.confMs)), kd) - mp.offZ) / mp.confScaleZ)
                    runs += r
                    cur = null
                    on = false
                    cnt = 0
                    lower = i + 1
                }
            }
        }
        cur?.let { runs += it }
        // the board is clipped to the drill: a run the IMU never confirms is sound alone
        if (ziS.any { it.isFinite() }) {
            return runs.filter { r ->
                val m = nanMax(ziS, r.on, r.off ?: k)
                !m.isNaN() && m >= mp.onZ
            }
        }
        return runs
    }

    fun motorDisagree(run: Run): Boolean {
        if (!hasAudio) return false
        val a = run.on
        val b = minOf(k, run.on + nf(p.motor.confMs))
        val zi = med(f[Feature.Z_IMU], a, b)
        val za = med(f[Feature.Z_AUD], a, b)
        val on = p.motor.onZ
        return !(zi.isFinite() && za.isFinite()) || (zi < on) != (za < on)
    }

    fun motorOffTime(run: Run): Int {
        val mp = p.motor
        val kd = run.off!!
        val lo = maxOf(run.on + 1, kd - nf(mp.offSearchMs))
        val hold = nf(mp.offHoldMs)
        val onZ = mp.onZ
        val w0 = maxOf(run.on, kd - nf(mp.confMs))
        val loudAud = hasAudio && med(f[Feature.Z_AUD], w0, kd) >= med(f[Feature.Z_IMU], w0, kd)
        val e = if (loudAud) eAudS else eImuS
        val level = med(e, run.on, kd) - mp.offLevelDb
        var kEn = kd
        var i = kd
        while (i >= lo) {
            val from = maxOf(0, i - hold)
            var finite = 0
            for (j in from until i) if (e[j].isFinite()) finite++
            if (s[i] >= onZ && e[i].isFinite() && e[i] >= level && finite >= 3 && e[i] >= med(e, from, i) - mp.offDropDb) {
                kEn = minOf(kd, i + 1)
                break
            }
            i--
        }
        i = kEn
        val stop = maxOf(lo, kEn - nf(mp.offF0Ms))
        while (i >= stop) {
            if (voiced[i] && s[i] >= onZ) {
                val from = maxOf(0, i - hold)
                var finite = 0
                for (j in from until i) if (f0[j].isFinite()) finite++
                if (finite >= 3 && f0[i] >= (1.0 - mp.offF0Drop) * med(f0, from, i)) return minOf(kEn, i + 1)
            }
            i--
        }
        return kEn
    }

    // ---- 6. spin-up end and references ----
    fun spinupEnd(run: Run, end: Int): Int {
        val sp = p.spinup
        val n = nf(sp.stableMs)
        val e = f[Feature.E_IMU]
        for (i in run.on + nf(sp.minMs) until minOf(end, run.on + nf(sp.maxMs))) {
            val hi = minOf(k, i + n)
            if (hasAudio) {
                var cnt = 0
                var mx = Double.NEGATIVE_INFINITY
                var mn = Double.POSITIVE_INFINITY
                for (j in i until hi) if (f0[j].isFinite()) {
                    cnt++
                    mx = maxOf(mx, f0[j])
                    mn = minOf(mn, f0[j])
                }
                if (cnt >= sp.voicedFrac * n && mx / mn - 1.0 <= sp.stableF0) return i
            } else {
                if (hi - i != n) continue
                var all = true
                var mx = Double.NEGATIVE_INFINITY
                var mn = Double.POSITIVE_INFINITY
                for (j in i until hi) {
                    if (!e[j].isFinite()) all = false
                    mx = maxOf(mx, e[j])
                    mn = minOf(mn, e[j])
                }
                if (all && mx - mn <= sp.stableDb) return i
            }
        }
        return minOf(end, run.on + nf(sp.fallbackMs))
    }

    fun references(kSu: Int, end: Int): Ref {
        val b = minOf(end, kSu + nf(p.spinup.refMs))
        return Ref(med(f0, kSu, b), med(f[Feature.E_CUT], kSu, b), med(f[Feature.E_IMU], kSu, b), med(f[Feature.THRUST], kSu, b), med(f[Feature.HAM], kSu, b))
    }

    // ---- 7. contact score ----
    fun contactRatios(ref: Ref, i: Int): Ratios {
        val cp = p.contact
        val rm = cp.ratioMax
        val r = Ratios()
        fun clip(v: Double) = if (v.isFinite()) maxOf(0.0, minOf(rm, v)) else Double.NaN
        if (voiced[i] && ref.f > 0) r.f0 = clip((1.0 - f0[i] / ref.f) / cp.f0Drop)
        r.cut = clip((f[Feature.E_CUT][i] - ref.c) / cp.cutDb)
        if (f[Feature.E_IMU][i].isFinite()) {
            r.imu = clip((f[Feature.E_IMU][i] - ref.e) / cp.imuDb)
            r.thrust = clip((ref.t - f[Feature.THRUST][i]) / cp.thrustG)
            r.hammer = clip((f[Feature.HAM][i] - ref.h) / cp.hammer)
        }
        return r
    }

    fun scoreOf(r: Ratios): Double {
        val w = p.contact.weights
        fun mix(vararg parts: Pair<Double, Double>): Double {
            var ws = 0.0
            for ((x, wt) in parts) if (!x.isNaN()) ws += wt
            if (ws <= 0) return 0.0
            var sum = 0.0
            for ((x, wt) in parts) if (!x.isNaN()) sum += wt * x
            return sum / ws
        }
        val drill = mix(r.f0 to w.f0, r.cut to w.cut, r.imu to w.imu, r.thrust to w.thrust)
        val ham = mix(r.hammer to w.hammer, r.cut to w.cut)
        return maxOf(drill, ham)
    }

    fun scores(ref: Ref, k0: Int, k1: Int): Pair<DoubleArray, List<Ratios>> {
        val rs = (k0 until k1).map { contactRatios(ref, it) }
        val sc = DoubleArray(rs.size) { scoreOf(rs[it]) }
        return (if (sc.isNotEmpty()) centredMedian(sc, p.contact.smoothFrames) else sc) to rs
    }

    class Contact(val frame: Int, val conf: Double, val disagree: Boolean)

    fun findContact(ref: Ref, k0: Int, k1: Int): Contact? {
        val cp = p.contact
        val (sm, rs) = scores(ref, k0, k1)
        val n = nf(cp.holdMs)
        var cnt = 0
        for (i in sm.indices) {
            cnt = if (sm[i] > cp.score) cnt + 1 else 0
            if (cnt == n) {
                val first = i - n + 1
                val plateau = med(sm, first, first + n)
                var j = first
                while (j > 0 && sm[j - 1] > cp.backFrac * plateau) j--
                val aud = ArrayList<Double>()
                val imu = ArrayList<Double>()
                for (r in rs.subList(first, first + n)) {
                    val a = listOf(r.f0, r.cut).filter { !it.isNaN() }
                    if (a.isNotEmpty()) aud += mean(a.toDoubleArray())
                    if (!r.imu.isNaN()) imu += mean(listOf(r.imu, r.thrust, r.hammer).filter { !it.isNaN() }.toDoubleArray())
                }
                val aM = if (aud.isNotEmpty()) mean(aud.toDoubleArray()) else null
                val iM = if (imu.isNotEmpty()) mean(imu.toDoubleArray()) else null
                val disagree = aM != null && iM != null && ((aM >= 1.0 && iM < 0.5) || (iM >= 1.0 && aM < 0.5))
                val conf = sigmoid((plateau - cp.score) / cp.confScale)
                return Contact(k0 + j, conf, disagree)
            }
        }
        return null
    }

    // ---- 8. load end and stuck ----
    fun levels(i: Int): Level {
        val lp = p.loadEnd
        val a = maxOf(0, i - nf(lp.levelGapMs) - nf(lp.levelMs))
        val b = maxOf(a, i - nf(lp.levelGapMs))
        return Level(med(f0, a, b), med(f[Feature.E_CUT], a, b), med(f[Feature.HAM], a, b), med(eStall, a, b))
    }

    fun recovery(ref: Ref, lv: Level, i: Int): Double {
        val lp = p.loadEnd
        val rs = ArrayList<Double>(3)
        if (voiced[i] && ref.f.isFinite() && lv.f.isFinite() && (ref.f - lv.f) / ref.f >= lp.minSag) {
            rs += (f0[i] - lv.f) / (ref.f - lv.f)
        }
        val cut = f[Feature.E_CUT][i]
        if (cut.isFinite() && ref.c.isFinite() && lv.c.isFinite() && lv.c - ref.c >= lp.minCutDb) {
            rs += (lv.c - cut) / (lv.c - ref.c)
        }
        // only while hammering: a drilling level just above a free run whose ham came out negative is no hammer
        val h2 = p.contact.hammer / 2
        if (ref.h.isFinite() && lv.h.isFinite() && lv.h >= h2 && lv.h - ref.h >= h2) {
            rs += (lv.h - f[Feature.HAM][i]) / (lv.h - ref.h)
        }
        return if (rs.isNotEmpty()) mean(rs.toDoubleArray()) else Double.NaN
    }

    fun stuckRatio(ref: Ref, lv: Level, i: Int, back: Boolean = false): Double {
        val stp = p.stuck
        if (s[i] < p.motor.onZ) return 0.0
        var r = 0.0
        val e = eStall[i]
        if (voiced[i]) {
            if (back && lv.f.isFinite()) {
                r = (1.0 - f0[i] / lv.f) / stp.backDrop
            } else if (!back && ref.f.isFinite()) {
                r = (1.0 - f0[i] / ref.f) / stp.f0Drop
            }
        } else if (!back && ref.e.isFinite() && e.isFinite()) {
            r = (ref.e - e) / stp.imuDb
        }
        if (back && lv.e.isFinite() && e.isFinite()) r = pyMax(r, (lv.e - e) / stp.backDb)
        return r
    }

    /** Python's max(a, b): a unless b > a (a NaN a stays). */
    private fun pyMax(a: Double, b: Double) = if (b > a) b else a

    class End(val through: Boolean, val tUs: Long, val conf: Double)

    fun classifyEnd(kc: Int, kr: Int, ke: Int, hole: Int): End {
        val lp = p.loadEnd
        val w0 = maxOf(kc + 1, kr - nf(lp.windowMs[0]))
        val w1 = minOf(k, ke + nf(lp.windowMs[1]) + 1)
        val b0 = minOf(kc + nf(p.contact.holdMs), w0 - 1)
        var bA = maxOf(kc, b0)
        var bB = w0
        if (bB - bA < 3) {
            bA = kc
            bB = w0
        }
        val imp = f[Feature.IMP]
        val gyro = f[Feature.GYRO_RAD]
        val flux = f[Feature.FLUX]
        val ham = f[Feature.HAM]
        val impB = med(imp, bA, bB)
        val h = listOfNotNull(med(ham, bA, bB).takeIf { it.isFinite() }, holes.getValue(hole).ham?.takeIf { it.isFinite() })
        val hammering = (h.maxOrNull() ?: Double.NEGATIVE_INFINITY) >= p.contact.hammer / 2
        var rImp = Double.NaN
        var rGyro = Double.NaN
        var rFlux = Double.NaN
        if (impB.isFinite() && anyFinite(imp, w0, w1)) {
            if (!hammering) rImp = (nanMax(imp, w0, w1) - impB) / lp.impG
            val gb = med(gyro, bA, bB)
            rGyro = (nanMax(gyro, w0, w1) - gb) / lp.gyroDps
        }
        val fb = med(flux, bA, bB)
        if (fb.isFinite() && fb > 0 && anyFinite(flux, w0, w1)) rFlux = (nanMax(flux, w0, w1) / fb - 1.0) / (lp.fluxRatio - 1.0)
        if (!rImp.isFinite()) rImp = Double.NaN
        if (!rGyro.isFinite()) rGyro = Double.NaN
        if (!rFlux.isFinite()) rFlux = Double.NaN
        val present = listOf(rImp, rGyro, rFlux).filter { !it.isNaN() }
        val ri = present.maxOrNull() ?: 0.0
        val through = ri >= 1.0
        val imuR = listOf(rImp, rGyro).filter { !it.isNaN() }.maxOrNull()
        val audR = rFlux.takeIf { !it.isNaN() }
        val disagree = imuR != null && audR != null && (imuR >= 1.0) != (audR >= 1.0)
        val dis = if (disagree) p.confidence.disagree else 1.0
        if (!through) return End(false, f.t(kr), sigmoid((1.0 - ri) / lp.confScale) * dis)
        var tImu: Long? = null
        var tAud: Long? = null
        if (!rImp.isNaN() && rImp >= 1.0) {
            val i0 = lowerBound(f.imuT, f.t(w0))
            val i1 = lowerBound(f.imuT, f.t(w1))
            if (i1 > i0) {
                var pk = i0
                for (i in i0 until i1) if (f.impS[i] > f.impS[pk]) pk = i
                val thr = impB + lp.impOnsetFrac * (f.impS[pk] - impB)
                var first = i0
                for (i in i0..pk) if (f.impS[i] > thr) {
                    first = i
                    break
                }
                tImu = f.imuT[first]
            }
        }
        if (audR != null && audR >= 1.0 && f.audio != null) {
            var kf = -1
            for (i in w0 until w1) if (!flux[i].isNaN() && (kf < 0 || flux[i] > flux[kf])) kf = i
            tAud = audioOnset(f.t(kf) - lp.audioSearchMs[0] * 1000, f.t(kf) + lp.audioSearchMs[1] * 1000)
        }
        val t = if (tAud != null && tImu != null) {
            if (abs(tAud - tImu) <= lp.audioImuAgreeMs * 1000) tAud else tImu
        } else {
            tImu ?: tAud ?: f.t(kr)
        }
        return End(true, t, sigmoid((ri - 1.0) / lp.confScale) * dis)
    }

    fun audioOnset(tLo: Double, tHi: Double): Long? {
        val lp = p.loadEnd
        val x = f.audio ?: return null
        val iLo = rnd(f.audioIndex(tLo))
        val iHi = rnd(f.audioIndex(tHi))
        val b0 = iLo - rnd(lp.audioBaseMs * 1000 / f.syncBUs)
        if (b0 < 0 || iHi > x.size || iHi <= iLo) return null
        if (b0 >= iLo) return null          // an empty base: its mean is NaN, nothing exceeds it
        val sq = DoubleArray((iLo - b0).toInt()) { val v = f.x((b0 + it).toInt()); v * v }
        val base = sqrt(mean(sq))
        if (base <= 0) return null
        val thr = lp.audioOnsetRatio * base
        for (i in iLo until iHi) if (abs(f.x(i.toInt())) > thr) return rnd(f.audioT(i.toDouble()))
        return null
    }

    // ---- the walk over the runs ----
    fun run() {
        val cp = p.contact
        val runs = motorRuns()
        var state = OFF
        var hole = 0
        var ref: Ref? = null
        var kc = 0
        for (run in runs) {
            val off = if (run.off != null) motorOffTime(run) else null
            val end = off ?: k
            val dis = if (motorDisagree(run)) p.confidence.disagree else 1.0
            var kSu = spinupEnd(run, end)
            if (state == HOLE_OFF) {
                // a restart in the hole spins up into the load: its f0 need not settle
                kSu = minOf(kSu, run.on + nf(p.restart.spinupMs), end)
                val (sm, _) = scores(ref!!, kSu, minOf(end, kSu + nf(p.restart.checkMs)))
                if (sm.isNotEmpty() && med(sm) > p.restart.score) {
                    state = HOLE
                    kc = kSu
                } else {
                    events += DetEvent("retract", f.t(run.on - 1), p.confidence.restartRetract, hole)
                    holes.getValue(hole).through = false
                    state = OFF
                }
            }
            events += DetEvent("motor_on", f.t(run.on), run.onConf * dis)
            if (state == OFF) {
                state = ON
                ref = references(kSu, end)
            }
            var i = kSu
            while (i < end) {
                if (state == ON) {
                    val hit = findContact(ref!!, i, end) ?: break
                    kc = hit.frame
                    hole++
                    holes[hole] = DetHole()
                    val conf = hit.conf * if (hit.disagree) p.confidence.disagree else 1.0
                    events += DetEvent("contact", f.t(kc), conf, hole)
                    state = HOLE
                    i = kc + nf(cp.holdMs)
                    continue
                }
                i = inHole(ref!!, kc, i, end, hole) ?: break
                state = ON
            }
            if (off != null) {
                events += DetEvent("motor_off", f.t(off), run.offConf * dis)
                run.off = off
                state = if (state == HOLE) HOLE_OFF else OFF
            }
        }
        kickbacks(runs)
        wobble()
        wander()
        speech(runs)
    }

    /** The drilling frames from i: the frame after a load end, or null (the run ends in the hole). */
    fun inHole(ref: Ref, kc: Int, start: Int, end: Int, hole: Int): Int? {
        val lp = p.loadEnd
        val stp = p.stuck
        val nEnd = nf(lp.holdMs)
        val nSt = nf(stp.holdMs)
        var cntEnd = 0
        var cntSt = 0
        var stuckFrom = -1
        var lv0: Level? = null
        var i = start
        while (i < end) {
            if (stuckFrom >= 0) {
                cntSt = if (!(stuckRatio(ref, lv0!!, i) > 1.0)) cntSt + 1 else 0
                if (cntSt == nSt) {
                    spans[stuckFrom].t1Us = f.t(i - nSt + 1)
                    stuckFrom = -1
                    cntSt = 0
                }
                i++
                continue
            }
            val lv = levels(i)
            cntSt = if (stuckRatio(ref, lv, i) > 1.0) cntSt + 1 else 0
            if (cntSt == nSt) {
                var ks = i - nSt + 1
                val l0 = levels(ks)
                lv0 = l0
                while (ks > kc + 1 && stuckRatio(ref, l0, ks - 1, back = true) > 1.0) ks--
                val x = med(DoubleArray(nSt) { stuckRatio(ref, l0, i - nSt + 1 + it) })
                val conf = sigmoid((x - 1.0) / stp.confScale)
                spans += DetSpan("stuck", f.t(ks), f.t(end), conf, hole)
                val h = holes.getValue(hole)
                h.tags += "bit_stuck"
                if (h.ham == null) h.ham = med(f[Feature.HAM], kc + nf(p.contact.holdMs), ks)
                stuckFrom = spans.size - 1
                cntSt = 0
                cntEnd = 0
                i++
                continue
            }
            // a stalling motor is no load end, though the cutting and the blows stop with it
            val rv = recovery(ref, lv, i)
            cntEnd = if (cntSt == 0 && rv.isFinite() && rv >= lp.recover) cntEnd + 1 else 0
            if (cntEnd == nEnd) {
                val ke = i - nEnd + 1
                val lvE = levels(ke)
                var kr = ke
                while (kr > kc + 1) {
                    val r = recovery(ref, lvE, kr - 1)
                    if (!(r.isFinite() && r > lp.start)) break
                    kr--
                }
                val e = classifyEnd(kc, kr, ke, hole)
                events += DetEvent(if (e.through) "breakthrough" else "retract", e.tUs, e.conf, hole)
                holes.getValue(hole).through = e.through
                return ke + nEnd
            }
            i++
        }
        return null
    }

    // ---- 11. kickback ----
    fun kickbacks(runs: List<Run>) {
        val kp = p.kickback
        val roll = f[Feature.ROLL]
        val n = nf(kp.rollMs)
        val refr = nf(kp.refractoryMs)
        var last = -1_000_000_000
        for (run in runs) {
            val end = run.off ?: k
            for (i in run.on until end) {
                if (i - last < refr || !roll[i].isFinite()) continue
                var sum = 0.0
                for (j in maxOf(run.on, i - n + 1)..i) if (!roll[j].isNaN()) sum += roll[j]
                val integ = sum * fm / 1000.0
                val x = maxOf(abs(roll[i]) / kp.rateDps, abs(integ) / kp.rollDeg)
                if (x <= 1.0) continue
                val sign = if ((if (abs(roll[i]) / kp.rateDps > 1.0) roll[i] else integ) > 0) 1.0 else -1.0
                var j = i
                while (j > run.on && roll[j - 1].isFinite() && sign * roll[j - 1] > kp.backDps) j--
                val i0 = lowerBound(f.imuT, f.t(j))
                val i1 = lowerBound(f.imuT, f.t(i + 1))
                var t = f.t(j)
                for (q in i0 until i1) if (sign * f.rollS[q] > kp.backDps) {
                    t = f.imuT[q]
                    break
                }
                var pk = Double.NaN
                for (q in i until minOf(end, i + n)) {
                    val v = abs(roll[q])
                    if (!v.isNaN() && (pk.isNaN() || v > pk)) pk = v
                }
                val peak = pyMax(pk / kp.rateDps, x)
                val h = holeAt(t)
                events += DetEvent("kickback", t, sigmoid((peak - 1.0) / kp.confScale), h)
                if (h != null && h != 0) holes.getValue(h).tags += "kickback"
                last = i
            }
        }
    }

    /** The hole the bit is in at tUs, or the one it left at most hole_after_ms before. */
    fun holeAt(tUs: Long): Int? {
        var h: Int? = null
        var left: Long? = null
        for (e in events.filter { it.type != "kickback" }.sortedBy { it.tUs }) {
            if (e.tUs > tUs) break
            if (e.type == "contact") {
                h = e.hole
                left = null
            } else if ((e.type == "breakthrough" || e.type == "retract") && h != null) {
                left = e.tUs
            }
        }
        if (h == null || (left != null && tUs - left > p.kickback.holeAfterMs * 1000)) return null
        return h
    }

    /** Per frame: in a hole with the motor on, and the hole number. */
    fun drillingMask(): Pair<BooleanArray, IntArray> {
        val mask = BooleanArray(k)
        val hole = IntArray(k)
        val evs = events.filter { it.type != "kickback" }.sortedBy { it.tUs } + DetEvent("end", f.t(k), 0.0)
        var cur: Int? = null
        var on = false
        var tPrev = f.t0Us
        for (e in evs) {
            if (cur != null && on) {
                val a = maxOf(0L, Math.floorDiv(tPrev - f.t0Us, f.frameUs)).toInt()
                val b = minOf(k.toLong(), Math.floorDiv(e.tUs - f.t0Us, f.frameUs)).toInt()
                for (q in a until b) {
                    mask[q] = true
                    hole[q] = cur
                }
            }
            tPrev = e.tUs
            when (e.type) {
                "contact" -> {
                    cur = e.hole
                    on = true
                }
                "breakthrough", "retract" -> cur = null
                "motor_off" -> on = false
                "motor_on" -> on = true
            }
        }
        return mask to hole
    }

    // ---- 12. suggestions ----
    fun wobble() {
        val wp = p.wobble
        val (mask, hole) = drillingMask()
        val wob = f[Feature.WOB]
        val hi = BooleanArray(k) { mask[it] && wob[it] > wp.rmsDps }
        val n = nf(wp.minMs)
        var i = 0
        while (i < k) {
            if (!hi[i]) {
                i++
                continue
            }
            var j = i
            while (j < k && hi[j] && hole[j] == hole[i]) j++
            if (j - i >= n) {
                val conf = wp.confidenceMax * sigmoid((med(wob, i, j) / wp.rmsDps - 1.0) / wp.confScale)
                spans += DetSpan("wobble", f.t(i), f.t(j), conf, hole[i])
            }
            i = j
        }
    }

    fun wander() {
        val wp = p.wander
        val lvl = inp.level ?: return
        if (lvl.size == 0) return
        val rows = (0 until lvl.size).filter { lvl.imuOk(it) }
        if (rows.isEmpty()) return
        val t = LongArray(rows.size) { lvl.tUs[rows[it]] }
        val el = DoubleArray(rows.size) { lvl.absoluteElevationDeg(rows[it]) }
        val yaw = DoubleArray(rows.size) { lvl.yawCdeg[rows[it]] / 100.0 }
        val evs = events.sortedBy { it.tUs }
        for ((idx, e) in evs.withIndex()) {
            if (e.type != "contact") continue
            var tEnd = e.tUs + wp.afterContactMs * 1000
            for (x in evs.subList(idx + 1, evs.size)) {
                if (x.type == "breakthrough" || x.type == "retract" || x.type == "motor_off") {
                    tEnd = minOf(tEnd, x.tUs.toDouble())
                    break
                }
            }
            val sel = t.indices.filter { t[it] >= e.tUs && t[it] < tEnd }
            val a = Math.floorDiv(e.tUs - f.t0Us, f.frameUs).toInt()
            val b = Math.floor((tEnd - f.t0Us) / f.frameUs).toInt()
            // the device level is no reference while hammer blows shake it
            if (sel.size < 2 || med(f[Feature.HAM], a, b) >= p.contact.hammer / 2) continue
            var drift = Double.NEGATIVE_INFINITY
            val e0 = el[sel[0]]
            val y0 = yaw[sel[0]]
            var de = Double.NEGATIVE_INFINITY
            var dy = Double.NEGATIVE_INFINITY
            for (q in sel) {
                de = maxOf(de, abs(el[q] - e0))
                dy = maxOf(dy, abs(pyMod(yaw[q] - y0 + 180.0, 360.0) - 180.0))
            }
            drift = maxOf(de, dy)
            if (drift > wp.deg) {
                val conf = wp.confidenceMax * sigmoid((drift / wp.deg - 1.0) / wp.confScale)
                spans += DetSpan("wander", e.tUs, tEnd.toLong(), conf, e.hole)
            }
        }
    }

    fun speech(runs: List<Run>) {
        val sp = p.speech
        if (!hasAudio) return
        val off = BooleanArray(k) { true }
        val pad = nf(sp.motorPadMs)
        for (run in runs) {
            val end = run.off ?: k
            for (q in maxOf(0, run.on - pad) until minOf(k, end + pad)) off[q] = false
        }
        val n = nf(sp.windowMs)
        val hop = nf(sp.hopMs)
        val z = f[Feature.Z_SPEECH]
        val eSp = f[Feature.E_SPEECH]
        val env = DoubleArray(k) { Math.pow(10.0, eSp[it] / 20.0) }
        val fr = 1000.0 / fm
        val bins = (1 until n / 2).filter { b -> sp.modHz[0] <= b * fr / n && b * fr / n <= sp.modHz[1] }
        val ph = DoubleArray(n) { 2.0 * Math.PI * it / n }
        val wins = ArrayList<Triple<Int, Int, Double>>()
        var i = 0
        while (i <= k - n) {
            val ok = (i until i + n).all { off[it] && env[it].isFinite() }
            if (ok && !(med(z, i, i + n) <= sp.z)) {
                val mu = mean(env.copyOfRange(i, i + n))
                val x = DoubleArray(n) { env[i + it] - mu }
                var tot = 0.0
                for (v in x) tot += v * v
                if (tot > 0) {
                    var mod = 0.0
                    for (b in bins) {
                        var c = 0.0
                        var sn = 0.0
                        for (q in 0 until n) {
                            c += x[q] * Math.cos(ph[q] * b)
                            sn += x[q] * Math.sin(ph[q] * b)
                        }
                        mod += c * c + sn * sn
                    }
                    val frac = 2.0 * mod / (n * tot)
                    if (frac > sp.modFrac) wins += Triple(i, i + n, frac)
                }
            }
            i += hop
        }
        val merged = ArrayList<Triple<Int, Int, Double>>()
        for (w in wins) {
            val lastW = merged.lastOrNull()
            if (lastW != null && w.first <= lastW.second) {
                merged[merged.size - 1] = Triple(lastW.first, w.second, maxOf(lastW.third, w.third))
            } else {
                merged += w
            }
        }
        for ((a, b, frac) in merged) {
            val conf = sp.confidenceMax * sigmoid((frac / sp.modFrac - 1.0) / sp.confScale)
            spans += DetSpan("ignore", f.t(a), f.t(b), conf, reason = "speech")
        }
    }
}
