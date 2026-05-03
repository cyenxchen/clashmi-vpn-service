package com.cyenx.clashmi.clashmi_vpn_service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cyenx.clashmi.core.clashmicore.Clashmicore
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** ClashmiVpnServicePlugin */
class ClashmiVpnServicePlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {
    private val tag = "ClashmiVpnService"
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermissionStart = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "clashmi_vpn_service")
        channel.setMethodCallHandler(this)
        ClashmiVpnRuntime.setStateEmitter { state, params ->
            mainHandler.post {
                channel.invokeMethod(
                    "stateChanged",
                    mapOf(
                        "state" to state,
                        "params" to params,
                    ),
                )
            }
        }
        Log.i(tag, "plugin attached")
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        Log.i(tag, "method=${call.method}")
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "getABIs" -> result.success(Build.SUPPORTED_ABIS.joinToString(prefix = "[", postfix = "]"))
            "getSystemVersion" -> result.success(Build.VERSION.SDK_INT.toString())
            "getAppGroupDirectory" -> result.success(context.filesDir.absolutePath)
            "currentState" -> result.success(ClashmiVpnRuntime.currentState)
            "prepareConfig" -> {
                try {
                    val args = call.arguments as? Map<*, *> ?: error("missing prepareConfig arguments")
                    val config = PreparedVpnConfig.fromMethodArguments(args)
                    ClashmiVpnRuntime.setPreparedConfig(config)
                    Log.i(tag, "prepareConfig ok core=${config.corePath} finalPatch=${config.corePathPatchFinal} controller=${config.externalController}")
                    result.success(null)
                } catch (error: Throwable) {
                    Log.e(tag, "prepareConfig failed: ${error.message}", error)
                    result.error("prepareConfig", error.message, null)
                }
            }
            "start" -> start(result, timeoutMillis(call))
            "restart" -> {
                stopService()
                start(result, timeoutMillis(call))
            }
            "stop" -> {
                stopService()
                result.success(null)
            }
            "clashiApiTraffic" -> result.success(Clashmicore.traffic())
            "clashiApiConnections" ->
                result.success(Clashmicore.connections(connectionListEnabled(call)))
            "installService",
            "uninstallService",
            "authorizeService" -> result.success(null)
            "isRunAsAdmin",
            "isServiceAuthorized",
            "getSystemProxyEnable",
            "autoStartIsActive" -> result.success(false)
            "setExcludeFromRecents" -> result.success(null)
            "setAlwaysOn",
            "setSystemProxy",
            "cleanSystemProxy",
            "firewallAddApp",
            "firewallAddPorts",
            "autoStartCreate",
            "autoStartDelete",
            "hideDockIcon",
            "proxy.setExcludeDevices" -> result.success(null)
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        ClashmiVpnRuntime.setStateEmitter(null)
        Log.i(tag, "plugin detached")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        activity = binding.activity
        binding.addActivityResultListener(this)
        Log.i(tag, "attached to activity")
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
        Log.i(tag, "detached from activity")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_VPN_PERMISSION) {
            return false
        }
        if (!pendingPermissionStart) {
            return true
        }
        pendingPermissionStart = false
        if (resultCode == Activity.RESULT_OK) {
            Log.i(tag, "vpn permission granted")
            startVpnService()
        } else {
            Log.w(tag, "vpn permission denied resultCode=$resultCode")
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult("VPN permission denied"))
        }
        return true
    }

    private fun start(result: Result, timeoutMillis: Long) {
        if (ClashmiVpnRuntime.preparedConfig == null) {
            result.success(ClashmiVpnRuntime.errorResult("prepareConfig must be called before start"))
            return
        }
        if (!ClashmiVpnRuntime.beginStart { waitResult ->
                mainHandler.post { result.success(waitResult) }
            }) {
            result.success(ClashmiVpnRuntime.errorResult("VPN start already pending"))
            return
        }

        val timeout = if (timeoutMillis > 0) timeoutMillis else DEFAULT_TIMEOUT_MILLIS
        mainHandler.postDelayed({
            if (ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.timeoutResult("service start timeout"))) {
                Log.w(tag, "vpn start timeout")
                stopService()
            }
        }, timeout)

        val permissionIntent = VpnService.prepare(activity ?: context)
        if (permissionIntent != null) {
            val currentActivity = activity
            if (currentActivity == null) {
                ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult("VPN permission requires an Activity"))
                return
            }
            pendingPermissionStart = true
            Log.i(tag, "requesting vpn permission")
            currentActivity.startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
            return
        }
        startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(context, ClashMiVpnService::class.java).setAction(ClashMiVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(tag, "start service intent sent")
        } catch (error: Throwable) {
            Log.e(tag, "start service failed: ${error.message}", error)
            ClashmiVpnRuntime.completeStart(ClashmiVpnRuntime.errorResult(error.message ?: error.toString()))
        }
    }

    private fun stopService() {
        val intent = Intent(context, ClashMiVpnService::class.java).setAction(ClashMiVpnService.ACTION_STOP)
        try {
            context.startService(intent)
        } catch (error: Throwable) {
            Log.w(tag, "stop service intent failed: ${error.message}", error)
            Clashmicore.stop()
            ClashmiVpnRuntime.updateState("disconnected")
        }
    }

    private fun timeoutMillis(call: MethodCall): Long {
        val args = call.arguments as? Map<*, *> ?: return DEFAULT_TIMEOUT_MILLIS
        return (args["timeoutMillis"] as? Number)?.toLong()
            ?: args["timeoutMillis"]?.toString()?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_MILLIS
    }

    private fun connectionListEnabled(call: MethodCall): Boolean {
        val args = call.arguments as? Map<*, *> ?: return false
        return args["withConnectionsList"] == true
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 6211
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
