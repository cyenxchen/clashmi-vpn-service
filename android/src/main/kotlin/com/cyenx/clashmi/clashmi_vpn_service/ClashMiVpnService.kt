package com.cyenx.clashmi.clashmi_vpn_service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cyenx.clashmi.core.clashmicore.Clashmicore
import com.cyenx.clashmi.core.clashmicore.PersistentLogWriter
import com.cyenx.clashmi.core.clashmicore.SocketProtector
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class ClashMiVpnService : VpnService() {
    private val desiredRunning = AtomicBoolean(false)
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClashMiVpnLifecycle")
    }
    @Volatile
    private var coreRunning = false
    private var tunFd: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networkSnapshotLogTracker = NetworkSnapshotLogTracker()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                desiredRunning.set(true)
                lifecycleLog("I", "start requested startId=$startId state=${ClashmiVpnRuntime.currentState}")
                try {
                    // Every startForegroundService request must be promoted before
                    // branching or queuing slow native work.
                    promoteToForeground()
                } catch (error: Throwable) {
                    val message = "foreground promotion failed: ${error.message ?: error}"
                    desiredRunning.set(false)
                    lifecycleLog("E", message, error)
                    updateState("disconnected")
                    ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(message))
                    stopSelfResult(startId)
                    return Service.START_NOT_STICKY
                }
                if (!enqueueLifecycle("start") { startCore(startId) }) {
                    failStart("lifecycle executor rejected start", startId)
                }
            }
            ACTION_STOP -> requestStop("stop action", startId)
            null -> {
                // START_NOT_STICKY should prevent null-intent resurrection, but
                // handle it defensively without unexpectedly rebuilding the VPN.
                lifecycleLog("W", "null start intent ignored startId=$startId")
                requestStop("null start intent", startId)
            }
            else -> {
                lifecycleLog("W", "unknown action=${intent.action} startId=$startId")
                stopSelfResult(startId)
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        desiredRunning.set(false)
        ClashmiVpnRuntime.clearPendingStart()
        if (coreRunning || ClashmiVpnRuntime.currentState != "disconnected") {
            updateState("disconnecting")
            enqueueLifecycle("destroy") {
                stopCore("service destroy", startId = null, stopService = false)
            }
        }
        lifecycleExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        lifecycleLog("W", "vpn permission revoked")
        requestStop("vpn revoked", startId = null)
        super.onRevoke()
    }

    private fun startCore(startId: Int) {
        if (!desiredRunning.get()) {
            lifecycleLog("I", "start cancelled before native work startId=$startId")
            finishCancelledStart(startId)
            return
        }
        if (coreRunning) {
            lifecycleLog("I", "core start ignored: already connected startId=$startId")
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.doneResult())
            return
        }
        val config = ClashmiVpnRuntime.preparedConfig ?: restorePreparedConfig()
        if (config == null) {
            failStart("missing prepared config", startId)
            return
        }
        try {
            lifecycleLog(
                "I",
                "core starting startId=$startId config=${config.corePath} patch=${config.corePathPatch} finalPatch=${config.corePathPatchFinal}",
            )
            updateState("connecting")
            clearErrorFile(config)
            installPersistentCoreLogWriter()
            installSocketProtector()
            updateAndroidNetworkInfo("core start")
            registerNetworkCallback()
            val fd = openTun(config)
            tunFd = fd
            if (!desiredRunning.get()) {
                lifecycleLog("I", "start cancelled before TUN handoff startId=$startId fd=$fd")
                closeTunFd()
                finishCancelledStart(startId)
                return
            }
            lifecycleLog("I", "handing TUN fd to core startId=$startId fd=$fd")
            // The Go core owns and closes the descriptor after this handoff.
            tunFd = -1
            Clashmicore.start(
                config.corePath,
                config.corePathPatch,
                config.corePathPatchFinal,
                config.baseDir,
                fd.toLong(),
                config.externalController,
                config.secret,
            )
            coreRunning = true
            if (!desiredRunning.get()) {
                lifecycleLog("I", "start completed after stop request; queued stop will clean up startId=$startId")
                return
            }
            lifecycleLog(
                "I",
                "core started startId=$startId controller=${config.externalController} tun=${Clashmicore.tunInfo()}",
            )
            updateState("connected")
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.doneResult())
        } catch (error: Throwable) {
            val message = error.message ?: error.toString()
            desiredRunning.set(false)
            coreRunning = false
            lifecycleLog("E", "core start failed startId=$startId: $message", error)
            writeErrorFile(config, message)
            unregisterNetworkCallback()
            closeTunFd()
            runCatching { Clashmicore.stop() }.onFailure {
                lifecycleLog("W", "native cleanup after start failure failed: ${it.message}", it)
            }
            clearPersistentCoreLogWriter()
            updateState("disconnected")
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(message))
            stopForegroundCompat()
            stopSelfResult(startId)
        }
    }

    private fun requestStop(reason: String, startId: Int?) {
        desiredRunning.set(false)
        ClashmiVpnRuntime.clearPendingStart()
        if (ClashmiVpnRuntime.currentState != "disconnected") {
            updateState("disconnecting")
        }
        lifecycleLog(
            "I",
            "stop requested reason=$reason startId=${startId ?: "none"} coreRunning=$coreRunning",
        )
        if (!enqueueLifecycle("stop") { stopCore(reason, startId, stopService = true) }) {
            ClashmiVpnRuntime.completeStop(
                ClashmiVpnRuntime.errorResult("lifecycle executor rejected stop", isCloseError = true),
            )
        }
    }

    private fun stopCore(reason: String, startId: Int?, stopService: Boolean) {
        val startedAt = System.currentTimeMillis()
        val needsNativeStop = coreRunning ||
            tunFd >= 0 ||
            tunPfd != null ||
            ClashmiVpnRuntime.currentState != "disconnected"
        lifecycleLog("I", "core stopping reason=$reason nativeCleanup=$needsNativeStop")
        unregisterNetworkCallback()
        var stopError: Throwable? = null
        if (needsNativeStop) {
            try {
                // Native shutdown may wait for listeners; never run it on the
                // Android main thread or acknowledge stop before it returns.
                Clashmicore.stop()
            } catch (error: Throwable) {
                stopError = error
                lifecycleLog("E", "core stop failed reason=$reason: ${error.message}", error)
            }
        }
        clearPersistentCoreLogWriter()
        coreRunning = false
        closeTunFd()
        updateState("disconnected")
        val elapsed = System.currentTimeMillis() - startedAt
        if (stopError == null) {
            lifecycleLog("I", "core stopped reason=$reason elapsedMs=$elapsed")
            ClashmiVpnRuntime.completeStop(ClashmiVpnRuntime.doneResult())
        } else {
            ClashmiVpnRuntime.completeStop(
                ClashmiVpnRuntime.errorResult(
                    stopError.message ?: stopError.toString(),
                    isCloseError = true,
                ),
            )
        }
        if (!desiredRunning.get()) {
            stopForegroundCompat()
            if (stopService) {
                if (startId == null) {
                    stopSelf()
                } else {
                    stopSelfResult(startId)
                }
            }
        }
    }

    private fun finishCancelledStart(startId: Int) {
        unregisterNetworkCallback()
        closeTunFd()
        updateState("disconnected")
        if (!desiredRunning.get()) {
            stopForegroundCompat()
            stopSelfResult(startId)
        }
    }

    private fun enqueueLifecycle(label: String, task: () -> Unit): Boolean {
        return try {
            lifecycleExecutor.execute(task)
            true
        } catch (error: RejectedExecutionException) {
            lifecycleLog("E", "lifecycle task rejected label=$label", error)
            false
        }
    }

    private fun openTun(config: PreparedVpnConfig): Int {
        val builder = Builder()
            .setSession(config.name)
            .setMtu(DEFAULT_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_DNS_SERVER)

        val enableIPv6Route = resolveEffectiveIPv6(config)
        if (enableIPv6Route) {
            builder
                .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
                .addRoute("::", 0)
            Log.i(
                TAG,
                "ipv6 route enabled address=$TUN_IPV6_ADDRESS/$TUN_IPV6_PREFIX serviceConfig=${config.enableIPv6}",
            )
        } else {
            Log.i(TAG, "ipv6 route disabled by effective config serviceConfig=${config.enableIPv6}")
        }

        Log.i(
            TAG,
            "own package remains inside vpn route; core outbound sockets are protected individually",
        )

        tunPfd = builder.establish() ?: error("VpnService.Builder.establish returned null")
        val fd = tunPfd!!.detachFd()
        tunPfd = null
        Log.i(TAG, "tun established fd=$fd")
        return fd
    }

    private fun resolveEffectiveIPv6(config: PreparedVpnConfig): Boolean {
        return try {
            val enabled = Clashmicore.effectiveIPv6(
                config.corePath,
                config.corePathPatch,
                config.corePathPatchFinal,
            )
            if (enabled != config.enableIPv6) {
                Log.i(
                    TAG,
                    "ipv6 route config differs from service.json effective=$enabled serviceConfig=${config.enableIPv6}",
                )
            }
            enabled
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "resolve effective ipv6 failed; fallback serviceConfig=${config.enableIPv6}: ${error.message}",
                error,
            )
            config.enableIPv6
        }
    }

    private fun installSocketProtector() {
        Clashmicore.setSocketProtector(
            object : SocketProtector {
                override fun protect(fd: Long): Boolean = protectCoreSocket(fd)
            },
        )
        Log.i(TAG, "socket protector installed")
    }

    private fun installPersistentCoreLogWriter() {
        Clashmicore.setPersistentLogWriter(
            object : PersistentLogWriter {
                override fun write(level: String, message: String) {
                    // Mihomo keeps its own observable logging system. Persist only
                    // the filtered diagnostic events selected by the Go bridge.
                    val normalizedLevel = level.uppercase(Locale.US).takeIf {
                        it in setOf("DEBUG", "INFO", "WARNING", "ERROR")
                    } ?: "UNKNOWN"
                    appendPersistentLog("CORE-$normalizedLevel", message)
                }
            },
        )
        lifecycleLog("I", "persistent core network diagnostics enabled")
    }

    private fun clearPersistentCoreLogWriter() {
        runCatching {
            Clashmicore.clearPersistentLogWriter()
        }.onFailure {
            Log.w(TAG, "clear persistent core log writer failed: ${it.message}", it)
        }
    }

    private fun protectCoreSocket(fd: Long): Boolean {
        if (fd < 0 || fd > Int.MAX_VALUE) {
            Log.w(TAG, "socket protect rejected invalid fd=$fd")
            return false
        }
        val ok = protect(fd.toInt())
        if (!ok) {
            Log.w(TAG, "VpnService.protect returned false fd=$fd")
        }
        return ok
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateAndroidNetworkInfo("network available")
            }

            override fun onLost(network: Network) {
                updateAndroidNetworkInfo("network lost")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateAndroidNetworkInfo("link properties changed")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateAndroidNetworkInfo("network capabilities changed")
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "network callback registered")
        } catch (error: Throwable) {
            Log.w(TAG, "register network callback failed: ${error.message}", error)
        }
    }

    private fun unregisterNetworkCallback() {
        networkSnapshotLogTracker.reset()
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            Log.i(TAG, "network callback unregistered")
        } catch (error: Throwable) {
            Log.w(TAG, "unregister network callback ignored: ${error.message}")
        }
    }

    @Synchronized
    private fun updateAndroidNetworkInfo(reason: String) {
        try {
            val snapshot = buildAndroidNetworkSnapshot(reason) ?: return
            val raw = snapshot.json.toString()
            val changed = networkSnapshotLogTracker.changed(raw)
            Clashmicore.setAndroidNetworkInfo(raw)
            val message =
                "android network info sent reason=$reason default=${snapshot.defaultInterface.ifEmpty { "none" }} " +
                    "interfaces=${snapshot.interfaceCount} changed=$changed"
            if (changed) {
                // Persist transitions, not repeated capability callbacks with the
                // same snapshot, so the overnight timeline remains readable.
                lifecycleLog("I", message)
            } else {
                Log.i(TAG, message)
            }
        } catch (error: Throwable) {
            networkSnapshotLogTracker.reset()
            lifecycleLog("W", "send android network info failed reason=$reason error=${error.message}", error)
        }
    }

    private fun buildAndroidNetworkSnapshot(reason: String): AndroidNetworkSnapshot? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            val interfaceName = linkProperties.interfaceName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@mapNotNull null
            }
            val addresses = JSONArray()
            linkProperties.linkAddresses.forEach { address ->
                addresses.put(address.toString())
            }
            if (addresses.length() == 0) {
                return@mapNotNull null
            }
            val dnsServers = JSONArray()
            linkProperties.dnsServers.forEach { server ->
                dnsServers.put(server.hostAddress)
            }
            val payload = JSONObject()
                .put("name", interfaceName)
                .put("index", interfaceIndex(interfaceName))
                .put("mtu", linkProperties.mtu)
                .put("addresses", addresses)
                .put("dnsServers", dnsServers)
            AndroidNetworkCandidate(
                name = interfaceName,
                payload = payload,
                isActive = network == activeNetwork,
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "no non-vpn internet network info available reason=$reason")
            val payload = JSONObject()
                .put("defaultInterface", "")
                .put("interfaces", JSONArray())
            return AndroidNetworkSnapshot(payload, "", 0)
        }
        val default = candidates.firstOrNull { it.isActive && it.isValidated }
            ?: candidates.firstOrNull { it.isActive }
            ?: candidates.firstOrNull { it.isValidated }
            ?: candidates.first()
        val interfaces = JSONArray()
        candidates.forEach { interfaces.put(it.payload) }
        val payload = JSONObject()
            .put("defaultInterface", default.name)
            .put("interfaces", interfaces)
        return AndroidNetworkSnapshot(payload, default.name, candidates.size)
    }

    private fun interfaceIndex(name: String): Int = runCatching {
        NetworkInterface.getByName(name)?.index ?: 0
    }.getOrElse {
        Log.w(TAG, "lookup interface index failed name=$name error=${it.message}")
        0
    }

    private fun closeTunFd() {
        val fd = tunFd
        tunFd = -1
        if (fd >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(fd).close()
                Log.i(TAG, "tun fd closed fd=$fd")
            } catch (error: Throwable) {
                Log.w(TAG, "close tun fd ignored: ${error.message}")
            }
        }
        try {
            tunPfd?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "close tun pfd ignored: ${error.message}")
        } finally {
            tunPfd = null
        }
    }

    private fun failStart(message: String, startId: Int) {
        desiredRunning.set(false)
        lifecycleLog("E", "$message startId=$startId")
        updateState("disconnected")
        ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(message))
        stopForegroundCompat()
        stopSelfResult(startId)
    }

    private fun updateState(state: String, params: Map<String, String> = emptyMap()) {
        ClashmiVpnRuntime.updateState(state, params)
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
        params.forEach { (key, value) -> intent.putExtra(key, value) }
        sendBroadcast(intent)
        Log.i(TAG, "state broadcast state=$state")
    }

    private fun restorePreparedConfig(): PreparedVpnConfig? {
        val configFile = File(filesDir, SERVICE_CONFIG_FILE_NAME)
        return runCatching {
            PreparedVpnConfig.fromConfigFile(configFile)?.also {
                ClashmiVpnRuntime.setPreparedConfig(it)
                Log.i(TAG, "prepared config restored from ${configFile.absolutePath}")
            }
        }.getOrElse {
            Log.w(TAG, "restore prepared config failed path=${configFile.absolutePath}: ${it.message}", it)
            null
        }
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(CHANNEL_ID, "Clash Mi VPN", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_sys_download_done
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(icon)
            .setContentTitle("Clash Mi")
            .setContentText("VPN is running")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun clearErrorFile(config: PreparedVpnConfig) {
        if (config.errorPath.isNotEmpty()) {
            runCatching { File(config.errorPath).delete() }
        }
    }

    private fun writeErrorFile(config: PreparedVpnConfig?, message: String) {
        val errorPath = config?.errorPath.orEmpty()
        if (errorPath.isEmpty()) {
            return
        }
        runCatching {
            File(errorPath).writeText(message)
        }.onFailure {
            Log.w(TAG, "write error file failed: ${it.message}")
        }
    }

    private fun lifecycleLog(level: String, message: String, error: Throwable? = null) {
        when (level) {
            "E" -> Log.e(TAG, message, error)
            "W" -> Log.w(TAG, message, error)
            else -> Log.i(TAG, message)
        }
        val stack = error?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
        appendPersistentLog(level, "$message$stack")
    }

    private fun appendPersistentLog(level: String, message: String) {
        val logPath = ClashmiVpnRuntime.preparedConfig?.logPath.orEmpty()
        if (logPath.isEmpty()) {
            return
        }
        // Logcat is volatile on production devices. Keep lifecycle evidence in
        // the shared rotating log so intermittent tile/stop races are inspectable.
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val rotated = RotatingLogWriter.append(
                File(logPath),
                "$timestamp [$level] [${Thread.currentThread().name}] $message\n",
            )
            if (rotated) {
                Log.i(
                    TAG,
                    "persistent log rotated path=$logPath maxBytes=${RotatingLogWriter.MAX_FILE_BYTES} maxFiles=${RotatingLogWriter.MAX_FILE_COUNT}",
                )
            }
        }.onFailure {
            Log.w(TAG, "append persistent log failed path=$logPath: ${it.message}")
        }
    }

    companion object {
        const val ACTION_START = "com.cyenx.clashmi.clashmi_vpn_service.START"
        const val ACTION_STOP = "com.cyenx.clashmi.clashmi_vpn_service.STOP"
        const val ACTION_STATE_CHANGED = "com.cyenx.clashmi.clashmi_vpn_service.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        private const val TAG = "ClashMiVpnService"
        private const val SERVICE_CONFIG_FILE_NAME = "service.json"
        private const val CHANNEL_ID = "clashmi_vpn"
        private const val NOTIFICATION_ID = 6210
        private const val DEFAULT_MTU = 4064
        private const val TUN_IPV4_ADDRESS = "172.19.0.1"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_IPV6_ADDRESS = "fdfe:dcbe:9876::1"
        private const val TUN_IPV6_PREFIX = 126
        private const val TUN_DNS_SERVER = "172.19.0.2"
    }

    private data class AndroidNetworkCandidate(
        val name: String,
        val payload: JSONObject,
        val isActive: Boolean,
        val isValidated: Boolean,
    )

    private data class AndroidNetworkSnapshot(
        val json: JSONObject,
        val defaultInterface: String,
        val interfaceCount: Int,
    )
}
