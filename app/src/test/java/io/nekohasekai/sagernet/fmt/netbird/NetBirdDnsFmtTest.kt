package io.nekohasekai.sagernet.fmt.netbird

import moe.matsuri.nb4a.SingBoxOptions.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBirdDnsFmtTest {
    @Test fun explicitZonesPrecedeFakeDnsDirectDnsAndLanBypass() {
        val fakeDns = DNSRule_DefaultOptions().apply { server = "dns-fake" }
        val directDns = DNSRule_DefaultOptions().apply { server = "dns-direct" }
        val bypass = Rule_DefaultOptions().apply { outbound = "direct"; ip_is_private = true }
        val dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf(fakeDns, directDns)
        }
        val route = RouteOptions().apply { rules = mutableListOf(bypass) }
        applyNetBirdDnsRouting(dns, route, "office-netbird", listOf("office.example"))
        assertTrue(dns.reverse_mapping)
        assertEquals("netbird", dns.servers.single()._hack_config_map["type"])
        assertEquals("office-netbird", dns.servers.single()._hack_config_map["outbound"])
        val dnsRule = dns.rules.first() as DNSRule_DefaultOptions
        assertEquals("dns-netbird", dnsRule.server)
        assertEquals(listOf("office.example"), dnsRule.domain_suffix)
        assertSame(fakeDns, dns.rules[1])
        assertSame(directDns, dns.rules[2])
        val routeRule = route.rules.first() as Rule_DefaultOptions
        assertEquals("office-netbird", routeRule.outbound)
        assertEquals(listOf("office.example"), routeRule.domain_suffix)
        assertSame(bypass, route.rules[1])
    }

    @Test fun emptySuffixesDoNotAddCatchAllOrAlterDns() {
        val dns = DNSOptions().apply { servers = mutableListOf(); rules = mutableListOf() }
        val route = RouteOptions().apply { rules = mutableListOf() }
        applyNetBirdDnsRouting(dns, route, "office-netbird", emptyList())
        assertTrue(dns.servers.isEmpty())
        assertTrue(dns.rules.isEmpty())
        assertTrue(route.rules.isEmpty())
        assertNull(dns.reverse_mapping)
    }
}
