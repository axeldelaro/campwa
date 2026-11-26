package com.aigallery.pro.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.aigallery.pro.data.AiModelState
import com.aigallery.pro.data.FolderPrefRow
import com.aigallery.pro.data.GalleryDatabase
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaMetaRow
import com.aigallery.pro.data.MediaType
import com.aigallery.pro.data.PersonProfile
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 100% Local On-Device Visual Taste Learning & Favorite Prediction AI:
 * - Extracts a 72-D L2-normalized visual + framing + portrait + quality + temporal feature vector.
 * - Combines THREE complementary local learners:
 *     1) Online Regularized Logistic Regression (`w ∈ R^72, b`) updated on every Tinder swipe!
 *     2) Multi-Modal k-NN Visual Prototype Similarity (captures diverse favorite aesthetics!)
 *     3) Precise Per-Person Bayesian Affinity (`Beta(α, β)`) from FaceRecognitionEngine!
 *     + Folder affinity prior!
 * - Produces an interpretable 0..99% favorite probability + plain-French explanation for EVERY media!
 */
object VisualPreferenceAiEngine {

    const val FEATURE_DIM = 72

    enum class TinderDeckMode(val id: String, val label: String) {
        ACTIVE_DISCOVERY("active", "🎲 Entraînement IA (Découverte)"),
        AI_TOP_PICKS("top_ai", "✨ Pépites Prédites par l'IA"),
        LATEST_FIRST("latest", "🕒 Plus Récents d'Abord")
    }

    /** Extracts a fast 72-D visual + portrait + quality + contextual embedding directly from [bmp]. */
    fun extractMediaFeatureVector(
        item: MediaItem,
        bmp: Bitmap,
        faceOut: FaceRecognitionEngine.FaceAnalysisOutput
    ): FloatArray {
        val vec = FloatArray(FEATURE_DIM)
        val thumb = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        val px = IntArray(64 * 64)
        thumb.getPixels(px, 0, 64, 0, 0, 64, 64)
        if (thumb !== bmp) thumb.recycle()

        val hsv = FloatArray(3)
        val lum = FloatArray(64 * 64)
        var warmCnt = 0; var skinCnt = 0; var darkCnt = 0; var vividCnt = 0
        var sumSat = 0f; var sumVal = 0f; var sumValSq = 0f

        // [0..23]: 24-D Perceptual HSV Palette + Tone stats
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
            lum[i] = v

            val hueBin = ((h / 30f).toInt()).coerceIn(0, 11)
            vec[hueBin] += (0.25f + s * 0.75f)
            val valBin = (v * 4f).toInt().coerceIn(0, 3)
            vec[12 + valBin] += 1f
            val satBin = (s * 4f).toInt().coerceIn(0, 3)
            vec[16 + satBin] += 1f

            if (h <= 55f || h >= 315f) warmCnt++
            if (r > 95 && g > 40 && b > 20 && r > g && r > b && abs(r - g) > 15 && s in 0.15f..0.72f && v > 0.25f) skinCnt++
            if (v < 0.25f) darkCnt++
            if (s > 0.55f && v > 0.35f) vividCnt++
            sumSat += s; sumVal += v; sumValSq += v * v
        }
        val nPx = px.size.toFloat()
        for (i in 0 until 20) vec[i] /= nPx
        vec[20] = warmCnt / nPx
        vec[21] = skinCnt / nPx
        vec[22] = darkCnt / nPx
        vec[23] = vividCnt / nPx

        // [24..39]: 16-D 4x4 Spatial Composition Brightness Grid
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                var sum = 0f
                for (y in by * 16 until (by + 1) * 16) {
                    for (x in bx * 16 until (bx + 1) * 16) {
                        sum += lum[y * 64 + x]
                    }
                }
                vec[24 + by * 4 + bx] = sum / 256f
            }
        }

        // [40..47]: 8-D Sharpness, Bokeh Ratio, Contrast, Aspect & Media Type
        var centerEdge = 0f; var borderEdge = 0f
        var cCnt = 0; var bCnt = 0
        for (y in 1 until 63) {
            for (x in 1 until 63) {
                val lap = abs(4f * lum[y * 64 + x] - lum[y * 64 + x - 1] - lum[y * 64 + x + 1] - lum[(y - 1) * 64 + x] - lum[(y + 1) * 64 + x])
                if (x in 16..47 && y in 16..47) { centerEdge += lap; cCnt++ }
                else { borderEdge += lap; bCnt++ }
            }
        }
        val cSharp = (centerEdge / cCnt.coerceAtLeast(1)).coerceIn(0f, 1f)
        val bSharp = (borderEdge / bCnt.coerceAtLeast(1)).coerceIn(0f, 1f)
        val avgVal = sumVal / nPx
        val contrast = sqrt(((sumValSq / nPx) - avgVal * avgVal).coerceAtLeast(0f))
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()

        vec[40] = (cSharp * 3f).coerceIn(0f, 1f)
        vec[41] = ((cSharp + 0.01f) / (cSharp + bSharp + 0.02f)).coerceIn(0f, 1f) // Bokeh/subject isolation!
        vec[42] = (contrast * 2.5f).coerceIn(0f, 1f)
        vec[43] = if (aspect < 0.88f) 1f else 0f // Portrait vertical
        vec[44] = if (aspect > 1.15f) 1f else 0f // Landscape horizontal
        vec[45] = if (item.type == MediaType.PHOTO) 1f else 0f
        vec[46] = if (item.type == MediaType.VIDEO) 1f else 0f
        vec[47] = if (item.type == MediaType.VIDEO) (ln((item.durationMs / 1000.0) + 1.0).toFloat() / 6f).coerceIn(0f, 1f) else (if (item.type == MediaType.GIF) 0.8f else 0f)

        // [48..63]: 16-D Face, Portrait Framing & Expression Context
        vec[48] = if (faceOut.faceCount == 0) 1f else 0f
        vec[49] = if (faceOut.faceCount == 1) 1f else 0f
        vec[50] = if (faceOut.faceCount == 2) 1f else 0f
        vec[51] = if (faceOut.faceCount >= 3) 1f else 0f
        vec[52] = (faceOut.largestFaceAreaRatio * 4f).coerceIn(0f, 1f)
        vec[53] = if (faceOut.largestFaceAreaRatio >= 0.14f) 1f else 0f          // Close-up portrait
        vec[54] = if (faceOut.largestFaceAreaRatio in 0.03f..0.14f) 1f else 0f   // Mid-shot
        vec[55] = if (faceOut.largestFaceAreaRatio in 0.002f..0.03f) 1f else 0f  // Full-body / wide
        vec[56] = faceOut.maxSmileProb
        vec[57] = if (faceOut.maxSmileProb >= 0.65f) 1f else 0f
        vec[58] = faceOut.avgEyeOpenProb
        vec[59] = faceOut.faceCenteredness
        vec[60] = if (faceOut.faceCount > 0 && aspect < 0.92f) 1f else 0f        // Vertical portrait!
        vec[61] = if (faceOut.faceCount > 0 && item.type == MediaType.VIDEO) 1f else 0f
        vec[62] = (sumSat / nPx).coerceIn(0f, 1f)
        vec[63] = (1f - abs(avgVal - 0.52f) * 2f).coerceIn(0f, 1f)               // Balanced exposure

        // [64..71]: 8-D Temporal & Quality Context
        val cal = Calendar.getInstance().apply { timeInMillis = item.dateModifiedSec * 1000L }
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        vec[64] = ((sin(2.0 * PI * hour / 24.0) + 1.0) * 0.5).toFloat()
        vec[65] = ((cos(2.0 * PI * hour / 24.0) + 1.0) * 0.5).toFloat()
        vec[66] = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) 1f else 0f
        vec[67] = if (hour >= 20.0 || hour <= 5.0) 1f else 0f                    // Night shot
        vec[68] = if (hour in 6.0..10.5 || hour in 16.5..20.0) 1f else 0f        // Golden hour
        vec[69] = (ln((item.sizeBytes / 1024.0) + 1.0).toFloat() / 12f).coerceIn(0f, 1f)
        vec[70] = (if (item.width >= 1000 || item.height >= 1000) 1f else 0.4f)
        vec[71] = 1f // Bias anchor feature

        return GalleryDatabase.l2Normalize(vec)
    }

    /**
     * Records a Tinder Swipe (`1 = Like`, `2 = Super-Like`, `-1 = Pass`) and updates:
     *  - SQLite favorite + swipe rating
     *  - Online Regularized Logistic Regression model weights
     *  - Per-Person Bayesian affinity counts (for EVERY recognized face in the media!)
     *  - Folder Bayesian affinity counts!
     */
    fun applySwipeFeedback(db: GalleryDatabase, item: MediaItem, rating: Int) {
        val prev = db.getMediaMeta(item.path)
        val prevRating = prev?.swipeRating ?: 0
        if (prevRating != 0) revertStatsOnly(db, item, prevRating)

        val markFav = rating > 0
        db.recordSwipeRating(item.path, rating, markFav)

        // Update per-person stats
        for (pid in item.personIds) {
            when (rating) {
                2 -> db.adjustPersonSwipeStats(pid, dLikes = 1, dDislikes = 0, dSuper = 1)
                1 -> db.adjustPersonSwipeStats(pid, dLikes = 1, dDislikes = 0, dSuper = 0)
                -1 -> db.adjustPersonSwipeStats(pid, dLikes = 0, dDislikes = 1, dSuper = 0)
            }
        }
        // Update folder stats
        when {
            rating > 0 -> db.adjustFolderSwipeStats(item.folderPath, dLikes = if (rating == 2) 2 else 1, dDislikes = 0)
            rating < 0 -> db.adjustFolderSwipeStats(item.folderPath, dLikes = 0, dDislikes = 1)
        }

        // Online Regularized Logistic Regression step on 72-D visual vector!
        val feat = prev?.featureVec
        if (feat != null && feat.size == FEATURE_DIM) {
            val model = db.loadAiModel(FEATURE_DIM)
            val w = model.weights.copyOf()
            var b = model.bias
            val y = if (rating > 0) 1f else 0f
            val sampleWeight = when (rating) {
                2 -> 2.4f
                1 -> 1.0f
                else -> 1.15f
            }
            val lr = (0.34f / (1f + model.trainSteps * 0.008f)).coerceAtLeast(0.08f) * sampleWeight
            val l2 = 0.004f
            var z = b
            for (i in 0 until FEATURE_DIM) z += w[i] * feat[i]
            val pred = sigmoid(z)
            val err = y - pred
            for (i in 0 until FEATURE_DIM) {
                w[i] = (w[i] + lr * (err * feat[i] - l2 * w[i])).coerceIn(-5f, 5f)
            }
            b = (b + lr * err * 0.45f).coerceIn(-3f, 3f)
            db.saveAiModel(AiModelState(w, b, model.trainSteps + 1))
        }
    }

    /** Cleanly undoes the previous swipe rating & person/folder counts! */
    fun undoSwipeFeedback(db: GalleryDatabase, item: MediaItem, previousRating: Int, priorWasFav: Boolean) {
        val cur = db.getMediaMeta(item.path)
        if (cur != null && cur.swipeRating != 0) {
            revertStatsOnly(db, item, cur.swipeRating)
        }
        db.recordSwipeRating(item.path, previousRating, priorWasFav)
        if (previousRating != 0) {
            for (pid in item.personIds) {
                when (previousRating) {
                    2 -> db.adjustPersonSwipeStats(pid, 1, 0, 1)
                    1 -> db.adjustPersonSwipeStats(pid, 1, 0, 0)
                    -1 -> db.adjustPersonSwipeStats(pid, 0, 1, 0)
                }
            }
        }
    }

    private fun revertStatsOnly(db: GalleryDatabase, item: MediaItem, oldRating: Int) {
        for (pid in item.personIds) {
            when (oldRating) {
                2 -> db.adjustPersonSwipeStats(pid, -1, 0, -1)
                1 -> db.adjustPersonSwipeStats(pid, -1, 0, 0)
                -1 -> db.adjustPersonSwipeStats(pid, 0, -1, 0)
            }
        }
        when {
            oldRating > 0 -> db.adjustFolderSwipeStats(item.folderPath, if (oldRating == 2) -2 else -1, 0)
            oldRating < 0 -> db.adjustFolderSwipeStats(item.folderPath, 0, -1)
        }
    }

    /**
     * Rescores EVERY indexed media item across the entire gallery combining:
     *  - Person Recognition Bayesian Affinity (up to 45% weight when recognized faces exist!)
     *  - Multi-Modal k-NN Visual Taste Similarity (up to 35% weight)
     *  - Online Regularized Logistic Regression Model (up to 20% weight)
     *  - Folder Affinity Prior (subtle tie-breaker)
     */
    fun rescoreAllMedia(db: GalleryDatabase, allMedia: List<MediaItem>): Int {
        val metaMap = db.loadAllMediaMeta()
        val personsMap = db.loadAllPersons().associateBy { it.id }
        val folderPrefs = db.loadAllFolderPrefs()
        val model = db.loadAiModel(FEATURE_DIM)

        val likedVecs = ArrayList<Pair<FloatArray, Float>>()
        val dislikedVecs = ArrayList<FloatArray>()
        for ((_, m) in metaMap) {
            val v = m.featureVec ?: continue
            if (v.size != FEATURE_DIM) continue
            when {
                m.swipeRating == 2 -> likedVecs.add(v to 1.45f)
                m.swipeRating == 1 || m.isFavorite -> likedVecs.add(v to 1.0f)
                m.swipeRating == -1 -> dislikedVecs.add(v)
            }
        }

        val hasLearned = likedVecs.isNotEmpty() || dislikedVecs.isNotEmpty() || personsMap.values.any { it.isVip || it.likesCount > 0 || it.dislikesCount > 0 }
        val updates = HashMap<String, Pair<Int, String>>(allMedia.size)

        for (item in allMedia) {
            val meta = metaMap[item.path]
            val feat = meta?.featureVec
            if (feat == null || feat.size != FEATURE_DIM) continue

            val reasons = ArrayList<String>(3)

            // 1) Person Recognition Affinity Signal
            var hasPersonSignal = false
            var personScore = 0.5f
            var topPerson: PersonProfile? = null
            if (item.personIds.isNotEmpty()) {
                val matched = item.personIds.mapNotNull { personsMap[it] }
                if (matched.isNotEmpty()) {
                    // Pick strongest person signal (VIP / highest deviation from 50%)
                    val strongest = matched.maxByOrNull { abs(it.affinityPct() - 50) + (if (it.isVip) 40 else 0) }
                    if (strongest != null) {
                        topPerson = strongest
                        val aff = strongest.affinityPct() / 100f
                        if (strongest.isVip || strongest.isIgnored || strongest.likesCount + strongest.dislikesCount > 0) {
                            hasPersonSignal = true
                            personScore = aff
                            if (strongest.isVip) reasons.add("⭐ ${strongest.displayName()} (VIP)")
                            else if (strongest.affinityPct() >= 65) reasons.add("👤 ${strongest.displayName()} (${strongest.affinityPct()} %)")
                            else if (strongest.isIgnored || strongest.affinityPct() <= 35) reasons.add("👤 ${strongest.displayName()} peu aimé")
                        } else {
                            reasons.add("👤 ${strongest.displayName()}")
                        }
                    }
                }
            }

            // 2) Multi-Modal k-NN Visual Taste Similarity (Top-6 closest likes vs Top-6 closest dislikes)
            var knnScore = 0.5f
            var hasKnn = false
            if (likedVecs.isNotEmpty() || dislikedVecs.isNotEmpty()) {
                hasKnn = true
                val posSim = if (likedVecs.isNotEmpty()) {
                    val top = likedVecs
                        .filter { it.first !== feat }
                        .map { GalleryDatabase.cosineSimilarity(feat, it.first) * it.second }
                        .sortedDescending()
                        .take(6)
                    if (top.isNotEmpty()) top.average().toFloat().coerceIn(0f, 1.2f) else 0.5f
                } else 0.45f

                val negSim = if (dislikedVecs.isNotEmpty()) {
                    val top = dislikedVecs
                        .filter { it !== feat }
                        .map { GalleryDatabase.cosineSimilarity(feat, it) }
                        .sortedDescending()
                        .take(6)
                    if (top.isNotEmpty()) top.average().toFloat().coerceIn(0f, 1f) else 0.45f
                } else 0.45f

                val diff = (posSim - negSim) * 4.2f
                knnScore = sigmoid(diff)
                if (knnScore >= 0.72f) reasons.add("🎨 Style très proche de tes favoris")
            }

            // 3) Online Logistic Regression Score
            var lrScore = 0.5f
            if (model.trainSteps > 0) {
                var z = model.bias
                for (i in 0 until FEATURE_DIM) z += model.weights[i] * feat[i]
                lrScore = sigmoid(z)
            }

            // 4) Folder Prior
            val fp = folderPrefs[item.folderPath]
            val folderScore = if (fp != null && fp.likes + fp.dislikes > 0) {
                ((fp.likes + 1.0) / (fp.likes + fp.dislikes + 2.0)).toFloat()
            } else 0.5f

            // 5) Base Aesthetic Quality Prior (when brand new before many swipes!)
            val qualityPrior = (0.42f + feat[40] * 0.16f + feat[41] * 0.14f + feat[56] * 0.14f + feat[63] * 0.10f).coerceIn(0.30f, 0.84f)

            val combinedProb: Float = when {
                !hasLearned -> qualityPrior
                hasPersonSignal && hasKnn -> personScore * 0.44f + knnScore * 0.30f + lrScore * 0.18f + folderScore * 0.08f
                hasPersonSignal -> personScore * 0.62f + lrScore * 0.24f + qualityPrior * 0.14f
                hasKnn -> knnScore * 0.52f + lrScore * 0.32f + folderScore * 0.10f + qualityPrior * 0.06f
                else -> lrScore * 0.70f + qualityPrior * 0.30f
            }

            val finalPct = (combinedProb * 100f).toInt().coerceIn(2, 99)

            if (reasons.isEmpty()) {
                when {
                    meta.faceCount > 0 && meta.smileAvg >= 0.6f -> reasons.add("😊 Portrait souriant net")
                    meta.faceCount > 0 -> reasons.add("📸 Portrait détecté")
                    item.type == MediaType.VIDEO -> reasons.add("🎬 Clip vidéo")
                    else -> reasons.add(if (hasLearned) "✨ Évalué par l'IA locale" else "🔍 Swipe pour affiner l'IA")
                }
            }
            if (topPerson == null && meta.faceCount > 0 && reasons.size < 2) {
                reasons.add("📸 ${meta.faceCount} visage(s)")
            }

            updates[item.path] = finalPct to reasons.take(2).joinToString(" · ")
        }

        db.updateAiScoresBatch(updates)
        return updates.size
    }

    /** Builds the Tinder AI swipe queue according to the selected discovery/prediction strategy! */
    fun buildTinderQueue(
        allMedia: List<MediaItem>,
        mode: TinderDeckMode,
        folderFilterPath: String? = null,
        personFilterId: Long? = null
    ): List<MediaItem> {
        val pool = allMedia.filter { item ->
            item.swipeRating == 0 &&
                (folderFilterPath == null || item.folderPath == folderFilterPath) &&
                (personFilterId == null || personFilterId in item.personIds)
        }
        if (pool.isEmpty()) return emptyList()

        return when (mode) {
            TinderDeckMode.AI_TOP_PICKS -> pool.sortedWith(
                compareByDescending<MediaItem> { it.aiScorePct }.thenByDescending { it.faceCount }.thenByDescending { it.dateModifiedSec }
            )
            TinderDeckMode.LATEST_FIRST -> pool.sortedByDescending { it.dateModifiedSec }
            TinderDeckMode.ACTIVE_DISCOVERY -> {
                // Active Learning: interleave top AI picks (fun!) + high-uncertainty items (fastest AI learning!) + diverse folders!
                val topPicks = pool.filter { it.aiScorePct >= 62 }.sortedByDescending { it.aiScorePct }
                val uncertain = pool.filter { it.aiScorePct in 35..61 }.sortedBy { abs(it.aiScorePct - 50) }
                val unscoredOrOther = pool.filter { it.aiScorePct < 35 }.shuffled()
                val out = ArrayList<MediaItem>(pool.size)
                var iT = 0; var iU = 0; var iO = 0
                var step = 0
                while (out.size < pool.size) {
                    val picked = when (step % 4) {
                        0, 2 -> if (iT < topPicks.size) topPicks[iT++] else if (iU < uncertain.size) uncertain[iU++] else if (iO < unscoredOrOther.size) unscoredOrOther[iO++] else null
                        1 -> if (iU < uncertain.size) uncertain[iU++] else if (iO < unscoredOrOther.size) unscoredOrOther[iO++] else if (iT < topPicks.size) topPicks[iT++] else null
                        else -> if (iO < unscoredOrOther.size) unscoredOrOther[iO++] else if (iU < uncertain.size) uncertain[iU++] else if (iT < topPicks.size) topPicks[iT++] else null
                    }
                    if (picked == null) break
                    out.add(picked)
                    step++
                }
                out
            }
        }
    }

    private fun sigmoid(x: Float): Float {
        val c = x.coerceIn(-12f, 12f)
        return (1.0 / (1.0 + exp(-c.toDouble()))).toFloat()
    }
}
