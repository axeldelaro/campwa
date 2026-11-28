import React, { useState } from 'react';
import {
  Ban,
  CheckCircle2,
  Coins,
  Download,
  Film,
  Gamepad2,
  Globe,
  Link2,
  Lock,
  Newspaper,
  Plus,
  SearchCheck,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Trash2,
  Users,
} from 'lucide-react';
import type { DnsLogEntry } from '../types/sentinel';

export const DNS_CATEGORY_META: Array<{
  id: string;
  label: string;
  desc: string;
  Icon: React.FC<{ className?: string }>;
}> = [
  { id: 'adult', label: 'Adulte / Porno', desc: 'Sites X, cams, contenu 18+', Icon: ShieldAlert },
  { id: 'social', label: 'Réseaux Web', desc: 'TikTok, Insta, X, Reddit, Snap', Icon: Users },
  { id: 'streaming', label: 'Streaming & Vidéo', desc: 'YouTube, Twitch, Netflix, VOD', Icon: Film },
  { id: 'gaming', label: 'Jeux en ligne', desc: 'Roblox, Steam, jeux web', Icon: Gamepad2 },
  { id: 'gambling_crypto', label: 'Paris & Crypto', desc: 'Paris sportifs, casino, trading', Icon: Coins },
  { id: 'news', label: 'Actus anxiogènes', desc: 'Chaînes info & flux continus', Icon: Newspaper },
  { id: 'ai_unfiltered', label: 'IA non filtrées', desc: 'Chatbots RP & IA sans filtre', Icon: Sparkles },
  { id: 'apk_proxy', label: 'APK & VPN/Proxy', desc: 'Sites APK, proxies web, VPN tiers', Icon: Download },
];

interface DnsFirewallCardProps {
  dnsVpnEnabled: boolean;
  dnsVpnRunning: boolean;
  vpnConsented?: boolean;
  dnsCategories: string[];
  dnsForceSafeSearch: boolean;
  dnsCoupleAppWeb: boolean;
  dnsBlacklist: string[];
  dnsWhitelist: string[];
  dnsBlockedKeywords: string[];
  dnsBlockedTodayCount: number;
  recentDnsLogs: DnsLogEntry[];
  canWeaken: boolean;
  isNativeSelfControl?: boolean;
  onRequestVpnConsent?: () => Promise<void>;
  onUpdateDns: (patch: {
    dnsVpnEnabled?: boolean;
    dnsCategories?: string[];
    dnsForceSafeSearch?: boolean;
    dnsCoupleAppWeb?: boolean;
    dnsBlacklist?: string[];
    dnsWhitelist?: string[];
    dnsBlockedKeywords?: string[];
  }) => Promise<void>;
}

export const DnsFirewallCard: React.FC<DnsFirewallCardProps> = ({
  dnsVpnEnabled,
  dnsVpnRunning,
  vpnConsented = true,
  dnsCategories,
  dnsForceSafeSearch,
  dnsCoupleAppWeb,
  dnsBlacklist,
  dnsWhitelist,
  dnsBlockedKeywords,
  dnsBlockedTodayCount,
  recentDnsLogs,
  canWeaken,
  isNativeSelfControl = false,
  onRequestVpnConsent,
  onUpdateDns,
}) => {
  const [newBlacklist, setNewBlacklist] = useState('');
  const [newWhitelist, setNewWhitelist] = useState('');
  const [newKeyword, setNewKeyword] = useState('');
  const [showLogs, setShowLogs] = useState(false);
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
    } finally {
      setBusy(false);
    }
  };

  const toggleCategory = (catId: string) => {
    const exists = dnsCategories.includes(catId);
    if (exists && !canWeaken) return;
    const next = exists
      ? dnsCategories.filter((c) => c !== catId)
      : [...dnsCategories, catId];
    void run(() => onUpdateDns({ dnsCategories: next, dnsVpnEnabled: true }));
  };

  const addDomain = (kind: 'black' | 'white' | 'kw') => {
    const raw = (kind === 'black' ? newBlacklist : kind === 'white' ? newWhitelist : newKeyword)
      .trim()
      .toLowerCase()
      .replace(/^https?:\/\//, '')
      .replace(/^www\./, '')
      .split('/')[0];
    if (!raw || raw.length < 3) return;
    if (kind === 'black') {
      setNewBlacklist('');
      void run(() => onUpdateDns({ dnsBlacklist: Array.from(new Set([...dnsBlacklist, raw])) }));
    } else if (kind === 'white') {
      if (!canWeaken) return;
      setNewWhitelist('');
      void run(() => onUpdateDns({ dnsWhitelist: Array.from(new Set([...dnsWhitelist, raw])) }));
    } else {
      setNewKeyword('');
      void run(() =>
        onUpdateDns({ dnsBlockedKeywords: Array.from(new Set([...dnsBlockedKeywords, raw])) })
      );
    }
  };

  return (
    <div className="space-y-4">
      {/* Main DNS/VPN Firewall Header */}
      <div className="kl-card p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div
              className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl ${
                dnsVpnRunning
                  ? 'bg-emerald-500 text-white kl-solid-white shadow-sm shadow-emerald-500/25'
                  : 'bg-sky-100 text-sky-600 dark:bg-sky-500/20 dark:text-sky-300'
              }`}
            >
              <Globe className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                  Pare-feu DNS / VPN Local
                </h3>
                <span
                  className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                    dnsVpnRunning
                      ? 'bg-emerald-500 text-white kl-solid-white'
                      : dnsVpnEnabled
                        ? 'bg-amber-500 text-white kl-solid-white'
                        : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                  }`}
                >
                  {dnsVpnRunning ? 'ACTIF' : dnsVpnEnabled ? 'EN ATTENTE VPN' : 'OFF'}
                </span>
              </div>
              <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                100 % local sans serveur · Wi-Fi + 4G/5G ·{' '}
                <strong className="text-slate-800 dark:text-slate-200">
                  {dnsBlockedTodayCount} blocage(s) aujourd’hui
                </strong>
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {isNativeSelfControl && onRequestVpnConsent && (!vpnConsented || !dnsVpnRunning) ? (
              <button
                type="button"
                disabled={busy}
                onClick={() => void run(onRequestVpnConsent)}
                className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-500 px-3.5 py-2 text-xs font-bold text-white kl-solid-white shadow-sm hover:bg-emerald-600 disabled:opacity-50"
              >
                <ShieldCheck className="h-3.5 w-3.5" />
                <span>Activer le Pare-feu VPN</span>
              </button>
            ) : (
              <button
                type="button"
                disabled={busy || (dnsVpnEnabled && !canWeaken)}
                onClick={() => void run(() => onUpdateDns({ dnsVpnEnabled: !dnsVpnEnabled }))}
                className={`inline-flex items-center gap-1.5 rounded-xl px-3.5 py-2 text-xs font-bold transition disabled:opacity-45 ${
                  dnsVpnEnabled
                    ? 'border border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-500/40 dark:bg-emerald-500/15 dark:text-emerald-200'
                    : 'bg-sky-500 text-white kl-solid-white hover:bg-sky-600'
                }`}
              >
                {dnsVpnEnabled && !canWeaken ? <Lock className="h-3.5 w-3.5" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
                <span>{dnsVpnEnabled ? (canWeaken ? 'Actif (Cliquer OFF)' : 'Scellé ON') : 'Activer DNS'}</span>
              </button>
            )}
          </div>
        </div>

        {/* 8 Built-In Category Filters */}
        <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {DNS_CATEGORY_META.map(({ id, label, desc, Icon }) => {
            const active = dnsCategories.includes(id);
            const lockedOn = active && !canWeaken;
            return (
              <button
                key={id}
                type="button"
                disabled={busy || lockedOn}
                onClick={() => toggleCategory(id)}
                className={`flex flex-col items-start rounded-xl border p-2.5 text-left transition ${
                  active
                    ? 'border-emerald-300 bg-emerald-50/80 dark:border-emerald-500/40 dark:bg-emerald-500/15'
                    : 'border-slate-200 bg-slate-50/70 hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.03]'
                }`}
              >
                <div className="flex w-full items-center justify-between">
                  <Icon
                    className={`h-4 w-4 ${
                      active ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'
                    }`}
                  />
                  {lockedOn ? (
                    <Lock className="h-3 w-3 text-emerald-600 dark:text-emerald-400" />
                  ) : (
                    <span
                      className={`rounded-full px-1.5 py-0.5 text-[9px] font-bold ${
                        active
                          ? 'bg-emerald-500 text-white kl-solid-white'
                          : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                      }`}
                    >
                      {active ? 'BLOQUÉ' : 'LIBRE'}
                    </span>
                  )}
                </div>
                <div className="mt-2 text-xs font-bold text-slate-900 dark:text-white">{label}</div>
                <div className="mt-0.5 text-[10px] leading-tight text-slate-500 dark:text-slate-400">
                  {desc}
                </div>
              </button>
            );
          })}
        </div>

        {/* SafeSearch & Absolute App <-> Web Coupling */}
        <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
          <button
            type="button"
            disabled={busy || (dnsForceSafeSearch && !canWeaken)}
            onClick={() => void run(() => onUpdateDns({ dnsForceSafeSearch: !dnsForceSafeSearch }))}
            className={`flex items-center justify-between gap-2 rounded-xl border p-3 text-left transition ${
              dnsForceSafeSearch
                ? 'border-sky-200 bg-sky-50/70 dark:border-sky-500/40 dark:bg-sky-500/10'
                : 'border-slate-200 bg-slate-50 dark:border-white/10 dark:bg-white/[0.03]'
            }`}
          >
            <div className="flex items-start gap-2.5">
              <SearchCheck className="mt-0.5 h-4 w-4 shrink-0 text-sky-500" />
              <div>
                <div className="text-xs font-bold text-slate-900 dark:text-white">
                  SafeSearch & YouTube Restreint
                </div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400">
                  Force Google, Bing, DuckDuckGo et YouTube en mode filtré via DNS.
                </div>
              </div>
            </div>
            <span
              className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                dnsForceSafeSearch ? 'bg-sky-500 text-white kl-solid-white' : 'bg-slate-200 text-slate-600'
              }`}
            >
              {dnsForceSafeSearch ? 'ON' : 'OFF'}
            </span>
          </button>

          <button
            type="button"
            disabled={busy || (dnsCoupleAppWeb && !canWeaken)}
            onClick={() => void run(() => onUpdateDns({ dnsCoupleAppWeb: !dnsCoupleAppWeb }))}
            className={`flex items-center justify-between gap-2 rounded-xl border p-3 text-left transition ${
              dnsCoupleAppWeb
                ? 'border-sky-200 bg-sky-50/70 dark:border-sky-500/40 dark:bg-sky-500/10'
                : 'border-slate-200 bg-slate-50 dark:border-white/10 dark:bg-white/[0.03]'
            }`}
          >
            <div className="flex items-start gap-2.5">
              <Link2 className="mt-0.5 h-4 w-4 shrink-0 text-sky-500" />
              <div>
                <div className="text-xs font-bold text-slate-900 dark:text-white">
                  Couplage absolu App ↔ Web
                </div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400">
                  Bloquer TikTok, Insta ou YouTube coupe aussi leur site sur tous les navigateurs.
                </div>
              </div>
            </div>
            <span
              className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                dnsCoupleAppWeb ? 'bg-sky-500 text-white kl-solid-white' : 'bg-slate-200 text-slate-600'
              }`}
            >
              {dnsCoupleAppWeb ? 'ON' : 'OFF'}
            </span>
          </button>
        </div>
      </div>

      {/* Custom Blacklist / Keywords / Whitelist */}
      <div className="kl-card space-y-3 p-4">
        <div className="text-xs font-bold text-slate-900 dark:text-white">
          Domaines & mots-clés personnalisés
        </div>

        {/* Add to Blacklist (ALWAYS allowed — hardening!) */}
        <div className="flex gap-2">
          <input
            type="text"
            value={newBlacklist}
            onChange={(e) => setNewBlacklist(e.target.value)}
            placeholder="Bloquer un domaine (ex: twitch.tv, reddit.com)"
            className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 placeholder-slate-400 focus:border-sky-400 focus:outline-none dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
          />
          <button
            type="button"
            disabled={busy || newBlacklist.trim().length < 3}
            onClick={() => addDomain('black')}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-rose-500 px-3 py-2 text-xs font-bold text-white kl-solid-white hover:bg-rose-600 disabled:opacity-40"
          >
            <Ban className="h-3.5 w-3.5" />
            <span>Bloquer</span>
          </button>
        </div>

        {dnsBlacklist.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {dnsBlacklist.map((d) => (
              <span
                key={d}
                className="inline-flex items-center gap-1 rounded-lg bg-rose-50 px-2 py-1 text-[11px] font-semibold text-rose-700 ring-1 ring-rose-200 dark:bg-rose-500/15 dark:text-rose-200 dark:ring-rose-500/30"
              >
                <span>{d}</span>
                {canWeaken && (
                  <button
                    type="button"
                    onClick={() =>
                      void run(() =>
                        onUpdateDns({ dnsBlacklist: dnsBlacklist.filter((x) => x !== d) })
                      )
                    }
                    className="ml-0.5 text-rose-500 hover:text-rose-700"
                  >
                    <Trash2 className="h-3 w-3" />
                  </button>
                )}
              </span>
            ))}
          </div>
        )}

        {/* Blocked Domain Keywords */}
        <div className="flex gap-2">
          <input
            type="text"
            value={newKeyword}
            onChange={(e) => setNewKeyword(e.target.value)}
            placeholder="Bloquer tout domaine contenant (ex: casino, betting)"
            className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 placeholder-slate-400 focus:border-sky-400 focus:outline-none dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
          />
          <button
            type="button"
            disabled={busy || newKeyword.trim().length < 3}
            onClick={() => addDomain('kw')}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-amber-500 px-3 py-2 text-xs font-bold text-white kl-solid-white hover:bg-amber-600 disabled:opacity-40"
          >
            <Plus className="h-3.5 w-3.5" />
            <span>Mot-clé</span>
          </button>
        </div>

        {dnsBlockedKeywords.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {dnsBlockedKeywords.map((kw) => (
              <span
                key={kw}
                className="inline-flex items-center gap-1 rounded-lg bg-amber-50 px-2 py-1 text-[11px] font-semibold text-amber-800 ring-1 ring-amber-200 dark:bg-amber-500/15 dark:text-amber-200 dark:ring-amber-500/30"
              >
                <span>*{kw}*</span>
                {canWeaken && (
                  <button
                    type="button"
                    onClick={() =>
                      void run(() =>
                        onUpdateDns({
                          dnsBlockedKeywords: dnsBlockedKeywords.filter((x) => x !== kw),
                        })
                      )
                    }
                  >
                    <Trash2 className="h-3 w-3" />
                  </button>
                )}
              </span>
            ))}
          </div>
        )}

        {/* Whitelist (only editable when canWeaken!) */}
        <div className="flex gap-2">
          <input
            type="text"
            disabled={!canWeaken}
            value={newWhitelist}
            onChange={(e) => setNewWhitelist(e.target.value)}
            placeholder={
              canWeaken
                ? 'Exception liste blanche (ex: wikipedia.org)'
                : 'Liste blanche verrouillée hors zone carte'
            }
            className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 placeholder-slate-400 disabled:opacity-50 dark:border-white/10 dark:bg-white/[0.05] dark:text-white"
          />
          <button
            type="button"
            disabled={busy || !canWeaken || newWhitelist.trim().length < 3}
            onClick={() => addDomain('white')}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-emerald-500 px-3 py-2 text-xs font-bold text-white kl-solid-white hover:bg-emerald-600 disabled:opacity-40"
          >
            <Plus className="h-3.5 w-3.5" />
            <span>Autoriser</span>
          </button>
        </div>

        {dnsWhitelist.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {dnsWhitelist.map((d) => (
              <span
                key={d}
                className="inline-flex items-center gap-1 rounded-lg bg-emerald-50 px-2 py-1 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-200 dark:bg-emerald-500/15 dark:text-emerald-200 dark:ring-emerald-500/30"
              >
                <span>{d}</span>
                <button
                  type="button"
                  onClick={() =>
                    void run(() =>
                      onUpdateDns({ dnsWhitelist: dnsWhitelist.filter((x) => x !== d) })
                    )
                  }
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </span>
            ))}
          </div>
        )}

        {/* Live Blocked DNS Log */}
        {recentDnsLogs.length > 0 && (
          <div className="pt-1">
            <button
              type="button"
              onClick={() => setShowLogs((v) => !v)}
              className="text-xs font-semibold text-sky-600 hover:underline dark:text-sky-400"
            >
              {showLogs
                ? 'Masquer le journal DNS'
                : `Voir les ${recentDnsLogs.length} derniers blocages DNS`}
            </button>
            {showLogs && (
              <div className="mt-2 max-h-48 space-y-1 overflow-y-auto rounded-xl bg-slate-50 p-2 dark:bg-white/[0.03]">
                {recentDnsLogs.map((log, i) => (
                  <div
                    key={`${log.timestamp}-${log.domain}-${i}`}
                    className="flex items-center justify-between gap-2 rounded-lg bg-white px-2.5 py-1.5 text-[11px] dark:bg-slate-900/70"
                  >
                    <span className="truncate font-mono text-slate-800 dark:text-slate-200">
                      {log.domain}
                    </span>
                    <div className="flex shrink-0 items-center gap-2">
                      <span className="rounded bg-rose-100 px-1.5 py-0.5 text-[10px] font-bold text-rose-700 dark:bg-rose-500/20 dark:text-rose-200">
                        {log.category}
                      </span>
                      <span className="text-[10px] text-slate-400">
                        {new Date(log.timestamp).toLocaleTimeString('fr-FR', {
                          hour: '2-digit',
                          minute: '2-digit',
                        })}
                      </span>
                    </div>
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
