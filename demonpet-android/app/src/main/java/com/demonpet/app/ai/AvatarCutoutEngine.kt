package com.demonpet.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.demonpet.app.media.MediaFolderHelper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Exact normalized facial landmark coordinates (0..1 relative to the cutout PNG)
 * used by DemonPetView to anchor 3D realistic horns on the real temples and realistic ember glow on the real eyes!
 */
data class AvatarFaceMeta(
    val leftEyeX: Float = 0.40f,
    val leftEyeY: Float = 0.38f,
    val rightEyeX: Float = 0.60f,
    val rightEyeY: Float = 0.38f,
    val leftTempleX: Float = 0.32f,
    val leftTempleY: Float = 0.23f,
    val rightTempleX: Float = 0.68f,
    val rightTempleY: Float = 0.23f,
    val foreheadY: Float = 0.21f,
    val chinY: Float = 0.64f,
    val faceWidthRatio: Float = 0.44f
) {
    fun toJson(): String = JSONObject().apply {
        put("leftEyeX", leftEyeX.toDouble())
        put("leftEyeY", leftEyeY.toDouble())
        put("rightEyeX", rightEyeX.toDouble())
        put("rightEyeY", rightEyeY.toDouble())
        put("leftTempleX", leftTempleX.toDouble())
        put("leftTempleY", leftTempleY.toDouble())
        put("rightTempleX", rightTempleX.toDouble())
        put("rightTempleY", rightTempleY.toDouble())
        put("foreheadY", foreheadY.toDouble())
        put("chinY", chinY.toDouble())
        put("faceWidthRatio", faceWidthRatio.toDouble())
    }.toString()

    companion object {
        fun fromJson(raw: String?): AvatarFaceMeta {
            if (raw.isNullOrBlank()) return AvatarFaceMeta()
            return try {
                val o = JSONObject(raw)
                AvatarFaceMeta(
                    leftEyeX = o.optDouble("leftEyeX", 0.40).toFloat(),
                    leftEyeY = o.optDouble("leftEyeY", 0.38).toFloat(),
                    rightEyeX = o.optDouble("rightEyeX", 0.60).toFloat(),
                    rightEyeY = o.optDouble("rightEyeY", 0.38).toFloat(),
                    leftTempleX = o.optDouble("leftTempleX", 0.32).toFloat(),
                    leftTempleY = o.optDouble("leftTempleY", 0.23).toFloat(),
                    rightTempleX = o.optDouble("rightTempleX", 0.68).toFloat(),
                    rightTempleY = o.optDouble("rightTempleY", 0.23).toFloat(),
                    foreheadY = o.optDouble("foreheadY", 0.21).toFloat(),
                    chinY = o.optDouble("chinY", 0.64).toFloat(),
                    faceWidthRatio = o.optDouble("faceWidthRatio", 0.44).toFloat()
                )
            } catch (_: Throwable) {
                AvatarFaceMeta()
            }
        }
    }
}

/**
 * Local AI Studio-Grade Realistic Portrait/Bust Cutout Engine (512x640 HD):
 * - Extracts the REAL person's head + hair + neck + upper shoulders cleanly with ML Kit Selfie Segmentation
 *   and soft studio alpha feathering + subtle bottom bust taper (no ugly circular chop!).
 * - Detects exact eye & forehead landmarks (`AvatarFaceMeta`) so DemonPetView anchors realistic 3D horns
 *   and angry glowing eyes right on the real face.
 */
object AvatarCutoutEngine {

    private const val AVATAR_OUT_FILE = "demon_avatar_head.png"
    private const val AVATAR_RAW_FILE = "demon_avatar_source.jpg"
    private const val AVATAR_META_FILE = "demon_avatar_meta.json"

    fun loadFaceMeta(context: Context): AvatarFaceMeta {
        return try {
            val f = File(context.filesDir, AVATAR_META_FILE)
            if (f.exists()) AvatarFaceMeta.fromJson(f.readText()) else AvatarFaceMeta()
        } catch (_: Throwable) {
            AvatarFaceMeta()
        }
    }

    fun createAvatarFromUri(context: Context, uri: Uri, includeHair: Boolean): String? {
        val source = MediaFolderHelper.decodeSampledUri(context, uri, 1280) ?: return null
        return try {
            val rawFile = File(context.filesDir, AVATAR_RAW_FILE)
            FileOutputStream(rawFile).use { fos ->
                source.compress(Bitmap.CompressFormat.JPEG, 94, fos)
            }
            cutoutFromBitmap(context, source, includeHair)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    fun rebuildFromSavedSource(context: Context, includeHair: Boolean): String? {
        val rawFile = File(context.filesDir, AVATAR_RAW_FILE)
        if (!rawFile.exists()) return null
        val source = MediaFolderHelper.decodeSampledFile(rawFile, 1280) ?: return null
        return try {
            cutoutFromBitmap(context, source, includeHair)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun cutoutFromBitmap(context: Context, source: Bitmap, includeHair: Boolean): String? {
        val img = InputImage.fromBitmap(source, 0)
        val faceOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        val detector = FaceDetection.getClient(faceOpts)
        val faces = try {
            Tasks.await(detector.process(img), 4L, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            try { detector.close() } catch (_: Throwable) {}
        }

        val sw = source.width
        val sh = source.height
        val bestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

        // Portrait 4:5 HD canvas (512 x 640) for crisp realistic bust/portrait!
        val outW = 512
        val outH = 640
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var meta = AvatarFaceMeta()

        if (bestFace != null) {
            val box = bestFace.boundingBox
            val bw = box.width().coerceAtLeast(24).toFloat()
            val bh = box.height().coerceAtLeast(24).toFloat()

            // Generous realistic portrait framing: includes full hair + neck + upper shoulders!
            val padLeft = if (includeHair) bw * 0.56f else bw * 0.22f
            val padRight = if (includeHair) bw * 0.56f else bw * 0.22f
            val padTop = if (includeHair) bh * 0.62f else bh * 0.22f
            val padBot = if (includeHair) bh * 0.86f else bh * 0.24f

            val rawL = (box.left - padLeft).toInt().coerceIn(0, sw - 4)
            val rawT = (box.top - padTop).toInt().coerceIn(0, sh - 4)
            val rawR = (box.right + padRight).toInt().coerceIn(rawL + 4, sw)
            val rawB = (box.bottom + padBot).toInt().coerceIn(rawT + 4, sh)

            // Fit 4:5 aspect ratio centered around the face
            val targetAspect = outW.toFloat() / outH.toFloat()
            var cw = (rawR - rawL).coerceAtLeast(4).toFloat()
            var ch = (rawB - rawT).coerceAtLeast(4).toFloat()
            if (cw / ch > targetAspect) {
                ch = cw / targetAspect
            } else {
                cw = ch * targetAspect
            }
            val cx = (rawL + rawR) * 0.5f
            val cy = (rawT + rawB) * 0.5f
            val cropW = cw.toInt().coerceIn(4, sw)
            val cropH = ch.toInt().coerceIn(4, sh)
            val sqL = (cx - cropW * 0.5f).toInt().coerceIn(0, max(0, sw - cropW))
            val sqT = (cy - cropH * 0.5f).toInt().coerceIn(0, max(0, sh - cropH))
            val sqR = min(sw, sqL + cropW)
            val sqB = min(sh, sqT + cropH)
            val finalW = (sqR - sqL).coerceAtLeast(4)
            val finalH = (sqB - sqT).coerceAtLeast(4)

            // Save exact normalized face landmarks for 3D horn & glowing eyes anchoring
            fun nx(x: Float) = ((x - sqL) / finalW.toFloat()).coerceIn(0.05f, 0.95f)
            fun ny(y: Float) = ((y - sqT) / finalH.toFloat()).coerceIn(0.05f, 0.95f)

            val lEye = bestFace.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rEye = bestFace.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val eye1X = if (lEye != null) nx(lEye.x) else nx(box.left + bw * 0.32f)
            val eye1Y = if (lEye != null) ny(lEye.y) else ny(box.top + bh * 0.42f)
            val eye2X = if (rEye != null) nx(rEye.x) else nx(box.left + bw * 0.68f)
            val eye2Y = if (rEye != null) ny(rEye.y) else ny(box.top + bh * 0.42f)

            val leftEyeX = min(eye1X, eye2X)
            val rightEyeX = max(eye1X, eye2X)
            val leftEyeY = if (eye1X <= eye2X) eye1Y else eye2Y
            val rightEyeY = if (eye1X <= eye2X) eye2Y else eye1Y
            val foreheadY = ny(box.top + bh * 0.04f)
            val chinY = ny(box.bottom.toFloat())
            val faceW = (bw / finalW.toFloat()).coerceIn(0.22f, 0.78f)

            meta = AvatarFaceMeta(
                leftEyeX = leftEyeX,
                leftEyeY = leftEyeY,
                rightEyeX = rightEyeX,
                rightEyeY = rightEyeY,
                leftTempleX = nx(box.left + bw * 0.16f),
                leftTempleY = ny(box.top + bh * 0.08f),
                rightTempleX = nx(box.right - bw * 0.16f),
                rightTempleY = ny(box.top + bh * 0.08f),
                foreheadY = foreheadY,
                chinY = chinY,
                faceWidthRatio = faceW
            )

            val bustCrop = Bitmap.createBitmap(source, sqL, sqT, finalW, finalH)
            val scaledBust = Bitmap.createScaledBitmap(bustCrop, outW, outH, true)
            if (bustCrop !== scaledBust) bustCrop.recycle()

            // Subtle studio clarity & richness enhancement (keeps 100% real likeness!)
            val studioPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                val cm = ColorMatrix().apply {
                    setSaturation(1.08f)
                }
                val contrast = 1.05f
                val translate = (-0.5f * contrast + 0.5f) * 255f
                cm.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )))
                colorFilter = ColorMatrixColorFilter(cm)
            }

            if (includeHair) {
                val segMask = runRealisticBustMask(scaledBust, outW, outH)
                val save = canvas.saveLayer(0f, 0f, outW.toFloat(), outH.toFloat(), null)
                canvas.drawBitmap(scaledBust, 0f, 0f, studioPaint)
                if (segMask != null) {
                    val dstIn = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    canvas.drawBitmap(segMask, 0f, 0f, dstIn)
                    segMask.recycle()
                } else {
                    clipSmoothPortraitMask(canvas, outW, outH)
                }
                canvas.restoreToCount(save)
            } else {
                val contour = bestFace.getContour(FaceContour.FACE)?.points
                if (contour != null && contour.size >= 8) {
                    val path = Path()
                    val scaleX = outW.toFloat() / finalW.toFloat()
                    val scaleY = outH.toFloat() / finalH.toFloat()
                    val pts = contour.map { pt -> Pair((pt.x - sqL) * scaleX, (pt.y - sqT) * scaleY) }
                    val n = pts.size
                    path.moveTo((pts[n - 1].first + pts[0].first) * 0.5f, (pts[n - 1].second + pts[0].second) * 0.5f)
                    for (i in 0 until n) {
                        val nxt = (i + 1) % n
                        path.quadTo(
                            pts[i].first, pts[i].second,
                            (pts[i].first + pts[nxt].first) * 0.5f,
                            (pts[i].second + pts[nxt].second) * 0.5f
                        )
                    }
                    path.close()
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(scaledBust, 0f, 0f, studioPaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(scaledBust, 0f, 0f, studioPaint)
                    clipSmoothPortraitMask(canvas, outW, outH)
                }
            }
            if (!scaledBust.isRecycled) scaledBust.recycle()
        } else {
            // No face detected: use Selfie Segmentation on full portrait center!
            val targetAspect = outW.toFloat() / outH.toFloat()
            val cw = min(sw.toFloat(), sh * targetAspect).toInt().coerceAtLeast(4)
            val ch = min(sh.toFloat(), sw / targetAspect).toInt().coerceAtLeast(4)
            val l = (sw - cw) / 2
            val t = (sh - ch) / 2
            val crop = Bitmap.createBitmap(source, l, t, cw, ch)
            val scaled = Bitmap.createScaledBitmap(crop, outW, outH, true)
            if (crop !== scaled) crop.recycle()

            val segMask = runRealisticBustMask(scaled, outW, outH)
            val save = canvas.saveLayer(0f, 0f, outW.toFloat(), outH.toFloat(), null)
            canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (segMask != null) {
                canvas.drawBitmap(segMask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
                segMask.recycle()
            } else {
                clipSmoothPortraitMask(canvas, outW, outH)
            }
            canvas.restoreToCount(save)
            if (!scaled.isRecycled) scaled.recycle()
        }

        // Save metadata + HD cutout PNG
        try {
            File(context.filesDir, AVATAR_META_FILE).writeText(meta.toJson())
        } catch (_: Throwable) {
        }
        val outFile = File(context.filesDir, AVATAR_OUT_FILE)
        FileOutputStream(outFile).use { fos ->
            result.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        result.recycle()
        return outFile.absolutePath
    }

    private fun clipSmoothPortraitMask(canvas: Canvas, w: Int, h: Int) {
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mc = Canvas(maskBmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.72f, 0f, h.toFloat(),
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        mc.drawOval(RectF(w * 0.08f, h * 0.04f, w * 0.92f, h * 0.96f), p)
        val dstIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBmp, 0f, 0f, dstIn)
        maskBmp.recycle()
    }

    /**
     * Studio-grade person silhouette mask with soft sub-pixel alpha feathering
     * and smooth bottom chest/shoulder fade so the cutout floats realistically on any background.
     */
    private fun runRealisticBustMask(bustBmp: Bitmap, outW: Int, outH: Int): Bitmap? {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val seg = Segmentation.getClient(opts)
        return try {
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(bustBmp, 0)), 3L, TimeUnit.SECONDS) ?: return null
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val rawAlpha = FloatArray(mw * mh)
            var fgCount = 0
            for (y in 0 until mh) {
                val ny = y.toFloat() / mh
                // Smooth realistic bust fade at the bottom 18% so shoulders blend softly into the air
                val bottomFade = when {
                    ny > 0.80f -> ((1f - ny) / 0.20f).coerceIn(0f, 1f)
                    else -> 1f
                }
                val smoothBottom = bottomFade * bottomFade * (3f - 2f * bottomFade)
                for (x in 0 until mw) {
                    val conf = buf.float
                    // S-curve soft feathering preserves fine hair edges cleanly without halo
                    val t = ((conf - 0.30f) / 0.42f).coerceIn(0f, 1f)
                    val smoothT = t * t * (3f - 2f * t)
                    val a = smoothT * smoothBottom
                    rawAlpha[y * mw + x] = a
                    if (a > 0.5f) fgCount++
                }
            }
            if (fgCount < mw * mh * 0.10f) return null

            // Lightweight 3x3 edge anti-aliasing pass on alpha channel
            val pixels = IntArray(mw * mh)
            for (y in 0 until mh) {
                for (x in 0 until mw) {
                    val c = rawAlpha[y * mw + x]
                    val blended = if (x in 1 until (mw - 1) && y in 1 until (mh - 1) && c > 0.02f && c < 0.98f) {
                        (c * 4f +
                            rawAlpha[y * mw + (x - 1)] +
                            rawAlpha[y * mw + (x + 1)] +
                            rawAlpha[(y - 1) * mw + x] +
                            rawAlpha[(y + 1) * mw + x]) / 8f
                    } else c
                    val aInt = (blended * 255f).toInt().coerceIn(0, 255)
                    pixels[y * mw + x] = Color.argb(aInt, 255, 255, 255)
                }
            }
            val rawMask = Bitmap.createBitmap(pixels, mw, mh, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(rawMask, outW, outH, true)
            if (rawMask !== scaled) rawMask.recycle()
            scaled
        } catch (_: Throwable) {
            null
        } finally {
            try { seg.close() } catch (_: Throwable) {}
        }
    }
}
