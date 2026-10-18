package io.nekohasekai.sagernet.control

import com.google.gson.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.AbstractBean
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object ControlTypes {
    fun check(value: JsonElement, type: Type) {
        requireControl(!value.isJsonNull, "INVALID_FIELD", "Null is not a supported update")
        if (
            type is ParameterizedType &&
                type.rawType is Class<*> &&
                Collection::class.java.isAssignableFrom(type.rawType as Class<*>)
        ) {
            requireControl(value.isJsonArray, "INVALID_FIELD", "Expected array")
            value.asJsonArray.forEach { check(it, type.actualTypeArguments[0]) }
            return
        }
        val clazz =
            type as? Class<*> ?: throw ControlError("UNSUPPORTED_FIELD", "Unsupported field type")
        val valid =
            when (clazz) {
                String::class.java -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                Boolean::class.java,
                java.lang.Boolean::class.java ->
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                Int::class.java,
                java.lang.Integer::class.java ->
                    value.isJsonPrimitive &&
                        value.asJsonPrimitive.isNumber &&
                        value.toString().toIntOrNull() != null
                Long::class.java,
                java.lang.Long::class.java ->
                    value.isJsonPrimitive &&
                        value.asJsonPrimitive.isNumber &&
                        value.toString().toLongOrNull() != null
                else -> false
            }
        requireControl(valid, "INVALID_FIELD", "Incorrect field type or integer range")
    }

    fun <T : Any> update(old: T, patch: JsonObject, allowed: Set<String>? = null): T {
        val fields =
            generateSequence(old.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
                .associateBy { it.name }
        ControlJson.keys(patch, allowed ?: fields.keys)
        patch.entrySet().forEach { (key, value) ->
            requireControl(
                !value.toString().contains(ControlJson.REDACTED),
                "REDACTED_INPUT",
                "Omit redacted fields from patches",
            )
            check(value, fields.getValue(key).genericType)
        }
        val merged = ControlJson.gson.toJsonTree(old).asJsonObject
        patch.entrySet().forEach { (key, value) -> merged.add(key, value.deepCopy()) }
        @Suppress("UNCHECKED_CAST")
        return ControlJson.gson.fromJson(merged, old.javaClass) as T
    }
}

object ControlProfiles {
    private val classes by lazy {
        ProxyEntity::class
            .java
            .declaredFields
            .filter { AbstractBean::class.java.isAssignableFrom(it.type) }
            .associate {
                it.name.removeSuffix("Bean").lowercase() to
                    it.type.asSubclass(AbstractBean::class.java)
            }
    }

    fun protocol(profile: ProxyEntity): String =
        if (profile.vmessBean?.isVLESS == true) "vless"
        else classes.entries.first { it.value == profile.requireBean().javaClass }.key

    fun create(protocol: String): AbstractBean {
        val type =
            classes[if (protocol == "vless") "vmess" else protocol]
                ?: throw ControlError("UNKNOWN_PROTOCOL", "Unsupported profile protocol")
        return type.getDeclaredConstructor().newInstance().apply {
            if (protocol == "vless")
                (this as io.nekohasekai.sagernet.fmt.v2ray.VMessBean).alterId = -1
            initializeDefaultValues()
        }
    }

    fun schema(): JsonObject =
        JsonObject().apply {
            (classes.keys + "vless").sorted().forEach {
                add(it, ControlJson.redact(ControlJson.gson.toJsonTree(create(it))))
            }
        }
}

/** Explicit allowlist. Integer-valued Android preferences are encoded as strings in this app. */
object ControlSettings {
    val types =
        buildMap<String, String> {
            listOf(
                    Key.REMOTE_DNS,
                    Key.DIRECT_DNS,
                    Key.GLOBAL_CUSTOM_CONFIG,
                    Key.MIXED_USERNAME,
                    Key.MIXED_PASSWORD,
                    Key.INDIVIDUAL,
                    Key.TUN_UNRECOGNIZED_TRAFFIC,
                    Key.TUN_SYSTEM_DNS_TRAFFIC,
                    Key.TUN_DNS_WHITELIST,
                    Key.TUN_DOT_WHITELIST,
                    Key.TUN_DOH_WHITELIST,
                    Key.RULES_GEOSITE_URL,
                    Key.RULES_GEOIP_URL,
                    Key.CLASH_API_SECRET,
                )
                .forEach { put(it, "string") }
            listOf(
                    Key.MTU,
                    Key.MIXED_PORT,
                    Key.IPV6_MODE,
                    Key.TRAFFIC_SNIFFING,
                    Key.LOG_LEVEL,
                    Key.CERT_PROVIDER,
                )
                .forEach { put(it, "integer-string") }
            listOf(
                    Key.ENABLE_DNS_ROUTING,
                    Key.ENABLE_FAKEDNS,
                    Key.BYPASS_LAN,
                    Key.BYPASS_LAN_IN_CORE,
                    Key.RESOLVE_DESTINATION,
                    Key.CONCURRENT_DIAL,
                    Key.ALLOW_ACCESS,
                    Key.PROXY_APPS,
                    Key.BYPASS_MODE,
                    Key.STRICT_ROUTE,
                    Key.APPEND_HTTP_PROXY,
                    Key.REQUIRE_PROXY_IN_VPN,
                    Key.ENABLE_CLASH_API,
                    Key.HIDE_CLASH_API,
                    Key.GLOBAL_MODE,
                )
                .forEach { put(it, "boolean") }
        }

    fun decode(row: KeyValuePair): Any? =
        when (row.valueType) {
            KeyValuePair.TYPE_BOOLEAN -> row.boolean
            KeyValuePair.TYPE_STRING -> row.string
            else -> row.long
        }

    fun patch(snapshot: ControlSnapshot, values: JsonObject) {
        ControlJson.keys(values, types.keys)
        values.entrySet().forEach { (key, v) ->
            requireControl(
                !v.toString().contains(ControlJson.REDACTED),
                "REDACTED_INPUT",
                "Omit redacted fields from patches",
            )
            val row = KeyValuePair(key)
            when (types.getValue(key)) {
                "boolean" -> {
                    ControlTypes.check(v, Boolean::class.java)
                    row.put(v.asBoolean)
                }
                else -> {
                    ControlTypes.check(v, String::class.java)
                    if (types[key] == "integer-string") {
                        val n = v.asString.toIntOrNull()
                        requireControl(
                            n != null && n >= 0,
                            "INVALID_FIELD",
                            "Expected nonnegative integer string",
                        )
                        if (key == Key.MIXED_PORT)
                            requireControl(
                                n!! in 1..65535,
                                "INVALID_FIELD",
                                "Port must be 1..65535",
                            )
                        if (key == Key.MTU)
                            requireControl(
                                n!! in 576..65535,
                                "INVALID_FIELD",
                                "MTU must be 576..65535",
                            )
                    }
                    row.put(v.asString)
                }
            }
            snapshot.setPreference(row)
        }
    }
}

object ControlPatch {
    private val groupFields =
        setOf("name", "userOrder", "order", "isSelector", "frontProxy", "landingProxy", "forceUTLS")

    fun apply(before: ControlSnapshot, patch: JsonObject): ControlSnapshot {
        ControlJson.keys(patch, setOf("operations"))
        val ops = patch["operations"]
        requireControl(
            ops != null && ops.isJsonArray && ops.asJsonArray.size() in 1..256,
            "INVALID_PATCH",
            "Expected 1..256 operations",
        )
        val next = before.copy()
        ops.asJsonArray.forEach { element ->
            requireControl(element.isJsonObject, "INVALID_PATCH", "Expected operation object")
            val op = element.asJsonObject
            val kind = ControlJson.string(op, "op")
            fun values() =
                op["values"]?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw ControlError("INVALID_FIELD", "Expected values object")
            fun id() = ControlJson.id(op)
            when (kind) {
                "settings.patch" -> {
                    ControlJson.keys(op, setOf("op", "values"))
                    ControlSettings.patch(next, values())
                }
                "selection.set" -> {
                    ControlJson.keys(op, setOf("op", "id"))
                    next.setPreference(KeyValuePair(Key.PROFILE_ID).put(id()))
                }
                "profile.create" -> {
                    ControlJson.keys(
                        op,
                        setOf("op", "id", "groupId", "protocol", "values", "userOrder"),
                    )
                    val id =
                        if (op.has("id")) id() else (next.profiles.maxOfOrNull { it.id } ?: 0) + 1
                    requireControl(
                        next.profiles.none { it.id == id },
                        "DUPLICATE_ID",
                        "Profile ID exists",
                    )
                    val bean =
                        ControlTypes.update(
                            ControlProfiles.create(ControlJson.string(op, "protocol")),
                            values(),
                        )
                    next.profiles.add(
                        ProxyEntity(
                                id = id,
                                groupId = ControlJson.id(op, "groupId"),
                                userOrder =
                                    if (op.has("userOrder")) ControlJson.id(op, "userOrder")
                                    else (next.profiles.maxOfOrNull { it.userOrder } ?: 0) + 1,
                            )
                            .apply { putBean(bean) }
                    )
                }
                "profile.update" -> {
                    ControlJson.keys(op, setOf("op", "id", "values", "groupId", "userOrder"))
                    val entity =
                        next.profiles.firstOrNull { it.id == id() }
                            ?: throw ControlError("NOT_FOUND", "Profile does not exist")
                    if (op.has("values"))
                        entity.putBean(ControlTypes.update(entity.requireBean(), values()))
                    if (op.has("groupId")) entity.groupId = ControlJson.id(op, "groupId")
                    if (op.has("userOrder")) entity.userOrder = ControlJson.id(op, "userOrder")
                }
                "profile.delete" -> {
                    ControlJson.keys(op, setOf("op", "id"))
                    requireControl(
                        next.profiles.removeAll { it.id == id() },
                        "NOT_FOUND",
                        "Profile does not exist",
                    )
                }
                "group.create" -> {
                    ControlJson.keys(op, setOf("op", "id", "values"))
                    val id =
                        if (op.has("id")) id() else (next.groups.maxOfOrNull { it.id } ?: 0) + 1
                    requireControl(
                        next.groups.none { it.id == id },
                        "DUPLICATE_ID",
                        "Group ID exists",
                    )
                    next.groups.add(
                        ControlTypes.update(
                            ProxyGroup(
                                id = id,
                                name = "",
                                userOrder = (next.groups.maxOfOrNull { it.userOrder } ?: 0) + 1,
                            ),
                            values(),
                            groupFields,
                        )
                    )
                }
                "group.update" -> {
                    ControlJson.keys(op, setOf("op", "id", "values"))
                    val index = next.groups.indexOfFirst { it.id == id() }
                    requireControl(index >= 0, "NOT_FOUND", "Group does not exist")
                    next.groups[index] =
                        ControlTypes.update(next.groups[index], values(), groupFields)
                }
                "group.delete" -> {
                    ControlJson.keys(op, setOf("op", "id"))
                    requireControl(
                        next.groups.removeAll { it.id == id() && !it.ungrouped },
                        "NOT_FOUND",
                        "Group missing or is the default group",
                    )
                }
                "rule.create" -> {
                    ControlJson.keys(op, setOf("op", "id", "values"))
                    val id = if (op.has("id")) id() else (next.rules.maxOfOrNull { it.id } ?: 0) + 1
                    requireControl(
                        next.rules.none { it.id == id },
                        "DUPLICATE_ID",
                        "Rule ID exists",
                    )
                    requireControl(
                        !values().has("id"),
                        "INVALID_FIELD",
                        "Set ID at the operation level",
                    )
                    next.rules.add(
                        ControlTypes.update(
                            RuleEntity(
                                id = id,
                                userOrder = (next.rules.maxOfOrNull { it.userOrder } ?: 0) + 1,
                            ),
                            values(),
                        )
                    )
                }
                "rule.update" -> {
                    ControlJson.keys(op, setOf("op", "id", "values"))
                    val index = next.rules.indexOfFirst { it.id == id() }
                    requireControl(index >= 0, "NOT_FOUND", "Rule does not exist")
                    requireControl(!values().has("id"), "INVALID_FIELD", "Rule ID is immutable")
                    next.rules[index] = ControlTypes.update(next.rules[index], values())
                }
                "rule.delete" -> {
                    ControlJson.keys(op, setOf("op", "id"))
                    requireControl(
                        next.rules.removeAll { it.id == id() },
                        "NOT_FOUND",
                        "Rule does not exist",
                    )
                }
                else -> throw ControlError("UNKNOWN_OPERATION", "Unsupported patch operation")
            }
        }
        validateReferences(next)
        return next
    }

    fun validateReferences(s: ControlSnapshot) {
        requireControl(
            s.profiles.all { it.id > 0 } && s.groups.all { it.id > 0 } && s.rules.all { it.id > 0 },
            "INVALID_FIELD",
            "Entity ID overflow",
        )
        val ids = s.profiles.map { it.id }.toSet()
        val groups = s.groups.associateBy { it.id }
        fun exists(id: Long) =
            requireControl(id in ids, "MISSING_PROFILE", "Referenced profile does not exist: $id")
        if (s.selection() > 0) exists(s.selection())
        s.profiles.forEach {
            requireControl(it.groupId in groups, "MISSING_GROUP", "Profile group does not exist")
        }
        s.groups.forEach { g ->
            if (g.frontProxy > 0) exists(g.frontProxy)
            if (g.landingProxy > 0) exists(g.landingProxy)
        }
        s.rules.forEach { if (it.outbound > 0) exists(it.outbound) }
        val edges =
            s.profiles.associate { p ->
                val refs = mutableListOf<Long>()
                p.chainBean?.let {
                    requireControl(
                        it.proxies.isNotEmpty(),
                        "EMPTY_CHAIN",
                        "Chain must contain profiles",
                    )
                    refs.addAll(it.proxies)
                }
                p.proxySetBean?.let { b ->
                    requireControl(b.type in 0..1, "INVALID_FIELD", "Unknown proxy set type")
                    if (b.type == 0) refs.addAll(b.proxies)
                    else {
                        requireControl(
                            b.groupId in groups,
                            "MISSING_GROUP",
                            "Proxy set group does not exist",
                        )
                        val excluded =
                            if (b.groupFilterNotRegex.isBlank()) null
                            else
                                try {
                                    Regex(b.groupFilterNotRegex)
                                } catch (_: Exception) {
                                    throw ControlError(
                                        "INVALID_FIELD",
                                        "Invalid group filter regex",
                                    )
                                }
                        refs.addAll(
                            s.profiles
                                .filter {
                                    it.groupId == b.groupId &&
                                        it.id != p.id &&
                                        (excluded == null ||
                                            excluded.containsMatchIn(it.requireBean().name))
                                }
                                .map { it.id }
                        )
                    }
                    requireControl(
                        refs.isNotEmpty(),
                        "EMPTY_SET",
                        "Proxy set must contain profiles",
                    )
                }
                refs.forEach(::exists)
                p.id to refs
            }
        val visiting = mutableSetOf<Long>()
        val done = mutableSetOf<Long>()
        fun visit(id: Long, depth: Int) {
            requireControl(depth < 128, "CHAIN_DEPTH", "Profile graph is too deep")
            if (id in done) return
            requireControl(visiting.add(id), "CHAIN_CYCLE", "Profile references contain a cycle")
            edges.getValue(id).forEach { visit(it, depth + 1) }
            visiting.remove(id)
            done.add(id)
        }
        ids.forEach { visit(it, 0) }
    }
}
