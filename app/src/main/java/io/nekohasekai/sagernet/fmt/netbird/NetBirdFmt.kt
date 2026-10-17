package io.nekohasekai.sagernet.fmt.netbird

import io.nekohasekai.sagernet.SagerNet
import moe.matsuri.nb4a.SingBoxOptions
import java.io.File

fun buildSingBoxOutboundNetBirdBean(
    bean: NetBirdBean,
    profileId: Long,
    forExport: Boolean = false,
): SingBoxOptions.Outbound_NetBirdOptions {
    validateNetBirdManagementUrl(bean.managementUrl)
    require(profileId > 0) { "Save the NetBird profile before connecting." }
    return SingBoxOptions.Outbound_NetBirdOptions().apply {
        type = "netbird"
        management_url = bean.managementUrl.trimEnd('/')
        setup_key = if (forExport) "" else bean.setupKey
        hostname = bean.hostname
        // Never put private peer keys in cache, profile exports or Android cloud backup.
        state_directory = if (forExport) "/NETBIRD_STATE_DIRECTORY" else
            File(SagerNet.application.noBackupFilesDir, "netbird/$profileId").absolutePath
    }
}
