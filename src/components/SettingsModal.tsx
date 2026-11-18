import React, { useState } from 'react';
import { Globe, Plus, Server, Trash2, Wifi, X } from 'lucide-react';
import type { BrokerHealth } from '../services/signalingService';
import { loadCustomTurnServers, saveCustomTurnServers } from '../services/deviceVault';
import type { TurnServerSetting } from '../types/sentinel';

interface SettingsModalProps {
  open: boolean;
  onClose: () => void;
  brokers: BrokerHealth[];
}

export const SettingsModal: React.FC<SettingsModalProps> = ({ open, onClose, brokers }) => {
  const [turnServers, setTurnServers] = useState<TurnServerSetting[]>(() =>
    loadCustomTurnServers()
  );
  const [urls, setUrls] = useState('');
  const [username, setUsername] = useState('');
  const [credential, setCredential] = useState('');

  if (!open) return null;

  const handleAddTurn = (e: React.FormEvent) => {
    e.preventDefault();
    if (!urls.trim()) return;
    const updated: TurnServerSetting[] = [
      ...turnServers,
      {
        urls: urls.trim(),
        username: username.trim() || undefined,
        credential: credential.trim() || undefined,
      },
    ];
    setTurnServers(updated);
    saveCustomTurnServers(updated);
    setUrls('');
    setUsername('');
    setCredential('');
  };

  const handleDeleteTurn = (idx: number) => {
    const updated = turnServers.filter((_, i) => i !== idx);
    setTurnServers(updated);
    saveCustomTurnServers(updated);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-md">
      <div className="max-h-[90vh] w-full max-w-xl overflow-y-auto rounded-2xl border border-slate-800 bg-obsidian-900 p-6 shadow-2xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <Server className="h-5 w-5 text-emerald-400" />
            <h3 className="text-base font-bold text-white">
              Infrastructure Réseau, Courtiers WSS & Relais STUN/TURN
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-800 hover:text-white"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* MQTT Brokers Health */}
        <div className="mt-5">
          <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-slate-400">
            <Wifi className="h-3.5 w-3.5 text-emerald-400" />
            Courtiers de Signalisation Serverless Redondants (E2EE AES-256-GCM)
          </h4>
          <div className="mt-2.5 space-y-2">
            {brokers.length === 0 ? (
              <div className="rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2.5 text-xs text-slate-400">
                Les courtiers WSS se connectent automatiquement dès la saisie d’un code portail.
              </div>
            ) : (
              brokers.map((b) => (
                <div
                  key={b.url}
                  className="flex items-center justify-between rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2.5 text-xs"
                >
                  <div>
                    <div className="font-semibold text-slate-200">{b.name}</div>
                    <div className="font-mono text-[11px] text-slate-500">{b.url}</div>
                  </div>
                  <span
                    className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 font-medium ${
                      b.connected
                        ? 'bg-emerald-500/15 text-emerald-300'
                        : 'bg-amber-500/15 text-amber-300'
                    }`}
                  >
                    <span
                      className={`h-1.5 w-1.5 rounded-full ${
                        b.connected ? 'bg-emerald-400' : 'bg-amber-400'
                      }`}
                    />
                    {b.connected ? 'Connecté' : 'Veille / Reconnexion'}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>

        {/* STUN / TURN Configuration */}
        <div className="mt-6">
          <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-slate-400">
            <Globe className="h-3.5 w-3.5 text-cyan-400" />
            Traversée NAT WebRTC (STUN Google/Cloudflare inclus + TURN optionnel)
          </h4>
          <p className="mt-1 text-xs text-slate-400">
            Les serveurs STUN haute disponibilité Google & Cloudflare sont actifs par défaut. Si
            votre smartphone se trouve derrière un NAT symétrique 4G/5G strict, vous pouvez ajouter
            un relais TURN personnalisé.
          </p>

          {turnServers.length > 0 && (
            <div className="mt-3 space-y-2">
              {turnServers.map((srv, idx) => (
                <div
                  key={`${srv.urls}-${idx}`}
                  className="flex items-center justify-between rounded-xl border border-slate-800 bg-obsidian-950 px-3.5 py-2 text-xs"
                >
                  <div className="min-w-0 flex-1 truncate">
                    <div className="truncate font-mono text-slate-200">{srv.urls}</div>
                    {srv.username && (
                      <div className="text-[11px] text-slate-500">Utilisateur : {srv.username}</div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => handleDeleteTurn(idx)}
                    className="ml-2 rounded-lg p-1.5 text-rose-400 hover:bg-rose-500/10"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
          )}

          <form onSubmit={handleAddTurn} className="mt-3 space-y-2.5 rounded-xl border border-slate-800 bg-obsidian-950/70 p-3.5">
            <div>
              <label className="block text-[11px] font-medium text-slate-400">
                URI Serveur TURN / TURNS (ex: turn:relay.example.com:3478)
              </label>
              <input
                type="text"
                value={urls}
                onChange={(e) => setUrls(e.target.value)}
                placeholder="turns:openrelay.example.org:443?transport=tcp"
                className="mt-1 w-full rounded-lg border border-slate-800 bg-obsidian-900 px-3 py-1.5 font-mono text-xs text-white placeholder-slate-600 focus:border-emerald-500 focus:outline-none"
              />
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="Identifiant (optionnel)"
                className="rounded-lg border border-slate-800 bg-obsidian-900 px-3 py-1.5 text-xs text-white placeholder-slate-600 focus:border-emerald-500 focus:outline-none"
              />
              <input
                type="password"
                value={credential}
                onChange={(e) => setCredential(e.target.value)}
                placeholder="Mot de passe / Clé (optionnel)"
                className="rounded-lg border border-slate-800 bg-obsidian-900 px-3 py-1.5 text-xs text-white placeholder-slate-600 focus:border-emerald-500 focus:outline-none"
              />
            </div>
            <button
              type="submit"
              className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-500/30 bg-emerald-500/15 px-3 py-1.5 text-xs font-semibold text-emerald-300 transition hover:bg-emerald-500/25"
            >
              <Plus className="h-3.5 w-3.5" />
              <span>Ajouter le serveur TURN</span>
            </button>
          </form>
        </div>
      </div>
    </div>
  );
};
