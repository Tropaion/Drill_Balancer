package dev.drillbalancer.recorder.label

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * |X_j| of the real FFT of [size] points (a power of two), j = 0 .. size / 2: numpy's
 * `np.abs(np.fft.rfft(x, size))`. The real input goes in as a complex FFT of half the size
 * (even samples real, odd imaginary) and is split afterwards. One instance per thread: it keeps
 * its work arrays.
 */
internal class RealFft(val size: Int) {
    private val half = size / 2
    private val bits = Integer.numberOfTrailingZeros(half)
    private val re = DoubleArray(half)
    private val im = DoubleArray(half)
    private val rev = IntArray(half) { Integer.reverse(it) ushr (32 - bits) }
    // e^{-2 pi i k / half} for the half-size FFT, and e^{-2 pi i k / size} for the split
    private val cosH = DoubleArray(half / 2) { cos(2.0 * PI * it / half) }
    private val sinH = DoubleArray(half / 2) { -sin(2.0 * PI * it / half) }
    private val cosN = DoubleArray(half + 1) { cos(2.0 * PI * it / size) }
    private val sinN = DoubleArray(half + 1) { -sin(2.0 * PI * it / size) }

    init {
        require(size >= 4 && Integer.bitCount(size) == 1) { "FFT size $size" }
    }

    /** |X_j| into [mag] (size / 2 + 1 values) of [x] zero-padded to size ([n] samples given, n <= size). */
    fun magnitudes(x: DoubleArray, n: Int, mag: DoubleArray) {
        for (k in 0 until half) {
            val a = 2 * k
            val i = rev[k]
            re[i] = if (a < n) x[a] else 0.0
            im[i] = if (a + 1 < n) x[a + 1] else 0.0
        }
        var len = 2
        while (len <= half) {
            val step = half / len
            val h = len / 2
            var start = 0
            while (start < half) {
                var t = 0
                for (j in 0 until h) {
                    val wr = cosH[t]
                    val wi = sinH[t]
                    val p = start + j
                    val q = p + h
                    val xr = re[q] * wr - im[q] * wi
                    val xi = re[q] * wi + im[q] * wr
                    re[q] = re[p] - xr
                    im[q] = im[p] - xi
                    re[p] += xr
                    im[p] += xi
                    t += step
                }
                start += len
            }
            len = len shl 1
        }
        // X_k = (Z_k + conj Z_{h-k}) / 2 - i e^{-2 pi i k / N} (Z_k - conj Z_{h-k}) / 2
        for (k in 0..half) {
            val zr = re[k % half]
            val zi = im[k % half]
            val cr = re[(half - k) % half]
            val ci = -im[(half - k) % half]
            val er = (zr + cr) * 0.5
            val ei = (zi + ci) * 0.5
            val or = (zr - cr) * 0.5
            val oi = (zi - ci) * 0.5
            // -i * (or + i oi) = oi - i or
            val tr = oi
            val ti = -or
            val wr = cosN[k]
            val wi = sinN[k]
            val xr = er + (tr * wr - ti * wi)
            val xi = ei + (tr * wi + ti * wr)
            mag[k] = sqrt(xr * xr + xi * xi)
        }
    }
}
