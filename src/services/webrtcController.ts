import {
  computeClientChallengeProof,
  computeDtlsMediaSas,
  computeSas,
  computeServerChallengeProof,
  constantTimeEqualHex,
  deriveBootstrapKeys,
  deriveSessionAesKey,
  extractSdpFingerprint,
  generateDerivedPairingKey,
  normalizePairingCode,
  randomHex,
} from '../crypto/cryptoEngine';
import type {
  AuthChallengeMsg,
  AuthErrMsg,
  AuthOkMsg,
  CameraFacingMode,
  DeviceTelemetry,
  IceCandidateMsg,
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
  removePairedDevice,
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
    isNewPairing: boolean;
  }) => void;
  onAuthError: (err: AuthErrMsg) => void;
  onRemoteStream: (stream: MediaStream | null) => void;
  onStatsUpdate: (stats: StreamRealtimeStats | null) => void;
  onLog: (level: 'info' | 'warn' | 'security', text: string) => void;
}

export class SentinelWebRtcController {
  private readonly callbacks: ControllerCallbacks;
  private signaling: EncryptedSignalingChannel | null = null;
  private pc: RTCPeerConnection | null = null;
  private remoteStream: MediaStream | null = null;

  private pairingCode = '';
  private tokenId = '';
  private clientNonce = '';
  private pendingIsNewPair = false;
  private activeSessionId = '';
  private authenticated = false;
  private handshakeInFlight = false;

  // Desired continuous state for automatic recovery across Wi-Fi <-> 5G transitions
  private desiredStreaming = false;
  private desiredFacing: CameraFacingMode = 'front';
  private desiredAudio = false;
  private desiredQuality: StreamQualityPreset = 'hd';

  private localSdpFingerprint = '';
  private remoteSdpFingerprint = '';
  private pendingCandidates: IceCandidateMsg[] = [];

  private statsTimer: number | null = null;
  private watchdogTimer: number | null = null;
  private streamRecoverTimer: number | null = null;
  private lastTelemetryAt = 0;
  private lastBytesReceived = 0;
  private lastStatsTimestamp = 0;

  private pendingPairCandidate: {
    deviceId: string;
    deviceName: string;
    serverNonce: string;
    pairedKeyHex: string;
  } | null = null;

  constructor(callbacks: ControllerCallbacks) {
    this.callbacks = callbacks;
  }

  public async connectToPortal(rawCode: string): Promise<void> {
    this.disconnect();

    const code = normalizePairingCode(rawCode);
    if (code.length !== 6) {
      throw new Error('Code à 6 caractères requis.');
    }

    this.pairingCode = code;
    this.tokenId = getOrCreateControllerTokenId();
    this.authenticated = false;
    this.handshakeInFlight = false;

    this.callbacks.onStateChange('connecting');
    this.callbacks.onLog('security', `Connexion chiffrée au smartphone [${code}]...`);

    const keys = await deriveBootstrapKeys(code);

    this.signaling = new EncryptedSignalingChannel({
      role: 'controller',
      topicId: keys.topicId,
      bootstrapEncKey: keys.bootstrapEncKey,
      onMessage: (msg, mode) => {
        void this.handleSignalMessage(msg, mode);
      },
      onConnectionChange: (connected, brokers) => {
        this.callbacks.onBrokerHealth(connected, brokers);
        if (connected && !this.authenticated) {
          void this.sendWakeAndHandshake();
        }
      },
    });

    this.signaling.start();

    // Continuous Wake-Ping & Auto-Reconnect Watchdog (retries every 2.5s until connected, and monitors health)
    this.watchdogTimer = window.setInterval(() => {
      if (!this.signaling || !this.signaling.isAnyConnected()) return;

      if (!this.authenticated) {
        void this.sendWakeAndHandshake();
      } else {
        // Periodic keep-alive ping to phone
        void this.signaling.send(
          {
            type: 'PING_STATUS',
            controllerId: this.signaling.getSenderId(),
            tokenId: this.tokenId,
            ts: Date.now(),
          },
          'boot'
        );

        // If phone rebooted or recreated its session (>25s without telemetry), re-authenticate seamlessly
        if (this.lastTelemetryAt > 0 && Date.now() - this.lastTelemetryAt > 25_000) {
          this.authenticated = false;
          this.lastTelemetryAt = Date.now();
          void this.sendWakeAndHandshake();
        }
      }
    }, 2500);
  }

  private async sendWakeAndHandshake(): Promise<void> {
    if (!this.signaling || this.authenticated || this.handshakeInFlight) return;
    this.handshakeInFlight = true;

    // Allow next retry in 2.2s if no response yet
    window.setTimeout(() => {
      this.handshakeInFlight = false;
    }, 2200);

    const saved = findPairedDeviceByCode(this.pairingCode);
    if (saved) {
      this.tokenId = saved.tokenId;
    }

    this.clientNonce = randomHex(24);
    const intent = saved ? 'reconnect' : 'pair';

    await this.signaling.send(
      {
        type: 'AUTH_INIT',
        controllerId: this.signaling.getSenderId(),
        tokenId: this.tokenId,
        clientNonce: this.clientNonce,
        intent,
        ts: Date.now(),
      },
      'boot'
    );
  }

  private async handleSignalMessage(msg: SignalMessage, _mode: 'boot' | 'sess'): Promise<void> {
    switch (msg.type) {
      case 'PORTAL_STATUS':
        this.handlePortalStatus(msg);
        break;

      case 'AUTH_CHALLENGE':
        await this.handleAuthChallenge(msg);
        break;

      case 'AUTH_OK':
        await this.handleAuthOk(msg);
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

      default:
        break;
    }
  }

  private handlePortalStatus(msg: PortalStatusMsg): void {
    this.lastTelemetryAt = Date.now();
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
      micEnabled: msg.micEnabled,
      uptimeSec: msg.uptimeSec,
      updatedAt: Date.now(),
    });

    if (msg.portalState === 'locked') {
      this.callbacks.onStateChange('locked');
    } else if (this.authenticated && !this.pc && !this.desiredStreaming) {
      this.callbacks.onStateChange('standby');
    }
  }

  private async handleAuthChallenge(msg: AuthChallengeMsg): Promise<void> {
    if (!this.signaling) return;

    const saved = findPairedDeviceByCode(this.pairingCode);
    let pairedKeyHex: string;
    let sendPairingKeyHex: string | undefined;

    if (msg.isFirstPair || !saved || !saved.pairedKeyHex) {
      pairedKeyHex = await generateDerivedPairingKey(msg.deviceSalt, this.tokenId);
      sendPairingKeyHex = pairedKeyHex;
      this.pendingIsNewPair = true;
    } else {
      pairedKeyHex = saved.pairedKeyHex;
      this.pendingIsNewPair = false;
    }

    const proofHex = await computeClientChallengeProof(
      pairedKeyHex,
      this.clientNonce,
      msg.serverNonce,
      this.tokenId,
      msg.deviceId
    );

    const sessionKey = await deriveSessionAesKey(pairedKeyHex, this.clientNonce, msg.serverNonce);
    this.signaling.setSessionKey(sessionKey);

    this.pendingPairCandidate = {
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      serverNonce: msg.serverNonce,
      pairedKeyHex,
    };

    await this.signaling.send(
      {
        type: 'AUTH_RESPONSE',
        controllerId: this.signaling.getSenderId(),
        tokenId: this.tokenId,
        pairingKeyHex: sendPairingKeyHex,
        proofHex,
        clientNonce: this.clientNonce,
        ts: Date.now(),
      },
      'boot'
    );
  }

  private async handleAuthOk(msg: AuthOkMsg): Promise<void> {
    if (!this.pendingPairCandidate) return;

    const { deviceId, pairedKeyHex, serverNonce } = this.pendingPairCandidate;
    const expectedServerProof = await computeServerChallengeProof(
      pairedKeyHex,
      this.clientNonce,
      serverNonce,
      this.tokenId,
      deviceId
    );

    if (!constantTimeEqualHex(expectedServerProof, msg.serverProofHex)) {
      this.callbacks.onLog('security', 'Alerte sécurité : preuve serveur invalide.');
      return;
    }

    const localSas = await computeSas(this.pairingCode, this.tokenId, msg.deviceId, pairedKeyHex);
    this.authenticated = true;
    this.handshakeInFlight = false;
    this.lastTelemetryAt = Date.now();

    savePairedDevice({
      pairingCode: this.pairingCode,
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      tokenId: this.tokenId,
      pairedKeyHex,
      sasCode: localSas.sasCode,
      sasSymbols: localSas.sasSymbols,
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
      micEnabled: msg.micEnabled,
      uptimeSec: msg.uptimeSec,
      updatedAt: Date.now(),
    });

    this.callbacks.onAuthSuccess({
      deviceName: msg.deviceName,
      deviceId: msg.deviceId,
      sasCode: localSas.sasCode,
      sasSymbols: localSas.sasSymbols,
      isNewPairing: this.pendingIsNewPair,
    });

    // If stream was active before a network drop, automatically resume live video!
    if (this.desiredStreaming && !this.pc) {
      this.callbacks.onLog('info', 'Reprise automatique du flux caméra direct...');
      await this.startCameraStream(this.desiredFacing, this.desiredAudio, this.desiredQuality);
    } else if (!this.pc) {
      this.callbacks.onStateChange('standby');
      this.callbacks.onLog('security', `Connecté à ${msg.deviceName} (SAS ${localSas.sasCode})`);
    }
  }

  private handleAuthErr(msg: AuthErrMsg): void {
    this.authenticated = false;
    this.handshakeInFlight = false;

    // If local stored token became stale (e.g. phone app reinstalled), clear stale token and auto-repair!
    if (msg.reason === 'INVALID_PROOF' || msg.reason === 'ALREADY_PAIRED') {
      const existing = findPairedDeviceByCode(this.pairingCode);
      if (existing) {
        removePairedDevice(this.pairingCode);
        this.tokenId = getOrCreateControllerTokenId();
        void this.sendWakeAndHandshake();
        return;
      }
    }

    if (msg.reason === 'LOCKED_OUT') {
      this.callbacks.onStateChange('locked');
    }
    this.callbacks.onAuthError(msg);
  }

  public async startCameraStream(
    cameraFacing: CameraFacingMode = 'front',
    enableAudio = false,
    quality: StreamQualityPreset = 'hd'
  ): Promise<void> {
    this.desiredStreaming = true;
    this.desiredFacing = cameraFacing;
    this.desiredAudio = enableAudio;
    this.desiredQuality = quality;

    if (!this.signaling || !this.authenticated) {
      this.callbacks.onStateChange('connecting');
      return;
    }

    this.closePeerConnectionOnly();

    this.activeSessionId = randomHex(10);
    this.pendingCandidates = [];
    this.callbacks.onStateChange('connecting');
    this.callbacks.onLog(
      'info',
      `Allumage de la caméra ${cameraFacing === 'front' ? 'avant (selfie)' : 'arrière'}...`
    );

    this.setupPeerConnection(this.activeSessionId);

    await this.signaling.send(
      {
        type: 'CMD_START_STREAM',
        sessionId: this.activeSessionId,
        cameraFacing,
        enableAudio,
        quality,
        ts: Date.now(),
      },
      'sess'
    );

    // Automatic stream retry if SDP offer didn't arrive within 6s (e.g. mobile cell handover)
    if (this.streamRecoverTimer) window.clearTimeout(this.streamRecoverTimer);
    const currentSid = this.activeSessionId;
    this.streamRecoverTimer = window.setTimeout(() => {
      if (
        this.desiredStreaming &&
        this.authenticated &&
        this.activeSessionId === currentSid &&
        this.pc?.connectionState !== 'connected'
      ) {
        void this.startCameraStream(this.desiredFacing, this.desiredAudio, this.desiredQuality);
      }
    }, 6500);
  }

  public async stopCameraStream(): Promise<void> {
    this.desiredStreaming = false;
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
      this.callbacks.onLog('info', 'Caméra mise en veille (pont d’écoute toujours actif).');
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
    this.remoteStream = new MediaStream();

    pc.ontrack = (event) => {
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
        this.callbacks.onStateChange('streaming');
        this.callbacks.onLog('security', 'Flux vidéo direct établi (DTLS-SRTP).');
        this.startStatsMonitor();
      } else if (state === 'failed' || state === 'disconnected') {
        this.stopStatsMonitor();
        if (this.desiredStreaming && this.pc === pc) {
          this.callbacks.onLog('warn', 'Changement réseau détecté — reconnexion vidéo automatique...');
          window.setTimeout(() => {
            if (this.desiredStreaming && this.pc === pc && pc.connectionState !== 'connected') {
              this.closePeerConnectionOnly();
              if (this.authenticated) {
                void this.startCameraStream(this.desiredFacing, this.desiredAudio, this.desiredQuality);
              } else {
                void this.sendWakeAndHandshake();
              }
            }
          }, 2500);
        }
      }
    };
  }

  private async handleSdpOffer(msg: SdpOfferMsg): Promise<void> {
    if (!this.pc || !this.signaling || msg.sessionId !== this.activeSessionId) return;

    this.remoteSdpFingerprint = msg.fingerprint || extractSdpFingerprint(msg.sdp);

    await this.pc.setRemoteDescription(
      new RTCSessionDescription({
        type: 'offer',
        sdp: msg.sdp,
      })
    );

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
        ts: Date.now(),
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
    if (msg.state === 'standby') {
      this.desiredStreaming = false;
      this.closePeerConnectionOnly();
      if (this.authenticated) {
        this.callbacks.onStateChange('standby');
      }
    } else if (msg.state === 'error' && msg.error) {
      this.callbacks.onLog('warn', `Caméra distante : ${msg.error}`);
    }
  }

  private startStatsMonitor(): void {
    this.stopStatsMonitor();
    this.lastBytesReceived = 0;
    this.lastStatsTimestamp = performance.now();

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

        report.forEach((stat) => {
          if (stat.type === 'candidate-pair' && stat.state === 'succeeded' && stat.nominated) {
            if (typeof stat.currentRoundTripTime === 'number') {
              rttMs = Math.round(stat.currentRoundTripTime * 1000);
            }
          }
          if (stat.type === 'inbound-rtp' && stat.kind === 'video') {
            const now = performance.now();
            const bytes = stat.bytesReceived ?? 0;
            if (this.lastBytesReceived > 0 && now > this.lastStatsTimestamp) {
              const deltaSec = (now - this.lastStatsTimestamp) / 1000;
              bitrateKbps = Math.round(((bytes - this.lastBytesReceived) * 8) / 1000 / deltaSec);
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
    if (this.watchdogTimer !== null) {
      window.clearInterval(this.watchdogTimer);
      this.watchdogTimer = null;
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
    this.handshakeInFlight = false;
    this.callbacks.onStateChange('offline');
  }
}
