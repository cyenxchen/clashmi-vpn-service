package com.cyenx.clashmi.clashmi_vpn_service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class NetworkSnapshotLogTrackerTest {
    @Test
    fun changed_reportsOnlyMeaningfulSnapshotTransitions() {
        val tracker = NetworkSnapshotLogTracker()

        assertTrue(tracker.changed("wifi-snapshot"))
        assertFalse(tracker.changed("wifi-snapshot"))
        assertTrue(tracker.changed("cellular-snapshot"))

        tracker.reset()
        assertTrue(tracker.changed("cellular-snapshot"))
    }
}
