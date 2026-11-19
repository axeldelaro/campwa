import React, { useEffect, useState } from 'react';
import {
  BatteryCharging,
  BookUser,
  Camera,
  CheckCircle2,
  Clock,
  Image as ImageIcon,
  Lock,
  MapPin,
  MessageSquare,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Wifi,
} from 'lucide-react';
import { normalizePairingCode } from '../crypto/cryptoEngine';
import { SentinelNative } from '../native/sentinelPlugin';
import type { NativePortalState } from '../types/sentinel';

interface AndroidEmitterViewProps {
  isNativeAndroid: boolean;
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

  useEffect(() => {
    const t = window.setInterval(() => setNowMs(Date.now()), 5000);
    return () => window.clearInterval(t);
  }, []);

  useEffect(() => {
    if (!isNativeAndroid) return;
    let listenerHandle: { remove: () => Promise<void> } | null = null;

    const init = async () => {
      try {
        const initial = await SentinelNative.getPortalState();
        setPortal(initial);
        listenerHandle = await SentinelNative.addListener('portalStateChanged', (next) => {
          setPortal(next);
        });
      } catch (err) {
        console.error('Native bridge init error:', err);
      }
    };

    void init();

    const poll = window.setInterval(async () => {
      try {
        const latest = await SentinelNative.getPortalState();
        setPortal(latest);
      } catch {
        // Ignore transient poll errors
      }
    }, 4000);

    return () => {
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
      if (res) setPortal(res);
      else setPortal(await SentinelNative.getPortalState());
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
    setPairMessage('Association chiffrée avec l’Espace Parent…');
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
  const aiNeedsScreenCapture = Boolean((portal.aiBlurFaces || portal.aiBlurNsfw) && !portal.aiShieldRunning);
  const needsDeviceAdmin = !portal.deviceAdminActive;

  const missingOsSteps: Array<{
    key: string;
    label: string;
    highlight?: boolean;
    icon: React.FC<{ className?: string }>;
    action: () => Promise<void>;
  }> = [];

  if (aiNeedsScreenCapture) {
    missingOsSteps.push({
      key: 'ai-screen',
      label: 'Capture écran pour le Filtre IA',
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
      label: 'Galerie photos publique',
      icon: ImageIcon,
      action: () => runAction(() => SentinelNative.requestGalleryPermission()),
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
  if (!p.sms) {
    missingOsSteps.push({
      key: 'sms',
      label: 'Envoi SMS',
      icon: MessageSquare,
      action: () => runAction(() => SentinelNative.requestSmsPermission()),
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
              <div
                className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${
                  portal.deviceAdminActive ? 'bg-emerald-500/20 text-emerald-300' : 'bg-amber-500/20 text-amber-300'
                }`}
              >
                {portal.deviceAdminActive ? (
                  <CheckCircle2 className="h-5 w-5" />
                ) : (
                  <ShieldAlert className="h-5 w-5" />
                )}
              </div>
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
      </div>
    );
  }

  /* ==================== MODE B: PARENT-MANAGED PROTECTED DEVICE ==================== */
  return (
    <div className="mx-auto max-w-md space-y-4 px-4 py-6">
      {/* Main Read-Only Status Card */}
      <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/15 ring-1 ring-emerald-500/30">
              <ShieldCheck className="h-5 w-5 text-emerald-400" />
            </div>
            <div>
              <h1 className="text-sm font-semibold text-white">Appareil protégé</h1>
              <p className="text-[11px] text-zinc-400">Piloté à distance par le Parent</p>
            </div>
          </div>
          <span
            className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 font-mono text-[10px] ${
              portal.mqttConnected
                ? 'bg-emerald-500/15 text-emerald-300'
                : 'bg-amber-500/15 text-amber-300'
            }`}
          >
            <Wifi className="h-3 w-3" />
            {portal.mqttConnected ? 'En ligne' : 'Hors ligne'}
          </span>
        </div>

        {/* Active Protections Read-Only Summary */}
        <div className="mt-4 flex flex-wrap gap-1.5 text-[11px]">
          {portal.deviceAdminActive && (
            <span className="rounded-lg bg-emerald-500/15 px-2.5 py-1 text-emerald-300">
              Admin verrouillé
            </span>
          )}
          {portal.blockSettingsAndStore && (
            <span className="rounded-lg bg-white/[0.06] px-2.5 py-1 text-zinc-200">
              Paramètres bloqués
            </span>
          )}
          {portal.aiShieldRunning && (
            <span className="inline-flex items-center gap-1 rounded-lg bg-emerald-500/15 px-2.5 py-1 text-emerald-300">
              <Sparkles className="h-3 w-3" />
              Filtre IA actif
            </span>
          )}
          {portal.curfewEnabled && (
            <span className="rounded-lg bg-white/[0.06] px-2.5 py-1 text-zinc-200">Couvre-feu</span>
          )}
          {(portal.schoolModeUntilMs ?? 0) > nowMs && (
            <span className="rounded-lg bg-emerald-500/15 px-2.5 py-1 text-emerald-200">Mode École</span>
          )}
          {portal.lostModeEnabled && (
            <span className="rounded-lg bg-rose-500/20 px-2.5 py-1 text-rose-200">Mode Perdu</span>
          )}
          {(portal.activeAppLocksCount ?? 0) > 0 && (
            <span className="rounded-lg bg-rose-500/15 px-2.5 py-1 text-rose-200">
              {portal.activeAppLocksCount} appli(s) verrouillée(s)
            </span>
          )}
        </div>

        {missingOsSteps.length === 0 && !needsDeviceAdmin && (
          <div className="mt-4 flex items-center gap-2 rounded-xl border border-emerald-500/20 bg-emerald-500/[0.07] px-3 py-2 text-xs text-emerald-300">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            <span>Toutes les autorisations sont actives.</span>
          </div>
        )}
      </div>

      {/* Missing OS Permissions (One-Tap Grants Only — Never Any Off Switch) */}
      {(missingOsSteps.length > 0 || needsDeviceAdmin) && (
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
      )}

      {/* Parent-Authorized Unpair Request ONLY (Child Cannot Unpair Locally) */}
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
  );
};
