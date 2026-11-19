package com.sentinelcam.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Standard Android Device Administration Receiver (Parental Control / Background Protection).
 * Protects the background portal against accidental stopping or uninstallation when explicitly
 * activated by the device owner with an Administrator PIN code.
 */
class SentinelDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        try {
            val prefs = PortalPreferences(context)
            prefs.appendLog("security", "Protection administrateur activée.")
        } catch (_: Throwable) {
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "La désactivation de la protection administrateur permet d’arrêter ou désinstaller SentinelCam."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        try {
            val prefs = PortalPreferences(context)
            prefs.adminProtectionEnabled = false
            prefs.appendLog("security", "Protection administrateur désactivée.")
            CameraForegroundService.syncAccessChannels(context)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        try {
            val prefs = PortalPreferences(context)
            val fails = prefs.lockscreenFailCount + 1
            prefs.lockscreenFailCount = fails
            if (fails >= 3) {
                CameraForegroundService.reportLockscreenIntrusion(context, fails)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        try {
            PortalPreferences(context).lockscreenFailCount = 0
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SentinelDeviceAdminReceiver::class.java)
        }
    }
}
