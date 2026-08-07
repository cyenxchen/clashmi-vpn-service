package com.cyenx.clashmi.clashmi_vpn_service

/** Keeps persistent network logs focused on actual snapshot transitions. */
internal class NetworkSnapshotLogTracker {
    private var previous: String? = null

    @Synchronized
    fun changed(current: String): Boolean {
        if (current == previous) {
            return false
        }
        previous = current
        return true
    }

    @Synchronized
    fun reset() {
        previous = null
    }
}
