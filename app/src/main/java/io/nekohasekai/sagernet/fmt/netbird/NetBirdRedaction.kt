package io.nekohasekai.sagernet.fmt.netbird

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Covers both sing-box snake_case and bean camelCase fields in debug diagnostics. */
fun String.redactNetBirdSecrets(): String {
    if (!contains("setup_key") && !contains("setupKey")) return this
    return runCatching {
        val tree = JsonParser.parseString(this)
        fun redact(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().toList().forEach { (key, child) ->
                    if (key == "setup_key" || key == "setupKey") {
                        value.asJsonObject.addProperty(key, "[redacted]")
                    } else redact(child)
                }
                value.isJsonArray -> value.asJsonArray.forEach { redact(it) }
            }
        }
        redact(tree)
        tree.toString()
    }.getOrDefault("[configuration omitted: NetBird credentials could not be redacted]")
}
