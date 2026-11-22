package com.demonpet.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.demonpet.app.ai.DemonVisionEngine
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.mdm.DemonMdmManager
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
    private lateinit var keyguardManager: KeyguardManager

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
    private var lastCounterScrollMs = 0L
    private var lastKbSabotageMs = 0L
    private var lastShadeDismissMs = 0L
    private var lastClickShieldMs = 0L
    private var lastSettingsEjectMs = 0L
    private var scrollBurstCount = 0
    private var scrollBurstStartMs = 0L
    private var lastDoomscrollMs = 0L
    private var receiverRegistered = false
    private var locationRegistered = false

    private val appGraceUntilMs = HashMap<String, Long>()
    private val appSessionSeconds = HashMap<String, Long>()
    private var lastSessionTickMs = System.currentTimeMillis()
    private var continuousScreenSeconds = 0L
    private var screenOffSinceMs = 0L
    private var screenW = 1080f
    private var screenH = 2400f

    private val settingsListener: (DemonConfigSnapshot) -> Unit = { snap ->
        config = snap
        mainHandler.post {
            if (snap.enabled) {
                ensureOverlayAttached(snap)
                overlayMgr?.updateConfig(snap)
                scheduleVisionLoop(150L)
                schedulePrankLoop(2_000L)
                scheduleGuardLoop(650L)
            } else {
                overlayMgr?.detachAll()
                overlayMgr = null
                workerHandler?.removeCallbacks(visionTick)
                mainHandler.removeCallbacks(prankTick)
                mainHandler.removeCallbacks(guardTick)
            }
            notifyStateChanged()
        }
    }

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    screenOffSinceMs = System.currentTimeMillis()
                    overlayMgr?.getCensorView()?.clearImmediate()
                    workerHandler?.removeCallbacks(visionTick)
                    mainHandler.removeCallbacks(prankTick)
                    mainHandler.removeCallbacks(guardTick)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    if (screenOffSinceMs > 0L && System.currentTimeMillis() - screenOffSinceMs > 90_000L) {
                        continuousScreenSeconds = 0L
                    }
                    lastSessionTickMs = System.currentTimeMillis()
                    if (config?.enabled == true) {
                        scheduleVisionLoop(200L)
                        schedulePrankLoop(4_000L)
                        scheduleGuardLoop(650L)
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

    /** Fast 650ms guard loop: keeps currentPkg synced & enforces strict Settings Lock + #44 Blacklist instantly */
    private val guardTick = object : Runnable {
        override fun run() {
            val cfg = config ?: return
            if (!screenInteractive || !cfg.enabled) return
            syncActivePackageFromRoot(cfg)
            mainHandler.postDelayed(this, 650L)
        }
    }

    private val prankTick = object : Runnable {
        override fun run() {
            val cfg = config ?: return
            if (!screenInteractive || !cfg.enabled) return

            syncActivePackageFromRoot(cfg)

            val now = System.currentTimeMillis()
            // Update continuous screen timer (#68 — default 3 min!) & watched app quota (#8)
            val dtSec = ((now - lastSessionTickMs) / 1000L).coerceIn(1L, 15L)
            lastSessionTickMs = now
            val modalOpen = overlayMgr?.isAnyModalActive() == true

            if (cfg.screenBreakEnabled && !modalOpen && currentPkg.isNotBlank() && currentPkg != packageName) {
                continuousScreenSeconds += dtSec
                if (continuousScreenSeconds >= cfg.screenBreakMinutes * 60L) {
                    continuousScreenSeconds = 0L
                    overlayMgr?.showScreenBreakOverlay(cfg.screenBreakDurationSec, cfg)
                }
            }

            val isWatchedOrGame = currentPkg.isNotBlank() && currentPkg != packageName &&
                (cfg.watchedPackages.contains(currentPkg) || isGamePackage(currentPkg))
            if (isWatchedOrGame && !modalOpen) {
                val total = (appSessionSeconds[currentPkg] ?: 0L) + dtSec
                appSessionSeconds[currentPkg] = total
                val progress = (0.35f + (total / 90f) * 0.65f).coerceIn(0.35f, 1.0f)
                overlayMgr?.getCensorView()?.sabotageProgress = progress

                if (total % 9L < dtSec) {
                    settings.adjustMood(+2)
                }
                // #121 Passive Level Drain on watched/social apps (-1 NV every ~2 min)
                if (cfg.rpgSeductionEnabled && total > 0L && total % 120L < dtSec) {
                    settings.adjustPlayerStats(levelDelta = -1, hpDelta = -6, drainDelta = +5)
                    overlayMgr?.say("💜 Drain passif : -1 NV (tu restes trop ici !)", 3_000L)
                }

                val grace = appGraceUntilMs[currentPkg] ?: 0L
                if (cfg.appQuotaLockEnabled && now > grace && total >= cfg.appQuotaMinutes * 60L) {
                    appSessionSeconds[currentPkg] = 0L
                    settings.adjustMood(+12)
                    if (cfg.timeoutCornerEnabled && cfg.chaosLevel >= 8) {
                        overlayMgr?.showTimeoutCornerWithVideo(
                            reason = "Quota dépassé (${cfg.appQuotaMinutes} min) ! Direction AU COIN ! 😈",
                            durationSec = cfg.timeoutCornerSec,
                            cfg = cfg,
                            originPkg = currentPkg
                        )
                    } else {
                        overlayMgr?.showLockShieldWithNegotiation(
                            title = "C'est bon, ça suffit ! 😈",
                            reason = "Tu as déjà passé ${cfg.appQuotaMinutes} min sur cette appli. Pause obligatoire !",
                            pkg = currentPkg,
                            allowNegotiation = true,
                            cfg = cfg
                        )
                    }
                }
            }

            // #10 Curfew check + #19 Night Brightness Dim
            val curfewNow = isCurfewActive(cfg)
            overlayMgr?.setCurfewNightcap(curfewNow)
            if (curfewNow && cfg.nightBrightnessEnabled) {
                dimNightBrightnessSafely()
            }

            // Faithful, aggressive periodic mischief interval:
            // Respects minIntervalSec directly, and cuts it by up to 40% when furious or Tyran!
            val moodMul = when {
                cfg.moodScore >= 85 -> 0.60f
                cfg.moodScore >= 65 -> 0.78f
                else -> 1.0f
            }
            val intervalMs = (cfg.minIntervalSec * 1000L * moodMul).toLong().coerceAtLeast(6_000L)
            val isOutsideSelf = currentPkg.isNotBlank() && currentPkg != packageName
            if (isOutsideSelf && (now - lastPrankMs) >= intervalMs) {
                lastPrankMs = now
                runRandomMischief(cfg)
            }

            mainHandler.postDelayed(this, 3_000L)
        }
    }

    private fun syncActivePackageFromRoot(cfg: DemonConfigSnapshot) {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = try { root.packageName?.toString()?.trim().orEmpty() } finally {
            try { root.recycle() } catch (_: Throwable) {}
        }
        if (pkg.isEmpty()) return
        val lower = pkg.lowercase()
        if (lower == "com.android.systemui") {
            maybeBlockSystemUiShade(cfg, lower, "")
            return
        }
        if (lower.contains("inputmethod") || lower.contains("gboard") || lower.contains("swiftkey")) return

        if (isProtectedSettingsPackage(lower) && (cfg.challengeLocked || cfg.strictSettingsLock)) {
            enforceStrictSettingsLock(pkg, cfg)
            return
        }
        if (pkg != currentPkg) {
            currentPkg = pkg
            currentPkgEnteredMs = System.currentTimeMillis()
            scrollBurstCount = 0
            overlayMgr?.getCensorView()?.clearImmediate()
            onForegroundAppChanged(pkg, cfg)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = DemonSettings(applicationContext)
        config = settings.snapshot()
        visionEngine = DemonVisionEngine(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
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
            schedulePrankLoop(3_500L)
            scheduleGuardLoop(650L)
        }
        notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val cfg = config ?: return
        if (!cfg.enabled) return
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()?.trim().orEmpty()
                if (pkg.isEmpty()) return
                val lower = pkg.lowercase()
                val cls = event.className?.toString()?.lowercase().orEmpty()
                val evText = event.text.joinToString(" ").lowercase()

                // #34 & #35 Notification Shade & Quick Settings Blocker
                if (lower == "com.android.systemui") {
                    maybeBlockSystemUiShade(cfg, cls, evText)
                    return
                }
                if (lower.contains("inputmethod") || lower.contains("gboard") || lower.contains("swiftkey")) return

                // #38 Timeout Corner Anti-Escape (only resets if user actually changes to a DIFFERENT app, max 2x!)
                if (overlayMgr?.isTimeoutCornerActive() == true && pkg != packageName) {
                    overlayMgr?.resetTimeoutCountdownIfEscaping(pkg)
                    return
                }

                // Strict Settings Lock check even if already in same package
                if (isProtectedSettingsPackage(lower) && (cfg.challengeLocked || cfg.strictSettingsLock)) {
                    enforceStrictSettingsLock(pkg, cfg)
                    return
                }

                if (pkg != currentPkg) {
                    currentPkg = pkg
                    currentPkgEnteredMs = now
                    scrollBurstCount = 0
                    overlayMgr?.getCensorView()?.clearImmediate()
                    onForegroundAppChanged(pkg, cfg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val evPkg = event.packageName?.toString()?.trim().orEmpty()
                if (evPkg.isNotEmpty() && evPkg != "com.android.systemui" && evPkg != currentPkg &&
                    !evPkg.lowercase().contains("inputmethod")
                ) {
                    currentPkg = evPkg
                    currentPkgEnteredMs = now
                    onForegroundAppChanged(evPkg, cfg)
                }

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

                // #26 Contre-scroll / Vol de scroll (aggressive & responsive!)
                if (cfg.counterScrollEnabled && currentPkg.isNotBlank() && currentPkg != packageName) {
                    val cooldown = (12_000L - cfg.chaosLevel * 850L).coerceAtLeast(3_500L)
                    val prob = (0.30f + cfg.chaosLevel * 0.055f).coerceAtMost(0.88f)
                    if (now - lastCounterScrollMs > cooldown && Random.nextFloat() < prob) {
                        lastCounterScrollMs = now
                        performCounterScrollGesture(doubleSwipe = cfg.chaosLevel >= 9)
                        overlayMgr?.say("Hop ! On remonte ! Tu scrolles trop ! 😈", 2_800L)
                    }
                }

                // #12 Anti-Doomscrolling detector (triggers after 12 scrolls!)
                if (cfg.antiDoomscrollEnabled && currentPkg.isNotBlank() && currentPkg != packageName) {
                    if (now - scrollBurstStartMs > 35_000L) {
                        scrollBurstStartMs = now
                        scrollBurstCount = 1
                    } else {
                        scrollBurstCount++
                    }
                    if (scrollBurstCount >= 12 && now - lastDoomscrollMs > 45_000L) {
                        scrollBurstCount = 0
                        lastDoomscrollMs = now
                        settings.adjustMood(+8)
                        overlayMgr?.triggerSitInCenter("Arrête de scroller à l'infini !! 😤", 7_500L)
                        if (cfg.counterScrollEnabled) performCounterScrollGesture(doubleSwipe = true)
                        if (cfg.draggedPopupEnabled) overlayMgr?.showDraggedPhotoPopup(cfg)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // #32 Sabotage de saisie clavier (triggers reliably from 4 chars, with BACK/DeadZone fallback!)
                val pkg = event.packageName?.toString().orEmpty()
                if (cfg.keyboardSabotageEnabled && pkg.isNotBlank() && pkg != packageName &&
                    overlayMgr?.isTimeoutCornerActive() != true
                ) {
                    val typed = event.text.joinToString(" ")
                    val cooldown = (24_000L - cfg.chaosLevel * 1_600L).coerceAtLeast(8_000L)
                    val prob = (0.35f + cfg.chaosLevel * 0.055f).coerceAtMost(0.90f)
                    if (typed.length >= 4 && now - lastKbSabotageMs > cooldown && Random.nextFloat() < prob) {
                        lastKbSabotageMs = now
                        var replaced = false
                        val src = event.source
                        if (src != null) {
                            try {
                                if (src.isEditable) {
                                    val replacement = KEYBOARD_SABOTAGE_PHRASES.random()
                                    val args = Bundle().apply {
                                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
                                    }
                                    replaced = src.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                }
                            } catch (_: Throwable) {
                            } finally {
                                try { src.recycle() } catch (_: Throwable) {}
                            }
                        }
                        if (!replaced) {
                            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) {}
                            if (cfg.deadZoneKeyboardEnabled && cfg.chaosLevel >= 8) {
                                overlayMgr?.showDeadZoneKeyboardOverlay(18)
                            }
                        }
                        settings.adjustMood(+5)
                        overlayMgr?.say(
                            if (replaced) "Hihi j'ai corrigé ton message ! 😈" else "Clavier fermé ! Tu écris à qui là ?! 😈",
                            3_200L
                        )
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkgRaw = event.packageName?.toString()?.trim().orEmpty()
                val pkgLower = pkgRaw.lowercase()
                if (pkgLower == "com.android.systemui") {
                    maybeBlockSystemUiShade(cfg, event.className?.toString()?.lowercase().orEmpty(), event.text.joinToString(" ").lowercase())
                    return
                }
                if (pkgRaw.isNotEmpty() && !pkgLower.contains("inputmethod") && !pkgLower.contains("gboard")) {
                    if (isProtectedSettingsPackage(pkgLower) && (cfg.challengeLocked || cfg.strictSettingsLock)) {
                        enforceStrictSettingsLock(pkgRaw, cfg)
                        return
                    }
                    if (pkgRaw != currentPkg) {
                        currentPkg = pkgRaw
                        currentPkgEnteredMs = now
                        scrollBurstCount = 0
                        onForegroundAppChanged(pkgRaw, cfg)
                    }
                }
                // #31, #37 & #71 Click-Steal / Anti-Like / Anti-Buy & Install Shield across ALL apps
                if ((cfg.clickStealShieldEnabled || cfg.antiLikeFollowEnabled || cfg.blockStoreBuyEnabled) &&
                    currentPkg.isNotBlank() && currentPkg != packageName && !isLauncherPackage(currentPkg)
                ) {
                    val cooldown = (14_000L - cfg.chaosLevel * 850L).coerceAtLeast(5_500L)
                    if (now - lastClickShieldMs > cooldown) {
                        lastClickShieldMs = now
                        scanAndShieldSensitiveButton(cfg)
                    }
                }
            }
        }
    }

    private fun isProtectedSettingsPackage(lowerPkg: String): Boolean {
        return lowerPkg == "com.android.settings" ||
            lowerPkg.contains("packageinstaller") ||
            lowerPkg.contains("miui.securitycenter") ||
            lowerPkg.contains("samsung.lool") ||
            lowerPkg.contains("safecenter") ||
            lowerPkg.contains("systemmanager") ||
            lowerPkg.contains("permissioncontroller")
    }

    private fun enforceStrictSettingsLock(pkg: String, cfg: DemonConfigSnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastSettingsEjectMs < 900L) return
        lastSettingsEjectMs = now
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        overlayMgr?.triggerPanicMode("Paramètres VERROUILLÉS ! Tapote le démon 20× + 100205 🔒😈", 5_500L)
        if (cfg.challengeLocked) {
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Paramètres verrouillés 🔒😈",
                reason = "Accès aux Paramètres et à la désinstallation bloqué ! Tapote le démon 20 fois de suite et saisis le code 100205 pour sortir.",
                pkg = pkg,
                allowNegotiation = false,
                cfg = cfg
            )
        }
    }

    private fun maybeBlockSystemUiShade(cfg: DemonConfigSnapshot, cls: String, evText: String) {
        if (!cfg.closeNotifShadeEnabled && !cfg.blockQuickSettingsEnabled) return
        if (try { keyguardManager.isKeyguardLocked } catch (_: Throwable) { false }) return
        if (cls.contains("volume") || cls.contains("toast")) return
        val now = System.currentTimeMillis()
        if (now - lastShadeDismissMs < 1_400L) return
        lastShadeDismissMs = now
        dismissNotificationShadeSafely()
        settings.adjustMood(+4)
        val isQs = cls.contains("qs") || cls.contains("quick") || cls.contains("control") ||
            evText.contains("wi-fi") || evText.contains("wifi") || evText.contains("avion")
        overlayMgr?.say(
            if (isQs) "Pas touche aux Réglages Rapides ! 😈" else "Notifications interdites ! 😤",
            2_800L
        )
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l.contains("launcher") || l.contains("trebuchet") || l.contains("miui.home") || l.contains("nexuslauncher")
    }

    private fun dismissNotificationShadeSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
        }
        // Fallback upward swipe from bottom of shade for OEM Control Centers (MIUI/HyperOS/OneUI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(screenW * 0.5f, screenH * 0.88f)
                    lineTo(screenW * 0.5f, screenH * 0.12f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 180L)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            } catch (_: Throwable) {
            }
        }
    }

    /** #26 Real downward-drag gesture (scrolls content back UP!) */
    private fun performCounterScrollGesture(doubleSwipe: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        fun swipeOnce() {
            try {
                val path = Path().apply {
                    moveTo(screenW * 0.5f, screenH * 0.24f)
                    lineTo(screenW * 0.5f, screenH * 0.84f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 210L)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            } catch (_: Throwable) {
            }
        }
        swipeOnce()
        if (doubleSwipe) {
            mainHandler.postDelayed({ swipeOnce() }, 300L)
        }
    }

    /** #29 Force pause video via Accessibility node or center screen tap gesture */
    private fun performVideoForcePause() {
        var clickedPause = false
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val target = findFirstMatchingNode(root, listOf("pause", "mettre en pause"))
                if (target != null) {
                    clickedPause = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target.recycle()
                }
                root.recycle()
            }
        } catch (_: Throwable) {
        }
        if (!clickedPause && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply { moveTo(screenW * 0.5f, screenH * 0.42f) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            } catch (_: Throwable) {
            }
        }
        overlayMgr?.say("PAUSE ! Regarde-moi d'abord ! ⏸️😈", 3_200L)
    }

    /** #31, #37 & #71 Scans UI tree for Like / Follow / Send / Buy / Install buttons and teleports a Demon shield onto them */
    private fun scanAndShieldSensitiveButton(cfg: DemonConfigSnapshot) {
        try {
            val root = rootInActiveWindow ?: return
            val keys = ArrayList<String>()
            if (cfg.blockStoreBuyEnabled) {
                keys.addAll(listOf("installer", "install", "acheter", "buy", "payer", "pay", "commander", "obtenir"))
            }
            if (cfg.antiLikeFollowEnabled) {
                keys.addAll(listOf("j'aime", "like", "s'abonner", "suivre", "follow", "subscribe"))
            }
            if (cfg.clickStealShieldEnabled) {
                keys.addAll(listOf("envoyer", "send", "commenter", "comment"))
            }
            val found = findFirstMatchingNode(root, keys)
            if (found != null) {
                val r = Rect()
                found.getBoundsInScreen(r)
                found.recycle()
                if (r.width() > 24 && r.height() > 24 && r.top > 80 && r.bottom < screenH - 40) {
                    val msg = if (cfg.blockStoreBuyEnabled && isStorePackage(currentPkg.lowercase())) {
                        "Tu n'installes et n'achètes RIEN sans moi ! 😈"
                    } else {
                        "Interdit d'appuyer là-dessus ! 😈"
                    }
                    overlayMgr?.showClickStealShield(r, msg, 9_000L)
                }
            }
            root.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun isStorePackage(lowerPkg: String): Boolean {
        return lowerPkg == "com.android.vending" ||
            lowerPkg.contains("galaxyapps") ||
            lowerPkg.contains("mipicks") ||
            lowerPkg.contains("getapps") ||
            lowerPkg.contains("appgallery") ||
            lowerPkg.contains("aurora.store") ||
            lowerPkg.contains("aptoide")
    }

    private fun findFirstMatchingNode(node: AccessibilityNodeInfo, keywords: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val txt = node.text?.toString()?.lowercase().orEmpty()
        val vid = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && node.isVisibleToUser) {
            for (k in keywords) {
                if ((desc.isNotEmpty() && desc.contains(k)) ||
                    (txt.isNotEmpty() && txt.contains(k)) ||
                    (vid.isNotEmpty() && vid.contains(k))
                ) {
                    return AccessibilityNodeInfo.obtain(node)
                }
            }
        }
        val cnt = node.childCount.coerceAtMost(28)
        for (i in 0 until cnt) {
            val child = node.getChild(i) ?: continue
            val hit = findFirstMatchingNode(child, keywords, depth + 1)
            child.recycle()
            if (hit != null) return hit
        }
        return null
    }

    private fun onForegroundAppChanged(pkg: String, cfg: DemonConfigSnapshot) {
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        val grace = appGraceUntilMs[pkg] ?: 0L
        val isWatched = cfg.watchedPackages.contains(pkg)
        val isGame = isGamePackage(pkg)

        // #13 & Strict Settings Lock
        if (isProtectedSettingsPackage(pkg.lowercase())) {
            if (cfg.challengeLocked || cfg.strictSettingsLock) {
                enforceStrictSettingsLock(pkg, cfg)
            } else if (cfg.settingsPanicEnabled) {
                overlayMgr?.triggerPanicMode("HEIN ?! Tu vas pas me désactiver ?! 😱", 6_000L)
            }
            return
        }

        // #71 Bloqueur Play Store / App Stores
        if (cfg.blockStoreBuyEnabled && isStorePackage(pkg.lowercase()) && now > grace) {
            settings.adjustMood(+8)
            overlayMgr?.showLockShieldWithNegotiation(
                title = "Boutique interdite ! 🛒😈",
                reason = "Tu n'installes et n'achètes aucune appli sans la permission de ${cfg.avatarName} !",
                pkg = pkg,
                allowNegotiation = !cfg.challengeLocked,
                cfg = cfg
            )
            return
        }

        // #44 Liste Noire Totale (Confiscation sans négociation!)
        if (cfg.blacklistEnabled && cfg.blacklistPackages.contains(pkg)) {
            settings.adjustMood(+10)
            try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            if (cfg.timeoutCornerEnabled) {
                overlayMgr?.showTimeoutCornerWithVideo(
                    reason = "Appli sur Liste Noire ! Direction AU COIN ! 😈",
                    durationSec = cfg.timeoutCornerSec,
                    cfg = cfg,
                    originPkg = pkg
                )
            } else {
                overlayMgr?.showLockShieldWithNegotiation(
                    title = "Appli Confisquée 🚫😈",
                    reason = "Cette appli est sur ta Liste Noire totale. Aucune négociation possible !",
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

        // #125 Mana Tax at Level 1 OR #69 Media Tournament OR #7 Permission Gate
        if (isWatched && now > grace) {
            if (cfg.rpgSeductionEnabled && cfg.playerLevel <= 1) {
                overlayMgr?.showSeductionBattleOverlay(cfg)
                return
            }
            val label = resolveAppLabel(pkg)
            if (cfg.entryTournamentEnabled) {
                overlayMgr?.showMediaTournamentGate(label, pkg, cfg.entryTournamentSize, cfg)
                return
            } else if (cfg.askPermissionGate) {
                overlayMgr?.showPermissionGatePopup(label, pkg, cfg)
                return
            }
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
        if (currentPkg == packageName || overlayMgr?.isTimeoutCornerActive() == true) return
        val actions = ArrayList<() -> Unit>()
        if (cfg.draggedPopupEnabled) actions.add { overlayMgr?.showDraggedPhotoPopup(cfg) }
        if (cfg.fullscreenFlashEnabled) actions.add { overlayMgr?.showFullscreenPhotoFlash(cfg) }
        if (cfg.forcedSlideshowEnabled) actions.add { overlayMgr?.showForcedSlideshow(cfg) }
        if (cfg.wallpaperPrankEnabled) {
            actions.add {
                overlayMgr?.say("Nouveau fond d'écran surprise ! 🖼️😈", 4_000L)
                Thread { MediaFolderHelper.applyRandomWallpaperPrank(applicationContext, cfg) }.start()
            }
        }
        if (cfg.compulsiveBackEnabled) {
            val backAction = {
                val times = if (cfg.moodScore >= 70 || cfg.chaosLevel >= 9) 2 else 1
                overlayMgr?.say("Retour ! Tu n'as rien à faire là ! ⬅️😈", 3_000L)
                for (i in 0 until times) {
                    mainHandler.postDelayed({ try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) {} }, i * 260L)
                }
            }
            actions.add(backAction)
            if (cfg.chaosLevel >= 8) actions.add(backAction)
        }
        if (cfg.forcePauseVideoEnabled && (isVideoPackage(currentPkg) || cfg.watchedPackages.contains(currentPkg))) {
            actions.add { performVideoForcePause() }
            if (isVideoPackage(currentPkg)) actions.add { performVideoForcePause() }
        }
        if (cfg.deadZoneKeyboardEnabled) {
            actions.add { overlayMgr?.showDeadZoneKeyboardOverlay(if (cfg.chaosLevel >= 8) 32 else 20) }
        }
        if (cfg.surpriseQuizEnabled) {
            actions.add { overlayMgr?.showSurpriseQuizOverlay(cfg) }
        }
        // Always active when enabled (no longer gated behind mood >= 65/75!), weighted 2x when strict/furious
        if (cfg.punishmentLinesEnabled) {
            val linesAction: () -> Unit = { overlayMgr?.showPunishmentLinesOverlay(cfg) }
            actions.add(linesAction)
            if (cfg.moodScore >= 65 || cfg.chaosLevel >= 9) actions.add(linesAction)
        }
        if (cfg.timeoutCornerEnabled) {
            val cornerAction: () -> Unit = {
                overlayMgr?.showTimeoutCornerWithVideo(
                    reason = "${cfg.avatarName} te met AU COIN ! Regarde la vidéo jusqu'au bout 😈",
                    durationSec = cfg.timeoutCornerSec,
                    cfg = cfg,
                    originPkg = currentPkg
                )
            }
            actions.add(cornerAction)
            if (cfg.moodScore >= 70 || cfg.chaosLevel >= 9) actions.add(cornerAction)
        }
        if (cfg.unremovableAltarEnabled) {
            val altarAction: () -> Unit = { overlayMgr?.showUnremovableAltar(cfg) }
            actions.add(altarAction)
            if (cfg.chaosLevel >= 8) actions.add(altarAction)
        }
        if (cfg.rpgSeductionEnabled) {
            val rpgAction: () -> Unit = { overlayMgr?.showSeductionBattleOverlay(cfg) }
            actions.add(rpgAction)
            if (cfg.chaosLevel >= 7) actions.add(rpgAction)
        }
        if (cfg.clickStealShieldEnabled || cfg.antiLikeFollowEnabled || cfg.blockStoreBuyEnabled) {
            actions.add { scanAndShieldSensitiveButton(cfg) }
        }
        if (actions.isEmpty()) return
        actions.random().invoke()
    }

    private fun isVideoPackage(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l.contains("youtube") || l.contains("tiktok") || l.contains("musically") ||
            l.contains("instagram") || l.contains("netflix") || l.contains("twitch") ||
            l.contains("vlc") || l.contains("chrome") || l.contains("browser") || l.contains("snapchat")
    }

    fun triggerManualTest(kind: String) {
        val cfg = settings.snapshot()
        ensureOverlayAttached(cfg)
        when (kind) {
            "popup" -> overlayMgr?.showDraggedPhotoPopup(cfg)
            "flash" -> overlayMgr?.showFullscreenPhotoFlash(cfg)
            "slideshow" -> overlayMgr?.showForcedSlideshow(cfg)
            "corner" -> overlayMgr?.showTimeoutCornerWithVideo(
                reason = "Punition Au Coin (vidéo de ton dossier en boucle) 😈",
                durationSec = cfg.timeoutCornerSec.coerceAtMost(20),
                cfg = cfg,
                originPkg = currentPkg
            )
            "break3m" -> overlayMgr?.showScreenBreakOverlay(cfg.screenBreakDurationSec.coerceAtMost(20), cfg)
            "tournament" -> overlayMgr?.showMediaTournamentGate("Test Appli", "test.demo", cfg.entryTournamentSize, cfg)
            "altar" -> overlayMgr?.showUnremovableAltar(cfg)
            "rpg" -> overlayMgr?.showSeductionBattleOverlay(cfg)
            "lines" -> overlayMgr?.showPunishmentLinesOverlay(cfg)
            "deadzone" -> overlayMgr?.showDeadZoneKeyboardOverlay(18)
            "quiz" -> overlayMgr?.showSurpriseQuizOverlay(cfg)
            "fury" -> {
                settings.update { putInt("mood_score", 98) }
                overlayMgr?.triggerPanicMode("CRISE DE COLÈRE ! Je ne te lâche plus ! 😈🔥", 5_500L)
                overlayMgr?.showDraggedPhotoPopup(cfg)
                mainHandler.postDelayed({ overlayMgr?.showDeadZoneKeyboardOverlay(15) }, 1_200L)
            }
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

    fun launchForcedFullscreenWitchBattle(def: com.demonpet.app.rpg.WitchCatalog.WitchDef): Boolean {
        val cfg = config ?: settings.snapshot()
        mainHandler.post {
            ensureOverlayAttached(cfg)
            overlayMgr?.showWitchBattleForDef(cfg, def)
        }
        return true
    }

    fun performSecretExitUnlock() {
        Thread { DemonMdmManager.restoreEverything(applicationContext, releaseDeviceOwner = false) }.start()
        DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
        settings.update {
            putBoolean("challenge_locked", false)
            putBoolean("strict_settings_lock", false)
            putBoolean("enabled", false)
            putInt("active_drain_pct", 0)
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

    private fun scheduleGuardLoop(delayMs: Long) {
        mainHandler.removeCallbacks(guardTick)
        if (screenInteractive && config?.enabled == true) {
            mainHandler.postDelayed(guardTick, delayMs)
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
        mainHandler.removeCallbacks(guardTick)
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        overlayMgr?.detachAll()
        overlayMgr = null
        try { visionEngine.close() } catch (_: Throwable) {}
        notifyStateChanged()
    }

    companion object {
        private val KEYBOARD_SABOTAGE_PHRASES = listOf(
            "Je promets d'ecouter Maman Demon",
            "Comment etre tres sage aujourd'hui",
            "Recette de soupe aux legumes",
            "Je dois ranger ma chambre"
        )

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
