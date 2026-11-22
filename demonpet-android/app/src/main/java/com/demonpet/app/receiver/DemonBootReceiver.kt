package com.demonpet.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.service.DemonCompanionService

class DemonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val cfg = DemonSettings(ctx).snapshot()
        if (cfg.enabled) {
            try {
                DemonCompanionService.start(ctx)
            } catch (_: Throwable) {
            }
        }
    }
}
