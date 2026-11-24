package com.gazelock.app.config

import android.content.Context
import android.content.SharedPreferences
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
        "Vidéo plein écran à 45–50 % d'opacité par-dessus tes applis : tactile 100 % déverrouillé, mais impossible de retirer les vidéos hors créneau 3 min (overlay) !"
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
    val durationSec: Int,              // 30..3600
    val infiniteUntilUnlock: Boolean,  // true = NO auto-end timer, ONLY removable via 3-min overlay portal!
    val overlayMode: OverlayMode,      // GHOST_PASSTHROUGH (45-50% touch-unlocked) vs FULL_LOCK (100% opaque)
    val ghostOpacityPct: Int,          // 35..65 (default 48% right in the 45-50% sweet spot!)
    val shuffleVideos: Boolean,
    val maxClipSec: Int,               // 0 = full video, else 15/30/60
    val allowSwipeNext: Boolean,
    val pauseVideoOnLookAway: Boolean,
    val lookAwayPenaltySec: Int,       // 0, 3, 5, 10
    val penaltyMode: PenaltyMode,      // FIXED, PROGRESSIVE, SUDDEN_DEATH
    val yawThresholdDeg: Int,          // 12..40
    val pitchThresholdDeg: Int,        // 14..40
    val requireEyesOpen: Boolean,
    val irisTrackingEnabled: Boolean,  // #2 Real pupil/iris gaze deviation detection
    val irisThresholdPct: Int,         // 18..52
    val antiEscapeLockEnabled: Boolean,// #13 Anti-Escape watchdog (+15s penalty)
    val voiceAlertEnabled: Boolean,
    val vibrateAlertEnabled: Boolean,
    // #7 Subliminal Image Flashes (0.08s-0.22s) + Neon Mantras
    val subliminalEnabled: Boolean,
    val subliminalIntervalSec: Int,    // 2..20
    val subliminalDurationMs: Int,     // 80..220
    val subliminalAlphaPct: Int,       // 30..95
    val subliminalMantras: String,
    // #8 Absolute Audio Lock + Forced Max Brightness + Hypnotic Binaural Beat
    val volumeLockEnabled: Boolean,
    val volumeFloorPct: Int,           // 40..100
    val forceMaxBrightness: Boolean,
    val binauralBeatEnabled: Boolean,
    val binauralVolumePct: Int,        // 10..80
    val binauralFreqHz: Int,           // 4..12 Hz Theta/Alpha hypnotic pulse
    // 3-Minute Overlay Portal Pop-ups & Mini-Games
    val unlockPopupsEnabled: Boolean,
    val unlockMinigamesEnabled: Boolean,
    val showTelemetryBadge: Boolean,
    val preset: StrictnessPreset,
    // Optional Auto-Ambush timer via Accessibility
    val autoTriggerEnabled: Boolean,
    val autoTriggerEveryMin: Int,      // 5..60
    val autoTriggerDurationSec: Int,   // 30..600
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
}

class GazeConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
                }
                StrictnessPreset.STRICT -> {
                    putInt(KEY_YAW_DEG, 22); putInt(KEY_PITCH_DEG, 24)
                    putBoolean(KEY_EYES_OPEN, true); putBoolean(KEY_IRIS_EN, true); putInt(KEY_IRIS_PCT, 32)
                    putBoolean(KEY_PAUSE_VIDEO, true); putInt(KEY_PENALTY_SEC, 3)
                    putString(KEY_PENALTY_MODE, PenaltyMode.PROGRESSIVE.id)
                    putBoolean(KEY_ANTI_ESCAPE, true); putBoolean(KEY_VOLUME_LOCK, true)
                    putInt(KEY_VOLUME_FLOOR_PCT, 65); putBoolean(KEY_FORCE_BRIGHTNESS, true)
                    putBoolean(KEY_SUBLIMINAL_EN, true); putBoolean(KEY_BINAURAL_EN, true)
                    putBoolean(KEY_SWIPE_NEXT, true)
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
                    putBoolean(KEY_SWIPE_NEXT, false)
                }
            }
        }
    }

    fun recordCompletedSession(focusedSec: Int, lookAways: Int, longestStreakSec: Int) {
        val cur = snapshot()
        update {
            putInt(KEY_STAT_SESSIONS, cur.sessionsCompleted + 1)
            putLong(KEY_STAT_FOCUSED_SEC, cur.totalFocusedSec + focusedSec.coerceAtLeast(0))
            putInt(KEY_STAT_LOOK_AWAYS, cur.totalLookAways + lookAways.coerceAtLeast(0))
            if (longestStreakSec > cur.bestStreakSec) putInt(KEY_STAT_BEST_STREAK, longestStreakSec)
        }
    }

    fun recordEscapeBlock() {
        update { putInt(KEY_STAT_ESCAPES, prefs.getInt(KEY_STAT_ESCAPES, 0) + 1) }
    }

    /**
     * Generates a fresh 6-digit access code ONLY after completing the imposed 3-minute (180s)
     * 0.1s folder image-spam + pop-ups + mini-games overlay trial! Valid for 2 minutes.
     */
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
        const val ACCESS_CODE_WAIT_SEC = 180 // Imposed 3 full minutes!
        const val IMAGE_SPAM_DELAY_MS = 100L // 0.1s rapid image display delay!

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
