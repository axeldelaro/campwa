package com.sentinelcam.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.sentinelcam.app.plugin.SentinelNativePlugin
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Register custom native Capacitor plugin BEFORE super.onCreate()
        registerPlugin(SentinelNativePlugin::class.java)
        super.onCreate(savedInstanceState)

        // If one-time onboarding was already completed and user didn't manually stop the portal,
        // automatically ensure the resilient ForegroundService is running with full Camera/Mic FGS eligibility.
        val prefs = PortalPreferences(this)
        if (prefs.onboardingCompleted && !prefs.userManuallyStopped && !CameraForegroundService.isRunning) {
            CameraForegroundService.startPortal(this)
        }
    }
}
