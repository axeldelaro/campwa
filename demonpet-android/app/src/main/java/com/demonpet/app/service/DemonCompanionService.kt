package com.demonpet.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonOverlayManager
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import com.demonpet.app.ui.MainActivity

/**
 * Companion Foreground Service:
 * - Keeps a clean status notification with 1-tap Pause (when Challenge Lock is OFF)
 *   or 20-tap + 100205 reminder (when Challenge Lock is ON).
 * - If Accessibility Service isn't enabled yet but SYSTEM_ALERT_WINDOW is granted,
 *   hosts the Demon Avatar & visual pranks immediately via TYPE_APPLICATION_OVERLAY!
 */
class DemonCompanionService : Service() {

    private lateinit var settings: DemonSettings
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallbackOverlay: DemonOverlayManager? = null

    private val prankTick = object : Runnable {
        override fun run() {
            val cfg = settings.snapshot()
            if (!cfg.enabled || DemonAccessibilityService.isServiceActive()) {
                fallbackOverlay?.detachAll()
                fallbackOverlay = null
                return
            }
            val mgr = fallbackOverlay
            if (mgr != null) {
                val actions = ArrayList<() -> Unit>()
                if (cfg.draggedPopupEnabled) actions.add { mgr.showDraggedPhotoPopup(cfg) }
                if (cfg.fullscreenFlashEnabled) actions.add { mgr.showFullscreenPhotoFlash(cfg) }
                if (cfg.wallpaperPrankEnabled) {
                    actions.add {
                        mgr.say("Nouveau fond d'écran ! 🖼️😈", 3_800L)
                        Thread { MediaFolderHelper.applyRandomWallpaperPrank(applicationContext, cfg) }.start()
                    }
                }
                if (actions.isNotEmpty()) actions.random().invoke()
            }
            val delay = (cfg.minIntervalSec * 1000L).coerceAtLeast(20_000L)
            mainHandler.postDelayed(this, delay)
        }
    }

    private val cfgListener: (DemonConfigSnapshot) -> Unit = { snap ->
        mainHandler.post {
            if (!snap.enabled) {
                stopSelf()
            } else {
                refreshNotification(snap)
                syncFallbackOverlay(snap)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = DemonSettings(applicationContext)
        DemonSettings.addListener(cfgListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val snap = settings.snapshot()
            if (!snap.challengeLocked) {
                settings.enabled = false
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val snap = settings.snapshot()
        createChannel()
        val notif = buildNotification(snap)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        syncFallbackOverlay(snap)
        return START_STICKY
    }

    private fun syncFallbackOverlay(snap: DemonConfigSnapshot) {
        // Yield cleanly to DemonAccessibilityService when active (no duplicate Demon!)
        if (DemonAccessibilityService.isServiceActive()) {
            fallbackOverlay?.detachAll()
            fallbackOverlay = null
            mainHandler.removeCallbacks(prankTick)
            return
        }
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!canDraw || !snap.enabled) {
            fallbackOverlay?.detachAll()
            fallbackOverlay = null
            mainHandler.removeCallbacks(prankTick)
            return
        }
        if (fallbackOverlay == null) {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val mgr = DemonOverlayManager(
                context = this,
                windowType = winType,
                settings = settings,
                onGoHome = {
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { startActivity(home) } catch (_: Throwable) {}
                },
                onExitUnlocked = {
                    DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
                    settings.update {
                        putBoolean("challenge_locked", false)
                        putBoolean("strict_settings_lock", false)
                        putBoolean("enabled", false)
                    }
                    stopSelf()
                },
                onGraceGranted = { _, _ -> }
            )
            fallbackOverlay = mgr
            mgr.attach(snap)
            mainHandler.removeCallbacks(prankTick)
            mainHandler.postDelayed(prankTick, (snap.minIntervalSec * 1000L).coerceAtLeast(20_000L))
        } else {
            fallbackOverlay?.updateConfig(snap)
        }
    }

    fun triggerFallbackTest(kind: String) {
        val snap = settings.snapshot()
        syncFallbackOverlay(snap)
        when (kind) {
            "popup" -> fallbackOverlay?.showDraggedPhotoPopup(snap)
            "flash" -> fallbackOverlay?.showFullscreenPhotoFlash(snap)
            "slideshow" -> fallbackOverlay?.showForcedSlideshow(snap)
            "corner" -> fallbackOverlay?.showTimeoutCornerWithVideo(
                reason = "Test Punition Au Coin (vidéo du dossier) 😈",
                durationSec = snap.timeoutCornerSec.coerceAtMost(20),
                cfg = snap
            )
            "lines" -> fallbackOverlay?.showPunishmentLinesOverlay(snap)
            "deadzone" -> fallbackOverlay?.showDeadZoneKeyboardOverlay(15)
            "quiz" -> fallbackOverlay?.showSurpriseQuizOverlay(snap)
            "wallpaper" -> {
                fallbackOverlay?.say("Nouveau fond d'écran ! 🖼️😈", 3_500L)
                Thread { MediaFolderHelper.applyRandomWallpaperPrank(applicationContext, snap) }.start()
            }
            "lock" -> fallbackOverlay?.showLockShieldWithNegotiation(
                title = "Test Blocage & Négociation 😈",
                reason = "Voici l'écran de blocage quand tu dépasses ton quota !",
                pkg = "test.demo",
                allowNegotiation = true,
                cfg = snap
            )
        }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        DemonSettings.removeListener(cfgListener)
        mainHandler.removeCallbacks(prankTick)
        fallbackOverlay?.detachAll()
        fallbackOverlay = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "DemonMom AI", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun refreshNotification(snap: DemonConfigSnapshot) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(snap))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(snap: DemonConfigSnapshot): Notification {
        instance = this
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("${snap.avatarName} • ${snap.mood.emoji} ${snap.mood.label}")
            .setContentText(
                if (snap.challengeLocked) "Verrouillé • Tapote le démon 20× + code 100205 pour sortir"
                else "Chaos ${snap.chaosLevel}/10 • Appuie pour ouvrir les réglages"
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)

        if (!snap.challengeLocked) {
            val stopPi = PendingIntent.getService(
                this, 2,
                Intent(this, DemonCompanionService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.R.drawable.ic_media_pause, "Calmer / Pause", stopPi)
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "demonmom_companion_v1"
        private const val NOTIF_ID = 8801
        private const val ACTION_STOP = "com.demonpet.app.ACTION_STOP"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: DemonCompanionService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, DemonCompanionService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try { context.stopService(Intent(context, DemonCompanionService::class.java)) } catch (_: Throwable) {}
        }
    }
}
