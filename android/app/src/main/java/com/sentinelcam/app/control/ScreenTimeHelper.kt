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
import java.util.Calendar

/**
 * Native Android Screen Time (UsageStatsManager), Screen Lock (DevicePolicyManager.lockNow),
 * and Launchable App Inventory helper.
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
        val lower = pkg.lowercase()
        return lower == "com.android.systemui" ||
            lower == "android" ||
            lower.contains("dialer") ||
            lower.contains("telecom") ||
            lower.contains("incallui") ||
            lower.contains("emergency") ||
            lower.contains("launcher")
    }

    /**
     * Ultra-fast foreground package detector using UsageEvents over the last few seconds.
     */
    fun detectCurrentForegroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTs = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    @Suppress("DEPRECATION") (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                if (isResume && event.timeStamp >= latestTs && !event.packageName.isNullOrEmpty()) {
                    latestTs = event.timeStamp
                    latestPkg = event.packageName
                }
            }
            latestPkg
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Computes exact today's screen time per app via UsageEvents (midnight -> now)
     * merged with all installed launchable apps and active timed block rules.
     */
    fun readScreenTimeReport(context: Context, prefs: PortalPreferences): JSONObject {
        val now = System.currentTimeMillis()
        val usageGranted = hasUsageAccess(context)
        val overlayGranted = hasOverlayPermission(context)
        val adminActive = isDeviceAdminActive(context)
        val activeRules = prefs.getActiveAppLockRules(now).associateBy { it.packageName }
        val screenPausedUntil = prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L

        val pm = context.packageManager
        val launcherPkg = getDefaultLauncherPackage(context)

        // 1. Enumerate all launchable installed apps
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

        // 2. Compute exact today foreground duration per package via UsageEvents
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

                // Add ongoing open session for the currently foreground app
                for ((pkg, started) in activeStartByPkg) {
                    if (now > started) {
                        val delta = (now - started).coerceAtMost(2 * 3600_000L)
                        usageMsByPkg[pkg] = (usageMsByPkg[pkg] ?: 0L) + delta
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Merge packages from usage + launchable apps + active lock rules
        val allPkgs = LinkedHashSet<String>()
        allPkgs.addAll(activeRules.keys)
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
            val blockedUntilMs: Long
        )

        val rows = ArrayList<AppRow>()
        var totalScreenTimeSec = 0L

        for (pkg in allPkgs) {
            if (isProtectedSystemPackage(context, pkg)) continue
            val usageSec = (usageMsByPkg[pkg] ?: 0L) / 1000L
            totalScreenTimeSec += usageSec
            val name = appLabels[pkg] ?: resolveAppName(pm, pkg)
            val blockedUntil = activeRules[pkg]?.blockedUntilMs ?: 0L
            rows.add(
                AppRow(
                    pkg = pkg,
                    name = name,
                    usageSec = usageSec,
                    lastUsedTs = lastUsedByPkg[pkg] ?: 0L,
                    blockedUntilMs = blockedUntil
                )
            )
        }

        // Sort: Blocked apps first, then by usage time descending, then alphabetical
        rows.sortWith(
            compareByDescending<AppRow> { it.blockedUntilMs > now }
                .thenByDescending { it.usageSec }
                .thenBy { it.name.lowercase() }
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
                }
            )
        }

        val currentAppName = currentFgPkg?.let { pkg ->
            if (pkg == context.packageName) "SentinelCam"
            else appLabels[pkg] ?: resolveAppName(pm, pkg)
        }

        return JSONObject().apply {
            put("hasUsageAccess", usageGranted)
            put("hasOverlayPermission", overlayGranted)
            put("deviceAdminActive", adminActive)
            put("totalScreenTimeSec", totalScreenTimeSec)
            put("currentPackage", currentFgPkg ?: JSONObject.NULL)
            put("currentAppName", currentAppName ?: JSONObject.NULL)
            put("screenPausedUntilMs", screenPausedUntil)
            put("apps", appsArray)
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
