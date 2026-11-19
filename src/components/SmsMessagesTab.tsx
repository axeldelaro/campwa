import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  BookUser,
  MessageSquare,
  Plus,
  RefreshCw,
  Search,
  Send,
  X,
} from 'lucide-react';
import type {
  SharedContactItem,
  SmsMessageItem,
  SmsThreadItem,
} from '../types/sentinel';

interface SmsMessagesTabProps {
  threads: SmsThreadItem[];
  threadsLoading: boolean;
  threadsError: string | null;
  activeThread: SmsThreadItem | null;
  messages: SmsMessageItem[];
  messagesLoading: boolean;
  messagesError: string | null;
  sending: boolean;
  sendFeedback: { ok: boolean; text: string } | null;
  contacts: SharedContactItem[];
  contactsLoading: boolean;
  canSendSms: boolean;
  onRefreshThreads: () => Promise<void>;
  onOpenThread: (thread: SmsThreadItem) => Promise<void>;
  onCloseThread: () => void;
  onReply: (address: string, contactName: string, body: string) => Promise<void>;
  onLoadContacts: (query?: string) => Promise<void>;
}

function formatShortTime(ts: number): string {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) {
    return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
}

function initialsOf(label: string): string {
  const clean = label.trim();
  if (!clean) return '?';
  const parts = clean.split(/\s+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return clean.slice(0, 2).toUpperCase();
}

export const SmsMessagesTab: React.FC<SmsMessagesTabProps> = ({
  threads,
  threadsLoading,
  threadsError,
  activeThread,
  messages,
  messagesLoading,
  messagesError,
  sending,
  sendFeedback,
  contacts,
  contactsLoading,
  canSendSms,
  onRefreshThreads,
  onOpenThread,
  onCloseThread,
  onReply,
  onLoadContacts,
}) => {
  const [search, setSearch] = useState('');
  const [replyText, setReplyText] = useState('');
  const [newMsgOpen, setNewMsgOpen] = useState(false);
  const [newPhone, setNewPhone] = useState('');
  const [newName, setNewName] = useState('');
  const [newBody, setNewBody] = useState('');
  const [contactFilter, setContactFilter] = useState('');
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length, activeThread?.threadId]);

  const filteredThreads = threads.filter((t) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      t.contactName.toLowerCase().includes(q) ||
      t.address.toLowerCase().includes(q) ||
      t.snippet.toLowerCase().includes(q)
    );
  });

  const handleSendReply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeThread || !replyText.trim() || sending) return;
    const text = replyText.trim();
    setReplyText('');
    await onReply(activeThread.address, activeThread.contactName, text);
  };

  const handleOpenNewComposer = () => {
    setNewMsgOpen(true);
    if (contacts.length === 0 && !contactsLoading) {
      void onLoadContacts('');
    }
  };

  const handleSendNew = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPhone.trim() || !newBody.trim() || sending) return;
    await onReply(newPhone.trim(), newName.trim(), newBody.trim());
    setNewBody('');
    setNewPhone('');
    setNewName('');
    setNewMsgOpen(false);
    void onRefreshThreads();
  };

  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
      <div className="grid min-h-[62vh] md:grid-cols-[310px_1fr]">
        {/* LEFT PANE: CONVERSATIONS LIST */}
        <div
          className={`flex flex-col border-white/[0.06] md:border-r ${
            activeThread ? 'hidden md:flex' : 'flex'
          }`}
        >
          <div className="flex items-center justify-between gap-2 border-b border-white/[0.06] px-4 py-3">
            <h2 className="text-sm font-semibold text-white">Messages</h2>
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                onClick={handleOpenNewComposer}
                className="inline-flex items-center gap-1 rounded-lg bg-white px-2.5 py-1.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
              >
                <Plus className="h-3.5 w-3.5" />
                <span>Nouveau</span>
              </button>
              <button
                type="button"
                disabled={threadsLoading}
                onClick={() => void onRefreshThreads()}
                className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white"
                title="Actualiser"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${threadsLoading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>

          <div className="border-b border-white/[0.06] p-3">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Rechercher une conversation…"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 py-2 pl-9 pr-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />
            </div>
          </div>

          {threadsError && (
            <div className="m-3 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">
              {threadsError}
            </div>
          )}

          <div className="max-h-[58vh] flex-1 divide-y divide-white/[0.05] overflow-y-auto">
            {threadsLoading && threads.length === 0 ? (
              <div className="flex items-center justify-center py-12 text-xs text-zinc-400">
                <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                Chargement des messages…
              </div>
            ) : filteredThreads.length === 0 ? (
              <div className="py-12 text-center text-xs text-zinc-500">
                Aucune conversation trouvée.
              </div>
            ) : (
              filteredThreads.map((t) => {
                const displayLabel = t.contactName || t.address;
                const selected = activeThread?.threadId === t.threadId;
                return (
                  <button
                    key={t.threadId}
                    type="button"
                    onClick={() => void onOpenThread(t)}
                    className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${
                      selected ? 'bg-white/[0.07]' : 'hover:bg-white/[0.03]'
                    }`}
                  >
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/[0.08] text-xs font-semibold text-zinc-200">
                      {initialsOf(displayLabel)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="truncate text-xs font-semibold text-white">
                          {displayLabel}
                        </span>
                        <span className="shrink-0 text-[10px] text-zinc-500">
                          {formatShortTime(t.lastDate)}
                        </span>
                      </div>
                      <div className="mt-0.5 flex items-center justify-between gap-2">
                        <p className="truncate text-[11px] text-zinc-400">
                          {t.lastType === 'sent' ? 'Vous : ' : ''}
                          {t.snippet}
                        </p>
                        {t.unreadCount > 0 && (
                          <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-400" />
                        )}
                      </div>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>

        {/* RIGHT PANE: ACTIVE CONVERSATION BUBBLES + DIRECT REPLY */}
        <div className={`flex flex-col ${activeThread ? 'flex' : 'hidden md:flex'}`}>
          {!activeThread ? (
            <div className="flex flex-1 flex-col items-center justify-center p-8 text-center text-xs text-zinc-500">
              <MessageSquare className="mb-2 h-7 w-7 text-zinc-600" />
              Sélectionnez une conversation pour lire les messages et répondre directement.
            </div>
          ) : (
            <>
              {/* Thread Header */}
              <div className="flex items-center justify-between gap-2 border-b border-white/[0.06] px-4 py-3">
                <div className="flex min-w-0 items-center gap-2.5">
                  <button
                    type="button"
                    onClick={onCloseThread}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-white/10 bg-zinc-950 text-zinc-300 md:hidden"
                  >
                    <ArrowLeft className="h-4 w-4" />
                  </button>
                  <div className="min-w-0">
                    <div className="truncate text-sm font-semibold text-white">
                      {activeThread.contactName || activeThread.address}
                    </div>
                    {activeThread.contactName && (
                      <div className="truncate font-mono text-[11px] text-zinc-500">
                        {activeThread.address}
                      </div>
                    )}
                  </div>
                </div>
                <button
                  type="button"
                  disabled={messagesLoading}
                  onClick={() => void onOpenThread(activeThread)}
                  className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white"
                  title="Actualiser la conversation"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${messagesLoading ? 'animate-spin' : ''}`} />
                </button>
              </div>

              {/* Chat Bubbles */}
              <div className="max-h-[48vh] flex-1 space-y-2.5 overflow-y-auto p-4">
                {messagesError && (
                  <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
                    {messagesError}
                  </div>
                )}

                {messagesLoading && messages.length === 0 ? (
                  <div className="flex items-center justify-center py-12 text-xs text-zinc-400">
                    <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                    Chargement…
                  </div>
                ) : (
                  messages.map((m) => {
                    const isSent = m.type === 'sent';
                    return (
                      <div
                        key={m.id}
                        className={`flex flex-col ${isSent ? 'items-end' : 'items-start'}`}
                      >
                        <div
                          className={`max-w-[82%] whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2 text-xs leading-relaxed ${
                            isSent
                              ? 'rounded-br-md bg-emerald-500 text-zinc-950 font-medium'
                              : 'rounded-bl-md border border-white/[0.07] bg-zinc-950 text-zinc-100'
                          }`}
                        >
                          {m.body}
                        </div>
                        <span className="mt-1 px-1 text-[10px] text-zinc-500">
                          {new Date(m.date).toLocaleString('fr-FR', {
                            day: '2-digit',
                            month: '2-digit',
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </span>
                      </div>
                    );
                  })
                )}
                <div ref={chatEndRef} />
              </div>

              {sendFeedback && (
                <div
                  className={`mx-4 mb-2 rounded-xl border px-3 py-1.5 text-[11px] ${
                    sendFeedback.ok
                      ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
                      : 'border-rose-500/30 bg-rose-500/10 text-rose-200'
                  }`}
                >
                  {sendFeedback.text}
                </div>
              )}

              {/* Direct Reply Composer (Answers directly from child's SIM!) */}
              <form
                onSubmit={(e) => void handleSendReply(e)}
                className="flex items-center gap-2 border-t border-white/[0.06] bg-zinc-950/60 p-3"
              >
                <input
                  type="text"
                  maxLength={640}
                  disabled={!canSendSms || sending}
                  value={replyText}
                  onChange={(e) => setReplyText(e.target.value)}
                  placeholder={
                    canSendSms
                      ? `Répondre à ${activeThread.contactName || activeThread.address}…`
                      : 'Activez « Envoi SMS » dans Règles pour répondre'
                  }
                  className="min-w-0 flex-1 rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none disabled:opacity-45"
                />
                <button
                  type="submit"
                  disabled={!canSendSms || sending || !replyText.trim()}
                  className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-white text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-35"
                  title="Envoyer"
                >
                  {sending ? (
                    <RefreshCw className="h-4 w-4 animate-spin" />
                  ) : (
                    <Send className="h-4 w-4" />
                  )}
                </button>
              </form>
            </>
          )}
        </div>
      </div>

      {/* NEW MESSAGE MODAL WITH CONTACT PICKER */}
      {newMsgOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-sm"
          onClick={() => setNewMsgOpen(false)}
        >
          <div
            className="w-full max-w-md space-y-3 rounded-2xl border border-white/10 bg-zinc-900 p-5"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-white">Nouveau message</h3>
              <button
                type="button"
                onClick={() => setNewMsgOpen(false)}
                className="rounded-lg p-1 text-zinc-400 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <form onSubmit={(e) => void handleSendNew(e)} className="space-y-3">
              <input
                type="tel"
                required
                value={newPhone}
                onChange={(e) => setNewPhone(e.target.value)}
                placeholder="Numéro (ex. +33 6 12 34 56 78)"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3.5 py-2.5 font-mono text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />

              {/* Quick Contact Picker */}
              {contacts.length > 0 && (
                <div className="space-y-1.5 rounded-xl border border-white/[0.06] bg-zinc-950 p-2.5">
                  <div className="flex items-center gap-1.5 text-[11px] text-zinc-400">
                    <BookUser className="h-3.5 w-3.5" />
                    <input
                      type="text"
                      value={contactFilter}
                      onChange={(e) => setContactFilter(e.target.value)}
                      placeholder="Choisir un contact…"
                      className="w-full bg-transparent text-xs text-white placeholder-zinc-500 focus:outline-none"
                    />
                  </div>
                  <div className="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto pt-1">
                    {contacts
                      .filter(
                        (c) =>
                          c.phones.length > 0 &&
                          (!contactFilter.trim() ||
                            c.name.toLowerCase().includes(contactFilter.toLowerCase()))
                      )
                      .slice(0, 20)
                      .map((c) => (
                        <button
                          key={c.id}
                          type="button"
                          onClick={() => {
                            setNewPhone(c.phones[0]);
                            setNewName(c.name);
                          }}
                          className="rounded-lg border border-white/10 bg-zinc-900 px-2.5 py-1 text-[11px] text-zinc-200 hover:border-emerald-400"
                        >
                          {c.name}
                        </button>
                      ))}
                  </div>
                </div>
              )}

              <textarea
                rows={3}
                maxLength={640}
                required
                value={newBody}
                onChange={(e) => setNewBody(e.target.value)}
                placeholder="Votre message…"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 p-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
              />

              <button
                type="submit"
                disabled={!canSendSms || sending || !newPhone.trim() || !newBody.trim()}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-white py-2.5 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-35"
              >
                <Send className="h-3.5 w-3.5" />
                <span>{sending ? 'Envoi…' : 'Envoyer depuis le téléphone'}</span>
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
