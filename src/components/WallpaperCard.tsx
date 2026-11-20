import React, { useRef, useState } from 'react';
import { Check, Image as ImageIcon, Send, Smartphone, Type, Upload } from 'lucide-react';

interface WallpaperCardProps {
  onApplyWallpaper: (base64Jpeg: string, target: 'home' | 'lock' | 'both') => Promise<void>;
}

const PRESET_THEMES = [
  { id: 'night', label: 'Nuit', bg1: '#0f172a', bg2: '#1e1b4b', accent: '#38bdf8' },
  { id: 'emerald', label: 'Forêt', bg1: '#052e16', bg2: '#064e3b', accent: '#34d399' },
  { id: 'amber', label: 'Alerte', bg1: '#451a03', bg2: '#78350f', accent: '#fbbf24' },
  { id: 'rose', label: 'Urgent', bg1: '#4c0519', bg2: '#881337', accent: '#fb7185' },
];

function compressImageToWallpaperJpeg(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Lecture image impossible.'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Format d’image non reconnu.'));
      img.onload = () => {
        const maxW = 900;
        const maxH = 1600;
        const scale = Math.min(maxW / img.width, maxH / img.height, 1);
        const w = Math.max(1, Math.round(img.width * scale));
        const h = Math.max(1, Math.round(img.height * scale));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          reject(new Error('Canvas indisponible.'));
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        resolve(canvas.toDataURL('image/jpeg', 0.8));
      };
      img.src = String(reader.result || '');
    };
    reader.readAsDataURL(file);
  });
}

function renderMessageWallpaperJpeg(
  headline: string,
  subtitle: string,
  theme: (typeof PRESET_THEMES)[number]
): string {
  const w = 720;
  const h = 1440;
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d')!;

  const grad = ctx.createLinearGradient(0, 0, w, h);
  grad.addColorStop(0, theme.bg1);
  grad.addColorStop(1, theme.bg2);
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, w, h);

  // Subtle decorative glow
  const rad = ctx.createRadialGradient(w * 0.5, h * 0.44, 20, w * 0.5, h * 0.44, 340);
  rad.addColorStop(0, `${theme.accent}33`);
  rad.addColorStop(1, 'transparent');
  ctx.fillStyle = rad;
  ctx.fillRect(0, 0, w, h);

  // Word-wrap headline cleanly
  ctx.fillStyle = '#ffffff';
  ctx.font = 'bold 54px system-ui, -apple-system, sans-serif';
  ctx.textAlign = 'center';
  const words = headline.trim().split(/\s+/);
  const lines: string[] = [];
  let line = '';
  for (const word of words) {
    const test = line ? `${line} ${word}` : word;
    if (ctx.measureText(test).width > w - 110 && line) {
      lines.push(line);
      line = word;
    } else {
      line = test;
    }
  }
  if (line) lines.push(line);

  const startY = h * 0.46 - ((lines.length - 1) * 68) / 2;
  lines.slice(0, 6).forEach((ln, idx) => {
    ctx.fillText(ln, w / 2, startY + idx * 68);
  });

  if (subtitle.trim()) {
    ctx.fillStyle = theme.accent;
    ctx.font = '500 30px system-ui, -apple-system, sans-serif';
    ctx.fillText(subtitle.trim().slice(0, 48), w / 2, startY + lines.slice(0, 6).length * 68 + 26);
  }

  return canvas.toDataURL('image/jpeg', 0.84);
}

export const WallpaperCard: React.FC<WallpaperCardProps> = ({ onApplyWallpaper }) => {
  const [mode, setMode] = useState<'photo' | 'message'>('photo');
  const [target, setTarget] = useState<'both' | 'home' | 'lock'>('both');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [headline, setHeadline] = useState('Pense à appeler Maman / Papa');
  const [subtitle, setSubtitle] = useState('Bonne journée !');
  const [themeId, setThemeId] = useState(PRESET_THEMES[0].id);
  const [sending, setSending] = useState(false);
  const [status, setStatus] = useState<{ ok: boolean; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const activeTheme = PRESET_THEMES.find((t) => t.id === themeId) ?? PRESET_THEMES[0];

  const handleFilePick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    setStatus(null);
    try {
      const dataUrl = await compressImageToWallpaperJpeg(f);
      setPreviewUrl(dataUrl);
    } catch (err) {
      setStatus({ ok: false, text: err instanceof Error ? err.message : 'Image invalide.' });
    }
    e.target.value = '';
  };

  const handleApply = async () => {
    setStatus(null);
    let dataUrl = previewUrl;
    if (mode === 'message') {
      if (!headline.trim()) return;
      dataUrl = renderMessageWallpaperJpeg(headline, subtitle, activeTheme);
    }
    if (!dataUrl) {
      setStatus({ ok: false, text: 'Choisissez d’abord une image.' });
      return;
    }
    setSending(true);
    try {
      await onApplyWallpaper(dataUrl, target);
      setStatus({ ok: true, text: 'Fond d’écran envoyé au smartphone !' });
    } catch (err) {
      setStatus({ ok: false, text: err instanceof Error ? err.message : 'Envoi impossible.' });
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="space-y-3 rounded-2xl border border-white/[0.07] bg-zinc-900/65 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
            <Smartphone className="h-4 w-4 text-sky-400" />
            Fond d’écran à distance
          </h3>
          <p className="text-[11px] text-zinc-400">
            Changez instantanément le fond d’écran d’accueil ou de verrouillage du téléphone.
          </p>
        </div>

        <div className="inline-flex rounded-xl border border-white/[0.08] bg-zinc-950 p-1">
          <button
            type="button"
            onClick={() => setMode('photo')}
            className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium transition ${
              mode === 'photo' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
            }`}
          >
            <ImageIcon className="h-3.5 w-3.5" />
            Photo
          </button>
          <button
            type="button"
            onClick={() => setMode('message')}
            className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium transition ${
              mode === 'message' ? 'bg-white text-zinc-950' : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Type className="h-3.5 w-3.5" />
            Texte
          </button>
        </div>
      </div>

      {/* Target screen picker */}
      <div className="grid grid-cols-3 gap-1.5">
        {(
          [
            { id: 'both', label: 'Les deux écrans' },
            { id: 'lock', label: 'Verrouillage' },
            { id: 'home', label: 'Accueil' },
          ] as const
        ).map((opt) => (
          <button
            key={opt.id}
            type="button"
            onClick={() => setTarget(opt.id)}
            className={`rounded-xl border px-2 py-1.5 text-[11px] font-medium transition ${
              target === opt.id
                ? 'border-sky-400/50 bg-sky-500/15 text-sky-200'
                : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>

      {mode === 'photo' ? (
        <div className="space-y-2.5">
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            onChange={(e) => void handleFilePick(e)}
            className="hidden"
          />
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-white/15 bg-zinc-950/70 px-4 py-4 text-xs font-medium text-zinc-300 transition hover:border-sky-400/40 hover:text-white"
          >
            <Upload className="h-4 w-4 text-sky-400" />
            {previewUrl ? 'Choisir une autre image' : 'Choisir une photo sur votre appareil'}
          </button>

          {previewUrl && (
            <div className="flex justify-center">
              <img
                src={previewUrl}
                alt="Aperçu fond d’écran"
                className="h-44 w-24 rounded-xl border border-white/15 object-cover shadow-lg"
              />
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2.5">
          <input
            type="text"
            value={headline}
            onChange={(e) => setHeadline(e.target.value)}
            maxLength={90}
            placeholder="Message principal affiché sur le fond d’écran..."
            className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 outline-none focus:border-sky-400/50"
          />
          <input
            type="text"
            value={subtitle}
            onChange={(e) => setSubtitle(e.target.value)}
            maxLength={48}
            placeholder="Sous-titre (optionnel)"
            className="w-full rounded-xl border border-white/10 bg-zinc-950 px-3 py-2 text-xs text-white placeholder-zinc-500 outline-none focus:border-sky-400/50"
          />
          <div className="flex flex-wrap items-center gap-1.5">
            {PRESET_THEMES.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => setThemeId(t.id)}
                className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-[11px] font-medium transition ${
                  themeId === t.id
                    ? 'border-white/40 bg-white/10 text-white'
                    : 'border-white/10 bg-zinc-950 text-zinc-400 hover:text-white'
                }`}
              >
                <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: t.accent }} />
                {t.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {status && (
        <div
          className={`flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs ${
            status.ok
              ? 'border-emerald-500/35 bg-emerald-500/10 text-emerald-200'
              : 'border-rose-500/35 bg-rose-500/10 text-rose-200'
          }`}
        >
          {status.ok && <Check className="h-3.5 w-3.5 shrink-0" />}
          <span>{status.text}</span>
        </div>
      )}

      <button
        type="button"
        disabled={sending || (mode === 'photo' ? !previewUrl : !headline.trim())}
        onClick={() => void handleApply()}
        className="flex w-full items-center justify-center gap-2 rounded-xl bg-white py-2.5 text-xs font-semibold text-zinc-950 transition hover:bg-zinc-200 disabled:opacity-40"
      >
        <Send className="h-3.5 w-3.5" />
        {sending ? 'Application en cours...' : 'Appliquer le fond d’écran'}
      </button>
    </div>
  );
};
