package io.nekohasekai.sagernet.control

import com.google.gson.*
import java.security.MessageDigest

class ControlError(val code: String, message: String) : RuntimeException(message)

fun requireControl(ok: Boolean, code: String, message: String) {
    if (!ok) throw ControlError(code, message)
}

object ControlJson {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    const val REDACTED = "<redacted>"

    fun objectOf(vararg pairs: Pair<String, Any?>): JsonObject =
        gson.toJsonTree(mapOf(*pairs)).asJsonObject

    fun canonical(value: JsonElement): String =
        when {
            value.isJsonObject ->
                value.asJsonObject
                    .entrySet()
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") { gson.toJson(it.key) + ":" + canonical(it.value) }
            value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
            else -> value.toString()
        }

    fun hash(value: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray()).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    fun parseObject(text: String): JsonObject =
        try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            throw ControlError("INVALID_JSON", "Expected a JSON object")
        }

    fun keys(obj: JsonObject, allowed: Set<String>) {
        requireControl(
            obj.keySet().all { it in allowed },
            "UNKNOWN_FIELD",
            "Unsupported request field",
        )
    }

    fun string(obj: JsonObject, key: String): String {
        val v = obj[key]
        requireControl(
            v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString,
            "INVALID_FIELD",
            "Expected string: $key",
        )
        return v.asString
    }

    fun id(obj: JsonObject, key: String = "id"): Long {
        val v = obj[key]
        requireControl(
            v != null &&
                v.isJsonPrimitive &&
                v.asJsonPrimitive.isNumber &&
                v.toString().matches(Regex("[0-9]+")),
            "INVALID_FIELD",
            "Expected integer: $key",
        )
        val parsed = v.asString.toLongOrNull()
        requireControl(
            parsed != null && parsed > 0,
            "INVALID_FIELD",
            "ID must be a positive signed 64-bit integer",
        )
        return parsed!!
    }

    fun merge(target: JsonObject, patch: JsonObject) {
        keys(patch, target.keySet())
        patch.entrySet().forEach { (key, v) ->
            requireControl(
                !v.toString().contains(REDACTED),
                "REDACTED_INPUT",
                "Omit redacted fields from patches",
            )
            val old = target[key]
            requireControl(!v.isJsonNull, "INVALID_FIELD", "Null is not a supported update: $key")
            val compatible =
                old.isJsonNull ||
                    (old.isJsonObject && v.isJsonObject) ||
                    (old.isJsonArray && v.isJsonArray) ||
                    (old.isJsonPrimitive &&
                        v.isJsonPrimitive &&
                        old.asJsonPrimitive.run {
                            (isBoolean && v.asJsonPrimitive.isBoolean) ||
                                (isNumber && v.asJsonPrimitive.isNumber) ||
                                (isString && v.asJsonPrimitive.isString)
                        })
            requireControl(compatible, "INVALID_FIELD", "Wrong value type: $key")
            target.add(key, v.deepCopy())
        }
    }

    // Opaque/custom configuration is parsed when possible; unknown credential-bearing fields stay
    // hidden.
    fun redact(value: JsonElement, key: String = ""): JsonElement {
        val normalized = key.lowercase().replace("_", "")
        if (
            listOf(
                    "password",
                    "secret",
                    "token",
                    "privatekey",
                    "presharedkey",
                    "setupkey",
                    "uuid",
                    "authorization",
                    "cookie",
                    "username",
                    "subscription",
                    "headers",
                    "auth",
                    "key",
                    "credential",
                    "psk",
                    "seed",
                    "passphrase",
                    "plugin",
                    "encryption",
                )
                .any { normalized.contains(it) }
        )
            return JsonPrimitive(REDACTED)
        if (value.isJsonObject)
            return JsonObject().apply {
                value.asJsonObject.entrySet().forEach { (k, v) -> add(k, redact(v, k)) }
            }
        if (value.isJsonArray)
            return JsonArray().apply { value.asJsonArray.forEach { add(redact(it, key)) } }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val text = value.asString
            if (normalized.contains("config") || normalized.endsWith("json")) {
                if (text.isBlank()) return value.deepCopy()
                return JsonPrimitive(REDACTED)
            }
            if (text.contains("://") && !normalized.endsWith("name")) {
                // URL path/query/userinfo may themselves be credentials. Do not export them
                // implicitly.
                return JsonPrimitive(REDACTED)
            }
        }
        return value.deepCopy()
    }

    fun diff(before: JsonElement, after: JsonElement, path: String = ""): JsonArray {
        val result = JsonArray()
        if (before == after) return result
        if (before.isJsonObject && after.isJsonObject) {
            (before.asJsonObject.keySet() + after.asJsonObject.keySet()).sorted().forEach { key ->
                diff(
                        before.asJsonObject[key] ?: JsonNull.INSTANCE,
                        after.asJsonObject[key] ?: JsonNull.INSTANCE,
                        "$path/$key",
                    )
                    .forEach { result.add(it) }
            }
        } else {
            // Values are deliberately omitted; even opaque JSON and arbitrary rule payloads may
            // hold credentials.
            result.add(
                objectOf(
                    "path" to path,
                    "action" to
                        if (before.isJsonNull) "add"
                        else if (after.isJsonNull) "remove" else "replace",
                )
            )
        }
        return result
    }
}
