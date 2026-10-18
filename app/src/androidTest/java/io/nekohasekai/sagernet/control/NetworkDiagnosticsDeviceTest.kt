package io.nekohasekai.sagernet.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsDeviceTest {
    @Test
    fun throwingListenerDoesNotPoisonRealNetworkActor() = runBlocking {
        val bad = Any()
        val good = Any()
        val received = CountDownLatch(1)
        try {
            DefaultNetworkListener.start(bad) {
                throw java.net.SocketException("synthetic vanished interface")
            }
            DefaultNetworkListener.start(good) { if (it != null) received.countDown() }
            assertNotNull(withTimeout(15000) { DefaultNetworkListener.get() })
            assertTrue(received.await(5, TimeUnit.SECONDS))
            // A subsequent message must still be processed after the bad callback.
            assertNotNull(withTimeout(15000) { DefaultNetworkListener.get() })
        } finally {
            DefaultNetworkListener.stop(bad)
            DefaultNetworkListener.stop(good)
        }
    }

    @Test
    fun persistentCrashIsReadableWithCoreLoggingDisabledAndMasksCredentials() {
        val dir = File(SagerNet.application.noBackupFilesDir, "diagnostics")
        val source = if (SagerNet.application.isBgProcess) "crash-bg" else "crash-main"
        val target = File(dir, "$source.log")
        val before = if (target.exists()) target.readBytes() else null
        try {
            ControlDiagnostics.crash(
                Thread.currentThread(),
                IllegalStateException("password=synthetic-device-password"),
            )
            val result = ControlDiagnostics.read(ControlJson.objectOf("source" to source))
            assertTrue(result["text"].asString.contains("IllegalStateException"))
            assertFalse(result["text"].asString.contains("synthetic-device-password"))
            assertTrue(result["redacted"].asBoolean)
        } finally {
            if (before == null) target.delete() else target.writeBytes(before)
        }
    }
}
