package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class VpnRecoveryPolicyTest {
    @Test fun stopInvalidatesQueuedStartEvenIfUserConnectsAgain() {
        assertTrue(VpnRecoveryPolicy.accepts(1, 1))
        assertFalse(VpnRecoveryPolicy.accepts(1, -2))
        assertFalse(VpnRecoveryPolicy.accepts(1, 3))
        assertTrue(VpnRecoveryPolicy.accepts(3, 3))
    }
    @Test fun stickyRestartRequiresPersistedOn() {
        assertTrue(VpnRecoveryPolicy.accepts(0, 5))
        assertFalse(VpnRecoveryPolicy.accepts(0, -6))
        assertFalse(VpnRecoveryPolicy.accepts(0, 0))
    }
    @Test fun backoffGrowsThenRemainsBoundedWithoutRetryLimit() {
        val expected = listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L)
        expected.forEachIndexed { n, delay -> assertEquals(delay, VpnRecoveryPolicy.delayMillis(n, 0)) }
        assertEquals(60999L, VpnRecoveryPolicy.delayMillis(Int.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(2000L, VpnRecoveryPolicy.delayMillis(-1, -1))
    }
}
