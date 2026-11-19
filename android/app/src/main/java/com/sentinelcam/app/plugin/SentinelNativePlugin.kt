package com.sentinelcam.app.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONObject

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
        ),
        Permission(
            alias = "contacts",
            strings = [
                Manifest.permission.READ_CONTACTS
            ]
        ),
        Permission(
            alias = "location",
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ]
        )
    ]
)
class SentinelNativePlugin : Plugin() {

    private lateinit var prefs: PortalPreferences

    private val serviceStateListener: () -> Unit = {
        activity?.runOnUiThread {
            try {
                notifyListeners("portalStateChanged", buildPortalStateObject())
            } catch (_: Throwable) {
            }
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionForAliases(
                    arrayOf("cameraAndMic", "notifications"),
                    call,
                    "permissionsCallback"
                )
            } else {
                requestPermissionForAlias("cameraAndMic", call, "permissionsCallback")
            }
        } catch (_: Throwable) {
            call.resolve(buildPermissionStatusObject())
        }
    }

    @PermissionCallback
    fun permissionsCallback(call: PluginCall) {
        call.resolve(buildPermissionStatusObject())
    }

    @PluginMethod
    fun requestContactsPermission(call: PluginCall) {
        try {
            requestPermissionForAlias("contacts", call, "contactsPermissionCallback")
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @PermissionCallback
    fun contactsPermissionCallback(call: PluginCall) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        prefs.globalAllowContacts = granted
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestLocationPermission(call: PluginCall) {
        try {
            requestPermissionForAlias("location", call, "locationPermissionCallback")
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @PermissionCallback
    fun locationPermissionCallback(call: PluginCall) {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        prefs.globalAllowLocation = fine || coarse
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestScreenSharePermission(call: PluginCall) {
        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mpm.createScreenCaptureIntent()
            startActivityForResult(call, captureIntent, "screenSharePermissionCallback")
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @ActivityCallback
    fun screenSharePermissionCallback(call: PluginCall?, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            prefs.globalAllowScreenShare = true
            prefs.userManuallyStopped = false
            CameraForegroundService.grantScreenShareConsent(context, result.data!!)
        } else {
            prefs.globalAllowScreenShare = false
            CameraForegroundService.revokeScreenShareConsent()
        }
        call?.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun revokeScreenSharePermission(call: PluginCall) {
        prefs.globalAllowScreenShare = false
        CameraForegroundService.revokeScreenShareConsent()
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun setGlobalShareSettings(call: PluginCall) {
        if (call.data.has("allowScreenShare")) {
            val v = call.getBoolean("allowScreenShare", false) ?: false
            prefs.globalAllowScreenShare = v
            if (!v) CameraForegroundService.revokeScreenShareConsent()
        }
        if (call.data.has("allowContacts")) {
            prefs.globalAllowContacts = call.getBoolean("allowContacts", false) ?: false
        }
        if (call.data.has("allowLocation")) {
            prefs.globalAllowLocation = call.getBoolean("allowLocation", false) ?: false
        }
        if (call.data.has("allowDeviceInfo")) {
            prefs.globalAllowDeviceInfo = call.getBoolean("allowDeviceInfo", true) ?: true
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
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
            } catch (_: Throwable) {
                try {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Throwable) {
                }
            }
        }
        val ret = JSObject().apply {
            put("batteryOptimizationIgnored", pm.isIgnoringBatteryOptimizations(pkg))
        }
        call.resolve(ret)
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    fun completeOnboardingAndStart(call: PluginCall) {
        val autoBoot = call.getBoolean("autoStartOnBoot", true) ?: true
        prefs.autoStartOnBoot = autoBoot
        prefs.onboardingCompleted = true
        prefs.userManuallyStopped = false
        prefs.appendLog("security", "Portail continu activé.")

        CameraForegroundService.startFromUi(context)

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = context.packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Throwable) {
        }

        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun startPortalService(call: PluginCall) {
        prefs.onboardingCompleted = true
        prefs.userManuallyStopped = false
        CameraForegroundService.startFromUi(context)
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
        CameraForegroundService.reloadSignaling(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun resetPairedController(call: PluginCall) {
        prefs.resetPairingOnly()
        CameraForegroundService.reloadSignaling(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun setAutoStartOnBoot(call: PluginCall) {
        val enabled = call.getBoolean("enabled", true) ?: true
        prefs.autoStartOnBoot = enabled
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun upsertAccessProfile(call: PluginCall) {
        try {
            val id = call.getString("id")
            val name = call.getString("name") ?: ""
            val role = call.getString("role") ?: "guest"
            val code = call.getString("code") ?: ""
            val enabled = call.getBoolean("enabled", true) ?: true
            val expiresAt: Long? = if (call.data.isNull("expiresAt")) {
                null
            } else {
                call.data.optLong("expiresAt", -1L).takeIf { it > 0L }
            }
            val allowAudio = call.getBoolean("allowAudio", true) ?: true
            val allowSwitchCamera = call.getBoolean("allowSwitchCamera", true) ?: true
            val allowScreenShare = call.getBoolean("allowScreenShare", false) ?: false
            val allowContacts = call.getBoolean("allowContacts", false) ?: false
            val allowLocation = call.getBoolean("allowLocation", false) ?: false
            val allowDeviceInfo = call.getBoolean("allowDeviceInfo", true) ?: true

            prefs.upsertAccessProfile(
                id = id,
                name = name,
                role = role,
                rawCode = code,
                enabled = enabled,
                expiresAt = expiresAt,
                allowAudio = allowAudio,
                allowSwitchCamera = allowSwitchCamera,
                allowScreenShare = allowScreenShare,
                allowContacts = allowContacts,
                allowLocation = allowLocation,
                allowDeviceInfo = allowDeviceInfo
            )
            CameraForegroundService.syncAccessChannels(context)
            call.resolve(buildPortalStateObject())
        } catch (e: IllegalArgumentException) {
            call.reject(e.message ?: "Paramètres invalides")
        } catch (t: Throwable) {
            call.reject(t.message ?: "Erreur enregistrement profil")
        }
    }

    @PluginMethod
    fun toggleAccessProfile(call: PluginCall) {
        val id = call.getString("id")
        val enabled = call.getBoolean("enabled", true) ?: true
        if (id.isNullOrEmpty()) {
            call.reject("Identifiant de profil manquant")
            return
        }
        prefs.toggleAccessProfile(id, enabled)
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun deleteAccessProfile(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrEmpty()) {
            call.reject("Identifiant de profil manquant")
            return
        }
        prefs.deleteAccessProfile(id)
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun kickActiveViewer(call: PluginCall) {
        CameraForegroundService.kickActiveViewer()
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

        val contactsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)

        return JSObject().apply {
            put("camera", camGranted)
            put("microphone", micGranted)
            put("notifications", notifGranted)
            put("batteryOptimizationIgnored", batteryIgnored)
            put("contacts", contactsGranted)
            put("location", locationGranted)
            put("screenShareReady", CameraForegroundService.hasActiveScreenProjectionConsent())
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

        val batIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
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

        val profilesArray = JSArray()
        for (p in prefs.getAccessProfiles()) {
            profilesArray.put(
                JSObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("role", p.role)
                    put("code", p.code)
                    put("enabled", p.enabled)
                    put("createdAt", p.createdAt)
                    put("expiresAt", p.expiresAt ?: JSONObject.NULL)
                    put("allowAudio", p.allowAudio)
                    put("allowSwitchCamera", p.allowSwitchCamera)
                    put("allowScreenShare", p.allowScreenShare)
                    put("allowContacts", p.allowContacts)
                    put("allowLocation", p.allowLocation)
                    put("allowDeviceInfo", p.allowDeviceInfo)
                    put("lastAccessAt", p.lastAccessAt ?: JSONObject.NULL)
                }
            )
        }

        val globalSharesObj = JSObject().apply {
            put("allowScreenShare", prefs.globalAllowScreenShare)
            put("allowContacts", prefs.globalAllowContacts)
            put("allowLocation", prefs.globalAllowLocation)
            put("allowDeviceInfo", prefs.globalAllowDeviceInfo)
            put("screenShareReady", CameraForegroundService.hasActiveScreenProjectionConsent())
        }

        return JSObject().apply {
            put("onboardingCompleted", prefs.onboardingCompleted)
            put("serviceRunning", running)
            put("portalState", portalState)
            put("pairingCode", prefs.pairingCode)
            put("deviceId", prefs.deviceId)
            put("deviceName", prefs.deviceName)
            put("paired", prefs.hasAnyAuthorizedToken())
            put("pairedTokenId", prefs.pairedTokenId)
            put("sasCode", prefs.sasCode)
            put("sasSymbols", prefs.sasSymbols)
            put("mqttConnected", CameraForegroundService.mqttConnected)
            put("activeBroker", CameraForegroundService.activeBrokerLabel)
            put("batteryLevel", batteryPct)
            put("isCharging", isCharging)
            put("cameraFacing", CameraForegroundService.currentFacing)
            put("mediaMode", CameraForegroundService.currentMediaMode)
            put("micEnabled", CameraForegroundService.currentMic)
            put("autoStartOnBoot", prefs.autoStartOnBoot)
            put("failedAttempts", prefs.failedAttempts)
            put("lockoutRemainingMs", lockoutRem)
            put("permissions", buildPermissionStatusObject())
            put("globalShares", globalSharesObj)
            put("logs", logsArray)
            put("accessProfiles", profilesArray)
            put("activeViewerName", CameraForegroundService.activeViewerName ?: JSONObject.NULL)
            put("activeViewerRole", CameraForegroundService.activeViewerRole ?: JSONObject.NULL)
            put("activeViewerExpiresAt", CameraForegroundService.activeViewerExpiresAt ?: JSONObject.NULL)
        }
    }
}
