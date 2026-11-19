package com.sentinelcam.app.control

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sentinelcam.app.data.SocialMessageStore
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences
import java.text.Normalizer
import java.util.Locale

/**
 * Strictly Passive Social & Messaging Notification Archiver (v24):
 * - NEVER cancels, hides, mutes, or blocks any notification on the child phone!
 * - Archives incoming WhatsApp, Instagram, Snapchat, Discord, Messenger, TikTok, Telegram, Signal
 *   messages locally (even if deleted or disappearing) and caches native Android RemoteInput
 *   actions so the Parent can reply directly from the Parent Website.
 * - Matches Parent-defined sensitive keywords locally and triggers instant security alerts.
 * - Automatically stays 100% inert if the device is unpaired (`!prefs.parentManaged`).
 */
class SentinelNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return

        val prefs = try {
            PortalPreferences(applicationContext)
        } catch (_: Throwable) {
            return
        }
        if (!prefs.parentManaged) return

        val notif = sbn.notification ?: return
        // Ignore ongoing background service notifications & group summary placeholders
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val knownApp = KNOWN_SOCIAL_APPS[pkg]
        val replyPair = extractReplyAction(notif)
        // Capture if it's a known social/messaging app OR any messaging notification exposing a direct-reply action
        if (knownApp == null && replyPair == null) return

        val extras = notif.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return

        val sender = rawTitle.trim()
        val body = rawText.trim()
        if (sender.isEmpty() || body.isEmpty()) return

        // Skip generic system placeholder strings ("3 nouveaux messages", "En cours de vérification...")
        val lowerBody = body.lowercase(Locale.FRENCH)
        if (lowerBody.startsWith("recherche de nouveaux messages") ||
            lowerBody.startsWith("checking for new messages") ||
            lowerBody.matches(Regex("^\\d+\\s+(nouveaux?\\s+)?messages?.*"))
        ) {
            return
        }

        val appLabel = knownApp ?: ScreenTimeHelper.resolveAppName(packageManager, pkg)
        val ts = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
        val defaultSmsPkg = try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(applicationContext)
        } catch (_: Throwable) {
            null
        }
        val isDefaultSms = !defaultSmsPkg.isNullOrBlank() && pkg == defaultSmsPkg

        if (isDefaultSms) {
            // SMS messages are already visible in the SMS tab; still run sensitive keyword alerts!
            checkSensitiveKeywords(prefs, "SMS", sender, body)
            return
        }

        val saved = SocialMessageStore.appendMessage(
            context = applicationContext,
            packageName = pkg,
            appLabel = appLabel,
            sender = sender,
            body = body,
            timestamp = ts,
            direction = "in"
        )

        val threadKey = SocialMessageStore.makeThreadKey(pkg, sender)
        if (replyPair != null) {
            SocialMessageStore.registerReplyAction(threadKey, replyPair.first, replyPair.second)
        }

        if (saved != null) {
            checkSensitiveKeywords(prefs, appLabel, sender, body)
        }
    }

    private fun extractReplyAction(notif: Notification): Pair<Notification.Action, android.app.RemoteInput>? {
        val actions = notif.actions ?: return null
        for (a in actions) {
            val inputs = a.remoteInputs ?: continue
            val ri = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.firstOrNull()
            if (ri != null && a.actionIntent != null) {
                return Pair(a, ri)
            }
        }
        return null
    }

    private fun checkSensitiveKeywords(
        prefs: PortalPreferences,
        appLabel: String,
        sender: String,
        body: String
    ) {
        val keywords = prefs.getSocialAlertKeywords()
        if (keywords.isEmpty()) return
        val normBody = normalizeForMatch(body)
        val matched = keywords.firstOrNull { kw ->
            val clean = normalizeForMatch(kw)
            clean.length >= 2 && normBody.contains(clean)
        } ?: return

        prefs.appendSecurityEvent(
            kind = "keyword",
            title = "Mot-clé détecté ($appLabel)",
            detail = "« $matched » dans un message de $sender : « ${body.take(90)} »"
        )
        CameraForegroundService.notifySecurityEventExternal()
    }

    private fun normalizeForMatch(raw: String): String {
        val nfd = Normalizer.normalize(raw.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    companion object {
        private val KNOWN_SOCIAL_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.snapchat.android" to "Snapchat",
            "com.discord" to "Discord",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "org.telegram.messenger" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
            "org.thoughtcrime.securesms" to "Signal",
            "com.viber.voip" to "Viber",
            "com.twitter.android" to "X / Twitter"
        )

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ).orEmpty()
                val expected = ComponentName(context, SentinelNotificationService::class.java).flattenToString()
                flat.contains(expected, ignoreCase = true) || flat.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
