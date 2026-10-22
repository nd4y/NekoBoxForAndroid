package io.nekohasekai.sagernet.bg

import android.util.AtomicFile
import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.RandomAccessFile

/** Device-local runtime intent, deliberately outside portable config/revisions.
 * One signed generation is written atomically under a cross-process file lock.
 * Positive = requested on, negative = explicitly off, zero = never requested.
 */
object VpnIntentStore {
    const val EXTRA_GENERATION = "vpn.intent.generation"
    private val journal by lazy { VpnIntentJournal(SagerNet.application.noBackupFilesDir) }
    fun token(): Long = journal.token()
    fun matches(expected: Long): Boolean = expected > 0 && token() == expected
    fun request(enabled: Boolean): Long = journal.request(enabled)
}

class VpnIntentJournal(private val directory: File) {
    companion object { private val mutex = Any() }
    private fun <T> locked(block: (AtomicFile) -> T): T = synchronized(mutex) {
        val dir = directory
        RandomAccessFile(File(dir, "vpn-intent.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { block(AtomicFile(File(dir, "vpn-intent"))) }
        }
    }
    private fun read(file: AtomicFile): Long =
        try { file.openRead().bufferedReader().use { it.readText().trim().toLongOrNull() ?: -1L } }
        catch (_: java.io.FileNotFoundException) { 0L }

    fun token(): Long = locked { read(it) }
    fun matches(expected: Long): Boolean = expected > 0 && token() == expected
    fun request(enabled: Boolean): Long = locked { file ->
        val old = read(file)
        if ((enabled && old > 0) || (!enabled && old < 0)) return@locked old // Idempotent intent.
        val next = kotlin.math.abs(old) + 1
        val value = if (enabled) next else -next
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (error: Throwable) { file.failWrite(out); throw error }
        value
    }
}
