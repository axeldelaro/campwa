package com.sentinelcam.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android SMS Inbox & Conversations Reader.
 * Provides a full Messages-app style view (conversation list + chronological chat bubbles)
 * with contact name resolution.
 */
object SmsInboxHelper {

    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasSendSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun listConversations(context: Context, maxThreads: Int = 40): JSONObject {
        val canRead = hasReadSmsPermission(context)
        val canSend = hasSendSmsPermission(context)
        if (!canRead) {
            return JSONObject().apply {
                put("error", "Autorisation lecture SMS manquante sur le téléphone.")
                put("threads", JSONArray())
                put("hasReadPermission", false)
                put("hasSendPermission", canSend)
            }
        }

        data class ThreadAcc(
            val threadId: String,
            val address: String,
            var contactName: String,
            val snippet: String,
            val lastDate: Long,
            val lastType: String,
            var messageCount: Int,
            var unreadCount: Int
        )

        val threads = LinkedHashMap<String, ThreadAcc>()
        val nameCache = HashMap<String, String>()
        val canLookupContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        fun resolveContactName(address: String): String {
            if (!canLookupContacts || address.isBlank()) return ""
            nameCache[address]?.let { return it }
            val resolved = try {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0).orEmpty() else ""
                } ?: ""
            } catch (_: Throwable) {
                ""
            }
            nameCache[address] = resolved
            return resolved
        }

        try {
            val projection = arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val idxThread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                val idxAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val idxBody = c.getColumnIndex(Telephony.Sms.BODY)
                val idxDate = c.getColumnIndex(Telephony.Sms.DATE)
                val idxType = c.getColumnIndex(Telephony.Sms.TYPE)
                val idxRead = c.getColumnIndex(Telephony.Sms.READ)

                var scanned = 0
                while (c.moveToNext() && scanned < 900) {
                    scanned++
                    val addr = (if (idxAddr >= 0) c.getString(idxAddr) else null)?.trim().orEmpty()
                    if (addr.isEmpty()) continue
                    val tid = (if (idxThread >= 0) c.getString(idxThread) else null)?.trim()
                        ?.ifEmpty { addr } ?: addr
                    val body = (if (idxBody >= 0) c.getString(idxBody) else null).orEmpty()
                    val date = if (idxDate >= 0) c.getLong(idxDate) else 0L
                    val typeInt = if (idxType >= 0) c.getInt(idxType) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val isRead = if (idxRead >= 0) c.getInt(idxRead) != 0 else true
                    val typeStr = if (typeInt == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent"

                    val existing = threads[tid]
                    if (existing == null) {
                        if (threads.size >= maxThreads) continue
                        threads[tid] = ThreadAcc(
                            threadId = tid,
                            address = addr,
                            contactName = resolveContactName(addr),
                            snippet = body.replace('\n', ' ').take(120),
                            lastDate = date,
                            lastType = typeStr,
                            messageCount = 1,
                            unreadCount = if (typeStr == "inbox" && !isRead) 1 else 0
                        )
                    } else {
                        existing.messageCount++
                        if (typeStr == "inbox" && !isRead) existing.unreadCount++
                    }
                }
            }
        } catch (t: Throwable) {
            return JSONObject().apply {
                put("error", "Lecture des SMS impossible : ${t.message}")
                put("threads", JSONArray())
                put("hasReadPermission", canRead)
                put("hasSendPermission", canSend)
            }
        }

        val arr = JSONArray()
        for (t in threads.values) {
            arr.put(
                JSONObject().apply {
                    put("threadId", t.threadId)
                    put("address", t.address)
                    put("contactName", t.contactName)
                    put("snippet", t.snippet)
                    put("lastDate", t.lastDate)
                    put("lastType", t.lastType)
                    put("messageCount", t.messageCount)
                    put("unreadCount", t.unreadCount)
                }
            )
        }

        return JSONObject().apply {
            put("threads", arr)
            put("hasReadPermission", true)
            put("hasSendPermission", canSend)
        }
    }

    fun getThreadMessages(
        context: Context,
        threadId: String,
        address: String = "",
        limit: Int = 80
    ): JSONObject {
        if (!hasReadSmsPermission(context)) {
            return JSONObject().apply {
                put("error", "Autorisation lecture SMS manquante sur le téléphone.")
                put("threadId", threadId)
                put("address", address)
                put("messages", JSONArray())
            }
        }

        data class SmsRow(
            val id: String,
            val body: String,
            val date: Long,
            val type: String,
            val read: Boolean
        )

        val rows = ArrayList<SmsRow>()
        val safeLimit = limit.coerceIn(10, 120)

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            val (sel, args) = if (threadId.toLongOrNull() != null) {
                Pair("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId))
            } else if (address.isNotBlank()) {
                Pair("${Telephony.Sms.ADDRESS} = ?", arrayOf(address))
            } else {
                Pair(null, null)
            }

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                sel,
                args,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val idxId = c.getColumnIndex(Telephony.Sms._ID)
                val idxBody = c.getColumnIndex(Telephony.Sms.BODY)
                val idxDate = c.getColumnIndex(Telephony.Sms.DATE)
                val idxType = c.getColumnIndex(Telephony.Sms.TYPE)
                val idxRead = c.getColumnIndex(Telephony.Sms.READ)

                while (c.moveToNext() && rows.size < safeLimit) {
                    val id = if (idxId >= 0) c.getString(idxId).orEmpty() else "${rows.size}"
                    val body = (if (idxBody >= 0) c.getString(idxBody) else null).orEmpty()
                    val date = if (idxDate >= 0) c.getLong(idxDate) else 0L
                    val typeInt = if (idxType >= 0) c.getInt(idxType) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val read = if (idxRead >= 0) c.getInt(idxRead) != 0 else true
                    val typeStr = if (typeInt == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent"
                    rows.add(SmsRow(id, body, date, typeStr, read))
                }
            }
        } catch (t: Throwable) {
            return JSONObject().apply {
                put("error", "Chargement conversation impossible : ${t.message}")
                put("threadId", threadId)
                put("address", address)
                put("messages", JSONArray())
            }
        }

        // Chronological order (oldest -> newest) like a native Messages chat
        rows.reverse()

        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("body", r.body)
                    put("date", r.date)
                    put("type", r.type)
                    put("read", r.read)
                }
            )
        }

        return JSONObject().apply {
            put("threadId", threadId)
            put("address", address)
            put("messages", arr)
        }
    }
}
