package com.sentinelcam.app.service

import android.Manifest
import android.app.ActivityManager
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.receiver.KeepAliveReceiver
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class CameraForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PortalPreferences

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var signalingEngine: MqttSignalingEngine? = null
    private var webRtcEngine: NativeWebRtcEngine? = null
    private var startedAtMs: Long = System.currentTimeMillis()
    private var activeSignalingCode: String = ""
    private var heartbeatStarted: Boolean = false

    @Volatile
    private var cameraFgsPromoted: Boolean = false

    private data class PendingChallenge(
        val tokenId: String,
        val clientNonce: String,
        val serverNonce: String,
        val createdAt: Long
    )

    private val pendingChallenges = ConcurrentHashMap<String, PendingChallenge>()

    override fun onCreate() {
        super.onCreate()
        prefs = PortalPreferences(this)
        startedAtMs = System.currentTimeMillis()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_PORTAL) {
            prefs.userManuallyStopped = true
            prefs.appendLog("security", "Portail coupé par l’utilisateur.")
            PortalWatchdogJobService.cancelAll(this)
            KeepAliveReceiver.cancelPulse(this)
            shutdownPortalCompletely()
            return START_NOT_STICKY
        }

        prefs.userManuallyStopped = false
        isRunning = true

        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) ?: false
        if (!startInForegroundSafely(promoteCamera = fromUi)) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Schedule continuous self-healing watchdogs
        PortalWatchdogJobService.scheduleWatchdog(this)
        KeepAliveReceiver.scheduleNextPulse(this)

        when (action) {
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
                updateNotificationAndNotifyListeners()
                return START_STICKY
            }

            ACTION_NUDGE_NETWORK -> {
                ensureEnginesRunning(forceReloadSignaling = false)
                signalingEngine?.nudgeReconnect()
                broadcastPresenceNow()
                return START_STICKY
            }

            ACTION_RESTART_SIGNALING -> {
                ensureEnginesRunning(forceReloadSignaling = true)
                return START_STICKY
            }
        }

        ensureEnginesRunning(forceReloadSignaling = false)
        notifyStateListeners()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                // JobScheduler + KeepAliveReceiver safely keep/revive the portal without Android 12-15 crashes
                PortalWatchdogJobService.scheduleImmediateRevive(applicationContext)
                KeepAliveReceiver.scheduleNextPulse(applicationContext)
                signalingEngine?.nudgeReconnect()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        val shouldRevive = try {
            prefs.onboardingCompleted && !prefs.userManuallyStopped
        } catch (_: Throwable) {
            false
        }

        isRunning = false
        mqttConnected = false
        isStreaming = false
        cameraFgsPromoted = false
        activeBrokerLabel = "Reconnexion..."

        serviceScope.cancel()
        try {
            signalingEngine?.destroy()
        } catch (_: Throwable) {
        }
        signalingEngine = null

        try {
            webRtcEngine?.shutdownEngine()
        } catch (_: Throwable) {
        }
        webRtcEngine = null

        releaseLocks()
        notifyStateListeners()

        if (shouldRevive) {
            PortalWatchdogJobService.scheduleImmediateRevive(applicationContext)
            KeepAliveReceiver.scheduleNextPulse(applicationContext)
        }

        super.onDestroy()
    }

    private fun shutdownPortalCompletely() {
        isRunning = false
        mqttConnected = false
        isStreaming = false
        cameraFgsPromoted = false
        activeBrokerLabel = "Arrêté"

        try {
            signalingEngine?.destroy()
        } catch (_: Throwable) {
        }
        signalingEngine = null

        try {
            webRtcEngine?.shutdownEngine()
        } catch (_: Throwable) {
        }
        webRtcEngine = null

        releaseLocks()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
        notifyStateListeners()
    }

    private fun ensureEnginesRunning(forceReloadSignaling: Boolean) {
        if (webRtcEngine == null) {
            initWebRtcEngine()
        }
        val currentCode = prefs.pairingCode
        if (signalingEngine == null || forceReloadSignaling || activeSignalingCode != currentCode) {
            initializeOrReloadSignaling(currentCode)
        }
        if (!heartbeatStarted) {
            heartbeatStarted = true
            startHeartbeatLoop()
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SentinelCam::ContinuousPortalWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
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
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
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
                serviceScope.launch(Dispatchers.Main) {
                    updateNotificationAndNotifyListeners()
                    broadcastPresenceNow()
                }
            }
        )
    }

    private fun initializeOrReloadSignaling(code: String) {
        activeSignalingCode = code
        val previous = signalingEngine
        signalingEngine = null
        try {
            previous?.destroy()
        } catch (_: Throwable) {
        }
        webRtcEngine?.stopStream()

        serviceScope.launch(Dispatchers.IO) {
            try {
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
                prefs.appendLog("security", "Pont continu actif 24h/24 — Code [$code]")
                notifyStateListeners()
            } catch (t: Throwable) {
                prefs.appendLog("warn", "Erreur init réseau: ${t.message}")
            }
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(8_000L)
                if (isRunning) {
                    acquireLocks()
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
            put("paired", prefs.hasAnyAuthorizedToken())
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
        try {
            val status = buildPortalStatusJson()
            signalingEngine?.send(status, mode = "boot", toPresence = true)
            webRtcEngine?.sendTelemetryOverDataChannel(status)
        } catch (_: Throwable) {
        }
    }

    private fun handleDecryptedSignal(msg: JSONObject) {
        try {
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
                        prefs.appendLog("info", "Caméra activée à distance ($facing).")
                        webRtcEngine?.startStream(sessionId, facing, enableAudio, quality)
                    }
                }

                "CMD_STOP_STREAM" -> {
                    val sessionId = msg.optString("sessionId", "")
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
                    prefs.appendLog("info", "Caméra mise en veille à distance.")
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
        } catch (t: Throwable) {
            prefs.appendLog("warn", "Signal ignoré: ${t.javaClass.simpleName}")
        }
    }

    private fun handleAuthInit(msg: JSONObject) {
        val now = System.currentTimeMillis()
        val lockoutRem = (prefs.lockoutUntil - now).coerceAtLeast(0L)
        if (lockoutRem > 0L) {
            sendAuthError(
                reason = "LOCKED_OUT",
                message = "Verrouillage sécurité actif (${(lockoutRem / 1000L) + 1}s).",
                lockoutRemainingMs = lockoutRem
            )
            return
        }

        val tokenId = msg.optString("tokenId", "")
        val clientNonce = msg.optString("clientNonce", "")
        if (tokenId.isEmpty() || clientNonce.isEmpty()) return

        // Check if this specific browser tokenId already holds a registered HKDF key;
        // if not, allow seamless HKDF pairing because the caller already proved knowledge of the 6-char PBKDF2 key!
        val existingKeyForToken = prefs.getAuthorizedKeyForToken(tokenId)
        val isFirstPairForThisBrowser = existingKeyForToken.isNullOrEmpty()

        val serverNonce = CryptoProtocol.randomHex(24)
        pendingChallenges[tokenId] = PendingChallenge(
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
                put("isFirstPair", isFirstPairForThisBrowser)
                put("ts", System.currentTimeMillis())
            },
            mode = "boot"
        )
    }

    private fun handleAuthResponse(msg: JSONObject) {
        val tokenId = msg.optString("tokenId", "")
        if (tokenId.isEmpty()) return
        val ch = pendingChallenges.remove(tokenId) ?: return

        if (System.currentTimeMillis() - ch.createdAt > 45_000L) {
            sendAuthError("EXPIRED_CHALLENGE", "Délai dépassé, reconnexion automatique...", 0L)
            return
        }

        val proofHex = msg.optString("proofHex", "")
        val incomingPairingKey = msg.optString("pairingKeyHex", "")
        val existingKey = prefs.getAuthorizedKeyForToken(tokenId)
        val candidateKeyHex = existingKey ?: incomingPairingKey

        if (candidateKeyHex.length != 64) {
            recordFailure("Clé d’appairage invalide.")
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
            recordFailure("Signature HMAC invalide !")
            val remAfter = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            sendAuthError(
                reason = if (remAfter > 0L) "LOCKED_OUT" else "INVALID_PROOF",
                message = "Authentification refusée.",
                lockoutRemainingMs = remAfter
            )
            return
        }

        val sas = CryptoProtocol.computeSas(
            normalizedCode = prefs.pairingCode,
            tokenId = ch.tokenId,
            deviceId = prefs.deviceId,
            pairedKeyHex = candidateKeyHex
        )

        prefs.registerAuthorizedToken(ch.tokenId, candidateKeyHex, Pair(sas.sasCode, sas.sasSymbols))

        val sessionKey = CryptoProtocol.deriveSessionAesKey(
            pairedKeyHex = candidateKeyHex,
            clientNonce = ch.clientNonce,
            serverNonce = ch.serverNonce
        )
        signalingEngine?.setSessionKey(sessionKey, ch.tokenId)

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

        prefs.appendLog("security", "Connexion Web autorisée — SAS [${sas.sasCode}]")
        notifyStateListeners()
    }

    private fun recordFailure(logMessage: String) {
        val count = prefs.failedAttempts + 1
        prefs.failedAttempts = count
        val lockoutMs = CryptoProtocol.calculateLockoutDurationMs(count)
        if (lockoutMs > 0L) {
            prefs.lockoutUntil = System.currentTimeMillis() + lockoutMs
            prefs.appendLog("security", "$logMessage Verrouillage ${lockoutMs / 1000L}s.")
        } else {
            prefs.appendLog("warn", "$logMessage ($count/3)")
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
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Portail Continu SentinelCam",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintient le pont continu SentinelCam actif en arrière-plan"
                    setShowBadge(false)
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            } catch (_: Throwable) {
            }
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
            "Flux direct chiffré (${if (currentFacing == "front") "Caméra avant" else "Caméra arrière"})"
        } else {
            "Code : ${prefs.pairingCode} • Pont continu 24h/24 actif"
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

    private fun startInForegroundSafely(promoteCamera: Boolean): Boolean {
        val notification = try {
            buildOngoingNotification()
        } catch (_: Throwable) {
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return try {
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (_: Throwable) {
                false
            }
        }

        val appVisible = promoteCamera || isAppInForeground() || cameraFgsPromoted
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        // 1. Try full CAMERA (+ MICROPHONE) FGS when eligible
        if (appVisible && hasCam) {
            var fullType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasMic) {
                fullType = fullType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                fullType = fullType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fullType)
                cameraFgsPromoted = true
                return true
            } catch (_: Throwable) {
                // Fall through to SPECIAL_USE fallback
            }
        }

        // 2. Fallback to SPECIAL_USE (Android 14+) or base startForeground (Android 11-13)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (hasCam) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateNotificationAndNotifyListeners() {
        if (isRunning) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildOngoingNotification())
            } catch (_: Throwable) {
            }
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
        } catch (_: Throwable) {
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
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "5G / 4G"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Mobile IP"
            }
        } catch (_: Throwable) {
            "Android IP"
        }
    }

    companion object {
        private const val CHANNEL_ID = "sentinelcam_portal_channel"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_STOP_PORTAL = "com.sentinelcam.app.ACTION_STOP_PORTAL"
        const val ACTION_SLEEP_CAMERA = "com.sentinelcam.app.ACTION_SLEEP_CAMERA"
        const val ACTION_RESTART_SIGNALING = "com.sentinelcam.app.ACTION_RESTART_SIGNALING"
        const val ACTION_NUDGE_NETWORK = "com.sentinelcam.app.ACTION_NUDGE_NETWORK"
        private const val EXTRA_FROM_UI = "extra_from_ui"

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
        var activeBrokerLabel: String = "Connexion..."
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
                } catch (_: Throwable) {
                }
            }
        }

        fun isAppInForeground(): Boolean {
            return try {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Called from visible MainActivity / Plugin UI: starts or upgrades service with full Camera FGS rights.
         */
        fun startFromUi(context: Context) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CameraForegroundService::class.java).apply {
                    putExtra(EXTRA_FROM_UI, true)
                }
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Throwable) {
            }
        }

        /**
         * Safe background revival / keep-alive nudge (called from JobScheduler, BootReceiver, KeepAliveReceiver).
         * Never throws BackgroundServiceStartNotAllowedException or ForegroundServiceStartNotAllowedException.
         */
        fun ensureRunningSafely(context: Context) {
            try {
                val appCtx = context.applicationContext
                if (isRunning) {
                    val nudge = Intent(appCtx, CameraForegroundService::class.java).apply {
                        action = ACTION_NUDGE_NETWORK
                    }
                    appCtx.startService(nudge)
                    return
                }

                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val canStartBg = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    isAppInForeground() ||
                    pm.isIgnoringBatteryOptimizations(appCtx.packageName)

                if (canStartBg) {
                    val intent = Intent(appCtx, CameraForegroundService::class.java)
                    ContextCompat.startForegroundService(appCtx, intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun reloadSignaling(context: Context) {
            try {
                if (!isRunning) {
                    startFromUi(context)
                    return
                }
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CameraForegroundService::class.java).apply {
                    action = ACTION_RESTART_SIGNALING
                    putExtra(EXTRA_FROM_UI, isAppInForeground())
                }
                appCtx.startService(intent)
            } catch (_: Throwable) {
            }
        }

        fun stopPortal(context: Context) {
            try {
                val appCtx = context.applicationContext
                PortalWatchdogJobService.cancelAll(appCtx)
                KeepAliveReceiver.cancelPulse(appCtx)
                if (!isRunning) return
                val intent = Intent(appCtx, CameraForegroundService::class.java).apply {
                    action = ACTION_STOP_PORTAL
                }
                appCtx.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
