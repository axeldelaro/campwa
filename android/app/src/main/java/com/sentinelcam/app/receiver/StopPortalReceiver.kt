package com.sentinelcam.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Handles Notification Quick Actions ("Couper le portail" / "Mettre en veille") via BroadcastReceiver
 * instead of Service start intents — completely eliminating ForegroundServiceDidNotStartInTimeException
 * crashes when stopping the portal on Android 8-15.
 */
class StopPortalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_USER_STOP_PORTAL -> {
                try {
                    val prefs = PortalPreferences(context)
                    prefs.userManuallyStopped = true
                    prefs.appendLog("security", "Portail mis en pause depuis la notification.")
                    CameraForegroundService.stopPortal(context)
                } catch (_: Throwable) {
                }
            }

            ACTION_USER_SLEEP_CAMERA -> {
                try {
                    CameraForegroundService.sleepCameraFromNotification(context)
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        const val ACTION_USER_STOP_PORTAL = "com.sentinelcam.app.ACTION_USER_STOP_PORTAL"
        const val ACTION_USER_SLEEP_CAMERA = "com.sentinelcam.app.ACTION_USER_SLEEP_CAMERA"
    }
}
