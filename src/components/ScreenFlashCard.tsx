import React, { useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  Image as ImageIcon,
  Monitor,
  Send,
  Type,
  Upload,
} from 'lucide-react';

interface ScreenFlashCardProps {
  onFlashText: (text: string, durationSec: number) => Promise<void>;
  onFlashImage: (base64Jpeg: string, durationSec: number, caption: string) => Promise<void>;
}

async function compressFileToBase64Jpeg(file: File, maxDim = 1080, quality = 0.78): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Lecture du fichier impossible.'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Image invalide.'));
      img.onload = () => {
        const scale = Math.min(1, maxDim / Math.max(img.width, img.height, 1));
        const w = Math.max(1, Math.round(img.width * scale));
        const h = Math.max(1, Math.round(img.height * scale));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          reject(new Error('Traitement image impossible.'));
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        resolve(canvas.toDataURL('image/jpeg', quality));
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  });
}

export const ScreenFlashCard: React.FC<ScreenFlashCardProps> = ({
  onFlashText,
  onFlashImage,
}) => {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'text' | 'image'>('text');
  const [text, setText] = useState('');
  const [durationSec, setDurationSec] = useState(3);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const [caption, setCaption] = useState('');
  const [sending, setSending] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handlePickImage = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const dataUrl = await compressFileToBase64Jpeg(file);
      setImagePreview(dataUrl);
      if (durationSec < 5) setDurationSec(5);
    } catch {
      // Ignore invalid file
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleSend = async (e: React.FormEvent) => {
    e.preventDefault();
    setSending(true);
    try {
      const sec = Math.max(1, Math.min(60, durationSec));
      if (mode === 'text') {
        if (!text.trim()) return;
        await onFlashText(text.trim(), sec);
        setText('');
      } else {
        if (!imagePreview) return;
        await onFlashImage(imagePreview, sec, caption.trim());
        setCaption('');
      }
    } finally {
      window.setTimeout(() => setSending(false), 500);
    }
  };

  return (
    <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-zinc-900/65">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3.5 text-left transition hover:bg-white/[0.02]"
      >
        <div className="flex items-center gap-2.5">
          <Monitor className="h-4 w-4 text-emerald-400" />
          <div>
            <div className="text-sm font-medium text-white">Afficher sur l’écran</div>
            <div className="text-[11px] text-zinc-500">
              Message sur fond noir (3 s) ou image temporaire
            </div>
          </div>
        </div>
        {open ? (
          <ChevronUp className="h-4 w-4 text-zinc-500" />
        ) : (
          <ChevronDown className="h-4 w-4 text-zinc-500" />
        )}
      </button>

      {open && (
        <form onSubmit={(e) => void handleSend(e)} className="space-y-3 border-t border-white/[0.06] p-4">
          <div className="grid grid-cols-2 gap-1 rounded-xl border border-white/[0.07] bg-zinc-950 p-1">
            <button
              type="button"
              onClick={() => {
                setMode('text');
                setDurationSec(3);
              }}
              className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs font-medium transition ${
                mode === 'text' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
              }`}
            >
              <Type className="h-3.5 w-3.5" />
              <span>Message (fond noir)</span>
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('image');
                if (durationSec === 3) setDurationSec(5);
              }}
              className={`inline-flex items-center justify-center gap-1.5 rounded-lg py-1.5 text-xs font-medium transition ${
                mode === 'image' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
              }`}
            >
              <ImageIcon className="h-3.5 w-3.5" />
              <span>Image plein écran</span>
            </button>
          </div>

          {mode === 'text' ? (
            <textarea
              rows={2}
              maxLength={280}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Ex : À table dans 5 minutes !"
              className="w-full rounded-xl border border-white/10 bg-zinc-950 p-3 text-xs text-white placeholder-zinc-500 focus:border-emerald-400 focus:outline-none"
            />
          ) : (
            <div className="space-y-2.5">
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                onChange={(e) => void handlePickImage(e)}
                className="hidden"
              />
              {imagePreview ? (
                <div className="relative overflow-hidden rounded-xl border border-white/10 bg-black">
                  <img
                    src={imagePreview}
                    alt="Aperçu"
                    className="mx-auto max-h-40 w-auto object-contain"
                  />
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="absolute bottom-2 right-2 rounded-lg bg-zinc-900/90 px-2.5 py-1 text-[11px] font-medium text-white ring-1 ring-white/15"
                  >
                    Changer
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-white/15 bg-zinc-950 py-5 text-xs text-zinc-300 hover:border-white/30"
                >
                  <Upload className="h-4 w-4 text-emerald-400" />
                  <span>Choisir une image</span>
                </button>
              )}
              <input
                type="text"
                maxLength={160}
                value={caption}
                onChange={(e) => setCaption(e.target.value)}
                placeholder="Légende (optionnel)"
                className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500"
              />
            </div>
          )}

          {/* Duration selector (strictly capped 1..60s) */}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-1.5 text-xs">
              <span className="text-zinc-400">Durée :</span>
              {[3, 5, 10, 15].map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => setDurationSec(s)}
                  className={`rounded-lg border px-2.5 py-1 font-mono text-[11px] ${
                    durationSec === s
                      ? 'border-emerald-400 bg-emerald-500/20 text-emerald-200'
                      : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                  }`}
                >
                  {s} s
                </button>
              ))}
              <input
                type="number"
                min={1}
                max={60}
                value={durationSec}
                onChange={(e) =>
                  setDurationSec(Math.max(1, Math.min(60, parseInt(e.target.value || '3', 10))))
                }
                className="w-12 rounded-lg border border-white/10 bg-zinc-950 px-1.5 py-1 text-center font-mono text-[11px] text-white"
              />
              <span className="text-[11px] text-zinc-500">s</span>
            </div>

            <button
              type="submit"
              disabled={sending || (mode === 'text' ? !text.trim() : !imagePreview)}
              className="inline-flex items-center gap-1.5 rounded-xl bg-white px-4 py-2 text-xs font-semibold text-zinc-950 hover:bg-zinc-200 disabled:opacity-35"
            >
              <Send className="h-3.5 w-3.5" />
              <span>{sending ? 'Envoi…' : 'Afficher'}</span>
            </button>
          </div>
        </form>
      )}
    </div>
  );
};
