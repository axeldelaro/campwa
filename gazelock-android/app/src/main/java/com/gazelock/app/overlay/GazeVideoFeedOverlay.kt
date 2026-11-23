package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
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
import com.gazelock.app.ai.GazeTrackerEngine.GazeReading
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.media.VideoFeedRepository.VideoItem

/**
 * Unskippable Fullscreen Video Feed Overlay (v2.0):
 * - Seamless continuous playlist of videos from the user's chosen folder (hidden .* supported!).
 * - Real-Time Iris + Head Pose Telemetry HUD + Progressive / Sudden Death Penalty alerts + #13 Escape Block Flash!
 * - Built-in 3-Minute Imposed Wait + 0.1s Folder Image-Spam screen to obtain a single-use 6-digit exit code!
 */
class GazeVideoFeedOverlay(
    private val context: Context,
    private val config: GazeConfigSnapshot,
    initialPlaylist: List<VideoItem>,
    private val onIssueAccessCodeAfterWait: () -> String,
    private val onVerifyAccessCode: (String) -> Boolean,
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

    @Volatile
    var isAccessWaitActive: Boolean = false
        private set

    private var activeWaitView: AccessCodeWaitSpamView? = null
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
    private val flashBannerTv: TextView
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
                if (!isCompleted && event != null &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
                ) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
        }

        // Layer 1: Fullscreen Hardware-Decoded Video Feed TextureView
        textureView = TextureView(context).apply {
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

        // Layer 2: Pulsing Red Warning Border
        warningBorder = WarningVignetteView(context).apply { visibility = View.GONE }
        rootView.addView(warningBorder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 3: Center "LOOK AT SCREEN!" Alert Card
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

        // Layer 4: Top Gaze Countdown HUD Pill (20 rapid taps -> Emergency Access Code / 3-Min Wait!)
        val topWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(36), dp(12), dp(6))
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
                    showEmergencyAccessModal()
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
        topWrap.addView(topPill)

        flashBannerTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(225, 190, 18, 60)) }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        topWrap.addView(flashBannerTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        rootView.addView(topWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom Aspect Toggle + Swipe Feed Hint
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
            setOnClickListener { centerCropMode = !centerCropMode; applyVideoTransform() }
        }
        bottomRow.addView(swipeHintTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(aspectBtn)
        rootView.addView(bottomRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Layer 5: Completion Screen Overlay
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

        // Layer 6: Emergency Access Code & 3-Min 0.1s Image-Spam Host
        pinModalHost = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(220, 8, 6, 16))
            visibility = View.GONE; isClickable = true
        }
        rootView.addView(pinModalHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

    fun showCompleted(focusedSec: Int, lookAways: Int, bestStreakSec: Int) {
        isCompleted = true
        stopWaitViewIfAny()
        releasePlayerOnly()
        warningBorder.visibility = View.GONE
        awayOverlay.visibility = View.GONE
        pinModalHost.visibility = View.GONE
        completeStatsTv.text = "⏱️ Temps regardé avec attention : ${formatMmSs(focusedSec * 1000L)}\n" +
            "👀 Détournements du regard : $lookAways\n" +
            "🔥 Plus longue série sans détourner les yeux : ${bestStreakSec}s"
        completeOverlay.visibility = View.VISIBLE
    }

    private fun showEmergencyAccessModal() {
        stopWaitViewIfAny()
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
            text = "🔑 Code d'Accès GazeLock AI"
            setTextColor(Color.WHITE); textSize = 16.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        card.addView(TextView(context).apply {
            text = "Pour obtenir un code d'accès à usage unique, tu dois subir 3 minutes d'attente imposées avec spam d'images (0,1 s) de ton dossier :"
            setTextColor(Color.rgb(208, 204, 228)); textSize = 11.8f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
        })

        // Primary 3-Minute 0.1s Image-Spam Trigger!
        card.addView(Button(context).apply {
            text = "⏳ OBTENIR LE CODE (3 min + Spam images 0,1 s)"
            isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(162, 28, 175)) }
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { launchThreeMinuteSpamWaitInOverlay() }
        })

        val errTv = TextView(context).apply {
            setTextColor(Color.rgb(252, 165, 165)); textSize = 11.5f; gravity = Gravity.CENTER; visibility = View.GONE
        }
        val input = EditText(context).apply {
            hint = "Ou saisis un code déjà obtenu (6 chiffres)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140, 140, 162)); textSize = 12.5f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(34, 28, 52)) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(input); card.addView(errTv)

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(Button(context).apply {
            text = "Retour vidéo"; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(52, 46, 70)) }
            setOnClickListener { pinModalHost.visibility = View.GONE }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        btnRow.addView(Button(context).apply {
            text = "Valider code"; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setOnClickListener {
                if (onVerifyAccessCode(input.text?.toString().orEmpty())) {
                    pinModalHost.visibility = View.GONE
                    onEmergencyExit()
                } else {
                    errTv.text = "Code invalide ou expiré — lance l'attente de 3 min !"
                    errTv.visibility = View.VISIBLE
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        card.addView(btnRow)

        pinModalHost.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(22); marginEnd = dp(22)
            }
        )
        pinModalHost.visibility = View.VISIBLE
    }

    private fun launchThreeMinuteSpamWaitInOverlay() {
        isAccessWaitActive = true
        try { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() } catch (_: Throwable) {}
        pinModalHost.removeAllViews()

        val waitView = AccessCodeWaitSpamView(
            context = context,
            cfg = config,
            onIssueCode = onIssueAccessCodeAfterWait,
            onCodeUnlockedAndExit = { issuedCode ->
                isAccessWaitActive = false
                if (onVerifyAccessCode(issuedCode)) {
                    pinModalHost.visibility = View.GONE
                    onEmergencyExit()
                }
            },
            onCancelWait = {
                isAccessWaitActive = false
                activeWaitView = null
                pinModalHost.removeAllViews()
                pinModalHost.visibility = View.GONE
                if (!isPausedByLookAway && !isCompleted) {
                    try { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Throwable) {}
                }
            }
        )
        activeWaitView = waitView
        pinModalHost.addView(waitView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pinModalHost.visibility = View.VISIBLE
        waitView.startThreeMinuteSpamWait()
    }

    private fun stopWaitViewIfAny() {
        isAccessWaitActive = false
        activeWaitView?.stopAndRelease()
        activeWaitView = null
    }

    private fun releasePlayerOnly() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    fun release() {
        released = true
        stopWaitViewIfAny()
        releasePlayerOnly()
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
            strokePaint.strokeWidth = width * 0.045f * (0.85f + 0.3f * pulse)
            strokePaint.alpha = (140 + 115 * pulse).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), strokePaint)
        }
    }
}
