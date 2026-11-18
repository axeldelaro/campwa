import React, { useEffect, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  Battery,
  BatteryCharging,
  Camera,
  CameraOff,
  CheckCircle2,
  Download,
  FlipHorizontal,
  Gauge,
  History,
  KeyRound,
  Link2,
  Lock,
  Maximize2,
  Mic,
  MicOff,
  Power,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Trash2,
  Unplug,
  Video,
  Volume2,
  VolumeX,
  Zap,
} from 'lucide-react';
import { normalizePairingCode } from '../crypto/cryptoEngine';
import { listPairedDevices, removePairedDevice } from '../services/deviceVault';
import type { BrokerHealth } from '../services/signalingService';
import { SentinelWebRtcController } from '../services/webrtcController';
import type {
  AuthErrMsg,
  CameraFacingMode,
  DeviceTelemetry,
  PairedDeviceRecord,
  PortalOperationalState,
  SnapshotItem,
  StreamQualityPreset,
  StreamRealtimeStats,
} from '../types/sentinel';
import { SecuritySasModal } from './SecuritySasModal';

interface ControlStationViewProps {
  onBrokerHealthChange: (connected: boolean, brokers: BrokerHealth[]) => void;
}

interface LogEntry {
  id: string;
  ts: number;
  level: 'info' | 'warn' | 'security';
  text: string;
}

export const ControlStationView: React.FC<ControlStationViewProps> = ({
  onBrokerHealthChange,
}) => {
  const [codeChars, setCodeChars] = useState<string[]>(['', '', '', '', '', '']);
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);

  const [savedDevices, setSavedDevices] = useState<PairedDeviceRecord[]>(() =>
    listPairedDevices()
  );
  const [portalState, setPortalState] = useState<PortalOperationalState>('offline');
  const [telemetry, setTelemetry] = useState<DeviceTelemetry | null>(null);
  const [stats, setStats] = useState<StreamRealtimeStats | null>(null);
  const [authError, setAuthError] = useState<AuthErrMsg | null>(null);

  const [sasInfo, setSasInfo] = useState<{
    deviceName: string;
    sasCode: string;
    sasSymbols: string;
  } | null>(null);
  const [sasModalOpen, setSasModalOpen] = useState(false);

  const [cameraFacing, setCameraFacing] = useState<CameraFacingMode>('front');
  const [remoteMicEnabled, setRemoteMicEnabled] = useState(false);
  const [localAudioMuted, setLocalAudioMuted] = useState(false);
  const [quality, setQuality] = useState<StreamQualityPreset>('hd');
  const [mirrorVideo, setMirrorVideo] = useState(true);

  const [snapshots, setSnapshots] = useState<SnapshotItem[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const videoContainerRef = useRef<HTMLDivElement | null>(null);
  const controllerRef = useRef<SentinelWebRtcController | null>(null);

  const appendLog = (level: 'info' | 'warn' | 'security', text: string) => {
    setLogs((prev) => [
      { id: `${Date.now()}-${Math.random()}`, ts: Date.now(), level, text },
      ...prev.slice(0, 49),
    ]);
  };

  useEffect(() => {
    const ctrl = new SentinelWebRtcController({
      onStateChange: (st) => setPortalState(st),
      onBrokerHealth: (connected, brokers) => onBrokerHealthChange(connected, brokers),
      onTelemetry: (t) => {
        setTelemetry(t);
        setCameraFacing(t.cameraFacing);
        setRemoteMicEnabled(t.micEnabled);
      },
      onAuthSuccess: (info) => {
        setAuthError(null);
        setSasInfo({
          deviceName: info.deviceName,
          sasCode: info.sasCode,
          sasSymbols: info.sasSymbols,
        });
        setSavedDevices(listPairedDevices());
        if (info.isNewPairing) {
          setSasModalOpen(true);
        }
      },
      onAuthError: (err) => {
        setAuthError(err);
      },
      onRemoteStream: (stream) => {
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          if (stream) {
            void videoRef.current.play().catch(() => {
              // Autoplay fallback handled via muted interaction
            });
          }
        }
      },
      onStatsUpdate: (s) => setStats(s),
      onLog: (level, text) => appendLog(level, text),
    });

    controllerRef.current = ctrl;
    return () => {
      ctrl.disconnect();
    };
  }, [onBrokerHealthChange]);

  const fullCode = codeChars.join('');

  const handleCharChange = (index: number, val: string) => {
    const clean = val.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
    if (!clean) {
      const next = [...codeChars];
      next[index] = '';
      setCodeChars(next);
      return;
    }

    // Handle multi-char paste/input
    if (clean.length > 1) {
      const chars = clean.slice(0, 6).split('');
      const next = [...codeChars];
      for (let i = 0; i < 6; i++) {
        if (index + i < 6 && chars[i]) {
          next[index + i] = chars[i];
        }
      }
      setCodeChars(next);
      const focusIdx = Math.min(5, index + chars.length);
      inputRefs.current[focusIdx]?.focus();
      return;
    }

    const next = [...codeChars];
    next[index] = clean;
    setCodeChars(next);
    if (index < 5) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !codeChars[index] && index > 0) {
      inputRefs.current[index - 1]?.focus();
    } else if (e.key === 'Enter' && fullCode.length === 6) {
      void handleConnect(fullCode);
    }
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const pasted = normalizePairingCode(e.clipboardData.getData('text'));
    if (!pasted) return;
    const next = ['', '', '', '', '', ''];
    for (let i = 0; i < pasted.length && i < 6; i++) {
      next[i] = pasted[i];
    }
    setCodeChars(next);
    if (pasted.length === 6) {
      void handleConnect(pasted);
    }
  };

  const handleConnect = async (targetCode: string) => {
    setAuthError(null);
    const normalized = normalizePairingCode(targetCode);
    if (normalized.length !== 6) return;

    setCodeChars(normalized.split(''));
    try {
      await controllerRef.current?.connectToPortal(normalized);
    } catch (err) {
      appendLog('warn', err instanceof Error ? err.message : 'Erreur de connexion');
    }
  };

  const handleDisconnect = () => {
    controllerRef.current?.disconnect();
    setTelemetry(null);
    setStats(null);
    setSasInfo(null);
  };

  const handleForgetDevice = (code: string, e: React.MouseEvent) => {
    e.stopPropagation();
    removePairedDevice(code);
    setSavedDevices(listPairedDevices());
  };

  const handleWakeCamera = async () => {
    try {
      await controllerRef.current?.startCameraStream(cameraFacing, remoteMicEnabled, quality);
    } catch (err) {
      appendLog('warn', err instanceof Error ? err.message : 'Impossible d’allumer la caméra');
    }
  };

  const handleSleepCamera = async () => {
    await controllerRef.current?.stopCameraStream();
  };

  const handleToggleFacing = async () => {
    const nextFacing: CameraFacingMode = cameraFacing === 'front' ? 'back' : 'front';
    setCameraFacing(nextFacing);
    setMirrorVideo(nextFacing === 'front');
    if (portalState === 'streaming') {
      await controllerRef.current?.switchCamera(nextFacing);
    }
  };

  const handleToggleRemoteMic = async () => {
    const next = !remoteMicEnabled;
    setRemoteMicEnabled(next);
    if (portalState === 'streaming') {
      await controllerRef.current?.toggleRemoteMicrophone(next);
    }
  };

  const handleCaptureSnapshot = () => {
    if (!videoRef.current || !controllerRef.current) return;
    const snap = controllerRef.current.captureSnapshot(
      videoRef.current,
      telemetry?.deviceName || 'SentinelCam'
    );
    if (snap) {
      setSnapshots((prev) => [snap, ...prev.slice(0, 7)]);
      appendLog('info', `Capture PNG enregistrée (${snap.width}×${snap.height}).`);
    }
  };

  const handleFullscreen = () => {
    if (!videoContainerRef.current) return;
    if (!document.fullscreenElement) {
      void videoContainerRef.current.requestFullscreen().catch(() => {});
    } else {
      void document.exitFullscreen().catch(() => {});
    }
  };

  const isLinked = portalState === 'standby' || portalState === 'streaming';
  const isStreaming = portalState === 'streaming';

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        {/* LEFT COLUMN: PAIRING & SAVED VAULT + LOGS */}
        <div className="space-y-5 lg:col-span-4">
          {/* Pairing Card */}
          <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-5 shadow-xl backdrop-blur">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <KeyRound className="h-4 w-4 text-emerald-400" />
                <h2 className="text-sm font-bold uppercase tracking-wider text-slate-200">
                  Liaison Portail E2EE
                </h2>
              </div>
              {isLinked && (
                <button
                  type="button"
                  onClick={handleDisconnect}
                  className="inline-flex items-center gap-1 rounded-lg border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-[11px] font-semibold text-rose-300 transition hover:bg-rose-500/20"
                >
                  <Unplug className="h-3 w-3" />
                  Délier
                </button>
              )}
            </div>

            <p className="mt-1.5 text-xs text-slate-400">
              Saisissez le code à 6 caractères généré par l’application Android SentinelCam.
            </p>

            {/* 6-char Input Slots */}
            <div className="mt-4 grid grid-cols-6 gap-2" onPaste={handlePaste}>
              {codeChars.map((ch, idx) => (
                <input
                  key={idx}
                  ref={(el) => {
                    inputRefs.current[idx] = el;
                  }}
                  type="text"
                  inputMode="text"
                  maxLength={6}
                  value={ch}
                  disabled={portalState === 'connecting'}
                  onChange={(e) => handleCharChange(idx, e.target.value)}
                  onKeyDown={(e) => handleKeyDown(idx, e)}
                  className="h-12 w-full rounded-xl border border-slate-700/80 bg-obsidian-950 text-center font-mono text-lg font-bold uppercase text-emerald-300 shadow-inner transition focus:border-emerald-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/25 disabled:opacity-50"
                  placeholder="•"
                />
              ))}
            </div>

            <button
              type="button"
              disabled={fullCode.length !== 6 || portalState === 'connecting'}
              onClick={() => void handleConnect(fullCode)}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-400 px-4 py-3 text-sm font-bold text-obsidian-950 shadow-lg shadow-emerald-500/20 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {portalState === 'connecting' ? (
                <>
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  <span>Négociation Cryptographique...</span>
                </>
              ) : isLinked ? (
                <>
                  <CheckCircle2 className="h-4 w-4" />
                  <span>Portail Authentifié ({fullCode})</span>
                </>
              ) : (
                <>
                  <Link2 className="h-4 w-4" />
                  <span>Se lier au Smartphone</span>
                </>
              )}
            </button>

            {authError && (
              <div className="mt-3.5 flex items-start gap-2.5 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-xs text-rose-200">
                <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
                <div>
                  <div className="font-bold">Accès refusé ({authError.reason})</div>
                  <div className="mt-0.5 text-rose-300/90">{authError.message}</div>
                </div>
              </div>
            )}
          </div>

          {/* Saved Paired Smartphones (1-Click Reconnect Vault) */}
          <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-5 shadow-xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Smartphone className="h-4 w-4 text-cyan-400" />
                <h3 className="text-xs font-bold uppercase tracking-wider text-slate-200">
                  Appareils Appairés (1-Clic)
                </h3>
              </div>
              <span className="rounded-full bg-slate-800 px-2 py-0.5 font-mono text-[10px] text-slate-400">
                {savedDevices.length}
              </span>
            </div>

            {savedDevices.length === 0 ? (
              <p className="mt-3 rounded-xl border border-dashed border-slate-800 bg-obsidian-950/50 p-3.5 text-center text-xs text-slate-500">
                Aucun smartphone mémorisé pour le moment. Votre jeton HKDF sera sauvegardé
                localement dès le premier appairage réussi.
              </p>
            ) : (
              <div className="mt-3 space-y-2">
                {savedDevices.map((dev) => {
                  const isCurrent = isLinked && fullCode === dev.pairingCode;
                  return (
                    <div
                      key={dev.pairingCode}
                      onClick={() => void handleConnect(dev.pairingCode)}
                      className={`group flex cursor-pointer items-center justify-between rounded-xl border p-3 transition ${
                        isCurrent
                          ? 'border-emerald-500/50 bg-emerald-500/10'
                          : 'border-slate-800 bg-obsidian-950/70 hover:border-slate-700 hover:bg-obsidian-950'
                      }`}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate text-xs font-bold text-white">
                            {dev.deviceName}
                          </span>
                          <span className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-emerald-300">
                            {dev.pairingCode}
                          </span>
                        </div>
                        <div className="mt-1 flex items-center gap-2 text-[11px] text-slate-400">
                          <span>SAS {dev.sasCode}</span>
                          <span>•</span>
                          <span>{dev.sasSymbols}</span>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={(e) => handleForgetDevice(dev.pairingCode, e)}
                        className="ml-2 rounded-lg p-1.5 text-slate-500 opacity-70 transition hover:bg-rose-500/15 hover:text-rose-400 group-hover:opacity-100"
                        title="Oublier ce jeton local"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Security & Signaling Event Log */}
          <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-5 shadow-xl">
            <div className="flex items-center gap-2">
              <History className="h-4 w-4 text-emerald-400" />
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-200">
                Journal Cryptographique Temps Réel
              </h3>
            </div>
            <div className="mt-3 max-h-52 space-y-1.5 overflow-y-auto pr-1 font-mono text-[11px]">
              {logs.length === 0 ? (
                <div className="text-slate-500">Prêt. En attente de liaison au portail...</div>
              ) : (
                logs.map((l) => (
                  <div
                    key={l.id}
                    className={`rounded-lg border px-2.5 py-1.5 ${
                      l.level === 'security'
                        ? 'border-emerald-500/20 bg-emerald-500/5 text-emerald-300'
                        : l.level === 'warn'
                        ? 'border-amber-500/25 bg-amber-500/10 text-amber-300'
                        : 'border-slate-800/80 bg-obsidian-950/60 text-slate-300'
                    }`}
                  >
                    <span className="mr-1.5 text-slate-500">
                      {new Date(l.ts).toLocaleTimeString('fr-FR')}
                    </span>
                    {l.text}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        {/* RIGHT COLUMN: LIVE MONITOR, ON-DEMAND CONTROLS & TELEMETRY */}
        <div className="space-y-5 lg:col-span-8">
          {/* Top Real-Time Telemetry Strip */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {/* Portal State Badge */}
            <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-3.5">
              <div className="text-[11px] font-medium text-slate-400">État du Portail</div>
              <div className="mt-1.5 flex items-center gap-2">
                <span
                  className={`h-2.5 w-2.5 rounded-full ${
                    portalState === 'streaming'
                      ? 'bg-rose-500 animate-ping'
                      : portalState === 'standby'
                      ? 'bg-emerald-400'
                      : portalState === 'connecting'
                      ? 'bg-cyan-400 animate-pulse'
                      : portalState === 'locked'
                      ? 'bg-amber-500'
                      : 'bg-slate-600'
                  }`}
                />
                <span className="text-sm font-bold text-white">
                  {portalState === 'streaming'
                    ? 'STREAMING DIRECT'
                    : portalState === 'standby'
                    ? 'EN LIGNE (VEILLE)'
                    : portalState === 'connecting'
                    ? 'CONNEXION...'
                    : portalState === 'locked'
                    ? 'VERROUILLÉ'
                    : 'HORS LIGNE'}
                </span>
              </div>
              <div className="mt-1 truncate font-mono text-[10px] text-slate-500">
                {telemetry ? `${telemetry.deviceName} • ${telemetry.networkType}` : 'Aucun flux actif'}
              </div>
            </div>

            {/* Remote Smartphone Battery */}
            <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-3.5">
              <div className="flex items-center justify-between text-[11px] font-medium text-slate-400">
                <span>Batterie Smartphone</span>
                {telemetry?.isCharging ? (
                  <BatteryCharging className="h-4 w-4 text-emerald-400" />
                ) : (
                  <Battery className="h-4 w-4 text-slate-400" />
                )}
              </div>
              <div className="mt-1.5 font-mono text-lg font-bold text-white">
                {telemetry ? `${telemetry.batteryLevel}%` : '—'}
              </div>
              <div className="mt-1 text-[10px] text-slate-500">
                {telemetry
                  ? telemetry.isCharging
                    ? 'En charge secteur/USB'
                    : 'Sur batterie autonome'
                  : 'En attente télémétrie'}
              </div>
            </div>

            {/* Latency RTT */}
            <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-3.5">
              <div className="flex items-center justify-between text-[11px] font-medium text-slate-400">
                <span>Latence Temps Réel</span>
                <Activity className="h-4 w-4 text-cyan-400" />
              </div>
              <div className="mt-1.5 font-mono text-lg font-bold text-cyan-300">
                {isStreaming && stats ? `${stats.rttMs} ms` : '—'}
              </div>
              <div className="mt-1 font-mono text-[10px] text-slate-500">
                {isStreaming && stats && stats.width > 0
                  ? `${stats.width}×${stats.height} @ ${stats.fps} fps`
                  : 'Capteur éteint (0 mA)'}
              </div>
            </div>

            {/* Bitrate & E2EE */}
            <div
              onClick={() => sasInfo && setSasModalOpen(true)}
              className={`rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-3.5 ${
                sasInfo ? 'cursor-pointer transition hover:border-emerald-500/40' : ''
              }`}
            >
              <div className="flex items-center justify-between text-[11px] font-medium text-slate-400">
                <span>Débit & Sécurité</span>
                <Gauge className="h-4 w-4 text-emerald-400" />
              </div>
              <div className="mt-1.5 font-mono text-lg font-bold text-emerald-300">
                {isStreaming && stats ? `${stats.bitrateKbps} kbps` : sasInfo ? sasInfo.sasCode : '—'}
              </div>
              <div className="mt-1 flex items-center gap-1 font-mono text-[10px] text-slate-400">
                <Lock className="h-3 w-3 text-emerald-400" />
                <span className="truncate">
                  {sasInfo ? `SAS ${sasInfo.sasSymbols} (Vérifier)` : 'DTLS-SRTP + AES-256'}
                </span>
              </div>
            </div>
          </div>

          {/* VIDEO VIEWPORT */}
          <div
            ref={videoContainerRef}
            className="relative aspect-video w-full overflow-hidden rounded-2xl border border-slate-800 bg-obsidian-950 shadow-2xl"
          >
            {/* Corner Tactical Reticles */}
            <div className="pointer-events-none absolute left-3 top-3 z-20 h-5 w-5 border-l-2 border-t-2 border-emerald-400/50" />
            <div className="pointer-events-none absolute right-3 top-3 z-20 h-5 w-5 border-r-2 border-t-2 border-emerald-400/50" />
            <div className="pointer-events-none absolute bottom-3 left-3 z-20 h-5 w-5 border-b-2 border-l-2 border-emerald-400/50" />
            <div className="pointer-events-none absolute bottom-3 right-3 z-20 h-5 w-5 border-b-2 border-r-2 border-emerald-400/50" />

            {/* Live Overlay Badges */}
            {isStreaming && (
              <div className="absolute left-5 top-5 z-20 flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/40 bg-rose-950/80 px-2.5 py-1 font-mono text-[11px] font-bold text-rose-200 backdrop-blur">
                  <span className="h-2 w-2 rounded-full bg-rose-500 animate-ping" />
                  LIVE • {cameraFacing === 'front' ? 'CAMÉRA FRONTALE' : 'CAMÉRA ARRIÈRE'}
                </span>
                <span className="inline-flex items-center gap-1 rounded-full border border-emerald-500/30 bg-obsidian-950/80 px-2.5 py-1 font-mono text-[10px] text-emerald-300 backdrop-blur">
                  <ShieldCheck className="h-3 w-3" />
                  E2EE {stats?.codec || 'WebRTC'}
                </span>
              </div>
            )}

            {/* Video Element */}
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted={localAudioMuted}
              className={`h-full w-full object-contain transition-opacity duration-300 ${
                isStreaming ? 'opacity-100' : 'opacity-0'
              } ${mirrorVideo ? 'scale-x-[-1]' : ''}`}
            />

            {/* Non-Streaming Standby / Offline Overlay */}
            {!isStreaming && (
              <div className="absolute inset-0 flex flex-col items-center justify-center bg-radial-glow p-6 text-center">
                {portalState === 'standby' ? (
                  <>
                    <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/10 shadow-lg shadow-emerald-500/10">
                      <CameraOff className="h-8 w-8 text-emerald-400" />
                    </div>
                    <h3 className="mt-4 text-lg font-bold text-white">
                      Portail Connecté • Capteur Caméra en Veille Physique
                    </h3>
                    <p className="mt-1.5 max-w-md text-xs text-slate-400">
                      Le capteur caméra du smartphone est actuellement coupé pour préserver la
                      batterie. Cliquez sur{' '}
                      <span className="font-semibold text-emerald-300">« Allumer la caméra »</span>{' '}
                      pour démarrer le flux direct en moins de 2 secondes.
                    </p>
                    <button
                      type="button"
                      onClick={() => void handleWakeCamera()}
                      className="mt-5 inline-flex items-center gap-2.5 rounded-xl bg-emerald-500 px-6 py-3 text-sm font-bold text-obsidian-950 shadow-xl shadow-emerald-500/25 transition hover:scale-[1.02] hover:bg-emerald-400"
                    >
                      <Video className="h-4 w-4" />
                      <span>Allumer la caméra maintenant</span>
                    </button>
                  </>
                ) : portalState === 'connecting' ? (
                  <>
                    <RefreshCw className="h-10 w-10 animate-spin text-emerald-400" />
                    <h3 className="mt-4 text-base font-bold text-white">
                      Établissement du Tunnel Sécurisé...
                    </h3>
                    <p className="mt-1 text-xs text-slate-400">
                      Réveil du service Android en arrière-plan et négociation DTLS-SRTP...
                    </p>
                  </>
                ) : portalState === 'locked' ? (
                  <>
                    <AlertTriangle className="h-10 w-10 text-amber-400" />
                    <h3 className="mt-3 text-base font-bold text-amber-300">
                      Portail Temporairement Verrouillé (Anti-Brute-Force)
                    </h3>
                    <p className="mt-1 max-w-md text-xs text-slate-400">
                      Plusieurs tentatives non autorisées ont été détectées. Le portail applique un
                      délai exponentiel de sécurité.
                    </p>
                  </>
                ) : (
                  <>
                    <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-slate-800 bg-obsidian-900">
                      <ShieldCheck className="h-8 w-8 text-slate-500" />
                    </div>
                    <h3 className="mt-4 text-base font-bold text-slate-200">
                      Aucun Smartphone Lié
                    </h3>
                    <p className="mt-1 max-w-sm text-xs text-slate-500">
                      Entrez le code à 6 caractères de votre smartphone Android (ou sélectionnez un
                      appareil déjà appairé à gauche) pour activer le centre de contrôle.
                    </p>
                  </>
                )}
              </div>
            )}
          </div>

          {/* ON-DEMAND COMMAND DECK */}
          <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-4 shadow-xl">
            <div className="flex flex-wrap items-center justify-between gap-3">
              {/* Primary Wake / Sleep Toggle */}
              <div className="flex flex-wrap items-center gap-2.5">
                {!isStreaming ? (
                  <button
                    type="button"
                    disabled={!isLinked}
                    onClick={() => void handleWakeCamera()}
                    className="inline-flex items-center gap-2 rounded-xl bg-emerald-500 px-5 py-2.5 text-sm font-bold text-obsidian-950 shadow-lg shadow-emerald-500/20 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <Zap className="h-4 w-4" />
                    <span>Allumer la caméra</span>
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => void handleSleepCamera()}
                    className="inline-flex items-center gap-2 rounded-xl border border-amber-500/40 bg-amber-500/15 px-5 py-2.5 text-sm font-bold text-amber-300 transition hover:bg-amber-500/25"
                  >
                    <Power className="h-4 w-4" />
                    <span>Mettre en veille la caméra</span>
                  </button>
                )}

                {/* Switch Front / Back Lens */}
                <button
                  type="button"
                  disabled={!isLinked}
                  onClick={() => void handleToggleFacing()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-950 px-3.5 py-2.5 text-xs font-semibold text-slate-200 transition hover:border-slate-600 hover:text-white disabled:opacity-40"
                  title="Basculer entre Caméra Selfie (Frontale) et Caméra Arrière"
                >
                  <RefreshCw className="h-3.5 w-3.5 text-cyan-400" />
                  <span>{cameraFacing === 'front' ? 'Selfie (Front)' : 'Arrière (Back)'}</span>
                </button>

                {/* Remote Microphone Control */}
                <button
                  type="button"
                  disabled={!isLinked}
                  onClick={() => void handleToggleRemoteMic()}
                  className={`inline-flex items-center gap-1.5 rounded-xl border px-3.5 py-2.5 text-xs font-semibold transition disabled:opacity-40 ${
                    remoteMicEnabled
                      ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                      : 'border-slate-700 bg-obsidian-950 text-slate-300 hover:text-white'
                  }`}
                  title="Activer ou couper le microphone du smartphone"
                >
                  {remoteMicEnabled ? (
                    <>
                      <Mic className="h-3.5 w-3.5 text-emerald-400" />
                      <span>Micro Actif</span>
                    </>
                  ) : (
                    <>
                      <MicOff className="h-3.5 w-3.5 text-slate-400" />
                      <span>Micro Coupé</span>
                    </>
                  )}
                </button>
              </div>

              {/* Secondary Tools: Snapshot, Mute Speaker, Mirror, Quality, Fullscreen */}
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  disabled={!isStreaming}
                  onClick={handleCaptureSnapshot}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-cyan-500/35 bg-cyan-500/10 px-3.5 py-2.5 text-xs font-semibold text-cyan-300 transition hover:bg-cyan-500/20 disabled:opacity-40"
                  title="Capturer une image PNG haute définition en local"
                >
                  <Camera className="h-3.5 w-3.5" />
                  <span>Capture PNG</span>
                </button>

                <button
                  type="button"
                  onClick={() => setLocalAudioMuted((m) => !m)}
                  className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-slate-800 bg-obsidian-950 text-slate-300 transition hover:text-white"
                  title={localAudioMuted ? 'Réactiver le haut-parleur local' : 'Couper le haut-parleur local'}
                >
                  {localAudioMuted ? (
                    <VolumeX className="h-4 w-4 text-rose-400" />
                  ) : (
                    <Volume2 className="h-4 w-4 text-emerald-400" />
                  )}
                </button>

                <button
                  type="button"
                  onClick={() => setMirrorVideo((m) => !m)}
                  className={`inline-flex h-9 w-9 items-center justify-center rounded-xl border bg-obsidian-950 transition ${
                    mirrorVideo
                      ? 'border-emerald-500/40 text-emerald-300'
                      : 'border-slate-800 text-slate-400 hover:text-white'
                  }`}
                  title="Inverser horizontalement (miroir selfie)"
                >
                  <FlipHorizontal className="h-4 w-4" />
                </button>

                <button
                  type="button"
                  onClick={() => setQuality((q) => (q === 'hd' ? 'sd' : 'hd'))}
                  className="rounded-xl border border-slate-800 bg-obsidian-950 px-2.5 py-2 font-mono text-xs font-bold text-slate-300 transition hover:border-slate-700 hover:text-white"
                  title="Qualité cible lors du prochain allumage"
                >
                  {quality.toUpperCase()}
                </button>

                <button
                  type="button"
                  disabled={!isStreaming}
                  onClick={handleFullscreen}
                  className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-slate-800 bg-obsidian-950 text-slate-300 transition hover:text-white disabled:opacity-40"
                  title="Plein écran"
                >
                  <Maximize2 className="h-4 w-4" />
                </button>
              </div>
            </div>
          </div>

          {/* Local Snapshots Gallery */}
          {snapshots.length > 0 && (
            <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/85 p-4">
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-300">
                Captures Instantanées Locales ({snapshots.length})
              </h3>
              <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
                {snapshots.map((snap) => (
                  <div
                    key={snap.id}
                    className="group relative overflow-hidden rounded-xl border border-slate-800 bg-obsidian-950"
                  >
                    <img
                      src={snap.dataUrl}
                      alt="Capture SentinelCam"
                      className="aspect-video w-full object-cover"
                    />
                    <div className="flex items-center justify-between px-2.5 py-1.5 text-[10px] text-slate-400">
                      <span>{new Date(snap.timestamp).toLocaleTimeString('fr-FR')}</span>
                      <a
                        href={snap.dataUrl}
                        download={`SentinelCam_${snap.timestamp}.png`}
                        className="inline-flex items-center gap-1 font-semibold text-emerald-400 hover:underline"
                      >
                        <Download className="h-3 w-3" />
                        PNG
                      </a>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      <SecuritySasModal
        open={sasModalOpen}
        onClose={() => setSasModalOpen(false)}
        deviceName={sasInfo?.deviceName || telemetry?.deviceName || 'Smartphone Android'}
        sasCode={sasInfo?.sasCode || ''}
        sasSymbols={sasInfo?.sasSymbols || ''}
        localFingerprint={stats?.localFingerprint}
        remoteFingerprint={stats?.remoteFingerprint}
      />
    </div>
  );
};
