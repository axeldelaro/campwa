import React, { useEffect, useMemo, useState } from 'react';
import QRCode from 'qrcode';
import {
  Activity,
  Award,
  Ban,
  BedDouble,
  Clock,
  Flame,
  Footprints,
  Globe,
  KeyRound,
  Lock,
  MapPin,
  Moon,
  Plus,
  QrCode,
  RefreshCw,
  Scissors,
  Search,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Timer,
  Trash2,
  Unlock,
  Wind,
  Zap,
} from 'lucide-react';
import { SentinelNative } from '../native/sentinelPlugin';
import type {
  NativePortalState,
  SelfControlScheduleSlot,
  SelfControlState,
} from '../types/sentinel';
import { DnsFirewallCard } from './DnsFirewallCard';
import { GeoZoneMapPicker } from './GeoZoneMapPicker';

interface SelfControlHubProps {
  portal: NativePortalState;
  onPortalRefresh: () => Promise<void>;
  onSwitchToChildMode?: () => void;
}

const FEED_KILLER_ITEMS = [
  { id: 'yt_shorts', label: 'YouTube Shorts', desc: 'Garde les vidéos longues éducatives, tue le flux Shorts' },
  { id: 'ig_reels', label: 'Instagram Reels', desc: 'Garde les DM/messages, bloque les Reels infinis' },
  { id: 'snap_spotlight', label: 'Snapchat Spotlight', desc: 'Coupe Spotlight tout en gardant les discussions' },
  { id: 'fb_reels', label: 'Facebook Reels', desc: 'Quitte automatiquement les vidéos courtes Facebook' },
  { id: 'x_foryou', label: 'X / Twitter « Pour vous »', desc: 'Bloque le fil algorithmique « Pour vous »' },
];

const PRESET_PROFILES = [
  {
    id: 'nofap',
    title: 'NoFap / Anti-Porno Extrême',
    desc: 'DNS Adulte + IA non filtrées + APK/Proxy + SafeSearch + Anti-VPN + Quarantaine',
    color: 'from-rose-500 to-orange-500',
  },
  {
    id: 'stop_shorts',
    title: 'Stop TikTok, Shorts & Reels',
    desc: 'Tueur de flux Shorts/Reels/Spotlight + TikTok bloqué + Pause consciente 20s',
    color: 'from-amber-500 to-rose-500',
  },
  {
    id: 'deep_work',
    title: 'Deep Work Étudiant / Pro',
    desc: 'DNS Réseaux/Streaming/Jeux + Créneaux 8h30–12h & 14h–18h + Pause 25s',
    color: 'from-sky-500 to-indigo-500',
  },
  {
    id: 'clean_sleep',
    title: 'Sommeil Sans Écran',
    desc: 'Couvre-feu 22h30–7h + Voile ambre 30 min avant + Détecteur sous-couette + 50 pas réveil',
    color: 'from-indigo-500 to-violet-600',
  },
  {
    id: 'monk_mode',
    title: 'Mode Moine Total',
    desc: 'Toutes catégories DNS + Tous flux courts + Réseaux bloqués + Sommeil + Anti-contournement',
    color: 'from-emerald-500 to-teal-600',
  },
];

function formatMinutesAsTime(min: number): string {
  const h = Math.floor(((min % 1440) + 1440) % 1440 / 60);
  const m = ((min % 60) + 60) % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function parseTimeToMinutes(val: string, fallback: number): number {
  const parts = val.split(':');
  if (parts.length !== 2) return fallback;
  const h = Number(parts[0]);
  const m = Number(parts[1]);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return fallback;
  return Math.max(0, Math.min(1439, h * 60 + m));
}

function randomCode(len: number): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  for (let i = 0; i < len; i++) {
    out += chars[Math.floor(Math.random() * chars.length)];
  }
  return out;
}

export const SelfControlHub: React.FC<SelfControlHubProps> = ({
  portal,
  onPortalRefresh,
  onSwitchToChildMode,
}) => {
  const [tab, setTab] = useState<'pacte' | 'carte' | 'dns' | 'apps' | 'habitudes'>('pacte');
  const [sc, setSc] = useState<SelfControlState | null>(null);
  const [apps, setApps] = useState<Array<{ packageName: string; appName: string }>>([]);
  const [appQuery, setAppQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  // Draft map & sealing options (before sealing or when unlocked inside zone)
  const [draftLat, setDraftLat] = useState(0);
  const [draftLng, setDraftLng] = useState(0);
  const [draftRadius, setDraftRadius] = useState(100);
  const [draftLabel, setDraftLabel] = useState('Parc / Lieu de déverrouillage');
  const [draftDwellSec, setDraftDwellSec] = useState(180);
  const [draftCoolingH, setDraftCoolingH] = useState(0);
  const [draftPartnerCode, setDraftPartnerCode] = useState('');
  const [draftQrSecret, setDraftQrSecret] = useState('');
  const [draftQrDataUrl, setDraftQrDataUrl] = useState<string | null>(null);
  const [draftPartnerTopic, setDraftPartnerTopic] = useState('');
  const [sealConfirmOpen, setSealConfirmOpen] = useState(false);

  // Unlock inputs when sealed
  const [unlockCodeInput, setUnlockCodeInput] = useState('');
  const [unlockQrInput, setUnlockQrInput] = useState('');

  // Draft weekly schedule & focus zone inputs
  const [newSchName, setNewSchName] = useState('Deep Work');
  const [newSchStart, setNewSchStart] = useState('09:00');
  const [newSchEnd, setNewSchEnd] = useState('12:00');
  const [newZoneName, setNewZoneName] = useState('Bureau / Bibliothèque');
  const [newZoneMode, setNewZoneMode] = useState<'deep_work' | 'night_curfew'>('deep_work');

  const refreshState = async () => {
    try {
      const next = await SentinelNative.getSelfControlState();
      setSc(next);
      if (next.hasUnlockZone) {
        setDraftLat((prev) => (prev === 0 ? next.unlockZoneLat : prev));
        setDraftLng((prev) => (prev === 0 ? next.unlockZoneLng : prev));
        setDraftRadius(next.unlockZoneRadiusM || 100);
        setDraftLabel(next.unlockZoneLabel || 'Zone de déverrouillage');
        setDraftDwellSec(next.unlockDwellSec || 180);
        setDraftCoolingH(next.coolingOffHours || 0);
        setDraftPartnerTopic(next.partnerAlertTopic || '');
      }
    } catch (err) {
      console.error('SelfControl state error:', err);
    }
  };

  useEffect(() => {
    void refreshState();
    void SentinelNative.getInstalledUserApps()
      .then((res) => setApps(res.apps || []))
      .catch(() => {});

    let handle: { remove: () => Promise<void> } | null = null;
    void SentinelNative.addListener('selfControlStateChanged', (next) => {
      setSc(next);
    }).then((h) => {
      handle = h;
    });

    const tick = window.setInterval(() => {
      if (!document.hidden) setNowMs(Date.now());
    }, 1000);

    return () => {
      window.clearInterval(tick);
      void SentinelNative.stopGeoUnlockWatch().catch(() => {});
      if (handle) void handle.remove();
    };
  }, []);

  useEffect(() => {
    if (tab === 'carte') {
      void SentinelNative.startGeoUnlockWatch()
        .then((next) => setSc(next))
        .catch(() => {});
    } else {
      void SentinelNative.stopGeoUnlockWatch().catch(() => {});
    }
  }, [tab]);

  useEffect(() => {
    if (!draftQrSecret) {
      setDraftQrDataUrl(null);
      return;
    }
    void QRCode.toDataURL(`SENTINEL-VAULT:${draftQrSecret}`, { width: 180, margin: 1 })
      .then((url) => setDraftQrDataUrl(url))
      .catch(() => setDraftQrDataUrl(null));
  }, [draftQrSecret]);

  const runSc = async (fn: () => Promise<SelfControlState>, okText?: string) => {
    setBusy(true);
    setFeedback(null);
    try {
      const next = await fn();
      setSc(next);
      const msg = next.message || okText;
      if (msg) setFeedback({ ok: true, text: msg });
      await onPortalRefresh();
    } catch (err) {
      setFeedback({
        ok: false,
        text: err instanceof Error ? err.message : 'Action refusée (Pacte scellé hors zone).',
      });
    } finally {
      setBusy(false);
    }
  };

  const filteredApps = useMemo(() => {
    const q = appQuery.trim().toLowerCase();
    if (!q) return apps.slice(0, 40);
    return apps
      .filter(
        (a) =>
          a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
      )
      .slice(0, 50);
  }, [apps, appQuery]);

  if (!sc) {
    return (
      <div className="flex min-h-[55vh] items-center justify-center">
        <RefreshCw className="h-6 w-6 animate-spin text-sky-500" />
      </div>
    );
  }

  const p = portal.permissions;
  const missingSetup: Array<{ key: string; label: string; action: () => Promise<void> }> = [];
  if (!portal.deviceAdminActive) {
    missingSetup.push({
      key: 'admin',
      label: 'Droits Administrateur (anti-désinstallation)',
      action: async () => {
        await SentinelNative.requestDeviceAdmin();
        await onPortalRefresh();
      },
    });
  }
  if (!p.accessibilityService) {
    missingSetup.push({
      key: 'a11y',
      label: 'Service Accessibilité (Tueur Shorts/Reels & Verrou Paramètres)',
      action: async () => {
        await SentinelNative.requestAccessibilityPermission();
        await onPortalRefresh();
      },
    });
  }
  if (!p.usageAccess) {
    missingSetup.push({
      key: 'usage',
      label: 'Accès Utilisation (Quotas, Détox & Pause Consciente)',
      action: async () => {
        await SentinelNative.requestUsageAccessPermission();
        await onPortalRefresh();
      },
    });
  }
  if (!p.overlay) {
    missingSetup.push({
      key: 'overlay',
      label: 'Superposition d’écran (Voile Coucher & Écrans de blocage)',
      action: async () => {
        await SentinelNative.requestOverlayPermission();
        await onPortalRefresh();
      },
    });
  }
  if (!p.location) {
    missingSetup.push({
      key: 'loc',
      label: 'GPS Précis (Zone Carte & Zones Focus Auto)',
      action: async () => {
        await SentinelNative.requestLocationPermission();
        await onPortalRefresh();
      },
    });
  }
  if (!sc.vpnConsented) {
    missingSetup.push({
      key: 'vpn',
      label: 'Pare-feu DNS / VPN Local (Anti-Porno, Social Web, SafeSearch)',
      action: async () => {
        const next = await SentinelNative.requestVpnPermission();
        setSc(next);
        await onPortalRefresh();
      },
    });
  }
  if (p.activityRecognition === false) {
    missingSetup.push({
      key: 'steps',
      label: 'Capteur de Pas (Défi Réveil & Gagner son Temps)',
      action: async () => {
        await SentinelNative.requestActivityRecognitionPermission();
        await onPortalRefresh();
      },
    });
  }

  const detoxRemMs = Math.max(0, sc.nuclearDetoxUntilMs - nowMs);
  const isDetoxActive = detoxRemMs > 0;
  const maxHeat = Math.max(1, ...(sc.weaknessHeatmap || [1]));
  const reclaimedHours = (sc.reclaimedMinutesTotal / 60).toFixed(1);

  const effectiveLat = sc.pactSealed && !sc.canWeakenNow ? sc.unlockZoneLat : draftLat || sc.unlockZoneLat;
  const effectiveLng = sc.pactSealed && !sc.canWeakenNow ? sc.unlockZoneLng : draftLng || sc.unlockZoneLng;

  const coolingRemMs =
    sc.coolingOffHours > 0 && sc.coolingOffRequestedAtMs > 0
      ? Math.max(0, sc.coolingOffRequestedAtMs + sc.coolingOffHours * 3_600_000 - nowMs)
      : 0;

  return (
    <div className="mx-auto max-w-2xl space-y-4 px-3 pb-24 pt-4 sm:px-5">
      {/* ==================== TOP HEADER HERO ==================== */}
      <div className="kl-card overflow-hidden p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div
              className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl text-white kl-solid-white shadow-sm ${
                sc.pactSealed
                  ? sc.canWeakenNow
                    ? 'bg-emerald-500'
                    : 'bg-sky-600'
                  : 'bg-slate-800 dark:bg-sky-500'
              }`}
            >
              {sc.pactSealed ? (
                sc.canWeakenNow ? (
                  <Unlock className="h-5 w-5" />
                ) : (
                  <Lock className="h-5 w-5" />
                )
              ) : (
                <ShieldCheck className="h-5 w-5" />
              )}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-base font-extrabold text-slate-900 dark:text-white">
                  Pacte Geo-Vault
                </h1>
                <span
                  className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                    sc.pactSealed
                      ? sc.canWeakenNow
                        ? 'bg-emerald-500 text-white kl-solid-white'
                        : 'bg-sky-500 text-white kl-solid-white'
                      : 'bg-amber-100 text-amber-800 dark:bg-amber-500/20 dark:text-amber-200'
                  }`}
                >
                  {sc.pactSealed
                    ? sc.canWeakenNow
                      ? 'DÉVERROUILLÉ'
                      : 'SCELLÉ'
                    : 'BROUILLON'}
                </span>
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                {sc.pactSealed
                  ? sc.canWeakenNow
                    ? 'Fenêtre de modification ouverte — vous pouvez ajuster ou lever le Pacte.'
                    : `Règle à sens unique : vous pouvez durcir, jamais assouplir hors « ${sc.unlockZoneLabel} ».`
                  : 'Configurez vos blocages DNS/Apps puis scellez le Pacte sur un lieu de la carte.'}
              </p>
            </div>
          </div>

          {!sc.pactSealed && onSwitchToChildMode && (
            <button
              type="button"
              onClick={onSwitchToChildMode}
              className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-1.5 text-[11px] font-semibold text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.05] dark:text-slate-200"
            >
              Mode Enfant
            </button>
          )}
        </div>

        {/* Clean Kidslox 5-Tab Bar */}
        <div className="mt-4 grid grid-cols-5 gap-1 rounded-2xl bg-slate-100 p-1 dark:bg-white/[0.05]">
          {(
            [
              { id: 'pacte', label: 'Pacte', Icon: Award },
              { id: 'carte', label: 'Carte', Icon: MapPin },
              { id: 'dns', label: 'DNS', Icon: Globe },
              { id: 'apps', label: 'Apps', Icon: Scissors },
              { id: 'habitudes', label: 'Rythme', Icon: Moon },
            ] as const
          ).map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              className={`flex flex-col items-center justify-center gap-0.5 rounded-xl py-2 text-[11px] font-bold transition ${
                tab === id
                  ? 'bg-white text-sky-600 shadow-sm dark:bg-slate-800 dark:text-sky-400'
                  : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-white'
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{label}</span>
            </button>
          ))}
        </div>
      </div>

      {feedback && (
        <div
          className={`rounded-2xl border px-4 py-3 text-xs font-semibold ${
            feedback.ok
              ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/35 dark:bg-emerald-500/15 dark:text-emerald-200'
              : 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-500/35 dark:bg-rose-500/15 dark:text-rose-200'
          }`}
        >
          {feedback.text}
        </div>
      )}

      {sc.clockTamperDetected && (
        <div className="flex items-center gap-2 rounded-2xl border border-rose-300 bg-rose-50 px-4 py-3 text-xs font-semibold text-rose-800 dark:border-rose-500/40 dark:bg-rose-500/15 dark:text-rose-200">
          <ShieldAlert className="h-4 w-4 shrink-0" />
          <span>Horloge anti-triche : un changement manuel d’heure système a été détecté.</span>
        </div>
      )}

      {/* ==================== TAB 1: PACTE, SÉRIE, PROFILS & DÉTOX ==================== */}
      {tab === 'pacte' && (
        <div className="space-y-4">
          {/* Missing Permissions Checklist */}
          {missingSetup.length > 0 && (
            <div className="kl-card border-amber-200 bg-amber-50/70 p-4 dark:border-amber-500/35 dark:bg-amber-500/10">
              <div className="text-xs font-bold text-amber-900 dark:text-amber-200">
                Permissions à activer pour un blindage total ({missingSetup.length})
              </div>
              <div className="mt-2.5 space-y-1.5">
                {missingSetup.map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    disabled={busy}
                    onClick={() => void item.action()}
                    className="flex w-full items-center justify-between gap-2 rounded-xl bg-white px-3 py-2 text-left text-xs font-medium text-slate-800 shadow-sm hover:bg-slate-50 dark:bg-slate-900/80 dark:text-slate-100"
                  >
                    <span>{item.label}</span>
                    <span className="shrink-0 font-bold text-sky-600 dark:text-sky-400">Activer</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Sobriety & Reclaimed Time KPI Grid */}
          <div className="grid grid-cols-3 gap-2.5">
            <div className="kl-card p-3.5 text-center">
              <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Série</div>
              <div className="mt-1 text-2xl font-black text-emerald-600 dark:text-emerald-400">
                {sc.streakDays}j
              </div>
              <div className="text-[10px] text-slate-500">jours propres</div>
            </div>
            <div className="kl-card p-3.5 text-center">
              <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Vie regagnée</div>
              <div className="mt-1 text-2xl font-black text-sky-600 dark:text-sky-400">
                {reclaimedHours}h
              </div>
              <div className="text-[10px] text-slate-500">{sc.totalBlockAttempts} tentatives bloquées</div>
            </div>
            <div className="kl-card p-3.5 text-center">
              <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Impulsions</div>
              <div className="mt-1 text-2xl font-black text-violet-600 dark:text-violet-400">
                {sc.abortedFrictionOpens}
              </div>
              <div className="text-[10px] text-slate-500">évitées par pause</div>
            </div>
          </div>

          {/* Nuclear Detox Mode */}
          <div className="kl-card p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2.5">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-rose-100 text-rose-600 dark:bg-rose-500/20 dark:text-rose-300">
                  <Zap className="h-4 w-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                    Mode Détox Nucléaire
                  </h3>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Verrouille tout sauf les essentiels (Téléphone, SMS, Cartes, Banque, Santé). Impossible à annuler hors zone carte !
                  </p>
                </div>
              </div>
              {isDetoxActive && sc.canWeakenNow && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void runSc(() => SentinelNative.stopNuclearDetox())}
                  className="shrink-0 rounded-xl bg-rose-500 px-3 py-1.5 text-xs font-bold text-white kl-solid-white"
                >
                  Stopper
                </button>
              )}
            </div>

            {isDetoxActive ? (
              <div className="mt-3 rounded-xl bg-emerald-50 px-3.5 py-2.5 text-xs font-bold text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-200">
                Cure Détox en cours · encore {Math.floor(detoxRemMs / 3_600_000)}h{' '}
                {Math.floor((detoxRemMs % 3_600_000) / 60_000)}m
              </div>
            ) : (
              <div className="mt-3 grid grid-cols-4 gap-2">
                {[
                  { h: 24, label: '24 heures' },
                  { h: 72, label: '3 jours' },
                  { h: 168, label: '7 jours' },
                  { h: 720, label: '30 jours' },
                ].map((opt) => (
                  <button
                    key={opt.h}
                    type="button"
                    disabled={busy}
                    onClick={() => void runSc(() => SentinelNative.startNuclearDetox({ hours: opt.h }))}
                    className="rounded-xl border border-slate-200 bg-slate-50 py-2 text-xs font-bold text-slate-800 hover:border-rose-300 hover:bg-rose-50 dark:border-white/10 dark:bg-white/[0.04] dark:text-white"
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* 1-Click Ready Profiles */}
          <div className="kl-card space-y-2.5 p-4">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-sky-500" />
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Profils Anti-Addiction Prêts en 1 Clic
              </h3>
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {PRESET_PROFILES.map((pr) => (
                <button
                  key={pr.id}
                  type="button"
                  disabled={busy}
                  onClick={() =>
                    void runSc(
                      () => SentinelNative.applySelfControlPreset({ presetId: pr.id }),
                      `Profil « ${pr.title} » appliqué !`
                    )
                  }
                  className="flex flex-col items-start rounded-xl border border-slate-200 bg-slate-50/70 p-3 text-left transition hover:border-sky-300 hover:bg-sky-50/40 dark:border-white/10 dark:bg-white/[0.03]"
                >
                  <span
                    className={`rounded-full bg-gradient-to-r ${pr.color} px-2.5 py-0.5 text-[10px] font-bold text-white kl-solid-white`}
                  >
                    Appliquer
                  </span>
                  <div className="mt-2 text-xs font-bold text-slate-900 dark:text-white">
                    {pr.title}
                  </div>
                  <div className="mt-0.5 text-[11px] leading-snug text-slate-500 dark:text-slate-400">
                    {pr.desc}
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* 24h Weakness-Hour Heatmap */}
          <div className="kl-card p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Flame className="h-4 w-4 text-amber-500" />
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Heures de faiblesse (7 jours)
                </h3>
              </div>
              <span className="text-[11px] text-slate-400">0h → 23h</span>
            </div>
            <div className="mt-3 flex h-20 items-end gap-1">
              {(sc.weaknessHeatmap || Array(24).fill(0)).map((val, hr) => {
                const pct = Math.max(8, Math.round((val / maxHeat) * 100));
                return (
                  <div key={hr} className="flex flex-1 flex-col items-center gap-1">
                    <div
                      className={`w-full rounded-t ${
                        val === 0
                          ? 'bg-slate-200 dark:bg-slate-800'
                          : val >= maxHeat * 0.7
                            ? 'bg-rose-500'
                            : 'bg-sky-500'
                      }`}
                      style={{ height: `${pct}%` }}
                      title={`${hr}h : ${val} blocage(s)`}
                    />
                    {hr % 6 === 0 && (
                      <span className="text-[9px] text-slate-400">{hr}h</span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* ==================== TAB 2: CARTE GEO-UNLOCK & SCELLAGE ==================== */}
      {tab === 'carte' && (
        <div className="space-y-4">
          <div className="kl-card space-y-4 p-4">
            <GeoZoneMapPicker
              latitude={effectiveLat}
              longitude={effectiveLng}
              radiusMeters={draftRadius}
              label={draftLabel}
              sealed={sc.pactSealed}
              canEditPin={!sc.pactSealed || sc.canWeakenNow}
              geoStatus={sc.geoStatus}
              unlockSessionUntilMs={sc.unlockSessionUntilMs}
              nowMs={nowMs}
              onSelectPoint={(lat, lng) => {
                setDraftLat(lat);
                setDraftLng(lng);
              }}
              onCheckPresenceNow={() => {
                void SentinelNative.startGeoUnlockWatch()
                  .then((next) => setSc(next))
                  .catch(() => {});
              }}
            />

            {/* Editable Zone Parameters (only before sealing or inside unlock session) */}
            {(!sc.pactSealed || sc.canWeakenNow) && (
              <div className="space-y-3 border-t border-slate-200 pt-3 dark:border-white/10">
                <div>
                  <label className="text-[11px] font-bold text-slate-700 dark:text-slate-300">
                    Nom de votre zone de déverrouillage
                  </label>
                  <input
                    type="text"
                    value={draftLabel}
                    onChange={(e) => setDraftLabel(e.target.value)}
                    placeholder="Ex: Parc Monceau, Bureau, Maison parents…"
                    className="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
                  />
                </div>

                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div>
                    <div className="flex justify-between text-[11px] font-semibold text-slate-700 dark:text-slate-300">
                      <span>Rayon GPS autorisé</span>
                      <span className="text-sky-600 dark:text-sky-400">{draftRadius} m</span>
                    </div>
                    <input
                      type="range"
                      min={30}
                      max={500}
                      step={10}
                      value={draftRadius}
                      onChange={(e) => setDraftRadius(Number(e.target.value))}
                      className="mt-1 w-full accent-sky-500"
                    />
                  </div>
                  <div>
                    <div className="flex justify-between text-[11px] font-semibold text-slate-700 dark:text-slate-300">
                      <span>Temps sur place requis</span>
                      <span className="text-sky-600 dark:text-sky-400">
                        {Math.floor(draftDwellSec / 60)}m {draftDwellSec % 60}s
                      </span>
                    </div>
                    <input
                      type="range"
                      min={30}
                      max={300}
                      step={30}
                      value={draftDwellSec}
                      onChange={(e) => setDraftDwellSec(Number(e.target.value))}
                      className="mt-1 w-full accent-sky-500"
                    />
                  </div>
                </div>

                {/* Optional Fallback Keys (configured BEFORE sealing) */}
                <div className="space-y-2.5 rounded-2xl bg-slate-50 p-3 dark:bg-white/[0.04]">
                  <div className="text-xs font-bold text-slate-900 dark:text-white">
                    Clés de secours optionnelles (choisies avant scellement)
                  </div>

                  <div>
                    <div className="text-[11px] font-semibold text-slate-600 dark:text-slate-300">
                      Délai de réflexion hors zone :
                    </div>
                    <div className="mt-1.5 grid grid-cols-4 gap-1.5">
                      {[
                        { h: 0, lbl: 'GPS strict' },
                        { h: 24, lbl: '24 h' },
                        { h: 48, lbl: '48 h' },
                        { h: 72, lbl: '72 h' },
                      ].map((opt) => (
                        <button
                          key={opt.h}
                          type="button"
                          onClick={() => setDraftCoolingH(opt.h)}
                          className={`rounded-xl py-1.5 text-[11px] font-bold ${
                            draftCoolingH === opt.h
                              ? 'bg-sky-500 text-white kl-solid-white'
                              : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                          }`}
                        >
                          {opt.lbl}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => setDraftPartnerCode(randomCode(8))}
                      className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3 py-1.5 text-[11px] font-bold text-slate-800 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-white dark:ring-white/10"
                    >
                      <KeyRound className="h-3.5 w-3.5 text-amber-500" />
                      <span>Générer Clé Partenaire</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setDraftQrSecret(`VAULT-${randomCode(10)}`)}
                      className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3 py-1.5 text-[11px] font-bold text-slate-800 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-white dark:ring-white/10"
                    >
                      <QrCode className="h-3.5 w-3.5 text-sky-500" />
                      <span>Générer Badge QR physique</span>
                    </button>
                  </div>

                  {draftPartnerCode && (
                    <div className="rounded-xl bg-amber-50 p-2.5 text-xs text-amber-900 dark:bg-amber-500/15 dark:text-amber-200">
                      Confiez ce code d’urgence à un ami (ne le gardez pas sur ce téléphone) :{' '}
                      <strong className="font-mono text-sm tracking-widest">{draftPartnerCode}</strong>
                    </div>
                  )}

                  {draftQrDataUrl && (
                    <div className="flex items-center gap-3 rounded-xl bg-white p-2.5 dark:bg-slate-900">
                      <img src={draftQrDataUrl} alt="Badge QR" className="h-24 w-24 rounded-lg" />
                      <div className="text-[11px] text-slate-600 dark:text-slate-300">
                        <div className="font-bold text-slate-900 dark:text-white">Badge QR physique</div>
                        <div>Imprimez ou collez ce QR loin de chez vous (code : <strong className="font-mono">{draftQrSecret}</strong>).</div>
                      </div>
                    </div>
                  )}

                  <div>
                    <label className="text-[11px] font-semibold text-slate-600 dark:text-slate-300">
                      Canal Partenaire de responsabilité (topic ntfy.sh optionnel)
                    </label>
                    <input
                      type="text"
                      value={draftPartnerTopic}
                      onChange={(e) => setDraftPartnerTopic(e.target.value)}
                      placeholder="Ex: mon_ami_pacte_9281"
                      className="mt-1 w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 dark:border-white/10 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                </div>

                {/* Big Seal CTA */}
                <button
                  type="button"
                  disabled={busy || (Math.abs(effectiveLat) < 0.0001 && Math.abs(effectiveLng) < 0.0001)}
                  onClick={() => setSealConfirmOpen(true)}
                  className="flex w-full items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-sky-500 to-emerald-500 py-3.5 text-sm font-extrabold text-white kl-solid-white shadow-md transition hover:opacity-95 disabled:opacity-40"
                >
                  <Lock className="h-4 w-4" />
                  <span>
                    {sc.pactSealed ? 'Re-sceller le Pacte sur cette zone' : 'Sceller le Pacte sur cette zone'}
                  </span>
                </button>
              </div>
            )}

            {/* Sealed Fallback Unlock Controls */}
            {sc.pactSealed && (
              <div className="space-y-3 border-t border-slate-200 pt-3 dark:border-white/10">
                {sc.canWeakenNow && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void runSc(() => SentinelNative.unsealSelfControlPact())}
                    className="flex w-full items-center justify-center gap-2 rounded-xl bg-rose-500 py-2.5 text-xs font-bold text-white kl-solid-white hover:bg-rose-600"
                  >
                    <Unlock className="h-4 w-4" />
                    <span>Lever complètement le Pacte (session active)</span>
                  </button>
                )}

                {!sc.canWeakenNow && sc.coolingOffHours > 0 && (
                  <div className="flex items-center justify-between gap-2 rounded-xl bg-slate-50 p-3 dark:bg-white/[0.04]">
                    <div className="text-xs">
                      <div className="font-bold text-slate-900 dark:text-white">
                        Délai de réflexion ({sc.coolingOffHours}h)
                      </div>
                      <div className="text-[11px] text-slate-500">
                        {coolingRemMs > 0
                          ? `En cours : encore ${Math.floor(coolingRemMs / 3_600_000)}h ${Math.floor(
                              (coolingRemMs % 3_600_000) / 60_000
                            )}m`
                          : 'Démarre un compte à rebours avant toute modification.'}
                      </div>
                    </div>
                    {coolingRemMs > 0 ? (
                      <button
                        type="button"
                        onClick={() => void runSc(() => SentinelNative.cancelCoolingOffUnlock())}
                        className="rounded-xl bg-slate-200 px-3 py-1.5 text-xs font-bold text-slate-800"
                      >
                        Annuler
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => void runSc(() => SentinelNative.requestCoolingOffUnlock())}
                        className="rounded-xl bg-amber-500 px-3 py-1.5 text-xs font-bold text-white kl-solid-white"
                      >
                        Lancer
                      </button>
                    )}
                  </div>
                )}

                {!sc.canWeakenNow && sc.hasPartnerCode && (
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={unlockCodeInput}
                      onChange={(e) => setUnlockCodeInput(e.target.value.toUpperCase())}
                      placeholder="Clé d’urgence Partenaire"
                      className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-xs uppercase text-slate-900 dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
                    />
                    <button
                      type="button"
                      disabled={busy || unlockCodeInput.trim().length < 4}
                      onClick={() =>
                        void runSc(
                          () => SentinelNative.unlockWithPartnerCode({ code: unlockCodeInput }),
                          'Clé partenaire validée : 15 min pour modifier le Pacte !'
                        )
                      }
                      className="rounded-xl bg-sky-500 px-3.5 py-2 text-xs font-bold text-white kl-solid-white"
                    >
                      Débloquer
                    </button>
                  </div>
                )}

                {!sc.canWeakenNow && sc.hasQrSecret && (
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={unlockQrInput}
                      onChange={(e) => setUnlockQrInput(e.target.value)}
                      placeholder="Code du Badge QR physique (VAULT-…)"
                      className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-xs text-slate-900 dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
                    />
                    <button
                      type="button"
                      disabled={busy || unlockQrInput.trim().length < 4}
                      onClick={() =>
                        void runSc(
                          () => SentinelNative.unlockWithQrSecret({ secret: unlockQrInput }),
                          'Badge QR validé : 15 min de modification !'
                        )
                      }
                      className="rounded-xl bg-emerald-500 px-3.5 py-2 text-xs font-bold text-white kl-solid-white"
                    >
                      Valider QR
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ==================== TAB 3: PARE-FEU DNS / VPN LOCAL ==================== */}
      {tab === 'dns' && (
        <DnsFirewallCard
          dnsVpnEnabled={sc.dnsVpnEnabled}
          dnsVpnRunning={sc.dnsVpnRunning}
          vpnConsented={sc.vpnConsented}
          dnsCategories={sc.dnsCategories}
          dnsForceSafeSearch={sc.dnsForceSafeSearch}
          dnsCoupleAppWeb={sc.dnsCoupleAppWeb}
          dnsBlacklist={sc.dnsBlacklist}
          dnsWhitelist={sc.dnsWhitelist}
          dnsBlockedKeywords={sc.dnsBlockedKeywords}
          dnsBlockedTodayCount={sc.dnsBlockedTodayCount}
          recentDnsLogs={sc.recentDnsLogs}
          canWeaken={sc.canWeakenNow}
          isNativeSelfControl
          onRequestVpnConsent={async () => {
            await runSc(() => SentinelNative.requestVpnPermission(), 'Pare-feu DNS/VPN local activé !');
          }}
          onUpdateDns={async (patch) => {
            await runSc(() => SentinelNative.updateSelfControlConfig(patch));
          }}
        />
      )}

      {/* ==================== TAB 4: APPS, FEED KILLER, PAUSE CONSCIENTE & QUOTAS ==================== */}
      {tab === 'apps' && (
        <div className="space-y-4">
          {/* In-App Addictive Feed Killer */}
          <div className="kl-card space-y-2.5 p-4">
            <div className="flex items-center gap-2">
              <Scissors className="h-4 w-4 text-rose-500" />
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Tueur de Flux Addictifs In-App
              </h3>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Bloque chirurgicalement uniquement les flux de scroll infini tout en laissant YouTube long ou vos messages utilisables.
            </p>
            <div className="space-y-1.5">
              {FEED_KILLER_ITEMS.map((item) => {
                const active = sc.feedKillerTargets.includes(item.id);
                const lockedOn = active && !sc.canWeakenNow;
                return (
                  <button
                    key={item.id}
                    type="button"
                    disabled={busy || lockedOn}
                    onClick={() => {
                      const next = active
                        ? sc.feedKillerTargets.filter((x) => x !== item.id)
                        : [...sc.feedKillerTargets, item.id];
                      void runSc(() =>
                        SentinelNative.updateSelfControlConfig({
                          selfControlEnabled: true,
                          feedKillerTargets: next,
                        })
                      );
                    }}
                    className={`flex w-full items-center justify-between gap-3 rounded-xl border p-3 text-left transition ${
                      active
                        ? 'border-rose-200 bg-rose-50/70 dark:border-rose-500/40 dark:bg-rose-500/15'
                        : 'border-slate-200 bg-slate-50/60 dark:border-white/10 dark:bg-white/[0.03]'
                    }`}
                  >
                    <div>
                      <div className="text-xs font-bold text-slate-900 dark:text-white">{item.label}</div>
                      <div className="text-[11px] text-slate-500 dark:text-slate-400">{item.desc}</div>
                    </div>
                    <span
                      className={`shrink-0 rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
                        active ? 'bg-rose-500 text-white kl-solid-white' : 'bg-slate-200 text-slate-600'
                      }`}
                    >
                      {lockedOn ? 'SCELLÉ' : active ? 'TUÉ' : 'OFF'}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Conscious Friction Pause Settings */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Wind className="h-4 w-4 text-sky-500" />
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Pause Consciente (Respiration 4-7-8)
                </h3>
              </div>
              <span className="rounded-lg bg-sky-100 px-2 py-0.5 font-mono text-xs font-bold text-sky-700 dark:bg-sky-500/20 dark:text-sky-300">
                {sc.frictionDelaySec}s
              </span>
            </div>
            <input
              type="range"
              min={10}
              max={60}
              step={5}
              value={sc.frictionDelaySec}
              onChange={(e) => {
                const v = Number(e.target.value);
                if (!sc.canWeakenNow && v < sc.frictionDelaySec) return;
                void runSc(() =>
                  SentinelNative.updateSelfControlConfig({
                    selfControlEnabled: true,
                    frictionDelaySec: v,
                  })
                );
              }}
              className="w-full accent-sky-500"
            />
          </div>

          {/* Anti-Bypass & New-App Quarantine */}
          <div className="kl-card grid grid-cols-1 gap-2 p-4 sm:grid-cols-2">
            <button
              type="button"
              disabled={busy || (sc.quarantineNewApps && !sc.canWeakenNow)}
              onClick={() =>
                void runSc(() =>
                  SentinelNative.updateSelfControlConfig({
                    quarantineNewApps: !sc.quarantineNewApps,
                  })
                )
              }
              className={`flex items-center justify-between rounded-xl border p-3 text-left ${
                sc.quarantineNewApps
                  ? 'border-violet-200 bg-violet-50/70 dark:border-violet-500/40 dark:bg-violet-500/15'
                  : 'border-slate-200 bg-slate-50 dark:border-white/10'
              }`}
            >
              <div>
                <div className="text-xs font-bold text-slate-900 dark:text-white">
                  Quarantaine nouvelles apps
                </div>
                <div className="text-[11px] text-slate-500">
                  Bloque toute app fraîchement installée ({sc.quarantinedPackages.length})
                </div>
              </div>
              <span className="rounded-full bg-violet-500 px-2 py-0.5 text-[10px] font-bold text-white kl-solid-white">
                {sc.quarantineNewApps ? 'ON' : 'OFF'}
              </span>
            </button>

            <button
              type="button"
              disabled={busy || (sc.blockBypassApps && !sc.canWeakenNow)}
              onClick={() =>
                void runSc(() =>
                  SentinelNative.updateSelfControlConfig({
                    blockBypassApps: !sc.blockBypassApps,
                  })
                )
              }
              className={`flex items-center justify-between rounded-xl border p-3 text-left ${
                sc.blockBypassApps
                  ? 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-500/40 dark:bg-emerald-500/15'
                  : 'border-slate-200 bg-slate-50 dark:border-white/10'
              }`}
            >
              <div>
                <div className="text-xs font-bold text-slate-900 dark:text-white">
                  Bloqueur VPN tiers & Fake GPS
                </div>
                <div className="text-[11px] text-slate-500">
                  Stoppe VPN externes, Fake GPS & Cloneurs
                </div>
              </div>
              <span className="rounded-full bg-emerald-500 px-2 py-0.5 text-[10px] font-bold text-white kl-solid-white">
                {sc.blockBypassApps ? 'ON' : 'OFF'}
              </span>
            </button>
          </div>

          {/* Installed Apps Control List (Block / Friction / 5m Session / 4 Opens/day) */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Applications installées ({apps.length})
              </h3>
            </div>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-2.5 h-3.5 w-3.5 text-slate-400" />
              <input
                type="text"
                value={appQuery}
                onChange={(e) => setAppQuery(e.target.value)}
                placeholder="Rechercher TikTok, Instagram, YouTube, Jeux…"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 py-2 pl-8 pr-3 text-xs text-slate-900 dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
              />
            </div>

            <div className="max-h-96 space-y-1.5 overflow-y-auto pr-0.5">
              {filteredApps.map((app) => {
                const isBlocked = sc.blockedAppPackages.includes(app.packageName);
                const hasFriction = sc.frictionPackages.includes(app.packageName);
                const sessRule = sc.sessionQuotas.find((s) => s.packageName === app.packageName);
                const maxOpens = sc.maxDailyOpens[app.packageName] || 0;
                const todayOpens = sc.todayOpens[app.packageName] || 0;
                const isQuarantined = sc.quarantinedPackages.includes(app.packageName);

                return (
                  <div
                    key={app.packageName}
                    className="rounded-xl border border-slate-200 bg-slate-50/70 p-2.5 dark:border-white/10 dark:bg-white/[0.03]"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0">
                        <div className="truncate text-xs font-bold text-slate-900 dark:text-white">
                          {app.appName}
                        </div>
                        <div className="truncate text-[10px] text-slate-400">
                          {isQuarantined
                            ? 'En quarantaine (nouvelle app)'
                            : maxOpens > 0
                              ? `${todayOpens}/${maxOpens} ouvertures auj.`
                              : app.packageName}
                        </div>
                      </div>
                      <div className="flex shrink-0 flex-wrap items-center gap-1">
                        {isQuarantined && sc.canWeakenNow && (
                          <button
                            type="button"
                            onClick={() =>
                              void runSc(() =>
                                SentinelNative.updateSelfControlConfig({
                                  releaseQuarantinedPackage: app.packageName,
                                })
                              )
                            }
                            className="rounded-lg bg-emerald-500 px-2 py-1 text-[10px] font-bold text-white kl-solid-white"
                          >
                            Libérer
                          </button>
                        )}
                        <button
                          type="button"
                          disabled={busy || (isBlocked && !sc.canWeakenNow)}
                          onClick={() => {
                            const next = isBlocked
                              ? sc.blockedAppPackages.filter((x) => x !== app.packageName)
                              : [...sc.blockedAppPackages, app.packageName];
                            void runSc(() =>
                              SentinelNative.updateSelfControlConfig({
                                selfControlEnabled: true,
                                blockedAppPackages: next,
                              })
                            );
                          }}
                          className={`inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[10px] font-bold ${
                            isBlocked
                              ? 'bg-rose-500 text-white kl-solid-white'
                              : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                          }`}
                        >
                          <Ban className="h-3 w-3" />
                          <span>{isBlocked ? 'Bloqué' : 'Bloquer'}</span>
                        </button>

                        <button
                          type="button"
                          disabled={busy || (hasFriction && !sc.canWeakenNow)}
                          onClick={() => {
                            const next = hasFriction
                              ? sc.frictionPackages.filter((x) => x !== app.packageName)
                              : [...sc.frictionPackages, app.packageName];
                            void runSc(() =>
                              SentinelNative.updateSelfControlConfig({
                                selfControlEnabled: true,
                                frictionPackages: next,
                              })
                            );
                          }}
                          className={`inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[10px] font-bold ${
                            hasFriction
                              ? 'bg-sky-500 text-white kl-solid-white'
                              : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                          }`}
                        >
                          <Wind className="h-3 w-3" />
                          <span>Pause</span>
                        </button>

                        <button
                          type="button"
                          disabled={busy || (Boolean(sessRule) && !sc.canWeakenNow)}
                          onClick={() => {
                            const next = sessRule
                              ? sc.sessionQuotas.filter((s) => s.packageName !== app.packageName)
                              : [
                                  ...sc.sessionQuotas,
                                  {
                                    packageName: app.packageName,
                                    appName: app.appName,
                                    maxSessionMin: 5,
                                    cooldownMin: 30,
                                  },
                                ];
                            void runSc(() =>
                              SentinelNative.updateSelfControlConfig({
                                selfControlEnabled: true,
                                sessionQuotas: next,
                              })
                            );
                          }}
                          className={`inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[10px] font-bold ${
                            sessRule
                              ? 'bg-amber-500 text-white kl-solid-white'
                              : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                          }`}
                        >
                          <Timer className="h-3 w-3" />
                          <span>{sessRule ? `${sessRule.maxSessionMin}m/${sessRule.cooldownMin}m` : '5m max'}</span>
                        </button>

                        <button
                          type="button"
                          disabled={busy || (maxOpens > 0 && !sc.canWeakenNow)}
                          onClick={() => {
                            const next = { ...sc.maxDailyOpens };
                            if (maxOpens > 0) delete next[app.packageName];
                            else next[app.packageName] = 4;
                            void runSc(() =>
                              SentinelNative.updateSelfControlConfig({
                                selfControlEnabled: true,
                                maxDailyOpens: next,
                              })
                            );
                          }}
                          className={`rounded-lg px-2 py-1 text-[10px] font-bold ${
                            maxOpens > 0
                              ? 'bg-violet-500 text-white kl-solid-white'
                              : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                          }`}
                        >
                          {maxOpens > 0 ? `${maxOpens}×/j` : '4×/j'}
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* ==================== TAB 5: SOMMEIL, PAS, CRÉNEAUX & ZONES FOCUS ==================== */}
      {tab === 'habitudes' && (
        <div className="space-y-4">
          {/* Sleep, Wind-Down & Under-Blanket Detector */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <BedDouble className="h-4 w-4 text-indigo-500" />
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Sommeil, Voile Ambre & Détecteur Sous-Couette
                </h3>
              </div>
              <button
                type="button"
                disabled={busy || (sc.bedtimeEnabled && !sc.canWeakenNow)}
                onClick={() =>
                  void runSc(() =>
                    SentinelNative.updateSelfControlConfig({
                      selfControlEnabled: true,
                      bedtimeEnabled: !sc.bedtimeEnabled,
                    })
                  )
                }
                className={`rounded-full px-3 py-1 text-[11px] font-bold ${
                  sc.bedtimeEnabled
                    ? 'bg-indigo-500 text-white kl-solid-white'
                    : 'bg-slate-200 text-slate-700'
                }`}
              >
                {sc.bedtimeEnabled ? 'Actif' : 'Inactif'}
              </button>
            </div>

            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-[11px] text-slate-500">Coucher</label>
                <input
                  type="time"
                  disabled={!sc.canWeakenNow}
                  value={formatMinutesAsTime(sc.bedtimeStartMin)}
                  onChange={(e) =>
                    void runSc(() =>
                      SentinelNative.updateSelfControlConfig({
                        bedtimeStartMin: parseTimeToMinutes(e.target.value, sc.bedtimeStartMin),
                      })
                    )
                  }
                  className="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
                />
              </div>
              <div>
                <label className="text-[11px] text-slate-500">Réveil</label>
                <input
                  type="time"
                  disabled={!sc.canWeakenNow}
                  value={formatMinutesAsTime(sc.bedtimeEndMin)}
                  onChange={(e) =>
                    void runSc(() =>
                      SentinelNative.updateSelfControlConfig({
                        bedtimeEndMin: parseTimeToMinutes(e.target.value, sc.bedtimeEndMin),
                      })
                    )
                  }
                  className="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-bold dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <button
                type="button"
                disabled={busy || (sc.windDownEnabled && !sc.canWeakenNow)}
                onClick={() =>
                  void runSc(() =>
                    SentinelNative.updateSelfControlConfig({
                      selfControlEnabled: true,
                      windDownEnabled: !sc.windDownEnabled,
                    })
                  )
                }
                className={`flex items-center justify-between rounded-xl border p-3 text-left ${
                  sc.windDownEnabled
                    ? 'border-amber-200 bg-amber-50/70 dark:border-amber-500/40 dark:bg-amber-500/15'
                    : 'border-slate-200 bg-slate-50 dark:border-white/10'
                }`}
              >
                <div>
                  <div className="text-xs font-bold text-slate-900 dark:text-white">Voile Coucher progressif</div>
                  <div className="text-[11px] text-slate-500">Filtre chaud ambre 30 min avant le coucher</div>
                </div>
                <span className="rounded-full bg-amber-500 px-2 py-0.5 text-[10px] font-bold text-white kl-solid-white">
                  {sc.windDownEnabled ? 'ON' : 'OFF'}
                </span>
              </button>

              <button
                type="button"
                disabled={busy || (sc.bedDetectorEnabled && !sc.canWeakenNow)}
                onClick={() =>
                  void runSc(() =>
                    SentinelNative.updateSelfControlConfig({
                      selfControlEnabled: true,
                      bedDetectorEnabled: !sc.bedDetectorEnabled,
                    })
                  )
                }
                className={`flex items-center justify-between rounded-xl border p-3 text-left ${
                  sc.bedDetectorEnabled
                    ? 'border-indigo-200 bg-indigo-50/70 dark:border-indigo-500/40 dark:bg-indigo-500/15'
                    : 'border-slate-200 bg-slate-50 dark:border-white/10'
                }`}
              >
                <div>
                  <div className="text-xs font-bold text-slate-900 dark:text-white">Détecteur Sous la Couette</div>
                  <div className="text-[11px] text-slate-500">&lt; 3 lux + position allongée la nuit → pause 20m</div>
                </div>
                <span className="rounded-full bg-indigo-500 px-2 py-0.5 text-[10px] font-bold text-white kl-solid-white">
                  {sc.bedDetectorEnabled ? 'ON' : 'OFF'}
                </span>
              </button>
            </div>
          </div>

          {/* Steps: Morning Anti-Bed Challenge & Earn Your Time */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Footprints className="h-4 w-4 text-emerald-500" />
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Pas du matin & Gagner son temps
                </h3>
              </div>
              <span className="rounded-lg bg-emerald-100 px-2.5 py-0.5 font-mono text-xs font-bold text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300">
                {sc.stepsToday} pas · +{sc.earnedBonusMinutesToday}m gagnés
              </span>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="rounded-xl bg-slate-50 p-3 dark:bg-white/[0.04]">
                <div className="text-xs font-bold text-slate-900 dark:text-white">Défi Réveil Sans Lit</div>
                <div className="mt-0.5 text-[11px] text-slate-500">
                  Réseaux verrouillés au réveil (5h–12h) jusqu’à marcher :
                </div>
                <div className="mt-2 flex gap-1.5">
                  {[0, 30, 50, 100].map((v) => (
                    <button
                      key={v}
                      type="button"
                      disabled={busy || (!sc.canWeakenNow && v < sc.morningStepsRequired)}
                      onClick={() =>
                        void runSc(() =>
                          SentinelNative.updateSelfControlConfig({
                            selfControlEnabled: true,
                            morningStepsRequired: v,
                          })
                        )
                      }
                      className={`flex-1 rounded-lg py-1.5 text-[11px] font-bold ${
                        sc.morningStepsRequired === v
                          ? 'bg-emerald-500 text-white kl-solid-white'
                          : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                      }`}
                    >
                      {v === 0 ? 'Off' : `${v}`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="rounded-xl bg-slate-50 p-3 dark:bg-white/[0.04]">
                <div className="text-xs font-bold text-slate-900 dark:text-white">Gagner +15 min par marche</div>
                <div className="mt-0.5 text-[11px] text-slate-500">
                  Palier de pas donnant +15 min sur vos quotas :
                </div>
                <div className="mt-2 flex gap-1.5">
                  {[0, 1000, 2000, 5000].map((v) => (
                    <button
                      key={v}
                      type="button"
                      onClick={() =>
                        void runSc(() =>
                          SentinelNative.updateSelfControlConfig({
                            selfControlEnabled: true,
                            earnStepsTarget: v,
                            earnStepsRewardMin: 15,
                          })
                        )
                      }
                      className={`flex-1 rounded-lg py-1.5 text-[11px] font-bold ${
                        sc.earnStepsTarget === v
                          ? 'bg-sky-500 text-white kl-solid-white'
                          : 'bg-white text-slate-700 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-white/10'
                      }`}
                    >
                      {v === 0 ? 'Off' : `${v / 1000}k`}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* Weekly Multi-Slot Schedules */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center gap-2">
              <Clock className="h-4 w-4 text-sky-500" />
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Créneaux Hebdomadaires ({sc.weeklySchedules.length})
              </h3>
            </div>

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-4">
              <input
                type="text"
                value={newSchName}
                onChange={(e) => setNewSchName(e.target.value)}
                placeholder="Nom du créneau"
                className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs dark:border-white/10 dark:bg-white/[0.05] dark:text-white sm:col-span-2"
              />
              <input
                type="time"
                value={newSchStart}
                onChange={(e) => setNewSchStart(e.target.value)}
                className="rounded-xl border border-slate-200 bg-slate-50 px-2.5 py-2 text-xs font-bold dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
              />
              <input
                type="time"
                value={newSchEnd}
                onChange={(e) => setNewSchEnd(e.target.value)}
                className="rounded-xl border border-slate-200 bg-slate-50 px-2.5 py-2 text-xs font-bold dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
              />
            </div>

            <button
              type="button"
              disabled={busy}
              onClick={() => {
                const nextSlot: SelfControlScheduleSlot = {
                  id: `sch_${Date.now().toString(36)}`,
                  name: newSchName.trim() || 'Créneau Focus',
                  startMin: parseTimeToMinutes(newSchStart, 540),
                  endMin: parseTimeToMinutes(newSchEnd, 720),
                  daysMask: 31, // Lun-Ven
                  mode: 'block_social',
                  blockedPackages: [],
                };
                void runSc(() =>
                  SentinelNative.updateSelfControlConfig({
                    selfControlEnabled: true,
                    weeklySchedules: [...sc.weeklySchedules, nextSlot],
                  })
                );
              }}
              className="inline-flex items-center gap-1.5 rounded-xl bg-sky-500 px-3.5 py-2 text-xs font-bold text-white kl-solid-white hover:bg-sky-600"
            >
              <Plus className="h-3.5 w-3.5" />
              <span>Ajouter ce créneau (Lun–Ven)</span>
            </button>

            {sc.weeklySchedules.length > 0 && (
              <div className="space-y-1.5">
                {sc.weeklySchedules.map((slot) => (
                  <div
                    key={slot.id}
                    className="flex items-center justify-between rounded-xl bg-slate-50 px-3 py-2 text-xs dark:bg-white/[0.04]"
                  >
                    <div>
                      <strong className="text-slate-900 dark:text-white">{slot.name}</strong>{' '}
                      <span className="font-mono text-slate-500">
                        {formatMinutesAsTime(slot.startMin)} → {formatMinutesAsTime(slot.endMin)}
                      </span>
                    </div>
                    {sc.canWeakenNow && (
                      <button
                        type="button"
                        onClick={() =>
                          void runSc(() =>
                            SentinelNative.updateSelfControlConfig({
                              weeklySchedules: sc.weeklySchedules.filter((s) => s.id !== slot.id),
                            })
                          )
                        }
                        className="text-rose-500 hover:text-rose-600"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Reverse Auto-Focus Geofences */}
          <div className="kl-card space-y-3 p-4">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4 text-emerald-500" />
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Zones Focus Automatiques (Géofencing inversé)
              </h3>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Activez automatiquement le blocage dès votre arrivée au bureau, à la fac ou le soir à la maison (basé sur votre position GPS actuelle).
            </p>
            <div className="flex flex-wrap gap-2">
              <input
                type="text"
                value={newZoneName}
                onChange={(e) => setNewZoneName(e.target.value)}
                placeholder="Nom du lieu (Bureau, Fac…)"
                className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
              />
              <select
                value={newZoneMode}
                onChange={(e) => setNewZoneMode(e.target.value as 'deep_work' | 'night_curfew')}
                className="rounded-xl border border-slate-200 bg-slate-50 px-2.5 py-2 text-xs font-semibold dark:border-white/10 dark:bg-slate-900 dark:text-white"
              >
                <option value="deep_work">Deep Work (toute la journée)</option>
                <option value="night_curfew">Couvre-feu Maison (&gt; 22h)</option>
              </select>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  const lat = sc.geoStatus?.hasFix ? sc.geoStatus.currentLat : effectiveLat;
                  const lng = sc.geoStatus?.hasFix ? sc.geoStatus.currentLng : effectiveLng;
                  if (Math.abs(lat) < 0.0001 && Math.abs(lng) < 0.0001) {
                    setFeedback({
                      ok: false,
                      text: 'Ouvrez d’abord l’onglet Carte et cliquez « Ma position » pour obtenir un point GPS.',
                    });
                    return;
                  }
                  void runSc(() =>
                    SentinelNative.updateSelfControlConfig({
                      selfControlEnabled: true,
                      focusGeofences: [
                        ...sc.focusGeofences,
                        {
                          id: `fg_${Date.now().toString(36)}`,
                          name: newZoneName.trim() || 'Zone Focus',
                          latitude: lat,
                          longitude: lng,
                          radiusMeters: 150,
                          mode: newZoneMode,
                          insideNow: false,
                        },
                      ],
                    })
                  );
                }}
                className="inline-flex items-center gap-1 rounded-xl bg-emerald-500 px-3 py-2 text-xs font-bold text-white kl-solid-white"
              >
                <Plus className="h-3.5 w-3.5" />
                <span>Ici</span>
              </button>
            </div>

            {sc.focusGeofences.length > 0 && (
              <div className="space-y-1.5">
                {sc.focusGeofences.map((z) => (
                  <div
                    key={z.id}
                    className="flex items-center justify-between rounded-xl bg-slate-50 px-3 py-2 text-xs dark:bg-white/[0.04]"
                  >
                    <div>
                      <strong className="text-slate-900 dark:text-white">{z.name}</strong>{' '}
                      <span className="text-[11px] text-slate-500">
                        ({z.mode === 'deep_work' ? 'Deep Work' : 'Maison > 22h'} · {z.radiusMeters} m)
                      </span>
                      {z.insideNow && (
                        <span className="ml-2 rounded-full bg-emerald-500 px-1.5 py-0.5 text-[9px] font-bold text-white kl-solid-white">
                          ACTIF
                        </span>
                      )}
                    </div>
                    {sc.canWeakenNow && (
                      <button
                        type="button"
                        onClick={() =>
                          void runSc(() =>
                            SentinelNative.updateSelfControlConfig({
                              focusGeofences: sc.focusGeofences.filter((x) => x.id !== z.id),
                            })
                          )
                        }
                        className="text-rose-500"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ==================== SEAL CONFIRMATION MODAL ==================== */}
      {sealConfirmOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-sm">
          <div className="kl-card w-full max-w-sm space-y-3 p-5 shadow-2xl">
            <div className="flex items-center gap-2 text-sm font-extrabold text-slate-900 dark:text-white">
              <Lock className="h-5 w-5 text-sky-500" />
              <span>Confirmer le scellement du Pacte</span>
            </div>
            <p className="text-xs leading-relaxed text-slate-600 dark:text-slate-300">
              Une fois scellé sur <strong>« {draftLabel} »</strong> (rayon {draftRadius} m, présence{' '}
              {Math.floor(draftDwellSec / 60)}m{draftDwellSec % 60}s), vous pourrez toujours{' '}
              <strong>durcir</strong> vos protections, mais il sera{' '}
              <strong>impossible d’assouplir, désactiver ou désinstaller</strong> sans vous rendre
              physiquement à cet endroit exact.
            </p>
            <div className="flex gap-2 pt-1">
              <button
                type="button"
                onClick={() => setSealConfirmOpen(false)}
                className="flex-1 rounded-xl border border-slate-200 bg-slate-50 py-2.5 text-xs font-bold text-slate-700 dark:border-white/10 dark:bg-white/[0.05] dark:text-slate-200"
              >
                Annuler
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={async () => {
                  setSealConfirmOpen(false);
                  await runSc(() =>
                    SentinelNative.sealSelfControlPact({
                      latitude: effectiveLat,
                      longitude: effectiveLng,
                      radiusMeters: draftRadius,
                      label: draftLabel,
                      dwellSeconds: draftDwellSec,
                      coolingOffHours: draftCoolingH,
                      partnerCode: draftPartnerCode || undefined,
                      qrSecret: draftQrSecret || undefined,
                      partnerAlertTopic: draftPartnerTopic || undefined,
                    })
                  );
                }}
                className="flex-1 rounded-xl bg-emerald-500 py-2.5 text-xs font-extrabold text-white kl-solid-white hover:bg-emerald-600"
              >
                Sceller maintenant
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
