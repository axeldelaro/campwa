import React, { useEffect, useState } from 'react';
import {
  BatteryCharging,
  BookUser,
  Camera,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
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
 * Pure, Tamper-Proof Child Device App (v16).
 *
 * - ZERO feature toggles accessible to the child: all rules, limits, AI pixelation,
 *   GPS, gallery, and permissions are controlled 100% remotely from the Parent Website.
 * - Step 1: Enter the 6-char code generated on the Parent Website to bind permanently.
 * - Step 2: Grant required Android OS permissions once via the clean setup checklist.
 * - Once paired, displays a calm read-only "Appareil protégé" screen.
 *   Local maintenance (unpair / change PIN) is strictly locked behind the Parent Admin PIN.
 */
export const AndroidEmitterView: React.FC<AndroidEmitterViewProps> = ({ isNativeAndroid }) => {
  const [portal, setPortal] = useState<NativePortalState | null>(null);
  const [busy, setBusy] = useState(false);
  const [nowMs, setNowMs] = useState(() => Date.now());

  // Parent-generated 6-char pairing code input
  const [pairCode, setPairCode] = useState('');
  const [pairMessage, setPairMessage] = useState<string | null>(null);

  // Local Parent Admin section (strictly behind Admin PIN)
  const [adminDrawerOpen, setAdminDrawerOpen] = useState(false);
  const [adminPinInput, setAdminPinInput] = useState('');
  const [adminUnlocked, setAdminUnlocked] = useState(false);
  const [verifiedAdminPin, setVerifiedAdminPin] = useState('');
  const [newAdminPin, setNewAdminPin] = useState('');
  const [adminFeedback, setAdminFeedback] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    const t = window.setInterval(() => setNowMs(Date.now()), 5000);
    return () => window.clearInterval(t);
  }, []);

  // Auto-relock local parent session when app goes to background
  useEffect(() => {
    const onVis = () => {
      if (document.hidden) {
        setAdminUnlocked(false);
        setVerifiedAdminPin('');
        setAdminDrawerOpen(false);
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
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

  const handlePair = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = normalizePairingCode(pairCode);
    if (clean.length !== 6) {
      setPairMessage('Saisissez les 6 caractères affichés sur le site Parent.');
      return;
    }
    setPairMessage('Connexion sécurisée à l’Espace Parent…');
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

  const handleUnlockAdmin = async (e: React.FormEvent) => {
    e.preventDefault();
    setAdminFeedback(null);
    setBusy(true);
    try {
      const res = await SentinelNative.verifyAdminPin({ pin: adminPinInput.trim() });
      if (res.valid) {
        setVerifiedAdminPin(adminPinInput.trim());
        setAdminUnlocked(true);
        setAdminPinInput('');
      } else {
        setAdminFeedback({ ok: false, text: 'Code administrateur incorrect.' });
      }
    } catch {
      setAdminFeedback({ ok: false, text: 'Vérification impossible.' });
    } finally {
      setBusy(false);
    }
  };

  const handleActivateDeviceAdmin = async () => {
    const pin = verifiedAdminPin || newAdminPin.trim();
    if (pin.length < 4) {
      setAdminFeedback({ ok: false, text: 'Définissez d’abord un code administrateur depuis le site Parent.' });
      return;
    }
    await runAction(() => SentinelNative.enableAdminProtection({ pin }));
  };

  const handleUpdatePinLocal = async (e: React.FormEvent) => {
    e.preventDefault();
    const p = newAdminPin.trim();
    if (p.length < 4 || p.length > 12) {
      setAdminFeedback({ ok: false, text: '4 à 12 caractères requis.' });
      return;
    }
    setBusy(true);
    setAdminFeedback(null);
    try {
      const next = await SentinelNative.updateAdminPin({ currentPin: verifiedAdminPin, newPin: p });
      setPortal(next);
      setVerifiedAdminPin(p);
      setNewAdminPin('');
      setAdminFeedback({ ok: true, text: 'Code administrateur mis à jour.' });
    } catch (err) {
      setAdminFeedback({ ok: false, text: err instanceof Error ? err.message : 'Modification refusée.' });
    } finally {
      setBusy(false);
    }
  };

  const handleUnpair = async () => {
    setBusy(true);
    setAdminFeedback(null);
    try {
      const next = await SentinelNative.unpairFromParent({ pin: verifiedAdminPin });
      setPortal(next);
      setAdminUnlocked(false);
      setVerifiedAdminPin('');
      setAdminDrawerOpen(false);
    } catch (err) {
      setAdminFeedback({ ok: false, text: err instanceof Error ? err.message : 'Action refusée.' });
    } finally {
      setBusy(false);
    }
  };

  const p = portal.permissions;
  const aiNeedsScreenCapture = Boolean((portal.aiBlurFaces || portal.aiBlurNsfw) && !portal.aiShieldRunning);
  const needsDeviceAdmin = Boolean(portal.hasAdminPin && !portal.deviceAdminActive);

  // Android OS permissions that still need a one-time OS prompt tap
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
      label: 'Activer la capture pour le Filtre IA',
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
      label: 'Affichage écran de blocage & Filtre IA',
      icon: ShieldCheck,
      action: () => runAction(() => SentinelNative.requestOverlayPermission()),
    });
  }
  if (!p.batteryOptimizationIgnored) {
    missingOsSteps.push({
      key: 'battery',
      label: 'Arrière-plan illimité (anti-veille)',
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
      label: 'Galerie photos (hors dossiers masqués)',
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
      label: 'Envoi SMS (carte SIM)',
      icon: MessageSquare,
      action: () => runAction(() => SentinelNative.requestSmsPermission()),
    });
  }

  /* ==================== MODE A: NOT YET PAIRED TO PARENT ==================== */
  if (!portal.parentManaged) {
    return (
      <div className="mx-auto max-w-md space-y-4 px-4 py-6">
        <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-6">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-500/15 ring-1 ring-emerald-500/30">
            <ShieldCheck className="h-6 w-6 text-emerald-400" />
          </div>
          <h1 className="mt-4 text-center text-lg font-semibold text-white">
            Associer au Parent
          </h1>
          <p className="mt-1 text-center text-xs text-zinc-400">
            Générez un code à 6 caractères sur le site Parent puis saisissez-le ici. Toutes les
            règles seront ensuite pilotées à distance.
          </p>

          <form onSubmit={(e) => void handlePair(e)} className="mt-5 space-y-3">
            <input
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoComplete="off"
              maxLength={6}
              value={pairCode}
              onChange={(e) => setPairCode(normalizePairingCode(e.target.value))}
              placeholder="CODE PARENT (6 CAR.)"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 py-3.5 text-center font-mono text-lg font-semibold uppercase tracking-[0.28em] text-white placeholder-zinc-600 focus:border-emerald-400 focus:outline-none"
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
                ? 'Temps écoulé — vérifiez le code affiché sur le site Parent et réessayez.'
                : pairMessage}
            </div>
          )}
        </div>

        {/* One-Time Android OS Permissions Setup */}
        {missingOsSteps.length > 0 && (
          <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-4">
            <div className="text-xs font-semibold text-white">
              Autorisations Android ({missingOsSteps.length} restantes)
            </div>
            <p className="mt-0.5 text-[11px] text-zinc-400">
              À valider une seule fois sur l’appareil avant de le confier.
            </p>
            <div className="mt-3 space-y-1.5">
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

  /* ==================== MODE B: PARENT-MANAGED READ-ONLY PROTECTED DEVICE ==================== */
  return (
    <div className="mx-auto max-w-md space-y-4 px-4 py-6">
      {/* Main Read-Only Protection Status Card (ZERO Child Toggles) */}
      <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/15 ring-1 ring-emerald-500/30">
              <ShieldCheck className="h-5 w-5 text-emerald-400" />
            </div>
            <div>
              <h1 className="text-sm font-semibold text-white">Appareil protégé</h1>
              <p className="text-[11px] text-zinc-400">Géré à distance par le Parent</p>
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

        {/* Read-Only Active Protections Badges */}
        <div className="mt-4 flex flex-wrap gap-1.5 text-[11px]">
          {portal.aiShieldRunning && (
            <span className="inline-flex items-center gap-1 rounded-lg bg-emerald-500/15 px-2.5 py-1 text-emerald-300">
              <Sparkles className="h-3 w-3" />
              Filtre IA actif
            </span>
          )}
          {portal.curfewEnabled && (
            <span className="rounded-lg bg-white/[0.06] px-2.5 py-1 text-zinc-200">Couvre-feu</span>
          )}
          {portal.blockSettingsAndStore && (
            <span className="rounded-lg bg-white/[0.06] px-2.5 py-1 text-zinc-200">
              Paramètres protégés
            </span>
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
          {!portal.aiShieldRunning &&
            !portal.curfewEnabled &&
            !portal.blockSettingsAndStore &&
            (portal.activeAppLocksCount ?? 0) === 0 && (
              <span className="text-xs text-zinc-500">
                Protection continue active en arrière-plan.
              </span>
            )}
        </div>

        {missingOsSteps.length === 0 && !needsDeviceAdmin && (
          <div className="mt-4 flex items-center gap-2 rounded-xl border border-emerald-500/20 bg-emerald-500/[0.07] px-3 py-2 text-xs text-emerald-300">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            <span>Toutes les autorisations système sont configurées.</span>
          </div>
        )}
      </div>

      {/* Missing OS Permissions Prompt (One-Tap System Grants Only — No Off Switches!) */}
      {(missingOsSteps.length > 0 || needsDeviceAdmin) && (
        <div className="rounded-2xl border border-amber-500/25 bg-zinc-900/75 p-4">
          <div className="flex items-center gap-2 text-xs font-semibold text-amber-200">
            <ShieldAlert className="h-4 w-4 text-amber-400" />
            <span>Autorisations Android à valider</span>
          </div>
          <div className="mt-2.5 space-y-1.5">
            {needsDeviceAdmin && (
              <button
                type="button"
                disabled={busy}
                onClick={() => void handleActivateDeviceAdmin()}
                className="flex w-full items-center justify-between gap-2 rounded-xl border border-amber-500/35 bg-amber-500/10 px-3 py-2.5 text-left text-xs text-amber-100"
              >
                <span className="inline-flex items-center gap-2">
                  <Lock className="h-3.5 w-3.5 text-amber-300" />
                  <span>Protection Administrateur Android</span>
                </span>
                <span className="font-semibold">Activer →</span>
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

      {/* Discreet Local Parent Admin Area (Strictly Locked Behind Admin PIN) */}
      <div className="rounded-2xl border border-white/[0.07] bg-zinc-900/60 p-4">
        <button
          type="button"
          onClick={() => setAdminDrawerOpen((v) => !v)}
          className="flex w-full items-center justify-between text-xs font-medium text-zinc-400 hover:text-white"
        >
          <span className="inline-flex items-center gap-2">
            <Lock className="h-3.5 w-3.5" />
            <span>Administration Parent (sur cet appareil)</span>
          </span>
          {adminDrawerOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>

        {adminDrawerOpen && (
          <div className="mt-3 space-y-3 border-t border-white/[0.06] pt-3">
            {adminFeedback && (
              <div
                className={`rounded-xl border px-3 py-2 text-xs ${
                  adminFeedback.ok
                    ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
                    : 'border-rose-500/30 bg-rose-500/10 text-rose-200'
                }`}
              >
                {adminFeedback.text}
              </div>
            )}

            {portal.hasAdminPin && !adminUnlocked ? (
              <form onSubmit={(e) => void handleUnlockAdmin(e)} className="flex gap-2">
                <input
                  type="password"
                  value={adminPinInput}
                  onChange={(e) => setAdminPinInput(e.target.value)}
                  placeholder="Code administrateur"
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={busy || !adminPinInput.trim()}
                  className="shrink-0 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                >
                  Ouvrir
                </button>
              </form>
            ) : (
              <div className="space-y-3">
                {portal.hasAdminPin && (
                  <form onSubmit={(e) => void handleUpdatePinLocal(e)} className="flex gap-2">
                    <input
                      type="password"
                      maxLength={12}
                      value={newAdminPin}
                      onChange={(e) => setNewAdminPin(e.target.value)}
                      placeholder="Nouveau code admin (4–12 car.)"
                      className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
                    />
                    <button
                      type="submit"
                      disabled={busy || newAdminPin.trim().length < 4}
                      className="shrink-0 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                    >
                      Changer
                    </button>
                  </form>
                )}

                <div className="flex flex-wrap items-center justify-between gap-2 pt-1 text-xs">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void runAction(() => SentinelNative.requestScreenSharePermission())}
                    className="rounded-lg border border-white/10 bg-zinc-950 px-3 py-1.5 text-zinc-300 hover:text-white"
                  >
                    Valider capture écran
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleUnpair()}
                    className="rounded-lg border border-rose-500/35 bg-rose-500/10 px-3 py-1.5 text-rose-200 hover:bg-rose-500/20"
                  >
                    Délier l’appareil
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
