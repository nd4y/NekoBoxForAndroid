package io.nekohasekai.sagernet.utils

internal object NetworkCallbackSafety {
    fun <T> deliver(listeners: Collection<(T) -> Unit>, value: T, failure: (Exception) -> Unit) {
        listeners.toList().forEach { listener ->
            try {
                listener(value)
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun mtu(fallback: Int, read: () -> Int): Int {
        val safeFallback = fallback.takeIf { it > 0 } ?: 1500
        return try {
            read().takeIf { it > 0 } ?: safeFallback
        } catch (_: java.net.SocketException) {
            safeFallback
        }
    }
}
