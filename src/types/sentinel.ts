export type PortalOperationalState = 'offline' | 'standby' | 'connecting' | 'streaming' | 'locked';

export type CameraFacingMode = 'front' | 'back';

export type StreamQualityPreset = 'sd' | 'hd';

export interface EncryptedEnvelope {
  v: 1;
  id: string;
  mode: 'boot' | 'sess';
  sender: string;
  ts: number;
  iv: string;
  ct: string;
}

export interface DerivedBootstrapKeys {
  normalizedCode: string;
  topicId: string;
  wakePushTopic: string;
  bootstrapEncKey: CryptoKey;
  bootstrapEncRaw: Uint8Array;
  bootstrapAuthRaw: Uint8Array;
}

export interface PairedDeviceRecord {
  pairingCode: string;
  deviceId: string;
  deviceName: string;
  tokenId: string;
  sasCode: string;
  sasSymbols: string;
  pairedAt: number;
  lastConnectedAt: number;
}

export interface DeviceTelemetry {
  deviceId: string;
  deviceName: string;
  portalState: 'standby' | 'streaming' | 'locked';
  paired: boolean;
  pairedTokenId: string | null;
  lockoutRemainingMs: number;
  failedAttempts: number;
  batteryLevel: number;
  isCharging: boolean;
  batteryTempC?: number;
  networkType: string;
  cameraFacing: CameraFacingMode;
  micEnabled: boolean;
  uptimeSec: number;
  updatedAt: number;
}

export interface StreamRealtimeStats {
  rttMs: number;
  bitrateKbps: number;
  fps: number;
  width: number;
  height: number;
  packetsLost: number;
  codec: string;
  dtlsState: string;
  localFingerprint: string;
  remoteFingerprint: string;
}

export interface SnapshotItem {
  id: string;
  dataUrl: string;
  timestamp: number;
  deviceName: string;
  width: number;
  height: number;
}

export interface TurnServerSetting {
  urls: string;
  username?: string;
  credential?: string;
}

/* ==================== V3 1-RTT INSTANT E2EE PROTOCOL PAYLOADS ==================== */

export interface HelloWakeMsg {
  type: 'HELLO_WAKE';
  controllerId: string;
  tokenId: string;
  clientNonce: string;
  proofHex: string;
  autoStartStream: boolean;
  sessionId: string;
  cameraFacing: CameraFacingMode;
  enableAudio: boolean;
  quality: StreamQualityPreset;
  ts: number;
}

export interface HelloAckMsg {
  type: 'HELLO_ACK';
  deviceId: string;
  deviceName: string;
  echoClientNonce: string;
  serverNonce: string;
  serverProofHex: string;
  sasCode: string;
  sasSymbols: string;
  portalState: 'standby' | 'streaming';
  batteryLevel: number;
  isCharging: boolean;
  networkType: string;
  cameraFacing: CameraFacingMode;
  micEnabled: boolean;
  uptimeSec: number;
  ts: number;
}

export interface PingStatusMsg {
  type: 'PING_STATUS';
  controllerId: string;
  tokenId?: string;
  ts: number;
}

export interface PortalStatusMsg {
  type: 'PORTAL_STATUS';
  deviceId: string;
  deviceName: string;
  portalState: 'standby' | 'streaming' | 'locked';
  paired: boolean;
  pairedTokenId: string | null;
  lockoutRemainingMs: number;
  failedAttempts: number;
  batteryLevel: number;
  isCharging: boolean;
  batteryTempC?: number;
  networkType: string;
  cameraFacing: CameraFacingMode;
  micEnabled: boolean;
  uptimeSec: number;
  ts: number;
}

export interface AuthErrMsg {
  type: 'AUTH_ERR';
  reason: 'LOCKED_OUT' | 'INVALID_PROOF';
  message: string;
  lockoutRemainingMs: number;
  failedAttempts: number;
  ts: number;
}

export interface CmdStartStreamMsg {
  type: 'CMD_START_STREAM';
  sessionId: string;
  cameraFacing: CameraFacingMode;
  enableAudio: boolean;
  quality: StreamQualityPreset;
  ts: number;
}

export interface CmdStopStreamMsg {
  type: 'CMD_STOP_STREAM';
  sessionId: string;
  ts: number;
}

export interface CmdSwitchCameraMsg {
  type: 'CMD_SWITCH_CAMERA';
  sessionId: string;
  cameraFacing: CameraFacingMode;
  ts: number;
}

export interface CmdToggleMicMsg {
  type: 'CMD_TOGGLE_MIC';
  sessionId: string;
  enableAudio: boolean;
  ts: number;
}

export interface SdpOfferMsg {
  type: 'SDP_OFFER';
  sessionId: string;
  sdp: string;
  fingerprint: string;
  ts: number;
}

export interface SdpAnswerMsg {
  type: 'SDP_ANSWER';
  sessionId: string;
  sdp: string;
  fingerprint: string;
}

export interface IceCandidateMsg {
  type: 'ICE_CANDIDATE';
  sessionId: string;
  candidate: string;
  sdpMid: string | null;
  sdpMLineIndex: number | null;
  ts: number;
}

export interface StreamStateMsg {
  type: 'STREAM_STATE';
  sessionId: string;
  state: 'starting' | 'streaming' | 'standby' | 'error';
  cameraFacing: CameraFacingMode;
  micEnabled: boolean;
  error?: string;
  ts: number;
}

export type SignalMessage =
  | HelloWakeMsg
  | HelloAckMsg
  | PingStatusMsg
  | PortalStatusMsg
  | AuthErrMsg
  | CmdStartStreamMsg
  | CmdStopStreamMsg
  | CmdSwitchCameraMsg
  | CmdToggleMicMsg
  | SdpOfferMsg
  | SdpAnswerMsg
  | IceCandidateMsg
  | StreamStateMsg;

/* ==================== ANDROID NATIVE PLUGIN TYPES ==================== */

export interface NativePermissionStatus {
  camera: boolean;
  microphone: boolean;
  notifications: boolean;
  batteryOptimizationIgnored: boolean;
}

export interface NativeAuditEntry {
  timestamp: number;
  level: 'info' | 'warn' | 'security';
  message: string;
}

export interface NativePortalState {
  onboardingCompleted: boolean;
  serviceRunning: boolean;
  portalState: 'stopped' | 'standby' | 'streaming' | 'locked';
  pairingCode: string;
  deviceId: string;
  deviceName: string;
  paired: boolean;
  pairedTokenId: string | null;
  sasCode: string | null;
  sasSymbols: string | null;
  mqttConnected: boolean;
  activeBroker: string;
  batteryLevel: number;
  isCharging: boolean;
  cameraFacing: CameraFacingMode;
  micEnabled: boolean;
  autoStartOnBoot: boolean;
  failedAttempts: number;
  lockoutRemainingMs: number;
  permissions: NativePermissionStatus;
  logs: NativeAuditEntry[];
}
