package com.sentinelcam.app.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sentinelcam.app.receiver.KeepAliveReceiver
import com.sentinelcam.app.selfcontrol.SelfControlStore
import com.sentinelcam.app.selfcontrol.WindDownVeilOverlay
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences
import com.sentinelcam.app.vpn.SentinelDnsVpnService
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * High-Power Hors-MDM Android Accessibility Protection Engine (v27):
 * - #11: Browser URL/search inspection, Incognito/Private tab auto-close & sensitive keyword alerts.
 * - #31: Syncs Read-Only Screen Freeze overlay (`TYPE_ACCESSIBILITY_OVERLAY`).
 * - #46: Reels / Shorts / TikTok vertical swipe session quota + automatic cooldown lock.
 * - #58: Surgical Settings PIN Shield (lets Wi-Fi/Bluetooth/Sound work; blocks SentinelCam/Admin/A11y/Reset/Date-Time).
 * - #59: Multi-Window / Split-Screen / Floating Pop-up inspection across ALL visible `windows`.
 * - #60: Quick Settings notification shade auto-dismiss when locked / curfew / school / lost.
 * - #66: Cross-Watchdog that instantly revives `CameraForegroundService` if killed.
 * - #68: Backdoor Guard (launcher App-Info long-press, PackageInstaller uninstall dialogs targeting SentinelCam).
 * - #21: Emits content-change signals so AI Vision only captures when the screen actually updates.
 */
class SentinelAccessibilityService : AccessibilityService() {

    private var lastContentInspectMs = 0L
    private var lastWatchdogReviveMs = 0L
    private var lastReelsScrollCountMs = 0L
    private var lastQuickShadeDismissMs = 0L
    private var lastBypassShieldMs = 0L
    private var lastIncognitoCloseMs = 0L
    private var lastWebKeywordAlertMs = 0L
    private var lastFeedKillMs = 0L
    private var lastVpnReviveMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        notifyConnectionChanged()
        reviveMainServiceIfNeeded()
        try {
            val prefs = PortalPreferences(applicationContext)
            if (prefs.parentManaged) {
                ReadOnlyScreenOverlay.syncWithUntilMs(applicationContext, prefs.readOnlyUntilMs)
            }
            val sc = SelfControlStore(applicationContext)
            if (sc.dnsVpnEnabled) {
                SentinelDnsVpnService.ensureRunningIfEnabled(applicationContext)
            }
            WindDownVeilOverlay.sync(applicationContext, sc.isWindDownWindowNow())
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()
        val pkg = event.packageName?.toString()?.trim().orEmpty()

        // #66 Cross-Watchdog: silently revive main service & DNS VPN if OEM killed them
        if (!CameraForegroundService.isRunning && now - lastWatchdogReviveMs > 8_000L) {
            lastWatchdogReviveMs = now
            reviveMainServiceIfNeeded()
        }
        if (!SentinelDnsVpnService.isVpnActive && now - lastVpnReviveMs > 12_000L) {
            lastVpnReviveMs = now
            try {
                val sc = SelfControlStore(applicationContext)
                if (sc.dnsVpnEnabled) {
                    SentinelDnsVpnService.ensureRunningIfEnabled(applicationContext)
                }
            } catch (_: Throwable) {
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isNotEmpty()) {
                    val lower = pkg.lowercase(Locale.US)
                    val isIme = lower.contains("inputmethod") ||
                        lower.contains("gboard") ||
                        lower.contains("honeyboard") ||
                        lower.contains("swiftkey")

                    // #60 Quick Settings / Notification Shade guard during active locks!
                    if (lower == "com.android.systemui") {
                        checkQuickSettingsGuard(event, now)
                        return
                    }

                    if (!isIme) {
                        lastWindowPackage = pkg
                        ScreenTimeHelper.notifyAccessibilityWindowChanged(pkg)
                        val visiblePkgs = collectVisiblePackages()
                        lastVisiblePackages = visiblePkgs
                        for (l in windowListeners) {
                            try { l.onWindowChanged(pkg) } catch (_: Throwable) {}
                            try { l.onContentChanged() } catch (_: Throwable) {}
                        }
                        inspectActiveWindowForGuards(pkg, event, now)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastContentInspectMs >= 320L) {
                    lastContentInspectMs = now
                    for (l in windowListeners) {
                        try { l.onContentChanged() } catch (_: Throwable) {}
                    }
                    if (pkg.isNotEmpty()) {
                        val lower = pkg.lowercase(Locale.US)
                        if (lower == "com.android.systemui") {
                            checkQuickSettingsGuard(event, now)
                        } else if (isSettingsOrInstaller(lower) || isKnownBrowser(lower) || isLauncher(lower) || isFeedKillerCandidate(lower)) {
                            inspectActiveWindowForGuards(pkg, event, now)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val dy = event.scrollDeltaY
                    if (dy != 0) {
                        for (l in windowListeners) {
                            try { l.onScrollDeltaPx(dy) } catch (_: Throwable) {}
                            try { l.onContentChanged() } catch (_: Throwable) {}
                        }
                        // #46 Reels / Shorts / TikTok vertical swipe quota counter
                        if (abs(dy) >= 120) {
                            trackReelsSwipeIfApplicable(pkg.ifEmpty { lastWindowPackage.orEmpty() }, now)
                        }
                    }
                } else {
                    trackReelsSwipeIfApplicable(pkg.ifEmpty { lastWindowPackage.orEmpty() }, now)
                }
            }
        }
    }

    private fun reviveMainServiceIfNeeded() {
        try {
            val prefs = PortalPreferences(applicationContext)
            val sc = SelfControlStore(applicationContext)
            if ((prefs.onboardingCompleted || sc.selfControlEnabled) && !prefs.userManuallyStopped) {
                CameraForegroundService.ensureRunningSafely(applicationContext)
                KeepAliveReceiver.scheduleImmediateRevive(applicationContext, 2_000L)
            }
        } catch (_: Throwable) {
        }
    }

    /** #59: Collects ALL visible foreground packages across Split-Screen, Floating Popups & PiP! */
    private fun collectVisiblePackages(): Set<String> {
        val out = LinkedHashSet<String>()
        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val root = w.root ?: continue
                    val p = root.packageName?.toString()?.trim().orEmpty()
                    if (p.isNotEmpty() && !isTransientOverlayPkg(p)) {
                        out.add(p)
                    }
                    try { root.recycle() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
        }
        lastWindowPackage?.let { if (it.isNotEmpty()) out.add(it) }
        return out
    }

    private fun isTransientOverlayPkg(pkg: String): Boolean {
        val l = pkg.lowercase(Locale.US)
        return l == "com.android.systemui" ||
            l.contains("inputmethod") ||
            l.contains("gboard") ||
            l.contains("swiftkey") ||
            l.contains("honeyboard")
    }

    /** #60: Auto-dismisses Quick Settings / Notification shade when Locked / Curfew / School / Lost Mode! */
    private fun checkQuickSettingsGuard(event: AccessibilityEvent, now: Long) {
        if (now - lastQuickShadeDismissMs < 900L) return
        val prefs = try { PortalPreferences(applicationContext) } catch (_: Throwable) { return }
        if (!prefs.parentManaged || !prefs.quickSettingsGuardEnabled) return
        if (prefs.parentFreeModeUntilMs > now || prefs.settingsUnlockUntilMs > now) return

        val strictLockActive = prefs.lostModeEnabled ||
            prefs.screenPausedUntilMs > now ||
            prefs.isCurfewActiveNow(now) ||
            prefs.schoolModeUntilMs > now ||
            prefs.readOnlyUntilMs > now
        if (!strictLockActive) return

        val cls = event.className?.toString()?.lowercase(Locale.US).orEmpty()
        val txt = event.text?.joinToString(" ")?.lowercase(Locale.FRENCH).orEmpty()
        val isShadeOrQs = cls.contains("qs") ||
            cls.contains("quicksettings") ||
            cls.contains("notificationshade") ||
            cls.contains("statusbar") ||
            txt.contains("mode avion") ||
            txt.contains("airplane") ||
            txt.contains("paramètres") ||
            txt.contains("settings") ||
            txt.contains("wi-fi") ||
            txt.contains("localisation")

        if (isShadeOrQs) {
            lastQuickShadeDismissMs = now
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** #46: Counts vertical video swipes in TikTok / YouTube Shorts / Instagram Reels / Snapchat Spotlight! */
    private fun trackReelsSwipeIfApplicable(pkg: String, now: Long) {
        if (pkg.isEmpty() || now - lastReelsScrollCountMs < 1_450L) return
        val lower = pkg.lowercase(Locale.US)
        val isShortVideoApp = SHORT_VIDEO_PACKAGES.any { lower == it }
        if (!isShortVideoApp) return

        val prefs = try { PortalPreferences(applicationContext) } catch (_: Throwable) { return }
        val sc = try { SelfControlStore(applicationContext) } catch (_: Throwable) { null }
        val managedOrSelf = prefs.parentManaged || (sc?.selfControlEnabled == true)
        if (!managedOrSelf || prefs.parentFreeModeUntilMs > now) return
        val limit = prefs.reelsSwipeLimit
        if (limit <= 0) return

        // If YouTube or Instagram, verify user is actually inside full-screen Shorts / Reels player
        val isAlwaysShortFeed = lower.contains("musically") || lower.contains("trill")
        if (!isAlwaysShortFeed) {
            val signals = collectScreenSignals(maxNodes = 36)
            val inShortsOrReels = when (lower) {
                "com.google.android.youtube" -> isYouTubeShortsPlayer(signals)
                "com.instagram.android" -> isInstagramReelsPlayer(signals)
                "com.snapchat.android" -> isSnapSpotlightPlayer(signals)
                "com.facebook.katana" -> isFacebookReelsPlayer(signals)
                else -> false
            }
            if (!inShortsOrReels) return
        }

        lastReelsScrollCountMs = now
        val (sessionCount, todayCount) = prefs.incrementReelsSwipe()
        if (sessionCount >= limit) {
            val cooldownMs = prefs.reelsCooldownMin * 60_000L
            val untilMs = now + cooldownMs
            prefs.reelsCooldownUntilMs = untilMs
            prefs.resetReelsSessionSwipes()
            prefs.appendSecurityEvent(
                kind = "quota",
                title = "Quota Reels / Shorts atteint ($limit swipes)",
                detail = "$todayCount vidéos courtes vues aujourd’hui · pause de ${prefs.reelsCooldownMin} min déclenchée."
            )
            CameraForegroundService.notifySecurityEventExternal()
            launchShieldDirect(
                mode = AppBlockActivity.MODE_REELS,
                pkg = pkg,
                appName = ScreenTimeHelper.resolveAppName(packageManager, pkg),
                untilMs = untilMs
            )
        }
    }

    /** #58 Surgical Settings PIN + #68 Backdoor Guard + #11 Browser Incognito & Keyword + v28 In-App Feed Killer & Geo-Vault Guard! */
    private fun inspectActiveWindowForGuards(pkg: String, event: AccessibilityEvent, now: Long) {
        val prefs = try { PortalPreferences(applicationContext) } catch (_: Throwable) { return }
        val sc = try { SelfControlStore(applicationContext) } catch (_: Throwable) { null }
        val lower = pkg.lowercase(Locale.US)

        // 0) v28 In-App Addictive Feed Killer (YouTube Shorts, IG Reels, Snap Spotlight, FB Reels, X "For You")
        if (sc != null && isFeedKillerCandidate(lower) && now - lastFeedKillMs > 1_100L) {
            val targets = sc.getFeedKillerTargets()
            if (targets.isNotEmpty() && prefs.parentFreeModeUntilMs <= now) {
                val killedFeed = checkAndKillAddictiveFeed(lower, targets)
                if (killedFeed != null) {
                    lastFeedKillMs = now
                    sc.recordReclaimedAction(pkg, "feed_killer:$killedFeed", estimatedSavedMin = 6)
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Throwable) {
                    }
                    return
                }
            }
        }

        val selfControlLocked = sc != null && sc.isSettingsProtectedNow(now)
        val parentActive = prefs.parentManaged && prefs.parentFreeModeUntilMs <= now
        if (!parentActive && !selfControlLocked) return

        val settingsUnlocked = !selfControlLocked && prefs.settingsUnlockUntilMs > now

        // 1) #58 & #68 + v28 Sealed Geo-Vault Anti-Tamper (App-Info / VPN / Private DNS / A11y / Admin / Dev / Date-Time / Uninstall)
        if (!settingsUnlocked && (isSettingsOrInstaller(lower) || isLauncher(lower))) {
            val screenText = collectCompactScreenText(maxNodes = 44)
            if (screenText.isNotEmpty()) {
                val targetsSentinel = screenText.contains("sentinelcam") || screenText.contains("com.sentinelcam")
                val hasLauncherDestructiveMenu = LAUNCHER_DESTRUCTIVE_TERMS.any { screenText.contains(it) }
                val hasSettingsDestructiveAction = SETTINGS_DESTRUCTIVE_TERMS.any { screenText.contains(it) }

                // #68 Backdoor Guard: NEVER fire on the home screen just because the SentinelCam icon is visible!
                // Only fire on launchers when the user opens an App-Info / Uninstall popup menu targeting SentinelCam.
                if ((prefs.backdoorGuardEnabled || selfControlLocked) && targetsSentinel && (
                        (isLauncher(lower) && hasLauncherDestructiveMenu) ||
                            lower.contains("packageinstaller") ||
                            (isSettingsOrInstaller(lower) && hasSettingsDestructiveAction)
                        )
                ) {
                    if (selfControlLocked && !parentActive) {
                        ejectGeoSealed(sc!!, pkg, "Pacte Geo-Vault (SentinelCam)", now)
                    } else {
                        ejectAndRequirePin(prefs, pkg, "Protection anti-désinstallation (SentinelCam)", now)
                    }
                    return
                }

                // #58 Truly Surgical Settings PIN:
                // Allows main Android Settings dashboard (Wi-Fi / Bluetooth / Volume / Display) cleanly!
                // Only blocks when actually INSIDE a critical sub-page (by Activity class OR page header title)
                // or when viewing SentinelCam inside Settings/Installer.
                if ((prefs.surgicalSettingsPinEnabled || selfControlLocked) && isSettingsOrInstaller(lower)) {
                    val clsLower = event.className?.toString()?.lowercase(Locale.US).orEmpty()
                    val isSettingsHomepage = isSettingsDashboardClass(clsLower)
                    if (!isSettingsHomepage) {
                        val headerText = collectHeaderText(maxNodes = 6)
                        val hitsClass = CRITICAL_SETTINGS_CLASS_HINTS.any { clsLower.contains(it) } ||
                            (selfControlLocked && SELF_CONTROL_CLASS_HINTS.any { clsLower.contains(it) })
                        val hitsHeader = headerText.isNotEmpty() && (
                            CRITICAL_SETTINGS_HEADER_TERMS.any { headerText.contains(it) } ||
                                (selfControlLocked && SELF_CONTROL_HEADER_TERMS.any { headerText.contains(it) })
                            )
                        val hitsDangerousAction = CRITICAL_ACTION_BODY_TERMS.any { screenText.contains(it) }
                        val shouldShieldGeneric = prefs.blockSettingsAndStore || selfControlLocked

                        if (targetsSentinel || (shouldShieldGeneric && (hitsClass || hitsHeader || hitsDangerousAction))) {
                            val label = if (targetsSentinel) "SentinelCam (Paramètres)" else "Réglage système protégé"
                            if (selfControlLocked && !parentActive) {
                                ejectGeoSealed(sc!!, pkg, label, now)
                            } else {
                                ejectAndRequirePin(prefs, pkg, label, now)
                            }
                            return
                        }
                    }
                }
            }
        }

        // 2) #11: Browser Incognito Blocker & Sensitive Web Keyword Inspector
        val incognitoActive = prefs.incognitoBlockEnabled || selfControlLocked
        if (isKnownBrowser(lower) && (incognitoActive || (parentActive && prefs.webKeywordGuardEnabled))) {
            val browserSignals = collectScreenSignals(maxNodes = 44)
            if (browserSignals.isNotEmpty()) {
                // Close Incognito / Private Browsing tabs immediately (banner / viewId only — zero false positives on normal web pages!)
                if (incognitoActive && now - lastIncognitoCloseMs > 3_000L) {
                    val isPrivateTab = INCOGNITO_STRICT_SIGNALS.any { browserSignals.contains(it) }
                    if (isPrivateTab) {
                        lastIncognitoCloseMs = now
                        prefs.appendSecurityEvent(
                            kind = "bypass",
                            title = "Navigation privée bloquée",
                            detail = "Onglet incognito détecté et fermé dans ${ScreenTimeHelper.resolveAppName(packageManager, pkg)}."
                        )
                        sc?.recordReclaimedAction(pkg, "incognito_blocked", estimatedSavedMin = 5)
                        CameraForegroundService.notifySecurityEventExternal()
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        } catch (_: Throwable) {}
                        return
                    }
                }

                // Real-time Sensitive Web Keyword Alert!
                if (parentActive && prefs.webKeywordGuardEnabled && now - lastWebKeywordAlertMs > 18_000L) {
                    val keywords = prefs.getSocialAlertKeywords()
                    val normScreen = normalizeText(browserSignals)
                    val matched = keywords.firstOrNull { kw ->
                        val clean = normalizeText(kw)
                        clean.length >= 3 && normScreen.contains(clean)
                    }
                    if (matched != null) {
                        lastWebKeywordAlertMs = now
                        val appLabel = ScreenTimeHelper.resolveAppName(packageManager, pkg)
                        prefs.appendSecurityEvent(
                            kind = "keyword",
                            title = "Recherche web sensible ($appLabel)",
                            detail = "Mot-clé « $matched » repéré dans le navigateur."
                        )
                        CameraForegroundService.notifySecurityEventExternal()
                    }
                }
            }
        }
    }

    private fun isYouTubeShortsPlayer(signals: String): Boolean =
        signals.contains("reel_player") ||
            signals.contains("reel_watch") ||
            signals.contains("reel_recycler") ||
            signals.contains("shorts_video") ||
            signals.contains("shorts_pivot") ||
            signals.contains("reel_pivot") ||
            signals.contains("remix ce short") ||
            signals.contains("remix this short")

    private fun isInstagramReelsPlayer(signals: String): Boolean =
        signals.contains("clips_viewer") ||
            signals.contains("clips_video") ||
            signals.contains("clips_tab_root") ||
            (signals.contains("audio d'origine") && signals.contains("reels")) ||
            (signals.contains("original audio") && signals.contains("reels"))

    private fun isSnapSpotlightPlayer(signals: String): Boolean =
        signals.contains("spotlight_opera") ||
            signals.contains("spotlight_feed") ||
            (signals.contains("spotlight") && signals.contains("remix"))

    private fun isFacebookReelsPlayer(signals: String): Boolean =
        signals.contains("fb_shorts") ||
            signals.contains("reels_viewer") ||
            (signals.contains("reels") && (signals.contains("audio d'origine") || signals.contains("original audio")))

    private fun checkAndKillAddictiveFeed(pkgLower: String, targets: Set<String>): String? {
        val signals = collectScreenSignals(maxNodes = 42)
        if (signals.isEmpty()) return null
        return when {
            pkgLower == "com.google.android.youtube" && "yt_shorts" in targets ->
                if (isYouTubeShortsPlayer(signals)) "yt_shorts" else null
            pkgLower == "com.instagram.android" && "ig_reels" in targets ->
                if (isInstagramReelsPlayer(signals)) "ig_reels" else null
            pkgLower == "com.snapchat.android" && "snap_spotlight" in targets ->
                if (isSnapSpotlightPlayer(signals)) "snap_spotlight" else null
            pkgLower == "com.facebook.katana" && "fb_reels" in targets ->
                if (isFacebookReelsPlayer(signals)) "fb_reels" else null
            pkgLower == "com.twitter.android" && "x_foryou" in targets -> {
                val isForYou = signals.contains("pour vous") && signals.contains("abonnements")
                if (isForYou) "x_foryou" else null
            }
            else -> null
        }
    }

    private fun isSettingsDashboardClass(clsLower: String): Boolean {
        if (clsLower.isEmpty()) return false
        return clsLower.endsWith(".settings") ||
            clsLower.contains("settingshomepageactivity") ||
            clsLower.contains("homepageactivity") ||
            clsLower.contains("secsettingshomepage") ||
            clsLower.contains("miuisettings") ||
            clsLower.contains("settingsactivity")
    }

    private fun collectHeaderText(maxNodes: Int): String {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return ""
        val sb = StringBuilder(160)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var textsFound = 0
        try {
            while (queue.isNotEmpty() && visited < 28 && textsFound < maxNodes) {
                val n = queue.removeFirst()
                visited++
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length <= 72) {
                    sb.append(t.lowercase(Locale.FRENCH)).append(' ')
                    textsFound++
                }
                val childCount = n.childCount.coerceAtMost(8)
                for (i in 0 until childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
                if (n !== root) try { n.recycle() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        } finally {
            while (queue.isNotEmpty()) {
                val rem = queue.removeFirst()
                if (rem !== root) try { rem.recycle() } catch (_: Throwable) {}
            }
            try { root.recycle() } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    private fun ejectGeoSealed(sc: SelfControlStore, pkg: String, label: String, now: Long) {
        if (now - lastBypassShieldMs < 1_200L) return
        lastBypassShieldMs = now
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {}
        sc.recordReclaimedAction(pkg, "geo_tamper_blocked", estimatedSavedMin = 5)
        sc.notifyPartnerIfConfigured("Tentative de modification bloquée", "Accès bloqué hors zone carte : $label")
        launchShieldDirect(
            mode = AppBlockActivity.MODE_GEO_SEALED,
            pkg = pkg,
            appName = label,
            untilMs = now + 3_600_000L
        )
    }

    private fun collectScreenSignals(maxNodes: Int): String {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return ""
        val sb = StringBuilder(384)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < maxNodes) {
                val n = queue.removeFirst()
                visited++
                val vid = n.viewIdResourceName?.lowercase(Locale.US)
                if (!vid.isNullOrEmpty()) {
                    sb.append(vid.substringAfterLast('/')).append(' ')
                }
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length <= 80) {
                    sb.append(t.lowercase(Locale.FRENCH)).append(' ')
                }
                val cd = n.contentDescription?.toString()?.trim()
                if (!cd.isNullOrEmpty() && cd.length <= 80) {
                    sb.append(cd.lowercase(Locale.FRENCH)).append(' ')
                }
                val childCount = n.childCount.coerceAtMost(10)
                for (i in 0 until childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
                if (n !== root) try { n.recycle() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        } finally {
            while (queue.isNotEmpty()) {
                val rem = queue.removeFirst()
                if (rem !== root) try { rem.recycle() } catch (_: Throwable) {}
            }
            try { root.recycle() } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    private fun isFeedKillerCandidate(lower: String): Boolean =
        lower == "com.google.android.youtube" ||
            lower == "com.instagram.android" ||
            lower == "com.snapchat.android" ||
            lower == "com.facebook.katana" ||
            lower == "com.twitter.android"

    private fun ejectAndRequirePin(prefs: PortalPreferences, pkg: String, label: String, now: Long) {
        if (now - lastBypassShieldMs < 1_200L) return
        lastBypassShieldMs = now
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {}
        prefs.appendSecurityEvent(
            kind = "bypass",
            title = "Accès protégé bloqué",
            detail = "Tentative bloquée : $label ($pkg)."
        )
        CameraForegroundService.notifySecurityEventExternal()
        launchShieldDirect(
            mode = AppBlockActivity.MODE_BYPASS,
            pkg = pkg,
            appName = label,
            untilMs = now + 3_600_000L
        )
    }

    private fun launchShieldDirect(mode: String, pkg: String, appName: String, untilMs: Long) {
        try {
            val intent = Intent(applicationContext, AppBlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(AppBlockActivity.EXTRA_MODE, mode)
                putExtra(AppBlockActivity.EXTRA_PACKAGE, pkg)
                putExtra(AppBlockActivity.EXTRA_APP_NAME, appName)
                putExtra(AppBlockActivity.EXTRA_UNTIL_MS, untilMs)
            }
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun collectCompactScreenText(maxNodes: Int): String {
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return ""
        val sb = StringBuilder(256)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < maxNodes) {
                val n = queue.removeFirst()
                visited++
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length <= 160) {
                    sb.append(t.lowercase(Locale.FRENCH)).append(' ')
                }
                val cd = n.contentDescription?.toString()?.trim()
                if (!cd.isNullOrEmpty() && cd.length <= 120) {
                    sb.append(cd.lowercase(Locale.FRENCH)).append(' ')
                }
                val childCount = n.childCount.coerceAtMost(12)
                for (i in 0 until childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
                if (n !== root) {
                    try { n.recycle() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
        } finally {
            while (queue.isNotEmpty()) {
                val rem = queue.removeFirst()
                if (rem !== root) try { rem.recycle() } catch (_: Throwable) {}
            }
            try { root.recycle() } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    private fun normalizeText(raw: String): String {
        val nfd = Normalizer.normalize(raw.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun isSettingsOrInstaller(lower: String): Boolean =
        lower.contains("settings") ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter")

    private fun isLauncher(lower: String): Boolean =
        lower.contains("launcher") || lower.contains("miui.home") || lower.contains("trebuchet")

    private fun isKnownBrowser(lower: String): Boolean =
        KNOWN_BROWSERS.any { lower == it } || lower.contains("browser")

    override fun onInterrupt() {
        // Nothing to interrupt
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
            notifyConnectionChanged()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
            notifyConnectionChanged()
        }
        super.onDestroy()
    }

    interface WindowEventListener {
        fun onWindowChanged(packageName: String)
        fun onScrollDeltaPx(deltaYPx: Int)
        fun onContentChanged() {}
    }

    companion object {
        @Volatile
        private var activeInstance: SentinelAccessibilityService? = null

        @Volatile
        var lastWindowPackage: String? = null
            private set

        @Volatile
        var lastVisiblePackages: Set<String> = emptySet()
            private set

        @Volatile
        private var lastScreenshotAtMs: Long = 0L

        private val captureInFlight = AtomicBoolean(false)
        private val captureExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SentinelA11yShot").apply { isDaemon = true }
        }
        private val windowListeners = CopyOnWriteArraySet<WindowEventListener>()
        private val connectionListeners = CopyOnWriteArraySet<() -> Unit>()

        val SHORT_VIDEO_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube",
            "com.instagram.android",
            "com.snapchat.android",
            "com.facebook.katana"
        )

        private val KNOWN_BROWSERS = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android"
        )

        private val LAUNCHER_DESTRUCTIVE_TERMS = listOf(
            "infos sur l'appli", "informations sur l'application", "info appli", "app info",
            "désinstaller", "uninstall", "supprimer l'appli", "remove app",
            "forcer l'arrêt", "force stop"
        )

        private val SETTINGS_DESTRUCTIVE_TERMS = listOf(
            "désinstaller", "uninstall", "forcer l'arrêt", "force stop",
            "effacer les données", "clear storage", "clear data",
            "désactiver l'appli", "disable app", "désactiver cette appli"
        )

        private val CRITICAL_SETTINGS_CLASS_HINTS = listOf(
            "accessibility", "deviceadmin", "developmentsettings", "masterclear",
            "resetdashboard", "datetimesettings", "usersettings", "usageaccess",
            "notificationaccess", "installedappdetails"
        )

        private val SELF_CONTROL_CLASS_HINTS = listOf(
            "vpnsettings", "privatedns", "drawoverlay"
        )

        private val CRITICAL_SETTINGS_HEADER_TERMS = listOf(
            "accessibilité", "accessibility",
            "administrateur de l'appareil", "device admin", "applications d'administration",
            "options pour les développeurs", "developer options",
            "réinitialiser", "factory reset", "rétablir la configuration",
            "date et heure", "date & time", "régler la date",
            "second espace", "second space", "espace privé", "private space", "dossier sécurisé",
            "utilisateurs multiples", "multiple users",
            "accès aux données d'utilisation", "usage access",
            "accès aux notifications", "notification access"
        )

        private val SELF_CONTROL_HEADER_TERMS = listOf(
            "vpn", "dns privé", "private dns", "position fictive", "mock location",
            "superposition", "draw over other apps"
        )

        private val CRITICAL_ACTION_BODY_TERMS = listOf(
            "rétablir la configuration d'usine", "erase all data", "factory data reset",
            "désactiver cette application d'administration", "deactivate this device admin",
            "débogage usb", "usb debugging"
        )

        private val INCOGNITO_STRICT_SIGNALS = listOf(
            "vous êtes passé en mode navigation privée",
            "you've gone incognito",
            "fermer les onglets de navigation privée",
            "close incognito tabs",
            "close all incognito tabs",
            "onglet de navigation privée",
            "incognito_tab",
            "new_incognito_tab",
            "vous naviguez en mode inprivate",
            "navigation inprivate",
            "mode secret activé",
            "fermer les onglets secrets"
        )

        fun isActiveNow(): Boolean = activeInstance != null

        fun canCaptureAutonomously(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeInstance != null

        fun getServiceContext(): Context? = activeInstance

        fun getOverlayWindowManager(): WindowManager? {
            return try {
                activeInstance?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            } catch (_: Throwable) {
                null
            }
        }

        fun addWindowListener(listener: WindowEventListener) {
            windowListeners.add(listener)
        }

        fun removeWindowListener(listener: WindowEventListener) {
            windowListeners.remove(listener)
        }

        fun addConnectionListener(listener: () -> Unit) {
            connectionListeners.add(listener)
        }

        fun removeConnectionListener(listener: () -> Unit) {
            connectionListeners.remove(listener)
        }

        private fun notifyConnectionChanged() {
            for (l in connectionListeners) {
                try { l.invoke() } catch (_: Throwable) {}
            }
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val expected = ComponentName(context, SentinelAccessibilityService::class.java).flattenToString()
                val shortExpected = ComponentName(context, SentinelAccessibilityService::class.java).flattenToShortString()
                enabled.contains(expected, ignoreCase = true) ||
                    enabled.contains(shortExpected, ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
        }

        fun captureScreenBitmap(
            maxWidth: Int = 540,
            onResult: (Bitmap?) -> Unit
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(null)
                return
            }
            val svc = activeInstance
            if (svc == null) {
                onResult(null)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastScreenshotAtMs < 1_020L) {
                onResult(null)
                return
            }
            if (!captureInFlight.compareAndSet(false, true)) {
                onResult(null)
                return
            }
            lastScreenshotAtMs = now
            try {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    captureExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var hwBmp: Bitmap? = null
                            var softBmp: Bitmap? = null
                            var finalBmp: Bitmap? = null
                            try {
                                val buffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                try {
                                    hwBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                } finally {
                                    try { buffer.close() } catch (_: Throwable) {}
                                }
                                if (hwBmp != null) {
                                    softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBmp.recycle()
                                    hwBmp = null
                                }
                                if (softBmp != null) {
                                    val w = softBmp.width
                                    val h = softBmp.height
                                    if (w > maxWidth && w > 0 && h > 0) {
                                        val ratio = maxWidth.toFloat() / w.toFloat()
                                        val tw = (w * ratio).toInt().coerceAtLeast(160)
                                        val th = (h * ratio).toInt().coerceAtLeast(240)
                                        finalBmp = Bitmap.createScaledBitmap(softBmp, tw, th, true)
                                        softBmp.recycle()
                                        softBmp = null
                                    } else {
                                        finalBmp = softBmp
                                        softBmp = null
                                    }
                                }
                            } catch (_: Throwable) {
                                try { hwBmp?.recycle() } catch (_: Throwable) {}
                                try { softBmp?.recycle() } catch (_: Throwable) {}
                                try { finalBmp?.recycle() } catch (_: Throwable) {}
                                finalBmp = null
                            } finally {
                                captureInFlight.set(false)
                            }
                            onResult(finalBmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            captureInFlight.set(false)
                            onResult(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                captureInFlight.set(false)
                onResult(null)
            }
        }

        fun disableServiceOnUnpair() {
            ReadOnlyScreenOverlay.hide()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    activeInstance?.disableSelf()
                }
            } catch (_: Throwable) {
            }
            activeInstance = null
        }
    }
}
