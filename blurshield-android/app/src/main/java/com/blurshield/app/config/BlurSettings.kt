package com.blurshield.app.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class BlurStyle(val id: String, val label: String) {
    GAUSSIAN("gaussian", "Flou Gaussien"),
    MOSAIC("mosaic", "Mosaïque Pixel"),
    FROSTED("frosted", "Verre dépoli"),
    SOLID_COLOR("solid_color", "Teinte unie"),
    SOLID_BLACK("solid_black", "Masque noir");

    companion object {
        fun fromId(id: String?): BlurStyle = values().firstOrNull { it.id == id } ?: GAUSSIAN
    }
}

enum class MaskShape(val id: String, val label: String) {
    ORGANIC("organic", "Contour visage"),
    OVAL("oval", "Ovale doux"),
    ROUNDED_RECT("rounded", "Rectangle arrondi");

    companion object {
        fun fromId(id: String?): MaskShape = values().firstOrNull { it.id == id } ?: ORGANIC
    }
}

enum class FaceGenderMode(val id: String, val label: String) {
    ALL("all", "Tous"),
    FEMALE_ONLY("female", "Femmes"),
    MALE_ONLY("male", "Hommes");

    companion object {
        fun fromId(id: String?): FaceGenderMode = values().firstOrNull { it.id == id } ?: ALL
    }
}

enum class SensitivityMode(val id: String, val label: String, val explicitThresh: Float, val strictThresh: Float, val faceThresh: Float) {
    PRECISE("precise", "Précis", 0.40f, 0.48f, 0.44f),
    BALANCED("balanced", "Équilibré", 0.27f, 0.36f, 0.34f),
    ULTRA("ultra", "Ultra", 0.18f, 0.26f, 0.25f);

    companion object {
        fun fromId(id: String?): SensitivityMode = values().firstOrNull { it.id == id } ?: BALANCED
    }
}

enum class SmoothnessMode(val id: String, val label: String, val posLerp: Float, val alphaIn: Float, val alphaOut: Float, val holdGraceMs: Long) {
    INSTANT("instant", "Instantané", 0.72f, 0.65f, 0.45f, 140L),
    FLUID("fluid", "Fluide 120Hz", 0.46f, 0.42f, 0.26f, 260L),
    CINEMA("cinema", "Cinéma doux", 0.28f, 0.28f, 0.16f, 420L);

    companion object {
        fun fromId(id: String?): SmoothnessMode = values().firstOrNull { it.id == id } ?: FLUID
    }
}

enum class AppFilterMode(val id: String, val label: String) {
    ALL_EXCEPT_EXCLUDED("exclude", "Toutes sauf exclusions"),
    ONLY_SELECTED("only", "Uniquement sélectionnées");

    companion object {
        fun fromId(id: String?): AppFilterMode = values().firstOrNull { it.id == id } ?: ALL_EXCEPT_EXCLUDED
    }
}

data class BlurConfigSnapshot(
    val enabled: Boolean,
    val blurFaces: Boolean,
    val faceGenderMode: FaceGenderMode,
    val blurGenitalia: Boolean,
    val blurBreasts: Boolean,
    val blurButtocks: Boolean,
    val blurBelly: Boolean,
    val blurCoveredSensitive: Boolean,
    val blurStyle: BlurStyle,
    val maskShape: MaskShape,
    val blurIntensity: Int,       // 1..10
    val maskPaddingPct: Int,      // 0..45
    val smoothnessMode: SmoothnessMode,
    val sensitivityMode: SensitivityMode,
    val roiTopPct: Int,           // 0..40
    val roiBottomPct: Int,        // 60..100
    val excludeSystemBars: Boolean,
    val ecoStaticSkip: Boolean,
    val showFloatingPill: Boolean,
    val showPerfHud: Boolean,
    val appFilterMode: AppFilterMode,
    val selectedPackages: Set<String>
) {
    val anyNsfwEnabled: Boolean
        get() = blurGenitalia || blurBreasts || blurButtocks || blurBelly

    val anyDetectionEnabled: Boolean
        get() = blurFaces || anyNsfwEnabled

    fun isPackageAllowedForBlur(pkg: String?, selfPackage: String): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == selfPackage) return false
        return when (appFilterMode) {
            AppFilterMode.ALL_EXCEPT_EXCLUDED -> !selectedPackages.contains(pkg)
            AppFilterMode.ONLY_SELECTED -> selectedPackages.contains(pkg)
        }
    }
}

class BlurSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): BlurConfigSnapshot {
        val rawPkgs = prefs.getStringSet(KEY_SELECTED_PKGS, emptySet()) ?: emptySet()
        return BlurConfigSnapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            blurFaces = prefs.getBoolean(KEY_BLUR_FACES, true),
            faceGenderMode = FaceGenderMode.fromId(prefs.getString(KEY_FACE_GENDER, FaceGenderMode.ALL.id)),
            blurGenitalia = prefs.getBoolean(KEY_BLUR_GENITALIA, true),
            blurBreasts = prefs.getBoolean(KEY_BLUR_BREASTS, true),
            blurButtocks = prefs.getBoolean(KEY_BLUR_BUTTOCKS, true),
            blurBelly = prefs.getBoolean(KEY_BLUR_BELLY, false),
            blurCoveredSensitive = prefs.getBoolean(KEY_BLUR_COVERED, false),
            blurStyle = BlurStyle.fromId(prefs.getString(KEY_BLUR_STYLE, BlurStyle.GAUSSIAN.id)),
            maskShape = MaskShape.fromId(prefs.getString(KEY_MASK_SHAPE, MaskShape.ORGANIC.id)),
            blurIntensity = prefs.getInt(KEY_BLUR_INTENSITY, 7).coerceIn(1, 10),
            maskPaddingPct = prefs.getInt(KEY_MASK_PADDING, 14).coerceIn(0, 45),
            smoothnessMode = SmoothnessMode.fromId(prefs.getString(KEY_SMOOTHNESS, SmoothnessMode.FLUID.id)),
            sensitivityMode = SensitivityMode.fromId(prefs.getString(KEY_SENSITIVITY, SensitivityMode.BALANCED.id)),
            roiTopPct = prefs.getInt(KEY_ROI_TOP, 0).coerceIn(0, 40),
            roiBottomPct = prefs.getInt(KEY_ROI_BOTTOM, 100).coerceIn(60, 100),
            excludeSystemBars = prefs.getBoolean(KEY_EXCLUDE_SYS_BARS, true),
            ecoStaticSkip = prefs.getBoolean(KEY_ECO_STATIC, true),
            showFloatingPill = prefs.getBoolean(KEY_FLOATING_PILL, false),
            showPerfHud = prefs.getBoolean(KEY_PERF_HUD, false),
            appFilterMode = AppFilterMode.fromId(prefs.getString(KEY_APP_FILTER_MODE, AppFilterMode.ALL_EXCEPT_EXCLUDED.id)),
            selectedPackages = HashSet(rawPkgs)
        )
    }

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        val snap = snapshot()
        for (l in listeners) {
            try { l(snap) } catch (_: Throwable) {}
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = update { putBoolean(KEY_ENABLED, v) }

    var blurFaces: Boolean
        get() = prefs.getBoolean(KEY_BLUR_FACES, true)
        set(v) = update { putBoolean(KEY_BLUR_FACES, v) }

    var faceGenderMode: FaceGenderMode
        get() = FaceGenderMode.fromId(prefs.getString(KEY_FACE_GENDER, FaceGenderMode.ALL.id))
        set(v) = update { putString(KEY_FACE_GENDER, v.id) }

    var blurGenitalia: Boolean
        get() = prefs.getBoolean(KEY_BLUR_GENITALIA, true)
        set(v) = update { putBoolean(KEY_BLUR_GENITALIA, v) }

    var blurBreasts: Boolean
        get() = prefs.getBoolean(KEY_BLUR_BREASTS, true)
        set(v) = update { putBoolean(KEY_BLUR_BREASTS, v) }

    var blurButtocks: Boolean
        get() = prefs.getBoolean(KEY_BLUR_BUTTOCKS, true)
        set(v) = update { putBoolean(KEY_BLUR_BUTTOCKS, v) }

    var blurBelly: Boolean
        get() = prefs.getBoolean(KEY_BLUR_BELLY, false)
        set(v) = update { putBoolean(KEY_BLUR_BELLY, v) }

    var blurCoveredSensitive: Boolean
        get() = prefs.getBoolean(KEY_BLUR_COVERED, false)
        set(v) = update { putBoolean(KEY_BLUR_COVERED, v) }

    var blurStyle: BlurStyle
        get() = BlurStyle.fromId(prefs.getString(KEY_BLUR_STYLE, BlurStyle.GAUSSIAN.id))
        set(v) = update { putString(KEY_BLUR_STYLE, v.id) }

    var maskShape: MaskShape
        get() = MaskShape.fromId(prefs.getString(KEY_MASK_SHAPE, MaskShape.ORGANIC.id))
        set(v) = update { putString(KEY_MASK_SHAPE, v.id) }

    var blurIntensity: Int
        get() = prefs.getInt(KEY_BLUR_INTENSITY, 7).coerceIn(1, 10)
        set(v) = update { putInt(KEY_BLUR_INTENSITY, v.coerceIn(1, 10)) }

    var maskPaddingPct: Int
        get() = prefs.getInt(KEY_MASK_PADDING, 14).coerceIn(0, 45)
        set(v) = update { putInt(KEY_MASK_PADDING, v.coerceIn(0, 45)) }

    var smoothnessMode: SmoothnessMode
        get() = SmoothnessMode.fromId(prefs.getString(KEY_SMOOTHNESS, SmoothnessMode.FLUID.id))
        set(v) = update { putString(KEY_SMOOTHNESS, v.id) }

    var sensitivityMode: SensitivityMode
        get() = SensitivityMode.fromId(prefs.getString(KEY_SENSITIVITY, SensitivityMode.BALANCED.id))
        set(v) = update { putString(KEY_SENSITIVITY, v.id) }

    var roiTopPct: Int
        get() = prefs.getInt(KEY_ROI_TOP, 0).coerceIn(0, 40)
        set(v) = update { putInt(KEY_ROI_TOP, v.coerceIn(0, 40)) }

    var roiBottomPct: Int
        get() = prefs.getInt(KEY_ROI_BOTTOM, 100).coerceIn(60, 100)
        set(v) = update { putInt(KEY_ROI_BOTTOM, v.coerceIn(60, 100)) }

    var excludeSystemBars: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_SYS_BARS, true)
        set(v) = update { putBoolean(KEY_EXCLUDE_SYS_BARS, v) }

    var ecoStaticSkip: Boolean
        get() = prefs.getBoolean(KEY_ECO_STATIC, true)
        set(v) = update { putBoolean(KEY_ECO_STATIC, v) }

    var showFloatingPill: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL, false)
        set(v) = update { putBoolean(KEY_FLOATING_PILL, v) }

    var showPerfHud: Boolean
        get() = prefs.getBoolean(KEY_PERF_HUD, false)
        set(v) = update { putBoolean(KEY_PERF_HUD, v) }

    var appFilterMode: AppFilterMode
        get() = AppFilterMode.fromId(prefs.getString(KEY_APP_FILTER_MODE, AppFilterMode.ALL_EXCEPT_EXCLUDED.id))
        set(v) = update { putString(KEY_APP_FILTER_MODE, v.id) }

    var selectedPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_SELECTED_PKGS, emptySet()) ?: emptySet())
        set(v) = update { putStringSet(KEY_SELECTED_PKGS, HashSet(v)) }

    companion object {
        private const val PREFS_NAME = "blurshield_pro_prefs_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BLUR_FACES = "blur_faces"
        private const val KEY_FACE_GENDER = "face_gender"
        private const val KEY_BLUR_GENITALIA = "blur_genitalia"
        private const val KEY_BLUR_BREASTS = "blur_breasts"
        private const val KEY_BLUR_BUTTOCKS = "blur_buttocks"
        private const val KEY_BLUR_BELLY = "blur_belly"
        private const val KEY_BLUR_COVERED = "blur_covered"
        private const val KEY_BLUR_STYLE = "blur_style"
        private const val KEY_MASK_SHAPE = "mask_shape"
        private const val KEY_BLUR_INTENSITY = "blur_intensity"
        private const val KEY_MASK_PADDING = "mask_padding"
        private const val KEY_SMOOTHNESS = "smoothness"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ROI_TOP = "roi_top"
        private const val KEY_ROI_BOTTOM = "roi_bottom"
        private const val KEY_EXCLUDE_SYS_BARS = "exclude_sys_bars"
        private const val KEY_ECO_STATIC = "eco_static"
        private const val KEY_FLOATING_PILL = "floating_pill"
        private const val KEY_PERF_HUD = "perf_hud"
        private const val KEY_APP_FILTER_MODE = "app_filter_mode"
        private const val KEY_SELECTED_PKGS = "selected_pkgs"

        private val listeners = CopyOnWriteArraySet<(BlurConfigSnapshot) -> Unit>()

        fun addListener(listener: (BlurConfigSnapshot) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (BlurConfigSnapshot) -> Unit) {
            listeners.remove(listener)
        }
    }
}
