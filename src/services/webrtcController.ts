import {
  computeDtlsMediaSas,
  computeEcdhSharedSecret,
  computeHelloAckProof,
  computeHelloWakeProof,
  computeSas,
  constantTimeEqualHex,
  deriveBootstrapKeys,
  deriveFastSessionKey,
  extractSdpFingerprint,
  generateEphemeralEcdh,
  normalizePairingCode,
  randomHex,
  type EphemeralEcdhPair,
} from '../crypto/cryptoEngine';
import type {
  ActiveAccessGrant,
  AuthErrMsg,
  CameraFacingMode,
  DataCallLogMsg,
  DataContactsMsg,
  DataControlResultMsg,
  DataDeviceInfoMsg,
  DataDrivingReportMsg,
  DataGalleryMsg,
  DataGalleryPhotoMsg,
  DataInsightsMsg,
  DataLocationHistoryMsg,
  DataLocationMsg,
  DataQuarantineListMsg,
  DataRecordingFileMsg,
  DataRecordingsMsg,
  DataScreenTimeMsg,
  DataSmsMessagesMsg,
  DataSmsResultMsg,
  DataSmsThreadsMsg,
  DataSocialMessagesMsg,
  DataSocialThreadsMsg,
  DataSystemStatusMsg,
  DataTimelineImageMsg,
  DataTimelineMsg,
  DerivedBootstrapKeys,
  DeviceTelemetry,
  GpsTrackingMode,
  GpsTrackPoint,
  HelloAckMsg,
  IceCandidateMsg,
  LocationHistoryBundle,
  MediaSourceMode,
  PortalOperationalState,
  PortalStatusMsg,
  SdpOfferMsg,
  SignalMessage,
  SnapshotItem,
  StreamQualityPreset,
  StreamRealtimeStats,
  StreamStateMsg,
} from '../types/sentinel';
import {
  buildIceServers,
  findPairedDeviceByCode,
  getOrCreateControllerTokenId,
  savePairedDevice,
} from './deviceVault';
import { EncryptedSignalingChannel, type BrokerHealth } from './signalingService';

export interface ControllerCallbacks {
  onStateChange: (state: PortalOperationalState) => void;
  onBrokerHealth: (connected: boolean, brokers: BrokerHealth[]) => void;
  onTelemetry: (telemetry: DeviceTelemetry) => void;
  onAuthSuccess: (info: {
    deviceName: string;
    deviceId: string;
    sasCode: string;
    sasSymbols: string;
    accessGrant: ActiveAccessGrant;
  }) => void;
  onAuthError: (err: AuthErrMsg) => void;
  onRemoteStream: (stream: MediaStream | null) => void;
  onStatsUpdate: (stats: StreamRealtimeStats | null) => void;
  onContactsData?: (data: DataContactsMsg) => void;
  onLocationData?: (data: DataLocationMsg) => void;
  onLocationHistoryData?: (data: LocationHistoryBundle) => void;
  onDeviceInfoData?: (data: DataDeviceInfoMsg) => void;
  onSmsResult?: (data: DataSmsResultMsg) => void;
  onSmsThreadsData?: (data: DataSmsThreadsMsg) => void;
  onSmsMessagesData?: (data: DataSmsMessagesMsg) => void;
  onScreenTimeData?: (data: DataScreenTimeMsg) => void;
  onControlResult?: (data: DataControlResultMsg) => void;
  onGalleryData?: (data: DataGalleryMsg) => void;
  onQuarantineListData?: (data: DataQuarantineListMsg) => void;
  onGalleryPhotoData?: (info: { mediaId: string; base64?: string; error?: string }) => void;
  onCallLogData?: (data: DataCallLogMsg) => void;
  onRecordingsData?: (data: DataRecordingsMsg) => void;
  onRecordingFileProgress?: (info: { recordingId: string; received: number; total: number }) => void;
  onRecordingFileData?: (info: {
    recordingId: string;
    mimeType: string;
    base64?: string;
    error?: string;
  }) => void;
  onSystemStatusData?: (data: DataSystemStatusMsg) => void;
  onSocialThreadsData?: (data: DataSocialThreadsMsg) => void;
  onSocialMessagesData?: (data: DataSocialMessagesMsg) => void;
  onInsightsData?: (data: DataInsightsMsg) => void;
  onTimelineData?: (data: DataTimelineMsg) => void;
  onTimelineImageData?: (info: { captureId: string; base64?: string; error?: string }) => void;
  onDrivingReportData?: (data: DataDrivingReportMsg) => void;
  onStreamError?: (message: string) => void;
  onTalkieStateChange?: (active: boolean) => void;
  onTalkieLevel?: (level: number) => void;
}

export class SentinelWebRtcController {
  private readonly callbacks: ControllerCallbacks;
  private signaling: EncryptedSignalingChannel | null = null;
  private bootstrapKeys: DerivedBootstrapKeys | null = null;
  private pc: RTCPeerConnection | null = null;
  private remoteStream: MediaStream | null = null;

  // One-Way Walkie-Talkie (Web -> Phone Loudspeaker) state
  private talkieActive = false;
  private allowIntercomGranted = true;
  private startedIntercomOnlySession = false;
  private talkieAudioSender: RTCRtpSender | null = null;
  private localMicStream: MediaStream | null = null;
  private silentAudioCtx: AudioContext | null = null;
  private silentAudioTrack: MediaStreamTrack | null = null;
  private vuAudioCtx: AudioContext | null = null;
  private vuRafId: number | null = null;

  private pairingCode = '';
  private tokenId = '';
  private activeSessionId = '';
  private authenticated = false;

  private desiredStreaming = false;
  private desiredMediaMode: MediaSourceMode = 'camera';
  private desiredFacing: CameraFacingMode = 'front';
  private desiredAudio = false;
  private desiredQuality: StreamQualityPreset = 'hd';

  private localSdpFingerprint = '';
  private remoteSdpFingerprint = '';
  private pendingCandidates: IceCandidateMsg[] = [];
  private ecdhPairsByNonce = new Map<string, EphemeralEcdhPair>();
  private historyReassembly = new Map<
    string,
    {
      totalChunks: number;
      chunks: Map<number, GpsTrackPoint[]>;
      rawPointCount: number;
      totalDistanceMeters: number;
      maxSpeedKmh: number;
      stopsCount: number;
      trackerState: LocationHistoryBundle['trackerState'];
      trackingMode: LocationHistoryBundle['trackingMode'];
      error?: string;
    }
  >();

  private photoReassembly = new Map<
    string,
    { mediaId: string; totalChunks: number; chunks: Map<number, string> }
  >();

  private recordingReassembly = new Map<
    string,
    { recordingId: string; mimeType: string; totalChunks: number; chunks: Map<number, string> }
  >();

  private timelineImageReassembly = new Map<
    string,
    { captureId: string; totalChunks: number; chunks: Map<number, string> }
  >();

  private statsTimer: number | null = null;
  private retryTimer: number | null = null;
  private streamRecoverTimer: number | null = null;
  private lastBytesReceived = 0;
  private lastStatsTimestamp = 0;
  private stalledVideoSeconds = 0;
  private transientRetryCount = 0;
  private visibilityListener: (() => void) | null = null;

  constructor(callbacks: ControllerCallbacks) {
    this.callbacks = callbacks;
  }

  public async connectToPortal(rawCode: string, autoStartCamera = true): Promise<void> {
    this.disconnect();

    const code = normalizePairingCode(rawCode);
    if (code.length !== 6) {
      throw new Error('Code à 6 caractères requis.');
    }

    this.pairingCode = code;
    this.tokenId = getOrCreateControllerTokenId();
    this.authenticated = false;
    this.desiredStreaming = autoStartCamera;
    this.desiredMediaMode = 'camera';
    this.transientRetryCount = 0;
    this.stalledVideoSeconds = 0;

    if (typeof document !== 'undefined' && !this.visibilityListener) {
      this.visibilityListener = () => {
        if (document.visibilityState === 'visible') {
          if (!this.authenticated) {
            void this.sendHelloWake();
          } else if (this.signaling) {
            void this.signaling.send(
              {
                type: 'PING_STATUS',
                controllerId: this.signaling.getSenderId(),
                tokenId: this.tokenId,
                ts: Date.now(),
              },
              'boot'
            );
            if (this.desiredStreaming && this.desiredMediaMode === 'camera') {
              if (!this.pc || this.pc.connectionState !== 'connected') {
                void this.startCameraStream(
                  this.desiredFacing,
                  this.desiredAudio,
                  this.desiredQuality,
                  'camera'
                );
              }
            }
          }
        }
      };
      document.addEventListener('visibilitychange', this.visibilityListener);
    }

    this.callbacks.onStateChange('connecting');

    const keys = await deriveBootstrapKeys(code);
    this.bootstrapKeys = keys;

    if (autoStartCamera) {
      this.activeSessionId = randomHex(10);
      this.pendingCandidates = [];
      this.setupPeerConnection(this.activeSessionId);
    }

    this.signaling = new EncryptedSignalingChannel({
      role: 'controller',
      topicId: keys.topicId,
      bootstrapEncKey: keys.bootstrapEncKey,
      onMessage: (msg, mode) => {
        void this.handleSignalMessage(msg, mode);
      },
      onSubscribedReady: () => {
        void this.sendHelloWake();
      },
      onConnectionChange: (connected, brokers) => {
        this.callbacks.onBrokerHealth(connected, brokers);
      },
    });

    this.signaling.start();

    this.retryTimer = window.setInterval(() => {
      if (!this.signaling || !this.signaling.isSubscribedAndReady()) return;

      if (!this.authenticated) {
        void this.sendHelloWake();
      } else {
        void this.signaling.send(
          {
            type: 'PING_STATUS',
            controllerId: this.signaling.getSenderId(),
            tokenId: this.tokenId,
            ts: Date.now(),
          },
          'boot'
        );
      }
    }, 1800);
  }

  private async sendHelloWake(): Promise<void> {
    if (!this.signaling || !this.bootstrapKeys || this.authenticated) return;

    const clientNonce = randomHex(20);
    const ecdhPair = await generateEphemeralEcdh();
    if (ecdhPair) {
      if (this.ecdhPairsByNonce.size > 8) {
        const firstKey = this.ecdhPairsByNonce.keys().next().value;
        if (firstKey) this.ecdhPairsByNonce.delete(firstKey);
      }
      this.ecdhPairsByNonce.set(clientNonce, ecdhPair);
    }

    const clientEcdhPub = ecdhPair?.publicRawBase64 ?? '';
    const proofHex = await computeHelloWakeProof(
      this.bootstrapKeys.bootstrapAuthRaw,
      clientNonce,
      this.tokenId,
      clientEcdhPub
    );

    if (this.desiredStreaming && !this.pc) {
      this.activeSessionId = randomHex(10);
      this.pendingCandidates = [];
      this.setupPeerConnection(this.activeSessionId);
    }

    await this.signaling.send(
      {
        type: 'HELLO_WAKE',
        controllerId: this.signaling.getSenderId(),
        tokenId: this.tokenId,
        clientNonce,
        proofHex,
        clientEcdhPub: clientEcdhPub || undefined,
        autoStartStream: this.desiredStreaming,
        sessionId: this.activeSessionId || randomHex(10),
        mediaMode: this.desiredMediaMode,
        cameraFacing: this.desiredFacing,
        enableAudio: this.desiredAudio,
        quality: this.desiredQuality,
        ts: Date.now(),
      },
      'boot'
    );
  }

  /* ==================== ONE-WAY WALKIE-TALKIE (WEB -> PHONE SPEAKER) ==================== */

  private ensureSilentAudioTrack(): MediaStreamTrack | null {
    try {
      if (this.silentAudioTrack && this.silentAudioTrack.readyState === 'live') {
        return this.silentAudioTrack;
      }
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtx) return null;
      this.silentAudioCtx = new AudioCtx();
      const dest = this.silentAudioCtx.createMediaStreamDestination();
      const track = dest.stream.getAudioTracks()[0] || null;
      if (track) {
        track.enabled = false;
      }
      this.silentAudioTrack = track;
      return track;
    } catch {
      return null;
    }
  }

  private async ensureBrowserMicTrack(): Promise<MediaStreamTrack> {
    const existing = this.localMicStream?.getAudioTracks()[0];
    if (existing && existing.readyState === 'live') {
      existing.enabled = true;
      return existing;
    }

    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
      video: false,
    });
    this.localMicStream = stream;
    const track = stream.getAudioTracks()[0];
    if (!track) {
      throw new Error('Aucun microphone disponible sur cet appareil.');
    }
    track.enabled = true;
    return track;
  }

  private startMicVuMeter(stream: MediaStream): void {
    this.stopMicVuMeter();
    try {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      this.vuAudioCtx = ctx;
      const src = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      src.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);

      const tick = () => {
        if (!this.talkieActive) {
          this.callbacks.onTalkieLevel?.(0);
          return;
        }
        analyser.getByteTimeDomainData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) {
          const v = (data[i] - 128) / 128;
          sum += v * v;
        }
        const rms = Math.sqrt(sum / data.length);
        const pct = Math.min(100, Math.round(rms * 320));
        this.callbacks.onTalkieLevel?.(pct);
        this.vuRafId = window.requestAnimationFrame(tick);
      };
      this.vuRafId = window.requestAnimationFrame(tick);
    } catch {
      // Optional VU meter
    }
  }

  private stopMicVuMeter(): void {
    if (this.vuRafId !== null) {
      window.cancelAnimationFrame(this.vuRafId);
      this.vuRafId = null;
    }
    if (this.vuAudioCtx) {
      void this.vuAudioCtx.close().catch(() => {});
      this.vuAudioCtx = null;
    }
    this.callbacks.onTalkieLevel?.(0);
  }

  public async startTalkie(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    if (!this.allowIntercomGranted) {
      this.callbacks.onStreamError?.('Le propriétaire n’a pas autorisé le haut-parleur Talkie.');
      return;
    }
    if (this.talkieActive) return;

    try {
      const micTrack = await this.ensureBrowserMicTrack();
      this.talkieActive = true;
      this.callbacks.onTalkieStateChange?.(true);

      if (this.localMicStream) {
        this.startMicVuMeter(this.localMicStream);
      }

      if (this.pc && this.talkieAudioSender && this.pc.connectionState !== 'closed') {
        // Instant 0ms track swap on existing Camera/Screen WebRTC tunnel!
        this.startedIntercomOnlySession = false;
        await this.talkieAudioSender.replaceTrack(micTrack);
        await this.signaling.send(
          {
            type: 'CMD_INTERCOM_STATE',
            sessionId: this.activeSessionId,
            active: true,
            ts: Date.now(),
          },
          'sess'
        );
      } else {
        // Camera is OFF (Eco-Standby): launch audio-only Walkie-Talkie tunnel (0% camera usage!)
        this.startedIntercomOnlySession = true;
        await this.startCameraStream('front', false, 'sd', 'intercom');
      }
    } catch (err) {
      this.talkieActive = false;
      this.stopMicVuMeter();
      this.callbacks.onTalkieStateChange?.(false);
      this.callbacks.onStreamError?.(
        err instanceof Error
          ? `Micro navigateur indisponible : ${err.message}`
          : 'Autorisez le microphone dans votre navigateur pour utiliser le Talkie.'
      );
    }
  }

  public async stopTalkie(): Promise<void> {
    if (!this.talkieActive) return;
    this.talkieActive = false;
    this.stopMicVuMeter();
    this.callbacks.onTalkieStateChange?.(false);

    const micTrack = this.localMicStream?.getAudioTracks()[0];
    if (micTrack) {
      micTrack.enabled = false;
    }

    if (this.talkieAudioSender) {
      try {
        const silent = this.ensureSilentAudioTrack();
        await this.talkieAudioSender.replaceTrack(silent);
      } catch {
        // Ignore
      }
    }

    if (this.signaling && this.authenticated && this.activeSessionId) {
      await this.signaling.send(
        {
          type: 'CMD_INTERCOM_STATE',
          sessionId: this.activeSessionId,
          active: false,
          ts: Date.now(),
        },
        'sess'
      );
    }

    if (this.startedIntercomOnlySession) {
      this.startedIntercomOnlySession = false;
      await this.stopCameraStream();
    }
  }

  public async sendServerlessPushInvitation(rawCode?: string): Promise<boolean> {
    const code = normalizePairingCode(rawCode || this.pairingCode);
    if (code.length !== 6) return false;
    try {
      const keys = await deriveBootstrapKeys(code);
      const response = await fetch(`https://ntfy.sh/${keys.wakePushTopic}`, {
        method: 'POST',
        headers: {
          Title: 'SentinelCam — Demande de connexion',
          Priority: 'high',
          Tags: 'camera,bell',
        },
        body: JSON.stringify({
          type: 'WAKE_INVITE',
          ts: Date.now(),
        }),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  public async requestContacts(query = ''): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_CONTACTS',
        requestId: randomHex(8),
        query: query.trim(),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestLocation(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_LOCATION',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestLocationHistory(
    fromTs: number,
    toTs: number,
    maxPoints = 1800
  ): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_LOCATION_HISTORY',
        requestId: randomHex(8),
        fromTs,
        toTs,
        maxPoints,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async clearRemoteLocationHistory(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_CLEAR_LOCATION_HISTORY',
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestDeviceInfo(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_DEVICE_INFO',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async sendRemoteSms(options: {
    phoneNumber: string;
    contactName?: string;
    message: string;
    includeGpsLink?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SEND_SMS',
        requestId: randomHex(8),
        phoneNumber: options.phoneNumber,
        contactName: options.contactName,
        message: options.message,
        includeGpsLink: Boolean(options.includeGpsLink),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async toggleTorch(enable: boolean): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send({ type: 'CMD_TOGGLE_TORCH', enable, ts: Date.now() }, 'sess');
  }

  public async ringDevice(active: boolean, durationSec = 15): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send({ type: 'CMD_RING_DEVICE', active, durationSec, ts: Date.now() }, 'sess');
  }

  public async speakText(text: string): Promise<void> {
    if (!this.signaling || !this.authenticated || !text.trim()) return;
    await this.signaling.send({ type: 'CMD_SPEAK_TEXT', text: text.trim().slice(0, 240), ts: Date.now() }, 'sess');
  }

  public async lockRemoteScreen(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      { type: 'CMD_LOCK_SCREEN', requestId: randomHex(8), ts: Date.now() },
      'sess'
    );
  }

  public async requestScreenTime(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      { type: 'CMD_GET_SCREEN_TIME', requestId: randomHex(8), ts: Date.now() },
      'sess'
    );
  }

  public async setRemoteAppLock(options: {
    packageName: string;
    appName: string;
    durationMinutes: number;
    untilMs?: number;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_APP_LOCK',
        requestId: randomHex(8),
        packageName: options.packageName,
        appName: options.appName,
        durationMinutes: options.durationMinutes,
        untilMs: options.untilMs,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteScreenPause(durationMinutes: number, lockImmediately = true): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_SCREEN_PAUSE',
        requestId: randomHex(8),
        durationMinutes,
        lockImmediately,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async clearAllRemoteAppLocks(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_CLEAR_ALL_APP_LOCKS',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteAdminPin(newPin: string, enableProtection = true): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_ADMIN_PIN',
        requestId: randomHex(8),
        newPin: newPin.trim(),
        enableProtection,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteCurfew(opts: {
    enabled: boolean;
    startMin: number;
    endMin: number;
    daysMask: number;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_CURFEW',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteAppQuota(opts: {
    packageName: string;
    appName: string;
    dailyLimitMinutes: number;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_APP_QUOTA',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async saveRemoteAppGroupQuota(opts: {
    id?: string;
    name?: string;
    packages?: string[];
    dailyLimitMinutes?: number;
    delete?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SAVE_APP_GROUP_QUOTA',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteSchoolMode(opts: {
    durationMinutes?: number;
    allowedPackages?: string[];
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_SCHOOL_MODE',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteAntiBypass(enabled: boolean): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_ANTI_BYPASS',
        requestId: randomHex(8),
        enabled,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteLostMode(opts: {
    enabled: boolean;
    message?: string;
    phoneNumber?: string;
    ringNow?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_LOST_MODE',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async respondTimeRequest(opts: {
    timeRequestId: string;
    approve: boolean;
    grantMinutes?: number;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_RESPOND_TIME_REQUEST',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async saveRemoteGeofence(opts: {
    id?: string;
    name?: string;
    latitude?: number;
    longitude?: number;
    radiusMeters?: number;
    delete?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SAVE_GEOFENCE',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async clearRemoteSecurityEvents(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_CLEAR_SECURITY_EVENTS',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestGallery(page = 0, album = '', pageSize = 18): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_GALLERY',
        requestId: randomHex(8),
        page,
        pageSize,
        album,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestGalleryPhoto(mediaId: string): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_GALLERY_PHOTO',
        requestId: randomHex(8),
        mediaId,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteAiShield(opts: {
    blurFaces?: boolean;
    blurNsfw?: boolean;
    nsfwStrict?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_AI_SHIELD',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemotePolicy(opts: {
    allowLocation?: boolean;
    allowLocationHistory?: boolean;
    gpsTrackingMode?: GpsTrackingMode;
    allowContacts?: boolean;
    allowSmsRelay?: boolean;
    allowGallery?: boolean;
    allowIntercom?: boolean;
    allowDeviceInfo?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SET_REMOTE_POLICY',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async respondUnpairRequest(approve: boolean): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_UNPAIR_DECISION',
        requestId: randomHex(8),
        approve,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestSmsThreads(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_SMS_THREADS',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestSmsMessages(threadId: string, address = ''): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_SMS_MESSAGES',
        requestId: randomHex(8),
        threadId,
        address,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async performGalleryAction(
    action:
      | 'delete'
      | 'quarantine'
      | 'list_quarantine'
      | 'get_quarantine_photo'
      | 'restore_quarantine'
      | 'delete_quarantine',
    mediaId?: string
  ): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GALLERY_ACTION',
        requestId: randomHex(8),
        action,
        mediaId,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async flashRemoteText(text: string, durationSec = 3): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_FLASH_SCREEN',
        requestId: randomHex(8),
        mode: 'text',
        text: text.trim().slice(0, 280),
        durationSec: Math.max(1, Math.min(60, Math.round(durationSec))),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async flashRemoteImage(
    base64Jpeg: string,
    durationSec = 5,
    caption = ''
  ): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    const clean = base64Jpeg.includes(',') ? base64Jpeg.split(',')[1] : base64Jpeg;
    const reqId = randomHex(8);
    const chunkSize = 16_000;
    const totalChunks = Math.max(1, Math.ceil(clean.length / chunkSize));
    const safeSec = Math.max(1, Math.min(60, Math.round(durationSec)));

    for (let i = 0; i < totalChunks; i++) {
      const slice = clean.slice(i * chunkSize, (i + 1) * chunkSize);
      await this.signaling.send(
        {
          type: 'CMD_FLASH_SCREEN',
          requestId: reqId,
          mode: 'image',
          caption: i === 0 ? caption.trim().slice(0, 160) : undefined,
          durationSec: safeSec,
          chunkIndex: i,
          totalChunks,
          data: slice,
          ts: Date.now(),
        },
        'sess'
      );
      if (totalChunks > 1) {
        await new Promise((r) => window.setTimeout(r, 60));
      }
    }
  }

  public async requestCallLog(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_CALL_LOG',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async updateCallBlockRule(opts: {
    action: 'add_block' | 'remove_block' | 'add_allow' | 'remove_allow' | 'set_flags';
    number?: string;
    label?: string;
    blockUnknownCalls?: boolean;
    callWhitelistOnly?: boolean;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_CALL_BLOCK_RULE',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async performRecordingAction(opts: {
    action: 'list' | 'start' | 'stop' | 'delete' | 'get_file';
    mode?: 'audio' | 'video';
    durationMinutes?: number;
    facing?: CameraFacingMode;
    recordingId?: string;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_RECORDING_ACTION',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async sendSystemControl(opts: {
    action:
      | 'get_status'
      | 'set_volume'
      | 'set_ringer'
      | 'set_brightness'
      | 'launch_app'
      | 'uninstall_app'
      | 'mdm_hide_app'
      | 'mdm_set_restriction'
      | 'mdm_set_screen_timeout'
      | 'mdm_lock_now'
      | 'mdm_reboot';
    stream?: 'music' | 'ring' | 'alarm';
    percent?: number;
    mode?: 'normal' | 'vibrate' | 'silent';
    packageName?: string;
    hidden?: boolean;
    target?:
      | 'wallpaper'
      | 'location'
      | 'date_time'
      | 'unknown_sources'
      | 'install_apps'
      | 'uninstall_apps'
      | 'safe_boot'
      | 'factory_reset'
      | 'usb_debug'
      | 'screenshots'
      | 'camera'
      | 'tethering';
    enabled?: boolean;
    timeoutSec?: number;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SYSTEM_CONTROL',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async performSocialAction(opts: {
    action: 'list_threads' | 'get_messages' | 'reply' | 'set_keywords' | 'clear';
    appFilter?: string;
    threadKey?: string;
    body?: string;
    keywords?: string[];
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_SOCIAL_ACTION',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async requestInsights(): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_INSIGHTS',
        requestId: randomHex(8),
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async performTimelineAction(opts: {
    action: 'list' | 'configure' | 'get_image' | 'delete' | 'clear';
    page?: number;
    pageSize?: number;
    enabled?: boolean;
    intervalSec?: number;
    monitoredPackages?: string[];
    captureId?: string;
  }): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_TIMELINE_ACTION',
        requestId: randomHex(8),
        ...opts,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async setRemoteWallpaper(
    base64Jpeg: string,
    target: 'home' | 'lock' | 'both' = 'both'
  ): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    const clean = base64Jpeg.includes(',') ? base64Jpeg.split(',')[1] : base64Jpeg;
    const reqId = randomHex(8);
    const chunkSize = 16_000;
    const totalChunks = Math.max(1, Math.ceil(clean.length / chunkSize));

    for (let i = 0; i < totalChunks; i++) {
      const slice = clean.slice(i * chunkSize, (i + 1) * chunkSize);
      await this.signaling.send(
        {
          type: 'CMD_SET_WALLPAPER',
          requestId: reqId,
          target,
          chunkIndex: i,
          totalChunks,
          data: slice,
          ts: Date.now(),
        },
        'sess'
      );
      if (totalChunks > 1) {
        await new Promise((r) => window.setTimeout(r, 55));
      }
    }
  }

  public async requestDrivingReport(days = 7): Promise<void> {
    if (!this.signaling || !this.authenticated) return;
    await this.signaling.send(
      {
        type: 'CMD_GET_DRIVING_REPORT',
        requestId: randomHex(8),
        days,
        ts: Date.now(),
      },
      'sess'
    );
  }

  private async handleSignalMessage(msg: SignalMessage, _mode: 'boot' | 'sess'): Promise<void> {
    switch (msg.type) {
      case 'HELLO_ACK':
        await this.handleHelloAck(msg);
        break;

      case 'PORTAL_STATUS':
        this.handlePortalStatus(msg);
        break;

      case 'AUTH_ERR':
        this.handleAuthErr(msg);
        break;

      case 'SDP_OFFER':
        await this.handleSdpOffer(msg);
        break;

      case 'ICE_CANDIDATE':
        await this.handleRemoteIceCandidate(msg);
        break;

      case 'STREAM_STATE':
        this.handleStreamState(msg);
        break;

      case 'DATA_CONTACTS':
        this.callbacks.onContactsData?.(msg);
        break;

      case 'DATA_LOCATION':
        this.callbacks.onLocationData?.(msg);
        break;

      case 'DATA_LOCATION_HISTORY':
        this.handleLocationHistoryChunk(msg);
        break;

      case 'DATA_DEVICE_INFO':
        this.callbacks.onDeviceInfoData?.(msg);
        break;

      case 'DATA_SMS_RESULT':
        this.callbacks.onSmsResult?.(msg);
        break;

      case 'DATA_SMS_THREADS':
        this.callbacks.onSmsThreadsData?.(msg);
        break;

      case 'DATA_SMS_MESSAGES':
        this.callbacks.onSmsMessagesData?.(msg);
        break;

      case 'DATA_SCREEN_TIME':
        this.callbacks.onScreenTimeData?.(msg);
        break;

      case 'DATA_CONTROL_RESULT':
        this.callbacks.onControlResult?.(msg);
        break;

      case 'DATA_GALLERY':
        this.callbacks.onGalleryData?.(msg);
        break;

      case 'DATA_QUARANTINE_LIST':
        this.callbacks.onQuarantineListData?.(msg);
        break;

      case 'DATA_GALLERY_PHOTO':
        this.handleGalleryPhotoChunk(msg);
        break;

      case 'DATA_CALL_LOG':
        this.callbacks.onCallLogData?.(msg);
        break;

      case 'DATA_RECORDINGS':
        this.callbacks.onRecordingsData?.(msg);
        break;

      case 'DATA_RECORDING_FILE':
        this.handleRecordingFileChunk(msg);
        break;

      case 'DATA_SYSTEM_STATUS':
        this.callbacks.onSystemStatusData?.(msg);
        break;

      case 'DATA_SOCIAL_THREADS':
        this.callbacks.onSocialThreadsData?.(msg);
        break;

      case 'DATA_SOCIAL_MESSAGES':
        this.callbacks.onSocialMessagesData?.(msg);
        break;

      case 'DATA_INSIGHTS':
        this.callbacks.onInsightsData?.(msg);
        break;

      case 'DATA_TIMELINE':
        this.callbacks.onTimelineData?.(msg);
        break;

      case 'DATA_TIMELINE_IMAGE':
        this.handleTimelineImageChunk(msg);
        break;

      case 'DATA_DRIVING_REPORT':
        this.callbacks.onDrivingReportData?.(msg);
        break;

      default:
        break;
    }
  }

  private handleTimelineImageChunk(msg: DataTimelineImageMsg): void {
    if (msg.error) {
      this.callbacks.onTimelineImageData?.({ captureId: msg.captureId, error: msg.error });
      return;
    }
    if (msg.totalChunks <= 1) {
      this.callbacks.onTimelineImageData?.({ captureId: msg.captureId, base64: msg.data });
      return;
    }
    let bucket = this.timelineImageReassembly.get(msg.requestId);
    if (!bucket) {
      bucket = {
        captureId: msg.captureId,
        totalChunks: msg.totalChunks,
        chunks: new Map<number, string>(),
      };
      this.timelineImageReassembly.set(msg.requestId, bucket);
    }
    bucket.chunks.set(msg.chunkIndex, msg.data);
    if (bucket.chunks.size >= bucket.totalChunks) {
      this.timelineImageReassembly.delete(msg.requestId);
      let full = '';
      for (let i = 0; i < bucket.totalChunks; i++) {
        full += bucket.chunks.get(i) || '';
      }
      this.callbacks.onTimelineImageData?.({ captureId: bucket.captureId, base64: full });
    }
  }

  private handleRecordingFileChunk(msg: DataRecordingFileMsg): void {
    if (msg.error) {
      this.callbacks.onRecordingFileData?.({
        recordingId: msg.recordingId,
        mimeType: msg.mimeType || 'audio/mp4',
        error: msg.error,
      });
      return;
    }
    if (msg.totalChunks <= 1) {
      this.callbacks.onRecordingFileData?.({
        recordingId: msg.recordingId,
        mimeType: msg.mimeType || 'audio/mp4',
        base64: msg.data,
      });
      return;
    }
    let bucket = this.recordingReassembly.get(msg.requestId);
    if (!bucket) {
      bucket = {
        recordingId: msg.recordingId,
        mimeType: msg.mimeType || 'audio/mp4',
        totalChunks: msg.totalChunks,
        chunks: new Map<number, string>(),
      };
      this.recordingReassembly.set(msg.requestId, bucket);
    }
    bucket.chunks.set(msg.chunkIndex, msg.data);
    this.callbacks.onRecordingFileProgress?.({
      recordingId: bucket.recordingId,
      received: bucket.chunks.size,
      total: bucket.totalChunks,
    });
    if (bucket.chunks.size >= bucket.totalChunks) {
      this.recordingReassembly.delete(msg.requestId);
      let full = '';
      for (let i = 0; i < bucket.totalChunks; i++) {
        full += bucket.chunks.get(i) || '';
      }
      this.callbacks.onRecordingFileData?.({
        recordingId: bucket.recordingId,
        mimeType: bucket.mimeType,
        base64: full,
      });
    }
  }

  private handleGalleryPhotoChunk(msg: DataGalleryPhotoMsg): void {
    if (msg.error) {
      this.callbacks.onGalleryPhotoData?.({ mediaId: msg.mediaId, error: msg.error });
      return;
    }
    if (msg.totalChunks <= 1) {
      this.callbacks.onGalleryPhotoData?.({ mediaId: msg.mediaId, base64: msg.data });
      return;
    }
    let bucket = this.photoReassembly.get(msg.requestId);
    if (!bucket) {
      bucket = {
        mediaId: msg.mediaId,
        totalChunks: msg.totalChunks,
        chunks: new Map<number, string>(),
      };
      this.photoReassembly.set(msg.requestId, bucket);
    }
    bucket.chunks.set(msg.chunkIndex, msg.data);
    if (bucket.chunks.size >= bucket.totalChunks) {
      this.photoReassembly.delete(msg.requestId);
      let full = '';
      for (let i = 0; i < bucket.totalChunks; i++) {
        full += bucket.chunks.get(i) || '';
      }
      this.callbacks.onGalleryPhotoData?.({ mediaId: bucket.mediaId, base64: full });
    }
  }

  private async handleHelloAck(msg: HelloAckMsg): Promise<void> {
    if (!this.signaling || !this.bootstrapKeys) return;

    const serverEcdhPub = msg.serverEcdhPub ?? '';
    const expectedServerProof = await computeHelloAckProof(
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.echoClientNonce,
      msg.serverNonce,
      msg.deviceId,
      serverEcdhPub
    );

    if (!constantTimeEqualHex(expectedServerProof, msg.serverProofHex)) {
      return;
    }

    let ecdhSharedSecret: Uint8Array | null = null;
    if (serverEcdhPub) {
      const matchingPair = this.ecdhPairsByNonce.get(msg.echoClientNonce);
      if (!matchingPair) return; // Reject unsolicited or mismatched PFS handshake
      ecdhSharedSecret = await computeEcdhSharedSecret(matchingPair.privateKey, serverEcdhPub);
      if (!ecdhSharedSecret) return;
    }
    // Zero ephemeral private keys immediately after deriving the session key
    this.ecdhPairsByNonce.clear();

    const sessionKey = await deriveFastSessionKey(
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.echoClientNonce,
      msg.serverNonce,
      msg.deviceId,
      ecdhSharedSecret
    );
    this.signaling.setSessionKey(sessionKey);

    const localSas = await computeSas(
      this.pairingCode,
      this.tokenId,
      msg.deviceId,
      this.bootstrapKeys.bootstrapAuthRaw
    );

    const accessGrant: ActiveAccessGrant = {
      role: msg.accessRole ?? 'owner',
      name: msg.accessName ?? 'Propriétaire',
      expiresAt: msg.accessExpiresAt ?? null,
      allowAudio: msg.allowAudio ?? true,
      allowSwitchCamera: msg.allowSwitchCamera ?? true,
      allowScreenShare: msg.allowScreenShare ?? false,
      allowContacts: msg.allowContacts ?? false,
      allowLocation: msg.allowLocation ?? false,
      allowDeviceInfo: msg.allowDeviceInfo ?? true,
      allowIntercom: msg.allowIntercom ?? true,
      allowSmsRelay: msg.allowSmsRelay ?? false,
      allowDeviceControl: msg.allowDeviceControl ?? (msg.accessRole === 'owner' || !msg.accessRole),
    };
    this.allowIntercomGranted = accessGrant.allowIntercom;

    this.authenticated = true;

    savePairedDevice({
      pairingCode: this.pairingCode,
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      tokenId: this.tokenId,
      sasCode: localSas.sasCode,
      sasSymbols: localSas.sasSymbols,
      accessRole: accessGrant.role,
      accessName: accessGrant.name,
      pairedAt: findPairedDeviceByCode(this.pairingCode)?.pairedAt ?? Date.now(),
      lastConnectedAt: Date.now(),
    });

    this.callbacks.onTelemetry({
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      portalState: msg.portalState,
      paired: true,
      pairedTokenId: this.tokenId,
      lockoutRemainingMs: 0,
      failedAttempts: 0,
      batteryLevel: msg.batteryLevel,
      isCharging: msg.isCharging,
      networkType: msg.networkType,
      cameraFacing: msg.cameraFacing,
      mediaMode: msg.mediaMode ?? 'camera',
      micEnabled: msg.micEnabled,
      intercomActive: msg.intercomActive ?? false,
      torchEnabled: msg.torchEnabled ?? false,
      ringingActive: msg.ringingActive ?? false,
      uptimeSec: msg.uptimeSec,
      accessRole: accessGrant.role,
      accessName: accessGrant.name,
      accessExpiresAt: accessGrant.expiresAt,
      allowAudio: accessGrant.allowAudio,
      allowSwitchCamera: accessGrant.allowSwitchCamera,
      allowScreenShare: accessGrant.allowScreenShare,
      allowContacts: accessGrant.allowContacts,
      allowLocation: accessGrant.allowLocation,
      allowDeviceInfo: accessGrant.allowDeviceInfo,
      allowIntercom: accessGrant.allowIntercom,
      allowSmsRelay: accessGrant.allowSmsRelay,
      allowDeviceControl: accessGrant.allowDeviceControl,
      activeAppLocksCount: msg.activeAppLocksCount,
      screenPausedUntilMs: msg.screenPausedUntilMs,
      adminProtectionEnabled: msg.adminProtectionEnabled,
      hasAdminPin: msg.hasAdminPin,
      deviceAdminActive: msg.deviceAdminActive,
      lostModeEnabled: msg.lostModeEnabled,
      curfewEnabled: msg.curfewEnabled,
      schoolModeUntilMs: msg.schoolModeUntilMs,
      blockSettingsAndStore: msg.blockSettingsAndStore,
      pendingRequestsCount: msg.pendingRequestsCount,
      securityAlertsCount: msg.securityAlertsCount,
      allowGallery: msg.allowGallery,
      hasGalleryPermission: msg.hasGalleryPermission,
      aiBlurFaces: msg.aiBlurFaces,
      aiBlurNsfw: msg.aiBlurNsfw,
      aiNsfwStrict: msg.aiNsfwStrict,
      aiShieldRunning: msg.aiShieldRunning,
      aiShieldAutonomous: msg.aiShieldAutonomous,
      aiShieldNeedsConsent: msg.aiShieldNeedsConsent,
      timelineCaptureEnabled: msg.timelineCaptureEnabled,
      timelineRunning: msg.timelineRunning,
      timelineNeedsConsent: msg.timelineNeedsConsent,
      parentManaged: msg.parentManaged,
      unpairRequestPending: msg.unpairRequestPending,
      unpairRequestedAt: msg.unpairRequestedAt,
      pfsEnabled: Boolean(ecdhSharedSecret) || Boolean(msg.pfsEnabled),
      gpsTrackerState: msg.gpsTrackerState,
      gpsTrackingMode: msg.gpsTrackingMode,
      gpsPointsCount: msg.gpsPointsCount,
      gpsTodayPointsCount: msg.gpsTodayPointsCount,
      offlineRecordingActive: msg.offlineRecordingActive,
      offlineRecordingMode: msg.offlineRecordingMode,
      permissionsHealth: msg.permissionsHealth,
      updatedAt: Date.now(),
    });

    this.callbacks.onAuthSuccess({
      deviceName: msg.deviceName,
      deviceId: msg.deviceId,
      sasCode: localSas.sasCode,
      sasSymbols: localSas.sasSymbols,
      accessGrant,
    });

    if (!this.desiredStreaming && !this.pc) {
      this.callbacks.onStateChange('standby');
    }
  }

  private handlePortalStatus(msg: PortalStatusMsg): void {
    if (msg.allowIntercom !== undefined) {
      this.allowIntercomGranted = msg.allowIntercom;
      if (!msg.allowIntercom && this.talkieActive) {
        void this.stopTalkie();
      }
    }

    this.callbacks.onTelemetry({
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      portalState: msg.portalState,
      paired: msg.paired,
      pairedTokenId: msg.pairedTokenId,
      lockoutRemainingMs: msg.lockoutRemainingMs,
      failedAttempts: msg.failedAttempts,
      batteryLevel: msg.batteryLevel,
      isCharging: msg.isCharging,
      batteryTempC: msg.batteryTempC,
      networkType: msg.networkType,
      cameraFacing: msg.cameraFacing,
      mediaMode: msg.mediaMode ?? 'camera',
      micEnabled: msg.micEnabled,
      intercomActive: msg.intercomActive ?? false,
      torchEnabled: msg.torchEnabled ?? false,
      ringingActive: msg.ringingActive ?? false,
      uptimeSec: msg.uptimeSec,
      accessRole: msg.accessRole,
      accessName: msg.accessName,
      accessExpiresAt: msg.accessExpiresAt,
      allowAudio: msg.allowAudio,
      allowSwitchCamera: msg.allowSwitchCamera,
      allowScreenShare: msg.allowScreenShare,
      allowContacts: msg.allowContacts,
      allowLocation: msg.allowLocation,
      allowDeviceInfo: msg.allowDeviceInfo,
      allowIntercom: msg.allowIntercom,
      allowSmsRelay: msg.allowSmsRelay,
      allowDeviceControl: msg.allowDeviceControl,
      activeAppLocksCount: msg.activeAppLocksCount,
      screenPausedUntilMs: msg.screenPausedUntilMs,
      adminProtectionEnabled: msg.adminProtectionEnabled,
      hasAdminPin: msg.hasAdminPin,
      deviceAdminActive: msg.deviceAdminActive,
      lostModeEnabled: msg.lostModeEnabled,
      curfewEnabled: msg.curfewEnabled,
      schoolModeUntilMs: msg.schoolModeUntilMs,
      blockSettingsAndStore: msg.blockSettingsAndStore,
      pendingRequestsCount: msg.pendingRequestsCount,
      securityAlertsCount: msg.securityAlertsCount,
      allowGallery: msg.allowGallery,
      hasGalleryPermission: msg.hasGalleryPermission,
      aiBlurFaces: msg.aiBlurFaces,
      aiBlurNsfw: msg.aiBlurNsfw,
      aiNsfwStrict: msg.aiNsfwStrict,
      aiShieldRunning: msg.aiShieldRunning,
      aiShieldAutonomous: msg.aiShieldAutonomous,
      aiShieldNeedsConsent: msg.aiShieldNeedsConsent,
      timelineCaptureEnabled: msg.timelineCaptureEnabled,
      timelineRunning: msg.timelineRunning,
      timelineNeedsConsent: msg.timelineNeedsConsent,
      parentManaged: msg.parentManaged,
      unpairRequestPending: msg.unpairRequestPending,
      unpairRequestedAt: msg.unpairRequestedAt,
      pfsEnabled: msg.pfsEnabled,
      gpsTrackerState: msg.gpsTrackerState,
      gpsTrackingMode: msg.gpsTrackingMode,
      gpsPointsCount: msg.gpsPointsCount,
      gpsTodayPointsCount: msg.gpsTodayPointsCount,
      offlineRecordingActive: msg.offlineRecordingActive,
      offlineRecordingMode: msg.offlineRecordingMode,
      permissionsHealth: msg.permissionsHealth,
      updatedAt: Date.now(),
    });

    if (msg.portalState === 'locked') {
      this.callbacks.onStateChange('locked');
    } else if (this.authenticated && !this.pc && !this.desiredStreaming) {
      this.callbacks.onStateChange('standby');
    } else if (
      this.authenticated &&
      this.desiredStreaming &&
      this.desiredMediaMode === 'camera' &&
      msg.portalState === 'standby' &&
      (!this.pc || this.pc.connectionState !== 'connected')
    ) {
      // Auto-recover camera seamlessly if a system permission dialog briefly interrupted capture
      if (this.streamRecoverTimer === null) {
        this.streamRecoverTimer = window.setTimeout(() => {
          this.streamRecoverTimer = null;
          if (this.authenticated && this.desiredStreaming && this.desiredMediaMode === 'camera') {
            void this.startCameraStream(
              this.desiredFacing,
              this.desiredAudio,
              this.desiredQuality,
              'camera'
            );
          }
        }, 900);
      }
    }
  }

  private handleLocationHistoryChunk(msg: DataLocationHistoryMsg): void {
    const providerDecode = (code: string): string => {
      switch (code) {
        case 'g':
          return 'gps';
        case 'n':
          return 'network';
        case 'f':
          return 'fused';
        default:
          return 'passive';
      }
    };

    const parsed: GpsTrackPoint[] = (msg.points ?? []).map((tuple) => ({
      timestamp: Number(tuple[0] ?? 0),
      latitude: Number(tuple[1] ?? 0),
      longitude: Number(tuple[2] ?? 0),
      accuracyMeters: Number(tuple[3] ?? 0),
      speedKmh: Number(tuple[4] ?? 0),
      batteryLevel: Number(tuple[5] ?? 100),
      dwellSeconds: Number(tuple[6] ?? 0),
      provider: providerDecode(String(tuple[7] ?? 'g')),
    }));

    const reqId = msg.requestId || 'default';
    const total = Math.max(1, msg.totalChunks || 1);

    let bucket = this.historyReassembly.get(reqId);
    if (!bucket) {
      if (this.historyReassembly.size > 6) {
        const oldestKey = this.historyReassembly.keys().next().value;
        if (oldestKey) this.historyReassembly.delete(oldestKey);
      }
      bucket = {
        totalChunks: total,
        chunks: new Map(),
        rawPointCount: msg.rawPointCount ?? parsed.length,
        totalDistanceMeters: msg.totalDistanceMeters ?? 0,
        maxSpeedKmh: msg.maxSpeedKmh ?? 0,
        stopsCount: msg.stopsCount ?? 0,
        trackerState: msg.trackerState ?? 'disabled',
        trackingMode: msg.trackingMode ?? 'eco',
        error: msg.error,
      };
      this.historyReassembly.set(reqId, bucket);
    }

    bucket.chunks.set(msg.chunkIndex ?? 0, parsed);
    if (msg.error) bucket.error = msg.error;

    if (bucket.chunks.size >= bucket.totalChunks) {
      this.historyReassembly.delete(reqId);
      const merged: GpsTrackPoint[] = [];
      for (let i = 0; i < bucket.totalChunks; i++) {
        const part = bucket.chunks.get(i);
        if (part) merged.push(...part);
      }
      merged.sort((a, b) => a.timestamp - b.timestamp);

      this.callbacks.onLocationHistoryData?.({
        points: merged,
        rawPointCount: bucket.rawPointCount,
        totalDistanceMeters: bucket.totalDistanceMeters,
        maxSpeedKmh: bucket.maxSpeedKmh,
        stopsCount: bucket.stopsCount,
        trackerState: bucket.trackerState,
        trackingMode: bucket.trackingMode,
        error: bucket.error,
      });
    }
  }

  private handleAuthErr(msg: AuthErrMsg): void {
    this.authenticated = false;
    this.desiredStreaming = false;
    this.talkieActive = false;
    this.stopMicVuMeter();
    this.callbacks.onTalkieStateChange?.(false);
    this.closePeerConnectionOnly();

    if (msg.reason === 'ACCESS_EXPIRED' || msg.reason === 'ACCESS_REVOKED') {
      if (this.visibilityListener && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.visibilityListener);
      this.visibilityListener = null;
    }

    if (this.retryTimer !== null) {
        window.clearInterval(this.retryTimer);
        this.retryTimer = null;
      }
      if (this.streamRecoverTimer !== null) {
        window.clearTimeout(this.streamRecoverTimer);
        this.streamRecoverTimer = null;
      }
      if (this.signaling) {
        this.signaling.destroy();
        this.signaling = null;
      }
      this.callbacks.onStateChange('offline');
    } else if (msg.reason === 'LOCKED_OUT') {
      this.callbacks.onStateChange('locked');
    }

    this.callbacks.onAuthError(msg);
  }

  public async startCameraStream(
    cameraFacing: CameraFacingMode = 'front',
    enableAudio = false,
    quality: StreamQualityPreset = 'hd',
    mediaMode: MediaSourceMode = 'camera'
  ): Promise<void> {
    this.desiredStreaming = true;
    this.desiredMediaMode = mediaMode;
    this.desiredFacing = cameraFacing;
    this.desiredAudio = enableAudio;
    this.desiredQuality = quality;
    if (mediaMode !== 'intercom') {
      this.startedIntercomOnlySession = false;
    }

    if (!this.signaling || !this.authenticated) {
      this.callbacks.onStateChange('connecting');
      void this.sendHelloWake();
      return;
    }

    this.closePeerConnectionOnly();

    this.activeSessionId = randomHex(10);
    this.pendingCandidates = [];
    if (mediaMode !== 'intercom') {
      this.callbacks.onStateChange('connecting');
    }

    this.setupPeerConnection(this.activeSessionId);

    await this.signaling.send(
      {
        type: 'CMD_START_STREAM',
        sessionId: this.activeSessionId,
        mediaMode,
        cameraFacing,
        enableAudio,
        quality,
        ts: Date.now(),
      },
      'sess'
    );

    if (this.streamRecoverTimer) window.clearTimeout(this.streamRecoverTimer);
    const currentSid = this.activeSessionId;
    this.streamRecoverTimer = window.setTimeout(() => {
      if (
        this.desiredStreaming &&
        this.authenticated &&
        this.activeSessionId === currentSid &&
        this.pc?.connectionState !== 'connected'
      ) {
        void this.startCameraStream(
          this.desiredFacing,
          this.desiredAudio,
          this.desiredQuality,
          this.desiredMediaMode
        );
      }
    }, 6500);
  }

  public async stopCameraStream(): Promise<void> {
    this.desiredStreaming = false;
    this.startedIntercomOnlySession = false;
    if (this.talkieActive) {
      this.talkieActive = false;
      this.stopMicVuMeter();
      this.callbacks.onTalkieStateChange?.(false);
    }
    if (this.streamRecoverTimer) {
      window.clearTimeout(this.streamRecoverTimer);
      this.streamRecoverTimer = null;
    }

    if (this.signaling && this.authenticated && this.activeSessionId) {
      await this.signaling.send(
        {
          type: 'CMD_STOP_STREAM',
          sessionId: this.activeSessionId,
          ts: Date.now(),
        },
        'sess'
      );
    }
    this.closePeerConnectionOnly();
    if (this.authenticated) {
      this.callbacks.onStateChange('standby');
    }
  }

  public async switchCamera(cameraFacing: CameraFacingMode): Promise<void> {
    this.desiredFacing = cameraFacing;
    if (!this.signaling || !this.authenticated || !this.activeSessionId) return;
    await this.signaling.send(
      {
        type: 'CMD_SWITCH_CAMERA',
        sessionId: this.activeSessionId,
        cameraFacing,
        ts: Date.now(),
      },
      'sess'
    );
  }

  public async toggleRemoteMicrophone(enableAudio: boolean): Promise<void> {
    this.desiredAudio = enableAudio;
    if (!this.signaling || !this.authenticated || !this.activeSessionId) return;
    await this.signaling.send(
      {
        type: 'CMD_TOGGLE_MIC',
        sessionId: this.activeSessionId,
        enableAudio,
        ts: Date.now(),
      },
      'sess'
    );
  }

  private setupPeerConnection(sessionId: string): void {
    const pc = new RTCPeerConnection({
      iceServers: buildIceServers(),
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    });
    this.pc = pc;
    this.talkieAudioSender = null;
    this.remoteStream = new MediaStream();

    pc.ontrack = (event) => {
      if (this.desiredMediaMode === 'intercom') return;
      if (event.streams && event.streams[0]) {
        this.remoteStream = event.streams[0];
      } else if (this.remoteStream) {
        this.remoteStream.addTrack(event.track);
      }
      this.callbacks.onRemoteStream(this.remoteStream);
      this.callbacks.onStateChange('streaming');
    };

    pc.onicecandidate = (event) => {
      if (event.candidate && this.signaling && this.activeSessionId === sessionId) {
        void this.signaling.send(
          {
            type: 'ICE_CANDIDATE',
            sessionId,
            candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
            ts: Date.now(),
          },
          'sess'
        );
      }
    };

    pc.ondatachannel = (event) => {
      const channel = event.channel;
      if (channel.label === 'sentinel-telemetry') {
        channel.onmessage = (ev) => {
          try {
            const data = JSON.parse(String(ev.data)) as PortalStatusMsg;
            if (data && data.type === 'PORTAL_STATUS') {
              this.handlePortalStatus(data);
            }
          } catch {
            // Ignore
          }
        };
      }
    };

    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      if (state === 'connected') {
        if (this.streamRecoverTimer) {
          window.clearTimeout(this.streamRecoverTimer);
          this.streamRecoverTimer = null;
        }
        if (this.desiredMediaMode !== 'intercom') {
          this.callbacks.onStateChange('streaming');
          this.startStatsMonitor();
        }
        // If user is currently holding Talkie, confirm speakerphone active on Android!
        if (this.talkieActive && this.signaling && this.activeSessionId === sessionId) {
          void this.signaling.send(
            {
              type: 'CMD_INTERCOM_STATE',
              sessionId,
              active: true,
              ts: Date.now(),
            },
            'sess'
          );
        }
      } else if (state === 'failed' || state === 'disconnected') {
        this.stopStatsMonitor();
        if (this.desiredStreaming && this.pc === pc) {
          window.setTimeout(() => {
            if (this.desiredStreaming && this.pc === pc && pc.connectionState !== 'connected') {
              this.closePeerConnectionOnly();
              if (this.authenticated) {
                void this.startCameraStream(
                  this.desiredFacing,
                  this.desiredAudio,
                  this.desiredQuality,
                  this.desiredMediaMode
                );
              } else {
                void this.sendHelloWake();
              }
            }
          }, 2200);
        }
      }
    };
  }

  private async handleSdpOffer(msg: SdpOfferMsg): Promise<void> {
    if (!this.signaling) return;

    if (!this.pc || msg.sessionId !== this.activeSessionId) {
      this.closePeerConnectionOnly();
      this.activeSessionId = msg.sessionId;
      this.setupPeerConnection(msg.sessionId);
    }
    if (!this.pc) return;

    this.remoteSdpFingerprint = msg.fingerprint || extractSdpFingerprint(msg.sdp);

    await this.pc.setRemoteDescription(
      new RTCSessionDescription({
        type: 'offer',
        sdp: msg.sdp,
      })
    );

    // Pre-attach Walkie-Talkie outbound audio track (real mic if talking, else silent dummy track)
    // BEFORE createAnswer() so WebRTC negotiates sendrecv/sendonly Opus immediately with 0ms swap latency!
    if (this.allowIntercomGranted) {
      const audioTransceiver = this.pc
        .getTransceivers()
        .find((t) => t.receiver.track?.kind === 'audio');

      if (audioTransceiver) {
        const trackToSend =
          this.talkieActive && this.localMicStream?.getAudioTracks()[0]
            ? this.localMicStream.getAudioTracks()[0]
            : this.ensureSilentAudioTrack();

        if (trackToSend) {
          try {
            await audioTransceiver.sender.replaceTrack(trackToSend);
            audioTransceiver.direction =
              this.desiredMediaMode === 'intercom' ? 'sendonly' : 'sendrecv';
            this.talkieAudioSender = audioTransceiver.sender;
          } catch {
            // Fallback gracefully if transceiver doesn't accept send
          }
        }
      }
    }

    for (const c of this.pendingCandidates) {
      try {
        await this.pc.addIceCandidate(
          new RTCIceCandidate({
            candidate: c.candidate,
            sdpMid: c.sdpMid,
            sdpMLineIndex: c.sdpMLineIndex,
          })
        );
      } catch {
        // Ignore
      }
    }
    this.pendingCandidates = [];

    const answer = await this.pc.createAnswer();
    await this.pc.setLocalDescription(answer);

    const answerSdp = answer.sdp || '';
    this.localSdpFingerprint = extractSdpFingerprint(answerSdp);
    await computeDtlsMediaSas(this.localSdpFingerprint, this.remoteSdpFingerprint);

    await this.signaling.send(
      {
        type: 'SDP_ANSWER',
        sessionId: msg.sessionId,
        sdp: answerSdp,
        fingerprint: this.localSdpFingerprint,
      },
      'sess'
    );
  }

  private async handleRemoteIceCandidate(msg: IceCandidateMsg): Promise<void> {
    if (!this.pc || msg.sessionId !== this.activeSessionId) return;

    if (!this.pc.remoteDescription || !this.pc.remoteDescription.type) {
      this.pendingCandidates.push(msg);
      return;
    }

    try {
      await this.pc.addIceCandidate(
        new RTCIceCandidate({
          candidate: msg.candidate,
          sdpMid: msg.sdpMid,
          sdpMLineIndex: msg.sdpMLineIndex,
        })
      );
    } catch {
      // Ignore
    }
  }

  private handleStreamState(msg: StreamStateMsg): void {
    if (msg.state === 'expired' || msg.state === 'revoked') {
      this.handleAuthErr({
        type: 'AUTH_ERR',
        reason: msg.state === 'expired' ? 'ACCESS_EXPIRED' : 'ACCESS_REVOKED',
        message:
          msg.error ||
          (msg.state === 'expired'
            ? 'La période d’accès de ce pass temporaire est expirée.'
            : 'Cet accès a été suspendu ou révoqué par le propriétaire du téléphone.'),
        lockoutRemainingMs: 0,
        failedAttempts: 0,
        ts: Date.now(),
      });
      return;
    }

    if (msg.state === 'error') {
      const errText = (msg.error || '').toLowerCase();
      const isPermanent =
        this.desiredMediaMode !== 'camera' ||
        errText.includes('permission') ||
        errText.includes('autoriser') ||
        errText.includes('autorisé') ||
        errText.includes('aucun capteur');

      if (!isPermanent && this.desiredStreaming && this.authenticated && this.transientRetryCount < 12) {
        this.transientRetryCount += 1;
        this.closePeerConnectionOnly();
        this.callbacks.onStateChange('connecting');
        this.callbacks.onStreamError?.('Reconnexion automatique de la caméra…');
        const delayMs = Math.min(8000, 1800 + this.transientRetryCount * 900);
        if (this.streamRecoverTimer) window.clearTimeout(this.streamRecoverTimer);
        this.streamRecoverTimer = window.setTimeout(() => {
          this.streamRecoverTimer = null;
          if (this.desiredStreaming && this.authenticated && this.desiredMediaMode === 'camera') {
            void this.startCameraStream(
              this.desiredFacing,
              this.desiredAudio,
              this.desiredQuality,
              'camera'
            );
          }
        }, delayMs);
        return;
      }

      this.desiredStreaming = false;
      this.talkieActive = false;
      this.stopMicVuMeter();
      this.callbacks.onTalkieStateChange?.(false);
      if (this.streamRecoverTimer) {
        window.clearTimeout(this.streamRecoverTimer);
        this.streamRecoverTimer = null;
      }
      this.closePeerConnectionOnly();
      if (this.authenticated) {
        this.callbacks.onStateChange('standby');
      }
      if (msg.error) {
        this.callbacks.onStreamError?.(msg.error);
      }
      return;
    }

    if (msg.state === 'standby') {
      this.desiredStreaming = false;
      this.closePeerConnectionOnly();
      if (this.authenticated) {
        this.callbacks.onStateChange('standby');
      }
    }
  }

  private startStatsMonitor(): void {
    this.stopStatsMonitor();
    this.lastBytesReceived = 0;
    this.lastStatsTimestamp = performance.now();
    this.stalledVideoSeconds = 0;

    this.statsTimer = window.setInterval(async () => {
      if (!this.pc) return;
      try {
        const report = await this.pc.getStats();
        let rttMs = 0;
        let bitrateKbps = 0;
        let fps = 0;
        let width = 0;
        let height = 0;
        let packetsLost = 0;
        let codecId = '';
        let codec = 'H264';
        let dtlsState = 'connected';
        let videoBytesDelta = 0;

        report.forEach((stat) => {
          if (stat.type === 'candidate-pair' && stat.state === 'succeeded' && stat.nominated) {
            if (typeof stat.currentRoundTripTime === 'number') {
              rttMs = Math.round(stat.currentRoundTripTime * 1000);
            }
          }
          if (stat.type === 'inbound-rtp' && stat.kind === 'video') {
            const now = performance.now();
            const bytes = stat.bytesReceived ?? 0;
            videoBytesDelta = bytes - this.lastBytesReceived;
            if (this.lastBytesReceived > 0 && now > this.lastStatsTimestamp) {
              const deltaSec = (now - this.lastStatsTimestamp) / 1000;
              bitrateKbps = Math.round((videoBytesDelta * 8) / 1000 / deltaSec);
            }
            this.lastBytesReceived = bytes;
            this.lastStatsTimestamp = now;

            fps = Math.round(stat.framesPerSecond ?? 0);
            width = stat.frameWidth ?? 0;
            height = stat.frameHeight ?? 0;
            packetsLost = stat.packetsLost ?? 0;
            if (stat.codecId) codecId = stat.codecId;
          }
          if (stat.type === 'transport' && stat.dtlsState) {
            dtlsState = stat.dtlsState;
          }
        });

        // Frozen-stream watchdog: if video stops delivering bytes for 7s, silently restart camera stream!
        if (this.desiredStreaming && this.desiredMediaMode === 'camera') {
          if (videoBytesDelta > 0) {
            this.stalledVideoSeconds = 0;
            this.transientRetryCount = 0;
          } else {
            this.stalledVideoSeconds += 1;
            if (this.stalledVideoSeconds >= 7) {
              this.stalledVideoSeconds = 0;
              this.closePeerConnectionOnly();
              if (this.authenticated) {
                void this.startCameraStream(
                  this.desiredFacing,
                  this.desiredAudio,
                  this.desiredQuality,
                  'camera'
                );
              }
              return;
            }
          }
        }

        if (codecId) {
          const codecStat = report.get(codecId);
          if (codecStat && codecStat.mimeType) {
            codec = String(codecStat.mimeType).replace('video/', '');
          }
        }

        this.callbacks.onStatsUpdate({
          rttMs,
          bitrateKbps: Math.max(0, bitrateKbps),
          fps,
          width,
          height,
          packetsLost,
          codec,
          dtlsState,
          localFingerprint: this.localSdpFingerprint,
          remoteFingerprint: this.remoteSdpFingerprint,
        });
      } catch {
        // Ignore
      }
    }, 1000);
  }

  private stopStatsMonitor(): void {
    if (this.statsTimer !== null) {
      window.clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
    this.callbacks.onStatsUpdate(null);
  }

  public captureSnapshot(videoEl: HTMLVideoElement, deviceName: string): SnapshotItem | null {
    const width = videoEl.videoWidth;
    const height = videoEl.videoHeight;
    if (!width || !height) return null;

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;

    ctx.drawImage(videoEl, 0, 0, width, height);

    const stamp = new Date().toLocaleString('fr-FR');
    const label = `SENTINELCAM • ${deviceName.toUpperCase()} • ${stamp}`;
    ctx.font = `600 ${Math.max(13, Math.round(height * 0.022))}px "JetBrains Mono", monospace`;
    const metrics = ctx.measureText(label);
    const padX = 12;
    const boxH = Math.max(26, Math.round(height * 0.042));
    ctx.fillStyle = 'rgba(5, 8, 15, 0.72)';
    ctx.fillRect(14, height - boxH - 14, metrics.width + padX * 2, boxH);
    ctx.fillStyle = '#34d399';
    ctx.fillText(label, 14 + padX, height - 14 - Math.round(boxH * 0.28));

    const dataUrl = canvas.toDataURL('image/png');
    const item: SnapshotItem = {
      id: randomHex(6),
      dataUrl,
      timestamp: Date.now(),
      deviceName,
      width,
      height,
    };

    const link = document.createElement('a');
    link.href = dataUrl;
    link.download = `SentinelCam_${deviceName.replace(/\s+/g, '_')}_${Date.now()}.png`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    return item;
  }

  private closePeerConnectionOnly(): void {
    this.stopStatsMonitor();
    this.activeSessionId = '';
    this.talkieAudioSender = null;
    if (this.pc) {
      try {
        this.pc.ontrack = null;
        this.pc.onicecandidate = null;
        this.pc.onconnectionstatechange = null;
        this.pc.close();
      } catch {
        // Ignore
      }
      this.pc = null;
    }
    this.remoteStream = null;
    this.callbacks.onRemoteStream(null);
  }

  public disconnect(): void {
    this.desiredStreaming = false;
    this.talkieActive = false;
    this.startedIntercomOnlySession = false;
    this.stopMicVuMeter();
    this.callbacks.onTalkieStateChange?.(false);

    if (this.localMicStream) {
      this.localMicStream.getTracks().forEach((t) => t.stop());
      this.localMicStream = null;
    }
    if (this.silentAudioTrack) {
      this.silentAudioTrack.stop();
      this.silentAudioTrack = null;
    }
    if (this.silentAudioCtx) {
      void this.silentAudioCtx.close().catch(() => {});
      this.silentAudioCtx = null;
    }

    if (this.visibilityListener && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.visibilityListener);
      this.visibilityListener = null;
    }

    if (this.retryTimer !== null) {
      window.clearInterval(this.retryTimer);
      this.retryTimer = null;
    }
    if (this.streamRecoverTimer !== null) {
      window.clearTimeout(this.streamRecoverTimer);
      this.streamRecoverTimer = null;
    }
    this.closePeerConnectionOnly();
    if (this.signaling) {
      this.signaling.destroy();
      this.signaling = null;
    }
    this.authenticated = false;
    this.callbacks.onStateChange('offline');
  }
}
