import React, { useCallback, useState } from 'react';
import { AndroidEmitterView } from './components/AndroidEmitterView';
import { ControlStationView } from './components/ControlStationView';
import { HeaderBar } from './components/HeaderBar';
import { SettingsModal } from './components/SettingsModal';
import { isAndroidNativeApp } from './native/sentinelPlugin';
import type { BrokerHealth } from './services/signalingService';

const APK_RELEASE_URL = 'https://github.com/axeldelaro/campwa/releases/latest';

export const App: React.FC = () => {
  const nativeAndroid = isAndroidNativeApp();
  const [activeTab, setActiveTab] = useState<'controller' | 'emitter'>(
    nativeAndroid ? 'emitter' : 'controller'
  );
  const [brokerConnected, setBrokerConnected] = useState(false);
  const [brokers, setBrokers] = useState<BrokerHealth[]>([]);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const handleBrokerHealth = useCallback((connected: boolean, health: BrokerHealth[]) => {
    setBrokerConnected(connected);
    setBrokers(health);
  }, []);

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

      <main className="pb-12">
        {nativeAndroid || activeTab === 'emitter' ? (
          <AndroidEmitterView isNativeAndroid={nativeAndroid} />
        ) : (
          <ControlStationView onBrokerHealthChange={handleBrokerHealth} />
        )}
      </main>

      <footer className="border-t border-slate-900/90 py-5 text-center text-[11px] text-slate-500">
        <div className="mx-auto max-w-7xl px-4">
          SentinelCam v1.0 • Architecture WebRTC Serverless E2EE (PBKDF2-SHA256 • HKDF • AES-256-GCM
          • DTLS-SRTP) •{' '}
          <a
            href="https://github.com/axeldelaro/campwa"
            target="_blank"
            rel="noopener noreferrer"
            className="text-emerald-400/80 hover:text-emerald-300 hover:underline"
          >
            GitHub Repository & Releases APK
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
