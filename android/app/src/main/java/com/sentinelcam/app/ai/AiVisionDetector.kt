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
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized organic mask region detected on-device (0..1 screen coordinates).
 * Uses either a smooth 36-point face contour polygon or a soft organic ellipse.
 */
data class DetectedMaskRegion(
    val id: String,
    val kind: String, // "face" | "nsfw"
    val rect: RectF, // normalized [0..1]
    val contour: List<PointF>?, // optional normalized polygon (e.g. ML Kit 36-pt face oval)
    val rotationDeg: Float = 0f,
    val confidence: Float = 1f
)

/**
 * Dual On-Device Local Neural Detector (100% Free & Offline):
 * 1. Google ML Kit Bundled Face Detection (36-point organic face contours, ~8 ms)
 * 2. Bundled NudeNet v3 YOLOv8n (`nudenet_320n.onnx`, 12 MB) via ONNX Runtime ARM64 NEON (~20 ms)
 */
class AiVisionDetector(context: Context) {

    private val appContext = context.applicationContext

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

            // Copy model asset once to cache file so ONNX Runtime can mmap directly without heap blowup
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

    /**
     * Runs face contour detection + NudeNet YOLOv8n NSFW detection on the frame.
     * Returns normalized [0..1] organic regions ready for smooth 60 FPS overlay rendering.
     */
    fun detectFrame(
        frame: Bitmap,
        blurFaces: Boolean,
        blurNsfw: Boolean,
        nsfwStrict: Boolean
    ): List<DetectedMaskRegion> {
        val w = frame.width.coerceAtLeast(1).toFloat()
        val h = frame.height.coerceAtLeast(1).toFloat()
        val results = ArrayList<DetectedMaskRegion>(8)

        // 1. ML Kit Bundled Face Detection (with smooth 36-point face contour polygon!)
        if (blurFaces) {
            ensureInitialized(needFaces = true, needNsfw = false)
            val detector = faceDetector
            if (detector != null) {
                try {
                    val img = InputImage.fromBitmap(frame, 0)
                    val faces = Tasks.await(detector.process(img), 220L, TimeUnit.MILLISECONDS)
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

                        results.add(
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
            }
        }

        // 2. Bundled NudeNet v3 YOLOv8n ONNX (exact NSFW regions + backup side-angle faces)
        if (blurNsfw || (blurFaces && results.isEmpty())) {
            ensureInitialized(needFaces = false, needNsfw = true)
            val onnxRegions = runNudeNet(frame, detectNsfw = blurNsfw, strict = nsfwStrict, detectBackupFaces = blurFaces)
            for (r in onnxRegions) {
                // Skip backup NudeNet face if ML Kit already covered it with a higher-precision contour
                if (r.kind == "face" && results.any { it.kind == "face" && iou(it.rect, r.rect) > 0.25f }) {
                    continue
                }
                results.add(r)
            }
        }

        return results
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

        // Exact NudeNet top-left letterbox padding with black border
        paddedCanvas.drawColor(Color.BLACK)
        val dst = RectF(0f, 0f, scaledW.toFloat(), scaledH.toFloat())
        paddedCanvas.drawBitmap(frame, null, dst, null)
        paddedBitmap.getPixels(pixelBuf, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        // Pack into CHW [1, 3, 320, 320] float32 normalized [0..1]
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
                    val featRows = batch[0] // shape [22, 2100]
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

        // Exact NudeNet v3 label indices:
        // 0: FEMALE_GENITALIA_COVERED, 1: FACE_FEMALE, 2: BUTTOCKS_EXPOSED,
        // 3: FEMALE_BREAST_EXPOSED, 4: FEMALE_GENITALIA_EXPOSED, 5: MALE_BREAST_EXPOSED,
        // 6: ANUS_EXPOSED, 12: FACE_MALE, 14: MALE_GENITALIA_EXPOSED,
        // 15: ANUS_COVERED, 16: FEMALE_BREAST_COVERED, 17: BUTTOCKS_COVERED
        private val FACE_CLASSES = setOf(1, 12)
        private val NSFW_EXPLICIT_CLASSES = setOf(2, 3, 4, 6, 14)
        private val NSFW_STRICT_CLASSES = setOf(0, 5, 15, 16, 17)
    }
}
