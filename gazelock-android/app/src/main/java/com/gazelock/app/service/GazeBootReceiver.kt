package com.gazelock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gazelock.app.config.GazeConfig

/**
 * #26 & #6 Immortal Mode Receiver (v7.0 TITAN):
 * On device reboot (`BOOT_COMPLETED`) or screen unlock (`USER_PRESENT`), resumes any interrupted
 * Video Session (+45s escape tax) OR interrupted Locked Image Pop-Up Storm (+60s or ∞)!
 */
class GazeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_PRESENT) return
        try {
            val gCfg = GazeConfig(context.applicationContext)
            val snap = gCfg.snapshot()

            if (!GazeSessionService.isSessionRunning && snap.rebootSurvivalEnabled) {
                val resumeSec = gCfg.consumeUnfinishedCheckpointSec()
                if (resumeSec != null) {
                    GazeSessionService.startSession(context.applicationContext, overrideDurationSec = resumeSec, resumedFromCrash = true)
                    return
                }
            }

            if (!ImagePopupStormService.isRunning && !GazeSessionService.isSessionRunning && snap.popupRebootSurvivalEnabled) {
                val popResume = gCfg.consumeUnfinishedPopupCheckpoint()
                if (popResume != null) {
                    ImagePopupStormService.start(
                        context.applicationContext,
                        resumedOverrideSec = popResume.first,
                        resumedInfinite = popResume.second
                    )
                }
            }
        } catch (_: Throwable) {}
    }
}
