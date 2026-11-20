package com.sentinelcam.app.data

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.sentinelcam.app.control.SentinelNotificationService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent On-Device Social & Messaging Archive + Rock-Solid Direct-Reply Engine (v25):
 * - Captures rich notification metadata: conversation/group title, individual author inside groups,
 *   subText context, media attachment badges (Photo/Vocal/Vidéo), and optional image preview thumbnail.
 * - Survives even if the child deletes messages or uses disappearing chats.
 * - Uses LIVE active StatusBarNotifications FIRST (with cached Wearable/Action fallback) + Android 14/15
 *   PendingIntent flags & RemoteInput.SOURCE_FREE_FORM_INPUT so Direct Reply works reliably on
 *   WhatsApp, Instagram, Snapchat, Discord, Messenger, Telegram, Signal, etc.
 * - 100% wiped on unpair.
 */
object SocialMessageStore {

    data class SocialMsgRecord(
        val id: String,
        val threadKey: String,
        val packageName: String,
        val appLabel: String,
        val sender: String,       // Conversation / group title
        val author: String = "",  // Individual author inside a group (empty in 1:1 chats)
        val subText: String = "", // Account / channel / context sub-label
        val mediaType: String = "", // "photo" | "audio" | "video" | "file" | ""
        val thumbBase64: String = "",
        val body: String,
        val timestamp: Long,
        val direction: String     // "in" | "out"
    )

    private data class ReplyActionHandle(
        val sbnKey: String,
        val packageName: String,
        val sender: String,
        val action: Notification.Action,
        val remoteInput: RemoteInput,
        val updatedAt: Long
    )

    private val lock = Any()
    private val replyHandles = ConcurrentHashMap<String, ReplyActionHandle>()
    private val recentDedup = LinkedHashMap<String, Long>()

    private fun getStoreFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "social_archive")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "messages.json")
    }

    fun makeThreadKey(packageName: String, sender: String): String {
        val cleanSender = sender.trim().lowercase(Locale.FRENCH).take(80)
        return "${packageName.lowercase(Locale.US)}::$cleanSender"
    }

    fun registerReplyAction(
        threadKey: String,
        sbnKey: String,
        packageName: String,
        sender: String,
        action: Notification.Action,
        remoteInput: RemoteInput
    ) {
        replyHandles[threadKey] = ReplyActionHandle(
            sbnKey = sbnKey,
            packageName = packageName,
            sender = sender,
            action = action,
            remoteInput = remoteInput,
            updatedAt = System.currentTimeMillis()
        )
        if (replyHandles.size > 160) {
            val oldest = replyHandles.entries.minByOrNull { it.value.updatedAt }?.key
            if (oldest != null) replyHandles.remove(oldest)
        }
    }

    fun canReplyToThread(threadKey: String): Boolean {
        val cached = replyHandles[threadKey]
        if (cached != null) {
            val live = SentinelNotificationService.findLiveReplyAction(
                cached.sbnKey,
                cached.packageName,
                cached.sender
            )
            if (live != null) return true
            // Cached PendingIntent usually remains valid while notification wasn't dismissed (< 6h)
            if ((System.currentTimeMillis() - cached.updatedAt) < 6 * 3600_000L) return true
        }
        val pkg = threadKey.substringBefore("::")
        val sender = threadKey.substringAfter("::", "")
        return SentinelNotificationService.findLiveReplyAction("", pkg, sender) != null
    }

    fun sendDirectReply(context: Context, threadKey: String, replyText: String): Pair<Boolean, String?> {
        val clean = replyText.trim().take(600)
        if (clean.isEmpty()) return Pair(false, "Message vide.")

        val cached = replyHandles[threadKey]
        val pkg = cached?.packageName ?: threadKey.substringBefore("::")
        val rawSender = cached?.sender ?: threadKey.substringAfter("::", "")

        // 1. Prefer a FRESH live notification action directly from NotificationListenerService.activeNotifications!
        val livePair = SentinelNotificationService.findLiveReplyAction(
            sbnKey = cached?.sbnKey.orEmpty(),
            packageName = pkg,
            senderHint = rawSender
        )
        val actionToUse = livePair?.first ?: cached?.action
        val remoteInputToUse = livePair?.second ?: cached?.remoteInput

        if (actionToUse == null || remoteInputToUse == null || actionToUse.actionIntent == null) {
            replyHandles.remove(threadKey)
            return Pair(
                false,
                "Notification expirée ou déjà effacée sur le téléphone. La réponse directe se réactivera au prochain message entrant."
            )
        }

        return try {
            val fillIntent = Intent().apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val resultsBundle = Bundle().apply {
                putCharSequence(remoteInputToUse.resultKey, clean)
            }
            // Preserve any existing extras on the reply action
            try {
                if (!actionToUse.extras.isEmpty) {
                    fillIntent.putExtras(actionToUse.extras)
                }
            } catch (_: Throwable) {
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInputToUse), fillIntent, resultsBundle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    RemoteInput.setResultsSource(fillIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                } catch (_: Throwable) {
                }
            }

            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                        .toBundle()
                    actionToUse.actionIntent.send(appCtx, 0, fillIntent, null, null, null, opts)
                } catch (_: Throwable) {
                    actionToUse.actionIntent.send(appCtx, 0, fillIntent)
                }
            } else {
                actionToUse.actionIntent.send(appCtx, 0, fillIntent)
            }

            val existing = getAllMessages(context).firstOrNull { it.threadKey == threadKey }
            appendMessage(
                context = context,
                packageName = existing?.packageName ?: pkg,
                appLabel = existing?.appLabel ?: "Message",
                sender = existing?.sender ?: rawSender,
                author = "Parent",
                subText = "",
                mediaType = "",
                thumbBase64 = "",
                body = clean,
                timestamp = System.currentTimeMillis(),
                direction = "out"
            )
            Pair(true, null)
        } catch (t: Throwable) {
            if (livePair == null) replyHandles.remove(threadKey)
            val detail = t.message?.takeIf { it.isNotBlank() } ?: "notification fermée ou non prise en charge par cette appli"
            Pair(false, "Envoi refusé ($detail). Attendez un nouveau message entrant.")
        }
    }

    fun appendMessage(
        context: Context,
        packageName: String,
        appLabel: String,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        direction: String = "in",
        author: String = "",
        subText: String = "",
        mediaType: String = "",
        thumbBase64: String = ""
    ): SocialMsgRecord? {
        val cleanSender = sender.trim().take(90)
        val cleanAuthor = author.trim().take(60)
        val cleanSub = subText.trim().take(70)
        val cleanBody = body.trim().take(1200)
        if (cleanSender.isEmpty() || cleanBody.isEmpty()) return null

        val threadKey = makeThreadKey(packageName, cleanSender)
        val dedupKey = "$threadKey|$direction|$cleanAuthor|$cleanBody"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val prevTs = recentDedup[dedupKey]
            if (prevTs != null && (now - prevTs) < 60_000L) {
                return null
            }
            recentDedup[dedupKey] = now
            if (recentDedup.size > 500) {
                val first = recentDedup.keys.firstOrNull()
                if (first != null) recentDedup.remove(first)
            }

            val arr = readRawArrayLocked(context)
            // Also skip exact duplicate already among top 15 messages of the same thread (when MessagingStyle reposts history)
            val checkCount = minOf(arr.length(), 40)
            for (i in 0 until checkCount) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("threadKey") == threadKey &&
                    o.optString("direction") == direction &&
                    o.optString("author") == cleanAuthor &&
                    o.optString("body") == cleanBody &&
                    kotlin.math.abs(o.optLong("timestamp", 0L) - timestamp) < 120_000L
                ) {
                    return null
                }
            }

            val rec = SocialMsgRecord(
                id = "sm_${now}_${UUID.randomUUID().toString().take(5)}",
                threadKey = threadKey,
                packageName = packageName,
                appLabel = appLabel,
                sender = cleanSender,
                author = cleanAuthor,
                subText = cleanSub,
                mediaType = mediaType,
                thumbBase64 = thumbBase64,
                body = cleanBody,
                timestamp = timestamp,
                direction = direction
            )

            val obj = JSONObject().apply {
                put("id", rec.id)
                put("threadKey", rec.threadKey)
                put("packageName", rec.packageName)
                put("appLabel", rec.appLabel)
                put("sender", rec.sender)
                if (rec.author.isNotEmpty()) put("author", rec.author)
                if (rec.subText.isNotEmpty()) put("subText", rec.subText)
                if (rec.mediaType.isNotEmpty()) put("mediaType", rec.mediaType)
                if (rec.thumbBase64.isNotEmpty()) put("thumbBase64", rec.thumbBase64)
                put("body", rec.body)
                put("timestamp", rec.timestamp)
                put("direction", rec.direction)
            }

            val next = JSONArray()
            next.put(obj)
            val maxKeep = 1_400
            for (i in 0 until minOf(arr.length(), maxKeep - 1)) {
                next.put(arr.getJSONObject(i))
            }
            writeRawArrayLocked(context, next)
            return rec
        }
    }

    fun getAllMessages(context: Context): List<SocialMsgRecord> {
        synchronized(lock) {
            val arr = readRawArrayLocked(context)
            val out = ArrayList<SocialMsgRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SocialMsgRecord(
                        id = o.optString("id"),
                        threadKey = o.optString("threadKey"),
                        packageName = o.optString("packageName"),
                        appLabel = o.optString("appLabel"),
                        sender = o.optString("sender"),
                        author = o.optString("author", ""),
                        subText = o.optString("subText", ""),
                        mediaType = o.optString("mediaType", ""),
                        thumbBase64 = o.optString("thumbBase64", ""),
                        body = o.optString("body"),
                        timestamp = o.optLong("timestamp", 0L),
                        direction = o.optString("direction", "in")
                    )
                )
            }
            return out
        }
    }

    fun listThreadsJson(context: Context, appFilter: String = "", maxThreads: Int = 80): JSONObject {
        val all = getAllMessages(context)
        data class Acc(
            val threadKey: String,
            val packageName: String,
            val appLabel: String,
            val sender: String,
            val subText: String,
            val snippet: String,
            val lastAuthor: String,
            val lastMediaType: String,
            val lastDate: Long,
            val lastDirection: String,
            var count: Int
        )

        val appsSet = LinkedHashSet<String>()
        val map = LinkedHashMap<String, Acc>()
        for (m in all) {
            appsSet.add(m.appLabel)
            if (appFilter.isNotBlank() && !m.appLabel.equals(appFilter, ignoreCase = true)) continue
            val ex = map[m.threadKey]
            if (ex == null) {
                if (map.size >= maxThreads) continue
                map[m.threadKey] = Acc(
                    threadKey = m.threadKey,
                    packageName = m.packageName,
                    appLabel = m.appLabel,
                    sender = m.sender,
                    subText = m.subText,
                    snippet = m.body.replace('\n', ' ').take(150),
                    lastAuthor = m.author,
                    lastMediaType = m.mediaType,
                    lastDate = m.timestamp,
                    lastDirection = m.direction,
                    count = 1
                )
            } else {
                ex.count++
            }
        }

        val threadsArr = JSONArray()
        for (t in map.values.sortedByDescending { it.lastDate }) {
            threadsArr.put(
                JSONObject().apply {
                    put("threadKey", t.threadKey)
                    put("packageName", t.packageName)
                    put("appLabel", t.appLabel)
                    put("sender", t.sender)
                    if (t.subText.isNotEmpty()) put("subText", t.subText)
                    if (t.lastAuthor.isNotEmpty()) put("lastAuthor", t.lastAuthor)
                    if (t.lastMediaType.isNotEmpty()) put("lastMediaType", t.lastMediaType)
                    put("snippet", t.snippet)
                    put("lastDate", t.lastDate)
                    put("lastDirection", t.lastDirection)
                    put("messageCount", t.count)
                    put("canReply", canReplyToThread(t.threadKey))
                }
            )
        }

        val appsArr = JSONArray()
        for (a in appsSet) appsArr.put(a)

        return JSONObject().apply {
            put("threads", threadsArr)
            put("apps", appsArr)
            put("totalMessages", all.size)
        }
    }

    fun getThreadMessagesJson(context: Context, threadKey: String, limit: Int = 150): JSONObject {
        val msgs = getAllMessages(context)
            .filter { it.threadKey == threadKey }
            .take(limit)
            .sortedBy { it.timestamp }

        val arr = JSONArray()
        for (m in msgs) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("sender", m.sender)
                    if (m.author.isNotEmpty()) put("author", m.author)
                    if (m.subText.isNotEmpty()) put("subText", m.subText)
                    if (m.mediaType.isNotEmpty()) put("mediaType", m.mediaType)
                    if (m.thumbBase64.isNotEmpty()) put("thumbBase64", m.thumbBase64)
                    put("body", m.body)
                    put("timestamp", m.timestamp)
                    put("direction", m.direction)
                }
            )
        }
        return JSONObject().apply {
            put("threadKey", threadKey)
            put("canReply", canReplyToThread(threadKey))
            put("messages", arr)
        }
    }

    fun wipeAll(context: Context) {
        synchronized(lock) {
            replyHandles.clear()
            recentDedup.clear()
            try {
                getStoreFile(context).delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readRawArrayLocked(context: Context): JSONArray {
        return try {
            val f = getStoreFile(context)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    private fun writeRawArrayLocked(context: Context, arr: JSONArray) {
        try {
            getStoreFile(context).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }
}
