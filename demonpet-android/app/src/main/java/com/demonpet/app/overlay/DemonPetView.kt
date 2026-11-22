package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.demonpet.app.config.AvatarStyle
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonMood
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Living Interactive Demon Avatar View (60–120 FPS):
 * - Renders the real AI-cutout head on an animated procedural cartoon body (Demon / Mom / Chibi / Head).
 * - Supports grabbing, gentle petting (cools mood), throwing with velocity, nightcap during curfew,
 *   furious glowing aura, comic speech bubble, and 20-consecutive-tap secret exit trigger!
 */
class DemonPetView(
    context: Context,
    private val onDragMove: (dx: Float, dy: Float) -> Unit,
    private val onDragRelease: (vxPxPerSec: Float, vyPxPerSec: Float, wasTap: Boolean) -> Unit,
    private val onGentlePet: () -> Unit,
    private val onSecretTwentyTaps: () -> Unit
) : View(context) {

    enum class PetPose {
        WALKING, CLIMBING_LEFT, CLIMBING_RIGHT, HANGING_TOP, PEEKING_LEFT, PEEKING_RIGHT, SITTING, PANICKING, DRAGGED
    }

    private val density = resources.displayMetrics.density
    private var config: DemonConfigSnapshot? = null
    private var headBitmap: Bitmap? = null
    private var cachedAvatarPath: String = ""

    var pose: PetPose = PetPose.WALKING
    var facingRight: Boolean = true
    var animPhase: Float = 0f
    var curfewNightcap: Boolean = false
    var speechText: String = ""
    var speechUntilMs: Long = 0L
    var heartUntilMs: Long = 0L

    // Touch / Throw / 20-Tap Exit state
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastMoveNs = 0L
    private var estVx = 0f
    private var estVy = 0f
    private var totalMoveDist = 0f
    private var consecutiveTaps = 0
    private var lastTapMs = 0L

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 16, 16, 22)
        style = Paint.Style.FILL
    }
    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 63, 94)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(253, 224, 71)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val headRect = RectF()
    private val bubbleRect = RectF()
    private val hornPath = Path()
    private val tailPath = Path()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateConfig(cfg: DemonConfigSnapshot) {
        config = cfg
        if (cfg.avatarPath != cachedAvatarPath) {
            cachedAvatarPath = cfg.avatarPath
            headBitmap?.let { if (!it.isRecycled) it.recycle() }
            headBitmap = if (cfg.avatarPath.isNotBlank() && File(cfg.avatarPath).exists()) {
                try { BitmapFactory.decodeFile(cfg.avatarPath) } catch (_: Throwable) { null }
            } else null
        }
        invalidate()
    }

    fun say(text: String, durationMs: Long = 4_200L) {
        speechText = text
        speechUntilMs = System.currentTimeMillis() + durationMs
        postInvalidate()
    }

    fun getAvatarBitmap(): Bitmap? = headBitmap

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                lastMoveNs = System.nanoTime()
                estVx = 0f
                estVy = 0f
                totalMoveDist = 0f
                pose = PetPose.DRAGGED
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastMoveNs) / 1_000_000_000f).coerceIn(0.004f, 0.2f)
                estVx = estVx * 0.55f + (dx / dt) * 0.45f
                estVy = estVy * 0.55f + (dy / dt) * 0.45f
                totalMoveDist += abs(dx) + abs(dy)
                lastRawX = event.rawX
                lastRawY = event.rawY
                lastMoveNs = nowNs
                onDragMove(dx, dy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTap = totalMoveDist < 22f * density
                if (wasTap) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs > 2_500L) {
                        consecutiveTaps = 0
                    }
                    lastTapMs = now
                    consecutiveTaps++
                    if (consecutiveTaps >= 20) {
                        consecutiveTaps = 0
                        onSecretTwentyTaps()
                    } else if (consecutiveTaps in 1..4) {
                        // Gentle pet reaction!
                        heartUntilMs = now + 950L
                        onGentlePet()
                    }
                } else {
                    consecutiveTaps = 0
                }
                onDragRelease(estVx, estVy, wasTap)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        val cfg = config
        val style = cfg?.avatarStyle ?: AvatarStyle.DEMON
        val mood = cfg?.mood ?: DemonMood.MOM
        val now = System.currentTimeMillis()
        val showSpeech = speechText.isNotBlank() && now < speechUntilMs

        // Reserve top band for speech bubble so head/body stays centered cleanly below it
        val bubbleAreaH = 44f * density
        val petAreaH = (h - bubbleAreaH).coerceAtLeast(48f * density)
        val cx = w * 0.5f
        val bounce = when (pose) {
            PetPose.WALKING -> abs(sin(animPhase * 4.5f)) * 5f * density
            PetPose.PANICKING -> sin(animPhase * 16f) * 5f * density
            PetPose.DRAGGED -> sin(animPhase * 10f) * 2.5f * density
            else -> sin(animPhase * 2.4f) * 2.5f * density
        }
        val swayDeg = when (pose) {
            PetPose.WALKING -> sin(animPhase * 4.5f) * 6f
            PetPose.PANICKING -> sin(animPhase * 18f) * 14f
            PetPose.DRAGGED -> (estVx / 160f).coerceIn(-22f, 22f)
            PetPose.CLIMBING_LEFT -> 12f
            PetPose.CLIMBING_RIGHT -> -12f
            PetPose.HANGING_TOP -> 180f
            PetPose.PEEKING_LEFT -> 22f
            PetPose.PEEKING_RIGHT -> -22f
            else -> sin(animPhase * 2f) * 3f
        }

        val headSize = (petAreaH * (if (style == AvatarStyle.FLOATING_HEAD) 0.78f else 0.54f)).coerceIn(36f * density, w * 0.72f)
        val headCy = bubbleAreaH + headSize * 0.58f - bounce

        // 1) Draw comic speech bubble at top when active
        if (showSpeech) {
            drawSpeechBubble(canvas, w, bubbleAreaH, speechText, mood)
        }

        canvas.save()
        canvas.rotate(swayDeg, cx, headCy + headSize * 0.25f)

        // 2) Furious / Jealous aura glow behind body
        if (mood == DemonMood.FURIOUS || mood == DemonMood.JEALOUS) {
            bodyPaint.style = Paint.Style.FILL
            bodyPaint.color = if (mood == DemonMood.FURIOUS) {
                Color.argb(72, 244, 63, 94)
            } else {
                Color.argb(54, 168, 85, 247)
            }
            canvas.drawCircle(cx, headCy + headSize * 0.22f, headSize * 0.70f, bodyPaint)
        }

        // 3) Procedural cute/demon body below head
        if (style != AvatarStyle.FLOATING_HEAD) {
            drawBody(canvas, cx, headCy + headSize * 0.40f, headSize, style, mood)
        }

        // 4) Real AI-Cutout Head (or cute fallback demon face if user hasn't picked photo yet)
        headRect.set(cx - headSize * 0.5f, headCy - headSize * 0.5f, cx + headSize * 0.5f, headCy + headSize * 0.5f)
        val bmp = headBitmap
        if (bmp != null && !bmp.isRecycled) {
            // Soft drop ring behind cutout head
            bodyPaint.style = Paint.Style.FILL
            bodyPaint.color = Color.argb(120, 15, 15, 20)
            canvas.drawCircle(cx, headCy + 2f * density, headSize * 0.49f, bodyPaint)
            canvas.drawBitmap(bmp, null, headRect, bmpPaint)
            // Accent outline ring matching mood
            strokePaint.strokeWidth = 2f * density
            strokePaint.color = when (mood) {
                DemonMood.FURIOUS -> Color.rgb(244, 63, 94)
                DemonMood.JEALOUS -> Color.rgb(192, 132, 252)
                DemonMood.MOM -> Color.rgb(251, 191, 36)
                DemonMood.ANGEL -> Color.rgb(52, 211, 153)
            }
            canvas.drawCircle(cx, headCy, headSize * 0.48f, strokePaint)
        } else {
            drawDefaultDemonHead(canvas, cx, headCy, headSize * 0.48f, mood)
        }

        // 5) Head accessories: Demon Horns / Mom Halo / Curfew Nightcap (#10)
        if (curfewNightcap) {
            drawNightcap(canvas, cx, headCy - headSize * 0.44f, headSize)
        } else if (style == AvatarStyle.DEMON || mood == DemonMood.FURIOUS) {
            drawDemonHorns(canvas, cx, headCy - headSize * 0.40f, headSize)
        } else if (style == AvatarStyle.OVERPROTECTIVE_MOM) {
            strokePaint.strokeWidth = 2.6f * density
            strokePaint.color = Color.rgb(253, 224, 71)
            canvas.drawOval(
                cx - headSize * 0.34f,
                headCy - headSize * 0.62f,
                cx + headSize * 0.34f,
                headCy - headSize * 0.47f,
                strokePaint
            )
        }

        canvas.restore()

        // 6) Floating Heart when gently petted
        if (now < heartUntilMs) {
            badgeTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
            canvas.drawText("💖", cx + headSize * 0.45f, headCy - headSize * 0.25f, badgeTextPaint)
        }

        // 7) Subtle secret tap progress indicator ONLY after 12 consecutive taps (out of 20!)
        if (consecutiveTaps >= 12 && now - lastTapMs < 2_500L) {
            badgeTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, resources.displayMetrics)
            canvas.drawText("$consecutiveTaps/20", cx, h - 4f * density, badgeTextPaint)
        }
    }

    private fun drawBody(canvas: Canvas, cx: Float, topY: Float, headSize: Float, style: AvatarStyle, mood: DemonMood) {
        val bw = headSize * 0.56f
        val bh = headSize * 0.52f
        val legSwing = if (pose == PetPose.WALKING || pose == PetPose.PANICKING) sin(animPhase * 9f) * 8f * density else 0f
        val armWave = sin(animPhase * 6.5f) * 7f * density

        // Demon Tail & Little Bat Wings
        if (style == AvatarStyle.DEMON || mood == DemonMood.FURIOUS) {
            val dir = if (facingRight) -1f else 1f
            tailPath.reset()
            tailPath.moveTo(cx + dir * bw * 0.3f, topY + bh * 0.65f)
            tailPath.quadTo(
                cx + dir * (bw * 1.1f),
                topY + bh * 0.55f + sin(animPhase * 5f) * 6f * density,
                cx + dir * (bw * 1.0f),
                topY + bh * 0.10f
            )
            strokePaint.color = Color.rgb(225, 29, 72)
            strokePaint.strokeWidth = 3f * density
            canvas.drawPath(tailPath, strokePaint)

            // Tiny wings
            bodyPaint.style = Paint.Style.FILL
            bodyPaint.color = Color.argb(215, 190, 18, 60)
            val flap = sin(animPhase * 8f) * 4f * density
            canvas.drawOval(cx - bw * 1.05f, topY + 2f * density - flap, cx - bw * 0.35f, topY + bh * 0.65f - flap, bodyPaint)
            canvas.drawOval(cx + bw * 0.35f, topY + 2f * density - flap, cx + bw * 1.05f, topY + bh * 0.65f - flap, bodyPaint)
        }

        // Little Legs
        strokePaint.color = Color.rgb(39, 39, 46)
        strokePaint.strokeWidth = 4.2f * density
        val hipY = topY + bh * 0.85f
        val legLen = bh * 0.42f
        canvas.drawLine(cx - bw * 0.24f, hipY, cx - bw * 0.26f + legSwing, hipY + legLen, strokePaint)
        canvas.drawLine(cx + bw * 0.24f, hipY, cx + bw * 0.26f - legSwing, hipY + legLen, strokePaint)

        // Torso
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = when (style) {
            AvatarStyle.DEMON -> Color.rgb(225, 29, 72)
            AvatarStyle.OVERPROTECTIVE_MOM -> Color.rgb(147, 51, 234)
            else -> Color.rgb(59, 130, 246)
        }
        canvas.drawRoundRect(cx - bw * 0.5f, topY, cx + bw * 0.5f, topY + bh, bw * 0.35f, bw * 0.35f, bodyPaint)

        // Cute Mom Apron accent
        if (style == AvatarStyle.OVERPROTECTIVE_MOM) {
            bodyPaint.color = Color.rgb(250, 245, 255)
            canvas.drawRoundRect(cx - bw * 0.30f, topY + bh * 0.25f, cx + bw * 0.30f, topY + bh * 0.92f, 6f * density, 6f * density, bodyPaint)
        }

        // Little Arms
        strokePaint.color = bodyPaint.color
        strokePaint.strokeWidth = 3.8f * density
        canvas.drawLine(cx - bw * 0.45f, topY + bh * 0.25f, cx - bw * 0.82f, topY + bh * 0.52f - armWave, strokePaint)
        canvas.drawLine(cx + bw * 0.45f, topY + bh * 0.25f, cx + bw * 0.82f, topY + bh * 0.52f + armWave, strokePaint)
    }

    private fun drawDemonHorns(canvas: Canvas, cx: Float, topY: Float, headSize: Float) {
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = Color.rgb(244, 63, 94)
        val r = headSize * 0.28f
        // Left horn
        hornPath.reset()
        hornPath.moveTo(cx - r * 0.9f, topY + r * 0.2f)
        hornPath.quadTo(cx - r * 1.45f, topY - r * 0.95f, cx - r * 0.35f, topY - r * 0.1f)
        hornPath.close()
        canvas.drawPath(hornPath, bodyPaint)
        // Right horn
        hornPath.reset()
        hornPath.moveTo(cx + r * 0.9f, topY + r * 0.2f)
        hornPath.quadTo(cx + r * 1.45f, topY - r * 0.95f, cx + r * 0.35f, topY - r * 0.1f)
        hornPath.close()
        canvas.drawPath(hornPath, bodyPaint)
    }

    private fun drawNightcap(canvas: Canvas, cx: Float, topY: Float, headSize: Float) {
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = Color.rgb(99, 102, 241)
        hornPath.reset()
        hornPath.moveTo(cx - headSize * 0.38f, topY + headSize * 0.12f)
        hornPath.quadTo(cx, topY - headSize * 0.42f, cx + headSize * 0.42f, topY - headSize * 0.08f)
        hornPath.lineTo(cx + headSize * 0.36f, topY + headSize * 0.12f)
        hornPath.close()
        canvas.drawPath(hornPath, bodyPaint)
        bodyPaint.color = Color.WHITE
        canvas.drawCircle(cx + headSize * 0.43f, topY - headSize * 0.08f, 4.5f * density, bodyPaint)
    }

    private fun drawDefaultDemonHead(canvas: Canvas, cx: Float, cy: Float, r: Float, mood: DemonMood) {
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = Color.rgb(28, 25, 34)
        canvas.drawCircle(cx, cy, r, bodyPaint)
        strokePaint.strokeWidth = 2.2f * density
        strokePaint.color = Color.rgb(244, 63, 94)
        canvas.drawCircle(cx, cy, r, strokePaint)
        // Eyes
        bodyPaint.color = if (mood == DemonMood.FURIOUS) Color.rgb(251, 113, 133) else Color.rgb(253, 224, 71)
        canvas.drawCircle(cx - r * 0.32f, cy - r * 0.10f, r * 0.14f, bodyPaint)
        canvas.drawCircle(cx + r * 0.32f, cy - r * 0.10f, r * 0.14f, bodyPaint)
        // Smirk
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2.2f * density
        canvas.drawArc(cx - r * 0.36f, cy + r * 0.04f, cx + r * 0.36f, cy + r * 0.44f, 15f, 150f, false, strokePaint)
    }

    private fun drawSpeechBubble(canvas: Canvas, w: Float, areaH: Float, text: String, mood: DemonMood) {
        val clipped = if (text.length > 28) text.take(27) + "…" else text
        val tw = bubbleTextPaint.measureText(clipped)
        val bw = (tw + 18f * density).coerceIn(60f * density, w - 6f * density)
        val bh = 28f * density
        val left = ((w - bw) * 0.5f).coerceIn(3f * density, w - bw - 3f * density)
        val top = (areaH - bh - 6f * density).coerceAtLeast(2f * density)
        bubbleRect.set(left, top, left + bw, top + bh)
        bubbleBorderPaint.color = when (mood) {
            DemonMood.FURIOUS -> Color.rgb(244, 63, 94)
            DemonMood.JEALOUS -> Color.rgb(192, 132, 252)
            else -> Color.rgb(251, 191, 36)
        }
        canvas.drawRoundRect(bubbleRect, 12f * density, 12f * density, bubbleBgPaint)
        canvas.drawRoundRect(bubbleRect, 12f * density, 12f * density, bubbleBorderPaint)
        val textY = bubbleRect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) * 0.5f
        canvas.drawText(clipped, bubbleRect.centerX(), textY, bubbleTextPaint)
    }

    override fun onDetachedFromWindow() {
        headBitmap?.let { if (!it.isRecycled) it.recycle() }
        headBitmap = null
        super.onDetachedFromWindow()
    }
}
