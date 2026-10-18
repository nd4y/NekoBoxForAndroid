package io.nekohasekai.sagernet.control

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.*
import org.junit.Test

class ControlPatchTest {
    private fun snapshot(): ControlSnapshot {
        val profile =
            ProxyEntity(id = 10, groupId = 1, userOrder = 4).apply {
                putBean(ControlProfiles.create("socks").apply { name = "original" })
            }
        profile.socksBean!!.password = "keep-secret"
        return ControlSnapshot(
            mutableListOf(ProxyGroup(id = 1, name = "test")),
            mutableListOf(profile),
            preferences = mutableListOf(KeyValuePair(Key.PROFILE_ID).put(10L)),
        )
    }

    private fun patch(s: ControlSnapshot, json: String) =
        ControlPatch.apply(s, ControlJson.parseObject(json))

    private fun rejects(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: ControlError) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun partialUpdatePreservesCredentialsIdsOrderAndOriginal() {
        val before = snapshot()
        val next =
            patch(
                before,
                """{"operations":[{"op":"profile.update","id":10,"values":{"name":"edited"}}]}""",
            )
        assertEquals("keep-secret", next.profiles[0].socksBean!!.password)
        assertEquals(10L, next.profiles[0].id)
        assertEquals(4L, next.profiles[0].userOrder)
        assertEquals("original", before.profiles[0].requireBean().name)
        assertEquals("edited", next.profiles[0].requireBean().name)
    }

    @Test
    fun unknownAndRedactedFieldsAreRejected() {
        rejects("UNKNOWN_FIELD") {
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.update","id":10,"values":{"passwrod":"x"}}]}""",
            )
        }
        rejects("REDACTED_INPUT") {
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.update","id":10,"values":{"password":"<redacted>"}}]}""",
            )
        }
    }

    @Test
    fun numericOverflowAndWrongArrayTypeAreRejected() {
        rejects("INVALID_FIELD") {
            ControlJson.id(ControlJson.parseObject("""{"id":18446744073709551617}"""))
        }
        rejects("INVALID_FIELD") {
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.update","id":10,"values":{"serverPort":4294967296}}]}""",
            )
        }
        rejects("INVALID_FIELD") {
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.create","groupId":1,"protocol":"chain","values":{"proxies":["10"]}}]}""",
            )
        }
    }

    @Test
    fun deletingSelectedOrReferencedProfileIsRejected() {
        rejects("MISSING_PROFILE") {
            patch(snapshot(), """{"operations":[{"op":"profile.delete","id":10}]}""")
        }
        rejects("MISSING_PROFILE") {
            patch(snapshot(), """{"operations":[{"op":"rule.create","values":{"outbound":999}}]}""")
        }
    }

    @Test
    fun missingGroupAndChainCycleAreRejected() {
        rejects("MISSING_GROUP") {
            patch(snapshot(), """{"operations":[{"op":"profile.update","id":10,"groupId":999}]}""")
        }
        rejects("CHAIN_CYCLE") {
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.create","id":11,"groupId":1,"protocol":"chain","values":{"proxies":[12]}},{"op":"profile.create","id":12,"groupId":1,"protocol":"chain","values":{"proxies":[11]}}]}""",
            )
        }
    }

    @Test
    fun explicitEmptyPasswordClearsIt() {
        val next =
            patch(
                snapshot(),
                """{"operations":[{"op":"profile.update","id":10,"values":{"password":""}}]}""",
            )
        assertEquals("", next.profiles[0].socksBean!!.password)
    }

    @Test
    fun trafficDoesNotChangeRevisionMaterial() {
        val before = snapshot()
        val next = before.copy()
        next.profiles[0].tx = 999
        next.profiles[0].rx = 1000
        next.profiles[0].ping = 500
        next.profiles[0].status = 2
        next.profiles[0].error = "test"
        assertEquals(
            ControlJson.canonical(before.revisionData()),
            ControlJson.canonical(next.revisionData()),
        )
        next.profiles[0].socksBean!!.password = "changed"
        assertNotEquals(
            ControlJson.canonical(before.revisionData()),
            ControlJson.canonical(next.revisionData()),
        )
    }

    @Test
    fun settingsAllowlistCannotEnableAPI() {
        rejects("UNKNOWN_FIELD") {
            patch(
                snapshot(),
                """{"operations":[{"op":"settings.patch","values":{"adbControlEnabled":true}}]}""",
            )
        }
    }

    @Test
    fun typedDNSAndRulesPatch() {
        val next =
            patch(
                snapshot(),
                """{"operations":[{"op":"settings.patch","values":{"remoteDns":"1.1.1.1","enableDnsRouting":true}},{"op":"rule.create","values":{"enabled":true,"domains":"domain:example.org","outbound":10,"packages":["com.example.app"]}}]}""",
            )
        assertEquals("1.1.1.1", next.preferences.first { it.key == Key.REMOTE_DNS }.string)
        assertEquals(setOf("com.example.app"), next.rules.single().packages)
    }

    @Test
    fun defaultExportHidesCredentialsAndOpaqueJSON() {
        val json =
            ControlJson.parseObject(
                """{"password":"secret","setupKey":"enrollment","uuid":"credential","psk":"psk-value","customOutboundJson":"{\"unexpectedCredential\":\"hidden\"}","name":"visible"}"""
            )
        val redacted = ControlJson.redact(json).toString()
        listOf("secret", "enrollment", "credential", "psk-value", "hidden").forEach {
            assertFalse(redacted.contains(":\"$it\""))
        }
        assertTrue(redacted.contains("visible"))
    }

    @Test
    fun generatedTagsRejectMissingDetourAndCycles() {
        rejects("MISSING_DETOUR") {
            ControlValidation.checkTags(
                ControlJson.parseObject("""{"outbounds":[{"tag":"a","detour":"missing"}]}""")
            )
        }
        rejects("DETOUR_CYCLE") {
            ControlValidation.checkTags(
                ControlJson.parseObject(
                    """{"outbounds":[{"tag":"a","detour":"b"},{"tag":"b","detour":"a"}]}"""
                )
            )
        }
        rejects("MISSING_DETOUR") {
            ControlValidation.checkTags(
                ControlJson.parseObject("""{"dns":{"servers":[{"tag":"dns"}],"final":"absent"}}""")
            )
        }
    }

    @Test
    fun legacyDNSAnyMatchIsNotAnOutboundDetour() {
        ControlValidation.checkTags(
            ControlJson.parseObject(
                """{"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"tag":"dns"}],"rules":[{"outbound":["any"],"server":"dns"}]}}"""
            )
        )
    }

    @Test
    fun semanticDiffNeverContainsValues() {
        val diff =
            ControlJson.diff(
                ControlJson.parseObject("""{"password":"old"}"""),
                ControlJson.parseObject("""{"password":"newsecret"}"""),
            )
        assertTrue(diff.toString().contains("/password"))
        assertFalse(diff.toString().contains("newsecret"))
    }
}
