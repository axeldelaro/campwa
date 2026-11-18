import {
  calculateLockoutDurationMs,
  computeClientChallengeProof,
  computeSas,
  computeServerChallengeProof,
  constantTimeEqualHex,
  deriveBootstrapKeys,
  deriveSessionAesKey,
  extractSdpFingerprint,
  generateSecurePairingCode,
  randomHex,
} from '../crypto/cryptoEngine';
import type {
  AuthInitMsg,
  AuthResponseMsg,
  CameraFacingMode,
  CmdStartStreamMsg,
  CmdSwitchCameraMsg,
  CmdToggleMicMsg,
  IceCandidateMsg,
  NativeAuditEntry,
  NativePortalState,
  PortalStatusMsg,
  SdpAnswerMsg,
  SignalMessage,
} from '../types/sentinel';
import { buildIceServers } from './deviceVault';
import { EncryptedSignalingChannel } from './signalingService';

const WEB_EMITTER_STORAGE_KEY = 'sentinelcam.web_emitter.v1';

interface PersistedWebEmitterConfig {
  onboardingCompleted: boolean;
  pairingCode: string;
  deviceId: string;
  deviceSalt: string;
  pairedTokenId: string | null;
  pairedKeyHex: string | null;
  sasCode: string | null;
  sasSymbols: string | null;
  failedAttempts: number;
  lockoutUntil: number;
}

/**
 * Browser-mode Emitter Engine implementing the exact same E2EE protocol as the Android
 * ForegroundService, enabling instant browser-to-browser testing or tablet kiosk use.
 */
export class BrowserEmitterEngine {
  private config: PersistedWebEmitterConfig;
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

  private pendingChallenge: {
    tokenId: string;
    clientNonce: string;
    serverNonce: string;
    createdAt: number;
  } | null = null;

  private heartbeatTimer: number | null = null;
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
      deviceSalt: randomHex(32),
      pairedTokenId: null,
      pairedKeyHex: null,
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

  private addLog(level: 'info' | 'warn' | 'security', message: string): void {
    this.logs.unshift({ timestamp: Date.now(), level, message });
    if (this.logs.length > 40) this.logs.pop();
    this.emitState();
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
      // Battery API optional on desktop browsers
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
      paired: Boolean(this.config.pairedKeyHex),
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
        if (connected) {
          void this.broadcastPresence();
        }
        this.emitState();
      },
    });

    this.signaling.start();
    this.addLog('security', `Portail Web actif en écoute E2EE — Code [${this.config.pairingCode}]`);

    this.heartbeatTimer = window.setInterval(() => {
      void this.broadcastPresence();
    }, 10_000);

    this.emitState();
    return this.getState();
  }

  public async stopPortal(): Promise<NativePortalState> {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
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
    this.config.deviceSalt = randomHex(32);
    this.config.pairedTokenId = null;
    this.config.pairedKeyHex = null;
    this.config.sasCode = null;
    this.config.sasSymbols = null;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();
    this.addLog('security', `Nouveau code généré : [${this.config.pairingCode}] — anciens jetons révoqués.`);
    if (this.serviceRunning) {
      return this.startPortal();
    }
    return this.getState();
  }

  public resetPairing(): NativePortalState {
    this.config.pairedTokenId = null;
    this.config.pairedKeyHex = null;
    this.config.sasCode = null;
    this.config.sasSymbols = null;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();
    this.stopMediaAndPeer();
    this.addLog('security', 'Appairage réinitialisé — prêt pour un nouveau poste de contrôle.');
    return this.getState();
  }

  private buildStatusMsg(): PortalStatusMsg {
    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    return {
      type: 'PORTAL_STATUS',
      deviceId: this.config.deviceId,
      deviceName: 'Portail Navigateur Web',
      portalState: lockoutRemainingMs > 0 ? 'locked' : this.mediaStream ? 'streaming' : 'standby',
      paired: Boolean(this.config.pairedKeyHex),
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

  private async broadcastPresence(): Promise<void> {
    if (!this.signaling || !this.serviceRunning) return;
    const status = this.buildStatusMsg();
    await this.signaling.send(status, 'boot', { toPresence: true });
    if (this.dataChannel && this.dataChannel.readyState === 'open') {
      try {
        this.dataChannel.send(JSON.stringify(status));
      } catch {
        // Ignore
      }
    }
  }

  private async handleMessage(msg: SignalMessage): Promise<void> {
    if (!this.signaling) return;

    switch (msg.type) {
      case 'PING_STATUS':
        await this.signaling.send(this.buildStatusMsg(), 'boot');
        break;

      case 'AUTH_INIT':
        await this.handleAuthInit(msg);
        break;

      case 'AUTH_RESPONSE':
        await this.handleAuthResponse(msg);
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
        this.addLog('info', 'Caméra mise en veille sur demande du poste de contrôle.');
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

  private async handleAuthInit(msg: AuthInitMsg): Promise<void> {
    if (!this.signaling) return;

    const lockoutRemainingMs = Math.max(0, this.config.lockoutUntil - Date.now());
    if (lockoutRemainingMs > 0) {
      await this.signaling.send(
        {
          type: 'AUTH_ERR',
          reason: 'LOCKED_OUT',
          message: `Protection anti-brute-force active. Réessayez dans ${Math.ceil(lockoutRemainingMs / 1000)}s.`,
          lockoutRemainingMs,
          failedAttempts: this.config.failedAttempts,
          ts: Date.now(),
        },
        'boot'
      );
      return;
    }

    const isFirstPair = !this.config.pairedKeyHex || this.config.pairedTokenId !== msg.tokenId;

    const serverNonce = randomHex(24);
    this.pendingChallenge = {
      tokenId: msg.tokenId,
      clientNonce: msg.clientNonce,
      serverNonce,
      createdAt: Date.now(),
    };

    await this.signaling.send(
      {
        type: 'AUTH_CHALLENGE',
        deviceId: this.config.deviceId,
        deviceName: 'Portail Navigateur Web',
        serverNonce,
        deviceSalt: this.config.deviceSalt,
        isFirstPair,
        ts: Date.now(),
      },
      'boot'
    );
  }

  private async handleAuthResponse(msg: AuthResponseMsg): Promise<void> {
    if (!this.signaling || !this.pendingChallenge) return;

    if (Date.now() - this.pendingChallenge.createdAt > 30_000) {
      this.pendingChallenge = null;
      await this.signaling.send(
        {
          type: 'AUTH_ERR',
          reason: 'EXPIRED_CHALLENGE',
          message: 'Challenge expiré, veuillez relancer la connexion.',
          lockoutRemainingMs: 0,
          failedAttempts: this.config.failedAttempts,
          ts: Date.now(),
        },
        'boot'
      );
      return;
    }

    const { clientNonce, serverNonce, tokenId } = this.pendingChallenge;
    this.pendingChallenge = null;

    const candidateKeyHex = this.config.pairedKeyHex ?? msg.pairingKeyHex;
    if (!candidateKeyHex || candidateKeyHex.length !== 64) {
      this.recordAuthFailure('Clé d’appairage absente ou invalide.');
      return;
    }

    const expectedProof = await computeClientChallengeProof(
      candidateKeyHex,
      clientNonce,
      serverNonce,
      tokenId,
      this.config.deviceId
    );

    if (!constantTimeEqualHex(expectedProof, msg.proofHex)) {
      this.recordAuthFailure('Échec de vérification HMAC-SHA256 !');
      const rem = Math.max(0, this.config.lockoutUntil - Date.now());
      await this.signaling.send(
        {
          type: 'AUTH_ERR',
          reason: rem > 0 ? 'LOCKED_OUT' : 'INVALID_PROOF',
          message: 'Signature HMAC invalide.',
          lockoutRemainingMs: rem,
          failedAttempts: this.config.failedAttempts,
          ts: Date.now(),
        },
        'boot'
      );
      return;
    }

    // Success! Reset failed attempts, persist pairing if first time, derive ephemeral session key
    const sas = await computeSas(
      this.config.pairingCode,
      tokenId,
      this.config.deviceId,
      candidateKeyHex
    );

    this.config.pairedTokenId = tokenId;
    this.config.pairedKeyHex = candidateKeyHex;
    this.config.sasCode = sas.sasCode;
    this.config.sasSymbols = sas.sasSymbols;
    this.config.failedAttempts = 0;
    this.config.lockoutUntil = 0;
    this.saveConfig();

    const sessionKey = await deriveSessionAesKey(candidateKeyHex, clientNonce, serverNonce);
    this.signaling.setSessionKey(sessionKey);

    const serverProofHex = await computeServerChallengeProof(
      candidateKeyHex,
      clientNonce,
      serverNonce,
      tokenId,
      this.config.deviceId
    );

    await this.signaling.send(
      {
        type: 'AUTH_OK',
        deviceId: this.config.deviceId,
        deviceName: 'Portail Navigateur Web',
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

    this.addLog('security', `Poste de contrôle authentifié — SAS [${sas.sasCode}]`);
  }

  private recordAuthFailure(reason: string): void {
    this.config.failedAttempts += 1;
    const lockoutMs = calculateLockoutDurationMs(this.config.failedAttempts);
    if (lockoutMs > 0) {
      this.config.lockoutUntil = Date.now() + lockoutMs;
      this.addLog(
        'security',
        `${reason} Verrouillage anti-brute-force activé pour ${Math.round(lockoutMs / 1000)}s.`
      );
    } else {
      this.addLog('warn', `${reason} (Échec ${this.config.failedAttempts}/3)`);
    }
    this.saveConfig();
  }

  private async handleStartStream(msg: CmdStartStreamMsg): Promise<void> {
    if (!this.signaling) return;
    this.stopMediaAndPeer();

    this.activeSessionId = msg.sessionId;
    this.cameraFacing = msg.cameraFacing;
    this.micEnabled = msg.enableAudio;

    try {
      const constraints: MediaStreamConstraints = {
        video: {
          facingMode: msg.cameraFacing === 'front' ? 'user' : 'environment',
          width: { ideal: msg.quality === 'hd' ? 1280 : 640 },
          height: { ideal: msg.quality === 'hd' ? 720 : 480 },
          frameRate: { ideal: 30, max: 30 },
        },
        audio: msg.enableAudio,
      };

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      this.mediaStream = stream;

      const pc = new RTCPeerConnection({
        iceServers: buildIceServers(),
        bundlePolicy: 'max-bundle',
      });
      this.pc = pc;

      this.dataChannel = pc.createDataChannel('sentinel-telemetry', { ordered: true });
      this.dataChannel.onopen = () => {
        void this.broadcastPresence();
      };

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

      this.addLog('info', 'Streaming caméra démarré en direct (DTLS-SRTP).');
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Accès caméra refusé';
      await this.signaling.send(
        {
          type: 'STREAM_STATE',
          sessionId: msg.sessionId,
          state: 'error',
          cameraFacing: this.cameraFacing,
          micEnabled: this.micEnabled,
          error: errorMsg,
          ts: Date.now(),
        },
        'sess'
      );
      this.addLog('warn', `Impossible d’ouvrir la caméra : ${errorMsg}`);
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
      void this.broadcastPresence();
    } catch {
      // Ignore if device only has 1 webcam
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
    void this.broadcastPresence();
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
