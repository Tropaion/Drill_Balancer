package dev.drillbalancer.recorder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * The arithmetic and text formats of the reference exporter (dbdata.dbrec + dbdata.io, Python
 * and numpy), so the finalizer writes the same numbers: the same sums in the same order, the
 * same rounding and the same JSON text. FinalizerCrossCheckTest compares the files.
 */

/** numpy's float64 reductions, which sum pairwise (numpy pairwise_sum: blocks of 128, 8 accumulators). */
internal object Np {
    fun sum(a: DoubleArray, from: Int = 0, n: Int = a.size - from): Double {
        if (n < 8) {
            var r = 0.0
            for (i in from until from + n) r += a[i]
            return r
        }
        if (n <= 128) {
            val r = DoubleArray(8) { a[from + it] }
            var i = 8
            val whole = n - n % 8
            while (i < whole) {
                for (j in 0 until 8) r[j] += a[from + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) res += a[from + i++]
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return sum(a, from, n2) + sum(a, from + n2, n - n2)
    }

    fun mean(a: DoubleArray): Double = sum(a) / a.size
}

/** Python's round(x, digits) on a float: the exact binary value rounded half to even. */
internal fun pyRound(x: Double, digits: Int): Double =
    if (!x.isFinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

/** Python's int(round(x)). */
internal fun pyRoundLong(x: Double): Long = Math.rint(x).toLong()

/**
 * JSON text as dbdata.io.dump_json writes it: json.dump(doc, indent=2, ensure_ascii=False) and a
 * newline. Numbers with a fraction or an exponent are floats and printed as Python's repr does
 * (shortest digits; 1e-05, 1e+16); integers stay integers.
 */
object PyJson {
    fun dump(e: JsonElement): String = StringBuilder().also { write(it, e, 0) }.append('\n').toString()

    private fun write(sb: StringBuilder, e: JsonElement, level: Int) {
        when (e) {
            is JsonObject -> block(sb, '{', '}', level, e.entries.toList()) { (k, v) ->
                quote(sb, k)
                sb.append(": ")
                write(sb, v, level + 1)
            }
            is JsonArray -> block(sb, '[', ']', level, e) { write(sb, it, level + 1) }
            JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(literal(e.content))
        }
    }

    private inline fun <T> block(sb: StringBuilder, open: Char, close: Char, level: Int, items: List<T>, item: (T) -> Unit) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open)
        items.forEachIndexed { i, x ->
            sb.append(if (i == 0) "\n" else ",\n")
            repeat(2 * (level + 1)) { sb.append(' ') }
            item(x)
        }
        sb.append('\n')
        repeat(2 * level) { sb.append(' ') }
        sb.append(close)
    }

    private fun literal(s: String): String = when {
        s == "true" || s == "false" || s == "null" -> s
        s.any { it == '.' || it == 'e' || it == 'E' } -> repr(s.toDouble())
        else -> s
    }

    /**
     * The shortest decimal that reads back as [d] (> 0), the nearest of those when several
     * have as few digits: what Python's repr prints (David Gay's dtoa, mode 0). Worked out here
     * rather than taken from Double.toString, whose digits differ between JVMs and Android
     * versions.
     */
    private fun shortest(d: Double): BigDecimal {
        val exact = BigDecimal(d)
        for (p in 1..17) {
            val near = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (near.toDouble() == d) return near
            // at a power of two the gap below is half the gap above: the nearest may miss
            val ok = listOf(RoundingMode.CEILING, RoundingMode.FLOOR).map { exact.round(MathContext(p, it)) }.filter { it.toDouble() == d }
            if (ok.isNotEmpty()) return ok.minBy { (it - exact).abs() }
        }
        return exact.round(MathContext(17, RoundingMode.HALF_EVEN))
    }

    /** Python's repr of a float. */
    fun repr(d: Double): String {
        require(d.isFinite()) { "JSON has no $d" }
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val bd = shortest(Math.abs(d)).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val exp = digits.length - 1 - bd.scale()          // d = digits[0].digits[1:] x 10^exp
        val sign = if (d < 0) "-" else ""
        if (exp < -4 || exp >= 16) {
            val mant = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            val e = Math.abs(exp).toString().padStart(2, '0')
            return "$sign${mant}e${if (exp < 0) '-' else '+'}$e"
        }
        val text = if (exp >= 0) {
            val int = if (digits.length > exp + 1) digits.substring(0, exp + 1) else digits.padEnd(exp + 1, '0')
            val frac = if (digits.length > exp + 1) digits.substring(exp + 1) else "0"
            "$int.$frac"
        } else {
            "0." + "0".repeat(-exp - 1) + digits
        }
        return sign + text
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** A CSV field as pandas' to_csv writes it (QUOTE_MINIMAL: quoted when it holds , " CR or LF). */
internal fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
