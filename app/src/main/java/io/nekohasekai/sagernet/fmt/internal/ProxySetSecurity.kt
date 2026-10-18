package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS

/** Preserve the upstream proxy-set exclusion policy when importing newer installations. */
fun isInsecureProxySetMember(profile: ProxyEntity, globalAllowInsecure: Boolean): Boolean {
    fun StandardV2RayBean.insecureTls() =
        security == "tls" && (allowInsecure == true || globalAllowInsecure)
    return when (profile.type) {
        ProxyEntity.TYPE_SOCKS,
        ProxyEntity.TYPE_SSR -> true
        ProxyEntity.TYPE_HTTP -> profile.httpBean?.let { !it.isTLS() || it.insecureTls() } ?: false
        ProxyEntity.TYPE_SS ->
            profile.ssBean?.method?.lowercase(java.util.Locale.ROOT) in
                setOf(
                    "none",
                    "aes-128-ctr",
                    "aes-192-ctr",
                    "aes-256-ctr",
                    "aes-128-cfb",
                    "aes-192-cfb",
                    "aes-256-cfb",
                    "rc4-md5",
                    "chacha20-ietf",
                    "xchacha20",
                )
        ProxyEntity.TYPE_VMESS ->
            profile.vmessBean?.let {
                if (!it.isVLESS) it.insecureTls()
                else if (
                    !it.vlessEncryption.isNullOrBlank() && !it.vlessEncryption.equals("none", true)
                )
                    false
                else
                    when (it.security) {
                        "reality" -> false
                        "tls" -> it.allowInsecure == true || globalAllowInsecure
                        else -> true
                    }
            } ?: false
        ProxyEntity.TYPE_TROJAN -> profile.trojanBean?.insecureTls() ?: false
        ProxyEntity.TYPE_SHADOWTLS -> profile.shadowTLSBean?.insecureTls() ?: false
        ProxyEntity.TYPE_TROJAN_GO ->
            profile.trojanGoBean?.let { it.allowInsecure == true || globalAllowInsecure } ?: false
        ProxyEntity.TYPE_HYSTERIA ->
            profile.hysteriaBean?.let { it.allowInsecure == true || globalAllowInsecure } ?: false
        ProxyEntity.TYPE_TUIC ->
            profile.tuicBean?.let { it.allowInsecure == true || globalAllowInsecure } ?: false
        ProxyEntity.TYPE_ANYTLS ->
            profile.anyTLSBean?.let {
                it.realityPubKey.isNullOrBlank() &&
                    (it.allowInsecure == true || globalAllowInsecure)
            } ?: false
        ProxyEntity.TYPE_JUICITY ->
            profile.juicityBean?.let {
                it.pinnedCertchainSha256.isNullOrBlank() &&
                    (it.allowInsecure == true || globalAllowInsecure)
            } ?: false
        ProxyEntity.TYPE_SSH -> profile.sshBean?.publicKey.isNullOrBlank()
        ProxyEntity.TYPE_MASTERDNSVPN -> profile.masterDnsVPNBean?.dataEncryptionMethod == 0
        ProxyEntity.TYPE_TRUST_TUNNEL ->
            profile.trustTunnelBean?.let {
                !(it.useCronetHttps == true ||
                    it.utlsFingerprint == "cronet" ||
                    (it.quic == true && it.useCronetQuic == true)) &&
                    it.certPublicKeySha256.isNullOrBlank() &&
                    (it.allowInsecure == true || globalAllowInsecure)
            } ?: false
        else -> false
    }
}
