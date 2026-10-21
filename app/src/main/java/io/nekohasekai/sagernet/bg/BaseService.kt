package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_000L

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        @Volatile var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var networkRecoveryJob: Job? = null
        var retryJob: Job? = null
        var stableJob: Job? = null
        @Volatile var retryAttempt = 0
        @Volatile var retryAtMs: Long? = null
        var intentToken = 0L
        var startAfterRecovery = false
        var lastStartId = 0
        @Volatile var holdTunDuringRecovery = false

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                CoreIdlePolicy.WHITELIST_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val action = CoreIdlePolicy.action(
                            SagerNet.power.isDeviceIdleMode,
                            SagerNet.power.isIgnoringBatteryOptimizations(SagerNet.application.packageName),
                            intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                        )
                        when (action) {
                            CoreIdlePolicy.Action.Sleep -> proxy?.box?.sleep()
                            CoreIdlePolicy.Action.Wake -> proxy?.box?.wake()
                            CoreIdlePolicy.Action.WakeAndRecover -> {
                                proxy?.box?.wake()
                                service.handleConnectionRecovery(
                                    reconnect = DataStore.wakeReconnect,
                                    reset = DataStore.wakeResetConnections
                                )
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    service.resetCoreNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> {
                    val generation = intent.getLongExtra(VpnIntentStore.EXTRA_GENERATION, 0L)
                    if (generation == 0L || generation == VpnIntentStore.token()) service.stopRunner()
                }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        val lifecycleMutex = Mutex()

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            io.nekohasekai.sagernet.control.ControlDiagnostics.event("service.state", mapOf("from" to state.name, "to" to s.name))
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun netBirdStatus(): String = Libcore.netBirdStatus()
        override fun netBirdDiagnostics(): String = Libcore.netBirdDiagnostics()
        override fun startNetBirdDiagnostics(): String = Libcore.startNetBirdDiagnostics()
        override fun cancelNetBirdDiagnostics(): String = Libcore.cancelNetBirdDiagnostics()

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box,
                    DataStore.connectionTestURL,
                    DataStore.connectionTestTimeout,
                    DataStore.profileTestType
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun isCoreProfilingRunning(): Boolean = Libcore.coreProfilingRunning()

        override fun hasCoreProfilerSnapshot(): Boolean = Libcore.hasCoreProfilerSnapshot()

        override fun performLibcoreGcSweep() {
            if (data?.proxy?.isInitialized() != true) {
                error("Service is not running")
            }
            Libcore.performLibcoreGCSweep()
        }

        override fun startCoreProfiling() {
            if (data?.proxy?.isInitialized() != true) {
                error("Core is not started yet")
            }
            Libcore.startCoreProfiling()
        }

        override fun stopCoreProfiling() {
            Libcore.stopCoreProfiling()
        }

        override fun writeCoreProfilerSnapshot(outputDir: String) {
            if (data?.proxy?.isInitialized() != true && !Libcore.hasCoreProfilerSnapshot()) {
                error("Core is not started yet")
            }
            Libcore.writeCoreProfilerSnapshot(outputDir)
        }

        override fun deleteCoreProfilerSnapshot() {
            Libcore.deleteCoreProfilerSnapshot()
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun masterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) = launch {
            broadcast { it.cbMasterDnsVPNResolverProgress(found, total, ready) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty), recover = true)
                return
            }
            if (canReloadSelector()) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // select from GUI
                    val proxy = data.proxy!!
                    runBlocking {
                        proxy.looper?.pauseUpdates {
                            proxy.box.selectOutbound(tag)
                            resetCoreNetwork()
                        } ?: run {
                            proxy.box.selectOutbound(tag)
                            resetCoreNetwork()
                        }
                    }
                    // or select from webui
                    // => selector_OnProxySelected
                }
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun hasActiveWifiRules(): Boolean {
            return SagerDatabase.rulesDao.enabledRules().any { RuleEntity.hasActiveWifiIdentity(it) }
        }

        fun startRunner() {
            if (VpnIntentStore.token() <= 0) return
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass).putExtra(VpnIntentStore.EXTRA_GENERATION, VpnIntentStore.token()))
            else startService(Intent(this, javaClass).putExtra(VpnIntentStore.EXTRA_GENERATION, VpnIntentStore.token()))
        }

        /**
         * Some devices throttle service/native cleanup aggressively while running on battery.
         * Hold a short, local PARTIAL_WAKE_LOCK only for the disconnect/reconnect critical section
         * so VpnService/tun/core teardown is not delayed by idle CPU scheduling.
         */
        private inline fun <T> withStopWakeLock(block: () -> T): T {
            this as Context
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val stopWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VpnServiceStop"
            ).apply {
                setReferenceCounted(false)
                acquire(15_000L)
            }
            return try {
                block()
            } finally {
                if (stopWakeLock.isHeld) {
                    runCatching { stopWakeLock.release() }.onFailure { Logs.w(it) }
                }
            }
        }

        fun removeForegroundNotification() {
            this as Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

        fun releaseRetainedResources() = Unit

        fun killProcesses() {
            data.networkRecoveryJob?.cancel()
            data.networkRecoveryJob = null
            SagerNet.application.nativeInterface.unregisterWifiStateListener()
            SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(false)
            data.proxy?.let { proxy ->
                runCatching {
                    runBlocking {
                        proxy.looper?.stop()
                        proxy.looper = null
                    }
                    runCatching {
                        if (Libcore.coreProfilingRunning()) {
                            Libcore.stopCoreProfiling()
                        }
                    }.onFailure { Logs.w(it) }
                    if (proxy.isInitialized()) {
                        proxy.box.closeTimeout(3000)
                    } else {
                        proxy.close()
                    }
                }.onFailure {
                    Logs.w(it)
                    runCatching { proxy.close() }.onFailure { closeError -> Logs.w(closeError) }
                }
            }
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        fun resetCoreNetwork() {
            val proxy = data.proxy
            if (proxy != null && proxy.isInitialized()) {
                runCatching {
                    proxy.box.resetNetwork()
                }.onFailure {
                    Logs.w(it)
                    Libcore.resetAllConnections(true)
                }
                return
            }
            Libcore.resetAllConnections(true)
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null, recover: Boolean = false) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                runOnMainDispatcher { stopRunner(restart, msg, recover) }
                return
            }
            if (!restart && !recover) {
                VpnIntentStore.request(false)
                VpnRecoveryWatchdog.cancel(this as Context)
            }
            data.holdTunDuringRecovery = (recover || restart) && VpnIntentStore.matches(data.intentToken)
            data.retryJob?.cancel(null)
            data.retryJob = null
            data.stableJob?.cancel(null)
            data.stableJob = null
            data.retryAtMs = null
            if (data.state == State.Stopping) return
            val preservingIntent = restart || recover
            if (!preservingIntent) {
                data.notification?.destroy()
                data.notification = null
            }
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                val currentJob = currentCoroutineContext()[Job]
                val connectingJob = data.connectingJob
                if (connectingJob != null && connectingJob != currentJob) {
                    cancelConnectingJob(connectingJob) {
                        data.proxy?.takeIf { it.isInitialized() }?.let { proxy ->
                            runCatching { proxy.box.cancelStart() }.onFailure { Logs.w(it) }
                        }
                    }
                }

                data.lifecycleMutex.withLock {
                    // Remove the foreground notification promptly, but keep the service alive until
                    // native/VPN cleanup finishes. This makes the visible disconnect state immediate
                    // while avoiding a new start racing the old tun/core instance.
                    if (!preservingIntent && VpnIntentStore.token() <= 0)
                        runCatching { removeForegroundNotification() }.onFailure { Logs.w(it) }

                    withContext(Dispatchers.IO) {
                        withStopWakeLock {
                            killProcesses()
                        }
                    }

                    data.proxy = null
                    DataStore.vpnService = null
                }

                // stop the service if nothing has bound to it
                val nextToken = VpnIntentStore.token()
                if (nextToken > 0) {
                    val wait = if (recover) VpnRecoveryPolicy.delayMillis(
                        data.retryAttempt++, kotlin.random.Random.nextLong(1000)
                    ) else 300L
                    data.retryAtMs = System.currentTimeMillis() + wait
                    io.nekohasekai.sagernet.control.ControlDiagnostics.event(
                        "service.retry.scheduled", mapOf("attempt" to data.retryAttempt, "delayMs" to wait)
                    )
                    data.changeState(State.Connecting)
                    data.notification?.postNotificationTitle(getString(R.string.vpn_recovery_wait, (wait + 999) / 1000))
                    data.retryJob = runOnMainDispatcher {
                        delay(wait)
                        if (VpnIntentStore.matches(nextToken)) {
                            data.retryAtMs = null
                            data.retryJob = null
                            data.startAfterRecovery = true
                            onStartCommand(Intent(this@Interface as Context, this@Interface.javaClass).putExtra(VpnIntentStore.EXTRA_GENERATION, nextToken), 0, 0)
                        }
                    }
                } else {
                    data.notification?.destroy()
                    data.notification = null
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    // Stop may arrive after cleanup retained a TUN. Dispose it
                    // here as well, so a bound service cannot keep routing alive.
                    data.holdTunDuringRecovery = false
                    releaseRetainedResources()
                    DataStore.baseService = null
                    data.changeState(State.Stopped, msg)
                    if (data.lastStartId > 0) stopSelfResult(data.lastStartId) else stopSelf()
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        fun handleConnectionRecovery(reconnect: Boolean, reset: Boolean) {
            if (reconnect && data.state.canStop) {
                DataStore.pendingResetConnectionsAfterReconnect = reset
                stopRunner(true)
                return
            }
            if (reset) {
                resetCoreNetwork()
            }
        }

        fun isVpnNetwork(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }
            return SagerNet.connectivity.getLinkProperties(network)?.interfaceName?.startsWith("tun") == true
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            val networkChangeRecoveryPolicy = NetworkChangeRecoveryPolicy()

            fun handleNetworkUpdate(network: Network?) {
                SagerNet.underlyingNetwork = network
                SagerNet.application.nativeInterface.syncNetworkState(network)
                DataStore.vpnService?.updateUnderlyingNetwork()
                val link = network?.let { current -> SagerNet.connectivity.getLinkProperties(current) }
                val currentName = link?.interfaceName
                upstreamInterfaceName = currentName
                val caps = network?.let { SagerNet.connectivity.getNetworkCapabilities(it) }
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "other"
                }
                io.nekohasekai.sagernet.control.ControlDiagnostics.event(
                    "network.update",
                    mapOf("interface" to currentName, "network" to network?.networkHandle, "transport" to transport),
                )
                val decision = networkChangeRecoveryPolicy.onNetworkChanged(
                    interfaceName = currentName,
                    isVpnNetwork = isVpnNetwork(network),
                    reconnectEnabled = DataStore.networkChangeReconnect,
                    resetEnabled = DataStore.networkChangeResetConnections,
                    networkHandle = network?.networkHandle,
                )
                if (decision.reconnect || decision.reset) {
                    Logs.d("Network changed: ${decision.oldInterfaceName} -> ${decision.newInterfaceName}")
                    data.networkRecoveryJob?.cancel()
                    data.networkRecoveryJob = runOnDefaultDispatcher {
                        delay(NETWORK_RECOVERY_DEBOUNCE_MS)
                        if (!data.state.started) return@runOnDefaultDispatcher
                        handleConnectionRecovery(
                            reconnect = decision.reconnect,
                            reset = decision.reset
                        )
                    }
                }
                if (decision.ignoredReconnectForVpn) {
                    Logs.d(
                        "Ignore VPN network change for reconnect: " +
                            "${decision.oldInterfaceName} -> ${decision.newInterfaceName}"
                    )
                }
            }

            DefaultNetworkListener.start(this) {
                handleNetworkUpdate(it)
            }
            runCatching {
                DefaultNetworkListener.get()
            }.onSuccess { network ->
                handleNetworkUpdate(network)
            }.onFailure { error ->
                Logs.w("Unable to fetch initial default network: ${error.message}")
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (startId > 0) data.lastStartId = startId
            val fromRecovery = data.startAfterRecovery
            data.startAfterRecovery = false
            val requested = intent?.getLongExtra(VpnIntentStore.EXTRA_GENERATION, 0L) ?: 0L
            var token = VpnIntentStore.token()
            // A first OS/legacy start is allowed. A saved explicit Stop is not.
            if (token == 0L && requested == 0L) token = VpnIntentStore.request(true)
            if (!VpnRecoveryPolicy.accepts(requested, token)) {
                if (token > 0 && data.state != State.Stopped) return Service.START_STICKY
                if (data.state == State.Stopped && startId > 0) (this as Service).stopSelfResult(startId)
                return Service.START_NOT_STICKY
            }
            DataStore.baseService = this
            VpnRecoveryWatchdog.schedule(this as Context)
            val data = data
            val newRequest = data.intentToken != token
            var resumeRetry = fromRecovery
            if (newRequest) {
                data.retryAttempt = 0
                data.retryAtMs = null
                if (data.retryJob != null) {
                    data.retryJob?.cancel(null)
                    data.retryJob = null
                    resumeRetry = true
                }
            }
            data.intentToken = token
            if (newRequest && data.state == State.Connected) {
                stopRunner(restart = true)
                return Service.START_STICKY
            }
            if (data.state != State.Stopped && !resumeRetry) {
                // A newer Connect may arrive while an earlier Stop is draining.
                // Fulfil the foreground deadline immediately; teardown will start
                // the newest generation after old native ownership is released.
                if (data.notification == null) data.notification = createNotification(data.proxy?.displayProfileName ?: "")
                return Service.START_STICKY
            }
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty), recover = true)
                return Service.START_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            runOnDefaultDispatcher {
                SubscriptionUpdater.syncBootReceiverEnabled()
            }
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                        addAction(CoreIdlePolicy.WHITELIST_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = runOnDefaultDispatcher {
                data.lifecycleMutex.withLock {
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            if (data.notification == null) data.notification = createNotification(ServiceNotification.genTitle(profile))
                            else data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))
                        }

                        Executable.killAll()    // clean up old processes
                        val hasActiveWifiRules = hasActiveWifiRules()
                        SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(hasActiveWifiRules)
                        SagerNet.application.nativeInterface.unregisterWifiStateListener()
                        preInit()
                        proxy.init()
                        // Stop may have arrived before init assigned the native box.
                        currentCoroutineContext().ensureActive()
                        if (hasActiveWifiRules) {
                            SagerNet.application.nativeInterface.registerWifiStateListener()
                            SagerNet.application.nativeInterface.notifyWifiStateChanged("post-init")
                        }
                        DataStore.currentProfile = profile.id

                        proxy.processes = GuardedProcessPool { failure ->
                            Logs.w(failure)
                            runOnMainDispatcher {
                                if (data.proxy === proxy && VpnIntentStore.matches(token))
                                    stopRunner(false, failure.readableMessage, recover = true)
                            }
                        }

                        currentCoroutineContext().ensureActive()
                        startProcesses()
                        RecoveryFaults.afterStart()
                        currentCoroutineContext().ensureActive()
                        if (DataStore.enableCoreProfiling) {
                            Libcore.startCoreProfiling()
                        }
                        if (!VpnIntentStore.matches(token)) { stopRunner(restart = VpnIntentStore.token() > 0); return@withLock }
                        data.holdTunDuringRecovery = false
                        data.changeState(State.Connected)
                        data.stableJob = runOnDefaultDispatcher {
                            delay(60_000)
                            if (VpnIntentStore.matches(token) && data.state == State.Connected) data.retryAttempt = 0
                        }
                        SagerNet.application.nativeInterface.maybeShowRedactedWifiToastOnConnect()

                        lateInit()
                        if (DataStore.pendingResetConnectionsAfterReconnect) {
                            DataStore.pendingResetConnectionsAfterReconnect = false
                            resetCoreNetwork()
                        }
                    } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                    } catch (_: UnknownHostException) {
                        io.nekohasekai.sagernet.control.ControlDiagnostics.event("service.start.failed", mapOf("category" to "dns", "type" to "UnknownHostException"))
                        currentCoroutineContext().ensureActive()
                        stopRunner(false, getString(R.string.invalid_server), recover = true)
                    } catch (e: PluginManager.PluginNotFoundException) {
                        currentCoroutineContext().ensureActive()
                        if (data.retryAttempt == 0) {
                            withContext(Dispatchers.Main.immediate) {
                                Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                            }
                            data.binder.missingPlugin(e.plugin)
                        }
                        Logs.w(e)
                        data.retryAttempt = maxOf(data.retryAttempt, 5)
                        stopRunner(false, null, recover = true)
                    } catch (exc: Throwable) {
                        io.nekohasekai.sagernet.control.ControlDiagnostics.event("service.start.failed", mapOf("type" to exc.javaClass.simpleName, "category" to VpnRecoveryPolicy.failureCategory(exc.readableMessage)))
                        // Go reports canceled startup as a native error, not CancellationException.
                        currentCoroutineContext().ensureActive()
                        if (exc.readableMessage.contains("no working DNS resolvers found", ignoreCase = true)) {
                            withContext(Dispatchers.Main.immediate) {
                                Toast.makeText(
                                    this@Interface,
                                    R.string.masterdnsvpn_no_working_dns,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            stopRunner(false, null, recover = true)
                            return@withLock
                        }
                        if (exc.javaClass.name.endsWith("proxyerror")) {
                            // error from golang
                            Logs.w(exc.readableMessage)
                        } else {
                            Logs.w(exc)
                        }
                        stopRunner(
                            false, "${getString(R.string.service_failed)}: ${exc.readableMessage}", recover = true
                        )
                    } finally {
                        data.connectingJob = null
                    }
                }
            }
            return Service.START_STICKY
        }
    }

}
