package com.sentinelcam.app.receiver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Official Android 12+ (API 31+) GET_PROVISIONING_MODE Handler for Android Enterprise QR Provisioning.
 * Instructs Android's Setup Wizard to enroll the factory-reset phone as a Fully Managed Device.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            )
        }
        @Suppress("DEPRECATION")
        val extras = intent?.getParcelableExtra<android.os.PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        if (extras != null) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, extras)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
