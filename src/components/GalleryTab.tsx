import React, { useState } from 'react';
import {
  Download,
  Image as ImageIcon,
  Lock,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Trash2,
  X,
} from 'lucide-react';
import type { GalleryAlbumItem, GalleryPhotoItem } from '../types/sentinel';

interface GalleryTabProps {
  items: GalleryPhotoItem[];
  quarantineItems: GalleryPhotoItem[];
  albums: GalleryAlbumItem[];
  activeAlbum: string;
  totalCount: number;
  quarantineCount: number;
  hasMore: boolean;
  loading: boolean;
  quarantineLoading: boolean;
  error: string | null;
  actionFeedback: { ok: boolean; text: string } | null;
  actionBusy: boolean;
  canViewGallery: boolean;
  canManageFiles: boolean;
  lightboxPhoto: { item: GalleryPhotoItem; base64?: string; loading: boolean; error?: string } | null;
  onRefresh: (album?: string) => Promise<void>;
  onLoadMore: () => Promise<void>;
  onRefreshQuarantine: () => Promise<void>;
  onSelectPhoto: (item: GalleryPhotoItem) => Promise<void>;
  onQuarantinePhoto: (item: GalleryPhotoItem) => Promise<void>;
  onDeletePhoto: (item: GalleryPhotoItem) => Promise<void>;
  onRestoreQuarantine: (item: GalleryPhotoItem) => Promise<void>;
  onDeleteQuarantine: (item: GalleryPhotoItem) => Promise<void>;
  onCloseLightbox: () => void;
  onDismissFeedback: () => void;
}

export const GalleryTab: React.FC<GalleryTabProps> = ({
  items,
  quarantineItems,
  albums,
  activeAlbum,
  totalCount,
  quarantineCount,
  hasMore,
  loading,
  quarantineLoading,
  error,
  actionFeedback,
  actionBusy,
  canViewGallery,
  canManageFiles,
  lightboxPhoto,
  onRefresh,
  onLoadMore,
  onRefreshQuarantine,
  onSelectPhoto,
  onQuarantinePhoto,
  onDeletePhoto,
  onRestoreQuarantine,
  onDeleteQuarantine,
  onCloseLightbox,
  onDismissFeedback,
}) => {
  const [viewMode, setViewMode] = useState<'public' | 'quarantine'>('public');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const handleSwitchMode = (mode: 'public' | 'quarantine') => {
    setViewMode(mode);
    setConfirmDeleteId(null);
    if (mode === 'quarantine') {
      void onRefreshQuarantine();
    } else if (items.length === 0) {
      void onRefresh(activeAlbum);
    }
  };

  const shownItems = viewMode === 'public' ? items : quarantineItems;
  const isBusy = viewMode === 'public' ? loading : quarantineLoading;

  return (
    <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1 rounded-xl border border-white/[0.07] bg-zinc-950 p-1">
          <button
            type="button"
            onClick={() => handleSwitchMode('public')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition ${
              viewMode === 'public' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
            }`}
          >
            <ImageIcon className="h-3.5 w-3.5" />
            <span>Galerie</span>
            {totalCount > 0 && <span className="opacity-65">({totalCount})</span>}
          </button>
          <button
            type="button"
            onClick={() => handleSwitchMode('quarantine')}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition ${
              viewMode === 'quarantine'
                ? 'bg-white text-zinc-950'
                : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Lock className="h-3.5 w-3.5" />
            <span>Quarantaine</span>
            {quarantineCount > 0 && <span className="opacity-65">({quarantineCount})</span>}
          </button>
        </div>

        <button
          type="button"
          disabled={isBusy}
          onClick={() =>
            viewMode === 'public' ? void onRefresh(activeAlbum) : void onRefreshQuarantine()
          }
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-300 hover:text-white disabled:opacity-40"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${isBusy ? 'animate-spin' : ''}`} />
          <span>Actualiser</span>
        </button>
      </div>

      {viewMode === 'quarantine' && (
        <div className="flex items-center gap-2 rounded-xl border border-white/[0.06] bg-zinc-950/75 px-3 py-2 text-[11px] text-zinc-400">
          <Lock className="h-3.5 w-3.5 shrink-0 text-emerald-400" />
          <span>
            Dossier verrouillé inaccessible depuis la galerie et les fichiers du téléphone.
          </span>
        </div>
      )}

      {canViewGallery && !canManageFiles && (
        <div className="flex items-center gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-200">
          <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-amber-400" />
          <span>
            Pour mettre en quarantaine ou supprimer des photos à distance, autorisez « Gestion photos »
            une fois dans l’appli SentinelCam sur le téléphone.
          </span>
        </div>
      )}

      {actionFeedback && (
        <div
          className={`flex items-center justify-between gap-2 rounded-xl border px-3.5 py-2 text-xs ${
            actionFeedback.ok
              ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
              : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
          }`}
        >
          <span>{actionFeedback.text}</span>
          <button type="button" onClick={onDismissFeedback} className="text-zinc-400 hover:text-white">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {viewMode === 'public' && albums.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          <button
            type="button"
            onClick={() => void onRefresh('')}
            className={`rounded-lg px-2.5 py-1 text-[11px] font-medium transition ${
              activeAlbum === ''
                ? 'bg-white text-zinc-950'
                : 'border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
            }`}
          >
            Toutes
          </button>
          {albums.map((alb) => (
            <button
              key={alb.name}
              type="button"
              onClick={() => void onRefresh(alb.name)}
              className={`rounded-lg px-2.5 py-1 text-[11px] font-medium transition ${
                activeAlbum === alb.name
                  ? 'bg-white text-zinc-950'
                  : 'border border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
              }`}
            >
              {alb.name} <span className="opacity-60">({alb.count})</span>
            </button>
          ))}
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-xs text-rose-200">
          {error}
        </div>
      )}

      {!canViewGallery && !error && (
        <div className="rounded-xl border border-white/[0.06] bg-zinc-950/70 p-4 text-xs text-zinc-400">
          Activez « Galerie photos » dans l’onglet Règles → Autorisations à distance.
        </div>
      )}

      {isBusy && shownItems.length === 0 ? (
        <div className="flex items-center justify-center py-12 text-xs text-zinc-400">
          <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
          Chargement…
        </div>
      ) : shownItems.length === 0 && !error ? (
        <div className="py-10 text-center text-xs text-zinc-500">
          {viewMode === 'quarantine'
            ? 'Aucune photo en quarantaine.'
            : 'Aucune photo trouvée.'}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-6">
            {shownItems.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => {
                  setConfirmDeleteId(null);
                  void onSelectPhoto(item);
                }}
                className="group relative aspect-square overflow-hidden rounded-xl border border-white/[0.06] bg-zinc-950 text-left transition hover:border-white/25"
              >
                <img
                  src={`data:image/jpeg;base64,${item.thumbBase64}`}
                  alt={item.name}
                  loading="lazy"
                  className="h-full w-full object-cover transition duration-200 group-hover:scale-105"
                />
                {item.quarantined && (
                  <span className="absolute right-1.5 top-1.5 rounded-md bg-black/75 p-1 text-amber-300">
                    <Lock className="h-3 w-3" />
                  </span>
                )}
              </button>
            ))}
          </div>

          {viewMode === 'public' && hasMore && (
            <div className="pt-1 text-center">
              <button
                type="button"
                disabled={loading}
                onClick={() => void onLoadMore()}
                className="rounded-xl border border-white/10 bg-zinc-950 px-4 py-2 text-xs font-medium text-zinc-200 hover:border-white/25 disabled:opacity-40"
              >
                {loading ? 'Chargement…' : 'Charger plus'}
              </button>
            </div>
          )}
        </>
      )}

      {/* Fullscreen Photo Viewer with Quarantine / Restore / Delete */}
      {lightboxPhoto && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-3 backdrop-blur-sm sm:p-4"
          onClick={onCloseLightbox}
        >
          <div
            className="w-full max-w-2xl overflow-hidden rounded-2xl border border-white/10 bg-zinc-950 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between gap-2 border-b border-white/[0.07] px-4 py-3 text-xs">
              <div className="min-w-0 truncate">
                <span className="font-medium text-white">{lightboxPhoto.item.name}</span>
                <span className="ml-2 text-zinc-500">
                  {new Date(lightboxPhoto.item.dateTaken).toLocaleDateString('fr-FR')}
                </span>
              </div>
              <button
                type="button"
                onClick={onCloseLightbox}
                className="rounded-lg p-1 text-zinc-400 hover:bg-white/10 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex min-h-[240px] items-center justify-center bg-black p-2">
              {lightboxPhoto.loading ? (
                <div className="flex items-center gap-2 text-xs text-zinc-400">
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  <span>Chargement…</span>
                </div>
              ) : lightboxPhoto.error ? (
                <div className="text-xs text-rose-300">{lightboxPhoto.error}</div>
              ) : lightboxPhoto.base64 ? (
                <img
                  src={`data:image/jpeg;base64,${lightboxPhoto.base64}`}
                  alt={lightboxPhoto.item.name}
                  className="max-h-[62vh] w-auto object-contain"
                />
              ) : null}
            </div>

            {actionFeedback && (
              <div
                className={`mx-4 mt-3 flex items-center justify-between gap-2 rounded-xl border px-3 py-2 text-xs ${
                  actionFeedback.ok
                    ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
                    : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
                }`}
              >
                <span>{actionFeedback.text}</span>
                <button type="button" onClick={onDismissFeedback} className="text-zinc-400 hover:text-white">
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            )}

            {/* Parent Moderation Bar: Quarantine / Restore / Delete / Download */}
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-white/[0.07] px-4 py-3 text-xs">
              <div className="flex flex-wrap items-center gap-2">
                {lightboxPhoto.item.quarantined ? (
                  <button
                    type="button"
                    disabled={actionBusy}
                    onClick={() => void onRestoreQuarantine(lightboxPhoto.item)}
                    className="inline-flex items-center gap-1.5 rounded-xl border border-emerald-500/40 bg-emerald-500/15 px-3 py-2 font-medium text-emerald-200 hover:bg-emerald-500/25 disabled:opacity-45"
                  >
                    {actionBusy ? (
                      <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <RotateCcw className="h-3.5 w-3.5" />
                    )}
                    <span>{actionBusy ? 'Traitement…' : 'Restaurer'}</span>
                  </button>
                ) : (
                  <button
                    type="button"
                    disabled={actionBusy}
                    onClick={() => void onQuarantinePhoto(lightboxPhoto.item)}
                    className="inline-flex items-center gap-1.5 rounded-xl border border-amber-500/40 bg-amber-500/15 px-3 py-2 font-medium text-amber-200 hover:bg-amber-500/25 disabled:opacity-45"
                  >
                    {actionBusy ? (
                      <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <ShieldAlert className="h-3.5 w-3.5" />
                    )}
                    <span>{actionBusy ? 'Traitement…' : 'Quarantaine'}</span>
                  </button>
                )}

                {confirmDeleteId === lightboxPhoto.item.id ? (
                  <button
                    type="button"
                    disabled={actionBusy}
                    onClick={() => {
                      setConfirmDeleteId(null);
                      if (lightboxPhoto.item.quarantined) {
                        void onDeleteQuarantine(lightboxPhoto.item);
                      } else {
                        void onDeletePhoto(lightboxPhoto.item);
                      }
                    }}
                    className="inline-flex items-center gap-1.5 rounded-xl bg-rose-500 px-3 py-2 font-semibold text-white hover:bg-rose-400 disabled:opacity-45"
                  >
                    {actionBusy ? (
                      <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Trash2 className="h-3.5 w-3.5" />
                    )}
                    <span>{actionBusy ? 'Suppression…' : 'Confirmer suppression'}</span>
                  </button>
                ) : (
                  <button
                    type="button"
                    disabled={actionBusy}
                    onClick={() => setConfirmDeleteId(lightboxPhoto.item.id)}
                    className="inline-flex items-center gap-1.5 rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 font-medium text-rose-200 hover:bg-rose-500/20 disabled:opacity-45"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    <span>Supprimer</span>
                  </button>
                )}
              </div>

              {lightboxPhoto.base64 && (
                <a
                  href={`data:image/jpeg;base64,${lightboxPhoto.base64}`}
                  download={lightboxPhoto.item.name || `photo-${lightboxPhoto.item.id}.jpg`}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3 py-2 font-semibold text-zinc-950 hover:bg-zinc-200"
                >
                  <Download className="h-3.5 w-3.5" />
                  <span>Enregistrer</span>
                </a>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
