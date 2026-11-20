package com.sentinelcam.app.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sentinelcam.app.storage.PortalPreferences
import java.util.Calendar

/**
 * Ultra-Low-Power Unified Enforcement Watchdog (v13):
 * - Lost Mode fullscreen contact lock
 * - Anti-Bypass guard (Settings / Play Store / Package Installer)
 * - Global Screen Pause & Scheduled Night Curfew
 * - School / Homework Whitelist Mode
 * - Timed Per-App Locks & Daily Per-App Quotas
 *
 * Battery guarantees:
 * - 0% CPU when screen is OFF.
 * - 0% CPU when NO rules or schedules are enabled.
 */
class AppLockMonitor(
    context: Context,
    private val prefs: PortalPreferences
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    @Volatile
    private var screenInteractive = true

    private var lastShieldLaunchMs = 0L
    private var receiverRegistered = false
    private var cachedUsageSec: Map<String, Long> = emptyMap()
    private var lastUsageRefreshMs = 0L
    private var lastUsageDayKey = ""
    private var lastObservedFgPkg: String? = null
    private val lastBypassLogByPkg = HashMap<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    lastObservedFgPkg = null
                    ScreenTimeHelper.notifyScreenOff()
                    handler.removeCallbacks(pollRunnable)
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    refreshSchedule()
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started || !screenInteractive) return

            val now = System.currentTimeMillis()
            val lostEnabled = prefs.lostModeEnabled
            val antiBypass = prefs.blockSettingsAndStore
            val pausedUntil = prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L
            val curfewConfigured = prefs.curfewEnabled
            val curfewActive = prefs.isCurfewActiveNow(now)
            val schoolUntil = prefs.schoolModeUntilMs.takeIf { it > now } ?: 0L
            val rules = prefs.getActiveAppLockRules(now)
            val quotas = prefs.getAppQuotas()

            if (!lostEnabled && !antiBypass && pausedUntil == 0L && !curfewConfigured &&
                schoolUntil == 0L && rules.isEmpty() && quotas.isEmpty()
            ) {
                // Zero rules configured -> 0% CPU idle
                return
            }

            if (lostEnabled) {
                val fgPkg = if (ScreenTimeHelper.hasUsageAccess(appContext)) {
                    ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
                } else {
                    null
                }
                val isCallApp = fgPkg != null && (
                    fgPkg.contains("dialer", true) ||
                        fgPkg.contains("telecom", true) ||
                        fgPkg.contains("incallui", true)
                    )
                if (fgPkg != appContext.packageName && !isCallApp) {
                    launchShield(
                        mode = AppBlockActivity.MODE_LOST,
                        pkg = fgPkg.orEmpty(),
                        appName = "Téléphone perdu",
                        untilMs = now + 86_400_000L
                    )
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
                return
            }

            if (ScreenTimeHelper.hasUsageAccess(appContext)) {
                val fgPkg = ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
                if (!fgPkg.isNullOrEmpty()) {
                    // 1. Anti-Bypass (Settings / Play Store / Package Installer)
                    if (antiBypass &&
                        prefs.settingsUnlockUntilMs <= now &&
                        ScreenTimeHelper.isSettingsOrStorePackage(fgPkg)
                    ) {
                        logBlockedOnce(fgPkg, "bypass", "Accès bloqué (Anti-contournement)", fgPkg)
                        launchShield(
                            mode = AppBlockActivity.MODE_BYPASS,
                            pkg = fgPkg,
                            appName = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg),
                            untilMs = now + 3_600_000L
                        )
                    } else if (!ScreenTimeHelper.isProtectedSystemPackage(appContext, fgPkg)) {
                        when {
                            // 2. Global Pause
                            pausedUntil > now -> {
                                launchShield(
                                    mode = AppBlockActivity.MODE_PAUSE,
                                    pkg = fgPkg,
                                    appName = "Appareil",
                                    untilMs = pausedUntil
                                )
                            }

                            // 3. Scheduled Curfew / Night Mode
                            curfewActive -> {
                                launchShield(
                                    mode = AppBlockActivity.MODE_CURFEW,
                                    pkg = fgPkg,
                                    appName = "Mode Nuit",
                                    untilMs = prefs.getCurfewEndTimestampMs(now)
                                )
                            }

                            // 4. School / Homework Whitelist Mode
                            schoolUntil > now && !prefs.getSchoolAllowedPackages().contains(fgPkg) -> {
                                val name = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg)
                                launchShield(
                                    mode = AppBlockActivity.MODE_SCHOOL,
                                    pkg = fgPkg,
                                    appName = name,
                                    untilMs = schoolUntil
                                )
                            }

                            else -> {
                                // 5. Timed Per-App Lock
                                val hit = rules.firstOrNull { it.packageName == fgPkg }
                                if (hit != null) {
                                    launchShield(
                                        mode = AppBlockActivity.MODE_APP_LOCK,
                                        pkg = hit.packageName,
                                        appName = hit.appName,
                                        untilMs = hit.blockedUntilMs
                                    )
                                } else if (quotas.isNotEmpty()) {
                                    // 6. Daily Per-App Quota (persists automatically every day until disabled;
                                    //    daily usage resets cleanly at midnight!)
                                    val q = quotas.firstOrNull { it.packageName == fgPkg }
                                    if (q != null) {
                                        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
                                        val switchedApp = fgPkg != lastObservedFgPkg
                                        if (todayKey != lastUsageDayKey || switchedApp || (now - lastUsageRefreshMs) > 25_000L) {
                                            cachedUsageSec = ScreenTimeHelper.computeTodayUsageSecByPackage(appContext)
                                            lastUsageRefreshMs = now
                                            lastUsageDayKey = todayKey
                                        }
                                        val usedSec = cachedUsageSec[fgPkg] ?: 0L
                                        val allowedSec = (q.dailyLimitMinutes + prefs.getDailyBonusMinutes(fgPkg)) * 60L
                                        if (usedSec >= allowedSec) {
                                            launchShield(
                                                mode = AppBlockActivity.MODE_QUOTA,
                                                pkg = q.packageName,
                                                appName = q.appName,
                                                untilMs = endOfTodayMs(now)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    lastObservedFgPkg = fgPkg
                }
            }

            // Adaptive battery-friendly cadence:
            // - 30s when only inactive curfew is configured
            // - 1.8s when only passive rules (anti-bypass / quotas) are watching
            // - 1.0s when active enforcement (pause / curfew / school / lost / app locks) is running
            val nextDelay = when {
                !lostEnabled && !antiBypass && pausedUntil == 0L &&
                    !curfewActive && schoolUntil == 0L && rules.isEmpty() && quotas.isEmpty() && curfewConfigured -> 30_000L
                !lostEnabled && pausedUntil == 0L && !curfewActive && schoolUntil == 0L && rules.isEmpty() -> 1_800L
                else -> POLL_INTERVAL_MS
            }
            handler.postDelayed(this, nextDelay)
        }
    }

    fun start() {
        if (started) {
            refreshSchedule()
            return
        }
        started = true
        screenInteractive = try {
            powerManager.isInteractive
        } catch (_: Throwable) {
            true
        }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                appContext.registerReceiver(screenReceiver, filter)
                receiverRegistered = true
            } catch (_: Throwable) {
            }
        }
        refreshSchedule()
    }

    fun refreshSchedule() {
        handler.removeCallbacks(pollRunnable)
        lastUsageRefreshMs = 0L
        if (!started || !screenInteractive) return
        val now = System.currentTimeMillis()
        val hasWork = prefs.lostModeEnabled ||
            prefs.blockSettingsAndStore ||
            prefs.screenPausedUntilMs > now ||
            prefs.curfewEnabled ||
            prefs.schoolModeUntilMs > now ||
            prefs.getActiveAppLockRules(now).isNotEmpty() ||
            prefs.getAppQuotas().isNotEmpty()
        if (hasWork) {
            handler.post(pollRunnable)
        }
    }

    fun destroy() {
        started = false
        handler.removeCallbacks(pollRunnable)
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(screenReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
    }

    private fun logBlockedOnce(pkg: String, kind: String, title: String, detailPkg: String) {
        val now = System.currentTimeMillis()
        val last = lastBypassLogByPkg[pkg] ?: 0L
        if (now - last < 90_000L) return
        lastBypassLogByPkg[pkg] = now
        val appLabel = ScreenTimeHelper.resolveAppName(appContext.packageManager, detailPkg)
        prefs.appendSecurityEvent(kind, title, "Tentative d’ouverture de $appLabel bloquée.")
    }

    private fun launchShield(mode: String, pkg: String, appName: String, untilMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastShieldLaunchMs < 1_300L) return
        lastShieldLaunchMs = now
        try {
            val intent = Intent(appContext, AppBlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(AppBlockActivity.EXTRA_MODE, mode)
                putExtra(AppBlockActivity.EXTRA_PACKAGE, pkg)
                putExtra(AppBlockActivity.EXTRA_APP_NAME, appName)
                putExtra(AppBlockActivity.EXTRA_UNTIL_MS, untilMs)
            }
            appContext.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun endOfTodayMs(nowMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_100L
    }
}
