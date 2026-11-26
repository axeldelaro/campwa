package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.random.Random

/**
 * Mini-Game #17 (v7.0 TITAN): Micro-Detail Zoom Match ("Loupe Micro-Détail 3×")
 * - 4 rounds: shows a sharp 3.2x cropped zoom detail of one folder photo under a 9s timer.
 * - Identify the exact full source photo among 4 choices!
 */
class ZoomDetailMatchView(
    context: Context,
    poolBitmaps: List<Bitmap>,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val safePool = ArrayList<Bitmap>()
    private var activeCropBmp: Bitmap? = null
    private val statusTv: TextView
    private val timeBar: ProgressBar
    private val zoomIv: ImageView
    private val choicesGrid: LinearLayout

    private val totalRounds = 4
    private val roundMs = 9_000L
    private var currentRound = 0
    private var correctIdx = 0
    private var roundEndMs = 0L
    private var finished = false
    private var released = false

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(242, 14, 10, 26))
            setStroke(dp(2), Color.rgb(14, 165, 233))
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))

        val valid = poolBitmaps.filter { !it.isRecycled }.take(12)
        for (b in valid) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(b, 320))
        while (safePool.size < 4) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(null, 320))

        addView(TextView(context).apply {
            text = "🔍 LOUPE MICRO-DÉTAIL 3×"
            setTextColor(Color.rgb(125, 211, 252)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        statusTv = TextView(context).apply {
            text = "Quelle photo contient exactement ce détail zoomé ?"
            setTextColor(Color.rgb(220, 220, 240)); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(5))
        }
        addView(statusTv)

        timeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(14, 165, 233))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timeBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { bottomMargin = dp(8) })

        zoomIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(3), Color.rgb(250, 204, 21))
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        addView(zoomIv, LayoutParams(dp(124), dp(124)).apply { bottomMargin = dp(10) })

        choicesGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        addView(choicesGrid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (finished || released) return
            val rem = (roundEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            timeBar.progress = ((rem * 1000L) / roundMs).toInt()
            if (rem <= 0L) {
                onMistake()
                statusTv.text = "⏰ Temps écoulé ! +5 s !"
                statusTv.setTextColor(Color.rgb(252, 165, 165))
                startRound(currentRound)
                return
            }
            mainHandler.postDelayed(this, 60L)
        }
    }

    init { startRound(0) }

    private fun startRound(roundIdx: Int) {
        currentRound = roundIdx
        roundEndMs = SystemClock.elapsedRealtime() + roundMs
        statusTv.text = "🔍 Manche ${roundIdx + 1}/$totalRounds • Trouve la photo d'origine !"
        statusTv.setTextColor(Color.rgb(220, 220, 240))

        val picks = safePool.shuffled(Random).take(4)
        correctIdx = Random.nextInt(4)
        val src = picks[correctIdx]

        // Create a crisp 3.2x micro-crop from a random interior region!
        activeCropBmp?.let { if (!it.isRecycled) it.recycle() }
        val cropSize = (minOf(src.width, src.height) / 3.2f).toInt().coerceAtLeast(48)
        val maxX = (src.width - cropSize).coerceAtLeast(1)
        val maxY = (src.height - cropSize).coerceAtLeast(1)
        val rx = Random.nextInt(0, maxX)
        val ry = Random.nextInt(0, maxY)
        val cropped = Bitmap.createBitmap(src, rx, ry, cropSize, cropSize)
        activeCropBmp = cropped
        zoomIv.setImageBitmap(cropped)

        choicesGrid.removeAllViews()
        val cellPx = dp(88)
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (c in 0 until 2) {
                val idx = r * 2 + c
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.argb(160, 125, 211, 252))
                    }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    if (!picks[idx].isRecycled) setImageBitmap(picks[idx])
                    setOnClickListener { handleChoice(idx) }
                }
                row.addView(iv, LayoutParams(cellPx, cellPx).apply { setMargins(dp(6), dp(5), dp(6), dp(5)) })
            }
            choicesGrid.addView(row)
        }

        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    private fun handleChoice(idx: Int) {
        if (finished || released) return
        if (idx == correctIdx) {
            if (currentRound + 1 >= totalRounds) {
                finished = true
                mainHandler.removeCallbacks(tick)
                statusTv.text = "✅ LOUPE PARFAITE !"
                statusTv.setTextColor(Color.rgb(134, 239, 172))
                mainHandler.postDelayed({ if (!released) onSolved() }, 360L)
            } else {
                startRound(currentRound + 1)
            }
        } else {
            onMistake()
            statusTv.text = "❌ Mauvaise photo ! +5 s · Regarde mieux le détail !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(tick)
        activeCropBmp?.let { if (!it.isRecycled) it.recycle() }
        activeCropBmp = null
        for (b in safePool) if (!b.isRecycled) b.recycle()
        safePool.clear()
    }
}
