package io.nekohasekai.sagernet.bg

import org.junit.Test
import org.junit.Assert.*

class VpnTunReusePolicyTest {
    @Test fun generatedNameAndFdDoNotForceInterfaceReplacement() {
        val first = VpnTunReusePolicy.signature("""{"Name":"tun0","FileDescriptor":9,"MTU":1500}""", "{}")
        val next = VpnTunReusePolicy.signature("""{"Name":"tun1","FileDescriptor":12,"MTU":1500}""", "{}")
        assertEquals(first, next)
        assertNotEquals(first, VpnTunReusePolicy.signature("""{"Name":"tun1","MTU":1400}""", "{}"))
    }

    @Test fun retainsOnlyMatchingLiveInterfaceDuringRecovery() {
        assertTrue(VpnTunReusePolicy.canReuse(true,"r1","r1","tun","tun",true))
        assertFalse(VpnTunReusePolicy.canReuse(false,"r1","r1","tun","tun",true))
        assertFalse(VpnTunReusePolicy.canReuse(true,"r1","r2","tun","tun",true))
        assertFalse(VpnTunReusePolicy.canReuse(true,"r1","r1","old","new",true))
        assertFalse(VpnTunReusePolicy.canReuse(true,null,null,"tun","tun",true))
        assertFalse(VpnTunReusePolicy.canReuse(true,"r1","r1","tun","tun",false))
    }
}
