package com.sentinelcam.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Standard Android Device Administration Receiver (Parental Control / Background Protection).
 * Protects the background portal against accidental stopping or uninstallation when explicitly
 * activated by the device owner with an Administrator PIN code.
 */
class SentinelDeviceAdminReceiver : DeviceAdminReceiver() {

    @Suppress("DEPRECATION")
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        try {
            val extras = intent.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
            MdmProvisioningHelper.applyManagedDeviceProvisioning(context, extras)
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launch)
        } catch (_: Throwable) {
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        try {
            val prefs = PortalPreferences(context)
            prefs.adminProtectionEnabled = true
            prefs.appendLog("security", "Protection administrateur activée.")
            CameraForegroundService.syncAccessChannels(context)
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
            if (prefs.parentManaged) {
                prefs.appendSecurityEvent(
                    "admin",
                    "Droits administrateur retirés",
                    "La protection anti-désinstallation a été désactivée sur l’appareil."
                )
            }
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
