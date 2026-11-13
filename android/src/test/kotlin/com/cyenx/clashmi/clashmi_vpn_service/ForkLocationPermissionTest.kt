package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertEquals

internal class ForkLocationPermissionTest {
    @Test
    fun foregroundMustPrecedeSeparateBackgroundRequest() {
        assertEquals("foreground", forkLocationRequest(36, false, false))
        assertEquals("settings", forkLocationRequest(36, true, false))
        assertEquals("background", forkLocationRequest(29, true, false))
        assertEquals("none", forkLocationRequest(28, true, false))
        assertEquals("none", forkLocationRequest(36, true, true))
    }
}
