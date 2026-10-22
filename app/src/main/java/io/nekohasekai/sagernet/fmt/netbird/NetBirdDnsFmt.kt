package io.nekohasekai.sagernet.fmt.netbird

import moe.matsuri.nb4a.SingBoxOptions.*

/** Custom JSON can replace entire DNS/rule lists. Restore explicit overlay zones last. */
fun applyNetBirdDnsRouting(config: MutableMap<String, Any?>, outboundTag: String, domains: List<String>) {
    if (domains.isEmpty()) return
    require(outboundTag.isNotBlank()) { "NetBird DNS requires an active outbound." }
    @Suppress("UNCHECKED_CAST")
    fun section(name: String) = (config[name] as? Map<String, Any?>).orEmpty().toMutableMap()
    fun rows(value: Any?) = (value as? List<*>).orEmpty()
    val dns = section("dns")
    val server = mapOf("type" to "netbird", "tag" to "dns-netbird", "outbound" to outboundTag)
    dns["servers"] = rows(dns["servers"]).filterNot { (it as? Map<*, *>)?.get("tag") == "dns-netbird" } + server
    val dnsRule = mapOf("domain_suffix" to domains, "server" to "dns-netbird")
    dns["rules"] = listOf(dnsRule) + rows(dns["rules"]).filterNot { it == dnsRule }
    dns["reverse_mapping"] = true
    config["dns"] = dns
    val route = section("route")
    val routeRule = mapOf("domain_suffix" to domains, "outbound" to outboundTag)
    route["rules"] = listOf(routeRule) + rows(route["rules"]).filterNot { it == routeRule }
    config["route"] = route
}

/** Explicit zones take precedence over FakeDNS, direct DNS and private-LAN bypass. */
fun applyNetBirdDnsRouting(dns: DNSOptions, route: RouteOptions, outboundTag: String, domains: List<String>) {
    if (domains.isEmpty()) return
    require(outboundTag.isNotBlank()) { "NetBird DNS requires an active outbound." }
    dns.reverse_mapping = true
    dns.servers.add(DNSServerOptions().apply {
        tag = "dns-netbird"
        _hack_config_map["type"] = "netbird"
        _hack_config_map["outbound"] = outboundTag
    })
    dns.rules.add(0, DNSRule_DefaultOptions().apply {
        domain_suffix = domains
        server = "dns-netbird"
    })
    route.rules.add(0, Rule_DefaultOptions().apply {
        domain_suffix = domains
        outbound = outboundTag
    })
}
