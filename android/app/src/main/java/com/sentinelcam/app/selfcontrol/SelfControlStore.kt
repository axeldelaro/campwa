package com.sentinelcam.app.selfcontrol

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import com.sentinelcam.app.control.ParentAlertPushHelper
import com.sentinelcam.app.storage.SentinelStoreDb
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * SentinelCam v28.0 — Pacte Geo-Vault (Auto-Contrôle Anti-Addiction) & Local DNS/VPN State Store.
 *
 * Security Invariants (enforced natively in Kotlin, never trust UI alone):
 * 1. Device-Protected Storage so rules enforce immediately after reboot before unlock.
 * 2. Strict One-Way Rule when `pactSealed == true` and NOT inside an active Geo-Unlock / Matured Cooling-Off session:
 *    - HARDENING (adding blocked apps, adding DNS categories, adding domains, enabling guards, starting Detox,
 *      lowering quotas, enabling Feed Killers) is ALWAYS allowed anywhere.
 *    - WEAKENING (removing blocks, raising quotas, disabling DNS categories/VPN, turning off guards, unsealing)
 *      is HARD-REJECTED unless physically verified inside the chosen map Geo-Unlock Zone (with anti-Fake-GPS dwell),
 *      OR via matured Cooling-Off delay, OR Partner Emergency Key, OR QR/NFC physical tag.
 * 3. Monotonic Anti-Cheat Clock: tracks `SystemClock.elapsedRealtime()` anchor to detect wall-clock tampering!
 */
class SelfControlStore(context: Context) {

    data class SessionQuotaRule(
        val packageName: String,
        val appName: String,
        val maxSessionMin: Int,
        val cooldownMin: Int
    )

    data class ScheduleSlot(
        val id: String,
        val name: String,
        val startMin: Int,
        val endMin: Int,
        val daysMask: Int, // bit 0=Mon .. bit 6=Sun
        val mode: String, // "focus" | "block_social" | "sleep"
        val blockedPackages: List<String>
    )

    data class FocusGeofenceRule(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Int,
        val mode: String, // "deep_work" | "night_curfew"
        val insideNow: Boolean
    )

    private val safeContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.applicationContext.createDeviceProtectedStorageContext()
    } else {
        context.applicationContext
    }

    private val prefs: SharedPreferences =
        safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val db: SentinelStoreDb = SentinelStoreDb.get(safeContext)

    init {
        synchronized(LOCK) {
            if (prefs.getLong(KEY_STREAK_START_MS, 0L) == 0L) {
                prefs.edit().putLong(KEY_STREAK_START_MS, System.currentTimeMillis()).apply()
            }
            checkAndAnchorMonotonicClock()
        }
    }

    /* ==================== MONOTONIC ANTI-CHEAT CLOCK ==================== */

    /**
     * Detects if user manually moved the system clock forward/backward by > 12 minutes
     * without rebooting (`elapsedRealtime` moved normally while `currentTimeMillis` jumped).
     */
    fun checkAndAnchorMonotonicClock(): Boolean {
        synchronized(LOCK) {
            val wallNow = System.currentTimeMillis()
            val monoNow = SystemClock.elapsedRealtime()
            val lastWall = prefs.getLong(KEY_CLOCK_ANCHOR_WALL, 0L)
            val lastMono = prefs.getLong(KEY_CLOCK_ANCHOR_MONO, 0L)
            var tampered = prefs.getBoolean(KEY_CLOCK_TAMPERED, false)

            if (lastWall > 0L && lastMono > 0L && monoNow >= lastMono) {
                val expectedWall = lastWall + (monoNow - lastMono)
                val driftMs = abs(wallNow - expectedWall)
                // > 15 min jump without reboot = suspicious manual clock manipulation!
                if (driftMs > 15L * 60_000L && pactSealed) {
                    tampered = true
                    prefs.edit().putBoolean(KEY_CLOCK_TAMPERED, true).apply()
                    notifyPartnerIfConfigured(
                        "Manipulation d'horloge détectée",
                        "Décalage système suspect de ${driftMs / 60_000L} min détecté sous Pacte scellé."
                    )
                }
            }
            prefs.edit()
                .putLong(KEY_CLOCK_ANCHOR_WALL, wallNow)
                .putLong(KEY_CLOCK_ANCHOR_MONO, monoNow)
                .apply()
            return tampered
        }
    }

    var clockTamperDetected: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_TAMPERED, false)
        set(value) { prefs.edit().putBoolean(KEY_CLOCK_TAMPERED, value).commit() }

    /* ==================== CORE MODE & PACT GEO-VAULT SEAL ==================== */

    var selfControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_SELF_CONTROL_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SELF_CONTROL_ENABLED, value).commit() }

    var pactSealed: Boolean
        get() = prefs.getBoolean(KEY_PACT_SEALED, false)
        set(value) { prefs.edit().putBoolean(KEY_PACT_SEALED, value).commit() }

    var pactSealedAtMs: Long
        get() = prefs.getLong(KEY_PACT_SEALED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_PACT_SEALED_AT, value).commit() }

    var unlockZoneLat: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ZONE_LAT, java.lang.Double.doubleToRawLongBits(0.0)))
        set(value) { prefs.edit().putLong(KEY_ZONE_LAT, java.lang.Double.doubleToRawLongBits(value)).commit() }

    var unlockZoneLng: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ZONE_LNG, java.lang.Double.doubleToRawLongBits(0.0)))
        set(value) { prefs.edit().putLong(KEY_ZONE_LNG, java.lang.Double.doubleToRawLongBits(value)).commit() }

    fun hasUnlockZoneConfigured(): Boolean = abs(unlockZoneLat) > 0.0001 || abs(unlockZoneLng) > 0.0001

    var unlockZoneRadiusM: Int
        get() = prefs.getInt(KEY_ZONE_RADIUS_M, 100).coerceIn(30, 500)
        set(value) { prefs.edit().putInt(KEY_ZONE_RADIUS_M, value.coerceIn(30, 500)).commit() }

    var unlockZoneLabel: String
        get() = prefs.getString(KEY_ZONE_LABEL, "Zone de déverrouillage") ?: "Zone de déverrouillage"
        set(value) { prefs.edit().putString(KEY_ZONE_LABEL, value.trim().take(60)).commit() }

    var unlockDwellSec: Int
        get() = prefs.getInt(KEY_ZONE_DWELL_SEC, 180).coerceIn(30, 600)
        set(value) { prefs.edit().putInt(KEY_ZONE_DWELL_SEC, value.coerceIn(30, 600)).commit() }

    var unlockSessionUntilMs: Long
        get() = prefs.getLong(KEY_UNLOCK_SESSION_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_UNLOCK_SESSION_UNTIL, value).commit() }

    /* ==================== FALLBACK UNLOCK KEYS ==================== */

    /** 0 = disabled (strict GPS only), 24 / 48 / 72 hours */
    var coolingOffHours: Int
        get() = prefs.getInt(KEY_COOLING_OFF_HOURS, 0).coerceIn(0, 168)
        set(value) { prefs.edit().putInt(KEY_COOLING_OFF_HOURS, value.coerceIn(0, 168)).commit() }

    var coolingOffRequestedAtMs: Long
        get() = prefs.getLong(KEY_COOLING_OFF_REQ_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_COOLING_OFF_REQ_AT, value).commit() }

    var partnerCodeHash: String
        get() = prefs.getString(KEY_PARTNER_CODE_HASH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PARTNER_CODE_HASH, value).commit() }

    var qrSecretHash: String
        get() = prefs.getString(KEY_QR_SECRET_HASH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_QR_SECRET_HASH, value).commit() }

    var partnerAlertTopic: String
        get() = prefs.getString(KEY_PARTNER_ALERT_TOPIC, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PARTNER_ALERT_TOPIC, value.trim().take(64)).commit() }

    fun isUnlockSessionActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        unlockSessionUntilMs > nowMs

    fun isCoolingOffMatured(nowMs: Long = System.currentTimeMillis()): Boolean {
        val h = coolingOffHours
        val req = coolingOffRequestedAtMs
        if (h <= 0 || req <= 0L) return false
        return nowMs >= req + h * 3_600_000L
    }

    /**
     * THE GOLDEN INVARIANT:
     * Can the user weaken, disable, remove rules, or unseal the pact right now?
     * Returns FALSE whenever Pact is sealed and user is outside their GPS unlock session / cooling-off!
     */
    fun canWeakenRulesNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!selfControlEnabled || !pactSealed) return true
        if (isUnlockSessionActive(nowMs)) return true
        if (isCoolingOffMatured(nowMs)) return true
        return false
    }

    /** True whenever Settings / VPN / A11y / DeviceAdmin / Date-Time / Uninstall must be shielded for Self-Control! */
    fun isSettingsProtectedNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!selfControlEnabled || !pactSealed) return false
        return !canWeakenRulesNow(nowMs)
    }

    fun sealPact(
        lat: Double,
        lng: Double,
        radiusM: Int,
        label: String,
        dwellSec: Int,
        coolingHours: Int,
        partnerRawCode: String?,
        qrRawSecret: String?,
        partnerTopic: String?
    ): Pair<Boolean, String> {
        synchronized(LOCK) {
            if (abs(lat) < 0.0001 && abs(lng) < 0.0001) {
                return Pair(false, "Choisissez un point exact sur la carte avant de sceller le Pacte.")
            }
            if (pactSealed && !canWeakenRulesNow()) {
                return Pair(false, "Le Pacte est déjà scellé. Rendez-vous dans votre zone carte pour le modifier.")
            }
            selfControlEnabled = true
            unlockZoneLat = lat
            unlockZoneLng = lng
            unlockZoneRadiusM = radiusM.coerceIn(30, 500)
            unlockZoneLabel = label.ifBlank { "Zone de déverrouillage" }
            unlockDwellSec = dwellSec.coerceIn(30, 600)
            coolingOffHours = coolingHours.coerceIn(0, 168)
            coolingOffRequestedAtMs = 0L
            if (!partnerRawCode.isNullOrBlank()) {
                partnerCodeHash = sha256(partnerRawCode.trim().uppercase(Locale.US))
            }
            if (!qrRawSecret.isNullOrBlank()) {
                qrSecretHash = sha256(qrRawSecret.trim())
            }
            if (partnerTopic != null) {
                partnerAlertTopic = partnerTopic.trim()
            }
            pactSealed = true
            pactSealedAtMs = System.currentTimeMillis()
            unlockSessionUntilMs = 0L
            clockTamperDetected = false
            prefs.edit().putLong(KEY_STREAK_START_MS, System.currentTimeMillis()).commit()
            checkAndAnchorMonotonicClock()
            notifyPartnerIfConfigured(
                "Pacte Geo-Vault scellé",
                "Auto-contrôle activé (zone : ${unlockZoneLabel}, rayon ${unlockZoneRadiusM} m)."
            )
            return Pair(true, "Pacte scellé ! Toute réduction de règles exige désormais votre présence physique dans la zone choisie.")
        }
    }

    fun grantUnlockSession(reason: String, durationMinutes: Int = 15) {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            unlockSessionUntilMs = now + durationMinutes.coerceIn(5, 30) * 60_000L
            coolingOffRequestedAtMs = 0L
            notifyPartnerIfConfigured("Session de modification ouverte", "Déverrouillage validé : $reason ($durationMinutes min).")
        }
    }

    fun revokeUnlockSession() {
        synchronized(LOCK) {
            unlockSessionUntilMs = 0L
        }
    }

    fun unsealPactIfAllowed(): Pair<Boolean, String> {
        synchronized(LOCK) {
            if (!canWeakenRulesNow()) {
                return Pair(false, "Impossible de lever le Pacte hors de la zone de déverrouillage.")
            }
            pactSealed = false
            unlockSessionUntilMs = 0L
            coolingOffRequestedAtMs = 0L
            nuclearDetoxUntilMs = 0L
            notifyPartnerIfConfigured("Pacte Geo-Vault levé", "Le mode auto-contrôle scellé a été désactivé.")
            return Pair(true, "Pacte levé. Vous pouvez librement modifier ou désactiver les règles.")
        }
    }

    fun forceEmergencyUnseal() {
        synchronized(LOCK) {
            pactSealed = false
            selfControlEnabled = false
            unlockSessionUntilMs = 0L
            coolingOffRequestedAtMs = 0L
            nuclearDetoxUntilMs = 0L
            dnsVpnEnabled = false
        }
    }

    fun verifyPartnerCodeAndUnlock(rawCode: String): Boolean {
        val stored = partnerCodeHash
        if (stored.isEmpty()) return false
        val clean = rawCode.trim().uppercase(Locale.US)
        if (sha256(clean) == stored) {
            grantUnlockSession("Clé Partenaire d'urgence", 15)
            return true
        }
        return false
    }

    fun verifyQrSecretAndUnlock(rawSecret: String): Boolean {
        val stored = qrSecretHash
        if (stored.isEmpty()) return false
        if (sha256(rawSecret.trim()) == stored) {
            grantUnlockSession("Badge QR / NFC physique", 15)
            return true
        }
        return false
    }

    fun requestCoolingOff(): Pair<Boolean, String> {
        synchronized(LOCK) {
            if (!pactSealed) return Pair(false, "Le Pacte n'est pas scellé.")
            if (coolingOffHours <= 0) {
                return Pair(false, "Le délai de réflexion n'était pas activé avant le scellement. Seule la zone GPS peut déverrouiller.")
            }
            val now = System.currentTimeMillis()
            if (coolingOffRequestedAtMs > 0L) {
                val remMs = (coolingOffRequestedAtMs + coolingOffHours * 3_600_000L - now).coerceAtLeast(0L)
                if (remMs == 0L) {
                    grantUnlockSession("Délai de réflexion écoulé", 15)
                    return Pair(true, "Délai de réflexion écoulé : session de modification ouverte (15 min).")
                }
                val remH = remMs / 3_600_000L
                val remM = (remMs % 3_600_000L) / 60_000L
                return Pair(true, "Délai déjà en cours : encore ${remH}h ${remM}m.")
            }
            coolingOffRequestedAtMs = now
            notifyPartnerIfConfigured(
                "Demande de déverrouillage différé (${coolingOffHours}h)",
                "Un compte à rebours de refroidissement de ${coolingOffHours}h a été lancé."
            )
            return Pair(true, "Délai de réflexion lancé (${coolingOffHours}h). Vous pourrez modifier le Pacte à la fin du compte à rebours.")
        }
    }

    fun cancelCoolingOff() {
        synchronized(LOCK) {
            coolingOffRequestedAtMs = 0L
        }
    }

    /* ==================== LOCAL DNS / VPN FIREWALL ==================== */

    var dnsVpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_DNS_VPN_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_DNS_VPN_ENABLED, value).commit() }

    fun getDnsCategories(): Set<String> = readStringSet(KEY_DNS_CATEGORIES)
    fun setDnsCategories(cats: Set<String>) = writeStringSet(KEY_DNS_CATEGORIES, cats)

    var dnsForceSafeSearch: Boolean
        get() = prefs.getBoolean(KEY_DNS_SAFESEARCH, true)
        set(value) { prefs.edit().putBoolean(KEY_DNS_SAFESEARCH, value).commit() }

    var dnsCoupleAppWeb: Boolean
        get() = prefs.getBoolean(KEY_DNS_COUPLE_APP_WEB, true)
        set(value) { prefs.edit().putBoolean(KEY_DNS_COUPLE_APP_WEB, value).commit() }

    fun getDnsBlacklist(): Set<String> = readStringSet(KEY_DNS_BLACKLIST)
    fun setDnsBlacklist(domains: Set<String>) = writeStringSet(KEY_DNS_BLACKLIST, domains.map { cleanDomain(it) }.filter { it.isNotEmpty() }.toSet())

    fun getDnsWhitelist(): Set<String> = readStringSet(KEY_DNS_WHITELIST)
    fun setDnsWhitelist(domains: Set<String>) = writeStringSet(KEY_DNS_WHITELIST, domains.map { cleanDomain(it) }.filter { it.isNotEmpty() }.toSet())

    fun getDnsBlockedKeywords(): Set<String> = readStringSet(KEY_DNS_KEYWORDS)
    fun setDnsBlockedKeywords(words: Set<String>) = writeStringSet(KEY_DNS_KEYWORDS, words.map { it.trim().lowercase(Locale.US) }.filter { it.length >= 3 }.toSet())

    /* ==================== DOPAMINE BREAKERS, QUOTAS & ANTI-BYPASS ==================== */

    /** Set of: "yt_shorts", "ig_reels", "snap_spotlight", "fb_reels", "x_foryou" */
    fun getFeedKillerTargets(): Set<String> = readStringSet(KEY_FEED_KILLERS)
    fun setFeedKillerTargets(targets: Set<String>) = writeStringSet(KEY_FEED_KILLERS, targets)

    fun getBlockedAppPackages(): Set<String> = readStringSet(KEY_BLOCKED_APPS)
    fun setBlockedAppPackages(pkgs: Set<String>) = writeStringSet(KEY_BLOCKED_APPS, pkgs)

    fun getSessionQuotas(): List<SessionQuotaRule> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_SESSION_QUOTAS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<SessionQuotaRule>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pkg = o.optString("packageName", "").trim()
                    val maxMin = o.optInt("maxSessionMin", 0)
                    val coolMin = o.optInt("cooldownMin", 15)
                    if (pkg.isNotEmpty() && maxMin > 0) {
                        out.add(SessionQuotaRule(pkg, o.optString("appName", pkg), maxMin.coerceIn(1, 180), coolMin.coerceIn(5, 240)))
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun setSessionQuotas(list: List<SessionQuotaRule>) {
        synchronized(LOCK) {
            val arr = JSONArray()
            for (r in list) {
                arr.put(JSONObject().apply {
                    put("packageName", r.packageName)
                    put("appName", r.appName)
                    put("maxSessionMin", r.maxSessionMin.coerceIn(1, 180))
                    put("cooldownMin", r.cooldownMin.coerceIn(5, 240))
                })
            }
            prefs.edit().putString(KEY_SESSION_QUOTAS, arr.toString()).commit()
        }
    }

    fun getSessionCooldownUntilMs(pkg: String): Long {
        val raw = prefs.getString(KEY_SESSION_COOLDOWNS, null) ?: return 0L
        return try {
            JSONObject(raw).optLong(pkg, 0L)
        } catch (_: Throwable) {
            0L
        }
    }

    fun setSessionCooldownUntilMs(pkg: String, untilMs: Long) {
        synchronized(LOCK) {
            val obj = try { JSONObject(prefs.getString(KEY_SESSION_COOLDOWNS, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            if (untilMs > System.currentTimeMillis()) obj.put(pkg, untilMs) else obj.remove(pkg)
            prefs.edit().putString(KEY_SESSION_COOLDOWNS, obj.toString()).commit()
        }
    }

    fun getMaxDailyOpens(): Map<String, Int> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_MAX_DAILY_OPENS, null) ?: return emptyMap()
            return try {
                val obj = JSONObject(raw)
                val map = LinkedHashMap<String, Int>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optInt(k, 0)
                    if (v > 0) map[k] = v.coerceIn(1, 100)
                }
                map
            } catch (_: Throwable) {
                emptyMap()
            }
        }
    }

    fun setMaxDailyOpens(map: Map<String, Int>) {
        synchronized(LOCK) {
            val obj = JSONObject()
            for ((k, v) in map) {
                if (k.isNotBlank() && v > 0) obj.put(k.trim(), v.coerceIn(1, 100))
            }
            prefs.edit().putString(KEY_MAX_DAILY_OPENS, obj.toString()).commit()
        }
    }

    fun getDailyOpensToday(pkg: String): Int {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_OPENS_TODAY, null) ?: return 0
            return try {
                val obj = JSONObject(raw)
                if (obj.optString("date") != todayKey()) return 0
                obj.optJSONObject("counts")?.optInt(pkg, 0) ?: 0
            } catch (_: Throwable) {
                0
            }
        }
    }

    fun incrementDailyOpen(pkg: String): Int {
        synchronized(LOCK) {
            val today = todayKey()
            val obj = try {
                val cur = JSONObject(prefs.getString(KEY_OPENS_TODAY, "{}") ?: "{}")
                if (cur.optString("date") == today) cur else JSONObject().put("date", today)
            } catch (_: Throwable) {
                JSONObject().put("date", today)
            }
            val counts = obj.optJSONObject("counts") ?: JSONObject()
            val next = counts.optInt(pkg, 0) + 1
            counts.put(pkg, next)
            obj.put("date", today)
            obj.put("counts", counts)
            prefs.edit().putString(KEY_OPENS_TODAY, obj.toString()).apply()
            return next
        }
    }

    /** Conscious Friction Pause (15..60 s breathing screen before opening tempting apps) */
    fun getFrictionPackages(): Set<String> = readStringSet(KEY_FRICTION_PKGS)
    fun setFrictionPackages(pkgs: Set<String>) = writeStringSet(KEY_FRICTION_PKGS, pkgs)

    var frictionDelaySec: Int
        get() = prefs.getInt(KEY_FRICTION_DELAY_SEC, 20).coerceIn(10, 60)
        set(value) { prefs.edit().putInt(KEY_FRICTION_DELAY_SEC, value.coerceIn(10, 60)).commit() }

    fun grantFrictionPass(pkg: String, minutes: Int = 10) {
        synchronized(LOCK) {
            val obj = try { JSONObject(prefs.getString(KEY_FRICTION_PASSES, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            obj.put(pkg, System.currentTimeMillis() + minutes * 60_000L)
            prefs.edit().putString(KEY_FRICTION_PASSES, obj.toString()).commit()
        }
    }

    fun hasActiveFrictionPass(pkg: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val raw = prefs.getString(KEY_FRICTION_PASSES, null) ?: return false
        return try {
            JSONObject(raw).optLong(pkg, 0L) > nowMs
        } catch (_: Throwable) {
            false
        }
    }

    /** Nuclear Detox Mode (locks everything except essential phone/maps/banking/health) */
    var nuclearDetoxUntilMs: Long
        get() = prefs.getLong(KEY_NUCLEAR_DETOX_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_NUCLEAR_DETOX_UNTIL, value).commit() }

    fun isNuclearDetoxActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        nuclearDetoxUntilMs > nowMs

    fun getNuclearWhitelistPackages(): Set<String> {
        val stored = readStringSet(KEY_NUCLEAR_WHITELIST)
        return if (stored.isEmpty()) DEFAULT_ESSENTIAL_PACKAGES else stored + DEFAULT_ESSENTIAL_PACKAGES
    }

    fun setNuclearWhitelistPackages(pkgs: Set<String>) = writeStringSet(KEY_NUCLEAR_WHITELIST, pkgs)

    /** Wind-Down Veil & Night Bedtime / Under-Blanket Detector */
    var windDownEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIND_DOWN_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_WIND_DOWN_ENABLED, value).commit() }

    var windDownMinutesBefore: Int
        get() = prefs.getInt(KEY_WIND_DOWN_MIN_BEFORE, 30).coerceIn(10, 90)
        set(value) { prefs.edit().putInt(KEY_WIND_DOWN_MIN_BEFORE, value.coerceIn(10, 90)).commit() }

    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_BEDTIME_ENABLED, value).commit() }

    var bedtimeStartMin: Int
        get() = prefs.getInt(KEY_BEDTIME_START_MIN, 22 * 60 + 30).coerceIn(0, 1439)
        set(value) { prefs.edit().putInt(KEY_BEDTIME_START_MIN, value.coerceIn(0, 1439)).commit() }

    var bedtimeEndMin: Int
        get() = prefs.getInt(KEY_BEDTIME_END_MIN, 7 * 60).coerceIn(0, 1439)
        set(value) { prefs.edit().putInt(KEY_BEDTIME_END_MIN, value.coerceIn(0, 1439)).commit() }

    var bedDetectorEnabled: Boolean
        get() = prefs.getBoolean(KEY_BED_DETECTOR_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_BED_DETECTOR_ENABLED, value).commit() }

    var bedDetectorLockUntilMs: Long
        get() = prefs.getLong(KEY_BED_DETECTOR_LOCK_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_BED_DETECTOR_LOCK_UNTIL, value).commit() }

    fun isBedtimeActiveNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!bedtimeEnabled) return false
        return isMinInRange(currentMinuteOfDay(nowMs), bedtimeStartMin, bedtimeEndMin)
    }

    fun getBedtimeEndTimestampMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val endCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, bedtimeEndMin / 60)
            set(Calendar.MINUTE, bedtimeEndMin % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (nowMin >= bedtimeEndMin) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return endCal.timeInMillis
    }

    fun isWindDownWindowNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!windDownEnabled || !bedtimeEnabled) return false
        if (isBedtimeActiveNow(nowMs)) return true
        val start = (bedtimeStartMin - windDownMinutesBefore + 1440) % 1440
        return isMinInRange(currentMinuteOfDay(nowMs), start, bedtimeStartMin)
    }

    /** New-App Auto-Quarantine & Bypass App Blocker */
    var quarantineNewApps: Boolean
        get() = prefs.getBoolean(KEY_QUARANTINE_NEW_APPS, true)
        set(value) { prefs.edit().putBoolean(KEY_QUARANTINE_NEW_APPS, value).commit() }

    fun getQuarantinedPackages(): Set<String> = readStringSet(KEY_QUARANTINED_PKGS)
    fun addQuarantinedPackage(pkg: String) {
        if (pkg.isBlank()) return
        val next = getQuarantinedPackages().toMutableSet()
        next.add(pkg.trim())
        writeStringSet(KEY_QUARANTINED_PKGS, next)
    }
    fun removeQuarantinedPackage(pkg: String) {
        val next = getQuarantinedPackages().toMutableSet()
        next.remove(pkg.trim())
        writeStringSet(KEY_QUARANTINED_PKGS, next)
    }

    var blockBypassApps: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_BYPASS_APPS, true)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_BYPASS_APPS, value).commit() }

    /* ==================== SCHEDULES, FOCUS GEOFENCES & EARN-TIME ==================== */

    fun getWeeklySchedules(): List<ScheduleSlot> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_WEEKLY_SCHEDULES, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<ScheduleSlot>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pkgs = ArrayList<String>()
                    val pa = o.optJSONArray("blockedPackages")
                    if (pa != null) {
                        for (j in 0 until pa.length()) {
                            val p = pa.optString(j, "").trim()
                            if (p.isNotEmpty()) pkgs.add(p)
                        }
                    }
                    out.add(
                        ScheduleSlot(
                            id = o.optString("id", "sch_$i"),
                            name = o.optString("name", "Créneau"),
                            startMin = o.optInt("startMin", 540).coerceIn(0, 1439),
                            endMin = o.optInt("endMin", 720).coerceIn(0, 1439),
                            daysMask = o.optInt("daysMask", 31) and 127,
                            mode = o.optString("mode", "block_social"),
                            blockedPackages = pkgs
                        )
                    )
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun setWeeklySchedules(list: List<ScheduleSlot>) {
        synchronized(LOCK) {
            val arr = JSONArray()
            for (s in list) {
                val pa = JSONArray()
                for (p in s.blockedPackages) pa.put(p)
                arr.put(JSONObject().apply {
                    put("id", s.id.ifBlank { "sch_${UUID.randomUUID().toString().take(6)}" })
                    put("name", s.name.take(40))
                    put("startMin", s.startMin.coerceIn(0, 1439))
                    put("endMin", s.endMin.coerceIn(0, 1439))
                    put("daysMask", s.daysMask and 127)
                    put("mode", s.mode)
                    put("blockedPackages", pa)
                })
            }
            prefs.edit().putString(KEY_WEEKLY_SCHEDULES, arr.toString()).commit()
        }
    }

    fun getActiveScheduleNow(nowMs: Long = System.currentTimeMillis()): ScheduleSlot? {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayBit = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (s in getWeeklySchedules()) {
            if ((s.daysMask and (1 shl dayBit)) != 0 && isMinInRange(nowMin, s.startMin, s.endMin)) {
                return s
            }
        }
        return null
    }

    fun getFocusGeofences(): List<FocusGeofenceRule> {
        synchronized(LOCK) {
            val raw = prefs.getString(KEY_FOCUS_GEOFENCES, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<FocusGeofenceRule>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        FocusGeofenceRule(
                            id = o.optString("id", "fg_$i"),
                            name = o.optString("name", "Zone Focus"),
                            latitude = o.optDouble("latitude", 0.0),
                            longitude = o.optDouble("longitude", 0.0),
                            radiusMeters = o.optInt("radiusMeters", 120).coerceIn(30, 1000),
                            mode = o.optString("mode", "deep_work"),
                            insideNow = o.optBoolean("insideNow", false)
                        )
                    )
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun setFocusGeofences(list: List<FocusGeofenceRule>) {
        synchronized(LOCK) {
            val arr = JSONArray()
            for (g in list) {
                arr.put(JSONObject().apply {
                    put("id", g.id.ifBlank { "fg_${UUID.randomUUID().toString().take(6)}" })
                    put("name", g.name.take(40))
                    put("latitude", g.latitude)
                    put("longitude", g.longitude)
                    put("radiusMeters", g.radiusMeters.coerceIn(30, 1000))
                    put("mode", g.mode)
                    put("insideNow", g.insideNow)
                })
            }
            prefs.edit().putString(KEY_FOCUS_GEOFENCES, arr.toString()).commit()
        }
    }

    fun getActiveFocusGeofenceMode(nowMs: Long = System.currentTimeMillis()): FocusGeofenceRule? {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        for (g in getFocusGeofences()) {
            if (!g.insideNow) continue
            if (g.mode == "deep_work") return g
            if (g.mode == "night_curfew" && (hour >= 22 || hour < 7)) return g
        }
        return null
    }

    /** Earn-Time (Steps & Educational Apps) + Morning Anti-Bed Challenge */
    var earnStepsTarget: Int
        get() = prefs.getInt(KEY_EARN_STEPS_TARGET, 0).coerceIn(0, 30_000)
        set(value) { prefs.edit().putInt(KEY_EARN_STEPS_TARGET, value.coerceIn(0, 30_000)).commit() }

    var earnStepsRewardMin: Int
        get() = prefs.getInt(KEY_EARN_STEPS_REWARD_MIN, 15).coerceIn(5, 120)
        set(value) { prefs.edit().putInt(KEY_EARN_STEPS_REWARD_MIN, value.coerceIn(5, 120)).commit() }

    var morningStepsRequired: Int
        get() = prefs.getInt(KEY_MORNING_STEPS_REQ, 0).coerceIn(0, 500)
        set(value) { prefs.edit().putInt(KEY_MORNING_STEPS_REQ, value.coerceIn(0, 500)).commit() }

    fun getStepsToday(): Int {
        if (prefs.getString(KEY_STEPS_DATE, "") != todayKey()) return 0
        return prefs.getInt(KEY_STEPS_TODAY, 0).coerceAtLeast(0)
    }

    fun setStepsToday(steps: Int) {
        synchronized(LOCK) {
            val today = todayKey()
            val clean = steps.coerceAtLeast(0)
            prefs.edit()
                .putString(KEY_STEPS_DATE, today)
                .putInt(KEY_STEPS_TODAY, clean)
                .apply()
            if (morningStepsRequired > 0 && clean >= morningStepsRequired) {
                prefs.edit().putString(KEY_MORNING_DONE_DATE, today).apply()
            }
        }
    }

    fun isMorningChallengePending(nowMs: Long = System.currentTimeMillis()): Boolean {
        val req = morningStepsRequired
        if (req <= 0 || !selfControlEnabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour !in 5..11) return false
        val today = todayKey()
        if (prefs.getString(KEY_MORNING_DONE_DATE, "") == today) return false
        return getStepsToday() < req
    }

    fun getEarnedBonusMinutesToday(): Int {
        val target = earnStepsTarget
        if (target <= 0) return 0
        val chunks = getStepsToday() / target
        return (chunks * earnStepsRewardMin).coerceAtMost(180)
    }

    /* ==================== SOBRIETY STATS & GAMIFICATION ==================== */

    var streakStartMs: Long
        get() = prefs.getLong(KEY_STREAK_START_MS, System.currentTimeMillis())
        set(value) { prefs.edit().putLong(KEY_STREAK_START_MS, value).commit() }

    var reclaimedMinutesTotal: Int
        get() = prefs.getInt(KEY_RECLAIMED_MIN_TOTAL, 0).coerceAtLeast(0)
        set(value) { prefs.edit().putInt(KEY_RECLAIMED_MIN_TOTAL, value.coerceAtLeast(0)).commit() }

    var abortedFrictionOpens: Int
        get() = prefs.getInt(KEY_ABORTED_FRICTION_OPENS, 0).coerceAtLeast(0)
        set(value) { prefs.edit().putInt(KEY_ABORTED_FRICTION_OPENS, value.coerceAtLeast(0)).commit() }

    fun recordReclaimedAction(pkg: String, reason: String, estimatedSavedMin: Int = 8) {
        synchronized(LOCK) {
            reclaimedMinutesTotal += estimatedSavedMin.coerceIn(1, 60)
            if (reason == "friction_aborted") {
                abortedFrictionOpens += 1
            }
            db.insertBlockAttempt(pkg, reason)
        }
    }

    /* ==================== 1-CLICK READY PROFILES (HARDEN-SAFE!) ==================== */

    /**
     * Applies one of 5 ready anti-addiction profiles.
     * Note: strictly ADDITIVE (union) if Pact is already sealed outside the zone, so it always obeys One-Way Rule!
     */
    fun applyPreset(presetId: String): String {
        synchronized(LOCK) {
            val cats = getDnsCategories().toMutableSet()
            val killers = getFeedKillerTargets().toMutableSet()
            val blocked = getBlockedAppPackages().toMutableSet()
            val friction = getFrictionPackages().toMutableSet()
            selfControlEnabled = true

            when (presetId) {
                "nofap" -> {
                    dnsVpnEnabled = true
                    dnsForceSafeSearch = true
                    dnsCoupleAppWeb = true
                    cats.addAll(listOf("adult", "ai_unfiltered", "apk_proxy"))
                    blockBypassApps = true
                    quarantineNewApps = true
                }

                "stop_shorts" -> {
                    dnsVpnEnabled = true
                    dnsCoupleAppWeb = true
                    killers.addAll(listOf("yt_shorts", "ig_reels", "snap_spotlight", "fb_reels", "x_foryou"))
                    blocked.addAll(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"))
                    friction.addAll(listOf("com.instagram.android", "com.google.android.youtube", "com.twitter.android", "com.reddit.frontpage"))
                    if (frictionDelaySec < 20) frictionDelaySec = 20
                }

                "deep_work" -> {
                    dnsVpnEnabled = true
                    dnsCoupleAppWeb = true
                    cats.addAll(listOf("social", "streaming", "gaming", "news"))
                    killers.addAll(listOf("yt_shorts", "ig_reels", "x_foryou"))
                    friction.addAll(listOf("com.instagram.android", "com.zhiliaoapp.musically", "com.google.android.youtube", "com.twitter.android"))
                    if (frictionDelaySec < 25) frictionDelaySec = 25
                    val curSch = getWeeklySchedules().toMutableList()
                    if (curSch.none { it.id == "preset_deep_work_am" }) {
                        curSch.add(ScheduleSlot("preset_deep_work_am", "Deep Work Matin", 8 * 60 + 30, 12 * 60, 31, "block_social", DEFAULT_SOCIAL_PACKAGES.toList()))
                    }
                    if (curSch.none { it.id == "preset_deep_work_pm" }) {
                        curSch.add(ScheduleSlot("preset_deep_work_pm", "Deep Work Après-midi", 14 * 60, 18 * 60, 31, "block_social", DEFAULT_SOCIAL_PACKAGES.toList()))
                    }
                    setWeeklySchedules(curSch)
                }

                "clean_sleep" -> {
                    bedtimeEnabled = true
                    bedtimeStartMin = 22 * 60 + 30
                    bedtimeEndMin = 7 * 60
                    windDownEnabled = true
                    windDownMinutesBefore = 30
                    bedDetectorEnabled = true
                    if (morningStepsRequired < 50) morningStepsRequired = 50
                }

                "monk_mode" -> {
                    dnsVpnEnabled = true
                    dnsForceSafeSearch = true
                    dnsCoupleAppWeb = true
                    cats.addAll(ALL_DNS_CATEGORIES)
                    killers.addAll(ALL_FEED_KILLERS)
                    blocked.addAll(DEFAULT_SOCIAL_PACKAGES)
                    friction.addAll(listOf("com.google.android.youtube", "com.reddit.frontpage"))
                    if (frictionDelaySec < 30) frictionDelaySec = 30
                    bedtimeEnabled = true
                    bedtimeStartMin = 22 * 60
                    bedtimeEndMin = 7 * 60
                    windDownEnabled = true
                    bedDetectorEnabled = true
                    blockBypassApps = true
                    quarantineNewApps = true
                    if (morningStepsRequired < 50) morningStepsRequired = 50
                }
            }
            setDnsCategories(cats)
            setFeedKillerTargets(killers)
            setBlockedAppPackages(blocked)
            setFrictionPackages(friction)
            return presetId
        }
    }

    /**
     * Safe config update that strictly obeys the One-Way Seal invariant!
     * Returns Pair(success, errorMessageOrNull).
     */
    fun applyConfigPatch(json: JSONObject): Pair<Boolean, String?> {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            val canWeaken = canWeakenRulesNow(now)

            if (json.has("selfControlEnabled")) {
                val v = json.optBoolean("selfControlEnabled", true)
                if (!v && !canWeaken) return Pair(false, "Impossible de désactiver l'Auto-Contrôle hors de votre zone carte.")
                selfControlEnabled = v
            }

            if (json.has("dnsVpnEnabled")) {
                val v = json.optBoolean("dnsVpnEnabled", false)
                if (!v && dnsVpnEnabled && !canWeaken) return Pair(false, "Le pare-feu DNS/VPN est scellé hors de votre zone carte.")
                dnsVpnEnabled = v
            }

            if (json.has("dnsCategories")) {
                val req = jsonArrayToSet(json.optJSONArray("dnsCategories"))
                val cur = getDnsCategories()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Vous pouvez ajouter des catégories DNS, mais pas en retirer hors de la zone carte.")
                }
                setDnsCategories(if (canWeaken) req else cur + req)
            }

            if (json.has("dnsForceSafeSearch")) {
                val v = json.optBoolean("dnsForceSafeSearch", true)
                if (!v && dnsForceSafeSearch && !canWeaken) return Pair(false, "SafeSearch est scellé hors zone.")
                dnsForceSafeSearch = v
            }

            if (json.has("dnsCoupleAppWeb")) {
                val v = json.optBoolean("dnsCoupleAppWeb", true)
                if (!v && dnsCoupleAppWeb && !canWeaken) return Pair(false, "Le couplage App↔Web est scellé hors zone.")
                dnsCoupleAppWeb = v
            }

            if (json.has("dnsBlacklist")) {
                val req = jsonArrayToSet(json.optJSONArray("dnsBlacklist")).map { cleanDomain(it) }.filter { it.isNotEmpty() }.toSet()
                val cur = getDnsBlacklist()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Impossible de retirer un domaine bloqué hors de votre zone carte.")
                }
                setDnsBlacklist(if (canWeaken) req else cur + req)
            }

            if (json.has("dnsWhitelist")) {
                val req = jsonArrayToSet(json.optJSONArray("dnsWhitelist")).map { cleanDomain(it) }.filter { it.isNotEmpty() }.toSet()
                val cur = getDnsWhitelist()
                if (!canWeaken && req.any { it !in cur }) {
                    return Pair(false, "Impossible d'ajouter une exception (liste blanche) hors de votre zone carte.")
                }
                setDnsWhitelist(req)
            }

            if (json.has("dnsBlockedKeywords")) {
                val req = jsonArrayToSet(json.optJSONArray("dnsBlockedKeywords"))
                val cur = getDnsBlockedKeywords()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Impossible de retirer un mot-clé bloqué hors zone.")
                }
                setDnsBlockedKeywords(if (canWeaken) req else cur + req)
            }

            if (json.has("feedKillerTargets")) {
                val req = jsonArrayToSet(json.optJSONArray("feedKillerTargets"))
                val cur = getFeedKillerTargets()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Impossible de désactiver un tueur de flux (Shorts/Reels) hors zone.")
                }
                setFeedKillerTargets(if (canWeaken) req else cur + req)
            }

            if (json.has("blockedAppPackages")) {
                val req = jsonArrayToSet(json.optJSONArray("blockedAppPackages"))
                val cur = getBlockedAppPackages()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Impossible de débloquer une application scellée hors de votre zone carte.")
                }
                setBlockedAppPackages(if (canWeaken) req else cur + req)
            }

            if (json.has("frictionPackages")) {
                val req = jsonArrayToSet(json.optJSONArray("frictionPackages"))
                val cur = getFrictionPackages()
                if (!canWeaken && !req.containsAll(cur)) {
                    return Pair(false, "Impossible de retirer la pause consciente hors zone.")
                }
                setFrictionPackages(if (canWeaken) req else cur + req)
            }

            if (json.has("frictionDelaySec")) {
                val v = json.optInt("frictionDelaySec", 20).coerceIn(10, 60)
                if (!canWeaken && v < frictionDelaySec) {
                    return Pair(false, "Impossible de raccourcir la pause consciente hors zone.")
                }
                frictionDelaySec = v
            }

            if (json.has("sessionQuotas")) {
                val arr = json.optJSONArray("sessionQuotas")
                val parsed = ArrayList<SessionQuotaRule>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pkg = o.optString("packageName", "").trim()
                        val maxM = o.optInt("maxSessionMin", 0)
                        val coolM = o.optInt("cooldownMin", 15)
                        if (pkg.isNotEmpty() && maxM > 0) {
                            parsed.add(SessionQuotaRule(pkg, o.optString("appName", pkg), maxM, coolM))
                        }
                    }
                }
                if (!canWeaken) {
                    val curMap = getSessionQuotas().associateBy { it.packageName }
                    val reqMap = parsed.associateBy { it.packageName }
                    for ((pkg, oldRule) in curMap) {
                        val nr = reqMap[pkg]
                        if (nr == null || nr.maxSessionMin > oldRule.maxSessionMin || nr.cooldownMin < oldRule.cooldownMin) {
                            return Pair(false, "Impossible d'assouplir un quota par session hors de la zone carte.")
                        }
                    }
                }
                setSessionQuotas(parsed)
            }

            if (json.has("maxDailyOpens")) {
                val obj = json.optJSONObject("maxDailyOpens")
                val reqMap = LinkedHashMap<String, Int>()
                if (obj != null) {
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = obj.optInt(k, 0)
                        if (v > 0) reqMap[k] = v.coerceIn(1, 100)
                    }
                }
                if (!canWeaken) {
                    for ((pkg, oldMax) in getMaxDailyOpens()) {
                        val nv = reqMap[pkg]
                        if (nv == null || nv > oldMax) {
                            return Pair(false, "Impossible d'augmenter le nombre d'ouvertures/jour hors zone.")
                        }
                    }
                }
                setMaxDailyOpens(reqMap)
            }

            if (json.has("bedtimeEnabled")) {
                val v = json.optBoolean("bedtimeEnabled", false)
                if (!v && bedtimeEnabled && !canWeaken) return Pair(false, "Le couvre-feu sommeil est scellé hors zone.")
                bedtimeEnabled = v
            }
            if (json.has("bedtimeStartMin") && canWeaken) bedtimeStartMin = json.optInt("bedtimeStartMin", bedtimeStartMin)
            if (json.has("bedtimeEndMin") && canWeaken) bedtimeEndMin = json.optInt("bedtimeEndMin", bedtimeEndMin)

            if (json.has("windDownEnabled")) {
                val v = json.optBoolean("windDownEnabled", false)
                if (!v && windDownEnabled && !canWeaken) return Pair(false, "Le voile coucher est scellé hors zone.")
                windDownEnabled = v
            }
            if (json.has("windDownMinutesBefore")) {
                val v = json.optInt("windDownMinutesBefore", 30)
                if (canWeaken || v >= windDownMinutesBefore) windDownMinutesBefore = v
            }

            if (json.has("bedDetectorEnabled")) {
                val v = json.optBoolean("bedDetectorEnabled", false)
                if (!v && bedDetectorEnabled && !canWeaken) return Pair(false, "Le détecteur sous-couette est scellé hors zone.")
                bedDetectorEnabled = v
            }

            if (json.has("quarantineNewApps")) {
                val v = json.optBoolean("quarantineNewApps", true)
                if (!v && quarantineNewApps && !canWeaken) return Pair(false, "La quarantaine des nouvelles apps est scellée hors zone.")
                quarantineNewApps = v
            }

            if (json.has("blockBypassApps")) {
                val v = json.optBoolean("blockBypassApps", true)
                if (!v && blockBypassApps && !canWeaken) return Pair(false, "Le blocage VPN tiers / Fake GPS est scellé hors zone.")
                blockBypassApps = v
            }

            if (json.has("releaseQuarantinedPackage")) {
                val pkg = json.optString("releaseQuarantinedPackage", "").trim()
                if (pkg.isNotEmpty()) {
                    if (!canWeaken) return Pair(false, "Impossible de libérer une nouvelle app de la quarantaine hors zone.")
                    removeQuarantinedPackage(pkg)
                }
            }

            if (json.has("weeklySchedules")) {
                val arr = json.optJSONArray("weeklySchedules")
                val parsed = ArrayList<ScheduleSlot>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pkgs = jsonArrayToSet(o.optJSONArray("blockedPackages")).toList()
                        parsed.add(
                            ScheduleSlot(
                                id = o.optString("id", "sch_${UUID.randomUUID().toString().take(6)}"),
                                name = o.optString("name", "Créneau"),
                                startMin = o.optInt("startMin", 540),
                                endMin = o.optInt("endMin", 720),
                                daysMask = o.optInt("daysMask", 31),
                                mode = o.optString("mode", "block_social"),
                                blockedPackages = pkgs
                            )
                        )
                    }
                }
                if (!canWeaken && parsed.size < getWeeklySchedules().size) {
                    return Pair(false, "Impossible de supprimer un créneau horaire hors zone.")
                }
                setWeeklySchedules(parsed)
            }

            if (json.has("focusGeofences")) {
                val arr = json.optJSONArray("focusGeofences")
                val parsed = ArrayList<FocusGeofenceRule>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        parsed.add(
                            FocusGeofenceRule(
                                id = o.optString("id", "fg_${UUID.randomUUID().toString().take(6)}"),
                                name = o.optString("name", "Zone Focus"),
                                latitude = o.optDouble("latitude", 0.0),
                                longitude = o.optDouble("longitude", 0.0),
                                radiusMeters = o.optInt("radiusMeters", 120),
                                mode = o.optString("mode", "deep_work"),
                                insideNow = o.optBoolean("insideNow", false)
                            )
                        )
                    }
                }
                if (!canWeaken && parsed.size < getFocusGeofences().size) {
                    return Pair(false, "Impossible de supprimer une zone Focus automatique hors zone.")
                }
                setFocusGeofences(parsed)
            }

            if (json.has("earnStepsTarget")) {
                val v = json.optInt("earnStepsTarget", 0)
                if (canWeaken || (earnStepsTarget == 0 && v > 0) || v >= earnStepsTarget) {
                    earnStepsTarget = v
                }
            }
            if (json.has("earnStepsRewardMin") && canWeaken) {
                earnStepsRewardMin = json.optInt("earnStepsRewardMin", 15)
            }
            if (json.has("morningStepsRequired")) {
                val v = json.optInt("morningStepsRequired", 0)
                if (!canWeaken && v < morningStepsRequired) {
                    return Pair(false, "Impossible de réduire le défi pas du matin hors zone.")
                }
                morningStepsRequired = v
            }
            if (json.has("partnerAlertTopic")) {
                val v = json.optString("partnerAlertTopic", "").trim()
                if (!canWeaken && v.isEmpty() && partnerAlertTopic.isNotEmpty()) {
                    return Pair(false, "Impossible de retirer le partenaire de responsabilité hors zone.")
                }
                partnerAlertTopic = v
            }
            if (json.has("nuclearWhitelistPackages") && canWeaken) {
                setNuclearWhitelistPackages(jsonArrayToSet(json.optJSONArray("nuclearWhitelistPackages")))
            }

            return Pair(true, null)
        }
    }

    fun startNuclearDetox(hours: Int): Pair<Boolean, String> {
        synchronized(LOCK) {
            val cleanHours = hours.coerceIn(1, 720)
            val now = System.currentTimeMillis()
            val target = now + cleanHours * 3_600_000L
            if (target < nuclearDetoxUntilMs && !canWeakenRulesNow(now)) {
                return Pair(false, "Impossible de raccourcir une cure Détox en cours hors zone.")
            }
            selfControlEnabled = true
            nuclearDetoxUntilMs = target
            notifyPartnerIfConfigured("Cure Détox Nucléaire lancée", "Blocage total activé pendant ${cleanHours}h (seuls les essentiels restent ouverts).")
            return Pair(true, "Cure Détox activée pour ${cleanHours}h !")
        }
    }

    fun stopNuclearDetoxIfAllowed(): Pair<Boolean, String> {
        synchronized(LOCK) {
            if (!canWeakenRulesNow()) {
                return Pair(false, "Cure Détox scellée : impossible de l'arrêter hors de la zone carte.")
            }
            nuclearDetoxUntilMs = 0L
            return Pair(true, "Cure Détox arrêtée.")
        }
    }

    fun notifyPartnerIfConfigured(title: String, detail: String) {
        val topic = partnerAlertTopic.trim()
        if (topic.isEmpty()) return
        try {
            ParentAlertPushHelper.sendCustomTopicAlertAsync(topic, "Pacte Geo-Vault • $title", detail)
        } catch (_: Throwable) {
        }
    }

    fun toJson(
        vpnActive: Boolean,
        geoSnapshot: GeoUnlockVerifier.GeoStatusSnapshot?
    ): JSONObject {
        val now = System.currentTimeMillis()
        val canWeaken = canWeakenRulesNow(now)
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val recentDnsArr = JSONArray()
        for (item in db.queryRecentDnsLogs(limit = 45, blockedOnly = false)) {
            recentDnsArr.put(JSONObject().apply {
                put("timestamp", item.timestamp)
                put("domain", item.domain)
                put("category", item.category)
                put("blocked", item.blocked)
            })
        }

        val heatmap = db.queryHourlyWeaknessHeatmap(days = 7)
        val heatmapArr = JSONArray()
        for (h in heatmap) heatmapArr.put(h)

        val sessionQuotasArr = JSONArray()
        for (sq in getSessionQuotas()) {
            sessionQuotasArr.put(JSONObject().apply {
                put("packageName", sq.packageName)
                put("appName", sq.appName)
                put("maxSessionMin", sq.maxSessionMin)
                put("cooldownMin", sq.cooldownMin)
                put("cooldownUntilMs", getSessionCooldownUntilMs(sq.packageName).takeIf { it > now } ?: 0L)
            })
        }

        val maxOpensObj = JSONObject()
        val todayOpensObj = JSONObject()
        for ((k, v) in getMaxDailyOpens()) {
            maxOpensObj.put(k, v)
            todayOpensObj.put(k, getDailyOpensToday(k))
        }

        val schedulesArr = JSONArray()
        for (s in getWeeklySchedules()) {
            schedulesArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("startMin", s.startMin)
                put("endMin", s.endMin)
                put("daysMask", s.daysMask)
                put("mode", s.mode)
                put("blockedPackages", JSONArray(s.blockedPackages))
            })
        }

        val focusZonesArr = JSONArray()
        for (g in getFocusGeofences()) {
            focusZonesArr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("latitude", g.latitude)
                put("longitude", g.longitude)
                put("radiusMeters", g.radiusMeters)
                put("mode", g.mode)
                put("insideNow", g.insideNow)
            })
        }

        val streakDays = ((now - streakStartMs).coerceAtLeast(0L) / 86_400_000L).toInt()

        return JSONObject().apply {
            put("selfControlEnabled", selfControlEnabled)
            put("pactSealed", pactSealed)
            put("pactSealedAtMs", pactSealedAtMs)
            put("canWeakenNow", canWeaken)
            put("unlockSessionUntilMs", unlockSessionUntilMs.takeIf { it > now } ?: 0L)
            put("hasUnlockZone", hasUnlockZoneConfigured())
            put("unlockZoneLat", unlockZoneLat)
            put("unlockZoneLng", unlockZoneLng)
            put("unlockZoneRadiusM", unlockZoneRadiusM)
            put("unlockZoneLabel", unlockZoneLabel)
            put("unlockDwellSec", unlockDwellSec)
            put("coolingOffHours", coolingOffHours)
            put("coolingOffRequestedAtMs", coolingOffRequestedAtMs)
            put("coolingOffMatured", isCoolingOffMatured(now))
            put("hasPartnerCode", partnerCodeHash.isNotEmpty())
            put("hasQrSecret", qrSecretHash.isNotEmpty())
            put("partnerAlertTopic", partnerAlertTopic)
            put("clockTamperDetected", clockTamperDetected)

            // Live Geo-Unlock presence snapshot
            if (geoSnapshot != null) {
                put("geoStatus", JSONObject().apply {
                    put("hasFix", geoSnapshot.hasFix)
                    put("currentLat", geoSnapshot.currentLat)
                    put("currentLng", geoSnapshot.currentLng)
                    put("accuracyM", geoSnapshot.accuracyM)
                    put("distanceM", geoSnapshot.distanceM)
                    put("insideZone", geoSnapshot.insideZone)
                    put("dwellSeconds", geoSnapshot.dwellSeconds)
                    put("requiredDwellSec", unlockDwellSec)
                    put("mockDetected", geoSnapshot.mockDetected)
                    put("mockReason", geoSnapshot.mockReason)
                    put("provider", geoSnapshot.provider)
                    put("updatedAt", geoSnapshot.updatedAt)
                })
            }

            // DNS/VPN Firewall
            put("dnsVpnEnabled", dnsVpnEnabled)
            put("dnsVpnRunning", vpnActive)
            put("dnsCategories", JSONArray(getDnsCategories().toList()))
            put("dnsForceSafeSearch", dnsForceSafeSearch)
            put("dnsCoupleAppWeb", dnsCoupleAppWeb)
            put("dnsBlacklist", JSONArray(getDnsBlacklist().toList()))
            put("dnsWhitelist", JSONArray(getDnsWhitelist().toList()))
            put("dnsBlockedKeywords", JSONArray(getDnsBlockedKeywords().toList()))
            put("dnsBlockedTodayCount", db.getBlockedDnsCountSince(todayStart))
            put("recentDnsLogs", recentDnsArr)

            // Dopamine Breakers & Quotas
            put("feedKillerTargets", JSONArray(getFeedKillerTargets().toList()))
            put("blockedAppPackages", JSONArray(getBlockedAppPackages().toList()))
            put("sessionQuotas", sessionQuotasArr)
            put("maxDailyOpens", maxOpensObj)
            put("todayOpens", todayOpensObj)
            put("frictionPackages", JSONArray(getFrictionPackages().toList()))
            put("frictionDelaySec", frictionDelaySec)
            put("nuclearDetoxUntilMs", nuclearDetoxUntilMs.takeIf { it > now } ?: 0L)
            put("nuclearWhitelistPackages", JSONArray(getNuclearWhitelistPackages().toList()))
            put("bedtimeEnabled", bedtimeEnabled)
            put("bedtimeStartMin", bedtimeStartMin)
            put("bedtimeEndMin", bedtimeEndMin)
            put("bedtimeActiveNow", isBedtimeActiveNow(now))
            put("windDownEnabled", windDownEnabled)
            put("windDownMinutesBefore", windDownMinutesBefore)
            put("windDownActiveNow", isWindDownWindowNow(now))
            put("bedDetectorEnabled", bedDetectorEnabled)
            put("quarantineNewApps", quarantineNewApps)
            put("quarantinedPackages", JSONArray(getQuarantinedPackages().toList()))
            put("blockBypassApps", blockBypassApps)

            // Schedules, Focus Zones, Earn-Time & Stats
            put("weeklySchedules", schedulesArr)
            put("activeScheduleName", getActiveScheduleNow(now)?.name ?: JSONObject.NULL)
            put("focusGeofences", focusZonesArr)
            put("activeFocusZoneName", getActiveFocusGeofenceMode(now)?.name ?: JSONObject.NULL)
            put("earnStepsTarget", earnStepsTarget)
            put("earnStepsRewardMin", earnStepsRewardMin)
            put("stepsToday", getStepsToday())
            put("earnedBonusMinutesToday", getEarnedBonusMinutesToday())
            put("morningStepsRequired", morningStepsRequired)
            put("morningChallengePending", isMorningChallengePending(now))

            put("streakStartMs", streakStartMs)
            put("streakDays", streakDays)
            put("reclaimedMinutesTotal", reclaimedMinutesTotal)
            put("abortedFrictionOpens", abortedFrictionOpens)
            put("totalBlockAttempts", db.getTotalBlockAttempts())
            put("weaknessHeatmap", heatmapArr)
        }
    }

    private fun readStringSet(key: String): Set<String> {
        synchronized(LOCK) {
            val raw = prefs.getString(key, null) ?: return emptySet()
            return try {
                val arr = JSONArray(raw)
                val out = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) out.add(s)
                }
                out
            } catch (_: Throwable) {
                emptySet()
            }
        }
    }

    private fun writeStringSet(key: String, values: Set<String>) {
        synchronized(LOCK) {
            val arr = JSONArray()
            for (v in values) {
                val c = v.trim()
                if (c.isNotEmpty()) arr.put(c)
            }
            prefs.edit().putString(key, arr.toString()).commit()
        }
    }

    private fun jsonArrayToSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun cleanDomain(raw: String): String {
        return raw.trim().lowercase(Locale.US)
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .substringBefore(":")
            .trim('.')
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentMinuteOfDay(nowMs: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun isMinInRange(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            nowMin in startMin until endMin
        } else {
            nowMin >= startMin || nowMin < endMin
        }
    }

    companion object {
        private val LOCK = Any()
        private const val PREFS_NAME = "sentinel_self_control_v28"

        private const val KEY_SELF_CONTROL_ENABLED = "sc_enabled"
        private const val KEY_PACT_SEALED = "sc_pact_sealed"
        private const val KEY_PACT_SEALED_AT = "sc_pact_sealed_at"
        private const val KEY_ZONE_LAT = "sc_zone_lat"
        private const val KEY_ZONE_LNG = "sc_zone_lng"
        private const val KEY_ZONE_RADIUS_M = "sc_zone_radius_m"
        private const val KEY_ZONE_LABEL = "sc_zone_label"
        private const val KEY_ZONE_DWELL_SEC = "sc_zone_dwell_sec"
        private const val KEY_UNLOCK_SESSION_UNTIL = "sc_unlock_until"

        private const val KEY_COOLING_OFF_HOURS = "sc_cool_hours"
        private const val KEY_COOLING_OFF_REQ_AT = "sc_cool_req_at"
        private const val KEY_PARTNER_CODE_HASH = "sc_partner_code_hash"
        private const val KEY_QR_SECRET_HASH = "sc_qr_secret_hash"
        private const val KEY_PARTNER_ALERT_TOPIC = "sc_partner_topic"

        private const val KEY_CLOCK_ANCHOR_WALL = "sc_clock_wall"
        private const val KEY_CLOCK_ANCHOR_MONO = "sc_clock_mono"
        private const val KEY_CLOCK_TAMPERED = "sc_clock_tampered"

        private const val KEY_DNS_VPN_ENABLED = "sc_dns_vpn_enabled"
        private const val KEY_DNS_CATEGORIES = "sc_dns_cats"
        private const val KEY_DNS_SAFESEARCH = "sc_dns_safesearch"
        private const val KEY_DNS_COUPLE_APP_WEB = "sc_dns_couple"
        private const val KEY_DNS_BLACKLIST = "sc_dns_blacklist"
        private const val KEY_DNS_WHITELIST = "sc_dns_whitelist"
        private const val KEY_DNS_KEYWORDS = "sc_dns_keywords"

        private const val KEY_FEED_KILLERS = "sc_feed_killers"
        private const val KEY_BLOCKED_APPS = "sc_blocked_apps"
        private const val KEY_SESSION_QUOTAS = "sc_session_quotas"
        private const val KEY_SESSION_COOLDOWNS = "sc_session_cooldowns"
        private const val KEY_MAX_DAILY_OPENS = "sc_max_opens"
        private const val KEY_OPENS_TODAY = "sc_opens_today"
        private const val KEY_FRICTION_PKGS = "sc_friction_pkgs"
        private const val KEY_FRICTION_DELAY_SEC = "sc_friction_delay"
        private const val KEY_FRICTION_PASSES = "sc_friction_passes"

        private const val KEY_NUCLEAR_DETOX_UNTIL = "sc_nuclear_until"
        private const val KEY_NUCLEAR_WHITELIST = "sc_nuclear_whitelist"
        private const val KEY_BEDTIME_ENABLED = "sc_bedtime_enabled"
        private const val KEY_BEDTIME_START_MIN = "sc_bedtime_start"
        private const val KEY_BEDTIME_END_MIN = "sc_bedtime_end"
        private const val KEY_WIND_DOWN_ENABLED = "sc_wind_down_enabled"
        private const val KEY_WIND_DOWN_MIN_BEFORE = "sc_wind_down_min"
        private const val KEY_BED_DETECTOR_ENABLED = "sc_bed_detector"
        private const val KEY_BED_DETECTOR_LOCK_UNTIL = "sc_bed_lock_until"

        private const val KEY_QUARANTINE_NEW_APPS = "sc_quarantine_new"
        private const val KEY_QUARANTINED_PKGS = "sc_quarantined_pkgs"
        private const val KEY_BLOCK_BYPASS_APPS = "sc_block_bypass"

        private const val KEY_WEEKLY_SCHEDULES = "sc_weekly_schedules"
        private const val KEY_FOCUS_GEOFENCES = "sc_focus_geofences"
        private const val KEY_EARN_STEPS_TARGET = "sc_earn_steps_target"
        private const val KEY_EARN_STEPS_REWARD_MIN = "sc_earn_steps_reward"
        private const val KEY_STEPS_DATE = "sc_steps_date"
        private const val KEY_STEPS_TODAY = "sc_steps_today"
        private const val KEY_MORNING_STEPS_REQ = "sc_morning_steps_req"
        private const val KEY_MORNING_DONE_DATE = "sc_morning_done_date"

        private const val KEY_STREAK_START_MS = "sc_streak_start"
        private const val KEY_RECLAIMED_MIN_TOTAL = "sc_reclaimed_min"
        private const val KEY_ABORTED_FRICTION_OPENS = "sc_aborted_friction"

        val ALL_DNS_CATEGORIES = setOf(
            "adult", "social", "streaming", "gaming",
            "gambling_crypto", "news", "ai_unfiltered", "apk_proxy"
        )

        val ALL_FEED_KILLERS = setOf(
            "yt_shorts", "ig_reels", "snap_spotlight", "fb_reels", "x_foryou"
        )

        val DEFAULT_SOCIAL_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.instagram.android",
            "com.snapchat.android",
            "com.twitter.android",
            "com.facebook.katana",
            "com.reddit.frontpage"
        )

        val DEFAULT_ESSENTIAL_PACKAGES = setOf(
            "com.sentinelcam.app",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.messaging",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.google.android.apps.maps",
            "com.waze",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.google.android.calendar",
            "com.samsung.android.calendar",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.app.contacts",
            "com.whatsapp"
        )

        val KNOWN_BYPASS_PACKAGES = setOf(
            // Fake GPS / Mock Location apps
            "com.lexa.fakegps",
            "com.incorporateapps.fakegps.fre",
            "com.incorporateapps.fakegps_route",
            "com.blogspot.newapphorizons.fakegps",
            "com.gsmartstudio.fakegps",
            "com.theappninjas.fakegpsjoystick",
            "com.evezzon.fakegps",
            "com.rosteam.gpsemulator",
            // 3rd-party VPN / Proxy apps
            "com.nordvpn.android",
            "com.expressvpn.vpn",
            "ch.protonvpn.android",
            "com.surfshark.vpnclient.android",
            "com.cloudflare.onedotonedotonedotone",
            "com.windscribe.vpn",
            "net.mullvad.mullvadvpn",
            "com.ixolit.ipvanish",
            "com.cyberghostvpn.android",
            "com.privateinternetaccess.android",
            "org.torproject.torbrowser",
            "org.torproject.android",
            "com.psiphon3",
            "com.fast.free.unblock.secure.vpn",
            "free.vpn.unblock.proxy.turbovpn",
            // App Cloners / Parallel Space / Calculator Vaults
            "com.lbe.parallel.intl",
            "com.excean.dualspace",
            "com.applisto.appcloner",
            "com.ludashi.dualspace",
            "com.hideitpro",
            "com.domobile.applockwatcher",
            "com.calculator.vault"
        )

        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
            return sb.toString()
        }
    }
}
