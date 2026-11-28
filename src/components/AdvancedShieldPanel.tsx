import React, { useEffect, useState } from 'react';
import {
  EyeOff,
  Flame,
  Layers,
  Lock,
  PanelTopClose,
  RotateCcw,
  Scissors,
  ShieldAlert,
  ShieldCheck,
  Snowflake,
  Sparkles,
  Zap,
} from 'lucide-react';
import type { DataScreenTimeMsg, DataSystemStatusMsg } from '../types/sentinel';
import { DnsFirewallCard } from './DnsFirewallCard';

interface AdvancedShieldPanelProps {
  screenTime: DataScreenTimeMsg | null;
  systemStatus: DataSystemStatusMsg | null;
  nowMs: number;
  onUpdateAdvanced: (opts: {
    readOnlyMinutes?: number;
    reelsSwipeLimit?: number;
    reelsCooldownMin?: number;
    resetReelsCooldown?: boolean;
    incognitoBlockEnabled?: boolean;
    webKeywordGuardEnabled?: boolean;
    surgicalSettingsPinEnabled?: boolean;
    multiWindowGuardEnabled?: boolean;
    quickSettingsGuardEnabled?: boolean;
    backdoorGuardEnabled?: boolean;
    dnsVpnEnabled?: boolean;
    dnsCategories?: string[];
    dnsForceSafeSearch?: boolean;
    dnsCoupleAppWeb?: boolean;
    dnsBlacklist?: string[];
    dnsWhitelist?: string[];
    dnsBlockedKeywords?: string[];
    feedKillerTargets?: string[];
  }) => Promise<void>;
  onMdmHardenAll?: () => Promise<void>;
}

const PARENT_FEED_KILLERS = [
  { id: 'yt_shorts', label: 'YouTube Shorts' },
  { id: 'ig_reels', label: 'Instagram Reels' },
  { id: 'snap_spotlight', label: 'Snapchat Spotlight' },
  { id: 'fb_reels', label: 'Facebook Reels' },
  { id: 'x_foryou', label: 'X « Pour vous »' },
];

const REELS_PRESETS = [
  { val: 0, label: 'Illimité' },
  { val: 10, label: '10 vidéos' },
  { val: 15, label: '15 vidéos' },
  { val: 25, label: '25 vidéos' },
  { val: 40, label: '40 vidéos' },
];

const FREEZE_PRESETS = [
  { min: 0, label: 'Désactiver' },
  { min: 10, label: '10 min' },
  { min: 30, label: '30 min' },
  { min: 60, label: '1 heure' },
];

/**
 * Clean Kidslox-Style Control Panel for ALL v27 Hors-MDM & Shield Superpowers:
 * - #8 Shield Score /100 + 1-Click MDM Harden (#2/#3/#8)
 * - #31 Read-Only Screen Freeze (Écran Lecture Seule)
 * - #46 Reels / Shorts / TikTok Swipe Quota + Cooldown
 * - #11 Browser Incognito Block & Real-Time Web Keyword Guard
 * - #58 Surgical Settings PIN Shield, #59 Multi-Window Guard, #60 Quick Settings Lock, #68 Backdoor Guard
 */
export const AdvancedShieldPanel: React.FC<AdvancedShieldPanelProps> = ({
  screenTime,
  systemStatus,
  nowMs,
  onUpdateAdvanced,
  onMdmHardenAll,
}) => {
  const [reelsCooldown, setReelsCooldown] = useState(15);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  useEffect(() => {
    if (screenTime?.reelsCooldownMin) {
      setReelsCooldown(screenTime.reelsCooldownMin);
    }
  }, [screenTime?.reelsCooldownMin]);

  const shieldScore = screenTime?.shieldScore ?? systemStatus?.shieldScore ?? 0;
  const isMdm = Boolean(systemStatus?.mdmDeviceOwner);
  const readOnlyUntil = screenTime?.readOnlyUntilMs ?? 0;
  const isReadOnly = readOnlyUntil > nowMs;
  const reelsLimit = screenTime?.reelsSwipeLimit ?? 0;
  const reelsToday = screenTime?.reelsSwipesToday ?? 0;
  const reelsSession = screenTime?.reelsSwipesSession ?? 0;
  const reelsCooling = (screenTime?.reelsCooldownUntilMs ?? 0) > nowMs;

  const incognitoOn = screenTime?.incognitoBlockEnabled ?? true;
  const webKeywordsOn = screenTime?.webKeywordGuardEnabled ?? true;
  const surgicalPinOn = screenTime?.surgicalSettingsPinEnabled ?? true;
  const multiWindowOn = screenTime?.multiWindowGuardEnabled ?? true;
  const quickSettingsOn = screenTime?.quickSettingsGuardEnabled ?? true;
  const backdoorOn = screenTime?.backdoorGuardEnabled ?? true;

  const runAction = async (key: string, fn: () => Promise<void>) => {
    setBusyKey(key);
    try {
      await fn();
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <div className="space-y-4">
      {/* ==================== #8 SHIELD SCORE & 1-CLICK HARDEN ==================== */}
      <div className="kl-card p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div
              className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl text-sm font-extrabold text-white kl-solid-white ${
                shieldScore >= 80
                  ? 'bg-emerald-500'
                  : shieldScore >= 55
                    ? 'bg-amber-500'
                    : 'bg-rose-500'
              }`}
            >
              {shieldScore}%
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Santé du Blindage Anti-Désinstallation
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                {isMdm
                  ? 'Mode Appareil Géré (MDM) + Triple Chien de Garde + SQLite WAL actifs'
                  : 'Mode Code 6 chiffres · Protections Accessibilité & Anti-contournement actives'}
              </p>
            </div>
          </div>

          {isMdm && onMdmHardenAll && (
            <button
              type="button"
              disabled={busyKey === 'harden'}
              onClick={() => void runAction('harden', onMdmHardenAll)}
              className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-500 px-3.5 py-2 text-xs font-bold text-white kl-solid-white shadow-sm shadow-emerald-500/25 transition hover:bg-emerald-600 disabled:opacity-50"
            >
              <Zap className="h-3.5 w-3.5" />
              <span>{busyKey === 'harden' ? 'Blindage…' : 'Durcir tout en 1 clic'}</span>
            </button>
          )}
        </div>

        <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              shieldScore >= 80 ? 'bg-emerald-500' : shieldScore >= 55 ? 'bg-amber-500' : 'bg-rose-500'
            }`}
            style={{ width: `${Math.max(8, shieldScore)}%` }}
          />
        </div>
      </div>

      {/* ==================== #31 ÉCRAN CONGELÉ / LECTURE SEULE ==================== */}
      <div className="kl-card p-4 sm:p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-amber-50 text-amber-600 dark:bg-amber-500/15 dark:text-amber-300">
              <Snowflake className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Écran Lecture Seule (Gel Tactile)
                </h3>
                {isReadOnly && (
                  <span className="rounded-full bg-amber-500 px-2 py-0.5 text-[10px] font-bold text-white kl-solid-white">
                    ACTIF
                  </span>
                )}
              </div>
              <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                Laisse voir le cours ou la vidéo affichée mais bloque 100 % des appuis tactiles (fonctionne avec le code 6 chiffres).
              </p>
            </div>
          </div>
        </div>

        <div className="mt-3 grid grid-cols-4 gap-2">
          {FREEZE_PRESETS.map((p) => {
            const active = p.min === 0 ? !isReadOnly : isReadOnly && p.min === 10;
            return (
              <button
                key={p.min}
                type="button"
                disabled={busyKey === `freeze_${p.min}`}
                onClick={() =>
                  void runAction(`freeze_${p.min}`, () =>
                    onUpdateAdvanced({ readOnlyMinutes: p.min })
                  )
                }
                className={`rounded-xl border px-2 py-2 text-xs font-semibold transition ${
                  p.min === 0 && isReadOnly
                    ? 'border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100 dark:border-rose-500/30 dark:bg-rose-500/15 dark:text-rose-200'
                    : active
                      ? 'border-amber-400 bg-amber-500 text-white kl-solid-white'
                      : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
                }`}
              >
                {p.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* ==================== #46 QUOTA REELS / SHORTS / TIKTOK PAR SWIPES ==================== */}
      <div className="kl-card p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600 dark:bg-rose-500/15 dark:text-rose-300">
              <Flame className="h-5 w-5" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Quota Vidéos Courtes (TikTok, Shorts, Reels)
              </h3>
              <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                Coupe automatiquement le scroll infini après un nombre précis de vidéos swipées.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 text-xs">
            <span className="rounded-lg bg-slate-100 px-2.5 py-1 font-mono font-semibold text-slate-700 dark:bg-white/10 dark:text-slate-200">
              {reelsLimit > 0 ? `${reelsSession}/${reelsLimit} session` : 'Libre'} · {reelsToday} auj.
            </span>
            {reelsCooling && (
              <button
                type="button"
                onClick={() =>
                  void runAction('reels_reset', () => onUpdateAdvanced({ resetReelsCooldown: true }))
                }
                className="inline-flex items-center gap-1 rounded-lg bg-emerald-500 px-2.5 py-1 font-semibold text-white kl-solid-white hover:bg-emerald-600"
              >
                <RotateCcw className="h-3 w-3" />
                Débloquer
              </button>
            )}
          </div>
        </div>

        <div className="mt-3 grid grid-cols-5 gap-1.5">
          {REELS_PRESETS.map((opt) => (
            <button
              key={opt.val}
              type="button"
              onClick={() =>
                void runAction(`reels_${opt.val}`, () =>
                  onUpdateAdvanced({ reelsSwipeLimit: opt.val, reelsCooldownMin: reelsCooldown })
                )
              }
              className={`rounded-xl border px-1.5 py-2 text-[11px] font-semibold transition ${
                reelsLimit === opt.val
                  ? 'border-sky-500 bg-sky-500 text-white kl-solid-white shadow-sm'
                  : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-300'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        {reelsLimit > 0 && (
          <div className="mt-3 flex items-center justify-between gap-2 rounded-xl bg-slate-50 px-3 py-2 text-xs dark:bg-white/[0.04]">
            <span className="text-slate-600 dark:text-slate-300">Durée de pause après {reelsLimit} swipes :</span>
            <div className="flex gap-1">
              {[10, 15, 30, 60].map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => {
                    setReelsCooldown(m);
                    void onUpdateAdvanced({ reelsSwipeLimit: reelsLimit, reelsCooldownMin: m });
                  }}
                  className={`rounded-lg px-2 py-1 text-[11px] font-semibold ${
                    reelsCooldown === m
                      ? 'bg-sky-500 text-white kl-solid-white'
                      : 'bg-white text-slate-600 ring-1 ring-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:ring-white/10'
                  }`}
                >
                  {m}m
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* ==================== #11 + #58 + #59 + #60 + #68 BOUCLIERS HORS-MDM ==================== */}
      <div className="kl-card p-4 sm:p-5">
        <div className="mb-3 flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-emerald-500" />
          <h3 className="text-sm font-bold text-slate-900 dark:text-white">
            Protections Anti-Contournement & Web (Code 6 chiffres)
          </h3>
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {(
            [
              {
                key: 'incognito',
                title: 'Bloquer Navigation Privée',
                desc: 'Ferme automatiquement les onglets Incognito dans les navigateurs.',
                active: incognitoOn,
                Icon: EyeOff,
                onToggle: () => onUpdateAdvanced({ incognitoBlockEnabled: !incognitoOn }),
              },
              {
                key: 'webkw',
                title: 'Alertes Mots-Clés Web',
                desc: 'Détecte les recherches sensibles en direct dans Chrome/Firefox.',
                active: webKeywordsOn,
                Icon: Sparkles,
                onToggle: () => onUpdateAdvanced({ webKeywordGuardEnabled: !webKeywordsOn }),
              },
              {
                key: 'surgical',
                title: 'PIN Chirurgical Paramètres',
                desc: 'Laisse Wi-Fi/Son ouverts mais bloque Admin, Accessibilité, Date & Reset.',
                active: surgicalPinOn,
                Icon: Lock,
                onToggle: () => onUpdateAdvanced({ surgicalSettingsPinEnabled: !surgicalPinOn }),
              },
              {
                key: 'multiwin',
                title: 'Blocage Écran Scindé & Bulles',
                desc: 'Inspecte toutes les fenêtres visibles contre les contournements PiP.',
                active: multiWindowOn,
                Icon: Layers,
                onToggle: () => onUpdateAdvanced({ multiWindowGuardEnabled: !multiWindowOn }),
              },
              {
                key: 'quickshade',
                title: 'Verrou Volet Tuiles Rapides',
                desc: 'Referme les réglages rapides sous Couvre-feu / Pause / Mode Perdu.',
                active: quickSettingsOn,
                Icon: PanelTopClose,
                onToggle: () => onUpdateAdvanced({ quickSettingsGuardEnabled: !quickSettingsOn }),
              },
              {
                key: 'backdoor',
                title: 'Bloqueur Portes Dérobées',
                desc: 'Bloque « Infos appli » & boîtes de désinstallation ciblant SentinelCam.',
                active: backdoorOn,
                Icon: ShieldAlert,
                onToggle: () => onUpdateAdvanced({ backdoorGuardEnabled: !backdoorOn }),
              },
            ] as const
          ).map(({ key, title, desc, active, Icon, onToggle }) => (
            <button
              key={key}
              type="button"
              disabled={busyKey === key}
              onClick={() => void runAction(key, onToggle)}
              className={`flex items-start justify-between gap-3 rounded-xl border p-3 text-left transition ${
                active
                  ? 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-500/40 dark:bg-emerald-500/10'
                  : 'border-slate-200 bg-slate-50/70 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.03]'
              }`}
            >
              <div className="flex min-w-0 items-start gap-2.5">
                <Icon
                  className={`mt-0.5 h-4 w-4 shrink-0 ${
                    active ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'
                  }`}
                />
                <div className="min-w-0">
                  <div className="text-xs font-bold text-slate-900 dark:text-white">{title}</div>
                  <div className="mt-0.5 text-[11px] leading-snug text-slate-500 dark:text-slate-400">
                    {desc}
                  </div>
                </div>
              </div>
              <span
                className={`mt-0.5 shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold ${
                  active
                    ? 'bg-emerald-500 text-white kl-solid-white'
                    : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                }`}
              >
                {active ? 'ON' : 'OFF'}
              </span>
            </button>
          ))}
        </div>
      </div>

      {/* ==================== V28 IN-APP ADDICTIVE FEED KILLER (PARENT REMOTE) ==================== */}
      <div className="kl-card p-4 sm:p-5">
        <div className="mb-2 flex items-center gap-2">
          <Scissors className="h-4 w-4 text-rose-500" />
          <h3 className="text-sm font-bold text-slate-900 dark:text-white">
            Tueur de Flux Addictifs In-App (Shorts, Reels, Spotlight)
          </h3>
        </div>
        <p className="mb-3 text-xs text-slate-500 dark:text-slate-400">
          Bloque uniquement les flux infinis dans l’application tout en laissant YouTube long ou les messages utilisables.
        </p>
        <div className="flex flex-wrap gap-2">
          {PARENT_FEED_KILLERS.map((fk) => {
            const current = screenTime?.feedKillerTargets ?? [];
            const active = current.includes(fk.id);
            return (
              <button
                key={fk.id}
                type="button"
                disabled={busyKey === `fk_${fk.id}`}
                onClick={() => {
                  const next = active
                    ? current.filter((x) => x !== fk.id)
                    : [...current, fk.id];
                  void runAction(`fk_${fk.id}`, () =>
                    onUpdateAdvanced({ feedKillerTargets: next })
                  );
                }}
                className={`rounded-xl border px-3 py-2 text-xs font-bold transition ${
                  active
                    ? 'border-rose-300 bg-rose-500 text-white kl-solid-white shadow-sm'
                    : 'border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
                }`}
              >
                {fk.label} {active ? '• TUÉ' : ''}
              </button>
            );
          })}
        </div>
      </div>

      {/* ==================== V28 BUILT-IN LOCAL DNS / VPN FIREWALL (PARENT REMOTE) ==================== */}
      <DnsFirewallCard
        dnsVpnEnabled={Boolean(screenTime?.dnsVpnEnabled)}
        dnsVpnRunning={Boolean(screenTime?.dnsVpnRunning)}
        vpnConsented={screenTime?.dnsVpnConsented ?? true}
        dnsCategories={screenTime?.dnsCategories ?? []}
        dnsForceSafeSearch={screenTime?.dnsForceSafeSearch ?? true}
        dnsCoupleAppWeb={screenTime?.dnsCoupleAppWeb ?? true}
        dnsBlacklist={screenTime?.dnsBlacklist ?? []}
        dnsWhitelist={screenTime?.dnsWhitelist ?? []}
        dnsBlockedKeywords={screenTime?.dnsBlockedKeywords ?? []}
        dnsBlockedTodayCount={screenTime?.dnsBlockedTodayCount ?? 0}
        recentDnsLogs={screenTime?.recentDnsLogs ?? []}
        canWeaken={true}
        onUpdateDns={async (patch) => {
          await onUpdateAdvanced(patch);
        }}
      />
    </div>
  );
};
