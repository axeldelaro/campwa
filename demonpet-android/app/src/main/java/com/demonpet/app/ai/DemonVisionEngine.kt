package com.demonpet.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.FaceCensorMode
import com.demonpet.app.config.GenderFilter
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

data class CensorTarget(
    val id: String,
    val kind: String, // "face" | "nsfw"
    val rect: RectF,  // normalized 0..1
    val contour: List<PointF>?,
    val rotationDeg: Float = 0f,
    val patch: Bitmap? = null,
    val avgColor: Int = Color.rgb(120, 110, 115),
    val isEyeBar: Boolean = false
)

/**
 * Parallel On-Device Vision Engine for DemonMom AI (#1, #2, #3, #4, #5, #6):
 * - ML Kit Face Contours (36-pt Bézier + eye-bar extraction + 3D Euler tilt)
 * - Bundled NudeNet v3 ONNX (18-class NSFW shield + Female/Male jealousy gender filter)
 */
class DemonVisionEngine(context: Context) {

    private val appContext = context.applicationContext
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "DemonMomAI").apply { isDaemon = true }
    }

    private var faceDetector: FaceDetector? = null
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName = "images"

    private val paddedBmp = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val paddedCanvas = Canvas(paddedBmp)
    private val pixelBuf = IntArray(MODEL_SIZE * MODEL_SIZE)
    private val floatArray = FloatArray(3 * MODEL_SIZE * MODEL_SIZE)
    private val tensorShape = longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())

    private fun ensureInit(needFace: Boolean, needOnnx: Boolean) {
        if (needFace && faceDetector == null) {
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
            try {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env
                val modelFile = File(appContext.filesDir, ASSET_MODEL_NAME)
                if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                    appContext.assets.open(ASSET_MODEL_NAME).use { input ->
                        FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                    }
                }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors().coerceIn(2, 4)))
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val s = env.createSession(modelFile.absolutePath, opts)
                inputName = s.inputNames.firstOrNull() ?: "images"
                ortSession = s
            } catch (_: Throwable) {
            }
        }
    }

    fun analyzeFrame(frame: Bitmap, cfg: DemonConfigSnapshot): List<CensorTarget> {
        if (!cfg.anyAiVisionEnabled) return emptyList()
        val wantFaces = cfg.faceCensorMode != FaceCensorMode.OFF
        val wantOnnx = cfg.nsfwShieldEnabled || (wantFaces && cfg.faceGenderFilter != GenderFilter.ALL)
        ensureInit(needFace = wantFaces, needOnnx = wantOnnx)

        val faceFuture = if (wantFaces) pool.submit(Callable { detectMlFaces(frame, cfg) }) else null
        val onnxFuture = if (wantOnnx) pool.submit(Callable { detectOnnx(frame, cfg) }) else null

        val mlFaces = try { faceFuture?.get(150L, TimeUnit.MILLISECONDS) ?: emptyList() } catch (_: Throwable) { emptyList() }
        val onnxList = try { onnxFuture?.get(220L, TimeUnit.MILLISECONDS) ?: emptyList() } catch (_: Throwable) { emptyList() }

        val merged = ArrayList<CensorTarget>(10)
        if (wantFaces) {
            if (cfg.faceGenderFilter == GenderFilter.ALL) {
                merged.addAll(mlFaces)
                for (r in onnxList) {
                    if (r.kind == "face" && merged.none { it.kind == "face" && iou(it.rect, r.rect) > 0.25f }) {
                        merged.add(r)
                    }
                }
            } else {
                for (r in onnxList) {
                    if (r.kind != "face") continue
                    val match = mlFaces.firstOrNull { iou(it.rect, r.rect) > 0.18f }
                    merged.add(match ?: r)
                }
            }
        }
        if (cfg.nsfwShieldEnabled) {
            for (r in onnxList) {
                if (r.kind == "nsfw") merged.add(r)
            }
        }

        // Exclude system bars cleanly
        val filtered = merged.filter { it.rect.centerY() in 0.035f..0.96f }
        return filtered.map { target ->
            val patch = buildPatch(frame, target.rect, target.kind == "nsfw", cfg)
            target.copy(patch = patch.first, avgColor = patch.second)
        }
    }

    private fun detectMlFaces(frame: Bitmap, cfg: DemonConfigSnapshot): List<CensorTarget> {
        val det = faceDetector ?: return emptyList()
        val w = frame.width.coerceAtLeast(1).toFloat()
        val h = frame.height.coerceAtLeast(1).toFloat()
        val out = ArrayList<CensorTarget>(6)
        try {
            val faces = Tasks.await(det.process(InputImage.fromBitmap(frame, 0)), 140L, TimeUnit.MILLISECONDS)
            for ((idx, face) in faces.withIndex()) {
                val b = face.boundingBox
                val l = (b.left / w).coerceIn(0f, 1f)
                val t = (b.top / h).coerceIn(0f, 1f)
                val r = (b.right / w).coerceIn(0f, 1f)
                val bot = (b.bottom / h).coerceIn(0f, 1f)
                if (r - l < 0.025f || bot - t < 0.025f) continue

                val rot = -face.headEulerAngleZ
                if (cfg.faceCensorMode == FaceCensorMode.EYE_BAR) {
                    val eyePts = ArrayList<PointF>(30)
                    face.getContour(FaceContour.LEFT_EYE)?.points?.let { eyePts.addAll(it) }
                    face.getContour(FaceContour.RIGHT_EYE)?.points?.let { eyePts.addAll(it) }
                    val eyeRect = if (eyePts.size >= 4) {
                        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                        for (pt in eyePts) {
                            val nx = pt.x / w; val ny = pt.y / h
                            if (nx < minX) minX = nx; if (ny < minY) minY = ny
                            if (nx > maxX) maxX = nx; if (ny > maxY) maxY = ny
                        }
                        val ew = (maxX - minX).coerceAtLeast((r - l) * 0.45f)
                        val eh = (maxY - minY).coerceAtLeast((bot - t) * 0.16f)
                        RectF((minX - ew * 0.2f).coerceIn(0f, 1f), (minY - eh * 0.4f).coerceIn(0f, 1f),
                            (maxX + ew * 0.2f).coerceIn(0f, 1f), (maxY + eh * 0.45f).coerceIn(0f, 1f))
                    } else {
                        RectF(l + (r - l) * 0.06f, t + (bot - t) * 0.24f, r - (r - l) * 0.06f, t + (bot - t) * 0.48f)
                    }
                    out.add(CensorTarget("f-${face.trackingId ?: idx}", "face", eyeRect, null, rot, isEyeBar = true))
                } else {
                    val contour = face.getContour(FaceContour.FACE)?.points?.takeIf { it.size >= 8 }
                        ?.map { PointF((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }
                    out.add(CensorTarget("f-${face.trackingId ?: idx}", "face", RectF(l, t, r, bot), contour, rot))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun detectOnnx(frame: Bitmap, cfg: DemonConfigSnapshot): List<CensorTarget> {
        val env = ortEnv ?: return emptyList()
        val session = ortSession ?: return emptyList()

        val origW = frame.width
        val origH = frame.height
        val maxDim = max(origW, origH).toFloat()
        val scale = MODEL_SIZE / maxDim
        val sw = (origW * scale).toInt().coerceIn(1, MODEL_SIZE)
        val sh = (origH * scale).toInt().coerceIn(1, MODEL_SIZE)

        paddedCanvas.drawColor(Color.BLACK)
        paddedCanvas.drawBitmap(frame, null, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), null)
        paddedBmp.getPixels(pixelBuf, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val plane = MODEL_SIZE * MODEL_SIZE
        val inv = 1f / 255f
        for (i in 0 until plane) {
            val px = pixelBuf[i]
            floatArray[i] = ((px ushr 16) and 0xFF) * inv
            floatArray[plane + i] = ((px ushr 8) and 0xFF) * inv
            floatArray[2 * plane + i] = (px and 0xFF) * inv
        }

        val candidates = ArrayList<CensorTarget>(10)
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), tensorShape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val batch = output[0].value as? Array<Array<FloatArray>> ?: return emptyList()
                    val rows = batch[0]
                    if (rows.size < 4 + NUM_CLASSES) return emptyList()
                    val anchors = rows[0].size
                    val xScale = maxDim / (MODEL_SIZE * origW.toFloat())
                    val yScale = maxDim / (MODEL_SIZE * origH.toFloat())

                    for (a in 0 until anchors) {
                        var bestCls = -1
                        var bestScore = 0.26f
                        for (c in 0 until NUM_CLASSES) {
                            val sc = rows[4 + c][a]
                            if (sc > bestScore) { bestScore = sc; bestCls = c }
                        }
                        if (bestCls < 0) continue

                        val kind = when (bestCls) {
                            CLS_FACE_F -> if (cfg.faceCensorMode != FaceCensorMode.OFF && cfg.faceGenderFilter != GenderFilter.MALE_ONLY && bestScore >= 0.34f) "face" else null
                            CLS_FACE_M -> if (cfg.faceCensorMode != FaceCensorMode.OFF && cfg.faceGenderFilter != GenderFilter.FEMALE_ONLY && bestScore >= 0.34f) "face" else null
                            CLS_GEN_F, CLS_GEN_M, CLS_ANUS, CLS_BREAST_F, CLS_BUTTOCKS ->
                                if (cfg.nsfwShieldEnabled && bestScore >= 0.28f) "nsfw" else null
                            else -> null
                        } ?: continue

                        val bw = rows[2][a]; val bh = rows[3][a]
                        val l = ((rows[0][a] - bw * 0.5f) * xScale).coerceIn(0f, 1f)
                        val t = ((rows[1][a] - bh * 0.5f) * yScale).coerceIn(0f, 1f)
                        val r = ((rows[0][a] + bw * 0.5f) * xScale).coerceIn(0f, 1f)
                        val bot = ((rows[1][a] + bh * 0.5f) * yScale).coerceIn(0f, 1f)
                        if (r - l < 0.022f || bot - t < 0.022f) continue
                        candidates.add(CensorTarget("nn-$kind-$bestCls-$a", kind, RectF(l, t, r, bot), null))
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        // Simple NMS
        val kept = ArrayList<CensorTarget>()
        for (c in candidates) {
            if (kept.none { it.kind == c.kind && iou(it.rect, c.rect) > 0.42f }) kept.add(c)
        }
        return kept
    }

    private fun buildPatch(frame: Bitmap, rect: RectF, isNsfw: Boolean, cfg: DemonConfigSnapshot): Pair<Bitmap?, Int> {
        val fw = frame.width; val fh = frame.height
        val pad = if (isNsfw) 0.16f else 0.12f
        val x1 = ((rect.left - rect.width() * pad) * fw).toInt().coerceIn(0, fw - 2)
        val y1 = ((rect.top - rect.height() * pad) * fh).toInt().coerceIn(0, fh - 2)
        val x2 = ((rect.right + rect.width() * pad) * fw).toInt().coerceIn(x1 + 2, fw)
        val y2 = ((rect.bottom + rect.height() * pad) * fh).toInt().coerceIn(y1 + 2, fh)
        val cw = (x2 - x1).coerceAtLeast(2); val ch = (y2 - y1).coerceAtLeast(2)

        val c = frame.getPixel((x1 + x2) / 2, (y1 + y2) / 2)
        val avg = Color.rgb((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)

        if (!isNsfw && (cfg.faceCensorMode == FaceCensorMode.EYE_BAR)) {
            return Pair(null, Color.rgb(15, 15, 20))
        }
        return try {
            val crop = Bitmap.createBitmap(frame, x1, y1, cw, ch)
            val tw = if (cfg.faceCensorMode == FaceCensorMode.GPU_SABOTAGE) 72 else 36
            val th = ((tw * ch.toFloat() / cw.toFloat()).toInt()).coerceIn(28, 84)
            val scaled = Bitmap.createScaledBitmap(crop, tw, th, true)
            if (crop !== scaled) crop.recycle()
            val rad = if (cfg.faceCensorMode == FaceCensorMode.GPU_SABOTAGE) 4 else 14
            FastStackBlur.blurInPlace(scaled, rad)
            Pair(scaled, avg)
        } catch (_: Throwable) {
            Pair(null, avg)
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left); val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right); val iy2 = min(a.bottom, b.bottom)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        try { pool.shutdownNow() } catch (_: Throwable) {}
        try { faceDetector?.close() } catch (_: Throwable) {}
        faceDetector = null
        try { ortSession?.close() } catch (_: Throwable) {}
        ortSession = null
    }

    companion object {
        private const val ASSET_MODEL_NAME = "nudenet_320n.onnx"
        private const val MODEL_SIZE = 320
        private const val NUM_CLASSES = 18
        private const val CLS_FACE_F = 1
        private const val CLS_BUTTOCKS = 2
        private const val CLS_BREAST_F = 3
        private const val CLS_GEN_F = 4
        private const val CLS_ANUS = 6
        private const val CLS_FACE_M = 12
        private const val CLS_GEN_M = 14
    }
}
