package io.nekohasekai.sagernet.utils

internal class DelegatingExceptionHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val report: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            report(thread, error)
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}
