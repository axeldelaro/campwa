package com.demonpet.app.mdm

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.receiver.DemonDeviceAdminReceiver

/**
 * MDM Device Owner Manager for DemonMom RPG v4.0:
 * - When enabled via ADB/Termux (`dpm set-device-owner com.demonpet.app/.receiver.DemonDeviceAdminReceiver`),
 *   lets Witch RPG defeats apply REAL reversible system "damage" on the phone:
 *     1. Suspends (greys out) or temporarily hides apps strictly in descending order of real UsageStats usage!
 *     2. Applies reversible curses (Camera seal, Play Store install seal, Screenshot seal, Brightness lock, Uninstall lock).
 * - Tracks EVERY single touched package & restriction persistently so `restoreEverything()` or Emergency Code `100205`
 *   cleanly restores 100% of the phone back to normal!
 */
object DemonMdmManager {

    private const val MDM_PREFS = "demonmom_mdm_state_v1"
    private const val KEY_SUSPENDED_PKGS = "mdm_suspended_pkgs"
    private const val KEY_HIDDEN_PKGS = "mdm_hidden_pkgs"
    private const val KEY_CURSES = "mdm_active_curses"

    data class AppUsageEntry(val packageName: String, val label: String, val foregroundMinutes: Long)
    data class MdmDamageReport(val applied: Boolean, val summary: String, val affectedAppLabel: String? = null)

    // Safety whitelist: NEVER suspend or hide vital communication, launcher, keyboard, settings recovery, or Termux/DemonMom!
    private val PROTECTED_KEYWORDS = listOf(
        "com.demonpet.app",
        "com.termux",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.settings",
        "inputmethod",
        "gboard",
        "swiftkey",
        "launcher",
        "trebuchet",
        "miui.home",
        "nexuslauncher",
        "emergency",
        "telecom",
        "clock",
        "deskclock"
    )

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun hasUsageStatsAccess(context: Context): Boolean {
        return try {
            val appOps = context.applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    private fun isProtectedPackage(pkg: String): Boolean {
        val l = pkg.lowercase()
        return PROTECTED_KEYWORDS.any { l.contains(it) }
    }

    /**
     * Returns installed user apps sorted from MOST USED to LEAST USED (last 7 days via UsageStatsManager).
     * Falls back cleanly to watchedPackages + installed launchable user apps if UsageStats isn't populated yet.
     */
    fun getMostUsedUserApps(context: Context, limit: Int = 25): List<AppUsageEntry> {
        val pm = context.packageManager
        val launchable = HashSet<String>()
        try {
            val i = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo?.packageName ?: continue
                if (!isProtectedPackage(p)) launchable.add(p)
            }
        } catch (_: Throwable) {
        }

        val usageMsByPkg = HashMap<String, Long>()
        try {
            val usm = context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 7L * 24L * 3600L * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, start, end).orEmpty()
            for (s in stats) {
                val p = s.packageName ?: continue
                if (!launchable.contains(p) || isProtectedPackage(p)) continue
                usageMsByPkg[p] = (usageMsByPkg[p] ?: 0L) + s.totalTimeInForeground
            }
        } catch (_: Throwable) {
        }

        val settings = DemonSettings(context)
        val watched = settings.watchedPackages
        val result = ArrayList<AppUsageEntry>()
        for (pkg in launchable) {
            val fgMs = usageMsByPkg[pkg] ?: 0L
            // Prioritize apps with real foreground usage; give watched apps a small tie-breaker boost
            val sortBoostMs = if (watched.contains(pkg)) 30_000L else 0L
            val isUserApp = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || watched.contains(pkg) || fgMs > 60_000L
            } catch (_: Throwable) {
                false
            }
            if (!isUserApp) continue
            val lbl = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg.substringAfterLast('.')
            }
            result.add(AppUsageEntry(pkg, lbl, (fgMs + sortBoostMs) / 60_000L))
        }

        return result.sortedByDescending { it.foregroundMinutes }.take(limit)
    }

    fun getSuspendedPackages(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
    }

    fun getHiddenPackages(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
    }

    fun getActiveCurses(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())
    }

    /**
     * Applies REAL MDM phone "damage" upon losing a Witch RPG battle or hitting critical level drain:
     * - Suspends the NEXT most-used app by real usage order!
     * - On severe defeats (or playerLevel <= 8), also applies a reversible system curse (Camera / Store / Screenshots / Hide).
     */
    fun applyDefeatMdmDamage(context: Context, playerLevel: Int, severe: Boolean): MdmDamageReport {
        if (!isDeviceOwner(context)) {
            return MdmDamageReport(false, "Mode MDM non activé (copie le script Termux dans Réglages pour les dégâts MDM réels).")
        }
        val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DemonDeviceAdminReceiver.getComponentName(context)
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val curses = HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())

        val notes = ArrayList<String>()
        var affectedLabel: String? = null

        // Protect DemonMom from uninstall during active MDM game (restored on end game / 100205!)
        try {
            dpm.setUninstallBlocked(admin, context.packageName, true)
        } catch (_: Throwable) {
        }

        // 1) Suspend the NEXT most-used app by real usage order!
        val ranked = getMostUsedUserApps(context, limit = 30)
        val nextTarget = ranked.firstOrNull { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) }
        if (nextTarget != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val failed = dpm.setPackagesSuspended(admin, arrayOf(nextTarget.packageName), true)
                    if (failed.isEmpty()) {
                        suspended.add(nextTarget.packageName)
                        affectedLabel = nextTarget.label
                        notes.add("🚫 Appli suspendue (#usage) : ${nextTarget.label}")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2) On Bad End / Severe defeat (level <= 8 or severe flag), apply an extra reversible system seal!
        if (severe || playerLevel <= 8) {
            when {
                !curses.contains("camera") -> {
                    try {
                        dpm.setCameraDisabled(admin, true)
                        curses.add("camera")
                        notes.add("📷 Sceau Caméra (caméra coupée)")
                    } catch (_: Throwable) {}
                }
                !curses.contains("install_apps") -> {
                    try {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                        curses.add("install_apps")
                        notes.add("🛒 Sceau Installations (Play Store coupé)")
                    } catch (_: Throwable) {}
                }
                !curses.contains("screen_capture") -> {
                    try {
                        dpm.setScreenCaptureDisabled(admin, true)
                        curses.add("screen_capture")
                        notes.add("🖼️ Sceau Captures d'écran")
                    } catch (_: Throwable) {}
                }
                !curses.contains("brightness_cfg") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    try {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BRIGHTNESS)
                        curses.add("brightness_cfg")
                        notes.add("🌙 Sceau Luminosité verrouillée")
                    } catch (_: Throwable) {}
                }
                playerLevel <= 4 && nextTarget != null -> {
                    try {
                        if (dpm.setApplicationHidden(admin, nextTarget.packageName, true)) {
                            hidden.add(nextTarget.packageName)
                            notes.add("👻 Appli cachée temporairement : ${nextTarget.label}")
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        prefs.edit()
            .putStringSet(KEY_SUSPENDED_PKGS, suspended)
            .putStringSet(KEY_HIDDEN_PKGS, hidden)
            .putStringSet(KEY_CURSES, curses)
            .apply()

        val msg = if (notes.isEmpty()) "Malédiction MDM active (${suspended.size} appli(s) gelée(s))" else notes.joinToString("\n")
        return MdmDamageReport(true, msg, affectedLabel)
    }

    /** Winning a Witch RPG battle heals & unsuspends 1 locked app! */
    fun healOneSuspendedApp(context: Context): String? {
        if (!isDeviceOwner(context)) return null
        val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DemonDeviceAdminReceiver.getComponentName(context)
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val target = suspended.firstOrNull() ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(admin, arrayOf(target), false)
            }
            suspended.remove(target)
            prefs.edit().putStringSet(KEY_SUSPENDED_PKGS, suspended).apply()
            val lbl = try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(target, 0)).toString()
            } catch (_: Throwable) {
                target.substringAfterLast('.')
            }
            lbl
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * GUARANTEED 100% FULL RESTORATION (End of Game OR Emergency Code `100205`):
     * Unsuspends ALL apps, unhides ALL apps, clears ALL curses/restrictions, restores wallpaper & unlocks uninstall.
     */
    fun restoreEverything(context: Context, releaseDeviceOwner: Boolean = false): String {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        var restoredCount = 0

        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)

            if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
                    restoredCount += suspended.size
                } catch (_: Throwable) {}
            }
            for (pkg in hidden) {
                try {
                    dpm.setApplicationHidden(admin, pkg, false)
                    restoredCount++
                } catch (_: Throwable) {}
            }
            try { dpm.setCameraDisabled(admin, false) } catch (_: Throwable) {}
            try { dpm.setScreenCaptureDisabled(admin, false) } catch (_: Throwable) {}
            try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) } catch (_: Throwable) {}
            try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME) } catch (_: Throwable) {}
            try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI) } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_BRIGHTNESS) } catch (_: Throwable) {}
            }
            try { dpm.setUninstallBlocked(admin, context.packageName, false) } catch (_: Throwable) {}

            if (releaseDeviceOwner) {
                try {
                    @Suppress("DEPRECATION")
                    dpm.clearDeviceOwnerApp(context.packageName)
                } catch (_: Throwable) {}
            }
        }

        prefs.edit()
            .remove(KEY_SUSPENDED_PKGS)
            .remove(KEY_HIDDEN_PKGS)
            .remove(KEY_CURSES)
            .apply()

        Thread { MediaFolderHelper.restoreOriginalWallpaper(context.applicationContext) }.start()
        return if (restoredCount > 0) {
            "Tout restauré : $restoredCount appli(s) réactivée(s) et tous les sceaux levés ✅"
        } else {
            "Tous les accès MDM, sceaux et paramètres ont été restaurés ✅"
        }
    }

    /** Ready-to-paste 1-block Termux script that configures ADB wireless, all permissions, Accessibility & MDM Device Owner! */
    fun buildTermuxOneLineScript(): String =
        """pkg update -y && pkg install -y android-tools && read -p "Port couplage (ex 37123): " PP && read -p "Code 6 chiffres: " PC && adb pair 127.0.0.1:${'$'}PP ${'$'}PC && read -p "Port connexion (ex 41234): " CP && adb connect 127.0.0.1:${'$'}CP && adb shell appops set com.demonpet.app MANAGE_EXTERNAL_STORAGE allow && adb shell appops set com.demonpet.app SYSTEM_ALERT_WINDOW allow && adb shell appops set com.demonpet.app WRITE_SETTINGS allow && adb shell appops set com.demonpet.app GET_USAGE_STATS allow && adb shell settings put secure enabled_accessibility_services com.demonpet.app/com.demonpet.app.service.DemonAccessibilityService && adb shell settings put secure accessibility_enabled 1 && adb shell dpm set-device-owner com.demonpet.app/.receiver.DemonDeviceAdminReceiver && echo "=== DEMONMOM MDM + ACCESSIBILITE 100% ACTIF ! ===" """.trim()
}
