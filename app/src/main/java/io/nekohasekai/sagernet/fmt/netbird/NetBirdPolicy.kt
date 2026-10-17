package io.nekohasekai.sagernet.fmt.netbird

import java.net.URI
import java.net.IDN

/** Input is ordered from the application/overlay towards the physical network. */
fun validateNetBirdChain(protocols: List<String>, forTest: Boolean = false) {
    val indexes = protocols.indices.filter { protocols[it] == "netbird" }
    if (indexes.isEmpty()) return
    require(!forTest) {
        "NetBird cannot run in background profile tests. Test VLESS separately, then connect the NetBird chain."
    }
    require(indexes.size == 1 && indexes.single() == 0 && protocols.getOrNull(1) == "vless") {
        "NetBird must be the final destination in a chain with a VLESS proxy before it (VLESS, NetBird in the chain editor)."
    }
    require("proxy-set" !in protocols) { "NetBird cannot be combined with a proxy set." }
}

fun validateNetBirdManagementUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/") &&
        (uri.port == -1 || uri.port in 1..65535)) {
        "NetBird management URL must be an HTTPS server address without credentials, query or path."
    }
}

/** Explicit suffixes only: never infer a network-wide DNS rule or accept a catch-all. */
fun parseNetBirdDnsDomains(value: String): List<String> =
    value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.removeSuffix(".") }.map { name ->
        val ascii = runCatching { IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull()
        require(ascii != null && ascii.length <= 253 && ascii.contains('.') &&
            ascii.split('.').all { it.isNotEmpty() && it.length <= 63 } &&
            !ascii.matches(Regex("[0-9.]+"))) {
            "Enter explicit NetBird DNS suffixes, for example example.netbird.cloud."
        }
        ascii
    }.distinct()

/** Revalidate after custom JSON merges so they cannot silently replace VLESS with direct. */
fun validateNetBirdConfig(
    config: Map<String, Any?>,
    expectedCount: Int? = null,
    forTest: Boolean = false,
    expectedStateDirectory: String? = null,
) {
    val outbounds = (config["outbounds"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    val netbirds = outbounds.filter { it["type"] == "netbird" }
    require(!forTest || netbirds.isEmpty()) {
        "NetBird cannot run in background profile tests. Test VLESS separately, then connect the NetBird chain."
    }
    require(expectedCount == null || netbirds.size == expectedCount) {
        "Custom JSON cannot remove or replace a NetBird outbound."
    }
    require(netbirds.size <= 1) { "Only one NetBird outbound may run at a time." }
    for (netbird in netbirds) {
        require(expectedStateDirectory == null || netbird["state_directory"] == expectedStateDirectory) {
            "Custom JSON cannot override the NetBird profile identity directory."
        }
        val detour = netbird["detour"] as? String
        val underlays = outbounds.filter { it["tag"] == detour }
        require(!detour.isNullOrBlank() && underlays.size == 1 && underlays.single()["type"] == "vless") {
            "NetBird requires a VLESS detour; direct fallback is not allowed."
        }
        require(netbird["tag"] != detour) { "NetBird detour cannot point to itself." }
    }
}

/** A shared config is a template, not a copy of enrollment credentials or peer state. */
fun sanitizeNetBirdConfigForExport(config: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    (config["outbounds"] as? List<MutableMap<String, Any?>>)?.forEach { outbound ->
        if (outbound["type"] == "netbird") {
            outbound["setup_key"] = ""
            outbound["state_directory"] = "/NETBIRD_STATE_DIRECTORY"
        }
    }
}
