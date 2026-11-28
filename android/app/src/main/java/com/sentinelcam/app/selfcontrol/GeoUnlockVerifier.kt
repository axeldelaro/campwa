package com.sentinelcam.app.selfcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sentinelcam.app.storage.PortalPreferences
import kotlin.math.roundToInt

/**
 * SentinelCam v28.0 — Anti-Fake-GPS Geo-Unlock Verifier & Reverse Auto-Focus Geofence Engine.
 *
 * Security checks before granting a 15-min Pact Modification Session:
 * 1. Native Android mock-location rejection (`Location.isMock` / `isFromMockProvider`).
 * 2. Requires authentic `GPS_PROVIDER` satellite fix with accuracy <= 65 m.
 * 3. Anti-teleport velocity check (> 220 km/h sudden jump > 600 m resets dwell & flags spoof attempt).
 * 4. Continuous real dwell inside the sealed map zone radius for `unlockDwellSec` seconds.
 */
object GeoUnlockVerifier {

    data class GeoStatusSnapshot(
        val hasFix: Boolean = false,
        val currentLat: Double = 0.0,
        val currentLng: Double = 0.0,
        val accuracyM: Int = 0,
        val distanceM: Int = -1,
        val insideZone: Boolean = false,
        val dwellSeconds: Int = 0,
        val mockDetected: Boolean = false,
        val mockReason: String = "",
        val provider: String = "",
        val updatedAt: Long = 0L
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var activeListener: LocationListener? = null
    private var onUpdateCallback: (() -> Unit)? = null

    @Volatile
    var latestSnapshot: GeoStatusSnapshot = GeoStatusSnapshot()
        private set

    private var prevValidLocation: Location? = null
    private var prevValidTimeMs: Long = 0L
    private var dwellStartedAtMs: Long = 0L

    private val autoStopRunnable = Runnable {
        stopActiveVerification()
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startActiveVerification(context: Context, onUpdate: () -> Unit) {
        val appCtx = context.applicationContext
        onUpdateCallback = onUpdate
        if (!hasLocationPermission(appCtx)) {
            latestSnapshot = GeoStatusSnapshot(
                hasFix = false,
                mockReason = "Permission GPS (Localisation précise) requise.",
                updatedAt = System.currentTimeMillis()
            )
            onUpdate()
            return
        }

        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        // Evaluate last known immediately for quick UI feedback (never grants dwell without fresh GPS!)
        try {
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = when {
                lastGps != null && (lastNet == null || lastGps.time >= lastNet.time - 30_000L) -> lastGps
                else -> lastNet
            }
            if (best != null) {
                evaluateLocation(appCtx, best, allowDwellAccumulation = false)
            }
        } catch (_: Throwable) {
        }

        stopActiveListenerOnly()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                evaluateLocation(appCtx, location, allowDwellAccumulation = true)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = listener

        mainHandler.post {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
                }
            } catch (_: Throwable) {
            }
        }

        // Auto-stop high-accuracy listener after 6 minutes to preserve battery
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, 6 * 60_000L)
    }

    fun stopActiveVerification() {
        mainHandler.removeCallbacks(autoStopRunnable)
        stopActiveListenerOnly()
        onUpdateCallback = null
    }

    private fun stopActiveListenerOnly() {
        val listener = activeListener ?: return
        activeListener = null
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * Called both by active verification AND passively by `SmartLocationTracker` fixes!
     * Evaluates Geo-Unlock presence + Reverse Auto-Focus Geofences.
     */
    fun onExternalLocationFix(context: Context, location: Location) {
        evaluateLocation(context.applicationContext, location, allowDwellAccumulation = true)
    }

    private fun evaluateLocation(context: Context, loc: Location, allowDwellAccumulation: Boolean) {
        val now = System.currentTimeMillis()
        val sc = SelfControlStore(context)
        sc.checkAndAnchorMonotonicClock()

        // 1. Check Native Android Mock Location flag
        val isMockFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }

        var mockDetected = isMockFlag
        var mockReason = if (isMockFlag) "Position simulée (Fake GPS) rejetée par Android !" else ""

        // 2. Check Anti-Teleport velocity anomaly
        val prev = prevValidLocation
        if (!mockDetected && prev != null && prevValidTimeMs > 0L) {
            val dtSec = ((now - prevValidTimeMs) / 1000.0)
            if (dtSec in 1.0..120.0) {
                val jumpedM = prev.distanceTo(loc).toDouble()
                val speedKmh = (jumpedM / dtSec) * 3.6
                if (jumpedM > 600.0 && speedKmh > 220.0) {
                    mockDetected = true
                    mockReason = "Téléportation GPS impossible détectée (${speedKmh.roundToInt()} km/h) !"
                }
            }
        }

        if (mockDetected) {
            dwellStartedAtMs = 0L
            try {
                val prefs = PortalPreferences(context)
                prefs.appendSecurityEvent(
                    kind = "bypass",
                    title = "Tentative Fake GPS bloquée",
                    detail = mockReason
                )
            } catch (_: Throwable) {
            }
            sc.notifyPartnerIfConfigured("Tentative Fake GPS détectée", mockReason)
        } else {
            prevValidLocation = loc
            prevValidTimeMs = now
        }

        // 3. Evaluate Reverse Auto-Focus Geofences (Work/Library/Home-Night)
        if (!mockDetected) {
            updateFocusGeofences(sc, loc)
        }

        // 4. Evaluate distance & dwell against Sealed Geo-Unlock Zone
        var distM = -1
        var inside = false
        var dwellSec = 0

        if (sc.hasUnlockZoneConfigured()) {
            val results = FloatArray(1)
            Location.distanceBetween(
                loc.latitude, loc.longitude,
                sc.unlockZoneLat, sc.unlockZoneLng,
                results
            )
            distM = results[0].roundToInt().coerceAtLeast(0)
            val radius = sc.unlockZoneRadiusM
            val isGpsSatellite = loc.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
            val goodAccuracy = loc.accuracy <= 65f

            inside = !mockDetected && distM <= radius

            if (inside && allowDwellAccumulation && sc.pactSealed && !sc.isUnlockSessionActive(now)) {
                if (!isGpsSatellite || !goodAccuracy) {
                    mockReason = if (!isGpsSatellite) {
                        "Dans la zone — en attente d’un signal GPS satellite authentique…"
                    } else {
                        "Précision GPS insuffisante (±${loc.accuracy.roundToInt()} m, requis ≤ 65 m)."
                    }
                } else {
                    if (dwellStartedAtMs == 0L) {
                        dwellStartedAtMs = now
                    }
                    dwellSec = ((now - dwellStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
                    if (dwellSec >= sc.unlockDwellSec) {
                        sc.grantUnlockSession("Présence GPS vérifiée (${sc.unlockZoneLabel})", 15)
                        dwellStartedAtMs = 0L
                    }
                }
            } else if (!inside) {
                dwellStartedAtMs = 0L
                // If user physically left the zone by > radius + 50m, don't reset partner/cooling unlocks, only clear dwell
            }
        }

        latestSnapshot = GeoStatusSnapshot(
            hasFix = true,
            currentLat = loc.latitude,
            currentLng = loc.longitude,
            accuracyM = loc.accuracy.roundToInt(),
            distanceM = distM,
            insideZone = inside,
            dwellSeconds = dwellSec,
            mockDetected = mockDetected,
            mockReason = mockReason,
            provider = loc.provider.orEmpty(),
            updatedAt = now
        )

        val cb = onUpdateCallback
        if (cb != null) {
            mainHandler.post { cb.invoke() }
        }
    }

    private fun updateFocusGeofences(sc: SelfControlStore, loc: Location) {
        val zones = sc.getFocusGeofences()
        if (zones.isEmpty()) return
        var changed = false
        val updated = zones.map { z ->
            val res = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.latitude, z.longitude, res)
            val dist = res[0]
            val nowInside = if (z.insideNow) {
                dist <= (z.radiusMeters + 35f) // hysteresis to avoid jitter
            } else {
                dist <= z.radiusMeters.toFloat()
            }
            if (nowInside != z.insideNow) changed = true
            z.copy(insideNow = nowInside)
        }
        if (changed) {
            sc.setFocusGeofences(updated)
        }
    }
}
