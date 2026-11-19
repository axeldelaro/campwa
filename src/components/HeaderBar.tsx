import React, { useEffect, useState } from 'react';
import { Download, Settings, Shield } from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface HeaderBarProps {
  isNativeAndroid: boolean;
  brokerConnected: boolean;
  onOpenSettings: () => void;
  apkDownloadUrl: string;
}

export const HeaderBar: React.FC<HeaderBarProps> = ({
  isNativeAndroid,
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
    <header className="sticky top-0 z-40 border-b border-white/[0.06] bg-[#09090b]/90 pt-[env(safe-area-inset-top)] backdrop-blur-xl">
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-2 px-3.5 py-2.5 sm:px-6 sm:py-3">
        {/* Clean Brand */}
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="relative flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/[0.06] ring-1 ring-white/10">
            <Shield className="h-4 w-4 text-emerald-400" />
            <span
              className={`absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full ring-2 ring-[#09090b] ${
                brokerConnected ? 'bg-emerald-400' : 'bg-zinc-500'
              }`}
            />
          </div>
          <div className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm font-semibold tracking-tight text-white">
              SentinelCam
            </span>
            {!isNativeAndroid && (
              <span className="rounded-md bg-white/[0.05] px-1.5 py-0.5 text-[10px] font-medium text-zinc-400">
                Parent
              </span>
            )}
          </div>
        </div>

        {/* Right Actions */}
        <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
          {deferredPrompt && !isNativeAndroid && (
            <button
              type="button"
              onClick={handleInstallPwa}
              className="rounded-lg bg-emerald-500/15 px-2.5 py-1.5 text-xs font-medium text-emerald-300 ring-1 ring-emerald-500/30 transition hover:bg-emerald-500/25"
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
              <span>APK</span>
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
