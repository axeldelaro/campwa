import React, { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Flame, Gauge, Layers, Maximize2 } from 'lucide-react';
import type { DrivingEventItem, GpsTrackPoint, SharedLocationData } from '../types/sentinel';
import type { HeatmapSpot } from '../utils/locationIntelligence';

interface GpsTrailMapProps {
  points: GpsTrackPoint[];
  liveLocation: SharedLocationData | null;
  focusedPoint?: GpsTrackPoint | null;
  heatmapSpots?: HeatmapSpot[];
  drivingEvents?: DrivingEventItem[];
}

const OVERSPEED_THRESHOLD_KMH = 130;

interface OverspeedZone {
  id: string;
  peakSpeedKmh: number;
  avgSpeedKmh: number;
  startTs: number;
  endTs: number;
  durationSec: number;
  distanceMeters: number;
  peakPoint: GpsTrackPoint;
  points: GpsTrackPoint[];
}

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatDwell(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.round(seconds / 60);
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function haversineMeters(a: GpsTrackPoint, b: GpsTrackPoint): number {
  const R = 6371000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.latitude - a.latitude);
  const dLon = toRad(b.longitude - a.longitude);
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLon / 2);
  const h = s1 * s1 + Math.cos(toRad(a.latitude)) * Math.cos(toRad(b.latitude)) * s2 * s2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

/**
 * Extracts exact > 130 km/h segments & contiguous zones along the GPS trajectory,
 * filtering out impossible GPS teleport spikes (> 320 km/h with poor accuracy).
 */
function extractOverspeedZones(points: GpsTrackPoint[]): OverspeedZone[] {
  const zones: OverspeedZone[] = [];
  let current: GpsTrackPoint[] = [];

  const isValidOverspeed = (p: GpsTrackPoint): boolean =>
    p.speedKmh > OVERSPEED_THRESHOLD_KMH &&
    p.speedKmh < 320 &&
    (p.accuracyMeters <= 70 || p.accuracyMeters === 0);

  const flush = () => {
    if (current.length === 0) return;
    let peak = current[0];
    let sumSpeed = 0;
    let dist = 0;
    for (let i = 0; i < current.length; i++) {
      const pt = current[i];
      sumSpeed += pt.speedKmh;
      if (pt.speedKmh > peak.speedKmh) peak = pt;
      if (i > 0) dist += haversineMeters(current[i - 1], pt);
    }
    const startTs = current[0].timestamp;
    const endTs = current[current.length - 1].timestamp;
    zones.push({
      id: `os-${startTs}-${zones.length}`,
      peakSpeedKmh: peak.speedKmh,
      avgSpeedKmh: sumSpeed / current.length,
      startTs,
      endTs,
      durationSec: Math.max(1, Math.round((endTs - startTs) / 1000)),
      distanceMeters: dist,
      peakPoint: peak,
      points: [...current],
    });
    current = [];
  };

  for (let i = 0; i < points.length; i++) {
    const p = points[i];
    if (isValidOverspeed(p)) {
      // Split into a new zone if there's a large time gap (> 3 min)
      if (current.length > 0 && p.timestamp - current[current.length - 1].timestamp > 180_000) {
        flush();
      }
      current.push(p);
    } else {
      flush();
    }
  }
  flush();
  return zones;
}

export const GpsTrailMap: React.FC<GpsTrailMapProps> = ({
  points,
  liveLocation,
  focusedPoint,
  heatmapSpots = [],
  drivingEvents = [],
}) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);
  const overlayGroupRef = useRef<L.LayerGroup | null>(null);
  const [mapStyle, setMapStyle] = useState<'dark' | 'osm'>('osm');
  const [showHeatmap, setShowHeatmap] = useState(false);

  const overspeedZones = useMemo(() => extractOverspeedZones(points), [points]);

  // Initialize Leaflet map once
  useEffect(() => {
    const el = containerRef.current;
    if (!el || mapRef.current) return;

    const map = L.map(el, {
      center: [48.8566, 2.3522],
      zoom: 13,
      zoomControl: true,
      attributionControl: true,
    });

    const group = L.layerGroup().addTo(map);
    mapRef.current = map;
    overlayGroupRef.current = group;

    const timer = window.setTimeout(() => {
      map.invalidateSize();
    }, 150);

    const onVis = () => {
      if (document.visibilityState === 'visible') {
        window.setTimeout(() => {
          try {
            map.invalidateSize();
          } catch {
            // Ignore
          }
        }, 180);
      }
    };
    document.addEventListener('visibilitychange', onVis);

    return () => {
      window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVis);
      map.remove();
      mapRef.current = null;
      tileLayerRef.current = null;
      overlayGroupRef.current = null;
    };
  }, []);

  // Switch tile layer cleanly
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (tileLayerRef.current) {
      map.removeLayer(tileLayerRef.current);
      tileLayerRef.current = null;
    }

    const url =
      mapStyle === 'dark'
        ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png'
        : 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

    const attribution =
      mapStyle === 'dark'
        ? '&copy; OpenStreetMap &copy; CARTO'
        : '&copy; OpenStreetMap contributors';

    tileLayerRef.current = L.tileLayer(url, {
      maxZoom: 19,
      attribution,
    }).addTo(map);
  }, [mapStyle]);

  // Render trail polyline, precision >130 km/h red segments, stop pins, start/end markers, and live location
  useEffect(() => {
    const map = mapRef.current;
    const group = overlayGroupRef.current;
    if (!map || !group) return;

    group.clearLayers();

    const coords: [number, number][] = points.map((p) => [p.latitude, p.longitude]);

    if (coords.length >= 2) {
      // Soft outer glow track
      L.polyline(coords, {
        color: '#38bdf8',
        weight: 8,
        opacity: 0.22,
        lineJoin: 'round',
        lineCap: 'round',
      }).addTo(group);

      // Crisp main route line
      L.polyline(coords, {
        color: '#10b981',
        weight: 3.5,
        opacity: 0.95,
        lineJoin: 'round',
        lineCap: 'round',
      }).addTo(group);

      // Precision > 130 km/h exact road segment overlay (crimson halo + bright red core)
      for (let i = 1; i < points.length; i++) {
        const a = points[i - 1];
        const b = points[i];
        const aOver = a.speedKmh > OVERSPEED_THRESHOLD_KMH && a.speedKmh < 320;
        const bOver = b.speedKmh > OVERSPEED_THRESHOLD_KMH && b.speedKmh < 320;
        if ((aOver || bOver) && b.timestamp - a.timestamp <= 180_000) {
          const seg: [number, number][] = [
            [a.latitude, a.longitude],
            [b.latitude, b.longitude],
          ];
          const maxSegSpeed = Math.max(a.speedKmh, b.speedKmh);
          L.polyline(seg, {
            color: '#f43f5e',
            weight: 13,
            opacity: 0.28,
            lineJoin: 'round',
            lineCap: 'round',
          }).addTo(group);
          L.polyline(seg, {
            color: '#ef4444',
            weight: 6,
            opacity: 0.98,
            lineJoin: 'round',
            lineCap: 'round',
          })
            .bindPopup(
              `<div style="font-size:12px;line-height:1.45">
                <strong style="color:#e11d48">Zone &gt; 130 km/h (${Math.round(maxSegSpeed)} km/h)</strong><br/>
                ${formatTime(b.timestamp)}<br/>
                Précision GPS : ±${b.accuracyMeters} m
              </div>`
            )
            .addTo(group);
        }
      }
    }

    // Dedicated Peak Speed Badges for every > 130 km/h zone
    for (const z of overspeedZones) {
      const p = z.peakPoint;
      const distKm = (z.distanceMeters / 1000).toFixed(2);
      const speedIcon = L.divIcon({
        className: 'sentinel-overspeed-badge',
        html: `<div style="background:#e11d48;color:#fff;font-weight:700;font-size:10px;padding:2px 6px;border-radius:999px;border:2px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.45);white-space:nowrap;transform:translate(-50%,-50%);">${Math.round(
          z.peakSpeedKmh
        )} km/h</div>`,
        iconSize: [0, 0],
        iconAnchor: [0, 0],
      });

      L.marker([p.latitude, p.longitude], { icon: speedIcon })
        .bindPopup(
          `<div style="font-size:12px;line-height:1.45">
            <strong style="color:#e11d48">Dépassement &gt; 130 km/h</strong><br/>
            Vitesse max : <strong>${Math.round(z.peakSpeedKmh)} km/h</strong> (moy. ${Math.round(
            z.avgSpeedKmh
          )} km/h)<br/>
            Heure : ${formatTime(z.startTs)}<br/>
            Durée : ${formatDwell(z.durationSec)} · Distance : ${distKm} km<br/>
            <a href="https://www.google.com/maps?q=${p.latitude},${p.longitude}" target="_blank" rel="noopener noreferrer" style="color:#0284c7;text-decoration:underline">Voir dans Google Maps</a>
          </div>`
        )
        .addTo(group);
    }

    // Start point marker
    if (points.length > 0) {
      const start = points[0];
      L.circleMarker([start.latitude, start.longitude], {
        radius: 7,
        color: '#ffffff',
        weight: 2,
        fillColor: '#10b981',
        fillOpacity: 1,
      })
        .bindPopup(
          `<div style="font-size:12px;line-height:1.4">
            <strong>Départ de la trace</strong><br/>
            ${formatTime(start.timestamp)}<br/>
            Précision : ±${start.accuracyMeters} m • Batterie : ${start.batteryLevel}%
          </div>`
        )
        .addTo(group);
    }

    // Stop / Dwell pins (stayed >= 2 min)
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      if (p.dwellSeconds >= 120) {
        L.circleMarker([p.latitude, p.longitude], {
          radius: 7.5,
          color: '#ffffff',
          weight: 2,
          fillColor: '#f59e0b',
          fillOpacity: 0.95,
        })
          .bindPopup(
            `<div style="font-size:12px;line-height:1.4">
              <strong>Arrêt (${formatDwell(p.dwellSeconds)})</strong><br/>
              Arrivée : ${formatTime(p.timestamp)}<br/>
              Précision : ±${p.accuracyMeters} m • Batterie : ${p.batteryLevel}%<br/>
              <a href="https://www.google.com/maps?q=${p.latitude},${p.longitude}" target="_blank" rel="noopener noreferrer" style="color:#0284c7;text-decoration:underline">Ouvrir dans Google Maps</a>
            </div>`
          )
          .addTo(group);
      } else if (points.length <= 160 && i > 0 && i < points.length - 1 && i % 2 === 0 && p.speedKmh <= OVERSPEED_THRESHOLD_KMH) {
        L.circleMarker([p.latitude, p.longitude], {
          radius: 3.5,
          color: '#065f46',
          weight: 1,
          fillColor: '#34d399',
          fillOpacity: 0.85,
        })
          .bindPopup(
            `<div style="font-size:12px;line-height:1.4">
              <strong>Passage</strong> • ${formatTime(p.timestamp)}<br/>
              Vitesse : ${p.speedKmh.toFixed(1)} km/h • ±${p.accuracyMeters} m<br/>
              Batterie : ${p.batteryLevel}% (${escapeHtml(p.provider.toUpperCase())})
            </div>`
          )
          .addTo(group);
      }
    }

    // Latest / Live marker
    const latestLat = liveLocation?.latitude ?? points[points.length - 1]?.latitude;
    const latestLng = liveLocation?.longitude ?? points[points.length - 1]?.longitude;
    const latestAcc = liveLocation?.accuracyMeters ?? points[points.length - 1]?.accuracyMeters ?? 20;
    const latestTs = liveLocation?.timestamp ?? points[points.length - 1]?.timestamp ?? Date.now();

    if (latestLat !== undefined && latestLng !== undefined) {
      if (latestAcc > 0 && latestAcc <= 250) {
        L.circle([latestLat, latestLng], {
          radius: latestAcc,
          color: '#38bdf8',
          weight: 1,
          fillColor: '#38bdf8',
          fillOpacity: 0.14,
        }).addTo(group);
      }

      L.circleMarker([latestLat, latestLng], {
        radius: 9,
        color: '#ffffff',
        weight: 3,
        fillColor: '#0ea5e9',
        fillOpacity: 1,
      })
        .bindPopup(
          `<div style="font-size:12px;line-height:1.4">
            <strong>Dernière position connue</strong><br/>
            ${formatTime(latestTs)}<br/>
            Précision : ±${latestAcc} m<br/>
            <a href="https://www.google.com/maps?q=${latestLat},${latestLng}" target="_blank" rel="noopener noreferrer" style="color:#0284c7;text-decoration:underline">Itinéraire Google Maps</a>
          </div>`
        )
        .addTo(group);
    }

    // Optional Heatmap Density Overlay (shows most frequented places!)
    if (showHeatmap && heatmapSpots.length > 0) {
      for (const spot of heatmapSpots) {
        const outerR = Math.round(70 + spot.weight * 190);
        const innerR = Math.round(28 + spot.weight * 75);
        const outerColor = spot.weight > 0.7 ? '#f43f5e' : spot.weight > 0.4 ? '#f59e0b' : '#38bdf8';
        L.circle([spot.latitude, spot.longitude], {
          radius: outerR,
          color: outerColor,
          weight: 0,
          fillColor: outerColor,
          fillOpacity: 0.14 + spot.weight * 0.14,
          interactive: false,
        }).addTo(group);
        L.circle([spot.latitude, spot.longitude], {
          radius: innerR,
          color: outerColor,
          weight: 1,
          opacity: 0.35,
          fillColor: outerColor,
          fillOpacity: 0.25 + spot.weight * 0.2,
        })
          .bindPopup(
            `<div style="font-size:12px;line-height:1.4">
              <strong>${escapeHtml(spot.label)}</strong><br/>
              Temps cumulé : ${formatDwell(spot.totalDwellSec)}
            </div>`
          )
          .addTo(group);
      }
    }

    // Driving Behavior Event Markers (harsh braking, phone use while driving, sharp turn)
    for (const ev of drivingEvents.slice(0, 40)) {
      if (!ev.latitude && !ev.longitude) continue;
      if (ev.kind === 'overspeed') continue; // Already highlighted by red >130 km/h segments
      const color = ev.kind === 'phone_use' ? '#e11d48' : ev.kind === 'harsh_brake' ? '#f97316' : '#eab308';
      L.circleMarker([ev.latitude, ev.longitude], {
        radius: 6.5,
        color: '#ffffff',
        weight: 2,
        fillColor: color,
        fillOpacity: 0.95,
      })
        .bindPopup(
          `<div style="font-size:12px;line-height:1.4">
            <strong style="color:${color}">${escapeHtml(ev.detail)}</strong><br/>
            ${formatTime(ev.timestamp)}
          </div>`
        )
        .addTo(group);
    }

    // Fit map view cleanly
    if (coords.length >= 2) {
      const bounds = L.latLngBounds(coords);
      if (liveLocation) {
        bounds.extend([liveLocation.latitude, liveLocation.longitude]);
      }
      map.fitBounds(bounds, { padding: [36, 36], maxZoom: 16 });
    } else if (latestLat !== undefined && latestLng !== undefined) {
      map.setView([latestLat, latestLng], 15);
    }

    map.invalidateSize();
  }, [points, liveLocation, overspeedZones, showHeatmap, heatmapSpots, drivingEvents]);

  // Smooth fly-to when user clicks a timeline item
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !focusedPoint) return;

    map.setView([focusedPoint.latitude, focusedPoint.longitude], 16, { animate: true });
    L.popup()
      .setLatLng([focusedPoint.latitude, focusedPoint.longitude])
      .setContent(
        `<div style="font-size:12px;line-height:1.4">
          <strong>${
            focusedPoint.speedKmh > OVERSPEED_THRESHOLD_KMH
              ? `Vitesse > 130 km/h (${Math.round(focusedPoint.speedKmh)} km/h)`
              : focusedPoint.dwellSeconds >= 120
              ? `Arrêt (${formatDwell(focusedPoint.dwellSeconds)})`
              : 'Point enregistré'
          }</strong><br/>
          ${formatTime(focusedPoint.timestamp)}<br/>
          Vitesse : ${focusedPoint.speedKmh.toFixed(1)} km/h • ±${focusedPoint.accuracyMeters} m<br/>
          Batterie : ${focusedPoint.batteryLevel}%
        </div>`
      )
      .openOn(map);
  }, [focusedPoint]);

  const handleFocusOverspeedZone = (zone: OverspeedZone) => {
    const map = mapRef.current;
    if (!map) return;
    if (zone.points.length >= 2) {
      const bounds = L.latLngBounds(
        zone.points.map((p) => [p.latitude, p.longitude] as [number, number])
      );
      map.fitBounds(bounds, { padding: [55, 55], maxZoom: 15 });
    } else {
      map.setView([zone.peakPoint.latitude, zone.peakPoint.longitude], 15, { animate: true });
    }
    L.popup()
      .setLatLng([zone.peakPoint.latitude, zone.peakPoint.longitude])
      .setContent(
        `<div style="font-size:12px;line-height:1.45">
          <strong style="color:#e11d48">Zone &gt; 130 km/h</strong><br/>
          Pointe : <strong>${Math.round(zone.peakSpeedKmh)} km/h</strong> · Moy. ${Math.round(
          zone.avgSpeedKmh
        )} km/h<br/>
          ${formatTime(zone.startTs)} · ${formatDwell(zone.durationSec)} (${(
          zone.distanceMeters / 1000
        ).toFixed(2)} km)
        </div>`
      )
      .openOn(map);
  };

  const handleRecenter = () => {
    const map = mapRef.current;
    if (!map) return;
    if (points.length >= 2) {
      const bounds = L.latLngBounds(points.map((p) => [p.latitude, p.longitude] as [number, number]));
      if (liveLocation) bounds.extend([liveLocation.latitude, liveLocation.longitude]);
      map.fitBounds(bounds, { padding: [36, 36], maxZoom: 16 });
    } else if (liveLocation) {
      map.setView([liveLocation.latitude, liveLocation.longitude], 15);
    }
  };

  return (
    <div className="relative overflow-hidden rounded-2xl border border-slate-800 bg-slate-950">
      {/* Precision > 130 km/h Overspeed Zones Alert Strip */}
      {overspeedZones.length > 0 && (
        <div className="border-b border-rose-500/25 bg-rose-500/10 px-3.5 py-2.5 text-xs">
          <div className="flex flex-wrap items-center justify-between gap-2 text-rose-200">
            <span className="inline-flex items-center gap-1.5 font-semibold">
              <Gauge className="h-4 w-4 text-rose-400" />
              <span>
                {overspeedZones.length} zone{overspeedZones.length > 1 ? 's' : ''} au-dessus de 130 km/h
              </span>
            </span>
            <span className="font-mono text-[11px] text-rose-300">
              Max : {Math.round(Math.max(...overspeedZones.map((z) => z.peakSpeedKmh)))} km/h
            </span>
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {overspeedZones.map((z) => (
              <button
                key={z.id}
                type="button"
                onClick={() => handleFocusOverspeedZone(z)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-rose-500/40 bg-zinc-950/85 px-2.5 py-1 text-[11px] font-medium text-rose-100 transition hover:bg-rose-500/20"
              >
                <span className="font-mono font-semibold text-rose-300">
                  {Math.round(z.peakSpeedKmh)} km/h
                </span>
                <span className="text-zinc-400">
                  {new Date(z.startTs).toLocaleTimeString('fr-FR', {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </span>
                {z.distanceMeters >= 100 && (
                  <span className="text-zinc-500">· {(z.distanceMeters / 1000).toFixed(1)} km</span>
                )}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="relative">
        <div ref={containerRef} className="h-[340px] w-full z-0 sm:h-[400px]" />

        {/* Floating Map Controls */}
        <div className="absolute right-3 top-3 z-[400] flex flex-col gap-1.5">
          <button
            type="button"
            onClick={() => setMapStyle((s) => (s === 'osm' ? 'dark' : 'osm'))}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-slate-950/90 px-2.5 py-1.5 text-[11px] font-bold text-slate-200 shadow-lg backdrop-blur transition hover:border-sky-500/60 hover:text-white"
            title="Changer le style de carte"
          >
            <Layers className="h-3.5 w-3.5 text-sky-400" />
            <span>{mapStyle === 'osm' ? 'Sombre' : 'Standard'}</span>
          </button>
          {heatmapSpots.length > 0 && (
            <button
              type="button"
              onClick={() => setShowHeatmap((v) => !v)}
              className={`inline-flex items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-[11px] font-bold shadow-lg backdrop-blur transition ${
                showHeatmap
                  ? 'border-amber-400/70 bg-amber-500/25 text-amber-100'
                  : 'border-slate-700 bg-slate-950/90 text-slate-200 hover:border-amber-400/60 hover:text-white'
              }`}
              title="Afficher la carte de chaleur des lieux fréquentés"
            >
              <Flame className="h-3.5 w-3.5 text-amber-400" />
              <span>Chaleur</span>
            </button>
          )}
          <button
            type="button"
            onClick={handleRecenter}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-slate-950/90 px-2.5 py-1.5 text-[11px] font-bold text-slate-200 shadow-lg backdrop-blur transition hover:border-emerald-500/60 hover:text-white"
            title="Recentrer sur toute la trace"
          >
            <Maximize2 className="h-3.5 w-3.5 text-emerald-400" />
            <span>Recentrer</span>
          </button>
        </div>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-800/80 bg-slate-950/95 px-3 py-2 text-[10px] text-slate-400">
        <div className="flex flex-wrap items-center gap-3">
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full border border-white bg-emerald-500" />
            Départ
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full border border-white bg-amber-500" />
            Arrêt (&ge; 2 min)
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2 w-4 rounded-full bg-rose-500" />
            &gt; 130 km/h
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full border border-white bg-sky-500" />
            Position actuelle
          </span>
        </div>
        <span>OpenStreetMap</span>
      </div>
    </div>
  );
};
