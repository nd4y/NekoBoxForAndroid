package io.nekohasekai.sagernet.fmt.netbird

import org.junit.Assert.assertThrows
import org.junit.Test

class NetBirdDirectPolicyTest {
    @Test fun directMustBeExplicitAndCannotSilentlyRetainADetour() {
        val bird = mapOf("type" to "netbird", "tag" to "mesh", "connection_mode" to "direct")
        fun config(node: Map<String, String>) = mapOf("outbounds" to listOf(node))
        validateNetBirdConfig(config(bird))
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdConfig(config(bird + ("detour" to "proxy"))) }
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdConfig(config(bird - "connection_mode")) }
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdConfig(config(bird + ("connection_mode" to "auto"))) }
    }
    @Test fun standaloneDirectAllowedButEmergencyStillRequiresVless() {
        validateNetBirdChain(listOf("netbird"), direct = true)
        validateNetBirdChain(listOf("netbird", "vless"), direct = true)
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdChain(listOf("netbird")) }
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdChain(listOf("netbird", "socks"), direct = true) }
        assertThrows(IllegalArgumentException::class.java) { validateNetBirdChain(listOf("netbird"), forTest = true, direct = true) }
    }
}
