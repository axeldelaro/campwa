package com.sentinelcam.app.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sentinelcam.app.selfcontrol.HabitSensorMonitor
import com.sentinelcam.app.selfcontrol.MindfulFrictionActivity
import com.sentinelcam.app.selfcontrol.SelfControlStore
import com.sentinelcam.app.selfcontrol.WindDownVeilOverlay
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
    private val scStore = SelfControlStore(appContext)
    private val habitSensors = HabitSensorMonitor(appContext) {
        handler.post { evaluateNow() }
    }

    @Volatile
    private var started = false

    @Volatile
    private var screenInteractive = true

    private var lastShieldLaunchMs = 0L
    private var lastFrictionLaunchMs = 0L
    private var receiverRegistered = false
    private var cachedUsageSec: Map<String, Long> = emptyMap()
    private var lastUsageRefreshMs = 0L
    private var lastUsageDayKey = ""
    private var lastObservedFgPkg: String? = null
    private var activeSessionPkg: String? = null
    private var activeSessionStartMs: Long = 0L
    private val lastBypassLogByPkg = HashMap<String, Long>()
    private val lastAttemptLogByPkg = HashMap<String, Long>()
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
                    activeSessionPkg = null
                    activeSessionStartMs = 0L
                    habitSensors.syncSensors(screenInteractive = false)
                    WindDownVeilOverlay.hide()
                    ScreenTimeHelper.notifyScreenOff()
                    handler.removeCallbacks(pollRunnable)
                    handler.removeCallbacks(quotaDeadlineRunnable)
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    habitSensors.syncSensors(screenInteractive = true)
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
        // #31 Read-Only Screen Freeze overlay sync & v28 Wind-Down Sleep Veil sync
        ReadOnlyScreenOverlay.syncWithUntilMs(appContext, prefs.readOnlyUntilMs)
        WindDownVeilOverlay.sync(appContext, scStore.selfControlEnabled && scStore.isWindDownWindowNow(now))
        habitSensors.syncSensors(screenInteractive = true)

        val lostEnabled = prefs.lostModeEnabled
        val parentFreeActive = prefs.parentFreeModeUntilMs > now && !lostEnabled && !scStore.selfControlEnabled
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

        // v28 Self-Control Geo-Vault rules
        val scOn = scStore.selfControlEnabled
        val scSealedLock = scStore.isSettingsProtectedNow(now)
        val scDetoxUntil = if (scOn) scStore.nuclearDetoxUntilMs.takeIf { it > now } ?: 0L else 0L
        val scBedtimeActive = scOn && scStore.isBedtimeActiveNow(now)
        val scBedDetectorUntil = if (scOn) scStore.bedDetectorLockUntilMs.takeIf { it > now } ?: 0L else 0L
        val scMorningPending = scOn && scStore.isMorningChallengePending(now)
        val scActiveSchedule = if (scOn) scStore.getActiveScheduleNow(now) else null
        val scActiveFocusGeo = if (scOn) scStore.getActiveFocusGeofenceMode(now) else null
        val scBlockedPkgs = if (scOn) scStore.getBlockedAppPackages() else emptySet()
        val scQuarantined = if (scOn) scStore.getQuarantinedPackages() else emptySet()
        val scSessionQuotas = if (scOn) scStore.getSessionQuotas() else emptyList()
        val scMaxOpens = if (scOn) scStore.getMaxDailyOpens() else emptyMap()
        val scFrictionPkgs = if (scOn) scStore.getFrictionPackages() else emptySet()
        val scHasAnyWork = scOn && (
            scSealedLock || scDetoxUntil > 0L || scBedtimeActive || scBedDetectorUntil > 0L ||
                scMorningPending || scActiveSchedule != null || scActiveFocusGeo != null ||
                scBlockedPkgs.isNotEmpty() || scQuarantined.isNotEmpty() || scSessionQuotas.isNotEmpty() ||
                scMaxOpens.isNotEmpty() || scFrictionPkgs.isNotEmpty() || scStore.bedtimeEnabled ||
                scStore.getWeeklySchedules().isNotEmpty()
            )

        if (!lostEnabled && !antiBypass && pausedUntil == 0L && !curfewConfigured &&
            schoolUntil == 0L && reelsCooldownUntil == 0L && rules.isEmpty() &&
            quotas.isEmpty() && groupQuotas.isEmpty() && !drivingActive && !scHasAnyWork
        ) {
            return // 0% CPU idle
        }

        // #59: Build the full candidate set of visible packages (Split-Screen / Floating Windows / Foreground)
        // Prioritize instant 0ms Accessibility window detection before slower UsageStats fallback!
        val candidates = LinkedHashSet<String>()
        val a11yPkg = if (a11yActive) SentinelAccessibilityService.lastWindowPackage else null
        if (!a11yPkg.isNullOrEmpty()) {
            candidates.add(a11yPkg)
        }
        if (a11yActive && prefs.multiWindowGuardEnabled) {
            candidates.addAll(SentinelAccessibilityService.lastVisiblePackages)
        }
        val usageFg = if (ScreenTimeHelper.hasUsageAccess(appContext)) {
            ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
        } else {
            null
        }
        if (!usageFg.isNullOrEmpty()) {
            candidates.add(usageFg)
        }
        val detectedFg = a11yPkg?.takeIf { it.isNotEmpty() } ?: usageFg

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
            // 1. Anti-Bypass: Play Store / App Markets are ALWAYS blocked when antiBypass is ON!
            // Android Settings is deferred to Surgical Settings PIN when Accessibility is active.
            val pkgLower = fgPkg.lowercase(Locale.US)
            val isAppStore = pkgLower == "com.android.vending" ||
                pkgLower.contains("galaxyapps") ||
                pkgLower.contains("appmarket") ||
                pkgLower.contains("xiaomi.market") ||
                pkgLower.contains("heytap.market")
            if (antiBypass &&
                prefs.settingsUnlockUntilMs <= now &&
                ScreenTimeHelper.isSettingsOrStorePackage(fgPkg) &&
                (isAppStore || !surgicalSettingsHandledByA11y)
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

            // === v28 SELF-CONTROL GEO-VAULT ENFORCEMENT ===
            if (scOn) {
                val essentials = scStore.getNuclearWhitelistPackages()
                val fgLabel = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg)

                // A. Bypass Apps Blocker (3rd-party VPNs, Fake GPS, App Cloners/Vaults)
                if (scStore.blockBypassApps && scSealedLock && SelfControlStore.KNOWN_BYPASS_PACKAGES.contains(fgPkg.lowercase(Locale.US))) {
                    recordSelfControlAttemptOnce(fgPkg, "bypass_app")
                    launchShield(AppBlockActivity.MODE_GEO_SEALED, fgPkg, "$fgLabel (Outil de contournement)", now + 3_600_000L)
                    break
                }

                // B. Auto-Quarantined Newly Installed Apps
                if (scQuarantined.contains(fgPkg)) {
                    recordSelfControlAttemptOnce(fgPkg, "quarantine")
                    launchShield(AppBlockActivity.MODE_QUARANTINE, fgPkg, fgLabel, now + 3_600_000L)
                    break
                }

                // C. Nuclear Detox Mode (blocks all non-essential apps!)
                if (scDetoxUntil > now && !essentials.contains(fgPkg)) {
                    recordSelfControlAttemptOnce(fgPkg, "detox")
                    launchShield(AppBlockActivity.MODE_DETOX, fgPkg, fgLabel, scDetoxUntil)
                    break
                }

                // D. Bedtime / Under-Blanket Night Lock
                if ((scBedtimeActive || scBedDetectorUntil > now) && !essentials.contains(fgPkg)) {
                    val endMs = if (scBedDetectorUntil > now) scBedDetectorUntil else scStore.getBedtimeEndTimestampMs(now)
                    recordSelfControlAttemptOnce(fgPkg, if (scBedDetectorUntil > now) "bed_detector" else "bedtime")
                    launchShield(AppBlockActivity.MODE_BED_NIGHT, fgPkg, fgLabel, endMs)
                    break
                }

                // E. Morning Anti-Bed Step Challenge
                if (scMorningPending && (SelfControlStore.DEFAULT_SOCIAL_PACKAGES.contains(fgPkg) || scBlockedPkgs.contains(fgPkg))) {
                    recordSelfControlAttemptOnce(fgPkg, "morning_steps")
                    launchShield(AppBlockActivity.MODE_MORNING_STEPS, fgPkg, fgLabel, now + 3_600_000L)
                    break
                }

                // F. Reverse Auto-Focus Geofences (Work / Library / Night Home)
                if (scActiveFocusGeo != null && !essentials.contains(fgPkg) &&
                    (SelfControlStore.DEFAULT_SOCIAL_PACKAGES.contains(fgPkg) || scBlockedPkgs.contains(fgPkg))
                ) {
                    recordSelfControlAttemptOnce(fgPkg, "focus_geofence")
                    launchShield(AppBlockActivity.MODE_SCHEDULE, fgPkg, "Zone Focus : ${scActiveFocusGeo.name}", now + 1_800_000L)
                    break
                }

                // G. Weekly Multi-Slot Schedules
                if (scActiveSchedule != null && !essentials.contains(fgPkg)) {
                    val hitSch = when (scActiveSchedule.mode) {
                        "focus", "sleep" -> true
                        else -> scActiveSchedule.blockedPackages.contains(fgPkg) || SelfControlStore.DEFAULT_SOCIAL_PACKAGES.contains(fgPkg)
                    }
                    if (hitSch) {
                        recordSelfControlAttemptOnce(fgPkg, "schedule")
                        launchShield(AppBlockActivity.MODE_SCHEDULE, fgPkg, "Créneau : ${scActiveSchedule.name}", now + 1_800_000L)
                        break
                    }
                }

                // H. Permanent Self-Control Blocked Apps
                if (scBlockedPkgs.contains(fgPkg)) {
                    recordSelfControlAttemptOnce(fgPkg, "blocked_app")
                    launchShield(AppBlockActivity.MODE_GEO_SEALED, fgPkg, fgLabel, now + 3_600_000L)
                    break
                }

                // I. Max Daily Opens Quota
                val maxOpens = scMaxOpens[fgPkg]
                if (maxOpens != null && maxOpens > 0) {
                    val switchedIntoApp = fgPkg != lastObservedFgPkg
                    val opensCount = if (switchedIntoApp) scStore.incrementDailyOpen(fgPkg) else scStore.getDailyOpensToday(fgPkg)
                    if (opensCount > maxOpens) {
                        recordSelfControlAttemptOnce(fgPkg, "max_opens")
                        launchShield(AppBlockActivity.MODE_OPENS, fgPkg, "$fgLabel ($maxOpens ouvertures max)", endOfTodayMs(now))
                        break
                    }
                }

                // J. Per-Session Max Duration + Mandatory Cooldown
                val sq = scSessionQuotas.firstOrNull { it.packageName == fgPkg }
                if (sq != null) {
                    val coolUntil = scStore.getSessionCooldownUntilMs(fgPkg)
                    if (coolUntil > now) {
                        recordSelfControlAttemptOnce(fgPkg, "session_cooldown")
                        launchShield(AppBlockActivity.MODE_SESSION, fgPkg, sq.appName, coolUntil)
                        break
                    }
                    if (activeSessionPkg != fgPkg) {
                        activeSessionPkg = fgPkg
                        activeSessionStartMs = now
                    }
                    val elapsedMs = now - activeSessionStartMs
                    val maxMs = sq.maxSessionMin * 60_000L
                    if (elapsedMs >= maxMs) {
                        val until = now + sq.cooldownMin * 60_000L
                        scStore.setSessionCooldownUntilMs(fgPkg, until)
                        activeSessionPkg = null
                        activeSessionStartMs = 0L
                        recordSelfControlAttemptOnce(fgPkg, "session_limit")
                        launchShield(AppBlockActivity.MODE_SESSION, fgPkg, sq.appName, until)
                        break
                    } else {
                        val remMs = (maxMs - elapsedMs).coerceIn(3_000L, 600_000L)
                        nextQuotaDeadlineMs = minOf(nextQuotaDeadlineMs ?: Long.MAX_VALUE, remMs)
                    }
                }

                // K. Conscious Friction Pause Screen (Breathing 15-60s before tempting app opens)
                if (scFrictionPkgs.contains(fgPkg) &&
                    fgPkg != lastObservedFgPkg &&
                    !scStore.hasActiveFrictionPass(fgPkg, now)
                ) {
                    launchFrictionPause(fgPkg, fgLabel, scStore.frictionDelaySec)
                    break
                }
            }

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
                    val earnedStepBonusMin = if (scOn) scStore.getEarnedBonusMinutesToday() else 0
                    if (q != null) {
                        val usedSec = cachedUsageSec[fgPkg] ?: 0L
                        val allowedSec = (q.dailyLimitMinutes + prefs.getDailyBonusMinutes(fgPkg) + earnedStepBonusMin) * 60L
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
            if (detectedFg != activeSessionPkg && detectedFg != appContext.packageName) {
                activeSessionPkg = null
                activeSessionStartMs = 0L
            }
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
            prefs.getAppGroupQuotas().isNotEmpty() ||
            scStore.selfControlEnabled
        habitSensors.syncSensors(screenInteractive = true)
        WindDownVeilOverlay.sync(appContext, scStore.selfControlEnabled && scStore.isWindDownWindowNow(now))
        if (hasWork) {
            handler.post(pollRunnable)
        }
    }

    fun destroy() {
        started = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(quotaDeadlineRunnable)
        ReadOnlyScreenOverlay.hide()
        WindDownVeilOverlay.hide()
        habitSensors.destroy()
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

    private fun recordSelfControlAttemptOnce(pkg: String, reason: String) {
        val now = System.currentTimeMillis()
        val key = "$pkg:$reason"
        val prev = lastAttemptLogByPkg[key] ?: 0L
        if (now - prev < 60_000L) return
        lastAttemptLogByPkg[key] = now
        scStore.recordReclaimedAction(pkg, reason, estimatedSavedMin = 8)
    }

    private fun launchFrictionPause(pkg: String, appName: String, delaySec: Int) {
        val now = System.currentTimeMillis()
        if (now - lastFrictionLaunchMs < 2_500L) return
        lastFrictionLaunchMs = now
        try {
            val intent = Intent(appContext, MindfulFrictionActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(MindfulFrictionActivity.EXTRA_PACKAGE, pkg)
                putExtra(MindfulFrictionActivity.EXTRA_APP_NAME, appName)
                putExtra(MindfulFrictionActivity.EXTRA_DELAY_SEC, delaySec)
            }
            appContext.startActivity(intent)
        } catch (_: Throwable) {
        }
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
