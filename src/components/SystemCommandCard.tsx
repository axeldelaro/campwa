import React, { useEffect, useState } from 'react';
import {
  CameraOff,
  Car,
  ChevronDown,
  ChevronUp,
  Clock,
  Cpu,
  ExternalLink,
  Eye,
  EyeOff,
  HardDrive,
  Image as ImageIcon,
  Lock,
  MapPin,
  PauseCircle,
  PlayCircle,
  Power,
  RefreshCw,
  Search,
  ShieldCheck,
  Sun,
  Trash2,
  Volume2,
  VolumeX,
  Wifi,
} from 'lucide-react';
import type { DataSystemStatusMsg, MdmRestrictionTarget } from '../types/sentinel';

interface SystemCommandCardProps {
  data: DataSystemStatusMsg | null;
  loading: boolean;
  defaultOpen?: boolean;
  onRefresh: () => Promise<void>;
  onSetVolume: (stream: 'music' | 'ring' | 'alarm', percent: number) => Promise<void>;
  onSetRinger: (mode: 'normal' | 'vibrate' | 'silent') => Promise<void>;
  onSetBrightness: (percent: number) => Promise<void>;
  onLaunchApp: (packageName: string) => Promise<void>;
  onUninstallApp: (packageName: string) => Promise<void>;
  onMdmSetAppHidden?: (packageName: string, hidden: boolean) => Promise<void>;
  onMdmSetAppSuspended?: (packageName: string, suspended: boolean, message?: string) => Promise<void>;
  onMdmSetRestriction?: (target: MdmRestrictionTarget, enabled: boolean) => Promise<void>;
  onMdmSetScreenTimeout?: (timeoutSec: number) => Promise<void>;
  onMdmLockNow?: () => Promise<void>;
  onMdmReboot?: () => Promise<void>;
  onMdmHardenAll?: () => Promise<void>;
  onSetDrivingSafety?: (opts: {
    enabled: boolean;
    thresholdKmh: number;
    suspendApps: boolean;
    silentRinger: boolean;
  }) => Promise<void>;
}

const TIMEOUT_OPTIONS: Array<{ sec: number; label: string }> = [
  { sec: 0, label: 'Libre' },
  { sec: 15, label: '15 s' },
  { sec: 30, label: '30 s' },
  { sec: 60, label: '1 min' },
  { sec: 120, label: '2 min' },
  { sec: 300, label: '5 min' },
];

export const SystemCommandCard: React.FC<SystemCommandCardProps> = ({
  data,
  loading,
  defaultOpen = true,
  onRefresh,
  onSetVolume,
  onSetRinger,
  onSetBrightness,
  onLaunchApp,
  onUninstallApp,
  onMdmSetAppHidden,
  onMdmSetAppSuspended,
  onMdmSetRestriction,
  onMdmSetScreenTimeout,
  onMdmLockNow,
  onMdmReboot,
  onMdmHardenAll,
  onSetDrivingSafety,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  const [appFilter, setAppFilter] = useState('');
  const [onlySideloaded, setOnlySideloaded] = useState(false);
  const [onlyHidden, setOnlyHidden] = useState(false);
  const [onlySuspended, setOnlySuspended] = useState(false);
  const [suspendMsg, setSuspendMsg] = useState('');
  const [confirmReboot, setConfirmReboot] = useState(false);
  const [localSpeedThreshold, setLocalSpeedThreshold] = useState(25);

  useEffect(() => {
    if (data?.mdmSuspendMessage !== undefined) {
      setSuspendMsg(data.mdmSuspendMessage);
    }
    if (data?.drivingSafety?.thresholdKmh) {
      setLocalSpeedThreshold(data.drivingSafety.thresholdKmh);
    }
  }, [data?.mdmSuspendMessage, data?.drivingSafety?.thresholdKmh]);

  useEffect(() => {
    if (open && !data && !loading) {
      void onRefresh();
    }
  }, [open, data, loading, onRefresh]);

  const handleToggleOpen = () => {
    const next = !open;
    setOpen(next);
    if (next && !data && !loading) {
      void onRefresh();
    }
  };

  const apps = data?.installedApps ?? [];
  const isMdm = Boolean(data?.mdmDeviceOwner);
  const driving = data?.drivingSafety ?? {
    enabled: false,
    thresholdKmh: localSpeedThreshold,
    suspendApps: true,
    silentRinger: true,
    activeNow: false,
    lastSpeedKmh: 0,
  };
  const sideloadCount = apps.filter((a) => a.sideloaded).length;
  const hiddenCount = apps.filter((a) => a.mdmHidden).length;
  const suspendedCount = apps.filter((a) => a.mdmSuspended).length;
  const filteredApps = apps.filter((a) => {
    if (onlySideloaded && !a.sideloaded) return false;
    if (onlyHidden && !a.mdmHidden) return false;
    if (onlySuspended && !a.mdmSuspended) return false;
    const q = appFilter.trim().toLowerCase();
    if (!q) return true;
    return a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q);
  });

  const mdmToggles: Array<{
    key: MdmRestrictionTarget;
    label: string;
    active: boolean;
  }> = [
    { key: 'multi_user', label: 'Bloquer Invité / Espaces & Privé A15', active: Boolean(data?.mdmBlockMultiUser) },
    { key: 'apps_control', label: 'Bloquer Forcer l’arrêt & Effacer', active: Boolean(data?.mdmBlockAppsControl) },
    { key: 'vpn_dns', label: 'Verrouiller VPN & DNS privé', active: Boolean(data?.mdmBlockVpnDns) },
    { key: 'usb_media', label: 'Bloquer USB OTG / SD & Partage BT', active: Boolean(data?.mdmBlockUsbMedia) },
    { key: 'airplane_mode', label: 'Bloquer Mode Avion', active: Boolean(data?.mdmBlockAirplaneMode) },
    { key: 'wallpaper', label: 'Verrouiller fond d’écran', active: Boolean(data?.mdmLockWallpaper) },
    { key: 'location', label: 'Verrouiller localisation', active: Boolean(data?.mdmLockLocation) },
    { key: 'date_time', label: 'Verrouiller date & heure', active: Boolean(data?.mdmLockDateTime) },
    { key: 'unknown_sources', label: 'Bloquer APK externes', active: Boolean(data?.mdmBlockUnknownSources) },
    { key: 'install_apps', label: 'Bloquer installations', active: Boolean(data?.mdmBlockInstallApps) },
    { key: 'uninstall_apps', label: 'Bloquer désinstallations', active: Boolean(data?.mdmBlockUninstallApps) },
    { key: 'safe_boot', label: 'Bloquer mode sans échec', active: Boolean(data?.mdmBlockSafeBoot) },
    { key: 'factory_reset', label: 'Bloquer reset paramètres', active: Boolean(data?.mdmBlockFactoryReset) },
    { key: 'usb_debug', label: 'Bloquer USB / PC', active: Boolean(data?.mdmBlockUsbDebug) },
    { key: 'screenshots', label: 'Bloquer captures d’écran', active: Boolean(data?.mdmBlockScreenshots) },
    { key: 'camera', label: 'Couper caméra système', active: Boolean(data?.mdmDisableCamera) },
    { key: 'tethering', label: 'Bloquer partage Wi-Fi', active: Boolean(data?.mdmBlockTethering) },
  ];

  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.08] bg-zinc-900/70">
      <button
        type="button"
        onClick={handleToggleOpen}
        className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
      >
        <div className="flex min-w-0 items-center gap-2.5">
          <ShieldCheck className={`h-4 w-4 shrink-0 ${isMdm ? 'text-emerald-400' : 'text-zinc-400'}`} />
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-white">Contrôle Admin MDM & Système</span>
              {isMdm && (
                <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-semibold text-emerald-300">
                  MDM Actif
                </span>
              )}
            </div>
            <div className="truncate text-[11px] text-zinc-500">
              {data
                ? `${data.wifiSummary} · ${(data.freeStorageMb / 1024).toFixed(1)} Go libres${
                    hiddenCount > 0 ? ` · ${hiddenCount} masquée(s)` : ''
                  }`
                : 'Verrouillages MDM, masquage d’applis, volumes et inventaire'}
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
              Chargement des contrôles système & MDM…
            </div>
          ) : (
            <>
              {/* ==================== MDM ADMIN CONTROL BLOCK (#2 & #17 + FULL MDM) ==================== */}
              <div className="space-y-3 rounded-xl border border-emerald-500/20 bg-zinc-950/90 p-3.5">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5 text-xs font-semibold text-white">
                    <Lock className="h-3.5 w-3.5 text-emerald-400" />
                    <span>Verrouillages système MDM</span>
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    {isMdm && onMdmHardenAll && (
                      <button
                        type="button"
                        onClick={() => void onMdmHardenAll()}
                        className="inline-flex items-center gap-1 rounded-lg bg-emerald-500 px-2.5 py-1 text-[10px] font-bold text-white kl-solid-white shadow-sm hover:bg-emerald-600"
                        title="Activer tous les verrous anti-désinstallation + auto-réparer les permissions"
                      >
                        <ShieldCheck className="h-3 w-3" />
                        <span>Durcir 1-clic</span>
                      </button>
                    )}
                    <button
                      type="button"
                      disabled={!onMdmLockNow}
                      onClick={() => void onMdmLockNow?.()}
                      className="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-zinc-900 px-2 py-1 text-[10px] font-medium text-zinc-200 hover:border-white/25 disabled:opacity-40"
                      title="Verrouiller l’écran immédiatement"
                    >
                      <Lock className="h-3 w-3" />
                      <span>Verrouiller</span>
                    </button>
                    {isMdm &&
                      onMdmReboot &&
                      (confirmReboot ? (
                        <button
                          type="button"
                          onClick={() => {
                            setConfirmReboot(false);
                            void onMdmReboot();
                          }}
                          className="inline-flex items-center gap-1 rounded-lg bg-rose-500 px-2 py-1 text-[10px] font-semibold text-white"
                        >
                          Confirmer
                        </button>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setConfirmReboot(true)}
                          className="inline-flex items-center gap-1 rounded-lg border border-rose-500/30 bg-rose-500/10 px-2 py-1 text-[10px] font-medium text-rose-200 hover:bg-rose-500/20"
                          title="Redémarrer le téléphone à distance"
                        >
                          <Power className="h-3 w-3" />
                          <span>Reboot</span>
                        </button>
                      ))}
                  </div>
                </div>

                {!isMdm && (
                  <div className="rounded-lg border border-amber-500/25 bg-amber-500/10 px-2.5 py-2 text-[11px] text-amber-200">
                    Mode Appareil Géré (MDM) non détecté sur cet appareil. Utilisez l’enrôlement par{' '}
                    <span className="font-semibold">QR Appareil Géré</span> pour activer les verrouillages système et le masquage d’applis.
                  </div>
                )}

                {/* 2-Column Clean Admin Restrictions Grid */}
                <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  {mdmToggles.map((item) => (
                    <button
                      key={item.key}
                      type="button"
                      disabled={!isMdm || !onMdmSetRestriction}
                      onClick={() => void onMdmSetRestriction?.(item.key, !item.active)}
                      className={`flex items-center justify-between gap-2 rounded-xl border px-3 py-2 text-left text-xs transition disabled:opacity-40 ${
                        item.active
                          ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-100'
                          : 'border-white/[0.07] bg-zinc-900/70 text-zinc-300 hover:border-white/15'
                      }`}
                    >
                      <span className="inline-flex min-w-0 items-center gap-1.5 truncate">
                        {item.key === 'wallpaper' && <ImageIcon className="h-3.5 w-3.5 shrink-0 text-emerald-400" />}
                        {item.key === 'location' && <MapPin className="h-3.5 w-3.5 shrink-0 text-emerald-400" />}
                        {item.key === 'camera' && <CameraOff className="h-3.5 w-3.5 shrink-0 text-rose-300" />}
                        <span className="truncate">{item.label}</span>
                      </span>
                      <span
                        className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                          item.active ? 'bg-emerald-500 text-zinc-950' : 'bg-zinc-800 text-zinc-400'
                        }`}
                      >
                        {item.active ? 'ON' : 'OFF'}
                      </span>
                    </button>
                  ))}
                </div>

                {/* #17 Max Screen Timeout Selector */}
                <div className="space-y-1.5 border-t border-white/[0.06] pt-2.5">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="inline-flex items-center gap-1.5 text-zinc-300">
                      <Clock className="h-3.5 w-3.5 text-sky-400" />
                      <span>Mise en veille écran maximale</span>
                    </span>
                    <span className="font-mono text-sky-300">
                      {(data.mdmMaxScreenTimeoutSec ?? 0) > 0 ? `${data.mdmMaxScreenTimeoutSec} s` : 'Libre'}
                    </span>
                  </div>
                  <div className="grid grid-cols-6 gap-1">
                    {TIMEOUT_OPTIONS.map((opt) => {
                      const active = (data.mdmMaxScreenTimeoutSec ?? 0) === opt.sec;
                      return (
                        <button
                          key={opt.sec}
                          type="button"
                          disabled={!isMdm || !onMdmSetScreenTimeout}
                          onClick={() => void onMdmSetScreenTimeout?.(opt.sec)}
                          className={`rounded-lg border py-1.5 text-[10px] font-medium transition disabled:opacity-40 ${
                            active
                              ? 'border-sky-400 bg-sky-500/20 text-sky-100'
                              : 'border-white/[0.07] bg-zinc-900 text-zinc-400 hover:text-white'
                          }`}
                        >
                          {opt.label}
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>

              {/* ==================== MODE TRAJET / SÉCURITÉ ROUTE (SEUIL VITESSE RÉGLABLE) ==================== */}
              <div className="space-y-3 rounded-xl border border-sky-500/25 bg-zinc-950/90 p-3.5">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5 text-xs font-semibold text-white">
                    <Car className="h-3.5 w-3.5 text-sky-400" />
                    <span>Mode Trajet automatique (Sécurité route)</span>
                  </div>
                  <button
                    type="button"
                    disabled={!onSetDrivingSafety}
                    onClick={() =>
                      void onSetDrivingSafety?.({
                        enabled: !driving.enabled,
                        thresholdKmh: localSpeedThreshold,
                        suspendApps: driving.suspendApps,
                        silentRinger: driving.silentRinger,
                      })
                    }
                    className={`rounded-full px-3 py-1 text-[10px] font-semibold transition disabled:opacity-40 ${
                      driving.enabled
                        ? 'bg-sky-400 text-zinc-950'
                        : 'border border-white/10 bg-zinc-900 text-zinc-400 hover:text-white'
                    }`}
                  >
                    {driving.enabled ? (driving.activeNow ? `ACTIF · ${driving.lastSpeedKmh} km/h` : 'ACTIF') : 'INACTIF'}
                  </button>
                </div>

                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="text-zinc-300">Seuil de vitesse d’activation</span>
                    <span className="font-mono font-semibold text-sky-300">{localSpeedThreshold} km/h</span>
                  </div>
                  <input
                    type="range"
                    min={10}
                    max={130}
                    step={5}
                    value={localSpeedThreshold}
                    onChange={(e) => setLocalSpeedThreshold(parseInt(e.target.value, 10))}
                    onMouseUp={(e) => {
                      const val = parseInt((e.target as HTMLInputElement).value, 10);
                      void onSetDrivingSafety?.({
                        enabled: driving.enabled,
                        thresholdKmh: val,
                        suspendApps: driving.suspendApps,
                        silentRinger: driving.silentRinger,
                      });
                    }}
                    onTouchEnd={(e) => {
                      const val = parseInt((e.target as HTMLInputElement).value, 10);
                      void onSetDrivingSafety?.({
                        enabled: driving.enabled,
                        thresholdKmh: val,
                        suspendApps: driving.suspendApps,
                        silentRinger: driving.silentRinger,
                      });
                    }}
                    className="w-full accent-sky-400"
                  />
                  <div className="grid grid-cols-4 gap-1">
                    {[15, 25, 50, 80].map((spd) => (
                      <button
                        key={spd}
                        type="button"
                        disabled={!onSetDrivingSafety}
                        onClick={() => {
                          setLocalSpeedThreshold(spd);
                          void onSetDrivingSafety?.({
                            enabled: driving.enabled,
                            thresholdKmh: spd,
                            suspendApps: driving.suspendApps,
                            silentRinger: driving.silentRinger,
                          });
                        }}
                        className={`rounded-lg border py-1 text-[10px] font-medium transition disabled:opacity-40 ${
                          localSpeedThreshold === spd
                            ? 'border-sky-400 bg-sky-500/20 text-sky-100'
                            : 'border-white/[0.07] bg-zinc-900 text-zinc-400 hover:text-white'
                        }`}
                      >
                        {spd} km/h
                      </button>
                    ))}
                  </div>
                </div>

                <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  <button
                    type="button"
                    disabled={!onSetDrivingSafety}
                    onClick={() =>
                      void onSetDrivingSafety?.({
                        enabled: driving.enabled,
                        thresholdKmh: localSpeedThreshold,
                        suspendApps: !driving.suspendApps,
                        silentRinger: driving.silentRinger,
                      })
                    }
                    className={`flex items-center justify-between rounded-xl border px-3 py-2 text-[11px] transition disabled:opacity-40 ${
                      driving.suspendApps
                        ? 'border-sky-500/45 bg-sky-500/15 text-sky-100'
                        : 'border-white/[0.07] bg-zinc-900 text-zinc-400'
                    }`}
                  >
                    <span>Suspendre réseaux / vidéos / jeux</span>
                    <span className="font-semibold">{driving.suspendApps ? 'ON' : 'OFF'}</span>
                  </button>
                  <button
                    type="button"
                    disabled={!onSetDrivingSafety}
                    onClick={() =>
                      void onSetDrivingSafety?.({
                        enabled: driving.enabled,
                        thresholdKmh: localSpeedThreshold,
                        suspendApps: driving.suspendApps,
                        silentRinger: !driving.silentRinger,
                      })
                    }
                    className={`flex items-center justify-between rounded-xl border px-3 py-2 text-[11px] transition disabled:opacity-40 ${
                      driving.silentRinger
                        ? 'border-sky-500/45 bg-sky-500/15 text-sky-100'
                        : 'border-white/[0.07] bg-zinc-900 text-zinc-400'
                    }`}
                  >
                    <span>Mode vibreur en roulant</span>
                    <span className="font-semibold">{driving.silentRinger ? 'ON' : 'OFF'}</span>
                  </button>
                </div>
                <p className="text-[10px] text-zinc-500">
                  S’active dès que la vitesse dépasse {localSpeedThreshold} km/h (garde Appels, Urgences & GPS actifs) et rétablit automatiquement tout 45 s après l’arrêt.
                </p>
              </div>

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

              {/* Ringer Mode + Volumes + Brightness */}
              <div className="space-y-3 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3">
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
                          : 'border-white/[0.08] bg-zinc-900 text-zinc-400 hover:text-white'
                      }`}
                    >
                      <Icon className="h-3.5 w-3.5" />
                      <span>{label}</span>
                    </button>
                  ))}
                </div>

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
                </div>
              </div>

              {/* Complete Installed Apps Inventory + #2 MDM Temporary Hide/Unhide */}
              <div className="space-y-2 border-t border-white/[0.06] pt-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-xs font-semibold text-white">
                    Applications ({apps.length})
                  </span>
                  <div className="flex flex-wrap items-center gap-1.5">
                    {suspendedCount > 0 && (
                      <button
                        type="button"
                        onClick={() => setOnlySuspended((v) => !v)}
                        className={`rounded-lg border px-2 py-0.5 text-[10px] font-medium ${
                          onlySuspended
                            ? 'border-sky-400 bg-sky-500/20 text-sky-200'
                            : 'border-white/10 bg-zinc-950 text-sky-300'
                        }`}
                      >
                        Suspendues ({suspendedCount})
                      </button>
                    )}
                    {hiddenCount > 0 && (
                      <button
                        type="button"
                        onClick={() => setOnlyHidden((v) => !v)}
                        className={`rounded-lg border px-2 py-0.5 text-[10px] font-medium ${
                          onlyHidden
                            ? 'border-purple-400 bg-purple-500/20 text-purple-200'
                            : 'border-white/10 bg-zinc-950 text-purple-300'
                        }`}
                      >
                        Masquées ({hiddenCount})
                      </button>
                    )}
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
                        Hors Store ({sideloadCount})
                      </button>
                    )}
                  </div>
                </div>

                {isMdm && onMdmSetAppSuspended && (
                  <input
                    type="text"
                    maxLength={140}
                    value={suspendMsg}
                    onChange={(e) => setSuspendMsg(e.target.value)}
                    placeholder="Message personnalisé sur appli suspendue (ex. Finis tes devoirs d’abord)"
                    className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-[11px] text-white placeholder-zinc-500 focus:border-sky-400 focus:outline-none"
                  />
                )}

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

                <div className="max-h-72 divide-y divide-white/[0.05] overflow-y-auto rounded-xl border border-white/[0.06] bg-zinc-950/60">
                  {filteredApps.slice(0, 90).map((app) => (
                    <div key={app.packageName} className="flex items-center justify-between gap-2 px-3 py-2 text-xs">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className={`truncate font-medium ${app.mdmHidden || app.mdmSuspended ? 'text-zinc-500 line-through' : 'text-white'}`}>
                            {app.appName}
                          </span>
                          {app.mdmSuspended && (
                            <span className="rounded bg-sky-500/20 px-1.5 py-0.5 text-[9px] font-semibold text-sky-200">
                              Suspendue
                            </span>
                          )}
                          {app.mdmHidden && (
                            <span className="rounded bg-purple-500/20 px-1.5 py-0.5 text-[9px] font-semibold text-purple-200">
                              Masquée MDM
                            </span>
                          )}
                          {app.sideloaded && (
                            <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[9px] font-semibold text-amber-200">
                              Hors Store
                            </span>
                          )}
                        </div>
                        <div className="truncate text-[10px] text-zinc-500">
                          {app.versionName ? `v${app.versionName} · ` : ''}
                          {new Date(app.installedAt).toLocaleDateString('fr-FR')}
                        </div>
                      </div>

                      <div className="flex shrink-0 items-center gap-1">
                        {isMdm && onMdmSetAppSuspended && !app.mdmHidden && (
                          <button
                            type="button"
                            onClick={() =>
                              void onMdmSetAppSuspended(
                                app.packageName,
                                !app.mdmSuspended,
                                suspendMsg.trim() || undefined
                              )
                            }
                            className={`rounded-lg border p-1.5 transition ${
                              app.mdmSuspended
                                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300 hover:bg-emerald-500/25'
                                : 'border-sky-500/30 bg-sky-500/10 text-sky-300 hover:bg-sky-500/20'
                            }`}
                            title={app.mdmSuspended ? 'Réactiver l’application' : 'Suspendre au niveau système (MDM)'}
                          >
                            {app.mdmSuspended ? (
                              <PlayCircle className="h-3.5 w-3.5" />
                            ) : (
                              <PauseCircle className="h-3.5 w-3.5" />
                            )}
                          </button>
                        )}
                        {isMdm && onMdmSetAppHidden && (
                          <button
                            type="button"
                            onClick={() => void onMdmSetAppHidden(app.packageName, !app.mdmHidden)}
                            className={`rounded-lg border p-1.5 transition ${
                              app.mdmHidden
                                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300 hover:bg-emerald-500/25'
                                : 'border-purple-500/30 bg-purple-500/10 text-purple-300 hover:bg-purple-500/20'
                            }`}
                            title={app.mdmHidden ? 'Réafficher sur le téléphone' : 'Masquer temporairement (MDM)'}
                          >
                            {app.mdmHidden ? <Eye className="h-3.5 w-3.5" /> : <EyeOff className="h-3.5 w-3.5" />}
                          </button>
                        )}
                        {app.hasLauncher && !app.mdmHidden && (
                          <button
                            type="button"
                            onClick={() => void onLaunchApp(app.packageName)}
                            className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-300 hover:text-white"
                            title="Ouvrir sur le téléphone"
                          >
                            <ExternalLink className="h-3.5 w-3.5" />
                          </button>
                        )}
                        {!app.isSystem && !app.mdmHidden && (
                          <button
                            type="button"
                            onClick={() => void onUninstallApp(app.packageName)}
                            className="rounded-lg border border-rose-500/30 bg-rose-500/10 p-1.5 text-rose-300 hover:bg-rose-500/20"
                            title={isMdm ? 'Désinstallation silencieuse MDM (sans confirmation)' : 'Demander la désinstallation'}
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
