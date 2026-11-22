package com.demonpet.app.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class AvatarStyle(val id: String, val label: String) {
    REALISTIC_DEMON("realistic_demon", "Démon Réaliste 😈"),
    REALISTIC_PORTRAIT("realistic_portrait", "Portrait Studio 📸"),
    REALISTIC_MOM_CROWN("realistic_crown", "Reine Possessive 👑"),
    REALISTIC_MINI_BODY("realistic_body", "Silhouette Complète ✨");

    companion object {
        fun fromId(id: String?): AvatarStyle = when (id) {
            "realistic_demon", "demon" -> REALISTIC_DEMON
            "realistic_portrait", "head" -> REALISTIC_PORTRAIT
            "realistic_crown", "mom" -> REALISTIC_MOM_CROWN
            "realistic_body", "chibi" -> REALISTIC_MINI_BODY
            else -> REALISTIC_DEMON
        }
    }
}

enum class SeverityPreset(val id: String, val label: String, val emoji: String) {
    GENTLE("gentle", "Gentil", "😇"),
    MOM("mom", "Maman Poule", "🤱"),
    STRICT("strict", "Strict", "😤"),
    TYRANT("tyrant", "Tyran", "😈");

    companion object {
        fun fromId(id: String?): SeverityPreset = values().firstOrNull { it.id == id } ?: STRICT
    }
}

enum class FaceCensorMode(val id: String, val label: String) {
    OFF("off", "Désactivé"),
    BLUR_JEALOUS("blur", "Flou jaloux (#1)"),
    FACE_SWAP_SELF("faceswap", "Coller SA tête (#2)"),
    EYE_BAR("eyebar", "Bandeau yeux (#5)"),
    GPU_SABOTAGE("gpu", "Shader GPU (#6)");

    companion object {
        fun fromId(id: String?): FaceCensorMode = values().firstOrNull { it.id == id } ?: FACE_SWAP_SELF
    }
}

enum class GenderFilter(val id: String, val label: String) {
    ALL("all", "Tous"),
    FEMALE_ONLY("female", "Femmes"),
    MALE_ONLY("male", "Hommes");

    companion object {
        fun fromId(id: String?): GenderFilter = values().firstOrNull { it.id == id } ?: ALL
    }
}

enum class WallpaperTarget(val id: String, val label: String) {
    BOTH("both", "Accueil + Verrou."),
    HOME("home", "Accueil seul"),
    LOCK("lock", "Verrouillage seul");

    companion object {
        fun fromId(id: String?): WallpaperTarget = values().firstOrNull { it.id == id } ?: BOTH
    }
}

enum class DemonMood(val label: String, val emoji: String) {
    ANGEL("Calme", "😇"),
    MOM("Maman poule", "🤱"),
    JEALOUS("Jalouse", "👀"),
    FURIOUS("Furieuse", "😈");

    companion object {
        fun fromScore(score: Int): DemonMood = when {
            score <= 25 -> ANGEL
            score <= 50 -> MOM
            score <= 75 -> JEALOUS
            else -> FURIOUS
        }
    }
}

data class DemonConfigSnapshot(
    val enabled: Boolean,
    val challengeLocked: Boolean,
    val strictSettingsLock: Boolean,
    val exitPin: String,
    val severityPreset: SeverityPreset,
    val avatarPath: String,
    val avatarName: String,
    val avatarStyle: AvatarStyle,
    val avatarSizeDp: Int,
    val cutoutIncludeHair: Boolean,
    val mediaFolderPath: String,
    val mediaTreeUri: String,
    val includeHiddenFiles: Boolean,
    val chaosLevel: Int,           // 1..10
    val moodScore: Int,            // 0..100
    val minIntervalSec: Int,       // 15..300
    val vibrateOnPrank: Boolean,
    // Vision AI (#1-#6)
    val faceCensorMode: FaceCensorMode,
    val faceGenderFilter: GenderFilter,
    val nsfwShieldEnabled: Boolean,
    // Parental & behaviors (#7-#13, #19-#23)
    val askPermissionGate: Boolean,
    val appQuotaLockEnabled: Boolean,
    val appQuotaMinutes: Int,      // 2..60
    val negotiationMiniGames: Boolean,
    val curfewEnabled: Boolean,
    val curfewStartHour: Int,      // 0..23
    val curfewEndHour: Int,        // 0..23
    val homeworkModeEnabled: Boolean,
    val antiDoomscrollEnabled: Boolean,
    val settingsPanicEnabled: Boolean,
    val wallpaperPrankEnabled: Boolean,
    val wallpaperTarget: WallpaperTarget,
    val draggedPopupEnabled: Boolean,
    val maxPopups: Int,            // 1..4
    val fullscreenFlashEnabled: Boolean,
    val flashDurationMs: Int,      // 500..5000
    val nightBrightnessEnabled: Boolean,
    val lowBatteryPanicEnabled: Boolean,
    val appCommentsEnabled: Boolean,
    val speedWarningEnabled: Boolean,
    val speedThresholdKmh: Int,    // 10..90
    val customPhrases: String,
    val watchedPackages: Set<String>,
    // NEW v2.0 Severe & Interactive Accessibility Powers (#26,27,29,31,32,34,35,37,38,39,42,44,50,53,61,65)
    val counterScrollEnabled: Boolean,       // #26
    val compulsiveBackEnabled: Boolean,      // #27
    val forcePauseVideoEnabled: Boolean,     // #29
    val clickStealShieldEnabled: Boolean,    // #31
    val keyboardSabotageEnabled: Boolean,    // #32
    val closeNotifShadeEnabled: Boolean,     // #34
    val blockQuickSettingsEnabled: Boolean,  // #35
    val antiLikeFollowEnabled: Boolean,      // #37
    val timeoutCornerEnabled: Boolean,       // #38 (with video from folder!)
    val timeoutCornerSec: Int,               // 15..300
    val punishmentLinesEnabled: Boolean,     // #39
    val punishmentLinesCount: Int,           // 2..10
    val punishmentPhrase: String,            // #39
    val deadZoneKeyboardEnabled: Boolean,    // #42
    val blacklistEnabled: Boolean,           // #44
    val blacklistPackages: Set<String>,      // #44
    val dodgeFingerEnabled: Boolean,         // #50
    val surpriseQuizEnabled: Boolean,        // #53
    val forcedSlideshowEnabled: Boolean      // #61
) {
    val mood: DemonMood
        get() = DemonMood.fromScore(moodScore)

    val anyAiVisionEnabled: Boolean
        get() = faceCensorMode != FaceCensorMode.OFF || nsfwShieldEnabled

    fun customPhraseList(): List<String> =
        customPhrases.split("\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
}

class DemonSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): DemonConfigSnapshot {
        val rawWatched = prefs.getStringSet(KEY_WATCHED_PKGS, null) ?: DEFAULT_WATCHED_PKGS
        val rawBlack = prefs.getStringSet(KEY_BLACKLIST_PKGS, null) ?: emptySet()
        return DemonConfigSnapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            challengeLocked = prefs.getBoolean(KEY_CHALLENGE_LOCKED, false),
            strictSettingsLock = prefs.getBoolean(KEY_STRICT_SETTINGS_LOCK, true),
            exitPin = prefs.getString(KEY_EXIT_PIN, DEFAULT_EXIT_PIN) ?: DEFAULT_EXIT_PIN,
            severityPreset = SeverityPreset.fromId(prefs.getString(KEY_SEVERITY_PRESET, SeverityPreset.STRICT.id)),
            avatarPath = prefs.getString(KEY_AVATAR_PATH, "").orEmpty(),
            avatarName = prefs.getString(KEY_AVATAR_NAME, "Maman Démon") ?: "Maman Démon",
            avatarStyle = AvatarStyle.fromId(prefs.getString(KEY_AVATAR_STYLE, AvatarStyle.REALISTIC_DEMON.id)),
            avatarSizeDp = prefs.getInt(KEY_AVATAR_SIZE, 108).coerceIn(72, 156),
            cutoutIncludeHair = prefs.getBoolean(KEY_CUTOUT_HAIR, true),
            mediaFolderPath = prefs.getString(KEY_MEDIA_FOLDER, "").orEmpty(),
            mediaTreeUri = prefs.getString(KEY_MEDIA_TREE_URI, "").orEmpty(),
            includeHiddenFiles = prefs.getBoolean(KEY_INCLUDE_HIDDEN, true),
            chaosLevel = prefs.getInt(KEY_CHAOS_LEVEL, 8).coerceIn(1, 10),
            moodScore = prefs.getInt(KEY_MOOD_SCORE, 65).coerceIn(0, 100),
            minIntervalSec = prefs.getInt(KEY_MIN_INTERVAL, 22).coerceIn(8, 300),
            vibrateOnPrank = prefs.getBoolean(KEY_VIBRATE, true),
            faceCensorMode = FaceCensorMode.fromId(prefs.getString(KEY_FACE_CENSOR, FaceCensorMode.FACE_SWAP_SELF.id)),
            faceGenderFilter = GenderFilter.fromId(prefs.getString(KEY_FACE_GENDER, GenderFilter.ALL.id)),
            nsfwShieldEnabled = prefs.getBoolean(KEY_NSFW_SHIELD, true),
            askPermissionGate = prefs.getBoolean(KEY_ASK_PERM_GATE, true),
            appQuotaLockEnabled = prefs.getBoolean(KEY_APP_QUOTA_ENABLED, true),
            appQuotaMinutes = prefs.getInt(KEY_APP_QUOTA_MIN, 8).coerceIn(2, 60),
            negotiationMiniGames = prefs.getBoolean(KEY_NEGOTIATION, true),
            curfewEnabled = prefs.getBoolean(KEY_CURFEW_ENABLED, false),
            curfewStartHour = prefs.getInt(KEY_CURFEW_START, 22).coerceIn(0, 23),
            curfewEndHour = prefs.getInt(KEY_CURFEW_END, 7).coerceIn(0, 23),
            homeworkModeEnabled = prefs.getBoolean(KEY_HOMEWORK_MODE, false),
            antiDoomscrollEnabled = prefs.getBoolean(KEY_ANTI_DOOMSCROLL, true),
            settingsPanicEnabled = prefs.getBoolean(KEY_SETTINGS_PANIC, true),
            wallpaperPrankEnabled = prefs.getBoolean(KEY_WALLPAPER_PRANK, true),
            wallpaperTarget = WallpaperTarget.fromId(prefs.getString(KEY_WALLPAPER_TARGET, WallpaperTarget.BOTH.id)),
            draggedPopupEnabled = prefs.getBoolean(KEY_DRAGGED_POPUP, true),
            maxPopups = prefs.getInt(KEY_MAX_POPUPS, 2).coerceIn(1, 4),
            fullscreenFlashEnabled = prefs.getBoolean(KEY_FULLSCREEN_FLASH, true),
            flashDurationMs = prefs.getInt(KEY_FLASH_DURATION, 1800).coerceIn(500, 5000),
            nightBrightnessEnabled = prefs.getBoolean(KEY_NIGHT_BRIGHTNESS, true),
            lowBatteryPanicEnabled = prefs.getBoolean(KEY_LOW_BATTERY_PANIC, true),
            appCommentsEnabled = prefs.getBoolean(KEY_APP_COMMENTS, true),
            speedWarningEnabled = prefs.getBoolean(KEY_SPEED_WARNING, true),
            speedThresholdKmh = prefs.getInt(KEY_SPEED_THRESHOLD, 25).coerceIn(10, 90),
            customPhrases = prefs.getString(KEY_CUSTOM_PHRASES, "").orEmpty(),
            watchedPackages = HashSet(rawWatched),
            counterScrollEnabled = prefs.getBoolean(KEY_COUNTER_SCROLL, true),
            compulsiveBackEnabled = prefs.getBoolean(KEY_COMPULSIVE_BACK, true),
            forcePauseVideoEnabled = prefs.getBoolean(KEY_FORCE_PAUSE_VIDEO, true),
            clickStealShieldEnabled = prefs.getBoolean(KEY_CLICK_STEAL_SHIELD, true),
            keyboardSabotageEnabled = prefs.getBoolean(KEY_KEYBOARD_SABOTAGE, true),
            closeNotifShadeEnabled = prefs.getBoolean(KEY_CLOSE_NOTIF_SHADE, true),
            blockQuickSettingsEnabled = prefs.getBoolean(KEY_BLOCK_QS, true),
            antiLikeFollowEnabled = prefs.getBoolean(KEY_ANTI_LIKE_FOLLOW, true),
            timeoutCornerEnabled = prefs.getBoolean(KEY_TIMEOUT_CORNER, true),
            timeoutCornerSec = prefs.getInt(KEY_TIMEOUT_CORNER_SEC, 45).coerceIn(15, 300),
            punishmentLinesEnabled = prefs.getBoolean(KEY_PUNISHMENT_LINES, true),
            punishmentLinesCount = prefs.getInt(KEY_PUNISHMENT_LINES_COUNT, 3).coerceIn(2, 10),
            punishmentPhrase = prefs.getString(KEY_PUNISHMENT_PHRASE, DEFAULT_PUNISHMENT_PHRASE) ?: DEFAULT_PUNISHMENT_PHRASE,
            deadZoneKeyboardEnabled = prefs.getBoolean(KEY_DEAD_ZONE_KB, true),
            blacklistEnabled = prefs.getBoolean(KEY_BLACKLIST_ENABLED, true),
            blacklistPackages = HashSet(rawBlack),
            dodgeFingerEnabled = prefs.getBoolean(KEY_DODGE_FINGER, true),
            surpriseQuizEnabled = prefs.getBoolean(KEY_SURPRISE_QUIZ, true),
            forcedSlideshowEnabled = prefs.getBoolean(KEY_FORCED_SLIDESHOW, true)
        )
    }

    /** #65 — 4 Severity Presets in 1 tap */
    fun applySeverityPreset(preset: SeverityPreset) {
        update {
            putString(KEY_SEVERITY_PRESET, preset.id)
            when (preset) {
                SeverityPreset.GENTLE -> {
                    putInt(KEY_CHAOS_LEVEL, 3)
                    putInt(KEY_MIN_INTERVAL, 90)
                    putInt(KEY_MOOD_SCORE, 20)
                    putInt(KEY_APP_QUOTA_MIN, 20)
                    putInt(KEY_TIMEOUT_CORNER_SEC, 20)
                    putInt(KEY_PUNISHMENT_LINES_COUNT, 2)
                    putBoolean(KEY_COUNTER_SCROLL, false)
                    putBoolean(KEY_COMPULSIVE_BACK, false)
                    putBoolean(KEY_FORCE_PAUSE_VIDEO, false)
                    putBoolean(KEY_CLICK_STEAL_SHIELD, false)
                    putBoolean(KEY_KEYBOARD_SABOTAGE, false)
                    putBoolean(KEY_CLOSE_NOTIF_SHADE, false)
                    putBoolean(KEY_BLOCK_QS, false)
                    putBoolean(KEY_ANTI_LIKE_FOLLOW, false)
                    putBoolean(KEY_TIMEOUT_CORNER, false)
                    putBoolean(KEY_PUNISHMENT_LINES, false)
                    putBoolean(KEY_DEAD_ZONE_KB, false)
                    putBoolean(KEY_DODGE_FINGER, true)
                    putBoolean(KEY_SURPRISE_QUIZ, true)
                    putBoolean(KEY_FORCED_SLIDESHOW, false)
                }
                SeverityPreset.MOM -> {
                    putInt(KEY_CHAOS_LEVEL, 5)
                    putInt(KEY_MIN_INTERVAL, 55)
                    putInt(KEY_MOOD_SCORE, 45)
                    putInt(KEY_APP_QUOTA_MIN, 12)
                    putInt(KEY_TIMEOUT_CORNER_SEC, 30)
                    putInt(KEY_PUNISHMENT_LINES_COUNT, 3)
                    putBoolean(KEY_COUNTER_SCROLL, true)
                    putBoolean(KEY_COMPULSIVE_BACK, false)
                    putBoolean(KEY_FORCE_PAUSE_VIDEO, true)
                    putBoolean(KEY_CLICK_STEAL_SHIELD, false)
                    putBoolean(KEY_KEYBOARD_SABOTAGE, false)
                    putBoolean(KEY_CLOSE_NOTIF_SHADE, false)
                    putBoolean(KEY_BLOCK_QS, true)
                    putBoolean(KEY_ANTI_LIKE_FOLLOW, true)
                    putBoolean(KEY_TIMEOUT_CORNER, true)
                    putBoolean(KEY_PUNISHMENT_LINES, false)
                    putBoolean(KEY_DEAD_ZONE_KB, false)
                    putBoolean(KEY_DODGE_FINGER, true)
                    putBoolean(KEY_SURPRISE_QUIZ, true)
                    putBoolean(KEY_FORCED_SLIDESHOW, true)
                }
                SeverityPreset.STRICT -> {
                    putInt(KEY_CHAOS_LEVEL, 9)
                    putInt(KEY_MIN_INTERVAL, 18)
                    putInt(KEY_MOOD_SCORE, 78)
                    putInt(KEY_APP_QUOTA_MIN, 5)
                    putInt(KEY_TIMEOUT_CORNER_SEC, 45)
                    putInt(KEY_PUNISHMENT_LINES_COUNT, 4)
                    putBoolean(KEY_COUNTER_SCROLL, true)
                    putBoolean(KEY_COMPULSIVE_BACK, true)
                    putBoolean(KEY_FORCE_PAUSE_VIDEO, true)
                    putBoolean(KEY_CLICK_STEAL_SHIELD, true)
                    putBoolean(KEY_KEYBOARD_SABOTAGE, true)
                    putBoolean(KEY_CLOSE_NOTIF_SHADE, true)
                    putBoolean(KEY_BLOCK_QS, true)
                    putBoolean(KEY_ANTI_LIKE_FOLLOW, true)
                    putBoolean(KEY_TIMEOUT_CORNER, true)
                    putBoolean(KEY_PUNISHMENT_LINES, true)
                    putBoolean(KEY_DEAD_ZONE_KB, true)
                    putBoolean(KEY_BLACKLIST_ENABLED, true)
                    putBoolean(KEY_DODGE_FINGER, true)
                    putBoolean(KEY_SURPRISE_QUIZ, true)
                    putBoolean(KEY_FORCED_SLIDESHOW, true)
                    putBoolean(KEY_STRICT_SETTINGS_LOCK, true)
                }
                SeverityPreset.TYRANT -> {
                    putInt(KEY_CHAOS_LEVEL, 10)
                    putInt(KEY_MIN_INTERVAL, 10)
                    putInt(KEY_MOOD_SCORE, 96)
                    putInt(KEY_APP_QUOTA_MIN, 2)
                    putInt(KEY_TIMEOUT_CORNER_SEC, 90)
                    putInt(KEY_PUNISHMENT_LINES_COUNT, 6)
                    putBoolean(KEY_COUNTER_SCROLL, true)
                    putBoolean(KEY_COMPULSIVE_BACK, true)
                    putBoolean(KEY_FORCE_PAUSE_VIDEO, true)
                    putBoolean(KEY_CLICK_STEAL_SHIELD, true)
                    putBoolean(KEY_KEYBOARD_SABOTAGE, true)
                    putBoolean(KEY_CLOSE_NOTIF_SHADE, true)
                    putBoolean(KEY_BLOCK_QS, true)
                    putBoolean(KEY_ANTI_LIKE_FOLLOW, true)
                    putBoolean(KEY_TIMEOUT_CORNER, true)
                    putBoolean(KEY_PUNISHMENT_LINES, true)
                    putBoolean(KEY_DEAD_ZONE_KB, true)
                    putBoolean(KEY_BLACKLIST_ENABLED, true)
                    putBoolean(KEY_DODGE_FINGER, true)
                    putBoolean(KEY_SURPRISE_QUIZ, true)
                    putBoolean(KEY_FORCED_SLIDESHOW, true)
                    putBoolean(KEY_STRICT_SETTINGS_LOCK, true)
                    putBoolean(KEY_ASK_PERM_GATE, true)
                    putBoolean(KEY_APP_QUOTA_ENABLED, true)
                    putBoolean(KEY_ANTI_DOOMSCROLL, true)
                    putBoolean(KEY_WALLPAPER_PRANK, true)
                    putBoolean(KEY_DRAGGED_POPUP, true)
                    putBoolean(KEY_FULLSCREEN_FLASH, true)
                }
            }
        }
    }

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        val snap = snapshot()
        for (l in listeners) {
            try { l(snap) } catch (_: Throwable) {}
        }
    }

    fun adjustMood(delta: Int): Int {
        val next = (prefs.getInt(KEY_MOOD_SCORE, 55) + delta).coerceIn(0, 100)
        update { putInt(KEY_MOOD_SCORE, next) }
        return next
    }

    fun verifyExitPin(input: String): Boolean {
        val clean = input.trim()
        val configured = prefs.getString(KEY_EXIT_PIN, DEFAULT_EXIT_PIN) ?: DEFAULT_EXIT_PIN
        return clean == configured || clean == DEFAULT_EXIT_PIN
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = update { putBoolean(KEY_ENABLED, v) }

    var challengeLocked: Boolean
        get() = prefs.getBoolean(KEY_CHALLENGE_LOCKED, false)
        set(v) = update { putBoolean(KEY_CHALLENGE_LOCKED, v) }

    var strictSettingsLock: Boolean
        get() = prefs.getBoolean(KEY_STRICT_SETTINGS_LOCK, true)
        set(v) = update { putBoolean(KEY_STRICT_SETTINGS_LOCK, v) }

    var avatarPath: String
        get() = prefs.getString(KEY_AVATAR_PATH, "").orEmpty()
        set(v) = update { putString(KEY_AVATAR_PATH, v) }

    var avatarName: String
        get() = prefs.getString(KEY_AVATAR_NAME, "Maman Démon") ?: "Maman Démon"
        set(v) = update { putString(KEY_AVATAR_NAME, v.ifBlank { "Maman Démon" }) }

    var avatarStyle: AvatarStyle
        get() = AvatarStyle.fromId(prefs.getString(KEY_AVATAR_STYLE, AvatarStyle.REALISTIC_DEMON.id))
        set(v) = update { putString(KEY_AVATAR_STYLE, v.id) }

    var avatarSizeDp: Int
        get() = prefs.getInt(KEY_AVATAR_SIZE, 108).coerceIn(72, 156)
        set(v) = update { putInt(KEY_AVATAR_SIZE, v.coerceIn(72, 156)) }

    var cutoutIncludeHair: Boolean
        get() = prefs.getBoolean(KEY_CUTOUT_HAIR, true)
        set(v) = update { putBoolean(KEY_CUTOUT_HAIR, v) }

    var mediaFolderPath: String
        get() = prefs.getString(KEY_MEDIA_FOLDER, "").orEmpty()
        set(v) = update { putString(KEY_MEDIA_FOLDER, v); putString(KEY_MEDIA_TREE_URI, "") }

    var mediaTreeUri: String
        get() = prefs.getString(KEY_MEDIA_TREE_URI, "").orEmpty()
        set(v) = update { putString(KEY_MEDIA_TREE_URI, v) }

    var includeHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_HIDDEN, true)
        set(v) = update { putBoolean(KEY_INCLUDE_HIDDEN, v) }

    var chaosLevel: Int
        get() = prefs.getInt(KEY_CHAOS_LEVEL, 7).coerceIn(1, 10)
        set(v) = update { putInt(KEY_CHAOS_LEVEL, v.coerceIn(1, 10)) }

    var minIntervalSec: Int
        get() = prefs.getInt(KEY_MIN_INTERVAL, 22).coerceIn(8, 300)
        set(v) = update { putInt(KEY_MIN_INTERVAL, v.coerceIn(8, 300)) }

    var vibrateOnPrank: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(v) = update { putBoolean(KEY_VIBRATE, v) }

    var faceCensorMode: FaceCensorMode
        get() = FaceCensorMode.fromId(prefs.getString(KEY_FACE_CENSOR, FaceCensorMode.FACE_SWAP_SELF.id))
        set(v) = update { putString(KEY_FACE_CENSOR, v.id) }

    var faceGenderFilter: GenderFilter
        get() = GenderFilter.fromId(prefs.getString(KEY_FACE_GENDER, GenderFilter.ALL.id))
        set(v) = update { putString(KEY_FACE_GENDER, v.id) }

    var nsfwShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_NSFW_SHIELD, true)
        set(v) = update { putBoolean(KEY_NSFW_SHIELD, v) }

    var askPermissionGate: Boolean
        get() = prefs.getBoolean(KEY_ASK_PERM_GATE, true)
        set(v) = update { putBoolean(KEY_ASK_PERM_GATE, v) }

    var appQuotaLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_QUOTA_ENABLED, true)
        set(v) = update { putBoolean(KEY_APP_QUOTA_ENABLED, v) }

    var appQuotaMinutes: Int
        get() = prefs.getInt(KEY_APP_QUOTA_MIN, 8).coerceIn(2, 60)
        set(v) = update { putInt(KEY_APP_QUOTA_MIN, v.coerceIn(2, 60)) }

    var negotiationMiniGames: Boolean
        get() = prefs.getBoolean(KEY_NEGOTIATION, true)
        set(v) = update { putBoolean(KEY_NEGOTIATION, v) }

    var curfewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURFEW_ENABLED, false)
        set(v) = update { putBoolean(KEY_CURFEW_ENABLED, v) }

    var curfewStartHour: Int
        get() = prefs.getInt(KEY_CURFEW_START, 22).coerceIn(0, 23)
        set(v) = update { putInt(KEY_CURFEW_START, v.coerceIn(0, 23)) }

    var curfewEndHour: Int
        get() = prefs.getInt(KEY_CURFEW_END, 7).coerceIn(0, 23)
        set(v) = update { putInt(KEY_CURFEW_END, v.coerceIn(0, 23)) }

    var homeworkModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOMEWORK_MODE, false)
        set(v) = update { putBoolean(KEY_HOMEWORK_MODE, v) }

    var antiDoomscrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_DOOMSCROLL, true)
        set(v) = update { putBoolean(KEY_ANTI_DOOMSCROLL, v) }

    var settingsPanicEnabled: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_PANIC, true)
        set(v) = update { putBoolean(KEY_SETTINGS_PANIC, v) }

    var wallpaperPrankEnabled: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_PRANK, true)
        set(v) = update { putBoolean(KEY_WALLPAPER_PRANK, v) }

    var wallpaperTarget: WallpaperTarget
        get() = WallpaperTarget.fromId(prefs.getString(KEY_WALLPAPER_TARGET, WallpaperTarget.BOTH.id))
        set(v) = update { putString(KEY_WALLPAPER_TARGET, v.id) }

    var draggedPopupEnabled: Boolean
        get() = prefs.getBoolean(KEY_DRAGGED_POPUP, true)
        set(v) = update { putBoolean(KEY_DRAGGED_POPUP, v) }

    var maxPopups: Int
        get() = prefs.getInt(KEY_MAX_POPUPS, 2).coerceIn(1, 4)
        set(v) = update { putInt(KEY_MAX_POPUPS, v.coerceIn(1, 4)) }

    var fullscreenFlashEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN_FLASH, true)
        set(v) = update { putBoolean(KEY_FULLSCREEN_FLASH, v) }

    var flashDurationMs: Int
        get() = prefs.getInt(KEY_FLASH_DURATION, 1800).coerceIn(500, 5000)
        set(v) = update { putInt(KEY_FLASH_DURATION, v.coerceIn(500, 5000)) }

    var nightBrightnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_BRIGHTNESS, true)
        set(v) = update { putBoolean(KEY_NIGHT_BRIGHTNESS, v) }

    var lowBatteryPanicEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOW_BATTERY_PANIC, true)
        set(v) = update { putBoolean(KEY_LOW_BATTERY_PANIC, v) }

    var appCommentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_COMMENTS, true)
        set(v) = update { putBoolean(KEY_APP_COMMENTS, v) }

    var speedWarningEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_WARNING, true)
        set(v) = update { putBoolean(KEY_SPEED_WARNING, v) }

    var speedThresholdKmh: Int
        get() = prefs.getInt(KEY_SPEED_THRESHOLD, 25).coerceIn(10, 90)
        set(v) = update { putInt(KEY_SPEED_THRESHOLD, v.coerceIn(10, 90)) }

    var customPhrases: String
        get() = prefs.getString(KEY_CUSTOM_PHRASES, "").orEmpty()
        set(v) = update { putString(KEY_CUSTOM_PHRASES, v) }

    var watchedPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_WATCHED_PKGS, DEFAULT_WATCHED_PKGS) ?: DEFAULT_WATCHED_PKGS)
        set(v) = update { putStringSet(KEY_WATCHED_PKGS, HashSet(v)) }

    // v2.0 properties
    var counterScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_COUNTER_SCROLL, true)
        set(v) = update { putBoolean(KEY_COUNTER_SCROLL, v) }

    var compulsiveBackEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPULSIVE_BACK, true)
        set(v) = update { putBoolean(KEY_COMPULSIVE_BACK, v) }

    var forcePauseVideoEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORCE_PAUSE_VIDEO, true)
        set(v) = update { putBoolean(KEY_FORCE_PAUSE_VIDEO, v) }

    var clickStealShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLICK_STEAL_SHIELD, true)
        set(v) = update { putBoolean(KEY_CLICK_STEAL_SHIELD, v) }

    var keyboardSabotageEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_SABOTAGE, true)
        set(v) = update { putBoolean(KEY_KEYBOARD_SABOTAGE, v) }

    var closeNotifShadeEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOSE_NOTIF_SHADE, true)
        set(v) = update { putBoolean(KEY_CLOSE_NOTIF_SHADE, v) }

    var blockQuickSettingsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_QS, true)
        set(v) = update { putBoolean(KEY_BLOCK_QS, v) }

    var antiLikeFollowEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_LIKE_FOLLOW, true)
        set(v) = update { putBoolean(KEY_ANTI_LIKE_FOLLOW, v) }

    var timeoutCornerEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIMEOUT_CORNER, true)
        set(v) = update { putBoolean(KEY_TIMEOUT_CORNER, v) }

    var timeoutCornerSec: Int
        get() = prefs.getInt(KEY_TIMEOUT_CORNER_SEC, 45).coerceIn(15, 300)
        set(v) = update { putInt(KEY_TIMEOUT_CORNER_SEC, v.coerceIn(15, 300)) }

    var punishmentLinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUNISHMENT_LINES, true)
        set(v) = update { putBoolean(KEY_PUNISHMENT_LINES, v) }

    var punishmentLinesCount: Int
        get() = prefs.getInt(KEY_PUNISHMENT_LINES_COUNT, 3).coerceIn(2, 10)
        set(v) = update { putInt(KEY_PUNISHMENT_LINES_COUNT, v.coerceIn(2, 10)) }

    var punishmentPhrase: String
        get() = prefs.getString(KEY_PUNISHMENT_PHRASE, DEFAULT_PUNISHMENT_PHRASE) ?: DEFAULT_PUNISHMENT_PHRASE
        set(v) = update { putString(KEY_PUNISHMENT_PHRASE, v.ifBlank { DEFAULT_PUNISHMENT_PHRASE }) }

    var deadZoneKeyboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEAD_ZONE_KB, true)
        set(v) = update { putBoolean(KEY_DEAD_ZONE_KB, v) }

    var blacklistEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLACKLIST_ENABLED, true)
        set(v) = update { putBoolean(KEY_BLACKLIST_ENABLED, v) }

    var blacklistPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_BLACKLIST_PKGS, emptySet()) ?: emptySet())
        set(v) = update { putStringSet(KEY_BLACKLIST_PKGS, HashSet(v)) }

    var dodgeFingerEnabled: Boolean
        get() = prefs.getBoolean(KEY_DODGE_FINGER, true)
        set(v) = update { putBoolean(KEY_DODGE_FINGER, v) }

    var surpriseQuizEnabled: Boolean
        get() = prefs.getBoolean(KEY_SURPRISE_QUIZ, true)
        set(v) = update { putBoolean(KEY_SURPRISE_QUIZ, v) }

    var forcedSlideshowEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORCED_SLIDESHOW, true)
        set(v) = update { putBoolean(KEY_FORCED_SLIDESHOW, v) }

    companion object {
        const val DEFAULT_EXIT_PIN = "100205"
        const val REQUIRED_EXIT_TAPS = 20
        const val DEFAULT_PUNISHMENT_PHRASE = "Je promets d'obeir a Maman Demon"

        private const val PREFS_NAME = "demonmom_prefs_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CHALLENGE_LOCKED = "challenge_locked"
        private const val KEY_STRICT_SETTINGS_LOCK = "strict_settings_lock"
        private const val KEY_EXIT_PIN = "exit_pin"
        private const val KEY_SEVERITY_PRESET = "severity_preset"
        private const val KEY_AVATAR_PATH = "avatar_path"
        private const val KEY_AVATAR_NAME = "avatar_name"
        private const val KEY_AVATAR_STYLE = "avatar_style"
        private const val KEY_AVATAR_SIZE = "avatar_size"
        private const val KEY_CUTOUT_HAIR = "cutout_hair"
        private const val KEY_MEDIA_FOLDER = "media_folder"
        private const val KEY_MEDIA_TREE_URI = "media_tree_uri"
        private const val KEY_INCLUDE_HIDDEN = "include_hidden"
        private const val KEY_CHAOS_LEVEL = "chaos_level"
        private const val KEY_MOOD_SCORE = "mood_score"
        private const val KEY_MIN_INTERVAL = "min_interval"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_FACE_CENSOR = "face_censor"
        private const val KEY_FACE_GENDER = "face_gender"
        private const val KEY_NSFW_SHIELD = "nsfw_shield"
        private const val KEY_ASK_PERM_GATE = "ask_perm_gate"
        private const val KEY_APP_QUOTA_ENABLED = "app_quota_enabled"
        private const val KEY_APP_QUOTA_MIN = "app_quota_min"
        private const val KEY_NEGOTIATION = "negotiation"
        private const val KEY_CURFEW_ENABLED = "curfew_enabled"
        private const val KEY_CURFEW_START = "curfew_start"
        private const val KEY_CURFEW_END = "curfew_end"
        private const val KEY_HOMEWORK_MODE = "homework_mode"
        private const val KEY_ANTI_DOOMSCROLL = "anti_doomscroll"
        private const val KEY_SETTINGS_PANIC = "settings_panic"
        private const val KEY_WALLPAPER_PRANK = "wallpaper_prank"
        private const val KEY_WALLPAPER_TARGET = "wallpaper_target"
        private const val KEY_DRAGGED_POPUP = "dragged_popup"
        private const val KEY_MAX_POPUPS = "max_popups"
        private const val KEY_FULLSCREEN_FLASH = "fullscreen_flash"
        private const val KEY_FLASH_DURATION = "flash_duration"
        private const val KEY_NIGHT_BRIGHTNESS = "night_brightness"
        private const val KEY_LOW_BATTERY_PANIC = "low_battery_panic"
        private const val KEY_APP_COMMENTS = "app_comments"
        private const val KEY_SPEED_WARNING = "speed_warning"
        private const val KEY_SPEED_THRESHOLD = "speed_threshold"
        private const val KEY_CUSTOM_PHRASES = "custom_phrases"
        private const val KEY_WATCHED_PKGS = "watched_pkgs"

        private const val KEY_COUNTER_SCROLL = "counter_scroll"
        private const val KEY_COMPULSIVE_BACK = "compulsive_back"
        private const val KEY_FORCE_PAUSE_VIDEO = "force_pause_video"
        private const val KEY_CLICK_STEAL_SHIELD = "click_steal_shield"
        private const val KEY_KEYBOARD_SABOTAGE = "keyboard_sabotage"
        private const val KEY_CLOSE_NOTIF_SHADE = "close_notif_shade"
        private const val KEY_BLOCK_QS = "block_quick_settings"
        private const val KEY_ANTI_LIKE_FOLLOW = "anti_like_follow"
        private const val KEY_TIMEOUT_CORNER = "timeout_corner"
        private const val KEY_TIMEOUT_CORNER_SEC = "timeout_corner_sec"
        private const val KEY_PUNISHMENT_LINES = "punishment_lines"
        private const val KEY_PUNISHMENT_LINES_COUNT = "punishment_lines_count"
        private const val KEY_PUNISHMENT_PHRASE = "punishment_phrase"
        private const val KEY_DEAD_ZONE_KB = "dead_zone_kb"
        private const val KEY_BLACKLIST_ENABLED = "blacklist_enabled"
        private const val KEY_BLACKLIST_PKGS = "blacklist_pkgs"
        private const val KEY_DODGE_FINGER = "dodge_finger"
        private const val KEY_SURPRISE_QUIZ = "surprise_quiz"
        private const val KEY_FORCED_SLIDESHOW = "forced_slideshow"

        val DEFAULT_WATCHED_PKGS = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.instagram.android",
            "com.snapchat.android",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "tv.twitch.android.app",
            "com.discord",
            "com.twitter.android",
            "com.supercell.brawlstars",
            "com.roblox.client"
        )

        private val listeners = CopyOnWriteArraySet<(DemonConfigSnapshot) -> Unit>()
        fun addListener(l: (DemonConfigSnapshot) -> Unit) { listeners.add(l) }
        fun removeListener(l: (DemonConfigSnapshot) -> Unit) { listeners.remove(l) }
    }
}
