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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Brand-New Mini-Game #5: Synchronized Precision Gauge Locks (4 Locks over a Folder Photo):
 * - A real folder photo reveals section by section as you break 4 neon precision locks!
 * - A glowing bar cursor sweeps left/right at increasing speed; tap "⚡ VERROUILLER !" inside
 *   the emerald target zone!
 * - Miss = +4s penalty + triggers onMistake()!
 */
class PrecisionGaugeStopView(
    context: Context,
    sourceBitmap: Bitmap?,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val photoBmp: Bitmap = PuzzleBitmapHelper.prepareSquareBoardBitmap(sourceBitmap, 480)
    private var locksCleared = 0
    private val totalLocks = 4
    private var cursorPos = 0.0f // 0..1
    private var cursorDir = 1f
    private var zoneStart = 0.38f
    private var zoneWidth = 0.24f
    private var speedPerSec = 1.05f
    private var lastFrameMs = SystemClock.elapsedRealtime()
    private var flashUntilMs = 0L
    private var lastHitOk = true
    private var solved = false
    private var running = true

    private lateinit var statusTv: TextView
    private lateinit var stageView: PrecisionCanvas
    private lateinit var stopBtn: Button

    private val animTick = object : Runnable {
        override fun run() {
            if (!running || solved) return
            val now = SystemClock.elapsedRealtime()
            val dtSec = ((now - lastFrameMs).coerceIn(8L, 60L)) / 1000f
            lastFrameMs = now

            cursorPos += cursorDir * speedPerSec * dtSec
            if (cursorPos >= 1f) { cursorPos = 1f; cursorDir = -1f }
            else if (cursorPos <= 0f) { cursorPos = 0f; cursorDir = 1f }

            stageView.invalidate()
            mainHandler.postDelayed(this, 16L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 16, 12, 30))
            setStroke(dp(2), Color.rgb(16, 185, 129))
        }

        addView(TextView(context).apply {
            text = "⏱️ VERROUS DE PRÉCISION (4 VERROUS)"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Frappe VERROUILLER pile quand le curseur traverse la zone verte ! La vitesse augmente à chaque verrou (+4 s si raté)."
            setTextColor(Color.rgb(212, 204, 232)); textSize = 10.8f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val sizeW = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(306))
        val sizeH = (sizeW * 0.82f).toInt()
        stageView = PrecisionCanvas(context).apply { setOnClickListener { handleStrike() } }
        addView(stageView, LayoutParams(sizeW, sizeH))

        stopBtn = Button(context).apply {
            text = "⚡ VERROUILLER !"
            isAllCaps = false; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LayoutParams(sizeW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { handleStrike() }
        }
        addView(stopBtn)

        configureLock(0)
        mainHandler.post(animTick)
    }

    private fun configureLock(level: Int) {
        zoneWidth = (0.25f - level * 0.03f).coerceAtLeast(0.14f)
        zoneStart = 0.08f + Random.nextFloat() * (0.84f - zoneWidth)
        speedPerSec = 1.05f + level * 0.28f
        statusTv.text = "Verrous brisés : $locksCleared / $totalLocks  •  Vitesse ${String.format("%.1fx", speedPerSec)}"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
    }

    private fun handleStrike() {
        if (solved || !running) return
        val inZone = cursorPos >= (zoneStart - 0.015f) && cursorPos <= (zoneStart + zoneWidth + 0.015f)
        flashUntilMs = SystemClock.elapsedRealtime() + 280L
        if (inZone) {
            lastHitOk = true
            locksCleared++
            if (locksCleared >= totalLocks) {
                solved = true
                stopBtn.isEnabled = false
                statusTv.text = "✅ 4/4 VERROUS BRISÉS !"
                statusTv.setTextColor(Color.rgb(134, 239, 172))
                stageView.invalidate()
                mainHandler.postDelayed({ onSolved() }, 650L)
            } else {
                configureLock(locksCleared)
                stageView.invalidate()
            }
        } else {
            lastHitOk = false
            onPenaltySec(4); onMistake()
            statusTv.text = "❌ HORS ZONE (+4 s) — Réessaie ! ($locksCleared/$totalLocks)"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            stageView.invalidate()
        }
    }

    fun release() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        try { if (!photoBmp.isRecycled) photoBmp.recycle() } catch (_: Throwable) {}
    }

    private inner class PrecisionCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val darkMask = Paint().apply { color = Color.argb(185, 16, 10, 30) }
        private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 26, 54) }
        private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 185, 129) }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(253, 224, 71) }
        private val flashPaint = Paint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val gaugeH = dp(42).toFloat()
            val imgH = (h - gaugeH - dp(12)).coerceAtLeast(dp(60).toFloat())

            // Draw photo + peel away 1 quadrant dark mask per lock cleared!
            if (!photoBmp.isRecycled) canvas.drawBitmap(photoBmp, null, RectF(0f, 0f, w, imgH), bmpPaint)
            val cw = w / 2f; val ch = imgH / 2f
            for (q in locksCleared until 4) {
                val r = q / 2; val c = q % 2
                canvas.drawRect(c * cw, r * ch, (c + 1) * cw, (r + 1) * ch, darkMask)
            }

            if (SystemClock.elapsedRealtime() < flashUntilMs) {
                flashPaint.color = if (lastHitOk) Color.argb(95, 16, 185, 129) else Color.argb(120, 244, 63, 94)
                canvas.drawRect(0f, 0f, w, imgH, flashPaint)
            }

            // Draw precision gauge bar at bottom
            val barTop = imgH + dp(10)
            val barRect = RectF(0f, barTop, w, h)
            canvas.drawRoundRect(barRect, dp(10).toFloat(), dp(10).toFloat(), barBg)

            val zRect = RectF(zoneStart * w, barTop + dp(3), (zoneStart + zoneWidth) * w, h - dp(3))
            canvas.drawRoundRect(zRect, dp(7).toFloat(), dp(7).toFloat(), zonePaint)

            val cx = cursorPos * w
            val cwBar = dp(6).toFloat()
            canvas.drawRoundRect(RectF(cx - cwBar, barTop - dp(2), cx + cwBar, h + dp(2)), dp(4).toFloat(), dp(4).toFloat(), cursorPaint)
        }
    }
}
