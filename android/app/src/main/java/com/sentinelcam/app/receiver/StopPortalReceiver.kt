package com.sentinelcam.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.MainActivity
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
                    if (prefs.parentManaged || (prefs.adminProtectionEnabled && prefs.hasAdminPin())) {
                        // Parent-managed / Admin Lock active: child cannot stop the service from notification
                        val open = Intent(context.applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.applicationContext.startActivity(open)
                        return
                    }
                    prefs.userManuallyStopped = true
                    prefs.appendLog("security", "Service mis en pause depuis la notification.")
                    CameraForegroundService.stopPortal(context)
                } catch (_: Throwable) {
                }
            }

            ACTION_USER_SLEEP_CAMERA -> {
                try {
                    val prefs = PortalPreferences(context)
                    if (prefs.parentManaged) return
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
