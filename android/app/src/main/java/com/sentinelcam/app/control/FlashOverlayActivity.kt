package com.sentinelcam.app.control

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * Triple-Fail-Safe Fallback Activity for Fullscreen Flash Text / Image Overlays.
 * Used automatically whenever WindowManager overlay permission is missing or OEM-restricted,
 * or when waking over the lock screen. Always self-closes after [1..60] seconds.
 */
class FlashOverlayActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var displayedBitmap: Bitmap? = null
    private var autoFinishAtMs: Long = 0L

    private val primaryFinish = Runnable {
        if (!isFinishing && !isDestroyed) finish()
    }
    private val watchdogFinish = Runnable {
        if (!isFinishing && !isDestroyed) finishAffinityOrSelf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWakeFlags()
        renderPendingPayload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderPendingPayload()
    }

    override fun onResume() {
        super.onResume()
        if (autoFinishAtMs > 0L && System.currentTimeMillis() >= autoFinishAtMs) {
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(primaryFinish)
        handler.removeCallbacks(watchdogFinish)
        recycleBitmap()
        super.onDestroy()
    }

    private fun applyWakeFlags() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } catch (_: Throwable) {
        }
    }

    private fun renderPendingPayload() {
        handler.removeCallbacks(primaryFinish)
        handler.removeCallbacks(watchdogFinish)
        recycleBitmap()

        val payload = pendingPayload.get()
        if (payload == null) {
            finish()
            return
        }

        val safeSec = payload.durationSec.coerceIn(1, 60)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        if (payload.mode == "image" && payload.imageBytes != null && payload.imageBytes.isNotEmpty()) {
            val bmp = decodeScaledBitmap(payload.imageBytes)
            if (bmp == null) {
                finish()
                return
            }
            displayedBitmap = bmp
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            root.addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val caption = payload.caption.trim().take(160)
            if (caption.isNotEmpty()) {
                val captionBox = LinearLayout(this).apply {
                    setBackgroundColor(Color.argb(190, 0, 0, 0))
                    setPadding(dp(20), dp(14), dp(20), dp(28))
                    gravity = Gravity.CENTER
                }
                val tv = TextView(this).apply {
                    text = caption
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
        } else {
            val msg = payload.text.trim().take(280)
            if (msg.isEmpty()) {
                finish()
                return
            }
            val tv = TextView(this).apply {
                text = msg
                setTextColor(Color.WHITE)
                textSize = if (msg.length > 90) 21f else 28f
                setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(48), dp(32), dp(48))
                setLineSpacing(dp(6).toFloat(), 1.12f)
            }
            root.addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        setContentView(root)
        val durationMs = safeSec * 1000L
        autoFinishAtMs = System.currentTimeMillis() + durationMs
        handler.postDelayed(primaryFinish, durationMs)
        handler.postDelayed(watchdogFinish, durationMs + 1500L)
    }

    private fun decodeScaledBitmap(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 1280) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun recycleBitmap() {
        val bmp = displayedBitmap
        displayedBitmap = null
        if (bmp != null) {
            try {
                if (!bmp.isRecycled) bmp.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun finishAffinityOrSelf() {
        try {
            finish()
        } catch (_: Throwable) {
        }
    }

    companion object {
        data class FlashPayload(
            val mode: String,
            val text: String = "",
            val imageBytes: ByteArray? = null,
            val caption: String = "",
            val durationSec: Int = 3
        )

        private val pendingPayload = AtomicReference<FlashPayload?>(null)

        fun launch(context: Context, payload: FlashPayload): Boolean {
            return try {
                pendingPayload.set(payload)
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, FlashOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                appCtx.startActivity(intent)
                true
            } catch (_: Throwable) {
                pendingPayload.set(null)
                false
            }
        }
    }
}
