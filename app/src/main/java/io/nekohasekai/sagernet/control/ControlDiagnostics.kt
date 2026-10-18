package io.nekohasekai.sagernet.control

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.io.File
import libcore.Libcore

/** App-private evidence survives a disabled core logger, process death and cache truncation. */
object ControlDiagnostics {
    private val directory
        get() = File(SagerNet.application.noBackupFilesDir, "diagnostics").apply { mkdirs() }

    val sources =
        listOf("core", "previous", "events", "crash-bg", "crash-main", "crash-native-bg", "crash-native-main")

    private fun file(source: String) =
        when (source) {
            "core" -> File(SagerNet.application.cacheDir, "neko.log")
            "previous" -> File(directory, "core-previous.log")
            "events" -> File(directory, "events.log")
            "crash-bg",
            "crash-main" -> File(directory, "$source.log")
            "crash-native-bg",
            "crash-native-main" -> File(directory, "$source.log").let {
                if (it.length() > 0) it else File(it.path + ".previous")
            }
            else -> throw ControlError("INVALID_FIELD", "Unknown log source")
        }

    @Synchronized
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        // Failure to record diagnostics must never prevent connection recovery.
        runCatching {
            val f = file("events")
            if (f.length() > 131072) {
                val tail = DiagnosticTail.read(f, null, 65536).text.substringAfter('\n', "")
                f.writeText(tail)
            }
            f.appendText(
                ControlJson.gson.toJson(
                    mapOf(
                        "timeMs" to System.currentTimeMillis(),
                        "pid" to Process.myPid(),
                        "event" to name,
                        "fields" to fields,
                    )
                ) + "\n"
            )
        }
    }

    fun processStart(isBg: Boolean) {
        if (!isBg) return
        runCatching {
            val current = file("core")
            if (current.length() > 0)
                file("previous").writeText(DiagnosticTail.read(current, null, 262144).text)
        }
        event("process.start")
    }

    fun crash(thread: Thread, error: Throwable) {
        runCatching {
            val source = if (SagerNet.application.isBgProcess) "crash-bg" else "crash-main"
            val target = file(source)
            val temp = File(target.path + ".tmp")
            temp.writeText(
                "timeMs=${System.currentTimeMillis()} thread=${thread.name}\n" +
                    error.stackTraceToString().take(131072)
            )
            check(temp.renameTo(target))
        }
    }

    private fun secrets(): List<String> {
        val values = mutableListOf<String>()
        fun visit(element: com.google.gson.JsonElement, key: String = "") {
            if (element.isJsonObject)
                element.asJsonObject.entrySet().forEach { (k, v) -> visit(v, k) }
            else if (element.isJsonArray) element.asJsonArray.forEach { visit(it, key) }
            else if (
                element.isJsonPrimitive &&
                    element.asJsonPrimitive.isString &&
                    ControlJson.redact(element, key).asString == ControlJson.REDACTED
            )
                values.add(element.asString)
        }
        val config = ControlFullConfig.export(ControlStore.capture())
        visit(config)
        // The type/value wrapper must keep preference context, including future opaque settings.
        config["preferences"].asJsonObject.entrySet().forEach { (_, row) ->
            val v = row.asJsonObject["value"]
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString) values.add(v.asString)
        }
        return values
    }

    fun read(params: com.google.gson.JsonObject): com.google.gson.JsonObject {
        ControlJson.keys(params, setOf("source", "maxBytes", "cursor", "includeSecrets"))
        val source = if (params.has("source")) ControlJson.string(params, "source") else "core"
        val maxBytes =
            if (params.has("maxBytes")) {
                ControlTypes.check(params["maxBytes"], Int::class.java)
                params["maxBytes"].asInt
            } else 65536
        requireControl(maxBytes in 256..262144, "INVALID_FIELD", "maxBytes must be 256..262144")
        if (params.has("includeSecrets"))
            ControlTypes.check(params["includeSecrets"], Boolean::class.java)
        val cursor = if (params.has("cursor")) ControlJson.string(params, "cursor") else null
        // Keep the panic and crashing goroutine at the beginning of a large native
        // dump. Subsequent cursor reads still retrieve the remaining frames.
        val result =
            DiagnosticTail.read(file(source), cursor, maxBytes, source.startsWith("crash-native-"))
        val raw = params["includeSecrets"]?.asBoolean == true
        return ControlJson.objectOf(
            "source" to source,
            "text" to
                if (raw) result.text
                else if (source == "events")
                    DiagnosticRedaction.redactEventLines(result.text, secrets())
                else DiagnosticRedaction.redact(result.text, secrets()),
            "cursor" to result.cursor,
            "reset" to result.reset,
            "truncated" to result.truncated,
            "redacted" to !raw,
            "coreLoggingEnabled" to (DataStore.logLevel > 0),
        )
    }

    fun snapshot(): com.google.gson.JsonObject {
        val am = SagerNet.application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits: List<Any> =
            if (Build.VERSION.SDK_INT >= 30)
                runCatching {
                        am.getHistoricalProcessExitReasons(BuildConfig.APPLICATION_ID, 0, 16).map {
                            mapOf(
                                "timestampMs" to it.timestamp,
                                "pid" to it.pid,
                                "process" to it.processName,
                                "reason" to it.reason,
                                "status" to it.status,
                                "importance" to it.importance,
                                "pssKiB" to it.pss,
                                "rssKiB" to it.rss,
                            )
                        }
                    }
                    .getOrDefault(emptyList())
            else emptyList()
        val network = SagerNet.underlyingNetwork
        val connectivity = SagerNet.connectivity
        val link = network?.let { connectivity.getLinkProperties(it) }
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val networkInfo =
            mapOf(
                "handle" to network?.networkHandle,
                "interface" to link?.interfaceName,
                "wifi" to
                    (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true),
                "cellular" to
                    (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ==
                        true),
                "validated" to
                    (caps?.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) == true),
                "dnsServers" to link?.dnsServers?.mapNotNull { it.hostAddress },
                "privateDnsActive" to
                    if (Build.VERSION.SDK_INT >= 28) link?.isPrivateDnsActive else null,
            )
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val threads =
            Thread.getAllStackTraces()
                .entries
                .sortedBy { it.key.id }
                .take(128)
                .map { (t, frames) ->
                    mapOf(
                        "id" to t.id,
                        "state" to t.state.name,
                        "name" to DiagnosticRedaction.redact(t.name),
                        "frames" to frames.take(48).map { it.toString() },
                    )
                }
        return ControlJson.objectOf(
            "schema" to "nekobox.diagnostics",
            "schemaVersion" to 1,
            "timeMs" to System.currentTimeMillis(),
            "appVersion" to BuildConfig.VERSION_NAME,
            "package" to BuildConfig.APPLICATION_ID,
            "nativeVersion" to Libcore.versionBox(),
            "androidApi" to Build.VERSION.SDK_INT,
            "pid" to Process.myPid(),
            "uid" to Process.myUid(),
            "totalPssKiB" to memory.totalPss,
            "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize(),
            "javaHeapBytes" to
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
            "coreLoggingEnabled" to (DataStore.logLevel > 0),
            "power" to mapOf(
                "interactive" to SagerNet.power.isInteractive,
                "powerSaveMode" to SagerNet.power.isPowerSaveMode,
                "deviceIdle" to if (Build.VERSION.SDK_INT >= 23) SagerNet.power.isDeviceIdleMode else null,
                "batteryOptimizationExempt" to if (Build.VERSION.SDK_INT >= 23)
                    SagerNet.power.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID) else null,
            ),
            "exits" to exits,
            "threads" to threads,
            "logSources" to sources,
            "network" to networkInfo,
            "native" to ControlJson.parseObject(Libcore.runtimeDiagnostics()),
        )
    }
}
