package io.nekohasekai.sagernet.control

import com.google.gson.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase

class ControlSnapshot(
    var groups: MutableList<ProxyGroup> = mutableListOf(),
    var profiles: MutableList<ProxyEntity> = mutableListOf(),
    var rules: MutableList<RuleEntity> = mutableListOf(),
    var preferences: MutableList<KeyValuePair> = mutableListOf(),
) {
    fun copy(): ControlSnapshot =
        ControlJson.gson.fromJson(ControlJson.gson.toJson(this), ControlSnapshot::class.java)

    fun selection(): Long = preferences.firstOrNull { it.key == Key.PROFILE_ID }?.long ?: 0

    fun setPreference(row: KeyValuePair) {
        preferences.removeAll { it.key == row.key }
        preferences.add(row)
    }

    fun export(): JsonObject =
        ControlJson.objectOf(
            "groups" to groups.sortedBy { it.id },
            "profiles" to
                profiles
                    .sortedBy { it.id }
                    .map {
                        ControlJson.objectOf(
                            "id" to it.id,
                            "groupId" to it.groupId,
                            "userOrder" to it.userOrder,
                            "protocol" to ControlProfiles.protocol(it),
                            "values" to it.requireBean(),
                        )
                    },
            "rules" to rules.sortedBy { it.id },
            "settings" to
                preferences
                    .filter { it.key in ControlSettings.types }
                    .associate { it.key to ControlSettings.decode(it) },
            "selectedProfile" to selection(),
        )

    fun revisionData(): JsonObject =
        export().apply {
            // All persistent preference writers participate, including unknown future settings.
            // Ignore runtime/UI selection metadata.
            add(
                "allPreferences",
                ControlJson.gson.toJsonTree(
                    preferences
                        .filter {
                            it.key !in
                                setOf(Key.PROFILE_CURRENT, Key.PROFILE_GROUP, "adbControlEnabled")
                        }
                        .sortedBy { it.key }
                ),
            )
        }

    fun invoke(table: String, method: String, args: Array<out Any?>): Any? {
        // Config generation is synchronous. Return copies so builders cannot alter the candidate
        // being checked.
        val view = this
        val result =
            when (table) {
                "preferences" ->
                    when (method) {
                        "get" -> view.preferences.firstOrNull { it.key == args[0] }
                        "all" -> view.preferences
                        else ->
                            throw ControlError(
                                "PREVIEW_WRITE",
                                "Config generation attempted a preference write",
                            )
                    }
                "groups" ->
                    when (method) {
                        "getById" -> view.groups.firstOrNull { it.id == args[0] }
                        "allGroups" -> view.groups.sortedBy { it.userOrder }
                        "subscriptions" -> view.groups.filter { it.type == 1 }
                        else ->
                            throw ControlError(
                                "PREVIEW_WRITE",
                                "Unsupported group access during validation",
                            )
                    }
                "profiles" ->
                    when (method) {
                        "getById" -> view.profiles.firstOrNull { it.id == args[0] }
                        "getAll" -> view.profiles
                        "getAllIds" -> view.profiles.map { it.id }.sorted()
                        "getEntities" -> view.profiles.filter { it.id in (args[0] as List<*>) }
                        "getByGroup" ->
                            view.profiles.filter { it.groupId == args[0] }.sortedBy { it.userOrder }
                        "getIdsByGroup" ->
                            view.profiles
                                .filter { it.groupId == args[0] }
                                .sortedBy { it.userOrder }
                                .map { it.id }
                        "countByGroup" -> view.profiles.count { it.groupId == args[0] }.toLong()
                        else ->
                            throw ControlError(
                                "PREVIEW_WRITE",
                                "Unsupported profile access during validation",
                            )
                    }
                "rules" ->
                    when (method) {
                        "allRules" -> view.rules.sortedBy { it.userOrder }
                        "enabledRules" ->
                            view.rules.filter { it.enabled == args[0] }.sortedBy { it.userOrder }
                        "getById" -> view.rules.firstOrNull { it.id == args[0] }
                        else ->
                            throw ControlError(
                                "PREVIEW_WRITE",
                                "Unsupported rule access during validation",
                            )
                    }
                else -> error("Unknown configuration store")
            }
        fun duplicate(row: Any?): Any? =
            when (row) {
                is ProxyEntity,
                is ProxyGroup,
                is RuleEntity,
                is KeyValuePair ->
                    ControlJson.gson.fromJson(ControlJson.gson.toJson(row), row.javaClass)
                else -> row
            }
        return if (result is List<*>) result.map(::duplicate) else duplicate(result)
    }

    companion object {
        fun capture(): ControlSnapshot =
            ControlSnapshot(
                SagerDatabase.instance.groupDao().allGroups().toMutableList(),
                SagerDatabase.instance.proxyDao().getAll().toMutableList(),
                SagerDatabase.instance.rulesDao().allRules().toMutableList(),
                PublicDatabase.instance.keyValuePairDao().all().toMutableList(),
            )
    }
}
