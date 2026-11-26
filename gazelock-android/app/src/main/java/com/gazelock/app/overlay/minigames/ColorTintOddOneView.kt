package com.gazelock.app.overlay.minigames

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.random.Random

/**
 * Brand-New Mini-Game #3: Spot the Altered Copy (3x3 Grid, 4 Progressive Rounds):
 * - Displays 9 copies of the SAME real folder photo in a 3x3 grid.
 * - Exactly ONE tile has a visual alteration (hue shift / saturation / brightness / slight zoom)
 *   getting progressively subtler across Rounds 1 -> 4 under an 8.5s timer!
 * - Wrong tap or timeout = +5s penalty + triggers onMistake().
 */
class ColorTintOddOneView(
    context: Context,
    private val sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var round = 1
    private val totalRounds = 4
    private var oddCell = 0
    private var baseBitmap: Bitmap? = null
    private var alteredBitmap: Bitmap? = null
    private var roundEndMs = 0L
    private val roundDurMs = 8_500L
    private var feedbackHold = false
    private var pickedCell = -1
    private var solved = false
    private var released = false

    private lateinit var statusTv: TextView
    private lateinit var timerBar: ProgressBar
    private lateinit var gridCanvas: OddGridCanvas

    private val timerTick = object : Runnable {
        override fun run() {
            if (released || solved) return
            if (!feedbackHold) {
                val rem = (roundEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                timerBar.progress = ((rem * 1000L) / roundDurMs).toInt()
                if (rem <= 0L) {
                    statusTv.text = "⏰ TEMPS ÉCOULÉ ! +5 s — Nouvelle grille…"
                    statusTv.setTextColor(Color.rgb(252, 165, 165))
                    onPenaltySec(5); onMistake()
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
            setStroke(dp(2), Color.rgb(168, 85, 247))
        }

        addView(TextView(context).apply {
            text = "🎨 CHERCHE LA COPIE ALTÉRÉE (3×3)"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Parmi ces 9 copies de la même photo, UNE SEULE a une nuance/teinte différente ! Trouve-la en 8,5 s (+5 s si erreur)."
            setTextColor(Color.rgb(212, 204, 232)); textSize = 10.8f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(306))
        timerBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(192, 132, 252))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timerBar, LayoutParams(sizePx, dp(6)).apply { bottomMargin = dp(8) })

        gridCanvas = OddGridCanvas(context)
        addView(gridCanvas, LayoutParams(sizePx, sizePx))

        startRound()
        mainHandler.post(timerTick)
    }

    private fun clearBitmaps() {
        try { if (baseBitmap != null && !baseBitmap!!.isRecycled) baseBitmap!!.recycle() } catch (_: Throwable) {}
        try { if (alteredBitmap != null && !alteredBitmap!!.isRecycled) alteredBitmap!!.recycle() } catch (_: Throwable) {}
        baseBitmap = null; alteredBitmap = null
    }

    private fun startRound() {
        if (released) return
        clearBitmaps()
        val valid = sourcePool.filter { !it.isRecycled }
        val src = if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
        val base = PuzzleBitmapHelper.prepareSquareBoardBitmap(src, 240)
        baseBitmap = base
        alteredBitmap = makeAlteredVariant(base, round)
        oddCell = Random.nextInt(9)
        pickedCell = -1
        feedbackHold = false
        roundEndMs = SystemClock.elapsedRealtime() + roundDurMs
        statusTv.text = "🎨 MANCHE $round/$totalRounds — TROUVE LA DIFFÉRENCE !"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
        gridCanvas.invalidate()
    }

    private fun makeAlteredVariant(src: Bitmap, r: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val cm = when (r) {
            1 -> ColorMatrix(floatArrayOf( // Strong magenta/warm boost
                1.42f, 0f, 0f, 0f, 22f,
                0f, 0.72f, 0f, 0f, 0f,
                0f, 0f, 1.35f, 0f, 18f,
                0f, 0f, 0f, 1f, 0f
            ))
            2 -> ColorMatrix(floatArrayOf( // Cool cyan tint
                0.76f, 0f, 0f, 0f, 0f,
                0f, 1.24f, 0f, 0f, 14f,
                0f, 0f, 1.32f, 0f, 22f,
                0f, 0f, 0f, 1f, 0f
            ))
            3 -> ColorMatrix().apply { setSaturation(0.18f) } // Desaturated B&W tint
            else -> ColorMatrix(floatArrayOf( // Subtle golden brightness shift
                1.22f, 0f, 0f, 0f, 20f,
                0f, 1.18f, 0f, 0f, 16f,
                0f, 0f, 0.84f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun handlePick(cell: Int) {
        if (feedbackHold || solved || cell !in 0..8) return
        pickedCell = cell
        feedbackHold = true
        if (cell == oddCell) {
            statusTv.text = "✅ BIEN VU ! ($round/$totalRounds)"
            statusTv.setTextColor(Color.rgb(134, 239, 172))
            gridCanvas.invalidate()
            mainHandler.postDelayed({
                if (released) return@postDelayed
                round++
                if (round > totalRounds) { solved = true; onSolved() } else startRound()
            }, 620L)
        } else {
            statusTv.text = "❌ MAUVAISE CASE (+5 s) !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            onPenaltySec(5); onMistake()
            gridCanvas.invalidate()
            mainHandler.postDelayed({ if (!released) startRound() }, 700L)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        clearBitmaps()
    }

    private inner class OddGridCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val normStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(150, 255, 255, 255) }
        private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(16, 185, 129) }
        private val errStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(244, 63, 94) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val base = baseBitmap ?: return; val alt = alteredBitmap ?: return
            if (width <= 0 || height <= 0) return
            val cw = width / 3f; val ch = height / 3f; val gap = dp(3).toFloat()
            for (i in 0 until 9) {
                val r = i / 3; val c = i % 3
                val rect = RectF(c * cw + gap, r * ch + gap, (c + 1) * cw - gap, (r + 1) * ch - gap)
                val bmp = if (i == oddCell) alt else base
                if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                val st = when {
                    feedbackHold && i == oddCell -> okStroke
                    feedbackHold && i == pickedCell && pickedCell != oddCell -> errStroke
                    else -> normStroke
                }
                canvas.drawRect(rect, st)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                val c = (event.x / (width / 3f)).toInt().coerceIn(0, 2)
                val r = (event.y / (height / 3f)).toInt().coerceIn(0, 2)
                handlePick(r * 3 + c)
            }
            return true
        }
    }
}
