package com.sentinelcam.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Periodic Doze-piercing Keep-Alive Receiver.
 * Wakes the radio every 2 minutes when screen is locked to ensure WebSocket connections
 * never go stale and revives CameraForegroundService if an OEM battery cleaner stopped it.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val prefs = PortalPreferences(context)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                CameraForegroundService.ensureRunningSafely(context)
                scheduleNextPulse(context)
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val REQ_CODE = 8402
        private const val PULSE_INTERVAL_MS = 120_000L // 2 minutes

        fun scheduleNextPulse(context: Context) {
            try {
                val appCtx = context.applicationContext
                val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(appCtx, KeepAliveReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    appCtx,
                    REQ_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + PULSE_INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: Throwable) {
            }
        }

        fun cancelPulse(context: Context) {
            try {
                val appCtx = context.applicationContext
                val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(appCtx, KeepAliveReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    appCtx,
                    REQ_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(pi)
            } catch (_: Throwable) {
            }
        }
    }
}
