package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Brand-New Mini-Game #6: Strip Realignment (5-Strip Image Reorder — 2 Photos: Vertical then Horizontal):
 * - Slices a real folder photo into 5 tall vertical strips (Round 1) and 5 horizontal bands (Round 2).
 * - Tap 2 strips to swap them until the full image aligns cleanly (correctly placed strips lock emerald ✓!).
 * - Includes 1.5s Peek button for zero frustration!
 */
class JigsawEdgeSnapView(
    context: Context,
    private val sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var round = 1
    private val totalRounds = 2
    private var verticalMode = true
    private var fullBitmap: Bitmap? = null
    private val strips = ArrayList<Bitmap>(5)
    private val order = IntArray(5) { it }
    private var selectedSlot = -1
    private var showingPreview = false
    private var solved = false
    private var released = false

    private val statusTv: TextView
    private val stripCanvas: StripCanvas

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 16, 12, 30))
            setStroke(dp(2), Color.rgb(236, 72, 153))
        }

        addView(TextView(context).apply {
            text = "🎞️ RÉALIGNEMENT DE BANDES (2 PHOTOS)"
            setTextColor(Color.rgb(249, 168, 212)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Touche 2 bandes pour les permuter et reformer les 2 photos du dossier ! Les bandes vertes sont verrouillées."
            setTextColor(Color.rgb(212, 204, 232)); textSize = 10.8f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(305))
        stripCanvas = StripCanvas(context)
        addView(stripCanvas, LayoutParams(sizePx, sizePx))

        addView(Button(context).apply {
            text = "👁️ Voir le modèle (1,5 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LayoutParams(sizePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) }
            setOnClickListener { triggerPreview() }
        })

        startRound()
    }

    private fun clearStrips() {
        for (s in strips) { try { if (!s.isRecycled) s.recycle() } catch (_: Throwable) {} }
        strips.clear()
        try { if (fullBitmap != null && !fullBitmap!!.isRecycled) fullBitmap!!.recycle() } catch (_: Throwable) {}
        fullBitmap = null
    }

    private fun startRound() {
        if (released) return
        clearStrips()
        verticalMode = (round == 1)
        val valid = sourcePool.filter { !it.isRecycled }
        val src = if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
        val full = PuzzleBitmapHelper.prepareSquareBoardBitmap(src, 500)
        fullBitmap = full

        val step = full.width / 5
        for (i in 0 until 5) {
            val slice = if (verticalMode) {
                Bitmap.createBitmap(full, i * step, 0, step, full.height)
            } else {
                Bitmap.createBitmap(full, 0, i * step, full.width, step)
            }
            strips.add(slice)
        }

        // Guarantee at most 1 strip starts in its final spot
        var guard = 0
        do {
            val perm = (0 until 5).shuffled(Random)
            for (i in 0 until 5) order[i] = perm[i]
            guard++
        } while (countCorrect() > 1 && guard < 15)

        selectedSlot = -1
        updateStatus()
        stripCanvas.invalidate()
    }

    private fun countCorrect(): Int {
        var ok = 0; for (i in 0 until 5) if (order[i] == i) ok++; return ok
    }

    private fun updateStatus() {
        val dir = if (verticalMode) "verticales" else "horizontales"
        statusTv.text = "Photo $round/$totalRounds ($dir) • Bien placées : ${countCorrect()}/5"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
    }

    private fun triggerPreview() {
        if (solved || showingPreview) return
        showingPreview = true
        stripCanvas.invalidate()
        mainHandler.postDelayed({ showingPreview = false; stripCanvas.invalidate() }, 1_500L)
    }

    private fun handleSlotTap(slot: Int) {
        if (solved || showingPreview || slot !in 0..4) return
        if (order[slot] == slot) return // Already locked green!
        if (selectedSlot == -1) {
            selectedSlot = slot; stripCanvas.invalidate()
        } else if (selectedSlot == slot) {
            selectedSlot = -1; stripCanvas.invalidate()
        } else {
            val tmp = order[selectedSlot]; order[selectedSlot] = order[slot]; order[slot] = tmp
            selectedSlot = -1
            if (countCorrect() == 5) {
                statusTv.text = "✅ PHOTO $round/$totalRounds RÉALIGNÉE !"
                statusTv.setTextColor(Color.rgb(134, 239, 172))
                stripCanvas.invalidate()
                mainHandler.postDelayed({
                    if (released) return@postDelayed
                    round++
                    if (round > totalRounds) { solved = true; onSolved() } else startRound()
                }, 700L)
            } else {
                updateStatus()
                stripCanvas.invalidate()
            }
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        clearStrips()
    }

    private inner class StripCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(250, 204, 21) }
        private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.rgb(16, 185, 129) }
        private val normStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(150, 255, 255, 255) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val full = fullBitmap
            if ((showingPreview || countCorrect() == 5) && full != null && !full.isRecycled) {
                canvas.drawBitmap(full, null, RectF(0f, 0f, w, h), bmpPaint)
                return
            }
            if (strips.size < 5) return
            val gap = dp(2).toFloat()
            for (i in 0 until 5) {
                val rect = if (verticalMode) {
                    val sw = w / 5f
                    RectF(i * sw + gap, 0f, (i + 1) * sw - gap, h)
                } else {
                    val sh = h / 5f
                    RectF(0f, i * sh + gap, w, (i + 1) * sh - gap)
                }
                val bmp = strips[order[i]]
                if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                val st = when {
                    i == selectedSlot -> selStroke
                    order[i] == i -> okStroke
                    else -> normStroke
                }
                canvas.drawRect(rect, st)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                val slot = if (verticalMode) (event.x / (width / 5f)).toInt().coerceIn(0, 4)
                else (event.y / (height / 5f)).toInt().coerceIn(0, 4)
                handleSlotTap(slot)
            }
            return true
        }
    }
}
