import React, { useEffect, useState } from 'react';
import {
  Battery,
  BatteryCharging,
  Check,
  Edit2,
  Eye,
  EyeOff,
  KeyRound,
  LogOut,
  Plus,
  QrCode,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Smartphone,
  Trash2,
  UserPlus,
  Users,
  Wifi,
  X,
} from 'lucide-react';
import { normalizePairingCode } from '../crypto/cryptoEngine';
import {
  generateMdmProvisioningQr,
  type MdmQrEnrollmentResult,
} from '../services/mdmProvisioningService';
import { ParentPushAlertsCard } from './ParentPushAlertsCard';
import type {
  ChildLiveStatus,
  ParentAccountSession,
  ParentChildRecord,
} from '../services/parentAccountService';
import type { AuthErrMsg, PortalOperationalState } from '../types/sentinel';

interface ParentFamilyHubProps {
  account: ParentAccountSession | null;
  liveStatuses: Record<string, ChildLiveStatus>;
  syncing: boolean;
  portalState: PortalOperationalState;
  connectingCode: string;
  authError: AuthErrMsg | null;
  pushSentFeedback: string | null;
  pairingActiveCode: string | null;
  pairingStatus: string | null;
  pairingMdmSession: {
    pairCode: string;
    deviceCode: string;
    familyKey: string;
    adminPin: string;
    childName: string;
  } | null;
  onLogin: (email: string, password: string) => Promise<void>;
  onRegister: (email: string, password: string, displayName: string) => Promise<void>;
  onLogout: () => void;
  onSyncLive: () => Promise<void>;
  onSelectChild: (code: string) => void;
  onRenameChild: (code: string, newName: string) => Promise<void>;
  onRemoveChild: (code: string) => Promise<void>;
  onStartAddChild: (childName: string, adminPin: string) => Promise<void>;
  onPreRegisterMdmChild: () => Promise<void>;
  onCancelAddChild: () => void;
  onDirectConnect: (code: string) => void;
  onSendWakePush: () => void;
  onCancelConnecting: () => void;
}

export const ParentFamilyHub: React.FC<ParentFamilyHubProps> = ({
  account,
  liveStatuses,
  syncing,
  portalState,
  connectingCode,
  authError,
  pushSentFeedback,
  pairingActiveCode,
  pairingStatus,
  pairingMdmSession,
  onLogin,
  onRegister,
  onLogout,
  onSyncLive,
  onSelectChild,
  onRenameChild,
  onRemoveChild,
  onStartAddChild,
  onPreRegisterMdmChild,
  onCancelAddChild,
  onDirectConnect,
  onSendWakePush,
  onCancelConnecting,
}) => {
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [authBusy, setAuthBusy] = useState(false);
  const [authFormError, setAuthFormError] = useState<string | null>(null);

  const [addChildOpen, setAddChildOpen] = useState(false);
  const [addMethod, setAddMethod] = useState<'code' | 'mdm_qr'>('code');
  const [newChildName, setNewChildName] = useState('');
  const [newChildPin, setNewChildPin] = useState('');
  const [mdmWifiSsid, setMdmWifiSsid] = useState('');
  const [mdmWifiPassword, setMdmWifiPassword] = useState('');
  const [mdmWifiOpen, setMdmWifiOpen] = useState(false);
  const [mdmQrResult, setMdmQrResult] = useState<MdmQrEnrollmentResult | null>(null);
  const [mdmQrLoading, setMdmQrLoading] = useState(false);
  const [mdmQrError, setMdmQrError] = useState<string | null>(null);

  useEffect(() => {
    if (!pairingMdmSession || addMethod !== 'mdm_qr') {
      return;
    }
    let cancelled = false;
    setMdmQrLoading(true);
    setMdmQrError(null);
    void generateMdmProvisioningQr({
      pairCode: pairingMdmSession.pairCode,
      familyKey: pairingMdmSession.familyKey,
      deviceCode: pairingMdmSession.deviceCode,
      adminPin: pairingMdmSession.adminPin,
      childName: pairingMdmSession.childName,
      wifiSsid: mdmWifiSsid,
      wifiPassword: mdmWifiPassword,
    })
      .then((res) => {
        if (!cancelled) setMdmQrResult(res);
      })
      .catch(() => {
        if (!cancelled) setMdmQrError('Impossible de générer le QR code MDM.');
      })
      .finally(() => {
        if (!cancelled) setMdmQrLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [pairingMdmSession, addMethod, mdmWifiSsid, mdmWifiPassword]);

  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [editingName, setEditingName] = useState('');
  const [confirmRemoveCode, setConfirmRemoveCode] = useState<string | null>(null);

  const [directCodeOpen, setDirectCodeOpen] = useState(false);
  const [directCode, setDirectCode] = useState('');

  const handleAuthSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthFormError(null);
    const cleanEmail = email.trim();
    if (cleanEmail.length < 3) {
      setAuthFormError('Saisissez un e-mail ou identifiant valide.');
      return;
    }
    if (password.length < 6) {
      setAuthFormError('Le mot de passe doit contenir au moins 6 caractères.');
      return;
    }
    if (authMode === 'register' && password !== passwordConfirm) {
      setAuthFormError('Les deux mots de passe ne correspondent pas.');
      return;
    }

    setAuthBusy(true);
    try {
      if (authMode === 'register') {
        await onRegister(cleanEmail, password, displayName.trim());
      } else {
        await onLogin(cleanEmail, password);
      }
      setPassword('');
      setPasswordConfirm('');
    } catch (err) {
      setAuthFormError(err instanceof Error ? err.message : 'Erreur de connexion.');
    } finally {
      setAuthBusy(false);
    }
  };

  const handleCreatePairing = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newChildPin.trim().length < 4) return;
    await onStartAddChild(newChildName.trim() || `Enfant ${(account?.children.length ?? 0) + 1}`, newChildPin.trim());
  };

  const handleSaveRename = async (e: React.FormEvent, code: string) => {
    e.preventDefault();
    if (editingName.trim()) {
      await onRenameChild(code, editingName.trim());
    }
    setEditingCode(null);
  };

  const renderConnectingBar = () =>
    portalState === 'connecting' ? (
      <div className="flex items-center justify-between gap-2 rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-zinc-300">
        <span className="inline-flex min-w-0 items-center gap-2 truncate">
          <RefreshCw className="h-3.5 w-3.5 shrink-0 animate-spin text-emerald-400" />
          <span className="truncate">Connexion à {connectingCode}…</span>
        </span>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={onSendWakePush}
            className="text-emerald-300 hover:underline"
          >
            Réveiller
          </button>
          <button
            type="button"
            onClick={onCancelConnecting}
            className="text-zinc-500 hover:text-white"
          >
            Annuler
          </button>
        </div>
      </div>
    ) : null;

  /* ==================== VIEW 1: PARENT LOGIN / SIGNUP ==================== */
  if (!account) {
    return (
      <div className="mx-auto flex min-h-[calc(100vh-4rem)] max-w-md flex-col justify-center px-3.5 py-6 sm:px-4">
        <div className="space-y-4 rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-5 sm:p-6">
          <div className="text-center">
            <h1 className="text-lg font-semibold tracking-tight text-white">Compte Parent</h1>
            <p className="mt-1 text-xs text-zinc-400">
              Retrouvez tous les appareils de vos enfants depuis n’importe quel téléphone ou PC
            </p>
          </div>

          {/* Mode Switcher */}
          <div className="grid grid-cols-2 gap-1 rounded-xl border border-white/[0.07] bg-zinc-950 p-1">
            <button
              type="button"
              onClick={() => {
                setAuthMode('login');
                setAuthFormError(null);
              }}
              className={`rounded-lg py-2 text-xs font-medium transition ${
                authMode === 'login' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
              }`}
            >
              Connexion
            </button>
            <button
              type="button"
              onClick={() => {
                setAuthMode('register');
                setAuthFormError(null);
              }}
              className={`rounded-lg py-2 text-xs font-medium transition ${
                authMode === 'register' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
              }`}
            >
              Créer un compte
            </button>
          </div>

          <form onSubmit={(e) => void handleAuthSubmit(e)} className="space-y-2.5">
            <input
              type="text"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="E-mail ou identifiant"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
            />

            {authMode === 'register' && (
              <input
                type="text"
                maxLength={32}
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Nom de la famille (optionnel)"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />
            )}

            <div className="relative">
              <input
                type={showPwd ? 'text' : 'password'}
                autoComplete={authMode === 'register' ? 'new-password' : 'current-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Mot de passe (min. 6 caractères)"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2.5 pl-3.5 pr-9 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />
              <button
                type="button"
                onClick={() => setShowPwd((v) => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-zinc-300"
              >
                {showPwd ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>

            {authMode === 'register' && (
              <input
                type={showPwd ? 'text' : 'password'}
                autoComplete="new-password"
                value={passwordConfirm}
                onChange={(e) => setPasswordConfirm(e.target.value)}
                placeholder="Confirmer le mot de passe"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />
            )}

            {authFormError && (
              <div className="rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
                {authFormError}
              </div>
            )}

            <button
              type="submit"
              disabled={authBusy}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-white py-3 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-40"
            >
              {authBusy ? (
                <>
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                  <span>
                    {authMode === 'register' ? 'Création du compte…' : 'Connexion…'}
                  </span>
                </>
              ) : (
                <span>
                  {authMode === 'register' ? 'Créer mon compte Parent' : 'Se connecter'}
                </span>
              )}
            </button>
          </form>

          <p className="text-center text-[11px] text-zinc-500">
            Gratuit, privé et synchronisé sur tous vos appareils
          </p>

          {renderConnectingBar()}

          {authError && (
            <div className="flex items-start gap-2 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
              <span>{authError.message}</span>
            </div>
          )}

          {/* Discreet Direct Code Fallback */}
          <div className="border-t border-white/[0.06] pt-3 text-center">
            <button
              type="button"
              onClick={() => setDirectCodeOpen((v) => !v)}
              className="text-[11px] text-zinc-500 hover:text-zinc-300"
            >
              {directCodeOpen ? 'Masquer l’accès par code' : 'Accès direct par code (sans compte)'}
            </button>

            {directCodeOpen && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const clean = normalizePairingCode(directCode);
                  if (clean.length === 6) onDirectConnect(clean);
                }}
                className="mt-2.5 flex gap-2"
              >
                <input
                  type="text"
                  maxLength={6}
                  value={directCode}
                  onChange={(e) => setDirectCode(normalizePairingCode(e.target.value))}
                  placeholder="CODE 6 CAR."
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-center font-mono text-xs uppercase tracking-widest text-white"
                />
                <button
                  type="submit"
                  disabled={directCode.length !== 6}
                  className="shrink-0 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
                >
                  Ouvrir
                </button>
              </form>
            )}
          </div>
        </div>
      </div>
    );
  }

  /* ==================== VIEW 2: LOGGED-IN MULTI-CHILD FAMILY DASHBOARD ==================== */
  return (
    <div className="mx-auto max-w-md space-y-4 px-3.5 py-6 sm:px-4">
      {/* Compact Parent Account Bar */}
      <div className="flex items-center justify-between gap-2 rounded-2xl border border-white/[0.07] bg-zinc-900/70 px-4 py-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/15 text-emerald-400">
            <Users className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold text-white">{account.displayName}</div>
            <div className="truncate text-[11px] text-zinc-500">{account.email}</div>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            disabled={syncing}
            onClick={() => void onSyncLive()}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-white/[0.08] bg-zinc-950 text-zinc-400 transition hover:text-white"
            title="Actualiser les appareils"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${syncing ? 'animate-spin text-emerald-400' : ''}`} />
          </button>
          <button
            type="button"
            onClick={onLogout}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-white/[0.08] bg-zinc-950 text-zinc-400 transition hover:border-rose-500/40 hover:text-rose-300"
            title="Déconnexion"
          >
            <LogOut className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {renderConnectingBar()}

      {/* Instant Phone Notifications Without Opening the Website */}
      <ParentPushAlertsCard
        familyKey={account.familyKey}
        childCodes={account.children.map((c) => c.pairingCode)}
      />

      {pushSentFeedback && (
        <div className="flex items-center justify-center gap-1.5 rounded-xl bg-emerald-500/10 px-3 py-2 text-xs text-emerald-300">
          <Check className="h-3.5 w-3.5" />
          <span>{pushSentFeedback}</span>
        </div>
      )}

      {authError && (
        <div className="flex items-start gap-2 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
          <span>{authError.message}</span>
        </div>
      )}

      {/* Children Roster */}
      <div className="space-y-2.5 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-4">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-white">
            Mes enfants ({account.children.length})
          </h2>
          {!pairingActiveCode && (
            <button
              type="button"
              onClick={() => setAddChildOpen((v) => !v)}
              className="inline-flex items-center gap-1 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
            >
              <Plus className="h-3.5 w-3.5" />
              <span>Ajouter</span>
            </button>
          )}
        </div>

        {/* Add Child Wizard (Code 6 car. + QR Code Appareil Entièrement Géré MDM) */}
        {pairingActiveCode ? (
          <div className="space-y-3 rounded-xl border border-emerald-500/35 bg-zinc-950 p-4 text-center">
            {/* Live switcher between 6-char code and MDM QR for the SAME session */}
            <div className="grid grid-cols-2 gap-1 rounded-xl border border-white/[0.08] bg-zinc-900 p-1">
              <button
                type="button"
                onClick={() => setAddMethod('code')}
                className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[11px] font-medium transition ${
                  addMethod === 'code' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                }`}
              >
                <KeyRound className="h-3.5 w-3.5" />
                <span>Code 6 car.</span>
              </button>
              <button
                type="button"
                onClick={() => setAddMethod('mdm_qr')}
                className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[11px] font-medium transition ${
                  addMethod === 'mdm_qr' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                }`}
              >
                <QrCode className="h-3.5 w-3.5" />
                <span>QR Appareil Géré</span>
              </button>
            </div>

            {addMethod === 'code' ? (
              <>
                <div className="text-[11px] font-medium text-zinc-400">
                  Saisissez ce code sur le téléphone de{' '}
                  <span className="text-white">{newChildName.trim() || 'l’enfant'}</span>
                </div>
                <div className="grid grid-cols-6 gap-1.5">
                  {pairingActiveCode.split('').map((ch, i) => (
                    <span
                      key={i}
                      className="flex h-12 items-center justify-center rounded-lg border border-emerald-500/40 bg-zinc-900 font-mono text-lg font-semibold text-emerald-300"
                    >
                      {ch}
                    </span>
                  ))}
                </div>
              </>
            ) : (
              <div className="space-y-2.5 text-left">
                <div className="text-center text-[11px] font-medium text-zinc-300">
                  QR Code <span className="text-emerald-300">Appareil Entièrement Géré (MDM)</span> pour{' '}
                  <span className="text-white">{pairingMdmSession?.childName || newChildName.trim() || 'l’enfant'}</span>
                </div>

                {mdmQrLoading ? (
                  <div className="flex h-56 flex-col items-center justify-center gap-2 rounded-xl border border-white/10 bg-zinc-900 text-xs text-zinc-400">
                    <RefreshCw className="h-5 w-5 animate-spin text-emerald-400" />
                    <span>Génération du QR Code Android Enterprise…</span>
                  </div>
                ) : mdmQrError ? (
                  <div className="rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-center text-xs text-rose-200">
                    {mdmQrError}
                  </div>
                ) : mdmQrResult ? (
                  <>
                    <div className="mx-auto w-fit rounded-2xl bg-white p-3 shadow-lg">
                      <img
                        src={mdmQrResult.qrDataUrl}
                        alt="QR Code Enrôlement Appareil Entièrement Géré"
                        className="h-56 w-56 rounded-lg object-contain sm:h-60 sm:w-60"
                      />
                    </div>
                    <div className="flex items-center justify-center gap-1.5 text-[10px]">
                      {mdmQrResult.releaseInfo.checksumVerified ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/15 px-2.5 py-0.5 text-emerald-300">
                          <ShieldCheck className="h-3 w-3" />
                          Signature APK vérifiée ({mdmQrResult.releaseInfo.releaseTag})
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2.5 py-0.5 text-amber-200">
                          <ShieldAlert className="h-3 w-3" />
                          Signature APK en attente de publication CI
                        </span>
                      )}
                    </div>
                  </>
                ) : null}

                <ol className="space-y-1 rounded-xl border border-white/[0.07] bg-zinc-900/80 p-3 text-[11px] text-zinc-300">
                  <li>
                    <span className="font-semibold text-emerald-300">1.</span> Démarrez le téléphone Android{' '}
                    <span className="text-white">neuf ou réinitialisé</span> (écran <em>Bienvenue</em>).
                  </li>
                  <li>
                    <span className="font-semibold text-emerald-300">2.</span> Tapez{' '}
                    <span className="text-white">6 fois</span> dans le vide sur l’écran d’accueil pour ouvrir le scanner QR Android.
                  </li>
                  <li>
                    <span className="font-semibold text-emerald-300">3.</span> Scannez ce QR code : Android installe{' '}
                    <span className="text-white">SentinelCam</span> en mode Appareil entièrement géré.
                  </li>
                  <li>
                    <span className="font-semibold text-emerald-300">4.</span> Validez l’écran Android : l’appareil s’associe automatiquement à votre compte Parent avec toutes les fonctions.
                  </li>
                </ol>

                <div className="flex items-center justify-between rounded-lg border border-white/[0.06] bg-zinc-900/60 px-3 py-1.5 text-[11px] text-zinc-400">
                  <span>Code manuel équivalent :</span>
                  <span className="font-mono font-semibold tracking-wider text-emerald-300">
                    {pairingActiveCode}
                  </span>
                </div>

                <button
                  type="button"
                  onClick={() => {
                    void onPreRegisterMdmChild();
                    setAddChildOpen(false);
                  }}
                  className="w-full rounded-xl border border-emerald-500/40 bg-emerald-500/15 py-2 text-xs font-semibold text-emerald-200 transition hover:bg-emerald-500/25"
                >
                  Enregistrer l’appareil en attente et fermer
                </button>
              </div>
            )}

            <div className="flex items-center justify-center gap-1.5 text-xs text-zinc-400">
              <RefreshCw className="h-3.5 w-3.5 animate-spin text-emerald-400" />
              <span>{pairingStatus || 'En attente du smartphone…'}</span>
            </div>
            <button
              type="button"
              onClick={() => {
                onCancelAddChild();
                setMdmQrResult(null);
                setAddChildOpen(false);
              }}
              className="text-xs text-zinc-500 hover:text-zinc-300"
            >
              Annuler
            </button>
          </div>
        ) : addChildOpen ? (
          <form
            onSubmit={(e) => void handleCreatePairing(e)}
            className="space-y-2.5 rounded-xl border border-white/[0.08] bg-zinc-950 p-3.5"
          >
            <div className="flex items-center justify-between text-xs">
              <span className="inline-flex items-center gap-1.5 font-medium text-white">
                <UserPlus className="h-3.5 w-3.5 text-emerald-400" />
                Nouvel appareil enfant
              </span>
              <button
                type="button"
                onClick={() => setAddChildOpen(false)}
                className="text-zinc-500 hover:text-white"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>

            {/* Enrollment Type Selector: Code 6 car. vs QR Code Appareil Géré (MDM) */}
            <div className="grid grid-cols-2 gap-1 rounded-xl border border-white/[0.08] bg-zinc-900 p-1">
              <button
                type="button"
                onClick={() => setAddMethod('code')}
                className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[11px] font-medium transition ${
                  addMethod === 'code' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                }`}
              >
                <KeyRound className="h-3.5 w-3.5" />
                <span>Par code (6 car.)</span>
              </button>
              <button
                type="button"
                onClick={() => setAddMethod('mdm_qr')}
                className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-[11px] font-medium transition ${
                  addMethod === 'mdm_qr' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                }`}
              >
                <QrCode className="h-3.5 w-3.5" />
                <span>QR Appareil Géré</span>
              </button>
            </div>

            <input
              type="text"
              maxLength={32}
              value={newChildName}
              onChange={(e) => setNewChildName(e.target.value)}
              placeholder="Prénom de l’enfant (ex. Lucas, Emma)"
              className="w-full rounded-xl border border-white/10 bg-zinc-900 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
            />
            <input
              type="password"
              maxLength={12}
              value={newChildPin}
              onChange={(e) => setNewChildPin(e.target.value)}
              placeholder="Code administrateur requis (4–12 car.)"
              className="w-full rounded-xl border border-white/10 bg-zinc-900 px-3 py-2 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
            />

            {addMethod === 'mdm_qr' && (
              <div className="space-y-2 rounded-xl border border-white/[0.06] bg-zinc-900/70 p-2.5">
                <p className="text-[11px] leading-relaxed text-zinc-400">
                  Enrôlement officiel <span className="text-zinc-200">Android Enterprise (Device Owner)</span> sur un téléphone neuf ou réinitialisé : installe SentinelCam, accorde les autorisations gérées et associe automatiquement l’appareil au compte Parent.
                </p>
                <button
                  type="button"
                  onClick={() => setMdmWifiOpen((v) => !v)}
                  className="inline-flex items-center gap-1.5 text-[11px] font-medium text-emerald-300 hover:text-emerald-200"
                >
                  <Wifi className="h-3.5 w-3.5" />
                  <span>
                    {mdmWifiOpen ? 'Masquer le Wi-Fi automatique' : 'Inclure le Wi-Fi dans le QR (optionnel)'}
                  </span>
                </button>
                {mdmWifiOpen && (
                  <div className="space-y-1.5 pt-1">
                    <input
                      type="text"
                      maxLength={64}
                      value={mdmWifiSsid}
                      onChange={(e) => setMdmWifiSsid(e.target.value)}
                      placeholder="Nom du réseau Wi-Fi (SSID)"
                      className="w-full rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                    />
                    <input
                      type="password"
                      maxLength={64}
                      value={mdmWifiPassword}
                      onChange={(e) => setMdmWifiPassword(e.target.value)}
                      placeholder="Mot de passe Wi-Fi (vide si réseau ouvert)"
                      className="w-full rounded-lg border border-white/10 bg-zinc-950 px-2.5 py-1.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
                    />
                  </div>
                )}
              </div>
            )}

            <button
              type="submit"
              disabled={newChildPin.trim().length < 4}
              className="w-full rounded-xl bg-white py-2.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-35"
            >
              {addMethod === 'mdm_qr'
                ? 'Générer le QR Code Appareil Géré'
                : 'Générer le code d’association'}
            </button>
          </form>
        ) : null}

        {/* Multi-Child Cards */}
        {account.children.length === 0 ? (
          <div className="rounded-xl border border-dashed border-white/[0.08] bg-zinc-950/50 py-8 text-center text-xs text-zinc-500">
            <Smartphone className="mx-auto mb-2 h-6 w-6 text-zinc-600" />
            Aucun enfant associé pour le moment.
          </div>
        ) : (
          <div className="space-y-2">
            {account.children.map((child: ParentChildRecord) => {
              const live = liveStatuses[child.pairingCode];
              const isEditing = editingCode === child.pairingCode;

              return (
                <div
                  key={child.pairingCode}
                  onClick={() => {
                    if (!isEditing) onSelectChild(child.pairingCode);
                  }}
                  className="cursor-pointer rounded-xl border border-white/[0.07] bg-zinc-950/80 p-3.5 transition hover:border-white/20"
                >
                  {isEditing ? (
                    <form
                      onSubmit={(e) => void handleSaveRename(e, child.pairingCode)}
                      onClick={(e) => e.stopPropagation()}
                      className="flex items-center gap-2"
                    >
                      <input
                        type="text"
                        autoFocus
                        maxLength={32}
                        value={editingName}
                        onChange={(e) => setEditingName(e.target.value)}
                        className="min-w-0 flex-1 rounded-lg border border-white/15 bg-zinc-900 px-2.5 py-1.5 text-xs text-white focus:border-emerald-400 focus:outline-none"
                      />
                      <button
                        type="submit"
                        className="rounded-lg bg-white px-2.5 py-1.5 text-xs font-semibold text-zinc-950"
                      >
                        OK
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingCode(null)}
                        className="rounded-lg p-1.5 text-zinc-500 hover:text-white"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </form>
                  ) : (
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-2.5">
                        <div className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-white/[0.05]">
                          <Smartphone className="h-4 w-4 text-zinc-300" />
                          <span
                            className={`absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-zinc-950 ${
                              live?.online ? 'bg-emerald-400' : 'bg-zinc-600'
                            }`}
                          />
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="truncate text-sm font-semibold text-white">
                              {child.childName || child.deviceName}
                            </span>
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setEditingCode(child.pairingCode);
                                setEditingName(child.childName || child.deviceName);
                              }}
                              className="rounded p-0.5 text-zinc-600 hover:text-zinc-300"
                              title="Renommer"
                            >
                              <Edit2 className="h-3 w-3" />
                            </button>
                          </div>
                          <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-zinc-500">
                            <span className="truncate">{child.deviceName}</span>
                            {live?.online && live.batteryLevel !== undefined && (
                              <>
                                <span>·</span>
                                <span className="inline-flex items-center gap-0.5 font-mono text-emerald-400">
                                  {live.isCharging ? (
                                    <BatteryCharging className="h-3 w-3" />
                                  ) : (
                                    <Battery className="h-3 w-3" />
                                  )}
                                  {live.batteryLevel}%
                                </span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>

                      <div className="flex shrink-0 items-center gap-1.5">
                        {confirmRemoveCode === child.pairingCode ? (
                          <div
                            className="flex items-center gap-1"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <button
                              type="button"
                              onClick={() => {
                                setConfirmRemoveCode(null);
                                void onRemoveChild(child.pairingCode);
                              }}
                              className="rounded-lg bg-rose-500 px-2 py-1 text-[11px] font-semibold text-white hover:bg-rose-400"
                            >
                              Retirer
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmRemoveCode(null)}
                              className="rounded-lg border border-white/10 px-2 py-1 text-[11px] text-zinc-400 hover:text-white"
                            >
                              Non
                            </button>
                          </div>
                        ) : (
                          <>
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setConfirmRemoveCode(child.pairingCode);
                              }}
                              className="rounded-lg p-1.5 text-zinc-600 transition hover:bg-rose-500/10 hover:text-rose-400"
                              title="Retirer cet appareil de l’historique"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </button>
                            <span className="text-xs font-medium text-zinc-400">Gérer →</span>
                          </>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Live Alert Pills right on the Child Card */}
                  {live &&
                    (live.unpairRequestPending ||
                      (live.pendingRequestsCount ?? 0) > 0 ||
                      live.lostModeEnabled ||
                      (live.screenPausedUntilMs ?? 0) > Date.now()) && (
                      <div className="mt-2.5 flex flex-wrap gap-1.5 border-t border-white/[0.05] pt-2 text-[10px]">
                        {live.unpairRequestPending && (
                          <span className="rounded bg-rose-500/20 px-2 py-0.5 font-semibold text-rose-200">
                            Demande de dissociation
                          </span>
                        )}
                        {(live.pendingRequestsCount ?? 0) > 0 && (
                          <span className="rounded bg-emerald-500/20 px-2 py-0.5 font-semibold text-emerald-200">
                            +{live.pendingRequestsCount} demande(s) de temps
                          </span>
                        )}
                        {live.lostModeEnabled && (
                          <span className="rounded bg-rose-500/20 px-2 py-0.5 text-rose-200">
                            Mode Perdu actif
                          </span>
                        )}
                        {(live.screenPausedUntilMs ?? 0) > Date.now() && (
                          <span className="rounded bg-amber-500/20 px-2 py-0.5 text-amber-200">
                            En pause
                          </span>
                        )}
                      </div>
                    )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Discreet Direct Code Fallback */}
      <div className="text-center">
        <button
          type="button"
          onClick={() => setDirectCodeOpen((v) => !v)}
          className="text-[11px] text-zinc-500 hover:text-zinc-300"
        >
          {directCodeOpen ? 'Masquer le code direct' : 'Ouvrir un autre appareil par code'}
        </button>

        {directCodeOpen && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const clean = normalizePairingCode(directCode);
              if (clean.length === 6) onDirectConnect(clean);
            }}
            className="mt-2.5 flex gap-2 rounded-2xl border border-white/[0.07] bg-zinc-900/70 p-3"
          >
            <input
              type="text"
              maxLength={6}
              value={directCode}
              onChange={(e) => setDirectCode(normalizePairingCode(e.target.value))}
              placeholder="CODE 6 CAR."
              className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-center font-mono text-xs uppercase tracking-widest text-white"
            />
            <button
              type="submit"
              disabled={directCode.length !== 6}
              className="shrink-0 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 disabled:opacity-35"
            >
              Ouvrir
            </button>
          </form>
        )}
      </div>
    </div>
  );
};
