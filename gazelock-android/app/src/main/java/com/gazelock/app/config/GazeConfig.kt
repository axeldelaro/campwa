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
        "Vidéo 100 % opaque, tactile verrouillé et anti-évasion total (Paramètres, Récents & Panneau bloqués)."
    );

    companion object {
        fun fromId(id: String?): OverlayMode = values().firstOrNull { it.id == id } ?: GHOST_PASSTHROUGH
    }
}

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
    // Portal & Mini-Games (#16, #17, #22, #51, #54, #70)
    val unlockPopupsEnabled: Boolean,
    val unlockMinigamesEnabled: Boolean,
    val overloadSprintEnabled: Boolean,
    val gazeGatedPortalEnabled: Boolean,
    val hellPuzzle4x4Enabled: Boolean,       // #51 4x4 Hell Sliding Puzzle (15 tiles)!
    val flashHunt9FpsEnabled: Boolean,       // #54 Flash Target Hunt at 9 images/sec (111ms)!
    val dailyEscalatingPortalEnabled: Boolean,// #70 Daily escalating portal: 3m -> 4m -> 5m -> 6m!
    val portalUsesToday: Int,
    // v4.0 Extreme Ghost & Control (#1, #2, #5, #9, #18, #21, #23, #24, #25, #26)
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
    // v5.0 APOCALYPSE Additions (#11, #21, #29, #30, #36, #76)
    val noBlinkChallengeEnabled: Boolean,    // #11 Anti-Blink Streak Challenge (+15s on blink!)
    val noBlinkDurationSec: Int,             // 8..20 s
    val appTrapEnabled: Boolean,             // #21 Trapped Apps instant ambush via Accessibility!
    val trappedPackagesCsv: String,
    val appTrapDurationSec: Int,             // 60..600 s
    val blockShadeInGhostEnabled: Boolean,   // #29 Block Quick Settings / Notification Shade in Ghost mode!
    val appSwitchTaxEnabled: Boolean,        // #30 >3 app switches/min -> +10s & +5% opacity!
    val pavlovSoundEnabled: Boolean,         // #36 Pavlovian chime on 30s streak & harsh buzz on faults!
    val finalBossEnabled: Boolean,           // #76 Final 30s 100% Zero-Fault Boss (fail = +60s!)
    val showTelemetryBadge: Boolean,
    val preset: StrictnessPreset,
    // Optional Auto-Ambush timer via Accessibility
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

    /** #70 Returns today's imposed portal duration in seconds: 180s -> 240s -> 300s -> 360s (max 6 min). */
    fun currentPortalWaitSec(): Int {
        if (!dailyEscalatingPortalEnabled) return GazeConfig.ACCESS_CODE_WAIT_SEC
        val extraMin = portalUsesToday.coerceIn(0, 3)
        return (3 + extraMin) * 60
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

    /** #70 Increments today's completed portal unlock count (3m -> 4m -> 5m -> 6m!). */
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
            putBoolean(KEY_BLIND_TIMER, blind)
        }
        val tag = if (blind) "🎲 ROULETTE CHAOS v5 : Durée SECRÈTE (??:??) · ${pm.label} · Boss Final 30s !"
        else "🎲 ROULETTE CHAOS v5 : ${durMin} min · ${pm.label} · Opacité $ghostOp% · Boss Final !"
        return (durMin * 60) to tag
    }

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

    fun recordCompletedSession(focusedSec: Int, lookAways: Int, longestStreakSec: Int) {
        val cur = snapshot()
        update {
            putInt(KEY_STAT_SESSIONS, cur.sessionsCompleted + 1)
            putLong(KEY_STAT_FOCUSED_SEC, cur.totalFocusedSec + focusedSec.coerceAtLeast(0))
            putInt(KEY_STAT_LOOK_AWAYS, cur.totalLookAways + lookAways.coerceAtLeast(0))
            if (longestStreakSec > cur.bestStreakSec) putInt(KEY_STAT_BEST_STREAK, longestStreakSec)
            putInt(KEY_PENDING_DEBT_SEC, 0)
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
        private const val KEY_NO_BLINK_EN = "no_blink_en_v5"
        private const val KEY_NO_BLINK_SEC = "no_blink_sec_v5"
        private const val KEY_APP_TRAP_EN = "app_trap_en_v5"
        private const val KEY_TRAPPED_PKGS = "trapped_pkgs_v5"
        private const val KEY_APP_TRAP_DUR_SEC = "app_trap_dur_v5"
        private const val KEY_BLOCK_SHADE_GHOST = "block_shade_ghost_v5"
        private const val KEY_APP_SWITCH_TAX = "app_switch_tax_v5"
        private const val KEY_PAVLOV_SOUND = "pavlov_sound_v5"
        private const val KEY_FINAL_BOSS = "final_boss_v5"
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
