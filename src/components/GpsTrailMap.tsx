import React, { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Layers, Maximize2 } from 'lucide-react';
import type { GpsTrackPoint, SharedLocationData } from '../types/sentinel';

interface GpsTrailMapProps {
  points: GpsTrackPoint[];
  liveLocation: SharedLocationData | null;
  focusedPoint?: GpsTrackPoint | null;
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

export const GpsTrailMap: React.FC<GpsTrailMapProps> = ({
  points,
  liveLocation,
  focusedPoint,
}) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);
  const overlayGroupRef = useRef<L.LayerGroup | null>(null);
  const [mapStyle, setMapStyle] = useState<'dark' | 'osm'>('osm');

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

    return () => {
      window.clearTimeout(timer);
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

  // Render trail polyline, stop pins, start/end markers, and live location
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
        opacity: 0.25,
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
      } else if (points.length <= 160 && i > 0 && i < points.length - 1 && i % 2 === 0) {
        // Subtle clickable breadcrumb dots when point count is moderate
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
  }, [points, liveLocation]);

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
            focusedPoint.dwellSeconds >= 120
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

      {/* Legend */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-800/80 bg-slate-950/95 px-3 py-2 text-[10px] text-slate-400">
        <div className="flex flex-wrap items-center gap-3">
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full bg-emerald-500 border border-white" />
            Départ
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full bg-amber-500 border border-white" />
            Arrêt (&ge; 2 min)
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2.5 w-2.5 rounded-full bg-sky-500 border border-white" />
            Position actuelle
          </span>
        </div>
        <span>100% OpenStreetMap • Trace chiffrée E2EE</span>
      </div>
    </div>
  );
};
