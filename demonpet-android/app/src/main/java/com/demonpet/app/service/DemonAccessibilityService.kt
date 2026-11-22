package com.demonpet.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.demonpet.app.ai.DemonVisionEngine
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonOverlayManager
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Core DemonMom AI Accessibility Engine:
 * Runs ALL 23 selected features (1–25 excluding 17 & 18) locally in real time via TYPE_ACCESSIBILITY_OVERLAY.
 */
class DemonAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: DemonSettings
    private lateinit var visionEngine: DemonVisionEngine
    private lateinit var powerManager: PowerManager

    private var overlayMgr: DemonOverlayManager? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val shotExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "DemonShot").apply { isDaemon = true } }
    private val shotInFlight = AtomicBoolean(false)

    @Volatile
    private var config: DemonConfigSnapshot? = null

    @Volatile
    private var screenInteractive = true

    @Volatile
    private var currentPkg = ""

    @Volatile
    private var currentPkgEnteredMs = 0L

    @Volatile
    private var batteryPct = 100

    @Volatile
    private var isCharging = false

    @Volatile
    private var movingFastActive = false

    private var lastShotMs = 0L
    private var lastPrankMs = 0L
    private var lastCommentMs = 0L
    private var lastLowBatWarnMs = 0L
    private var scrollBurstCount = 0
    private var scrollBurstStartMs = 0L
    private var lastDoomscrollMs = 0L
    private var receiverRegistered = false
    private var locationRegistered = false

    private val appGraceUntilMs = HashMap<String, Long>()
    private val appSessionSeconds = HashMap<String, Long>()
    private var lastSessionTickMs = System.currentTimeMillis()
    private var screenW = 1080f
    private var screenH = 2400f

    private val settingsListener: (DemonConfigSnapshot) -> Unit = { snap ->
        config = snap
        mainHandler.post {
            if (snap.enabled) {
                ensureOverlayAttached(snap)
                overlayMgr?.updateConfig(snap)
                scheduleVisionLoop(150L)
                schedulePrankLoop(15_000L)
            } else {
                overlayMgr?.detachAll()
                overlayMgr = null
                workerHandler?.removeCallbacks(visionTick)
                mainHandler.removeCallbacks(prankTick)
            }
            notifyStateChanged()
        }
    }

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    overlayMgr?.getCensorView()?.clearImmediate()
                    workerHandler?.removeCallbacks(visionTick)
                    mainHandler.removeCallbacks(prankTick)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    if (config?.enabled == true) {
                        scheduleVisionLoop(200L)
                        schedulePrankLoop(20_000L)
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val nowCharging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                    val wasCharging = isCharging
                    isCharging = nowCharging
                    if (lvl >= 0) batteryPct = (lvl * 100) / sc

                    val cfg = config ?: return
                    if (cfg.enabled && cfg.lowBatteryPanicEnabled) {
                        if (!wasCharging && nowCharging && batteryPct <= 25) {
                            overlayMgr?.say("Bravo, enfin branché ! 💖", 4_000L)
                            settings.adjustMood(-8)
                        } else if (!nowCharging && batteryPct <= 20) {
                            val now = System.currentTimeMillis()
                            if (now - lastLowBatWarnMs > 120_000L) {
                                lastLowBatWarnMs = now
                                overlayMgr?.triggerPanicMode("CHARGE-LE TOUT DE SUITE ($batteryPct%) ! 🔋😱", 5_500L)
                            }
                        }
                    }
                }
            }
        }
    }

    private val passiveSpeedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cfg = config ?: return
            if (!cfg.enabled || !cfg.speedWarningEnabled) {
                movingFastActive = false
                return
            }
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            if (speedKmh >= cfg.speedThresholdKmh) {
                if (!movingFastActive) {
                    movingFastActive = true
                    overlayMgr?.triggerPanicMode("Regarde la route ! (${speedKmh.toInt()} km/h) 🚗😈", 5_000L)
                }
            } else if (speedKmh < 8f) {
                movingFastActive = false
            }
        }
    }

    private val visionTick = object : Runnable {
        override fun run() {
            val cfg = config ?: return
            if (!screenInteractive || !cfg.enabled || !cfg.anyAiVisionEnabled) return
            if (currentPkg == packageName) {
                overlayMgr?.getCensorView()?.clearImmediate()
                workerHandler?.postDelayed(this, 1_200L)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

            val now = System.currentTimeMillis()
            val wait = 1_030L - (now - lastShotMs)
            if (wait > 0L) {
                workerHandler?.postDelayed(this, wait + 20L)
                return
            }
            if (shotInFlight.compareAndSet(false, true)) {
                lastShotMs = now
                captureAndAnalyze(cfg)
            }
            workerHandler?.postDelayed(this, 1_050L)
        }
    }

    private val prankTick = object : Runnable {
        override fun run() {
            val cfg = config ?: return
            if (!screenInteractive || !cfg.enabled) return

            val now = System.currentTimeMillis()
            // Update session usage for watched apps (#8)
            val dtSec = ((now - lastSessionTickMs) / 1000L).coerceIn(1L, 30L)
            lastSessionTickMs = now
            if (currentPkg.isNotBlank() && cfg.watchedPackages.contains(currentPkg)) {
                val total = (appSessionSeconds[currentPkg] ?: 0L) + dtSec
                appSessionSeconds[currentPkg] = total
                // Ramp up #6 GPU Sabotage intensity over 3 minutes of video watching!
                val progress = (0.30f + (total / 180f) * 0.70f).coerceIn(0.30f, 1.0f)
                overlayMgr?.getCensorView()?.sabotageProgress = progress

                val grace = appGraceUntilMs[currentPkg] ?: 0L
                if (cfg.appQuotaLockEnabled && now > grace && total >= cfg.appQuotaMinutes * 60L) {
                    appSessionSeconds[currentPkg] = 0L
                    settings.adjustMood(+8)
                    overlayMgr?.showLockShieldWithNegotiation(
                        title = "C'est bon, ça suffit ! 😈",
                        reason = "Tu as déjà passé ${cfg.appQuotaMinutes} min sur cette appli. Pause obligatoire !",
                        pkg = currentPkg,
                        allowNegotiation = true,
                        cfg = cfg
                    )
                }
            }

            // #10 Curfew check + #19 Night Brightness Dim
            val curfewNow = isCurfewActive(cfg)
            overlayMgr?.setCurfewNightcap(curfewNow)
            if (curfewNow && cfg.nightBrightnessEnabled) {
                dimNightBrightnessSafely()
            }

            // Periodic Mischievous Prank Trigger (#14 Wallpaper / #15 Dragged Popup / #16 Fullscreen Flash)
            val chaosFactor = (11 - cfg.chaosLevel).coerceIn(1, 10)
            val moodBoostMs = if (cfg.moodScore > 70) 0.72f else 1.0f
            val intervalMs = (cfg.minIntervalSec * 1000L * (0.45f + chaosFactor * 0.14f) * moodBoostMs).toLong().coerceAtLeast(15_000L)
            if (currentPkg != packageName && (now - lastPrankMs) >= intervalMs) {
                lastPrankMs = now
                runRandomMischief(cfg)
            }

            mainHandler.postDelayed(this, 6_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = DemonSettings(applicationContext)
        config = settings.snapshot()
        visionEngine = DemonVisionEngine(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels.toFloat().coerceAtLeast(360f)
        screenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
        screenInteractive = try { powerManager.isInteractive } catch (_: Throwable) { true }

        val ht = HandlerThread("DemonMomWorker").apply { start() }
        workerThread = ht
        workerHandler = Handler(ht.looper)

        registerReceivers()
        registerPassiveLocation()
        DemonSettings.addListener(settingsListener)
        activeInstance = this

        val snap = config!!
        if (snap.enabled) {
            ensureOverlayAttached(snap)
            scheduleVisionLoop(200L)
            schedulePrankLoop(12_000L)
        }
        notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val cfg = config ?: return
        if (!cfg.enabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()?.trim().orEmpty()
                if (pkg.isEmpty()) return
                val lower = pkg.lowercase()
                if (lower == "com.android.systemui" || lower.contains("inputmethod") || lower.contains("gboard")) return
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    currentPkgEnteredMs = System.currentTimeMillis()
                    scrollBurstCount = 0
                    overlayMgr?.getCensorView()?.clearImmediate()
                    onForegroundAppChanged(pkg, cfg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 0ms scroll-shift for censor overlays
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val dy = event.scrollDeltaY
                    val dx = event.scrollDeltaX
                    if (dy != 0 || dx != 0) {
                        overlayMgr?.getCensorView()?.shiftMasks(
                            (-dx.toFloat() / screenW).coerceIn(-0.4f, 0.4f),
                            (-dy.toFloat() / screenH).coerceIn(-0.4f, 0.4f)
                        )
                    }
                }
                // #12 Anti-Doomscrolling detector
                if (cfg.antiDoomscrollEnabled && currentPkg != packageName) {
                    val now = System.currentTimeMillis()
                    if (now - scrollBurstStartMs > 45_000L) {
                        scrollBurstStartMs = now
                        scrollBurstCount = 1
                    } else {
                        scrollBurstCount++
                    }
                    if (scrollBurstCount >= 32 && now - lastDoomscrollMs > 90_000L) {
                        scrollBurstCount = 0
                        lastDoomscrollMs = now
                        settings.adjustMood(+6)
                        overlayMgr?.triggerSitInCenter("Arrête de scroller à l'infini !! 😤", 7_500L)
                        if (cfg.draggedPopupEnabled) {
                            overlayMgr?.showDraggedPhotoPopup(cfg)
                        }
                    }
                }
            }
        }
    }

    private fun onForegroundAppChanged(pkg: String, cfg: DemonConfigSnapshot) {
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        val grace = appGraceUntilMs[pkg] ?: 0L
        val isWatched = cfg.watchedPackages.contains(pkg)
        val isGame = isGamePackage(pkg)

        // #13 Panique dans les Paramètres (+ Verrouillage défi si Challenge Lock activé)
        if (pkg == "com.android.settings" || pkg.contains("packageinstaller")) {
            if (cfg.settingsPanicEnabled) {
                overlayMgr?.triggerPanicMode("HEIN ?! Tu vas pas me désactiver ?! 😱", 6_000L)
            }
            if (cfg.challengeLocked) {
                overlayMgr?.showLockShieldWithNegotiation(
                    title = "Verrouillage Démon actif 🔒😈",
                    reason = "Paramètres verrouillés ! Pour tout déverrouiller proprement, tapote le démon 20 fois de suite et saisis le code 100205.",
                    pkg = pkg,
                    allowNegotiation = false,
                    cfg = cfg
                )
            }
            return
        }

        // #23 Alerte Vitesse / Route
        if (movingFastActive && cfg.speedWarningEnabled && (isWatched || isGame)) {
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Regarde la route ! 🚗😈",
                reason = "Tu bouges trop vite (> ${cfg.speedThresholdKmh} km/h). Pas de vidéos ni réseaux en déplacement !",
                pkg = pkg,
                allowNegotiation = false,
                cfg = cfg
            )
            return
        }

        // #20 Panique Batterie Faible (< 20%) -> bloque les jeux jusqu'à charge
        if (cfg.lowBatteryPanicEnabled && !isCharging && batteryPct <= 20 && isGame) {
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Batterie à $batteryPct% ! 🔋😱",
                reason = "Branche ton téléphone TOUT DE SUITE avant de jouer !",
                pkg = pkg,
                allowNegotiation = false,
                cfg = cfg
            )
            return
        }

        // #10 Couvre-feu « Au lit ! »
        if (isCurfewActive(cfg) && (isWatched || isGame) && now > grace) {
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Couvre-feu ! Au lit ! 🌙😈",
                reason = "Il est tard (${cfg.curfewStartHour}h–${cfg.curfewEndHour}h). Pose le téléphone et dors !",
                pkg = pkg,
                allowNegotiation = true,
                cfg = cfg
            )
            return
        }

        // #11 Mode « Fais tes devoirs ! »
        if (cfg.homeworkModeEnabled && (isWatched || isGame) && now > grace) {
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Fais tes devoirs d'abord ! 📚😤",
                reason = "Mode Devoirs actif : pas de jeux ni réseaux sociaux pour le moment.",
                pkg = pkg,
                allowNegotiation = true,
                cfg = cfg
            )
            return
        }

        // #7 « Demande la permission d'abord ! »
        if (cfg.askPermissionGate && isWatched && now > grace) {
            val label = resolveAppLabel(pkg)
            overlayMgr?.showPermissionGatePopup(label, pkg, cfg)
            return
        }

        // #22 Commentaires en direct selon l'appli ouverte
        if (cfg.appCommentsEnabled && now - lastCommentMs > 14_000L) {
            val comment = commentForPackage(pkg, isGame, cfg)
            if (comment != null) {
                lastCommentMs = now
                overlayMgr?.say(comment, 4_200L)
            }
        }
    }

    private fun runRandomMischief(cfg: DemonConfigSnapshot) {
        val actions = ArrayList<() -> Unit>()
        if (cfg.draggedPopupEnabled) actions.add { overlayMgr?.showDraggedPhotoPopup(cfg) }
        if (cfg.fullscreenFlashEnabled) actions.add { overlayMgr?.showFullscreenPhotoFlash(cfg) }
        if (cfg.wallpaperPrankEnabled) {
            actions.add {
                overlayMgr?.say("Nouveau fond d'écran surprise ! 🖼️😈", 4_000L)
                Thread { MediaFolderHelper.applyRandomWallpaperPrank(applicationContext, cfg) }.start()
            }
        }
        if (actions.isEmpty()) return
        actions.random().invoke()
    }

    fun triggerManualTest(kind: String) {
        val cfg = settings.snapshot()
        ensureOverlayAttached(cfg)
        when (kind) {
            "popup" -> overlayMgr?.showDraggedPhotoPopup(cfg)
            "flash" -> overlayMgr?.showFullscreenPhotoFlash(cfg)
            "wallpaper" -> {
                overlayMgr?.say("Changement de fond d'écran ! 🖼️😈", 4_000L)
                Thread { MediaFolderHelper.applyRandomWallpaperPrank(applicationContext, cfg) }.start()
            }
            "lock" -> overlayMgr?.showLockShieldWithNegotiation(
                title = "Test Blocage & Négociation 😈",
                reason = "Voici l'écran de blocage quand tu dépasses ton quota !",
                pkg = "test.demo",
                allowNegotiation = true,
                cfg = cfg
            )
        }
    }

    private fun captureAndAnalyze(cfg: DemonConfigSnapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            shotInFlight.set(false)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var hw: Bitmap? = null
                    var soft: Bitmap? = null
                    var scaled: Bitmap? = null
                    try {
                        val buf = screenshot.hardwareBuffer
                        try { hw = Bitmap.wrapHardwareBuffer(buf, screenshot.colorSpace) } finally {
                            try { buf.close() } catch (_: Throwable) {}
                        }
                        if (hw != null) {
                            soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle(); hw = null
                        }
                        val srcSoft = soft
                        if (srcSoft != null) {
                            val ratio = 320f / minOf(srcSoft.width, srcSoft.height).coerceAtLeast(1)
                            scaled = if (ratio < 0.95f) {
                                Bitmap.createScaledBitmap(
                                    srcSoft,
                                    (srcSoft.width * ratio).toInt().coerceAtLeast(160),
                                    (srcSoft.height * ratio).toInt().coerceAtLeast(240),
                                    true
                                ).also {
                                    srcSoft.recycle()
                                    soft = null
                                }
                            } else {
                                srcSoft.also { soft = null }
                            }
                        }
                    } catch (_: Throwable) {
                        try { hw?.recycle() } catch (_: Throwable) {}
                        try { soft?.recycle() } catch (_: Throwable) {}
                        try { scaled?.recycle() } catch (_: Throwable) {}
                        scaled = null
                    } finally {
                        shotInFlight.set(false)
                    }
                    if (scaled != null) {
                        val ref = scaled
                        workerHandler?.post {
                            try {
                                val targets = visionEngine.analyzeFrame(ref, cfg)
                                overlayMgr?.getCensorView()?.updateTargets(targets, cfg)
                                if (targets.any { it.kind == "nsfw" }) {
                                    settings.adjustMood(+5)
                                    overlayMgr?.say("PAS DE ÇA ICI !! 🙈😈", 3_500L)
                                } else if (targets.isNotEmpty() && Random.nextFloat() < 0.14f) {
                                    overlayMgr?.say("C'est qui ça ?! Regarde-MOI 👀", 3_000L)
                                }
                            } catch (_: Throwable) {
                            } finally {
                                if (!ref.isRecycled) ref.recycle()
                            }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    shotInFlight.set(false)
                }
            })
        } catch (_: Throwable) {
            shotInFlight.set(false)
        }
    }

    private fun ensureOverlayAttached(cfg: DemonConfigSnapshot) {
        if (overlayMgr != null) return
        val mgr = DemonOverlayManager(
            context = this,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            settings = settings,
            onGoHome = {
                try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            },
            onExitUnlocked = {
                performSecretExitUnlock()
            },
            onGraceGranted = { pkg, mins ->
                appGraceUntilMs[pkg] = System.currentTimeMillis() + mins * 60_000L
            }
        )
        overlayMgr = mgr
        mgr.attach(cfg)
    }

    fun performSecretExitUnlock() {
        DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
        settings.update {
            putBoolean("challenge_locked", false)
            putBoolean("enabled", false)
        }
        overlayMgr?.detachAll()
        overlayMgr = null
        DemonCompanionService.stop(applicationContext)
        notifyStateChanged()
    }

    private fun scheduleVisionLoop(delayMs: Long) {
        val h = workerHandler ?: return
        h.removeCallbacks(visionTick)
        if (screenInteractive && config?.enabled == true && config?.anyAiVisionEnabled == true) {
            h.postDelayed(visionTick, delayMs)
        }
    }

    private fun schedulePrankLoop(delayMs: Long) {
        mainHandler.removeCallbacks(prankTick)
        if (screenInteractive && config?.enabled == true) {
            mainHandler.postDelayed(prankTick, delayMs)
        }
    }

    private fun isCurfewActive(cfg: DemonConfigSnapshot): Boolean {
        if (!cfg.curfewEnabled) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = cfg.curfewStartHour
        val e = cfg.curfewEndHour
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

    private fun dimNightBrightnessSafely() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(applicationContext)) {
                val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 120)
                if (cur > 55) {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 42)
                    overlayMgr?.say("Tu vas t'abîmer les yeux ! Luminosité baissée 👀", 3_800L)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun commentForPackage(pkg: String, isGame: Boolean, cfg: DemonConfigSnapshot): String? {
        val lower = pkg.lowercase()
        val custom = cfg.customPhraseList()
        if (custom.isNotEmpty() && Random.nextFloat() < 0.25f) return custom.random()
        return when {
            lower.contains("tiktok") || lower.contains("musically") -> "Encore sur TikTok ?! 👀"
            lower.contains("instagram") -> "Tu regardes qui sur Insta là ? 😈"
            lower.contains("youtube") -> "Une petite vidéo ? Je surveille ! 👀"
            lower.contains("snapchat") -> "Tu snap avec qui ?! 😤"
            lower.contains("whatsapp") || lower.contains("messenger") -> "À qui tu écris là ? 👀"
            lower.contains("discord") -> "Sur Discord au lieu de bosser ? 😈"
            lower.contains("gallery") || lower.contains("photos") -> "Je vois tes photos moi aussi ! 👀"
            isGame -> "Encore un jeu ?! Fais attention au chrono ! 🎮"
            else -> null
        }
    }

    private fun isGamePackage(pkg: String): Boolean {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            pkg.substringAfterLast('.')
        }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            registerReceiver(sysReceiver, f)
            receiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun registerPassiveLocation() {
        if (locationRegistered) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 12_000L, 15f, passiveSpeedListener, Looper.getMainLooper())
                locationRegistered = true
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (activeInstance === this) activeInstance = null
        DemonSettings.removeListener(settingsListener)
        if (receiverRegistered) {
            try { unregisterReceiver(sysReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        if (locationRegistered) {
            try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(passiveSpeedListener) } catch (_: Throwable) {}
            locationRegistered = false
        }
        workerHandler?.removeCallbacks(visionTick)
        mainHandler.removeCallbacks(prankTick)
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        overlayMgr?.detachAll()
        overlayMgr = null
        try { visionEngine.close() } catch (_: Throwable) {}
        notifyStateChanged()
    }

    companion object {
        @Volatile
        var activeInstance: DemonAccessibilityService? = null
            private set

        private val stateListeners = CopyOnWriteArraySet<() -> Unit>()
        fun isServiceActive(): Boolean = activeInstance != null
        fun addStateListener(l: () -> Unit) { stateListeners.add(l) }
        fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }
        fun notifyStateChanged() { for (l in stateListeners) try { l() } catch (_: Throwable) {} }

        fun isEnabledInSettings(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val full = ComponentName(context, DemonAccessibilityService::class.java).flattenToString()
                val short = ComponentName(context, DemonAccessibilityService::class.java).flattenToShortString()
                enabled.contains(full, true) || enabled.contains(short, true)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
