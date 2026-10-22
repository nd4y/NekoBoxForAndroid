package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreIdlePolicyTest {
    @Test
    fun exemptVpnStaysAwakeDuringDozeWithoutAnEntryReconnect() {
        assertEquals(CoreIdlePolicy.Action.Wake, CoreIdlePolicy.action(true, true, true))
        assertEquals(CoreIdlePolicy.Action.WakeAndRecover, CoreIdlePolicy.action(false, true, true))
    }

    @Test
    fun nonExemptVpnRetainsSleepAndWakeRecovery() {
        assertEquals(CoreIdlePolicy.Action.Sleep, CoreIdlePolicy.action(true, false, true))
        assertEquals(CoreIdlePolicy.Action.WakeAndRecover, CoreIdlePolicy.action(false, false, true))
    }

    @Test
    fun exemptionChangesWhileIdleResumeOrSuspendTheCore() {
        assertEquals(CoreIdlePolicy.Action.Sleep, CoreIdlePolicy.action(true, false, false))
        assertEquals(CoreIdlePolicy.Action.Wake, CoreIdlePolicy.action(true, true, false))
        assertEquals(CoreIdlePolicy.Action.Sleep, CoreIdlePolicy.action(true, false, false))
    }

    @Test
    fun whitelistUpdatesOutsideDozeDoNotReconnectActiveTraffic() {
        assertEquals(CoreIdlePolicy.Action.Wake, CoreIdlePolicy.action(false, true, false))
        assertEquals(CoreIdlePolicy.Action.Wake, CoreIdlePolicy.action(false, false, false))
    }
}
