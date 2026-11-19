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
    <div className="min-h-screen bg-[#09090b] text-zinc-100">
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
          <div className="rounded-2xl border border-white/10 bg-zinc-900/90 p-5 text-center">
            <BellRing className="mx-auto h-7 w-7 text-emerald-400" />
            <h2 className="mt-3 text-base font-semibold text-white">Ouvrir SentinelCam</h2>
            <p className="mt-1 text-xs text-zinc-400">
              Code <span className="font-mono text-white">{inviteCode}</span>
            </p>
            <a
              href={androidIntentHref}
              className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-white px-5 py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              <span>Lancer l’application</span>
            </a>
            <button
              type="button"
              onClick={() => setInviteCode(null)}
              className="mt-3 text-xs text-zinc-500 hover:text-zinc-300"
            >
              Continuer sur le Web
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

      {!nativeAndroid && (
        <footer className="border-t border-white/[0.05] py-5 text-center text-xs text-zinc-600">
          <div className="mx-auto flex max-w-5xl items-center justify-center gap-4 px-4">
            <span>SentinelCam</span>
            <span>·</span>
            <a
              href={APK_RELEASE_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="text-zinc-400 transition hover:text-white"
            >
              Application Android
            </a>
          </div>
        </footer>
      )}

      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        brokers={brokers}
      />
    </div>
  );
};

export default App;
