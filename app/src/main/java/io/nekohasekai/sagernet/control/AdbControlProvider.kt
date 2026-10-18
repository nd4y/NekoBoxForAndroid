package io.nekohasekai.sagernet.control

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.database.DataStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Versioned, opt-in control surface for the Android shell UID. No socket listener or exported file
 * path.
 */
class AdbControlProvider : ContentProvider() {
    private val writers = Executors.newFixedThreadPool(2)
    private val pending = ConcurrentHashMap<String, Future<*>>()
    private val ioLock = Any()
    private val executions = ConcurrentHashMap.newKeySet<String>()
    private val directory
        get() = File(ControlAccess.directory, "requests").apply { mkdirs() }

    override fun onCreate() = true

    private fun authorize() {
        // Capture/check the Binder identity before clearing it or scheduling any work.
        if (Binder.getCallingUid() != 2000) throw SecurityException("ADB_SHELL_REQUIRED")
        val identity = Binder.clearCallingIdentity()
        try {
            if (!DataStore.configurationStore.getBoolean("adbControlEnabled", false))
                throw SecurityException("ADB_CONTROL_DISABLED")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun id(value: String?): String {
        require(
            value != null &&
                value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        ) {
            "Invalid request ID"
        }
        return value
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        require(directory.listFiles().orEmpty().size < 64) { "Too many requests; wait for expiry" }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        val segments = uri.pathSegments
        require(segments.size == 2) { "Invalid control URI" }
        val id = id(segments[1])
        if (segments[0] == "responses" && mode == "r") {
            return ParcelFileDescriptor.open(
                File(directory, "$id.response"),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        require(segments[0] == "requests" && mode == "w") { "Unsupported control stream" }
        synchronized(ioLock) {
            cleanup()
            require(
                pending.size < 8 &&
                    !pending.containsKey(id) &&
                    !File(directory, "$id.request").exists()
            ) {
                "Request ID already exists or queue full"
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val output = File(directory, "$id.request")
            pending[id] =
                writers.submit {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                            output.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= 1048576) { "Request exceeds 1 MiB" }
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        output.delete()
                        throw e
                    }
                }
            return pipe[1]
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        require(method == "execute" && (extras == null || extras.isEmpty)) {
            "Unsupported provider call"
        }
        val id = id(arg)
        require(executions.add(id)) { "Request already executing" }
        val identity = Binder.clearCallingIdentity()
        try {
            if (File(directory, "$id.response").exists())
                return Bundle().apply { putString("result", id) }
            val result =
                try {
                    pending.remove(id)?.get(30, TimeUnit.SECONDS)
                    val file = File(directory, "$id.request")
                    requireControl(
                        file.exists() && file.length() <= 1048576,
                        "INVALID_REQUEST",
                        "Missing or oversized request",
                    )
                    val request =
                        try {
                            ControlJson.parseObject(file.readText())
                        } finally {
                            file.delete()
                        }
                    // Recheck opt-in immediately before dispatch. API commits also recheck it under
                    // the write lock.
                    requireControl(
                        DataStore.configurationStore.getBoolean("adbControlEnabled", false),
                        "DISABLED",
                        "ADB control was disabled",
                    )
                    ControlApi.execute(request)
                } catch (e: ControlError) {
                    ControlApi.error(e.code, e.message ?: e.code)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AdbControl",
                        "Request failure: ${e.javaClass.simpleName}; cause: ${e.cause?.javaClass?.simpleName}",
                    )
                    ControlApi.error("INVALID_REQUEST", "Could not read request")
                }
            File(directory, "$id.response").writeText(ControlJson.gson.toJson(result))
            return Bundle().apply { putString("result", id) }
        } finally {
            executions.remove(id)
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun getType(uri: Uri): String {
        authorize()
        return "application/json"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        authorize()
        throw UnsupportedOperationException("Use call/read/write")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        authorize()
        throw UnsupportedOperationException("Use call/read/write")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        authorize()
        throw UnsupportedOperationException("Use call/read/write")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        authorize()
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "responses") {
            "Only response cleanup is supported"
        }
        return if (File(directory, "${id(uri.lastPathSegment)}.response").delete()) 1 else 0
    }
}
