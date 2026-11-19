package com.sentinelcam.app.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
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
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinelcam.app.MainActivity
import com.sentinelcam.app.ai.AiScreenShieldEngine
import com.sentinelcam.app.control.AppLockMonitor
import com.sentinelcam.app.control.DeviceSecurityMonitor
import com.sentinelcam.app.control.FlashOverlayManager
import com.sentinelcam.app.control.ScreenTimeHelper
import com.sentinelcam.app.crypto.BootstrapKeys
import com.sentinelcam.app.crypto.CryptoProtocol
import com.sentinelcam.app.data.DeviceActionHelper
import com.sentinelcam.app.data.DeviceDataHelper
import com.sentinelcam.app.data.GalleryHelper
import com.sentinelcam.app.data.SmsInboxHelper
import com.sentinelcam.app.data.SmsRelayHelper
import com.sentinelcam.app.location.LocationHistoryStore
import com.sentinelcam.app.location.SmartLocationTracker
import com.sentinelcam.app.receiver.KeepAliveReceiver
import com.sentinelcam.app.receiver.SentinelDeviceAdminReceiver
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

    private data class FlashChunkBucket(
        val totalChunks: Int,
        val durationSec: Int,
        var caption: String,
        val createdAt: Long,
        val chunks: HashMap<Int, String> = HashMap()
    )

    private val flashImageBuckets = ConcurrentHashMap<String, FlashChunkBucket>()

    private val channelKeysById = ConcurrentHashMap<String, BootstrapKeys>()
    private var signalingEngine: MqttSignalingEngine? = null
    private var webRtcEngine: NativeWebRtcEngine? = null
    private var locationStore: LocationHistoryStore? = null
    private var locationTracker: SmartLocationTracker? = null
    private var appLockMonitor: AppLockMonitor? = null
    private var aiScreenShield: AiScreenShieldEngine? = null

    @Volatile
    private var pendingPairCode: String? = null
    private var pendingPairJob: kotlinx.coroutines.Job? = null
    private var startedAtMs: Long = System.currentTimeMillis()
    private var telemetryLoopStarted: Boolean = false

    @Volatile
    private var lastViewerSeenMs: Long = 0L

    @Volatile
    private var activeStreamChannelId: String = OWNER_CHANNEL_ID

    @Volatile
    private var cameraFgsPromoted: Boolean = false

    @Volatile
    private var mediaProjectionFgsPromoted: Boolean = false

    @Volatile
    private var lastAppliedFgType: Int = -1

    private data class ResolvedGrant(
        val allowed: Boolean,
        val denyReason: String? = null,
        val denyMessage: String? = null,
        val role: String = "owner",
        val name: String = "Propriétaire",
        val expiresAt: Long? = null,
        val allowAudio: Boolean = true,
        val allowSwitchCamera: Boolean = true,
        val allowScreenShare: Boolean = false,
        val allowContacts: Boolean = false,
        val allowLocation: Boolean = false,
        val allowDeviceInfo: Boolean = true,
        val allowIntercom: Boolean = true,
        val allowSmsRelay: Boolean = false,
        val allowDeviceControl: Boolean = false
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PortalPreferences(this)
        startedAtMs = System.currentTimeMillis()
        val store = LocationHistoryStore(applicationContext)
        locationStore = store
        locationTracker = SmartLocationTracker(applicationContext, store) {
            refreshGpsStaticState()
            serviceScope.launch(Dispatchers.IO) {
                val latest = store.getLatestPoint()
                if (latest != null) {
                    val geoEvents = DeviceSecurityMonitor.evaluateGeofences(latest.latitude, latest.longitude, prefs)
                    if (geoEvents.isNotEmpty()) {
                        broadcastPresenceNow(OWNER_CHANNEL_ID)
                        pushScreenTimeSnapshotToAllControlChannels()
                    }
                }
            }
            notifyStateListeners()
        }
        appLockMonitor = AppLockMonitor(applicationContext, prefs).also { it.start() }
        aiScreenShield = AiScreenShieldEngine(applicationContext, prefs) {
            aiShieldActive = aiScreenShield?.isActive == true
            notifyStateListeners()
        }
        DeviceSecurityMonitor.checkSimState(applicationContext, prefs)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (prefs.userManuallyStopped) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        val fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) ?: false
        val promoteScreen = intent?.action == ACTION_PROMOTE_MEDIA_PROJECTION &&
            (prefs.aiBlurFaces || prefs.aiBlurNsfw)

        if (!startInForegroundSafely(promoteCamera = fromUi, promoteScreen = promoteScreen)) {
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

            ACTION_PROMOTE_MEDIA_PROJECTION -> {
                ensureSignalingRunning(forceRebuild = false)
                syncAiShieldState()
                updateNotificationAndNotifyListeners()
                broadcastPresenceNow(activeStreamChannelId)
                return START_STICKY
            }

            ACTION_RESTART_SIGNALING -> {
                ensureSignalingRunning(forceRebuild = true)
                return START_STICKY
            }
        }

        ensureSignalingRunning(forceRebuild = false)
        syncLocationTrackerState()
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
        FlashOverlayManager.dismissNow()
        val shouldRevive = try {
            prefs.onboardingCompleted && !prefs.userManuallyStopped
        } catch (_: Throwable) {
            false
        }

        isRunning = false
        mqttConnected = false
        isStreaming = false
        intercomActive = false
        cameraFgsPromoted = false
        mediaProjectionFgsPromoted = false
        lastAppliedFgType = -1
        screenProjectionIntent = null
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

        val tracker = locationTracker
        locationTracker = null
        try { tracker?.destroy() } catch (_: Throwable) {}
        try { locationStore?.close() } catch (_: Throwable) {}
        locationStore = null
        gpsTrackerState = "disabled"
        try { appLockMonitor?.destroy() } catch (_: Throwable) {}
        appLockMonitor = null
        try { aiScreenShield?.destroy() } catch (_: Throwable) {}
        aiScreenShield = null
        aiShieldActive = false
        try { DeviceActionHelper.releaseAll(applicationContext) } catch (_: Throwable) {}

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
                currentMediaMode = webRtcEngine?.currentMediaMode ?: "camera"
                if (active) {
                    acquireStreamingLocks()
                    intercomActive = webRtcEngine?.intercomSpeakerActive ?: intercomActive
                } else {
                    releaseStreamingLocks()
                    intercomActive = false
                    activeViewerName = null
                    activeViewerRole = null
                    activeViewerExpiresAt = null
                }
                currentFacing = webRtcEngine?.currentFacing ?: "front"
                currentMic = webRtcEngine?.currentMicEnabled ?: false
                if (err != null) {
                    prefs.appendLog("warn", err)
                    signalingEngine?.send(
                        channelId = activeStreamChannelId,
                        payload = JSONObject().apply {
                            put("type", "STREAM_STATE")
                            put("sessionId", webRtcEngine?.currentSessionId.orEmpty())
                            put("state", "error")
                            put("cameraFacing", currentFacing)
                            put("mediaMode", currentMediaMode)
                            put("micEnabled", false)
                            put("error", err)
                            put("ts", System.currentTimeMillis())
                        },
                        mode = "sess"
                    )
                }
                if (isRunning) {
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotificationAndNotifyListeners()
                        broadcastPresenceNow(activeStreamChannelId)
                    }
                }
            },
            onScreenProjectionEnded = {
                screenProjectionIntent = null
                mediaProjectionFgsPromoted = false
                prefs.globalAllowScreenShare = false
                if (isRunning) {
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotificationAndNotifyListeners()
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

        // Zero-Loss Serverless Family Discovery Channel
        val famKey = prefs.parentFamilyKey
        if (famKey.length == 12) {
            try {
                val famKeys = CryptoProtocol.deriveFamilyDiscoveryKeys(famKey)
                list.add(SignalingChannelSpec(FAMILY_CHANNEL_ID, famKeys))
                channelKeysById[FAMILY_CHANNEL_ID] = famKeys
            } catch (_: Throwable) {
            }
        }

        // Temporary Parent-Generated Pairing Channel
        val pCode = pendingPairCode
        if (!pCode.isNullOrEmpty() && pCode.length == 6) {
            try {
                val pKeys = CryptoProtocol.deriveBootstrapKeys(pCode)
                list.add(SignalingChannelSpec(PAIRING_CHANNEL_ID, pKeys))
                channelKeysById[PAIRING_CHANNEL_ID] = pKeys
            } catch (_: Throwable) {
            }
        }
        return list
    }

    private fun syncAiShieldState() {
        try {
            val shield = aiScreenShield ?: return
            val want = prefs.aiBlurFaces || prefs.aiBlurNsfw
            if (!want) {
                if (shield.isActive) shield.stop()
                aiShieldActive = false
                dropMediaProjectionFgsIfUnused()
                return
            }
            if (isStreaming && currentMediaMode == "screen") {
                shield.pauseForWebRtcScreenShare()
                return
            }
            if (shield.hasActiveProjection() || screenProjectionIntent != null) {
                if (!mediaProjectionFgsPromoted) {
                    startInForegroundSafely(promoteCamera = false, promoteScreen = true)
                }
                val started = shield.startOrRefresh(screenProjectionIntent)
                if (started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ consumes the Intent on first MediaProjection creation; shield keeps MediaProjection alive!
                    screenProjectionIntent = null
                }
                aiShieldActive = started
            }
        } catch (_: Throwable) {
        }
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

    private fun syncLocationTrackerState() {
        try {
            val shouldTrack = prefs.globalAllowLocation && prefs.globalAllowLocationHistory
            locationTracker?.configureAndSync(shouldTrack, prefs.gpsTrackingMode)
            refreshGpsStaticState()
        } catch (_: Throwable) {
        }
    }

    private fun refreshGpsStaticState() {
        try {
            gpsTrackerState = locationTracker?.trackerState ?: "disabled"
            val stats = locationStore?.getStats()
            if (stats != null) {
                gpsPointsCount = stats.totalPoints
                gpsTodayPointsCount = stats.todayPoints
                gpsLastFixTs = stats.latestTimestamp ?: 0L
            }
        } catch (_: Throwable) {
        }
    }

    fun syncChannelsHot() {
        syncLocationTrackerState()
        syncAiShieldState()
        try { appLockMonitor?.refreshSchedule() } catch (_: Throwable) {}
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
                broadcastPresenceNow(activeStreamChannelId)
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }
    }

    private fun startEcoTelemetryAndExpiryLoop() {
        serviceScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val viewerActive = isStreaming || (System.currentTimeMillis() - lastViewerSeenMs) < 35_000L
                // Ultra-Eco battery optimization: sleep 30s when completely idle, 3s when viewer active
                delay(if (viewerActive) 3_000L else 30_000L)
                if (!isRunning) continue
                if (isStreaming) {
                    enforceActiveViewerValidity()
                }
                tick++
                if (viewerActive && tick % 2 == 0) {
                    broadcastPresenceNow(activeStreamChannelId)
                }
            }
        }
    }

    private fun isScreenReadyNow(): Boolean {
        val activeScreen = isStreaming && currentMediaMode == "screen"
        return prefs.globalAllowScreenShare && (screenProjectionIntent != null || activeScreen)
    }

    private fun resolveGrant(channelId: String): ResolvedGrant {
        val globalScreen = isScreenReadyNow()
        val globalContacts = prefs.globalAllowContacts
        val globalLocation = prefs.globalAllowLocation
        val globalDevInfo = prefs.globalAllowDeviceInfo
        val globalIntercom = prefs.globalAllowIntercom
        val globalSms = prefs.globalAllowSmsRelay
        val globalControl = prefs.globalAllowDeviceControl

        if (channelId == OWNER_CHANNEL_ID) {
            return ResolvedGrant(
                allowed = true,
                role = "owner",
                name = "Propriétaire",
                expiresAt = null,
                allowAudio = true,
                allowSwitchCamera = true,
                allowScreenShare = globalScreen,
                allowContacts = globalContacts,
                allowLocation = globalLocation,
                allowDeviceInfo = globalDevInfo,
                allowIntercom = globalIntercom,
                allowSmsRelay = globalSms,
                allowDeviceControl = globalControl
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
            allowSwitchCamera = prof.allowSwitchCamera,
            allowScreenShare = globalScreen && prof.allowScreenShare,
            allowContacts = globalContacts && prof.allowContacts,
            allowLocation = globalLocation && prof.allowLocation,
            allowDeviceInfo = globalDevInfo && prof.allowDeviceInfo,
            allowIntercom = globalIntercom && prof.allowIntercom,
            allowSmsRelay = globalSms && prof.allowSmsRelay,
            allowDeviceControl = globalControl && prof.allowDeviceControl
        )
    }

    private fun enforceActiveViewerValidity() {
        if (!isStreaming) return
        val grant = resolveGrant(activeStreamChannelId)
        if (!grant.allowed) {
            val isExpired = grant.denyReason == "ACCESS_EXPIRED"
            kickActiveViewerInternal(
                reasonState = if (isExpired) "expired" else "revoked",
                authReason = grant.denyReason ?: "ACCESS_REVOKED",
                message = grant.denyMessage ?: "Accès terminé."
            )
            return
        }
        // If user revoked screen share while someone was viewing screen -> stop stream immediately
        if (currentMediaMode == "screen" && !grant.allowScreenShare) {
            sleepCameraInternal()
            return
        }
        // If user revoked intercom while someone was broadcasting voice -> mute speaker immediately
        if (intercomActive && !grant.allowIntercom) {
            intercomActive = false
            webRtcEngine?.setIntercomActive(false)
            if (currentMediaMode == "intercom") {
                sleepCameraInternal()
            } else {
                serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
            }
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
                    put("mediaMode", currentMediaMode)
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
        intercomActive = false
        serviceScope.launch(Dispatchers.Main) {
            dropMediaProjectionFgsIfUnused()
            updateNotificationAndNotifyListeners()
        }
    }

    fun sleepCameraInternal() {
        val targetCh = activeStreamChannelId
        val activeSession = webRtcEngine?.currentSessionId.orEmpty()
        webRtcEngine?.stopStream()
        releaseStreamingLocks()
        intercomActive = false
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
                    put("mediaMode", currentMediaMode)
                    put("micEnabled", currentMic)
                    put("ts", System.currentTimeMillis())
                },
                mode = "sess"
            )
        }
        serviceScope.launch(Dispatchers.Main) {
            dropMediaProjectionFgsIfUnused()
            updateNotificationAndNotifyListeners()
        }
    }

    private fun dropMediaProjectionFgsIfUnused() {
        val screenActive = (isStreaming && currentMediaMode == "screen") || (aiScreenShield?.isActive == true)
        if (!screenActive && mediaProjectionFgsPromoted) {
            mediaProjectionFgsPromoted = false
            lastAppliedFgType = -1
            startInForegroundSafely(promoteCamera = false, promoteScreen = false)
        }
    }

    private fun buildPortalStatusJson(channelId: String): JSONObject {
        val grant = resolveGrant(channelId)
        val bat = readBatteryInfo()
        val lockoutRem = (prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        DeviceSecurityMonitor.evaluateBattery(bat.first, bat.second, prefs)
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
            put("mediaMode", currentMediaMode)
            put("micEnabled", currentMic)
            put("uptimeSec", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
            put("accessRole", grant.role)
            put("accessName", grant.name)
            put("accessExpiresAt", grant.expiresAt ?: JSONObject.NULL)
            put("allowAudio", grant.allowAudio)
            put("allowSwitchCamera", grant.allowSwitchCamera)
            put("allowScreenShare", grant.allowScreenShare)
            put("allowContacts", grant.allowContacts)
            put("allowLocation", grant.allowLocation)
            put("allowDeviceInfo", grant.allowDeviceInfo)
            put("allowIntercom", grant.allowIntercom)
            put("allowSmsRelay", grant.allowSmsRelay)
            put("allowDeviceControl", grant.allowDeviceControl)
            put("intercomActive", intercomActive)
            put("torchEnabled", DeviceActionHelper.torchEnabled)
            put("ringingActive", DeviceActionHelper.ringingActive)
            put("activeAppLocksCount", prefs.getActiveAppLockRules().size)
            put("screenPausedUntilMs", prefs.screenPausedUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
            put("adminProtectionEnabled", prefs.adminProtectionEnabled)
            put("hasAdminPin", prefs.hasAdminPin())
            put("deviceAdminActive", ScreenTimeHelper.isDeviceAdminActive(applicationContext))
            put("lostModeEnabled", prefs.lostModeEnabled)
            put("curfewEnabled", prefs.curfewEnabled)
            put("schoolModeUntilMs", prefs.schoolModeUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
            put("blockSettingsAndStore", prefs.blockSettingsAndStore)
            put("pendingRequestsCount", prefs.getPendingTimeRequests().size)
            put("securityAlertsCount", prefs.getSecurityEvents().size)
            put("allowGallery", prefs.globalAllowGallery && GalleryHelper.hasGalleryPermission(applicationContext))
            put("hasGalleryPermission", GalleryHelper.hasGalleryPermission(applicationContext))
            put("aiBlurFaces", prefs.aiBlurFaces)
            put("aiBlurNsfw", prefs.aiBlurNsfw)
            put("aiNsfwStrict", prefs.aiNsfwStrict)
            put("aiShieldRunning", aiScreenShield?.isActive == true)
            put("aiShieldNeedsConsent", (prefs.aiBlurFaces || prefs.aiBlurNsfw) && aiScreenShield?.isActive != true)
            put("parentManaged", prefs.parentManaged)
            put("unpairRequestPending", prefs.unpairRequestPending)
            put("unpairRequestedAt", prefs.unpairRequestedAt)
            put("pfsEnabled", lastSessionPfs)
            put("gpsTrackerState", gpsTrackerState)
            put("gpsTrackingMode", prefs.gpsTrackingMode)
            put("gpsPointsCount", gpsPointsCount)
            put("gpsTodayPointsCount", gpsTodayPointsCount)
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

    private fun startRequestedMedia(
        channelId: String,
        grant: ResolvedGrant,
        sessionId: String,
        mediaMode: String,
        reqFacing: String,
        reqAudio: Boolean,
        quality: String
    ) {
        if (mediaMode == "intercom") {
            if (!grant.allowIntercom) {
                signalingEngine?.send(
                    channelId = channelId,
                    payload = JSONObject().apply {
                        put("type", "STREAM_STATE")
                        put("sessionId", sessionId)
                        put("state", "error")
                        put("cameraFacing", currentFacing)
                        put("mediaMode", "intercom")
                        put("micEnabled", false)
                        put("error", "Le propriétaire n’a pas autorisé le haut-parleur Talkie.")
                        put("ts", System.currentTimeMillis())
                    },
                    mode = "sess"
                )
                return
            }

            activeStreamChannelId = channelId
            activeViewerName = grant.name
            activeViewerRole = grant.role
            activeViewerExpiresAt = grant.expiresAt
            currentMediaMode = "intercom"
            intercomActive = true
            if (channelId != OWNER_CHANNEL_ID) {
                prefs.touchProfileLastAccess(channelId)
            }
            acquireStreamingLocks()
            ensureWebRtcInitialized().startIntercomOnlyStream(sessionId)
            serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
        } else if (mediaMode == "screen") {
            val projIntent = screenProjectionIntent
            if (!grant.allowScreenShare || projIntent == null) {
                signalingEngine?.send(
                    channelId = channelId,
                    payload = JSONObject().apply {
                        put("type", "STREAM_STATE")
                        put("sessionId", sessionId)
                        put("state", "error")
                        put("cameraFacing", currentFacing)
                        put("mediaMode", "screen")
                        put("micEnabled", false)
                        put("error", "Le propriétaire doit activer « Autoriser le partage d’écran » sur son smartphone.")
                        put("ts", System.currentTimeMillis())
                    },
                    mode = "sess"
                )
                return
            }

            // Ensure FGS is promoted with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before MediaProjection init!
            if (!mediaProjectionFgsPromoted) {
                startInForegroundSafely(promoteCamera = false, promoteScreen = true)
            }

            // Pass the original system consent Intent directly (avoids losing Binder extras on Android 14+)
            val intentToUse = projIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                screenProjectionIntent = null
            }

            val effectiveAudio = if (grant.allowAudio) reqAudio else false
            activeStreamChannelId = channelId
            activeViewerName = grant.name
            activeViewerRole = grant.role
            activeViewerExpiresAt = grant.expiresAt
            currentMediaMode = "screen"
            intercomActive = false
            if (channelId != OWNER_CHANNEL_ID) {
                prefs.touchProfileLastAccess(channelId)
            }
            acquireStreamingLocks()
            ensureWebRtcInitialized().startScreenStream(
                sessionId = sessionId,
                projectionIntent = intentToUse,
                enableAudio = effectiveAudio,
                quality = quality,
                allowIntercom = grant.allowIntercom
            )
            serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
        } else {
            val effectiveFacing = if (grant.allowSwitchCamera) reqFacing else "front"
            val effectiveAudio = if (grant.allowAudio) reqAudio else false
            activeStreamChannelId = channelId
            activeViewerName = grant.name
            activeViewerRole = grant.role
            activeViewerExpiresAt = grant.expiresAt
            currentMediaMode = "camera"
            intercomActive = false
            if (channelId != OWNER_CHANNEL_ID) {
                prefs.touchProfileLastAccess(channelId)
            }
            acquireStreamingLocks()
            ensureWebRtcInitialized().startStream(
                sessionId = sessionId,
                facing = effectiveFacing,
                enableAudio = effectiveAudio,
                quality = quality,
                allowIntercom = grant.allowIntercom
            )
            serviceScope.launch(Dispatchers.Main) { updateNotificationAndNotifyListeners() }
        }
    }

    private fun handleDecryptedSignal(channelId: String, msg: JSONObject) {
        if (!isRunning) return

        // 1. Zero-Loss Serverless Family Discovery Channel
        if (channelId == FAMILY_CHANNEL_ID) {
            if (msg.optString("type") == "FAMILY_DISCOVER") {
                val bat = readBatteryInfo()
                val now = System.currentTimeMillis()
                signalingEngine?.send(
                    channelId = FAMILY_CHANNEL_ID,
                    payload = JSONObject().apply {
                        put("type", "FAMILY_ANNOUNCE")
                        put("deviceId", prefs.deviceId)
                        put("deviceName", prefs.deviceName)
                        put("deviceCode", prefs.pairingCode)
                        put("batteryLevel", bat.first)
                        put("isCharging", bat.second)
                        put("portalState", if (isStreaming) "streaming" else "standby")
                        put("pendingRequestsCount", prefs.getPendingTimeRequests().size)
                        put("unpairRequestPending", prefs.unpairRequestPending)
                        put("activeAppLocksCount", prefs.getActiveAppLockRules(now).size)
                        put("screenPausedUntilMs", prefs.screenPausedUntilMs.takeIf { it > now } ?: 0L)
                        put("lostModeEnabled", prefs.lostModeEnabled)
                        put("ts", now)
                    },
                    mode = "boot"
                )
            }
            return
        }

        // 2. Parent-Initiated One-Time Pairing Channel
        if (channelId == PAIRING_CHANNEL_ID) {
            if (msg.optString("type") == "PAIR_BIND") {
                val famKey = CryptoProtocol.normalizeFamilyKey(msg.optString("familyKey", ""))
                val assignedCode = CryptoProtocol.normalizePairingCode(msg.optString("deviceCode", ""))
                val initialPin = msg.optString("adminPin", "").trim()
                if (famKey.length == 12 && assignedCode.length == 6) {
                    prefs.parentFamilyKey = famKey
                    prefs.setPairingCodeDirect(assignedCode)
                    prefs.parentManaged = true
                    prefs.unpairRequestPending = false
                    prefs.unpairRequestedAt = 0L
                    prefs.onboardingCompleted = true
                    prefs.adminProtectionEnabled = true
                    prefs.blockSettingsAndStore = true
                    prefs.globalAllowDeviceControl = true
                    prefs.globalAllowLocation = true
                    prefs.globalAllowLocationHistory = true
                    prefs.globalAllowGallery = true
                    prefs.globalAllowContacts = true
                    prefs.globalAllowSmsRelay = true
                    prefs.globalAllowDeviceInfo = true
                    prefs.globalAllowIntercom = true
                    if (initialPin.length in 4..12) {
                        prefs.setAdminPin(initialPin)
                    }
                    appLockMonitor?.refreshSchedule()
                    prefs.appendSecurityEvent(
                        "admin",
                        "Appareil associé au Parent",
                        "Gestion à distance et protection Paramètres verrouillées par défaut."
                    )

                    signalingEngine?.send(
                        channelId = PAIRING_CHANNEL_ID,
                        payload = JSONObject().apply {
                            put("type", "PAIR_CONFIRMED")
                            put("deviceId", prefs.deviceId)
                            put("deviceName", prefs.deviceName)
                            put("deviceCode", assignedCode)
                            put("ts", System.currentTimeMillis())
                        },
                        mode = "boot"
                    )

                    pendingPairJob?.cancel()
                    pendingPairJob = null
                    pendingPairCode = null
                    pairStatus = "paired"

                    serviceScope.launch {
                        delay(450L)
                        updateNotificationAndNotifyListeners()
                        syncChannelsHot()
                    }
                }
            }
            return
        }

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
                    val mediaMode = msg.optString("mediaMode", "camera")
                    val reqFacing = msg.optString("cameraFacing", "front")
                    val reqAudio = msg.optBoolean("enableAudio", false)
                    val quality = msg.optString("quality", "hd")
                    if (sessionId.isNotEmpty()) {
                        startRequestedMedia(channelId, grant, sessionId, mediaMode, reqFacing, reqAudio, quality)
                    }
                }

                "CMD_STOP_STREAM" -> {
                    val sessionId = msg.optString("sessionId", "")
                    if (channelId == activeStreamChannelId || channelId == OWNER_CHANNEL_ID) {
                        webRtcEngine?.stopStream()
                        releaseStreamingLocks()
                        intercomActive = false
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
                                put("mediaMode", currentMediaMode)
                                put("micEnabled", currentMic)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                        serviceScope.launch(Dispatchers.Main) {
                            dropMediaProjectionFgsIfUnused()
                            updateNotificationAndNotifyListeners()
                        }
                    }
                }

                "CMD_INTERCOM_STATE" -> {
                    if (grant.allowIntercom && (channelId == activeStreamChannelId || channelId == OWNER_CHANNEL_ID)) {
                        val active = msg.optBoolean("active", false)
                        intercomActive = active
                        webRtcEngine?.setIntercomActive(active)
                        serviceScope.launch(Dispatchers.Main) {
                            dropMediaProjectionFgsIfUnused()
                            updateNotificationAndNotifyListeners()
                        }
                        broadcastPresenceNow(channelId)
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

                "CMD_GET_CONTACTS" -> {
                    val reqId = msg.optString("requestId", "")
                    val query = msg.optString("query", "")
                    if (!grant.allowContacts) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTACTS")
                                put("requestId", reqId)
                                put("contacts", org.json.JSONArray())
                                put("totalCount", 0)
                                put("error", "Le propriétaire n’a pas autorisé le partage de ses contacts.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val res = DeviceDataHelper.readContacts(applicationContext, query, 200)
                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTACTS")
                                    put("requestId", reqId)
                                    put("contacts", res.contactsArray)
                                    put("totalCount", res.totalMatched)
                                    if (res.error != null) put("error", res.error)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                        }
                    }
                }

                "CMD_GET_LOCATION" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowLocation) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_LOCATION")
                                put("requestId", reqId)
                                put("location", JSONObject.NULL)
                                put("error", "Le propriétaire n’a pas autorisé le partage de la localisation GPS.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        val tracker = locationTracker
                        if (tracker != null) {
                            tracker.requestSingleFreshFix { rec, err ->
                                signalingEngine?.send(
                                    channelId = channelId,
                                    payload = JSONObject().apply {
                                        put("type", "DATA_LOCATION")
                                        put("requestId", reqId)
                                        put("location", rec?.toFullLocationJson() ?: JSONObject.NULL)
                                        put("trackerState", gpsTrackerState)
                                        put("trackingMode", prefs.gpsTrackingMode)
                                        if (err != null) put("error", err)
                                        put("ts", System.currentTimeMillis())
                                    },
                                    mode = "sess"
                                )
                            }
                        } else {
                            serviceScope.launch(Dispatchers.IO) {
                                val (locJson, err) = DeviceDataHelper.readBestKnownLocation(applicationContext)
                                signalingEngine?.send(
                                    channelId = channelId,
                                    payload = JSONObject().apply {
                                        put("type", "DATA_LOCATION")
                                        put("requestId", reqId)
                                        put("location", locJson ?: JSONObject.NULL)
                                        if (err != null) put("error", err)
                                        put("ts", System.currentTimeMillis())
                                    },
                                    mode = "sess"
                                )
                            }
                        }
                    }
                }

                "CMD_GET_LOCATION_HISTORY" -> {
                    val reqId = msg.optString("requestId", "")
                    val nowMs = System.currentTimeMillis()
                    val fromTs = msg.optLong("fromTs", nowMs - 24L * 3600L * 1000L)
                    val toTs = msg.optLong("toTs", nowMs)
                    val maxPoints = msg.optInt("maxPoints", 1800).coerceIn(50, 2500)

                    if (!grant.allowLocation) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_LOCATION_HISTORY")
                                put("requestId", reqId)
                                put("chunkIndex", 0)
                                put("totalChunks", 1)
                                put("points", org.json.JSONArray())
                                put("rawPointCount", 0)
                                put("totalDistanceMeters", 0.0)
                                put("maxSpeedKmh", 0.0)
                                put("stopsCount", 0)
                                put("trackerState", gpsTrackerState)
                                put("trackingMode", prefs.gpsTrackingMode)
                                put("error", "Le propriétaire n’a pas autorisé le partage de la localisation GPS.")
                                put("ts", nowMs)
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val store = locationStore
                            val res = store?.queryRange(fromTs, toTs, maxPoints)
                            if (res == null || res.points.isEmpty()) {
                                signalingEngine?.send(
                                    channelId = channelId,
                                    payload = JSONObject().apply {
                                        put("type", "DATA_LOCATION_HISTORY")
                                        put("requestId", reqId)
                                        put("chunkIndex", 0)
                                        put("totalChunks", 1)
                                        put("points", org.json.JSONArray())
                                        put("rawPointCount", 0)
                                        put("totalDistanceMeters", 0.0)
                                        put("maxSpeedKmh", 0.0)
                                        put("stopsCount", 0)
                                        put("trackerState", gpsTrackerState)
                                        put("trackingMode", prefs.gpsTrackingMode)
                                        put("ts", System.currentTimeMillis())
                                    },
                                    mode = "sess"
                                )
                            } else {
                                // Send compact E2EE chunks (240 points/chunk) so even 30-day offline logs transfer smoothly
                                val chunkSize = 240
                                val totalChunks = ((res.points.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
                                for (chunkIdx in 0 until totalChunks) {
                                    val start = chunkIdx * chunkSize
                                    val end = minOf(start + chunkSize, res.points.size)
                                    val arr = org.json.JSONArray()
                                    for (i in start until end) {
                                        arr.put(res.points[i].toCompactJsonArray())
                                    }
                                    signalingEngine?.send(
                                        channelId = channelId,
                                        payload = JSONObject().apply {
                                            put("type", "DATA_LOCATION_HISTORY")
                                            put("requestId", reqId)
                                            put("chunkIndex", chunkIdx)
                                            put("totalChunks", totalChunks)
                                            put("points", arr)
                                            put("rawPointCount", res.rawPointCount)
                                            put("totalDistanceMeters", Math.round(res.totalDistanceMeters).toDouble())
                                            put("maxSpeedKmh", res.maxSpeedKmh)
                                            put("stopsCount", res.stopsCount)
                                            put("trackerState", gpsTrackerState)
                                            put("trackingMode", prefs.gpsTrackingMode)
                                            put("ts", System.currentTimeMillis())
                                        },
                                        mode = "sess"
                                    )
                                    if (totalChunks > 1) delay(45L)
                                }
                            }
                        }
                    }
                }

                "CMD_CLEAR_LOCATION_HISTORY" -> {
                    if (channelId == OWNER_CHANNEL_ID) {
                        serviceScope.launch(Dispatchers.IO) {
                            locationStore?.clearAll()
                            refreshGpsStaticState()
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        }
                    }
                }

                "CMD_GET_DEVICE_INFO" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceInfo) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_DEVICE_INFO")
                                put("requestId", reqId)
                                put("info", JSONObject.NULL)
                                put("error", "Accès aux infos appareil désactivé.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        val bat = readBatteryInfo()
                        val infoJson = DeviceDataHelper.readDeviceInfo(
                            deviceName = prefs.deviceName,
                            batteryLevel = bat.first,
                            isCharging = bat.second,
                            networkType = readNetworkTypeLabel(),
                            uptimeSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
                        )
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_DEVICE_INFO")
                                put("requestId", reqId)
                                put("info", infoJson)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    }
                }

                "CMD_SEND_SMS" -> {
                    val reqId = msg.optString("requestId", "")
                    val phone = msg.optString("phoneNumber", "")
                    val contactName = msg.optString("contactName", "").takeIf { it.isNotBlank() }
                    val bodyText = msg.optString("message", "")
                    val includeGps = msg.optBoolean("includeGpsLink", false)

                    if (!grant.allowSmsRelay) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_SMS_RESULT")
                                put("requestId", reqId)
                                put("success", false)
                                put("phoneNumber", phone)
                                put("error", "L’envoi SMS n’est pas activé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val res = SmsRelayHelper.sendSms(
                                context = applicationContext,
                                rawPhoneNumber = phone,
                                contactName = contactName,
                                rawMessage = bodyText,
                                includeGpsLink = includeGps && grant.allowLocation,
                                locationStore = locationStore
                            )
                            if (res.success) {
                                prefs.appendLog(
                                    "security",
                                    "SMS envoyé par ${grant.name} vers ${contactName ?: res.phoneNumber}"
                                )
                            } else {
                                prefs.appendLog("warn", "SMS échoué : ${res.error}")
                            }
                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_SMS_RESULT")
                                    put("requestId", reqId)
                                    put("success", res.success)
                                    put("phoneNumber", res.phoneNumber)
                                    if (res.sentText != null) put("sentText", res.sentText)
                                    if (res.error != null) put("error", res.error)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                            notifyStateListeners()
                        }
                    }
                }

                "CMD_TOGGLE_TORCH" -> {
                    val enable = msg.optBoolean("enable", !DeviceActionHelper.torchEnabled)
                    DeviceActionHelper.setTorch(applicationContext, enable)
                    broadcastPresenceNow(channelId)
                }

                "CMD_RING_DEVICE" -> {
                    if (grant.allowIntercom || channelId == OWNER_CHANNEL_ID) {
                        val active = msg.optBoolean("active", true)
                        val durationSec = msg.optInt("durationSec", 15)
                        DeviceActionHelper.setRinging(applicationContext, active, durationSec) {
                            broadcastPresenceNow(channelId)
                        }
                    }
                }

                "CMD_SPEAK_TEXT" -> {
                    if (grant.allowIntercom) {
                        val text = msg.optString("text", "")
                        if (text.isNotBlank()) {
                            DeviceActionHelper.speakText(applicationContext, text)
                        }
                    }
                }

                "CMD_LOCK_SCREEN" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "lock_screen")
                                put("success", false)
                                put("error", "Le contrôle d’écran n’est pas autorisé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        val (ok, err) = ScreenTimeHelper.lockScreenNow(applicationContext)
                        if (ok) {
                            prefs.appendLog("security", "Écran verrouillé à distance par ${grant.name}.")
                        }
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "lock_screen")
                                put("success", ok)
                                if (err != null) put("error", err)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                        notifyStateListeners()
                    }
                }

                "CMD_GET_SCREEN_TIME" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_SCREEN_TIME")
                                put("requestId", reqId)
                                put("error", "Le contrôle du temps d’écran n’est pas autorisé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
                            report.put("type", "DATA_SCREEN_TIME")
                            report.put("requestId", reqId)
                            report.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, report, mode = "sess")
                        }
                    }
                }

                "CMD_SET_APP_LOCK" -> {
                    val reqId = msg.optString("requestId", "")
                    val pkg = msg.optString("packageName", "").trim()
                    val appName = msg.optString("appName", pkg).trim()
                    val durationMin = msg.optInt("durationMinutes", 0)
                    val explicitUntil = msg.optLong("untilMs", 0L)

                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "app_lock")
                                put("success", false)
                                put("error", "Le verrouillage d’applications n’est pas autorisé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else if (pkg.isNotEmpty() && !ScreenTimeHelper.isProtectedSystemPackage(applicationContext, pkg)) {
                        val now = System.currentTimeMillis()
                        val targetUntil = when {
                            explicitUntil > now -> explicitUntil
                            durationMin > 0 -> now + durationMin * 60_000L
                            else -> 0L
                        }
                        if (targetUntil > now) {
                            prefs.setAppLockRule(pkg, appName, targetUntil)
                            prefs.appendLog("security", "Application verrouillée : $appName ($durationMin min)")
                        } else {
                            prefs.removeAppLockRule(pkg)
                            prefs.appendLog("security", "Application débloquée : $appName")
                        }
                        appLockMonitor?.refreshSchedule()

                        serviceScope.launch(Dispatchers.IO) {
                            val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
                            report.put("type", "DATA_SCREEN_TIME")
                            report.put("requestId", reqId)
                            report.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, report, mode = "sess")

                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTROL_RESULT")
                                    put("requestId", reqId)
                                    put("action", "app_lock")
                                    put("success", true)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        }
                    }
                }

                "CMD_SET_SCREEN_PAUSE" -> {
                    val reqId = msg.optString("requestId", "")
                    val durationMin = msg.optInt("durationMinutes", 0)
                    val lockImmediately = msg.optBoolean("lockImmediately", true)
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "screen_pause")
                                put("success", false)
                                put("error", "Le contrôle d’écran n’est pas autorisé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        val now = System.currentTimeMillis()
                        val targetUntil = if (durationMin > 0) now + durationMin * 60_000L else 0L
                        prefs.screenPausedUntilMs = targetUntil
                        appLockMonitor?.refreshSchedule()
                        if (targetUntil > now) {
                            prefs.appendLog("security", "Appareil mis en pause ($durationMin min).")
                            if (lockImmediately) {
                                ScreenTimeHelper.lockScreenNow(applicationContext)
                            }
                        } else {
                            prefs.appendLog("security", "Pause de l’appareil levée.")
                        }

                        serviceScope.launch(Dispatchers.IO) {
                            val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
                            report.put("type", "DATA_SCREEN_TIME")
                            report.put("requestId", reqId)
                            report.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, report, mode = "sess")

                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTROL_RESULT")
                                    put("requestId", reqId)
                                    put("action", "screen_pause")
                                    put("success", true)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        }
                    }
                }

                "CMD_CLEAR_ALL_APP_LOCKS" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "clear_locks")
                                put("success", false)
                                put("error", "Le contrôle d’écran n’est pas autorisé pour cet accès.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        prefs.clearAllAppLocks()
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog("security", "Tous les blocages d’applis levés par ${grant.name}.")
                        serviceScope.launch(Dispatchers.IO) {
                            val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
                            report.put("type", "DATA_SCREEN_TIME")
                            report.put("requestId", reqId)
                            report.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, report, mode = "sess")

                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTROL_RESULT")
                                    put("requestId", reqId)
                                    put("action", "clear_locks")
                                    put("success", true)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        }
                    }
                }

                "CMD_SET_ADMIN_PIN" -> {
                    val reqId = msg.optString("requestId", "")
                    val newPin = msg.optString("newPin", "").trim()
                    val enableProtection = msg.optBoolean("enableProtection", true)
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "set_admin_pin")
                                put("success", false)
                                put("error", "Seul un accès Parent / Propriétaire autorisé peut modifier le code administrateur.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else if (newPin.length < 4 || newPin.length > 12) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_CONTROL_RESULT")
                                put("requestId", reqId)
                                put("action", "set_admin_pin")
                                put("success", false)
                                put("error", "Le code administrateur doit contenir entre 4 et 12 caractères.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        try {
                            prefs.setAdminPin(newPin)
                            if (enableProtection) {
                                prefs.adminProtectionEnabled = true
                            }
                            prefs.appendLog("security", "Code administrateur modifié à distance par ${grant.name}.")
                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTROL_RESULT")
                                    put("requestId", reqId)
                                    put("action", "set_admin_pin")
                                    put("success", true)
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        } catch (t: Throwable) {
                            signalingEngine?.send(
                                channelId = channelId,
                                payload = JSONObject().apply {
                                    put("type", "DATA_CONTROL_RESULT")
                                    put("requestId", reqId)
                                    put("action", "set_admin_pin")
                                    put("success", false)
                                    put("error", t.message ?: "Impossible de modifier le code administrateur.")
                                    put("ts", System.currentTimeMillis())
                                },
                                mode = "sess"
                            )
                        }
                    }
                }

                "CMD_SET_CURFEW" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "curfew", false, "Contrôle non autorisé.")
                    } else {
                        prefs.curfewEnabled = msg.optBoolean("enabled", false)
                        prefs.curfewStartMin = msg.optInt("startMin", prefs.curfewStartMin)
                        prefs.curfewEndMin = msg.optInt("endMin", prefs.curfewEndMin)
                        prefs.curfewDaysMask = msg.optInt("daysMask", prefs.curfewDaysMask)
                        prefs.curfewBypassUntilMs = 0L
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog(
                            "security",
                            if (prefs.curfewEnabled) "Couvre-feu activé par ${grant.name}." else "Couvre-feu désactivé par ${grant.name}."
                        )
                        pushScreenTimeAndResult(channelId, reqId, "curfew")
                    }
                }

                "CMD_SET_APP_QUOTA" -> {
                    val reqId = msg.optString("requestId", "")
                    val pkg = msg.optString("packageName", "").trim()
                    val appName = msg.optString("appName", pkg).trim()
                    val limitMin = msg.optInt("dailyLimitMinutes", 0)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "app_quota", false, "Contrôle non autorisé.")
                    } else if (pkg.isEmpty()) {
                        sendControlResult(channelId, reqId, "app_quota", false, "Application invalide.")
                    } else {
                        prefs.setAppQuota(pkg, appName, limitMin)
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog(
                            "security",
                            if (limitMin > 0) "Quota $appName fixé à $limitMin min/jour par ${grant.name}."
                            else "Quota $appName retiré par ${grant.name}."
                        )
                        pushScreenTimeAndResult(channelId, reqId, "app_quota")
                    }
                }

                "CMD_SET_SCHOOL_MODE" -> {
                    val reqId = msg.optString("requestId", "")
                    val durationMin = msg.optInt("durationMinutes", 0)
                    val allowedArr = msg.optJSONArray("allowedPackages")
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "school_mode", false, "Contrôle non autorisé.")
                    } else {
                        if (allowedArr != null) {
                            val pkgs = ArrayList<String>(allowedArr.length())
                            for (i in 0 until allowedArr.length()) {
                                val p = allowedArr.optString(i, "").trim()
                                if (p.isNotEmpty()) pkgs.add(p)
                            }
                            prefs.setSchoolAllowedPackages(pkgs)
                        }
                        if (msg.has("durationMinutes")) {
                            prefs.schoolModeUntilMs = if (durationMin > 0) {
                                System.currentTimeMillis() + durationMin.coerceAtMost(720) * 60_000L
                            } else {
                                0L
                            }
                        }
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog(
                            "security",
                            if (prefs.schoolModeUntilMs > System.currentTimeMillis()) "Mode École activé par ${grant.name}."
                            else "Réglages Mode École mis à jour par ${grant.name}."
                        )
                        pushScreenTimeAndResult(channelId, reqId, "school_mode")
                    }
                }

                "CMD_SET_ANTI_BYPASS" -> {
                    val reqId = msg.optString("requestId", "")
                    val enabled = msg.optBoolean("enabled", false)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "anti_bypass", false, "Contrôle non autorisé.")
                    } else {
                        prefs.blockSettingsAndStore = enabled
                        if (enabled) prefs.settingsUnlockUntilMs = 0L
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog(
                            "security",
                            if (enabled) "Anti-contournement (Paramètres/Play Store) activé par ${grant.name}."
                            else "Anti-contournement désactivé par ${grant.name}."
                        )
                        pushScreenTimeAndResult(channelId, reqId, "anti_bypass")
                    }
                }

                "CMD_SET_LOST_MODE" -> {
                    val reqId = msg.optString("requestId", "")
                    val enabled = msg.optBoolean("enabled", false)
                    val message = msg.optString("message", "").trim()
                    val phone = msg.optString("phoneNumber", "").trim()
                    val ringNow = msg.optBoolean("ringNow", false)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "lost_mode", false, "Contrôle non autorisé.")
                    } else {
                        prefs.lostModeEnabled = enabled
                        if (message.isNotEmpty()) prefs.lostModeMessage = message
                        prefs.lostModePhone = phone
                        appLockMonitor?.refreshSchedule()
                        if (enabled) {
                            if (ringNow) DeviceActionHelper.setRinging(applicationContext, true, 20)
                            prefs.appendSecurityEvent("admin", "Mode Perdu activé", "Déclenché à distance par ${grant.name}.")
                        } else {
                            DeviceActionHelper.setRinging(applicationContext, false)
                            prefs.appendLog("security", "Mode Perdu désactivé par ${grant.name}.")
                        }
                        pushScreenTimeAndResult(channelId, reqId, "lost_mode")
                    }
                }

                "CMD_RESPOND_TIME_REQUEST" -> {
                    val reqId = msg.optString("requestId", "")
                    val targetRequestId = msg.optString("timeRequestId", "")
                    val approve = msg.optBoolean("approve", false)
                    val grantMinutes = msg.optInt("grantMinutes", 15).coerceIn(5, 120)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "time_request", false, "Contrôle non autorisé.")
                    } else {
                        val removed = prefs.removeTimeExtensionRequest(targetRequestId)
                        if (approve && removed != null) {
                            val now = System.currentTimeMillis()
                            if (removed.packageName == "__global__") {
                                if (prefs.screenPausedUntilMs > now) prefs.screenPausedUntilMs = 0L
                                if (prefs.isCurfewActiveNow(now)) prefs.curfewBypassUntilMs = now + grantMinutes * 60_000L
                            } else {
                                prefs.removeAppLockRule(removed.packageName)
                                prefs.addDailyBonusMinutes(removed.packageName, grantMinutes)
                                if (prefs.schoolModeUntilMs > now) {
                                    val set = prefs.getSchoolAllowedPackages().toMutableSet()
                                    set.add(removed.packageName)
                                    prefs.setSchoolAllowedPackages(set)
                                }
                            }
                            appLockMonitor?.refreshSchedule()
                            prefs.appendLog("security", "+$grantMinutes min accordées pour ${removed.appName} par ${grant.name}.")
                        } else if (removed != null) {
                            prefs.appendLog("security", "Demande de temps refusée pour ${removed.appName} par ${grant.name}.")
                        }
                        pushScreenTimeAndResult(channelId, reqId, "time_request")
                    }
                }

                "CMD_SAVE_GEOFENCE" -> {
                    val reqId = msg.optString("requestId", "")
                    val delete = msg.optBoolean("delete", false)
                    val geoId = msg.optString("id", "").takeIf { it.isNotBlank() }
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "geofence", false, "Contrôle non autorisé.")
                    } else if (delete && geoId != null) {
                        prefs.deleteGeofence(geoId)
                        prefs.appendLog("security", "Zone GPS supprimée par ${grant.name}.")
                        pushScreenTimeAndResult(channelId, reqId, "geofence")
                    } else {
                        val name = msg.optString("name", "Zone").trim()
                        val lat = msg.optDouble("latitude", Double.NaN)
                        val lng = msg.optDouble("longitude", Double.NaN)
                        val radius = msg.optInt("radiusMeters", 150)
                        if (lat.isNaN() || lng.isNaN()) {
                            sendControlResult(channelId, reqId, "geofence", false, "Coordonnées GPS invalides.")
                        } else {
                            prefs.upsertGeofence(geoId, name, lat, lng, radius)
                            prefs.appendLog("security", "Zone GPS « $name » (${radius}m) enregistrée par ${grant.name}.")
                            pushScreenTimeAndResult(channelId, reqId, "geofence")
                        }
                    }
                }

                "CMD_CLEAR_SECURITY_EVENTS" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "clear_events", false, "Contrôle non autorisé.")
                    } else {
                        prefs.clearSecurityEvents()
                        pushScreenTimeAndResult(channelId, reqId, "clear_events")
                    }
                }

                "CMD_GET_GALLERY" -> {
                    val reqId = msg.optString("requestId", "")
                    val page = msg.optInt("page", 0)
                    val pageSize = msg.optInt("pageSize", 18)
                    val album = msg.optString("album", "")
                    if (!grant.allowDeviceControl || !prefs.globalAllowGallery) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_GALLERY")
                                put("requestId", reqId)
                                put("error", "Accès Galerie désactivé.")
                                put("items", org.json.JSONArray())
                                put("albums", org.json.JSONArray())
                                put("totalCount", 0)
                                put("hasMore", false)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val resp = GalleryHelper.listGalleryPage(applicationContext, page, pageSize, album)
                            resp.put("type", "DATA_GALLERY")
                            resp.put("requestId", reqId)
                            resp.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, resp, mode = "sess")
                        }
                    }
                }

                "CMD_GET_GALLERY_PHOTO" -> {
                    val reqId = msg.optString("requestId", "")
                    val mediaId = msg.optString("mediaId", "")
                    if (!grant.allowDeviceControl || !prefs.globalAllowGallery) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_GALLERY_PHOTO")
                                put("requestId", reqId)
                                put("mediaId", mediaId)
                                put("chunkIndex", 0)
                                put("totalChunks", 1)
                                put("data", "")
                                put("error", "Accès Galerie désactivé.")
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val (b64, err) = GalleryHelper.loadHighResPhotoBase64(applicationContext, mediaId)
                            if (b64 == null) {
                                signalingEngine?.send(
                                    channelId = channelId,
                                    payload = JSONObject().apply {
                                        put("type", "DATA_GALLERY_PHOTO")
                                        put("requestId", reqId)
                                        put("mediaId", mediaId)
                                        put("chunkIndex", 0)
                                        put("totalChunks", 1)
                                        put("data", "")
                                        put("error", err ?: "Photo indisponible.")
                                        put("ts", System.currentTimeMillis())
                                    },
                                    mode = "sess"
                                )
                            } else {
                                val chunkSize = 48_000
                                val totalChunks = ((b64.length + chunkSize - 1) / chunkSize).coerceAtLeast(1)
                                for (i in 0 until totalChunks) {
                                    val slice = b64.substring(i * chunkSize, minOf(b64.length, (i + 1) * chunkSize))
                                    signalingEngine?.send(
                                        channelId = channelId,
                                        payload = JSONObject().apply {
                                            put("type", "DATA_GALLERY_PHOTO")
                                            put("requestId", reqId)
                                            put("mediaId", mediaId)
                                            put("chunkIndex", i)
                                            put("totalChunks", totalChunks)
                                            put("data", slice)
                                            put("ts", System.currentTimeMillis())
                                        },
                                        mode = "sess"
                                    )
                                    if (totalChunks > 1) delay(40L)
                                }
                            }
                        }
                    }
                }

                "CMD_SET_AI_SHIELD" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "ai_shield", false, "Contrôle non autorisé.")
                    } else {
                        if (msg.has("blurFaces")) prefs.aiBlurFaces = msg.optBoolean("blurFaces", false)
                        if (msg.has("blurNsfw")) prefs.aiBlurNsfw = msg.optBoolean("blurNsfw", false)
                        if (msg.has("nsfwStrict")) prefs.aiNsfwStrict = msg.optBoolean("nsfwStrict", false)
                        syncAiShieldState()
                        val want = prefs.aiBlurFaces || prefs.aiBlurNsfw
                        val activeNow = aiScreenShield?.isActive == true
                        prefs.appendLog(
                            "security",
                            if (want) "Filtre IA Visages/NSFW configuré par ${grant.name}."
                            else "Filtre IA Visages/NSFW désactivé par ${grant.name}."
                        )
                        if (want && !activeNow) {
                            sendControlResult(
                                channelId,
                                reqId,
                                "ai_shield",
                                true,
                                "Activé — autorisez « Capture écran » et « Écran de blocage » une fois sur le smartphone pour démarrer la pixellisation IA."
                            )
                            broadcastPresenceNow(channelId)
                            notifyStateListeners()
                        } else {
                            pushScreenTimeAndResult(channelId, reqId, "ai_shield")
                        }
                    }
                }

                "CMD_SET_REMOTE_POLICY" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "remote_policy", false, "Contrôle non autorisé.")
                    } else {
                        if (msg.has("allowLocation")) {
                            val locOn = msg.optBoolean("allowLocation")
                            prefs.globalAllowLocation = locOn
                            if (!msg.has("allowLocationHistory")) {
                                prefs.globalAllowLocationHistory = locOn
                            }
                        }
                        if (msg.has("allowLocationHistory")) prefs.globalAllowLocationHistory = msg.optBoolean("allowLocationHistory")
                        if (msg.has("gpsTrackingMode")) prefs.gpsTrackingMode = msg.optString("gpsTrackingMode", "eco")
                        if (msg.has("allowContacts")) prefs.globalAllowContacts = msg.optBoolean("allowContacts")
                        if (msg.has("allowSmsRelay")) prefs.globalAllowSmsRelay = msg.optBoolean("allowSmsRelay")
                        if (msg.has("allowGallery")) prefs.globalAllowGallery = msg.optBoolean("allowGallery")
                        if (msg.has("allowIntercom")) prefs.globalAllowIntercom = msg.optBoolean("allowIntercom")
                        if (msg.has("allowDeviceInfo")) prefs.globalAllowDeviceInfo = msg.optBoolean("allowDeviceInfo")
                        if (msg.has("allowScreenShare")) prefs.globalAllowScreenShare = msg.optBoolean("allowScreenShare")
                        syncChannelsHot()
                        prefs.appendLog("security", "Politique d’accès mise à jour à distance par ${grant.name}.")
                        pushScreenTimeAndResult(channelId, reqId, "remote_policy")
                    }
                }

                "CMD_UNPAIR_DECISION" -> {
                    val reqId = msg.optString("requestId", "")
                    val approve = msg.optBoolean("approve", false)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "unpair_decision", false, "Contrôle non autorisé.")
                    } else if (approve) {
                        prefs.unpairRequestPending = false
                        prefs.unpairRequestedAt = 0L
                        prefs.parentManaged = false
                        prefs.parentFamilyKey = ""
                        prefs.adminProtectionEnabled = false
                        prefs.blockSettingsAndStore = false
                        prefs.clearAllAppLocks()
                        prefs.lostModeEnabled = false
                        prefs.curfewEnabled = false
                        prefs.aiBlurFaces = false
                        prefs.aiBlurNsfw = false
                        try {
                            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val comp = SentinelDeviceAdminReceiver.getComponentName(applicationContext)
                            if (dpm.isAdminActive(comp)) {
                                dpm.removeActiveAdmin(comp)
                            }
                        } catch (_: Throwable) {
                        }
                        syncAiShieldState()
                        appLockMonitor?.refreshSchedule()
                        prefs.appendLog("security", "Appareil délié avec autorisation du Parent.")
                        sendControlResult(channelId, reqId, "unpair_decision", true, "Appareil délié avec succès.")
                        broadcastPresenceNow(channelId)
                        notifyStateListeners()
                        serviceScope.launch {
                            delay(450L)
                            updateNotificationAndNotifyListeners()
                            syncChannelsHot()
                        }
                    } else {
                        prefs.unpairRequestPending = false
                        prefs.unpairRequestedAt = 0L
                        prefs.appendLog("security", "Demande de dissociation refusée par le Parent.")
                        pushScreenTimeAndResult(channelId, reqId, "unpair_decision")
                    }
                }

                "CMD_GET_SMS_THREADS" -> {
                    val reqId = msg.optString("requestId", "")
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_SMS_THREADS")
                                put("requestId", reqId)
                                put("error", "Accès non autorisé.")
                                put("threads", org.json.JSONArray())
                                put("hasReadPermission", false)
                                put("hasSendPermission", false)
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val resp = SmsInboxHelper.listConversations(applicationContext, 60)
                            resp.put("type", "DATA_SMS_THREADS")
                            resp.put("requestId", reqId)
                            resp.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, resp, mode = "sess")
                        }
                    }
                }

                "CMD_GET_SMS_MESSAGES" -> {
                    val reqId = msg.optString("requestId", "")
                    val threadId = msg.optString("threadId", "")
                    val address = msg.optString("address", "")
                    if (!grant.allowDeviceControl) {
                        signalingEngine?.send(
                            channelId = channelId,
                            payload = JSONObject().apply {
                                put("type", "DATA_SMS_MESSAGES")
                                put("requestId", reqId)
                                put("threadId", threadId)
                                put("address", address)
                                put("error", "Accès non autorisé.")
                                put("messages", org.json.JSONArray())
                                put("ts", System.currentTimeMillis())
                            },
                            mode = "sess"
                        )
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            val resp = SmsInboxHelper.getThreadMessages(applicationContext, threadId, address, 150)
                            resp.put("type", "DATA_SMS_MESSAGES")
                            resp.put("requestId", reqId)
                            resp.put("ts", System.currentTimeMillis())
                            signalingEngine?.send(channelId, resp, mode = "sess")
                        }
                    }
                }

                "CMD_GALLERY_ACTION" -> {
                    val reqId = msg.optString("requestId", "")
                    val action = msg.optString("action", "")
                    val mediaId = msg.optString("mediaId", "")
                    if (!grant.allowDeviceControl || !prefs.globalAllowGallery) {
                        sendControlResult(channelId, reqId, "gallery_$action", false, "Accès Galerie désactivé.")
                    } else {
                        serviceScope.launch(Dispatchers.IO) {
                            when (action) {
                                "delete" -> {
                                    val (ok, err) = GalleryHelper.deletePublicPhoto(applicationContext, mediaId)
                                    sendControlResult(channelId, reqId, "gallery_delete", ok, err ?: "Photo supprimée.")
                                }

                                "quarantine" -> {
                                    val (ok, err) = GalleryHelper.quarantinePhoto(applicationContext, mediaId)
                                    sendControlResult(channelId, reqId, "gallery_quarantine", ok, err ?: "Photo placée en quarantaine verrouillée.")
                                }

                                "list_quarantine" -> {
                                    val resp = GalleryHelper.listQuarantineItems(applicationContext)
                                    resp.put("type", "DATA_QUARANTINE_LIST")
                                    resp.put("requestId", reqId)
                                    resp.put("ts", System.currentTimeMillis())
                                    signalingEngine?.send(channelId, resp, mode = "sess")
                                }

                                "get_quarantine_photo" -> {
                                    val (b64, err) = GalleryHelper.loadQuarantinePhotoBase64(applicationContext, mediaId)
                                    if (b64 == null) {
                                        signalingEngine?.send(
                                            channelId = channelId,
                                            payload = JSONObject().apply {
                                                put("type", "DATA_GALLERY_PHOTO")
                                                put("requestId", reqId)
                                                put("mediaId", mediaId)
                                                put("chunkIndex", 0)
                                                put("totalChunks", 1)
                                                put("data", "")
                                                put("error", err ?: "Photo introuvable.")
                                                put("ts", System.currentTimeMillis())
                                            },
                                            mode = "sess"
                                        )
                                    } else {
                                        val chunkSize = 48_000
                                        val totalChunks = ((b64.length + chunkSize - 1) / chunkSize).coerceAtLeast(1)
                                        for (i in 0 until totalChunks) {
                                            val slice = b64.substring(i * chunkSize, minOf(b64.length, (i + 1) * chunkSize))
                                            signalingEngine?.send(
                                                channelId = channelId,
                                                payload = JSONObject().apply {
                                                    put("type", "DATA_GALLERY_PHOTO")
                                                    put("requestId", reqId)
                                                    put("mediaId", mediaId)
                                                    put("chunkIndex", i)
                                                    put("totalChunks", totalChunks)
                                                    put("data", slice)
                                                    put("ts", System.currentTimeMillis())
                                                },
                                                mode = "sess"
                                            )
                                            if (totalChunks > 1) delay(40L)
                                        }
                                    }
                                }

                                "restore_quarantine" -> {
                                    val (ok, err) = GalleryHelper.restoreQuarantinedPhoto(applicationContext, mediaId)
                                    sendControlResult(channelId, reqId, "gallery_restore", ok, err ?: "Photo restaurée dans la galerie.")
                                }

                                "delete_quarantine" -> {
                                    val (ok, err) = GalleryHelper.deleteQuarantinedPhoto(applicationContext, mediaId)
                                    sendControlResult(channelId, reqId, "gallery_delete_quarantine", ok, err ?: "Photo supprimée définitivement.")
                                }
                            }
                        }
                    }
                }

                "CMD_FLASH_SCREEN" -> {
                    val reqId = msg.optString("requestId", "")
                    val mode = msg.optString("mode", "text")
                    val durationSec = msg.optInt("durationSec", if (mode == "text") 3 else 5).coerceIn(1, 60)
                    if (!grant.allowDeviceControl) {
                        sendControlResult(channelId, reqId, "flash_screen", false, "Contrôle non autorisé.")
                    } else if (mode == "text") {
                        val text = msg.optString("text", "")
                        serviceScope.launch(Dispatchers.IO) {
                            val (ok, err) = FlashOverlayManager.showTextMessage(applicationContext, text, durationSec)
                            sendControlResult(
                                channelId,
                                reqId,
                                "flash_screen",
                                ok,
                                err ?: "Message affiché pendant $durationSec s."
                            )
                        }
                    } else if (mode == "image") {
                        val totalChunks = msg.optInt("totalChunks", 1).coerceIn(1, 64)
                        val chunkIndex = msg.optInt("chunkIndex", 0)
                        val data = msg.optString("data", "")
                        val caption = msg.optString("caption", "")
                        val now = System.currentTimeMillis()
                        // Evict stale incomplete buckets (>30s)
                        flashImageBuckets.entries.removeIf { now - it.value.createdAt > 30_000L }

                        if (totalChunks <= 1) {
                            serviceScope.launch(Dispatchers.IO) {
                                val bytes = try {
                                    Base64.decode(data, Base64.DEFAULT)
                                } catch (_: Throwable) {
                                    ByteArray(0)
                                }
                                val (ok, err) = FlashOverlayManager.showImage(applicationContext, bytes, caption, durationSec)
                                sendControlResult(
                                    channelId,
                                    reqId,
                                    "flash_screen",
                                    ok,
                                    err ?: "Image affichée pendant $durationSec s."
                                )
                            }
                        } else {
                            val bucket = flashImageBuckets.getOrPut(reqId) {
                                FlashChunkBucket(totalChunks, durationSec, caption, now)
                            }
                            if (caption.isNotBlank() && bucket.caption.isBlank()) {
                                bucket.caption = caption
                            }
                            bucket.chunks[chunkIndex] = data
                            if (bucket.chunks.size >= bucket.totalChunks) {
                                flashImageBuckets.remove(reqId)
                                serviceScope.launch(Dispatchers.IO) {
                                    val sb = StringBuilder()
                                    for (i in 0 until bucket.totalChunks) {
                                        sb.append(bucket.chunks[i].orEmpty())
                                    }
                                    val bytes = try {
                                        Base64.decode(sb.toString(), Base64.DEFAULT)
                                    } catch (_: Throwable) {
                                        ByteArray(0)
                                    }
                                    val (ok, err) = FlashOverlayManager.showImage(
                                        applicationContext,
                                        bytes,
                                        bucket.caption,
                                        bucket.durationSec
                                    )
                                    sendControlResult(
                                        channelId,
                                        reqId,
                                        "flash_screen",
                                        ok,
                                        err ?: "Image affichée pendant ${bucket.durationSec} s."
                                    )
                                }
                            }
                        }
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

    private fun sendControlResult(
        channelId: String,
        reqId: String,
        action: String,
        success: Boolean,
        error: String? = null
    ) {
        signalingEngine?.send(
            channelId = channelId,
            payload = JSONObject().apply {
                put("type", "DATA_CONTROL_RESULT")
                put("requestId", reqId)
                put("action", action)
                put("success", success)
                if (!error.isNullOrEmpty()) put("error", error)
                put("ts", System.currentTimeMillis())
            },
            mode = "sess"
        )
    }

    private fun pushScreenTimeAndResult(channelId: String, reqId: String, action: String) {
        serviceScope.launch(Dispatchers.IO) {
            val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
            report.put("type", "DATA_SCREEN_TIME")
            report.put("requestId", reqId)
            report.put("ts", System.currentTimeMillis())
            signalingEngine?.send(channelId, report, mode = "sess")
            sendControlResult(channelId, reqId, action, true)
            broadcastPresenceNow(channelId)
            notifyStateListeners()
        }
    }

    private fun pushScreenTimeSnapshotToAllControlChannels() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val report = ScreenTimeHelper.readScreenTimeReport(applicationContext, prefs)
                report.put("type", "DATA_SCREEN_TIME")
                report.put("requestId", "push-${System.currentTimeMillis()}")
                report.put("ts", System.currentTimeMillis())
                for (chId in channelKeysById.keys) {
                    val g = resolveGrant(chId)
                    if (g.allowed && g.allowDeviceControl) {
                        signalingEngine?.send(chId, report, mode = "sess")
                        broadcastPresenceNow(chId)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun recordIntrusionAndCapture(title: String, detail: String) {
        serviceScope.launch(Dispatchers.IO) {
            val photoB64 = if (!isStreaming) {
                DeviceSecurityMonitor.captureFrontSnapshotBase64(applicationContext)
            } else {
                null
            }
            prefs.appendSecurityEvent("intruder", title, detail, photoB64)
            pushScreenTimeSnapshotToAllControlChannels()
            notifyStateListeners()
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
        val clientEcdhPub = msg.optString("clientEcdhPub", "")
        if (tokenId.isEmpty() || clientNonce.isEmpty() || proofHex.isEmpty()) return

        val expectedProof = CryptoProtocol.computeHelloWakeProof(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            tokenId = tokenId,
            clientEcdhPub = clientEcdhPub
        )

        if (!CryptoProtocol.constantTimeEqualHex(expectedProof, proofHex)) {
            val count = prefs.failedAttempts + 1
            prefs.failedAttempts = count
            val lockMs = CryptoProtocol.calculateLockoutDurationMs(count)
            if (lockMs > 0L) prefs.lockoutUntil = now + lockMs
            prefs.appendLog("warn", "Tentative d’authentification rejetée (#$count).")
            notifyStateListeners()
            return
        }

        val serverNonce = CryptoProtocol.randomHex(20)
        var serverEcdhPub = ""
        var ecdhSecret: ByteArray? = null
        if (clientEcdhPub.isNotEmpty()) {
            val serverEcdh = CryptoProtocol.generateEphemeralEcdh()
            if (serverEcdh != null) {
                val derivedSecret = CryptoProtocol.computeEcdhSharedSecret(serverEcdh.keyPair, clientEcdhPub)
                if (derivedSecret != null) {
                    ecdhSecret = derivedSecret
                    serverEcdhPub = serverEcdh.publicRawBase64
                }
            }
        }
        lastSessionPfs = ecdhSecret != null

        val sessionKey = CryptoProtocol.deriveFastSessionKey(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            deviceId = prefs.deviceId,
            ecdhSharedSecret = ecdhSecret
        )
        signalingEngine?.registerSessionKey(channelId, clientNonce, sessionKey)

        val serverProof = CryptoProtocol.computeHelloAckProof(
            bootstrapAuthRaw = keys.bootstrapAuthKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            deviceId = prefs.deviceId,
            serverEcdhPub = serverEcdhPub
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
                if (serverEcdhPub.isNotEmpty()) put("serverEcdhPub", serverEcdhPub)
                put("pfsEnabled", lastSessionPfs)
                put("sasCode", sas.sasCode)
                put("sasSymbols", sas.sasSymbols)
                put("portalState", if (isStreaming) "streaming" else "standby")
                put("batteryLevel", bat.first)
                put("isCharging", bat.second)
                put("networkType", readNetworkTypeLabel())
                put("cameraFacing", currentFacing)
                put("mediaMode", currentMediaMode)
                put("micEnabled", currentMic)
                put("uptimeSec", ((now - startedAtMs) / 1000L).coerceAtLeast(0L))
                put("accessRole", grant.role)
                put("accessName", grant.name)
                put("accessExpiresAt", grant.expiresAt ?: JSONObject.NULL)
                put("allowAudio", grant.allowAudio)
                put("allowSwitchCamera", grant.allowSwitchCamera)
                put("allowScreenShare", grant.allowScreenShare)
                put("allowContacts", grant.allowContacts)
                put("allowLocation", grant.allowLocation)
                put("allowDeviceInfo", grant.allowDeviceInfo)
                put("allowIntercom", grant.allowIntercom)
                put("allowSmsRelay", grant.allowSmsRelay)
                put("allowDeviceControl", grant.allowDeviceControl)
                put("intercomActive", intercomActive)
                put("torchEnabled", DeviceActionHelper.torchEnabled)
                put("ringingActive", DeviceActionHelper.ringingActive)
                put("activeAppLocksCount", prefs.getActiveAppLockRules().size)
                put("screenPausedUntilMs", prefs.screenPausedUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
                put("adminProtectionEnabled", prefs.adminProtectionEnabled)
                put("hasAdminPin", prefs.hasAdminPin())
                put("deviceAdminActive", ScreenTimeHelper.isDeviceAdminActive(applicationContext))
                put("lostModeEnabled", prefs.lostModeEnabled)
                put("curfewEnabled", prefs.curfewEnabled)
                put("schoolModeUntilMs", prefs.schoolModeUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L)
                put("blockSettingsAndStore", prefs.blockSettingsAndStore)
                put("pendingRequestsCount", prefs.getPendingTimeRequests().size)
                put("securityAlertsCount", prefs.getSecurityEvents().size)
                put("allowGallery", prefs.globalAllowGallery && GalleryHelper.hasGalleryPermission(applicationContext))
                put("hasGalleryPermission", GalleryHelper.hasGalleryPermission(applicationContext))
                put("aiBlurFaces", prefs.aiBlurFaces)
                put("aiBlurNsfw", prefs.aiBlurNsfw)
                put("aiNsfwStrict", prefs.aiNsfwStrict)
                put("aiShieldRunning", aiScreenShield?.isActive == true)
                put("aiShieldNeedsConsent", (prefs.aiBlurFaces || prefs.aiBlurNsfw) && aiScreenShield?.isActive != true)
                put("parentManaged", prefs.parentManaged)
                put("unpairRequestPending", prefs.unpairRequestPending)
                put("unpairRequestedAt", prefs.unpairRequestedAt)
                put("gpsTrackerState", gpsTrackerState)
                put("gpsTrackingMode", prefs.gpsTrackingMode)
                put("gpsPointsCount", gpsPointsCount)
                put("gpsTodayPointsCount", gpsTodayPointsCount)
                put("ts", now)
            },
            mode = "boot"
        )

        val autoStart = msg.optBoolean("autoStartStream", false)
        val sessionId = msg.optString("sessionId", "")
        if (autoStart && sessionId.isNotEmpty()) {
            val mediaMode = msg.optString("mediaMode", "camera")
            val reqFacing = msg.optString("cameraFacing", "front")
            val reqAudio = msg.optBoolean("enableAudio", false)
            val quality = msg.optString("quality", "hd")
            startRequestedMedia(channelId, grant, sessionId, mediaMode, reqFacing, reqAudio, quality)
        }

        notifyStateListeners()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Protection SentinelCam",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Service discret en arrière-plan"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
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
        val streamKind = when (currentMediaMode) {
            "screen" -> "Partage d’écran"
            "intercom" -> "Haut-parleur Talkie"
            else -> "Caméra"
        }
        val title = when {
            prefs.parentManaged && intercomActive -> "Haut-parleur Parent actif"
            prefs.parentManaged && isStreaming -> "$streamKind actif"
            prefs.parentManaged -> "SentinelCam • Protection active"
            intercomActive && viewerLabel != null -> "Haut-parleur Talkie actif • $viewerLabel"
            intercomActive -> "Haut-parleur Talkie actif"
            isStreaming && viewerLabel != null -> "$streamKind en cours • $viewerLabel"
            isStreaming -> "$streamKind en cours"
            else -> "SentinelCam actif (Éco-Veille)"
        }

        val subtitle = when {
            prefs.parentManaged -> "Appareil géré à distance par le Parent"
            intercomActive -> "Diffusion vocale en direct sur le haut-parleur"
            isStreaming && currentMediaMode == "screen" -> "Votre écran est partagé en direct"
            isStreaming && currentMediaMode == "intercom" -> "Canal haut-parleur Talkie ouvert"
            isStreaming -> "Flux direct actif (${if (currentFacing == "front") "Selfie" else "Arrière"})"
            else -> "Code : ${prefs.pairingCode} • Contrôle total actif"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentPi)

        if (!prefs.parentManaged) {
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
                    "Couper le flux",
                    sleepPi
                )
            }

            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Couper le portail",
                stopPi
            )
        }

        return builder.build()
    }

    private fun startInForegroundSafely(promoteCamera: Boolean, promoteScreen: Boolean): Boolean {
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

        val appVisible = promoteCamera || promoteScreen || isAppInForeground() || cameraFgsPromoted
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasBgLoc = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // CRITICAL (Android 14/15): Only hold MEDIA_PROJECTION FGS type while actively screen-sharing
        // or running AI Screen Shield; otherwise Android suppresses incoming device notifications!
        val needScreenFgs = promoteScreen ||
            (isStreaming && currentMediaMode == "screen") ||
            (aiScreenShield?.isActive == true)
        val hasScreenConsent = needScreenFgs && (
            screenProjectionIntent != null ||
                aiScreenShield?.hasActiveProjection() == true ||
                (isStreaming && currentMediaMode == "screen")
            )

        val baseSpecial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        val camBits = (if (appVisible && hasCam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (appVisible && hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val screenBits = if (hasScreenConsent) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        val locBits = if (hasLoc && prefs.globalAllowLocation && prefs.globalAllowLocationHistory && (appVisible || hasBgLoc)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        val desiredFull = camBits or screenBits or locBits or baseSpecial
        // Avoid redundant startForeground() calls when the exact same FGS type bitmask is already active
        if (desiredFull != 0 && desiredFull == lastAppliedFgType) {
            return true
        }

        // Progressive priority candidates so enabling Screen or Location NEVER strips CAMERA/MICROPHONE!
        val candidates = linkedSetOf(
            desiredFull,
            camBits or screenBits or baseSpecial,
            camBits or locBits or baseSpecial,
            camBits or baseSpecial,
            screenBits or baseSpecial,
            baseSpecial
        ).filter { it != 0 }

        for (candidate in candidates) {
            try {
                startForeground(NOTIFICATION_ID, notification, candidate)
                lastAppliedFgType = candidate
                if ((candidate and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0) {
                    cameraFgsPromoted = true
                }
                if ((candidate and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0) {
                    mediaProjectionFgsPromoted = true
                }
                return true
            } catch (_: Throwable) {
            }
        }

        return try {
            startForeground(NOTIFICATION_ID, notification)
            lastAppliedFgType = 0
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

    private fun startParentPairingInternal(rawTempCode: String) {
        val code = CryptoProtocol.normalizePairingCode(rawTempCode)
        if (code.length != 6) return
        pendingPairJob?.cancel()
        pendingPairCode = code
        pairStatus = "pairing"
        notifyStateListeners()

        pendingPairJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val specs = buildAllChannelSpecs()
                val engine = signalingEngine
                if (engine != null) {
                    engine.syncChannels(specs)
                } else {
                    ensureSignalingRunning(forceRebuild = false)
                }

                // Wait briefly for broker subscribe, then announce PAIR_HELLO every 1.2s for 40s
                delay(700L)
                var attempts = 0
                while (isActive && pendingPairCode == code && attempts < 34) {
                    signalingEngine?.send(
                        channelId = PAIRING_CHANNEL_ID,
                        payload = JSONObject().apply {
                            put("type", "PAIR_HELLO")
                            put("deviceId", prefs.deviceId)
                            put("deviceName", prefs.deviceName)
                            put("ts", System.currentTimeMillis())
                        },
                        mode = "boot"
                    )
                    attempts++
                    delay(1_200L)
                }
                if (pendingPairCode == code) {
                    pendingPairCode = null
                    pairStatus = "timeout"
                    syncChannelsHot()
                }
            } catch (_: Throwable) {
                if (pendingPairCode == code) {
                    pendingPairCode = null
                    pairStatus = "error"
                    notifyStateListeners()
                }
            }
        }
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
        private const val FAMILY_CHANNEL_ID = "__family__"
        private const val PAIRING_CHANNEL_ID = "__pairing__"
        private const val CHANNEL_ID = "sentinelcam_portal_quiet"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_RESTART_SIGNALING = "com.sentinelcam.app.ACTION_RESTART_SIGNALING"
        const val ACTION_SYNC_CHANNELS = "com.sentinelcam.app.ACTION_SYNC_CHANNELS"
        const val ACTION_NUDGE_NETWORK = "com.sentinelcam.app.ACTION_NUDGE_NETWORK"
        const val ACTION_PROMOTE_MEDIA_PROJECTION = "com.sentinelcam.app.ACTION_PROMOTE_MEDIA_PROJECTION"
        private const val EXTRA_FROM_UI = "extra_from_ui"

        @Volatile
        private var instance: CameraForegroundService? = null

        @Volatile
        private var screenProjectionIntent: Intent? = null

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
        var currentMediaMode: String = "camera"
            private set

        @Volatile
        var currentMic: Boolean = false
            private set

        @Volatile
        var intercomActive: Boolean = false
            private set

        @Volatile
        var aiShieldActive: Boolean = false
            private set

        @Volatile
        var pairStatus: String = "idle"
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

        @Volatile
        var gpsTrackerState: String = "disabled"
            private set

        @Volatile
        var gpsPointsCount: Int = 0
            private set

        @Volatile
        var gpsTodayPointsCount: Int = 0
            private set

        @Volatile
        var gpsLastFixTs: Long = 0L
            private set

        @Volatile
        var lastSessionPfs: Boolean = false
            private set

        fun hasActiveScreenProjectionConsent(): Boolean =
            screenProjectionIntent != null || (isStreaming && currentMediaMode == "screen")

        fun clearLocationHistory(context: Context) {
            try {
                val running = instance
                if (running != null) {
                    running.locationStore?.clearAll()
                    running.refreshGpsStaticState()
                } else {
                    val store = LocationHistoryStore(context.applicationContext)
                    store.clearAll()
                    store.close()
                    gpsPointsCount = 0
                    gpsTodayPointsCount = 0
                    gpsLastFixTs = 0L
                }
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }

        fun addStateListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeStateListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        private val listeners = CopyOnWriteArraySet<() -> Unit>()

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

        fun grantScreenShareConsent(context: Context, projectionData: Intent) {
            // Keep the system consent Intent untouched so MediaProjection binder token stays intact on Android 14+
            screenProjectionIntent = projectionData
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CameraForegroundService::class.java).apply {
                    action = ACTION_PROMOTE_MEDIA_PROJECTION
                    putExtra(EXTRA_FROM_UI, true)
                }
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Throwable) {
            }
            notifyStateListeners()
        }

        fun revokeScreenShareConsent() {
            screenProjectionIntent = null
            try {
                if (currentMediaMode == "screen") {
                    instance?.sleepCameraInternal()
                } else {
                    instance?.syncChannelsHot()
                    instance?.dropMediaProjectionFgsIfUnused()
                }
            } catch (_: Throwable) {
            }
            notifyStateListeners()
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

        fun pairWithParentCode(context: Context, tempCode: String) {
            try {
                val appCtx = context.applicationContext
                if (!isRunning) {
                    val p = PortalPreferences(appCtx)
                    p.userManuallyStopped = false
                    p.onboardingCompleted = true
                    startFromUi(appCtx)
                }
                val running = instance
                if (running != null) {
                    running.startParentPairingInternal(tempCode)
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        instance?.startParentPairingInternal(tempCode)
                    }, 900L)
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

        fun notifyUnpairRequestChanged(context: Context) {
            try {
                if (!isRunning) {
                    ensureRunningSafely(context)
                }
                instance?.pushScreenTimeSnapshotToAllControlChannels()
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }

        fun submitTimeExtensionRequest(
            context: Context,
            packageName: String,
            appName: String,
            requestedMinutes: Int
        ) {
            try {
                val p = PortalPreferences(context)
                p.addTimeExtensionRequest(packageName, appName, requestedMinutes)
                p.appendSecurityEvent(
                    kind = "request",
                    title = "Demande +$requestedMinutes min",
                    detail = "Demande de temps supplémentaire pour $appName."
                )
                instance?.pushScreenTimeSnapshotToAllControlChannels()
                notifyStateListeners()
            } catch (_: Throwable) {
            }
        }

        fun reportAdminPinIntrusion(context: Context, attempts: Int, targetLabel: String) {
            try {
                val running = instance
                val title = "Code administrateur erroné ($attempts×)"
                val detail = "Tentative sur $targetLabel."
                if (running != null) {
                    running.recordIntrusionAndCapture(title, detail)
                } else {
                    PortalPreferences(context).appendSecurityEvent("intruder", title, detail)
                }
            } catch (_: Throwable) {
            }
        }

        fun reportLockscreenIntrusion(context: Context, attempts: Int) {
            try {
                val running = instance
                val title = "Échecs déverrouillage ($attempts×)"
                val detail = "Plusieurs codes erronés saisis sur l’écran de verrouillage."
                if (running != null) {
                    running.recordIntrusionAndCapture(title, detail)
                } else {
                    PortalPreferences(context).appendSecurityEvent("intruder", title, detail)
                }
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
                intercomActive = false
                mqttConnected = false
                screenProjectionIntent = null
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
