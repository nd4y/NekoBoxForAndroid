package io.nekohasekai.sagernet.control

import com.google.gson.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.ByteBuffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** A versioned, editable document. Configuration is portable; device authorization stays local. */
object ControlFullConfig {
    const val SCHEMA = "nekobox.full-config"
    val localKeys = setOf(Key.PROFILE_CURRENT, Key.PROFILE_GROUP, "adbControlEnabled")

    fun export(snapshot: ControlSnapshot): JsonObject {
        val config = snapshot.export()
        config.remove("settings")
        config["profiles"].asJsonArray.forEach { item ->
            val id = item.asJsonObject["id"].asLong
            item.asJsonObject.addProperty(
                "entityUuid",
                snapshot.profiles.first { it.id == id }.uuid,
            )
        }
        config.add(
            "preferences",
            JsonObject().apply {
                snapshot.preferences
                    .filter { it.key !in localKeys && it.key != Key.PROFILE_ID }
                    .sortedBy { it.key }
                    .forEach { add(it.key, preference(it)) }
            },
        )
        return config
    }

    /**
     * Preference wrappers must not discard the key's redaction context. Unknown values stay hidden.
     */
    fun redact(config: JsonObject): JsonObject {
        val result = ControlJson.redact(config).asJsonObject
        val preferences = JsonObject()
        config["preferences"].asJsonObject.entrySet().forEach { (key, value) ->
            val row = value.deepCopy().asJsonObject
            row.add(
                "value",
                if (key in ControlSettings.types) ControlJson.redact(row["value"], key)
                else JsonPrimitive(ControlJson.REDACTED),
            )
            preferences.add(key, row)
        }
        result.add("preferences", preferences)
        return result
    }

    private fun preference(row: KeyValuePair): JsonObject {
        val type: String
        val value: Any?
        when (row.valueType) {
            KeyValuePair.TYPE_BOOLEAN -> {
                type = "boolean"
                value = row.boolean
            }
            KeyValuePair.TYPE_FLOAT -> {
                type = "float"
                value = row.float
            }
            KeyValuePair.TYPE_INT -> {
                type = "int"
                value = row.long
            }
            KeyValuePair.TYPE_LONG -> {
                type = "long"
                value = row.long
            }
            KeyValuePair.TYPE_STRING -> {
                type = "string"
                value = row.string
            }
            KeyValuePair.TYPE_STRING_SET -> {
                type = "string-set"
                value = row.stringSet!!.sorted()
            }
            else ->
                return ControlJson.objectOf(
                    "type" to "raw",
                    "valueType" to row.valueType,
                    "value" to row.value.toByteString().base64(),
                )
        }
        return ControlJson.objectOf("type" to type, "value" to value)
    }

    private fun decodePreference(
        key: String,
        item: JsonObject,
        before: KeyValuePair?,
    ): KeyValuePair {
        if (before != null && preference(before) == item) return before
        val type = ControlJson.string(item, "type")
        ControlJson.keys(
            item,
            if (type == "raw") setOf("type", "value", "valueType") else setOf("type", "value"),
        )
        val v = item["value"] ?: throw ControlError("INVALID_FIELD", "Preference value required")
        val row = KeyValuePair(key)
        when (type) {
            "boolean" -> {
                ControlTypes.check(v, Boolean::class.java)
                row.put(v.asBoolean)
            }
            "string" -> {
                ControlTypes.check(v, String::class.java)
                row.put(v.asString)
            }
            "long" -> {
                ControlTypes.check(v, Long::class.java)
                row.put(v.asLong)
            }
            "int" -> {
                ControlTypes.check(v, Int::class.java)
                row.put(v.asInt)
            }
            "float" -> {
                requireControl(
                    v.isJsonPrimitive &&
                        v.asJsonPrimitive.isNumber &&
                        v.asString.toFloatOrNull()?.isFinite() == true,
                    "INVALID_FIELD",
                    "Expected finite float",
                )
                row.put(v.asFloat)
            }
            "string-set" -> {
                requireControl(v.isJsonArray, "INVALID_FIELD", "Expected string array")
                val items =
                    v.asJsonArray.map {
                        ControlTypes.check(it, String::class.java)
                        it.asString
                    }
                requireControl(
                    items.size == items.toSet().size,
                    "INVALID_FIELD",
                    "Duplicate set item",
                )
                // KeyValuePair.put(Set) historically counts UTF-16 characters rather than UTF-8
                // bytes.
                row.valueType = KeyValuePair.TYPE_STRING_SET
                row.value =
                    ByteArrayOutputStream()
                        .apply {
                            items.sorted().forEach { item ->
                                val bytes = item.toByteArray(Charsets.UTF_8)
                                write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                                write(bytes)
                            }
                        }
                        .toByteArray()
            }
            "raw" -> {
                ControlTypes.check(item["valueType"] ?: JsonNull.INSTANCE, Int::class.java)
                ControlTypes.check(v, String::class.java)
                val n = item["valueType"].asInt
                requireControl(
                    n !in 1..6 && n >= 0,
                    "INVALID_FIELD",
                    "Use typed preference encoding",
                )
                row.valueType = n
                row.value =
                    try {
                        v.asString.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException()
                    } catch (_: IllegalArgumentException) {
                        throw ControlError("INVALID_FIELD", "Invalid raw preference")
                    }
            }
            else -> throw ControlError("INVALID_FIELD", "Unknown preference type")
        }
        return row
    }

    private fun fields(clazz: Class<*>) =
        generateSequence(clazz as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
            .associateBy { it.name }

    private fun check(
        value: JsonElement,
        type: Type,
        nullable: Boolean = false,
        path: String = "config",
    ) {
        if (value.isJsonNull) {
            requireControl(nullable, "INVALID_FIELD", "Null is not allowed for this field")
            return
        }
        if (
            type is ParameterizedType &&
                type.rawType is Class<*> &&
                Collection::class.java.isAssignableFrom(type.rawType as Class<*>)
        ) {
            requireControl(value.isJsonArray, "INVALID_FIELD", "Expected array")
            value.asJsonArray.forEach { check(it, type.actualTypeArguments[0], path = path + "/*") }
            return
        }
        val clazz =
            type as? Class<*> ?: throw ControlError("INVALID_FIELD", "Unsupported document type")
        if (
            clazz == String::class.java ||
                clazz.isPrimitive ||
                Number::class.java.isAssignableFrom(clazz) ||
                clazz == java.lang.Boolean::class.java
        ) {
            try {
                ControlTypes.check(value, type)
            } catch (e: ControlError) {
                throw ControlError(e.code, path + ": " + e.message)
            }
            return
        }
        requireControl(value.isJsonObject, "INVALID_FIELD", "Expected object")
        val members = fields(clazz)
        ControlJson.keys(value.asJsonObject, members.keys)
        requireControl(
            value.asJsonObject.keySet() == members.keys,
            "INCOMPLETE_CONFIG",
            "Full object must retain every field; use patches for partial edits",
        )
        val defaults = clazz.getDeclaredConstructor().newInstance()
        value.asJsonObject.entrySet().forEach { (name, item) ->
            val field = members.getValue(name)
            field.isAccessible = true
            check(item, field.genericType, field.get(defaults) == null, path + "/" + name)
        }
    }

    private fun <T> decode(value: JsonElement, clazz: Class<T>): T {
        check(value, clazz)
        return ControlJson.gson.fromJson(value, clazz)
    }

    fun parse(before: ControlSnapshot, document: JsonObject): ControlSnapshot {
        ControlJson.keys(document, setOf("schema", "schemaVersion", "revision", "config"))
        requireControl(
            document["schema"]?.asString == SCHEMA && document["schemaVersion"]?.toString() == "1",
            "CONFIG_SCHEMA",
            "Expected nekobox.full-config schemaVersion 1",
        )
        requireControl(
            !document.toString().contains(ControlJson.REDACTED),
            "REDACTED_INPUT",
            "A full import requires an unredacted document; use pull or export --full --include-secrets",
        )
        val config =
            document["config"]?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw ControlError("INVALID_CONFIG", "Expected config object")
        val required = setOf("profiles", "groups", "rules", "preferences", "selectedProfile")
        ControlJson.keys(config, required)
        requireControl(
            config.keySet() == required,
            "INVALID_CONFIG",
            "Full document must contain every configuration section",
        )
        fun array(key: String) =
            config[key].takeIf { it.isJsonArray }?.asJsonArray
                ?: throw ControlError("INVALID_FIELD", "Expected array section")
        requireControl(
            array("profiles").size() <= 512 &&
                array("groups").size() <= 512 &&
                array("rules").size() <= 4096,
            "VALIDATION_LIMIT",
            "Too many configuration entities",
        )
        val next = before.copy()
        next.groups =
            array("groups")
                .map {
                    decode(it, ProxyGroup::class.java).apply {
                        requireControl(
                            type != 1 || subscription != null,
                            "INVALID_CONFIG",
                            "Subscription data required",
                        )
                        initializeDefaultValues()
                    }
                }
                .toMutableList()
        next.rules = array("rules").map { decode(it, RuleEntity::class.java) }.toMutableList()
        next.profiles =
            array("profiles")
                .map { item ->
                    requireControl(item.isJsonObject, "INVALID_FIELD", "Expected profile object")
                    val p = item.asJsonObject
                    ControlJson.keys(
                        p,
                        setOf("id", "groupId", "userOrder", "protocol", "values", "entityUuid"),
                    )
                    val id = ControlJson.id(p)
                    val protocol = ControlJson.string(p, "protocol")
                    val template = ControlProfiles.create(protocol)
                    val values =
                        p["values"]
                            ?: throw ControlError("INVALID_FIELD", "Profile values required")
                    val bean =
                        decode(values, template.javaClass).apply { initializeDefaultValues() }
                    val old = before.profiles.firstOrNull { it.id == id }
                    val profile =
                        old?.let {
                            ControlJson.gson.fromJson(
                                ControlJson.gson.toJson(it),
                                ProxyEntity::class.java,
                            )
                        } ?: ProxyEntity(id = id)
                    profile.putBean(bean)
                    requireControl(
                        ControlProfiles.protocol(profile) == protocol,
                        "INVALID_FIELD",
                        "Profile protocol disagrees with values",
                    )
                    profile.groupId = ControlJson.id(p, "groupId")
                    ControlTypes.check(p["userOrder"] ?: JsonNull.INSTANCE, Long::class.java)
                    profile.userOrder = p["userOrder"].asLong
                    profile.uuid = ControlJson.string(p, "entityUuid")
                    profile
                }
                .toMutableList()
        requireControl(
            next.groups.isNotEmpty() && next.groups.count { it.ungrouped } == 1,
            "INVALID_CONFIG",
            "Exactly one default group is required",
        )
        for (ids in
            listOf(
                next.profiles.map { it.id },
                next.groups.map { it.id },
                next.rules.map { it.id },
            )) {
            requireControl(ids.size == ids.toSet().size, "DUPLICATE_ID", "Duplicate entity ID")
        }
        val prefs = config["preferences"]
        requireControl(prefs.isJsonObject, "INVALID_FIELD", "Expected preferences object")
        requireControl(
            prefs.asJsonObject.size() <= 2048,
            "VALIDATION_LIMIT",
            "Too many preferences",
        )
        next.preferences = before.preferences.filter { it.key in localKeys }.toMutableList()
        prefs.asJsonObject.entrySet().forEach { (key, value) ->
            requireControl(
                key !in localKeys && key != Key.PROFILE_ID && key.isNotEmpty() && key.length <= 256,
                "LOCAL_STATE",
                "Device authorization/runtime preferences cannot be imported",
            )
            requireControl(value.isJsonObject, "INVALID_FIELD", "Expected typed preference")
            val row =
                decodePreference(
                    key,
                    value.asJsonObject,
                    before.preferences.firstOrNull { it.key == key },
                )
            if (key in ControlSettings.types) {
                val probe = ControlSnapshot()
                ControlSettings.patch(
                    probe,
                    ControlJson.objectOf(key to ControlSettings.decode(row)),
                )
            }
            next.preferences.add(row)
        }
        val selected = config["selectedProfile"]
        ControlTypes.check(selected, Long::class.java)
        requireControl(selected.asLong >= 0, "INVALID_FIELD", "Invalid selected profile")
        next.setPreference(KeyValuePair(Key.PROFILE_ID).put(selected.asLong))
        ControlPatch.validateReferences(next)
        return next
    }
}
