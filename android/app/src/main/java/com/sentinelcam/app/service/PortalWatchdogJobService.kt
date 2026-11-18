package com.sentinelcam.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Persisted Android JobScheduler Watchdog.
 * Automatically checks every 15 minutes (and whenever network connectivity returns) that
 * CameraForegroundService is alive and listening, reviving it safely without crashing.
 */
class PortalWatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            val prefs = PortalPreferences(applicationContext)
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                CameraForegroundService.ensureRunningSafely(applicationContext)
            }
        } catch (_: Throwable) {
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 9401

        fun scheduleWatchdog(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val component = ComponentName(context, PortalWatchdogJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID, component)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
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
                    .setMinimumLatency(1500L)
                    .setOverrideDeadline(4000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                scheduler.schedule(jobInfo)
            } catch (_: Throwable) {
            }
        }

        fun cancelAll(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                scheduler.cancel(JOB_ID)
                scheduler.cancel(JOB_ID + 1)
            } catch (_: Throwable) {
            }
        }
    }
}
