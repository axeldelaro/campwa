package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * #5 Compact Floating Ghost Micro-Popup during normal phone usage:
 * - Mounted in its own small WRAP_CONTENT window so the rest of the screen stays 100% touchable!
 * - Shows a real folder thumbnail + neon mantra + draining 6.5s bar + "👁️ VU !" button.
 * - Ignoring until 0.0s fires [onTimedOut] -> boosts Living Opacity (+6%) + vibration!
 */
class GhostMicroPopupView(
    context: Context,
    thumbnail: Bitmap?,
    mantra: String,
    private val durationMs: Long = 6_500L,
    private val onAcknowledged: () -> Unit,
    private val onTimedOut: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var bar: ProgressBar
    private val endElapsedMs = SystemClock.elapsedRealtime() + durationMs
    private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val rem = (endElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            bar.progress = ((rem * 1000L) / durationMs.coerceAtLeast(1L)).toInt()
            if (rem <= 0L) {
                finished = true
                onTimedOut()
            } else {
                mainHandler.postDelayed(this, 50L)
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(11), dp(9), dp(11), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(232, 24, 10, 38))
            setStroke(dp(2), Color.rgb(236, 72, 153))
        }

        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(250, 204, 21))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 255, 255, 255))
        }
        addView(bar, LayoutParams(dp(186), dp(4)).apply { bottomMargin = dp(6) })

        if (thumbnail != null && !thumbnail.isRecycled) {
            addView(ImageView(context).apply {
                setImageBitmap(thumbnail)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(dp(170), dp(82)).apply { bottomMargin = dp(6) })
        }

        addView(TextView(context).apply {
            text = "✨ $mantra"
            setTextColor(Color.rgb(249, 168, 212)); textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })

        addView(Button(context).apply {
            text = "👁️ VU ! (sinon Opacité +6 %)"
            isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(219, 39, 119)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                if (!finished) {
                    finished = true
                    mainHandler.removeCallbacks(tick)
                    onAcknowledged()
                }
            }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        mainHandler.post(tick)
    }

    fun release() {
        finished = true
        mainHandler.removeCallbacks(tick)
    }
}
