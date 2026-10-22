package io.nekohasekai.sagernet.control

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.*
import org.junit.Test

class ControlFullConfigTest {
    private fun snapshot() =
        ControlSnapshot(
            groups =
                mutableListOf(
                    ProxyGroup(id = 1, ungrouped = true, name = "default"),
                    ProxyGroup(
                        id = 2,
                        type = 1,
                        name = "private",
                        subscription =
                            SubscriptionBean().apply {
                                initializeDefaultValues()
                                link = "https://example.org/private-token"
                                token = "keep-token"
                            },
                    ),
                ),
            profiles =
                mutableListOf(
                    ProxyEntity(id = 10, groupId = 1, userOrder = 4, uuid = "entity-id").apply {
                        putBean(ControlProfiles.create("socks").apply { name = "original" })
                        socksBean!!.password = "keep-password"
                    }
                ),
            rules = mutableListOf(RuleEntity(id = 3, name = "test", outbound = 10)),
            preferences =
                mutableListOf(
                    KeyValuePair(Key.PROFILE_ID).put(10L),
                    KeyValuePair("unknownFutureSetting").put("keep-unknown"),
                    KeyValuePair("adbControlEnabled").put(true),
                    KeyValuePair(Key.PROFILE_CURRENT).put(42L),
                ),
        )

    private fun document(s: ControlSnapshot) =
        ControlJson.objectOf(
            "schema" to ControlFullConfig.SCHEMA,
            "schemaVersion" to 1,
            "revision" to "local-revision",
            "config" to ControlFullConfig.export(s),
        )

    private fun rejects(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected " + code)
        } catch (e: ControlError) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun fullRoundtripRetainsCredentialsUnknownSettingsAndLocalAuthorization() {
        val s = snapshot()
        val d = document(s)
        val n = ControlFullConfig.parse(s, d)
        assertEquals(s.revisionData(), n.revisionData())
        assertEquals("keep-password", n.profiles.single().socksBean!!.password)
        assertEquals("keep-token", n.groups[1].subscription!!.token)
        assertEquals(true, n.preferences.first { it.key == "adbControlEnabled" }.boolean)
        assertFalse(d["config"].asJsonObject["preferences"].asJsonObject.has("adbControlEnabled"))
        assertFalse(d["config"].asJsonObject["preferences"].asJsonObject.has(Key.PROFILE_CURRENT))
    }

    @Test
    fun fullEditAndExplicitEntityRemovalAreIsolatedFromOriginal() {
        val s = snapshot()
        val d = document(s)
        val config = d["config"].asJsonObject
        config["profiles"]
            .asJsonArray[0]
            .asJsonObject["values"]
            .asJsonObject
            .addProperty("name", "edited")
        config["rules"].asJsonArray.remove(0)
        val n = ControlFullConfig.parse(s, d)
        assertEquals("edited", n.profiles.single().requireBean().name)
        assertEquals("original", s.profiles.single().requireBean().name)
        assertEquals("keep-password", n.profiles.single().socksBean!!.password)
        assertTrue(n.rules.isEmpty())
        assertEquals(1, s.rules.size)
    }

    @Test
    fun fullImportRejectsRedactionIncompleteSectionsAndUnknownFields() {
        val s = snapshot()
        var d = document(s)
        d["config"]
            .asJsonObject["profiles"]
            .asJsonArray[0]
            .asJsonObject["values"]
            .asJsonObject
            .addProperty("password", "<redacted>")
        rejects("REDACTED_INPUT") { ControlFullConfig.parse(s, d) }
        d = document(s)
        d["config"].asJsonObject.remove("rules")
        rejects("INVALID_CONFIG") { ControlFullConfig.parse(s, d) }
        d = document(s)
        d["config"].asJsonObject["groups"].asJsonArray[0].asJsonObject.addProperty("typo", true)
        rejects("UNKNOWN_FIELD") { ControlFullConfig.parse(s, d) }
        d = document(s)
        d["config"]
            .asJsonObject["profiles"]
            .asJsonArray[0]
            .asJsonObject["values"]
            .asJsonObject
            .remove("password")
        rejects("INCOMPLETE_CONFIG") { ControlFullConfig.parse(s, d) }
    }

    @Test
    fun duplicatesOverflowLocalAuthorizationAndKnownPreferenceRangesAreRejected() {
        val s = snapshot()
        var d = document(s)
        var config = d["config"].asJsonObject
        config["profiles"].asJsonArray.add(config["profiles"].asJsonArray[0].deepCopy())
        rejects("DUPLICATE_ID") { ControlFullConfig.parse(s, d) }
        d = document(s)
        d["config"]
            .asJsonObject["profiles"]
            .asJsonArray[0]
            .asJsonObject
            .add("id", ControlJson.parseObject("{\"n\":9223372036854775808}")["n"])
        rejects("INVALID_FIELD") { ControlFullConfig.parse(s, d) }
        d = document(s)
        config = d["config"].asJsonObject
        config["preferences"]
            .asJsonObject
            .add("adbControlEnabled", ControlJson.objectOf("type" to "boolean", "value" to false))
        rejects("LOCAL_STATE") { ControlFullConfig.parse(s, d) }
        d = document(s)
        d["config"]
            .asJsonObject["preferences"]
            .asJsonObject
            .add(Key.MTU, ControlJson.objectOf("type" to "string", "value" to "1"))
        rejects("INVALID_FIELD") { ControlFullConfig.parse(s, d) }
    }

    @Test
    fun typedPreferencesRetainUtf8SetsAndNumericTypes() {
        val s = snapshot()
        val d = document(s)
        val p = d["config"].asJsonObject["preferences"].asJsonObject
        p.add(
            "utf8Set",
            ControlJson.objectOf("type" to "string-set", "value" to listOf("Привет", "日本語")),
        )
        p.add("longValue", ControlJson.objectOf("type" to "long", "value" to Long.MAX_VALUE))
        p.add("floatValue", ControlJson.objectOf("type" to "float", "value" to 1.25))
        p.add(
            "rawFuture",
            ControlJson.objectOf("type" to "raw", "valueType" to 99, "value" to "AQID"),
        )
        val n = ControlFullConfig.parse(s, d)
        assertArrayEquals(byteArrayOf(1, 2, 3), n.preferences.first { it.key == "rawFuture" }.value)
        assertEquals(setOf("Привет", "日本語"), n.preferences.first { it.key == "utf8Set" }.stringSet)
        assertEquals(Long.MAX_VALUE, n.preferences.first { it.key == "longValue" }.long)
        assertEquals(1.25f, n.preferences.first { it.key == "floatValue" }.float!!, 0f)
        assertEquals(n.revisionData(), ControlFullConfig.parse(n, document(n)).revisionData())
    }

    @Test
    fun masterDnsFloatingPointOptionsRoundtripAndInvalidFloatsAreRejected() {
        val s = snapshot()
        s.profiles.add(
            ProxyEntity(id = 20, groupId = 1).apply {
                putBean(ControlProfiles.create("masterdnsvpn"))
            }
        )
        val d = document(s)
        val n = ControlFullConfig.parse(s, d)
        assertEquals(s.revisionData(), n.revisionData())
        ControlTypes.check(ControlJson.gson.toJsonTree(0.125), java.lang.Double::class.java)
        rejects("INVALID_FIELD") {
            ControlTypes.check(ControlJson.gson.toJsonTree("NaN"), java.lang.Double::class.java)
        }
    }
    @Test fun typedPreferenceRedactionKeepsOpaqueAndUnknownValuesPrivate() {
        val s = snapshot()
        s.preferences.add(KeyValuePair(Key.GLOBAL_CUSTOM_CONFIG).put("""{"auth":"opaque-test-password"}"""))
        s.preferences.add(KeyValuePair("futurePrivateSetting").put("future-test-token"))
        s.preferences.add(KeyValuePair(Key.MTU).put("1400"))
        val full = ControlFullConfig.export(s)
        val redacted = ControlFullConfig.redact(full)
        val text = redacted.toString()
        assertFalse(text.contains("opaque-test-password"))
        assertFalse(text.contains("future-test-token"))
        assertFalse(text.contains("keep-password"))
        assertFalse(text.contains("keep-token"))
        assertEquals("1400", redacted["preferences"].asJsonObject[Key.MTU].asJsonObject["value"].asString)
        assertTrue(full.toString().contains("opaque-test-password"))
    }

}
