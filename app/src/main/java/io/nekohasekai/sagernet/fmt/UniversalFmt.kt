package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.netbird.NetBirdBean
import io.nekohasekai.sagernet.fmt.netbird.sanitizeNetBirdConfigForExport
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.proxy.config.ConfigBean
import okio.ByteString.Companion.toByteString

fun parseUniversal(link: String): AbstractBean {
    return if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))))
        }.requireBean()
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.b64Decode(link.substringAfter(":").substringAfter(":")))
        }.requireBean()
    }
}

fun AbstractBean.toUniversalLink(): String {
    var link = "sn://"
    val type = TypeMap.reversed[ProxyEntity().putBean(this).type]
        ?: error("Profile is not exportable as SN link")
    link += type
    link += "?"
    val exportBean = when (this) {
        is NetBirdBean -> clone().apply { setupKey = "" }
        is ConfigBean -> clone().apply {
            if (config.isNotBlank()) {
                val configMap = gson.fromJson(config, mutableMapOf<String, Any?>().javaClass)
                sanitizeNetBirdConfigForExport(
                    if (this.type == 0) configMap else mapOf("outbounds" to listOf(configMap))
                )
                config = gson.toJson(configMap)
            }
        }
        else -> this
    }
    link += Util.zlibCompress(KryoConverters.serialize(exportBean), 9)
        .toByteString().base64Url().trimEnd('=')
    return link
}


fun ProxyGroup.toUniversalLink(): String {
    var link = "sn://subscription?"
    export = true
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    export = false
    return link
}
