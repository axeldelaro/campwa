package com.sentinelcam.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinelcam.app.location.LocationHistoryStore

data class SmsRelayResult(
    val success: Boolean,
    val phoneNumber: String,
    val sentText: String? = null,
    val error: String? = null
)

/**
 * Opt-In SMS Relay via native Android SmsManager (SIM plan, no third-party API).
 * Active only when authorized by the device owner (`globalAllowSmsRelay` + `SEND_SMS` permission)
 * and permitted for the connected access code.
 */
object SmsRelayHelper {
    private const val MAX_MESSAGE_CHARS = 640
    private const val SMS_NOTIF_CHANNEL_ID = "sentinelcam_sms_relay_alerts"
    private const val SMS_NOTIF_ID = 4409

    fun sanitizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder()
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (ch == '+' && sb.isEmpty()) {
                sb.append(ch)
            } else if (ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun sendSms(
        context: Context,
        rawPhoneNumber: String,
        contactName: String?,
        rawMessage: String,
        includeGpsLink: Boolean,
        locationStore: LocationHistoryStore?
    ): SmsRelayResult {
        val appCtx = context.applicationContext
        val granted = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val cleanPhone = sanitizePhoneNumber(rawPhoneNumber)
        if (!granted) {
            return SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Permission SMS non accordée sur l’appareil."
            )
        }

        if (cleanPhone.length < 3 || cleanPhone.length > 18) {
            return SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Numéro de téléphone invalide."
            )
        }

        var body = rawMessage.trim()
        if (body.isEmpty()) {
            return SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Le message ne peut pas être vide."
            )
        }

        if (includeGpsLink) {
            val latest = locationStore?.getLatestPoint()
            if (latest != null) {
                val latStr = String.format(java.util.Locale.US, "%.5f", latest.latitude)
                val lngStr = String.format(java.util.Locale.US, "%.5f", latest.longitude)
                val gpsSuffix = "\nhttps://maps.google.com/?q=$latStr,$lngStr"
                if (body.length + gpsSuffix.length <= MAX_MESSAGE_CHARS) {
                    body += gpsSuffix
                }
            }
        }

        if (body.length > MAX_MESSAGE_CHARS) {
            body = body.substring(0, MAX_MESSAGE_CHARS)
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appCtx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanPhone, null, body, null, null)
            }

            postSmsNotification(appCtx, contactName?.takeIf { it.isNotBlank() } ?: cleanPhone)

            SmsRelayResult(
                success = true,
                phoneNumber = cleanPhone,
                sentText = body
            )
        } catch (t: Throwable) {
            SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Envoi SMS impossible : ${t.message}"
            )
        }
    }

    private fun postSmsNotification(context: Context, recipientLabel: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    SMS_NOTIF_CHANNEL_ID,
                    "Notifications SMS SentinelCam",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Informe lorsqu’un SMS est envoyé via SentinelCam"
                }
                nm.createNotificationChannel(ch)
            }

            val notif = NotificationCompat.Builder(context, SMS_NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("SMS envoyé")
                .setContentText("Destinataire : $recipientLabel")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(SMS_NOTIF_ID, notif)
        } catch (_: Throwable) {
        }
    }
}
