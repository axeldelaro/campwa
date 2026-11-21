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
import android.os.Build
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.FaceGenderMode
import com.blurshield.app.config.FaceSubZone
import com.blurshield.app.config.MaskShape
import com.blurshield.app.overlay.BlurShaderEngine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BlurRegion(
    val id: String,
    val kind: String, // "face" | "nsfw" | "body"
    val rect: RectF, // normalized [0..1]
    val contour: List<PointF>?,
    val rotationDeg: Float = 0f,
    val eulerYaw: Float = 0f,
    val confidence: Float = 1f,
    val patch: Bitmap? = null,
    val alphaMask: Bitmap? = null, // Feature #1 Silhouette / Feature #5 Skin-Tone pixel mask
    val avgColor: Int = Color.rgb(130, 128, 125),
    val reactiveEmoji: String = "😎",
    val isSubZoneBar: Boolean = false
)

data class DetectionFrameResult(
    val regions: List<BlurRegion>,
    val inferenceMs: Long
)

/**
 * BlurShield AI v2.0 Parallel Neural Vision Engine:
 * - 3-way concurrent inference: ML Kit Face (133-pt contours + 3D Euler pose + expressions),
 *   NudeNet v3 YOLOv8n ONNX (4 threads + ALL_OPT), and ML Kit Selfie Segmentation (#1 Silhouette).
 * - Surgical Face Sub-Zone masking (#3): Full Face, Eyes Only, Mouth Only, Upper Face, Eyes+Mouth, Hair+Head.
 * - 3D Head Pose Euler X/Y/Z tracking & side-profile compensation (#4).
 * - Multi-Ethnic Skin-Tone pixel contour refinement for NSFW regions (#5).
 * - High-resolution live patches for Android 13+ AGSL GPU Shaders (#9) & Reactive Emoji (#10).
 */
class BlurVisionEngine(context: Context) {

    private val appContext = context.applicationContext
    private val parallelPool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "BlurShieldAI").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 2
        }
    }

    private var faceDetector: FaceDetector? = null
    private val silhouetteSegmenter = SilhouetteSegmenter()
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
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.038f)
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

        // Run ML Kit Face whenever faces are enabled (gives 133-pt contours, 3D Euler & expressions even when gender filter is active)
        val needMlKitFaces = cfg.blurFaces
        val needOnnx = cfg.anyNsfwEnabled || cfg.blurFaces
        val needSilhouette = cfg.needsSilhouetteEngine
        ensureInitialized(needFaces = needMlKitFaces, needOnnx = needOnnx)

        val faceFuture = if (needMlKitFaces) {
            parallelPool.submit(Callable { runMlKitFaces(frame, cfg) })
        } else null

        val runOnnxInParallel = cfg.anyNsfwEnabled || (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.ALL)
        val onnxFuture = if (runOnnxInParallel) {
            parallelPool.submit(Callable { runNudeNet(frame, cfg) })
        } else null

        val silhouetteFuture = if (needSilhouette) {
            parallelPool.submit(Callable { silhouetteSegmenter.segment(frame) })
        } else null

        val mlFaces = if (faceFuture != null) {
            try { faceFuture.get(150L, TimeUnit.MILLISECONDS) } catch (_: Throwable) { emptyList() }
        } else emptyList()

        val onnxRegions = if (onnxFuture != null) {
            try { onnxFuture.get(220L, TimeUnit.MILLISECONDS) } catch (_: Throwable) { emptyList() }
        } else if (cfg.blurFaces && mlFaces.isEmpty()) {
            // Backup ONNX face pass for steep side-profiles or occluded faces missed by ML Kit
            runNudeNet(frame, cfg)
        } else emptyList()

        val silhouette = if (silhouetteFuture != null) {
            try { silhouetteFuture.get(130L, TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
        } else null

        val merged = ArrayList<BlurRegion>(12)

        // 1) Fuse ML Kit faces with ONNX gender filtering when Female/Male-only is selected
        if (cfg.blurFaces) {
            if (cfg.faceGenderMode == FaceGenderMode.ALL) {
                merged.addAll(mlFaces)
                for (r in onnxRegions) {
                    if (r.kind == "face" && merged.none { it.kind == "face" && iou(it.rect, r.rect) > 0.22f }) {
                        merged.add(r)
                    }
                }
            } else {
                // Keep only ONNX gender-matched faces, enriched with ML Kit precision contours/3D pose/expressions!
                for (r in onnxRegions) {
                    if (r.kind != "face") continue
                    val mlMatches = mlFaces.filter { iou(it.rect, r.rect) > 0.18f }
                    if (mlMatches.isNotEmpty()) {
                        merged.addAll(mlMatches)
                    } else {
                        merged.add(r)
                    }
                }
            }
        }

        // 2) Add all enabled NSFW detections
        for (r in onnxRegions) {
            if (r.kind == "nsfw") merged.add(r)
        }

        // 3) Feature #1: Optional Whole-Body Pixel Silhouette Mode
        if (cfg.blurWholeBodySilhouette && silhouette != null && silhouette.personBoundsNorm != null && silhouette.coverageRatio > 0.035f) {
            val bounds = silhouette.personBoundsNorm
            val bodyAlpha = silhouette.extractRegionAlphaMask(bounds, padFrac = 0.02f, featherPct = cfg.edgeFeatherPct)
            if (bodyAlpha != null) {
                merged.add(
                    BlurRegion(
                        id = "silhouette-body-0",
                        kind = "body",
                        rect = bounds,
                        contour = null,
                        confidence = 0.98f,
                        alphaMask = bodyAlpha,
                        reactiveEmoji = cfg.emojiPack.baseEmoji
                    )
                )
            }
        }

        // Apply Screen ROI (Region of Interest) + optional system bar exclusion
        val minY = max(cfg.roiTopPct / 100f, if (cfg.excludeSystemBars) 0.032f else 0f)
        val maxY = min(cfg.roiBottomPct / 100f, if (cfg.excludeSystemBars) 0.965f else 1f)

        val filtered = merged.filter { region ->
            val cy = region.rect.centerY()
            cy in minY..maxY
        }

        // Generate style-specific live patches + pixel-accurate alpha masks (#1 Silhouette + #5 Skin-Tone Refinement)
        val padBase = cfg.maskPaddingPct / 100f
        val withPatches = filtered.map { region ->
            val isNsfw = region.kind == "nsfw"
            val isBody = region.kind == "body"
            val padFrac = if (isBody) 0.02f else padBase + (if (isNsfw) 0.04f else 0f)

            var alphaMask = region.alphaMask
            if (alphaMask == null && !region.isSubZoneBar) {
                if (isNsfw && cfg.skinToneRefinement) {
                    // Feature #5: YCbCr + HSV multi-ethnic skin-tone pixel contour refinement
                    alphaMask = SkinToneRefiner.refineSkinMask(frame, region.rect, padFrac, cfg.edgeFeatherPct)
                }
                if (silhouette != null && cfg.silhouetteSegmentation) {
                    val useSilhouette = (cfg.maskShape == MaskShape.SILHOUETTE) ||
                        (region.kind == "face" && cfg.faceSubZone == FaceSubZone.HAIR_AND_HEAD) ||
                        isNsfw
                    if (useSilhouette) {
                        alphaMask = if (alphaMask != null) {
                            silhouette.intersectWithMask(alphaMask, region.rect, padFrac)
                        } else if (cfg.maskShape == MaskShape.SILHOUETTE || region.kind == "face" && cfg.faceSubZone == FaceSubZone.HAIR_AND_HEAD) {
                            silhouette.extractRegionAlphaMask(region.rect, padFrac, cfg.edgeFeatherPct)
                        } else null
                    }
                }
            }

            val (patch, avg) = buildStyledPatch(frame, region.rect, padFrac, cfg)
            region.copy(patch = patch, alphaMask = alphaMask, avgColor = avg)
        }

        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        return DetectionFrameResult(withPatches, elapsedMs)
    }

    private fun runMlKitFaces(frame: Bitmap, cfg: BlurConfigSnapshot): List<BlurRegion> {
        val detector = faceDetector ?: return emptyList()
        val w = frame.width.coerceAtLeast(1).toFloat()
        val h = frame.height.coerceAtLeast(1).toFloat()
        val out = ArrayList<BlurRegion>(8)
        try {
            val img = InputImage.fromBitmap(frame, 0)
            val faces = Tasks.await(detector.process(img), 140L, TimeUnit.MILLISECONDS)
            for ((idx, face) in faces.withIndex()) {
                val b = face.boundingBox
                val baseLeft = (b.left / w).coerceIn(0f, 1f)
                val baseTop = (b.top / h).coerceIn(0f, 1f)
                val baseRight = (b.right / w).coerceIn(0f, 1f)
                val baseBottom = (b.bottom / h).coerceIn(0f, 1f)
                val bw = baseRight - baseLeft
                val bh = baseBottom - baseTop
                if (bw < 0.020f || bh < 0.020f) continue

                // Feature #4: 3D Euler Angle Tracking (Pitch X, Yaw Y, Roll Z)
                val eulerY = face.headEulerAngleY
                val eulerZ = if (cfg.headPose3dTracking) -face.headEulerAngleZ else 0f
                val yawShift = if (cfg.headPose3dTracking && abs(eulerY) > 18f) {
                    (eulerY / 90f) * bw * 0.14f
                } else 0f

                // Feature #10: Live Facial Expression -> Reactive Emoji
                val emoji = BlurShaderEngine.resolveEmoji(
                    pack = cfg.emojiPack,
                    isNsfw = false,
                    smileProb = face.smilingProbability ?: -1f,
                    leftEyeOpen = face.leftEyeOpenProbability ?: -1f,
                    rightEyeOpen = face.rightEyeOpenProbability ?: -1f
                )

                val trackKey = "mlface-${face.trackingId ?: idx}"

                // Feature #3: Surgical Face Sub-Zone Extraction (using ML Kit 133-pt facial contours)
                when (cfg.faceSubZone) {
                    FaceSubZone.FULL_FACE -> {
                        val contour = extractNormContour(face, FaceContour.FACE, w, h)
                        val rect = RectF(
                            (baseLeft + yawShift).coerceIn(0f, 1f),
                            baseTop,
                            (baseRight + yawShift).coerceIn(0f, 1f),
                            baseBottom
                        )
                        out.add(
                            BlurRegion(
                                id = trackKey,
                                kind = "face",
                                rect = rect,
                                contour = contour,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = emoji
                            )
                        )
                    }

                    FaceSubZone.HAIR_AND_HEAD -> {
                        val rawFace = extractNormContour(face, FaceContour.FACE, w, h)
                        val hairContour = rawFace?.map { pt ->
                            val relY = (pt.y - baseTop) / bh.coerceAtLeast(0.01f)
                            val liftY = if (relY < 0.45f) bh * 0.36f * (1f - relY / 0.45f) else 0f
                            val widenX = (pt.x - (baseLeft + baseRight) * 0.5f) * 0.16f
                            PointF((pt.x + widenX).coerceIn(0f, 1f), (pt.y - liftY).coerceIn(0f, 1f))
                        }
                        val rect = RectF(
                            (baseLeft - bw * 0.16f + yawShift).coerceIn(0f, 1f),
                            (baseTop - bh * 0.36f).coerceIn(0f, 1f),
                            (baseRight + bw * 0.16f + yawShift).coerceIn(0f, 1f),
                            (baseBottom + bh * 0.04f).coerceIn(0f, 1f)
                        )
                        out.add(
                            BlurRegion(
                                id = "$trackKey-head",
                                kind = "face",
                                rect = rect,
                                contour = hairContour,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = emoji
                            )
                        )
                    }

                    FaceSubZone.EYES_ONLY -> {
                        val eyeRect = computeEyesRect(face, baseLeft, baseTop, bw, bh, w, h)
                        out.add(
                            BlurRegion(
                                id = "$trackKey-eyes",
                                kind = "face",
                                rect = eyeRect,
                                contour = null,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = "🕶️",
                                isSubZoneBar = true
                            )
                        )
                    }

                    FaceSubZone.MOUTH_ONLY -> {
                        val mouthRect = computeMouthRect(face, baseLeft, baseTop, bw, bh, w, h)
                        out.add(
                            BlurRegion(
                                id = "$trackKey-mouth",
                                kind = "face",
                                rect = mouthRect,
                                contour = null,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = "🤐",
                                isSubZoneBar = true
                            )
                        )
                    }

                    FaceSubZone.UPPER_FACE -> {
                        val upperRect = RectF(
                            (baseLeft - bw * 0.04f + yawShift).coerceIn(0f, 1f),
                            (baseTop - bh * 0.06f).coerceIn(0f, 1f),
                            (baseRight + bw * 0.04f + yawShift).coerceIn(0f, 1f),
                            (baseTop + bh * 0.62f).coerceIn(0f, 1f)
                        )
                        out.add(
                            BlurRegion(
                                id = "$trackKey-upper",
                                kind = "face",
                                rect = upperRect,
                                contour = null,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = emoji,
                                isSubZoneBar = true
                            )
                        )
                    }

                    FaceSubZone.EYES_AND_MOUTH -> {
                        val eyeRect = computeEyesRect(face, baseLeft, baseTop, bw, bh, w, h)
                        val mouthRect = computeMouthRect(face, baseLeft, baseTop, bw, bh, w, h)
                        out.add(
                            BlurRegion(
                                id = "$trackKey-eyes",
                                kind = "face",
                                rect = eyeRect,
                                contour = null,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = "🕶️",
                                isSubZoneBar = true
                            )
                        )
                        out.add(
                            BlurRegion(
                                id = "$trackKey-mouth",
                                kind = "face",
                                rect = mouthRect,
                                contour = null,
                                rotationDeg = eulerZ,
                                eulerYaw = eulerY,
                                confidence = 0.99f,
                                reactiveEmoji = "🤐",
                                isSubZoneBar = true
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun extractNormContour(face: Face, contourType: Int, w: Float, h: Float): List<PointF>? {
        val raw = face.getContour(contourType)?.points
        if (raw == null || raw.size < 6) return null
        return raw.map { pt -> PointF((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f)) }
    }

    private fun computeEyesRect(
        face: Face,
        baseLeft: Float,
        baseTop: Float,
        bw: Float,
        bh: Float,
        w: Float,
        h: Float
    ): RectF {
        val pts = ArrayList<PointF>(40)
        face.getContour(FaceContour.LEFT_EYE)?.points?.let { pts.addAll(it) }
        face.getContour(FaceContour.RIGHT_EYE)?.points?.let { pts.addAll(it) }
        face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points?.let { pts.addAll(it) }
        face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points?.let { pts.addAll(it) }

        if (pts.size >= 6) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                val nx = p.x / w
                val ny = p.y / h
                if (nx < minX) minX = nx
                if (ny < minY) minY = ny
                if (nx > maxX) maxX = nx
                if (ny > maxY) maxY = ny
            }
            val ew = (maxX - minX).coerceAtLeast(bw * 0.45f)
            val eh = (maxY - minY).coerceAtLeast(bh * 0.16f)
            return RectF(
                (minX - ew * 0.16f).coerceIn(0f, 1f),
                (minY - eh * 0.25f).coerceIn(0f, 1f),
                (maxX + ew * 0.16f).coerceIn(0f, 1f),
                (maxY + eh * 0.38f).coerceIn(0f, 1f)
            )
        }
        return RectF(
            (baseLeft + bw * 0.06f).coerceIn(0f, 1f),
            (baseTop + bh * 0.22f).coerceIn(0f, 1f),
            (baseLeft + bw * 0.94f).coerceIn(0f, 1f),
            (baseTop + bh * 0.48f).coerceIn(0f, 1f)
        )
    }

    private fun computeMouthRect(
        face: Face,
        baseLeft: Float,
        baseTop: Float,
        bw: Float,
        bh: Float,
        w: Float,
        h: Float
    ): RectF {
        val pts = ArrayList<PointF>(32)
        face.getContour(FaceContour.UPPER_LIP_TOP)?.points?.let { pts.addAll(it) }
        face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points?.let { pts.addAll(it) }

        if (pts.size >= 6) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                val nx = p.x / w
                val ny = p.y / h
                if (nx < minX) minX = nx
                if (ny < minY) minY = ny
                if (nx > maxX) maxX = nx
                if (ny > maxY) maxY = ny
            }
            val mw = (maxX - minX).coerceAtLeast(bw * 0.28f)
            val mh = (maxY - minY).coerceAtLeast(bh * 0.12f)
            return RectF(
                (minX - mw * 0.20f).coerceIn(0f, 1f),
                (minY - mh * 0.35f).coerceIn(0f, 1f),
                (maxX + mw * 0.20f).coerceIn(0f, 1f),
                (maxY + mh * 0.35f).coerceIn(0f, 1f)
            )
        }
        return RectF(
            (baseLeft + bw * 0.22f).coerceIn(0f, 1f),
            (baseTop + bh * 0.66f).coerceIn(0f, 1f),
            (baseLeft + bw * 0.78f).coerceIn(0f, 1f),
            (baseTop + bh * 0.88f).coerceIn(0f, 1f)
        )
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
                                confidence = bestScore,
                                reactiveEmoji = if (kind == "nsfw") "🛡️" else cfg.emojiPack.baseEmoji
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
            CLS_FACE_FEMALE -> if (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.MALE_ONLY && score >= sens.faceThresh) "face" else null
            CLS_FACE_MALE -> if (cfg.blurFaces && cfg.faceGenderMode != FaceGenderMode.FEMALE_ONLY && score >= sens.faceThresh) "face" else null

            CLS_GENITALIA_F_EXP, CLS_GENITALIA_M_EXP, CLS_ANUS_EXP ->
                if (cfg.blurGenitalia && score >= sens.explicitThresh) "nsfw" else null
            CLS_GENITALIA_F_COV, CLS_ANUS_COV ->
                if (cfg.blurGenitalia && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            CLS_BREAST_F_EXP ->
                if (cfg.blurBreasts && score >= sens.explicitThresh) "nsfw" else null
            CLS_BREAST_F_COV, CLS_BREAST_M_EXP ->
                if (cfg.blurBreasts && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

            CLS_BUTTOCKS_EXP ->
                if (cfg.blurButtocks && score >= sens.explicitThresh) "nsfw" else null
            CLS_BUTTOCKS_COV ->
                if (cfg.blurButtocks && cfg.blurCoveredSensitive && score >= sens.strictThresh) "nsfw" else null

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
        padFrac: Float,
        cfg: BlurConfigSnapshot
    ): Pair<Bitmap?, Int> {
        val fw = frame.width
        val fh = frame.height
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
        if (cfg.blurStyle == BlurStyle.CUSTOM_IMAGE) {
            return Pair(null, avgColor)
        }

        return try {
            when (cfg.blurStyle) {
                BlurStyle.MOSAIC -> {
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

                BlurStyle.GPU_BOKEH, BlurStyle.GPU_FLUTED_GLASS, BlurStyle.GPU_LIQUID_RIPPLE,
                BlurStyle.GPU_VORONOI_CRYSTAL, BlurStyle.GPU_CYBER_GLITCH -> {
                    // Android 13+ AGSL GPU shaders sample sharp 96px crops directly on GPU;
                    // Android 8-12 native fallback gets pre-blurred patch so privacy is always 100%.
                    val hasAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    val targetW = if (hasAgsl) 88 else 40
                    val targetH = ((targetW * cropH.toFloat() / cropW.toFloat()).toInt()).coerceIn(32, 112)
                    val cropped = Bitmap.createBitmap(frame, x1, y1, cropW, cropH)
                    val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                    if (cropped !== scaled && !cropped.isRecycled) cropped.recycle()
                    if (!hasAgsl) {
                        val rad = (4 + cfg.blurIntensity * 1.4f).toInt().coerceIn(5, 18)
                        FastStackBlur.blurInPlace(scaled, rad)
                    } else if (cfg.blurStyle == BlurStyle.GPU_FLUTED_GLASS || cfg.blurStyle == BlurStyle.GPU_LIQUID_RIPPLE) {
                        // Light pre-soften guarantees zero facial recognition even through glass/liquid refraction
                        FastStackBlur.blurInPlace(scaled, (2 + cfg.blurIntensity / 2).coerceIn(2, 7))
                    }
                    Pair(scaled, avgColor)
                }

                BlurStyle.GAUSSIAN, BlurStyle.FROSTED, BlurStyle.EMOJI_REACTIVE -> {
                    val targetW = 38
                    val targetH = ((targetW * cropH.toFloat() / cropW.toFloat()).toInt()).coerceIn(26, 56)
                    val cropped = Bitmap.createBitmap(frame, x1, y1, cropW, cropH)
                    val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                    if (cropped !== scaled && !cropped.isRecycled) cropped.recycle()
                    val radius = (3 + cfg.blurIntensity * 1.5f).toInt().coerceIn(4, 19)
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
        try { silhouetteSegmenter.close() } catch (_: Throwable) {}
        try { ortSession?.close() } catch (_: Throwable) {}
        ortSession = null
        onnxReady = false
    }

    companion object {
        private const val ASSET_MODEL_NAME = "nudenet_320n.onnx"
        private const val MODEL_SIZE = 320
        private const val NUM_CLASSES = 18

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
