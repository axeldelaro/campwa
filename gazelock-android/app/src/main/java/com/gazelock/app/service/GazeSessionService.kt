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
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.gazelock.app.ai.GazeTrackerEngine
import com.gazelock.app.ai.GazeTrackerEngine.GazeReading
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.GazeVideoFeedOverlay
import com.gazelock.app.ui.MainActivity
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground Camera + Local AI Gaze Session Service (LifecycleService — Android 8 to 16 compliant!):
 * - Runs CameraX + ML Kit Face Detection in real time on the front camera.
 * - Mounts [GazeVideoFeedOverlay] via [GazeAccessibilityService] (`TYPE_ACCESSIBILITY_OVERLAY`) or
 *   falls back to `TYPE_APPLICATION_OVERLAY`.
 * - Precision 100ms timer loop: counts down ONLY while the user looks at the screen, freezes immediately
 *   when looking away, applies optional look-away time penalty, offline French TTS alert & vibration!
 */
class GazeSessionService : LifecycleService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var activeCfg: GazeConfigSnapshot? = null
    private var tracker: GazeTrackerEngine? = null
    private var overlay: GazeVideoFeedOverlay? = null
    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Session Runtime State
    private var totalDurationMs = 180_000L
    private var remainingMs = 180_000L
    private var focusedMs = 0L
    private var currentStreakMs = 0L
    private var bestStreakMs = 0L
    private var lookAwayEpisodes = 0
    private var currentAwayMs = 0L
    private var penaltyAppliedForEpisode = false
    private var lastVoiceAlertMs = 0L
    private var lastVibrateMs = 0L
    private var lastNotifUpdateMs = 0L
    private var lockedVolumeTarget = -1
    private var sessionCompleted = false

    @Volatile
    private var latestGaze = GazeReading(
        faceDetected = true, yawDeg = 0f, pitchDeg = 0f, eyeOpenAvg = 0.9f,
        rawLooking = true, isLookingAtScreen = true, reason = "Initialisation IA…"
    )

    private val tickRunnable = object : Runnable {
        override fun run() {
            val cfg = activeCfg ?: return
            val ov = overlay ?: return
            if (sessionCompleted) return

            val gaze = latestGaze
            val now = SystemClock.elapsedRealtime()

            // Optional Volume Lock (prevents muting to 0 while locked!)
            if (cfg.volumeLockEnabled && lockedVolumeTarget > 0) {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) < lockedVolumeTarget) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, lockedVolumeTarget, 0)
                    }
                } catch (_: Throwable) {}
            }

            if (gaze.isLookingAtScreen) {
                if (currentAwayMs > 0L) {
                    currentAwayMs = 0L
                    penaltyAppliedForEpisode = false
                }
                remainingMs = (remainingMs - 100L).coerceAtLeast(0L)
                focusedMs += 100L
                currentStreakMs += 100L
                if (currentStreakMs > bestStreakMs) bestStreakMs = currentStreakMs
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
                if (cfg.lookAwayPenaltySec > 0 && !penaltyAppliedForEpisode && currentAwayMs >= 2_500L) {
                    penaltyAppliedForEpisode = true
                    remainingMs += cfg.lookAwayPenaltySec * 1000L
                    totalDurationMs = maxOf(totalDurationMs, remainingMs)
                    vibrateShort(130L)
                    if (cfg.voiceAlertEnabled && ttsReady) {
                        speakFrench("Pénalité ! Plus ${cfg.lookAwayPenaltySec} secondes !")
                        lastVoiceAlertMs = now
                    }
                }
            }

            ov.updateHud(remainingMs, totalDurationMs, gaze, currentAwayMs, lookAwayEpisodes)

            if (now - lastNotifUpdateMs >= 2_000L) {
                lastNotifUpdateMs = now
                updateForegroundNotification()
            }

            if (remainingMs <= 0L) {
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
                try {
                    tts?.language = Locale.FRENCH
                    ttsReady = true
                } catch (_: Throwable) {}
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
        val notif = buildNotification("👁️ Démarrage du fil vidéo GazeLock AI…")
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
        totalDurationMs = durSec * 1000L
        remainingMs = totalDurationMs
        focusedMs = 0L; currentStreakMs = 0L; bestStreakMs = 0L
        lookAwayEpisodes = 0; currentAwayMs = 0L; penaltyAppliedForEpisode = false
        sessionCompleted = false

        if (cfg.volumeLockEnabled) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    lockedVolumeTarget = curV.coerceAtLeast((maxV * 0.40f).toInt().coerceAtLeast(1))
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, lockedVolumeTarget, 0)
                }
            } catch (_: Throwable) {}
        }

        val ov = GazeVideoFeedOverlay(
            context = applicationContext,
            config = cfg,
            initialPlaylist = playlist,
            onVerifyEmergencyPin = { pin -> gazeConfig.verifyEmergencyPin(pin) },
            onEmergencyExit = { stopSessionCleanly() },
            onSessionCompleteDismiss = { stopSessionCleanly() }
        )

        val mounted = mountOverlay(ov)
        if (!mounted) {
            ov.release()
            mainHandler.post {
                Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (ou Superposition) pour verrouiller le fil vidéo !", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        overlay = ov
        isSessionRunning = true
        notifyListeners()

        // Start Local AI CameraX + ML Kit Face/Eye Gaze Tracking bound to this LifecycleService!
        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { reading -> latestGaze = reading },
            onCameraError = { err ->
                mainHandler.post { Toast.makeText(applicationContext, "⚠️ $err", Toast.LENGTH_LONG).show() }
            }
        )
        tracker = tr
        tr.start(this, cfg)

        mainHandler.postDelayed(tickRunnable, 200L)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun mountOverlay(ov: GazeVideoFeedOverlay): Boolean {
        val a11y = GazeAccessibilityService.activeInstance
        if (a11y != null && a11y.mountFullscreenOverlay(ov.rootView)) {
            mountedViaA11y = true
            return true
        }
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canDraw) return false
        return try {
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
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            fallbackWm?.addView(ov.rootView, lp)
            mountedViaA11y = false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun onSessionFinishedNaturally() {
        sessionCompleted = true
        mainHandler.removeCallbacks(tickRunnable)
        tracker?.stop()
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
        tracker?.shutdown(); tracker = null
        val ov = overlay
        overlay = null
        if (ov != null) {
            ov.release()
            if (mountedViaA11y) {
                GazeAccessibilityService.activeInstance?.removeMountedOverlay()
            } else {
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
        tracker?.shutdown(); tracker = null
        overlay?.release(); overlay = null
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
            .setContentTitle("👁️ GazeLock AI")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun updateForegroundNotification(customText: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val txt = customText ?: run {
            val s = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
            val status = if (latestGaze.isLookingAtScreen) "👁️ Regard fixe" else "⏸️ Pause (regard détourné)"
            String.format("%s — %02d:%02d restant(s)", status, s / 60, s % 60)
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

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) { listeners.add(l) }
        fun removeStateListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() { for (l in listeners) try { l() } catch (_: Throwable) {} }

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
