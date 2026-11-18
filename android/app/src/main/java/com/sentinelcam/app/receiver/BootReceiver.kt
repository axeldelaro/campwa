package com.sentinelcam.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Automatically relaunches the continuous SentinelCam ForegroundService after a smartphone
 * reboot or APK update, provided the user completed informed-consent onboarding and enabled boot start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = PortalPreferences(context)
            if (prefs.onboardingCompleted && prefs.autoStartOnBoot && !prefs.userManuallyStopped) {
                prefs.appendLog("info", "Relance automatique du portail après démarrage système.")
                try {
                    CameraForegroundService.startPortal(context)
                } catch (_: Exception) {
                    // Safely ignored if restricted by OEM background policy before first unlock
                }
            }
        }
    }
}
