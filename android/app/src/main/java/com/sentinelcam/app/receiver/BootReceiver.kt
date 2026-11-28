package com.sentinelcam.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.selfcontrol.SelfControlStore
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.service.PortalWatchdogJobService
import com.sentinelcam.app.storage.PortalPreferences
import com.sentinelcam.app.vpn.SentinelDnsVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                val prefs = PortalPreferences(context)
                val sc = SelfControlStore(context)
                if (sc.dnsVpnEnabled) {
                    SentinelDnsVpnService.ensureRunningIfEnabled(context)
                }
                if ((prefs.onboardingCompleted || sc.selfControlEnabled) && !prefs.userManuallyStopped) {
                    PortalWatchdogJobService.scheduleWatchdog(context)
                    KeepAliveReceiver.scheduleNextPulse(context)
                    if (prefs.autoStartOnBoot ||
                        prefs.parentManaged ||
                        sc.selfControlEnabled ||
                        action == Intent.ACTION_USER_PRESENT ||
                        action == Intent.ACTION_MY_PACKAGE_REPLACED
                    ) {
                        CameraForegroundService.ensureRunningSafely(context)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
