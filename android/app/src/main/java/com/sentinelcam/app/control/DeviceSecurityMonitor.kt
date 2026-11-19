package com.sentinelcam.app.control

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.sentinelcam.app.storage.PortalPreferences
import com.sentinelcam.app.storage.SecurityEventItem
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local Zero-Server Device Security Monitor:
 * - SIM card removal / operator change detection (non-privileged API, Android 8–15 safe)
 * - Geofencing safe-zone entry/exit evaluation with hysteresis
 * - Critical battery (<10%) alert tracking
 * - Discreet Camera2 front snapshot on failed unlock / wrong Admin PIN (<= 15 KB JPEG)
 */
object DeviceSecurityMonitor {

    fun checkSimState(context: Context, prefs: PortalPreferences): SecurityEventItem? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            val state = tm.simState
            val op = tm.simOperator.orEmpty()
            val opName = tm.simOperatorName.orEmpty()
            val country = tm.simCountryIso.orEmpty()
            val fp = "$state|$op|$opName|$country"

            val previous = prefs.lastSimFingerprint
            if (previous.isEmpty()) {
                prefs.lastSimFingerprint = fp
                return null
            }
            if (previous == fp) return null

            val prevState = previous.substringBefore("|").toIntOrNull() ?: TelephonyManager.SIM_STATE_UNKNOWN
            prefs.lastSimFingerprint = fp

            when {
                state == TelephonyManager.SIM_STATE_ABSENT && prevState == TelephonyManager.SIM_STATE_READY -> {
                    prefs.appendSecurityEvent(
                        kind = "sim",
                        title = "Carte SIM retirée",
                        detail = "La carte SIM a été retirée ou n’est plus détectée."
                    )
                }

                state == TelephonyManager.SIM_STATE_READY &&
                    prevState == TelephonyManager.SIM_STATE_READY &&
                    op.isNotEmpty() &&
                    !previous.contains("|$op|") -> {
                    prefs.appendSecurityEvent(
                        kind = "sim",
                        title = "Changement de carte SIM",
                        detail = "Nouvel opérateur détecté : ${opName.ifBlank { op }}."
                    )
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun evaluateBattery(level: Int, isCharging: Boolean, prefs: PortalPreferences): SecurityEventItem? {
        if (level <= 0) return null
        if (isCharging || level >= 18) {
            if (prefs.lowBatteryAlertSent) {
                prefs.lowBatteryAlertSent = false
            }
            return null
        }
        if (level <= 10 && !prefs.lowBatteryAlertSent) {
            prefs.lowBatteryAlertSent = true
            return prefs.appendSecurityEvent(
                kind = "battery",
                title = "Batterie critique ($level%)",
                detail = "Le téléphone risque de s’éteindre prochainement."
            )
        }
        return null
    }

    fun evaluateGeofences(lat: Double, lng: Double, prefs: PortalPreferences): List<SecurityEventItem> {
        val zones = prefs.getGeofences()
        if (zones.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val events = ArrayList<SecurityEventItem>()

        for (zone in zones) {
            val dist = haversineMeters(lat, lng, zone.latitude, zone.longitude)
            val prevInside = zone.inside
            val nextInside = when (prevInside) {
                null -> dist <= zone.radiusMeters
                true -> dist <= (zone.radiusMeters + 25.0) // 25m hysteresis to prevent GPS jitter
                false -> dist <= (zone.radiusMeters - 15.0).coerceAtLeast(30.0)
            }

            if (prevInside == null) {
                prefs.updateGeofenceInside(zone.id, nextInside, now)
            } else if (prevInside != nextInside) {
                prefs.updateGeofenceInside(zone.id, nextInside, now)
                val ev = prefs.appendSecurityEvent(
                    kind = "geofence",
                    title = if (nextInside) "Entrée : ${zone.name}" else "Sortie : ${zone.name}",
                    detail = if (nextInside) {
                        "L’appareil est entré dans la zone ${zone.name} (${dist.toInt()} m)."
                    } else {
                        "L’appareil a quitté la zone ${zone.name} (${dist.toInt()} m du centre)."
                    }
                )
                events.add(ev)
            }
        }
        return events
    }

    /**
     * Captures a compact ~10-15 KB JPEG thumbnail from the front camera without UI preview.
     * Returns null safely if permission is missing, camera is in use, or timeout expires.
     */
    @SuppressLint("MissingPermission")
    fun captureFrontSnapshotBase64(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val thread = HandlerThread("SentinelIntruderCam").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var resultB64: String? = null
        var cameraDeviceRef: CameraDevice? = null
        var imageReaderRef: ImageReader? = null

        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var frontId: String? = null
            var sensorOrientation = 270
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontId = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                    break
                }
            }
            val camId = frontId ?: return null

            val reader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 1)
            imageReaderRef = reader

            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        try {
                            val buf = img.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            resultB64 = compressAndRotateThumbnail(bytes, sensorOrientation)
                        } finally {
                            img.close()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }, handler)

            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.JPEG_QUALITY, 65.toByte())
                                        }.build()
                                        session.capture(req, null, handler)
                                    } catch (_: Throwable) {
                                        latch.countDown()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    latch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (_: Throwable) {
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    latch.countDown()
                }
            }, handler)

            latch.await(3_200L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Silently ignore if camera busy
        } finally {
            try {
                cameraDeviceRef?.close()
            } catch (_: Throwable) {
            }
            try {
                imageReaderRef?.close()
            } catch (_: Throwable) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    thread.quitSafely()
                } else {
                    thread.quit()
                }
            } catch (_: Throwable) {
            }
        }
        return resultB64
    }

    private fun compressAndRotateThumbnail(jpegBytes: ByteArray, sensorOrientation: Int): String? {
        val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        return try {
            val matrix = Matrix().apply {
                if (sensorOrientation != 0) postRotate(sensorOrientation.toFloat())
            }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            val maxDim = 240
            val scale = maxDim.toFloat() / maxOf(rotated.width, rotated.height).coerceAtLeast(1)
            val finalBmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    rotated,
                    (rotated.width * scale).toInt().coerceAtLeast(1),
                    (rotated.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                rotated
            }
            val out = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
            if (finalBmp !== rotated) finalBmp.recycle()
            if (rotated !== raw) rotated.recycle()
            raw.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            raw.recycle()
            null
        }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2.0 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
