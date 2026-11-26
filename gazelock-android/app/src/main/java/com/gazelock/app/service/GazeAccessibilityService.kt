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
 * AccessibilityService Fortress Multi-Window Host for GazeLock AI v7.0 TITAN:
 * - #1 FLAG_SECURE anti-screenshot flag on Portal, Boss Final & Attention Hijack windows.
 * - #2 Anti-Split-Screen / Freeform / PiP multi-window blocker during sessions & locked pop-ups.
 * - #3 Self-Healing Z-order watchdog helpers (`bringOverlaysToFront`, `bringStormOverlaysToFront`).
 * - #4 Hardware Volume Down / Mute blocking in BOTH Video Sessions AND Locked Image Pop-Ups!
 * - #5 Block Notification Shade / Quick Settings in Locked Image Pop-Ups as well as Ghost Video!
 * - #6 Immortal Mode auto-resume on crash/reboot for BOTH Video Sessions AND Locked Image Pop-Ups!
 * - #21 App-Trap Ambush + #30 App-Switch Tax + #37 Random Time Bomb detonation!
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
    private var stormSpiralView: View? = null
    private var stormStageView: View? = null
    private var stormStageParams: WindowManager.LayoutParams? = null
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
                val nowWall = System.currentTimeMillis()

                // #26 Immortal Video Session Resume
                if (interactive && cfg.rebootSurvivalEnabled && !GazeSessionService.isSessionRunning) {
                    val resumeSec = gCfg.consumeUnfinishedCheckpointSec()
                    if (resumeSec != null) {
                        GazeSessionService.startSession(applicationContext, overrideDurationSec = resumeSec, resumedFromCrash = true)
                        mainHandler.postDelayed(this, 8_000L)
                        return
                    }
                }

                // #6 Immortal Locked Image Pop-Up Storm Resume
                if (interactive && cfg.popupRebootSurvivalEnabled && !ImagePopupStormService.isRunning && !GazeSessionService.isSessionRunning) {
                    val popResume = gCfg.consumeUnfinishedPopupCheckpoint()
                    if (popResume != null) {
                        ImagePopupStormService.start(applicationContext, resumedOverrideSec = popResume.first, resumedInfinite = popResume.second)
                        mainHandler.postDelayed(this, 8_000L)
                        return
                    }
                }

                // #37 Armed Time Bomb Detonation!
                if (interactive && cfg.timeBombArmedUntilMs > 0L && nowWall >= cfg.timeBombArmedUntilMs && !GazeSessionService.isSessionRunning) {
                    gCfg.disarmTimeBomb()
                    GazeSessionService.startSession(
                        context = applicationContext,
                        overrideDurationSec = cfg.timeBombDurationSec,
                        fromTimeBomb = true
                    )
                    mainHandler.postDelayed(this, 10_000L)
                    return
                }

                // Optional periodic screen-time ambush
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
            mainHandler.postDelayed(this, 8_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        activeInstance = this
        mainHandler.postDelayed(autoAmbushTick, 3_500L)
        notifyStateChanged()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                if (GazeSessionService.isSessionRunning && GazeSessionService.isVolumeLockCurrentlyActive()) {
                    if (event.action == KeyEvent.ACTION_DOWN) GazeSessionService.enforceVolumeFloorNow()
                    return true
                }
                // #4 Strict Audio Guard inside Locked Image Pop-Ups!
                if (ImagePopupStormService.isRunning && ImagePopupStormService.isExitLocked) {
                    val cfg = GazeConfig(applicationContext).snapshot()
                    if (cfg.strictAudioGuardEnabled) {
                        if (event.action == KeyEvent.ACTION_DOWN) ImagePopupStormService.enforceVolumeFloorNow()
                        return true
                    }
                }
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

        // ==================== STANDALONE IMAGE POP-UP MODE HARDENING (WHEN LOCKED!) ====================
        if (!GazeSessionService.isSessionRunning && ImagePopupStormService.isRunning) {
            if (ImagePopupStormService.isExitLocked) {
                // #5 Block Notification Shade / Quick Settings in Locked Pop-Ups!
                if (cfg.popupBlockShadeEnabled && isNotificationOrQuickSettings(pkg, cls)) {
                    if (now - lastShadeDismissMs > 1_000L) {
                        lastShadeDismissMs = now
                        collapseShadeNow()
                    }
                    return
                }
                // #2 Anti-Split-Screen + Anti-Escape in Locked Pop-Ups!
                val isSplitEscape = cfg.antiSplitScreenEnabled && isSplitOrFloatingWindow(cls, pkg)
                if ((cfg.antiEscapeLockEnabled && isBlockedSettingsOrSystem(pkg)) || isSplitEscape) {
                    if (now - lastEscapeEjectMs > 2_200L) {
                        lastEscapeEjectMs = now
                        ejectToHomeAndCollapse()
                        bringStormOverlaysToFront()
                        ImagePopupStormService.onEscapeBlocked()
                    }
                }
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

        // ==================== ACTIVE VIDEO SESSION ENFORCEMENT ====================
        val isGhost = GazeSessionService.isCurrentSessionGhostMode()

        // 1) #29 Collapse Notification Shade / Quick Settings (in FULL_LOCK, Portal, OR Ghost when enabled!)
        val blockShadeNow = !isGhost || portalView != null || cfg.blockShadeInGhostEnabled
        if (blockShadeNow && isNotificationOrQuickSettings(pkg, cls)) {
            if (now - lastShadeDismissMs > 1_000L) {
                lastShadeDismissMs = now
                collapseShadeNow()
            }
            return
        }

        // 2) #13 Anti-Escape + #2 Anti-Split-Screen / Freeform / PiP Blocker!
        if (cfg.antiEscapeLockEnabled || cfg.antiSplitScreenEnabled) {
            val isSystemEscape = cfg.antiEscapeLockEnabled && isBlockedSettingsOrSystem(pkg)
            val isRecentsEscape = cfg.antiEscapeLockEnabled && !isGhost && (
                cls.contains("recents") || cls.contains("overview") || cls.contains("taskswitcher") ||
                    pkg.contains("recents") || pkg.contains("quickstep")
                )
            val isSplitEscape = cfg.antiSplitScreenEnabled && isSplitOrFloatingWindow(cls, pkg)
            if ((isSystemEscape || isRecentsEscape || isSplitEscape) && now - lastEscapeEjectMs > 2_400L) {
                lastEscapeEjectMs = now
                ejectToHomeAndCollapse()
                bringOverlaysToFront()
                GazeSessionService.triggerEscapeAttemptPenalty(isSplitEscape)
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

    private fun collapseShadeNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
        } else {
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) {}
        }
    }

    private fun ejectToHomeAndCollapse() {
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
        }
    }

    private fun isNotificationOrQuickSettings(pkg: String, cls: String): Boolean {
        return pkg == "com.android.systemui" && (
            cls.contains("shade") || cls.contains("panel") || cls.contains("controlcenter") ||
                cls.contains("quicksettings") || cls.contains("statusbar") || cls.contains("globalactions")
            )
    }

    /** #2 Detects Android Split-Screen / Docked / Freeform / Floating Window / MIUI Mini-Window attempts! */
    private fun isSplitOrFloatingWindow(cls: String, pkg: String): Boolean {
        return cls.contains("splitscreen") || cls.contains("docked") || cls.contains("freeform") ||
            cls.contains("multiwindow") || cls.contains("floatingwindow") || cls.contains("miniwindow") ||
            cls.contains("pipmenu") || pkg.contains("freeform")
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
            pkg.contains("permissioncontroller") ||
            pkg.contains("oplus.safecenter") ||
            pkg.contains("coloros.safecenter")
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
    fun mountVideoOverlay(view: View, passThroughTouches: Boolean, forceMaxBrightness: Boolean, flagSecure: Boolean = false): Boolean {
        val winMgr = wm ?: return false
        removeVideoOverlay()
        recentAppSwitches.clear()
        lastForegroundUserPkg = ""
        return try {
            var flags = if (passThroughTouches) {
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
            if (flagSecure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
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

    fun isVideoOverlayAttached(): Boolean = videoView?.isAttachedToWindow == true

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
                x = (dm.widthPixels - (205 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
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
    fun mountPortalOverlay(view: View, forceMaxBrightness: Boolean, flagSecure: Boolean = true): Boolean {
        val winMgr = wm ?: return false
        removePortalOverlay()
        return try {
            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (flagSecure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
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

    fun bringStormOverlaysToFront() {
        mainHandler.post {
            val winMgr = wm ?: return@post
            try { stormStageView?.let { v -> stormStageParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
            try { stormPillView?.let { v -> stormPillParams?.let { lp -> winMgr.updateViewLayout(v, lp) } } } catch (_: Throwable) {}
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

    /** #26 Optional 60 FPS Hypnotic Spiral Backdrop underneath standalone image pop-ups (always touch-through!). */
    fun mountStormSpiralOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeStormSpiralOverlay()
        return try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            winMgr.addView(view, lp)
            stormSpiralView = view
            true
        } catch (_: Throwable) { false }
    }

    fun removeStormSpiralOverlay() {
        val v = stormSpiralView ?: return
        stormSpiralView = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
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
                x = (dm.widthPixels - (190 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
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

    fun mountStormStageOverlay(view: View, touchable: Boolean): Boolean {
        val winMgr = wm ?: return false
        removeStormStageOverlay()
        return try {
            val flags = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            winMgr.addView(view, lp)
            stormStageView = view; stormStageParams = lp
            true
        } catch (_: Throwable) { false }
    }

    fun removeStormStageOverlay() {
        val v = stormStageView ?: return
        stormStageView = null; stormStageParams = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    fun removeAllStormOverlays() {
        for (v in stormPopupViews.toList()) {
            try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
        }
        stormPopupViews.clear()
        removeStormStageOverlay()
        removeStormSpiralOverlay()
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
