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
import java.util.ArrayDeque

data class SmsRelayResult(
    val success: Boolean,
    val phoneNumber: String,
    val sentText: String? = null,
    val error: String? = null,
    val remainingQuota: Int = 0
)

/**
 * Strictly Opt-In Lost-Phone / Emergency SMS Relay via native Android SmsManager (SIM plan, 0€ API).
 *
 * Security & Anti-Abuse Protections:
 * 1. Requires BOTH explicit user toggle (`globalAllowSmsRelay`) AND OS `SEND_SMS` runtime permission.
 * 2. Strict sliding-window rate limiter (max 5 SMS / 10 minutes).
 * 3. Strict phone number & message length validation (max 320 chars).
 * 4. Full transparency: every sent SMS triggers a visible Android notification and an Audit Log entry.
 */
object SmsRelayHelper {
    private const val MAX_SMS_PER_WINDOW = 5
    private const val WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    private const val MAX_MESSAGE_CHARS = 320
    private const val SMS_NOTIF_CHANNEL_ID = "sentinelcam_sms_relay_alerts"
    private const val SMS_NOTIF_ID = 4409

    private val sentTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun getRemainingQuota(nowMs: Long = System.currentTimeMillis()): Int {
        pruneWindowLocked(nowMs)
        return (MAX_SMS_PER_WINDOW - sentTimestamps.size).coerceAtLeast(0)
    }

    @Synchronized
    private fun pruneWindowLocked(nowMs: Long) {
        while (sentTimestamps.isNotEmpty() && (nowMs - sentTimestamps.first()) > WINDOW_MS) {
            sentTimestamps.removeFirst()
        }
    }

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

    fun sendEmergencySms(
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
                error = "Permission SMS (SEND_SMS) non accordée sur le smartphone.",
                remainingQuota = getRemainingQuota()
            )
        }

        if (cleanPhone.length < 4 || cleanPhone.length > 18) {
            return SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Numéro de téléphone invalide.",
                remainingQuota = getRemainingQuota()
            )
        }

        val now = System.currentTimeMillis()
        synchronized(this) {
            pruneWindowLocked(now)
            if (sentTimestamps.size >= MAX_SMS_PER_WINDOW) {
                return SmsRelayResult(
                    success = false,
                    phoneNumber = cleanPhone,
                    error = "Limite anti-abus atteinte (max $MAX_SMS_PER_WINDOW SMS / 10 min). Réessayez dans quelques minutes.",
                    remainingQuota = 0
                )
            }
        }

        var body = rawMessage.trim()
        if (body.isEmpty()) {
            return SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Le message SMS ne peut pas être vide.",
                remainingQuota = getRemainingQuota()
            )
        }

        if (includeGpsLink) {
            val latest = locationStore?.getLatestPoint()
            if (latest != null) {
                val latStr = String.format(java.util.Locale.US, "%.5f", latest.latitude)
                val lngStr = String.format(java.util.Locale.US, "%.5f", latest.longitude)
                val gpsSuffix = "\nGPS: https://maps.google.com/?q=$latStr,$lngStr"
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

            val remaining = synchronized(this) {
                sentTimestamps.addLast(System.currentTimeMillis())
                (MAX_SMS_PER_WINDOW - sentTimestamps.size).coerceAtLeast(0)
            }

            postTransparentSmsNotification(appCtx, contactName?.takeIf { it.isNotBlank() } ?: cleanPhone)

            SmsRelayResult(
                success = true,
                phoneNumber = cleanPhone,
                sentText = body,
                remainingQuota = remaining
            )
        } catch (t: Throwable) {
            SmsRelayResult(
                success = false,
                phoneNumber = cleanPhone,
                error = "Échec d’envoi SMS (vérifiez la carte SIM / le réseau) : ${t.message}",
                remainingQuota = getRemainingQuota()
            )
        }
    }

    private fun postTransparentSmsNotification(context: Context, recipientLabel: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    SMS_NOTIF_CHANNEL_ID,
                    "Alertes Relais SMS SentinelCam",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifie immédiatement lorsqu’un SMS est envoyé via le relais d’urgence"
                }
                nm.createNotificationChannel(ch)
            }

            val notif = NotificationCompat.Builder(context, SMS_NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("SentinelCam : SMS envoyé via relais")
                .setContentText("Destinataire : $recipientLabel")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(SMS_NOTIF_ID, notif)
        } catch (_: Throwable) {
        }
    }
}
