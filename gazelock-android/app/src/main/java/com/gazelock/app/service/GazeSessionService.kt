package com.gazelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.gazelock.app.overlay.AttentionHijackOverlayView
import com.gazelock.app.overlay.GazeVideoFeedOverlay
import com.gazelock.app.overlay.GhostMicroPopupView
import com.gazelock.app.overlay.TouchDeadZoneView
import com.gazelock.app.ui.MainActivity
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Foreground Session Orchestrator for GazeLock AI v4.0 ULTRA EXTREME:
 * - #1 Living Opacity Escalation (48% -> 96% -> forced hijack!)
 * - #2 Sudden Attention Hijack bursts in Ghost mode (96% touch-locked until unbroken gaze!)
 * - #5 Floating Ghost Micro-Popups during normal phone usage (+6% opacity if ignored!)
 * - #9 Punitive Confiscated Touch Dead-Zones on look-away in Ghost mode!
 * - #16 6 Visual Folder-Image Mini-Games + #17 Final 45s 20 FPS Overload Sprint + #22 Gaze-Gated 3-Min Portal!
 * - #18 Time Debt carryover on early abort via 3-min portal!
 * - #21 Hypnotic 60 FPS Spiral + #23 Deep TTS Mantra Whispers + #24 Blind Timer + #25 Anti-Flat Accelerometer + #26 Immortal Mode!
 */
class GazeSessionService : LifecycleService(), SensorEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var activeCfg: GazeConfigSnapshot? = null
    private var tracker: GazeTrackerEngine? = null
    private var overlay: GazeVideoFeedOverlay? = null
    private var hypnoticAudio: HypnoticAudioEngine? = null
    private var sensorManager: SensorManager? = null

    // Fallback WindowManager handles (only when Accessibility isn't running)
    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false
    private var fbPillView: View? = null
    private var fbPillParams: WindowManager.LayoutParams? = null
    private var fbMicroPopupView: View? = null
    private var fbDeadZoneView: View? = null
    private var fbPortalView: View? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Core Session Runtime State
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
    private var lastCheckpointSaveMs = 0L
    private var lockedVolumeTarget = -1
    private var sessionCompleted = false
    private var lastPenaltyBanner: String = ""
    private var lastPenaltyBannerUntilMs = 0L

    // #1 Living Opacity Runtime
    private var dynamicOpacityPct = 48f
    private var opacityCreepAccumMs = 0L
    private var opacityCooldownAccumMs = 0L

    // #2 Attention Hijack Runtime
    private var hijackActive = false
    private var hijackView: AttentionHijackOverlayView? = null
    private var hijackFocusedMs = 0L
    private var hijackAwayGraceMs = 0L
    private var hijackRequiredMs = 14_000L
    private var nextHijackElapsedMs = 0L
    private var hijackWarned = false

    // #5 Ghost Micro-Popups Runtime
    private var activeMicroPopup: GhostMicroPopupView? = null
    private var nextMicroPopupElapsedMs = 0L

    // #9 Confiscated Touch Dead-Zone Runtime
    private var activeDeadZone: TouchDeadZoneView? = null
    private var deadZoneRemainingMs = 0L
    private val deadZoneTotalMs = 18_000L

    // #23 Deep TTS Mantra Whispers Runtime
    private var nextWhisperElapsedMs = 0L

    // #25 Anti-Flat Accelerometer Runtime
    @Volatile
    private var accelFlatNow = false
    private var flatAccumMs = 0L

    @Volatile
    private var latestGaze = GazeReading(
        faceDetected = true, yawDeg = 0f, pitchDeg = 0f, eyeOpenAvg = 0.9f,
        irisOffsetPct = 0, irisMeasured = false,
        rawLooking = true, isLookingAtScreen = true, reason = "Initialisation IA…"
    )

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        // Flat face-up or face-down on a desk/bed: gravity almost purely along Z axis!
        accelFlatNow = abs(z) > 8.95f && hypot(x.toDouble(), y.toDouble()) < 2.25
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeNextPenaltySec(cfg: GazeConfigSnapshot): Int = when (cfg.penaltyMode) {
        PenaltyMode.FIXED -> cfg.lookAwayPenaltySec
        PenaltyMode.PROGRESSIVE -> (cfg.lookAwayPenaltySec.coerceAtLeast(3) shl progressiveStep.coerceAtMost(4)).coerceAtMost(60)
        PenaltyMode.SUDDEN_DEATH -> -1
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

            val now = SystemClock.elapsedRealtime()
            enforceVolumeFloorInternal()

            // #26 Immortal Mode periodic checkpoint every 4s
            if (cfg.rebootSurvivalEnabled && now - lastCheckpointSaveMs >= 4_000L) {
                lastCheckpointSaveMs = now
                gazeConfig.saveActiveSessionCheckpoint((remainingMs / 1000L).toInt(), cfg.infiniteUntilUnlock)
            }

            // If inside 3-Minute Overlay Unlock Portal, keep video countdown frozen & suppress popups/hijack!
            if (ov.isAccessWaitActive) {
                dismissMicroPopup()
                dismissDeadZone()
                hypnoticAudio?.setPaused(false)
                mainHandler.postDelayed(this, 100L)
                return
            }

            // #25 Anti-Flat Table Detection
            if (cfg.antiFlatSensorEnabled && accelFlatNow) {
                flatAccumMs += 100L
            } else {
                flatAccumMs = 0L
            }
            val phoneFlatPenalty = cfg.antiFlatSensorEnabled && flatAccumMs >= 6_000L
            val flatReason = if (phoneFlatPenalty) "📐 Téléphone posé à plat — Reprends-le en main !" else null

            val gaze = latestGaze
            val effectivelyLooking = gaze.isLookingAtScreen && !phoneFlatPenalty

            // ==================== #2 ACTIVE ATTENTION HIJACK LOOP ====================
            if (hijackActive) {
                if (effectivelyLooking) {
                    hijackAwayGraceMs = 0L
                    hijackFocusedMs += 100L
                    focusedMs += 100L
                    if (!cfg.infiniteUntilUnlock) remainingMs = (remainingMs - 100L).coerceAtLeast(0L)
                } else {
                    hijackAwayGraceMs += 100L
                    if (hijackAwayGraceMs >= 900L && hijackFocusedMs > 0L) {
                        hijackFocusedMs = 0L
                        vibrateShort(110L)
                    }
                }
                hijackView?.updateHijackState(
                    focusedMs = hijackFocusedMs,
                    requiredMs = hijackRequiredMs,
                    isLooking = effectivelyLooking,
                    reason = flatReason ?: gaze.reason
                )
                if (hijackFocusedMs >= hijackRequiredMs) {
                    endAttentionHijack(cfg, ov, now)
                }
                mainHandler.postDelayed(this, 100L)
                return
            }

            // ==================== NORMAL / GHOST SESSION TICK ====================
            if (effectivelyLooking) {
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

                // #1 Living Opacity: slow creep over time + gentle cooldown when deeply focused!
                if (ov.isGhostMode && cfg.livingOpacityEnabled) {
                    opacityCreepAccumMs += 100L
                    if (opacityCreepAccumMs >= 45_000L) {
                        opacityCreepAccumMs = 0L
                        bumpLivingOpacity(cfg, ov, +3f, now, banner = null)
                    }
                    if (dynamicOpacityPct > cfg.ghostOpacityPct + 1f && currentStreakMs >= 15_000L) {
                        opacityCooldownAccumMs += 100L
                        if (opacityCooldownAccumMs >= 15_000L) {
                            opacityCooldownAccumMs = 0L
                            dynamicOpacityPct = (dynamicOpacityPct - 2f).coerceAtLeast(cfg.ghostOpacityPct.toFloat())
                            ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt())
                        }
                    }
                } else if (ov.isGhostMode) {
                    ov.setDynamicGhostOpacity(cfg.ghostOpacityPct)
                }

                // #9 Shrink active Touch Dead-Zone while focusing cleanly!
                if (activeDeadZone != null) {
                    deadZoneRemainingMs = (deadZoneRemainingMs - 100L).coerceAtLeast(0L)
                    activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = true)
                    if (deadZoneRemainingMs <= 0L) {
                        dismissDeadZone()
                        showBanner("✅ Zone tactile libérée !", now, 2_400L)
                    }
                }

                // #23 Deep Hypnotic TTS Mantra Whispers
                if (cfg.ttsMantraWhisperEnabled && ttsReady && now >= nextWhisperElapsedMs && now - lastVoiceAlertMs > 6_000L) {
                    nextWhisperElapsedMs = now + Random.nextLong(24_000L, 40_000L)
                    speakHypnoticWhisper(cfg.mantraList().random())
                }
            } else {
                if (currentAwayMs == 0L) {
                    lookAwayEpisodes++
                    currentStreakMs = 0L
                    opacityCooldownAccumMs = 0L
                    penaltyAppliedForEpisode = false
                }
                currentAwayMs += 100L

                // Spike Ghost opacity to 90% if phone was laid flat on a table (#25)!
                if (ov.isGhostMode && phoneFlatPenalty) {
                    ov.setDynamicGhostOpacity(90, intenseSpiral = true)
                }

                // Regrow Dead-Zone if looking away while it's active!
                if (activeDeadZone != null) {
                    deadZoneRemainingMs = (deadZoneRemainingMs + 200L).coerceAtMost(22_000L)
                    activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false)
                }

                if (cfg.vibrateAlertEnabled && now - lastVibrateMs > 1_800L) {
                    lastVibrateMs = now
                    vibrateShort(70L)
                }
                if (cfg.voiceAlertEnabled && ttsReady && now - lastVoiceAlertMs > 4_800L) {
                    lastVoiceAlertMs = now
                    val msg = if (phoneFlatPenalty) "Reprends le téléphone en main !"
                    else listOf("Regarde l'écran !", "Chronomètre en pause !", "Ne détourne pas les yeux !").random()
                    speakAlertFrench(msg)
                }

                val triggerMs = if (cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH) 2_200L else 2_500L
                val nextPen = computeNextPenaltySec(cfg)
                if (!penaltyAppliedForEpisode && currentAwayMs >= triggerMs) {
                    penaltyAppliedForEpisode = true
                    vibrateShort(150L)

                    // #1 Living Opacity bump on look-away fault!
                    if (ov.isGhostMode && cfg.livingOpacityEnabled) {
                        bumpLivingOpacity(cfg, ov, +6f, now, banner = null)
                    }
                    // #9 Spawn Confiscated Touch Dead-Zone on look-away fault in Ghost mode!
                    if (ov.isGhostMode && cfg.deadZonesEnabled) {
                        spawnOrRefreshDeadZone()
                    }

                    if (!cfg.infiniteUntilUnlock && (nextPen > 0 || cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH)) {
                        if (cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH) {
                            remainingMs = initialDurationMs
                            totalDurationMs = initialDurationMs
                            showBanner("💀 MORT SUBITE ! Chrono remis à ${initialDurationMs / 1000L} s !", now, 5_000L)
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakAlertFrench("Zéro faute échoué ! Chronomètre remis à zéro !")
                                lastVoiceAlertMs = now
                            }
                        } else {
                            remainingMs += nextPen * 1000L
                            totalDurationMs = maxOf(totalDurationMs, remainingMs)
                            progressiveStep++
                            val modeTag = if (cfg.penaltyMode == PenaltyMode.PROGRESSIVE) "📈 Pénalité Progressive" else "🚨 Pénalité"
                            showBanner("$modeTag : +$nextPen s ajoutées !", now, 4_500L)
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakAlertFrench("Pénalité ! Plus $nextPen secondes !")
                                lastVoiceAlertMs = now
                            }
                        }
                    }
                }
            }

            // ==================== #2 SCHEDULED ATTENTION HIJACK IN GHOST MODE ====================
            if (ov.isGhostMode && cfg.attentionHijackEnabled && !hijackActive && now >= nextHijackElapsedMs - 3_000L) {
                if (!hijackWarned) {
                    hijackWarned = true
                    vibrateShort(120L)
                    showBanner("⚡ COUPURE D'EMPRISE DANS 3 s ! Prépare ton regard !", now, 3_000L)
                }
                if (now >= nextHijackElapsedMs) {
                    startAttentionHijack(cfg, ov)
                    mainHandler.postDelayed(this, 100L)
                    return
                }
            }

            // ==================== #5 SCHEDULED GHOST MICRO-POPUPS ====================
            if (ov.isGhostMode && cfg.ghostMicroPopupsEnabled && activeMicroPopup == null && !hijackActive && now >= nextMicroPopupElapsedMs) {
                scheduleNextMicroPopup(cfg, now)
                spawnGhostMicroPopup(cfg, ov)
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
                flashBanner = bannerToShow,
                overrideReason = flatReason
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

    // ==================== #1 LIVING OPACITY ESCALATION ====================
    private fun bumpLivingOpacity(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, delta: Float, now: Long, banner: String?) {
        if (!ov.isGhostMode || !cfg.livingOpacityEnabled) return
        dynamicOpacityPct = (dynamicOpacityPct + delta).coerceIn(cfg.ghostOpacityPct.toFloat(), 96f)
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt())
        if (banner != null) showBanner(banner, now, 3_800L)
        // Hitting >= 95% automatically forces a sudden Attention Hijack!
        if (dynamicOpacityPct >= 95f && !hijackActive && !ov.isAccessWaitActive) {
            startAttentionHijack(cfg, ov)
        }
    }

    // ==================== #2 ATTENTION HIJACK ====================
    private fun startAttentionHijack(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        if (hijackActive || ov.isAccessWaitActive || sessionCompleted) return
        dismissMicroPopup()
        dismissDeadZone()
        hijackActive = true
        hijackWarned = false
        hijackFocusedMs = 0L
        hijackAwayGraceMs = 0L
        hijackRequiredMs = cfg.attentionHijackFocusSec.coerceIn(8, 25) * 1000L

        ov.setDynamicGhostOpacity(96, intenseSpiral = true)
        vibrateShort(180L)
        if (cfg.voiceAlertEnabled && ttsReady) {
            speakAlertFrench("Coupure d'emprise ! Fixe l'écran pour récupérer le tactile !")
            lastVoiceAlertMs = SystemClock.elapsedRealtime()
        }

        val hv = AttentionHijackOverlayView(
            context = applicationContext,
            requiredSec = cfg.attentionHijackFocusSec,
            onOpenThreeMinPortal = {
                cancelHijackForPortal(cfg, ov)
                ov.openUnlockPortal()
            }
        )
        hijackView = hv
        if (!mountPortalWindow(hv, cfg.forceMaxBrightness)) {
            hijackActive = false; hijackView = null
            ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt())
        }
    }

    private fun endAttentionHijack(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, now: Long) {
        hijackActive = false
        hijackView = null
        removePortalWindow()
        dynamicOpacityPct = (cfg.ghostOpacityPct + 6).coerceAtMost(70).toFloat()
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), intenseSpiral = false)
        scheduleNextHijack(cfg, now)
        vibrateShort(90L)
        showBanner("✅ Emprise levée — Tactile rendu (${dynamicOpacityPct.toInt()} %) !", now, 3_200L)
    }

    private fun cancelHijackForPortal(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        hijackActive = false
        hijackView = null
        removePortalWindow()
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), intenseSpiral = false)
        scheduleNextHijack(cfg, SystemClock.elapsedRealtime())
    }

    private fun scheduleNextHijack(cfg: GazeConfigSnapshot, now: Long) {
        val baseMs = cfg.attentionHijackIntervalMin.coerceIn(1, 8) * 60_000L
        val jitter = Random.nextLong(-(baseMs / 5L), (baseMs / 5L) + 1L)
        nextHijackElapsedMs = now + (baseMs + jitter).coerceAtLeast(45_000L)
        hijackWarned = false
    }

    // ==================== #5 GHOST MICRO-POPUPS ====================
    private fun scheduleNextMicroPopup(cfg: GazeConfigSnapshot, now: Long) {
        val baseMs = cfg.ghostMicroPopupIntervalSec.coerceIn(20, 90) * 1000L
        val jitter = Random.nextLong(-(baseMs / 4L), (baseMs / 4L) + 1L)
        nextMicroPopupElapsedMs = now + (baseMs + jitter).coerceAtLeast(16_000L)
    }

    private fun spawnGhostMicroPopup(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        dismissMicroPopup()
        val thumb = ov.pickRandomFolderThumbnail()
        val mantra = cfg.mantraList().random()
        val mp = GhostMicroPopupView(
            context = applicationContext,
            thumbnail = thumb,
            mantra = mantra,
            durationMs = 6_500L,
            onAcknowledged = {
                dismissMicroPopup()
            },
            onTimedOut = {
                dismissMicroPopup()
                vibrateShort(130L)
                val now = SystemClock.elapsedRealtime()
                if (cfg.livingOpacityEnabled) {
                    bumpLivingOpacity(cfg, ov, +6f, now, "⚠️ Micro-pop-up ignoré ! Opacité montée à ${(dynamicOpacityPct + 6f).toInt().coerceAtMost(96)} % !")
                } else {
                    if (!cfg.infiniteUntilUnlock) {
                        remainingMs += 10_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
                    }
                    showBanner("⚠️ Micro-pop-up ignoré ! +10 s ajoutées !", now, 3_500L)
                }
            }
        )
        if (mountMicroPopupWindow(mp)) {
            activeMicroPopup = mp
            vibrateShort(45L)
        } else {
            mp.release()
        }
    }

    private fun dismissMicroPopup() {
        activeMicroPopup?.release()
        activeMicroPopup = null
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeMicroPopupOverlay()
        fbMicroPopupView?.let { v -> fbMicroPopupView = null; try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
    }

    // ==================== #9 CONFISCATED TOUCH DEAD-ZONE ====================
    private fun spawnOrRefreshDeadZone() {
        deadZoneRemainingMs = deadZoneTotalMs
        if (activeDeadZone != null) {
            activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false)
            return
        }
        val dz = TouchDeadZoneView(applicationContext)
        dz.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false)
        if (mountDeadZoneWindow(dz)) {
            activeDeadZone = dz
        }
    }

    private fun dismissDeadZone() {
        activeDeadZone = null
        deadZoneRemainingMs = 0L
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeDeadZoneOverlay()
        fbDeadZoneView?.let { v -> fbDeadZoneView = null; try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
    }

    private fun showBanner(text: String, now: Long, durMs: Long) {
        lastPenaltyBanner = text
        lastPenaltyBannerUntilMs = now + durMs
    }

    override fun onCreate() {
        super.onCreate()
        gazeConfig = GazeConfig(applicationContext)
        fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.language = Locale.FRENCH; ttsReady = true } catch (_: Throwable) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSessionCleanly(earlyEmergencyAbort = false)
            return START_NOT_STICKY
        }

        if (isSessionRunning) return START_STICKY

        val cfg = gazeConfig.snapshot()
        val overrideDur = intent?.getIntExtra(EXTRA_DURATION_SEC, -1) ?: -1
        val resumedFromCrash = intent?.getBooleanExtra(EXTRA_RESUMED_CRASH, false) ?: false
        var durSec = if (overrideDur >= 15) overrideDur else cfg.durationSec

        // #18 Apply Pending Time Debt automatically on timed session launch!
        val appliedDebtSec = if (!cfg.infiniteUntilUnlock && cfg.timeDebtEnabled && !resumedFromCrash && cfg.pendingTimeDebtSec > 0) {
            durSec += cfg.pendingTimeDebtSec
            cfg.pendingTimeDebtSec
        } else 0

        createChannel()
        val notif = buildNotification(
            if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Overlay Ghost GazeLock AI v4.0 actif…"
            else "👁️ Démarrage du fil vidéo GazeLock AI v4.0…"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIF_ID, notif)
        } catch (_: Throwable) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        }

        val playlist = VideoFeedRepository.loadVideoPlaylist(applicationContext, cfg)
        if (playlist.isEmpty()) {
            mainHandler.post { Toast.makeText(applicationContext, "⚠️ Aucune vidéo trouvée dans le dossier choisi !", Toast.LENGTH_LONG).show() }
            gazeConfig.clearActiveSessionCheckpoint()
            stopSelf()
            return START_NOT_STICKY
        }

        val now = SystemClock.elapsedRealtime()
        activeCfg = cfg
        initialDurationMs = durSec * 1000L
        totalDurationMs = initialDurationMs
        remainingMs = initialDurationMs
        focusedMs = 0L; currentStreakMs = 0L; bestStreakMs = 0L
        lookAwayEpisodes = 0; progressiveStep = 0; currentAwayMs = 0L; penaltyAppliedForEpisode = false
        dynamicOpacityPct = cfg.ghostOpacityPct.toFloat(); opacityCreepAccumMs = 0L; opacityCooldownAccumMs = 0L
        hijackActive = false; hijackView = null; hijackWarned = false
        activeMicroPopup = null; activeDeadZone = null; deadZoneRemainingMs = 0L
        accelFlatNow = false; flatAccumMs = 0L
        sessionCompleted = false

        scheduleNextHijack(cfg, now)
        scheduleNextMicroPopup(cfg, now)
        nextWhisperElapsedMs = now + Random.nextLong(16_000L, 28_000L)

        when {
            resumedFromCrash -> showBanner("🔁 MODE IMMORTEL : Session reprise (+45 s d'évasion) !", now, 6_000L)
            appliedDebtSec > 0 -> showBanner("💸 DETTE DE TEMPS APPLIQUÉE : +${appliedDebtSec / 60}m${appliedDebtSec % 60}s !", now, 6_000L)
            else -> { lastPenaltyBanner = ""; lastPenaltyBannerUntilMs = 0L }
        }

        // #8 Volume Floor Lock
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
            gazeLookingSupplier = { latestGaze.isLookingAtScreen && !(cfg.antiFlatSensorEnabled && flatAccumMs >= 6_000L) },
            onOverloadSprintChanged = { sprint -> hypnoticAudio?.setOverloadSprint(sprint) },
            onMountPortalOverlay = { portalView -> mountPortalWindow(portalView, cfg.forceMaxBrightness) },
            onRemovePortalOverlay = { removePortalWindow() },
            onDragGhostPill = { dx, dy -> dragGhostPillWindow(dx, dy) },
            onIssueAccessCodeAfterWait = { gazeConfig.issueUnlockedAccessCode() },
            onVerifyAccessCode = { code -> gazeConfig.verifyDynamicAccessCode(code) },
            onEmergencyExit = { stopSessionCleanly(earlyEmergencyAbort = true) },
            onSessionCompleteDismiss = { stopSessionCleanly(earlyEmergencyAbort = false) }
        )

        val mounted = mountPrimaryOverlays(ov, cfg)
        if (!mounted) {
            ov.release()
            mainHandler.post { Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (recommandé) ou Superposition !", Toast.LENGTH_LONG).show() }
            stopSelf()
            return START_NOT_STICKY
        }

        overlay = ov
        activeInstance = this
        isSessionRunning = true
        notifyListeners()

        if (cfg.rebootSurvivalEnabled) {
            gazeConfig.saveActiveSessionCheckpoint(durSec, cfg.infiniteUntilUnlock)
        }

        if (cfg.binauralBeatEnabled) {
            hypnoticAudio = HypnoticAudioEngine().apply { start(cfg.binauralVolumePct, cfg.binauralFreqHz) }
        }

        // #25 Register Accelerometer for Anti-Flat Table Detection
        if (cfg.antiFlatSensorEnabled) {
            try {
                val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: Throwable) {}
        }

        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { reading -> latestGaze = reading },
            onCameraError = { err -> mainHandler.post { Toast.makeText(applicationContext, "⚠️ $err", Toast.LENGTH_LONG).show() } }
        )
        tracker = tr
        tr.start(this, cfg)

        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            mainHandler.postDelayed({
                try { GazeAccessibilityService.activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            }, 350L)
        }

        mainHandler.postDelayed(tickRunnable, 200L)
        return START_STICKY
    }

    fun applyEscapeAttemptPenaltyInternal() {
        mainHandler.post {
            val cfg = activeCfg ?: return@post
            val ov = overlay ?: return@post
            if (!isSessionRunning || sessionCompleted) return@post
            gazeConfig.recordEscapeBlock()
            val now = SystemClock.elapsedRealtime()
            if (!cfg.infiniteUntilUnlock) {
                remainingMs += 15_000L
                totalDurationMs = maxOf(totalDurationMs, remainingMs)
            }
            if (ov.isGhostMode && cfg.livingOpacityEnabled) {
                bumpLivingOpacity(cfg, ov, +10f, now, null)
            }
            if (ov.isGhostMode && cfg.deadZonesEnabled && !hijackActive && !ov.isAccessWaitActive) {
                spawnOrRefreshDeadZone()
            }
            val msg = if (ov.isGhostMode) "🚫 Paramètres verrouillés ! Portail 3 min requis (+15 s · Opacité +10 %)"
            else "🚨 TENTATIVE D'ÉVASION BLOQUÉE ! +15 s de pénalité !"
            showBanner(msg, now, 5_500L)
            vibrateShort(180L)
            if (cfg.voiceAlertEnabled && ttsReady) {
                speakAlertFrench("Paramètres verrouillés ! Passe par le portail de trois minutes !")
                lastVoiceAlertMs = now
            }
        }
    }

    fun openUnlockPortalInternal() {
        mainHandler.post {
            val cfg = activeCfg ?: return@post
            val ov = overlay ?: return@post
            if (isSessionRunning && !sessionCompleted) {
                if (hijackActive) cancelHijackForPortal(cfg, ov)
                ov.openUnlockPortal()
            }
        }
    }

    // ==================== MULTI-WINDOW MOUNTING (A11Y + FALLBACK) ====================
    @Suppress("DEPRECATION")
    private fun mountPrimaryOverlays(ov: GazeVideoFeedOverlay, cfg: GazeConfigSnapshot): Boolean {
        val isGhost = ov.isGhostMode
        val a11y = GazeAccessibilityService.activeInstance
        if (a11y != null && a11y.mountVideoOverlay(ov.rootView, passThroughTouches = isGhost, forceMaxBrightness = cfg.forceMaxBrightness)) {
            mountedViaA11y = true
            ov.ghostPillView?.let { pill -> a11y.mountGhostPillOverlay(pill) }
            return true
        }
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canDraw) return false
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val flags = if (isGhost) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, winType, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (cfg.forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            fallbackWm?.addView(ov.rootView, lp)
            ov.ghostPillView?.let { pill ->
                val dm = resources.displayMetrics
                val plp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, winType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (dm.widthPixels - (190 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                    y = (44 * dm.density).toInt()
                }
                fallbackWm?.addView(pill, plp)
                fbPillView = pill; fbPillParams = plp
            }
            mountedViaA11y = false
            true
        } catch (_: Throwable) { false }
    }

    private fun dragGhostPillWindow(dx: Int, dy: Int) {
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.dragGhostPillBy(dx, dy)
        } else {
            val v = fbPillView ?: return; val lp = fbPillParams ?: return
            val dm = resources.displayMetrics
            lp.x = (lp.x + dx).coerceIn(0, (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0))
            lp.y = (lp.y + dy).coerceIn((20 * dm.density).toInt(), (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0))
            try { fallbackWm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun mountMicroPopupWindow(view: View): Boolean {
        if (mountedViaA11y) {
            return GazeAccessibilityService.activeInstance?.mountMicroPopupOverlay(view) == true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = Random.nextInt(12, (dm.widthPixels - (225 * dm.density).toInt()).coerceAtLeast(24))
                y = Random.nextInt((115 * dm.density).toInt(), (dm.heightPixels - (240 * dm.density).toInt()).coerceAtLeast((140 * dm.density).toInt()))
            }
            fallbackWm?.addView(view, lp); fbMicroPopupView = view; true
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun mountDeadZoneWindow(view: View): Boolean {
        if (mountedViaA11y) {
            return GazeAccessibilityService.activeInstance?.mountDeadZoneOverlay(view) == true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val dm = resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                (dm.widthPixels * 0.68f).toInt(), (170 * dm.density).toInt(), winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER; y = (45 * dm.density).toInt() }
            fallbackWm?.addView(view, lp); fbDeadZoneView = view; true
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun mountPortalWindow(view: View, forceMaxBrightness: Boolean): Boolean {
        if (mountedViaA11y) {
            val a11y = GazeAccessibilityService.activeInstance
            if (a11y != null && a11y.mountPortalOverlay(view, forceMaxBrightness)) return true
        }
        return try {
            removePortalWindow()
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, winType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenBrightness = if (forceMaxBrightness) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            fallbackWm?.addView(view, lp); fbPortalView = view; true
        } catch (_: Throwable) { false }
    }

    private fun removePortalWindow() {
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removePortalOverlay()
        fbPortalView?.let { v -> fbPortalView = null; try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
    }

    private fun onSessionFinishedNaturally() {
        sessionCompleted = true
        mainHandler.removeCallbacks(tickRunnable)
        dismissMicroPopup(); dismissDeadZone()
        tracker?.stop()
        hypnoticAudio?.stop(); hypnoticAudio = null
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        val fSec = (focusedMs / 1000L).toInt()
        val streakSec = (bestStreakMs / 1000L).toInt()
        gazeConfig.recordCompletedSession(fSec, lookAwayEpisodes, streakSec)
        vibrateShort(110L)
        if (activeCfg?.voiceAlertEnabled == true && ttsReady) {
            speakAlertFrench("Session terminée ! Bravo pour ton attention.")
        }
        overlay?.showCompleted(fSec, lookAwayEpisodes, streakSec)
        updateForegroundNotification("✨ Session GazeLock AI terminée !")
    }

    private fun stopSessionCleanly(earlyEmergencyAbort: Boolean) {
        mainHandler.removeCallbacks(tickRunnable)
        val cfg = activeCfg
        // #18 Time Debt Calculation on early abort via 3-minute unlock portal!
        if (earlyEmergencyAbort && cfg != null && !cfg.infiniteUntilUnlock && cfg.timeDebtEnabled && remainingMs > 10_000L) {
            val addDebtSec = ((remainingMs / 1000L) * cfg.timeDebtCarryPct / 100L).toInt()
            if (addDebtSec > 0) {
                val totalDebt = gazeConfig.addPendingTimeDebt(addDebtSec)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "💸 Dette de Temps : +${addDebtSec / 60}m${addDebtSec % 60}s (Total : ${totalDebt / 60}m${totalDebt % 60}s reporté !)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        gazeConfig.clearActiveSessionCheckpoint()

        isSessionRunning = false
        if (activeInstance === this) activeInstance = null
        hijackActive = false; hijackView = null
        dismissMicroPopup(); dismissDeadZone()
        tracker?.shutdown(); tracker = null
        hypnoticAudio?.stop(); hypnoticAudio = null
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}

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
        dismissMicroPopup(); dismissDeadZone()
        tracker?.shutdown(); tracker = null
        hypnoticAudio?.stop(); hypnoticAudio = null
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        overlay?.release(); overlay = null
        try { GazeAccessibilityService.activeInstance?.removeAllOverlays() } catch (_: Throwable) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        notifyListeners()
        super.onDestroy()
    }

    private fun speakAlertFrench(text: String) {
        try {
            tts?.setPitch(1.0f); tts?.setSpeechRate(1.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gaze_alert")
        } catch (_: Throwable) {}
    }

    /** #23 Deep slow hypnotic voice whispers for custom mantras! */
    private fun speakHypnoticWhisper(mantra: String) {
        try {
            tts?.setPitch(0.72f); tts?.setSpeechRate(0.76f)
            tts?.speak(mantra.lowercase(Locale.FRENCH), TextToSpeech.QUEUE_FLUSH, null, "gaze_whisper")
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else vib.vibrate(ms)
        } catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GazeLock AI — Session Active", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(content: String): Notification {
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("👁️ GazeLock AI v4.0 ULTRA")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun updateForegroundNotification(customText: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cfg = activeCfg
        val txt = customText ?: run {
            val prefix = if (cfg?.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Ghost ${dynamicOpacityPct.toInt()}%" else "🔒 Verrouillé"
            when {
                cfg?.infiniteUntilUnlock == true -> "$prefix — ∞ (Portail 3 min requis)"
                cfg?.blindTimerEnabled == true -> "$prefix — ??:?? (Chrono Aveugle)"
                else -> {
                    val s = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
                    val status = if (latestGaze.isLookingAtScreen) "👁️ Regard OK" else "⏸️ Pause"
                    String.format("%s · %s — %02d:%02d", prefix, status, s / 60, s % 60)
                }
            }
        }
        try { nm.notify(NOTIF_ID, buildNotification(txt)) } catch (_: Throwable) {}
    }

    companion object {
        private const val CHANNEL_ID = "gazelock_session_ch"
        private const val NOTIF_ID = 4105
        const val ACTION_STOP = "com.gazelock.app.STOP_SESSION"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_RESUMED_CRASH = "extra_resumed_crash"

        @Volatile
        var isSessionRunning: Boolean = false
            private set

        @Volatile
        private var activeInstance: GazeSessionService? = null

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) { listeners.add(l) }
        fun removeStateListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() { for (l in listeners) try { l() } catch (_: Throwable) {} }

        fun isCurrentSessionGhostMode(): Boolean = activeInstance?.activeCfg?.overlayMode == OverlayMode.GHOST_PASSTHROUGH
        fun isVolumeLockCurrentlyActive(): Boolean = activeInstance?.activeCfg?.volumeLockEnabled == true
        fun enforceVolumeFloorNow() { activeInstance?.mainHandler?.post { activeInstance?.enforceVolumeFloorInternal() } }
        fun triggerEscapeAttemptPenalty() { activeInstance?.applyEscapeAttemptPenaltyInternal() }
        fun openUnlockPortalOverlay() { activeInstance?.openUnlockPortalInternal() }

        fun startSession(context: Context, overrideDurationSec: Int = -1, resumedFromCrash: Boolean = false) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply {
                if (overrideDurationSec > 0) putExtra(EXTRA_DURATION_SEC, overrideDurationSec)
                if (resumedFromCrash) putExtra(EXTRA_RESUMED_CRASH, true)
            }
            ContextCompat.startForegroundService(context.applicationContext, i)
        }

        fun stopSession(context: Context) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply { action = ACTION_STOP }
            try { context.applicationContext.startService(i) } catch (_: Throwable) {}
        }
    }
}
