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
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bulletproof Triple-Fail-Safe Fullscreen Flash Overlay Manager (v20):
 * - Displays either a crisp white message on a solid black (#000000) screen (default 3s)
 *   OR a parent-selected fullscreen image for X seconds (strictly bounded 1..60s).
 * - Starts immediately at alpha = 1f (never stalls invisible on Android 12–15 WindowManager).
 * - Wakes the screen briefly if off/dim.
 * - Executes WindowManager.addView synchronously so any OEM/permission error is caught and
 *   automatically falls back to FlashOverlayActivity!
 * - Triple fail-safe guarantees ZERO risk of permanent screen freeze or lockup:
 *   1) Primary auto-dismiss timer removes view immediately after durationSec.
 *   2) Secondary hard-watchdog timer (+1.5s) calls removeViewImmediate.
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
        val safeSec = durationSeconds.coerceIn(1, 60)
        val appCtx = context.applicationContext

        wakeScreenBriefly(appCtx)

        if (!canShowOverlay(appCtx)) {
            val launched = FlashOverlayActivity.launch(
                appCtx,
                FlashOverlayActivity.Companion.FlashPayload(
                    mode = "text",
                    text = cleanMsg,
                    durationSec = safeSec
                )
            )
            return if (launched) {
                Pair(true, null)
            } else {
                Pair(false, "Autorisez « Afficher par-dessus les autres applis » sur le téléphone.")
            }
        }

        val overlayErr = runOnMainBlocking {
            try {
                dismissImmediateLocked()
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = appCtx.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val container = FrameLayout(appCtx).apply {
                    setBackgroundColor(Color.BLACK)
                    alpha = 1f
                    visibility = View.VISIBLE
                }

                val tv = TextView(appCtx).apply {
                    text = cleanMsg
                    setTextColor(Color.WHITE)
                    textSize = if (cleanMsg.length > 90) 21f else 28f
                    setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(32), dp(48), dp(32), dp(48))
                    setLineSpacing(dp(6).toFloat(), 1.12f)
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
                null
            } catch (t: Throwable) {
                dismissImmediateLocked()
                t.message ?: "overlay_error"
            }
        }

        if (overlayErr == null) return Pair(true, null)

        // Automatic fallback to dedicated Activity if WindowManager overlay failed on this OEM
        val fallbackOk = FlashOverlayActivity.launch(
            appCtx,
            FlashOverlayActivity.Companion.FlashPayload(
                mode = "text",
                text = cleanMsg,
                durationSec = safeSec
            )
        )
        return if (fallbackOk) {
            Pair(true, null)
        } else {
            Pair(false, "Affichage impossible sur le téléphone ($overlayErr).")
        }
    }

    fun showImage(
        context: Context,
        imageBytes: ByteArray,
        caption: String = "",
        durationSeconds: Int = 5
    ): Pair<Boolean, String?> {
        if (imageBytes.isEmpty()) return Pair(false, "Image vide.")
        val safeSec = durationSeconds.coerceIn(1, 60)
        val appCtx = context.applicationContext
        val cleanCaption = caption.trim().take(160)

        wakeScreenBriefly(appCtx)

        if (!canShowOverlay(appCtx)) {
            val launched = FlashOverlayActivity.launch(
                appCtx,
                FlashOverlayActivity.Companion.FlashPayload(
                    mode = "image",
                    imageBytes = imageBytes,
                    caption = cleanCaption,
                    durationSec = safeSec
                )
            )
            return if (launched) {
                Pair(true, null)
            } else {
                Pair(false, "Autorisez « Afficher par-dessus les autres applis » sur le téléphone.")
            }
        }

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

        val overlayErr = runOnMainBlocking {
            try {
                dismissImmediateLocked()
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = appCtx.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val root = FrameLayout(appCtx).apply {
                    setBackgroundColor(Color.BLACK)
                    alpha = 1f
                    visibility = View.VISIBLE
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
                        setBackgroundColor(Color.argb(190, 0, 0, 0))
                        setPadding(dp(20), dp(14), dp(20), dp(28))
                        gravity = Gravity.CENTER
                    }
                    val tv = TextView(appCtx).apply {
                        text = cleanCaption
                        setTextColor(Color.WHITE)
                        textSize = 18f
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
                null
            } catch (t: Throwable) {
                try {
                    if (!decoded.isRecycled) decoded.recycle()
                } catch (_: Throwable) {
                }
                dismissImmediateLocked()
                t.message ?: "overlay_error"
            }
        }

        if (overlayErr == null) return Pair(true, null)

        val fallbackOk = FlashOverlayActivity.launch(
            appCtx,
            FlashOverlayActivity.Companion.FlashPayload(
                mode = "image",
                imageBytes = imageBytes,
                caption = cleanCaption,
                durationSec = safeSec
            )
        )
        return if (fallbackOk) {
            Pair(true, null)
        } else {
            Pair(false, "Affichage image impossible ($overlayErr).")
        }
    }

    fun dismissNow() {
        mainHandler.post {
            dismissImmediateLocked()
        }
    }

    private fun wakeScreenBriefly(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SentinelCam::FlashWake"
                )
                wl.setReferenceCounted(false)
                wl.acquire(1_800L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun runOnMainBlocking(block: () -> String?): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val resultRef = AtomicReference<String?>("timeout")
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                resultRef.set(block())
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2_500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        return resultRef.get()
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

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm.addView(view, params)
        activeView = view
        activeWindowManager = wm
        activeBitmap = bitmap

        // 1. Primary deterministic dismiss timer (no stuck fade animation!)
        val primary = Runnable {
            if (activeView === view) {
                dismissImmediateLocked()
            }
        }
        primaryDismissRunnable = primary
        mainHandler.postDelayed(primary, durationMs)

        // 2. Hard watchdog fail-safe (+1.5s) guarantees overlay can NEVER stay stuck
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
