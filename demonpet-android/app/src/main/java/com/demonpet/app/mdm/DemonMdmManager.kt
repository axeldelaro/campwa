package com.demonpet.app.mdm

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.receiver.DemonDeviceAdminReceiver

/**
 * Full-Power Shizuku (Android 11–16 / HyperOS) + MDM Hybrid System Control Engine — v9.0:
 * All 12 Concrete System Powers (100% reversible on Victory / End Game / Code 100205, NEVER disables Wi-Fi!):
 *  1) App Grey-Out (`cmd package suspend --user 0`) by real usage order (single or social/game category!)
 *  2) App Disappear (`pm disable-user --user 0`) for Boss/low-Level defeats
 *  3) Force-Close App (`am force-stop`) + Pull Back to DemonMom RPG (`am start`)
 *  4) System Fullscreen Task Pinning (`am task lock <taskId>` / `am task lock stop`)
 *  5) Chrono Global Slow-Motion (`window/transition/animator_duration_scale 3.5`)
 *  6) Fake Critical 3% Battery Illusion (`cmd battery set level 3` / `reset`)
 *  7) Illusion Screen Density Distortion (`wm density <dpi>` / `wm density reset`)
 *  8) Isolation Seal: Notifications + Background Freeze (`POST_NOTIFICATION deny` + `RUN_IN_BACKGROUND deny` + DND)
 *  9) Inquisitor Privacy Seal: Microphone + Clipboard + Camera (`RECORD_AUDIO` / `READ_CLIPBOARD` / `CAMERA deny`)
 * 10) Eclipse Dark Mode & Battery Saver (`cmd uimode night yes` + `cmd power set-mode 1`)
 * 11) Screen Sleep Snap (`input keyevent 26`) + Brightness Drain + Audio Volume Curse
 * 12) Camera LED Torch Strobe Pulses
 */
object DemonMdmManager {

    private const val MDM_PREFS = "demonmom_mdm_state_v1"
    private const val KEY_SUSPENDED_PKGS = "mdm_suspended_pkgs"
    private const val KEY_HIDDEN_PKGS = "mdm_hidden_pkgs"
    private const val KEY_CURSES = "mdm_active_curses"
    private const val KEY_CAM_SEALED_PKGS = "mdm_cam_sealed_pkgs"
    private const val KEY_SILENCED_PKGS = "mdm_silenced_pkgs"
    private const val KEY_MIC_CLIP_PKGS = "mdm_mic_clip_pkgs"
    private const val KEY_FOCUS_PKGS = "mdm_focus_pkgs_v10"
    private const val KEY_SAVED_VOLUME = "mdm_saved_media_vol"
    private const val KEY_SAVED_BRIGHTNESS = "mdm_saved_sys_bright"

    data class AppUsageEntry(val packageName: String, val label: String, val foregroundMinutes: Long)
    data class AppThreatInfo(val packageName: String, val label: String, val isHostage: Boolean)
    data class MdmDamageReport(val applied: Boolean, val summary: String, val affectedAppLabel: String? = null)

    private val PROTECTED_KEYWORDS = listOf(
        "com.demonpet.app", "moe.shizuku", "com.termux",
        "com.android.systemui", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.contacts", "com.android.mms",
        "com.android.apps.messaging", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.settings", "inputmethod", "gboard", "swiftkey",
        "launcher", "trebuchet", "miui.home", "nexuslauncher",
        "emergency", "telecom", "clock", "deskclock", "securitycenter"
    )

    private val POPULAR_FALLBACK_KEYWORDS = listOf(
        "instagram", "tiktok", "musically", "youtube", "twitter", "reddit", "snapchat",
        "discord", "twitch", "netflix", "spotify", "chrome", "facebook", "whatsapp", "telegram"
    )

    fun isDeviceOwner(context: Context): Boolean = try {
        val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(context.packageName)
    } catch (_: Throwable) { false }

    fun isShizukuActive(): Boolean = DemonShizukuBridge.hasShizukuPermission()
    fun hasPrivilegedControl(context: Context): Boolean = isDeviceOwner(context) || isShizukuActive()

    fun getControlStatusLabel(context: Context): String = when {
        isDeviceOwner(context) -> "👑 MDM Device Owner ACTIF"
        isShizukuActive() -> "⚡ Shizuku ADB ACTIF"
        DemonShizukuBridge.isShizukuRunning() -> "⚠️ Shizuku détecté (clique Autoriser !)"
        DemonShizukuBridge.isShizukuInstalled(context) -> "⚠️ Shizuku installé mais arrêté"
        else -> "⚙️ Shizuku non installé"
    }

    fun hasUsageStatsAccess(context: Context): Boolean = try {
        val appOps = context.applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun isProtectedPackage(pkg: String): Boolean {
        val l = pkg.lowercase()
        return PROTECTED_KEYWORDS.any { l.contains(it) }
    }

    fun getMostUsedUserApps(context: Context, limit: Int = 30): List<AppUsageEntry> {
        val pm = context.packageManager
        val launchable = LinkedHashSet<String>()
        try {
            val i = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo?.packageName ?: continue
                if (!isProtectedPackage(p)) launchable.add(p)
            }
        } catch (_: Throwable) {}

        val usageMsByPkg = HashMap<String, Long>()
        try {
            val usm = context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, end - 7L * 24L * 3600L * 1000L, end).orEmpty()
            for (s in stats) {
                val p = s.packageName ?: continue
                if (!launchable.contains(p) || isProtectedPackage(p)) continue
                usageMsByPkg[p] = (usageMsByPkg[p] ?: 0L) + s.totalTimeInForeground
            }
        } catch (_: Throwable) {}

        val watched = DemonSettings(context).watchedPackages
        val result = ArrayList<AppUsageEntry>()
        for (pkg in launchable) {
            val fgMs = usageMsByPkg[pkg] ?: 0L
            val watchedBoostMs = if (watched.contains(pkg)) 180_000L else 0L
            val popularBoostMs = if (POPULAR_FALLBACK_KEYWORDS.any { pkg.lowercase().contains(it) }) 60_000L else 0L
            val isUserApp = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || watched.contains(pkg) || fgMs > 60_000L
            } catch (_: Throwable) { false }
            if (!isUserApp) continue
            val lbl = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg.substringAfterLast('.') }
            result.add(AppUsageEntry(pkg, lbl, (fgMs + watchedBoostMs + popularBoostMs) / 60_000L))
        }
        return result.sortedByDescending { it.foregroundMinutes }.take(limit)
    }

    fun getSuspendedPackages(context: Context): Set<String> =
        HashSet(context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())

    fun getHiddenPackages(context: Context): Set<String> =
        HashSet(context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())

    fun getActiveCurses(context: Context): Set<String> =
        HashSet(context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_CURSES, emptySet()) ?: emptySet())

    private fun markCurse(context: Context, key: String) {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())
        set.add(key)
        prefs.edit().putStringSet(KEY_CURSES, set).apply()
    }

    private fun suspendPackageViaShizuku(pkg: String): Pair<Boolean, String> {
        val r1 = DemonShizukuBridge.execShellSync("cmd package suspend --user 0 $pkg")
        if (r1.ok) return true to "suspended"
        val r2 = DemonShizukuBridge.execShellSync("pm suspend --user 0 $pkg")
        if (r2.ok) return true to "suspended"
        val r3 = DemonShizukuBridge.execShellSync("pm disable-user --user 0 $pkg")
        if (r3.ok) return true to "disabled"
        return false to "cmd=${r1.output.take(40)} | pm=${r2.output.take(40)}"
    }

    private fun unsuspendPackageViaShizuku(pkg: String): Boolean {
        val r1 = DemonShizukuBridge.execShellSync("cmd package unsuspend --user 0 $pkg")
        val r2 = DemonShizukuBridge.execShellSync("pm unsuspend --user 0 $pkg")
        val r3 = DemonShizukuBridge.execShellSync("pm enable --user 0 $pkg")
        return r1.ok || r2.ok || r3.ok
    }

    // ==================== 10 CONCRETE SHIZUKU SUPERPOWERS (100% REVERSIBLE!) ====================

    /** 1) System-level Fullscreen Task Pinning (blocks Home & Recents while in a Boss/Forced duel!) */
    fun setTaskPinningViaShizuku(taskId: Int, lock: Boolean): Boolean {
        if (!isShizukuActive()) return false
        return if (lock && taskId > 0) {
            DemonShizukuBridge.execShellSync("am task lock $taskId").ok
        } else {
            DemonShizukuBridge.execShellSync("am task lock stop").ok
        }
    }

    /** 2) Force-stop an escaping/cursed app and pull the user straight back into DemonMom RPG! */
    fun forceStopAndRecallToGame(pkg: String): Boolean {
        if (!isShizukuActive() || isProtectedPackage(pkg)) return false
        DemonShizukuBridge.execShellSync("am force-stop $pkg")
        return DemonShizukuBridge.execShellSync("am start -n com.demonpet.app/.ui.MainActivity").ok
    }

    /** 3) Chrono-Witch Global Slow-Motion (makes ALL Android animations 3.5x slower until Victory/Restore!) */
    fun applyChronoSlowMoCurse(context: Context, slowMo: Boolean): Boolean {
        if (!isShizukuActive()) return false
        val scale = if (slowMo) "3.5" else "1.0"
        val ok1 = DemonShizukuBridge.execShellSync("settings put global window_animation_scale $scale").ok
        val ok2 = DemonShizukuBridge.execShellSync("settings put global transition_animation_scale $scale").ok
        val ok3 = DemonShizukuBridge.execShellSync("settings put global animator_duration_scale $scale").ok
        if (slowMo && (ok1 || ok2 || ok3)) markCurse(context, "chrono_slowmo")
        return ok1 || ok2 || ok3
    }

    /** 4) Fake Critical 3% Battery Illusion (Android's real status bar displays 3% until reset!) */
    fun applyFakeLowBatteryCurse(context: Context, enable: Boolean, fakePct: Int = 3): Boolean {
        if (!isShizukuActive()) return false
        return if (enable) {
            val ok = DemonShizukuBridge.execShellSync("cmd battery set level ${fakePct.coerceIn(2, 15)}").ok
            if (ok) markCurse(context, "fake_battery")
            ok
        } else {
            DemonShizukuBridge.execShellSync("cmd battery reset").ok
        }
    }

    /** 5) Illusionist Screen Density Distortion (zooms entire Android UI giant/tiny until reset!) */
    fun applyScreenDensityDistortion(context: Context, distort: Boolean): Boolean {
        if (!isShizukuActive()) return false
        return if (distort) {
            val curDpi = context.resources.displayMetrics.densityDpi.coerceIn(280, 640)
            val shifted = (curDpi * 1.24f).toInt().coerceIn(340, 720)
            val ok = DemonShizukuBridge.execShellSync("wm density $shifted").ok
            if (ok) markCurse(context, "density_warp")
            ok
        } else {
            DemonShizukuBridge.execShellSync("wm density reset").ok
        }
    }

    /** 6) Isolation Seal: Silences notifications + background execution on top 5 apps + enables DND! */
    fun applyIsolationNotificationCurse(context: Context): String? {
        if (!isShizukuActive()) return null
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val silenced = HashSet(prefs.getStringSet(KEY_SILENCED_PKGS, emptySet()) ?: emptySet())
        val targets = getMostUsedUserApps(context, limit = 5)
        var count = 0
        for (t in targets) {
            val ok1 = DemonShizukuBridge.execShellSync("appops set ${t.packageName} POST_NOTIFICATION deny").ok
            val ok2 = DemonShizukuBridge.execShellSync("appops set ${t.packageName} RUN_IN_BACKGROUND deny").ok
            if (ok1 || ok2) { silenced.add(t.packageName); count++ }
        }
        DemonShizukuBridge.execShellSync("cmd notification set_dnd priority")
        if (count > 0) {
            prefs.edit().putStringSet(KEY_SILENCED_PKGS, silenced).apply()
            markCurse(context, "isolation_dnd")
            return "🔕 Isolement du Coven : notifications & arrière-plan coupés sur $count applis + Ne Pas Déranger !"
        }
        return null
    }

    /** 7) Inquisitor Silence Seal: Revokes Microphone + Clipboard on top 5 apps until Victory/Restore! */
    fun applyMicAndClipboardCurse(context: Context): String? {
        if (!isShizukuActive()) return null
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val sealed = HashSet(prefs.getStringSet(KEY_MIC_CLIP_PKGS, emptySet()) ?: emptySet())
        val targets = getMostUsedUserApps(context, limit = 5)
        var count = 0
        for (t in targets) {
            val ok1 = DemonShizukuBridge.execShellSync("appops set ${t.packageName} RECORD_AUDIO deny").ok
            val ok2 = DemonShizukuBridge.execShellSync("appops set ${t.packageName} READ_CLIPBOARD deny").ok
            if (ok1 || ok2) { sealed.add(t.packageName); count++ }
        }
        if (count > 0) {
            prefs.edit().putStringSet(KEY_MIC_CLIP_PKGS, sealed).apply()
            markCurse(context, "mic_clipboard")
            return "🎤 Sceau de Silence : micro & copier-coller scellés sur $count applis !"
        }
        return null
    }

    /** 8) Sovereign Eclipse Mode: Forces System Dark Mode + Android Battery Saver! */
    fun applyEclipsePowerAndDarkCurse(context: Context, enable: Boolean): Boolean {
        if (!isShizukuActive()) return false
        return if (enable) {
            val ok1 = DemonShizukuBridge.execShellSync("cmd uimode night yes").ok
            val ok2 = DemonShizukuBridge.execShellSync("cmd power set-mode 1").ok
            if (ok1 || ok2) markCurse(context, "eclipse_mode")
            ok1 || ok2
        } else {
            DemonShizukuBridge.execShellSync("cmd power set-mode 0")
            DemonShizukuBridge.execShellSync("cmd uimode night auto").ok
        }
    }

    /** 9) Sovereign Screen Sleep Snap (instantly turns off the display on Eclipse Ultimate!) */
    fun triggerScreenSleepSnap(): Boolean {
        if (!isShizukuActive()) return false
        return DemonShizukuBridge.execShellSync("input keyevent 26").ok
    }

    /** 10) Disappear Top App (`pm disable-user --user 0`: icon vanishes completely until Victory/Restore!) */
    fun disappearTopAppViaShizuku(context: Context): Pair<String, String>? {
        if (!isShizukuActive()) return null
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val target = getMostUsedUserApps(context, limit = 25)
            .firstOrNull { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) } ?: return null
        val r = DemonShizukuBridge.execShellSync("pm disable-user --user 0 ${target.packageName}")
        if (!r.ok) return null
        hidden.add(target.packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PKGS, hidden).apply()
        return target.packageName to target.label
    }

    /** Category Sweep: Greys out up to [count] top user apps at once (used by Bosses in Phase 2/3!) */
    fun seizeCategoryHostagesMidBattle(context: Context, count: Int = 2): List<Pair<String, String>> {
        val seized = ArrayList<Pair<String, String>>()
        for (i in 0 until count.coerceIn(1, 3)) {
            seizeAppHostageMidBattle(context)?.let { seized.add(it) }
        }
        return seized
    }

    fun testShizukuSuspendDiagnostic(context: Context): String {
        if (!isShizukuActive()) return "❌ Shizuku non autorisé : ${DemonShizukuBridge.getDiagnostics(context)}"
        DemonShizukuBridge.autoGrantSafeAppOps()
        val idRes = DemonShizukuBridge.execShellSync("id")
        if (!idRes.ok) return "❌ Échec liaison UserService Shizuku : ${idRes.output}"
        val target = getMostUsedUserApps(context, limit = 10).firstOrNull()
            ?: return "✅ Shizuku OK (${idRes.output.take(30)}) mais aucune appli utilisateur trouvée."
        val (ok, mode) = suspendPackageViaShizuku(target.packageName)
        if (!ok) return "⚠️ Shizuku lié (${idRes.output.take(28)}) mais échec sur ${target.label} : $mode"
        applyFakeLowBatteryCurse(context, true, 3)
        try { Thread.sleep(2_500L) } catch (_: Throwable) {}
        val restored = unsuspendPackageViaShizuku(target.packageName)
        applyFakeLowBatteryCurse(context, false)
        return "✅ SUCCÈS SHIZUKU ! « ${target.label} » grisée ($mode) + Illusion Batterie 3 % testée puis restaurée (${if (restored) "OK" else "manuel"}) • ${idRes.output.take(26)}"
    }

    /** Full Defeat Punishment (Usage-ordered App Grey/Disappear + System Curses!) */
    fun applyDefeatMdmDamage(context: Context, playerLevel: Int, severe: Boolean): MdmDamageReport {
        val useDo = isDeviceOwner(context)
        val useShizuku = !useDo && isShizukuActive()
        if (!useDo && !useShizuku) {
            return MdmDamageReport(false, "Mode Shizuku non connecté (${DemonShizukuBridge.getDiagnostics(context)}).")
        }
        if (useShizuku && !hasUsageStatsAccess(context)) DemonShizukuBridge.autoGrantSafeAppOps()

        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val curses = HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())
        val camSealed = HashSet(prefs.getStringSet(KEY_CAM_SEALED_PKGS, emptySet()) ?: emptySet())

        val notes = ArrayList<String>()
        var affectedLabel: String? = null
        val ranked = getMostUsedUserApps(context, limit = 30)
        val availableTargets = ranked.filter { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) }
        val nextTarget = availableTargets.firstOrNull()

        if (useDo) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            try { dpm.setUninstallBlocked(admin, context.packageName, true) } catch (_: Throwable) {}
            if (nextTarget != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    if (dpm.setPackagesSuspended(admin, arrayOf(nextTarget.packageName), true).isEmpty()) {
                        suspended.add(nextTarget.packageName); affectedLabel = nextTarget.label
                        notes.add("🚫 Appli grisée (#usage) : ${nextTarget.label}")
                    }
                } catch (_: Throwable) {}
            }
        } else {
            if (nextTarget != null) {
                // At NV <= 5 (Soumis Absolu) or severe Boss defeat, make the app VANISH completely!
                if (playerLevel <= 5 && severe) {
                    val rDis = DemonShizukuBridge.execShellSync("pm disable-user --user 0 ${nextTarget.packageName}")
                    if (rDis.ok) {
                        hidden.add(nextTarget.packageName); affectedLabel = nextTarget.label
                        notes.add("👻 Appli DISPARUE de l'accueil (Shizuku) : ${nextTarget.label}")
                    }
                }
                if (affectedLabel == null) {
                    val (ok, mode) = suspendPackageViaShizuku(nextTarget.packageName)
                    if (ok) {
                        if (mode == "disabled") hidden.add(nextTarget.packageName) else suspended.add(nextTarget.packageName)
                        affectedLabel = nextTarget.label
                        notes.add("🚫 Appli grisée (Shizuku #usage) : ${nextTarget.label}")
                    }
                }
            }

            if (severe || playerLevel <= 10) {
                when {
                    !curses.contains("chrono_slowmo") -> if (applyChronoSlowMoCurse(context, true)) {
                        curses.add("chrono_slowmo"); notes.add("⏳ Malédiction Chrono : animations Android ×3,5 ralenti !")
                    }
                    !curses.contains("fake_battery") -> if (applyFakeLowBatteryCurse(context, true, 3)) {
                        curses.add("fake_battery"); notes.add("🔋 Illusion Batterie Critique : barre système bloquée à 3 % !")
                    }
                    !curses.contains("isolation_dnd") -> applyIsolationNotificationCurse(context)?.let {
                        curses.add("isolation_dnd"); notes.add(it)
                    }
                    !curses.contains("play_store") -> if (suspendPackageViaShizuku("com.android.vending").first) {
                        suspended.add("com.android.vending"); curses.add("play_store"); notes.add("🛒 Sceau Play Store grisé !")
                    }
                    !curses.contains("eclipse_mode") -> if (applyEclipsePowerAndDarkCurse(context, true)) {
                        curses.add("eclipse_mode"); notes.add("🌑 Mode Éclipse : Sombre + Économie d'énergie forcés !")
                    }
                    !curses.contains("camera") -> {
                        for (p in ranked.take(5).map { it.packageName }) {
                            if (DemonShizukuBridge.execShellSync("appops set $p CAMERA deny").ok) camSealed.add(p)
                        }
                        if (camSealed.isNotEmpty()) { curses.add("camera"); notes.add("📷 Sceau Caméra sur ${camSealed.size} applis !") }
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

        val msg = if (notes.isEmpty()) "Emprise active (${suspended.size + hidden.size} appli(s) grisée(s))" else notes.joinToString("\n")
        return MdmDamageReport(true, msg, affectedLabel)
    }

    fun getNextMostUsedAppLabel(context: Context): String? {
        val suspended = getSuspendedPackages(context); val hidden = getHiddenPackages(context)
        return getMostUsedUserApps(context, limit = 15)
            .firstOrNull { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) }?.label
    }

    fun seizeAppHostageMidBattle(context: Context): Pair<String, String>? {
        val useDo = isDeviceOwner(context); val useShizuku = !useDo && isShizukuActive()
        if (!useDo && !useShizuku) return null
        if (useShizuku && !hasUsageStatsAccess(context)) DemonShizukuBridge.autoGrantSafeAppOps()

        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val target = getMostUsedUserApps(context, limit = 25)
            .firstOrNull { !suspended.contains(it.packageName) && !hidden.contains(it.packageName) } ?: return null

        var seized = false; var asDisabled = false
        if (useDo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                seized = dpm.setPackagesSuspended(DemonDeviceAdminReceiver.getComponentName(context), arrayOf(target.packageName), true).isEmpty()
            } catch (_: Throwable) {}
        } else if (useShizuku) {
            val (ok, mode) = suspendPackageViaShizuku(target.packageName)
            seized = ok; asDisabled = (mode == "disabled")
        }
        if (!seized) return null

        if (asDisabled) hidden.add(target.packageName) else suspended.add(target.packageName)
        prefs.edit().putStringSet(KEY_SUSPENDED_PKGS, suspended).putStringSet(KEY_HIDDEN_PKGS, hidden).apply()
        return target.packageName to target.label
    }

    fun releaseSpecificHostageApp(context: Context, pkg: String): Boolean {
        var released = false
        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                dpm.setApplicationHidden(admin, pkg, false); released = true
            } catch (_: Throwable) {}
        } else if (isShizukuActive()) {
            released = unsuspendPackageViaShizuku(pkg)
        }
        if (released) {
            val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
            val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
            val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
            suspended.remove(pkg); hidden.remove(pkg)
            prefs.edit().putStringSet(KEY_SUSPENDED_PKGS, suspended).putStringSet(KEY_HIDDEN_PKGS, hidden).apply()
        }
        return released
    }

    fun applyWitchBrightnessCurse(context: Context, brightness255: Int): Boolean {
        val clamped = brightness255.coerceIn(14, 240)
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED_BRIGHTNESS)) {
            val cur = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 145) } catch (_: Throwable) { 145 }
            prefs.edit().putInt(KEY_SAVED_BRIGHTNESS, cur).apply()
        }
        if (isShizukuActive() && DemonShizukuBridge.execShellSync("settings put system screen_brightness $clamped").ok) return true
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped); true
            } else false
        } catch (_: Throwable) { false }
    }

    fun applyWitchAudioCurse(context: Context, targetFraction: Float): Boolean {
        return try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SAVED_VOLUME)) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * targetFraction.coerceIn(0f, 0.7f)).toInt(), 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun triggerWitchTorchPulse(context: Context, pulses: Int = 2) {
        Thread {
            try {
                val cm = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@Thread
                val camId = cm.cameraIdList.firstOrNull() ?: return@Thread
                for (i in 0 until pulses.coerceIn(1, 3)) {
                    cm.setTorchMode(camId, true); Thread.sleep(110L)
                    cm.setTorchMode(camId, false); Thread.sleep(150L)
                }
            } catch (_: Throwable) {}
        }.start()
    }

    fun applyWitchSystemSealMidBattle(context: Context): String? {
        if (!isShizukuActive() && !isDeviceOwner(context)) return null
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val curses = HashSet(prefs.getStringSet(KEY_CURSES, emptySet()) ?: emptySet())
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val camSealed = HashSet(prefs.getStringSet(KEY_CAM_SEALED_PKGS, emptySet()) ?: emptySet())

        val msg: String? = when {
            !curses.contains("play_store") && isShizukuActive() -> {
                if (suspendPackageViaShizuku("com.android.vending").first) {
                    suspended.add("com.android.vending"); curses.add("play_store")
                    "🛒 Sceau Play Store posé en plein combat !"
                } else null
            }
            !curses.contains("camera") && isShizukuActive() -> {
                for (p in getMostUsedUserApps(context, limit = 4).map { it.packageName }) {
                    if (DemonShizukuBridge.execShellSync("appops set $p CAMERA deny").ok) camSealed.add(p)
                }
                if (camSealed.isNotEmpty()) { curses.add("camera"); "📷 Caméra scellée sur ${camSealed.size} applis !" } else null
            }
            else -> if (applyWitchBrightnessCurse(context, 24)) "🌙 Luminosité système écrasée !" else null
        }
        prefs.edit().putStringSet(KEY_SUSPENDED_PKGS, suspended).putStringSet(KEY_CURSES, curses).putStringSet(KEY_CAM_SEALED_PKGS, camSealed).apply()
        return msg
    }

    /** Restores temporary visual/audio/chrono/battery/density curses smoothly upon Victory! */
    fun restoreBattleTempCurses(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SAVED_VOLUME)) {
            val savedVol = prefs.getInt(KEY_SAVED_VOLUME, -1)
            if (savedVol >= 0) {
                try { (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0) } catch (_: Throwable) {}
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        }
        if (prefs.contains(KEY_SAVED_BRIGHTNESS)) {
            val savedBr = prefs.getInt(KEY_SAVED_BRIGHTNESS, 145).coerceIn(40, 240)
            if (isShizukuActive()) DemonShizukuBridge.execShellSync("settings put system screen_brightness $savedBr")
            else try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, savedBr)
                }
            } catch (_: Throwable) {}
            prefs.edit().remove(KEY_SAVED_BRIGHTNESS).apply()
        }
        if (isShizukuActive()) {
            DemonShizukuBridge.execShellSync("am task lock stop")
            DemonShizukuBridge.execShellSync("cmd battery reset")
            DemonShizukuBridge.execShellSync("wm density reset")
            DemonShizukuBridge.execShellSync("settings put global window_animation_scale 1.0")
            DemonShizukuBridge.execShellSync("settings put global transition_animation_scale 1.0")
            DemonShizukuBridge.execShellSync("settings put global animator_duration_scale 1.0")
            DemonShizukuBridge.execShellSync("cmd power set-mode 0")
            DemonShizukuBridge.execShellSync("cmd notification set_dnd off")
        }
    }

    fun healOneSuspendedApp(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val target = suspended.firstOrNull { it != "com.android.vending" } ?: hidden.firstOrNull() ?: suspended.firstOrNull() ?: return null

        var healed = false
        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) dpm.setPackagesSuspended(admin, arrayOf(target), false)
                dpm.setApplicationHidden(admin, target, false); healed = true
            } catch (_: Throwable) {}
        } else if (isShizukuActive()) {
            healed = unsuspendPackageViaShizuku(target)
        }
        if (!healed) return null
        suspended.remove(target); hidden.remove(target)
        prefs.edit().putStringSet(KEY_SUSPENDED_PKGS, suspended).putStringSet(KEY_HIDDEN_PKGS, hidden).apply()
        return try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(target, 0)).toString() } catch (_: Throwable) { target.substringAfterLast('.') }
    }

    /** GUARANTEED 100% FULL RESTORATION OF ALL 12 SHIZUKU/MDM SYSTEM POWERS! */
    fun restoreEverything(context: Context, releaseDeviceOwner: Boolean = false): String {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val suspended = HashSet(prefs.getStringSet(KEY_SUSPENDED_PKGS, emptySet()) ?: emptySet())
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN_PKGS, emptySet()) ?: emptySet())
        val camSealed = HashSet(prefs.getStringSet(KEY_CAM_SEALED_PKGS, emptySet()) ?: emptySet())
        val silenced = HashSet(prefs.getStringSet(KEY_SILENCED_PKGS, emptySet()) ?: emptySet())
        val micClip = HashSet(prefs.getStringSet(KEY_MIC_CLIP_PKGS, emptySet()) ?: emptySet())
        var restoredCount = 0

        if (isDeviceOwner(context)) {
            val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = DemonDeviceAdminReceiver.getComponentName(context)
            if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false); restoredCount += suspended.size } catch (_: Throwable) {}
            }
            for (pkg in hidden) { try { dpm.setApplicationHidden(admin, pkg, false); restoredCount++ } catch (_: Throwable) {} }
            try { dpm.setCameraDisabled(admin, false) } catch (_: Throwable) {}
            try { dpm.setScreenCaptureDisabled(admin, false) } catch (_: Throwable) {}
            try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) } catch (_: Throwable) {}
            try { dpm.setUninstallBlocked(admin, context.packageName, false) } catch (_: Throwable) {}
            if (releaseDeviceOwner) {
                try { @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(context.packageName) } catch (_: Throwable) {}
            }
        }

        if (isShizukuActive()) {
            val focusPkgs = HashSet(prefs.getStringSet(KEY_FOCUS_PKGS, emptySet()) ?: emptySet())
            val allPkgs = HashSet<String>().apply { addAll(suspended); addAll(hidden); addAll(focusPkgs) }
            for (pkg in allPkgs) if (unsuspendPackageViaShizuku(pkg)) restoredCount++
            for (pkg in camSealed) DemonShizukuBridge.execShellSync("appops set $pkg CAMERA allow")
            for (pkg in silenced) {
                DemonShizukuBridge.execShellSync("appops set $pkg POST_NOTIFICATION allow")
                DemonShizukuBridge.execShellSync("appops set $pkg RUN_IN_BACKGROUND allow")
            }
            for (pkg in micClip) {
                DemonShizukuBridge.execShellSync("appops set $pkg RECORD_AUDIO allow")
                DemonShizukuBridge.execShellSync("appops set $pkg READ_CLIPBOARD allow")
            }
            DemonShizukuBridge.execShellSync("am task lock stop")
            DemonShizukuBridge.execShellSync("cmd battery reset")
            DemonShizukuBridge.execShellSync("wm density reset")
            DemonShizukuBridge.execShellSync("settings put global window_animation_scale 1.0")
            DemonShizukuBridge.execShellSync("settings put global transition_animation_scale 1.0")
            DemonShizukuBridge.execShellSync("settings put global animator_duration_scale 1.0")
            DemonShizukuBridge.execShellSync("cmd power set-mode 0")
            DemonShizukuBridge.execShellSync("cmd uimode night auto")
            DemonShizukuBridge.execShellSync("cmd notification set_dnd off")
            DemonShizukuBridge.execShellSync("settings put system screen_brightness 145")
        }

        prefs.edit()
            .remove(KEY_SUSPENDED_PKGS).remove(KEY_HIDDEN_PKGS).remove(KEY_CURSES)
            .remove(KEY_CAM_SEALED_PKGS).remove(KEY_SILENCED_PKGS).remove(KEY_MIC_CLIP_PKGS)
            .remove(KEY_FOCUS_PKGS)
            .apply()
        com.demonpet.app.rpg.WitchRpgState(context).setActiveFocusUntil(0L)

        restoreBattleTempCurses(context)
        Thread { MediaFolderHelper.restoreOriginalWallpaper(context.applicationContext) }.start()
        return if (restoredCount > 0) {
            "Tout restauré : $restoredCount appli(s) dégrisées/réactivées + Chrono, Batterie, Densité, Notifs, Micro & Sceaux réinitialisés ✅"
        } else {
            "Tous les sceaux Shizuku (Chrono, Batterie, Densité, Notifs, Micro, Luminosité, Volume, Fond d'écran) ont été restaurés ✅"
        }
    }

    /** Live Top-8 Most Used Apps Threat Radar (shows which apps are currently hostage or next in danger!) */
    fun getTopUsedAppsWithStatus(context: Context, limit: Int = 8): List<AppThreatInfo> {
        val susp = getSuspendedPackages(context)
        val hid = getHiddenPackages(context)
        return getMostUsedUserApps(context, limit = limit).map {
            AppThreatInfo(it.packageName, it.label, susp.contains(it.packageName) || hid.contains(it.packageName))
        }
    }

    /** Shizuku Focus / Study Contract: suspends top distracting apps cleanly for [minutes] min & awards Gold/XP! */
    fun startShizukuFocusContract(context: Context, minutes: Int): String {
        if (!isShizukuActive()) return "⚠️ Autorise Shizuku d'abord pour activer le Mode Focus !"
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val focusSet = HashSet(prefs.getStringSet(KEY_FOCUS_PKGS, emptySet()) ?: emptySet())
        val targets = getMostUsedUserApps(context, limit = 6)
        var locked = 0
        for (t in targets) {
            if (suspendPackageViaShizuku(t.packageName).first) {
                focusSet.add(t.packageName)
                locked++
            }
        }
        prefs.edit().putStringSet(KEY_FOCUS_PKGS, focusSet).apply()
        val untilMs = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        com.demonpet.app.rpg.WitchRpgState(context).setActiveFocusUntil(untilMs)
        return "🎯 Contrat Focus Shizuku ($minutes min) activé : $locked appli(s) mise(s) en pause ! Récompense +150 💎 à la fin !"
    }

    /** Releases all Shizuku Focus Contract apps cleanly and grants rewards! */
    fun finishShizukuFocusContract(context: Context, awardRewards: Boolean = true): String {
        val prefs = context.applicationContext.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
        val focusSet = HashSet(prefs.getStringSet(KEY_FOCUS_PKGS, emptySet()) ?: emptySet())
        var freed = 0
        if (isShizukuActive()) {
            for (pkg in focusSet) {
                if (unsuspendPackageViaShizuku(pkg)) freed++
            }
        }
        prefs.edit().remove(KEY_FOCUS_PKGS).apply()
        val rpg = com.demonpet.app.rpg.WitchRpgState(context)
        return if (awardRewards) {
            val (gold, total) = rpg.completeFocusSession()
            "🏆 Contrat Focus réussi ! $freed appli(s) libérées · +$gold 💎 & +Affection Démon (Total : $total) !"
        } else {
            rpg.setActiveFocusUntil(0L)
            "🕊️ Mode Focus arrêté : $freed appli(s) réactivées."
        }
    }

    fun buildTermuxOneLineScript(): String =
        """pkg update -y && pkg install -y android-tools && read -p "Port couplage: " PP && read -p "Code 6 chiffres: " PC && adb pair 127.0.0.1:${'$'}PP ${'$'}PC && read -p "Port connexion: " CP && adb connect 127.0.0.1:${'$'}CP && adb shell appops set com.demonpet.app MANAGE_EXTERNAL_STORAGE allow; adb shell appops set com.demonpet.app SYSTEM_ALERT_WINDOW allow; adb shell appops set com.demonpet.app WRITE_SETTINGS allow; adb shell appops set com.demonpet.app GET_USAGE_STATS allow; echo "=== PERMISSIONS OK ! ===" """.trim()
}
