package com.sentinelcam.app.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Triple-Fail-Safe Fullscreen Flash Overlay Manager:
 * - Displays either a crisp parent message on a pure black (#000000) screen (default 3s)
 *   OR a parent-selected image for X seconds (strictly bounded 1..60s).
 * - Triple fail-safe guarantees ZERO risk of permanent screen freeze or lockup:
 *   1) Primary auto-dismiss timer with smooth fade-out.
 *   2) Secondary hard-watchdog timer (+1.5s) calling removeViewImmediate.
 *   3) Automatic cleanup on service shutdown and non-focusable window flags.
 */
object FlashOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeView: View? = null
    private var activeWindowManager: WindowManager? = null
    private var activeBitmap: Bitmap? = null
    private var primaryDismissRunnable: Runnable? = null
    private var hardWatchdogRunnable: Runnable? = null

    fun canShowOverlay(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun showTextMessage(
        context: Context,
        message: String,
        durationSeconds: Int = 3
    ): Pair<Boolean, String?> {
        val cleanMsg = message.trim().take(280)
        if (cleanMsg.isEmpty()) return Pair(false, "Message vide.")
        if (!canShowOverlay(context)) {
            return Pair(false, "Autorisation d’affichage superposé manquante sur le téléphone.")
        }
        val safeSec = durationSeconds.coerceIn(1, 60)

        mainHandler.post {
            try {
                dismissImmediateLocked()
                val appCtx = context.applicationContext
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = appCtx.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val container = FrameLayout(appCtx).apply {
                    setBackgroundColor(Color.BLACK)
                    alpha = 0f
                }

                val tv = TextView(appCtx).apply {
                    text = cleanMsg
                    setTextColor(Color.WHITE)
                    textSize = if (cleanMsg.length > 90) 20f else 26f
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(32), dp(48), dp(32), dp(48))
                    setLineSpacing(dp(6).toFloat(), 1.1f)
                }

                container.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )

                attachWithTripleFailsafe(wm, container, null, safeSec * 1000L)
            } catch (_: Throwable) {
                dismissImmediateLocked()
            }
        }
        return Pair(true, null)
    }

    fun showImage(
        context: Context,
        imageBytes: ByteArray,
        caption: String = "",
        durationSeconds: Int = 5
    ): Pair<Boolean, String?> {
        if (imageBytes.isEmpty()) return Pair(false, "Image vide.")
        if (!canShowOverlay(context)) {
            return Pair(false, "Autorisation d’affichage superposé manquante sur le téléphone.")
        }
        val safeSec = durationSeconds.coerceIn(1, 60)

        val decoded: Bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
                ?: return Pair(false, "Image illisible.")
        } catch (_: Throwable) {
            return Pair(false, "Décodage image impossible.")
        }

        val cleanCaption = caption.trim().take(160)

        mainHandler.post {
            try {
                dismissImmediateLocked()
                val appCtx = context.applicationContext
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = appCtx.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val root = FrameLayout(appCtx).apply {
                    setBackgroundColor(Color.BLACK)
                    alpha = 0f
                }

                val iv = ImageView(appCtx).apply {
                    setImageBitmap(decoded)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                root.addView(
                    iv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                if (cleanCaption.isNotEmpty()) {
                    val captionBox = LinearLayout(appCtx).apply {
                        setBackgroundColor(Color.argb(185, 0, 0, 0))
                        setPadding(dp(20), dp(14), dp(20), dp(28))
                        gravity = Gravity.CENTER
                    }
                    val tv = TextView(appCtx).apply {
                        text = cleanCaption
                        setTextColor(Color.WHITE)
                        textSize = 17f
                        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                        gravity = Gravity.CENTER
                    }
                    captionBox.addView(tv)
                    root.addView(
                        captionBox,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        )
                    )
                }

                attachWithTripleFailsafe(wm, root, decoded, safeSec * 1000L)
            } catch (_: Throwable) {
                try {
                    if (!decoded.isRecycled) decoded.recycle()
                } catch (_: Throwable) {
                }
                dismissImmediateLocked()
            }
        }
        return Pair(true, null)
    }

    fun dismissNow() {
        mainHandler.post {
            dismissImmediateLocked()
        }
    }

    private fun attachWithTripleFailsafe(
        wm: WindowManager,
        view: View,
        bitmap: Bitmap?,
        durationMs: Long
    ) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(view, params)
        activeView = view
        activeWindowManager = wm
        activeBitmap = bitmap

        view.animate().alpha(1f).setDuration(180L).start()

        // 1. Primary smooth dismiss timer
        val primary = Runnable {
            val v = activeView
            if (v === view) {
                v.animate()
                    .alpha(0f)
                    .setDuration(240L)
                    .withEndAction {
                        if (activeView === view) dismissImmediateLocked()
                    }
                    .start()
            }
        }
        primaryDismissRunnable = primary
        mainHandler.postDelayed(primary, durationMs)

        // 2. Hard watchdog fail-safe (+1.5s) in case animation callback never fires
        val watchdog = Runnable {
            if (activeView === view) {
                dismissImmediateLocked()
            }
        }
        hardWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, durationMs + 1500L)
    }

    private fun dismissImmediateLocked() {
        primaryDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        hardWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        primaryDismissRunnable = null
        hardWatchdogRunnable = null

        val v = activeView
        val wm = activeWindowManager
        activeView = null
        activeWindowManager = null
        if (v != null && wm != null) {
            try {
                v.animate().cancel()
                wm.removeViewImmediate(v)
            } catch (_: Throwable) {
                try {
                    wm.removeView(v)
                } catch (_: Throwable) {
                }
            }
        }

        val bmp = activeBitmap
        activeBitmap = null
        if (bmp != null) {
            try {
                if (!bmp.isRecycled) bmp.recycle()
            } catch (_: Throwable) {
            }
        }
    }
}
