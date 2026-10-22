package io.nekohasekai.sagernet.bg

/** Preserve overlay connectivity for a VPN explicitly exempted from Doze. */
internal object CoreIdlePolicy {
    const val WHITELIST_CHANGED = "android.os.action.POWER_SAVE_WHITELIST_CHANGED"

    enum class Action { Sleep, Wake, WakeAndRecover }

    fun action(deviceIdle: Boolean, batteryExempt: Boolean, idleStateChanged: Boolean): Action =
        when {
            deviceIdle && !batteryExempt -> Action.Sleep
            !deviceIdle && idleStateChanged -> Action.WakeAndRecover
            else -> Action.Wake
        }
}
