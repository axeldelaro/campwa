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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.random.Random

/**
 * AccessibilityService Multi-Window Host for GazeLock AI v5.0 APOCALYPSE:
 * - Hosts up to 5 layered `TYPE_ACCESSIBILITY_OVERLAY` windows cleanly.
 * - #8 Hardware Volume Down / Mute key interception via `onKeyEvent`.
 * - #13 Surgical Anti-Escape in Ghost mode & Total Anti-Escape in Full-Lock mode.
 * - #21 App-Trap Ambush: opening any user-selected trapped app immediately injects a GazeLock session!
 * - #26 Immortal Mode: resumes interrupted session (+45s) on reboot/unlock.
 * - #29 Block Quick Settings / Notification Shade EVEN in Ghost Pass-Through mode!
 * - #30 App-Switch Tax: switching apps > 3 times/min in Ghost mode adds +10s & +5% opacity!
 */
class GazeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var powerManager: PowerManager? = null

    private var videoView: View? = null
    private var videoParams: WindowManager.LayoutParams? = null
    private var ghostPillView: View? = null
    private var ghostPillParams: WindowManager.LayoutParams? = null
    private var microPopupView: View? = null
    private var microPopupParams: WindowManager.LayoutParams? = null
    private var deadZoneView: View? = null
    private var deadZoneParams: WindowManager.LayoutParams? = null
    private var portalView: View? = null
    private var portalParams: WindowManager.LayoutParams? = null
    private val stormPopupViews = CopyOnWriteArrayList<View>()
    private var stormPillView: View? = null
    private var stormPillParams: WindowManager.LayoutParams? = null

    private var activeScreenSec = 0
    private var lastShadeDismissMs = 0L
    private var lastEscapeEjectMs = 0L
    private var lastAppTrapTriggerMs = 0L

    // #30 App-Switch Tax tracking
    private var lastForegroundUserPkg = ""
    private val recentAppSwitches = ArrayDeque<Long>()
    private var lastAppSwitchTaxMs = 0L

    private val autoAmbushTick = object : Runnable {
        override fun run() {
            try {
                val interactive = try { powerManager?.isInteractive == true } catch (_: Throwable) { true }
                val gCfg = GazeConfig(applicationContext)
                val cfg = gCfg.snapshot()

                if (interactive && cfg.rebootSurvivalEnabled && !GazeSessionService.isSessionRunning) {
                    val resumeSec = gCfg.consumeUnfinishedCheckpointSec()
                    if (resumeSec != null) {
                        GazeSessionService.startSession(applicationContext, overrideDurationSec = resumeSec, resumedFromCrash = true)
                        mainHandler.postDelayed(this, 10_000L)
                        return
                    }
                }

                if (interactive && cfg.autoTriggerEnabled && !GazeSessionService.isSessionRunning && !ImagePopupStormService.isRunning) {
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
        mainHandler.postDelayed(autoAmbushTick, 4_000L)
        notifyStateChanged()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && GazeSessionService.isSessionRunning && GazeSessionService.isVolumeLockCurrentlyActive()) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GazeSessionService.enforceVolumeFloorNow()
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cfg = GazeConfig(applicationContext).snapshot()
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        val cls = event.className?.toString()?.lowercase().orEmpty()
        val now = System.currentTimeMillis()

        // ==================== STANDALONE IMAGE POP-UP MODE ANTI-ESCAPE (WHEN LOCKED!) ====================
        if (!GazeSessionService.isSessionRunning && ImagePopupStormService.isRunning) {
            if (ImagePopupStormService.isExitLocked && cfg.antiEscapeLockEnabled &&
                isBlockedSettingsOrSystem(pkg) && now - lastEscapeEjectMs > 2_500L
            ) {
                lastEscapeEjectMs = now
                try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
                }
                ImagePopupStormService.onEscapeBlocked()
            }
            return
        }

        // ==================== #21 APP-TRAP AMBUSH (WHEN NO SESSION IS RUNNING) ====================
        if (!GazeSessionService.isSessionRunning) {
            if (cfg.appTrapEnabled && pkg.isNotBlank() && pkg != packageName.lowercase() &&
                now - lastAppTrapTriggerMs > 35_000L &&
                now - GazeSessionService.lastSessionEndedMs > 30_000L
            ) {
                if (pkg in cfg.trappedPackageSet()) {
                    lastAppTrapTriggerMs = now
                    GazeSessionService.startSession(
                        context = applicationContext,
                        overrideDurationSec = cfg.appTrapDurationSec,
                        trapTriggerPkg = pkg
                    )
                }
            }
            return
        }

        // ==================== ACTIVE SESSION ENFORCEMENT ====================
        val isGhost = GazeSessionService.isCurrentSessionGhostMode()

        // 1) #29 Collapse Notification Shade / Quick Settings (in FULL_LOCK, Portal, OR Ghost when #29 enabled!)
        val blockShadeNow = !isGhost || portalView != null || cfg.blockShadeInGhostEnabled
        if (blockShadeNow && pkg == "com.android.systemui" &&
            (cls.contains("shade") || cls.contains("panel") || cls.contains("controlcenter") || cls.contains("quicksettings"))
        ) {
            if (now - lastShadeDismissMs > 1_100L) {
                lastShadeDismissMs = now
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
                } else {
                    try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) {}
                }
            }
            return
        }

        // 2) #13 Surgical vs Total Anti-Escape Watchdog
        if (cfg.antiEscapeLockEnabled) {
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
                GazeSessionService.triggerEscapeAttemptPenalty()
                return
            }
        }

        // 3) #30 App-Switch Tax in Ghost Mode (> 3 distinct app switches within 60s -> +10s & +5% opacity!)
        if (isGhost && cfg.appSwitchTaxEnabled && portalView == null && isTrackableUserApp(pkg)) {
            if (pkg != lastForegroundUserPkg) {
                val hadPrevious = lastForegroundUserPkg.isNotEmpty()
                lastForegroundUserPkg = pkg
                if (hadPrevious) {
                    recentAppSwitches.addLast(now)
                    while (recentAppSwitches.isNotEmpty() && now - recentAppSwitches.first() > 60_000L) {
                        recentAppSwitches.removeFirst()
                    }
                    if (recentAppSwitches.size > 3 && now - lastAppSwitchTaxMs > 12_000L) {
                        lastAppSwitchTaxMs = now
                        GazeSessionService.triggerAppSwitchTax()
                    }
                }
            }
        }
    }

    private fun isTrackableUserApp(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName.lowercase()) return false
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("gboard") || pkg.contains("keyboard")) return false
        if (pkg.contains("launcher") || pkg.contains("nexuslauncher") || pkg.contains("trebuchet") || pkg.contains("miui.home")) return false
        return true
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
        recentAppSwitches.clear()
        lastForegroundUserPkg = ""
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
            videoView = view; videoParams = lp
            true
        } catch (_: Throwable) { false }
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels - (195 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                y = (44 * dm.density).toInt()
            }
            winMgr.addView(view, lp)
            ghostPillView = view; ghostPillParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun dragGhostPillBy(dx: Int, dy: Int) {
        val winMgr = wm ?: return
        val v = ghostPillView ?: return
        val lp = ghostPillParams ?: return
        val dm = resources.displayMetrics
        lp.x = (lp.x + dx).coerceIn(0, (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn((20 * dm.density).toInt(), (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0))
        try { winMgr.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    fun mountMicroPopupOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeMicroPopupOverlay()
        return try {
            val dm = resources.displayMetrics
            val maxX = (dm.widthPixels - (225 * dm.density).toInt()).coerceAtLeast(16)
            val minY = (115 * dm.density).toInt()
            val maxY = (dm.heightPixels - (240 * dm.density).toInt()).coerceAtLeast(minY + 10)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = Random.nextInt(12, maxX)
                y = Random.nextInt(minY, maxY)
            }
            winMgr.addView(view, lp)
            microPopupView = view; microPopupParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun removeMicroPopupOverlay() {
        val v = microPopupView ?: return
        microPopupView = null; microPopupParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun mountDeadZoneOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeDeadZoneOverlay()
        return try {
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                (dm.widthPixels * 0.68f).toInt(),
                (170 * dm.density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                y = (45 * dm.density).toInt()
            }
            winMgr.addView(view, lp)
            deadZoneView = view; deadZoneParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun removeDeadZoneOverlay() {
        val v = deadZoneView ?: return
        deadZoneView = null; deadZoneParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
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
            portalView = view; portalParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun bringOverlaysToFront() {
        mainHandler.post {
            val winMgr = wm ?: return@post
            try { videoView?.let { v -> videoParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { ghostPillView?.let { v -> ghostPillParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { deadZoneView?.let { v -> deadZoneParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { microPopupView?.let { v -> microPopupParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
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

    // ==================== STANDALONE IMAGE POP-UP STORM MULTI-WINDOW SLOTS ====================
    fun mountStormPopupWindow(view: View, x: Int, y: Int, widthPx: Int, heightPx: Int, touchable: Boolean): Boolean {
        val winMgr = wm ?: return false
        return try {
            val flags = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            val lp = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            winMgr.addView(view, lp)
            stormPopupViews.add(view)
            true
        } catch (_: Throwable) { false }
    }

    fun removeStormPopupWindow(view: View) {
        stormPopupViews.remove(view)
        try { wm?.removeViewImmediate(view) } catch (_: Throwable) {}
    }

    fun mountStormPillOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeStormPillOverlay()
        return try {
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (dm.widthPixels - (170 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                y = (44 * dm.density).toInt()
            }
            winMgr.addView(view, lp)
            stormPillView = view; stormPillParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun dragStormPillBy(dx: Int, dy: Int) {
        val winMgr = wm ?: return
        val v = stormPillView ?: return
        val lp = stormPillParams ?: return
        val dm = resources.displayMetrics
        lp.x = (lp.x + dx).coerceIn(0, (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn((20 * dm.density).toInt(), (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0))
        try { winMgr.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    fun removeStormPillOverlay() {
        val v = stormPillView ?: return
        stormPillView = null; stormPillParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun removeAllStormOverlays() {
        for (v in stormPopupViews.toList()) {
            try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
        }
        stormPopupViews.clear()
        removeStormPillOverlay()
    }

    fun removeAllOverlays() {
        removePortalOverlay()
        removeAllStormOverlays()
        removeMicroPopupOverlay()
        removeDeadZoneOverlay()
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
