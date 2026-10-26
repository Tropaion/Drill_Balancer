package dev.drillbalancer.recorder.label

import dev.drillbalancer.recorder.export.Np
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** level.csv.gz as columns (the detector and the derived labels read these). */
class LevelRows(
    val tUs: LongArray,
    val flags: IntArray,
    val elevationCdeg: IntArray,
    val yawCdeg: IntArray,
    val deviationCdeg: IntArray,
    val zeroElevationCdeg: IntArray,
) {
    val size: Int get() = tUs.size

    /** Bit elevation above horizontal per row (labels.absolute_elevation_deg): the zero added back on ZERO_SET rows. */
    fun absoluteElevationDeg(i: Int): Double {
        val zero = if (flags[i] and FLAG_ZERO_SET != 0) zeroElevationCdeg[i].toDouble() else 0.0
        return (elevationCdeg[i].toDouble() + zero) / 100.0
    }

    fun imuOk(i: Int): Boolean = flags[i] and FLAG_IMU_OK != 0

    companion object {
        const val FLAG_ZERO_SET = 1 shl 4
        const val FLAG_IMU_OK = 1 shl 6
    }
}

/**
 * What the detector reads (autodetect.Inputs): the IMU (t_us, counts), the audio file's first
 * channel and its sync fit, the LEVEL rows, the coverage of the labels.
 */
class DetectInputs(
    val tUs: LongArray,
    val counts: ShortArray,               // N x 6: ax ay az gx gy gz
    val accelLsbPerG: Double,
    val gyroLsbPerDps: Double,
    val odrHz: Double,
    drillAxis: DoubleArray?,              // meta.imu.mount.drill_axis when valid
    val audio: ShortArray?,               // the first channel (MIC1, or MIC2 alone)
    val audioRateHz: Double,
    val syncAUs: Double,                  // the sync fit with adc_delay_us subtracted
    val syncBUs: Double,
    val level: LevelRows?,
    val coverage: LongArray,              // [t0, t1)
    val recordingId: String,
    val tolDeg: Double,                   // meta.level.tol_deg (infinite without one)
    val audioGaps: List<LongArray> = emptyList(),   // [f0, f1): silence filled in for missing audio
) {
    val n: Int get() = tUs.size

    /** The bit direction in the board frame, normalised. */
    val axis: DoubleArray = (drillAxis ?: doubleArrayOf(0.0, 0.0, -1.0)).let { d ->
        val norm = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        doubleArrayOf(d[0] / norm, d[1] / norm, d[2] / norm)
    }

    init {
        require(tUs.isNotEmpty() && counts.size == tUs.size * 6) { "IMU arrays do not match" }
    }
}

/** The feature columns of features.csv.gz (frame and t_us left out), in that file's order. */
enum class Feature(val column: String) {
    E_IMU("e_imu"), THRUST("thrust"), IMP("imp"), ROLL("roll"), GYRO_RAD("gyro_rad"), WOB("wob"), HAM("ham"),
    E_AUD("e_aud"), F0("f0"), HARM("harm"), E_CUT("e_cut"), FLUX("flux"), E_SPEECH("e_speech"),
    Z_IMU("z_imu"), Z_AUD("z_aud"), Z_SPEECH("z_speech"), S("s"),
}

/** Per-frame features (frame k starts at t0 + k frameUs) and the sample-level series the onsets use. */
class Features(
    val t0Us: Long,
    val frameUs: Long,
    val n: Int,
    private val cols: Map<Feature, DoubleArray>,
    val imuT: LongArray,
    val impS: DoubleArray,
    val rollS: DoubleArray,
    val audio: ShortArray?,
    val syncAUs: Double,
    val syncBUs: Double,
) {
    operator fun get(f: Feature): DoubleArray = cols.getValue(f)

    /** Any frame with audio (e_aud is all NaN without it). */
    val hasAudio: Boolean = cols.getValue(Feature.E_AUD).any { it.isFinite() }

    fun t(k: Int): Long = t0Us + k * frameUs

    fun audioT(i: Double): Double = syncAUs + syncBUs * i

    fun audioIndex(tUs: Double): Double = (tUs - syncAUs) / syncBUs

    /** Sample i of the audio at full scale 1. */
    fun x(i: Int): Double = audio!![i] / 32768.0
}

/** Sections 1-4 of the docstring: frames, IMU and audio features, noise floors and z-scores. */
internal object FeatureExtractor {
    fun compute(inp: DetectInputs, p: DetectParams): Features {
        val frameUs = (p.frameMs * 1000).toLong()
        val t0 = inp.tUs[0]
        val k = ((inp.tUs[inp.n - 1] - t0) / frameUs).toInt() + 1
        val cols = HashMap<Feature, DoubleArray>()
        val (imp, roll) = imu(inp, p, t0, frameUs, k, cols)
        audio(inp, p, t0, frameUs, k, cols)
        val block = msFrames(p.noise.blockMs, p.frameMs)
        val eImu = cols.getValue(Feature.E_IMU)
        cols[Feature.Z_IMU] = noiseZ(eImu, quietBlock(eImu, block), p)
        val eAud = cols.getValue(Feature.E_AUD)
        if (eAud.any { it.isFinite() }) {
            val sl = quietBlock(eAud, block)              // also the reference of e_speech
            cols[Feature.Z_AUD] = noiseZ(eAud, sl, p)
            cols[Feature.Z_SPEECH] = noiseZ(cols.getValue(Feature.E_SPEECH), sl, p, cols.getValue(Feature.E_SPEECH))
        } else {
            cols[Feature.Z_AUD] = DoubleArray(k) { Double.NaN }
            cols[Feature.Z_SPEECH] = DoubleArray(k) { Double.NaN }
        }
        val nz = p.features.smoothZFrames
        val zi = centredMedian(cols.getValue(Feature.Z_IMU), nz)
        val za = centredMedian(cols.getValue(Feature.Z_AUD), nz)
        // the larger of the two where either exists, NaN where neither does (a transport gap): np.fmax
        cols[Feature.S] = DoubleArray(k) { i ->
            val a = zi[i]
            val b = za[i]
            when {
                a.isNaN() -> b
                b.isNaN() -> a
                else -> maxOf(a, b)
            }
        }
        return Features(t0, frameUs, k, cols, inp.tUs, imp, roll, inp.audio, inp.syncAUs, inp.syncBUs)
    }

    // ---- 2. IMU ----

    private fun imu(inp: DetectInputs, p: DetectParams, t0: Long, frameUs: Long, kFrames: Int, cols: MutableMap<Feature, DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val fp = p.features
        val fs = inp.odrHz
        val n = inp.n
        val d = inp.axis
        val c = inp.counts
        // One axis at a time (a 20-minute take has half a million samples per array); the sums
        // run in the reference's order: ((x0 + x1) + x2), 0 + x0 being x0 exactly.
        val e = DoubleArray(n)
        val imp = DoubleArray(n)
        val ad = DoubleArray(n)
        for (ax in 0 until 3) {
            val a = DoubleArray(n) { c[it * 6 + ax] / inp.accelLsbPerG }
            val h = Biquad.hp(a, fp.hpHz, fs)
            for (i in 0 until n) {
                e[i] += h[i] * h[i]
                imp[i] += h[i] * d[ax]
                ad[i] += a[i] * d[ax]
            }
        }
        val thrust = Biquad.lp(Biquad.hp(ad, fp.thrustBandHz[0], fs), fp.thrustBandHz[1], fs)
        val roll = DoubleArray(n)
        for (ax in 0 until 3) for (i in 0 until n) roll[i] += c[i * 6 + 3 + ax] / inp.gyroLsbPerDps * d[ax]
        val gr = DoubleArray(n)
        val wb = DoubleArray(n)
        for (ax in 0 until 3) {
            val wr = DoubleArray(n) { c[it * 6 + 3 + ax] / inp.gyroLsbPerDps - roll[it] * d[ax] }
            val g = Biquad.hp(wr, fp.hpHz, fs)
            val band = Biquad.lp(Biquad.hp(wr, fp.wobbleBandHz[0], fs), fp.wobbleBandHz[1], fs)
            for (i in 0 until n) {
                gr[i] += g[i] * g[i]
                wb[i] += band[i] * band[i]
            }
        }
        for (i in 0 until n) gr[i] = sqrt(gr[i])

        val t = inp.tUs
        val frame = IntArray(n) { ((t[it] - t0) / frameUs).toInt() }
        val cnt = DoubleArray(kFrames)
        for (f in frame) cnt[f] += 1.0

        fun meanOf(v: DoubleArray): DoubleArray {
            val s = DoubleArray(kFrames)
            for (i in 0 until n) s[frame[i]] += v[i]
            return DoubleArray(kFrames) { if (cnt[it] > 0) s[it] / cnt[it] else Double.NaN }
        }

        fun maxOf(v: DoubleArray): DoubleArray {
            val m = DoubleArray(kFrames) { Double.NEGATIVE_INFINITY }
            for (i in 0 until n) if (v[i] > m[frame[i]]) m[frame[i]] = v[i]
            for (k in 0 until kFrames) if (cnt[k] == 0.0) m[k] = Double.NaN
            return m
        }

        // wobble: RMS over the samples of the wobble_window_ms centred on the frame
        val perFrame = DoubleArray(kFrames)
        for (i in 0 until n) perFrame[frame[i]] += wb[i]
        val sw = DoubleArray(kFrames + 1)
        val sc = DoubleArray(kFrames + 1)
        for (k in 0 until kFrames) {
            sw[k + 1] = sw[k] + perFrame[k]
            sc[k + 1] = sc[k] + cnt[k]
        }
        val hw = msFrames(fp.wobbleWindowMs, frameUs / 1000.0) / 2
        val wob = DoubleArray(kFrames) { k ->
            val lo = (k - hw).coerceIn(0, kFrames)
            val hi = (k + hw + 1).coerceIn(0, kFrames)
            val nw = sc[hi] - sc[lo]
            if (nw > 0) sqrt((sw[hi] - sw[lo]) / nw) else Double.NaN
        }
        val meanE = meanOf(e)
        cols[Feature.E_IMU] = DoubleArray(kFrames) { 10.0 * log10(meanE[it] + fp.epsG2) }
        cols[Feature.THRUST] = meanOf(thrust)
        cols[Feature.IMP] = maxOf(imp)
        cols[Feature.ROLL] = meanOf(roll)
        cols[Feature.GYRO_RAD] = maxOf(gr)
        cols[Feature.WOB] = wob

        // hammer periodicity: autocorrelation of |imp| at the blow rates
        val env = DoubleArray(n) { abs(imp[it]) }
        val half = fp.hammerWindowMs * 500.0
        val lagLo = rnd(fs / fp.hammerRateHz[1]).toInt()
        val lagHi = rnd(fs / fp.hammerRateHz[0]).toInt()
        val ham = DoubleArray(kFrames)
        var i0 = 0
        var i1 = 0
        var x = DoubleArray(64)
        for (k in 0 until kFrames) {
            val tc = t0 + k * frameUs + frameUs / 2.0
            // searchsorted: side right of tc - half, side left of tc + half (both move forward with k)
            while (i0 < n && t[i0].toDouble() <= tc - half) i0++
            while (i1 < n && t[i1].toDouble() < tc + half) i1++
            val m = i1 - i0
            if (m < 4) continue
            if (x.size < m) x = DoubleArray(m)
            val mu = Np.sum(env, i0, m) / m
            var den = 0.0
            for (j in 0 until m) {
                x[j] = env[i0 + j] - mu
                den += x[j] * x[j]
            }
            if (den <= 0.0) continue
            if (lagLo < m) {
                var best = Double.NEGATIVE_INFINITY
                for (lag in lagLo..minOf(lagHi, m - 1)) {
                    var s = 0.0
                    for (j in 0 until m - lag) s += x[j] * x[j + lag]
                    if (s > best) best = s
                }
                ham[k] = best / den
            }
        }
        cols[Feature.HAM] = ham
        return imp to roll
    }

    // ---- 3. audio ----

    private fun audio(inp: DetectInputs, p: DetectParams, t0: Long, frameUs: Long, kFrames: Int, cols: MutableMap<Feature, DoubleArray>) {
        val fp = p.features
        val names = listOf(Feature.E_AUD, Feature.F0, Feature.HARM, Feature.E_CUT, Feature.FLUX, Feature.E_SPEECH)
        for (f in names) cols[f] = DoubleArray(kFrames) { Double.NaN }
        val audio = inp.audio ?: return
        val m = audio.size
        val s = LongArray(kFrames + 1) { rnd(((t0 + it * frameUs) - inp.syncAUs) / inp.syncBUs) }
        val ok = BooleanArray(kFrames) { s[it] >= 0 && s[it + 1] <= m && s[it + 1] > s[it] }
        for (g in inp.audioGaps) for (k in 0 until kFrames) if (s[k] < g[1] && s[k + 1] > g[0]) ok[k] = false
        val csum = cumulativeSquares(audio, s, ok)

        val nWin = fp.fftWindow
        val nFft = fp.fftSize
        val nb = nFft / 2 + 1
        val dbin = inp.audioRateHz / nFft
        val win = DoubleArray(nWin) { 0.5 - 0.5 * cos(2.0 * PI * it / nWin) }
        val fft = RealFft(nFft)
        val seg = DoubleArray(nWin)
        val mag = DoubleArray(nb)
        val lmag = DoubleArray(nb)
        val lh = DoubleArray(nb)

        val eAud = cols.getValue(Feature.E_AUD)
        val eCut = cols.getValue(Feature.E_CUT)
        val eSpeech = cols.getValue(Feature.E_SPEECH)
        val flux = cols.getValue(Feature.FLUX)
        val harmCol = cols.getValue(Feature.HARM)
        val f0Col = cols.getValue(Feature.F0)

        val (cut0, cut1) = band(fp.cutBandHz[0], fp.cutBandHz[1], dbin, nb)
        val (sp0, sp1) = band(fp.speechBandHz[0], fp.speechBandHz[1], dbin, nb)
        val (fl0, fl1) = band(fp.fluxBandHz[0], fp.fluxBandHz[1], dbin, nb)
        val prevBand = DoubleArray(fl1 - fl0 + 1)
        val cutPw = DoubleArray(cut1 - cut0 + 1)
        val spPw = DoubleArray(sp1 - sp0 + 1)
        val fluxV = DoubleArray(fl1 - fl0 + 1)
        val log1e6 = ln(1e-6)

        val hh = fp.hpsHarmonics
        val j0 = ceil(fp.f0RangeHz[0] / dbin).toInt()
        val j1 = minOf(floor(fp.f0RangeHz[1] / dbin).toInt(), (nb - 1) / hh)
        val nj = j1 - j0 + 1
        val hps = DoubleArray(nj)
        val pb = fp.prominenceBins
        val promBuf = DoubleArray(2 * pb + 1)
        // the bins read below: lmag in the flux band, lh up to the last harmonic's prominence window
        val lhTop = minOf(nb - 1, hh * maxOf(j0, j1) + pb)

        var prevOk = false
        for (k in 0 until kFrames) {
            if (!ok[k]) {
                prevOk = false
                continue
            }
            val c = Math.floorDiv(s[k] + s[k + 1], 2L)
            val a0 = c - nWin / 2
            for (i in 0 until nWin) {
                val idx = a0 + i
                seg[i] = if (idx in 0 until m) (audio[idx.toInt()] / 32768.0) * win[i] else 0.0
            }
            eAud[k] = 10.0 * log10((csum[k + 1] - csum[k]) / (s[k + 1] - s[k]).toDouble() + fp.epsFs2)
            fft.magnitudes(seg, nWin, mag)
            for (j in cut0..cut1) cutPw[j - cut0] = mag[j] * mag[j]
            eCut[k] = 10.0 * log10(Np.sum(cutPw) + fp.epsFs2)
            for (j in sp0..sp1) spPw[j - sp0] = mag[j] * mag[j]
            eSpeech[k] = 10.0 * log10(Np.sum(spPw) + fp.epsFs2)

            for (j in fl0..fl1) lmag[j] = ln(mag[j] + 1e-6)
            for (j in 0..lhTop) lh[j] = ln(mag[j] + 1e-9)
            for (j in fl0..fl1) {
                val prev = if (prevOk) prevBand[j - fl0] else log1e6
                fluxV[j - fl0] = maxOf(0.0, lmag[j] - prev)
            }
            flux[k] = Np.sum(fluxV) / fluxV.size
            for (j in fl0..fl1) prevBand[j - fl0] = lmag[j]
            prevOk = true

            // f0: the harmonic product spectrum, its first maximum, the harmonics' prominence
            var best = 0
            for (r in 0 until nj) {
                val jj = j0 + r
                var v = 0.0
                for (h in 1..hh) v += lh[h * jj]
                hps[r] = v
                if (v > hps[best]) best = r
            }
            var harm = 0.0
            for (h in 1..hh) {
                val cc = h * (j0 + best)
                var cntv = 0
                for (o in -pb..pb) {
                    val idx = cc + o
                    if (idx in 0 until nb) promBuf[cntv++] = lh[idx]
                }
                java.util.Arrays.sort(promBuf, 0, cntv)
                val medv = if (cntv % 2 == 1) promBuf[cntv / 2] else promBuf[cntv / 2] - (promBuf[cntv / 2] - promBuf[cntv / 2 - 1]) * 0.5
                harm += lh[cc] - medv
            }
            harm /= hh
            harmCol[k] = harm
            if (harm < fp.voicedMin || best == 0 || best == nj - 1) continue
            val den = hps[best - 1] - 2.0 * hps[best] + hps[best + 1]
            val delta = if (den != 0.0) (0.5 * (hps[best - 1] - hps[best + 1]) / den).coerceIn(-0.5, 0.5) else 0.0
            f0Col[k] = (j0 + best + delta) * dbin
        }
    }

    /**
     * csum[k] = the cumulative sum of x^2 (x = samples / 32768, summed in order from sample 0, as
     * np.cumsum does) up to s[k], kept for the frames with audio only (the whole running sum of a
     * 20-minute take would be 150 MB of doubles).
     */
    private fun cumulativeSquares(audio: ShortArray, s: LongArray, ok: BooleanArray): DoubleArray {
        val out = DoubleArray(s.size)
        var sum = 0.0
        var i = 0
        for (k in s.indices) {
            val need = k < ok.size && ok[k] || k > 0 && ok[k - 1]
            if (!need) continue
            val target = s[k].toInt()
            while (i < target) {
                val x = audio[i] / 32768.0
                sum += x * x
                i++
            }
            out[k] = sum
        }
        return out
    }

    private fun band(lo: Double, hi: Double, d: Double, nbins: Int): Pair<Int, Int> =
        maxOf(0, ceil(lo / d).toInt()) to minOf(nbins - 1, floor(hi / d).toInt())

    // ---- 4. noise floor ----

    /** [from, to) of v's quietest block. */
    private fun quietBlock(v: DoubleArray, block: Int): IntRange {
        var best = -1
        var bestMed = Double.POSITIVE_INFINITY
        var i = 0
        while (i <= v.size - block) {
            var finite = 0
            for (j in i until i + block) if (v[j].isFinite()) finite++
            if (finite * 2 >= block) {
                val mm = med(v, i, i + block)
                if (mm < bestMed) {
                    best = i
                    bestMed = mm
                }
            }
            i += block
        }
        return if (best < 0) v.indices else best until best + block
    }

    private fun noiseZ(v: DoubleArray, sl: IntRange, p: DetectParams, ref: DoubleArray = v): DoubleArray {
        val block = ref.copyOfRange(sl.first, sl.last + 1)
        val fl = pct(block, p.noise.floorPct)
        val m = med(block)
        val mad = med(DoubleArray(block.size) { abs(block[it] - m) })
        val spread = if (mad.isFinite()) maxOf(1.4826 * mad, p.noise.spreadMinDb) else p.noise.spreadMinDb
        return DoubleArray(v.size) { (v[it] - fl) / spread }
    }
}
