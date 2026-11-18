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
  randomHex,
} from '../crypto/cryptoEngine';
import type {
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

const WEB_EMITTER_STORAGE_KEY = 'sentinelcam.web_emitter.v3';

interface PersistedWebEmitterConfig {
  onboardingCompleted: boolean;
  pairingCode: string;
  deviceId: string;
  pairedTokenId: string | null;
  sasCode: string | null;
  sasSymbols: string | null;
  failedAttempts: number;
  lockoutUntil: number;
}

export class BrowserEmitterEngine {
  private config: PersistedWebEmitterConfig;
  private bootstrapKeys: DerivedBootstrapKeys | null = null;
  private signaling: EncryptedSignalingChannel | null = null;
  private pc: RTCPeerConnection | null = null;
  private dataChannel: RTCDataChannel | null = null;
  private mediaStream: MediaStream | null = null;

  private serviceRunning = false;
  private mqttConnected = false;
  private activeBroker = 'Déconnecté';
  private cameraFacing: CameraFacingMode = 'front';
  private micEnabled = false;
  private activeSessionId = '';
  private startedAt = Date.now();
  private batteryLevel = 100;
  private isCharging = true;

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
        return JSON.parse(raw) as PersistedWebEmitterConfig;
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

    const keys = await deriveBootstrapKeys(this.config.pairingCode);
    this.bootstrapKeys = keys;

    this.signaling = new EncryptedSignalingChannel({
      role: 'device',
      topicId: keys.topicId,
      bootstrapEncKey: keys.bootstrapEncKey,
      onMessage: (msg) => {
        void this.handleMessage(msg);
      },
      onConnectionChange: (connected, brokers) => {
        this.mqttConnected = connected;
        const active = brokers.filter((b) => b.connected).map((b) => b.name);
        this.activeBroker = active.length > 0 ? active.join(' + ') : 'Reconnexion...';
        this.emitState();
      },
    });

    this.signaling.start();
    this.emitState();
    return this.getState();
  }

  public async stopPortal(): Promise<NativePortalState> {
    this.stopMediaAndPeer();
    if (this.signaling) {
      this.signaling.destroy();
      this.signaling = null;
    }
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
      return this.startPortal();
    }
    return this.getState();
  }

  private buildStatusMsg(): PortalStatusMsg {
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
      ts: Date.now(),
    };
  }

  private async handleMessage(msg: SignalMessage): Promise<void> {
    if (!this.signaling || !this.bootstrapKeys) return;

    switch (msg.type) {
      case 'PING_STATUS':
        await this.signaling.send(this.buildStatusMsg(), 'boot');
        break;

      case 'HELLO_WAKE':
        await this.handleHelloWake(msg);
        break;

      case 'CMD_START_STREAM':
        await this.handleStartStream(msg);
        break;

      case 'CMD_STOP_STREAM':
        this.stopMediaAndPeer();
        await this.signaling.send(
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
        await this.handleSwitchCamera(msg);
        break;

      case 'CMD_TOGGLE_MIC':
        this.handleToggleMic(msg);
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

  private async handleHelloWake(msg: HelloWakeMsg): Promise<void> {
    if (!this.signaling || !this.bootstrapKeys) return;

    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    if (lockoutRemainingMs > 0) {
      await this.signaling.send(
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
      this.bootstrapKeys.bootstrapAuthRaw,
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
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.clientNonce,
      serverNonce,
      this.config.deviceId
    );
    this.signaling.setSessionKey(sessionKey);

    const serverProofHex = await computeHelloAckProof(
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.clientNonce,
      serverNonce,
      this.config.deviceId
    );

    const sas = await computeSas(
      this.config.pairingCode,
      msg.tokenId,
      this.config.deviceId,
      this.bootstrapKeys.bootstrapAuthRaw
    );

    this.config.pairedTokenId = msg.tokenId;
    this.config.sasCode = sas.sasCode;
    this.config.sasSymbols = sas.sasSymbols;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();

    await this.signaling.send(
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
        ts: Date.now(),
      },
      'boot'
    );

    if (msg.autoStartStream) {
      await this.handleStartStream({
        type: 'CMD_START_STREAM',
        sessionId: msg.sessionId,
        cameraFacing: msg.cameraFacing,
        enableAudio: msg.enableAudio,
        quality: msg.quality,
        ts: Date.now(),
      });
    }
  }

  private async handleStartStream(msg: CmdStartStreamMsg): Promise<void> {
    if (!this.signaling) return;
    this.stopMediaAndPeer();

    this.activeSessionId = msg.sessionId;
    this.cameraFacing = msg.cameraFacing;
    this.micEnabled = msg.enableAudio;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: msg.cameraFacing === 'front' ? 'user' : 'environment',
          width: { ideal: msg.quality === 'hd' ? 1280 : 640 },
          height: { ideal: msg.quality === 'hd' ? 720 : 480 },
          frameRate: { ideal: 30, max: 30 },
        },
        audio: msg.enableAudio,
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
        if (ev.candidate && this.signaling && this.activeSessionId === msg.sessionId) {
          void this.signaling.send(
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

      await this.signaling.send(
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
