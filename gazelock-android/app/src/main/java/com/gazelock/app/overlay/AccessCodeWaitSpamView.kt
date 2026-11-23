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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository
import kotlin.random.Random

/**
 * Fullscreen 3-Minute (180s) Imposed Wait + 0.1s (100ms) Rapid Folder Image-Spam Access Code Gate:
 * - Preloads a memory-safe RGB_565 bitmap pool of images from the configured video folder
 *   (or extracted frames from the folder's videos if 0 photos exist) so 10 FPS (0.1s) display runs
 *   with zero lag and zero OutOfMemoryError!
 * - Canceling before 00:00 resets the full 3-minute wait.
 * - Reaching 00:00 reveals the newly issued 6-digit access code!
 */
class AccessCodeWaitSpamView(
    context: Context,
    private val cfg: GazeConfigSnapshot,
    private val onIssueCode: () -> String,
    private val onCodeUnlockedAndExit: ((String) -> Unit)? = null,
    private val onCancelWait: () -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var imageView: ImageView
    private lateinit var countdownTv: TextView
    private lateinit var subTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var actionBtn: Button
    private lateinit var revealCard: LinearLayout
    private lateinit var revealedCodeTv: TextView

    private val bitmapPool = ArrayList<Bitmap>()
    private var running = false
    private var endElapsedMs = 0L
    private var totalWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
    private var flashCounter = 0
    private var lastBmpIdx = -1

    private val spamTick = object : Runnable {
        override fun run() {
            if (!running) return
            val remMs = (endElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val elapsedMs = (totalWaitMs - remMs).coerceIn(0L, totalWaitMs)
            progressBar.progress = ((elapsedMs * 1000L) / totalWaitMs.coerceAtLeast(1L)).toInt()

            val remSec = ((remMs + 999L) / 1000L).toInt()
            countdownTv.text = String.format("⏳ CODE D'ACCÈS : %02d:%02d", remSec / 60, remSec % 60)

            // 0.1s (100ms) Rapid Image-Spam Swap!
            if (bitmapPool.isNotEmpty()) {
                var idx = Random.nextInt(bitmapPool.size)
                if (bitmapPool.size > 1 && idx == lastBmpIdx) idx = (idx + 1) % bitmapPool.size
                lastBmpIdx = idx
                val bmp = bitmapPool[idx]
                if (!bmp.isRecycled) {
                    imageView.setImageBitmap(bmp)
                    // Subtle dynamic scale jitter makes every 0.1s image pop!
                    val sc = 1.0f + (flashCounter % 3) * 0.03f
                    imageView.scaleX = sc; imageView.scaleY = sc
                }
                flashCounter++
                subTv.text = "🖼️ Spam dossier (0,1 s/image) • #$flashCounter • Attente obligatoire 3 min"
            } else {
                // Visual pulse fallback while loading
                setBackgroundColor(if (flashCounter++ % 2 == 0) Color.rgb(36, 8, 28) else Color.rgb(16, 8, 34))
                subTv.text = "⏳ Chargement des images du dossier… Attente obligatoire 3 min"
            }

            if (remMs <= 0L) {
                finishWaitAndRevealCode()
            } else {
                mainHandler.postDelayed(this, GazeConfig.IMAGE_SPAM_DELAY_MS)
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        // Layer 1: Fullscreen 0.1s Spam ImageView
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(imageView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 2: Top Translucent Countdown Pill
        val topBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(38), dp(16), dp(10))
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(215, 22, 8, 28))
                setStroke(dp(2), Color.rgb(225, 29, 72))
            }
        }
        countdownTv = TextView(context).apply {
            text = "⏳ CODE D'ACCÈS : 03:00"
            setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "⏳ Préparation du spam d'images (0,1 s)…"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        pill.addView(countdownTv)
        pill.addView(progressBar, LinearLayout.LayoutParams(dp(230), dp(6)).apply { topMargin = dp(5) })
        pill.addView(subTv)
        topBox.addView(pill)
        addView(topBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Bottom Cancel Button (resets the 3-minute wait!)
        actionBtn = Button(context).apply {
            text = "↩ Annuler l'attente (remet les 3 min à zéro)"
            isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(205, 42, 34, 62))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                stopAndRelease()
                onCancelWait()
            }
        }
        addView(
            actionBtn,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(28)
            }
        )

        // Layer 4: Code Revealed Screen (shown ONLY once 3:00 hits 00:00!)
        revealCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(242, 12, 9, 24))
            setPadding(dp(26), dp(26), dp(26), dp(26))
            visibility = View.GONE
        }
        revealCard.addView(TextView(context).apply {
            text = "🔓 CODE D'ACCÈS DÉBLOQUÉ !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        revealCard.addView(TextView(context).apply {
            text = "Tu as tenu les 3 minutes d'attente imposées. Voici ton code d'accès valable 2 minutes :"
            setTextColor(Color.rgb(215, 215, 235)); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14))
        })
        revealedCodeTv = TextView(context).apply {
            text = "------"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 36f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.rgb(28, 22, 48))
                setStroke(dp(2), Color.rgb(250, 204, 21))
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        revealCard.addView(revealedCodeTv)
        val confirmBtn = Button(context).apply {
            text = if (onCodeUnlockedAndExit != null) "✅ UTILISER LE CODE & DÉVERROUILLER" else "✅ FERMER"
            isAllCaps = false; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(20), dp(13), dp(20), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            setOnClickListener {
                val c = revealedCodeTv.text?.toString()?.replace(" ", "").orEmpty()
                stopAndRelease()
                if (onCodeUnlockedAndExit != null) onCodeUnlockedAndExit.invoke(c) else onCancelWait()
            }
        }
        revealCard.addView(confirmBtn)
        addView(revealCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun startThreeMinuteSpamWait() {
        if (running) return
        running = true
        flashCounter = 0
        lastBmpIdx = -1
        totalWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
        endElapsedMs = SystemClock.elapsedRealtime() + totalWaitMs

        // Preload folder image pool (or video frames fallback) off main thread while timer begins immediately!
        Thread {
            val loaded = VideoFeedRepository.loadSpamBitmapPool(context.applicationContext, cfg, maxPoolSize = 32)
            mainHandler.post {
                if (!running) {
                    for (b in loaded) if (!b.isRecycled) b.recycle()
                    return@post
                }
                bitmapPool.addAll(loaded)
            }
        }.start()

        mainHandler.post(spamTick)
    }

    private fun finishWaitAndRevealCode() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        val freshCode = onIssueCode()
        val pretty = if (freshCode.length == 6) "${freshCode.substring(0, 3)} ${freshCode.substring(3)}" else freshCode
        revealedCodeTv.text = pretty
        actionBtn.visibility = View.GONE
        revealCard.visibility = View.VISIBLE
    }

    fun stopAndRelease() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        imageView.setImageDrawable(null)
        for (b in bitmapPool) {
            try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
        bitmapPool.clear()
    }
}
