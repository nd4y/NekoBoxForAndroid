package io.nekohasekai.sagernet.utils

import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class NetworkCallbackSafetyTest {
    @Test
    fun disappearingInterfaceDoesNotTerminateHandover() {
        assertEquals(
            1500,
            NetworkCallbackSafety.mtu(1500) { throw SocketException("No such device") },
        )
        assertEquals(1400, NetworkCallbackSafety.mtu(1500) { 1400 })
        assertEquals(1500, NetworkCallbackSafety.mtu(1500) { 0 })
    }

    @Test
    fun badListenerDoesNotKillOtherListenersOrNextTransition() {
        val received = mutableListOf<String?>()
        var failed = 0
        val listeners =
            listOf<(String?) -> Unit>(
                { throw IllegalArgumentException("stale network") },
                { received.add(it) },
            )
        for (network in listOf("wlan0", null, "rmnet0", "wlan0")) {
            NetworkCallbackSafety.deliver(listeners, network) { failed++ }
        }
        assertEquals(listOf("wlan0", null, "rmnet0", "wlan0"), received)
        assertEquals(4, failed)
    }
}
