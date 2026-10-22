package io.nekohasekai.sagernet.control

import android.util.AtomicFile
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cross-database undo journal, protected by ControlAccess in every application process. */
object ControlStore {
    private data class Journal(
        val id: String,
        val created: Long,
        val before: ControlSnapshot,
        val after: ControlSnapshot,
    )

    private val journal
        get() = AtomicFile(File(ControlAccess.directory, "journal.json"))

    private val checkpoints
        get() = File(ControlAccess.directory, "checkpoints").apply { mkdirs() }

    private fun atomic(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun revision(s: ControlSnapshot): String =
        ControlAccess.exclusive {
            val file = AtomicFile(File(ControlAccess.directory, "revision.key"))
            val key =
                if (file.baseFile.exists()) file.readFully()
                else
                    ByteArray(32).apply {
                        SecureRandom().nextBytes(this)
                        atomic(file, this)
                    }
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(ControlJson.canonical(s.revisionData()).toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
            }
        }

    fun capture(): ControlSnapshot = ControlAccess.exclusive { ControlSnapshot.capture() }

    fun recoverLocked() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        val data = ControlJson.gson.fromJson(String(journal.readFully()), Journal::class.java)
        persist(data.after, data.before)
        journal.delete()
    }

    fun apply(expected: String, next: ControlSnapshot): JsonObject =
        ControlAccess.exclusive {
            requireControl(
                DataStore.configurationStore.getBoolean("adbControlEnabled", false),
                "DISABLED",
                "ADB control was disabled",
            )
            val before = ControlSnapshot.capture()
            requireControl(
                revision(before) == expected,
                "REVISION_CONFLICT",
                "Configuration changed; export and plan again",
            )
            val data =
                Journal(UUID.randomUUID().toString(), System.currentTimeMillis(), before, next)
            val bytes = ControlJson.gson.toJson(data).toByteArray()
            requireControl(
                bytes.size <= 32 * 1024 * 1024,
                "CONFIG_TOO_LARGE",
                "Configuration exceeds checkpoint limit",
            )
            atomic(journal, bytes)
            try {
                persist(before, next)
                atomic(AtomicFile(File(checkpoints, data.id + ".json")), bytes)
                journal.delete()
            } catch (e: Exception) {
                // Leave the journal in place if recovery itself fails. No reader can see a partial
                // commit.
                recoverLocked()
                throw e
            }
            checkpoints
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(20)
                ?.forEach { AtomicFile(it).delete() }
            val savedRevision = revision(ControlSnapshot.capture())
            val running = DataStore.baseService?.data?.proxy?.configurationRevision
            ControlJson.objectOf(
                "revision" to savedRevision,
                "checkpoint" to data.id,
                "restartRequired" to (DataStore.serviceState.connected && running != savedRevision),
            )
        }

    fun rollback(id: String, expected: String): JsonObject {
        requireControl(id.matches(Regex("[a-f0-9-]{36}")), "INVALID_FIELD", "Invalid checkpoint ID")
        return ControlAccess.exclusive {
            val path = File(checkpoints, "$id.json")
            requireControl(path.exists(), "NOT_FOUND", "Checkpoint does not exist")
            val data =
                ControlJson.gson.fromJson(String(AtomicFile(path).readFully()), Journal::class.java)
            val current = ControlSnapshot.capture()
            requireControl(
                revision(current) == expected && revision(data.after) == expected,
                "REVISION_CONFLICT",
                "Rollback requires the checkpoint's resulting revision",
            )
            // Restore only keys/rows changed by this checkpoint; volatile counters are retained by
            // persist.
            apply(expected, data.before)
        }
    }

    fun list(): List<JsonObject> =
        ControlAccess.exclusive {
            checkpoints
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    val data =
                        ControlJson.gson.fromJson(
                            String(AtomicFile(it).readFully()),
                            Journal::class.java,
                        )
                    ControlJson.objectOf(
                        "id" to data.id,
                        "created" to data.created,
                        "beforeRevision" to revision(data.before),
                        "afterRevision" to revision(data.after),
                    )
                } ?: emptyList()
        }

    private fun equal(a: Any?, b: Any?) =
        ControlJson.gson.toJsonTree(a) == ControlJson.gson.toJsonTree(b)

    private fun profileConfig(p: ProxyEntity) =
        ControlJson.objectOf(
            "id" to p.id,
            "groupId" to p.groupId,
            "userOrder" to p.userOrder,
            "type" to p.type,
            "bean" to p.requireBean(),
            "uuid" to p.uuid,
        )

    private val ignoredPreferences =
        setOf(
            io.nekohasekai.sagernet.Key.PROFILE_CURRENT,
            io.nekohasekai.sagernet.Key.PROFILE_GROUP,
            "adbControlEnabled",
        )

    private fun persist(from: ControlSnapshot, to: ControlSnapshot) {
        val db = SagerDatabase.instance
        // Raw DAOs here avoid lazy-wrapper initialization during recovery. The caller owns the OS
        // lock.
        db.runInTransaction {
            val groups = db.groupDao()
            val profiles = db.proxyDao()
            val rules = db.rulesDao()
            from.profiles
                .filter { a -> to.profiles.none { it.id == a.id } }
                .forEach { profiles.deleteById(it.id) }
            from.rules
                .filter { a -> to.rules.none { it.id == a.id } }
                .forEach { rules.deleteById(it.id) }
            from.groups
                .filter { a -> to.groups.none { it.id == a.id } }
                .forEach { groups.deleteById(it.id) }
            to.groups
                .filter { a -> !equal(from.groups.firstOrNull { it.id == a.id }, a) }
                .forEach {
                    if (groups.getById(it.id) == null) groups.createGroup(it)
                    else groups.updateGroup(it)
                }
            to.profiles
                .filter { a ->
                    from.profiles
                        .firstOrNull { it.id == a.id }
                        ?.let { profileConfig(it) == profileConfig(a) } != true
                }
                .forEach { row ->
                    val current = profiles.getById(row.id)
                    val update =
                        ControlJson.gson.fromJson(
                            ControlJson.gson.toJson(row),
                            ProxyEntity::class.java,
                        )
                    if (current != null) {
                        update.tx = current.tx
                        update.rx = current.rx
                        update.status = current.status
                        update.ping = current.ping
                        update.error = current.error
                        profiles.updateProxy(update)
                    } else profiles.addProxy(update)
                }
            to.rules
                .filter { a -> !equal(from.rules.firstOrNull { it.id == a.id }, a) }
                .forEach {
                    if (rules.getById(it.id) == null) rules.createRule(it) else rules.updateRule(it)
                }
        }
        val prefs = PublicDatabase.instance
        prefs.runInTransaction {
            val dao = prefs.keyValuePairDao()
            from.preferences
                .filter { it.key !in ignoredPreferences }
                .filter { a -> to.preferences.none { it.key == a.key } }
                .forEach { dao.delete(it.key) }
            to.preferences
                .filter { it.key !in ignoredPreferences }
                .filter { a -> !equal(from.preferences.firstOrNull { it.key == a.key }, a) }
                .forEach { dao.put(it) }
        }
    }
}
