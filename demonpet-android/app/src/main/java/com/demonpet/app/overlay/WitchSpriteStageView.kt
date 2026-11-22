package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 60 FPS Fullscreen Visual Novel / Monster Girl Quest Stage View (#95, #96, #98, #99, #119, #120, #128):
 * - Renders the gothic blurred backdrop (with live pixelation/quality drain!)
 * - Rotating arcane magic circle behind the segmented Witch sprite
 * - Live2D-lite animated Witch sprite (breathing, sway, lunge zoom on attack, shake on hit, close-up during capture/drain!)
 * - Energy orbs physically drained upward from the player's Level bar into the Witch
 * - Heartbeat crimson/magenta vignette pulsing as player HP drops
 */
class WitchSpriteStageView(context: Context) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private var backdropBmp: Bitmap? = null
    private var degradedBackdropBmp: Bitmap? = null
    private var lastQualityBucket = 100
    private var spriteBmp: Bitmap? = null

    private var animRunning = false
    private var lastNs = 0L
    private var timeSec = 0f

    // Live2D-lite animation targets
    var captureZoomFactor = 0f      // 0f normal -> 1f intense close-up during Capture/Drain End Scene
    var drainIntensity = 0f         // 0f..1f controls quality degradation, orb stream, and magenta trance
    var hpRatio = 1f                // 1f..0f controls heartbeat vignette
    private var lungeAmount = 0f
    private var shakeAmount = 0f
    private var flashAlpha = 0f
    private var flashColor = Color.rgb(217, 70, 239)

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setWitchBitmaps(sprite: Bitmap, backdrop: Bitmap) {
        spriteBmp = sprite
        backdropBmp = backdrop
        degradedBackdropBmp?.let { if (!it.isRecycled) it.recycle() }
        degradedBackdropBmp = null
        lastQualityBucket = 100
        invalidate()
    }

    fun triggerWitchAttackLunge(intense: Boolean = false) {
        lungeAmount = if (intense) 1.0f else 0.65f
        flashColor = if (intense) Color.rgb(232, 70, 245) else Color.rgb(244, 63, 94)
        flashAlpha = if (intense) 155f else 95f
    }

    fun triggerWitchHitShake() {
        shakeAmount = 1.0f
        flashColor = Color.WHITE
        flashAlpha = 75f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animRunning) {
            animRunning = true
            lastNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        degradedBackdropBmp?.let { if (!it.isRecycled) it.recycle() }
        degradedBackdropBmp = null
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animRunning) return
        val dt = if (lastNs > 0L) ((frameTimeNanos - lastNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f) else 0.016f
        lastNs = frameTimeNanos
        timeSec += dt
        if (lungeAmount > 0f) lungeAmount = (lungeAmount - dt * 2.4f).coerceAtLeast(0f)
        if (shakeAmount > 0f) shakeAmount = (shakeAmount - dt * 3.2f).coerceAtLeast(0f)
        if (flashAlpha > 0f) flashAlpha = (flashAlpha - dt * 280f).coerceAtLeast(0f)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun getBackdropForQuality(raw: Bitmap): Bitmap {
        val qBucket = ((1f - drainIntensity * 0.85f) * 100f).toInt().coerceIn(12, 100) / 12 * 12
        if (qBucket >= 88) return raw
        if (qBucket == lastQualityBucket && degradedBackdropBmp?.isRecycled == false) {
            return degradedBackdropBmp!!
        }
        lastQualityBucket = qBucket
        degradedBackdropBmp?.let { if (!it.isRecycled) it.recycle() }
        val scale = (qBucket / 100f).coerceIn(0.10f, 0.90f)
        val tw = (raw.width * scale).toInt().coerceAtLeast(16)
        val th = (raw.height * scale).toInt().coerceAtLeast(16)
        val tiny = Bitmap.createScaledBitmap(raw, tw, th, true)
        val pix = Bitmap.createScaledBitmap(tiny, raw.width, raw.height, false)
        if (tiny !== pix) tiny.recycle()
        degradedBackdropBmp = pix
        return pix
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Fullscreen Gothic Blurred Backdrop (with live pixelation quality drain!)
        val bg = backdropBmp
        if (bg != null && !bg.isRecycled) {
            val activeBg = getBackdropForQuality(bg)
            canvas.drawBitmap(activeBg, null, RectF(0f, 0f, w, h), bmpPaint)
        } else {
            canvas.drawColor(Color.rgb(14, 7, 22))
        }

        // Atmospheric bottom-to-top dark gradient for VN readability
        val grad = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.argb(150, 12, 5, 20), Color.argb(45, 22, 8, 34), Color.argb(235, 10, 6, 16)),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, grad)

        // 2. Rotating Arcane Magic Circle behind Witch (#98)
        drawArcaneMagicCircle(canvas, w * 0.5f, h * 0.37f, minOf(w, h) * 0.44f)

        // 3. Fullscreen Live2D-lite Animated Witch Sprite (#95, #96)
        val sp = spriteBmp
        if (sp != null && !sp.isRecycled) {
            val aspect = sp.width.toFloat() / sp.height.coerceAtLeast(1).toFloat()
            val targetH = h * (0.76f + captureZoomFactor * 0.16f + lungeAmount * 0.10f)
            val targetW = (targetH * aspect).coerceAtLeast(w * 0.65f)
            val breathScaleY = 1f + sin(timeSec * 2.1f) * 0.016f
            val swayX = sin(timeSec * 1.15f) * 7f * density + sin(timeSec * 38f) * shakeAmount * 14f * density
            val topY = h * (0.10f - captureZoomFactor * 0.05f - lungeAmount * 0.03f)
            val left = (w - targetW) * 0.5f + swayX
            val dest = RectF(left, topY, left + targetW, topY + targetH)

            canvas.save()
            canvas.scale(1f, breathScaleY, dest.centerX(), dest.bottom)
            // Subtle magenta hypnosis color shift proportional to drainIntensity
            if (drainIntensity > 0.15f) {
                val cm = ColorMatrix().apply { setSaturation((1f - drainIntensity * 0.35f).coerceIn(0.55f, 1f)) }
                bmpPaint.colorFilter = ColorMatrixColorFilter(cm)
            } else {
                bmpPaint.colorFilter = null
            }
            canvas.drawBitmap(sp, null, dest, bmpPaint)
            bmpPaint.colorFilter = null
            canvas.restore()
        }

        // 4. Drained Energy Orbs flowing UP into the Witch (#119)
        if (drainIntensity > 0.05f || captureZoomFactor > 0.2f) {
            drawDrainedEnergyOrbs(canvas, w, h)
        }

        // 5. Heartbeat Crimson/Magenta Trance Vignette (#99)
        val stress = (1f - hpRatio).coerceIn(0f, 1f).coerceAtLeast(drainIntensity * 0.75f)
        if (stress > 0.12f) {
            val beat = 0.65f + 0.35f * abs(sin(timeSec * (2.6f + stress * 3.5f)))
            val vigAlpha = (stress * beat * 170f).toInt().coerceIn(0, 185)
            val vigColor = if (drainIntensity > 0.35f) Color.argb(vigAlpha, 192, 38, 211) else Color.argb(vigAlpha, 220, 38, 68)
            val vig = Paint().apply {
                shader = RadialGradient(
                    w * 0.5f, h * 0.42f, maxOf(w, h) * 0.62f,
                    intArrayOf(Color.TRANSPARENT, vigColor),
                    floatArrayOf(0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, vig)
        }

        // 6. Impact / Seduction Flash
        if (flashAlpha > 1f) {
            canvas.drawColor(Color.argb(flashAlpha.toInt().coerceIn(0, 180), Color.red(flashColor), Color.green(flashColor), Color.blue(flashColor)))
        }
    }

    private fun drawArcaneMagicCircle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val alpha = (95 + (drainIntensity * 110f).toInt()).coerceIn(85, 210)
        runePaint.color = if (drainIntensity > 0.35f) Color.argb(alpha, 232, 121, 249) else Color.argb(alpha, 244, 63, 94)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(timeSec * (14f + drainIntensity * 26f))
        canvas.drawCircle(0f, 0f, r, runePaint)
        canvas.drawCircle(0f, 0f, r * 0.86f, runePaint)

        // Hexagram Star
        for (offset in listOf(0.0, PI / 3.0)) {
            val tri = Path()
            for (i in 0 until 3) {
                val ang = offset + i * (2.0 * PI / 3.0)
                val x = (cos(ang) * r * 0.85f).toFloat()
                val y = (sin(ang) * r * 0.85f).toFloat()
                if (i == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
            }
            tri.close()
            canvas.drawPath(tri, runePaint)
        }
        // Rune ticks around outer ring
        for (i in 0 until 12) {
            val ang = i * (2.0 * PI / 12.0)
            val x1 = (cos(ang) * r * 0.86f).toFloat()
            val y1 = (sin(ang) * r * 0.86f).toFloat()
            val x2 = (cos(ang) * r).toFloat()
            val y2 = (sin(ang) * r).toFloat()
            canvas.drawLine(x1, y1, x2, y2, runePaint)
        }
        canvas.restore()
    }

    private fun drawDrainedEnergyOrbs(canvas: Canvas, w: Float, h: Float) {
        val count = (6 + (drainIntensity * 10).toInt()).coerceIn(6, 16)
        for (i in 0 until count) {
            val phase = (timeSec * (0.55f + drainIntensity * 0.5f) + i * 0.37f) % 1f
            val startX = w * (0.12f + (i * 0.76f / count))
            val targetX = w * 0.5f
            val x = startX + (targetX - startX) * phase + sin(timeSec * 3f + i) * 18f * density
            val y = h * 0.88f - phase * h * 0.52f
            val a = (sin(phase * PI).toFloat() * 235f).toInt().coerceIn(0, 235)
            val rad = (5f + (i % 3) * 2.5f) * density
            orbPaint.shader = RadialGradient(
                x, y, rad * 1.8f,
                intArrayOf(Color.argb(a, 255, 240, 255), Color.argb((a * 0.7f).toInt(), 217, 70, 239), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, rad * 1.8f, orbPaint)
        }
    }
}
