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
                    .putString(KEY_PAIRING_CODE, CryptoProtocol.generateSecurePairingCode())
                    .putString(KEY_DEVICE_ID, "and-${CryptoProtocol.randomHex(6)}")
                    .putString(KEY_DEVICE_SALT, CryptoProtocol.randomHex(32))
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

    /**
     * Multi-browser token registry keyed by tokenId -> pairedKeyHex (up to 10 authorized sessions).
     * Ensures typing the valid 6-char code on PC, mobile, or a fresh browser always pairs cleanly,
     * while regenerating the code revokes all tokens immediately.
     */
    fun getAuthorizedKeyForToken(tokenId: String): String? {
        synchronized(LOCK) {
            val mapJson = prefs.getString(KEY_TOKEN_MAP, null)
            if (!mapJson.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(mapJson)
                    val found = obj.optString(tokenId, "")
                    if (found.length == 64) return found
                } catch (_: Throwable) {
                }
            }
            if (pairedTokenId == tokenId && pairedKeyHex?.length == 64) {
                return pairedKeyHex
            }
            return null
        }
    }

    fun registerAuthorizedToken(tokenId: String, keyHex: String, sas: Pair<String, String>) {
        synchronized(LOCK) {
            val obj = try {
                JSONObject(prefs.getString(KEY_TOKEN_MAP, "{}") ?: "{}")
            } catch (_: Throwable) {
                JSONObject()
            }
            obj.put(tokenId, keyHex)
            // Keep max 10 tokens
            val keys = obj.keys().asSequence().toList()
            if (keys.size > 10) {
                obj.remove(keys.first())
            }
            prefs.edit()
                .putString(KEY_TOKEN_MAP, obj.toString())
                .putString(KEY_PAIRED_TOKEN_ID, tokenId)
                .putString(KEY_PAIRED_KEY_HEX, keyHex)
                .putString(KEY_SAS_CODE, sas.first)
                .putString(KEY_SAS_SYMBOLS, sas.second)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
        }
    }

    fun hasAnyAuthorizedToken(): Boolean {
        synchronized(LOCK) {
            if (!pairedKeyHex.isNullOrEmpty()) return true
            val raw = prefs.getString(KEY_TOKEN_MAP, null) ?: return false
            return try {
                JSONObject(raw).length() > 0
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun regeneratePairingCode(): String {
        synchronized(LOCK) {
            val nextCode = CryptoProtocol.generateSecurePairingCode()
            prefs.edit()
                .putString(KEY_PAIRING_CODE, nextCode)
                .putString(KEY_DEVICE_SALT, CryptoProtocol.randomHex(32))
                .remove(KEY_TOKEN_MAP)
                .remove(KEY_PAIRED_TOKEN_ID)
                .remove(KEY_PAIRED_KEY_HEX)
                .remove(KEY_SAS_CODE)
                .remove(KEY_SAS_SYMBOLS)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            appendLog("security", "Nouveau code généré [$nextCode] — jetons précédents révoqués.")
            return nextCode
        }
    }

    fun resetPairingOnly() {
        synchronized(LOCK) {
            prefs.edit()
                .remove(KEY_TOKEN_MAP)
                .remove(KEY_PAIRED_TOKEN_ID)
                .remove(KEY_PAIRED_KEY_HEX)
                .remove(KEY_SAS_CODE)
                .remove(KEY_SAS_SYMBOLS)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            appendLog("security", "Appairages réinitialisés.")
        }
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
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SALT = "device_salt"
        private const val KEY_TOKEN_MAP = "authorized_token_map"
        private const val KEY_PAIRED_TOKEN_ID = "paired_token_id"
        private const val KEY_PAIRED_KEY_HEX = "paired_key_hex"
        private const val KEY_SAS_CODE = "sas_code"
        private const val KEY_SAS_SYMBOLS = "sas_symbols"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_AUDIT_LOGS = "audit_logs"
    }
}
