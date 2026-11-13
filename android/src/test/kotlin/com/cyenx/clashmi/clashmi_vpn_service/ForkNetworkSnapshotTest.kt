package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import java.net.InetAddress

internal class ForkNetworkSnapshotTest {
    private class Clock {
        var now = 0L
        data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        fun after(delay: Long, run: () -> Unit): () -> Unit {
            val task = Task(now + delay, run)
            tasks += task
            return { task.cancelled = true }
        }
        fun advance(millis: Long) {
            now += millis
            tasks.toList().filter { !it.cancelled && it.at <= now }.forEach { it.cancelled = true; it.run() }
        }
        fun pending() = tasks.count { !it.cancelled }
    }
    @Test fun A7_handoffCancelsLossAndRealLossPublishesAfterOneSecond() {
        val clock = Clock()
        var none = 0
        val state = ForkUnderlyingNetwork<String>(clock::after) { none++ }
        state.available("wifi")
        state.lost("wifi")
        clock.advance(999)
        assertEquals("wifi", state.current())
        state.available("cellular")
        clock.advance(1)
        assertEquals(0, none)
        assertEquals(0, clock.pending())
        state.lost("wifi")
        assertEquals(0, clock.pending())
        state.lost("cellular")
        clock.advance(999)
        assertEquals(0, none)
        clock.advance(1)
        assertEquals(null, state.current())
        assertEquals(1, none)
        state.available("wifi2"); state.lost("wifi2"); state.clear()
        assertEquals(0, clock.pending())
        clock.advance(1000)
        assertEquals(1, none)
    }
    @Test fun A7_snapshotWaitsForBothFreshCallbacksAndExtractsFields() {
        val caps = mutableMapOf("wifi" to "old", "old" to "stale")
        val links = mutableMapOf("wifi" to "old", "old" to "stale")
        val state = ForkUnderlyingNetwork<String>()
        forkUnderlyingAvailable(state, "wifi", caps, links)
        assertFalse(forkNetworkReady("wifi", caps, links))
        caps["wifi"] = "fresh"
        assertFalse(forkNetworkReady("wifi", caps, links))
        links["wifi"] = "fresh"
        assertTrue(forkNetworkReady("wifi", caps, links))
        assertFalse(caps.containsKey("old")); assertFalse(links.containsKey("old"))
        assertEquals("wifi", forkNetworkTransport(true, false, false))
        assertEquals("wired", forkNetworkTransport(false, true, false))
        assertEquals("cellular", forkNetworkTransport(false, false, true))
        assertEquals("", forkNetworkTransport(false, false, false))
        assertEquals(mapOf("ssid" to "\"GOLDWIND\"", "bssid" to "02:00:00:00:00:00"), forkWifiFields("wifi", "\"GOLDWIND\"", "02:00:00:00:00:00"))
        assertTrue(forkWifiFields("cellular", "stale", "stale").isEmpty())
        assertEquals("10.15.67.254", forkDefaultGateway(listOf(false to InetAddress.getByName("10.1.1.1"), true to InetAddress.getByName("fe80::1"), true to InetAddress.getByName("10.15.67.254"))))
    }
    @Test fun A7_permissionReregistersAndReportPrecedesCoreStart() {
        val calls = mutableListOf<String>()
        forkRefreshRegistration(true, { calls += "unregister" }, { calls += "register" })
        forkReportBeforeStart({ calls += "report" }, { calls += "start" })
        assertEquals(listOf("unregister", "register", "report", "start"), calls)
        calls.clear()
        forkRefreshRegistration(false, { calls += "unregister" }, { calls += "register" })
        assertEquals(listOf("unregister"), calls)
        val before = forkNetworkPermissionSignature(listOf(0,0,-1), true, true)
        assertNotEquals(before, forkNetworkPermissionSignature(listOf(0,0,0), true, true))
        assertNotEquals(before, forkNetworkPermissionSignature(listOf(0,0,-1), false, false))
    }
    @Test fun A7_lossNotificationDoesNotHoldStateLock() {
        val clock = Clock()
        lateinit var state: ForkUnderlyingNetwork<String>
        var held = false
        state = ForkUnderlyingNetwork(clock::after) { held = Thread.holdsLock(state) }
        state.available("wifi"); state.lost("wifi"); clock.advance(1000)
        assertFalse(held, "publishing with the state lock inverts the snapshot service lock order")
    }
}
