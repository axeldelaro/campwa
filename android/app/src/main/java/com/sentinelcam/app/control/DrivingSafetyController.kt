package com.sentinelcam.app.control

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sentinelcam.app.receiver.SentinelDeviceAdminReceiver
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONObject
import kotlin.math.max

/**
 * Automatic Road / Trip Safety Mode (Mode Trajet):
 * - Parent configures activation speed threshold (10..130 km/h, default 25 km/h).
 * - Uses existing validated GPS speed samples from DrivingBehaviorMonitor (0% extra GPS battery!).
 * - When sustained speed >= threshold:
 *     1) Automatically suspends distracting social/video/game apps via native MDM `setPackagesSuspended`
 *        (and via AppLockMonitor shield fallback on non-MDM phones), NEVER blocking Phone/Emergency/Maps/Waze/Music.
 *     2) Optionally switches ringer to vibrate/silent and restores previous ringer mode once parked.
 * - Bridges red lights via 45s stopped hysteresis before automatically restoring everything.
 * - Tracks driving-suspended packages separately so manually suspended apps stay untouched!
 */
object DrivingSafetyController {

    private const val PREFS_NAME = "sentinel_mdm_controls_v1"
    private const val KEY_ENABLED = "driving_safety_enabled"
    private const val KEY_THRESHOLD_KMH = "driving_threshold_kmh"
    private const val KEY_SUSPEND_APPS = "driving_suspend_apps"
    private const val KEY_SILENT_RINGER = "driving_silent_ringer"
    private const val KEY_DRIVING_SUSPENDED_PKGS = "driving_suspended_pkgs"
    private const val KEY_SAVED_RINGER_MODE = "driving_saved_ringer_mode"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastSpeedKmh: Int = 0
        private set

    private var aboveThresholdSinceMs: Long = 0L
    private var lastTriggerLogMs: Long = 0L

    private val DISTRACTION_PACKAGES = setOf(
        "com.zhiliaoapp.musically",       // TikTok
        "com.ss.android.ugc.trill",       // TikTok Alt
        "com.instagram.android",          // Instagram
        "com.snapchat.android",           // Snapchat
        "com.google.android.youtube",     // YouTube
        "com.netflix.mediaclient",        // Netflix
        "tv.twitch.android.app",          // Twitch
        "com.discord",                    // Discord
        "com.twitter.android",            // X / Twitter
        "com.facebook.katana",            // Facebook
        "com.facebook.orca",              // Messenger
        "com.whatsapp",                   // WhatsApp
        "org.telegram.messenger",         // Telegram
        "com.reddit.frontpage",           // Reddit
        "com.pinterest",                  // Pinterest
        "com.supercell.brawlstars",       // Brawl Stars
        "com.supercell.clashroyale",      // Clash Royale
        "com.supercell.clashofclans",     // Clash of Clans
        "com.roblox.client",              // Roblox
        "com.mojang.minecraftpe",         // Minecraft
        "com.kiloo.subwaysurf",           // Subway Surfers
        "com.dts.freefireth",             // Free Fire
        "com.tencent.ig"                  // PUBG Mobile
    )

    private val NAV_AND_ESSENTIAL_PREFIXES = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.android.emergency",
        "com.spotify.music",
        "com.google.android.apps.youtube.music"
    )

    private var pendingDisengageRunnable: Runnable? = null

    fun isEnabled(context: Context): Boolean {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, false)
    }

    fun getThresholdKmh(context: Context): Int {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_THRESHOLD_KMH, 25).coerceIn(10, 130)
    }

    fun isSuspendAppsEnabled(context: Context): Boolean {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_SUSPEND_APPS, true)
    }

    fun isSilentRingerEnabled(context: Context): Boolean {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_SILENT_RINGER, true)
    }

    fun configure(
        context: Context,
        enabled: Boolean,
        thresholdKmh: Int,
        suspendApps: Boolean,
        silentRinger: Boolean
    ): Pair<Boolean, String> {
        val appCtx = context.applicationContext
        val cleanThreshold = thresholdKmh.coerceIn(10, 130)
        val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_THRESHOLD_KMH, cleanThreshold)
            .putBoolean(KEY_SUSPEND_APPS, suspendApps)
            .putBoolean(KEY_SILENT_RINGER, silentRinger)
            .apply()

        if (!enabled) {
            disengageNow(appCtx)
            return Pair(true, "Mode Trajet automatique désactivé.")
        }
        return Pair(true, "Mode Trajet activé (seuil : $cleanThreshold km/h).")
    }

    fun readStateJson(context: Context): JSONObject {
        return JSONObject().apply {
            put("enabled", isEnabled(context))
            put("thresholdKmh", getThresholdKmh(context))
            put("suspendApps", isSuspendAppsEnabled(context))
            put("silentRinger", isSilentRingerEnabled(context))
            put("activeNow", isActive)
            put("lastSpeedKmh", lastSpeedKmh)
        }
    }

    fun isPackageBlockedByDriving(context: Context, packageName: String): Boolean {
        if (!isActive || !isSuspendAppsEnabled(context)) return false
        if (packageName == context.packageName) return false
        if (NAV_AND_ESSENTIAL_PREFIXES.any { packageName.startsWith(it) }) return false
        return DISTRACTION_PACKAGES.contains(packageName) || isInstalledGame(context, packageName)
    }

    fun onSpeedSample(context: Context, speedKmh: Float, accuracyMeters: Float) {
        val appCtx = context.applicationContext
        if (accuracyMeters > 65f) return
        val speedInt = speedKmh.toInt().coerceIn(0, 320)
        lastSpeedKmh = speedInt

        if (!isEnabled(appCtx)) {
            if (isActive) disengageNow(appCtx)
            return
        }

        val threshold = getThresholdKmh(appCtx).toFloat()
        val now = System.currentTimeMillis()

        if (speedKmh >= threshold) {
            cancelPendingDisengage()
            if (aboveThresholdSinceMs == 0L) {
                aboveThresholdSinceMs = now
            }
            val sustained = (now - aboveThresholdSinceMs) >= 3_000L || speedKmh >= threshold + 8f
            if (sustained && !isActive) {
                engageNow(appCtx, speedInt, threshold.toInt())
            }
        } else {
            aboveThresholdSinceMs = 0L
            val stopThreshold = max(5f, threshold * 0.40f)
            if (isActive && speedKmh < stopThreshold) {
                scheduleDisengage(appCtx, delayMs = 45_000L)
            }
        }
    }

    private fun engageNow(context: Context, speedKmh: Int, thresholdKmh: Int) {
        isActive = true
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1) Native OS-level MDM suspension of distracting apps (if Device Owner)
        if (isSuspendAppsEnabled(context)) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                val admin = SentinelDeviceAdminReceiver.getComponentName(context)
                if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                    val manualSuspended = SystemControlHelper.getTrackedSuspendedPackages(context)
                    val targets = findInstalledDistractionPackages(context)
                        .filter { !manualSuspended.contains(it) }
                    if (targets.isNotEmpty()) {
                        dpm.setPackagesSuspended(admin, targets.toTypedArray(), true)
                        sp.edit().putStringSet(KEY_DRIVING_SUSPENDED_PKGS, HashSet(targets)).apply()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2) Optional ringer silence during trip
        if (isSilentRingerEnabled(context)) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prevMode = am.ringerMode
                if (prevMode != AudioManager.RINGER_MODE_VIBRATE && prevMode != AudioManager.RINGER_MODE_SILENT) {
                    sp.edit().putInt(KEY_SAVED_RINGER_MODE, prevMode).apply()
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
            } catch (_: Throwable) {
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerLogMs > 180_000L) {
            lastTriggerLogMs = now
            try {
                PortalPreferences(context).appendLog(
                    "safety",
                    "Mode Trajet activé automatiquement ($speedKmh km/h >= seuil $thresholdKmh km/h)."
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleDisengage(context: Context, delayMs: Long) {
        if (pendingDisengageRunnable != null) return
        val r = Runnable {
            pendingDisengageRunnable = null
            disengageNow(context)
        }
        pendingDisengageRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelPendingDisengage() {
        pendingDisengageRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDisengageRunnable = null
    }

    fun disengageNow(context: Context) {
        cancelPendingDisengage()
        aboveThresholdSinceMs = 0L
        val wasActive = isActive
        isActive = false

        val appCtx = context.applicationContext
        val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1) Unsuspend ONLY packages suspended by Driving Mode (keep manually suspended apps suspended!)
        val drivingSuspended = HashSet(sp.getStringSet(KEY_DRIVING_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        if (drivingSuspended.isNotEmpty()) {
            try {
                val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
                if (dpm != null && dpm.isDeviceOwnerApp(appCtx.packageName)) {
                    val manualSuspended = SystemControlHelper.getTrackedSuspendedPackages(appCtx)
                    val toRestore = drivingSuspended.filter { !manualSuspended.contains(it) }
                    if (toRestore.isNotEmpty()) {
                        dpm.setPackagesSuspended(admin, toRestore.toTypedArray(), false)
                    }
                }
            } catch (_: Throwable) {
            }
            sp.edit().remove(KEY_DRIVING_SUSPENDED_PKGS).apply()
        }

        // 2) Restore saved ringer mode
        val savedRinger = sp.getInt(KEY_SAVED_RINGER_MODE, -1)
        if (savedRinger >= 0) {
            try {
                val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.ringerMode = savedRinger
            } catch (_: Throwable) {
            }
            sp.edit().remove(KEY_SAVED_RINGER_MODE).apply()
        }

        if (wasActive) {
            try {
                PortalPreferences(appCtx).appendLog("safety", "Mode Trajet levé automatiquement (arrêt détecté).")
            } catch (_: Throwable) {
            }
        }
    }

    fun disengageAndResetOnUnpair(context: Context) {
        val appCtx = context.applicationContext
        disengageNow(appCtx)
        val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_DRIVING_SUSPENDED_PKGS)
            .remove(KEY_SAVED_RINGER_MODE)
            .apply()
    }

    private fun findInstalledDistractionPackages(context: Context): List<String> {
        val pm = context.packageManager
        val out = ArrayList<String>()
        try {
            val installed = pm.getInstalledApplications(0)
            for (ai in installed) {
                val pkg = ai.packageName ?: continue
                if (pkg == context.packageName) continue
                if (NAV_AND_ESSENTIAL_PREFIXES.any { pkg.startsWith(it) }) continue
                val isGame = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME)
                if (DISTRACTION_PACKAGES.contains(pkg) || isGame) {
                    out.add(pkg)
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun isInstalledGame(context: Context, packageName: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME
        } catch (_: Throwable) {
            false
        }
    }
}
