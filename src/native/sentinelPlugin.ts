import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type {
  AccessProfileInput,
  DataScreenTimeMsg,
  GpsTrackingMode,
  NativePermissionStatus,
  NativePortalState,
} from '../types/sentinel';

export interface SentinelNativePluginContract {
  getPortalState(): Promise<NativePortalState>;
  requestAllPermissions(): Promise<NativePermissionStatus>;
  requestBatteryExemption(): Promise<{ batteryOptimizationIgnored: boolean }>;
  openManufacturerBatterySettings(): Promise<NativePortalState>;
  requestContactsPermission(): Promise<NativePortalState>;
  requestLocationPermission(): Promise<NativePortalState>;
  requestBackgroundLocationPermission(): Promise<NativePortalState>;
  requestSmsPermission(): Promise<NativePortalState>;
  requestCallLogPermission(): Promise<NativePortalState>;
  requestCallScreeningRole(): Promise<NativePortalState>;
  requestNotificationListenerPermission(): Promise<NativePortalState>;
  requestWriteSettingsPermission(): Promise<NativePortalState>;
  openAppPermissionsSettings(): Promise<NativePortalState>;
  requestGalleryPermission(): Promise<NativePortalState>;
  requestStorageManagePermission(): Promise<NativePortalState>;
  requestUsageAccessPermission(): Promise<NativePortalState>;
  requestOverlayPermission(): Promise<NativePortalState>;
  requestAccessibilityPermission(): Promise<NativePortalState>;
  requestDeviceAdmin(): Promise<NativePortalState>;
  pairWithParentCode(options: { code: string }): Promise<NativePortalState>;
  requestParentUnpair(): Promise<NativePortalState>;
  unpairFromParent(options: { pin: string }): Promise<NativePortalState>;
  emergencyLocalExit(options: { code: string }): Promise<NativePortalState>;
  getLocalDashboardData(): Promise<DataScreenTimeMsg>;
  submitChildChangeRequest(options: {
    targetKey: string;
    label: string;
    requestedMinutes?: number;
  }): Promise<NativePortalState>;
  configureParentPhoneNotifications(options: {
    childCode?: string;
    familyKey?: string;
    clear?: boolean;
    test?: boolean;
  }): Promise<NativePortalState>;
  setAiShieldSettings(options: {
    blurFaces?: boolean;
    blurNsfw?: boolean;
    nsfwStrict?: boolean;
  }): Promise<NativePortalState>;
  clearLocationHistory(): Promise<NativePortalState>;
  requestScreenSharePermission(): Promise<NativePortalState>;
  revokeScreenSharePermission(): Promise<NativePortalState>;
  setGlobalShareSettings(options: {
    allowScreenShare?: boolean;
    allowContacts?: boolean;
    allowLocation?: boolean;
    allowLocationHistory?: boolean;
    gpsTrackingMode?: GpsTrackingMode;
    allowDeviceInfo?: boolean;
    allowIntercom?: boolean;
    allowSmsRelay?: boolean;
    allowDeviceControl?: boolean;
    allowGallery?: boolean;
  }): Promise<NativePortalState>;
  completeOnboardingAndStart(options: { autoStartOnBoot: boolean }): Promise<NativePortalState>;
  startPortalService(): Promise<NativePortalState>;
  stopPortalService(options?: { adminPin?: string }): Promise<NativePortalState>;
  enableAdminProtection(options: { pin: string }): Promise<NativePortalState>;
  verifyAdminPin(options: { pin: string }): Promise<{ valid: boolean }>;
  disableAdminProtection(options: { pin: string }): Promise<NativePortalState>;
  updateAdminPin(options: { currentPin: string; newPin: string }): Promise<NativePortalState>;
  regeneratePairingCode(): Promise<NativePortalState>;
  resetPairedController(): Promise<NativePortalState>;
  setAutoStartOnBoot(options: { enabled: boolean }): Promise<NativePortalState>;
  upsertAccessProfile(options: AccessProfileInput): Promise<NativePortalState>;
  toggleAccessProfile(options: { id: string; enabled: boolean }): Promise<NativePortalState>;
  deleteAccessProfile(options: { id: string }): Promise<NativePortalState>;
  kickActiveViewer(): Promise<NativePortalState>;
  addListener(
    eventName: 'portalStateChanged',
    listenerFunc: (state: NativePortalState) => void
  ): Promise<PluginListenerHandle>;
}

export const SentinelNative = registerPlugin<SentinelNativePluginContract>('SentinelNative');

export function isAndroidNativeApp(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}
