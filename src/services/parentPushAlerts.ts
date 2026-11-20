import { normalizeFamilyKey, normalizePairingCode } from '../crypto/cryptoEngine';

const encoder = new TextEncoder();

async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', encoder.encode(text));
  const arr = new Uint8Array(buf);
  let out = '';
  for (let i = 0; i < arr.length; i++) {
    out += arr[i].toString(16).padStart(2, '0');
  }
  return out;
}

export async function deriveChildAlertTopic(pairingCode: string): Promise<string> {
  const clean = normalizePairingCode(pairingCode);
  if (clean.length !== 6) return '';
  const hash = await sha256Hex(`sentinel-parent-alert-v26:${clean}`);
  return `sc_alert_${hash.slice(0, 18)}`;
}

export async function deriveFamilyAlertTopic(familyKey: string): Promise<string> {
  const clean = normalizeFamilyKey(familyKey);
  if (clean.length !== 12) return '';
  const hash = await sha256Hex(`sentinel-family-alert-v26:${clean}`);
  return `sc_fam_${hash.slice(0, 18)}`;
}

export async function sendTestParentPushAlert(
  topic: string,
  label = 'SentinelCam'
): Promise<boolean> {
  const clean = topic.trim();
  if (!clean) return false;
  try {
    const res = await fetch('https://ntfy.sh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        topic: clean,
        title: `${label} • Notification Parent active`,
        message:
          'Les alertes et demandes de votre enfant arriveront directement sur votre téléphone sans ouvrir le site.',
        priority: 4,
        tags: ['white_check_mark'],
        click: 'https://axeldelaro.github.io/campwa/',
      }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

export async function showBrowserOrSwNotification(title: string, body: string): Promise<void> {
  if (typeof window === 'undefined' || !('Notification' in window)) return;
  if (Notification.permission !== 'granted') return;
  try {
    if ('serviceWorker' in navigator) {
      const reg = await navigator.serviceWorker.getRegistration();
      if (reg && 'showNotification' in reg) {
        await reg.showNotification(title, {
          body,
          icon: './icon.svg',
          badge: './icon.svg',
          tag: `sc-${Date.now()}`,
        });
        return;
      }
    }
  } catch {
    // Fallback to standard Notification API
  }
  try {
    new Notification(title, { body, icon: './icon.svg' });
  } catch {
    // Ignore
  }
}

/**
 * Keeps a lightweight SSE stream open to ntfy.sh for the Parent's private alert topic(s)
 * and fires native OS/PWA notifications whenever an alert or request arrives from a child device.
 */
export function subscribeParentAlertSse(
  topics: string[],
  onAlertReceived?: (title: string, message: string) => void
): () => void {
  const valid = Array.from(new Set(topics.map((t) => t.trim()).filter((t) => t.startsWith('sc_'))));
  if (valid.length === 0 || typeof EventSource === 'undefined') {
    return () => {};
  }
  let closed = false;
  let es: EventSource | null = null;
  let retryTimer: number | null = null;

  const connect = () => {
    if (closed) return;
    try {
      es = new EventSource(`https://ntfy.sh/${valid.join(',')}/sse`);
      es.onmessage = (ev) => {
        try {
          const data = JSON.parse(ev.data) as {
            event?: string;
            title?: string;
            message?: string;
          };
          if (data.event && data.event !== 'message') return;
          const msg = (data.message || '').trim();
          if (!msg || msg.includes('WAKE_INVITE')) return;
          const title = (data.title || '').trim() || 'Alerte SentinelCam';
          onAlertReceived?.(title, msg);
          void showBrowserOrSwNotification(title, msg);
        } catch {
          // Ignore malformed frame
        }
      };
      es.onerror = () => {
        try {
          es?.close();
        } catch {
          // Ignore
        }
        es = null;
        if (!closed) {
          retryTimer = window.setTimeout(connect, 10_000);
        }
      };
    } catch {
      // Ignore
    }
  };

  connect();

  return () => {
    closed = true;
    if (retryTimer !== null) window.clearTimeout(retryTimer);
    try {
      es?.close();
    } catch {
      // Ignore
    }
  };
}
