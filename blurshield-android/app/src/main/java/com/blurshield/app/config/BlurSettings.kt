package com.blurshield.app.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class BlurStyle(val id: String, val label: String, val isGpuShader: Boolean = false, val isAnimated: Boolean = false) {
    GAUSSIAN("gaussian", "Gaussien"),
    MOSAIC("mosaic", "Mosaïque"),
    FROSTED("frosted", "Verre dépoli"),
    SOLID_COLOR("solid_color", "Caméléon"),
    SOLID_BLACK("solid_black", "Noir opaque"),
    GPU_BOKEH("gpu_bokeh", "Bokeh GPU", isGpuShader = true),
    GPU_FLUTED_GLASS("gpu_fluted", "Verre strié", isGpuShader = true),
    GPU_LIQUID_RIPPLE("gpu_liquid", "Liquide GPU", isGpuShader = true, isAnimated = true),
    GPU_VORONOI_CRYSTAL("gpu_voronoi", "Cristal GPU", isGpuShader = true),
    GPU_CYBER_GLITCH("gpu_glitch", "Glitch GPU", isGpuShader = true, isAnimated = true),
    EMOJI_REACTIVE("emoji_reactive", "Emoji réactif"),
    CUSTOM_IMAGE("custom_image", "Image perso");

    companion object {
        fun fromId(id: String?): BlurStyle = values().firstOrNull { it.id == id } ?: GAUSSIAN
    }
}

enum class MaskShape(val id: String, val label: String) {
    SILHOUETTE("silhouette", "Silhouette IA"),
    ORGANIC("organic", "Contour visage"),
    OVAL("oval", "Ovale 3D"),
    ROUNDED_RECT("rounded", "Rectangle 3D");

    companion object {
        fun fromId(id: String?): MaskShape = values().firstOrNull { it.id == id } ?: ORGANIC
    }
}

enum class FaceSubZone(val id: String, val label: String) {
    FULL_FACE("full", "Visage complet"),
    EYES_ONLY("eyes", "Yeux seuls"),
    MOUTH_ONLY("mouth", "Bouche seule"),
    UPPER_FACE("upper", "Haut visage"),
    EYES_AND_MOUTH("eyes_mouth", "Yeux + Bouche"),
    HAIR_AND_HEAD("head_hair", "Tête + Cheveux");

    companion object {
        fun fromId(id: String?): FaceSubZone = values().firstOrNull { it.id == id } ?: FULL_FACE
    }
}

enum class EmojiPack(val id: String, val label: String, val baseEmoji: String, val isReactive: Boolean = false) {
    AUTO_REACTIVE("auto", "Auto 😄😉", "😎", isReactive = true),
    COOL("cool", "😎 Cool", "😎"),
    ROBOT("robot", "🤖 Robot", "🤖"),
    CAT("cat", "🐱 Chat", "🐱"),
    FOX("fox", "🦊 Renard", "🦊"),
    ALIEN("alien", "👾 Pixel", "👾"),
    SHIELD("shield", "🛡️ Bouclier", "🛡️");

    companion object {
        fun fromId(id: String?): EmojiPack = values().firstOrNull { it.id == id } ?: AUTO_REACTIVE
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

enum class SmoothnessMode(
    val id: String,
    val label: String,
    val posLerp: Float,
    val alphaIn: Float,
    val alphaOut: Float,
    val holdGraceMs: Long,
    val velGain: Float
) {
    ZERO_LAG_KALMAN("kalman", "Kalman 0-Lag", 0.62f, 0.56f, 0.30f, 240L, 0.42f),
    INSTANT("instant", "Instantané", 0.78f, 0.68f, 0.46f, 140L, 0.20f),
    FLUID("fluid", "Fluide 120Hz", 0.48f, 0.44f, 0.26f, 280L, 0.32f),
    CINEMA("cinema", "Cinéma doux", 0.30f, 0.30f, 0.17f, 420L, 0.24f);

    companion object {
        fun fromId(id: String?): SmoothnessMode = values().firstOrNull { it.id == id } ?: ZERO_LAG_KALMAN
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
    val faceSubZone: FaceSubZone,
    val headPose3dTracking: Boolean,
    val silhouetteSegmentation: Boolean,
    val blurWholeBodySilhouette: Boolean,
    val skinToneRefinement: Boolean,
    val blurGenitalia: Boolean,
    val blurBreasts: Boolean,
    val blurButtocks: Boolean,
    val blurBelly: Boolean,
    val blurCoveredSensitive: Boolean,
    val blurStyle: BlurStyle,
    val maskShape: MaskShape,
    val emojiPack: EmojiPack,
    val customMaskImagePath: String,
    val blurIntensity: Int,       // 1..10
    val maskPaddingPct: Int,      // 0..45
    val edgeFeatherPct: Int,      // 0..100
    val maskOpacityPct: Int,      // 35..100
    val smoothnessMode: SmoothnessMode,
    val kalmanPrediction: Boolean,
    val kalmanLeadMs: Int,        // 0..140
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
        get() = blurFaces || anyNsfwEnabled || blurWholeBodySilhouette

    val needsSilhouetteEngine: Boolean
        get() = blurWholeBodySilhouette || (silhouetteSegmentation && (maskShape == MaskShape.SILHOUETTE || anyNsfwEnabled || blurFaces))

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
            faceSubZone = FaceSubZone.fromId(prefs.getString(KEY_FACE_SUB_ZONE, FaceSubZone.FULL_FACE.id)),
            headPose3dTracking = prefs.getBoolean(KEY_HEAD_POSE_3D, true),
            silhouetteSegmentation = prefs.getBoolean(KEY_SILHOUETTE_SEG, true),
            blurWholeBodySilhouette = prefs.getBoolean(KEY_WHOLE_BODY_SILHOUETTE, false),
            skinToneRefinement = prefs.getBoolean(KEY_SKIN_REFINE, true),
            blurGenitalia = prefs.getBoolean(KEY_BLUR_GENITALIA, true),
            blurBreasts = prefs.getBoolean(KEY_BLUR_BREASTS, true),
            blurButtocks = prefs.getBoolean(KEY_BLUR_BUTTOCKS, true),
            blurBelly = prefs.getBoolean(KEY_BLUR_BELLY, false),
            blurCoveredSensitive = prefs.getBoolean(KEY_BLUR_COVERED, false),
            blurStyle = BlurStyle.fromId(prefs.getString(KEY_BLUR_STYLE, BlurStyle.GAUSSIAN.id)),
            maskShape = MaskShape.fromId(prefs.getString(KEY_MASK_SHAPE, MaskShape.SILHOUETTE.id)),
            emojiPack = EmojiPack.fromId(prefs.getString(KEY_EMOJI_PACK, EmojiPack.AUTO_REACTIVE.id)),
            customMaskImagePath = prefs.getString(KEY_CUSTOM_IMG_PATH, "").orEmpty(),
            blurIntensity = prefs.getInt(KEY_BLUR_INTENSITY, 7).coerceIn(1, 10),
            maskPaddingPct = prefs.getInt(KEY_MASK_PADDING, 12).coerceIn(0, 45),
            edgeFeatherPct = prefs.getInt(KEY_EDGE_FEATHER, 40).coerceIn(0, 100),
            maskOpacityPct = prefs.getInt(KEY_MASK_OPACITY, 100).coerceIn(35, 100),
            smoothnessMode = SmoothnessMode.fromId(prefs.getString(KEY_SMOOTHNESS, SmoothnessMode.ZERO_LAG_KALMAN.id)),
            kalmanPrediction = prefs.getBoolean(KEY_KALMAN_PRED, true),
            kalmanLeadMs = prefs.getInt(KEY_KALMAN_LEAD_MS, 55).coerceIn(0, 140),
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

    var faceSubZone: FaceSubZone
        get() = FaceSubZone.fromId(prefs.getString(KEY_FACE_SUB_ZONE, FaceSubZone.FULL_FACE.id))
        set(v) = update { putString(KEY_FACE_SUB_ZONE, v.id) }

    var headPose3dTracking: Boolean
        get() = prefs.getBoolean(KEY_HEAD_POSE_3D, true)
        set(v) = update { putBoolean(KEY_HEAD_POSE_3D, v) }

    var silhouetteSegmentation: Boolean
        get() = prefs.getBoolean(KEY_SILHOUETTE_SEG, true)
        set(v) = update { putBoolean(KEY_SILHOUETTE_SEG, v) }

    var blurWholeBodySilhouette: Boolean
        get() = prefs.getBoolean(KEY_WHOLE_BODY_SILHOUETTE, false)
        set(v) = update { putBoolean(KEY_WHOLE_BODY_SILHOUETTE, v) }

    var skinToneRefinement: Boolean
        get() = prefs.getBoolean(KEY_SKIN_REFINE, true)
        set(v) = update { putBoolean(KEY_SKIN_REFINE, v) }

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
        get() = MaskShape.fromId(prefs.getString(KEY_MASK_SHAPE, MaskShape.SILHOUETTE.id))
        set(v) = update { putString(KEY_MASK_SHAPE, v.id) }

    var emojiPack: EmojiPack
        get() = EmojiPack.fromId(prefs.getString(KEY_EMOJI_PACK, EmojiPack.AUTO_REACTIVE.id))
        set(v) = update { putString(KEY_EMOJI_PACK, v.id) }

    var customMaskImagePath: String
        get() = prefs.getString(KEY_CUSTOM_IMG_PATH, "").orEmpty()
        set(v) = update { putString(KEY_CUSTOM_IMG_PATH, v) }

    var blurIntensity: Int
        get() = prefs.getInt(KEY_BLUR_INTENSITY, 7).coerceIn(1, 10)
        set(v) = update { putInt(KEY_BLUR_INTENSITY, v.coerceIn(1, 10)) }

    var maskPaddingPct: Int
        get() = prefs.getInt(KEY_MASK_PADDING, 12).coerceIn(0, 45)
        set(v) = update { putInt(KEY_MASK_PADDING, v.coerceIn(0, 45)) }

    var edgeFeatherPct: Int
        get() = prefs.getInt(KEY_EDGE_FEATHER, 40).coerceIn(0, 100)
        set(v) = update { putInt(KEY_EDGE_FEATHER, v.coerceIn(0, 100)) }

    var maskOpacityPct: Int
        get() = prefs.getInt(KEY_MASK_OPACITY, 100).coerceIn(35, 100)
        set(v) = update { putInt(KEY_MASK_OPACITY, v.coerceIn(35, 100)) }

    var smoothnessMode: SmoothnessMode
        get() = SmoothnessMode.fromId(prefs.getString(KEY_SMOOTHNESS, SmoothnessMode.ZERO_LAG_KALMAN.id))
        set(v) = update { putString(KEY_SMOOTHNESS, v.id) }

    var kalmanPrediction: Boolean
        get() = prefs.getBoolean(KEY_KALMAN_PRED, true)
        set(v) = update { putBoolean(KEY_KALMAN_PRED, v) }

    var kalmanLeadMs: Int
        get() = prefs.getInt(KEY_KALMAN_LEAD_MS, 55).coerceIn(0, 140)
        set(v) = update { putInt(KEY_KALMAN_LEAD_MS, v.coerceIn(0, 140)) }

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
        private const val KEY_FACE_SUB_ZONE = "face_sub_zone"
        private const val KEY_HEAD_POSE_3D = "head_pose_3d"
        private const val KEY_SILHOUETTE_SEG = "silhouette_seg"
        private const val KEY_WHOLE_BODY_SILHOUETTE = "whole_body_silhouette"
        private const val KEY_SKIN_REFINE = "skin_refine"
        private const val KEY_BLUR_GENITALIA = "blur_genitalia"
        private const val KEY_BLUR_BREASTS = "blur_breasts"
        private const val KEY_BLUR_BUTTOCKS = "blur_buttocks"
        private const val KEY_BLUR_BELLY = "blur_belly"
        private const val KEY_BLUR_COVERED = "blur_covered"
        private const val KEY_BLUR_STYLE = "blur_style"
        private const val KEY_MASK_SHAPE = "mask_shape"
        private const val KEY_EMOJI_PACK = "emoji_pack"
        private const val KEY_CUSTOM_IMG_PATH = "custom_img_path"
        private const val KEY_BLUR_INTENSITY = "blur_intensity"
        private const val KEY_MASK_PADDING = "mask_padding"
        private const val KEY_EDGE_FEATHER = "edge_feather"
        private const val KEY_MASK_OPACITY = "mask_opacity"
        private const val KEY_SMOOTHNESS = "smoothness"
        private const val KEY_KALMAN_PRED = "kalman_pred"
        private const val KEY_KALMAN_LEAD_MS = "kalman_lead_ms"
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
