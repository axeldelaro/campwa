import React, { useEffect, useState } from 'react';
import {
  AlertTriangle,
  BookOpen,
  Check,
  ChevronDown,
  ChevronUp,
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
  Unlink,
  X,
} from 'lucide-react';
import type {
  AppUsageEntry,
  DataScreenTimeMsg,
  DeviceTelemetry,
  GpsTrackingMode,
  SharedLocationData,
} from '../types/sentinel';
import { ScreenFlashCard } from './ScreenFlashCard';

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
  onRespondUnpair: (approve: boolean) => Promise<void>;
  onFlashText: (text: string, durationSec: number) => Promise<void>;
  onFlashImage: (base64Jpeg: string, durationSec: number, caption: string) => Promise<void>;
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
  onRespondUnpair: (approve: boolean) => Promise<void>;
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

/* ==================== TAB 1: ACCUEIL (CLEAN & FOCUSED) ==================== */
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
  onRespondUnpair,
  onFlashText,
  onFlashImage,
}) => {
  const [appSearch, setAppSearch] = useState('');
  const [expandedPkg, setExpandedPkg] = useState<string | null>(null);
  const [customLockMins, setCustomLockMins] = useState('45');
  const [pauseMenuOpen, setPauseMenuOpen] = useState(false);
  const [customPauseMins, setCustomPauseMins] = useState('30');
  const [healthExpanded, setHealthExpanded] = useState(false);

  const pausedUntil = screenTime?.screenPausedUntilMs ?? telemetry?.screenPausedUntilMs ?? 0;
  const isPaused = pausedUntil > nowMs;
  const schoolUntil = screenTime?.schoolMode?.untilMs ?? telemetry?.schoolModeUntilMs ?? 0;
  const isSchoolActive = schoolUntil > nowMs;
  const pendingReqs = screenTime?.pendingRequests ?? [];
  const blockedApps = (screenTime?.apps ?? []).filter((a) => a.blockedUntilMs > nowMs || a.quotaExceeded);

  const ph = telemetry?.permissionsHealth;
  const healthChecks: Array<{ key: string; label: string; ok: boolean }> = ph
    ? [
        { key: 'admin', label: 'Droits administrateur', ok: ph.deviceAdmin },
        { key: 'battery', label: 'Batterie illimitée', ok: ph.batteryUnrestricted },
        { key: 'usage', label: 'Temps d’écran', ok: ph.usageAccess },
        { key: 'overlay', label: 'Affichage superposé', ok: ph.overlay },
        { key: 'camera', label: 'Caméra', ok: ph.camera },
        { key: 'mic', label: 'Micro', ok: ph.microphone },
        { key: 'gps', label: 'Position GPS', ok: ph.location },
        { key: 'bgGps', label: 'GPS arrière-plan', ok: ph.backgroundLocation },
        { key: 'gallery', label: 'Galerie photos', ok: ph.gallery },
        { key: 'manage', label: 'Gestion photos (quarantaine)', ok: ph.storageManage },
        { key: 'readSms', label: 'Lecture SMS', ok: ph.readSms },
        { key: 'sendSms', label: 'Envoi SMS', ok: ph.sendSms },
        ...(ph.callLog !== undefined ? [{ key: 'callLog', label: 'Journal d’appels', ok: ph.callLog }] : []),
        ...(ph.callScreening !== undefined
          ? [{ key: 'callScreen', label: 'Filtre d’appels', ok: ph.callScreening }]
          : []),
      ]
    : screenTime
    ? [
        { key: 'admin', label: 'Droits administrateur', ok: Boolean(screenTime.deviceAdminActive) },
        { key: 'usage', label: 'Temps d’écran', ok: Boolean(screenTime.hasUsageAccess) },
        { key: 'overlay', label: 'Affichage superposé', ok: Boolean(screenTime.hasOverlayPermission) },
      ]
    : [];
  const missingHealth = healthChecks.filter((c) => !c.ok);

  return (
    <div className="space-y-4">
      {feedback && (
        <div
          className={`flex items-center justify-between gap-2 rounded-xl border px-3.5 py-2.5 text-xs ${
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

      {/* Pending Unpair Authorization Request from Child Device */}
      {telemetry?.unpairRequestPending && (
        <div className="flex flex-col gap-3 rounded-2xl border border-rose-500/40 bg-rose-500/10 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-start gap-2.5 text-xs">
            <Unlink className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
            <div>
              <div className="font-semibold text-rose-100">Demande de dissociation</div>
              <p className="mt-0.5 text-[11px] text-rose-200/80">
                L’appareil demande votre autorisation pour retirer la protection parentale.
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              onClick={() => void onRespondUnpair(true)}
              className="flex-1 rounded-xl bg-rose-500 px-3.5 py-2 text-xs font-semibold text-white hover:bg-rose-400 sm:flex-none"
            >
              Autoriser
            </button>
            <button
              type="button"
              onClick={() => void onRespondUnpair(false)}
              className="flex-1 rounded-xl border border-white/15 bg-zinc-950/70 px-3.5 py-2 text-xs font-medium text-zinc-200 hover:text-white sm:flex-none"
            >
              Refuser
            </button>
          </div>
        </div>
      )}

      {/* Pending "+15 min" Time Requests */}
      {pendingReqs.length > 0 && (
        <div className="space-y-2 rounded-2xl border border-emerald-500/35 bg-emerald-500/10 p-3.5">
          {pendingReqs.map((req) => (
            <div key={req.id} className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <div className="text-emerald-100">
                <span className="font-semibold">+{req.requestedMinutes} min demandées</span> · {req.appName}
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => void onRespondTimeRequest(req.id, true, req.requestedMinutes)}
                  className="inline-flex items-center gap-1 rounded-lg bg-white px-2.5 py-1.5 text-[11px] font-semibold text-zinc-950 hover:bg-zinc-200"
                >
                  <Check className="h-3 w-3" />
                  Accepter
                </button>
                <button
                  type="button"
                  onClick={() => void onRespondTimeRequest(req.id, false)}
                  className="rounded-lg border border-white/15 bg-zinc-950/70 px-2.5 py-1.5 text-[11px] text-zinc-300 hover:text-white"
                >
                  Refuser
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Remote Device Permissions Health Monitor */}
      {healthChecks.length > 0 &&
        (missingHealth.length === 0 ? (
          <div className="flex items-center justify-between gap-2 rounded-xl border border-emerald-500/20 bg-emerald-500/[0.06] px-3.5 py-2 text-[11px] text-emerald-300">
            <span className="inline-flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
              <span>
                Toutes les autorisations sont actives
                {ph?.manufacturer ? ` · ${ph.manufacturer}` : ''}
              </span>
            </span>
            <span className="font-mono text-[10px] opacity-80">
              {healthChecks.length}/{healthChecks.length}
            </span>
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-amber-500/30 bg-amber-500/[0.07]">
            <button
              type="button"
              onClick={() => setHealthExpanded((v) => !v)}
              className="flex w-full items-center justify-between gap-2 px-3.5 py-2.5 text-left text-xs text-amber-200"
            >
              <span className="inline-flex items-center gap-2">
                <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-400" />
                <span className="font-medium">
                  {missingHealth.length} autorisation{missingHealth.length > 1 ? 's' : ''} à vérifier sur le téléphone
                </span>
              </span>
              {healthExpanded ? (
                <ChevronUp className="h-3.5 w-3.5 shrink-0 text-amber-300" />
              ) : (
                <ChevronDown className="h-3.5 w-3.5 shrink-0 text-amber-300" />
              )}
            </button>
            <div className="flex flex-wrap gap-1.5 border-t border-amber-500/15 px-3.5 py-2 text-[11px]">
              {missingHealth.map((m) => (
                <span
                  key={m.key}
                  className="rounded-lg bg-black/35 px-2 py-0.5 text-amber-100"
                >
                  {m.label}
                </span>
              ))}
            </div>
            {healthExpanded && (
              <div className="border-t border-amber-500/15 bg-black/20 px-3.5 py-2.5 text-[11px] text-amber-200/80">
                Ouvrez l’application SentinelCam sur le téléphone de l’enfant et touchez chaque élément manquant pour l’activer en un clic.
              </div>
            )}
          </div>
        ))}

      {/* Hero: Screen Time + 7-Day Trend */}
      <div className="grid gap-4 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4 sm:p-5 md:grid-cols-[1fr_1.3fr]">
        <div className="flex flex-col justify-between gap-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs text-zinc-400">Temps d’écran aujourd’hui</span>
            <button
              type="button"
              disabled={screenTimeLoading}
              onClick={() => void onRefresh()}
              className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-zinc-400 transition hover:bg-white/[0.06] hover:text-white"
              title="Actualiser"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${screenTimeLoading ? 'animate-spin' : ''}`} />
            </button>
          </div>
          <div className="font-mono text-3xl font-semibold tracking-tight text-white">
            {formatUsageDuration(screenTime?.totalScreenTimeSec ?? 0)}
          </div>
          <div className="text-xs text-zinc-400">
            {screenTime?.currentAppName ? (
              <>
                En cours : <span className="font-medium text-emerald-300">{screenTime.currentAppName}</span>
              </>
            ) : (
              'En veille'
            )}
          </div>
        </div>

        {/* Minimal 7-Day Chart */}
        <div className="border-t border-white/[0.06] pt-3 md:border-l md:border-t-0 md:pl-5 md:pt-0">
          {screenTime?.weeklyHistory && screenTime.weeklyHistory.length > 0 ? (
            <div className="grid grid-cols-7 items-end gap-1.5">
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
                      <div className="flex h-14 w-full items-end rounded-md bg-zinc-950/90 p-0.5">
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
            <div className="flex h-20 items-center justify-center text-xs text-zinc-500">
              {screenTimeLoading ? 'Chargement…' : '7 derniers jours'}
            </div>
          )}
        </div>
      </div>

      {/* 3 Clean Primary Quick Actions */}
      <div className="space-y-2.5 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-3">
        <div className="grid grid-cols-3 gap-2">
          <button
            type="button"
            onClick={() => void onLockScreenNow()}
            className="flex items-center justify-center gap-1.5 rounded-xl bg-white px-2.5 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
          >
            <Lock className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">Verrouiller</span>
          </button>

          {isPaused ? (
            <button
              type="button"
              onClick={() => void onSetScreenPause(0)}
              className="flex items-center justify-center gap-1.5 rounded-xl border border-rose-500/40 bg-rose-500/15 px-2.5 py-2.5 text-xs font-medium text-rose-200"
            >
              <Clock className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">{formatCountdown(pausedUntil, nowMs)}</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setPauseMenuOpen((v) => !v)}
              className={`flex items-center justify-center gap-1.5 rounded-xl border px-2.5 py-2.5 text-xs font-medium transition ${
                pauseMenuOpen
                  ? 'border-white/25 bg-white/10 text-white'
                  : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
              }`}
            >
              <Clock className="h-3.5 w-3.5 shrink-0 text-zinc-400" />
              <span className="truncate">Pause</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => void onSetSchoolMode({ durationMinutes: isSchoolActive ? 0 : 120 })}
            className={`flex items-center justify-center gap-1.5 rounded-xl border px-2.5 py-2.5 text-xs font-medium transition ${
              isSchoolActive
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
            }`}
          >
            <BookOpen className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">
              {isSchoolActive ? formatCountdown(schoolUntil, nowMs) : 'École'}
            </span>
          </button>
        </div>

        {pauseMenuOpen && !isPaused && (
          <div className="flex flex-wrap items-center gap-1.5 border-t border-white/[0.06] pt-2.5">
            {[15, 30, 60, 120].map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => {
                  setPauseMenuOpen(false);
                  void onSetScreenPause(m);
                }}
                className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1.5 text-xs text-zinc-200 hover:border-white/25"
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
              className="w-14 rounded-lg border border-white/10 bg-zinc-950 px-2 py-1.5 font-mono text-xs text-white"
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
              className="rounded-lg bg-white px-2.5 py-1.5 text-xs font-semibold text-zinc-950"
            >
              OK
            </button>
          </div>
        )}
      </div>

      {/* Flash Message (Black Screen 3s) or Fullscreen Image Overlay */}
      <ScreenFlashCard onFlashText={onFlashText} onFlashImage={onFlashImage} />

      {/* Applications & Limits */}
      <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-white">Applications</h2>
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
            placeholder="Filtrer une application…"
            className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-9 pr-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
          />
        </div>

        {screenTimeLoading && !screenTime ? (
          <div className="flex items-center justify-center py-10 text-xs text-zinc-400">
            <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
            Chargement…
          </div>
        ) : (
          <div className="mt-3 max-h-[48vh] space-y-1.5 overflow-y-auto pr-0.5">
            {(screenTime?.apps ?? [])
              .filter((a) => {
                const q = appSearch.trim().toLowerCase();
                return !q || a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q);
              })
              .map((app) => {
                const isTimedBlocked = app.blockedUntilMs > nowMs;
                const isQuotaHit = Boolean(app.quotaExceeded);
                const hasQuota = (app.dailyLimitMinutes ?? 0) > 0;
                const isOpen = expandedPkg === app.packageName;

                return (
                  <div
                    key={app.packageName}
                    className={`rounded-xl border px-3 py-2.5 transition ${
                      isTimedBlocked || isQuotaHit
                        ? 'border-rose-500/35 bg-rose-500/[0.06]'
                        : 'border-white/[0.05] bg-zinc-950/75'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="truncate text-xs font-medium text-white">{app.appName}</span>
                          <span className="font-mono text-[11px] text-zinc-500">
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
                              {app.dailyLimitMinutes}m/j
                            </span>
                          )}
                          {isTimedBlocked && (
                            <span className="rounded bg-rose-500/20 px-1.5 py-0.5 font-mono text-[10px] text-rose-200">
                              {formatCountdown(app.blockedUntilMs, nowMs)}
                            </span>
                          )}
                        </div>
                      </div>

                      <div className="flex shrink-0 items-center gap-1.5">
                        {isTimedBlocked && (
                          <button
                            type="button"
                            onClick={() => void onApplyAppLock(app, 0)}
                            className="rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950"
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
                        <div className="space-y-1.5">
                          <span className="block text-zinc-400">Verrouiller :</span>
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
                              className="rounded-lg border border-rose-500/30 bg-rose-500/15 px-2.5 py-1.5 text-rose-200"
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

                        <div className="space-y-1.5">
                          <span className="block text-zinc-400">Quota / jour :</span>
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
                                    : 'border-white/10 bg-zinc-900 text-zinc-300'
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
                Aucune application chargée.
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

/* ==================== TAB 2: RÈGLES (SERENE 5-SECTION ACCORDION) ==================== */
type RuleSection = 'schedule' | 'security' | 'policy' | 'journal' | null;

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
  onRespondUnpair,
}) => {
  const [openSection, setOpenSection] = useState<RuleSection>(null);
  const [confirmUnpairRemote, setConfirmUnpairRemote] = useState(false);

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

  const policyItems = [
    { key: 'allowLocation', label: 'GPS & Trajets', active: Boolean(telemetry?.allowLocation) },
    { key: 'allowGallery', label: 'Galerie photos', active: Boolean(telemetry?.allowGallery) },
    { key: 'allowContacts', label: 'Contacts', active: Boolean(telemetry?.allowContacts) },
    { key: 'allowSmsRelay', label: 'Envoi SMS', active: Boolean(telemetry?.allowSmsRelay) },
    { key: 'allowIntercom', label: 'Haut-parleur', active: Boolean(telemetry?.allowIntercom) },
    { key: 'allowDeviceInfo', label: 'Infos système', active: Boolean(telemetry?.allowDeviceInfo) },
  ] as const;
  const activePoliciesCount = policyItems.filter((p) => p.active).length;

  const toggleSection = (id: Exclude<RuleSection, null>) => {
    setOpenSection((cur) => (cur === id ? null : id));
  };

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
    <div className="space-y-3">
      {feedback && (
        <div
          className={`flex items-center justify-between gap-2 rounded-xl border px-3.5 py-2.5 text-xs ${
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

      {/* 1. Automatic Visual Filter — Always Directly Accessible */}
      <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <Sparkles className="h-4 w-4 text-emerald-400" />
            <div>
              <h3 className="text-sm font-semibold text-white">Filtre d’écran automatique</h3>
              <p className="text-[11px] text-zinc-500">Masquage en direct des visages et contenus sensibles</p>
            </div>
          </div>
          <span
            className={`rounded-md px-2 py-0.5 text-[10px] font-medium ${
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

        <div className="mt-3 grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={() => void onSetAiShield({ blurFaces: !telemetry?.aiBlurFaces })}
            className={`flex items-center justify-between rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
              telemetry?.aiBlurFaces
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                : 'border-white/[0.08] bg-zinc-950 text-zinc-400 hover:text-white'
            }`}
          >
            <span>Visages</span>
            <span className="text-[10px] font-semibold">{telemetry?.aiBlurFaces ? 'ON' : 'OFF'}</span>
          </button>

          <button
            type="button"
            onClick={() => void onSetAiShield({ blurNsfw: !telemetry?.aiBlurNsfw })}
            className={`flex items-center justify-between rounded-xl border px-3 py-2.5 text-xs font-medium transition ${
              telemetry?.aiBlurNsfw
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                : 'border-white/[0.08] bg-zinc-950 text-zinc-400 hover:text-white'
            }`}
          >
            <span>Contenu sensible</span>
            <span className="text-[10px] font-semibold">{telemetry?.aiBlurNsfw ? 'ON' : 'OFF'}</span>
          </button>
        </div>

        {telemetry?.aiBlurNsfw && (
          <div className="mt-2.5 flex items-center justify-between border-t border-white/[0.06] pt-2.5 text-xs">
            <span className="text-zinc-400">Niveau strict</span>
            <button
              type="button"
              onClick={() => void onSetAiShield({ nsfwStrict: !telemetry?.aiNsfwStrict })}
              className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
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

      {/* 2. Horaires & École (Accordion) */}
      <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
        <button
          type="button"
          onClick={() => toggleSection('schedule')}
          className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
        >
          <div className="flex min-w-0 items-center gap-2.5">
            <Moon className="h-4 w-4 shrink-0 text-zinc-400" />
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">Horaires & École</div>
              <div className="truncate text-[11px] text-zinc-500">
                {curfewEnabled ? `Couvre-feu ${curfewStart}–${curfewEnd}` : 'Couvre-feu inactif'}
                {isSchoolActive ? ` · École ${formatCountdown(schoolUntil, nowMs)}` : ''}
              </div>
            </div>
          </div>
          {openSection === 'schedule' ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-zinc-500" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-zinc-500" />
          )}
        </button>

        {openSection === 'schedule' && (
          <div className="space-y-4 border-t border-white/[0.06] p-4">
            {/* Curfew */}
            <div className="space-y-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold text-white">Couvre-feu / Mode Nuit</span>
                <button
                  type="button"
                  onClick={() => setCurfewEnabled((v) => !v)}
                  className={`rounded-lg border px-2.5 py-1 text-xs font-medium ${
                    curfewEnabled
                      ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {curfewEnabled ? 'Actif' : 'Inactif'}
                </button>
              </div>
              <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-2">
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
              <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
                <div className="grid grid-cols-7 gap-1 sm:flex">
                  {DAY_LABELS.map((lbl, idx) => {
                    const on = (curfewMask & (1 << idx)) !== 0;
                    return (
                      <button
                        key={idx}
                        type="button"
                        onClick={() => setCurfewMask((m) => m ^ (1 << idx))}
                        className={`flex h-8 items-center justify-center rounded-lg text-[11px] font-semibold sm:h-7 sm:w-7 ${
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
                  className="w-full rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 sm:w-auto sm:py-1.5"
                >
                  Enregistrer
                </button>
              </div>
            </div>

            {/* School Whitelist */}
            <div className="space-y-2.5 border-t border-white/[0.06] pt-4">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold text-white">Mode École (liste blanche)</span>
                <div className="flex items-center gap-1">
                  {isSchoolActive ? (
                    <button
                      type="button"
                      onClick={() => void onSetSchoolMode({ durationMinutes: 0 })}
                      className="rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950"
                    >
                      Arrêter
                    </button>
                  ) : (
                    [60, 120, 240].map((m) => (
                      <button
                        key={m}
                        type="button"
                        onClick={() => void onSetSchoolMode({ durationMinutes: m })}
                        className="rounded-lg border border-white/10 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-300"
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
                placeholder="Filtrer les applications autorisées…"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-white placeholder-zinc-500"
              />
              <div className="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto">
                {(screenTime?.apps ?? [])
                  .filter((a) => !schoolSearch.trim() || a.appName.toLowerCase().includes(schoolSearch.toLowerCase()))
                  .slice(0, 32)
                  .map((app) => {
                    const allowed = Boolean(app.schoolAllowed);
                    return (
                      <button
                        key={app.packageName}
                        type="button"
                        onClick={() => void handleToggleSchoolPkg(app.packageName)}
                        className={`rounded-lg border px-2 py-0.5 text-[11px] ${
                          allowed
                            ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-200'
                            : 'border-white/[0.07] bg-zinc-950 text-zinc-400'
                        }`}
                      >
                        {allowed ? '✓ ' : ''}
                        {app.appName}
                      </button>
                    );
                  })}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 3. Sécurité, Mode Perdu & Dissociation (Accordion) */}
      <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
        <button
          type="button"
          onClick={() => toggleSection('security')}
          className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
        >
          <div className="flex min-w-0 items-center gap-2.5">
            <ShieldCheck className="h-4 w-4 shrink-0 text-emerald-400" />
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">Sécurité & Verrouillage</div>
              <div className="truncate text-[11px] text-zinc-500">
                {antiBypassActive ? 'Paramètres verrouillés' : 'Paramètres libres'}
                {' · '}
                {telemetry?.deviceAdminActive ? 'Admin actif' : 'Admin inactif'}
                {lostActive ? ' · Mode Perdu ON' : ''}
              </div>
            </div>
          </div>
          {openSection === 'security' ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-zinc-500" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-zinc-500" />
          )}
        </button>

        {openSection === 'security' && (
          <div className="space-y-4 border-t border-white/[0.06] p-4">
            {/* Anti-Bypass */}
            <div className="flex items-center justify-between gap-3">
              <div>
                <div className="text-xs font-semibold text-white">Paramètres & Play Store</div>
                <div className="text-[11px] text-zinc-500">Bloqué par défaut (code admin requis)</div>
              </div>
              <button
                type="button"
                onClick={() => void onSetAntiBypass(!antiBypassActive)}
                className={`shrink-0 rounded-lg border px-3 py-1.5 text-xs font-medium ${
                  antiBypassActive
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-400'
                }`}
              >
                {antiBypassActive ? 'Verrouillés' : 'Libres'}
              </button>
            </div>

            {/* Admin PIN */}
            <div className="border-t border-white/[0.06] pt-3.5">
              <div className="flex items-center justify-between text-xs">
                <span className="inline-flex items-center gap-1.5 font-semibold text-white">
                  <KeyRound className="h-3.5 w-3.5 text-zinc-400" />
                  Code administrateur
                </span>
                <span className="text-[11px] text-zinc-500">
                  {telemetry?.hasAdminPin ? 'Défini' : 'Non défini'}
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
                    className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-3 pr-8 text-xs text-white placeholder-zinc-500"
                  />
                  <button
                    type="button"
                    onClick={() => setPinShow((v) => !v)}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-zinc-500"
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
                    className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
                  />
                  <button
                    type="submit"
                    disabled={pinBusy || pinVal.trim().length < 4 || pinConfirm.trim().length < 4}
                    className="shrink-0 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                  >
                    OK
                  </button>
                </div>
              </form>
            </div>

            {/* Lost Mode */}
            <div className="space-y-2 border-t border-white/[0.06] pt-3.5">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-white">Mode Perdu</span>
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
              <input
                type="text"
                maxLength={200}
                value={lostMessage}
                onChange={(e) => setLostMessage(e.target.value)}
                placeholder="Message sur l’écran verrouillé"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
              />
              <div className="flex flex-wrap items-center gap-2">
                <input
                  type="tel"
                  maxLength={32}
                  value={lostPhone}
                  onChange={(e) => setLostPhone(e.target.value)}
                  placeholder="Numéro de contact"
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
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
                  className="rounded-xl bg-rose-500 px-3.5 py-2 text-xs font-semibold text-white hover:bg-rose-400"
                >
                  {lostActive ? 'Mettre à jour' : 'Activer'}
                </button>
              </div>
            </div>

            {/* Parent-Controlled Unpair */}
            <div className="border-t border-white/[0.06] pt-3.5">
              {confirmUnpairRemote ? (
                <div className="space-y-2 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-xs">
                  <p className="leading-relaxed text-rose-100">
                    Délier cet appareil supprimera immédiatement <strong>tous les droits parentaux</strong>,
                    les règles, les droits administrateur, l’accès à distance et invalidera définitivement
                    l’ancien code d’accès. Continuer ?
                  </p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => {
                        setConfirmUnpairRemote(false);
                        void onRespondUnpair(true);
                      }}
                      className="rounded-lg bg-rose-500 px-3 py-1.5 font-semibold text-white hover:bg-rose-400"
                    >
                      Confirmer et tout révoquer
                    </button>
                    <button
                      type="button"
                      onClick={() => setConfirmUnpairRemote(false)}
                      className="rounded-lg border border-white/10 px-3 py-1.5 text-zinc-300"
                    >
                      Annuler
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex items-center justify-between gap-2 text-xs">
                  <div>
                    <div className="font-medium text-zinc-200">Délier définitivement cet appareil</div>
                    <div className="text-[11px] text-zinc-500">
                      Révoque tous les droits de l’adulte et libère complètement le téléphone
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => setConfirmUnpairRemote(true)}
                    className="shrink-0 rounded-lg border border-rose-500/35 bg-rose-500/10 px-3 py-1.5 text-xs font-medium text-rose-200 hover:bg-rose-500/20"
                  >
                    Délier
                  </button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* 4. Autorisations à distance (Accordion) */}
      <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
        <button
          type="button"
          onClick={() => toggleSection('policy')}
          className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
        >
          <div className="flex min-w-0 items-center gap-2.5">
            <Sliders className="h-4 w-4 shrink-0 text-zinc-400" />
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">Autorisations à distance</div>
              <div className="truncate text-[11px] text-zinc-500">
                {activePoliciesCount} / {policyItems.length} fonctions actives
              </div>
            </div>
          </div>
          {openSection === 'policy' ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-zinc-500" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-zinc-500" />
          )}
        </button>

        {openSection === 'policy' && (
          <div className="grid grid-cols-2 gap-2 border-t border-white/[0.06] p-4">
            {policyItems.map((item) => (
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
                <span className="shrink-0 text-[10px] font-semibold">
                  {item.active ? 'ON' : 'OFF'}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* 5. Journal d’alertes (Accordion) */}
      <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
        <button
          type="button"
          onClick={() => toggleSection('journal')}
          className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
        >
          <div className="flex min-w-0 items-center gap-2.5">
            <ShieldAlert className="h-4 w-4 shrink-0 text-zinc-400" />
            <div className="min-w-0">
              <div className="text-sm font-medium text-white">Journal d’alertes</div>
              <div className="truncate text-[11px] text-zinc-500">
                {securityEvents.length === 0
                  ? 'Aucun événement'
                  : `${securityEvents.length} événement${securityEvents.length > 1 ? 's' : ''}`}
              </div>
            </div>
          </div>
          {openSection === 'journal' ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-zinc-500" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-zinc-500" />
          )}
        </button>

        {openSection === 'journal' && (
          <div className="border-t border-white/[0.06] p-4">
            {securityEvents.length > 0 && (
              <div className="mb-2.5 flex justify-end">
                <button
                  type="button"
                  onClick={() => void onClearSecurityEvents()}
                  className="text-xs text-zinc-400 hover:text-rose-300"
                >
                  Effacer le journal
                </button>
              </div>
            )}
            {securityEvents.length === 0 ? (
              <div className="py-4 text-center text-xs text-zinc-500">Aucun événement enregistré.</div>
            ) : (
              <div className="max-h-64 space-y-2 overflow-y-auto pr-0.5">
                {securityEvents.map((ev) => (
                  <div
                    key={ev.id}
                    className="flex items-start justify-between gap-3 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
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
                      <p className="mt-0.5 text-[11px] text-zinc-400">{ev.detail}</p>
                    </div>

                    {ev.photoBase64 && (
                      <a
                        href={`data:image/jpeg;base64,${ev.photoBase64}`}
                        download={`intrusion-${ev.timestamp}.jpg`}
                        className="shrink-0 overflow-hidden rounded-lg border border-rose-500/40"
                      >
                        <img
                          src={`data:image/jpeg;base64,${ev.photoBase64}`}
                          alt="Capture"
                          className="h-11 w-11 object-cover"
                        />
                      </a>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

/* ==================== GEOFENCING COLLAPSIBLE CARD (EMBEDDED IN POSITION TAB) ==================== */
export const GeofenceZonesCard: React.FC<GeofenceZonesCardProps> = ({
  screenTime,
  locationData,
  onSaveGeofence,
  onRequestLocation,
}) => {
  const [open, setOpen] = useState(false);
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

  const zones = screenTime?.geofences ?? [];

  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
      >
        <div className="flex items-center gap-2.5">
          <MapPin className="h-4 w-4 text-emerald-400" />
          <div>
            <div className="text-sm font-medium text-white">Zones de sécurité</div>
            <div className="text-[11px] text-zinc-500">
              {zones.length === 0 ? 'Aucune zone définie' : `${zones.length} zone${zones.length > 1 ? 's' : ''}`}
            </div>
          </div>
        </div>
        {open ? <ChevronUp className="h-4 w-4 text-zinc-500" /> : <ChevronDown className="h-4 w-4 text-zinc-500" />}
      </button>

      {open && (
        <div className="space-y-3 border-t border-white/[0.06] p-4">
          {zones.length > 0 && (
            <div className="space-y-1.5">
              {zones.map((z) => (
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
                          z.inside ? 'bg-emerald-500/15 text-emerald-300' : 'bg-amber-500/15 text-amber-300'
                        }`}
                      >
                        {z.inside ? 'Dedans' : 'Dehors'}
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

          <div className="flex justify-end">
            <button
              type="button"
              onClick={() => {
                void onRequestLocation();
                if (locationData) {
                  setGeoLat(locationData.latitude.toFixed(5));
                  setGeoLng(locationData.longitude.toFixed(5));
                }
              }}
              className="text-[11px] text-emerald-400 hover:underline"
            >
              Remplir avec la position actuelle
            </button>
          </div>

          <form onSubmit={(e) => void handleCreate(e)} className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <input
              type="text"
              value={geoName}
              onChange={(e) => setGeoName(e.target.value)}
              placeholder="Nom (Maison)"
              className="col-span-2 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white sm:col-span-1"
            />
            <input
              type="text"
              value={geoLat}
              onChange={(e) => setGeoLat(e.target.value)}
              placeholder="Latitude"
              className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white"
            />
            <input
              type="text"
              value={geoLng}
              onChange={(e) => setGeoLng(e.target.value)}
              placeholder="Longitude"
              className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white"
            />
            <div className="col-span-2 flex gap-1.5 sm:col-span-1">
              <select
                value={geoRadius}
                onChange={(e) => setGeoRadius(parseInt(e.target.value, 10))}
                className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 text-xs text-white"
              >
                <option value={100}>100 m</option>
                <option value={150}>150 m</option>
                <option value={300}>300 m</option>
                <option value={500}>500 m</option>
              </select>
              <button
                type="submit"
                disabled={!geoLat || !geoLng}
                className="inline-flex items-center justify-center rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
