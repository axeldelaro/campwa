package com.sentinelcam.app.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sentinelcam.app.storage.PortalPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * #20 Ultra-Low-Power 100% Event-Driven Enforcement Engine (v27):
 * - Zero aggressive polling when Accessibility is active: evaluates instantly on `onWindowChanged()`
 *   and schedules ONE exact quota deadline timer for the foreground app's remaining time!
 * - #59 Multi-Window / Split-Screen / Floating Pop-up inspection across ALL visible `windows`.
 * - #31 Syncs Read-Only Screen Freeze overlay.
 * - #46 Enforces Reels / Shorts / TikTok vertical swipe cooldown pauses.
 * - #58 Respects Surgical Settings PIN Mode (when enabled with Accessibility, lets Wi-Fi/Sound pass
 *   while Accessibility immediately blocks SentinelCam/Admin/A11y/Reset/Date-Time pages).
 * - Kidslox Parent Free Mode (`parentFreeModeUntilMs > now`): temporarily suspends locks cleanly!
 * - 0% CPU when screen is OFF or when no rules are configured.
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
    private var a11yListenerRegistered = false

    private val quotaDeadlineRunnable = Runnable {
        if (!started || !screenInteractive) return@Runnable
        lastUsageRefreshMs = 0L
        evaluateNow()
    }

    private val a11yWindowListener = object : SentinelAccessibilityService.WindowEventListener {
        override fun onWindowChanged(packageName: String) {
            if (!started || !screenInteractive) return
            handler.removeCallbacks(quotaDeadlineRunnable)
            if (packageName != lastObservedFgPkg || prefs.multiWindowGuardEnabled) {
                handler.removeCallbacks(pollRunnable)
                handler.post(pollRunnable)
            }
        }

        override fun onScrollDeltaPx(deltaYPx: Int) {}
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    lastObservedFgPkg = null
                    ScreenTimeHelper.notifyScreenOff()
                    handler.removeCallbacks(pollRunnable)
                    handler.removeCallbacks(quotaDeadlineRunnable)
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
            evaluateNow()
        }
    }

    private fun evaluateNow() {
        if (!started || !screenInteractive) return

        val now = System.currentTimeMillis()
        // #31 Read-Only Screen Freeze overlay sync
        ReadOnlyScreenOverlay.syncWithUntilMs(appContext, prefs.readOnlyUntilMs)

        val lostEnabled = prefs.lostModeEnabled
        val parentFreeActive = prefs.parentFreeModeUntilMs > now && !lostEnabled
        if (parentFreeActive) {
            handler.removeCallbacks(quotaDeadlineRunnable)
            val delay = (prefs.parentFreeModeUntilMs - now).coerceIn(5_000L, 60_000L)
            handler.postDelayed(pollRunnable, delay)
            return
        }

        val antiBypass = prefs.blockSettingsAndStore
        val pausedUntil = prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L
        val curfewConfigured = prefs.curfewEnabled
        val curfewActive = prefs.isCurfewActiveNow(now)
        val schoolUntil = prefs.schoolModeUntilMs.takeIf { it > now } ?: 0L
        val reelsCooldownUntil = prefs.reelsCooldownUntilMs.takeIf { it > now } ?: 0L
        val rules = prefs.getActiveAppLockRules(now)
        val quotas = prefs.getAppQuotas()
        val groupQuotas = prefs.getAppGroupQuotas()
        val drivingActive = DrivingSafetyController.isActive && DrivingSafetyController.isSuspendAppsEnabled(appContext)
        val a11yActive = SentinelAccessibilityService.isActiveNow()

        if (!lostEnabled && !antiBypass && pausedUntil == 0L && !curfewConfigured &&
            schoolUntil == 0L && reelsCooldownUntil == 0L && rules.isEmpty() &&
            quotas.isEmpty() && groupQuotas.isEmpty() && !drivingActive
        ) {
            return // 0% CPU idle
        }

        // #59: Build the full candidate set of visible packages (Split-Screen / Floating Windows / Foreground)
        val candidates = LinkedHashSet<String>()
        if (a11yActive && prefs.multiWindowGuardEnabled) {
            candidates.addAll(SentinelAccessibilityService.lastVisiblePackages)
        }
        val detectedFg = if (ScreenTimeHelper.hasUsageAccess(appContext)) {
            ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
        } else {
            SentinelAccessibilityService.lastWindowPackage
        }
        if (!detectedFg.isNullOrEmpty()) {
            candidates.add(detectedFg)
        }

        if (lostEnabled) {
            val isCallApp = candidates.any {
                it.contains("dialer", true) || it.contains("telecom", true) || it.contains("incallui", true)
            }
            val onlySelf = candidates.isNotEmpty() && candidates.all { it == appContext.packageName }
            if (!onlySelf && !isCallApp) {
                launchShield(
                    mode = AppBlockActivity.MODE_LOST,
                    pkg = detectedFg.orEmpty(),
                    appName = "Téléphone perdu",
                    untilMs = now + 86_400_000L
                )
            }
            handler.postDelayed(pollRunnable, if (a11yActive) 8_000L else POLL_INTERVAL_MS)
            return
        }

        var nextQuotaDeadlineMs: Long? = null
        val surgicalSettingsHandledByA11y = a11yActive && prefs.surgicalSettingsPinEnabled

        for (fgPkg in candidates) {
            // 1. Anti-Bypass (full block if surgical mode is OFF or Accessibility is not running)
            if (antiBypass &&
                !surgicalSettingsHandledByA11y &&
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
                break
            }

            if (ScreenTimeHelper.isProtectedSystemPackage(appContext, fgPkg)) continue

            // 2. Global Pause (Kidslox Locked Mode)
            if (pausedUntil > now) {
                launchShield(
                    mode = AppBlockActivity.MODE_PAUSE,
                    pkg = fgPkg,
                    appName = "Appareil",
                    untilMs = pausedUntil
                )
                break
            }

            // 3. Scheduled Night Curfew
            if (curfewActive) {
                launchShield(
                    mode = AppBlockActivity.MODE_CURFEW,
                    pkg = fgPkg,
                    appName = "Mode Nuit",
                    untilMs = prefs.getCurfewEndTimestampMs(now)
                )
                break
            }

            // 4. School / Homework Whitelist Mode
            if (schoolUntil > now && !prefs.getSchoolAllowedPackages().contains(fgPkg)) {
                val name = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg)
                launchShield(
                    mode = AppBlockActivity.MODE_SCHOOL,
                    pkg = fgPkg,
                    appName = name,
                    untilMs = schoolUntil
                )
                break
            }

            // 5. #46 Reels / Shorts Cooldown Pause
            if (reelsCooldownUntil > now && SentinelAccessibilityService.SHORT_VIDEO_PACKAGES.contains(fgPkg.lowercase(Locale.US))) {
                launchShield(
                    mode = AppBlockActivity.MODE_REELS,
                    pkg = fgPkg,
                    appName = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg),
                    untilMs = reelsCooldownUntil
                )
                break
            }

            // 6. Timed Per-App Lock
            val hit = rules.firstOrNull { it.packageName == fgPkg }
            if (hit != null) {
                launchShield(
                    mode = AppBlockActivity.MODE_APP_LOCK,
                    pkg = hit.packageName,
                    appName = hit.appName,
                    untilMs = hit.blockedUntilMs
                )
                break
            }

            // 7. Driving Safety Mode fallback
            if (drivingActive && DrivingSafetyController.isPackageBlockedByDriving(appContext, fgPkg)) {
                val thr = DrivingSafetyController.getThresholdKmh(appContext)
                launchShield(
                    mode = AppBlockActivity.MODE_APP_LOCK,
                    pkg = fgPkg,
                    appName = "Mode Trajet (> $thr km/h)",
                    untilMs = now + 60_000L
                )
                break
            }

            // 8. #20 Exact Event-Driven Daily Per-App & Group Quotas (Zero polling loop!)
            if (quotas.isNotEmpty() || groupQuotas.isNotEmpty()) {
                val q = quotas.firstOrNull { it.packageName == fgPkg }
                val gHit = groupQuotas.firstOrNull { it.packages.contains(fgPkg) }
                if (q != null || gHit != null) {
                    val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
                    val switchedApp = fgPkg != lastObservedFgPkg
                    if (todayKey != lastUsageDayKey || switchedApp || (now - lastUsageRefreshMs) > 30_000L) {
                        cachedUsageSec = ScreenTimeHelper.computeTodayUsageSecByPackage(appContext)
                        lastUsageRefreshMs = now
                        lastUsageDayKey = todayKey
                    }

                    var blocked = false
                    if (q != null) {
                        val usedSec = cachedUsageSec[fgPkg] ?: 0L
                        val allowedSec = (q.dailyLimitMinutes + prefs.getDailyBonusMinutes(fgPkg)) * 60L
                        val remSec = allowedSec - usedSec
                        if (remSec <= 0L) {
                            blocked = true
                            launchShield(
                                mode = AppBlockActivity.MODE_QUOTA,
                                pkg = q.packageName,
                                appName = q.appName,
                                untilMs = endOfTodayMs(now)
                            )
                        } else {
                            val remMs = (remSec * 1_000L).coerceIn(4_000L, 600_000L)
                            nextQuotaDeadlineMs = minOf(nextQuotaDeadlineMs ?: Long.MAX_VALUE, remMs)
                        }
                    }

                    if (!blocked && gHit != null) {
                        var groupUsedSec = 0L
                        for (p in gHit.packages) groupUsedSec += cachedUsageSec[p] ?: 0L
                        val groupKey = "group:${gHit.id}"
                        val allowedSec = (gHit.dailyLimitMinutes + prefs.getDailyBonusMinutes(groupKey)) * 60L
                        val remSec = allowedSec - groupUsedSec
                        if (remSec <= 0L) {
                            blocked = true
                            launchShield(
                                mode = AppBlockActivity.MODE_QUOTA,
                                pkg = groupKey,
                                appName = "${gHit.name} (groupe)",
                                untilMs = endOfTodayMs(now)
                            )
                        } else {
                            val remMs = (remSec * 1_000L).coerceIn(4_000L, 600_000L)
                            nextQuotaDeadlineMs = minOf(nextQuotaDeadlineMs ?: Long.MAX_VALUE, remMs)
                        }
                    }
                    if (blocked) break
                }
            }
        }

        if (!detectedFg.isNullOrEmpty()) {
            lastObservedFgPkg = detectedFg
        }

        // #20 Schedule exact single quota deadline timer (cancels redundant UsageStats polling!)
        handler.removeCallbacks(quotaDeadlineRunnable)
        if (nextQuotaDeadlineMs != null) {
            handler.postDelayed(quotaDeadlineRunnable, nextQuotaDeadlineMs)
        }

        // Event-driven eco cadence: 18s safety net when Accessibility gives instant 0ms window triggers!
        val nextDelay = when {
            !lostEnabled && !antiBypass && pausedUntil == 0L &&
                !curfewActive && schoolUntil == 0L && reelsCooldownUntil == 0L &&
                rules.isEmpty() && quotas.isEmpty() && groupQuotas.isEmpty() && curfewConfigured -> 45_000L
            a11yActive -> 18_000L
            !lostEnabled && pausedUntil == 0L && !curfewActive && schoolUntil == 0L && rules.isEmpty() -> 3_500L
            else -> POLL_INTERVAL_MS
        }
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, nextDelay)
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
        if (!a11yListenerRegistered) {
            SentinelAccessibilityService.addWindowListener(a11yWindowListener)
            a11yListenerRegistered = true
        }
        refreshSchedule()
    }

    fun refreshSchedule() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(quotaDeadlineRunnable)
        lastUsageRefreshMs = 0L
        ReadOnlyScreenOverlay.syncWithUntilMs(appContext, prefs.readOnlyUntilMs)
        if (!started || !screenInteractive) return
        val now = System.currentTimeMillis()
        val hasWork = prefs.lostModeEnabled ||
            prefs.blockSettingsAndStore ||
            prefs.screenPausedUntilMs > now ||
            prefs.curfewEnabled ||
            prefs.schoolModeUntilMs > now ||
            prefs.reelsCooldownUntilMs > now ||
            prefs.parentFreeModeUntilMs > now ||
            prefs.getActiveAppLockRules(now).isNotEmpty() ||
            prefs.getAppQuotas().isNotEmpty() ||
            prefs.getAppGroupQuotas().isNotEmpty()
        if (hasWork) {
            handler.post(pollRunnable)
        }
    }

    fun destroy() {
        started = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(quotaDeadlineRunnable)
        ReadOnlyScreenOverlay.hide()
        if (a11yListenerRegistered) {
            SentinelAccessibilityService.removeWindowListener(a11yWindowListener)
            a11yListenerRegistered = false
        }
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
        private const val POLL_INTERVAL_MS = 1_200L
    }
}
