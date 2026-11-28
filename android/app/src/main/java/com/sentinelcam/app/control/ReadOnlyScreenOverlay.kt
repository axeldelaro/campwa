package com.sentinelcam.app.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * #31: Mode « Écran Congelé / Lecture Seule » (100% Hors-MDM!):
 * - Transparent full-screen touch shield (`TYPE_ACCESSIBILITY_OVERLAY` or `TYPE_APPLICATION_OVERLAY`)
 *   that absorbs 100% of finger touches so the child can watch a video/lesson without leaving it or tapping anything.
 * - Displays a clean, minimal amber Kidslox-inspired status pill at top with live countdown timer.
 */
object ReadOnlyScreenOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedWm: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var pillTv: TextView? = null
    private var activeUntilMs: Long = 0L

    @Volatile
    var isShowing: Boolean = false
        private set

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            val now = System.currentTimeMillis()
            if (activeUntilMs in 1L..now) {
                hide()
                return
            }
            updatePillText(now)
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    fun syncWithUntilMs(context: Context, untilMs: Long) {
        val now = System.currentTimeMillis()
        if (untilMs > now) {
            show(context, untilMs)
        } else {
            hide()
        }
    }

    fun show(context: Context, untilMs: Long) {
        activeUntilMs = untilMs
        mainHandler.post {
            if (isShowing && overlayRoot != null) {
                updatePillText(System.currentTimeMillis())
                return@post
            }
            val a11yCtx = SentinelAccessibilityService.getServiceContext()
            val a11yWm = SentinelAccessibilityService.getOverlayWindowManager()
            val useA11y = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && a11yCtx != null && a11yWm != null

            val appCtx = context.applicationContext
            if (!useA11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                return@post
            }

            val uiCtx = a11yCtx ?: appCtx
            val wm = if (useA11y) a11yWm!! else appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dp = uiCtx.resources.displayMetrics.density

            val root = FrameLayout(uiCtx).apply {
                setBackgroundColor(Color.argb(12, 245, 158, 11)) // ultra-subtle warm tint so child knows touch is paused
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, _ -> true } // Swallow 100% of touches!
            }

            val pill = TextView(uiCtx).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                val px = (14 * dp).toInt()
                val py = (7 * dp).toInt()
                setPadding(px, py, px, py)
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Color.argb(230, 217, 119, 6))
                    setStroke((1 * dp).toInt().coerceAtLeast(1), Color.argb(140, 255, 255, 255))
                }
                elevation = 6 * dp
            }
            val lpPill = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (38 * dp).toInt()
            }
            root.addView(pill, lpPill)

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            try {
                wm.addView(root, lp)
                attachedWm = wm
                overlayRoot = root
                pillTv = pill
                isShowing = true
                updatePillText(System.currentTimeMillis())
                mainHandler.removeCallbacks(tickRunnable)
                mainHandler.postDelayed(tickRunnable, 1_000L)
            } catch (_: Throwable) {
                isShowing = false
            }
        }
    }

    fun hide() {
        activeUntilMs = 0L
        mainHandler.post {
            mainHandler.removeCallbacks(tickRunnable)
            val root = overlayRoot
            val wm = attachedWm
            overlayRoot = null
            pillTv = null
            attachedWm = null
            isShowing = false
            if (root != null && wm != null) {
                try {
                    wm.removeViewImmediate(root)
                } catch (_: Throwable) {
                    try { wm.removeView(root) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun updatePillText(nowMs: Long) {
        val tv = pillTv ?: return
        val remSec = ((activeUntilMs - nowMs) / 1000L).coerceAtLeast(0L)
        val suffix = if (activeUntilMs > nowMs && remSec < 86_400L) {
            val m = remSec / 60L
            val s = remSec % 60L
            " · ${m}m${s.toString().padStart(2, '0')}s"
        } else {
            ""
        }
        tv.text = "Écran en lecture seule (tactile gelé)$suffix"
    }
}
