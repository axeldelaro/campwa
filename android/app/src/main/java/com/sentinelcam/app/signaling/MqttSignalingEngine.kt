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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private object SharedOkHttpPool {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(45, TimeUnit.SECONDS) // Eco-friendly 45s keepalive ping
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Eco-Power MQTT 3.1.1 over WSS Engine + Instant ntfy.sh Push Wake Listener.
 * - Maintains a single ultra-lightweight Primary WSS socket (EMQX), failing over to HiveMQ ONLY if needed.
 * - Also listens on a zero-CPU SSE stream (`ntfy.sh`) for instant remote wake pushes.
 * - Never shuts down shared executors on stop -> 100% immune to shutdown crashes!
 */
class MqttSignalingEngine(
    context: Context,
    private val topicId: String,
    private val wakePushTopic: String,
    private val bootstrapEncKey: ByteArray,
    private val onMessageDecrypted: (payload: JSONObject, mode: String) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, brokerLabel: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderId = "device-${CryptoProtocol.randomHex(6)}"
    private val packetIdGen = AtomicInteger(1)

    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var latestSessionKey: ByteArray? = null

    @Volatile
    private var destroyed = false

    private val inboundTopic = "sentinelcam/v1/$topicId/c2d"
    private val outboundTopic = "sentinelcam/v1/$topicId/d2c"
    private val presenceTopic = "sentinelcam/v1/$topicId/presence"

    private inner class MqttWsConnection(val name: String, val url: String) {
        @Volatile var ws: WebSocket? = null
        @Volatile var mqttReady: Boolean = false
        @Volatile var connecting: Boolean = false
        @Volatile var lastActivityMs: Long = 0L

        private val streamBuffer = ByteArrayOutputStream()

        @Synchronized
        fun resetBuffer() {
            streamBuffer.reset()
        }

        @Synchronized
        fun appendAndExtractPackets(incoming: ByteArray): List<Pair<Int, ByteArray>> {
            streamBuffer.write(incoming)
            val all = streamBuffer.toByteArray()
            val out = ArrayList<Pair<Int, ByteArray>>()
            var cursor = 0

            while (cursor < all.size) {
                if (cursor + 2 > all.size) break
                val firstByte = all[cursor].toInt() and 0xFF

                var multiplier = 1
                var remainingLength = 0
                var lenCursor = cursor + 1
                var completeLen = false

                while (lenCursor < all.size && lenCursor <= cursor + 4) {
                    val encoded = all[lenCursor].toInt() and 0xFF
                    remainingLength += (encoded and 127) * multiplier
                    lenCursor++
                    if ((encoded and 128) == 0) {
                        completeLen = true
                        break
                    }
                    multiplier *= 128
                }

                if (!completeLen) break
                if (lenCursor + remainingLength > all.size) break

                val body = all.copyOfRange(lenCursor, lenCursor + remainingLength)
                out.add(Pair(firstByte, body))
                cursor = lenCursor + remainingLength
            }

            streamBuffer.reset()
            if (cursor < all.size) {
                streamBuffer.write(all, cursor, all.size - cursor)
            }
            return out
        }
    }

    private val primaryNode = MqttWsConnection("EMQX Eco", "wss://broker.emqx.io:8084/mqtt")
    private val failoverNode = MqttWsConnection("HiveMQ Eco", "wss://broker.hivemq.com:8884/mqtt")
    private var pushWs: WebSocket? = null

    private val seenIds = LinkedHashSet<String>()
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!destroyed) nudgeReconnect()
        }

        override fun onLost(network: Network) {
            if (!destroyed) emitHealth()
        }
    }

    fun registerSessionKey(id: String, keyBytes: ByteArray) {
        latestSessionKey = keyBytes
        sessionKeys[id] = keyBytes
        if (sessionKeys.size > 12) {
            val oldest = sessionKeys.keys().nextElement()
            if (oldest != null && oldest != id) sessionKeys.remove(oldest)
        }
    }

    fun start() {
        if (destroyed) return

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Throwable) {
        }

        connectPushWakeStream()

        // Eco-Standby Loop: checks every 6s locally (0 network bytes if socket healthy), sends 2-byte MQTT ping every 48s
        scope.launch {
            var tick = 0
            while (isActive && !destroyed) {
                try {
                    maintainEcoSockets()
                    if (tick > 0 && tick % 8 == 0) {
                        sendMqttPingActive()
                    }
                    tick++
                } catch (_: Throwable) {
                }
                delay(6_000L)
            }
        }
    }

    private fun connectPushWakeStream() {
        if (destroyed) return
        try {
            val req = Request.Builder()
                .url("wss://ntfy.sh/$wakePushTopic/ws")
                .build()
            pushWs = SharedOkHttpPool.client.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!destroyed && text.contains("WAKE_INVITE")) {
                        nudgeReconnect()
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    fun nudgeReconnect() {
        if (destroyed) return
        scope.launch {
            try {
                maintainEcoSockets()
                sendMqttPingActive()
            } catch (_: Throwable) {
            }
        }
    }

    private fun maintainEcoSockets() {
        if (destroyed) return
        val now = System.currentTimeMillis()

        // Reset primary if silent > 95s
        if (primaryNode.mqttReady && primaryNode.lastActivityMs > 0L && (now - primaryNode.lastActivityMs) > 95_000L) {
            closeNodeQuietly(primaryNode)
        }

        if (!primaryNode.mqttReady) {
            if (!primaryNode.connecting || (now - primaryNode.lastActivityMs) > 10_000L) {
                connectNode(primaryNode)
            }
            // Activate failover ONLY while primary is down (Eco-battery!)
            if (!failoverNode.mqttReady && (!failoverNode.connecting || (now - failoverNode.lastActivityMs) > 10_000L)) {
                connectNode(failoverNode)
            }
        } else {
            // Primary is healthy! Close backup if it was open to save radio battery
            if (failoverNode.ws != null) {
                closeNodeQuietly(failoverNode)
            }
        }
    }

    private fun connectNode(node: MqttWsConnection) {
        if (destroyed) return
        closeNodeQuietly(node)
        node.connecting = true
        node.lastActivityMs = System.currentTimeMillis()
        node.resetBuffer()

        try {
            val req = Request.Builder()
                .url(node.url)
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()

            val socket = SharedOkHttpPool.client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (destroyed) {
                        try { webSocket.close(1000, null) } catch (_: Throwable) {}
                        return
                    }
                    node.lastActivityMs = System.currentTimeMillis()
                    val clientId = "sc_d_${CryptoProtocol.randomHex(5)}"
                    val connectPacket = buildMqttConnectPacket(clientId, keepAliveSec = 60)
                    webSocket.send(connectPacket.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (destroyed) return
                    node.lastActivityMs = System.currentTimeMillis()
                    try {
                        val packets = node.appendAndExtractPackets(bytes.toByteArray())
                        for ((headerByte, body) in packets) {
                            handleMqttControlPacket(node, webSocket, headerByte, body)
                        }
                    } catch (_: Throwable) {
                        closeNodeQuietly(node)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    node.mqttReady = false
                    node.connecting = false
                    try { webSocket.close(1000, null) } catch (_: Throwable) {}
                    if (!destroyed) emitHealth()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    node.mqttReady = false
                    node.connecting = false
                    if (!destroyed) emitHealth()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    node.mqttReady = false
                    node.connecting = false
                    if (!destroyed) emitHealth()
                }
            })

            node.ws = socket
        } catch (_: Throwable) {
            node.mqttReady = false
            node.connecting = false
            if (!destroyed) emitHealth()
        }
    }

    private fun handleMqttControlPacket(
        node: MqttWsConnection,
        webSocket: WebSocket,
        headerByte: Int,
        body: ByteArray
    ) {
        if (destroyed) return
        when (headerByte and 0xF0) {
            0x20 -> {
                if (body.size >= 2 && (body[1].toInt() and 0xFF) == 0) {
                    node.mqttReady = true
                    node.connecting = false
                    val subPacket = buildMqttSubscribePacket(nextPacketId(), inboundTopic, qos = 1)
                    webSocket.send(subPacket.toByteString())
                    emitHealth()
                } else {
                    closeNodeQuietly(node)
                }
            }

            0x30 -> {
                if (body.size < 2) return
                val qos = (headerByte ushr 1) and 0x03
                val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                var idx = 2 + topicLen
                if (idx > body.size) return

                if (qos > 0) {
                    if (idx + 2 > body.size) return
                    val pktHi = body[idx]
                    val pktLo = body[idx + 1]
                    idx += 2
                    if (qos == 1) {
                        webSocket.send(byteArrayOf(0x40.toByte(), 0x02.toByte(), pktHi, pktLo).toByteString())
                    }
                }

                val payload = body.copyOfRange(idx, body.size)
                scope.launch {
                    handleEncryptedPayload(payload)
                }
            }

            0x90 -> emitHealth()
            0xD0 -> node.lastActivityMs = System.currentTimeMillis()
        }
    }

    private fun sendMqttPingActive() {
        val pingBytes = byteArrayOf(0xC0.toByte(), 0x00.toByte()).toByteString()
        for (node in listOf(primaryNode, failoverNode)) {
            if (node.mqttReady) {
                try { node.ws?.send(pingBytes) } catch (_: Throwable) {}
            }
        }
    }

    fun send(payload: JSONObject, mode: String = "boot", toPresence: Boolean = false) {
        if (destroyed) return
        val key = if (mode == "sess") latestSessionKey else bootstrapEncKey
        if (key == null) return

        scope.launch {
            try {
                val rawEnvelope = CryptoProtocol.encryptEnvelope(payload, key, mode, senderId)
                val targetTopic = if (toPresence) presenceTopic else outboundTopic
                val msgBytes = rawEnvelope.toByteArray(Charsets.UTF_8)

                // When replying to a controller, publish on active socket(s) immediately
                for (node in listOf(primaryNode, failoverNode)) {
                    val sock = node.ws
                    if (node.mqttReady && sock != null) {
                        try {
                            val pkt = buildMqttPublishPacket(targetTopic, msgBytes, 1, nextPacketId())
                            sock.send(pkt.toByteString())
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleEncryptedPayload(bytes: ByteArray) {
        if (destroyed || bytes.isEmpty()) return
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Throwable) {
            return
        }

        if (envelope.optString("sender") == senderId) return
        val id = envelope.optString("id", "")
        if (id.isEmpty() || !markSeen(id)) return

        val mode = envelope.optString("mode", "boot")
        if (mode == "boot") {
            val plain = CryptoProtocol.decryptEnvelope(envelope, bootstrapEncKey) ?: return
            if (!destroyed) onMessageDecrypted(plain, mode)
        } else {
            val primary = latestSessionKey
            if (primary != null) {
                val plain = CryptoProtocol.decryptEnvelope(envelope, primary)
                if (plain != null) {
                    if (!destroyed) onMessageDecrypted(plain, mode)
                    return
                }
            }
            for (candidateKey in sessionKeys.values) {
                val plain = CryptoProtocol.decryptEnvelope(envelope, candidateKey)
                if (plain != null) {
                    latestSessionKey = candidateKey
                    if (!destroyed) onMessageDecrypted(plain, mode)
                    return
                }
            }
        }
    }

    @Synchronized
    private fun markSeen(id: String): Boolean {
        if (seenIds.contains(id)) return false
        seenIds.add(id)
        if (seenIds.size > 500) {
            val first = seenIds.iterator().next()
            seenIds.remove(first)
        }
        return true
    }

    private fun closeNodeQuietly(node: MqttWsConnection) {
        node.mqttReady = false
        node.connecting = false
        val old = node.ws
        node.ws = null
        if (old != null) {
            try { old.close(1000, null) } catch (_: Throwable) {}
        }
    }

    private fun emitHealth() {
        if (destroyed) return
        val activeNames = listOf(primaryNode, failoverNode).filter { it.mqttReady && it.ws != null }.map { it.name }
        val isAnyConnected = activeNames.isNotEmpty()
        val label = if (isAnyConnected) activeNames.joinToString(" + ") else "Veille / Reconnexion..."
        onConnectionStateChanged(isAnyConnected, label)
    }

    fun destroy() {
        destroyed = true
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        scope.cancel()
        closeNodeQuietly(primaryNode)
        closeNodeQuietly(failoverNode)
        try { pushWs?.close(1000, null) } catch (_: Throwable) {}
        pushWs = null
    }

    private fun nextPacketId(): Int {
        return packetIdGen.getAndUpdate { if (it >= 65530) 1 else it + 1 }
    }

    private fun writeUtf8String(out: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun encodeRemainingLength(out: ByteArrayOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun buildMqttConnectPacket(clientId: String, keepAliveSec: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeUtf8String(body, "MQTT")
        body.write(0x04)
        body.write(0x02)
        body.write((keepAliveSec ushr 8) and 0xFF)
        body.write(keepAliveSec and 0xFF)
        writeUtf8String(body, clientId)

        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream(bodyBytes.size + 5)
        packet.write(0x10)
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }

    private fun buildMqttSubscribePacket(packetId: Int, topic: String, qos: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeUtf8String(body, topic)
        body.write(qos and 0x03)

        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream(bodyBytes.size + 5)
        packet.write(0x82)
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }

    private fun buildMqttPublishPacket(topic: String, payload: ByteArray, qos: Int, packetId: Int): ByteArray {
        val body = ByteArrayOutputStream(topic.length + payload.size + 8)
        writeUtf8String(body, topic)
        if (qos > 0) {
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
        }
        body.write(payload)

        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream(bodyBytes.size + 5)
        val flags = (qos and 0x03) shl 1
        packet.write(0x30 or flags)
        encodeRemainingLength(packet, bodyBytes.size)
        packet.write(bodyBytes)
        return packet.toByteArray()
    }
}
