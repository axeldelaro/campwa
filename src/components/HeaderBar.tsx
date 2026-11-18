import React, { useEffect, useState } from 'react';
import {
  Download,
  Monitor,
  Radio,
  Settings,
  ShieldCheck,
  Smartphone,
  Sparkles,
} from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface HeaderBarProps {
  isNativeAndroid: boolean;
  activeTab: 'controller' | 'emitter';
  onTabChange: (tab: 'controller' | 'emitter') => void;
  brokerConnected: boolean;
  onOpenSettings: () => void;
  apkDownloadUrl: string;
}

export const HeaderBar: React.FC<HeaderBarProps> = ({
  isNativeAndroid,
  activeTab,
  onTabChange,
  brokerConnected,
  onOpenSettings,
  apkDownloadUrl,
}) => {
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
    };
    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  const handleInstallPwa = async () => {
    if (!deferredPrompt) return;
    await deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    setDeferredPrompt(null);
  };

  return (
    <header className="sticky top-0 z-40 border-b border-slate-800/80 bg-obsidian-950/85 backdrop-blur-xl">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6">
        {/* Brand Identity */}
        <div className="flex items-center gap-3">
          <div className="relative flex h-10 w-10 items-center justify-center rounded-xl border border-emerald-500/30 bg-gradient-to-br from-emerald-500/20 via-obsidian-900 to-cyan-500/20 shadow-lg shadow-emerald-950/50">
            <ShieldCheck className="h-5 w-5 text-emerald-400" />
            <span
              className={`absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-obsidian-950 ${
                brokerConnected ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'
              }`}
            />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-base font-bold tracking-tight text-white sm:text-lg">
                Sentinel<span className="text-emerald-400">Cam</span>
              </span>
              <span className="hidden rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider text-emerald-300 sm:inline-block">
                E2EE • DTLS-SRTP
              </span>
            </div>
            <p className="text-[11px] text-slate-400">
              {isNativeAndroid
                ? 'Service Natif Android Continu • Chiffrement Bout-en-Bout'
                : 'Centre de Contrôle WebRTC à la Demande • Zéro Serveur Tiers'}
            </p>
          </div>
        </div>

        {/* Mode Switcher (Web Only) */}
        {!isNativeAndroid && (
          <div className="flex items-center rounded-xl border border-slate-800 bg-obsidian-900/90 p-1">
            <button
              type="button"
              onClick={() => onTabChange('controller')}
              className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
                activeTab === 'controller'
                  ? 'bg-emerald-500 text-obsidian-950 shadow-sm shadow-emerald-500/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Monitor className="h-3.5 w-3.5" />
              <span>Poste de Contrôle</span>
            </button>
            <button
              type="button"
              onClick={() => onTabChange('emitter')}
              className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
                activeTab === 'emitter'
                  ? 'bg-emerald-500 text-obsidian-950 shadow-sm shadow-emerald-500/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Radio className="h-3.5 w-3.5" />
              <span>Mode Émetteur</span>
            </button>
          </div>
        )}

        {/* Right Actions */}
        <div className="flex items-center gap-2">
          {deferredPrompt && !isNativeAndroid && (
            <button
              type="button"
              onClick={handleInstallPwa}
              className="inline-flex items-center gap-1.5 rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-3 py-2 text-xs font-semibold text-cyan-300 transition hover:bg-cyan-500/20"
            >
              <Sparkles className="h-3.5 w-3.5" />
              <span className="hidden md:inline">Installer PWA</span>
            </button>
          )}

          {!isNativeAndroid && (
            <a
              href={apkDownloadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-3 py-2 text-xs font-semibold text-emerald-300 transition hover:border-emerald-400/60 hover:bg-emerald-500/20"
              title="Télécharger l’application Android SentinelCam (.apk)"
            >
              <Smartphone className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">APK Android</span>
              <Download className="h-3.5 w-3.5 opacity-80" />
            </a>
          )}

          <button
            type="button"
            onClick={onOpenSettings}
            className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-slate-800 bg-obsidian-900 text-slate-300 transition hover:border-slate-700 hover:text-white"
            title="Configuration réseau & serveurs STUN/TURN"
          >
            <Settings className="h-4 w-4" />
          </button>
        </div>
      </div>
    </header>
  );
};
