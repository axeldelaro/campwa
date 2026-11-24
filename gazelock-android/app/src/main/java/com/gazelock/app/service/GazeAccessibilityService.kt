package com.gazelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gazelock.app.config.GazeConfig
import java.util.concurrent.CopyOnWriteArraySet

/**
 * AccessibilityService Host for GazeLock AI v3.0:
 * - Multi-window trusted `TYPE_ACCESSIBILITY_OVERLAY` host:
 *   1) Video + #7 Subliminal Window (either touch-unlocked Ghost 45-50% OR Full-Lock opaque!)
 *   2) Compact Draggable Ghost Control Pill Window (Ghost mode only — zero close button!)
 *   3) Fullscreen Interactive Unlock Portal Window (3-min 0.1s spam + pop-ups + Sliding/Reconstruction mini-games!)
 * - #8 Hardware Volume Key Interception via `onKeyEvent` (swallows VOLUME_DOWN & VOLUME_MUTE while Volume Lock is active!).
 * - #13 Surgical Anti-Escape in Ghost mode (allows normal apps while blocking Settings/Uninstall/Force-Stop/Security +15s!)
 *   and Total Anti-Escape in Full-Lock mode (also blocks Recents & Notification Shade!).
 */
class GazeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var powerManager: PowerManager? = null

    // Multi-Window Handles
    private var videoView: View? = null
    private var videoParams: WindowManager.LayoutParams? = null
    private var ghostPillView: View? = null
    private var ghostPillParams: WindowManager.LayoutParams? = null
    private var portalView: View? = null
    private var portalParams: WindowManager.LayoutParams? = null

    private var activeScreenSec = 0
    private var lastShadeDismissMs = 0L
    private var lastEscapeEjectMs = 0L

    private val autoAmbushTick = object : Runnable {
        override fun run() {
            try {
                val interactive = try { powerManager?.isInteractive == true } catch (_: Throwable) { true }
                val cfg = GazeConfig(applicationContext).snapshot()
                if (interactive && cfg.autoTriggerEnabled && !GazeSessionService.isSessionRunning) {
                    activeScreenSec += 10
                    if (activeScreenSec >= cfg.autoTriggerEveryMin * 60) {
                        activeScreenSec = 0
                        GazeSessionService.startSession(applicationContext, overrideDurationSec = cfg.autoTriggerDurationSec)
                    }
                } else if (!interactive) {
                    activeScreenSec = 0
                }
            } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        activeInstance = this
        mainHandler.postDelayed(autoAmbushTick, 10_000L)
        notifyStateChanged()
    }

    /**
     * #8 Intercepts hardware Volume Down / Mute keys during active session when Volume Lock is enabled!
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && GazeSessionService.isSessionRunning && GazeSessionService.isVolumeLockCurrentlyActive()) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GazeSessionService.enforceVolumeFloorNow(applicationContext)
                }
                return true // Completely swallow hardware volume-down / mute press!
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !GazeSessionService.isSessionRunning) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cfg = GazeConfig(applicationContext).snapshot()
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        val cls = event.className?.toString()?.lowercase().orEmpty()
        val now = System.currentTimeMillis()
        val isGhost = GazeSessionService.isCurrentSessionGhostMode()

        // 1) In FULL_LOCK mode (or while 3-min Portal is open), collapse Notification Shade / Quick Settings!
        if ((!isGhost || portalView != null) && pkg == "com.android.systemui" &&
            (cls.contains("shade") || cls.contains("panel") || cls.contains("controlcenter") || cls.contains("quicksettings"))
        ) {
            if (now - lastShadeDismissMs > 1_200L) {
                lastShadeDismissMs = now
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
                }
            }
            return
        }

        // 2) #13 Anti-Escape Watchdog:
        //    - In GHOST mode: surgically blocks ONLY Settings / Uninstall / App Info / Security Center so user can still use normal apps!
        //    - In FULL_LOCK mode: blocks Settings/Security + Recents/Overview/Task Switcher!
        if (!cfg.antiEscapeLockEnabled) return
        val isSystemEscape = isBlockedSettingsOrSystem(pkg)
        val isRecentsEscape = !isGhost && (
            cls.contains("recents") || cls.contains("overview") || cls.contains("taskswitcher") ||
                pkg.contains("recents") || pkg.contains("quickstep")
            )

        if ((isSystemEscape || isRecentsEscape) && now - lastEscapeEjectMs > 2_800L) {
            lastEscapeEjectMs = now
            try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
            }
            bringOverlaysToFront()
            GazeSessionService.triggerEscapeAttemptPenalty(applicationContext)
        }
    }

    private fun isBlockedSettingsOrSystem(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg.contains("packageinstaller") ||
            pkg.contains("miui.securitycenter") ||
            pkg.contains("miui.permcenter") ||
            pkg.contains("samsung.lool") ||
            pkg.contains("safecenter") ||
            pkg.contains("systemmanager") ||
            pkg.contains("iqoo.secure") ||
            pkg.contains("permissioncontroller")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoAmbushTick)
        removeAllOverlays()
        if (activeInstance === this) activeInstance = null
        notifyStateChanged()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun mountVideoOverlay(view: View, passThroughTouches: Boolean, forceMaxBrightness: Boolean): Boolean {
        val winMgr = wm ?: return false
        removeVideoOverlay()
        return try {
            val flags = if (passThroughTouches) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            winMgr.addView(view, lp)
            videoView = view
            videoParams = lp
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun mountGhostPillOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeGhostPillOverlay()
        return try {
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels - (175 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                y = (44 * dm.density).toInt()
            }
            winMgr.addView(view, lp)
            ghostPillView = view
            ghostPillParams = lp
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun dragGhostPillBy(dx: Int, dy: Int) {
        val winMgr = wm ?: return
        val v = ghostPillView ?: return
        val lp = ghostPillParams ?: return
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0)
        val maxY = (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0)
        lp.x = (lp.x + dx).coerceIn(0, maxX)
        lp.y = (lp.y + dy).coerceIn((20 * dm.density).toInt(), maxY)
        try { winMgr.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    fun mountPortalOverlay(view: View, forceMaxBrightness: Boolean): Boolean {
        val winMgr = wm ?: return false
        removePortalOverlay()
        return try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            winMgr.addView(view, lp)
            portalView = view
            portalParams = lp
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun bringOverlaysToFront() {
        mainHandler.post {
            val winMgr = wm ?: return@post
            try { videoView?.let { v -> videoParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { ghostPillView?.let { v -> ghostPillParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { portalView?.let { v -> portalParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
        }
    }

    fun removePortalOverlay() {
        val v = portalView ?: return
        portalView = null; portalParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun removeGhostPillOverlay() {
        val v = ghostPillView ?: return
        ghostPillView = null; ghostPillParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun removeVideoOverlay() {
        val v = videoView ?: return
        videoView = null; videoParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun removeAllOverlays() {
        removePortalOverlay()
        removeGhostPillOverlay()
        removeVideoOverlay()
    }

    companion object {
        @Volatile
        var activeInstance: GazeAccessibilityService? = null
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) { listeners.add(l) }
        fun removeStateListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyStateChanged() { for (l in listeners) try { l() } catch (_: Throwable) {} }

        fun isServiceActive(): Boolean = activeInstance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.contains("${context.packageName}/")
        }
    }
}
