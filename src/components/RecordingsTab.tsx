import React, { useState } from 'react';
import {
  Circle,
  Download,
  Lock,
  Mic,
  Play,
  RefreshCw,
  Square,
  Trash2,
  Video,
  X,
} from 'lucide-react';
import type {
  CameraFacingMode,
  DataRecordingsMsg,
  OfflineRecordingItem,
} from '../types/sentinel';

interface RecordingsTabProps {
  data: DataRecordingsMsg | null;
  loading: boolean;
  downloadProgress: { recordingId: string; received: number; total: number } | null;
  activeMedia: {
    item: OfflineRecordingItem;
    mimeType: string;
    base64?: string;
    loading: boolean;
    error?: string;
  } | null;
  onRefresh: () => Promise<void>;
  onStart: (mode: 'audio' | 'video', durationMinutes: number, facing: CameraFacingMode) => Promise<void>;
  onStop: () => Promise<void>;
  onOpenFile: (item: OfflineRecordingItem) => Promise<void>;
  onDelete: (item: OfflineRecordingItem) => Promise<void>;
  onClosePlayer: () => void;
}

function formatDuration(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m}m ${s.toString().padStart(2, '0')}s` : `${s} s`;
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} Mo`;
  return `${Math.max(1, Math.round(bytes / 1024))} Ko`;
}

export const RecordingsTab: React.FC<RecordingsTabProps> = ({
  data,
  loading,
  downloadProgress,
  activeMedia,
  onRefresh,
  onStart,
  onStop,
  onOpenFile,
  onDelete,
  onClosePlayer,
}) => {
  const [mode, setMode] = useState<'audio' | 'video'>('audio');
  const [durationMin, setDurationMin] = useState<number>(5);
  const [facing, setFacing] = useState<CameraFacingMode>('back');
  const [busy, setBusy] = useState(false);

  const items = data?.items ?? [];
  const isRec = Boolean(data?.isRecording);

  const handleStart = async () => {
    setBusy(true);
    try {
      await onStart(mode, durationMin, facing);
    } finally {
      window.setTimeout(() => setBusy(false), 700);
    }
  };

  const handleStop = async () => {
    setBusy(true);
    try {
      await onStop();
    } finally {
      window.setTimeout(() => setBusy(false), 700);
    }
  };

  return (
    <div className="space-y-3.5 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold text-white">
            <span>Enregistrement différé</span>
            {isRec && (
              <span className="inline-flex items-center gap-1 rounded-full bg-rose-500/20 px-2 py-0.5 text-[10px] font-semibold text-rose-200 animate-pulse">
                <Circle className="h-2 w-2 fill-rose-500 text-rose-500" />
                En cours ({data?.activeMode === 'video' ? 'Vidéo' : 'Audio'})
              </span>
            )}
          </div>
          <p className="text-[11px] text-zinc-500">
            Continue même hors ligne et stocke dans un coffre privé invisible par l’enfant
          </p>
        </div>

        <button
          type="button"
          disabled={loading}
          onClick={() => void onRefresh()}
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-300 hover:text-white disabled:opacity-40"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Actualiser</span>
        </button>
      </div>

      {/* Start / Stop Controls */}
      <div className="space-y-3 rounded-xl border border-white/[0.07] bg-zinc-950/75 p-3.5">
        {isRec ? (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-xs text-rose-200">
              Enregistrement {data?.activeMode === 'video' ? 'vidéo' : 'audio ambiant'} en cours sur le
              téléphone (arrêt auto prévu à{' '}
              {data?.activeTargetEndAt
                ? new Date(data.activeTargetEndAt).toLocaleTimeString('fr-FR', {
                    hour: '2-digit',
                    minute: '2-digit',
                  })
                : '…'}
              ).
            </div>
            <button
              type="button"
              disabled={busy}
              onClick={() => void handleStop()}
              className="inline-flex items-center gap-1.5 rounded-xl bg-rose-500 px-4 py-2 text-xs font-semibold text-white hover:bg-rose-400 disabled:opacity-45"
            >
              <Square className="h-3.5 w-3.5 fill-current" />
              <span>Arrêter et sauvegarder</span>
            </button>
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-1 rounded-xl border border-white/[0.07] bg-zinc-900 p-1">
                <button
                  type="button"
                  onClick={() => {
                    setMode('audio');
                    if (durationMin > 30) setDurationMin(5);
                  }}
                  className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium ${
                    mode === 'audio' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  <Mic className="h-3.5 w-3.5" />
                  <span>Audio ambiant</span>
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setMode('video');
                    if (durationMin > 10) setDurationMin(2);
                  }}
                  className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium ${
                    mode === 'video' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
                  }`}
                >
                  <Video className="h-3.5 w-3.5" />
                  <span>Vidéo caméra</span>
                </button>
              </div>

              {mode === 'video' && (
                <div className="flex items-center gap-1 text-xs">
                  <button
                    type="button"
                    onClick={() => setFacing('back')}
                    className={`rounded-lg border px-2.5 py-1 ${
                      facing === 'back'
                        ? 'border-emerald-400 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/10 bg-zinc-900 text-zinc-400'
                    }`}
                  >
                    Arrière
                  </button>
                  <button
                    type="button"
                    onClick={() => setFacing('front')}
                    className={`rounded-lg border px-2.5 py-1 ${
                      facing === 'front'
                        ? 'border-emerald-400 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/10 bg-zinc-900 text-zinc-400'
                    }`}
                  >
                    Avant
                  </button>
                </div>
              )}
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-1.5 text-xs">
                <span className="text-zinc-400">Durée max :</span>
                {(mode === 'audio' ? [1, 5, 15, 30] : [1, 2, 5, 10]).map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setDurationMin(m)}
                    className={`rounded-lg border px-2.5 py-1 font-mono text-[11px] ${
                      durationMin === m
                        ? 'border-emerald-400 bg-emerald-500/15 text-emerald-200'
                        : 'border-white/10 bg-zinc-900 text-zinc-400 hover:text-white'
                    }`}
                  >
                    {m} min
                  </button>
                ))}
              </div>

              <button
                type="button"
                disabled={busy}
                onClick={() => void handleStart()}
                className="inline-flex items-center gap-1.5 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-45"
              >
                <Circle className="h-3 w-3 fill-rose-500 text-rose-500" />
                <span>Lancer l’enregistrement</span>
              </button>
            </div>
          </>
        )}
      </div>

      {/* Vault List */}
      <div className="flex items-center gap-2 text-[11px] text-zinc-500">
        <Lock className="h-3.5 w-3.5 text-emerald-400" />
        <span>Coffre chiffré sur le téléphone ({items.length})</span>
      </div>

      {items.length === 0 ? (
        <div className="py-8 text-center text-xs text-zinc-500">
          Aucun enregistrement sauvegardé.
        </div>
      ) : (
        <div className="divide-y divide-white/[0.05] rounded-xl border border-white/[0.06] bg-zinc-950/60">
          {items.map((item) => (
            <div key={item.id} className="flex items-center justify-between gap-2 px-3.5 py-2.5 text-xs">
              <div className="flex min-w-0 items-center gap-2.5">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/[0.06] text-emerald-300">
                  {item.mode === 'video' ? (
                    <Video className="h-4 w-4" />
                  ) : (
                    <Mic className="h-4 w-4" />
                  )}
                </div>
                <div className="min-w-0">
                  <div className="font-medium text-white">
                    {item.mode === 'video' ? 'Vidéo' : 'Audio'} · {formatDuration(item.durationSec)}
                  </div>
                  <div className="text-[11px] text-zinc-500">
                    {new Date(item.startedAt).toLocaleString('fr-FR', {
                      day: '2-digit',
                      month: '2-digit',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}{' '}
                    · {formatSize(item.sizeBytes)}
                  </div>
                </div>
              </div>

              <div className="flex shrink-0 items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => void onOpenFile(item)}
                  className="inline-flex items-center gap-1 rounded-lg bg-white px-2.5 py-1.5 text-[11px] font-semibold text-zinc-950 hover:bg-zinc-200"
                >
                  <Play className="h-3 w-3 fill-current" />
                  <span>Lire</span>
                </button>
                <button
                  type="button"
                  onClick={() => void onDelete(item)}
                  className="rounded-lg border border-white/10 bg-zinc-900 p-1.5 text-zinc-400 hover:text-rose-300"
                  title="Supprimer"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Playback Modal */}
      {activeMedia && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-sm"
          onClick={onClosePlayer}
        >
          <div
            className="w-full max-w-lg overflow-hidden rounded-2xl border border-white/10 bg-zinc-950 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-white/[0.07] px-4 py-3 text-xs">
              <span className="font-semibold text-white">
                {activeMedia.item.mode === 'video' ? 'Enregistrement vidéo' : 'Enregistrement audio'} ·{' '}
                {formatDuration(activeMedia.item.durationSec)}
              </span>
              <button
                type="button"
                onClick={onClosePlayer}
                className="rounded-lg p-1 text-zinc-400 hover:bg-white/10 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="p-5">
              {activeMedia.loading ? (
                <div className="space-y-2 py-6 text-center text-xs text-zinc-300">
                  <RefreshCw className="mx-auto h-5 w-5 animate-spin text-emerald-400" />
                  <div>
                    Téléchargement depuis le téléphone…
                    {downloadProgress && downloadProgress.recordingId === activeMedia.item.id
                      ? ` ${Math.round((downloadProgress.received / Math.max(1, downloadProgress.total)) * 100)}%`
                      : ''}
                  </div>
                </div>
              ) : activeMedia.error ? (
                <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200">
                  {activeMedia.error}
                </div>
              ) : activeMedia.base64 ? (
                <div className="space-y-3">
                  {activeMedia.item.mode === 'video' ? (
                    <video
                      controls
                      autoPlay
                      playsInline
                      src={`data:${activeMedia.mimeType};base64,${activeMedia.base64}`}
                      className="max-h-[55vh] w-full rounded-xl bg-black"
                    />
                  ) : (
                    <audio
                      controls
                      autoPlay
                      src={`data:${activeMedia.mimeType};base64,${activeMedia.base64}`}
                      className="w-full"
                    />
                  )}
                  <div className="flex justify-end">
                    <a
                      href={`data:${activeMedia.mimeType};base64,${activeMedia.base64}`}
                      download={`${activeMedia.item.id}.${activeMedia.item.mode === 'video' ? 'mp4' : 'm4a'}`}
                      className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3.5 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200"
                    >
                      <Download className="h-3.5 w-3.5" />
                      <span>Télécharger</span>
                    </a>
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
