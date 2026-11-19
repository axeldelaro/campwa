package com.sentinelcam.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.storage.PortalPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Eco Watchdog & Serverless Push-Invitation Poller.
 * 1. Ensures CameraForegroundService stays alive if enabled.
 * 2. Even if the user paused/closed the app, checks for incoming Push Invitations (`ntfy.sh`)
 *    and posts a clickable system notification allowing 1-tap wake!
 */
class PortalWatchdogJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        jobScope.launch {
            try {
                val prefs = PortalPreferences(applicationContext)
                if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                    CameraForegroundService.ensureRunningSafely(applicationContext)
                } else if (prefs.onboardingCompleted) {
                    checkIncomingPushInvitation(applicationContext, prefs.pairingCode)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    jobFinished(params, false)
                } catch (_: Throwable) {
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    private fun checkIncomingPushInvitation(context: Context, code: String) {
        try {
            val keys = CryptoProtocol.deriveBootstrapKeys(code)
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://ntfy.sh/${keys.wakePushTopic}/json?poll=1&since=15m")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.contains("WAKE_INVITE")) {
                    postInviteNotification(context, code)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun postInviteNotification(context: Context, code: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sentinelcam_invite_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    channelId,
                    "Invitations SentinelCam",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(ch)
            }

            val wakeIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse("sentinelcam://wake?code=$code")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                991,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Invitation de connexion SentinelCam")
                .setContentText("Appuyez pour réveiller l’application et autoriser le flux ($code)")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .build()

            nm.notify(4409, notif)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val JOB_ID = 9401

        fun scheduleWatchdog(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val component = ComponentName(context, PortalWatchdogJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID, component)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Throwable) {
            }
        }

        fun scheduleImmediateRevive(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val component = ComponentName(context, PortalWatchdogJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID + 1, component)
                    .setMinimumLatency(1_200L)
                    .setOverrideDeadline(3_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Throwable) {
            }
        }
    }
}
