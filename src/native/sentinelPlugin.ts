import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type {
  AccessProfileInput,
  GpsTrackingMode,
  NativePermissionStatus,
  NativePortalState,
} from '../types/sentinel';

export interface SentinelNativePluginContract {
  getPortalState(): Promise<NativePortalState>;
  requestAllPermissions(): Promise<NativePermissionStatus>;
  requestBatteryExemption(): Promise<{ batteryOptimizationIgnored: boolean }>;
  requestContactsPermission(): Promise<NativePortalState>;
  requestLocationPermission(): Promise<NativePortalState>;
  requestBackgroundLocationPermission(): Promise<NativePortalState>;
  requestSmsPermission(): Promise<NativePortalState>;
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
  }): Promise<NativePortalState>;
  completeOnboardingAndStart(options: { autoStartOnBoot: boolean }): Promise<NativePortalState>;
  startPortalService(): Promise<NativePortalState>;
  stopPortalService(): Promise<NativePortalState>;
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
