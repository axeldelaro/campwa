package com.sentinelcam.app.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.storage.PortalPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-Server Instant Push Alert Engine (v26.2):
 * 1. Child -> Parent Push: Whenever a security alert, "+15 min" time request, rule change request,
 *    or unpair request happens on the child device, pushes an instant UTF-8 notification to ntfy.sh
 *    on the deterministic private topic(s) for the child pairing code and Parent family key.
 * 2. Parent Phone Native Subscriber: When the Parent activates "Notifications Parent" on their own
 *    smartphone, listens in the background via a single lightweight WebSocket and triggers native
 *    Android high-priority notifications (sound + vibration) without opening the website!
 */
object ParentAlertPushHelper {

    private const val PARENT_ALERT_CHANNEL_ID = "sentinelcam_parent_alerts_v1"
    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SentinelParentPush").apply { isDaemon = true }
    }
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val lastSentByKey = ConcurrentHashMap<String, Long>()
    private val notifIdGen = AtomicInteger(4000)

    @Volatile
    private var subscriberWs: WebSocket? = null

    @Volatile
    private var subscribedTopicsKey: String = ""

    @Volatile
    var subscriberConnected: Boolean = false
        private set

    private fun sha256Hex(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun deriveChildAlertTopic(pairingCode: String): String {
        val clean = CryptoProtocol.normalizePairingCode(pairingCode)
        if (clean.length != 6) return ""
        val hash = sha256Hex("sentinel-parent-alert-v26:$clean")
        return "sc_alert_${hash.substring(0, 18)}"
    }

    fun deriveFamilyAlertTopic(familyKey: String): String {
        val clean = familyKey.trim().uppercase()
        if (clean.length != 12) return ""
        val hash = sha256Hex("sentinel-family-alert-v26:$clean")
        return "sc_fam_${hash.substring(0, 18)}"
    }

    fun dispatchAlertAsync(
        prefs: PortalPreferences,
        kind: String,
        title: String,
        detail: String
    ) {
        val now = System.currentTimeMillis()
        val dedupKey = "$kind:$title"
        val cooldownMs = if (kind == "request" || kind == "admin") 2_500L else 20_000L
        val prev = lastSentByKey[dedupKey] ?: 0L
        if (now - prev < cooldownMs) return
        lastSentByKey[dedupKey] = now

        val topics = LinkedHashSet<String>()
        val childTopic = deriveChildAlertTopic(prefs.pairingCode)
        if (childTopic.isNotEmpty()) topics.add(childTopic)
        val famTopic = deriveFamilyAlertTopic(prefs.parentFamilyKey)
        if (famTopic.isNotEmpty()) topics.add(famTopic)
        if (topics.isEmpty()) return

        val devName = prefs.deviceName
        val priority = when (kind) {
            "intruder", "sim", "speed", "keyword" -> 5
            "request", "admin", "geofence", "bypass" -> 4
            else -> 3
        }
        val tag = when (kind) {
            "request" -> "bell"
            "intruder", "bypass" -> "rotating_light"
            "speed" -> "car"
            "geofence" -> "round_pushpin"
            "keyword" -> "speech_balloon"
            "battery" -> "battery"
            else -> "shield"
        }

        pushExecutor.execute {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            for (topic in topics) {
                try {
                    val bodyJson = JSONObject().apply {
                        put("topic", topic)
                        put("title", "$devName • $title")
                        put("message", detail)
                        put("priority", priority)
                        put("tags", JSONArray().put(tag))
                        put("click", "https://axeldelaro.github.io/campwa/")
                    }
                    val req = Request.Builder()
                        .url("https://ntfy.sh")
                        .post(bodyJson.toString().toRequestBody(mediaType))
                        .build()
                    httpClient.newCall(req).execute().use { _ -> }
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun sendTestAlertAsync(topic: String, deviceLabel: String = "SentinelCam") {
        val clean = topic.trim()
        if (clean.isEmpty()) return
        pushExecutor.execute {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val bodyJson = JSONObject().apply {
                    put("topic", clean)
                    put("title", "$deviceLabel • Notification Parent active")
                    put("message", "Les alertes et demandes de votre enfant arriveront directement sur votre téléphone sans ouvrir le site.")
                    put("priority", 4)
                    put("tags", JSONArray().put("white_check_mark"))
                    put("click", "https://axeldelaro.github.io/campwa/")
                }
                val req = Request.Builder()
                    .url("https://ntfy.sh")
                    .post(bodyJson.toString().toRequestBody(mediaType))
                    .build()
                httpClient.newCall(req).execute().use { _ -> }
            } catch (_: Throwable) {
            }
        }
    }

    fun syncParentPhoneSubscriber(context: Context, prefs: PortalPreferences) {
        val appCtx = context.applicationContext
        val topics = prefs.getParentSubscribedTopics()
        if (topics.isEmpty() || prefs.parentManaged) {
            stopSubscriber()
            return
        }
        val joined = topics.sorted().joinToString(",")
        if (joined == subscribedTopicsKey && subscriberWs != null) {
            return
        }
        stopSubscriber()
        subscribedTopicsKey = joined
        ensureAlertChannel(appCtx)

        try {
            val req = Request.Builder()
                .url("wss://ntfy.sh/$joined/ws")
                .build()
            subscriberWs = httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    subscriberConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.optString("event") != "message") return
                        val title = json.optString("title", "").ifEmpty { "Alerte SentinelCam" }
                        val message = json.optString("message", "")
                        if (message.isEmpty() || message.contains("WAKE_INVITE")) return
                        showNativeParentNotification(appCtx, title, message)
                    } catch (_: Throwable) {
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    subscriberConnected = false
                    subscriberWs = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    subscriberConnected = false
                    subscriberWs = null
                }
            })
        } catch (_: Throwable) {
            subscriberConnected = false
            subscriberWs = null
        }
    }

    fun stopSubscriber() {
        subscriberConnected = false
        subscribedTopicsKey = ""
        try {
            subscriberWs?.close(1000, "stop")
        } catch (_: Throwable) {
        }
        subscriberWs = null
    }

    private fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    PARENT_ALERT_CHANNEL_ID,
                    "Alertes & Demandes Enfant",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications instantanées des demandes et alertes de sécurité de votre enfant"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            } catch (_: Throwable) {
            }
        }
    }

    private fun showNativeParentNotification(context: Context, title: String, message: String) {
        try {
            ensureAlertChannel(context)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                210,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, PARENT_ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifIdGen.incrementAndGet(), notif)
        } catch (_: Throwable) {
        }
    }
}
