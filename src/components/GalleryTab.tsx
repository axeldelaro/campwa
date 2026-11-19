import React from 'react';
import { Download, Image as ImageIcon, RefreshCw, X } from 'lucide-react';
import type { GalleryAlbumItem, GalleryPhotoItem } from '../types/sentinel';

interface GalleryTabProps {
  items: GalleryPhotoItem[];
  albums: GalleryAlbumItem[];
  activeAlbum: string;
  totalCount: number;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  canViewGallery: boolean;
  lightboxPhoto: { item: GalleryPhotoItem; base64?: string; loading: boolean; error?: string } | null;
  onRefresh: (album?: string) => Promise<void>;
  onLoadMore: () => Promise<void>;
  onSelectPhoto: (item: GalleryPhotoItem) => Promise<void>;
  onCloseLightbox: () => void;
}

export const GalleryTab: React.FC<GalleryTabProps> = ({
  items,
  albums,
  activeAlbum,
  totalCount,
  hasMore,
  loading,
  error,
  canViewGallery,
  lightboxPhoto,
  onRefresh,
  onLoadMore,
  onSelectPhoto,
  onCloseLightbox,
}) => {
  return (
    <div className="rounded-2xl border border-white/[0.08] bg-zinc-900/70 p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <ImageIcon className="h-4 w-4 text-zinc-400" />
          <div>
            <h2 className="text-sm font-semibold text-white">Galerie photos</h2>
            <p className="text-[11px] text-zinc-400">
              {totalCount > 0
                ? `${totalCount} photos publiques (dossiers masqués exclus)`
                : 'Dossiers masqués et corbeille automatiquement exclus'}
            </p>
          </div>
        </div>

        <button
          type="button"
          disabled={loading}
          onClick={() => void onRefresh(activeAlbum)}
          className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-zinc-950 px-3 py-1.5 text-xs font-medium text-zinc-300 hover:text-white disabled:opacity-40"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Actualiser</span>
        </button>
      </div>

      {albums.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
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
        <div className="mt-4 rounded-xl border border-rose-500/35 bg-rose-500/10 p-3 text-xs text-rose-200">
          {error}
        </div>
      )}

      {!canViewGallery && !error && (
        <div className="mt-4 rounded-xl border border-white/[0.06] bg-zinc-950/70 p-4 text-xs text-zinc-400">
          Autorisez la Galerie sur le smartphone (ou activez-la dans l’onglet Règles).
        </div>
      )}

      {loading && items.length === 0 ? (
        <div className="flex items-center justify-center py-12 text-xs text-zinc-400">
          <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
          Chargement de la galerie…
        </div>
      ) : items.length === 0 && !error ? (
        <div className="py-10 text-center text-xs text-zinc-500">
          Cliquez sur « Actualiser » pour parcourir les photos publiques.
        </div>
      ) : (
        <>
          <div className="mt-4 grid grid-cols-3 gap-2 sm:grid-cols-4 md:grid-cols-6">
            {items.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => void onSelectPhoto(item)}
                className="group relative aspect-square overflow-hidden rounded-xl border border-white/[0.06] bg-zinc-950 text-left transition hover:border-white/25"
              >
                <img
                  src={`data:image/jpeg;base64,${item.thumbBase64}`}
                  alt={item.name}
                  loading="lazy"
                  className="h-full w-full object-cover transition duration-200 group-hover:scale-105"
                />
                <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 to-transparent px-1.5 py-1 text-[9px] text-zinc-300 opacity-0 transition group-hover:opacity-100">
                  {new Date(item.dateTaken).toLocaleDateString('fr-FR')}
                </div>
              </button>
            ))}
          </div>

          {hasMore && (
            <div className="mt-4 text-center">
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

      {/* Fullscreen Photo Lightbox */}
      {lightboxPhoto && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/85 p-4 backdrop-blur-sm"
          onClick={onCloseLightbox}
        >
          <div
            className="w-full max-w-2xl overflow-hidden rounded-2xl border border-white/10 bg-zinc-950 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-white/[0.07] px-4 py-3 text-xs">
              <div className="min-w-0 truncate">
                <span className="font-medium text-white">{lightboxPhoto.item.name}</span>
                <span className="ml-2 text-zinc-500">
                  {lightboxPhoto.item.album} ·{' '}
                  {new Date(lightboxPhoto.item.dateTaken).toLocaleString('fr-FR')}
                </span>
              </div>
              <div className="flex items-center gap-2">
                {lightboxPhoto.base64 && (
                  <a
                    href={`data:image/jpeg;base64,${lightboxPhoto.base64}`}
                    download={lightboxPhoto.item.name || `photo-${lightboxPhoto.item.id}.jpg`}
                    className="inline-flex items-center gap-1 rounded-lg bg-white px-2.5 py-1 text-[11px] font-semibold text-zinc-950 hover:bg-zinc-200"
                  >
                    <Download className="h-3 w-3" />
                    <span>Télécharger</span>
                  </a>
                )}
                <button
                  type="button"
                  onClick={onCloseLightbox}
                  className="rounded-lg p-1 text-zinc-400 hover:bg-white/10 hover:text-white"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </div>

            <div className="flex min-h-[260px] items-center justify-center bg-black p-2">
              {lightboxPhoto.loading ? (
                <div className="flex items-center gap-2 text-xs text-zinc-400">
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  <span>Chargement HD chiffré…</span>
                </div>
              ) : lightboxPhoto.error ? (
                <div className="text-xs text-rose-300">{lightboxPhoto.error}</div>
              ) : lightboxPhoto.base64 ? (
                <img
                  src={`data:image/jpeg;base64,${lightboxPhoto.base64}`}
                  alt={lightboxPhoto.item.name}
                  className="max-h-[72vh] w-auto object-contain"
                />
              ) : null}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
