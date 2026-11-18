package com.sentinelcam.app

import android.content.Intent
import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.sentinelcam.app.plugin.SentinelNativePlugin
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SentinelNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        handleWakeDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        try {
            val prefs = PortalPreferences(this)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                CameraForegroundService.startFromUi(this)
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleWakeDeepLink(intent: Intent?) {
        try {
            val uri = intent?.data ?: return
            if (uri.scheme == "sentinelcam" && uri.host == "wake") {
                val prefs = PortalPreferences(this)
                if (prefs.onboardingCompleted) {
                    prefs.userManuallyStopped = false
                    CameraForegroundService.startFromUi(this)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
