package com.sentinelcam.app.receiver

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.service.PortalWatchdogJobService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Official, Transparent Android Enterprise Fully Managed (Device Owner) Provisioning Helper.
 *
 * - Activates ONLY after an explicit factory-reset QR Code provisioning through Android's
 *   official Setup Wizard (which displays Google's full-screen "Managed Device" disclosure
 *   and maintains a permanent visible managed-device notice on Lock Screen & Settings).
 * - Never hides or disables the launcher icon.
 * - Pre-links the device with the exact same Parent Family Key / Device Code / Admin PIN
 *   used by standard code pairing, and cleanly releases Device Owner on Parent unpair.
 */
object MdmProvisioningHelper {

    const val EXTRA_PAIR_CODE = "pairCode"
    const val EXTRA_FAMILY_KEY = "familyKey"
    const val EXTRA_DEVICE_CODE = "deviceCode"
    const val EXTRA_ADMIN_PIN = "adminPin"
    const val EXTRA_CHILD_NAME = "childName"

    fun applyManagedDeviceProvisioning(context: Context, extras: PersistableBundle?) {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        val isDeviceOwner = try {
            dpm?.isDeviceOwnerApp(appCtx.packageName) == true
        } catch (_: Throwable) {
            false
        }

        // 1. Official Android Enterprise Device Owner transparent configuration
        if (dpm != null && isDeviceOwner) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.setOrganizationName(admin, "Contrôle Parental SentinelCam")
                }
            } catch (_: Throwable) {
            }

            try {
                dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
            } catch (_: Throwable) {
            }

            val runtimePerms = buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.SEND_SMS)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }

            for (perm in runtimePerms) {
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        appCtx.packageName,
                        perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (_: Throwable) {
                }
            }

            try {
                dpm.setUninstallBlocked(admin, appCtx.packageName, true)
            } catch (_: Throwable) {
            }
        }

        // 2. Bind the exact same Parent-Managed credentials as standard pairing
        val prefs = PortalPreferences(appCtx)
        val rawFamily = extras?.getString(EXTRA_FAMILY_KEY).orEmpty()
        val rawDeviceCode = extras?.getString(EXTRA_DEVICE_CODE).orEmpty()
        val rawAdminPin = extras?.getString(EXTRA_ADMIN_PIN).orEmpty().trim()
        val rawPairCode = extras?.getString(EXTRA_PAIR_CODE).orEmpty()
        val rawChildName = extras?.getString(EXTRA_CHILD_NAME).orEmpty().trim()

        val famKey = CryptoProtocol.normalizeFamilyKey(rawFamily)
        val deviceCode = CryptoProtocol.normalizePairingCode(rawDeviceCode)
        val pairCode = CryptoProtocol.normalizePairingCode(rawPairCode)

        if (famKey.length == 12 && deviceCode.length == 6) {
            prefs.parentFamilyKey = famKey
            prefs.setPairingCodeDirect(deviceCode)
            prefs.parentManaged = true
            prefs.unpairRequestPending = false
            prefs.unpairRequestedAt = 0L
            prefs.onboardingCompleted = true
            prefs.userManuallyStopped = false
            prefs.adminProtectionEnabled = true
            prefs.blockSettingsAndStore = true
            prefs.globalAllowDeviceControl = true
            prefs.globalAllowLocation = true
            prefs.globalAllowLocationHistory = true
            prefs.globalAllowGallery = true
            prefs.globalAllowContacts = true
            prefs.globalAllowSmsRelay = true
            prefs.globalAllowDeviceInfo = true
            prefs.globalAllowIntercom = true
            if (rawAdminPin.length in 4..12) {
                prefs.setAdminPin(rawAdminPin)
            }
            val labelInfo = if (rawChildName.isNotEmpty()) " ($rawChildName)" else ""
            prefs.appendSecurityEvent(
                "admin",
                "Enrôlement Appareil Géré (MDM) terminé$labelInfo",
                if (isDeviceOwner) {
                    "Appareil enrôlé officiellement en mode Appareil entièrement géré (Device Owner) et associé au compte Parent."
                } else {
                    "Appareil pré-configuré par QR code et associé au compte Parent."
                }
            )
            PortalWatchdogJobService.scheduleWatchdog(appCtx)
            KeepAliveReceiver.scheduleNextPulse(appCtx)
            CameraForegroundService.startFromUi(appCtx)
        }

        // 3. Also notify the Parent's open QR screen live if it is still listening on pairCode
        if (pairCode.length == 6) {
            try {
                CameraForegroundService.pairWithParentCode(appCtx, pairCode)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Cleanly releases Device Owner & uninstall lock when the Parent unpairs the device,
     * restoring full user control and uninstallability.
     */
    @Suppress("DEPRECATION")
    fun releaseDeviceOwnerOnUnpair(context: Context) {
        val appCtx = context.applicationContext
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = SentinelDeviceAdminReceiver.getComponentName(appCtx)
        try {
            if (dpm.isDeviceOwnerApp(appCtx.packageName)) {
                try {
                    dpm.setUninstallBlocked(admin, appCtx.packageName, false)
                } catch (_: Throwable) {
                }
                try {
                    dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
                } catch (_: Throwable) {
                }
                try {
                    dpm.clearDeviceOwnerApp(appCtx.packageName)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
