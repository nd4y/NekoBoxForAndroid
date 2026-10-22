package io.nekohasekai.sagernet.control

import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * All persistent configuration DAO calls share this process + OS lock. Never hold it across network
 * I/O.
 */
object ControlAccess {
    private val mutex = ReentrantLock(true)
    val candidate = ThreadLocal<ControlSnapshot?>()
    val directory: File
        get() = File(SagerNet.application.noBackupFilesDir, "adb-control").apply { mkdirs() }

    fun <T> exclusive(block: () -> T): T {
        if (mutex.isHeldByCurrentThread) return block()
        return mutex.withLock {
            RandomAccessFile(File(directory, "configuration.lock"), "rw").use { file ->
                file.channel.lock().use {
                    ControlStore.recoverLocked()
                    block()
                }
            }
        }
    }

    fun <T> preview(snapshot: ControlSnapshot, block: () -> T): T {
        check(candidate.get() == null)
        candidate.set(snapshot)
        try {
            return block()
        } finally {
            candidate.remove()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> wrap(type: Class<T>, target: T, table: String): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            val snapshot = candidate.get()
            if (method.declaringClass == Any::class.java)
                method.invoke(target, *(args ?: emptyArray()))
            else if (snapshot != null) snapshot.invoke(table, method.name, args ?: emptyArray())
            else
                exclusive {
                    try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
        } as T
}
