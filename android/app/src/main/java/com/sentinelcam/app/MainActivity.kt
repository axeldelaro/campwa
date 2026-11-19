package com.sentinelcam.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import com.getcapacitor.BridgeActivity
import com.sentinelcam.app.plugin.SentinelNativePlugin
import com.sentinelcam.app.receiver.KeepAliveReceiver
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.service.PortalWatchdogJobService
import com.sentinelcam.app.storage.PortalPreferences

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SentinelNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        hardenWebView()
        handleWakeDeepLink(intent)
    }

    private fun hardenWebView() {
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            bridge?.webView?.settings?.apply {
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        } catch (_: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        try {
            val prefs = PortalPreferences(this)
            // Keep app out of swipeable Recents carousel once paired with Parent so it can NEVER be swiped closed accidentally
            applyExcludeFromRecents(prefs.parentManaged)

            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                if (!CameraForegroundService.isRunning || !CameraForegroundService.hasCameraFgsPromoted()) {
                    CameraForegroundService.startFromUi(this)
                }
                KeepAliveReceiver.scheduleNextPulse(this)
                PortalWatchdogJobService.scheduleWatchdog(this)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            val prefs = PortalPreferences(this)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                KeepAliveReceiver.scheduleImmediateRevive(applicationContext, 2_000L)
                PortalWatchdogJobService.scheduleImmediateRevive(applicationContext)
                CameraForegroundService.ensureRunningSafely(applicationContext)
            }
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun applyExcludeFromRecents(exclude: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (task in am.appTasks) {
                task.setExcludeFromRecents(exclude)
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
