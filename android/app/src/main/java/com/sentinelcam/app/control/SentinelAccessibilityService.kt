package com.sentinelcam.app.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Official Android Accessibility Protection Service (v26):
 * - Enabled ONCE by the Parent during initial setup; survives reboots forever and requires
 *   ZERO child confirmation when the Parent activates Face/NSFW Blur or Auto-Screenshots remotely!
 * - Uses official Android 11+ (API 30+) `AccessibilityService.takeScreenshot()` -> zero MediaProjection
 *   prompt, zero Android 14/15 notification hiding!
 * - Provides official `TYPE_ACCESSIBILITY_OVERLAY` window context so the Face/NSFW mosaic blur overlay
 *   can draw over all apps automatically in the background without child intervention.
 * - Reports instant window-change and scroll-delta events so blur masks clear in 0ms on app switch
 *   and glide smoothly on scroll.
 * - Automatically calls `disableSelf()` on unpair so child regains 100% control!
 */
class SentinelAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        notifyConnectionChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()?.trim().orEmpty()
                if (pkg.isNotEmpty()) {
                    val lower = pkg.lowercase()
                    val isTransientUi = lower == "com.android.systemui" ||
                        lower.contains("inputmethod") ||
                        lower.contains("gboard") ||
                        lower.contains("honeyboard") ||
                        lower.contains("swiftkey")
                    if (!isTransientUi) {
                        lastWindowPackage = pkg
                        ScreenTimeHelper.notifyAccessibilityWindowChanged(pkg)
                        for (l in windowListeners) {
                            try { l.onWindowChanged(pkg) } catch (_: Throwable) {}
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val dy = event.scrollDeltaY
                    if (dy != 0) {
                        for (l in windowListeners) {
                            try { l.onScrollDeltaPx(dy) } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
            notifyConnectionChanged()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
            notifyConnectionChanged()
        }
        super.onDestroy()
    }

    interface WindowEventListener {
        fun onWindowChanged(packageName: String)
        fun onScrollDeltaPx(deltaYPx: Int)
    }

    companion object {
        @Volatile
        private var activeInstance: SentinelAccessibilityService? = null

        @Volatile
        var lastWindowPackage: String? = null
            private set

        @Volatile
        private var lastScreenshotAtMs: Long = 0L

        private val captureInFlight = AtomicBoolean(false)
        private val captureExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SentinelA11yShot").apply { isDaemon = true }
        }
        private val windowListeners = CopyOnWriteArraySet<WindowEventListener>()
        private val connectionListeners = CopyOnWriteArraySet<() -> Unit>()

        fun isActiveNow(): Boolean = activeInstance != null

        fun canCaptureAutonomously(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeInstance != null

        fun getServiceContext(): Context? = activeInstance

        fun getOverlayWindowManager(): WindowManager? {
            return try {
                activeInstance?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            } catch (_: Throwable) {
                null
            }
        }

        fun addWindowListener(listener: WindowEventListener) {
            windowListeners.add(listener)
        }

        fun removeWindowListener(listener: WindowEventListener) {
            windowListeners.remove(listener)
        }

        fun addConnectionListener(listener: () -> Unit) {
            connectionListeners.add(listener)
        }

        fun removeConnectionListener(listener: () -> Unit) {
            connectionListeners.remove(listener)
        }

        private fun notifyConnectionChanged() {
            for (l in connectionListeners) {
                try { l.invoke() } catch (_: Throwable) {}
            }
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val expected = ComponentName(context, SentinelAccessibilityService::class.java).flattenToString()
                val shortExpected = ComponentName(context, SentinelAccessibilityService::class.java).flattenToShortString()
                enabled.contains(expected, ignoreCase = true) ||
                    enabled.contains(shortExpected, ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Official Android 11+ zero-prompt background screenshot capture.
         * Automatically respects Android's ~1s rate limit and downscales cleanly to save RAM/CPU.
         */
        fun captureScreenBitmap(
            maxWidth: Int = 540,
            onResult: (Bitmap?) -> Unit
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(null)
                return
            }
            val svc = activeInstance
            if (svc == null) {
                onResult(null)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastScreenshotAtMs < 1_020L) {
                onResult(null)
                return
            }
            if (!captureInFlight.compareAndSet(false, true)) {
                onResult(null)
                return
            }
            lastScreenshotAtMs = now
            try {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    captureExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var hwBmp: Bitmap? = null
                            var softBmp: Bitmap? = null
                            var finalBmp: Bitmap? = null
                            try {
                                val buffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                try {
                                    hwBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                } finally {
                                    try { buffer.close() } catch (_: Throwable) {}
                                }
                                if (hwBmp != null) {
                                    softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBmp.recycle()
                                    hwBmp = null
                                }
                                if (softBmp != null) {
                                    val w = softBmp.width
                                    val h = softBmp.height
                                    if (w > maxWidth && w > 0 && h > 0) {
                                        val ratio = maxWidth.toFloat() / w.toFloat()
                                        val tw = (w * ratio).toInt().coerceAtLeast(160)
                                        val th = (h * ratio).toInt().coerceAtLeast(240)
                                        finalBmp = Bitmap.createScaledBitmap(softBmp, tw, th, true)
                                        softBmp.recycle()
                                        softBmp = null
                                    } else {
                                        finalBmp = softBmp
                                        softBmp = null
                                    }
                                }
                            } catch (_: Throwable) {
                                try { hwBmp?.recycle() } catch (_: Throwable) {}
                                try { softBmp?.recycle() } catch (_: Throwable) {}
                                try { finalBmp?.recycle() } catch (_: Throwable) {}
                                finalBmp = null
                            } finally {
                                captureInFlight.set(false)
                            }
                            onResult(finalBmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            captureInFlight.set(false)
                            onResult(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                captureInFlight.set(false)
                onResult(null)
            }
        }

        /**
         * Atomically disables the Accessibility Service on unpair (official API 24+ `disableSelf()`).
         */
        fun disableServiceOnUnpair() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    activeInstance?.disableSelf()
                }
            } catch (_: Throwable) {
            }
            activeInstance = null
        }
    }
}
