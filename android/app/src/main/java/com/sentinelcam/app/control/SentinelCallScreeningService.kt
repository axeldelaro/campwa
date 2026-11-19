package com.sentinelcam.app.control

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.sentinelcam.app.data.CallLogHelper
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Native Android CallScreeningService (API 24+):
 * Silently rejects Parent-blocked phone numbers, hidden/private callers, or non-whitelisted callers
 * without interfering with any normal notifications or allowed contacts.
 * Automatically becomes 100% passive when the device is unpaired (`!prefs.parentManaged`).
 */
@RequiresApi(Build.VERSION_CODES.N)
class SentinelCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = try {
            PortalPreferences(applicationContext)
        } catch (_: Throwable) {
            allowCall(callDetails)
            return
        }

        // Never block anything if Parent has unpaired the device
        if (!prefs.parentManaged) {
            allowCall(callDetails)
            return
        }

        // Only screen incoming calls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            allowCall(callDetails)
            return
        }

        val rawNumber = try {
            callDetails.handle?.schemeSpecificPart?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }

        // 1. Unknown / Private / Hidden caller check
        if (rawNumber.isEmpty() || rawNumber.equals("anonymous", ignoreCase = true)) {
            if (prefs.blockUnknownCalls || prefs.callWhitelistOnly) {
                prefs.appendSecurityEvent(
                    kind = "call",
                    title = "Appel masqué bloqué",
                    detail = "Un appel entrant sans numéro visible a été rejeté automatiquement."
                )
                rejectCall(callDetails)
                return
            }
            allowCall(callDetails)
            return
        }

        val key = CallLogHelper.normalizePhoneKey(rawNumber)

        // 2. Explicit Parent Blacklist check
        val blocked = prefs.getBlockedNumbers()
        val matchedBlock = blocked.firstOrNull { b ->
            val bk = CallLogHelper.normalizePhoneKey(b.number)
            (bk.isNotEmpty() && bk == key) || b.number.equals(rawNumber, ignoreCase = true)
        }
        if (matchedBlock != null) {
            val label = matchedBlock.label.ifBlank { rawNumber }
            prefs.appendSecurityEvent(
                kind = "call",
                title = "Appel bloqué ($label)",
                detail = "Appel entrant du numéro $rawNumber rejeté selon la liste du Parent."
            )
            rejectCall(callDetails)
            return
        }

        // 3. Optional Parent Whitelist-Only Mode (only allowed numbers + saved contacts may ring)
        if (prefs.callWhitelistOnly) {
            val allowed = prefs.getAllowedCallNumbers()
            val inWhitelist = allowed.any { a ->
                val ak = CallLogHelper.normalizePhoneKey(a.number)
                (ak.isNotEmpty() && ak == key) || a.number.equals(rawNumber, ignoreCase = true)
            }
            if (!inWhitelist && !isInContacts(rawNumber)) {
                prefs.appendSecurityEvent(
                    kind = "call",
                    title = "Appel hors liste blanche bloqué",
                    detail = "Le numéro inconnu $rawNumber a été bloqué (mode contacts autorisés uniquement)."
                )
                rejectCall(callDetails)
                return
            }
        }

        allowCall(callDetails)
    }

    private fun isInContacts(number: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun allowCall(callDetails: Call.Details) {
        try {
            val resp = CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            respondToCall(callDetails, resp)
        } catch (_: Throwable) {
        }
    }

    private fun rejectCall(callDetails: Call.Details) {
        try {
            val resp = CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
                .build()
            respondToCall(callDetails, resp)
        } catch (_: Throwable) {
        }
    }
}
