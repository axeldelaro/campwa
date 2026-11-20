import {
  base64ToBytes,
  bytesToBase64,
  bytesToHex,
  hexToBytes,
  importAesGcmKey,
  normalizePairingCode,
  randomHex,
} from '../crypto/cryptoEngine';
import type { FamilyAnnounceMsg, PairedDeviceRecord } from '../types/sentinel';
import {
  getOrCreateControllerTokenId,
  listPairedDevices,
  replacePairedDevices,
  setFamilyKey,
} from './deviceVault';
import { discoverFamilyDevices } from './parentPairingService';
import { publishRetainedRecord, readRetainedRecord } from './signalingService';

const ACCOUNT_SESSION_KEY = 'sentinelcam.parent.account.v18';
const PAIRING_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const ACCOUNT_PBKDF2_ITERATIONS = 150_000;

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

export interface ParentChildRecord {
  pairingCode: string;
  deviceId: string;
  deviceName: string;
  childName: string;
  pairedAt: number;
  lastConnectedAt: number;
}

export interface ParentAccountSession {
  email: string;
  displayName: string;
  familyKey: string;
  vaultEncRawHex: string;
  vaultAuthRawHex: string;
  acctTopic: string;
  vaultTopic: string;
  createdAt: number;
  updatedAt: number;
  children: ParentChildRecord[];
  deletedCodes?: Record<string, number>;
}

interface AccountMarkerRecord {
  v: 1;
  nonce: string;
  proof: string;
  createdAt: number;
}

interface EncryptedVaultEnvelope {
  v: 1;
  iv: string;
  ct: string;
  ts: number;
}

interface DecryptedVaultPayload {
  v: 1;
  email: string;
  displayName: string;
  updatedAt: number;
  children: ParentChildRecord[];
  deletedCodes?: Record<string, number>;
}

export interface ChildLiveStatus {
  deviceCode: string;
  deviceName: string;
  online: boolean;
  batteryLevel?: number;
  isCharging?: boolean;
  portalState?: string;
  pendingRequestsCount?: number;
  unpairRequestPending?: boolean;
  activeAppLocksCount?: number;
  screenPausedUntilMs?: number;
  lostModeEnabled?: boolean;
  lastSeenAt: number;
}

export function normalizeAccountEmail(raw: string): string {
  return raw.trim().toLowerCase();
}

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', textEncoder.encode(input));
  return bytesToHex(new Uint8Array(digest));
}

async function computeHmacHex(rawKey: Uint8Array, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    rawKey as BufferSource,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, textEncoder.encode(message));
  return bytesToHex(new Uint8Array(sig));
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

interface DerivedParentAccountKeys {
  email: string;
  familyKey: string;
  vaultEncRaw: Uint8Array;
  vaultAuthRaw: Uint8Array;
  acctTopic: string;
  vaultTopic: string;
}

/**
 * Deterministically derives ALL cryptographic keys from (Email + Password) locally in the browser:
 * - Same (Email + Password) on ANY new phone/PC produces the exact same FamilyKey & Encrypted Vault Topic.
 * - Zero password or plaintext ever leaves the user's browser.
 */
async function deriveParentAccountKeys(
  rawEmail: string,
  rawPassword: string
): Promise<DerivedParentAccountKeys> {
  const email = normalizeAccountEmail(rawEmail);
  const password = rawPassword;

  if (email.length < 3) {
    throw new Error('Saisissez un e-mail ou identifiant valide (min. 3 caractères).');
  }
  if (password.length < 6) {
    throw new Error('Le mot de passe doit contenir au moins 6 caractères.');
  }

  const baseKey = await crypto.subtle.importKey(
    'raw',
    textEncoder.encode(password),
    { name: 'PBKDF2' },
    false,
    ['deriveBits']
  );

  const bits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: textEncoder.encode(`sentinelcam-parent-account-v1:${email}`),
      iterations: ACCOUNT_PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    baseKey,
    640 // 80 bytes
  );

  const master = new Uint8Array(bits);
  let familyKey = '';
  for (let i = 0; i < 12; i++) {
    familyKey += PAIRING_ALPHABET[master[i] % PAIRING_ALPHABET.length];
  }

  const vaultEncRaw = master.slice(16, 48); // 32 bytes AES-256-GCM
  const vaultAuthRaw = master.slice(48, 80); // 32 bytes HMAC-SHA256

  const emailHash = await sha256Hex(`sentinelcam-acct-id-v1:${email}`);
  const vaultHash = await sha256Hex(`sentinelcam-vault-id-v1:${bytesToHex(vaultAuthRaw)}`);

  return {
    email,
    familyKey,
    vaultEncRaw,
    vaultAuthRaw,
    acctTopic: `scam/v1/ac/${emailHash.slice(0, 32)}`,
    vaultTopic: `scam/v1/kv/${vaultHash.slice(0, 32)}`,
  };
}

async function encryptVaultPayload(
  payload: DecryptedVaultPayload,
  vaultEncRaw: Uint8Array
): Promise<string> {
  const key = await importAesGcmKey(vaultEncRaw);
  const iv = new Uint8Array(12);
  crypto.getRandomValues(iv);
  const plainBytes = textEncoder.encode(JSON.stringify(payload));
  const ctBuf = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    plainBytes
  );
  const env: EncryptedVaultEnvelope = {
    v: 1,
    iv: bytesToBase64(iv),
    ct: bytesToBase64(new Uint8Array(ctBuf)),
    ts: payload.updatedAt,
  };
  return JSON.stringify(env);
}

async function decryptVaultPayload(
  rawJson: string,
  vaultEncRaw: Uint8Array
): Promise<DecryptedVaultPayload | null> {
  try {
    const env = JSON.parse(rawJson) as EncryptedVaultEnvelope;
    if (!env || env.v !== 1 || !env.iv || !env.ct) return null;
    const key = await importAesGcmKey(vaultEncRaw);
    const iv = base64ToBytes(env.iv);
    const ct = base64ToBytes(env.ct);
    const plainBuf = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv as BufferSource },
      key,
      ct as BufferSource
    );
    const parsed = JSON.parse(textDecoder.decode(plainBuf)) as DecryptedVaultPayload;
    if (!parsed || parsed.v !== 1 || !Array.isArray(parsed.children)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function parseAccountMarker(rawJson: string): AccountMarkerRecord | null {
  try {
    const m = JSON.parse(rawJson) as AccountMarkerRecord;
    if (!m || m.v !== 1 || !m.nonce || !m.proof) return null;
    return m;
  } catch {
    return null;
  }
}

function mergeDeletedCodes(
  a?: Record<string, number>,
  b?: Record<string, number>
): Record<string, number> {
  const out: Record<string, number> = {};
  for (const src of [a, b]) {
    if (!src) continue;
    for (const [k, v] of Object.entries(src)) {
      const code = normalizePairingCode(k);
      if (code.length === 6 && typeof v === 'number' && v > (out[code] ?? 0)) {
        out[code] = v;
      }
    }
  }
  return out;
}

function filterOutDeletedChildren(
  list: ParentChildRecord[],
  deletedCodes?: Record<string, number>
): ParentChildRecord[] {
  if (!deletedCodes) return list;
  return list.filter((c) => {
    const delAt = deletedCodes[normalizePairingCode(c.pairingCode)];
    if (!delAt) return true;
    // Only allow through if newly re-paired strictly AFTER deletion timestamp
    return (c.pairedAt ?? 0) > delAt + 5000;
  });
}

function mergeChildrenLists(
  primary: ParentChildRecord[],
  secondary: ParentChildRecord[]
): ParentChildRecord[] {
  const byCode = new Map<string, ParentChildRecord>();
  for (const item of [...secondary, ...primary]) {
    const code = normalizePairingCode(item.pairingCode);
    if (code.length !== 6) continue;
    const prev = byCode.get(code);
    if (!prev) {
      byCode.set(code, {
        ...item,
        pairingCode: code,
        childName: item.childName?.trim() || item.deviceName || 'Enfant',
      });
    } else {
      byCode.set(code, {
        pairingCode: code,
        deviceId: item.deviceId || prev.deviceId,
        deviceName: item.deviceName || prev.deviceName,
        childName:
          item.childName && item.childName !== item.deviceName
            ? item.childName
            : prev.childName || item.deviceName || 'Enfant',
        pairedAt: Math.min(prev.pairedAt || Date.now(), item.pairedAt || Date.now()),
        lastConnectedAt: Math.max(prev.lastConnectedAt || 0, item.lastConnectedAt || 0),
      });
    }
  }
  return Array.from(byCode.values()).sort((a, b) => b.lastConnectedAt - a.lastConnectedAt);
}

function syncChildrenToDeviceVault(children: ParentChildRecord[]): void {
  const existingMap = new Map(listPairedDevices().map((d) => [d.pairingCode, d]));
  const tokenId = getOrCreateControllerTokenId();
  const nextRecords: PairedDeviceRecord[] = children.map((c) => {
    const ex = existingMap.get(c.pairingCode);
    return {
      pairingCode: c.pairingCode,
      deviceId: c.deviceId || ex?.deviceId || '',
      deviceName: c.deviceName || ex?.deviceName || 'Smartphone',
      childName: c.childName,
      tokenId: ex?.tokenId || tokenId,
      sasCode: ex?.sasCode || '',
      sasSymbols: ex?.sasSymbols || '',
      accessRole: 'owner',
      accessName: 'Parent',
      pairedAt: c.pairedAt,
      lastConnectedAt: c.lastConnectedAt,
    };
  });
  replacePairedDevices(nextRecords);
}

export function loadParentAccountSession(): ParentAccountSession | null {
  try {
    const raw = localStorage.getItem(ACCOUNT_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ParentAccountSession;
    if (!parsed || !parsed.email || !parsed.familyKey || !parsed.vaultEncRawHex) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function saveParentAccountSession(session: ParentAccountSession): void {
  try {
    localStorage.setItem(ACCOUNT_SESSION_KEY, JSON.stringify(session));
    setFamilyKey(session.familyKey);
    syncChildrenToDeviceVault(session.children);
  } catch {
    // Ignore storage errors
  }
}

export function logoutParentAccount(): void {
  try {
    localStorage.removeItem(ACCOUNT_SESSION_KEY);
    replacePairedDevices([]);
  } catch {
    // Ignore
  }
}

async function publishAccountRecords(
  session: ParentAccountSession,
  includeMarker = false
): Promise<boolean> {
  const vaultEncRaw = hexToBytes(session.vaultEncRawHex);
  const vaultPayload: DecryptedVaultPayload = {
    v: 1,
    email: session.email,
    displayName: session.displayName,
    updatedAt: session.updatedAt,
    children: session.children,
    deletedCodes: session.deletedCodes,
  };
  const encryptedVault = await encryptVaultPayload(vaultPayload, vaultEncRaw);
  const okVault = await publishRetainedRecord(session.vaultTopic, encryptedVault);

  if (includeMarker) {
    const vaultAuthRaw = hexToBytes(session.vaultAuthRawHex);
    const nonce = randomHex(16);
    const proof = await computeHmacHex(vaultAuthRaw, `acct:${nonce}`);
    const marker: AccountMarkerRecord = {
      v: 1,
      nonce,
      proof,
      createdAt: session.createdAt,
    };
    await publishRetainedRecord(session.acctTopic, JSON.stringify(marker));
  }
  return okVault;
}

/**
 * Registers a brand-new Zero-Knowledge Serverless Parent Account.
 */
export async function registerParentAccount(options: {
  email: string;
  password: string;
  displayName?: string;
}): Promise<ParentAccountSession> {
  const derived = await deriveParentAccountKeys(options.email, options.password);

  // Check if an account already exists for this identifier on the broker pool
  const existingMarkers = await readRetainedRecord(derived.acctTopic, 1700);
  if (existingMarkers.some((m) => parseAccountMarker(m) !== null)) {
    throw new Error('Un compte existe déjà avec cet identifiant. Connectez-vous.');
  }

  const now = Date.now();
  const displayName =
    options.displayName?.trim() ||
    derived.email.split('@')[0].replace(/[._-]+/g, ' ').trim() ||
    'Parent';

  const session: ParentAccountSession = {
    email: derived.email,
    displayName,
    familyKey: derived.familyKey,
    vaultEncRawHex: bytesToHex(derived.vaultEncRaw),
    vaultAuthRawHex: bytesToHex(derived.vaultAuthRaw),
    acctTopic: derived.acctTopic,
    vaultTopic: derived.vaultTopic,
    createdAt: now,
    updatedAt: now,
    children: [],
  };

  const published = await publishAccountRecords(session, true);
  if (!published) {
    throw new Error('Réseau indisponible. Vérifiez votre connexion Internet et réessayez.');
  }

  saveParentAccountSession(session);
  return session;
}

/**
 * Logs into an existing Parent Account from ANY new phone, browser, or computer,
 * verifies the password cryptographically, decrypts the multi-child vault,
 * AND discovers all currently online child devices in parallel.
 */
export async function loginParentAccount(options: {
  email: string;
  password: string;
  onLiveChild?: (status: ChildLiveStatus) => void;
}): Promise<{ session: ParentAccountSession; liveStatuses: Record<string, ChildLiveStatus> }> {
  const derived = await deriveParentAccountKeys(options.email, options.password);

  // Read Account Marker + Encrypted Vault in parallel
  const [rawMarkers, rawVaults] = await Promise.all([
    readRetainedRecord(derived.acctTopic, 1900),
    readRetainedRecord(derived.vaultTopic, 1900),
  ]);

  const parsedMarkers = rawMarkers
    .map(parseAccountMarker)
    .filter((m): m is AccountMarkerRecord => m !== null);

  let markerVerified = false;
  if (parsedMarkers.length > 0) {
    for (const marker of parsedMarkers) {
      const expected = await computeHmacHex(derived.vaultAuthRaw, `acct:${marker.nonce}`);
      if (constantTimeEqual(expected, marker.proof)) {
        markerVerified = true;
        break;
      }
    }
    if (!markerVerified) {
      throw new Error('Mot de passe incorrect.');
    }
  }

  // Decrypt all candidate vaults and pick the newest one
  let latestVault: DecryptedVaultPayload | null = null;
  for (const raw of rawVaults) {
    const dec = await decryptVaultPayload(raw, derived.vaultEncRaw);
    if (dec && (!latestVault || dec.updatedAt > latestVault.updatedAt)) {
      latestVault = dec;
    }
  }

  // Live discovery via deterministic familyKey (recovers online children even if brokers reset!)
  const liveStatuses: Record<string, ChildLiveStatus> = {};
  const liveDiscovered: ParentChildRecord[] = [];
  const discoveredRaw = await discoverFamilyDevices(
    derived.familyKey,
    (msg: FamilyAnnounceMsg) => {
      const code = normalizePairingCode(msg.deviceCode);
      const st: ChildLiveStatus = {
        deviceCode: code,
        deviceName: msg.deviceName,
        online: true,
        batteryLevel: msg.batteryLevel,
        isCharging: msg.isCharging,
        portalState: msg.portalState,
        pendingRequestsCount: msg.pendingRequestsCount,
        unpairRequestPending: msg.unpairRequestPending,
        activeAppLocksCount: msg.activeAppLocksCount,
        screenPausedUntilMs: msg.screenPausedUntilMs,
        lostModeEnabled: msg.lostModeEnabled,
        lastSeenAt: Date.now(),
      };
      liveStatuses[code] = st;
      options.onLiveChild?.(st);
    },
    latestVault || markerVerified ? 2400 : 3800
  );

  for (const msg of discoveredRaw) {
    const code = normalizePairingCode(msg.deviceCode);
    liveDiscovered.push({
      pairingCode: code,
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      childName: msg.deviceName,
      pairedAt: Date.now(),
      lastConnectedAt: Date.now(),
    });
  }

  if (!markerVerified && !latestVault && liveDiscovered.length === 0) {
    throw new Error('Compte introuvable. Vérifiez vos identifiants ou créez un compte.');
  }

  const now = Date.now();
  const mergedDeleted = mergeDeletedCodes(latestVault?.deletedCodes);
  const mergedChildren = filterOutDeletedChildren(
    mergeChildrenLists(latestVault?.children ?? [], liveDiscovered),
    mergedDeleted
  );
  const session: ParentAccountSession = {
    email: derived.email,
    displayName:
      latestVault?.displayName ||
      derived.email.split('@')[0].replace(/[._-]+/g, ' ').trim() ||
      'Parent',
    familyKey: derived.familyKey,
    vaultEncRawHex: bytesToHex(derived.vaultEncRaw),
    vaultAuthRawHex: bytesToHex(derived.vaultAuthRaw),
    acctTopic: derived.acctTopic,
    vaultTopic: derived.vaultTopic,
    createdAt: parsedMarkers[0]?.createdAt ?? now,
    updatedAt: Math.max(latestVault?.updatedAt ?? 0, now),
    children: mergedChildren,
    deletedCodes: mergedDeleted,
  };

  saveParentAccountSession(session);

  // Refresh retained records on both brokers in background so vault stays alive forever
  void publishAccountRecords(session, !markerVerified);

  return { session, liveStatuses };
}

/**
 * Adds or updates a child device inside the Parent Account and syncs the encrypted vault.
 */
export async function upsertAccountChild(
  session: ParentAccountSession,
  child: ParentChildRecord
): Promise<ParentAccountSession> {
  const cleanCode = normalizePairingCode(child.pairingCode);
  const nextDeleted = { ...(session.deletedCodes ?? {}) };
  delete nextDeleted[cleanCode];
  const merged = mergeChildrenLists([child], session.children);
  const updated: ParentAccountSession = {
    ...session,
    updatedAt: Date.now(),
    children: merged,
    deletedCodes: nextDeleted,
  };
  saveParentAccountSession(updated);
  await publishAccountRecords(updated, false);
  return updated;
}

/**
 * Renames a child label (e.g. "Lucas", "Emma") and syncs across all parent devices.
 */
export async function renameAccountChild(
  session: ParentAccountSession,
  pairingCode: string,
  newChildName: string
): Promise<ParentAccountSession> {
  const cleanCode = normalizePairingCode(pairingCode);
  const cleanName = newChildName.trim().slice(0, 32);
  if (!cleanName) return session;

  const updated: ParentAccountSession = {
    ...session,
    updatedAt: Date.now(),
    children: session.children.map((c) =>
      c.pairingCode === cleanCode ? { ...c, childName: cleanName } : c
    ),
  };
  saveParentAccountSession(updated);
  await publishAccountRecords(updated, false);
  return updated;
}

/**
 * Removes a child from the Parent Account vault and tombstones its code so stale
 * broker copies can NEVER resurrect a removed or unpaired child into history.
 */
export async function removeAccountChild(
  session: ParentAccountSession,
  pairingCode: string
): Promise<ParentAccountSession> {
  const cleanCode = normalizePairingCode(pairingCode);
  const now = Date.now();
  const nextDeleted = { ...(session.deletedCodes ?? {}), [cleanCode]: now };
  const updated: ParentAccountSession = {
    ...session,
    updatedAt: now,
    children: session.children.filter((c) => c.pairingCode !== cleanCode),
    deletedCodes: nextDeleted,
  };
  saveParentAccountSession(updated);
  await publishAccountRecords(updated, false);
  return updated;
}

/**
 * Refreshes both the Encrypted Cloud Vault (for Co-Parent sync) AND live online status
 * of every child phone bound to this Parent Account.
 */
export async function syncParentAccountLive(
  session: ParentAccountSession,
  onLiveChild?: (status: ChildLiveStatus) => void
): Promise<{ session: ParentAccountSession; liveStatuses: Record<string, ChildLiveStatus> }> {
  const vaultEncRaw = hexToBytes(session.vaultEncRawHex);
  const liveStatuses: Record<string, ChildLiveStatus> = {};

  const [rawVaults, discovered] = await Promise.all([
    readRetainedRecord(session.vaultTopic, 1400),
    discoverFamilyDevices(
      session.familyKey,
      (msg) => {
        const code = normalizePairingCode(msg.deviceCode);
        const st: ChildLiveStatus = {
          deviceCode: code,
          deviceName: msg.deviceName,
          online: true,
          batteryLevel: msg.batteryLevel,
          isCharging: msg.isCharging,
          portalState: msg.portalState,
          pendingRequestsCount: msg.pendingRequestsCount,
          unpairRequestPending: msg.unpairRequestPending,
          activeAppLocksCount: msg.activeAppLocksCount,
          screenPausedUntilMs: msg.screenPausedUntilMs,
          lostModeEnabled: msg.lostModeEnabled,
          lastSeenAt: Date.now(),
        };
        liveStatuses[code] = st;
        onLiveChild?.(st);
      },
      2600
    ),
  ]);

  let remoteVault: DecryptedVaultPayload | null = null;
  for (const raw of rawVaults) {
    const dec = await decryptVaultPayload(raw, vaultEncRaw);
    if (dec && (!remoteVault || dec.updatedAt > remoteVault.updatedAt)) {
      remoteVault = dec;
    }
  }

  const liveRecords: ParentChildRecord[] = discovered.map((m) => ({
    pairingCode: normalizePairingCode(m.deviceCode),
    deviceId: m.deviceId,
    deviceName: m.deviceName,
    childName: m.deviceName,
    pairedAt: Date.now(),
    lastConnectedAt: Date.now(),
  }));

  const mergedDeleted = mergeDeletedCodes(session.deletedCodes, remoteVault?.deletedCodes);
  const baseList =
    remoteVault && remoteVault.updatedAt > session.updatedAt
      ? mergeChildrenLists(remoteVault.children, session.children)
      : mergeChildrenLists(session.children, remoteVault?.children ?? []);

  const finalChildren = filterOutDeletedChildren(
    mergeChildrenLists(baseList, liveRecords),
    mergedDeleted
  );
  const changed =
    finalChildren.length !== session.children.length ||
    (remoteVault && remoteVault.updatedAt > session.updatedAt);

  const nextSession: ParentAccountSession = {
    ...session,
    displayName: remoteVault?.displayName || session.displayName,
    updatedAt: changed ? Date.now() : session.updatedAt,
    children: finalChildren,
    deletedCodes: mergedDeleted,
  };

  saveParentAccountSession(nextSession);
  if (liveRecords.length > 0 && finalChildren.length > (remoteVault?.children.length ?? 0)) {
    void publishAccountRecords(nextSession, false);
  }

  return { session: nextSession, liveStatuses };
}
