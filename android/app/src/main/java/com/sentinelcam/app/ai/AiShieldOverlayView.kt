package com.sentinelcam.app.ai

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-fluid 60 FPS Organic Blur Shield Overlay.
 *
 * - Renders smooth 36-point Bézier face contours (ML Kit) & feathered organic ellipses (NSFW),
 *   NEVER ugly sharp squares.
 * - Uses Choreographer 60 FPS interpolation + alpha fade-in/out + scroll-shift support.
 * - Strictly FLAG_NOT_TOUCHABLE so the user can still scroll and interact normally.
 */
class AiShieldOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private data class TrackedMask(
        val id: String,
        val kind: String,
        val currentRect: RectF,
        val targetRect: RectF,
        var contour: List<PointF>?,
        var rotationDeg: Float,
        var alpha: Float,
        var targetAlpha: Float,
        var lastSeenMs: Long
    )

    private val lock = Any()
    private val masks = ArrayList<TrackedMask>(12)
    private var animRunning = false

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
    }
    private val path = Path()
    private val drawRect = RectF()

    init {
        setWillNotDraw(false)
        // Software layer allows BlurMaskFilter to render ultra-soft organic feathered edges smoothly
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun updateDetections(detected: List<DetectedMaskRegion>, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val matchedIds = HashSet<String>(detected.size)

            for (det in detected) {
                // Match to closest existing mask of same kind by IoU / center proximity for silky tracking
                val existing = findBestMatchLocked(det, matchedIds)
                if (existing != null) {
                    matchedIds.add(existing.id)
                    existing.targetRect.set(det.rect)
                    existing.contour = det.contour
                    existing.rotationDeg = det.rotationDeg
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
                        alpha = 0.25f,
                        targetAlpha = 1f,
                        lastSeenMs = nowMs
                    )
                    masks.add(created)
                    matchedIds.add(created.id)
                }
            }

            // Smoothly fade out masks that are no longer detected after a brief hold grace window
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                if (!matchedIds.contains(m.id)) {
                    val age = nowMs - m.lastSeenMs
                    if (age > HOLD_GRACE_MS) {
                        m.targetAlpha = 0f
                    }
                    if (m.alpha <= 0.02f && m.targetAlpha == 0f) {
                        iter.remove()
                    }
                }
            }
        }
        post { ensureAnimating() }
    }

    /**
     * Immediately shifts all active masks vertically when screen scroll is detected (0ms lag).
     */
    fun shiftMasksVertically(dyNorm: Float) {
        if (abs(dyNorm) < 0.002f) return
        synchronized(lock) {
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                m.currentRect.offset(0f, dyNorm)
                m.targetRect.offset(0f, dyNorm)
                m.contour = m.contour?.map { pt -> PointF(pt.x, pt.y + dyNorm) }
                if (m.currentRect.bottom < -0.05f || m.currentRect.top > 1.05f) {
                    iter.remove()
                }
            }
        }
        postInvalidateOnAnimation()
    }

    fun clearAllMasks() {
        synchronized(lock) {
            for (m in masks) {
                m.targetAlpha = 0f
            }
        }
        post { ensureAnimating() }
    }

    fun clearImmediate() {
        synchronized(lock) {
            masks.clear()
        }
        postInvalidate()
    }

    fun getCurrentNormalizedBoxes(): List<RectF> {
        synchronized(lock) {
            return masks.filter { it.alpha > 0.15f }.map { RectF(it.currentRect) }
        }
    }

    private fun findBestMatchLocked(det: DetectedMaskRegion, alreadyMatched: Set<String>): TrackedMask? {
        var best: TrackedMask? = null
        var bestScore = 0.08f
        val dcx = det.rect.centerX()
        val dcy = det.rect.centerY()

        for (m in masks) {
            if (m.kind != det.kind || alreadyMatched.contains(m.id)) continue
            val overlap = rectIou(m.targetRect, det.rect)
            val dx = m.targetRect.centerX() - dcx
            val dy = m.targetRect.centerY() - dcy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val prox = (1f - dist * 3.5f).coerceAtLeast(0f) * 0.4f
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
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        var needsNextFrame = false
        synchronized(lock) {
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                // Silky exponential smoothing at 60 FPS
                val lerp = 0.36f
                m.currentRect.left += (m.targetRect.left - m.currentRect.left) * lerp
                m.currentRect.top += (m.targetRect.top - m.currentRect.top) * lerp
                m.currentRect.right += (m.targetRect.right - m.currentRect.right) * lerp
                m.currentRect.bottom += (m.targetRect.bottom - m.currentRect.bottom) * lerp

                val alphaRate = if (m.targetAlpha > m.alpha) 0.28f else 0.16f
                m.alpha += (m.targetAlpha - m.alpha) * alphaRate

                val moved = abs(m.targetRect.left - m.currentRect.left) > 0.001f ||
                    abs(m.targetRect.top - m.currentRect.top) > 0.001f ||
                    abs(m.targetAlpha - m.alpha) > 0.01f

                if (m.targetAlpha == 0f && m.alpha <= 0.02f) {
                    iter.remove()
                } else if (moved) {
                    needsNextFrame = true
                }
            }
            if (masks.isNotEmpty()) {
                // Keep ticking gently until settled
                needsNextFrame = needsNextFrame || masks.any { abs(it.targetAlpha - it.alpha) > 0.01f }
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
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        synchronized(lock) {
            if (masks.isEmpty()) return

            for (m in masks) {
                if (m.alpha <= 0.02f) continue
                val a = (m.alpha * 255f).toInt().coerceIn(0, 255)
                val isNsfw = m.kind == "nsfw"

                val contour = m.contour
                if (!isNsfw && contour != null && contour.size >= 8) {
                    // Organic 36-point smooth Bézier face contour mask (expanded 14% around centroid)
                    drawOrganicContourMask(canvas, contour, w, h, a)
                } else {
                    // Soft organic elliptical frosted cloud (expanded 16% for faces, 22% for NSFW)
                    val padX = m.currentRect.width() * (if (isNsfw) 0.22f else 0.14f)
                    val padY = m.currentRect.height() * (if (isNsfw) 0.22f else 0.16f)
                    drawRect.set(
                        (m.currentRect.left - padX) * w,
                        (m.currentRect.top - padY) * h,
                        (m.currentRect.right + padX) * w,
                        (m.currentRect.bottom + padY) * h
                    )
                    drawOrganicCloudMask(canvas, drawRect, isNsfw, m.rotationDeg, a)
                }
            }
        }
    }

    private fun drawOrganicContourMask(
        canvas: Canvas,
        normPts: List<PointF>,
        w: Float,
        h: Float,
        alphaInt: Int
    ) {
        var cx = 0f
        var cy = 0f
        for (p in normPts) {
            cx += p.x * w
            cy += p.y * h
        }
        cx /= normPts.size
        cy /= normPts.size

        // Expand slightly outward so hair/chin/cheeks are smoothly covered
        val expand = 1.16f
        val pts = normPts.map { p ->
            PointF(
                cx + (p.x * w - cx) * expand,
                cy + (p.y * h - cy) * expand
            )
        }

        path.reset()
        val n = pts.size
        val startMidX = (pts[n - 1].x + pts[0].x) * 0.5f
        val startMidY = (pts[n - 1].y + pts[0].y) * 0.5f
        path.moveTo(startMidX, startMidY)
        for (i in 0 until n) {
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val midX = (cur.x + next.x) * 0.5f
            val midY = (cur.y + next.y) * 0.5f
            path.quadTo(cur.x, cur.y, midX, midY)
        }
        path.close()

        // Outer soft frosted feather halo
        featherPaint.color = Color.argb((alphaInt * 0.86f).toInt(), 24, 24, 28)
        canvas.drawPath(path, featherPaint)

        // Smooth frosted core
        corePaint.shader = null
        corePaint.color = Color.argb((alphaInt * 0.94f).toInt(), 32, 32, 38)
        canvas.drawPath(path, corePaint)
    }

    private fun drawOrganicCloudMask(
        canvas: Canvas,
        rect: RectF,
        isNsfw: Boolean,
        rotationDeg: Float,
        alphaInt: Int
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rx = (rect.width() * 0.5f).coerceAtLeast(18f)
        val ry = (rect.height() * 0.5f).coerceAtLeast(18f)
        val radius = max(rx, ry)

        canvas.save()
        if (abs(rotationDeg) > 2f) {
            canvas.rotate(rotationDeg, cx, cy)
        }

        // Layer 1: Wide diffused frosted halo (eliminates any harsh boundary)
        val outerColor = if (isNsfw) {
            Color.argb((alphaInt * 0.92f).toInt(), 20, 20, 24)
        } else {
            Color.argb((alphaInt * 0.84f).toInt(), 26, 26, 32)
        }
        featherPaint.color = outerColor
        canvas.drawOval(rect, featherPaint)

        // Layer 2: Soft radial frosted glass gradient core
        val coreAlpha = if (isNsfw) (alphaInt * 0.98f).toInt() else (alphaInt * 0.94f).toInt()
        val midAlpha = if (isNsfw) (alphaInt * 0.92f).toInt() else (alphaInt * 0.84f).toInt()
        corePaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                Color.argb(coreAlpha, 36, 36, 44),
                Color.argb(midAlpha, 24, 24, 30),
                Color.argb(0, 18, 18, 22)
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(rect, corePaint)
        corePaint.shader = null

        canvas.restore()
    }

    companion object {
        private const val HOLD_GRACE_MS = 380L
    }
}
