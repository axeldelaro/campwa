package com.sentinelcam.app.control

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.sentinelcam.app.data.SocialMessageStore
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Strictly Passive, High-Detail Social & Messaging Notification Archiver + Live Direct-Reply Bridge (v25):
 * - NEVER cancels, hides, mutes, or blocks any notification on the child phone!
 * - Extracts FULL notification details:
 *   * Android MessagingStyle history (`EXTRA_MESSAGES` array with real group vs individual author, timestamp, MIME media type)
 *   * InboxStyle multi-line messages (`EXTRA_TEXT_LINES`)
 *   * BigTextStyle full text, SubText/account label, InfoText
 *   * Attached preview images (`EXTRA_PICTURE`) compressed to tiny ~4 KB JPEG thumbnails
 *   * Direct-reply `RemoteInput` actions from BOTH `notif.actions` AND `WearableExtender(notif).actions`
 * - Exposes live `activeNotifications` lookup so Direct Reply ALWAYS uses a fresh valid `PendingIntent`!
 * - Automatically stays 100% inert if unpaired (`!prefs.parentManaged`).
 */
class SentinelNotificationService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) activeInstance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

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
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val knownApp = KNOWN_SOCIAL_APPS[pkg]
        val replyPair = extractReplyActionFromNotification(notif)
        // Skip pure empty summary placeholders UNLESS they carry a reply action or useful text lines
        val isSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isSummary && replyPair == null) return
        if (knownApp == null && replyPair == null) return

        val extras = notif.extras ?: return
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val title = convTitle.ifEmpty { rawTitle }
        if (title.isEmpty()) return

        val appLabel = knownApp ?: ScreenTimeHelper.resolveAppName(packageManager, pkg)
        val defaultSmsPkg = try {
            Telephony.Sms.getDefaultSmsPackage(applicationContext)
        } catch (_: Throwable) {
            null
        }
        val isDefaultSms = !defaultSmsPkg.isNullOrBlank() && pkg == defaultSmsPkg

        val previewThumb = extractPictureThumbnailBase64(extras)
        val extractedEntries = extractRichMessages(extras, convTitle, rawTitle, sbn.postTime)
        if (extractedEntries.isEmpty()) return

        val threadKey = SocialMessageStore.makeThreadKey(pkg, title)
        if (replyPair != null && !isDefaultSms) {
            SocialMessageStore.registerReplyAction(
                threadKey = threadKey,
                sbnKey = sbn.key.orEmpty(),
                packageName = pkg,
                sender = title,
                action = replyPair.first,
                remoteInput = replyPair.second
            )
        }

        for ((index, entry) in extractedEntries.withIndex()) {
            val cleanBody = entry.body.trim()
            if (cleanBody.isEmpty() || isGenericPlaceholder(cleanBody)) continue

            if (isDefaultSms) {
                checkSensitiveKeywords(prefs, "SMS", entry.author.ifEmpty { title }, cleanBody)
                continue
            }

            val attachThumb = if (index == extractedEntries.lastIndex) previewThumb else ""
            val saved = SocialMessageStore.appendMessage(
                context = applicationContext,
                packageName = pkg,
                appLabel = appLabel,
                sender = title,
                body = cleanBody,
                timestamp = entry.timestamp,
                direction = "in",
                author = entry.author,
                subText = subText,
                mediaType = entry.mediaType,
                thumbBase64 = attachThumb
            )
            if (saved != null) {
                checkSensitiveKeywords(prefs, appLabel, entry.author.ifEmpty { title }, cleanBody)
            }
        }
    }

    private data class ParsedNotifEntry(
        val author: String,
        val body: String,
        val timestamp: Long,
        val mediaType: String
    )

    private fun extractRichMessages(
        extras: Bundle,
        convTitle: String,
        rawTitle: String,
        postTime: Long
    ): List<ParsedNotifEntry> {
        val fallbackTs = if (postTime > 0L) postTime else System.currentTimeMillis()
        val out = ArrayList<ParsedNotifEntry>()

        // 1. Android MessagingStyle (WhatsApp, Instagram, Messenger, Telegram, Signal, Discord)
        @Suppress("DEPRECATION")
        val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (parcelables != null && parcelables.isNotEmpty()) {
            // Take up to the last 6 messages in the MessagingStyle bundle so nothing is missed!
            val startIdx = (parcelables.size - 6).coerceAtLeast(0)
            for (i in startIdx until parcelables.size) {
                val b = parcelables[i] as? Bundle ?: continue
                val msgText = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                val msgTime = b.getLong("time", fallbackTs).takeIf { it > 0L } ?: fallbackTs
                val mime = b.getString("type")?.lowercase(Locale.US).orEmpty()
                val mediaType = classifyMediaType(mime, msgText)

                var authorName = ""
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    val person = b.getParcelable<Person>("sender_person")
                    authorName = person?.name?.toString()?.trim().orEmpty()
                }
                if (authorName.isEmpty()) {
                    authorName = b.getCharSequence("sender")?.toString()?.trim().orEmpty()
                }
                // Avoid duplicating author when it's identical to 1:1 conversation title
                val effectiveAuthor = if (convTitle.isNotEmpty() && !authorName.equals(convTitle, ignoreCase = true)) {
                    authorName
                } else if (authorName.isNotEmpty() && !authorName.equals(rawTitle, ignoreCase = true)) {
                    authorName
                } else {
                    ""
                }

                val finalBody = msgText.ifEmpty { defaultBodyForMedia(mediaType) }
                if (finalBody.isNotEmpty()) {
                    out.add(ParsedNotifEntry(effectiveAuthor, finalBody, msgTime, mediaType))
                }
            }
            if (out.isNotEmpty()) return out
        }

        // 2. InboxStyle multi-line text (e.g. grouped notifications / Snapchat / TikTok)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            val joined = textLines.takeLast(5)
                .mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .joinToString("\n")
            if (joined.isNotEmpty()) {
                val mediaType = classifyMediaType("", joined)
                out.add(ParsedNotifEntry("", joined, fallbackTs, mediaType))
                return out
            }
        }

        // 3. Standard BigText / Text fallback
        val rawBody = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (rawBody.isNotEmpty()) {
            // WhatsApp group fallback when EXTRA_MESSAGES wasn't set: title="Group: Author" or rawTitle != convTitle
            var author = ""
            if (convTitle.isNotEmpty() && rawTitle.isNotEmpty() && !rawTitle.equals(convTitle, ignoreCase = true)) {
                author = rawTitle.removePrefix("$convTitle:").removePrefix("$convTitle :").trim()
            }
            val mediaType = classifyMediaType("", rawBody)
            out.add(ParsedNotifEntry(author, rawBody, fallbackTs, mediaType))
        }
        return out
    }

    private fun classifyMediaType(mime: String, text: String): String {
        val lowMime = mime.lowercase(Locale.US)
        if (lowMime.startsWith("image/")) return "photo"
        if (lowMime.startsWith("audio/")) return "audio"
        if (lowMime.startsWith("video/")) return "video"
        if (lowMime.isNotEmpty()) return "file"

        val low = text.lowercase(Locale.FRENCH)
        return when {
            low.contains("📷") || low.startsWith("photo") || low.contains("a envoyé une photo") || low.contains("sent a photo") || low.contains("snap") -> "photo"
            low.contains("🎤") || low.contains("message vocal") || low.contains("audio") || low.contains("voice message") -> "audio"
            low.contains("🎥") || low.contains("🎬") || low.startsWith("vidéo") || low.contains("sent a video") -> "video"
            low.contains("📄") || low.contains("📎") || low.contains("document") -> "file"
            else -> ""
        }
    }

    private fun defaultBodyForMedia(mediaType: String): String {
        return when (mediaType) {
            "photo" -> "📷 Photo reçue"
            "audio" -> "🎤 Message vocal"
            "video" -> "🎬 Vidéo reçue"
            "file" -> "📎 Pièce jointe"
            else -> ""
        }
    }

    private fun extractPictureThumbnailBase64(extras: Bundle): String {
        return try {
            @Suppress("DEPRECATION")
            val bmp = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) ?: return ""
            val maxDim = 220f
            val scale = minOf(maxDim / bmp.width.coerceAtLeast(1), maxDim / bmp.height.coerceAtLeast(1), 1f)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 58, baos)
            if (scaled !== bmp) scaled.recycle()
            val bytes = baos.toByteArray()
            if (bytes.size > 24_000) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isGenericPlaceholder(body: String): Boolean {
        val lowerBody = body.lowercase(Locale.FRENCH)
        return lowerBody.startsWith("recherche de nouveaux messages") ||
            lowerBody.startsWith("checking for new messages") ||
            lowerBody.startsWith("sauvegarde des discussions") ||
            lowerBody.matches(Regex("^\\d+\\s+(nouveaux?\\s+)?messages?(\\s+dans\\s+\\d+\\s+discussions?)?.*"))
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
        @Volatile
        private var activeInstance: SentinelNotificationService? = null

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

        fun extractReplyActionFromNotification(notif: Notification): Pair<Notification.Action, RemoteInput>? {
            val allActions = ArrayList<Notification.Action>()
            notif.actions?.let { allActions.addAll(it) }
            try {
                Notification.WearableExtender(notif).actions?.let { allActions.addAll(it) }
            } catch (_: Throwable) {
            }

            // 1st priority: explicit SEMANTIC_ACTION_REPLY with free-form RemoteInput
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (a in allActions) {
                    if (a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && a.actionIntent != null) {
                        val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                            ?: a.remoteInputs?.firstOrNull()
                        if (ri != null) return Pair(a, ri)
                    }
                }
            }

            // 2nd priority: any action with a free-form RemoteInput
            for (a in allActions) {
                if (a.actionIntent == null) continue
                val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                    ?: a.remoteInputs?.firstOrNull { !it.resultKey.isNullOrBlank() }
                if (ri != null) return Pair(a, ri)
            }
            return null
        }

        /**
         * Queries live active StatusBarNotifications on the child phone so Direct Reply ALWAYS gets
         * the freshest non-expired PendingIntent & RemoteInput (fixes WhatsApp/Instagram/Android 14+!).
         */
        fun findLiveReplyAction(
            sbnKey: String,
            packageName: String,
            senderHint: String
        ): Pair<Notification.Action, RemoteInput>? {
            val svc = activeInstance ?: return null
            val active = try {
                svc.activeNotifications
            } catch (_: Throwable) {
                null
            } ?: return null

            // Exact SBN key match first
            if (sbnKey.isNotEmpty()) {
                for (sbn in active) {
                    if (sbn.key == sbnKey) {
                        val hit = extractReplyActionFromNotification(sbn.notification ?: continue)
                        if (hit != null) return hit
                    }
                }
            }

            // Fallback: match same package + same conversation/sender title
            val normHint = senderHint.trim().lowercase(Locale.FRENCH)
            for (sbn in active) {
                if (!sbn.packageName.equals(packageName, ignoreCase = true)) continue
                val notif = sbn.notification ?: continue
                val extras = notif.extras ?: continue
                val conv = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val candidate = conv.ifEmpty { title }.lowercase(Locale.FRENCH)
                if (normHint.isNotEmpty() && (candidate == normHint || candidate.contains(normHint) || normHint.contains(candidate))) {
                    val hit = extractReplyActionFromNotification(notif)
                    if (hit != null) return hit
                }
            }
            return null
        }

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
