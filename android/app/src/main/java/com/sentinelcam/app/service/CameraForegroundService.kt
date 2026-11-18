package com.sentinelcam.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.signaling.MqttSignalingEngine
import com.sentinelcam.app.storage.PortalPreferences
import com.sentinelcam.app.webrtc.NativeWebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

class CameraForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PortalPreferences

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var signalingEngine: MqttSignalingEngine? = null
    private var webRtcEngine: NativeWebRtcEngine? = null
    private var startedAtMs: Long = System.currentTimeMillis()

    private data class PendingChallenge(
        val tokenId: String,
        val clientNonce: String,
        val serverNonce: String,
        val createdAt: Long
    )

    @Volatile
    private var pendingChallenge: PendingChallenge? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PortalPreferences(this)
        startedAtMs = System.currentTimeMillis()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_PORTAL -> {
                prefs.userManuallyStopped = true
                prefs.appendLog("security", "Portail coupé manuellement par l’utilisateur.")
                shutdownPortalCompletely()
                return START_NOT_STICKY
            }

            ACTION_SLEEP_CAMERA -> {
                val activeSession = webRtcEngine?.currentSessionId.orEmpty()
                webRtcEngine?.stopStream()
                if (activeSession.isNotEmpty()) {
                    signalingEngine?.send(
                        JSONObject().apply {
                            put("type", "STREAM_STATE")
                            put("sessionId", activeSession)
                            put("state", "standby")
                            put("cameraFacing", currentFacing)
                            put("micEnabled", currentMic)
                            put("ts", System.currentTimeMillis())
                        },
                        mode = "sess"
                    )
                }
                prefs.appendLog("info", "Caméra mise en veille depuis la notification système.")
                updateNotificationAndNotifyListeners()
                return START_STICKY
            }

            ACTION_RESTART_SIGNALING -> {
                startInForeground()
                initializeOrReloadSignaling()
                return START_STICKY
            }
        }

        prefs.userManuallyStopped = false
        isRunning = true
        startInForeground()

        if (webRtcEngine == null) {
            initWebRtcEngine()
        }
        if (signalingEngine == null) {
            initializeOrReloadSignaling()
            startHeartbeatLoop()
        }

        notifyStateListeners()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure uninterrupted continuity if user swipes the app from Recents
        if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
            val restartIntent = Intent(applicationContext, CameraForegroundService::class.java)
            val pi = PendingIntent.getService(
                applicationContext,
                701,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1200L,
                pi
            )
        }
    }

    override fun onDestroy() {
        isRunning = false
        mqttConnected = false
        isStreaming = false
        activeBrokerLabel = "Arrêté"
        serviceScope.cancel()
        signalingEngine?.destroy()
        signalingEngine = null
        webRtcEngine?.releaseAll()
        webRtcEngine = null
        releaseLocks()
        notifyStateListeners()
        super.onDestroy()
    }

    private fun shutdownPortalCompletely() {
        isRunning = false
        mqttConnected = false
        isStreaming = false
        activeBrokerLabel = "Arrêté"
        signalingEngine?.destroy()
        signalingEngine = null
        webRtcEngine?.releaseAll()
        webRtcEngine = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyStateListeners()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SentinelCam::ContinuousPortalWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "SentinelCam::LowLatencyWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun initWebRtcEngine() {
        webRtcEngine = NativeWebRtcEngine(
            context = this,
            onLocalOfferReady = { sessionId, sdp, fingerprint ->
                signalingEngine?.send(
                    JSONObject().apply {
                        put("type", "SDP_OFFER")
                        put("sessionId", sessionId)
                        put("sdp", sdp)
                        put("fingerprint", fingerprint)
                        put("ts", System.currentTimeMillis())
                    },
                    mode = "sess"
                )
            },
            onLocalIceCandidate = { sessionId, candidate ->
                signalingEngine?.send(
                    JSONObject().apply {
                        put("type", "ICE_CANDIDATE")
                        put("sessionId", sessionId)
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("ts", System.currentTimeMillis())
                    },
                    mode = "sess"
                )
            },
            onStreamStateChanged = { active, err ->
                isStreaming = active
                currentFacing = webRtcEngine?.currentFacing ?: "front"
                currentMic = webRtcEngine?.currentMicEnabled ?: false
                if (err != null) {
                    prefs.appendLog("warn", err)
                }
                updateNotificationAndNotifyListeners()
                broadcastPresenceNow()
            }
        )
    }

    private fun initializeOrReloadSignaling() {
        signalingEngine?.destroy()
        signalingEngine = null
        webRtcEngine?.stopStream()

        val code = prefs.pairingCode
        serviceScope.launch(Dispatchers.Default) {
            val keys = CryptoProtocol.deriveBootstrapKeys(code)
            val engine = MqttSignalingEngine(
                context = applicationContext,
                topicId = keys.topicId,
                bootstrapEncKey = keys.bootstrapEncKey,
                onMessageDecrypted = { json, _ ->
                    handleDecryptedSignal(json)
                },
                onConnectionStateChanged = { connected, brokerLabel ->
                    mqttConnected = connected
                    activeBrokerLabel = brokerLabel
                    if (connected) {
                        broadcastPresenceNow()
                    }
                    notifyStateListeners()
                }
            )
            signalingEngine = engine
            engine.start()
            prefs.appendLog("security", "Portail E2EE en écoute continue — Code [$code]")
            notifyStateListeners()
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                if (isRunning) {
                    broadcastPresenceNow()
                }
            }
        }
    }

    private fun buildPortalStatusJson(): JSONObject {
        val bat = readBatteryInfo()
        val lockoutRem = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        val stateStr = when {
            lockoutRem > 0L -> "locked"
            isStreaming -> "streaming"
            else -> "standby"
        }

        return JSONObject().apply {
            put("type", "PORTAL_STATUS")
            put("deviceId", prefs.deviceId)
            put("deviceName", prefs.deviceName)
            put("portalState", stateStr)
            put("paired", !prefs.pairedKeyHex.isNullOrEmpty())
            put("pairedTokenId", prefs.pairedTokenId ?: JSONObject.NULL)
            put("lockoutRemainingMs", lockoutRem)
            put("failedAttempts", prefs.failedAttempts)
            put("batteryLevel", bat.first)
            put("isCharging", bat.second)
            put("networkType", readNetworkTypeLabel())
            put("cameraFacing", currentFacing)
            put("micEnabled", currentMic)
            put("uptimeSec", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
            put("ts", System.currentTimeMillis())
        }
    }

    private fun broadcastPresenceNow() {
        val status = buildPortalStatusJson()
        signalingEngine?.send(status, mode = "boot", toPresence = true)
        webRtcEngine?.sendTelemetryOverDataChannel(status)
    }

    private fun handleDecryptedSignal(msg: JSONObject) {
        when (msg.optString("type")) {
            "PING_STATUS" -> {
                signalingEngine?.send(buildPortalStatusJson(), mode = "boot")
            }

            "AUTH_INIT" -> handleAuthInit(msg)
            "AUTH_RESPONSE" -> handleAuthResponse(msg)

            "CMD_START_STREAM" -> {
                val sessionId = msg.optString("sessionId", "")
                val facing = msg.optString("cameraFacing", "front")
                val enableAudio = msg.optBoolean("enableAudio", false)
                val quality = msg.optString("quality", "hd")
                if (sessionId.isNotEmpty()) {
                    prefs.appendLog("info", "Allumage caméra demandé à distance ($facing).")
                    serviceScope.launch(Dispatchers.Main) {
                        webRtcEngine?.startStream(sessionId, facing, enableAudio, quality)
                    }
                }
            }

            "CMD_STOP_STREAM" -> {
                val sessionId = msg.optString("sessionId", "")
                serviceScope.launch(Dispatchers.Main) {
                    webRtcEngine?.stopStream()
                    signalingEngine?.send(
                        JSONObject().apply {
                            put("type", "STREAM_STATE")
                            put("sessionId", sessionId)
                            put("state", "standby")
                            put("cameraFacing", currentFacing)
                            put("micEnabled", currentMic)
                            put("ts", System.currentTimeMillis())
                        },
                        mode = "sess"
                    )
                }
                prefs.appendLog("info", "Capteur caméra mis en veille à distance.")
            }

            "CMD_SWITCH_CAMERA" -> {
                val targetFacing = msg.optString("cameraFacing", "front")
                webRtcEngine?.switchCamera(targetFacing)
                currentFacing = targetFacing
                broadcastPresenceNow()
                notifyStateListeners()
            }

            "CMD_TOGGLE_MIC" -> {
                val enableAudio = msg.optBoolean("enableAudio", false)
                webRtcEngine?.setMicrophoneEnabled(enableAudio)
                currentMic = enableAudio
                broadcastPresenceNow()
                notifyStateListeners()
            }

            "SDP_ANSWER" -> {
                val sessionId = msg.optString("sessionId", "")
                val sdp = msg.optString("sdp", "")
                if (sessionId.isNotEmpty() && sdp.isNotEmpty()) {
                    webRtcEngine?.applyRemoteAnswer(sessionId, sdp)
                }
            }

            "ICE_CANDIDATE" -> {
                val sessionId = msg.optString("sessionId", "")
                val candidate = msg.optString("candidate", "")
                val sdpMid = if (msg.isNull("sdpMid")) null else msg.optString("sdpMid")
                val sdpMLineIndex = msg.optInt("sdpMLineIndex", 0)
                if (sessionId.isNotEmpty() && candidate.isNotEmpty()) {
                    webRtcEngine?.addRemoteIceCandidate(sessionId, candidate, sdpMid, sdpMLineIndex)
                }
            }
        }
    }

    private fun handleAuthInit(msg: JSONObject) {
        val now = System.currentTimeMillis()
        val lockoutRem = (prefs.lockoutUntil - now).coerceAtLeast(0L)
        if (lockoutRem > 0L) {
            sendAuthError(
                reason = "LOCKED_OUT",
                message = "Verrouillage anti-brute-force actif (${(lockoutRem / 1000L) + 1}s).",
                lockoutRemainingMs = lockoutRem
            )
            return
        }

        val tokenId = msg.optString("tokenId", "")
        val clientNonce = msg.optString("clientNonce", "")
        if (tokenId.isEmpty() || clientNonce.isEmpty()) return

        val existingPairedKey = prefs.pairedKeyHex
        val isFirstPair = existingPairedKey.isNullOrEmpty()

        if (!isFirstPair && prefs.pairedTokenId != tokenId) {
            recordFailure("Tentative d’accès par un poste non appairé bloquée.")
            val remAfter = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            sendAuthError(
                reason = if (remAfter > 0L) "LOCKED_OUT" else "ALREADY_PAIRED",
                message = "Ce smartphone est verrouillé sur un navigateur déjà appairé.",
                lockoutRemainingMs = remAfter
            )
            return
        }

        val serverNonce = CryptoProtocol.randomHex(24)
        pendingChallenge = PendingChallenge(
            tokenId = tokenId,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            createdAt = now
        )

        signalingEngine?.send(
            JSONObject().apply {
                put("type", "AUTH_CHALLENGE")
                put("deviceId", prefs.deviceId)
                put("deviceName", prefs.deviceName)
                put("serverNonce", serverNonce)
                put("deviceSalt", prefs.deviceSalt)
                put("isFirstPair", isFirstPair)
                put("ts", System.currentTimeMillis())
            },
            mode = "boot"
        )
    }

    private fun handleAuthResponse(msg: JSONObject) {
        val ch = pendingChallenge ?: return
        pendingChallenge = null

        if (System.currentTimeMillis() - ch.createdAt > 30_000L) {
            sendAuthError("EXPIRED_CHALLENGE", "Challenge expiré.", 0L)
            return
        }

        val proofHex = msg.optString("proofHex", "")
        val incomingPairingKey = msg.optString("pairingKeyHex", "")
        val candidateKeyHex = prefs.pairedKeyHex ?: incomingPairingKey

        if (candidateKeyHex.length != 64) {
            recordFailure("Jeton cryptographique invalide.")
            return
        }

        val expectedProof = CryptoProtocol.computeClientChallengeProof(
            pairedKeyHex = candidateKeyHex,
            clientNonce = ch.clientNonce,
            serverNonce = ch.serverNonce,
            tokenId = ch.tokenId,
            deviceId = prefs.deviceId
        )

        if (!CryptoProtocol.constantTimeEqualHex(expectedProof, proofHex)) {
            recordFailure("Échec de signature HMAC-SHA256 !")
            val remAfter = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            sendAuthError(
                reason = if (remAfter > 0L) "LOCKED_OUT" else "INVALID_PROOF",
                message = "Preuve HMAC invalide.",
                lockoutRemainingMs = remAfter
            )
            return
        }

        // Valid authentication!
        val sas = CryptoProtocol.computeSas(
            normalizedCode = prefs.pairingCode,
            tokenId = ch.tokenId,
            deviceId = prefs.deviceId,
            pairedKeyHex = candidateKeyHex
        )

        prefs.pairedTokenId = ch.tokenId
        prefs.pairedKeyHex = candidateKeyHex
        prefs.sasCode = sas.sasCode
        prefs.sasSymbols = sas.sasSymbols
        prefs.failedAttempts = 0
        prefs.lockoutUntil = 0L

        val sessionKey = CryptoProtocol.deriveSessionAesKey(
            pairedKeyHex = candidateKeyHex,
            clientNonce = ch.clientNonce,
            serverNonce = ch.serverNonce
        )
        signalingEngine?.setSessionKey(sessionKey)

        val serverProof = CryptoProtocol.computeServerChallengeProof(
            pairedKeyHex = candidateKeyHex,
            clientNonce = ch.clientNonce,
            serverNonce = ch.serverNonce,
            tokenId = ch.tokenId,
            deviceId = prefs.deviceId
        )

        val bat = readBatteryInfo()
        signalingEngine?.send(
            JSONObject().apply {
                put("type", "AUTH_OK")
                put("deviceId", prefs.deviceId)
                put("deviceName", prefs.deviceName)
                put("serverProofHex", serverProof)
                put("sasCode", sas.sasCode)
                put("sasSymbols", sas.sasSymbols)
                put("portalState", if (isStreaming) "streaming" else "standby")
                put("batteryLevel", bat.first)
                put("isCharging", bat.second)
                put("networkType", readNetworkTypeLabel())
                put("cameraFacing", currentFacing)
                put("micEnabled", currentMic)
                put("uptimeSec", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
                put("ts", System.currentTimeMillis())
            },
            mode = "boot"
        )

        prefs.appendLog("security", "Poste de contrôle authentifié — SAS [${sas.sasCode}]")
        notifyStateListeners()
    }

    private fun recordFailure(logMessage: String) {
        val count = prefs.failedAttempts + 1
        prefs.failedAttempts = count
        val lockoutMs = CryptoProtocol.calculateLockoutDurationMs(count)
        if (lockoutMs > 0L) {
            prefs.lockoutUntil = System.currentTimeMillis() + lockoutMs
            prefs.appendLog("security", "$logMessage Verrouillage ${lockoutMs / 1000L}s activé.")
        } else {
            prefs.appendLog("warn", "$logMessage (Tentative $count/3)")
        }
        notifyStateListeners()
    }

    private fun sendAuthError(reason: String, message: String, lockoutRemainingMs: Long) {
        signalingEngine?.send(
            JSONObject().apply {
                put("type", "AUTH_ERR")
                put("reason", reason)
                put("message", message)
                put("lockoutRemainingMs", lockoutRemainingMs)
                put("failedAttempts", prefs.failedAttempts)
                put("ts", System.currentTimeMillis())
            },
            mode = "boot"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Portail Continu SentinelCam",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Affiche l’état transparent du portail caméra SentinelCam en arrière-plan"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP_PORTAL
        }
        val stopPi = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isStreaming) {
            "Streaming caméra en cours"
        } else {
            "Portail SentinelCam actif - En attente"
        }

        val subtitle = if (isStreaming) {
            "Flux WebRTC chiffré actif (${if (currentFacing == "front") "Caméra frontale" else "Caméra arrière"})"
        } else {
            "Code [${prefs.pairingCode}] • Capteur éteint • Écoute E2EE continue"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)

        if (isStreaming) {
            val sleepIntent = Intent(this, CameraForegroundService::class.java).apply {
                action = ACTION_SLEEP_CAMERA
            }
            val sleepPi = PendingIntent.getService(
                this,
                102,
                sleepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Mettre en veille",
                sleepPi
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Couper le portail",
            stopPi
        )

        return builder.build()
    }

    private fun startInForeground() {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var fgType = 0
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (fgType == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }

            try {
                if (fgType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                // Fallback on Android 14+ when triggered cold from BOOT_COMPLETED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationAndNotifyListeners() {
        if (isRunning) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildOngoingNotification())
        }
        notifyStateListeners()
    }

    private fun readBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        } catch (_: Exception) {
            Pair(100, false)
        }
    }

    private fun readNetworkTypeLabel(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "Hors ligne"
            val caps = cm.getNetworkCapabilities(net) ?: return "Réseau actif"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "5G/4G LTE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> " Mobile IP"
            }
        } catch (_: Exception) {
            "Android IP"
        }
    }

    companion object {
        private const val CHANNEL_ID = "sentinelcam_portal_channel"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_STOP_PORTAL = "com.sentinelcam.app.ACTION_STOP_PORTAL"
        const val ACTION_SLEEP_CAMERA = "com.sentinelcam.app.ACTION_SLEEP_CAMERA"
        const val ACTION_RESTART_SIGNALING = "com.sentinelcam.app.ACTION_RESTART_SIGNALING"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isStreaming: Boolean = false
            private set

        @Volatile
        var mqttConnected: Boolean = false
            private set

        @Volatile
        var activeBrokerLabel: String = "En attente"
            private set

        @Volatile
        var currentFacing: String = "front"
            private set

        @Volatile
        var currentMic: Boolean = false
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeStateListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        private fun notifyStateListeners() {
            for (l in listeners) {
                try {
                    l.invoke()
                } catch (_: Exception) {
                }
            }
        }

        fun startPortal(context: Context) {
            val intent = Intent(context.applicationContext, CameraForegroundService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun reloadSignaling(context: Context) {
            if (!isRunning) return
            val intent = Intent(context.applicationContext, CameraForegroundService::class.java).apply {
                action = ACTION_RESTART_SIGNALING
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stopPortal(context: Context) {
            val intent = Intent(context.applicationContext, CameraForegroundService::class.java).apply {
                action = ACTION_STOP_PORTAL
            }
            context.applicationContext.startService(intent)
        }
    }
}
