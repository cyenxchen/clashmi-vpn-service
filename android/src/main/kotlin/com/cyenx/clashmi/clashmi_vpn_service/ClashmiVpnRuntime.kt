package com.cyenx.clashmi.clashmi_vpn_service

internal object ClashmiVpnRuntime {
    private var stateEmitter: ((String, Map<String, String>) -> Unit)? = null
    private var pendingStart: ((Map<String, Any>) -> Unit)? = null
    private var pendingStop: ((Map<String, Any>) -> Unit)? = null

    @Volatile
    var preparedConfig: PreparedVpnConfig? = null
        private set

    @Volatile
    var currentState: String = "disconnected"
        private set

    @Synchronized
    fun setPreparedConfig(config: PreparedVpnConfig) {
        preparedConfig = config
    }

    @Synchronized
    fun setStateEmitter(emitter: ((String, Map<String, String>) -> Unit)?) {
        stateEmitter = emitter
    }

    @Synchronized
    fun updateState(state: String, params: Map<String, String> = emptyMap()) {
        currentState = state
        stateEmitter?.invoke(state, params)
    }

    @Synchronized
    fun beginStart(callback: (Map<String, Any>) -> Unit): Boolean {
        if (pendingStart != null) {
            return false
        }
        pendingStart = callback
        return true
    }

    @Synchronized
    fun completeStart(result: Map<String, Any>): Boolean {
        val callback = pendingStart ?: return false
        pendingStart = null
        callback(result)
        return true
    }

    @Synchronized
    fun clearPendingStart() {
        val callback = pendingStart ?: return
        pendingStart = null
        // A stop can race a pending start. Complete the Flutter call instead of
        // silently dropping its callback and leaving the UI waiting forever.
        callback(errorResult("VPN start cancelled by stop request", isCloseError = true))
    }

    @Synchronized
    fun beginStop(callback: (Map<String, Any>) -> Unit): Boolean {
        if (pendingStop != null) {
            return false
        }
        pendingStop = callback
        return true
    }

    @Synchronized
    fun completeStop(result: Map<String, Any>): Boolean {
        val callback = pendingStop ?: return false
        pendingStop = null
        callback(result)
        return true
    }

    fun doneResult(): Map<String, Any> = mapOf("type" to "done")

    fun errorResult(message: String, isCloseError: Boolean = false): Map<String, Any> =
        mapOf(
            "type" to "error",
            "err" to mapOf(
                "message" to message,
                "is_close_error" to isCloseError,
            ),
        )

    fun timeoutResult(message: String): Map<String, Any> = mapOf("type" to "timeout", "err" to mapOf("message" to message))
}

/** Read-only process-local VPN state used by the Android quick settings tile. */
object ClashmiVpnStatus {
    @JvmStatic
    fun currentState(): String = ClashmiVpnRuntime.currentState
}
