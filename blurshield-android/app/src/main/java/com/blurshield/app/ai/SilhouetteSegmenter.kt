package com.blurshield.app.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

data class SilhouetteResult(
    val maskWidth: Int,
    val maskHeight: Int,
    val confidences: FloatArray,
    val personBoundsNorm: RectF?,
    val coverageRatio: Float
) {
    /**
     * Extracts a crisp anti-aliased ARGB alpha mask for any normalized sub-rectangle
     * (used to clip NSFW / face boxes strictly to the real pixel-accurate body/hair silhouette!).
     */
    fun extractRegionAlphaMask(
        normRect: RectF,
        padFrac: Float,
        featherPct: Int
    ): Bitmap? {
        val l = (normRect.left - normRect.width() * padFrac).coerceIn(0f, 1f)
        val t = (normRect.top - normRect.height() * padFrac).coerceIn(0f, 1f)
        val r = (normRect.right + normRect.width() * padFrac).coerceIn(l + 0.01f, 1f)
        val b = (normRect.bottom + normRect.height() * padFrac).coerceIn(t + 0.01f, 1f)

        val x1 = (l * maskWidth).toInt().coerceIn(0, maskWidth - 2)
        val y1 = (t * maskHeight).toInt().coerceIn(0, maskHeight - 2)
        val x2 = (r * maskWidth).toInt().coerceIn(x1 + 2, maskWidth)
        val y2 = (b * maskHeight).toInt().coerceIn(y1 + 2, maskHeight)
        val cw = (x2 - x1).coerceAtLeast(2)
        val ch = (y2 - y1).coerceAtLeast(2)

        val pixels = IntArray(cw * ch)
        val low = 0.34f - (featherPct / 100f) * 0.14f
        val high = 0.62f + (featherPct / 100f) * 0.22f
        val invSpan = 1f / (high - low).coerceAtLeast(0.08f)

        var fgCount = 0
        for (yy in 0 until ch) {
            val srcRow = (y1 + yy) * maskWidth
            val dstRow = yy * cw
            for (xx in 0 until cw) {
                val conf = confidences[srcRow + x1 + xx]
                val a = (((conf - low) * invSpan).coerceIn(0f, 1f) * 255f).toInt()
                if (a > 90) fgCount++
                pixels[dstRow + xx] = Color.argb(a, 255, 255, 255)
            }
        }
        val ratio = fgCount.toFloat() / (cw * ch).toFloat()
        // If silhouette detector didn't catch this sub-region (e.g. tiny background person), return null to fall back cleanly
        if (ratio < 0.08f) return null
        return Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /**
     * Combines an existing skin-tone alpha mask with the person silhouette alpha mask
     * for maximum precision: hair/body boundaries + exact skin edges!
     */
    fun intersectWithMask(
        otherMask: Bitmap,
        normRect: RectF,
        padFrac: Float
    ): Bitmap {
        val mw = otherMask.width
        val mh = otherMask.height
        val l = (normRect.left - normRect.width() * padFrac).coerceIn(0f, 1f)
        val t = (normRect.top - normRect.height() * padFrac).coerceIn(0f, 1f)
        val w = (normRect.width() * (1f + padFrac * 2f)).coerceAtLeast(0.01f)
        val h = (normRect.height() * (1f + padFrac * 2f)).coerceAtLeast(0.01f)

        val px = IntArray(mw * mh)
        otherMask.getPixels(px, 0, mw, 0, 0, mw, mh)
        var kept = 0
        for (y in 0 until mh) {
            val ny = (t + ((y + 0.5f) / mh) * h).coerceIn(0f, 0.999f)
            val sy = (ny * maskHeight).toInt().coerceIn(0, maskHeight - 1)
            val sRow = sy * maskWidth
            val dRow = y * mw
            for (x in 0 until mw) {
                val nx = (l + ((x + 0.5f) / mw) * w).coerceIn(0f, 0.999f)
                val sx = (nx * maskWidth).toInt().coerceIn(0, maskWidth - 1)
                val sil = ((confidences[sRow + sx] - 0.25f) / 0.35f).coerceIn(0f, 1f)
                val origA = (px[dRow + x] ushr 24) and 0xFF
                val mergedA = (origA * sil).toInt().coerceIn(0, 255)
                if (mergedA > 80) kept++
                px[dRow + x] = Color.argb(mergedA, 255, 255, 255)
            }
        }
        // Only apply intersection if silhouette actually overlapped with this box
        if (kept < (mw * mh * 0.04f)) return otherMask
        otherMask.setPixels(px, 0, mw, 0, 0, mw, mh)
        return otherMask
    }
}

/**
 * Feature #1: Real-Time Pixel-Accurate Person/Body Silhouette Segmenter (ML Kit Selfie Segmentation).
 * Runs in STREAM_MODE in ~8–14 ms on-device in parallel with Face + NudeNet ONNX.
 */
class SilhouetteSegmenter {

    private var segmenter: Segmenter? = null

    private fun ensureInit() {
        if (segmenter == null) {
            try {
                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build()
                segmenter = Segmentation.getClient(options)
            } catch (_: Throwable) {
            }
        }
    }

    fun segment(frame: Bitmap): SilhouetteResult? {
        ensureInit()
        val client = segmenter ?: return null
        return try {
            val img = InputImage.fromBitmap(frame, 0)
            val mask = Tasks.await(client.process(img), 120L, TimeUnit.MILLISECONDS) ?: return null
            val mw = mask.width
            val mh = mask.height
            if (mw < 8 || mh < 8) return null
            val buf = mask.buffer
            buf.rewind()
            val total = mw * mh
            val conf = FloatArray(total)
            var minX = mw
            var minY = mh
            var maxX = -1
            var maxY = -1
            var fgCount = 0

            for (y in 0 until mh) {
                val row = y * mw
                for (x in 0 until mw) {
                    val v = buf.float
                    conf[row + x] = v
                    if (v >= 0.50f) {
                        fgCount++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val ratio = fgCount.toFloat() / total.toFloat()
            val bounds = if (fgCount > 24 && maxX > minX && maxY > minY) {
                RectF(
                    (minX.toFloat() / mw).coerceIn(0f, 1f),
                    (minY.toFloat() / mh).coerceIn(0f, 1f),
                    ((maxX + 1).toFloat() / mw).coerceIn(0f, 1f),
                    ((maxY + 1).toFloat() / mh).coerceIn(0f, 1f)
                )
            } else {
                null
            }
            SilhouetteResult(mw, mh, conf, bounds, ratio)
        } catch (_: Throwable) {
            null
        }
    }

    fun close() {
        try { segmenter?.close() } catch (_: Throwable) {}
        segmenter = null
    }
}
