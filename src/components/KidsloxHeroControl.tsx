import React from 'react';
import {
  AlertTriangle,
  Battery,
  BatteryCharging,
  BellRing,
  BookOpen,
  CloudOff,
  Lock,
  ShieldCheck,
  Snowflake,
  Unlock,
  Wifi,
} from 'lucide-react';
import type { DataScreenTimeMsg, DeviceTelemetry, PortalOperationalState } from '../types/sentinel';

interface KidsloxHeroControlProps {
  deviceName: string;
  portalState: PortalOperationalState;
  telemetry: DeviceTelemetry | null;
  screenTime: DataScreenTimeMsg | null;
  nowMs: number;
  offlineQueueCount: number;
  onSelectMode: (mode: 'locked' | 'child' | 'parent') => Promise<void>;
  onToggleReadOnly: (minutes: number) => Promise<void>;
  onToggleSchoolMode: (minutes: number) => Promise<void>;
  onLockScreenNow: () => Promise<void>;
  onRingDevice: () => Promise<void>;
  onOpenShieldTab: () => void;
}

function formatShortRemaining(untilMs: number | undefined, nowMs: number): string | null {
  if (!untilMs || untilMs <= nowMs) return null;
  const totalSec = Math.floor((untilMs - nowMs) / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  if (h > 0) return `${h}h${m.toString().padStart(2, '0')}`;
  return `${Math.max(1, m)} min`;
}

/**
 * Iconic Kidslox-Inspired Tri-Mode Hero Card:
 * - Signature 3-state control pill: [Verrouillé] | [Enfant] | [Parent]
 * - Live Shield Score /100, Battery, Network, Offline Queue (#22), and Silence Radio (#8) warning!
 * - 1-tap quick controls: Read-Only Screen Freeze (#31), School Mode, Ring, Lock Screen.
 */
export const KidsloxHeroControl: React.FC<KidsloxHeroControlProps> = ({
  deviceName,
  portalState,
  telemetry,
  screenTime,
  nowMs,
  offlineQueueCount,
  onSelectMode,
  onToggleReadOnly,
  onToggleSchoolMode,
  onLockScreenNow,
  onRingDevice,
  onOpenShieldTab,
}) => {
  const pausedUntil = screenTime?.screenPausedUntilMs ?? telemetry?.screenPausedUntilMs ?? 0;
  const parentFreeUntil = screenTime?.parentFreeModeUntilMs ?? 0;
  const readOnlyUntil = screenTime?.readOnlyUntilMs ?? 0;
  const schoolUntil = screenTime?.schoolMode?.untilMs ?? telemetry?.schoolModeUntilMs ?? 0;

  const isLockedMode = pausedUntil > nowMs || Boolean(screenTime?.lostMode?.enabled);
  const isParentMode = !isLockedMode && parentFreeUntil > nowMs;
  const activeMode: 'locked' | 'child' | 'parent' = isLockedMode
    ? 'locked'
    : isParentMode
      ? 'parent'
      : 'child';

  const isReadOnly = readOnlyUntil > nowMs;
  const isSchool = schoolUntil > nowMs;
  const isOnline = portalState !== 'offline' && portalState !== 'connecting';
  const shieldScore = screenTime?.shieldScore ?? screenTime?.systemStatus?.shieldScore;

  // #8 Silence Radio detection: warn if offline > 30 min since last telemetry update
  const offlineMinutes =
    !isOnline && telemetry?.updatedAt
      ? Math.floor((nowMs - telemetry.updatedAt) / 60_000)
      : 0;
  const silenceRadioWarning = !isOnline && offlineMinutes >= 30;

  const initials = (deviceName || 'E').trim().slice(0, 2).toUpperCase();

  return (
    <div className="kl-card overflow-hidden">
      {/* Top Identity + Live Health Strip */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3.5 dark:border-white/[0.06] sm:px-5">
        <div className="flex min-w-0 items-center gap-3">
          <div
            className={`relative flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl text-sm font-bold text-white kl-solid-white shadow-sm ${
              activeMode === 'locked'
                ? 'bg-gradient-to-br from-rose-500 to-red-600'
                : activeMode === 'parent'
                  ? 'bg-gradient-to-br from-blue-500 to-indigo-600'
                  : 'bg-gradient-to-br from-emerald-500 to-teal-600'
            }`}
          >
            {initials}
            <span
              className={`absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full ring-2 ring-white dark:ring-[#111827] ${
                isOnline ? 'bg-emerald-400' : 'bg-slate-400'
              }`}
            />
          </div>

          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="truncate text-base font-bold text-slate-900 dark:text-white">
                {deviceName || 'Appareil Enfant'}
              </h1>
              <span
                className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                  isOnline
                    ? 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200 dark:bg-emerald-500/15 dark:text-emerald-300 dark:ring-emerald-500/30'
                    : 'bg-slate-100 text-slate-600 ring-1 ring-slate-200 dark:bg-white/10 dark:text-slate-300 dark:ring-white/15'
                }`}
              >
                {isOnline ? 'En ligne' : portalState === 'connecting' ? 'Connexion…' : 'Hors ligne'}
              </span>
            </div>

            <div className="mt-0.5 flex flex-wrap items-center gap-2.5 text-[11px] text-slate-500 dark:text-slate-400">
              {telemetry && (
                <span className="inline-flex items-center gap-1 font-medium">
                  {telemetry.isCharging ? (
                    <BatteryCharging className="h-3.5 w-3.5 text-emerald-500" />
                  ) : (
                    <Battery
                      className={`h-3.5 w-3.5 ${
                        telemetry.batteryLevel <= 15 ? 'text-rose-500' : 'text-slate-400'
                      }`}
                    />
                  )}
                  {telemetry.batteryLevel}%
                </span>
              )}
              {telemetry?.networkType && (
                <span className="inline-flex items-center gap-1">
                  <Wifi className="h-3 w-3 text-sky-500" />
                  {telemetry.networkType}
                </span>
              )}
              {screenTime?.currentAppName && (
                <span className="truncate font-medium text-slate-700 dark:text-slate-300">
                  Sur {screenTime.currentAppName}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Shield Protection Score Pill (#8) */}
        {typeof shieldScore === 'number' && (
          <button
            type="button"
            onClick={onOpenShieldTab}
            className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-xs font-semibold transition ${
              shieldScore >= 80
                ? 'border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 dark:border-emerald-500/30 dark:bg-emerald-500/15 dark:text-emerald-300'
                : shieldScore >= 55
                  ? 'border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100 dark:border-amber-500/30 dark:bg-amber-500/15 dark:text-amber-300'
                  : 'border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100 dark:border-rose-500/30 dark:bg-rose-500/15 dark:text-rose-300'
            }`}
            title="Voir le diagnostic du blindage et durcir la protection"
          >
            <ShieldCheck className="h-4 w-4" />
            <span>Blindage {shieldScore}/100</span>
          </button>
        )}
      </div>

      {/* #8 Silence Radio & #22 Offline Queue Banners */}
      {silenceRadioWarning && (
        <div className="flex items-center gap-2 border-b border-rose-200 bg-rose-50 px-4 py-2 text-xs text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200">
          <AlertTriangle className="h-4 w-4 shrink-0 text-rose-500" />
          <span>
            <strong>Silence radio ({offlineMinutes} min)</strong> — L’appareil ne répond plus (éteint, mode avion ou hors réseau).
          </span>
        </div>
      )}

      {offlineQueueCount > 0 && (
        <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
          <CloudOff className="h-4 w-4 shrink-0 text-amber-500" />
          <span>
            <strong>{offlineQueueCount} commande(s) en file d’attente</strong> — s’appliqueront automatiquement dès reconnexion.
          </span>
        </div>
      )}

      {/* SIGNATURE KIDSLOX 3-MODE SEGMENTED SWITCH */}
      <div className="p-4 sm:p-5">
        <div className="mb-2 flex items-center justify-between text-[11px] font-semibold uppercase tracking-wider text-slate-400">
          <span>Mode de contrôle actif</span>
          {activeMode === 'locked' && formatShortRemaining(pausedUntil, nowMs) && (
            <span className="font-mono text-rose-600 dark:text-rose-300">
              Reste {formatShortRemaining(pausedUntil, nowMs)}
            </span>
          )}
          {activeMode === 'parent' && formatShortRemaining(parentFreeUntil, nowMs) && (
            <span className="font-mono text-blue-600 dark:text-blue-300">
              Libre encore {formatShortRemaining(parentFreeUntil, nowMs)}
            </span>
          )}
        </div>

        <div className="grid grid-cols-3 gap-2 rounded-2xl bg-slate-100 p-1.5 dark:bg-slate-900">
          <button
            type="button"
            onClick={() => void onSelectMode('locked')}
            className={`flex flex-col items-center justify-center gap-1 rounded-xl px-2 py-3 text-xs font-bold transition-all ${
              activeMode === 'locked'
                ? 'bg-rose-500 text-white kl-solid-white shadow-md shadow-rose-500/30'
                : 'text-slate-600 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/5'
            }`}
          >
            <Lock className="h-4 w-4" />
            <span>Verrouillé</span>
          </button>

          <button
            type="button"
            onClick={() => void onSelectMode('child')}
            className={`flex flex-col items-center justify-center gap-1 rounded-xl px-2 py-3 text-xs font-bold transition-all ${
              activeMode === 'child'
                ? 'bg-emerald-500 text-white kl-solid-white shadow-md shadow-emerald-500/30'
                : 'text-slate-600 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/5'
            }`}
          >
            <ShieldCheck className="h-4 w-4" />
            <span>Enfant</span>
          </button>

          <button
            type="button"
            onClick={() => void onSelectMode('parent')}
            className={`flex flex-col items-center justify-center gap-1 rounded-xl px-2 py-3 text-xs font-bold transition-all ${
              activeMode === 'parent'
                ? 'bg-blue-600 text-white kl-solid-white shadow-md shadow-blue-600/30'
                : 'text-slate-600 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/5'
            }`}
          >
            <Unlock className="h-4 w-4" />
            <span>Parent (Libre)</span>
          </button>
        </div>

        {/* 1-Tap Kidslox Quick Action Row (#31 Gel Tactile, Mode École, Sonner, Verrouiller) */}
        <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <button
            type="button"
            onClick={() => void onToggleReadOnly(isReadOnly ? 0 : 15)}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition ${
              isReadOnly
                ? 'border-amber-400 bg-amber-500 text-white kl-solid-white shadow-sm'
                : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
            }`}
            title="Mode Lecture Seule : laisse voir l'écran mais bloque 100% du tactile"
          >
            <Snowflake className="h-3.5 w-3.5" />
            <span>
              {isReadOnly
                ? `Gelé (${formatShortRemaining(readOnlyUntil, nowMs) || 'ON'})`
                : 'Gel tactile 15m'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => void onToggleSchoolMode(isSchool ? 0 : 60)}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition ${
              isSchool
                ? 'border-violet-500 bg-violet-600 text-white kl-solid-white shadow-sm'
                : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
            }`}
          >
            <BookOpen className="h-3.5 w-3.5" />
            <span>
              {isSchool
                ? `École (${formatShortRemaining(schoolUntil, nowMs) || 'ON'})`
                : 'Mode École 1h'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => void onRingDevice()}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition ${
              telemetry?.ringingActive
                ? 'border-amber-400 bg-amber-500 text-white kl-solid-white animate-pulse'
                : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
            }`}
          >
            <BellRing className="h-3.5 w-3.5" />
            <span>{telemetry?.ringingActive ? 'Arrêter' : 'Faire sonner'}</span>
          </button>

          <button
            type="button"
            onClick={() => void onLockScreenNow()}
            className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200"
          >
            <Lock className="h-3.5 w-3.5" />
            <span>Éteindre écran</span>
          </button>
        </div>
      </div>
    </div>
  );
};
