import React, { useEffect, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  Battery,
  BatteryCharging,
  Camera,
  CameraOff,
  Download,
  FlipHorizontal,
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
  const [autoWakeOnConnect, setAutoWakeOnConnect] = useState(true);

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

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const videoContainerRef = useRef<HTMLDivElement | null>(null);
  const controllerRef = useRef<SentinelWebRtcController | null>(null);
  const autoWakeRef = useRef(autoWakeOnConnect);
  const hasAutoWokenSessionRef = useRef(false);

  useEffect(() => {
    autoWakeRef.current = autoWakeOnConnect;
  }, [autoWakeOnConnect]);

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

        // Automatically wake the camera right upon initial connection if enabled
        if (autoWakeRef.current && !hasAutoWokenSessionRef.current) {
          hasAutoWokenSessionRef.current = true;
          void ctrl.startCameraStream('front', false, 'hd');
        }
      },
      onAuthError: (err) => {
        setAuthError(err);
      },
      onRemoteStream: (stream) => {
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          if (stream) {
            void videoRef.current.play().catch(() => {});
          }
        }
      },
      onStatsUpdate: (s) => setStats(s),
      onLog: () => {},
    });

    controllerRef.current = ctrl;
    return () => {
      ctrl.disconnect();
    };
  }, [onBrokerHealthChange]);

  const fullCode = codeChars.join('');

  const handleConnect = async (targetCode: string) => {
    const normalized = normalizePairingCode(targetCode);
    if (normalized.length !== 6) return;

    setAuthError(null);
    hasAutoWokenSessionRef.current = false;
    setCodeChars(normalized.split(''));
    try {
      await controllerRef.current?.connectToPortal(normalized);
    } catch {
      // Handled by state callbacks
    }
  };

  const handleCharChange = (index: number, val: string) => {
    const clean = val.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
    if (!clean) {
      const next = [...codeChars];
      next[index] = '';
      setCodeChars(next);
      return;
    }

    if (clean.length > 1) {
      const chars = clean.slice(0, 6).split('');
      const next = [...codeChars];
      for (let i = 0; i < 6; i++) {
        if (index + i < 6 && chars[i]) {
          next[index + i] = chars[i];
        }
      }
      setCodeChars(next);
      const joined = next.join('');
      if (joined.length === 6) {
        void handleConnect(joined);
      } else {
        inputRefs.current[Math.min(5, index + chars.length)]?.focus();
      }
      return;
    }

    const next = [...codeChars];
    next[index] = clean;
    setCodeChars(next);
    if (index < 5) {
      inputRefs.current[index + 1]?.focus();
    }
    const joined = next.join('');
    if (joined.length === 6) {
      void handleConnect(joined);
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

  const handleDisconnect = () => {
    hasAutoWokenSessionRef.current = false;
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
    await controllerRef.current?.startCameraStream(cameraFacing, remoteMicEnabled, quality);
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

  const isSessionOpen = Boolean(sasInfo) || portalState === 'standby' || portalState === 'streaming';
  const isStreaming = portalState === 'streaming';

  /* ==================== SCREEN 1: SIMPLE 6-CHAR CODE ENTRY ==================== */
  if (!isSessionOpen) {
    return (
      <div className="mx-auto flex min-h-[78vh] max-w-lg flex-col justify-center px-4 py-8">
        <div className="rounded-3xl border border-slate-800/90 bg-obsidian-900/90 p-6 shadow-2xl backdrop-blur sm:p-8">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/10 shadow-lg shadow-emerald-500/10">
            <Smartphone className="h-7 w-7 text-emerald-400" />
          </div>

          <h1 className="mt-4 text-center text-2xl font-extrabold tracking-tight text-white">
            Connecter un Smartphone
          </h1>
          <p className="mt-1.5 text-center text-xs text-slate-400">
            Tapez le code à 6 caractères affiché sur l’application Android SentinelCam : la
            connexion démarre automatiquement.
          </p>

          {/* 6-character Code Slots */}
          <div className="mt-6 grid grid-cols-6 gap-2 sm:gap-2.5" onPaste={handlePaste}>
            {codeChars.map((ch, idx) => (
              <input
                key={idx}
                ref={(el) => {
                  inputRefs.current[idx] = el;
                }}
                type="text"
                inputMode="text"
                autoComplete="off"
                autoCapitalize="characters"
                maxLength={6}
                value={ch}
                onChange={(e) => handleCharChange(idx, e.target.value)}
                onKeyDown={(e) => handleKeyDown(idx, e)}
                className="h-14 w-full rounded-2xl border border-slate-700 bg-obsidian-950 text-center font-mono text-2xl font-extrabold uppercase text-emerald-300 shadow-inner transition focus:border-emerald-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/30"
                placeholder="•"
              />
            ))}
          </div>

          {/* Auto-wake checkbox */}
          <label className="mt-4 flex cursor-pointer items-center justify-center gap-2 text-xs text-slate-300">
            <input
              type="checkbox"
              checked={autoWakeOnConnect}
              onChange={(e) => setAutoWakeOnConnect(e.target.checked)}
              className="h-4 w-4 rounded border-slate-700 bg-obsidian-950 text-emerald-500 focus:ring-emerald-500"
            />
            <span>Allumer la caméra automatiquement dès la connexion</span>
          </label>

          {/* Primary Connect / Ping Button */}
          <button
            type="button"
            disabled={fullCode.length !== 6}
            onClick={() => void handleConnect(fullCode)}
            className="mt-5 flex w-full items-center justify-center gap-2.5 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-400 px-6 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/20 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {portalState === 'connecting' ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" />
                <span>Ping du smartphone en cours ({fullCode})...</span>
              </>
            ) : (
              <>
                <Zap className="h-4 w-4" />
                <span>Se connecter maintenant</span>
              </>
            )}
          </button>

          {portalState === 'connecting' && (
            <div className="mt-3 flex items-center justify-between rounded-xl border border-emerald-500/20 bg-emerald-500/5 px-3.5 py-2.5 text-xs text-emerald-300">
              <span>Réveil chiffré envoyé sur les 3 relais mondiaux...</span>
              <button
                type="button"
                onClick={handleDisconnect}
                className="font-semibold text-slate-400 underline hover:text-white"
              >
                Annuler
              </button>
            </div>
          )}

          {authError && (
            <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3.5 text-xs text-rose-200">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
              <div>
                <div className="font-bold">Connexion refusée</div>
                <div className="mt-0.5 text-rose-300/90">{authError.message}</div>
              </div>
            </div>
          )}

          {/* 1-Click Saved Smartphones */}
          {savedDevices.length > 0 && (
            <div className="mt-6 border-t border-slate-800/90 pt-5">
              <div className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Reconnexion rapide en 1 clic
              </div>
              <div className="mt-2.5 space-y-2">
                {savedDevices.map((dev) => (
                  <div
                    key={dev.pairingCode}
                    onClick={() => void handleConnect(dev.pairingCode)}
                    className="group flex cursor-pointer items-center justify-between rounded-2xl border border-slate-800 bg-obsidian-950/80 px-4 py-3 transition hover:border-emerald-500/50 hover:bg-obsidian-950"
                  >
                    <div className="flex items-center gap-3">
                      <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-500/10 font-mono text-xs font-bold text-emerald-400">
                        {dev.pairingCode.slice(0, 2)}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-bold text-white">{dev.deviceName}</span>
                          <span className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[11px] font-bold text-emerald-300">
                            {dev.pairingCode}
                          </span>
                        </div>
                        <div className="text-[11px] text-slate-500">
                          Cliquer pour connecter immédiatement • {dev.sasSymbols}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={(e) => handleForgetDevice(dev.pairingCode, e)}
                      className="rounded-lg p-1.5 text-slate-500 transition hover:bg-rose-500/15 hover:text-rose-400"
                      title="Supprimer cet appareil mémorisé"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    );
  }

  /* ==================== SCREEN 2: CONNECTED LIVE CAMERA STATION ==================== */
  return (
    <div className="mx-auto max-w-5xl space-y-4 px-4 py-5 sm:px-6">
      {/* Compact Connected Device Status Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-800/90 bg-obsidian-900/90 px-4 py-3 shadow-lg">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2">
            <span
              className={`h-2.5 w-2.5 rounded-full ${
                isStreaming
                  ? 'bg-rose-500 animate-ping'
                  : portalState === 'connecting'
                  ? 'bg-cyan-400 animate-pulse'
                  : 'bg-emerald-400'
              }`}
            />
            <span className="text-sm font-bold text-white">
              {telemetry?.deviceName || sasInfo?.deviceName || 'Smartphone Android'}
            </span>
            <span className="rounded-md bg-slate-800 px-2 py-0.5 font-mono text-xs font-bold text-emerald-300">
              {fullCode}
            </span>
          </div>

          {/* Battery Pill */}
          {telemetry && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-800 bg-obsidian-950 px-2.5 py-1 font-mono text-xs text-slate-200">
              {telemetry.isCharging ? (
                <BatteryCharging className="h-3.5 w-3.5 text-emerald-400" />
              ) : (
                <Battery className="h-3.5 w-3.5 text-slate-400" />
              )}
              {telemetry.batteryLevel}% • {telemetry.networkType}
            </span>
          )}

          {/* Live Latency & Bitrate Pill */}
          {isStreaming && stats && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2.5 py-1 font-mono text-xs text-cyan-300">
              <Activity className="h-3.5 w-3.5" />
              {stats.rttMs} ms • {stats.bitrateKbps} kbps
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          {sasInfo && (
            <button
              type="button"
              onClick={() => setSasModalOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 font-mono text-xs font-semibold text-emerald-300 transition hover:bg-emerald-500/20"
              title="Vérifier l’empreinte cryptographique de bout en bout"
            >
              <Lock className="h-3.5 w-3.5" />
              <span>E2EE {sasInfo.sasCode}</span>
            </button>
          )}

          <button
            type="button"
            onClick={handleDisconnect}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-1.5 text-xs font-semibold text-slate-300 transition hover:border-rose-500/40 hover:text-rose-300"
          >
            <Unplug className="h-3.5 w-3.5" />
            <span>Changer de code</span>
          </button>
        </div>
      </div>

      {/* LIVE VIDEO MONITOR */}
      <div
        ref={videoContainerRef}
        className="relative aspect-video w-full overflow-hidden rounded-3xl border border-slate-800 bg-obsidian-950 shadow-2xl"
      >
        {isStreaming && (
          <div className="absolute left-4 top-4 z-20 flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/40 bg-rose-950/85 px-3 py-1 font-mono text-xs font-bold text-rose-200 backdrop-blur">
              <span className="h-2 w-2 rounded-full bg-rose-500 animate-ping" />
              EN DIRECT • {cameraFacing === 'front' ? 'SELFIE' : 'ARRIÈRE'}
            </span>
          </div>
        )}

        <video
          ref={videoRef}
          autoPlay
          playsInline
          muted={localAudioMuted}
          className={`h-full w-full object-contain transition-opacity duration-300 ${
            isStreaming ? 'opacity-100' : 'opacity-0'
          } ${mirrorVideo ? 'scale-x-[-1]' : ''}`}
        />

        {!isStreaming && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-radial-glow p-6 text-center">
            {portalState === 'connecting' ? (
              <>
                <RefreshCw className="h-11 w-11 animate-spin text-emerald-400" />
                <h3 className="mt-4 text-lg font-bold text-white">
                  Ouverture du flux caméra en direct...
                </h3>
                <p className="mt-1 text-xs text-slate-400">
                  Connexion chiffrée directe avec le smartphone en arrière-plan...
                </p>
              </>
            ) : portalState === 'locked' ? (
              <>
                <AlertTriangle className="h-10 w-10 text-amber-400" />
                <h3 className="mt-3 text-base font-bold text-amber-300">
                  Temporisation de sécurité active
                </h3>
              </>
            ) : (
              <>
                <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/10">
                  <CameraOff className="h-8 w-8 text-emerald-400" />
                </div>
                <h3 className="mt-4 text-lg font-bold text-white">
                  Smartphone connecté • Caméra en veille
                </h3>
                <p className="mt-1 max-w-md text-xs text-slate-400">
                  Le capteur caméra est éteint pour économiser la batterie. Cliquez ci-dessous pour
                  afficher la vidéo en direct immédiatement.
                </p>
                <button
                  type="button"
                  onClick={() => void handleWakeCamera()}
                  className="mt-5 inline-flex items-center gap-2.5 rounded-2xl bg-emerald-500 px-7 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/25 transition hover:scale-[1.02] hover:bg-emerald-400"
                >
                  <Video className="h-5 w-5" />
                  <span>Allumer la caméra</span>
                </button>
              </>
            )}
          </div>
        )}
      </div>

      {/* SIMPLE ACTION DECK */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-800/90 bg-obsidian-900/90 p-4 shadow-xl">
        <div className="flex flex-wrap items-center gap-2.5">
          {!isStreaming ? (
            <button
              type="button"
              onClick={() => void handleWakeCamera()}
              className="inline-flex items-center gap-2 rounded-xl bg-emerald-500 px-5 py-2.5 text-sm font-extrabold text-obsidian-950 shadow-lg shadow-emerald-500/20 transition hover:bg-emerald-400"
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

          <button
            type="button"
            onClick={() => void handleToggleFacing()}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-950 px-3.5 py-2.5 text-xs font-semibold text-slate-200 transition hover:border-slate-600 hover:text-white"
          >
            <RefreshCw className="h-3.5 w-3.5 text-cyan-400" />
            <span>{cameraFacing === 'front' ? 'Caméra Selfie' : 'Caméra Arrière'}</span>
          </button>

          <button
            type="button"
            onClick={() => void handleToggleRemoteMic()}
            className={`inline-flex items-center gap-1.5 rounded-xl border px-3.5 py-2.5 text-xs font-semibold transition ${
              remoteMicEnabled
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                : 'border-slate-700 bg-obsidian-950 text-slate-300 hover:text-white'
            }`}
          >
            {remoteMicEnabled ? (
              <>
                <Mic className="h-3.5 w-3.5 text-emerald-400" />
                <span>Micro ON</span>
              </>
            ) : (
              <>
                <MicOff className="h-3.5 w-3.5 text-slate-400" />
                <span>Micro OFF</span>
              </>
            )}
          </button>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={!isStreaming}
            onClick={handleCaptureSnapshot}
            className="inline-flex items-center gap-1.5 rounded-xl border border-cyan-500/35 bg-cyan-500/10 px-3.5 py-2.5 text-xs font-semibold text-cyan-300 transition hover:bg-cyan-500/20 disabled:opacity-40"
          >
            <Camera className="h-3.5 w-3.5" />
            <span>Photo PNG</span>
          </button>

          <button
            type="button"
            onClick={() => setLocalAudioMuted((m) => !m)}
            className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-slate-800 bg-obsidian-950 text-slate-300 transition hover:text-white"
            title={localAudioMuted ? 'Activer le son' : 'Couper le son'}
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
              mirrorVideo ? 'border-emerald-500/40 text-emerald-300' : 'border-slate-800 text-slate-400'
            }`}
            title="Mode miroir"
          >
            <FlipHorizontal className="h-4 w-4" />
          </button>

          <button
            type="button"
            onClick={() => setQuality((q) => (q === 'hd' ? 'sd' : 'hd'))}
            className="rounded-xl border border-slate-800 bg-obsidian-950 px-2.5 py-2 font-mono text-xs font-bold text-slate-300"
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

      {/* Snapshots Strip */}
      {snapshots.length > 0 && (
        <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/90 p-4">
          <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-300">
            <ShieldCheck className="h-4 w-4 text-emerald-400" />
            <span>Photos capturées ({snapshots.length})</span>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
            {snapshots.map((snap) => (
              <div
                key={snap.id}
                className="overflow-hidden rounded-xl border border-slate-800 bg-obsidian-950"
              >
                <img src={snap.dataUrl} alt="Capture" className="aspect-video w-full object-cover" />
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
