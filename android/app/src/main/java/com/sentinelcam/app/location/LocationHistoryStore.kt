package com.sentinelcam.app.location

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class GpsPointRecord(
    val id: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val bearing: Float,
    val altitude: Double,
    val provider: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val dwellDurationMs: Long
) {
    /**
     * Compact wire tuple: [ts, lat, lng, accInt, speedKmh1Decimal, batteryPct, dwellSec, providerCode]
     * Saves ~72% bandwidth over verbose JSON objects when syncing thousands of offline points over E2EE.
     */
    fun toCompactJsonArray(): JSONArray {
        val providerCode = when (provider.lowercase()) {
            "gps" -> "g"
            "network" -> "n"
            "fused" -> "f"
            else -> "p"
        }
        return JSONArray().apply {
            put(timestamp)
            put(Math.round(latitude * 1_000_000.0) / 1_000_000.0)
            put(Math.round(longitude * 1_000_000.0) / 1_000_000.0)
            put(accuracyMeters.toInt().coerceAtLeast(0))
            put(Math.round(speedMps * 36.0) / 10.0) // km/h with 1 decimal
            put(batteryLevel)
            put((dwellDurationMs / 1000L).coerceAtLeast(0L))
            put(providerCode)
        }
    }

    fun toFullLocationJson(): JSONObject {
        return JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracyMeters", accuracyMeters.toInt().coerceAtLeast(0))
            put("speedKmh", Math.round(speedMps * 36.0) / 10.0)
            put("bearing", bearing.toInt())
            put("altitude", Math.round(altitude))
            put("provider", provider)
            put("batteryLevel", batteryLevel)
            put("dwellSeconds", (dwellDurationMs / 1000L).coerceAtLeast(0L))
            put("timestamp", timestamp)
        }
    }
}

data class LocationStoreStats(
    val totalPoints: Int,
    val todayPoints: Int,
    val oldestTimestamp: Long?,
    val latestTimestamp: Long?
)

data class LocationHistoryQueryResult(
    val points: List<GpsPointRecord>,
    val rawPointCount: Int,
    val totalDistanceMeters: Double,
    val maxSpeedKmh: Double,
    val stopsCount: Int,
    val fromTs: Long,
    val toTs: Long
)

/**
 * Offline-first SQLite GPS Trail Store.
 * Persists high-precision location points locally even with ZERO network or MQTT broker availability.
 * Automatically maintains rolling 30-day / 25,000-point retention (< 3 MB on disk).
 */
class LocationHistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_POINTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                acc REAL NOT NULL,
                spd REAL NOT NULL,
                brg REAL NOT NULL,
                alt REAL NOT NULL,
                prv TEXT NOT NULL,
                bat INTEGER NOT NULL,
                chg INTEGER NOT NULL,
                dwell INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gps_ts ON $TABLE_POINTS(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    @Synchronized
    fun insertPoint(
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        speedMps: Float,
        bearing: Float,
        altitude: Double,
        provider: String,
        batteryLevel: Int,
        isCharging: Boolean,
        dwellDurationMs: Long = 0L
    ): Long {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("ts", timestamp)
                put("lat", latitude)
                put("lng", longitude)
                put("acc", accuracyMeters)
                put("spd", speedMps)
                put("brg", bearing)
                put("alt", altitude)
                put("prv", provider)
                put("bat", batteryLevel)
                put("chg", if (isCharging) 1 else 0)
                put("dwell", dwellDurationMs)
            }
            val rowId = db.insert(TABLE_POINTS, null, cv)
            insertCounter++
            if (insertCounter % 40 == 0) {
                pruneOldRecordsLocked(db)
            }
            rowId
        } catch (_: Throwable) {
            -1L
        }
    }

    @Synchronized
    fun updatePointDwell(id: Long, dwellDurationMs: Long, batteryLevel: Int) {
        if (id <= 0L) return
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("dwell", dwellDurationMs.coerceAtLeast(0L))
                put("bat", batteryLevel)
            }
            db.update(TABLE_POINTS, cv, "id = ?", arrayOf(id.toString()))
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun getLatestPoint(): GpsPointRecord? {
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT id, ts, lat, lng, acc, spd, brg, alt, prv, bat, chg, dwell FROM $TABLE_POINTS ORDER BY ts DESC LIMIT 1",
                null
            ).use { c ->
                if (c.moveToFirst()) readCursorRow(c) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun getStats(): LocationStoreStats {
        return try {
            val db = readableDatabase
            var total = 0
            var oldest: Long? = null
            var latest: Long? = null

            db.rawQuery("SELECT COUNT(*), MIN(ts), MAX(ts) FROM $TABLE_POINTS", null).use { c ->
                if (c.moveToFirst()) {
                    total = c.getInt(0)
                    if (!c.isNull(1)) oldest = c.getLong(1)
                    if (!c.isNull(2)) latest = c.getLong(2)
                }
            }

            val startOfDay = computeStartOfTodayMs()
            var today = 0
            db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_POINTS WHERE ts >= ?",
                arrayOf(startOfDay.toString())
            ).use { c ->
                if (c.moveToFirst()) today = c.getInt(0)
            }

            LocationStoreStats(
                totalPoints = total,
                todayPoints = today,
                oldestTimestamp = oldest,
                latestTimestamp = latest
            )
        } catch (_: Throwable) {
            LocationStoreStats(0, 0, null, null)
        }
    }

    @Synchronized
    fun queryRange(
        fromTs: Long,
        toTs: Long,
        maxReturnedPoints: Int = 1800
    ): LocationHistoryQueryResult {
        val rawList = ArrayList<GpsPointRecord>()
        try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT id, ts, lat, lng, acc, spd, brg, alt, prv, bat, chg, dwell
                FROM $TABLE_POINTS
                WHERE ts >= ? AND ts <= ?
                ORDER BY ts ASC
                """.trimIndent(),
                arrayOf(fromTs.toString(), toTs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    rawList.add(readCursorRow(c))
                }
            }
        } catch (_: Throwable) {
        }

        var totalDistance = 0.0
        var maxSpeedKmh = 0.0
        var stopsCount = 0
        val distResult = FloatArray(1)

        for (i in rawList.indices) {
            val p = rawList[i]
            val kmh = p.speedMps * 3.6
            if (kmh > maxSpeedKmh && kmh < 320.0) {
                maxSpeedKmh = kmh
            }
            if (p.dwellDurationMs >= 120_000L) {
                stopsCount++
            }
            if (i > 0) {
                val prev = rawList[i - 1]
                Location.distanceBetween(prev.latitude, prev.longitude, p.latitude, p.longitude, distResult)
                val seg = distResult[0].toDouble()
                val dtSec = ((p.timestamp - prev.timestamp) / 1000.0).coerceAtLeast(1.0)
                // Guard against GPS glitch jumps (>120 m/s = 432 km/h)
                if (seg >= 3.0 && (seg / dtSec) < 120.0) {
                    totalDistance += seg
                }
            }
        }

        val finalPoints = if (rawList.size <= maxReturnedPoints) {
            rawList
        } else {
            smartDownsamplePreservingStopsAndCorners(rawList, maxReturnedPoints)
        }

        return LocationHistoryQueryResult(
            points = finalPoints,
            rawPointCount = rawList.size,
            totalDistanceMeters = totalDistance,
            maxSpeedKmh = Math.round(maxSpeedKmh * 10.0) / 10.0,
            stopsCount = stopsCount,
            fromTs = fromTs,
            toTs = toTs
        )
    }

    @Synchronized
    fun clearAll() {
        try {
            writableDatabase.delete(TABLE_POINTS, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun pruneOldRecordsLocked(db: SQLiteDatabase) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            db.delete(TABLE_POINTS, "ts < ?", arrayOf(cutoff.toString()))

            db.rawQuery("SELECT COUNT(*) FROM $TABLE_POINTS", null).use { c ->
                if (c.moveToFirst()) {
                    val count = c.getInt(0)
                    if (count > MAX_STORED_POINTS) {
                        val toDelete = count - MAX_STORED_POINTS
                        db.execSQL(
                            "DELETE FROM $TABLE_POINTS WHERE id IN (SELECT id FROM $TABLE_POINTS ORDER BY ts ASC LIMIT $toDelete)"
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun smartDownsamplePreservingStopsAndCorners(
        src: List<GpsPointRecord>,
        limit: Int
    ): List<GpsPointRecord> {
        if (src.size <= limit) return src
        val kept = ArrayList<GpsPointRecord>(limit)
        val step = (src.size.toDouble() / limit.toDouble()).coerceAtLeast(1.0)
        var nextScheduled = 0.0

        for (i in src.indices) {
            val p = src[i]
            val isEdge = i == 0 || i == src.lastIndex
            val isStop = p.dwellDurationMs >= 120_000L
            val isSharpTurn = if (i > 0 && i < src.lastIndex) {
                val delta = abs(p.bearing - src[i - 1].bearing)
                val normalizedDelta = if (delta > 180f) 360f - delta else delta
                normalizedDelta >= 35f && p.speedMps > 1.5f
            } else {
                false
            }

            if (isEdge || isStop || isSharpTurn || i >= nextScheduled.toInt()) {
                kept.add(p)
                if (i >= nextScheduled.toInt()) {
                    nextScheduled += step
                }
            }
        }

        // If still over limit, thin non-stop middle points evenly
        if (kept.size <= limit) return kept
        val finalOut = ArrayList<GpsPointRecord>(limit)
        val ratio = kept.size.toDouble() / limit.toDouble()
        for (k in 0 until limit) {
            val idx = (k * ratio).toInt().coerceIn(0, kept.lastIndex)
            finalOut.add(kept[idx])
        }
        if (finalOut.last().id != src.last().id) {
            finalOut[finalOut.lastIndex] = src.last()
        }
        return finalOut
    }

    private fun readCursorRow(c: android.database.Cursor): GpsPointRecord {
        return GpsPointRecord(
            id = c.getLong(0),
            timestamp = c.getLong(1),
            latitude = c.getDouble(2),
            longitude = c.getDouble(3),
            accuracyMeters = c.getFloat(4),
            speedMps = c.getFloat(5),
            bearing = c.getFloat(6),
            altitude = c.getDouble(7),
            provider = c.getString(8) ?: "gps",
            batteryLevel = c.getInt(9),
            isCharging = c.getInt(10) == 1,
            dwellDurationMs = c.getLong(11)
        )
    }

    private fun computeStartOfTodayMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val DB_NAME = "sentinelcam_gps_history.db"
        private const val DB_VERSION = 1
        private const val TABLE_POINTS = "gps_points"
        private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
        private const val MAX_STORED_POINTS = 25_000
        private var insertCounter = 0
    }
}
