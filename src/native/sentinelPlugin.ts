import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type { NativePermissionStatus, NativePortalState } from '../types/sentinel';

export interface SentinelNativePluginContract {
  getPortalState(): Promise<NativePortalState>;
  requestAllPermissions(): Promise<NativePermissionStatus>;
  requestBatteryExemption(): Promise<{ batteryOptimizationIgnored: boolean }>;
  completeOnboardingAndStart(options: { autoStartOnBoot: boolean }): Promise<NativePortalState>;
  startPortalService(): Promise<NativePortalState>;
  stopPortalService(): Promise<NativePortalState>;
  regeneratePairingCode(): Promise<NativePortalState>;
  resetPairedController(): Promise<NativePortalState>;
  setAutoStartOnBoot(options: { enabled: boolean }): Promise<NativePortalState>;
  addListener(
    eventName: 'portalStateChanged',
    listenerFunc: (state: NativePortalState) => void
  ): Promise<PluginListenerHandle>;
}

export const SentinelNative = registerPlugin<SentinelNativePluginContract>('SentinelNative');

export function isAndroidNativeApp(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}
