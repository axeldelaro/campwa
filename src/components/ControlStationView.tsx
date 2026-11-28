import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Camera,
  CameraOff,
  Car,
  ChevronDown,
  ChevronUp,
  Circle,
  Clock,
  Download,
  ExternalLink,
  Flashlight,
  FlipHorizontal,
  Image as ImageIcon,
  LayoutDashboard,
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
  Send,
  ShieldCheck,
  Sliders,
  Sparkles,
  Square,
  Unplug,
  Video,
  Volume2,
  VolumeX,
  Zap,
  ZoomIn,
} from 'lucide-react';
import { normalizePairingCode } from '../crypto/cryptoEngine';
import { removePairedDevice } from '../services/deviceVault';
import {
  loadParentAccountSession,
  loginParentAccount,
  logoutParentAccount,
  registerParentAccount,
  removeAccountChild,
  renameAccountChild,
  syncParentAccountLive,
  upsertAccountChild,
  type ChildLiveStatus,
  type ParentAccountSession,
} from '../services/parentAccountService';
import { ParentPairingSession } from '../services/parentPairingService';
import type { BrokerHealth } from '../services/signalingService';
import { SentinelWebRtcController } from '../services/webrtcController';
import type {
  ActiveAccessGrant,
  AppUsageEntry,
  AuthErrMsg,
  CameraFacingMode,
  DataCallLogMsg,
  DataDrivingReportMsg,
  DataInsightsMsg,
  DataRecordingsMsg,
  DataScreenTimeMsg,
  DataSocialMessagesMsg,
  DataSocialThreadsMsg,
  DataSystemStatusMsg,
  DataTimelineMsg,
  DeviceTelemetry,
  DrivingEventItem,
  GalleryAlbumItem,
  GalleryPhotoItem,
  GpsTrackPoint,
  LocationHistoryBundle,
  MediaSourceMode,
  OfflineRecordingItem,
  PortalOperationalState,
  SharedContactItem,
  SharedLocationData,
  SmsMessageItem,
  SmsThreadItem,
  SnapshotItem,
  SocialThreadItem,
  StreamQualityPreset,
  TimelineCaptureItem,
} from '../types/sentinel';
import { buildLocationIntelligence } from '../utils/locationIntelligence';
import { CallsTab } from './CallsTab';
import { AdvancedShieldPanel } from './AdvancedShieldPanel';
import { GeofenceZonesCard, ParentalHomeTab, ParentalRulesTab } from './ControlPanelTab';
import { DrivingScoreCard } from './DrivingScoreCard';
import { GalleryTab } from './GalleryTab';
import { GpsTrailMap } from './GpsTrailMap';
import { InsightsCard } from './InsightsCard';
import { KidsloxHeroControl } from './KidsloxHeroControl';
import { ParentFamilyHub } from './ParentFamilyHub';
import { ParentPushAlertsCard } from './ParentPushAlertsCard';
import { RecordingsTab } from './RecordingsTab';
import { SmsMessagesTab } from './SmsMessagesTab';
import { SocialMessagesTab } from './SocialMessagesTab';
import { SystemCommandCard } from './SystemCommandCard';
import { TimelineTab } from './TimelineTab';
import { WallpaperCard } from './WallpaperCard';

interface ControlStationViewProps {
  onBrokerHealthChange: (connected: boolean, brokers: BrokerHealth[]) => void;
  initialCode?: string | null;
}

type ViewerTab = 'home' | 'messages' | 'media' | 'location' | 'rules';

export const ControlStationView: React.FC<ControlStationViewProps> = ({
  onBrokerHealthChange,
  initialCode,
}) => {
  const [activeCode, setActiveCode] = useState('');
  const [account, setAccount] = useState<ParentAccountSession | null>(() =>
    loadParentAccountSession()
  );
  const accountRef = useRef<ParentAccountSession | null>(account);
  accountRef.current = account;
  const [liveStatuses, setLiveStatuses] = useState<Record<string, ChildLiveStatus>>({});
  const [accountSyncing, setAccountSyncing] = useState(false);

  const [portalState, setPortalState] = useState<PortalOperationalState>('offline');
  const [telemetry, setTelemetry] = useState<DeviceTelemetry | null>(null);
  const [sessionConnected, setSessionConnected] = useState(false);
  const [connectedDeviceName, setConnectedDeviceName] = useState('');
  const [authError, setAuthError] = useState<AuthErrMsg | null>(null);
  const [streamNotice, setStreamNotice] = useState<string | null>(null);
  const [pushSentFeedback, setPushSentFeedback] = useState<string | null>(null);
  const [accessGrant, setAccessGrant] = useState<ActiveAccessGrant | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [viewerTab, setViewerTab] = useState<ViewerTab>('home');
  const [messagesSubTab, setMessagesSubTab] = useState<'sms' | 'social' | 'calls' | 'insights'>('sms');
  const [mediaSubTab, setMediaSubTab] = useState<'live' | 'photos' | 'timeline' | 'recordings'>('photos');
  const [locationSubTab, setLocationSubTab] = useState<'map' | 'places' | 'driving'>('map');
  const [rulesSubTab, setRulesSubTab] = useState<'shield' | 'system' | 'protection'>('shield');
  const [offlineQueueCount, setOfflineQueueCount] = useState(0);

  // Social Messages Archive + Direct Reply State (v24/v25)
  const [socialThreadsData, setSocialThreadsData] = useState<DataSocialThreadsMsg | null>(null);
  const [socialMessagesData, setSocialMessagesData] = useState<DataSocialMessagesMsg | null>(null);
  const [socialThreadsLoading, setSocialThreadsLoading] = useState(false);
  const [socialMessagesLoading, setSocialMessagesLoading] = useState(false);
  const [socialReplyFeedback, setSocialReplyFeedback] = useState<{ ok: boolean; text: string } | null>(null);

  // Conversation & Behavioral Insights State (v24/v25)
  const [insightsData, setInsightsData] = useState<DataInsightsMsg | null>(null);
  const [insightsLoading, setInsightsLoading] = useState(false);

  // Driving Behavior & Safety Score State (v25)
  const [drivingReport, setDrivingReport] = useState<DataDrivingReportMsg | null>(null);
  const [drivingLoading, setDrivingLoading] = useState(false);
  const [drivingDays, setDrivingDays] = useState(7);

  // Visual Activity Timeline State (v24/v26)
  const [timelineData, setTimelineData] = useState<DataTimelineMsg | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineHourFilter, setTimelineHourFilter] = useState<number | null>(null);
  const [timelineLightbox, setTimelineLightbox] = useState<{
    item: TimelineCaptureItem;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null>(null);

  // Call Log & Number Blocker State
  const [callLogData, setCallLogData] = useState<DataCallLogMsg | null>(null);
  const [callLogLoading, setCallLogLoading] = useState(false);

  // Offline Deferred Recordings State
  const [recordingsData, setRecordingsData] = useState<DataRecordingsMsg | null>(null);
  const [recordingsLoading, setRecordingsLoading] = useState(false);
  const [recordingDownloadProgress, setRecordingDownloadProgress] = useState<{
    recordingId: string;
    received: number;
    total: number;
  } | null>(null);
  const [activeRecordingMedia, setActiveRecordingMedia] = useState<{
    item: OfflineRecordingItem;
    mimeType: string;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null>(null);

  // Hardware & System Command Center State
  const [systemStatus, setSystemStatus] = useState<DataSystemStatusMsg | null>(null);
  const [systemStatusLoading, setSystemStatusLoading] = useState(false);

  // Add Child Pairing (Code + Official MDM QR Enrollment)
  const [pairingActiveCode, setPairingActiveCode] = useState<string | null>(null);
  const [pairingStatus, setPairingStatus] = useState<string | null>(null);
  const [pairingMdmSession, setPairingMdmSession] = useState<{
    pairCode: string;
    deviceCode: string;
    familyKey: string;
    adminPin: string;
    childName: string;
  } | null>(null);
  const pairingSessionRef = useRef<ParentPairingSession | null>(null);

  // SMS Messages App State
  const [smsThreads, setSmsThreads] = useState<SmsThreadItem[]>([]);
  const [smsThreadsLoading, setSmsThreadsLoading] = useState(false);
  const [smsThreadsError, setSmsThreadsError] = useState<string | null>(null);
  const [activeSmsThread, setActiveSmsThread] = useState<SmsThreadItem | null>(null);
  const activeSmsThreadRef = useRef<SmsThreadItem | null>(null);
  activeSmsThreadRef.current = activeSmsThread;
  const [smsMessages, setSmsMessages] = useState<SmsMessageItem[]>([]);
  const [smsMessagesLoading, setSmsMessagesLoading] = useState(false);
  const [smsMessagesError, setSmsMessagesError] = useState<string | null>(null);
  const [smsSending, setSmsSending] = useState(false);
  const [smsSendFeedback, setSmsSendFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const [contacts, setContacts] = useState<SharedContactItem[]>([]);
  const [contactsLoading, setContactsLoading] = useState(false);

  // Remote Photo Gallery + Locked Quarantine Vault
  const [galleryItems, setGalleryItems] = useState<GalleryPhotoItem[]>([]);
  const [quarantineItems, setQuarantineItems] = useState<GalleryPhotoItem[]>([]);
  const [galleryAlbums, setGalleryAlbums] = useState<GalleryAlbumItem[]>([]);
  const [galleryAlbum, setGalleryAlbum] = useState('');
  const [galleryPage, setGalleryPage] = useState(0);
  const [galleryTotal, setGalleryTotal] = useState(0);
  const [quarantineCount, setQuarantineCount] = useState(0);
  const [galleryHasMore, setGalleryHasMore] = useState(false);
  const [galleryLoading, setGalleryLoading] = useState(false);
  const [quarantineLoading, setQuarantineLoading] = useState(false);
  const [galleryError, setGalleryError] = useState<string | null>(null);
  const [galleryCanManageFiles, setGalleryCanManageFiles] = useState(true);
  const [galleryActionBusy, setGalleryActionBusy] = useState(false);
  const [galleryActionFeedback, setGalleryActionFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const pendingGalleryTargetIdRef = useRef<string | null>(null);
  const [lightboxPhoto, setLightboxPhoto] = useState<{
    item: GalleryPhotoItem;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null>(null);

  // Location & Device Info
  const [locationData, setLocationData] = useState<SharedLocationData | null>(null);
  const [locationLoading, setLocationLoading] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [historyRange, setHistoryRange] = useState<'today' | '24h' | '7d' | '30d'>('24h');
  const [historyBundle, setHistoryBundle] = useState<LocationHistoryBundle | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [focusedTrackPoint, setFocusedTrackPoint] = useState<GpsTrackPoint | null>(null);
  const [historyListOpen, setHistoryListOpen] = useState(false);

  // Camera / Screen / Talkie Controls
  const [videoZoom, setVideoZoom] = useState<number>(1);
  const [isRecording, setIsRecording] = useState(false);
  const [ttsOpen, setTtsOpen] = useState(false);
  const [ttsText, setTtsText] = useState('');
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);

  // Screen Time & Parental Rules
  const [screenTime, setScreenTime] = useState<DataScreenTimeMsg | null>(null);
  const [screenTimeLoading, setScreenTimeLoading] = useState(false);
  const [controlFeedback, setControlFeedback] = useState<{ ok: boolean; text: string } | null>(null);

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
            name: t.accessName ?? prev?.name ?? 'Parent',
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
        setSessionConnected(true);
        setConnectedDeviceName(info.deviceName);
        if (info.accessGrant.allowDeviceControl) {
          setViewerTab('home');
          setScreenTimeLoading(true);
          void ctrl.requestScreenTime();
          window.setTimeout(() => setScreenTimeLoading(false), 8000);
        } else {
          setViewerTab('media');
          setMediaSubTab('live');
        }
      },
      onAuthError: (err) => {
        setAuthError(err);
        if (err.reason === 'ACCESS_EXPIRED' || err.reason === 'ACCESS_REVOKED') {
          setSessionConnected(false);
          setAccessGrant(null);
          setTelemetry(null);
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
      onStatsUpdate: () => {},
      onContactsData: (data) => {
        setContactsLoading(false);
        if (!data.error) {
          setContacts(data.contacts || []);
        }
      },
      onSmsThreadsData: (data) => {
        setSmsThreadsLoading(false);
        if (data.error) {
          setSmsThreadsError(data.error);
        } else {
          setSmsThreadsError(null);
          setSmsThreads(data.threads || []);
        }
      },
      onSmsMessagesData: (data) => {
        setSmsMessagesLoading(false);
        if (data.error) {
          setSmsMessagesError(data.error);
        } else {
          setSmsMessagesError(null);
          setSmsMessages(data.messages || []);
        }
      },
      onSmsResult: (res) => {
        setSmsSending(false);
        if (res.success) {
          setSmsSendFeedback({ ok: true, text: 'Message envoyé.' });
          window.setTimeout(() => setSmsSendFeedback(null), 2500);
          // Refresh open conversation + threads after brief delay so Android writes Sent SMS
          window.setTimeout(() => {
            const cur = activeSmsThreadRef.current;
            if (cur) {
              void ctrl.requestSmsMessages(cur.threadId, cur.address);
            }
            void ctrl.requestSmsThreads();
          }, 1400);
        } else {
          setSmsSendFeedback({ ok: false, text: res.error || 'Échec de l’envoi.' });
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
      onScreenTimeData: (data) => {
        setScreenTimeLoading(false);
        setScreenTime(data);
        if (data.error) {
          setControlFeedback({ ok: false, text: data.error });
        }
      },
      onControlResult: (res) => {
        if (res.action.startsWith('gallery_')) {
          setGalleryActionBusy(false);
          setGalleryActionFeedback({
            ok: res.success,
            text: res.error || (res.success ? 'Action appliquée.' : 'Action impossible.'),
          });
          if (res.success) {
            const targetId = pendingGalleryTargetIdRef.current;
            pendingGalleryTargetIdRef.current = null;
            if (targetId) {
              setGalleryItems((prev) => prev.filter((p) => p.id !== targetId));
              setQuarantineItems((prev) => prev.filter((p) => p.id !== targetId));
            }
            setLightboxPhoto(null);
            void ctrl.requestGallery(0, '', 18);
            void ctrl.performGalleryAction('list_quarantine');
          }
          return;
        }
        if (res.action === 'social_reply') {
          setSocialReplyFeedback({
            ok: res.success,
            text: res.error || (res.success ? 'Réponse envoyée directement.' : 'Envoi impossible.'),
          });
          if (res.success) {
            window.setTimeout(() => setSocialReplyFeedback(null), 3500);
          }
          return;
        }
        if (res.action === 'unpair_decision' && res.success && pendingUnpairCodeRef.current) {
          const targetCode = pendingUnpairCodeRef.current;
          setControlFeedback({
            ok: true,
            text: res.error || 'Tous les droits parentaux ont été supprimés.',
          });
          window.setTimeout(() => finalizeUnpairCleanup(targetCode), 350);
          return;
        }
        if (res.success) {
          const labels: Record<string, string> = {
            lock_screen: 'Écran verrouillé.',
            screen_pause: 'État de pause mis à jour.',
            clear_locks: 'Tous les blocages ont été levés.',
            set_admin_pin: 'Code administrateur mis à jour.',
            curfew: 'Couvre-feu enregistré.',
            app_quota: 'Quota quotidien mis à jour.',
            app_group_quota: 'Quota de groupe enregistré.',
            school_mode: 'Mode École mis à jour.',
            anti_bypass: 'Protection Paramètres mise à jour.',
            lost_mode: 'Mode Perdu mis à jour.',
            time_request: 'Réponse envoyée.',
            geofence: 'Zone de sécurité enregistrée.',
            clear_events: 'Journal effacé.',
            ai_shield: res.error || 'Filtre d’écran mis à jour.',
            remote_policy: 'Autorisations mises à jour.',
            unpair_decision: res.error || 'Décision appliquée.',
            flash_screen: res.error || 'Affiché sur l’écran.',
            call_block: res.error || 'Filtrage d’appels mis à jour.',
            recording_start: res.error || 'Enregistrement démarré.',
            recording_stop: res.error || 'Enregistrement sauvegardé.',
            recording_delete: res.error || 'Enregistrement supprimé.',
            system_control: res.error || 'Paramètre système mis à jour.',
            social_reply: res.error || 'Réponse envoyée directement.',
            social_keywords: res.error || 'Mots-clés d’alerte mis à jour.',
            social_clear: res.error || 'Archive réseaux effacée.',
            timeline_config: res.error || 'Réglages de captures enregistrés.',
            timeline_delete: res.error || 'Capture supprimée.',
            timeline_clear: res.error || 'Toutes les captures ont été effacées.',
            set_wallpaper: res.error || 'Fond d’écran appliqué.',
            advanced_controls: res.error || 'Contrôle avancé mis à jour.',
          };
          setControlFeedback({ ok: true, text: labels[res.action] || 'Règle enregistrée.' });
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
          if (data.quarantineCount !== undefined) setQuarantineCount(data.quarantineCount);
          if (data.canManageFiles !== undefined) setGalleryCanManageFiles(Boolean(data.canManageFiles));
          setGalleryHasMore(Boolean(data.hasMore));
          setGalleryPage(data.page ?? 0);
          setGalleryItems((prev) =>
            data.page > 0 ? [...prev, ...(data.items || [])] : data.items || []
          );
        }
      },
      onQuarantineListData: (data) => {
        setQuarantineLoading(false);
        if (!data.error) {
          setQuarantineItems(data.items || []);
          setQuarantineCount(data.totalCount ?? data.items?.length ?? 0);
        }
      },
      onGalleryPhotoData: (info) => {
        setLightboxPhoto((prev) => {
          if (!prev || prev.item.id !== info.mediaId) return prev;
          return { ...prev, loading: false, base64: info.base64, error: info.error };
        });
      },
      onCallLogData: (data) => {
        setCallLogLoading(false);
        setCallLogData(data);
      },
      onRecordingsData: (data) => {
        setRecordingsLoading(false);
        setRecordingsData(data);
      },
      onRecordingFileProgress: (prog) => {
        setRecordingDownloadProgress(prog);
      },
      onRecordingFileData: (info) => {
        setRecordingDownloadProgress(null);
        setActiveRecordingMedia((prev) => {
          if (!prev || prev.item.id !== info.recordingId) return prev;
          return {
            ...prev,
            mimeType: info.mimeType || prev.mimeType,
            loading: false,
            base64: info.base64,
            error: info.error,
          };
        });
      },
      onSystemStatusData: (data) => {
        setSystemStatusLoading(false);
        setSystemStatus(data);
      },
      onSocialThreadsData: (data) => {
        setSocialThreadsLoading(false);
        setSocialThreadsData(data);
      },
      onSocialMessagesData: (data) => {
        setSocialMessagesLoading(false);
        setSocialMessagesData(data);
      },
      onInsightsData: (data) => {
        setInsightsLoading(false);
        setInsightsData(data);
      },
      onTimelineData: (data) => {
        setTimelineLoading(false);
        setTimelineData(data);
      },
      onTimelineImageData: (info) => {
        setTimelineLightbox((prev) => {
          if (!prev || prev.item.id !== info.captureId) return prev;
          return { ...prev, loading: false, base64: info.base64, error: info.error };
        });
      },
      onDrivingReportData: (data) => {
        setDrivingLoading(false);
        setDrivingReport(data);
      },
      onStreamError: (msg) => setStreamNotice(msg),
      onTalkieStateChange: (active) => {
        setTalkieActive(active);
        if (!active) setTalkieLocked(false);
      },
      onTalkieLevel: (lvl) => setTalkieLevel(lvl),
      onOfflineQueueChange: (count) => setOfflineQueueCount(count),
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
      setActiveCode(clean);
      void controllerRef.current?.connectToPortal(clean, false);
    }
  }, [initialCode]);

  const fullCode = activeCode;

  const handleSyncAccountLive = async (targetSession = accountRef.current) => {
    if (!targetSession) return;
    setAccountSyncing(true);
    try {
      const { session: synced, liveStatuses: fresh } = await syncParentAccountLive(
        targetSession,
        (st) => {
          setLiveStatuses((prev) => ({ ...prev, [st.deviceCode]: st }));
        }
      );
      setAccount(synced);
      setLiveStatuses((prev) => ({ ...prev, ...fresh }));
    } finally {
      setAccountSyncing(false);
    }
  };

  useEffect(() => {
    if (account && !sessionConnected && portalState === 'offline') {
      void handleSyncAccountLive(account);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account?.email]);

  const handleConnect = async (targetCode: string) => {
    const normalized = normalizePairingCode(targetCode);
    if (normalized.length !== 6) return;

    setAuthError(null);
    setStreamNotice(null);
    setPushSentFeedback(null);
    setViewerTab('home');
    setMessagesSubTab('sms');
    setMediaSubTab('photos');
    setLocationSubTab('map');
    setRulesSubTab('protection');
    setSmsThreads([]);
    setActiveSmsThread(null);
    setSmsMessages([]);
    setCallLogData(null);
    setRecordingsData(null);
    setActiveRecordingMedia(null);
    setSystemStatus(null);
    setSocialThreadsData(null);
    setSocialMessagesData(null);
    setSocialReplyFeedback(null);
    setInsightsData(null);
    setDrivingReport(null);
    setTimelineHourFilter(null);
    setTimelineData(null);
    setTimelineLightbox(null);
    setContacts([]);
    setGalleryItems([]);
    setQuarantineItems([]);
    setGalleryCanManageFiles(true);
    setGalleryActionBusy(false);
    setGalleryActionFeedback(null);
    setLocationData(null);
    setHistoryBundle(null);
    setFocusedTrackPoint(null);
    setScreenTime(null);
    setControlFeedback(null);
    setActiveCode(normalized);
    try {
      await controllerRef.current?.connectToPortal(normalized, false);
    } catch {
      // Handled via state callbacks
    }
  };

  const handleSendPushInvite = async () => {
    if (fullCode.length !== 6) return;
    setPushSentFeedback('Réveil envoyé…');
    await controllerRef.current?.sendServerlessPushInvitation(fullCode);
    setPushSentFeedback('Signal de réveil envoyé.');
    window.setTimeout(() => setPushSentFeedback(null), 3500);
  };

  const handleDisconnect = () => {
    setTalkieLocked(false);
    setTalkieActive(false);
    const prevCode = fullCode;
    controllerRef.current?.disconnect();
    setTelemetry(null);
    setSessionConnected(false);
    setConnectedDeviceName('');
    setAccessGrant(null);
    setStreamNotice(null);
    setActiveCode('');
    if (prevCode) {
      // Never leave a disconnected direct-code device lingering in local history
      if (!accountRef.current) {
        removePairedDevice(prevCode);
      }
    }
    if (accountRef.current) {
      void handleSyncAccountLive(accountRef.current);
    }
  };

  const handleLoginParentAccount = async (email: string, password: string) => {
    const { session, liveStatuses: fresh } = await loginParentAccount({
      email,
      password,
      onLiveChild: (st) => {
        setLiveStatuses((prev) => ({ ...prev, [st.deviceCode]: st }));
      },
    });
    setAccount(session);
    setLiveStatuses(fresh);
  };

  const handleRegisterParentAccount = async (
    email: string,
    password: string,
    displayName: string
  ) => {
    const session = await registerParentAccount({ email, password, displayName });
    setAccount(session);
    setLiveStatuses({});
  };

  const handleLogoutParentAccount = () => {
    pairingSessionRef.current?.stop();
    setPairingActiveCode(null);
    setPairingStatus(null);
    logoutParentAccount();
    setAccount(null);
    setLiveStatuses({});
  };

  const handleRenameChild = async (code: string, newName: string) => {
    if (!accountRef.current) return;
    const updated = await renameAccountChild(accountRef.current, code, newName);
    setAccount(updated);
  };

  const handleRemoveChild = async (code: string) => {
    removePairedDevice(code);
    setLiveStatuses((prev) => {
      const next = { ...prev };
      delete next[code];
      return next;
    });
    if (!accountRef.current) return;
    const updated = await removeAccountChild(accountRef.current, code);
    setAccount(updated);
  };

  const handleStartAddChild = async (childName: string, adminPin: string) => {
    const curAccount = accountRef.current;
    if (!curAccount) return;
    pairingSessionRef.current?.stop();
    const session = new ParentPairingSession({
      familyKey: curAccount.familyKey,
      adminPin,
      onStatus: (msg) => setPairingStatus(msg),
      onPaired: (info) => {
        setPairingActiveCode(null);
        setPairingStatus(null);
        setPairingMdmSession(null);
        const now = Date.now();
        if (accountRef.current) {
          void upsertAccountChild(accountRef.current, {
            pairingCode: info.deviceCode,
            deviceId: info.deviceId,
            deviceName: info.deviceName,
            childName: childName.trim() || info.deviceName,
            pairedAt: now,
            lastConnectedAt: now,
          }).then((updated) => setAccount(updated));
        }
        void handleConnect(info.deviceCode);
      },
    });
    pairingSessionRef.current = session;
    setPairingActiveCode(session.getCode());
    setPairingMdmSession({
      pairCode: session.getCode(),
      deviceCode: session.getAssignedDeviceCode(),
      familyKey: session.getFamilyKey(),
      adminPin: session.getAdminPin(),
      childName: childName.trim() || `Enfant ${(curAccount.children.length ?? 0) + 1}`,
    });
    setPairingStatus('En attente du smartphone…');
    await session.start();
  };

  const handlePreRegisterMdmChild = async () => {
    const meta = pairingMdmSession;
    const curAccount = accountRef.current;
    if (!meta || !curAccount) return;
    const now = Date.now();
    const updated = await upsertAccountChild(curAccount, {
      pairingCode: meta.deviceCode,
      deviceId: `mdm-${meta.deviceCode.toLowerCase()}`,
      deviceName: `${meta.childName} (MDM)`,
      childName: meta.childName,
      pairedAt: now,
      lastConnectedAt: now,
    });
    setAccount(updated);
    handleCancelParentPairing();
  };

  const handleCancelParentPairing = () => {
    pairingSessionRef.current?.stop();
    pairingSessionRef.current = null;
    setPairingActiveCode(null);
    setPairingMdmSession(null);
    setPairingStatus(null);
  };

  const isSessionOpen = sessionConnected || portalState === 'standby' || portalState === 'streaming';
  const isStreaming = portalState === 'streaming';
  const canUseAudio = accessGrant ? accessGrant.allowAudio : true;
  const canSwitchCam = accessGrant ? accessGrant.allowSwitchCamera : true;
  const canViewScreen = accessGrant ? accessGrant.allowScreenShare : false;
  const canViewLocation = accessGrant ? accessGrant.allowLocation : false;
  const canUseIntercom = accessGrant ? accessGrant.allowIntercom : true;
  const canUseSmsRelay = accessGrant ? accessGrant.allowSmsRelay : false;
  const canUseDeviceControl = accessGrant ? accessGrant.allowDeviceControl : true;

  /* ==================== TAB HANDLERS ==================== */
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
    if (tab === 'rules' && rulesSubTab === 'system' && !systemStatus && !systemStatusLoading) {
      setSystemStatusLoading(true);
      void controllerRef.current?.sendSystemControl({ action: 'get_status' });
      window.setTimeout(() => setSystemStatusLoading(false), 10000);
    }
  };

  const handleFetchSmsThreads = async () => {
    setSmsThreadsLoading(true);
    setSmsThreadsError(null);
    await controllerRef.current?.requestSmsThreads();
    window.setTimeout(() => setSmsThreadsLoading(false), 10000);
  };

  const handleOpenMessagesTab = () => {
    setViewerTab('messages');
    if (messagesSubTab === 'sms' && smsThreads.length === 0 && !smsThreadsLoading) {
      void handleFetchSmsThreads();
    } else if (messagesSubTab === 'social' && !socialThreadsData && !socialThreadsLoading) {
      setSocialThreadsLoading(true);
      void controllerRef.current?.performSocialAction({ action: 'list_threads' });
      window.setTimeout(() => setSocialThreadsLoading(false), 10000);
    } else if (messagesSubTab === 'calls' && !callLogData && !callLogLoading) {
      setCallLogLoading(true);
      void controllerRef.current?.requestCallLog();
      window.setTimeout(() => setCallLogLoading(false), 10000);
    } else if (messagesSubTab === 'insights' && !insightsData && !insightsLoading) {
      setInsightsLoading(true);
      void controllerRef.current?.requestInsights();
      window.setTimeout(() => setInsightsLoading(false), 10000);
    }
  };

  const handleOpenSmsThread = async (thread: SmsThreadItem) => {
    setActiveSmsThread(thread);
    setSmsMessages([]);
    setSmsMessagesLoading(true);
    setSmsMessagesError(null);
    await controllerRef.current?.requestSmsMessages(thread.threadId, thread.address);
    window.setTimeout(() => setSmsMessagesLoading(false), 10000);
  };

  const handleReplySms = async (address: string, contactName: string, body: string) => {
    setSmsSending(true);
    setSmsSendFeedback(null);
    await controllerRef.current?.sendRemoteSms({
      phoneNumber: address,
      contactName: contactName || undefined,
      message: body,
      includeGpsLink: false,
    });
    window.setTimeout(() => setSmsSending(false), 12000);
  };

  const handleFetchGallery = async (page = 0, album = galleryAlbum) => {
    setGalleryAlbum(album);
    setGalleryLoading(true);
    setGalleryError(null);
    await controllerRef.current?.requestGallery(page, album, 18);
    window.setTimeout(() => setGalleryLoading(false), 12000);
  };

  const handleFetchQuarantine = async () => {
    setQuarantineLoading(true);
    await controllerRef.current?.performGalleryAction('list_quarantine');
    window.setTimeout(() => setQuarantineLoading(false), 10000);
  };

  const handleOpenMediaTab = () => {
    setViewerTab('media');
    if (mediaSubTab === 'photos' && galleryItems.length === 0 && !galleryLoading) {
      void handleFetchGallery(0, '');
    } else if (mediaSubTab === 'timeline' && !timelineData && !timelineLoading) {
      setTimelineLoading(true);
      void controllerRef.current?.performTimelineAction({ action: 'list', page: 0 });
      window.setTimeout(() => setTimelineLoading(false), 10000);
    } else if (mediaSubTab === 'recordings' && !recordingsData && !recordingsLoading) {
      setRecordingsLoading(true);
      void controllerRef.current?.performRecordingAction({ action: 'list' });
      window.setTimeout(() => setRecordingsLoading(false), 10000);
    }
  };

  const handleSelectGalleryPhoto = async (item: GalleryPhotoItem) => {
    setLightboxPhoto({ item, loading: true });
    if (item.quarantined) {
      await controllerRef.current?.performGalleryAction('get_quarantine_photo', item.id);
    } else {
      await controllerRef.current?.requestGalleryPhoto(item.id);
    }
  };

  const handleFetchLocation = async () => {
    setLocationLoading(true);
    setLocationError(null);
    await controllerRef.current?.requestLocation();
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

  const handleFetchDrivingReport = async (days = drivingDays) => {
    setDrivingDays(days);
    setDrivingLoading(true);
    await controllerRef.current?.requestDrivingReport(days);
    window.setTimeout(() => setDrivingLoading(false), 10000);
  };

  const handleOpenLocationTab = () => {
    setViewerTab('location');
    if (!locationData && canViewLocation) {
      void handleFetchLocation();
      void handleFetchLocationHistory(historyRange);
    }
    if (!screenTime && canUseDeviceControl) {
      void handleFetchScreenTime();
    }
    if (!drivingReport && !drivingLoading && canUseDeviceControl) {
      void handleFetchDrivingReport(drivingDays);
    }
  };

  const locationIntelligence = useMemo(
    () => buildLocationIntelligence(historyBundle?.points ?? [], screenTime?.geofences ?? []),
    [historyBundle?.points, screenTime?.geofences]
  );

  const handleExportGpx = () => {
    const pts = historyBundle?.points ?? [];
    if (pts.length === 0) return;
    const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const name = esc(activeChildRecord?.childName || telemetry?.deviceName || 'Trajet');
    const trkpts = pts
      .map(
        (p) =>
          `      <trkpt lat="${p.latitude.toFixed(6)}" lon="${p.longitude.toFixed(6)}"><time>${new Date(
            p.timestamp
          ).toISOString()}</time></trkpt>`
      )
      .join('\n');
    const gpx = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="SentinelCam" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk>\n    <name>${name}</name>\n    <trkseg>\n${trkpts}\n    </trkseg>\n  </trk>\n</gpx>`;
    const blob = new Blob([gpx], { type: 'application/gpx+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `trajet-${historyRange}-${new Date().toISOString().slice(0, 10)}.gpx`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleExportCsv = () => {
    const pts = historyBundle?.points ?? [];
    if (pts.length === 0) return;
    const header = 'date,latitude,longitude,precision_m,vitesse_kmh,batterie_pct';
    const rows = pts.map(
      (p) =>
        `${new Date(p.timestamp).toISOString()},${p.latitude.toFixed(6)},${p.longitude.toFixed(6)},${p.accuracyMeters},${p.speedKmh.toFixed(1)},${p.batteryLevel}`
    );
    const blob = new Blob([[header, ...rows].join('\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `trajet-${historyRange}-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const pendingUnpairCodeRef = useRef<string | null>(null);
  const unpairFallbackTimerRef = useRef<number | null>(null);

  const finalizeUnpairCleanup = (codeToRemove: string) => {
    if (unpairFallbackTimerRef.current !== null) {
      window.clearTimeout(unpairFallbackTimerRef.current);
      unpairFallbackTimerRef.current = null;
    }
    pendingUnpairCodeRef.current = null;
    if (codeToRemove.length === 6) {
      removePairedDevice(codeToRemove);
      if (accountRef.current) {
        void removeAccountChild(accountRef.current, codeToRemove).then((updated) =>
          setAccount(updated)
        );
      }
    }
    handleDisconnect();
  };

  const handleRespondUnpair = async (approve: boolean) => {
    setControlFeedback(null);
    if (approve) {
      const codeToRemove = fullCode;
      pendingUnpairCodeRef.current = codeToRemove;
      setControlFeedback({ ok: true, text: 'Révocation des droits sur le téléphone…' });
      await controllerRef.current?.respondUnpairRequest(true);
      // Fallback cleanup after 2.2s if phone already stopped its service right after wiping
      if (unpairFallbackTimerRef.current !== null) {
        window.clearTimeout(unpairFallbackTimerRef.current);
      }
      unpairFallbackTimerRef.current = window.setTimeout(() => {
        if (pendingUnpairCodeRef.current === codeToRemove) {
          finalizeUnpairCleanup(codeToRemove);
        }
      }, 2200);
    } else {
      await controllerRef.current?.respondUnpairRequest(false);
    }
  };

  /* ==================== MEDIA & TALKIE HANDLERS ==================== */
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

  const handleWakeMedia = async (mode: MediaSourceMode) => {
    setStreamNotice(null);
    setMediaMode(mode);
    setMirrorVideo(mode === 'camera' && cameraFacing === 'front');
    await controllerRef.current?.startCameraStream(
      canSwitchCam ? cameraFacing : 'front',
      canUseAudio ? remoteMicEnabled : false,
      quality,
      mode
    );
  };

  const handleSleepCamera = async () => {
    await controllerRef.current?.stopCameraStream();
  };

  const handleToggleFacing = async () => {
    if (!canSwitchCam) return;
    const nextFacing: CameraFacingMode = cameraFacing === 'front' ? 'back' : 'front';
    setCameraFacing(nextFacing);
    setMirrorVideo(nextFacing === 'front');
    if (isStreaming && mediaMode === 'camera') {
      await controllerRef.current?.switchCamera(nextFacing);
    }
  };

  const handleToggleRemoteMic = async () => {
    if (!canUseAudio) return;
    const next = !remoteMicEnabled;
    setRemoteMicEnabled(next);
    if (isStreaming) {
      await controllerRef.current?.toggleRemoteMicrophone(next);
    }
  };

  const handleCaptureSnapshot = () => {
    if (!videoRef.current || !controllerRef.current) return;
    const snap = controllerRef.current.captureSnapshot(
      videoRef.current,
      activeChildRecord?.childName || telemetry?.deviceName || 'Capture'
    );
    if (snap) setSnapshots((prev) => [snap, ...prev.slice(0, 7)]);
  };

  const handleFullscreen = () => {
    if (!videoContainerRef.current) return;
    if (!document.fullscreenElement) {
      void videoContainerRef.current.requestFullscreen().catch(() => {});
    } else {
      void document.exitFullscreen().catch(() => {});
    }
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
          a.download = `video-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.webm`;
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

  const handleSendTts = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ttsText.trim()) return;
    await controllerRef.current?.speakText(ttsText.trim());
    setTtsText('');
  };

  const activeChildRecord = account?.children.find((c) => c.pairingCode === fullCode) ?? null;

  /* ==================== SCREEN 1: PARENT ACCOUNT & MULTI-CHILD HUB ==================== */
  if (!isSessionOpen) {
    return (
      <ParentFamilyHub
        account={account}
        liveStatuses={liveStatuses}
        syncing={accountSyncing}
        portalState={portalState}
        connectingCode={fullCode}
        authError={authError}
        pushSentFeedback={pushSentFeedback}
        pairingActiveCode={pairingActiveCode}
        pairingStatus={pairingStatus}
        pairingMdmSession={pairingMdmSession}
        onLogin={handleLoginParentAccount}
        onRegister={handleRegisterParentAccount}
        onLogout={handleLogoutParentAccount}
        onSyncLive={() => handleSyncAccountLive()}
        onSelectChild={(code) => void handleConnect(code)}
        onRenameChild={handleRenameChild}
        onRemoveChild={handleRemoveChild}
        onStartAddChild={handleStartAddChild}
        onPreRegisterMdmChild={handlePreRegisterMdmChild}
        onCancelAddChild={handleCancelParentPairing}
        onDirectConnect={(code) => void handleConnect(code)}
        onSendWakePush={() => void handleSendPushInvite()}
        onCancelConnecting={handleDisconnect}
      />
    );
  }

  /* ==================== SCREEN 2: CONNECTED CHILD WORKSPACE (KIDSLOX 5-TAB STRUCTURE) ==================== */
  return (
    <div className="mx-auto max-w-5xl space-y-4 px-3.5 pb-24 pt-3.5 sm:px-6 sm:pb-10 sm:pt-5">
      {/* Clean Minimal Top Breadcrumb Row */}
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={handleDisconnect}
          className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50 dark:border-white/10 dark:bg-white/[0.06] dark:text-slate-200 dark:hover:bg-white/10"
          title={account ? 'Revenir à la liste des enfants' : 'Quitter'}
        >
          <Unplug className="h-3.5 w-3.5 text-sky-500" />
          <span>{account ? '← Mes enfants' : '← Changer d’appareil'}</span>
        </button>

        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={screenTimeLoading}
            onClick={() => void handleFetchScreenTime()}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50 disabled:opacity-40 dark:border-white/10 dark:bg-white/[0.06] dark:text-slate-200"
          >
            <RefreshCw className={`h-3.5 w-3.5 text-sky-500 ${screenTimeLoading ? 'animate-spin' : ''}`} />
            <span className="hidden sm:inline">Actualiser</span>
          </button>
        </div>
      </div>

      {/* Desktop Kidslox 5-Tab Clean Segmented Navigation */}
      <div className="hidden grid-cols-5 gap-1.5 rounded-2xl border border-slate-200/80 bg-white p-1.5 shadow-sm dark:border-white/[0.08] dark:bg-[#111827] sm:grid">
        <button
          type="button"
          disabled={!canUseDeviceControl}
          onClick={() => handleOpenHomeOrRulesTab('home')}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-semibold transition disabled:opacity-35 ${
            viewerTab === 'home'
              ? 'bg-sky-500 text-white kl-solid-white shadow-sm shadow-sky-500/25'
              : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/[0.06]'
          }`}
        >
          <LayoutDashboard className="h-4 w-4 shrink-0" />
          <span>Accueil</span>
          {(telemetry?.unpairRequestPending || (telemetry?.pendingRequestsCount ?? 0) > 0) && (
            <span
              className={`rounded-full px-1.5 py-0.5 font-mono text-[10px] font-bold ${
                telemetry?.unpairRequestPending
                  ? 'bg-rose-500 text-white kl-solid-white'
                  : viewerTab === 'home'
                    ? 'bg-white/25 text-white kl-solid-white'
                    : 'bg-emerald-500 text-white kl-solid-white'
              }`}
            >
              {telemetry?.unpairRequestPending ? '!' : `+${telemetry?.pendingRequestsCount}`}
            </span>
          )}
        </button>

        <button
          type="button"
          disabled={!canUseDeviceControl}
          onClick={() => {
            handleOpenHomeOrRulesTab('rules');
            if (!systemStatus && !systemStatusLoading) {
              setSystemStatusLoading(true);
              void controllerRef.current?.sendSystemControl({ action: 'get_status' });
              window.setTimeout(() => setSystemStatusLoading(false), 10000);
            }
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-semibold transition disabled:opacity-35 ${
            viewerTab === 'rules'
              ? 'bg-sky-500 text-white kl-solid-white shadow-sm shadow-sky-500/25'
              : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/[0.06]'
          }`}
        >
          <ShieldCheck className="h-4 w-4 shrink-0" />
          <span>Blindage</span>
          {(telemetry?.securityAlertsCount ?? 0) > 0 && (
            <span
              className={`rounded-full px-1.5 py-0.5 font-mono text-[10px] font-bold ${
                viewerTab === 'rules'
                  ? 'bg-white/25 text-white kl-solid-white'
                  : 'bg-amber-500 text-white kl-solid-white'
              }`}
            >
              {telemetry?.securityAlertsCount}
            </span>
          )}
        </button>

        <button
          type="button"
          disabled={!canViewLocation}
          onClick={handleOpenLocationTab}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-semibold transition disabled:opacity-35 ${
            viewerTab === 'location'
              ? 'bg-sky-500 text-white kl-solid-white shadow-sm shadow-sky-500/25'
              : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/[0.06]'
          }`}
        >
          <MapPin className="h-4 w-4 shrink-0" />
          <span>Position</span>
        </button>

        <button
          type="button"
          onClick={handleOpenMessagesTab}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-semibold transition ${
            viewerTab === 'messages'
              ? 'bg-sky-500 text-white kl-solid-white shadow-sm shadow-sky-500/25'
              : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/[0.06]'
          }`}
        >
          <MessageSquare className="h-4 w-4 shrink-0" />
          <span>Messages</span>
        </button>

        <button
          type="button"
          onClick={handleOpenMediaTab}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-semibold transition ${
            viewerTab === 'media'
              ? 'bg-sky-500 text-white kl-solid-white shadow-sm shadow-sky-500/25'
              : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-white/[0.06]'
          }`}
        >
          <ImageIcon className="h-4 w-4 shrink-0" />
          <span>Médias</span>
        </button>
      </div>

      {/* Mobile Kidslox Fixed Bottom 5-Tab Bar */}
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200/90 bg-white/95 pb-[env(safe-area-inset-bottom)] shadow-[0_-4px_20px_rgba(15,23,42,0.06)] backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#0b1120]/95 sm:hidden">
        <div className="grid grid-cols-5 px-1.5 py-1.5">
          <button
            type="button"
            disabled={!canUseDeviceControl}
            onClick={() => handleOpenHomeOrRulesTab('home')}
            className={`relative flex flex-col items-center justify-center gap-0.5 rounded-xl py-1 text-[10px] font-semibold transition disabled:opacity-35 ${
              viewerTab === 'home' ? 'text-sky-600 dark:text-sky-400' : 'text-slate-400'
            }`}
          >
            <div className="relative">
              <LayoutDashboard className="h-5 w-5" />
              {(telemetry?.unpairRequestPending || (telemetry?.pendingRequestsCount ?? 0) > 0) && (
                <span className="absolute -right-2 -top-1 rounded-full bg-rose-500 px-1 font-mono text-[9px] font-bold text-white kl-solid-white">
                  {telemetry?.unpairRequestPending ? '!' : telemetry?.pendingRequestsCount}
                </span>
              )}
            </div>
            <span>Accueil</span>
          </button>

          <button
            type="button"
            disabled={!canUseDeviceControl}
            onClick={() => {
              handleOpenHomeOrRulesTab('rules');
              if (!systemStatus && !systemStatusLoading) {
                setSystemStatusLoading(true);
                void controllerRef.current?.sendSystemControl({ action: 'get_status' });
                window.setTimeout(() => setSystemStatusLoading(false), 10000);
              }
            }}
            className={`relative flex flex-col items-center justify-center gap-0.5 rounded-xl py-1 text-[10px] font-semibold transition disabled:opacity-35 ${
              viewerTab === 'rules' ? 'text-sky-600 dark:text-sky-400' : 'text-slate-400'
            }`}
          >
            <div className="relative">
              <ShieldCheck className="h-5 w-5" />
              {(telemetry?.securityAlertsCount ?? 0) > 0 && (
                <span className="absolute -right-2 -top-1 rounded-full bg-amber-500 px-1 font-mono text-[9px] font-bold text-white kl-solid-white">
                  {telemetry?.securityAlertsCount}
                </span>
              )}
            </div>
            <span>Blindage</span>
          </button>

          <button
            type="button"
            disabled={!canViewLocation}
            onClick={handleOpenLocationTab}
            className={`flex flex-col items-center justify-center gap-0.5 rounded-xl py-1 text-[10px] font-semibold transition disabled:opacity-35 ${
              viewerTab === 'location' ? 'text-sky-600 dark:text-sky-400' : 'text-slate-400'
            }`}
          >
            <MapPin className="h-5 w-5" />
            <span>Position</span>
          </button>

          <button
            type="button"
            onClick={handleOpenMessagesTab}
            className={`flex flex-col items-center justify-center gap-0.5 rounded-xl py-1 text-[10px] font-semibold transition ${
              viewerTab === 'messages' ? 'text-sky-600 dark:text-sky-400' : 'text-slate-400'
            }`}
          >
            <MessageSquare className="h-5 w-5" />
            <span>Messages</span>
          </button>

          <button
            type="button"
            onClick={handleOpenMediaTab}
            className={`flex flex-col items-center justify-center gap-0.5 rounded-xl py-1 text-[10px] font-semibold transition ${
              viewerTab === 'media' ? 'text-sky-600 dark:text-sky-400' : 'text-slate-400'
            }`}
          >
            <ImageIcon className="h-5 w-5" />
            <span>Médias</span>
          </button>
        </div>
      </nav>

      {/* Instant Phone Notifications Without Opening the Website */}
      <ParentPushAlertsCard
        familyKey={account?.familyKey}
        childCode={activeCode}
        compact
      />

      {/* ==================== TAB 1: ACCUEIL (KIDSLOX HERO + SCREEN TIME) ==================== */}
      {viewerTab === 'home' && (
        <div className="space-y-4">
          <KidsloxHeroControl
            deviceName={activeChildRecord?.childName || telemetry?.deviceName || connectedDeviceName || 'Appareil'}
            portalState={portalState}
            telemetry={telemetry}
            screenTime={screenTime}
            nowMs={nowMs}
            offlineQueueCount={offlineQueueCount}
            onSelectMode={async (mode) => {
              setControlFeedback(null);
              const res = await controllerRef.current?.setAdvancedControls({
                deviceMode: mode,
                lockMinutes: 120,
                parentFreeMinutes: 60,
              });
              if (res?.queued) {
                setControlFeedback({
                  ok: true,
                  text: 'Appareil hors ligne : mode mis en file d’attente, s’appliquera dès reconnexion.',
                });
              }
            }}
            onToggleReadOnly={async (minutes) => {
              setControlFeedback(null);
              const res = await controllerRef.current?.setAdvancedControls({ readOnlyMinutes: minutes });
              if (res?.queued) {
                setControlFeedback({
                  ok: true,
                  text: 'Gel tactile mis en file d’attente hors ligne.',
                });
              }
            }}
            onToggleSchoolMode={async (minutes) => {
              setControlFeedback(null);
              await controllerRef.current?.setRemoteSchoolMode({ durationMinutes: minutes });
            }}
            onLockScreenNow={async () => {
              setControlFeedback(null);
              await controllerRef.current?.lockRemoteScreen();
            }}
            onRingDevice={async () => {
              await controllerRef.current?.ringDevice(!(telemetry?.ringingActive ?? false), 15);
            }}
            onOpenShieldTab={() => {
              setViewerTab('rules');
              setRulesSubTab('shield');
              if (!systemStatus && !systemStatusLoading) {
                setSystemStatusLoading(true);
                void controllerRef.current?.sendSystemControl({ action: 'get_status' });
                window.setTimeout(() => setSystemStatusLoading(false), 10000);
              }
            }}
          />

          <ParentalHomeTab
          screenTime={screenTime}
          screenTimeLoading={screenTimeLoading}
          telemetry={telemetry}
          nowMs={nowMs}
          feedback={controlFeedback}
          onDismissFeedback={() => setControlFeedback(null)}
          onRefresh={handleFetchScreenTime}
          onLockScreenNow={async () => {
            setControlFeedback(null);
            await controllerRef.current?.lockRemoteScreen();
          }}
          onSetScreenPause={async (minutes) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteScreenPause(minutes, minutes > 0);
          }}
          onApplyAppLock={async (app: AppUsageEntry, minutes: number, explicitUntilMs?: number) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteAppLock({
              packageName: app.packageName,
              appName: app.appName,
              durationMinutes: minutes,
              untilMs: explicitUntilMs,
            });
          }}
          onClearAllAppLocks={async () => {
            setControlFeedback(null);
            await controllerRef.current?.clearAllRemoteAppLocks();
          }}
          onSetAppQuota={async (app, dailyLimitMinutes) => {
            setControlFeedback(null);
            await controllerRef.current?.setRemoteAppQuota({
              packageName: app.packageName,
              appName: app.appName,
              dailyLimitMinutes,
            });
          }}
          onSaveAppGroupQuota={async (opts) => {
            setControlFeedback(null);
            await controllerRef.current?.saveRemoteAppGroupQuota(opts);
          }}
          onJumpToCapturesHour={(hour) => {
            setTimelineHourFilter(hour);
            setViewerTab('media');
            setMediaSubTab('timeline');
            setTimelineLoading(true);
            void controllerRef.current?.performTimelineAction({
              action: 'list',
              page: 0,
              pageSize: 40,
            });
            window.setTimeout(() => setTimelineLoading(false), 10000);
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
          onFlashText={async (text, durationSec) => {
            setControlFeedback(null);
            await controllerRef.current?.flashRemoteText(text, durationSec);
          }}
          onFlashImage={async (base64Jpeg, durationSec, caption) => {
            setControlFeedback(null);
            await controllerRef.current?.flashRemoteImage(base64Jpeg, durationSec, caption);
          }}
        />
        </div>
      )}

      {/* ==================== TAB 2: MESSAGES (SMS, RÉSEAUX, APPELS & ANALYSE) ==================== */}
      {viewerTab === 'messages' && (
        <div className="space-y-3.5">
          <div className="grid grid-cols-4 gap-1 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-1 sm:inline-flex sm:w-auto sm:gap-1.5">
            <button
              type="button"
              onClick={() => {
                setMessagesSubTab('sms');
                if (smsThreads.length === 0 && !smsThreadsLoading) {
                  void handleFetchSmsThreads();
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                messagesSubTab === 'sms'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <MessageSquare className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">SMS</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMessagesSubTab('social');
                if (!socialThreadsData && !socialThreadsLoading) {
                  setSocialThreadsLoading(true);
                  void controllerRef.current?.performSocialAction({ action: 'list_threads' });
                  window.setTimeout(() => setSocialThreadsLoading(false), 10000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                messagesSubTab === 'social'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Send className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Réseaux</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMessagesSubTab('calls');
                if (!callLogData && !callLogLoading) {
                  setCallLogLoading(true);
                  void controllerRef.current?.requestCallLog();
                  window.setTimeout(() => setCallLogLoading(false), 10000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                messagesSubTab === 'calls'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Phone className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Appels</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMessagesSubTab('insights');
                if (!insightsData && !insightsLoading) {
                  setInsightsLoading(true);
                  void controllerRef.current?.requestInsights();
                  window.setTimeout(() => setInsightsLoading(false), 12000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                messagesSubTab === 'insights'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Sparkles className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Analyse</span>
            </button>
          </div>

          {messagesSubTab === 'sms' ? (
            <SmsMessagesTab
              threads={smsThreads}
              threadsLoading={smsThreadsLoading}
              threadsError={smsThreadsError}
              activeThread={activeSmsThread}
              messages={smsMessages}
              messagesLoading={smsMessagesLoading}
              messagesError={smsMessagesError}
              sending={smsSending}
              sendFeedback={smsSendFeedback}
              contacts={contacts}
              contactsLoading={contactsLoading}
              canSendSms={canUseSmsRelay}
              onRefreshThreads={handleFetchSmsThreads}
              onOpenThread={handleOpenSmsThread}
              onCloseThread={() => setActiveSmsThread(null)}
              onReply={handleReplySms}
              onLoadContacts={async (q = '') => {
                setContactsLoading(true);
                await controllerRef.current?.requestContacts(q);
                window.setTimeout(() => setContactsLoading(false), 8000);
              }}
            />
          ) : messagesSubTab === 'social' ? (
            <SocialMessagesTab
              threadsData={socialThreadsData}
              messagesData={socialMessagesData}
              loadingThreads={socialThreadsLoading}
              loadingMessages={socialMessagesLoading}
              replyFeedback={socialReplyFeedback}
              onDismissReplyFeedback={() => setSocialReplyFeedback(null)}
              onRefreshThreads={async (appFilter = '') => {
                setSocialThreadsLoading(true);
                await controllerRef.current?.performSocialAction({
                  action: 'list_threads',
                  appFilter,
                });
                window.setTimeout(() => setSocialThreadsLoading(false), 10000);
              }}
              onOpenThread={async (thread: SocialThreadItem) => {
                setSocialMessagesLoading(true);
                await controllerRef.current?.performSocialAction({
                  action: 'get_messages',
                  threadKey: thread.threadKey,
                });
                window.setTimeout(() => setSocialMessagesLoading(false), 10000);
              }}
              onReplyThread={async (threadKey: string, body: string) => {
                await controllerRef.current?.performSocialAction({
                  action: 'reply',
                  threadKey,
                  body,
                });
              }}
              onSaveKeywords={async (keywords: string[]) => {
                await controllerRef.current?.performSocialAction({
                  action: 'set_keywords',
                  keywords,
                });
              }}
              onClearArchive={async () => {
                await controllerRef.current?.performSocialAction({ action: 'clear' });
              }}
            />
          ) : messagesSubTab === 'insights' ? (
            <InsightsCard
              data={insightsData}
              loading={insightsLoading}
              onRefresh={async () => {
                setInsightsLoading(true);
                await controllerRef.current?.requestInsights();
                window.setTimeout(() => setInsightsLoading(false), 12000);
              }}
            />
          ) : (
            <CallsTab
              data={callLogData}
              loading={callLogLoading}
              onRefresh={async () => {
                setCallLogLoading(true);
                await controllerRef.current?.requestCallLog();
                window.setTimeout(() => setCallLogLoading(false), 10000);
              }}
              onAddBlock={async (number, label) => {
                await controllerRef.current?.updateCallBlockRule({
                  action: 'add_block',
                  number,
                  label,
                });
              }}
              onRemoveBlock={async (number) => {
                await controllerRef.current?.updateCallBlockRule({
                  action: 'remove_block',
                  number,
                });
              }}
              onAddAllow={async (number, label) => {
                await controllerRef.current?.updateCallBlockRule({
                  action: 'add_allow',
                  number,
                  label,
                });
              }}
              onRemoveAllow={async (number) => {
                await controllerRef.current?.updateCallBlockRule({
                  action: 'remove_allow',
                  number,
                });
              }}
              onSetFlags={async (opts) => {
                await controllerRef.current?.updateCallBlockRule({
                  action: 'set_flags',
                  ...opts,
                });
              }}
            />
          )}
        </div>
      )}

      {/* ==================== TAB 3: MÉDIAS (PHOTOS + DIRECT + DIFFÉRÉ + CAPTURES AUTO) ==================== */}
      {viewerTab === 'media' && (
        <div className="space-y-3.5">
          <div className="grid grid-cols-4 gap-1 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-1 sm:inline-flex sm:w-auto sm:gap-1.5">
            <button
              type="button"
              onClick={() => {
                setMediaSubTab('photos');
                if (galleryItems.length === 0 && !galleryLoading) {
                  void handleFetchGallery(0, '');
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                mediaSubTab === 'photos'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <ImageIcon className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Photos</span>
            </button>
            <button
              type="button"
              onClick={() => setMediaSubTab('live')}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                mediaSubTab === 'live'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Video className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Direct</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMediaSubTab('recordings');
                if (!recordingsData && !recordingsLoading) {
                  setRecordingsLoading(true);
                  void controllerRef.current?.performRecordingAction({ action: 'list' });
                  window.setTimeout(() => setRecordingsLoading(false), 10000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                mediaSubTab === 'recordings'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Circle
                className={`h-3 w-3 shrink-0 ${
                  telemetry?.offlineRecordingActive || recordingsData?.isRecording
                    ? 'fill-rose-500 text-rose-500 animate-pulse'
                    : ''
                }`}
              />
              <span className="truncate">Différé</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMediaSubTab('timeline');
                if (!timelineData && !timelineLoading) {
                  setTimelineLoading(true);
                  void controllerRef.current?.performTimelineAction({ action: 'list', page: 0 });
                  window.setTimeout(() => setTimelineLoading(false), 10000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1 rounded-xl px-2 py-2 text-xs font-medium transition sm:gap-1.5 sm:px-4 sm:py-1.5 ${
                mediaSubTab === 'timeline'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Camera
                className={`h-3.5 w-3.5 shrink-0 ${
                  telemetry?.timelineRunning || timelineData?.running
                    ? 'text-emerald-400'
                    : ''
                }`}
              />
              <span className="truncate">Captures</span>
            </button>
          </div>

          {mediaSubTab === 'timeline' && (
            <TimelineTab
              data={timelineData}
              availableApps={screenTime?.apps ?? []}
              loading={timelineLoading}
              hourFilter={timelineHourFilter}
              onClearHourFilter={() => setTimelineHourFilter(null)}
              lightbox={timelineLightbox}
              onRefresh={async (page = 0) => {
                setTimelineLoading(true);
                await controllerRef.current?.performTimelineAction({ action: 'list', page });
                window.setTimeout(() => setTimelineLoading(false), 10000);
              }}
              onConfigure={async (opts) => {
                await controllerRef.current?.performTimelineAction({
                  action: 'configure',
                  ...opts,
                });
              }}
              onOpenCapture={async (item) => {
                setTimelineLightbox({ item, loading: true });
                await controllerRef.current?.performTimelineAction({
                  action: 'get_image',
                  captureId: item.id,
                });
              }}
              onDeleteCapture={async (captureId) => {
                await controllerRef.current?.performTimelineAction({
                  action: 'delete',
                  captureId,
                });
              }}
              onClearAll={async () => {
                await controllerRef.current?.performTimelineAction({ action: 'clear' });
              }}
              onCloseLightbox={() => setTimelineLightbox(null)}
            />
          )}

          {mediaSubTab === 'recordings' && (
            <RecordingsTab
              data={recordingsData}
              loading={recordingsLoading}
              downloadProgress={recordingDownloadProgress}
              activeMedia={activeRecordingMedia}
              onRefresh={async () => {
                setRecordingsLoading(true);
                await controllerRef.current?.performRecordingAction({ action: 'list' });
                window.setTimeout(() => setRecordingsLoading(false), 10000);
              }}
              onStart={async (mode, durationMinutes, facing) => {
                await controllerRef.current?.performRecordingAction({
                  action: 'start',
                  mode,
                  durationMinutes,
                  facing,
                });
              }}
              onStop={async () => {
                await controllerRef.current?.performRecordingAction({ action: 'stop' });
              }}
              onOpenFile={async (item) => {
                setRecordingDownloadProgress(null);
                setActiveRecordingMedia({
                  item,
                  mimeType: item.mimeType || (item.mode === 'video' ? 'video/mp4' : 'audio/mp4'),
                  loading: true,
                });
                await controllerRef.current?.performRecordingAction({
                  action: 'get_file',
                  recordingId: item.id,
                });
              }}
              onDelete={async (item) => {
                await controllerRef.current?.performRecordingAction({
                  action: 'delete',
                  recordingId: item.id,
                });
              }}
              onClosePlayer={() => {
                setActiveRecordingMedia(null);
                setRecordingDownloadProgress(null);
              }}
            />
          )}

          {mediaSubTab === 'photos' && (
            <GalleryTab
              items={galleryItems}
              quarantineItems={quarantineItems}
              albums={galleryAlbums}
              activeAlbum={galleryAlbum}
              totalCount={galleryTotal}
              quarantineCount={quarantineCount}
              hasMore={galleryHasMore}
              loading={galleryLoading}
              quarantineLoading={quarantineLoading}
              error={galleryError}
              actionFeedback={galleryActionFeedback}
              actionBusy={galleryActionBusy}
              canViewGallery={Boolean(telemetry?.allowGallery)}
              canManageFiles={galleryCanManageFiles}
              lightboxPhoto={lightboxPhoto}
              onRefresh={async (alb) => {
                await handleFetchGallery(0, alb !== undefined ? alb : galleryAlbum);
              }}
              onLoadMore={async () => {
                await handleFetchGallery(galleryPage + 1, galleryAlbum);
              }}
              onRefreshQuarantine={handleFetchQuarantine}
              onSelectPhoto={handleSelectGalleryPhoto}
              onQuarantinePhoto={async (item) => {
                setGalleryActionFeedback(null);
                setGalleryActionBusy(true);
                pendingGalleryTargetIdRef.current = item.id;
                await controllerRef.current?.performGalleryAction('quarantine', item.id);
                window.setTimeout(() => setGalleryActionBusy(false), 12000);
              }}
              onDeletePhoto={async (item) => {
                setGalleryActionFeedback(null);
                setGalleryActionBusy(true);
                pendingGalleryTargetIdRef.current = item.id;
                await controllerRef.current?.performGalleryAction('delete', item.id);
                window.setTimeout(() => setGalleryActionBusy(false), 12000);
              }}
              onRestoreQuarantine={async (item) => {
                setGalleryActionFeedback(null);
                setGalleryActionBusy(true);
                pendingGalleryTargetIdRef.current = item.id;
                await controllerRef.current?.performGalleryAction('restore_quarantine', item.id);
                window.setTimeout(() => setGalleryActionBusy(false), 12000);
              }}
              onDeleteQuarantine={async (item) => {
                setGalleryActionFeedback(null);
                setGalleryActionBusy(true);
                pendingGalleryTargetIdRef.current = item.id;
                await controllerRef.current?.performGalleryAction('delete_quarantine', item.id);
                window.setTimeout(() => setGalleryActionBusy(false), 12000);
              }}
              onCloseLightbox={() => {
                setLightboxPhoto(null);
                setGalleryActionBusy(false);
              }}
              onDismissFeedback={() => setGalleryActionFeedback(null)}
            />
          )}

          {mediaSubTab === 'live' && (
            <>
              {streamNotice && (
                <div className="flex items-center gap-2 rounded-xl border border-amber-500/35 bg-amber-500/10 px-3.5 py-2.5 text-xs text-amber-200">
                  <AlertTriangle className="h-4 w-4 shrink-0 text-amber-400" />
                  <span>{streamNotice}</span>
                </div>
              )}

              <div
                ref={videoContainerRef}
                className="relative aspect-video w-full overflow-hidden rounded-2xl border border-white/[0.08] bg-zinc-950"
              >
                {isStreaming && (
                  <div className="absolute left-3 top-3 z-20">
                    <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/40 bg-zinc-950/85 px-2.5 py-1 text-[11px] font-semibold text-rose-200 backdrop-blur">
                      <span className="h-2 w-2 rounded-full bg-rose-500 animate-ping" />
                      {mediaMode === 'screen'
                        ? 'Écran en direct'
                        : `Direct · ${cameraFacing === 'front' ? 'Avant' : 'Arrière'}`}
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
                        <RefreshCw className="h-8 w-8 animate-spin text-emerald-400" />
                        <div className="mt-3 text-sm font-medium text-white">Connexion en cours…</div>
                      </>
                    ) : (
                      <>
                        <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-white/10 bg-white/[0.04]">
                          <CameraOff className="h-6 w-6 text-zinc-400" />
                        </div>
                        <h3 className="mt-3 text-sm font-semibold text-white">Caméra en veille</h3>
                        <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
                          <button
                            type="button"
                            onClick={() => void handleWakeMedia('camera')}
                            className="inline-flex items-center gap-2 rounded-xl bg-white px-5 py-2.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
                          >
                            <Video className="h-4 w-4" />
                            <span>Allumer la caméra</span>
                          </button>
                          {canViewScreen && (
                            <button
                              type="button"
                              onClick={() => void handleWakeMedia('screen')}
                              className="inline-flex items-center gap-2 rounded-xl border border-white/15 bg-zinc-900 px-5 py-2.5 text-xs font-semibold text-zinc-200 hover:bg-zinc-800"
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

              {/* Controls Bar */}
              <div className="space-y-2.5 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-3.5 sm:flex sm:flex-wrap sm:items-center sm:justify-between sm:gap-2.5 sm:space-y-0">
                <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
                  {!isStreaming ? (
                    <button
                      type="button"
                      onClick={() => void handleWakeMedia('camera')}
                      className="col-span-2 inline-flex items-center justify-center gap-2 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 sm:col-span-1"
                    >
                      <Zap className="h-3.5 w-3.5" />
                      <span>Allumer</span>
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => void handleSleepCamera()}
                      className="col-span-2 inline-flex items-center justify-center gap-2 rounded-xl border border-amber-500/40 bg-amber-500/15 px-4 py-2 text-xs font-semibold text-amber-200 sm:col-span-1"
                    >
                      <Power className="h-3.5 w-3.5" />
                      <span>Couper</span>
                    </button>
                  )}

                  {canViewScreen && (
                    <button
                      type="button"
                      onClick={() =>
                        void handleWakeMedia(mediaMode === 'screen' && isStreaming ? 'camera' : 'screen')
                      }
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-200"
                    >
                      <Monitor className="h-3.5 w-3.5" />
                      <span>{mediaMode === 'screen' && isStreaming ? 'Caméra' : 'Écran'}</span>
                    </button>
                  )}

                  {mediaMode === 'camera' && (
                    <button
                      type="button"
                      disabled={!canSwitchCam}
                      onClick={() => void handleToggleFacing()}
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-200 disabled:opacity-35"
                    >
                      <RefreshCw className="h-3.5 w-3.5 text-zinc-400" />
                      <span>{cameraFacing === 'front' ? 'Avant' : 'Arrière'}</span>
                    </button>
                  )}

                  <button
                    type="button"
                    disabled={!canUseAudio}
                    onClick={() => void handleToggleRemoteMic()}
                    className={`inline-flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium disabled:opacity-35 ${
                      remoteMicEnabled && canUseAudio
                        ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-300'
                    }`}
                  >
                    {remoteMicEnabled && canUseAudio ? (
                      <>
                        <Mic className="h-3.5 w-3.5" />
                        <span>Micro ON</span>
                      </>
                    ) : (
                      <>
                        <MicOff className="h-3.5 w-3.5 text-zinc-400" />
                        <span>Micro OFF</span>
                      </>
                    )}
                  </button>
                </div>

                <div className="flex flex-wrap items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => void controllerRef.current?.toggleTorch(!(telemetry?.torchEnabled ?? false))}
                    className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium ${
                      telemetry?.torchEnabled
                        ? 'border-amber-400/60 bg-amber-400/20 text-amber-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-300'
                    }`}
                  >
                    <Flashlight className="h-3.5 w-3.5" />
                    <span>Torche</span>
                  </button>

                  <button
                    type="button"
                    disabled={!isStreaming}
                    onClick={handleToggleRecording}
                    className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium disabled:opacity-35 ${
                      isRecording
                        ? 'border-rose-500/60 bg-rose-500/20 text-rose-200 animate-pulse'
                        : 'border-white/10 bg-zinc-950 text-zinc-300'
                    }`}
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
                    className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs font-medium text-zinc-300 disabled:opacity-35"
                  >
                    <Camera className="h-3.5 w-3.5" />
                    <span>Photo</span>
                  </button>

                  <button
                    type="button"
                    disabled={!isStreaming}
                    onClick={() => setVideoZoom((z) => (z >= 4 ? 1 : z + 1))}
                    className="inline-flex items-center gap-1 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 font-mono text-xs text-zinc-300 disabled:opacity-35"
                  >
                    <ZoomIn className="h-3.5 w-3.5" />
                    <span>{videoZoom}×</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setLocalAudioMuted((m) => !m)}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-white/10 bg-zinc-950 text-zinc-300"
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
                      className={`inline-flex h-8 w-8 items-center justify-center rounded-xl border bg-zinc-950 ${
                        mirrorVideo ? 'border-emerald-500/40 text-emerald-300' : 'border-white/10 text-zinc-400'
                      }`}
                    >
                      <FlipHorizontal className="h-4 w-4" />
                    </button>
                  )}

                  <button
                    type="button"
                    onClick={() => setQuality((q) => (q === 'hd' ? 'sd' : 'hd'))}
                    className="rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-2 font-mono text-xs text-zinc-300"
                  >
                    {quality.toUpperCase()}
                  </button>

                  <button
                    type="button"
                    disabled={!isStreaming}
                    onClick={handleFullscreen}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-white/10 bg-zinc-950 text-zinc-300 disabled:opacity-35"
                  >
                    <Maximize2 className="h-4 w-4" />
                  </button>
                </div>
              </div>

              {/* Talkie-Walkie / Voice Speaker Bar */}
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
                  <span className="text-xs font-medium text-white">Parler sur le haut-parleur</span>
                  {talkieActive && (
                    <div className="h-1.5 w-24 overflow-hidden rounded-full bg-zinc-950">
                      <div
                        className="h-full rounded-full bg-emerald-400 transition-all duration-75"
                        style={{ width: `${Math.max(8, talkieLevel)}%` }}
                      />
                    </div>
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
                    <span>{talkieActive && !talkieLocked ? 'Parlez…' : 'Maintenir'}</span>
                  </button>

                  <button
                    type="button"
                    disabled={!canUseIntercom}
                    onClick={handleToggleTalkieLock}
                    className={`rounded-xl border px-3 py-2 text-xs font-medium disabled:opacity-35 ${
                      talkieLocked
                        ? 'border-rose-500/50 bg-rose-500/15 text-rose-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-300'
                    }`}
                  >
                    {talkieLocked ? 'Couper' : 'Mains libres'}
                  </button>

                  <button
                    type="button"
                    disabled={!canUseIntercom}
                    onClick={() => setTtsOpen((v) => !v)}
                    className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium disabled:opacity-35 ${
                      ttsOpen
                        ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-300'
                    }`}
                  >
                    <MessageSquare className="h-3.5 w-3.5" />
                    <span className="hidden sm:inline">Texte vocal</span>
                  </button>
                </div>

                {ttsOpen && canUseIntercom && (
                  <form
                    onSubmit={(e) => void handleSendTts(e)}
                    className="flex w-full gap-2 border-t border-white/[0.06] pt-2.5"
                  >
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
                <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
                  <div className="text-xs font-semibold text-white">
                    Captures ({snapshots.length})
                  </div>
                  <div className="mt-3 grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                    {snapshots.map((snap) => (
                      <div
                        key={snap.id}
                        className="overflow-hidden rounded-xl border border-white/[0.07] bg-zinc-950"
                      >
                        <img src={snap.dataUrl} alt="Capture" className="aspect-video w-full object-cover" />
                        <div className="flex items-center justify-between px-2.5 py-1.5 text-[10px] text-zinc-400">
                          <span>{new Date(snap.timestamp).toLocaleTimeString('fr-FR')}</span>
                          <a
                            href={snap.dataUrl}
                            download={`capture_${snap.timestamp}.png`}
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
        </div>
      )}

      {/* ==================== TAB 4: POSITION (CARTE + LIEUX + CONDUITE) ==================== */}
      {viewerTab === 'location' && (
        <div className="space-y-3.5">
          {/* Sub-navigation pills */}
          <div className="grid grid-cols-3 gap-1.5 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-1 sm:inline-flex sm:w-auto">
            <button
              type="button"
              onClick={() => setLocationSubTab('map')}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-medium transition sm:px-4 sm:py-1.5 ${
                locationSubTab === 'map'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <MapPin className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Carte</span>
            </button>
            <button
              type="button"
              onClick={() => setLocationSubTab('places')}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-medium transition sm:px-4 sm:py-1.5 ${
                locationSubTab === 'places'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Clock className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">
                Lieux ({locationIntelligence.visits.length})
              </span>
            </button>
            <button
              type="button"
              onClick={() => {
                setLocationSubTab('driving');
                if (!drivingReport && !drivingLoading) {
                  void handleFetchDrivingReport(drivingDays);
                }
              }}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-medium transition sm:px-4 sm:py-1.5 ${
                locationSubTab === 'driving'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Car className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">
                Conduite{drivingReport ? ` · ${drivingReport.score}` : ''}
              </span>
            </button>
          </div>

          {/* Sub-tab 1: Carte, Zones > 130 km/h, Chaleur (Heatmap) & Export */}
          {locationSubTab === 'map' && (
            <>
              <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <MapPin className="h-4 w-4 text-emerald-400" />
                    <h2 className="text-sm font-semibold text-white">Localisation & Trajets</h2>
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
                    Activez « GPS & Trajets » dans Règles → Protection → Autorisations à distance.
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
                      heatmapSpots={locationIntelligence.heatmapSpots}
                      drivingEvents={drivingReport?.events ?? []}
                    />

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
                        <div className="text-sm font-medium text-white">Points détaillés & Export</div>
                        <div className="text-[11px] text-zinc-500">
                          {historyBundle?.points.length} points enregistrés (GPX / CSV)
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
                                  {pt.speedKmh > 130 && pt.speedKmh < 320 && (
                                    <span className="rounded bg-rose-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-rose-300">
                                      &gt; 130 km/h
                                    </span>
                                  )}
                                </div>
                                <span
                                  className={`shrink-0 font-mono text-[11px] ${
                                    pt.speedKmh > 130 && pt.speedKmh < 320
                                      ? 'font-semibold text-rose-300'
                                      : 'text-zinc-400'
                                  }`}
                                >
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
            </>
          )}

          {/* Sub-tab 2: Timeline Intelligente des Lieux & Zones de Sécurité */}
          {locationSubTab === 'places' && (
            <div className="space-y-3.5">
              <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
                      <Clock className="h-4 w-4 text-sky-400" />
                      Journal automatique des lieux visités
                    </h3>
                    <p className="text-[11px] text-zinc-400">
                      Arrêts détectés automatiquement, durées de présence et nouveaux lieux sur la période sélectionnée.
                    </p>
                  </div>
                  <div className="flex gap-1">
                    {(['today', '24h', '7d', '30d'] as const).map((r) => (
                      <button
                        key={r}
                        type="button"
                        onClick={() => void handleFetchLocationHistory(r)}
                        className={`rounded-lg px-2 py-1 text-[11px] font-medium ${
                          historyRange === r
                            ? 'bg-white text-zinc-950'
                            : 'border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                        }`}
                      >
                        {r === 'today' ? 'Auj.' : r}
                      </button>
                    ))}
                  </div>
                </div>

                {locationIntelligence.visits.length === 0 ? (
                  <div className="rounded-xl border border-dashed border-white/10 bg-zinc-950/60 px-4 py-8 text-center text-xs text-zinc-400">
                    Aucun arrêt prolongé (&ge; 4 min) détecté sur cette période. Essayez « 7 j » ou « 30 j ».
                  </div>
                ) : (
                  <div className="max-h-[420px] space-y-2 overflow-y-auto pr-0.5">
                    {locationIntelligence.visits.map((v) => {
                      const mins = Math.round(v.durationSec / 60);
                      const durLabel =
                        mins >= 60 ? `${Math.floor(mins / 60)}h${(mins % 60).toString().padStart(2, '0')}` : `${mins} min`;
                      return (
                        <div
                          key={v.id}
                          className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950/85 p-3"
                        >
                          <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="text-xs font-semibold text-white">{v.label}</span>
                              {v.matchedGeofenceName && (
                                <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-medium text-emerald-300">
                                  Zone connue
                                </span>
                              )}
                              {v.isNewPlace && (
                                <span className="rounded-full border border-amber-400/35 bg-amber-500/15 px-2 py-0.5 text-[10px] font-semibold text-amber-200">
                                  Nouveau lieu
                                </span>
                              )}
                            </div>
                            <p className="mt-1 text-[11px] text-zinc-400">
                              {new Date(v.startTs).toLocaleDateString('fr-FR', {
                                weekday: 'short',
                                day: '2-digit',
                                month: '2-digit',
                              })}{' '}
                              ·{' '}
                              {new Date(v.startTs).toLocaleTimeString('fr-FR', {
                                hour: '2-digit',
                                minute: '2-digit',
                              })}{' '}
                              →{' '}
                              {new Date(v.endTs).toLocaleTimeString('fr-FR', {
                                hour: '2-digit',
                                minute: '2-digit',
                              })}{' '}
                              · <span className="font-mono text-zinc-200">{durLabel}</span>
                            </p>
                          </div>

                          <div className="flex shrink-0 items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => {
                                setFocusedTrackPoint(v.point);
                                setLocationSubTab('map');
                              }}
                              className="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-zinc-900 px-2.5 py-1.5 text-[11px] text-zinc-200 hover:border-white/25"
                            >
                              <MapPin className="h-3 w-3 text-sky-400" />
                              Carte
                            </button>
                            {!v.matchedGeofenceName && canUseDeviceControl && (
                              <button
                                type="button"
                                onClick={async () => {
                                  const name = window.prompt('Nommer cette zone (ex : Collège, Ami, Sport) :', 'Nouveau lieu');
                                  if (name && name.trim()) {
                                    await controllerRef.current?.saveRemoteGeofence({
                                      name: name.trim(),
                                      latitude: v.latitude,
                                      longitude: v.longitude,
                                      radiusMeters: 150,
                                    });
                                  }
                                }}
                                className="inline-flex items-center gap-1 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-1.5 text-[11px] text-emerald-200 hover:bg-emerald-500/20"
                                title="Enregistrer comme zone de sécurité"
                              >
                                <Plus className="h-3 w-3" />
                                Zone
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

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
            </div>
          )}

          {/* Sub-tab 3: Score de Conduite */}
          {locationSubTab === 'driving' && (
            <DrivingScoreCard
              data={drivingReport}
              loading={drivingLoading}
              days={drivingDays}
              onChangeDays={(d) => handleFetchDrivingReport(d)}
              onRefresh={() => handleFetchDrivingReport(drivingDays)}
              onFocusEvent={(ev: DrivingEventItem) => {
                if (ev.latitude || ev.longitude) {
                  setFocusedTrackPoint({
                    timestamp: ev.timestamp,
                    latitude: ev.latitude,
                    longitude: ev.longitude,
                    accuracyMeters: 20,
                    speedKmh: ev.speedKmh,
                    batteryLevel: telemetry?.batteryLevel ?? 100,
                    dwellSeconds: 0,
                    provider: 'gps',
                  });
                  setLocationSubTab('map');
                }
              }}
            />
          )}
        </div>
      )}

      {/* ==================== TAB 5: BLINDAGE (HORS-MDM + MDM & SYSTÈME + RÈGLES) ==================== */}
      {viewerTab === 'rules' && (
        <div className="space-y-4">
          <div className="grid grid-cols-3 gap-1.5 rounded-2xl border border-slate-200/80 bg-white p-1.5 shadow-sm dark:border-white/[0.07] dark:bg-zinc-900/70 sm:inline-flex sm:w-auto">
            <button
              type="button"
              onClick={() => setRulesSubTab('shield')}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-semibold transition sm:px-4 sm:py-1.5 ${
                rulesSubTab === 'shield'
                  ? 'bg-sky-500 text-white kl-solid-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-100 dark:text-zinc-400 dark:hover:text-white'
              }`}
            >
              <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Boucliers v27</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setRulesSubTab('system');
                if (!systemStatus && !systemStatusLoading) {
                  setSystemStatusLoading(true);
                  void controllerRef.current?.sendSystemControl({ action: 'get_status' });
                  window.setTimeout(() => setSystemStatusLoading(false), 10000);
                }
              }}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-semibold transition sm:px-4 sm:py-1.5 ${
                rulesSubTab === 'system'
                  ? 'bg-sky-500 text-white kl-solid-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-100 dark:text-zinc-400 dark:hover:text-white'
              }`}
            >
              <Sliders className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">MDM & Apps</span>
            </button>
            <button
              type="button"
              onClick={() => setRulesSubTab('protection')}
              className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-semibold transition sm:px-4 sm:py-1.5 ${
                rulesSubTab === 'protection'
                  ? 'bg-sky-500 text-white kl-solid-white shadow-sm'
                  : 'text-slate-600 hover:bg-slate-100 dark:text-zinc-400 dark:hover:text-white'
              }`}
            >
              <Clock className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">Horaires & IA</span>
            </button>
          </div>

          {rulesSubTab === 'shield' ? (
            <AdvancedShieldPanel
              screenTime={screenTime}
              systemStatus={systemStatus}
              nowMs={nowMs}
              onUpdateAdvanced={async (opts) => {
                setControlFeedback(null);
                const res = await controllerRef.current?.setAdvancedControls(opts);
                if (res?.queued) {
                  setControlFeedback({
                    ok: true,
                    text: 'Règle enregistrée dans la file hors ligne (#22).',
                  });
                }
              }}
              onMdmHardenAll={async () => {
                setControlFeedback(null);
                await controllerRef.current?.sendSystemControl({ action: 'mdm_harden_all' });
              }}
            />
          ) : rulesSubTab === 'protection' ? (
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
              onSetAdminPin={async (pin) => {
                setControlFeedback(null);
                await controllerRef.current?.setRemoteAdminPin(pin, true);
              }}
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
          ) : (
            <div className="space-y-3.5">
              <SystemCommandCard
                data={systemStatus}
                loading={systemStatusLoading}
                defaultOpen={true}
                onRefresh={async () => {
                  setSystemStatusLoading(true);
                  await controllerRef.current?.sendSystemControl({ action: 'get_status' });
                  window.setTimeout(() => setSystemStatusLoading(false), 10000);
                }}
                onSetVolume={async (stream, percent) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'set_volume',
                    stream,
                    percent,
                  });
                }}
                onSetRinger={async (mode) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'set_ringer',
                    mode,
                  });
                }}
                onSetBrightness={async (percent) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'set_brightness',
                    percent,
                  });
                }}
                onLaunchApp={async (packageName) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'launch_app',
                    packageName,
                  });
                }}
                onUninstallApp={async (packageName) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'uninstall_app',
                    packageName,
                  });
                }}
                onMdmSetAppHidden={async (packageName, hidden) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_hide_app',
                    packageName,
                    hidden,
                  });
                }}
                onMdmSetAppSuspended={async (packageName, suspended, message) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_suspend_app',
                    packageName,
                    suspended,
                    message,
                  });
                }}
                onSetDrivingSafety={async (opts) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'set_driving_safety',
                    enabled: opts.enabled,
                    thresholdKmh: opts.thresholdKmh,
                    suspendApps: opts.suspendApps,
                    silentRinger: opts.silentRinger,
                  });
                }}
                onMdmSetRestriction={async (target, enabled) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_set_restriction',
                    target,
                    enabled,
                  });
                }}
                onMdmSetScreenTimeout={async (timeoutSec) => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_set_screen_timeout',
                    timeoutSec,
                  });
                }}
                onMdmLockNow={async () => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_lock_now',
                  });
                }}
                onMdmReboot={async () => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_reboot',
                  });
                }}
                onMdmHardenAll={async () => {
                  await controllerRef.current?.sendSystemControl({
                    action: 'mdm_harden_all',
                  });
                }}
              />

              <WallpaperCard
                onApplyWallpaper={async (base64Jpeg, target) => {
                  setControlFeedback(null);
                  await controllerRef.current?.setRemoteWallpaper(base64Jpeg, target);
                }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
};
