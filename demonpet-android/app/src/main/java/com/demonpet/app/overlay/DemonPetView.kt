package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.demonpet.app.ai.AvatarCutoutEngine
import com.demonpet.app.ai.AvatarFaceMeta
import com.demonpet.app.config.AvatarStyle
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonMood
import com.demonpet.app.config.DemonSettings
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photorealistic Living Demon Avatar View (60–120 FPS):
 * - Renders the real HD AI-segmented portrait/bust from the user's photo with realistic studio drop shadow,
 *   natural breathing/sway animation, shaded leather-like bat wings, 3D specular horns anchored to the REAL
 *   forehead/temples (from ML Kit Face Landmarks), realistic glowing ember eyes when furious,
 *   #50 playful finger dodging on drag, and 20-consecutive-tap secret exit (`100205`).
 */
class DemonPetView(
    context: Context,
    private val onDragMove: (dx: Float, dy: Float) -> Unit,
    private val onDragRelease: (vxPxPerSec: Float, vyPxPerSec: Float, wasTap: Boolean) -> Unit,
    private val onGentlePet: () -> Unit,
    private val onSecretTwentyTaps: () -> Unit,
    private val onDodgeJump: () -> Unit = {}
) : View(context) {

    enum class PetPose {
        WALKING, CLIMBING_LEFT, CLIMBING_RIGHT, HANGING_TOP, PEEKING_LEFT, PEEKING_RIGHT, SITTING, PANICKING, DRAGGED
    }

    private val density = resources.displayMetrics.density
    private var config: DemonConfigSnapshot? = null
    private var bustBitmap: Bitmap? = null
    private var shadowBitmap: Bitmap? = null
    private var faceMeta: AvatarFaceMeta = AvatarFaceMeta()
    private var cachedAvatarPath: String = ""
    private var cachedAvatarMtime: Long = 0L

    var pose: PetPose = PetPose.WALKING
    var facingRight: Boolean = true
    var animPhase: Float = 0f
    var curfewNightcap: Boolean = false
    var speechText: String = ""
    var speechUntilMs: Long = 0L
    var heartUntilMs: Long = 0L

    // Touch / Throw / #50 Dodge / 20-Tap Exit state
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastMoveNs = 0L
    private var estVx = 0f
    private var estVy = 0f
    private var moved = false
    private var dodgedThisGesture = false
    private var dodgeCountRecent = 0
    private var lastDodgeResetMs = 0L

    private var secretTapCount = 0
    private var lastTapMs = 0L

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 145
    }
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 16, 14, 24)
        style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 244, 63, 94)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun updateConfig(cfg: DemonConfigSnapshot) {
        config = cfg
        val p = cfg.avatarPath
        val file = if (p.isNotBlank()) File(p) else null
        val mtime = if (file != null && file.exists()) file.lastModified() else 0L
        if (p != cachedAvatarPath || mtime != cachedAvatarMtime) {
            cachedAvatarPath = p
            cachedAvatarMtime = mtime
            bustBitmap?.let { if (!it.isRecycled) it.recycle() }
            shadowBitmap?.let { if (!it.isRecycled) it.recycle() }
            bustBitmap = null
            shadowBitmap = null
            if (file != null && file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(p) } catch (_: Throwable) { null }
                if (bmp != null) {
                    bustBitmap = bmp
                    shadowBitmap = buildSoftDropShadow(bmp)
                }
            }
            faceMeta = AvatarCutoutEngine.loadFaceMeta(context)
        }
        invalidate()
    }

    private fun buildSoftDropShadow(src: Bitmap): Bitmap? {
        return try {
            val alpha = src.extractAlpha()
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawBitmap(alpha, 0f, 8f, p)
            alpha.recycle()
            out
        } catch (_: Throwable) {
            null
        }
    }

    fun say(message: String, durationMs: Long = 3_600L) {
        speechText = message
        speechUntilMs = System.currentTimeMillis() + durationMs
        invalidate()
    }

    fun showHeart() {
        heartUntilMs = System.currentTimeMillis() + 1_400L
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                lastMoveNs = System.nanoTime()
                estVx = 0f
                estVy = 0f
                moved = false
                dodgedThisGesture = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dodgedThisGesture) return true
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!moved && (abs(event.rawX - downRawX) > 10f * density || abs(event.rawY - downRawY) > 10f * density)) {
                    moved = true
                    // #50 Playful Finger Dodge: ONLY on drag (never interferes with 20-tap exit!)
                    val cfg = config
                    if (cfg != null && cfg.dodgeFingerEnabled && secretTapCount < 3) {
                        if (now - lastDodgeResetMs > 35_000L) {
                            dodgeCountRecent = 0
                            lastDodgeResetMs = now
                        }
                        if (dodgeCountRecent < 3) {
                            dodgeCountRecent++
                            dodgedThisGesture = true
                            say(listOf("Raté ! 😈", "Tu ne m'auras pas !", "Touche pas ! 😤").random(), 2_200L)
                            onDodgeJump()
                            return true
                        }
                    }
                    pose = PetPose.DRAGGED
                }
                if (moved) {
                    val nowNs = System.nanoTime()
                    val dtSec = ((nowNs - lastMoveNs) / 1_000_000_000f).coerceIn(0.004f, 0.1f)
                    estVx = estVx * 0.55f + (dx / dtSec) * 0.45f
                    estVy = estVy * 0.55f + (dy / dtSec) * 0.45f
                    lastMoveNs = nowNs
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDragMove(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dodgedThisGesture) {
                    dodgedThisGesture = false
                    return true
                }
                if (!moved) {
                    if (now - lastTapMs <= 1650L) {
                        secretTapCount++
                    } else {
                        secretTapCount = 1
                    }
                    lastTapMs = now

                    if (secretTapCount >= DemonSettings.REQUIRED_EXIT_TAPS) {
                        secretTapCount = 0
                        onSecretTwentyTaps()
                    } else {
                        if (secretTapCount == 1) {
                            showHeart()
                            onGentlePet()
                        } else if (secretTapCount in 14..19) {
                            say("${secretTapCount}/20… 👀", 1_100L)
                        }
                    }
                    onDragRelease(0f, 0f, true)
                } else {
                    onDragRelease(estVx, estVy, false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cfg = config ?: return
        val now = System.currentTimeMillis()
        val mood = cfg.mood
        val style = cfg.avatarStyle

        val w = width.toFloat()
        val h = height.toFloat()
        val hasBubble = speechText.isNotEmpty() && now < speechUntilMs
        val bubbleReserve = if (hasBubble) 40f * density else 10f * density

        val availH = (h - bubbleReserve - 8f * density).coerceAtLeast(52f * density)
        // Realistic 4:5 portrait ratio
        val bodyH = availH * 0.88f
        val bodyW = (bodyH * 0.80f).coerceAtMost(w * 0.78f)
        val cx = w * 0.5f
        val baseY = h - 8f * density
        val topY = baseY - bodyH

        // Organic breathing + subtle realistic head sway
        val breathScaleY = 1f + sin(animPhase * 0.65f) * 0.022f
        val hoverDy = when (pose) {
            PetPose.WALKING -> -abs(sin(animPhase * 1.15f)) * 4.5f * density
            PetPose.PANICKING -> sin(animPhase * 3.8f) * 5f * density
            PetPose.SITTING -> sin(animPhase * 0.5f) * 1.5f * density
            else -> sin(animPhase * 0.8f) * 2.5f * density
        }
        val tiltDeg = when (pose) {
            PetPose.PANICKING -> sin(animPhase * 4.2f) * 8f
            PetPose.DRAGGED -> 5f
            PetPose.WALKING -> sin(animPhase * 0.9f) * 2.8f
            PetPose.CLIMBING_LEFT -> 12f
            PetPose.CLIMBING_RIGHT -> -12f
            PetPose.HANGING_TOP -> 180f
            else -> sin(animPhase * 0.5f) * 1.4f
        }

        // Ground contact shadow (realistic floating depth)
        if (pose != PetPose.DRAGGED && pose != PetPose.HANGING_TOP) {
            val groundShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, baseY - 2f * density, bodyW * 0.42f,
                    intArrayOf(Color.argb(125, 0, 0, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawOval(
                RectF(cx - bodyW * 0.40f, baseY - 6f * density, cx + bodyW * 0.40f, baseY + 3f * density),
                groundShadow
            )
        }

        canvas.save()
        canvas.translate(cx, topY + bodyH * 0.5f + hoverDy)
        canvas.rotate(tiltDeg)
        canvas.scale(if (facingRight) 1f else -1f, breathScaleY)
        canvas.translate(-cx, -(topY + bodyH * 0.5f))

        val portraitRect = RectF(cx - bodyW * 0.5f, topY, cx + bodyW * 0.5f, baseY - 2f * density)

        // 1. Mood atmospheric rim aura behind silhouette
        drawAtmosphericAura(canvas, portraitRect, mood)

        // 2. Realistic shaded bat wings behind shoulders (for REALISTIC_DEMON or FURIOUS mood)
        if (style == AvatarStyle.REALISTIC_DEMON || mood == DemonMood.FURIOUS) {
            drawRealisticWings(canvas, portraitRect, mood)
        }

        // 3. Optional subtle dark tailored mini-body underneath for REALISTIC_MINI_BODY
        if (style == AvatarStyle.REALISTIC_MINI_BODY) {
            drawRealisticTailoredShadowTorso(canvas, portraitRect)
        }

        // 4. REALISTIC PHOTO PORTRAIT / BUST + STUDIO DROP SHADOW
        val bmp = bustBitmap
        if (bmp != null && !bmp.isRecycled) {
            shadowBitmap?.let { shBmp ->
                if (!shBmp.isRecycled) {
                    val shRect = RectF(
                        portraitRect.left - 3f * density,
                        portraitRect.top + 3f * density,
                        portraitRect.right + 3f * density,
                        portraitRect.bottom + 5f * density
                    )
                    canvas.drawBitmap(shBmp, null, shRect, shadowPaint)
                }
            }
            // Subtle moody color grading tint when FURIOUS (keeps 100% photo realism!)
            bmpPaint.colorFilter = if (mood == DemonMood.FURIOUS) {
                PorterDuffColorFilter(Color.argb(34, 220, 38, 38), PorterDuff.Mode.SRC_ATOP)
            } else null
            canvas.drawBitmap(bmp, null, portraitRect, bmpPaint)
            bmpPaint.colorFilter = null

            // 5. Realistic glowing ember eyes anchored to the REAL person's pupils when Jealous/Furious!
            if (mood == DemonMood.FURIOUS || (mood == DemonMood.JEALOUS && style == AvatarStyle.REALISTIC_DEMON)) {
                drawRealisticGlowingEyes(canvas, portraitRect, mood == DemonMood.FURIOUS)
            }

            // 6. Realistic 3D Horns or Royal Crown anchored to the REAL forehead/temples!
            if (curfewNightcap) {
                drawRealisticNightcap(canvas, portraitRect)
            } else {
                when (style) {
                    AvatarStyle.REALISTIC_DEMON -> drawRealistic3DHorns(canvas, portraitRect, mood)
                    AvatarStyle.REALISTIC_MOM_CROWN -> drawRealisticCrown(canvas, portraitRect)
                    AvatarStyle.REALISTIC_PORTRAIT -> { /* Pure ultra-clean studio cutout */ }
                    AvatarStyle.REALISTIC_MINI_BODY -> if (mood == DemonMood.FURIOUS) drawRealistic3DHorns(canvas, portraitRect, mood)
                }
            }
        } else {
            drawPlaceholderPortrait(canvas, portraitRect)
        }

        // 7. Rising realistic embers when furious
        if (mood == DemonMood.FURIOUS) {
            drawEmberParticles(canvas, portraitRect)
        }

        canvas.restore()

        // 8. Gentle pet heart particle
        if (now < heartUntilMs) {
            val t = 1f - (heartUntilMs - now) / 1_400f
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 18f * density
                alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawText("❤️", cx - 9f * density, topY - t * 24f * density, hp)
        }

        // 9. Sleek Glassmorphism Speech Bubble
        if (hasBubble) {
            drawGlassSpeechBubble(canvas, cx, (topY - 8f * density).coerceAtLeast(34f * density), w, speechText)
        }
    }

    private fun drawAtmosphericAura(canvas: Canvas, rect: RectF, mood: DemonMood) {
        val auraColor = when (mood) {
            DemonMood.ANGEL -> Color.argb(42, 167, 243, 208)
            DemonMood.MOM -> Color.argb(48, 251, 191, 36)
            DemonMood.JEALOUS -> Color.argb(65, 244, 63, 94)
            DemonMood.FURIOUS -> Color.argb(105, 220, 38, 38)
        }
        val pulse = 1f + sin(animPhase * 1.4f) * 0.06f
        val r = maxOf(rect.width(), rect.height()) * 0.56f * pulse
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                rect.centerX(), rect.centerY(), r,
                intArrayOf(auraColor, Color.TRANSPARENT),
                floatArrayOf(0.25f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), r, p)
    }

    private fun drawRealisticWings(canvas: Canvas, rect: RectF, mood: DemonMood) {
        val flap = sin(animPhase * if (mood == DemonMood.FURIOUS) 2.5f else 1.25f) * 0.14f
        val shoulderY = rect.top + rect.height() * (faceMeta.chinY + 0.05f).coerceIn(0.55f, 0.78f)
        val wingSpan = rect.width() * 0.48f
        val wingH = rect.height() * 0.36f

        for (dir in listOf(-1f, 1f)) {
            canvas.save()
            val anchorX = rect.centerX() + dir * rect.width() * 0.16f
            canvas.translate(anchorX, shoulderY)
            canvas.rotate(dir * (10f + flap * 55f))

            val path = Path().apply {
                moveTo(0f, 0f)
                // Upper bone arch
                cubicTo(
                    dir * wingSpan * 0.45f, -wingH * 0.92f,
                    dir * wingSpan * 0.96f, -wingH * 0.65f,
                    dir * wingSpan * 1.08f, -wingH * 0.12f
                )
                // Scalloped bat-wing lower membrane
                quadTo(dir * wingSpan * 0.78f, wingH * 0.02f, dir * wingSpan * 0.74f, wingH * 0.28f)
                quadTo(dir * wingSpan * 0.46f, wingH * 0.12f, dir * wingSpan * 0.36f, wingH * 0.38f)
                quadTo(dir * wingSpan * 0.14f, wingH * 0.22f, 0f, wingH * 0.18f)
                close()
            }

            val wingFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, -wingH * 0.8f, dir * wingSpan, wingH * 0.35f,
                    intArrayOf(
                        Color.argb(235, 24, 10, 18),
                        if (mood == DemonMood.FURIOUS) Color.argb(220, 115, 16, 36) else Color.argb(210, 58, 18, 34),
                        Color.argb(185, 14, 8, 14)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * density
                color = Color.argb(140, 244, 63, 94)
            }
            canvas.drawPath(path, wingFill)
            canvas.drawLine(0f, 0f, dir * wingSpan * 0.74f, wingH * 0.26f, ribPaint)
            canvas.drawLine(0f, 0f, dir * wingSpan * 0.36f, wingH * 0.36f, ribPaint)
            canvas.restore()
        }
    }

    /**
     * Anchors sculpted 3D horns directly onto the REAL person's temples/forehead
     * with contact shadow, crimson-obsidian gradient, and specular highlight.
     */
    private fun drawRealistic3DHorns(canvas: Canvas, rect: RectF, mood: DemonMood) {
        val hornScale = (rect.width() * faceMeta.faceWidthRatio * 0.46f).coerceIn(10f * density, 28f * density)
        val lx = rect.left + rect.width() * faceMeta.leftTempleX
        val ly = rect.top + rect.height() * faceMeta.leftTempleY
        val rx = rect.left + rect.width() * faceMeta.rightTempleX
        val ry = rect.top + rect.height() * faceMeta.rightTempleY

        drawSingleRealisticHorn(canvas, lx, ly, -1f, hornScale, mood)
        drawSingleRealisticHorn(canvas, rx, ry, 1f, hornScale, mood)
    }

    private fun drawSingleRealisticHorn(canvas: Canvas, baseX: Float, baseY: Float, dir: Float, s: Float, mood: DemonMood) {
        // Soft shadow where horn meets skin/hair
        val rootShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 0, 0, 0)
            maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(RectF(baseX - s * 0.35f, baseY - s * 0.12f, baseX + s * 0.35f, baseY + s * 0.22f), rootShadow)

        val hornPath = Path().apply {
            moveTo(baseX - dir * s * 0.26f, baseY + s * 0.06f)
            cubicTo(
                baseX + dir * s * 0.05f, baseY - s * 0.58f,
                baseX + dir * s * 0.65f, baseY - s * 0.92f,
                baseX + dir * s * 0.92f, baseY - s * 1.18f
            )
            cubicTo(
                baseX + dir * s * 0.96f, baseY - s * 0.62f,
                baseX + dir * s * 0.52f, baseY - s * 0.16f,
                baseX + dir * s * 0.28f, baseY + s * 0.14f
            )
            close()
        }

        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                baseX, baseY + s * 0.1f,
                baseX + dir * s * 0.9f, baseY - s * 1.15f,
                intArrayOf(
                    Color.rgb(28, 8, 14),
                    if (mood == DemonMood.FURIOUS) Color.rgb(190, 24, 52) else Color.rgb(125, 18, 38),
                    Color.rgb(245, 92, 110)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(hornPath, grad)

        // Subtle anatomical specular ridge highlight
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(140, 255, 228, 232)
        }
        val ridge = Path().apply {
            moveTo(baseX - dir * s * 0.04f, baseY - s * 0.10f)
            quadTo(baseX + dir * s * 0.34f, baseY - s * 0.64f, baseX + dir * s * 0.78f, baseY - s * 1.02f)
        }
        canvas.drawPath(ridge, spec)
    }

    private fun drawRealisticGlowingEyes(canvas: Canvas, rect: RectF, intense: Boolean) {
        val lx = rect.left + rect.width() * faceMeta.leftEyeX
        val ly = rect.top + rect.height() * faceMeta.leftEyeY
        val rx = rect.left + rect.width() * faceMeta.rightEyeX
        val ry = rect.top + rect.height() * faceMeta.rightEyeY
        val glowR = (rect.width() * faceMeta.faceWidthRatio * (if (intense) 0.19f else 0.12f)).coerceAtLeast(5f * density)

        for (pt in listOf(Pair(lx, ly), Pair(rx, ry))) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    pt.first, pt.second, glowR,
                    intArrayOf(
                        if (intense) Color.argb(225, 255, 235, 235) else Color.argb(150, 255, 180, 180),
                        if (intense) Color.argb(195, 244, 38, 68) else Color.argb(110, 244, 63, 94),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(pt.first, pt.second, glowR, p)
        }
    }

    private fun drawRealisticCrown(canvas: Canvas, rect: RectF) {
        val cx = rect.left + rect.width() * ((faceMeta.leftTempleX + faceMeta.rightTempleX) * 0.5f)
        val cy = rect.top + rect.height() * (faceMeta.foreheadY - 0.04f)
        val cw = rect.width() * faceMeta.faceWidthRatio * 0.72f
        val ch = cw * 0.44f

        val crown = Path().apply {
            moveTo(cx - cw * 0.46f, cy)
            lineTo(cx - cw * 0.52f, cy - ch * 0.85f)
            lineTo(cx - cw * 0.18f, cy - ch * 0.35f)
            lineTo(cx, cy - ch * 1.05f)
            lineTo(cx + cw * 0.18f, cy - ch * 0.35f)
            lineTo(cx + cw * 0.52f, cy - ch * 0.85f)
            lineTo(cx + cw * 0.46f, cy)
            close()
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy,
                intArrayOf(Color.rgb(180, 120, 18), Color.rgb(254, 240, 138), Color.rgb(217, 119, 6)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(crown, goldPaint)
        canvas.drawCircle(cx, cy - ch * 0.35f, 2.4f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 29, 72)
        })
    }

    private fun drawRealisticNightcap(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.top + rect.height() * faceMeta.foreheadY
        val w = rect.width() * faceMeta.faceWidthRatio * 0.9f
        val cap = Path().apply {
            moveTo(cx - w * 0.52f, cy)
            cubicTo(cx - w * 0.1f, cy - w * 0.8f, cx + w * 0.65f, cy - w * 0.4f, cx + w * 0.85f, cy + w * 0.15f)
            lineTo(cx + w * 0.46f, cy + w * 0.04f)
            close()
        }
        canvas.drawPath(cap, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - w * 0.5f, cy - w * 0.6f, cx + w * 0.7f, cy + w * 0.15f, Color.rgb(67, 56, 202), Color.rgb(30, 27, 75), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(cx + w * 0.85f, cy + w * 0.15f, 5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun drawRealisticTailoredShadowTorso(canvas: Canvas, rect: RectF) {
        val torsoTop = rect.top + rect.height() * 0.72f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.centerX(), torsoTop, rect.centerX(), rect.bottom,
                intArrayOf(Color.argb(190, 24, 18, 32), Color.argb(0, 12, 10, 18)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(rect.left + rect.width() * 0.18f, torsoTop, rect.right - rect.width() * 0.18f, rect.bottom),
            rect.width() * 0.25f, rect.width() * 0.25f, p
        )
    }

    private fun drawEmberParticles(canvas: Canvas, rect: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 6) {
            val phase = (animPhase * 0.42f + i * 1.13f) % 1f
            val px = rect.left + rect.width() * (0.18f + (i * 0.14f)) + sin(animPhase + i) * 5f * density
            val py = rect.bottom - phase * rect.height() * 0.95f
            p.color = Color.argb(((1f - phase) * 210).toInt().coerceIn(0, 255), 255, 110 + (i * 18) % 90, 50)
            canvas.drawCircle(px, py, (1.6f + (i % 3) * 0.6f) * density, p)
        }
    }

    private fun drawPlaceholderPortrait(canvas: Canvas, rect: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                rect.centerX(), rect.centerY(), rect.width() * 0.55f,
                Color.rgb(62, 24, 48), Color.rgb(22, 16, 30), Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(rect, p)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = rect.width() * 0.42f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("😈", rect.centerX(), rect.centerY() + rect.width() * 0.14f, txt)
    }

    private fun drawGlassSpeechBubble(canvas: Canvas, cx: Float, bottomY: Float, viewW: Float, text: String) {
        val padH = 11f * density
        val padV = 6f * density
        val maxTw = (viewW - 22f * density).coerceAtLeast(70f * density)
        val shown = if (bubbleTextPaint.measureText(text) > maxTw) {
            var cut = text
            while (cut.length > 4 && bubbleTextPaint.measureText("$cut…") > maxTw) cut = cut.dropLast(1)
            "$cut…"
        } else text
        val tw = bubbleTextPaint.measureText(shown)
        val bw = tw + padH * 2f
        val bh = 24f * density
        val left = (cx - bw * 0.5f).coerceIn(4f * density, (viewW - bw - 4f * density).coerceAtLeast(4f * density))
        val top = (bottomY - bh).coerceAtLeast(2f * density)
        val r = RectF(left, top, left + bw, top + bh)

        canvas.drawRoundRect(r, 12f * density, 12f * density, bubbleBgPaint)
        canvas.drawRoundRect(r, 12f * density, 12f * density, bubbleStrokePaint)
        val fm = bubbleTextPaint.fontMetrics
        val baseline = r.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(shown, r.left + padH, baseline, bubbleTextPaint)
    }
}
