package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import com.demonpet.app.ai.CensorTarget
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.FaceCensorMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hardware-Accelerated Jealous Censor Overlay (#1, #2, #3, #4, #5, #6):
 * - #1 Jealous Gaussian Blur on faces
 * - #2 Possessive Face-Swap: stamps DemonMom's cutout head directly over detected faces!
 * - #4 Dramatic NSFW Shield ("NON 😈" + blur)
 * - #5 Stylish Eyes-Only censor bar
 * - #6 Progressive AGSL GPU Liquid/Glitch Sabotage (Android 13+ RuntimeShader + API 26-32 fallback)
 */
class DemonCensorOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private data class ActiveMask(
        val id: String,
        val kind: String,
        val currentRect: RectF,
        val targetRect: RectF,
        var contour: List<PointF>?,
        var rotationDeg: Float,
        var patch: Bitmap?,
        var avgColor: Int,
        var isEyeBar: Boolean,
        var alpha: Float,
        var targetAlpha: Float,
        var lastSeenMs: Long
    )

    private val lock = Any()
    private val masks = ArrayList<ActiveMask>(12)
    private var config: DemonConfigSnapshot? = null
    private var avatarBitmap: Bitmap? = null
    private var cachedAvatarPath = ""
    private var animRunning = false
    private val startNs = System.nanoTime()
    private var timeSec = 0f

    @Volatile
    var sabotageProgress: Float = 0.45f // Ramps 0.25 -> 1.0 the longer user watches video (#6)

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val clipPath = Path()
    private val drawRect = RectF()
    private var agslSabotageShader: Any? = null

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateConfig(cfg: DemonConfigSnapshot) {
        config = cfg
        if (cfg.avatarPath != cachedAvatarPath) {
            cachedAvatarPath = cfg.avatarPath
            avatarBitmap?.let { if (!it.isRecycled) it.recycle() }
            avatarBitmap = if (cfg.avatarPath.isNotBlank() && File(cfg.avatarPath).exists()) {
                try { BitmapFactory.decodeFile(cfg.avatarPath) } catch (_: Throwable) { null }
            } else null
        }
        postInvalidateOnAnimation()
    }

    fun updateTargets(targets: List<CensorTarget>, cfg: DemonConfigSnapshot) {
        updateConfig(cfg)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val matched = HashSet<String>()
            for (t in targets) {
                val existing = masks.firstOrNull { it.kind == t.kind && !matched.contains(it.id) && (it.id == t.id || iou(it.targetRect, t.rect) > 0.14f) }
                if (existing != null) {
                    matched.add(existing.id)
                    existing.targetRect.set(t.rect)
                    if (t.contour != null) existing.contour = t.contour
                    existing.rotationDeg = t.rotationDeg
                    existing.isEyeBar = t.isEyeBar
                    if (t.patch != null) {
                        val old = existing.patch
                        existing.patch = t.patch
                        if (old != null && old !== t.patch && !old.isRecycled) old.recycle()
                    }
                    existing.avgColor = t.avgColor
                    existing.targetAlpha = 1f
                    existing.lastSeenMs = now
                } else {
                    val created = ActiveMask(
                        id = t.id,
                        kind = t.kind,
                        currentRect = RectF(t.rect),
                        targetRect = RectF(t.rect),
                        contour = t.contour,
                        rotationDeg = t.rotationDeg,
                        patch = t.patch,
                        avgColor = t.avgColor,
                        isEyeBar = t.isEyeBar,
                        alpha = 0.5f,
                        targetAlpha = 1f,
                        lastSeenMs = now
                    )
                    masks.add(created)
                    matched.add(created.id)
                }
            }
            val it = masks.iterator()
            while (it.hasNext()) {
                val m = it.next()
                if (!matched.contains(m.id)) {
                    if (now - m.lastSeenMs > 300L) m.targetAlpha = 0f
                    if (m.targetAlpha == 0f && m.alpha < 0.04f) {
                        m.patch?.let { b -> if (!b.isRecycled) b.recycle() }
                        it.remove()
                    }
                }
            }
        }
        post { ensureAnimating() }
    }

    fun shiftMasks(dxNorm: Float, dyNorm: Float) {
        if (abs(dxNorm) < 0.001f && abs(dyNorm) < 0.001f) return
        synchronized(lock) {
            val it = masks.iterator()
            while (it.hasNext()) {
                val m = it.next()
                m.currentRect.offset(dxNorm, dyNorm)
                m.targetRect.offset(dxNorm, dyNorm)
                m.contour = m.contour?.map { p -> PointF(p.x + dxNorm, p.y + dyNorm) }
                if (m.currentRect.bottom < -0.06f || m.currentRect.top > 1.06f) {
                    m.patch?.let { b -> if (!b.isRecycled) b.recycle() }
                    it.remove()
                }
            }
        }
        postInvalidateOnAnimation()
    }

    fun clearImmediate() {
        synchronized(lock) {
            for (m in masks) m.patch?.let { if (!it.isRecycled) it.recycle() }
            masks.clear()
        }
        postInvalidate()
    }

    private fun ensureAnimating() {
        if (!animRunning) {
            animRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        timeSec = ((frameTimeNanos - startNs) / 1_000_000_000.0).toFloat()
        var needMore = false
        val isGpuAnim = config?.faceCensorMode == FaceCensorMode.GPU_SABOTAGE
        synchronized(lock) {
            val it = masks.iterator()
            while (it.hasNext()) {
                val m = it.next()
                m.currentRect.left += (m.targetRect.left - m.currentRect.left) * 0.5f
                m.currentRect.top += (m.targetRect.top - m.currentRect.top) * 0.5f
                m.currentRect.right += (m.targetRect.right - m.currentRect.right) * 0.5f
                m.currentRect.bottom += (m.targetRect.bottom - m.currentRect.bottom) * 0.5f
                m.alpha += (m.targetAlpha - m.alpha) * 0.42f
                val moving = abs(m.targetRect.left - m.currentRect.left) > 0.0008f ||
                    abs(m.targetRect.top - m.currentRect.top) > 0.0008f ||
                    abs(m.targetAlpha - m.alpha) > 0.01f
                if (m.targetAlpha == 0f && m.alpha < 0.04f) {
                    m.patch?.let { b -> if (!b.isRecycled) b.recycle() }
                    it.remove()
                } else if (moving || isGpuAnim) {
                    needMore = true
                }
            }
        }
        invalidate()
        if (needMore) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            animRunning = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val cfg = config ?: return

        synchronized(lock) {
            for (m in masks) {
                if (m.alpha <= 0.03f) continue
                val aInt = (m.alpha * 255f).toInt().coerceIn(0, 255)
                val isNsfw = m.kind == "nsfw"
                val pad = if (isNsfw) 0.16f else 0.12f
                drawRect.set(
                    (m.currentRect.left - m.currentRect.width() * pad) * w,
                    (m.currentRect.top - m.currentRect.height() * pad) * h,
                    (m.currentRect.right + m.currentRect.width() * pad) * w,
                    (m.currentRect.bottom + m.currentRect.height() * pad) * h
                )

                canvas.save()
                if (abs(m.rotationDeg) > 1.5f) {
                    canvas.rotate(m.rotationDeg, drawRect.centerX(), drawRect.centerY())
                }

                if (isNsfw) {
                    // #4 Dramatic NSFW Shield
                    clipPath.reset()
                    clipPath.addOval(drawRect, Path.Direction.CW)
                    canvas.clipPath(clipPath)
                    val p = m.patch
                    if (p != null && !p.isRecycled) {
                        bmpPaint.alpha = aInt
                        canvas.drawBitmap(p, null, drawRect, bmpPaint)
                    } else {
                        fillPaint.color = Color.argb(aInt, 24, 10, 18)
                        canvas.drawRect(drawRect, fillPaint)
                    }
                    fillPaint.color = Color.argb((aInt * 0.52f).toInt(), 159, 18, 57)
                    canvas.drawRect(drawRect, fillPaint)
                    textPaint.textSize = (min(drawRect.width(), drawRect.height()) * 0.28f).coerceIn(14f * density, 34f * density)
                    textPaint.alpha = aInt
                    val ty = drawRect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
                    canvas.drawText("NON 😈", drawRect.centerX(), ty, textPaint)
                } else {
                    when (cfg.faceCensorMode) {
                        FaceCensorMode.FACE_SWAP_SELF -> {
                            // #2 Possessive Face-Swap: stamp DemonMom's cutout head over other people's faces!
                            val padRect = RectF(
                                drawRect.left - drawRect.width() * 0.10f,
                                drawRect.top - drawRect.height() * 0.12f,
                                drawRect.right + drawRect.width() * 0.10f,
                                drawRect.bottom + drawRect.height() * 0.10f
                            )
                            val av = avatarBitmap
                            if (av != null && !av.isRecycled) {
                                // Blurred privacy backing first
                                m.patch?.takeIf { !it.isRecycled }?.let { p ->
                                    clipPath.reset()
                                    clipPath.addOval(drawRect, Path.Direction.CW)
                                    canvas.save()
                                    canvas.clipPath(clipPath)
                                    bmpPaint.alpha = aInt
                                    canvas.drawBitmap(p, null, drawRect, bmpPaint)
                                    canvas.restore()
                                }
                                bmpPaint.alpha = aInt
                                canvas.drawBitmap(av, null, padRect, bmpPaint)
                            } else {
                                fillPaint.color = Color.argb(aInt, 244, 63, 94)
                                canvas.drawOval(drawRect, fillPaint)
                                textPaint.textSize = (min(drawRect.width(), drawRect.height()) * 0.55f).coerceIn(18f, 160f)
                                val ty = drawRect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
                                canvas.drawText("😈", drawRect.centerX(), ty, textPaint)
                            }
                        }

                        FaceCensorMode.EYE_BAR -> {
                            // #5 Stylish Eye Censor Bar
                            val r = min(drawRect.width(), drawRect.height()) * 0.25f
                            fillPaint.color = Color.argb(aInt, 12, 12, 16)
                            canvas.drawRoundRect(drawRect, r, r, fillPaint)
                            strokePaint.strokeWidth = 2f * density
                            strokePaint.color = Color.argb(aInt, 244, 63, 94)
                            canvas.drawRoundRect(drawRect, r, r, strokePaint)
                            textPaint.textSize = (drawRect.height() * 0.45f).coerceIn(9f * density, 16f * density)
                            val ty = drawRect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
                            canvas.drawText("REGARDE-MOI 😈", drawRect.centerX(), ty, textPaint)
                        }

                        FaceCensorMode.GPU_SABOTAGE -> {
                            // #6 Progressive AGSL GPU Liquid/Glitch Sabotage
                            clipPath.reset()
                            clipPath.addOval(drawRect, Path.Direction.CW)
                            canvas.clipPath(clipPath)
                            drawGpuSabotage(canvas, m.patch, drawRect, m.avgColor, aInt)
                        }

                        FaceCensorMode.BLUR_JEALOUS, FaceCensorMode.OFF -> {
                            // #1 Jealous Gaussian Blur
                            clipPath.reset()
                            clipPath.addOval(drawRect, Path.Direction.CW)
                            canvas.clipPath(clipPath)
                            val p = m.patch
                            if (p != null && !p.isRecycled) {
                                bmpPaint.alpha = aInt
                                canvas.drawBitmap(p, null, drawRect, bmpPaint)
                            } else {
                                fillPaint.color = Color.argb(aInt, Color.red(m.avgColor), Color.green(m.avgColor), Color.blue(m.avgColor))
                                canvas.drawRect(drawRect, fillPaint)
                            }
                        }
                    }
                }
                canvas.restore()
            }
        }
    }

    private fun drawGpuSabotage(canvas: Canvas, patch: Bitmap?, rect: RectF, avgColor: Int, alphaInt: Int) {
        if (patch == null || patch.isRecycled) {
            fillPaint.color = Color.argb(alphaInt, Color.red(avgColor), Color.green(avgColor), Color.blue(avgColor))
            canvas.drawRect(rect, fillPaint)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (drawAgslSabotage(canvas, patch, rect, alphaInt)) return
        }
        // Native high-speed wave/glitch fallback for API 26–32
        val bands = 8
        val bh = rect.height() / bands
        val amp = rect.width() * (0.06f + sabotageProgress * 0.14f)
        bmpPaint.alpha = alphaInt
        for (i in 0 until bands) {
            val shift = sin(timeSec * 5f + i * 1.1f) * amp
            canvas.drawBitmap(patch, null, RectF(rect.left + shift, rect.top + i * bh, rect.right + shift, rect.top + (i + 1) * bh + 1f), bmpPaint)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgslSabotage(canvas: Canvas, patch: Bitmap, rect: RectF, alphaInt: Int): Boolean {
        return try {
            if (agslSabotageShader == null) agslSabotageShader = RuntimeShader(AGSL_SABOTAGE)
            val rs = agslSabotageShader as RuntimeShader
            val bs = BitmapShader(patch, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderMatrix.reset()
            shaderMatrix.setScale(rect.width() / patch.width.coerceAtLeast(1), rect.height() / patch.height.coerceAtLeast(1))
            shaderMatrix.postTranslate(rect.left, rect.top)
            bs.setLocalMatrix(shaderMatrix)
            rs.setInputShader("uImage", bs)
            rs.setFloatUniform("uOrigin", rect.left, rect.top)
            rs.setFloatUniform("uSize", rect.width().coerceAtLeast(1f), rect.height().coerceAtLeast(1f))
            rs.setFloatUniform("uIntensity", sabotageProgress.coerceIn(0.25f, 1f))
            rs.setFloatUniform("uTime", timeSec)
            shaderPaint.shader = rs
            shaderPaint.alpha = alphaInt
            canvas.drawRect(rect, shaderPaint)
            shaderPaint.shader = null
            true
        } catch (_: Throwable) {
            shaderPaint.shader = null
            false
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left); val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right); val iy2 = min(a.bottom, b.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    override fun onDetachedFromWindow() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        clearImmediate()
        avatarBitmap?.let { if (!it.isRecycled) it.recycle() }
        avatarBitmap = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val AGSL_SABOTAGE = """
            uniform shader uImage;
            uniform float2 uOrigin;
            uniform float2 uSize;
            uniform float uIntensity;
            uniform float uTime;
            half4 main(float2 fragCoord) {
                float2 uv = (fragCoord - uOrigin) / max(uSize, float2(1.0));
                float amp = min(uSize.x, uSize.y) * (0.05 + uIntensity * 0.14);
                float dx = sin(uv.y * 12.0 + uTime * 4.5) * amp;
                float dy = cos(uv.x * 10.0 - uTime * 3.2) * amp * 0.7;
                float split = uSize.x * (0.02 + uIntensity * 0.05);
                half r = uImage.eval(clamp(fragCoord + float2(dx + split, dy), uOrigin, uOrigin + uSize)).r;
                half g = uImage.eval(clamp(fragCoord + float2(dx, dy), uOrigin, uOrigin + uSize)).g;
                half b = uImage.eval(clamp(fragCoord + float2(dx - split, dy), uOrigin, uOrigin + uSize)).b;
                return half4(r, g, b, 1.0);
            }
        """
    }
}
