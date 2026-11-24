package com.gazelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.gazelock.app.ai.GazeTrackerEngine
import com.gazelock.app.ai.GazeTrackerEngine.GazeReading
import com.gazelock.app.audio.HypnoticAudioEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.OverlayMode
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.GazeVideoFeedOverlay
import com.gazelock.app.ui.MainActivity
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground Camera + Local AI Gaze Session Service (v3.0):
 * - Supports Semi-Transparent Ghost Pass-Through Mode (45-50% opacity, touch-unlocked + floating pill)
 *   AND Full-Lock Mode (100% opaque).
 * - #2 Real Iris/Pupil + Head Pose AI Gaze Tracking.
 * - #7 Subliminal Image Flashes & Neon Mantras inside [GazeVideoFeedOverlay].
 * - #8 Absolute Volume Lock (floor 40-100% + hardware Volume Down/Mute interception) + Forced Max Brightness
 *   + Local Synthesized Binaural Hypnotic Beat ([HypnoticAudioEngine]).
 * - #12 Fixed, Progressive & Sudden Death Penalties + #13 Anti-Escape +15s Watchdog.
 * - Interactive 3-Minute Overlay Unlock Portal (0.1s image spam + pop-ups + Sliding Puzzle & Image Reconstruction!).
 */
class GazeSessionService : LifecycleService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var activeCfg: GazeConfigSnapshot? = null
    private var tracker: GazeTrackerEngine? = null
    private var overlay: GazeVideoFeedOverlay? = null
    private var hypnoticAudio: HypnoticAudioEngine? = null

    // Fallback WindowManager handles (used only if AccessibilityService isn't running)
    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false
    private var fbPillView: View? = null
    private var fbPillParams: WindowManager.LayoutParams? = null
    private var fbPortalView: View? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Session Runtime State
    private var initialDurationMs = 300_000L
    private var totalDurationMs = 300_000L
    private var remainingMs = 300_000L
    private var focusedMs = 0L
    private var currentStreakMs = 0L
    private var bestStreakMs = 0L
    private var lookAwayEpisodes = 0
    private var progressiveStep = 0
    private var currentAwayMs = 0L
    private var penaltyAppliedForEpisode = false
    private var lastVoiceAlertMs = 0L
    private var lastVibrateMs = 0L
    private var lastNotifUpdateMs = 0L
    private var lockedVolumeTarget = -1
    private var sessionCompleted = false
    private var lastPenaltyBanner: String = ""
    private var lastPenaltyBannerUntilMs = 0L

    @Volatile
    private var latestGaze = GazeReading(
        faceDetected = true, yawDeg = 0f, pitchDeg = 0f, eyeOpenAvg = 0.9f,
        irisOffsetPct = 0, irisMeasured = false,
        rawLooking = true, isLookingAtScreen = true, reason = "Initialisation IA…"
    )

    private fun computeNextPenaltySec(cfg: GazeConfigSnapshot): Int {
        return when (cfg.penaltyMode) {
            PenaltyMode.FIXED -> cfg.lookAwayPenaltySec
            PenaltyMode.PROGRESSIVE -> {
                val base = cfg.lookAwayPenaltySec.coerceAtLeast(3)
                val shift = progressiveStep.coerceAtMost(4)
                (base shl shift).coerceAtMost(60)
            }
            PenaltyMode.SUDDEN_DEATH -> -1
        }
    }

    private fun enforceVolumeFloorInternal() {
        val cfg = activeCfg ?: return
        if (!cfg.volumeLockEnabled || lockedVolumeTarget <= 0) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < lockedVolumeTarget) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, lockedVolumeTarget, 0)
            }
        } catch (_: Throwable) {}
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val cfg = activeCfg ?: return
            val ov = overlay ?: return
            if (sessionCompleted) return

            // #8 Continuous Volume Floor Enforcement (even during 3-min portal!)
            enforceVolumeFloorInternal()

            // Pause video session countdown while inside the 3-minute overlay portal!
            if (ov.isAccessWaitActive) {
                hypnoticAudio?.setPaused(false) // Keep hypnotic pulse gently active during 3-min portal!
                mainHandler.postDelayed(this, 100L)
                return
            }

            val gaze = latestGaze
            val now = SystemClock.elapsedRealtime()

            if (gaze.isLookingAtScreen) {
                if (currentAwayMs > 0L) {
                    currentAwayMs = 0L
                    penaltyAppliedForEpisode = false
                }
                if (!cfg.infiniteUntilUnlock) {
                    remainingMs = (remainingMs - 100L).coerceAtLeast(0L)
                }
                focusedMs += 100L
                currentStreakMs += 100L
                if (currentStreakMs > bestStreakMs) bestStreakMs = currentStreakMs
                hypnoticAudio?.setPaused(false)
                ov.checkMaxClipSwitch()
            } else {
                if (currentAwayMs == 0L) {
                    lookAwayEpisodes++
                    currentStreakMs = 0L
                    penaltyAppliedForEpisode = false
                }
                currentAwayMs += 100L

                if (cfg.vibrateAlertEnabled && now - lastVibrateMs > 1_800L) {
                    lastVibrateMs = now
                    vibrateShort(70L)
                }
                if (cfg.voiceAlertEnabled && ttsReady && now - lastVoiceAlertMs > 4_800L) {
                    lastVoiceAlertMs = now
                    speakFrench(
                        listOf("Regarde l'écran !", "Chronomètre en pause !", "Ne détourne pas les yeux !").random()
                    )
                }

                if (!cfg.infiniteUntilUnlock) {
                    val triggerMs = if (cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH) 2_200L else 2_500L
                    val nextPen = computeNextPenaltySec(cfg)
                    if (!penaltyAppliedForEpisode && currentAwayMs >= triggerMs && (nextPen > 0 || cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH)) {
                        penaltyAppliedForEpisode = true
                        vibrateShort(150L)
                        if (cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH) {
                            remainingMs = initialDurationMs
                            totalDurationMs = initialDurationMs
                            lastPenaltyBanner = "💀 MORT SUBITE ! Chrono remis à ${initialDurationMs / 1000L} s !"
                            lastPenaltyBannerUntilMs = now + 5_000L
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakFrench("Zéro faute échoué ! Chronomètre remis à zéro !")
                                lastVoiceAlertMs = now
                            }
                        } else {
                            remainingMs += nextPen * 1000L
                            totalDurationMs = maxOf(totalDurationMs, remainingMs)
                            progressiveStep++
                            val modeTag = if (cfg.penaltyMode == PenaltyMode.PROGRESSIVE) "📈 Pénalité Progressive" else "🚨 Pénalité"
                            lastPenaltyBanner = "$modeTag : +$nextPen s ajoutées !"
                            lastPenaltyBannerUntilMs = now + 4_500L
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakFrench("Pénalité ! Plus $nextPen secondes !")
                                lastVoiceAlertMs = now
                            }
                        }
                    }
                }
            }

            val bannerToShow = if (now < lastPenaltyBannerUntilMs) lastPenaltyBanner else ""
            ov.updateHud(
                remainingMs = remainingMs,
                totalDurationMs = totalDurationMs,
                gaze = gaze,
                lookAwayDurationMs = currentAwayMs,
                penaltyAppliedCount = lookAwayEpisodes,
                nextPenaltySec = computeNextPenaltySec(cfg),
                penaltyMode = cfg.penaltyMode,
                flashBanner = bannerToShow
            )

            if (now - lastNotifUpdateMs >= 2_000L) {
                lastNotifUpdateMs = now
                updateForegroundNotification()
            }

            if (!cfg.infiniteUntilUnlock && remainingMs <= 0L) {
                onSessionFinishedNaturally()
            } else {
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gazeConfig = GazeConfig(applicationContext)
        fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.language = Locale.FRENCH; ttsReady = true } catch (_: Throwable) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSessionCleanly()
            return START_NOT_STICKY
        }

        if (isSessionRunning) return START_STICKY

        val cfg = gazeConfig.snapshot()
        val overrideDur = intent?.getIntExtra(EXTRA_DURATION_SEC, -1) ?: -1
        val durSec = if (overrideDur >= 15) overrideDur else cfg.durationSec

        createChannel()
        val notif = buildNotification(
            if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Overlay Semi-Transparent GazeLock AI actif…"
            else "👁️ Démarrage du fil vidéo GazeLock AI…"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        }

        val playlist = VideoFeedRepository.loadVideoPlaylist(applicationContext, cfg)
        if (playlist.isEmpty()) {
            mainHandler.post {
                Toast.makeText(applicationContext, "⚠️ Aucune vidéo trouvée dans le dossier choisi !", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        activeCfg = cfg
        initialDurationMs = durSec * 1000L
        totalDurationMs = initialDurationMs
        remainingMs = initialDurationMs
        focusedMs = 0L; currentStreakMs = 0L; bestStreakMs = 0L
        lookAwayEpisodes = 0; progressiveStep = 0; currentAwayMs = 0L; penaltyAppliedForEpisode = false
        lastPenaltyBanner = ""; lastPenaltyBannerUntilMs = 0L
        sessionCompleted = false

        // #8 Compute & enforce Volume Floor (40..100%)
        lockedVolumeTarget = -1
        if (cfg.volumeLockEnabled) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    lockedVolumeTarget = ((maxV * cfg.volumeFloorPct.coerceIn(40, 100)) / 100f).toInt().coerceIn(1, maxV)
                    enforceVolumeFloorInternal()
                }
            } catch (_: Throwable) {}
        }

        val ov = GazeVideoFeedOverlay(
            context = applicationContext,
            config = cfg,
            initialPlaylist = playlist,
            onMountPortalOverlay = { portalView -> mountPortalWindow(portalView, cfg.forceMaxBrightness) },
            onRemovePortalOverlay = { removePortalWindow() },
            onDragGhostPill = { dx, dy -> dragGhostPillWindow(dx, dy) },
            onIssueAccessCodeAfterWait = { gazeConfig.issueUnlockedAccessCode() },
            onVerifyAccessCode = { code -> gazeConfig.verifyDynamicAccessCode(code) },
            onEmergencyExit = { stopSessionCleanly() },
            onSessionCompleteDismiss = { stopSessionCleanly() }
        )

        val mounted = mountPrimaryOverlays(ov, cfg)
        if (!mounted) {
            ov.release()
            mainHandler.post {
                Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (recommandé) ou Superposition pour lancer l'overlay !", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        overlay = ov
        activeInstance = this
        isSessionRunning = true
        notifyListeners()

        // #8 Start Local Synthesized Hypnotic Binaural Pulse if enabled!
        if (cfg.binauralBeatEnabled) {
            hypnoticAudio = HypnoticAudioEngine().apply {
                start(cfg.binauralVolumePct, cfg.binauralFreqHz)
            }
        }

        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { reading -> latestGaze = reading },
            onCameraError = { err ->
                mainHandler.post { Toast.makeText(applicationContext, "⚠️ $err", Toast.LENGTH_LONG).show() }
            }
        )
        tracker = tr
        tr.start(this, cfg)

        // In Ghost Pass-Through mode, immediately drop user to their Home Screen so they can keep using their phone right away!
        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            mainHandler.postDelayed({
                try {
                    GazeAccessibilityService.activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                } catch (_: Throwable) {}
            }, 350L)
        }

        mainHandler.postDelayed(tickRunnable, 200L)
        return START_STICKY
    }

    /** Called by #13 Anti-Escape Watchdog when Settings / Uninstall / Force-Stop / Recents escape is attempted! */
    fun applyEscapeAttemptPenaltyInternal() {
        mainHandler.post {
            if (!isSessionRunning || sessionCompleted) return@post
            gazeConfig.recordEscapeBlock()
            if (activeCfg?.infiniteUntilUnlock != true) {
                remainingMs += 15_000L
                totalDurationMs = maxOf(totalDurationMs, remainingMs)
            }
            lastPenaltyBanner = if (activeCfg?.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
                "🚫 Paramètres verrouillés ! Utilise le portail 3 min 🔒 (+15 s)"
            } else {
                "🚨 TENTATIVE D'ÉVASION BLOQUÉE ! +15 s de pénalité !"
            }
            lastPenaltyBannerUntilMs = SystemClock.elapsedRealtime() + 5_500L
            vibrateShort(180L)
            if (activeCfg?.voiceAlertEnabled == true && ttsReady) {
                speakFrench("Paramètres verrouillés ! Passe par le portail de trois minutes !")
                lastVoiceAlertMs = SystemClock.elapsedRealtime()
            }
        }
    }

    fun openUnlockPortalInternal() {
        mainHandler.post {
            if (isSessionRunning && !sessionCompleted) {
                overlay?.openUnlockPortal()
            }
        }
    }

    // ==================== MULTI-WINDOW MOUNTING (A11Y TRUSTED + FALLBACK) ====================
    @Suppress("DEPRECATION")
    private fun mountPrimaryOverlays(ov: GazeVideoFeedOverlay, cfg: GazeConfigSnapshot): Boolean {
        val isGhost = ov.isGhostMode
        val a11y = GazeAccessibilityService.activeInstance
        if (a11y != null && a11y.mountVideoOverlay(ov.rootView, passThroughTouches = isGhost, forceMaxBrightness = cfg.forceMaxBrightness)) {
            mountedViaA11y = true
            ov.ghostPillView?.let { pill -> a11y.mountGhostPillOverlay(pill) }
            return true
        }

        // Fallback via SYSTEM_ALERT_WINDOW if Accessibility isn't active
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canDraw) return false
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val flags = if (isGhost) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (cfg.forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            fallbackWm?.addView(ov.rootView, lp)

            ov.ghostPillView?.let { pill ->
                val dm = resources.displayMetrics
                val plp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    winType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (dm.widthPixels - (175 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                    y = (44 * dm.density).toInt()
                }
                fallbackWm?.addView(pill, plp)
                fbPillView = pill
                fbPillParams = plp
            }
            mountedViaA11y = false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun dragGhostPillWindow(dx: Int, dy: Int) {
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.dragGhostPillBy(dx, dy)
        } else {
            val v = fbPillView ?: return
            val lp = fbPillParams ?: return
            val dm = resources.displayMetrics
            lp.x = (lp.x + dx).coerceIn(0, (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0))
            lp.y = (lp.y + dy).coerceIn((20 * dm.density).toInt(), (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0))
            try { fallbackWm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun mountPortalWindow(view: View, forceMaxBrightness: Boolean): Boolean {
        if (mountedViaA11y) {
            val a11y = GazeAccessibilityService.activeInstance
            if (a11y != null && a11y.mountPortalOverlay(view, forceMaxBrightness)) return true
        }
        return try {
            removePortalWindow()
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            fallbackWm?.addView(view, lp)
            fbPortalView = view
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun removePortalWindow() {
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.removePortalOverlay()
        }
        fbPortalView?.let { v ->
            fbPortalView = null
            try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {}
        }
    }

    private fun onSessionFinishedNaturally() {
        sessionCompleted = true
        mainHandler.removeCallbacks(tickRunnable)
        tracker?.stop()
        hypnoticAudio?.stop(); hypnoticAudio = null
        val fSec = (focusedMs / 1000L).toInt()
        val streakSec = (bestStreakMs / 1000L).toInt()
        gazeConfig.recordCompletedSession(fSec, lookAwayEpisodes, streakSec)
        vibrateShort(110L)
        if (activeCfg?.voiceAlertEnabled == true && ttsReady) {
            speakFrench("Session terminée ! Bravo pour ton attention.")
        }
        overlay?.showCompleted(fSec, lookAwayEpisodes, streakSec)
        updateForegroundNotification("✨ Session GazeLock AI terminée !")
    }

    private fun stopSessionCleanly() {
        mainHandler.removeCallbacks(tickRunnable)
        isSessionRunning = false
        if (activeInstance === this) activeInstance = null
        tracker?.shutdown(); tracker = null
        hypnoticAudio?.stop(); hypnoticAudio = null
        val ov = overlay
        overlay = null
        if (ov != null) {
            ov.release()
            if (mountedViaA11y) {
                GazeAccessibilityService.activeInstance?.removeAllOverlays()
            } else {
                removePortalWindow()
                fbPillView?.let { try { fallbackWm?.removeViewImmediate(it) } catch (_: Throwable) {} }
                fbPillView = null; fbPillParams = null
                try { fallbackWm?.removeViewImmediate(ov.rootView) } catch (_: Throwable) {}
            }
        }
        notifyListeners()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickRunnable)
        isSessionRunning = false
        if (activeInstance === this) activeInstance = null
        tracker?.shutdown(); tracker = null
        hypnoticAudio?.stop(); hypnoticAudio = null
        overlay?.release(); overlay = null
        try { GazeAccessibilityService.activeInstance?.removeAllOverlays() } catch (_: Throwable) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        notifyListeners()
        super.onDestroy()
    }

    private fun speakFrench(text: String) {
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gaze_alert") } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "GazeLock AI — Session Active", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("👁️ GazeLock AI v3.0")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun updateForegroundNotification(customText: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cfg = activeCfg
        val txt = customText ?: run {
            val prefix = if (cfg?.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Ghost ${cfg.ghostOpacityPct}%" else "🔒 Verrouillé"
            if (cfg?.infiniteUntilUnlock == true) {
                "$prefix — ∞ (Portail 3 min requis)"
            } else {
                val s = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
                val status = if (latestGaze.isLookingAtScreen) "👁️ Regard OK" else "⏸️ Pause"
                String.format("%s · %s — %02d:%02d", prefix, status, s / 60, s % 60)
            }
        }
        try { nm.notify(NOTIF_ID, buildNotification(txt)) } catch (_: Throwable) {}
    }

    companion object {
        private const val CHANNEL_ID = "gazelock_session_ch"
        private const val NOTIF_ID = 4105
        const val ACTION_STOP = "com.gazelock.app.STOP_SESSION"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"

        @Volatile
        var isSessionRunning: Boolean = false
            private set

        @Volatile
        private var activeInstance: GazeSessionService? = null

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) { listeners.add(l) }
        fun removeStateListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() { for (l in listeners) try { l() } catch (_: Throwable) {} }

        fun isCurrentSessionGhostMode(): Boolean =
            activeInstance?.activeCfg?.overlayMode == OverlayMode.GHOST_PASSTHROUGH

        fun isVolumeLockCurrentlyActive(): Boolean =
            activeInstance?.activeCfg?.volumeLockEnabled == true

        fun enforceVolumeFloorNow(context: Context) {
            activeInstance?.mainHandler?.post { activeInstance?.enforceVolumeFloorInternal() }
        }

        fun triggerEscapeAttemptPenalty(context: Context) {
            activeInstance?.applyEscapeAttemptPenaltyInternal()
        }

        fun openUnlockPortalOverlay() {
            activeInstance?.openUnlockPortalInternal()
        }

        fun startSession(context: Context, overrideDurationSec: Int = -1) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply {
                if (overrideDurationSec > 0) putExtra(EXTRA_DURATION_SEC, overrideDurationSec)
            }
            ContextCompat.startForegroundService(context.applicationContext, i)
        }

        fun stopSession(context: Context) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply { action = ACTION_STOP }
            try { context.applicationContext.startService(i) } catch (_: Throwable) {}
        }
    }
}
