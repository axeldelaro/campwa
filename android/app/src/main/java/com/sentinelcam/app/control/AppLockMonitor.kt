package com.sentinelcam.app.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Ultra-Low-Power Foreground App Lock & Screen Pause Watchdog.
 *
 * Battery guarantees:
 * - 0% CPU when NO rules are active (stops polling automatically as soon as timers expire).
 * - 0% CPU when screen is OFF (listens to ACTION_SCREEN_OFF / ACTION_SCREEN_ON).
 * - Works 100% offline once a timed lock rule has been stored in PortalPreferences.
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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
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
            val pausedUntil = prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L
            val rules = prefs.getActiveAppLockRules(now)

            if (pausedUntil == 0L && rules.isEmpty()) {
                // Nothing locked anymore -> go completely idle (0% CPU)
                return
            }

            if (ScreenTimeHelper.hasUsageAccess(appContext)) {
                val fgPkg = ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
                if (!fgPkg.isNullOrEmpty() && !ScreenTimeHelper.isProtectedSystemPackage(appContext, fgPkg)) {
                    if (pausedUntil > now) {
                        launchShield(
                            pkg = fgPkg,
                            appName = "Appareil",
                            globalPause = true,
                            untilMs = pausedUntil
                        )
                    } else {
                        val hit = rules.firstOrNull { it.packageName == fgPkg }
                        if (hit != null) {
                            launchShield(
                                pkg = hit.packageName,
                                appName = hit.appName,
                                globalPause = false,
                                untilMs = hit.blockedUntilMs
                            )
                        }
                    }
                }
            }

            handler.postDelayed(this, POLL_INTERVAL_MS)
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
        if (!started || !screenInteractive) return
        val now = System.currentTimeMillis()
        val hasWork = prefs.screenPausedUntilMs > now || prefs.getActiveAppLockRules(now).isNotEmpty()
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

    private fun launchShield(pkg: String, appName: String, globalPause: Boolean, untilMs: Long) {
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
                putExtra(AppBlockActivity.EXTRA_PACKAGE, pkg)
                putExtra(AppBlockActivity.EXTRA_APP_NAME, appName)
                putExtra(AppBlockActivity.EXTRA_GLOBAL_PAUSE, globalPause)
                putExtra(AppBlockActivity.EXTRA_UNTIL_MS, untilMs)
            }
            appContext.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_100L
    }
}
