import React, { useEffect, useState } from 'react';
import {
  Bell,
  BellRing,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  ExternalLink,
  Send,
  Smartphone,
} from 'lucide-react';
import {
  deriveChildAlertTopic,
  deriveFamilyAlertTopic,
  sendTestParentPushAlert,
  subscribeParentAlertSse,
} from '../services/parentPushAlerts';

interface ParentPushAlertsCardProps {
  familyKey?: string;
  childCode?: string;
  childCodes?: string[];
  compact?: boolean;
}

export const ParentPushAlertsCard: React.FC<ParentPushAlertsCardProps> = ({
  familyKey,
  childCode,
  childCodes = [],
  compact = false,
}) => {
  const [open, setOpen] = useState(false);
  const [primaryTopic, setPrimaryTopic] = useState('');
  const [allTopics, setAllTopics] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testSent, setTestSent] = useState(false);
  const [notifPerm, setNotifPerm] = useState<NotificationPermission>(() =>
    typeof window !== 'undefined' && 'Notification' in window ? Notification.permission : 'default'
  );
  const [latestBanner, setLatestBanner] = useState<{ title: string; message: string } | null>(null);

  useEffect(() => {
    let active = true;
    const compute = async () => {
      const list: string[] = [];
      let main = '';
      if (familyKey && familyKey.length === 12) {
        const ft = await deriveFamilyAlertTopic(familyKey);
        if (ft) {
          list.push(ft);
          main = ft;
        }
      }
      if (childCode && childCode.length === 6) {
        const ct = await deriveChildAlertTopic(childCode);
        if (ct) {
          list.push(ct);
          if (!main) main = ct;
        }
      }
      for (const c of childCodes) {
        const ct = await deriveChildAlertTopic(c);
        if (ct) list.push(ct);
      }
      if (active) {
        setPrimaryTopic(main || list[0] || '');
        setAllTopics(Array.from(new Set(list)));
      }
    };
    void compute();
    return () => {
      active = false;
    };
  }, [familyKey, childCode, JSON.stringify(childCodes)]);

  useEffect(() => {
    if (allTopics.length === 0) return;
    return subscribeParentAlertSse(allTopics, (title, message) => {
      setLatestBanner({ title, message });
    });
  }, [JSON.stringify(allTopics)]);

  const handleRequestBrowserNotif = async () => {
    if (typeof window === 'undefined' || !('Notification' in window)) return;
    try {
      const res = await Notification.requestPermission();
      setNotifPerm(res);
      if (res === 'granted' && primaryTopic) {
        void sendTestParentPushAlert(primaryTopic, 'SentinelCam');
      }
    } catch {
      // Ignore
    }
  };

  const handleCopyTopic = async () => {
    if (!primaryTopic) return;
    try {
      await navigator.clipboard.writeText(primaryTopic);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2500);
    } catch {
      // Ignore
    }
  };

  const handleTestPush = async () => {
    if (!primaryTopic) return;
    setTesting(true);
    setTestSent(false);
    try {
      const ok = await sendTestParentPushAlert(primaryTopic, 'SentinelCam');
      if (ok) {
        setTestSent(true);
        window.setTimeout(() => setTestSent(false), 3500);
      }
    } finally {
      setTesting(false);
    }
  };

  if (!primaryTopic) return null;

  return (
    <div className="space-y-2">
      {latestBanner && (
        <div className="flex items-center justify-between gap-2 rounded-xl border border-amber-500/40 bg-amber-500/15 px-3.5 py-2.5 text-xs text-amber-100">
          <div className="min-w-0">
            <span className="font-semibold">{latestBanner.title}</span> · {latestBanner.message}
          </div>
          <button
            type="button"
            onClick={() => setLatestBanner(null)}
            className="shrink-0 text-[11px] text-amber-300 hover:text-white"
          >
            Fermer
          </button>
        </div>
      )}

      <div className="overflow-hidden rounded-2xl border border-white/[0.08] bg-zinc-900/75">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left transition hover:bg-white/[0.02]"
        >
          <div className="flex min-w-0 items-center gap-2.5">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-sky-500/15 text-sky-400 ring-1 ring-sky-500/30">
              <BellRing className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-white">
                <span>Notifications sur votre téléphone (sans ouvrir le site)</span>
                {notifPerm === 'granted' && (
                  <span className="rounded bg-emerald-500/20 px-1.5 py-0.5 text-[10px] font-medium text-emerald-300">
                    Actif
                  </span>
                )}
              </div>
              {!compact && (
                <p className="truncate text-[11px] text-zinc-400">
                  Recevez chaque demande (+15 min, règles) et alerte (vitesse, zone, mot-clé, intrus) en direct
                </p>
              )}
            </div>
          </div>
          {open ? (
            <ChevronUp className="h-4 w-4 shrink-0 text-zinc-400" />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 text-zinc-400" />
          )}
        </button>

        {open && (
          <div className="space-y-3 border-t border-white/[0.06] bg-zinc-950/80 p-4 text-xs">
            {/* Method 1: 24/7 Lock-Screen Push even with browser closed */}
            <div className="space-y-2 rounded-xl border border-sky-500/25 bg-sky-500/[0.07] p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1.5 font-semibold text-sky-100">
                  <Smartphone className="h-3.5 w-3.5 text-sky-400" />
                  1. Sur téléphone verrouillé 24h/24 (site fermé)
                </span>
                <span className="rounded bg-sky-500/20 px-1.5 py-0.5 text-[10px] text-sky-200">
                  Android & iPhone
                </span>
              </div>
              <p className="text-[11px] leading-relaxed text-zinc-300">
                Abonnez votre téléphone en 1 clic via l’application gratuite <strong>ntfy</strong> (sans compte) ou activez le <strong>Mode Notifications Parent</strong> dans l’APK SentinelCam sur votre propre téléphone :
              </p>

              <div className="flex flex-wrap items-center gap-2 pt-1">
                <a
                  href={`ntfy://ntfy.sh/${primaryTopic}`}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-sky-400 px-3 py-2 text-xs font-semibold text-zinc-950 transition hover:bg-sky-300"
                >
                  <Bell className="h-3.5 w-3.5" />
                  Abonner mon téléphone en 1 clic
                </a>

                <button
                  type="button"
                  onClick={() => void handleCopyTopic()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-white/15 bg-zinc-900 px-3 py-2 text-xs font-medium text-zinc-200 hover:border-white/30"
                >
                  {copied ? (
                    <>
                      <Check className="h-3.5 w-3.5 text-emerald-400" />
                      Canal copié !
                    </>
                  ) : (
                    <>
                      <Copy className="h-3.5 w-3.5 text-zinc-400" />
                      Copier le canal ({primaryTopic.slice(0, 14)}…)
                    </>
                  )}
                </button>

                <button
                  type="button"
                  disabled={testing}
                  onClick={() => void handleTestPush()}
                  className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-500/35 bg-emerald-500/15 px-3 py-2 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/25 disabled:opacity-40"
                >
                  <Send className="h-3.5 w-3.5" />
                  {testSent ? 'Notification envoyée !' : testing ? 'Envoi…' : 'Tester sur mon téléphone'}
                </button>
              </div>

              <div className="flex flex-wrap items-center gap-3 pt-1 text-[11px] text-zinc-400">
                <a
                  href="https://play.google.com/store/apps/details?id=io.heckel.ntfy"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-sky-300 hover:underline"
                >
                  <span>ntfy sur Google Play</span>
                  <ExternalLink className="h-3 w-3" />
                </a>
                <span>·</span>
                <a
                  href="https://apps.apple.com/app/ntfy/id1625396347"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-sky-300 hover:underline"
                >
                  <span>ntfy sur App Store (iPhone)</span>
                  <ExternalLink className="h-3 w-3" />
                </a>
              </div>
            </div>

            {/* Method 2: Browser / PWA Notifications */}
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-white/[0.07] bg-zinc-900/70 p-3">
              <div>
                <div className="font-semibold text-white">
                  2. Notifications directes Navigateur / Web App
                </div>
                <div className="text-[11px] text-zinc-400">
                  {notifPerm === 'granted'
                    ? 'Activées sur cet appareil dès que l’application web est ouverte en arrière-plan.'
                    : 'Affiche une bannière système dès qu’une alerte ou demande arrive.'}
                </div>
              </div>
              {notifPerm !== 'granted' && (
                <button
                  type="button"
                  onClick={() => void handleRequestBrowserNotif()}
                  className="rounded-xl bg-white px-3 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
                >
                  Autoriser ici
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
