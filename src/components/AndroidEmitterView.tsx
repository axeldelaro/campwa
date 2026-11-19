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
  Image as ImageIcon,
  KeyRound,
  Lock,
  MapPin,
  MessageSquare,
  Mic,
  Monitor,
  Play,
  Plus,
  Power,
  RefreshCw,
  Share2,
  ShieldAlert,
  ShieldCheck,
  Sliders,
  Smartphone,
  Sparkles,
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

type EmitterSectionTab = 'status' | 'settings' | 'access';

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
  const [section, setSection] = useState<EmitterSectionTab>('status');
  const [accessSubTab, setAccessSubTab] = useState<'friends' | 'passes'>('friends');
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
  const [allowDeviceControl, setAllowDeviceControl] = useState(false);

  // Parental / Admin Background Protection state
  const [adminPinInput, setAdminPinInput] = useState('');
  const [adminUnlockInput, setAdminUnlockInput] = useState('');
  const [newAdminPinInput, setNewAdminPinInput] = useState('');
  const [adminUnlocked, setAdminUnlocked] = useState(false);
  const [verifiedAdminPin, setVerifiedAdminPin] = useState('');
  const [adminError, setAdminError] = useState<string | null>(null);
  const [adminNotice, setAdminNotice] = useState<string | null>(null);
  const [parentPairCode, setParentPairCode] = useState('');
  const [pairFeedback, setPairFeedback] = useState<string | null>(null);

  const isAdminLocked = Boolean(
    isNativeAndroid && portal?.adminProtectionEnabled && portal?.hasAdminPin && !adminUnlocked
  );

  const requireAdminUnlocked = (): boolean => {
    if (!isAdminLocked) return true;
    setAdminError('Code administrateur requis pour modifier ce réglage.');
    return false;
  };

  useEffect(() => {
    const handleVisibility = () => {
      if (document.hidden) {
        setAdminUnlocked(false);
        setVerifiedAdminPin('');
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);
    return () => document.removeEventListener('visibilitychange', handleVisibility);
  }, []);

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
      }, 4000);

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

  const handlePairWithParentCode = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isNativeAndroid) return;
    const clean = normalizePairingCode(parentPairCode);
    if (clean.length !== 6) {
      setPairFeedback('Saisissez le code à 6 caractères affiché sur le site Parent.');
      return;
    }
    setPairFeedback('Association en cours avec le Parent…');
    setBusy(true);
    try {
      const next = await SentinelNative.pairWithParentCode({ code: clean });
      setPortal(next);
      setParentPairCode('');
    } catch (err) {
      setPairFeedback(err instanceof Error ? err.message : 'Association impossible.');
    } finally {
      setBusy(false);
    }
  };

  const handleToggleGalleryShare = async () => {
    if (!portal || !requireAdminUnlocked()) return;
    setBusy(true);
    try {
      const targetVal = !(portal.globalShares.allowGallery !== false && portal.permissions.gallery);
      if (isNativeAndroid) {
        if (targetVal && !portal.permissions.gallery) {
          const next = await SentinelNative.requestGalleryPermission();
          setPortal(next);
        } else {
          const next = await SentinelNative.setGlobalShareSettings({ allowGallery: targetVal });
          setPortal(next);
        }
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleLocalAiShield = async (field: 'blurFaces' | 'blurNsfw') => {
    if (!portal || !requireAdminUnlocked() || !isNativeAndroid) return;
    setBusy(true);
    try {
      const nextVal = field === 'blurFaces' ? !portal.aiBlurFaces : !portal.aiBlurNsfw;
      const next = await SentinelNative.setAiShieldSettings({ [field]: nextVal });
      setPortal(next);
      if (nextVal && !next.aiShieldRunning && !next.permissions.screenShareReady) {
        const afterConsent = await SentinelNative.requestScreenSharePermission();
        setPortal(afterConsent);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleToggleScreenShare = async () => {
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!isNativeAndroid || !requireAdminUnlocked()) return;
    setBusy(true);
    try {
      const next = await SentinelNative.clearLocationHistory();
      setPortal(next);
    } finally {
      setBusy(false);
    }
  };

  const handleToggleDeviceInfoShare = async () => {
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!portal || !requireAdminUnlocked()) return;
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
    if (!portal || !requireAdminUnlocked()) return;
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

  const handleToggleDeviceControl = async () => {
    if (!portal || !requireAdminUnlocked()) return;
    setBusy(true);
    try {
      const targetVal = !(portal.globalShares.allowDeviceControl ?? true);
      if (isNativeAndroid) {
        const next = await SentinelNative.setGlobalShareSettings({ allowDeviceControl: targetVal });
        setPortal(next);
      }
    } finally {
      setBusy(false);
    }
  };

  const handleRequestUsageAccess = async () => {
    if (!isNativeAndroid || !requireAdminUnlocked()) return;
    await SentinelNative.requestUsageAccessPermission();
  };

  const handleRequestOverlay = async () => {
    if (!isNativeAndroid || !requireAdminUnlocked()) return;
    await SentinelNative.requestOverlayPermission();
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
    setAdminNotice(null);
    setBusy(true);
    try {
      const next = await SentinelNative.disableAdminProtection({ pin: verifiedAdminPin });
      setPortal(next);
      setAdminUnlocked(false);
      setVerifiedAdminPin('');
      setNewAdminPinInput('');
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : 'Désactivation refusée.');
    } finally {
      setBusy(false);
    }
  };

  const handleUpdateAdminPin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isNativeAndroid || !adminUnlocked) return;
    setAdminError(null);
    setAdminNotice(null);
    const nextPin = newAdminPinInput.trim();
    if (nextPin.length < 4 || nextPin.length > 12) {
      setAdminError('Le nouveau code doit contenir entre 4 et 12 caractères.');
      return;
    }
    setBusy(true);
    try {
      const next = await SentinelNative.updateAdminPin({
        currentPin: verifiedAdminPin,
        newPin: nextPin,
      });
      setPortal(next);
      setVerifiedAdminPin(nextPin);
      setNewAdminPinInput('');
      setAdminNotice('Code administrateur mis à jour.');
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : 'Modification refusée.');
    } finally {
      setBusy(false);
    }
  };

  const handleRegenerateCode = async () => {
    if (!requireAdminUnlocked()) return;
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
    if (!requireAdminUnlocked()) return;
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
    if (!requireAdminUnlocked()) {
      setFormError('Déverrouillez la Protection Administrateur sur l’onglet Portail pour gérer les accès.');
      return;
    }
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
      allowDeviceControl,
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
    if (!requireAdminUnlocked()) {
      setFormError('Code administrateur requis pour modifier un profil.');
      return;
    }
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
    if (!requireAdminUnlocked()) {
      setFormError('Code administrateur requis pour prolonger un accès.');
      return;
    }
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
        allowSmsRelay: prof.allowSmsRelay,
        allowDeviceControl: prof.allowDeviceControl,
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
    if (!requireAdminUnlocked()) {
      setFormError('Code administrateur requis pour révoquer un profil.');
      return;
    }
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

      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={allowDeviceControl}
          onChange={(e) => setAllowDeviceControl(e.target.checked)}
          className="h-4 w-4 rounded border-slate-700 bg-obsidian-900 text-emerald-500"
        />
        <Lock className="h-3.5 w-3.5 text-sky-400" />
        <span>Parent / Contrôle</span>
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
                  SMS
                </span>
              )}
              {prof.allowDeviceControl && (
                <span className="rounded-md bg-sky-500/15 px-1.5 py-0.5 text-sky-300">
                  Parent / Contrôle
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

      {/* Clean 3-Tab Parental Device Navigation */}
      <div className="mb-4 grid grid-cols-3 gap-1.5 rounded-2xl border border-white/[0.08] bg-zinc-900/80 p-1.5">
        <button
          type="button"
          onClick={() => {
            setSection('status');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition ${
            section === 'status'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:text-white'
          }`}
        >
          <ShieldCheck className="h-3.5 w-3.5" />
          <span>Statut</span>
        </button>

        <button
          type="button"
          onClick={() => {
            setSection('settings');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition ${
            section === 'settings'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:text-white'
          }`}
        >
          <Sliders className="h-3.5 w-3.5" />
          <span>Réglages</span>
        </button>

        <button
          type="button"
          onClick={() => {
            setSection('access');
            setFormError(null);
          }}
          className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-xs font-medium transition ${
            section === 'access'
              ? 'bg-white text-zinc-950'
              : 'text-zinc-400 hover:text-white'
          }`}
        >
          <Users className="h-3.5 w-3.5" />
          <span>Accès ({allProfiles.length})</span>
        </button>
      </div>

      {/* ==================== TAB 1: STATUS, PAIRING CODE & ADMIN PROTECTION ==================== */}
      {section === 'status' && (
        <div className="space-y-4">
          <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span
                  className={`h-2 w-2 rounded-full ${
                    portal.portalState === 'streaming'
                      ? 'bg-rose-500 animate-pulse'
                      : portal.serviceRunning
                      ? 'bg-emerald-400'
                      : 'bg-zinc-600'
                  }`}
                />
                <span className="text-sm font-semibold text-white">
                  {portal.portalState === 'streaming'
                    ? portal.mediaMode === 'screen'
                      ? 'Écran partagé'
                      : 'Direct en cours'
                    : portal.serviceRunning
                    ? portal.parentManaged
                      ? 'Géré par le Parent'
                      : 'Protection active'
                    : 'Service en pause'}
                </span>
              </div>

              <span
                className={`inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 font-mono text-[11px] ${
                  portal.mqttConnected
                    ? 'bg-emerald-500/15 text-emerald-300'
                    : 'bg-amber-500/15 text-amber-300'
                }`}
              >
                <Wifi className="h-3 w-3" />
                {portal.mqttConnected ? 'Connecté' : 'Hors ligne'}
              </span>
            </div>

            {/* Parent-Initiated Pairing Card (Enter code generated on Parent Website) */}
            {isNativeAndroid && (
              <div className="mt-4 rounded-xl border border-white/[0.07] bg-zinc-950 p-3.5">
                <div className="flex items-center justify-between gap-2 text-xs">
                  <span className="font-medium text-white">
                    {portal.parentManaged ? 'Appareil associé au Parent' : 'Associer au Parent'}
                  </span>
                  {portal.parentManaged && (
                    <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-[10px] font-medium text-emerald-300">
                      Lié sans perte
                    </span>
                  )}
                </div>
                {(!portal.parentManaged || adminUnlocked) && (
                  <form onSubmit={(e) => void handlePairWithParentCode(e)} className="mt-2.5 flex gap-2">
                    <input
                      type="text"
                      maxLength={6}
                      value={parentPairCode}
                      onChange={(e) => setParentPairCode(normalizePairingCode(e.target.value))}
                      placeholder="Code généré sur le site Parent (6 car.)"
                      className="min-w-0 flex-1 rounded-lg border border-white/10 bg-zinc-900 px-3 py-1.5 font-mono text-xs uppercase tracking-widest text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                    />
                    <button
                      type="submit"
                      disabled={busy || parentPairCode.length !== 6}
                      className="shrink-0 rounded-lg bg-white px-3.5 py-1.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-35"
                    >
                      {portal.pairStatus === 'pairing' ? '…' : 'Associer'}
                    </button>
                  </form>
                )}
                {(pairFeedback || portal.pairStatus === 'pairing' || portal.pairStatus === 'paired') && (
                  <div className="mt-2 text-[11px] text-emerald-300">
                    {portal.pairStatus === 'pairing'
                      ? 'Synchronisation chiffrée avec l’Espace Parent…'
                      : portal.pairStatus === 'paired'
                      ? 'Association confirmée — appareil désormais piloté par le Parent.'
                      : pairFeedback}
                  </div>
                )}
              </div>
            )}

            <div className="mt-3.5 rounded-xl border border-white/[0.07] bg-zinc-950 p-4 text-center">
              <div className="flex items-center justify-center gap-1.5 text-[11px] font-medium text-zinc-400">
                <KeyRound className="h-3.5 w-3.5 text-zinc-400" />
                <span>Code de connexion Parent</span>
              </div>

              <div className="mt-3 flex justify-center gap-1.5">
                {portal.pairingCode.split('').map((c, idx) => (
                  <span
                    key={idx}
                    className="flex h-12 w-10 items-center justify-center rounded-lg border border-white/10 bg-zinc-900 font-mono text-xl font-semibold text-white sm:h-14 sm:w-11 sm:text-2xl"
                  >
                    {c}
                  </span>
                ))}
              </div>

              <div className="mt-3.5 flex flex-wrap items-center justify-center gap-2">
                <button
                  type="button"
                  onClick={() => void handleCopyText(portal.pairingCode, 'master')}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-white px-4 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
                >
                  {copiedId === 'master' ? (
                    <>
                      <Check className="h-3.5 w-3.5" />
                      <span>Copié</span>
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
                  className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-900 px-3 py-1.5 text-xs font-medium text-zinc-300 transition hover:text-white"
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  <span>Renouveler</span>
                </button>
              </div>
            </div>

            {/* Compact Setup Checklist (ONLY shown if any permission is missing) */}
            {isNativeAndroid &&
              (!portal.permissions.batteryOptimizationIgnored ||
                !portal.permissions.usageAccess ||
                !portal.permissions.overlay ||
                !portal.permissions.gallery ||
                !portal.permissions.camera ||
                ((portal.aiBlurFaces || portal.aiBlurNsfw) && !portal.aiShieldRunning)) && (
                <div className="mt-3.5 space-y-1.5">
                  {(portal.aiBlurFaces || portal.aiBlurNsfw) && !portal.aiShieldRunning && (
                    <button
                      type="button"
                      onClick={() => void handleToggleScreenShare()}
                      className="flex w-full items-center justify-between rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200"
                    >
                      <span className="inline-flex items-center gap-1.5">
                        <Sparkles className="h-3.5 w-3.5" />
                        Démarrer le Filtre IA (Visages / NSFW)
                      </span>
                      <span className="font-semibold">Autoriser →</span>
                    </button>
                  )}
                  {!portal.permissions.usageAccess && (
                    <button
                      type="button"
                      onClick={() => void handleRequestUsageAccess()}
                      className="flex w-full items-center justify-between rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200"
                    >
                      <span>Autoriser le Temps d’écran</span>
                      <span className="font-semibold">Configurer →</span>
                    </button>
                  )}
                  {!portal.permissions.overlay && (
                    <button
                      type="button"
                      onClick={() => void handleRequestOverlay()}
                      className="flex w-full items-center justify-between rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200"
                    >
                      <span>Autoriser l’écran de blocage</span>
                      <span className="font-semibold">Configurer →</span>
                    </button>
                  )}
                  {!portal.permissions.gallery && (
                    <button
                      type="button"
                      onClick={() => void handleToggleGalleryShare()}
                      className="flex w-full items-center justify-between rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200"
                    >
                      <span className="inline-flex items-center gap-1.5">
                        <ImageIcon className="h-3.5 w-3.5" />
                        Autoriser la Galerie (hors dossiers masqués)
                      </span>
                      <span className="font-semibold">Autoriser →</span>
                    </button>
                  )}
                  {!portal.permissions.batteryOptimizationIgnored && (
                    <button
                      type="button"
                      onClick={() => void handleRequestBatteryDoze()}
                      className="flex w-full items-center justify-between rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200"
                    >
                      <span className="inline-flex items-center gap-1.5">
                        <BatteryCharging className="h-3.5 w-3.5" />
                        Arrière-plan illimité
                      </span>
                      <span className="font-semibold">Autoriser →</span>
                    </button>
                  )}
                  {!portal.permissions.camera && (
                    <button
                      type="button"
                      onClick={() => void handleOneTapSetupAndStart()}
                      className="flex w-full items-center justify-between rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-200"
                    >
                      <span className="inline-flex items-center gap-1.5">
                        <ShieldAlert className="h-3.5 w-3.5" />
                        Autorisation caméra
                      </span>
                      <span className="font-semibold">Autoriser →</span>
                    </button>
                  )}
                </div>
              )}

            <div className="mt-4">
              <button
                type="button"
                disabled={busy}
                onClick={() => void handleToggleService()}
                className={`flex w-full items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-xs font-semibold transition ${
                  portal.serviceRunning
                    ? 'border border-white/10 bg-zinc-950 text-zinc-300 hover:border-rose-500/40 hover:text-rose-300'
                    : 'bg-white text-zinc-950 hover:bg-zinc-200'
                }`}
              >
                {portal.serviceRunning ? (
                  <>
                    <Power className="h-3.5 w-3.5" />
                    <span>Mettre en pause</span>
                  </>
                ) : (
                  <>
                    <Play className="h-3.5 w-3.5" />
                    <span>Activer la protection</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ==================== TAB 2: SETTINGS (PERMISSIONS & LOGS) ==================== */}
      {section === 'settings' && (
        <div className="space-y-4">
          {/* CLEAN COMPACT PERMISSION LIST */}
          <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/75 p-4">
            <div className="flex items-center justify-between pb-2">
              <div className="flex items-center gap-2">
                <Sliders className="h-4 w-4 text-emerald-400" />
                <h2 className="text-sm font-semibold text-white">Autorisations</h2>
              </div>
              <span className="text-[11px] text-zinc-500">Contrôle local</span>
            </div>

            <div className="divide-y divide-white/[0.06]">
              {/* Screen Time & Remote Control */}
              {isNativeAndroid && (
                <div className="py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2.5">
                      <Lock className="h-4 w-4 shrink-0 text-sky-400" />
                      <div>
                        <div className="text-xs font-medium text-white">Contrôle & Temps d’écran</div>
                        <div className="text-[11px] text-zinc-500">
                          {shares.allowDeviceControl !== false
                            ? (portal.activeAppLocksCount ?? 0) > 0
                              ? `${portal.activeAppLocksCount} appli(s) verrouillée(s)`
                              : 'Verrouillage, quotas, couvre-feu'
                            : 'Désactivé'}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void handleToggleDeviceControl()}
                      className={`shrink-0 rounded-lg border px-2.5 py-1 text-[11px] font-medium transition ${
                        shares.allowDeviceControl !== false
                          ? 'border-sky-500/40 bg-sky-500/20 text-sky-200'
                          : 'border-white/10 bg-zinc-950 text-zinc-400'
                      }`}
                    >
                      {shares.allowDeviceControl !== false ? 'Actif' : 'Inactif'}
                    </button>
                  </div>

                  {shares.allowDeviceControl !== false &&
                    (!portal.permissions.usageAccess || !portal.permissions.overlay) && (
                      <div className="mt-2.5 grid grid-cols-2 gap-2">
                        <button
                          type="button"
                          onClick={() => void handleRequestUsageAccess()}
                          className={`flex items-center justify-between rounded-lg border px-2.5 py-1.5 text-[11px] font-medium ${
                            portal.permissions.usageAccess
                              ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                              : 'border-amber-500/35 bg-amber-500/10 text-amber-200'
                          }`}
                        >
                          <span>Temps d’écran</span>
                          <span>{portal.permissions.usageAccess ? 'OK' : 'Autoriser'}</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleRequestOverlay()}
                          className={`flex items-center justify-between rounded-lg border px-2.5 py-1.5 text-[11px] font-medium ${
                            portal.permissions.overlay
                              ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                              : 'border-amber-500/35 bg-amber-500/10 text-amber-200'
                          }`}
                        >
                          <span>Écran blocage</span>
                          <span>{portal.permissions.overlay ? 'OK' : 'Autoriser'}</span>
                        </button>
                      </div>
                    )}
                </div>
              )}

              {/* GPS Location & Offline History */}
              <div className="py-3">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2.5">
                    <MapPin className="h-4 w-4 shrink-0 text-rose-400" />
                    <div>
                      <div className="text-xs font-medium text-white">GPS & Zones de sécurité</div>
                      <div className="text-[11px] text-zinc-500">
                        {shares.allowLocation && (portal.permissions.location || !isNativeAndroid)
                          ? `${portal.gpsTodayPointsCount ?? 0} pts aujourd’hui · ${
                              portal.gpsTrackerState === 'stationary' ? 'Immobile (0% GPS)' : 'Actif'
                            }`
                          : 'Désactivé'}
                      </div>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleToggleLocationShare()}
                    className={`shrink-0 rounded-lg border px-2.5 py-1 text-[11px] font-medium transition ${
                      shares.allowLocation && (portal.permissions.location || !isNativeAndroid)
                        ? 'border-rose-500/40 bg-rose-500/20 text-rose-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-400'
                    }`}
                  >
                    {shares.allowLocation && (portal.permissions.location || !isNativeAndroid) ? 'Actif' : 'Inactif'}
                  </button>
                </div>

                {shares.allowLocation && (portal.permissions.location || !isNativeAndroid) && (
                  <div className="mt-2.5 space-y-2">
                    <div className="grid grid-cols-3 gap-1.5">
                      {(
                        [
                          { id: 'eco', label: 'Éco' },
                          { id: 'balanced', label: 'Équilibré' },
                          { id: 'precision', label: 'Précis' },
                        ] as const
                      ).map((opt) => (
                        <button
                          key={opt.id}
                          type="button"
                          disabled={busy}
                          onClick={() => void handleSetGpsTrackingMode(opt.id)}
                          className={`rounded-lg border py-1 text-[11px] font-medium transition ${
                            (shares.gpsTrackingMode ?? 'eco') === opt.id
                              ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-200'
                              : 'border-white/[0.07] bg-zinc-950 text-zinc-400'
                          }`}
                        >
                          {opt.label}
                        </button>
                      ))}
                    </div>
                    <div className="flex items-center justify-between gap-2 text-[11px]">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleToggleLocationHistory()}
                        className="text-zinc-400 hover:text-white"
                      >
                        Historique 30j :{' '}
                        <strong className={shares.allowLocationHistory !== false ? 'text-emerald-300' : 'text-zinc-500'}>
                          {shares.allowLocationHistory !== false ? 'ON' : 'OFF'}
                        </strong>
                      </button>
                      {isNativeAndroid && (portal.gpsPointsCount ?? 0) > 0 && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => void handleClearLocationHistory()}
                          className="text-zinc-500 hover:text-rose-300"
                        >
                          Vider ({portal.gpsPointsCount})
                        </button>
                      )}
                    </div>
                    {isNativeAndroid && portal.permissions.backgroundLocation === false && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleRequestBackgroundLocation()}
                        className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-amber-500/35 bg-amber-500/10 py-1.5 text-[11px] font-medium text-amber-200"
                      >
                        <Zap className="h-3 w-3" />
                        <span>Autoriser GPS en arrière-plan</span>
                      </button>
                    )}
                  </div>
                )}
              </div>

              {/* Walkie-Talkie */}
              <div className="flex items-center justify-between gap-3 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Volume2 className="h-4 w-4 shrink-0 text-emerald-400" />
                  <span className="text-xs font-medium text-white">Haut-parleur (Talkie)</span>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleIntercomShare()}
                  className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    shares.allowIntercom
                      ? 'border-emerald-500/40 bg-emerald-500/20 text-emerald-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {shares.allowIntercom ? 'Actif' : 'Inactif'}
                </button>
              </div>

              {/* Screen Share */}
              <div className="flex items-center justify-between gap-3 py-2.5">
                <div className="flex items-center gap-2.5">
                  <Monitor className="h-4 w-4 shrink-0 text-violet-400" />
                  <span className="text-xs font-medium text-white">Partage d’écran</span>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleScreenShare()}
                  className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    shares.allowScreenShare && shares.screenShareReady
                      ? 'border-violet-500/40 bg-violet-500/20 text-violet-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-300'
                  }`}
                >
                  {shares.allowScreenShare && shares.screenShareReady ? 'Actif' : 'Autoriser'}
                </button>
              </div>

              {/* AI Face & NSFW Blur Shield */}
              {isNativeAndroid && (
                <div className="py-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2.5">
                      <Sparkles className="h-4 w-4 shrink-0 text-emerald-400" />
                      <div>
                        <div className="text-xs font-medium text-white">Filtre IA Visages & NSFW</div>
                        <div className="text-[11px] text-zinc-500">
                          {portal.aiShieldRunning ? 'Floutage local actif' : 'IA embarquée (ML Kit + NudeNet)'}
                        </div>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleToggleLocalAiShield('blurFaces')}
                        className={`rounded-lg border px-2 py-1 text-[11px] font-medium ${
                          portal.aiBlurFaces
                            ? 'border-emerald-500/40 bg-emerald-500/20 text-emerald-200'
                            : 'border-white/10 bg-zinc-950 text-zinc-400'
                        }`}
                      >
                        Visages
                      </button>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleToggleLocalAiShield('blurNsfw')}
                        className={`rounded-lg border px-2 py-1 text-[11px] font-medium ${
                          portal.aiBlurNsfw
                            ? 'border-emerald-500/40 bg-emerald-500/20 text-emerald-200'
                            : 'border-white/10 bg-zinc-950 text-zinc-400'
                        }`}
                      >
                        NSFW
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Gallery (Public Folders Only) */}
              {isNativeAndroid && (
                <div className="flex items-center justify-between gap-3 py-2.5">
                  <div className="flex items-center gap-2.5">
                    <ImageIcon className="h-4 w-4 shrink-0 text-zinc-300" />
                    <div>
                      <div className="text-xs font-medium text-white">Galerie photos</div>
                      <div className="text-[11px] text-zinc-500">Hors dossiers masqués et corbeille</div>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void handleToggleGalleryShare()}
                    className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                      shares.allowGallery !== false && portal.permissions.gallery
                        ? 'border-emerald-500/40 bg-emerald-500/20 text-emerald-200'
                        : 'border-white/10 bg-zinc-950 text-zinc-400'
                    }`}
                  >
                    {shares.allowGallery !== false && portal.permissions.gallery ? 'Actif' : 'Activer'}
                  </button>
                </div>
              )}

              {/* Contacts */}
              <div className="flex items-center justify-between gap-3 py-2.5">
                <div className="flex items-center gap-2.5">
                  <BookUser className="h-4 w-4 shrink-0 text-amber-400" />
                  <span className="text-xs font-medium text-white">Contacts</span>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleContactsShare()}
                  className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    shares.allowContacts && (portal.permissions.contacts || !isNativeAndroid)
                      ? 'border-amber-500/40 bg-amber-500/20 text-amber-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {shares.allowContacts && (portal.permissions.contacts || !isNativeAndroid) ? 'Actif' : 'Inactif'}
                </button>
              </div>

              {/* SMS Relay */}
              <div className="flex items-center justify-between gap-3 py-2.5">
                <div className="flex items-center gap-2.5">
                  <MessageSquare className="h-4 w-4 shrink-0 text-orange-400" />
                  <span className="text-xs font-medium text-white">Envoi de SMS (SIM)</span>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleSmsRelay()}
                  className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    shares.allowSmsRelay && (portal.permissions.sms || !isNativeAndroid)
                      ? 'border-orange-500/40 bg-orange-500/20 text-orange-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {shares.allowSmsRelay && (portal.permissions.sms || !isNativeAndroid) ? 'Actif' : 'Inactif'}
                </button>
              </div>

              {/* Device Info */}
              <div className="flex items-center justify-between gap-3 pt-2.5">
                <div className="flex items-center gap-2.5">
                  <Cpu className="h-4 w-4 shrink-0 text-cyan-400" />
                  <span className="text-xs font-medium text-white">Infos appareil</span>
                </div>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void handleToggleDeviceInfoShare()}
                  className={`rounded-lg border px-2.5 py-1 text-[11px] font-medium ${
                    shares.allowDeviceInfo
                      ? 'border-cyan-500/40 bg-cyan-500/20 text-cyan-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400'
                  }`}
                >
                  {shares.allowDeviceInfo ? 'Actif' : 'Inactif'}
                </button>
              </div>
            </div>
          </div>

        </div>
      )}

      {/* ==================== ADMIN PROTECTION (SHOWN ON STATUS TAB FOR INSTANT ACCESS) ==================== */}
      {section === 'status' && isNativeAndroid && (
        <div className="mt-4">
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
                Protège l’arrêt, les réglages et la désinstallation par code administrateur.
              </p>

              {(portal.curfewEnabled ||
                portal.blockSettingsAndStore ||
                portal.lostModeEnabled ||
                (portal.schoolModeUntilMs ?? 0) > nowMs ||
                (portal.activeAppLocksCount ?? 0) > 0) && (
                <div className="mt-2.5 flex flex-wrap gap-1.5 text-[10px]">
                  {portal.curfewEnabled && (
                    <span className="rounded-md bg-indigo-500/15 px-2 py-0.5 text-indigo-300">Couvre-feu</span>
                  )}
                  {portal.blockSettingsAndStore && (
                    <span className="rounded-md bg-amber-500/15 px-2 py-0.5 text-amber-200">Anti-contournement</span>
                  )}
                  {(portal.schoolModeUntilMs ?? 0) > nowMs && (
                    <span className="rounded-md bg-sky-500/15 px-2 py-0.5 text-sky-300">Mode École</span>
                  )}
                  {portal.lostModeEnabled && (
                    <span className="rounded-md bg-rose-500/20 px-2 py-0.5 text-rose-200">Mode Perdu</span>
                  )}
                  {(portal.activeAppLocksCount ?? 0) > 0 && (
                    <span className="rounded-md bg-white/[0.07] px-2 py-0.5 text-zinc-300">
                      {portal.activeAppLocksCount} appli(s) bloquée(s)
                    </span>
                  )}
                </div>
              )}

              {adminError && (
                <div className="mt-3 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
                  {adminError}
                </div>
              )}

              {adminNotice && (
                <div className="mt-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200">
                  {adminNotice}
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
                <div className="mt-3 space-y-2.5">
                  <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-2.5">
                    <span className="text-xs text-emerald-200">Session administrateur déverrouillée</span>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setAdminUnlocked(false);
                          setVerifiedAdminPin('');
                          setNewAdminPinInput('');
                        }}
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

                  <form onSubmit={(e) => void handleUpdateAdminPin(e)} className="flex gap-2">
                    <input
                      type="password"
                      inputMode="text"
                      maxLength={12}
                      value={newAdminPinInput}
                      onChange={(e) => setNewAdminPinInput(e.target.value)}
                      placeholder="Nouveau code administrateur (4–12 car.)"
                      className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                    />
                    <button
                      type="submit"
                      disabled={busy || newAdminPinInput.trim().length < 4}
                      className="shrink-0 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
                    >
                      Changer
                    </button>
                  </form>
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
        </div>
      )}

      {/* ==================== TAB 3: UNIFIED ACCESS (PARENTS + TIMED PASSES) ==================== */}
      {section === 'access' && (
        <div className="mb-3 flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setAccessSubTab('friends')}
            className={`inline-flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium transition ${
              accessSubTab === 'friends'
                ? 'bg-white/[0.12] text-white'
                : 'text-zinc-400 hover:bg-white/[0.04] hover:text-white'
            }`}
          >
            <Users className="h-3.5 w-3.5" />
            <span>Parents ({friendProfiles.length})</span>
          </button>
          <button
            type="button"
            onClick={() => setAccessSubTab('passes')}
            className={`inline-flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium transition ${
              accessSubTab === 'passes'
                ? 'bg-white/[0.12] text-white'
                : 'text-zinc-400 hover:bg-white/[0.04] hover:text-white'
            }`}
          >
            <Timer className="h-3.5 w-3.5" />
            <span>Pass temporaires ({timedPasses.length})</span>
          </button>
        </div>
      )}

      {/* ==================== ACCESS SUB-VIEW: TIMED PASSES ==================== */}
      {section === 'access' && accessSubTab === 'passes' && (
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

      {/* ==================== ACCESS SUB-VIEW: PARENTS & FRIENDS ==================== */}
      {section === 'access' && accessSubTab === 'friends' && (
        <div className="space-y-4">
          <form
            onSubmit={(e) => void handleCreateProfile('friend', e)}
            className="rounded-3xl border border-slate-800 bg-obsidian-900/95 p-5 shadow-2xl"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <UserCheck className="h-4 w-4 text-cyan-400" />
                <h2 className="text-sm font-semibold text-white">Ajouter un Parent / Proche</h2>
              </div>
              <button
                type="button"
                onClick={() => {
                  setFormName((n) => n || 'Parent');
                  setAllowDeviceControl(true);
                  setAllowLocation(true);
                  setAllowDeviceInfo(true);
                  setAllowIntercom(true);
                  setAllowSmsRelay(true);
                }}
                className="rounded-lg border border-sky-400/40 bg-sky-500/15 px-2.5 py-1 text-[11px] font-medium text-sky-200 hover:bg-sky-500/25"
              >
                Préréglage Parent
              </button>
            </div>

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
