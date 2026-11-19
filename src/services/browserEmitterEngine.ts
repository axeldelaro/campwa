import {
  calculateLockoutDurationMs,
  computeHelloAckProof,
  computeHelloWakeProof,
  computeSas,
  constantTimeEqualHex,
  deriveBootstrapKeys,
  deriveFastSessionKey,
  extractSdpFingerprint,
  generateSecurePairingCode,
  normalizePairingCode,
  randomHex,
} from '../crypto/cryptoEngine';
import type {
  AccessProfile,
  AccessProfileInput,
  AccessProfileRole,
  CameraFacingMode,
  CmdStartStreamMsg,
  CmdSwitchCameraMsg,
  CmdToggleMicMsg,
  DerivedBootstrapKeys,
  HelloWakeMsg,
  IceCandidateMsg,
  NativeAuditEntry,
  NativePortalState,
  PortalStatusMsg,
  SdpAnswerMsg,
  SignalMessage,
} from '../types/sentinel';
import { buildIceServers } from './deviceVault';
import { EncryptedSignalingChannel } from './signalingService';

const WEB_EMITTER_STORAGE_KEY = 'sentinelcam.web_emitter.v4';

interface PersistedWebEmitterConfig {
  onboardingCompleted: boolean;
  pairingCode: string;
  deviceId: string;
  pairedTokenId: string | null;
  sasCode: string | null;
  sasSymbols: string | null;
  failedAttempts: number;
  lockoutUntil: number;
  accessProfiles: AccessProfile[];
}

interface ActiveChannelHandle {
  channelId: string;
  keys: DerivedBootstrapKeys;
  signaling: EncryptedSignalingChannel;
}

export class BrowserEmitterEngine {
  private config: PersistedWebEmitterConfig;
  private channels = new Map<string, ActiveChannelHandle>();
  private pc: RTCPeerConnection | null = null;
  private dataChannel: RTCDataChannel | null = null;
  private mediaStream: MediaStream | null = null;

  private serviceRunning = false;
  private mqttConnected = false;
  private activeBroker = 'Déconnecté';
  private cameraFacing: CameraFacingMode = 'front';
  private micEnabled = false;
  private activeSessionId = '';
  private activeViewerChannelId: string | null = null;
  private activeViewerName: string | null = null;
  private activeViewerRole: AccessProfileRole | null = null;
  private activeViewerExpiresAt: number | null = null;
  private startedAt = Date.now();
  private batteryLevel = 100;
  private isCharging = true;
  private expiryWatchdog: number | null = null;

  private logs: NativeAuditEntry[] = [];
  private readonly onUpdate: (state: NativePortalState) => void;

  constructor(onUpdate: (state: NativePortalState) => void) {
    this.onUpdate = onUpdate;
    this.config = this.loadConfig();
    void this.pollBatteryStatus();
  }

  private loadConfig(): PersistedWebEmitterConfig {
    try {
      const raw = localStorage.getItem(WEB_EMITTER_STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as PersistedWebEmitterConfig;
        return {
          ...parsed,
          accessProfiles: Array.isArray(parsed.accessProfiles) ? parsed.accessProfiles : [],
        };
      }
    } catch {
      // Ignore
    }
    const fresh: PersistedWebEmitterConfig = {
      onboardingCompleted: false,
      pairingCode: generateSecurePairingCode(),
      deviceId: `web-${randomHex(6)}`,
      pairedTokenId: null,
      sasCode: null,
      sasSymbols: null,
      failedAttempts: 0,
      lockoutUntil: 0,
      accessProfiles: [],
    };
    this.saveConfig(fresh);
    return fresh;
  }

  private saveConfig(cfg = this.config): void {
    this.config = cfg;
    try {
      localStorage.setItem(WEB_EMITTER_STORAGE_KEY, JSON.stringify(cfg));
    } catch {
      // Ignore
    }
  }

  private async pollBatteryStatus(): Promise<void> {
    try {
      const nav = navigator as Navigator & {
        getBattery?: () => Promise<{
          level: number;
          charging: boolean;
          addEventListener: (ev: string, cb: () => void) => void;
        }>;
      };
      if (nav.getBattery) {
        const bat = await nav.getBattery();
        const sync = () => {
          this.batteryLevel = Math.round(bat.level * 100);
          this.isCharging = bat.charging;
          this.emitState();
        };
        sync();
        bat.addEventListener('levelchange', sync);
        bat.addEventListener('chargingchange', sync);
      }
    } catch {
      // Optional
    }
  }

  public getState(): NativePortalState {
    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    let portalState: NativePortalState['portalState'] = 'stopped';
    if (this.serviceRunning) {
      if (lockoutRemainingMs > 0) portalState = 'locked';
      else if (this.mediaStream) portalState = 'streaming';
      else portalState = 'standby';
    }

    return {
      onboardingCompleted: this.config.onboardingCompleted,
      serviceRunning: this.serviceRunning,
      portalState,
      pairingCode: this.config.pairingCode,
      deviceId: this.config.deviceId,
      deviceName: 'Portail Navigateur Web',
      paired: Boolean(this.config.pairedTokenId),
      pairedTokenId: this.config.pairedTokenId,
      sasCode: this.config.sasCode,
      sasSymbols: this.config.sasSymbols,
      mqttConnected: this.mqttConnected,
      activeBroker: this.activeBroker,
      batteryLevel: this.batteryLevel,
      isCharging: this.isCharging,
      cameraFacing: this.cameraFacing,
      micEnabled: this.micEnabled,
      autoStartOnBoot: false,
      failedAttempts: this.config.failedAttempts,
      lockoutRemainingMs,
      permissions: {
        camera: true,
        microphone: true,
        notifications: true,
        batteryOptimizationIgnored: true,
      },
      logs: [...this.logs],
      accessProfiles: [...this.config.accessProfiles],
      activeViewerName: this.activeViewerName,
      activeViewerRole: this.activeViewerRole,
      activeViewerExpiresAt: this.activeViewerExpiresAt,
    };
  }

  private emitState(): void {
    this.onUpdate(this.getState());
  }

  public async startPortal(): Promise<NativePortalState> {
    await this.stopPortal();

    this.config.onboardingCompleted = true;
    this.saveConfig();
    this.serviceRunning = true;
    this.startedAt = Date.now();

    await this.syncAllChannels();

    this.expiryWatchdog = window.setInterval(() => {
      void this.enforceActiveViewerValidity();
    }, 3000);

    this.emitState();
    return this.getState();
  }

  public async stopPortal(): Promise<NativePortalState> {
    if (this.expiryWatchdog !== null) {
      window.clearInterval(this.expiryWatchdog);
      this.expiryWatchdog = null;
    }
    this.stopMediaAndPeer();
    for (const ch of this.channels.values()) {
      ch.signaling.destroy();
    }
    this.channels.clear();
    this.serviceRunning = false;
    this.mqttConnected = false;
    this.activeBroker = 'Arrêté';
    this.emitState();
    return this.getState();
  }

  public async regenerateCode(): Promise<NativePortalState> {
    this.config.pairingCode = generateSecurePairingCode();
    this.config.pairedTokenId = null;
    this.config.sasCode = null;
    this.config.sasSymbols = null;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();
    if (this.serviceRunning) {
      await this.syncAllChannels();
    }
    this.emitState();
    return this.getState();
  }

  public async upsertAccessProfile(input: AccessProfileInput): Promise<NativePortalState> {
    const cleanCode = normalizePairingCode(input.code);
    if (cleanCode.length !== 6) {
      throw new Error('Le code fixe doit contenir exactement 6 caractères alphanumériques.');
    }
    if (cleanCode === this.config.pairingCode) {
      throw new Error('Ce code est déjà utilisé comme code principal.');
    }
    const existingCollision = this.config.accessProfiles.find(
      (p) => p.code === cleanCode && p.id !== input.id
    );
    if (existingCollision) {
      throw new Error(`Le code ${cleanCode} est déjà attribué à « ${existingCollision.name} ».`);
    }

    const existing = input.id
      ? this.config.accessProfiles.find((p) => p.id === input.id)
      : undefined;

    const record: AccessProfile = {
      id: existing?.id ?? `prof-${randomHex(6)}`,
      name: input.name.trim() || (input.role === 'friend' ? 'Ami' : 'Pass Invité'),
      role: input.role,
      code: cleanCode,
      enabled: input.enabled,
      createdAt: existing?.createdAt ?? Date.now(),
      expiresAt: input.expiresAt,
      allowAudio: input.allowAudio,
      allowSwitchCamera: input.allowSwitchCamera,
      lastAccessAt: existing?.lastAccessAt ?? null,
    };

    this.config.accessProfiles = [
      record,
      ...this.config.accessProfiles.filter((p) => p.id !== record.id),
    ];
    this.saveConfig();

    if (this.serviceRunning) {
      await this.syncAllChannels();
      await this.enforceActiveViewerValidity();
    }
    this.emitState();
    return this.getState();
  }

  public async toggleAccessProfile(id: string, enabled: boolean): Promise<NativePortalState> {
    this.config.accessProfiles = this.config.accessProfiles.map((p) =>
      p.id === id ? { ...p, enabled } : p
    );
    this.saveConfig();
    if (this.serviceRunning) {
      await this.enforceActiveViewerValidity();
    }
    this.emitState();
    return this.getState();
  }

  public async deleteAccessProfile(id: string): Promise<NativePortalState> {
    if (this.activeViewerChannelId === id) {
      await this.kickActiveViewer('revoked');
    }
    this.config.accessProfiles = this.config.accessProfiles.filter((p) => p.id !== id);
    this.saveConfig();
    if (this.serviceRunning) {
      await this.syncAllChannels();
    }
    this.emitState();
    return this.getState();
  }

  public async kickActiveViewer(reason: 'revoked' | 'expired' = 'revoked'): Promise<NativePortalState> {
    const chId = this.activeViewerChannelId;
    const sid = this.activeSessionId;
    if (chId) {
      const ch = this.channels.get(chId);
      if (ch) {
        if (sid) {
          await ch.signaling.send(
            {
              type: 'STREAM_STATE',
              sessionId: sid,
              state: reason,
              cameraFacing: this.cameraFacing,
              micEnabled: false,
              error:
                reason === 'expired'
                  ? 'Votre pass temporaire a expiré.'
                  : 'Accès coupé par le propriétaire.',
              ts: Date.now(),
            },
            'sess'
          );
        }
        await ch.signaling.send(
          {
            type: 'AUTH_ERR',
            reason: reason === 'expired' ? 'ACCESS_EXPIRED' : 'ACCESS_REVOKED',
            message:
              reason === 'expired'
                ? 'Votre pass temporaire a expiré.'
                : 'Accès révoqué ou suspendu par le propriétaire.',
            lockoutRemainingMs: 0,
            failedAttempts: 0,
            ts: Date.now(),
          },
          'boot'
        );
      }
    }
    this.stopMediaAndPeer();
    this.emitState();
    return this.getState();
  }

  private async syncAllChannels(): Promise<void> {
    const desired = new Map<string, string>();
    desired.set('owner', this.config.pairingCode);
    for (const p of this.config.accessProfiles) {
      desired.set(p.id, p.code);
    }

    // Remove stale channels
    for (const [id, handle] of this.channels.entries()) {
      const targetCode = desired.get(id);
      if (!targetCode || handle.keys.normalizedCode !== targetCode) {
        handle.signaling.destroy();
        this.channels.delete(id);
      }
    }

    // Add missing channels
    for (const [channelId, code] of desired.entries()) {
      if (this.channels.has(channelId)) continue;
      const keys = await deriveBootstrapKeys(code);
      const signaling = new EncryptedSignalingChannel({
        role: 'device',
        topicId: keys.topicId,
        bootstrapEncKey: keys.bootstrapEncKey,
        onMessage: (msg) => {
          void this.handleMessage(channelId, msg);
        },
        onConnectionChange: (connected, brokers) => {
          if (channelId === 'owner') {
            this.mqttConnected = connected;
            const active = brokers.filter((b) => b.connected).map((b) => b.name);
            this.activeBroker = active.length > 0 ? active.join(' + ') : 'Reconnexion...';
            this.emitState();
          }
        },
      });
      signaling.start();
      this.channels.set(channelId, { channelId, keys, signaling });
    }
  }

  private resolveChannelGrant(channelId: string): {
    allowed: boolean;
    reason?: 'ACCESS_EXPIRED' | 'ACCESS_REVOKED';
    message?: string;
    role: AccessProfileRole;
    name: string;
    expiresAt: number | null;
    allowAudio: boolean;
    allowSwitchCamera: boolean;
  } {
    if (channelId === 'owner') {
      return {
        allowed: true,
        role: 'owner',
        name: 'Propriétaire',
        expiresAt: null,
        allowAudio: true,
        allowSwitchCamera: true,
      };
    }

    const prof = this.config.accessProfiles.find((p) => p.id === channelId);
    if (!prof || !prof.enabled) {
      return {
        allowed: false,
        reason: 'ACCESS_REVOKED',
        message: 'Ce profil ami ou pass invité est actuellement désactivé par le propriétaire.',
        role: prof?.role ?? 'guest',
        name: prof?.name ?? 'Invité',
        expiresAt: prof?.expiresAt ?? null,
        allowAudio: false,
        allowSwitchCamera: false,
      };
    }

    if (prof.expiresAt !== null && Date.now() > prof.expiresAt) {
      return {
        allowed: false,
        reason: 'ACCESS_EXPIRED',
        message: `Le pass temporaire « ${prof.name} » est arrivé à expiration.`,
        role: prof.role,
        name: prof.name,
        expiresAt: prof.expiresAt,
        allowAudio: false,
        allowSwitchCamera: false,
      };
    }

    return {
      allowed: true,
      role: prof.role,
      name: prof.name,
      expiresAt: prof.expiresAt,
      allowAudio: prof.allowAudio,
      allowSwitchCamera: prof.allowSwitchCamera,
    };
  }

  private async enforceActiveViewerValidity(): Promise<void> {
    if (!this.activeViewerChannelId || this.activeViewerChannelId === 'owner') return;
    const grant = this.resolveChannelGrant(this.activeViewerChannelId);
    if (!grant.allowed) {
      await this.kickActiveViewer(grant.reason === 'ACCESS_EXPIRED' ? 'expired' : 'revoked');
    }
  }

  private buildStatusMsg(channelId: string): PortalStatusMsg {
    const grant = this.resolveChannelGrant(channelId);
    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    return {
      type: 'PORTAL_STATUS',
      deviceId: this.config.deviceId,
      deviceName: 'Portail Navigateur Web',
      portalState: lockoutRemainingMs > 0 ? 'locked' : this.mediaStream ? 'streaming' : 'standby',
      paired: Boolean(this.config.pairedTokenId),
      pairedTokenId: this.config.pairedTokenId,
      lockoutRemainingMs,
      failedAttempts: this.config.failedAttempts,
      batteryLevel: this.batteryLevel,
      isCharging: this.isCharging,
      networkType: 'Web WSS',
      cameraFacing: this.cameraFacing,
      micEnabled: this.micEnabled,
      uptimeSec: Math.floor((Date.now() - this.startedAt) / 1000),
      accessRole: grant.role,
      accessName: grant.name,
      accessExpiresAt: grant.expiresAt,
      allowAudio: grant.allowAudio,
      allowSwitchCamera: grant.allowSwitchCamera,
      ts: Date.now(),
    };
  }

  private async handleMessage(channelId: string, msg: SignalMessage): Promise<void> {
    const handle = this.channels.get(channelId);
    if (!handle) return;

    const grant = this.resolveChannelGrant(channelId);
    if (!grant.allowed) {
      await handle.signaling.send(
        {
          type: 'AUTH_ERR',
          reason: grant.reason ?? 'ACCESS_REVOKED',
          message: grant.message ?? 'Accès refusé.',
          lockoutRemainingMs: 0,
          failedAttempts: 0,
          ts: Date.now(),
        },
        'boot'
      );
      return;
    }

    switch (msg.type) {
      case 'PING_STATUS':
        await handle.signaling.send(this.buildStatusMsg(channelId), 'boot');
        break;

      case 'HELLO_WAKE':
        await this.handleHelloWake(handle, msg);
        break;

      case 'CMD_START_STREAM':
        await this.handleStartStream(handle, msg);
        break;

      case 'CMD_STOP_STREAM':
        this.stopMediaAndPeer();
        await handle.signaling.send(
          {
            type: 'STREAM_STATE',
            sessionId: msg.sessionId,
            state: 'standby',
            cameraFacing: this.cameraFacing,
            micEnabled: this.micEnabled,
            ts: Date.now(),
          },
          'sess'
        );
        break;

      case 'CMD_SWITCH_CAMERA':
        if (grant.allowSwitchCamera) {
          await this.handleSwitchCamera(msg);
        }
        break;

      case 'CMD_TOGGLE_MIC':
        if (grant.allowAudio) {
          this.handleToggleMic(msg);
        }
        break;

      case 'SDP_ANSWER':
        await this.handleSdpAnswer(msg);
        break;

      case 'ICE_CANDIDATE':
        await this.handleIceCandidate(msg);
        break;

      default:
        break;
    }
  }

  private async handleHelloWake(handle: ActiveChannelHandle, msg: HelloWakeMsg): Promise<void> {
    const grant = this.resolveChannelGrant(handle.channelId);
    if (!grant.allowed) return;

    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    if (lockoutRemainingMs > 0) {
      await handle.signaling.send(
        {
          type: 'AUTH_ERR',
          reason: 'LOCKED_OUT',
          message: `Verrouillage actif (${Math.ceil(lockoutRemainingMs / 1000)}s).`,
          lockoutRemainingMs,
          failedAttempts: this.config.failedAttempts,
          ts: Date.now(),
        },
        'boot'
      );
      return;
    }

    const expectedProof = await computeHelloWakeProof(
      handle.keys.bootstrapAuthRaw,
      msg.clientNonce,
      msg.tokenId
    );

    if (!constantTimeEqualHex(expectedProof, msg.proofHex)) {
      this.config.failedAttempts += 1;
      const lockMs = calculateLockoutDurationMs(this.config.failedAttempts);
      if (lockMs > 0) this.config.lockoutUntil = Date.now() + lockMs;
      this.saveConfig();
      return;
    }

    const serverNonce = randomHex(20);
    const sessionKey = await deriveFastSessionKey(
      handle.keys.bootstrapAuthRaw,
      msg.clientNonce,
      serverNonce,
      this.config.deviceId
    );
    handle.signaling.setSessionKey(sessionKey);

    const serverProofHex = await computeHelloAckProof(
      handle.keys.bootstrapAuthRaw,
      msg.clientNonce,
      serverNonce,
      this.config.deviceId
    );

    const sas = await computeSas(
      handle.keys.normalizedCode,
      msg.tokenId,
      this.config.deviceId,
      handle.keys.bootstrapAuthRaw
    );

    if (handle.channelId !== 'owner') {
      this.config.accessProfiles = this.config.accessProfiles.map((p) =>
        p.id === handle.channelId ? { ...p, lastAccessAt: Date.now() } : p
      );
    }

    this.config.pairedTokenId = msg.tokenId;
    this.config.sasCode = sas.sasCode;
    this.config.sasSymbols = sas.sasSymbols;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();

    await handle.signaling.send(
      {
        type: 'HELLO_ACK',
        deviceId: this.config.deviceId,
        deviceName: 'Portail Navigateur Web',
        echoClientNonce: msg.clientNonce,
        serverNonce,
        serverProofHex,
        sasCode: sas.sasCode,
        sasSymbols: sas.sasSymbols,
        portalState: this.mediaStream ? 'streaming' : 'standby',
        batteryLevel: this.batteryLevel,
        isCharging: this.isCharging,
        networkType: 'Web WSS',
        cameraFacing: this.cameraFacing,
        micEnabled: this.micEnabled,
        uptimeSec: Math.floor((Date.now() - this.startedAt) / 1000),
        accessRole: grant.role,
        accessName: grant.name,
        accessExpiresAt: grant.expiresAt,
        allowAudio: grant.allowAudio,
        allowSwitchCamera: grant.allowSwitchCamera,
        ts: Date.now(),
      },
      'boot'
    );

    if (msg.autoStartStream) {
      await this.handleStartStream(handle, {
        type: 'CMD_START_STREAM',
        sessionId: msg.sessionId,
        cameraFacing: grant.allowSwitchCamera ? msg.cameraFacing : 'front',
        enableAudio: grant.allowAudio ? msg.enableAudio : false,
        quality: msg.quality,
        ts: Date.now(),
      });
    }
  }

  private async handleStartStream(
    handle: ActiveChannelHandle,
    msg: CmdStartStreamMsg
  ): Promise<void> {
    const grant = this.resolveChannelGrant(handle.channelId);
    if (!grant.allowed) return;

    this.stopMediaAndPeer();

    this.activeSessionId = msg.sessionId;
    this.activeViewerChannelId = handle.channelId;
    this.activeViewerName = grant.name;
    this.activeViewerRole = grant.role;
    this.activeViewerExpiresAt = grant.expiresAt;
    this.cameraFacing = grant.allowSwitchCamera ? msg.cameraFacing : 'front';
    this.micEnabled = grant.allowAudio ? msg.enableAudio : false;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: this.cameraFacing === 'front' ? 'user' : 'environment',
          width: { ideal: msg.quality === 'hd' ? 1280 : 640 },
          height: { ideal: msg.quality === 'hd' ? 720 : 480 },
          frameRate: { ideal: 30, max: 30 },
        },
        audio: this.micEnabled,
      });
      this.mediaStream = stream;

      const pc = new RTCPeerConnection({
        iceServers: buildIceServers(),
        bundlePolicy: 'max-bundle',
      });
      this.pc = pc;

      this.dataChannel = pc.createDataChannel('sentinel-telemetry', { ordered: true });
      for (const track of stream.getTracks()) {
        pc.addTrack(track, stream);
      }

      pc.onicecandidate = (ev) => {
        if (ev.candidate && this.activeSessionId === msg.sessionId) {
          void handle.signaling.send(
            {
              type: 'ICE_CANDIDATE',
              sessionId: msg.sessionId,
              candidate: ev.candidate.candidate,
              sdpMid: ev.candidate.sdpMid,
              sdpMLineIndex: ev.candidate.sdpMLineIndex,
              ts: Date.now(),
            },
            'sess'
          );
        }
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      const offerSdp = offer.sdp || '';

      await handle.signaling.send(
        {
          type: 'SDP_OFFER',
          sessionId: msg.sessionId,
          sdp: offerSdp,
          fingerprint: extractSdpFingerprint(offerSdp),
          ts: Date.now(),
        },
        'sess'
      );
      this.emitState();
    } catch {
      // Ignore
    }
  }

  private async handleSwitchCamera(msg: CmdSwitchCameraMsg): Promise<void> {
    if (!this.pc || !this.mediaStream) return;
    this.cameraFacing = msg.cameraFacing;
    try {
      const newStream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: msg.cameraFacing === 'front' ? 'user' : 'environment',
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      });
      const newVideoTrack = newStream.getVideoTracks()[0];
      const sender = this.pc.getSenders().find((s) => s.track?.kind === 'video');
      if (sender && newVideoTrack) {
        await sender.replaceTrack(newVideoTrack);
      }
      this.mediaStream.getVideoTracks().forEach((t) => {
        t.stop();
        this.mediaStream?.removeTrack(t);
      });
      if (newVideoTrack) this.mediaStream.addTrack(newVideoTrack);
      this.emitState();
    } catch {
      // Ignore
    }
  }

  private handleToggleMic(msg: CmdToggleMicMsg): void {
    this.micEnabled = msg.enableAudio;
    if (this.mediaStream) {
      this.mediaStream.getAudioTracks().forEach((t) => {
        t.enabled = msg.enableAudio;
      });
    }
    this.emitState();
  }

  private async handleSdpAnswer(msg: SdpAnswerMsg): Promise<void> {
    if (!this.pc || msg.sessionId !== this.activeSessionId) return;
    await this.pc.setRemoteDescription(
      new RTCSessionDescription({
        type: 'answer',
        sdp: msg.sdp,
      })
    );
  }

  private async handleIceCandidate(msg: IceCandidateMsg): Promise<void> {
    if (!this.pc || msg.sessionId !== this.activeSessionId) return;
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

  private stopMediaAndPeer(): void {
    this.activeSessionId = '';
    this.activeViewerChannelId = null;
    this.activeViewerName = null;
    this.activeViewerRole = null;
    this.activeViewerExpiresAt = null;
    if (this.mediaStream) {
      for (const track of this.mediaStream.getTracks()) {
        track.stop();
      }
      this.mediaStream = null;
    }
    if (this.dataChannel) {
      try {
        this.dataChannel.close();
      } catch {
        // Ignore
      }
      this.dataChannel = null;
    }
    if (this.pc) {
      try {
        this.pc.close();
      } catch {
        // Ignore
      }
      this.pc = null;
    }
    this.emitState();
  }
}
