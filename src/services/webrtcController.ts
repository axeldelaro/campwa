import {
  computeDtlsMediaSas,
  computeHelloAckProof,
  computeHelloWakeProof,
  computeSas,
  constantTimeEqualHex,
  deriveBootstrapKeys,
  deriveFastSessionKey,
  extractSdpFingerprint,
  normalizePairingCode,
  randomHex,
} from '../crypto/cryptoEngine';
import type {
  ActiveAccessGrant,
  AuthErrMsg,
  CameraFacingMode,
  DerivedBootstrapKeys,
  DeviceTelemetry,
  HelloAckMsg,
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
}

export class SentinelWebRtcController {
  private readonly callbacks: ControllerCallbacks;
  private signaling: EncryptedSignalingChannel | null = null;
  private bootstrapKeys: DerivedBootstrapKeys | null = null;
  private pc: RTCPeerConnection | null = null;
  private remoteStream: MediaStream | null = null;

  private pairingCode = '';
  private tokenId = '';
  private activeSessionId = '';
  private authenticated = false;

  private desiredStreaming = false;
  private desiredFacing: CameraFacingMode = 'front';
  private desiredAudio = false;
  private desiredQuality: StreamQualityPreset = 'hd';

  private localSdpFingerprint = '';
  private remoteSdpFingerprint = '';
  private pendingCandidates: IceCandidateMsg[] = [];

  private statsTimer: number | null = null;
  private retryTimer: number | null = null;
  private streamRecoverTimer: number | null = null;
  private lastBytesReceived = 0;
  private lastStatsTimestamp = 0;

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
    const proofHex = await computeHelloWakeProof(
      this.bootstrapKeys.bootstrapAuthRaw,
      clientNonce,
      this.tokenId
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
        autoStartStream: this.desiredStreaming,
        sessionId: this.activeSessionId || randomHex(10),
        cameraFacing: this.desiredFacing,
        enableAudio: this.desiredAudio,
        quality: this.desiredQuality,
        ts: Date.now(),
      },
      'boot'
    );
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

      default:
        break;
    }
  }

  private async handleHelloAck(msg: HelloAckMsg): Promise<void> {
    if (!this.signaling || !this.bootstrapKeys) return;

    const expectedServerProof = await computeHelloAckProof(
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.echoClientNonce,
      msg.serverNonce,
      msg.deviceId
    );

    if (!constantTimeEqualHex(expectedServerProof, msg.serverProofHex)) {
      return;
    }

    const sessionKey = await deriveFastSessionKey(
      this.bootstrapKeys.bootstrapAuthRaw,
      msg.echoClientNonce,
      msg.serverNonce,
      msg.deviceId
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
    };

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
      micEnabled: msg.micEnabled,
      uptimeSec: msg.uptimeSec,
      accessRole: accessGrant.role,
      accessName: accessGrant.name,
      accessExpiresAt: accessGrant.expiresAt,
      allowAudio: accessGrant.allowAudio,
      allowSwitchCamera: accessGrant.allowSwitchCamera,
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
      accessRole: msg.accessRole,
      accessName: msg.accessName,
      accessExpiresAt: msg.accessExpiresAt,
      allowAudio: msg.allowAudio,
      allowSwitchCamera: msg.allowSwitchCamera,
      updatedAt: Date.now(),
    });

    if (msg.portalState === 'locked') {
      this.callbacks.onStateChange('locked');
    } else if (this.authenticated && !this.pc && !this.desiredStreaming) {
      this.callbacks.onStateChange('standby');
    }
  }

  private handleAuthErr(msg: AuthErrMsg): void {
    this.authenticated = false;
    this.desiredStreaming = false;
    this.closePeerConnectionOnly();

    if (msg.reason === 'ACCESS_EXPIRED' || msg.reason === 'ACCESS_REVOKED') {
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
    quality: StreamQualityPreset = 'hd'
  ): Promise<void> {
    this.desiredStreaming = true;
    this.desiredFacing = cameraFacing;
    this.desiredAudio = enableAudio;
    this.desiredQuality = quality;

    if (!this.signaling || !this.authenticated) {
      this.callbacks.onStateChange('connecting');
      void this.sendHelloWake();
      return;
    }

    this.closePeerConnectionOnly();

    this.activeSessionId = randomHex(10);
    this.pendingCandidates = [];
    this.callbacks.onStateChange('connecting');

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
    }, 5500);
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
        this.startStatsMonitor();
      } else if (state === 'failed' || state === 'disconnected') {
        this.stopStatsMonitor();
        if (this.desiredStreaming && this.pc === pc) {
          window.setTimeout(() => {
            if (this.desiredStreaming && this.pc === pc && pc.connectionState !== 'connected') {
              this.closePeerConnectionOnly();
              if (this.authenticated) {
                void this.startCameraStream(this.desiredFacing, this.desiredAudio, this.desiredQuality);
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
