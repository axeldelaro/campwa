import React, { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Crosshair, LocateFixed, Lock, ShieldAlert, Unlock } from 'lucide-react';
import type { SelfControlGeoStatus } from '../types/sentinel';

interface GeoZoneMapPickerProps {
  latitude: number;
  longitude: number;
  radiusMeters: number;
  label: string;
  sealed: boolean;
  canEditPin: boolean;
  geoStatus?: SelfControlGeoStatus;
  unlockSessionUntilMs: number;
  nowMs: number;
  onSelectPoint: (lat: number, lng: number) => void;
  onCheckPresenceNow: () => void;
}

function formatDistance(m: number): string {
  if (m < 0) return 'En attente du GPS…';
  if (m < 1000) return `${m} m`;
  return `${(m / 1000).toFixed(2)} km`;
}

export const GeoZoneMapPicker: React.FC<GeoZoneMapPickerProps> = ({
  latitude,
  longitude,
  radiusMeters,
  label,
  sealed,
  canEditPin,
  geoStatus,
  unlockSessionUntilMs,
  nowMs,
  onSelectPoint,
  onCheckPresenceNow,
}) => {
  const mapElRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);
  const canEditRef = useRef(canEditPin);
  const onSelectRef = useRef(onSelectPoint);

  canEditRef.current = canEditPin;
  onSelectRef.current = onSelectPoint;

  const hasZone = Math.abs(latitude) > 0.0001 || Math.abs(longitude) > 0.0001;

  useEffect(() => {
    const el = mapElRef.current;
    if (!el || mapRef.current) return;

    const initialCenter: [number, number] = hasZone
      ? [latitude, longitude]
      : geoStatus?.hasFix
        ? [geoStatus.currentLat, geoStatus.currentLng]
        : [48.8566, 2.3522];

    const map = L.map(el, {
      center: initialCenter,
      zoom: hasZone ? 16 : 12,
      zoomControl: true,
      attributionControl: false,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
    }).addTo(map);

    const group = L.layerGroup().addTo(map);
    mapRef.current = map;
    layerRef.current = group;

    map.on('click', (e: L.LeafletMouseEvent) => {
      if (!canEditRef.current) return;
      onSelectRef.current(
        Number(e.latlng.lat.toFixed(6)),
        Number(e.latlng.lng.toFixed(6))
      );
    });

    const t = window.setTimeout(() => {
      try {
        map.invalidateSize();
      } catch {
        // ignore
      }
    }, 180);

    return () => {
      window.clearTimeout(t);
      map.remove();
      mapRef.current = null;
      layerRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const group = layerRef.current;
    if (!map || !group) return;

    group.clearLayers();

    if (hasZone) {
      const zoneColor = sealed
        ? geoStatus?.insideZone
          ? '#10b981'
          : '#0284c7'
        : '#0ea5e9';

      L.circle([latitude, longitude], {
        radius: radiusMeters,
        color: zoneColor,
        weight: 2.5,
        fillColor: zoneColor,
        fillOpacity: 0.18,
      }).addTo(group);

      L.circleMarker([latitude, longitude], {
        radius: 8,
        color: '#ffffff',
        weight: 3,
        fillColor: zoneColor,
        fillOpacity: 1,
      })
        .bindPopup(
          `<div style="font-size:12px"><strong>${label || 'Zone de déverrouillage'}</strong><br/>Rayon : ${radiusMeters} m</div>`
        )
        .addTo(group);
    }

    if (geoStatus?.hasFix) {
      if (geoStatus.accuracyM > 0 && geoStatus.accuracyM <= 180) {
        L.circle([geoStatus.currentLat, geoStatus.currentLng], {
          radius: geoStatus.accuracyM,
          color: '#6366f1',
          weight: 1,
          fillColor: '#6366f1',
          fillOpacity: 0.1,
          interactive: false,
        }).addTo(group);
      }
      L.circleMarker([geoStatus.currentLat, geoStatus.currentLng], {
        radius: 6.5,
        color: '#ffffff',
        weight: 2.5,
        fillColor: geoStatus.mockDetected ? '#ef4444' : '#6366f1',
        fillOpacity: 1,
      })
        .bindPopup(
          `<div style="font-size:12px"><strong>Votre position</strong><br/>Précision : ±${geoStatus.accuracyM} m</div>`
        )
        .addTo(group);
    }
  }, [hasZone, latitude, longitude, radiusMeters, label, sealed, geoStatus]);

  const handleCenterOnMe = () => {
    onCheckPresenceNow();
    const map = mapRef.current;
    if (geoStatus?.hasFix && map) {
      map.setView([geoStatus.currentLat, geoStatus.currentLng], 16);
      if (canEditPin && !hasZone) {
        onSelectPoint(
          Number(geoStatus.currentLat.toFixed(6)),
          Number(geoStatus.currentLng.toFixed(6))
        );
      }
      return;
    }
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const lat = Number(pos.coords.latitude.toFixed(6));
          const lng = Number(pos.coords.longitude.toFixed(6));
          map?.setView([lat, lng], 16);
          if (canEditPin && !hasZone) {
            onSelectPoint(lat, lng);
          }
        },
        () => {},
        { enableHighAccuracy: true, timeout: 8000 }
      );
    }
  };

  const handleFocusZone = () => {
    if (hasZone && mapRef.current) {
      mapRef.current.setView([latitude, longitude], 16);
    }
  };

  const sessionRemSec = Math.max(0, Math.floor((unlockSessionUntilMs - nowMs) / 1000));
  const sessionActive = sessionRemSec > 0;
  const dwellSec = geoStatus?.dwellSeconds ?? 0;
  const reqDwell = Math.max(1, geoStatus?.requiredDwellSec ?? 180);
  const dwellPct = Math.min(100, Math.round((dwellSec / reqDwell) * 100));

  return (
    <div className="space-y-3">
      {/* Live Geo-Presence Radar Card */}
      {sealed && (
        <div
          className={`rounded-2xl border p-3.5 ${
            geoStatus?.mockDetected
              ? 'border-rose-300 bg-rose-50 dark:border-rose-500/40 dark:bg-rose-500/15'
              : sessionActive
                ? 'border-emerald-300 bg-emerald-50 dark:border-emerald-500/40 dark:bg-emerald-500/15'
                : geoStatus?.insideZone
                  ? 'border-sky-300 bg-sky-50 dark:border-sky-500/40 dark:bg-sky-500/15'
                  : 'border-slate-200 bg-slate-50 dark:border-white/10 dark:bg-white/[0.04]'
          }`}
        >
          <div className="flex items-start justify-between gap-2.5">
            <div className="flex items-start gap-2.5">
              {geoStatus?.mockDetected ? (
                <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-rose-500" />
              ) : sessionActive ? (
                <Unlock className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600 dark:text-emerald-400" />
              ) : (
                <Lock className="mt-0.5 h-5 w-5 shrink-0 text-sky-600 dark:text-sky-400" />
              )}
              <div>
                <div className="text-xs font-bold text-slate-900 dark:text-white">
                  {geoStatus?.mockDetected
                    ? 'Fake GPS / position simulée bloquée'
                    : sessionActive
                      ? `Zone validée — modifications ouvertes (${Math.floor(sessionRemSec / 60)}m${String(
                          sessionRemSec % 60
                        ).padStart(2, '0')}s)`
                      : geoStatus?.insideZone
                        ? `Dans la zone « ${label} » — vérification en cours…`
                        : `Distance de « ${label} » : ${formatDistance(geoStatus?.distanceM ?? -1)}`}
                </div>
                <div className="mt-0.5 text-[11px] text-slate-600 dark:text-slate-300">
                  {geoStatus?.mockReason
                    ? geoStatus.mockReason
                    : sessionActive
                      ? 'Vous pouvez assouplir vos règles ou lever le Pacte pendant cette fenêtre.'
                      : geoStatus?.insideZone
                        ? `Restez physiquement sur place (${dwellSec}s / ${reqDwell}s) sous signal GPS satellite réel.`
                        : `Rendez-vous à moins de ${radiusMeters} m de ce point et restez ${Math.round(
                            reqDwell / 60
                          )} min pour modifier vos règles.`}
                </div>
              </div>
            </div>
            <button
              type="button"
              onClick={onCheckPresenceNow}
              className="shrink-0 rounded-xl bg-sky-500 px-3 py-1.5 text-[11px] font-bold text-white kl-solid-white shadow-sm hover:bg-sky-600"
            >
              Vérifier GPS
            </button>
          </div>

          {geoStatus?.insideZone && !sessionActive && !geoStatus.mockDetected && (
            <div className="mt-2.5">
              <div className="h-2 w-full overflow-hidden rounded-full bg-white/80 dark:bg-slate-800">
                <div
                  className="h-full rounded-full bg-emerald-500 transition-all duration-500"
                  style={{ width: `${Math.max(5, dwellPct)}%` }}
                />
              </div>
              <div className="mt-1 flex justify-between text-[10px] font-semibold text-slate-500 dark:text-slate-400">
                <span>GPS satellite ±{geoStatus.accuracyM} m</span>
                <span>{dwellPct}% ({dwellSec}s / {reqDwell}s)</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Interactive Leaflet Map Container */}
      <div className="relative overflow-hidden rounded-2xl border border-slate-200 shadow-sm dark:border-white/10">
        <div ref={mapElRef} className="h-64 w-full sm:h-72" />

        <div className="pointer-events-none absolute left-2.5 top-2.5 z-[400] max-w-[72%] rounded-xl bg-white/92 px-2.5 py-1.5 text-[11px] font-semibold text-slate-800 shadow backdrop-blur dark:bg-slate-900/90 dark:text-slate-100">
          {canEditPin
            ? 'Touchez la carte pour placer votre point de déverrouillage'
            : `Point scellé : ${label} (rayon ${radiusMeters} m)`}
        </div>

        <div className="absolute bottom-2.5 right-2.5 z-[400] flex flex-col gap-1.5">
          <button
            type="button"
            onClick={handleCenterOnMe}
            className="flex items-center gap-1.5 rounded-xl bg-white/95 px-2.5 py-1.5 text-[11px] font-bold text-slate-800 shadow hover:bg-white dark:bg-slate-900/95 dark:text-white"
            title="Centrer sur ma position GPS"
          >
            <LocateFixed className="h-3.5 w-3.5 text-sky-500" />
            <span>Ma position</span>
          </button>
          {hasZone && (
            <button
              type="button"
              onClick={handleFocusZone}
              className="flex items-center gap-1.5 rounded-xl bg-white/95 px-2.5 py-1.5 text-[11px] font-bold text-slate-800 shadow hover:bg-white dark:bg-slate-900/95 dark:text-white"
              title="Centrer sur la zone de déverrouillage"
            >
              <Crosshair className="h-3.5 w-3.5 text-emerald-500" />
              <span>Voir zone</span>
            </button>
          )}
        </div>
      </div>

      {hasZone && (
        <div className="flex items-center justify-between rounded-xl bg-slate-50 px-3 py-2 text-[11px] text-slate-600 dark:bg-white/[0.04] dark:text-slate-300">
          <span>
            Coordonnées :{' '}
            <strong className="font-mono">
              {latitude.toFixed(5)}, {longitude.toFixed(5)}
            </strong>
          </span>
          <span className="font-semibold text-sky-600 dark:text-sky-400">Rayon {radiusMeters} m</span>
        </div>
      )}
    </div>
  );
};
