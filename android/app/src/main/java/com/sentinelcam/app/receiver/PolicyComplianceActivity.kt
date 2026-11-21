package com.sentinelcam.app.receiver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import com.sentinelcam.app.MainActivity

/**
 * Official Android 12+ (API 31+) ADMIN_POLICY_COMPLIANCE Handler for Android Enterprise QR Provisioning.
 * Applies transparent Managed Device configuration & links the Parent pairing credentials from the QR bundle.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            val extras = intent?.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
            MdmProvisioningHelper.applyManagedDeviceProvisioning(this, extras)
        } catch (_: Throwable) {
        }
        setResult(RESULT_OK, Intent())
        try {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launch)
        } catch (_: Throwable) {
        }
        finish()
    }
}
