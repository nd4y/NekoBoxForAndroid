package io.nekohasekai.sagernet.control

import com.google.gson.*
import io.nekohasekai.sagernet.fmt.buildConfig
import libcore.Libcore

object ControlValidation {
    fun validate(s: ControlSnapshot): List<Long> {
        ControlPatch.validateReferences(s)
        // NetBird is a chain component, not an independently connectable root in this fork.
        // Validate its fields and all parent chains; reject it as an explicit selection/rule
        // target.
        s.profiles
            .mapNotNull { it.netbirdBean }
            .forEach {
                try {
                    io.nekohasekai.sagernet.fmt.netbird.validateNetBirdManagementUrl(
                        it.managementUrl
                    )
                    io.nekohasekai.sagernet.fmt.netbird.parseNetBirdDnsDomains(it.dnsDomains)
                } catch (_: Exception) {
                    throw ControlError(
                        "INVALID_CONFIG",
                        "Invalid NetBird management URL or DNS suffixes",
                    )
                }
            }
        val requiredRoots =
            s.rules.filter { it.enabled && it.outbound > 0 }.map { it.outbound }.toSet() +
                s.selection()
        val roots = s.profiles.filter { it.netbirdBean == null || it.id in requiredRoots }
        // Validate independently connectable profiles, including inactive chains affected by edits.
        requireControl(
            s.profiles.size <= 512,
            "VALIDATION_LIMIT",
            "API validation supports at most 512 profiles",
        )
        return ControlAccess.preview(s) {
            roots.map { profile ->
                try {
                    val json = buildConfig(profile).config
                    Libcore.checkConfigJSON(json)
                    checkTags(ControlJson.parseObject(json))
                } catch (e: ControlError) {
                    throw e
                } catch (_: Exception) {
                    throw ControlError(
                        "INVALID_CONFIG",
                        "Configuration validation failed for profile ${profile.id}",
                    )
                }
                profile.id
            }
        }
    }

    /** Resolve generated references without constructing transports or contacting any network. */
    fun checkTags(config: JsonObject) {
        fun objects(name: String) =
            config[name]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject }
                ?: emptyList()
        val out = objects("outbounds") + objects("endpoints")
        val tags = out.mapNotNull { it["tag"]?.asString }
        requireControl(tags.size == tags.toSet().size, "DUPLICATE_TAG", "Duplicate outbound tag")
        val dns = config["dns"]?.takeIf { it.isJsonObject }?.asJsonObject
        val servers = dns?.get("servers")?.asJsonArray?.map { it.asJsonObject } ?: emptyList()
        val dnsTags = servers.mapNotNull { it["tag"]?.asString }.toSet()
        fun reference(v: JsonElement?, pool: Collection<String>) {
            if (v != null && !v.isJsonNull)
                requireControl(
                    v.isJsonPrimitive && v.asString in pool,
                    "MISSING_DETOUR",
                    "Configuration references a missing tag",
                )
        }
        fun walk(v: JsonElement, dnsRule: Boolean = false) {
            when {
                v.isJsonArray -> v.asJsonArray.forEach { walk(it, dnsRule) }
                v.isJsonObject ->
                    v.asJsonObject.entrySet().forEach { (key, x) ->
                        when (key) {
                            "detour",
                            "download_detour" -> reference(x, tags)
                            "outbound" ->
                                if (dnsRule && x.isJsonArray)
                                    x.asJsonArray.forEach { reference(it, tags + "any") }
                                else reference(x, tags)
                            "domain_resolver",
                            "default_domain_resolver" ->
                                reference(
                                    if (x.isJsonObject) x.asJsonObject["server"] else x,
                                    dnsTags,
                                )
                            "outbounds" ->
                                if (x.isJsonArray) x.asJsonArray.forEach { reference(it, tags) }
                            "server" -> if (dnsRule) reference(x, dnsTags)
                            else -> if (x.isJsonArray || x.isJsonObject) walk(x, dnsRule)
                        }
                    }
            }
        }
        out.forEach { walk(it) }
        config["route"]?.let { route ->
            reference(route.asJsonObject["final"], tags)
            walk(route)
        }
        dns?.let {
            reference(it["final"], dnsTags)
            it["rules"]?.let { rules -> walk(rules, true) }
        }
        servers.forEach { server ->
            walk(server)
            reference(server["address_resolver"], dnsTags)
        }
        val graph =
            out.associate {
                it["tag"]?.asString.orEmpty() to
                    buildList {
                        it["detour"]?.let { d -> add(d.asString) }
                        it["outbounds"]?.asJsonArray?.forEach { d -> add(d.asString) }
                    }
            }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(id: String, depth: Int) {
            if (id in done) return
            requireControl(
                depth < 128 && visiting.add(id),
                "DETOUR_CYCLE",
                "Outbound references contain a cycle",
            )
            graph[id]?.forEach { visit(it, depth + 1) }
            visiting.remove(id)
            done.add(id)
        }
        graph.keys.forEach { visit(it, 0) }
    }
}
