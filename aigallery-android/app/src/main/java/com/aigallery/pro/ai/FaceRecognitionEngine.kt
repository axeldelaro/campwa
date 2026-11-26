package com.aigallery.pro.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.aigallery.pro.data.GalleryDatabase
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaType
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 100% On-Device Precise Person / Face Recognition Engine (Zero Cloud!):
 * - Uses ML Kit Face Detection (ACCURATE + ALL landmarks + 133 dense contours + smile/eyes).
 * - Aligns every face canonically by eye line into a 96x96 normalized crop.
 * - Extracts a 224-D L2-normalized biometric embedding combining:
 *     1) 36-D pose/scale-invariant facial landmark & contour geometric ratios
 *     2) 128-D multi-block Uniform-LBP spatial facial texture (4x4 grid, lighting invariant!)
 *     3) 40-D spatial gradient orientation (HOG) across eyes/nose/mouth/jaw
 *     4) 20-D illumination-normalized skin/eye/hair chromatic signature
 * - Matches against clustered Person prototypes + top-k verified samples via cosine similarity!
 */
class FaceRecognitionEngine(private val context: Context) {

    data class FaceAnalysisOutput(
        val faceCount: Int,
        val largestFaceAreaRatio: Float,
        val maxSmileProb: Float,
        val avgEyeOpenProb: Float,
        val faceCenteredness: Float,
        val matchedPersonIds: List<Long>
    )

    private val avatarDir: File = File(context.filesDir, "face_avatars").apply { if (!exists()) mkdirs() }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
    )

    fun decodeSampledBitmapForAi(item: MediaItem, maxDim: Int = 640): Bitmap? {
        return try {
            if (item.type == MediaType.VIDEO) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(item.path)
                    val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val tUs = if (durMs > 1500L) (durMs * 350L) else 0L
                    val raw = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: mmr.getFrameAtTime(0L)
                    raw?.let { scaleDownIfNeeded(it, maxDim) }
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                var sample = 1
                var w = opts.outWidth; var h = opts.outHeight
                while (w / 2 >= maxDim || h / 2 >= maxDim) {
                    sample *= 2; w /= 2; h /= 2
                }
                val decOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(item.path, decOpts) ?: return null
                val rotated = applyExifOrientation(item.path, bmp)
                scaleDownIfNeeded(rotated, maxDim)
            }
        } catch (_: Throwable) { null }
    }

    /** Detects, embeds, and clusters all faces in [bmp], persisting face records + person profiles to [db]. */
    fun analyzeAndClusterFaces(
        item: MediaItem,
        bmp: Bitmap,
        db: GalleryDatabase,
        matchThreshold: Float = 0.76f
    ): FaceAnalysisOutput {
        val faces: List<Face> = try {
            val img = InputImage.fromBitmap(bmp, 0)
            Tasks.await(detector.process(img), 4_500L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { emptyList() }

        db.clearFacesForMedia(item.path)
        if (faces.isEmpty()) {
            return FaceAnalysisOutput(0, 0f, 0f, 0f, 0f, emptyList())
        }

        val imgArea = (bmp.width * bmp.height).coerceAtLeast(1).toFloat()
        val persons = db.loadAllPersons().toMutableList()
        val samplesByPerson = db.loadAllFaceSamplesByPerson(maxPerPerson = 6)

        var maxAreaRatio = 0f
        var maxSmile = 0f
        var sumEyes = 0f
        var bestCentered = 0f
        val matchedIds = ArrayList<Long>(faces.size)

        // Process up to 5 largest clear faces per media
        val sortedFaces = faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(5)
        for ((idx, face) in sortedFaces.withIndex()) {
            val box = face.boundingBox
            if (box.width() < 28 || box.height() < 28) continue
            val areaRatio = ((box.width() * box.height()).toFloat() / imgArea).coerceIn(0f, 1f)
            if (areaRatio > maxAreaRatio) maxAreaRatio = areaRatio

            val smile = (face.smilingProbability ?: 0.25f).coerceIn(0f, 1f)
            if (smile > maxSmile) maxSmile = smile
            val lEyeP = face.leftEyeOpenProbability ?: 0.85f
            val rEyeP = face.rightEyeOpenProbability ?: 0.85f
            sumEyes += (lEyeP + rEyeP) * 0.5f

            val fcx = box.exactCenterX() / bmp.width.coerceAtLeast(1)
            val fcy = box.exactCenterY() / bmp.height.coerceAtLeast(1)
            val centered = (1f - hypot((fcx - 0.5f).toDouble(), (fcy - 0.45f).toDouble()).toFloat() * 1.4f).coerceIn(0f, 1f)
            if (centered > bestCentered) bestCentered = centered

            // 1) Align face canonically to 96x96 by eyes!
            val aligned96 = alignFaceCrop(bmp, face, 96) ?: continue
            // 2) Extract 224-D L2-normalized biometric identity embedding!
            val embedding = extractFaceEmbedding224(aligned96, face)

            // 3) Match against existing person clusters (0.55 centroid + 0.45 top-3 k-NN samples)
            var bestPid = -1L
            var bestSim = -1f
            for (p in persons) {
                if (p.prototypeEmbedding.size != embedding.size) continue
                val protoSim = GalleryDatabase.cosineSimilarity(embedding, p.prototypeEmbedding)
                val samples = samplesByPerson[p.id]
                var sampleSim = protoSim
                if (!samples.isNullOrEmpty()) {
                    val sims = samples.map { GalleryDatabase.cosineSimilarity(embedding, it) }.sortedDescending()
                    val k = minOf(3, sims.size)
                    var sum = 0f
                    for (j in 0 until k) sum += sims[j]
                    sampleSim = sum / k
                }
                val combined = protoSim * 0.55f + sampleSim * 0.45f
                if (combined > bestSim) {
                    bestSim = combined
                    bestPid = p.id
                }
            }

            // Avoid matching two distinct faces in the SAME photo to the same person ID!
            val alreadyInPhoto = bestPid in matchedIds
            val avatarPath = saveAvatarIfNeeded(aligned96, item.path.hashCode(), idx)
            aligned96.recycle()

            val assignedPid: Long
            val confidence: Float
            if (bestPid > 0L && bestSim >= matchThreshold && !alreadyInPhoto) {
                assignedPid = bestPid
                confidence = bestSim
                db.insertFaceAndUpdatePrototype(
                    mediaPath = item.path,
                    personId = assignedPid,
                    faceVec = embedding,
                    avatarPath = avatarPath,
                    confidence = confidence,
                    smileProb = smile,
                    eyeProb = (lEyeP + rEyeP) * 0.5f,
                    faceRatio = areaRatio
                )
            } else {
                assignedPid = db.createPerson(avatarPath, embedding)
                confidence = 1.0f
                db.insertFaceAndUpdatePrototype(
                    mediaPath = item.path,
                    personId = assignedPid,
                    faceVec = embedding,
                    avatarPath = avatarPath,
                    confidence = confidence,
                    smileProb = smile,
                    eyeProb = (lEyeP + rEyeP) * 0.5f,
                    faceRatio = areaRatio
                )
                // Add immediately to in-memory person list for remaining media in this pass
                persons.add(
                    com.aigallery.pro.data.PersonProfile(
                        id = assignedPid,
                        name = "",
                        avatarPath = avatarPath,
                        faceCount = 1,
                        likesCount = 0,
                        dislikesCount = 0,
                        superLikesCount = 0,
                        isVip = false,
                        isIgnored = false,
                        prototypeEmbedding = embedding
                    )
                )
            }
            matchedIds.add(assignedPid)
        }

        val validCnt = matchedIds.size
        return FaceAnalysisOutput(
            faceCount = validCnt,
            largestFaceAreaRatio = maxAreaRatio,
            maxSmileProb = maxSmile,
            avgEyeOpenProb = if (validCnt > 0) (sumEyes / validCnt).coerceIn(0f, 1f) else 0f,
            faceCenteredness = bestCentered,
            matchedPersonIds = matchedIds
        )
    }

    /** Eye-line affine alignment into a crisp canonical size x size crop! */
    private fun alignFaceCrop(src: Bitmap, face: Face, outSize: Int): Bitmap? {
        return try {
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val m = Matrix()

            if (leftEye != null && rightEye != null) {
                // Ensure eyeA is visually on the left in image coordinates
                val eyeA = if (leftEye.x <= rightEye.x) leftEye else rightEye
                val eyeB = if (leftEye.x <= rightEye.x) rightEye else leftEye
                val dx = eyeB.x - eyeA.x
                val dy = eyeB.y - eyeA.y
                val eyeDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (eyeDist >= 10f) {
                    val angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
                    val midX = (eyeA.x + eyeB.x) * 0.5f
                    val midY = (eyeA.y + eyeB.y) * 0.5f
                    val scale = (outSize * 0.40f) / eyeDist
                    m.postTranslate(-midX, -midY)
                    m.postRotate(-angleDeg)
                    m.postScale(scale, scale)
                    m.postTranslate(outSize * 0.50f, outSize * 0.36f)
                    canvas.drawBitmap(src, m, paint)
                    return out
                }
            }

            // Fallback: centered bounding box crop with small margin
            val box = face.boundingBox
            val padX = (box.width() * 0.14f).toInt()
            val padY = (box.height() * 0.16f).toInt()
            val l = (box.left - padX).coerceIn(0, src.width - 1)
            val t = (box.top - padY).coerceIn(0, src.height - 1)
            val r = (box.right + padX).coerceIn(l + 1, src.width)
            val b = (box.bottom + padY).coerceIn(t + 1, src.height)
            val sx = outSize.toFloat() / (r - l).coerceAtLeast(1)
            val sy = outSize.toFloat() / (b - t).coerceAtLeast(1)
            m.postTranslate(-l.toFloat(), -t.toFloat())
            m.postScale(sx, sy)
            canvas.drawBitmap(src, m, paint)
            out
        } catch (_: Throwable) { null }
    }

    /**
     * Extracts a 224-D L2-normalized biometric identity descriptor:
     *  - [0..35]:   36-D pose/scale-invariant facial landmark & contour geometric ratios (weight 1.35)
     *  - [36..163]: 128-D multi-block 4x4 LBP micro-texture (weight 1.15)
     *  - [164..203]: 40-D spatial gradient orientation HOG over facial grid (weight 1.00)
     *  - [204..223]: 20-D illumination-normalized skin/eye/hair chromatic signature (weight 0.65)
     */
    private fun extractFaceEmbedding224(aligned96: Bitmap, face: Face): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val w = aligned96.width
        val h = aligned96.height
        val pixels = IntArray(w * h)
        aligned96.getPixels(pixels, 0, w, 0, 0, w, h)

        // Illumination-normalized grayscale image (zero-mean, unit-std inside central oval!)
        val gray = FloatArray(w * h)
        var meanL = 0.0
        for (i in pixels.indices) {
            val c = pixels[i]
            val l = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            gray[i] = l
            meanL += l
        }
        meanL /= pixels.size.coerceAtLeast(1)
        var varL = 0.0
        for (v in gray) {
            val d = v - meanL
            varL += d * d
        }
        val stdL = sqrt(varL / pixels.size.coerceAtLeast(1)).toFloat().coerceAtLeast(12f)
        for (i in gray.indices) gray[i] = ((gray[i] - meanL.toFloat()) / stdL).coerceIn(-3f, 3f)

        // ---- PART 1: 36-D Geometric Landmark & Contour Ratios (invariant to lighting & clothes!) ----
        val geo = FloatArray(36)
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mb = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val lc = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
        val rc = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

        val unit = if (le != null && re != null) {
            dist(le, re).coerceAtLeast(face.boundingBox.width() * 0.22f).coerceAtLeast(1f)
        } else face.boundingBox.width().toFloat().coerceAtLeast(1f)
        val eyeMid = if (le != null && re != null) PointF((le.x + re.x) * 0.5f, (le.y + re.y) * 0.5f)
        else PointF(face.boundingBox.exactCenterX(), face.boundingBox.top + face.boundingBox.height() * 0.36f)

        var gi = 0
        fun putGeo(v: Float) { if (gi < 36) geo[gi++] = v.coerceIn(-2.5f, 2.5f) }

        putGeo(face.boundingBox.height().toFloat() / face.boundingBox.width().coerceAtLeast(1))
        putGeo(if (nose != null) dist(eyeMid, nose) / unit else 0.72f)
        putGeo(if (le != null && nose != null) dist(le, nose) / unit else 0.90f)
        putGeo(if (re != null && nose != null) dist(re, nose) / unit else 0.90f)
        putGeo(if (ml != null && mr != null) dist(ml, mr) / unit else 0.85f)
        putGeo(if (nose != null && mb != null) dist(nose, mb) / unit else 0.58f)
        putGeo(if (mb != null) dist(eyeMid, mb) / unit else 1.25f)
        putGeo(if (lc != null && rc != null) dist(lc, rc) / unit else 1.35f)
        putGeo(if (lc != null && ml != null) dist(lc, ml) / unit else 0.55f)
        putGeo(if (rc != null && mr != null) dist(rc, mr) / unit else 0.55f)

        // Contour shape ratios (eyes, eyebrows, nose bridge, lips, face oval width profile!)
        val oval = face.getContour(FaceContour.FACE)?.points
        if (oval != null && oval.size >= 18) {
            val topY = oval.minOf { it.y }
            val botY = oval.maxOf { it.y }
            val ovalH = (botY - topY).coerceAtLeast(1f)
            putGeo(ovalH / unit)
            // Measure horizontal oval width at 6 anatomical height levels (forehead -> jaw -> chin!)
            val fracs = floatArrayOf(0.18f, 0.34f, 0.50f, 0.66f, 0.80f, 0.92f)
            for (f in fracs) {
                val ty = topY + ovalH * f
                val near = oval.filter { abs(it.y - ty) <= ovalH * 0.11f }
                val sliceW = if (near.size >= 2) (near.maxOf { it.x } - near.minOf { it.x }) else unit
                putGeo(sliceW / unit)
            }
        } else {
            repeat(7) { putGeo(1.0f) }
        }

        val lEyePts = face.getContour(FaceContour.LEFT_EYE)?.points
        val rEyePts = face.getContour(FaceContour.RIGHT_EYE)?.points
        putGeo(contourWidth(lEyePts) / unit)
        putGeo(contourHeight(lEyePts) / unit)
        putGeo(contourWidth(rEyePts) / unit)
        putGeo(contourHeight(rEyePts) / unit)

        val lBrow = face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rBrow = face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points
        putGeo(contourWidth(lBrow) / unit)
        putGeo(contourWidth(rBrow) / unit)
        if (lBrow != null && lEyePts != null && lBrow.isNotEmpty() && lEyePts.isNotEmpty()) {
            putGeo((lEyePts.map { it.y }.average().toFloat() - lBrow.map { it.y }.average().toFloat()) / unit)
        } else putGeo(0.22f)
        if (rBrow != null && rEyePts != null && rBrow.isNotEmpty() && rEyePts.isNotEmpty()) {
            putGeo((rEyePts.map { it.y }.average().toFloat() - rBrow.map { it.y }.average().toFloat()) / unit)
        } else putGeo(0.22f)

        val noseBot = face.getContour(FaceContour.NOSE_BOTTOM)?.points
        val noseBr = face.getContour(FaceContour.NOSE_BRIDGE)?.points
        putGeo(contourWidth(noseBot) / unit)
        putGeo(contourHeight(noseBr) / unit)

        val upLip = face.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val lowLip = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        putGeo(contourWidth(upLip) / unit)
        putGeo(contourWidth(lowLip) / unit)
        if (upLip != null && lowLip != null && upLip.isNotEmpty() && lowLip.isNotEmpty()) {
            putGeo((lowLip.map { it.y }.average().toFloat() - upLip.map { it.y }.average().toFloat()) / unit)
        } else putGeo(0.24f)

        GalleryDatabase.l2Normalize(geo)
        for (i in 0 until 36) vec[i] = geo[i] * 1.35f

        // ---- PART 2: 128-D Multi-Block LBP Texture (4x4 spatial grid x 8 bit-orientation counts) ----
        val lbp = FloatArray(128)
        val cellW = w / 4
        val cellH = h / 4
        val dx8 = intArrayOf(-1, 0, 1, 1, 1, 0, -1, -1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 1, 1, 1, 0)
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                val baseBin = (by * 4 + bx) * 8
                val y0 = (by * cellH).coerceAtLeast(1)
                val y1 = ((by + 1) * cellH).coerceAtMost(h - 2)
                val x0 = (bx * cellW).coerceAtLeast(1)
                val x1 = ((bx + 1) * cellW).coerceAtMost(w - 2)
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val center = gray[y * w + x]
                        for (k in 0 until 8) {
                            val nb = gray[(y + dy8[k]) * w + (x + dx8[k])]
                            if (nb >= center) lbp[baseBin + k] += 1f
                        }
                        x += 2
                    }
                    y += 2
                }
            }
        }
        GalleryDatabase.l2Normalize(lbp)
        for (i in 0 until 128) vec[36 + i] = lbp[i] * 1.15f

        // ---- PART 3: 40-D Spatial HOG Gradient Orientation (2x5 horizontal/vertical anatomical zones x 4 bins) ----
        val hog = FloatArray(40)
        val zCols = 2; val zRows = 5
        val zw = w / zCols; val zh = h / zRows
        for (y in 2 until h - 2 step 2) {
            val zr = (y / zh).coerceIn(0, zRows - 1)
            for (x in 2 until w - 2 step 2) {
                val zc = (x / zw).coerceIn(0, zCols - 1)
                val gx = gray[y * w + (x + 1)] - gray[y * w + (x - 1)]
                val gy = gray[(y + 1) * w + x] - gray[(y - 1) * w + x]
                val mag = hypot(gx.toDouble(), gy.toDouble()).toFloat()
                if (mag > 0.08f) {
                    var ang = atan2(gy.toDouble(), gx.toDouble())
                    if (ang < 0) ang += PI
                    val bin = ((ang / PI) * 4.0).toInt().coerceIn(0, 3)
                    hog[(zr * zCols + zc) * 4 + bin] += mag
                }
            }
        }
        GalleryDatabase.l2Normalize(hog)
        for (i in 0 until 40) vec[164 + i] = hog[i] * 1.00f

        // ---- PART 4: 20-D Illumination-Normalized Regional Color Signature (Hairline, Eyes, Cheeks, Lips, Chin) ----
        val chroma = FloatArray(20)
        // 5 vertical facial bands x 4 features (normalized R-G, normalized B-Y, saturation, relative luminance)
        val regions = arrayOf(
            intArrayOf(18, 0, 78, 22),   // Hairline / Forehead
            intArrayOf(16, 26, 80, 44),  // Eyes & Brows
            intArrayOf(22, 46, 74, 66),  // Cheeks & Nose Skin
            intArrayOf(28, 66, 68, 82),  // Mouth / Lips
            intArrayOf(26, 80, 70, 95)   // Chin / Jaw
        )
        for (rIdx in regions.indices) {
            val r = regions[rIdx]
            var sRG = 0f; var sBY = 0f; var sSat = 0f; var sLum = 0f; var cnt = 0
            for (y in r[1] until r[3] step 2) {
                for (x in r[0] until r[2] step 2) {
                    val c = pixels[y * w + x]
                    val rf = Color.red(c) / 255f
                    val gf = Color.green(c) / 255f
                    val bf = Color.blue(c) / 255f
                    val sum = (rf + gf + bf).coerceAtLeast(0.05f)
                    val nr = rf / sum; val ng = gf / sum; val nb = bf / sum
                    sRG += (nr - ng)
                    sBY += (nb - (nr + ng) * 0.5f)
                    val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf)
                    sSat += if (mx > 0.01f) (mx - mn) / mx else 0f
                    sLum += (rf + gf + bf) / 3f
                    cnt++
                }
            }
            val div = cnt.coerceAtLeast(1).toFloat()
            chroma[rIdx * 4 + 0] = sRG / div
            chroma[rIdx * 4 + 1] = sBY / div
            chroma[rIdx * 4 + 2] = sSat / div
            chroma[rIdx * 4 + 3] = (sLum / div) - 0.5f
        }
        GalleryDatabase.l2Normalize(chroma)
        for (i in 0 until 20) vec[204 + i] = chroma[i] * 0.65f

        return GalleryDatabase.l2Normalize(vec)
    }

    private fun saveAvatarIfNeeded(aligned96: Bitmap, hashKey: Int, idx: Int): String {
        val file = File(avatarDir, "face_${abs(hashKey)}_$idx.jpg")
        if (!file.exists()) {
            try {
                FileOutputStream(file).use { out ->
                    aligned96.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            } catch (_: Throwable) { return "" }
        }
        return file.absolutePath
    }

    private fun dist(a: PointF, b: PointF): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun contourWidth(pts: List<PointF>?): Float {
        if (pts == null || pts.size < 2) return 0f
        return (pts.maxOf { it.x } - pts.minOf { it.x }).coerceAtLeast(0f)
    }

    private fun contourHeight(pts: List<PointF>?): Float {
        if (pts == null || pts.size < 2) return 0f
        return (pts.maxOf { it.y } - pts.minOf { it.y }).coerceAtLeast(0f)
    }

    private fun scaleDownIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val scale = maxDim.toFloat() / maxOf(w, h).coerceAtLeast(1)
        val nw = (w * scale).toInt().coerceAtLeast(32)
        val nh = (h * scale).toInt().coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun applyExifOrientation(path: String, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (ori) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) bmp else {
                val m = Matrix().apply { postRotate(deg) }
                val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rot !== bmp) bmp.recycle()
                rot
            }
        } catch (_: Throwable) { bmp }
    }

    fun close() {
        try { detector.close() } catch (_: Throwable) {}
    }

    companion object {
        const val EMBEDDING_DIM = 224
    }
}
