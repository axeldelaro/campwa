import React, { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Camera,
  Check,
  Clock,
  Download,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
  X,
} from 'lucide-react';
import type {
  AppUsageEntry,
  DataTimelineMsg,
  TimelineCaptureItem,
} from '../types/sentinel';

interface TimelineTabProps {
  data: DataTimelineMsg | null;
  availableApps: AppUsageEntry[];
  loading: boolean;
  lightbox: {
    item: TimelineCaptureItem;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null;
  onRefresh: (page?: number) => Promise<void>;
  onConfigure: (opts: {
    enabled: boolean;
    intervalSec: number;
    monitoredPackages: string[];
  }) => Promise<void>;
  onOpenCapture: (item: TimelineCaptureItem) => Promise<void>;
  onDeleteCapture: (captureId: string) => Promise<void>;
  onClearAll: () => Promise<void>;
  onCloseLightbox: () => void;
}

const INTERVAL_OPTIONS = [5, 10, 15, 30, 60];

function formatCaptureTime(ts: number): string {
  const d = new Date(ts);
  return (
    d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) +
    ' • ' +
    d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  );
}

export const TimelineTab: React.FC<TimelineTabProps> = ({
  data,
  availableApps,
  loading,
  lightbox,
  onRefresh,
  onConfigure,
  onOpenCapture,
  onDeleteCapture,
  onClearAll,
  onCloseLightbox,
}) => {
  const [showSettings, setShowSettings] = useState(false);
  const [enabledDraft, setEnabledDraft] = useState(false);
  const [intervalDraft, setIntervalDraft] = useState(15);
  const [selectedPkgs, setSelectedPkgs] = useState<string[]>([]);
  const [appSearch, setAppSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [confirmClear, setConfirmClear] = useState(false);

  useEffect(() => {
    if (!data) return;
    setEnabledDraft(data.enabled);
    setIntervalDraft(data.intervalSec || 15);
    setSelectedPkgs(data.monitoredPackages ?? []);
  }, [data?.enabled, data?.intervalSec, JSON.stringify(data?.monitoredPackages ?? [])]);

  const items = data?.items ?? [];

  const filteredApps = useMemo(() => {
    const q = appSearch.trim().toLowerCase();
    const list = [...availableApps].sort((a, b) => {
      const aSel = selectedPkgs.includes(a.packageName) ? 1 : 0;
      const bSel = selectedPkgs.includes(b.packageName) ? 1 : 0;
      if (aSel !== bSel) return bSel - aSel;
      return b.usageSecondsToday - a.usageSecondsToday || a.appName.localeCompare(b.appName);
    });
    if (!q) return list;
    return list.filter(
      (a) => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
    );
  }, [availableApps, appSearch, selectedPkgs]);

  const togglePkg = (pkg: string) => {
    setSelectedPkgs((prev) =>
      prev.includes(pkg) ? prev.filter((p) => p !== pkg) : [...prev, pkg]
    );
  };

  const handleSaveConfig = async (nextEnabled = enabledDraft, nextInterval = intervalDraft, nextPkgs = selectedPkgs) => {
    setSaving(true);
    try {
      await onConfigure({
        enabled: nextEnabled,
        intervalSec: nextInterval,
        monitoredPackages: nextPkgs,
      });
    } finally {
      setSaving(false);
    }
  };

  const handleDownload = (item: TimelineCaptureItem, base64: string) => {
    const a = document.createElement('a');
    a.href = `data:image/jpeg;base64,${base64}`;
    a.download = `capture_${item.appName.replace(/[^a-z0-9]/gi, '_')}_${item.timestamp}.jpg`;
    a.click();
  };

  return (
    <div className="space-y-3.5 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
            <Camera className="h-4 w-4 text-sky-400" />
            Captures automatiques par application
          </h3>
          <p className="text-[11px] text-zinc-400">
            Capture automatiquement l’écran toutes les {data?.intervalSec ?? intervalDraft} s uniquement sur les applications choisies.
          </p>
        </div>

        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setShowSettings((v) => !v)}
            className={`inline-flex items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-xs font-medium transition ${
              showSettings
                ? 'border-sky-400/40 bg-sky-500/15 text-sky-200'
                : 'border-white/10 bg-zinc-950 text-zinc-200 hover:border-white/20'
            }`}
          >
            <SlidersHorizontal className="h-3.5 w-3.5" />
            Réglages ({selectedPkgs.length} app{selectedPkgs.length > 1 ? 's' : ''})
          </button>

          <button
            type="button"
            onClick={() => void onRefresh(0)}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-1.5 text-xs font-medium text-zinc-200 hover:border-white/20 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            Actualiser
          </button>
        </div>
      </div>

      {/* Status & Quick Toggle */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-white/[0.06] bg-zinc-950/80 px-3.5 py-2.5">
        <div className="flex items-center gap-2.5">
          <span
            className={`h-2.5 w-2.5 rounded-full ${
              data?.running
                ? 'bg-emerald-400 animate-pulse'
                : data?.enabled
                  ? 'bg-amber-400'
                  : 'bg-zinc-600'
            }`}
          />
          <div>
            <p className="text-xs font-medium text-white">
              {data?.running
                ? `Surveillance active • toutes les ${data.intervalSec} s`
                : data?.enabled
                  ? 'Activé (en attente d’autorisation ou d’applications)'
                  : 'Captures automatiques en pause'}
            </p>
            <p className="text-[10px] text-zinc-500">
              {selectedPkgs.length} application(s) surveillée(s) • {data?.totalCount ?? 0} capture(s) en mémoire
            </p>
          </div>
        </div>

        <button
          type="button"
          disabled={saving}
          onClick={() => {
            const next = !enabledDraft;
            setEnabledDraft(next);
            if (next && selectedPkgs.length === 0) {
              setShowSettings(true);
            }
            void handleSaveConfig(next, intervalDraft, selectedPkgs);
          }}
          className={`rounded-xl px-3.5 py-1.5 text-xs font-semibold transition disabled:opacity-50 ${
            enabledDraft
              ? 'bg-emerald-500 text-zinc-950 hover:bg-emerald-400'
              : 'border border-white/15 bg-zinc-900 text-zinc-200 hover:bg-zinc-800'
          }`}
        >
          {enabledDraft ? 'Activé' : 'Activer'}
        </button>
      </div>

      {/* Consent Notice */}
      {data?.needsConsent && selectedPkgs.length > 0 && (
        <div className="flex items-start gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
          <div>
            <p className="font-medium text-amber-100">Autorisation capture écran requise une fois</p>
            <p className="mt-0.5 text-[11px] text-amber-200/80">
              Ouvrez l’application sur le smartphone de l’enfant et appuyez une fois sur « Autoriser capture écran » pour démarrer les captures automatiques.
            </p>
          </div>
        </div>
      )}

      {/* Configuration Drawer */}
      {(showSettings || (enabledDraft && selectedPkgs.length === 0)) && (
        <div className="space-y-3 rounded-xl border border-sky-500/20 bg-zinc-950/90 p-3.5">
          {/* Interval Selector */}
          <div className="space-y-1.5">
            <label className="flex items-center gap-1.5 text-xs font-medium text-zinc-200">
              <Clock className="h-3.5 w-3.5 text-sky-400" />
              Fréquence de capture (si l’écran change)
            </label>
            <div className="flex flex-wrap gap-1.5">
              {INTERVAL_OPTIONS.map((sec) => (
                <button
                  key={sec}
                  type="button"
                  onClick={() => setIntervalDraft(sec)}
                  className={`rounded-lg border px-3 py-1 text-xs font-medium transition ${
                    intervalDraft === sec
                      ? 'border-sky-400 bg-sky-500/20 text-sky-200'
                      : 'border-white/10 bg-zinc-900 text-zinc-400 hover:text-white'
                  }`}
                >
                  Toutes les {sec} s
                </button>
              ))}
            </div>
          </div>

          {/* Monitored Apps Multi-Select */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium text-zinc-200">
                Applications à surveiller ({selectedPkgs.length} sélectionnée{selectedPkgs.length > 1 ? 's' : ''})
              </label>
              {selectedPkgs.length > 0 && (
                <button
                  type="button"
                  onClick={() => setSelectedPkgs([])}
                  className="text-[11px] text-zinc-400 hover:text-white"
                >
                  Tout décocher
                </button>
              )}
            </div>

            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
              <input
                type="text"
                value={appSearch}
                onChange={(e) => setAppSearch(e.target.value)}
                placeholder="Rechercher TikTok, Snapchat, Instagram, Roblox..."
                className="w-full rounded-lg border border-white/10 bg-zinc-900 py-1.5 pl-8 pr-3 text-xs text-white placeholder-zinc-500 outline-none focus:border-sky-400/50"
              />
            </div>

            {availableApps.length === 0 ? (
              <p className="py-2 text-[11px] text-zinc-500">
                Liste des applications en cours de chargement... Ouvrez l’onglet Apps ou actualisez l’Accueil.
              </p>
            ) : (
              <div className="max-h-52 grid grid-cols-1 gap-1.5 overflow-y-auto pr-1 sm:grid-cols-2">
                {filteredApps.map((app) => {
                  const checked = selectedPkgs.includes(app.packageName);
                  return (
                    <button
                      key={app.packageName}
                      type="button"
                      onClick={() => togglePkg(app.packageName)}
                      className={`flex items-center justify-between gap-2 rounded-lg border px-2.5 py-2 text-left text-xs transition ${
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
            )}
          </div>

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={() => setShowSettings(false)}
              className="rounded-xl border border-white/10 px-3 py-1.5 text-xs text-zinc-300 hover:text-white"
            >
              Fermer
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={async () => {
                await handleSaveConfig(enabledDraft, intervalDraft, selectedPkgs);
                setShowSettings(false);
              }}
              className="rounded-xl bg-sky-500 px-4 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-sky-400 disabled:opacity-50"
            >
              {saving ? 'Enregistrement...' : 'Appliquer les réglages'}
            </button>
          </div>
        </div>
      )}

      {/* Captures Grid */}
      {items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-white/10 bg-zinc-950/50 px-4 py-8 text-center">
          <Camera className="mx-auto mb-2 h-6 w-6 text-zinc-600" />
          <p className="text-xs font-medium text-zinc-300">Aucune capture enregistrée</p>
          <p className="mt-1 text-[11px] text-zinc-500">
            Choisissez les applications à surveiller et activez la capture : chaque nouvel écran apparaîtra ici.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 md:grid-cols-4">
            {items.map((item) => (
              <div
                key={item.id}
                className="group relative overflow-hidden rounded-xl border border-white/[0.08] bg-zinc-950"
              >
                <button
                  type="button"
                  onClick={() => void onOpenCapture(item)}
                  className="block w-full text-left"
                >
                  <div className="aspect-[9/16] w-full overflow-hidden bg-black">
                    <img
                      src={`data:image/jpeg;base64,${item.thumbBase64}`}
                      alt={item.appName}
                      className="h-full w-full object-cover transition duration-300 group-hover:scale-105"
                      loading="lazy"
                    />
                  </div>
                  <div className="p-2">
                    <p className="truncate text-[11px] font-semibold text-white">{item.appName}</p>
                    <p className="text-[10px] text-zinc-500">{formatCaptureTime(item.timestamp)}</p>
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => void onDeleteCapture(item.id)}
                  className="absolute right-1.5 top-1.5 rounded-lg bg-black/70 p-1 text-zinc-300 opacity-85 transition hover:bg-rose-600 hover:text-white"
                  title="Supprimer cette capture"
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </div>
            ))}
          </div>

          {/* Pagination & Clear */}
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-white/[0.06] pt-2.5">
            <div className="flex items-center gap-2">
              {data && data.page > 0 && (
                <button
                  type="button"
                  onClick={() => void onRefresh(data.page - 1)}
                  className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200 hover:border-white/25"
                >
                  Précédent
                </button>
              )}
              {data?.hasMore && (
                <button
                  type="button"
                  onClick={() => void onRefresh((data.page ?? 0) + 1)}
                  className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200 hover:border-white/25"
                >
                  Suivant
                </button>
              )}
            </div>

            {confirmClear ? (
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={async () => {
                    await onClearAll();
                    setConfirmClear(false);
                  }}
                  className="rounded-lg bg-rose-500 px-2.5 py-1 text-xs font-semibold text-white hover:bg-rose-400"
                >
                  Tout supprimer
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmClear(false)}
                  className="rounded-lg border border-white/10 px-2.5 py-1 text-xs text-zinc-300"
                >
                  Annuler
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmClear(true)}
                className="inline-flex items-center gap-1 text-xs text-zinc-400 hover:text-rose-300"
              >
                <Trash2 className="h-3.5 w-3.5" />
                Tout effacer
              </button>
            )}
          </div>
        </div>
      )}

      {/* Fullscreen Lightbox */}
      {lightbox && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/90 p-4 backdrop-blur-sm"
          onClick={onCloseLightbox}
        >
          <div
            className="flex max-h-[92vh] w-full max-w-md flex-col overflow-hidden rounded-2xl border border-white/15 bg-zinc-950"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-white/10 px-4 py-3">
              <div>
                <p className="text-sm font-semibold text-white">{lightbox.item.appName}</p>
                <p className="text-[11px] text-zinc-400">
                  {formatCaptureTime(lightbox.item.timestamp)}
                </p>
              </div>
              <button
                type="button"
                onClick={onCloseLightbox}
                className="rounded-lg border border-white/10 p-1.5 text-zinc-400 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex flex-1 items-center justify-center overflow-auto bg-black p-2">
              {lightbox.loading ? (
                <p className="py-12 text-xs text-zinc-400">Chargement de la capture...</p>
              ) : lightbox.error ? (
                <p className="py-12 text-xs text-rose-400">{lightbox.error}</p>
              ) : lightbox.base64 ? (
                <img
                  src={`data:image/jpeg;base64,${lightbox.base64}`}
                  alt={lightbox.item.appName}
                  className="max-h-[70vh] w-auto rounded-lg object-contain"
                />
              ) : null}
            </div>

            <div className="flex items-center justify-between gap-2 border-t border-white/10 px-4 py-3">
              <button
                type="button"
                disabled={!lightbox.base64}
                onClick={() =>
                  lightbox.base64 && handleDownload(lightbox.item, lightbox.base64)
                }
                className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-40"
              >
                <Download className="h-3.5 w-3.5" />
                Enregistrer
              </button>
              <button
                type="button"
                onClick={async () => {
                  await onDeleteCapture(lightbox.item.id);
                  onCloseLightbox();
                }}
                className="inline-flex items-center gap-1.5 rounded-xl border border-rose-500/30 bg-rose-500/15 px-3.5 py-2 text-xs font-medium text-rose-300 hover:bg-rose-500/25"
              >
                <Trash2 className="h-3.5 w-3.5" />
                Supprimer
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
