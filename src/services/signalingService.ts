import mqtt, { type MqttClient } from 'mqtt';
import { decryptEnvelope, encryptEnvelope, randomHex } from '../crypto/cryptoEngine';
import type { EncryptedEnvelope, SignalMessage } from '../types/sentinel';

export const SIGNALING_BROKERS = [
  {
    name: 'EMQX Global WSS',
    url: 'wss://broker.emqx.io:8084/mqtt',
  },
  {
    name: 'HiveMQ Public WSS',
    url: 'wss://broker.hivemq.com:8884/mqtt',
  },
] as const;

export type SignalingRole = 'controller' | 'device';

export interface BrokerHealth {
  name: string;
  url: string;
  connected: boolean;
}

interface PooledBrokerNode {
  name: string;
  url: string;
  client: MqttClient;
  connected: boolean;
  listeners: Set<(topic: string, payload: Uint8Array) => void>;
  statusListeners: Set<() => void>;
}

let sharedPool: PooledBrokerNode[] | null = null;

/**
 * Pre-warms TLS WebSocket connections as soon as the web app loads so that typing
 * a 6-character code connects in ~100ms with 0ms TCP/TLS handshake latency!
 */
export function warmUpSignalingPool(): PooledBrokerNode[] {
  if (sharedPool) return sharedPool;

  sharedPool = SIGNALING_BROKERS.map((broker) => {
    const client = mqtt.connect(broker.url, {
      clientId: `sc_w_${randomHex(6)}`,
      clean: true,
      keepalive: 25,
      reconnectPeriod: 1500,
      connectTimeout: 7000,
      protocolVersion: 4,
    });

    const node: PooledBrokerNode = {
      name: broker.name,
      url: broker.url,
      client,
      connected: false,
      listeners: new Set(),
      statusListeners: new Set(),
    };

    const notifyStatus = () => {
      node.statusListeners.forEach((fn) => fn());
    };

    client.on('connect', () => {
      node.connected = true;
      notifyStatus();
    });

    client.on('reconnect', () => {
      node.connected = false;
      notifyStatus();
    });

    client.on('close', () => {
      node.connected = false;
      notifyStatus();
    });

    client.on('offline', () => {
      node.connected = false;
      notifyStatus();
    });

    client.on('error', () => {
      // Handled automatically by MQTT reconnect loop
    });

    client.on('message', (topic, payload) => {
      node.listeners.forEach((fn) => fn(topic, payload));
    });

    return node;
  });

  if (typeof window !== 'undefined') {
    const nudgePoolReconnect = () => {
      sharedPool?.forEach((n) => {
        if (!n.connected) {
          try {
            n.client.reconnect();
          } catch {
            // Ignore
          }
        }
      });
    };
    window.addEventListener('online', nudgePoolReconnect);
    window.addEventListener('pageshow', nudgePoolReconnect);
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
          nudgePoolReconnect();
        }
      });
    }
  }

  return sharedPool;
}

async function waitForConnectedBrokers(timeoutMs = 3500): Promise<PooledBrokerNode[]> {
  const pool = warmUpSignalingPool();
  const ready = pool.filter((n) => n.connected);
  if (ready.length > 0) return ready;

  return new Promise((resolve) => {
    let settled = false;
    const cleanup = () => {
      pool.forEach((n) => n.statusListeners.delete(onStatus));
    };
    const finish = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(pool.filter((n) => n.connected));
    };
    const onStatus = () => {
      if (pool.some((n) => n.connected)) {
        // Give 220ms for second broker to also finish TLS handshake
        window.setTimeout(finish, 220);
      }
    };
    pool.forEach((n) => n.statusListeners.add(onStatus));
    window.setTimeout(finish, timeoutMs);
  });
}

/**
 * Publishes an end-to-end encrypted JSON record with MQTT retain=true across ALL public brokers
 * in our pool (100% free, zero paid backend). Replicates automatically if a second broker connects.
 */
export async function publishRetainedRecord(topic: string, payload: string): Promise<boolean> {
  const pool = warmUpSignalingPool();
  const connected = await waitForConnectedBrokers(4000);
  if (connected.length === 0) return false;

  const publishedUrls = new Set<string>();
  const doPublish = (node: PooledBrokerNode) => {
    if (!node.connected || publishedUrls.has(node.url)) return;
    publishedUrls.add(node.url);
    try {
      node.client.publish(topic, payload, { qos: 1, retain: true });
    } catch {
      // Ignore transient publish failure
    }
  };

  connected.forEach(doPublish);

  // Replicate to any remaining broker if it comes online within 6s
  const pendingNodes = pool.filter((n) => !publishedUrls.has(n.url));
  if (pendingNodes.length > 0) {
    const onLate = () => {
      pendingNodes.forEach(doPublish);
    };
    pendingNodes.forEach((n) => n.statusListeners.add(onLate));
    window.setTimeout(() => {
      pendingNodes.forEach((n) => n.statusListeners.delete(onLate));
    }, 6000);
  }

  return publishedUrls.size > 0;
}

/**
 * Reads all retained payloads for a topic across all connected public MQTT brokers.
 * Resolves quickly once brokers respond or after timeoutMs if no retained record exists.
 */
export async function readRetainedRecord(topic: string, timeoutMs = 2000): Promise<string[]> {
  const connected = await waitForConnectedBrokers(3500);
  if (connected.length === 0) return [];

  const textDecoder = new TextDecoder();
  const payloads: string[] = [];
  const seen = new Set<string>();

  return new Promise((resolve) => {
    let settled = false;
    let settleEarlyTimer: number | null = null;
    const listeners = new Map<PooledBrokerNode, (t: string, p: Uint8Array) => void>();

    const finish = () => {
      if (settled) return;
      settled = true;
      if (settleEarlyTimer !== null) window.clearTimeout(settleEarlyTimer);
      listeners.forEach((fn, node) => {
        node.listeners.delete(fn);
        if (node.connected) {
          try {
            node.client.unsubscribe(topic);
          } catch {
            // Ignore
          }
        }
      });
      resolve(payloads);
    };

    connected.forEach((node) => {
      const listener = (msgTopic: string, buf: Uint8Array) => {
        if (msgTopic !== topic || buf.length === 0) return;
        const txt = textDecoder.decode(buf).trim();
        if (txt && !seen.has(txt)) {
          seen.add(txt);
          payloads.push(txt);
        }
        // Once we receive at least one retained record, wait briefly for a potentially newer copy from broker #2
        if (settleEarlyTimer === null) {
          settleEarlyTimer = window.setTimeout(finish, 420);
        }
      };
      listeners.set(node, listener);
      node.listeners.add(listener);
      try {
        node.client.subscribe(topic, { qos: 1 });
      } catch {
        // Ignore
      }
    });

    window.setTimeout(finish, timeoutMs);
  });
}

interface SignalingOptions {
  role: SignalingRole;
  topicId: string;
  bootstrapEncKey: CryptoKey;
  onMessage: (msg: SignalMessage, mode: 'boot' | 'sess') => void;
  onSubscribedReady?: () => void;
  onConnectionChange?: (connected: boolean, brokers: BrokerHealth[]) => void;
}

export class EncryptedSignalingChannel {
  private readonly role: SignalingRole;
  private readonly topicId: string;
  private readonly senderId: string;
  private readonly bootstrapKey: CryptoKey;
  private sessionKey: CryptoKey | null = null;
  private readonly onMessage: (msg: SignalMessage, mode: 'boot' | 'sess') => void;
  private readonly onSubscribedReady?: () => void;
  private readonly onConnectionChange?: (connected: boolean, brokers: BrokerHealth[]) => void;

  private poolNodes: PooledBrokerNode[] = [];
  private subscribedUrls = new Set<string>();
  private seenEnvelopeIds = new Set<string>();
  private seenQueue: string[] = [];
  private destroyed = false;

  private readonly inboundTopic: string;
  private readonly outboundTopic: string;
  private readonly presenceTopic: string;
  private readonly subTopics: string[];

  private msgListenerBound: ((topic: string, payload: Uint8Array) => void) | null = null;
  private statusListenerBound: (() => void) | null = null;

  constructor(options: SignalingOptions) {
    this.role = options.role;
    this.topicId = options.topicId;
    this.senderId = `${options.role}-${randomHex(6)}`;
    this.bootstrapKey = options.bootstrapEncKey;
    this.onMessage = options.onMessage;
    this.onSubscribedReady = options.onSubscribedReady;
    this.onConnectionChange = options.onConnectionChange;

    const base = `sentinelcam/v1/${this.topicId}`;
    this.presenceTopic = `${base}/presence`;
    if (this.role === 'controller') {
      this.inboundTopic = `${base}/d2c`;
      this.outboundTopic = `${base}/c2d`;
      this.subTopics = [this.inboundTopic, this.presenceTopic];
    } else {
      this.inboundTopic = `${base}/c2d`;
      this.outboundTopic = `${base}/d2c`;
      this.subTopics = [this.inboundTopic];
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
    this.poolNodes = warmUpSignalingPool();

    this.msgListenerBound = (topic, payload) => {
      if (this.subTopics.includes(topic)) {
        void this.handleRawPacket(payload);
      }
    };

    this.statusListenerBound = () => {
      if (this.destroyed) return;
      this.ensureSubscriptions();
      this.emitHealth();
    };

    for (const node of this.poolNodes) {
      node.listeners.add(this.msgListenerBound);
      node.statusListeners.add(this.statusListenerBound);
    }

    this.ensureSubscriptions();
    this.emitHealth();
  }

  private ensureSubscriptions(): void {
    for (const node of this.poolNodes) {
      if (!node.connected) {
        this.subscribedUrls.delete(node.url);
        continue;
      }
      if (this.subscribedUrls.has(node.url)) continue;

      node.client.subscribe(this.subTopics, { qos: 1 }, (err) => {
        if (this.destroyed || err) return;
        const wasEmpty = this.subscribedUrls.size === 0;
        this.subscribedUrls.add(node.url);
        this.emitHealth();
        if (wasEmpty && this.onSubscribedReady) {
          this.onSubscribedReady();
        }
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
    for (const node of this.poolNodes) {
      if (node.connected) {
        node.client.publish(targetTopic, serialized, {
          qos: 1,
          retain: Boolean(options?.retain),
        });
        publishedCount++;
      }
    }
    return publishedCount > 0;
  }

  public isSubscribedAndReady(): boolean {
    return this.subscribedUrls.size > 0;
  }

  public destroy(): void {
    this.destroyed = true;
    for (const node of this.poolNodes) {
      if (this.msgListenerBound) node.listeners.delete(this.msgListenerBound);
      if (this.statusListenerBound) node.statusListeners.delete(this.statusListenerBound);
      if (node.connected) {
        try {
          node.client.unsubscribe(this.subTopics);
        } catch {
          // Ignore
        }
      }
    }
    this.subscribedUrls.clear();
    this.seenEnvelopeIds.clear();
    this.seenQueue = [];
  }

  private emitHealth(): void {
    if (!this.onConnectionChange) return;
    const health: BrokerHealth[] = this.poolNodes.map((c) => ({
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
    if (this.seenQueue.length > 500) {
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
