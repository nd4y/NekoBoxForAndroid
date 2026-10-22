package io.nekohasekai.sagernet.bg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.*
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class VpnIntentJournalDeviceTest {
    private lateinit var directory: File
    @Before fun prepare() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "vpn-intent-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun survivesReconstructionAndExplicitStopFencesOldStart() {
        val journal = VpnIntentJournal(directory)
        val first = journal.request(true)
        assertTrue(first > 0)
        assertEquals(first, VpnIntentJournal(directory).token())
        assertEquals(first, journal.request(true))
        journal.request(false)
        assertFalse(VpnRecoveryPolicy.accepts(first, journal.token()))
        val next = journal.request(true)
        assertTrue(next > first)
        assertFalse(VpnRecoveryPolicy.accepts(first, next))
    }
    @Test fun interruptedAtomicWritePreservesExplicitStop() {
        File(directory,"vpn-intent.bak").writeText("-17")
        assertEquals(-17L, VpnIntentJournal(directory).token())
    }
    @Test fun corruptedIntentFailsClosedUntilExplicitConnect() {
        File(directory,"vpn-intent").writeText("invalid")
        val journal = VpnIntentJournal(directory)
        assertTrue(journal.token() < 0)
        assertTrue(journal.request(true) > 0)
    }
    @Test fun concurrentInstancesSerializeGenerations() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map { pool.submit { repeat(20) { VpnIntentJournal(directory).request(false) } } }
            jobs.forEach { it.get() }
            assertEquals(-1L, VpnIntentJournal(directory).token())
            assertEquals(2L, VpnIntentJournal(directory).request(true))
        } finally { pool.shutdownNow() }
    }
}
