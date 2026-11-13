package com.cyenx.clashmi.clashmi_vpn_service

internal class ForkUnderlyingNetwork<T>(
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
    private val onExpired: () -> Unit = {},
) {
    private var selected: T? = null
    private var cancelLoss: (() -> Unit)? = null
    private var generation = 0L

    @Synchronized
    fun available(network: T) { cancelPending(); selected = network }

    @Synchronized
    fun lost(network: T) {
        if (selected != network) return
        cancelPending()
        val token = generation
        cancelLoss = schedule(1000) {
            val notify = synchronized(this) {
                if (token == generation && selected == network) {
                    selected = null
                    cancelLoss = null
                    true
                } else false
            }
            // Snapshot publication takes the service lock and may read current().
            if (notify) onExpired()
        }
    }

    private fun cancelPending() { generation++; cancelLoss?.invoke(); cancelLoss = null }
    @Synchronized fun clear() { cancelPending(); selected = null }

    @Synchronized
    fun current(): T? = selected
}

internal class ForkNetworkEpoch {
    private var epoch = 0L
    @Synchronized fun begin(): Long = ++epoch
    @Synchronized fun accept(token: Long, block: () -> Unit) { if (token == epoch) block() }
}

internal fun <N, C, L> forkUnderlyingAvailable(
    state: ForkUnderlyingNetwork<N>, network: N,
    capabilities: MutableMap<N, C>, links: MutableMap<N, L>,
) { capabilities.clear(); links.clear(); state.available(network) }
