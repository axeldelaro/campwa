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
  allowGallery?: boolean;
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
  childName?: string;
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

export interface DevicePermissionsHealth {
  camera: boolean;
  microphone: boolean;
  location: boolean;
  backgroundLocation: boolean;
  contacts: boolean;
  readSms: boolean;
  sendSms: boolean;
  callLog?: boolean;
  callScreening?: boolean;
  writeSettings?: boolean;
  gallery: boolean;
  storageManage: boolean;
  usageAccess: boolean;
  overlay: boolean;
  batteryUnrestricted: boolean;
  deviceAdmin: boolean;
  manufacturer?: string;
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
  lostModeEnabled?: boolean;
  curfewEnabled?: boolean;
  schoolModeUntilMs?: number;
  blockSettingsAndStore?: boolean;
  pendingRequestsCount?: number;
  securityAlertsCount?: number;
  allowGallery?: boolean;
  hasGalleryPermission?: boolean;
  aiBlurFaces?: boolean;
  aiBlurNsfw?: boolean;
  aiNsfwStrict?: boolean;
  aiShieldRunning?: boolean;
  aiShieldNeedsConsent?: boolean;
  parentManaged?: boolean;
  unpairRequestPending?: boolean;
  unpairRequestedAt?: number;
  pfsEnabled?: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
  offlineRecordingActive?: boolean;
  offlineRecordingMode?: 'audio' | 'video';
  permissionsHealth?: DevicePermissionsHealth;
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
  lostModeEnabled?: boolean;
  curfewEnabled?: boolean;
  schoolModeUntilMs?: number;
  blockSettingsAndStore?: boolean;
  pendingRequestsCount?: number;
  securityAlertsCount?: number;
  allowGallery?: boolean;
  hasGalleryPermission?: boolean;
  aiBlurFaces?: boolean;
  aiBlurNsfw?: boolean;
  aiNsfwStrict?: boolean;
  aiShieldRunning?: boolean;
  aiShieldNeedsConsent?: boolean;
  parentManaged?: boolean;
  unpairRequestPending?: boolean;
  unpairRequestedAt?: number;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
  offlineRecordingActive?: boolean;
  offlineRecordingMode?: 'audio' | 'video';
  permissionsHealth?: DevicePermissionsHealth;
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
  lostModeEnabled?: boolean;
  curfewEnabled?: boolean;
  schoolModeUntilMs?: number;
  blockSettingsAndStore?: boolean;
  pendingRequestsCount?: number;
  securityAlertsCount?: number;
  allowGallery?: boolean;
  hasGalleryPermission?: boolean;
  aiBlurFaces?: boolean;
  aiBlurNsfw?: boolean;
  aiNsfwStrict?: boolean;
  aiShieldRunning?: boolean;
  aiShieldNeedsConsent?: boolean;
  parentManaged?: boolean;
  unpairRequestPending?: boolean;
  unpairRequestedAt?: number;
  pfsEnabled?: boolean;
  gpsTrackerState?: GpsTrackerState;
  gpsTrackingMode?: GpsTrackingMode;
  gpsPointsCount?: number;
  gpsTodayPointsCount?: number;
  offlineRecordingActive?: boolean;
  offlineRecordingMode?: 'audio' | 'video';
  permissionsHealth?: DevicePermissionsHealth;
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
  dailyLimitMinutes?: number;
  bonusMinutesToday?: number;
  quotaExceeded?: boolean;
  schoolAllowed?: boolean;
}

export interface DailyScreenHistoryEntry {
  dateKey: string;
  dayLabel: string;
  totalSeconds: number;
  isToday: boolean;
}

export interface CurfewConfig {
  enabled: boolean;
  startMin: number;
  endMin: number;
  daysMask: number;
  activeNow: boolean;
  endTs: number;
}

export interface SchoolModeState {
  active: boolean;
  untilMs: number;
  allowedPackages: string[];
}

export interface LostModeState {
  enabled: boolean;
  message: string;
  phoneNumber: string;
}

export interface TimeExtensionRequestItem {
  id: string;
  packageName: string;
  appName: string;
  requestedMinutes: number;
  createdAt: number;
}

export interface GeofenceZoneItem {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  inside: boolean | null;
  lastTransitionTs: number;
}

export interface SecurityEventEntry {
  id: string;
  timestamp: number;
  kind: 'intruder' | 'sim' | 'geofence' | 'battery' | 'bypass' | 'request' | 'admin';
  title: string;
  detail: string;
  photoBase64?: string;
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
  weeklyHistory?: DailyScreenHistoryEntry[];
  curfew?: CurfewConfig;
  schoolMode?: SchoolModeState;
  blockSettingsAndStore?: boolean;
  lostMode?: LostModeState;
  pendingRequests?: TimeExtensionRequestItem[];
  geofences?: GeofenceZoneItem[];
  securityEvents?: SecurityEventEntry[];
  error?: string;
  ts: number;
}

export interface DataControlResultMsg {
  type: 'DATA_CONTROL_RESULT';
  requestId: string;
  action:
    | 'lock_screen'
    | 'app_lock'
    | 'screen_pause'
    | 'clear_locks'
    | 'set_admin_pin'
    | 'curfew'
    | 'app_quota'
    | 'school_mode'
    | 'anti_bypass'
    | 'lost_mode'
    | 'time_request'
    | 'geofence'
    | 'clear_events'
    | 'ai_shield'
    | 'remote_policy'
    | 'unpair_decision'
    | 'flash_screen'
    | 'gallery_delete'
    | 'gallery_quarantine'
    | 'gallery_restore'
    | 'gallery_delete_quarantine'
    | 'call_block'
    | 'recording_start'
    | 'recording_stop'
    | 'recording_delete'
    | 'system_control';
  success: boolean;
  error?: string;
  ts: number;
}

export interface GalleryPhotoItem {
  id: string;
  name: string;
  album: string;
  dateTaken: number;
  width: number;
  height: number;
  thumbBase64: string;
  quarantined?: boolean;
}

export interface GalleryAlbumItem {
  name: string;
  count: number;
}

export interface CmdGetGalleryMsg {
  type: 'CMD_GET_GALLERY';
  requestId: string;
  page: number;
  pageSize?: number;
  album?: string;
  ts: number;
}

export interface DataGalleryMsg {
  type: 'DATA_GALLERY';
  requestId: string;
  items: GalleryPhotoItem[];
  albums: GalleryAlbumItem[];
  page: number;
  totalCount: number;
  hasMore: boolean;
  quarantineCount?: number;
  canManageFiles?: boolean;
  error?: string;
  ts: number;
}

export interface CmdGalleryActionMsg {
  type: 'CMD_GALLERY_ACTION';
  requestId: string;
  action:
    | 'delete'
    | 'quarantine'
    | 'list_quarantine'
    | 'get_quarantine_photo'
    | 'restore_quarantine'
    | 'delete_quarantine';
  mediaId?: string;
  ts: number;
}

export interface DataQuarantineListMsg {
  type: 'DATA_QUARANTINE_LIST';
  requestId: string;
  items: GalleryPhotoItem[];
  totalCount: number;
  error?: string;
  ts: number;
}

export interface SmsThreadItem {
  threadId: string;
  address: string;
  contactName: string;
  snippet: string;
  lastDate: number;
  lastType: 'inbox' | 'sent';
  messageCount: number;
  unreadCount: number;
}

export interface SmsMessageItem {
  id: string;
  body: string;
  date: number;
  type: 'inbox' | 'sent';
  read: boolean;
}

export interface CmdGetSmsThreadsMsg {
  type: 'CMD_GET_SMS_THREADS';
  requestId: string;
  ts: number;
}

export interface DataSmsThreadsMsg {
  type: 'DATA_SMS_THREADS';
  requestId: string;
  threads: SmsThreadItem[];
  hasReadPermission: boolean;
  hasSendPermission: boolean;
  error?: string;
  ts: number;
}

export interface CmdGetSmsMessagesMsg {
  type: 'CMD_GET_SMS_MESSAGES';
  requestId: string;
  threadId: string;
  address?: string;
  ts: number;
}

export interface DataSmsMessagesMsg {
  type: 'DATA_SMS_MESSAGES';
  requestId: string;
  threadId: string;
  address: string;
  messages: SmsMessageItem[];
  error?: string;
  ts: number;
}

export interface CmdFlashScreenMsg {
  type: 'CMD_FLASH_SCREEN';
  requestId: string;
  mode: 'text' | 'image';
  text?: string;
  caption?: string;
  durationSec: number;
  chunkIndex?: number;
  totalChunks?: number;
  data?: string;
  ts: number;
}

export interface CmdGetGalleryPhotoMsg {
  type: 'CMD_GET_GALLERY_PHOTO';
  requestId: string;
  mediaId: string;
  ts: number;
}

export interface DataGalleryPhotoMsg {
  type: 'DATA_GALLERY_PHOTO';
  requestId: string;
  mediaId: string;
  chunkIndex: number;
  totalChunks: number;
  data: string;
  error?: string;
  ts: number;
}

export interface CmdSetAiShieldMsg {
  type: 'CMD_SET_AI_SHIELD';
  requestId: string;
  blurFaces?: boolean;
  blurNsfw?: boolean;
  nsfwStrict?: boolean;
  ts: number;
}

export interface CmdSetRemotePolicyMsg {
  type: 'CMD_SET_REMOTE_POLICY';
  requestId: string;
  allowLocation?: boolean;
  allowLocationHistory?: boolean;
  gpsTrackingMode?: GpsTrackingMode;
  allowContacts?: boolean;
  allowSmsRelay?: boolean;
  allowGallery?: boolean;
  allowIntercom?: boolean;
  allowDeviceInfo?: boolean;
  ts: number;
}

export interface PairHelloMsg {
  type: 'PAIR_HELLO';
  deviceId: string;
  deviceName: string;
  ts: number;
}

export interface PairBindMsg {
  type: 'PAIR_BIND';
  familyKey: string;
  deviceCode: string;
  adminPin?: string;
  ts: number;
}

export interface PairConfirmedMsg {
  type: 'PAIR_CONFIRMED';
  deviceId: string;
  deviceName: string;
  deviceCode: string;
  ts: number;
}

export interface FamilyDiscoverMsg {
  type: 'FAMILY_DISCOVER';
  ts: number;
}

export interface FamilyAnnounceMsg {
  type: 'FAMILY_ANNOUNCE';
  deviceId: string;
  deviceName: string;
  deviceCode: string;
  batteryLevel: number;
  isCharging: boolean;
  portalState: string;
  pendingRequestsCount?: number;
  unpairRequestPending?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  lostModeEnabled?: boolean;
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

export interface CmdSetCurfewMsg {
  type: 'CMD_SET_CURFEW';
  requestId: string;
  enabled: boolean;
  startMin: number;
  endMin: number;
  daysMask: number;
  ts: number;
}

export interface CmdSetAppQuotaMsg {
  type: 'CMD_SET_APP_QUOTA';
  requestId: string;
  packageName: string;
  appName: string;
  dailyLimitMinutes: number;
  ts: number;
}

export interface CmdSetSchoolModeMsg {
  type: 'CMD_SET_SCHOOL_MODE';
  requestId: string;
  durationMinutes?: number;
  allowedPackages?: string[];
  ts: number;
}

export interface CmdSetAntiBypassMsg {
  type: 'CMD_SET_ANTI_BYPASS';
  requestId: string;
  enabled: boolean;
  ts: number;
}

export interface CmdSetLostModeMsg {
  type: 'CMD_SET_LOST_MODE';
  requestId: string;
  enabled: boolean;
  message?: string;
  phoneNumber?: string;
  ringNow?: boolean;
  ts: number;
}

export interface CmdRespondTimeRequestMsg {
  type: 'CMD_RESPOND_TIME_REQUEST';
  requestId: string;
  timeRequestId: string;
  approve: boolean;
  grantMinutes?: number;
  ts: number;
}

export interface CmdSaveGeofenceMsg {
  type: 'CMD_SAVE_GEOFENCE';
  requestId: string;
  id?: string;
  name?: string;
  latitude?: number;
  longitude?: number;
  radiusMeters?: number;
  delete?: boolean;
  ts: number;
}

export interface CmdClearSecurityEventsMsg {
  type: 'CMD_CLEAR_SECURITY_EVENTS';
  requestId: string;
  ts: number;
}

export interface CmdUnpairDecisionMsg {
  type: 'CMD_UNPAIR_DECISION';
  requestId: string;
  approve: boolean;
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

export interface CallLogEntry {
  id: string;
  number: string;
  contactName: string;
  type: 'incoming' | 'outgoing' | 'missed' | 'rejected' | 'blocked';
  date: number;
  durationSec: number;
  blocked: boolean;
}

export interface PhoneRuleEntry {
  number: string;
  label: string;
  addedAt: number;
}

export interface CmdGetCallLogMsg {
  type: 'CMD_GET_CALL_LOG';
  requestId: string;
  ts: number;
}

export interface DataCallLogMsg {
  type: 'DATA_CALL_LOG';
  requestId: string;
  calls: CallLogEntry[];
  blockedNumbers: PhoneRuleEntry[];
  allowedNumbers: PhoneRuleEntry[];
  blockUnknownCalls: boolean;
  callWhitelistOnly: boolean;
  hasCallLogPermission: boolean;
  hasCallScreeningRole: boolean;
  error?: string;
  ts: number;
}

export interface CmdCallBlockRuleMsg {
  type: 'CMD_CALL_BLOCK_RULE';
  requestId: string;
  action: 'add_block' | 'remove_block' | 'add_allow' | 'remove_allow' | 'set_flags';
  number?: string;
  label?: string;
  blockUnknownCalls?: boolean;
  callWhitelistOnly?: boolean;
  ts: number;
}

export interface OfflineRecordingItem {
  id: string;
  mode: 'audio' | 'video';
  mimeType: string;
  startedAt: number;
  durationSec: number;
  sizeBytes: number;
}

export interface CmdRecordingActionMsg {
  type: 'CMD_RECORDING_ACTION';
  requestId: string;
  action: 'list' | 'start' | 'stop' | 'delete' | 'get_file';
  mode?: 'audio' | 'video';
  durationMinutes?: number;
  facing?: CameraFacingMode;
  recordingId?: string;
  ts: number;
}

export interface DataRecordingsMsg {
  type: 'DATA_RECORDINGS';
  requestId: string;
  items: OfflineRecordingItem[];
  isRecording: boolean;
  activeMode: 'audio' | 'video';
  activeStartedAt: number;
  activeTargetEndAt: number;
  error?: string;
  ts: number;
}

export interface DataRecordingFileMsg {
  type: 'DATA_RECORDING_FILE';
  requestId: string;
  recordingId: string;
  mimeType: string;
  chunkIndex: number;
  totalChunks: number;
  data: string;
  error?: string;
  ts: number;
}

export interface InstalledAppInventoryItem {
  packageName: string;
  appName: string;
  versionName: string;
  installedAt: number;
  updatedAt: number;
  isSystem: boolean;
  sideloaded: boolean;
  hasLauncher: boolean;
}

export interface CmdSystemControlMsg {
  type: 'CMD_SYSTEM_CONTROL';
  requestId: string;
  action: 'get_status' | 'set_volume' | 'set_ringer' | 'set_brightness' | 'launch_app' | 'uninstall_app';
  stream?: 'music' | 'ring' | 'alarm';
  percent?: number;
  mode?: 'normal' | 'vibrate' | 'silent';
  packageName?: string;
  ts: number;
}

export interface DataSystemStatusMsg {
  type: 'DATA_SYSTEM_STATUS';
  requestId: string;
  volumeMusic: number;
  volumeRing: number;
  volumeAlarm: number;
  ringerMode: 'normal' | 'vibrate' | 'silent';
  brightnessPct: number;
  canWriteSettings: boolean;
  freeStorageMb: number;
  totalStorageMb: number;
  freeRamMb: number;
  totalRamMb: number;
  batteryTempC: number;
  wifiSummary: string;
  androidVersion: string;
  model: string;
  installedApps: InstalledAppInventoryItem[];
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
  | CmdSetCurfewMsg
  | CmdSetAppQuotaMsg
  | CmdSetSchoolModeMsg
  | CmdSetAntiBypassMsg
  | CmdSetLostModeMsg
  | CmdRespondTimeRequestMsg
  | CmdSaveGeofenceMsg
  | CmdClearSecurityEventsMsg
  | CmdGetGalleryMsg
  | DataGalleryMsg
  | CmdGetGalleryPhotoMsg
  | DataGalleryPhotoMsg
  | CmdGalleryActionMsg
  | DataQuarantineListMsg
  | CmdGetSmsThreadsMsg
  | DataSmsThreadsMsg
  | CmdGetSmsMessagesMsg
  | DataSmsMessagesMsg
  | CmdFlashScreenMsg
  | CmdGetCallLogMsg
  | DataCallLogMsg
  | CmdCallBlockRuleMsg
  | CmdRecordingActionMsg
  | DataRecordingsMsg
  | DataRecordingFileMsg
  | CmdSystemControlMsg
  | DataSystemStatusMsg
  | CmdSetAiShieldMsg
  | CmdSetRemotePolicyMsg
  | CmdUnpairDecisionMsg
  | PairHelloMsg
  | PairBindMsg
  | PairConfirmedMsg
  | FamilyDiscoverMsg
  | FamilyAnnounceMsg
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
  readSms?: boolean;
  callLog?: boolean;
  callScreening?: boolean;
  writeSettings?: boolean;
  gallery?: boolean;
  storageManage?: boolean;
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
  deviceManufacturer?: string;
  manufacturerTip?: string;
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
  curfewEnabled?: boolean;
  lostModeEnabled?: boolean;
  blockSettingsAndStore?: boolean;
  schoolModeUntilMs?: number;
  securityAlertsCount?: number;
  aiBlurFaces?: boolean;
  aiBlurNsfw?: boolean;
  aiNsfwStrict?: boolean;
  aiShieldRunning?: boolean;
  parentManaged?: boolean;
  unpairRequestPending?: boolean;
  unpairRequestedAt?: number;
  pairStatus?: 'idle' | 'pairing' | 'paired' | 'timeout' | 'error';
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
