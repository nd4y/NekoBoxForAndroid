package io.nekohasekai.sagernet.bg

internal class NetworkChangeRecoveryPolicy {
    data class Decision(
        val oldInterfaceName: String?,
        val newInterfaceName: String?,
        val reconnect: Boolean = false,
        val reset: Boolean = false,
        val ignoredReconnectForVpn: Boolean = false,
        val networkIdentityChanged: Boolean = false,
    ) {
        val changed: Boolean
            get() = oldInterfaceName != newInterfaceName || networkIdentityChanged
    }

    private var observedInitialState = false
    private var currentInterfaceName: String? = null
    private var currentNetworkHandle: Long? = null
    private var pendingRecoveryAfterLoss = false

    @Synchronized
    fun onNetworkChanged(
        interfaceName: String?,
        isVpnNetwork: Boolean,
        reconnectEnabled: Boolean,
        resetEnabled: Boolean,
        networkHandle: Long? = null,
    ): Decision {
        val oldInterfaceName = currentInterfaceName
        val oldNetworkHandle = currentNetworkHandle

        if (!observedInitialState) {
            observedInitialState = true
            currentInterfaceName = interfaceName
            currentNetworkHandle = networkHandle
            return Decision(oldInterfaceName, interfaceName)
        }

        if (oldInterfaceName == interfaceName && oldNetworkHandle == networkHandle) {
            return Decision(oldInterfaceName, interfaceName)
        }

        val lostKnownInterface = oldInterfaceName != null && interfaceName == null
        val recoveredAfterLoss =
            oldInterfaceName == null && interfaceName != null && pendingRecoveryAfterLoss
        val switchedKnownInterface = oldInterfaceName != null && interfaceName != null
        val reconnectCandidate = recoveredAfterLoss || switchedKnownInterface
        val reconnect = reconnectEnabled && reconnectCandidate && !isVpnNetwork
        val ignoredReconnectForVpn = reconnectEnabled && reconnectCandidate && isVpnNetwork
        val reset =
            resetEnabled && (lostKnownInterface || recoveredAfterLoss || switchedKnownInterface)

        currentInterfaceName = interfaceName
        currentNetworkHandle = networkHandle
        if (lostKnownInterface) {
            pendingRecoveryAfterLoss = true
        } else if (interfaceName != null && !isVpnNetwork) {
            pendingRecoveryAfterLoss = false
        }

        return Decision(
            oldInterfaceName = oldInterfaceName,
            newInterfaceName = interfaceName,
            reconnect = reconnect,
            reset = reset,
            ignoredReconnectForVpn = ignoredReconnectForVpn,
            networkIdentityChanged = oldNetworkHandle != networkHandle,
        )
    }
}
