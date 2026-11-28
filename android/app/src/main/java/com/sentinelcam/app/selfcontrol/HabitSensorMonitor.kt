package com.sentinelcam.app.selfcontrol

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * SentinelCam v28.0 — Ultra-Low-Power Habit & Physical Sensor Monitor:
 * 1. Hardware Step Counter (`Sensor.TYPE_STEP_COUNTER`): powers "Earn Your Time" steps
 *    and the Morning Anti-Bed Challenge (walk N steps to unlock social apps at wake-up).
 * 2. Under-Blanket Night Detector (`TYPE_LIGHT < 3.5 lux` + lying posture `TYPE_ACCELEROMETER`):
 *    ONLY listens during night hours when screen is ON & `bedDetectorEnabled == true` (0% daytime drain!).
 */
class HabitSensorMonitor(
    context: Context,
    private val onBedDetected: () -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val scStore = SelfControlStore(appContext)
    private val sensorPrefs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        appContext.createDeviceProtectedStorageContext()
    } else appContext).getSharedPreferences("sentinel_habit_sensors_v28", Context.MODE_PRIVATE)

    @Volatile
    private var stepListenerActive = false
    @Volatile
    private var nightSensorsActive = false

    private var lastLux: Float = 100f
    private var lastAccelZ: Float = 0f
    private var darkFlatStartMs: Long = 0L

    fun syncSensors(screenInteractive: Boolean) {
        val sm = sensorManager ?: return
        val needsSteps = scStore.selfControlEnabled && (scStore.earnStepsTarget > 0 || scStore.morningStepsRequired > 0)

        if (needsSteps && !stepListenerActive) {
            val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                try {
                    sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    stepListenerActive = true
                } catch (_: Throwable) {
                }
            }
        } else if (!needsSteps && stepListenerActive) {
            val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                try { sm.unregisterListener(this, stepSensor) } catch (_: Throwable) {}
            }
            stepListenerActive = false
        }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNightHours = hour >= 22 || hour < 6
        val needsNight = screenInteractive && scStore.selfControlEnabled && scStore.bedDetectorEnabled && isNightHours

        if (needsNight && !nightSensorsActive) {
            try {
                sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                nightSensorsActive = true
                darkFlatStartMs = 0L
            } catch (_: Throwable) {
            }
        } else if (!needsNight && nightSensorsActive) {
            unregisterNightSensors()
        }
    }

    private fun unregisterNightSensors() {
        val sm = sensorManager ?: return
        try {
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.unregisterListener(this, it) }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.unregisterListener(this, it) }
        } catch (_: Throwable) {
        }
        nightSensorsActive = false
        darkFlatStartMs = 0L
    }

    fun destroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
        stepListenerActive = false
        nightSensorsActive = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSinceBoot = event.values[0].toInt().coerceAtLeast(0)
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val storedDay = sensorPrefs.getString("step_day", "")
                var baseline = sensorPrefs.getInt("step_base", -1)
                if (storedDay != today || baseline < 0 || totalSinceBoot < baseline) {
                    baseline = totalSinceBoot
                    sensorPrefs.edit().putString("step_day", today).putInt("step_base", baseline).apply()
                }
                val todaySteps = (totalSinceBoot - baseline).coerceAtLeast(0)
                scStore.setStepsToday(todaySteps)
            }

            Sensor.TYPE_LIGHT -> {
                lastLux = event.values[0]
                evaluateUnderBlanket()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelZ = event.values[2]
                evaluateUnderBlanket()
            }
        }
    }

    private fun evaluateUnderBlanket() {
        if (!nightSensorsActive || !scStore.bedDetectorEnabled) return
        val now = System.currentTimeMillis()
        if (scStore.bedDetectorLockUntilMs > now) return

        // Pitch dark (< 3.5 lux) AND phone lying flat or held overhead in bed (|z| > 6.5 m/s^2)
        val isDark = lastLux < 3.5f
        val isLyingPosture = abs(lastAccelZ) > 6.5f || lastAccelZ < -3.5f

        if (isDark && isLyingPosture) {
            if (darkFlatStartMs == 0L) {
                darkFlatStartMs = now
            } else if (now - darkFlatStartMs >= 35_000L) {
                darkFlatStartMs = 0L
                scStore.bedDetectorLockUntilMs = now + 20 * 60_000L
                scStore.recordReclaimedAction("night:under_blanket", "bed_detector", estimatedSavedMin = 20)
                onBedDetected()
            }
        } else {
            darkFlatStartMs = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
