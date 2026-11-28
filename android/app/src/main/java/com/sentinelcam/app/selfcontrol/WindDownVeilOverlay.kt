package com.sentinelcam.app.selfcontrol

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.sentinelcam.app.control.SentinelAccessibilityService

/**
 * SentinelCam v28.0 — Progressive Bedtime Wind-Down Veil:
 * A non-touch-blocking warm amber + dimming veil over the screen during the 30 min
 * before bedtime so bright blue light and dopamine colors gently fade out.
 */
object WindDownVeilOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedWm: WindowManager? = null
    private var veilView: View? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    fun sync(context: Context, shouldShow: Boolean) {
        if (shouldShow) show(context) else hide()
    }

    fun show(context: Context) {
        mainHandler.post {
            if (isShowing && veilView != null) return@post
            val a11yCtx = SentinelAccessibilityService.getServiceContext()
            val a11yWm = SentinelAccessibilityService.getOverlayWindowManager()
            val useA11y = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && a11yCtx != null && a11yWm != null

            val appCtx = context.applicationContext
            if (!useA11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                return@post
            }

            val uiCtx = a11yCtx ?: appCtx
            val wm = if (useA11y) a11yWm!! else appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val v = View(uiCtx).apply {
                // Warm amber/sleep tint (alpha 64 = ~25% warm low-blue filter)
                setBackgroundColor(Color.argb(64, 217, 102, 24))
            }

            val winType = when {
                useA11y -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            try {
                wm.addView(v, lp)
                attachedWm = wm
                veilView = v
                isShowing = true
            } catch (_: Throwable) {
                isShowing = false
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = veilView
            val wm = attachedWm
            veilView = null
            attachedWm = null
            isShowing = false
            if (v != null && wm != null) {
                try {
                    wm.removeViewImmediate(v)
                } catch (_: Throwable) {
                    try { wm.removeView(v) } catch (_: Throwable) {}
                }
            }
        }
    }
}
