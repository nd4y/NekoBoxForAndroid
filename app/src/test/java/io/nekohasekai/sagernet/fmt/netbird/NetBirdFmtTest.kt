package io.nekohasekai.sagernet.fmt.netbird

import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.toUniversalLink
import com.google.gson.JsonParser
import java.util.Base64
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NetBirdFmtTest {
    private fun bean() = NetBirdBean().apply {
        initializeDefaultValues()
        setupKey = "test-secret"
        managementUrl = "https://netbird.example.org/"
        dnsDomains = "office.example.org"
    }

    @Test fun enrollmentSettingsRoundTripWithoutEmbeddingPeerIdentity() {
        val original = bean()
        val restored = KryoConverters.deserialize(NetBirdBean(), KryoConverters.serialize(original))
        assertEquals(original.setupKey, restored.setupKey)
        assertEquals(original.managementUrl, restored.managementUrl)
        assertEquals(original.dnsDomains, restored.dnsDomains)
        assertFalse(restored.toString().contains("test-secret"))
    }

    @Test fun rawConfigUniversalLinksOmitEnrollmentKeyAndIdentityPath() {
        val outbound = """{"type":"netbird","tag":"overlay","setup_key":"test-secret","state_directory":"/private/netbird/42"}"""
        for (configType in listOf(0, 1)) {
            val original = ConfigBean().apply {
                initializeDefaultValues()
                type = configType
                config = if (configType == 0) """{"outbounds":[$outbound]}""" else outbound
            }
            val link = original.toUniversalLink()
            val restored = KryoConverters.deserialize(ConfigBean(), Util.zlibDecompress(
                Base64.getUrlDecoder().decode(link.substringAfter('?'))
            ))
            val json = JsonParser.parseString(restored.config).asJsonObject
            val netbird = if (configType == 0) json.getAsJsonArray("outbounds")[0].asJsonObject else json
            assertEquals("", netbird["setup_key"].asString)
            assertEquals("/NETBIRD_STATE_DIRECTORY", netbird["state_directory"].asString)
            assertFalse(restored.config.contains("test-secret"))
            assertFalse(restored.config.contains("/private/netbird/42"))
            assertEquals(configType, restored.type.toInt())
            assertEquals(if (configType == 0) """{"outbounds":[$outbound]}""" else outbound, original.config)
        }
    }

    @Test fun configExportOmitsSetupKeyAndDeviceSpecificStoragePath() {
        val original = bean()
        val outbound = buildSingBoxOutboundNetBirdBean(original, 42, forExport = true)
        assertEquals("netbird", outbound.type)
        assertEquals("https://netbird.example.org", outbound.management_url)
        assertEquals("", outbound.setup_key)
        assertEquals("/NETBIRD_STATE_DIRECTORY", outbound.state_directory)
        assertEquals("test-secret", original.setupKey)
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundNetBirdBean(original, 0, forExport = true)
        }
    }
}
