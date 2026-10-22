package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.withTimeout

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()

        class Update(val network: Network) : NetworkMessage()

        class Lost(val network: Network) : NetworkMessage()

        class Work(val action: () -> Unit) : NetworkMessage()
    }

    private fun notify(listeners: Collection<(Network?) -> Unit>, network: Network?) =
        NetworkCallbackSafety.deliver(listeners, network) { error ->
            io.nekohasekai.sagernet.control.ControlDiagnostics.event(
                "network.listener.failure",
                mapOf("error" to error.javaClass.name),
            )
            Logs.w(error)
        }

    // Never execute native network recovery on ConnectivityThread/the main callback handler.
    private val networkActor =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).actor<NetworkMessage>(
            capacity = Channel.UNLIMITED
        ) {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            var network: Network? = null
            val pendingRequests = arrayListOf<NetworkMessage.Get>()
            for (message in channel) when (message) {
                is NetworkMessage.Start -> {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) notify(listOf(message.listener), network)
                }
                is NetworkMessage.Get -> {
                    pendingRequests.removeAll { it.response.isCompleted }
                    if (listeners.isEmpty())
                        message.response.completeExceptionally(
                            UnknownHostException("No network listeners")
                        )
                    else if (network == null) pendingRequests += message
                    else message.response.complete(network)
                }
                is NetworkMessage.Stop ->
                    if (
                        listeners.isNotEmpty() && // was not empty
                            listeners.remove(message.key) != null &&
                            listeners.isEmpty()
                    ) {
                        network = null
                        runCatching { unregister() }.onFailure { Logs.w(it) }
                        pendingRequests.forEach {
                            it.response.completeExceptionally(
                                UnknownHostException("Network listener stopped")
                            )
                        }
                        pendingRequests.clear()
                    }

                is NetworkMessage.Put -> {
                    network = message.network
                    pendingRequests.forEach { it.response.complete(message.network) }
                    pendingRequests.clear()
                    notify(listeners.values, network)
                }
                is NetworkMessage.Update ->
                    if (network == message.network) notify(listeners.values, network)
                is NetworkMessage.Work ->
                    NetworkCallbackSafety.deliver(
                        listOf<((Unit) -> Unit)>({ message.action() }),
                        Unit,
                    ) { error ->
                        io.nekohasekai.sagernet.control.ControlDiagnostics.event(
                            "network.worker.failure",
                            mapOf("error" to error.javaClass.name),
                        )
                        Logs.w(error)
                    }
                is NetworkMessage.Lost ->
                    if (network == message.network) {
                        network = null
                        notify(listeners.values, null)
                    }
            }
        }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get() =
        if (fallback)
            {
                SagerNet.connectivity.activeNetwork
                    ?: throw UnknownHostException() // failed to listen, return current if available
            }
        else
            NetworkMessage.Get().run {
                try {
                    withTimeout(10_000L) {
                        networkActor.send(this@run)
                        response.await()
                    }
                } finally {
                    response.cancel()
                }
            }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = enqueue(NetworkMessage.Put(network))

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) { // it's a good idea to refresh capabilities
            enqueue(NetworkMessage.Update(network))
        }

        override fun onLost(network: Network) = enqueue(NetworkMessage.Lost(network))
    }

    fun runOnNetworkWorker(action: () -> Unit) = enqueue(NetworkMessage.Work(action))

    private fun enqueue(message: NetworkMessage) {
        networkActor.trySend(message)
    }

    @Volatile private var fallback = false
    private val request =
        NetworkRequest.Builder()
            .apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                }
            }
            .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P
     * DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible
     * networks that satisfies default network capabilities but only THE default network.
     * Unfortunately, we need to have android.permission.CHANGE_NETWORK_STATE to be able to call
     * requestNetwork.
     *
     * Source:
     * https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE ->
                    {
                        SagerNet.connectivity.registerBestMatchingNetworkCallback(
                            request,
                            Callback,
                            mainHandler,
                        )
                    }
                in 28 until 31 ->
                    { // we want REQUEST here instead of LISTEN
                        SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
                    }
                in 26 until 28 ->
                    {
                        SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                    }
                in 24 until 26 ->
                    {
                        SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                    }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    private fun unregister() = SagerNet.connectivity.unregisterNetworkCallback(Callback)
}
