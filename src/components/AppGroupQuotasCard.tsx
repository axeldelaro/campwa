import React, { useMemo, useState } from 'react';
import {
  Check,
  Edit3,
  Layers,
  Plus,
  Search,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react';
import type { AppGroupQuotaItem, AppUsageEntry } from '../types/sentinel';

interface AppGroupQuotasCardProps {
  groups: AppGroupQuotaItem[];
  availableApps: AppUsageEntry[];
  onSaveGroup: (opts: {
    id?: string;
    name?: string;
    packages?: string[];
    dailyLimitMinutes?: number;
    delete?: boolean;
  }) => Promise<void>;
}

const SOCIAL_KEYWORDS = [
  'tiktok',
  'musically',
  'instagram',
  'snapchat',
  'twitter',
  'facebook',
  'threads',
  'bereal',
  'discord',
  'reddit',
  'pinterest',
];

const MEDIA_GAMES_KEYWORDS = [
  'youtube',
  'twitch',
  'netflix',
  'disney',
  'roblox',
  'minecraft',
  'supercell',
  'brawlstars',
  'clash',
  'fortnite',
  'epicgames',
  'garena',
  'pubg',
  'stumble',
];

function formatMinutesProgress(usedSec: number, limitMin: number, bonusMin: number): string {
  const usedMin = Math.floor(Math.max(0, usedSec) / 60);
  const totalLimit = limitMin + Math.max(0, bonusMin);
  return `${usedMin} / ${totalLimit} min/j`;
}

export const AppGroupQuotasCard: React.FC<AppGroupQuotasCardProps> = ({
  groups,
  availableApps,
  onSaveGroup,
}) => {
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | undefined>(undefined);
  const [nameDraft, setNameDraft] = useState('');
  const [limitDraft, setLimitDraft] = useState(60);
  const [selectedPkgs, setSelectedPkgs] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const [saving, setSaving] = useState(false);

  const safeGroups = Array.isArray(groups) ? groups : [];
  const safeApps = Array.isArray(availableApps) ? availableApps : [];

  const appNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of safeApps) {
      if (a?.packageName) map.set(a.packageName, a.appName || a.packageName);
    }
    return map;
  }, [safeApps]);

  const filteredApps = useMemo(() => {
    const q = search.trim().toLowerCase();
    const sorted = [...safeApps].sort((a, b) => {
      const aSel = selectedPkgs.includes(a.packageName) ? 1 : 0;
      const bSel = selectedPkgs.includes(b.packageName) ? 1 : 0;
      if (aSel !== bSel) return bSel - aSel;
      return (b.usageSecondsToday || 0) - (a.usageSecondsToday || 0) || (a.appName || '').localeCompare(b.appName || '');
    });
    if (!q) return sorted;
    return sorted.filter(
      (a) => (a.appName || '').toLowerCase().includes(q) || (a.packageName || '').toLowerCase().includes(q)
    );
  }, [safeApps, search, selectedPkgs]);

  const openNewEditor = () => {
    setEditingId(undefined);
    setNameDraft('');
    setLimitDraft(60);
    setSelectedPkgs([]);
    setSearch('');
    setEditorOpen(true);
  };

  const openEditGroup = (group: AppGroupQuotaItem) => {
    setEditingId(group.id);
    setNameDraft(group.name);
    setLimitDraft(group.dailyLimitMinutes);
    setSelectedPkgs([...group.packages]);
    setSearch('');
    setEditorOpen(true);
  };

  const applyPreset = (presetName: string, limitMin: number, keywords: string[]) => {
    const matched = availableApps
      .filter((a) => {
        const hay = `${a.appName} ${a.packageName}`.toLowerCase();
        return keywords.some((kw) => hay.includes(kw));
      })
      .map((a) => a.packageName);
    setEditingId(undefined);
    setNameDraft(presetName);
    setLimitDraft(limitMin);
    setSelectedPkgs(matched);
    setSearch('');
    setEditorOpen(true);
  };

  const togglePkg = (pkg: string) => {
    setSelectedPkgs((prev) =>
      prev.includes(pkg) ? prev.filter((p) => p !== pkg) : [...prev, pkg]
    );
  };

  const handleSave = async () => {
    if (selectedPkgs.length === 0 || limitDraft <= 0) return;
    setSaving(true);
    try {
      await onSaveGroup({
        id: editingId,
        name: nameDraft.trim() || 'Groupe d’apps',
        packages: selectedPkgs,
        dailyLimitMinutes: Math.max(5, Math.min(720, Math.round(limitDraft))),
      });
      setEditorOpen(false);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    setSaving(true);
    try {
      await onSaveGroup({ id, delete: true });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-3 rounded-2xl border border-white/[0.08] bg-zinc-900/65 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-xl bg-sky-500/15 text-sky-400 ring-1 ring-sky-500/25">
            <Layers className="h-3.5 w-3.5" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-white">Quotas par groupe d’apps</h2>
            <p className="text-[11px] text-zinc-500">
              Temps cumulé partagé (ex. Réseaux sociaux = 60 min/j · Récurrent chaque jour)
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={() => (editorOpen ? setEditorOpen(false) : openNewEditor())}
          className="inline-flex items-center gap-1 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
        >
          {editorOpen ? (
            <>
              <X className="h-3.5 w-3.5" />
              Fermer
            </>
          ) : (
            <>
              <Plus className="h-3.5 w-3.5" />
              Créer un groupe
            </>
          )}
        </button>
      </div>

      {/* Quick Presets */}
      {!editorOpen && safeGroups.length === 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-dashed border-white/10 bg-zinc-950/60 p-3">
          <span className="inline-flex items-center gap-1 text-[11px] text-zinc-400">
            <Sparkles className="h-3 w-3 text-sky-400" />
            Modèles rapides :
          </span>
          <button
            type="button"
            onClick={() => applyPreset('Réseaux sociaux', 60, SOCIAL_KEYWORDS)}
            className="rounded-lg border border-sky-500/30 bg-sky-500/10 px-2.5 py-1 text-[11px] font-medium text-sky-200 hover:bg-sky-500/20"
          >
            + Réseaux sociaux (1h/j)
          </button>
          <button
            type="button"
            onClick={() => applyPreset('Jeux & Vidéos', 90, MEDIA_GAMES_KEYWORDS)}
            className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-1 text-[11px] font-medium text-emerald-200 hover:bg-emerald-500/20"
          >
            + Jeux & Vidéos (1h30/j)
          </button>
        </div>
      )}

      {/* Active Groups List */}
      {safeGroups.length > 0 && (
        <div className="space-y-2">
          {safeGroups.map((g) => {
            const totalLimitSec = (g.dailyLimitMinutes + (g.bonusMinutesToday || 0)) * 60;
            const pct = Math.min(100, Math.round((g.usedSecondsToday / Math.max(60, totalLimitSec)) * 100));
            const memberPkgs = Array.isArray(g.packages) ? g.packages : [];
            return (
              <div
                key={g.id}
                className={`rounded-xl border p-3 transition ${
                  g.quotaExceeded
                    ? 'border-rose-500/35 bg-rose-500/[0.07]'
                    : 'border-white/[0.06] bg-zinc-950/80'
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold text-white">{g.name}</span>
                      <span
                        className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-medium ${
                          g.quotaExceeded
                            ? 'bg-rose-500/20 text-rose-200'
                            : pct >= 80
                              ? 'bg-amber-500/20 text-amber-200'
                              : 'bg-emerald-500/15 text-emerald-300'
                        }`}
                      >
                        {formatMinutesProgress(g.usedSecondsToday, g.dailyLimitMinutes, g.bonusMinutesToday)}
                      </span>
                      <span className="rounded bg-white/[0.05] px-1.5 py-0.5 text-[10px] text-zinc-400">
                        Récurrent
                      </span>
                    </div>

                    {/* Progress Bar */}
                    <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-zinc-800">
                      <div
                        className={`h-full rounded-full transition-all ${
                          g.quotaExceeded
                            ? 'bg-rose-400'
                            : pct >= 80
                              ? 'bg-amber-400'
                              : 'bg-emerald-400'
                        }`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>

                    {/* Member Apps */}
                    <div className="mt-2 flex flex-wrap gap-1">
                      {memberPkgs.map((pkg) => (
                        <span
                          key={pkg}
                          className="rounded-md border border-white/[0.06] bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-300"
                        >
                          {appNameMap.get(pkg) || pkg.split('.').pop()}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="flex shrink-0 items-center gap-1">
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => openEditGroup(g)}
                      className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-300 hover:border-white/25 hover:text-white"
                      title="Modifier"
                    >
                      <Edit3 className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => void handleDelete(g.id)}
                      className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-400 hover:border-rose-500/40 hover:bg-rose-500/15 hover:text-rose-200"
                      title="Supprimer ce groupe"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Group Creator / Editor */}
      {editorOpen && (
        <div className="space-y-3 rounded-xl border border-sky-500/25 bg-zinc-950/95 p-3.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs font-semibold text-white">
              {editingId ? 'Modifier le groupe' : 'Nouveau quota de groupe'}
            </span>
            {!editingId && (
              <div className="flex gap-1.5">
                <button
                  type="button"
                  onClick={() => applyPreset('Réseaux sociaux', 60, SOCIAL_KEYWORDS)}
                  className="rounded-md border border-white/10 bg-zinc-900 px-2 py-0.5 text-[10px] text-sky-300 hover:border-sky-400/40"
                >
                  Préremplir Réseaux
                </button>
                <button
                  type="button"
                  onClick={() => applyPreset('Jeux & Vidéos', 90, MEDIA_GAMES_KEYWORDS)}
                  className="rounded-md border border-white/10 bg-zinc-900 px-2 py-0.5 text-[10px] text-emerald-300 hover:border-emerald-400/40"
                >
                  Préremplir Jeux
                </button>
              </div>
            )}
          </div>

          <div className="grid gap-2.5 sm:grid-cols-[1fr_auto]">
            <input
              type="text"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              maxLength={32}
              placeholder="Nom du groupe (ex. Réseaux sociaux)"
              className="w-full rounded-xl border border-white/10 bg-zinc-900 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-sky-400 focus:outline-none"
            />
            <div className="flex items-center gap-1.5">
              {[30, 60, 90, 120].map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => setLimitDraft(m)}
                  className={`rounded-lg border px-2 py-2 text-[11px] font-medium ${
                    limitDraft === m
                      ? 'border-sky-400 bg-sky-500/20 text-sky-200'
                      : 'border-white/10 bg-zinc-900 text-zinc-400 hover:text-white'
                  }`}
                >
                  {m >= 60 ? `${m / 60}h${m % 60 ? m % 60 : ''}` : `${m}m`}
                </button>
              ))}
              <input
                type="number"
                min={5}
                max={720}
                value={limitDraft}
                onChange={(e) => setLimitDraft(parseInt(e.target.value, 10) || 30)}
                className="w-14 rounded-lg border border-white/10 bg-zinc-900 px-2 py-2 text-center font-mono text-xs text-white"
              />
              <span className="text-[11px] text-zinc-500">min/j</span>
            </div>
          </div>

          {/* Multi-App Picker */}
          <div className="space-y-2">
            <div className="flex items-center justify-between text-[11px]">
              <span className="text-zinc-300">
                Applications du groupe ({selectedPkgs.length} sélectionnée{selectedPkgs.length > 1 ? 's' : ''})
              </span>
              {selectedPkgs.length > 0 && (
                <button
                  type="button"
                  onClick={() => setSelectedPkgs([])}
                  className="text-zinc-400 hover:text-white"
                >
                  Tout effacer
                </button>
              )}
            </div>

            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Rechercher une application…"
                className="w-full rounded-xl border border-white/10 bg-zinc-900 py-1.5 pl-8 pr-3 text-xs text-white placeholder-zinc-500 focus:border-sky-400 focus:outline-none"
              />
            </div>

            <div className="grid max-h-44 grid-cols-1 gap-1.5 overflow-y-auto pr-1 sm:grid-cols-2">
              {filteredApps.map((app) => {
                const checked = selectedPkgs.includes(app.packageName);
                return (
                  <button
                    key={app.packageName}
                    type="button"
                    onClick={() => togglePkg(app.packageName)}
                    className={`flex items-center justify-between gap-2 rounded-lg border px-2.5 py-1.5 text-left text-xs transition ${
                      checked
                        ? 'border-sky-400/50 bg-sky-500/15 text-white'
                        : 'border-white/[0.06] bg-zinc-900/70 text-zinc-300 hover:border-white/15'
                    }`}
                  >
                    <span className="truncate font-medium">{app.appName}</span>
                    <span
                      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                        checked
                          ? 'border-sky-400 bg-sky-400 text-zinc-950'
                          : 'border-white/20 bg-transparent'
                      }`}
                    >
                      {checked && <Check className="h-3 w-3" />}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={() => setEditorOpen(false)}
              className="rounded-xl border border-white/10 px-3 py-1.5 text-xs text-zinc-300 hover:text-white"
            >
              Annuler
            </button>
            <button
              type="button"
              disabled={saving || selectedPkgs.length === 0 || limitDraft <= 0}
              onClick={() => void handleSave()}
              className="rounded-xl bg-sky-500 px-4 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-sky-400 disabled:opacity-40"
            >
              {saving ? 'Enregistrement…' : 'Enregistrer le groupe'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
