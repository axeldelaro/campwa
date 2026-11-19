import React, { useEffect, useRef, useState } from 'react';
import {
  BatteryCharging,
  BookUser,
  Camera,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  Cpu,
  Eye,
  KeyRound,
  Lock,
  MapPin,
  MessageSquare,
  Mic,
  Monitor,
  Play,
  Plus,
  Power,
  Radio,
  RefreshCw,
  Share2,
  ShieldAlert,
  ShieldCheck,
  Sliders,
  Smartphone,
  Timer,
  Trash2,
  UserCheck,
  Users,
  Volume2,
  Wifi,
  XCircle,
  Zap,
} from 'lucide-react';
import { generateSecurePairingCode, normalizePairingCode } from '../crypto/cryptoEngine';
import { SentinelNative } from '../native/sentinelPlugin';
import { BrowserEmitterEngine } from '../services/browserEmitterEngine';
import type {
  AccessProfile,
  AccessProfileInput,
  GpsTrackingMode,
  NativePortalState,
} from '../types/sentinel';

interface AndroidEmitterViewProps {
  isNativeAndroid: boolean;
}

type EmitterSectionTab = 'portal' | 'passes' | 'friends';

const DURATION_PRESETS = [
  { label: '15 min', ms: 15 * 60 * 1000 },
  { label: '1 heure', ms: 60 * 60 * 1000 },
  { label: '4 heures', ms: 4 * 60 * 60 * 1000 },
  { label: '24 heures', ms: 24 * 60 * 60 * 1000 },
  { label: '7 jours', ms: 7 * 24 * 60 * 60 * 1000 },
] as const;

const WEB_APP_PUBLIC_URL = 'https://axeldelaro.github.io/campwa/';

function formatRemaining(expiresAt: number | null, nowMs: number): string {
  if (expiresAt === null) return 'Permanent (serveur allumé)';
  const diff = expiresAt - nowMs;
  if (diff <= 0) return 'Expiré';
  const totalSec = Math.floor(diff / 1000);
  const days = Math.floor(totalSec / 86400);
  const hours = Math.floor((totalSec % 86400) / 3600);
  const mins = Math.floor((totalSec % 3600) / 60);
  const secs = totalSec % 60;
  if (days > 0) return `Expire dans ${days}j ${hours}h`;
  if (hours > 0) return `Expire dans ${hours}h ${mins}m`;
  if (mins > 0) return `Expire dans ${mins}m ${secs}s`;
  return `Expire dans ${secs}s`;
}

export const AndroidEmitterView: React.FC<AndroidEmitterViewProps> = ({ isNativeAndroid }) => {
  const [portal, setPortal] = useState<NativePortalState | null>(null);
  const [section, setSection] = useState<EmitterSectionTab>('portal');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  // Pass / Friend creation form state
  const [formName, setFormName] = useState('');
  const [formCode, setFormCode] = useState('');
  const [durationPresetIdx, setDurationPresetIdx] = useState<number>(1);
  const [useCustomExpiry, setUseCustomExpiry] = useState(false);
  const [customExpiryLocal, setCustomExpiryLocal] = useState('');
  const [friendHasExpiry, setFriendHasExpiry] = useState(false);
  const [allowAudio, setAllowAudio] = useState(true);
  const [allowSwitchCamera, setAllowSwitchCamera] = useState(true);
  const [allowScreenShare, setAllowScreenShare] = useState(false);
  const [allowContacts, setAllowContacts] = useState(false);
  const [allowLocation, setAllowLocation] = useState(false);
  const [allowDeviceInfo, setAllowDeviceInfo] = useState(true);
  const [allowIntercom, setAllowIntercom] = useState(true);
  const [allowSmsRelay, setAllowSmsRelay] = useState(false);

  // Parental / Admin Background Protection state
  const [adminPinInput, setAdminPinInput] = useState('');
  const [adminUnlockInput, setAdminUnlockInput] = useState('');
  const [adminUnlocked, setAdminUnlocked] = useState(false);
  const [verifiedAdminPin, setVerifiedAdminPin] = useState('');
  const [adminError, setAdminError] = useState<string | null>(null);

  const webEngineRef = useRef<BrowserEmitterEngine | null>(null);

  useEffect(() => {
    const t = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

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

  const handleToggleScreenShare = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const currentlyActive = portal.globalShares.allowScreenShare && portal.globalShares.screenShareReady;
      if (isNativeAndroid) {
        const next = currentlyActive
          ? await SentinelNative.revokeScreenSharePermission()
          : await SentinelNative.requestScreenSharePermission();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = currentlyActive
          ? webEngineRef.current.revokeScreenShare()
          : await webEngineRef.current.requestScreenSharePermission();
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleContactsShare = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !portal.globalShares.allowContacts;
      if (isNativeAndroid) {
        if (targetVal && !portal.permissions.contacts) {
          const afterPerm = await SentinelNative.requestContactsPermission();
          setPortal(afterPerm);
        } else {
          const next = await SentinelNative.setGlobalShareSettings({ allowContacts: targetVal });
          setPortal(next);
        }
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ allowContacts: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleLocationShare = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !portal.globalShares.allowLocation;
      if (isNativeAndroid) {
        if (targetVal && !portal.permissions.location) {
          const afterPerm = await SentinelNative.requestLocationPermission();
          setPortal(afterPerm);
        } else {
          const next = await SentinelNative.setGlobalShareSettings({ allowLocation: targetVal });
          setPortal(next);
        }
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ allowLocation: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleLocationHistory = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !(portal.globalShares.allowLocationHistory ?? true);
      if (isNativeAndroid) {
        const next = await SentinelNative.setGlobalShareSettings({
          allowLocationHistory: targetVal,
        });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({
          allowLocationHistory: targetVal,
        });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleSetGpsTrackingMode = async (mode: GpsTrackingMode) => {
    if (!portal) return;
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.setGlobalShareSettings({ gpsTrackingMode: mode });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ gpsTrackingMode: mode });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleRequestBackgroundLocation = async () => {
    if (!isNativeAndroid) return;
    setBusy(true);
    try {
      const next = await SentinelNative.requestBackgroundLocationPermission();
      setPortal(next);
    } finally {
      setBusy(false);
    }
  };

  const handleClearLocationHistory = async () => {
    if (!isNativeAndroid) return;
    setBusy(true);
    try {
      const next = await SentinelNative.clearLocationHistory();
      setPortal(next);
    } finally {
      setBusy(false);
    }
  };

  const handleToggleDeviceInfoShare = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !portal.globalShares.allowDeviceInfo;
      if (isNativeAndroid) {
        const next = await SentinelNative.setGlobalShareSettings({ allowDeviceInfo: targetVal });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ allowDeviceInfo: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleIntercomShare = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !portal.globalShares.allowIntercom;
      if (isNativeAndroid) {
        const next = await SentinelNative.setGlobalShareSettings({ allowIntercom: targetVal });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ allowIntercom: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleSmsRelay = async () => {
    if (!portal) return;
    setBusy(true);
    try {
      const targetVal = !(portal.globalShares.allowSmsRelay ?? false);
      if (isNativeAndroid) {
        if (targetVal && !portal.permissions.sms) {
          const afterPerm = await SentinelNative.requestSmsPermission();
          setPortal(afterPerm);
        } else {
          const next = await SentinelNative.setGlobalShareSettings({ allowSmsRelay: targetVal });
          setPortal(next);
        }
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.setGlobalShareSettings({ allowSmsRelay: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleService = async () => {
    if (!portal) return;
    if (portal.serviceRunning && portal.adminProtectionEnabled && portal.hasAdminPin && !adminUnlocked) {
      setAdminError('Déverrouillez avec le code administrateur pour arrêter le service.');
      return;
    }
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = portal.serviceRunning
          ? await SentinelNative.stopPortalService({ adminPin: verifiedAdminPin })
          : await SentinelNative.startPortalService();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = portal.serviceRunning
          ? await webEngineRef.current.stopPortal()
          : await webEngineRef.current.startPortal();
        setPortal(next);
      }
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : 'Code administrateur requis.');
    } finally {
      setBusy(false);
    }
  };

  const handleEnableAdminProtection = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isNativeAndroid) return;
    setAdminError(null);
    const pin = adminPinInput.trim();
    if (pin.length < 4 || pin.length > 12) {
      setAdminError('Choisissez un code de 4 à 12 caractères.');
      return;
    }
    setBusy(true);
    try {
      const next = await SentinelNative.enableAdminProtection({ pin });
      setPortal(next);
      setVerifiedAdminPin(pin);
      setAdminPinInput('');
      setAdminUnlocked(false);
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : 'Activation impossible.');
    } finally {
      setBusy(false);
    }
  };

  const handleUnlockAdmin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isNativeAndroid) return;
    setAdminError(null);
    setBusy(true);
    try {
      const res = await SentinelNative.verifyAdminPin({ pin: adminUnlockInput.trim() });
      if (res.valid) {
        setVerifiedAdminPin(adminUnlockInput.trim());
        setAdminUnlocked(true);
        setAdminUnlockInput('');
      } else {
        setAdminError('Code administrateur incorrect.');
      }
    } catch {
      setAdminError('Vérification impossible.');
    } finally {
      setBusy(false);
    }
  };

  const handleDisableAdminProtection = async () => {
    if (!isNativeAndroid) return;
    setAdminError(null);
    setBusy(true);
    try {
      const next = await SentinelNative.disableAdminProtection({ pin: verifiedAdminPin });
      setPortal(next);
      setAdminUnlocked(false);
      setVerifiedAdminPin('');
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : 'Désactivation refusée.');
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

  const handleKickViewer = async () => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.kickActiveViewer();
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.kickActiveViewer('revoked');
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleCreateProfile = async (role: 'guest' | 'friend', e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    const chosenCode = normalizePairingCode(formCode) || generateSecurePairingCode();
    if (chosenCode.length !== 6) {
      setFormError('Le code fixe doit comporter exactement 6 caractères (ex: PAUL01 ou SOIR24).');
      return;
    }

    let expiresAt: number | null = null;
    if (role === 'guest' || (role === 'friend' && friendHasExpiry)) {
      if (useCustomExpiry && customExpiryLocal) {
        const parsedTs = new Date(customExpiryLocal).getTime();
        if (Number.isNaN(parsedTs) || parsedTs <= Date.now() + 60_000) {
          setFormError('Veuillez choisir une date/heure d’expiration future.');
          return;
        }
        expiresAt = parsedTs;
      } else {
        const preset = DURATION_PRESETS[durationPresetIdx] ?? DURATION_PRESETS[1];
        expiresAt = Date.now() + preset.ms;
      }
    }

    const input: AccessProfileInput = {
      name: formName.trim() || (role === 'friend' ? 'Nouvel Ami' : 'Pass Temporaire'),
      role,
      code: chosenCode,
      enabled: true,
      expiresAt,
      allowAudio,
      allowSwitchCamera,
      allowScreenShare,
      allowContacts,
      allowLocation,
      allowDeviceInfo,
      allowIntercom,
      allowSmsRelay,
    };

    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.upsertAccessProfile(input);
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.upsertAccessProfile(input);
        setPortal(next);
      }
      setFormName('');
      setFormCode('');
      setUseCustomExpiry(false);
      setCustomExpiryLocal('');
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Impossible d’enregistrer ce profil.');
    } finally {
      setBusy(false);
    }
  };

  const handleToggleProfile = async (prof: AccessProfile) => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.toggleAccessProfile({
          id: prof.id,
          enabled: !prof.enabled,
        });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.toggleAccessProfile(prof.id, !prof.enabled);
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleExtendPass = async (prof: AccessProfile, addMs: number) => {
    setBusy(true);
    try {
      const base = Math.max(Date.now(), prof.expiresAt ?? Date.now());
      const updatedInput: AccessProfileInput = {
        id: prof.id,
        name: prof.name,
        role: prof.role,
        code: prof.code,
        enabled: true,
        expiresAt: base + addMs,
        allowAudio: prof.allowAudio,
        allowSwitchCamera: prof.allowSwitchCamera,
        allowScreenShare: prof.allowScreenShare,
        allowContacts: prof.allowContacts,
        allowLocation: prof.allowLocation,
        allowDeviceInfo: prof.allowDeviceInfo,
        allowIntercom: prof.allowIntercom,
      };
      if (isNativeAndroid) {
        const next = await SentinelNative.upsertAccessProfile(updatedInput);
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.upsertAccessProfile(updatedInput);
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteProfile = async (id: string) => {
    setBusy(true);
    try {
      if (isNativeAndroid) {
        const next = await SentinelNative.deleteAccessProfile({ id });
        setPortal(next);
      } else if (webEngineRef.current) {
        const next = await webEngineRef.current.deleteAccessProfile(id);
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleCopyText = async (text: string, tag: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(tag);
      window.setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // Ignore
    }
  };

  const handleShareAccess = async (prof: AccessProfile) => {
    const baseUrl = isNativeAndroid
      ? WEB_APP_PUBLIC_URL
      : `${window.location.origin}${window.location.pathname}`;
    const directUrl = `${baseUrl}?code=${prof.code}`;
    const expiryLabel =
      prof.expiresAt !== null
        ? `Valide jusqu’au ${new Date(prof.expiresAt).toLocaleString('fr-FR')}`
        : 'Accès ami autorisé dès que le serveur est actif';

    if (navigator.share) {
      try {
        await navigator.share({
          title: `Accès SentinelCam — ${prof.name}`,
          text: `Code d’accès (${prof.name}) : ${prof.code}\n${expiryLabel}`,
          url: directUrl,
        });
        return;
      } catch {
        // Fallback to clipboard
      }
    }
    await handleCopyText(directUrl, `share-${prof.id}`);
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
            Vous gardez le contrôle absolu : activez à la carte ce que vous souhaitez partager
            (caméra, écran, contacts, localisation GPS) globalement et par profil ami / pass
            temporaire.
          </p>

          <div className="mt-5 space-y-2 rounded-2xl border border-slate-800 bg-obsidian-950 p-4 text-left text-xs text-slate-300">
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Accès Caméra, Écran, Contacts & GPS 100% au choix de l’utilisateur</span>
            </div>
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Codes fixes temporaires & Profils Amis révocables en 1 clic</span>
            </div>
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>Éco-Veille en tâche de fond + notification transparente</span>
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

  const allProfiles = portal.accessProfiles ?? [];
  const timedPasses = allProfiles.filter((p) => p.role === 'guest');
  const friendProfiles = allProfiles.filter((p) => p.role === 'friend');
  const shares = portal.globalShares ?? {
    allowScreenShare: false,
    allowContacts: false,
    allowLocation: false,
    allowDeviceInfo: true,
    allowIntercom: true,
    screenShareReady: false,
  };

  const renderPermissionCheckboxes = () => (
    <div className="mt-4 grid grid-cols-2 gap-2.5 rounded-2xl border border-slate-800/80 bg-obsidian-950/70 p-3.5 text-xs text-slate-300 sm:grid-cols-3">
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowAudio}
          onChange={(e) => setAllowAudio(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Mic className="h-3.5 w-3.5 text-emerald-400" />
        <span>Micro</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowSwitchCamera}
          onChange={(e) => setAllowSwitchCamera(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Camera className="h-3.5 w-3.5 text-cyan-400" />
        <span>Cam. arrière</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowScreenShare}
          onChange={(e) => setAllowScreenShare(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Monitor className="h-3.5 w-3.5 text-violet-400" />
        <span>Écran</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowContacts}
          onChange={(e) => setAllowContacts(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <BookUser className="h-3.5 w-3.5 text-amber-400" />
        <span>Contacts</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowLocation}
          onChange={(e) => setAllowLocation(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <MapPin className="h-3.5 w-3.5 text-rose-400" />
        <span>Position GPS</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowDeviceInfo}
          onChange={(e) => setAllowDeviceInfo(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Cpu className="h-3.5 w-3.5 text-slate-400" />
        <span>Infos tel</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowIntercom}
          onChange={(e) => setAllowIntercom(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Volume2 className="h-3.5 w-3.5 text-emerald-300" />
        <span>Talkie HP</span>
      </label>

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowSmsRelay}
          onChange={(e) => setAllowSmsRelay(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <MessageSquare className="h-3.5 w-3.5 text-orange-400" />
        <span>Relais SMS</span>
      </label>
    </div>
  );

  const renderProfileCard = (prof: AccessProfile) => {
    const isExpired = prof.expiresAt !== null && prof.expiresAt <= nowMs;
    const isActive = prof.enabled && !isExpired;

    return (
      <div
        key={prof.id}
        className={`rounded-2xl border p-4 transition ${
          isActive
            ? 'border-slate-800 bg-obsidian-950/90'
            : 'border-slate-800/60 bg-obsidian-950/40 opacity-75'
        }`}
      >
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="truncate text-sm font-bold text-white">{prof.name}</span>
              <span className="rounded-lg border border-emerald-500/35 bg-emerald-500/10 px-2 py-0.5 font-mono text-xs font-extrabold tracking-wider text-emerald-300">
                {prof.code}
              </span>
              {isExpired ? (
                <span className="rounded-full bg-rose-500/15 px-2 py-0.5 text-[10px] font-bold text-rose-300">
                  Expiré
                </span>
              ) : prof.enabled ? (
                <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-bold text-emerald-300">
                  Actif
                </span>
              ) : (
                <span className="rounded-full bg-amber-500/15 px-2 py-0.5 text-[10px] font-bold text-amber-300">
                  En pause
                </span>
              )}
            </div>

            <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[10px]">
              <span className="inline-flex items-center gap-1 rounded-md bg-slate-800/80 px-2 py-0.5 text-slate-300">
                <Clock className="h-3 w-3 text-cyan-400" />
                {formatRemaining(prof.expiresAt, nowMs)}
              </span>
              {prof.allowAudio && (
                <span className="rounded-md bg-emerald-500/10 px-1.5 py-0.5 text-emerald-300">
                  Micro
                </span>
              )}
              {prof.allowSwitchCamera && (
                <span className="rounded-md bg-cyan-500/10 px-1.5 py-0.5 text-cyan-300">
                  Cam. Arrière
                </span>
              )}
              {prof.allowScreenShare && (
                <span className="rounded-md bg-violet-500/15 px-1.5 py-0.5 text-violet-300">
                  Écran
                </span>
              )}
              {prof.allowContacts && (
                <span className="rounded-md bg-amber-500/15 px-1.5 py-0.5 text-amber-300">
                  Contacts
                </span>
              )}
              {prof.allowLocation && (
                <span className="rounded-md bg-rose-500/15 px-1.5 py-0.5 text-rose-300">
                  GPS
                </span>
              )}
              {prof.allowIntercom && (
                <span className="rounded-md bg-teal-500/15 px-1.5 py-0.5 text-teal-300">
                  Talkie HP
                </span>
              )}
              {prof.allowSmsRelay && (
                <span className="rounded-md bg-orange-500/15 px-1.5 py-0.5 text-orange-300">
                  SMS Urgence
                </span>
              )}
            </div>

            {prof.lastAccessAt && (
              <div className="mt-1.5 text-[10px] text-slate-500">
                Dernier accès : {new Date(prof.lastAccessAt).toLocaleString('fr-FR')}
              </div>
            )}
          </div>

          <button
            type="button"
            disabled={busy}
            onClick={() => void handleToggleProfile(prof)}
            className={`shrink-0 rounded-xl border px-2.5 py-1.5 text-[11px] font-bold transition ${
              prof.enabled
                ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300 hover:bg-emerald-500/25'
                : 'border-slate-700 bg-obsidian-900 text-slate-400 hover:text-white'
            }`}
          >
            {prof.enabled ? 'Suspendre' : 'Réactiver'}
          </button>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-slate-800/80 pt-3">
          <button
            type="button"
            onClick={() => void handleCopyText(prof.code, `code-${prof.id}`)}
            className="inline-flex items-center gap-1 rounded-xl border border-slate-700 bg-obsidian-900 px-2.5 py-1.5 text-[11px] font-semibold text-slate-200 hover:border-emerald-500/50"
          >
            {copiedId === `code-${prof.id}` ? (
              <>
                <Check className="h-3 w-3 text-emerald-400" />
                <span className="text-emerald-300">Code copié</span>
              </>
            ) : (
              <>
                <Copy className="h-3 w-3" />
                <span>Code</span>
              </>
            )}
          </button>

          <button
            type="button"
            onClick={() => void handleShareAccess(prof)}
            className="inline-flex items-center gap-1 rounded-xl border border-cyan-500/35 bg-cyan-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-cyan-300 hover:bg-cyan-500/20"
          >
            {copiedId === `share-${prof.id}` ? (
              <>
                <Check className="h-3 w-3" />
                <span>Lien copié !</span>
              </>
            ) : (
              <>
                <Share2 className="h-3 w-3" />
                <span>Lien direct</span>
              </>
            )}
          </button>

          {prof.role === 'guest' && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void handleExtendPass(prof, 60 * 60 * 1000)}
              className="inline-flex items-center gap-1 rounded-xl border border-slate-700 bg-obsidian-900 px-2.5 py-1.5 text-[11px] font-semibold text-slate-300 hover:border-emerald-500/40 hover:text-emerald-300"
            >
              <Plus className="h-3 w-3" />
              <span>+1h</span>
            </button>
          )}

          <button
            type="button"
            disabled={busy}
            onClick={() => void handleDeleteProfile(prof.id)}
            className="ml-auto inline-flex items-center gap-1 rounded-xl border border-rose-500/30 bg-rose-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-rose-300 hover:bg-rose-500/20"
          >
            <Trash2 className="h-3 w-3" />
            <span>Révoquer</span>
          </button>
        </div>
      </div>
    );
  };

  /* ==================== PERSISTENT PORTAL + USER-CONTROLLED SHARING ==================== */
  return (
    <div className="mx-auto max-w-xl px-4 py-6">
      {!isNativeAndroid && (
        <div className="mb-4 flex items-center gap-2.5 rounded-2xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-xs text-cyan-200">
          <Smartphone className="h-4 w-4 shrink-0 text-cyan-400" />
          <span>
            <strong>Mode Émetteur Web</strong> — Utilisez l’APK Android pour partager votre écran
            téléphone, vos contacts et votre GPS en tâche de fond.
          </span>
        </div>
      )}

      {/* Active Viewer Live Alert Banner */}
      {portal.portalState === 'streaming' && portal.activeViewerName && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-rose-500/45 bg-rose-500/15 px-4 py-3 shadow-lg">
          <div className="flex items-center gap-2.5">
            <Eye className="h-5 w-5 text-rose-400 animate-pulse" />
            <div className="text-xs">
              <div className="font-bold text-white">
                {portal.intercomActive
                  ? 'Haut-parleur Talkie actif'
                  : portal.mediaMode === 'screen'
                  ? 'Partage d’écran'
                  : 'Caméra'}{' '}
                en direct : {portal.activeViewerName}{' '}
                <span className="rounded bg-rose-500/30 px-1.5 py-0.5 text-[10px] uppercase text-rose-200">
                  {portal.activeViewerRole === 'owner'
                    ? 'Propriétaire'
                    : portal.activeViewerRole === 'friend'
                    ? 'Ami'
                    : 'Pass'}
                </span>
              </div>
              {portal.activeViewerExpiresAt && (
                <div className="text-[11px] text-rose-200/80">
                  {formatRemaining(portal.activeViewerExpiresAt, nowMs)}
                </div>
              )}
            </div>
          </div>
          <button
            type="button"
            disabled={busy}
            onClick={() => void handleKickViewer()}
            className="inline-flex items-center gap-1.5 rounded-xl bg-rose-500 px-3 py-1.5 text-xs font-extrabold text-white transition hover:bg-rose-400"
          >
            <XCircle className="h-3.5 w-3.5" />
            <span>Couper</span>
          </button>
        </div>
      )}

      {/* Navigation Tabs */}
      <div className="mb-4 grid grid-cols-3 gap-1.5 rounded-2xl border border-slate-800 bg-obsidian-900/90 p-1.5">
        <button
          type="button"
          onClick={() => {
            setSection('portal');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-bold transition ${
            section === 'portal'
              ? 'bg-emerald-500 text-obsidian-950 shadow'
              : 'text-slate-400 hover:text-white'
          }`}
        >
          <Radio className="h-3.5 w-3.5" />
          <span>Portail</span>
        </button>

        <button
          type="button"
          onClick={() => {
            setSection('passes');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-bold transition ${
            section === 'passes'
              ? 'bg-emerald-500 text-obsidian-950 shadow'
              : 'text-slate-400 hover:text-white'
          }`}
        >
          <Timer className="h-3.5 w-3.5" />
          <span>Pass ({timedPasses.length})</span>
        </button>

        <button
          type="button"
          onClick={() => {
            setSection('friends');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2.5 text-xs font-bold transition ${
            section === 'friends'
              ? 'bg-emerald-500 text-obsidian-950 shadow'
              : 'text-slate-400 hover:text-white'
          }`}
        >
          <Users className="h-3.5 w-3.5" />
          <span>Amis ({friendProfiles.length})</span>
        </button>
      </div>

      {/* ==================== TAB 1: MASTER PORTAL + USER SHARE CONTROLS ==================== */}
      {section === 'portal' && (
        <div className="space-y-4">
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-6 shadow-2xl">
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
                    ? portal.mediaMode === 'screen'
                      ? 'Écran partagé en direct'
                      : 'Caméra en direct'
                    : portal.serviceRunning
                    ? 'Éco-Veille active 24h/24'
                    : 'Serveur en pause'}
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
                {portal.mqttConnected ? 'Relais Connecté' : 'En attente'}
              </span>
            </div>

            <div className="mt-5 rounded-2xl border border-emerald-500/30 bg-obsidian-950 p-6 text-center shadow-inner">
              <div className="flex items-center justify-center gap-1.5 text-[11px] font-bold uppercase tracking-widest text-slate-400">
                <KeyRound className="h-3.5 w-3.5 text-emerald-400" />
                <span>Code Principal (Propriétaire)</span>
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

              <div className="mt-4 flex flex-wrap items-center justify-center gap-2.5">
                <button
                  type="button"
                  onClick={() => void handleCopyText(portal.pairingCode, 'master')}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-500 px-4 py-2 text-xs font-extrabold text-obsidian-950 transition hover:bg-emerald-400"
                >
                  {copiedId === 'master' ? (
                    <>
                      <Check className="h-3.5 w-3.5" />
                      <span>Copié !</span>
                    </>
                  ) : (
                    <>
                      <Copy className="h-3.5 w-3.5" />
                      <span>Copier</span>
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
                    <span>Couper le serveur</span>
                  </>
                ) : (
                  <>
                    <Play className="h-4 w-4" />
                    <span>Allumer le serveur</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* USER-CONTROLLED GRANULAR PERMISSION CENTER */}
          <div className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-5 shadow-2xl">
            <div className="flex items-center gap-2">
              <Sliders className="h-5 w-5 text-emerald-400" />
              <h2 className="text-sm font-extrabold text-white">
                Ce que vous autorisez à partager (Vous décidez)
              </h2>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              Rien n’est partagé sans votre accord explicite. Activez ou coupez chaque accès en 1
              clic ci-dessous.
            </p>

            <div className="mt-4 space-y-2.5">
              {/* Walkie-Talkie Loudspeaker Control */}
              <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-start gap-3">
                  <Volume2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-400" />
                  <div>
                    <div className="text-xs font-bold text-white">Haut-Parleur Talkie (1 sens)</div>
                    <div className="text-[11px] text-slate-400">
                      Permet de parler depuis le site Web directement sur le haut-parleur du téléphone.
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleIntercomShare()}
                  className={`shrink-0 rounded-xl border px-3 py-2 text-xs font-bold transition ${
                    shares.allowIntercom
                      ? 'border-emerald-500/40 bg-emerald-500/20 text-emerald-200'
                      : 'border-slate-700 bg-obsidian-900 text-slate-300'
                  }`}
                >
                  {shares.allowIntercom ? 'Activé' : 'Désactivé'}
                </button>
              </div>

              {/* Screen Share Control */}
              <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-start gap-3">
                  <Monitor className="mt-0.5 h-5 w-5 shrink-0 text-violet-400" />
                  <div>
                    <div className="text-xs font-bold text-white">Partage d’Écran en Direct</div>
                    <div className="text-[11px] text-slate-400">
                      {shares.allowScreenShare && shares.screenShareReady
                        ? 'Autorisé par Android — prêt à être visionné à distance.'
                        : 'Désactivé — demande votre validation système Android.'}
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleScreenShare()}
                  className={`shrink-0 rounded-xl px-3 py-2 text-xs font-bold transition ${
                    shares.allowScreenShare && shares.screenShareReady
                      ? 'border border-violet-500/40 bg-violet-500/20 text-violet-200 hover:bg-rose-500/20 hover:text-rose-200'
                      : 'bg-violet-500 text-white hover:bg-violet-400'
                  }`}
                >
                  {shares.allowScreenShare && shares.screenShareReady ? 'Actif (Couper)' : 'Autoriser'}
                </button>
              </div>

              {/* Contacts Share Control */}
              <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-start gap-3">
                  <BookUser className="mt-0.5 h-5 w-5 shrink-0 text-amber-400" />
                  <div>
                    <div className="text-xs font-bold text-white">Répertoire Contacts</div>
                    <div className="text-[11px] text-slate-400">
                      {shares.allowContacts && portal.permissions.contacts
                        ? 'Partage autorisé (lecture seule chiffrée E2EE).'
                        : 'Privé — aucun contact accessible à distance.'}
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleContactsShare()}
                  className={`shrink-0 rounded-xl border px-3 py-2 text-xs font-bold transition ${
                    shares.allowContacts && (portal.permissions.contacts || !isNativeAndroid)
                      ? 'border-amber-500/40 bg-amber-500/20 text-amber-200'
                      : 'border-slate-700 bg-obsidian-900 text-slate-300 hover:border-amber-400'
                  }`}
                >
                  {shares.allowContacts && (portal.permissions.contacts || !isNativeAndroid)
                    ? 'Activé'
                    : 'Activer'}
                </button>
              </div>

              {/* GPS Location & Offline Trail History Control */}
              <div className="rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <MapPin className="mt-0.5 h-5 w-5 shrink-0 text-rose-400" />
                    <div>
                      <div className="text-xs font-bold text-white">
                        Localisation & Historique GPS Continu
                      </div>
                      <div className="text-[11px] text-slate-400">
                        {shares.allowLocation && (portal.permissions.location || !isNativeAndroid)
                          ? 'Trace précise enregistrée hors-ligne (SQLite) avec mise en veille GPS automatique à l’arrêt.'
                          : 'Désactivé — position masquée et capteur GPS coupé.'}
                      </div>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleToggleLocationShare()}
                    className={`shrink-0 rounded-xl border px-3 py-2 text-xs font-bold transition ${
                      shares.allowLocation && (portal.permissions.location || !isNativeAndroid)
                        ? 'border-rose-500/40 bg-rose-500/20 text-rose-200'
                        : 'border-slate-700 bg-obsidian-900 text-slate-300 hover:border-rose-400'
                    }`}
                  >
                    {shares.allowLocation && (portal.permissions.location || !isNativeAndroid)
                      ? 'Activé'
                      : 'Activer'}
                  </button>
                </div>

                {shares.allowLocation && (portal.permissions.location || !isNativeAndroid) && (
                  <div className="mt-3 space-y-2.5 border-t border-slate-800/80 pt-3">
                    {/* Offline History Toggle + Live Stats */}
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <div className="text-[11px] font-bold text-slate-200">
                          Historique continu hors-ligne (30 jours)
                        </div>
                        <div className="text-[10px] text-slate-500">
                          {portal.gpsTodayPointsCount ?? 0} pts aujourd’hui •{' '}
                          {portal.gpsPointsCount ?? 0} pts stockés localement
                        </div>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => void handleToggleLocationHistory()}
                          className={`rounded-lg border px-2.5 py-1 text-[10px] font-bold transition ${
                            shares.allowLocationHistory !== false
                              ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-300'
                              : 'border-slate-700 bg-obsidian-900 text-slate-400'
                          }`}
                        >
                          {shares.allowLocationHistory !== false ? 'Journal ON' : 'Journal OFF'}
                        </button>
                        {isNativeAndroid && (portal.gpsPointsCount ?? 0) > 0 && (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => void handleClearLocationHistory()}
                            className="rounded-lg border border-slate-700 bg-obsidian-900 px-2 py-1 text-[10px] font-bold text-slate-400 hover:border-rose-500/40 hover:text-rose-300"
                            title="Effacer l’historique GPS local"
                          >
                            Vider
                          </button>
                        )}
                      </div>
                    </div>

                    {/* Battery-Smart Mode Selector */}
                    {shares.allowLocationHistory !== false && (
                      <div className="space-y-1.5">
                        <div className="flex flex-wrap items-center justify-between gap-1 text-[10px]">
                          <span className="font-bold text-slate-400">
                            Profil batterie (GPS coupé à l’arrêt + bouclier &lt;15%)
                          </span>
                          <span
                            className={`rounded-full px-2 py-0.5 font-mono font-bold ${
                              portal.gpsTrackerState === 'moving'
                                ? 'bg-emerald-500/20 text-emerald-300'
                                : portal.gpsTrackerState === 'stationary'
                                ? 'bg-sky-500/20 text-sky-300'
                                : portal.gpsTrackerState === 'battery_saver'
                                ? 'bg-amber-500/20 text-amber-300'
                                : 'bg-slate-800 text-slate-400'
                            }`}
                          >
                            {portal.gpsTrackerState === 'moving'
                              ? 'En mouvement'
                              : portal.gpsTrackerState === 'stationary'
                              ? 'Immobile • 0% GPS'
                              : portal.gpsTrackerState === 'battery_saver'
                              ? 'Éco Batterie'
                              : 'Prêt'}
                          </span>
                        </div>
                        <div className="grid grid-cols-3 gap-1.5">
                          {(
                            [
                              { id: 'eco', label: 'Ultra-Éco', sub: '20s / 20m' },
                              { id: 'balanced', label: 'Équilibré', sub: '10s / 12m' },
                              { id: 'precision', label: 'Précis', sub: '4s / 6m' },
                            ] as const
                          ).map((opt) => {
                            const active = (shares.gpsTrackingMode ?? 'eco') === opt.id;
                            return (
                              <button
                                key={opt.id}
                                type="button"
                                disabled={busy}
                                onClick={() => void handleSetGpsTrackingMode(opt.id)}
                                className={`rounded-xl border px-2 py-1.5 text-center transition ${
                                  active
                                    ? 'border-emerald-500/60 bg-emerald-500/15 text-emerald-200'
                                    : 'border-slate-800 bg-obsidian-900 text-slate-400 hover:border-slate-700 hover:text-slate-200'
                                }`}
                              >
                                <div className="text-[11px] font-bold">{opt.label}</div>
                                <div className="font-mono text-[9px] opacity-75">{opt.sub}</div>
                              </button>
                            );
                          })}
                        </div>

                        {isNativeAndroid && portal.permissions.backgroundLocation === false && (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => void handleRequestBackgroundLocation()}
                            className="mt-1 flex w-full items-center justify-center gap-1.5 rounded-xl border border-amber-500/35 bg-amber-500/10 px-3 py-2 text-[11px] font-bold text-amber-200 hover:bg-amber-500/20"
                          >
                            <Zap className="h-3.5 w-3.5" />
                            <span>Autoriser le GPS en arrière-plan (« Toujours autoriser »)</span>
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* Lost-Phone Emergency SMS Relay Control (Strictly Opt-In) */}
              <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-start gap-3">
                  <MessageSquare className="mt-0.5 h-5 w-5 shrink-0 text-orange-400" />
                  <div>
                    <div className="text-xs font-bold text-white">
                      Relais SMS d’Urgence (Téléphone perdu)
                    </div>
                    <div className="text-[11px] text-slate-400">
                      {shares.allowSmsRelay && (portal.permissions.sms || !isNativeAndroid)
                        ? 'Activé — vous pouvez envoyer un SMS d’urgence depuis la carte SIM (max 5 / 10 min, notifié & journalisé).'
                        : 'Désactivé — aucun SMS ne peut être envoyé à distance.'}
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleSmsRelay()}
                  className={`shrink-0 rounded-xl border px-3 py-2 text-xs font-bold transition ${
                    shares.allowSmsRelay && (portal.permissions.sms || !isNativeAndroid)
                      ? 'border-orange-500/40 bg-orange-500/20 text-orange-200'
                      : 'border-slate-700 bg-obsidian-900 text-slate-300 hover:border-orange-400'
                  }`}
                >
                  {shares.allowSmsRelay && (portal.permissions.sms || !isNativeAndroid)
                    ? 'Activé'
                    : 'Activer'}
                </button>
              </div>

              {/* System Device Info Control */}
              <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-800 bg-obsidian-950 p-3.5">
                <div className="flex items-start gap-3">
                  <Cpu className="mt-0.5 h-5 w-5 shrink-0 text-cyan-400" />
                  <div>
                    <div className="text-xs font-bold text-white">Infos Appareil & Stockage</div>
                    <div className="text-[11px] text-slate-400">
                      Modèle, version Android, batterie, réseau et espace libre.
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleDeviceInfoShare()}
                  className={`shrink-0 rounded-xl border px-3 py-2 text-xs font-bold transition ${
                    shares.allowDeviceInfo
                      ? 'border-cyan-500/40 bg-cyan-500/20 text-cyan-200'
                      : 'border-slate-700 bg-obsidian-900 text-slate-300'
                  }`}
                >
                  {shares.allowDeviceInfo ? 'Activé' : 'Désactivé'}
                </button>
              </div>
            </div>
          </div>

          {/* Parental / Administrator Background Protection Card */}
          {isNativeAndroid && (
            <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Lock className="h-4 w-4 text-emerald-400" />
                  <h2 className="text-sm font-semibold text-white">
                    Protection Administrateur
                  </h2>
                </div>
                <span
                  className={`rounded-md px-2 py-0.5 text-[10px] font-medium ${
                    portal.adminProtectionEnabled
                      ? 'bg-emerald-500/15 text-emerald-300'
                      : 'bg-white/[0.05] text-zinc-400'
                  }`}
                >
                  {portal.adminProtectionEnabled
                    ? portal.deviceAdminActive
                      ? 'Active (Admin Android)'
                      : 'Verrouillée par code'
                    : 'Désactivée'}
                </span>
              </div>

              <p className="mt-1.5 text-xs text-zinc-400">
                Maintient le service actif en arrière-plan et protège l’arrêt ou la désinstallation
                par un code administrateur (type contrôle parental).
              </p>

              {adminError && (
                <div className="mt-3 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
                  {adminError}
                </div>
              )}

              {!portal.adminProtectionEnabled ? (
                <form onSubmit={(e) => void handleEnableAdminProtection(e)} className="mt-3 flex gap-2">
                  <input
                    type="password"
                    inputMode="text"
                    value={adminPinInput}
                    onChange={(e) => setAdminPinInput(e.target.value)}
                    placeholder="Définir un code admin (4–12 car.)"
                    className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                  />
                  <button
                    type="submit"
                    disabled={busy || adminPinInput.trim().length < 4}
                    className="shrink-0 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
                  >
                    Activer
                  </button>
                </form>
              ) : !adminUnlocked ? (
                <form onSubmit={(e) => void handleUnlockAdmin(e)} className="mt-3 flex gap-2">
                  <input
                    type="password"
                    inputMode="text"
                    value={adminUnlockInput}
                    onChange={(e) => setAdminUnlockInput(e.target.value)}
                    placeholder="Code administrateur pour modifier ou arrêter"
                    className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                  />
                  <button
                    type="submit"
                    disabled={busy || !adminUnlockInput.trim()}
                    className="shrink-0 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
                  >
                    Déverrouiller
                  </button>
                </form>
              ) : (
                <div className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-2.5">
                  <span className="text-xs text-emerald-200">Session administrateur déverrouillée</span>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setAdminUnlocked(false)}
                      className="rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1 text-[11px] font-medium text-zinc-300 hover:text-white"
                    >
                      Verrouiller
                    </button>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void handleDisableAdminProtection()}
                      className="rounded-lg border border-rose-500/40 bg-rose-500/15 px-2.5 py-1 text-[11px] font-medium text-rose-200 hover:bg-rose-500/25"
                    >
                      Désactiver
                    </button>
                  </div>
                </div>
              )}

              {portal.logs.length > 0 && (
                <div className="mt-4 border-t border-white/[0.06] pt-3">
                  <div className="mb-1.5 text-[11px] font-medium text-zinc-500">Activité récente</div>
                  <div className="max-h-24 space-y-1 overflow-y-auto font-mono text-[10px]">
                    {portal.logs.slice(0, 6).map((entry, idx) => (
                      <div key={`${entry.timestamp}-${idx}`} className="flex items-start gap-2">
                        <span className="shrink-0 text-zinc-500">
                          {new Date(entry.timestamp).toLocaleTimeString('fr-FR', {
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </span>
                        <span className={entry.level === 'warn' ? 'text-amber-300' : 'text-zinc-300'}>
                          {entry.message}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* ==================== TAB 2: CUSTOM FIXED TIMED ACCESS CODES ==================== */}
      {section === 'passes' && (
        <div className="space-y-4">
          <form
            onSubmit={(e) => void handleCreateProfile('guest', e)}
            className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-5 shadow-2xl"
          >
            <div className="flex items-center gap-2">
              <Timer className="h-5 w-5 text-emerald-400" />
              <h2 className="text-sm font-extrabold text-white">
                Créer un Code Fixe Temporaire
              </h2>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              Choisissez un code fixe à 6 caractères, une durée précise et exactement quels accès
              accorder à cet invité.
            </p>

            <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="block text-[11px] font-semibold text-slate-400">
                  Nom du pass
                </label>
                <input
                  type="text"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder="Ex: Dépannage, Baby-sitter"
                  className="mt-1 w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2.5 text-xs text-white placeholder-slate-600 focus:border-emerald-400 focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-[11px] font-semibold text-slate-400">
                  Code fixe (6 caractères au choix)
                </label>
                <div className="mt-1 flex gap-1.5">
                  <input
                    type="text"
                    maxLength={6}
                    value={formCode}
                    onChange={(e) => setFormCode(normalizePairingCode(e.target.value))}
                    placeholder="EX: AIDE26"
                    className="w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2.5 font-mono text-sm font-extrabold uppercase tracking-widest text-emerald-300 placeholder-slate-600 focus:border-emerald-400 focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setFormCode(generateSecurePairingCode())}
                    className="shrink-0 rounded-xl border border-slate-700 bg-obsidian-950 px-3 text-slate-300 hover:border-emerald-500/50 hover:text-emerald-300"
                  >
                    <RefreshCw className="h-4 w-4" />
                  </button>
                </div>
              </div>
            </div>

            <div className="mt-4">
              <div className="flex items-center justify-between">
                <label className="text-[11px] font-semibold text-slate-400">
                  Durée d’autorisation
                </label>
                <button
                  type="button"
                  onClick={() => setUseCustomExpiry((v) => !v)}
                  className="text-[11px] font-semibold text-cyan-400 hover:underline"
                >
                  {useCustomExpiry ? 'Durée rapide' : 'Date/heure précise'}
                </button>
              </div>

              {!useCustomExpiry ? (
                <div className="mt-2 grid grid-cols-3 gap-2 sm:grid-cols-5">
                  {DURATION_PRESETS.map((preset, idx) => (
                    <button
                      key={preset.label}
                      type="button"
                      onClick={() => setDurationPresetIdx(idx)}
                      className={`rounded-xl border px-2 py-2 text-xs font-bold transition ${
                        durationPresetIdx === idx
                          ? 'border-emerald-400 bg-emerald-500/20 text-emerald-300'
                          : 'border-slate-800 bg-obsidian-950 text-slate-400 hover:text-white'
                      }`}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              ) : (
                <input
                  type="datetime-local"
                  value={customExpiryLocal}
                  onChange={(e) => setCustomExpiryLocal(e.target.value)}
                  className="mt-2 w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2.5 text-xs text-white focus:border-emerald-400 focus:outline-none"
                />
              )}
            </div>

            <div className="mt-4">
              <div className="text-[11px] font-semibold text-slate-400">
                Permissions accordées à ce pass :
              </div>
              {renderPermissionCheckboxes()}
            </div>

            {formError && (
              <div className="mt-3 rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
                {formError}
              </div>
            )}

            <button
              type="submit"
              disabled={busy}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-500 px-5 py-3 text-xs font-extrabold text-obsidian-950 shadow-lg shadow-emerald-500/20 transition hover:bg-emerald-400 disabled:opacity-50"
            >
              <Plus className="h-4 w-4" />
              <span>Activer ce Code Temporaire</span>
            </button>
          </form>

          <div className="space-y-2.5">
            {timedPasses.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-slate-800 bg-obsidian-900/50 p-6 text-center text-xs text-slate-500">
                Aucun pass temporaire actif.
              </div>
            ) : (
              timedPasses.map(renderProfileCard)
            )}
          </div>
        </div>
      )}

      {/* ==================== TAB 3: FRIEND PROFILES ==================== */}
      {section === 'friends' && (
        <div className="space-y-4">
          <form
            onSubmit={(e) => void handleCreateProfile('friend', e)}
            className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-5 shadow-2xl"
          >
            <div className="flex items-center gap-2">
              <UserCheck className="h-5 w-5 text-cyan-400" />
              <h2 className="text-sm font-extrabold text-white">
                Ajouter un Profil Ami Autorisé
              </h2>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              Choisissez un code fixe pour un proche et cochez les éléments auxquels il peut
              accéder lorsque le serveur est allumé.
            </p>

            <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="block text-[11px] font-semibold text-slate-400">
                  Prénom / Nom de l’ami
                </label>
                <input
                  type="text"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder="Ex: Paul, Marie, Famille"
                  className="mt-1 w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2.5 text-xs text-white placeholder-slate-600 focus:border-cyan-400 focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-[11px] font-semibold text-slate-400">
                  Code personnel fixe (6 car.)
                </label>
                <div className="mt-1 flex gap-1.5">
                  <input
                    type="text"
                    maxLength={6}
                    value={formCode}
                    onChange={(e) => setFormCode(normalizePairingCode(e.target.value))}
                    placeholder="EX: PAUL01"
                    className="w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2.5 font-mono text-sm font-extrabold uppercase tracking-widest text-cyan-300 placeholder-slate-600 focus:border-cyan-400 focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setFormCode(generateSecurePairingCode())}
                    className="shrink-0 rounded-xl border border-slate-700 bg-obsidian-950 px-3 text-slate-300 hover:border-cyan-400 hover:text-cyan-300"
                  >
                    <RefreshCw className="h-4 w-4" />
                  </button>
                </div>
              </div>
            </div>

            <div className="mt-4">
              <div className="text-[11px] font-semibold text-slate-400">
                Accès autorisés pour cet ami :
              </div>
              {renderPermissionCheckboxes()}
            </div>

            <label className="mt-3 flex cursor-pointer items-center gap-2 text-xs text-slate-300">
              <input
                type="checkbox"
                checked={friendHasExpiry}
                onChange={(e) => setFriendHasExpiry(e.target.checked)}
                className="h-4 w-4 rounded border-slate-700 bg-obsidian-950 text-emerald-500"
              />
              <span>Ajouter une date d’expiration (optionnel)</span>
            </label>

            {friendHasExpiry && (
              <div className="mt-2.5">
                <input
                  type="datetime-local"
                  value={customExpiryLocal}
                  onChange={(e) => {
                    setUseCustomExpiry(true);
                    setCustomExpiryLocal(e.target.value);
                  }}
                  className="w-full rounded-xl border border-slate-700 bg-obsidian-950 px-3 py-2 text-xs text-white focus:border-cyan-400 focus:outline-none"
                />
              </div>
            )}

            {formError && (
              <div className="mt-3 rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
                {formError}
              </div>
            )}

            <button
              type="submit"
              disabled={busy}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-cyan-400 to-emerald-400 px-5 py-3 text-xs font-extrabold text-obsidian-950 shadow-lg transition hover:brightness-110 disabled:opacity-50"
            >
              <UserCheck className="h-4 w-4" />
              <span>Enregistrer ce Profil Ami</span>
            </button>
          </form>

          <div className="space-y-2.5">
            {friendProfiles.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-slate-800 bg-obsidian-900/50 p-6 text-center text-xs text-slate-500">
                Aucun profil ami ajouté.
              </div>
            ) : (
              friendProfiles.map(renderProfileCard)
            )}
          </div>
        </div>
      )}
    </div>
  );
};
