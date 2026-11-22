package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.media.MediaFolderHelper
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Unified Overlay Orchestrator for DemonMom AI:
 * - Living 60–120 FPS Demon Avatar physics (walking, wall climbing, peeking, sitting, throwing & bouncing)
 * - #15 Dragged Floating Photo Popups (Desktop Goose)
 * - #16 Fullscreen Photo Flash Overlay (zoom/shake/fade)
 * - #7 "Demande la permission !" Dramatic Popup
 * - #8/#9/#10/#11/#20/#23 Sarcastic Lock Shield with Mini-Game Negotiation (Pet 10x / Math / Wallpaper Trade)
 * - 20-Tap Secret Exit PIN (`100205`) Overlay Dialog
 */
class DemonOverlayManager(
    private val context: Context,
    private val windowType: Int,
    private val settings: DemonSettings,
    private val onGoHome: () -> Unit,
    private val onExitUnlocked: () -> Unit,
    private val onGraceGranted: (packageName: String, minutes: Int) -> Unit
) : Choreographer.FrameCallback {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var screenW = context.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(360f)
    private var screenH = context.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(640f)

    private var petView: DemonPetView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var censorView: DemonCensorOverlayView? = null

    private val activePopups = ArrayList<Pair<View, Bitmap?>>()
    private var flashOverlayView: View? = null
    private var flashBitmap: Bitmap? = null
    private var activeModalView: View? = null

    private var physicsRunning = false
    private var lastFrameNs = 0L
    private var petX = 120f
    private var petY = 500f
    private var petVx = 110f
    private var petVy = 0f
    private var isDraggingPet = false
    private var isThrown = false
    private var nextBehaviorChangeMs = 0L
    private var panicUntilMs = 0L
    private var sitCenterUntilMs = 0L

    @Volatile
    private var currentConfig: DemonConfigSnapshot = settings.snapshot()

    private fun dp(v: Int): Int = (v * density).toInt()

    fun attach(cfg: DemonConfigSnapshot) {
        currentConfig = cfg
        mainHandler.post {
            attachCensorLayerLocked(cfg)
            attachPetLayerLocked(cfg)
            startPhysics()
        }
    }

    fun updateConfig(cfg: DemonConfigSnapshot) {
        currentConfig = cfg
        mainHandler.post {
            censorView?.updateConfig(cfg)
            petView?.updateConfig(cfg)
            resizePetWindowLocked(cfg)
        }
    }

    fun getCensorView(): DemonCensorOverlayView? = censorView

    fun say(text: String, durationMs: Long = 4_200L) {
        mainHandler.post { petView?.say(text, durationMs) }
    }

    fun setCurfewNightcap(active: Boolean) {
        mainHandler.post {
            petView?.curfewNightcap = active
            petView?.invalidate()
        }
    }

    fun triggerPanicMode(message: String, durationMs: Long = 5_000L) {
        panicUntilMs = System.currentTimeMillis() + durationMs
        say(message, durationMs)
        vibrateShort(70L)
    }

    fun triggerSitInCenter(message: String, durationMs: Long = 7_500L) {
        sitCenterUntilMs = System.currentTimeMillis() + durationMs
        say(message, durationMs)
        vibrateShort(50L)
    }

    fun detachAll() {
        stopPhysics()
        mainHandler.post {
            dismissModal()
            dismissFlash()
            clearAllPopups()
            petView?.let { v -> try { wm.removeViewImmediate(v) } catch (_: Throwable) {} }
            petView = null
            petParams = null
            censorView?.let { v -> try { wm.removeViewImmediate(v) } catch (_: Throwable) {} }
            censorView = null
        }
    }

    private fun attachCensorLayerLocked(cfg: DemonConfigSnapshot) {
        if (censorView != null) return
        try {
            val view = DemonCensorOverlayView(context).apply { updateConfig(cfg) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(view, params)
            censorView = view
        } catch (_: Throwable) {
        }
    }

    private fun attachPetLayerLocked(cfg: DemonConfigSnapshot) {
        if (petView != null) return
        try {
            val dm = context.resources.displayMetrics
            screenW = dm.widthPixels.toFloat().coerceAtLeast(360f)
            screenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
            val winW = dp(cfg.avatarSizeDp + 44)
            val winH = dp(cfg.avatarSizeDp + 64)
            petX = (screenW - winW) * 0.5f
            petY = screenH - winH - dp(90)

            val view = DemonPetView(
                context = context,
                onDragMove = { dx, dy ->
                    isDraggingPet = true
                    isThrown = false
                    petX = (petX + dx).coerceIn(-winW * 0.35f, screenW - winW * 0.65f)
                    petY = (petY + dy).coerceIn(dp(24).toFloat(), screenH - winH * 0.65f)
                    applyPetPositionLocked()
                },
                onDragRelease = { vx, vy, wasTap ->
                    isDraggingPet = false
                    val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
                    if (!wasTap && speed > 550f * density) {
                        isThrown = true
                        petVx = vx.coerceIn(-1800f, 1800f)
                        petVy = vy.coerceIn(-1800f, 1400f)
                        settings.adjustMood(+12)
                        petView?.say(THROW_LINES.random(), 3_500L)
                        vibrateShort(45L)
                    } else {
                        isThrown = false
                        petView?.pose = DemonPetView.PetPose.WALKING
                    }
                },
                onGentlePet = {
                    settings.adjustMood(-4)
                    if (Random.nextFloat() < 0.35f) {
                        petView?.say(PET_LINES.random(), 2_600L)
                    }
                },
                onSecretTwentyTaps = {
                    showSecretExitPinDialog()
                }
            )
            view.updateConfig(cfg)
            view.say("Je te surveille… 😈", 4_000L)

            val params = WindowManager.LayoutParams(
                winW,
                winH,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = petX.toInt()
                y = petY.toInt()
            }
            wm.addView(view, params)
            petView = view
            petParams = params
        } catch (_: Throwable) {
        }
    }

    private fun resizePetWindowLocked(cfg: DemonConfigSnapshot) {
        val v = petView ?: return
        val p = petParams ?: return
        val newW = dp(cfg.avatarSizeDp + 44)
        val newH = dp(cfg.avatarSizeDp + 64)
        if (p.width != newW || p.height != newH) {
            p.width = newW
            p.height = newH
            try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
        }
    }

    private fun applyPetPositionLocked() {
        val v = petView ?: return
        val p = petParams ?: return
        p.x = petX.toInt()
        p.y = petY.toInt()
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun startPhysics() {
        if (!physicsRunning) {
            physicsRunning = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopPhysics() {
        physicsRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!physicsRunning) return
        val dt = if (lastFrameNs > 0L) ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.004f, 0.06f) else 0.016f
        lastFrameNs = frameTimeNanos

        val v = petView
        val p = petParams
        if (v != null && p != null) {
            v.animPhase += dt
            val winW = p.width.toFloat()
            val winH = p.height.toFloat()
            val now = System.currentTimeMillis()
            val floorY = screenH - winH - dp(36)
            val ceilY = dp(32).toFloat()

            if (!isDraggingPet) {
                when {
                    isThrown -> {
                        petVy += 2200f * dt // gravity
                        petX += petVx * dt
                        petY += petVy * dt
                        if (petX < 0f) { petX = 0f; petVx = abs(petVx) * 0.65f }
                        if (petX > screenW - winW) { petX = screenW - winW; petVx = -abs(petVx) * 0.65f }
                        if (petY < ceilY) { petY = ceilY; petVy = abs(petVy) * 0.55f }
                        if (petY >= floorY) {
                            petY = floorY
                            petVy = -abs(petVy) * 0.35f
                            petVx *= 0.6f
                            if (abs(petVy) < 120f) {
                                isThrown = false
                                v.pose = DemonPetView.PetPose.WALKING
                                petVx = (if (Random.nextBoolean()) 1f else -1f) * (70f + currentConfig.chaosLevel * 12f)
                            }
                        }
                    }

                    now < panicUntilMs -> {
                        v.pose = DemonPetView.PetPose.PANICKING
                        petX += petVx * dt * 2.4f
                        if (petX < 8f) { petX = 8f; petVx = abs(petVx).coerceAtLeast(180f) }
                        if (petX > screenW - winW - 8f) { petX = screenW - winW - 8f; petVx = -abs(petVx).coerceAtLeast(180f) }
                        v.facingRight = petVx >= 0f
                    }

                    now < sitCenterUntilMs -> {
                        v.pose = DemonPetView.PetPose.SITTING
                        val targetX = (screenW - winW) * 0.5f
                        val targetY = (screenH - winH) * 0.45f
                        petX += (targetX - petX) * 0.10f
                        petY += (targetY - petY) * 0.10f
                    }

                    else -> {
                        if (now > nextBehaviorChangeMs) {
                            pickNextAutonomousBehavior(v, winW, winH, floorY, ceilY)
                            nextBehaviorChangeMs = now + Random.nextLong(4_500L, 11_000L)
                        }
                        val speedMul = 0.7f + currentConfig.chaosLevel * 0.11f
                        when (v.pose) {
                            DemonPetView.PetPose.WALKING -> {
                                petY += (floorY - petY) * 0.08f
                                petX += petVx * dt * speedMul
                                if (petX < 6f) { petX = 6f; petVx = abs(petVx) }
                                if (petX > screenW - winW - 6f) { petX = screenW - winW - 6f; petVx = -abs(petVx) }
                                v.facingRight = petVx >= 0f
                            }
                            DemonPetView.PetPose.CLIMBING_LEFT -> {
                                petX += (-winW * 0.15f - petX) * 0.12f
                                petY += petVy * dt * speedMul
                                if (petY < ceilY + 40f) { petY = ceilY + 40f; petVy = abs(petVy) }
                                if (petY > floorY - 30f) { petY = floorY - 30f; petVy = -abs(petVy) }
                            }
                            DemonPetView.PetPose.CLIMBING_RIGHT -> {
                                petX += (screenW - winW * 0.85f - petX) * 0.12f
                                petY += petVy * dt * speedMul
                                if (petY < ceilY + 40f) { petY = ceilY + 40f; petVy = abs(petVy) }
                                if (petY > floorY - 30f) { petY = floorY - 30f; petVy = -abs(petVy) }
                            }
                            DemonPetView.PetPose.HANGING_TOP -> {
                                petY += (ceilY - petY) * 0.12f
                                petX += petVx * dt * 0.65f
                                if (petX < 20f) { petX = 20f; petVx = abs(petVx) }
                                if (petX > screenW - winW - 20f) { petX = screenW - winW - 20f; petVx = -abs(petVx) }
                            }
                            DemonPetView.PetPose.PEEKING_LEFT -> {
                                petX += (-winW * 0.42f - petX) * 0.14f
                            }
                            DemonPetView.PetPose.PEEKING_RIGHT -> {
                                petX += (screenW - winW * 0.58f - petX) * 0.14f
                            }
                            else -> {
                                // Sitting quietly
                            }
                        }
                    }
                }
                applyPetPositionLocked()
            }
            v.invalidate()
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pickNextAutonomousBehavior(v: DemonPetView, winW: Float, winH: Float, floorY: Float, ceilY: Float) {
        val roll = Random.nextInt(100)
        when {
            roll < 38 -> {
                v.pose = DemonPetView.PetPose.WALKING
                petVx = (if (Random.nextBoolean()) 1f else -1f) * Random.nextFloat().let { 85f + it * 90f }
            }
            roll < 55 -> {
                v.pose = if (Random.nextBoolean()) DemonPetView.PetPose.CLIMBING_LEFT else DemonPetView.PetPose.CLIMBING_RIGHT
                petVy = (if (Random.nextBoolean()) 1f else -1f) * 85f
            }
            roll < 70 -> {
                v.pose = DemonPetView.PetPose.HANGING_TOP
                petVx = (if (Random.nextBoolean()) 1f else -1f) * 70f
            }
            roll < 86 -> {
                v.pose = if (Random.nextBoolean()) DemonPetView.PetPose.PEEKING_LEFT else DemonPetView.PetPose.PEEKING_RIGHT
                petY = Random.nextFloat() * (floorY - ceilY - 120f).coerceAtLeast(100f) + ceilY + 60f
                if (Random.nextFloat() < 0.45f) v.say("Coucou… je te vois 👀", 3_000L)
            }
            else -> {
                v.pose = DemonPetView.PetPose.SITTING
            }
        }
    }

    // ==================== #15 DRAGGED FLOATING PHOTO POPUP (DESKTOP GOOSE) ====================

    fun showDraggedPhotoPopup(cfg: DemonConfigSnapshot) {
        Thread {
            val bmp = MediaFolderHelper.loadRandomMediaBitmap(context, cfg, maxDimPx = 640) ?: return@Thread
            mainHandler.post {
                try {
                    while (activePopups.size >= cfg.maxPopups) {
                        val oldest = activePopups.removeAt(0)
                        try { wm.removeViewImmediate(oldest.first) } catch (_: Throwable) {}
                        oldest.second?.let { if (!it.isRecycled) it.recycle() }
                    }

                    val cardW = dp(210)
                    val cardH = dp(250)
                    val container = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat()
                            setColor(Color.rgb(20, 20, 26))
                            setStroke(dp(2), Color.rgb(244, 63, 94))
                        }
                        setPadding(dp(8), dp(6), dp(8), dp(8))
                    }

                    val titleBar = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val caption = TextView(context).apply {
                        text = pickPossessiveCaption(cfg)
                        setTextColor(Color.rgb(253, 164, 175))
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    }
                    val closeBtn = TextView(context).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        setPadding(dp(8), dp(2), dp(4), dp(2))
                    }
                    titleBar.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    titleBar.addView(closeBtn)
                    container.addView(titleBar)

                    val imgView = ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    container.addView(imgView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)).apply {
                        topMargin = dp(6)
                    })

                    val fromLeft = Random.nextBoolean()
                    val targetX = Random.nextInt(dp(20), (screenW.toInt() - cardW - dp(20)).coerceAtLeast(dp(24)))
                    val targetY = Random.nextInt(dp(90), (screenH.toInt() - cardH - dp(120)).coerceAtLeast(dp(100)))
                    val startX = if (fromLeft) -cardW else screenW.toInt() + dp(10)

                    val params = WindowManager.LayoutParams(
                        cardW,
                        cardH,
                        windowType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = startX
                        y = targetY
                    }

                    val pair = Pair<View, Bitmap?>(container, bmp)
                    closeBtn.setOnClickListener {
                        activePopups.remove(pair)
                        try { wm.removeViewImmediate(container) } catch (_: Throwable) {}
                        if (!bmp.isRecycled) bmp.recycle()
                    }

                    var dragStartX = 0; var dragStartY = 0
                    var touchStartX = 0f; var touchStartY = 0f
                    container.setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                dragStartX = params.x; dragStartY = params.y
                                touchStartX = ev.rawX; touchStartY = ev.rawY
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                params.x = dragStartX + (ev.rawX - touchStartX).toInt()
                                params.y = dragStartY + (ev.rawY - touchStartY).toInt()
                                try { wm.updateViewLayout(container, params) } catch (_: Throwable) {}
                                true
                            }
                            else -> false
                        }
                    }

                    wm.addView(container, params)
                    activePopups.add(pair)

                    // Animate Demon pulling the photo card into screen!
                    petView?.say("Tiens, regarde ça ! 😈", 3_000L)
                    vibrateShort(35L)
                    var step = 0
                    val totalSteps = 18
                    val slideRunnable = object : Runnable {
                        override fun run() {
                            step++
                            val frac = (step.toFloat() / totalSteps).coerceIn(0f, 1f)
                            val ease = 1f - (1f - frac) * (1f - frac)
                            params.x = (startX + (targetX - startX) * ease).toInt()
                            try { wm.updateViewLayout(container, params) } catch (_: Throwable) {}
                            if (step < totalSteps) mainHandler.postDelayed(this, 16L)
                        }
                    }
                    mainHandler.post(slideRunnable)
                } catch (_: Throwable) {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }.start()
    }

    fun clearAllPopups() {
        for ((v, bmp) in activePopups) {
            try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
            bmp?.let { if (!it.isRecycled) it.recycle() }
        }
        activePopups.clear()
    }

    // ==================== #16 FULLSCREEN PHOTO FLASH OVERLAY ====================

    fun showFullscreenPhotoFlash(cfg: DemonConfigSnapshot) {
        Thread {
            val bmp = MediaFolderHelper.loadRandomMediaBitmap(context, cfg, maxDimPx = 1280) ?: return@Thread
            mainHandler.post {
                dismissFlash()
                try {
                    val root = FrameLayout(context).apply {
                        setBackgroundColor(Color.argb(235, 9, 9, 12))
                    }
                    val iv = ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        scaleX = 1.12f
                        scaleY = 1.12f
                        alpha = 0f
                    }
                    root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                    val banner = TextView(context).apply {
                        text = pickPossessiveCaption(cfg)
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(dp(20), dp(12), dp(20), dp(12))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(20).toFloat()
                            setColor(Color.argb(200, 225, 29, 72))
                        }
                    }
                    val bLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        bottomMargin = dp(64)
                    }
                    root.addView(banner, bLp)
                    root.setOnClickListener { dismissFlash() }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        windowType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    )
                    wm.addView(root, params)
                    flashOverlayView = root
                    flashBitmap = bmp
                    vibrateShort(65L)

                    iv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).start()
                    mainHandler.postDelayed({ dismissFlash() }, cfg.flashDurationMs.toLong())
                } catch (_: Throwable) {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }.start()
    }

    fun dismissFlash() {
        val v = flashOverlayView ?: return
        flashOverlayView = null
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        flashBitmap?.let { if (!it.isRecycled) it.recycle() }
        flashBitmap = null
    }

    // ==================== #7 "DEMANDE LA PERMISSION !" DRAMATIC GATE ====================

    fun showPermissionGatePopup(appLabel: String, pkg: String, cfg: DemonConfigSnapshot) {
        mainHandler.post {
            if (activeModalView != null) return@post
            val card = buildDarkModalCard(
                title = "Tu vas où comme ça ?! 😈",
                subtitle = "Tu veux ouvrir « $appLabel » sans me demander la permission d'abord ?"
            )
            val statusTv = TextView(context).apply {
                text = ""
                setTextColor(Color.rgb(253, 224, 71))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            }
            card.addView(statusTv)

            val pleaseBtn = makePrimaryButton("S'il te plaît 🥺 (Juste 5 min)")
            val backBtn = makeSecondaryButton("Pardon, je retourne à l'accueil")

            var teasing = false
            pleaseBtn.setOnClickListener {
                if (teasing) return@setOnClickListener
                if (Random.nextFloat() < 0.55f) {
                    settings.adjustMood(-5)
                    dismissModal()
                    petView?.say("Bon d'accord… mais je te surveille ! 👀", 3_500L)
                    onGraceGranted(pkg, 5)
                } else {
                    teasing = true
                    pleaseBtn.isEnabled = false
                    var rem = 5
                    val tick = object : Runnable {
                        override fun run() {
                            if (activeModalView == null) return
                            if (rem > 0) {
                                statusTv.text = "Hmm… je réfléchis… attends $rem s 😈"
                                rem--
                                mainHandler.postDelayed(this, 1_000L)
                            } else {
                                dismissModal()
                                petView?.say("Ok c'est bon, file ! 😈", 3_000L)
                                onGraceGranted(pkg, 5)
                            }
                        }
                    }
                    mainHandler.post(tick)
                }
            }
            backBtn.setOnClickListener {
                dismissModal()
                onGoHome()
                petView?.say("C'est bien, sage ! 😇", 3_000L)
            }

            card.addView(pleaseBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })
            card.addView(backBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            presentModal(card, focusable = false)
        }
    }

    // ==================== #8 / #9 / #10 / #11 / #20 / #23 LOCK SHIELD + MINI-GAME NEGOTIATION ====================

    fun showLockShieldWithNegotiation(
        title: String,
        reason: String,
        pkg: String,
        allowNegotiation: Boolean,
        cfg: DemonConfigSnapshot
    ) {
        mainHandler.post {
            if (activeModalView != null) return@post
            val card = buildDarkModalCard(title = title, subtitle = reason)

            if (allowNegotiation && cfg.negotiationMiniGames) {
                val negBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(8))
                }

                // Mini-Game 1: Pet Demon 10x
                var pets = 0
                val petBtn = makePrimaryButton("💖 Caresser ${cfg.avatarName} (0/10) → +10 min")
                petBtn.setOnClickListener {
                    pets++
                    vibrateShort(20L)
                    if (pets >= 10) {
                        settings.adjustMood(-12)
                        dismissModal()
                        petView?.say("Hihi bon ok, +10 minutes ! 💖", 3_500L)
                        onGraceGranted(pkg, 10)
                    } else {
                        petBtn.text = "💖 Caresser ${cfg.avatarName} ($pets/10) → +10 min"
                    }
                }
                negBox.addView(petBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })

                // Mini-Game 2: Wallpaper Trade Deal!
                val wallBtn = makeAccentButton("🖼️ Laisser changer mon fond d'écran → +10 min")
                wallBtn.setOnClickListener {
                    wallBtn.isEnabled = false
                    wallBtn.text = "Changement en cours…"
                    Thread {
                        MediaFolderHelper.applyRandomWallpaperPrank(context, cfg)
                        mainHandler.post {
                            dismissModal()
                            petView?.say("Marché conclu ! Beau fond d'écran 😈", 4_000L)
                            onGraceGranted(pkg, 10)
                        }
                    }.start()
                }
                negBox.addView(wallBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })

                // Mini-Game 3: Quick Mental Math Challenge
                val a = Random.nextInt(12, 39)
                val b = Random.nextInt(7, 24)
                val mathRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val mathInput = EditText(context).apply {
                    hint = "$a + $b = ?"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.rgb(161, 161, 170))
                    textSize = 13f
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.rgb(12, 12, 16))
                        setStroke(dp(1), Color.argb(40, 255, 255, 255))
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val mathBtn = Button(context).apply {
                    text = "Valider"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(16, 185, 129)) }
                    setOnClickListener {
                        if (mathInput.text.toString().trim().toIntOrNull() == a + b) {
                            dismissModal()
                            petView?.say("Bien joué ! +10 min accordées 🧠", 3_500L)
                            onGraceGranted(pkg, 10)
                        } else {
                            mathInput.error = "Raté ! 😈"
                        }
                    }
                }
                mathRow.addView(mathInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
                mathRow.addView(mathBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
                negBox.addView(mathRow)
                card.addView(negBox)
            }

            val homeBtn = makeSecondaryButton("Retourner à l'accueil")
            homeBtn.setOnClickListener {
                dismissModal()
                onGoHome()
            }
            card.addView(homeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) })
            presentModal(card, focusable = allowNegotiation && cfg.negotiationMiniGames)
        }
    }

    // ==================== 20-TAP SECRET EXIT PIN (100205) DIALOG ====================

    fun showSecretExitPinDialog() {
        mainHandler.post {
            dismissModal()
            vibrateShort(90L)
            val card = buildDarkModalCard(
                title = "Sortie secrète (20 taps réussis)",
                subtitle = "Saisis le code de sortie pour déverrouiller DemonMom AI, retirer l'administrateur et mettre en pause."
            )
            val pinInput = EditText(context).apply {
                hint = "Code secret (ex. 100205)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(161, 161, 170))
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.rgb(12, 12, 16))
                    setStroke(dp(1), Color.rgb(244, 63, 94))
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val errTv = TextView(context).apply {
                setTextColor(Color.rgb(251, 113, 133))
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
            }
            val okBtn = makePrimaryButton("Déverrouiller & Sortir")
            val cancelBtn = makeSecondaryButton("Annuler")

            okBtn.setOnClickListener {
                if (settings.verifyExitPin(pinInput.text.toString())) {
                    dismissModal()
                    onExitUnlocked()
                } else {
                    errTv.text = "Mauvais code ! 😈"
                    vibrateShort(100L)
                }
            }
            cancelBtn.setOnClickListener { dismissModal() }

            card.addView(pinInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            card.addView(errTv)
            card.addView(okBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })
            card.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            presentModal(card, focusable = true)
        }
    }

    private fun buildDarkModalCard(title: String, subtitle: String): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.rgb(20, 20, 26))
                setStroke(dp(2), Color.rgb(244, 63, 94))
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val avatarPath = currentConfig.avatarPath
        if (avatarPath.isNotBlank() && File(avatarPath).exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(avatarPath)
                if (bmp != null) {
                    val iv = ImageView(context).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    card.addView(iv, LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(8)
                    })
                }
            } catch (_: Throwable) {
            }
        }
        card.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.addView(TextView(context).apply {
            text = subtitle
            setTextColor(Color.rgb(212, 212, 216))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        })
        return card
    }

    private fun presentModal(card: LinearLayout, focusable: Boolean) {
        dismissModal()
        try {
            val backdrop = FrameLayout(context).apply {
                setBackgroundColor(Color.argb(205, 9, 9, 12))
                setPadding(dp(22), dp(22), dp(22), dp(22))
            }
            backdrop.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val flags = if (focusable) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(backdrop, params)
            activeModalView = backdrop
        } catch (_: Throwable) {
        }
    }

    fun dismissModal() {
        val v = activeModalView ?: return
        activeModalView = null
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
    }

    private fun makePrimaryButton(label: String): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(225, 29, 72)) }
    }

    private fun makeAccentButton(label: String): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(147, 51, 234)) }
    }

    private fun makeSecondaryButton(label: String): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.rgb(212, 212, 216))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.rgb(30, 30, 38))
            setStroke(dp(1), Color.argb(30, 255, 255, 255))
        }
    }

    private fun pickPossessiveCaption(cfg: DemonConfigSnapshot): String {
        val custom = cfg.customPhraseList()
        if (custom.isNotEmpty() && Random.nextFloat() < 0.55f) return custom.random()
        return POSSESSIVE_CAPTIONS.random()
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        if (!currentConfig.vibrateOnPrank) return
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val THROW_LINES = listOf(
            "AÏE ! Tu m'as lancée ?! 😈",
            "Tu vas le regretter !! 💢",
            "Hey ! Doucement !! 😤",
            "Oh tu cherches la guerre ? 😈"
        )
        private val PET_LINES = listOf(
            "Hihi 💖",
            "C'est bien, sois gentil 😇",
            "Aww… mais je surveille quand même 👀",
            "Encore ! 💕"
        )
        private val POSSESSIVE_CAPTIONS = listOf(
            "Regarde-MOI plutôt 😈",
            "Coucou, tu m'avais oubliée ? 👀",
            "C'est MON téléphone ici 😈",
            "Petite surprise ! 💖",
            "Pas le droit de m'ignorer 😤"
        )
    }
}
