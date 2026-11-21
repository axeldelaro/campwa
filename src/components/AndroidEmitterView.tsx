import React, { useEffect, useRef, useState } from 'react';
import {
  BatteryCharging,
  BellRing,
  BookUser,
  Camera,
  CheckCircle2,
  Clock,
  Image as ImageIcon,
  LayoutDashboard,
  Lock,
  MapPin,
  MessageSquare,
  Phone,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Sun,
  Trash2,
  Wifi,
} from 'lucide-react';
import { normalizeFamilyKey, normalizePairingCode } from '../crypto/cryptoEngine';
import { SentinelNative } from '../native/sentinelPlugin';
import { GeofenceZonesCard, ParentalHomeTab, ParentalRulesTab } from './ControlPanelTab';
import { DrivingScoreCard } from './DrivingScoreCard';
import { SystemCommandCard } from './SystemCommandCard';
import type {
  AppGroupQuotaItem,
  AppUsageEntry,
  DataScreenTimeMsg,
  DeviceTelemetry,
  HourlyTimelineApp,
  HourlyTimelineSlot,
  NativePortalState,
} from '../types/sentinel';

interface AndroidEmitterViewProps {
  isNativeAndroid: boolean;
}

function toArray<T>(val: unknown): T[] {
  if (Array.isArray(val)) return val as T[];
  if (val && typeof val === 'object') return Object.values(val) as T[];
  return [];
}

function sanitizeScreenTime(raw: DataScreenTimeMsg | null | undefined): DataScreenTimeMsg | null {
  if (!raw || typeof raw !== 'object') return null;
  return {
    ...raw,
    apps: toArray<AppUsageEntry>(raw.apps),
    groupQuotas: toArray<AppGroupQuotaItem>(raw.groupQuotas).map((g) => ({
      ...g,
      packages: toArray<string>(g?.packages),
    })),
    weeklyHistory: toArray(raw.weeklyHistory),
    hourlyTimeline: toArray<HourlyTimelineSlot>(raw.hourlyTimeline).map((s) => ({
      ...s,
      topApps: toArray<HourlyTimelineApp>(s?.topApps),
    })),
    pendingRequests: toArray(raw.pendingRequests),
    geofences: toArray(raw.geofences),
    securityEvents: toArray(raw.securityEvents),
    systemStatus: raw.systemStatus
      ? {
          ...raw.systemStatus,
          installedApps: toArray(raw.systemStatus.installedApps),
        }
      : undefined,
    drivingReport: raw.drivingReport
      ? {
          ...raw.drivingReport,
          events: toArray(raw.drivingReport.events),
        }
      : undefined,
  };
}

class ChildSectionBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): { hasError: boolean } {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="rounded-2xl border border-amber-500/30 bg-zinc-900/80 p-4 text-center text-xs text-zinc-300">
          <p>Affichage temporairement indisponible pour cette section.</p>
          <button
            type="button"
            onClick={() => this.setState({ hasError: false })}
            className="mt-2 rounded-xl bg-white px-3 py-1.5 font-semibold text-zinc-950"
          >
            Réessayer
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

/**
 * Pure, Tamper-Proof Child Device App (v17):
 * - Device Admin (anti-uninstall & screen lock) is requested right at installation
 *   and locked ON by default upon pairing.
 * - ZERO feature toggles accessible on the child device.
 * - Child CANNOT unlink locally: unpairing strictly requires explicit Parent approval
 *   from the Parent Website (CMD_UNPAIR_DECISION).
 */
export const AndroidEmitterView: React.FC<AndroidEmitterViewProps> = ({ isNativeAndroid }) => {
  const [portal, setPortal] = useState<NativePortalState | null>(null);
  const [busy, setBusy] = useState(false);
  const [nowMs, setNowMs] = useState(() => Date.now());

  const [pairCode, setPairCode] = useState('');
  const [pairMessage, setPairMessage] = useState<string | null>(null);
  const [confirmUnpairAsk, setConfirmUnpairAsk] = useState(false);

  // Child Read-Only Mirror Dashboard State (reuses Parent tabs; any edit sends request to Parent)
  const [childTab, setChildTab] = useState<'home' | 'rules' | 'device'>('home');
  const [localScreenTime, setLocalScreenTime] = useState<DataScreenTimeMsg | null>(null);
  const [localLoading, setLocalLoading] = useState(false);
  const [childFeedback, setChildFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const lastLocalFetchMsRef = useRef<number>(0);

  // Parent Phone Native Notifications Subscriber State (for unassociated Parent phone)
  const [parentPushInput, setParentPushInput] = useState('');
  const [parentPushFeedback, setParentPushFeedback] = useState<string | null>(null);

  // Local Emergency Exit / Cheat Code (7-tap Shield icon -> removes MDM Device Owner & unpairs offline)
  const [cheatModalOpen, setCheatModalOpen] = useState(false);
  const [cheatCodeInput, setCheatCodeInput] = useState('');
  const [cheatError, setCheatError] = useState<string | null>(null);
  const cheatTapCountRef = useRef(0);
  const cheatLastTapMsRef = useRef(0);

  const handleSecretShieldTap = () => {
    const now = Date.now();
    if (now - cheatLastTapMsRef.current > 2200) {
      cheatTapCountRef.current = 0;
    }
    cheatLastTapMsRef.current = now;
    cheatTapCountRef.current += 1;
    if (cheatTapCountRef.current >= 7) {
      cheatTapCountRef.current = 0;
      setCheatCodeInput('');
      setCheatError(null);
      setCheatModalOpen(true);
    }
  };

  const handleSubmitCheatExit = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = cheatCodeInput.trim();
    if (!clean) return;
    setBusy(true);
    setCheatError(null);
    try {
      const next = await SentinelNative.emergencyLocalExit({ code: clean });
      setPortal(next);
      setCheatModalOpen(false);
      setCheatCodeInput('');
      setPairMessage('Mode MDM et supervision retirés. Vous pouvez désinstaller l’application librement.');
    } catch (err) {
      setCheatError(err instanceof Error ? err.message : 'Code invalide.');
    } finally {
      setBusy(false);
    }
  };

  const refreshLocalDashboard = async (force = false) => {
    if (!isNativeAndroid) return;
    const now = Date.now();
    if (!force && now - lastLocalFetchMsRef.current < 5_000) return;
    lastLocalFetchMsRef.current = now;
    setLocalLoading(true);
    try {
      const data = await SentinelNative.getLocalDashboardData();
      setLocalScreenTime(sanitizeScreenTime(data));
    } catch {
      // Ignore transient read errors
    } finally {
      setLocalLoading(false);
    }
  };

  useEffect(() => {
    if (isNativeAndroid && portal?.parentManaged) {
      void refreshLocalDashboard(true);
    }
  }, [isNativeAndroid, portal?.parentManaged, childTab]);

  useEffect(() => {
    const t = window.setInterval(() => {
      if (!document.hidden) setNowMs(Date.now());
    }, 10_000);
    return () => window.clearInterval(t);
  }, []);

  useEffect(() => {
    if (!isNativeAndroid) return;
    let listenerHandle: { remove: () => Promise<void> } | null = null;

    const init = async () => {
      try {
        const initial = await SentinelNative.getPortalState();
        setPortal(initial);
        if (initial.parentManaged) {
          void refreshLocalDashboard(true);
        }
        listenerHandle = await SentinelNative.addListener('portalStateChanged', (next) => {
          setPortal(next);
          if (next.parentManaged && !document.hidden) {
            void refreshLocalDashboard(false);
          }
        });
      } catch (err) {
        console.error('Native bridge init error:', err);
      }
    };

    void init();

    const handleVisibility = () => {
      if (!document.hidden) {
        void SentinelNative.getPortalState().then((latest) => {
          setPortal(latest);
          if (latest.parentManaged) void refreshLocalDashboard(true);
        }).catch(() => {});
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    // Eco WebView poll: only while app is visible on screen (0% background WebView drain!)
    const poll = window.setInterval(async () => {
      if (document.hidden) return;
      try {
        const latest = await SentinelNative.getPortalState();
        setPortal(latest);
      } catch {
        // Ignore transient poll errors
      }
    }, 12_000);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibility);
      window.clearInterval(poll);
      if (listenerHandle) void listenerHandle.remove();
    };
  }, [isNativeAndroid]);

  if (!isNativeAndroid) {
    return (
      <div className="mx-auto flex min-h-[70vh] max-w-md flex-col justify-center px-4 py-10 text-center">
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-6">
          <Smartphone className="mx-auto h-8 w-8 text-emerald-400" />
          <h1 className="mt-3 text-base font-semibold text-white">Application Android requise</h1>
          <p className="mt-1.5 text-xs leading-relaxed text-zinc-400">
            Installez l’APK SentinelCam sur le téléphone à protéger, puis associez-le avec le code
            généré dans votre Espace Parent.
          </p>
          <a
            href="https://github.com/axeldelaro/campwa/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            className="mt-5 inline-flex w-full items-center justify-center rounded-xl bg-white px-4 py-2.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
          >
            Télécharger l’application Android
          </a>
        </div>
      </div>
    );
  }

  if (!portal) {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <RefreshCw className="h-6 w-6 animate-spin text-zinc-400" />
      </div>
    );
  }

  const runAction = async (fn: () => Promise<NativePortalState | void>) => {
    setBusy(true);
    try {
      const res = await fn();
      if (res && typeof res === 'object' && 'permissions' in res && 'globalShares' in res) {
        setPortal(res);
      } else {
        setPortal(await SentinelNative.getPortalState());
      }
    } finally {
      setBusy(false);
    }
  };

  const handleRequestDeviceAdmin = async () => {
    await runAction(() => SentinelNative.requestDeviceAdmin());
  };

  const handlePair = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = normalizePairingCode(pairCode);
    if (clean.length !== 6) {
      setPairMessage('Saisissez les 6 caractères affichés sur le site Parent.');
      return;
    }
    if (!portal.deviceAdminActive) {
      setPairMessage('Activez d’abord les droits administrateur Android (étape 1).');
      await handleRequestDeviceAdmin();
      return;
    }
    setPairMessage('Association avec l’Espace Parent en cours…');
    setBusy(true);
    try {
      const next = await SentinelNative.pairWithParentCode({ code: clean });
      setPortal(next);
      setPairCode('');
    } catch (err) {
      setPairMessage(err instanceof Error ? err.message : 'Association impossible.');
    } finally {
      setBusy(false);
    }
  };

  const handleRequestParentUnpair = async () => {
    setConfirmUnpairAsk(false);
    await runAction(() => SentinelNative.requestParentUnpair());
  };

  const p = portal.permissions;
  const hasAutonomousScreenProtection = Boolean(p.accessibilityService);
  const aiNeedsScreenCapture = Boolean((portal.aiBlurFaces || portal.aiBlurNsfw) && !portal.aiShieldRunning);
  const timelineNeedsScreenCapture = Boolean(portal.timelineCaptureEnabled && !portal.timelineRunning);
  const needsDeviceAdmin = !portal.deviceAdminActive;

  const missingOsSteps: Array<{
    key: string;
    label: string;
    highlight?: boolean;
    icon: React.FC<{ className?: string }>;
    action: () => Promise<void>;
  }> = [];

  if (!hasAutonomousScreenProtection) {
    missingOsSteps.push({
      key: 'a11y-autonomous',
      label: 'Protection écran autonome (Flou IA & Captures sans popup)',
      highlight: true,
      icon: Sparkles,
      action: () => runAction(() => SentinelNative.requestAccessibilityPermission()),
    });
  } else if (aiNeedsScreenCapture || timelineNeedsScreenCapture) {
    missingOsSteps.push({
      key: 'ai-screen',
      label:
        aiNeedsScreenCapture && timelineNeedsScreenCapture
          ? 'Autoriser capture écran (Filtre IA & Captures auto)'
          : timelineNeedsScreenCapture
            ? 'Autoriser capture écran (Captures auto)'
            : 'Capture écran pour le Filtre IA',
      highlight: true,
      icon: Sparkles,
      action: () => runAction(() => SentinelNative.requestScreenSharePermission()),
    });
  }
  if (!p.usageAccess) {
    missingOsSteps.push({
      key: 'usage',
      label: 'Temps d’écran & blocage d’applis',
      icon: Lock,
      action: () => runAction(() => SentinelNative.requestUsageAccessPermission()),
    });
  }
  if (!p.overlay) {
    missingOsSteps.push({
      key: 'overlay',
      label: 'Écran de blocage & Filtre IA',
      icon: ShieldCheck,
      action: () => runAction(() => SentinelNative.requestOverlayPermission()),
    });
  }
  if (!p.batteryOptimizationIgnored) {
    missingOsSteps.push({
      key: 'battery',
      label: 'Arrière-plan illimité',
      icon: BatteryCharging,
      action: () => runAction(async () => { await SentinelNative.requestBatteryExemption(); }),
    });
  }
  if (!p.location) {
    missingOsSteps.push({
      key: 'location',
      label: 'Localisation GPS',
      icon: MapPin,
      action: () => runAction(() => SentinelNative.requestLocationPermission()),
    });
  } else if (p.backgroundLocation === false) {
    missingOsSteps.push({
      key: 'bg-location',
      label: 'GPS en arrière-plan (« Toujours »)',
      icon: MapPin,
      action: () => runAction(() => SentinelNative.requestBackgroundLocationPermission()),
    });
  }
  if (!p.gallery) {
    missingOsSteps.push({
      key: 'gallery',
      label: 'Galerie photos',
      icon: ImageIcon,
      action: () => runAction(() => SentinelNative.requestGalleryPermission()),
    });
  }
  if (!p.storageManage) {
    missingOsSteps.push({
      key: 'storage-manage',
      label: 'Gestion photos (quarantaine & suppression)',
      icon: Lock,
      action: () => runAction(() => SentinelNative.requestStorageManagePermission()),
    });
  }
  if (!p.camera || !p.microphone) {
    missingOsSteps.push({
      key: 'cam',
      label: 'Caméra, micro & notifications',
      icon: Camera,
      action: () =>
        runAction(async () => {
          await SentinelNative.requestAllPermissions();
          return SentinelNative.completeOnboardingAndStart({ autoStartOnBoot: true });
        }),
    });
  }
  if (!p.contacts) {
    missingOsSteps.push({
      key: 'contacts',
      label: 'Contacts',
      icon: BookUser,
      action: () => runAction(() => SentinelNative.requestContactsPermission()),
    });
  }
  if (!p.sms || !p.readSms) {
    missingOsSteps.push({
      key: 'sms',
      label: 'Messages SMS (lecture & réponse)',
      icon: MessageSquare,
      action: () => runAction(() => SentinelNative.requestSmsPermission()),
    });
  }
  if (!p.sms || !p.readSms || !hasAutonomousScreenProtection) {
    missingOsSteps.push({
      key: 'restricted-settings',
      label: 'Paramètres appli (si grisé sur Android 13+ : ⋮ → Paramètres restreints)',
      icon: ShieldAlert,
      action: () => runAction(() => SentinelNative.openAppPermissionsSettings()),
    });
  }
  if (!p.callLog) {
    missingOsSteps.push({
      key: 'call-log',
      label: 'Journal d’appels',
      icon: Phone,
      action: () => runAction(() => SentinelNative.requestCallLogPermission()),
    });
  }
  if (!p.callScreening) {
    missingOsSteps.push({
      key: 'call-screening',
      label: 'Filtre d’appels bloqués',
      icon: ShieldAlert,
      action: () => runAction(() => SentinelNative.requestCallScreeningRole()),
    });
  }
  if (!p.notificationListener) {
    missingOsSteps.push({
      key: 'notif-listener',
      label: 'Messages réseaux sociaux (accès notifications)',
      icon: MessageSquare,
      action: () => runAction(() => SentinelNative.requestNotificationListenerPermission()),
    });
  }
  if (!p.writeSettings) {
    missingOsSteps.push({
      key: 'write-settings',
      label: 'Luminosité & paramètres système',
      icon: Sun,
      action: () => runAction(() => SentinelNative.requestWriteSettingsPermission()),
    });
  }

  /* ==================== MODE A: INSTALLATION & ASSOCIATION ==================== */
  if (!portal.parentManaged) {
    return (
      <div className="mx-auto max-w-md space-y-4 px-4 py-6">
        {/* Step 1: Mandatory Android Device Admin at Install */}
        <div
          className={`rounded-2xl border p-4 ${
            portal.deviceAdminActive
              ? 'border-emerald-500/25 bg-emerald-500/[0.06]'
              : 'border-amber-500/40 bg-amber-500/[0.08]'
          }`}
        >
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <button
                type="button"
                onClick={handleSecretShieldTap}
                className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl active:scale-95 ${
                  portal.deviceAdminActive ? 'bg-emerald-500/20 text-emerald-300' : 'bg-amber-500/20 text-amber-300'
                }`}
              >
                {portal.deviceAdminActive ? (
                  <CheckCircle2 className="h-5 w-5" />
                ) : (
                  <ShieldAlert className="h-5 w-5" />
                )}
              </button>
              <div>
                <div className="text-xs font-semibold text-white">
                  1. Droits administrateur Android
                </div>
                <div className="text-[11px] text-zinc-400">
                  {portal.deviceAdminActive
                    ? 'Verrouillage anti-désinstallation actif'
                    : 'Requis pour verrouiller la désinstallation par défaut'}
                </div>
              </div>
            </div>
            {!portal.deviceAdminActive && (
              <button
                type="button"
                disabled={busy}
                onClick={() => void handleRequestDeviceAdmin()}
                className="shrink-0 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-40"
              >
                Activer
              </button>
            )}
          </div>
        </div>

        {/* Step 2: Bind to Parent Website */}
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-5">
          <div className="text-xs font-semibold text-white">2. Associer à l’Espace Parent</div>
          <p className="mt-1 text-[11px] text-zinc-400">
            Saisissez le code à 6 caractères affiché sur le site Parent. Toutes les protections seront
            verrouillées par défaut.
          </p>

          <form onSubmit={(e) => void handlePair(e)} className="mt-4 space-y-3">
            <input
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoComplete="off"
              maxLength={6}
              value={pairCode}
              onChange={(e) => setPairCode(normalizePairingCode(e.target.value))}
              placeholder="CODE PARENT"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 py-3 text-center font-mono text-lg font-semibold uppercase tracking-[0.28em] text-white placeholder-zinc-600 focus:border-emerald-400 focus:outline-none"
            />
            <button
              type="submit"
              disabled={busy || pairCode.length !== 6}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-white py-3 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
            >
              {portal.pairStatus === 'pairing' ? (
                <>
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                  <span>Association en cours…</span>
                </>
              ) : (
                <span>Associer cet appareil</span>
              )}
            </button>
          </form>

          {(pairMessage || portal.pairStatus === 'timeout') && (
            <div className="mt-3 text-center text-xs text-emerald-300">
              {portal.pairStatus === 'timeout'
                ? 'Temps écoulé — vérifiez le code affiché sur le site Parent.'
                : pairMessage}
            </div>
          )}
        </div>

        {/* Step 3: One-Time Android OS Permissions */}
        {missingOsSteps.length > 0 && (
          <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-4">
            <div className="text-xs font-semibold text-white">
              3. Autorisations système ({missingOsSteps.length})
            </div>
            <div className="mt-2.5 space-y-1.5">
              {missingOsSteps.map((step) => {
                const Icon = step.icon;
                return (
                  <button
                    key={step.key}
                    type="button"
                    disabled={busy}
                    onClick={() => void step.action()}
                    className="flex w-full items-center justify-between gap-2 rounded-xl border border-white/[0.07] bg-zinc-950 px-3 py-2.5 text-left text-xs text-zinc-200 transition hover:border-white/20"
                  >
                    <span className="inline-flex items-center gap-2">
                      <Icon className="h-3.5 w-3.5 text-zinc-400" />
                      <span>{step.label}</span>
                    </span>
                    <span className="shrink-0 font-medium text-emerald-400">Autoriser</span>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Brand-Smart Manufacturer Anti-Sleep Assistant */}
        <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/60 p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="text-xs font-semibold text-white">
                Assistant anti-veille ({portal.deviceManufacturer || 'Android'})
              </div>
              <p className="mt-1 text-[11px] leading-relaxed text-zinc-400">
                {portal.manufacturerTip ||
                  'Réglez la batterie sur « Non restreinte » pour empêcher la coupure en arrière-plan.'}
              </p>
            </div>
            <button
              type="button"
              disabled={busy}
              onClick={() => void runAction(() => SentinelNative.openManufacturerBatterySettings())}
              className="shrink-0 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-[11px] font-medium text-emerald-300 hover:border-white/25"
            >
              Régler
            </button>
          </div>
        </div>

        {/* Parent Phone Mode: Receive Native Push Alerts on Parent's Smartphone Without Going to the Website */}
        <div className="space-y-3 rounded-2xl border border-sky-500/25 bg-zinc-900/75 p-4">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <BellRing className="h-4 w-4 text-sky-400" />
              <div>
                <div className="text-xs font-semibold text-white">
                  Téléphone Parent : Notifications sans ouvrir le site
                </div>
                <div className="text-[11px] text-zinc-400">
                  {(portal.parentSubscribedTopicsCount ?? 0) > 0
                    ? `${portal.parentSubscribedTopicsCount} canal(aux) actif(s) en arrière-plan`
                    : 'Recevez ici les demandes (+15 min) et alertes de votre enfant 24h/24'}
                </div>
              </div>
            </div>
            {(portal.parentSubscribedTopicsCount ?? 0) > 0 && (
              <button
                type="button"
                disabled={busy}
                onClick={async () => {
                  await runAction(() => SentinelNative.configureParentPhoneNotifications({ clear: true }));
                  setParentPushFeedback('Notifications Parent désactivées sur ce téléphone.');
                }}
                className="rounded-lg border border-white/10 bg-zinc-950 p-1.5 text-zinc-400 hover:text-rose-300"
                title="Désactiver"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          <div className="flex gap-2">
            <input
              type="text"
              value={parentPushInput}
              onChange={(e) => setParentPushInput(e.target.value.toUpperCase())}
              maxLength={14}
              placeholder="Code enfant (6 car.) ou Clé famille (12 car.)"
              className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 font-mono text-xs uppercase text-white placeholder-zinc-500 focus:border-sky-400 focus:outline-none"
            />
            <button
              type="button"
              disabled={busy}
              onClick={async () => {
                const clean6 = normalizePairingCode(parentPushInput);
                const clean12 = normalizeFamilyKey(parentPushInput);
                if (clean12.length === 12) {
                  await runAction(() =>
                    SentinelNative.configureParentPhoneNotifications({ familyKey: clean12, test: true })
                  );
                  setParentPushInput('');
                  setParentPushFeedback('Notifications Famille activées (test envoyé) !');
                } else if (clean6.length === 6) {
                  await runAction(() =>
                    SentinelNative.configureParentPhoneNotifications({ childCode: clean6, test: true })
                  );
                  setParentPushInput('');
                  setParentPushFeedback(`Notifications activées pour l’appareil ${clean6} (test envoyé) !`);
                } else if ((portal.parentSubscribedTopicsCount ?? 0) > 0) {
                  await runAction(() => SentinelNative.configureParentPhoneNotifications({ test: true }));
                  setParentPushFeedback('Notification de test envoyée !');
                } else {
                  setParentPushFeedback('Saisissez le code à 6 caractères de l’enfant.');
                }
              }}
              className="shrink-0 rounded-xl bg-sky-400 px-3.5 py-2 text-xs font-semibold text-zinc-950 hover:bg-sky-300 disabled:opacity-40"
            >
              Activer & Tester
            </button>
          </div>

          {parentPushFeedback && (
            <div className="text-[11px] text-sky-300">{parentPushFeedback}</div>
          )}
        </div>

        {cheatModalOpen && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 px-4 backdrop-blur-sm">
            <form
              onSubmit={(e) => void handleSubmitCheatExit(e)}
              className="w-full max-w-sm space-y-3 rounded-2xl border border-rose-500/40 bg-zinc-900 p-5 shadow-2xl"
            >
              <div className="flex items-center gap-2 text-sm font-semibold text-white">
                <Lock className="h-4 w-4 text-rose-400" />
                <span>Retrait d’urgence (MDM & Supervision)</span>
              </div>
              <p className="text-xs leading-relaxed text-zinc-400">
                Saisissez le code de sortie d’urgence pour retirer immédiatement le mode MDM et rendre
                SentinelCam désinstallable hors-ligne.
              </p>
              <input
                type="password"
                autoFocus
                maxLength={16}
                value={cheatCodeInput}
                onChange={(e) => setCheatCodeInput(e.target.value)}
                placeholder="Code de sortie…"
                className="w-full rounded-xl border border-white/15 bg-zinc-950 px-3.5 py-2.5 font-mono text-sm tracking-widest text-white placeholder-zinc-500 focus:border-rose-400 focus:outline-none"
              />
              {cheatError && (
                <div className="rounded-lg bg-rose-500/15 px-3 py-1.5 text-xs text-rose-200">
                  {cheatError}
                </div>
              )}
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setCheatModalOpen(false)}
                  className="flex-1 rounded-xl border border-white/10 bg-zinc-950 py-2.5 text-xs font-medium text-zinc-300"
                >
                  Annuler
                </button>
                <button
                  type="submit"
                  disabled={busy || cheatCodeInput.trim().length < 4}
                  className="flex-1 rounded-xl bg-rose-500 py-2.5 text-xs font-semibold text-white hover:bg-rose-400 disabled:opacity-40"
                >
                  Tout retirer
                </button>
              </div>
            </form>
          </div>
        )}
      </div>
    );
  }

  /* ==================== MODE B: PARENT-MANAGED PROTECTED DEVICE (READ-ONLY MIRROR) ==================== */
  const sendChangeRequest = async (targetKey: string, label: string, requestedMinutes = 15) => {
    try {
      const next = await SentinelNative.submitChildChangeRequest({
        targetKey,
        label,
        requestedMinutes,
      });
      setPortal(next);
      setChildFeedback({
        ok: true,
        text: `Modification verrouillée — demande envoyée au Parent : ${label}`,
      });
      void refreshLocalDashboard();
    } catch {
      setChildFeedback({ ok: false, text: 'Impossible d’envoyer la demande au Parent.' });
    }
  };

  const mirrorTelemetry: DeviceTelemetry = {
    deviceId: portal.deviceId,
    deviceName: portal.deviceName,
    portalState: portal.portalState === 'stopped' ? 'standby' : portal.portalState,
    paired: portal.paired,
    pairedTokenId: portal.pairedTokenId,
    lockoutRemainingMs: portal.lockoutRemainingMs,
    failedAttempts: portal.failedAttempts,
    batteryLevel: portal.batteryLevel,
    isCharging: portal.isCharging,
    networkType: portal.mqttConnected ? 'Connecté' : 'Hors ligne',
    cameraFacing: portal.cameraFacing,
    mediaMode: portal.mediaMode,
    micEnabled: portal.micEnabled,
    uptimeSec: 0,
    allowLocation: portal.globalShares.allowLocation,
    allowGallery: portal.globalShares.allowGallery,
    allowContacts: portal.globalShares.allowContacts,
    allowSmsRelay: portal.globalShares.allowSmsRelay,
    allowIntercom: portal.globalShares.allowIntercom,
    allowDeviceInfo: portal.globalShares.allowDeviceInfo,
    activeAppLocksCount: portal.activeAppLocksCount,
    screenPausedUntilMs: portal.screenPausedUntilMs,
    adminProtectionEnabled: portal.adminProtectionEnabled,
    hasAdminPin: portal.hasAdminPin,
    deviceAdminActive: portal.deviceAdminActive,
    lostModeEnabled: portal.lostModeEnabled,
    curfewEnabled: portal.curfewEnabled,
    schoolModeUntilMs: portal.schoolModeUntilMs,
    blockSettingsAndStore: portal.blockSettingsAndStore,
    securityAlertsCount: portal.securityAlertsCount,
    aiBlurFaces: portal.aiBlurFaces,
    aiBlurNsfw: portal.aiBlurNsfw,
    aiNsfwStrict: portal.aiNsfwStrict,
    aiShieldRunning: portal.aiShieldRunning,
    aiShieldAutonomous: portal.aiShieldAutonomous,
    timelineCaptureEnabled: portal.timelineCaptureEnabled,
    timelineRunning: portal.timelineRunning,
    parentManaged: portal.parentManaged,
    unpairRequestPending: portal.unpairRequestPending,
    unpairRequestedAt: portal.unpairRequestedAt,
    permissionsHealth: {
      camera: p.camera,
      microphone: p.microphone,
      location: p.location,
      backgroundLocation: Boolean(p.backgroundLocation),
      contacts: p.contacts,
      readSms: Boolean(p.readSms),
      sendSms: Boolean(p.sms),
      callLog: p.callLog,
      callScreening: p.callScreening,
      notificationListener: p.notificationListener,
      writeSettings: p.writeSettings,
      gallery: Boolean(p.gallery),
      storageManage: Boolean(p.storageManage),
      usageAccess: Boolean(p.usageAccess),
      overlay: Boolean(p.overlay),
      accessibilityService: p.accessibilityService,
      batteryUnrestricted: p.batteryOptimizationIgnored,
      deviceAdmin: Boolean(portal.deviceAdminActive),
      manufacturer: portal.deviceManufacturer,
    },
    updatedAt: nowMs,
  };

  // Instant non-null fallback ScreenTime report from portal state (NO hook after early return!)
  const effectiveScreenTime: DataScreenTimeMsg = localScreenTime ?? {
    type: 'DATA_SCREEN_TIME',
    requestId: 'instant-fallback',
    hasUsageAccess: Boolean(p?.usageAccess),
    hasOverlayPermission: Boolean(p?.overlay),
    deviceAdminActive: Boolean(portal.deviceAdminActive),
    totalScreenTimeSec: 0,
    screenPausedUntilMs: portal.screenPausedUntilMs ?? 0,
    apps: [],
    groupQuotas: [],
    weeklyHistory: [],
    curfew: {
      enabled: Boolean(portal.curfewEnabled),
      startMin: 21 * 60 + 30,
      endMin: 7 * 60,
      daysMask: 127,
      activeNow: false,
      endTs: 0,
    },
    schoolMode: {
      active: (portal.schoolModeUntilMs ?? 0) > nowMs,
      untilMs: portal.schoolModeUntilMs ?? 0,
      allowedPackages: [],
    },
    blockSettingsAndStore: Boolean(portal.blockSettingsAndStore),
    lostMode: {
      enabled: Boolean(portal.lostModeEnabled),
      message: 'Téléphone perdu. Merci de contacter le Parent.',
      phoneNumber: '',
    },
    pendingRequests: [],
    geofences: [],
    securityEvents: [],
    ts: nowMs,
  };

  return (
    <div className="mx-auto max-w-2xl space-y-3.5 px-3.5 pb-10 pt-4">
      {/* Compact Read-Only Header (7-tap Shield icon opens Emergency Local MDM Exit) */}
      <div className="flex items-center justify-between gap-2 rounded-2xl border border-white/[0.08] bg-zinc-900/80 px-4 py-3">
        <div className="flex items-center gap-2.5 min-w-0">
          <button
            type="button"
            onClick={handleSecretShieldTap}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/15 ring-1 ring-emerald-500/30 active:scale-95"
          >
            <ShieldCheck className="h-4 w-4 text-emerald-400" />
          </button>
          <div className="min-w-0">
            <h1 className="truncate text-sm font-semibold text-white">{portal.deviceName}</h1>
            <p className="truncate text-[11px] text-zinc-400">
              Mode lecture seule · toute modification envoie une demande au Parent
            </p>
          </div>
        </div>
        <span
          className={`inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-0.5 font-mono text-[10px] ${
            portal.mqttConnected
              ? 'bg-emerald-500/15 text-emerald-300'
              : 'bg-amber-500/15 text-amber-300'
          }`}
        >
          <Wifi className="h-3 w-3" />
          {portal.mqttConnected ? 'En ligne' : 'Hors ligne'}
        </span>
      </div>

      {/* Emergency Local MDM Exit Modal (Cheat Code or Parent Admin PIN) */}
      {cheatModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 px-4 backdrop-blur-sm">
          <form
            onSubmit={(e) => void handleSubmitCheatExit(e)}
            className="w-full max-w-sm space-y-3 rounded-2xl border border-rose-500/40 bg-zinc-900 p-5 shadow-2xl"
          >
            <div className="flex items-center gap-2 text-sm font-semibold text-white">
              <Lock className="h-4 w-4 text-rose-400" />
              <span>Retrait d’urgence (MDM & Supervision)</span>
            </div>
            <p className="text-xs leading-relaxed text-zinc-400">
              Saisissez le code de sortie d’urgence (ou le code PIN administrateur) pour lever
              immédiatement le mode MDM, réafficher toutes les applis et rendre SentinelCam
              désinstallable hors-ligne.
            </p>
            <input
              type="password"
              autoFocus
              maxLength={16}
              value={cheatCodeInput}
              onChange={(e) => setCheatCodeInput(e.target.value)}
              placeholder="Code de sortie…"
              className="w-full rounded-xl border border-white/15 bg-zinc-950 px-3.5 py-2.5 font-mono text-sm tracking-widest text-white placeholder-zinc-500 focus:border-rose-400 focus:outline-none"
            />
            {cheatError && (
              <div className="rounded-lg bg-rose-500/15 px-3 py-1.5 text-xs text-rose-200">
                {cheatError}
              </div>
            )}
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setCheatModalOpen(false)}
                className="flex-1 rounded-xl border border-white/10 bg-zinc-950 py-2.5 text-xs font-medium text-zinc-300"
              >
                Annuler
              </button>
              <button
                type="submit"
                disabled={busy || cheatCodeInput.trim().length < 4}
                className="flex-1 rounded-xl bg-rose-500 py-2.5 text-xs font-semibold text-white hover:bg-rose-400 disabled:opacity-40"
              >
                Tout retirer
              </button>
            </div>
          </form>
        </div>
      )}

      {/* 1-Tap Prompt if Usage Access is not yet enabled (so app durations & stats load immediately) */}
      {!p.usageAccess && (
        <button
          type="button"
          disabled={busy}
          onClick={() => void runAction(() => SentinelNative.requestUsageAccessPermission())}
          className="flex w-full items-center justify-between gap-2 rounded-2xl border border-amber-500/40 bg-amber-500/12 px-4 py-3 text-left text-xs text-amber-100"
        >
          <span className="inline-flex items-center gap-2 font-medium">
            <ShieldAlert className="h-4 w-4 shrink-0 text-amber-400" />
            <span>Activez « Temps d’écran » pour charger les durées et applications</span>
          </span>
          <span className="shrink-0 rounded-lg bg-amber-400 px-2.5 py-1 font-semibold text-zinc-950">
            Activer
          </span>
        </button>
      )}

      {/* 3-Tab Child Navigation (Accueil / Règles & Système / Appareil) */}
      <div className="grid grid-cols-3 gap-1.5 rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-1.5">
        <button
          type="button"
          onClick={() => setChildTab('home')}
          className={`flex items-center justify-center gap-1.5 rounded-xl py-2 text-xs font-medium transition ${
            childTab === 'home' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
          }`}
        >
          <LayoutDashboard className="h-3.5 w-3.5" />
          Accueil
        </button>
        <button
          type="button"
          onClick={() => setChildTab('rules')}
          className={`flex items-center justify-center gap-1.5 rounded-xl py-2 text-xs font-medium transition ${
            childTab === 'rules' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
          }`}
        >
          <ShieldCheck className="h-3.5 w-3.5" />
          Règles & Système
        </button>
        <button
          type="button"
          onClick={() => setChildTab('device')}
          className={`flex items-center justify-center gap-1.5 rounded-xl py-2 text-xs font-medium transition ${
            childTab === 'device' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
          }`}
        >
          <Smartphone className="h-3.5 w-3.5" />
          Appareil
          {(missingOsSteps.length > 0 || needsDeviceAdmin) && (
            <span className="rounded-full bg-amber-400 px-1.5 font-mono text-[9px] font-bold text-zinc-950">
              {missingOsSteps.length + (needsDeviceAdmin ? 1 : 0)}
            </span>
          )}
        </button>
      </div>

      {/* TAB 1: EXACT PARENT ACCUEIL (READ-ONLY -> EVERY EDIT SENDS REQUEST TO PARENT) */}
      {childTab === 'home' && (
        <ChildSectionBoundary>
          <ParentalHomeTab
          screenTime={effectiveScreenTime}
          screenTimeLoading={localLoading && !localScreenTime}
          telemetry={mirrorTelemetry}
          nowMs={nowMs}
          feedback={childFeedback}
          onDismissFeedback={() => setChildFeedback(null)}
          onRefresh={() => refreshLocalDashboard(true)}
          onLockScreenNow={() => sendChangeRequest('change:lock', 'Verrouillage écran')}
          onSetScreenPause={(mins) =>
            sendChangeRequest(
              'change:pause',
              mins === 0 ? 'Arrêter la pause écran' : `Modifier la pause écran (${mins} min)`
            )
          }
          onApplyAppLock={(app: AppUsageEntry, mins: number) =>
            sendChangeRequest(
              `change:app:${app.packageName}`,
              mins === 0 ? `Débloquer ${app.appName}` : `Modifier le blocage de ${app.appName}`
            )
          }
          onClearAllAppLocks={() => sendChangeRequest('__global__', 'Débloquer toutes les applications')}
          onSetAppQuota={(app: AppUsageEntry, limit: number) =>
            sendChangeRequest(
              `change:app:${app.packageName}`,
              limit === 0
                ? `Retirer le quota de ${app.appName}`
                : `Modifier le quota de ${app.appName} (${limit} min/j)`
            )
          }
          onSaveAppGroupQuota={(opts) =>
            sendChangeRequest(
              opts.id ? `change:group:${opts.id}` : 'change:group:new',
              opts.delete
                ? 'Supprimer un quota de groupe'
                : `Modifier le groupe « ${opts.name || 'Apps'} »`
            )
          }
          onJumpToCapturesHour={() =>
            setChildFeedback({
              ok: true,
              text: 'Les captures automatiques sont consultables uniquement depuis l’Espace Parent.',
            })
          }
          onSetSchoolMode={(opts) =>
            sendChangeRequest(
              'change:school',
              opts.durationMinutes === 0 ? 'Désactiver le Mode École' : 'Modifier le Mode École'
            )
          }
          onRespondTimeRequest={async () => {
            setChildFeedback({
              ok: false,
              text: 'Seul le Parent peut accepter ou refuser une demande en attente.',
            });
          }}
          onRespondUnpair={async (approve) => {
            if (approve) await handleRequestParentUnpair();
            else setChildFeedback({ ok: false, text: 'Seul le Parent peut décider de la dissociation.' });
          }}
          onFlashText={() => sendChangeRequest('change:flash', 'Message plein écran')}
          onFlashImage={() => sendChangeRequest('change:flash', 'Image plein écran')}
        />
        </ChildSectionBoundary>
      )}

      {/* TAB 2: EXACT PARENT RÈGLES + ZONES + CONDUITE + SYSTÈME (READ-ONLY -> EVERY EDIT SENDS REQUEST TO PARENT) */}
      {childTab === 'rules' && (
        <ChildSectionBoundary>
        <div className="space-y-3.5">
          <ParentalRulesTab
            screenTime={effectiveScreenTime}
            screenTimeLoading={localLoading && !localScreenTime}
            telemetry={mirrorTelemetry}
            nowMs={nowMs}
            feedback={childFeedback}
            onDismissFeedback={() => setChildFeedback(null)}
            onRefresh={() => refreshLocalDashboard(true)}
            onSetCurfew={(opts) =>
              sendChangeRequest(
                'change:curfew',
                opts.enabled ? 'Modifier les horaires du Couvre-feu' : 'Désactiver le Couvre-feu'
              )
            }
            onSetSchoolMode={(opts) =>
              sendChangeRequest(
                'change:school',
                opts.durationMinutes === 0 ? 'Désactiver le Mode École' : 'Modifier la liste blanche École'
              )
            }
            onSetAntiBypass={(enabled) =>
              sendChangeRequest(
                'change:anti_bypass',
                enabled ? 'Verrouiller Paramètres & Play Store' : 'Débloquer Paramètres & Play Store'
              )
            }
            onSetLostMode={(opts) =>
              sendChangeRequest(
                'change:lost_mode',
                opts.enabled ? 'Modifier le Mode Perdu' : 'Désactiver le Mode Perdu'
              )
            }
            onClearSecurityEvents={() =>
              sendChangeRequest('change:journal', 'Effacer le journal de sécurité')
            }
            onSetAdminPin={() =>
              sendChangeRequest('change:pin', 'Modifier le code administrateur')
            }
            onSetAiShield={() =>
              sendChangeRequest('change:ai_shield', 'Modifier le Filtre d’écran IA')
            }
            onSetRemotePolicy={() =>
              sendChangeRequest('change:policy', 'Modifier les autorisations à distance')
            }
            onRespondUnpair={async () => {
              await handleRequestParentUnpair();
              setChildFeedback({
                ok: true,
                text: 'Demande de dissociation envoyée au Parent.',
              });
            }}
          />

          <GeofenceZonesCard
            screenTime={effectiveScreenTime}
            locationData={null}
            onSaveGeofence={(opts) =>
              sendChangeRequest(
                'change:geofence',
                opts.delete ? 'Supprimer une zone de sécurité' : `Modifier la zone « ${opts.name || 'GPS'} »`
              )
            }
            onRequestLocation={async () => {
              void refreshLocalDashboard(true);
            }}
          />

          {effectiveScreenTime.drivingReport && (
            <DrivingScoreCard
              data={effectiveScreenTime.drivingReport}
              loading={localLoading}
              days={7}
              onChangeDays={async () => {
                await refreshLocalDashboard(true);
              }}
              onRefresh={() => refreshLocalDashboard(true)}
              onFocusEvent={() => {}}
            />
          )}

          <SystemCommandCard
            data={effectiveScreenTime.systemStatus ?? null}
            loading={localLoading && !effectiveScreenTime.systemStatus}
            onRefresh={() => refreshLocalDashboard(true)}
            onSetVolume={(stream, pct) =>
              sendChangeRequest('change:system', `Modifier le volume (${stream} → ${pct}%)`)
            }
            onSetRinger={(mode) =>
              sendChangeRequest('change:system', `Passer la sonnerie en mode ${mode}`)
            }
            onSetBrightness={(pct) =>
              sendChangeRequest('change:system', `Modifier la luminosité (${pct}%)`)
            }
            onLaunchApp={(pkg) =>
              sendChangeRequest(`change:app:${pkg}`, `Autoriser l’ouverture de ${pkg}`)
            }
            onUninstallApp={(pkg) =>
              sendChangeRequest(`change:app:${pkg}`, `Désinstaller l’application ${pkg}`)
            }
          />
        </div>
        </ChildSectionBoundary>
      )}

      {/* TAB 3: APPAREIL (PERMISSIONS, ANTI-VEILLE & DISSOCIATION) */}
      {childTab === 'device' && (
        <div className="space-y-4">
          {/* Missing OS Permissions (One-Tap Grants Only — Never Any Off Switch) */}
          {(missingOsSteps.length > 0 || needsDeviceAdmin) ? (
            <div className="rounded-2xl border border-amber-500/25 bg-zinc-900/75 p-4">
              <div className="flex items-center gap-2 text-xs font-semibold text-amber-200">
                <ShieldAlert className="h-4 w-4 text-amber-400" />
                <span>Autorisations à finaliser</span>
              </div>
              <div className="mt-2.5 space-y-1.5">
                {needsDeviceAdmin && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleRequestDeviceAdmin()}
                    className="flex w-full items-center justify-between gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-left text-xs text-amber-100"
                  >
                    <span className="inline-flex items-center gap-2">
                      <Lock className="h-3.5 w-3.5 text-amber-300" />
                      <span>Activer les droits administrateur</span>
                    </span>
                    <span className="shrink-0 font-semibold">Activer</span>
                  </button>
                )}
                {missingOsSteps.map((step) => {
                  const Icon = step.icon;
                  return (
                    <button
                      key={step.key}
                      type="button"
                      disabled={busy}
                      onClick={() => void step.action()}
                      className={`flex w-full items-center justify-between gap-2 rounded-xl border px-3 py-2.5 text-left text-xs transition ${
                        step.highlight
                          ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
                          : 'border-white/[0.07] bg-zinc-950 text-zinc-200 hover:border-white/20'
                      }`}
                    >
                      <span className="inline-flex items-center gap-2">
                        <Icon className="h-3.5 w-3.5 opacity-80" />
                        <span>{step.label}</span>
                      </span>
                      <span className="shrink-0 font-semibold text-emerald-400">Autoriser</span>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-2 rounded-2xl border border-emerald-500/20 bg-emerald-500/[0.07] px-4 py-3 text-xs text-emerald-300">
              <CheckCircle2 className="h-4 w-4 shrink-0" />
              <span>Toutes les autorisations système sont actives.</span>
            </div>
          )}

          {/* Brand-Smart Manufacturer Anti-Sleep Assistant */}
          <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/55 p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-1.5 text-xs font-semibold text-white">
                  <BatteryCharging className="h-3.5 w-3.5 text-emerald-400" />
                  <span>Assistant anti-veille ({portal.deviceManufacturer || 'Android'})</span>
                </div>
                <p className="mt-1 text-[11px] leading-relaxed text-zinc-400">
                  {portal.manufacturerTip ||
                    'Réglez la batterie sur « Non restreinte » pour empêcher la coupure en arrière-plan.'}
                </p>
              </div>
              <button
                type="button"
                disabled={busy}
                onClick={() => void runAction(() => SentinelNative.openManufacturerBatterySettings())}
                className="shrink-0 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-[11px] font-medium text-emerald-300 hover:border-white/25"
              >
                Ouvrir
              </button>
            </div>
          </div>

          {/* Parent-Authorized Unpair Request ONLY */}
          <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/50 p-4">
            {portal.unpairRequestPending ? (
              <div className="flex items-start gap-2.5 text-xs text-amber-200">
                <Clock className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
                <div>
                  <div className="font-medium">Demande envoyée au Parent</div>
                  <p className="mt-0.5 text-[11px] text-zinc-400">
                    La dissociation de cet appareil attend l’autorisation du Parent depuis le site Web.
                  </p>
                </div>
              </div>
            ) : confirmUnpairAsk ? (
              <div className="space-y-2.5 text-xs">
                <p className="text-zinc-300">
                  Envoyer une demande au Parent pour délier cet appareil ? Le Parent devra l’autoriser
                  depuis son espace.
                </p>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleRequestParentUnpair()}
                    className="flex-1 rounded-xl bg-white py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
                  >
                    Envoyer la demande
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmUnpairAsk(false)}
                    className="rounded-xl border border-white/10 px-3 py-2 text-xs text-zinc-400 hover:text-white"
                  >
                    Annuler
                  </button>
                </div>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-2 text-xs">
                <span className="text-zinc-500">Dissocier l’appareil</span>
                <button
                  type="button"
                  onClick={() => setConfirmUnpairAsk(true)}
                  className="rounded-lg border border-white/10 bg-zinc-950 px-3 py-1.5 text-[11px] font-medium text-zinc-300 hover:text-white"
                >
                  Demander au parent
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
