package com.blurshield.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import com.blurshield.app.ai.BlurRegion
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.MaskShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hardware-Accelerated 60–120 FPS Real-Time Blur & Pixelation Overlay View.
 * - Supports 5 blur styles (Gaussian, Mosaic, Frosted Glass, Solid Color, Solid Black)
 * - Supports 3 shapes (Organic 36-point Bézier face contour, Soft Oval, Rounded Rect)
 * - Supports 0ms scroll-delta tracking + velocity-smoothed Choreographer interpolation
 * - Optional real-time FPS / latency HUD pill
 */
class BlurOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private data class TrackedMask(
        val id: String,
        val kind: String,
        val currentRect: RectF,
        val targetRect: RectF,
        var contour: List<PointF>?,
        var rotationDeg: Float,
        var patch: Bitmap?,
        var avgColor: Int,
        var alpha: Float,
        var targetAlpha: Float,
        var lastSeenMs: Long
    )

    private val lock = Any()
    private val masks = ArrayList<TrackedMask>(14)
    private var animRunning = false

    @Volatile
    private var currentConfig: BlurConfigSnapshot? = null

    @Volatile
    private var lastInferenceMs: Long = 0L

    @Volatile
    private var activeModeLabel: String = "Auto"

    private var lastFrameNs: Long = 0L
    private var smoothedFps: Float = 60f

    private val mosaicPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    private val smoothBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 9, 9, 11)
        style = Paint.Style.FILL
    }

    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 52, 211, 153)
        style = Paint.Style.STROKE
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(228, 255, 240)
        textSize = 11f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    private val clipPath = Path()
    private val drawRect = RectF()
    private val hudRect = RectF()
    private val density = resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        hudBorderPaint.strokeWidth = 1f * density
    }

    fun updateConfig(cfg: BlurConfigSnapshot, modeLabel: String = activeModeLabel) {
        currentConfig = cfg
        activeModeLabel = modeLabel
        postInvalidateOnAnimation()
    }

    fun updateDetections(
        detected: List<BlurRegion>,
        inferenceMs: Long,
        cfg: BlurConfigSnapshot,
        modeLabel: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        currentConfig = cfg
        lastInferenceMs = inferenceMs
        activeModeLabel = modeLabel

        synchronized(lock) {
            val matchedIds = HashSet<String>(detected.size)

            for (det in detected) {
                val existing = findBestMatchLocked(det, matchedIds)
                if (existing != null) {
                    matchedIds.add(existing.id)
                    existing.targetRect.set(det.rect)
                    if (det.contour != null) existing.contour = det.contour
                    existing.rotationDeg = det.rotationDeg
                    if (det.patch != null) {
                        val old = existing.patch
                        existing.patch = det.patch
                        existing.avgColor = det.avgColor
                        if (old != null && old !== det.patch && !old.isRecycled) {
                            old.recycle()
                        }
                    } else if (cfg.blurStyle == BlurStyle.SOLID_BLACK || cfg.blurStyle == BlurStyle.SOLID_COLOR) {
                        recyclePatchSafely(existing)
                        existing.avgColor = det.avgColor
                    }
                    existing.targetAlpha = 1f
                    existing.lastSeenMs = nowMs
                } else {
                    val created = TrackedMask(
                        id = det.id,
                        kind = det.kind,
                        currentRect = RectF(det.rect),
                        targetRect = RectF(det.rect),
                        contour = det.contour,
                        rotationDeg = det.rotationDeg,
                        patch = det.patch,
                        avgColor = det.avgColor,
                        alpha = 0.4f,
                        targetAlpha = 1f,
                        lastSeenMs = nowMs
                    )
                    masks.add(created)
                    matchedIds.add(created.id)
                }
            }

            val holdGrace = cfg.smoothnessMode.holdGraceMs
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                if (!matchedIds.contains(m.id)) {
                    val age = nowMs - m.lastSeenMs
                    if (age > holdGrace) {
                        m.targetAlpha = 0f
                    }
                    if (m.alpha <= 0.03f && m.targetAlpha == 0f) {
                        recyclePatchSafely(m)
                        iter.remove()
                    }
                }
            }
        }
        post { ensureAnimating() }
    }

    fun shiftMasks(dxNorm: Float, dyNorm: Float) {
        if (abs(dxNorm) < 0.001f && abs(dyNorm) < 0.001f) return
        synchronized(lock) {
            if (masks.isEmpty()) return
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                m.currentRect.offset(dxNorm, dyNorm)
                m.targetRect.offset(dxNorm, dyNorm)
                m.contour = m.contour?.map { pt -> PointF(pt.x + dxNorm, pt.y + dyNorm) }
                if (m.currentRect.bottom < -0.06f || m.currentRect.top > 1.06f ||
                    m.currentRect.right < -0.06f || m.currentRect.left > 1.06f
                ) {
                    recyclePatchSafely(m)
                    iter.remove()
                }
            }
        }
        postInvalidateOnAnimation()
    }

    fun clearAllMasks() {
        synchronized(lock) {
            for (m in masks) m.targetAlpha = 0f
        }
        post { ensureAnimating() }
    }

    fun clearImmediate() {
        synchronized(lock) {
            for (m in masks) recyclePatchSafely(m)
            masks.clear()
        }
        postInvalidate()
    }

    fun getActiveBoxes(): List<RectF> {
        synchronized(lock) {
            return masks.filter { it.alpha > 0.15f }.map { RectF(it.currentRect) }
        }
    }

    fun getActiveCount(): Int = synchronized(lock) { masks.count { it.alpha > 0.15f } }

    private fun recyclePatchSafely(m: TrackedMask) {
        try {
            val b = m.patch
            m.patch = null
            if (b != null && !b.isRecycled) b.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun findBestMatchLocked(det: BlurRegion, alreadyMatched: Set<String>): TrackedMask? {
        var best: TrackedMask? = null
        var bestScore = 0.07f
        val dcx = det.rect.centerX()
        val dcy = det.rect.centerY()

        for (m in masks) {
            if (m.kind != det.kind || alreadyMatched.contains(m.id)) continue
            val overlap = rectIou(m.targetRect, det.rect)
            val dx = m.targetRect.centerX() - dcx
            val dy = m.targetRect.centerY() - dcy
            val dist = sqrt(dx * dx + dy * dy)
            val prox = (1f - dist * 3.0f).coerceAtLeast(0f) * 0.44f
            val score = overlap + prox
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    private fun rectIou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun ensureAnimating() {
        if (!animRunning) {
            animRunning = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNs > 0L) {
            val dtSec = (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            if (dtSec in 0.002f..0.25f) {
                val instantFps = (1f / dtSec).coerceIn(15f, 144f)
                smoothedFps = smoothedFps * 0.85f + instantFps * 0.15f
            }
        }
        lastFrameNs = frameTimeNanos

        val smooth = currentConfig?.smoothnessMode
        val posLerp = smooth?.posLerp ?: 0.46f
        val alphaIn = smooth?.alphaIn ?: 0.42f
        val alphaOut = smooth?.alphaOut ?: 0.26f

        var needsNextFrame = false
        synchronized(lock) {
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                m.currentRect.left += (m.targetRect.left - m.currentRect.left) * posLerp
                m.currentRect.top += (m.targetRect.top - m.currentRect.top) * posLerp
                m.currentRect.right += (m.targetRect.right - m.currentRect.right) * posLerp
                m.currentRect.bottom += (m.targetRect.bottom - m.currentRect.bottom) * posLerp

                val aRate = if (m.targetAlpha > m.alpha) alphaIn else alphaOut
                m.alpha += (m.targetAlpha - m.alpha) * aRate

                val moved = abs(m.targetRect.left - m.currentRect.left) > 0.0006f ||
                    abs(m.targetRect.top - m.currentRect.top) > 0.0006f ||
                    abs(m.targetRect.right - m.currentRect.right) > 0.0006f ||
                    abs(m.targetRect.bottom - m.currentRect.bottom) > 0.0006f ||
                    abs(m.targetAlpha - m.alpha) > 0.01f

                if (m.targetAlpha == 0f && m.alpha <= 0.03f) {
                    recyclePatchSafely(m)
                    iter.remove()
                } else if (moved) {
                    needsNextFrame = true
                }
            }
        }

        invalidate()

        if (needsNextFrame) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            animRunning = false
        }
    }

    override fun onDetachedFromWindow() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        clearImmediate()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return
        val cfg = currentConfig

        val style = cfg?.blurStyle ?: BlurStyle.GAUSSIAN
        val shape = cfg?.maskShape ?: MaskShape.ORGANIC
        val padBase = (cfg?.maskPaddingPct ?: 14) / 100f

        var drawnCount = 0
        synchronized(lock) {
            for (m in masks) {
                if (m.alpha <= 0.03f) continue
                drawnCount++
                val alphaInt = (m.alpha * 255f).toInt().coerceIn(0, 255)
                val isNsfw = m.kind == "nsfw"
                val pad = padBase + (if (isNsfw) 0.04f else 0f)

                drawRect.set(
                    (m.currentRect.left - m.currentRect.width() * pad) * w,
                    (m.currentRect.top - m.currentRect.height() * pad) * h,
                    (m.currentRect.right + m.currentRect.width() * pad) * w,
                    (m.currentRect.bottom + m.currentRect.height() * pad) * h
                )

                clipPath.reset()
                val contour = m.contour
                when {
                    shape == MaskShape.ORGANIC && !isNsfw && contour != null && contour.size >= 8 -> {
                        buildSmoothContourPath(clipPath, contour, w, h, 1f + pad * 0.9f)
                    }
                    shape == MaskShape.ROUNDED_RECT -> {
                        val corner = min(drawRect.width(), drawRect.height()) * 0.22f
                        clipPath.addRoundRect(drawRect, corner, corner, Path.Direction.CW)
                    }
                    else -> {
                        clipPath.addOval(drawRect, Path.Direction.CW)
                    }
                }

                canvas.save()
                if (abs(m.rotationDeg) > 2f && (shape != MaskShape.ORGANIC || contour == null || isNsfw)) {
                    canvas.rotate(m.rotationDeg, drawRect.centerX(), drawRect.centerY())
                }

                // Soft feathered outer rim matching scene color
                if (style != BlurStyle.SOLID_BLACK) {
                    haloPaint.strokeWidth = (if (style == BlurStyle.FROSTED) 2.5f else 7f) * density
                    haloPaint.color = if (style == BlurStyle.FROSTED) {
                        Color.argb((alphaInt * 0.55f).toInt(), 255, 255, 255)
                    } else {
                        Color.argb(
                            (alphaInt * 0.45f).toInt(),
                            Color.red(m.avgColor),
                            Color.green(m.avgColor),
                            Color.blue(m.avgColor)
                        )
                    }
                    canvas.drawPath(clipPath, haloPaint)
                }

                canvas.clipPath(clipPath)
                val patch = m.patch
                when (style) {
                    BlurStyle.SOLID_BLACK -> {
                        fillPaint.color = Color.argb(alphaInt, 10, 10, 14)
                        canvas.drawRect(drawRect, fillPaint)
                    }

                    BlurStyle.SOLID_COLOR -> {
                        fillPaint.color = Color.argb(
                            alphaInt,
                            Color.red(m.avgColor),
                            Color.green(m.avgColor),
                            Color.blue(m.avgColor)
                        )
                        canvas.drawRect(drawRect, fillPaint)
                    }

                    BlurStyle.MOSAIC -> {
                        if (patch != null && !patch.isRecycled) {
                            mosaicPaint.alpha = alphaInt
                            canvas.drawBitmap(patch, null, drawRect, mosaicPaint)
                        } else {
                            fillPaint.color = Color.argb(alphaInt, Color.red(m.avgColor), Color.green(m.avgColor), Color.blue(m.avgColor))
                            canvas.drawRect(drawRect, fillPaint)
                        }
                    }

                    BlurStyle.GAUSSIAN -> {
                        if (patch != null && !patch.isRecycled) {
                            smoothBitmapPaint.alpha = alphaInt
                            canvas.drawBitmap(patch, null, drawRect, smoothBitmapPaint)
                        } else {
                            fillPaint.color = Color.argb(alphaInt, Color.red(m.avgColor), Color.green(m.avgColor), Color.blue(m.avgColor))
                            canvas.drawRect(drawRect, fillPaint)
                        }
                    }

                    BlurStyle.FROSTED -> {
                        if (patch != null && !patch.isRecycled) {
                            smoothBitmapPaint.alpha = alphaInt
                            canvas.drawBitmap(patch, null, drawRect, smoothBitmapPaint)
                        }
                        fillPaint.color = Color.argb((alphaInt * 0.38f).toInt(), 240, 246, 255)
                        canvas.drawRect(drawRect, fillPaint)
                    }
                }

                canvas.restore()
            }
        }

        // Optional real-time Performance HUD
        if (cfg?.showPerfHud == true) {
            val label = "$activeModeLabel • ${lastInferenceMs}ms • ${smoothedFps.toInt()} FPS • $drawnCount zone${if (drawnCount > 1) "s" else ""}"
            val tw = hudTextPaint.measureText(label)
            val padH = 10f * density
            val topY = 38f * density
            val pillH = 24f * density
            hudRect.set(12f * density, topY, 12f * density + tw + padH * 2f, topY + pillH)
            canvas.drawRoundRect(hudRect, 12f * density, 12f * density, hudBgPaint)
            canvas.drawRoundRect(hudRect, 12f * density, 12f * density, hudBorderPaint)
            val textY = hudRect.centerY() - (hudTextPaint.descent() + hudTextPaint.ascent()) * 0.5f
            canvas.drawText(label, hudRect.left + padH, textY, hudTextPaint)
        }
    }

    private fun buildSmoothContourPath(
        path: Path,
        normPts: List<PointF>,
        w: Float,
        h: Float,
        expand: Float
    ) {
        var cx = 0f
        var cy = 0f
        for (p in normPts) {
            cx += p.x * w
            cy += p.y * h
        }
        cx /= normPts.size
        cy /= normPts.size

        val n = normPts.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            xs[i] = cx + (normPts[i].x * w - cx) * expand
            ys[i] = cy + (normPts[i].y * h - cy) * expand
        }

        val startMidX = (xs[n - 1] + xs[0]) * 0.5f
        val startMidY = (ys[n - 1] + ys[0]) * 0.5f
        path.moveTo(startMidX, startMidY)
        for (i in 0 until n) {
            val next = (i + 1) % n
            path.quadTo(xs[i], ys[i], (xs[i] + xs[next]) * 0.5f, (ys[i] + ys[next]) * 0.5f)
        }
        path.close()
    }
}
