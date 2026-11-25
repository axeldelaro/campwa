package com.gazelock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gazelock.app.audio.PavlovSoundEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.PopupTouchMode
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.ui.MainActivity
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.random.Random

/**
 * Standalone Image Pop-Up Storm Mode (v5.1 — runs OUTSIDE of videos over all phone apps!):
 * - Fully customizable Size (25-92%), Frequency (0.3s-30s), Duration (0.4s-15s), Max Simultaneous (1-8),
 *   Opacity (35-100%), Touch Mode (Pass-Through / Tap-to-Close 1-3x + Mitosis / Touch-Locked),
 *   Random Tilt, Neon Mantras, Haptics & Optional Portal Exit Lock + Anti-Escape!
 */
class ImagePopupStormService : Service() {

    private data class ActivePopup(
        val view: View,
        val expireRunnable: Runnable
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false

    private val bitmapPool = ArrayList<Bitmap>()
    private val activePopups = ArrayList<ActivePopup>()
    private var lastBmpIdx = -1

    private var stormPillView: LinearLayout? = null
    private var stormPillLabelTv: TextView? = null
    private var fbPillParams: WindowManager.LayoutParams? = null
    private var portalHost: FrameLayout? = null
    private var activePortalWait: AccessCodeWaitSpamView? = null
    private var portalOpen = false

    private var sessionRemainingMs = 0L
    private var sessionInfinite = true
    private var lastSessionTickMs = 0L
    private var totalSpawnedCount = 0

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val sessionTimerTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastSessionTickMs).coerceIn(100L, 1_500L)
            lastSessionTickMs = now

            if (!portalOpen && !sessionInfinite) {
                sessionRemainingMs = (sessionRemainingMs - dt).coerceAtLeast(0L)
                if (sessionRemainingMs <= 0L) {
                    mainHandler.post {
                        Toast.makeText(applicationContext, "✨ Session Pop-Ups d'Images terminée !", Toast.LENGTH_SHORT).show()
                        stopStormCleanly()
                    }
                    return
                }
            }
            updatePillLabel()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val spawnLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val cfg = gazeConfig.snapshot()
            if (!portalOpen && bitmapPool.isNotEmpty()) {
                spawnSingleImagePopup(cfg)
            }
            val delayMs = (cfg.popupIntervalDeciSec.coerceIn(3, 300) * 100L)
            mainHandler.postDelayed(this, delayMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gazeConfig = GazeConfig(applicationContext)
        fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStormCleanly()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        val cfg = gazeConfig.snapshot()
        if (cfg.popupFolderPath.isBlank() && cfg.popupTreeUri.isBlank() &&
            cfg.folderPath.isBlank() && cfg.treeUri.isBlank()
        ) {
            mainHandler.post {
                Toast.makeText(applicationContext, "📂 Choisis d'abord un dossier contenant des images !", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notif = buildNotification("🖼️ Mode Pop-Ups d'Images actif…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIF_ID, notif)
        } catch (_: Throwable) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        }

        val a11y = GazeAccessibilityService.activeInstance
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (a11y == null && !canDraw) {
            mainHandler.post {
                Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (ou Superposition) pour afficher les pop-ups d'images !", Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        mountedViaA11y = (a11y != null)

        isRunning = true
        isExitLocked = cfg.popupLockExitWithPortal
        activeInstance = this
        portalOpen = false
        totalSpawnedCount = 0
        sessionInfinite = (cfg.popupSessionDurationSec <= 0)
        sessionRemainingMs = cfg.popupSessionDurationSec * 1000L
        lastSessionTickMs = SystemClock.elapsedRealtime()
        notifyListeners()

        mountControlPill(cfg)

        // Preload RGB_565 folder image pool off UI thread!
        Thread {
            val loaded = VideoFeedRepository.loadPopupModeBitmapPool(applicationContext, cfg, maxPoolSize = 44)
            mainHandler.post {
                if (!isRunning) {
                    for (b in loaded) if (!b.isRecycled) b.recycle()
                    return@post
                }
                if (loaded.isEmpty()) {
                    Toast.makeText(applicationContext, "⚠️ Aucune image (ni vidéo) trouvée dans le dossier choisi !", Toast.LENGTH_LONG).show()
                    stopStormCleanly()
                    return@post
                }
                bitmapPool.addAll(loaded)
                // Immediately launch first pop-up so feedback is instant!
                mainHandler.post(spawnLoopRunnable)
            }
        }.start()

        mainHandler.postDelayed(sessionTimerTick, 500L)

        // Drop user to home screen so they can immediately keep using their phone while pop-ups fire!
        mainHandler.postDelayed({
            try {
                GazeAccessibilityService.activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (_: Throwable) {}
        }, 280L)

        return START_STICKY
    }

    private fun spawnSingleImagePopup(cfg: GazeConfigSnapshot, fromMitosis: Boolean = false) {
        if (!isRunning || portalOpen) return
        val validBmps = bitmapPool.filter { !it.isRecycled }
        if (validBmps.isEmpty()) return

        val maxSimul = cfg.popupMaxSimultaneous.coerceIn(1, 8)
        while (activePopups.size >= maxSimul) {
            removePopup(activePopups.first())
        }

        var idx = Random.nextInt(validBmps.size)
        if (validBmps.size > 1 && idx == lastBmpIdx) idx = (idx + 1) % validBmps.size
        lastBmpIdx = idx
        val bmp = validBmps[idx]

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val sizeFrac = cfg.popupSizePct.coerceIn(25, 92) / 100f
        val targetW = (sw * sizeFrac).toInt().coerceIn(dp(96), sw - dp(16))
        val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()).coerceIn(0.55f, 1.85f)
        val targetH = (targetW / ratio).toInt().coerceIn(dp(96), (sh * 0.72f).toInt())

        val padX = dp(10)
        val minY = dp(52)
        val maxX = (sw - targetW - padX).coerceAtLeast(padX + 1)
        val maxY = (sh - targetH - dp(42)).coerceAtLeast(minY + 1)
        val posX = Random.nextInt(padX, maxX)
        val posY = Random.nextInt(minY, maxY)

        val touchMode = cfg.popupTouchMode
        val touchable = touchMode != PopupTouchMode.PASS_THROUGH
        val targetAlpha = cfg.popupOpacityPct.coerceIn(35, 100) / 100f
        val accentColors = intArrayOf(
            Color.rgb(236, 72, 153),
            Color.rgb(168, 85, 247),
            Color.rgb(244, 63, 94),
            Color.rgb(250, 204, 21),
            Color.rgb(14, 165, 233)
        )
        val strokeCol = if (fromMitosis) Color.rgb(244, 63, 94) else accentColors[Random.nextInt(accentColors.size)]

        val card = FrameLayout(applicationContext).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 18, 12, 28))
                setStroke(dp(2), strokeCol)
            }
            if (cfg.popupRandomRotation) {
                rotation = Random.nextInt(-13, 14).toFloat()
            }
            alpha = 0f
            scaleX = 0.68f
            scaleY = 0.68f
        }

        val iv = ImageView(applicationContext).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (cfg.popupShowMantra) {
            val mantra = cfg.mantraList().random()
            card.addView(TextView(applicationContext).apply {
                text = mantra
                setTextColor(Color.WHITE)
                textSize = if (cfg.popupSizePct < 42) 10.5f else 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(170, 20, 8, 30))
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setShadowLayer(8f, 0f, 0f, strokeCol)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        var activeEntry: ActivePopup? = null
        val durationMs = cfg.popupDurationDeciSec.coerceIn(4, 150) * 100L
        val expireRunnable = Runnable {
            val entry = activeEntry ?: return@Runnable
            if (entry in activePopups) {
                removePopup(entry)
                // Optional Mitosis: ignoring a clickable pop-up splits it into 2!
                if (touchMode == PopupTouchMode.TAP_DISMISS && cfg.popupMitosisEnabled && isRunning && !portalOpen) {
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                    spawnSingleImagePopup(cfg, fromMitosis = true)
                    spawnSingleImagePopup(cfg, fromMitosis = true)
                }
            }
        }

        // Corner interactive badge + tap logic
        when (touchMode) {
            PopupTouchMode.TAP_DISMISS -> {
                var tapsLeft = cfg.popupTapsToClose.coerceIn(1, 3)
                val badge = TextView(applicationContext).apply {
                    text = if (tapsLeft > 1) "×$tapsLeft" else "✕"
                    setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(215, 225, 29, 72)) }
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }
                card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(5); marginEnd = dp(5)
                })
                card.setOnClickListener {
                    tapsLeft--
                    if (tapsLeft <= 0) {
                        activeEntry?.let { removePopup(it) }
                    } else {
                        badge.text = "×$tapsLeft"
                        card.animate().scaleX(0.92f).scaleY(0.92f).setDuration(55L).withEndAction {
                            card.animate().scaleX(1f).scaleY(1f).setDuration(55L).start()
                        }.start()
                    }
                }
            }
            PopupTouchMode.TOUCH_LOCKED -> {
                val lockBadge = TextView(applicationContext).apply {
                    text = "🔒"
                    setTextColor(Color.WHITE); textSize = 10.5f
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(190, 20, 12, 32)) }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                card.addView(lockBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(5); marginEnd = dp(5)
                })
                card.setOnClickListener {
                    card.animate().translationX(dp(7).toFloat()).setDuration(40L).withEndAction {
                        card.animate().translationX(-dp(7).toFloat()).setDuration(40L).withEndAction {
                            card.animate().translationX(0f).setDuration(40L).start()
                        }.start()
                    }.start()
                }
            }
            PopupTouchMode.PASS_THROUGH -> { /* Pure pass-through, no touch listener */ }
        }

        val mounted = mountPopupWindow(card, posX, posY, targetW, targetH, touchable)
        if (!mounted) return

        val entry = ActivePopup(card, expireRunnable)
        activeEntry = entry
        activePopups.add(entry)
        totalSpawnedCount++
        updatePillLabel()

        card.animate().alpha(targetAlpha).scaleX(1.0f).scaleY(1.0f).setDuration(150L).start()
        if (cfg.popupVibrate) vibrateShort(28L)
        mainHandler.postDelayed(expireRunnable, durationMs)
    }

    private fun removePopup(entry: ActivePopup) {
        mainHandler.removeCallbacks(entry.expireRunnable)
        activePopups.remove(entry)
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.removeStormPopupWindow(entry.view)
        } else {
            try { fallbackWm?.removeViewImmediate(entry.view) } catch (_: Throwable) {}
        }
        updatePillLabel()
    }

    private fun clearAllActivePopups() {
        for (p in activePopups.toList()) {
            mainHandler.removeCallbacks(p.expireRunnable)
            if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeStormPopupWindow(p.view)
            else try { fallbackWm?.removeViewImmediate(p.view) } catch (_: Throwable) {}
        }
        activePopups.clear()
    }

    // ==================== COMPACT DRAGGABLE CONTROL PILL ====================
    private fun mountControlPill(cfg: GazeConfigSnapshot) {
        val pill = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(215, 22, 10, 36))
                setStroke(dp(1), Color.rgb(236, 72, 153))
            }
        }
        val lbl = TextView(applicationContext).apply {
            text = "🖼️ Pop-ups"
            setTextColor(Color.WHITE); textSize = 11.2f; typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(6), 0)
        }
        stormPillLabelTv = lbl
        pill.addView(lbl)

        if (cfg.popupLockExitWithPortal) {
            val waitMin = cfg.currentPortalWaitSec() / 60
            pill.addView(TextView(applicationContext).apply {
                text = "🔓 ${waitMin}m"
                setTextColor(Color.WHITE); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
                setPadding(dp(7), dp(3), dp(7), dp(3))
                setOnClickListener { openUnlockPortalForStorm() }
            })
        } else {
            pill.addView(TextView(applicationContext).apply {
                text = "⏹ Stop"
                setTextColor(Color.WHITE); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(225, 29, 72)) }
                setPadding(dp(7), dp(3), dp(7), dp(3))
                setOnClickListener { stopStormCleanly() }
            })
        }

        var lastRawX = 0f; var lastRawY = 0f; var dragging = false
        lbl.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastRawX = ev.rawX; lastRawY = ev.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - lastRawX).toInt(); val dy = (ev.rawY - lastRawY).toInt()
                    if (dragging || abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        dragging = true; dragPillWindow(dx, dy); lastRawX = ev.rawX; lastRawY = ev.rawY
                    }
                    true
                }
                else -> false
            }
        }

        stormPillView = pill
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.mountStormPillOverlay(pill)
        } else {
            try {
                val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                val dm = resources.displayMetrics
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, winType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (dm.widthPixels - dp(170)).coerceAtLeast(dp(8)); y = dp(44)
                }
                fallbackWm?.addView(pill, lp); fbPillParams = lp
            } catch (_: Throwable) {}
        }
        updatePillLabel()
    }

    private fun updatePillLabel() {
        val timeTxt = if (sessionInfinite) "∞" else {
            val s = ((sessionRemainingMs + 999L) / 1000L).coerceAtLeast(0L)
            String.format("%02d:%02d", s / 60L, s % 60L)
        }
        stormPillLabelTv?.text = "🖼️ $timeTxt · ×${activePopups.size}"
    }

    private fun dragPillWindow(dx: Int, dy: Int) {
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.dragStormPillBy(dx, dy)
        } else {
            val v = stormPillView ?: return; val lp = fbPillParams ?: return
            val dm = resources.displayMetrics
            lp.x = (lp.x + dx).coerceIn(0, (dm.widthPixels - v.width.coerceAtLeast(80)).coerceAtLeast(0))
            lp.y = (lp.y + dy).coerceIn(dp(20), (dm.heightPixels - v.height.coerceAtLeast(60)).coerceAtLeast(0))
            try { fallbackWm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    // ==================== LOCKED MODE: INTERACTIVE OVERLAY UNLOCK PORTAL ====================
    fun openUnlockPortalForStorm() {
        mainHandler.post {
            if (!isRunning || portalOpen) return@post
            portalOpen = true
            clearAllActivePopups()
            val cfg = gazeConfig.snapshot()

            val host = object : FrameLayout(applicationContext) {
                override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                    if (event != null && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)) return true
                    return super.dispatchKeyEvent(event)
                }
            }.apply { setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true; isFocusableInTouchMode = true }

            val waitView = AccessCodeWaitSpamView(
                context = applicationContext,
                cfg = cfg,
                gazeLookingSupplier = null,
                onOverloadSprintChanged = null,
                onIssueCode = { gazeConfig.issueUnlockedAccessCode() },
                onRecordDailyPortalCompletion = { gazeConfig.recordPortalUnlockCompletedToday() },
                onCodeUnlockedAndExit = { code ->
                    if (gazeConfig.verifyDynamicAccessCode(code)) {
                        closePortalWindow()
                        stopStormCleanly()
                    }
                },
                onCancelWait = {
                    closePortalWindow()
                }
            )
            activePortalWait = waitView
            host.addView(waitView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (mountPortalWindow(host)) {
                portalHost = host
                waitView.startThreeMinuteSpamWait()
            } else {
                waitView.stopAndRelease(); activePortalWait = null; portalOpen = false
            }
        }
    }

    private fun closePortalWindow() {
        portalOpen = false
        activePortalWait?.stopAndRelease(); activePortalWait = null
        val h = portalHost; portalHost = null
        if (h != null) {
            if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removePortalOverlay()
            else try { fallbackWm?.removeViewImmediate(h) } catch (_: Throwable) {}
        }
    }

    private fun handleEscapeBlockedInternal() {
        mainHandler.post {
            if (!isRunning || portalOpen) return@post
            gazeConfig.recordEscapeBlock()
            vibrateShort(160L)
            val cfg = gazeConfig.snapshot()
            if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
            Toast.makeText(applicationContext, "🚫 Paramètres verrouillés ! Passe par le portail 🔓 !", Toast.LENGTH_SHORT).show()
            // Spawn 2 punishment pop-ups immediately!
            spawnSingleImagePopup(cfg, fromMitosis = true)
            spawnSingleImagePopup(cfg, fromMitosis = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun mountPopupWindow(view: View, x: Int, y: Int, w: Int, h: Int, touchable: Boolean): Boolean {
        if (mountedViaA11y) {
            return GazeAccessibilityService.activeInstance?.mountStormPopupWindow(view, x, y, w, h, touchable) == true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val flags = if (touchable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            val lp = WindowManager.LayoutParams(w, h, winType, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y
            }
            fallbackWm?.addView(view, lp); true
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun mountPortalWindow(view: View): Boolean {
        if (mountedViaA11y) {
            val a11y = GazeAccessibilityService.activeInstance
            if (a11y != null && a11y.mountPortalOverlay(view, forceMaxBrightness = true)) return true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, winType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            fallbackWm?.addView(view, lp); true
        } catch (_: Throwable) { false }
    }

    private fun stopStormCleanly() {
        mainHandler.removeCallbacks(spawnLoopRunnable)
        mainHandler.removeCallbacks(sessionTimerTick)
        isRunning = false
        isExitLocked = false
        if (activeInstance === this) activeInstance = null
        closePortalWindow()
        clearAllActivePopups()
        stormPillView?.let { pill ->
            if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeStormPillOverlay()
            else try { fallbackWm?.removeViewImmediate(pill) } catch (_: Throwable) {}
        }
        stormPillView = null; stormPillLabelTv = null; fbPillParams = null
        for (b in bitmapPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        bitmapPool.clear()
        notifyListeners()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(spawnLoopRunnable)
        mainHandler.removeCallbacks(sessionTimerTick)
        isRunning = false; isExitLocked = false
        if (activeInstance === this) activeInstance = null
        closePortalWindow()
        clearAllActivePopups()
        try { GazeAccessibilityService.activeInstance?.removeAllStormOverlays() } catch (_: Throwable) {}
        for (b in bitmapPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        bitmapPool.clear()
        notifyListeners()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GazeLock AI — Pop-Ups d'Images", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(content: String): Notification {
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("🖼️ GazeLock AI — Mode Pop-Ups d'Images")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gazelock_popup_storm_ch"
        private const val NOTIF_ID = 4109
        const val ACTION_STOP = "com.gazelock.app.STOP_POPUP_STORM"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isExitLocked: Boolean = false
            private set

        @Volatile
        private var activeInstance: ImagePopupStormService? = null

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addStateListener(l: () -> Unit) { listeners.add(l) }
        fun removeStateListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() { for (l in listeners) try { l() } catch (_: Throwable) {} }

        fun onEscapeBlocked() { activeInstance?.handleEscapeBlockedInternal() }
        fun openUnlockPortal() { activeInstance?.openUnlockPortalForStorm() }

        fun start(context: Context) {
            val i = Intent(context.applicationContext, ImagePopupStormService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, i)
        }

        fun stop(context: Context) {
            val i = Intent(context.applicationContext, ImagePopupStormService::class.java).apply { action = ACTION_STOP }
            try { context.applicationContext.startService(i) } catch (_: Throwable) {}
        }
    }
}
