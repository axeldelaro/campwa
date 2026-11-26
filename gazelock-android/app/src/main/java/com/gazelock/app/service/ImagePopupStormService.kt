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
import com.gazelock.app.config.PopupAnimStyle
import com.gazelock.app.config.PopupFrameStyle
import com.gazelock.app.config.PopupSpawnZone
import com.gazelock.app.config.PopupTouchMode
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.overlay.PopupStormCanvasView
import com.gazelock.app.ui.MainActivity
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * MEGA Standalone Image Pop-Up Storm Service (v6.0 — runs OUTSIDE of videos over all apps!):
 * - Hybrid Engine:
 *   1) GPU 60 FPS [PopupStormCanvasView] stage for PASS_THROUGH (always!) OR massive/unlimited count (>12 or ∞):
 *      handles 30ms (33/s!) frequencies, bursts of 1..12 photos, and 350+ simultaneous sprites with ZERO lag!
 *   2) Individual compact floating windows when touchable & maxSimultaneous <= 12 so touches outside
 *      the pop-ups still interact cleanly with apps underneath!
 * - Full v6.0 features: Min/Max Size (12%..100%), Ramp-Up Acceleration (up to 4x), 4 Spawn Zones,
 *   5 Entrance Animations, 5 Frame Styles, Persistent ∞ Duration, Swipe-Away Fling, Mitosis,
 *   Pavlov Audio Cues, and Optional Portal Exit Lock + Anti-Escape!
 */
class ImagePopupStormService : Service() {

    private data class ActiveWindowPopup(
        val view: View,
        val expireRunnable: Runnable?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gazeConfig: GazeConfig
    private var fallbackWm: WindowManager? = null
    private var mountedViaA11y = false

    private val bitmapPool = ArrayList<Bitmap>()
    private val activeWindowPopups = ArrayList<ActiveWindowPopup>()
    private var canvasStage: PopupStormCanvasView? = null
    private var canvasStageTouchable = false
    private var lastBmpIdx = -1
    private var gridCursor = 0

    private var stormPillView: LinearLayout? = null
    private var stormPillLabelTv: TextView? = null
    private var fbPillParams: WindowManager.LayoutParams? = null
    private var portalHost: FrameLayout? = null
    private var activePortalWait: AccessCodeWaitSpamView? = null
    private var portalOpen = false

    private var sessionStartElapsedMs = 0L
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
                        Toast.makeText(applicationContext, "✨ Session Pop-Ups d'Images terminée ($totalSpawnedCount images) !", Toast.LENGTH_SHORT).show()
                        stopStormCleanly()
                    }
                    return
                }
            }
            updatePillLabel()
            mainHandler.postDelayed(this, 450L)
        }
    }

    private val spawnLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val cfg = gazeConfig.snapshot()
            if (!portalOpen && bitmapPool.isNotEmpty()) {
                val burst = cfg.popupBurstCount.coerceIn(1, 12)
                repeat(burst) { spawnOnePopup(cfg) }
                if (cfg.popupVibrate) vibrateShort(22L)
                if (cfg.popupSoundOnSpawn) PavlovSoundEngine.playRewardChime()
            }

            // Optional Ramp-Up Storm: smoothly accelerates up to 4x faster over 3 minutes!
            val baseDelay = cfg.popupIntervalMs.coerceIn(30, 60_000).toLong()
            val delayMs = if (cfg.popupRampUpEnabled) {
                val elapsedSec = ((SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000.0).coerceAtLeast(0.0)
                val speedFactor = (1.0 + (elapsedSec / 60.0)).coerceIn(1.0, 4.0)
                (baseDelay / speedFactor).toLong().coerceAtLeast(30L)
            } else baseDelay

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
            mainHandler.post { Toast.makeText(applicationContext, "📂 Choisis d'abord un dossier contenant des images !", Toast.LENGTH_LONG).show() }
            stopSelf(); return START_NOT_STICKY
        }

        createChannel()
        val notif = buildNotification("🖼️ Tempête Pop-Ups d'Images v6.0 active…")
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
            mainHandler.post { Toast.makeText(applicationContext, "⚠️ Active l'Accessibilité (ou Superposition) !", Toast.LENGTH_LONG).show() }
            stopSelf(); return START_NOT_STICKY
        }
        mountedViaA11y = (a11y != null)

        isRunning = true
        isExitLocked = cfg.popupLockExitWithPortal
        activeInstance = this
        portalOpen = false
        totalSpawnedCount = 0
        gridCursor = 0
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        sessionInfinite = (cfg.popupSessionDurationSec <= 0)
        sessionRemainingMs = cfg.popupSessionDurationSec * 1000L
        lastSessionTickMs = sessionStartElapsedMs
        notifyListeners()

        ensureAppropriateStage(cfg)
        mountControlPill(cfg)

        Thread {
            val loaded = VideoFeedRepository.loadPopupModeBitmapPool(applicationContext, cfg, maxPoolSize = 52)
            mainHandler.post {
                if (!isRunning) {
                    for (b in loaded) if (!b.isRecycled) b.recycle()
                    return@post
                }
                if (loaded.isEmpty()) {
                    Toast.makeText(applicationContext, "⚠️ Aucune image (ni vidéo) trouvée dans ce dossier !", Toast.LENGTH_LONG).show()
                    stopStormCleanly(); return@post
                }
                bitmapPool.addAll(loaded)
                mainHandler.post(spawnLoopRunnable)
            }
        }.start()

        mainHandler.postDelayed(sessionTimerTick, 450L)

        mainHandler.postDelayed({
            try { GazeAccessibilityService.activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        }, 260L)

        return START_STICKY
    }

    private fun shouldUseCanvasStage(cfg: GazeConfigSnapshot): Boolean =
        cfg.popupTouchMode == PopupTouchMode.PASS_THROUGH ||
            cfg.popupMaxSimultaneous == 0 ||
            cfg.popupMaxSimultaneous > 12

    private fun ensureAppropriateStage(cfg: GazeConfigSnapshot) {
        val wantCanvas = shouldUseCanvasStage(cfg)
        val wantTouchable = cfg.popupTouchMode != PopupTouchMode.PASS_THROUGH
        if (wantCanvas) {
            if (activeWindowPopups.isNotEmpty()) clearWindowPopupsOnly()
            if (canvasStage == null || canvasStageTouchable != wantTouchable) {
                removeCanvasStageOnly()
                val stage = PopupStormCanvasView(
                    context = applicationContext,
                    interactiveMode = wantTouchable,
                    onMitosisRequested = {
                        val c = gazeConfig.snapshot()
                        if (isRunning && !portalOpen) {
                            if (c.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                            spawnOnePopup(c, fromMitosis = true)
                            spawnOnePopup(c, fromMitosis = true)
                        }
                    },
                    onActiveCountChanged = { updatePillLabel() },
                    onSpriteDismissed = {
                        if (gazeConfig.snapshot().popupSoundOnSpawn) PavlovSoundEngine.playRewardChime()
                    }
                )
                if (mountStageWindow(stage, wantTouchable)) {
                    canvasStage = stage
                    canvasStageTouchable = wantTouchable
                    // Keep control pill ALWAYS above the canvas stage!
                    stormPillView?.let { remountPillOnTop() }
                }
            }
        } else {
            if (canvasStage != null) removeCanvasStageOnly()
        }
    }

    private fun pickNextBitmap(): Bitmap? {
        val valid = bitmapPool.filter { !it.isRecycled }
        if (valid.isEmpty()) return null
        var idx = Random.nextInt(valid.size)
        if (valid.size > 1 && idx == lastBmpIdx) idx = (idx + 1) % valid.size
        lastBmpIdx = idx
        return valid[idx]
    }

    private fun spawnOnePopup(cfg: GazeConfigSnapshot, fromMitosis: Boolean = false) {
        if (!isRunning || portalOpen) return
        ensureAppropriateStage(cfg)
        val bmp = pickNextBitmap() ?: return

        val stage = canvasStage
        if (stage != null) {
            stage.spawnSprite(bmp, cfg, fromMitosis)
            totalSpawnedCount++
            updatePillLabel()
            return
        }

        // Individual floating window mode (touchable <= 12 so apps underneath remain clickable around pop-ups!)
        val maxSimul = cfg.popupMaxSimultaneous.coerceIn(1, 12)
        while (activeWindowPopups.size >= maxSimul) {
            removeWindowPopup(activeWindowPopups.first())
        }

        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val minPct = minOf(cfg.popupMinSizePct, cfg.popupSizePct).coerceIn(12, 100)
        val maxPct = maxOf(cfg.popupMinSizePct, cfg.popupSizePct).coerceIn(15, 100)
        val chosenPct = if (minPct == maxPct) maxPct else Random.nextInt(minPct, maxPct + 1)

        val targetW = (sw * chosenPct / 100f).toInt().coerceIn(dp(64), sw - dp(10))
        val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()).coerceIn(0.50f, 1.95f)
        val targetH = (targetW / ratio).toInt().coerceIn(dp(64), (sh * 0.80f).toInt())

        val (posX, posY) = pickWindowTopLeft(cfg.popupSpawnZone, sw, sh, targetW, targetH)
        val baseAlpha = cfg.popupOpacityPct.coerceIn(15, 100) / 100f
        val targetAlpha = if (cfg.popupRandomOpacity) (baseAlpha * (0.55f + Random.nextFloat() * 0.45f)).coerceIn(0.20f, 1f) else baseAlpha
        val accentColors = intArrayOf(Color.rgb(236, 72, 153), Color.rgb(168, 85, 247), Color.rgb(244, 63, 94), Color.rgb(250, 204, 21), Color.rgb(14, 165, 233))
        val strokeCol = if (fromMitosis || cfg.popupFrameStyle == PopupFrameStyle.CRIMSON) Color.rgb(244, 63, 94) else accentColors[Random.nextInt(accentColors.size)]

        val card = FrameLayout(applicationContext).apply {
            val padPx = when (cfg.popupFrameStyle) {
                PopupFrameStyle.BORDERLESS -> 0
                PopupFrameStyle.POLAROID -> dp(5)
                else -> dp(3)
            }
            val botPad = if (cfg.popupFrameStyle == PopupFrameStyle.POLAROID) dp(24) else padPx
            setPadding(padPx, padPx, padPx, botPad)
            if (cfg.popupFrameStyle != PopupFrameStyle.BORDERLESS) {
                background = GradientDrawable().apply {
                    cornerRadius = if (cfg.popupFrameStyle == PopupFrameStyle.POLAROID) dp(4).toFloat() else dp(14).toFloat()
                    setColor(if (cfg.popupFrameStyle == PopupFrameStyle.POLAROID) Color.rgb(245, 245, 250) else Color.argb(235, 18, 12, 28))
                    setStroke(dp(if (cfg.popupFrameStyle == PopupFrameStyle.DOUBLE_PULSE) 3 else 2), strokeCol)
                }
            }
            if (cfg.popupRandomRotation) rotation = Random.nextInt(-13, 14).toFloat()
        }

        card.addView(ImageView(applicationContext).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (cfg.popupShowMantra) {
            val isPol = cfg.popupFrameStyle == PopupFrameStyle.POLAROID
            card.addView(TextView(applicationContext).apply {
                text = cfg.mantraList().random()
                setTextColor(if (isPol) Color.rgb(24, 14, 38) else Color.WHITE)
                textSize = if (chosenPct < 38) 10f else 12.5f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                if (!isPol) setBackgroundColor(Color.argb(175, 20, 8, 30))
                setPadding(dp(5), dp(3), dp(5), dp(3))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        var activeEntry: ActiveWindowPopup? = null
        val expireRunnable: Runnable? = if (cfg.popupDurationMs <= 0) null else Runnable {
            val e = activeEntry ?: return@Runnable
            if (e in activeWindowPopups) {
                removeWindowPopup(e)
                if (cfg.popupTouchMode == PopupTouchMode.TAP_DISMISS && cfg.popupMitosisEnabled && isRunning && !portalOpen) {
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                    spawnOnePopup(cfg, fromMitosis = true)
                    spawnOnePopup(cfg, fromMitosis = true)
                }
            }
        }

        if (cfg.popupTouchMode == PopupTouchMode.TAP_DISMISS) {
            var tapsLeft = cfg.popupTapsToClose.coerceIn(1, 3)
            val badge = TextView(applicationContext).apply {
                text = if (tapsLeft > 1) "×$tapsLeft" else "✕"
                setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(218, 225, 29, 72)) }
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }
            card.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4); marginEnd = dp(4)
            })
            var dX = 0f; var dY = 0f
            card.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dX = ev.rawX; dY = ev.rawY; true }
                    MotionEvent.ACTION_UP -> {
                        val dist = hypot((ev.rawX - dX).toDouble(), (ev.rawY - dY).toDouble()).toFloat()
                        if (cfg.popupSwipeDismiss && dist >= dp(42)) {
                            activeEntry?.let { removeWindowPopup(it) }
                        } else {
                            tapsLeft--
                            if (tapsLeft <= 0) activeEntry?.let { removeWindowPopup(it) }
                            else {
                                badge.text = "×$tapsLeft"
                                card.animate().scaleX(0.92f).scaleY(0.92f).setDuration(50L).withEndAction {
                                    card.animate().scaleX(1f).scaleY(1f).setDuration(50L).start()
                                }.start()
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
        } else {
            card.setOnClickListener {
                card.animate().translationX(dp(7).toFloat()).setDuration(40L).withEndAction {
                    card.animate().translationX(-dp(7).toFloat()).setDuration(40L).withEndAction {
                        card.animate().translationX(0f).setDuration(40L).start()
                    }.start()
                }.start()
            }
        }

        if (!mountIndividualPopupWindow(card, posX, posY, targetW, targetH)) return

        // Apply entrance animation style!
        val anim = if (cfg.popupAnimStyle == PopupAnimStyle.RANDOM) {
            listOf(PopupAnimStyle.POP_ZOOM, PopupAnimStyle.INSTANT_FLASH, PopupAnimStyle.SLIDE_IN, PopupAnimStyle.SPIN_IN)[Random.nextInt(4)]
        } else cfg.popupAnimStyle
        when (anim) {
            PopupAnimStyle.INSTANT_FLASH -> card.alpha = targetAlpha
            PopupAnimStyle.SPIN_IN -> {
                card.alpha = 0f; card.scaleX = 0.4f; card.scaleY = 0.4f; card.rotation -= 220f
                card.animate().alpha(targetAlpha).scaleX(1f).scaleY(1f).rotationBy(220f).setDuration(190L).start()
            }
            PopupAnimStyle.SLIDE_IN -> {
                card.alpha = 0.3f; card.translationX = (if (Random.nextBoolean()) 1 else -1) * dp(95).toFloat()
                card.animate().alpha(targetAlpha).translationX(0f).setDuration(170L).start()
            }
            else -> {
                card.alpha = 0f; card.scaleX = 0.65f; card.scaleY = 0.65f
                card.animate().alpha(targetAlpha).scaleX(1f).scaleY(1f).setDuration(150L).start()
            }
        }

        val entry = ActiveWindowPopup(card, expireRunnable)
        activeEntry = entry
        activeWindowPopups.add(entry)
        totalSpawnedCount++
        updatePillLabel()
        if (expireRunnable != null) {
            mainHandler.postDelayed(expireRunnable, cfg.popupDurationMs.coerceIn(100, 60_000).toLong())
        }
    }

    private fun pickWindowTopLeft(zone: PopupSpawnZone, sw: Int, sh: Int, w: Int, h: Int): Pair<Int, Int> {
        val pad = dp(8)
        val minY = dp(48)
        val maxX = (sw - w - pad).coerceAtLeast(pad + 1)
        val maxY = (sh - h - dp(38)).coerceAtLeast(minY + 1)
        return when (zone) {
            PopupSpawnZone.CENTER -> {
                val cx = ((sw - w) / 2 + Random.nextInt(-dp(45), dp(46))).coerceIn(pad, maxX)
                val cy = ((sh - h) / 2 + Random.nextInt(-dp(65), dp(66))).coerceIn(minY, maxY)
                cx to cy
            }
            PopupSpawnZone.EDGES -> when (Random.nextInt(4)) {
                0 -> Random.nextInt(pad, maxX) to minY
                1 -> Random.nextInt(pad, maxX) to maxY
                2 -> pad to Random.nextInt(minY, maxY)
                else -> maxX to Random.nextInt(minY, maxY)
            }
            PopupSpawnZone.GRID -> {
                val cols = (sw / w.coerceAtLeast(dp(64))).coerceIn(2, 4)
                val rows = (sh / h.coerceAtLeast(dp(64))).coerceIn(3, 6)
                val cell = gridCursor++ % (cols * rows)
                val r = cell / cols; val c = cell % cols
                val x = (c * (sw / cols)).coerceIn(pad, maxX)
                val y = (minY + r * ((sh - minY) / rows)).coerceIn(minY, maxY)
                x to y
            }
            PopupSpawnZone.ANYWHERE -> Random.nextInt(pad, maxX) to Random.nextInt(minY, maxY)
        }
    }

    private fun removeWindowPopup(entry: ActiveWindowPopup) {
        entry.expireRunnable?.let { mainHandler.removeCallbacks(it) }
        activeWindowPopups.remove(entry)
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeStormPopupWindow(entry.view)
        else try { fallbackWm?.removeViewImmediate(entry.view) } catch (_: Throwable) {}
        updatePillLabel()
    }

    private fun clearWindowPopupsOnly() {
        for (p in activeWindowPopups.toList()) {
            p.expireRunnable?.let { mainHandler.removeCallbacks(it) }
            if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeStormPopupWindow(p.view)
            else try { fallbackWm?.removeViewImmediate(p.view) } catch (_: Throwable) {}
        }
        activeWindowPopups.clear()
    }

    private fun removeCanvasStageOnly() {
        val st = canvasStage ?: return
        canvasStage = null
        st.clearAll()
        if (mountedViaA11y) GazeAccessibilityService.activeInstance?.removeStormStageOverlay()
        else try { fallbackWm?.removeViewImmediate(st) } catch (_: Throwable) {}
    }

    // ==================== COMPACT DRAGGABLE CONTROL PILL ====================
    private fun mountControlPill(cfg: GazeConfigSnapshot) {
        val pill = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(220, 22, 10, 36))
                setStroke(dp(1), Color.rgb(236, 72, 153))
            }
        }
        val lbl = TextView(applicationContext).apply {
            text = "🖼️ Pop-ups"
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(0, 0, dp(6), 0)
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
        attachPillWindow(pill)
        updatePillLabel()
    }

    @Suppress("DEPRECATION")
    private fun attachPillWindow(pill: View) {
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.mountStormPillOverlay(pill)
        } else {
            try {
                val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                val dm = resources.displayMetrics
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, winType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels - dp(190)).coerceAtLeast(dp(8)); y = dp(44) }
                fallbackWm?.addView(pill, lp); fbPillParams = lp
            } catch (_: Throwable) {}
        }
    }

    private fun remountPillOnTop() {
        val pill = stormPillView ?: return
        if (mountedViaA11y) {
            GazeAccessibilityService.activeInstance?.removeStormPillOverlay()
            GazeAccessibilityService.activeInstance?.mountStormPillOverlay(pill)
        } else {
            try { fallbackWm?.removeViewImmediate(pill) } catch (_: Throwable) {}
            attachPillWindow(pill)
        }
    }

    private fun updatePillLabel() {
        val timeTxt = if (sessionInfinite) "∞" else {
            val s = ((sessionRemainingMs + 999L) / 1000L).coerceAtLeast(0L)
            String.format("%02d:%02d", s / 60L, s % 60L)
        }
        val liveCnt = (canvasStage?.activeCount() ?: 0) + activeWindowPopups.size
        stormPillLabelTv?.text = "🖼️ $timeTxt · ×$liveCnt (#$totalSpawnedCount)"
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
            clearWindowPopupsOnly()
            canvasStage?.clearAll()
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
                onCancelWait = { closePortalWindow() }
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
            repeat(4) { spawnOnePopup(cfg, fromMitosis = true) }
        }
    }

    @Suppress("DEPRECATION")
    private fun mountStageWindow(view: View, touchable: Boolean): Boolean {
        if (mountedViaA11y) {
            return GazeAccessibilityService.activeInstance?.mountStormStageOverlay(view, touchable) == true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val flags = if (touchable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, winType, flags, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            fallbackWm?.addView(view, lp); true
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun mountIndividualPopupWindow(view: View, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (mountedViaA11y) {
            return GazeAccessibilityService.activeInstance?.mountStormPopupWindow(view, x, y, w, h, touchable = true) == true
        }
        return try {
            val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                w, h, winType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
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
        isRunning = false; isExitLocked = false
        if (activeInstance === this) activeInstance = null
        closePortalWindow()
        clearWindowPopupsOnly()
        removeCanvasStageOnly()
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
        clearWindowPopupsOnly()
        removeCanvasStageOnly()
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
            .setContentTitle("🖼️ GazeLock AI v6.0 — Tempête Pop-Ups")
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
