import React from 'react';
import {
  AlertTriangle,
  Car,
  Gauge,
  MapPin,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Zap,
} from 'lucide-react';
import type { DataDrivingReportMsg, DrivingEventItem } from '../types/sentinel';

interface DrivingScoreCardProps {
  data: DataDrivingReportMsg | null;
  loading: boolean;
  days: number;
  onChangeDays: (days: number) => Promise<void>;
  onRefresh: () => Promise<void>;
  onFocusEvent: (ev: DrivingEventItem) => void;
}

function kindMeta(kind: DrivingEventItem['kind']): { label: string; color: string } {
  switch (kind) {
    case 'overspeed':
      return { label: 'Excès > 130 km/h', color: 'border-rose-500/40 bg-rose-500/15 text-rose-300' };
    case 'phone_use':
      return { label: 'Téléphone en roulant', color: 'border-rose-500/40 bg-rose-500/15 text-rose-200' };
    case 'harsh_brake':
      return { label: 'Freinage brusque', color: 'border-amber-500/40 bg-amber-500/15 text-amber-200' };
    case 'sharp_turn':
      return { label: 'Virage sec', color: 'border-amber-500/40 bg-amber-500/15 text-amber-200' };
    default:
      return { label: 'Accélération forte', color: 'border-sky-500/40 bg-sky-500/15 text-sky-200' };
  }
}

export const DrivingScoreCard: React.FC<DrivingScoreCardProps> = ({
  data,
  loading,
  days,
  onChangeDays,
  onRefresh,
  onFocusEvent,
}) => {
  const score = data?.score ?? 100;
  const events = Array.isArray(data?.events) ? data.events : [];

  const scoreTone =
    score >= 85
      ? { ring: 'border-emerald-400/50 bg-emerald-500/15 text-emerald-300', label: 'Conduite sûre' }
      : score >= 65
        ? { ring: 'border-amber-400/50 bg-amber-500/15 text-amber-300', label: 'Vigilance modérée' }
        : { ring: 'border-rose-500/50 bg-rose-500/15 text-rose-300', label: 'Conduite à risque' };

  return (
    <div className="space-y-4 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
            <Car className="h-4 w-4 text-emerald-400" />
            Score de conduite & Sécurité route
          </h3>
          <p className="text-[11px] text-zinc-400">
            Freinages brusques, accélérations, virages, téléphone en roulant et vitesses &gt; 130 km/h.
          </p>
        </div>

        <div className="flex items-center gap-1.5">
          {[7, 30].map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => void onChangeDays(d)}
              className={`rounded-lg px-2.5 py-1 text-xs font-medium transition ${
                days === d
                  ? 'bg-white text-zinc-950'
                  : 'border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
              }`}
            >
              {d} j
            </button>
          ))}
          <button
            type="button"
            onClick={() => void onRefresh()}
            disabled={loading}
            className="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200 hover:border-white/20 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {/* Score Hero + KPIs */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="flex items-center gap-3.5 rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3.5">
          <div
            className={`flex h-16 w-16 shrink-0 flex-col items-center justify-center rounded-2xl border ${scoreTone.ring}`}
          >
            <span className="font-mono text-2xl font-bold leading-none">{score}</span>
            <span className="text-[10px] opacity-80">/ 100</span>
          </div>
          <div>
            <p className="text-xs font-semibold text-white">{scoreTone.label}</p>
            <p className="mt-0.5 text-[11px] text-zinc-400">
              {data ? `${data.totalDistanceKm ?? 0} km parcourus` : 'Analyse en attente'}
            </p>
            <p className="text-[10px] text-zinc-500">
              Pointe : <span className="font-mono text-zinc-300">{data?.maxSpeedKmh ?? 0} km/h</span>
            </p>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 sm:col-span-2">
          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3">
            <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
              <Gauge className="h-3.5 w-3.5 text-rose-400" />
              Excès &gt; 130 km/h
            </div>
            <p className="mt-1 font-mono text-lg font-bold text-white">
              {data?.overspeedCount ?? 0}
            </p>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3">
            <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
              <Smartphone className="h-3.5 w-3.5 text-rose-400" />
              Téléphone en roulant
            </div>
            <p className="mt-1 font-mono text-lg font-bold text-white">
              {data?.phoneUseCount ?? 0}
            </p>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3">
            <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
              <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
              Freinages / Virages
            </div>
            <p className="mt-1 font-mono text-lg font-bold text-white">
              {(data?.harshBrakes ?? 0) + (data?.sharpTurns ?? 0)}
            </p>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3">
            <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
              <Zap className="h-3.5 w-3.5 text-sky-400" />
              Accélérations fortes
            </div>
            <p className="mt-1 font-mono text-lg font-bold text-white">
              {data?.harshAccels ?? 0}
            </p>
          </div>
        </div>
      </div>

      {/* Events List */}
      {events.length === 0 ? (
        <div className="flex items-center justify-center gap-2 rounded-xl border border-emerald-500/20 bg-emerald-500/[0.05] px-4 py-6 text-xs text-emerald-200">
          <ShieldCheck className="h-4 w-4 text-emerald-400" />
          <span>Aucun incident de conduite détecté sur les {days} derniers jours.</span>
        </div>
      ) : (
        <div className="space-y-1.5">
          <p className="text-[11px] font-medium text-zinc-400">
            Événements détectés ({events.length}) — touchez un événement pour le voir sur la carte
          </p>
          <div className="max-h-72 space-y-1.5 overflow-y-auto pr-0.5">
            {events.map((ev) => {
              const meta = kindMeta(ev.kind);
              return (
                <button
                  key={ev.id}
                  type="button"
                  onClick={() => onFocusEvent(ev)}
                  className="flex w-full items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950/80 px-3 py-2.5 text-left transition hover:border-white/20"
                >
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className={`rounded-md border px-1.5 py-0.5 text-[10px] font-semibold ${meta.color}`}>
                        {meta.label}
                      </span>
                      <span className="font-mono text-[10px] text-zinc-500">
                        {new Date(ev.timestamp).toLocaleString('fr-FR', {
                          day: '2-digit',
                          month: '2-digit',
                          hour: '2-digit',
                          minute: '2-digit',
                        })}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-xs text-zinc-200">{ev.detail}</p>
                  </div>
                  <span className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-white/10 px-2 py-1 text-[10px] text-zinc-300">
                    <MapPin className="h-3 w-3 text-sky-400" />
                    Voir
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
