import React from 'react';
import {
  AlertTriangle,
  BarChart3,
  Moon,
  RefreshCw,
  Sparkles,
  Users,
} from 'lucide-react';
import type { DataInsightsMsg } from '../types/sentinel';

interface InsightsCardProps {
  data: DataInsightsMsg | null;
  loading: boolean;
  onRefresh: () => Promise<void>;
}

function formatNightMinutes(mins: number): string {
  const safe = Math.max(0, Math.round(mins || 0));
  if (safe < 60) return `${safe} min`;
  const h = Math.floor(safe / 60);
  const m = safe % 60;
  return m > 0 ? `${h}h${m.toString().padStart(2, '0')}` : `${h}h`;
}

export const InsightsCard: React.FC<InsightsCardProps> = ({ data, loading, onRefresh }) => {
  // Defensive normalization so older APKs or partial payloads NEVER crash the UI
  const topContacts = Array.isArray(data?.topContacts) ? data.topContacts : [];
  const topics = Array.isArray(data?.topics) ? data.topics : [];
  const recurringWords = Array.isArray(data?.recurringWords) ? data.recurringWords : [];
  const nightUsage = data?.nightUsage;

  const getContactCount = (c: Record<string, unknown>): number => {
    const raw = (c.totalCount ?? c.totalInteractions ?? 0) as number;
    return Number.isFinite(raw) ? raw : 0;
  };

  const maxContactCount = Math.max(
    1,
    ...topContacts.map((c) => getContactCount(c as unknown as Record<string, unknown>))
  );
  const alertTopics = topics.filter((t) => Boolean(t?.alert));

  return (
    <div className="space-y-4 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2.5">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-indigo-400/25 bg-indigo-500/15 text-indigo-300">
            <Sparkles className="h-4 w-4" />
          </div>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="text-sm font-semibold text-white">
                Analyse des conversations & habitudes
              </h3>
              {alertTopics.length > 0 && (
                <span className="inline-flex items-center gap-1 rounded-full border border-rose-500/35 bg-rose-500/15 px-2 py-0.5 text-[10px] font-semibold text-rose-300">
                  <AlertTriangle className="h-3 w-3" />
                  Vigilance
                </span>
              )}
            </div>
            <p className="text-[11px] text-zinc-400">
              {data
                ? `${data.analyzedMessagesCount ?? 0} messages et ${data.analyzedCallsCount ?? 0} appels analysés localement`
                : 'Contacts les plus fréquents, sujets récurrents et activité nocturne'}
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={() => void onRefresh()}
          disabled={loading}
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-200 transition hover:border-white/20 disabled:opacity-50"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          Actualiser
        </button>
      </div>

      {data?.error && (
        <div className="rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
          {data.error}
        </div>
      )}

      {!data && !loading ? (
        <div className="rounded-xl border border-dashed border-white/10 bg-zinc-950/60 p-6 text-center">
          <p className="text-xs text-zinc-400">
            Appuyez sur « Actualiser » pour analyser les contacts les plus fréquents, les sujets récurrents et l’activité nocturne.
          </p>
        </div>
      ) : loading && !data ? (
        <div className="rounded-xl border border-white/[0.06] bg-zinc-950/60 p-6 text-center text-xs text-zinc-400">
          Analyse locale en cours sur le smartphone…
        </div>
      ) : (
        <div className="space-y-3.5">
          {/* 1. Top Contacts */}
          <div className="space-y-2.5 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3.5">
            <div className="flex items-center justify-between">
              <h4 className="flex items-center gap-1.5 text-xs font-semibold text-white">
                <Users className="h-3.5 w-3.5 text-sky-400" />
                Avec qui l’enfant parle le plus (30 jours)
              </h4>
              <span className="text-[10px] text-zinc-500">SMS + Appels + Réseaux</span>
            </div>

            {topContacts.length === 0 ? (
              <p className="py-2 text-[11px] text-zinc-500">
                Pas encore assez d’échanges enregistrés pour établir le classement.
              </p>
            ) : (
              <div className="space-y-2.5">
                {topContacts.slice(0, 8).map((c, idx) => {
                  const rec = c as unknown as Record<string, unknown>;
                  const count = getContactCount(rec);
                  const pct = Math.max(8, Math.round((count / maxContactCount) * 100));
                  const channels = Array.isArray(c?.channels) ? c.channels : [];
                  const callMin = typeof c?.callMinutes === 'number' ? c.callMinutes : 0;

                  return (
                    <div key={`${c?.name || 'c'}-${idx}`} className="space-y-1">
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <div className="flex min-w-0 items-center gap-1.5">
                          <span className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-white/[0.08] text-[10px] font-semibold text-zinc-300">
                            {idx + 1}
                          </span>
                          <span className="truncate font-medium text-white">
                            {c?.name || 'Inconnu'}
                          </span>
                          {c?.isNewIntensive && (
                            <span className="shrink-0 rounded-full border border-amber-400/35 bg-amber-500/15 px-1.5 py-0.5 text-[9px] font-semibold text-amber-200">
                              Nouveau & intensif
                            </span>
                          )}
                        </div>
                        <span className="shrink-0 text-[11px] font-medium text-zinc-300">
                          {count} échanges
                        </span>
                      </div>

                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/[0.06]">
                        <div
                          className={`h-full rounded-full ${
                            c?.isNewIntensive ? 'bg-amber-400' : 'bg-sky-400'
                          }`}
                          style={{ width: `${pct}%` }}
                        />
                      </div>

                      <div className="flex flex-wrap items-center gap-1.5 text-[10px] text-zinc-500">
                        {channels.map((ch) => (
                          <span
                            key={String(ch)}
                            className="rounded bg-white/[0.05] px-1.5 py-0.5 text-zinc-400"
                          >
                            {String(ch)}
                          </span>
                        ))}
                        {callMin > 0 && <span>• {callMin} min d’appel</span>}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* 2. Recurring Topics & Words */}
          <div className="space-y-2.5 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3.5">
            <h4 className="flex items-center gap-1.5 text-xs font-semibold text-white">
              <BarChart3 className="h-3.5 w-3.5 text-indigo-400" />
              Synthèse des sujets récurrents
            </h4>

            {topics.length === 0 ? (
              <p className="py-1 text-[11px] text-zinc-500">
                Aucun thème dominant détecté pour l’instant.
              </p>
            ) : (
              <div className="space-y-2.5">
                {topics.map((t, idx) => {
                  const rec = t as unknown as Record<string, unknown>;
                  const sampleList = Array.isArray(rec.sampleWords)
                    ? (rec.sampleWords as string[])
                    : Array.isArray(rec.samples)
                      ? (rec.samples as string[])
                      : [];
                  const pct = Number.isFinite(t?.percent) ? t.percent : 0;

                  return (
                    <div key={t?.id || `topic-${idx}`} className="space-y-1">
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <span className={`font-medium ${t?.alert ? 'text-rose-300' : 'text-zinc-200'}`}>
                          {t?.alert ? '⚠ ' : ''}
                          {t?.label || 'Thème'}
                        </span>
                        <span className="text-[11px] text-zinc-400">
                          {pct}% ({t?.count ?? 0})
                        </span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/[0.06]">
                        <div
                          className={`h-full rounded-full ${t?.alert ? 'bg-rose-500' : 'bg-indigo-400'}`}
                          style={{ width: `${Math.max(6, Math.min(100, pct))}%` }}
                        />
                      </div>
                      {sampleList.length > 0 && (
                        <p className="text-[10px] text-zinc-500">
                          Termes relevés : {sampleList.join(', ')}
                        </p>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {recurringWords.length > 0 && (
              <div className="border-t border-white/[0.06] pt-2.5">
                <p className="mb-1.5 text-[10px] font-medium uppercase tracking-wider text-zinc-500">
                  Mots les plus utilisés
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {recurringWords.slice(0, 14).map((w, idx) => (
                    <span
                      key={`${w?.word || 'w'}-${idx}`}
                      className="rounded-full border border-white/10 bg-zinc-900 px-2.5 py-0.5 text-[11px] text-zinc-300"
                    >
                      {w?.word}{' '}
                      <span className="text-[10px] text-zinc-500">×{w?.count ?? 0}</span>
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* 3. Nighttime Usage (22h-06h) */}
          {nightUsage && (
            <div className="space-y-2 rounded-xl border border-white/[0.06] bg-zinc-950/75 p-3.5">
              <div className="flex items-center justify-between gap-2">
                <h4 className="flex items-center gap-1.5 text-xs font-semibold text-white">
                  <Moon className="h-3.5 w-3.5 text-violet-400" />
                  Activité nocturne (22h – 06h, 7 jours)
                </h4>
                <span
                  className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                    (nightUsage.totalNightMinutes7d ?? 0) >= 45
                      ? 'bg-amber-500/15 text-amber-300'
                      : 'bg-emerald-500/15 text-emerald-300'
                  }`}
                >
                  {(nightUsage.totalNightMinutes7d ?? 0) > 0
                    ? formatNightMinutes(nightUsage.totalNightMinutes7d)
                    : 'Calme'}
                </span>
              </div>

              {!nightUsage.available ? (
                <p className="text-[11px] text-zinc-500">
                  Autorisez « Temps d’écran » sur le smartphone pour mesurer l’activité nocturne.
                </p>
              ) : (nightUsage.totalNightMinutes7d ?? 0) === 0 ? (
                <p className="text-[11px] text-zinc-400">
                  Aucune utilisation notable détectée entre 22h et 06h cette semaine.
                </p>
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {(Array.isArray(nightUsage.topNightApps) ? nightUsage.topNightApps : []).map((a) => (
                    <span
                      key={a.packageName}
                      className="rounded-lg border border-violet-500/20 bg-violet-500/10 px-2 py-1 text-[11px] text-violet-200"
                    >
                      {a.appName} • {formatNightMinutes(a.minutes)}
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
