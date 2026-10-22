package io.nekohasekai.sagernet.bg

/** Pure scheduling policy: bounded rate, no finite retry limit. */
object VpnRecoveryPolicy {
    fun delayMillis(attempt: Int, jitterMillis: Long): Long =
        minOf(60_000L, 2_000L shl attempt.coerceIn(0, 5)) + jitterMillis.coerceIn(0, 999)

    fun failureCategory(message: String): String {
        val lower = message.lowercase()
        return when {
            "lookup" in lower || "resolv" in lower || "dns" in lower -> "dns"
            "tls" in lower || "certificate" in lower -> "tls"
            "timeout" in lower || "deadline" in lower -> "timeout"
            "injected startup" in lower -> "test-injection"
            else -> "startup"
        }
    }

    fun accepts(requested: Long, current: Long): Boolean =
        current > 0 && (requested == 0L || requested == current)
}
