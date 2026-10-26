package dev.drillbalancer.recorder.label

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The small helpers of dbdata.autodetect (its module docstring's "Definitions"), with numpy's
 * rules where they matter: medians and percentiles over the finite values only, numpy's linear
 * interpolation, Python's floored modulo.
 */

/** round(x) = floor(x + 0.5). */
internal fun rnd(x: Double): Long = floor(x + 0.5).toLong()

/** nf(ms): max(1, round(ms / frame_ms)) frames. */
internal fun msFrames(ms: Double, frameMs: Double): Int = maxOf(1L, rnd(ms / frameMs)).toInt()

/** 1 / (1 + exp(-x)), x clamped to +-60 the way Python's min/max clamp it (NaN gives 60). */
internal fun sigmoid(x: Double): Double {
    val a = if (x < 60.0) x else 60.0
    val b = if (a > -60.0) a else -60.0
    return 1.0 / (1.0 + exp(-b))
}

/** Python's x % m for a float and m > 0 (the result has the sign of m). */
internal fun pyMod(x: Double, m: Double): Double {
    var r = x % m
    if (r != 0.0) {
        if (r < 0) r += m
    } else {
        r = 0.0
    }
    return r
}

/** The finite values of a[from, to) (clipped to the array), sorted. */
private fun finiteSorted(a: DoubleArray, from: Int, to: Int): DoubleArray {
    val lo = from.coerceIn(0, a.size)
    val hi = to.coerceIn(lo, a.size)
    val out = DoubleArray(hi - lo)
    var n = 0
    for (i in lo until hi) {
        val v = a[i]
        if (v.isFinite()) out[n++] = v
    }
    val s = if (n == out.size) out else out.copyOf(n)
    s.sort()
    return s
}

/** med(): the median of the finite values (numpy's rule: b - (b - a) * 0.5 for an even count); NaN for none. */
internal fun med(a: DoubleArray, from: Int = 0, to: Int = a.size): Double = medSorted(finiteSorted(a, from, to))

internal fun med(values: List<Double>): Double = med(values.toDoubleArray())

private fun medSorted(s: DoubleArray): Double {
    val n = s.size
    if (n == 0) return Double.NaN
    if (n % 2 == 1) return s[n / 2]
    val lo = s[n / 2 - 1]
    val hi = s[n / 2]
    return hi - (hi - lo) * 0.5
}

/** np.percentile(finite values, q), numpy's linear method; NaN for none. */
internal fun pct(a: DoubleArray, q: Double, from: Int = 0, to: Int = a.size): Double {
    val s = finiteSorted(a, from, to)
    val n = s.size
    if (n == 0) return Double.NaN
    val virtual = (n - 1) * (q / 100.0)
    val prev = floor(virtual)
    val gamma = virtual - prev
    val i = prev.toInt().coerceIn(0, n - 1)
    val j = (i + 1).coerceAtMost(n - 1)
    val a0 = s[i]
    val b0 = s[j]
    val diff = b0 - a0
    return if (gamma >= 0.5) b0 - diff * (1.0 - gamma) else a0 + diff * gamma
}

/** np.median of finite values: the middle one, or the mean of the middle two ((a + b) / 2). */
internal fun npMedian(values: DoubleArray): Double {
    val s = values.copyOf()
    s.sort()
    val n = s.size
    if (n == 0) return Double.NaN
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/** medN(v)[k]: the median over [k - n/2, k + n/2], clipped at the ends, NaN ignored. */
internal fun centredMedian(v: DoubleArray, n: Int): DoubleArray {
    val h = n / 2
    val out = DoubleArray(v.size)
    val buf = DoubleArray(2 * h + 1)
    for (k in v.indices) {
        var m = 0
        for (i in maxOf(0, k - h)..minOf(v.size - 1, k + h)) {
            val x = v[i]
            if (x.isFinite()) buf[m++] = x
        }
        if (m == 0) {
            out[k] = Double.NaN
            continue
        }
        java.util.Arrays.sort(buf, 0, m)
        out[k] = if (m % 2 == 1) buf[m / 2] else buf[m / 2] - (buf[m / 2] - buf[m / 2 - 1]) * 0.5
    }
    return out
}

/** The largest finite value of a[from, to), or NaN (np.nanmax; callers check one exists). */
internal fun nanMax(a: DoubleArray, from: Int, to: Int): Double {
    var m = Double.NaN
    for (i in from.coerceAtLeast(0) until to.coerceAtMost(a.size)) {
        val v = a[i]
        if (!v.isNaN() && (m.isNaN() || v > m)) m = v
    }
    return m
}

/** Any finite value in a[from, to). */
internal fun anyFinite(a: DoubleArray, from: Int, to: Int): Boolean {
    for (i in from.coerceAtLeast(0) until to.coerceAtMost(a.size)) if (a[i].isFinite()) return true
    return false
}

/** The arithmetic mean of [values] (numpy's pairwise sum). */
internal fun mean(values: DoubleArray): Double = dev.drillbalancer.recorder.export.Np.mean(values)

/** RBJ cookbook 2nd-order high-/low-pass, Q = 1/sqrt(2), normalised by a0: (b0, b1, b2, a1, a2). */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double, val highPass: Boolean) {
    /**
     * Direct form I, sample by sample, started in the steady state of x[0] (x1 = x2 = x[0];
     * y1 = y2 = 0 for a high-pass, x[0] for a low-pass): autodetect.biquad_reference.
     */
    fun run(x: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        var x1 = x[0]
        var x2 = x[0]
        var y1 = if (highPass) 0.0 else x[0]
        var y2 = y1
        for (i in x.indices) {
            val v = x[i]
            val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = v
            y2 = y1
            y1 = y
            out[i] = y
        }
        return out
    }

    companion object {
        private val Q = 1.0 / sqrt(2.0)

        fun of(highPass: Boolean, fc: Double, fs: Double): Biquad {
            val w = 2.0 * PI * fc / fs
            val al = sin(w) / (2.0 * Q)
            val c = cos(w)
            val a0 = 1.0 + al
            val b0: Double
            val b1: Double
            if (highPass) {
                b0 = (1.0 + c) / 2.0
                b1 = -(1.0 + c)
            } else {
                b0 = (1.0 - c) / 2.0
                b1 = 1.0 - c
            }
            return Biquad(b0 / a0, b1 / a0, b0 / a0, -2.0 * c / a0, (1.0 - al) / a0, highPass)
        }

        fun hp(x: DoubleArray, fc: Double, fs: Double) = of(true, fc, fs).run(x)
        fun lp(x: DoubleArray, fc: Double, fs: Double) = of(false, fc, fs).run(x)
    }
}
