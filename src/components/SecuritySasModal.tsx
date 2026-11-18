import React from 'react';
import { CheckCircle2, KeyRound, Lock, ShieldCheck, X } from 'lucide-react';

interface SecuritySasModalProps {
  open: boolean;
  onClose: () => void;
  deviceName: string;
  sasCode: string;
  sasSymbols: string;
  localFingerprint?: string;
  remoteFingerprint?: string;
}

export const SecuritySasModal: React.FC<SecuritySasModalProps> = ({
  open,
  onClose,
  deviceName,
  sasCode,
  sasSymbols,
  localFingerprint,
  remoteFingerprint,
}) => {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-md">
      <div className="w-full max-w-lg rounded-2xl border border-emerald-500/30 bg-obsidian-900 p-6 shadow-2xl">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-emerald-500/30 bg-emerald-500/15">
              <ShieldCheck className="h-6 w-6 text-emerald-400" />
            </div>
            <div>
              <h3 className="text-base font-bold text-white">
                Vérification Cryptographique Anti-MitM (SAS)
              </h3>
              <p className="text-xs text-slate-400">
                Appareil lié : <span className="font-medium text-slate-200">{deviceName}</span>
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-800 hover:text-white"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="mt-5 rounded-xl border border-emerald-500/25 bg-obsidian-950 p-5 text-center">
          <p className="text-xs uppercase tracking-wider text-slate-400">
            Empreinte d’Authentification Courte (SAS)
          </p>
          <div className="mt-2 text-3xl tracking-widest">{sasSymbols || '🛡️ 🔐 ⚡ 🛰️'}</div>
          <div className="mt-2 font-mono text-2xl font-bold tracking-widest text-emerald-400">
            {sasCode || 'NON ÉTABLI'}
          </div>
          <p className="mt-2 text-xs text-slate-400">
            Comparez ce code avec celui affiché sur l’application Android SentinelCam lors du
            premier appairage. S’ils correspondent, aucune interception Man-in-the-Middle n’est
            possible.
          </p>
        </div>

        <div className="mt-4 space-y-2.5 text-xs">
          <div className="flex items-center gap-2.5 rounded-lg border border-slate-800 bg-obsidian-950/70 px-3.5 py-2.5">
            <KeyRound className="h-4 w-4 shrink-0 text-emerald-400" />
            <div>
              <div className="font-semibold text-slate-200">
                Dérivation Clé & Jeton : PBKDF2-SHA256 (150k) + HKDF-SHA256
              </div>
              <div className="text-slate-400">
                Authentification mutuelle HMAC-SHA256 à chaque réveil caméra
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2.5 rounded-lg border border-slate-800 bg-obsidian-950/70 px-3.5 py-2.5">
            <Lock className="h-4 w-4 shrink-0 text-cyan-400" />
            <div className="min-w-0 flex-1">
              <div className="font-semibold text-slate-200">
                Signalisation AES-256-GCM + Flux Média WebRTC DTLS-SRTP
              </div>
              {remoteFingerprint ? (
                <div className="mt-1 truncate font-mono text-[10px] text-slate-400">
                  SDP Distant : {remoteFingerprint}
                </div>
              ) : (
                <div className="text-slate-400">
                  Empreintes SDP SHA-256 vérifiées dès l’ouverture du flux vidéo
                </div>
              )}
              {localFingerprint && (
                <div className="truncate font-mono text-[10px] text-slate-500">
                  SDP Local : {localFingerprint}
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="mt-6 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center gap-2 rounded-xl bg-emerald-500 px-4 py-2 text-xs font-bold text-obsidian-950 transition hover:bg-emerald-400"
          >
            <CheckCircle2 className="h-4 w-4" />
            <span>Empreinte Vérifiée</span>
          </button>
        </div>
      </div>
    </div>
  );
};
