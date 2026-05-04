package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.assertTrue
import kotlin.test.Test

internal class ClashmiVpnServicePluginTest {
    @Test
    fun preparedVpnConfig_parsesEnableIPv6() {
        val config = PreparedVpnConfig.fromMethodArguments(
            mapOf(
                "config" to mapOf(
                    "base_dir" to "/data/user/0/com.nebula.clashmi/files",
                    "core_path" to "/profiles/current.yaml",
                    "name" to "Clash Mi",
                    "control_port" to 9090,
                    "enable_ipv6" to true,
                ),
            ),
        )

        assertTrue(config.enableIPv6)
    }
}
