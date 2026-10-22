package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.IOException

/** App-private, debug-only one-shot seam for real service recovery tests.
 * Never enabled by a config import or an external intent. Release builds omit it.
 */
internal object RecoveryFaults {
    fun afterStart() {
        if (!BuildConfig.DEBUG) return
        val file = File(SagerNet.application.noBackupFilesDir, "test-start-failures")
        if (!file.exists()) return
        val count = file.readText().trim().toIntOrNull()?.coerceIn(0, 3) ?: 0
        if (count > 0) {
            file.writeText((count - 1).toString())
            throw IOException("Injected startup failure")
        }
    }
}
