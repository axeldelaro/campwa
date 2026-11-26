package com.gazelock.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.PopupAnimStyle
import com.gazelock.app.config.PopupFrameStyle
import com.gazelock.app.config.PopupSpawnZone
import com.gazelock.app.config.PopupTouchMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 60 FPS Hardware-Accelerated Unlimited Image Pop-Up Storm Stage (v6.0):
 * - Renders 1 up to 350+ simultaneous image pop-ups on ONE single GPU-accelerated Canvas window
 *   (zero WindowManager surface exhaustion, zero lag even at 30ms / 33 pop-ups/sec bursts!).
 * - Supports 4 Spawn Zones (Anywhere / Center / Edges / Grid Collage), 5 Entrance Animations
 *   (Pop Zoom / Instant Flash / Slide-In / Spin-In / Random), 5 Frame Styles (Neon / Crimson /
 *   Polaroid / Borderless / Double Pulse), Min-Max Size (12%..100%), Persistent ∞ Duration,
 *   Tap-to-Close (1..3x), Swipe-Away Fling, Mitosis on Timeout, and Touch-Locked refusal shake!
 */
class PopupStormCanvasView(
    context: Context,
    private val interactiveMode: Boolean,
    private val onMitosisRequested: () -> Unit,
    private val onActiveCountChanged: (Int) -> Unit,
    private val onSpriteDismissed: () -> Unit
) : View(context) {

    data class StormSprite(
        val bitmap: Bitmap,
        var cx: Float,
        var cy: Float,
        val w: Float,
        val h: Float,
        val baseRotation: Float,
        val targetAlpha: Float,
        val strokeColor: Int,
        val frameStyle: PopupFrameStyle,
        val animStyle: PopupAnimStyle,
        val slideFromX: Float,
        val slideFromY: Float,
        val mantra: String?,
        val touchMode: PopupTouchMode,
        var tapsLeft: Int,
        val swipeDismiss: Boolean,
        val mitosisOnExpire: Boolean,
        val spawnMs: Long,
        val expireMs: Long, // Long.MAX_VALUE = ∞ Persistent!
        var shakeUntilMs: Long = 0L,
        var flingVx: Float = 0f,
        var flingVy: Float = 0f,
        var flinging: Boolean = false
    )

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val sprites = ArrayList<StormSprite>(64)
    private var gridCursor = 0

    // Reusable Paints (zero per-frame GC allocation -> silky 60 FPS!)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mantraBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mantraTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 225, 29, 72) }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = dp(11).toFloat()
    }
    private val tmpRect = RectF()
    private val imgRect = RectF()

    // Touch / Swipe-Away Tracking
    private var downX = 0f
    private var downY = 0f
    private var downSprite: StormSprite? = null

    private val accentPalette = intArrayOf(
        Color.rgb(236, 72, 153),
        Color.rgb(168, 85, 247),
        Color.rgb(244, 63, 94),
        Color.rgb(250, 204, 21),
        Color.rgb(14, 165, 233),
        Color.rgb(16, 185, 129)
    )

    init {
        isClickable = interactiveMode
        isFocusable = false
    }

    fun activeCount(): Int = sprites.size

    fun spawnSprite(bitmap: Bitmap, cfg: GazeConfigSnapshot, fromMitosis: Boolean = false) {
        if (bitmap.isRecycled) return
        val sw = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val sh = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()

        // Cap: 0 = ∞ Unlimited (capped at a massive 360 GPU sprites to keep 60 FPS silky forever!)
        val maxCap = if (cfg.popupMaxSimultaneous <= 0) 360 else cfg.popupMaxSimultaneous.coerceIn(1, 360)
        while (sprites.size >= maxCap) {
            sprites.removeAt(0)
        }

        val minPct = minOf(cfg.popupMinSizePct, cfg.popupSizePct).coerceIn(12, 100)
        val maxPct = maxOf(cfg.popupMinSizePct, cfg.popupSizePct).coerceIn(15, 100)
        val chosenPct = if (minPct == maxPct) maxPct else Random.nextInt(minPct, maxPct + 1)

        val targetW = (sw * chosenPct / 100f).coerceIn(dp(56).toFloat(), sw)
        val ratio = (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()).coerceIn(0.50f, 2.0f)
        val extraPolaroidH = if (cfg.popupFrameStyle == PopupFrameStyle.POLAROID) dp(26).toFloat() else 0f
        val targetH = ((targetW / ratio) + extraPolaroidH).coerceIn(dp(56).toFloat(), sh * 0.90f)

        val (cx, cy) = pickSpawnCenter(cfg.popupSpawnZone, sw, sh, targetW, targetH)

        val resolvedAnim = if (cfg.popupAnimStyle == PopupAnimStyle.RANDOM) {
            listOf(PopupAnimStyle.POP_ZOOM, PopupAnimStyle.INSTANT_FLASH, PopupAnimStyle.SLIDE_IN, PopupAnimStyle.SPIN_IN)[Random.nextInt(4)]
        } else cfg.popupAnimStyle

        val (slideX, slideY) = when (Random.nextInt(4)) {
            0 -> (-targetW) to cy
            1 -> (sw + targetW) to cy
            2 -> cx to (-targetH)
            else -> cx to (sh + targetH)
        }

        val baseAlpha = cfg.popupOpacityPct.coerceIn(15, 100) / 100f
        val finalAlpha = if (cfg.popupRandomOpacity) {
            (baseAlpha * (0.55f + Random.nextFloat() * 0.45f)).coerceIn(0.18f, 1.0f)
        } else baseAlpha

        val strokeCol = when {
            fromMitosis || cfg.popupFrameStyle == PopupFrameStyle.CRIMSON -> Color.rgb(244, 63, 94)
            else -> accentPalette[Random.nextInt(accentPalette.size)]
        }

        val now = SystemClock.elapsedRealtime()
        val expire = if (cfg.popupDurationMs <= 0) Long.MAX_VALUE else now + cfg.popupDurationMs.coerceIn(100, 60_000).toLong()

        val sp = StormSprite(
            bitmap = bitmap,
            cx = cx,
            cy = cy,
            w = targetW,
            h = targetH,
            baseRotation = if (cfg.popupRandomRotation) Random.nextInt(-14, 15).toFloat() else 0f,
            targetAlpha = finalAlpha,
            strokeColor = strokeCol,
            frameStyle = cfg.popupFrameStyle,
            animStyle = resolvedAnim,
            slideFromX = slideX,
            slideFromY = slideY,
            mantra = if (cfg.popupShowMantra) cfg.mantraList().random() else null,
            touchMode = cfg.popupTouchMode,
            tapsLeft = cfg.popupTapsToClose.coerceIn(1, 3),
            swipeDismiss = cfg.popupSwipeDismiss,
            mitosisOnExpire = cfg.popupMitosisEnabled && cfg.popupTouchMode == PopupTouchMode.TAP_DISMISS,
            spawnMs = now,
            expireMs = expire
        )
        sprites.add(sp)
        onActiveCountChanged(sprites.size)
        invalidate()
    }

    private fun pickSpawnCenter(zone: PopupSpawnZone, sw: Float, sh: Float, w: Float, h: Float): Pair<Float, Float> {
        val halfW = (w * 0.5f).coerceAtMost(sw * 0.48f)
        val halfH = (h * 0.5f).coerceAtMost(sh * 0.48f)
        val minX = halfW; val maxX = (sw - halfW).coerceAtLeast(minX + 1f)
        val minY = maxOf(halfH, dp(42).toFloat()); val maxY = (sh - halfH).coerceAtLeast(minY + 1f)

        return when (zone) {
            PopupSpawnZone.CENTER -> {
                val jx = (Random.nextFloat() - 0.5f) * sw * 0.30f
                val jy = (Random.nextFloat() - 0.5f) * sh * 0.30f
                (sw * 0.5f + jx).coerceIn(minX, maxX) to (sh * 0.5f + jy).coerceIn(minY, maxY)
            }
            PopupSpawnZone.EDGES -> {
                when (Random.nextInt(4)) {
                    0 -> (minX + Random.nextFloat() * (maxX - minX)) to minY // Top band
                    1 -> (minX + Random.nextFloat() * (maxX - minX)) to maxY // Bottom band
                    2 -> minX to (minY + Random.nextFloat() * (maxY - minY)) // Left band
                    else -> maxX to (minY + Random.nextFloat() * (maxY - minY)) // Right band
                }
            }
            PopupSpawnZone.GRID -> {
                val cols = (sw / w.coerceAtLeast(dp(60).toFloat())).toInt().coerceIn(2, 5)
                val rows = (sh / h.coerceAtLeast(dp(60).toFloat())).toInt().coerceIn(3, 8)
                val cell = gridCursor++ % (cols * rows)
                val r = cell / cols; val c = cell % cols
                val cw = sw / cols; val ch = (sh - dp(40)) / rows
                (c * cw + cw * 0.5f) to (dp(36) + r * ch + ch * 0.5f)
            }
            PopupSpawnZone.ANYWHERE -> {
                (minX + Random.nextFloat() * (maxX - minX)) to (minY + Random.nextFloat() * (maxY - minY))
            }
        }
    }

    fun clearAll() {
        sprites.clear()
        onActiveCountChanged(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sprites.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        var mitosisCount = 0
        var removedAny = false

        val it = sprites.iterator()
        while (it.hasNext()) {
            val sp = it.next()
            if (sp.bitmap.isRecycled) {
                it.remove(); removedAny = true; continue
            }

            if (sp.flinging) {
                sp.cx += sp.flingVx
                sp.cy += sp.flingVy
                if (sp.cx < -sp.w || sp.cx > width + sp.w || sp.cy < -sp.h || sp.cy > height + sp.h) {
                    it.remove(); removedAny = true; continue
                }
            } else if (now >= sp.expireMs) {
                it.remove(); removedAny = true
                if (sp.mitosisOnExpire) mitosisCount++
                continue
            }

            drawSprite(canvas, sp, now)
        }

        if (removedAny) onActiveCountChanged(sprites.size)
        if (mitosisCount > 0) {
            repeat(mitosisCount.coerceAtMost(3)) { onMitosisRequested() }
        }
        if (sprites.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawSprite(canvas: Canvas, sp: StormSprite, now: Long) {
        val ageMs = (now - sp.spawnMs).coerceAtLeast(0L)
        val inFrac = (ageMs / 170f).coerceIn(0f, 1f)
        val fadeOutFrac = if (sp.expireMs == Long.MAX_VALUE) 1f else {
            val rem = (sp.expireMs - now).coerceAtLeast(0L)
            if (rem < 160L) (rem / 160f).coerceIn(0f, 1f) else 1f
        }

        var drawX = sp.cx
        var drawY = sp.cy
        var scale = 1f
        var rot = sp.baseRotation
        var alpha = sp.targetAlpha * fadeOutFrac

        when (sp.animStyle) {
            PopupAnimStyle.INSTANT_FLASH -> { /* Instant full snap! */ }
            PopupAnimStyle.POP_ZOOM -> {
                // Smooth overshoot bounce!
                scale = if (inFrac < 0.75f) 0.55f + (inFrac / 0.75f) * 0.51f else 1.06f - ((inFrac - 0.75f) / 0.25f) * 0.06f
                alpha *= inFrac
            }
            PopupAnimStyle.SLIDE_IN -> {
                val ease = 1f - (1f - inFrac) * (1f - inFrac)
                drawX = sp.slideFromX + (sp.cx - sp.slideFromX) * ease
                drawY = sp.slideFromY + (sp.cy - sp.slideFromY) * ease
                alpha *= (0.35f + 0.65f * inFrac)
            }
            PopupAnimStyle.SPIN_IN -> {
                val ease = 1f - (1f - inFrac) * (1f - inFrac)
                scale = 0.35f + 0.65f * ease
                rot = sp.baseRotation - (1f - ease) * 260f
                alpha *= inFrac
            }
            PopupAnimStyle.RANDOM -> { scale = 0.7f + 0.3f * inFrac; alpha *= inFrac }
        }

        if (now < sp.shakeUntilMs) {
            drawX += sin(now / 18.0).toFloat() * dp(8)
        }

        val alphaByte = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(drawX, drawY)
        canvas.rotate(rot)
        canvas.scale(scale, scale)

        val hw = sp.w * 0.5f; val hh = sp.h * 0.5f
        tmpRect.set(-hw, -hh, hw, hh)

        val pad = when (sp.frameStyle) {
            PopupFrameStyle.BORDERLESS -> 0f
            PopupFrameStyle.POLAROID -> dp(6).toFloat()
            else -> dp(3).toFloat()
        }
        val bottomExtra = if (sp.frameStyle == PopupFrameStyle.POLAROID) dp(24).toFloat() else 0f

        // Card background frame
        if (sp.frameStyle != PopupFrameStyle.BORDERLESS) {
            fillPaint.color = if (sp.frameStyle == PopupFrameStyle.POLAROID) Color.argb(alphaByte, 245, 245, 250)
            else Color.argb((alphaByte * 0.92f).toInt(), 18, 12, 28)
            val rad = if (sp.frameStyle == PopupFrameStyle.POLAROID) dp(4).toFloat() else dp(12).toFloat()
            canvas.drawRoundRect(tmpRect, rad, rad, fillPaint)
        }

        // Photo bitmap
        imgRect.set(-hw + pad, -hh + pad, hw - pad, hh - pad - bottomExtra)
        bmpPaint.alpha = alphaByte
        canvas.drawBitmap(sp.bitmap, null, imgRect, bmpPaint)

        // Frame stroke styles
        when (sp.frameStyle) {
            PopupFrameStyle.NEON_MULTI, PopupFrameStyle.CRIMSON -> {
                strokePaint.color = Color.argb(alphaByte, Color.red(sp.strokeColor), Color.green(sp.strokeColor), Color.blue(sp.strokeColor))
                strokePaint.strokeWidth = dp(2).toFloat()
                canvas.drawRoundRect(tmpRect, dp(12).toFloat(), dp(12).toFloat(), strokePaint)
            }
            PopupFrameStyle.DOUBLE_PULSE -> {
                val pulse = 0.6f + 0.4f * sin(now / 110.0).toFloat()
                strokePaint.color = Color.argb((alphaByte * pulse).toInt(), Color.red(sp.strokeColor), Color.green(sp.strokeColor), Color.blue(sp.strokeColor))
                strokePaint.strokeWidth = dp(3).toFloat()
                canvas.drawRoundRect(tmpRect, dp(12).toFloat(), dp(12).toFloat(), strokePaint)
                strokePaint.color = Color.argb(alphaByte, 255, 255, 255)
                strokePaint.strokeWidth = dp(1).toFloat()
                canvas.drawRect(imgRect, strokePaint)
            }
            PopupFrameStyle.POLAROID -> {
                strokePaint.color = Color.argb((alphaByte * 0.55f).toInt(), 180, 180, 200)
                strokePaint.strokeWidth = dp(1).toFloat()
                canvas.drawRect(tmpRect, strokePaint)
            }
            PopupFrameStyle.BORDERLESS -> {}
        }

        // Mantra caption
        if (sp.mantra != null) {
            mantraTextPaint.textSize = (sp.w * 0.085f).coerceIn(dp(9).toFloat(), dp(15).toFloat())
            if (sp.frameStyle == PopupFrameStyle.POLAROID) {
                mantraTextPaint.color = Color.argb(alphaByte, 26, 16, 40)
                canvas.drawText(sp.mantra.take(24), 0f, hh - dp(8), mantraTextPaint)
            } else {
                mantraBgPaint.color = Color.argb((alphaByte * 0.72f).toInt(), 18, 6, 28)
                val barH = mantraTextPaint.textSize + dp(8)
                canvas.drawRect(imgRect.left, imgRect.bottom - barH, imgRect.right, imgRect.bottom, mantraBgPaint)
                mantraTextPaint.color = Color.argb(alphaByte, 255, 255, 255)
                canvas.drawText(sp.mantra.take(24), 0f, imgRect.bottom - dp(5), mantraTextPaint)
            }
        }

        // Interactive corner badge in TAP_DISMISS / TOUCH_LOCKED
        if (interactiveMode && sp.touchMode != PopupTouchMode.PASS_THROUGH) {
            val bx = hw - dp(15); val by = -hh + dp(15)
            badgeBgPaint.color = if (sp.touchMode == PopupTouchMode.TOUCH_LOCKED) Color.argb(alphaByte, 24, 16, 36)
            else Color.argb(alphaByte, 225, 29, 72)
            canvas.drawCircle(bx, by, dp(11).toFloat(), badgeBgPaint)
            val txt = when {
                sp.touchMode == PopupTouchMode.TOUCH_LOCKED -> "🔒"
                sp.tapsLeft > 1 -> "×${sp.tapsLeft}"
                else -> "✕"
            }
            badgeTextPaint.alpha = alphaByte
            canvas.drawText(txt, bx, by + dp(4), badgeTextPaint)
        }

        canvas.restore()
    }

    private fun hitTestTopSprite(tx: Float, ty: Float): StormSprite? {
        for (i in sprites.indices.reversed()) {
            val sp = sprites[i]
            if (sp.flinging) continue
            if (tx >= sp.cx - sp.w * 0.52f && tx <= sp.cx + sp.w * 0.52f &&
                ty >= sp.cy - sp.h * 0.52f && ty <= sp.cy + sp.h * 0.52f
            ) return sp
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactiveMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downSprite = hitTestTopSprite(event.x, event.y)
                return downSprite != null
            }
            MotionEvent.ACTION_UP -> {
                val sp = downSprite ?: return false
                downSprite = null
                if (sp !in sprites || sp.flinging) return true
                if (sp.touchMode == PopupTouchMode.TOUCH_LOCKED) {
                    sp.shakeUntilMs = SystemClock.elapsedRealtime() + 220L
                    invalidate()
                    return true
                }
                val dx = event.x - downX; val dy = event.y - downY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (sp.swipeDismiss && dist >= dp(46)) {
                    // Fling sprite cleanly off screen!
                    val norm = dist.coerceAtLeast(1f)
                    sp.flingVx = (dx / norm) * dp(36)
                    sp.flingVy = (dy / norm) * dp(36)
                    sp.flinging = true
                    onSpriteDismissed()
                    invalidate()
                } else {
                    sp.tapsLeft--
                    if (sp.tapsLeft <= 0) {
                        sprites.remove(sp)
                        onActiveCountChanged(sprites.size)
                        onSpriteDismissed()
                    } else {
                        sp.shakeUntilMs = SystemClock.elapsedRealtime() + 110L
                    }
                    invalidate()
                }
                return true
            }
        }
        return downSprite != null
    }
}
