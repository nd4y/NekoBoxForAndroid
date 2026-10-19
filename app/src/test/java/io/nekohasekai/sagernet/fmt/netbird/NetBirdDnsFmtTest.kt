package io.nekohasekai.sagernet.fmt.netbird

import moe.matsuri.nb4a.SingBoxOptions.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBirdDnsFmtTest {
    @Test fun replacedCustomDnsAndRoutingStillUseOverlayForExplicitZones() {
        val bootstrap = mapOf("outbound" to listOf("any"), "server" to "bootstrap")
        val bypass = mapOf("ip_is_private" to true, "outbound" to "direct")
        val config = mutableMapOf<String, Any?>(
            "dns" to mapOf("servers" to listOf(mapOf("tag" to "bootstrap")), "rules" to listOf(bootstrap), "reverse_mapping" to false),
            "route" to mapOf("rules" to listOf(bypass)),
        )
        applyNetBirdDnsRouting(config, "office-netbird", listOf("office.example"))
        val once = config.toString()
        applyNetBirdDnsRouting(config, "office-netbird", listOf("office.example"))
        assertEquals(once, config.toString())
        val dns = config["dns"] as Map<*, *>
        assertEquals(true, dns["reverse_mapping"])
        val dnsRules = dns["rules"] as List<*>
        assertEquals("dns-netbird", (dnsRules.first() as Map<*, *>)["server"])
        assertEquals(bootstrap, dnsRules[1])
        val routeRules = (config["route"] as Map<*, *>)["rules"] as List<*>
        assertEquals("office-netbird", (routeRules.first() as Map<*, *>)["outbound"])
        assertEquals(bypass, routeRules[1])
    }

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
