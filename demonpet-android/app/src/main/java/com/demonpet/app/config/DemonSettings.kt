package com.demonpet.app.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class AvatarStyle(val id: String, val label: String) {
    DEMON("demon", "Petit Démon 😈"),
    OVERPROTECTIVE_MOM("mom", "Maman Poule 🤱"),
    CHIBI("chibi", "Chibi Cute ✨"),
    FLOATING_HEAD("head", "Tête pure 👻");

    companion object {
        fun fromId(id: String?): AvatarStyle = values().firstOrNull { it.id == id } ?: DEMON
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
    ANGEL("Ange calme", "😇"),
    MOM("Maman poule", "🤱"),
    JEALOUS("Jalouse", "👀"),
    FURIOUS("Démon furieux", "😈");

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
    val exitPin: String,
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
    val faceCensorMode: FaceCensorMode,
    val faceGenderFilter: GenderFilter,
    val nsfwShieldEnabled: Boolean,
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
    val watchedPackages: Set<String>
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
        return DemonConfigSnapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            challengeLocked = prefs.getBoolean(KEY_CHALLENGE_LOCKED, false),
            exitPin = prefs.getString(KEY_EXIT_PIN, DEFAULT_EXIT_PIN) ?: DEFAULT_EXIT_PIN,
            avatarPath = prefs.getString(KEY_AVATAR_PATH, "").orEmpty(),
            avatarName = prefs.getString(KEY_AVATAR_NAME, "Maman Démon") ?: "Maman Démon",
            avatarStyle = AvatarStyle.fromId(prefs.getString(KEY_AVATAR_STYLE, AvatarStyle.DEMON.id)),
            avatarSizeDp = prefs.getInt(KEY_AVATAR_SIZE, 92).coerceIn(64, 136),
            cutoutIncludeHair = prefs.getBoolean(KEY_CUTOUT_HAIR, true),
            mediaFolderPath = prefs.getString(KEY_MEDIA_FOLDER, "").orEmpty(),
            mediaTreeUri = prefs.getString(KEY_MEDIA_TREE_URI, "").orEmpty(),
            includeHiddenFiles = prefs.getBoolean(KEY_INCLUDE_HIDDEN, true),
            chaosLevel = prefs.getInt(KEY_CHAOS_LEVEL, 6).coerceIn(1, 10),
            moodScore = prefs.getInt(KEY_MOOD_SCORE, 45).coerceIn(0, 100),
            minIntervalSec = prefs.getInt(KEY_MIN_INTERVAL, 45).coerceIn(15, 300),
            vibrateOnPrank = prefs.getBoolean(KEY_VIBRATE, true),
            faceCensorMode = FaceCensorMode.fromId(prefs.getString(KEY_FACE_CENSOR, FaceCensorMode.FACE_SWAP_SELF.id)),
            faceGenderFilter = GenderFilter.fromId(prefs.getString(KEY_FACE_GENDER, GenderFilter.ALL.id)),
            nsfwShieldEnabled = prefs.getBoolean(KEY_NSFW_SHIELD, true),
            askPermissionGate = prefs.getBoolean(KEY_ASK_PERM_GATE, true),
            appQuotaLockEnabled = prefs.getBoolean(KEY_APP_QUOTA_ENABLED, true),
            appQuotaMinutes = prefs.getInt(KEY_APP_QUOTA_MIN, 10).coerceIn(2, 60),
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
            watchedPackages = HashSet(rawWatched)
        )
    }

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        val snap = snapshot()
        for (l in listeners) {
            try { l(snap) } catch (_: Throwable) {}
        }
    }

    fun adjustMood(delta: Int): Int {
        val next = (prefs.getInt(KEY_MOOD_SCORE, 45) + delta).coerceIn(0, 100)
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

    var avatarPath: String
        get() = prefs.getString(KEY_AVATAR_PATH, "").orEmpty()
        set(v) = update { putString(KEY_AVATAR_PATH, v) }

    var avatarName: String
        get() = prefs.getString(KEY_AVATAR_NAME, "Maman Démon") ?: "Maman Démon"
        set(v) = update { putString(KEY_AVATAR_NAME, v.ifBlank { "Maman Démon" }) }

    var avatarStyle: AvatarStyle
        get() = AvatarStyle.fromId(prefs.getString(KEY_AVATAR_STYLE, AvatarStyle.DEMON.id))
        set(v) = update { putString(KEY_AVATAR_STYLE, v.id) }

    var avatarSizeDp: Int
        get() = prefs.getInt(KEY_AVATAR_SIZE, 92).coerceIn(64, 136)
        set(v) = update { putInt(KEY_AVATAR_SIZE, v.coerceIn(64, 136)) }

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
        get() = prefs.getInt(KEY_CHAOS_LEVEL, 6).coerceIn(1, 10)
        set(v) = update { putInt(KEY_CHAOS_LEVEL, v.coerceIn(1, 10)) }

    var minIntervalSec: Int
        get() = prefs.getInt(KEY_MIN_INTERVAL, 45).coerceIn(15, 300)
        set(v) = update { putInt(KEY_MIN_INTERVAL, v.coerceIn(15, 300)) }

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
        get() = prefs.getInt(KEY_APP_QUOTA_MIN, 10).coerceIn(2, 60)
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

    companion object {
        const val DEFAULT_EXIT_PIN = "100205"
        const val REQUIRED_EXIT_TAPS = 20

        private const val PREFS_NAME = "demonmom_prefs_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CHALLENGE_LOCKED = "challenge_locked"
        private const val KEY_EXIT_PIN = "exit_pin"
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
