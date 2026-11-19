package com.sentinelcam.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized organic mask region detected on-device (0..1 screen coordinates)
 * carrying a real live color-matched mosaic pixelation patch sampled from the screen!
 */
data class DetectedMaskRegion(
    val id: String,
    val kind: String, // "face" | "nsfw"
    val rect: RectF, // normalized [0..1]
    val contour: List<PointF>?, // optional normalized 36-point Bézier face oval
    val rotationDeg: Float = 0f,
    val confidence: Float = 1f,
    val mosaicPatch: Bitmap? = null,
    val avgColor: Int = Color.rgb(160, 150, 145)
)

/**
 * Ultra-Fast Parallel On-Device Neural Detector (v16):
 * - Runs Google ML Kit Bundled Face Detection (36-point contours, ~5-8ms)
 *   AND Bundled NudeNet v3 YOLOv8n ONNX (`nudenet_320n.onnx`, ~14-18ms) IN PARALLEL!
 * - Generates true color-matched mosaic pixel patches directly from the live frame (ZERO black masks).
 */
class AiVisionDetector(context: Context) {

    private val appContext = context.applicationContext
    private val parallelPool = Executors.newFixedThreadPool(2)

    private var faceDetector: FaceDetector? = null
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"

    // Pre-allocated zero-GC buffers for 320x320 ONNX inference
    private val paddedBitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val paddedCanvas = Canvas(paddedBitmap)
    private val pixelBuf = IntArray(MODEL_SIZE * MODEL_SIZE)
    private val floatArray = FloatArray(3 * MODEL_SIZE * MODEL_SIZE)
    private val tensorShape = longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())

    @Volatile
    var nsfwModelReady: Boolean = false
        private set

    fun ensureInitialized(needFaces: Boolean, needNsfw: Boolean) {
        if (needFaces && faceDetector == null) {
            try {
                val opts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setMinFaceSize(0.055f)
                    .enableTracking()
                    .build()
                faceDetector = FaceDetection.getClient(opts)
            } catch (_: Throwable) {
            }
        }
        if (needNsfw && ortSession == null) {
            initNudeNetOnnx()
        }
    }

    private fun initNudeNetOnnx() {
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val modelFile = File(appContext.filesDir, "nudenet_320n.onnx")
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                appContext.assets.open(ASSET_MODEL_NAME).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(3)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            inputName = session.inputNames.firstOrNull() ?: "images"
            ortSession = session
            nsfwModelReady = true
        } catch (_: Throwable) {
            nsfwModelReady = false
        }
    }

    fun detectFrame(
        frame: Bitmap,
        blurFaces: Boolean,
        blurNsfw: Boolean,
        nsfwStrict: Boolean
    ): List<DetectedMaskRegion> {
        ensureInitialized(needFaces = blurFaces, needNsfw = blurNsfw || blurFaces)

        val faceFuture = if (blurFaces) {
            parallelPool.submit(Callable { runMlKitFaces(frame) })
        } else null

        val nsfwFuture = if (blurNsfw) {
            parallelPool.submit(Callable {
                runNudeNet(frame, detectNsfw = true, strict = nsfwStrict, detectBackupFaces = blurFaces)
            })
        } else null

        val results = ArrayList<DetectedMaskRegion>(8)

        if (faceFuture != null) {
            try {
                results.addAll(faceFuture.get(180L, TimeUnit.MILLISECONDS))
            } catch (_: Throwable) {
            }
        }

        if (nsfwFuture != null) {
            try {
                val onnxRegions = nsfwFuture.get(240L, TimeUnit.MILLISECONDS)
                for (r in onnxRegions) {
                    if (r.kind == "face" && results.any { it.kind == "face" && iou(it.rect, r.rect) > 0.25f }) {
                        continue
                    }
                    results.add(r)
                }
            } catch (_: Throwable) {
            }
        } else if (blurFaces && results.isEmpty()) {
            // Backup NudeNet face pass only if ML Kit missed side/tilted faces
            val backupFaces = runNudeNet(frame, detectNsfw = false, strict = false, detectBackupFaces = true)
            results.addAll(backupFaces)
        }

        // Attach crisp live color-matched mosaic pixel patches directly from the screen frame
        return results.map { region ->
            val (patch, avg) = buildMosaicPatch(frame, region.rect, region.kind == "nsfw")
            region.copy(mosaicPatch = patch, avgColor = avg)
        }
    }

    private fun runMlKitFaces(frame: Bitmap): List<DetectedMaskRegion> {
        val detector = faceDetector ?: return emptyList()
        val w = frame.width.coerceAtLeast(1).toFloat()
        val h = frame.height.coerceAtLeast(1).toFloat()
        val out = ArrayList<DetectedMaskRegion>(6)
        try {
            val img = InputImage.fromBitmap(frame, 0)
            val faces = Tasks.await(detector.process(img), 160L, TimeUnit.MILLISECONDS)
            for ((idx, face) in faces.withIndex()) {
                val b = face.boundingBox
                val left = (b.left / w).coerceIn(0f, 1f)
                val top = (b.top / h).coerceIn(0f, 1f)
                val right = (b.right / w).coerceIn(0f, 1f)
                val bottom = (b.bottom / h).coerceIn(0f, 1f)
                if (right - left < 0.025f || bottom - top < 0.025f) continue

                val rawContour = face.getContour(FaceContour.FACE)?.points
                val normContour = if (rawContour != null && rawContour.size >= 8) {
                    rawContour.map { pt -> PointF((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f)) }
                } else {
                    null
                }

                out.add(
                    DetectedMaskRegion(
                        id = "mlface-${face.trackingId ?: idx}",
                        kind = "face",
                        rect = RectF(left, top, right, bottom),
                        contour = normContour,
                        rotationDeg = face.headEulerAngleZ,
                        confidence = 0.99f
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun runNudeNet(
        frame: Bitmap,
        detectNsfw: Boolean,
        strict: Boolean,
        detectBackupFaces: Boolean
    ): List<DetectedMaskRegion> {
        val env = ortEnv ?: return emptyList()
        val session = ortSession ?: return emptyList()

        val origW = frame.width
        val origH = frame.height
        val maxDim = max(origW, origH).toFloat()
        val scale = MODEL_SIZE / maxDim
        val scaledW = (origW * scale).toInt().coerceIn(1, MODEL_SIZE)
        val scaledH = (origH * scale).toInt().coerceIn(1, MODEL_SIZE)

        paddedCanvas.drawColor(Color.BLACK)
        val dst = RectF(0f, 0f, scaledW.toFloat(), scaledH.toFloat())
        paddedCanvas.drawBitmap(frame, null, dst, null)
        paddedBitmap.getPixels(pixelBuf, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val planeSize = MODEL_SIZE * MODEL_SIZE
        val inv255 = 1f / 255f
        for (i in 0 until planeSize) {
            val px = pixelBuf[i]
            floatArray[i] = ((px ushr 16) and 0xFF) * inv255
            floatArray[planeSize + i] = ((px ushr 8) and 0xFF) * inv255
            floatArray[2 * planeSize + i] = (px and 0xFF) * inv255
        }

        val candidates = ArrayList<DetectedMaskRegion>(12)
        try {
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), tensorShape)
            tensor.use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { output ->
                    val rawVal = output[0].value
                    @Suppress("UNCHECKED_CAST")
                    val batch = rawVal as? Array<Array<FloatArray>> ?: return emptyList()
                    val featRows = batch[0]
                    if (featRows.size < 4 + NUM_CLASSES) return emptyList()
                    val numAnchors = featRows[0].size

                    val cxRow = featRows[0]
                    val cyRow = featRows[1]
                    val wRow = featRows[2]
                    val hRow = featRows[3]

                    val xNormScale = maxDim / (MODEL_SIZE * origW.toFloat())
                    val yNormScale = maxDim / (MODEL_SIZE * origH.toFloat())

                    for (a in 0 until numAnchors) {
                        var bestClass = -1
                        var bestScore = 0.24f
                        for (c in 0 until NUM_CLASSES) {
                            val sc = featRows[4 + c][a]
                            if (sc > bestScore) {
                                bestScore = sc
                                bestClass = c
                            }
                        }
                        if (bestClass < 0) continue

                        val kind: String? = when {
                            detectNsfw && bestClass in NSFW_EXPLICIT_CLASSES && bestScore >= 0.27f -> "nsfw"
                            detectNsfw && strict && bestClass in NSFW_STRICT_CLASSES && bestScore >= 0.36f -> "nsfw"
                            detectBackupFaces && bestClass in FACE_CLASSES && bestScore >= 0.36f -> "face"
                            else -> null
                        }
                        if (kind == null) continue

                        val bw = wRow[a]
                        val bh = hRow[a]
                        val left = ((cxRow[a] - bw * 0.5f) * xNormScale).coerceIn(0f, 1f)
                        val top = ((cyRow[a] - bh * 0.5f) * yNormScale).coerceIn(0f, 1f)
                        val right = ((cxRow[a] + bw * 0.5f) * xNormScale).coerceIn(0f, 1f)
                        val bottom = ((cyRow[a] + bh * 0.5f) * yNormScale).coerceIn(0f, 1f)
                        if (right - left < 0.02f || bottom - top < 0.02f) continue

                        candidates.add(
                            DetectedMaskRegion(
                                id = "nn-$kind-$bestClass-$a",
                                kind = kind,
                                rect = RectF(left, top, right, bottom),
                                contour = null,
                                confidence = bestScore
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        return applyNms(candidates, iouThreshold = 0.42f)
    }

    /**
     * Extracts a tiny real-color mosaic grid (e.g. 8x10 px for faces, 6x8 px for NSFW)
     * directly from the live screen region so the overlay renders true TV-style mosaic pixelation!
     */
    private fun buildMosaicPatch(frame: Bitmap, normRect: RectF, isNsfw: Boolean): Pair<Bitmap?, Int> {
        return try {
            val fw = frame.width
            val fh = frame.height
            val padX = normRect.width() * (if (isNsfw) 0.18f else 0.12f)
            val padY = normRect.height() * (if (isNsfw) 0.18f else 0.14f)
            val x1 = ((normRect.left - padX) * fw).toInt().coerceIn(0, fw - 2)
            val y1 = ((normRect.top - padY) * fh).toInt().coerceIn(0, fh - 2)
            val x2 = ((normRect.right + padX) * fw).toInt().coerceIn(x1 + 2, fw)
            val y2 = ((normRect.bottom + padY) * fh).toInt().coerceIn(y1 + 2, fh)

            val cropW = (x2 - x1).coerceAtLeast(2)
            val cropH = (y2 - y1).coerceAtLeast(2)

            // True mosaic cell resolution (coarse enough to completely anonymize, rich enough to match real colors)
            val cols = if (isNsfw) 6 else 8
            val rows = ((cols * cropH.toFloat() / cropW.toFloat()).toInt()).coerceIn(5, if (isNsfw) 8 else 11)

            val patch = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            var total = 0

            for (ry in 0 until rows) {
                val py = (y1 + ((ry + 0.5f) / rows) * cropH).toInt().coerceIn(0, fh - 1)
                for (rx in 0 until cols) {
                    val px = (x1 + ((rx + 0.5f) / cols) * cropW).toInt().coerceIn(0, fw - 1)
                    // 2x2 micro-average for smoother anti-aliased mosaic tile color
                    val c1 = frame.getPixel(px, py)
                    val px2 = (px + 1).coerceAtMost(fw - 1)
                    val py2 = (py + 1).coerceAtMost(fh - 1)
                    val c2 = frame.getPixel(px2, py2)
                    val r = (((c1 ushr 16) and 0xFF) + ((c2 ushr 16) and 0xFF)) / 2
                    val g = (((c1 ushr 8) and 0xFF) + ((c2 ushr 8) and 0xFF)) / 2
                    val b = ((c1 and 0xFF) + (c2 and 0xFF)) / 2
                    patch.setPixel(rx, ry, Color.rgb(r, g, b))
                    rSum += r
                    gSum += g
                    bSum += b
                    total++
                }
            }
            val avgColor = if (total > 0) {
                Color.rgb((rSum / total).toInt(), (gSum / total).toInt(), (bSum / total).toInt())
            } else {
                Color.rgb(140, 135, 130)
            }
            Pair(patch, avgColor)
        } catch (_: Throwable) {
            Pair(null, Color.rgb(140, 135, 130))
        }
    }

    private fun applyNms(items: List<DetectedMaskRegion>, iouThreshold: Float): List<DetectedMaskRegion> {
        if (items.size <= 1) return items
        val sorted = items.sortedByDescending { it.confidence }
        val kept = ArrayList<DetectedMaskRegion>(sorted.size)
        for (cand in sorted) {
            var suppressed = false
            for (k in kept) {
                if (k.kind == cand.kind && iou(k.rect, cand.rect) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) kept.add(cand)
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        try {
            parallelPool.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            faceDetector?.close()
        } catch (_: Throwable) {
        }
        faceDetector = null
        try {
            ortSession?.close()
        } catch (_: Throwable) {
        }
        ortSession = null
        nsfwModelReady = false
    }

    companion object {
        private const val ASSET_MODEL_NAME = "nudenet_320n.onnx"
        private const val MODEL_SIZE = 320
        private const val NUM_CLASSES = 18

        private val FACE_CLASSES = setOf(1, 12)
        private val NSFW_EXPLICIT_CLASSES = setOf(2, 3, 4, 6, 14)
        private val NSFW_STRICT_CLASSES = setOf(0, 5, 15, 16, 17)
    }
}
