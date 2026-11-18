package com.sentinelcam.app.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

@CapacitorPlugin(
    name = "SentinelNative",
    permissions = [
        Permission(
            alias = "cameraAndMic",
            strings = [
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ]
        ),
        Permission(
            alias = "notifications",
            strings = [
                "android.permission.POST_NOTIFICATIONS"
            ]
        )
    ]
)
class SentinelNativePlugin : Plugin() {

    private lateinit var prefs: PortalPreferences

    private val serviceStateListener: () -> Unit = {
        activity?.runOnUiThread {
            notifyListeners("portalStateChanged", buildPortalStateObject())
        }
    }

    override fun load() {
        super.load()
        prefs = PortalPreferences(context)
        CameraForegroundService.addStateListener(serviceStateListener)
    }

    override fun handleOnDestroy() {
        CameraForegroundService.removeStateListener(serviceStateListener)
        super.handleOnDestroy()
    }

    @PluginMethod
    fun getPortalState(call: PluginCall) {
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestAllPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionForAliases(
                arrayOf("cameraAndMic", "notifications"),
                call,
                "permissionsCallback"
            )
        } else {
            requestPermissionForAlias("cameraAndMic", call, "permissionsCallback")
        }
    }

    @PermissionCallback
    fun permissionsCallback(call: PluginCall) {
        call.resolve(buildPermissionStatusObject())
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    fun requestBatteryExemption(call: PluginCall) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val pkg = context.packageName
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {
                }
            }
        }
        val ret = JSObject().apply {
            put("batteryOptimizationIgnored", pm.isIgnoringBatteryOptimizations(pkg))
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun completeOnboardingAndStart(call: PluginCall) {
        val autoBoot = call.getBoolean("autoStartOnBoot", true) ?: true
        prefs.autoStartOnBoot = autoBoot
        prefs.onboardingCompleted = true
        prefs.userManuallyStopped = false
        prefs.appendLog("security", "Consentement initial validé — activation du portail continu.")
        CameraForegroundService.startPortal(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun startPortalService(call: PluginCall) {
        prefs.userManuallyStopped = false
        CameraForegroundService.startPortal(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun stopPortalService(call: PluginCall) {
        prefs.userManuallyStopped = true
        CameraForegroundService.stopPortal(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun regeneratePairingCode(call: PluginCall) {
        prefs.regeneratePairingCode()
        if (CameraForegroundService.isRunning) {
            CameraForegroundService.reloadSignaling(context)
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun resetPairedController(call: PluginCall) {
        prefs.resetPairingOnly()
        if (CameraForegroundService.isRunning) {
            CameraForegroundService.reloadSignaling(context)
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun setAutoStartOnBoot(call: PluginCall) {
        val enabled = call.getBoolean("enabled", true) ?: true
        prefs.autoStartOnBoot = enabled
        call.resolve(buildPortalStateObject())
    }

    private fun buildPermissionStatusObject(): JSObject {
        val camGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)

        return JSObject().apply {
            put("camera", camGranted)
            put("microphone", micGranted)
            put("notifications", notifGranted)
            put("batteryOptimizationIgnored", batteryIgnored)
        }
    }

    private fun buildPortalStateObject(): JSObject {
        val running = CameraForegroundService.isRunning
        val streaming = CameraForegroundService.isStreaming
        val lockoutRem = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)

        val portalState = when {
            !running -> "stopped"
            lockoutRem > 0L -> "locked"
            streaming -> "streaming"
            else -> "standby"
        }

        val batIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val logsArray = JSArray()
        for (item in prefs.getLogs()) {
            logsArray.put(
                JSObject().apply {
                    put("timestamp", item.timestamp)
                    put("level", item.level)
                    put("message", item.message)
                }
            )
        }

        return JSObject().apply {
            put("onboardingCompleted", prefs.onboardingCompleted)
            put("serviceRunning", running)
            put("portalState", portalState)
            put("pairingCode", prefs.pairingCode)
            put("deviceId", prefs.deviceId)
            put("deviceName", prefs.deviceName)
            put("paired", !prefs.pairedKeyHex.isNullOrEmpty())
            put("pairedTokenId", prefs.pairedTokenId)
            put("sasCode", prefs.sasCode)
            put("sasSymbols", prefs.sasSymbols)
            put("mqttConnected", CameraForegroundService.mqttConnected)
            put("activeBroker", CameraForegroundService.activeBrokerLabel)
            put("batteryLevel", batteryPct)
            put("isCharging", isCharging)
            put("cameraFacing", CameraForegroundService.currentFacing)
            put("micEnabled", CameraForegroundService.currentMic)
            put("autoStartOnBoot", prefs.autoStartOnBoot)
            put("failedAttempts", prefs.failedAttempts)
            put("lockoutRemainingMs", lockoutRem)
            put("permissions", buildPermissionStatusObject())
            put("logs", logsArray)
        }
    }
}
