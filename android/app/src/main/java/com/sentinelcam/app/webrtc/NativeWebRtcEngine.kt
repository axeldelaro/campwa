package com.sentinelcam.app.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Process-wide singleton WebRTC Native Runtime so restarting CameraForegroundService
 * never re-initializes or disposes JNI globals (eliminates SIGSEGV on service restart).
 */
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
 * Thread-safe Headless Hardware WebRTC Capture Engine.
 * All native WebRTC & Camera2 operations are strictly serialized onto a dedicated single-thread
 * executor (`rtcExecutor`) to prevent JNI race conditions and re-entrant callback crashes.
 */
class NativeWebRtcEngine(
    context: Context,
    private val onLocalOfferReady: (sessionId: String, sdp: String, fingerprint: String) -> Unit,
    private val onLocalIceCandidate: (sessionId: String, candidate: IceCandidate) -> Unit,
    private val onStreamStateChanged: (isStreaming: Boolean, error: String?) -> Unit
) {
    private val appContext = context.applicationContext
    private val rtcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SentinelWebRtcWorker").apply { isDaemon = true }
    }

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: CameraVideoCapturer? = null
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
    var currentMicEnabled: Boolean = false
        private set

    @Volatile
    var isStreamingActive: Boolean = false
        private set

    fun startStream(
        sessionId: String,
        facing: String = "front",
        enableAudio: Boolean = false,
        quality: String = "hd"
    ) {
        rtcExecutor.execute {
            startStreamOnWorker(sessionId, facing, enableAudio, quality)
        }
    }

    private fun startStreamOnWorker(
        sessionId: String,
        facing: String,
        enableAudio: Boolean,
        quality: String
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

        try {
            val runtime = SharedWebRtcRuntime.ensureReady(appContext)
            if (runtime == null) {
                onStreamStateChanged(false, "Initialisation matérielle WebRTC impossible.")
                return
            }
            val (rootEgl, factory) = runtime

            currentSessionId = sessionId
            currentFacing = facing
            currentMicEnabled = enableAudio

            val capturer = createCameraCapturer(facing)
            if (capturer == null) {
                onStreamStateChanged(false, "Aucun capteur caméra détecté.")
                return
            }
            videoCapturer = capturer

            val texHelper = SurfaceTextureHelper.create("SentinelCaptureTex", rootEgl.eglBaseContext)
            surfaceTextureHelper = texHelper

            val vSource = factory.createVideoSource(capturer.isScreencast)
            videoSource = vSource
            capturer.initialize(texHelper, appContext, vSource.capturerObserver)

            val width = if (quality == "sd") 640 else 1280
            val height = if (quality == "sd") 480 else 720
            capturer.startCapture(width, height, 30)

            val vTrack = factory.createVideoTrack("ARDAMSv0", vSource)
            vTrack.setEnabled(true)
            videoTrack = vTrack

            val hasAudioPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasAudioPerm) {
                try {
                    val aSource = factory.createAudioSource(MediaConstraints())
                    audioSource = aSource
                    val aTrack = factory.createAudioTrack("ARDAMSa0", aSource)
                    aTrack.setEnabled(enableAudio)
                    audioTrack = aTrack
                } catch (_: Throwable) {
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
                    // CRITICAL: Never close PeerConnection synchronously inside C++ callback!
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

                override fun onAddStream(stream: MediaStream?) {}

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

            pc.addTrack(vTrack, listOf("ARDAMS"))
            audioTrack?.let { pc.addTrack(it, listOf("ARDAMS")) }

            val dcInit = DataChannel.Init().apply { ordered = true }
            dataChannel = pc.createDataChannel("sentinel-telemetry", dcInit)

            isStreamingActive = true
            onStreamStateChanged(true, null)

            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
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
            onStreamStateChanged(false, t.message ?: "Erreur ouverture caméra")
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
                val capturer = videoCapturer ?: return@execute
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
                audioTrack?.setEnabled(enabled)
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

        try {
            videoCapturer?.stopCapture()
        } catch (_: Throwable) {
        }
        try {
            videoCapturer?.dispose()
        } catch (_: Throwable) {
        }
        videoCapturer = null

        try {
            dataChannel?.close()
        } catch (_: Throwable) {
        }
        dataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Throwable) {
        }
        peerConnection = null

        try {
            videoTrack?.dispose()
        } catch (_: Throwable) {
        }
        videoTrack = null

        try {
            videoSource?.dispose()
        } catch (_: Throwable) {
        }
        videoSource = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Throwable) {
        }
        surfaceTextureHelper = null

        try {
            audioTrack?.dispose()
        } catch (_: Throwable) {
        }
        audioTrack = null

        try {
            audioSource?.dispose()
        } catch (_: Throwable) {
        }
        audioSource = null

        if (wasStreaming) {
            onStreamStateChanged(false, null)
        }
    }

    fun shutdownEngine() {
        rtcExecutor.execute {
            stopStreamOnWorker()
        }
        rtcExecutor.shutdown()
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
