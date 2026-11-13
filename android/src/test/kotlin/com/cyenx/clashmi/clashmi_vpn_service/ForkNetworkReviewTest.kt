package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ForkNetworkReviewTest {
    @Test fun A2_lostThenAvailableDoesNotPublishTransientNone() {
        val state = ForkUnderlyingNetwork<String>()
        state.available("wifi")
        state.lost("wifi")
        assertEquals("wifi", state.current(), "keep the last selection during the handoff grace period")
        state.available("cellular")
        assertEquals("cellular", state.current())
    }
    @Test fun A3_availableClearsAllCachedCapabilitiesAndLinks() {
        val state = ForkUnderlyingNetwork<String>()
        val caps = mutableMapOf("wifi" to "old caps", "other" to "other caps")
        val links = mutableMapOf("wifi" to "old links", "other" to "other links")
        forkUnderlyingAvailable(state, "wifi", caps, links)
        assertTrue(caps.isEmpty() && links.isEmpty(), "old network data must await fresh callbacks")
    }
    @Test fun A4_anyAddressDoesNotHideRealIPv6Gateway() {
        val ip = java.net.InetAddress.getByName("fe80::1")
        assertEquals(ip.hostAddress, forkDefaultGateway(listOf(true to java.net.InetAddress.getByName("0.0.0.0"),true to ip)))
        assertEquals(null, forkDefaultGateway(listOf(true to java.net.InetAddress.getByName("::"))))
    }
    @Test fun A5_receiverIsNotExportedAtEverySupportedAPI() {
        for (api in listOf(26,29,30,32,33,36)) assertEquals(4, forkLocationReceiverFlags(api), "API $api")
    }
}
