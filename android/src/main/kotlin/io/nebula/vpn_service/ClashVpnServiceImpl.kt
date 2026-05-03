package io.nebula.vpn_service

import android.content.Intent
import android.net.VpnService
import android.util.Log

class ClashVpnServiceImpl : VpnService() {
    private val tag = "ClashVpnServiceImpl"

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        Log.i(tag, "onStartCommand action=$action")
        when (action) {
            ACTION_START -> sendStartResult("Android VPN start is not implemented yet")
            ACTION_STOP -> {
                sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")
        super.onDestroy()
    }

    private fun sendStartResult(error: String) {
        sendBroadcast(
            Intent(ACTION_START_RESULT)
                .setPackage(packageName)
                .putExtra("err", error)
        )
    }

    companion object {
        private const val ACTION_START = "vpn.service.START"
        private const val ACTION_STOP = "vpn.service.STOP"
        private const val ACTION_STOPPED = "vpn.service.STOPED"
        private const val ACTION_START_RESULT = "vpn.service.START_RESULT"
    }
}
