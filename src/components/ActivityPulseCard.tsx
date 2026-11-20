import React, { useMemo, useState } from 'react';
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  Camera,
  Clock,
  GraduationCap,
  Moon,
  Smartphone,
  Sparkles,
  TrendingUp,
} from 'lucide-react';
import type {
  HourlyTimelineSlot,
  UnlockMetricsData,
  WeeklyTrendsData,
} from '../types/sentinel';

interface ActivityPulseCardProps {
  unlockMetrics?: UnlockMetricsData;
  hourlyTimeline?: HourlyTimelineSlot[];
  weeklyTrends?: WeeklyTrendsData;
  onJumpToCapturesHour: (hour: number) => void;
}

function formatClock(ts?: number): string {
  if (!ts || ts <= 0) return '--:--';
  const d = new Date(ts);
  return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

function formatShortDuration(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h${m > 0 ? m.toString().padStart(2, '0') : ''}`;
  if (m > 0) return `${m} min`;
  return s > 0 ? `${s} s` : '0 min';
}

function formatMinutesLabel(min: number): string {
  const m = Math.max(0, Math.round(min));
  const h = Math.floor(m / 60);
  const rem = m % 60;
  if (h > 0) return `${h}h${rem.toString().padStart(2, '0')}`;
  return `${m} min`;
}

function DeltaPill({ delta }: { delta?: number }) {
  if (delta === undefined || delta === null || Number.isNaN(delta)) {
    return (
      <span className="rounded-md bg-white/[0.06] px-1.5 py-0.5 font-mono text-[10px] text-zinc-400">
        En cours
      </span>
    );
  }
  const isUp = delta > 0;
  const isZero = delta === 0;
  return (
    <span
      className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 font-mono text-[10px] font-semibold ${
        isZero
          ? 'bg-white/[0.06] text-zinc-300'
          : isUp
            ? delta >= 20
              ? 'bg-rose-500/20 text-rose-300'
              : 'bg-amber-500/20 text-amber-200'
            : 'bg-emerald-500/20 text-emerald-300'
      }`}
    >
      {isUp ? (
        <ArrowUpRight className="h-3 w-3" />
      ) : !isZero ? (
        <ArrowDownRight className="h-3 w-3" />
      ) : null}
      {isUp ? `+${delta}%` : `${delta}%`}
    </span>
  );
}

export const ActivityPulseCard: React.FC<ActivityPulseCardProps> = ({
  unlockMetrics,
  hourlyTimeline,
  weeklyTrends,
  onJumpToCapturesHour,
}) => {
  const slots = useMemo(() => {
    if (Array.isArray(hourlyTimeline) && hourlyTimeline.length === 24) {
      return hourlyTimeline;
    }
    return Array.from({ length: 24 }, (_, hour) => ({
      hour,
      totalSeconds: 0,
      unlocks: 0,
      isNight: hour >= 22 || hour < 6,
      isSchoolHour: (hour >= 8 && hour <= 11) || (hour >= 14 && hour <= 16),
      captureCount: 0,
      topApps: [],
    }));
  }, [hourlyTimeline]);

  // Default selected hour: most recent active hour, or current hour
  const defaultHour = useMemo(() => {
    const active = [...slots].reverse().find((s) => s.totalSeconds > 0 || s.captureCount > 0);
    return active ? active.hour : new Date().getHours();
  }, [slots]);

  const [selectedHour, setSelectedHour] = useState<number | null>(null);
  const activeHour = selectedHour ?? defaultHour;
  const activeSlot = slots[activeHour] ?? slots[0];

  const maxHourSec = useMemo(
    () => Math.max(600, ...slots.map((s) => s.totalSeconds || 0)),
    [slots]
  );

  const totalCapturesToday = useMemo(
    () => slots.reduce((acc, s) => acc + (s.captureCount || 0), 0),
    [slots]
  );

  if (!unlockMetrics && !weeklyTrends && (!hourlyTimeline || hourlyTimeline.length === 0)) {
    return null;
  }

  const compulsionIdx = unlockMetrics?.compulsionIndex ?? 0;
  const compulsionLabel = unlockMetrics?.compulsionLabel ?? 'Calme';
  const compulsionTone =
    compulsionIdx >= 75
      ? 'border-rose-500/35 bg-rose-500/15 text-rose-200'
      : compulsionIdx >= 50
        ? 'border-amber-500/35 bg-amber-500/15 text-amber-200'
        : compulsionIdx >= 25
          ? 'border-sky-500/35 bg-sky-500/15 text-sky-200'
          : 'border-emerald-500/35 bg-emerald-500/15 text-emerald-200';

  return (
    <div className="space-y-4 rounded-2xl border border-white/[0.08] bg-gradient-to-b from-zinc-900/80 to-zinc-950/85 p-4 shadow-[0_8px_30px_rgba(0,0,0,0.28)]">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-xl bg-emerald-500/15 text-emerald-400 ring-1 ring-emerald-500/25">
            <Activity className="h-3.5 w-3.5" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-white">Rythme & Habitudes</h2>
            <p className="text-[11px] text-zinc-500">
              Déverrouillages, tendances hebdo & frise heure par heure
            </p>
          </div>
        </div>
        {unlockMetrics && (
          <span
            className={`inline-flex items-center gap-1 rounded-lg border px-2 py-0.5 text-[11px] font-semibold ${compulsionTone}`}
          >
            <Sparkles className="h-3 w-3" />
            {compulsionLabel} ({compulsionIdx}/100)
          </span>
        )}
      </div>

      {/* #6 Pickups & Compulsion KPI Grid */}
      {unlockMetrics && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
            <div className="flex items-center justify-between gap-1 text-[10px] text-zinc-400">
              <span className="inline-flex items-center gap-1">
                <Smartphone className="h-3 w-3 text-emerald-400" />
                Déverrouillages
              </span>
              <DeltaPill delta={weeklyTrends?.unlocksDeltaPct} />
            </div>
            <div className="mt-1.5 font-mono text-lg font-semibold text-white">
              {unlockMetrics.unlocksToday}
            </div>
            <div className="text-[10px] text-zinc-500">
              {unlockMetrics.shortChecksCount} coup{unlockMetrics.shortChecksCount > 1 ? 's' : ''} d’œil &lt;45s
            </div>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
            <div className="flex items-center gap-1 text-[10px] text-zinc-400">
              <Clock className="h-3 w-3 text-sky-400" />
              Amplitude du jour
            </div>
            <div className="mt-1.5 font-mono text-sm font-semibold text-white">
              {formatClock(unlockMetrics.firstUnlockTs)} → {formatClock(unlockMetrics.lastUnlockTs)}
            </div>
            <div className="mt-0.5 text-[10px] text-zinc-500">
              Moy. {formatShortDuration(unlockMetrics.avgSessionSeconds)} / session
            </div>
          </div>

          <div
            className={`rounded-xl border p-2.5 ${
              unlockMetrics.schoolHoursUnlocks >= 10
                ? 'border-amber-500/30 bg-amber-500/[0.07]'
                : 'border-white/[0.06] bg-zinc-950/75'
            }`}
          >
            <div className="flex items-center gap-1 text-[10px] text-zinc-400">
              <GraduationCap className="h-3 w-3 text-amber-400" />
              Pendant les cours
            </div>
            <div className="mt-1.5 font-mono text-lg font-semibold text-white">
              {unlockMetrics.schoolHoursUnlocks}
            </div>
            <div className="text-[10px] text-zinc-500">8h–12h · 14h–17h</div>
          </div>

          <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
            <div className="flex items-center justify-between gap-1 text-[10px] text-zinc-400">
              <span className="inline-flex items-center gap-1">
                <Moon className="h-3 w-3 text-violet-400" />
                Nuit (7j)
              </span>
              <DeltaPill delta={weeklyTrends?.nightDeltaPct} />
            </div>
            <div className="mt-1.5 font-mono text-lg font-semibold text-white">
              {formatMinutesLabel(weeklyTrends?.nightMinutesThisWeek ?? 0)}
            </div>
            <div className="text-[10px] text-zinc-500">Entre 22h et 06h</div>
          </div>
        </div>
      )}

      {/* #4 Week-over-Week Trend Strip */}
      {weeklyTrends && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950/60 px-3 py-2 text-[11px]">
          <div className="flex items-center gap-2">
            <TrendingUp className="h-3.5 w-3.5 text-emerald-400 shrink-0" />
            <span className="text-zinc-300">
              Moyenne 7 jours :{' '}
              <strong className="font-mono text-white">
                {formatMinutesLabel(weeklyTrends.avgDailyMinutesThisWeek)}/j
              </strong>
              {weeklyTrends.avgDailyMinutesPrevWeek > 0 && (
                <span className="text-zinc-500">
                  {' '}
                  (sem. préc. {formatMinutesLabel(weeklyTrends.avgDailyMinutesPrevWeek)}/j)
                </span>
              )}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] text-zinc-500">Évolution 7j :</span>
            <DeltaPill delta={weeklyTrends.screenTimeDeltaPct} />
          </div>
        </div>
      )}

      {/* #2 Interactive Hour-by-Hour Visual Timeline (00h–23h) linked with Auto-Screenshots */}
      <div className="space-y-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-xs font-medium text-zinc-200">
            Frise horaire du jour (00h – 23h)
          </span>
          <div className="flex flex-wrap items-center gap-2 text-[10px] text-zinc-400">
            <span className="inline-flex items-center gap-1">
              <span className="h-2 w-2 rounded-full bg-emerald-400" />
              Jour
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-2 w-2 rounded-full bg-sky-400" />
              Cours
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-2 w-2 rounded-full bg-violet-400" />
              Nuit
            </span>
            {totalCapturesToday > 0 && (
              <span className="inline-flex items-center gap-1 text-sky-300">
                <Camera className="h-2.5 w-2.5" />
                {totalCapturesToday} capture{totalCapturesToday > 1 ? 's' : ''}
              </span>
            )}
          </div>
        </div>

        {/* 24-Bar Interactive Chart */}
        <div className="rounded-xl border border-white/[0.06] bg-zinc-950/80 px-2 pb-2 pt-3">
          <div className="grid h-20 grid-cols-[repeat(24,minmax(0,1fr))] items-end gap-[3px]">
            {slots.map((slot) => {
              const isSelected = slot.hour === activeHour;
              const pct =
                slot.totalSeconds > 0
                  ? Math.max(12, Math.round((slot.totalSeconds / maxHourSec) * 100))
                  : 4;
              const barColor =
                slot.totalSeconds <= 0
                  ? 'bg-white/[0.06]'
                  : slot.isNight
                    ? 'bg-violet-400'
                    : slot.isSchoolHour
                      ? 'bg-sky-400'
                      : 'bg-emerald-400';

              return (
                <button
                  key={slot.hour}
                  type="button"
                  onClick={() => setSelectedHour(slot.hour)}
                  title={`${slot.hour}h–${(slot.hour + 1) % 24}h : ${formatShortDuration(slot.totalSeconds)}${slot.captureCount > 0 ? ` • ${slot.captureCount} capture(s)` : ''}`}
                  className={`group relative flex h-full flex-col items-center justify-end rounded-sm transition ${
                    isSelected ? 'bg-white/[0.08] ring-1 ring-white/30' : 'hover:bg-white/[0.04]'
                  }`}
                >
                  {slot.captureCount > 0 && (
                    <span className="mb-0.5 h-1.5 w-1.5 shrink-0 rounded-full bg-amber-300 shadow-[0_0_6px_rgba(252,211,77,0.8)]" />
                  )}
                  <div
                    className={`w-full rounded-t-[2px] transition-all ${barColor} ${
                      isSelected ? 'opacity-100' : 'opacity-80 group-hover:opacity-100'
                    }`}
                    style={{ height: `${pct}%` }}
                  />
                </button>
              );
            })}
          </div>
          <div className="mt-1.5 flex justify-between px-0.5 font-mono text-[9px] text-zinc-500">
            <span>00h</span>
            <span>06h</span>
            <span>12h</span>
            <span>18h</span>
            <span>23h</span>
          </div>
        </div>

        {/* Selected Hour Detail Card + Direct Link to Auto-Screenshots */}
        {activeSlot && (
          <div className="rounded-xl border border-white/[0.07] bg-zinc-950/90 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-mono text-xs font-semibold text-white">
                  {activeSlot.hour.toString().padStart(2, '0')}h00 –{' '}
                  {((activeSlot.hour + 1) % 24).toString().padStart(2, '0')}h00
                </span>
                <span className="rounded-md bg-white/[0.06] px-1.5 py-0.5 font-mono text-[10px] text-zinc-300">
                  {formatShortDuration(activeSlot.totalSeconds)}
                </span>
                {activeSlot.unlocks > 0 && (
                  <span className="rounded-md bg-white/[0.05] px-1.5 py-0.5 text-[10px] text-zinc-400">
                    {activeSlot.unlocks} déverr.
                  </span>
                )}
                {activeSlot.isNight && (
                  <span className="rounded-md bg-violet-500/20 px-1.5 py-0.5 text-[10px] text-violet-200">
                    Nuit
                  </span>
                )}
                {activeSlot.isSchoolHour && (
                  <span className="rounded-md bg-sky-500/20 px-1.5 py-0.5 text-[10px] text-sky-200">
                    Heures de cours
                  </span>
                )}
              </div>

              {activeSlot.captureCount > 0 && (
                <button
                  type="button"
                  onClick={() => onJumpToCapturesHour(activeSlot.hour)}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-sky-500/20 px-2.5 py-1 text-[11px] font-semibold text-sky-200 ring-1 ring-sky-400/35 transition hover:bg-sky-500/30"
                >
                  <Camera className="h-3 w-3" />
                  Voir {activeSlot.captureCount} capture{activeSlot.captureCount > 1 ? 's' : ''}
                </button>
              )}
            </div>

            {activeSlot.topApps && activeSlot.topApps.length > 0 ? (
              <div className="mt-2.5 grid gap-1.5 sm:grid-cols-2">
                {activeSlot.topApps.map((app) => {
                  const sharePct = Math.min(
                    100,
                    Math.max(8, Math.round((app.seconds / Math.max(60, activeSlot.totalSeconds)) * 100))
                  );
                  return (
                    <div
                      key={app.packageName}
                      className="rounded-lg border border-white/[0.05] bg-zinc-900/70 px-2.5 py-1.5"
                    >
                      <div className="flex items-center justify-between gap-2 text-[11px]">
                        <span className="truncate font-medium text-zinc-200">{app.appName}</span>
                        <span className="shrink-0 font-mono text-[10px] text-zinc-400">
                          {formatShortDuration(app.seconds)}
                        </span>
                      </div>
                      <div className="mt-1 h-1 w-full overflow-hidden rounded-full bg-zinc-800">
                        <div
                          className={`h-full rounded-full ${
                            activeSlot.isNight
                              ? 'bg-violet-400'
                              : activeSlot.isSchoolHour
                                ? 'bg-sky-400'
                                : 'bg-emerald-400'
                          }`}
                          style={{ width: `${sharePct}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="mt-2 text-[11px] text-zinc-500">
                Aucune activité applicative enregistrée sur cette tranche horaire.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
