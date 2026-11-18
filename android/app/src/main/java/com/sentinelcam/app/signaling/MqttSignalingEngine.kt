package com.sentinelcam.app.signaling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sentinelcam.app.crypto.CryptoProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArrayList

class MqttSignalingEngine(
    context: Context,
    private val topicId: String,
    private val bootstrapEncKey: ByteArray,
    private val onMessageDecrypted: (payload: JSONObject, mode: String) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, brokerLabel: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderId = "device-${CryptoProtocol.randomHex(6)}"

    @Volatile
    private var sessionEncKey: ByteArray? = null

    @Volatile
    private var destroyed = false

    private val inboundTopic = "sentinelcam/v1/$topicId/c2d"
    private val outboundTopic = "sentinelcam/v1/$topicId/d2c"
    private val presenceTopic = "sentinelcam/v1/$topicId/presence"

    private data class BrokerNode(
        val name: String,
        val uri: String,
        var client: MqttAsyncClient? = null,
        @Volatile var connected: Boolean = false,
        @Volatile var connecting: Boolean = false
    )

    private val brokers = CopyOnWriteArrayList(
        listOf(
            BrokerNode("EMQX WSS", "wss://broker.emqx.io:8084/mqtt"),
            BrokerNode("HiveMQ WSS", "wss://broker.hivemq.com:8884/mqtt")
        )
    )

    private val seenIds = LinkedHashSet<String>()
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!destroyed) {
                scope.launch { ensureAllConnected() }
            }
        }

        override fun onLost(network: Network) {
            emitHealth()
        }
    }

    fun setSessionKey(keyBytes: ByteArray?) {
        sessionEncKey = keyBytes
    }

    fun start() {
        if (destroyed) return

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // Fallback to watchdog loop
        }

        // Infinite resilient watchdog ensuring reconnection across tunnels, Airplane mode, Wi-Fi <-> 5G
        scope.launch {
            while (isActive && !destroyed) {
                ensureAllConnected()
                delay(5_000L)
            }
        }
    }

    private fun ensureAllConnected() {
        for (node in brokers) {
            if (destroyed) return
            val existing = node.client
            if (existing != null && existing.isConnected) {
                node.connected = true
                continue
            }
            if (node.connecting) continue
            connectBrokerNode(node)
        }
    }

    private fun connectBrokerNode(node: BrokerNode) {
        node.connecting = true
        try {
            try {
                node.client?.close(true)
            } catch (_: Exception) {
            }

            val clientId = "sc_d_${CryptoProtocol.randomHex(5)}"
            val client = MqttAsyncClient(node.uri, clientId, MemoryPersistence())
            node.client = client

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    node.connected = true
                    node.connecting = false
                    subscribeInbound(client)
                    emitHealth()
                }

                override fun connectionLost(cause: Throwable?) {
                    node.connected = false
                    node.connecting = false
                    emitHealth()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val bytes = message?.payload ?: return
                    scope.launch {
                        handleRawPacket(bytes)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                keepAliveInterval = 25
                connectionTimeout = 10
                maxReconnectDelay = 12_000
            }

            client.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    node.connected = true
                    node.connecting = false
                    subscribeInbound(client)
                    emitHealth()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    node.connected = false
                    node.connecting = false
                    emitHealth()
                }
            })
        } catch (_: Exception) {
            node.connected = false
            node.connecting = false
            emitHealth()
        }
    }

    private fun subscribeInbound(client: MqttAsyncClient) {
        try {
            if (client.isConnected) {
                client.subscribe(inboundTopic, 1)
            }
        } catch (_: Exception) {
        }
    }

    fun send(payload: JSONObject, mode: String = "boot", toPresence: Boolean = false) {
        if (destroyed) return
        val key = if (mode == "sess") sessionEncKey else bootstrapEncKey
        if (key == null) return

        scope.launch {
            try {
                val rawEnvelope = CryptoProtocol.encryptEnvelope(payload, key, mode, senderId)
                val targetTopic = if (toPresence) presenceTopic else outboundTopic
                val msgBytes = rawEnvelope.toByteArray(Charsets.UTF_8)

                for (node in brokers) {
                    val c = node.client
                    if (c != null && c.isConnected) {
                        try {
                            val mqttMsg = MqttMessage(msgBytes).apply {
                                qos = 1
                                isRetained = false
                            }
                            c.publish(targetTopic, mqttMsg)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleRawPacket(bytes: ByteArray) {
        if (destroyed) return
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }

        if (envelope.optString("sender") == senderId) return
        val id = envelope.optString("id", "")
        if (id.isEmpty() || !markSeen(id)) return

        val mode = envelope.optString("mode", "boot")
        val key = if (mode == "sess") sessionEncKey else bootstrapEncKey
        if (key == null) return

        val plain = CryptoProtocol.decryptEnvelope(envelope, key) ?: return
        onMessageDecrypted(plain, mode)
    }

    @Synchronized
    private fun markSeen(id: String): Boolean {
        if (seenIds.contains(id)) return false
        seenIds.add(id)
        if (seenIds.size > 400) {
            val first = seenIds.iterator().next()
            seenIds.remove(first)
        }
        return true
    }

    private fun emitHealth() {
        val activeNames = brokers.filter { it.connected && it.client?.isConnected == true }.map { it.name }
        val isAnyConnected = activeNames.isNotEmpty()
        val label = if (isAnyConnected) activeNames.joinToString(" + ") else "Reconnexion WSS..."
        onConnectionStateChanged(isAnyConnected, label)
    }

    fun destroy() {
        destroyed = true
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        scope.cancel()
        for (node in brokers) {
            try {
                node.client?.disconnectForcibly(500, 500)
                node.client?.close(true)
            } catch (_: Exception) {
            }
        }
        brokers.clear()
    }
}
