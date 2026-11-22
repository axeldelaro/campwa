package com.demonpet.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.demonpet.app.media.MediaFolderHelper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Local AI Face / Head Cutout Engine:
 * - Takes any portrait photo from gallery.
 * - Detects exact face contour (ML Kit 36-pt Bézier) AND person silhouette (ML Kit Selfie Segmentation).
 * - Produces a crisp transparent 320x320 PNG of the real person's head (with or without hair)
 *   ready to animate as the DemonMom avatar AND stamp in #2 Face-Swap mode!
 */
object AvatarCutoutEngine {

    private const val AVATAR_OUT_FILE = "demon_avatar_head.png"
    private const val AVATAR_RAW_FILE = "demon_avatar_source.jpg"

    fun createAvatarFromUri(context: Context, uri: Uri, includeHair: Boolean): String? {
        val source = MediaFolderHelper.decodeSampledUri(context, uri, 960) ?: return null
        return try {
            // Save source copy so user can toggle "Inclure cheveux" later without re-picking!
            val rawFile = File(context.filesDir, AVATAR_RAW_FILE)
            FileOutputStream(rawFile).use { fos ->
                source.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            cutoutFromBitmap(context, source, includeHair)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    fun rebuildFromSavedSource(context: Context, includeHair: Boolean): String? {
        val rawFile = File(context.filesDir, AVATAR_RAW_FILE)
        if (!rawFile.exists()) return null
        val source = MediaFolderHelper.decodeSampledFile(rawFile, 960) ?: return null
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

        val outSize = 320
        val result = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (bestFace != null) {
            val box = bestFace.boundingBox
            val bw = box.width().coerceAtLeast(20).toFloat()
            val bh = box.height().coerceAtLeast(20).toFloat()

            val padLeft = if (includeHair) bw * 0.34f else bw * 0.14f
            val padRight = if (includeHair) bw * 0.34f else bw * 0.14f
            val padTop = if (includeHair) bh * 0.48f else bh * 0.16f
            val padBot = if (includeHair) bh * 0.14f else bh * 0.10f

            val cropL = (box.left - padLeft).toInt().coerceIn(0, sw - 4)
            val cropT = (box.top - padTop).toInt().coerceIn(0, sh - 4)
            val cropR = (box.right + padRight).toInt().coerceIn(cropL + 4, sw)
            val cropB = (box.bottom + padBot).toInt().coerceIn(cropT + 4, sh)
            val cw = (cropR - cropL).coerceAtLeast(4)
            val ch = (cropB - cropT).coerceAtLeast(4)

            // Fit square-centered crop preserving aspect ratio
            val side = max(cw, ch)
            val cx = (cropL + cropR) / 2
            val cy = (cropT + cropB) / 2
            val sqL = (cx - side / 2).coerceIn(0, max(0, sw - side))
            val sqT = (cy - side / 2).coerceIn(0, max(0, sh - side))
            val sqR = min(sw, sqL + side)
            val sqB = min(sh, sqT + side)

            val headCrop = Bitmap.createBitmap(source, sqL, sqT, (sqR - sqL).coerceAtLeast(4), (sqB - sqT).coerceAtLeast(4))
            val scaledHead = Bitmap.createScaledBitmap(headCrop, outSize, outSize, true)
            if (headCrop !== scaledHead) headCrop.recycle()

            if (includeHair) {
                // Combine Selfie Segmentation mask with clean oval collar crop so hair looks razor-sharp!
                val segMask = runHeadSilhouetteMask(scaledHead, outSize)
                val save = canvas.saveLayer(0f, 0f, outSize.toFloat(), outSize.toFloat(), null)
                canvas.drawBitmap(scaledHead, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                if (segMask != null) {
                    val dstIn = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    canvas.drawBitmap(segMask, 0f, 0f, dstIn)
                    segMask.recycle()
                } else {
                    clipSmoothOval(canvas, outSize)
                }
                canvas.restoreToCount(save)
            } else {
                // Tight 36-point ML Kit face contour Bézier cutout!
                val contour = bestFace.getContour(FaceContour.FACE)?.points
                if (contour != null && contour.size >= 8) {
                    val path = Path()
                    val scaleX = outSize.toFloat() / (sqR - sqL).coerceAtLeast(1)
                    val scaleY = outSize.toFloat() / (sqB - sqT).coerceAtLeast(1)
                    val pts = contour.map { pt ->
                        Pair((pt.x - sqL) * scaleX, (pt.y - sqT) * scaleY)
                    }
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
                    canvas.drawBitmap(scaledHead, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                } else {
                    canvas.drawBitmap(scaledHead, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    clipSmoothOval(canvas, outSize)
                }
            }
            if (!scaledHead.isRecycled) scaledHead.recycle()
        } else {
            // No face found: clean center-cropped circular portrait fallback
            val side = min(sw, sh)
            val l = (sw - side) / 2
            val t = (sh - side) / 2
            val path = Path().apply { addOval(RectF(8f, 8f, outSize - 8f, outSize - 8f), Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(
                source,
                Rect(l, t, l + side, t + side),
                Rect(0, 0, outSize, outSize),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            canvas.restore()
        }

        val outFile = File(context.filesDir, AVATAR_OUT_FILE)
        FileOutputStream(outFile).use { fos ->
            result.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        result.recycle()
        return outFile.absolutePath
    }

    private fun clipSmoothOval(canvas: Canvas, size: Int) {
        val maskBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val mc = Canvas(maskBmp)
        mc.drawOval(RectF(size * 0.08f, size * 0.05f, size * 0.92f, size * 0.95f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        val dstIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBmp, 0f, 0f, dstIn)
        maskBmp.recycle()
    }

    private fun runHeadSilhouetteMask(headBmp: Bitmap, outSize: Int): Bitmap? {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val seg = Segmentation.getClient(opts)
        return try {
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(headBmp, 0)), 3L, TimeUnit.SECONDS) ?: return null
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val pixels = IntArray(mw * mh)
            var fgCount = 0
            for (y in 0 until mh) {
                val ny = y.toFloat() / mh
                // Softly taper bottom edge so shoulders never look abruptly chopped
                val bottomFade = if (ny > 0.84f) ((1f - ny) / 0.16f).coerceIn(0f, 1f) else 1f
                for (x in 0 until mw) {
                    val conf = buf.float
                    val alpha = (((conf - 0.35f) / 0.35f).coerceIn(0f, 1f) * bottomFade * 255f).toInt()
                    if (alpha > 120) fgCount++
                    pixels[y * mw + x] = Color.argb(alpha, 255, 255, 255)
                }
            }
            if (fgCount < mw * mh * 0.12f) return null
            val rawMask = Bitmap.createBitmap(pixels, mw, mh, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(rawMask, outSize, outSize, true)
            if (rawMask !== scaled) rawMask.recycle()
            scaled
        } catch (_: Throwable) {
            null
        } finally {
            try { seg.close() } catch (_: Throwable) {}
        }
    }
}
