package com.blurshield.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.FaceGenderMode
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

data class BlurRegion(
    val id: String,
    val kind: String, // "face" | "nsfw"
    val rect: RectF, // normalized [0..1]
    val contour: List<PointF>?,
    val rotationDeg: Float = 0f,
    val confidence: Float = 1f,
    val patch: Bitmap? = null,
    val avgColor: Int = Color.rgb(130, 128, 125)
)

data class DetectionFrameResult(
    val regions: List<BlurRegion>,
    val inferenceMs: Long
)

/**
 * High-Performance Parallel Neural Vision Engine for BlurShield AI:
 * - Concurrent ML Kit Face Contours (36-point Bézier) + NudeNet v3 YOLOv8n ONNX (4 threads + ALL_OPT).
 * - Granular zone filtering (Faces by gender, Genitalia, Breasts, Buttocks, Belly, Covered sensitive).
 * - Screen Region-of-Interest (ROI) filtering + live Gaussian / Mosaic / Frosted / Solid patch generation.
 */
class BlurVisionEngine(context: Context) {

    private val appContext = context.applicationContext
    private val parallelPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "BlurShieldAI").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    private var faceDetector: FaceDetector? = null
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"

    private val paddedBitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val paddedCanvas = Canvas(paddedBitmap)
    private val pixelBuf = IntArray(MODEL_SIZE * MODEL_SIZE)
    private val floatArray = FloatArray(3 * MODEL_SIZE * MODEL_SIZE)
    private val tensorShape = longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())

    @Volatile
    var onnxReady: Boolean = false
        private set

    fun ensureInitialized(needFaces: Boolean, needOnnx: Boolean) {
        if (needFaces && faceDetector == null) {
            try {
                val opts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setMinFaceSize(0.045f)
                    .enableTracking()
                    .build()
                faceDetector = FaceDetection.getClient(opts)
            } catch (_: Throwable) {
            }
        }
        if (needOnnx && ortSession == null) {
            initOnnx()
        }
    }

    private fun initOnnx() {
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val modelFile = File(appContext.filesDir, ASSET_MODEL_NAME)
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                appContext.assets.open(ASSET_MODEL_NAME).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val cpuCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(min(4, cpuCores))
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                } catch (_: Throwable) {
                }
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            inputName = session.inputNames.firstOrNull() ?: "images"
            ortSession = session
            onnxReady = true
        } catch (_: Throwable) {
            onnxReady = false
        }
    }

    fun detectFrame(frame: Bitmap, cfg: BlurConfigSnapshot): DetectionFrameResult {
        val t0 = System.nanoTime()
        if (!cfg.anyDetectionEnabled) {
            return DetectionFrameResult(emptyList(), 0L)
        }

        val needMlKitFaces = cfg.blurFaces && cfg.faceGenderMode == FaceGenderMode.ALL
        val needOnnx = cfg.anyNsfwEnabled || cfg.blurFaces
        ensureInitialized(needFaces = needMlKitFaces, needOnnx = needOnnx)

        val faceFuture = if (needMlKitFaces) {
            parallelPool.submit(Callable { runMlKitFaces(frame) })
        } else null

        val runOnnxInParallel = cfg.anyNsfwEnabled || (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.ALL)
        val onnxFuture = if (runOnnxInParallel) {
            parallelPool.submit(Callable { runNudeNet(frame, cfg) })
        } else null

        val merged = ArrayList<BlurRegion>(10)

        if (faceFuture != null) {
            try {
                merged.addAll(faceFuture.get(150L, TimeUnit.MILLISECONDS))
            } catch (_: Throwable) {
            }
        }

        if (onnxFuture != null) {
            try {
                val onnxRegions = onnxFuture.get(220L, TimeUnit.MILLISECONDS)
                for (r in onnxRegions) {
                    if (r.kind == "face" && merged.any { it.kind == "face" && iou(it.rect, r.rect) > 0.25f }) {
                        continue
                    }
                    merged.add(r)
                }
            } catch (_: Throwable) {
            }
        } else if (cfg.blurFaces && merged.isEmpty()) {
            // Backup ONNX face pass for side-profile or occluded faces missed by ML Kit
            merged.addAll(runNudeNet(frame, cfg))
        }

        // Apply Screen ROI (Region of Interest) + optional system bar exclusion
        val minY = max(cfg.roiTopPct / 100f, if (cfg.excludeSystemBars) 0.032f else 0f)
        val maxY = min(cfg.roiBottomPct / 100f, if (cfg.excludeSystemBars) 0.965f else 1f)

        val filtered = merged.filter { region ->
            val cy = region.rect.centerY()
            cy in minY..maxY
        }

        // Generate style-specific live color/blur patches directly from the live frame
        val withPatches = filtered.map { region ->
            val (patch, avg) = buildStyledPatch(frame, region.rect, region.kind == "nsfw", cfg)
            region.copy(patch = patch, avgColor = avg)
        }

        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        return DetectionFrameResult(withPatches, elapsedMs)
    }

    private fun runMlKitFaces(frame: Bitmap): List<BlurRegion> {
        val detector = faceDetector ?: return emptyList()
        val w = frame.width.coerceAtLeast(1).toFloat()
        val h = frame.height.coerceAtLeast(1).toFloat()
        val out = ArrayList<BlurRegion>(6)
        try {
            val img = InputImage.fromBitmap(frame, 0)
            val faces = Tasks.await(detector.process(img), 140L, TimeUnit.MILLISECONDS)
            for ((idx, face) in faces.withIndex()) {
                val b = face.boundingBox
                val left = (b.left / w).coerceIn(0f, 1f)
                val top = (b.top / h).coerceIn(0f, 1f)
                val right = (b.right / w).coerceIn(0f, 1f)
                val bottom = (b.bottom / h).coerceIn(0f, 1f)
                if (right - left < 0.022f || bottom - top < 0.022f) continue

                val rawContour = face.getContour(FaceContour.FACE)?.points
                val normContour = if (rawContour != null && rawContour.size >= 8) {
                    rawContour.map { pt -> PointF((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f)) }
                } else {
                    null
                }

                out.add(
                    BlurRegion(
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

    private fun runNudeNet(frame: Bitmap, cfg: BlurConfigSnapshot): List<BlurRegion> {
        val env = ortEnv ?: return emptyList()
        val session = ortSession ?: return emptyList()

        val origW = frame.width
        val origH = frame.height
        val maxDim = max(origW, origH).toFloat()
        val scale = MODEL_SIZE / maxDim
        val scaledW = (origW * scale).toInt().coerceIn(1, MODEL_SIZE)
        val scaledH = (origH * scale).toInt().coerceIn(1, MODEL_SIZE)

        paddedCanvas.drawColor(Color.BLACK)
        paddedCanvas.drawBitmap(frame, null, RectF(0f, 0f, scaledW.toFloat(), scaledH.toFloat()), null)
        paddedBitmap.getPixels(pixelBuf, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val planeSize = MODEL_SIZE * MODEL_SIZE
        val inv255 = 1f / 255f
        for (i in 0 until planeSize) {
            val px = pixelBuf[i]
            floatArray[i] = ((px ushr 16) and 0xFF) * inv255
            floatArray[planeSize + i] = ((px ushr 8) and 0xFF) * inv255
            floatArray[2 * planeSize + i] = (px and 0xFF) * inv255
        }

        val sens = cfg.sensitivityMode
        val minAny = minOf(sens.explicitThresh, sens.strictThresh, sens.faceThresh)
        val candidates = ArrayList<BlurRegion>(12)

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
                        var bestScore = minAny
                        for (c in 0 until NUM_CLASSES) {
                            val sc = featRows[4 + c][a]
                            if (sc > bestScore) {
                                bestScore = sc
                                bestClass = c
                            }
                        }
                        if (bestClass < 0) continue

                        val kind = classifyEnabledClass(bestClass, bestScore, cfg) ?: continue

                        val bw = wRow[a]
                        val bh = hRow[a]
                        val left = ((cxRow[a] - bw * 0.5f) * xNormScale).coerceIn(0f, 1f)
                        val top = ((cyRow[a] - bh * 0.5f) * yNormScale).coerceIn(0f, 1f)
                        val right = ((cxRow[a] + bw * 0.5f) * xNormScale).coerceIn(0f, 1f)
                        val bottom = ((cyRow[a] + bh * 0.5f) * yNormScale).coerceIn(0f, 1f)
                        if (right - left < 0.02f || bottom - top < 0.02f) continue

                        candidates.add(
                            BlurRegion(
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

    private fun classifyEnabledClass(cls: Int, score: Float, cfg: BlurConfigSnapshot): String? {
        val sens = cfg.sensitivityMode
        return when (cls) {
            // Faces
            CLS_FACE_FEMALE -> if (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.MALE_ONLY && score >= sens.faceThresh) "face" else null
            CLS_FACE_MALE -> if (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.FEMALE_ONLY && score >= sens.faceThresh) "face" else null

            // Genitalia / Anus
            CLS_GENITALIA_F_EXP, CLS_GENITALIA_M_EXP, CLS_ANUS_EXP ->
                if (cfg.blurGenitalia && score >= sens.explicitThresh) "nsfw" else null
            CLS_GENITALIA_F_COV, CLS_ANUS_COV ->
                if (cfg.blurGenitalia && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            // Breasts / Chest
            CLS_BREAST_F_EXP ->
                if (cfg.blurBreasts && score >= sens.explicitThresh) "nsfw" else null
            CLS_BREAST_F_COV, CLS_BREAST_M_EXP ->
                if (cfg.blurBreasts && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            // Buttocks
            CLS_BUTTOCKS_EXP ->
                if (cfg.blurButtocks && score >= sens.explicitThresh) "nsfw" else null
            CLS_BUTTOCKS_COV ->
                if (cfg.blurButtocks && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            // Belly
            CLS_BELLY_EXP ->
                if (cfg.blurBelly && score >= sens.explicitThresh) "nsfw" else null
            CLS_BELLY_COV ->
                if (cfg.blurBelly && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            else -> null
        }
    }

    private fun buildStyledPatch(
        frame: Bitmap,
        normRect: RectF,
        isNsfw: Boolean,
        cfg: BlurConfigSnapshot
    ): Pair<Bitmap?, Int> {
        val fw = frame.width
        val fh = frame.height
        val padFrac = (cfg.maskPaddingPct / 100f) + (if (isNsfw) 0.04f else 0f)
        val x1 = ((normRect.left - normRect.width() * padFrac) * fw).toInt().coerceIn(0, fw - 2)
        val y1 = ((normRect.top - normRect.height() * padFrac) * fh).toInt().coerceIn(0, fh - 2)
        val x2 = ((normRect.right + normRect.width() * padFrac) * fw).toInt().coerceIn(x1 + 2, fw)
        val y2 = ((normRect.bottom + normRect.height() * padFrac) * fh).toInt().coerceIn(y1 + 2, fh)
        val cropW = (x2 - x1).coerceAtLeast(2)
        val cropH = (y2 - y1).coerceAtLeast(2)

        if (cfg.blurStyle == BlurStyle.SOLID_BLACK) {
            return Pair(null, Color.rgb(12, 12, 16))
        }

        val avgColor = sampleAverageColor(frame, x1, y1, cropW, cropH)
        if (cfg.blurStyle == BlurStyle.SOLID_COLOR) {
            return Pair(null, avgColor)
        }

        return try {
            when (cfg.blurStyle) {
                BlurStyle.MOSAIC -> {
                    // Higher blurIntensity (1..10) = bigger mosaic blocks (fewer columns)
                    val cols = (14 - cfg.blurIntensity).coerceIn(3, 12)
                    val rows = ((cols * cropH.toFloat() / cropW.toFloat()).toInt()).coerceIn(3, 14)
                    val patch = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
                    for (ry in 0 until rows) {
                        val py = (y1 + ((ry + 0.5f) / rows) * cropH).toInt().coerceIn(0, fh - 1)
                        for (rx in 0 until cols) {
                            val px = (x1 + ((rx + 0.5f) / cols) * cropW).toInt().coerceIn(0, fw - 1)
                            patch.setPixel(rx, ry, frame.getPixel(px, py))
                        }
                    }
                    Pair(patch, avgColor)
                }

                BlurStyle.GAUSSIAN, BlurStyle.FROSTED -> {
                    val targetW = 36
                    val targetH = ((targetW * cropH.toFloat() / cropW.toFloat()).toInt()).coerceIn(24, 52)
                    val cropped = Bitmap.createBitmap(frame, x1, y1, cropW, cropH)
                    val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                    if (cropped !== scaled && !cropped.isRecycled) cropped.recycle()
                    val radius = (3 + cfg.blurIntensity * 1.4f).toInt().coerceIn(4, 18)
                    FastStackBlur.blurInPlace(scaled, radius)
                    Pair(scaled, avgColor)
                }

                else -> Pair(null, avgColor)
            }
        } catch (_: Throwable) {
            Pair(null, avgColor)
        }
    }

    private fun sampleAverageColor(frame: Bitmap, x1: Int, y1: Int, w: Int, h: Int): Int {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var cnt = 0
        val fw = frame.width
        val fh = frame.height
        for (gy in 0 until 4) {
            val py = (y1 + ((gy + 0.5f) / 4f) * h).toInt().coerceIn(0, fh - 1)
            for (gx in 0 until 4) {
                val px = (x1 + ((gx + 0.5f) / 4f) * w).toInt().coerceIn(0, fw - 1)
                val c = frame.getPixel(px, py)
                rSum += (c ushr 16) and 0xFF
                gSum += (c ushr 8) and 0xFF
                bSum += c and 0xFF
                cnt++
            }
        }
        return if (cnt > 0) Color.rgb((rSum / cnt).toInt(), (gSum / cnt).toInt(), (bSum / cnt).toInt())
        else Color.rgb(120, 120, 125)
    }

    private fun applyNms(items: List<BlurRegion>, iouThreshold: Float): List<BlurRegion> {
        if (items.size <= 1) return items
        val sorted = items.sortedByDescending { it.confidence }
        val kept = ArrayList<BlurRegion>(sorted.size)
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
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        try { parallelPool.shutdownNow() } catch (_: Throwable) {}
        try { faceDetector?.close() } catch (_: Throwable) {}
        faceDetector = null
        try { ortSession?.close() } catch (_: Throwable) {}
        ortSession = null
        onnxReady = false
    }

    companion object {
        private const val ASSET_MODEL_NAME = "nudenet_320n.onnx"
        private const val MODEL_SIZE = 320
        private const val NUM_CLASSES = 18

        // Official NudeNet v3 18-class taxonomy
        private const val CLS_GENITALIA_F_COV = 0
        private const val CLS_FACE_FEMALE = 1
        private const val CLS_BUTTOCKS_EXP = 2
        private const val CLS_BREAST_F_EXP = 3
        private const val CLS_GENITALIA_F_EXP = 4
        private const val CLS_BREAST_M_EXP = 5
        private const val CLS_ANUS_EXP = 6
        private const val CLS_BELLY_COV = 8
        private const val CLS_FACE_MALE = 12
        private const val CLS_BELLY_EXP = 13
        private const val CLS_GENITALIA_M_EXP = 14
        private const val CLS_ANUS_COV = 15
        private const val CLS_BREAST_F_COV = 16
        private const val CLS_BUTTOCKS_COV = 17
    }
}
