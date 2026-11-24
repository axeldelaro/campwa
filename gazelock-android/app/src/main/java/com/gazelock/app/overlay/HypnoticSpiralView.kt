package com.gazelock.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * #21 Real-Time Rotating Hypnotic Optical Spiral + Concentric Pulse Rings (synced to Binaural Hz):
 * - Runs smoothly via Choreographer-backed postInvalidateOnAnimation() only while visible.
 * - Zero touch interference (works transparently over Ghost Pass-Through AND Full-Lock video!).
 */
class HypnoticSpiralView(
    context: Context,
    private val baseOpacityPct: Int,
    private val beatFreqHz: Int
) : View(context) {

    @Volatile
    private var intenseMode = false

    private val spiralPaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val spiralPaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pathA = Path()
    private val pathB = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    fun setIntenseMode(intense: Boolean) {
        intenseMode = intense
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || width <= 0 || height <= 0) return

        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxR = hypot(cx, cy) * 1.05f
        val tSec = SystemClock.elapsedRealtime() / 1000.0
        val speedMul = if (intenseMode) 2.25 else 1.0
        val baseAngle = (tSec * 2.1 * speedMul) % (2.0 * PI)

        val pulse = 0.70f + 0.30f * sin(tSec * 2.0 * PI * (beatFreqHz.coerceIn(4, 12) * 0.25)).toFloat()
        val alphaPct = if (intenseMode) (baseOpacityPct + 18).coerceAtMost(72) else baseOpacityPct.coerceIn(12, 55)
        val alphaByte = ((alphaPct / 100f) * 255f * pulse).toInt().coerceIn(20, 200)

        spiralPaintA.color = Color.argb(alphaByte, 236, 72, 153) // Neon magenta
        spiralPaintA.strokeWidth = width * 0.018f
        spiralPaintB.color = Color.argb((alphaByte * 0.85f).toInt(), 168, 85, 247) // Electric violet
        spiralPaintB.strokeWidth = width * 0.015f

        // 2-arm logarithmic/Archimedean twin spiral (lightweight 90-point polyline!)
        pathA.reset(); pathB.reset()
        val steps = 92
        val turns = 4.2 * 2.0 * PI
        for (i in 0..steps) {
            val frac = i.toFloat() / steps.toFloat()
            val r = frac * maxR
            val thetaA = baseAngle + frac * turns
            val thetaB = thetaA + PI
            val xA = cx + (r * cos(thetaA)).toFloat()
            val yA = cy + (r * sin(thetaA)).toFloat()
            val xB = cx + (r * cos(thetaB)).toFloat()
            val yB = cy + (r * sin(thetaB)).toFloat()
            if (i == 0) {
                pathA.moveTo(xA, yA); pathB.moveTo(xB, yB)
            } else {
                pathA.lineTo(xA, yA); pathB.lineTo(xB, yB)
            }
        }
        canvas.drawPath(pathA, spiralPaintA)
        canvas.drawPath(pathB, spiralPaintB)

        // Pulsing inward/outward concentric hypnotic rings
        ringPaint.strokeWidth = width * 0.008f
        val ringPhase = ((tSec * 0.85 * speedMul) % 1.0).toFloat()
        for (k in 0 until 5) {
            val f = (k / 5f + ringPhase) % 1.0f
            val r = f * maxR
            val ra = ((1f - f) * alphaByte * 0.85f).toInt().coerceIn(0, 180)
            ringPaint.color = Color.argb(ra, 253, 224, 71)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        postInvalidateOnAnimation()
    }
}
