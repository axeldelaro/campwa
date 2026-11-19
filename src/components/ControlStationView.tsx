import React, { useEffect, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  Battery,
  BatteryCharging,
  BellRing,
  BookUser,
  Camera,
  CameraOff,
  Check,
  Clock,
  Copy,
  Cpu,
  Download,
  ExternalLink,
  FlipHorizontal,
  HardDrive,
  Lock,
  Mail,
  MapPin,
  Maximize2,
  Mic,
  MicOff,
  Monitor,
  Phone,
  Power,
  RefreshCw,
  Search,
  Share2,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Trash2,
  Unplug,
  UserCheck,
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
  ActiveAccessGrant,
  AuthErrMsg,
  CameraFacingMode,
  DeviceTelemetry,
  MediaSourceMode,
  PairedDeviceRecord,
  PortalOperationalState,
  SharedContactItem,
  SharedDeviceInfoData,
  SharedLocationData,
  SnapshotItem,
  StreamQualityPreset,
  StreamRealtimeStats,
} from '../types/sentinel';
import { SecuritySasModal } from './SecuritySasModal';

interface ControlStationViewProps {
  onBrokerHealthChange: (connected: boolean, brokers: BrokerHealth[]) => void;
  initialCode?: string | null;
}

type ViewerTab = 'media' | 'contacts' | 'device';

function formatCountdown(expiresAt: number | null, nowMs: number): string | null {
  if (expiresAt === null) return null;
  const diff = expiresAt - nowMs;
  if (diff <= 0) return 'Expiré';
  const totalSec = Math.floor(diff / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (d > 0) return `${d}j ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m ${s.toString().padStart(2, '0')}s`;
}

export const ControlStationView: React.FC<ControlStationViewProps> = ({
  onBrokerHealthChange,
  initialCode,
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
  const [streamNotice, setStreamNotice] = useState<string | null>(null);
  const [autoWakeOnConnect, setAutoWakeOnConnect] = useState(true);
  const [pushSentFeedback, setPushSentFeedback] = useState<string | null>(null);
  const [accessGrant, setAccessGrant] = useState<ActiveAccessGrant | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [viewerTab, setViewerTab] = useState<ViewerTab>('media');

  // Shared remote data (Contacts, GPS, Device Info)
  const [contacts, setContacts] = useState<SharedContactItem[]>([]);
  const [contactsTotal, setContactsTotal] = useState(0);
  const [contactsQuery, setContactsQuery] = useState('');
  const [contactsLoading, setContactsLoading] = useState(false);
  const [contactsError, setContactsError] = useState<string | null>(null);

  const [locationData, setLocationData] = useState<SharedLocationData | null>(null);
  const [locationLoading, setLocationLoading] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);

  const [deviceInfo, setDeviceInfo] = useState<SharedDeviceInfoData | null>(null);
  const [deviceInfoLoading, setDeviceInfoLoading] = useState(false);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const [sasInfo, setSasInfo] = useState<{
    deviceName: string;
    sasCode: string;
    sasSymbols: string;
  } | null>(null);
  const [sasModalOpen, setSasModalOpen] = useState(false);

  const [mediaMode, setMediaMode] = useState<MediaSourceMode>('camera');
  const [cameraFacing, setCameraFacing] = useState<CameraFacingMode>('front');
  const [remoteMicEnabled, setRemoteMicEnabled] = useState(false);
  const [localAudioMuted, setLocalAudioMuted] = useState(false);
  const [quality, setQuality] = useState<StreamQualityPreset>('hd');
  const [mirrorVideo, setMirrorVideo] = useState(true);
  const [snapshots, setSnapshots] = useState<SnapshotItem[]>([]);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const videoContainerRef = useRef<HTMLDivElement | null>(null);
  const controllerRef = useRef<SentinelWebRtcController | null>(null);
  const autoConnectedInitialRef = useRef(false);

  useEffect(() => {
    const t = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

  useEffect(() => {
    const ctrl = new SentinelWebRtcController({
      onStateChange: (st) => setPortalState(st),
      onBrokerHealth: (connected, brokers) => onBrokerHealthChange(connected, brokers),
      onTelemetry: (t) => {
        setTelemetry(t);
        setCameraFacing(t.cameraFacing);
        if (t.mediaMode) {
          setMediaMode(t.mediaMode);
          if (t.mediaMode === 'screen') setMirrorVideo(false);
        }
        setRemoteMicEnabled(t.micEnabled);
        if (t.accessRole) {
          setAccessGrant((prev) => ({
            role: t.accessRole ?? prev?.role ?? 'owner',
            name: t.accessName ?? prev?.name ?? 'Propriétaire',
            expiresAt: t.accessExpiresAt !== undefined ? t.accessExpiresAt : (prev?.expiresAt ?? null),
            allowAudio: t.allowAudio ?? prev?.allowAudio ?? true,
            allowSwitchCamera: t.allowSwitchCamera ?? prev?.allowSwitchCamera ?? true,
            allowScreenShare: t.allowScreenShare ?? prev?.allowScreenShare ?? false,
            allowContacts: t.allowContacts ?? prev?.allowContacts ?? false,
            allowLocation: t.allowLocation ?? prev?.allowLocation ?? false,
            allowDeviceInfo: t.allowDeviceInfo ?? prev?.allowDeviceInfo ?? true,
          }));
        }
      },
      onAuthSuccess: (info) => {
        setAuthError(null);
        setStreamNotice(null);
        setAccessGrant(info.accessGrant);
        setSasInfo({
          deviceName: info.deviceName,
          sasCode: info.sasCode,
          sasSymbols: info.sasSymbols,
        });
        setSavedDevices(listPairedDevices());
      },
      onAuthError: (err) => {
        setAuthError(err);
        if (err.reason === 'ACCESS_EXPIRED' || err.reason === 'ACCESS_REVOKED') {
          setSasInfo(null);
          setAccessGrant(null);
          setTelemetry(null);
          setStats(null);
        }
      },
      onRemoteStream: (stream) => {
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          if (stream) {
            setStreamNotice(null);
            void videoRef.current.play().catch(() => {});
          }
        }
      },
      onStatsUpdate: (s) => setStats(s),
      onContactsData: (data) => {
        setContactsLoading(false);
        if (data.error) {
          setContactsError(data.error);
          setContacts([]);
        } else {
          setContactsError(null);
          setContacts(data.contacts || []);
          setContactsTotal(data.totalCount ?? data.contacts?.length ?? 0);
        }
      },
      onLocationData: (data) => {
        setLocationLoading(false);
        if (data.error || !data.location) {
          setLocationError(data.error || 'Position indisponible.');
        } else {
          setLocationError(null);
          setLocationData(data.location);
        }
      },
      onDeviceInfoData: (data) => {
        setDeviceInfoLoading(false);
        if (data.info) {
          setDeviceInfo(data.info);
        }
      },
      onStreamError: (msg) => {
        setStreamNotice(msg);
      },
    });

    controllerRef.current = ctrl;
    return () => {
      ctrl.disconnect();
    };
  }, [onBrokerHealthChange]);

  useEffect(() => {
    if (!initialCode || autoConnectedInitialRef.current) return;
    const clean = normalizePairingCode(initialCode);
    if (clean.length === 6) {
      autoConnectedInitialRef.current = true;
      setCodeChars(clean.split(''));
      void controllerRef.current?.connectToPortal(clean, true);
    }
  }, [initialCode]);

  const fullCode = codeChars.join('');

  const handleConnect = async (targetCode: string) => {
    const normalized = normalizePairingCode(targetCode);
    if (normalized.length !== 6) return;

    setAuthError(null);
    setStreamNotice(null);
    setPushSentFeedback(null);
    setViewerTab('media');
    setContacts([]);
    setLocationData(null);
    setDeviceInfo(null);
    setCodeChars(normalized.split(''));
    try {
      await controllerRef.current?.connectToPortal(normalized, autoWakeOnConnect);
    } catch {
      // Handled via state callbacks
    }
  };

  const handleSendPushInvite = async () => {
    if (fullCode.length !== 6) return;
    setPushSentFeedback('Envoi du Push de réveil...');
    const pushed = await controllerRef.current?.sendServerlessPushInvitation(fullCode);

    const inviteUrl = `${window.location.origin}${window.location.pathname}?invite=${fullCode}`;
    if (navigator.share) {
      try {
        await navigator.share({
          title: 'Ouvrir SentinelCam',
          text: `Appuyez pour activer le portail SentinelCam (${fullCode}) :`,
          url: inviteUrl,
        });
        setPushSentFeedback('Push envoyé + Invitation partagée !');
        return;
      } catch {
        // User cancelled share sheet
      }
    }

    try {
      await navigator.clipboard.writeText(inviteUrl);
      setPushSentFeedback(
        pushed
          ? 'Push envoyé au téléphone + Lien d’invitation copié !'
          : 'Lien d’ouverture directe copié !'
      );
    } catch {
      setPushSentFeedback('Signal Push envoyé !');
    }

    window.setTimeout(() => setPushSentFeedback(null), 4000);
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
    controllerRef.current?.disconnect();
    setTelemetry(null);
    setStats(null);
    setSasInfo(null);
    setAccessGrant(null);
    setStreamNotice(null);
  };

  const handleForgetDevice = (code: string, e: React.MouseEvent) => {
    e.stopPropagation();
    removePairedDevice(code);
    setSavedDevices(listPairedDevices());
  };

  const handleWakeMedia = async (mode: MediaSourceMode) => {
    setStreamNotice(null);
    setMediaMode(mode);
    setMirrorVideo(mode === 'camera' && cameraFacing === 'front');
    const allowedAudio = accessGrant ? accessGrant.allowAudio : true;
    const allowedSwitch = accessGrant ? accessGrant.allowSwitchCamera : true;
    await controllerRef.current?.startCameraStream(
      allowedSwitch ? cameraFacing : 'front',
      allowedAudio ? remoteMicEnabled : false,
      quality,
      mode
    );
  };

  const handleSleepCamera = async () => {
    await controllerRef.current?.stopCameraStream();
  };

  const handleToggleFacing = async () => {
    if (accessGrant && !accessGrant.allowSwitchCamera) return;
    const nextFacing: CameraFacingMode = cameraFacing === 'front' ? 'back' : 'front';
    setCameraFacing(nextFacing);
    setMirrorVideo(nextFacing === 'front');
    if (portalState === 'streaming' && mediaMode === 'camera') {
      await controllerRef.current?.switchCamera(nextFacing);
    }
  };

  const handleToggleRemoteMic = async () => {
    if (accessGrant && !accessGrant.allowAudio) return;
    const next = !remoteMicEnabled;
    setRemoteMicEnabled(next);
    if (portalState === 'streaming') {
      await controllerRef.current?.toggleRemoteMicrophone(next);
    }
  };

  const handleFetchContacts = async (queryStr = contactsQuery) => {
    setContactsLoading(true);
    setContactsError(null);
    await controllerRef.current?.requestContacts(queryStr);
  };

  const handleFetchLocation = async () => {
    setLocationLoading(true);
    setLocationError(null);
    await controllerRef.current?.requestLocation();
  };

  const handleFetchDeviceInfo = async () => {
    setDeviceInfoLoading(true);
    await controllerRef.current?.requestDeviceInfo();
  };

  const handleOpenContactsTab = () => {
    setViewerTab('contacts');
    if (contacts.length === 0 && !contactsLoading) {
      void handleFetchContacts('');
    }
  };

  const handleOpenDeviceTab = () => {
    setViewerTab('device');
    if (!deviceInfo && accessGrant?.allowDeviceInfo) {
      void handleFetchDeviceInfo();
    }
    if (!locationData && accessGrant?.allowLocation) {
      void handleFetchLocation();
    }
  };

  const handleCopySmall = async (value: string, id: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedKey(id);
      window.setTimeout(() => setCopiedKey(null), 1500);
    } catch {
      // Ignore
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
  const remainingLabel = accessGrant ? formatCountdown(accessGrant.expiresAt, nowMs) : null;
  const canUseAudio = accessGrant ? accessGrant.allowAudio : true;
  const canSwitchCam = accessGrant ? accessGrant.allowSwitchCamera : true;
  const canViewScreen = accessGrant ? accessGrant.allowScreenShare : false;
  const canViewContacts = accessGrant ? accessGrant.allowContacts : false;
  const canViewLocation = accessGrant ? accessGrant.allowLocation : false;
  const canViewDeviceInfo = accessGrant ? accessGrant.allowDeviceInfo : true;

  /* ==================== SCREEN 1: INSTANT 6-CHAR CODE ENTRY ==================== */
  if (!isSessionOpen) {
    return (
      <div className="mx-auto flex min-h-[78vh] max-w-lg flex-col justify-center px-4 py-8">
        <div className="rounded-3xl border border-slate-800/90 bg-obsidian-900/90 p-6 shadow-2xl backdrop-blur sm:p-8">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/10 shadow-lg shadow-emerald-500/10">
            <Smartphone className="h-7 w-7 text-emerald-400" />
          </div>

          <h1 className="mt-4 text-center text-2xl font-extrabold tracking-tight text-white">
            Connexion Instantanée
          </h1>
          <p className="mt-1.5 text-center text-xs text-slate-400">
            Tapez un <strong>code principal</strong>, un <strong>code fixe temporaire</strong> ou
            votre <strong>code profil ami</strong> (6 caractères) pour accéder aux éléments autorisés
            par le propriétaire.
          </p>

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

          <label className="mt-4 flex cursor-pointer items-center justify-center gap-2 text-xs text-slate-300">
            <input
              type="checkbox"
              checked={autoWakeOnConnect}
              onChange={(e) => setAutoWakeOnConnect(e.target.checked)}
              className="h-4 w-4 rounded border-slate-700 bg-obsidian-950 text-emerald-500 focus:ring-emerald-500"
            />
            <span>Ouvrir la vidéo automatiquement dès la connexion</span>
          </label>

          <button
            type="button"
            disabled={fullCode.length !== 6}
            onClick={() => void handleConnect(fullCode)}
            className="mt-5 flex w-full items-center justify-center gap-2.5 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-400 px-6 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/20 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {portalState === 'connecting' ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" />
                <span>Connexion instantanée ({fullCode})...</span>
              </>
            ) : (
              <>
                <Zap className="h-4 w-4" />
                <span>Se connecter maintenant</span>
              </>
            )}
          </button>

          {fullCode.length === 6 && (
            <div className="mt-3 space-y-2">
              <button
                type="button"
                onClick={() => void handleSendPushInvite()}
                className="flex w-full items-center justify-center gap-2 rounded-2xl border border-cyan-500/35 bg-cyan-500/10 px-4 py-3 text-xs font-bold text-cyan-300 transition hover:bg-cyan-500/20"
              >
                <BellRing className="h-4 w-4" />
                <span>Envoyer un Push / Lien d’invitation (si appli fermée)</span>
                <Share2 className="h-3.5 w-3.5 opacity-75" />
              </button>

              {pushSentFeedback && (
                <div className="flex items-center justify-center gap-1.5 rounded-xl bg-emerald-500/10 px-3 py-2 text-xs font-semibold text-emerald-300">
                  <Check className="h-3.5 w-3.5" />
                  <span>{pushSentFeedback}</span>
                </div>
              )}

              {portalState === 'connecting' && (
                <div className="text-center">
                  <button
                    type="button"
                    onClick={handleDisconnect}
                    className="text-[11px] text-slate-400 underline hover:text-white"
                  >
                    Annuler la tentative en cours
                  </button>
                </div>
              )}
            </div>
          )}

          {authError && (
            <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3.5 text-xs text-rose-200">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
              <div>
                <div className="font-bold">
                  {authError.reason === 'ACCESS_EXPIRED'
                    ? 'Pass temporaire expiré'
                    : authError.reason === 'ACCESS_REVOKED'
                    ? 'Accès suspendu ou révoqué'
                    : 'Connexion refusée'}
                </div>
                <div className="mt-0.5 text-rose-300/90">{authError.message}</div>
              </div>
            </div>
          )}

          {savedDevices.length > 0 && (
            <div className="mt-6 border-t border-slate-800/90 pt-5">
              <div className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Accès mémorisés (Connexion en 1 clic)
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
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="text-sm font-bold text-white">{dev.deviceName}</span>
                          <span className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[11px] font-bold text-emerald-300">
                            {dev.pairingCode}
                          </span>
                          {dev.accessRole && dev.accessRole !== 'owner' && (
                            <span className="rounded-full bg-cyan-500/15 px-2 py-0.5 text-[10px] font-bold text-cyan-300">
                              {dev.accessRole === 'friend' ? 'Ami' : 'Pass'} : {dev.accessName}
                            </span>
                          )}
                        </div>
                        <div className="text-[11px] text-slate-500">
                          Connexion instantanée • {dev.sasSymbols}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={(e) => handleForgetDevice(dev.pairingCode, e)}
                      className="rounded-lg p-1.5 text-slate-500 transition hover:bg-rose-500/15 hover:text-rose-400"
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

  /* ==================== SCREEN 2: CONNECTED LIVE CONTROL STATION ==================== */
  return (
    <div className="mx-auto max-w-5xl space-y-4 px-4 py-5 sm:px-6">
      {/* Top Status & Permissions Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-800/90 bg-obsidian-900/90 px-4 py-3 shadow-lg">
        <div className="flex flex-wrap items-center gap-2.5">
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

          {accessGrant && accessGrant.role !== 'owner' && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-cyan-500/35 bg-cyan-500/10 px-2.5 py-1 text-xs font-bold text-cyan-300">
              {accessGrant.role === 'friend' ? (
                <UserCheck className="h-3.5 w-3.5" />
              ) : (
                <Clock className="h-3.5 w-3.5" />
              )}
              <span>
                {accessGrant.role === 'friend' ? 'Ami' : 'Pass'} : {accessGrant.name}
                {remainingLabel ? ` • ${remainingLabel}` : ''}
              </span>
            </span>
          )}

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
            <span>Déconnecter</span>
          </button>
        </div>
      </div>

      {/* Viewer Navigation Tabs: Caméra & Écran | Contacts | GPS & Appareil */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setViewerTab('media')}
          className={`inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-xs font-bold transition ${
            viewerTab === 'media'
              ? 'border-emerald-400 bg-emerald-500 text-obsidian-950'
              : 'border-slate-800 bg-obsidian-900 text-slate-300 hover:text-white'
          }`}
        >
          <Video className="h-4 w-4" />
          <span>Caméra & Écran</span>
        </button>

        <button
          type="button"
          disabled={!canViewContacts}
          onClick={handleOpenContactsTab}
          title={!canViewContacts ? 'Le propriétaire n’a pas activé le partage des contacts' : undefined}
          className={`inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-xs font-bold transition disabled:cursor-not-allowed disabled:opacity-40 ${
            viewerTab === 'contacts'
              ? 'border-amber-400 bg-amber-400 text-obsidian-950'
              : 'border-slate-800 bg-obsidian-900 text-slate-300 hover:text-white'
          }`}
        >
          <BookUser className="h-4 w-4" />
          <span>{canViewContacts ? 'Contacts partagés' : 'Contacts (Privé)'}</span>
        </button>

        <button
          type="button"
          disabled={!canViewLocation && !canViewDeviceInfo}
          onClick={handleOpenDeviceTab}
          className={`inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-xs font-bold transition disabled:cursor-not-allowed disabled:opacity-40 ${
            viewerTab === 'device'
              ? 'border-cyan-400 bg-cyan-400 text-obsidian-950'
              : 'border-slate-800 bg-obsidian-900 text-slate-300 hover:text-white'
          }`}
        >
          <MapPin className="h-4 w-4" />
          <span>{canViewLocation ? 'GPS & Infos Appareil' : 'Infos Appareil'}</span>
        </button>
      </div>

      {/* ==================== VIEWER TAB 1: CAMERA & SCREEN SHARE ==================== */}
      {viewerTab === 'media' && (
        <>
          {streamNotice && (
            <div className="flex items-center gap-2.5 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-xs text-amber-200">
              <AlertTriangle className="h-4 w-4 shrink-0 text-amber-400" />
              <span>{streamNotice}</span>
            </div>
          )}

          <div
            ref={videoContainerRef}
            className="relative aspect-video w-full overflow-hidden rounded-3xl border border-slate-800 bg-obsidian-950 shadow-2xl"
          >
            {isStreaming && (
              <div className="absolute left-4 top-4 z-20 flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/40 bg-rose-950/85 px-3 py-1 font-mono text-xs font-bold text-rose-200 backdrop-blur">
                  <span className="h-2 w-2 rounded-full bg-rose-500 animate-ping" />
                  {mediaMode === 'screen'
                    ? 'ÉCRAN EN DIRECT'
                    : `EN DIRECT • ${cameraFacing === 'front' ? 'SELFIE' : 'ARRIÈRE'}`}
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
              } ${mirrorVideo && mediaMode === 'camera' ? 'scale-x-[-1]' : ''}`}
            />

            {!isStreaming && (
              <div className="absolute inset-0 flex flex-col items-center justify-center bg-radial-glow p-6 text-center">
                {portalState === 'connecting' ? (
                  <>
                    <RefreshCw className="h-11 w-11 animate-spin text-emerald-400" />
                    <h3 className="mt-4 text-lg font-bold text-white">
                      {mediaMode === 'screen'
                        ? 'Ouverture du partage d’écran...'
                        : 'Démarrage instantané de la vidéo...'}
                    </h3>
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
                      Mode Éco-Veille Actif (0% conso caméra)
                    </h3>
                    <p className="mt-1 max-w-md text-xs text-slate-400">
                      Choisissez d’allumer la caméra ou de visionner l’écran si le propriétaire l’a
                      autorisé.
                    </p>
                    <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
                      <button
                        type="button"
                        onClick={() => void handleWakeMedia('camera')}
                        className="inline-flex items-center gap-2 rounded-2xl bg-emerald-500 px-6 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/25 transition hover:scale-[1.02] hover:bg-emerald-400"
                      >
                        <Video className="h-5 w-5" />
                        <span>Allumer la caméra</span>
                      </button>

                      {canViewScreen && (
                        <button
                          type="button"
                          onClick={() => void handleWakeMedia('screen')}
                          className="inline-flex items-center gap-2 rounded-2xl border border-violet-400/50 bg-violet-500/20 px-6 py-3.5 text-sm font-extrabold text-violet-200 shadow-xl transition hover:scale-[1.02] hover:bg-violet-500/30"
                        >
                          <Monitor className="h-5 w-5" />
                          <span>Voir l’écran</span>
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            )}
          </div>

          {/* Media Action Deck */}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-800/90 bg-obsidian-900/90 p-4 shadow-xl">
            <div className="flex flex-wrap items-center gap-2.5">
              {!isStreaming ? (
                <button
                  type="button"
                  onClick={() => void handleWakeMedia('camera')}
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
                  <span>Couper le flux (Éco-Veille)</span>
                </button>
              )}

              {/* Switch between Camera & Screen Share */}
              <button
                type="button"
                disabled={!canViewScreen}
                onClick={() =>
                  void handleWakeMedia(mediaMode === 'screen' && isStreaming ? 'camera' : 'screen')
                }
                title={
                  !canViewScreen
                    ? 'Partage d’écran non autorisé par le propriétaire sur son téléphone'
                    : undefined
                }
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3.5 py-2.5 text-xs font-bold transition disabled:cursor-not-allowed disabled:opacity-40 ${
                  mediaMode === 'screen' && isStreaming
                    ? 'border-violet-400 bg-violet-500/25 text-violet-200'
                    : 'border-violet-500/35 bg-violet-500/10 text-violet-300 hover:bg-violet-500/20'
                }`}
              >
                <Monitor className="h-3.5 w-3.5" />
                <span>
                  {mediaMode === 'screen' && isStreaming
                    ? 'Revenir Caméra'
                    : canViewScreen
                    ? 'Partage d’Écran'
                    : 'Écran (Privé)'}
                </span>
              </button>

              {mediaMode === 'camera' && (
                <button
                  type="button"
                  disabled={!canSwitchCam}
                  onClick={() => void handleToggleFacing()}
                  title={!canSwitchCam ? 'Changement de caméra restreint' : undefined}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-950 px-3.5 py-2.5 text-xs font-semibold text-slate-200 transition hover:border-slate-600 hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <RefreshCw className="h-3.5 w-3.5 text-cyan-400" />
                  <span>{cameraFacing === 'front' ? 'Caméra Selfie' : 'Caméra Arrière'}</span>
                </button>
              )}

              <button
                type="button"
                disabled={!canUseAudio}
                onClick={() => void handleToggleRemoteMic()}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3.5 py-2.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-40 ${
                  remoteMicEnabled && canUseAudio
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                    : 'border-slate-700 bg-obsidian-950 text-slate-300 hover:text-white'
                }`}
              >
                {remoteMicEnabled && canUseAudio ? (
                  <>
                    <Mic className="h-3.5 w-3.5 text-emerald-400" />
                    <span>Micro ON</span>
                  </>
                ) : (
                  <>
                    <MicOff className="h-3.5 w-3.5 text-slate-400" />
                    <span>{canUseAudio ? 'Micro OFF' : 'Micro restreint'}</span>
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
              >
                {localAudioMuted ? (
                  <VolumeX className="h-4 w-4 text-rose-400" />
                ) : (
                  <Volume2 className="h-4 w-4 text-emerald-400" />
                )}
              </button>

              {mediaMode === 'camera' && (
                <button
                  type="button"
                  onClick={() => setMirrorVideo((m) => !m)}
                  className={`inline-flex h-9 w-9 items-center justify-center rounded-xl border bg-obsidian-950 transition ${
                    mirrorVideo ? 'border-emerald-500/40 text-emerald-300' : 'border-slate-800 text-slate-400'
                  }`}
                >
                  <FlipHorizontal className="h-4 w-4" />
                </button>
              )}

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
              >
                <Maximize2 className="h-4 w-4" />
              </button>
            </div>
          </div>

          {snapshots.length > 0 && (
            <div className="rounded-2xl border border-slate-800/90 bg-obsidian-900/90 p-4">
              <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-300">
                <ShieldCheck className="h-4 w-4 text-emerald-400" />
                <span>Captures locales ({snapshots.length})</span>
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
        </>
      )}

      {/* ==================== VIEWER TAB 2: SHARED CONTACTS ==================== */}
      {viewerTab === 'contacts' && (
        <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-5 shadow-2xl">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <BookUser className="h-5 w-5 text-amber-400" />
              <div>
                <h2 className="text-sm font-extrabold text-white">
                  Répertoire Contacts Partagé (E2EE)
                </h2>
                <p className="text-xs text-slate-400">
                  {contactsTotal > 0
                    ? `${contacts.length} affiché(s) sur ${contactsTotal} contact(s)`
                    : 'Recherchez un nom ou un numéro sur le téléphone autorisé.'}
                </p>
              </div>
            </div>

            <button
              type="button"
              disabled={contactsLoading}
              onClick={() => void handleFetchContacts(contactsQuery)}
              className="inline-flex items-center gap-1.5 rounded-xl border border-amber-500/40 bg-amber-500/15 px-3.5 py-2 text-xs font-bold text-amber-200 hover:bg-amber-500/25"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${contactsLoading ? 'animate-spin' : ''}`} />
              <span>Actualiser</span>
            </button>
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              void handleFetchContacts(contactsQuery);
            }}
            className="mt-4 flex gap-2"
          >
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
              <input
                type="text"
                value={contactsQuery}
                onChange={(e) => setContactsQuery(e.target.value)}
                placeholder="Rechercher un nom ou un numéro..."
                className="w-full rounded-xl border border-slate-700 bg-obsidian-950 py-2.5 pl-9 pr-3 text-xs text-white placeholder-slate-500 focus:border-amber-400 focus:outline-none"
              />
            </div>
            <button
              type="submit"
              disabled={contactsLoading}
              className="rounded-xl bg-amber-400 px-4 py-2.5 text-xs font-extrabold text-obsidian-950 hover:bg-amber-300"
            >
              Chercher
            </button>
          </form>

          {contactsError && (
            <div className="mt-4 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-xs text-rose-200">
              {contactsError}
            </div>
          )}

          {contactsLoading ? (
            <div className="flex items-center justify-center py-12 text-xs text-slate-400">
              <RefreshCw className="mr-2 h-5 w-5 animate-spin text-amber-400" />
              Récupération chiffrée du répertoire...
            </div>
          ) : contacts.length === 0 && !contactsError ? (
            <div className="py-10 text-center text-xs text-slate-500">
              Aucun contact trouvé pour cette recherche.
            </div>
          ) : (
            <div className="mt-4 grid max-h-[55vh] grid-cols-1 gap-2.5 overflow-y-auto pr-1 sm:grid-cols-2">
              {contacts.map((c) => (
                <div
                  key={c.id}
                  className="rounded-2xl border border-slate-800 bg-obsidian-950/80 p-3.5"
                >
                  <div className="font-bold text-white">{c.name}</div>
                  <div className="mt-2 space-y-1">
                    {c.phones.map((ph, idx) => (
                      <div
                        key={`${c.id}-p-${idx}`}
                        className="flex items-center justify-between gap-2 text-xs"
                      >
                        <a
                          href={`tel:${ph}`}
                          className="inline-flex items-center gap-1.5 font-mono text-emerald-300 hover:underline"
                        >
                          <Phone className="h-3 w-3" />
                          <span>{ph}</span>
                        </a>
                        <button
                          type="button"
                          onClick={() => void handleCopySmall(ph, `${c.id}-p-${idx}`)}
                          className="rounded p-1 text-slate-500 hover:text-white"
                          title="Copier le numéro"
                        >
                          {copiedKey === `${c.id}-p-${idx}` ? (
                            <Check className="h-3 w-3 text-emerald-400" />
                          ) : (
                            <Copy className="h-3 w-3" />
                          )}
                        </button>
                      </div>
                    ))}
                    {c.emails.map((em, idx) => (
                      <div
                        key={`${c.id}-e-${idx}`}
                        className="flex items-center gap-1.5 truncate text-[11px] text-cyan-300"
                      >
                        <Mail className="h-3 w-3 shrink-0" />
                        <span className="truncate">{em}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ==================== VIEWER TAB 3: GPS LOCATION & DEVICE INFO ==================== */}
      {viewerTab === 'device' && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {/* GPS Location Card */}
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-5 shadow-2xl">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <MapPin className="h-5 w-5 text-rose-400" />
                <h2 className="text-sm font-extrabold text-white">Localisation GPS</h2>
              </div>
              {canViewLocation && (
                <button
                  type="button"
                  disabled={locationLoading}
                  onClick={() => void handleFetchLocation()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-rose-500/35 bg-rose-500/15 px-3 py-1.5 text-xs font-bold text-rose-200 hover:bg-rose-500/25"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${locationLoading ? 'animate-spin' : ''}`} />
                  <span>Localiser</span>
                </button>
              )}
            </div>

            {!canViewLocation ? (
              <p className="mt-4 rounded-xl border border-slate-800 bg-obsidian-950 p-4 text-xs text-slate-400">
                Le propriétaire n’a pas autorisé le partage de la localisation GPS pour cet accès.
              </p>
            ) : locationError ? (
              <p className="mt-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-xs text-rose-200">
                {locationError}
              </p>
            ) : locationData ? (
              <div className="mt-4 space-y-3">
                <div className="overflow-hidden rounded-2xl border border-slate-800 bg-obsidian-950">
                  <iframe
                    title="Carte GPS SentinelCam"
                    src={`https://www.openstreetmap.org/export/embed.html?bbox=${
                      locationData.longitude - 0.008
                    }%2C${locationData.latitude - 0.006}%2C${
                      locationData.longitude + 0.008
                    }%2C${locationData.latitude + 0.006}&layer=mapnik&marker=${
                      locationData.latitude
                    }%2C${locationData.longitude}`}
                    className="h-52 w-full border-0"
                  />
                </div>
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-slate-800 bg-obsidian-950 p-3 text-xs">
                  <div className="font-mono text-slate-200">
                    {locationData.latitude.toFixed(5)}, {locationData.longitude.toFixed(5)}
                    <div className="text-[10px] text-slate-500">
                      Précision ±{locationData.accuracyMeters}m •{' '}
                      {new Date(locationData.timestamp).toLocaleTimeString('fr-FR')}
                    </div>
                  </div>
                  <a
                    href={`https://www.google.com/maps?q=${locationData.latitude},${locationData.longitude}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 rounded-lg bg-emerald-500 px-3 py-1.5 text-xs font-extrabold text-obsidian-950 hover:bg-emerald-400"
                  >
                    <span>Google Maps</span>
                    <ExternalLink className="h-3 w-3" />
                  </a>
                </div>
              </div>
            ) : (
              <div className="py-10 text-center text-xs text-slate-500">
                Cliquez sur « Localiser » pour interroger le GPS du smartphone.
              </div>
            )}
          </div>

          {/* Device Info Card */}
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-5 shadow-2xl">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Cpu className="h-5 w-5 text-cyan-400" />
                <h2 className="text-sm font-extrabold text-white">État & Système</h2>
              </div>
              {canViewDeviceInfo && (
                <button
                  type="button"
                  disabled={deviceInfoLoading}
                  onClick={() => void handleFetchDeviceInfo()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-cyan-500/35 bg-cyan-500/15 px-3 py-1.5 text-xs font-bold text-cyan-200 hover:bg-cyan-500/25"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${deviceInfoLoading ? 'animate-spin' : ''}`} />
                  <span>Actualiser</span>
                </button>
              )}
            </div>

            {!canViewDeviceInfo ? (
              <p className="mt-4 rounded-xl border border-slate-800 bg-obsidian-950 p-4 text-xs text-slate-400">
                Accès aux informations système désactivé par le propriétaire.
              </p>
            ) : deviceInfo ? (
              <div className="mt-4 space-y-2.5 text-xs">
                <div className="flex justify-between rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2.5">
                  <span className="text-slate-400">Appareil</span>
                  <span className="font-bold text-white">{deviceInfo.deviceName}</span>
                </div>
                <div className="flex justify-between rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2.5">
                  <span className="text-slate-400">Système</span>
                  <span className="font-mono text-cyan-300">
                    Android {deviceInfo.androidVersion} (API {deviceInfo.sdkInt})
                  </span>
                </div>
                <div className="flex justify-between rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2.5">
                  <span className="text-slate-400">Batterie & Réseau</span>
                  <span className="font-mono text-emerald-300">
                    {deviceInfo.batteryLevel}% {deviceInfo.isCharging ? '(En charge)' : ''} •{' '}
                    {deviceInfo.networkType}
                  </span>
                </div>
                {deviceInfo.storageTotalMb > 0 && (
                  <div className="rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-3">
                    <div className="flex items-center justify-between">
                      <span className="inline-flex items-center gap-1.5 text-slate-400">
                        <HardDrive className="h-3.5 w-3.5 text-emerald-400" />
                        Stockage libre
                      </span>
                      <span className="font-mono text-white">
                        {(deviceInfo.storageFreeMb / 1024).toFixed(1)} Go /{' '}
                        {(deviceInfo.storageTotalMb / 1024).toFixed(1)} Go
                      </span>
                    </div>
                    <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-800">
                      <div
                        className="h-full rounded-full bg-gradient-to-r from-emerald-500 to-cyan-400"
                        style={{
                          width: `${Math.min(
                            100,
                            Math.round(
                              ((deviceInfo.storageTotalMb - deviceInfo.storageFreeMb) /
                                deviceInfo.storageTotalMb) *
                                100
                            )
                          )}%`,
                        }}
                      />
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <div className="py-10 text-center text-xs text-slate-500">
                Cliquez sur « Actualiser » pour lire l’état du smartphone.
              </div>
            )}
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
