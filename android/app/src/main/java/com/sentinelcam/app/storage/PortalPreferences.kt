package com.sentinelcam.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.crypto.KeystoreVault
import org.json.JSONArray
import org.json.JSONObject

data class AuditLogItem(
    val timestamp: Long,
    val level: String,
    val message: String
)

data class AccessProfileRecord(
    val id: String,
    val name: String,
    val role: String, // "friend" or "guest"
    val code: String, // 6-char fixed custom code
    val enabled: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val allowAudio: Boolean,
    val allowSwitchCamera: Boolean,
    val allowScreenShare: Boolean,
    val allowContacts: Boolean,
    val allowLocation: Boolean,
    val allowDeviceInfo: Boolean,
    val allowIntercom: Boolean,
    val allowSmsRelay: Boolean = false,
    val lastAccessAt: Long?
)

class PortalPreferences(context: Context) {
    private val safeContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.applicationContext.createDeviceProtectedStorageContext()
    } else {
        context.applicationContext
    }

    private val prefs: SharedPreferences =
        safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(LOCK) {
            if (prefs.getString(KEY_PAIRING_CODE, null).isNullOrEmpty()) {
                prefs.edit()
                    .putString(KEY_PAIRING_CODE, KeystoreVault.encrypt(CryptoProtocol.generateSecurePairingCode()))
                    .putString(KEY_DEVICE_ID, "and-${CryptoProtocol.randomHex(6)}")
                    .putString(KEY_DEVICE_SALT, KeystoreVault.encrypt(CryptoProtocol.randomHex(32)))
                    .commit()
            }
        }
    }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).commit()
        }

    var userManuallyStopped: Boolean
        get() = prefs.getBoolean(KEY_MANUALLY_STOPPED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, value).commit()
        }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).commit()
        }

    /* ==================== USER-CONTROLLED GLOBAL SHARE TOGGLES ==================== */

    var globalAllowScreenShare: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_SCREEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_SCREEN, value).commit()
        }

    var globalAllowContacts: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_CONTACTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_CONTACTS, value).commit()
        }

    var globalAllowLocation: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_LOCATION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_LOCATION, value).commit()
        }

    var globalAllowLocationHistory: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_LOCATION_HISTORY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_LOCATION_HISTORY, value).commit()
        }

    /** "eco" | "balanced" | "precision" */
    var gpsTrackingMode: String
        get() = prefs.getString(KEY_GPS_TRACKING_MODE, "eco") ?: "eco"
        set(value) {
            val clean = if (value in listOf("eco", "balanced", "precision")) value else "eco"
            prefs.edit().putString(KEY_GPS_TRACKING_MODE, clean).commit()
        }

    var globalAllowDeviceInfo: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_DEVICE_INFO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_DEVICE_INFO, value).commit()
        }

    var globalAllowIntercom: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_INTERCOM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_INTERCOM, value).commit()
        }

    /** Strictly opt-in (OFF by default): Lost-Phone Emergency SMS Relay via native SIM */
    var globalAllowSmsRelay: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_SMS_RELAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_SMS_RELAY, value).commit()
        }

    val pairingCode: String
        get() {
            val raw = prefs.getString(KEY_PAIRING_CODE, null)
            return KeystoreVault.decrypt(raw) ?: CryptoProtocol.generateSecurePairingCode()
        }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: "and-default"

    val deviceSalt: String
        get() {
            val raw = prefs.getString(KEY_DEVICE_SALT, null)
            return KeystoreVault.decrypt(raw) ?: CryptoProtocol.randomHex(32)
        }

    val deviceName: String
        get() {
            val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "Smartphone"
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        }

    var pairedTokenId: String?
        get() = prefs.getString(KEY_PAIRED_TOKEN_ID, null)
        set(value) {
            prefs.edit().putString(KEY_PAIRED_TOKEN_ID, value).commit()
        }

    var pairedKeyHex: String?
        get() = prefs.getString(KEY_PAIRED_KEY_HEX, null)
        set(value) {
            prefs.edit().putString(KEY_PAIRED_KEY_HEX, value).commit()
        }

    var sasCode: String?
        get() = prefs.getString(KEY_SAS_CODE, null)
        set(value) {
            prefs.edit().putString(KEY_SAS_CODE, value).commit()
        }

    var sasSymbols: String?
        get() = prefs.getString(KEY_SAS_SYMBOLS, null)
        set(value) {
            prefs.edit().putString(KEY_SAS_SYMBOLS, value).commit()
        }

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).commit()
        }

    var lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).commit()
        }

    fun hasAnyAuthorizedToken(): Boolean {
        synchronized(LOCK) {
            return !pairedTokenId.isNullOrEmpty() || !pairedKeyHex.isNullOrEmpty()
        }
    }

    fun regeneratePairingCode(): String {
        synchronized(LOCK) {
            val usedCodes = getAccessProfiles().map { it.code }.toSet()
            var nextCode = CryptoProtocol.generateSecurePairingCode()
            var tries = 0
            while (usedCodes.contains(nextCode) && tries < 10) {
                nextCode = CryptoProtocol.generateSecurePairingCode()
                tries++
            }
            prefs.edit()
                .putString(KEY_PAIRING_CODE, KeystoreVault.encrypt(nextCode))
                .putString(KEY_DEVICE_SALT, KeystoreVault.encrypt(CryptoProtocol.randomHex(32)))
                .remove(KEY_PAIRED_TOKEN_ID)
                .remove(KEY_PAIRED_KEY_HEX)
                .remove(KEY_SAS_CODE)
                .remove(KEY_SAS_SYMBOLS)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            appendLog("security", "Nouveau code principal généré [$nextCode].")
            return nextCode
        }
    }

    fun resetPairingOnly() {
        synchronized(LOCK) {
            prefs.edit()
                .remove(KEY_PAIRED_TOKEN_ID)
                .remove(KEY_PAIRED_KEY_HEX)
                .remove(KEY_SAS_CODE)
                .remove(KEY_SAS_SYMBOLS)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            appendLog("security", "Sessions réinitialisées.")
        }
    }

    /* ==================== ACCESS PROFILES (FRIENDS & TIMED PASSES) ==================== */

    fun getAccessProfiles(): List<AccessProfileRecord> {
        synchronized(LOCK) {
            val stored = prefs.getString(KEY_ACCESS_PROFILES, null) ?: return emptyList()
            val raw = KeystoreVault.decrypt(stored) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<AccessProfileRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val expVal = if (o.isNull("expiresAt")) null else o.optLong("expiresAt", -1L).takeIf { it > 0L }
                    val lastVal = if (o.isNull("lastAccessAt")) null else o.optLong("lastAccessAt", -1L).takeIf { it > 0L }
                    out.add(
                        AccessProfileRecord(
                            id = o.optString("id"),
                            name = o.optString("name", "Accès"),
                            role = if (o.optString("role") == "friend") "friend" else "guest",
                            code = CryptoProtocol.normalizePairingCode(o.optString("code", "")),
                            enabled = o.optBoolean("enabled", true),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            expiresAt = expVal,
                            allowAudio = o.optBoolean("allowAudio", true),
                            allowSwitchCamera = o.optBoolean("allowSwitchCamera", true),
                            allowScreenShare = o.optBoolean("allowScreenShare", false),
                            allowContacts = o.optBoolean("allowContacts", false),
                            allowLocation = o.optBoolean("allowLocation", false),
                            allowDeviceInfo = o.optBoolean("allowDeviceInfo", true),
                            allowIntercom = o.optBoolean("allowIntercom", true),
                            allowSmsRelay = o.optBoolean("allowSmsRelay", false),
                            lastAccessAt = lastVal
                        )
                    )
                }
                out.filter { it.id.isNotEmpty() && it.code.length == 6 }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun findAccessProfileById(id: String): AccessProfileRecord? {
        return getAccessProfiles().firstOrNull { it.id == id }
    }

    fun upsertAccessProfile(
        id: String?,
        name: String,
        role: String,
        rawCode: String,
        enabled: Boolean,
        expiresAt: Long?,
        allowAudio: Boolean,
        allowSwitchCamera: Boolean,
        allowScreenShare: Boolean,
        allowContacts: Boolean,
        allowLocation: Boolean,
        allowDeviceInfo: Boolean,
        allowIntercom: Boolean,
        allowSmsRelay: Boolean = false
    ): AccessProfileRecord {
        synchronized(LOCK) {
            val cleanCode = CryptoProtocol.normalizePairingCode(rawCode)
            require(cleanCode.length == 6) {
                "Le code fixe doit comporter exactement 6 caractères alphanumériques."
            }
            require(cleanCode != pairingCode) {
                "Ce code est déjà utilisé comme code principal."
            }

            val current = getAccessProfiles().toMutableList()
            val collision = current.firstOrNull { it.code == cleanCode && it.id != id }
            require(collision == null) {
                "Le code $cleanCode est déjà utilisé par « ${collision?.name} »."
            }

            val existing = if (!id.isNullOrEmpty()) current.firstOrNull { it.id == id } else null
            val safeRole = if (role == "friend") "friend" else "guest"
            val record = AccessProfileRecord(
                id = existing?.id ?: "prof-${CryptoProtocol.randomHex(6)}",
                name = name.trim().ifEmpty { if (safeRole == "friend") "Ami" else "Pass Temporaire" },
                role = safeRole,
                code = cleanCode,
                enabled = enabled,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                expiresAt = expiresAt,
                allowAudio = allowAudio,
                allowSwitchCamera = allowSwitchCamera,
                allowScreenShare = allowScreenShare,
                allowContacts = allowContacts,
                allowLocation = allowLocation,
                allowDeviceInfo = allowDeviceInfo,
                allowIntercom = allowIntercom,
                allowSmsRelay = allowSmsRelay,
                lastAccessAt = existing?.lastAccessAt
            )

            val updated = mutableListOf(record)
            updated.addAll(current.filter { it.id != record.id })
            saveAccessProfilesLocked(updated.take(20))
            appendLog(
                "security",
                if (safeRole == "friend") "Profil ami enregistré : ${record.name} [${record.code}]"
                else "Pass temporaire créé : ${record.name} [${record.code}]"
            )
            return record
        }
    }

    fun toggleAccessProfile(id: String, enabled: Boolean) {
        synchronized(LOCK) {
            val updated = getAccessProfiles().map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            saveAccessProfilesLocked(updated)
        }
    }

    fun deleteAccessProfile(id: String) {
        synchronized(LOCK) {
            val updated = getAccessProfiles().filter { it.id != id }
            saveAccessProfilesLocked(updated)
            appendLog("security", "Accès révoqué et supprimé.")
        }
    }

    fun touchProfileLastAccess(id: String) {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            val updated = getAccessProfiles().map {
                if (it.id == id) it.copy(lastAccessAt = now) else it
            }
            saveAccessProfilesLocked(updated)
        }
    }

    private fun saveAccessProfilesLocked(list: List<AccessProfileRecord>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("role", p.role)
                    put("code", p.code)
                    put("enabled", p.enabled)
                    put("createdAt", p.createdAt)
                    put("expiresAt", p.expiresAt ?: JSONObject.NULL)
                    put("allowAudio", p.allowAudio)
                    put("allowSwitchCamera", p.allowSwitchCamera)
                    put("allowScreenShare", p.allowScreenShare)
                    put("allowContacts", p.allowContacts)
                    put("allowLocation", p.allowLocation)
                    put("allowDeviceInfo", p.allowDeviceInfo)
                    put("allowIntercom", p.allowIntercom)
                    put("allowSmsRelay", p.allowSmsRelay)
                    put("lastAccessAt", p.lastAccessAt ?: JSONObject.NULL)
                }
            )
        }
        prefs.edit().putString(KEY_ACCESS_PROFILES, KeystoreVault.encrypt(arr.toString())).commit()
    }

    fun appendLog(level: String, message: String) {
        synchronized(LOCK) {
            try {
                val current = getLogs().toMutableList()
                current.add(0, AuditLogItem(System.currentTimeMillis(), level, message))
                val trimmed = current.take(40)
                val arr = JSONArray()
                for (item in trimmed) {
                    arr.put(
                        JSONObject().apply {
                            put("timestamp", item.timestamp)
                            put("level", item.level)
                            put("message", item.message)
                        }
                    )
                }
                prefs.edit().putString(KEY_AUDIT_LOGS, arr.toString()).apply()
            } catch (_: Throwable) {
            }
        }
    }

    fun getLogs(): List<AuditLogItem> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_AUDIT_LOGS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val list = ArrayList<AuditLogItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AuditLogItem(
                            timestamp = obj.optLong("timestamp", 0L),
                            level = obj.optString("level", "info"),
                            message = obj.optString("message", "")
                        )
                    )
                }
                list
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    companion object {
        private val LOCK = Any()
        private const val PREFS_NAME = "sentinelcam_portal_dp_prefs_v2"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_MANUALLY_STOPPED = "manually_stopped"
        private const val KEY_AUTO_START_BOOT = "auto_start_boot"
        private const val KEY_GLOBAL_SCREEN = "global_allow_screen_share"
        private const val KEY_GLOBAL_CONTACTS = "global_allow_contacts"
        private const val KEY_GLOBAL_LOCATION = "global_allow_location"
        private const val KEY_GLOBAL_LOCATION_HISTORY = "global_allow_location_history"
        private const val KEY_GPS_TRACKING_MODE = "gps_tracking_mode"
        private const val KEY_GLOBAL_DEVICE_INFO = "global_allow_device_info"
        private const val KEY_GLOBAL_INTERCOM = "global_allow_intercom"
        private const val KEY_GLOBAL_SMS_RELAY = "global_allow_sms_relay"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SALT = "device_salt"
        private const val KEY_PAIRED_TOKEN_ID = "paired_token_id"
        private const val KEY_PAIRED_KEY_HEX = "paired_key_hex"
        private const val KEY_SAS_CODE = "sas_code"
        private const val KEY_SAS_SYMBOLS = "sas_symbols"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_AUDIT_LOGS = "audit_logs"
        private const val KEY_ACCESS_PROFILES = "access_profiles_v4"
    }
}
