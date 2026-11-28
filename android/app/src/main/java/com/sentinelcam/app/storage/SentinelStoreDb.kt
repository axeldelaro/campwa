package com.sentinelcam.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build

/**
 * #24: High-Speed Indexed SQLite WAL Database (Device-Protected Storage Safe):
 * - Replaces heavy XML SharedPreferences JSON rewrites for security events, browser alerts,
 *   and audit logs -> zero UI jank, crash-resilient WAL mode, automatic 30-day rolling purge!
 */
class SentinelStoreDb private constructor(context: Context) : SQLiteOpenHelper(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.applicationContext.createDeviceProtectedStorageContext()
    } else {
        context.applicationContext
    },
    DB_NAME,
    null,
    DB_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS security_events (
                id TEXT PRIMARY KEY,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                photo_b64 TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sec_events_ts ON security_events(ts DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_logs_ts ON audit_logs(ts DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dns_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                domain TEXT NOT NULL,
                category TEXT NOT NULL,
                blocked INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dns_logs_ts ON dns_logs(ts DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS block_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                pkg TEXT NOT NULL,
                reason TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_block_attempts_ts ON block_attempts(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    data class DnsLogItem(
        val timestamp: Long,
        val domain: String,
        val category: String,
        val blocked: Boolean
    )

    fun insertDnsLog(domain: String, category: String, blocked: Boolean, timestamp: Long = System.currentTimeMillis()) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("ts", timestamp)
                put("domain", domain.take(180))
                put("category", category.take(40))
                put("blocked", if (blocked) 1 else 0)
            }
            db.insert("dns_logs", null, cv)
            // Keep latest 400 DNS rows (~14 days rolling)
            val cutoff = timestamp - 14L * 24L * 3600L * 1000L
            db.delete("dns_logs", "ts < ?", arrayOf(cutoff.toString()))
            db.execSQL(
                """
                DELETE FROM dns_logs WHERE id NOT IN (
                    SELECT id FROM dns_logs ORDER BY ts DESC LIMIT 400
                )
                """.trimIndent()
            )
        } catch (_: Throwable) {
        }
    }

    fun queryRecentDnsLogs(limit: Int = 50, blockedOnly: Boolean = false): List<DnsLogItem> {
        val out = ArrayList<DnsLogItem>()
        try {
            val sql = if (blockedOnly) {
                "SELECT ts, domain, category, blocked FROM dns_logs WHERE blocked = 1 ORDER BY ts DESC LIMIT ?"
            } else {
                "SELECT ts, domain, category, blocked FROM dns_logs ORDER BY ts DESC LIMIT ?"
            }
            readableDatabase.rawQuery(sql, arrayOf(limit.coerceIn(1, 200).toString())).use { c ->
                while (c.moveToNext()) {
                    out.add(DnsLogItem(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun getBlockedDnsCountSince(sinceMs: Long): Int {
        return try {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM dns_logs WHERE blocked = 1 AND ts >= ?",
                arrayOf(sinceMs.toString())
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    fun insertBlockAttempt(pkg: String, reason: String, timestamp: Long = System.currentTimeMillis()) {
        try {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("ts", timestamp)
                put("hour", hour)
                put("pkg", pkg.take(140))
                put("reason", reason.take(48))
            }
            db.insert("block_attempts", null, cv)
            val cutoff = timestamp - 30L * 24L * 3600L * 1000L
            db.delete("block_attempts", "ts < ?", arrayOf(cutoff.toString()))
        } catch (_: Throwable) {
        }
    }

    fun queryHourlyWeaknessHeatmap(days: Int = 7): IntArray {
        val counts = IntArray(24)
        try {
            val cutoff = System.currentTimeMillis() - days.coerceIn(1, 30) * 24L * 3600L * 1000L
            readableDatabase.rawQuery(
                "SELECT hour, COUNT(*) FROM block_attempts WHERE ts >= ? GROUP BY hour",
                arrayOf(cutoff.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val h = c.getInt(0)
                    if (h in 0..23) counts[h] = c.getInt(1)
                }
            }
        } catch (_: Throwable) {
        }
        return counts
    }

    fun getTotalBlockAttempts(): Int {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM block_attempts", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    fun insertSecurityEvent(item: SecurityEventItem) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("id", item.id)
                put("ts", item.timestamp)
                put("kind", item.kind)
                put("title", item.title)
                put("detail", item.detail)
                if (item.photoBase64 != null) put("photo_b64", item.photoBase64) else putNull("photo_b64")
            }
            db.insertWithOnConflict("security_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            // Rolling purge: keep newest 120 events & drop events older than 30 days; retain photo blobs on latest 6
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            db.delete("security_events", "ts < ?", arrayOf(cutoff.toString()))
            db.execSQL(
                """
                DELETE FROM security_events WHERE id NOT IN (
                    SELECT id FROM security_events ORDER BY ts DESC LIMIT 120
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE security_events SET photo_b64 = NULL
                WHERE photo_b64 IS NOT NULL AND id NOT IN (
                    SELECT id FROM security_events WHERE photo_b64 IS NOT NULL ORDER BY ts DESC LIMIT 6
                )
                """.trimIndent()
            )
        } catch (_: Throwable) {
        }
    }

    fun querySecurityEvents(limit: Int = 50): List<SecurityEventItem> {
        val out = ArrayList<SecurityEventItem>()
        try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT id, ts, kind, title, detail, photo_b64 FROM security_events ORDER BY ts DESC LIMIT ?",
                arrayOf(limit.coerceIn(1, 150).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        SecurityEventItem(
                            id = c.getString(0),
                            timestamp = c.getLong(1),
                            kind = c.getString(2),
                            title = c.getString(3),
                            detail = c.getString(4),
                            photoBase64 = if (c.isNull(5)) null else c.getString(5)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun clearSecurityEvents() {
        try {
            writableDatabase.delete("security_events", null, null)
        } catch (_: Throwable) {
        }
    }

    fun insertAuditLog(timestamp: Long, level: String, message: String) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("ts", timestamp)
                put("level", level)
                put("message", message.take(260))
            }
            db.insert("audit_logs", null, cv)
            db.execSQL(
                """
                DELETE FROM audit_logs WHERE id NOT IN (
                    SELECT id FROM audit_logs ORDER BY ts DESC LIMIT 80
                )
                """.trimIndent()
            )
        } catch (_: Throwable) {
        }
    }

    fun queryAuditLogs(limit: Int = 40): List<AuditLogItem> {
        val out = ArrayList<AuditLogItem>()
        try {
            readableDatabase.rawQuery(
                "SELECT ts, level, message FROM audit_logs ORDER BY ts DESC LIMIT ?",
                arrayOf(limit.coerceIn(1, 100).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(AuditLogItem(c.getLong(0), c.getString(1), c.getString(2)))
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun clearAllOnUnpair() {
        try {
            val db = writableDatabase
            db.delete("security_events", null, null)
            db.delete("audit_logs", null, null)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val DB_NAME = "sentinel_wal_store_v27.db"
        private const val DB_VERSION = 2
        private const val RETENTION_MS = 30L * 24L * 3600L * 1000L

        @Volatile
        private var instance: SentinelStoreDb? = null

        fun get(context: Context): SentinelStoreDb {
            return instance ?: synchronized(this) {
                instance ?: SentinelStoreDb(context).also { instance = it }
            }
        }
    }
}
