package com.sentinelcam.app.webrtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.sentinelcam.app.crypto.CryptoProtocol
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors

private object SharedWebRtcRuntime {
    private var initialized = false
    var eglBase: EglBase? = null
        private set
    var factory: PeerConnectionFactory? = null
        private set

    @Synchronized
    fun ensureReady(appContext: Context): Pair<EglBase, PeerConnectionFactory>? {
        if (initialized && eglBase != null && factory != null) {
            return Pair(eglBase!!, factory!!)
        }
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val rootEgl = EglBase.create()
            val encoderFactory = DefaultVideoEncoderFactory(rootEgl.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(rootEgl.eglBaseContext)
            val audioModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            val pcFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            audioModule.release()
            eglBase = rootEgl
            factory = pcFactory
            initialized = true
            Pair(rootEgl, pcFactory)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Thread-safe Hardware WebRTC Engine supporting:
 * 1. Camera2 video streaming (`CameraVideoCapturer`)
 * 2. User-consented Screen Share (`ScreenCapturerAndroid`)
 * 3. One-Way Walkie-Talkie Loudspeaker playback (`Web -> Android Built-in Speaker`) both
 *    during active video sessions and in standalone audio-only mode (0% camera usage).
 */
class NativeWebRtcEngine(
    context: Context,
    private val onLocalOfferReady: (sessionId: String, sdp: String, fingerprint: String) -> Unit,
    private val onLocalIceCandidate: (sessionId: String, candidate: IceCandidate) -> Unit,
    private val onStreamStateChanged: (isStreaming: Boolean, error: String?) -> Unit,
    private val onScreenProjectionEnded: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val rtcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SentinelWebRtcWorker").apply { isDaemon = true }
    }

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    var currentSessionId: String = ""
        private set

    @Volatile
    var currentFacing: String = "front"
        private set

    @Volatile
    var currentMediaMode: String = "camera"
        private set

    @Volatile
    var currentMicEnabled: Boolean = false
        private set

    @Volatile
    var intercomSpeakerActive: Boolean = false
        private set

    @Volatile
    var isStreamingActive: Boolean = false
        private set

    @Volatile
    private var shuttingDown: Boolean = false

    fun startStream(
        sessionId: String,
        facing: String = "front",
        enableAudio: Boolean = false,
        quality: String = "hd",
        allowIntercom: Boolean = true
    ) {
        rtcExecutor.execute {
            startCameraStreamOnWorker(sessionId, facing, enableAudio, quality, allowIntercom)
        }
    }

    fun startScreenStream(
        sessionId: String,
        projectionIntent: Intent,
        enableAudio: Boolean = false,
        quality: String = "hd",
        allowIntercom: Boolean = true
    ) {
        rtcExecutor.execute {
            startScreenStreamOnWorker(sessionId, projectionIntent, enableAudio, quality, allowIntercom)
        }
    }

    fun startIntercomOnlyStream(sessionId: String) {
        rtcExecutor.execute {
            startIntercomOnlyOnWorker(sessionId)
        }
    }

    fun setIntercomActive(active: Boolean) {
        rtcExecutor.execute {
            applyLoudspeakerMode(active)
        }
    }

    private fun applyLoudspeakerMode(active: Boolean) {
        intercomSpeakerActive = active
        // Strictly one-way: mute phone's outbound mic while remote user is speaking to prevent feedback
        try {
            audioTrack?.setEnabled(if (active) false else currentMicEnabled)
        } catch (_: Throwable) {
        }

        try {
            if (active) {
                requestVoiceAudioFocus()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        audioManager.clearCommunicationDevice()
                    } catch (_: Throwable) {
                    }
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
                abandonVoiceAudioFocus()
            }
        } catch (_: Throwable) {
        }
    }

    private fun requestVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun abandonVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startCameraStreamOnWorker(
        sessionId: String,
        facing: String,
        enableAudio: Boolean,
        quality: String,
        allowIntercom: Boolean
    ) {
        stopStreamOnWorker()

        val hasCamPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamPerm) {
            onStreamStateChanged(false, "Permission caméra requise sur le smartphone.")
            return
        }

        val capturer = createCameraCapturer(facing)
        if (capturer == null) {
            onStreamStateChanged(false, "Aucun capteur caméra détecté.")
            return
        }

        currentFacing = facing
        currentMediaMode = "camera"
        val width = if (quality == "sd") 640 else 1280
        val height = if (quality == "sd") 480 else 720
        launchSession(sessionId, capturer, width, height, 30, enableAudio, allowIntercom)
    }

    private fun startScreenStreamOnWorker(
        sessionId: String,
        projectionIntent: Intent,
        enableAudio: Boolean,
        quality: String,
        allowIntercom: Boolean
    ) {
        stopStreamOnWorker()

        val metrics = appContext.resources.displayMetrics
        val rawW = metrics.widthPixels.coerceAtLeast(360)
        val rawH = metrics.heightPixels.coerceAtLeast(640)
        val isLandscape = rawW > rawH

        // Hardware H.264/VP8 encoders on Samsung/Xiaomi/Pixel require 16-pixel aligned dimensions
        val maxShortEdge = if (quality == "sd") 544 else 720
        val scale = if (isLandscape) {
            (maxShortEdge.toFloat() / rawH.toFloat()).coerceAtMost(1.0f)
        } else {
            (maxShortEdge.toFloat() / rawW.toFloat()).coerceAtMost(1.0f)
        }

        val alignedW = (((rawW * scale).toInt() / 16) * 16).coerceIn(320, 1280)
        val alignedH = (((rawH * scale).toInt() / 16) * 16).coerceIn(320, 1600)

        val screenCapturer = ScreenCapturerAndroid(
            projectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    rtcExecutor.execute {
                        if (currentMediaMode == "screen") {
                            stopStreamOnWorker()
                        }
                        onScreenProjectionEnded()
                    }
                }
            }
        )

        currentMediaMode = "screen"
        launchSession(sessionId, screenCapturer, alignedW, alignedH, 24, enableAudio, allowIntercom)
    }

    private fun startIntercomOnlyOnWorker(sessionId: String) {
        stopStreamOnWorker()
        currentMediaMode = "intercom"
        applyLoudspeakerMode(true)
        launchSession(sessionId, capturer = null, width = 0, height = 0, fps = 0, enableAudio = false, allowIntercom = true)
    }

    private fun launchSession(
        sessionId: String,
        capturer: VideoCapturer?,
        width: Int,
        height: Int,
        fps: Int,
        enableAudio: Boolean,
        allowIntercom: Boolean
    ) {
        try {
            val runtime = SharedWebRtcRuntime.ensureReady(appContext)
            if (runtime == null) {
                onStreamStateChanged(false, "Initialisation matérielle WebRTC impossible.")
                return
            }
            val (rootEgl, factory) = runtime

            currentSessionId = sessionId
            currentMicEnabled = enableAudio

            var createdVideoTrack: VideoTrack? = null
            if (capturer != null) {
                videoCapturer = capturer
                val texHelper = SurfaceTextureHelper.create("SentinelCaptureTex", rootEgl.eglBaseContext)
                surfaceTextureHelper = texHelper

                val vSource = factory.createVideoSource(capturer.isScreencast)
                videoSource = vSource
                capturer.initialize(texHelper, appContext, vSource.capturerObserver)
                capturer.startCapture(width, height, fps)

                val vTrack = factory.createVideoTrack("ARDAMSv0", vSource)
                vTrack.setEnabled(true)
                videoTrack = vTrack
                createdVideoTrack = vTrack
            }

            // Local phone mic track only if we are streaming camera/screen and have RECORD_AUDIO
            if (capturer != null) {
                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasAudioPerm) {
                    try {
                        val aSource = factory.createAudioSource(MediaConstraints())
                        audioSource = aSource
                        val aTrack = factory.createAudioTrack("ARDAMSa0", aSource)
                        aTrack.setEnabled(enableAudio && !intercomSpeakerActive)
                        audioTrack = aTrack
                    } catch (_: Throwable) {
                    }
                }
            }

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        rtcExecutor.execute {
                            if (currentSessionId == sessionId) {
                                stopStreamOnWorker()
                            }
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null && currentSessionId == sessionId) {
                        onLocalIceCandidate(sessionId, candidate)
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    // Ensure received remote Walkie-Talkie audio track is enabled for loudspeaker playout
                    stream?.audioTracks?.forEach { t ->
                        try { t.setEnabled(true) } catch (_: Throwable) {}
                    }
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            })

            if (pc == null) {
                stopStreamOnWorker()
                onStreamStateChanged(false, "Impossible de créer la session WebRTC.")
                return
            }
            peerConnection = pc

            createdVideoTrack?.let { vTrack ->
                val sender = pc.addTrack(vTrack, listOf("ARDAMS"))
                if (currentMediaMode == "screen" && sender != null) {
                    try {
                        val params = sender.parameters
                        params.degradationPreference =
                            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                        for (enc in params.encodings) {
                            enc.maxBitrateBps = 1_800_000
                            enc.maxFramerate = 24
                        }
                        sender.parameters = params
                    } catch (_: Throwable) {
                    }
                }
            }

            val localAudio = audioTrack
            if (localAudio != null) {
                pc.addTrack(localAudio, listOf("ARDAMS"))
            } else if (allowIntercom) {
                // Audio-receive-only transceiver so Web can talk directly to Android Loudspeaker!
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }

            val dcInit = DataChannel.Init().apply { ordered = true }
            dataChannel = pc.createDataChannel("sentinel-telemetry", dcInit)

            isStreamingActive = true
            onStreamStateChanged(true, null)

            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(
                    MediaConstraints.KeyValuePair(
                        "OfferToReceiveAudio",
                        if (allowIntercom) "true" else "false"
                    )
                )
            }

            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    rtcExecutor.execute {
                        val activePc = peerConnection ?: return@execute
                        if (currentSessionId != sessionId) return@execute
                        activePc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                val sdp = desc.description
                                val fp = CryptoProtocol.extractSdpFingerprint(sdp)
                                onLocalOfferReady(sessionId, sdp, fp)
                            }
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {}
                        }, desc)
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    rtcExecutor.execute {
                        onStreamStateChanged(false, error ?: "Erreur création SDP")
                    }
                }
                override fun onSetFailure(error: String?) {}
            }, offerConstraints)

        } catch (t: Throwable) {
            stopStreamOnWorker()
            if (currentMediaMode == "screen") {
                onScreenProjectionEnded()
            }
            onStreamStateChanged(
                false,
                if (currentMediaMode == "screen") {
                    "Le propriétaire doit ré-autoriser le partage d’écran sur son smartphone."
                } else {
                    t.message ?: "Erreur ouverture flux média"
                }
            )
        }
    }

    fun applyRemoteAnswer(sessionId: String, sdp: String) {
        rtcExecutor.execute {
            try {
                val pc = peerConnection ?: return@execute
                if (sessionId != currentSessionId) return@execute
                val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, answer)
            } catch (_: Throwable) {
            }
        }
    }

    fun addRemoteIceCandidate(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        rtcExecutor.execute {
            try {
                val pc = peerConnection ?: return@execute
                if (sessionId != currentSessionId) return@execute
                pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            } catch (_: Throwable) {
            }
        }
    }

    fun switchCamera(targetFacing: String) {
        rtcExecutor.execute {
            try {
                val capturer = videoCapturer as? CameraVideoCapturer ?: return@execute
                if (targetFacing == currentFacing) return@execute
                val enumerator = getCameraEnumerator()
                val targetDevice = findCameraDeviceName(enumerator, preferFront = targetFacing == "front")
                    ?: return@execute

                capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        currentFacing = if (isFrontCamera) "front" else "back"
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {}
                }, targetDevice)
            } catch (_: Throwable) {
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        rtcExecutor.execute {
            try {
                currentMicEnabled = enabled
                audioTrack?.setEnabled(enabled && !intercomSpeakerActive)
            } catch (_: Throwable) {
            }
        }
    }

    fun sendTelemetryOverDataChannel(statusJson: JSONObject) {
        rtcExecutor.execute {
            try {
                val dc = dataChannel ?: return@execute
                if (dc.state() != DataChannel.State.OPEN) return@execute
                val bytes = statusJson.toString().toByteArray(Charsets.UTF_8)
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            } catch (_: Throwable) {
            }
        }
    }

    fun stopStream() {
        rtcExecutor.execute {
            stopStreamOnWorker()
        }
    }

    private fun stopStreamOnWorker() {
        val wasStreaming = isStreamingActive
        isStreamingActive = false
        currentSessionId = ""

        if (intercomSpeakerActive) {
            applyLoudspeakerMode(false)
        }

        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        try { videoCapturer?.dispose() } catch (_: Throwable) {}
        videoCapturer = null

        try { dataChannel?.close() } catch (_: Throwable) {}
        dataChannel = null

        try { peerConnection?.close() } catch (_: Throwable) {}
        peerConnection = null

        try { videoTrack?.dispose() } catch (_: Throwable) {}
        videoTrack = null

        try { videoSource?.dispose() } catch (_: Throwable) {}
        videoSource = null

        try { surfaceTextureHelper?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper = null

        try { audioTrack?.dispose() } catch (_: Throwable) {}
        audioTrack = null

        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioSource = null

        if (wasStreaming && !shuttingDown) {
            onStreamStateChanged(false, null)
        }
    }

    fun shutdownEngine() {
        shuttingDown = true
        try {
            rtcExecutor.execute {
                stopStreamOnWorker()
            }
        } catch (_: Throwable) {
        }
    }

    private fun getCameraEnumerator(): CameraEnumerator {
        return try {
            if (Camera2Enumerator.isSupported(appContext)) {
                Camera2Enumerator(appContext)
            } else {
                Camera1Enumerator(true)
            }
        } catch (_: Throwable) {
            Camera1Enumerator(true)
        }
    }

    private fun findCameraDeviceName(enumerator: CameraEnumerator, preferFront: Boolean): String? {
        return try {
            val names = enumerator.deviceNames
            for (name in names) {
                if (preferFront && enumerator.isFrontFacing(name)) return name
                if (!preferFront && enumerator.isBackFacing(name)) return name
            }
            names.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun createCameraCapturer(facing: String): CameraVideoCapturer? {
        return try {
            val enumerator = getCameraEnumerator()
            val deviceName = findCameraDeviceName(enumerator, preferFront = facing == "front") ?: return null
            enumerator.createCapturer(deviceName, null)
        } catch (_: Throwable) {
            null
        }
    }
}
