import type { DerivedBootstrapKeys, EncryptedEnvelope, SignalMessage } from '../types/sentinel';

const PAIRING_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const PBKDF2_SALT = 'sentinelcam-v3-pbkdf2-salt';
const PBKDF2_ITERATIONS = 100_000;
const SAS_SYMBOLS_TABLE = [
  '🛡️', '🔐', '⚡', '🛰️', '🔑', '💎', '🧭', '🔥',
  '🌐', '🧿', '🎯', '🏔️', '🦅', '⚓', '🧬', '🔮',
];

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// In-memory cache so reconnecting to a code takes 0.0ms
const bootstrapCache = new Map<string, DerivedBootstrapKeys>();

export function normalizePairingCode(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, 6);
}

export function generateSecurePairingCode(): string {
  const randomBytes = new Uint8Array(6);
  crypto.getRandomValues(randomBytes);
  let code = '';
  for (let i = 0; i < 6; i++) {
    code += PAIRING_ALPHABET[randomBytes[i] % PAIRING_ALPHABET.length];
  }
  return code;
}

export function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

export function bytesToHex(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    out += bytes[i].toString(16).padStart(2, '0');
  }
  return out;
}

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim().toLowerCase();
  if (clean.length % 2 !== 0) {
    throw new Error('Invalid hex string length');
  }
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

/**
 * Memoized 100,000-round PBKDF2-HMAC-SHA256 derivation.
 * Byte-identical to Kotlin CryptoProtocol.deriveBootstrapKeys().
 */
export async function deriveBootstrapKeys(code: string): Promise<DerivedBootstrapKeys> {
  const normalizedCode = normalizePairingCode(code);
  if (normalizedCode.length !== 6) {
    throw new Error('Le code doit comporter 6 caractères.');
  }

  const cached = bootstrapCache.get(normalizedCode);
  if (cached) return cached;

  const baseKey = await crypto.subtle.importKey(
    'raw',
    encoder.encode(normalizedCode),
    { name: 'PBKDF2' },
    false,
    ['deriveBits']
  );

  const bits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: encoder.encode(PBKDF2_SALT),
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    baseKey,
    512 // 64 bytes
  );

  const master = new Uint8Array(bits);
  const topicId = bytesToHex(master.slice(0, 16));
  const bootstrapEncRaw = master.slice(16, 48); // 32 bytes
  const bootstrapAuthRaw = master.slice(48, 64);// 16 bytes
  const wakePushTopic = `sc_wake_${topicId.slice(0, 20)}`;

  const bootstrapEncKey = await importAesGcmKey(bootstrapEncRaw);

  const result: DerivedBootstrapKeys = {
    normalizedCode,
    topicId,
    wakePushTopic,
    bootstrapEncKey,
    bootstrapEncRaw,
    bootstrapAuthRaw,
  };
  bootstrapCache.set(normalizedCode, result);
  return result;
}

export async function importAesGcmKey(rawKeyBytes: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    'raw',
    rawKeyBytes as BufferSource,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

export async function hkdfSha256(
  ikm: Uint8Array,
  salt: Uint8Array,
  infoStr: string,
  lengthBytes = 32
): Promise<Uint8Array> {
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    ikm as BufferSource,
    { name: 'HKDF' },
    false,
    ['deriveBits']
  );

  const derived = await crypto.subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: salt as BufferSource,
      info: encoder.encode(infoStr),
    },
    keyMaterial,
    lengthBytes * 8
  );

  return new Uint8Array(derived);
}

export async function hmacSha256Hex(keyBytes: Uint8Array, message: string): Promise<string> {
  const hmacKey = await crypto.subtle.importKey(
    'raw',
    keyBytes as BufferSource,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', hmacKey, encoder.encode(message));
  return bytesToHex(new Uint8Array(sig));
}

export async function computeHelloWakeProof(
  bootstrapAuthRaw: Uint8Array,
  clientNonce: string,
  tokenId: string
): Promise<string> {
  return hmacSha256Hex(bootstrapAuthRaw, `hello-wake-v3:${clientNonce}:${tokenId}`);
}

export async function computeHelloAckProof(
  bootstrapAuthRaw: Uint8Array,
  clientNonce: string,
  serverNonce: string,
  deviceId: string
): Promise<string> {
  return hmacSha256Hex(bootstrapAuthRaw, `hello-ack-v3:${clientNonce}:${serverNonce}:${deviceId}`);
}

/**
 * Stateless 1-RTT Ephemeral Session Key (HKDF-SHA256) with per-connection Forward Secrecy.
 */
export async function deriveFastSessionKey(
  bootstrapAuthRaw: Uint8Array,
  clientNonce: string,
  serverNonce: string,
  deviceId: string
): Promise<CryptoKey> {
  const salt = encoder.encode(`${clientNonce}:${serverNonce}:${deviceId}`);
  const raw = await hkdfSha256(bootstrapAuthRaw, salt, 'sentinelcam-v3-fast-session', 32);
  return importAesGcmKey(raw);
}

export async function computeSas(
  normalizedCode: string,
  tokenId: string,
  deviceId: string,
  bootstrapAuthRaw: Uint8Array
): Promise<{ sasCode: string; sasSymbols: string }> {
  const authHex = bytesToHex(bootstrapAuthRaw);
  const payload = encoder.encode(`sentinelcam-sas-v3:${normalizedCode}:${tokenId}:${deviceId}:${authHex}`);
  const digestBuf = await crypto.subtle.digest('SHA-256', payload);
  const digest = new Uint8Array(digestBuf);

  const p1 = digest[0].toString(16).padStart(2, '0').toUpperCase();
  const p2 = digest[1].toString(16).padStart(2, '0').toUpperCase();
  const p3 = digest[2].toString(16).padStart(2, '0').toUpperCase();

  const s1 = SAS_SYMBOLS_TABLE[digest[3] & 0x0f];
  const s2 = SAS_SYMBOLS_TABLE[(digest[3] >> 4) & 0x0f];
  const s3 = SAS_SYMBOLS_TABLE[digest[4] & 0x0f];
  const s4 = SAS_SYMBOLS_TABLE[(digest[4] >> 4) & 0x0f];

  return {
    sasCode: `${p1}-${p2}-${p3}`,
    sasSymbols: `${s1} ${s2} ${s3} ${s4}`,
  };
}

export async function computeDtlsMediaSas(fpA: string, fpB: string): Promise<string> {
  if (!fpA || !fpB) return 'EN ATTENTE SDP';
  const sorted = [fpA.trim().toUpperCase(), fpB.trim().toUpperCase()].sort().join('|');
  const digestBuf = await crypto.subtle.digest('SHA-256', encoder.encode(`dtls-sas-v3:${sorted}`));
  const d = new Uint8Array(digestBuf);
  return `${d[0].toString(16).padStart(2, '0')}:${d[1].toString(16).padStart(2, '0')}:${d[2]
    .toString(16)
    .padStart(2, '0')}:${d[3].toString(16).padStart(2, '0')}`.toUpperCase();
}

export function extractSdpFingerprint(sdp: string): string {
  const match = sdp.match(/a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)/i);
  return match ? match[1].toUpperCase() : '';
}

export async function encryptEnvelope(
  msg: SignalMessage,
  aesKey: CryptoKey,
  mode: 'boot' | 'sess',
  senderId: string
): Promise<EncryptedEnvelope> {
  const iv = new Uint8Array(12);
  crypto.getRandomValues(iv);
  const plaintext = encoder.encode(JSON.stringify(msg));

  const ciphertextBuf = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, tagLength: 128 },
    aesKey,
    plaintext
  );

  return {
    v: 1,
    id: randomHex(12),
    mode,
    sender: senderId,
    ts: Date.now(),
    iv: bytesToBase64(iv),
    ct: bytesToBase64(new Uint8Array(ciphertextBuf)),
  };
}

export async function decryptEnvelope(
  envelope: EncryptedEnvelope,
  aesKey: CryptoKey,
  maxSkewMs = 180_000
): Promise<SignalMessage | null> {
  if (envelope.v !== 1 || !envelope.iv || !envelope.ct) {
    return null;
  }

  if (Math.abs(Date.now() - envelope.ts) > maxSkewMs) {
    return null;
  }

  try {
    const iv = base64ToBytes(envelope.iv);
    const ct = base64ToBytes(envelope.ct);
    const plainBuf = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv as BufferSource, tagLength: 128 },
      aesKey,
      ct as BufferSource
    );
    return JSON.parse(decoder.decode(plainBuf)) as SignalMessage;
  } catch {
    return null;
  }
}

export function constantTimeEqualHex(a: string, b: string): boolean {
  const s1 = a.toLowerCase();
  const s2 = b.toLowerCase();
  if (s1.length !== s2.length) return false;
  let diff = 0;
  for (let i = 0; i < s1.length; i++) {
    diff |= s1.charCodeAt(i) ^ s2.charCodeAt(i);
  }
  return diff === 0;
}

export function calculateLockoutDurationMs(failedAttempts: number): number {
  if (failedAttempts < 3) return 0;
  const exponent = Math.min(failedAttempts - 3, 5);
  return 30_000 * Math.pow(2, exponent);
}
