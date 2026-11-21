package com.sentinelcam.app.control

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.UserManager
import android.provider.Settings
import com.sentinelcam.app.receiver.SentinelDeviceAdminReceiver
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Full Hardware & System Command Center Helper (v23):
 * - Real-time volumes (Media, Ring, Alarm) + Ringer mode (Normal / Vibrate / Silent)
 * - Screen brightness read & write (when WRITE_SETTINGS granted)
 * - Hardware telemetry (Storage, RAM, Battery Temp, Wi-Fi/Network)
 * - Complete installed user apps inventory (including sideloaded / non-Play-Store detection + install date)
 * - Remote app launch & parent-initiated uninstall trigger (with temporary Settings grace)
 */
object SystemControlHelper {

    fun canWriteSystemSettings(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    }

    private fun streamPct(am: AudioManager, stream: Int): Int {
        return try {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val cur = am.getStreamVolume(stream).coerceIn(0, max)
            (cur * 100) / max
        } catch (_: Throwable) {
            0
        }
    }

    fun readSystemSnapshot(context: Context): JSONObject {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val musicPct = streamPct(am, AudioManager.STREAM_MUSIC)
        val ringPct = streamPct(am, AudioManager.STREAM_RING)
        val alarmPct = streamPct(am, AudioManager.STREAM_ALARM)
        val ringerMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }

        val brightnessPct = try {
            val raw = Settings.System.getInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((raw.coerceIn(0, 255) * 100) / 255)
        } catch (_: Throwable) {
            50
        }

        var freeStorageMb = 0L
        var totalStorageMb = 0L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
            totalStorageMb = (stat.blockCountLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Throwable) {
        }

        var freeRamMb = 0L
        var totalRamMb = 0L
        try {
            val actMgr = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)
            freeRamMb = memInfo.availMem / (1024L * 1024L)
            totalRamMb = memInfo.totalMem / (1024L * 1024L)
        } catch (_: Throwable) {
        }

        var batteryTempC = 0.0
        try {
            val bat = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (tenths > 0) batteryTempC = tenths / 10.0
        } catch (_: Throwable) {
        }

        val wifiLabel = readWifiSummary(appCtx)
        val appsArr = readInstalledUserApps(appCtx)
        val mdmState = readMdmControlState(appCtx)

        return JSONObject().apply {
            put("volumeMusic", musicPct)
            put("volumeRing", ringPct)
            put("volumeAlarm", alarmPct)
            put("ringerMode", ringerMode)
            put("brightnessPct", brightnessPct)
            put("canWriteSettings", canWriteSystemSettings(appCtx))
            put("freeStorageMb", freeStorageMb)
            put("totalStorageMb", totalStorageMb)
            put("freeRamMb", freeRamMb)
            put("totalRamMb", totalRamMb)
            put("batteryTempC", batteryTempC)
            put("wifiSummary", wifiLabel)
            put("androidVersion", Build.VERSION.RELEASE ?: "?")
            put("model", "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim())
            put("mdmDeviceOwner", mdmState.optBoolean("deviceOwner", false))
            put("mdmLockWallpaper", mdmState.optBoolean("lockWallpaper", false))
            put("mdmLockLocation", mdmState.optBoolean("lockLocation", false))
            put("mdmMaxScreenTimeoutSec", mdmState.optInt("maxScreenTimeoutSec", 0))
            put("mdmLockDateTime", mdmState.optBoolean("lockDateTime", false))
            put("mdmBlockUnknownSources", mdmState.optBoolean("blockUnknownSources", false))
            put("mdmBlockInstallApps", mdmState.optBoolean("blockInstallApps", false))
            put("mdmBlockUninstallApps", mdmState.optBoolean("blockUninstallApps", false))
            put("mdmBlockSafeBoot", mdmState.optBoolean("blockSafeBoot", false))
            put("mdmBlockFactoryReset", mdmState.optBoolean("blockFactoryReset", false))
            put("mdmBlockUsbDebug", mdmState.optBoolean("blockUsbDebug", false))
            put("mdmBlockScreenshots", mdmState.optBoolean("blockScreenshots", false))
            put("mdmDisableCamera", mdmState.optBoolean("disableCamera", false))
            put("mdmBlockTethering", mdmState.optBoolean("blockTethering", false))
            put("mdmSuspendMessage", getMdmSuspendMessage(appCtx))
            put("drivingSafety", DrivingSafetyController.readStateJson(appCtx))
            put("installedApps", appsArr)
        }
    }

    private fun readWifiSummary(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "Hors ligne"
            val caps = cm.getNetworkCapabilities(net) ?: return "Connecté"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val ssid = wm?.connectionInfo?.ssid?.replace("\"", "")?.takeUnless {
                    it.isBlank() || it == "<unknown ssid>"
                }
                if (ssid != null) "Wi-Fi · $ssid" else "Wi-Fi actif"
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "Données mobiles (4G/5G)"
            } else {
                "Réseau actif"
            }
        } catch (_: Throwable) {
            "Réseau actif"
        }
    }

    private fun readInstalledUserApps(context: Context): JSONArray {
        val pm = context.packageManager
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = SentinelDeviceAdminReceiver.getComponentName(context)
        val isDo = try { dpm?.isDeviceOwnerApp(context.packageName) == true } catch (_: Throwable) { false }
        val hiddenTracked = getTrackedHiddenPackages(context)
        val suspendedTracked = getTrackedSuspendedPackages(context)
        val out = ArrayList<JSONObject>()
        try {
            val flags = if (isDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                0
            }
            val pkgs = pm.getInstalledPackages(flags)
            for (pi in pkgs) {
                val ai = pi.applicationInfo ?: continue
                val pkg = pi.packageName ?: continue
                if (pkg == context.packageName) continue
                val isMdmHidden = isDo && (try {
                    dpm?.isApplicationHidden(admin, pkg) == true
                } catch (_: Throwable) {
                    false
                } || hiddenTracked.contains(pkg))

                val isMdmSuspended = isDo && (try {
                    dpm?.isPackageSuspended(admin, pkg) == true
                } catch (_: Throwable) {
                    false
                } || suspendedTracked.contains(pkg))

                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val hasLauncher = pm.getLaunchIntentForPackage(pkg) != null

                // Include ALL user-installed apps, MDM-hidden apps, and key user-facing system apps
                if (!isMdmHidden && isSystem && !isUpdatedSystem && !hasLauncher) continue

                val label = try {
                    pm.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
                } catch (_: Throwable) {
                    pkg
                }

                val installer = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getInstallSourceInfo(pkg).installingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkg)
                    }
                } catch (_: Throwable) {
                    null
                }

                val isPlayStore = installer == "com.android.vending" ||
                    installer == "com.sec.android.app.samsungapps" ||
                    installer == "com.xiaomi.mipicks" ||
                    installer == "com.huawei.appmarket"
                val sideloaded = !isSystem && !isPlayStore

                out.add(
                    JSONObject().apply {
                        put("packageName", pkg)
                        put("appName", label)
                        put("versionName", pi.versionName ?: "")
                        put("installedAt", pi.firstInstallTime)
                        put("updatedAt", pi.lastUpdateTime)
                        put("isSystem", isSystem)
                        put("sideloaded", sideloaded)
                        put("hasLauncher", hasLauncher)
                        put("mdmHidden", isMdmHidden)
                        put("mdmSuspended", isMdmSuspended)
                    }
                )
            }
        } catch (_: Throwable) {
        }

        // Show MDM-hidden, MDM-suspended, sideloaded, and most recently installed apps first!
        out.sortWith(
            compareByDescending<JSONObject> { it.optBoolean("mdmHidden") || it.optBoolean("mdmSuspended") }
                .thenByDescending { it.optBoolean("sideloaded") }
                .thenByDescending { !it.optBoolean("hasLauncher") && !it.optBoolean("isSystem") }
                .thenByDescending { it.optLong("installedAt") }
                .thenBy { it.optString("appName").lowercase(Locale.FRENCH) }
        )

        val arr = JSONArray()
        for (item in out.take(140)) arr.put(item)
        return arr
    }

    /* ==================== OFFICIAL ANDROID ENTERPRISE MDM CONTROLS (#2 & #17) ==================== */

    private const val MDM_PREFS_NAME = "sentinel_mdm_controls_v1"
    private const val KEY_MDM_HIDDEN_PKGS = "hidden_pkgs"
    private const val KEY_MDM_SUSPENDED_PKGS = "suspended_pkgs"
    private const val KEY_MDM_SUSPEND_MSG = "suspend_message"
    private val PROTECTED_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer"
    )

    fun getTrackedHiddenPackages(context: Context): MutableSet<String> {
        val sp = context.applicationContext.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(sp.getStringSet(KEY_MDM_HIDDEN_PKGS, emptySet()) ?: emptySet())
    }

    private fun saveTrackedHiddenPackages(context: Context, set: Set<String>) {
        val sp = context.applicationContext.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putStringSet(KEY_MDM_HIDDEN_PKGS, HashSet(set)).apply()
    }

    fun getTrackedSuspendedPackages(context: Context): MutableSet<String> {
        val sp = context.applicationContext.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(sp.getStringSet(KEY_MDM_SUSPENDED_PKGS, emptySet()) ?: emptySet())
    }

    private fun saveTrackedSuspendedPackages(context: Context, set: Set<String>) {
        val sp = context.applicationContext.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putStringSet(KEY_MDM_SUSPENDED_PKGS, HashSet(set)).apply()
    }

    fun getMdmSuspendMessage(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_MDM_SUSPEND_MSG, "").orEmpty()
    }

    /**
     * #2 (List 1): Native OS-level App Suspension via DevicePolicyManager.setPackagesSuspended().
     * Greys out the app in Android launcher immediately (0% battery, zero bypass) and stores
     * the Parent's custom message (also displayed via short support message on Android 7+).
     */
    fun setMdmAppSuspended(
        context: Context,
        packageName: String,
        suspended: Boolean,
        customMessage: String? = null
    ): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        if (packageName.isBlank() || packageName == appCtx.packageName) {
            return Pair(false, "Impossible de suspendre SentinelCam.")
        }
        if (PROTECTED_SYSTEM_PACKAGES.contains(packageName)) {
            return Pair(false, "Application système critique non suspendable.")
        }
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) {
            return Pair(false, "Disponible uniquement sur un appareil enrôlé par QR Code Appareil Géré (MDM).")
        }
        return try {
            val cleanMsg = customMessage?.trim()?.take(180)
            if (cleanMsg != null) {
                val sp = appCtx.getSharedPreferences(MDM_PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit().putString(KEY_MDM_SUSPEND_MSG, cleanMsg).apply()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setShortSupportMessage(
                            admin,
                            if (cleanMsg.isNotEmpty()) cleanMsg else null
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended)
            if (failed.isEmpty()) {
                val tracked = getTrackedSuspendedPackages(appCtx)
                if (suspended) tracked.add(packageName) else tracked.remove(packageName)
                saveTrackedSuspendedPackages(appCtx, tracked)
                Pair(
                    true,
                    if (suspended) "Application suspendue au niveau système." else "Application réactivée."
                )
            } else {
                Pair(false, "Android n’a pas pu suspendre cette application.")
            }
        } catch (t: Throwable) {
            Pair(false, "Suspension MDM impossible : ${t.message}")
        }
    }

    fun readMdmControlState(context: Context): JSONObject {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        val isDo = try { dpm?.isDeviceOwnerApp(appCtx.packageName) == true } catch (_: Throwable) { false }
        var lockWallpaper = false
        var lockLocation = false
        var lockDateTime = false
        var blockUnknownSources = false
        var blockInstallApps = false
        var blockUninstallApps = false
        var blockSafeBoot = false
        var blockFactoryReset = false
        var blockUsbDebug = false
        var blockScreenshots = false
        var disableCamera = false
        var blockTethering = false
        var maxLockSec = 0

        if (dpm != null && isDo) {
            try {
                val r = dpm.getUserRestrictions(admin)
                lockWallpaper = r.getBoolean(UserManager.DISALLOW_SET_WALLPAPER, false)
                lockLocation = r.getBoolean(UserManager.DISALLOW_CONFIG_LOCATION, false)
                lockDateTime = r.getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, false)
                blockUnknownSources = r.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false)
                blockInstallApps = r.getBoolean(UserManager.DISALLOW_INSTALL_APPS, false)
                blockUninstallApps = r.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false)
                blockSafeBoot = r.getBoolean(UserManager.DISALLOW_SAFE_BOOT, false)
                blockFactoryReset = r.getBoolean(UserManager.DISALLOW_FACTORY_RESET, false)
                blockUsbDebug = r.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
                blockTethering = r.getBoolean(UserManager.DISALLOW_CONFIG_TETHERING, false)
            } catch (_: Throwable) {
            }
            try { blockScreenshots = dpm.getScreenCaptureDisabled(admin) } catch (_: Throwable) {}
            try { disableCamera = dpm.getCameraDisabled(admin) } catch (_: Throwable) {}
            try {
                val ms = dpm.getMaximumTimeToLock(admin)
                maxLockSec = if (ms > 0L) (ms / 1000L).toInt() else 0
            } catch (_: Throwable) {
            }
        }

        return JSONObject().apply {
            put("deviceOwner", isDo)
            put("lockWallpaper", lockWallpaper)
            put("lockLocation", lockLocation)
            put("lockDateTime", lockDateTime)
            put("blockUnknownSources", blockUnknownSources)
            put("blockInstallApps", blockInstallApps)
            put("blockUninstallApps", blockUninstallApps)
            put("blockSafeBoot", blockSafeBoot)
            put("blockFactoryReset", blockFactoryReset)
            put("blockUsbDebug", blockUsbDebug)
            put("blockScreenshots", blockScreenshots)
            put("disableCamera", disableCamera)
            put("blockTethering", blockTethering)
            put("maxScreenTimeoutSec", maxLockSec)
        }
    }

    /**
     * #2: Temporarily hide or unhide a third-party app via official DevicePolicyManager.setApplicationHidden().
     * Strictly forbids hiding SentinelCam itself or core Android system packages.
     */
    fun setMdmAppHidden(context: Context, packageName: String, hidden: Boolean): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        if (packageName.isBlank() || packageName == appCtx.packageName) {
            return Pair(false, "Impossible de masquer SentinelCam (l'application de supervision reste toujours visible).")
        }
        if (PROTECTED_SYSTEM_PACKAGES.contains(packageName)) {
            return Pair(false, "Application système critique non masquable.")
        }
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) {
            return Pair(false, "Disponible uniquement sur un appareil enrôlé par QR Code Appareil Géré (MDM).")
        }
        return try {
            val ok = dpm.setApplicationHidden(admin, packageName, hidden)
            if (ok) {
                val tracked = getTrackedHiddenPackages(appCtx)
                if (hidden) tracked.add(packageName) else tracked.remove(packageName)
                saveTrackedHiddenPackages(appCtx, tracked)
                Pair(true, if (hidden) "Application masquée temporairement." else "Application réaffichée sur l’appareil.")
            } else {
                Pair(false, "Android a refusé le masquage de cette application.")
            }
        } catch (t: Throwable) {
            Pair(false, "Action MDM impossible : ${t.message}")
        }
    }

    /**
     * #17 + Full Official Android Enterprise MDM Admin Restrictions & Hardware Policies.
     */
    fun setMdmRestriction(context: Context, target: String, enabled: Boolean): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) {
            return Pair(false, "Disponible uniquement sur un appareil enrôlé par QR Code Appareil Géré (MDM).")
        }
        return try {
            when (target) {
                "screenshots" -> {
                    dpm.setScreenCaptureDisabled(admin, enabled)
                    return Pair(true, "Blocage captures d’écran ${if (enabled) "activé" else "désactivé"}.")
                }
                "camera" -> {
                    dpm.setCameraDisabled(admin, enabled)
                    return Pair(true, "Coupure caméra système ${if (enabled) "activée" else "désactivée"}.")
                }
                "usb_debug" -> {
                    if (enabled) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                    } else {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                    }
                    return Pair(true, "Verrouillage USB / PC ${if (enabled) "activé" else "désactivé"}.")
                }
                "date_time" -> {
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try { dpm.setAutoTimeEnabled(admin, true) } catch (_: Throwable) {}
                        }
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                    } else {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                    }
                    return Pair(true, "Verrouillage date & heure ${if (enabled) "activé" else "désactivé"}.")
                }
            }

            val (restrictionKey, label) = when (target) {
                "wallpaper" -> UserManager.DISALLOW_SET_WALLPAPER to "Verrouillage du fond d’écran"
                "location" -> UserManager.DISALLOW_CONFIG_LOCATION to "Verrouillage de la localisation"
                "unknown_sources" -> UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to "Blocage APK externes"
                "install_apps" -> UserManager.DISALLOW_INSTALL_APPS to "Blocage nouvelles installations"
                "uninstall_apps" -> UserManager.DISALLOW_UNINSTALL_APPS to "Blocage désinstallations"
                "safe_boot" -> UserManager.DISALLOW_SAFE_BOOT to "Blocage mode sans échec"
                "factory_reset" -> UserManager.DISALLOW_FACTORY_RESET to "Blocage réinitialisation paramètres"
                "tethering" -> UserManager.DISALLOW_CONFIG_TETHERING to "Blocage partage de connexion"
                else -> return Pair(false, "Restriction inconnue.")
            }
            if (enabled) {
                dpm.addUserRestriction(admin, restrictionKey)
            } else {
                dpm.clearUserRestriction(admin, restrictionKey)
            }
            Pair(true, "$label ${if (enabled) "activé" else "désactivé"}.")
        } catch (t: Throwable) {
            Pair(false, "Erreur MDM : ${t.message}")
        }
    }

    fun mdmRebootDevice(context: Context): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) {
            return Pair(false, "Disponible uniquement en mode Appareil Géré (MDM).")
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot(admin)
                Pair(true, "Redémarrage à distance lancé.")
            } else {
                Pair(false, "Nécessite Android 7.0+.")
            }
        } catch (t: Throwable) {
            Pair(false, "Redémarrage impossible : ${t.message}")
        }
    }

    fun mdmLockNow(context: Context): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isAdminActive(admin)) {
            return Pair(false, "Administration de l’appareil non activée.")
        }
        return try {
            dpm.lockNow()
            Pair(true, "Écran verrouillé instantanément.")
        } catch (t: Throwable) {
            Pair(false, "Verrouillage impossible : ${t.message}")
        }
    }

    /**
     * #17: Set maximum screen lock timeout (seconds; 0 = unlimited).
     */
    fun setMdmMaxScreenTimeout(context: Context, seconds: Int): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return Pair(false, "Gestionnaire MDM indisponible.")
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) {
            return Pair(false, "Disponible uniquement sur un appareil enrôlé par QR Code Appareil Géré (MDM).")
        }
        val clampedSec = if (seconds <= 0) 0 else seconds.coerceIn(15, 600)
        return try {
            dpm.setMaximumTimeToLock(admin, clampedSec.toLong() * 1000L)
            val msg = if (clampedSec == 0) {
                "Délai maximal de mise en veille retiré."
            } else {
                "Mise en veille automatique plafonnée à ${clampedSec} s."
            }
            Pair(true, msg)
        } catch (t: Throwable) {
            Pair(false, "Réglage mise en veille refusé : ${t.message}")
        }
    }

    fun clearAllMdmControlsOnUnpair(context: Context) {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        if (!dpm.isDeviceOwnerApp(appCtx.packageName)) return

        // Restore all temporarily hidden third-party apps
        val tracked = getTrackedHiddenPackages(appCtx)
        for (pkg in tracked) {
            try {
                dpm.setApplicationHidden(admin, pkg, false)
            } catch (_: Throwable) {
            }
        }
        saveTrackedHiddenPackages(appCtx, emptySet())

        // Unsuspend all manually suspended apps + disengage Mode Trajet
        val suspended = getTrackedSuspendedPackages(appCtx)
        if (suspended.isNotEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
            } catch (_: Throwable) {
            }
        }
        saveTrackedSuspendedPackages(appCtx, emptySet())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setShortSupportMessage(admin, null)
            }
        } catch (_: Throwable) {
        }
        DrivingSafetyController.disengageAndResetOnUnpair(appCtx)

        // Release ALL MDM locks cleanly on unpair
        val allRestrictions = listOf(
            UserManager.DISALLOW_SET_WALLPAPER,
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_CONFIG_TETHERING
        )
        for (key in allRestrictions) {
            try { dpm.clearUserRestriction(admin, key) } catch (_: Throwable) {}
        }
        try { dpm.setScreenCaptureDisabled(admin, false) } catch (_: Throwable) {}
        try { dpm.setCameraDisabled(admin, false) } catch (_: Throwable) {}
        try { dpm.setMaximumTimeToLock(admin, 0L) } catch (_: Throwable) {}
    }

    fun setVolumePercent(context: Context, streamName: String, percent: Int): Pair<Boolean, String?> {
        return try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = when (streamName) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val target = ((percent.coerceIn(0, 100) * max) + 50) / 100
            am.setStreamVolume(stream, target.coerceIn(0, max), 0)
            Pair(true, null)
        } catch (t: Throwable) {
            Pair(false, "Réglage volume refusé par le système (${t.message ?: "Ne pas déranger actif"}). ")
        }
    }

    fun setRingerMode(context: Context, mode: String): Pair<Boolean, String?> {
        return try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val target = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            am.ringerMode = target
            Pair(true, null)
        } catch (_: Throwable) {
            Pair(false, "Impossible de passer en mode silencieux (restriction système).")
        }
    }

    fun setBrightnessPercent(context: Context, percent: Int): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        if (!canWriteSystemSettings(appCtx)) {
            return Pair(false, "Autorisez « Modification des paramètres système » sur le téléphone pour régler la luminosité.")
        }
        return try {
            Settings.System.putInt(
                appCtx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val val255 = ((percent.coerceIn(5, 100) * 255) / 100).coerceIn(10, 255)
            Settings.System.putInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, val255)
            Pair(true, null)
        } catch (t: Throwable) {
            Pair(false, "Réglage luminosité impossible : ${t.message}")
        }
    }

    fun launchApp(context: Context, packageName: String): Pair<Boolean, String?> {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return Pair(false, "Cette application ne peut pas être lancée directement.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Pair(true, null)
        } catch (t: Throwable) {
            Pair(false, "Ouverture impossible : ${t.message}")
        }
    }

    fun promptUninstallApp(context: Context, prefs: PortalPreferences, packageName: String): Pair<Boolean, String?> {
        if (packageName.isBlank() || packageName == context.packageName) {
            return Pair(false, "Application invalide.")
        }
        val appCtx = context.applicationContext
        // Verify package is installed and not a non-removable core system app
        try {
            val ai = appCtx.packageManager.getApplicationInfo(packageName, 0)
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSys && !isUpdatedSys) {
                return Pair(false, "Application système Android non désinstallable (utilisez plutôt le blocage).")
            }
        } catch (_: Throwable) {
            return Pair(false, "Application introuvable sur le téléphone.")
        }

        // Grant generous 90s Settings/PackageInstaller grace so Anti-Bypass never blocks uninstall dialog
        prefs.settingsUnlockUntilMs = maxOf(
            prefs.settingsUnlockUntilMs,
            System.currentTimeMillis() + 90_000L
        )

        // Wake screen briefly if off so uninstall prompt is visible
        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SentinelCam::UninstallWake"
                )
                wl.setReferenceCounted(false)
                wl.acquire(2_000L)
            }
        } catch (_: Throwable) {
        }

        val intents = listOf(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )

        for (intent in intents) {
            try {
                appCtx.startActivity(intent)
                return Pair(true, "Fenêtre de désinstallation ouverte sur le téléphone.")
            } catch (_: Throwable) {
            }
        }
        return Pair(false, "Impossible d’ouvrir la désinstallation (vérifiez l’autorisation Superposition).")
    }

    /**
     * Remotely sets the child phone's Home Screen, Lock Screen, or Both wallpapers (v25).
     * Automatically center-crops the decoded image to match the phone's exact screen aspect ratio.
     */
    fun setRemoteWallpaper(
        context: Context,
        imageBytes: ByteArray,
        target: String = "both" // "home" | "lock" | "both"
    ): Pair<Boolean, String?> {
        if (imageBytes.isEmpty()) return Pair(false, "Image vide ou invalide.")
        val appCtx = context.applicationContext
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1440 || opts.outHeight / sample > 2560) {
                sample *= 2
            }
            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val rawBmp = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
                ?: return Pair(false, "Format d’image non pris en charge.")

            val dm = appCtx.resources.displayMetrics
            val targetW = dm.widthPixels.coerceIn(720, 1440)
            val targetH = dm.heightPixels.coerceIn(1280, 2560)

            // Center-crop cleanly to fill screen with zero distortion
            val scale = maxOf(
                targetW.toFloat() / rawBmp.width.coerceAtLeast(1).toFloat(),
                targetH.toFloat() / rawBmp.height.coerceAtLeast(1).toFloat()
            )
            val scaledW = (rawBmp.width * scale).toInt().coerceAtLeast(targetW)
            val scaledH = (rawBmp.height * scale).toInt().coerceAtLeast(targetH)
            val scaled = android.graphics.Bitmap.createScaledBitmap(rawBmp, scaledW, scaledH, true)
            if (scaled !== rawBmp) rawBmp.recycle()

            val cropX = ((scaledW - targetW) / 2).coerceAtLeast(0)
            val cropY = ((scaledH - targetH) / 2).coerceAtLeast(0)
            val finalBmp = android.graphics.Bitmap.createBitmap(
                scaled,
                cropX,
                cropY,
                targetW.coerceAtMost(scaledW - cropX),
                targetH.coerceAtMost(scaledH - cropY)
            )
            if (finalBmp !== scaled) scaled.recycle()

            val wm = android.app.WallpaperManager.getInstance(appCtx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val flags = when (target) {
                    "home" -> android.app.WallpaperManager.FLAG_SYSTEM
                    "lock" -> android.app.WallpaperManager.FLAG_LOCK
                    else -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                }
                wm.setBitmap(finalBmp, null, true, flags)
            } else {
                wm.setBitmap(finalBmp)
            }
            finalBmp.recycle()

            val label = when (target) {
                "home" -> "l’écran d’accueil"
                "lock" -> "l’écran de verrouillage"
                else -> "l’écran d’accueil et de verrouillage"
            }
            Pair(true, "Fond d’écran appliqué sur $label.")
        } catch (t: Throwable) {
            Pair(false, "Impossible de changer le fond d’écran : ${t.message ?: "erreur système"}")
        }
    }
}
