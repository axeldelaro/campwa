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
  ChevronDown,
  ChevronUp,
  Circle,
  Clock,
  Copy,
  Cpu,
  Download,
  ExternalLink,
  Flashlight,
  FlipHorizontal,
  HardDrive,
  Image as ImageIcon,
  KeyRound,
  LayoutDashboard,
  Lock,
  Mail,
  MapPin,
  Maximize2,
  MessageSquare,
  Mic,
  MicOff,
  Monitor,
  Navigation,
  Phone,
  Plus,
  Power,
  RefreshCw,
  Search,
  Send,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Square,
  Trash2,
  Unplug,
  UserCheck,
  Video,
  Volume2,
  VolumeX,
  X,
  Zap,
  ZoomIn,
} from 'lucide-react';
import { formatFamilyKey, normalizeFamilyKey, normalizePairingCode } from '../crypto/cryptoEngine';
import {
  getOrCreateFamilyKey,
  listPairedDevices,
  removePairedDevice,
  setFamilyKey,
} from '../services/deviceVault';
import { discoverFamilyDevices, ParentPairingSession } from '../services/parentPairingService';
import type { BrokerHealth } from '../services/signalingService';
import { SentinelWebRtcController } from '../services/webrtcController';
import type {
  ActiveAccessGrant,
  AppUsageEntry,
  AuthErrMsg,
  CameraFacingMode,
  DataScreenTimeMsg,
  DeviceTelemetry,
  GalleryAlbumItem,
  GalleryPhotoItem,
  GpsTrackPoint,
  LocationHistoryBundle,
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
import { GeofenceZonesCard, ParentalHomeTab, ParentalRulesTab } from './ControlPanelTab';
import { GalleryTab } from './GalleryTab';
import { GpsTrailMap } from './GpsTrailMap';
import { SecuritySasModal } from './SecuritySasModal';

interface ControlStationViewProps {
  onBrokerHealthChange: (connected: boolean, brokers: BrokerHealth[]) => void;
  initialCode?: string | null;
}

type ViewerTab = 'home' | 'rules' | 'location' | 'live';

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
  const [pushSentFeedback, setPushSentFeedback] = useState<string | null>(null);
  const [accessGrant, setAccessGrant] = useState<ActiveAccessGrant | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [viewerTab, setViewerTab] = useState<ViewerTab>('home');
  const [liveSubTab, setLiveSubTab] = useState<'media' | 'gallery' | 'contacts'>('media');

  // Zero-Loss Parent Family Space & Parent-Initiated Pairing state
  const [familyKey, setFamilyKeyState] = useState<string>(() => getOrCreateFamilyKey());
  const [pairingActiveCode, setPairingActiveCode] = useState<string | null>(null);
  const [pairingAdminPin, setPairingAdminPin] = useState('');
  const [pairingStatus, setPairingStatus] = useState<string | null>(null);
  const [familyDrawerOpen, setFamilyDrawerOpen] = useState(false);
  const [restoreKeyInput, setRestoreKeyInput] = useState('');
  const [restoringFamily, setRestoringFamily] = useState(false);
  const [restoreFeedback, setRestoreFeedback] = useState<string | null>(null);
  const [directCodeOpen, setDirectCodeOpen] = useState(false);
  const pairingSessionRef = useRef<ParentPairingSession | null>(null);

  // Remote Photo Gallery (excluding hidden folders)
  const [galleryItems, setGalleryItems] = useState<GalleryPhotoItem[]>([]);
  const [galleryAlbums, setGalleryAlbums] = useState<GalleryAlbumItem[]>([]);
  const [galleryAlbum, setGalleryAlbum] = useState('');
  const [galleryPage, setGalleryPage] = useState(0);
  const [galleryTotal, setGalleryTotal] = useState(0);
  const [galleryHasMore, setGalleryHasMore] = useState(false);
  const [galleryLoading, setGalleryLoading] = useState(false);
  const [galleryError, setGalleryError] = useState<string | null>(null);
  const [lightboxPhoto, setLightboxPhoto] = useState<{
    item: GalleryPhotoItem;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null>(null);

  // Shared remote data (Contacts, GPS, Device Info)
  const [contacts, setContacts] = useState<SharedContactItem[]>([]);
  const [contactsTotal, setContactsTotal] = useState(0);
  const [contactsQuery, setContactsQuery] = useState('');
  const [contactsLoading, setContactsLoading] = useState(false);
  const [contactsError, setContactsError] = useState<string | null>(null);

  const [locationData, setLocationData] = useState<SharedLocationData | null>(null);
  const [locationLoading, setLocationLoading] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [historyRange, setHistoryRange] = useState<'today' | '24h' | '7d' | '30d'>('24h');
  const [historyBundle, setHistoryBundle] = useState<LocationHistoryBundle | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [focusedTrackPoint, setFocusedTrackPoint] = useState<GpsTrackPoint | null>(null);

  const [deviceInfo, setDeviceInfo] = useState<SharedDeviceInfoData | null>(null);
  const [deviceInfoLoading, setDeviceInfoLoading] = useState(false);
  const [historyListOpen, setHistoryListOpen] = useState(false);
  const [sysInfoOpen, setSysInfoOpen] = useState(false);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // SMS Composer
  const [smsOpen, setSmsOpen] = useState(false);
  const [smsPhone, setSmsPhone] = useState('');
  const [smsContactName, setSmsContactName] = useState('');
  const [smsBody, setSmsBody] = useState('');
  const [smsIncludeGps, setSmsIncludeGps] = useState(false);
  const [smsSending, setSmsSending] = useState(false);
  const [smsResultMsg, setSmsResultMsg] = useState<{ ok: boolean; text: string } | null>(null);

  // Additional Controls: Video Zoom, Local Recording, Text-to-Speech
  const [videoZoom, setVideoZoom] = useState<number>(1);
  const [isRecording, setIsRecording] = useState(false);
  const [ttsOpen, setTtsOpen] = useState(false);
  const [ttsText, setTtsText] = useState('');
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);

  // Screen Lock, Screen Time & Parental Control state
  const [screenTime, setScreenTime] = useState<DataScreenTimeMsg | null>(null);
  const [screenTimeLoading, setScreenTimeLoading] = useState(false);
  const [controlFeedback, setControlFeedback] = useState<{ ok: boolean; text: string } | null>(null);

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
  const [talkieActive, setTalkieActive] = useState(false);
  const [talkieLocked, setTalkieLocked] = useState(false);
  const [talkieLevel, setTalkieLevel] = useState(0);
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
            allowIntercom: t.allowIntercom ?? prev?.allowIntercom ?? true,
            allowSmsRelay: t.allowSmsRelay ?? prev?.allowSmsRelay ?? false,
            allowDeviceControl: t.allowDeviceControl ?? prev?.allowDeviceControl ?? true,
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
        if (info.accessGrant.allowDeviceControl) {
          setViewerTab('home');
          setScreenTimeLoading(true);
          void ctrl.requestScreenTime();
          window.setTimeout(() => setScreenTimeLoading(false), 8000);
        } else {
          setViewerTab('live');
        }
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
      onLocationHistoryData: (bundle) => {
        setHistoryLoading(false);
        setHistoryBundle(bundle);
        if (bundle.error) {
          setLocationError(bundle.error);
        } else if (bundle.points.length > 0) {
          const last = bundle.points[bundle.points.length - 1];
          setLocationData((prev) =>
            prev && prev.timestamp > last.timestamp
              ? prev
              : {
                  latitude: last.latitude,
                  longitude: last.longitude,
                  accuracyMeters: last.accuracyMeters,
                  speedKmh: last.speedKmh,
                  batteryLevel: last.batteryLevel,
                  dwellSeconds: last.dwellSeconds,
                  provider: last.provider,
                  timestamp: last.timestamp,
                }
          );
        }
      },
      onDeviceInfoData: (data) => {
        setDeviceInfoLoading(false);
        if (data.info) {
          setDeviceInfo(data.info);
        }
      },
      onSmsResult: (res) => {
        setSmsSending(false);
        if (res.success) {
          setSmsResultMsg({
            ok: true,
            text: `SMS envoyé vers ${res.phoneNumber}.`,
          });
          setSmsBody('');
        } else {
          setSmsResultMsg({
            ok: false,
            text: res.error || 'Échec de l’envoi SMS.',
          });
        }
      },
      onScreenTimeData: (data) => {
        setScreenTimeLoading(false);
        setScreenTime(data);
        if (data.error) {
          setControlFeedback({ ok: false, text: data.error });
        }
      },
      onControlResult: (res) => {
        if (res.success) {
          const labels: Record<string, string> = {
            lock_screen: 'Écran verrouillé.',
            screen_pause: 'État de pause mis à jour.',
            clear_locks: 'Tous les blocages ont été levés.',
            set_admin_pin: 'Code administrateur mis à jour.',
            curfew: 'Couvre-feu enregistré.',
            app_quota: 'Quota quotidien mis à jour.',
            school_mode: 'Mode École mis à jour.',
            anti_bypass: 'Protection Paramètres / Play Store mise à jour.',
            lost_mode: 'Mode Perdu mis à jour.',
            time_request: 'Réponse envoyée à l’appareil.',
            geofence: 'Zone GPS mise à jour.',
            clear_events: 'Journal de sécurité effacé.',
            ai_shield: res.error || 'Filtre IA mis à jour.',
            remote_policy: 'Politique de l’appareil mise à jour.',
            unpair_decision: res.error || 'Décision de dissociation appliquée.',
          };
          setControlFeedback({ ok: true, text: labels[res.action] || 'Règle mise à jour.' });
        } else {
          setControlFeedback({ ok: false, text: res.error || 'Action refusée.' });
        }
      },
      onGalleryData: (data) => {
        setGalleryLoading(false);
        if (data.error) {
          setGalleryError(data.error);
        } else {
          setGalleryError(null);
          setGalleryAlbums(data.albums || []);
          setGalleryTotal(data.totalCount ?? 0);
          setGalleryHasMore(Boolean(data.hasMore));
          setGalleryPage(data.page ?? 0);
          setGalleryItems((prev) => (data.page > 0 ? [...prev, ...(data.items || [])] : data.items || []));
        }
      },
      onGalleryPhotoData: (info) => {
        setLightboxPhoto((prev) => {
          if (!prev || prev.item.id !== info.mediaId) return prev;
          return {
            ...prev,
            loading: false,
            base64: info.base64,
            error: info.error,
          };
        });
      },
      onStreamError: (msg) => {
        setStreamNotice(msg);
      },
      onTalkieStateChange: (active) => {
        setTalkieActive(active);
        if (!active) setTalkieLocked(false);
      },
      onTalkieLevel: (lvl) => {
        setTalkieLevel(lvl);
      },
    });

    controllerRef.current = ctrl;
    return () => {
      pairingSessionRef.current?.stop();
      ctrl.disconnect();
    };
  }, [onBrokerHealthChange]);

  useEffect(() => {
    if (!initialCode || autoConnectedInitialRef.current) return;
    const clean = normalizePairingCode(initialCode);
    if (clean.length === 6) {
      autoConnectedInitialRef.current = true;
      setCodeChars(clean.split(''));
      void controllerRef.current?.connectToPortal(clean, false);
    }
  }, [initialCode]);

  const fullCode = codeChars.join('');

  const handleConnect = async (targetCode: string) => {
    const normalized = normalizePairingCode(targetCode);
    if (normalized.length !== 6) return;

    setAuthError(null);
    setStreamNotice(null);
    setPushSentFeedback(null);
    setViewerTab('home');
    setLiveSubTab('media');
    setContacts([]);
    setLocationData(null);
    setHistoryBundle(null);
    setFocusedTrackPoint(null);
    setScreenTime(null);
    setControlFeedback(null);
    setDeviceInfo(null);
    setCodeChars(normalized.split(''));
    try {
      await controllerRef.current?.connectToPortal(normalized, false);
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
    setTalkieLocked(false);
    setTalkieActive(false);
    controllerRef.current?.disconnect();
    setTelemetry(null);
    setStats(null);
    setSasInfo(null);
    setAccessGrant(null);
    setStreamNotice(null);
  };

  const handleTalkiePressStart = () => {
    if (!canUseIntercom || talkieLocked) return;
    setStreamNotice(null);
    void controllerRef.current?.startTalkie();
  };

  const handleTalkiePressEnd = () => {
    if (talkieLocked) return;
    void controllerRef.current?.stopTalkie();
  };

  const handleToggleTalkieLock = () => {
    if (!canUseIntercom) return;
    setStreamNotice(null);
    if (talkieActive) {
      setTalkieLocked(false);
      void controllerRef.current?.stopTalkie();
    } else {
      setTalkieLocked(true);
      void controllerRef.current?.startTalkie();
    }
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

  const handleOpenContactsSubTab = () => {
    setViewerTab('live');
    setLiveSubTab('contacts');
    if (contacts.length === 0 && !contactsLoading && canViewContacts) {
      void handleFetchContacts('');
    }
  };

  const handleFetchGallery = async (page = 0, album = galleryAlbum) => {
    setGalleryAlbum(album);
    setGalleryLoading(true);
    setGalleryError(null);
    await controllerRef.current?.requestGallery(page, album, 18);
    window.setTimeout(() => setGalleryLoading(false), 12000);
  };

  const handleOpenGallerySubTab = () => {
    setViewerTab('live');
    setLiveSubTab('gallery');
    if (galleryItems.length === 0 && !galleryLoading) {
      void handleFetchGallery(0, '');
    }
  };

  const handleSelectGalleryPhoto = async (item: GalleryPhotoItem) => {
    setLightboxPhoto({ item, loading: true });
    await controllerRef.current?.requestGalleryPhoto(item.id);
  };

  const handleStartParentPairing = async () => {
    pairingSessionRef.current?.stop();
    const session = new ParentPairingSession({
      familyKey,
      adminPin: pairingAdminPin.trim(),
      onStatus: (msg) => setPairingStatus(msg),
      onPaired: (info) => {
        setPairingActiveCode(null);
        setPairingStatus(null);
        setPairingAdminPin('');
        setSavedDevices(listPairedDevices());
        void handleConnect(info.deviceCode);
      },
    });
    pairingSessionRef.current = session;
    setPairingActiveCode(session.getCode());
    setPairingStatus('Initialisation du canal sécurisé…');
    await session.start();
  };

  const handleCancelParentPairing = () => {
    pairingSessionRef.current?.stop();
    pairingSessionRef.current = null;
    setPairingActiveCode(null);
    setPairingStatus(null);
  };

  const handleRestoreFamily = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = normalizeFamilyKey(restoreKeyInput);
    if (clean.length !== 12) {
      setRestoreFeedback('La Clé Famille doit contenir 12 caractères.');
      return;
    }
    setFamilyKey(clean);
    setFamilyKeyState(clean);
    setRestoringFamily(true);
    setRestoreFeedback('Recherche des appareils associés…');
    try {
      const found = await discoverFamilyDevices(clean, () => {
        setSavedDevices(listPairedDevices());
      });
      setSavedDevices(listPairedDevices());
      setRestoreFeedback(
        found.length > 0
          ? `${found.length} appareil${found.length > 1 ? 's' : ''} retrouvé${found.length > 1 ? 's' : ''} !`
          : 'Clé Famille enregistrée. Aucun appareil en ligne pour le moment (réessayez quand le téléphone est connecté).'
      );
      setRestoreKeyInput('');
    } finally {
      setRestoringFamily(false);
    }
  };

  const handleOpenLocationTab = () => {
    setViewerTab('location');
    if (!deviceInfo && accessGrant?.allowDeviceInfo) {
      void handleFetchDeviceInfo();
    }
    if (!locationData && accessGrant?.allowLocation) {
      void handleFetchLocation();
      void handleFetchLocationHistory(historyRange);
    }
    if (!screenTime && accessGrant?.allowDeviceControl) {
      void handleFetchScreenTime();
    }
  };

  const handleFetchLocationHistory = async (range: 'today' | '24h' | '7d' | '30d') => {
    setHistoryRange(range);
    setHistoryLoading(true);
    setFocusedTrackPoint(null);
    const now = Date.now();
    let fromTs = now - 24 * 3600 * 1000;
    if (range === 'today') {
      const start = new Date();
      start.setHours(0, 0, 0, 0);
      fromTs = start.getTime();
    } else if (range === '7d') {
      fromTs = now - 7 * 24 * 3600 * 1000;
    } else if (range === '30d') {
      fromTs = now - 30 * 24 * 3600 * 1000;
    }
    await controllerRef.current?.requestLocationHistory(fromTs, now, 1800);
    window.setTimeout(() => setHistoryLoading(false), 12000);
  };

  const handleExportGpx = () => {
    const pts = historyBundle?.points ?? [];
    if (pts.length === 0) return;
    const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const name = esc(telemetry?.deviceName || 'SentinelCam');
    const trkpts = pts
      .map(
        (p) =>
          `      <trkpt lat="${p.latitude.toFixed(6)}" lon="${p.longitude.toFixed(6)}"><time>${new Date(
            p.timestamp
          ).toISOString()}</time><desc>±${p.accuracyMeters}m | ${p.speedKmh.toFixed(
            1
          )} km/h | Bat ${p.batteryLevel}%</desc></trkpt>`
      )
      .join('\n');
    const gpx = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="SentinelCam v7.0" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk>\n    <name>${name} (${historyRange})</name>\n    <trkseg>\n${trkpts}\n    </trkseg>\n  </trk>\n</gpx>`;
    const blob = new Blob([gpx], { type: 'application/gpx+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sentinelcam-trace-${historyRange}-${new Date().toISOString().slice(0, 10)}.gpx`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleExportCsv = () => {
    const pts = historyBundle?.points ?? [];
    if (pts.length === 0) return;
    const header = 'date_iso,latitude,longitude,precision_m,vitesse_kmh,batterie_pct,arret_sec,source';
    const rows = pts.map(
      (p) =>
        `${new Date(p.timestamp).toISOString()},${p.latitude.toFixed(6)},${p.longitude.toFixed(6)},${
          p.accuracyMeters
        },${p.speedKmh.toFixed(1)},${p.batteryLevel},${p.dwellSeconds},${p.provider}`
    );
    const blob = new Blob([[header, ...rows].join('\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sentinelcam-trace-${historyRange}-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
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
  const canUseIntercom = accessGrant ? accessGrant.allowIntercom : true;
  const canUseSmsRelay = accessGrant ? accessGrant.allowSmsRelay : false;
  const canUseDeviceControl = accessGrant ? accessGrant.allowDeviceControl : true;

  const handleFetchScreenTime = async () => {
    setScreenTimeLoading(true);
    setControlFeedback(null);
    await controllerRef.current?.requestScreenTime();
    window.setTimeout(() => setScreenTimeLoading(false), 8000);
  };

  const handleOpenHomeOrRulesTab = (tab: 'home' | 'rules') => {
    setViewerTab(tab);
    if (canUseDeviceControl && !screenTime && !screenTimeLoading) {
      void handleFetchScreenTime();
    }
  };

  const handleLockScreenNow = async () => {
    setControlFeedback(null);
    await controllerRef.current?.lockRemoteScreen();
  };

  const handleSetScreenPause = async (minutes: number) => {
    setControlFeedback(null);
    await controllerRef.current?.setRemoteScreenPause(minutes, minutes > 0);
  };

  const handleApplyAppLock = async (app: AppUsageEntry, minutes: number, explicitUntilMs?: number) => {
    setControlFeedback(null);
    await controllerRef.current?.setRemoteAppLock({
      packageName: app.packageName,
      appName: app.appName,
      durationMinutes: minutes,
      untilMs: explicitUntilMs,
    });
  };

  const handleClearAllAppLocks = async () => {
    setControlFeedback(null);
    await controllerRef.current?.clearAllRemoteAppLocks();
  };

  const handleSetRemoteAdminPin = async (pin: string) => {
    setControlFeedback(null);
    await controllerRef.current?.setRemoteAdminPin(pin, true);
  };

  const handleRespondUnpair = async (approve: boolean) => {
    setControlFeedback(null);
    await controllerRef.current?.respondUnpairRequest(approve);
    if (approve) {
      const codeToRemove = fullCode;
      window.setTimeout(() => {
        if (codeToRemove.length === 6) {
          removePairedDevice(codeToRemove);
          setSavedDevices(listPairedDevices());
        }
        handleDisconnect();
      }, 650);
    }
  };

  useEffect(() => {
    if (!isSessionOpen) return;
    const onKeyDown = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        setSasModalOpen(false);
        setSmsOpen(false);
        setTtsOpen(false);
        return;
      }
      const tag = (ev.target as HTMLElement | null)?.tagName;
      if (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        (ev.target as HTMLElement | null)?.isContentEditable ||
        ev.ctrlKey ||
        ev.metaKey ||
        ev.altKey
      ) {
        return;
      }
      if (!isStreaming) return;
      const key = ev.key.toLowerCase();
      if (key === 'f') {
        ev.preventDefault();
        handleFullscreen();
      } else if (key === 'p') {
        ev.preventDefault();
        handleCaptureSnapshot();
      } else if (key === 'r') {
        ev.preventDefault();
        handleToggleRecording();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  const openSmsComposer = (phone = '', name = '') => {
    setSmsPhone(phone);
    setSmsContactName(name);
    setSmsResultMsg(null);
    setSmsOpen(true);
  };

  const handleToggleRecording = () => {
    if (isRecording) {
      try {
        mediaRecorderRef.current?.stop();
      } catch {
        // Ignore
      }
      setIsRecording(false);
      return;
    }

    const stream = videoRef.current?.srcObject as MediaStream | null;
    if (!stream || typeof MediaRecorder === 'undefined') return;
    try {
      recordedChunksRef.current = [];
      const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=vp9,opus')
        ? 'video/webm;codecs=vp9,opus'
        : MediaRecorder.isTypeSupported('video/webm')
        ? 'video/webm'
        : '';
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      recorder.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) recordedChunksRef.current.push(ev.data);
      };
      recorder.onstop = () => {
        const blob = new Blob(recordedChunksRef.current, { type: recorder.mimeType || 'video/webm' });
        if (blob.size > 0) {
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `sentinelcam-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.webm`;
          a.click();
          URL.revokeObjectURL(url);
        }
        recordedChunksRef.current = [];
      };
      recorder.start(500);
      mediaRecorderRef.current = recorder;
      setIsRecording(true);
    } catch {
      setIsRecording(false);
    }
  };

  const handleCycleZoom = () => {
    setVideoZoom((z) => (z >= 4 ? 1 : z + 1));
  };

  const handleToggleTorch = async () => {
    const next = !(telemetry?.torchEnabled ?? false);
    await controllerRef.current?.toggleTorch(next);
  };

  const handleToggleRing = async () => {
    const next = !(telemetry?.ringingActive ?? false);
    await controllerRef.current?.ringDevice(next, 15);
  };

  const handleSendTts = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ttsText.trim()) return;
    await controllerRef.current?.speakText(ttsText.trim());
    setTtsText('');
  };

  const buildComposedSmsText = () => {
    let text = smsBody.trim();
    if (smsIncludeGps && locationData) {
      const gpsUrl = `https://maps.google.com/?q=${locationData.latitude.toFixed(5)},${locationData.longitude.toFixed(5)}`;
      if (!text.includes('maps.google.com')) {
        text = `${text}\nGPS: ${gpsUrl}`;
      }
    }
    return text;
  };

  const handleSendRemoteSms = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!smsPhone.trim() || !smsBody.trim()) return;
    setSmsSending(true);
    setSmsResultMsg(null);
    await controllerRef.current?.sendRemoteSms({
      phoneNumber: smsPhone.trim(),
      contactName: smsContactName.trim() || undefined,
      message: smsBody.trim(),
      includeGpsLink: smsIncludeGps && canViewLocation,
    });
    window.setTimeout(() => setSmsSending(false), 12000);
  };

  const cleanWaDigits = smsPhone.replace(/[^0-9]/g, '');
  const composedPreviewText = buildComposedSmsText();
  const smsSchemeHref = `sms:${encodeURIComponent(smsPhone.trim())}?body=${encodeURIComponent(composedPreviewText)}`;
  const whatsappHref = cleanWaDigits
    ? `https://wa.me/${cleanWaDigits}?text=${encodeURIComponent(composedPreviewText)}`
    : `https://wa.me/?text=${encodeURIComponent(composedPreviewText)}`;

  /* ==================== SCREEN 1: PARENT FAMILY SPACE (ZERO-LOSS PAIRING) ==================== */
  if (!isSessionOpen) {
    return (
      <div className="mx-auto flex min-h-[calc(100vh-4rem)] max-w-md flex-col justify-center px-3.5 py-6 sm:px-4 sm:py-10">
        <div className="space-y-4 rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-5 sm:p-7">
          <div className="text-center">
            <h1 className="text-xl font-semibold tracking-tight text-white">Espace Parent</h1>
            <p className="mt-1 text-xs text-zinc-400">
              Associez un smartphone et pilotez toutes ses règles à distance
            </p>
          </div>

          {/* Saved / Paired Devices (1-Click Open) */}
          {savedDevices.length > 0 && (
            <div className="space-y-1.5">
              <div className="text-[11px] font-medium text-zinc-500">Appareils associés</div>
              {savedDevices.map((dev) => (
                <div
                  key={dev.pairingCode}
                  onClick={() => void handleConnect(dev.pairingCode)}
                  className="flex cursor-pointer items-center justify-between gap-2 rounded-xl border border-white/[0.07] bg-zinc-950/80 px-3.5 py-3 transition hover:border-white/20"
                >
                  <div className="flex min-w-0 items-center gap-2.5">
                    <Smartphone className="h-4 w-4 shrink-0 text-emerald-400" />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-xs font-semibold text-white">
                          {dev.deviceName}
                        </span>
                        <span className="shrink-0 font-mono text-[11px] text-zinc-500">
                          {dev.pairingCode}
                        </span>
                      </div>
                      {dev.accessRole && dev.accessRole !== 'owner' && (
                        <div className="truncate text-[10px] text-zinc-500">{dev.accessName}</div>
                      )}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-1.5">
                    <span className="text-[11px] font-medium text-zinc-300">Ouvrir →</span>
                    <button
                      type="button"
                      onClick={(e) => handleForgetDevice(dev.pairingCode, e)}
                      className="rounded-lg p-1.5 text-zinc-600 transition hover:text-rose-400"
                      title="Retirer de la liste"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Parent-Generated Pairing Wizard */}
          {pairingActiveCode ? (
            <div className="rounded-xl border border-emerald-500/35 bg-zinc-950 p-4 text-center">
              <div className="text-[11px] font-medium text-zinc-400">
                Saisissez ce code dans l’application du smartphone
              </div>
              <div className="mt-3 grid grid-cols-6 gap-1.5">
                {pairingActiveCode.split('').map((ch, i) => (
                  <span
                    key={i}
                    className="flex h-12 items-center justify-center rounded-lg border border-emerald-500/40 bg-zinc-900 font-mono text-lg font-semibold text-emerald-300 sm:text-xl"
                  >
                    {ch}
                  </span>
                ))}
              </div>
              <div className="mt-3 flex items-center justify-center gap-1.5 text-xs text-zinc-400">
                <RefreshCw className="h-3.5 w-3.5 shrink-0 animate-spin text-emerald-400" />
                <span>{pairingStatus || 'En attente du smartphone…'}</span>
              </div>
              <button
                type="button"
                onClick={handleCancelParentPairing}
                className="mt-3 text-xs text-zinc-500 hover:text-zinc-300"
              >
                Annuler
              </button>
            </div>
          ) : (
            <div className="space-y-2.5">
              <input
                type="password"
                maxLength={12}
                value={pairingAdminPin}
                onChange={(e) => setPairingAdminPin(e.target.value)}
                placeholder="Code administrateur requis (4–12 car.)"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />
              <button
                type="button"
                disabled={pairingAdminPin.trim().length < 4}
                onClick={() => void handleStartParentPairing()}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-white px-5 py-3 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
              >
                <Plus className="h-4 w-4" />
                <span>Associer un nouvel appareil</span>
              </button>
            </div>
          )}

          {/* Connecting indicator */}
          {portalState === 'connecting' && (
            <div className="flex items-center justify-between rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-zinc-300">
              <span className="inline-flex items-center gap-2">
                <RefreshCw className="h-3.5 w-3.5 animate-spin text-emerald-400" />
                Connexion à {fullCode}…
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => void handleSendPushInvite()}
                  className="text-emerald-300 hover:underline"
                >
                  Réveiller
                </button>
                <button
                  type="button"
                  onClick={handleDisconnect}
                  className="text-zinc-500 hover:text-white"
                >
                  Annuler
                </button>
              </div>
            </div>
          )}

          {pushSentFeedback && (
            <div className="flex items-center justify-center gap-1.5 rounded-lg bg-emerald-500/10 px-3 py-1.5 text-xs text-emerald-300">
              <Check className="h-3.5 w-3.5" />
              <span>{pushSentFeedback}</span>
            </div>
          )}

          {authError && (
            <div className="flex items-start gap-2.5 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
              <div>
                <div className="font-medium">
                  {authError.reason === 'ACCESS_EXPIRED'
                    ? 'Accès expiré'
                    : authError.reason === 'ACCESS_REVOKED'
                    ? 'Accès révoqué'
                    : 'Connexion refusée'}
                </div>
                <div className="mt-0.5 text-rose-300/80">{authError.message}</div>
              </div>
            </div>
          )}

          {/* Zero-Loss Family Key Backup & Multi-Device Restore */}
          <div className="border-t border-white/[0.07] pt-3.5">
            <div className="flex items-center justify-between gap-2 text-xs">
              <button
                type="button"
                onClick={() => setFamilyDrawerOpen((v) => !v)}
                className="inline-flex items-center gap-1.5 text-zinc-400 hover:text-white"
              >
                <KeyRound className="h-3.5 w-3.5 text-emerald-400" />
                <span>Clé Famille (récupération sans perte)</span>
              </button>
              <button
                type="button"
                onClick={() => setDirectCodeOpen((v) => !v)}
                className="text-[11px] text-zinc-500 hover:text-zinc-300"
              >
                Code direct
              </button>
            </div>

            {familyDrawerOpen && (
              <div className="mt-3 space-y-3 rounded-xl border border-white/[0.07] bg-zinc-950 p-3.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <div>
                    <div className="text-[10px] text-zinc-500">Votre Clé Famille (à conserver)</div>
                    <div className="mt-0.5 font-mono text-sm font-semibold tracking-wider text-emerald-300">
                      {formatFamilyKey(familyKey)}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleCopySmall(formatFamilyKey(familyKey), 'fam-key')}
                    className="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-zinc-900 px-2.5 py-1 text-[11px] text-zinc-200 hover:border-white/25"
                  >
                    {copiedKey === 'fam-key' ? (
                      <Check className="h-3 w-3 text-emerald-400" />
                    ) : (
                      <Copy className="h-3 w-3" />
                    )}
                    <span>{copiedKey === 'fam-key' ? 'Copiée' : 'Copier'}</span>
                  </button>
                </div>

                <form onSubmit={(e) => void handleRestoreFamily(e)} className="flex gap-1.5 border-t border-white/[0.06] pt-2.5">
                  <input
                    type="text"
                    value={restoreKeyInput}
                    onChange={(e) => setRestoreKeyInput(e.target.value)}
                    placeholder="Restaurer une Clé Famille (XXXX-XXXX-XXXX)"
                    className="min-w-0 flex-1 rounded-lg border border-white/10 bg-zinc-900 px-2.5 py-1.5 font-mono text-[11px] uppercase text-white placeholder-zinc-500"
                  />
                  <button
                    type="submit"
                    disabled={restoringFamily || normalizeFamilyKey(restoreKeyInput).length !== 12}
                    className="shrink-0 rounded-lg bg-white px-3 py-1.5 text-[11px] font-semibold text-zinc-950 disabled:opacity-35"
                  >
                    {restoringFamily ? '…' : 'Retrouver'}
                  </button>
                </form>

                {restoreFeedback && (
                  <div className="text-[11px] text-emerald-300">{restoreFeedback}</div>
                )}
              </div>
            )}

            {/* Direct 6-Char Code Fallback */}
            {directCodeOpen && (
              <div className="mt-3 space-y-2.5 rounded-xl border border-white/[0.07] bg-zinc-950 p-3.5">
                <div className="grid grid-cols-6 gap-1.5" onPaste={handlePaste}>
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
                      className="h-11 w-full rounded-lg border border-white/10 bg-zinc-900 text-center font-mono text-base font-semibold uppercase text-white focus:border-emerald-400 focus:outline-none"
                      placeholder="·"
                    />
                  ))}
                </div>
                <button
                  type="button"
                  disabled={fullCode.length !== 6}
                  onClick={() => void handleConnect(fullCode)}
                  className="w-full rounded-lg bg-white py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-35"
                >
                  Connexion directe
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }

  /* ==================== SCREEN 2: CONNECTED WORKSPACE ==================== */
  return (
    <div className="mx-auto max-w-5xl space-y-3.5 px-3.5 pb-24 pt-3.5 sm:space-y-4 sm:px-6 sm:pb-10 sm:pt-5">
      {/* Compact Device Status Bar (Single Clean Row on Mobile) */}
      <div className="flex items-center justify-between gap-2 rounded-2xl border border-white/[0.07] bg-zinc-900/70 px-3.5 py-2.5 sm:px-4 sm:py-3">
        <div className="flex min-w-0 items-center gap-2 sm:gap-2.5">
          <span
            className={`h-2 w-2 shrink-0 rounded-full ${
              isStreaming
                ? 'bg-rose-500 animate-pulse'
                : portalState === 'connecting'
                ? 'bg-amber-400 animate-pulse'
                : 'bg-emerald-400'
            }`}
          />
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <span className="truncate text-sm font-semibold text-white">
                {telemetry?.deviceName || sasInfo?.deviceName || 'Smartphone'}
              </span>
              <span className="hidden rounded bg-white/[0.06] px-1.5 py-0.5 font-mono text-[11px] text-zinc-400 sm:inline">
                {fullCode}
              </span>
            </div>
            {telemetry && (
              <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
                {telemetry.isCharging ? (
                  <BatteryCharging className="h-3 w-3 text-emerald-400" />
                ) : (
                  <Battery className="h-3 w-3 text-zinc-500" />
                )}
                <span className="font-mono">{telemetry.batteryLevel}%</span>
                <span className="text-zinc-600">·</span>
                <span className="truncate">{telemetry.networkType}</span>
                {isStreaming && stats && (
                  <>
                    <span className="hidden text-zinc-600 sm:inline">·</span>
                    <span className="hidden items-center gap-1 font-mono text-zinc-500 sm:inline-flex">
                      <Activity className="h-3 w-3" />
                      {stats.rttMs} ms
                    </span>
                  </>
                )}
              </div>
            )}
          </div>

          {accessGrant && accessGrant.role !== 'owner' && (
            <span className="hidden items-center gap-1 rounded-md bg-white/[0.05] px-2 py-0.5 text-[11px] text-zinc-300 sm:inline-flex">
              {accessGrant.role === 'friend' ? (
                <UserCheck className="h-3 w-3 text-emerald-400" />
              ) : (
                <Clock className="h-3 w-3 text-zinc-400" />
              )}
              <span>
                {accessGrant.name}
                {remainingLabel ? ` · ${remainingLabel}` : ''}
              </span>
            </span>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
          <button
            type="button"
            disabled={!canUseIntercom}
            onClick={() => void handleToggleRing()}
            className={`inline-flex h-8 items-center justify-center gap-1.5 rounded-lg border px-2.5 text-xs font-medium transition disabled:opacity-35 ${
              telemetry?.ringingActive
                ? 'border-rose-500/60 bg-rose-500/20 text-rose-200 animate-pulse'
                : 'border-white/[0.08] bg-white/[0.03] text-zinc-300 hover:bg-white/[0.07]'
            }`}
            title="Faire sonner l’appareil pour le localiser"
          >
            <BellRing className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">
              {telemetry?.ringingActive ? 'Arrêter' : 'Sonnerie'}
            </span>
          </button>

          {sasInfo && (
            <button
              type="button"
              onClick={() => setSasModalOpen(true)}
              className="hidden h-8 items-center gap-1.5 rounded-lg border border-white/[0.08] bg-white/[0.03] px-2.5 font-mono text-[11px] text-zinc-300 transition hover:bg-white/[0.07] sm:inline-flex"
              title="Code de vérification E2EE"
            >
              <Lock className="h-3 w-3 text-emerald-400" />
              <span>{sasInfo.sasCode}</span>
            </button>
          )}

          <button
            type="button"
            onClick={handleDisconnect}
            className="inline-flex h-8 items-center justify-center gap-1.5 rounded-lg border border-white/[0.08] bg-white/[0.03] px-2.5 text-xs font-medium text-zinc-300 transition hover:border-rose-500/40 hover:text-rose-300"
            title="Quitter la session"
          >
            <Unplug className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">Quitter</span>
          </button>
        </div>
      </div>

      {/* Desktop Top 4-Tab Bar */}
      <div className="hidden grid-cols-4 gap-1.5 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-1.5 sm:grid">
        <button
          type="button"
          disabled={!canUseDeviceControl}
          onClick={() => handleOpenHomeOrRulesTab('home')}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-35 ${
            viewerTab === 'home'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:bg-white/[0.05] hover:text-white'
          }`}
        >
          <LayoutDashboard className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">Accueil</span>
          {(telemetry?.unpairRequestPending ||
            (telemetry?.pendingRequestsCount ?? 0) > 0 ||
            (telemetry?.activeAppLocksCount ?? 0) > 0) && (
            <span
              className={`rounded px-1 py-0.5 font-mono text-[10px] ${
                telemetry?.unpairRequestPending
                  ? 'bg-rose-500 text-white'
                  : viewerTab === 'home'
                  ? 'bg-zinc-900 text-white'
                  : (telemetry?.pendingRequestsCount ?? 0) > 0
                  ? 'bg-emerald-500/25 text-emerald-300'
                  : 'bg-rose-500/20 text-rose-300'
              }`}
            >
              {telemetry?.unpairRequestPending
                ? '!'
                : (telemetry?.pendingRequestsCount ?? 0) > 0
                ? `+${telemetry?.pendingRequestsCount}`
                : telemetry?.activeAppLocksCount}
            </span>
          )}
        </button>

        <button
          type="button"
          disabled={!canUseDeviceControl}
          onClick={() => handleOpenHomeOrRulesTab('rules')}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-35 ${
            viewerTab === 'rules'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:bg-white/[0.05] hover:text-white'
          }`}
        >
          <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">Règles</span>
          {(telemetry?.securityAlertsCount ?? 0) > 0 && (
            <span
              className={`rounded px-1 py-0.5 font-mono text-[10px] ${
                viewerTab === 'rules' ? 'bg-zinc-900 text-white' : 'bg-white/[0.08] text-zinc-300'
              }`}
            >
              {telemetry?.securityAlertsCount}
            </span>
          )}
        </button>

        <button
          type="button"
          disabled={!canViewLocation && !canViewDeviceInfo}
          onClick={handleOpenLocationTab}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-35 ${
            viewerTab === 'location'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:bg-white/[0.05] hover:text-white'
          }`}
        >
          <MapPin className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">Position</span>
        </button>

        <button
          type="button"
          onClick={() => setViewerTab('live')}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition ${
            viewerTab === 'live'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:bg-white/[0.05] hover:text-white'
          }`}
        >
          <Video className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">Direct</span>
        </button>
      </div>

      {/* Native-Style Fixed Mobile Bottom Navigation Bar */}
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-white/[0.08] bg-[#09090b]/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl sm:hidden">
        <div className="grid grid-cols-4 px-1.5 py-1.5">
          <button
            type="button"
            disabled={!canUseDeviceControl}
            onClick={() => handleOpenHomeOrRulesTab('home')}
            className={`relative flex flex-col items-center justify-center gap-1 rounded-xl py-1.5 text-[10px] font-medium transition disabled:opacity-35 ${
              viewerTab === 'home' ? 'text-white' : 'text-zinc-500'
            }`}
          >
            <div className="relative">
              <LayoutDashboard className={`h-5 w-5 ${viewerTab === 'home' ? 'text-emerald-400' : ''}`} />
              {(telemetry?.unpairRequestPending ||
                (telemetry?.pendingRequestsCount ?? 0) > 0 ||
                (telemetry?.activeAppLocksCount ?? 0) > 0) && (
                <span
                  className={`absolute -right-2 -top-1 rounded-full px-1 font-mono text-[9px] font-bold ${
                    telemetry?.unpairRequestPending
                      ? 'bg-rose-500 text-white'
                      : 'bg-emerald-500 text-zinc-950'
                  }`}
                >
                  {telemetry?.unpairRequestPending
                    ? '!'
                    : (telemetry?.pendingRequestsCount ?? 0) > 0
                    ? `+${telemetry?.pendingRequestsCount}`
                    : telemetry?.activeAppLocksCount}
                </span>
              )}
            </div>
            <span>Accueil</span>
          </button>

          <button
            type="button"
            disabled={!canUseDeviceControl}
            onClick={() => handleOpenHomeOrRulesTab('rules')}
            className={`relative flex flex-col items-center justify-center gap-1 rounded-xl py-1.5 text-[10px] font-medium transition disabled:opacity-35 ${
              viewerTab === 'rules' ? 'text-white' : 'text-zinc-500'
            }`}
          >
            <div className="relative">
              <ShieldCheck className={`h-5 w-5 ${viewerTab === 'rules' ? 'text-emerald-400' : ''}`} />
              {(telemetry?.securityAlertsCount ?? 0) > 0 && (
                <span className="absolute -right-2 -top-1 rounded-full bg-amber-400 px-1 font-mono text-[9px] font-bold text-zinc-950">
                  {telemetry?.securityAlertsCount}
                </span>
              )}
            </div>
            <span>Règles</span>
          </button>

          <button
            type="button"
            disabled={!canViewLocation && !canViewDeviceInfo}
            onClick={handleOpenLocationTab}
            className={`flex flex-col items-center justify-center gap-1 rounded-xl py-1.5 text-[10px] font-medium transition disabled:opacity-35 ${
              viewerTab === 'location' ? 'text-white' : 'text-zinc-500'
            }`}
          >
            <MapPin className={`h-5 w-5 ${viewerTab === 'location' ? 'text-emerald-400' : ''}`} />
            <span>Position</span>
          </button>

          <button
            type="button"
            onClick={() => setViewerTab('live')}
            className={`flex flex-col items-center justify-center gap-1 rounded-xl py-1.5 text-[10px] font-medium transition ${
              viewerTab === 'live' ? 'text-white' : 'text-zinc-500'
            }`}
          >
            <Video className={`h-5 w-5 ${viewerTab === 'live' ? 'text-emerald-400' : ''}`} />
            <span>Direct</span>
          </button>
        </div>
      </nav>

      {/* ==================== TAB 1: PARENTAL CONTROL HOME ==================== */}
      {viewerTab === 'home' && (
        <ParentalHomeTab
          screenTime={screenTime}
          screenTimeLoading={screenTimeLoading}
          telemetry={telemetry}
          nowMs={nowMs}
          feedback={controlFeedback}
          onDismissFeedback={() => setControlFeedback(null)}
          onRefresh={handleFetchScreenTime}
          onLockScreenNow={handleLockScreenNow}
          onSetScreenPause={handleSetScreenPause}
          onApplyAppLock={handleApplyAppLock}
          onClearAllAppLocks={handleClearAllAppLocks}
          onSetAppQuota={async (app, dailyLimitMinutes) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteAppQuota({
              packageName: app.packageName,
              appName: app.appName,
              dailyLimitMinutes,
            });
          }}
          onSetSchoolMode={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteSchoolMode(opts);
          }}
          onRespondTimeRequest={async (timeRequestId, approve, grantMinutes) => {
            setControlFeedback(null);
            await controllerRef.current?.respondTimeRequest({
              timeRequestId,
              approve,
              grantMinutes,
            });
          }}
          onRespondUnpair={handleRespondUnpair}
        />
      )}

      {/* ==================== TAB 2: RULES, ANTI-BYPASS, LOST MODE & JOURNAL ==================== */}
      {viewerTab === 'rules' && (
        <ParentalRulesTab
          screenTime={screenTime}
          screenTimeLoading={screenTimeLoading}
          telemetry={telemetry}
          nowMs={nowMs}
          feedback={controlFeedback}
          onDismissFeedback={() => setControlFeedback(null)}
          onRefresh={handleFetchScreenTime}
          onSetCurfew={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteCurfew(opts);
          }}
          onSetSchoolMode={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteSchoolMode(opts);
          }}
          onSetAntiBypass={async (enabled) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteAntiBypass(enabled);
          }}
          onSetLostMode={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteLostMode(opts);
          }}
          onClearSecurityEvents={async () => {
            setControlFeedback(null);
            await controllerRef.current?.clearRemoteSecurityEvents();
          }}
          onSetAdminPin={handleSetRemoteAdminPin}
          onSetAiShield={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteAiShield(opts);
          }}
          onSetRemotePolicy={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemotePolicy(opts);
          }}
          onRespondUnpair={handleRespondUnpair}
        />
      )}

      {/* ==================== TAB 4: DIRECT (CAMERA / SCREEN / GALLERY / CONTACTS & SMS) ==================== */}
      {viewerTab === 'live' && (
        <div className="grid grid-cols-3 gap-1.5 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-1 sm:inline-flex sm:w-auto">
          <button
            type="button"
            onClick={() => setLiveSubTab('media')}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-2.5 py-2 text-xs font-medium transition sm:py-1.5 ${
              liveSubTab === 'media'
                ? 'bg-white text-zinc-950'
                : 'text-zinc-400 hover:bg-white/[0.04] hover:text-white'
            }`}
          >
            <Video className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">Caméra</span>
          </button>
          <button
            type="button"
            onClick={handleOpenGallerySubTab}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-2.5 py-2 text-xs font-medium transition sm:py-1.5 ${
              liveSubTab === 'gallery'
                ? 'bg-white text-zinc-950'
                : 'text-zinc-400 hover:bg-white/[0.04] hover:text-white'
            }`}
          >
            <ImageIcon className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">Galerie</span>
          </button>
          <button
            type="button"
            onClick={handleOpenContactsSubTab}
            className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-2.5 py-2 text-xs font-medium transition sm:py-1.5 ${
              liveSubTab === 'contacts'
                ? 'bg-white text-zinc-950'
                : 'text-zinc-400 hover:bg-white/[0.04] hover:text-white'
            }`}
          >
            <BookUser className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">{canViewContacts ? 'Contacts' : 'SMS'}</span>
          </button>
        </div>
      )}

      {/* ==================== DIRECT SUB-VIEW: PHOTO GALLERY ==================== */}
      {viewerTab === 'live' && liveSubTab === 'gallery' && (
        <GalleryTab
          items={galleryItems}
          albums={galleryAlbums}
          activeAlbum={galleryAlbum}
          totalCount={galleryTotal}
          hasMore={galleryHasMore}
          loading={galleryLoading}
          error={galleryError}
          canViewGallery={Boolean(telemetry?.allowGallery)}
          lightboxPhoto={lightboxPhoto}
          onRefresh={async (alb) => {
            await handleFetchGallery(0, alb !== undefined ? alb : galleryAlbum);
          }}
          onLoadMore={async () => {
            await handleFetchGallery(galleryPage + 1, galleryAlbum);
          }}
          onSelectPhoto={handleSelectGalleryPhoto}
          onCloseLightbox={() => setLightboxPhoto(null)}
        />
      )}

      {/* ==================== DIRECT SUB-VIEW 1: CAMERA & SCREEN SHARE ==================== */}
      {viewerTab === 'live' && liveSubTab === 'media' && (
        <>
          {streamNotice && (
            <div className="flex items-center gap-2.5 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-xs text-amber-200">
              <AlertTriangle className="h-4 w-4 shrink-0 text-amber-400" />
              <span>{streamNotice}</span>
            </div>
          )}

          <div
            ref={videoContainerRef}
            className="relative aspect-video w-full overflow-hidden rounded-2xl border border-white/[0.08] bg-zinc-950"
          >
            {isStreaming && (
              <div className="absolute left-3 top-3 z-20 flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/40 bg-zinc-950/85 px-2.5 py-1 font-mono text-[11px] font-semibold text-rose-200 backdrop-blur">
                  <span className="h-2 w-2 rounded-full bg-rose-500 animate-ping" />
                  {mediaMode === 'screen'
                    ? 'ÉCRAN EN DIRECT'
                    : `DIRECT · ${cameraFacing === 'front' ? 'SELFIE' : 'ARRIÈRE'}`}
                </span>
              </div>
            )}

            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted={localAudioMuted}
              style={{
                transform: `${mirrorVideo && mediaMode === 'camera' ? 'scaleX(-1) ' : ''}scale(${videoZoom})`,
              }}
              className={`h-full w-full object-contain transition-all duration-200 ${
                isStreaming ? 'opacity-100' : 'opacity-0'
              }`}
            />

            {!isStreaming && (
              <div className="absolute inset-0 flex flex-col items-center justify-center p-5 text-center">
                {portalState === 'connecting' ? (
                  <>
                    <RefreshCw className="h-9 w-9 animate-spin text-emerald-400" />
                    <h3 className="mt-3 text-sm font-semibold text-white">
                      {mediaMode === 'screen'
                        ? 'Ouverture du partage d’écran…'
                        : 'Démarrage de la vidéo…'}
                    </h3>
                  </>
                ) : portalState === 'locked' ? (
                  <>
                    <AlertTriangle className="h-9 w-9 text-amber-400" />
                    <h3 className="mt-3 text-sm font-semibold text-amber-300">
                      Temporisation de sécurité active
                    </h3>
                  </>
                ) : (
                  <>
                    <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.04]">
                      <CameraOff className="h-6 w-6 text-zinc-400" />
                    </div>
                    <h3 className="mt-3 text-base font-semibold text-white">
                      Caméra en veille
                    </h3>
                    <p className="mt-1 max-w-xs text-xs text-zinc-400">
                      Démarrez la caméra ou l’écran à la demande.
                    </p>
                    <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
                      <button
                        type="button"
                        onClick={() => void handleWakeMedia('camera')}
                        className="inline-flex items-center gap-2 rounded-xl bg-white px-5 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
                      >
                        <Video className="h-4 w-4" />
                        <span>Allumer la caméra</span>
                      </button>

                      {canViewScreen && (
                        <button
                          type="button"
                          onClick={() => void handleWakeMedia('screen')}
                          className="inline-flex items-center gap-2 rounded-xl border border-white/15 bg-zinc-900 px-5 py-2.5 text-xs font-semibold text-zinc-200 transition hover:bg-zinc-800"
                        >
                          <Monitor className="h-4 w-4" />
                          <span>Voir l’écran</span>
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            )}
          </div>

          {/* Media Action Deck (Clean Mobile Grid) */}
          <div className="space-y-2.5 rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-3.5 sm:flex sm:flex-wrap sm:items-center sm:justify-between sm:gap-3 sm:space-y-0 sm:p-4">
            <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
              {!isStreaming ? (
                <button
                  type="button"
                  onClick={() => void handleWakeMedia('camera')}
                  className="col-span-2 inline-flex items-center justify-center gap-2 rounded-xl bg-white px-4 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 sm:col-span-1"
                >
                  <Zap className="h-4 w-4" />
                  <span>Allumer la caméra</span>
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => void handleSleepCamera()}
                  className="col-span-2 inline-flex items-center justify-center gap-2 rounded-xl border border-amber-500/40 bg-amber-500/15 px-4 py-2.5 text-xs font-semibold text-amber-200 transition hover:bg-amber-500/25 sm:col-span-1"
                >
                  <Power className="h-4 w-4" />
                  <span>Couper le flux</span>
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
                    ? 'Autorisez « Capture écran » une fois sur le smartphone'
                    : undefined
                }
                className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-35 ${
                  mediaMode === 'screen' && isStreaming
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
              >
                <Monitor className="h-3.5 w-3.5" />
                <span>
                  {mediaMode === 'screen' && isStreaming
                    ? 'Caméra'
                    : canViewScreen
                    ? 'Écran'
                    : 'Écran (inactif)'}
                </span>
              </button>

              {mediaMode === 'camera' && (
                <button
                  type="button"
                  disabled={!canSwitchCam}
                  onClick={() => void handleToggleFacing()}
                  title={!canSwitchCam ? 'Changement de caméra restreint' : undefined}
                  className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-200 transition hover:border-white/20 disabled:cursor-not-allowed disabled:opacity-35"
                >
                  <RefreshCw className="h-3.5 w-3.5 text-zinc-400" />
                  <span>{cameraFacing === 'front' ? 'Selfie' : 'Arrière'}</span>
                </button>
              )}

              <button
                type="button"
                disabled={!canUseAudio}
                onClick={() => void handleToggleRemoteMic()}
                className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-35 ${
                  remoteMicEnabled && canUseAudio
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
              >
                {remoteMicEnabled && canUseAudio ? (
                  <>
                    <Mic className="h-3.5 w-3.5 text-emerald-400" />
                    <span>Micro ON</span>
                  </>
                ) : (
                  <>
                    <MicOff className="h-3.5 w-3.5 text-zinc-400" />
                    <span>{canUseAudio ? 'Micro OFF' : 'Restreint'}</span>
                  </>
                )}
              </button>
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                onClick={() => void handleToggleTorch()}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition ${
                  telemetry?.torchEnabled
                    ? 'border-amber-400/60 bg-amber-400/20 text-amber-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
                title="Lampe torche"
              >
                <Flashlight className="h-3.5 w-3.5" />
                <span>Torche</span>
              </button>

              <button
                type="button"
                disabled={!isStreaming}
                onClick={handleToggleRecording}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition disabled:opacity-35 ${
                  isRecording
                    ? 'border-rose-500/60 bg-rose-500/20 text-rose-200 animate-pulse'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
                title="Enregistrer la vidéo en local"
              >
                {isRecording ? (
                  <>
                    <Square className="h-3 w-3 fill-current" />
                    <span>Stop</span>
                  </>
                ) : (
                  <>
                    <Circle className="h-3 w-3 fill-rose-500 text-rose-500" />
                    <span>REC</span>
                  </>
                )}
              </button>

              <button
                type="button"
                disabled={!isStreaming}
                onClick={handleCaptureSnapshot}
                className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-300 transition hover:text-white disabled:opacity-35"
              >
                <Camera className="h-3.5 w-3.5" />
                <span>Photo</span>
              </button>

              <button
                type="button"
                disabled={!isStreaming}
                onClick={handleCycleZoom}
                className={`inline-flex items-center gap-1 rounded-xl border px-2.5 py-2 font-mono text-xs font-medium transition disabled:opacity-35 ${
                  videoZoom > 1
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
                title="Zoom numérique"
              >
                <ZoomIn className="h-3.5 w-3.5" />
                <span>{videoZoom}×</span>
              </button>

              <button
                type="button"
                onClick={() => setLocalAudioMuted((m) => !m)}
                className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-white/10 bg-zinc-950 text-zinc-300 transition hover:text-white"
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
                  className={`inline-flex h-8 w-8 items-center justify-center rounded-xl border bg-zinc-950 transition ${
                    mirrorVideo ? 'border-emerald-500/40 text-emerald-300' : 'border-white/10 text-zinc-400'
                  }`}
                >
                  <FlipHorizontal className="h-4 w-4" />
                </button>
              )}

              <button
                type="button"
                onClick={() => setQuality((q) => (q === 'hd' ? 'sd' : 'hd'))}
                className="rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 font-mono text-xs font-semibold text-zinc-300"
              >
                {quality.toUpperCase()}
              </button>

              <button
                type="button"
                disabled={!isStreaming}
                onClick={handleFullscreen}
                className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-white/10 bg-zinc-950 text-zinc-300 transition hover:text-white disabled:opacity-35"
              >
                <Maximize2 className="h-4 w-4" />
              </button>
            </div>
          </div>

          {/* Compact Talkie-Walkie Bar (Inside Direct > Caméra) */}
          <div
            className={`space-y-2.5 rounded-2xl border px-3.5 py-3 transition sm:flex sm:flex-wrap sm:items-center sm:justify-between sm:gap-3 sm:space-y-0 sm:px-4 ${
              talkieActive
                ? 'border-emerald-500/50 bg-emerald-500/10'
                : 'border-white/[0.07] bg-zinc-900/65'
            }`}
          >
            <div className="flex items-center gap-2.5">
              <Volume2
                className={`h-4 w-4 shrink-0 ${talkieActive ? 'text-emerald-400 animate-pulse' : 'text-zinc-400'}`}
              />
              <span className="text-xs font-medium text-white">Haut-parleur</span>
              {talkieActive ? (
                <div className="flex items-center gap-2">
                  <div className="h-1.5 w-24 overflow-hidden rounded-full bg-zinc-950 sm:w-36">
                    <div
                      className="h-full rounded-full bg-emerald-400 transition-all duration-75"
                      style={{ width: `${Math.max(8, talkieLevel)}%` }}
                    />
                  </div>
                  <span className="text-[11px] text-emerald-300">En cours</span>
                </div>
              ) : (
                !canUseIntercom && <span className="text-[11px] text-zinc-500">Désactivé</span>
              )}
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                disabled={!canUseIntercom}
                onPointerDown={handleTalkiePressStart}
                onPointerUp={handleTalkiePressEnd}
                onPointerLeave={handleTalkiePressEnd}
                onPointerCancel={handleTalkiePressEnd}
                className={`inline-flex flex-1 select-none touch-none items-center justify-center gap-1.5 rounded-xl px-3.5 py-2 text-xs font-semibold transition disabled:opacity-35 sm:flex-none ${
                  talkieActive && !talkieLocked
                    ? 'bg-rose-500 text-white'
                    : 'bg-white text-zinc-950 hover:bg-zinc-200'
                }`}
              >
                <Mic className="h-3.5 w-3.5" />
                <span>{talkieActive && !talkieLocked ? 'Parlez…' : 'Maintenir pour parler'}</span>
              </button>

              <button
                type="button"
                disabled={!canUseIntercom}
                onClick={handleToggleTalkieLock}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition disabled:opacity-35 ${
                  talkieLocked
                    ? 'border-rose-500/50 bg-rose-500/15 text-rose-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
              >
                <span>{talkieLocked ? 'Couper' : 'Mains libres'}</span>
              </button>

              <button
                type="button"
                disabled={!canUseIntercom}
                onClick={() => setTtsOpen((v) => !v)}
                className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition disabled:opacity-35 ${
                  ttsOpen
                    ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                    : 'border-white/10 bg-zinc-950 text-zinc-300 hover:text-white'
                }`}
                title="Faire lire un message écrit sur le haut-parleur"
              >
                <MessageSquare className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">Texte vocal</span>
              </button>
            </div>

            {ttsOpen && canUseIntercom && (
              <form onSubmit={(e) => void handleSendTts(e)} className="mt-2 flex w-full gap-2 border-t border-white/[0.06] pt-2.5">
                <input
                  type="text"
                  maxLength={240}
                  value={ttsText}
                  onChange={(e) => setTtsText(e.target.value)}
                  placeholder="Message à lire à voix haute…"
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={!ttsText.trim()}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                >
                  <Send className="h-3.5 w-3.5" />
                  <span>Lire</span>
                </button>
              </form>
            )}
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

      {/* ==================== DIRECT SUB-VIEW 2: CONTACTS & SMS ==================== */}
      {viewerTab === 'live' && liveSubTab === 'contacts' && (
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <BookUser className="h-4 w-4 text-zinc-400" />
              <div>
                <h2 className="text-sm font-semibold text-white">Contacts & SMS</h2>
                <p className="text-xs text-zinc-400">
                  {contactsTotal > 0 ? `${contacts.length} / ${contactsTotal}` : 'Répertoire partagé'}
                </p>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => openSmsComposer('', '')}
                className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
              >
                <MessageSquare className="h-3.5 w-3.5" />
                <span>Nouveau SMS</span>
              </button>
              {canViewContacts && (
                <button
                  type="button"
                  disabled={contactsLoading}
                  onClick={() => void handleFetchContacts(contactsQuery)}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-300 hover:text-white"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${contactsLoading ? 'animate-spin' : ''}`} />
                  <span>Actualiser</span>
                </button>
              )}
            </div>
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
                        <div className="flex items-center gap-1">
                          <button
                            type="button"
                            onClick={() => openSmsComposer(ph, c.name)}
                            className="inline-flex items-center gap-1 rounded-lg border border-orange-500/35 bg-orange-500/15 px-2 py-0.5 text-[10px] font-bold text-orange-200 hover:bg-orange-500/30"
                            title="Envoyer un SMS à ce contact"
                          >
                            <MessageSquare className="h-3 w-3" />
                            <span>SMS</span>
                          </button>
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

      {/* ==================== TAB 3: POSITION (CLEAN MAP-FIRST VIEW) ==================== */}
      {viewerTab === 'location' && (
        <div className="space-y-3">
          {/* Interactive Map Card */}
          <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <MapPin className="h-4 w-4 text-emerald-400" />
                <h2 className="text-sm font-semibold text-white">Localisation</h2>
                {telemetry?.gpsTrackerState && telemetry.gpsTrackerState !== 'disabled' && (
                  <span className="rounded-md bg-white/[0.06] px-2 py-0.5 text-[10px] text-zinc-300">
                    {telemetry.gpsTrackerState === 'moving'
                      ? 'En mouvement'
                      : telemetry.gpsTrackerState === 'stationary'
                      ? 'À l’arrêt'
                      : telemetry.gpsTrackerState === 'battery_saver'
                      ? 'Éco'
                      : 'Permission requise'}
                  </span>
                )}
              </div>

              {canViewLocation && (
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    disabled={locationLoading || historyLoading}
                    onClick={() => {
                      void handleFetchLocation();
                      void handleFetchLocationHistory(historyRange);
                    }}
                    className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-200 hover:border-white/25"
                  >
                    <RefreshCw
                      className={`h-3.5 w-3.5 ${locationLoading || historyLoading ? 'animate-spin' : ''}`}
                    />
                    <span>Actualiser</span>
                  </button>
                  {locationData && (
                    <a
                      href={`https://www.google.com/maps?q=${locationData.latitude},${locationData.longitude}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
                    >
                      <span>Maps</span>
                      <ExternalLink className="h-3 w-3" />
                    </a>
                  )}
                </div>
              )}
            </div>

            {!canViewLocation ? (
              <p className="rounded-xl border border-white/[0.06] bg-zinc-950 p-4 text-xs text-zinc-400">
                Activez « GPS & Trajets » dans l’onglet Règles → Autorisations à distance.
              </p>
            ) : (
              <>
                <div className="grid grid-cols-4 gap-1 rounded-xl border border-white/[0.07] bg-zinc-950 p-1">
                  {(
                    [
                      { id: 'today', label: 'Auj.' },
                      { id: '24h', label: '24 h' },
                      { id: '7d', label: '7 j' },
                      { id: '30d', label: '30 j' },
                    ] as const
                  ).map((r) => (
                    <button
                      key={r.id}
                      type="button"
                      onClick={() => void handleFetchLocationHistory(r.id)}
                      className={`rounded-lg py-1.5 text-xs font-medium transition ${
                        historyRange === r.id
                          ? 'bg-white text-zinc-950'
                          : 'text-zinc-400 hover:text-white'
                      }`}
                    >
                      {r.label}
                    </button>
                  ))}
                </div>

                {locationError && (
                  <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
                    {locationError}
                  </div>
                )}

                <GpsTrailMap
                  points={historyBundle?.points ?? []}
                  liveLocation={locationData}
                  focusedPoint={focusedTrackPoint}
                />

                {/* Minimal Single-Row Trip Summary */}
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950 px-3.5 py-2.5 text-xs text-zinc-400">
                  <div className="flex flex-wrap items-center gap-3 font-mono">
                    <span className="text-white">
                      {((historyBundle?.totalDistanceMeters ?? 0) / 1000).toFixed(2)} km
                    </span>
                    <span>·</span>
                    <span>{historyBundle?.stopsCount ?? 0} arrêt(s)</span>
                    <span>·</span>
                    <span>{(historyBundle?.maxSpeedKmh ?? 0).toFixed(0)} km/h max</span>
                  </div>
                  {locationData && (
                    <span className="text-[11px] text-zinc-500">
                      ±{locationData.accuracyMeters} m ·{' '}
                      {new Date(locationData.timestamp).toLocaleTimeString('fr-FR', {
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  )}
                </div>
              </>
            )}
          </div>

          {/* Collapsible Safe Zones (Geofencing) */}
          {canUseDeviceControl && (
            <GeofenceZonesCard
              screenTime={screenTime}
              locationData={locationData}
              onSaveGeofence={async (opts) => {
                setControlFeedback(null);
                await controllerRef.current?.saveRemoteGeofence(opts);
              }}
              onRequestLocation={async () => {
                await handleFetchLocation();
              }}
            />
          )}

          {/* Collapsible Chronological Trail & Export */}
          {canViewLocation && (historyBundle?.points.length ?? 0) > 0 && (
            <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
              <button
                type="button"
                onClick={() => setHistoryListOpen((v) => !v)}
                className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
              >
                <div className="flex items-center gap-2.5">
                  <Navigation className="h-4 w-4 text-zinc-400" />
                  <div>
                    <div className="text-sm font-medium text-white">Historique & Export</div>
                    <div className="text-[11px] text-zinc-500">
                      {historyBundle?.points.length} points enregistrés
                    </div>
                  </div>
                </div>
                {historyListOpen ? (
                  <ChevronUp className="h-4 w-4 text-zinc-500" />
                ) : (
                  <ChevronDown className="h-4 w-4 text-zinc-500" />
                )}
              </button>

              {historyListOpen && (
                <div className="space-y-3 border-t border-white/[0.06] p-4">
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={handleExportGpx}
                      className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-zinc-300 hover:text-white"
                    >
                      <Download className="h-3.5 w-3.5" />
                      <span>Export GPX</span>
                    </button>
                    <button
                      type="button"
                      onClick={handleExportCsv}
                      className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs text-zinc-300 hover:text-white"
                    >
                      <Download className="h-3.5 w-3.5" />
                      <span>Export CSV</span>
                    </button>
                  </div>

                  <div className="max-h-52 space-y-1.5 overflow-y-auto pr-0.5">
                    {[...(historyBundle?.points ?? [])]
                      .reverse()
                      .slice(0, 60)
                      .map((pt, idx) => {
                        const isStop = pt.dwellSeconds >= 120;
                        return (
                          <button
                            key={`${pt.timestamp}-${idx}`}
                            type="button"
                            onClick={() => setFocusedTrackPoint(pt)}
                            className="flex w-full items-center justify-between gap-2 rounded-xl border border-white/[0.05] bg-zinc-950/80 px-3 py-2 text-left text-xs hover:border-white/15"
                          >
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <span
                                  className={`h-2 w-2 shrink-0 rounded-full ${
                                    isStop ? 'bg-amber-400' : 'bg-emerald-400'
                                  }`}
                                />
                                <span className="font-mono text-white">
                                  {new Date(pt.timestamp).toLocaleString('fr-FR', {
                                    day: '2-digit',
                                    month: '2-digit',
                                    hour: '2-digit',
                                    minute: '2-digit',
                                  })}
                                </span>
                                {isStop && (
                                  <span className="text-[10px] text-amber-300">
                                    Arrêt {Math.round(pt.dwellSeconds / 60)}m
                                  </span>
                                )}
                              </div>
                            </div>
                            <span className="shrink-0 font-mono text-[11px] text-zinc-400">
                              {pt.speedKmh.toFixed(0)} km/h · {pt.batteryLevel}%
                            </span>
                          </button>
                        );
                      })}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Collapsible System Info */}
          <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
            <button
              type="button"
              onClick={() => {
                const next = !sysInfoOpen;
                setSysInfoOpen(next);
                if (next && !deviceInfo && canViewDeviceInfo) {
                  void handleFetchDeviceInfo();
                }
              }}
              className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
            >
              <div className="flex items-center gap-2.5">
                <Cpu className="h-4 w-4 text-zinc-400" />
                <div>
                  <div className="text-sm font-medium text-white">Informations système</div>
                  <div className="text-[11px] text-zinc-500">
                    {deviceInfo
                      ? `Android ${deviceInfo.androidVersion} · ${(deviceInfo.storageFreeMb / 1024).toFixed(1)} Go libres`
                      : 'Stockage, modèle et version Android'}
                  </div>
                </div>
              </div>
              {sysInfoOpen ? (
                <ChevronUp className="h-4 w-4 text-zinc-500" />
              ) : (
                <ChevronDown className="h-4 w-4 text-zinc-500" />
              )}
            </button>

            {sysInfoOpen && (
              <div className="space-y-2 border-t border-white/[0.06] p-4 text-xs">
                {!canViewDeviceInfo ? (
                  <p className="text-zinc-400">Désactivé dans Règles → Autorisations à distance.</p>
                ) : deviceInfo ? (
                  <>
                    <div className="flex justify-between rounded-xl border border-white/[0.05] bg-zinc-950 px-3 py-2">
                      <span className="text-zinc-400">Modèle</span>
                      <span className="font-medium text-white">{deviceInfo.deviceName}</span>
                    </div>
                    <div className="flex justify-between rounded-xl border border-white/[0.05] bg-zinc-950 px-3 py-2">
                      <span className="text-zinc-400">Système</span>
                      <span className="font-mono text-zinc-200">
                        Android {deviceInfo.androidVersion} (API {deviceInfo.sdkInt})
                      </span>
                    </div>
                    {deviceInfo.storageTotalMb > 0 && (
                      <div className="flex justify-between rounded-xl border border-white/[0.05] bg-zinc-950 px-3 py-2">
                        <span className="inline-flex items-center gap-1.5 text-zinc-400">
                          <HardDrive className="h-3.5 w-3.5" />
                          Stockage libre
                        </span>
                        <span className="font-mono text-zinc-200">
                          {(deviceInfo.storageFreeMb / 1024).toFixed(1)} /{' '}
                          {(deviceInfo.storageTotalMb / 1024).toFixed(1)} Go
                        </span>
                      </div>
                    )}
                  </>
                ) : (
                  <div className="flex items-center justify-between text-zinc-400">
                    <span>{deviceInfoLoading ? 'Chargement…' : 'Indisponible'}</span>
                    <button
                      type="button"
                      onClick={() => void handleFetchDeviceInfo()}
                      className="text-emerald-400 hover:underline"
                    >
                      Actualiser
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ==================== SMS COMPOSER MODAL ==================== */}
      {smsOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl border border-white/10 bg-zinc-900 p-5 shadow-2xl">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-white">
                {smsContactName ? `SMS à ${smsContactName}` : 'Envoyer un SMS'}
              </h3>
              <button
                type="button"
                onClick={() => setSmsOpen(false)}
                className="rounded-lg p-1.5 text-zinc-400 hover:bg-white/[0.06] hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <form onSubmit={(e) => void handleSendRemoteSms(e)} className="mt-4 space-y-3">
              <div>
                <label className="mb-1 block text-[11px] font-medium text-zinc-400">
                  Destinataire
                </label>
                <input
                  type="tel"
                  required
                  value={smsPhone}
                  onChange={(e) => setSmsPhone(e.target.value)}
                  placeholder="+33 6 12 34 56 78"
                  className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 font-mono text-sm text-white placeholder-zinc-600 focus:border-emerald-400 focus:outline-none"
                />
              </div>

              <div>
                <div className="mb-1 flex items-center justify-between text-[11px]">
                  <label className="font-medium text-zinc-400">Message</label>
                  <span className="font-mono text-[10px] text-zinc-500">
                    {smsBody.length}/640
                  </span>
                </div>
                <textarea
                  rows={4}
                  maxLength={640}
                  required
                  value={smsBody}
                  onChange={(e) => setSmsBody(e.target.value)}
                  placeholder="Écrivez votre message…"
                  className="w-full rounded-xl border border-white/10 bg-zinc-950 p-3 text-xs text-white placeholder-zinc-600 focus:border-emerald-400 focus:outline-none"
                />
              </div>

              {canViewLocation && (
                <label className="flex cursor-pointer items-center gap-2 text-xs text-zinc-400">
                  <input
                    type="checkbox"
                    checked={smsIncludeGps}
                    onChange={(e) => setSmsIncludeGps(e.target.checked)}
                    className="h-3.5 w-3.5 rounded border-zinc-700 bg-zinc-950 text-emerald-500 focus:ring-0"
                  />
                  <span>Joindre la dernière position GPS</span>
                </label>
              )}

              {smsResultMsg && (
                <div
                  className={`rounded-xl border p-3 text-xs ${
                    smsResultMsg.ok
                      ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
                      : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
                  }`}
                >
                  {smsResultMsg.text}
                </div>
              )}

              <button
                type="submit"
                disabled={!canUseSmsRelay || smsSending || !smsPhone.trim() || !smsBody.trim()}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-white px-4 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:cursor-not-allowed disabled:opacity-35"
              >
                <Send className="h-3.5 w-3.5" />
                <span>
                  {smsSending ? 'Envoi en cours…' : 'Envoyer depuis l’appareil'}
                </span>
              </button>

              {!canUseSmsRelay && (
                <p className="text-center text-[11px] text-zinc-500">
                  Envoi via la carte SIM non activé pour ce code.
                </p>
              )}

              <div className="grid grid-cols-3 gap-2 border-t border-white/[0.07] pt-3">
                <a
                  href={smsSchemeHref}
                  className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 text-[11px] font-medium text-zinc-300 hover:bg-white/[0.05] hover:text-white"
                >
                  <MessageSquare className="h-3.5 w-3.5" />
                  <span>Appli SMS</span>
                </a>
                <a
                  href={whatsappHref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 text-[11px] font-medium text-zinc-300 hover:bg-white/[0.05] hover:text-white"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                  <span>WhatsApp</span>
                </a>
                <button
                  type="button"
                  onClick={() => void handleCopySmall(composedPreviewText, 'sms-copy')}
                  className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 text-[11px] font-medium text-zinc-300 hover:bg-white/[0.05] hover:text-white"
                >
                  {copiedKey === 'sms-copy' ? (
                    <Check className="h-3.5 w-3.5 text-emerald-400" />
                  ) : (
                    <Copy className="h-3.5 w-3.5" />
                  )}
                  <span>{copiedKey === 'sms-copy' ? 'Copié' : 'Copier'}</span>
                </button>
              </div>
            </form>
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
