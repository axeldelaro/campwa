package com.sentinelcam.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.sentinelcam.app.crypto.CryptoProtocol
import org.json.JSONArray
import org.json.JSONObject

data class AuditLogItem(
    val timestamp: Long,
    val level: String,
    val message: String
)

class PortalPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (prefs.getString(KEY_PAIRING_CODE, null).isNullOrEmpty()) {
            prefs.edit()
                .putString(KEY_PAIRING_CODE, CryptoProtocol.generateSecurePairingCode())
                .putString(KEY_DEVICE_ID, "and-${CryptoProtocol.randomHex(6)}")
                .putString(KEY_DEVICE_SALT, CryptoProtocol.randomHex(32))
                .apply()
        }
    }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var userManuallyStopped: Boolean
        get() = prefs.getBoolean(KEY_MANUALLY_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    val pairingCode: String
        get() = prefs.getString(KEY_PAIRING_CODE, null) ?: CryptoProtocol.generateSecurePairingCode()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: "and-default"

    val deviceSalt: String
        get() = prefs.getString(KEY_DEVICE_SALT, null) ?: CryptoProtocol.randomHex(32)

    val deviceName: String
        get() {
            val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "Smartphone"
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        }

    var pairedTokenId: String?
        get() = prefs.getString(KEY_PAIRED_TOKEN_ID, null)
        set(value) = prefs.edit().putString(KEY_PAIRED_TOKEN_ID, value).apply()

    var pairedKeyHex: String?
        get() = prefs.getString(KEY_PAIRED_KEY_HEX, null)
        set(value) = prefs.edit().putString(KEY_PAIRED_KEY_HEX, value).apply()

    var sasCode: String?
        get() = prefs.getString(KEY_SAS_CODE, null)
        set(value) = prefs.edit().putString(KEY_SAS_CODE, value).apply()

    var sasSymbols: String?
        get() = prefs.getString(KEY_SAS_SYMBOLS, null)
        set(value) = prefs.edit().putString(KEY_SAS_SYMBOLS, value).apply()

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    var lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    fun regeneratePairingCode(): String {
        val nextCode = CryptoProtocol.generateSecurePairingCode()
        prefs.edit()
            .putString(KEY_PAIRING_CODE, nextCode)
            .putString(KEY_DEVICE_SALT, CryptoProtocol.randomHex(32))
            .remove(KEY_PAIRED_TOKEN_ID)
            .remove(KEY_PAIRED_KEY_HEX)
            .remove(KEY_SAS_CODE)
            .remove(KEY_SAS_SYMBOLS)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        appendLog("security", "Nouveau code généré [$nextCode] — anciens jetons révoqués.")
        return nextCode
    }

    fun resetPairingOnly() {
        prefs.edit()
            .remove(KEY_PAIRED_TOKEN_ID)
            .remove(KEY_PAIRED_KEY_HEX)
            .remove(KEY_SAS_CODE)
            .remove(KEY_SAS_SYMBOLS)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        appendLog("security", "Appairage réinitialisé — prêt pour un nouveau navigateur.")
    }

    @Synchronized
    fun appendLog(level: String, message: String) {
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
    }

    @Synchronized
    fun getLogs(): List<AuditLogItem> {
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "sentinelcam_portal_secure_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_MANUALLY_STOPPED = "manually_stopped"
        private const val KEY_AUTO_START_BOOT = "auto_start_boot"
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
    }
}
