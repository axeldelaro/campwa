package com.sentinelcam.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strictly opt-in Android Device Data Provider (Contacts, GPS Location, System Info).
 * Only executes after BOTH user-controlled global toggles AND profile permissions AND OS runtime
 * permissions have been verified.
 */
object DeviceDataHelper {

    data class ContactsQueryResult(
        val contactsArray: JSONArray,
        val totalMatched: Int,
        val error: String? = null
    )

    fun readContacts(context: Context, query: String = "", maxItems: Int = 200): ContactsQueryResult {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            return ContactsQueryResult(
                contactsArray = JSONArray(),
                totalMatched = 0,
                error = "Permission Contacts (READ_CONTACTS) non accordée sur le smartphone."
            )
        }

        return try {
            val resolver = context.contentResolver
            val normalizedQuery = query.trim().lowercase()

            data class MutableContact(
                val id: String,
                val name: String,
                val phones: LinkedHashSet<String> = LinkedHashSet(),
                val emails: LinkedHashSet<String> = LinkedHashSet()
            )

            val map = LinkedHashMap<String, MutableContact>()

            // 1. Read phone numbers
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val cid = if (idIdx >= 0) cursor.getString(idIdx) ?: continue else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "Sans nom" else "Sans nom"
                    val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val entry = map.getOrPut(cid) { MutableContact(cid, name) }
                    if (num.isNotEmpty()) entry.phones.add(num)
                }
            }

            // 2. Enrich with emails
            val emailProjection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                emailProjection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val emIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    val cid = if (idIdx >= 0) cursor.getString(idIdx) ?: continue else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "Sans nom" else "Sans nom"
                    val email = if (emIdx >= 0) cursor.getString(emIdx)?.trim().orEmpty() else ""
                    if (email.isNotEmpty()) {
                        val entry = map.getOrPut(cid) { MutableContact(cid, name) }
                        entry.emails.add(email)
                    }
                }
            }

            val filtered = map.values
                .filter { c ->
                    if (normalizedQuery.isEmpty()) true
                    else c.name.lowercase().contains(normalizedQuery) ||
                        c.phones.any { it.lowercase().contains(normalizedQuery) } ||
                        c.emails.any { it.lowercase().contains(normalizedQuery) }
                }
                .sortedBy { it.name.lowercase() }

            val arr = JSONArray()
            for (c in filtered.take(maxItems)) {
                val phonesJson = JSONArray()
                c.phones.forEach { phonesJson.put(it) }
                val emailsJson = JSONArray()
                c.emails.forEach { emailsJson.put(it) }

                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("phones", phonesJson)
                        put("emails", emailsJson)
                    }
                )
            }

            ContactsQueryResult(contactsArray = arr, totalMatched = filtered.size)
        } catch (t: Throwable) {
            ContactsQueryResult(
                contactsArray = JSONArray(),
                totalMatched = 0,
                error = "Impossible de lire les contacts : ${t.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun readBestKnownLocation(context: Context): Pair<JSONObject?, String?> {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            return Pair(null, "Permission Localisation non accordée sur le smartphone.")
        }

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: Location? = null

            for (provider in providers) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (best == null || loc.time > best.time || (loc.time == best.time && loc.accuracy < best.accuracy)) {
                        best = loc
                    }
                } catch (_: Throwable) {
                }
            }

            if (best == null) {
                Pair(null, "Aucune position GPS récente disponible (vérifiez que la localisation est activée).")
            } else {
                val json = JSONObject().apply {
                    put("latitude", best.latitude)
                    put("longitude", best.longitude)
                    put("accuracyMeters", best.accuracy.toInt())
                    put("provider", best.provider ?: "GPS")
                    put("timestamp", best.time)
                }
                Pair(json, null)
            }
        } catch (t: Throwable) {
            Pair(null, "Erreur GPS : ${t.message}")
        }
    }

    fun readDeviceInfo(
        deviceName: String,
        batteryLevel: Int,
        isCharging: Boolean,
        networkType: String,
        uptimeSec: Long
    ): JSONObject {
        var freeMb = 0L
        var totalMb = 0L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            freeMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
            totalMb = (stat.blockCountLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Throwable) {
        }

        return JSONObject().apply {
            put("deviceName", deviceName)
            put("manufacturer", Build.MANUFACTURER ?: "Android")
            put("model", Build.MODEL ?: "Smartphone")
            put("androidVersion", Build.VERSION.RELEASE ?: "?")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("batteryLevel", batteryLevel)
            put("isCharging", isCharging)
            put("networkType", networkType)
            put("storageFreeMb", freeMb)
            put("storageTotalMb", totalMb)
            put("uptimeSec", uptimeSec)
            put("timestamp", System.currentTimeMillis())
        }
    }
}
