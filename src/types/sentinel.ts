export type PortalOperationalState = 'offline' | 'standby' | 'connecting' | 'streaming' | 'locked';

export type CameraFacingMode = 'front' | 'back';

export type MediaSourceMode = 'camera' | 'screen' | 'intercom';

export type StreamQualityPreset = 'sd' | 'hd';

export type AccessProfileRole = 'owner' | 'friend' | 'guest';

export type GpsTrackingMode = 'eco' | 'balanced' | 'precision';

export type GpsTrackerState =
  | 'disabled'
  | 'no_permission'
  | 'moving'
  | 'stationary'
  | 'battery_saver';

export interface GlobalShareSettings {
  allowScreenShare: boolean;
  allowContacts: boolean;
  allowLocation: boolean;
  allowLocationHistory: boolean;
  gpsTrackingMode: GpsTrackingMode;
  allowDeviceInfo: boolean;
  allowIntercom: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
  screenShareReady: boolean;
}

export interface AccessProfile {
  id: string;
  name: string;
  role: 'friend' | 'guest';
  code: string;
  enabled: boolean;
  createdAt: number;
  expiresAt: number | null;
  allowAudio: boolean;
  allowSwitchCamera: boolean;
  allowScreenShare: boolean;
  allowContacts: boolean;
  allowLocation: boolean;
  allowDeviceInfo: boolean;
  allowIntercom: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
  lastAccessAt: number | null;
}

export interface AccessProfileInput {
  id?: string;
  name: string;
  role: 'friend' | 'guest';
  code: string;
  enabled: boolean;
  expiresAt: number | null;
  allowAudio: boolean;
  allowSwitchCamera: boolean;
  allowScreenShare: boolean;
  allowContacts: boolean;
  allowLocation: boolean;
  allowDeviceInfo: boolean;
  allowIntercom: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
}

export interface SharedContactItem {
  id: string;
  name: string;
  phones: string[];
  emails: string[];
}

export interface SharedLocationData {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  speedKmh?: number;
  bearing?: number;
  altitude?: number;
  batteryLevel?: number;
  dwellSeconds?: number;
  provider: string;
  timestamp: number;
}

export interface GpsTrackPoint {
  timestamp: number;
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  speedKmh: number;
  batteryLevel: number;
  dwellSeconds: number;
  provider: string;
}

export interface LocationHistoryBundle {
  points: GpsTrackPoint[];
  rawPointCount: number;
  totalDistanceMeters: number;
  maxSpeedKmh: number;
  stopsCount: number;
  trackerState: GpsTrackerState;
  trackingMode: GpsTrackingMode;
  error?: string;
}

export interface SharedDeviceInfoData {
  deviceName: string;
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdkInt: number;
  batteryLevel: number;
  isCharging: boolean;
  networkType: string;
  storageFreeMb: number;
  storageTotalMb: number;
  uptimeSec: number;
  timestamp: number;
}

export interface EncryptedEnvelope {
  v: 1;
  aad?: 1;
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
  accessRole?: AccessProfileRole;
  accessName?: string;
  pairedAt: number;
  lastConnectedAt: number;
}

export interface ActiveAccessGrant {
  role: AccessProfileRole;
  name: string;
  expiresAt: number | null;
  allowAudio: boolean;
  allowSwitchCamera: boolean;
  allowScreenShare: boolean;
  allowContacts: boolean;
  allowLocation: boolean;
  allowDeviceInfo: boolean;
  allowIntercom: boolean;
  allowSmsRelay: boolean;
  allowDeviceControl: boolean;
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
  mediaMode?: MediaSourceMode;
  micEnabled: boolean;
  intercomActive?: boolean;
  torchEnabled?: boolean;
  ringingActive?: boolean;
  uptimeSec: number;
  accessRole?: AccessProfileRole;
  accessName?: string;
  accessExpiresAt?: number | null;
  allowAudio?: boolean;
  allowSwitchCamera?: boolean;
  allowScreenShare?: boolean;
  allowContacts?: boolean;
  allowLocation?: boolean;
  allowDeviceInfo?: boolean;
  allowIntercom?: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  adminProtectionEnabled?: boolean;
  hasAdminPin?: boolean;
  deviceAdminActive?: boolean;
  pfsEnabled?: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
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

/* ==================== V6 1-RTT INSTANT E2EE PROTOCOL PAYLOADS ==================== */

export interface HelloWakeMsg {
  type: 'HELLO_WAKE';
  controllerId: string;
  tokenId: string;
  clientNonce: string;
  proofHex: string;
  clientEcdhPub?: string;
  autoStartStream: boolean;
  sessionId: string;
  mediaMode?: MediaSourceMode;
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
  serverEcdhPub?: string;
  pfsEnabled?: boolean;
  sasCode: string;
  sasSymbols: string;
  portalState: 'standby' | 'streaming';
  batteryLevel: number;
  isCharging: boolean;
  networkType: string;
  cameraFacing: CameraFacingMode;
  mediaMode?: MediaSourceMode;
  micEnabled: boolean;
  intercomActive?: boolean;
  torchEnabled?: boolean;
  ringingActive?: boolean;
  uptimeSec: number;
  accessRole?: AccessProfileRole;
  accessName?: string;
  accessExpiresAt?: number | null;
  allowAudio?: boolean;
  allowSwitchCamera?: boolean;
  allowScreenShare?: boolean;
  allowContacts?: boolean;
  allowLocation?: boolean;
  allowDeviceInfo?: boolean;
  allowIntercom?: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  adminProtectionEnabled?: boolean;
  hasAdminPin?: boolean;
  deviceAdminActive?: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
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
  mediaMode?: MediaSourceMode;
  micEnabled: boolean;
  intercomActive?: boolean;
  torchEnabled?: boolean;
  ringingActive?: boolean;
  uptimeSec: number;
  accessRole?: AccessProfileRole;
  accessName?: string;
  accessExpiresAt?: number | null;
  allowAudio?: boolean;
  allowSwitchCamera?: boolean;
  allowScreenShare?: boolean;
  allowContacts?: boolean;
  allowLocation?: boolean;
  allowDeviceInfo?: boolean;
  allowIntercom?: boolean;
  allowSmsRelay?: boolean;
  allowDeviceControl?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  adminProtectionEnabled?: boolean;
  hasAdminPin?: boolean;
  deviceAdminActive?: boolean;
  pfsEnabled?: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
  ts: number;
}

export interface AuthErrMsg {
  type: 'AUTH_ERR';
  reason: 'LOCKED_OUT' | 'INVALID_PROOF' | 'ACCESS_EXPIRED' | 'ACCESS_REVOKED';
  message: string;
  lockoutRemainingMs: number;
  failedAttempts: number;
  ts: number;
}

export interface CmdStartStreamMsg {
  type: 'CMD_START_STREAM';
  sessionId: string;
  mediaMode?: MediaSourceMode;
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

export interface CmdIntercomStateMsg {
  type: 'CMD_INTERCOM_STATE';
  sessionId: string;
  active: boolean;
  ts: number;
}

export interface CmdGetContactsMsg {
  type: 'CMD_GET_CONTACTS';
  requestId: string;
  query?: string;
  ts: number;
}

export interface DataContactsMsg {
  type: 'DATA_CONTACTS';
  requestId: string;
  contacts: SharedContactItem[];
  totalCount: number;
  error?: string;
  ts: number;
}

export interface CmdGetLocationMsg {
  type: 'CMD_GET_LOCATION';
  requestId: string;
  ts: number;
}

export interface DataLocationMsg {
  type: 'DATA_LOCATION';
  requestId: string;
  location: SharedLocationData | null;
  trackerState?: GpsTrackerState;
  trackingMode?: GpsTrackingMode;
  error?: string;
  ts: number;
}

export interface CmdGetLocationHistoryMsg {
  type: 'CMD_GET_LOCATION_HISTORY';
  requestId: string;
  fromTs: number;
  toTs: number;
  maxPoints?: number;
  ts: number;
}

export interface CmdClearLocationHistoryMsg {
  type: 'CMD_CLEAR_LOCATION_HISTORY';
  ts: number;
}

export interface DataLocationHistoryMsg {
  type: 'DATA_LOCATION_HISTORY';
  requestId: string;
  chunkIndex: number;
  totalChunks: number;
  /** Compact tuples: [ts, lat, lng, acc, speedKmh, bat, dwellSec, providerCode] */
  points: [number, number, number, number, number, number, number, string][];
  rawPointCount: number;
  totalDistanceMeters: number;
  maxSpeedKmh: number;
  stopsCount: number;
  trackerState?: GpsTrackerState;
  trackingMode?: GpsTrackingMode;
  error?: string;
  ts: number;
}

export interface CmdGetDeviceInfoMsg {
  type: 'CMD_GET_DEVICE_INFO';
  requestId: string;
  ts: number;
}

export interface DataDeviceInfoMsg {
  type: 'DATA_DEVICE_INFO';
  requestId: string;
  info: SharedDeviceInfoData | null;
  error?: string;
  ts: number;
}

export interface CmdSendSmsMsg {
  type: 'CMD_SEND_SMS';
  requestId: string;
  phoneNumber: string;
  contactName?: string;
  message: string;
  includeGpsLink?: boolean;
  ts: number;
}

export interface DataSmsResultMsg {
  type: 'DATA_SMS_RESULT';
  requestId: string;
  success: boolean;
  phoneNumber: string;
  sentText?: string;
  error?: string;
  remainingQuota?: number;
  ts: number;
}

export interface CmdToggleTorchMsg {
  type: 'CMD_TOGGLE_TORCH';
  enable: boolean;
  ts: number;
}

export interface CmdRingDeviceMsg {
  type: 'CMD_RING_DEVICE';
  active: boolean;
  durationSec?: number;
  ts: number;
}

export interface AppUsageEntry {
  packageName: string;
  appName: string;
  usageSecondsToday: number;
  lastUsedAt: number;
  blockedUntilMs: number;
}

export interface DataScreenTimeMsg {
  type: 'DATA_SCREEN_TIME';
  requestId: string;
  hasUsageAccess?: boolean;
  hasOverlayPermission?: boolean;
  deviceAdminActive?: boolean;
  totalScreenTimeSec?: number;
  currentPackage?: string | null;
  currentAppName?: string | null;
  screenPausedUntilMs?: number;
  apps?: AppUsageEntry[];
  error?: string;
  ts: number;
}

export interface DataControlResultMsg {
  type: 'DATA_CONTROL_RESULT';
  requestId: string;
  action: 'lock_screen' | 'app_lock' | 'screen_pause' | 'clear_locks' | 'set_admin_pin';
  success: boolean;
  error?: string;
  ts: number;
}

export interface CmdLockScreenMsg {
  type: 'CMD_LOCK_SCREEN';
  requestId: string;
  ts: number;
}

export interface CmdGetScreenTimeMsg {
  type: 'CMD_GET_SCREEN_TIME';
  requestId: string;
  ts: number;
}

export interface CmdSetAppLockMsg {
  type: 'CMD_SET_APP_LOCK';
  requestId: string;
  packageName: string;
  appName: string;
  durationMinutes: number;
  untilMs?: number;
  ts: number;
}

export interface CmdSetScreenPauseMsg {
  type: 'CMD_SET_SCREEN_PAUSE';
  requestId: string;
  durationMinutes: number;
  lockImmediately?: boolean;
  ts: number;
}

export interface CmdClearAllAppLocksMsg {
  type: 'CMD_CLEAR_ALL_APP_LOCKS';
  requestId: string;
  ts: number;
}

export interface CmdSetAdminPinMsg {
  type: 'CMD_SET_ADMIN_PIN';
  requestId: string;
  newPin: string;
  enableProtection?: boolean;
  ts: number;
}

export interface CmdSpeakTextMsg {
  type: 'CMD_SPEAK_TEXT';
  text: string;
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
  state: 'starting' | 'streaming' | 'standby' | 'error' | 'expired' | 'revoked';
  cameraFacing: CameraFacingMode;
  mediaMode?: MediaSourceMode;
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
  | CmdIntercomStateMsg
  | CmdGetContactsMsg
  | DataContactsMsg
  | CmdGetLocationMsg
  | DataLocationMsg
  | CmdGetLocationHistoryMsg
  | CmdClearLocationHistoryMsg
  | DataLocationHistoryMsg
  | CmdGetDeviceInfoMsg
  | DataDeviceInfoMsg
  | CmdSendSmsMsg
  | DataSmsResultMsg
  | CmdToggleTorchMsg
  | CmdRingDeviceMsg
  | CmdSpeakTextMsg
  | CmdLockScreenMsg
  | CmdGetScreenTimeMsg
  | CmdSetAppLockMsg
  | CmdSetScreenPauseMsg
  | CmdClearAllAppLocksMsg
  | CmdSetAdminPinMsg
  | DataScreenTimeMsg
  | DataControlResultMsg
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
  contacts: boolean;
  location: boolean;
  backgroundLocation?: boolean;
  sms?: boolean;
  usageAccess?: boolean;
  overlay?: boolean;
  screenShareReady: boolean;
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
  mediaMode: MediaSourceMode;
  micEnabled: boolean;
  intercomActive: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
  gpsLastFixTs?: number;
  hardwareKeystoreReady?: boolean;
  pfsEnabled?: boolean;
  adminProtectionEnabled?: boolean;
  hasAdminPin?: boolean;
  deviceAdminActive?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  autoStartOnBoot: boolean;
  failedAttempts: number;
  lockoutRemainingMs: number;
  permissions: NativePermissionStatus;
  globalShares: GlobalShareSettings;
  logs: NativeAuditEntry[];
  accessProfiles: AccessProfile[];
  activeViewerName: string | null;
  activeViewerRole: AccessProfileRole | null;
  activeViewerExpiresAt: number | null;
}
