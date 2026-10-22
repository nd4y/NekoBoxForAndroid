package io.nekohasekai.sagernet.bg

object VpnTunReusePolicy {
    fun signature(options: String, platform: String): String {
        // Go auto-selects a new interface name when the retained tun already
        // exists. Android Builder does not consume that generated name/FD.
        val stable = runCatching {
            org.json.JSONObject(options).apply {
                remove("Name"); remove("name")
                remove("FileDescriptor"); remove("file_descriptor")
            }.toString()
        }.getOrElse { options }
        return stable + "\n" + platform
    }

    fun canReuse(retaining: Boolean, oldRevision: String?, newRevision: String?,
        oldConfig: String?, newConfig: String, descriptorValid: Boolean): Boolean =
        retaining && descriptorValid && oldRevision != null && oldRevision == newRevision && oldConfig == newConfig
}
