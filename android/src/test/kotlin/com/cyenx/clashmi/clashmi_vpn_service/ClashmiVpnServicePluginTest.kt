package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun clearPendingStart_completesWaitingRequestWithError() {
        var result: Map<String, Any>? = null

        assertTrue(ClashmiVpnRuntime.beginStart { result = it })
        ClashmiVpnRuntime.clearPendingStart()

        // A stop/restart must not leave the MethodChannel start call hanging forever.
        val completed = assertNotNull(result)
        assertEquals("error", completed["type"])
        assertFalse(ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.doneResult()))
    }

    @Test
    fun stopWaiter_completesOnlyAfterNativeShutdownFinishes() {
        val runtimeClass = ClashmiVpnRuntime::class.java
        val beginStop = assertNotNull(
            runtimeClass.declaredMethods.firstOrNull { it.name.startsWith("beginStop") },
            "ClashmiVpnRuntime must expose a stop waiter",
        )
        val completeStop = assertNotNull(
            runtimeClass.declaredMethods.firstOrNull { it.name.startsWith("completeStop") },
            "ClashmiVpnRuntime must complete the stop waiter after native shutdown",
        )
        var result: Map<String, Any>? = null
        val callback: (Map<String, Any>) -> Unit = { result = it }

        assertEquals(true, beginStop.invoke(ClashmiVpnRuntime, callback))
        assertNull(result, "requestStop must wait while native cleanup is still running")
        assertEquals(
            true,
            completeStop.invoke(ClashmiVpnRuntime, ClashmiVpnRuntime.doneResult()),
        )
        assertEquals(ClashmiVpnRuntime.doneResult(), result)
    }
}
