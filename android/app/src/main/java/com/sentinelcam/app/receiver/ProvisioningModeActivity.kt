package com.sentinelcam.app.receiver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Official Android 10–15+ GET_PROVISIONING_MODE Handler for Android Enterprise QR Provisioning.
 * Responds immediately with PROVISIONING_MODE_FULLY_MANAGED_DEVICE and forwards admin extras.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val allowed = intent?.getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
            )
            val chosenMode = if (allowed != null && allowed.isNotEmpty()) {
                when {
                    allowed.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE) ->
                        DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                    else -> allowed[0]
                }
            } else {
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            }
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, chosenMode)
        }
        @Suppress("DEPRECATION")
        val extras = intent?.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        if (extras != null) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, extras)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
