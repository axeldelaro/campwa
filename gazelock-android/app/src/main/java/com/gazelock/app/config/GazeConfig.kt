package com.gazelock.app.config

import android.content.Context
import android.content.SharedPreferences

enum class StrictnessPreset(val id: String, val label: String, val emoji: String) {
    GENTLE("gentle", "Doux", "🌿"),
    STRICT("strict", "Strict", "🔥"),
    RUTHLESS("ruthless", "Impitoyable", "😈");

    companion object {
        fun fromId(id: String?): StrictnessPreset = values().firstOrNull { it.id == id } ?: STRICT
    }
}

data class GazeConfigSnapshot(
    val folderPath: String,
    val treeUri: String,
    val durationSec: Int,              // 30..3600
    val shuffleVideos: Boolean,
    val maxClipSec: Int,               // 0 = full video, else 15/30/60
    val allowSwipeNext: Boolean,
    val pauseVideoOnLookAway: Boolean,
    val lookAwayPenaltySec: Int,       // 0, 3, 5, 10
    val yawThresholdDeg: Int,          // 14..38
    val pitchThresholdDeg: Int,        // 16..38
    val requireEyesOpen: Boolean,
    val voiceAlertEnabled: Boolean,
    val vibrateAlertEnabled: Boolean,
    val volumeLockEnabled: Boolean,
    val showTelemetryBadge: Boolean,
    val emergencyPin: String,
    val preset: StrictnessPreset,
    // Optional Auto-Ambush timer via Accessibility
    val autoTriggerEnabled: Boolean,
    val autoTriggerEveryMin: Int,      // 5..60
    val autoTriggerDurationSec: Int,   // 30..600
    // Cumulative Stats
    val sessionsCompleted: Int,
    val totalFocusedSec: Long,
    val totalLookAways: Int,
    val bestStreakSec: Int
)

class GazeConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): GazeConfigSnapshot = GazeConfigSnapshot(
        folderPath = prefs.getString(KEY_FOLDER_PATH, "").orEmpty(),
        treeUri = prefs.getString(KEY_TREE_URI, "").orEmpty(),
        durationSec = prefs.getInt(KEY_DURATION_SEC, 180).coerceIn(30, 3600),
        shuffleVideos = prefs.getBoolean(KEY_SHUFFLE, true),
        maxClipSec = prefs.getInt(KEY_MAX_CLIP_SEC, 0).coerceIn(0, 300),
        allowSwipeNext = prefs.getBoolean(KEY_SWIPE_NEXT, true),
        pauseVideoOnLookAway = prefs.getBoolean(KEY_PAUSE_VIDEO, true),
        lookAwayPenaltySec = prefs.getInt(KEY_PENALTY_SEC, 3).coerceIn(0, 30),
        yawThresholdDeg = prefs.getInt(KEY_YAW_DEG, 22).coerceIn(12, 40),
        pitchThresholdDeg = prefs.getInt(KEY_PITCH_DEG, 24).coerceIn(14, 40),
        requireEyesOpen = prefs.getBoolean(KEY_EYES_OPEN, true),
        voiceAlertEnabled = prefs.getBoolean(KEY_VOICE_ALERT, true),
        vibrateAlertEnabled = prefs.getBoolean(KEY_VIBRATE, true),
        volumeLockEnabled = prefs.getBoolean(KEY_VOLUME_LOCK, false),
        showTelemetryBadge = prefs.getBoolean(KEY_TELEMETRY, true),
        emergencyPin = prefs.getString(KEY_PIN, DEFAULT_PIN).orEmpty().ifBlank { DEFAULT_PIN },
        preset = StrictnessPreset.fromId(prefs.getString(KEY_PRESET, StrictnessPreset.STRICT.id)),
        autoTriggerEnabled = prefs.getBoolean(KEY_AUTO_EN, false),
        autoTriggerEveryMin = prefs.getInt(KEY_AUTO_EVERY_MIN, 15).coerceIn(5, 60),
        autoTriggerDurationSec = prefs.getInt(KEY_AUTO_DUR_SEC, 90).coerceIn(30, 600),
        sessionsCompleted = prefs.getInt(KEY_STAT_SESSIONS, 0),
        totalFocusedSec = prefs.getLong(KEY_STAT_FOCUSED_SEC, 0L),
        totalLookAways = prefs.getInt(KEY_STAT_LOOK_AWAYS, 0),
        bestStreakSec = prefs.getInt(KEY_STAT_BEST_STREAK, 0)
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
                    putBoolean(KEY_EYES_OPEN, false); putBoolean(KEY_PAUSE_VIDEO, false)
                    putInt(KEY_PENALTY_SEC, 0); putBoolean(KEY_VOLUME_LOCK, false)
                    putBoolean(KEY_SWIPE_NEXT, true)
                }
                StrictnessPreset.STRICT -> {
                    putInt(KEY_YAW_DEG, 22); putInt(KEY_PITCH_DEG, 24)
                    putBoolean(KEY_EYES_OPEN, true); putBoolean(KEY_PAUSE_VIDEO, true)
                    putInt(KEY_PENALTY_SEC, 3); putBoolean(KEY_VOLUME_LOCK, false)
                    putBoolean(KEY_SWIPE_NEXT, true)
                }
                StrictnessPreset.RUTHLESS -> {
                    putInt(KEY_YAW_DEG, 16); putInt(KEY_PITCH_DEG, 18)
                    putBoolean(KEY_EYES_OPEN, true); putBoolean(KEY_PAUSE_VIDEO, true)
                    putInt(KEY_PENALTY_SEC, 6); putBoolean(KEY_VOLUME_LOCK, true)
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

    fun verifyEmergencyPin(input: String): Boolean = input.trim() == snapshot().emergencyPin

    companion object {
        const val DEFAULT_PIN = "100205"
        const val EMERGENCY_TAPS = 20

        private const val PREFS_NAME = "gazelock_ai_prefs_v1"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_DURATION_SEC = "duration_sec"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_MAX_CLIP_SEC = "max_clip_sec"
        private const val KEY_SWIPE_NEXT = "swipe_next"
        private const val KEY_PAUSE_VIDEO = "pause_video_away"
        private const val KEY_PENALTY_SEC = "penalty_sec"
        private const val KEY_YAW_DEG = "yaw_deg"
        private const val KEY_PITCH_DEG = "pitch_deg"
        private const val KEY_EYES_OPEN = "eyes_open"
        private const val KEY_VOICE_ALERT = "voice_alert"
        private const val KEY_VIBRATE = "vibrate_alert"
        private const val KEY_VOLUME_LOCK = "volume_lock"
        private const val KEY_TELEMETRY = "show_telemetry"
        private const val KEY_PIN = "emergency_pin"
        private const val KEY_PRESET = "preset"
        private const val KEY_AUTO_EN = "auto_en"
        private const val KEY_AUTO_EVERY_MIN = "auto_every_min"
        private const val KEY_AUTO_DUR_SEC = "auto_dur_sec"
        private const val KEY_STAT_SESSIONS = "stat_sessions"
        private const val KEY_STAT_FOCUSED_SEC = "stat_focused_sec"
        private const val KEY_STAT_LOOK_AWAYS = "stat_look_aways"
        private const val KEY_STAT_BEST_STREAK = "stat_best_streak"
    }
}
