package com.sentinelcam.app.control

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.sentinelcam.app.receiver.SentinelDeviceAdminReceiver
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Native Android Screen Time (today + 7-day history), Screen Lock,
 * Anti-Bypass package detection, and Parental Rule snapshot builder.
 */
object ScreenTimeHelper {

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(context)
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(SentinelDeviceAdminReceiver.getComponentName(context))
        } catch (_: Throwable) {
            false
        }
    }

    fun lockScreenNow(context: Context): Pair<Boolean, String?> {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = SentinelDeviceAdminReceiver.getComponentName(context)
            if (!dpm.isAdminActive(comp)) {
                return Pair(
                    false,
                    "Activez la Protection Administrateur sur le smartphone pour autoriser le verrouillage d’écran."
                )
            }
            dpm.lockNow()
            Pair(true, null)
        } catch (se: SecurityException) {
            Pair(
                false,
                "Désactivez puis réactivez la Protection Administrateur sur le smartphone une fois pour valider le verrouillage d’écran."
            )
        } catch (t: Throwable) {
            Pair(false, "Verrouillage impossible : ${t.message}")
        }
    }

    fun getDefaultLauncherPackage(context: Context): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            info?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    fun isProtectedSystemPackage(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        if (pkg == getDefaultLauncherPackage(context)) return true
        val lower = pkg.lowercase(Locale.US)
        return lower == "com.android.systemui" ||
            lower == "android" ||
            lower.contains("dialer") ||
            lower.contains("telecom") ||
            lower.contains("incallui") ||
            lower.contains("emergency") ||
            lower.contains("launcher") ||
            lower.contains("permissioncontroller") ||
            lower.contains("notification") ||
            lower.contains("statusbar") ||
            lower.contains("keyguard") ||
            lower.contains("inputmethod") ||
            lower.contains("gboard") ||
            lower.contains("honeyboard") ||
            lower.contains("swiftkey") ||
            lower.contains("wellbeing")
    }

    fun isSettingsOrStorePackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower == "com.android.settings" ||
            lower == "com.android.vending" ||
            lower == "com.android.packageinstaller" ||
            lower == "com.google.android.packageinstaller" ||
            lower == "com.samsung.android.lool" ||
            lower == "com.miui.securitycenter" ||
            lower == "com.coloros.safecenter"
    }

    private val fgLock = Any()
    private var cachedFgPkg: String? = null
    private var cachedFgSinceMs: Long = 0L
    private var lastFgQueryMs: Long = 0L

    fun notifyScreenOff() {
        synchronized(fgLock) {
            cachedFgPkg = null
            cachedFgSinceMs = 0L
            lastFgQueryMs = System.currentTimeMillis()
        }
    }

    /**
     * Ultra-low-CPU stateful foreground app detector (v25):
     * - Cold-starts with a 4-hour lookback so apps open >60s (e.g. YouTube, TikTok, Roblox)
     *   are NEVER missed by daily quotas or auto-screenshots!
     * - Subsequent calls query only the tiny incremental delta since the last tick (saving ~80% CPU).
     */
    fun detectCurrentForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val now = System.currentTimeMillis()
        synchronized(fgLock) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val lookbackFrom = if (lastFgQueryMs > 0L && (now - lastFgQueryMs) < 30 * 60_000L) {
                    (lastFgQueryMs - 2_000L).coerceAtLeast(0L)
                } else {
                    (now - 4L * 3600_000L).coerceAtLeast(0L)
                }
                val events = usm.queryEvents(lookbackFrom, now)
                val event = UsageEvents.Event()
                var currentPkg = cachedFgPkg
                var currentSince = cachedFgSinceMs

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED,
                        @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            if (!pkg.isNullOrEmpty()) {
                                if (pkg != currentPkg) {
                                    currentPkg = pkg
                                    currentSince = event.timeStamp
                                } else if (currentSince == 0L) {
                                    currentSince = event.timeStamp
                                }
                            }
                        }

                        UsageEvents.Event.ACTIVITY_PAUSED,
                        @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            if (!pkg.isNullOrEmpty() && pkg == currentPkg && event.timeStamp >= currentSince) {
                                currentPkg = null
                                currentSince = 0L
                            }
                        }

                        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                        UsageEvents.Event.KEYGUARD_SHOWN -> {
                            currentPkg = null
                            currentSince = 0L
                        }
                    }
                }
                cachedFgPkg = currentPkg
                cachedFgSinceMs = currentSince
                lastFgQueryMs = now
                return currentPkg
            } catch (_: Throwable) {
                return cachedFgPkg
            }
        }
    }

    fun computeTodayUsageSecByPackage(context: Context): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val now = System.currentTimeMillis()
        val usageMsByPkg = HashMap<String, Long>()
        try {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = cal.timeInMillis
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(startOfDay, now)
            val ev = UsageEvents.Event()
            val activeStartByPkg = HashMap<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                val ts = ev.timeStamp
                val isResumed = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                val isPaused = ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)

                if (isResumed) {
                    activeStartByPkg[pkg] = ts
                } else if (isPaused) {
                    val started = activeStartByPkg.remove(pkg)
                    if (started != null && ts > started) {
                        val delta = (ts - started).coerceAtMost(4 * 3600_000L)
                        usageMsByPkg[pkg] = (usageMsByPkg[pkg] ?: 0L) + delta
                    }
                } else if (ev.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    for ((openPkg, started) in activeStartByPkg) {
                        if (ts > started) {
                            val delta = (ts - started).coerceAtMost(4 * 3600_000L)
                            usageMsByPkg[openPkg] = (usageMsByPkg[openPkg] ?: 0L) + delta
                        }
                    }
                    activeStartByPkg.clear()
                }
            }

            for ((pkg, started) in activeStartByPkg) {
                if (now > started) {
                    val delta = (now - started).coerceAtMost(4 * 3600_000L)
                    usageMsByPkg[pkg] = (usageMsByPkg[pkg] ?: 0L) + delta
                }
            }

            // If an app stayed open continuously across midnight (resumed yesterday, still foreground today),
            // make sure its live time since 00:00:00 is counted!
            val activeFg = synchronized(fgLock) { Pair(cachedFgPkg, cachedFgSinceMs) }
            val fgPkg = activeFg.first
            if (!fgPkg.isNullOrEmpty() && !activeStartByPkg.containsKey(fgPkg) && (usageMsByPkg[fgPkg] ?: 0L) == 0L) {
                val effectiveStart = maxOf(startOfDay, activeFg.second)
                if (now > effectiveStart) {
                    usageMsByPkg[fgPkg] = (now - effectiveStart).coerceAtMost(4 * 3600_000L)
                }
            }
        } catch (_: Throwable) {
        }
        return usageMsByPkg.mapValues { it.value / 1000L }
    }

    private fun read7DayHistory(context: Context, todayTotalSec: Long): JSONArray {
        val out = JSONArray()
        val dayFmt = SimpleDateFormat("EEE", Locale.FRENCH)
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val usm = if (hasUsageAccess(context)) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } else {
            null
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -6)
        }

        for (dayIdx in 0 until 7) {
            val dayStart = cal.timeInMillis
            val dayEnd = dayStart + 86_400_000L - 1L
            val isToday = dayIdx == 6

            var totalSec = if (isToday) todayTotalSec else 0L
            if (!isToday && usm != null) {
                try {
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd)
                    var sumMs = 0L
                    for (s in stats) {
                        val pkg = s.packageName ?: continue
                        if (isProtectedSystemPackage(context, pkg)) continue
                        if (s.firstTimeStamp >= dayStart - 3_600_000L && s.lastTimeUsed <= dayEnd + 3_600_000L) {
                            sumMs += s.totalTimeInForeground.coerceAtMost(14 * 3600_000L)
                        }
                    }
                    totalSec = (sumMs / 1000L).coerceAtMost(20 * 3600L)
                } catch (_: Throwable) {
                }
            }

            val dateObj = Date(dayStart)
            out.put(JSONObject().apply {
                put("dateKey", keyFmt.format(dateObj))
                put("dayLabel", dayFmt.format(dateObj).replace(".", "").replaceFirstChar { it.uppercase() })
                put("totalSeconds", totalSec)
                put("isToday", isToday)
            })
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /**
     * Full parental control + screen time + security state report.
     */
    fun readScreenTimeReport(context: Context, prefs: PortalPreferences): JSONObject {
        val now = System.currentTimeMillis()
        val usageGranted = hasUsageAccess(context)
        val overlayGranted = hasOverlayPermission(context)
        val adminActive = isDeviceAdminActive(context)
        val activeRules = prefs.getActiveAppLockRules(now).associateBy { it.packageName }
        val quotas = prefs.getAppQuotas().associateBy { it.packageName }
        val screenPausedUntil = prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L
        val schoolUntil = prefs.schoolModeUntilMs.takeIf { it > now } ?: 0L
        val schoolAllowed = prefs.getSchoolAllowedPackages()

        val pm = context.packageManager
        val launcherPkg = getDefaultLauncherPackage(context)

        val appLabels = LinkedHashMap<String, String>()
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg == launcherPkg) continue
                val label = try {
                    ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                } catch (_: Throwable) {
                    pkg
                }
                if (label.isNotEmpty()) {
                    appLabels[pkg] = label
                }
            }
        } catch (_: Throwable) {
        }

        val usageMsByPkg = HashMap<String, Long>()
        val lastUsedByPkg = HashMap<String, Long>()
        var currentFgPkg: String? = null
        var currentFgTs = 0L

        if (usageGranted) {
            try {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val events = usm.queryEvents(startOfDay, now)
                val ev = UsageEvents.Event()
                val activeStartByPkg = HashMap<String, Long>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    val pkg = ev.packageName ?: continue
                    val ts = ev.timeStamp
                    val isResumed = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    val isPaused = ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)

                    if (isResumed) {
                        activeStartByPkg[pkg] = ts
                        lastUsedByPkg[pkg] = ts
                        if (ts >= currentFgTs) {
                            currentFgTs = ts
                            currentFgPkg = pkg
                        }
                    } else if (isPaused) {
                        val started = activeStartByPkg.remove(pkg)
                        if (started != null && ts > started) {
                            val delta = (ts - started).coerceAtMost(4 * 3600_000L)
                            usageMsByPkg[pkg] = (usageMsByPkg[pkg] ?: 0L) + delta
                        }
                        lastUsedByPkg[pkg] = ts
                    }
                }

                for ((pkg, started) in activeStartByPkg) {
                    if (now > started) {
                        val delta = (now - started).coerceAtMost(2 * 3600_000L)
                        usageMsByPkg[pkg] = (usageMsByPkg[pkg] ?: 0L) + delta
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val allPkgs = LinkedHashSet<String>()
        allPkgs.addAll(activeRules.keys)
        allPkgs.addAll(quotas.keys)
        allPkgs.addAll(appLabels.keys)
        for ((pkg, ms) in usageMsByPkg) {
            if (ms >= 15_000L && !isProtectedSystemPackage(context, pkg)) {
                allPkgs.add(pkg)
            }
        }

        data class AppRow(
            val pkg: String,
            val name: String,
            val usageSec: Long,
            val lastUsedTs: Long,
            val blockedUntilMs: Long,
            val dailyLimitMinutes: Int,
            val bonusMinutesToday: Int,
            val quotaExceeded: Boolean,
            val schoolAllowed: Boolean
        )

        val rows = ArrayList<AppRow>()
        var totalScreenTimeSec = 0L

        for (pkg in allPkgs) {
            if (isProtectedSystemPackage(context, pkg)) continue
            val usageSec = (usageMsByPkg[pkg] ?: 0L) / 1000L
            totalScreenTimeSec += usageSec
            val name = appLabels[pkg] ?: resolveAppName(pm, pkg)
            val blockedUntil = activeRules[pkg]?.blockedUntilMs ?: 0L
            val limitMin = quotas[pkg]?.dailyLimitMinutes ?: 0
            val bonusMin = if (limitMin > 0) prefs.getDailyBonusMinutes(pkg) else 0
            val exceeded = limitMin > 0 && usageSec >= (limitMin + bonusMin) * 60L
            rows.add(
                AppRow(
                    pkg = pkg,
                    name = name,
                    usageSec = usageSec,
                    lastUsedTs = lastUsedByPkg[pkg] ?: 0L,
                    blockedUntilMs = blockedUntil,
                    dailyLimitMinutes = limitMin,
                    bonusMinutesToday = bonusMin,
                    quotaExceeded = exceeded,
                    schoolAllowed = schoolAllowed.contains(pkg)
                )
            )
        }

        rows.sortWith(
            compareByDescending<AppRow> { it.blockedUntilMs > now || it.quotaExceeded }
                .thenByDescending { it.dailyLimitMinutes > 0 }
                .thenByDescending { it.usageSec }
                .thenBy { it.name.lowercase(Locale.FRENCH) }
        )

        val appsArray = JSONArray()
        for (r in rows.take(120)) {
            appsArray.put(
                JSONObject().apply {
                    put("packageName", r.pkg)
                    put("appName", r.name)
                    put("usageSecondsToday", r.usageSec)
                    put("lastUsedAt", r.lastUsedTs)
                    put("blockedUntilMs", r.blockedUntilMs)
                    put("dailyLimitMinutes", r.dailyLimitMinutes)
                    put("bonusMinutesToday", r.bonusMinutesToday)
                    put("quotaExceeded", r.quotaExceeded)
                    put("schoolAllowed", r.schoolAllowed)
                }
            )
        }

        val currentAppName = currentFgPkg?.let { pkg ->
            if (pkg == context.packageName) "SentinelCam"
            else appLabels[pkg] ?: resolveAppName(pm, pkg)
        }

        val curfewActive = prefs.isCurfewActiveNow(now)
        val curfewJson = JSONObject().apply {
            put("enabled", prefs.curfewEnabled)
            put("startMin", prefs.curfewStartMin)
            put("endMin", prefs.curfewEndMin)
            put("daysMask", prefs.curfewDaysMask)
            put("activeNow", curfewActive)
            put("endTs", if (curfewActive) prefs.getCurfewEndTimestampMs(now) else 0L)
        }

        val schoolAllowedArr = JSONArray()
        for (p in schoolAllowed) schoolAllowedArr.put(p)
        val schoolJson = JSONObject().apply {
            put("active", schoolUntil > now)
            put("untilMs", schoolUntil)
            put("allowedPackages", schoolAllowedArr)
        }

        val lostJson = JSONObject().apply {
            put("enabled", prefs.lostModeEnabled)
            put("message", prefs.lostModeMessage)
            put("phoneNumber", prefs.lostModePhone)
        }

        val reqsArr = JSONArray()
        for (req in prefs.getPendingTimeRequests(now)) {
            reqsArr.put(JSONObject().apply {
                put("id", req.id)
                put("packageName", req.packageName)
                put("appName", req.appName)
                put("requestedMinutes", req.requestedMinutes)
                put("createdAt", req.createdAt)
            })
        }

        val geoArr = JSONArray()
        for (z in prefs.getGeofences()) {
            geoArr.put(JSONObject().apply {
                put("id", z.id)
                put("name", z.name)
                put("latitude", z.latitude)
                put("longitude", z.longitude)
                put("radiusMeters", z.radiusMeters)
                put("inside", z.inside ?: JSONObject.NULL)
                put("lastTransitionTs", z.lastTransitionTs)
            })
        }

        val eventsArr = JSONArray()
        for (ev in prefs.getSecurityEvents().take(25)) {
            eventsArr.put(JSONObject().apply {
                put("id", ev.id)
                put("timestamp", ev.timestamp)
                put("kind", ev.kind)
                put("title", ev.title)
                put("detail", ev.detail)
                if (ev.photoBase64 != null) put("photoBase64", ev.photoBase64)
            })
        }

        // #1: App Group Quotas (combined pool across multiple apps, persistent every day!)
        val groupQuotasArr = JSONArray()
        for (g in prefs.getAppGroupQuotas()) {
            var groupUsedSec = 0L
            val pkgsArr = JSONArray()
            for (p in g.packages) {
                pkgsArr.put(p)
                groupUsedSec += (usageMsByPkg[p] ?: 0L) / 1000L
            }
            val bonusMin = prefs.getDailyBonusMinutes("group:${g.id}")
            val exceeded = groupUsedSec >= (g.dailyLimitMinutes + bonusMin) * 60L
            groupQuotasArr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("packages", pkgsArr)
                    put("dailyLimitMinutes", g.dailyLimitMinutes)
                    put("usedSecondsToday", groupUsedSec)
                    put("bonusMinutesToday", bonusMin)
                    put("quotaExceeded", exceeded)
                }
            )
        }

        // #6 Pickups & Compulsion + #2 Hourly Visual Timeline (linked with Auto-Screenshots!) + #4 Weekly Trends
        val (unlockMetricsJson, hourlyTimelineArr) = computeUnlockMetricsAndHourlyTimeline(
            context = context,
            pm = pm,
            appLabels = appLabels,
            now = now
        )
        val weeklyHistoryArr = read7DayHistory(context, totalScreenTimeSec)
        val weeklyTrendsJson = computeWeeklyTrends(
            context = context,
            todayTotalSec = totalScreenTimeSec,
            unlocksToday = unlockMetricsJson.optInt("unlocksToday", 0),
            now = now
        )

        return JSONObject().apply {
            put("hasUsageAccess", usageGranted)
            put("hasOverlayPermission", overlayGranted)
            put("deviceAdminActive", adminActive)
            put("totalScreenTimeSec", totalScreenTimeSec)
            put("currentPackage", currentFgPkg ?: JSONObject.NULL)
            put("currentAppName", currentAppName ?: JSONObject.NULL)
            put("screenPausedUntilMs", screenPausedUntil)
            put("apps", appsArray)
            put("groupQuotas", groupQuotasArr)
            put("weeklyHistory", weeklyHistoryArr)
            put("unlockMetrics", unlockMetricsJson)
            put("hourlyTimeline", hourlyTimelineArr)
            put("weeklyTrends", weeklyTrendsJson)
            put("curfew", curfewJson)
            put("schoolMode", schoolJson)
            put("blockSettingsAndStore", prefs.blockSettingsAndStore)
            put("lostMode", lostJson)
            put("pendingRequests", reqsArr)
            put("geofences", geoArr)
            put("securityEvents", eventsArr)
        }
    }

    /**
     * #6 Pickups & Compulsion Index + #2 Hour-by-Hour Visual Timeline (linked to Auto-Screenshots).
     */
    private fun computeUnlockMetricsAndHourlyTimeline(
        context: Context,
        pm: PackageManager,
        appLabels: Map<String, String>,
        now: Long
    ): Pair<JSONObject, JSONArray> {
        val unlocksByHour = IntArray(24)
        val hourTotalSec = LongArray(24)
        val hourAppsSec = Array(24) { HashMap<String, Long>() }
        var totalUnlocks = 0
        var firstUnlockTs = 0L
        var lastUnlockTs = 0L
        var sessionCount = 0
        var sessionTotalSec = 0L
        var shortChecksCount = 0

        if (hasUsageAccess(context)) {
            try {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val events = usm.queryEvents(startOfDay, now)
                val ev = UsageEvents.Event()
                val evCal = Calendar.getInstance()

                var screenOnStartMs = 0L
                var lastUnlockEventMs = 0L
                val activeAppStart = HashMap<String, Long>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    val ts = ev.timeStamp
                    val pkg = ev.packageName
                    evCal.timeInMillis = ts
                    val hr = evCal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)

                    when (ev.eventType) {
                        UsageEvents.Event.KEYGUARD_HIDDEN,
                        UsageEvents.Event.SCREEN_INTERACTIVE -> {
                            // Deduplicate SCREEN_INTERACTIVE + KEYGUARD_HIDDEN arriving within 8s of each other
                            if (ts - lastUnlockEventMs > 8_000L) {
                                lastUnlockEventMs = ts
                                totalUnlocks++
                                unlocksByHour[hr]++
                                if (firstUnlockTs == 0L) firstUnlockTs = ts
                                lastUnlockTs = ts
                                if (screenOnStartMs == 0L) screenOnStartMs = ts
                            }
                        }

                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            if (screenOnStartMs > 0L && ts > screenOnStartMs) {
                                val sessSec = ((ts - screenOnStartMs) / 1000L).coerceAtMost(4 * 3600L)
                                sessionCount++
                                sessionTotalSec += sessSec
                                if (sessSec in 2..45) shortChecksCount++
                            }
                            screenOnStartMs = 0L
                            // Close any open app intervals at screen off
                            for ((openPkg, startTs) in activeAppStart) {
                                if (ts > startTs && !isProtectedSystemPackage(context, openPkg)) {
                                    addIntervalToHours(startTs, ts, openPkg, hourTotalSec, hourAppsSec)
                                }
                            }
                            activeAppStart.clear()
                        }

                        UsageEvents.Event.ACTIVITY_RESUMED,
                        @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            if (!pkg.isNullOrEmpty() && !isProtectedSystemPackage(context, pkg)) {
                                activeAppStart[pkg] = ts
                                if (firstUnlockTs == 0L) firstUnlockTs = ts
                                if (ts > lastUnlockTs) lastUnlockTs = ts
                            }
                        }

                        UsageEvents.Event.ACTIVITY_PAUSED,
                        @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            if (!pkg.isNullOrEmpty()) {
                                val startTs = activeAppStart.remove(pkg)
                                if (startTs != null && ts > startTs && !isProtectedSystemPackage(context, pkg)) {
                                    addIntervalToHours(startTs, ts, pkg, hourTotalSec, hourAppsSec)
                                }
                            }
                        }
                    }
                }

                // Close still-active foreground apps up to now
                for ((openPkg, startTs) in activeAppStart) {
                    if (now > startTs && !isProtectedSystemPackage(context, openPkg)) {
                        addIntervalToHours(startTs, now, openPkg, hourTotalSec, hourAppsSec)
                    }
                }
                if (screenOnStartMs > 0L && now > screenOnStartMs) {
                    val sessSec = ((now - screenOnStartMs) / 1000L).coerceAtMost(4 * 3600L)
                    sessionCount++
                    sessionTotalSec += sessSec
                }
            } catch (_: Throwable) {
            }
        }

        val schoolHoursUnlocks =
            unlocksByHour[8] + unlocksByHour[9] + unlocksByHour[10] + unlocksByHour[11] +
                unlocksByHour[14] + unlocksByHour[15] + unlocksByHour[16]
        val avgSessionSec = if (sessionCount > 0) (sessionTotalSec / sessionCount).toInt() else 0

        // Compulsion score 0..100 combining total pickups + short compulsive micro-checks
        val compulsionRaw = ((totalUnlocks * 0.85) + (shortChecksCount * 1.4)).toInt().coerceIn(0, 100)
        val compulsionLabel = when {
            compulsionRaw >= 75 -> "Compulsif"
            compulsionRaw >= 50 -> "Élevé"
            compulsionRaw >= 25 -> "Modéré"
            else -> "Calme"
        }

        val byHourArr = JSONArray()
        for (h in 0 until 24) byHourArr.put(unlocksByHour[h])

        val unlockJson = JSONObject().apply {
            put("unlocksToday", totalUnlocks)
            put("unlocksByHour", byHourArr)
            put("firstUnlockTs", firstUnlockTs)
            put("lastUnlockTs", lastUnlockTs)
            put("avgSessionSeconds", avgSessionSec)
            put("shortChecksCount", shortChecksCount)
            put("schoolHoursUnlocks", schoolHoursUnlocks)
            put("compulsionIndex", compulsionRaw)
            put("compulsionLabel", compulsionLabel)
        }

        // Build Hour-by-Hour Visual Timeline linked with VisualTimelineManager captures!
        val capturesByHour = VisualTimelineManager.countCapturesByHourToday(context)
        val timelineArr = JSONArray()
        for (h in 0 until 24) {
            val topAppsArr = JSONArray()
            for ((pkg, sec) in hourAppsSec[h].entries.sortedByDescending { it.value }.take(4)) {
                if (sec < 10L) continue
                topAppsArr.put(
                    JSONObject().apply {
                        put("packageName", pkg)
                        put("appName", appLabels[pkg] ?: resolveAppName(pm, pkg))
                        put("seconds", sec)
                    }
                )
            }
            timelineArr.put(
                JSONObject().apply {
                    put("hour", h)
                    put("totalSeconds", hourTotalSec[h])
                    put("unlocks", unlocksByHour[h])
                    put("isNight", h >= 22 || h < 6)
                    put("isSchoolHour", h in 8..11 || h in 14..16)
                    put("captureCount", capturesByHour[h])
                    put("topApps", topAppsArr)
                }
            )
        }

        return Pair(unlockJson, timelineArr)
    }

    private fun addIntervalToHours(
        startTs: Long,
        endTs: Long,
        pkg: String,
        hourTotalSec: LongArray,
        hourAppsSec: Array<HashMap<String, Long>>
    ) {
        val safeEnd = minOf(endTs, startTs + 4 * 3600_000L)
        if (safeEnd <= startTs) return
        val cal = Calendar.getInstance()
        var cursor = startTs
        while (cursor < safeEnd) {
            cal.timeInMillis = cursor
            val hr = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val sliceEnd = minOf(safeEnd, cal.timeInMillis + 1L)
            val sec = ((sliceEnd - cursor) / 1000L).coerceAtLeast(0L)
            if (sec > 0L) {
                hourTotalSec[hr] = (hourTotalSec[hr] + sec).coerceAtMost(3600L)
                hourAppsSec[hr][pkg] = (hourAppsSec[hr][pkg] ?: 0L) + sec
            }
            cursor = sliceEnd
        }
    }

    /**
     * #4 Week-over-Week Trends: compares last 7 days vs previous 7 days (days -14..-7).
     */
    private fun computeWeeklyTrends(
        context: Context,
        todayTotalSec: Long,
        unlocksToday: Int,
        now: Long
    ): JSONObject {
        var thisWeekSec = todayTotalSec
        var prevWeekSec = 0L
        var nightThisWeekMin = 0
        var nightPrevWeekMin = 0
        var avgDailyUnlocks7d = unlocksToday

        if (hasUsageAccess(context)) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startToday = cal.timeInMillis

                // Sum days -6..-1 for this week, and days -13..-7 for previous week
                for (offset in 1..13) {
                    val dStart = startToday - offset * 86_400_000L
                    val dEnd = dStart + 86_400_000L - 1L
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dStart, dEnd)
                    var sumMs = 0L
                    for (s in stats) {
                        val pkg = s.packageName ?: continue
                        if (isProtectedSystemPackage(context, pkg)) continue
                        if (s.firstTimeStamp >= dStart - 3_600_000L && s.lastTimeUsed <= dEnd + 3_600_000L) {
                            sumMs += s.totalTimeInForeground.coerceAtMost(14 * 3600_000L)
                        }
                    }
                    val daySec = (sumMs / 1000L).coerceAtMost(20 * 3600L)
                    if (offset <= 6) {
                        thisWeekSec += daySec
                    } else {
                        prevWeekSec += daySec
                    }
                }

                // Estimate 7-day average unlocks + night activity using UsageEvents (Android retains ~7-10d of events)
                val sevenDaysAgo = now - 7L * 86_400_000L
                val fourteenDaysAgo = now - 14L * 86_400_000L
                val evs = usm.queryEvents(fourteenDaysAgo, now)
                val ev = UsageEvents.Event()
                val evCal = Calendar.getInstance()
                val activeStart = HashMap<String, Long>()
                var nightThisMs = 0L
                var nightPrevMs = 0L
                var unlocks7d = 0
                var lastUnlockMs = 0L

                while (evs.hasNextEvent()) {
                    evs.getNextEvent(ev)
                    val ts = ev.timeStamp
                    val pkg = ev.packageName
                    if ((ev.eventType == UsageEvents.Event.KEYGUARD_HIDDEN ||
                            ev.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) &&
                        ts >= sevenDaysAgo && (ts - lastUnlockMs > 8_000L)
                    ) {
                        lastUnlockMs = ts
                        unlocks7d++
                    }
                    if (pkg.isNullOrEmpty() || isProtectedSystemPackage(context, pkg)) continue
                    val isRes = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    val isPau = ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)
                    if (isRes) {
                        activeStart[pkg] = ts
                    } else if (isPau) {
                        val st = activeStart.remove(pkg) ?: continue
                        if (ts > st) {
                            evCal.timeInMillis = st
                            val hr = evCal.get(Calendar.HOUR_OF_DAY)
                            if (hr >= 22 || hr < 6) {
                                val delta = (ts - st).coerceAtMost(2 * 3600_000L)
                                if (st >= sevenDaysAgo) nightThisMs += delta else nightPrevMs += delta
                            }
                        }
                    }
                }
                nightThisWeekMin = (nightThisMs / 60_000L).toInt()
                nightPrevWeekMin = (nightPrevMs / 60_000L).toInt()
                if (unlocks7d > 0) avgDailyUnlocks7d = (unlocks7d / 7).coerceAtLeast(1)
            } catch (_: Throwable) {
            }
        }

        val thisWeekMin = (thisWeekSec / 60L).toInt()
        val prevWeekMin = (prevWeekSec / 60L).toInt()
        val screenDeltaPct: Int? = if (prevWeekMin >= 30) {
            (((thisWeekMin - prevWeekMin) * 100.0) / prevWeekMin.toDouble()).toInt().coerceIn(-99, 300)
        } else {
            null
        }
        val nightDeltaPct: Int? = if (nightPrevWeekMin >= 10) {
            (((nightThisWeekMin - nightPrevWeekMin) * 100.0) / nightPrevWeekMin.toDouble()).toInt().coerceIn(-99, 300)
        } else {
            null
        }
        val unlocksDeltaPct: Int? = if (avgDailyUnlocks7d >= 5) {
            (((unlocksToday - avgDailyUnlocks7d) * 100.0) / avgDailyUnlocks7d.toDouble()).toInt().coerceIn(-99, 300)
        } else {
            null
        }

        return JSONObject().apply {
            put("screenTimeThisWeekMin", thisWeekMin)
            put("screenTimePrevWeekMin", prevWeekMin)
            if (screenDeltaPct != null) put("screenTimeDeltaPct", screenDeltaPct)
            put("avgDailyMinutesThisWeek", thisWeekMin / 7)
            put("avgDailyMinutesPrevWeek", prevWeekMin / 7)
            put("nightMinutesThisWeek", nightThisWeekMin)
            put("nightMinutesPrevWeek", nightPrevWeekMin)
            if (nightDeltaPct != null) put("nightDeltaPct", nightDeltaPct)
            put("avgDailyUnlocks7d", avgDailyUnlocks7d)
            if (unlocksDeltaPct != null) put("unlocksDeltaPct", unlocksDeltaPct)
        }
    }

    fun resolveAppName(pm: PackageManager, packageName: String): String {
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString().trim().takeUnless { it.isEmpty() } ?: packageName
        } catch (_: Throwable) {
            packageName
        }
    }
}
