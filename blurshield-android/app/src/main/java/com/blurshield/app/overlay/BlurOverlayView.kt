package com.blurshield.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import com.blurshield.app.ai.BlurRegion
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.MaskShape
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BlurShield AI v2.0 Hardware-Accelerated 60–120 FPS Real-Time Overlay:
 * - #1 Pixel-Accurate Silhouette & #5 Skin-Tone Alpha Mask GPU Compositing (DST_IN)
 * - #2 Predictive 2D Kalman Velocity Tracker (Zero-Lag 120Hz motion extrapolation + contour glide)
 * - #3 Surgical Face Sub-Zone Rendering (Eyes / Mouth / Upper / Full / Hair)
 * - #4 3D Euler Head Angle Rotation Tracking (smooth angular Kalman interpolation)
 * - #9 Android 13+ AGSL GPU RuntimeShaders (Bokeh, Fluted Glass, Liquid, Voronoi, Cyber Glitch)
 * - #10 Reactive Emoji & Custom Gallery Image Masking
 * - Adjustable Edge Feathering (0..100%) & Opacity (35..100%) + Live FPS/Latency HUD
 */
class BlurOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private data class TrackedMask(
        val id: String,
        val kind: String,
        val currentRect: RectF,
        val targetRect: RectF,
        // Kalman 2D Velocity State (normalized units / second)
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vw: Float = 0f,
        var vh: Float = 0f,
        var currentAngle: Float = 0f,
        var targetAngle: Float = 0f,
        var vAngle: Float = 0f,
        var contour: List<PointF>?,
        var patch: Bitmap?,
        var alphaMask: Bitmap?,
        var avgColor: Int,
        var reactiveEmoji: String,
        var isSubZoneBar: Boolean,
        var alpha: Float,
        var targetAlpha: Float,
        var lastSeenMs: Long,
        var lastMeasMs: Long
    )

    private val lock = Any()
    private val masks = ArrayList<TrackedMask>(16)
    private val shaderEngine = BlurShaderEngine()
    private var animRunning = false

    @Volatile
    private var currentConfig: BlurConfigSnapshot? = null

    @Volatile
    private var lastInferenceMs: Long = 0L

    @Volatile
    private var activeModeLabel: String = "Auto"

    private var lastFrameNs: Long = 0L
    private var animStartNs: Long = System.nanoTime()
    private var currentTimeSec: Float = 0f
    private var smoothedFps: Float = 60f

    private val mosaicPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    private val smoothBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }

    private val maskClipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
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
        color = Color.argb(196, 9, 9, 11)
        style = Paint.Style.FILL
    }

    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 52, 211, 153)
        style = Paint.Style.STROKE
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(228, 255, 240)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
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
        post {
            if (cfg.blurStyle.isAnimated && getActiveCount() > 0) {
                ensureAnimating()
            } else {
                postInvalidateOnAnimation()
            }
        }
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

        val velGain = cfg.smoothnessMode.velGain
        val useKalman = cfg.kalmanPrediction

        synchronized(lock) {
            val matchedIds = HashSet<String>(detected.size)

            for (det in detected) {
                val existing = findBestMatchLocked(det, matchedIds)
                if (existing != null) {
                    matchedIds.add(existing.id)

                    // Feature #2: Predictive 2D Kalman Velocity Update (cx, cy, w, h, angle)
                    val dtSec = ((nowMs - existing.lastMeasMs).coerceIn(16L, 650L)) / 1000f
                    if (useKalman && dtSec in 0.016f..0.60f) {
                        val oldCx = existing.targetRect.centerX()
                        val oldCy = existing.targetRect.centerY()
                        val oldW = existing.targetRect.width()
                        val oldH = existing.targetRect.height()

                        val newCx = det.rect.centerX()
                        val newCy = det.rect.centerY()
                        val newW = det.rect.width()
                        val newH = det.rect.height()

                        val measVx = ((newCx - oldCx) / dtSec).coerceIn(-1.6f, 1.6f)
                        val measVy = ((newCy - oldCy) / dtSec).coerceIn(-1.6f, 1.6f)
                        val measVw = ((newW - oldW) / dtSec).coerceIn(-0.9f, 0.9f)
                        val measVh = ((newH - oldH) / dtSec).coerceIn(-0.9f, 0.9f)
                        val measVa = ((det.rotationDeg - existing.targetAngle) / dtSec).coerceIn(-180f, 180f)

                        // Adaptive dead-zone eliminates micro-jitter on static faces
                        existing.vx = if (abs(measVx) < 0.015f) existing.vx * 0.45f else existing.vx * (1f - velGain) + measVx * velGain
                        existing.vy = if (abs(measVy) < 0.015f) existing.vy * 0.45f else existing.vy * (1f - velGain) + measVy * velGain
                        existing.vw = existing.vw * (1f - velGain) + measVw * velGain
                        existing.vh = existing.vh * (1f - velGain) + measVh * velGain
                        existing.vAngle = existing.vAngle * (1f - velGain) + measVa * velGain
                    } else {
                        existing.vx = 0f
                        existing.vy = 0f
                        existing.vw = 0f
                        existing.vh = 0f
                        existing.vAngle = 0f
                    }

                    existing.targetRect.set(det.rect)
                    existing.targetAngle = det.rotationDeg
                    if (det.contour != null) existing.contour = det.contour
                    existing.reactiveEmoji = det.reactiveEmoji
                    existing.isSubZoneBar = det.isSubZoneBar

                    if (det.patch != null) {
                        val old = existing.patch
                        existing.patch = det.patch
                        existing.avgColor = det.avgColor
                        if (old != null && old !== det.patch && !old.isRecycled) old.recycle()
                    } else if (cfg.blurStyle == BlurStyle.SOLID_BLACK || cfg.blurStyle == BlurStyle.SOLID_COLOR || cfg.blurStyle == BlurStyle.CUSTOM_IMAGE) {
                        recycleBitmapSafely(existing.patch)
                        existing.patch = null
                        existing.avgColor = det.avgColor
                    }

                    if (det.alphaMask != null) {
                        val oldMask = existing.alphaMask
                        existing.alphaMask = det.alphaMask
                        if (oldMask != null && oldMask !== det.alphaMask && !oldMask.isRecycled) oldMask.recycle()
                    } else if (!cfg.silhouetteSegmentation && !cfg.skinToneRefinement) {
                        recycleBitmapSafely(existing.alphaMask)
                        existing.alphaMask = null
                    }

                    existing.targetAlpha = 1f
                    existing.lastSeenMs = nowMs
                    existing.lastMeasMs = nowMs
                } else {
                    val created = TrackedMask(
                        id = det.id,
                        kind = det.kind,
                        currentRect = RectF(det.rect),
                        targetRect = RectF(det.rect),
                        currentAngle = det.rotationDeg,
                        targetAngle = det.rotationDeg,
                        contour = det.contour,
                        patch = det.patch,
                        alphaMask = det.alphaMask,
                        avgColor = det.avgColor,
                        reactiveEmoji = det.reactiveEmoji,
                        isSubZoneBar = det.isSubZoneBar,
                        alpha = 0.45f,
                        targetAlpha = 1f,
                        lastSeenMs = nowMs,
                        lastMeasMs = nowMs
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
                        recycleMaskBitmaps(m)
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
                // Damp vertical velocity slightly during manual scroll to prevent double-shifting
                m.vy *= 0.35f
                if (m.currentRect.bottom < -0.08f || m.currentRect.top > 1.08f ||
                    m.currentRect.right < -0.08f || m.currentRect.left > 1.08f
                ) {
                    recycleMaskBitmaps(m)
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
            for (m in masks) recycleMaskBitmaps(m)
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

    private fun recycleMaskBitmaps(m: TrackedMask) {
        recycleBitmapSafely(m.patch)
        m.patch = null
        recycleBitmapSafely(m.alphaMask)
        m.alphaMask = null
    }

    private fun recycleBitmapSafely(b: Bitmap?) {
        try {
            if (b != null && !b.isRecycled) b.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun findBestMatchLocked(det: BlurRegion, alreadyMatched: Set<String>): TrackedMask? {
        // Exact tracking ID match first (keeps ML Kit face tracking IDs rock-solid across fast motion!)
        for (m in masks) {
            if (m.id == det.id && m.kind == det.kind && !alreadyMatched.contains(m.id)) {
                return m
            }
        }
        var best: TrackedMask? = null
        var bestScore = 0.06f
        val dcx = det.rect.centerX()
        val dcy = det.rect.centerY()

        for (m in masks) {
            if (m.kind != det.kind || alreadyMatched.contains(m.id)) continue
            val overlap = rectIou(m.targetRect, det.rect)
            val dx = m.targetRect.centerX() - dcx
            val dy = m.targetRect.centerY() - dcy
            val dist = sqrt(dx * dx + dy * dy)
            val prox = (1f - dist * 2.8f).coerceAtLeast(0f) * 0.48f
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
        var dtSec = 0.016f
        if (lastFrameNs > 0L) {
            val rawDt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            if (rawDt in 0.002f..0.25f) {
                dtSec = rawDt
                val instantFps = (1f / rawDt).coerceIn(15f, 144f)
                smoothedFps = smoothedFps * 0.85f + instantFps * 0.15f
            }
        }
        lastFrameNs = frameTimeNanos
        currentTimeSec = ((frameTimeNanos - animStartNs) / 1_000_000_000.0).toFloat()

        val cfg = currentConfig
        val smooth = cfg?.smoothnessMode
        // Frame-rate independent exponential smoothing (feels identically silky at 60Hz, 90Hz & 120Hz!)
        val basePosLerp = smooth?.posLerp ?: 0.56f
        val frameScale = (dtSec * 60f).coerceIn(0.35f, 2.2f)
        val posLerp = (1f - Math.pow((1f - basePosLerp).toDouble(), frameScale.toDouble()).toFloat()).coerceIn(0.12f, 0.92f)
        val alphaIn = (1f - Math.pow((1f - (smooth?.alphaIn ?: 0.48f)).toDouble(), frameScale.toDouble()).toFloat()).coerceIn(0.15f, 0.92f)
        val alphaOut = (1f - Math.pow((1f - (smooth?.alphaOut ?: 0.28f)).toDouble(), frameScale.toDouble()).toFloat()).coerceIn(0.10f, 0.85f)

        val useKalman = cfg?.kalmanPrediction ?: true
        val leadMaxSec = ((cfg?.kalmanLeadMs ?: 55) / 1000f).coerceIn(0f, 0.15f)
        val animatedShader = cfg?.blurStyle?.isAnimated == true
        val nowMs = System.currentTimeMillis()

        var needsNextFrame = false
        synchronized(lock) {
            val iter = masks.iterator()
            while (iter.hasNext()) {
                val m = iter.next()

                // Feature #2: Predictive Kalman target with smooth exponential decay after last AI frame
                val ageSec = ((nowMs - m.lastMeasMs).coerceIn(0L, 450L)) / 1000f
                val decay = exp(-ageSec * 6.5f)
                val predSec = if (useKalman) (leadMaxSec * decay) else 0f

                val predCx = (m.targetRect.centerX() + m.vx * predSec).coerceIn(0.01f, 0.99f)
                val predCy = (m.targetRect.centerY() + m.vy * predSec).coerceIn(0.01f, 0.99f)
                val predW = (m.targetRect.width() + m.vw * predSec * 0.5f).coerceIn(0.02f, 1.0f)
                val predH = (m.targetRect.height() + m.vh * predSec * 0.5f).coerceIn(0.02f, 1.0f)
                val predLeft = predCx - predW * 0.5f
                val predTop = predCy - predH * 0.5f
                val predRight = predCx + predW * 0.5f
                val predBottom = predCy + predH * 0.5f
                val predAngle = (m.targetAngle + m.vAngle * predSec * 0.6f).coerceIn(-65f, 65f)

                val prevCx = m.currentRect.centerX()
                val prevCy = m.currentRect.centerY()

                m.currentRect.left += (predLeft - m.currentRect.left) * posLerp
                m.currentRect.top += (predTop - m.currentRect.top) * posLerp
                m.currentRect.right += (predRight - m.currentRect.right) * posLerp
                m.currentRect.bottom += (predBottom - m.currentRect.bottom) * posLerp
                m.currentAngle += (predAngle - m.currentAngle) * posLerp

                // Glide organic 36-pt contour smoothly along with Kalman velocity delta at 120 FPS!
                val dCx = m.currentRect.centerX() - prevCx
                val dCy = m.currentRect.centerY() - prevCy
                if (m.contour != null && (abs(dCx) > 0.0002f || abs(dCy) > 0.0002f)) {
                    for (pt in m.contour!!) {
                        pt.x += dCx
                        pt.y += dCy
                    }
                }

                // Smooth velocity damping between measurements
                val velDamp = exp(-dtSec * 4.2f)
                m.vx *= velDamp
                m.vy *= velDamp
                m.vw *= velDamp
                m.vh *= velDamp
                m.vAngle *= velDamp

                val aRate = if (m.targetAlpha > m.alpha) alphaIn else alphaOut
                m.alpha += (m.targetAlpha - m.alpha) * aRate

                val moved = abs(predLeft - m.currentRect.left) > 0.0005f ||
                    abs(predTop - m.currentRect.top) > 0.0005f ||
                    abs(predRight - m.currentRect.right) > 0.0005f ||
                    abs(predBottom - m.currentRect.bottom) > 0.0005f ||
                    abs(predAngle - m.currentAngle) > 0.25f ||
                    abs(m.vx) > 0.008f || abs(m.vy) > 0.008f ||
                    abs(m.targetAlpha - m.alpha) > 0.01f

                if (m.targetAlpha == 0f && m.alpha <= 0.03f) {
                    recycleMaskBitmaps(m)
                    iter.remove()
                } else if (moved || animatedShader) {
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
        shaderEngine.close()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return
        val cfg = currentConfig

        val style = cfg?.blurStyle ?: BlurStyle.GAUSSIAN
        val shape = cfg?.maskShape ?: MaskShape.SILHOUETTE
        val padBase = (cfg?.maskPaddingPct ?: 12) / 100f
        val featherPct = cfg?.edgeFeatherPct ?: 40
        val opacityMul = ((cfg?.maskOpacityPct ?: 100) / 100f).coerceIn(0.35f, 1f)
        val intensity = cfg?.blurIntensity ?: 7

        var drawnCount = 0
        synchronized(lock) {
            for (m in masks) {
                if (m.alpha <= 0.03f) continue
                drawnCount++
                val alphaInt = (m.alpha * opacityMul * 255f).toInt().coerceIn(0, 255)
                val isNsfw = m.kind == "nsfw"
                val isBody = m.kind == "body"
                val pad = if (isBody) 0.02f else padBase + (if (isNsfw) 0.04f else 0f)

                drawRect.set(
                    (m.currentRect.left - m.currentRect.width() * pad) * w,
                    (m.currentRect.top - m.currentRect.height() * pad) * h,
                    (m.currentRect.right + m.currentRect.width() * pad) * w,
                    (m.currentRect.bottom + m.currentRect.height() * pad) * h
                )

                val alphaMask = m.alphaMask
                val usePixelAlphaMask = alphaMask != null && !alphaMask.isRecycled &&
                    !m.isSubZoneBar && (isBody || isNsfw || shape == MaskShape.SILHOUETTE)

                if (usePixelAlphaMask) {
                    // Features #1 & #5: Razor-sharp pixel-accurate Silhouette / Skin-Tone GPU Layer Compositing!
                    val saveCount = canvas.saveLayer(drawRect, null)
                    drawStyleContent(canvas, style, m, cfg, intensity, alphaInt)
                    canvas.drawBitmap(alphaMask!!, null, drawRect, maskClipPaint)
                    canvas.restoreToCount(saveCount)
                    continue
                }

                clipPath.reset()
                val contour = m.contour
                when {
                    m.isSubZoneBar -> {
                        val corner = min(drawRect.width(), drawRect.height()) * 0.38f
                        clipPath.addRoundRect(drawRect, corner, corner, Path.Direction.CW)
                    }
                    (shape == MaskShape.ORGANIC || shape == MaskShape.SILHOUETTE) && !isNsfw && contour != null && contour.size >= 8 -> {
                        buildSmoothContourPath(clipPath, contour, w, h, 1f + pad * 0.92f)
                    }
                    shape == MaskShape.ROUNDED_RECT -> {
                        val corner = min(drawRect.width(), drawRect.height()) * 0.24f
                        clipPath.addRoundRect(drawRect, corner, corner, Path.Direction.CW)
                    }
                    else -> {
                        clipPath.addOval(drawRect, Path.Direction.CW)
                    }
                }

                canvas.save()
                // Feature #4: Smooth 3D Euler head rotation around region center
                val shouldRotate = abs(m.currentAngle) > 1.5f &&
                    (m.isSubZoneBar || shape == MaskShape.OVAL || shape == MaskShape.ROUNDED_RECT ||
                        contour == null || style == BlurStyle.EMOJI_REACTIVE || style == BlurStyle.CUSTOM_IMAGE)
                if (shouldRotate) {
                    canvas.rotate(m.currentAngle, drawRect.centerX(), drawRect.centerY())
                }

                // Soft feathered edge blending (0..100%)
                if (featherPct > 4 && style != BlurStyle.SOLID_BLACK && style != BlurStyle.EMOJI_REACTIVE) {
                    val featherWidth = (2f + (featherPct / 100f) * 14f) * density
                    haloPaint.strokeWidth = featherWidth
                    haloPaint.color = if (style == BlurStyle.FROSTED || style == BlurStyle.GPU_FLUTED_GLASS) {
                        Color.argb((alphaInt * 0.45f).toInt(), 255, 255, 255)
                    } else {
                        Color.argb(
                            (alphaInt * (0.25f + (featherPct / 100f) * 0.35f)).toInt(),
                            Color.red(m.avgColor),
                            Color.green(m.avgColor),
                            Color.blue(m.avgColor)
                        )
                    }
                    canvas.drawPath(clipPath, haloPaint)
                }

                canvas.clipPath(clipPath)
                drawStyleContent(canvas, style, m, cfg, intensity, alphaInt)
                canvas.restore()
            }
        }

        // Optional real-time Performance HUD
        if (cfg?.showPerfHud == true) {
            val kTag = if (cfg.kalmanPrediction) "K120" else "Std"
            val label = "$activeModeLabel • $kTag • ${lastInferenceMs}ms • ${smoothedFps.toInt()} FPS • $drawnCount z"
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

    private fun drawStyleContent(
        canvas: Canvas,
        style: BlurStyle,
        m: TrackedMask,
        cfg: BlurConfigSnapshot?,
        intensity: Int,
        alphaInt: Int
    ) {
        val patch = m.patch
        when (style) {
            BlurStyle.SOLID_BLACK -> {
                fillPaint.color = Color.argb(alphaInt, 10, 10, 14)
                canvas.drawRect(drawRect, fillPaint)
            }

            BlurStyle.SOLID_COLOR -> {
                fillPaint.color = Color.argb(alphaInt, Color.red(m.avgColor), Color.green(m.avgColor), Color.blue(m.avgColor))
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
                fillPaint.color = Color.argb((alphaInt * 0.36f).toInt(), 240, 246, 255)
                canvas.drawRect(drawRect, fillPaint)
            }

            BlurStyle.GPU_BOKEH, BlurStyle.GPU_FLUTED_GLASS, BlurStyle.GPU_LIQUID_RIPPLE,
            BlurStyle.GPU_VORONOI_CRYSTAL, BlurStyle.GPU_CYBER_GLITCH -> {
                shaderEngine.drawGpuStyle(canvas, style, patch, drawRect, m.avgColor, intensity, alphaInt, currentTimeSec)
            }

            BlurStyle.EMOJI_REACTIVE -> {
                shaderEngine.drawReactiveEmoji(canvas, drawRect, m.reactiveEmoji, patch, m.avgColor, alphaInt)
            }

            BlurStyle.CUSTOM_IMAGE -> {
                if (cfg != null) {
                    shaderEngine.drawCustomImage(canvas, drawRect, cfg, patch, m.avgColor, alphaInt)
                }
            }
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
