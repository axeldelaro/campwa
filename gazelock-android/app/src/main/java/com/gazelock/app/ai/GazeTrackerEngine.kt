package com.gazelock.app.ai

import android.annotation.SuppressLint
import android.content.Context
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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 100% On-Device Local AI Gaze & Eye-Attention Tracker (CameraX + Google ML Kit Face Detection):
 * - Runs front camera at ~12-15 FPS completely offline (zero cloud, zero recording/saving of images!).
 * - Evaluates Face presence, Head Yaw (left/right), Head Pitch (up/down) and Left/Right Eye Open probabilities.
 * - Includes smart blink/jitter debouncing (520ms grace before declaring "Look Away", 150ms fast resume!).
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
        val rawLooking: Boolean,
        val isLookingAtScreen: Boolean,
        val reason: String,
        val cameraReady: Boolean = true
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null

    @Volatile
    private var activeConfig: GazeConfigSnapshot? = null

    @Volatile
    private var started = false

    // Temporal debouncing state (prevents blinks from pausing the timer!)
    private var lastRawLookingMs = 0L
    private var lastRawAwayMs = 0L
    private var smoothedLooking = true

    fun updateConfig(cfg: GazeConfigSnapshot) {
        activeConfig = cfg
    }

    fun start(lifecycleOwner: LifecycleOwner, cfg: GazeConfigSnapshot) {
        activeConfig = cfg
        if (started) return
        started = true
        val now = SystemClock.elapsedRealtime()
        lastRawLookingMs = now
        lastRawAwayMs = 0L
        smoothedLooking = true

        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.14f)
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
        val input = InputImage.fromMediaImage(mediaImg, rotation)
        det.process(input)
            .addOnSuccessListener { faces ->
                val cfg = activeConfig
                val now = SystemClock.elapsedRealtime()
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

                val yaw: Float
                val pitch: Float
                val eyeAvg: Float
                val rawLooking: Boolean
                val rawReason: String

                if (face == null || cfg == null) {
                    yaw = 0f; pitch = 0f; eyeAvg = 0f
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
                    val yawOk = abs(yaw) <= cfg.yawThresholdDeg
                    val pitchOk = abs(pitch) <= cfg.pitchThresholdDeg
                    val eyesOk = !cfg.requireEyesOpen || maxEye >= 0.34f

                    rawLooking = yawOk && pitchOk && eyesOk
                    rawReason = when {
                        !yawOk -> "👀 Tête tournée (${yaw.toInt()}° > ±${cfg.yawThresholdDeg}°)"
                        !pitchOk -> "👀 Regard détourné haut/bas (${pitch.toInt()}°)"
                        !eyesOk -> "🙈 Yeux fermés (${(eyeAvg * 100).toInt()} %)"
                        else -> "✅ REGARD FIXE SUR L'ÉCRAN"
                    }
                }

                // Smart blink-safe smoothing: 520ms away before pausing, 140ms quick resume!
                if (rawLooking) {
                    if (lastRawLookingMs == 0L) lastRawLookingMs = now
                    lastRawAwayMs = 0L
                    if (!smoothedLooking && now - lastRawLookingMs >= 140L) {
                        smoothedLooking = true
                    }
                } else {
                    if (lastRawAwayMs == 0L) lastRawAwayMs = now
                    lastRawLookingMs = 0L
                    val graceMs = if (rawReason.contains("Yeux fermés")) 700L else 520L
                    if (smoothedLooking && now - lastRawAwayMs >= graceMs) {
                        smoothedLooking = false
                    }
                }

                onReading(
                    GazeReading(
                        faceDetected = face != null,
                        yawDeg = yaw,
                        pitchDeg = pitch,
                        eyeOpenAvg = eyeAvg,
                        rawLooking = rawLooking,
                        isLookingAtScreen = smoothedLooking,
                        reason = if (smoothedLooking) "✅ REGARD FIXE SUR L'ÉCRAN" else rawReason,
                        cameraReady = true
                    )
                )
            }
            .addOnCompleteListener {
                frameBusy.set(false)
                imageProxy.close()
            }
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
