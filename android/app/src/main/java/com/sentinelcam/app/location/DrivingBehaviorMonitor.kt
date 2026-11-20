package com.sentinelcam.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.sentinelcam.app.control.ScreenTimeHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ultra-Low-Battery Driving Behavior & Score Engine (v25):
 * - 0% IMU battery when walking or stationary! Hardware linear acceleration sensor is ONLY
 *   registered while GPS speed >= 18 km/h and automatically unregistered 45s after stopping.
 * - Detects:
 *   1) Harsh Braking (`harsh_brake`) via IMU G-force peak or rapid GPS deceleration
 *   2) Harsh Acceleration (`harsh_accel`)
 *   3) Sharp / Dangerous Cornering (`sharp_turn`)
 *   4) Phone Handling While Driving (`phone_use`: screen ON & non-navigation app in foreground >= 10s at >= 30 km/h)
 *   5) Overspeed (`overspeed`: > 130 km/h with valid GPS accuracy)
 * - Persists up to 400 recent driving events locally in `filesDir/driving_events.json` (100% wiped on unpair).
 * - Computes a 0..100 Driving Safety Score over the requested window (7d / 30d).
 */
class DrivingBehaviorMonitor(
    context: Context,
    private val onCriticalDrivingEvent: (title: String, detail: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val linearAccelSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var imuRegistered = false

    @Volatile
    private var lastLat = 0.0

    @Volatile
    private var lastLng = 0.0

    @Volatile
    private var lastSpeedKmh = 0f

    @Volatile
    private var lastLocationTs = 0L

    private var prevGpsSpeedKmh = 0f
    private var prevGpsTs = 0L
    private var phoneUseStartMs = 0L
    private var phoneUseAppName = ""
    private var lastEventByKindMs = HashMap<String, Long>()

    private val disarmImuRunnable = Runnable {
        unregisterImu()
    }

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || lastSpeedKmh < 18f) return
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val horizMag = sqrt(ax * ax + ay * ay)
            val totalMag = sqrt(ax * ax + ay * ay + az * az)

            // Ignore tiny vibrations / pocket taps; look for real vehicle G-forces (> ~0.78g)
            if (totalMag < 7.6f) return

            val now = System.currentTimeMillis()
            if (now - lastLocationTs > 20_000L) return

            // Classify lateral cornering vs longitudinal brake/accel
            val lateral = abs(ax)
            val longitudinal = sqrt(ay * ay + az * az)
            if (lateral >= 7.4f && lateral > longitudinal * 1.15f && lastSpeedKmh >= 25f) {
                recordEventWithCooldown(
                    kind = "sharp_turn",
                    speedKmh = lastSpeedKmh,
                    severity = if (lateral >= 10.5f) "high" else "medium",
                    detail = "Virage brusque détecté à ${lastSpeedKmh.toInt()} km/h",
                    cooldownMs = 25_000L
                )
            } else if (horizMag >= 8.2f && lastSpeedKmh >= 22f) {
                // Use GPS delta when available to distinguish brake vs accel
                val kind = if (lastSpeedKmh < prevGpsSpeedKmh - 4f) "harsh_brake" else "harsh_accel"
                val label = if (kind == "harsh_brake") {
                    "Freinage brusque à ${lastSpeedKmh.toInt()} km/h"
                } else {
                    "Accélération brusque à ${lastSpeedKmh.toInt()} km/h"
                }
                recordEventWithCooldown(
                    kind = kind,
                    speedKmh = lastSpeedKmh,
                    severity = if (horizMag >= 11.2f) "high" else "medium",
                    detail = label,
                    cooldownMs = 25_000L
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Called directly from SmartLocationTracker whenever a validated GPS location arrives (0 extra GPS cost!).
     */
    fun onGpsFix(location: Location, speedMps: Float) {
        val now = System.currentTimeMillis()
        val speedKmh = (speedMps * 3.6f).coerceIn(0f, 320f)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 20f

        lastLat = location.latitude
        lastLng = location.longitude
        lastSpeedKmh = speedKmh
        lastLocationTs = now

        // 1. Dynamic Battery Gate: ONLY arm IMU sensors while in a moving vehicle >= 18 km/h!
        if (speedKmh >= 18f && accuracy <= 55f) {
            mainHandler.removeCallbacks(disarmImuRunnable)
            if (!imuRegistered) {
                mainHandler.post { registerImu() }
            }
            mainHandler.postDelayed(disarmImuRunnable, 45_000L)
        } else if (speedKmh < 6f && imuRegistered) {
            mainHandler.removeCallbacks(disarmImuRunnable)
            mainHandler.postDelayed(disarmImuRunnable, 15_000L)
        }

        // 2. GPS-based Harsh Braking / Harsh Acceleration detection (works even on devices without linear accel)
        val dtSec = ((now - prevGpsTs) / 1000f)
        if (prevGpsTs > 0L && dtSec in 1.2f..6.0f && accuracy <= 45f) {
            val deltaKmh = speedKmh - prevGpsSpeedKmh
            val rateKmhPerSec = deltaKmh / dtSec
            if (rateKmhPerSec <= -11.5f && prevGpsSpeedKmh >= 35f) {
                recordEventWithCooldown(
                    kind = "harsh_brake",
                    speedKmh = prevGpsSpeedKmh,
                    severity = if (rateKmhPerSec <= -16f) "high" else "medium",
                    detail = "Freinage d’urgence (${prevGpsSpeedKmh.toInt()} → ${speedKmh.toInt()} km/h)",
                    cooldownMs = 25_000L
                )
            } else if (rateKmhPerSec >= 10.5f && speedKmh >= 35f) {
                recordEventWithCooldown(
                    kind = "harsh_accel",
                    speedKmh = speedKmh,
                    severity = if (rateKmhPerSec >= 14.5f) "high" else "medium",
                    detail = "Accélération brutale (${prevGpsSpeedKmh.toInt()} → ${speedKmh.toInt()} km/h)",
                    cooldownMs = 25_000L
                )
            }
        }
        prevGpsSpeedKmh = speedKmh
        prevGpsTs = now

        // 3. Overspeed > 130 km/h
        if (speedKmh > 130f && speedKmh < 320f && accuracy <= 55f) {
            recordEventWithCooldown(
                kind = "overspeed",
                speedKmh = speedKmh,
                severity = if (speedKmh >= 150f) "high" else "medium",
                detail = "Excès de vitesse à ${speedKmh.toInt()} km/h",
                cooldownMs = 180_000L
            )
        }

        // 4. Phone Manipulation While Moving >= 30 km/h (excluding Maps/Waze/Phone call)
        val screenOn = try { powerManager.isInteractive } catch (_: Throwable) { false }
        if (speedKmh >= 30f && accuracy <= 55f && screenOn) {
            val fgPkg = ScreenTimeHelper.detectCurrentForegroundPackage(appContext)
            if (!fgPkg.isNullOrEmpty() && !isAllowedDrivingApp(fgPkg) && !ScreenTimeHelper.isProtectedSystemPackage(appContext, fgPkg)) {
                val appLabel = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg)
                if (phoneUseStartMs == 0L) {
                    phoneUseStartMs = now
                    phoneUseAppName = appLabel
                } else if ((now - phoneUseStartMs) >= 12_000L) {
                    recordEventWithCooldown(
                        kind = "phone_use",
                        speedKmh = speedKmh,
                        severity = "high",
                        detail = "Téléphone utilisé en roulant ($appLabel à ${speedKmh.toInt()} km/h)",
                        cooldownMs = 180_000L,
                        triggerAlert = true
                    )
                    phoneUseStartMs = now
                }
            } else {
                phoneUseStartMs = 0L
            }
        } else {
            phoneUseStartMs = 0L
        }
    }

    private fun isAllowedDrivingApp(pkg: String): Boolean {
        val low = pkg.lowercase(Locale.US)
        return low.contains("maps") ||
            low.contains("waze") ||
            low.contains("androidauto") ||
            low.contains("projection.gearhead") ||
            low.contains("spotify") ||
            low.contains("coyote") ||
            low.contains("tomtom") ||
            low.contains("sygic") ||
            low == appContext.packageName
    }

    private fun registerImu() {
        if (imuRegistered) return
        val sensor = linearAccelSensor ?: return
        try {
            // SENSOR_DELAY_NORMAL (~5 Hz) is plenty for vehicle braking/cornering & uses minimal battery!
            val ok = sensorManager?.registerListener(imuListener, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
            imuRegistered = ok
        } catch (_: Throwable) {
            imuRegistered = false
        }
    }

    private fun unregisterImu() {
        if (!imuRegistered) return
        try {
            sensorManager?.unregisterListener(imuListener)
        } catch (_: Throwable) {
        }
        imuRegistered = false
    }

    fun destroy() {
        mainHandler.removeCallbacks(disarmImuRunnable)
        unregisterImu()
    }

    private fun recordEventWithCooldown(
        kind: String,
        speedKmh: Float,
        severity: String,
        detail: String,
        cooldownMs: Long,
        triggerAlert: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        synchronized(storeLock) {
            val last = lastEventByKindMs[kind] ?: 0L
            if (now - last < cooldownMs) return
            lastEventByKindMs[kind] = now

            val obj = JSONObject().apply {
                put("id", "drv_${now}_${UUID.randomUUID().toString().take(4)}")
                put("timestamp", now)
                put("kind", kind)
                put("severity", severity)
                put("speedKmh", speedKmh.toInt())
                put("latitude", lastLat)
                put("longitude", lastLng)
                put("detail", detail)
            }

            val arr = readEventsLocked(appContext)
            val next = JSONArray()
            next.put(obj)
            val maxKeep = 400
            for (i in 0 until minOf(arr.length(), maxKeep - 1)) {
                next.put(arr.getJSONObject(i))
            }
            writeEventsLocked(appContext, next)
        }
        if (triggerAlert) {
            onCriticalDrivingEvent("Téléphone en roulant", detail)
        }
    }

    companion object {
        private val storeLock = Any()

        private fun getFile(context: Context): File {
            return File(context.applicationContext.filesDir, "driving_events.json")
        }

        private fun readEventsLocked(context: Context): JSONArray {
            return try {
                val f = getFile(context)
                if (!f.exists()) JSONArray() else JSONArray(f.readText())
            } catch (_: Throwable) {
                JSONArray()
            }
        }

        private fun writeEventsLocked(context: Context, arr: JSONArray) {
            try {
                getFile(context).writeText(arr.toString())
            } catch (_: Throwable) {
            }
        }

        fun buildDrivingReport(
            context: Context,
            locationStore: LocationHistoryStore?,
            days: Int = 7
        ): JSONObject {
            val safeDays = days.coerceIn(1, 30)
            val now = System.currentTimeMillis()
            val fromTs = now - safeDays * 86_400_000L

            var harshBrakes = 0
            var harshAccels = 0
            var sharpTurns = 0
            var phoneUseCount = 0
            var overspeedCount = 0
            var maxSpeedEvent = 0

            val filteredArr = JSONArray()
            synchronized(storeLock) {
                val raw = readEventsLocked(context)
                for (i in 0 until raw.length()) {
                    val o = raw.optJSONObject(i) ?: continue
                    if (o.optLong("timestamp", 0L) < fromTs) continue
                    val kind = o.optString("kind")
                    val spd = o.optInt("speedKmh", 0)
                    if (spd > maxSpeedEvent) maxSpeedEvent = spd
                    when (kind) {
                        "harsh_brake" -> harshBrakes++
                        "harsh_accel" -> harshAccels++
                        "sharp_turn" -> sharpTurns++
                        "phone_use" -> phoneUseCount++
                        "overspeed" -> overspeedCount++
                    }
                    if (filteredArr.length() < 120) {
                        filteredArr.put(o)
                    }
                }
            }

            // Enrich with GPS track distance & max speed from SQLite
            var totalDistKm = 0.0
            var gpsMaxSpeedKmh = maxSpeedEvent.toDouble()
            try {
                if (locationStore != null) {
                    val res = locationStore.queryRange(fromTs, now, 1200)
                    totalDistKm = res.totalDistanceMeters / 1000.0
                    if (res.maxSpeedKmh > gpsMaxSpeedKmh) gpsMaxSpeedKmh = res.maxSpeedKmh
                }
            } catch (_: Throwable) {
            }

            // Calculate fair 0..100 Safety Score (starts at 100, weighted deductions)
            val penalty = (harshBrakes * 4) +
                (harshAccels * 3) +
                (sharpTurns * 4) +
                (phoneUseCount * 8) +
                (overspeedCount * 10)
            val score = (100 - penalty).coerceIn(10, 100)

            return JSONObject().apply {
                put("days", safeDays)
                put("score", score)
                put("totalDistanceKm", Math.round(totalDistKm * 10.0) / 10.0)
                put("maxSpeedKmh", Math.round(gpsMaxSpeedKmh).toInt())
                put("harshBrakes", harshBrakes)
                put("harshAccels", harshAccels)
                put("sharpTurns", sharpTurns)
                put("phoneUseCount", phoneUseCount)
                put("overspeedCount", overspeedCount)
                put("events", filteredArr)
            }
        }

        fun clearAll(context: Context) {
            synchronized(storeLock) {
                try {
                    getFile(context).delete()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
