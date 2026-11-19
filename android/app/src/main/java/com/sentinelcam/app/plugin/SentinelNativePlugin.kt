package com.sentinelcam.app.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
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
import com.sentinelcam.app.control.ScreenTimeHelper
import com.sentinelcam.app.control.SentinelNotificationService
import com.sentinelcam.app.control.SystemControlHelper
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.crypto.KeystoreVault
import com.sentinelcam.app.data.CallLogHelper
import com.sentinelcam.app.data.GalleryHelper
import com.sentinelcam.app.receiver.SentinelDeviceAdminReceiver
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
        ),
        Permission(
            alias = "backgroundLocation",
            strings = [
                "android.permission.ACCESS_BACKGROUND_LOCATION"
            ]
        ),
        Permission(
            alias = "sms",
            strings = [
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS
            ]
        ),
        Permission(
            alias = "callLog",
            strings = [
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS
            ]
        ),
        Permission(
            alias = "galleryModern",
            strings = [
                "android.permission.READ_MEDIA_IMAGES"
            ]
        ),
        Permission(
            alias = "galleryLegacy",
            strings = [
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
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
        if ((fine || coarse) && CameraForegroundService.isRunning) {
            CameraForegroundService.startFromUi(context)
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestBackgroundLocationPermission(call: PluginCall) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissionForAlias("backgroundLocation", call, "backgroundLocationCallback")
            } else {
                call.resolve(buildPortalStateObject())
            }
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @PermissionCallback
    fun backgroundLocationCallback(call: PluginCall) {
        if (CameraForegroundService.isRunning) {
            CameraForegroundService.startFromUi(context)
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun clearLocationHistory(call: PluginCall) {
        CameraForegroundService.clearLocationHistory(context)
        call.resolve(buildPortalStateObject())
    }

    private fun grantTemporarySettingsGrace(durationMs: Long = 120_000L) {
        val target = System.currentTimeMillis() + durationMs
        if (prefs.settingsUnlockUntilMs < target) {
            prefs.settingsUnlockUntilMs = target
        }
    }

    @PluginMethod
    fun openAppPermissionsSettings(call: PluginCall) {
        grantTemporarySettingsGrace(120_000L)
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun openManufacturerBatterySettings(call: PluginCall) {
        grantTemporarySettingsGrace(150_000L)
        val brand = "${Build.MANUFACTURER.orEmpty()} ${Build.BRAND.orEmpty()}".lowercase()
        val candidates = ArrayList<Intent>()

        when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")))
            }

            brand.contains("samsung") -> {
                candidates.add(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }

            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> {
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")))
            }

            brand.contains("huawei") || brand.contains("honor") -> {
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
            }

            brand.contains("vivo") || brand.contains("iqoo") -> {
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
            }
        }

        // Universal Android fallback: App Details -> Batterie -> Non restreinte
        candidates.add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )

        var opened = false
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    opened = true
                    break
                }
            } catch (_: Throwable) {
            }
        }
        if (!opened) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Throwable) {
            }
        }
        call.resolve(buildPortalStateObject())
    }

    private fun getFormattedManufacturer(): String {
        val raw = Build.MANUFACTURER?.trim().orEmpty()
        return if (raw.isEmpty()) "Android" else raw.replaceFirstChar { it.uppercase() }
    }

    private fun getManufacturerBatteryTip(): String {
        val brand = "${Build.MANUFACTURER.orEmpty()} ${Build.BRAND.orEmpty()}".lowercase()
        return when {
            brand.contains("samsung") ->
                "Ouvrez Batterie → choisissez « Non restreinte » pour empêcher la mise en veille la nuit."
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "Activez « Démarrage automatique » et réglez l’économiseur sur « Aucune restriction »."
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "Activez « Autoriser le démarrage automatique » et « Activité en arrière-plan »."
            brand.contains("huawei") || brand.contains("honor") ->
                "Passez la gestion de lancement en mode manuel (tout activer en arrière-plan)."
            brand.contains("vivo") || brand.contains("iqoo") ->
                "Autorisez l’exécution en arrière-plan et la consommation élevée pour SentinelCam."
            else ->
                "Ouvrez Batterie → choisissez « Non restreinte » pour une protection continue 24h/24."
        }
    }

    @PluginMethod
    fun requestSmsPermission(call: PluginCall) {
        try {
            grantTemporarySettingsGrace(90_000L)
            requestPermissionForAlias("sms", call, "smsPermissionCallback")
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @PluginMethod
    fun requestCallLogPermission(call: PluginCall) {
        try {
            grantTemporarySettingsGrace(90_000L)
            requestPermissionForAlias("callLog", call, "callLogPermissionCallback")
        } catch (_: Throwable) {
            call.resolve(buildPortalStateObject())
        }
    }

    @PermissionCallback
    fun callLogPermissionCallback(call: PluginCall) {
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestCallScreeningRole(call: PluginCall) {
        grantTemporarySettingsGrace(120_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                    !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
                ) {
                    val intent = rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    startActivityForResult(call, intent, "callScreeningRoleCallback")
                    return
                }
            } catch (_: Throwable) {
            }
        }
        call.resolve(buildPortalStateObject())
    }

    @ActivityCallback
    fun callScreeningRoleCallback(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        CameraForegroundService.syncAccessChannels(context)
        call?.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestWriteSettingsPermission(call: PluginCall) {
        grantTemporarySettingsGrace(120_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
            }
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestNotificationListenerPermission(call: PluginCall) {
        grantTemporarySettingsGrace(150_000L)
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestUsageAccessPermission(call: PluginCall) {
        grantTemporarySettingsGrace(120_000L)
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestOverlayPermission(call: PluginCall) {
        grantTemporarySettingsGrace(120_000L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Throwable) {
        }
        call.resolve(buildPortalStateObject())
    }

    @PermissionCallback
    fun smsPermissionCallback(call: PluginCall) {
        val sendGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        prefs.globalAllowSmsRelay = sendGranted
        if (sendGranted || readGranted) {
            prefs.appendLog("security", "Autorisation SMS mise à jour.")
        }
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
        if (prefs.parentManaged) {
            call.reject("Géré à distance par le Parent.")
            return
        }
        prefs.globalAllowScreenShare = false
        CameraForegroundService.revokeScreenShareConsent()
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun setGlobalShareSettings(call: PluginCall) {
        if (prefs.parentManaged) {
            call.reject("Géré à distance par le Parent.")
            return
        }
        if (call.data.has("allowScreenShare")) {
            val v = call.getBoolean("allowScreenShare", false) ?: false
            prefs.globalAllowScreenShare = v
            if (!v) CameraForegroundService.revokeScreenShareConsent()
        }
        if (call.data.has("allowContacts")) {
            prefs.globalAllowContacts = call.getBoolean("allowContacts", false) ?: false
        }
        if (call.data.has("allowLocation")) {
            val enabled = call.getBoolean("allowLocation", false) ?: false
            prefs.globalAllowLocation = enabled
            if (enabled && CameraForegroundService.isRunning) {
                CameraForegroundService.startFromUi(context)
            }
        }
        if (call.data.has("allowLocationHistory")) {
            val enabled = call.getBoolean("allowLocationHistory", true) ?: true
            prefs.globalAllowLocationHistory = enabled
            if (enabled && prefs.globalAllowLocation && CameraForegroundService.isRunning) {
                CameraForegroundService.startFromUi(context)
            }
        }
        if (call.data.has("gpsTrackingMode")) {
            val mode = call.getString("gpsTrackingMode") ?: "eco"
            prefs.gpsTrackingMode = mode
        }
        if (call.data.has("allowDeviceInfo")) {
            prefs.globalAllowDeviceInfo = call.getBoolean("allowDeviceInfo", true) ?: true
        }
        if (call.data.has("allowIntercom")) {
            prefs.globalAllowIntercom = call.getBoolean("allowIntercom", true) ?: true
        }
        if (call.data.has("allowSmsRelay")) {
            val enabled = call.getBoolean("allowSmsRelay", false) ?: false
            prefs.globalAllowSmsRelay = enabled
            prefs.appendLog(
                "security",
                if (enabled) "Envoi SMS activé." else "Envoi SMS désactivé."
            )
        }
        if (call.data.has("allowDeviceControl")) {
            val enabled = call.getBoolean("allowDeviceControl", true) ?: true
            prefs.globalAllowDeviceControl = enabled
        }
        if (call.data.has("allowGallery")) {
            val enabled = call.getBoolean("allowGallery", true) ?: true
            prefs.globalAllowGallery = enabled
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestStorageManagePermission(call: PluginCall) {
        if (GalleryHelper.hasStorageManagePermission(context)) {
            call.resolve(buildPortalStateObject())
            return
        }
        grantTemporarySettingsGrace(120_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Throwable) {
                }
            }
            call.resolve(buildPortalStateObject())
        } else {
            requestPermissionForAlias("galleryLegacy", call, "galleryPermissionCallback")
        }
    }

    @PluginMethod
    fun requestGalleryPermission(call: PluginCall) {
        if (GalleryHelper.hasGalleryPermission(context)) {
            prefs.globalAllowGallery = true
            CameraForegroundService.syncAccessChannels(context)
            call.resolve(buildPortalStateObject())
            return
        }
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "galleryModern" else "galleryLegacy"
        requestPermissionForAlias(alias, call, "galleryPermissionCallback")
    }

    @PermissionCallback
    fun galleryPermissionCallback(call: PluginCall) {
        if (GalleryHelper.hasGalleryPermission(context)) {
            prefs.globalAllowGallery = true
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun pairWithParentCode(call: PluginCall) {
        val raw = call.getString("code") ?: ""
        val clean = CryptoProtocol.normalizePairingCode(raw)
        if (clean.length != 6) {
            call.reject("Code à 6 caractères requis.")
            return
        }
        CameraForegroundService.pairWithParentCode(context, clean)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun requestDeviceAdmin(call: PluginCall) {
        try {
            grantTemporarySettingsGrace(120_000L)
            prefs.adminProtectionEnabled = true
            prefs.userManuallyStopped = false
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = SentinelDeviceAdminReceiver.getComponentName(context)
            if (!dpm.isAdminActive(comp)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Verrouille la protection parentale contre la désinstallation ou l’arrêt sans autorisation du Parent."
                    )
                }
                startActivityForResult(call, intent, "deviceAdminCallback")
            } else {
                call.resolve(buildPortalStateObject())
            }
        } catch (t: Throwable) {
            call.reject(t.message ?: "Impossible d’ouvrir les droits administrateur.")
        }
    }

    @PluginMethod
    fun requestParentUnpair(call: PluginCall) {
        if (!prefs.parentManaged) {
            call.resolve(buildPortalStateObject())
            return
        }
        prefs.unpairRequestPending = true
        prefs.unpairRequestedAt = System.currentTimeMillis()
        prefs.appendSecurityEvent(
            "admin",
            "Demande de dissociation",
            "L’appareil demande l’autorisation du Parent pour être délié."
        )
        CameraForegroundService.notifyUnpairRequestChanged(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun unpairFromParent(call: PluginCall) {
        call.reject("L’autorisation du Parent depuis le site Web est obligatoire pour délier cet appareil.")
    }

    @PluginMethod
    fun setAiShieldSettings(call: PluginCall) {
        if (prefs.parentManaged) {
            call.reject("Géré à distance par le Parent.")
            return
        }
        if (call.data.has("blurFaces")) {
            prefs.aiBlurFaces = call.getBoolean("blurFaces", false) ?: false
        }
        if (call.data.has("blurNsfw")) {
            prefs.aiBlurNsfw = call.getBoolean("blurNsfw", false) ?: false
        }
        if (call.data.has("nsfwStrict")) {
            prefs.aiNsfwStrict = call.getBoolean("nsfwStrict", false) ?: false
        }
        CameraForegroundService.syncAccessChannels(context)
        call.resolve(buildPortalStateObject())
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    fun requestBatteryExemption(call: PluginCall) {
        grantTemporarySettingsGrace(90_000L)
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
        if (prefs.parentManaged) {
            call.reject("Impossible d’arrêter le service sur un appareil géré par le Parent.")
            return
        }
        if (prefs.adminProtectionEnabled && prefs.hasAdminPin()) {
            val pin = call.getString("adminPin") ?: ""
            if (!prefs.verifyAdminPin(pin)) {
                call.reject("Code administrateur requis pour arrêter le service.")
                return
            }
        }
        prefs.userManuallyStopped = true
        CameraForegroundService.stopPortal(context)
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun enableAdminProtection(call: PluginCall) {
        try {
            val pin = call.getString("pin")?.trim().orEmpty()
            if (pin.length < 4 || pin.length > 12) {
                call.reject("Choisissez un code administrateur de 4 à 12 caractères.")
                return
            }
            prefs.setAdminPin(pin)
            prefs.adminProtectionEnabled = true
            prefs.userManuallyStopped = false
            prefs.appendLog("security", "Verrouillage administrateur configuré.")

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = SentinelDeviceAdminReceiver.getComponentName(context)
            if (!dpm.isAdminActive(comp)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Protège le service en arrière-plan contre l’arrêt ou la désinstallation sans code administrateur."
                    )
                }
                startActivityForResult(call, intent, "deviceAdminCallback")
            } else {
                call.resolve(buildPortalStateObject())
            }
        } catch (t: Throwable) {
            call.reject(t.message ?: "Impossible d’activer le mode administrateur.")
        }
    }

    @ActivityCallback
    fun deviceAdminCallback(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        call?.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun verifyAdminPin(call: PluginCall) {
        val pin = call.getString("pin") ?: ""
        val valid = !prefs.hasAdminPin() || prefs.verifyAdminPin(pin)
        call.resolve(JSObject().apply { put("valid", valid) })
    }

    @PluginMethod
    fun disableAdminProtection(call: PluginCall) {
        if (prefs.parentManaged) {
            call.reject("Autorisation du Parent requise depuis le site Web.")
            return
        }
        val pin = call.getString("pin") ?: ""
        if (prefs.hasAdminPin() && !prefs.verifyAdminPin(pin)) {
            call.reject("Code administrateur incorrect.")
            return
        }
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = SentinelDeviceAdminReceiver.getComponentName(context)
            if (dpm.isAdminActive(comp)) {
                dpm.removeActiveAdmin(comp)
            }
        } catch (_: Throwable) {
        }
        prefs.clearAdminPin()
        prefs.appendLog("security", "Protection administrateur désactivée par code.")
        call.resolve(buildPortalStateObject())
    }

    @PluginMethod
    fun updateAdminPin(call: PluginCall) {
        val currentPin = call.getString("currentPin") ?: ""
        val newPin = call.getString("newPin")?.trim().orEmpty()
        if (prefs.hasAdminPin() && !prefs.verifyAdminPin(currentPin)) {
            call.reject("Code actuel incorrect.")
            return
        }
        if (newPin.length < 4 || newPin.length > 12) {
            call.reject("Le nouveau code doit contenir 4 à 12 caractères.")
            return
        }
        prefs.setAdminPin(newPin)
        prefs.appendLog("security", "Code administrateur modifié.")
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
            val allowIntercom = call.getBoolean("allowIntercom", true) ?: true
            val allowSmsRelay = call.getBoolean("allowSmsRelay", false) ?: false
            val allowDeviceControl = call.getBoolean("allowDeviceControl", false) ?: false

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
                allowDeviceInfo = allowDeviceInfo,
                allowIntercom = allowIntercom,
                allowSmsRelay = allowSmsRelay,
                allowDeviceControl = allowDeviceControl
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

        val bgLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            locationGranted
        }

        val smsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readSmsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
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
            put("backgroundLocation", bgLocationGranted)
            put("sms", smsGranted)
            put("readSms", readSmsGranted)
            put("callLog", CallLogHelper.hasCallLogPermission(context))
            put("callScreening", CallLogHelper.hasCallScreeningRole(context))
            put("writeSettings", SystemControlHelper.canWriteSystemSettings(context))
            put("notificationListener", SentinelNotificationService.isNotificationAccessEnabled(context))
            put("gallery", GalleryHelper.hasGalleryPermission(context))
            put("storageManage", GalleryHelper.hasStorageManagePermission(context))
            put("usageAccess", ScreenTimeHelper.hasUsageAccess(context))
            put("overlay", ScreenTimeHelper.hasOverlayPermission(context))
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
                    put("allowIntercom", p.allowIntercom)
                    put("allowSmsRelay", p.allowSmsRelay)
                    put("allowDeviceControl", p.allowDeviceControl)
                    put("lastAccessAt", p.lastAccessAt ?: JSONObject.NULL)
                }
            )
        }

        val globalSharesObj = JSObject().apply {
            put("allowScreenShare", prefs.globalAllowScreenShare)
            put("allowContacts", prefs.globalAllowContacts)
            put("allowLocation", prefs.globalAllowLocation)
            put("allowLocationHistory", prefs.globalAllowLocationHistory)
            put("gpsTrackingMode", prefs.gpsTrackingMode)
            put("allowDeviceInfo", prefs.globalAllowDeviceInfo)
            put("allowIntercom", prefs.globalAllowIntercom)
            put("allowSmsRelay", prefs.globalAllowSmsRelay)
            put("allowDeviceControl", prefs.globalAllowDeviceControl)
            put("allowGallery", prefs.globalAllowGallery)
            put("screenShareReady", CameraForegroundService.hasActiveScreenProjectionConsent())
        }

        return JSObject().apply {
            put("onboardingCompleted", prefs.onboardingCompleted)
            put("serviceRunning", running)
            put("portalState", portalState)
            put("pairingCode", prefs.pairingCode)
            put("deviceId", prefs.deviceId)
            put("deviceName", prefs.deviceName)
            put("deviceManufacturer", getFormattedManufacturer())
            put("manufacturerTip", getManufacturerBatteryTip())
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
            put("intercomActive", CameraForegroundService.intercomActive)
            put("gpsTrackerState", CameraForegroundService.gpsTrackerState)
            put("gpsPointsCount", CameraForegroundService.gpsPointsCount)
            put("gpsTodayPointsCount", CameraForegroundService.gpsTodayPointsCount)
            put("gpsLastFixTs", CameraForegroundService.gpsLastFixTs)
            put("hardwareKeystoreReady", KeystoreVault.isHardwareKeystoreReady())
            put("pfsEnabled", CameraForegroundService.lastSessionPfs)
            put("adminProtectionEnabled", prefs.adminProtectionEnabled)
            put("hasAdminPin", prefs.hasAdminPin())
            put("activeAppLocksCount", prefs.getActiveAppLockRules().size)
            put("screenPausedUntilMs", prefs.screenPausedUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
            put("curfewEnabled", prefs.curfewEnabled)
            put("lostModeEnabled", prefs.lostModeEnabled)
            put("blockSettingsAndStore", prefs.blockSettingsAndStore)
            put("schoolModeUntilMs", prefs.schoolModeUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
            put("securityAlertsCount", prefs.getSecurityEvents().size)
            put("aiBlurFaces", prefs.aiBlurFaces)
            put("aiBlurNsfw", prefs.aiBlurNsfw)
            put("aiNsfwStrict", prefs.aiNsfwStrict)
            put("aiShieldRunning", CameraForegroundService.aiShieldActive)
            put("timelineCaptureEnabled", prefs.timelineCaptureEnabled)
            put("timelineRunning", CameraForegroundService.timelineActive)
            put("parentManaged", prefs.parentManaged)
            put("unpairRequestPending", prefs.unpairRequestPending)
            put("unpairRequestedAt", prefs.unpairRequestedAt)
            put("pairStatus", CameraForegroundService.pairStatus)
            put(
                "deviceAdminActive",
                try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    dpm.isAdminActive(SentinelDeviceAdminReceiver.getComponentName(context))
                } catch (_: Throwable) {
                    false
                }
            )
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
