package com.sentinelcam.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.sentinelcam.app.plugin.SentinelNativePlugin
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SentinelNativePlugin::class.java)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Promote/start the continuous ForegroundService ONLY in onResume when Activity is guaranteed TOP-visible
        try {
            val prefs = PortalPreferences(this)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                CameraForegroundService.startFromUi(this)
            }
        } catch (_: Throwable) {
        }
    }
}
