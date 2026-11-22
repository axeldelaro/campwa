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
 * 60 FPS Fullscreen Visual Novel / Monster Girl Quest Stage View (v6.0 — Android 16 HWUI Crash-Free!):
 * - NEVER calls manual Bitmap.recycle() during/near onDraw() (prevents Android 14–16 HWUI RenderThread SIGABRT crashes
 *   when transitioning into Phase 3 Drain End-Scenes!).
 * - Renders gothic blurred backdrop (with safe cached quality degradation), rotating arcane magic circle,
 *   Boss Enrage crimson-gold aura, Illusion Barrier hexagon shield, Mirror Clone twin effect,
 *   Hypnotic trance rings, Live2D-lite breathing/lunge/hit-shake, drained energy orbs, and heartbeat vignette!
 */
class WitchSpriteStageView(context: Context) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    @Volatile
    private var backdropBmp: Bitmap? = null
    @Volatile
    private var degradedBackdropBmp: Bitmap? = null
    private var lastQualityBucket = 100
    @Volatile
    private var spriteBmp: Bitmap? = null

    private var animRunning = false
    private var lastNs = 0L
    private var timeSec = 0f

    // Live2D-lite & RPG Status animation targets
    var captureZoomFactor = 0f       // 0f normal -> 1f intense close-up during Capture/Drain End Scene
    var drainIntensity = 0f          // 0f..1f controls quality degradation, orb stream, and magenta trance
    var hpRatio = 1f                 // 1f..0f controls heartbeat vignette
    var shieldActive = false         // Shimmering arcane barrier around Witch
    var mirrorCloneActive = false    // Ghostly twin silhouettes when Witch casts Mirage
    var enrageAuraActive = false     // Crimson-gold Boss Phase 2/3 Enrage aura
    var hypnoticSpiralActive = false // Pulsing hypnotic violet rings when Charmed/Hypnotized

    private var lungeAmount = 0f
    private var shakeAmount = 0f
    private var flashAlpha = 0f
    private var flashColor = Color.rgb(217, 70, 239)

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clonePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
    }
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
    }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hypnoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }

    fun setWitchBitmaps(sprite: Bitmap?, backdrop: Bitmap?) {
        spriteBmp = sprite
        backdropBmp = backdrop
        // Safe drop without manual .recycle() so HWUI RenderThread never reads a recycled buffer!
        degradedBackdropBmp = null
        lastQualityBucket = 100
        invalidate()
    }

    fun clearBitmaps() {
        spriteBmp = null
        backdropBmp = null
        degradedBackdropBmp = null
        lastQualityBucket = 100
        invalidate()
    }

    fun triggerWitchAttackLunge(intense: Boolean = false) {
        lungeAmount = if (intense) 1.0f else 0.65f
        flashColor = if (intense) Color.rgb(232, 70, 245) else Color.rgb(244, 63, 94)
        flashAlpha = if (intense) 155f else 95f
    }

    fun triggerWitchHitShake(critical: Boolean = false) {
        shakeAmount = if (critical) 1.35f else 1.0f
        flashColor = if (critical) Color.rgb(253, 224, 71) else Color.WHITE
        flashAlpha = if (critical) 130f else 75f
    }

    fun triggerPerfectParryFlash() {
        shakeAmount = 0.85f
        flashColor = Color.rgb(56, 189, 248)
        flashAlpha = 140f
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
        degradedBackdropBmp = null
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animRunning) return
        val dt = if (lastNs > 0L) ((frameTimeNanos - lastNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f) else 0.016f
        lastNs = frameTimeNanos
        timeSec += dt
        if (lungeAmount > 0f) lungeAmount = (lungeAmount - dt * 2.4f).coerceAtLeast(0f)
        if (shakeAmount > 0f) shakeAmount = (shakeAmount - dt * 3.0f).coerceAtLeast(0f)
        if (flashAlpha > 0f) flashAlpha = (flashAlpha - dt * 270f).coerceAtLeast(0f)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun getBackdropForQuality(raw: Bitmap): Bitmap {
        if (raw.isRecycled) return raw
        val qBucket = ((1f - drainIntensity * 0.80f) * 100f).toInt().coerceIn(16, 100) / 16 * 16
        if (qBucket >= 84) return raw
        val cached = degradedBackdropBmp
        if (qBucket == lastQualityBucket && cached != null && !cached.isRecycled) {
            return cached
        }
        return try {
            lastQualityBucket = qBucket
            val scale = (qBucket / 100f).coerceIn(0.14f, 0.85f)
            val tw = (raw.width * scale).toInt().coerceAtLeast(20)
            val th = (raw.height * scale).toInt().coerceAtLeast(20)
            val tiny = Bitmap.createScaledBitmap(raw, tw, th, true)
            val pix = Bitmap.createScaledBitmap(tiny, raw.width.coerceAtLeast(32), raw.height.coerceAtLeast(32), false)
            // Drop reference safely (NO manual recycle while RenderThread may still read previous frame!)
            degradedBackdropBmp = pix
            pix
        } catch (_: Throwable) {
            raw
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            drawStageSafely(canvas)
        } catch (_: Throwable) {
            canvas.drawColor(Color.rgb(14, 7, 24))
        }
    }

    private fun drawStageSafely(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Fullscreen Gothic Blurred Backdrop (safe against recycled bitmaps!)
        val bg = backdropBmp
        if (bg != null && !bg.isRecycled) {
            val activeBg = getBackdropForQuality(bg)
            if (!activeBg.isRecycled) {
                canvas.drawBitmap(activeBg, null, RectF(0f, 0f, w, h), bmpPaint)
            } else {
                canvas.drawColor(Color.rgb(14, 7, 22))
            }
        } else {
            canvas.drawColor(Color.rgb(14, 7, 22))
        }

        // Atmospheric VN gradient
        val grad = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.argb(155, 12, 5, 20), Color.argb(40, 22, 8, 34), Color.argb(238, 10, 6, 16)),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, grad)

        // 2. Rotating Arcane Magic Circle behind Witch
        drawArcaneMagicCircle(canvas, w * 0.5f, h * 0.36f, minOf(w, h) * 0.44f)

        // 3. Optional Hypnotic Trance Rings
        if (hypnoticSpiralActive || drainIntensity > 0.55f) {
            drawHypnoticRings(canvas, w * 0.5f, h * 0.36f, minOf(w, h) * 0.48f)
        }

        // 4. Fullscreen Live2D-lite Animated Witch Sprite
        val sp = spriteBmp
        if (sp != null && !sp.isRecycled) {
            val aspect = sp.width.toFloat() / sp.height.coerceAtLeast(1).toFloat()
            val targetH = h * (0.76f + captureZoomFactor * 0.16f + lungeAmount * 0.10f)
            val targetW = (targetH * aspect).coerceAtLeast(w * 0.65f)
            val breathScaleY = 1f + sin(timeSec * 2.2f) * 0.017f
            val swayX = sin(timeSec * 1.2f) * 7f * density + sin(timeSec * 40f) * shakeAmount * 14f * density
            val topY = h * (0.10f - captureZoomFactor * 0.05f - lungeAmount * 0.03f)
            val left = (w - targetW) * 0.5f + swayX
            val dest = RectF(left, topY, left + targetW, topY + targetH)

            // Optional Mirage Twin Clones
            if (mirrorCloneActive) {
                val offset = (34f + sin(timeSec * 3.1f) * 14f) * density
                clonePaint.alpha = 95
                canvas.drawBitmap(sp, null, RectF(dest.left - offset, dest.top + 8f, dest.right - offset, dest.bottom + 8f), clonePaint)
                canvas.drawBitmap(sp, null, RectF(dest.left + offset, dest.top + 8f, dest.right + offset, dest.bottom + 8f), clonePaint)
            }

            canvas.save()
            canvas.scale(1f, breathScaleY, dest.centerX(), dest.bottom)
            if (drainIntensity > 0.15f) {
                val cm = ColorMatrix().apply { setSaturation((1f - drainIntensity * 0.35f).coerceIn(0.55f, 1f)) }
                bmpPaint.colorFilter = ColorMatrixColorFilter(cm)
            } else {
                bmpPaint.colorFilter = null
            }
            canvas.drawBitmap(sp, null, dest, bmpPaint)
            bmpPaint.colorFilter = null
            canvas.restore()

            // Optional Arcane Barrier Shield
            if (shieldActive) {
                drawBarrierHexagon(canvas, w * 0.5f, h * 0.38f, minOf(w, h) * 0.34f)
            }
        }

        // 5. Drained Energy Orbs flowing UP into the Witch
        if (drainIntensity > 0.05f || captureZoomFactor > 0.2f || enrageAuraActive) {
            drawDrainedEnergyOrbs(canvas, w, h)
        }

        // 6. Heartbeat Crimson/Magenta Trance Vignette
        val stress = (1f - hpRatio).coerceIn(0f, 1f).coerceAtLeast(drainIntensity * 0.75f)
        if (stress > 0.12f) {
            val beat = 0.65f + 0.35f * abs(sin(timeSec * (2.6f + stress * 3.5f)))
            val vigAlpha = (stress * beat * 170f).toInt().coerceIn(0, 185)
            val vigColor = when {
                drainIntensity > 0.35f -> Color.argb(vigAlpha, 192, 38, 211)
                enrageAuraActive -> Color.argb(vigAlpha, 234, 88, 12)
                else -> Color.argb(vigAlpha, 220, 38, 68)
            }
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

        // 7. Impact / Parry / Critical Flash
        if (flashAlpha > 1f) {
            canvas.drawColor(Color.argb(flashAlpha.toInt().coerceIn(0, 180), Color.red(flashColor), Color.green(flashColor), Color.blue(flashColor)))
        }
    }

    private fun drawArcaneMagicCircle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val alpha = (95 + (drainIntensity * 110f).toInt() + if (enrageAuraActive) 45 else 0).coerceIn(85, 230)
        runePaint.color = when {
            enrageAuraActive -> Color.argb(alpha, 251, 146, 60)
            drainIntensity > 0.35f -> Color.argb(alpha, 232, 121, 249)
            else -> Color.argb(alpha, 244, 63, 94)
        }

        canvas.save()
        canvas.translate(cx, cy)
        val rotSpeed = 14f + drainIntensity * 26f + if (enrageAuraActive) 20f else 0f
        canvas.rotate(timeSec * rotSpeed)
        canvas.drawCircle(0f, 0f, r, runePaint)
        canvas.drawCircle(0f, 0f, r * 0.86f, runePaint)

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
        for (i in 0 until 12) {
            val ang = i * (2.0 * PI / 12.0)
            canvas.drawLine(
                (cos(ang) * r * 0.86f).toFloat(),
                (sin(ang) * r * 0.86f).toFloat(),
                (cos(ang) * r).toFloat(),
                (sin(ang) * r).toFloat(),
                runePaint
            )
        }
        canvas.restore()
    }

    private fun drawBarrierHexagon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = 0.72f + 0.28f * sin(timeSec * 5f)
        shieldPaint.color = Color.argb((195 * pulse).toInt().coerceIn(80, 225), 56, 189, 248)
        val hex = Path()
        for (i in 0 until 6) {
            val ang = (timeSec * 0.5) + i * (PI / 3.0)
            val x = cx + (cos(ang) * r).toFloat()
            val y = cy + (sin(ang) * r).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        canvas.drawPath(hex, shieldPaint)
    }

    private fun drawHypnoticRings(canvas: Canvas, cx: Float, cy: Float, maxR: Float) {
        for (i in 0 until 4) {
            val p = ((timeSec * 0.45f + i * 0.25f) % 1f)
            val rad = maxR * p
            val a = ((1f - p) * 145f).toInt().coerceIn(0, 155)
            hypnoPaint.color = Color.argb(a, 192, 132, 252)
            canvas.drawCircle(cx, cy, rad, hypnoPaint)
        }
    }

    private fun drawDrainedEnergyOrbs(canvas: Canvas, w: Float, h: Float) {
        val count = (7 + (drainIntensity * 11).toInt()).coerceIn(7, 18)
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
                intArrayOf(
                    Color.argb(a, 255, 240, 255),
                    if (enrageAuraActive) Color.argb((a * 0.75f).toInt(), 251, 146, 60) else Color.argb((a * 0.7f).toInt(), 217, 70, 239),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, rad * 1.8f, orbPaint)
        }
    }
}
