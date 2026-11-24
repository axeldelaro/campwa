package com.gazelock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gazelock.app.config.GazeConfig

/**
 * #26 Immortal Mode Receiver:
 * On device reboot (`BOOT_COMPLETED`) or screen unlock (`USER_PRESENT`), if an active GazeLock session
 * was interrupted before completing, automatically resumes it with +45s escape penalty!
 */
class GazeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_PRESENT) return
        if (GazeSessionService.isSessionRunning) return
        try {
            val cfg = GazeConfig(context.applicationContext)
            if (!cfg.snapshot().rebootSurvivalEnabled) return
            val resumeSec = cfg.consumeUnfinishedCheckpointSec() ?: return
            GazeSessionService.startSession(context.applicationContext, overrideDurationSec = resumeSec, resumedFromCrash = true)
        } catch (_: Throwable) {}
    }
}
