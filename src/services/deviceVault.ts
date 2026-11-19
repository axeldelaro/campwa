import { generateFamilyKey, normalizeFamilyKey, randomHex } from '../crypto/cryptoEngine';
import type { PairedDeviceRecord, TurnServerSetting } from '../types/sentinel';

const VAULT_PAIRED_KEY = 'sentinelcam.vault.paired.v1';
const VAULT_TOKEN_ID_KEY = 'sentinelcam.vault.token_id.v1';
const VAULT_TURN_KEY = 'sentinelcam.vault.turn.v1';
const VAULT_FAMILY_KEY = 'sentinelcam.vault.family_key.v15';

export function getOrCreateFamilyKey(): string {
  try {
    const existing = normalizeFamilyKey(localStorage.getItem(VAULT_FAMILY_KEY) || '');
    if (existing.length === 12) return existing;
    const created = generateFamilyKey();
    localStorage.setItem(VAULT_FAMILY_KEY, created);
    return created;
  } catch {
    return generateFamilyKey();
  }
}

export function setFamilyKey(rawKey: string): string {
  const clean = normalizeFamilyKey(rawKey);
  if (clean.length !== 12) {
    throw new Error('La Clé Famille doit contenir 12 caractères.');
  }
  try {
    localStorage.setItem(VAULT_FAMILY_KEY, clean);
  } catch {
    // Ignore
  }
  return clean;
}

export function getOrCreateControllerTokenId(): string {
  try {
    const existing = localStorage.getItem(VAULT_TOKEN_ID_KEY);
    if (existing && existing.length === 24) {
      return existing;
    }
    const created = randomHex(12);
    localStorage.setItem(VAULT_TOKEN_ID_KEY, created);
    return created;
  } catch {
    return randomHex(12);
  }
}

export function listPairedDevices(): PairedDeviceRecord[] {
  try {
    const raw = localStorage.getItem(VAULT_PAIRED_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as PairedDeviceRecord[];
    if (!Array.isArray(parsed)) return [];
    return parsed.sort((a, b) => b.lastConnectedAt - a.lastConnectedAt);
  } catch {
    return [];
  }
}

export function findPairedDeviceByCode(code: string): PairedDeviceRecord | undefined {
  const normalized = code.trim().toUpperCase();
  return listPairedDevices().find((d) => d.pairingCode === normalized);
}

export function savePairedDevice(record: PairedDeviceRecord): void {
  try {
    const existing = listPairedDevices().find((d) => d.pairingCode === record.pairingCode);
    const merged: PairedDeviceRecord = {
      ...record,
      childName: record.childName ?? existing?.childName,
    };
    const list = listPairedDevices().filter((d) => d.pairingCode !== record.pairingCode);
    list.unshift(merged);
    localStorage.setItem(VAULT_PAIRED_KEY, JSON.stringify(list.slice(0, 20)));
  } catch {
    // Ignore storage quota errors
  }
}

export function replacePairedDevices(records: PairedDeviceRecord[]): void {
  try {
    localStorage.setItem(VAULT_PAIRED_KEY, JSON.stringify(records.slice(0, 20)));
  } catch {
    // Ignore
  }
}

export function removePairedDevice(code: string): void {
  try {
    const normalized = code.trim().toUpperCase();
    const list = listPairedDevices().filter((d) => d.pairingCode !== normalized);
    localStorage.setItem(VAULT_PAIRED_KEY, JSON.stringify(list));
  } catch {
    // Ignore
  }
}

export function loadCustomTurnServers(): TurnServerSetting[] {
  try {
    const raw = localStorage.getItem(VAULT_TURN_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as TurnServerSetting[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveCustomTurnServers(servers: TurnServerSetting[]): void {
  try {
    localStorage.setItem(VAULT_TURN_KEY, JSON.stringify(servers));
  } catch {
    // Ignore
  }
}

export function buildIceServers(): RTCIceServer[] {
  const defaults: RTCIceServer[] = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
    { urls: 'stun:stun2.l.google.com:19302' },
    { urls: 'stun:stun3.l.google.com:19302' },
    { urls: 'stun:stun4.l.google.com:19302' },
    { urls: 'stun:stun.cloudflare.com:3478' },
    { urls: 'stun:stun.nextcloud.com:443' },
  ];

  const custom = loadCustomTurnServers()
    .filter((s) => s.urls.trim().length > 0)
    .map((s) => ({
      urls: s.urls.trim(),
      ...(s.username ? { username: s.username } : {}),
      ...(s.credential ? { credential: s.credential } : {}),
    }));

  return [...defaults, ...custom];
}
