package com.cyenx.clashmi.clashmi_vpn_service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal fun forkLocationRequest(sdk: Int, foreground: Boolean, background: Boolean): String = when {
    !foreground -> "foreground"
    sdk < 29 || background -> "none"
    sdk == 29 -> "background"
    else -> "settings"
}

internal class ForkLocationPermissions(private val context: Context, private val activity: () -> Activity?) {
    private var pending: MethodChannel.Result? = null
    private val requestCode = 7312

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun status(): Map<String, Boolean> {
        val foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = foreground && (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        return mapOf("foreground" to foreground, "background" to background)
    }

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        when (call.method) {
            "networkLocationStatus" -> result.success(status())
            "requestNetworkLocationForeground" -> {
                if (status()["foreground"] == true) result.success(status())
                else request(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), result)
            }
            "requestNetworkLocationBackground" -> {
                val state = status()
                when (forkLocationRequest(Build.VERSION.SDK_INT, state["foreground"] == true, state["background"] == true)) {
                    "foreground" -> result.error("foreground_required", "Grant foreground location first", null)
                    "background" -> request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), result)
                    "settings" -> openSettings(result)
                    else -> result.success(status())
                }
            }
            "openNetworkLocationSettings" -> openSettings(result)
            else -> return false
        }
        return true
    }

    private fun openSettings(result: MethodChannel.Result) {
        val current = activity()
        if (current == null) { result.error("activity_required", "Location settings require an Activity", null); return }
        current.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        result.success(status())
    }

    private fun request(permissions: Array<String>, result: MethodChannel.Result) {
        val current = activity()
        if (current == null) { result.error("activity_required", "Location permission requires an Activity", null); return }
        if (pending != null) { result.error("request_pending", "Location permission request already pending", null); return }
        pending = result
        current.requestPermissions(permissions, requestCode)
    }

    fun onResult(code: Int): Boolean {
        if (code != requestCode) return false
        pending?.success(status())
        pending = null
        context.sendBroadcast(Intent("${context.packageName}.NETWORK_LOCATION_CHANGED").setPackage(context.packageName))
        return true
    }

    fun detach() {
        pending?.error("detached", "Plugin detached during location permission request", null)
        pending = null
    }
}
