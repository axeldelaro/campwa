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
 * Native Android SMS Inbox & Conversations Reader (v20):
 * - Scans up to 8000 messages across all threads so older conversations ALWAYS appear.
 * - Falls back seamlessly to content://sms/inbox + content://sms/sent + content://sms/outbox
 *   if OEM ROMs restrict Telephony.Sms.CONTENT_URI.
 * - Matches both thread_id and normalized phone suffix when loading full chat history.
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

    private fun normalizePhoneKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 8) digits.takeLast(9) else raw.trim().lowercase()
    }

    private data class RawSmsRecord(
        val id: String,
        val threadId: String,
        val address: String,
        val body: String,
        val date: Long,
        val type: String,
        val read: Boolean
    )

    private fun querySmsUri(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        maxRows: Int,
        defaultType: String? = null
    ): List<RawSmsRecord> {
        val out = ArrayList<RawSmsRecord>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val idxId = c.getColumnIndex(Telephony.Sms._ID)
                val idxThread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                val idxAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val idxBody = c.getColumnIndex(Telephony.Sms.BODY)
                val idxDate = c.getColumnIndex(Telephony.Sms.DATE)
                val idxType = c.getColumnIndex(Telephony.Sms.TYPE)
                val idxRead = c.getColumnIndex(Telephony.Sms.READ)

                var count = 0
                while (c.moveToNext() && count < maxRows) {
                    count++
                    val id = if (idxId >= 0) c.getString(idxId).orEmpty() else "$count"
                    val addr = (if (idxAddr >= 0) c.getString(idxAddr) else null)?.trim().orEmpty()
                    if (addr.isEmpty()) continue
                    val rawTid = (if (idxThread >= 0) c.getString(idxThread) else null)?.trim().orEmpty()
                    val tid = if (rawTid.isNotEmpty() && rawTid != "0") rawTid else normalizePhoneKey(addr)
                    val body = (if (idxBody >= 0) c.getString(idxBody) else null).orEmpty()
                    val date = if (idxDate >= 0) c.getLong(idxDate) else 0L
                    val typeInt = if (idxType >= 0) c.getInt(idxType) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val isRead = if (idxRead >= 0) c.getInt(idxRead) != 0 else true
                    val typeStr = defaultType
                        ?: if (typeInt == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent"
                    out.add(RawSmsRecord(id, tid, addr, body, date, typeStr, isRead))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun queryAllSmsRows(context: Context, maxRows: Int = 8000): List<RawSmsRecord> {
        val primary = querySmsUri(context, Telephony.Sms.CONTENT_URI, null, null, maxRows)
        if (primary.isNotEmpty()) return primary

        // Fallback for OEM ROMs that expose inbox & sent URIs separately
        val seenIds = HashSet<String>()
        val merged = ArrayList<RawSmsRecord>()
        val uris = listOf(
            Pair(Uri.parse("content://sms/inbox"), "inbox"),
            Pair(Uri.parse("content://sms/sent"), "sent"),
            Pair(Uri.parse("content://sms/outbox"), "sent")
        )
        for ((u, defType) in uris) {
            val rows = querySmsUri(context, u, null, null, maxRows / 2, defType)
            for (r in rows) {
                if (seenIds.add("${r.id}_${r.date}")) {
                    merged.add(r)
                }
            }
        }
        merged.sortByDescending { it.date }
        return merged
    }

    fun listConversations(context: Context, maxThreads: Int = 60): JSONObject {
        val canRead = hasReadSmsPermission(context)
        val canSend = hasSendSmsPermission(context)
        if (!canRead) {
            return JSONObject().apply {
                put(
                    "error",
                    "Autorisez « Messages SMS » dans l’appli SentinelCam sur le téléphone (sur Android 13–15, activez « Paramètres restreints » si demandé)."
                )
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
            val allRows = queryAllSmsRows(context, maxRows = 8000)
            for (r in allRows) {
                val existing = threads[r.threadId]
                if (existing == null) {
                    if (threads.size >= maxThreads) continue
                    threads[r.threadId] = ThreadAcc(
                        threadId = r.threadId,
                        address = r.address,
                        contactName = resolveContactName(r.address),
                        snippet = r.body.replace('\n', ' ').take(120),
                        lastDate = r.date,
                        lastType = r.type,
                        messageCount = 1,
                        unreadCount = if (r.type == "inbox" && !r.read) 1 else 0
                    )
                } else {
                    existing.messageCount++
                    if (r.type == "inbox" && !r.read) existing.unreadCount++
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

        val sorted = threads.values.sortedByDescending { it.lastDate }
        val arr = JSONArray()
        for (t in sorted) {
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
        limit: Int = 150
    ): JSONObject {
        if (!hasReadSmsPermission(context)) {
            return JSONObject().apply {
                put("error", "Autorisation lecture SMS manquante sur le téléphone.")
                put("threadId", threadId)
                put("address", address)
                put("messages", JSONArray())
            }
        }

        val safeLimit = limit.coerceIn(20, 200)
        val rows = ArrayList<RawSmsRecord>()

        try {
            // 1. Primary query by numeric thread_id
            if (threadId.toLongOrNull() != null) {
                rows.addAll(
                    querySmsUri(
                        context,
                        Telephony.Sms.CONTENT_URI,
                        "${Telephony.Sms.THREAD_ID} = ?",
                        arrayOf(threadId),
                        safeLimit
                    )
                )
            }

            // 2. Fallback / supplement by exact or normalized phone number if thread_id returned nothing
            if (rows.isEmpty() && address.isNotBlank()) {
                val byExactAddr = querySmsUri(
                    context,
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.ADDRESS} = ?",
                    arrayOf(address),
                    safeLimit
                )
                rows.addAll(byExactAddr)
            }

            if (rows.isEmpty() && address.isNotBlank()) {
                val targetKey = normalizePhoneKey(address)
                val allRows = queryAllSmsRows(context, maxRows = 4000)
                for (r in allRows) {
                    if (r.threadId == threadId || normalizePhoneKey(r.address) == targetKey) {
                        rows.add(r)
                        if (rows.size >= safeLimit) break
                    }
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

        // Deduplicate & sort chronologically (oldest -> newest) like a native Messages chat
        val unique = LinkedHashMap<String, RawSmsRecord>()
        for (r in rows) {
            unique["${r.id}_${r.date}"] = r
        }
        val sorted = unique.values.sortedBy { it.date }

        val arr = JSONArray()
        for (r in sorted) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("body", r.body.take(800))
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
