package com.sentinelcam.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.service.PortalWatchdogJobService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Doze-Piercing Self-Healing Keep-Alive & Instant Revival Receiver (v21):
 * - Revives CameraForegroundService within 2 seconds if the app is swiped away or killed by OEM.
 * - Keeps cellular/Wi-Fi signaling sockets alive with a lightweight 3-minute heartbeat (45s during recovery).
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val prefs = PortalPreferences(context)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                CameraForegroundService.ensureRunningSafely(context)
                PortalWatchdogJobService.scheduleWatchdog(context)
                scheduleNextPulse(context)
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val REQ_CODE = 8402
        private const val REQ_CODE_REVIVE = 8403
        private const val ECO_CONNECTED_INTERVAL_MS = 900_000L // 15 min doze-friendly eco heartbeat when connected
        private const val RECOVERY_INTERVAL_MS = 60_000L // 60 s recovery if offline/stopped

        fun scheduleImmediateRevive(context: Context, delayMs: Long = 2_000L) {
            try {
                val appCtx = context.applicationContext
                val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(appCtx, KeepAliveReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    appCtx,
                    REQ_CODE_REVIVE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceIn(1_200L, 15_000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: Throwable) {
            }
        }

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
                val healthy = CameraForegroundService.isRunning && CameraForegroundService.mqttConnected
                val intervalMs = if (healthy) ECO_CONNECTED_INTERVAL_MS else RECOVERY_INTERVAL_MS
                val triggerAt = SystemClock.elapsedRealtime() + intervalMs
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
                for (code in intArrayOf(REQ_CODE, REQ_CODE_REVIVE)) {
                    val intent = Intent(appCtx, KeepAliveReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(
                        appCtx,
                        code,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.cancel(pi)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
