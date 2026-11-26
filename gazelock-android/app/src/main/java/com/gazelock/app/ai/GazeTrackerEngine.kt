package com.gazelock.app.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.gazelock.app.config.GazeConfigSnapshot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 100% On-Device Local AI Head-Pose + Sub-Pixel Iris + TITAN Anti-Cheat Gaze Tracker (v7.0):
 * - Head Yaw/Pitch + Eye-Open + Sub-Pixel Iris/Pupil deviation on raw CameraX Y-plane.
 * - #8 Auto Zero-Point 3s Posture Calibration (learns natural sitting/lying angle automatically).
 * - #9 Anti-Camera Cover / Black-Frame Detector (samples Y-plane luma + variance in <0.05ms!).
 * - #10 Face Distance Guard (blocks leaving phone far away on a desk!).
 * - #11 Micro-Sleep / Heavy Eyelids Detector (sustained drowsy eyes > 1.6s).
 * - #12 Live Escalating Strictness (`setTightenLevel` progressively narrows tolerances!).
 * - #14 Solo-Face Guard (alerts if multiple faces try to substitute in frame!).
 */
class GazeTrackerEngine(
    private val context: Context,
    private val onReading: (GazeReading) -> Unit,
    private val onCameraError: (String) -> Unit = {}
) {

    data class GazeReading(
        val faceDetected: Boolean,
        val yawDeg: Float,
        val pitchDeg: Float,
        val eyeOpenAvg: Float,
        val irisOffsetPct: Int,
        val irisMeasured: Boolean,
        val rawLooking: Boolean,
        val isLookingAtScreen: Boolean,
        val reason: String,
        val cameraReady: Boolean = true,
        val cameraCovered: Boolean = false,
        val faceTooFar: Boolean = false,
        val drowsyDetected: Boolean = false,
        val multiFaceAlert: Boolean = false,
        val zeroCalibrated: Boolean = false
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null

    @Volatile
    private var activeConfig: GazeConfigSnapshot? = null

    @Volatile
    private var started = false

    @Volatile
    private var tightenLevel = 0

    private var startElapsedMs = 0L
    private var lastRawLookingMs = 0L
    private var lastRawAwayMs = 0L
    private var smoothedLooking = true
    private var smoothedIrisOffset = 0f

    // #8 Zero-Point Auto-Calibration state
    private var zeroCalibSumYaw = 0f
    private var zeroCalibSumPitch = 0f
    private var zeroCalibSamples = 0
    private var zeroYawOffset = 0f
    private var zeroPitchOffset = 0f
    private var zeroCalibrated = false

    // #11 Micro-Sleep tracking
    private var drowsyStartMs = 0L

    fun updateConfig(cfg: GazeConfigSnapshot) {
        activeConfig = cfg
    }

    /** #12 Escalating Strictness: narrows head angle & iris tolerances dynamically! */
    fun setTightenLevel(level: Int) {
        tightenLevel = level.coerceIn(0, 10)
    }

    fun start(lifecycleOwner: LifecycleOwner, cfg: GazeConfigSnapshot) {
        activeConfig = cfg
        if (started) return
        started = true
        val now = SystemClock.elapsedRealtime()
        startElapsedMs = now
        lastRawLookingMs = now
        lastRawAwayMs = 0L
        smoothedLooking = true
        smoothedIrisOffset = 0f
        tightenLevel = 0
        zeroCalibSumYaw = 0f; zeroCalibSumPitch = 0f; zeroCalibSamples = 0
        zeroYawOffset = 0f; zeroPitchOffset = 0f; zeroCalibrated = false
        drowsyStartMs = 0L

        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.11f)
            .build()
        detector = FaceDetection.getClient(opts)

        val future = ProcessCameraProvider.getInstance(context.applicationContext)
        future.addListener({
            if (!started) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindAnalysis(provider, lifecycleOwner)
            } catch (t: Throwable) {
                onCameraError("Caméra frontale indisponible : ${t.message.orEmpty()}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @Suppress("DEPRECATION")
    private fun bindAnalysis(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            provider.unbindAll()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        } catch (t: Throwable) {
            onCameraError("Erreur liaison caméra IA : ${t.message.orEmpty()}")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy) {
        val det = detector
        val mediaImg = imageProxy.image
        if (!started || det == null || mediaImg == null) {
            imageProxy.close()
            return
        }
        if (!frameBusy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val bufW = imageProxy.width
        val bufH = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        // #9 Ultra-fast camera cover / black-frame check (<0.05ms) BEFORE ML Kit!
        val isDarkCovered = isFrameCoveredDark(yBuffer, bufW, bufH, rowStride, pixelStride)

        val input = InputImage.fromMediaImage(mediaImg, rotation)
        det.process(input)
            .addOnSuccessListener { faces ->
                val cfg = activeConfig
                val now = SystemClock.elapsedRealtime()
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val rotW = if (rotation == 90 || rotation == 270) bufH else bufW

                val yaw: Float
                val pitch: Float
                val eyeAvg: Float
                var irisPct = 0
                var irisMeasured = false
                var cameraCovered = false
                var faceTooFar = false
                var drowsyNow = false
                var multiFace = false
                val rawLooking: Boolean
                val rawReason: String

                if (cfg == null) {
                    yaw = 0f; pitch = 0f; eyeAvg = 0f
                    rawLooking = true; rawReason = "Initialisation…"
                } else if (cfg.antiCameraCoverEnabled && isDarkCovered && face == null) {
                    yaw = 0f; pitch = 0f; eyeAvg = 0f
                    cameraCovered = true
                    rawLooking = false
                    rawReason = "🚫 CAMÉRA OBSTRUÉE — Retire ton doigt de l'objectif !"
                } else if (face == null) {
                    yaw = 0f; pitch = 0f; eyeAvg = 0f
                    drowsyStartMs = 0L
                    rawLooking = false
                    rawReason = "❓ Visage hors cadre — reviens face à l'écran !"
                } else {
                    yaw = face.headEulerAngleY
                    pitch = face.headEulerAngleX
                    val lEye = face.leftEyeOpenProbability
                    val rEye = face.rightEyeOpenProbability
                    eyeAvg = when {
                        lEye != null && rEye != null -> (lEye + rEye) * 0.5f
                        lEye != null -> lEye
                        rEye != null -> rEye
                        else -> 0.85f
                    }
                    val maxEye = maxOf(lEye ?: 0.85f, rEye ?: 0.85f)

                    // #8 Auto Zero-Point 3s Calibration
                    if (cfg.autoZeroCalibrationEnabled && !zeroCalibrated && now - startElapsedMs <= 3_500L) {
                        if (abs(yaw) <= 22f && abs(pitch) <= 22f && maxEye >= 0.45f) {
                            zeroCalibSumYaw += yaw
                            zeroCalibSumPitch += pitch
                            zeroCalibSamples++
                            if (zeroCalibSamples >= 10 || now - startElapsedMs >= 3_000L) {
                                zeroYawOffset = (zeroCalibSumYaw / zeroCalibSamples).coerceIn(-14f, 14f)
                                zeroPitchOffset = (zeroCalibSumPitch / zeroCalibSamples).coerceIn(-14f, 14f)
                                zeroCalibrated = true
                            }
                        }
                    }

                    // #12 Escalating Strictness dynamically narrows limits!
                    val tight = if (cfg.escalatingStrictnessEnabled) tightenLevel else 0
                    val effYawLimit = (cfg.yawThresholdDeg - tight).coerceAtLeast(12)
                    val effPitchLimit = (cfg.pitchThresholdDeg - tight).coerceAtLeast(14)
                    val effIrisLimit = (cfg.irisThresholdPct - tight * 2).coerceAtLeast(18)

                    val relYaw = if (cfg.autoZeroCalibrationEnabled && zeroCalibrated) yaw - zeroYawOffset else yaw
                    val relPitch = if (cfg.autoZeroCalibrationEnabled && zeroCalibrated) pitch - zeroPitchOffset else pitch

                    val yawOk = abs(relYaw) <= effYawLimit
                    val pitchOk = abs(relPitch) <= effPitchLimit
                    val eyesOk = !cfg.requireEyesOpen || maxEye >= 0.34f

                    // #10 Face Distance Guard (blocks leaving phone far on table!)
                    val faceWidthRatio = face.boundingBox.width().toFloat() / rotW.coerceAtLeast(1).toFloat()
                    faceTooFar = cfg.faceDistanceGuardEnabled && faceWidthRatio < 0.16f

                    // #11 Micro-Sleep / Heavy Eyelids Detector (>1.6s with eyeAvg < 0.45f)
                    if (cfg.antiDrowsyEnabled && eyeAvg < 0.45f) {
                        if (drowsyStartMs == 0L) drowsyStartMs = now
                        if (now - drowsyStartMs >= 1_600L) drowsyNow = true
                    } else {
                        drowsyStartMs = 0L
                    }

                    // #14 Solo-Face Guard (multiple people trying to trick camera)
                    multiFace = cfg.soloFaceGuardEnabled && faces.size > 1

                    // Sub-Pixel Iris/Pupil Gaze Deviation inside ML Kit Eye Contours
                    if (eyesOk) {
                        val leftPts = face.getContour(FaceContour.LEFT_EYE)?.points
                        val rightPts = face.getContour(FaceContour.RIGHT_EYE)?.points
                        val leftOff = estimateIrisHorizontalOffset(leftPts, yBuffer, bufW, bufH, rowStride, pixelStride, rotation)
                        val rightOff = estimateIrisHorizontalOffset(rightPts, yBuffer, bufW, bufH, rowStride, pixelStride, rotation)
                        val combined = when {
                            leftOff != null && rightOff != null -> (leftOff + rightOff) * 0.5f
                            leftOff != null -> leftOff
                            rightOff != null -> rightOff
                            else -> null
                        }
                        if (combined != null) {
                            irisMeasured = true
                            smoothedIrisOffset = smoothedIrisOffset * 0.52f + combined * 0.48f
                            irisPct = (abs(smoothedIrisOffset) * 100f).toInt().coerceIn(0, 100)
                        }
                    }

                    val irisOk = !cfg.irisTrackingEnabled || !irisMeasured || irisPct <= effIrisLimit
                    rawLooking = yawOk && pitchOk && eyesOk && irisOk && !faceTooFar && !drowsyNow && !multiFace
                    rawReason = when {
                        multiFace -> "👥 Plusieurs visages — Reste seul devant la caméra !"
                        faceTooFar -> "📏 Visage trop loin — Rapproche le téléphone !"
                        drowsyNow -> "😴 SOMNOLENCE DÉTECTÉE — Ouvre grand les yeux !"
                        !yawOk -> "👀 Tête tournée (${relYaw.toInt()}° > ±$effYawLimit°)"
                        !pitchOk -> "👀 Regard détourné haut/bas (${relPitch.toInt()}°)"
                        !eyesOk -> "🙈 Yeux fermés (${(eyeAvg * 100).toInt()} %)"
                        !irisOk -> "👁️ Pupilles détournées (Iris $irisPct % > $effIrisLimit %)"
                        else -> "✅ REGARD & IRIS FIXÉS"
                    }
                }

                // Blink-safe smoothing (instant trigger on camera cover!)
                if (rawLooking) {
                    if (lastRawLookingMs == 0L) lastRawLookingMs = now
                    lastRawAwayMs = 0L
                    if (!smoothedLooking && now - lastRawLookingMs >= 140L) smoothedLooking = true
                } else {
                    if (lastRawAwayMs == 0L) lastRawAwayMs = now
                    lastRawLookingMs = 0L
                    val graceMs = when {
                        cameraCovered -> 180L
                        rawReason.contains("Yeux fermés") -> 680L
                        else -> 480L
                    }
                    if (smoothedLooking && now - lastRawAwayMs >= graceMs) smoothedLooking = false
                }

                onReading(
                    GazeReading(
                        faceDetected = face != null,
                        yawDeg = yaw,
                        pitchDeg = pitch,
                        eyeOpenAvg = eyeAvg,
                        irisOffsetPct = irisPct,
                        irisMeasured = irisMeasured,
                        rawLooking = rawLooking,
                        isLookingAtScreen = smoothedLooking,
                        reason = if (smoothedLooking) "✅ REGARD & IRIS FIXÉS" else rawReason,
                        cameraReady = true,
                        cameraCovered = cameraCovered,
                        faceTooFar = faceTooFar,
                        drowsyDetected = drowsyNow,
                        multiFaceAlert = multiFace,
                        zeroCalibrated = zeroCalibrated
                    )
                )
            }
            .addOnCompleteListener {
                frameBusy.set(false)
                imageProxy.close()
            }
    }

    /** #9 Samples a 10x10 grid on the raw Y-plane to detect a covered / taped front camera in <0.05ms! */
    private fun isFrameCoveredDark(yBuf: ByteBuffer, bufW: Int, bufH: Int, rowStride: Int, pixelStride: Int): Boolean {
        if (bufW < 32 || bufH < 32) return false
        val cap = yBuf.capacity()
        var sum = 0
        var minL = 255
        var maxL = 0
        var count = 0
        val stepX = (bufW / 10).coerceAtLeast(1)
        val stepY = (bufH / 10).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bufH) {
            var x = stepX / 2
            while (x < bufW) {
                val idx = y * rowStride + x * pixelStride
                if (idx in 0 until cap) {
                    val lum = yBuf.get(idx).toInt() and 0xFF
                    sum += lum
                    if (lum < minL) minL = lum
                    if (lum > maxL) maxL = lum
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count < 20) return false
        val avg = sum / count
        val spread = maxL - minL
        return avg < 15 && spread < 18
    }

    private fun estimateIrisHorizontalOffset(
        points: List<PointF>?,
        yBuf: ByteBuffer,
        bufW: Int,
        bufH: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDeg: Int
    ): Float? {
        if (points == null || points.size < 6) return null
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val eyeW = maxX - minX
        val eyeH = maxY - minY
        if (eyeW < 10f || eyeH < 3.5f) return null

        val x0 = (minX + eyeW * 0.10f).toInt()
        val x1 = (maxX - eyeW * 0.10f).toInt()
        val y0 = (minY + eyeH * 0.18f).toInt()
        val y1 = (maxY - eyeH * 0.18f).toInt()
        if (x1 <= x0 + 3 || y1 <= y0 + 1) return null

        val cap = yBuf.capacity()
        var minLum = 255
        var maxLum = 0
        val step = if (eyeW > 40f) 2 else 1

        fun sampleLum(mx: Int, my: Int): Int {
            val bx: Int; val by: Int
            when (rotationDeg) {
                90 -> { bx = my; by = bufH - 1 - mx }
                180 -> { bx = bufW - 1 - mx; by = bufH - 1 - my }
                270 -> { bx = bufW - 1 - my; by = mx }
                else -> { bx = mx; by = my }
            }
            if (bx !in 0 until bufW || by !in 0 until bufH) return -1
            val idx = by * rowStride + bx * pixelStride
            if (idx !in 0 until cap) return -1
            return yBuf.get(idx).toInt() and 0xFF
        }

        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val lum = sampleLum(x, y)
                if (lum >= 0) {
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum
                }
                x += step
            }
            y += step
        }
        val contrast = maxLum - minLum
        if (contrast < 18) return null

        val darkCutoff = minLum + (contrast * 0.28f).toInt()
        var sumWx = 0f
        var sumW = 0f
        y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val lum = sampleLum(x, y)
                if (lum in 0..darkCutoff) {
                    val w = (darkCutoff - lum + 1).toFloat()
                    sumWx += x * w
                    sumW += w
                }
                x += step
            }
            y += step
        }
        if (sumW <= 0f) return null
        val pupilX = sumWx / sumW
        val centerX = (minX + maxX) * 0.5f
        return ((pupilX - centerX) / (eyeW * 0.42f)).coerceIn(-1f, 1f)
    }

    fun stop() {
        started = false
        try {
            ContextCompat.getMainExecutor(context).execute {
                try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
                cameraProvider = null
            }
        } catch (_: Throwable) {}
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
    }

    fun shutdown() {
        stop()
        try { analysisExecutor.shutdownNow() } catch (_: Throwable) {}
    }
}
