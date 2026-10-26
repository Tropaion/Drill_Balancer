package dev.drillbalancer.recorder.export

import dev.drillbalancer.protocol.FirmwareSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts of JSON Schema 2020-12 the dataset contract's schemas use (type, const, enum,
 * oneOf, $ref, properties, required, additionalProperties, propertyNames, items, min/maxItems,
 * uniqueItems, minimum, maximum, exclusiveMinimum, maxLength, minLength, pattern), for the
 * finalizer's tests: what it writes must fit meta.v1 / events.v1 without Python
 * (dbdata.validate checks the rest, opt-in). Returns the problems, empty when it fits.
 */
class SchemaCheck(name: String) {
    private val root = Json.parseToJsonElement(FirmwareSources.text("tools/dataset/dbdata/contract/$name")).jsonObject

    fun problems(doc: JsonElement): List<String> = ArrayList<String>().also { check(root, doc, "$", it) }

    private fun resolve(s: JsonObject): JsonObject {
        val ref = (s["\$ref"] as? JsonPrimitive)?.content ?: return s
        require(ref.startsWith("#/\$defs/")) { ref }
        return resolve(root["\$defs"]!!.jsonObject[ref.removePrefix("#/\$defs/")]!!.jsonObject)
    }

    private fun check(schemaIn: JsonObject, v: JsonElement, at: String, out: MutableList<String>) {
        val s = resolve(schemaIn)
        s["oneOf"]?.let { alts ->
            val ok = alts.jsonArray.count { problems(it.jsonObject, v).isEmpty() }
            if (ok != 1) out += "$at: matches $ok of oneOf"
        }
        s["const"]?.let { if (it != v) out += "$at: $v is not $it" }
        s["enum"]?.let { e -> if (e.jsonArray.none { same(it, v) }) out += "$at: $v not in $e" }
        s["type"]?.let { t -> if (!typeOk(t.jsonPrimitive.content, v)) out += "$at: $v is not ${t.jsonPrimitive.content}" }
        if (v is JsonPrimitive && v !is JsonNull) {
            if (v.isString) {
                val str = v.content
                val len = str.codePointCount(0, str.length)
                s["maxLength"]?.let { if (len > it.jsonPrimitive.int) out += "$at: longer than $it" }
                s["minLength"]?.let { if (len < it.jsonPrimitive.int) out += "$at: shorter than $it" }
                s["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(str)) out += "$at: '$str' !~ $it" }
            } else v.doubleOrNull?.let { d ->
                s["minimum"]?.let { if (d < it.jsonPrimitive.double) out += "$at: $d < $it" }
                s["maximum"]?.let { if (d > it.jsonPrimitive.double) out += "$at: $d > $it" }
                s["exclusiveMinimum"]?.let { if (d <= it.jsonPrimitive.double) out += "$at: $d <= $it" }
            }
        }
        if (v is JsonObject) {
            val props = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
            s["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in v) out += "$at: lacks ${r.jsonPrimitive.content}" }
            for ((k, x) in v) {
                s["propertyNames"]?.let { check(it.jsonObject, JsonPrimitive(k), "$at.$k(name)", out) }
                val p = props[k]
                when {
                    p != null -> check(p.jsonObject, x, "$at.$k", out)
                    s["additionalProperties"] is JsonObject -> check(s["additionalProperties"]!!.jsonObject, x, "$at.$k", out)
                    (s["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false -> out += "$at: unexpected $k"
                }
            }
        }
        if (v is JsonArray) {
            s["minItems"]?.let { if (v.size < it.jsonPrimitive.int) out += "$at: fewer than $it items" }
            s["maxItems"]?.let { if (v.size > it.jsonPrimitive.int) out += "$at: more than $it items" }
            if ((s["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true && v.toSet().size != v.size) out += "$at: items not unique"
            s["items"]?.let { item -> v.forEachIndexed { i, x -> check(item.jsonObject, x, "$at[$i]", out) } }
        }
    }

    private fun problems(s: JsonObject, v: JsonElement) = ArrayList<String>().also { check(s, v, "", it) }

    private fun same(a: JsonElement, b: JsonElement): Boolean =
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull) {
            a.doubleOrNull != null && a.doubleOrNull == b.doubleOrNull
        } else {
            a == b
        }

    private fun typeOk(t: String, v: JsonElement): Boolean = when (t) {
        "null" -> v is JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v !is JsonNull && v.content.toLongOrNull() != null
        "number" -> v is JsonPrimitive && !v.isString && v !is JsonNull && v.booleanOrNull == null && v.doubleOrNull != null
        else -> error("type $t")
    }
}
