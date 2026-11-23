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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gazelock.app.config.GazeConfig
import java.util.concurrent.CopyOnWriteArraySet

/**
 * AccessibilityService Host for GazeLock AI:
 * - Mounts the fullscreen video feed directly on `TYPE_ACCESSIBILITY_OVERLAY` (impossible to exit casually,
 *   covers status bar, navigation bar & all apps cleanly!).
 * - Anti-Escape Watchdog during active sessions: auto-collapses Notification Shade / Quick Settings if pulled down!
 * - Optional Auto-Ambush timer: launches a GazeLock video session automatically every X minutes of screen use!
 */
class GazeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var mountedView: View? = null
    private var powerManager: PowerManager? = null
    private var activeScreenSec = 0
    private var lastShadeDismissMs = 0L

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !GazeSessionService.isSessionRunning) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.lowercase().orEmpty()
            val cls = event.className?.toString()?.lowercase().orEmpty()
            // Collapse Notification Shade / Control Center if user tries to pull it down mid-session!
            if (pkg == "com.android.systemui" &&
                (cls.contains("shade") || cls.contains("panel") || cls.contains("controlcenter") || cls.contains("quicksettings"))
            ) {
                val now = System.currentTimeMillis()
                if (now - lastShadeDismissMs > 1_200L) {
                    lastShadeDismissMs = now
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoAmbushTick)
        removeMountedOverlay()
        if (activeInstance === this) activeInstance = null
        notifyStateChanged()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun mountFullscreenOverlay(view: View): Boolean {
        val winMgr = wm ?: return false
        removeMountedOverlay()
        return try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            winMgr.addView(view, lp)
            mountedView = view
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun removeMountedOverlay() {
        val v = mountedView ?: return
        mountedView = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
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
