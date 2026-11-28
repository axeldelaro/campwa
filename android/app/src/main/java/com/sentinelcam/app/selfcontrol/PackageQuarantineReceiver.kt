package com.sentinelcam.app.selfcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * SentinelCam v28.0 — Automatic New-App Quarantine Receiver:
 * While Pact Geo-Vault is sealed (and `quarantineNewApps` is ON), any newly installed app
 * (e.g. a fresh browser, game, or VPN installed impulsively) is automatically quarantined
 * until the user unlocks inside their Geo-Unlock zone!
 */
class PackageQuarantineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == context.packageName) return

        try {
            val sc = SelfControlStore(context)
            if (sc.selfControlEnabled && sc.pactSealed && sc.quarantineNewApps && !sc.canWeakenRulesNow()) {
                sc.addQuarantinedPackage(pkg)
                sc.notifyPartnerIfConfigured(
                    "Nouvelle application mise en quarantaine",
                    "Application installée sous Pacte scellé et bloquée automatiquement : $pkg"
                )
            }
            val prefs = PortalPreferences(context)
            if (prefs.parentManaged) {
                prefs.appendSecurityEvent(
                    kind = "admin",
                    title = "Nouvelle application installée",
                    detail = "Package détecté : $pkg"
                )
                CameraForegroundService.notifySecurityEventExternal()
            }
        } catch (_: Throwable) {
        }
    }
}
