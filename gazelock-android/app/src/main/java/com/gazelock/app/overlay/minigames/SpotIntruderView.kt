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
 * #16 Spot the Intruder Flash Mini-Game (3 quick rounds using REAL folder images!):
 * - Phase 1 (2.2s): Memorize 4 folder photos displayed in a 2x2 grid.
 * - Phase 2 (0.38s): Brief hypnotic flash transition.
 * - Phase 3 (6.0s tight timer!): 1 of the 4 photos has been swapped for an intruder image!
 *   Tap the changed photo before 0.0s! Wrong pick or timeout = +6s penalty + triggers onMistake & retries round!
 */
class SpotIntruderView(
    context: Context,
    private val sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private enum class Phase { MEMORIZE, FLASH, PICK, FEEDBACK }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var round = 1
    private val totalRounds = 3
    private var phase = Phase.MEMORIZE
    private var phaseEndMs = 0L
    private var phaseDurMs = 2_200L
    private var intruderCell = 0
    private var pickedCell = -1
    private var lastPickCorrect = false
    private var solved = false
    private var released = false

    private val roundOrig = ArrayList<Bitmap>(4)
    private var intruderBitmap: Bitmap? = null

    private lateinit var statusTv: TextView
    private lateinit var timerBar: ProgressBar
    private lateinit var gridView: IntruderCanvas

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (released || solved) return
            val now = SystemClock.elapsedRealtime()
            val rem = (phaseEndMs - now).coerceAtLeast(0L)
            timerBar.progress = ((rem * 1000L) / phaseDurMs.coerceAtLeast(1L)).toInt()

            if (rem <= 0L) {
                when (phase) {
                    Phase.MEMORIZE -> enterFlashPhase()
                    Phase.FLASH -> enterPickPhase()
                    Phase.PICK -> {
                        // Timed out!
                        pickedCell = -1
                        lastPickCorrect = false
                        phase = Phase.FEEDBACK
                        phaseDurMs = 950L
                        phaseEndMs = now + phaseDurMs
                        statusTv.text = "⏰ TROP LENT ! +6 s — Nouvelle grille…"
                        statusTv.setTextColor(Color.rgb(252, 165, 165))
                        onPenaltySec(6)
                        onMistake()
                        gridView.invalidate()
                    }
                    Phase.FEEDBACK -> {
                        if (lastPickCorrect) {
                            round++
                            if (round > totalRounds) {
                                solved = true
                                statusTv.text = "✅ 3/3 INTRUS REPÉRÉS !"
                                statusTv.setTextColor(Color.rgb(134, 239, 172))
                                mainHandler.postDelayed({ onSolved() }, 550L)
                                return
                            }
                        }
                        startRound()
                    }
                }
            }
            mainHandler.postDelayed(this, 40L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(236, 16, 12, 30))
            setStroke(dp(2), Color.rgb(245, 158, 11))
        }

        addView(TextView(context).apply {
            text = "🔍 TROUVE L'INTRUS ÉCLAIR (3 MANCHES)"
            setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Mémorise les 4 images (2,2 s), puis touche celle qui a changé en moins de 6 s ! Erreur = +6 s !"
            setTextColor(Color.rgb(212, 204, 232)); textSize = 11.2f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12.2f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(306))
        timerBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(56, 189, 248))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timerBar, LayoutParams(sizePx, dp(6)).apply { bottomMargin = dp(8) })

        gridView = IntruderCanvas(context)
        addView(gridView, LayoutParams(sizePx, sizePx))

        startRound()
        mainHandler.post(tickRunnable)
    }

    private fun clearRoundBitmaps() {
        for (b in roundOrig) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        roundOrig.clear()
        try { if (intruderBitmap != null && !intruderBitmap!!.isRecycled) intruderBitmap!!.recycle() } catch (_: Throwable) {}
        intruderBitmap = null
    }

    private fun startRound() {
        clearRoundBitmaps()
        val valid = sourcePool.filter { !it.isRecycled }.shuffled(Random)
        for (i in 0 until 4) {
            val src = if (i < valid.size) valid[i] else null
            roundOrig.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(src, 260))
        }
        intruderCell = Random.nextInt(4)
        intruderBitmap = if (valid.size >= 5) {
            PuzzleBitmapHelper.prepareSquareBoardBitmap(valid[4], 260)
        } else {
            // Vivid color-inverted distinct intruder if folder has < 5 images!
            makeInvertedVariant(roundOrig[intruderCell])
        }

        pickedCell = -1
        lastPickCorrect = false
        phase = Phase.MEMORIZE
        phaseDurMs = 2_200L
        phaseEndMs = SystemClock.elapsedRealtime() + phaseDurMs
        timerBar.progressTintList = ColorStateList.valueOf(Color.rgb(56, 189, 248))
        statusTv.text = "👀 MANCHE $round/$totalRounds — MÉMORISE LES 4 IMAGES !"
        statusTv.setTextColor(Color.rgb(125, 211, 252))
        gridView.invalidate()
    }

    private fun makeInvertedVariant(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val cm = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun enterFlashPhase() {
        phase = Phase.FLASH
        phaseDurMs = 380L
        phaseEndMs = SystemClock.elapsedRealtime() + phaseDurMs
        statusTv.text = "⚡ FLASH…"
        gridView.invalidate()
    }

    private fun enterPickPhase() {
        phase = Phase.PICK
        phaseDurMs = 6_000L
        phaseEndMs = SystemClock.elapsedRealtime() + phaseDurMs
        timerBar.progressTintList = ColorStateList.valueOf(Color.rgb(245, 158, 11))
        statusTv.text = "🎯 MANCHE $round/$totalRounds — TOUCHE L'IMAGE CHANGÉE !"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
        gridView.invalidate()
    }

    private fun handlePick(cell: Int) {
        if (phase != Phase.PICK || cell !in 0..3) return
        pickedCell = cell
        phase = Phase.FEEDBACK
        phaseDurMs = 850L
        phaseEndMs = SystemClock.elapsedRealtime() + phaseDurMs
        if (cell == intruderCell) {
            lastPickCorrect = true
            statusTv.text = "✅ INTRUS TROUVÉ ! ($round/$totalRounds)"
            statusTv.setTextColor(Color.rgb(134, 239, 172))
        } else {
            lastPickCorrect = false
            statusTv.text = "❌ MAUVAISE IMAGE ! +6 s — On recommence !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            onPenaltySec(6)
            onMistake()
        }
        gridView.invalidate()
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        clearRoundBitmaps()
    }

    private inner class IntruderCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val borderNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.argb(170, 255, 255, 255)
        }
        private val borderOk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(5).toFloat(); color = Color.rgb(16, 185, 129)
        }
        private val borderErr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(5).toFloat(); color = Color.rgb(244, 63, 94)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            if (phase == Phase.FLASH) {
                canvas.drawColor(Color.rgb(225, 29, 72))
                return
            }
            if (roundOrig.size < 4) return

            val cw = w / 2f; val ch = h / 2f
            val gap = dp(4).toFloat()
            for (i in 0 until 4) {
                val r = i / 2; val c = i % 2
                val rect = RectF(c * cw + gap, r * ch + gap, (c + 1) * cw - gap, (r + 1) * ch - gap)
                val showIntruder = (phase == Phase.PICK || phase == Phase.FEEDBACK) && i == intruderCell
                val bmp = if (showIntruder) intruderBitmap else roundOrig[i]
                if (bmp != null && !bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                val st = when {
                    phase == Phase.FEEDBACK && i == intruderCell -> borderOk
                    phase == Phase.FEEDBACK && i == pickedCell && !lastPickCorrect -> borderErr
                    else -> borderNormal
                }
                canvas.drawRect(rect, st)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (phase == Phase.PICK && event.actionMasked == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                val c = if (event.x < width / 2f) 0 else 1
                val r = if (event.y < height / 2f) 0 else 1
                handlePick(r * 2 + c)
            }
            return true
        }
    }
}
