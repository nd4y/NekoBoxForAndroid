package io.nekohasekai.sagernet.bg

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectingJobTest {
    @Test
    fun releasesBlockingNativeStartBeforeWaitingForConnectingJob() = runBlocking {
        val enteredNativeStart = CountDownLatch(1)
        val nativeCancellation = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            enteredNativeStart.countDown()
            check(nativeCancellation.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(enteredNativeStart.await(5, TimeUnit.SECONDS))
            withTimeout(1000) {
                cancelConnectingJob(job) {
                    assertTrue(job.isCancelled)
                    nativeCancellation.countDown()
                }
            }
            assertTrue(job.isCompleted)
        } finally {
            nativeCancellation.countDown()
            job.cancelAndJoin()
        }
    }
}
