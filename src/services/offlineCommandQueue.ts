import type { SignalMessage } from '../types/sentinel';

/**
 * #22 Store & Forward Offline Command Queue:
 * - Persists parental control commands sent while the child phone is offline (metro, airplane, asleep).
 * - Automatically flushes every queued command in exact order the millisecond the device reconnects!
 */
export interface QueuedCommandEntry {
  id: string;
  label: string;
  payload: SignalMessage;
  queuedAt: number;
}

const STORAGE_PREFIX = 'sentinelcam_offline_queue_v27_';
const MAX_QUEUED_PER_DEVICE = 25;
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

function keyFor(code: string): string {
  return `${STORAGE_PREFIX}${code.trim().toUpperCase()}`;
}

export function loadOfflineQueue(code: string): QueuedCommandEntry[] {
  if (!code) return [];
  try {
    const raw = localStorage.getItem(keyFor(code));
    if (!raw) return [];
    const parsed = JSON.parse(raw) as QueuedCommandEntry[];
    if (!Array.isArray(parsed)) return [];
    const cutoff = Date.now() - MAX_AGE_MS;
    return parsed.filter((e) => e && typeof e.queuedAt === 'number' && e.queuedAt > cutoff);
  } catch {
    return [];
  }
}

export function enqueueOfflineCommand(
  code: string,
  label: string,
  payload: SignalMessage
): QueuedCommandEntry[] {
  if (!code) return [];
  const current = loadOfflineQueue(code);
  const entry: QueuedCommandEntry = {
    id: `q-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`,
    label,
    payload,
    queuedAt: Date.now(),
  };
  const next = [...current, entry].slice(-MAX_QUEUED_PER_DEVICE);
  try {
    localStorage.setItem(keyFor(code), JSON.stringify(next));
  } catch {
    // Ignore quota errors
  }
  return next;
}

export function clearOfflineQueue(code: string): void {
  if (!code) return;
  try {
    localStorage.removeItem(keyFor(code));
  } catch {
    // Ignore
  }
}

export function removeOfflineCommand(code: string, id: string): QueuedCommandEntry[] {
  const next = loadOfflineQueue(code).filter((e) => e.id !== id);
  try {
    if (next.length === 0) localStorage.removeItem(keyFor(code));
    else localStorage.setItem(keyFor(code), JSON.stringify(next));
  } catch {
    // Ignore
  }
  return next;
}
