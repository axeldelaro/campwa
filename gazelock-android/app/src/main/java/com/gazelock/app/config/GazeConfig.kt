package com.gazelock.app.config

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class StrictnessPreset(val id: String, val label: String, val emoji: String) {
    GENTLE("gentle", "Doux", "🌿"),
    STRICT("strict", "Strict", "🔥"),
    RUTHLESS("ruthless", "Impitoyable", "😈");

    companion object {
        fun fromId(id: String?): StrictnessPreset = values().firstOrNull { it.id == id } ?: STRICT
    }
}

enum class PenaltyMode(val id: String, val label: String, val desc: String) {
    FIXED("fixed", "Fixe", "Ajoute la même pénalité à chaque écart"),
    PROGRESSIVE("progressive", "📈 Progressive", "Double à chaque écart (+3s → +6s → +12s → +24s…)"),
    SUDDEN_DEATH("sudden_death", "💀 Mort Subite", "Détourner le regard > 2,2 s remet le chrono à ZERO !");

    companion object {
        fun fromId(id: String?): PenaltyMode = values().firstOrNull { it.id == id } ?: PROGRESSIVE
    }
}

enum class OverlayMode(val id: String, val label: String, val emoji: String, val desc: String) {
    GHOST_PASSTHROUGH(
        "ghost",
        "👻 Semi-Transparent",
        "👻",
        "Vidéo plein écran à 45–50 % d'opacité par-dessus tes applis : tactile 100 % déverrouillé, mais impossible de retirer les vidéos hors portail imposé (overlay) !"
    ),
    FULL_LOCK(
        "full_lock",
        "🔒 Plein Écran Verrouillé",
        "🔒",
        "Vidéo 100 % opaque, tactile verrouillé et anti-évasion total (Paramètres, Récents, Split-Screen & Panneau bloqués)."
    );

    companion object {
        fun fromId(id: String?): OverlayMode = values().firstOrNull { it.id == id } ?: GHOST_PASSTHROUGH
    }
}

enum class PopupTouchMode(val id: String, val label: String, val desc: String) {
    PASS_THROUGH("pass", "👻 Tactile Libre", "Les pop-ups s'affichent par-dessus tes applis sans bloquer tes clics (supporte ∞ illimité à 60 FPS !)"),
    TAP_DISMISS("tap", "👆 Cliquable", "Touche ou balaie chaque pop-up (1–3 clics) pour l'éjecter — mitose & esquive en option !"),
    TOUCH_LOCKED("locked", "🔒 Bloquant", "Impossible à fermer manuellement pendant toute sa durée d'affichage");

    companion object {
        fun fromId(id: String?): PopupTouchMode = values().firstOrNull { it.id == id } ?: TAP_DISMISS
    }
}

enum class PopupSpawnZone(val id: String, val label: String) {
    ANYWHERE("anywhere", "🎲 Partout"),
    CENTER("center", "🎯 Centre"),
    EDGES("edges", "🖼️ Bords"),
    GRID("grid", "🧱 Collage");

    companion object {
        fun fromId(id: String?): PopupSpawnZone = values().firstOrNull { it.id == id } ?: ANYWHERE
    }
}

enum class PopupAnimStyle(val id: String, val label: String) {
    POP_ZOOM("zoom", "💥 Pop Zoom"),
    INSTANT_FLASH("flash", "⚡ Flash Brut"),
    SLIDE_IN("slide", "🌊 Glissement"),
    SPIN_IN("spin", "🌀 Tourbillon"),
    RANDOM("random", "🎲 Aléatoire");

    companion object {
        fun fromId(id: String?): PopupAnimStyle = values().firstOrNull { it.id == id } ?: RANDOM
    }
}

enum class PopupFrameStyle(val id: String, val label: String) {
    NEON_MULTI("neon", "🌈 Néon"),
    CRIMSON("crimson", "❤️ Rouge"),
    POLAROID("polaroid", "📸 Polaroid"),
    BORDERLESS("none", "🚫 Sans bord"),
    DOUBLE_PULSE("double", "⚡ Double");

    companion object {
        fun fromId(id: String?): PopupFrameStyle = values().firstOrNull { it.id == id } ?: NEON_MULTI
    }
}

enum class StormPreset(val id: String, val label: String) {
    GENTLE("gentle", "🌊 Doux"),
    INTENSE("intense", "⚡ Intensif"),
    HURRICANE("hurricane", "🌪️ Ouragan ∞"),
    APOCALYPSE("apocalypse", "☢️ APOCALYPSE ∞")
}

data class SessionReport(
    val grade: String,
    val focusPct: Int,
    val focusedSec: Int,
    val lookAways: Int,
    val bestStreakSec: Int,
    val escapesBlocked: Int,
    val timestampMs: Long
)

data class GazeConfigSnapshot(
    val folderPath: String,
    val treeUri: String,
    val durationSec: Int,
    val infiniteUntilUnlock: Boolean,
    val overlayMode: OverlayMode,
    val ghostOpacityPct: Int,
    val shuffleVideos: Boolean,
    val maxClipSec: Int,
    val allowSwipeNext: Boolean,
    val pauseVideoOnLookAway: Boolean,
    val lookAwayPenaltySec: Int,
    val penaltyMode: PenaltyMode,
    val yawThresholdDeg: Int,
    val pitchThresholdDeg: Int,
    val requireEyesOpen: Boolean,
    val irisTrackingEnabled: Boolean,
    val irisThresholdPct: Int,
    val antiEscapeLockEnabled: Boolean,
    val voiceAlertEnabled: Boolean,
    val vibrateAlertEnabled: Boolean,
    // #7 Subliminal Image Flashes & Neon Mantras
    val subliminalEnabled: Boolean,
    val subliminalIntervalSec: Int,
    val subliminalDurationMs: Int,
    val subliminalAlphaPct: Int,
    val subliminalMantras: String,
    // #8 Absolute Audio Lock + Forced Max Brightness + Hypnotic Binaural Beat
    val volumeLockEnabled: Boolean,
    val volumeFloorPct: Int,
    val forceMaxBrightness: Boolean,
    val binauralBeatEnabled: Boolean,
    val binauralVolumePct: Int,
    val binauralFreqHz: Int,
    // Portal & Mini-Games (#16, #17, #22, #51, #54, #70 + v6/v7)
    val unlockPopupsEnabled: Boolean,
    val unlockMinigamesEnabled: Boolean,
    val portalMinigameCount: Int,             // 3..12 mini-games from 18-game pool!
    val overloadSprintEnabled: Boolean,
    val gazeGatedPortalEnabled: Boolean,
    val hellPuzzle4x4Enabled: Boolean,
    val flashHunt9FpsEnabled: Boolean,
    val dailyEscalatingPortalEnabled: Boolean,
    val portalUsesToday: Int,
    // v4.0 Extreme Ghost & Control
    val livingOpacityEnabled: Boolean,
    val attentionHijackEnabled: Boolean,
    val attentionHijackIntervalMin: Int,
    val attentionHijackFocusSec: Int,
    val ghostMicroPopupsEnabled: Boolean,
    val ghostMicroPopupIntervalSec: Int,
    val deadZonesEnabled: Boolean,
    val timeDebtEnabled: Boolean,
    val timeDebtCarryPct: Int,
    val pendingTimeDebtSec: Int,
    val hypnoticSpiralEnabled: Boolean,
    val spiralOpacityPct: Int,
    val ttsMantraWhisperEnabled: Boolean,
    val blindTimerEnabled: Boolean,
    val antiFlatSensorEnabled: Boolean,
    val rebootSurvivalEnabled: Boolean,
    // v5.0 APOCALYPSE Additions
    val noBlinkChallengeEnabled: Boolean,
    val noBlinkDurationSec: Int,
    val appTrapEnabled: Boolean,
    val trappedPackagesCsv: String,
    val appTrapDurationSec: Int,
    val blockShadeInGhostEnabled: Boolean,
    val appSwitchTaxEnabled: Boolean,
    val pavlovSoundEnabled: Boolean,
    val finalBossEnabled: Boolean,
    // v5.1 / v6.0 MEGA Standalone Image Pop-Up Storm Mode
    val popupFolderPath: String,
    val popupTreeUri: String,
    val popupMinSizePct: Int,
    val popupSizePct: Int,
    val popupIntervalMs: Int,
    val popupDurationMs: Int,
    val popupMaxSimultaneous: Int,
    val popupBurstCount: Int,
    val popupOpacityPct: Int,
    val popupRandomOpacity: Boolean,
    val popupRampUpEnabled: Boolean,
    val popupSpawnZone: PopupSpawnZone,
    val popupAnimStyle: PopupAnimStyle,
    val popupFrameStyle: PopupFrameStyle,
    val popupTouchMode: PopupTouchMode,
    val popupTapsToClose: Int,
    val popupSwipeDismiss: Boolean,
    val popupMitosisEnabled: Boolean,
    val popupRandomRotation: Boolean,
    val popupShowMantra: Boolean,
    val popupVibrate: Boolean,
    val popupSoundOnSpawn: Boolean,
    val popupSessionDurationSec: Int,
    val popupLockExitWithPortal: Boolean,
    // ==================== v7.0 TITAN FORTRESS & HARDENING ====================
    // Pillar 1: Anti-Escape & System Hardening (#1..#7)
    val portalFlagSecureEnabled: Boolean,       // #1 FLAG_SECURE on Portal, Boss & Hijack (anti-screenshot!)
    val antiSplitScreenEnabled: Boolean,        // #2 Block Split-Screen / Freeform / PiP multi-window!
    val overlayWatchdogEnabled: Boolean,        // #3 Self-healing Z-order & re-mount watchdog every 1.5s!
    val strictAudioGuardEnabled: Boolean,       // #4 Block volume down/mute in locked pop-ups + 250ms audio lock!
    val popupBlockShadeEnabled: Boolean,        // #5 Block Notification Shade / Quick Settings in locked pop-ups!
    val popupRebootSurvivalEnabled: Boolean,    // #6 Immortal checkpoint survival for locked Pop-Up Storm!
    val screenOffPenaltyEnabled: Boolean,       // #7 Turning screen off during session adds +20s & +10% opacity!
    // Pillar 2: AI Gaze & Biometric Hardening (#8..#14)
    val autoZeroCalibrationEnabled: Boolean,    // #8 3s automatic neutral posture zero-point calibration!
    val antiCameraCoverEnabled: Boolean,        // #9 Camera black-frame / finger cover detector (double penalty!)
    val faceDistanceGuardEnabled: Boolean,      // #10 Too-far face distance guard (<17% frame width)!
    val antiDrowsyEnabled: Boolean,             // #11 Micro-sleep / heavy eyelids (>1.6s) alarm!
    val escalatingStrictnessEnabled: Boolean,   // #12 Progressive angle/iris tightening every 2 min or 3 faults!
    val soloFaceGuardEnabled: Boolean,          // #14 Face swap / multi-face instability guard!
    // Pillar 3: Video & Sensory Saturation (#15..#21)
    val ghostBreathingOpacityEnabled: Boolean,  // #15 Hypnotic sine breathing opacity wave (±8%)!
    val faultGlitchEnabled: Boolean,            // #16 Video shake glitch + chromatic strobe on faults!
    val dynamicPlaybackSpeedEnabled: Boolean,   // #17 0.85x deep-focus trance -> 1.35x pressure speed!
    val scrollingMarqueeEnabled: Boolean,       // #18 Continuous scrolling neon mantra marquee banners!
    val freezeFrameShockEnabled: Boolean,       // #19 Periodic 0.9s 92% snap-freeze photo-bomb shock!
    val heartbeatHapticsEnabled: Boolean,       // #20 Rhythmic double-tap heartbeat haptics on high intensity!
    val inductionTunnelEnabled: Boolean,        // #21 10s 95% spiral induction tunnel before Ghost touch unlocks!
    // Pillar 4: Pop-Up Storm TITAN Upgrades (#22..#29)
    val popupDriftPhysicsEnabled: Boolean,      // #22 60 FPS diagonal drift & wall bounce physics!
    val popupPulseScaleEnabled: Boolean,        // #23 60 FPS hypnotic scale breathing pulse on sprites!
    val popupEvasiveDodgeEnabled: Boolean,      // #24 35% of clickable pop-ups dodge your first tap!
    val popupSurgeWavesEnabled: Boolean,        // #25 Every 25s -> 6s 3x speed SURGE WAVE!
    val popupSpiralOverlayEnabled: Boolean,     // #26 60 FPS hypnotic spiral backdrop under pop-ups!
    val popupBinauralEnabled: Boolean,          // #27 Synthesized binaural beat audio in Pop-Up mode!
    val popupGazeBoostEnabled: Boolean,         // #28 Optional gaze-boosted surge intensity flag
    // Pillar 5: Portal Hardening (#30..#35)
    val portalCancelTaxEnabled: Boolean,        // #34a Canceling portal adds +30s & +10% opacity!
    val portalRequireCodeEntryEnabled: Boolean, // #34b Must manually type the 6-digit unlocked code!
    val portalHardcorePenaltiesEnabled: Boolean,// #34c Double mistake penalties (+10s) + extra trap pop-ups!
    // Pillar 6: Time Bomb, Contracts, Config Lock & Report (#36..#40)
    val timeBombArmedUntilMs: Long,             // #37 Random ambush timestamp (>0 when armed)
    val timeBombDurationSec: Int,               // #37 Duration of Time Bomb session
    val dailyContractGoalMin: Int,              // #38 Daily focus goal in minutes (15..180)
    val dailyFocusedSecToday: Int,              // #38 Focused seconds achieved today
    val dailyContractStreakDays: Int,           // #38 Consecutive days meeting contract
    val configLockUntilMs: Long,                // #39 Hardcore settings lock expiry timestamp
    val lastReport: SessionReport?,             // #40 Detailed post-session report card
    // Telemetry, Presets & Auto-Ambush
    val showTelemetryBadge: Boolean,
    val preset: StrictnessPreset,
    val autoTriggerEnabled: Boolean,
    val autoTriggerEveryMin: Int,
    val autoTriggerDurationSec: Int,
    // Cumulative Stats
    val sessionsCompleted: Int,
    val totalFocusedSec: Long,
    val totalLookAways: Int,
    val bestStreakSec: Int,
    val escapeBlocksCount: Int
) {
    fun mantraList(): List<String> =
        subliminalMantras.split("\n", "|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { GazeConfig.DEFAULT_MANTRAS_LIST }

    fun trappedPackageSet(): Set<String> =
        trappedPackagesCsv.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun currentPortalWaitSec(): Int {
        if (!dailyEscalatingPortalEnabled) return GazeConfig.ACCESS_CODE_WAIT_SEC
        val extraMin = portalUsesToday.coerceIn(0, 3)
        return (3 + extraMin) * 60
    }

    fun isConfigLockedNow(nowMs: Long = System.currentTimeMillis()): Boolean = configLockUntilMs > nowMs

    fun rankTitle(): String = when {
        totalFocusedSec >= 36_000L -> "☢️ OVERLORD"
        totalFocusedSec >= 18_000L -> "👑 TITAN"
        totalFocusedSec >= 7_200L -> "🔥 DÉVOUÉ"
        totalFocusedSec >= 2_700L -> "👁️ CAPTIF"
        totalFocusedSec >= 600L -> "⚡ INITIÉ"
        else -> "🌱 NOVICE"
    }
}

class GazeConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun readPortalUsesToday(): Int {
        val savedDay = prefs.getString(KEY_PORTAL_DAY, "").orEmpty()
        return if (savedDay == todayKey()) prefs.getInt(KEY_PORTAL_USES_TODAY, 0).coerceIn(0, 10) else 0
    }

    private fun readDailyFocusedSecToday(): Int {
        val savedDay = prefs.getString(KEY_CONTRACT_DAY, "").orEmpty()
        return if (savedDay == todayKey()) prefs.getInt(KEY_CONTRACT_FOCUSED_SEC, 0).coerceAtLeast(0) else 0
    }

    fun recordPortalUnlockCompletedToday(): Int {
        val today = todayKey()
        val cur = readPortalUsesToday()
        val next = (cur + 1).coerceAtMost(10)
        update {
            putString(KEY_PORTAL_DAY, today)
            putInt(KEY_PORTAL_USES_TODAY, next)
        }
        return next
    }

    private fun readLastReport(): SessionReport? {
        val ts = prefs.getLong(KEY_REP_TS, 0L)
        if (ts <= 0L) return null
        return SessionReport(
            grade = prefs.getString(KEY_REP_GRADE, "A").orEmpty(),
            focusPct = prefs.getInt(KEY_REP_FOCUS_PCT, 100),
            focusedSec = prefs.getInt(KEY_REP_FOCUSED_SEC, 0),
            lookAways = prefs.getInt(KEY_REP_LOOK_AWAYS, 0),
            bestStreakSec = prefs.getInt(KEY_REP_STREAK, 0),
            escapesBlocked = prefs.getInt(KEY_REP_ESCAPES, 0),
            timestampMs = ts
        )
    }

    fun saveSessionReport(grade: String, focusPct: Int, focusedSec: Int, lookAways: Int, bestStreakSec: Int, escapesBlocked: Int) {
        update {
            putString(KEY_REP_GRADE, grade)
            putInt(KEY_REP_FOCUS_PCT, focusPct.coerceIn(0, 100))
            putInt(KEY_REP_FOCUSED_SEC, focusedSec.coerceAtLeast(0))
            putInt(KEY_REP_LOOK_AWAYS, lookAways.coerceAtLeast(0))
            putInt(KEY_REP_STREAK, bestStreakSec.coerceAtLeast(0))
            putInt(KEY_REP_ESCAPES, escapesBlocked.coerceAtLeast(0))
            putLong(KEY_REP_TS, System.currentTimeMillis())
        }
    }

    fun clearSessionReport() {
        update { remove(KEY_REP_TS) }
    }

    fun snapshot(): GazeConfigSnapshot = GazeConfigSnapshot(
        folderPath = prefs.getString(KEY_FOLDER_PATH, "").orEmpty(),
        treeUri = prefs.getString(KEY_TREE_URI, "").orEmpty(),
        durationSec = prefs.getInt(KEY_DURATION_SEC, 300).coerceIn(30, 3600),
        infiniteUntilUnlock = prefs.getBoolean(KEY_INFINITE_UNLOCK, false),
        overlayMode = OverlayMode.fromId(prefs.getString(KEY_OVERLAY_MODE, OverlayMode.GHOST_PASSTHROUGH.id)),
        ghostOpacityPct = prefs.getInt(KEY_GHOST_OPACITY, 48).coerceIn(35, 65),
        shuffleVideos = prefs.getBoolean(KEY_SHUFFLE, true),
        maxClipSec = prefs.getInt(KEY_MAX_CLIP_SEC, 0).coerceIn(0, 300),
        allowSwipeNext = prefs.getBoolean(KEY_SWIPE_NEXT, true),
        pauseVideoOnLookAway = prefs.getBoolean(KEY_PAUSE_VIDEO, true),
        lookAwayPenaltySec = prefs.getInt(KEY_PENALTY_SEC, 3).coerceIn(0, 30),
        penaltyMode = PenaltyMode.fromId(prefs.getString(KEY_PENALTY_MODE, PenaltyMode.PROGRESSIVE.id)),
        yawThresholdDeg = prefs.getInt(KEY_YAW_DEG, 22).coerceIn(12, 40),
        pitchThresholdDeg = prefs.getInt(KEY_PITCH_DEG, 24).coerceIn(14, 40),
        requireEyesOpen = prefs.getBoolean(KEY_EYES_OPEN, true),
        irisTrackingEnabled = prefs.getBoolean(KEY_IRIS_EN, true),
        irisThresholdPct = prefs.getInt(KEY_IRIS_PCT, 32).coerceIn(18, 52),
        antiEscapeLockEnabled = prefs.getBoolean(KEY_ANTI_ESCAPE, true),
        voiceAlertEnabled = prefs.getBoolean(KEY_VOICE_ALERT, true),
        vibrateAlertEnabled = prefs.getBoolean(KEY_VIBRATE, true),
        subliminalEnabled = prefs.getBoolean(KEY_SUBLIMINAL_EN, true),
        subliminalIntervalSec = prefs.getInt(KEY_SUBLIMINAL_INTERVAL, 5).coerceIn(2, 20),
        subliminalDurationMs = prefs.getInt(KEY_SUBLIMINAL_DUR_MS, 120).coerceIn(80, 220),
        subliminalAlphaPct = prefs.getInt(KEY_SUBLIMINAL_ALPHA, 78).coerceIn(30, 95),
        subliminalMantras = prefs.getString(KEY_SUBLIMINAL_MANTRAS, DEFAULT_MANTRAS_TEXT).orEmpty().ifBlank { DEFAULT_MANTRAS_TEXT },
        volumeLockEnabled = prefs.getBoolean(KEY_VOLUME_LOCK, true),
        volumeFloorPct = prefs.getInt(KEY_VOLUME_FLOOR_PCT, 70).coerceIn(40, 100),
        forceMaxBrightness = prefs.getBoolean(KEY_FORCE_BRIGHTNESS, true),
        binauralBeatEnabled = prefs.getBoolean(KEY_BINAURAL_EN, true),
        binauralVolumePct = prefs.getInt(KEY_BINAURAL_VOL, 35).coerceIn(10, 80),
        binauralFreqHz = prefs.getInt(KEY_BINAURAL_HZ, 6).coerceIn(4, 12),
        unlockPopupsEnabled = prefs.getBoolean(KEY_UNLOCK_POPUPS, true),
        unlockMinigamesEnabled = prefs.getBoolean(KEY_UNLOCK_MINIGAMES, true),
        portalMinigameCount = prefs.getInt(KEY_PORTAL_GAME_COUNT, 6).coerceIn(3, 12),
        overloadSprintEnabled = prefs.getBoolean(KEY_OVERLOAD_SPRINT, true),
        gazeGatedPortalEnabled = prefs.getBoolean(KEY_GAZE_PORTAL, true),
        hellPuzzle4x4Enabled = prefs.getBoolean(KEY_HELL_4X4, true),
        flashHunt9FpsEnabled = prefs.getBoolean(KEY_FLASH_HUNT_9FPS, true),
        dailyEscalatingPortalEnabled = prefs.getBoolean(KEY_DAILY_ESC_PORTAL, true),
        portalUsesToday = readPortalUsesToday(),
        livingOpacityEnabled = prefs.getBoolean(KEY_LIVING_OPACITY, true),
        attentionHijackEnabled = prefs.getBoolean(KEY_HIJACK_EN, true),
        attentionHijackIntervalMin = prefs.getInt(KEY_HIJACK_INT_MIN, 2).coerceIn(1, 8),
        attentionHijackFocusSec = prefs.getInt(KEY_HIJACK_FOCUS_SEC, 14).coerceIn(8, 25),
        ghostMicroPopupsEnabled = prefs.getBoolean(KEY_GHOST_POPUPS_EN, true),
        ghostMicroPopupIntervalSec = prefs.getInt(KEY_GHOST_POPUPS_INT, 38).coerceIn(20, 90),
        deadZonesEnabled = prefs.getBoolean(KEY_DEAD_ZONES_EN, true),
        timeDebtEnabled = prefs.getBoolean(KEY_TIME_DEBT_EN, true),
        timeDebtCarryPct = prefs.getInt(KEY_TIME_DEBT_PCT, 75).coerceIn(50, 100),
        pendingTimeDebtSec = prefs.getInt(KEY_PENDING_DEBT_SEC, 0).coerceIn(0, 3600),
        hypnoticSpiralEnabled = prefs.getBoolean(KEY_SPIRAL_EN, true),
        spiralOpacityPct = prefs.getInt(KEY_SPIRAL_OPACITY, 26).coerceIn(12, 55),
        ttsMantraWhisperEnabled = prefs.getBoolean(KEY_TTS_WHISPER, true),
        blindTimerEnabled = prefs.getBoolean(KEY_BLIND_TIMER, false),
        antiFlatSensorEnabled = prefs.getBoolean(KEY_ANTI_FLAT, true),
        rebootSurvivalEnabled = prefs.getBoolean(KEY_REBOOT_SURVIVAL, true),
        noBlinkChallengeEnabled = prefs.getBoolean(KEY_NO_BLINK_EN, true),
        noBlinkDurationSec = prefs.getInt(KEY_NO_BLINK_SEC, 12).coerceIn(8, 20),
        appTrapEnabled = prefs.getBoolean(KEY_APP_TRAP_EN, false),
        trappedPackagesCsv = prefs.getString(KEY_TRAPPED_PKGS, DEFAULT_TRAPPED_PKGS).orEmpty(),
        appTrapDurationSec = prefs.getInt(KEY_APP_TRAP_DUR_SEC, 180).coerceIn(60, 600),
        blockShadeInGhostEnabled = prefs.getBoolean(KEY_BLOCK_SHADE_GHOST, true),
        appSwitchTaxEnabled = prefs.getBoolean(KEY_APP_SWITCH_TAX, true),
        pavlovSoundEnabled = prefs.getBoolean(KEY_PAVLOV_SOUND, true),
        finalBossEnabled = prefs.getBoolean(KEY_FINAL_BOSS, true),
        popupFolderPath = prefs.getString(KEY_POPUP_FOLDER_PATH, "").orEmpty(),
        popupTreeUri = prefs.getString(KEY_POPUP_TREE_URI, "").orEmpty(),
        popupMinSizePct = prefs.getInt(KEY_POPUP_MIN_SIZE_PCT, 28).coerceIn(12, 100),
        popupSizePct = prefs.getInt(KEY_POPUP_SIZE_PCT, 62).coerceIn(15, 100),
        popupIntervalMs = prefs.getInt(KEY_POPUP_INTERVAL_MS, 1000).coerceIn(30, 60_000),
        popupDurationMs = prefs.getInt(KEY_POPUP_DURATION_MS, 3500).coerceIn(0, 60_000),
        popupMaxSimultaneous = prefs.getInt(KEY_POPUP_MAX_SIMUL_V6, 20).coerceIn(0, 150),
        popupBurstCount = prefs.getInt(KEY_POPUP_BURST_CNT, 1).coerceIn(1, 12),
        popupOpacityPct = prefs.getInt(KEY_POPUP_OPACITY_PCT, 88).coerceIn(15, 100),
        popupRandomOpacity = prefs.getBoolean(KEY_POPUP_RAND_OPACITY, false),
        popupRampUpEnabled = prefs.getBoolean(KEY_POPUP_RAMP_UP, false),
        popupSpawnZone = PopupSpawnZone.fromId(prefs.getString(KEY_POPUP_ZONE, PopupSpawnZone.ANYWHERE.id)),
        popupAnimStyle = PopupAnimStyle.fromId(prefs.getString(KEY_POPUP_ANIM, PopupAnimStyle.RANDOM.id)),
        popupFrameStyle = PopupFrameStyle.fromId(prefs.getString(KEY_POPUP_FRAME, PopupFrameStyle.NEON_MULTI.id)),
        popupTouchMode = PopupTouchMode.fromId(prefs.getString(KEY_POPUP_TOUCH_MODE, PopupTouchMode.TAP_DISMISS.id)),
        popupTapsToClose = prefs.getInt(KEY_POPUP_TAPS_CLOSE, 1).coerceIn(1, 3),
        popupSwipeDismiss = prefs.getBoolean(KEY_POPUP_SWIPE_DISMISS, true),
        popupMitosisEnabled = prefs.getBoolean(KEY_POPUP_MITOSIS, false),
        popupRandomRotation = prefs.getBoolean(KEY_POPUP_ROTATION, true),
        popupShowMantra = prefs.getBoolean(KEY_POPUP_MANTRA, true),
        popupVibrate = prefs.getBoolean(KEY_POPUP_VIBRATE, true),
        popupSoundOnSpawn = prefs.getBoolean(KEY_POPUP_SOUND_SPAWN, false),
        popupSessionDurationSec = prefs.getInt(KEY_POPUP_SESSION_DUR, 0).coerceIn(0, 3600),
        popupLockExitWithPortal = prefs.getBoolean(KEY_POPUP_LOCK_EXIT, false),
        // v7.0 TITAN
        portalFlagSecureEnabled = prefs.getBoolean(KEY_V7_FLAG_SECURE, true),
        antiSplitScreenEnabled = prefs.getBoolean(KEY_V7_ANTI_SPLIT, true),
        overlayWatchdogEnabled = prefs.getBoolean(KEY_V7_WATCHDOG, true),
        strictAudioGuardEnabled = prefs.getBoolean(KEY_V7_STRICT_AUDIO, true),
        popupBlockShadeEnabled = prefs.getBoolean(KEY_V7_POPUP_BLOCK_SHADE, true),
        popupRebootSurvivalEnabled = prefs.getBoolean(KEY_V7_POPUP_IMMORTAL, true),
        screenOffPenaltyEnabled = prefs.getBoolean(KEY_V7_SCREEN_OFF_TAX, true),
        autoZeroCalibrationEnabled = prefs.getBoolean(KEY_V7_AUTO_ZERO, true),
        antiCameraCoverEnabled = prefs.getBoolean(KEY_V7_ANTI_CAM_COVER, true),
        faceDistanceGuardEnabled = prefs.getBoolean(KEY_V7_FACE_DIST, true),
        antiDrowsyEnabled = prefs.getBoolean(KEY_V7_ANTI_DROWSY, true),
        escalatingStrictnessEnabled = prefs.getBoolean(KEY_V7_ESC_STRICT, true),
        soloFaceGuardEnabled = prefs.getBoolean(KEY_V7_SOLO_FACE, true),
        ghostBreathingOpacityEnabled = prefs.getBoolean(KEY_V7_GHOST_BREATHE, true),
        faultGlitchEnabled = prefs.getBoolean(KEY_V7_FAULT_GLITCH, true),
        dynamicPlaybackSpeedEnabled = prefs.getBoolean(KEY_V7_DYN_SPEED, true),
        scrollingMarqueeEnabled = prefs.getBoolean(KEY_V7_MARQUEE, true),
        freezeFrameShockEnabled = prefs.getBoolean(KEY_V7_FREEZE_SHOCK, true),
        heartbeatHapticsEnabled = prefs.getBoolean(KEY_V7_HEARTBEAT_HAP, true),
        inductionTunnelEnabled = prefs.getBoolean(KEY_V7_INDUCTION, true),
        popupDriftPhysicsEnabled = prefs.getBoolean(KEY_V7_POPUP_DRIFT, true),
        popupPulseScaleEnabled = prefs.getBoolean(KEY_V7_POPUP_PULSE, true),
        popupEvasiveDodgeEnabled = prefs.getBoolean(KEY_V7_POPUP_DODGE, false),
        popupSurgeWavesEnabled = prefs.getBoolean(KEY_V7_POPUP_SURGE, true),
        popupSpiralOverlayEnabled = prefs.getBoolean(KEY_V7_POPUP_SPIRAL, false),
        popupBinauralEnabled = prefs.getBoolean(KEY_V7_POPUP_BINAURAL, false),
        popupGazeBoostEnabled = prefs.getBoolean(KEY_V7_POPUP_GAZE_BOOST, false),
        portalCancelTaxEnabled = prefs.getBoolean(KEY_V7_PORTAL_CANCEL_TAX, true),
        portalRequireCodeEntryEnabled = prefs.getBoolean(KEY_V7_PORTAL_CODE_ENTRY, true),
        portalHardcorePenaltiesEnabled = prefs.getBoolean(KEY_V7_PORTAL_HARDCORE, false),
        timeBombArmedUntilMs = prefs.getLong(KEY_V7_BOMB_UNTIL, 0L),
        timeBombDurationSec = prefs.getInt(KEY_V7_BOMB_DUR, 300).coerceIn(60, 1800),
        dailyContractGoalMin = prefs.getInt(KEY_CONTRACT_GOAL_MIN, 30).coerceIn(10, 180),
        dailyFocusedSecToday = readDailyFocusedSecToday(),
        dailyContractStreakDays = prefs.getInt(KEY_CONTRACT_STREAK, 0).coerceAtLeast(0),
        configLockUntilMs = prefs.getLong(KEY_V7_CONFIG_LOCK_UNTIL, 0L),
        lastReport = readLastReport(),
        showTelemetryBadge = prefs.getBoolean(KEY_TELEMETRY, true),
        preset = StrictnessPreset.fromId(prefs.getString(KEY_PRESET, StrictnessPreset.STRICT.id)),
        autoTriggerEnabled = prefs.getBoolean(KEY_AUTO_EN, false),
        autoTriggerEveryMin = prefs.getInt(KEY_AUTO_EVERY_MIN, 15).coerceIn(5, 60),
        autoTriggerDurationSec = prefs.getInt(KEY_AUTO_DUR_SEC, 90).coerceIn(30, 600),
        sessionsCompleted = prefs.getInt(KEY_STAT_SESSIONS, 0),
        totalFocusedSec = prefs.getLong(KEY_STAT_FOCUSED_SEC, 0L),
        totalLookAways = prefs.getInt(KEY_STAT_LOOK_AWAYS, 0),
        bestStreakSec = prefs.getInt(KEY_STAT_BEST_STREAK, 0),
        escapeBlocksCount = prefs.getInt(KEY_STAT_ESCAPES, 0)
    )

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    /** #29 1-Tap Image Pop-Up Storm Presets */
    fun applyStormPreset(p: StormPreset) {
        update {
            when (p) {
                StormPreset.GENTLE -> {
                    putInt(KEY_POPUP_MIN_SIZE_PCT, 26); putInt(KEY_POPUP_SIZE_PCT, 48)
                    putInt(KEY_POPUP_INTERVAL_MS, 2500); putInt(KEY_POPUP_DURATION_MS, 4000)
                    putInt(KEY_POPUP_MAX_SIMUL_V6, 6); putInt(KEY_POPUP_BURST_CNT, 1)
                    putInt(KEY_POPUP_OPACITY_PCT, 82); putBoolean(KEY_POPUP_RAMP_UP, false)
                    putBoolean(KEY_V7_POPUP_SURGE, false); putBoolean(KEY_V7_POPUP_DRIFT, true)
                    putBoolean(KEY_V7_POPUP_PULSE, true); putBoolean(KEY_V7_POPUP_DODGE, false)
                    putBoolean(KEY_POPUP_MITOSIS, false); putBoolean(KEY_V7_POPUP_SPIRAL, false)
                }
                StormPreset.INTENSE -> {
                    putInt(KEY_POPUP_MIN_SIZE_PCT, 24); putInt(KEY_POPUP_SIZE_PCT, 66)
                    putInt(KEY_POPUP_INTERVAL_MS, 450); putInt(KEY_POPUP_DURATION_MS, 5000)
                    putInt(KEY_POPUP_MAX_SIMUL_V6, 35); putInt(KEY_POPUP_BURST_CNT, 2)
                    putInt(KEY_POPUP_OPACITY_PCT, 90); putBoolean(KEY_POPUP_RAMP_UP, true)
                    putBoolean(KEY_V7_POPUP_SURGE, true); putBoolean(KEY_V7_POPUP_DRIFT, true)
                    putBoolean(KEY_V7_POPUP_PULSE, true); putBoolean(KEY_V7_POPUP_DODGE, true)
                    putBoolean(KEY_POPUP_MITOSIS, true); putBoolean(KEY_V7_POPUP_SPIRAL, true)
                }
                StormPreset.HURRICANE -> {
                    putInt(KEY_POPUP_MIN_SIZE_PCT, 20); putInt(KEY_POPUP_SIZE_PCT, 80)
                    putInt(KEY_POPUP_INTERVAL_MS, 50); putInt(KEY_POPUP_DURATION_MS, 7000)
                    putInt(KEY_POPUP_MAX_SIMUL_V6, 0); putInt(KEY_POPUP_BURST_CNT, 3)
                    putInt(KEY_POPUP_OPACITY_PCT, 92); putBoolean(KEY_POPUP_RAND_OPACITY, true)
                    putBoolean(KEY_POPUP_RAMP_UP, true); putBoolean(KEY_V7_POPUP_SURGE, true)
                    putBoolean(KEY_V7_POPUP_DRIFT, true); putBoolean(KEY_V7_POPUP_PULSE, true)
                    putBoolean(KEY_V7_POPUP_SPIRAL, true); putBoolean(KEY_V7_POPUP_BINAURAL, true)
                    putString(KEY_POPUP_TOUCH_MODE, PopupTouchMode.PASS_THROUGH.id)
                }
                StormPreset.APOCALYPSE -> {
                    putInt(KEY_POPUP_MIN_SIZE_PCT, 16); putInt(KEY_POPUP_SIZE_PCT, 98)
                    putInt(KEY_POPUP_INTERVAL_MS, 30); putInt(KEY_POPUP_DURATION_MS, 0)
                    putInt(KEY_POPUP_MAX_SIMUL_V6, 0); putInt(KEY_POPUP_BURST_CNT, 8)
                    putInt(KEY_POPUP_OPACITY_PCT, 95); putBoolean(KEY_POPUP_RAND_OPACITY, true)
                    putBoolean(KEY_POPUP_RAMP_UP, true); putBoolean(KEY_V7_POPUP_SURGE, true)
                    putBoolean(KEY_V7_POPUP_DRIFT, true); putBoolean(KEY_V7_POPUP_PULSE, true)
                    putBoolean(KEY_V7_POPUP_SPIRAL, true); putBoolean(KEY_V7_POPUP_BINAURAL, true)
                    putBoolean(KEY_POPUP_LOCK_EXIT, true); putBoolean(KEY_V7_POPUP_IMMORTAL, true)
                    putBoolean(KEY_V7_POPUP_BLOCK_SHADE, true); putBoolean(KEY_POPUP_SOUND_SPAWN, true)
                    putString(KEY_POPUP_TOUCH_MODE, PopupTouchMode.PASS_THROUGH.id)
                }
            }
        }
    }

    /** #36 Red Button TITAN OVERLORD: arms EVERY hardcore feature in 1 tap! */
    fun applyTitanOverlordPreset() {
        applyPreset(StrictnessPreset.RUTHLESS)
        update {
            putInt(KEY_GHOST_OPACITY, 50)
            putInt(KEY_HIJACK_INT_MIN, 1); putInt(KEY_HIJACK_FOCUS_SEC, 18)
            putInt(KEY_SUBLIMINAL_INTERVAL, 2)
            putInt(KEY_GHOST_POPUPS_INT, 20)
            putInt(KEY_PORTAL_GAME_COUNT, 9)
            putBoolean(KEY_V7_FLAG_SECURE, true)
            putBoolean(KEY_V7_ANTI_SPLIT, true)
            putBoolean(KEY_V7_WATCHDOG, true)
            putBoolean(KEY_V7_STRICT_AUDIO, true)
            putBoolean(KEY_V7_POPUP_BLOCK_SHADE, true)
            putBoolean(KEY_V7_POPUP_IMMORTAL, true)
            putBoolean(KEY_V7_SCREEN_OFF_TAX, true)
            putBoolean(KEY_V7_AUTO_ZERO, true)
            putBoolean(KEY_V7_ANTI_CAM_COVER, true)
            putBoolean(KEY_V7_FACE_DIST, true)
            putBoolean(KEY_V7_ANTI_DROWSY, true)
            putBoolean(KEY_V7_ESC_STRICT, true)
            putBoolean(KEY_V7_SOLO_FACE, true)
            putBoolean(KEY_V7_GHOST_BREATHE, true)
            putBoolean(KEY_V7_FAULT_GLITCH, true)
            putBoolean(KEY_V7_DYN_SPEED, true)
            putBoolean(KEY_V7_MARQUEE, true)
            putBoolean(KEY_V7_FREEZE_SHOCK, true)
            putBoolean(KEY_V7_HEARTBEAT_HAP, true)
            putBoolean(KEY_V7_INDUCTION, true)
            putBoolean(KEY_V7_PORTAL_CANCEL_TAX, true)
            putBoolean(KEY_V7_PORTAL_CODE_ENTRY, true)
            putBoolean(KEY_V7_PORTAL_HARDCORE, true)
        }
    }

    /** #37 Arm a surprise Time Bomb that explodes at a random instant between minDelayMin..maxDelayMin! */
    fun armTimeBomb(minDelayMin: Int, maxDelayMin: Int, durationSec: Int): Long {
        val delayMin = if (maxDelayMin <= minDelayMin) minDelayMin else Random.nextInt(minDelayMin, maxDelayMin + 1)
        val triggerAt = System.currentTimeMillis() + delayMin * 60_000L
        update {
            putLong(KEY_V7_BOMB_UNTIL, triggerAt)
            putInt(KEY_V7_BOMB_DUR, durationSec.coerceIn(60, 1800))
        }
        return triggerAt
    }

    fun disarmTimeBomb() {
        update { putLong(KEY_V7_BOMB_UNTIL, 0L) }
    }

    /** #39 Lock critical security settings for X hours so you cannot weaken them on impulse! */
    fun lockCriticalSettingsForHours(hours: Int) {
        val until = System.currentTimeMillis() + hours.coerceIn(1, 48) * 3_600_000L
        update {
            putLong(KEY_V7_CONFIG_LOCK_UNTIL, until)
            putBoolean(KEY_ANTI_ESCAPE, true)
            putBoolean(KEY_REBOOT_SURVIVAL, true)
            putBoolean(KEY_V7_FLAG_SECURE, true)
            putBoolean(KEY_V7_ANTI_SPLIT, true)
            putBoolean(KEY_V7_WATCHDOG, true)
            putBoolean(KEY_V7_SCREEN_OFF_TAX, true)
            putBoolean(KEY_V7_PORTAL_CANCEL_TAX, true)
        }
    }

    fun applyPreset(p: StrictnessPreset) {
        update {
            putString(KEY_PRESET, p.id)
            when (p) {
                StrictnessPreset.GENTLE -> {
                    putInt(KEY_YAW_DEG, 32); putInt(KEY_PITCH_DEG, 32)
                    putBoolean(KEY_EYES_OPEN, false); putBoolean(KEY_IRIS_EN, false); putInt(KEY_IRIS_PCT, 44)
                    putBoolean(KEY_PAUSE_VIDEO, false); putInt(KEY_PENALTY_SEC, 0)
                    putString(KEY_PENALTY_MODE, PenaltyMode.FIXED.id)
                    putBoolean(KEY_ANTI_ESCAPE, true); putBoolean(KEY_VOLUME_LOCK, false)
                    putBoolean(KEY_FORCE_BRIGHTNESS, false); putBoolean(KEY_SWIPE_NEXT, true)
                    putBoolean(KEY_LIVING_OPACITY, false); putBoolean(KEY_HIJACK_EN, false)
                    putBoolean(KEY_DEAD_ZONES_EN, false); putBoolean(KEY_GAZE_PORTAL, false)
                    putBoolean(KEY_ANTI_FLAT, false); putBoolean(KEY_NO_BLINK_EN, false)
                    putBoolean(KEY_HELL_4X4, false); putBoolean(KEY_FINAL_BOSS, false)
                    putBoolean(KEY_BLOCK_SHADE_GHOST, false); putBoolean(KEY_APP_SWITCH_TAX, false)
                    putBoolean(KEY_V7_ESC_STRICT, false); putBoolean(KEY_V7_INDUCTION, false)
                    putBoolean(KEY_V7_PORTAL_HARDCORE, false)
                }
                StrictnessPreset.STRICT -> {
                    putInt(KEY_YAW_DEG, 22); putInt(KEY_PITCH_DEG, 24)
                    putBoolean(KEY_EYES_OPEN, true); putBoolean(KEY_IRIS_EN, true); putInt(KEY_IRIS_PCT, 32)
                    putBoolean(KEY_PAUSE_VIDEO, true); putInt(KEY_PENALTY_SEC, 3)
                    putString(KEY_PENALTY_MODE, PenaltyMode.PROGRESSIVE.id)
                    putBoolean(KEY_ANTI_ESCAPE, true); putBoolean(KEY_VOLUME_LOCK, true)
                    putInt(KEY_VOLUME_FLOOR_PCT, 65); putBoolean(KEY_FORCE_BRIGHTNESS, true)
                    putBoolean(KEY_SUBLIMINAL_EN, true); putBoolean(KEY_BINAURAL_EN, true)
                    putBoolean(KEY_LIVING_OPACITY, true); putBoolean(KEY_HIJACK_EN, true); putInt(KEY_HIJACK_INT_MIN, 3)
                    putBoolean(KEY_GHOST_POPUPS_EN, true); putBoolean(KEY_DEAD_ZONES_EN, true)
                    putBoolean(KEY_OVERLOAD_SPRINT, true); putBoolean(KEY_TIME_DEBT_EN, true)
                    putBoolean(KEY_SPIRAL_EN, true); putBoolean(KEY_GAZE_PORTAL, true)
                    putBoolean(KEY_TTS_WHISPER, true); putBoolean(KEY_ANTI_FLAT, true)
                    putBoolean(KEY_REBOOT_SURVIVAL, true); putBoolean(KEY_NO_BLINK_EN, true)
                    putBoolean(KEY_BLOCK_SHADE_GHOST, true); putBoolean(KEY_APP_SWITCH_TAX, true)
                    putBoolean(KEY_PAVLOV_SOUND, true); putBoolean(KEY_HELL_4X4, true)
                    putBoolean(KEY_FLASH_HUNT_9FPS, true); putBoolean(KEY_DAILY_ESC_PORTAL, true)
                    putBoolean(KEY_FINAL_BOSS, true); putBoolean(KEY_SWIPE_NEXT, true)
                    putBoolean(KEY_V7_FLAG_SECURE, true); putBoolean(KEY_V7_ANTI_SPLIT, true)
                    putBoolean(KEY_V7_ANTI_CAM_COVER, true); putBoolean(KEY_V7_FACE_DIST, true)
                    putBoolean(KEY_V7_ANTI_DROWSY, true); putBoolean(KEY_V7_ESC_STRICT, true)
                    putBoolean(KEY_V7_FAULT_GLITCH, true); putBoolean(KEY_V7_SCREEN_OFF_TAX, true)
                }
                StrictnessPreset.RUTHLESS -> {
                    putInt(KEY_YAW_DEG, 16); putInt(KEY_PITCH_DEG, 18)
                    putBoolean(KEY_EYES_OPEN, true); putBoolean(KEY_IRIS_EN, true); putInt(KEY_IRIS_PCT, 24)
                    putBoolean(KEY_PAUSE_VIDEO, true); putInt(KEY_PENALTY_SEC, 5)
                    putString(KEY_PENALTY_MODE, PenaltyMode.SUDDEN_DEATH.id)
                    putBoolean(KEY_ANTI_ESCAPE, true); putBoolean(KEY_VOLUME_LOCK, true)
                    putInt(KEY_VOLUME_FLOOR_PCT, 85); putBoolean(KEY_FORCE_BRIGHTNESS, true)
                    putBoolean(KEY_SUBLIMINAL_EN, true); putInt(KEY_SUBLIMINAL_INTERVAL, 3)
                    putBoolean(KEY_BINAURAL_EN, true); putInt(KEY_BINAURAL_VOL, 50)
                    putBoolean(KEY_LIVING_OPACITY, true); putBoolean(KEY_HIJACK_EN, true)
                    putInt(KEY_HIJACK_INT_MIN, 2); putInt(KEY_HIJACK_FOCUS_SEC, 18)
                    putBoolean(KEY_GHOST_POPUPS_EN, true); putInt(KEY_GHOST_POPUPS_INT, 24)
                    putBoolean(KEY_DEAD_ZONES_EN, true); putBoolean(KEY_OVERLOAD_SPRINT, true)
                    putBoolean(KEY_TIME_DEBT_EN, true); putInt(KEY_TIME_DEBT_PCT, 100)
                    putBoolean(KEY_SPIRAL_EN, true); putInt(KEY_SPIRAL_OPACITY, 36)
                    putBoolean(KEY_GAZE_PORTAL, true); putBoolean(KEY_TTS_WHISPER, true)
                    putBoolean(KEY_BLIND_TIMER, true); putBoolean(KEY_ANTI_FLAT, true)
                    putBoolean(KEY_REBOOT_SURVIVAL, true); putBoolean(KEY_NO_BLINK_EN, true)
                    putInt(KEY_NO_BLINK_SEC, 16); putBoolean(KEY_BLOCK_SHADE_GHOST, true)
                    putBoolean(KEY_APP_SWITCH_TAX, true); putBoolean(KEY_PAVLOV_SOUND, true)
                    putBoolean(KEY_HELL_4X4, true); putBoolean(KEY_FLASH_HUNT_9FPS, true)
                    putBoolean(KEY_DAILY_ESC_PORTAL, true); putBoolean(KEY_FINAL_BOSS, true)
                    putBoolean(KEY_SWIPE_NEXT, false)
                    putBoolean(KEY_V7_FLAG_SECURE, true); putBoolean(KEY_V7_ANTI_SPLIT, true)
                    putBoolean(KEY_V7_WATCHDOG, true); putBoolean(KEY_V7_STRICT_AUDIO, true)
                    putBoolean(KEY_V7_SCREEN_OFF_TAX, true); putBoolean(KEY_V7_ANTI_CAM_COVER, true)
                    putBoolean(KEY_V7_FACE_DIST, true); putBoolean(KEY_V7_ANTI_DROWSY, true)
                    putBoolean(KEY_V7_ESC_STRICT, true); putBoolean(KEY_V7_SOLO_FACE, true)
                    putBoolean(KEY_V7_FAULT_GLITCH, true); putBoolean(KEY_V7_DYN_SPEED, true)
                    putBoolean(KEY_V7_MARQUEE, true); putBoolean(KEY_V7_FREEZE_SHOCK, true)
                    putBoolean(KEY_V7_HEARTBEAT_HAP, true); putBoolean(KEY_V7_INDUCTION, true)
                    putBoolean(KEY_V7_PORTAL_CANCEL_TAX, true); putBoolean(KEY_V7_PORTAL_CODE_ENTRY, true)
                    putBoolean(KEY_V7_PORTAL_HARDCORE, true)
                }
            }
        }
    }

    fun applyChaosRouletteRoll(): Pair<Int, String> {
        val durMin = Random.nextInt(4, 21)
        val ghostOp = listOf(45, 48, 50, 54, 58).random()
        val pm = if (Random.nextBoolean()) PenaltyMode.PROGRESSIVE else PenaltyMode.SUDDEN_DEATH
        val hijackInt = Random.nextInt(1, 4)
        val hijackSec = Random.nextInt(12, 21)
        val blind = Random.nextBoolean()
        update {
            putInt(KEY_DURATION_SEC, durMin * 60)
            putBoolean(KEY_INFINITE_UNLOCK, false)
            putInt(KEY_GHOST_OPACITY, ghostOp)
            putString(KEY_PENALTY_MODE, pm.id)
            putBoolean(KEY_LIVING_OPACITY, true)
            putBoolean(KEY_HIJACK_EN, true)
            putInt(KEY_HIJACK_INT_MIN, hijackInt)
            putInt(KEY_HIJACK_FOCUS_SEC, hijackSec)
            putBoolean(KEY_GHOST_POPUPS_EN, true)
            putBoolean(KEY_DEAD_ZONES_EN, true)
            putBoolean(KEY_SUBLIMINAL_EN, true)
            putBoolean(KEY_SPIRAL_EN, true)
            putBoolean(KEY_OVERLOAD_SPRINT, true)
            putBoolean(KEY_GAZE_PORTAL, true)
            putBoolean(KEY_NO_BLINK_EN, true)
            putBoolean(KEY_BLOCK_SHADE_GHOST, true)
            putBoolean(KEY_APP_SWITCH_TAX, true)
            putBoolean(KEY_PAVLOV_SOUND, true)
            putBoolean(KEY_HELL_4X4, true)
            putBoolean(KEY_FLASH_HUNT_9FPS, true)
            putBoolean(KEY_DAILY_ESC_PORTAL, true)
            putBoolean(KEY_FINAL_BOSS, true)
            putBoolean(KEY_V7_FAULT_GLITCH, true)
            putBoolean(KEY_V7_FREEZE_SHOCK, true)
            putBoolean(KEY_V7_INDUCTION, true)
            putBoolean(KEY_V7_SCREEN_OFF_TAX, true)
            putBoolean(KEY_V7_PORTAL_CANCEL_TAX, true)
            putBoolean(KEY_BLIND_TIMER, blind)
        }
        val tag = if (blind) "🎲 ROULETTE TITAN : Durée SECRÈTE (??:??) · ${pm.label} · Boss Final 30s !"
        else "🎲 ROULETTE TITAN : ${durMin} min · ${pm.label} · Opacité $ghostOp% · Boss Final !"
        return (durMin * 60) to tag
    }

    // ---- Video Session Immortal Checkpoints ----
    fun saveActiveSessionCheckpoint(remainingSec: Int, infinite: Boolean) {
        update {
            putBoolean(KEY_ACTIVE_CHECKPOINT, true)
            putInt(KEY_CHECKPOINT_REM_SEC, remainingSec.coerceAtLeast(30))
            putBoolean(KEY_CHECKPOINT_INF, infinite)
            putLong(KEY_CHECKPOINT_TS, System.currentTimeMillis())
        }
    }

    fun clearActiveSessionCheckpoint() {
        update {
            putBoolean(KEY_ACTIVE_CHECKPOINT, false)
            remove(KEY_CHECKPOINT_REM_SEC)
            remove(KEY_CHECKPOINT_INF)
            remove(KEY_CHECKPOINT_TS)
        }
    }

    fun consumeUnfinishedCheckpointSec(): Int? {
        if (!prefs.getBoolean(KEY_ACTIVE_CHECKPOINT, false)) return null
        val ts = prefs.getLong(KEY_CHECKPOINT_TS, 0L)
        if (System.currentTimeMillis() - ts > 12L * 3600L * 1000L) {
            clearActiveSessionCheckpoint()
            return null
        }
        val rem = prefs.getInt(KEY_CHECKPOINT_REM_SEC, 180)
        return (rem + 45).coerceIn(60, 3600)
    }

    // ---- #6 Locked Image Pop-Up Storm Immortal Checkpoints ----
    fun saveActivePopupCheckpoint(remainingSec: Int, infinite: Boolean) {
        update {
            putBoolean(KEY_POPUP_CHK_ACTIVE, true)
            putInt(KEY_POPUP_CHK_REM_SEC, if (infinite) 0 else remainingSec.coerceAtLeast(30))
            putBoolean(KEY_POPUP_CHK_INF, infinite)
            putLong(KEY_POPUP_CHK_TS, System.currentTimeMillis())
        }
    }

    fun clearActivePopupCheckpoint() {
        update {
            putBoolean(KEY_POPUP_CHK_ACTIVE, false)
            remove(KEY_POPUP_CHK_REM_SEC)
            remove(KEY_POPUP_CHK_INF)
            remove(KEY_POPUP_CHK_TS)
        }
    }

    fun consumeUnfinishedPopupCheckpoint(): Pair<Int, Boolean>? {
        if (!prefs.getBoolean(KEY_POPUP_CHK_ACTIVE, false)) return null
        val ts = prefs.getLong(KEY_POPUP_CHK_TS, 0L)
        if (System.currentTimeMillis() - ts > 12L * 3600L * 1000L) {
            clearActivePopupCheckpoint()
            return null
        }
        val inf = prefs.getBoolean(KEY_POPUP_CHK_INF, true)
        val rem = prefs.getInt(KEY_POPUP_CHK_REM_SEC, 0)
        return (if (inf) 0 else (rem + 60).coerceIn(60, 3600)) to inf
    }

    fun recordCompletedSession(focusedSec: Int, lookAways: Int, longestStreakSec: Int) {
        val cur = snapshot()
        val today = todayKey()
        val prevTodaySec = readDailyFocusedSecToday()
        val nextTodaySec = prevTodaySec + focusedSec.coerceAtLeast(0)
        val goalSec = cur.dailyContractGoalMin * 60
        val alreadyAwardedDay = prefs.getString(KEY_CONTRACT_AWARDED_DAY, "").orEmpty()
        var streak = cur.dailyContractStreakDays
        var awardDay = alreadyAwardedDay
        if (nextTodaySec >= goalSec && alreadyAwardedDay != today) {
            streak += 1
            awardDay = today
        }

        update {
            putInt(KEY_STAT_SESSIONS, cur.sessionsCompleted + 1)
            putLong(KEY_STAT_FOCUSED_SEC, cur.totalFocusedSec + focusedSec.coerceAtLeast(0))
            putInt(KEY_STAT_LOOK_AWAYS, cur.totalLookAways + lookAways.coerceAtLeast(0))
            if (longestStreakSec > cur.bestStreakSec) putInt(KEY_STAT_BEST_STREAK, longestStreakSec)
            putInt(KEY_PENDING_DEBT_SEC, 0)
            putString(KEY_CONTRACT_DAY, today)
            putInt(KEY_CONTRACT_FOCUSED_SEC, nextTodaySec)
            putInt(KEY_CONTRACT_STREAK, streak)
            if (awardDay.isNotBlank()) putString(KEY_CONTRACT_AWARDED_DAY, awardDay)
        }
        clearActiveSessionCheckpoint()
    }

    fun addPendingTimeDebt(addSec: Int): Int {
        if (addSec <= 0) return snapshot().pendingTimeDebtSec
        val next = (prefs.getInt(KEY_PENDING_DEBT_SEC, 0) + addSec).coerceIn(0, 3600)
        update { putInt(KEY_PENDING_DEBT_SEC, next) }
        return next
    }

    fun recordEscapeBlock() {
        update { putInt(KEY_STAT_ESCAPES, prefs.getInt(KEY_STAT_ESCAPES, 0) + 1) }
    }

    fun issueUnlockedAccessCode(): String {
        val code = String.format("%06d", Random.nextInt(100_000, 999_999))
        val expiresMs = System.currentTimeMillis() + 120_000L
        update {
            putString(KEY_TEMP_CODE, code)
            putLong(KEY_TEMP_CODE_EXP, expiresMs)
        }
        return code
    }

    fun getActiveAccessCodeIfValid(): String? {
        val code = prefs.getString(KEY_TEMP_CODE, "").orEmpty()
        val exp = prefs.getLong(KEY_TEMP_CODE_EXP, 0L)
        return if (code.length == 6 && System.currentTimeMillis() < exp) code else null
    }

    fun verifyDynamicAccessCode(input: String): Boolean {
        val valid = getActiveAccessCodeIfValid() ?: return false
        val ok = input.trim() == valid
        if (ok) {
            update { remove(KEY_TEMP_CODE); remove(KEY_TEMP_CODE_EXP) }
        }
        return ok
    }

    companion object {
        const val EMERGENCY_TAPS = 20
        const val ACCESS_CODE_WAIT_SEC = 180
        const val IMAGE_SPAM_DELAY_MS = 100L
        const val OVERLOAD_SPAM_DELAY_MS = 50L

        const val DEFAULT_TRAPPED_PKGS =
            "com.zhiliaoapp.musically,com.instagram.android,com.google.android.youtube,com.android.chrome,com.reddit.frontpage"

        val DEFAULT_MANTRAS_LIST = listOf(
            "REGARDE L'ÉCRAN",
            "NE DÉTOURNE PAS LES YEUX",
            "RESTE FIXÉ",
            "CONCENTRE-TOI ICI",
            "OBÉIS AU RYTHME",
            "IMPOSSIBLE D'IGNORER",
            "TOUTE TON ATTENTION",
            "LAISSE-TOI CAPTER"
        )
        val DEFAULT_MANTRAS_TEXT: String = DEFAULT_MANTRAS_LIST.joinToString("\n")

        private const val PREFS_NAME = "gazelock_ai_prefs_v1"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_DURATION_SEC = "duration_sec"
        private const val KEY_INFINITE_UNLOCK = "infinite_unlock_v3"
        private const val KEY_OVERLAY_MODE = "overlay_mode_v3"
        private const val KEY_GHOST_OPACITY = "ghost_opacity_v3"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_MAX_CLIP_SEC = "max_clip_sec"
        private const val KEY_SWIPE_NEXT = "swipe_next"
        private const val KEY_PAUSE_VIDEO = "pause_video_away"
        private const val KEY_PENALTY_SEC = "penalty_sec"
        private const val KEY_PENALTY_MODE = "penalty_mode_v2"
        private const val KEY_YAW_DEG = "yaw_deg"
        private const val KEY_PITCH_DEG = "pitch_deg"
        private const val KEY_EYES_OPEN = "eyes_open"
        private const val KEY_IRIS_EN = "iris_en_v2"
        private const val KEY_IRIS_PCT = "iris_pct_v2"
        private const val KEY_ANTI_ESCAPE = "anti_escape_v2"
        private const val KEY_VOICE_ALERT = "voice_alert"
        private const val KEY_VIBRATE = "vibrate_alert"
        private const val KEY_SUBLIMINAL_EN = "subliminal_en_v3"
        private const val KEY_SUBLIMINAL_INTERVAL = "subliminal_int_v3"
        private const val KEY_SUBLIMINAL_DUR_MS = "subliminal_dur_v3"
        private const val KEY_SUBLIMINAL_ALPHA = "subliminal_alpha_v3"
        private const val KEY_SUBLIMINAL_MANTRAS = "subliminal_mantras_v3"
        private const val KEY_VOLUME_LOCK = "volume_lock"
        private const val KEY_VOLUME_FLOOR_PCT = "volume_floor_v3"
        private const val KEY_FORCE_BRIGHTNESS = "force_brightness_v3"
        private const val KEY_BINAURAL_EN = "binaural_en_v3"
        private const val KEY_BINAURAL_VOL = "binaural_vol_v3"
        private const val KEY_BINAURAL_HZ = "binaural_hz_v3"
        private const val KEY_UNLOCK_POPUPS = "unlock_popups_v3"
        private const val KEY_UNLOCK_MINIGAMES = "unlock_minigames_v3"
        private const val KEY_PORTAL_GAME_COUNT = "portal_game_count_v6"
        private const val KEY_OVERLOAD_SPRINT = "overload_sprint_v4"
        private const val KEY_GAZE_PORTAL = "gaze_portal_v4"
        private const val KEY_HELL_4X4 = "hell_4x4_v5"
        private const val KEY_FLASH_HUNT_9FPS = "flash_hunt_9fps_v5"
        private const val KEY_DAILY_ESC_PORTAL = "daily_esc_portal_v5"
        private const val KEY_PORTAL_DAY = "portal_day_v5"
        private const val KEY_PORTAL_USES_TODAY = "portal_uses_today_v5"
        private const val KEY_LIVING_OPACITY = "living_opacity_v4"
        private const val KEY_HIJACK_EN = "hijack_en_v4"
        private const val KEY_HIJACK_INT_MIN = "hijack_int_min_v4"
        private const val KEY_HIJACK_FOCUS_SEC = "hijack_focus_sec_v4"
        private const val KEY_GHOST_POPUPS_EN = "ghost_popups_en_v4"
        private const val KEY_GHOST_POPUPS_INT = "ghost_popups_int_v4"
        private const val KEY_DEAD_ZONES_EN = "dead_zones_en_v4"
        private const val KEY_TIME_DEBT_EN = "time_debt_en_v4"
        private const val KEY_TIME_DEBT_PCT = "time_debt_pct_v4"
        private const val KEY_PENDING_DEBT_SEC = "pending_debt_sec_v4"
        private const val KEY_SPIRAL_EN = "spiral_en_v4"
        private const val KEY_SPIRAL_OPACITY = "spiral_opacity_v4"
        private const val KEY_TTS_WHISPER = "tts_whisper_v4"
        private const val KEY_BLIND_TIMER = "blind_timer_v4"
        private const val KEY_ANTI_FLAT = "anti_flat_v4"
        private const val KEY_REBOOT_SURVIVAL = "reboot_survival_v4"
        private const val KEY_ACTIVE_CHECKPOINT = "active_chk_v4"
        private const val KEY_CHECKPOINT_REM_SEC = "chk_rem_sec_v4"
        private const val KEY_CHECKPOINT_INF = "chk_inf_v4"
        private const val KEY_CHECKPOINT_TS = "chk_ts_v4"
        private const val KEY_POPUP_CHK_ACTIVE = "popup_chk_active_v7"
        private const val KEY_POPUP_CHK_REM_SEC = "popup_chk_rem_v7"
        private const val KEY_POPUP_CHK_INF = "popup_chk_inf_v7"
        private const val KEY_POPUP_CHK_TS = "popup_chk_ts_v7"
        private const val KEY_NO_BLINK_EN = "no_blink_en_v5"
        private const val KEY_NO_BLINK_SEC = "no_blink_sec_v5"
        private const val KEY_APP_TRAP_EN = "app_trap_en_v5"
        private const val KEY_TRAPPED_PKGS = "trapped_pkgs_v5"
        private const val KEY_APP_TRAP_DUR_SEC = "app_trap_dur_v5"
        private const val KEY_BLOCK_SHADE_GHOST = "block_shade_ghost_v5"
        private const val KEY_APP_SWITCH_TAX = "app_switch_tax_v5"
        private const val KEY_PAVLOV_SOUND = "pavlov_sound_v5"
        private const val KEY_FINAL_BOSS = "final_boss_v5"
        private const val KEY_POPUP_FOLDER_PATH = "popup_folder_path_v51"
        private const val KEY_POPUP_TREE_URI = "popup_tree_uri_v51"
        private const val KEY_POPUP_MIN_SIZE_PCT = "popup_min_size_v6"
        private const val KEY_POPUP_SIZE_PCT = "popup_size_pct_v51"
        private const val KEY_POPUP_INTERVAL_MS = "popup_int_ms_v6"
        private const val KEY_POPUP_DURATION_MS = "popup_dur_ms_v6"
        private const val KEY_POPUP_MAX_SIMUL_V6 = "popup_max_simul_v6"
        private const val KEY_POPUP_BURST_CNT = "popup_burst_cnt_v6"
        private const val KEY_POPUP_OPACITY_PCT = "popup_opacity_v51"
        private const val KEY_POPUP_RAND_OPACITY = "popup_rand_op_v6"
        private const val KEY_POPUP_RAMP_UP = "popup_ramp_up_v6"
        private const val KEY_POPUP_ZONE = "popup_zone_v6"
        private const val KEY_POPUP_ANIM = "popup_anim_v6"
        private const val KEY_POPUP_FRAME = "popup_frame_v6"
        private const val KEY_POPUP_TOUCH_MODE = "popup_touch_mode_v51"
        private const val KEY_POPUP_TAPS_CLOSE = "popup_taps_close_v51"
        private const val KEY_POPUP_SWIPE_DISMISS = "popup_swipe_dismiss_v6"
        private const val KEY_POPUP_MITOSIS = "popup_mitosis_v51"
        private const val KEY_POPUP_ROTATION = "popup_rotation_v51"
        private const val KEY_POPUP_MANTRA = "popup_mantra_v51"
        private const val KEY_POPUP_VIBRATE = "popup_vibrate_v51"
        private const val KEY_POPUP_SOUND_SPAWN = "popup_sound_spawn_v6"
        private const val KEY_POPUP_SESSION_DUR = "popup_sess_dur_v51"
        private const val KEY_POPUP_LOCK_EXIT = "popup_lock_exit_v51"
        // v7.0 TITAN Keys
        private const val KEY_V7_FLAG_SECURE = "v7_flag_secure"
        private const val KEY_V7_ANTI_SPLIT = "v7_anti_split"
        private const val KEY_V7_WATCHDOG = "v7_watchdog"
        private const val KEY_V7_STRICT_AUDIO = "v7_strict_audio"
        private const val KEY_V7_POPUP_BLOCK_SHADE = "v7_popup_block_shade"
        private const val KEY_V7_POPUP_IMMORTAL = "v7_popup_immortal"
        private const val KEY_V7_SCREEN_OFF_TAX = "v7_screen_off_tax"
        private const val KEY_V7_AUTO_ZERO = "v7_auto_zero"
        private const val KEY_V7_ANTI_CAM_COVER = "v7_anti_cam_cover"
        private const val KEY_V7_FACE_DIST = "v7_face_dist"
        private const val KEY_V7_ANTI_DROWSY = "v7_anti_drowsy"
        private const val KEY_V7_ESC_STRICT = "v7_esc_strict"
        private const val KEY_V7_SOLO_FACE = "v7_solo_face"
        private const val KEY_V7_GHOST_BREATHE = "v7_ghost_breathe"
        private const val KEY_V7_FAULT_GLITCH = "v7_fault_glitch"
        private const val KEY_V7_DYN_SPEED = "v7_dyn_speed"
        private const val KEY_V7_MARQUEE = "v7_marquee"
        private const val KEY_V7_FREEZE_SHOCK = "v7_freeze_shock"
        private const val KEY_V7_HEARTBEAT_HAP = "v7_heartbeat_hap"
        private const val KEY_V7_INDUCTION = "v7_induction"
        private const val KEY_V7_POPUP_DRIFT = "v7_popup_drift"
        private const val KEY_V7_POPUP_PULSE = "v7_popup_pulse"
        private const val KEY_V7_POPUP_DODGE = "v7_popup_dodge"
        private const val KEY_V7_POPUP_SURGE = "v7_popup_surge"
        private const val KEY_V7_POPUP_SPIRAL = "v7_popup_spiral"
        private const val KEY_V7_POPUP_BINAURAL = "v7_popup_binaural"
        private const val KEY_V7_POPUP_GAZE_BOOST = "v7_popup_gaze_boost"
        private const val KEY_V7_PORTAL_CANCEL_TAX = "v7_portal_cancel_tax"
        private const val KEY_V7_PORTAL_CODE_ENTRY = "v7_portal_code_entry"
        private const val KEY_V7_PORTAL_HARDCORE = "v7_portal_hardcore"
        private const val KEY_V7_BOMB_UNTIL = "v7_bomb_until"
        private const val KEY_V7_BOMB_DUR = "v7_bomb_dur"
        private const val KEY_CONTRACT_GOAL_MIN = "v7_contract_goal_min"
        private const val KEY_CONTRACT_DAY = "v7_contract_day"
        private const val KEY_CONTRACT_FOCUSED_SEC = "v7_contract_focused_sec"
        private const val KEY_CONTRACT_STREAK = "v7_contract_streak"
        private const val KEY_CONTRACT_AWARDED_DAY = "v7_contract_awarded_day"
        private const val KEY_V7_CONFIG_LOCK_UNTIL = "v7_config_lock_until"
        private const val KEY_REP_GRADE = "v7_rep_grade"
        private const val KEY_REP_FOCUS_PCT = "v7_rep_focus_pct"
        private const val KEY_REP_FOCUSED_SEC = "v7_rep_focused_sec"
        private const val KEY_REP_LOOK_AWAYS = "v7_rep_look_aways"
        private const val KEY_REP_STREAK = "v7_rep_streak"
        private const val KEY_REP_ESCAPES = "v7_rep_escapes"
        private const val KEY_REP_TS = "v7_rep_ts"
        private const val KEY_TELEMETRY = "show_telemetry"
        private const val KEY_PRESET = "preset"
        private const val KEY_AUTO_EN = "auto_en"
        private const val KEY_AUTO_EVERY_MIN = "auto_every_min"
        private const val KEY_AUTO_DUR_SEC = "auto_dur_sec"
        private const val KEY_STAT_SESSIONS = "stat_sessions"
        private const val KEY_STAT_FOCUSED_SEC = "stat_focused_sec"
        private const val KEY_STAT_LOOK_AWAYS = "stat_look_aways"
        private const val KEY_STAT_BEST_STREAK = "stat_best_streak"
        private const val KEY_STAT_ESCAPES = "stat_escapes_v2"
        private const val KEY_TEMP_CODE = "temp_access_code_v2"
        private const val KEY_TEMP_CODE_EXP = "temp_access_code_exp_v2"
    }
}
