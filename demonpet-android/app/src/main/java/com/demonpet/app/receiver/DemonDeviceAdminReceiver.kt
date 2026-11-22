package com.demonpet.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Standard Android Device Admin Receiver for DemonMom AI Challenge Lock.
 * Automatically released when the user taps the Demon 20 times and enters exit code 100205.
 */
class DemonDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Tapote le démon 20 fois et saisis le code 100205 dans DemonMom AI pour tout désactiver proprement."
    }

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, DemonDeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            return try {
                val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(getComponentName(context))
            } catch (_: Throwable) {
                false
            }
        }

        fun removeAdminSafely(context: Context) {
            try {
                val dpm = context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val comp = getComponentName(context)
                if (dpm.isAdminActive(comp)) {
                    dpm.removeActiveAdmin(comp)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
