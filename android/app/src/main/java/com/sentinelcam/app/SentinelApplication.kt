package com.sentinelcam.app

import android.app.Application
import android.os.Looper
import android.util.Log
import com.sentinelcam.app.receiver.KeepAliveReceiver
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.service.PortalWatchdogJobService
import com.sentinelcam.app.storage.PortalPreferences

class SentinelApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashShield()

        try {
            val prefs = PortalPreferences(this)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                PortalWatchdogJobService.scheduleWatchdog(this)
                KeepAliveReceiver.scheduleNextPulse(this)
                CameraForegroundService.ensureRunningSafely(this)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Init watchdog error", t)
        }
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
                val prefs = PortalPreferences(this@SentinelApplication)
                prefs.appendLog(
                    "warn",
                    "Récupération automatique (${thread.name}): ${throwable.javaClass.simpleName}"
                )
                if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                    KeepAliveReceiver.scheduleImmediateRevive(this@SentinelApplication, 2_000L)
                    PortalWatchdogJobService.scheduleImmediateRevive(this@SentinelApplication)
                }
            } catch (_: Throwable) {
            }

            // Prevent non-UI background worker exceptions (vendor camera/socket quirks) from force-closing the app
            if (thread != Looper.getMainLooper().thread) {
                return@setDefaultUncaughtExceptionHandler
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "SentinelApp"
    }
}
