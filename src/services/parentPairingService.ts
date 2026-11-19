import {
  deriveBootstrapKeys,
  deriveFamilyDiscoveryKeys,
  generateSecurePairingCode,
  normalizeFamilyKey,
} from '../crypto/cryptoEngine';
import type { FamilyAnnounceMsg, PairConfirmedMsg, PairHelloMsg, SignalMessage } from '../types/sentinel';
import { getOrCreateControllerTokenId, savePairedDevice } from './deviceVault';
import { EncryptedSignalingChannel } from './signalingService';

/**
 * Serverless Parent-Initiated Pairing Session:
 * 1. Parent generates a one-time 6-char code on the Website.
 * 2. Android app enters that code and sends PAIR_HELLO.
 * 3. Website binds the phone with (familyKey, permanentDeviceCode, optional adminPin).
 * 4. Phone confirms PAIR_CONFIRMED and becomes permanently Parent-Managed!
 */
export class ParentPairingSession {
  private channel: EncryptedSignalingChannel | null = null;
  private readonly tempCode: string;
  private readonly assignedDeviceCode: string;
  private readonly familyKey: string;
  private readonly adminPin: string;
  private completed = false;

  constructor(options: {
    familyKey: string;
    adminPin?: string;
    onPaired: (info: { deviceId: string; deviceName: string; deviceCode: string }) => void;
    onStatus?: (msg: string) => void;
  }) {
    this.tempCode = generateSecurePairingCode();
    this.assignedDeviceCode = generateSecurePairingCode();
    this.familyKey = normalizeFamilyKey(options.familyKey);
    this.adminPin = (options.adminPin || '').trim();
    this.onPaired = options.onPaired;
    this.onStatus = options.onStatus;
  }

  private readonly onPaired: (info: { deviceId: string; deviceName: string; deviceCode: string }) => void;
  private readonly onStatus?: (msg: string) => void;

  public getCode(): string {
    return this.tempCode;
  }

  public async start(): Promise<void> {
    const keys = await deriveBootstrapKeys(this.tempCode);
    this.channel = new EncryptedSignalingChannel({
      role: 'controller',
      topicId: keys.topicId,
      bootstrapEncKey: keys.bootstrapEncKey,
      onSubscribedReady: () => {
        this.onStatus?.('En attente du smartphone…');
      },
      onMessage: (msg: SignalMessage) => {
        if (this.completed) return;
        if (msg.type === 'PAIR_HELLO') {
          void this.handleHello(msg);
        } else if (msg.type === 'PAIR_CONFIRMED') {
          this.handleConfirmed(msg);
        }
      },
    });
    this.channel.start();
  }

  private async handleHello(msg: PairHelloMsg): Promise<void> {
    this.onStatus?.(`Association de ${msg.deviceName} en cours…`);
    await this.channel?.send(
      {
        type: 'PAIR_BIND',
        familyKey: this.familyKey,
        deviceCode: this.assignedDeviceCode,
        adminPin: this.adminPin.length >= 4 ? this.adminPin : undefined,
        ts: Date.now(),
      },
      'boot'
    );
  }

  private handleConfirmed(msg: PairConfirmedMsg): void {
    if (this.completed) return;
    this.completed = true;
    savePairedDevice({
      pairingCode: msg.deviceCode,
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      tokenId: getOrCreateControllerTokenId(),
      sasCode: '',
      sasSymbols: '',
      accessRole: 'owner',
      accessName: 'Parent',
      pairedAt: Date.now(),
      lastConnectedAt: Date.now(),
    });
    this.stop();
    this.onPaired({
      deviceId: msg.deviceId,
      deviceName: msg.deviceName,
      deviceCode: msg.deviceCode,
    });
  }

  public stop(): void {
    this.channel?.destroy();
    this.channel = null;
  }
}

/**
 * Zero-Loss Serverless Family Discovery:
 * Given ONLY the Parent's 12-char Family Key on ANY new browser/computer,
 * pings the encrypted Family Discovery topic and restores all paired child phones automatically!
 */
export async function discoverFamilyDevices(
  rawFamilyKey: string,
  onDeviceFound: (dev: FamilyAnnounceMsg) => void,
  timeoutMs = 5500
): Promise<FamilyAnnounceMsg[]> {
  const keys = await deriveFamilyDiscoveryKeys(rawFamilyKey);
  const foundMap = new Map<string, FamilyAnnounceMsg>();

  return new Promise((resolve) => {
    let pingTimer: number | null = null;
    const ch = new EncryptedSignalingChannel({
      role: 'controller',
      topicId: keys.topicId,
      bootstrapEncKey: keys.bootstrapEncKey,
      onSubscribedReady: () => {
        void ch.send({ type: 'FAMILY_DISCOVER', ts: Date.now() }, 'boot');
        if (pingTimer === null) {
          pingTimer = window.setInterval(() => {
            void ch.send({ type: 'FAMILY_DISCOVER', ts: Date.now() }, 'boot');
          }, 1500);
        }
      },
      onMessage: (msg: SignalMessage) => {
        if (msg.type === 'FAMILY_ANNOUNCE') {
          if (!foundMap.has(msg.deviceCode)) {
            foundMap.set(msg.deviceCode, msg);
            savePairedDevice({
              pairingCode: msg.deviceCode,
              deviceId: msg.deviceId,
              deviceName: msg.deviceName,
              tokenId: getOrCreateControllerTokenId(),
              sasCode: '',
              sasSymbols: '',
              accessRole: 'owner',
              accessName: 'Parent',
              pairedAt: Date.now(),
              lastConnectedAt: Date.now(),
            });
            onDeviceFound(msg);
          }
        }
      },
    });

    ch.start();

    window.setTimeout(() => {
      if (pingTimer !== null) window.clearInterval(pingTimer);
      ch.destroy();
      resolve(Array.from(foundMap.values()));
    }, timeoutMs);
  });
}
