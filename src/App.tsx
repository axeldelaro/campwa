import React, { useCallback, useEffect, useState } from 'react';
import { BellRing, ExternalLink } from 'lucide-react';
import { AndroidEmitterView } from './components/AndroidEmitterView';
import { ControlStationView } from './components/ControlStationView';
import { HeaderBar } from './components/HeaderBar';
import { SettingsModal } from './components/SettingsModal';
import { normalizePairingCode } from './crypto/cryptoEngine';
import { isAndroidNativeApp } from './native/sentinelPlugin';
import { warmUpSignalingPool, type BrokerHealth } from './services/signalingService';

const APK_RELEASE_URL = 'https://github.com/axeldelaro/campwa/releases/latest';

export const App: React.FC = () => {
  const nativeAndroid = isAndroidNativeApp();
  const [activeTab, setActiveTab] = useState<'controller' | 'emitter'>(
    nativeAndroid ? 'emitter' : 'controller'
  );
  const [brokerConnected, setBrokerConnected] = useState(false);
  const [brokers, setBrokers] = useState<BrokerHealth[]>([]);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [inviteCode, setInviteCode] = useState<string | null>(null);
  const [sharedAccessCode, setSharedAccessCode] = useState<string | null>(null);

  useEffect(() => {
    if (!nativeAndroid) {
      warmUpSignalingPool();

      const params = new URLSearchParams(window.location.search);
      const rawInvite = params.get('invite');
      if (rawInvite) {
        const clean = normalizePairingCode(rawInvite);
        if (clean.length === 6) {
          setInviteCode(clean);
          window.location.href = `sentinelcam://wake?code=${clean}`;
        }
      }

      const rawCode = params.get('code');
      if (rawCode) {
        const clean = normalizePairingCode(rawCode);
        if (clean.length === 6) {
          setSharedAccessCode(clean);
          setActiveTab('controller');
        }
      }
    }
  }, [nativeAndroid]);

  const handleBrokerHealth = useCallback((connected: boolean, health: BrokerHealth[]) => {
    setBrokerConnected(connected);
    setBrokers(health);
  }, []);

  const androidIntentHref = inviteCode
    ? `intent://wake?code=${inviteCode}#Intent;scheme=sentinelcam;package=com.sentinelcam.app;end`
    : '#';

  return (
    <div className="min-h-screen bg-obsidian-950 bg-tactical-grid text-slate-100">
      <HeaderBar
        isNativeAndroid={nativeAndroid}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        brokerConnected={brokerConnected}
        onOpenSettings={() => setSettingsOpen(true)}
        apkDownloadUrl={APK_RELEASE_URL}
      />

      {!nativeAndroid && inviteCode && (
        <div className="mx-auto mt-6 max-w-md px-4">
          <div className="rounded-3xl border border-emerald-500/40 bg-emerald-500/10 p-6 text-center shadow-2xl">
            <BellRing className="mx-auto h-10 w-10 text-emerald-400 animate-bounce" />
            <h2 className="mt-3 text-lg font-extrabold text-white">
              Invitation d’ouverture SentinelCam
            </h2>
            <p className="mt-1 text-xs text-slate-300">
              Appuyez sur le bouton ci-dessous pour réveiller automatiquement l’application Android
              SentinelCam (Code <span className="font-mono font-bold text-emerald-300">{inviteCode}</span>).
            </p>
            <a
              href={androidIntentHref}
              className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-500 px-6 py-3.5 text-sm font-extrabold text-obsidian-950 shadow-lg transition hover:bg-emerald-400"
            >
              <ExternalLink className="h-4 w-4" />
              <span>Ouvrir l’application SentinelCam</span>
            </a>
            <button
              type="button"
              onClick={() => setInviteCode(null)}
              className="mt-3 text-xs text-slate-400 underline hover:text-white"
            >
              Continuer vers le Poste de Contrôle Web
            </button>
          </div>
        </div>
      )}

      <main className="pb-12">
        {nativeAndroid || activeTab === 'emitter' ? (
          <AndroidEmitterView isNativeAndroid={nativeAndroid} />
        ) : (
          <ControlStationView
            onBrokerHealthChange={handleBrokerHealth}
            initialCode={sharedAccessCode}
          />
        )}
      </main>

      <footer className="border-t border-slate-900/90 py-5 text-center text-[11px] text-slate-500">
        <div className="mx-auto max-w-7xl px-4">
          SentinelCam v7.0 • Historique GPS Hors-Ligne & Ultra-Éco • Talkie HP • Partages à la Carte •{' '}
          <a
            href="https://github.com/axeldelaro/campwa/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            className="text-emerald-400/80 hover:text-emerald-300 hover:underline"
          >
            Télécharger l’APK Android
          </a>
        </div>
      </footer>

      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        brokers={brokers}
      />
    </div>
  );
};

export default App;
