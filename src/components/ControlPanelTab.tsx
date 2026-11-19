import React, { useEffect, useState } from 'react';
import {
  AlertTriangle,
  BookOpen,
  Check,
  Clock,
  Eye,
  EyeOff,
  KeyRound,
  Lock,
  MapPin,
  Moon,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Sliders,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react';
import type {
  AppUsageEntry,
  DataScreenTimeMsg,
  DeviceTelemetry,
  GpsTrackingMode,
  SharedLocationData,
} from '../types/sentinel';

export interface ParentalCommonProps {
  screenTime: DataScreenTimeMsg | null;
  screenTimeLoading: boolean;
  telemetry: DeviceTelemetry | null;
  nowMs: number;
  feedback: { ok: boolean; text: string } | null;
  onDismissFeedback: () => void;
  onRefresh: () => Promise<void>;
}

interface ParentalHomeTabProps extends ParentalCommonProps {
  onLockScreenNow: () => Promise<void>;
  onSetScreenPause: (minutes: number) => Promise<void>;
  onApplyAppLock: (app: AppUsageEntry, minutes: number, explicitUntilMs?: number) => Promise<void>;
  onClearAllAppLocks: () => Promise<void>;
  onSetAppQuota: (app: AppUsageEntry, dailyLimitMinutes: number) => Promise<void>;
  onSetSchoolMode: (opts: { durationMinutes?: number; allowedPackages?: string[] }) => Promise<void>;
  onRespondTimeRequest: (timeRequestId: string, approve: boolean, grantMinutes?: number) => Promise<void>;
  onOpenRulesTab: () => void;
}

interface ParentalRulesTabProps extends ParentalCommonProps {
  onSetCurfew: (opts: { enabled: boolean; startMin: number; endMin: number; daysMask: number }) => Promise<void>;
  onSetSchoolMode: (opts: { durationMinutes?: number; allowedPackages?: string[] }) => Promise<void>;
  onSetAntiBypass: (enabled: boolean) => Promise<void>;
  onSetLostMode: (opts: {
    enabled: boolean;
    message?: string;
    phoneNumber?: string;
    ringNow?: boolean;
  }) => Promise<void>;
  onClearSecurityEvents: () => Promise<void>;
  onSetAdminPin: (pin: string) => Promise<void>;
  onSetAiShield: (opts: { blurFaces?: boolean; blurNsfw?: boolean; nsfwStrict?: boolean }) => Promise<void>;
  onSetRemotePolicy: (opts: {
    allowLocation?: boolean;
    allowLocationHistory?: boolean;
    gpsTrackingMode?: GpsTrackingMode;
    allowContacts?: boolean;
    allowSmsRelay?: boolean;
    allowGallery?: boolean;
    allowIntercom?: boolean;
    allowDeviceInfo?: boolean;
  }) => Promise<void>;
}

interface GeofenceZonesCardProps {
  screenTime: DataScreenTimeMsg | null;
  locationData: SharedLocationData | null;
  onSaveGeofence: (opts: {
    id?: string;
    name?: string;
    latitude?: number;
    longitude?: number;
    radiusMeters?: number;
    delete?: boolean;
  }) => Promise<void>;
  onRequestLocation: () => Promise<void>;
}

const DAY_LABELS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];

function formatUsageDuration(totalSec: number): string {
  const s = Math.max(0, Math.floor(totalSec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${m.toString().padStart(2, '0')}m`;
  if (m > 0) return `${m} min`;
  return s > 0 ? '< 1 min' : '0 min';
}

function formatCountdown(targetMs: number | null, nowMs: number): string | null {
  if (!targetMs || targetMs <= nowMs) return null;
  const totalSec = Math.floor((targetMs - nowMs) / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m.toString().padStart(2, '0')}m`;
  return `${m}m ${s.toString().padStart(2, '0')}s`;
}

function minToTimeStr(min: number): string {
  const h = Math.floor(min / 60) % 24;
  const m = min % 60;
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`;
}

function timeStrToMin(str: string, fallback: number): number {
  const parts = str.split(':');
  if (parts.length !== 2) return fallback;
  const h = parseInt(parts[0], 10);
  const m = parseInt(parts[1], 10);
  if (Number.isNaN(h) || Number.isNaN(m)) return fallback;
  return Math.max(0, Math.min(1439, h * 60 + m));
}

function getUntilMidnightMs(): number {
  const d = new Date();
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

/* ==================== TAB 1: PARENTAL HOME DASHBOARD ==================== */
export const ParentalHomeTab: React.FC<ParentalHomeTabProps> = ({
  screenTime,
  screenTimeLoading,
  telemetry,
  nowMs,
  feedback,
  onDismissFeedback,
  onRefresh,
  onLockScreenNow,
  onSetScreenPause,
  onApplyAppLock,
  onClearAllAppLocks,
  onSetAppQuota,
  onSetSchoolMode,
  onRespondTimeRequest,
  onOpenRulesTab,
}) => {
  const [appSearch, setAppSearch] = useState('');
  const [expandedPkg, setExpandedPkg] = useState<string | null>(null);
  const [customLockMins, setCustomLockMins] = useState('45');
  const [pauseMenuOpen, setPauseMenuOpen] = useState(false);
  const [customPauseMins, setCustomPauseMins] = useState('30');

  const pausedUntil = screenTime?.screenPausedUntilMs ?? telemetry?.screenPausedUntilMs ?? 0;
  const isPaused = pausedUntil > nowMs;
  const schoolUntil = screenTime?.schoolMode?.untilMs ?? telemetry?.schoolModeUntilMs ?? 0;
  const isSchoolActive = schoolUntil > nowMs;
  const lostActive = screenTime?.lostMode?.enabled ?? telemetry?.lostModeEnabled ?? false;
  const curfewActive = screenTime?.curfew?.enabled ?? telemetry?.curfewEnabled ?? false;
  const antiBypassActive = screenTime?.blockSettingsAndStore ?? telemetry?.blockSettingsAndStore ?? false;
  const pendingReqs = screenTime?.pendingRequests ?? [];
  const blockedApps = (screenTime?.apps ?? []).filter((a) => a.blockedUntilMs > nowMs || a.quotaExceeded);

  return (
    <div className="space-y-4">
      {feedback && (
        <div
          className={`flex items-center justify-between gap-2 rounded-xl border px-4 py-2.5 text-xs ${
            feedback.ok
              ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
              : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
          }`}
        >
          <span>{feedback.text}</span>
          <button type="button" onClick={onDismissFeedback} className="text-zinc-400 hover:text-white">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Missing Android Permissions Compact Bar */}
      {screenTime && (!screenTime.hasUsageAccess || !screenTime.hasOverlayPermission || !screenTime.deviceAdminActive) && (
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-amber-500/25 bg-amber-500/[0.08] px-3.5 py-2.5 text-[11px] text-amber-200">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-400" />
          <span className="font-medium">À activer sur le téléphone :</span>
          {!screenTime.deviceAdminActive && <span className="rounded bg-black/30 px-2 py-0.5">Admin Android</span>}
          {!screenTime.hasUsageAccess && <span className="rounded bg-black/30 px-2 py-0.5">Temps d’écran</span>}
          {!screenTime.hasOverlayPermission && <span className="rounded bg-black/30 px-2 py-0.5">Écran de blocage</span>}
        </div>
      )}

      {/* Pending "+15 min" Requests */}
      {pendingReqs.length > 0 && (
        <div className="space-y-2 rounded-2xl border border-emerald-500/35 bg-emerald-500/10 p-3.5">
          {pendingReqs.map((req) => (
            <div key={req.id} className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <div className="text-emerald-100">
                <span className="font-semibold">Demande +{req.requestedMinutes} min</span> · {req.appName}
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => void onRespondTimeRequest(req.id, true, req.requestedMinutes)}
                  className="inline-flex items-center gap-1 rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950 hover:bg-zinc-200"
                >
                  <Check className="h-3 w-3" />
                  Accepter
                </button>
                <button
                  type="button"
                  onClick={() => void onRespondTimeRequest(req.id, false)}
                  className="rounded-lg border border-white/15 bg-zinc-950/70 px-2.5 py-1 text-[11px] text-zinc-300 hover:text-white"
                >
                  Refuser
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Hero Summary: Today's Screen Time + 7-Day Mini Chart */}
      <div className="grid gap-4 rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-5 md:grid-cols-[1fr_1.35fr]">
        <div className="flex flex-col justify-between gap-3">
          <div>
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-zinc-400">Temps d’écran aujourd’hui</span>
              <button
                type="button"
                disabled={screenTimeLoading}
                onClick={() => void onRefresh()}
                className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] text-zinc-400 transition hover:bg-white/[0.05] hover:text-white"
                title="Actualiser"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${screenTimeLoading ? 'animate-spin' : ''}`} />
                <span>Actualiser</span>
              </button>
            </div>
            <div className="mt-1.5 font-mono text-3xl font-semibold tracking-tight text-white">
              {formatUsageDuration(screenTime?.totalScreenTimeSec ?? 0)}
            </div>
            <div className="mt-1 text-xs text-zinc-400">
              {screenTime?.currentAppName ? (
                <>
                  Application ouverte : <span className="font-medium text-emerald-300">{screenTime.currentAppName}</span>
                </>
              ) : (
                'Écran en veille ou accueil'
              )}
            </div>
          </div>

          {/* Active Protections Compact Pills */}
          <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
            {curfewActive && (
              <span className="rounded-md bg-white/[0.06] px-2 py-0.5 text-zinc-300">
                Couvre-feu {minToTimeStr(screenTime?.curfew?.startMin ?? 1290)}–{minToTimeStr(screenTime?.curfew?.endMin ?? 420)}
              </span>
            )}
            {antiBypassActive && (
              <span className="rounded-md bg-emerald-500/15 px-2 py-0.5 text-emerald-300">
                Paramètres verrouillés
              </span>
            )}
            {lostActive && (
              <span className="rounded-md bg-rose-500/20 px-2 py-0.5 font-medium text-rose-200">
                Mode Perdu actif
              </span>
            )}
          </div>
        </div>

        {/* 7-Day Bar Chart */}
        <div className="border-t border-white/[0.06] pt-3 md:border-l md:border-t-0 md:pl-5 md:pt-0">
          <div className="text-[11px] font-medium text-zinc-500">7 derniers jours</div>
          {screenTime?.weeklyHistory && screenTime.weeklyHistory.length > 0 ? (
            <div className="mt-2.5 grid grid-cols-7 items-end gap-1.5">
              {(() => {
                const maxSec = Math.max(1800, ...screenTime.weeklyHistory.map((d) => d.totalSeconds));
                return screenTime.weeklyHistory.map((day) => {
                  const pct = Math.max(8, Math.round((day.totalSeconds / maxSec) * 100));
                  return (
                    <div key={day.dateKey} className="flex flex-col items-center gap-1">
                      <span className="font-mono text-[10px] text-zinc-500">
                        {day.totalSeconds >= 3600
                          ? `${(day.totalSeconds / 3600).toFixed(1)}h`
                          : `${Math.round(day.totalSeconds / 60)}m`}
                      </span>
                      <div className="flex h-16 w-full items-end rounded-md bg-zinc-950/90 p-0.5">
                        <div
                          className={`w-full rounded ${day.isToday ? 'bg-emerald-400' : 'bg-zinc-600'}`}
                          style={{ height: `${pct}%` }}
                        />
                      </div>
                      <span className={`text-[10px] ${day.isToday ? 'font-semibold text-white' : 'text-zinc-500'}`}>
                        {day.dayLabel}
                      </span>
                    </div>
                  );
                });
              })()}
            </div>
          ) : (
            <div className="flex h-24 items-center justify-center text-xs text-zinc-500">
              {screenTimeLoading ? 'Chargement…' : 'En attente des données'}
            </div>
          )}
        </div>
      </div>

      {/* 4 Crisp Quick Actions */}
      <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-3.5">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <button
            type="button"
            onClick={() => void onLockScreenNow()}
            className="flex items-center justify-center gap-2 rounded-xl bg-white px-3 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
          >
            <Lock className="h-3.5 w-3.5" />
            <span>Verrouiller</span>
          </button>

          {isPaused ? (
            <button
              type="button"
              onClick={() => void onSetScreenPause(0)}
              className="flex items-center justify-center gap-1.5 rounded-xl border border-rose-500/40 bg-rose-500/15 px-3 py-2.5 text-xs font-medium text-rose-200 hover:bg-rose-500/25"
            >
              <Clock className="h-3.5 w-3.5" />
              <span>Pause · {formatCountdown(pausedUntil, nowMs)}</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setPauseMenuOpen((v) => !v)}
              className={`flex items-center justify-center gap-2 rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
                pauseMenuOpen
                  ? 'border-white/25 bg-white/10 text-white'
                  : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
              }`}
            >
              <Clock className="h-3.5 w-3.5 text-zinc-400" />
              <span>Mettre en pause</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => void onSetSchoolMode({ durationMinutes: isSchoolActive ? 0 : 120 })}
            className={`flex items-center justify-center gap-2 rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
              isSchoolActive
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
            }`}
          >
            <BookOpen className="h-3.5 w-3.5" />
            <span>{isSchoolActive ? `École · ${formatCountdown(schoolUntil, nowMs)}` : 'Mode École'}</span>
          </button>

          <button
            type="button"
            onClick={onOpenRulesTab}
            className={`flex items-center justify-center gap-2 rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
              lostActive
                ? 'border-rose-500/50 bg-rose-500/20 text-rose-200'
                : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
            }`}
          >
            <ShieldCheck className="h-3.5 w-3.5 text-zinc-400" />
            <span>{lostActive ? 'Mode Perdu ON' : 'Règles & Sécurité'}</span>
          </button>
        </div>

        {pauseMenuOpen && !isPaused && (
          <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-white/[0.06] pt-3">
            <span className="text-xs text-zinc-400">Durée de pause :</span>
            {[15, 30, 60, 120].map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => {
                  setPauseMenuOpen(false);
                  void onSetScreenPause(m);
                }}
                className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200 hover:border-white/25"
              >
                {m >= 60 ? `${m / 60} h` : `${m} min`}
              </button>
            ))}
            <input
              type="number"
              min={1}
              max={1440}
              value={customPauseMins}
              onChange={(e) => setCustomPauseMins(e.target.value)}
              className="w-14 rounded-lg border border-white/10 bg-zinc-950 px-2 py-1 font-mono text-xs text-white"
            />
            <button
              type="button"
              onClick={() => {
                const m = parseInt(customPauseMins, 10);
                if (m > 0) {
                  setPauseMenuOpen(false);
                  void onSetScreenPause(m);
                }
              }}
              className="rounded-lg bg-white px-2.5 py-1 text-xs font-semibold text-zinc-950"
            >
              OK
            </button>
          </div>
        )}
      </div>

      {/* Applications, Timed Locks & Daily Quotas */}
      <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-white">Applications & Limites</h2>
          {blockedApps.length > 0 && (
            <button
              type="button"
              onClick={() => void onClearAllAppLocks()}
              className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-[11px] font-medium text-zinc-200 hover:border-white/25"
            >
              Tout débloquer ({blockedApps.length})
            </button>
          )}
        </div>

        <div className="relative mt-3">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
          <input
            type="text"
            value={appSearch}
            onChange={(e) => setAppSearch(e.target.value)}
            placeholder="Rechercher une application (YouTube, TikTok, Instagram…)"
            className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-9 pr-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
          />
        </div>

        {screenTimeLoading && !screenTime ? (
          <div className="flex items-center justify-center py-10 text-xs text-zinc-400">
            <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
            Chargement des applications…
          </div>
        ) : (
          <div className="mt-3 max-h-[50vh] space-y-1.5 overflow-y-auto pr-1">
            {(screenTime?.apps ?? [])
              .filter((a) => {
                const q = appSearch.trim().toLowerCase();
                return !q || a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q);
              })
              .map((app) => {
                const isTimedBlocked = app.blockedUntilMs > nowMs;
                const isQuotaHit = Boolean(app.quotaExceeded);
                const hasQuota = (app.dailyLimitMinutes ?? 0) > 0;
                const maxUsage = Math.max(60, ...(screenTime?.apps ?? []).map((x) => x.usageSecondsToday));
                const pct = Math.min(100, Math.round((app.usageSecondsToday / maxUsage) * 100));
                const isOpen = expandedPkg === app.packageName;

                return (
                  <div
                    key={app.packageName}
                    className={`rounded-xl border px-3 py-2.5 transition ${
                      isTimedBlocked || isQuotaHit
                        ? 'border-rose-500/35 bg-rose-500/[0.07]'
                        : 'border-white/[0.05] bg-zinc-950/75'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-xs font-medium text-white">{app.appName}</span>
                          <span className="font-mono text-[11px] text-zinc-400">
                            {formatUsageDuration(app.usageSecondsToday)}
                          </span>
                          {hasQuota && (
                            <span
                              className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${
                                isQuotaHit
                                  ? 'bg-amber-500/20 text-amber-200'
                                  : 'bg-white/[0.06] text-zinc-300'
                              }`}
                            >
                              Limite {app.dailyLimitMinutes}m/j
                            </span>
                          )}
                          {isTimedBlocked && (
                            <span className="rounded bg-rose-500/20 px-1.5 py-0.5 font-mono text-[10px] text-rose-200">
                              Bloqué · {formatCountdown(app.blockedUntilMs, nowMs)}
                            </span>
                          )}
                        </div>
                        {app.usageSecondsToday > 0 && (
                          <div className="mt-1.5 h-1 w-full max-w-xs overflow-hidden rounded-full bg-zinc-800">
                            <div
                              className={`h-full rounded-full ${
                                isTimedBlocked || isQuotaHit ? 'bg-rose-400' : 'bg-emerald-400/80'
                              }`}
                              style={{ width: `${Math.max(4, pct)}%` }}
                            />
                          </div>
                        )}
                      </div>

                      <div className="flex shrink-0 items-center gap-1.5">
                        {isTimedBlocked && (
                          <button
                            type="button"
                            onClick={() => void onApplyAppLock(app, 0)}
                            className="rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950 hover:bg-zinc-200"
                          >
                            Débloquer
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => setExpandedPkg(isOpen ? null : app.packageName)}
                          className="rounded-lg border border-white/10 bg-white/[0.04] px-2.5 py-1 text-[11px] text-zinc-300 hover:text-white"
                        >
                          {isOpen ? 'Fermer' : 'Gérer'}
                        </button>
                      </div>
                    </div>

                    {isOpen && (
                      <div className="mt-2.5 space-y-2.5 border-t border-white/[0.06] pt-2.5 text-[11px]">
                        <div className="space-y-1.5 sm:flex sm:flex-wrap sm:items-center sm:gap-1.5 sm:space-y-0">
                          <span className="block text-zinc-400 sm:w-24">Verrouiller :</span>
                          <div className="flex flex-wrap items-center gap-1.5">
                            {[15, 30, 60, 120].map((m) => (
                              <button
                                key={m}
                                type="button"
                                onClick={() => {
                                  setExpandedPkg(null);
                                  void onApplyAppLock(app, m);
                                }}
                                className="rounded-lg border border-rose-500/30 bg-rose-500/15 px-2.5 py-1.5 text-rose-200 hover:bg-rose-500/25"
                              >
                                {m >= 60 ? `${m / 60} h` : `${m} min`}
                              </button>
                            ))}
                            <button
                              type="button"
                              onClick={() => {
                                setExpandedPkg(null);
                                void onApplyAppLock(app, 0, getUntilMidnightMs());
                              }}
                              className="rounded-lg border border-rose-500/30 bg-rose-500/15 px-2.5 py-1.5 text-rose-200 hover:bg-rose-500/25"
                            >
                              Minuit
                            </button>
                            <div className="inline-flex items-center gap-1">
                              <input
                                type="number"
                                min={1}
                                max={1440}
                                value={customLockMins}
                                onChange={(e) => setCustomLockMins(e.target.value)}
                                className="w-12 rounded-lg border border-white/10 bg-zinc-900 px-1.5 py-1.5 font-mono text-white"
                              />
                              <button
                                type="button"
                                onClick={() => {
                                  const m = parseInt(customLockMins, 10);
                                  if (m > 0) {
                                    setExpandedPkg(null);
                                    void onApplyAppLock(app, m);
                                  }
                                }}
                                className="rounded-lg bg-rose-500 px-2.5 py-1.5 font-semibold text-white"
                              >
                                min
                              </button>
                            </div>
                          </div>
                        </div>

                        <div className="space-y-1.5 sm:flex sm:flex-wrap sm:items-center sm:gap-1.5 sm:space-y-0">
                          <span className="block text-zinc-400 sm:w-24">Quota / jour :</span>
                          <div className="flex flex-wrap items-center gap-1.5">
                            {[15, 30, 60, 120].map((m) => (
                              <button
                                key={m}
                                type="button"
                                onClick={() => {
                                  setExpandedPkg(null);
                                  void onSetAppQuota(app, m);
                                }}
                                className={`rounded-lg border px-2.5 py-1.5 ${
                                  app.dailyLimitMinutes === m
                                    ? 'border-emerald-400 bg-emerald-500/20 text-emerald-200'
                                    : 'border-white/10 bg-zinc-900 text-zinc-300 hover:border-white/25'
                                }`}
                              >
                                {m >= 60 ? `${m / 60} h` : `${m} min`}
                              </button>
                            ))}
                            {hasQuota && (
                              <button
                                type="button"
                                onClick={() => {
                                  setExpandedPkg(null);
                                  void onSetAppQuota(app, 0);
                                }}
                                className="rounded-lg border border-white/10 bg-zinc-900 px-2.5 py-1.5 text-zinc-400 hover:text-white"
                              >
                                Retirer
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}

            {(screenTime?.apps?.length ?? 0) === 0 && !screenTimeLoading && (
              <div className="py-8 text-center text-xs text-zinc-500">
                Cliquez sur « Actualiser » pour charger les applications.
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

/* ==================== TAB 2: RULES, ANTI-BYPASS, LOST MODE & SECURITY LOG ==================== */
export const ParentalRulesTab: React.FC<ParentalRulesTabProps> = ({
  screenTime,
  telemetry,
  nowMs,
  feedback,
  onDismissFeedback,
  onSetCurfew,
  onSetSchoolMode,
  onSetAntiBypass,
  onSetLostMode,
  onClearSecurityEvents,
  onSetAdminPin,
  onSetAiShield,
  onSetRemotePolicy,
}) => {
  const [curfewEnabled, setCurfewEnabled] = useState(false);
  const [curfewStart, setCurfewStart] = useState('21:30');
  const [curfewEnd, setCurfewEnd] = useState('07:00');
  const [curfewMask, setCurfewMask] = useState(127);
  const [schoolSearch, setSchoolSearch] = useState('');

  const [lostMessage, setLostMessage] = useState('Téléphone perdu. Merci de me contacter.');
  const [lostPhone, setLostPhone] = useState('');
  const [lostRing, setLostRing] = useState(true);

  const [pinVal, setPinVal] = useState('');
  const [pinConfirm, setPinConfirm] = useState('');
  const [pinShow, setPinShow] = useState(false);
  const [pinBusy, setPinBusy] = useState(false);
  const [pinLocalErr, setPinLocalErr] = useState<string | null>(null);

  useEffect(() => {
    if (!screenTime) return;
    if (screenTime.curfew) {
      setCurfewEnabled(screenTime.curfew.enabled);
      setCurfewStart(minToTimeStr(screenTime.curfew.startMin));
      setCurfewEnd(minToTimeStr(screenTime.curfew.endMin));
      setCurfewMask(screenTime.curfew.daysMask);
    }
    if (screenTime.lostMode) {
      if (screenTime.lostMode.message) setLostMessage(screenTime.lostMode.message);
      setLostPhone(screenTime.lostMode.phoneNumber || '');
    }
  }, [screenTime]);

  const schoolUntil = screenTime?.schoolMode?.untilMs ?? telemetry?.schoolModeUntilMs ?? 0;
  const isSchoolActive = schoolUntil > nowMs;
  const lostActive = screenTime?.lostMode?.enabled ?? telemetry?.lostModeEnabled ?? false;
  const antiBypassActive = screenTime?.blockSettingsAndStore ?? telemetry?.blockSettingsAndStore ?? false;
  const securityEvents = screenTime?.securityEvents ?? [];

  const handleToggleSchoolPkg = async (pkg: string) => {
    const current = new Set(screenTime?.schoolMode?.allowedPackages ?? []);
    if (current.has(pkg)) current.delete(pkg);
    else current.add(pkg);
    await onSetSchoolMode({ allowedPackages: Array.from(current) });
  };

  const handleSubmitPin = async (e: React.FormEvent) => {
    e.preventDefault();
    setPinLocalErr(null);
    const p = pinVal.trim();
    if (p.length < 4 || p.length > 12) {
      setPinLocalErr('4 à 12 caractères requis.');
      return;
    }
    if (p !== pinConfirm.trim()) {
      setPinLocalErr('Les deux codes ne correspondent pas.');
      return;
    }
    setPinBusy(true);
    try {
      await onSetAdminPin(p);
      setPinVal('');
      setPinConfirm('');
    } finally {
      window.setTimeout(() => setPinBusy(false), 600);
    }
  };

  return (
    <div className="space-y-4">
      {feedback && (
        <div
          className={`flex items-center justify-between gap-2 rounded-xl border px-4 py-2.5 text-xs ${
            feedback.ok
              ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
              : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
          }`}
        >
          <span>{feedback.text}</span>
          <button type="button" onClick={onDismissFeedback} className="text-zinc-400 hover:text-white">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        {/* Card 0A: Local On-Device AI Face & NSFW Mosaic Pixelation Shield */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2">
              <Sparkles className="h-4 w-4 shrink-0 text-emerald-400" />
              <div className="min-w-0">
                <h3 className="text-sm font-semibold text-white">Filtre IA Visages & NSFW</h3>
                <p className="text-[11px] text-zinc-400">
                  Pixellisation mosaïque locale temps réel (ML Kit + NudeNet)
                </p>
              </div>
            </div>
            <span
              className={`shrink-0 rounded-md px-2 py-0.5 text-[10px] font-medium ${
                telemetry?.aiShieldRunning
                  ? 'bg-emerald-500/15 text-emerald-300'
                  : telemetry?.aiShieldNeedsConsent
                  ? 'bg-amber-500/15 text-amber-200'
                  : 'bg-white/[0.06] text-zinc-400'
              }`}
            >
              {telemetry?.aiShieldRunning
                ? 'Actif'
                : telemetry?.aiShieldNeedsConsent
                ? 'Validation requise'
                : 'Inactif'}
            </span>
          </div>

          <div className="mt-3.5 divide-y divide-white/[0.06]">
            <div className="flex items-center justify-between gap-2 py-2.5">
              <div className="min-w-0">
                <div className="text-xs font-medium text-white">Pixellisation anti-visages</div>
                <div className="text-[11px] text-zinc-500">Mosaïque couleur sur contours organiques (60 FPS)</div>
              </div>
              <button
                type="button"
                onClick={() => void onSetAiShield({ blurFaces: !telemetry?.aiBlurFaces })}
                className={`shrink-0 rounded-lg border px-3 py-1.5 text-xs font-medium transition ${
                  telemetry?.aiBlurFaces
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                }`}
              >
                {telemetry?.aiBlurFaces ? 'Activé' : 'Désactivé'}
              </button>
            </div>

            <div className="flex items-center justify-between gap-2 py-2.5">
              <div className="min-w-0">
                <div className="text-xs font-medium text-white">Pixellisation zones NSFW</div>
                <div className="text-[11px] text-zinc-500">Modèle NudeNet local 100 % hors-ligne</div>
              </div>
              <button
                type="button"
                onClick={() => void onSetAiShield({ blurNsfw: !telemetry?.aiBlurNsfw })}
                className={`shrink-0 rounded-lg border px-3 py-1.5 text-xs font-medium transition ${
                  telemetry?.aiBlurNsfw
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                }`}
              >
                {telemetry?.aiBlurNsfw ? 'Activé' : 'Désactivé'}
              </button>
            </div>

            {telemetry?.aiBlurNsfw && (
              <div className="flex items-center justify-between gap-2 pt-2.5">
                <span className="text-xs text-zinc-400">Niveau strict (zones couvertes sensibles)</span>
                <button
                  type="button"
                  onClick={() => void onSetAiShield({ nsfwStrict: !telemetry?.aiNsfwStrict })}
                  className={`shrink-0 rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    telemetry?.aiNsfwStrict
                      ? 'border-amber-500/40 bg-amber-500/15 text-amber-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {telemetry?.aiNsfwStrict ? 'Strict' : 'Standard'}
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Card 0B: Remote Parent Policy Toggles */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex items-center gap-2">
            <Sliders className="h-4 w-4 text-zinc-300" />
            <div>
              <h3 className="text-sm font-semibold text-white">Politique de l’appareil</h3>
              <p className="text-[11px] text-zinc-400">Tout se pilote uniquement depuis cet espace</p>
            </div>
          </div>

          <div className="mt-3.5 grid grid-cols-2 gap-2">
            {(
              [
                { key: 'allowLocation', label: 'GPS & Trajets', active: Boolean(telemetry?.allowLocation) },
                { key: 'allowGallery', label: 'Galerie photos', active: Boolean(telemetry?.allowGallery) },
                { key: 'allowContacts', label: 'Contacts', active: Boolean(telemetry?.allowContacts) },
                { key: 'allowSmsRelay', label: 'Envoi SMS', active: Boolean(telemetry?.allowSmsRelay) },
                { key: 'allowIntercom', label: 'Haut-parleur', active: Boolean(telemetry?.allowIntercom) },
                { key: 'allowDeviceInfo', label: 'Infos système', active: Boolean(telemetry?.allowDeviceInfo) },
              ] as const
            ).map((item) => (
              <button
                key={item.key}
                type="button"
                onClick={() => void onSetRemotePolicy({ [item.key]: !item.active })}
                className={`flex items-center justify-between gap-1 rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
                  item.active
                    ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
                    : 'border-white/[0.07] bg-zinc-950 text-zinc-400 hover:text-white'
                }`}
              >
                <span className="truncate">{item.label}</span>
                <span
                  className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                    item.active ? 'bg-emerald-500/20 text-emerald-200' : 'bg-white/[0.05] text-zinc-500'
                  }`}
                >
                  {item.active ? 'ON' : 'OFF'}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Card 1: Scheduled Curfew */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <Moon className="h-4 w-4 text-zinc-300" />
              <div>
                <h3 className="text-sm font-semibold text-white">Couvre-feu / Mode Nuit</h3>
                <p className="text-[11px] text-zinc-400">Pause automatique hors-ligne</p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => setCurfewEnabled((v) => !v)}
              className={`shrink-0 rounded-lg border px-2.5 py-1 text-xs font-medium ${
                curfewEnabled
                  ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                  : 'border-white/10 bg-zinc-950 text-zinc-400'
              }`}
            >
              {curfewEnabled ? 'Actif' : 'Inactif'}
            </button>
          </div>

          <div className="mt-3.5 grid grid-cols-[1fr_auto_1fr] items-center gap-2">
            <input
              type="time"
              value={curfewStart}
              onChange={(e) => setCurfewStart(e.target.value)}
              className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white"
            />
            <span className="text-xs text-zinc-500">→</span>
            <input
              type="time"
              value={curfewEnd}
              onChange={(e) => setCurfewEnd(e.target.value)}
              className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white"
            />
          </div>

          <div className="mt-3 flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
            <div className="grid grid-cols-7 gap-1 sm:flex sm:items-center">
              {DAY_LABELS.map((lbl, idx) => {
                const on = (curfewMask & (1 << idx)) !== 0;
                return (
                  <button
                    key={idx}
                    type="button"
                    onClick={() => setCurfewMask((m) => m ^ (1 << idx))}
                    className={`flex h-8 items-center justify-center rounded-lg text-[11px] font-semibold transition sm:h-7 sm:w-7 ${
                      on
                        ? 'bg-white text-zinc-950'
                        : 'border border-white/10 bg-zinc-950 text-zinc-500'
                    }`}
                  >
                    {lbl}
                  </button>
                );
              })}
            </div>
            <button
              type="button"
              onClick={() =>
                void onSetCurfew({
                  enabled: curfewEnabled,
                  startMin: timeStrToMin(curfewStart, 21 * 60 + 30),
                  endMin: timeStrToMin(curfewEnd, 7 * 60),
                  daysMask: curfewMask,
                })
              }
              className="w-full rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 sm:w-auto sm:py-1.5"
            >
              Enregistrer
            </button>
          </div>
        </div>

        {/* Card 2: School Whitelist */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <BookOpen className="h-4 w-4 text-zinc-300" />
              <div>
                <h3 className="text-sm font-semibold text-white">Mode École (Liste blanche)</h3>
                <p className="text-[11px] text-zinc-400">Applications autorisées pendant les cours</p>
              </div>
            </div>
            <div className="flex items-center gap-1">
              {isSchoolActive ? (
                <button
                  type="button"
                  onClick={() => void onSetSchoolMode({ durationMinutes: 0 })}
                  className="rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950"
                >
                  Arrêter ({formatCountdown(schoolUntil, nowMs)})
                </button>
              ) : (
                [60, 120, 240].map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => void onSetSchoolMode({ durationMinutes: m })}
                    className="rounded-lg border border-white/10 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-300 hover:border-white/25"
                  >
                    {m / 60}h
                  </button>
                ))
              )}
            </div>
          </div>

          <input
            type="text"
            value={schoolSearch}
            onChange={(e) => setSchoolSearch(e.target.value)}
            placeholder="Filtrer les applications…"
            className="mt-3 w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-white placeholder-zinc-500"
          />
          <div className="mt-2.5 flex max-h-28 flex-wrap gap-1.5 overflow-y-auto">
            {(screenTime?.apps ?? [])
              .filter((a) => !schoolSearch.trim() || a.appName.toLowerCase().includes(schoolSearch.toLowerCase()))
              .slice(0, 36)
              .map((app) => {
                const allowed = Boolean(app.schoolAllowed);
                return (
                  <button
                    key={app.packageName}
                    type="button"
                    onClick={() => void handleToggleSchoolPkg(app.packageName)}
                    className={`rounded-lg border px-2 py-0.5 text-[11px] transition ${
                      allowed
                        ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/[0.07] bg-zinc-950 text-zinc-400 hover:text-white'
                    }`}
                  >
                    {allowed ? '✓ ' : ''}
                    {app.appName}
                  </button>
                );
              })}
          </div>
        </div>

        {/* Card 3: Anti-Bypass & Remote Admin PIN */}
        <div className="space-y-4 rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-emerald-400" />
              <div>
                <div className="text-sm font-semibold text-white">Paramètres & Play Store</div>
                <div className="text-[11px] text-zinc-400">Exige le code administrateur</div>
              </div>
            </div>
            <button
              type="button"
              onClick={() => void onSetAntiBypass(!antiBypassActive)}
              className={`shrink-0 rounded-lg border px-3 py-1.5 text-xs font-medium ${
                antiBypassActive
                  ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                  : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
              }`}
            >
              {antiBypassActive ? 'Verrouillés' : 'Libres'}
            </button>
          </div>

          <div className="border-t border-white/[0.06] pt-3.5">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <KeyRound className="h-3.5 w-3.5 text-zinc-400" />
                <span className="text-xs font-semibold text-white">Code administrateur</span>
              </div>
              <span className="text-[11px] text-zinc-400">
                {telemetry?.hasAdminPin && telemetry?.adminProtectionEnabled ? 'Actif' : 'Non défini'}
              </span>
            </div>
            {pinLocalErr && <div className="mt-1.5 text-[11px] text-rose-300">{pinLocalErr}</div>}
            <form onSubmit={(e) => void handleSubmitPin(e)} className="mt-2.5 grid gap-2 sm:grid-cols-2">
              <div className="relative">
                <input
                  type={pinShow ? 'text' : 'password'}
                  autoComplete="new-password"
                  maxLength={12}
                  value={pinVal}
                  onChange={(e) => setPinVal(e.target.value)}
                  placeholder="Nouveau code (4–12 car.)"
                  className="w-full rounded-xl border border-white/10 bg-zinc-950 py-1.5 pl-3 pr-8 text-xs text-white placeholder-zinc-500"
                />
                <button
                  type="button"
                  onClick={() => setPinShow((v) => !v)}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-zinc-300"
                >
                  {pinShow ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                </button>
              </div>
              <div className="flex gap-1.5">
                <input
                  type={pinShow ? 'text' : 'password'}
                  autoComplete="new-password"
                  maxLength={12}
                  value={pinConfirm}
                  onChange={(e) => setPinConfirm(e.target.value)}
                  placeholder="Confirmer"
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-white placeholder-zinc-500"
                />
                <button
                  type="submit"
                  disabled={pinBusy || pinVal.trim().length < 4 || pinConfirm.trim().length < 4}
                  className="shrink-0 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                >
                  OK
                </button>
              </div>
            </form>
          </div>
        </div>

        {/* Card 4: Lost Mode */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3 className="text-sm font-semibold text-white">Mode Perdu</h3>
              <p className="text-[11px] text-zinc-400">Écran bloqué + message + bouton d’appel</p>
            </div>
            {lostActive && (
              <button
                type="button"
                onClick={() => void onSetLostMode({ enabled: false })}
                className="rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950"
              >
                Désactiver
              </button>
            )}
          </div>

          <div className="mt-3 space-y-2">
            <input
              type="text"
              maxLength={200}
              value={lostMessage}
              onChange={(e) => setLostMessage(e.target.value)}
              placeholder="Message sur l’écran verrouillé"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-white placeholder-zinc-500"
            />
            <div className="flex flex-wrap items-center gap-2">
              <input
                type="tel"
                maxLength={32}
                value={lostPhone}
                onChange={(e) => setLostPhone(e.target.value)}
                placeholder="Numéro de contact"
                className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-white placeholder-zinc-500"
              />
              <label className="flex cursor-pointer items-center gap-1.5 text-xs text-zinc-400">
                <input
                  type="checkbox"
                  checked={lostRing}
                  onChange={(e) => setLostRing(e.target.checked)}
                  className="h-3.5 w-3.5 rounded border-zinc-700 bg-zinc-950"
                />
                <span>Sirène</span>
              </label>
              <button
                type="button"
                onClick={() =>
                  void onSetLostMode({
                    enabled: true,
                    message: lostMessage,
                    phoneNumber: lostPhone,
                    ringNow: lostRing,
                  })
                }
                className="rounded-xl bg-rose-500 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-rose-400"
              >
                {lostActive ? 'Mettre à jour' : 'Activer'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Security Journal */}
      <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <ShieldAlert className="h-4 w-4 text-zinc-400" />
            <h3 className="text-sm font-semibold text-white">
              Journal de sécurité ({securityEvents.length})
            </h3>
          </div>
          {securityEvents.length > 0 && (
            <button
              type="button"
              onClick={() => void onClearSecurityEvents()}
              className="text-xs text-zinc-400 hover:text-rose-300"
            >
              Effacer
            </button>
          )}
        </div>

        {securityEvents.length === 0 ? (
          <div className="py-6 text-center text-xs text-zinc-500">Aucun événement enregistré.</div>
        ) : (
          <div className="mt-3 max-h-64 space-y-2 overflow-y-auto pr-1">
            {securityEvents.map((ev) => (
              <div
                key={ev.id}
                className="flex items-start justify-between gap-3 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span
                      className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                        ev.kind === 'intruder' || ev.kind === 'sim'
                          ? 'bg-rose-500/20 text-rose-300'
                          : ev.kind === 'geofence'
                          ? 'bg-emerald-500/15 text-emerald-300'
                          : ev.kind === 'battery' || ev.kind === 'bypass'
                          ? 'bg-amber-500/20 text-amber-300'
                          : 'bg-white/[0.07] text-zinc-300'
                      }`}
                    >
                      {ev.kind}
                    </span>
                    <span className="text-xs font-medium text-white">{ev.title}</span>
                    <span className="font-mono text-[10px] text-zinc-500">
                      {new Date(ev.timestamp).toLocaleString('fr-FR', {
                        day: '2-digit',
                        month: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  </div>
                  <p className="mt-0.5 text-xs text-zinc-400">{ev.detail}</p>
                </div>

                {ev.photoBase64 && (
                  <a
                    href={`data:image/jpeg;base64,${ev.photoBase64}`}
                    download={`intrusion-${ev.timestamp}.jpg`}
                    className="shrink-0 overflow-hidden rounded-lg border border-rose-500/40"
                    title="Télécharger la capture"
                  >
                    <img
                      src={`data:image/jpeg;base64,${ev.photoBase64}`}
                      alt="Capture"
                      className="h-12 w-12 object-cover"
                    />
                  </a>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

/* ==================== GEOFENCING CARD (EMBEDDED IN POSITION TAB) ==================== */
export const GeofenceZonesCard: React.FC<GeofenceZonesCardProps> = ({
  screenTime,
  locationData,
  onSaveGeofence,
  onRequestLocation,
}) => {
  const [geoName, setGeoName] = useState('Maison');
  const [geoRadius, setGeoRadius] = useState(150);
  const [geoLat, setGeoLat] = useState('');
  const [geoLng, setGeoLng] = useState('');

  useEffect(() => {
    if (locationData && !geoLat && !geoLng) {
      setGeoLat(locationData.latitude.toFixed(5));
      setGeoLng(locationData.longitude.toFixed(5));
    }
  }, [locationData, geoLat, geoLng]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    const lat = parseFloat(geoLat.replace(',', '.'));
    const lng = parseFloat(geoLng.replace(',', '.'));
    if (Number.isNaN(lat) || Number.isNaN(lng)) return;
    await onSaveGeofence({
      name: geoName.trim() || 'Zone',
      latitude: lat,
      longitude: lng,
      radiusMeters: geoRadius,
    });
    setGeoName('');
  };

  return (
    <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <MapPin className="h-4 w-4 text-emerald-400" />
          <div>
            <h3 className="text-sm font-semibold text-white">Zones de sécurité</h3>
            <p className="text-[11px] text-zinc-400">Alerte locale à l’entrée et à la sortie</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => {
            void onRequestLocation();
            if (locationData) {
              setGeoLat(locationData.latitude.toFixed(5));
              setGeoLng(locationData.longitude.toFixed(5));
            }
          }}
          className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-[11px] text-zinc-300 hover:text-white"
        >
          Utiliser la position actuelle
        </button>
      </div>

      {(screenTime?.geofences?.length ?? 0) > 0 && (
        <div className="mt-3 space-y-1.5">
          {screenTime?.geofences?.map((z) => (
            <div
              key={z.id}
              className="flex items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950/75 px-3 py-2 text-xs"
            >
              <div className="flex items-center gap-2">
                <span className="font-medium text-white">{z.name}</span>
                <span className="font-mono text-[11px] text-zinc-500">{z.radiusMeters} m</span>
                {z.inside !== null && (
                  <span
                    className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                      z.inside
                        ? 'bg-emerald-500/15 text-emerald-300'
                        : 'bg-amber-500/15 text-amber-300'
                    }`}
                  >
                    {z.inside ? 'Dans la zone' : 'Hors zone'}
                  </span>
                )}
              </div>
              <button
                type="button"
                onClick={() => void onSaveGeofence({ id: z.id, delete: true })}
                className="text-zinc-500 hover:text-rose-400"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}

      <form onSubmit={(e) => void handleCreate(e)} className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <input
          type="text"
          value={geoName}
          onChange={(e) => setGeoName(e.target.value)}
          placeholder="Nom (Maison, École)"
          className="col-span-2 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white sm:col-span-1 sm:py-1.5"
        />
        <input
          type="text"
          value={geoLat}
          onChange={(e) => setGeoLat(e.target.value)}
          placeholder="Latitude"
          className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white sm:py-1.5"
        />
        <input
          type="text"
          value={geoLng}
          onChange={(e) => setGeoLng(e.target.value)}
          placeholder="Longitude"
          className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white sm:py-1.5"
        />
        <div className="col-span-2 flex gap-1.5 sm:col-span-1">
          <select
            value={geoRadius}
            onChange={(e) => setGeoRadius(parseInt(e.target.value, 10))}
            className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 text-xs text-white sm:py-1.5"
          >
            <option value={100}>100 m</option>
            <option value={150}>150 m</option>
            <option value={300}>300 m</option>
            <option value={500}>500 m</option>
          </select>
          <button
            type="submit"
            disabled={!geoLat || !geoLng}
            className="inline-flex items-center justify-center rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35 sm:py-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
          </button>
        </div>
      </form>
    </div>
  );
};
