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
import android.view.View
import com.blurshield.app.ai.FastStackBlur
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.MaskShape
import kotlin.math.min

/**
 * Interactive real-time preview of BlurStyle, MaskShape, Intensity, Padding & ROI Zone.
 */
class BlurPreviewView(context: Context) : View(context) {

    private var config: BlurConfigSnapshot? = null
    private var baseSceneBitmap: Bitmap? = null
    private val density = resources.displayMetrics.density

    private val mosaicPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val roiShadePaint = Paint().apply {
        color = Color.argb(140, 9, 9, 11)
        style = Paint.Style.FILL
    }
    private val roiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 228, 228, 231)
        textSize = 10.5f * resources.displayMetrics.scaledDensity
    }

    private val clipPath = Path()
    private val faceRect = RectF()
    private val bodyRect = RectF()

    fun setConfig(cfg: BlurConfigSnapshot) {
        config = cfg
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 20 && h > 20) {
            baseSceneBitmap?.recycle()
            baseSceneBitmap = renderSyntheticPortrait(w, h)
        }
    }

    override fun onDetachedFromWindow() {
        baseSceneBitmap?.recycle()
        baseSceneBitmap = null
        super.onDetachedFromWindow()
    }

    private fun renderSyntheticPortrait(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.rgb(26, 35, 52), Color.rgb(42, 31, 56), Color.rgb(18, 26, 34)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // Subtle studio backdrop glow
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 56, 189, 248) }
        c.drawCircle(w * 0.25f, h * 0.3f, h * 0.35f, glow)
        glow.color = Color.argb(40, 244, 114, 182)
        c.drawCircle(w * 0.78f, h * 0.7f, h * 0.38f, glow)

        // Stylized portrait (left = Face demo, right = Body/NSFW zone demo)
        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 178, 148) }
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 38, 32) }
        val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 35, 35) }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 112, 116) }

        // Left subject: Portrait face
        val fx = w * 0.30f
        val fy = h * 0.48f
        val fr = min(w * 0.14f, h * 0.28f)
        c.drawOval(fx - fr * 1.15f, fy - fr * 1.2f, fx + fr * 1.15f, fy + fr * 0.9f, hairPaint)
        c.drawOval(fx - fr * 0.92f, fy - fr * 1.05f, fx + fr * 0.92f, fy + fr * 1.05f, skinPaint)
        // Eyes + mouth
        c.drawCircle(fx - fr * 0.32f, fy - fr * 0.12f, fr * 0.11f, featurePaint)
        c.drawCircle(fx + fr * 0.32f, fy - fr * 0.12f, fr * 0.11f, featurePaint)
        c.drawRoundRect(fx - fr * 0.28f, fy + fr * 0.36f, fx + fr * 0.28f, fy + fr * 0.48f, 6f, 6f, accentPaint)

        // Right subject: Sensitive torso region demo
        val bx = w * 0.70f
        val by = h * 0.54f
        val brW = min(w * 0.16f, h * 0.34f)
        val brH = h * 0.30f
        val torsoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 168, 138) }
        c.drawRoundRect(bx - brW, by - brH, bx + brW, by + brH, brW * 0.45f, brW * 0.45f, torsoPaint)
        c.drawCircle(bx - brW * 0.38f, by - brH * 0.12f, brW * 0.28f, Color.rgb(200, 146, 120).let { col ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        })
        c.drawCircle(bx + brW * 0.38f, by - brH * 0.12f, brW * 0.28f, Color.rgb(200, 146, 120).let { col ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        })
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = baseSceneBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val cfg = config

        // Rounded clip for the entire preview card
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, 16f * density, 16f * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(scene, 0f, 0f, null)

        if (cfg != null && cfg.enabled) {
            val pad = cfg.maskPaddingPct / 100f
            val fx = w * 0.30f
            val fy = h * 0.48f
            val fr = min(w * 0.14f, h * 0.28f)
            faceRect.set(
                fx - fr * (0.95f + pad),
                fy - fr * (1.08f + pad),
                fx + fr * (0.95f + pad),
                fy + fr * (1.08f + pad)
            )
            if (cfg.blurFaces) {
                drawMaskRegion(canvas, scene, faceRect, cfg, isFace = true)
            }

            val bx = w * 0.70f
            val by = h * 0.54f
            val brW = min(w * 0.16f, h * 0.34f)
            val brH = h * 0.30f
            bodyRect.set(
                bx - brW * (0.95f + pad),
                by - brH * (0.85f + pad),
                bx + brW * (0.95f + pad),
                by + brH * (0.85f + pad)
            )
            if (cfg.anyNsfwEnabled) {
                drawMaskRegion(canvas, scene, bodyRect, cfg, isFace = false)
            }

            // ROI active area visualization
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

        // Caption pills
        canvas.drawText("Visage", w * 0.22f, h - 10f * density, labelPaint)
        canvas.drawText("Zone NSFW", w * 0.60f, h - 10f * density, labelPaint)
        canvas.restore()
    }

    private fun drawMaskRegion(
        canvas: Canvas,
        scene: Bitmap,
        rect: RectF,
        cfg: BlurConfigSnapshot,
        isFace: Boolean
    ) {
        val path = Path()
        when (cfg.maskShape) {
            MaskShape.ROUNDED_RECT -> {
                val r = min(rect.width(), rect.height()) * 0.22f
                path.addRoundRect(rect, r, r, Path.Direction.CW)
            }
            MaskShape.OVAL, MaskShape.ORGANIC -> {
                if (isFace && cfg.maskShape == MaskShape.ORGANIC) {
                    // Organic chin-tapered face contour preview
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
                } else {
                    path.addOval(rect, Path.Direction.CW)
                }
            }
        }

        canvas.save()
        if (cfg.blurStyle == BlurStyle.FROSTED) {
            strokePaint.strokeWidth = 2f * density
            strokePaint.color = Color.argb(150, 255, 255, 255)
            canvas.drawPath(path, strokePaint)
        }
        canvas.clipPath(path)

        val x1 = rect.left.toInt().coerceIn(0, scene.width - 2)
        val y1 = rect.top.toInt().coerceIn(0, scene.height - 2)
        val x2 = rect.right.toInt().coerceIn(x1 + 2, scene.width)
        val y2 = rect.bottom.toInt().coerceIn(y1 + 2, scene.height)
        val cw = (x2 - x1).coerceAtLeast(2)
        val ch = (y2 - y1).coerceAtLeast(2)

        when (cfg.blurStyle) {
            BlurStyle.SOLID_BLACK -> {
                fillPaint.color = Color.rgb(12, 12, 16)
                canvas.drawRect(rect, fillPaint)
            }

            BlurStyle.SOLID_COLOR -> {
                fillPaint.color = Color.rgb(208, 164, 138)
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
                canvas.drawBitmap(patch, null, rect, mosaicPaint)
                patch.recycle()
            }

            BlurStyle.GAUSSIAN, BlurStyle.FROSTED -> {
                val crop = Bitmap.createBitmap(scene, x1, y1, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, 36, 40, true)
                if (crop !== scaled) crop.recycle()
                val rad = (3 + cfg.blurIntensity * 1.4f).toInt().coerceIn(4, 18)
                FastStackBlur.blurInPlace(scaled, rad)
                canvas.drawBitmap(scaled, null, rect, smoothPaint)
                scaled.recycle()
                if (cfg.blurStyle == BlurStyle.FROSTED) {
                    fillPaint.color = Color.argb(95, 240, 246, 255)
                    canvas.drawRect(rect, fillPaint)
                }
            }
        }
        canvas.restore()
    }
}
