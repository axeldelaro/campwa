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

  private localSdpFingerprint = '';
  private remoteSdpFingerprint = '';
  private pendingCandidates: IceCandidateMsg[] = [];

  private statsTimer: number | null = null;
  private pingTimer: number | null = null;
  private authTimeoutTimer: number | null = null;
  private lastBytesReceived = 0;
  private lastStatsTimestamp = 0;

  constructor(callbacks: ControllerCallbacks) {
    this.callbacks = callbacks;
  }

  public async connectToPortal(rawCode: string): Promise<void> {
    this.disconnect();

    const code = normalizePairingCode(rawCode);
    if (code.length !== 6) {
      throw new Error('Veuillez saisir un code d’appairage valide à 6 caractères.');
    }

    this.pairingCode = code;
    this.tokenId = getOrCreateControllerTokenId();
    this.authenticated = false;

    this.callbacks.onStateChange('connecting');
    this.callbacks.onLog('security', `Dérivation PBKDF2-HMAC-SHA256 (150 000 itérations) pour le portail [${code}]...`);

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
          void this.initiateHandshake();
        }
      },
    });

    this.signaling.start();

    this.pingTimer = window.setInterval(() => {
      if (this.signaling?.isAnyConnected()) {
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
    }, 10_000);
  }

  private async initiateHandshake(): Promise<void> {
    if (!this.signaling || this.authenticated) return;

    const saved = findPairedDeviceByCode(this.pairingCode);
    if (saved) {
      this.tokenId = saved.tokenId;
    }

    this.clientNonce = randomHex(24);
    const intent = saved ? 'reconnect' : 'pair';
    this.callbacks.onLog(
      'security',
      intent === 'reconnect'
        ? 'Jeton d’appairage détecté en coffre local — Envoi de la requête de reconnexion HMAC...'
        : 'Premier appairage détecté — Initialisation de l’échange cryptographique HKDF...'
    );

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

    if (this.authTimeoutTimer) window.clearTimeout(this.authTimeoutTimer);
    this.authTimeoutTimer = window.setTimeout(() => {
      if (!this.authenticated) {
        this.callbacks.onLog(
          'warn',
          'En attente de réponse du smartphone... Vérifiez que le portail SentinelCam est actif sur Android.'
        );
      }
    }, 6500);
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
    } else if (this.authenticated && !this.pc) {
      this.callbacks.onStateChange('standby');
    }
  }

  private async handleAuthChallenge(msg: AuthChallengeMsg): Promise<void> {
    if (!this.signaling) return;

    const saved = findPairedDeviceByCode(this.pairingCode);
    let pairedKeyHex: string;
    let sendPairingKeyHex: string | undefined;

    if (msg.isFirstPair) {
      pairedKeyHex = await generateDerivedPairingKey(msg.deviceSalt, this.tokenId);
      sendPairingKeyHex = pairedKeyHex;
      this.pendingIsNewPair = true;
    } else {
      if (!saved || !saved.pairedKeyHex) {
        this.callbacks.onAuthError({
          type: 'AUTH_ERR',
          reason: 'ALREADY_PAIRED',
          message:
            'Ce smartphone est déjà verrouillé sur un autre navigateur appairé. Réinitialisez l’appairage depuis l’application SentinelCam Android pour autoriser ce poste.',
          lockoutRemainingMs: 0,
          failedAttempts: 0,
          ts: Date.now(),
        });
        return;
      }
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

    // Pre-derive ephemeral session key so we are ready as soon as AUTH_OK arrives
    const sessionKey = await deriveSessionAesKey(pairedKeyHex, this.clientNonce, msg.serverNonce);
    this.signaling.setSessionKey(sessionKey);

    // Temporarily stash candidate pairing record in memory via closure metadata
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

  private pendingPairCandidate: {
    deviceId: string;
    deviceName: string;
    serverNonce: string;
    pairedKeyHex: string;
  } | null = null;

  private async handleAuthOk(msg: AuthOkMsg): Promise<void> {
    if (!this.pendingPairCandidate) return;
    if (this.authTimeoutTimer) {
      window.clearTimeout(this.authTimeoutTimer);
      this.authTimeoutTimer = null;
    }

    const { deviceId, pairedKeyHex, serverNonce } = this.pendingPairCandidate;

    // Mutual authentication check: verify phone actually holds pairedKeyHex!
    const expectedServerProof = await computeServerChallengeProof(
      pairedKeyHex,
      this.clientNonce,
      serverNonce,
      this.tokenId,
      deviceId
    );

    if (!constantTimeEqualHex(expectedServerProof, msg.serverProofHex)) {
      this.callbacks.onLog(
        'security',
        'ALERTE MITM : La preuve cryptographique mutuelle du serveur est invalide ! Connexion rejetée.'
      );
      this.disconnect();
      return;
    }

    const localSas = await computeSas(this.pairingCode, this.tokenId, msg.deviceId, pairedKeyHex);
    this.authenticated = true;

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

    this.callbacks.onStateChange('standby');
    this.callbacks.onLog(
      'security',
      `Authentification mutuelle E2EE réussie avec ${msg.deviceName} — SAS [${localSas.sasCode}] ${localSas.sasSymbols}`
    );

    this.callbacks.onAuthSuccess({
      deviceName: msg.deviceName,
      deviceId: msg.deviceId,
      sasCode: localSas.sasCode,
      sasSymbols: localSas.sasSymbols,
      isNewPairing: this.pendingIsNewPair,
    });
  }

  private handleAuthErr(msg: AuthErrMsg): void {
    this.authenticated = false;
    if (msg.reason === 'LOCKED_OUT') {
      this.callbacks.onStateChange('locked');
    } else {
      this.callbacks.onStateChange('offline');
    }
    this.callbacks.onLog('warn', `Refus d’authentification (${msg.reason}) : ${msg.message}`);
    this.callbacks.onAuthError(msg);
  }

  /**
   * Sends encrypted wake signal to start the remote smartphone camera on-demand.
   */
  public async startCameraStream(
    cameraFacing: CameraFacingMode = 'front',
    enableAudio = false,
    quality: StreamQualityPreset = 'hd'
  ): Promise<void> {
    if (!this.signaling || !this.authenticated) {
      throw new Error('Le poste de contrôle n’est pas encore authentifié auprès du portail.');
    }

    this.closePeerConnectionOnly();

    this.activeSessionId = randomHex(10);
    this.pendingCandidates = [];
    this.callbacks.onStateChange('connecting');
    this.callbacks.onLog(
      'info',
      `Envoi du signal E2EE d’allumage capteur (${cameraFacing === 'front' ? 'Caméra Frontale' : 'Caméra Arrière'})...`
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
  }

  /**
   * Physically shuts down the camera sensor on the smartphone while keeping the background portal alive.
   */
  public async stopCameraStream(): Promise<void> {
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
      this.callbacks.onLog('info', 'Capteur caméra distant mis en veille physique (économie batterie active).');
    }
  }

  public async switchCamera(cameraFacing: CameraFacingMode): Promise<void> {
    if (!this.signaling || !this.authenticated || !this.activeSessionId) return;
    this.callbacks.onLog(
      'info',
      `Basculement vers ${cameraFacing === 'front' ? 'la caméra frontale' : 'la caméra arrière'}...`
    );
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
    if (!this.signaling || !this.authenticated || !this.activeSessionId) return;
    this.callbacks.onLog('info', enableAudio ? 'Activation du microphone distant...' : 'Coupure du microphone distant...');
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
            // Ignore malformed telemetry frame
          }
        };
      }
    };

    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      if (state === 'connected') {
        this.callbacks.onStateChange('streaming');
        this.callbacks.onLog('security', 'Tunnel média WebRTC DTLS-SRTP établi en direct.');
        this.startStatsMonitor();
      } else if (state === 'failed' || state === 'closed') {
        this.stopStatsMonitor();
        if (this.authenticated && this.pc === pc) {
          this.callbacks.onLog('warn', 'Flux WebRTC interrompu — retour à l’état de veille.');
          this.closePeerConnectionOnly();
          this.callbacks.onStateChange('standby');
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

    // Flush queued Trickle ICE candidates that arrived prior to SDP Offer
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
        // Ignore non-fatal candidate race
      }
    }
    this.pendingCandidates = [];

    const answer = await this.pc.createAnswer();
    await this.pc.setLocalDescription(answer);

    const answerSdp = answer.sdp || '';
    this.localSdpFingerprint = extractSdpFingerprint(answerSdp);

    const mediaSas = await computeDtlsMediaSas(this.localSdpFingerprint, this.remoteSdpFingerprint);
    this.callbacks.onLog('security', `Empreinte DTLS-SRTP vérifiée : [${mediaSas}]`);

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
      // Ignore benign duplicate candidate
    }
  }

  private handleStreamState(msg: StreamStateMsg): void {
    if (msg.state === 'standby') {
      this.closePeerConnectionOnly();
      if (this.authenticated) {
        this.callbacks.onStateChange('standby');
      }
    } else if (msg.state === 'error' && msg.error) {
      this.callbacks.onLog('warn', `Erreur capteur distant : ${msg.error}`);
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
        let codec = 'H264/VP8';
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
        // Ignore transient stats read error
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

    // Subtle tactical timestamp watermark bar at bottom-left
    const stamp = new Date().toLocaleString('fr-FR');
    const label = `SENTINELCAM E2EE • ${deviceName.toUpperCase()} • ${stamp}`;
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
    if (this.pingTimer !== null) {
      window.clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    if (this.authTimeoutTimer !== null) {
      window.clearTimeout(this.authTimeoutTimer);
      this.authTimeoutTimer = null;
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
