import React, { useEffect, useRef, useState } from 'react';
import {
  BatteryCharging,
  Bell,
  Camera,
  Check,
  CheckCircle2,
  Copy,
  KeyRound,
  Lock,
  Play,
  Power,
  Radio,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Unlock,
  Wifi,
  Zap,
} from 'lucide-react';
import { SentinelNative } from '../native/sentinelPlugin';
import { BrowserEmitterEngine } from '../services/browserEmitterEngine';
import type { NativePortalState } from '../types/sentinel';

interface AndroidEmitterViewProps {
  isNativeAndroid: boolean;
}

export const AndroidEmitterView: React.FC<AndroidEmitterViewProps> = ({ isNativeAndroid }) => {
  const [portal, setPortal] = useState<NativePortalState | null>(null);
  const [consentChecked, setConsentChecked] = useState(false);
  const [bootAutoStart, setBootAutoStart] = useState(true);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);

  const webEngineRef = useRef<BrowserEmitterEngine | null>(null);

  useEffect(() => {
    if (isNativeAndroid) {
      let listenerHandle: { remove: () => Promise<void> } | null = null;

      const initNative = async () => {
        try {
          const initial = await SentinelNative.getPortalState();
          setPortal(initial);
          setBootAutoStart(initial.autoStartOnBoot);

          // If user already completed one-time onboarding and service isn't running, ensure continuous portal starts seamlessly
          if (initial.onboardingCompleted && !initial.serviceRunning) {
            const started = await SentinelNative.startPortalService();
            setPortal(started);
          }

          listenerHandle = await SentinelNative.addListener('portalStateChanged', (nextState) => {
            setPortal(nextState);
          });
        } catch (err) {
          console.error('Native bridge error:', err);
        }
      };

      void initNative();

      const pollInterval = window.setInterval(async () => {
        try {
          const latest = await SentinelNative.getPortalState();
          setPortal(latest);
        } catch {
          // Ignore
        }
      }, 3000);

      return () => {
        window.clearInterval(pollInterval);
        if (listenerHandle) {
          void listenerHandle.remove();
        }
      };
    } else {
      const engine = new BrowserEmitterEngine((nextState) => {
        setPortal(nextState);
      });
      webEngineRef.current = engine;
      setPortal(engine.getState());

      return () => {
        void engine.stopPortal();
      };
    }
  }, [isNativeAndroid]);

  const handleRequestPermissions = async () => {
    if (!isNativeAndroid) return;
    setBusy(true);
    try {
      await SentinelNative.requestAllPermissions();
      const updated = await SentinelNative.getPortalState();
      setPortal(updated);
    } finally {
      setBusy(false);
    }
  };

  const handleRequestBatteryDoze = async () => {
    if (!isNativeAndroid) return;
    setBusy(true);
    try {
      await SentinelNative.requestBatteryExemption();
      const updated = await SentinelNative.getPortalState();
      setPortal(updated);
    } finally {
      setBusy(false);
    }
  };

  const handleCompleteOnboarding = async () => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const started = await SentinelNative.completeOnboardingAndStart({
          autoStartOnBoot: bootAutoStart,
        });
        setPortal(started);
      } else if (webEngineRef.current) {
        const started = await webEngineRef.current.startPortal();
        setPortal(started);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleService = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = portal.serviceRunning
          ? await SentinelNative.stopPortalService()
          : await SentinelNative.startPortalService();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = portal.serviceRunning
          ? await webEngineRef.current.stopPortal()
          : await webEngineRef.current.startPortal();
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleRegenerateCode = async () => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.regeneratePairingCode();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.regenerateCode();
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleResetPairing = async () => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.resetPairedController();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = webEngineRef.current.resetPairing();
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleBootStart = async () => {
    if (!isNativeAndroid || !portal) return;
    const nextVal = !portal.autoStartOnBoot;
    setBootAutoStart(nextVal);
    const updated = await SentinelNative.setAutoStartOnBoot({ enabled: nextVal });
    setPortal(updated);
  };

  const handleCopyCode = async () => {
    if (!portal) return;
    try {
      await navigator.clipboard.writeText(portal.pairingCode);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignore
    }
  };

  if (!portal) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <RefreshCw className="h-8 w-8 animate-spin text-emerald-400" />
      </div>
    );
  }

  /* ==================== ONE-TIME ONBOARDING WIZARD ==================== */
  if (isNativeAndroid && !portal.onboardingCompleted) {
    const perms = portal.permissions;
    const mandatoryReady = perms.camera && consentChecked;

    return (
      <div className="mx-auto max-w-2xl px-4 py-8">
        <div className="rounded-3xl border border-emerald-500/30 bg-obsidian-900/90 p-6 shadow-2xl sm:p-8">
          <div className="flex items-center gap-3.5">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/15">
              <ShieldCheck className="h-6 w-6 text-emerald-400" />
            </div>
            <div>
              <span className="rounded-full bg-emerald-500/15 px-2.5 py-0.5 font-mono text-[10px] font-bold uppercase tracking-wider text-emerald-300">
                Configuration Initiale Unique
              </span>
              <h1 className="mt-1 text-xl font-extrabold text-white">
                Activation du Portail Continu SentinelCam
              </h1>
            </div>
          </div>

          <p className="mt-4 text-xs leading-relaxed text-slate-300">
            Cet assistant configure votre smartphone une seule fois afin de maintenir un portail
            d’écoute chiffré de bout en bout en arrière-plan. Une{' '}
            <strong className="text-white">notification système permanente</strong> restera visible
            dans votre barre d’état Android indiquant si le portail est en attente ou en streaming,
            avec un bouton immédiat{' '}
            <strong className="text-emerald-300">« Couper le portail »</strong> pour révoquer
            l’accès à tout instant.
          </p>

          {/* Step-by-step Permission Cards */}
          <div className="mt-6 space-y-3">
            {/* Step 1: Camera, Mic, Notifications */}
            <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-4">
              <div className="flex items-start gap-3">
                <Camera className="mt-0.5 h-5 w-5 shrink-0 text-emerald-400" />
                <div>
                  <div className="text-xs font-bold text-white">
                    1. Caméra Frontale, Microphone & Notification Système
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-400">
                    Requis sur Android 11–15 pour capturer le flux vidéo hors de l’écran principal
                    et afficher l’état du ForegroundService.
                  </div>
                </div>
              </div>
              {perms.camera && perms.notifications ? (
                <span className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-emerald-500/15 px-3 py-1.5 text-xs font-bold text-emerald-300">
                  <CheckCircle2 className="h-4 w-4" />
                  Accordé
                </span>
              ) : (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleRequestPermissions()}
                  className="shrink-0 rounded-xl bg-emerald-500 px-3.5 py-2 text-xs font-bold text-obsidian-950 transition hover:bg-emerald-400"
                >
                  Autoriser
                </button>
              )}
            </div>

            {/* Step 2: Battery Optimization Exemption (Doze) */}
            <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-4">
              <div className="flex items-start gap-3">
                <BatteryCharging className="mt-0.5 h-5 w-5 shrink-0 text-cyan-400" />
                <div>
                  <div className="text-xs font-bold text-white">
                    2. Exemption du Mode Doze / Économiseur de Batterie
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-400">
                    Empêche Android de suspendre l’écoute réseau lorsque l’écran est verrouillé ou
                    que d’autres applications sont utilisées.
                  </div>
                </div>
              </div>
              {perms.batteryOptimizationIgnored ? (
                <span className="inline-flex shrink-0 items-center gap-1 rounded-xl bg-emerald-500/15 px-3 py-1.5 text-xs font-bold text-emerald-300">
                  <CheckCircle2 className="h-4 w-4" />
                  Exempté
                </span>
              ) : (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleRequestBatteryDoze()}
                  className="shrink-0 rounded-xl border border-cyan-500/40 bg-cyan-500/15 px-3.5 py-2 text-xs font-bold text-cyan-300 transition hover:bg-cyan-500/25"
                >
                  Exempter
                </button>
              )}
            </div>

            {/* Step 3: Auto-start on Boot */}
            <label className="flex cursor-pointer items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-4">
              <div className="flex items-start gap-3">
                <Bell className="mt-0.5 h-5 w-5 shrink-0 text-amber-400" />
                <div>
                  <div className="text-xs font-bold text-white">
                    3. Relance Automatique après Redémarrage du Téléphone
                  </div>
                  <div className="mt-0.5 text-[11px] text-slate-400">
                    Réactive le portail d’écoute au démarrage d’Android (BOOT_COMPLETED).
                  </div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={bootAutoStart}
                onChange={(e) => setBootAutoStart(e.target.checked)}
                className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500 focus:ring-emerald-500"
              />
            </label>
          </div>

          {/* Explicit Informed Consent */}
          <label className="mt-5 flex cursor-pointer items-start gap-3 rounded-2xl border border-emerald-500/25 bg-emerald-500/5 p-4">
            <input
              type="checkbox"
              checked={consentChecked}
              onChange={(e) => setConsentChecked(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-emerald-500/40 bg-obsidian-950 text-emerald-500 focus:ring-emerald-500"
            />
            <span className="text-xs leading-relaxed text-slate-200">
              <strong>Consentement éclairé :</strong> Je suis le propriétaire ou l’utilisateur
              autorisé de cet appareil et j’accepte l’activation du service continu SentinelCam en
              arrière-plan, contrôlable uniquement via mon code d’appairage chiffré de bout en bout.
            </span>
          </label>

          <button
            type="button"
            disabled={!mandatoryReady || busy}
            onClick={() => void handleCompleteOnboarding()}
            className="mt-6 flex w-full items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-400 px-6 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/25 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Zap className="h-4 w-4" />
            <span>Activer le Portail Continu Maintenant</span>
          </button>
        </div>
      </div>
    );
  }

  /* ==================== PERSISTENT PORTAL DASHBOARD ==================== */
  return (
    <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
      {!isNativeAndroid && (
        <div className="mb-5 flex items-center justify-between gap-3 rounded-2xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-xs text-cyan-200">
          <div className="flex items-center gap-2.5">
            <Smartphone className="h-4 w-4 shrink-0 text-cyan-400" />
            <span>
              <strong>Mode Émetteur Web (Test Navigateur)</strong> — Pour une écoute permanente
              écran verrouillé et en arrière-plan sur smartphone, installez l’APK Android natif.
            </span>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 md:grid-cols-12">
        {/* PAIRING CODE & SERVICE HERO CARD */}
        <div className="space-y-5 md:col-span-7">
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-6 shadow-2xl">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2.5">
                <Radio
                  className={`h-5 w-5 ${
                    portal.portalState === 'streaming'
                      ? 'text-rose-400 animate-pulse'
                      : portal.serviceRunning
                      ? 'text-emerald-400'
                      : 'text-slate-500'
                  }`}
                />
                <span className="text-sm font-bold text-white">
                  {portal.portalState === 'streaming'
                    ? '🔴 STREAMING CAMÉRA EN COURS'
                    : portal.portalState === 'locked'
                    ? '🛡️ VERROUILLÉ (ANTI-BRUTE-FORCE)'
                    : portal.serviceRunning
                    ? 'PORTAIL SENTINELCAM ACTIF — EN ATTENTE'
                    : 'PORTAIL COUPÉ'}
                </span>
              </div>

              <span
                className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 font-mono text-[11px] font-semibold ${
                  portal.mqttConnected
                    ? 'bg-emerald-500/15 text-emerald-300'
                    : 'bg-amber-500/15 text-amber-300'
                }`}
              >
                <Wifi className="h-3 w-3" />
                {portal.mqttConnected ? 'Signalisation WSS Active' : portal.activeBroker}
              </span>
            </div>

            {/* 6-CHAR PAIRING CODE DISPLAY */}
            <div className="mt-6 rounded-2xl border border-emerald-500/30 bg-obsidian-950 p-6 text-center shadow-inner">
              <div className="flex items-center justify-center gap-1.5 text-[11px] font-semibold uppercase tracking-widest text-slate-400">
                <KeyRound className="h-3.5 w-3.5 text-emerald-400" />
                <span>Code d’Appairage Permanent (6 Caractères)</span>
              </div>

              <div className="mt-3 flex justify-center gap-2 sm:gap-3">
                {portal.pairingCode.split('').map((c, idx) => (
                  <span
                    key={idx}
                    className="flex h-14 w-11 items-center justify-center rounded-xl border border-emerald-500/40 bg-obsidian-900 font-mono text-2xl font-extrabold text-emerald-300 shadow-lg shadow-emerald-950/40 sm:h-16 sm:w-13 sm:text-3xl"
                  >
                    {c}
                  </span>
                ))}
              </div>

              <div className="mt-4 flex flex-wrap items-center justify-center gap-2.5">
                <button
                  type="button"
                  onClick={() => void handleCopyCode()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-900 px-3.5 py-2 text-xs font-semibold text-slate-200 transition hover:border-emerald-500/50 hover:text-white"
                >
                  {copied ? (
                    <>
                      <Check className="h-3.5 w-3.5 text-emerald-400" />
                      <span className="text-emerald-300">Copié !</span>
                    </>
                  ) : (
                    <>
                      <Copy className="h-3.5 w-3.5" />
                      <span>Copier le code</span>
                    </>
                  )}
                </button>

                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleRegenerateCode()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-900 px-3.5 py-2 text-xs font-semibold text-slate-300 transition hover:border-amber-500/50 hover:text-amber-300"
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  <span>Régénérer le code</span>
                </button>
              </div>
            </div>

            {/* Service Start / Stop Controls */}
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
              <button
                type="button"
                disabled={busy}
                onClick={() => void handleToggleService()}
                className={`inline-flex flex-1 items-center justify-center gap-2 rounded-2xl px-5 py-3 text-sm font-bold transition ${
                  portal.serviceRunning
                    ? 'border border-rose-500/40 bg-rose-500/15 text-rose-300 hover:bg-rose-500/25'
                    : 'bg-emerald-500 text-obsidian-950 shadow-lg shadow-emerald-500/25 hover:bg-emerald-400'
                }`}
              >
                {portal.serviceRunning ? (
                  <>
                    <Power className="h-4 w-4" />
                    <span>Couper le portail d’écoute</span>
                  </>
                ) : (
                  <>
                    <Play className="h-4 w-4" />
                    <span>Activer le portail d’écoute</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* SECURITY LOCK, SAS & LOGS */}
        <div className="space-y-5 md:col-span-5">
          {/* Cryptographic Pairing Lock Card */}
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-5 shadow-xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                {portal.paired ? (
                  <Lock className="h-4 w-4 text-emerald-400" />
                ) : (
                  <Unlock className="h-4 w-4 text-amber-400" />
                )}
                <h3 className="text-xs font-bold uppercase tracking-wider text-white">
                  Verrouillage Cryptographique
                </h3>
              </div>
              <span
                className={`rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
                  portal.paired
                    ? 'bg-emerald-500/15 text-emerald-300'
                    : 'bg-amber-500/15 text-amber-300'
                }`}
              >
                {portal.paired ? 'Poste Verrouillé' : 'Prêt à l’appairage'}
              </span>
            </div>

            {portal.paired ? (
              <div className="mt-3 space-y-3">
                <div className="rounded-xl border border-emerald-500/25 bg-obsidian-950 p-3.5 text-center">
                  <div className="text-[10px] uppercase tracking-wider text-slate-400">
                    Empreinte SAS Anti-MitM
                  </div>
                  <div className="mt-1 text-xl">{portal.sasSymbols}</div>
                  <div className="mt-0.5 font-mono text-base font-bold text-emerald-400">
                    {portal.sasCode}
                  </div>
                </div>
                <p className="text-[11px] text-slate-400">
                  Seul le navigateur détenant le jeton secret HKDF-SHA256 (`{portal.pairedTokenId?.slice(0, 8)}…`) peut réveiller la caméra.
                </p>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleResetPairing()}
                  className="w-full rounded-xl border border-slate-700 bg-obsidian-950 py-2 text-xs font-semibold text-slate-300 transition hover:border-amber-500/50 hover:text-amber-300"
                >
                  Réinitialiser l’appairage (Autoriser un autre poste)
                </button>
              </div>
            ) : (
              <p className="mt-2.5 text-xs text-slate-400">
                Saisissez le code à 6 caractères sur le Poste de Contrôle Web. Dès la première
                liaison, ce portail se verrouillera cryptographiquement sur votre navigateur.
              </p>
            )}

            {isNativeAndroid && (
              <div className="mt-4 space-y-2 border-t border-slate-800 pt-4 text-xs">
                <label className="flex cursor-pointer items-center justify-between">
                  <span className="text-slate-300">Démarrage auto au boot Android</span>
                  <input
                    type="checkbox"
                    checked={portal.autoStartOnBoot}
                    onChange={() => void handleToggleBootStart()}
                    className="h-4 w-4 rounded border-slate-700 bg-obsidian-950 text-emerald-500"
                  />
                </label>

                {!portal.permissions.batteryOptimizationIgnored && (
                  <button
                    type="button"
                    onClick={() => void handleRequestBatteryDoze()}
                    className="mt-2 flex w-full items-center justify-center gap-1.5 rounded-xl border border-amber-500/35 bg-amber-500/10 py-2 text-xs font-semibold text-amber-300"
                  >
                    <ShieldAlert className="h-3.5 w-3.5" />
                    <span>Désactiver l’optimisation batterie (Doze)</span>
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Audit Logs */}
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/90 p-5 shadow-xl">
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-300">
              Journal du Portail
            </h3>
            <div className="mt-3 max-h-48 space-y-1.5 overflow-y-auto font-mono text-[11px]">
              {portal.logs.length === 0 ? (
                <div className="text-slate-500">Aucun événement enregistré.</div>
              ) : (
                portal.logs.map((entry, idx) => (
                  <div
                    key={`${entry.timestamp}-${idx}`}
                    className="rounded-lg border border-slate-800/80 bg-obsidian-950/70 px-2.5 py-1.5 text-slate-300"
                  >
                    <span className="mr-1.5 text-slate-500">
                      {new Date(entry.timestamp).toLocaleTimeString('fr-FR')}
                    </span>
                    {entry.message}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
