package com.sentinelcam.app.data

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android Call Log Reader & Phone Number Normalizer (v23).
 */
object CallLogHelper {

    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallScreeningRole(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
            } catch (_: Throwable) {
                false
            }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun normalizePhoneKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 8) digits.takeLast(9) else digits
    }

    fun readCallLogAndBlockConfig(
        context: Context,
        prefs: PortalPreferences,
        limit: Int = 120
    ): JSONObject {
        val hasPerm = hasCallLogPermission(context)
        val hasRole = hasCallScreeningRole(context)
        val blockedList = prefs.getBlockedNumbers()
        val blockedKeys = blockedList.map { normalizePhoneKey(it.number) }.filter { it.isNotEmpty() }.toSet()

        val callsArr = JSONArray()
        var errorMsg: String? = null

        if (!hasPerm) {
            errorMsg = "Autorisez « Journal d’appels » une fois dans l’appli SentinelCam sur le téléphone."
        } else {
            try {
                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    val idxId = c.getColumnIndex(CallLog.Calls._ID)
                    val idxNum = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val idxName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val idxType = c.getColumnIndex(CallLog.Calls.TYPE)
                    val idxDate = c.getColumnIndex(CallLog.Calls.DATE)
                    val idxDur = c.getColumnIndex(CallLog.Calls.DURATION)

                    var count = 0
                    val safeMax = limit.coerceIn(20, 200)
                    while (c.moveToNext() && count < safeMax) {
                        count++
                        val id = if (idxId >= 0) c.getString(idxId).orEmpty() else "$count"
                        val num = (if (idxNum >= 0) c.getString(idxNum) else null)?.trim().orEmpty()
                        val name = (if (idxName >= 0) c.getString(idxName) else null)?.trim().orEmpty()
                        val typeInt = if (idxType >= 0) c.getInt(idxType) else CallLog.Calls.INCOMING_TYPE
                        val date = if (idxDate >= 0) c.getLong(idxDate) else 0L
                        val dur = if (idxDur >= 0) c.getLong(idxDur) else 0L

                        val typeStr = when (typeInt) {
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            CallLog.Calls.BLOCKED_TYPE -> "blocked"
                            else -> "incoming"
                        }
                        val key = normalizePhoneKey(num)
                        val isBlockedNow = key.isNotEmpty() && blockedKeys.contains(key)

                        callsArr.put(
                            JSONObject().apply {
                                put("id", id)
                                put("number", num.ifEmpty { "Numéro masqué" })
                                put("contactName", name)
                                put("type", typeStr)
                                put("date", date)
                                put("durationSec", dur)
                                put("blocked", isBlockedNow)
                            }
                        )
                    }
                }
            } catch (t: Throwable) {
                errorMsg = "Lecture du journal d’appels impossible : ${t.message}"
            }
        }

        val blockedArr = JSONArray()
        for (b in blockedList) {
            blockedArr.put(
                JSONObject().apply {
                    put("number", b.number)
                    put("label", b.label)
                    put("addedAt", b.addedAt)
                }
            )
        }

        val allowedArr = JSONArray()
        for (a in prefs.getAllowedCallNumbers()) {
            allowedArr.put(
                JSONObject().apply {
                    put("number", a.number)
                    put("label", a.label)
                    put("addedAt", a.addedAt)
                }
            )
        }

        return JSONObject().apply {
            put("calls", callsArr)
            put("blockedNumbers", blockedArr)
            put("allowedNumbers", allowedArr)
            put("blockUnknownCalls", prefs.blockUnknownCalls)
            put("callWhitelistOnly", prefs.callWhitelistOnly)
            put("hasCallLogPermission", hasPerm)
            put("hasCallScreeningRole", hasRole)
            if (errorMsg != null) put("error", errorMsg)
        }
    }
}
