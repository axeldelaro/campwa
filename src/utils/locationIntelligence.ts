import type { GeofenceZoneItem, GpsTrackPoint } from '../types/sentinel';

export interface SmartPlaceVisit {
  id: string;
  label: string;
  matchedGeofenceName: string | null;
  latitude: number;
  longitude: number;
  startTs: number;
  endTs: number;
  durationSec: number;
  isNewPlace: boolean;
  visitCountInPeriod: number;
  point: GpsTrackPoint;
}

export interface HeatmapSpot {
  id: string;
  latitude: number;
  longitude: number;
  label: string;
  totalDwellSec: number;
  pointCount: number;
  weight: number; // 0.15 .. 1.0
}

function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLon / 2);
  const a = s1 * s1 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * s2 * s2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function matchGeofence(lat: number, lng: number, geofences: GeofenceZoneItem[]): string | null {
  for (const z of geofences) {
    const dist = haversineM(lat, lng, z.latitude, z.longitude);
    if (dist <= Math.max(z.radiusMeters, 110)) {
      return z.name;
    }
  }
  return null;
}

/**
 * Turns raw GPS history points into:
 * 1) A clean chronological Smart Visits Timeline (arrivals, departures, dwell durations, geofence matching, "New place" detection)
 * 2) Weighted Heatmap spots for the map overlay
 */
export function buildLocationIntelligence(
  points: GpsTrackPoint[],
  geofences: GeofenceZoneItem[] = []
): { visits: SmartPlaceVisit[]; heatmapSpots: HeatmapSpot[] } {
  if (!points || points.length === 0) {
    return { visits: [], heatmapSpots: [] };
  }

  const sorted = [...points].sort((a, b) => a.timestamp - b.timestamp);

  // Step 1: Extract contiguous stationary stops (radius <= 90m, duration >= 4 min)
  interface RawStop {
    latSum: number;
    lngSum: number;
    count: number;
    startTs: number;
    endTs: number;
    dwellExtraSec: number;
    repPoint: GpsTrackPoint;
  }

  const rawStops: RawStop[] = [];
  let cur: RawStop | null = null;

  const flushStop = () => {
    if (!cur) return;
    const spanSec = Math.max(
      Math.round((cur.endTs - cur.startTs) / 1000),
      cur.dwellExtraSec
    );
    if (spanSec >= 240) {
      rawStops.push(cur);
    }
    cur = null;
  };

  for (const pt of sorted) {
    if (!cur) {
      cur = {
        latSum: pt.latitude,
        lngSum: pt.longitude,
        count: 1,
        startTs: pt.timestamp,
        endTs: pt.timestamp + (pt.dwellSeconds || 0) * 1000,
        dwellExtraSec: pt.dwellSeconds || 0,
        repPoint: pt,
      };
      continue;
    }
    const centerLat = cur.latSum / cur.count;
    const centerLng = cur.lngSum / cur.count;
    const dist = haversineM(centerLat, centerLng, pt.latitude, pt.longitude);
    const timeGapMs = pt.timestamp - cur.endTs;

    if (dist <= 95 && timeGapMs <= 45 * 60_000) {
      cur.latSum += pt.latitude;
      cur.lngSum += pt.longitude;
      cur.count += 1;
      cur.endTs = Math.max(cur.endTs, pt.timestamp + (pt.dwellSeconds || 0) * 1000);
      cur.dwellExtraSec = Math.max(cur.dwellExtraSec, pt.dwellSeconds || 0);
    } else {
      flushStop();
      cur = {
        latSum: pt.latitude,
        lngSum: pt.longitude,
        count: 1,
        startTs: pt.timestamp,
        endTs: pt.timestamp + (pt.dwellSeconds || 0) * 1000,
        dwellExtraSec: pt.dwellSeconds || 0,
        repPoint: pt,
      };
    }
  }
  flushStop();

  // Step 2: Group stops into unique physical places (within 110m) to count repeat visits & build heatmap
  interface PlaceCluster {
    id: string;
    latSum: number;
    lngSum: number;
    weightCount: number;
    totalDwellSec: number;
    visits: number;
    geofenceName: string | null;
    label: string;
  }

  const clusters: PlaceCluster[] = [];
  const stopToCluster: PlaceCluster[] = [];

  for (const st of rawStops) {
    const lat = st.latSum / st.count;
    const lng = st.lngSum / st.count;
    const durSec = Math.max(Math.round((st.endTs - st.startTs) / 1000), st.dwellExtraSec);

    let matched = clusters.find(
      (c) => haversineM(c.latSum / c.weightCount, c.lngSum / c.weightCount, lat, lng) <= 115
    );
    if (!matched) {
      const geo = matchGeofence(lat, lng, geofences);
      matched = {
        id: `pl-${clusters.length}`,
        latSum: lat,
        lngSum: lng,
        weightCount: 1,
        totalDwellSec: durSec,
        visits: 1,
        geofenceName: geo,
        label: geo || '',
      };
      clusters.push(matched);
    } else {
      matched.latSum += lat;
      matched.lngSum += lng;
      matched.weightCount += 1;
      matched.totalDwellSec += durSec;
      matched.visits += 1;
    }
    stopToCluster.push(matched);
  }

  // Name unnamed clusters clearly by frequency
  let unnamedIdx = 1;
  for (const c of [...clusters].sort((a, b) => b.totalDwellSec - a.totalDwellSec)) {
    if (!c.label) {
      c.label = c.visits >= 2 ? `Lieu habituel #${unnamedIdx++}` : 'Nouveau lieu';
    }
  }

  const visits: SmartPlaceVisit[] = rawStops
    .map((st, i) => {
      const cl = stopToCluster[i];
      const lat = st.latSum / st.count;
      const lng = st.lngSum / st.count;
      const durSec = Math.max(Math.round((st.endTs - st.startTs) / 1000), st.dwellExtraSec);
      const isNew = !cl.geofenceName && cl.visits === 1;
      return {
        id: `visit-${st.startTs}-${i}`,
        label: cl.label,
        matchedGeofenceName: cl.geofenceName,
        latitude: lat,
        longitude: lng,
        startTs: st.startTs,
        endTs: st.endTs,
        durationSec: durSec,
        isNewPlace: isNew,
        visitCountInPeriod: cl.visits,
        point: {
          ...st.repPoint,
          latitude: lat,
          longitude: lng,
          dwellSeconds: durSec,
        },
      };
    })
    .reverse(); // Most recent first

  // Step 3: Build heatmap density spots (combine place clusters + grid buckets for routes without long stops)
  const gridMap = new Map<
    string,
    { latSum: number; lngSum: number; count: number; dwellSec: number; label: string }
  >();

  for (const pt of sorted) {
    // ~90m spatial cell
    const gx = Math.round(pt.latitude * 1100);
    const gy = Math.round(pt.longitude * 900);
    const key = `${gx}:${gy}`;
    const cell = gridMap.get(key);
    const dwell = Math.max(15, pt.dwellSeconds || 15);
    if (!cell) {
      gridMap.set(key, {
        latSum: pt.latitude,
        lngSum: pt.longitude,
        count: 1,
        dwellSec: dwell,
        label: matchGeofence(pt.latitude, pt.longitude, geofences) || '',
      });
    } else {
      cell.latSum += pt.latitude;
      cell.lngSum += pt.longitude;
      cell.count += 1;
      cell.dwellSec += dwell;
    }
  }

  const rawCells = Array.from(gridMap.entries())
    .map(([id, c]) => ({
      id,
      latitude: c.latSum / c.count,
      longitude: c.lngSum / c.count,
      label: c.label || `${c.count} points`,
      totalDwellSec: c.dwellSec,
      pointCount: c.count,
    }))
    .sort((a, b) => b.totalDwellSec - a.totalDwellSec)
    .slice(0, 75);

  const maxDwell = Math.max(60, ...rawCells.map((c) => c.totalDwellSec));
  const heatmapSpots: HeatmapSpot[] = rawCells.map((c) => ({
    ...c,
    weight: Math.max(0.18, Math.min(1, Math.log10(c.totalDwellSec + 10) / Math.log10(maxDwell + 10))),
  }));

  return { visits, heatmapSpots };
}
