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
import kotlin.random.Random

/**
 * AccessibilityService Multi-Window Host for GazeLock AI v4.0 ULTRA:
 * - Hosts up to 5 layered `TYPE_ACCESSIBILITY_OVERLAY` windows cleanly:
 *   1) Video + #21 Hypnotic Spiral + #7 Subliminal Window (Ghost Pass-Through OR Full-Lock)
 *   2) Compact Draggable Ghost Pill Window (zero close button!)
 *   3) #5 Floating Ghost Micro-Popup Window (compact WRAP_CONTENT, rest of phone stays touchable!)
 *   4) #9 Punitive Confiscated Touch Dead-Zone Window (localized touch blocker!)
 *   5) Interactive Fullscreen Portal / #2 Attention Hijack / Completion Window!
 * - #8 Hardware Volume Down / Mute key interception via `onKeyEvent`.
 * - #13 Surgical Anti-Escape in Ghost mode & Total Anti-Escape in Full-Lock mode.
 * - #26 Immortal Mode: resumes interrupted session (+45s escape tax) upon reboot or unlock!
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

    private var activeScreenSec = 0
    private var lastShadeDismissMs = 0L
    private var lastEscapeEjectMs = 0L

    private val autoAmbushTick = object : Runnable {
        override fun run() {
            try {
                val interactive = try { powerManager?.isInteractive == true } catch (_: Throwable) { true }
                val gCfg = GazeConfig(applicationContext)
                val cfg = gCfg.snapshot()

                // #26 Immortal Mode: if session was interrupted by reboot/force-kill, auto-resume (+45s)!
                if (interactive && cfg.rebootSurvivalEnabled && !GazeSessionService.isSessionRunning) {
                    val resumeSec = gCfg.consumeUnfinishedCheckpointSec()
                    if (resumeSec != null) {
                        GazeSessionService.startSession(applicationContext, overrideDurationSec = resumeSec, resumedFromCrash = true)
                        mainHandler.postDelayed(this, 10_000L)
                        return
                    }
                }

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
        if (event == null || !GazeSessionService.isSessionRunning) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cfg = GazeConfig(applicationContext).snapshot()
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        val cls = event.className?.toString()?.lowercase().orEmpty()
        val now = System.currentTimeMillis()
        val isGhost = GazeSessionService.isCurrentSessionGhostMode()

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
            GazeSessionService.triggerEscapeAttemptPenalty()
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
                x = (dm.widthPixels - (190 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
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

    /** #5 Mount compact floating Ghost Micro-Popup at a random edge/quadrant! */
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

    /** #9 Mount localized Confiscated Touch Dead-Zone over center-lower display! */
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

    fun removeAllOverlays() {
        removePortalOverlay()
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
