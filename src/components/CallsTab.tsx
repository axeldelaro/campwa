import React, { useState } from 'react';
import {
  Ban,
  Check,
  Phone,
  PhoneIncoming,
  PhoneMissed,
  PhoneOff,
  PhoneOutgoing,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import type { DataCallLogMsg } from '../types/sentinel';

interface CallsTabProps {
  data: DataCallLogMsg | null;
  loading: boolean;
  onRefresh: () => Promise<void>;
  onAddBlock: (number: string, label?: string) => Promise<void>;
  onRemoveBlock: (number: string) => Promise<void>;
  onAddAllow: (number: string, label?: string) => Promise<void>;
  onRemoveAllow: (number: string) => Promise<void>;
  onSetFlags: (opts: { blockUnknownCalls?: boolean; callWhitelistOnly?: boolean }) => Promise<void>;
}

function formatCallDuration(sec: number): string {
  if (!sec || sec <= 0) return '0 s';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m === 0) return `${s} s`;
  return `${m} min ${s.toString().padStart(2, '0')} s`;
}

export const CallsTab: React.FC<CallsTabProps> = ({
  data,
  loading,
  onRefresh,
  onAddBlock,
  onRemoveBlock,
  onAddAllow,
  onRemoveAllow,
  onSetFlags,
}) => {
  const [subView, setSubView] = useState<'log' | 'rules'>('log');
  const [search, setSearch] = useState('');
  const [newNum, setNewNum] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [allowNum, setAllowNum] = useState('');
  const [allowLabel, setAllowLabel] = useState('');

  const calls = data?.calls ?? [];
  const blockedNumbers = data?.blockedNumbers ?? [];
  const allowedNumbers = data?.allowedNumbers ?? [];

  const filteredCalls = calls.filter((c) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return c.number.toLowerCase().includes(q) || c.contactName.toLowerCase().includes(q);
  });

  const handleAddBlockSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newNum.trim()) return;
    await onAddBlock(newNum.trim(), newLabel.trim());
    setNewNum('');
    setNewLabel('');
  };

  const handleAddAllowSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allowNum.trim()) return;
    await onAddAllow(allowNum.trim(), allowLabel.trim());
    setAllowNum('');
    setAllowLabel('');
  };

  return (
    <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1 rounded-xl border border-white/[0.07] bg-zinc-950 p-1">
          <button
            type="button"
            onClick={() => setSubView('log')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition ${
              subView === 'log' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Phone className="h-3.5 w-3.5" />
            <span>Journal</span>
            {calls.length > 0 && <span className="opacity-65">({calls.length})</span>}
          </button>
          <button
            type="button"
            onClick={() => setSubView('rules')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition ${
              subView === 'rules' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Ban className="h-3.5 w-3.5" />
            <span>Numéros bloqués</span>
            {blockedNumbers.length > 0 && <span className="opacity-65">({blockedNumbers.length})</span>}
          </button>
        </div>

        <button
          type="button"
          disabled={loading}
          onClick={() => void onRefresh()}
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-300 hover:text-white disabled:opacity-40"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Actualiser</span>
        </button>
      </div>

      {data && !data.hasCallScreeningRole && (
        <div className="flex items-center gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-200">
          <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-amber-400" />
          <span>
            Activez « Filtre d’appels » une fois dans l’appli SentinelCam sur le téléphone pour
            rejeter automatiquement les numéros bloqués.
          </span>
        </div>
      )}

      {data?.error && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">
          {data.error}
        </div>
      )}

      {subView === 'log' ? (
        <div className="space-y-2.5">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Rechercher un nom ou un numéro…"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-9 pr-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
            />
          </div>

          {loading && calls.length === 0 ? (
            <div className="flex items-center justify-center py-10 text-xs text-zinc-400">
              <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
              Chargement du journal d’appels…
            </div>
          ) : filteredCalls.length === 0 ? (
            <div className="py-10 text-center text-xs text-zinc-500">Aucun appel enregistré.</div>
          ) : (
            <div className="max-h-[54vh] divide-y divide-white/[0.05] overflow-y-auto rounded-xl border border-white/[0.06] bg-zinc-950/60">
              {filteredCalls.map((c) => {
                const isMissed = c.type === 'missed' || c.type === 'rejected' || c.type === 'blocked';
                const canBlock = c.number && c.number !== 'Numéro masqué';
                return (
                  <div key={c.id} className="flex items-center justify-between gap-2 px-3.5 py-2.5">
                    <div className="flex min-w-0 items-center gap-2.5">
                      <div
                        className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
                          c.type === 'blocked' || c.blocked
                            ? 'bg-rose-500/15 text-rose-400'
                            : isMissed
                            ? 'bg-amber-500/15 text-amber-300'
                            : c.type === 'outgoing'
                            ? 'bg-sky-500/15 text-sky-300'
                            : 'bg-emerald-500/15 text-emerald-300'
                        }`}
                      >
                        {c.type === 'outgoing' ? (
                          <PhoneOutgoing className="h-3.5 w-3.5" />
                        ) : c.type === 'missed' ? (
                          <PhoneMissed className="h-3.5 w-3.5" />
                        ) : c.type === 'rejected' || c.type === 'blocked' ? (
                          <PhoneOff className="h-3.5 w-3.5" />
                        ) : (
                          <PhoneIncoming className="h-3.5 w-3.5" />
                        )}
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className="truncate text-xs font-medium text-white">
                            {c.contactName || c.number}
                          </span>
                          {c.blocked && (
                            <span className="rounded bg-rose-500/20 px-1.5 py-0.5 text-[10px] font-medium text-rose-300">
                              Bloqué
                            </span>
                          )}
                        </div>
                        <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-zinc-500">
                          {c.contactName && <span className="font-mono">{c.number}</span>}
                          <span>
                            {new Date(c.date).toLocaleString('fr-FR', {
                              day: '2-digit',
                              month: '2-digit',
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </span>
                          <span>·</span>
                          <span>{formatCallDuration(c.durationSec)}</span>
                        </div>
                      </div>
                    </div>

                    {canBlock && (
                      <button
                        type="button"
                        onClick={() =>
                          c.blocked
                            ? void onRemoveBlock(c.number)
                            : void onAddBlock(c.number, c.contactName)
                        }
                        className={`shrink-0 rounded-lg border px-2.5 py-1 text-[11px] font-medium transition ${
                          c.blocked
                            ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200 hover:bg-emerald-500/20'
                            : 'border-rose-500/35 bg-rose-500/10 text-rose-200 hover:bg-rose-500/20'
                        }`}
                      >
                        {c.blocked ? 'Débloquer' : 'Bloquer'}
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-4">
          {/* Quick Global Call Filter Toggles */}
          <div className="grid gap-2 sm:grid-cols-2">
            <button
              type="button"
              onClick={() =>
                void onSetFlags({ blockUnknownCalls: !(data?.blockUnknownCalls ?? false) })
              }
              className={`flex items-center justify-between gap-2 rounded-xl border px-3.5 py-2.5 text-left text-xs font-medium transition ${
                data?.blockUnknownCalls
                  ? 'border-rose-500/40 bg-rose-500/10 text-rose-200'
                  : 'border-white/[0.08] bg-zinc-950 text-zinc-300'
              }`}
            >
              <span>Bloquer les numéros masqués</span>
              <span className="text-[10px] font-semibold">
                {data?.blockUnknownCalls ? 'ON' : 'OFF'}
              </span>
            </button>

            <button
              type="button"
              onClick={() =>
                void onSetFlags({ callWhitelistOnly: !(data?.callWhitelistOnly ?? false) })
              }
              className={`flex items-center justify-between gap-2 rounded-xl border px-3.5 py-2.5 text-left text-xs font-medium transition ${
                data?.callWhitelistOnly
                  ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
                  : 'border-white/[0.08] bg-zinc-950 text-zinc-300'
              }`}
            >
              <span>Uniquement contacts & liste blanche</span>
              <span className="text-[10px] font-semibold">
                {data?.callWhitelistOnly ? 'ON' : 'OFF'}
              </span>
            </button>
          </div>

          {/* Add Blocked Number Form */}
          <form onSubmit={(e) => void handleAddBlockSubmit(e)} className="space-y-2 rounded-xl border border-white/[0.06] bg-zinc-950/70 p-3">
            <div className="text-xs font-medium text-white">Bloquer un numéro</div>
            <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
              <input
                type="tel"
                required
                value={newNum}
                onChange={(e) => setNewNum(e.target.value)}
                placeholder="Numéro (ex. +33 6 12 34 56 78)"
                className="rounded-xl border border-white/10 bg-zinc-900 px-3 py-2 font-mono text-xs text-white placeholder-zinc-500"
              />
              <input
                type="text"
                value={newLabel}
                onChange={(e) => setNewLabel(e.target.value)}
                placeholder="Nom / motif (optionnel)"
                className="rounded-xl border border-white/10 bg-zinc-900 px-3 py-2 text-xs text-white placeholder-zinc-500"
              />
              <button
                type="submit"
                className="inline-flex items-center justify-center gap-1 rounded-xl bg-rose-500 px-3.5 py-2 text-xs font-semibold text-white hover:bg-rose-400"
              >
                <Ban className="h-3.5 w-3.5" />
                <span>Bloquer</span>
              </button>
            </div>

            {blockedNumbers.length > 0 && (
              <div className="divide-y divide-white/[0.05] pt-2">
                {blockedNumbers.map((b) => (
                  <div key={b.number} className="flex items-center justify-between gap-2 py-2 text-xs">
                    <div className="min-w-0">
                      <div className="font-mono font-medium text-rose-200">{b.number}</div>
                      {b.label && <div className="text-[11px] text-zinc-400">{b.label}</div>}
                    </div>
                    <button
                      type="button"
                      onClick={() => void onRemoveBlock(b.number)}
                      className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-400 hover:text-white"
                      title="Débloquer"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </form>

          {/* Optional Whitelist Section */}
          {data?.callWhitelistOnly && (
            <form onSubmit={(e) => void handleAddAllowSubmit(e)} className="space-y-2 rounded-xl border border-emerald-500/25 bg-emerald-500/[0.05] p-3">
              <div className="flex items-center gap-1.5 text-xs font-medium text-emerald-200">
                <ShieldCheck className="h-3.5 w-3.5" />
                <span>Numéros toujours autorisés (en plus du répertoire)</span>
              </div>
              <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                <input
                  type="tel"
                  required
                  value={allowNum}
                  onChange={(e) => setAllowNum(e.target.value)}
                  placeholder="Numéro autorisé"
                  className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs text-white placeholder-zinc-500"
                />
                <input
                  type="text"
                  value={allowLabel}
                  onChange={(e) => setAllowLabel(e.target.value)}
                  placeholder="Nom (ex. Maman, Mamie)"
                  className="rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
                />
                <button
                  type="submit"
                  className="inline-flex items-center justify-center gap-1 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950"
                >
                  <Plus className="h-3.5 w-3.5" />
                  <span>Ajouter</span>
                </button>
              </div>
              {allowedNumbers.length > 0 && (
                <div className="divide-y divide-white/[0.06] pt-1">
                  {allowedNumbers.map((a) => (
                    <div key={a.number} className="flex items-center justify-between gap-2 py-2 text-xs">
                      <div className="flex items-center gap-2">
                        <Check className="h-3.5 w-3.5 text-emerald-400" />
                        <span className="font-mono text-white">{a.number}</span>
                        {a.label && <span className="text-zinc-400">· {a.label}</span>}
                      </div>
                      <button
                        type="button"
                        onClick={() => void onRemoveAllow(a.number)}
                        className="rounded-lg border border-white/10 bg-zinc-950 p-1.5 text-zinc-400 hover:text-white"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </form>
          )}
        </div>
      )}
    </div>
  );
};
