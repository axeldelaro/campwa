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
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

/**
 * #16 Moving Sequence Mini-Game (1 -> 9) on a pulsing folder image background:
 * - 9 neon orbs numbered 1..9 continuously drift and bounce at 60 FPS across the folder photo.
 * - Must tap strictly in ascending order 1 -> 9!
 * - Wrong tap flashes crimson, adds +4s penalty (+ triggers onMistake!), and slightly accelerates orbs.
 * - Includes a "Slow Down (+5s)" helper unlocked after 16s so zero soft-lock!
 */
class MovingSequenceView(
    context: Context,
    sourceBitmap: Bitmap?,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private data class Orb(
        val number: Int,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var popped: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val bgBitmap: Bitmap = PuzzleBitmapHelper.prepareSquareBoardBitmap(sourceBitmap, 520)
    private val orbs = ArrayList<Orb>(9)
    private var nextExpected = 1
    private var mistakes = 0
    private var speedScale = 1.0f
    private var errFlashUntilMs = 0L
    private var solved = false
    private var slowUnlocked = false
    private var running = true

    private val statusTv: TextView
    private lateinit var arenaView: SequenceCanvas
    private lateinit var slowBtn: Button

    private val animTick = object : Runnable {
        override fun run() {
            if (!running || solved) return
            arenaView.stepPhysics()
            arenaView.invalidate()
            mainHandler.postDelayed(this, 16L)
        }
    }

    private val unlockSlowRunnable = Runnable {
        if (!solved) {
            slowUnlocked = true
            slowBtn.isEnabled = true
            slowBtn.alpha = 1.0f
            slowBtn.text = "🐢 Ralentir les cibles (+5 s)"
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(236, 16, 12, 30))
            setStroke(dp(2), Color.rgb(16, 185, 129))
        }

        addView(TextView(context).apply {
            text = "🎯 SÉQUENCE FUYANTE (1 → 9)"
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Touche les cibles mobiles dans l'ordre exact 1 → 9 sur l'image ! Erreur = +4 s + accélération !"
            setTextColor(Color.rgb(212, 204, 232)); textSize = 11.2f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(312))
        arenaView = SequenceCanvas(context)
        addView(arenaView, LayoutParams(sizePx, sizePx))

        slowBtn = Button(context).apply {
            text = "🐢 Ralentir (dans 16 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
            isEnabled = false; alpha = 0.45f
            layoutParams = LayoutParams(sizePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener {
                if (!slowUnlocked || solved) return@setOnClickListener
                onPenaltySec(5)
                speedScale = (speedScale * 0.42f).coerceAtLeast(0.28f)
                slowBtn.isEnabled = false; slowBtn.alpha = 0.45f; slowBtn.text = "🐢 Ralenti !"
            }
        }
        addView(slowBtn)

        updateStatus()
        mainHandler.post(animTick)
        mainHandler.postDelayed(unlockSlowRunnable, 16_000L)
    }

    private fun updateStatus() {
        statusTv.text = if (solved) {
            "✅ SÉQUENCE 1 → 9 COMPLÉTÉE !"
        } else {
            "Cible suivante : #$nextExpected  •  Erreurs : $mistakes (+4s)"
        }
    }

    fun release() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        try { if (!bgBitmap.isRecycled) bgBitmap.recycle() } catch (_: Throwable) {}
    }

    private inner class SequenceCanvas(ctx: Context) : View(ctx) {
        private val radiusPx = dp(23).toFloat()
        private val hitRadiusPx = dp(31).toFloat()
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(17).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private var initializedOrbs = false

        private fun ensureOrbsInitialized() {
            if (initializedOrbs || width <= 0 || height <= 0) return
            initializedOrbs = true
            orbs.clear()
            val pad = radiusPx + dp(6)
            for (n in 1..9) {
                val x = Random.nextFloat() * (width - pad * 2f) + pad
                val y = Random.nextFloat() * (height - pad * 2f) + pad
                val baseSpeed = dp(2).toFloat() * (0.95f + Random.nextFloat() * 0.65f)
                val angle = Random.nextDouble(0.0, Math.PI * 2.0)
                orbs.add(Orb(n, x, y, (kotlin.math.cos(angle) * baseSpeed).toFloat(), (kotlin.math.sin(angle) * baseSpeed).toFloat()))
            }
        }

        fun stepPhysics() {
            ensureOrbsInitialized()
            if (width <= 0 || height <= 0) return
            val pad = radiusPx + dp(4)
            for (o in orbs) {
                if (o.popped) continue
                o.x += o.vx * speedScale
                o.y += o.vy * speedScale
                if (o.x < pad) { o.x = pad; o.vx = kotlin.math.abs(o.vx) }
                else if (o.x > width - pad) { o.x = width - pad; o.vx = -kotlin.math.abs(o.vx) }
                if (o.y < pad) { o.y = pad; o.vy = kotlin.math.abs(o.vy) }
                else if (o.y > height - pad) { o.y = height - pad; o.vy = -kotlin.math.abs(o.vy) }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ensureOrbsInitialized()
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            if (!bgBitmap.isRecycled) canvas.drawBitmap(bgBitmap, null, RectF(0f, 0f, w, h), bmpPaint)
            val errActive = SystemClock.elapsedRealtime() < errFlashUntilMs
            veilPaint.color = if (errActive) Color.argb(155, 225, 29, 72) else Color.argb(110, 12, 8, 24)
            canvas.drawRect(0f, 0f, w, h, veilPaint)

            // Draw remaining orbs (draw target orb last so it's always cleanly clickable on top!)
            val remaining = orbs.filter { !it.popped }.sortedBy { if (it.number == nextExpected) 100 else it.number }
            for (o in remaining) {
                val isNext = (o.number == nextExpected)
                orbFill.color = if (isNext) Color.argb(235, 219, 39, 119) else Color.argb(215, 36, 26, 64)
                orbStroke.color = if (isNext) Color.rgb(253, 224, 71) else Color.argb(190, 192, 132, 252)
                canvas.drawCircle(o.x, o.y, radiusPx, orbFill)
                canvas.drawCircle(o.x, o.y, radiusPx, orbStroke)
                canvas.drawText("${o.number}", o.x, o.y + dp(6), numPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (solved || event.actionMasked != MotionEvent.ACTION_DOWN) return true
            val tx = event.x; val ty = event.y
            // Check exact target orb first (fair hit detection if orbs overlap!)
            val target = orbs.firstOrNull { !it.popped && it.number == nextExpected }
            if (target != null && hypot(tx - target.x, ty - target.y) <= hitRadiusPx) {
                target.popped = true
                nextExpected++
                if (nextExpected > 9) {
                    solved = true
                    mainHandler.removeCallbacks(unlockSlowRunnable)
                    slowBtn.isEnabled = false
                    statusTv.setTextColor(Color.rgb(134, 239, 172))
                    updateStatus()
                    invalidate()
                    mainHandler.postDelayed({ onSolved() }, 700L)
                } else {
                    updateStatus()
                    invalidate()
                }
                return true
            }

            // Did user tap a WRONG orb?
            val wrong = orbs.firstOrNull { !it.popped && it.number != nextExpected && hypot(tx - it.x, ty - it.y) <= hitRadiusPx }
            if (wrong != null) {
                mistakes++
                speedScale = (speedScale + 0.16f).coerceAtMost(1.85f)
                errFlashUntilMs = SystemClock.elapsedRealtime() + 360L
                onPenaltySec(4)
                onMistake()
                updateStatus()
                invalidate()
            }
            return true
        }
    }
}
