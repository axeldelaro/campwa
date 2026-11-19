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
import com.sentinelcam.app.crypto.BootstrapKeys
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.receiver.KeepAliveReceiver
import com.sentinelcam.app.receiver.StopPortalReceiver
import com.sentinelcam.app.signaling.MqttSignalingEngine
import com.sentinelcam.app.signaling.SignalingChannelSpec
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

    private val channelKeysById = ConcurrentHashMap<String, BootstrapKeys>()
    private var signalingEngine: MqttSignalingEngine? = null
    private var webRtcEngine: NativeWebRtcEngine? = null
    private var startedAtMs: Long = System.currentTimeMillis()
    private var telemetryLoopStarted: Boolean = false

    @Volatile
    private var lastViewerSeenMs: Long = 0L

    @Volatile
    private var activeStreamChannelId: String = OWNER_CHANNEL_ID

    @Volatile
    private var cameraFgsPromoted: Boolean = false

    private data class ResolvedGrant(
        val allowed: Boolean,
        val denyReason: String? = null,
        val denyMessage: String? = null,
        val role: String = "owner",
        val name: String = "Propriétaire",
        val expiresAt: Long? = null,
        val allowAudio: Boolean = true,
        val allowSwitchCamera: Boolean = true
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PortalPreferences(this)
        startedAtMs = System.currentTimeMillis()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (prefs.userManuallyStopped) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) ?: false

        if (!startInForegroundSafely(promoteCamera = fromUi)) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        PortalWatchdogJobService.scheduleWatchdog(this)
        KeepAliveReceiver.scheduleNextPulse(this)

        when (intent?.action) {
            ACTION_NUDGE_NETWORK -> {
                ensureSignalingRunning(forceRebuild = false)
                signalingEngine?.nudgeReconnect()
                return START_STICKY
            }

            ACTION_SYNC_CHANNELS -> {
                syncChannelsHot()
                return START_STICKY
            }

            ACTION_RESTART_SIGNALING -> {
                ensureSignalingRunning(forceRebuild = true)
                return START_STICKY
            }
        }

        ensureSignalingRunning(forceRebuild = false)
        notifyStateListeners()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (prefs.onboardingCompleted && !prefs.userManuallyStopped) {
                PortalWatchdogJobService.scheduleImmediateRevive(applicationContext)
                KeepAliveReceiver.scheduleNextPulse(applicationContext)
                signalingEngine?.nudgeReconnect()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        instance = null
        val shouldRevive = try {
            prefs.onboardingCompleted && !prefs.userManuallyStopped
        } catch (_: Throwable) {
            false
        }

        isRunning = false
        mqttConnected = false
        isStreaming = false
        cameraFgsPromoted = false
        activeViewerName = null
        activeViewerRole = null
        activeViewerExpiresAt = null
        activeBrokerLabel = if (shouldRevive) "Reconnexion..." else "En pause"

        serviceScope.cancel()

        val sig = signalingEngine
        signalingEngine = null
        try { sig?.destroy() } catch (_: Throwable) {}

        val rtc = webRtcEngine
        webRtcEngine = null
        try { rtc?.shutdownEngine() } catch (_: Throwable) {}

        releaseStreamingLocks()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }

        notifyStateListeners()

        if (shouldRevive) {
            PortalWatchdogJobService.scheduleImmediateRevive(applicationContext)
            KeepAliveReceiver.scheduleNextPulse(applicationContext)
        }

        super.onDestroy()
    }

    private fun acquireStreamingLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SentinelCam::ActiveStreamWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L)
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
                wifiLock = wm.createWifiLock(lockMode, "SentinelCam::StreamWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseStreamingLocks() {
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

    private fun ensureWebRtcInitialized(): NativeWebRtcEngine {
        var rtc = webRtcEngine
        if (rtc != null) return rtc
        rtc = NativeWebRtcEngine(
            context = this,
            onLocalOfferReady = { sessionId, sdp, fingerprint ->
                signalingEngine?.send(
                    channelId = activeStreamChannelId,
                    payload = JSONObject().apply {
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
                    channelId = activeStreamChannelId,
                    payload = JSONObject().apply {
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
                if (active) {
                    acquireStreamingLocks()
                } else {
                    releaseStreamingLocks()
                    activeViewerName = null
                    activeViewerRole = null
                    activeViewerExpiresAt = null
                }
                currentFacing = webRtcEngine?.currentFacing ?: "front"
                currentMic = webRtcEngine?.currentMicEnabled ?: false
                if (err != null) {
                    prefs.appendLog("warn", err)
                }
                if (isRunning) {
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotificationAndNotifyListeners()
                        broadcastPresenceNow(activeStreamChannelId)
                    }
                }
            }
        )
        webRtcEngine = rtc
        return rtc
    }

    private fun buildAllChannelSpecs(): List<SignalingChannelSpec> {
        val list = ArrayList<SignalingChannelSpec>()
        val ownerKeys = CryptoProtocol.deriveBootstrapKeys(prefs.pairingCode)
        list.add(SignalingChannelSpec(OWNER_CHANNEL_ID, ownerKeys))
        channelKeysById.clear()
        channelKeysById[OWNER_CHANNEL_ID] = ownerKeys

        for (prof in prefs.getAccessProfiles()) {
            try {
                val keys = CryptoProtocol.deriveBootstrapKeys(prof.code)
                list.add(SignalingChannelSpec(prof.id, keys))
                channelKeysById[prof.id] = keys
            } catch (_: Throwable) {
            }
        }
        return list
    }

    private fun ensureSignalingRunning(forceRebuild: Boolean) {
        if (signalingEngine == null || forceRebuild) {
            val prev = signalingEngine
            signalingEngine = null
            try { prev?.destroy() } catch (_: Throwable) {}
            webRtcEngine?.stopStream()
            releaseStreamingLocks()

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val specs = buildAllChannelSpecs()
                    val ownerWakeTopic = specs.first().keys.wakePushTopic
                    val engine = MqttSignalingEngine(
                        context = applicationContext,
                        initialChannels = specs,
                        primaryWakePushTopic = ownerWakeTopic,
                        onMessageDecrypted = { channelId, json, _ ->
                            handleDecryptedSignal(channelId, json)
                        },
                        onConnectionStateChanged = { connected, brokerLabel ->
                            mqttConnected = connected
                            activeBrokerLabel = brokerLabel
                            notifyStateListeners()
                        }
                    )
                    signalingEngine = engine
                    engine.start()
                    notifyStateListeners()
                } catch (_: Throwable) {
                }
            }
        } else {
            syncChannelsHot()
        }

        if (!telemetryLoopStarted) {
            telemetryLoopStarted = true
            startEcoTelemetryAndExpiryLoop()
        }
    }

    fun syncChannelsHot() {
        val engine = signalingEngine
        if (engine == null) {
            ensureSignalingRunning(forceRebuild = false)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val specs = buildAllChannelSpecs()
                engine.syncChannels(specs)
                enforceActiveViewerValidity()
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }
    }

    private fun startEcoTelemetryAndExpiryLoop() {
        serviceScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                delay(3_000L)
                if (!isRunning) continue
                enforceActiveViewerValidity()
                tick++
                if (tick % 2 == 0) {
                    val viewerActive = isStreaming || (System.currentTimeMillis() - lastViewerSeenMs) < 35_000L
                    if (viewerActive) {
                        broadcastPresenceNow(activeStreamChannelId)
                    }
                }
            }
        }
    }

    private fun resolveGrant(channelId: String): ResolvedGrant {
        if (channelId == OWNER_CHANNEL_ID) {
            return ResolvedGrant(
                allowed = true,
                role = "owner",
                name = "Propriétaire",
                expiresAt = null,
                allowAudio = true,
                allowSwitchCamera = true
            )
        }

        val prof = prefs.findAccessProfileById(channelId)
            ?: return ResolvedGrant(
                allowed = false,
                denyReason = "ACCESS_REVOKED",
                denyMessage = "Ce code d’accès a été supprimé par le propriétaire."
            )

        if (!prof.enabled) {
            return ResolvedGrant(
                allowed = false,
                denyReason = "ACCESS_REVOKED",
                denyMessage = "L’accès « ${prof.name} » a été suspendu par le propriétaire.",
                role = prof.role,
                name = prof.name,
                expiresAt = prof.expiresAt
            )
        }

        val exp = prof.expiresAt
        if (exp != null && System.currentTimeMillis() > exp) {
            return ResolvedGrant(
                allowed = false,
                denyReason = "ACCESS_EXPIRED",
                denyMessage = "Le pass temporaire « ${prof.name} » est arrivé à expiration.",
                role = prof.role,
                name = prof.name,
                expiresAt = exp
            )
        }

        return ResolvedGrant(
            allowed = true,
            role = prof.role,
            name = prof.name,
            expiresAt = exp,
            allowAudio = prof.allowAudio,
            allowSwitchCamera = prof.allowSwitchCamera
        )
    }

    private fun enforceActiveViewerValidity() {
        if (!isStreaming || activeStreamChannelId == OWNER_CHANNEL_ID) return
        val grant = resolveGrant(activeStreamChannelId)
        if (!grant.allowed) {
            val isExpired = grant.denyReason == "ACCESS_EXPIRED"
            kickActiveViewerInternal(
                reasonState = if (isExpired) "expired" else "revoked",
                authReason = grant.denyReason ?: "ACCESS_REVOKED",
                message = grant.denyMessage ?: "Accès terminé."
            )
        }
    }

    fun kickActiveViewerInternal(
        reasonState: String = "revoked",
        authReason: String = "ACCESS_REVOKED",
        message: String = "Connexion coupée par le propriétaire du smartphone."
    ) {
        val targetCh = activeStreamChannelId
        val activeSession = webRtcEngine?.currentSessionId.orEmpty()
        webRtcEngine?.stopStream()
        releaseStreamingLocks()

        if (activeSession.isNotEmpty()) {
            signalingEngine?.send(
                channelId = targetCh,
                payload = JSONObject().apply {
                    put("type", "STREAM_STATE")
                    put("sessionId", activeSession)
                    put("state", reasonState)
                    put("cameraFacing", currentFacing)
                    put("micEnabled", false)
                    put("error", message)
                    put("ts", System.currentTimeMillis())
                },
                mode = "sess"
            )
        }

        signalingEngine?.send(
            channelId = targetCh,
            payload = JSONObject().apply {
                put("type", "AUTH_ERR")
                put("reason", authReason)
                put("message", message)
                put("lockoutRemainingMs", 0L)
                put("failedAttempts", 0)
                put("ts", System.currentTimeMillis())
            },
            mode = "boot"
        )

        activeViewerName = null
        activeViewerRole = null
        activeViewerExpiresAt = null
        serviceScope.launch(Dispatchers.Main) {
            updateNotificationAndNotifyListeners()
        }
    }

    fun sleepCameraInternal() {
        val targetCh = activeStreamChannelId
        val activeSession = webRtcEngine?.currentSessionId.orEmpty()
        webRtcEngine?.stopStream()
        releaseStreamingLocks()
        activeViewerName = null
        activeViewerRole = null
        activeViewerExpiresAt = null

        if (activeSession.isNotEmpty()) {
            signalingEngine?.send(
                channelId = targetCh,
                payload = JSONObject().apply {
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
    }

    private fun buildPortalStatusJson(channelId: String): JSONObject {
        val grant = resolveGrant(channelId)
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
            put("paired", !prefs.pairedTokenId.isNullOrEmpty())
            put("pairedTokenId", prefs.pairedTokenId ?: JSONObject.NULL)
            put("lockoutRemainingMs", lockoutRem)
            put("failedAttempts", prefs.failedAttempts)
            put("batteryLevel", bat.first)
            put("isCharging", bat.second)
            put("networkType", readNetworkTypeLabel())
            put("cameraFacing", currentFacing)
            put("micEnabled", currentMic)
            put("uptimeSec", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
            put("accessRole", grant.role)
            put("accessName", grant.name)
            put("accessExpiresAt", grant.expiresAt ?: JSONObject.NULL)
            put("allowAudio", grant.allowAudio)
            put("allowSwitchCamera", grant.allowSwitchCamera)
            put("ts", System.currentTimeMillis())
        }
    }

    private fun broadcastPresenceNow(channelId: String) {
        try {
            val status = buildPortalStatusJson(channelId)
            signalingEngine?.send(channelId, status, mode = "boot", toPresence = true)
            webRtcEngine?.sendTelemetryOverDataChannel(status)
        } catch (_: Throwable) {
        }
    }

    private fun handleDecryptedSignal(channelId: String, msg: JSONObject) {
        if (!isRunning) return

        val grant = resolveGrant(channelId)
        if (!grant.allowed) {
            signalingEngine?.send(
                channelId = channelId,
                payload = JSONObject().apply {
                    put("type", "AUTH_ERR")
                    put("reason", grant.denyReason ?: "ACCESS_REVOKED")
                    put("message", grant.denyMessage ?: "Accès non autorisé.")
                    put("lockoutRemainingMs", 0L)
                    put("failedAttempts", 0)
                    put("ts", System.currentTimeMillis())
                },
                mode = "boot"
            )
            return
        }

        lastViewerSeenMs = System.currentTimeMillis()

        try {
            when (msg.optString("type")) {
                "HELLO_WAKE" -> handleHelloWake(channelId, grant, msg)

                "PING_STATUS" -> {
                    signalingEngine?.send(channelId, buildPortalStatusJson(channelId), mode = "boot")
                }

                "CMD_START_STREAM" -> {
                    val sessionId = msg.optString("sessionId", "")
                    val reqFacing = msg.optString("cameraFacing", "front")
                    val reqAudio = msg.optBoolean("enableAudio", false)
                    val quality = msg.optString("quality", "hd")
                    if (sessionId.isNotEmpty()) {
                        val effectiveFacing = if (grant.allowSwitchCamera) reqFacing else "front"
                        val effectiveAudio = if (grant.allowAudio) reqAudio else false
                        activeStreamChannelId = channelId
                        activeViewerName = grant.name
                        activeViewerRole = grant.role
                        activeViewerExpiresAt = grant.expiresAt
                        if (channelId != OWNER_CHANNEL_ID) {
                            prefs.touchProfileLastAccess(channelId)
                        }
                        acquireStreamingLocks()
                        ensureWebRtcInitialized().startStream(sessionId, effectiveFacing, effectiveAudio, quality)
                        serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
                    }
                }

                "CMD_STOP_STREAM" -> {
                    val sessionId = msg.optString("sessionId", "")
                    if (channelId == activeStreamChannelId || channelId == OWNER_CHANNEL_ID) {
                        webRtcEngine?.stopStream()
                        releaseStreamingLocks()
                        activeViewerName = null
                        activeViewerRole = null
                        activeViewerExpiresAt = null
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "STREAM_STATE")
                                put("sessionId", sessionId)
                                put("state", "standby")
                                put("cameraFacing", currentFacing)
                                put("micEnabled", currentMic)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                        serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
                    }
                }

                "CMD_SWITCH_CAMERA" -> {
                    if (grant.allowSwitchCamera && (channelId == activeStreamChannelId || channelId == OWNER_CHANNEL_ID)) {
                        val targetFacing = msg.optString("cameraFacing", "front")
                        webRtcEngine?.switchCamera(targetFacing)
                        currentFacing = targetFacing
                        broadcastPresenceNow(channelId)
                        notifyStateListeners()
                    }
                }

                "CMD_TOGGLE_MIC" -> {
                    if (grant.allowAudio && (channelId == activeStreamChannelId || channelId == OWNER_CHANNEL_ID)) {
                        val enableAudio = msg.optBoolean("enableAudio", false)
                        webRtcEngine?.setMicrophoneEnabled(enableAudio)
                        currentMic = enableAudio
                        broadcastPresenceNow(channelId)
                        notifyStateListeners()
                    }
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
        } catch (_: Throwable) {
        }
    }

    private fun handleHelloWake(channelId: String, grant: ResolvedGrant, msg: JSONObject) {
        val keys = channelKeysById[channelId] ?: return
        val now = System.currentTimeMillis()
        val lockoutRem = (prefs.lockoutUntil - now).coerceAtLeast(0L)
        if (lockoutRem > 0L) {
            signalingEngine?.send(
                channelId = channelId,
                payload = JSONObject().apply {
                    put("type", "AUTH_ERR")
                    put("reason", "LOCKED_OUT")
                    put("message", "Temporisation active (${(lockoutRem / 1000L) + 1}s).")
                    put("lockoutRemainingMs", lockoutRem)
                    put("failedAttempts", prefs.failedAttempts)
                    put("ts", now)
                },
                mode = "boot"
            )
            return
        }

        val tokenId = msg.optString("tokenId", "")
        val clientNonce = msg.optString("clientNonce", "")
        val proofHex = msg.optString("proofHex", "")
        if (tokenId.isEmpty() || clientNonce.isEmpty() || proofHex.isEmpty()) return

        val expectedProof = CryptoProtocol.computeHelloWakeProof(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            tokenId = tokenId
        )

        if (!CryptoProtocol.constantTimeEqualHex(expectedProof, proofHex)) {
            val count = prefs.failedAttempts + 1
            prefs.failedAttempts = count
            val lockMs = CryptoProtocol.calculateLockoutDurationMs(count)
            if (lockMs > 0L) prefs.lockoutUntil = now + lockMs
            return
        }

        val serverNonce = CryptoProtocol.randomHex(20)
        val sessionKey = CryptoProtocol.deriveFastSessionKey(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            deviceId = prefs.deviceId
        )
        signalingEngine?.registerSessionKey(channelId, clientNonce, sessionKey)

        val serverProof = CryptoProtocol.computeHelloAckProof(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            deviceId = prefs.deviceId
        )

        val sas = CryptoProtocol.computeSas(
            normalizedCode = keys.normalizedCode,
            tokenId = tokenId,
            deviceId = prefs.deviceId,
            bootstrapAuthRaw = keys.bootstrapAuthKey
        )

        if (channelId != OWNER_CHANNEL_ID) {
            prefs.touchProfileLastAccess(channelId)
        }

        prefs.pairedTokenId = tokenId
        prefs.sasCode = sas.sasCode
        prefs.sasSymbols = sas.sasSymbols
        prefs.failedAttempts = 0
        prefs.lockoutUntil = 0L

        val bat = readBatteryInfo()
        signalingEngine?.send(
            channelId = channelId,
            payload = JSONObject().apply {
                put("type", "HELLO_ACK")
                put("deviceId", prefs.deviceId)
                put("deviceName", prefs.deviceName)
                put("echoClientNonce", clientNonce)
                put("serverNonce", serverNonce)
                put("serverProofHex", serverProof)
                put("sasCode", sas.sasCode)
                put("sasSymbols", sas.sasSymbols)
                put("portalState", if (isStreaming) "streaming" else "standby")
                put("batteryLevel", bat.first)
                put("isCharging", bat.second)
                put("networkType", readNetworkTypeLabel())
                put("cameraFacing", currentFacing)
                put("micEnabled", currentMic)
                put("uptimeSec", ((now - startedAtMs) / 1000L).coerceAtLeast(0L))
                put("accessRole", grant.role)
                put("accessName", grant.name)
                put("accessExpiresAt", grant.expiresAt ?: JSONObject.NULL)
                put("allowAudio", grant.allowAudio)
                put("allowSwitchCamera", grant.allowSwitchCamera)
                put("ts", now)
            },
            mode = "boot"
        )

        val autoStart = msg.optBoolean("autoStartStream", false)
        val sessionId = msg.optString("sessionId", "")
        if (autoStart && sessionId.isNotEmpty()) {
            val reqFacing = msg.optString("cameraFacing", "front")
            val reqAudio = msg.optBoolean("enableAudio", false)
            val quality = msg.optString("quality", "hd")
            val effectiveFacing = if (grant.allowSwitchCamera) reqFacing else "front"
            val effectiveAudio = if (grant.allowAudio) reqAudio else false

            activeStreamChannelId = channelId
            activeViewerName = grant.name
            activeViewerRole = grant.role
            activeViewerExpiresAt = grant.expiresAt
            acquireStreamingLocks()
            ensureWebRtcInitialized().startStream(sessionId, effectiveFacing, effectiveAudio, quality)
            serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
        }

        notifyStateListeners()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Portail Éco-Veille SentinelCam",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Écoute éco-énergie en tâche de fond SentinelCam"
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

        val stopIntent = Intent(this, StopPortalReceiver::class.java).apply {
            action = StopPortalReceiver.ACTION_USER_STOP_PORTAL
        }
        val stopPi = PendingIntent.getBroadcast(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val viewerLabel = activeViewerName
        val title = if (isStreaming && viewerLabel != null) {
            "Streaming en cours • $viewerLabel"
        } else if (isStreaming) {
            "Streaming caméra en cours"
        } else {
            "SentinelCam actif (Éco-Veille)"
        }

        val activeAccessCount = prefs.getAccessProfiles().count {
            it.enabled && (it.expiresAt == null || it.expiresAt > System.currentTimeMillis())
        }
        val subtitle = if (isStreaming) {
            "Flux direct actif (${if (currentFacing == "front") "Selfie" else "Arrière"})"
        } else if (activeAccessCount > 0) {
            "Code : ${prefs.pairingCode} • +$activeAccessCount accès ami/pass actif(s)"
        } else {
            "Code : ${prefs.pairingCode} • Capteur éteint (0% conso)"
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
            val sleepIntent = Intent(this, StopPortalReceiver::class.java).apply {
                action = StopPortalReceiver.ACTION_USER_SLEEP_CAMERA
            }
            val sleepPi = PendingIntent.getBroadcast(
                this,
                102,
                sleepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Couper la vidéo",
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
            }
        }

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
        private const val OWNER_CHANNEL_ID = "owner"
        private const val CHANNEL_ID = "sentinelcam_portal_channel"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_RESTART_SIGNALING = "com.sentinelcam.app.ACTION_RESTART_SIGNALING"
        const val ACTION_SYNC_CHANNELS = "com.sentinelcam.app.ACTION_SYNC_CHANNELS"
        const val ACTION_NUDGE_NETWORK = "com.sentinelcam.app.ACTION_NUDGE_NETWORK"
        private const val EXTRA_FROM_UI = "extra_from_ui"

        @Volatile
        private var instance: CameraForegroundService? = null

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

        @Volatile
        var activeViewerName: String? = null
            private set

        @Volatile
        var activeViewerRole: String? = null
            private set

        @Volatile
        var activeViewerExpiresAt: Long? = null
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
                try { l.invoke() } catch (_: Throwable) {}
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

        fun ensureRunningSafely(context: Context) {
            try {
                val appCtx = context.applicationContext
                if (isRunning) {
                    instance?.signalingEngine?.nudgeReconnect()
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

        fun syncAccessChannels(context: Context) {
            try {
                if (!isRunning) return
                val running = instance
                if (running != null) {
                    running.syncChannelsHot()
                } else {
                    val intent = Intent(context.applicationContext, CameraForegroundService::class.java).apply {
                        action = ACTION_SYNC_CHANNELS
                    }
                    context.applicationContext.startService(intent)
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

        fun kickActiveViewer() {
            try {
                instance?.kickActiveViewerInternal()
            } catch (_: Throwable) {
            }
        }

        fun sleepCameraFromNotification(@Suppress("UNUSED_PARAMETER") context: Context) {
            try {
                instance?.sleepCameraInternal()
            } catch (_: Throwable) {
            }
        }

        fun stopPortal(context: Context) {
            try {
                val appCtx = context.applicationContext
                KeepAliveReceiver.cancelPulse(appCtx)
                PortalWatchdogJobService.scheduleWatchdog(appCtx)

                isRunning = false
                isStreaming = false
                mqttConnected = false
                activeViewerName = null
                activeViewerRole = null
                activeViewerExpiresAt = null
                activeBrokerLabel = "En pause"

                appCtx.stopService(Intent(appCtx, CameraForegroundService::class.java))
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }
    }
}
