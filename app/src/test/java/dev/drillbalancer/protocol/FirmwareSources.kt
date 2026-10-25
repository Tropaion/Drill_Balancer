package dev.drillbalancer.protocol

import java.io.File

/**
 * Reads the firmware's C sources next to the app (../firmware relative to the Gradle
 * project dir, which is the unit tests' working directory), for the conformance checks.
 */
object FirmwareSources {
    val dir: File by lazy {
        val d = File(System.getProperty("user.dir"), "../firmware").canonicalFile
        check(File(d, "components/ble/include/ble_protocol.h").isFile) {
            "Firmware sources not found at $d. The conformance tests compare the app's protocol " +
                "constants with the C headers and need the repository checkout (app/ next to firmware/)."
        }
        d
    }

    fun text(path: String): String {
        val f = File(dir, path)
        check(f.isFile) { "missing firmware file $f" }
        return f.readText()
    }

    /** Text with comments removed (string literals here hold no comment markers). */
    fun code(path: String): String =
        text(path).replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), "")

    /** Integer #defines: decimal/hex literals with a u suffix, (-1), (1u << n). */
    fun intDefines(path: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val def = Regex("""^\s*#define\s+(\w+)\s+(.+)$""")
        for (line in code(path).lines()) {
            val m = def.find(line) ?: continue
            evalInt(m.groupValues[2].trim())?.let { out[m.groupValues[1]] = it }
        }
        return out
    }

    /** Float #defines such as 0.05f. */
    fun floatDefines(path: String): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        val def = Regex("""^\s*#define\s+(\w+)\s+(-?\d+\.\d*)f\s*$""")
        for (line in code(path).lines()) {
            val m = def.find(line) ?: continue
            out[m.groupValues[1]] = m.groupValues[2].toFloat()
        }
        return out
    }

    private fun evalInt(expr: String): Long? {
        val e = expr.removeSurrounding("(", ")").trim()
        Regex("""^(-?)(0[xX][0-9A-Fa-f]+|\d+)[uU]?$""").find(e)?.let {
            val v = it.groupValues[2].let { s -> if (s.startsWith("0x", true)) s.substring(2).toLong(16) else s.toLong() }
            return if (it.groupValues[1] == "-") -v else v
        }
        Regex("""^(\d+)[uU]?\s*<<\s*(\d+)$""").find(e)?.let {
            return it.groupValues[1].toLong() shl it.groupValues[2].toInt()
        }
        return null
    }

    /** The enumerators of `typedef enum { ... } name;` in order, with their values. */
    fun enum(path: String, name: String): Map<String, Int> {
        val m = Regex("""typedef\s+enum\s*\{([^}]*)\}\s*$name\s*;""").find(code(path))
            ?: error("enum $name not found in $path")
        val out = LinkedHashMap<String, Int>()
        var next = 0
        for (entry in m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = entry.split('=').map { it.trim() }
            val v = if (parts.size == 2) evalInt(parts[1])!!.toInt() else next
            out[parts[0]] = v
            next = v + 1
        }
        return out
    }

    /** Fields (type, name) of `typedef struct __attribute__((packed)) { ... } name;`. */
    fun packedStruct(path: String, name: String): List<Pair<String, String>> {
        val m = Regex("""typedef\s+struct\s+__attribute__\(\(packed\)\)\s*\{([^}]*)\}\s*$name\s*;""").find(code(path))
            ?: error("struct $name not found in $path")
        return Regex("""(\w+)\s+(\w+)\s*;""").findAll(m.groupValues[1]).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }
}
