package com.gazelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.gazelock.app.audio.PavlovSoundEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.OverlayMode
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AttentionHijackOverlayView
import com.gazelock.app.overlay.FinalBossOverlayView
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
 * Foreground Session Orchestrator for GazeLock AI v5.0 APOCALYPSE:
 * - Includes ALL v1..v4 features PLUS:
 *   #11 Anti-Blink Streak Challenge (+15s & +8% opacity on blink!)
 *   #21 App-Trap Ambush trigger banner & post-session cooldown
 *   #29 Ghost Quick Settings / Shade blocking (in GazeAccessibilityService)
 *   #30 App-Switch Tax (>3 app switches/min -> +10s & +5% opacity!)
 *   #36 Pavlovian Audio Conditioning (chime every 30s streak, harsh buzz on faults!)
 *   #51 4x4 Hell Puzzle + #54 9 img/s Flash Target Hunt + #70 Daily Escalating Portal (3m->6m!)
 *   #76 Final 30s Zero-Fault Boss (100% locked, fail >1s = +60s!)
 */
class GazeSessionService : LifecycleService(), SensorEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var activeCfg: GazeConfigSnapshot? = null
    private var tracker: GazeTrackerEngine? = null
    private var overlay: GazeVideoFeedOverlay? = null
    private var hypnoticAudio: HypnoticAudioEngine? = null
    private var sensorManager: SensorManager? = null

    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false
    private var fbPillView: View? = null
    private var fbPillParams: WindowManager.LayoutParams? = null
    private var fbMicroPopupView: View? = null
    private var fbDeadZoneView: View? = null
    private var fbPortalView: View? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Core Runtime
    private var initialDurationMs = 300_000L
    private var totalDurationMs = 300_000L
    private var remainingMs = 300_000L
    private var focusedMs = 0L
    private var totalAwayMs = 0L
    private var sessionEscapesBlocked = 0
    private var currentStreakMs = 0L
    private var bestStreakMs = 0L
    private var lastPavlovMilestoneSec = 0
    private var lookAwayEpisodes = 0
    private var progressiveStep = 0
    private var currentAwayMs = 0L
    private var penaltyAppliedForEpisode = false
    private var lastVoiceAlertMs = 0L
    private var lastVibrateMs = 0L
    private var lastNotifUpdateMs = 0L
    private var lastCheckpointSaveMs = 0L
    private var lastWatchdogMs = 0L
    private var lastDrowsyAlarmMs = 0L
    private var lastCamCoverAlarmMs = 0L
    private var lastHeartbeatHapticMs = 0L
    private var nextFreezeShockElapsedMs = 0L
    private var screenOffPendingTax = false
    private var screenReceiverRegistered = false
    private var lockedVolumeTarget = -1
    private var sessionCompleted = false
    private var lastPenaltyBanner: String = ""
    private var lastPenaltyBannerUntilMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            val cfg = activeCfg ?: return
            if (!isSessionRunning || sessionCompleted || !cfg.screenOffPenaltyEnabled) return
            if (act == Intent.ACTION_SCREEN_OFF) {
                screenOffPendingTax = true
            } else if ((act == Intent.ACTION_SCREEN_ON || act == Intent.ACTION_USER_PRESENT) && screenOffPendingTax) {
                screenOffPendingTax = false
                mainHandler.post { applyScreenOffTaxInternal() }
            }
        }
    }

    // #1 Living Opacity
    private var dynamicOpacityPct = 48f
    private var opacityCreepAccumMs = 0L
    private var opacityCooldownAccumMs = 0L

    // #2 Attention Hijack
    private var hijackActive = false
    private var hijackView: AttentionHijackOverlayView? = null
    private var hijackFocusedMs = 0L
    private var hijackAwayGraceMs = 0L
    private var hijackRequiredMs = 14_000L
    private var nextHijackElapsedMs = 0L
    private var hijackWarned = false

    // #5 Ghost Micro-Popups
    private var activeMicroPopup: GhostMicroPopupView? = null
    private var nextMicroPopupElapsedMs = 0L

    // #9 Confiscated Touch Dead-Zone
    private var activeDeadZone: TouchDeadZoneView? = null
    private var deadZoneRemainingMs = 0L
    private val deadZoneTotalMs = 18_000L

    // #11 Anti-Blink Challenge
    private var noBlinkActive = false
    private var noBlinkWarned = false
    private var noBlinkRemainingMs = 0L
    private var nextNoBlinkElapsedMs = 0L

    // #76 Final 30s Zero-Fault Boss
    private var finalBossActive = false
    private var finalBossView: FinalBossOverlayView? = null
    private var bossAwayMs = 0L
    private val finalBossTotalMs = 30_000L

    // #23 Whispers & #25 Anti-Flat
    private var nextWhisperElapsedMs = 0L
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

            // #3 Self-Healing Z-Order Overlay Watchdog every 1.5s
            if (cfg.overlayWatchdogEnabled && now - lastWatchdogMs >= 1_500L) {
                lastWatchdogMs = now
                if (mountedViaA11y) {
                    val a11y = GazeAccessibilityService.activeInstance
                    if (a11y != null) {
                        if (!a11y.isVideoOverlayAttached()) {
                            a11y.mountVideoOverlay(ov.rootView, passThroughTouches = ov.isGhostMode, forceMaxBrightness = cfg.forceMaxBrightness, flagSecure = !ov.isGhostMode && cfg.portalFlagSecureEnabled)
                        } else {
                            a11y.bringOverlaysToFront()
                        }
                    }
                }
            }

            // #12 Escalating Strictness: progressively tightens tolerances every 2 min or 3 faults!
            if (cfg.escalatingStrictnessEnabled) {
                val tight = ((focusedMs / 120_000L).toInt() + (lookAwayEpisodes / 3)).coerceIn(0, 8)
                tracker?.setTightenLevel(tight)
            }

            if (cfg.rebootSurvivalEnabled && now - lastCheckpointSaveMs >= 4_000L) {
                lastCheckpointSaveMs = now
                gazeConfig.saveActiveSessionCheckpoint((remainingMs / 1000L).toInt(), cfg.infiniteUntilUnlock)
            }

            // Pause session countdown while inside the Overlay Unlock Portal!
            if (ov.isAccessWaitActive) {
                dismissMicroPopup()
                dismissDeadZone()
                noBlinkActive = false
                hypnoticAudio?.setPaused(false)
                mainHandler.postDelayed(this, 100L)
                return
            }

            // #25 Anti-Flat Table Detection
            if (cfg.antiFlatSensorEnabled && accelFlatNow) flatAccumMs += 100L else flatAccumMs = 0L
            val phoneFlatPenalty = cfg.antiFlatSensorEnabled && flatAccumMs >= 6_000L
            val flatReason = if (phoneFlatPenalty) "📐 Téléphone posé à plat — Reprends-le en main !" else null

            val gaze = latestGaze
            val effectivelyLooking = gaze.isLookingAtScreen && !phoneFlatPenalty

            // ==================== #76 FINAL 30s ZERO-FAULT BOSS LOOP ====================
            if (finalBossActive) {
                if (effectivelyLooking) {
                    bossAwayMs = 0L
                    remainingMs = (remainingMs - 100L).coerceAtLeast(0L)
                    focusedMs += 100L
                    currentStreakMs += 100L
                    if (currentStreakMs > bestStreakMs) bestStreakMs = currentStreakMs
                } else {
                    bossAwayMs += 100L
                    if (bossAwayMs >= 1_000L) {
                        failFinalBoss(cfg, ov, now)
                        mainHandler.postDelayed(this, 100L)
                        return
                    }
                }
                finalBossView?.updateBoss(remainingMs, finalBossTotalMs, effectivelyLooking, bossAwayMs, flatReason ?: gaze.reason)
                if (remainingMs <= 0L) {
                    vanquishFinalBossAndFinish(cfg)
                } else {
                    mainHandler.postDelayed(this, 100L)
                }
                return
            }

            // ==================== #2 ATTENTION HIJACK LOOP ====================
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
                        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                    }
                }
                hijackView?.updateHijackState(hijackFocusedMs, hijackRequiredMs, effectivelyLooking, flatReason ?: gaze.reason)
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

                // #36 Pavlovian Reward Chime every 30s of unbroken perfect focus!
                val streakSec = (currentStreakMs / 1000L).toInt()
                if (cfg.pavlovSoundEnabled && streakSec >= 30 && streakSec / 30 > lastPavlovMilestoneSec) {
                    lastPavlovMilestoneSec = streakSec / 30
                    PavlovSoundEngine.playRewardChime()
                }

                hypnoticAudio?.setPaused(false)
                ov.checkMaxClipSwitch()

                // #1 Living Opacity creep & focus cooldown + #15 Breathing Wave
                val breathDelta = if (ov.isGhostMode && cfg.ghostBreathingOpacityEnabled) {
                    (kotlin.math.sin(now / 1590.0) * 7.0).toFloat()
                } else 0f
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
                        }
                    }
                    ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), breathingWaveDelta = breathDelta)
                } else if (ov.isGhostMode) {
                    ov.setDynamicGhostOpacity(cfg.ghostOpacityPct, breathingWaveDelta = breathDelta)
                }

                // #17 Dynamic Video Playback Speed (0.86x deep trance -> 1.30x high pressure!)
                if (cfg.dynamicPlaybackSpeedEnabled) {
                    val speedGoal = when {
                        dynamicOpacityPct >= 80f -> 1.28f
                        currentStreakMs >= 25_000L -> 0.86f
                        else -> 1.0f
                    }
                    ov.setDynamicPlaybackSpeed(speedGoal)
                }

                // #19 Periodic Snap-Freeze Photo-Bombe Shock
                if (cfg.freezeFrameShockEnabled && now >= nextFreezeShockElapsedMs && !hijackActive && !finalBossActive) {
                    nextFreezeShockElapsedMs = now + Random.nextLong(38_000L, 62_000L)
                    ov.triggerFreezeFrameShock()
                    vibrateShort(75L)
                }

                // #20 Heartbeat Haptic Pulse when high intensity (opacity >= 75%)
                if (cfg.heartbeatHapticsEnabled && dynamicOpacityPct >= 75f && now - lastHeartbeatHapticMs >= 2_200L) {
                    lastHeartbeatHapticMs = now
                    vibrateHeartbeat()
                }

                // #9 Shrink Dead-Zone
                if (activeDeadZone != null) {
                    deadZoneRemainingMs = (deadZoneRemainingMs - 100L).coerceAtLeast(0L)
                    activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = true)
                    if (deadZoneRemainingMs <= 0L) {
                        dismissDeadZone()
                        showBanner("✅ Zone tactile libérée !", now, 2_400L)
                        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
                    }
                }

                // #23 Deep Hypnotic TTS Whispers
                if (cfg.ttsMantraWhisperEnabled && ttsReady && now >= nextWhisperElapsedMs && now - lastVoiceAlertMs > 6_000L) {
                    nextWhisperElapsedMs = now + Random.nextLong(24_000L, 40_000L)
                    speakHypnoticWhisper(cfg.mantraList().random())
                }
            } else {
                if (currentAwayMs == 0L) {
                    lookAwayEpisodes++
                    currentStreakMs = 0L
                    lastPavlovMilestoneSec = 0
                    opacityCooldownAccumMs = 0L
                    penaltyAppliedForEpisode = false
                }
                currentAwayMs += 100L
                totalAwayMs += 100L

                // #9 Camera Cover / Finger Over Lens Double Penalty & Siren!
                if (gaze.cameraCovered && now - lastCamCoverAlarmMs >= 4_500L) {
                    lastCamCoverAlarmMs = now
                    ov.triggerFaultGlitch()
                    vibrateShort(220L)
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playSirenAlert()
                    if (!cfg.infiniteUntilUnlock) {
                        remainingMs += 10_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
                    }
                    if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +12f, now, null)
                    showBanner("🚫 CAMÉRA OBSTRUÉE ! +10 s · Opacité +12 % !", now, 4_500L)
                }

                // #11 Micro-Sleep / Heavy Eyelids Wake-Up Alarm!
                if (gaze.drowsyDetected && now - lastDrowsyAlarmMs >= 4_000L) {
                    lastDrowsyAlarmMs = now
                    ov.triggerFaultGlitch()
                    vibrateHeartbeat()
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playSirenAlert()
                    showBanner("😴 SOMNOLENCE DÉTECTÉE — OUVRE GRAND LES YEUX !", now, 3_600L)
                }

                if (ov.isGhostMode && phoneFlatPenalty) ov.setDynamicGhostOpacity(90, intenseSpiral = true)

                if (activeDeadZone != null) {
                    deadZoneRemainingMs = (deadZoneRemainingMs + 200L).coerceAtMost(22_000L)
                    activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false)
                }

                if (cfg.vibrateAlertEnabled && now - lastVibrateMs > 1_800L) {
                    lastVibrateMs = now; vibrateShort(70L)
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
                    ov.triggerFaultGlitch()
                    vibrateShort(150L)
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()

                    if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +6f, now, banner = null)
                    if (ov.isGhostMode && cfg.deadZonesEnabled) spawnOrRefreshDeadZone()

                    if (!cfg.infiniteUntilUnlock && (nextPen > 0 || cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH)) {
                        if (cfg.penaltyMode == PenaltyMode.SUDDEN_DEATH) {
                            remainingMs = initialDurationMs; totalDurationMs = initialDurationMs
                            showBanner("💀 MORT SUBITE ! Chrono remis à ${initialDurationMs / 1000L} s !", now, 5_000L)
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakAlertFrench("Zéro faute échoué ! Chronomètre remis à zéro !"); lastVoiceAlertMs = now
                            }
                        } else {
                            remainingMs += nextPen * 1000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
                            progressiveStep++
                            val modeTag = if (cfg.penaltyMode == PenaltyMode.PROGRESSIVE) "📈 Pénalité Progressive" else "🚨 Pénalité"
                            showBanner("$modeTag : +$nextPen s ajoutées !", now, 4_500L)
                            if (cfg.voiceAlertEnabled && ttsReady) {
                                speakAlertFrench("Pénalité ! Plus $nextPen secondes !"); lastVoiceAlertMs = now
                            }
                        }
                    }
                }
            }

            // ==================== #11 ANTI-BLINK CHALLENGE ENGINE ====================
            var noBlinkOverrideBanner = ""
            if (cfg.noBlinkChallengeEnabled && !hijackActive && !finalBossActive) {
                if (noBlinkActive) {
                    val blinkedOrAway = !effectivelyLooking || (gaze.faceDetected && gaze.eyeOpenAvg < 0.38f)
                    if (blinkedOrAway) {
                        noBlinkActive = false
                        scheduleNextNoBlink(now)
                        vibrateShort(200L)
                        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                        if (!cfg.infiniteUntilUnlock) {
                            remainingMs += 15_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
                        }
                        if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +8f, now, null)
                        showBanner("🚨 CLIGNEMENT DÉTECTÉ ! +15 s · Opacité +8 % !", now, 4_500L)
                        if (cfg.voiceAlertEnabled && ttsReady) { speakAlertFrench("Tu as cligné des yeux ! Plus quinze secondes !"); lastVoiceAlertMs = now }
                    } else {
                        noBlinkRemainingMs = (noBlinkRemainingMs - 100L).coerceAtLeast(0L)
                        noBlinkOverrideBanner = String.format("👁️ NE CLIGNE PAS ! %.1f s · Yeux %d%%", noBlinkRemainingMs / 1000f, (gaze.eyeOpenAvg * 100).toInt())
                        if (noBlinkRemainingMs <= 0L) {
                            noBlinkActive = false
                            scheduleNextNoBlink(now)
                            if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
                            showBanner("✅ DÉFI ANTI-CLIGNEMENT RÉUSSI !", now, 3_200L)
                        }
                    }
                } else if (now >= nextNoBlinkElapsedMs - 2_500L && (cfg.infiniteUntilUnlock || remainingMs > 45_000L)) {
                    if (!noBlinkWarned) {
                        noBlinkWarned = true
                        vibrateShort(70L)
                        showBanner("👁️ PRÉPARE-TOI : DÉFI ANTI-CLIGNEMENT (${cfg.noBlinkDurationSec} s) !", now, 2_500L)
                    }
                    if (now >= nextNoBlinkElapsedMs) {
                        noBlinkActive = true
                        noBlinkWarned = false
                        noBlinkRemainingMs = cfg.noBlinkDurationSec * 1000L
                    }
                }
            }

            // ==================== #76 TRIGGER FINAL 30s ZERO-FAULT BOSS ====================
            if (!cfg.infiniteUntilUnlock && cfg.finalBossEnabled && initialDurationMs >= 45_000L &&
                !finalBossActive && !hijackActive && remainingMs in 1_000L..finalBossTotalMs
            ) {
                startFinalBoss(cfg, ov)
                mainHandler.postDelayed(this, 100L)
                return
            }

            // ==================== #2 SCHEDULED ATTENTION HIJACK ====================
            if (ov.isGhostMode && cfg.attentionHijackEnabled && !hijackActive && !noBlinkActive &&
                (cfg.infiniteUntilUnlock || remainingMs > 42_000L) && now >= nextHijackElapsedMs - 3_000L
            ) {
                if (!hijackWarned) {
                    hijackWarned = true; vibrateShort(120L)
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

            val bannerToShow = if (noBlinkOverrideBanner.isNotBlank()) noBlinkOverrideBanner
            else if (now < lastPenaltyBannerUntilMs) lastPenaltyBanner else ""

            val focusScorePct = if (focusedMs + totalAwayMs > 0L) {
                ((focusedMs * 100L) / (focusedMs + totalAwayMs)).toInt().coerceIn(0, 100)
            } else 100

            ov.updateHud(
                remainingMs = remainingMs,
                totalDurationMs = totalDurationMs,
                gaze = gaze,
                lookAwayDurationMs = currentAwayMs,
                penaltyAppliedCount = lookAwayEpisodes,
                nextPenaltySec = computeNextPenaltySec(cfg),
                penaltyMode = cfg.penaltyMode,
                flashBanner = bannerToShow,
                overrideReason = flatReason,
                focusScorePct = focusScorePct
            )

            if (now - lastNotifUpdateMs >= 2_000L) {
                lastNotifUpdateMs = now; updateForegroundNotification()
            }

            if (!cfg.infiniteUntilUnlock && remainingMs <= 0L) {
                onSessionFinishedNaturally()
            } else {
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    // ==================== #76 FINAL BOSS LIFECYCLE ====================
    private fun startFinalBoss(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        if (finalBossActive || ov.isAccessWaitActive || sessionCompleted) return
        dismissMicroPopup(); dismissDeadZone(); noBlinkActive = false
        finalBossActive = true
        bossAwayMs = 0L
        ov.setDynamicGhostOpacity(100, intenseSpiral = true)
        ov.setDynamicPlaybackSpeed(1.32f)
        hypnoticAudio?.setOverloadSprint(true)
        vibrateHeartbeat()
        if (cfg.voiceAlertEnabled && ttsReady) {
            speakAlertFrench("Boss Final ! Trente secondes zéro faute !")
            lastVoiceAlertMs = SystemClock.elapsedRealtime()
        }
        val bv = FinalBossOverlayView(applicationContext) {
            cancelBossForPortal(cfg, ov)
            ov.openUnlockPortal()
        }
        finalBossView = bv
        if (!mountPortalWindow(bv, cfg.forceMaxBrightness, flagSecure = cfg.portalFlagSecureEnabled)) {
            finalBossActive = false; finalBossView = null
            hypnoticAudio?.setOverloadSprint(false)
        }
    }

    private fun failFinalBoss(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, now: Long) {
        finalBossActive = false
        finalBossView = null
        removePortalWindow()
        hypnoticAudio?.setOverloadSprint(false)
        remainingMs += 60_000L
        totalDurationMs = maxOf(totalDurationMs, remainingMs)
        dynamicOpacityPct = (cfg.ghostOpacityPct + 14).coerceAtMost(85).toFloat()
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), intenseSpiral = false)
        vibrateShort(260L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
        if (cfg.voiceAlertEnabled && ttsReady) {
            speakAlertFrench("Boss Final échoué ! Plus soixante secondes !")
            lastVoiceAlertMs = now
        }
        showBanner("💀 BOSS FINAL ÉCHOUÉ ! +60 s AJOUTÉES !", now, 6_000L)
    }

    private fun vanquishFinalBossAndFinish(cfg: GazeConfigSnapshot) {
        finalBossActive = false
        finalBossView = null
        removePortalWindow()
        hypnoticAudio?.setOverloadSprint(false)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
        onSessionFinishedNaturally()
    }

    private fun cancelBossForPortal(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        finalBossActive = false; finalBossView = null
        removePortalWindow()
        hypnoticAudio?.setOverloadSprint(false)
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), intenseSpiral = false)
    }

    private fun scheduleNextNoBlink(now: Long) {
        nextNoBlinkElapsedMs = now + Random.nextLong(68_000L, 115_000L)
        noBlinkWarned = false
    }

    // ==================== #1 LIVING OPACITY ====================
    private fun bumpLivingOpacity(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, delta: Float, now: Long, banner: String?) {
        if (!ov.isGhostMode || !cfg.livingOpacityEnabled) return
        dynamicOpacityPct = (dynamicOpacityPct + delta).coerceIn(cfg.ghostOpacityPct.toFloat(), 96f)
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt())
        if (banner != null) showBanner(banner, now, 3_800L)
        if (dynamicOpacityPct >= 95f && !hijackActive && !finalBossActive && !ov.isAccessWaitActive) {
            startAttentionHijack(cfg, ov)
        }
    }

    // ==================== #2 ATTENTION HIJACK & #21 INDUCTION TUNNEL ====================
    private fun startAttentionHijack(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, overrideFocusSec: Int = -1) {
        if (hijackActive || finalBossActive || ov.isAccessWaitActive || sessionCompleted) return
        dismissMicroPopup(); dismissDeadZone(); noBlinkActive = false
        hijackActive = true; hijackWarned = false
        hijackFocusedMs = 0L; hijackAwayGraceMs = 0L
        val reqSec = if (overrideFocusSec > 0) overrideFocusSec else cfg.attentionHijackFocusSec.coerceIn(8, 25)
        hijackRequiredMs = reqSec * 1000L

        ov.setDynamicGhostOpacity(96, intenseSpiral = true)
        vibrateShort(180L)
        if (cfg.voiceAlertEnabled && ttsReady) {
            val spoken = if (overrideFocusSec > 0) "Tunnel d'induction ! Fixe l'écran dix secondes !"
            else "Coupure d'emprise ! Fixe l'écran pour récupérer le tactile !"
            speakAlertFrench(spoken)
            lastVoiceAlertMs = SystemClock.elapsedRealtime()
        }

        val hv = AttentionHijackOverlayView(applicationContext, reqSec) {
            cancelHijackForPortal(cfg, ov)
            ov.openUnlockPortal()
        }
        hijackView = hv
        if (!mountPortalWindow(hv, cfg.forceMaxBrightness, flagSecure = cfg.portalFlagSecureEnabled)) {
            hijackActive = false; hijackView = null
            ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt())
        }
    }

    private fun endAttentionHijack(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay, now: Long) {
        hijackActive = false; hijackView = null
        removePortalWindow()
        dynamicOpacityPct = (cfg.ghostOpacityPct + 6).coerceAtMost(70).toFloat()
        ov.setDynamicGhostOpacity(dynamicOpacityPct.toInt(), intenseSpiral = false)
        scheduleNextHijack(cfg, now)
        vibrateShort(90L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
        showBanner("✅ Emprise levée — Tactile rendu (${dynamicOpacityPct.toInt()} %) !", now, 3_200L)
    }

    private fun cancelHijackForPortal(cfg: GazeConfigSnapshot, ov: GazeVideoFeedOverlay) {
        hijackActive = false; hijackView = null
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
        val mp = GhostMicroPopupView(
            context = applicationContext,
            thumbnail = ov.pickRandomFolderThumbnail(),
            mantra = cfg.mantraList().random(),
            durationMs = 6_500L,
            onAcknowledged = { dismissMicroPopup() },
            onTimedOut = {
                dismissMicroPopup()
                vibrateShort(130L)
                if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                val now = SystemClock.elapsedRealtime()
                if (cfg.livingOpacityEnabled) {
                    bumpLivingOpacity(cfg, ov, +6f, now, "⚠️ Micro-pop-up ignoré ! Opacité montée à ${(dynamicOpacityPct + 6f).toInt().coerceAtMost(96)} % !")
                } else {
                    if (!cfg.infiniteUntilUnlock) { remainingMs += 10_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs) }
                    showBanner("⚠️ Micro-pop-up ignoré ! +10 s ajoutées !", now, 3_500L)
                }
            }
        )
        if (mountMicroPopupWindow(mp)) { activeMicroPopup = mp; vibrateShort(45L) } else mp.release()
    }

    private fun dismissMicroPopup() {
        activeMicroPopup?.release(); activeMicroPopup = null
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeMicroPopupOverlay()
        fbMicroPopupView?.let { v -> fbMicroPopupView = null; try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
    }

    // ==================== #9 CONFISCATED TOUCH DEAD-ZONE ====================
    private fun spawnOrRefreshDeadZone() {
        deadZoneRemainingMs = deadZoneTotalMs
        if (activeDeadZone != null) {
            activeDeadZone?.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false); return
        }
        val dz = TouchDeadZoneView(applicationContext)
        dz.updateRemaining(deadZoneRemainingMs, deadZoneTotalMs, isLooking = false)
        if (mountDeadZoneWindow(dz)) activeDeadZone = dz
    }

    private fun dismissDeadZone() {
        activeDeadZone = null; deadZoneRemainingMs = 0L
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeDeadZoneOverlay()
        fbDeadZoneView?.let { v -> fbDeadZoneView = null; try { fallbackWm?.removeViewImmediate(v) } catch (_: Throwable) {} }
    }

    private fun showBanner(text: String, now: Long, durMs: Long) {
        lastPenaltyBanner = text; lastPenaltyBannerUntilMs = now + durMs
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
            stopSessionCleanly(earlyEmergencyAbort = false); return START_NOT_STICKY
        }
        if (isSessionRunning) return START_STICKY

        val cfg = gazeConfig.snapshot()
        val overrideDur = intent?.getIntExtra(EXTRA_DURATION_SEC, -1) ?: -1
        val resumedFromCrash = intent?.getBooleanExtra(EXTRA_RESUMED_CRASH, false) ?: false
        val fromTimeBomb = intent?.getBooleanExtra(EXTRA_FROM_TIME_BOMB, false) ?: false
        val trapPkg = intent?.getStringExtra(EXTRA_TRAP_PKG).orEmpty()
        var durSec = if (overrideDur >= 15) overrideDur else cfg.durationSec

        val appliedDebtSec = if (!cfg.infiniteUntilUnlock && cfg.timeDebtEnabled && !resumedFromCrash && cfg.pendingTimeDebtSec > 0) {
            durSec += cfg.pendingTimeDebtSec; cfg.pendingTimeDebtSec
        } else 0

        createChannel()
        val notif = buildNotification(
            if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Overlay Ghost GazeLock AI v7.0 TITAN…"
            else "👁️ Démarrage GazeLock AI v7.0 TITAN…"
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
            stopSelf(); return START_NOT_STICKY
        }

        val now = SystemClock.elapsedRealtime()
        activeCfg = cfg
        initialDurationMs = durSec * 1000L; totalDurationMs = initialDurationMs; remainingMs = initialDurationMs
        focusedMs = 0L; totalAwayMs = 0L; sessionEscapesBlocked = 0
        currentStreakMs = 0L; bestStreakMs = 0L; lastPavlovMilestoneSec = 0
        lookAwayEpisodes = 0; progressiveStep = 0; currentAwayMs = 0L; penaltyAppliedForEpisode = false
        dynamicOpacityPct = cfg.ghostOpacityPct.toFloat(); opacityCreepAccumMs = 0L; opacityCooldownAccumMs = 0L
        hijackActive = false; hijackView = null; hijackWarned = false
        finalBossActive = false; finalBossView = null; bossAwayMs = 0L
        noBlinkActive = false; noBlinkWarned = false
        activeMicroPopup = null; activeDeadZone = null; deadZoneRemainingMs = 0L
        accelFlatNow = false; flatAccumMs = 0L; screenOffPendingTax = false
        lastWatchdogMs = now; lastDrowsyAlarmMs = 0L; lastCamCoverAlarmMs = 0L; lastHeartbeatHapticMs = 0L
        nextFreezeShockElapsedMs = now + Random.nextLong(35_000L, 55_000L)
        sessionCompleted = false

        scheduleNextHijack(cfg, now)
        scheduleNextMicroPopup(cfg, now)
        scheduleNextNoBlink(now)
        nextWhisperElapsedMs = now + Random.nextLong(16_000L, 28_000L)

        when {
            fromTimeBomb -> {
                showBanner("💣 BOMBE À RETARDEMENT DÉCLENCHÉE ! Session surprise lancée !", now, 6_000L)
                if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playSirenAlert()
            }
            trapPkg.isNotBlank() -> {
                showBanner("🪤 APPLI PIÉGÉE DÉTECTÉE ! Session forcée lancée !", now, 5_500L)
                if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
            }
            resumedFromCrash -> showBanner("🔁 MODE IMMORTEL : Session reprise (+45 s d'évasion) !", now, 6_000L)
            appliedDebtSec > 0 -> showBanner("💸 DETTE DE TEMPS APPLIQUÉE : +${appliedDebtSec / 60}m${appliedDebtSec % 60}s !", now, 6_000L)
            else -> { lastPenaltyBanner = ""; lastPenaltyBannerUntilMs = 0L }
        }

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
            onMountPortalOverlay = { portalView -> mountPortalWindow(portalView, cfg.forceMaxBrightness, flagSecure = cfg.portalFlagSecureEnabled) },
            onRemovePortalOverlay = { removePortalWindow() },
            onDragGhostPill = { dx, dy -> dragGhostPillWindow(dx, dy) },
            onIssueAccessCodeAfterWait = { gazeConfig.issueUnlockedAccessCode() },
            onRecordDailyPortalCompletion = { gazeConfig.recordPortalUnlockCompletedToday() },
            onVerifyAccessCode = { code -> gazeConfig.verifyDynamicAccessCode(code) },
            onPortalCancelled = { applyPortalCancelTaxInternal() },
            onEmergencyExit = { stopSessionCleanly(earlyEmergencyAbort = true) },
            onSessionCompleteDismiss = { stopSessionCleanly(earlyEmergencyAbort = false) }
        )

        val mounted = mountPrimaryOverlays(ov, cfg)
        if (!mounted) {
            ov.release()
            mainHandler.post { Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (recommandé) ou Superposition !", Toast.LENGTH_LONG).show() }
            stopSelf(); return START_NOT_STICKY
        }

        overlay = ov; activeInstance = this; isSessionRunning = true; notifyListeners()
        if (cfg.rebootSurvivalEnabled) gazeConfig.saveActiveSessionCheckpoint(durSec, cfg.infiniteUntilUnlock)
        if (cfg.binauralBeatEnabled) hypnoticAudio = HypnoticAudioEngine().apply { start(cfg.binauralVolumePct, cfg.binauralFreqHz) }
        if (cfg.screenOffPenaltyEnabled && !screenReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(screenStateReceiver, filter)
                }
                screenReceiverRegistered = true
            } catch (_: Throwable) {}
        }
        if (cfg.antiFlatSensorEnabled) {
            try {
                sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } catch (_: Throwable) {}
        }

        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { reading -> latestGaze = reading },
            onCameraError = { err -> mainHandler.post { Toast.makeText(applicationContext, "⚠️ $err", Toast.LENGTH_LONG).show() } }
        )
        tracker = tr; tr.start(this, cfg)

        // If launched manually in Ghost mode (not triggered over a trapped app), go Home so user can use phone immediately!
        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH && trapPkg.isBlank()) {
            mainHandler.postDelayed({
                try { GazeAccessibilityService.activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            }, 350L)
        }

        // #21 10-Second Induction Tunnel before unlocking Ghost touch!
        if (ov.isGhostMode && cfg.inductionTunnelEnabled && (cfg.infiniteUntilUnlock || durSec >= 45)) {
            mainHandler.postDelayed({
                if (isSessionRunning && !sessionCompleted && !hijackActive && !finalBossActive) {
                    startAttentionHijack(cfg, ov, overrideFocusSec = 10)
                }
            }, 900L)
        }

        mainHandler.postDelayed(tickRunnable, 200L)
        return START_STICKY
    }

    /** #7 Screen-Off Evasion Tax (+20s & +10% opacity when user manually turns screen off during session!) */
    private fun applyScreenOffTaxInternal() {
        val cfg = activeCfg ?: return
        val ov = overlay ?: return
        if (!isSessionRunning || sessionCompleted) return
        val now = SystemClock.elapsedRealtime()
        if (!cfg.infiniteUntilUnlock) {
            remainingMs += 20_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
        }
        if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +10f, now, null)
        ov.triggerFaultGlitch()
        vibrateShort(220L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playSirenAlert()
        showBanner("🛑 ÉCRAN ÉTEINT DÉTECTÉ ! +20 s · Opacité +10 % !", now, 5_500L)
    }

    /** #34a Portal Cancel Tax (+30s & +10% opacity so Cancel cannot be abused as a free pause!) */
    private fun applyPortalCancelTaxInternal() {
        val cfg = activeCfg ?: return
        val ov = overlay ?: return
        if (!isSessionRunning || sessionCompleted || !cfg.portalCancelTaxEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (!cfg.infiniteUntilUnlock) {
            remainingMs += 30_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
        }
        if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +10f, now, null)
        ov.triggerFaultGlitch()
        vibrateShort(180L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
        showBanner("⚠️ PORTAIL ANNULÉ : +30 s · Opacité +10 % !", now, 5_000L)
    }

    /** #30 App-Switch Tax hook from AccessibilityService (>3 app switches/min in Ghost mode!) */
    fun applyAppSwitchTaxInternal() {
        mainHandler.post {
            val cfg = activeCfg ?: return@post
            val ov = overlay ?: return@post
            if (!isSessionRunning || sessionCompleted || hijackActive || finalBossActive || ov.isAccessWaitActive) return@post
            val now = SystemClock.elapsedRealtime()
            if (!cfg.infiniteUntilUnlock) {
                remainingMs += 10_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs)
            }
            if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +5f, now, null)
            ov.triggerFaultGlitch()
            vibrateShort(130L)
            if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
            showBanner("🔀 TAXE CHANGEMENT D'APPLI (>3/min) : +10 s · Opacité +5 % !", now, 4_200L)
        }
    }

    fun applyEscapeAttemptPenaltyInternal(isSplitScreen: Boolean = false) {
        mainHandler.post {
            val cfg = activeCfg ?: return@post
            val ov = overlay ?: return@post
            if (!isSessionRunning || sessionCompleted) return@post
            sessionEscapesBlocked++
            gazeConfig.recordEscapeBlock()
            val now = SystemClock.elapsedRealtime()
            if (!cfg.infiniteUntilUnlock) { remainingMs += 15_000L; totalDurationMs = maxOf(totalDurationMs, remainingMs) }
            if (ov.isGhostMode && cfg.livingOpacityEnabled) bumpLivingOpacity(cfg, ov, +10f, now, null)
            if (ov.isGhostMode && cfg.deadZonesEnabled && !hijackActive && !finalBossActive && !ov.isAccessWaitActive) spawnOrRefreshDeadZone()
            ov.triggerFaultGlitch()
            val msg = when {
                isSplitScreen -> "🛡️ ÉCRAN SCINDÉ / FENÊTRE FLOTTANTE BLOQUÉ ! +15 s · +10 % !"
                ov.isGhostMode -> "🚫 Paramètres verrouillés ! Portail requis (+15 s · Opacité +10 %)"
                else -> "🚨 TENTATIVE D'ÉVASION BLOQUÉE ! +15 s de pénalité !"
            }
            showBanner(msg, now, 5_500L)
            vibrateShort(180L)
            if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
            if (cfg.voiceAlertEnabled && ttsReady) {
                speakAlertFrench(if (isSplitScreen) "Écran scindé interdit ! Plus quinze secondes !" else "Paramètres verrouillés ! Passe par le portail imposé !")
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
                if (finalBossActive) cancelBossForPortal(cfg, ov)
                ov.openUnlockPortal()
            }
        }
    }

    // ==================== MULTI-WINDOW MOUNTING ====================
    @Suppress("DEPRECATION")
    private fun mountPrimaryOverlays(ov: GazeVideoFeedOverlay, cfg: GazeConfigSnapshot): Boolean {
        val isGhost = ov.isGhostMode
        val a11y = GazeAccessibilityService.activeInstance
        if (a11y != null && a11y.mountVideoOverlay(ov.rootView, passThroughTouches = isGhost, forceMaxBrightness = cfg.forceMaxBrightness, flagSecure = !isGhost && cfg.portalFlagSecureEnabled)) {
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
                    x = (dm.widthPixels - (195 * dm.density).toInt()).coerceAtLeast((8 * dm.density).toInt())
                    y = (44 * dm.density).toInt()
                }
                fallbackWm?.addView(pill, plp); fbPillView = pill; fbPillParams = plp
            }
            mountedViaA11y = false; true
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
        if (mountedViaA11y) return GazeAccessibilityService.activeInstance?.mountMicroPopupOverlay(view) == true
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
        if (mountedViaA11y) return GazeAccessibilityService.activeInstance?.mountDeadZoneOverlay(view) == true
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
    private fun mountPortalWindow(view: View, forceMaxBrightness: Boolean, flagSecure: Boolean = true): Boolean {
        if (mountedViaA11y) {
            val a11y = GazeAccessibilityService.activeInstance
            if (a11y != null && a11y.mountPortalOverlay(view, forceMaxBrightness, flagSecure)) return true
        }
        return try {
            removePortalWindow()
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (flagSecure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, winType, flags, PixelFormat.TRANSLUCENT
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

    private fun computeDisciplineGrade(focusPct: Int, lookAways: Int, escapes: Int): String = when {
        focusPct >= 98 && lookAways == 0 && escapes == 0 -> "S+"
        focusPct >= 94 && escapes == 0 -> "S"
        focusPct >= 86 -> "A"
        focusPct >= 75 -> "B"
        focusPct >= 62 -> "C"
        focusPct >= 48 -> "D"
        else -> "F"
    }

    private fun onSessionFinishedNaturally() {
        sessionCompleted = true
        mainHandler.removeCallbacks(tickRunnable)
        dismissMicroPopup(); dismissDeadZone(); noBlinkActive = false
        tracker?.stop()
        hypnoticAudio?.stop(); hypnoticAudio = null
        unregisterScreenReceiverSafe()
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        val fSec = (focusedMs / 1000L).toInt()
        val streakSec = (bestStreakMs / 1000L).toInt()
        val focusPct = if (focusedMs + totalAwayMs > 0L) ((focusedMs * 100L) / (focusedMs + totalAwayMs)).toInt().coerceIn(0, 100) else 100
        val grade = computeDisciplineGrade(focusPct, lookAwayEpisodes, sessionEscapesBlocked)
        gazeConfig.recordCompletedSession(fSec, lookAwayEpisodes, streakSec)
        gazeConfig.saveSessionReport(grade, focusPct, fSec, lookAwayEpisodes, streakSec, sessionEscapesBlocked)
        vibrateShort(110L)
        if (activeCfg?.voiceAlertEnabled == true && ttsReady) speakAlertFrench("Session terminée ! Note $grade ! Bravo pour ton attention.")
        overlay?.showCompleted(grade, focusPct, fSec, lookAwayEpisodes, streakSec, sessionEscapesBlocked)
        updateForegroundNotification("🏆 Session terminée — Note $grade ($focusPct %) !")
    }

    private fun unregisterScreenReceiverSafe() {
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            try { unregisterReceiver(screenStateReceiver) } catch (_: Throwable) {}
        }
    }

    private fun stopSessionCleanly(earlyEmergencyAbort: Boolean) {
        mainHandler.removeCallbacks(tickRunnable)
        unregisterScreenReceiverSafe()
        val cfg = activeCfg
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
        lastSessionEndedMs = System.currentTimeMillis()

        isSessionRunning = false
        if (activeInstance === this) activeInstance = null
        hijackActive = false; hijackView = null
        finalBossActive = false; finalBossView = null
        noBlinkActive = false
        dismissMicroPopup(); dismissDeadZone()
        tracker?.shutdown(); tracker = null
        hypnoticAudio?.stop(); hypnoticAudio = null
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}

        val ov = overlay; overlay = null
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
        unregisterScreenReceiverSafe()
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

    /** #20 Rhythmic double-beat heartbeat haptic pulse ("lub-dub")! */
    @Suppress("DEPRECATION")
    private fun vibrateHeartbeat() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0L, 40L, 95L, 55L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else vib.vibrate(pattern, -1)
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
            .setContentTitle("👁️ GazeLock AI v7.0 TITAN")
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
                cfg?.infiniteUntilUnlock == true -> "$prefix — ∞ (Portail requis)"
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
        const val EXTRA_TRAP_PKG = "extra_trap_pkg"
        const val EXTRA_FROM_TIME_BOMB = "extra_from_time_bomb"

        @Volatile
        var isSessionRunning: Boolean = false
            private set

        @Volatile
        var lastSessionEndedMs: Long = 0L
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
        fun triggerEscapeAttemptPenalty(isSplitScreen: Boolean = false) { activeInstance?.applyEscapeAttemptPenaltyInternal(isSplitScreen) }
        fun triggerAppSwitchTax() { activeInstance?.applyAppSwitchTaxInternal() }
        fun openUnlockPortalOverlay() { activeInstance?.openUnlockPortalInternal() }

        fun startSession(
            context: Context,
            overrideDurationSec: Int = -1,
            resumedFromCrash: Boolean = false,
            trapTriggerPkg: String = "",
            fromTimeBomb: Boolean = false
        ) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply {
                if (overrideDurationSec > 0) putExtra(EXTRA_DURATION_SEC, overrideDurationSec)
                if (resumedFromCrash) putExtra(EXTRA_RESUMED_CRASH, true)
                if (trapTriggerPkg.isNotBlank()) putExtra(EXTRA_TRAP_PKG, trapTriggerPkg)
                if (fromTimeBomb) putExtra(EXTRA_FROM_TIME_BOMB, true)
            }
            ContextCompat.startForegroundService(context.applicationContext, i)
        }

        fun stopSession(context: Context) {
            val i = Intent(context.applicationContext, GazeSessionService::class.java).apply { action = ACTION_STOP }
            try { context.applicationContext.startService(i) } catch (_: Throwable) {}
        }
    }
}
