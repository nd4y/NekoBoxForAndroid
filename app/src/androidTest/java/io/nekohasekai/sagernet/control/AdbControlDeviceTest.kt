package io.nekohasekai.sagernet.control

import android.content.ContentValues
import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbControlDeviceTest {
    @Test
    fun appUidCannotUseAnyProviderEntryPoint() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val base = Uri.parse("content://${ctx.packageName}.control")
        val request = Uri.parse("$base/requests/${UUID.randomUUID()}")
        val response = Uri.parse("$base/responses/${UUID.randomUUID()}")
        val actions =
            listOf<() -> Unit>(
                {
                    resolver.call(base, "execute", UUID.randomUUID().toString(), null)
                    Unit
                },
                {
                    resolver.openOutputStream(request)?.close()
                    Unit
                },
                {
                    resolver.openInputStream(response)?.close()
                    Unit
                },
                {
                    resolver.query(base, null, null, null, null)?.close()
                    Unit
                },
                {
                    resolver.insert(base, ContentValues())
                    Unit
                },
                {
                    resolver.update(base, ContentValues(), null, null)
                    Unit
                },
                {
                    resolver.delete(response, null, null)
                    Unit
                },
            )
        // Android may swallow a getType SecurityException and return null. No MIME information is
        // disclosed.
        assertNull(resolver.getType(base))
        actions.forEachIndexed { index, action ->
            try {
                action()
                fail("Entry point $index admitted app UID")
            } catch (e: SecurityException) {
                assertTrue(
                    "Entry point $index: ${e.message}",
                    e.message.orEmpty().contains("ADB_SHELL_REQUIRED"),
                )
            }
        }
    }

    @Test
    fun generatedPerAppBlockRuleHasNoDanglingOutbound() {
        val candidate =
            ControlStore.capture().copy().apply {
                groups = mutableListOf(ProxyGroup(id = 1, name = "Synthetic"))
                profiles =
                    mutableListOf(
                        io.nekohasekai.sagernet.database.ProxyEntity(id = 10, groupId = 1).apply {
                            putBean(ControlProfiles.create("socks"))
                        }
                    )
                rules = mutableListOf()
                setPreference(
                    io.nekohasekai.sagernet.database.preference
                        .KeyValuePair(Key.PROFILE_ID)
                        .put(10L)
                )
            }
        ControlSettings.patch(
            candidate,
            ControlJson.objectOf(
                Key.PROXY_APPS to true,
                Key.INDIVIDUAL to "",
                Key.TUN_UNRECOGNIZED_TRAFFIC to "block",
            ),
        )
        assertEquals(listOf(10L), ControlValidation.validate(candidate))
    }

    @Test
    fun nativeDecoderRejectsUnknownTypesWithoutStartingTransports() {
        libcore.Libcore.checkConfigJSON("{}")
        libcore.Libcore.checkConfigJSON("""{"outbounds":[{"type":"direct","tag":"direct"}]}""")
        for (json in
            listOf(
                "{",
                """{"outbounds":[{"type":"does-not-exist"}]}""",
                """{"outbounds":[{"type":"direct","unknown":true}]}""",
            )) {
            var rejected = false
            try {
                libcore.Libcore.checkConfigJSON(json)
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Native decoder accepted invalid options", rejected)
        }
    }

    @Test
    fun journalRecoversPartialCrossDatabaseCommitAndStaleWritesFail() {
        val wasEnabled = DataStore.configurationStore.getBoolean("adbControlEnabled", false)
        DataStore.configurationStore.putBoolean("adbControlEnabled", true)
        val before = ControlStore.capture()
        val revision = ControlStore.revision(before)
        try {
            val next = before.copy()
            next.groups.add(
                ProxyGroup(
                    id = (next.groups.maxOfOrNull { it.id } ?: 0) + 1,
                    name = "ADB recovery test",
                )
            )
            ControlSettings.patch(next, ControlJson.objectOf(Key.DIRECT_DNS to "192.0.2.53"))
            val applied = ControlStore.apply(revision, next)
            try {
                ControlStore.apply(revision, before)
                fail("Stale write accepted")
            } catch (e: ControlError) {
                assertEquals("REVISION_CONFLICT", e.code)
            }
            val checkpoint =
                File(ControlAccess.directory, "checkpoints/${applied["checkpoint"].asString}.json")
            // Reproduce a crash after profile commit but before preference commit. No production
            // fault hook.
            ControlAccess.exclusive {
                val prefs = PublicDatabase.instance.keyValuePairDao()
                val original = before.preferences.firstOrNull { it.key == Key.DIRECT_DNS }
                if (original == null) prefs.delete(Key.DIRECT_DNS) else prefs.put(original)
                val journal = AtomicFile(File(ControlAccess.directory, "journal.json"))
                val output = journal.startWrite()
                output.write(checkpoint.readBytes())
                journal.finishWrite(output)
            }
            val recovered = ControlStore.capture()
            assertEquals(revision, ControlStore.revision(recovered))
            assertEquals(before.groups.map { it.id }, recovered.groups.map { it.id })
            assertFalse(File(ControlAccess.directory, "journal.json").exists())
        } finally {
            ControlStore.apply(ControlStore.revision(ControlStore.capture()), before)
            DataStore.configurationStore.putBoolean("adbControlEnabled", wasEnabled)
        }
    }
}
