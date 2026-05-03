package com.cyenx.clashmi.clashmi_vpn_service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cyenx.clashmi.core.clashmicore.Clashmicore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class ClashMiVpnService : VpnService() {
    private val stopping = AtomicBoolean(false)
    private var tunFd: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCore("stop action")
            ACTION_START, null -> startCore()
            else -> Log.w(TAG, "unknown action=${intent.action}")
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopCore("service destroy")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "vpn revoked")
        stopCore("vpn revoked")
        super.onRevoke()
    }

    private fun startCore() {
        if (worker?.isAlive == true) {
            Log.i(TAG, "core start ignored: worker already alive")
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.doneResult())
            return
        }
        stopping.set(false)
        startForegroundService()
        worker = Thread({
            val config = ClashmiVpnRuntime.preparedConfig
            if (config == null) {
                failStart("missing prepared config")
                return@Thread
            }
            try {
                Log.i(TAG, "core starting config=${config.corePath} patch=${config.corePathPatch} finalPatch=${config.corePathPatchFinal}")
                ClashmiVpnRuntime.updateState("connecting")
                clearErrorFile(config)
                val fd = openTun(config)
                tunFd = fd
                Log.i(TAG, "handing tun fd to core fd=$fd")
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
                Log.i(TAG, "core started fd=$fd controller=${config.externalController} tun=${Clashmicore.tunInfo()}")
                ClashmiVpnRuntime.updateState("connected")
                ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.doneResult())
            } catch (error: Throwable) {
                val message = error.message ?: error.toString()
                Log.e(TAG, "core start failed: $message", error)
                writeErrorFile(config, message)
                closeTunFd()
                Clashmicore.stop()
                ClashmiVpnRuntime.updateState("disconnected")
                ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(message))
                stopSelf()
            }
        }, "ClashMiVpnCore")
        worker?.start()
    }

    private fun stopCore(reason: String) {
        if (!stopping.compareAndSet(false, true)) {
            return
        }
        Log.i(TAG, "core stopping reason=$reason")
        ClashmiVpnRuntime.updateState("disconnecting")
        try {
            Clashmicore.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "core stop failed: ${error.message}", error)
        }
        closeTunFd()
        ClashmiVpnRuntime.updateState("disconnected")
        stopForegroundCompat()
        stopSelf()
    }

    private fun openTun(config: PreparedVpnConfig): Int {
        val builder = Builder()
            .setSession(config.name)
            .setMtu(DEFAULT_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_DNS_SERVER)

        try {
            builder.addDisallowedApplication(packageName)
            Log.i(TAG, "excluded own package from vpn route: $packageName")
        } catch (error: Throwable) {
            Log.w(TAG, "exclude own package failed: ${error.message}", error)
        }

        tunPfd = builder.establish() ?: error("VpnService.Builder.establish returned null")
        val fd = tunPfd!!.detachFd()
        tunPfd = null
        Log.i(TAG, "tun established fd=$fd")
        return fd
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

    private fun failStart(message: String) {
        Log.e(TAG, message)
        ClashmiVpnRuntime.updateState("disconnected")
        ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(message))
        stopSelf()
    }

    private fun startForegroundService() {
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

    companion object {
        const val ACTION_START = "com.cyenx.clashmi.clashmi_vpn_service.START"
        const val ACTION_STOP = "com.cyenx.clashmi.clashmi_vpn_service.STOP"
        private const val TAG = "ClashMiVpnService"
        private const val CHANNEL_ID = "clashmi_vpn"
        private const val NOTIFICATION_ID = 6210
        private const val DEFAULT_MTU = 4064
        private const val TUN_IPV4_ADDRESS = "172.19.0.1"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_DNS_SERVER = "172.19.0.2"
    }
}
