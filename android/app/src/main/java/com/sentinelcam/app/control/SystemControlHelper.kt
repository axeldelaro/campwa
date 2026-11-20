package com.sentinelcam.app.control

import android.app.ActivityManager
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
import android.provider.Settings
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
        val out = ArrayList<JSONObject>()
        try {
            val pkgs = pm.getInstalledPackages(0)
            for (pi in pkgs) {
                val ai = pi.applicationInfo ?: continue
                val pkg = pi.packageName ?: continue
                if (pkg == context.packageName) continue
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val hasLauncher = pm.getLaunchIntentForPackage(pkg) != null

                // Include ALL user-installed apps (even hidden ones without launcher!) + key user-facing system apps
                if (isSystem && !isUpdatedSystem && !hasLauncher) continue

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
                    }
                )
            }
        } catch (_: Throwable) {
        }

        // Show sideloaded / hidden / most recently installed apps first!
        out.sortWith(
            compareByDescending<JSONObject> { it.optBoolean("sideloaded") }
                .thenByDescending { !it.optBoolean("hasLauncher") && !it.optBoolean("isSystem") }
                .thenByDescending { it.optLong("installedAt") }
                .thenBy { it.optString("appName").lowercase(Locale.FRENCH) }
        )

        val arr = JSONArray()
        for (item in out.take(140)) arr.put(item)
        return arr
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
