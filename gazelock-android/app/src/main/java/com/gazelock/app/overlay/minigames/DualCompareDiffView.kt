package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import kotlin.random.Random

/**
 * Mini-Game #18 (v7.0 TITAN): Rapid Twin-or-Trap Duel ("Photo Jumelle ou Piège ?")
 * - 6 rapid rounds (4.2s each): displays 2 folder photo frames side-by-side.
 * - Tap IDENTIQUES if exact twins, or DIFFÉRENTES if trap (different photo OR rotated/cropped variant!).
 */
class DualCompareDiffView(
    context: Context,
    poolBitmaps: List<Bitmap>,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val safePool = ArrayList<Bitmap>()
    private var tempTrapBmp: Bitmap? = null
    private val statusTv: TextView
    private val timeBar: ProgressBar
    private val leftIv: ImageView
    private val rightIv: ImageView

    private val totalRounds = 6
    private val roundMs = 4_200L
    private var currentRound = 0
    private var currentIsIdentical = true
    private var roundEndMs = 0L
    private var finished = false
    private var released = false

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(242, 14, 10, 26))
            setStroke(dp(2), Color.rgb(250, 204, 21))
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))

        val valid = poolBitmaps.filter { !it.isRecycled }.take(12)
        for (b in valid) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(b, 240))
        while (safePool.size < 4) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(null, 240))

        addView(TextView(context).apply {
            text = "⚖️ PHOTO JUMELLE OU PIÈGE ?"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        statusTv = TextView(context).apply {
            text = "Duel 1/$totalRounds • Identiques ou piège ?"
            setTextColor(Color.WHITE); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(5))
        }
        addView(statusTv)

        timeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(250, 204, 21))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timeBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { bottomMargin = dp(10) })

        val pairRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val imgSz = dp(124)
        leftIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.rgb(216, 180, 254)) }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        rightIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.rgb(216, 180, 254)) }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        pairRow.addView(leftIv, LayoutParams(imgSz, imgSz).apply { marginEnd = dp(8) })
        pairRow.addView(rightIv, LayoutParams(imgSz, imgSz).apply { marginStart = dp(8) })
        addView(pairRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val sameBtn = Button(context).apply {
            text = "✅ IDENTIQUES"; isAllCaps = false; textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(16, 150, 108)) }
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { submitAnswer(answeredIdentical = true) }
        }
        val diffBtn = Button(context).apply {
            text = "❌ PIÈGE / DIFF"; isAllCaps = false; textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { submitAnswer(answeredIdentical = false) }
        }
        btnRow.addView(sameBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        btnRow.addView(diffBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        addView(btnRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (finished || released) return
            val rem = (roundEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            timeBar.progress = ((rem * 1000L) / roundMs).toInt()
            if (rem <= 0L) {
                onMistake()
                statusTv.text = "⏰ Trop lent ! +4 s !"
                statusTv.setTextColor(Color.rgb(252, 165, 165))
                startRound(currentRound)
                return
            }
            mainHandler.postDelayed(this, 50L)
        }
    }

    init { startRound(0) }

    private fun startRound(roundIdx: Int) {
        currentRound = roundIdx
        roundEndMs = SystemClock.elapsedRealtime() + roundMs
        statusTv.text = "⚖️ Duel ${roundIdx + 1}/$totalRounds • Identiques ou Piège ?"
        statusTv.setTextColor(Color.WHITE)

        tempTrapBmp?.let { if (!it.isRecycled) it.recycle() }
        tempTrapBmp = null

        val picks = safePool.shuffled(Random)
        val base = picks[0]
        leftIv.setImageBitmap(base)

        currentIsIdentical = Random.nextBoolean()
        if (currentIsIdentical) {
            rightIv.setImageBitmap(base)
        } else {
            val trapKind = Random.nextInt(3)
            if (trapKind == 0 && picks.size > 1) {
                rightIv.setImageBitmap(picks[1])
            } else if (trapKind == 1) {
                // Subtle horizontal mirror trap!
                val m = Matrix().apply { postScale(-1f, 1f) }
                val flipped = Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
                tempTrapBmp = flipped
                rightIv.setImageBitmap(flipped)
            } else {
                // 90° rotated trap!
                val m = Matrix().apply { postRotate(90f) }
                val rot = Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
                tempTrapBmp = rot
                rightIv.setImageBitmap(rot)
            }
        }

        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    private fun submitAnswer(answeredIdentical: Boolean) {
        if (finished || released) return
        if (answeredIdentical == currentIsIdentical) {
            if (currentRound + 1 >= totalRounds) {
                finished = true
                mainHandler.removeCallbacks(tick)
                statusTv.text = "✅ 6/6 DUELS RÉUSSIS !"
                statusTv.setTextColor(Color.rgb(134, 239, 172))
                mainHandler.postDelayed({ if (!released) onSolved() }, 340L)
            } else {
                startRound(currentRound + 1)
            }
        } else {
            onMistake()
            statusTv.text = "❌ Raté ! +4 s · Duel suivant !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            startRound(currentRound)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(tick)
        tempTrapBmp?.let { if (!it.isRecycled) it.recycle() }
        tempTrapBmp = null
        for (b in safePool) if (!b.isRecycled) b.recycle()
        safePool.clear()
    }
}
