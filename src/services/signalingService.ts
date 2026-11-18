import mqtt, { type MqttClient } from 'mqtt';
import { decryptEnvelope, encryptEnvelope, randomHex } from '../crypto/cryptoEngine';
import type { EncryptedEnvelope, SignalMessage } from '../types/sentinel';

export const SIGNALING_BROKERS = [
  {
    name: 'EMQX Global WSS',
    url: 'wss://broker.emqx.io:8084/mqtt',
  },
  {
    name: 'HiveMQ Cloud Public WSS',
    url: 'wss://broker.hivemq.com:8884/mqtt',
  },
] as const;

export type SignalingRole = 'controller' | 'device';

export interface BrokerHealth {
  name: string;
  url: string;
  connected: boolean;
}

interface SignalingOptions {
  role: SignalingRole;
  topicId: string;
  bootstrapEncKey: CryptoKey;
  onMessage: (msg: SignalMessage, mode: 'boot' | 'sess') => void;
  onConnectionChange?: (connected: boolean, brokers: BrokerHealth[]) => void;
}

/**
 * Dual-Active Serverless WebRTC Signaling Engine over Secure MQTT WebSockets (WSS).
 * - Connects simultaneously to multiple global public MQTT WSS clusters for zero single-point-of-failure.
 * - Encrypts 100% of packets with AES-256-GCM (bootstrap key or ephemeral HKDF session key).
 * - Deduplicates incoming envelopes via sliding LRU window & enforces ±120s replay defense.
 * - Handles infinite automatic reconnection & immediate Wi-Fi <-> 4G/5G network handoff.
 */
export class EncryptedSignalingChannel {
  private readonly role: SignalingRole;
  private readonly topicId: string;
  private readonly senderId: string;
  private readonly bootstrapKey: CryptoKey;
  private sessionKey: CryptoKey | null = null;
  private readonly onMessage: (msg: SignalMessage, mode: 'boot' | 'sess') => void;
  private readonly onConnectionChange?: (connected: boolean, brokers: BrokerHealth[]) => void;

  private clients: Array<{ name: string; url: string; client: MqttClient; connected: boolean }> = [];
  private seenEnvelopeIds = new Set<string>();
  private seenQueue: string[] = [];
  private destroyed = false;

  private readonly inboundTopic: string;
  private readonly outboundTopic: string;
  private readonly presenceTopic: string;

  private readonly handleOnlineBound: () => void;

  constructor(options: SignalingOptions) {
    this.role = options.role;
    this.topicId = options.topicId;
    this.senderId = `${options.role}-${randomHex(6)}`;
    this.bootstrapKey = options.bootstrapEncKey;
    this.onMessage = options.onMessage;
    this.onConnectionChange = options.onConnectionChange;

    const base = `sentinelcam/v1/${this.topicId}`;
    this.presenceTopic = `${base}/presence`;
    if (this.role === 'controller') {
      this.inboundTopic = `${base}/d2c`;
      this.outboundTopic = `${base}/c2d`;
    } else {
      this.inboundTopic = `${base}/c2d`;
      this.outboundTopic = `${base}/d2c`;
    }

    this.handleOnlineBound = () => this.forceReconnectAll();
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.handleOnlineBound);
    }
  }

  public setSessionKey(key: CryptoKey | null): void {
    this.sessionKey = key;
  }

  public getSenderId(): string {
    return this.senderId;
  }

  public start(): void {
    if (this.destroyed) return;

    for (const broker of SIGNALING_BROKERS) {
      const clientId = `sc_${this.role.slice(0, 1)}_${randomHex(5)}`;
      const client = mqtt.connect(broker.url, {
        clientId,
        clean: true,
        keepalive: 25,
        reconnectPeriod: 2500,
        connectTimeout: 10_000,
        protocolVersion: 4,
      });

      const entry = {
        name: broker.name,
        url: broker.url,
        client,
        connected: false,
      };
      this.clients.push(entry);

      client.on('connect', () => {
        if (this.destroyed) return;
        entry.connected = true;
        const topics =
          this.role === 'controller'
            ? [this.inboundTopic, this.presenceTopic]
            : [this.inboundTopic];

        client.subscribe(topics, { qos: 1 }, () => {
          this.emitHealth();
        });
        this.emitHealth();
      });

      client.on('reconnect', () => {
        entry.connected = false;
        this.emitHealth();
      });

      client.on('close', () => {
        entry.connected = false;
        this.emitHealth();
      });

      client.on('offline', () => {
        entry.connected = false;
        this.emitHealth();
      });

      client.on('error', () => {
        // Handled gracefully by MQTT automatic reconnect loop
      });

      client.on('message', (_topic, payload) => {
        void this.handleRawPacket(payload);
      });
    }
  }

  public async send(
    msg: SignalMessage,
    mode: 'boot' | 'sess' = 'boot',
    options?: { toPresence?: boolean; retain?: boolean }
  ): Promise<boolean> {
    if (this.destroyed) return false;
    const key = mode === 'sess' ? this.sessionKey : this.bootstrapKey;
    if (!key) return false;

    const envelope = await encryptEnvelope(msg, key, mode, this.senderId);
    const serialized = JSON.stringify(envelope);
    const targetTopic = options?.toPresence ? this.presenceTopic : this.outboundTopic;

    let publishedCount = 0;
    for (const entry of this.clients) {
      if (entry.connected) {
        entry.client.publish(targetTopic, serialized, {
          qos: 1,
          retain: Boolean(options?.retain),
        });
        publishedCount++;
      }
    }
    return publishedCount > 0;
  }

  public isAnyConnected(): boolean {
    return this.clients.some((c) => c.connected);
  }

  public forceReconnectAll(): void {
    if (this.destroyed) return;
    for (const entry of this.clients) {
      if (!entry.connected) {
        try {
          entry.client.reconnect();
        } catch {
          // Ignore
        }
      }
    }
  }

  public destroy(): void {
    this.destroyed = true;
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', this.handleOnlineBound);
    }
    for (const entry of this.clients) {
      try {
        entry.client.end(true);
      } catch {
        // Ignore
      }
    }
    this.clients = [];
    this.seenEnvelopeIds.clear();
    this.seenQueue = [];
  }

  private emitHealth(): void {
    if (!this.onConnectionChange) return;
    const health: BrokerHealth[] = this.clients.map((c) => ({
      name: c.name,
      url: c.url,
      connected: c.connected,
    }));
    this.onConnectionChange(
      health.some((h) => h.connected),
      health
    );
  }

  private markSeen(id: string): boolean {
    if (!id || this.seenEnvelopeIds.has(id)) {
      return false;
    }
    this.seenEnvelopeIds.add(id);
    this.seenQueue.push(id);
    if (this.seenQueue.length > 400) {
      const oldest = this.seenQueue.shift();
      if (oldest) this.seenEnvelopeIds.delete(oldest);
    }
    return true;
  }

  private async handleRawPacket(rawBuffer: Uint8Array): Promise<void> {
    if (this.destroyed) return;
    let envelope: EncryptedEnvelope;
    try {
      const text = new TextDecoder().decode(rawBuffer);
      envelope = JSON.parse(text) as EncryptedEnvelope;
    } catch {
      return;
    }

    if (!envelope || envelope.sender === this.senderId) {
      return;
    }

    if (!this.markSeen(envelope.id)) {
      return;
    }

    const key = envelope.mode === 'sess' ? this.sessionKey : this.bootstrapKey;
    if (!key) return;

    const decrypted = await decryptEnvelope(envelope, key);
    if (!decrypted) return;

    this.onMessage(decrypted, envelope.mode);
  }
}
