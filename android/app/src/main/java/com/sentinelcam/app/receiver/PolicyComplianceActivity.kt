package com.sentinelcam.app.receiver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Official Android 10–15+ ADMIN_POLICY_COMPLIANCE Handler for Android Enterprise QR Provisioning.
 * Applies transparent Managed Device policies and immediately returns RESULT_OK to Setup Wizard
 * (NEVER launches another Activity on top of Setup Wizard, which prevents MIUI/Android OOBE aborts!).
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            val persistable = intent?.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
            val bundle = intent?.getBundleExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
            )
            MdmProvisioningHelper.applyManagedDeviceProvisioning(
                context = this,
                persistableExtras = persistable,
                bundleExtras = bundle
            )
        } catch (_: Throwable) {
        }
        setResult(RESULT_OK, Intent())
        finish()
    }
}
