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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Mini-Game #15 (v7.0 TITAN): Moving Target Blitz ("Chasse aux Cibles Mouvantes")
 * - 3 rounds: folder thumbnails bounce at 60 FPS inside an arena.
 * - Tap the exact TARGET photo 4 times before the 10s timer runs out while dodging moving decoys!
 * - Wrong tap or timeout triggers onMistake() (+5s penalty).
 */
class TargetTapBlitzView(
    context: Context,
    poolBitmaps: List<Bitmap>,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private data class MovingTile(
        val bmp: Bitmap,
        val isTarget: Boolean,
        var cx: Float,
        var cy: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val safePool = ArrayList<Bitmap>()
    private val statusTv: TextView
    private val targetPreviewIv: ImageView
    private val timeBar: ProgressBar
    private val arenaView: BlitzArenaView

    private val totalRounds = 3
    private val hitsPerRound = 4
    private val roundDurationMs = 10_500L

    private var currentRound = 0
    private var hitsInRound = 0
    private var roundEndMs = 0L
    private var finished = false
    private var released = false

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(242, 14, 10, 26))
            setStroke(dp(2), Color.rgb(244, 63, 94))
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))

        val valid = poolBitmaps.filter { !it.isRecycled }.take(10)
        if (valid.isNotEmpty()) {
            for (b in valid) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(b, 180))
        } else {
            for (i in 0 until 6) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(null, 180))
        }
        while (safePool.size < 4) {
            safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(null, 180))
        }

        addView(TextView(context).apply {
            text = "🎯 CHASSE AUX CIBLES MOUVANTES"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })

        val targetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(4))
        }
        targetRow.addView(TextView(context).apply {
            text = "CIBLE À TOUCHER (×4) →  "
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        })
        targetPreviewIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setStroke(dp(2), Color.rgb(16, 185, 129))
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        targetRow.addView(targetPreviewIv, LayoutParams(dp(44), dp(44)))
        addView(targetRow)

        statusTv = TextView(context).apply {
            text = "Manche 1/$totalRounds • Touches : 0/$hitsPerRound"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11.4f; gravity = Gravity.CENTER
        }
        addView(statusTv)

        timeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(244, 63, 94))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(75, 255, 255, 255))
        }
        addView(timeBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6); bottomMargin = dp(8) })

        arenaView = BlitzArenaView(context)
        val arenaH = (context.resources.displayMetrics.heightPixels * 0.34f).toInt().coerceIn(dp(210), dp(310))
        addView(arenaView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, arenaH))
    }

    private val timerTick: Runnable = object : Runnable {
        override fun run() {
            if (finished || released) return
            val rem = (roundEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            timeBar.progress = ((rem * 1000L) / roundDurationMs).toInt()
            if (rem <= 0L) {
                onMistake()
                statusTv.text = "⏰ Trop lent ! +5 s · Nouvelle vague !"
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
        hitsInRound = 0
        roundEndMs = SystemClock.elapsedRealtime() + roundDurationMs
        statusTv.text = "Manche ${roundIdx + 1}/$totalRounds • Touches : 0/$hitsPerRound"
        statusTv.setTextColor(Color.rgb(216, 180, 254))

        val shuffled = safePool.shuffled(Random)
        val targetBmp = shuffled[0]
        targetPreviewIv.setImageBitmap(targetBmp)

        val tiles = ArrayList<MovingTile>()
        val speedBase = dp(3) + roundIdx * dp(1)
        val radius = dp(32).toFloat()
        // 2 copies of the target + 4 moving decoys
        val items = listOf(targetBmp to true, targetBmp to true) + shuffled.drop(1).take(4).map { it to false }
        for ((bmp, isTgt) in items) {
            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val spd = speedBase * (0.9f + Random.nextFloat() * 0.65f)
            tiles.add(
                MovingTile(
                    bmp = bmp,
                    isTarget = isTgt,
                    cx = dp(60) + Random.nextFloat() * dp(180),
                    cy = dp(50) + Random.nextFloat() * dp(150),
                    vx = (kotlin.math.cos(angle) * spd).toFloat(),
                    vy = (kotlin.math.sin(angle) * spd).toFloat(),
                    radius = radius
                )
            )
        }
        arenaView.setTiles(tiles)
        mainHandler.removeCallbacks(timerTick)
        mainHandler.post(timerTick)
    }

    private inner class BlitzArenaView(ctx: Context) : View(ctx) {
        private val tiles = ArrayList<MovingTile>()
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val bgPaint = Paint().apply { color = Color.rgb(22, 16, 38) }
        private val dst = RectF()
        private var flashRedUntil = 0L

        fun setTiles(list: List<MovingTile>) {
            tiles.clear()
            tiles.addAll(list)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val now = SystemClock.elapsedRealtime()
            bgPaint.color = if (now < flashRedUntil) Color.rgb(92, 16, 36) else Color.rgb(22, 16, 38)
            canvas.drawRoundRect(0f, 0f, w, h, dp(12).toFloat(), dp(12).toFloat(), bgPaint)

            for (t in tiles) {
                t.cx += t.vx
                t.cy += t.vy
                if (t.cx < t.radius) { t.cx = t.radius; t.vx = kotlin.math.abs(t.vx) }
                if (t.cx > w - t.radius) { t.cx = w - t.radius; t.vx = -kotlin.math.abs(t.vx) }
                if (t.cy < t.radius) { t.cy = t.radius; t.vy = kotlin.math.abs(t.vy) }
                if (t.cy > h - t.radius) { t.cy = h - t.radius; t.vy = -kotlin.math.abs(t.vy) }

                dst.set(t.cx - t.radius, t.cy - t.radius, t.cx + t.radius, t.cy + t.radius)
                if (!t.bmp.isRecycled) canvas.drawBitmap(t.bmp, null, dst, bmpPaint)
                strokePaint.color = Color.argb(180, 216, 180, 254)
                canvas.drawRect(dst, strokePaint)
            }
            if (!finished && !released) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (finished || released || event.actionMasked != MotionEvent.ACTION_DOWN) return true
            val tx = event.x; val ty = event.y
            val hit = tiles.reversed().firstOrNull { hypot((tx - it.cx).toDouble(), (ty - it.cy).toDouble()) <= it.radius * 1.18f }
            if (hit == null) return true
            if (hit.isTarget) {
                hitsInRound++
                // Teleport hit target & boost speed!
                hit.cx = hit.radius + Random.nextFloat() * (width - hit.radius * 2).coerceAtLeast(1f)
                hit.cy = hit.radius + Random.nextFloat() * (height - hit.radius * 2).coerceAtLeast(1f)
                hit.vx = -hit.vx * 1.12f; hit.vy = -hit.vy * 1.12f
                if (hitsInRound >= hitsPerRound) {
                    if (currentRound + 1 >= totalRounds) {
                        finished = true
                        mainHandler.removeCallbacks(timerTick)
                        statusTv.text = "✅ CIBLES ÉLIMINÉES !"
                        statusTv.setTextColor(Color.rgb(134, 239, 172))
                        mainHandler.postDelayed({ if (!released) onSolved() }, 360L)
                    } else {
                        startRound(currentRound + 1)
                    }
                } else {
                    statusTv.text = "Manche ${currentRound + 1}/$totalRounds • Touches : $hitsInRound/$hitsPerRound ✅"
                    statusTv.setTextColor(Color.rgb(134, 239, 172))
                }
            } else {
                flashRedUntil = SystemClock.elapsedRealtime() + 260L
                onMistake()
                statusTv.text = "❌ Leurre touché ! +5 s ! Touche la CIBLE !"
                statusTv.setTextColor(Color.rgb(252, 165, 165))
            }
            invalidate()
            return true
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(timerTick)
        for (b in safePool) if (!b.isRecycled) b.recycle()
        safePool.clear()
    }
}
