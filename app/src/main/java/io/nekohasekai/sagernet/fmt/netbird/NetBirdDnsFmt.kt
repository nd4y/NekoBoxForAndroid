package io.nekohasekai.sagernet.fmt.netbird

import moe.matsuri.nb4a.SingBoxOptions.*

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
