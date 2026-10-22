package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class DelegatingExceptionHandlerTest {
    @Test fun reportsThenDelegatesOriginalFailure() {
        val calls = mutableListOf<String>()
        val thread = Thread.currentThread()
        val failure = IllegalStateException("synthetic")
        val handler = DelegatingExceptionHandler(Thread.UncaughtExceptionHandler { t, e ->
            assertSame(thread, t)
            assertSame(failure, e)
            calls += "platform"
        }) { t, e ->
            assertSame(thread, t)
            assertSame(failure, e)
            calls += "report"
        }
        handler.uncaughtException(thread, failure)
        assertEquals(listOf("report", "platform"), calls)
    }

    @Test fun reporterFailureStillDelegatesOriginalException() {
        val original = IllegalArgumentException("original")
        val reportingFailure = IllegalStateException("reporting")
        var delegated: Throwable? = null
        val handler = DelegatingExceptionHandler(Thread.UncaughtExceptionHandler { _, e ->
            delegated = e
        }) { _, _ -> throw reportingFailure }
        try {
            handler.uncaughtException(Thread.currentThread(), original)
            fail("reporter should throw")
        } catch (e: IllegalStateException) {
            assertSame(reportingFailure, e)
        }
        assertSame(original, delegated)
    }
}
