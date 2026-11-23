package com.gazelock.app.overlay

import android.content.Context
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import com.gazelock.app.ai.GazeTrackerEngine.GazeReading
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository.VideoItem
import kotlin.math.abs

/**
 * Unskippable Fullscreen Video Feed Overlay:
 * - Seamless continuous playlist of videos from the user's chosen folder (hidden .* supported!).
 * - Hardware-accelerated TextureView + MediaPlayer with proper CENTER_CROP / FIT_CENTER toggle.
 * - Optional swipe-up to advance to next video in the feed while still impossible to exit.
 * - Real-time Gaze HUD: emerald countdown when eyes are locked on screen, pulsing crimson border &
 *   frozen timer when looking away!
 * - Built-in safe emergency exit: 20 rapid taps on top timer pill + PIN code (default 100205).
 */
class GazeVideoFeedOverlay(
    private val context: Context,
    private val config: GazeConfigSnapshot,
    initialPlaylist: List<VideoItem>,
    private val onVerifyEmergencyPin: (String) -> Boolean,
    private val onEmergencyExit: () -> Unit,
    private val onSessionCompleteDismiss: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

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

    // Swipe-up gesture tracking
    private var touchDownY = 0f
    private var emergTaps = 0
    private var emergLastTapMs = 0L

    // UI references
    val rootView: FrameLayout
    private val textureView: TextureView
    private val warningBorder: WarningVignetteView
    private val topPill: LinearLayout
    private val timerTv: TextView
    private val subInfoTv: TextView
    private val telemetryTv: TextView
    private val progressBar: ProgressBar
    private val awayOverlay: LinearLayout
    private val awayTitleTv: TextView
    private val awayReasonTv: TextView
    private val awayPenaltyTv: TextView
    private val pinModalHost: FrameLayout
    private val completeOverlay: LinearLayout
    private val completeStatsTv: TextView
    private val swipeHintTv: TextView

    init {
        rootView = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                // Swallow Back/Home/Menu keys cleanly while session is running!
                if (!isCompleted && event != null &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
                ) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Layer 1: Fullscreen Hardware-Decoded Video Feed TextureView
        textureView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surfaceObj = Surface(surface)
                    playCurrentVideo()
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    applyVideoTransform()
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePlayerOnly()
                    surfaceObj?.release()
                    surfaceObj = null
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        rootView.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Swipe-up detector on video surface (only changes video in feed if enabled; NEVER exits!)
        textureView.setOnTouchListener { _, ev ->
            if (isCompleted) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchDownY = ev.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.rawY - touchDownY
                    if (config.allowSwipeNext && dy < -dp(85) && !isPausedByLookAway) {
                        nextVideoInFeed()
                    }
                    true
                }
                else -> true
            }
        }

        // Layer 2: Pulsing Red Warning Border when user looks away!
        warningBorder = WarningVignetteView(context).apply { visibility = View.GONE }
        rootView.addView(warningBorder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 3: Center "LOOK AT SCREEN!" Alert Card (visible ONLY when looking away!)
        awayOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(185, 20, 4, 12))
            setPadding(dp(24), dp(22), dp(24), dp(22))
            visibility = View.GONE
        }
        awayTitleTv = TextView(context).apply {
            text = "👁️ REGARDE L'ÉCRAN !"
            setTextColor(Color.rgb(254, 202, 202)); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        awayReasonTv = TextView(context).apply {
            text = "Chrono en pause tant que ton regard est détourné…"
            setTextColor(Color.WHITE); textSize = 14.2f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(6))
        }
        awayPenaltyTv = TextView(context).apply {
            text = ""
            setTextColor(Color.rgb(253, 224, 71)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        awayOverlay.addView(awayTitleTv); awayOverlay.addView(awayReasonTv); awayOverlay.addView(awayPenaltyTv)
        rootView.addView(awayOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 4: Sleek Top Gaze Countdown HUD Pill (20 rapid taps = emergency PIN exit!)
        val topWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(36), dp(12), dp(6))
        }
        topPill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = makePillBg(looking = true)
            setOnClickListener {
                val now = System.currentTimeMillis()
                emergTaps = if (now - emergLastTapMs <= 1600L) emergTaps + 1 else 1
                emergLastTapMs = now
                if (emergTaps >= GazeConfig.EMERGENCY_TAPS) {
                    emergTaps = 0
                    showEmergencyPinOverlay()
                }
            }
        }
        timerTv = TextView(context).apply {
            text = "👁️ ${formatMmSs(config.durationSec * 1000L)}"
            setTextColor(Color.WHITE); textSize = 17.5f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subInfoTv = TextView(context).apply {
            text = "🎬 Fil vidéo verrouillé • Regarde pour faire tourner le chrono"
            setTextColor(Color.rgb(220, 240, 230)); textSize = 10.6f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
        }
        telemetryTv = TextView(context).apply {
            text = "📷 Initialisation IA du regard…"
            setTextColor(Color.rgb(200, 200, 225)); textSize = 9.8f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            visibility = if (config.showTelemetryBadge) View.VISIBLE else View.GONE
        }
        topPill.addView(timerTv)
        topPill.addView(progressBar, LinearLayout.LayoutParams(dp(220), dp(5)).apply { topMargin = dp(4) })
        topPill.addView(subInfoTv)
        topPill.addView(telemetryTv)
        topWrap.addView(topPill)
        rootView.addView(topWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Tiny Bottom Corner Aspect-Ratio Toggle (Crop/Fit) + Swipe Feed Hint
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(22))
        }
        swipeHintTv = TextView(context).apply {
            text = if (config.allowSwipeNext) "⬆️ Glisse vers le haut : vidéo suivante" else "🔒 Fil automatique verrouillé"
            setTextColor(Color.argb(185, 255, 255, 255)); textSize = 11f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        val aspectBtn = Button(context).apply {
            text = "⛶ Format"; isAllCaps = false; textSize = 10.6f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(145, 20, 16, 32)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                centerCropMode = !centerCropMode
                applyVideoTransform()
            }
        }
        bottomRow.addView(swipeHintTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(aspectBtn)
        rootView.addView(bottomRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Layer 5: Completion Screen Overlay (shown when timer hits 00:00!)
        completeOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 10, 8, 22))
            setPadding(dp(26), dp(28), dp(26), dp(28))
            visibility = View.GONE
        }
        completeOverlay.addView(TextView(context).apply {
            text = "✨ SESSION ACCOMPLIE !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 23f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        completeStatsTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(22))
        }
        completeOverlay.addView(completeStatsTv)
        completeOverlay.addView(Button(context).apply {
            text = "✅ QUITTER LE FIL VIDÉO"
            isAllCaps = false; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setOnClickListener { onSessionCompleteDismiss() }
        })
        rootView.addView(completeOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 6: Emergency PIN Modal Host (20 rapid taps on timer pill!)
        pinModalHost = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(215, 8, 6, 16))
            visibility = View.GONE
            isClickable = true
        }
        rootView.addView(pinModalHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun makePillBg(looking: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        if (looking) {
            setColor(Color.argb(195, 10, 26, 22))
            setStroke(dp(1), Color.argb(200, 16, 185, 129))
        } else {
            setColor(Color.argb(225, 68, 10, 24))
            setStroke(dp(2), Color.rgb(244, 63, 94))
        }
    }

    private fun playCurrentVideo() {
        if (released || isCompleted || playlist.isEmpty()) return
        val surf = surfaceObj ?: return
        releasePlayerOnly()

        val item = playlist[currentIdx % playlist.size]
        clipStartedElapsedMs = SystemClock.elapsedRealtime()
        subInfoTv.text = "🎬 ${(currentIdx % playlist.size) + 1}/${playlist.size} · ${item.title.take(28)}"

        try {
            val mp = MediaPlayer().apply {
                setSurface(surf)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                if (item.filePath != null) setDataSource(item.filePath)
                else if (item.contentUri != null) setDataSource(context, item.contentUri)

                setOnVideoSizeChangedListener { _, w, h ->
                    videoW = w; videoH = h
                    mainHandler.post { applyVideoTransform() }
                }
                setOnPreparedListener { player ->
                    if (released || isCompleted) {
                        try { player.release() } catch (_: Throwable) {}
                        return@setOnPreparedListener
                    }
                    if (!isPausedByLookAway) {
                        try { player.start() } catch (_: Throwable) {}
                    }
                }
                setOnCompletionListener {
                    mainHandler.post { nextVideoInFeed() }
                }
                setOnErrorListener { _, _, _ ->
                    mainHandler.postDelayed({ nextVideoInFeed() }, 250L)
                    true
                }
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
        if (released || isCompleted || isPausedByLookAway || config.maxClipSec <= 0) return
        if (clipStartedElapsedMs > 0L && SystemClock.elapsedRealtime() - clipStartedElapsedMs >= config.maxClipSec * 1000L) {
            nextVideoInFeed()
        }
    }

    private fun applyVideoTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return

        val videoRatio = videoW.toFloat() / videoH.toFloat()
        val viewRatio = vw / vh
        var sx = 1f
        var sy = 1f
        if (centerCropMode) {
            if (videoRatio > viewRatio) sx = videoRatio / viewRatio else sy = viewRatio / videoRatio
        } else {
            if (videoRatio > viewRatio) sy = viewRatio / videoRatio else sx = videoRatio / viewRatio
        }
        val m = Matrix()
        m.setScale(sx, sy, vw * 0.5f, vh * 0.5f)
        textureView.setTransform(m)
    }

    fun updateHud(
        remainingMs: Long,
        totalDurationMs: Long,
        gaze: GazeReading,
        lookAwayDurationMs: Long,
        penaltyAppliedCount: Int
    ) {
        if (released || isCompleted) return
        val looking = gaze.isLookingAtScreen
        val timeStr = formatMmSs(remainingMs)
        val pct = (((totalDurationMs - remainingMs).coerceAtLeast(0L) * 1000L) / totalDurationMs.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
        progressBar.progress = pct

        if (looking) {
            timerTv.text = "👁️ $timeStr"
            topPill.background = makePillBg(looking = true)
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            warningBorder.visibility = View.GONE
            awayOverlay.visibility = View.GONE
            if (isPausedByLookAway) {
                isPausedByLookAway = false
                try { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Throwable) {}
            }
        } else {
            timerTv.text = "⏸️ $timeStr · PAUSE"
            topPill.background = makePillBg(looking = false)
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            warningBorder.visibility = View.VISIBLE
            warningBorder.invalidate()
            awayOverlay.visibility = View.VISIBLE
            awayReasonTv.text = "${gaze.reason}\nLe chronomètre est FIGÉ tant que tu ne regardes pas !"
            awayPenaltyTv.text = when {
                config.lookAwayPenaltySec <= 0 -> "Écarts détectés : $penaltyAppliedCount"
                lookAwayDurationMs < 2_500L -> String.format("⚠️ Pénalité +%ds dans %.1fs !", config.lookAwayPenaltySec, (2_500L - lookAwayDurationMs) / 1000f)
                else -> "🚨 Pénalité +${config.lookAwayPenaltySec}s appliquée ! (Total écarts : $penaltyAppliedCount)"
            }
            if (config.pauseVideoOnLookAway && !isPausedByLookAway) {
                isPausedByLookAway = true
                try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Throwable) {}
            }
        }

        if (config.showTelemetryBadge) {
            telemetryTv.text = if (gaze.faceDetected) {
                "IA Regard · Yaw ${gaze.yawDeg.toInt()}° · Pitch ${gaze.pitchDeg.toInt()}° · Yeux ${(gaze.eyeOpenAvg * 100).toInt()} %"
            } else {
                "IA Regard · Aucun visage détecté devant la caméra frontale"
            }
        }
    }

    fun showCompleted(focusedSec: Int, lookAways: Int, bestStreakSec: Int) {
        isCompleted = true
        releasePlayerOnly()
        warningBorder.visibility = View.GONE
        awayOverlay.visibility = View.GONE
        pinModalHost.visibility = View.GONE
        completeStatsTv.text = "⏱️ Temps regardé avec attention : ${formatMmSs(focusedSec * 1000L)}\n" +
            "👀 Détournements du regard : $lookAways\n" +
            "🔥 Plus longue série sans détourner les yeux : ${bestStreakSec}s"
        completeOverlay.visibility = View.VISIBLE
    }

    private fun showEmergencyPinOverlay() {
        pinModalHost.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(20, 16, 32))
                setStroke(dp(2), Color.rgb(225, 29, 72))
            }
        }
        card.addView(TextView(context).apply {
            text = "🔓 Sortie d'Urgence GazeLock AI"
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        card.addView(TextView(context).apply {
            text = "Saisis le code secret (par défaut 100205) pour arrêter immédiatement le fil vidéo :"
            setTextColor(Color.rgb(200, 200, 220)); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
        })
        val errTv = TextView(context).apply {
            setTextColor(Color.rgb(252, 165, 165)); textSize = 11.5f; gravity = Gravity.CENTER; visibility = View.GONE
        }
        val input = EditText(context).apply {
            hint = "Code PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140, 140, 160))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(34, 28, 52)) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(input)
        card.addView(errTv)

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(Button(context).apply {
            text = "Annuler"; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(52, 46, 70)) }
            setOnClickListener { pinModalHost.visibility = View.GONE }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        btnRow.addView(Button(context).apply {
            text = "Déverrouiller"; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setOnClickListener {
                if (onVerifyEmergencyPin(input.text?.toString().orEmpty())) {
                    pinModalHost.visibility = View.GONE
                    onEmergencyExit()
                } else {
                    errTv.text = "Mauvais code PIN !"
                    errTv.visibility = View.VISIBLE
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        card.addView(btnRow)

        pinModalHost.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(26); marginEnd = dp(26)
            }
        )
        pinModalHost.visibility = View.VISIBLE
    }

    private fun releasePlayerOnly() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    fun release() {
        released = true
        releasePlayerOnly()
        try { surfaceObj?.release() } catch (_: Throwable) {}
        surfaceObj = null
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = ((ms + 999L) / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return String.format("%02d:%02d", m, s)
    }

    private class WarningVignetteView(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(210, 244, 63, 94)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pulse = 0.55f + 0.45f * kotlin.math.sin(SystemClock.elapsedRealtime() / 160.0).toFloat()
            strokePaint.strokeWidth = width * 0.045f * (0.85f + 0.3f * pulse)
            strokePaint.alpha = (140 + 115 * pulse).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), strokePaint)
        }
    }
}
