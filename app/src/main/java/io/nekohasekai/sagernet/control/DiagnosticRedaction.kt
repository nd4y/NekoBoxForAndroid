package io.nekohasekai.sagernet.control

/** Conservative text masking; stack frames and error classes remain useful. */
internal object DiagnosticRedaction {
    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        var result = text
        secrets
            .filter { it.length >= 4 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { result = result.replace(it, ControlJson.REDACTED) }
        result =
            Regex("(?i)(?:https?|socks5?|vless|vmess|trojan)://[^\\s\"<>]+")
                .replace(result, ControlJson.REDACTED)
        result =
            Regex("(?i)(authorization[\\s\"':=]+)(?:bearer|basic)?[ \t]*[^\\r\\n,}]+").replace(
                result
            ) {
                it.groupValues[1] + ControlJson.REDACTED
            }
        result =
            Regex(
                    "(?i)((?:password|passwd|token|secret|private[_-]?key|preshared[_-]?key|setup[_-]?key|uuid)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,}]+)"
                )
                .replace(result) { it.groupValues[1] + ControlJson.REDACTED }
        result =
            Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
                .replace(result, ControlJson.REDACTED)
        return result
    }

    /** Event rows remain valid JSONL even when a credential is a numeric string. */
    fun redactEventLines(text: String, secrets: Collection<String>): String {
        fun walk(
            value: com.google.gson.JsonElement,
            key: String = "",
        ): com.google.gson.JsonElement {
            if (value.isJsonObject)
                return com.google.gson.JsonObject().apply {
                    value.asJsonObject.entrySet().forEach { (k, v) -> add(k, walk(v, k)) }
                }
            if (value.isJsonArray)
                return com.google.gson.JsonArray().apply {
                    value.asJsonArray.forEach { add(walk(it, key)) }
                }
            val safe = ControlJson.redact(value, key)
            return if (safe.isJsonPrimitive && safe.asJsonPrimitive.isString)
                com.google.gson.JsonPrimitive(redact(safe.asString, secrets))
            else safe
        }
        return text.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) return@joinToString line
            try {
                walk(com.google.gson.JsonParser.parseString(line)).toString()
            } catch (_: Exception) {
                redact(line, secrets)
            } // An initial tail may begin inside a row.
        }
    }
}
