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
 * Ultra-Realistic Living Demon Avatar View v3 (60–120 FPS):
 * - Renders the 640x800 HD defringed portrait/bust with real silhouette Rim-Lighting (follows the real hair & face contours!),
 *   soft studio drop shadow, articulated 3D whip tail, multi-layer veined bat wings, sculpted striated 3D obsidian horns
 *   anchored to the REAL temples (scaling with anger), realistic glowing eyes (crimson embers when angry, hypnotic violet-pink
 *   during MGQ seduction drain!), layered flames/smoke or seduction energy orbs, #50 finger dodge, and 20-tap exit (`100205`).
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
    private var rimMaskBitmap: Bitmap? = null
    private var faceMeta: AvatarFaceMeta = AvatarFaceMeta()
    private var cachedAvatarPath: String = ""
    private var cachedAvatarMtime: Long = 0L

    var pose: PetPose = PetPose.WALKING
    var facingRight: Boolean = true
    var animPhase: Float = 0f
    var curfewNightcap: Boolean = false
    var seductionAuraActive: Boolean = false
    var speechText: String = ""
    var speechUntilMs: Long = 0L
    var heartUntilMs: Long = 0L
    private var gazeOffsetX: Float = 0f
    private var gazeOffsetY: Float = 0f

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
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 155 }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 14, 12, 22)
        style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 244, 63, 94)
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
            rimMaskBitmap?.let { if (!it.isRecycled) it.recycle() }
            bustBitmap = null
            shadowBitmap = null
            rimMaskBitmap = null
            if (file != null && file.exists()) {
                val bmp = try { BitmapFactory.decodeFile(p) } catch (_: Throwable) { null }
                if (bmp != null) {
                    bustBitmap = bmp
                    shadowBitmap = buildSoftDropShadow(bmp)
                    rimMaskBitmap = buildContourRimMask(bmp)
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
                maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawBitmap(alpha, 0f, 10f, p)
            alpha.recycle()
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts a crisp inner-edge Rim-Light mask directly from the REAL portrait alpha!
     * Lets us cast realistic crimson firelight or violet seduction glow onto the real hair & cheeks.
     */
    private fun buildContourRimMask(src: Bitmap): Bitmap? {
        return try {
            val sw = 160
            val sh = 200
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== src) small.recycle()
            val rimPx = IntArray(sw * sh)
            for (y in 2 until (sh - 2)) {
                val row = y * sw
                for (x in 2 until (sw - 2)) {
                    val a = (px[row + x] ushr 24) and 0xFF
                    if (a > 120) {
                        val minNeighbor = minOf(
                            (px[row + (x - 2)] ushr 24) and 0xFF,
                            (px[row + (x + 2)] ushr 24) and 0xFF,
                            (px[(y - 2) * sw + x] ushr 24) and 0xFF
                        )
                        if (minNeighbor < 110) {
                            val rimAlpha = ((a - minNeighbor).coerceIn(0, 255) * 0.85f).toInt()
                            rimPx[row + x] = Color.argb(rimAlpha, 255, 255, 255)
                        }
                    }
                }
            }
            val raw = Bitmap.createBitmap(rimPx, sw, sh, Bitmap.Config.ARGB_8888)
            val blurred = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val c = Canvas(blurred)
            c.drawBitmap(raw, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL)
            })
            raw.recycle()
            blurred
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
        // Subtle live eye tracking toward finger touch!
        if (width > 0 && height > 0) {
            gazeOffsetX = ((event.x / width - 0.5f) * 2.2f).coerceIn(-1f, 1f)
            gazeOffsetY = ((event.y / height - 0.5f) * 2.2f).coerceIn(-1f, 1f)
        }
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
        val isSeduction = seductionAuraActive || cfg.activeDrainPct >= 25

        val w = width.toFloat()
        val h = height.toFloat()
        val hasBubble = speechText.isNotEmpty() && now < speechUntilMs
        val bubbleReserve = if (hasBubble) 38f * density else 10f * density

        val availH = (h - bubbleReserve - 8f * density).coerceAtLeast(52f * density)
        val bodyH = availH * 0.89f
        val bodyW = (bodyH * 0.80f).coerceAtMost(w * 0.78f)
        val cx = w * 0.5f
        val baseY = h - 8f * density
        val topY = baseY - bodyH

        // Organic breathing + subtle realistic sway
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
            PetPose.WALKING -> sin(animPhase * 0.9f) * 2.5f
            PetPose.CLIMBING_LEFT -> 12f
            PetPose.CLIMBING_RIGHT -> -12f
            PetPose.HANGING_TOP -> 180f
            else -> sin(animPhase * 0.5f) * 1.3f
        }

        // Ground contact shadow
        if (pose != PetPose.DRAGGED && pose != PetPose.HANGING_TOP) {
            val groundShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, baseY - 2f * density, bodyW * 0.44f,
                    intArrayOf(Color.argb(135, 0, 0, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawOval(
                RectF(cx - bodyW * 0.42f, baseY - 6f * density, cx + bodyW * 0.42f, baseY + 3f * density),
                groundShadow
            )
        }

        canvas.save()
        canvas.translate(cx, topY + bodyH * 0.5f + hoverDy)
        canvas.rotate(tiltDeg)
        canvas.scale(if (facingRight) 1f else -1f, breathScaleY)
        canvas.translate(-cx, -(topY + bodyH * 0.5f))

        val portraitRect = RectF(cx - bodyW * 0.5f, topY, cx + bodyW * 0.5f, baseY - 2f * density)

        // 1. Atmospheric rim aura (crimson anger OR violet-rose MGQ seduction!)
        drawAtmosphericAura(canvas, portraitRect, mood, isSeduction)

        // 2. Articulated 3D Demon Whip Tail behind the shoulders
        if (style == AvatarStyle.REALISTIC_DEMON || mood == DemonMood.FURIOUS || isSeduction) {
            drawRealistic3DTail(canvas, portraitRect, mood, isSeduction)
        }

        // 3. Multi-layered Realistic Veined Bat Wings
        if (style == AvatarStyle.REALISTIC_DEMON || mood == DemonMood.FURIOUS) {
            drawUltraRealisticWings(canvas, portraitRect, mood, isSeduction)
        }

        // 4. Optional dark tailored mini-torso for REALISTIC_MINI_BODY
        if (style == AvatarStyle.REALISTIC_MINI_BODY) {
            drawRealisticTailoredShadowTorso(canvas, portraitRect)
        }

        // 5. ULTRA-HD REALISTIC PORTRAIT + STUDIO DROP SHADOW + CONTOUR RIM LIGHT
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

            // Subtle realistic cinema color grade matching mood/seduction while keeping 100% real likeness
            bmpPaint.colorFilter = when {
                isSeduction -> PorterDuffColorFilter(Color.argb(26, 217, 70, 239), PorterDuff.Mode.SRC_ATOP)
                mood == DemonMood.FURIOUS -> PorterDuffColorFilter(Color.argb(30, 220, 38, 38), PorterDuff.Mode.SRC_ATOP)
                else -> null
            }
            canvas.drawBitmap(bmp, null, portraitRect, bmpPaint)
            bmpPaint.colorFilter = null

            // Real contour Rim-Light hugging hair & cheeks!
            rimMaskBitmap?.let { rim ->
                if (!rim.isRecycled && (mood == DemonMood.FURIOUS || mood == DemonMood.JEALOUS || isSeduction)) {
                    val rimColor = when {
                        isSeduction -> Color.rgb(232, 121, 249)
                        mood == DemonMood.FURIOUS -> Color.rgb(255, 68, 56)
                        else -> Color.rgb(244, 63, 94)
                    }
                    rimPaint.colorFilter = PorterDuffColorFilter(rimColor, PorterDuff.Mode.SRC_IN)
                    rimPaint.alpha = when {
                        isSeduction -> 175
                        mood == DemonMood.FURIOUS -> 195
                        else -> 115
                    }
                    canvas.drawBitmap(rim, null, portraitRect, rimPaint)
                }
            }

            // 6. Realistic glowing eyes (crimson fire OR hypnotic MGQ magenta!) following gaze
            if (mood == DemonMood.FURIOUS || isSeduction || (mood == DemonMood.JEALOUS && style == AvatarStyle.REALISTIC_DEMON)) {
                drawRealisticGlowingEyes(canvas, portraitRect, intense = mood == DemonMood.FURIOUS || isSeduction, seduction = isSeduction)
            }

            // 7. Sculpted Striated 3D Horns (grow dynamically with anger!) or Royal Crown
            if (curfewNightcap) {
                drawRealisticNightcap(canvas, portraitRect)
            } else {
                when (style) {
                    AvatarStyle.REALISTIC_DEMON -> drawSculpted3DHorns(canvas, portraitRect, mood, cfg.moodScore, isSeduction)
                    AvatarStyle.REALISTIC_MOM_CROWN -> drawRealisticCrown(canvas, portraitRect)
                    AvatarStyle.REALISTIC_PORTRAIT -> { /* Ultra-pure studio portrait */ }
                    AvatarStyle.REALISTIC_MINI_BODY -> if (mood == DemonMood.FURIOUS || isSeduction) {
                        drawSculpted3DHorns(canvas, portraitRect, mood, cfg.moodScore, isSeduction)
                    }
                }
            }
        } else {
            drawPlaceholderPortrait(canvas, portraitRect)
        }

        // 8. Layered Flames / Embers (Furious) OR Hypnotic Seduction Energy Orbs (MGQ!)
        if (isSeduction) {
            drawSeductionOrbs(canvas, portraitRect)
        } else if (mood == DemonMood.FURIOUS) {
            drawRealisticFlamesAndEmbers(canvas, portraitRect)
        }

        canvas.restore()

        // 9. Pet heart particle
        if (now < heartUntilMs) {
            val t = 1f - (heartUntilMs - now) / 1_400f
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 18f * density
                alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawText("❤️", cx - 9f * density, topY - t * 24f * density, hp)
        }

        // 10. Sleek Glassmorphism Speech Bubble
        if (hasBubble) {
            drawGlassSpeechBubble(canvas, cx, (topY - 8f * density).coerceAtLeast(34f * density), w, speechText, isSeduction)
        }
    }

    private fun drawAtmosphericAura(canvas: Canvas, rect: RectF, mood: DemonMood, isSeduction: Boolean) {
        val auraColor = when {
            isSeduction -> Color.argb(110, 217, 70, 239)
            mood == DemonMood.ANGEL -> Color.argb(42, 167, 243, 208)
            mood == DemonMood.MOM -> Color.argb(48, 251, 191, 36)
            mood == DemonMood.JEALOUS -> Color.argb(68, 244, 63, 94)
            else -> Color.argb(118, 220, 38, 38)
        }
        val pulse = 1f + sin(animPhase * 1.45f) * 0.07f
        val r = maxOf(rect.width(), rect.height()) * 0.58f * pulse
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                rect.centerX(), rect.centerY(), r,
                intArrayOf(auraColor, Color.TRANSPARENT),
                floatArrayOf(0.22f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), r, p)
    }

    /** Articulated 3D shaded whip tail behind the lower bust */
    private fun drawRealistic3DTail(canvas: Canvas, rect: RectF, mood: DemonMood, isSeduction: Boolean) {
        val speed = if (mood == DemonMood.FURIOUS) 2.9f else 1.3f
        val sway = sin(animPhase * speed) * rect.width() * 0.14f
        val startX = rect.centerX() - rect.width() * 0.14f
        val startY = rect.bottom - rect.height() * 0.22f
        val tipX = rect.right + rect.width() * 0.18f + sway * 0.5f
        val tipY = rect.bottom - rect.height() * 0.44f + cos(animPhase * speed) * rect.height() * 0.08f

        val tailPath = Path().apply {
            moveTo(startX, startY)
            cubicTo(
                rect.right + rect.width() * 0.06f, startY + rect.height() * 0.12f,
                rect.right + rect.width() * 0.26f + sway, startY - rect.height() * 0.10f,
                tipX, tipY
            )
        }
        val baseColor = if (isSeduction) Color.rgb(162, 28, 175) else Color.rgb(125, 18, 38)
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5.4f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(24, 8, 16)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.4f * density
            strokeCap = Paint.Cap.ROUND
            color = baseColor
        }
        val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(150, 255, 210, 230)
        }
        canvas.drawPath(tailPath, darkPaint)
        canvas.drawPath(tailPath, bodyPaint)
        canvas.drawPath(tailPath, specPaint)

        // 3D Spade / Heart Demon Tip
        val s = 7.5f * density
        val spade = Path().apply {
            moveTo(tipX, tipY - s)
            cubicTo(tipX + s * 1.1f, tipY - s * 0.2f, tipX + s * 0.7f, tipY + s * 0.7f, tipX, tipY + s * 0.3f)
            cubicTo(tipX - s * 0.7f, tipY + s * 0.7f, tipX - s * 1.1f, tipY - s * 0.2f, tipX, tipY - s)
            close()
        }
        canvas.drawPath(spade, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(tipX - s, tipY - s, tipX + s, tipY + s, Color.rgb(244, 63, 94), Color.rgb(55, 10, 24), Shader.TileMode.CLAMP)
        })
    }

    private fun drawUltraRealisticWings(canvas: Canvas, rect: RectF, mood: DemonMood, isSeduction: Boolean) {
        val flap = sin(animPhase * if (mood == DemonMood.FURIOUS) 2.6f else 1.25f) * 0.15f
        val shoulderY = rect.top + rect.height() * (faceMeta.chinY + 0.05f).coerceIn(0.54f, 0.78f)
        val wingSpan = rect.width() * 0.54f
        val wingH = rect.height() * 0.40f

        for (dir in listOf(-1f, 1f)) {
            canvas.save()
            val anchorX = rect.centerX() + dir * rect.width() * 0.15f
            canvas.translate(anchorX, shoulderY)
            canvas.rotate(dir * (10f + flap * 56f))

            val path = Path().apply {
                moveTo(0f, 0f)
                cubicTo(
                    dir * wingSpan * 0.46f, -wingH * 0.96f,
                    dir * wingSpan * 0.98f, -wingH * 0.68f,
                    dir * wingSpan * 1.12f, -wingH * 0.10f
                )
                quadTo(dir * wingSpan * 0.80f, wingH * 0.02f, dir * wingSpan * 0.76f, wingH * 0.30f)
                quadTo(dir * wingSpan * 0.48f, wingH * 0.14f, dir * wingSpan * 0.38f, wingH * 0.40f)
                quadTo(dir * wingSpan * 0.15f, wingH * 0.22f, 0f, wingH * 0.18f)
                close()
            }

            // Soft drop shadow behind wing
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(95, 0, 0, 0)
                maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            })

            val midCol = when {
                isSeduction -> Color.argb(225, 134, 25, 143)
                mood == DemonMood.FURIOUS -> Color.argb(232, 136, 19, 45)
                else -> Color.argb(220, 62, 18, 36)
            }
            val wingFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, -wingH * 0.85f, dir * wingSpan, wingH * 0.35f,
                    intArrayOf(Color.argb(242, 18, 8, 15), midCol, Color.argb(195, 12, 6, 14)),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(path, wingFill)

            // Bone spine + realistic veins
            val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.1f * density
                strokeCap = Paint.Cap.ROUND
                color = if (isSeduction) Color.argb(190, 240, 171, 252) else Color.argb(180, 244, 63, 94)
            }
            val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * density
                color = Color.argb(125, 253, 164, 175)
            }
            canvas.drawLine(0f, 0f, dir * wingSpan * 0.76f, wingH * 0.28f, bonePaint)
            canvas.drawLine(0f, 0f, dir * wingSpan * 0.38f, wingH * 0.38f, veinPaint)
            canvas.drawLine(dir * wingSpan * 0.35f, -wingH * 0.35f, dir * wingSpan * 1.04f, -wingH * 0.12f, veinPaint)
            canvas.restore()
        }
    }

    /**
     * Sculpted striated 3D obsidian horns anchored to the REAL person's temples.
     * Dynamically scale larger & glow hotter as moodScore increases!
     */
    private fun drawSculpted3DHorns(canvas: Canvas, rect: RectF, mood: DemonMood, moodScore: Int, isSeduction: Boolean) {
        val angerGrowth = 0.86f + (moodScore / 100f) * 0.34f
        val hornScale = (rect.width() * faceMeta.faceWidthRatio * 0.48f * angerGrowth).coerceIn(11f * density, 34f * density)
        val lx = rect.left + rect.width() * faceMeta.leftTempleX
        val ly = rect.top + rect.height() * faceMeta.leftTempleY
        val rx = rect.left + rect.width() * faceMeta.rightTempleX
        val ry = rect.top + rect.height() * faceMeta.rightTempleY

        drawStriatedHorn(canvas, lx, ly, -1f, hornScale, mood, isSeduction)
        drawStriatedHorn(canvas, rx, ry, 1f, hornScale, mood, isSeduction)
    }

    private fun drawStriatedHorn(canvas: Canvas, baseX: Float, baseY: Float, dir: Float, s: Float, mood: DemonMood, isSeduction: Boolean) {
        // Deep contact shadow on hair/skin
        val rootShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 0, 0, 0)
            maskFilter = BlurMaskFilter(4.5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(RectF(baseX - s * 0.38f, baseY - s * 0.10f, baseX + s * 0.38f, baseY + s * 0.26f), rootShadow)

        val hornPath = Path().apply {
            moveTo(baseX - dir * s * 0.28f, baseY + s * 0.08f)
            cubicTo(
                baseX + dir * s * 0.04f, baseY - s * 0.62f,
                baseX + dir * s * 0.68f, baseY - s * 0.96f,
                baseX + dir * s * 0.98f, baseY - s * 1.24f
            )
            cubicTo(
                baseX + dir * s * 1.00f, baseY - s * 0.64f,
                baseX + dir * s * 0.54f, baseY - s * 0.14f,
                baseX + dir * s * 0.30f, baseY + s * 0.16f
            )
            close()
        }

        val tipColor = when {
            isSeduction -> Color.rgb(240, 171, 252)
            mood == DemonMood.FURIOUS -> Color.rgb(255, 110, 90)
            else -> Color.rgb(225, 72, 92)
        }
        val midColor = when {
            isSeduction -> Color.rgb(134, 25, 143)
            mood == DemonMood.FURIOUS -> Color.rgb(185, 22, 48)
            else -> Color.rgb(112, 16, 36)
        }
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                baseX, baseY + s * 0.12f,
                baseX + dir * s * 0.96f, baseY - s * 1.22f,
                intArrayOf(Color.rgb(20, 6, 12), midColor, tipColor),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(hornPath, grad)

        // Realistic keratin growth rings (striations) clipped inside horn
        canvas.save()
        canvas.clipPath(hornPath)
        val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = Color.argb(110, 12, 4, 8)
        }
        for (i in 1..4) {
            val t = i * 0.20f
            val rx = baseX + dir * s * (t * 0.72f)
            val ry = baseY - s * (t * 0.94f)
            canvas.drawLine(rx - dir * s * 0.28f, ry - s * 0.10f, rx + dir * s * 0.28f, ry + s * 0.12f, ringDark)
        }
        canvas.restore()

        // Glossy specular ridge highlight
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(165, 255, 232, 240)
        }
        val ridge = Path().apply {
            moveTo(baseX - dir * s * 0.05f, baseY - s * 0.10f)
            quadTo(baseX + dir * s * 0.36f, baseY - s * 0.66f, baseX + dir * s * 0.84f, baseY - s * 1.08f)
        }
        canvas.drawPath(ridge, spec)
    }

    private fun drawRealisticGlowingEyes(canvas: Canvas, rect: RectF, intense: Boolean, seduction: Boolean) {
        val gx = gazeOffsetX * 2.2f * density
        val gy = gazeOffsetY * 1.6f * density
        val lx = rect.left + rect.width() * faceMeta.leftEyeX + gx
        val ly = rect.top + rect.height() * faceMeta.leftEyeY + gy
        val rx = rect.left + rect.width() * faceMeta.rightEyeX + gx
        val ry = rect.top + rect.height() * faceMeta.rightEyeY + gy
        val glowR = (rect.width() * faceMeta.faceWidthRatio * (if (intense) 0.21f else 0.13f)).coerceAtLeast(5.5f * density)

        val coreCol = if (seduction) Color.argb(235, 255, 230, 255) else Color.argb(230, 255, 235, 225)
        val midCol = when {
            seduction -> Color.argb(215, 232, 70, 245)
            intense -> Color.argb(205, 244, 38, 68)
            else -> Color.argb(120, 244, 63, 94)
        }
        for (pt in listOf(Pair(lx, ly), Pair(rx, ry))) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    pt.first, pt.second, glowR,
                    intArrayOf(coreCol, midCol, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.36f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(pt.first, pt.second, glowR, p)
        }
    }

    private fun drawRealisticCrown(canvas: Canvas, rect: RectF) {
        val cx = rect.left + rect.width() * ((faceMeta.leftTempleX + faceMeta.rightTempleX) * 0.5f)
        val cy = rect.top + rect.height() * (faceMeta.foreheadY - 0.04f)
        val cw = rect.width() * faceMeta.faceWidthRatio * 0.74f
        val ch = cw * 0.45f
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
        canvas.drawPath(crown, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy,
                intArrayOf(Color.rgb(180, 120, 18), Color.rgb(254, 240, 138), Color.rgb(217, 119, 6)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        })
        canvas.drawCircle(cx, cy - ch * 0.35f, 2.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 29, 72) })
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
                intArrayOf(Color.argb(195, 24, 18, 32), Color.argb(0, 12, 10, 18)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(rect.left + rect.width() * 0.18f, torsoTop, rect.right - rect.width() * 0.18f, rect.bottom),
            rect.width() * 0.25f, rect.width() * 0.25f, p
        )
    }

    private fun drawRealisticFlamesAndEmbers(canvas: Canvas, rect: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 8) {
            val phase = (animPhase * 0.48f + i * 0.91f) % 1f
            val px = rect.left + rect.width() * (0.12f + (i * 0.11f)) + sin(animPhase * 1.6f + i) * 6f * density
            val py = rect.bottom - phase * rect.height() * 0.98f
            val alpha = ((1f - phase) * 225).toInt().coerceIn(0, 255)
            p.color = Color.argb(alpha, 255, (90 + (i * 22) % 120), 40)
            canvas.drawCircle(px, py, (1.8f + (1f - phase) * 2.2f) * density, p)
        }
    }

    private fun drawSeductionOrbs(canvas: Canvas, rect: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 7) {
            val phase = (animPhase * 0.36f + i * 0.87f) % 1f
            val angle = animPhase * 1.1f + i * 1.05f
            val px = rect.centerX() + cos(angle) * rect.width() * 0.48f
            val py = rect.bottom - phase * rect.height() * 0.95f
            val alpha = ((1f - phase) * 220).toInt().coerceIn(0, 255)
            p.shader = RadialGradient(
                px, py, 6.5f * density,
                intArrayOf(Color.argb(alpha, 255, 220, 255), Color.argb(alpha / 2, 217, 70, 239), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, 6.5f * density, p)
        }
    }

    private fun drawPlaceholderPortrait(canvas: Canvas, rect: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                rect.centerX(), rect.centerY(), rect.width() * 0.55f,
                Color.rgb(68, 22, 52), Color.rgb(20, 14, 28), Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(rect, p)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = rect.width() * 0.42f; textAlign = Paint.Align.CENTER }
        canvas.drawText("😈", rect.centerX(), rect.centerY() + rect.width() * 0.14f, txt)
    }

    private fun drawGlassSpeechBubble(canvas: Canvas, cx: Float, bottomY: Float, viewW: Float, text: String, isSeduction: Boolean) {
        val padH = 11f * density
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

        bubbleStrokePaint.color = if (isSeduction) Color.argb(230, 232, 121, 249) else Color.argb(220, 244, 63, 94)
        canvas.drawRoundRect(r, 12f * density, 12f * density, bubbleBgPaint)
        canvas.drawRoundRect(r, 12f * density, 12f * density, bubbleStrokePaint)
        val fm = bubbleTextPaint.fontMetrics
        val baseline = r.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(shown, r.left + padH, baseline, bubbleTextPaint)
    }
}
