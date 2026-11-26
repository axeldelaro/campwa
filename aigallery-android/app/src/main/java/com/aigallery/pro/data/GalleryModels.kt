package com.aigallery.pro.data

enum class MediaType(val id: String) {
    PHOTO("photo"),
    VIDEO("video"),
    GIF("gif");

    companion object {
        fun fromId(id: String?): MediaType = values().firstOrNull { it.id == id } ?: PHOTO
    }
}

enum class SortOrder(val id: String, val label: String) {
    DATE_DESC("date_desc", "Date (récent → ancien)"),
    DATE_ASC("date_asc", "Date (ancien → récent)"),
    NAME_ASC("name_asc", "Nom (A → Z)"),
    NAME_DESC("name_desc", "Nom (Z → A)"),
    SIZE_DESC("size_desc", "Taille (plus gros)"),
    AI_SCORE_DESC("ai_desc", "✨ Score IA Favori");

    companion object {
        fun fromId(id: String?): SortOrder = values().firstOrNull { it.id == id } ?: DATE_DESC
    }
}

enum class MediaFilter(val id: String, val label: String) {
    ALL("all", "Tous"),
    PHOTOS("photos", "Photos"),
    VIDEOS("videos", "Vidéos"),
    GIFS("gifs", "GIFs"),
    FAVORITES("favs", "❤️ Favoris"),
    AI_TOP("ai_top", "✨ Top IA")
}

enum class GalleryTheme(val id: String, val label: String) {
    DARK("dark", "Sombre (Simple Pro)"),
    AMOLED("amoled", "AMOLED Noir Pur");

    companion object {
        fun fromId(id: String?): GalleryTheme = values().firstOrNull { it.id == id } ?: DARK
    }
}

data class MediaItem(
    val path: String,
    val name: String,
    val folderPath: String,
    val folderName: String,
    val type: MediaType,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val isFavorite: Boolean = false,
    val swipeRating: Int = 0,          // 0=unseen, 1=like, 2=superlike, -1=pass
    val aiScorePct: Int = -1,          // -1=not scored yet, 0..99 predicted favorite probability!
    val aiReason: String = "",
    val personIds: List<Long> = emptyList(),
    val faceCount: Int = 0
)

data class AlbumFolder(
    val path: String,
    val name: String,
    val coverPath: String,
    val coverType: MediaType,
    val mediaCount: Int,
    val latestDateSec: Long,
    val totalSizeBytes: Long,
    val isPinned: Boolean = false,
    val isHiddenFolder: Boolean = false,
    val avgAiScore: Int = -1
)

data class PersonProfile(
    val id: Long,
    val name: String,
    val avatarPath: String,
    val faceCount: Int,
    val likesCount: Int,
    val dislikesCount: Int,
    val superLikesCount: Int,
    val isVip: Boolean,
    val isIgnored: Boolean,
    val prototypeEmbedding: FloatArray
) {
    /** Bayesian smoothed affinity probability 0..100% from user Tinder swipes & VIP status! */
    fun affinityPct(): Int {
        if (isIgnored) return 5
        val weightedPos = likesCount * 1.0 + superLikesCount * 2.5 + (if (isVip) 3.5 else 0.0) + 1.2
        val weightedNeg = dislikesCount * 1.2 + 1.2
        return ((weightedPos / (weightedPos + weightedNeg)) * 100.0).toInt().coerceIn(5, 99)
    }

    fun displayName(): String = name.ifBlank { "Personne #$id" }
}

data class DetectedFaceRecord(
    val faceId: Long,
    val mediaPath: String,
    val personId: Long,
    val embedding: FloatArray,
    val avatarPath: String,
    val confidence: Float,
    val smileProb: Float,
    val eyeOpenProb: Float,
    val faceRatio: Float
)

data class TrashedMedia(
    val trashId: Long,
    val originalPath: String,
    val trashFilePath: String,
    val name: String,
    val type: MediaType,
    val trashedAtMs: Long
)

data class MediaMetaRow(
    val path: String,
    val isFavorite: Boolean,
    val swipeRating: Int,
    val swipedAtMs: Long,
    val aiScorePct: Int,
    val aiReason: String,
    val featureVec: FloatArray?,
    val indexedMtimeSec: Long,
    val faceCount: Int,
    val smileAvg: Float
)

data class FolderPrefRow(
    val folderPath: String,
    val isPinned: Boolean,
    val isExcluded: Boolean,
    val likes: Int,
    val dislikes: Int
)

data class AiModelState(
    val weights: FloatArray,
    val bias: Float,
    val trainSteps: Int
)
