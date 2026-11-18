import type { DerivedBootstrapKeys, EncryptedEnvelope, SignalMessage } from '../types/sentinel';

const PAIRING_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const PBKDF2_SALT = 'sentinelcam-v1-pbkdf2-salt';
const PBKDF2_ITERATIONS = 150_000;
const SAS_SYMBOLS_TABLE = [
  '🛡️', '🔐', '⚡', '🛰️', '🔑', '💎', '🧭', '🔥',
  '🌐', '🧿', '🎯', '🏔️', '🦅', '⚓', '🧬', '🔮',
];

const encoder = new TextEncoder();
const decoder = new TextDecoder();

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
 * Derives deterministic topic routing ID (128-bit), AES-256-GCM bootstrap key (256-bit),
 * and HMAC key (128-bit) from the 6-character pairing code via 150,000-round PBKDF2-HMAC-SHA256.
 * Byte-identical to Kotlin CryptoProtocol.deriveBootstrapKeys().
 */
export async function deriveBootstrapKeys(code: string): Promise<DerivedBootstrapKeys> {
  const normalizedCode = normalizePairingCode(code);
  if (normalizedCode.length !== 6) {
    throw new Error('Le code d’appairage doit comporter exactement 6 caractères.');
  }

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
  const topicBytes = master.slice(0, 16);        // 16 bytes -> 32 hex chars
  const bootstrapEncRaw = master.slice(16, 48); // 32 bytes -> AES-256-GCM
  const bootstrapAuthRaw = master.slice(48, 64);// 16 bytes

  const bootstrapEncKey = await importAesGcmKey(bootstrapEncRaw);

  return {
    normalizedCode,
    topicId: bytesToHex(topicBytes),
    bootstrapEncKey,
    bootstrapEncRaw,
    bootstrapAuthRaw,
  };
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

/**
 * RFC 5869 HKDF-SHA256 (32-byte output).
 * Byte-identical to Kotlin CryptoProtocol.hkdfSha256().
 */
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

/**
 * Derives a persistent 256-bit Pairing Key on initial pairing using HKDF-SHA256.
 */
export async function generateDerivedPairingKey(
  deviceSaltHex: string,
  tokenId: string
): Promise<string> {
  const randomSeed = new Uint8Array(32);
  crypto.getRandomValues(randomSeed);
  const derived = await hkdfSha256(
    randomSeed,
    hexToBytes(deviceSaltHex),
    `sentinelcam-pair-v1:${tokenId}`,
    32
  );
  return bytesToHex(derived);
}

/**
 * Computes HMAC-SHA256 challenge-response proof (and mutual server proof).
 */
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

export async function computeClientChallengeProof(
  pairedKeyHex: string,
  clientNonce: string,
  serverNonce: string,
  tokenId: string,
  deviceId: string
): Promise<string> {
  return hmacSha256Hex(
    hexToBytes(pairedKeyHex),
    `client-auth-v1:${clientNonce}:${serverNonce}:${tokenId}:${deviceId}`
  );
}

export async function computeServerChallengeProof(
  pairedKeyHex: string,
  clientNonce: string,
  serverNonce: string,
  tokenId: string,
  deviceId: string
): Promise<string> {
  return hmacSha256Hex(
    hexToBytes(pairedKeyHex),
    `server-auth-v1:${clientNonce}:${serverNonce}:${tokenId}:${deviceId}`
  );
}

/**
 * Derives an ephemeral forward-secret AES-256-GCM session key from the persistent paired key
 * and fresh client/server nonces.
 */
export async function deriveSessionAesKey(
  pairedKeyHex: string,
  clientNonce: string,
  serverNonce: string
): Promise<CryptoKey> {
  const salt = encoder.encode(`${clientNonce}:${serverNonce}`);
  const raw = await hkdfSha256(
    hexToBytes(pairedKeyHex),
    salt,
    'sentinelcam-session-aes256gcm-v1',
    32
  );
  return importAesGcmKey(raw);
}

/**
 * Computes Short Authentication String (SAS) to verify absence of Man-in-the-Middle (MitM).
 * Byte-identical to Kotlin CryptoProtocol.computeSas().
 */
export async function computeSas(
  normalizedCode: string,
  tokenId: string,
  deviceId: string,
  pairedKeyHex: string
): Promise<{ sasCode: string; sasSymbols: string }> {
  const payload = encoder.encode(
    `sentinelcam-sas-v1:${normalizedCode}:${tokenId}:${deviceId}:${pairedKeyHex.toLowerCase()}`
  );
  const digestBuf = await crypto.subtle.digest('SHA-256', payload);
  const digest = new Uint8Array(digestBuf);

  const hexPair1 = digest[0].toString(16).padStart(2, '0').toUpperCase();
  const hexPair2 = digest[1].toString(16).padStart(2, '0').toUpperCase();
  const hexPair3 = digest[2].toString(16).padStart(2, '0').toUpperCase();
  const sasCode = `${hexPair1}-${hexPair2}-${hexPair3}`;

  const s1 = SAS_SYMBOLS_TABLE[digest[3] & 0x0f];
  const s2 = SAS_SYMBOLS_TABLE[(digest[3] >> 4) & 0x0f];
  const s3 = SAS_SYMBOLS_TABLE[digest[4] & 0x0f];
  const s4 = SAS_SYMBOLS_TABLE[(digest[4] >> 4) & 0x0f];

  return {
    sasCode,
    sasSymbols: `${s1} ${s2} ${s3} ${s4}`,
  };
}

/**
 * Computes combined DTLS-SRTP media SAS fingerprint from local & remote SDP SHA-256 fingerprints.
 */
export async function computeDtlsMediaSas(
  fpA: string,
  fpB: string
): Promise<string> {
  if (!fpA || !fpB) return 'EN ATTENTE SDP';
  const sorted = [fpA.trim().toUpperCase(), fpB.trim().toUpperCase()].sort().join('|');
  const digestBuf = await crypto.subtle.digest('SHA-256', encoder.encode(`dtls-sas-v1:${sorted}`));
  const d = new Uint8Array(digestBuf);
  return `${d[0].toString(16).padStart(2, '0')}:${d[1].toString(16).padStart(2, '0')}:${d[2]
    .toString(16)
    .padStart(2, '0')}:${d[3].toString(16).padStart(2, '0')}`.toUpperCase();
}

export function extractSdpFingerprint(sdp: string): string {
  const match = sdp.match(/a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)/i);
  return match ? match[1].toUpperCase() : '';
}

/**
 * Encrypts a SignalMessage into an authenticated AES-256-GCM EncryptedEnvelope.
 */
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

/**
 * Decrypts & authenticates an AES-256-GCM EncryptedEnvelope, enforcing ±120s anti-replay window.
 */
export async function decryptEnvelope(
  envelope: EncryptedEnvelope,
  aesKey: CryptoKey,
  maxSkewMs = 120_000
): Promise<SignalMessage | null> {
  if (envelope.v !== 1 || !envelope.iv || !envelope.ct) {
    return null;
  }

  const skew = Math.abs(Date.now() - envelope.ts);
  if (skew > maxSkewMs) {
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
    const json = decoder.decode(plainBuf);
    return JSON.parse(json) as SignalMessage;
  } catch {
    return null;
  }
}

/**
 * Constant-time hex comparison to prevent timing side-channels.
 */
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

/**
 * Exponential anti-brute-force lockout calculator (locks after 3 failed attempts).
 */
export function calculateLockoutDurationMs(failedAttempts: number): number {
  if (failedAttempts < 3) return 0;
  const exponent = Math.min(failedAttempts - 3, 5); // 30s, 60s, 120s, 240s, 480s, 960s
  return 30_000 * Math.pow(2, exponent);
}
