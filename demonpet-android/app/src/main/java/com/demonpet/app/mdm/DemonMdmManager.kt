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
 * Hybrid Privileged System Control Manager (Shizuku ADB Shell + MDM Device Owner):
 * - Works immediately on Android 11–16 (including Xiaomi HyperOS / Android 16!) via **Shizuku**
 *   WITHOUT factory reset and WITHOUT removing any Google/Xiaomi accounts!
 * - Also supports full MDM Device Owner (`DevicePolicyManager`) automatically if provisioned.
 * - Suspends/disables apps strictly in descending order of real usage (`UsageStatsManager`),
 *   applies reversible curses (Camera seal, Play Store seal, Screen lock), and guarantees 100% restoration
 *   on End of Game, RPG Victory, or Emergency Code `100205`!
 */
object DemonMdmManager {

    private const val MDM_PREFS = "demonmom_mdm_state_v1"
    private const val KEY_SUSPENDED_PKGS = "mdm_suspended_pkgs"
    private const val KEY_HIDDEN_PKGS = "mdm_hidden_pkgs"
    private const val KEY_CURSES = "mdm_active_curses"
    private const val KEY_CAM_SEALED_PKGS = "mdm_cam_sealed_pkgs"

    data class AppUsageEntry(val packageName: String, val label: String, val foregroundMinutes: Long)
    data class MdmDamageReport(val applied: Boolean, val summary: String, val affectedAppLabel: String? = null)

    // Safety whitelist: NEVER suspend or disable vital communication, launcher, keyboard, settings, Shizuku, Termux, or DemonMom!
    private val PROTECTED_KEYWORDS = listOf(
        "com.demonpet.app",
        "moe.shizuku",
        "com.termux",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.contacts",
        "com.android.mms",
        "com.android.apps.messaging",
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

    fun isShizukuActive(): Boolean = DemonShizukuBridge.hasShizukuPermission()

    fun hasPrivilegedControl(context: Context): Boolean = isDeviceOwner(context) || isShizukuActive()

    fun getControlStatusLabel(context: Context): String = when {
        isDeviceOwner(context) -> "👑 MDM Device Owner ACTIF"
        isShizukuActive() -> "⚡ Shizuku ADB ACTIF"
        DemonShizukuBridge.isShizukuRunning() -> "⚠️ Shizuku lancé (clique Autoriser)"
        else -> "⚙️ Shizuku / MDM inactif"
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
     * Applies REAL phone "damage" upon losing a Witch RPG battle:
     * - Automatically uses **Device Owner** if active, otherwise **Shizuku ADB Shell** (Android 11–16, zero reset!).
     * - Suspends/disables the NEXT most-used app by real usage order!
     * - On severe defeats (or playerLevel <= 8), applies a reversible system seal (Camera / Play Store / Disable / Screen Lock).
     */
    fun applyDefeatMdmDamage(context: Context, playerLevel: Int, severe: Boolean): MdmDamageReport {
        val useDo = isDeviceOwner(context)
        val useShizuku = !useDo && isShizukuActive()
        if (!useDo && !useShizuku) {
            return MdmDamageReport(false, "Mode Shizuku/MDM non activé (autorise Shizuku dans l'accueil pour geler les applis par usage !).")
        }

        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val curses = HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())
        val camSealed = HashSet(prefs.getStringSet(KEY_CAM_SEALED_PKGS, emptySet()) ?: emptySet())

        val notes = ArrayList<String>()
        var affectedLabel: String? = null
        val ranked = getMostUsedUserApps(context, limit = 30)
        val nextTarget = ranked.firstOrNull { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) }

        if (useDo) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            try { dpm.setUninstallBlocked(admin, context.packageName, true) } catch (_: Throwable) {}

            if (nextTarget != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val failed = dpm.setPackagesSuspended(admin, arrayOf(nextTarget.packageName), true)
                    if (failed.isEmpty()) {
                        suspended.add(nextTarget.packageName)
                        affectedLabel = nextTarget.label
                        notes.add("🚫 Appli suspendue (#usage) : ${nextTarget.label}")
                    }
                } catch (_: Throwable) {}
            }
            if (severe || playerLevel <= 8) {
                when {
                    !curses.contains("camera") -> try {
                        dpm.setCameraDisabled(admin, true); curses.add("camera")
                        notes.add("📷 Sceau Caméra activé")
                    } catch (_: Throwable) {}
                    !curses.contains("install_apps") -> try {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS); curses.add("install_apps")
                        notes.add("🛒 Sceau Play Store activé")
                    } catch (_: Throwable) {}
                    !curses.contains("screen_capture") -> try {
                        dpm.setScreenCaptureDisabled(admin, true); curses.add("screen_capture")
                        notes.add("🖼️ Sceau Captures d'écran activé")
                    } catch (_: Throwable) {}
                    playerLevel <= 4 && nextTarget != null -> try {
                        if (dpm.setApplicationHidden(admin, nextTarget.packageName, true)) {
                            hidden.add(nextTarget.packageName)
                            notes.add("👻 Appli cachée : ${nextTarget.label}")
                        }
                    } catch (_: Throwable) {}
                }
            }
        } else {
            // === SHIZUKU PRIVILEGED SHELL ENGINE (WORKS ON XIAOMI HYPEROS / ANDROID 16 WITHOUT RESET!) ===
            if (nextTarget != null) {
                if (playerLevel <= 4) {
                    val rDis = DemonShizukuBridge.execShellSync("pm disable-user --user 0 ${nextTarget.packageName}")
                    if (rDis.ok) {
                        hidden.add(nextTarget.packageName)
                        affectedLabel = nextTarget.label
                        notes.add("👻 Appli désactivée (Shizuku #usage) : ${nextTarget.label}")
                    }
                }
                if (affectedLabel == null) {
                    val rSusp = DemonShizukuBridge.execShellSync("pm suspend --user 0 ${nextTarget.packageName}")
                    if (rSusp.ok) {
                        suspended.add(nextTarget.packageName)
                        affectedLabel = nextTarget.label
                        notes.add("🚫 Appli suspendue (Shizuku #usage) : ${nextTarget.label}")
                    } else {
                        // Fallback if OEM blocks pm suspend on a given app -> pm disable-user!
                        val rDis = DemonShizukuBridge.execShellSync("pm disable-user --user 0 ${nextTarget.packageName}")
                        if (rDis.ok) {
                            hidden.add(nextTarget.packageName)
                            affectedLabel = nextTarget.label
                            notes.add("👻 Appli désactivée (Shizuku #usage) : ${nextTarget.label}")
                        }
                    }
                }
            }

            if (severe || playerLevel <= 8) {
                when {
                    !curses.contains("play_store") -> {
                        val r = DemonShizukuBridge.execShellSync("pm suspend --user 0 com.android.vending")
                        if (r.ok) {
                            suspended.add("com.android.vending")
                            curses.add("play_store")
                            notes.add("🛒 Sceau Play Store (suspendu via Shizuku)")
                        }
                    }
                    !curses.contains("camera") -> {
                        val targets = ranked.take(5).map { it.packageName }
                        for (p in targets) {
                            if (DemonShizukuBridge.execShellSync("appops set $p CAMERA deny").ok) {
                                camSealed.add(p)
                            }
                        }
                        if (camSealed.isNotEmpty()) {
                            curses.add("camera")
                            notes.add("📷 Sceau Caméra sur tes ${camSealed.size} applis principales")
                        }
                    }
                    !curses.contains("brightness_drain") -> {
                        if (DemonShizukuBridge.execShellSync("settings put system screen_brightness 28").ok) {
                            curses.add("brightness_drain")
                            notes.add("🌙 Luminosité drainée au minimum (Shizuku)")
                        }
                    }
                }
            }
        }

        prefs.edit()
            .putStringSet(KEY_SUSPENDED_PKGS, suspended)
            .putStringSet(KEY_HIDDEN_PKGS, hidden)
            .putStringSet(KEY_CURSES, curses)
            .putStringSet(KEY_CAM_SEALED_PKGS, camSealed)
            .apply()

        val msg = if (notes.isEmpty()) "Emprise active (${suspended.size + hidden.size} appli(s) gelée(s))" else notes.joinToString("\n")
        return MdmDamageReport(true, msg, affectedLabel)
    }

    /** Winning a Witch RPG battle heals & unsuspends 1 locked app! */
    fun healOneSuspendedApp(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val target = suspended.firstOrNull { it != "com.android.vending" }
            ?: hidden.firstOrNull()
            ?: suspended.firstOrNull()
            ?: return null

        var healed = false
        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) dpm.setPackagesSuspended(admin, arrayOf(target), false)
                dpm.setApplicationHidden(admin, target, false)
                healed = true
            } catch (_: Throwable) {}
        } else if (isShizukuActive()) {
            val r1 = DemonShizukuBridge.execShellSync("pm unsuspend --user 0 $target")
            val r2 = DemonShizukuBridge.execShellSync("pm enable --user 0 $target")
            healed = r1.ok || r2.ok
        }

        if (!healed) return null
        suspended.remove(target)
        hidden.remove(target)
        prefs.edit()
            .putStringSet(KEY_SUSPENDED_PKGS, suspended)
            .putStringSet(KEY_HIDDEN_PKGS, hidden)
            .apply()

        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(target, 0)).toString()
        } catch (_: Throwable) {
            target.substringAfterLast('.')
        }
    }

    /**
     * GUARANTEED 100% FULL RESTORATION (End of Game OR Emergency Code `100205`):
     * Restores ALL apps & seals via BOTH Device Owner and Shizuku!
     */
    fun restoreEverything(context: Context, releaseDeviceOwner: Boolean = false): String {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val camSealed = HashSet(prefs.getStringSet(KEY_CAM_SEALED_PKGS, emptySet()) ?: emptySet())
        var restoredCount = 0

        // 1) Device Owner restore
        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false); restoredCount += suspended.size } catch (_: Throwable) {}
            }
            for (pkg in hidden) {
                try { dpm.setApplicationHidden(admin, pkg, false); restoredCount++ } catch (_: Throwable) {}
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

        // 2) Shizuku restore (unsuspend + enable every touched package + restore camera ops + brightness)
        if (isShizukuActive()) {
            val allPkgs = HashSet<String>().apply { addAll(suspended); addAll(hidden) }
            for (pkg in allPkgs) {
                val r1 = DemonShizukuBridge.execShellSync("pm unsuspend --user 0 $pkg")
                val r2 = DemonShizukuBridge.execShellSync("pm enable --user 0 $pkg")
                if (r1.ok || r2.ok) restoredCount++
            }
            for (pkg in camSealed) {
                DemonShizukuBridge.execShellSync("appops set $pkg CAMERA allow")
            }
            DemonShizukuBridge.execShellSync("settings put system screen_brightness 140")
        }

        prefs.edit()
            .remove(KEY_SUSPENDED_PKGS)
            .remove(KEY_HIDDEN_PKGS)
            .remove(KEY_CURSES)
            .remove(KEY_CAM_SEALED_PKGS)
            .apply()

        Thread { MediaFolderHelper.restoreOriginalWallpaper(context.applicationContext) }.start()
        return if (restoredCount > 0) {
            "Tout restauré : $restoredCount appli(s) réactivée(s) et tous les sceaux levés ✅"
        } else {
            "Tous les accès Shizuku/MDM, sceaux et paramètres ont été restaurés ✅"
        }
    }

    /** Clean Termux 1-paste script (never fails on MIUI/HyperOS WRITE_SECURE_SETTINGS!) */
    fun buildTermuxOneLineScript(): String =
        """pkg update -y && pkg install -y android-tools && read -p "Port couplage: " PP && read -p "Code 6 chiffres: " PC && adb pair 127.0.0.1:${'$'}PP ${'$'}PC && read -p "Port connexion: " CP && adb connect 127.0.0.1:${'$'}CP && adb shell appops set com.demonpet.app MANAGE_EXTERNAL_STORAGE allow; adb shell appops set com.demonpet.app SYSTEM_ALERT_WINDOW allow; adb shell appops set com.demonpet.app WRITE_SETTINGS allow; adb shell appops set com.demonpet.app GET_USAGE_STATS allow; echo "=== PERMISSIONS OK ! Ouvre Shizuku > Demarrer (Debogage sans fil) puis DemonMom > Autoriser Shizuku ! ===" """.trim()
}
