package com.cyenx.clashmi.clashmi_vpn_service

internal data class PreparedVpnConfig(
    val baseDir: String,
    val corePath: String,
    val corePathPatch: String,
    val corePathPatchFinal: String,
    val logPath: String,
    val errorPath: String,
    val name: String,
    val controlPort: Int,
    val secret: String,
    val enableIPv6: Boolean,
) {
    val externalController: String
        get() = if (controlPort > 0) "127.0.0.1:$controlPort" else ""

    companion object {
        fun fromMethodArguments(args: Map<*, *>): PreparedVpnConfig {
            val config = args["config"] as? Map<*, *>
                ?: error("missing config")
            val controlPort = (config["control_port"] as? Number)?.toInt()
                ?: config["control_port"]?.toString()?.toIntOrNull()
                ?: 0
            return PreparedVpnConfig(
                baseDir = config.stringValue("base_dir"),
                corePath = config.stringValue("core_path"),
                corePathPatch = config.stringValue("core_path_patch"),
                corePathPatchFinal = config.stringValue("core_path_patch_final"),
                logPath = config.stringValue("log_path"),
                errorPath = config.stringValue("err_path"),
                name = config.stringValue("name").ifEmpty { "Clash Mi" },
                controlPort = controlPort,
                secret = config.stringValue("secret"),
                enableIPv6 = config.boolValue("enable_ipv6"),
            )
        }

        private fun Map<*, *>.stringValue(key: String): String = this[key]?.toString().orEmpty()

        private fun Map<*, *>.boolValue(key: String): Boolean =
            when (val value = this[key]) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> false
            }
    }
}
