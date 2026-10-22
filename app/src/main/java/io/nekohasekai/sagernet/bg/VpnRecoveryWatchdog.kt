package io.nekohasekai.sagernet.bg

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.control.ControlDiagnostics
import io.nekohasekai.sagernet.database.DataStore

/** Inexact, OS-owned safety net: survives death of the native :bg process.
 * It only inspects our own service, never probes the network while healthy.
 * Android may defer delivery during idle; this is not an exact alarm.
 */
object VpnRecoveryWatchdog {
    private const val INTERVAL_MS = 60_000L
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 443, Intent(context, VpnRecoveryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, pending(context),
            )
        }.onFailure { ControlDiagnostics.event("service.watchdog.error", mapOf("type" to it.javaClass.simpleName)) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
    }
}

/** Runs in the default app process, separate from the native VPN process. */
class VpnRecoveryReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent?) {
        val generation = VpnIntentStore.token()
        if (generation <= 0) {
            VpnRecoveryWatchdog.cancel(context)
            return
        }
        VpnRecoveryWatchdog.schedule(context)
        val service = if (DataStore.serviceMode == Key.MODE_VPN) VpnService::class.java else ProxyService::class.java
        val component = ComponentName(context, service)
        // getRunningServices remains available for this app's own services.
        val running = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE).any { it.service == component && it.pid > 0 }
        }.getOrDefault(false)
        if (running || !VpnIntentStore.matches(generation)) return
        if (DataStore.serviceMode == Key.MODE_VPN && android.net.VpnService.prepare(context) != null) return
        runCatching {
            ControlDiagnostics.event("service.watchdog.recover")
            ContextCompat.startForegroundService(context,
                Intent(context, service).putExtra(VpnIntentStore.EXTRA_GENERATION, generation))
        }.onFailure { ControlDiagnostics.event("service.watchdog.error", mapOf("type" to it.javaClass.simpleName)) }
    }
}
