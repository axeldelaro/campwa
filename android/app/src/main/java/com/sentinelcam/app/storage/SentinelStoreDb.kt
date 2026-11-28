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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
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
        private const val DB_VERSION = 1
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
