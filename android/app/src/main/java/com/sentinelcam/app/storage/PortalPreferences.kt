package com.sentinelcam.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.crypto.KeystoreVault
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AuditLogItem(
    val timestamp: Long,
    val level: String,
    val message: String
)

data class AppLockRule(
    val packageName: String,
    val appName: String,
    val blockedUntilMs: Long
)

data class AppQuotaRule(
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int
)

data class GeofenceZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val inside: Boolean?,
    val lastTransitionTs: Long
)

data class TimeExtensionRequest(
    val id: String,
    val packageName: String,
    val appName: String,
    val requestedMinutes: Int,
    val createdAt: Long
)

data class SecurityEventItem(
    val id: String,
    val timestamp: Long,
    val kind: String, // "intruder" | "sim" | "geofence" | "battery" | "bypass" | "request" | "admin" | "call" | "speed"
    val title: String,
    val detail: String,
    val photoBase64: String? = null
)

data class PhoneRuleEntry(
    val number: String,
    val label: String,
    val addedAt: Long
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
    val allowDeviceControl: Boolean = false,
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

    var globalAllowDeviceControl: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_DEVICE_CONTROL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_DEVICE_CONTROL, value).commit()
        }

    var globalAllowGallery: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_GALLERY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOBAL_GALLERY, value).commit()
        }

    /* ==================== V15 ON-DEVICE AI BLUR SHIELD & PARENT BINDING ==================== */

    var aiBlurFaces: Boolean
        get() = prefs.getBoolean(KEY_AI_BLUR_FACES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AI_BLUR_FACES, value).commit()
        }

    var aiBlurNsfw: Boolean
        get() = prefs.getBoolean(KEY_AI_BLUR_NSFW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AI_BLUR_NSFW, value).commit()
        }

    var aiNsfwStrict: Boolean
        get() = prefs.getBoolean(KEY_AI_NSFW_STRICT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AI_NSFW_STRICT, value).commit()
        }

    var parentManaged: Boolean
        get() = prefs.getBoolean(KEY_PARENT_MANAGED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PARENT_MANAGED, value).commit()
        }

    var unpairRequestPending: Boolean
        get() = prefs.getBoolean(KEY_UNPAIR_REQUEST_PENDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_UNPAIR_REQUEST_PENDING, value).commit()
        }

    var unpairRequestedAt: Long
        get() = prefs.getLong(KEY_UNPAIR_REQUESTED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_UNPAIR_REQUESTED_AT, value).commit()
        }

    var parentFamilyKey: String
        get() = KeystoreVault.decrypt(prefs.getString(KEY_PARENT_FAMILY_KEY, null)) ?: ""
        set(value) {
            val clean = CryptoProtocol.normalizeFamilyKey(value)
            if (clean.length == 12) {
                prefs.edit().putString(KEY_PARENT_FAMILY_KEY, KeystoreVault.encrypt(clean)).commit()
            } else {
                prefs.edit().remove(KEY_PARENT_FAMILY_KEY).commit()
            }
        }

    fun setPairingCodeDirect(rawCode: String) {
        val clean = CryptoProtocol.normalizePairingCode(rawCode)
        require(clean.length == 6) { "Code appareil invalide." }
        synchronized(LOCK) {
            prefs.edit()
                .putString(KEY_PAIRING_CODE, KeystoreVault.encrypt(clean))
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
        }
    }

    var screenPausedUntilMs: Long
        get() = prefs.getLong(KEY_SCREEN_PAUSED_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SCREEN_PAUSED_UNTIL, value).commit()
        }

    /* ==================== TIMED APP LOCK RULES (PERSISTENT & OFFLINE-SAFE) ==================== */

    fun getActiveAppLockRules(nowMs: Long = System.currentTimeMillis()): List<AppLockRule> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_APP_LOCK_RULES, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val active = ArrayList<AppLockRule>()
                var expiredAny = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pkg = o.optString("packageName", "")
                    val until = o.optLong("blockedUntilMs", 0L)
                    if (pkg.isNotEmpty() && until > nowMs) {
                        active.add(
                            AppLockRule(
                                packageName = pkg,
                                appName = o.optString("appName", pkg),
                                blockedUntilMs = until
                            )
                        )
                    } else {
                        expiredAny = true
                    }
                }
                if (expiredAny) {
                    saveAppLockRulesLocked(active)
                }
                active
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun setAppLockRule(packageName: String, appName: String, blockedUntilMs: Long) {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            val list = getActiveAppLockRules(now).filter { it.packageName != packageName }.toMutableList()
            if (blockedUntilMs > now) {
                list.add(AppLockRule(packageName = packageName, appName = appName.ifBlank { packageName }, blockedUntilMs = blockedUntilMs))
            }
            saveAppLockRulesLocked(list)
        }
    }

    fun removeAppLockRule(packageName: String) {
        synchronized(LOCK) {
            val list = getActiveAppLockRules().filter { it.packageName != packageName }
            saveAppLockRulesLocked(list)
        }
    }

    fun clearAllAppLocks() {
        synchronized(LOCK) {
            prefs.edit()
                .remove(KEY_APP_LOCK_RULES)
                .putLong(KEY_SCREEN_PAUSED_UNTIL, 0L)
                .putLong(KEY_SCHOOL_MODE_UNTIL, 0L)
                .commit()
        }
    }

    private fun saveAppLockRulesLocked(list: List<AppLockRule>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().apply {
                    put("packageName", r.packageName)
                    put("appName", r.appName)
                    put("blockedUntilMs", r.blockedUntilMs)
                }
            )
        }
        prefs.edit().putString(KEY_APP_LOCK_RULES, arr.toString()).commit()
    }

    /* ==================== V13 PARENTAL CONTROL: CURFEW, QUOTAS, SCHOOL MODE ==================== */

    var curfewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURFEW_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CURFEW_ENABLED, value).commit()
        }

    /** Minutes from midnight (0..1439), default 21:30 = 1290 */
    var curfewStartMin: Int
        get() = prefs.getInt(KEY_CURFEW_START_MIN, 21 * 60 + 30)
        set(value) {
            prefs.edit().putInt(KEY_CURFEW_START_MIN, value.coerceIn(0, 1439)).commit()
        }

    /** Minutes from midnight (0..1439), default 07:00 = 420 */
    var curfewEndMin: Int
        get() = prefs.getInt(KEY_CURFEW_END_MIN, 7 * 60)
        set(value) {
            prefs.edit().putInt(KEY_CURFEW_END_MIN, value.coerceIn(0, 1439)).commit()
        }

    /** Bitmask: bit 0 = Mon .. bit 6 = Sun, default 127 (all days) */
    var curfewDaysMask: Int
        get() = prefs.getInt(KEY_CURFEW_DAYS_MASK, 127)
        set(value) {
            prefs.edit().putInt(KEY_CURFEW_DAYS_MASK, value and 127).commit()
        }

    /** Temporary bypass when parent grants +15 min or unlocks curfew locally */
    var curfewBypassUntilMs: Long
        get() = prefs.getLong(KEY_CURFEW_BYPASS_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_CURFEW_BYPASS_UNTIL, value).commit()
        }

    fun isCurfewActiveNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!curfewEnabled) return false
        if (curfewBypassUntilMs > nowMs) return false
        val start = curfewStartMin
        val end = curfewEndMin
        if (start == end) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dayBitToday = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
        val mask = curfewDaysMask
        return if (start < end) {
            ((mask and (1 shl dayBitToday)) != 0) && nowMin in start until end
        } else {
            // Overnight curfew (e.g. 21:30 -> 07:00)
            if (nowMin >= start) {
                (mask and (1 shl dayBitToday)) != 0
            } else if (nowMin < end) {
                val yesterdayBit = (dayBitToday + 6) % 7
                (mask and (1 shl yesterdayBit)) != 0
            } else {
                false
            }
        }
    }

    fun getCurfewEndTimestampMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val endH = curfewEndMin / 60
        val endM = curfewEndMin % 60
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (curfewStartMin > curfewEndMin && nowMin >= curfewStartMin) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        cal.set(Calendar.HOUR_OF_DAY, endH)
        cal.set(Calendar.MINUTE, endM)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis.coerceAtLeast(nowMs + 60_000L)
    }

    private fun calendarDayToBit(calDay: Int): Int {
        return when (calDay) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
    }

    fun getAppQuotas(): List<AppQuotaRule> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_APP_QUOTAS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<AppQuotaRule>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pkg = o.optString("packageName", "")
                    val lim = o.optInt("dailyLimitMinutes", 0)
                    if (pkg.isNotEmpty() && lim > 0) {
                        out.add(AppQuotaRule(pkg, o.optString("appName", pkg), lim))
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun setAppQuota(packageName: String, appName: String, dailyLimitMinutes: Int) {
        synchronized(LOCK) {
            val list = getAppQuotas().filter { it.packageName != packageName }.toMutableList()
            if (dailyLimitMinutes > 0) {
                list.add(AppQuotaRule(packageName, appName.ifBlank { packageName }, dailyLimitMinutes.coerceAtMost(720)))
            }
            val arr = JSONArray()
            for (q in list) {
                arr.put(JSONObject().apply {
                    put("packageName", q.packageName)
                    put("appName", q.appName)
                    put("dailyLimitMinutes", q.dailyLimitMinutes)
                })
            }
            prefs.edit().putString(KEY_APP_QUOTAS, arr.toString()).commit()
        }
    }

    private fun todayDateKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun getDailyBonusMinutes(packageName: String): Int {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_APP_BONUS_TODAY, null) ?: return 0
            return try {
                val obj = JSONObject(raw)
                if (obj.optString("date") != todayDateKey()) return 0
                obj.optJSONObject("bonuses")?.optInt(packageName, 0) ?: 0
            } catch (_: Throwable) {
                0
            }
        }
    }

    fun addDailyBonusMinutes(packageName: String, deltaMinutes: Int) {
        synchronized(LOCK) {
            val today = todayDateKey()
            val obj = try {
                val existing = JSONObject(prefs.getString(KEY_APP_BONUS_TODAY, "{}") ?: "{}")
                if (existing.optString("date") == today) existing else JSONObject().put("date", today)
            } catch (_: Throwable) {
                JSONObject().put("date", today)
            }
            val bonuses = obj.optJSONObject("bonuses") ?: JSONObject()
            val cur = bonuses.optInt(packageName, 0)
            bonuses.put(packageName, (cur + deltaMinutes).coerceAtLeast(0))
            obj.put("date", today)
            obj.put("bonuses", bonuses)
            prefs.edit().putString(KEY_APP_BONUS_TODAY, obj.toString()).commit()
        }
    }

    var schoolModeUntilMs: Long
        get() = prefs.getLong(KEY_SCHOOL_MODE_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SCHOOL_MODE_UNTIL, value).commit()
        }

    fun getSchoolAllowedPackages(): Set<String> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_SCHOOL_ALLOWED_PKGS, null)
            if (raw.isNullOrEmpty()) return DEFAULT_SCHOOL_PACKAGES
            return try {
                val arr = JSONArray(raw)
                val out = LinkedHashSet<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "").trim()
                    if (p.isNotEmpty()) out.add(p)
                }
                if (out.isEmpty()) DEFAULT_SCHOOL_PACKAGES else out
            } catch (_: Throwable) {
                DEFAULT_SCHOOL_PACKAGES
            }
        }
    }

    fun setSchoolAllowedPackages(pkgs: Collection<String>) {
        synchronized(LOCK) {
            val arr = JSONArray()
            for (p in pkgs.distinct()) {
                if (p.isNotBlank()) arr.put(p.trim())
            }
            prefs.edit().putString(KEY_SCHOOL_ALLOWED_PKGS, arr.toString()).commit()
        }
    }

    /* ==================== V13 SECURITY: ANTI-BYPASS, LOST MODE, GEOFENCES, EVENTS ==================== */

    var blockSettingsAndStore: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SETTINGS_STORE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_SETTINGS_STORE, value).commit()
        }

    var settingsUnlockUntilMs: Long
        get() = prefs.getLong(KEY_SETTINGS_UNLOCK_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SETTINGS_UNLOCK_UNTIL, value).commit()
        }

    var lostModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOST_MODE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOST_MODE_ENABLED, value).commit()
        }

    var lostModeMessage: String
        get() = prefs.getString(KEY_LOST_MODE_MSG, "Téléphone perdu. Merci de contacter le propriétaire.") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LOST_MODE_MSG, value.trim().take(240)).commit()
        }

    var lostModePhone: String
        get() = prefs.getString(KEY_LOST_MODE_PHONE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LOST_MODE_PHONE, value.trim().take(32)).commit()
        }

    var lastSimFingerprint: String
        get() = prefs.getString(KEY_LAST_SIM_FP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_SIM_FP, value).commit()
        }

    var lowBatteryAlertSent: Boolean
        get() = prefs.getBoolean(KEY_LOW_BAT_ALERT_SENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOW_BAT_ALERT_SENT, value).commit()
        }

    var lockscreenFailCount: Int
        get() = prefs.getInt(KEY_LOCKSCREEN_FAIL_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_LOCKSCREEN_FAIL_COUNT, value.coerceAtLeast(0)).commit()
        }

    fun getGeofences(): List<GeofenceZone> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_GEOFENCES, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<GeofenceZone>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isNotEmpty()) {
                        out.add(
                            GeofenceZone(
                                id = id,
                                name = o.optString("name", "Zone"),
                                latitude = o.optDouble("latitude", 0.0),
                                longitude = o.optDouble("longitude", 0.0),
                                radiusMeters = o.optInt("radiusMeters", 150).coerceIn(50, 5000),
                                inside = if (o.isNull("inside")) null else o.optBoolean("inside"),
                                lastTransitionTs = o.optLong("lastTransitionTs", 0L)
                            )
                        )
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun upsertGeofence(id: String?, name: String, lat: Double, lng: Double, radiusMeters: Int): GeofenceZone {
        synchronized(LOCK) {
            val list = getGeofences().toMutableList()
            val cleanId = if (!id.isNullOrBlank()) id else "geo-${CryptoProtocol.randomHex(5)}"
            val existing = list.firstOrNull { it.id == cleanId }
            val zone = GeofenceZone(
                id = cleanId,
                name = name.trim().ifEmpty { "Zone" }.take(40),
                latitude = lat,
                longitude = lng,
                radiusMeters = radiusMeters.coerceIn(50, 5000),
                inside = existing?.inside,
                lastTransitionTs = existing?.lastTransitionTs ?: 0L
            )
            val updated = mutableListOf(zone)
            updated.addAll(list.filter { it.id != cleanId })
            saveGeofencesLocked(updated.take(8))
            return zone
        }
    }

    fun deleteGeofence(id: String) {
        synchronized(LOCK) {
            saveGeofencesLocked(getGeofences().filter { it.id != id })
        }
    }

    fun updateGeofenceInside(id: String, inside: Boolean, ts: Long) {
        synchronized(LOCK) {
            val updated = getGeofences().map {
                if (it.id == id) it.copy(inside = inside, lastTransitionTs = ts) else it
            }
            saveGeofencesLocked(updated)
        }
    }

    private fun saveGeofencesLocked(zones: List<GeofenceZone>) {
        val arr = JSONArray()
        for (z in zones) {
            arr.put(JSONObject().apply {
                put("id", z.id)
                put("name", z.name)
                put("latitude", z.latitude)
                put("longitude", z.longitude)
                put("radiusMeters", z.radiusMeters)
                put("inside", z.inside ?: JSONObject.NULL)
                put("lastTransitionTs", z.lastTransitionTs)
            })
        }
        prefs.edit().putString(KEY_GEOFENCES, arr.toString()).commit()
    }

    fun getPendingTimeRequests(nowMs: Long = System.currentTimeMillis()): List<TimeExtensionRequest> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_TIME_REQUESTS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<TimeExtensionRequest>()
                val cutoff = nowMs - 2 * 3600_000L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val created = o.optLong("createdAt", 0L)
                    if (created > cutoff) {
                        out.add(
                            TimeExtensionRequest(
                                id = o.optString("id"),
                                packageName = o.optString("packageName"),
                                appName = o.optString("appName"),
                                requestedMinutes = o.optInt("requestedMinutes", 15),
                                createdAt = created
                            )
                        )
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun addTimeExtensionRequest(packageName: String, appName: String, requestedMinutes: Int = 15): TimeExtensionRequest {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            val current = getPendingTimeRequests(now).filter { it.packageName != packageName }.toMutableList()
            val req = TimeExtensionRequest(
                id = "req-${CryptoProtocol.randomHex(5)}",
                packageName = packageName,
                appName = appName.ifBlank { packageName },
                requestedMinutes = requestedMinutes.coerceIn(5, 120),
                createdAt = now
            )
            current.add(0, req)
            saveTimeRequestsLocked(current.take(10))
            return req
        }
    }

    fun removeTimeExtensionRequest(requestId: String): TimeExtensionRequest? {
        synchronized(LOCK) {
            val current = getPendingTimeRequests()
            val found = current.firstOrNull { it.id == requestId }
            saveTimeRequestsLocked(current.filter { it.id != requestId })
            return found
        }
    }

    private fun saveTimeRequestsLocked(list: List<TimeExtensionRequest>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("packageName", r.packageName)
                put("appName", r.appName)
                put("requestedMinutes", r.requestedMinutes)
                put("createdAt", r.createdAt)
            })
        }
        prefs.edit().putString(KEY_TIME_REQUESTS, arr.toString()).commit()
    }

    fun appendSecurityEvent(kind: String, title: String, detail: String, photoBase64: String? = null): SecurityEventItem {
        synchronized(LOCK) {
            val item = SecurityEventItem(
                id = "ev-${CryptoProtocol.randomHex(5)}",
                timestamp = System.currentTimeMillis(),
                kind = kind,
                title = title.take(80),
                detail = detail.take(200),
                photoBase64 = photoBase64?.take(45_000)
            )
            val current = getSecurityEvents().toMutableList()
            current.add(0, item)
            // Keep max 35 events, and keep photo data only on the 4 most recent photo events to stay tiny
            var photosKept = 0
            val trimmed = current.take(35).map { ev ->
                if (ev.photoBase64 != null) {
                    photosKept++
                    if (photosKept <= 4) ev else ev.copy(photoBase64 = null)
                } else {
                    ev
                }
            }
            val arr = JSONArray()
            for (ev in trimmed) {
                arr.put(JSONObject().apply {
                    put("id", ev.id)
                    put("timestamp", ev.timestamp)
                    put("kind", ev.kind)
                    put("title", ev.title)
                    put("detail", ev.detail)
                    if (ev.photoBase64 != null) put("photoBase64", ev.photoBase64)
                })
            }
            prefs.edit().putString(KEY_SECURITY_EVENTS, arr.toString()).apply()
            appendLog("security", "$title — $detail")
            return item
        }
    }

    fun getSecurityEvents(): List<SecurityEventItem> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_SECURITY_EVENTS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<SecurityEventItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        SecurityEventItem(
                            id = o.optString("id", "ev-$i"),
                            timestamp = o.optLong("timestamp", 0L),
                            kind = o.optString("kind", "admin"),
                            title = o.optString("title", ""),
                            detail = o.optString("detail", ""),
                            photoBase64 = if (o.has("photoBase64") && !o.isNull("photoBase64")) o.optString("photoBase64") else null
                        )
                    )
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun clearSecurityEvents() {
        synchronized(LOCK) {
            prefs.edit().remove(KEY_SECURITY_EVENTS).commit()
        }
    }

    /* ==================== V23 CALL BLOCKING (BLACKLIST, UNKNOWN & WHITELIST) ==================== */

    var blockUnknownCalls: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_UNKNOWN_CALLS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_UNKNOWN_CALLS, value).commit()
        }

    var callWhitelistOnly: Boolean
        get() = prefs.getBoolean(KEY_CALL_WHITELIST_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CALL_WHITELIST_ONLY, value).commit()
        }

    fun getBlockedNumbers(): List<PhoneRuleEntry> = readPhoneRuleList(KEY_BLOCKED_NUMBERS)

    fun addBlockedNumber(number: String, label: String = "") {
        val clean = number.trim().take(32)
        if (clean.isEmpty()) return
        synchronized(LOCK) {
            val current = readPhoneRuleList(KEY_BLOCKED_NUMBERS)
                .filterNot { it.number.equals(clean, ignoreCase = true) }
                .toMutableList()
            current.add(0, PhoneRuleEntry(clean, label.trim().take(40), System.currentTimeMillis()))
            writePhoneRuleList(KEY_BLOCKED_NUMBERS, current.take(100))
        }
    }

    fun removeBlockedNumber(number: String) {
        val clean = number.trim()
        synchronized(LOCK) {
            val updated = readPhoneRuleList(KEY_BLOCKED_NUMBERS)
                .filterNot { it.number.equals(clean, ignoreCase = true) }
            writePhoneRuleList(KEY_BLOCKED_NUMBERS, updated)
        }
    }

    fun getAllowedCallNumbers(): List<PhoneRuleEntry> = readPhoneRuleList(KEY_ALLOWED_CALL_NUMBERS)

    fun addAllowedCallNumber(number: String, label: String = "") {
        val clean = number.trim().take(32)
        if (clean.isEmpty()) return
        synchronized(LOCK) {
            val current = readPhoneRuleList(KEY_ALLOWED_CALL_NUMBERS)
                .filterNot { it.number.equals(clean, ignoreCase = true) }
                .toMutableList()
            current.add(0, PhoneRuleEntry(clean, label.trim().take(40), System.currentTimeMillis()))
            writePhoneRuleList(KEY_ALLOWED_CALL_NUMBERS, current.take(100))
        }
    }

    fun removeAllowedCallNumber(number: String) {
        val clean = number.trim()
        synchronized(LOCK) {
            val updated = readPhoneRuleList(KEY_ALLOWED_CALL_NUMBERS)
                .filterNot { it.number.equals(clean, ignoreCase = true) }
            writePhoneRuleList(KEY_ALLOWED_CALL_NUMBERS, updated)
        }
    }

    private fun readPhoneRuleList(key: String): List<PhoneRuleEntry> {
        synchronized(LOCK) {
            val raw = prefs.getString(key, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<PhoneRuleEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val num = o.optString("number", "").trim()
                    if (num.isNotEmpty()) {
                        out.add(
                            PhoneRuleEntry(
                                number = num,
                                label = o.optString("label", ""),
                                addedAt = o.optLong("addedAt", 0L)
                            )
                        )
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun writePhoneRuleList(key: String, list: List<PhoneRuleEntry>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("number", item.number)
                    put("label", item.label)
                    put("addedAt", item.addedAt)
                }
            )
        }
        prefs.edit().putString(key, arr.toString()).commit()
    }

    /* ==================== PARENTAL / ADMIN CODE-PROTECTED BACKGROUND MODE ==================== */

    var adminProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADMIN_PROTECTION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ADMIN_PROTECTION, value).commit()
        }

    fun hasAdminPin(): Boolean {
        return !prefs.getString(KEY_ADMIN_PIN_HASH, null).isNullOrEmpty()
    }

    fun setAdminPin(rawPin: String) {
        val clean = rawPin.trim()
        require(clean.length in 4..12) { "Le code administrateur doit contenir entre 4 et 12 caractères." }
        val salt = CryptoProtocol.randomHex(16)
        val hash = CryptoProtocol.hmacSha256Hex(salt.toByteArray(Charsets.UTF_8), "admin-pin-v9:$clean")
        prefs.edit()
            .putString(KEY_ADMIN_PIN_SALT, KeystoreVault.encrypt(salt))
            .putString(KEY_ADMIN_PIN_HASH, KeystoreVault.encrypt(hash))
            .commit()
    }

    fun verifyAdminPin(candidate: String): Boolean {
        val storedSalt = KeystoreVault.decrypt(prefs.getString(KEY_ADMIN_PIN_SALT, null)) ?: return false
        val storedHash = KeystoreVault.decrypt(prefs.getString(KEY_ADMIN_PIN_HASH, null)) ?: return false
        val expected = CryptoProtocol.hmacSha256Hex(
            storedSalt.toByteArray(Charsets.UTF_8),
            "admin-pin-v9:${candidate.trim()}"
        )
        return CryptoProtocol.constantTimeEqualHex(expected, storedHash)
    }

    fun clearAdminPin() {
        prefs.edit()
            .remove(KEY_ADMIN_PIN_SALT)
            .remove(KEY_ADMIN_PIN_HASH)
            .putBoolean(KEY_ADMIN_PROTECTION, false)
            .commit()
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

    /**
     * Complete, atomic cryptographic & functional revocation of ALL Parent/Adult rights on Unpair.
     * After this runs, ZERO rules, ZERO locks, ZERO quotas, ZERO remote permissions, ZERO admin PIN,
     * ZERO family keys, and ZERO old pairing codes remain valid!
     */
    fun revokeAllParentRightsAndReset(): String {
        synchronized(LOCK) {
            val nextCode = CryptoProtocol.generateSecurePairingCode()
            prefs.edit()
                .putBoolean(KEY_PARENT_MANAGED, false)
                .remove(KEY_PARENT_FAMILY_KEY)
                .putBoolean(KEY_UNPAIR_REQUEST_PENDING, false)
                .putLong(KEY_UNPAIR_REQUESTED_AT, 0L)
                .putBoolean(KEY_ONBOARDING_COMPLETED, false)
                .putBoolean(KEY_MANUALLY_STOPPED, true)
                .putBoolean(KEY_ADMIN_PROTECTION, false)
                .remove(KEY_ADMIN_PIN_SALT)
                .remove(KEY_ADMIN_PIN_HASH)
                .putBoolean(KEY_BLOCK_SETTINGS_STORE, false)
                .putLong(KEY_SETTINGS_UNLOCK_UNTIL, 0L)
                .remove(KEY_APP_LOCK_RULES)
                .putLong(KEY_SCREEN_PAUSED_UNTIL, 0L)
                .putLong(KEY_SCHOOL_MODE_UNTIL, 0L)
                .remove(KEY_SCHOOL_ALLOWED_PKGS)
                .remove(KEY_APP_QUOTAS)
                .remove(KEY_APP_BONUS_TODAY)
                .putBoolean(KEY_CURFEW_ENABLED, false)
                .putLong(KEY_CURFEW_BYPASS_UNTIL, 0L)
                .putBoolean(KEY_LOST_MODE_ENABLED, false)
                .remove(KEY_LOST_MODE_MSG)
                .remove(KEY_LOST_MODE_PHONE)
                .remove(KEY_GEOFENCES)
                .remove(KEY_TIME_REQUESTS)
                .remove(KEY_SECURITY_EVENTS)
                .remove(KEY_BLOCKED_NUMBERS)
                .remove(KEY_ALLOWED_CALL_NUMBERS)
                .putBoolean(KEY_BLOCK_UNKNOWN_CALLS, false)
                .putBoolean(KEY_CALL_WHITELIST_ONLY, false)
                .putBoolean(KEY_AI_BLUR_FACES, false)
                .putBoolean(KEY_AI_BLUR_NSFW, false)
                .putBoolean(KEY_AI_NSFW_STRICT, false)
                .putBoolean(KEY_GLOBAL_SCREEN, false)
                .putBoolean(KEY_GLOBAL_CONTACTS, false)
                .putBoolean(KEY_GLOBAL_LOCATION, false)
                .putBoolean(KEY_GLOBAL_LOCATION_HISTORY, false)
                .putBoolean(KEY_GLOBAL_DEVICE_INFO, false)
                .putBoolean(KEY_GLOBAL_INTERCOM, false)
                .putBoolean(KEY_GLOBAL_SMS_RELAY, false)
                .putBoolean(KEY_GLOBAL_DEVICE_CONTROL, false)
                .putBoolean(KEY_GLOBAL_GALLERY, false)
                .remove(KEY_ACCESS_PROFILES)
                .putString(KEY_PAIRING_CODE, KeystoreVault.encrypt(nextCode))
                .putString(KEY_DEVICE_SALT, KeystoreVault.encrypt(CryptoProtocol.randomHex(32)))
                .remove(KEY_PAIRED_TOKEN_ID)
                .remove(KEY_PAIRED_KEY_HEX)
                .remove(KEY_SAS_CODE)
                .remove(KEY_SAS_SYMBOLS)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            return nextCode
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
                            allowDeviceControl = o.optBoolean("allowDeviceControl", false),
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
        allowSmsRelay: Boolean = false,
        allowDeviceControl: Boolean = false
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
                allowDeviceControl = allowDeviceControl,
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
                    put("allowDeviceControl", p.allowDeviceControl)
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
        private const val KEY_GLOBAL_DEVICE_CONTROL = "global_allow_device_control"
        private const val KEY_GLOBAL_GALLERY = "global_allow_gallery_v15"
        private const val KEY_AI_BLUR_FACES = "ai_blur_faces_v15"
        private const val KEY_AI_BLUR_NSFW = "ai_blur_nsfw_v15"
        private const val KEY_AI_NSFW_STRICT = "ai_nsfw_strict_v15"
        private const val KEY_PARENT_MANAGED = "parent_managed_v15"
        private const val KEY_PARENT_FAMILY_KEY = "parent_family_key_v15"
        private const val KEY_UNPAIR_REQUEST_PENDING = "unpair_request_pending_v17"
        private const val KEY_UNPAIR_REQUESTED_AT = "unpair_requested_at_v17"
        private const val KEY_SCREEN_PAUSED_UNTIL = "screen_paused_until_ms"
        private const val KEY_APP_LOCK_RULES = "app_lock_rules_v11"
        private const val KEY_CURFEW_ENABLED = "curfew_enabled_v13"
        private const val KEY_CURFEW_START_MIN = "curfew_start_min_v13"
        private const val KEY_CURFEW_END_MIN = "curfew_end_min_v13"
        private const val KEY_CURFEW_DAYS_MASK = "curfew_days_mask_v13"
        private const val KEY_CURFEW_BYPASS_UNTIL = "curfew_bypass_until_v13"
        private const val KEY_APP_QUOTAS = "app_quotas_v13"
        private const val KEY_APP_BONUS_TODAY = "app_bonus_today_v13"
        private const val KEY_SCHOOL_MODE_UNTIL = "school_mode_until_v13"
        private const val KEY_SCHOOL_ALLOWED_PKGS = "school_allowed_pkgs_v13"
        private const val KEY_BLOCK_SETTINGS_STORE = "block_settings_store_v13"
        private const val KEY_SETTINGS_UNLOCK_UNTIL = "settings_unlock_until_v13"
        private const val KEY_LOST_MODE_ENABLED = "lost_mode_enabled_v13"
        private const val KEY_LOST_MODE_MSG = "lost_mode_msg_v13"
        private const val KEY_LOST_MODE_PHONE = "lost_mode_phone_v13"
        private const val KEY_LAST_SIM_FP = "last_sim_fp_v13"
        private const val KEY_LOW_BAT_ALERT_SENT = "low_bat_alert_sent_v13"
        private const val KEY_LOCKSCREEN_FAIL_COUNT = "lockscreen_fail_count_v13"
        private const val KEY_GEOFENCES = "geofences_v13"
        private const val KEY_TIME_REQUESTS = "time_requests_v13"
        private const val KEY_SECURITY_EVENTS = "security_events_v13"
        private const val KEY_BLOCKED_NUMBERS = "blocked_numbers_v23"
        private const val KEY_ALLOWED_CALL_NUMBERS = "allowed_call_numbers_v23"
        private const val KEY_BLOCK_UNKNOWN_CALLS = "block_unknown_calls_v23"
        private const val KEY_CALL_WHITELIST_ONLY = "call_whitelist_only_v23"

        private val DEFAULT_SCHOOL_PACKAGES = setOf(
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.indexeducation.pronote"
        )
        private const val KEY_ADMIN_PROTECTION = "admin_protection_enabled"
        private const val KEY_ADMIN_PIN_SALT = "admin_pin_salt"
        private const val KEY_ADMIN_PIN_HASH = "admin_pin_hash"
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
