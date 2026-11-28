import React, { useEffect, useState } from 'react';
import { Download, Moon, ShieldCheck, Sun } from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface HeaderBarProps {
  isNativeAndroid: boolean;
  brokerConnected: boolean;
  apkDownloadUrl: string;
}

const THEME_KEY = 'sentinelcam_ui_theme_v27';

export const HeaderBar: React.FC<HeaderBarProps> = ({
  isNativeAndroid,
  brokerConnected,
  apkDownloadUrl,
}) => {
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [darkMode, setDarkMode] = useState<boolean>(() => {
    try {
      return localStorage.getItem(THEME_KEY) === 'dark';
    } catch {
      return false;
    }
  });

  useEffect(() => {
    const root = document.documentElement;
    if (darkMode) root.classList.add('dark');
    else root.classList.remove('dark');
    try {
      localStorage.setItem(THEME_KEY, darkMode ? 'dark' : 'light');
    } catch {
      // Ignore storage error
    }
  }, [darkMode]);

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
    <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/90 pt-[env(safe-area-inset-top)] backdrop-blur-xl dark:border-white/[0.07] dark:bg-[#0b1120]/90">
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-2 px-4 py-2.5 sm:px-6 sm:py-3">
        {/* Kidslox-inspired Clean Family Brand */}
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-sky-500 to-emerald-500 shadow-sm shadow-sky-500/25">
            <ShieldCheck className="h-5 w-5 text-white kl-solid-white" />
            <span
              className={`absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-white dark:ring-[#0b1120] ${
                brokerConnected ? 'bg-emerald-400' : 'bg-slate-400'
              }`}
              title={brokerConnected ? 'Serveur temps réel connecté' : 'Connexion en attente'}
            />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="truncate text-[15px] font-bold tracking-tight text-slate-900 dark:text-white">
                SentinelCam
              </span>
              <span className="rounded-full bg-sky-50 px-2 py-0.5 text-[10px] font-semibold text-sky-700 ring-1 ring-sky-200/80 dark:bg-sky-500/15 dark:text-sky-300 dark:ring-sky-500/30">
                {isNativeAndroid ? 'Appareil Protégé' : 'Espace Parent'}
              </span>
            </div>
          </div>
        </div>

        {/* Clean Minimal Actions */}
        <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
          <button
            type="button"
            onClick={() => setDarkMode((v) => !v)}
            className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-600 transition hover:bg-slate-100 dark:border-white/10 dark:bg-white/[0.06] dark:text-slate-300 dark:hover:bg-white/10"
            title={darkMode ? 'Passer en thème clair' : 'Passer en thème sombre'}
          >
            {darkMode ? <Sun className="h-4 w-4 text-amber-400" /> : <Moon className="h-4 w-4 text-slate-600" />}
          </button>

          {deferredPrompt && !isNativeAndroid && (
            <button
              type="button"
              onClick={handleInstallPwa}
              className="rounded-xl bg-emerald-500 px-3 py-1.5 text-xs font-semibold text-white kl-solid-white shadow-sm shadow-emerald-500/20 transition hover:bg-emerald-600"
            >
              Installer
            </button>
          )}

          {!isNativeAndroid && (
            <a
              href={apkDownloadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50 dark:border-white/10 dark:bg-white/[0.06] dark:text-slate-200 dark:hover:bg-white/10"
              title="Télécharger l’application Android"
            >
              <Download className="h-3.5 w-3.5 text-sky-500" />
              <span>App Android</span>
            </a>
          )}
        </div>
      </div>
    </header>
  );
};
