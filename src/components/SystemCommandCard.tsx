import React, { useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  Cpu,
  ExternalLink,
  HardDrive,
  RefreshCw,
  Search,
  Sun,
  Trash2,
  Volume2,
  VolumeX,
  Wifi,
} from 'lucide-react';
import type { DataSystemStatusMsg } from '../types/sentinel';

interface SystemCommandCardProps {
  data: DataSystemStatusMsg | null;
  loading: boolean;
  onRefresh: () => Promise<void>;
  onSetVolume: (stream: 'music' | 'ring' | 'alarm', percent: number) => Promise<void>;
  onSetRinger: (mode: 'normal' | 'vibrate' | 'silent') => Promise<void>;
  onSetBrightness: (percent: number) => Promise<void>;
  onLaunchApp: (packageName: string) => Promise<void>;
  onUninstallApp: (packageName: string) => Promise<void>;
}

export const SystemCommandCard: React.FC<SystemCommandCardProps> = ({
  data,
  loading,
  onRefresh,
  onSetVolume,
  onSetRinger,
  onSetBrightness,
  onLaunchApp,
  onUninstallApp,
}) => {
  const [open, setOpen] = useState(false);
  const [appFilter, setAppFilter] = useState('');
  const [onlySideloaded, setOnlySideloaded] = useState(false);

  const handleToggleOpen = () => {
    const next = !open;
    setOpen(next);
    if (next && !data && !loading) {
      void onRefresh();
    }
  };

  const apps = data?.installedApps ?? [];
  const sideloadCount = apps.filter((a) => a.sideloaded).length;
  const filteredApps = apps.filter((a) => {
    if (onlySideloaded && !a.sideloaded) return false;
    const q = appFilter.trim().toLowerCase();
    if (!q) return true;
    return a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q);
  });

  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
      <button
        type="button"
        onClick={handleToggleOpen}
        className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
      >
        <div className="flex min-w-0 items-center gap-2.5">
          <Cpu className="h-4 w-4 shrink-0 text-zinc-400" />
          <div className="min-w-0">
            <div className="text-sm font-medium text-white">Centre de commande & Système</div>
            <div className="truncate text-[11px] text-zinc-500">
              {data
                ? `${data.wifiSummary} · ${(data.freeStorageMb / 1024).toFixed(1)} Go libres${
                    sideloadCount > 0 ? ` · ${sideloadCount} appli(s) hors Store` : ''
                  }`
                : 'Volumes, sonnerie, luminosité et inventaire complet des applis'}
            </div>
          </div>
        </div>
        {open ? (
          <ChevronUp className="h-4 w-4 shrink-0 text-zinc-500" />
        ) : (
          <ChevronDown className="h-4 w-4 shrink-0 text-zinc-500" />
        )}
      </button>

      {open && (
        <div className="space-y-4 border-t border-white/[0.06] p-4">
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-medium text-zinc-300">
              {data ? `${data.model} · Android ${data.androidVersion}` : 'État matériel'}
            </span>
            <button
              type="button"
              disabled={loading}
              onClick={() => void onRefresh()}
              className="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-[11px] text-zinc-300 hover:text-white disabled:opacity-40"
            >
              <RefreshCw className={`h-3 w-3 ${loading ? 'animate-spin' : ''}`} />
              <span>Actualiser</span>
            </button>
          </div>

          {!data ? (
            <div className="flex items-center justify-center py-6 text-xs text-zinc-400">
              <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
              Chargement des paramètres du téléphone…
            </div>
          ) : (
            <>
              {/* Hardware Telemetry Strip */}
              <div className="grid grid-cols-3 gap-2 text-center text-[11px]">
                <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
                  <Wifi className="mx-auto h-3.5 w-3.5 text-emerald-400" />
                  <div className="mt-1 truncate font-medium text-white">{data.wifiSummary}</div>
                </div>
                <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
                  <HardDrive className="mx-auto h-3.5 w-3.5 text-sky-400" />
                  <div className="mt-1 font-mono font-medium text-white">
                    {(data.freeStorageMb / 1024).toFixed(1)} Go
                  </div>
                  <div className="text-[10px] text-zinc-500">Libres</div>
                </div>
                <div className="rounded-xl border border-white/[0.06] bg-zinc-950/75 p-2.5">
                  <Cpu className="mx-auto h-3.5 w-3.5 text-amber-400" />
                  <div className="mt-1 font-mono font-medium text-white">
                    {data.batteryTempC > 0 ? `${data.batteryTempC.toFixed(1)}°C` : `${Math.round(data.freeRamMb)} Mo`}
                  </div>
                  <div className="text-[10px] text-zinc-500">
                    {data.batteryTempC > 0 ? 'Batterie' : 'RAM libre'}
                  </div>
                </div>
              </div>

              {/* Ringer Mode */}
              <div className="space-y-1.5">
                <div className="text-xs font-medium text-zinc-300">Mode sonnerie</div>
                <div className="grid grid-cols-3 gap-1.5 text-xs">
                  {(
                    [
                      { id: 'normal', label: 'Normal', Icon: Volume2 },
                      { id: 'vibrate', label: 'Vibreur', Icon: Volume2 },
                      { id: 'silent', label: 'Silencieux', Icon: VolumeX },
                    ] as const
                  ).map(({ id, label, Icon }) => (
                    <button
                      key={id}
                      type="button"
                      onClick={() => void onSetRinger(id)}
                      className={`inline-flex items-center justify-center gap-1.5 rounded-xl border py-2 font-medium transition ${
                        data.ringerMode === id
                          ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-200'
                          : 'border-white/[0.08] bg-zinc-950 text-zinc-400 hover:text-white'
                      }`}
                    >
                      <Icon className="h-3.5 w-3.5" />
                      <span>{label}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Volume Sliders & Brightness */}
              <div className="space-y-3 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3">
                {(
                  [
                    { key: 'music', label: 'Volume Média', value: data.volumeMusic },
                    { key: 'ring', label: 'Volume Sonnerie', value: data.volumeRing },
                    { key: 'alarm', label: 'Volume Alarme', value: data.volumeAlarm },
                  ] as const
                ).map((v) => (
                  <div key={v.key} className="space-y-1">
                    <div className="flex items-center justify-between text-[11px]">
                      <span className="text-zinc-300">{v.label}</span>
                      <span className="font-mono text-zinc-400">{v.value}%</span>
                    </div>
                    <input
                      type="range"
                      min={0}
                      max={100}
                      step={5}
                      defaultValue={v.value}
                      onMouseUp={(e) =>
                        void onSetVolume(v.key, parseInt((e.target as HTMLInputElement).value, 10))
                      }
                      onTouchEnd={(e) =>
                        void onSetVolume(v.key, parseInt((e.target as HTMLInputElement).value, 10))
                      }
                      className="w-full accent-emerald-400"
                    />
                  </div>
                ))}

                <div className="space-y-1 border-t border-white/[0.06] pt-2.5">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="inline-flex items-center gap-1.5 text-zinc-300">
                      <Sun className="h-3.5 w-3.5 text-amber-300" />
                      Luminosité écran
                    </span>
                    <span className="font-mono text-zinc-400">{data.brightnessPct}%</span>
                  </div>
                  <input
                    type="range"
                    min={10}
                    max={100}
                    step={5}
                    defaultValue={data.brightnessPct}
                    onMouseUp={(e) =>
                      void onSetBrightness(parseInt((e.target as HTMLInputElement).value, 10))
                    }
                    onTouchEnd={(e) =>
                      void onSetBrightness(parseInt((e.target as HTMLInputElement).value, 10))
                    }
                    className="w-full accent-amber-400"
                  />
                  {!data.canWriteSettings && (
                    <div className="text-[10px] text-amber-300/80">
                      Autorisez « Paramètres système » sur le téléphone pour piloter la luminosité.
                    </div>
                  )}
                </div>
              </div>

              {/* Complete Installed Apps Inventory */}
              <div className="space-y-2 border-t border-white/[0.06] pt-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-xs font-semibold text-white">
                    Applications installées ({apps.length})
                  </span>
                  {sideloadCount > 0 && (
                    <button
                      type="button"
                      onClick={() => setOnlySideloaded((v) => !v)}
                      className={`rounded-lg border px-2 py-0.5 text-[10px] font-medium ${
                        onlySideloaded
                          ? 'border-amber-400 bg-amber-500/20 text-amber-200'
                          : 'border-white/10 bg-zinc-950 text-amber-300'
                      }`}
                    >
                      Hors Play Store ({sideloadCount})
                    </button>
                  )}
                </div>

                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
                  <input
                    type="text"
                    value={appFilter}
                    onChange={(e) => setAppFilter(e.target.value)}
                    placeholder="Rechercher une application…"
                    className="w-full rounded-xl border border-white/10 bg-zinc-950 py-1.5 pl-8 pr-3 text-xs text-white placeholder-zinc-500"
                  />
                </div>

                <div className="max-h-64 divide-y divide-white/[0.05] overflow-y-auto rounded-xl border border-white/[0.06] bg-zinc-950/60">
                  {filteredApps.slice(0, 80).map((app) => (
                    <div key={app.packageName} className="flex items-center justify-between gap-2 px-3 py-2 text-xs">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="truncate font-medium text-white">{app.appName}</span>
                          {app.sideloaded && (
                            <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[9px] font-semibold text-amber-200">
                              Hors Store
                            </span>
                          )}
                          {!app.hasLauncher && !app.isSystem && (
                            <span className="rounded bg-rose-500/20 px-1.5 py-0.5 text-[9px] font-semibold text-rose-200">
                              Masquée
                            </span>
                          )}
                        </div>
                        <div className="text-[10px] text-zinc-500">
                          Installée le {new Date(app.installedAt).toLocaleDateString('fr-FR')}
                          {app.versionName ? ` · v${app.versionName}` : ''}
                        </div>
                      </div>

                      <div className="flex shrink-0 items-center gap-1">
                        {app.hasLauncher && (
                          <button
                            type="button"
                            onClick={() => void onLaunchApp(app.packageName)}
                            className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-300 hover:text-white"
                            title="Ouvrir sur le téléphone"
                          >
                            <ExternalLink className="h-3.5 w-3.5" />
                          </button>
                        )}
                        {!app.isSystem && (
                          <button
                            type="button"
                            onClick={() => void onUninstallApp(app.packageName)}
                            className="rounded-lg border border-rose-500/30 bg-rose-500/10 p-1.5 text-rose-300 hover:bg-rose-500/20"
                            title="Désinstaller"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};
