import React, { useEffect, useState } from 'react';
import { Download, Monitor, Radio, Settings, Shield } from 'lucide-react';

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
    <header className="sticky top-0 z-40 border-b border-white/[0.06] bg-[#09090b]/85 backdrop-blur-xl">
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-3 sm:px-6">
        {/* Minimal Brand */}
        <div className="flex items-center gap-2.5">
          <div className="relative flex h-8 w-8 items-center justify-center rounded-lg bg-white/[0.06] ring-1 ring-white/10">
            <Shield className="h-4 w-4 text-emerald-400" />
            <span
              className={`absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full ring-2 ring-[#09090b] ${
                brokerConnected ? 'bg-emerald-400' : 'bg-zinc-500'
              }`}
            />
          </div>
          <span className="text-sm font-semibold tracking-tight text-white">SentinelCam</span>
        </div>

        {/* Clean Switcher (Web Only) */}
        {!isNativeAndroid && (
          <div className="flex items-center rounded-lg bg-white/[0.04] p-0.5 ring-1 ring-white/[0.07]">
            <button
              type="button"
              onClick={() => onTabChange('controller')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition ${
                activeTab === 'controller'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Monitor className="h-3.5 w-3.5" />
              <span>Parent</span>
            </button>
            <button
              type="button"
              onClick={() => onTabChange('emitter')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition ${
                activeTab === 'emitter'
                  ? 'bg-white text-zinc-950'
                  : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Radio className="h-3.5 w-3.5" />
              <span>Appareil</span>
            </button>
          </div>
        )}

        {/* Right Actions */}
        <div className="flex items-center gap-2">
          {deferredPrompt && !isNativeAndroid && (
            <button
              type="button"
              onClick={handleInstallPwa}
              className="rounded-lg bg-white/[0.06] px-2.5 py-1.5 text-xs font-medium text-zinc-200 ring-1 ring-white/10 transition hover:bg-white/10"
            >
              Installer
            </button>
          )}

          {!isNativeAndroid && (
            <a
              href={apkDownloadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 rounded-lg bg-white/[0.06] px-2.5 py-1.5 text-xs font-medium text-zinc-200 ring-1 ring-white/10 transition hover:bg-white/10"
              title="Télécharger l’application Android"
            >
              <Download className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Android</span>
            </a>
          )}

          <button
            type="button"
            onClick={onOpenSettings}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-white/[0.04] text-zinc-400 ring-1 ring-white/[0.07] transition hover:text-white"
            title="Paramètres réseau"
          >
            <Settings className="h-4 w-4" />
          </button>
        </div>
      </div>
    </header>
  );
};
