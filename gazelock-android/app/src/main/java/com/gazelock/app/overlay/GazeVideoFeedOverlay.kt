package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.gazelock.app.ai.GazeTrackerEngine.GazeReading
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.OverlayMode
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.media.VideoFeedRepository.VideoItem
import kotlin.math.abs
import kotlin.random.Random

/**
 * GazeLock AI v3.0 Video Feed Overlay:
 * - Supports TWO modes:
 *   1) [OverlayMode.GHOST_PASSTHROUGH]: Fullscreen video fills the display at 45–50% opacity (35..65%)
 *      with FLAG_NOT_TOUCHABLE so the user keeps using ALL apps normally, paired with a tiny draggable
 *      floating pill (no close button!) that launches the 3-minute interactive overlay unlock portal!
 *   2) [OverlayMode.FULL_LOCK]: 100% opaque touch-locked fullscreen video feed with 20-tap portal trigger.
 * - #7 Subliminal Image Flashes (0.08s–0.22s) + Neon Mantras layered seamlessly over the video in BOTH modes!
 * - Interactive Portal Window (Window #3) hosts the 3-minute 0.1s image-spam + pop-ups + Sliding Puzzle &
 *   Image Reconstruction mini-games directly in overlay!
 */
class GazeVideoFeedOverlay(
    private val context: Context,
    private val config: GazeConfigSnapshot,
    initialPlaylist: List<VideoItem>,
    private val onMountPortalOverlay: (View) -> Boolean,
    private val onRemovePortalOverlay: () -> Unit,
    private val onDragGhostPill: (dx: Int, dy: Int) -> Unit,
    private val onIssueAccessCodeAfterWait: () -> String,
    private val onVerifyAccessCode: (String) -> Boolean,
    private val onEmergencyExit: () -> Unit,
    private val onSessionCompleteDismiss: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val isGhostMode: Boolean = config.overlayMode == OverlayMode.GHOST_PASSTHROUGH

    private val playlist = ArrayList(initialPlaylist)
    private var currentIdx = 0
    private var clipStartedElapsedMs = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var surfaceObj: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var centerCropMode = true
    private var isPausedByLookAway = false
    private var isCompleted = false
    private var released = false

    @Volatile
    var isAccessWaitActive: Boolean = false
        private set

    private var activeWaitView: AccessCodeWaitSpamView? = null
    private var portalHostView: FrameLayout? = null
    private var touchDownY = 0f
    private var emergTaps = 0
    private var emergLastTapMs = 0L

    // #7 Subliminal Image Pool + Scheduler
    private val subliminalPool = ArrayList<Bitmap>()
    private var lastSubliminalIdx = -1
    private val mantras = config.mantraList()

    // Window #1: Main Fullscreen Video + Subliminal Layer (touch-unlocked in Ghost mode, locked in Full-Lock!)
    val rootView: FrameLayout
    private val textureView: TextureView
    private lateinit var subliminalLayer: FrameLayout
    private lateinit var subliminalImageView: ImageView
    private val subliminalMantraTv: TextView
    private val warningBorder: WarningVignetteView
    private val flashBannerTv: TextView

    // Full-Lock HUD elements
    private val fullLockHudWrap: LinearLayout
    private val topPill: LinearLayout
    private val timerTv: TextView
    private val subInfoTv: TextView
    private val telemetryTv: TextView
    private val progressBar: ProgressBar
    private val awayOverlay: LinearLayout
    private val awayTitleTv: TextView
    private val awayReasonTv: TextView
    private val awayPenaltyTv: TextView
    private val bottomRow: LinearLayout

    // Ghost Mode passive look-away status strip (non-touchable, top-center)
    private val ghostAwayStripTv: TextView

    // Window #2: Compact Draggable Floating Control Pill (ONLY used in Ghost Pass-Through mode!)
    val ghostPillView: LinearLayout?
    private var ghostPillTimerTv: TextView? = null
    private var ghostPillDotTv: TextView? = null

    private val hideSubliminalRunnable = Runnable {
        subliminalLayer.visibility = View.GONE
        subliminalImageView.setImageDrawable(null)
    }

    private val subliminalLoopRunnable = object : Runnable {
        override fun run() {
            if (released || isCompleted) return
            if (config.subliminalEnabled && !isAccessWaitActive && !isPausedByLookAway) {
                triggerSingleSubliminalFlash()
            }
            scheduleNextSubliminalFlash()
        }
    }

    init {
        // ==================== WINDOW #1: FULLSCREEN VIDEO + SUBLIMINAL LAYER ====================
        rootView = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (!isGhostMode && !isCompleted && event != null &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
                ) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(if (isGhostMode) Color.TRANSPARENT else Color.BLACK)
            isClickable = !isGhostMode
            isFocusable = !isGhostMode
            isFocusableInTouchMode = !isGhostMode
        }

        // Layer 1: Hardware-Accelerated TextureView (supports flawless 45-50% alpha transparency over apps!)
        textureView = TextureView(context).apply {
            alpha = if (isGhostMode) (config.ghostOpacityPct.coerceIn(35, 65) / 100f) else 1.0f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surfaceObj = Surface(surface)
                    playCurrentVideo()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { applyVideoTransform() }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePlayerOnly(); surfaceObj?.release(); surfaceObj = null; return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        rootView.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (!isGhostMode) {
            textureView.setOnTouchListener { _, ev ->
                if (isCompleted || isAccessWaitActive) return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchDownY = ev.rawY; true }
                    MotionEvent.ACTION_UP -> {
                        val dy = ev.rawY - touchDownY
                        if (config.allowSwipeNext && dy < -dp(85) && !isPausedByLookAway) nextVideoInFeed()
                        true
                    }
                    else -> true
                }
            }
        }

        // Layer 2: #7 Subliminal Image Flash + Neon Mantra Overlay (0.08s–0.22s!)
        subliminalLayer = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = false; isFocusable = false
        }
        subliminalImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        subliminalMantraTv = TextView(context).apply {
            text = ""
            setTextColor(Color.rgb(255, 235, 255))
            textSize = 31f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setShadowLayer(18f, 0f, 0f, Color.rgb(225, 29, 72))
        }
        subliminalLayer.addView(subliminalImageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        subliminalLayer.addView(subliminalMantraTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        rootView.addView(subliminalLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 3: Pulsing Crimson Warning Vignette (works in BOTH modes!)
        warningBorder = WarningVignetteView(context).apply { visibility = View.GONE }
        rootView.addView(warningBorder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 4: Full-Lock "LOOK AT SCREEN!" Card (only visible in FULL_LOCK mode)
        awayOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(190, 20, 4, 12))
            setPadding(dp(24), dp(22), dp(24), dp(22))
            visibility = View.GONE
        }
        awayTitleTv = TextView(context).apply {
            text = "👁️ REGARDE L'ÉCRAN !"
            setTextColor(Color.rgb(254, 202, 202)); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        awayReasonTv = TextView(context).apply {
            text = "Chrono en pause tant que ton regard est détourné…"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(6))
        }
        awayPenaltyTv = TextView(context).apply {
            text = ""
            setTextColor(Color.rgb(253, 224, 71)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        awayOverlay.addView(awayTitleTv); awayOverlay.addView(awayReasonTv); awayOverlay.addView(awayPenaltyTv)
        rootView.addView(awayOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 5: Top HUD Wrapper (Full-Lock Pill + Shared Flash Banner + Ghost Passive Look-Away Strip)
        val topWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(36), dp(12), dp(6))
        }
        fullLockHudWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            visibility = if (isGhostMode) View.GONE else View.VISIBLE
        }
        topPill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = makePillBg(looking = true)
            setOnClickListener {
                val now = System.currentTimeMillis()
                emergTaps = if (now - emergLastTapMs <= 1600L) emergTaps + 1 else 1
                emergLastTapMs = now
                if (emergTaps >= GazeConfig.EMERGENCY_TAPS) {
                    emergTaps = 0
                    openUnlockPortal()
                }
            }
        }
        timerTv = TextView(context).apply {
            text = if (config.infiniteUntilUnlock) "👁️ ∞ (PORTAIL 3 MIN)" else "👁️ ${formatMmSs(config.durationSec * 1000L)}"
            setTextColor(Color.WHITE); textSize = 17.5f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            visibility = if (config.infiniteUntilUnlock) View.GONE else View.VISIBLE
        }
        subInfoTv = TextView(context).apply {
            text = "🎬 Fil vidéo verrouillé • ${config.penaltyMode.label}"
            setTextColor(Color.rgb(220, 240, 230)); textSize = 10.6f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
        }
        telemetryTv = TextView(context).apply {
            text = "📷 Initialisation IA Regard + Iris…"
            setTextColor(Color.rgb(200, 200, 225)); textSize = 9.7f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            visibility = if (config.showTelemetryBadge) View.VISIBLE else View.GONE
        }
        topPill.addView(timerTv)
        topPill.addView(progressBar, LinearLayout.LayoutParams(dp(225), dp(5)).apply { topMargin = dp(4) })
        topPill.addView(subInfoTv)
        topPill.addView(telemetryTv)
        fullLockHudWrap.addView(topPill)
        topWrap.addView(fullLockHudWrap)

        // Ghost Passive Look-Away Strip (non-touchable!)
        ghostAwayStripTv = TextView(context).apply {
            setTextColor(Color.rgb(254, 202, 202)); textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(210, 88, 10, 28))
                setStroke(dp(1), Color.rgb(244, 63, 94))
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        topWrap.addView(ghostAwayStripTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(44) })

        flashBannerTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(230, 190, 18, 60)) }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        topWrap.addView(flashBannerTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        rootView.addView(topWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 6: Bottom Aspect Toggle + Swipe Hint (FULL_LOCK mode only)
        bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(22))
            visibility = if (isGhostMode) View.GONE else View.VISIBLE
        }
        val swipeHintTv = TextView(context).apply {
            text = if (config.allowSwipeNext) "⬆️ Glisse vers le haut : vidéo suivante" else "🔒 Fil automatique verrouillé"
            setTextColor(Color.argb(185, 255, 255, 255)); textSize = 11f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        val aspectBtn = Button(context).apply {
            text = "⛶ Format"; isAllCaps = false; textSize = 10.6f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(145, 20, 16, 32)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { centerCropMode = !centerCropMode; applyVideoTransform() }
        }
        bottomRow.addView(swipeHintTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(aspectBtn)
        rootView.addView(bottomRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // ==================== WINDOW #2: COMPACT DRAGGABLE GHOST PILL (ONLY IN GHOST MODE) ====================
        ghostPillView = if (isGhostMode) buildGhostControlPill() else null

        // Preload #7 Subliminal Image Pool off UI thread & start loop!
        if (config.subliminalEnabled) {
            Thread {
                val loaded = VideoFeedRepository.loadSpamBitmapPool(context.applicationContext, config, maxPoolSize = 24)
                mainHandler.post {
                    if (released) {
                        for (b in loaded) if (!b.isRecycled) b.recycle()
                        return@post
                    }
                    subliminalPool.addAll(loaded)
                }
            }.start()
            scheduleNextSubliminalFlash()
        }
    }

    private fun buildGhostControlPill(): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(210, 18, 12, 32))
                setStroke(dp(1), Color.argb(215, 192, 38, 211))
            }
        }

        val dot = TextView(context).apply {
            text = "🟢"
            textSize = 10f
            setPadding(0, 0, dp(4), 0)
        }
        ghostPillDotTv = dot

        val tm = TextView(context).apply {
            text = if (config.infiniteUntilUnlock) "👻 ∞ · ${config.ghostOpacityPct}%" else "👻 ${formatMmSs(config.durationSec * 1000L)}"
            setTextColor(Color.WHITE); textSize = 11.4f; typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(6), 0)
        }
        ghostPillTimerTv = tm

        pill.addView(dot)
        pill.addView(tm)

        if (config.allowSwipeNext) {
            val nextBtn = TextView(context).apply {
                text = "⏭"
                setTextColor(Color.rgb(216, 180, 254)); textSize = 13f
                setPadding(dp(5), dp(2), dp(5), dp(2))
                setOnClickListener { if (!isAccessWaitActive && !isPausedByLookAway) nextVideoInFeed() }
            }
            pill.addView(nextBtn)
        }

        // ONLY way to stop the Ghost overlay early: launches the 3-minute imposed overlay portal!
        val unlockBtn = TextView(context).apply {
            text = "🔓 3m"
            setTextColor(Color.WHITE); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setOnClickListener { openUnlockPortal() }
        }
        pill.addView(unlockBtn)

        // Smooth drag-to-move on the timer/handle area so it never blocks an app button underneath!
        var lastRawX = 0f; var lastRawY = 0f; var dragging = false
        val dragListener = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastRawX = ev.rawX; lastRawY = ev.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - lastRawX).toInt()
                    val dy = (ev.rawY - lastRawY).toInt()
                    if (dragging || abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        dragging = true
                        onDragGhostPill(dx, dy)
                        lastRawX = ev.rawX; lastRawY = ev.rawY
                    }
                    true
                }
                else -> false
            }
        }
        dot.setOnTouchListener(dragListener)
        tm.setOnTouchListener(dragListener)

        return pill
    }

    // ==================== #7 SUBLIMINAL IMAGE FLASHES + NEON MANTRAS ====================
    private fun scheduleNextSubliminalFlash() {
        if (released || isCompleted || !config.subliminalEnabled) return
        val baseMs = config.subliminalIntervalSec.coerceIn(2, 20) * 1000L
        val jitter = Random.nextLong(-(baseMs / 4L), (baseMs / 4L) + 1L)
        mainHandler.postDelayed(subliminalLoopRunnable, (baseMs + jitter).coerceAtLeast(1_500L))
    }

    private fun triggerSingleSubliminalFlash() {
        if (released || isCompleted || isAccessWaitActive) return
        val alphaFactor = config.subliminalAlphaPct.coerceIn(30, 95) / 100f
        // Keep subliminal image clearly perceptible in Ghost mode too!
        subliminalLayer.alpha = if (isGhostMode) (alphaFactor * 0.85f).coerceIn(0.30f, 0.82f) else alphaFactor

        val validImgs = subliminalPool.filter { !it.isRecycled }
        if (validImgs.isNotEmpty()) {
            var idx = Random.nextInt(validImgs.size)
            if (validImgs.size > 1 && idx == lastSubliminalIdx) idx = (idx + 1) % validImgs.size
            lastSubliminalIdx = idx
            subliminalImageView.setImageBitmap(validImgs[idx])
            subliminalImageView.visibility = View.VISIBLE
        } else {
            subliminalImageView.visibility = View.GONE
        }

        val mantra = mantras[Random.nextInt(mantras.size)]
        subliminalMantraTv.text = mantra
        subliminalMantraTv.rotation = Random.nextInt(-5, 6).toFloat()
        subliminalLayer.visibility = View.VISIBLE

        mainHandler.removeCallbacks(hideSubliminalRunnable)
        mainHandler.postDelayed(hideSubliminalRunnable, config.subliminalDurationMs.coerceIn(80, 220).toLong())
    }

    private fun makePillBg(looking: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        if (looking) {
            setColor(Color.argb(195, 10, 26, 22)); setStroke(dp(1), Color.argb(200, 16, 185, 129))
        } else {
            setColor(Color.argb(225, 68, 10, 24)); setStroke(dp(2), Color.rgb(244, 63, 94))
        }
    }

    private fun playCurrentVideo() {
        if (released || isCompleted || playlist.isEmpty()) return
        val surf = surfaceObj ?: return
        releasePlayerOnly()
        val item = playlist[currentIdx % playlist.size]
        clipStartedElapsedMs = SystemClock.elapsedRealtime()
        subInfoTv.text = "🎬 ${(currentIdx % playlist.size) + 1}/${playlist.size} · ${item.title.take(26)}"

        try {
            val mp = MediaPlayer().apply {
                setSurface(surf)
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
                )
                if (item.filePath != null) setDataSource(item.filePath)
                else if (item.contentUri != null) setDataSource(context, item.contentUri)
                setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; mainHandler.post { applyVideoTransform() } }
                setOnPreparedListener { player ->
                    if (released || isCompleted) { try { player.release() } catch (_: Throwable) {}; return@setOnPreparedListener }
                    if (!isPausedByLookAway && !isAccessWaitActive) { try { player.start() } catch (_: Throwable) {} }
                }
                setOnCompletionListener { mainHandler.post { nextVideoInFeed() } }
                setOnErrorListener { _, _, _ -> mainHandler.postDelayed({ nextVideoInFeed() }, 250L); true }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (_: Throwable) {
            mainHandler.postDelayed({ nextVideoInFeed() }, 300L)
        }
    }

    fun nextVideoInFeed() {
        if (released || isCompleted || playlist.isEmpty()) return
        currentIdx++
        if (currentIdx >= playlist.size) {
            currentIdx = 0
            if (config.shuffleVideos && playlist.size > 1) playlist.shuffle()
        }
        playCurrentVideo()
    }

    fun checkMaxClipSwitch() {
        if (released || isCompleted || isPausedByLookAway || isAccessWaitActive || config.maxClipSec <= 0) return
        if (clipStartedElapsedMs > 0L && SystemClock.elapsedRealtime() - clipStartedElapsedMs >= config.maxClipSec * 1000L) {
            nextVideoInFeed()
        }
    }

    private fun applyVideoTransform() {
        val vw = textureView.width.toFloat(); val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val videoRatio = videoW.toFloat() / videoH.toFloat()
        val viewRatio = vw / vh
        var sx = 1f; var sy = 1f
        if (centerCropMode) {
            if (videoRatio > viewRatio) sx = videoRatio / viewRatio else sy = viewRatio / videoRatio
        } else {
            if (videoRatio > viewRatio) sy = viewRatio / videoRatio else sx = videoRatio / viewRatio
        }
        val m = Matrix(); m.setScale(sx, sy, vw * 0.5f, vh * 0.5f)
        textureView.setTransform(m)
    }

    fun updateHud(
        remainingMs: Long,
        totalDurationMs: Long,
        gaze: GazeReading,
        lookAwayDurationMs: Long,
        penaltyAppliedCount: Int,
        nextPenaltySec: Int,
        penaltyMode: PenaltyMode,
        flashBanner: String
    ) {
        if (released || isCompleted) return

        if (flashBanner.isNotBlank()) {
            flashBannerTv.text = flashBanner
            flashBannerTv.visibility = View.VISIBLE
        } else {
            flashBannerTv.visibility = View.GONE
        }

        val looking = gaze.isLookingAtScreen
        val timeStr = if (config.infiniteUntilUnlock) "∞" else formatMmSs(remainingMs)

        // Update Ghost Mode compact floating pill
        if (isGhostMode) {
            ghostPillDotTv?.text = if (looking) "🟢" else "🔴"
            ghostPillTimerTv?.text = if (config.infiniteUntilUnlock) {
                "👻 ∞ · ${config.ghostOpacityPct}%"
            } else {
                "👻 $timeStr"
            }
            if (looking) {
                warningBorder.visibility = View.GONE
                ghostAwayStripTv.visibility = View.GONE
                if (isPausedByLookAway) {
                    isPausedByLookAway = false
                    if (!isAccessWaitActive) { try { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Throwable) {} }
                }
            } else {
                warningBorder.visibility = View.VISIBLE
                warningBorder.invalidate()
                ghostAwayStripTv.text = "👁️ ${gaze.reason} — Chrono figé !"
                ghostAwayStripTv.visibility = View.VISIBLE
                if (config.pauseVideoOnLookAway && !isPausedByLookAway) {
                    isPausedByLookAway = true
                    try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Throwable) {}
                }
            }
            return
        }

        // Full-Lock HUD update
        if (!config.infiniteUntilUnlock) {
            val pct = (((totalDurationMs - remainingMs).coerceAtLeast(0L) * 1000L) / totalDurationMs.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
            progressBar.progress = pct
        }

        if (looking) {
            timerTv.text = if (config.infiniteUntilUnlock) "👁️ ∞ · PORTAIL 3 MIN" else "👁️ $timeStr"
            topPill.background = makePillBg(looking = true)
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            warningBorder.visibility = View.GONE
            awayOverlay.visibility = View.GONE
            if (isPausedByLookAway) {
                isPausedByLookAway = false
                if (!isAccessWaitActive) { try { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Throwable) {} }
            }
        } else {
            timerTv.text = "⏸️ $timeStr · PAUSE"
            topPill.background = makePillBg(looking = false)
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            warningBorder.visibility = View.VISIBLE
            warningBorder.invalidate()
            awayOverlay.visibility = View.VISIBLE
            awayReasonTv.text = "${gaze.reason}\nLe chronomètre est FIGÉ tant que tu ne regardes pas !"

            val trigMs = if (penaltyMode == PenaltyMode.SUDDEN_DEATH) 2_200L else 2_500L
            awayPenaltyTv.text = when {
                config.infiniteUntilUnlock -> "Mode Infini actif — Regarde l'écran !"
                penaltyMode == PenaltyMode.SUDDEN_DEATH && lookAwayDurationMs < trigMs ->
                    String.format("💀 MORT SUBITE : RESET CHRONO dans %.1fs !", (trigMs - lookAwayDurationMs) / 1000f)
                penaltyMode == PenaltyMode.SUDDEN_DEATH ->
                    "💀 CHRONOMÈTRE REMIS À ZÉRO ! (Écarts : $penaltyAppliedCount)"
                nextPenaltySec <= 0 ->
                    "Écarts détectés : $penaltyAppliedCount"
                lookAwayDurationMs < trigMs -> {
                    val pLabel = if (penaltyMode == PenaltyMode.PROGRESSIVE) "📈 Pénalité Progressive" else "⚠️ Pénalité"
                    String.format("$pLabel +%ds dans %.1fs !", nextPenaltySec, (trigMs - lookAwayDurationMs) / 1000f)
                }
                else -> "🚨 Pénalité appliquée ! (Total écarts : $penaltyAppliedCount)"
            }
            if (config.pauseVideoOnLookAway && !isPausedByLookAway) {
                isPausedByLookAway = true
                try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Throwable) {}
            }
        }

        if (config.showTelemetryBadge) {
            telemetryTv.text = if (gaze.faceDetected) {
                val irisStr = if (gaze.irisMeasured) " · 👁️Iris ${gaze.irisOffsetPct}%" else ""
                "IA · Yaw ${gaze.yawDeg.toInt()}° · Pitch ${gaze.pitchDeg.toInt()}°$irisStr · Yeux ${(gaze.eyeOpenAvg * 100).toInt()}%"
            } else {
                "IA Regard · Aucun visage devant la caméra frontale"
            }
        }
    }

    // ==================== WINDOW #3: INTERACTIVE PORTAL OVERLAY (3-MIN TRIAL & COMPLETION) ====================
    fun openUnlockPortal() {
        if (released || isCompleted) return
        if (portalHostView != null) return // Already open!

        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event != null &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
                ) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.argb(232, 8, 6, 18))
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(20, 16, 34))
                setStroke(dp(2), Color.rgb(225, 29, 72))
            }
        }
        card.addView(TextView(context).apply {
            text = "🔑 Portail de Déblocage (Overlay)"
            setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        val extraDesc = buildString {
            append("Impossible de retirer les vidéos directement. Tu dois accomplir le créneau de 3 minutes imposées en overlay : spam d'images (0,1 s)")
            if (config.unlockPopupsEnabled) append(" + pop-ups interactifs")
            if (config.unlockMinigamesEnabled) append(" + mini-jeux (Puzzle à glissement & Reconstitution d'image)")
            append(" !")
        }
        card.addView(TextView(context).apply {
            text = extraDesc
            setTextColor(Color.rgb(212, 206, 232)); textSize = 11.8f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(12))
        })

        // Launch 3-Minute Overlay Portal immediately!
        card.addView(Button(context).apply {
            text = "⏳ LANCER LE CRÉNEAU 3 MIN (Overlay + Mini-Jeux)"
            isAllCaps = false; textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(162, 28, 175)) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { startThreeMinuteTrialInsidePortal(host) }
        })

        val errTv = TextView(context).apply {
            setTextColor(Color.rgb(252, 165, 165)); textSize = 11.5f; gravity = Gravity.CENTER; visibility = View.GONE
        }
        val input = EditText(context).apply {
            hint = "Ou saisis un code obtenu (< 2 min)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140, 140, 162)); textSize = 12.5f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(34, 28, 52)) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(input); card.addView(errTv)

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(Button(context).apply {
            text = "↩ Retour vidéos"; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(52, 46, 70)) }
            setOnClickListener { closePortalOverlay() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        btnRow.addView(Button(context).apply {
            text = "Valider code"; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setOnClickListener {
                if (onVerifyAccessCode(input.text?.toString().orEmpty())) {
                    closePortalOverlay()
                    onEmergencyExit()
                } else {
                    errTv.text = "Code invalide ou expiré — lance le créneau de 3 min !"
                    errTv.visibility = View.VISIBLE
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        card.addView(btnRow)

        host.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(20); marginEnd = dp(20)
            }
        )

        if (onMountPortalOverlay(host)) {
            portalHostView = host
        }
    }

    private fun startThreeMinuteTrialInsidePortal(host: FrameLayout) {
        isAccessWaitActive = true
        mainHandler.removeCallbacks(hideSubliminalRunnable)
        subliminalLayer.visibility = View.GONE
        try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Throwable) {}
        host.removeAllViews()

        val waitView = AccessCodeWaitSpamView(
            context = context,
            cfg = config,
            onIssueCode = onIssueAccessCodeAfterWait,
            onCodeUnlockedAndExit = { issuedCode ->
                isAccessWaitActive = false
                if (onVerifyAccessCode(issuedCode)) {
                    closePortalOverlay()
                    onEmergencyExit()
                }
            },
            onCancelWait = {
                closePortalOverlay()
                if (!isPausedByLookAway && !isCompleted) {
                    try { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Throwable) {}
                }
            }
        )
        activeWaitView = waitView
        host.addView(waitView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        waitView.startThreeMinuteSpamWait()
    }

    private fun closePortalOverlay() {
        isAccessWaitActive = false
        activeWaitView?.stopAndRelease()
        activeWaitView = null
        if (portalHostView != null) {
            portalHostView = null
            onRemovePortalOverlay()
        }
    }

    fun showCompleted(focusedSec: Int, lookAways: Int, bestStreakSec: Int) {
        isCompleted = true
        mainHandler.removeCallbacks(subliminalLoopRunnable)
        mainHandler.removeCallbacks(hideSubliminalRunnable)
        subliminalLayer.visibility = View.GONE
        closePortalOverlay()
        releasePlayerOnly()
        warningBorder.visibility = View.GONE
        awayOverlay.visibility = View.GONE
        ghostAwayStripTv.visibility = View.GONE

        // Mount interactive completion overlay on Window #3 so even in Ghost mode the button is 100% touchable!
        val completeBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 10, 8, 22))
            setPadding(dp(26), dp(28), dp(26), dp(28))
            isClickable = true; isFocusable = true
        }
        completeBox.addView(TextView(context).apply {
            text = "✨ SESSION ACCOMPLIE !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 23f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        completeBox.addView(TextView(context).apply {
            text = "⏱️ Temps fixé avec attention : ${formatMmSs(focusedSec * 1000L)}\n" +
                "👀 Détournements du regard : $lookAways\n" +
                "🔥 Plus longue série sans détourner les yeux : ${bestStreakSec}s"
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(22))
        })
        completeBox.addView(Button(context).apply {
            text = "✅ QUITTER LE FIL VIDÉO"
            isAllCaps = false; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { onSessionCompleteDismiss() }
        })

        val host = FrameLayout(context).apply {
            addView(completeBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (onMountPortalOverlay(host)) {
            portalHostView = host
        } else {
            onSessionCompleteDismiss()
        }
    }

    private fun releasePlayerOnly() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(subliminalLoopRunnable)
        mainHandler.removeCallbacks(hideSubliminalRunnable)
        closePortalOverlay()
        releasePlayerOnly()
        subliminalImageView.setImageDrawable(null)
        for (b in subliminalPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        subliminalPool.clear()
        try { surfaceObj?.release() } catch (_: Throwable) {}
        surfaceObj = null
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = ((ms + 999L) / 1000L).coerceAtLeast(0L)
        return String.format("%02d:%02d", totalSec / 60L, totalSec % 60L)
    }

    private class WarningVignetteView(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(210, 244, 63, 94)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pulse = 0.55f + 0.45f * kotlin.math.sin(SystemClock.elapsedRealtime() / 160.0).toFloat()
            strokePaint.strokeWidth = width * 0.042f * (0.85f + 0.3f * pulse)
            strokePaint.alpha = (140 + 115 * pulse).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), strokePaint)
        }
    }
}
