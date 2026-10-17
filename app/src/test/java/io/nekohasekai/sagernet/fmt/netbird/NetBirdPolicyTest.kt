package io.nekohasekai.sagernet.fmt.netbird

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetBirdPolicyTest {
    @Test fun ordinaryChainsRemainValid() {
        validateNetBirdChain(listOf("vless", "socks"))
        validateNetBirdChain(emptyList())
    }

    @Test fun netbirdRequiresInnerPositionAndVlessUnderlay() {
        validateNetBirdChain(listOf("netbird", "vless"))
        validateNetBirdChain(listOf("netbird", "vless", "vless"))
        for (chain in listOf(
            listOf("netbird"), listOf("vless", "netbird"),
            listOf("netbird", "socks"), listOf("netbird", "vless", "netbird"),
            listOf("netbird", "vless", "proxy-set"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateNetBirdChain(chain) }
        }
    }

    @Test fun managementServerRejectsCredentialBearingAndPlaintextUrls() {
        validateNetBirdManagementUrl("https://api.netbird.io:443")
        validateNetBirdManagementUrl("https://netbird.example.org/")
        for (url in listOf("http://example.org", "https://user:secret@example.org", "https://example.org/path",
            "https://example.org?key=secret", "https://example.org#fragment", "https://example.org:99999", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { validateNetBirdManagementUrl(url) }
        }
    }

    @Test fun dnsOnlyAcceptsExplicitSuffixesAndNormalizesDuplicates() {
        assertEquals(emptyList<String>(), parseNetBirdDnsDomains(" "))
        assertEquals(listOf("office.example", "example.netbird.cloud"),
            parseNetBirdDnsDomains(" Office.Example.,office.example,\nexample.netbird.cloud"))
        for (value in listOf("*", ".", "*.*", "cloud", "https://example.org", "100.64.0.1", "a..example")) {
            assertThrows(value, IllegalArgumentException::class.java) { parseNetBirdDnsDomains(value) }
        }
    }

    @Test fun customJsonCannotReplaceTheVlessUnderlayOrAddSecondIdentity() {
        fun config(vararg outbounds: Map<String, String>): Map<String, Any?> = mapOf("outbounds" to outbounds.toList())
        val bird = mapOf("type" to "netbird", "tag" to "overlay", "detour" to "underlay")
        val vless = mapOf("type" to "vless", "tag" to "underlay")
        validateNetBirdConfig(config(bird, vless), expectedCount = 1)
        assertThrows(IllegalArgumentException::class.java) {
            validateNetBirdConfig(config(vless), expectedCount = 1)
        }
        for (invalid in listOf(
            config(bird), config(bird, vless + ("type" to "direct")),
            config(bird, vless, bird + ("tag" to "overlay2")), config(bird, vless, vless),
            config(bird - "detour", vless),
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateNetBirdConfig(invalid) }
        }
    }

    @Test fun customJsonCannotMoveGeneratedIdentityIntoBackedUpOrSharedStorage() {
        val directory = "/data/user/0/test/no_backup/netbird/42"
        val bird = mapOf("type" to "netbird", "tag" to "overlay", "detour" to "underlay",
            "state_directory" to directory)
        val vless = mapOf("type" to "vless", "tag" to "underlay")
        fun config(netbird: Map<String, String>) = mapOf("outbounds" to listOf(netbird, vless))
        validateNetBirdConfig(config(bird), expectedStateDirectory = directory)
        for (changed in listOf(bird - "state_directory", bird + ("state_directory" to "/sdcard/netbird"))) {
            assertThrows(IllegalArgumentException::class.java) {
                validateNetBirdConfig(config(changed), expectedStateDirectory = directory)
            }
        }
        // An explicitly authored full JSON profile keeps its native path configuration.
        validateNetBirdConfig(config(bird + ("state_directory" to "/other/private/path")))
    }

    @Test fun temporaryTestsCannotOpenThePersistentIdentityIncludingRawConfigs() {
        assertThrows(IllegalArgumentException::class.java) {
            validateNetBirdChain(listOf("netbird", "vless"), forTest = true)
        }
        validateNetBirdChain(listOf("vless"), forTest = true)
        val config = mapOf("outbounds" to listOf(
            mapOf("type" to "netbird", "tag" to "overlay", "detour" to "underlay"),
            mapOf("type" to "vless", "tag" to "underlay"),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            validateNetBirdConfig(config, forTest = true)
        }
        validateNetBirdConfig(config, forTest = false)
    }

    @Test fun rawConfigExportRemovesEnrollmentAndHostStorageInformation() {
        val netbird = mutableMapOf<String, Any?>("type" to "netbird", "setup_key" to "secret",
            "state_directory" to "/data/user/0/app/no_backup/netbird/42", "hostname" to "test-phone")
        val config = mapOf("outbounds" to listOf(netbird))
        sanitizeNetBirdConfigForExport(config)
        assertEquals("", netbird["setup_key"])
        assertEquals("/NETBIRD_STATE_DIRECTORY", netbird["state_directory"])
        assertEquals("test-phone", netbird["hostname"])
    }

}
