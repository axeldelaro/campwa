package com.gazelock.app.overlay.minigames

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Brand-New Mini-Game #4: Visual Symbol Reflex Count (4 Rounds on real folder photos):
 * - Overlays 5..12 glowing non-overlapping neon icons (★ pink vs ◆ cyan) on a real folder photo.
 * - Answer the rapid visual question ("Combien de ★ ?", "Combien de ◆ ?", "Total ★+◆ ?") in 7.5s!
 * - Wrong pick or timeout = +5s penalty + triggers onMistake().
 */
class RapidMathImageGridView(
    context: Context,
    private val sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private data class IconDot(val isStar: Boolean, val nx: Float, val ny: Float)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var round = 1
    private val totalRounds = 4
    private var bgBitmap: Bitmap? = null
    private val icons = ArrayList<IconDot>()
    private var correctAnswer = 0
    private var roundEndMs = 0L
    private val roundDurMs = 7_500L
    private var locked = false
    private var solved = false
    private var released = false

    private lateinit var questionTv: TextView
    private lateinit var timerBar: ProgressBar
    private lateinit var arenaView: SymbolArenaCanvas
    private val answerButtons = ArrayList<Button>(4)

    private val tick = object : Runnable {
        override fun run() {
            if (released || solved) return
            if (!locked) {
                val rem = (roundEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                timerBar.progress = ((rem * 1000L) / roundDurMs).toInt()
                if (rem <= 0L) {
                    onPenaltySec(5); onMistake()
                    questionTv.text = "⏰ TROP LENT ! +5 s — Nouvelle image…"
                    questionTv.setTextColor(Color.rgb(252, 165, 165))
                    startRound()
                }
            }
            mainHandler.postDelayed(this, 45L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 16, 12, 30))
            setStroke(dp(2), Color.rgb(14, 165, 233))
        }

        addView(TextView(context).apply {
            text = "🔢 RÉFLEXE VISUEL DE SYMBOLES (4 MANCHES)"
            setTextColor(Color.rgb(125, 211, 252)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })

        questionTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        addView(questionTv)

        val sizeW = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(306))
        val sizeH = (sizeW * 0.72f).toInt()
        timerBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(56, 189, 248))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timerBar, LayoutParams(sizeW, dp(6)).apply { bottomMargin = dp(8) })

        arenaView = SymbolArenaCanvas(context)
        addView(arenaView, LayoutParams(sizeW, sizeH))

        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        for (i in 0 until 4) {
            val b = Button(context).apply {
                text = "0"; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(46, 36, 76)) }
                minHeight = 0; minimumHeight = 0; setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            answerButtons.add(b)
            row.addView(b, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(4); if (i < 3) marginEnd = dp(4)
            })
        }
        addView(row, LayoutParams(sizeW, ViewGroup.LayoutParams.WRAP_CONTENT))

        startRound()
        mainHandler.post(tick)
    }

    private fun startRound() {
        if (released) return
        try { if (bgBitmap != null && !bgBitmap!!.isRecycled) bgBitmap!!.recycle() } catch (_: Throwable) {}
        val valid = sourcePool.filter { !it.isRecycled }
        val src = if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
        bgBitmap = PuzzleBitmapHelper.prepareSquareBoardBitmap(src, 360)

        icons.clear()
        val stars = Random.nextInt(3, 8)
        val diamonds = Random.nextInt(2, 7)
        fun placeIcon(isStar: Boolean) {
            var bestX = 0.5f; var bestY = 0.5f
            for (attempt in 0 until 25) {
                val cx = 0.12f + Random.nextFloat() * 0.76f
                val cy = 0.16f + Random.nextFloat() * 0.68f
                if (icons.all { hypot((cx - it.nx).toDouble(), (cy - it.ny).toDouble()) > 0.16 }) {
                    bestX = cx; bestY = cy; break
                }
                bestX = cx; bestY = cy
            }
            icons.add(IconDot(isStar, bestX, bestY))
        }
        repeat(stars) { placeIcon(true) }
        repeat(diamonds) { placeIcon(false) }

        val qKind = Random.nextInt(3)
        val qLabel = when (qKind) {
            0 -> { correctAnswer = stars; "Manche $round/$totalRounds — Combien de ★ ROSES ?" }
            1 -> { correctAnswer = diamonds; "Manche $round/$totalRounds — Combien de ◆ BLEUS ?" }
            else -> { correctAnswer = stars + diamonds; "Manche $round/$totalRounds — TOTAL (★ + ◆) ?" }
        }
        questionTv.text = qLabel
        questionTv.setTextColor(Color.rgb(253, 224, 71))

        // Build 4 distinct answer choices including correctAnswer
        val opts = LinkedHashSet<Int>()
        opts.add(correctAnswer)
        val deltas = listOf(-2, -1, 1, 2, 3, -3).shuffled(Random)
        for (d in deltas) {
            if (opts.size >= 4) break
            val cand = correctAnswer + d
            if (cand >= 1) opts.add(cand)
        }
        val shuffled = opts.toList().shuffled(Random)
        for (i in 0 until 4) {
            val v = shuffled[i]
            val btn = answerButtons[i]
            btn.text = "$v"
            btn.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(46, 36, 76)) }
            btn.setOnClickListener { handleAnswer(v, btn) }
        }

        locked = false
        roundEndMs = SystemClock.elapsedRealtime() + roundDurMs
        arenaView.invalidate()
    }

    private fun handleAnswer(value: Int, btn: Button) {
        if (locked || solved) return
        locked = true
        if (value == correctAnswer) {
            btn.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            questionTv.text = "✅ EXACT ! ($round/$totalRounds)"
            questionTv.setTextColor(Color.rgb(134, 239, 172))
            mainHandler.postDelayed({
                if (released) return@postDelayed
                round++
                if (round > totalRounds) { solved = true; onSolved() } else startRound()
            }, 580L)
        } else {
            btn.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            questionTv.text = "❌ FAUX (c'était $correctAnswer) ! +5 s !"
            questionTv.setTextColor(Color.rgb(252, 165, 165))
            onPenaltySec(5); onMistake()
            mainHandler.postDelayed({ if (!released) startRound() }, 700L)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        try { if (bgBitmap != null && !bgBitmap!!.isRecycled) bgBitmap!!.recycle() } catch (_: Throwable) {}
        bgBitmap = null
    }

    private inner class SymbolArenaCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val veil = Paint().apply { color = Color.argb(120, 12, 8, 24) }
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 18, 12, 34) }
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(244, 114, 182); textSize = dp(20).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val diaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248); textSize = dp(20).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val b = bgBitmap
            if (b != null && !b.isRecycled) canvas.drawBitmap(b, null, RectF(0f, 0f, w, h), bmpPaint)
            canvas.drawRect(0f, 0f, w, h, veil)

            val rad = dp(15).toFloat()
            for (ic in icons) {
                val x = ic.nx * w; val y = ic.ny * h
                canvas.drawCircle(x, y, rad, badgeBg)
                canvas.drawText(if (ic.isStar) "★" else "◆", x, y + dp(7), if (ic.isStar) starPaint else diaPaint)
            }
        }
    }
}
