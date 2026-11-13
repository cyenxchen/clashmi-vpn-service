package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ForkUnderlyingNetworkTest {
    @Test
    fun onlyBestNetworkCallbackSelectsDefault() {
        val state = ForkUnderlyingNetwork<String>()
        state.available("wifi")
        assertEquals("wifi", state.current())
        state.available("cellular")
        state.lost("wifi")
        assertEquals("cellular", state.current())
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun queuedCallbackFromOldRegistrationCannotPublish() {
        val gate = ForkNetworkEpoch()
        val old = gate.begin()
        val current = gate.begin()
        var published = "new"
        gate.accept(old) { published = "old" }
        assertEquals("new", published)
        gate.accept(current) { published = "current" }
        assertEquals("current", published)
    }
}
