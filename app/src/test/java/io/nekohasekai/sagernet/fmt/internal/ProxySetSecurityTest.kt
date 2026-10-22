package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test

class ProxySetSecurityTest {
    private fun vless(
        security: String,
        allow: Boolean = false,
        encryption: String = "",
    ): ProxyEntity =
        ProxyEntity(type = ProxyEntity.TYPE_VMESS).apply {
            vmessBean =
                VMessBean().apply {
                    initializeDefaultValues()
                    alterId = -1
                    this.security = security
                    allowInsecure = allow
                    vlessEncryption = encryption
                }
        }

    @Test
    fun vlessEncryptionRealityAndCertificateVerification() {
        assertTrue(isInsecureProxySetMember(vless(""), false))
        assertFalse(isInsecureProxySetMember(vless("reality"), true))
        assertFalse(
            isInsecureProxySetMember(
                vless("", encryption = "mlkem768x25519plus.native.0rtt.test"),
                true,
            )
        )
        assertFalse(isInsecureProxySetMember(vless("tls"), false))
        assertTrue(isInsecureProxySetMember(vless("tls", true), false))
        assertTrue(isInsecureProxySetMember(vless("tls"), true))
    }

    @Test
    fun legacyProxySetDoesNotEnableFilterAndNewRoundtripPreservesIt() {
        val out = ByteBufferOutput(1024)
        out.writeInt(1)
        out.writeBoolean(false)
        out.writeString("https://example.org/")
        out.writeString("3m")
        out.writeString("3m")
        out.writeInt(50)
        out.writeInt(ProxySetBean.TYPE_LIST)
        out.writeInt(1)
        out.writeLong(42)
        val bean = ProxySetBean()
        bean.deserialize(ByteBufferInput(out.toBytes()))
        assertFalse(bean.skipInsecureProfiles)
        assertEquals(listOf(42L), bean.proxies)
        bean.initializeDefaultValues()
        bean.skipInsecureProfiles = true
        val copy = KryoConverters.deserialize(ProxySetBean(), KryoConverters.serialize(bean))
        assertTrue(copy.skipInsecureProfiles)
        assertEquals(listOf(42L), copy.proxies)
    }

    @Test
    fun plainProxyAndWireGuardPolicies() {
        assertTrue(isInsecureProxySetMember(ProxyEntity(type = ProxyEntity.TYPE_SOCKS), false))
        assertFalse(isInsecureProxySetMember(ProxyEntity(type = ProxyEntity.TYPE_WG), false))
    }
}
