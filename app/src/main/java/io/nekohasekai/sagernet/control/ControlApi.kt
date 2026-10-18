package io.nekohasekai.sagernet.control

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore

object ControlApi {
    fun execute(request: JsonObject): JsonObject {
        return try {
            ControlJson.keys(request, setOf("apiVersion", "method", "params"))
            requireControl(
                request["apiVersion"]?.toString() == "1",
                "API_VERSION",
                "Expected apiVersion 1",
            )
            val params = request["params"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val result = dispatch(ControlJson.string(request, "method"), params)
            ControlJson.objectOf("ok" to true, "apiVersion" to 1, "result" to result)
        } catch (e: ControlError) {
            error(e.code, e.message ?: e.code)
        } catch (_: Exception) {
            error("INTERNAL_ERROR", "Operation failed; no diagnostic secrets are returned")
        }
    }

    fun error(code: String, message: String) =
        ControlJson.objectOf(
            "ok" to false,
            "apiVersion" to 1,
            "error" to ControlJson.objectOf("code" to code, "message" to message),
        )

    private fun dispatch(method: String, p: JsonObject): Any {
        when (method) {
            "capabilities" -> {
                ControlJson.keys(p, emptySet())
                return ControlJson.objectOf(
                    "apiVersion" to 1,
                    "maxRequestBytes" to 1048576,
                    "secretExports" to true,
                    "methods" to
                        listOf(
                            "capabilities",
                            "status",
                            "config.export",
                            "config.plan",
                            "config.apply",
                            "config.rollback",
                            "checkpoints.list",
                            "vpn.start",
                            "vpn.stop",
                            "vpn.restart",
                        ),
                    "settings" to ControlSettings.types,
                    "profiles" to ControlProfiles.schema(),
                    "validation" to
                        listOf("typed-fields", "references", "generated-config", "native-options"),
                )
            }
            "status" -> {
                ControlJson.keys(p, emptySet())
                val snapshot = ControlStore.capture()
                val revision = ControlStore.revision(snapshot)
                val state = DataStore.baseService?.data?.state ?: BaseService.State.Stopped
                val running =
                    if (state == BaseService.State.Connected)
                        DataStore.baseService?.data?.proxy?.configurationRevision
                    else null
                return ControlJson.objectOf(
                    "savedRevision" to revision,
                    "runningRevision" to running,
                    "state" to state.name,
                    "selectedProfile" to snapshot.selection(),
                    "runningProfile" to DataStore.baseService?.data?.proxy?.profile?.id,
                    "restartRequired" to (running != null && running != revision),
                )
            }
            "config.export" -> {
                ControlJson.keys(p, setOf("includeSecrets"))
                if (p.has("includeSecrets"))
                    ControlTypes.check(p["includeSecrets"], Boolean::class.java)
                val s = ControlStore.capture()
                val content = s.export()
                return ControlJson.objectOf(
                    "revision" to ControlStore.revision(s),
                    "config" to
                        if (p["includeSecrets"]?.asBoolean == true) content
                        else ControlJson.redact(content),
                )
            }
            "config.plan",
            "config.apply" -> {
                ControlJson.keys(
                    p,
                    if (method == "config.apply") setOf("patch", "expectedRevision")
                    else setOf("patch"),
                )
                val patch =
                    p["patch"]?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: throw ControlError("INVALID_PATCH", "Expected patch object")
                val before = ControlStore.capture()
                val revision = ControlStore.revision(before)
                if (method == "config.apply")
                    requireControl(
                        ControlJson.string(p, "expectedRevision") == revision,
                        "REVISION_CONFLICT",
                        "Configuration changed; export and plan again",
                    )
                val next = ControlPatch.apply(before, patch)
                val roots = ControlValidation.validate(next)
                if (method == "config.apply") return ControlStore.apply(revision, next)
                return ControlJson.objectOf(
                    "revision" to revision,
                    "candidateRevision" to ControlStore.revision(next),
                    "changes" to ControlJson.diff(before.export(), next.export()),
                    "validatedProfiles" to roots,
                    "assignedProfileIds" to
                        next.profiles
                            .filter { n -> before.profiles.none { it.id == n.id } }
                            .map { it.id },
                    "assignedGroupIds" to
                        next.groups
                            .filter { n -> before.groups.none { it.id == n.id } }
                            .map { it.id },
                    "assignedRuleIds" to
                        next.rules
                            .filter { n -> before.rules.none { it.id == n.id } }
                            .map { it.id },
                    "networkChecked" to false,
                )
            }
            "config.rollback" -> {
                ControlJson.keys(p, setOf("checkpoint", "expectedRevision"))
                return ControlStore.rollback(
                    ControlJson.string(p, "checkpoint"),
                    ControlJson.string(p, "expectedRevision"),
                )
            }
            "checkpoints.list" -> {
                ControlJson.keys(p, emptySet())
                return ControlStore.list()
            }
            "vpn.start",
            "vpn.stop",
            "vpn.restart" -> {
                ControlJson.keys(p, emptySet())
                if (method != "vpn.stop") {
                    requireControl(
                        DataStore.selectedProxy > 0,
                        "NO_SELECTION",
                        "Select a profile first",
                    )
                    requireControl(
                        DataStore.serviceMode != Key.MODE_VPN ||
                            android.net.VpnService.prepare(SagerNet.application) == null,
                        "VPN_PERMISSION_REQUIRED",
                        "Grant VPN permission in the application first",
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    when (method) {
                        "vpn.stop" -> SagerNet.stopService()
                        "vpn.restart" ->
                            if (DataStore.serviceState == BaseService.State.Connected)
                                SagerNet.reloadService()
                            else SagerNet.startService()
                        else -> SagerNet.startService()
                    }
                }
                return ControlJson.objectOf(
                    "accepted" to true,
                    "action" to method,
                    "completion" to "poll status",
                )
            }
            else -> throw ControlError("UNKNOWN_METHOD", "Unsupported API method")
        }
    }
}
