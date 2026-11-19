package com.sentinelcam.app.data

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent On-Device Social & Messaging Archive + Live Direct-Reply Cache (v24):
 * - Captures WhatsApp, Instagram, Snapchat, Discord, Messenger, TikTok, Telegram, Signal, etc.
 * - Survives even if the child deletes messages or uses disappearing chats.
 * - Caches active Android Notification RemoteInput actions so the Parent can reply directly
 *   from the Parent Website without opening the app on the child phone.
 * - 100% wiped on unpair.
 */
object SocialMessageStore {

    data class SocialMsgRecord(
        val id: String,
        val threadKey: String,
        val packageName: String,
        val appLabel: String,
        val sender: String,
        val body: String,
        val timestamp: Long,
        val direction: String // "in" | "out"
    )

    private data class ReplyActionHandle(
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

    fun registerReplyAction(threadKey: String, action: Notification.Action, remoteInput: RemoteInput) {
        replyHandles[threadKey] = ReplyActionHandle(action, remoteInput, System.currentTimeMillis())
        if (replyHandles.size > 150) {
            val oldest = replyHandles.entries.minByOrNull { it.value.updatedAt }?.key
            if (oldest != null) replyHandles.remove(oldest)
        }
    }

    fun canReplyToThread(threadKey: String): Boolean = replyHandles.containsKey(threadKey)

    fun sendDirectReply(context: Context, threadKey: String, replyText: String): Pair<Boolean, String?> {
        val clean = replyText.trim().take(600)
        if (clean.isEmpty()) return Pair(false, "Message vide.")
        val handle = replyHandles[threadKey]
            ?: return Pair(
                false,
                "Réponse rapide expirée pour cette conversation (attendez un nouveau message entrant pour répondre directement)."
            )

        return try {
            val fillIntent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(handle.remoteInput.resultKey, clean)
            }
            RemoteInput.addResultsToIntent(arrayOf(handle.remoteInput), fillIntent, bundle)
            handle.action.actionIntent.send(context.applicationContext, 0, fillIntent)

            // Also record our sent reply locally so the thread stays complete
            val existing = getAllMessages(context).firstOrNull { it.threadKey == threadKey }
            appendMessage(
                context = context,
                packageName = existing?.packageName ?: threadKey.substringBefore("::"),
                appLabel = existing?.appLabel ?: "Message",
                sender = existing?.sender ?: threadKey.substringAfter("::"),
                body = clean,
                timestamp = System.currentTimeMillis(),
                direction = "out"
            )
            Pair(true, null)
        } catch (t: Throwable) {
            replyHandles.remove(threadKey)
            Pair(false, "Envoi impossible : ${t.message ?: "notification fermée"}")
        }
    }

    fun appendMessage(
        context: Context,
        packageName: String,
        appLabel: String,
        sender: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        direction: String = "in"
    ): SocialMsgRecord? {
        val cleanSender = sender.trim().take(80)
        val cleanBody = body.trim().take(700)
        if (cleanSender.isEmpty() || cleanBody.isEmpty()) return null

        val threadKey = makeThreadKey(packageName, cleanSender)
        val dedupKey = "$threadKey|$direction|$cleanBody"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // Avoid duplicate inserts when apps repost grouped summary notifications
            val prevTs = recentDedup[dedupKey]
            if (prevTs != null && (now - prevTs) < 45_000L) {
                return null
            }
            recentDedup[dedupKey] = now
            if (recentDedup.size > 400) {
                val first = recentDedup.keys.firstOrNull()
                if (first != null) recentDedup.remove(first)
            }

            val arr = readRawArrayLocked(context)
            val rec = SocialMsgRecord(
                id = "sm_${now}_${UUID.randomUUID().toString().take(5)}",
                threadKey = threadKey,
                packageName = packageName,
                appLabel = appLabel,
                sender = cleanSender,
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
            val snippet: String,
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
                    snippet = m.body.replace('\n', ' ').take(120),
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
