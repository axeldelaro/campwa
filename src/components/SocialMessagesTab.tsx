import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowLeft,
  Bell,
  Check,
  MessageSquare,
  Plus,
  RefreshCw,
  Search,
  Send,
  Trash2,
  X,
} from 'lucide-react';
import type {
  DataSocialMessagesMsg,
  DataSocialThreadsMsg,
  SocialThreadItem,
} from '../types/sentinel';

interface SocialMessagesTabProps {
  threadsData: DataSocialThreadsMsg | null;
  messagesData: DataSocialMessagesMsg | null;
  loadingThreads: boolean;
  loadingMessages: boolean;
  onRefreshThreads: (appFilter?: string) => Promise<void>;
  onOpenThread: (thread: SocialThreadItem) => Promise<void>;
  onReplyThread: (threadKey: string, body: string) => Promise<void>;
  onSaveKeywords: (keywords: string[]) => Promise<void>;
  onClearArchive: () => Promise<void>;
}

function appBadgeColor(appLabel: string): string {
  const low = appLabel.toLowerCase();
  if (low.includes('whatsapp')) return 'border-emerald-500/30 bg-emerald-500/15 text-emerald-300';
  if (low.includes('instagram')) return 'border-pink-500/30 bg-pink-500/15 text-pink-300';
  if (low.includes('snapchat')) return 'border-amber-400/30 bg-amber-400/15 text-amber-200';
  if (low.includes('discord')) return 'border-indigo-400/30 bg-indigo-500/15 text-indigo-300';
  if (low.includes('messenger')) return 'border-sky-500/30 bg-sky-500/15 text-sky-300';
  if (low.includes('tiktok')) return 'border-fuchsia-500/30 bg-fuchsia-500/15 text-fuchsia-300';
  if (low.includes('telegram')) return 'border-cyan-500/30 bg-cyan-500/15 text-cyan-300';
  return 'border-white/15 bg-white/10 text-zinc-200';
}

function formatShortTime(ts: number): string {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const sameDay =
    d.getDate() === now.getDate() &&
    d.getMonth() === now.getMonth() &&
    d.getFullYear() === now.getFullYear();
  if (sameDay) {
    return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) +
    ' ' +
    d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

export const SocialMessagesTab: React.FC<SocialMessagesTabProps> = ({
  threadsData,
  messagesData,
  loadingThreads,
  loadingMessages,
  onRefreshThreads,
  onOpenThread,
  onReplyThread,
  onSaveKeywords,
  onClearArchive,
}) => {
  const [selectedApp, setSelectedApp] = useState<string>('');
  const [search, setSearch] = useState('');
  const [activeThread, setActiveThread] = useState<SocialThreadItem | null>(null);
  const [replyText, setReplyText] = useState('');
  const [sendingReply, setSendingReply] = useState(false);

  const [showKeywords, setShowKeywords] = useState(false);
  const [newKeyword, setNewKeyword] = useState('');
  const [savingKw, setSavingKw] = useState(false);
  const [confirmClear, setConfirmClear] = useState(false);

  const threads = threadsData?.threads ?? [];
  const apps = threadsData?.apps ?? [];
  const keywords = threadsData?.keywords ?? [];
  const hasAccess = threadsData?.hasNotificationAccess ?? true;

  const filteredThreads = useMemo(() => {
    const q = search.trim().toLowerCase();
    return threads.filter((t) => {
      if (selectedApp && t.appLabel.toLowerCase() !== selectedApp.toLowerCase()) return false;
      if (!q) return true;
      return (
        t.sender.toLowerCase().includes(q) ||
        t.snippet.toLowerCase().includes(q) ||
        t.appLabel.toLowerCase().includes(q)
      );
    });
  }, [threads, selectedApp, search]);

  const activeMessages =
    activeThread && messagesData?.threadKey === activeThread.threadKey
      ? messagesData.messages
      : [];
  const canReplyActive =
    activeThread && messagesData?.threadKey === activeThread.threadKey
      ? messagesData.canReply
      : Boolean(activeThread?.canReply);

  const handleSelectThread = async (t: SocialThreadItem) => {
    setActiveThread(t);
    setReplyText('');
    await onOpenThread(t);
  };

  const handleSendReply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeThread || !replyText.trim() || sendingReply) return;
    setSendingReply(true);
    try {
      await onReplyThread(activeThread.threadKey, replyText.trim());
      setReplyText('');
    } finally {
      setSendingReply(false);
    }
  };

  const handleAddKeyword = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = newKeyword.trim();
    if (!clean || savingKw) return;
    const next = Array.from(new Set([...keywords, clean]));
    setSavingKw(true);
    try {
      await onSaveKeywords(next);
      setNewKeyword('');
    } finally {
      setSavingKw(false);
    }
  };

  const handleRemoveKeyword = async (kw: string) => {
    if (savingKw) return;
    const next = keywords.filter((k) => k !== kw);
    setSavingKw(true);
    try {
      await onSaveKeywords(next);
    } finally {
      setSavingKw(false);
    }
  };

  return (
    <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
            <MessageSquare className="h-4 w-4 text-emerald-400" />
            Réseaux sociaux & Messageries
          </h3>
          <p className="text-[11px] text-zinc-400">
            WhatsApp, Instagram, Snapchat, Discord, Messenger, TikTok — archivés même s’ils sont supprimés.
          </p>
        </div>

        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setShowKeywords((v) => !v)}
            className={`inline-flex items-center gap-1.5 rounded-xl border px-2.5 py-1.5 text-xs font-medium transition ${
              showKeywords
                ? 'border-amber-400/40 bg-amber-500/15 text-amber-200'
                : 'border-white/10 bg-zinc-950 text-zinc-300 hover:border-white/20 hover:text-white'
            }`}
          >
            <Bell className="h-3.5 w-3.5" />
            Mots-clés ({keywords.length})
          </button>

          <button
            type="button"
            onClick={() => void onRefreshThreads(selectedApp)}
            disabled={loadingThreads}
            className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-2.5 py-1.5 text-xs font-medium text-zinc-200 transition hover:border-white/20 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loadingThreads ? 'animate-spin' : ''}`} />
            Actualiser
          </button>
        </div>
      </div>

      {/* Missing permission notice */}
      {threadsData && !hasAccess && (
        <div className="flex items-start gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
          <div>
            <p className="font-medium text-amber-100">Accès aux notifications non activé sur le smartphone</p>
            <p className="mt-0.5 text-[11px] text-amber-200/80">
              Ouvrez l’application sur le téléphone de l’enfant et appuyez sur « Messages réseaux sociaux » pour activer l’archivage discret (les notifications restent 100 % visibles pour l’enfant).
            </p>
          </div>
        </div>
      )}

      {/* Sensitive Alert Keywords Panel */}
      {showKeywords && (
        <div className="space-y-2.5 rounded-xl border border-amber-500/20 bg-zinc-950/90 p-3">
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium text-amber-200">
              Alertes automatiques sur mots sensibles
            </p>
            <span className="text-[10px] text-zinc-500">
              Déclenche une alerte sur l’Accueil
            </span>
          </div>

          <form onSubmit={handleAddKeyword} className="flex gap-2">
            <input
              type="text"
              value={newKeyword}
              onChange={(e) => setNewKeyword(e.target.value)}
              placeholder="Ex : harcèlement, secret, nude, fugue..."
              maxLength={40}
              className="flex-1 rounded-lg border border-white/10 bg-zinc-900 px-3 py-1.5 text-xs text-white placeholder-zinc-500 outline-none focus:border-amber-400/50"
            />
            <button
              type="submit"
              disabled={!newKeyword.trim() || savingKw}
              className="inline-flex items-center gap-1 rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-zinc-950 transition hover:bg-amber-400 disabled:opacity-40"
            >
              <Plus className="h-3.5 w-3.5" />
              Ajouter
            </button>
          </form>

          {keywords.length === 0 ? (
            <p className="text-[11px] text-zinc-500">
              Aucun mot-clé configuré. Ajoutez des mots pour être alerté dès qu’ils apparaissent dans un message entrant.
            </p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {keywords.map((kw) => (
                <span
                  key={kw}
                  className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-0.5 text-[11px] font-medium text-amber-200"
                >
                  {kw}
                  <button
                    type="button"
                    onClick={() => void handleRemoveKeyword(kw)}
                    className="rounded-full p-0.5 hover:bg-amber-500/20"
                    title="Retirer"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Thread Detail View */}
      {activeThread ? (
        <div className="space-y-3">
          <div className="flex items-center justify-between gap-2 rounded-xl border border-white/[0.06] bg-zinc-950 px-3 py-2">
            <div className="flex items-center gap-2 min-w-0">
              <button
                type="button"
                onClick={() => setActiveThread(null)}
                className="inline-flex items-center gap-1 rounded-lg border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5 hover:text-white"
              >
                <ArrowLeft className="h-3.5 w-3.5" />
                Retour
              </button>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-white">{activeThread.sender}</p>
                <div className="flex items-center gap-1.5">
                  <span
                    className={`rounded-md border px-1.5 py-0.5 text-[10px] font-medium ${appBadgeColor(
                      activeThread.appLabel
                    )}`}
                  >
                    {activeThread.appLabel}
                  </span>
                  {canReplyActive && (
                    <span className="inline-flex items-center gap-1 text-[10px] text-emerald-400">
                      <Check className="h-3 w-3" />
                      Réponse directe active
                    </span>
                  )}
                </div>
              </div>
            </div>
            <button
              type="button"
              onClick={() => void onOpenThread(activeThread)}
              disabled={loadingMessages}
              className="rounded-lg border border-white/10 p-1.5 text-zinc-400 hover:text-white"
              title="Actualiser la conversation"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${loadingMessages ? 'animate-spin' : ''}`} />
            </button>
          </div>

          <div className="max-h-[380px] space-y-2 overflow-y-auto rounded-xl border border-white/[0.06] bg-zinc-950/70 p-3">
            {loadingMessages && activeMessages.length === 0 ? (
              <p className="py-8 text-center text-xs text-zinc-500">Chargement des messages...</p>
            ) : activeMessages.length === 0 ? (
              <p className="py-8 text-center text-xs text-zinc-500">Aucun message dans cette conversation.</p>
            ) : (
              activeMessages.map((m) => {
                const isOut = m.direction === 'out';
                return (
                  <div key={m.id} className={`flex ${isOut ? 'justify-end' : 'justify-start'}`}>
                    <div
                      className={`max-w-[85%] rounded-2xl px-3 py-2 text-xs leading-relaxed ${
                        isOut
                          ? 'bg-indigo-600 text-white rounded-br-sm'
                          : 'border border-white/[0.08] bg-zinc-900 text-zinc-100 rounded-bl-sm'
                      }`}
                    >
                      <p className="whitespace-pre-wrap break-words">{m.body}</p>
                      <p
                        className={`mt-1 text-[10px] ${
                          isOut ? 'text-indigo-200 text-right' : 'text-zinc-500'
                        }`}
                      >
                        {isOut ? 'Répondu à distance • ' : ''}
                        {formatShortTime(m.timestamp)}
                      </p>
                    </div>
                  </div>
                );
              })
            )}
          </div>

          {/* Direct Reply Form */}
          <form onSubmit={handleSendReply} className="space-y-1.5">
            <div className="flex gap-2">
              <input
                type="text"
                value={replyText}
                onChange={(e) => setReplyText(e.target.value)}
                disabled={!canReplyActive || sendingReply}
                placeholder={
                  canReplyActive
                    ? `Répondre directement sur ${activeThread.appLabel}...`
                    : 'Réponse directe disponible dès le prochain message reçu...'
                }
                maxLength={500}
                className="flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 outline-none focus:border-emerald-400/50 disabled:opacity-50"
              />
              <button
                type="submit"
                disabled={!canReplyActive || !replyText.trim() || sendingReply}
                className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-500 px-3.5 py-2 text-xs font-semibold text-zinc-950 transition hover:bg-emerald-400 disabled:opacity-40"
              >
                <Send className="h-3.5 w-3.5" />
                Envoyer
              </button>
            </div>
            {!canReplyActive && (
              <p className="text-[10px] text-zinc-500">
                La réponse directe utilise la notification active du téléphone. Si la notification a été effacée, elle se réactivera automatiquement au prochain message reçu.
              </p>
            )}
          </form>
        </div>
      ) : (
        /* Threads List View */
        <div className="space-y-3">
          {/* App filter pills */}
          {apps.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                onClick={() => setSelectedApp('')}
                className={`rounded-full px-2.5 py-1 text-[11px] font-medium transition ${
                  selectedApp === ''
                    ? 'bg-white text-zinc-950'
                    : 'border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                }`}
              >
                Tous ({threads.length})
              </button>
              {apps.map((app) => (
                <button
                  key={app}
                  type="button"
                  onClick={() => setSelectedApp(app === selectedApp ? '' : app)}
                  className={`rounded-full border px-2.5 py-1 text-[11px] font-medium transition ${
                    selectedApp === app
                      ? 'border-white bg-white text-zinc-950'
                      : 'border-white/10 bg-zinc-950 text-zinc-300 hover:border-white/25'
                  }`}
                >
                  {app}
                </button>
              ))}
            </div>
          )}

          {/* Search bar */}
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Rechercher un contact, une application ou un mot..."
              className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-9 pr-3 text-xs text-white placeholder-zinc-500 outline-none focus:border-white/25"
            />
          </div>

          {filteredThreads.length === 0 ? (
            <div className="rounded-xl border border-dashed border-white/10 bg-zinc-950/50 px-4 py-8 text-center">
              <MessageSquare className="mx-auto mb-2 h-6 w-6 text-zinc-600" />
              <p className="text-xs font-medium text-zinc-300">
                {loadingThreads
                  ? 'Chargement des conversations...'
                  : 'Aucun message réseau archivé pour le moment'}
              </p>
              <p className="mt-1 text-[11px] text-zinc-500">
                Dès que le smartphone recevra un message WhatsApp, Instagram, Snapchat, Discord ou Messenger, il apparaîtra ici automatiquement.
              </p>
            </div>
          ) : (
            <div className="max-h-[420px] space-y-1.5 overflow-y-auto pr-0.5">
              {filteredThreads.map((t) => (
                <button
                  key={t.threadKey}
                  type="button"
                  onClick={() => void handleSelectThread(t)}
                  className="flex w-full items-start justify-between gap-3 rounded-xl border border-white/[0.06] bg-zinc-950/80 p-3 text-left transition hover:border-white/20 hover:bg-zinc-900"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="truncate text-xs font-semibold text-white">{t.sender}</span>
                      <span
                        className={`rounded-md border px-1.5 py-0.5 text-[10px] font-medium ${appBadgeColor(
                          t.appLabel
                        )}`}
                      >
                        {t.appLabel}
                      </span>
                      {t.canReply && (
                        <span
                          className="h-2 w-2 rounded-full bg-emerald-400"
                          title="Réponse directe possible"
                        />
                      )}
                    </div>
                    <p className="mt-1 truncate text-[11px] text-zinc-400">
                      {t.lastDirection === 'out' ? 'Vous : ' : ''}
                      {t.snippet}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className="text-[10px] text-zinc-500">{formatShortTime(t.lastDate)}</p>
                    <span className="mt-1 inline-block rounded-full bg-white/[0.06] px-1.5 py-0.5 text-[10px] text-zinc-400">
                      {t.messageCount}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          )}

          {/* Footer clear action */}
          {threads.length > 0 && (
            <div className="flex items-center justify-between border-t border-white/[0.06] pt-2.5 text-[11px] text-zinc-500">
              <span>{threadsData?.totalMessages ?? 0} message(s) archivé(s) sur l’appareil</span>
              {confirmClear ? (
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={async () => {
                      await onClearArchive();
                      setConfirmClear(false);
                    }}
                    className="rounded-lg bg-rose-500 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-rose-400"
                  >
                    Confirmer
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmClear(false)}
                    className="rounded-lg border border-white/10 px-2 py-1 text-[11px] text-zinc-300"
                  >
                    Annuler
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmClear(true)}
                  className="inline-flex items-center gap-1 text-[11px] text-zinc-400 hover:text-rose-300"
                >
                  <Trash2 className="h-3 w-3" />
                  Vider l’archive
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
