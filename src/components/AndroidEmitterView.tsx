import React, { useEffect, useRef, useState } from 'react';
import {
  BatteryCharging,
  Check,
  CheckCircle2,
  Copy,
  KeyRound,
  Play,
  Power,
  Radio,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
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

          listenerHandle = await SentinelNative.addListener('portalStateChanged', (nextState) => {
            setPortal(nextState);
          });
        } catch (err) {
          console.error('Native bridge init error:', err);
        }
      };

      void initNative();

      const pollInterval = window.setInterval(async () => {
        try {
          const latest = await SentinelNative.getPortalState();
          setPortal(latest);
        } catch {
          // Ignore transient poll errors
        }
      }, 2500);

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

  /**
   * 1-Click Complete Setup: Requests permissions sequentially and immediately launches
   * the persistent Android ForegroundService + Doze battery exemption dialog.
   */
  const handleOneTapSetupAndStart = async () => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        await SentinelNative.requestAllPermissions();
        const started = await SentinelNative.completeOnboardingAndStart({
          autoStartOnBoot: true,
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
      <div className="flex min-h-[65vh] items-center justify-center">
        <RefreshCw className="h-8 w-8 animate-spin text-emerald-400" />
      </div>
    );
  }

  /* ==================== ONE-TIME 1-CLICK ONBOARDING ==================== */
  if (isNativeAndroid && !portal.onboardingCompleted) {
    return (
      <div className="mx-auto flex min-h-[78vh] max-w-md flex-col justify-center px-4 py-8">
        <div className="rounded-3xl border border-emerald-500/30 bg-obsidian-900/95 p-6 text-center shadow-2xl sm:p-8">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl border border-emerald-500/30 bg-emerald-500/15">
            <ShieldCheck className="h-8 w-8 text-emerald-400" />
          </div>

          <h1 className="mt-4 text-xl font-extrabold text-white">
            Activer SentinelCam en Continu
          </h1>
          <p className="mt-2 text-xs leading-relaxed text-slate-300">
            Une seule validation suffit : l’application maintiendra ensuite un pont chiffré
            permanent en arrière-plan (même écran verrouillé ou application fermée), avec une
            notification système transparente permettant de couper le portail à tout moment.
          </p>

          <div className="mt-5 space-y-2 rounded-2xl border border-slate-800 bg-obsidian-950 p-4 text-left text-xs text-slate-300">
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Accès Caméra & Microphone à distance</span>
            </div>
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Fonctionnement continu en tâche de fond (hors veille Doze)</span>
            </div>
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Reconnexion automatique Wi-Fi ↔ 4G/5G & après redémarrage</span>
            </div>
          </div>

          <button
            type="button"
            disabled={busy}
            onClick={() => void handleOneTapSetupAndStart()}
            className="mt-6 flex w-full items-center justify-center gap-2.5 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-400 px-6 py-4 text-sm font-extrabold text-obsidian-950 shadow-xl shadow-emerald-500/25 transition hover:brightness-110 disabled:opacity-50"
          >
            {busy ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" />
                <span>Activation en cours...</span>
              </>
            ) : (
              <>
                <Zap className="h-5 w-5" />
                <span>Autoriser & Activer Maintenant</span>
              </>
            )}
          </button>
        </div>
      </div>
    );
  }

  /* ==================== PERSISTENT BACKGROUND PORTAL SCREEN ==================== */
  return (
    <div className="mx-auto max-w-lg px-4 py-6">
      {!isNativeAndroid && (
        <div className="mb-4 flex items-center gap-2.5 rounded-2xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-xs text-cyan-200">
          <Smartphone className="h-4 w-4 shrink-0 text-cyan-400" />
          <span>
            <strong>Mode Test Navigateur</strong> — Installez l’APK Android pour le maintien
            permanent écran éteint.
          </span>
        </div>
      )}

      <div className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-6 shadow-2xl">
        {/* Live Status Pill */}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
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
                ? 'Caméra en direct'
                : portal.serviceRunning
                ? 'Actif en tâche de fond 24h/24'
                : 'Portail en pause'}
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
            {portal.mqttConnected ? 'Relais Connecté' : 'Reconnexion...'}
          </span>
        </div>

        {/* 6-CHAR PAIRING CODE */}
        <div className="mt-6 rounded-2xl border border-emerald-500/30 bg-obsidian-950 p-6 text-center shadow-inner">
          <div className="flex items-center justify-center gap-1.5 text-[11px] font-bold uppercase tracking-widest text-slate-400">
            <KeyRound className="h-3.5 w-3.5 text-emerald-400" />
            <span>Votre Code de Connexion</span>
          </div>

          <div className="mt-3 flex justify-center gap-2">
            {portal.pairingCode.split('').map((c, idx) => (
              <span
                key={idx}
                className="flex h-14 w-11 items-center justify-center rounded-xl border border-emerald-500/40 bg-obsidian-900 font-mono text-2xl font-extrabold text-emerald-300 shadow-lg sm:h-16 sm:w-12 sm:text-3xl"
              >
                {c}
              </span>
            ))}
          </div>

          <p className="mt-3 text-xs text-slate-400">
            Entrez ce code sur le site web pour vous connecter à tout moment, même lorsque cet écran
            est verrouillé ou que vous utilisez d’autres applications.
          </p>

          <div className="mt-4 flex flex-wrap items-center justify-center gap-2.5">
            <button
              type="button"
              onClick={() => void handleCopyCode()}
              className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-500 px-4 py-2 text-xs font-extrabold text-obsidian-950 transition hover:bg-emerald-400"
            >
              {copied ? (
                <>
                  <Check className="h-3.5 w-3.5" />
                  <span>Copié !</span>
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
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700 bg-obsidian-900 px-3.5 py-2 text-xs font-semibold text-slate-300 transition hover:text-white"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              <span>Nouveau code</span>
            </button>
          </div>
        </div>

        {/* Battery Optimization Reminder (if user skipped system dialog) */}
        {isNativeAndroid && !portal.permissions.batteryOptimizationIgnored && (
          <button
            type="button"
            onClick={() => void handleRequestBatteryDoze()}
            className="mt-4 flex w-full items-center justify-center gap-2 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-xs font-bold text-amber-300 transition hover:bg-amber-500/20"
          >
            <BatteryCharging className="h-4 w-4 shrink-0" />
            <span>Autoriser l’arrière-plan illimité (anti-veille batterie)</span>
          </button>
        )}

        {isNativeAndroid && !portal.permissions.camera && (
          <button
            type="button"
            onClick={() => void handleOneTapSetupAndStart()}
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-2xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-xs font-bold text-rose-300"
          >
            <ShieldAlert className="h-4 w-4 shrink-0" />
            <span>Autoriser l’accès Caméra manquant</span>
          </button>
        )}

        {/* Start / Pause Portal Button */}
        <div className="mt-5">
          <button
            type="button"
            disabled={busy}
            onClick={() => void handleToggleService()}
            className={`flex w-full items-center justify-center gap-2 rounded-2xl px-5 py-3.5 text-sm font-bold transition ${
              portal.serviceRunning
                ? 'border border-slate-700 bg-obsidian-950 text-slate-300 hover:border-rose-500/40 hover:text-rose-300'
                : 'bg-emerald-500 text-obsidian-950 shadow-lg shadow-emerald-500/25 hover:bg-emerald-400'
            }`}
          >
            {portal.serviceRunning ? (
              <>
                <Power className="h-4 w-4" />
                <span>Couper temporairement le portail</span>
              </>
            ) : (
              <>
                <Play className="h-4 w-4" />
                <span>Relancer le portail continu</span>
              </>
            )}
          </button>
        </div>

        {portal.sasCode && (
          <div className="mt-4 flex items-center justify-between rounded-xl border border-slate-800/80 bg-obsidian-950/60 px-3.5 py-2 text-[11px] text-slate-400">
            <span>Sécurité E2EE active</span>
            <span className="font-mono font-bold text-emerald-400">
              SAS {portal.sasCode} {portal.sasSymbols}
            </span>
          </div>
        )}
      </div>
    </div>
  );
};
