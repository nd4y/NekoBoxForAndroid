package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.netbird.redactNetBirdSecrets
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    @Volatile
    var configurationRevision: String? = null

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        if (notTmp) {
            io.nekohasekai.sagernet.control.ControlAccess.exclusive {
                val snapshot = io.nekohasekai.sagernet.control.ControlSnapshot.capture()
                val current = snapshot.profiles.firstOrNull { it.id == profile.id }
                    ?: error("Selected profile no longer exists")
                // The service may already have captured a profile while the UI selected another one.
                snapshot.setPreference(io.nekohasekai.sagernet.database.preference.KeyValuePair(
                    io.nekohasekai.sagernet.Key.PROFILE_ID).put(profile.id))
                val revision = io.nekohasekai.sagernet.control.ControlStore.revision(snapshot)
                config = io.nekohasekai.sagernet.fmt.buildConfig(current)
                configurationRevision = revision
            }
        } else super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d(config.config.redactNetBirdSecrets())
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap).redactNetBirdSecrets())
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        runBlocking {
            looper?.stop()
            looper = null
        }
        super.close()
    }
}
