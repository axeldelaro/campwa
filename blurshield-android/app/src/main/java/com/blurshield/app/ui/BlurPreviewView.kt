package com.blurshield.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import com.blurshield.app.ai.FastStackBlur
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.FaceSubZone
import com.blurshield.app.config.MaskShape
import com.blurshield.app.overlay.BlurShaderEngine
import kotlin.math.min
import kotlin.math.sin

/**
 * Live Animated Interactive Preview of BlurShield AI v2.0:
 * Shows all 12 styles (including AGSL GPU Shaders, Reactive Emoji & Custom Image),
 * 6 surgical face sub-zones, 3D head-tilt rotation, silhouette/skin-refined contours,
 * edge feathering, opacity, padding, and ROI screen bounds.
 */
class BlurPreviewView(context: Context) : View(context), Choreographer.FrameCallback {

    private var config: BlurConfigSnapshot? = null
    private var baseSceneBitmap: Bitmap? = null
    private val shaderEngine = BlurShaderEngine()
    private val density = resources.displayMetrics.density
    private var animRunning = false
    private val startNs = System.nanoTime()
    private var timeSec = 0f

    private val mosaicPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val roiShadePaint = Paint().apply {
        color = Color.argb(145, 9, 9, 11)
        style = Paint.Style.FILL
    }
    private val roiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 228, 228, 231)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
        isFakeBoldText = true
    }

    private val clipPath = Path()
    private val faceRect = RectF()
    private val bodyRect = RectF()
    private val subRect = RectF()

    fun setConfig(cfg: BlurConfigSnapshot) {
        config = cfg
        val needsAnim = cfg.enabled && (cfg.blurStyle.isAnimated || cfg.headPose3dTracking)
        if (needsAnim) startAnim() else stopAnim()
        invalidate()
    }

    private fun startAnim() {
        if (!animRunning && isAttachedToWindow) {
            animRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopAnim() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animRunning) return
        timeSec = ((frameTimeNanos - startNs) / 1_000_000_000.0).toFloat()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val cfg = config
        if (cfg != null && cfg.enabled && (cfg.blurStyle.isAnimated || cfg.headPose3dTracking)) {
            startAnim()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 20 && h > 20) {
            baseSceneBitmap?.recycle()
            baseSceneBitmap = renderSyntheticPortrait(w, h)
        }
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        baseSceneBitmap?.recycle()
        baseSceneBitmap = null
        shaderEngine.close()
        super.onDetachedFromWindow()
    }

    private fun renderSyntheticPortrait(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.rgb(22, 32, 48), Color.rgb(44, 28, 58), Color.rgb(15, 24, 34)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 56, 189, 248) }
        c.drawCircle(w * 0.25f, h * 0.3f, h * 0.36f, glow)
        glow.color = Color.argb(48, 244, 114, 182)
        c.drawCircle(w * 0.76f, h * 0.68f, h * 0.40f, glow)

        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 180, 150) }
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 36, 32) }
        val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 34, 36) }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 110, 118) }
        val shirtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 65, 86) }

        // Left subject: Portrait face + shoulders
        val fx = w * 0.28f
        val fy = h * 0.46f
        val fr = min(w * 0.13f, h * 0.26f)
        c.drawOval(fx - fr * 1.5f, fy + fr * 0.85f, fx + fr * 1.5f, fy + fr * 2.5f, shirtPaint)
        c.drawOval(fx - fr * 1.18f, fy - fr * 1.28f, fx + fr * 1.18f, fy + fr * 0.85f, hairPaint)
        c.drawOval(fx - fr * 0.90f, fy - fr * 1.02f, fx + fr * 0.90f, fy + fr * 1.02f, skinPaint)
        c.drawCircle(fx - fr * 0.32f, fy - fr * 0.14f, fr * 0.11f, featurePaint)
        c.drawCircle(fx + fr * 0.32f, fy - fr * 0.14f, fr * 0.11f, featurePaint)
        c.drawRoundRect(fx - fr * 0.28f, fy + fr * 0.36f, fx + fr * 0.28f, fy + fr * 0.50f, 6f, 6f, accentPaint)

        // Right subject: Body / NSFW silhouette demo
        val bx = w * 0.72f
        val by = h * 0.52f
        val brW = min(w * 0.16f, h * 0.33f)
        val brH = h * 0.31f
        val torsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 168, 138) }
        c.drawRoundRect(bx - brW, by - brH, bx + brW, by + brH, brW * 0.48f, brW * 0.48f, torsoPaint)
        val contourTint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 146, 120) }
        c.drawCircle(bx - brW * 0.38f, by - brH * 0.10f, brW * 0.28f, contourTint)
        c.drawCircle(bx + brW * 0.38f, by - brH * 0.10f, brW * 0.28f, contourTint)
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = baseSceneBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val cfg = config

        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, 16f * density, 16f * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(scene, 0f, 0f, null)

        if (cfg != null && cfg.enabled) {
            val pad = cfg.maskPaddingPct / 100f
            val tiltDeg = if (cfg.headPose3dTracking) sin(timeSec * 1.9f) * 8.5f else 0f

            val fx = w * 0.28f
            val fy = h * 0.46f
            val fr = min(w * 0.13f, h * 0.26f)
            faceRect.set(
                fx - fr * (0.94f + pad),
                fy - fr * (1.06f + pad),
                fx + fr * (0.94f + pad),
                fy + fr * (1.06f + pad)
            )
            if (cfg.blurFaces || cfg.blurWholeBodySilhouette) {
                when (cfg.faceSubZone) {
                    FaceSubZone.EYES_ONLY -> {
                        subRect.set(fx - fr * (0.85f + pad * 0.5f), fy - fr * 0.40f, fx + fr * (0.85f + pad * 0.5f), fy + fr * 0.10f)
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = true, tiltDeg = tiltDeg, emoji = "🕶️")
                    }
                    FaceSubZone.MOUTH_ONLY -> {
                        subRect.set(fx - fr * (0.56f + pad * 0.4f), fy + fr * 0.18f, fx + fr * (0.56f + pad * 0.4f), fy + fr * 0.68f)
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = true, tiltDeg = tiltDeg, emoji = "🤐")
                    }
                    FaceSubZone.UPPER_FACE -> {
                        subRect.set(faceRect.left, faceRect.top, faceRect.right, fy + fr * 0.22f)
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = true, tiltDeg = tiltDeg, emoji = cfg.emojiPack.baseEmoji)
                    }
                    FaceSubZone.EYES_AND_MOUTH -> {
                        subRect.set(fx - fr * (0.85f + pad * 0.5f), fy - fr * 0.40f, fx + fr * (0.85f + pad * 0.5f), fy + fr * 0.10f)
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = true, tiltDeg = tiltDeg, emoji = "🕶️")
                        subRect.set(fx - fr * (0.56f + pad * 0.4f), fy + fr * 0.18f, fx + fr * (0.56f + pad * 0.4f), fy + fr * 0.68f)
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = true, tiltDeg = tiltDeg, emoji = "🤐")
                    }
                    FaceSubZone.HAIR_AND_HEAD -> {
                        subRect.set(fx - fr * (1.22f + pad), fy - fr * (1.35f + pad), fx + fr * (1.22f + pad), fy + fr * (1.08f + pad))
                        drawMaskRegion(canvas, scene, subRect, cfg, isFace = true, isSubBar = false, tiltDeg = tiltDeg, emoji = cfg.emojiPack.baseEmoji)
                    }
                    FaceSubZone.FULL_FACE -> {
                        val demoEmoji = if (cfg.emojiPack.isReactive) {
                            if (sin(timeSec * 1.4f) > 0.2f) "😄" else "😉"
                        } else cfg.emojiPack.baseEmoji
                        drawMaskRegion(canvas, scene, faceRect, cfg, isFace = true, isSubBar = false, tiltDeg = tiltDeg, emoji = demoEmoji)
                    }
                }
            }

            val bx = w * 0.72f
            val by = h * 0.52f
            val brW = min(w * 0.16f, h * 0.33f)
            val brH = h * 0.31f
            bodyRect.set(
                bx - brW * (0.95f + pad * 0.8f),
                by - brH * (0.88f + pad * 0.8f),
                bx + brW * (0.95f + pad * 0.8f),
                by + brH * (0.88f + pad * 0.8f)
            )
            if (cfg.anyNsfwEnabled || cfg.blurWholeBodySilhouette) {
                drawMaskRegion(canvas, scene, bodyRect, cfg, isFace = false, isSubBar = false, tiltDeg = 0f, emoji = "🛡️")
            }

            val topY = h * (cfg.roiTopPct / 100f)
            val botY = h * (cfg.roiBottomPct / 100f)
            if (topY > 1f) {
                canvas.drawRect(0f, 0f, w, topY, roiShadePaint)
                canvas.drawLine(0f, topY, w, topY, roiLinePaint)
            }
            if (botY < h - 1f) {
                canvas.drawRect(0f, botY, w, h, roiShadePaint)
                canvas.drawLine(0f, botY, w, botY, roiLinePaint)
            }
        }

        val faceCaption = if (cfg != null) "Visage (${cfg.faceSubZone.label})" else "Visage"
        val bodyCaption = if (cfg?.blurWholeBodySilhouette == true) "Silhouette Corps" else if (cfg?.skinToneRefinement == true) "NSFW (Peau IA)" else "Zone NSFW"
        canvas.drawText(faceCaption, w * 0.08f, h - 10f * density, labelPaint)
        canvas.drawText(bodyCaption, w * 0.56f, h - 10f * density, labelPaint)
        canvas.restore()
    }

    private fun drawMaskRegion(
        canvas: Canvas,
        scene: Bitmap,
        rect: RectF,
        cfg: BlurConfigSnapshot,
        isFace: Boolean,
        isSubBar: Boolean,
        tiltDeg: Float,
        emoji: String
    ) {
        val path = Path()
        when {
            isSubBar -> {
                val r = min(rect.width(), rect.height()) * 0.38f
                path.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            cfg.maskShape == MaskShape.ROUNDED_RECT -> {
                val r = min(rect.width(), rect.height()) * 0.24f
                path.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            isFace && (cfg.maskShape == MaskShape.ORGANIC || cfg.maskShape == MaskShape.SILHOUETTE) -> {
                val cx = rect.centerX()
                val top = rect.top
                val bot = rect.bottom
                val l = rect.left
                val r = rect.right
                val midY = rect.top + rect.height() * 0.44f
                path.moveTo(cx, top)
                path.cubicTo(r, top, r, midY, cx + rect.width() * 0.36f, bot - rect.height() * 0.14f)
                path.quadTo(cx, bot + rect.height() * 0.04f, cx - rect.width() * 0.36f, bot - rect.height() * 0.14f)
                path.cubicTo(l, midY, l, top, cx, top)
                path.close()
            }
            !isFace && (cfg.skinToneRefinement || cfg.maskShape == MaskShape.SILHOUETTE) -> {
                // Smooth contoured body silhouette preview
                val cx = rect.centerX()
                val top = rect.top
                val bot = rect.bottom
                val l = rect.left
                val r = rect.right
                val waistY = rect.top + rect.height() * 0.62f
                path.moveTo(cx, top)
                path.cubicTo(r, top, r, top + rect.height() * 0.35f, r - rect.width() * 0.08f, waistY)
                path.cubicTo(r - rect.width() * 0.02f, bot, cx + rect.width() * 0.22f, bot, cx, bot)
                path.cubicTo(cx - rect.width() * 0.22f, bot, l + rect.width() * 0.02f, bot, l + rect.width() * 0.08f, waistY)
                path.cubicTo(l, top + rect.height() * 0.35f, l, top, cx, top)
                path.close()
            }
            else -> path.addOval(rect, Path.Direction.CW)
        }

        val alphaInt = ((cfg.maskOpacityPct / 100f) * 255f).toInt().coerceIn(80, 255)
        canvas.save()
        if (kotlin.math.abs(tiltDeg) > 0.5f) {
            canvas.rotate(tiltDeg, rect.centerX(), rect.centerY())
        }

        if (cfg.edgeFeatherPct > 5 && cfg.blurStyle != BlurStyle.SOLID_BLACK && cfg.blurStyle != BlurStyle.EMOJI_REACTIVE) {
            strokePaint.strokeWidth = (1.5f + (cfg.edgeFeatherPct / 100f) * 8f) * density
            strokePaint.color = if (cfg.blurStyle == BlurStyle.FROSTED || cfg.blurStyle == BlurStyle.GPU_FLUTED_GLASS) {
                Color.argb((alphaInt * 0.45f).toInt(), 255, 255, 255)
            } else {
                Color.argb((alphaInt * 0.38f).toInt(), 210, 168, 140)
            }
            canvas.drawPath(path, strokePaint)
        }

        canvas.clipPath(path)

        val x1 = rect.left.toInt().coerceIn(0, scene.width - 2)
        val y1 = rect.top.toInt().coerceIn(0, scene.height - 2)
        val x2 = rect.right.toInt().coerceIn(x1 + 2, scene.width)
        val y2 = rect.bottom.toInt().coerceIn(y1 + 2, scene.height)
        val cw = (x2 - x1).coerceAtLeast(2)
        val ch = (y2 - y1).coerceAtLeast(2)
        val avgColor = Color.rgb(208, 164, 138)

        when (cfg.blurStyle) {
            BlurStyle.SOLID_BLACK -> {
                fillPaint.color = Color.argb(alphaInt, 12, 12, 16)
                canvas.drawRect(rect, fillPaint)
            }

            BlurStyle.SOLID_COLOR -> {
                fillPaint.color = Color.argb(alphaInt, 208, 164, 138)
                canvas.drawRect(rect, fillPaint)
            }

            BlurStyle.MOSAIC -> {
                val cols = (14 - cfg.blurIntensity).coerceIn(3, 12)
                val rows = ((cols * ch.toFloat() / cw.toFloat()).toInt()).coerceIn(3, 14)
                val patch = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
                for (ry in 0 until rows) {
                    val py = (y1 + ((ry + 0.5f) / rows) * ch).toInt().coerceIn(0, scene.height - 1)
                    for (rx in 0 until cols) {
                        val px = (x1 + ((rx + 0.5f) / cols) * cw).toInt().coerceIn(0, scene.width - 1)
                        patch.setPixel(rx, ry, scene.getPixel(px, py))
                    }
                }
                mosaicPaint.alpha = alphaInt
                canvas.drawBitmap(patch, null, rect, mosaicPaint)
                patch.recycle()
            }

            BlurStyle.GAUSSIAN, BlurStyle.FROSTED -> {
                val crop = Bitmap.createBitmap(scene, x1, y1, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, 38, 42, true)
                if (crop !== scaled) crop.recycle()
                val rad = (3 + cfg.blurIntensity * 1.4f).toInt().coerceIn(4, 18)
                FastStackBlur.blurInPlace(scaled, rad)
                smoothPaint.alpha = alphaInt
                canvas.drawBitmap(scaled, null, rect, smoothPaint)
                scaled.recycle()
                if (cfg.blurStyle == BlurStyle.FROSTED) {
                    fillPaint.color = Color.argb((alphaInt * 0.36f).toInt(), 240, 246, 255)
                    canvas.drawRect(rect, fillPaint)
                }
            }

            BlurStyle.GPU_BOKEH, BlurStyle.GPU_FLUTED_GLASS, BlurStyle.GPU_LIQUID_RIPPLE,
            BlurStyle.GPU_VORONOI_CRYSTAL, BlurStyle.GPU_CYBER_GLITCH -> {
                val crop = Bitmap.createBitmap(scene, x1, y1, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, 72, 72, true)
                if (crop !== scaled) crop.recycle()
                shaderEngine.drawGpuStyle(canvas, cfg.blurStyle, scaled, rect, avgColor, cfg.blurIntensity, alphaInt, timeSec)
                scaled.recycle()
            }

            BlurStyle.EMOJI_REACTIVE -> {
                val crop = Bitmap.createBitmap(scene, x1, y1, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, 34, 34, true)
                if (crop !== scaled) crop.recycle()
                FastStackBlur.blurInPlace(scaled, 10)
                shaderEngine.drawReactiveEmoji(canvas, rect, emoji, scaled, avgColor, alphaInt)
                scaled.recycle()
            }

            BlurStyle.CUSTOM_IMAGE -> {
                shaderEngine.drawCustomImage(canvas, rect, cfg, null, avgColor, alphaInt)
            }
        }
        canvas.restore()
    }
}
