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

/**
 * Headless Hardware WebRTC Capture Engine for Android ForegroundService.
 * Uses Camera2Enumerator + Offscreen SurfaceTextureHelper (EGL) so capture continues
 * uninterrupted even when MainActivity is closed, another app is active, or the screen is locked.
 * Physically releases Camera2 when stopped to ensure 0 mA idle camera battery usage.
 */
class NativeWebRtcEngine(
    context: Context,
    private val onLocalOfferReady: (sessionId: String, sdp: String, fingerprint: String) -> Unit,
    private val onLocalIceCandidate: (sessionId: String, candidate: IceCandidate) -> Unit,
    private val onStreamStateChanged: (isStreaming: Boolean, error: String?) -> Unit
) {
    private val appContext = context.applicationContext
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

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

    @Synchronized
    private fun ensureFactoryInitialized() {
        if (peerConnectionFactory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val rootEgl = EglBase.create()
        eglBase = rootEgl

        val encoderFactory = DefaultVideoEncoderFactory(rootEgl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEgl.eglBaseContext)
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        audioModule.release()
    }

    @Synchronized
    fun startStream(
        sessionId: String,
        facing: String = "front",
        enableAudio: Boolean = false,
        quality: String = "hd"
    ) {
        stopStream()

        val hasCamPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamPerm) {
            onStreamStateChanged(false, "Permission CAMERA non accordée sur Android.")
            return
        }

        try {
            ensureFactoryInitialized()
            val factory = peerConnectionFactory ?: return
            val rootEgl = eglBase ?: return

            currentSessionId = sessionId
            currentFacing = facing
            currentMicEnabled = enableAudio

            val capturer = createCameraCapturer(facing)
            if (capturer == null) {
                onStreamStateChanged(false, "Aucun capteur caméra disponible.")
                return
            }
            videoCapturer = capturer

            val texHelper = SurfaceTextureHelper.create("SentinelCamCapture", rootEgl.eglBaseContext)
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

            // Optional Microphone Track
            val hasAudioPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasAudioPerm) {
                val aSource = factory.createAudioSource(MediaConstraints())
                audioSource = aSource
                val aTrack = factory.createAudioTrack("ARDAMSa0", aSource)
                aTrack.setEnabled(enableAudio)
                audioTrack = aTrack
            }

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
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
                    if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        stopStream()
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
                stopStream()
                onStreamStateChanged(false, "Échec création PeerConnection native.")
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
                    pc.setLocalDescription(object : SdpObserver {
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

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    onStreamStateChanged(false, error ?: "Erreur SDP Offer")
                }
                override fun onSetFailure(error: String?) {}
            }, offerConstraints)

        } catch (e: Exception) {
            stopStream()
            onStreamStateChanged(false, e.message ?: "Erreur matérielle Camera2")
        }
    }

    @Synchronized
    fun applyRemoteAnswer(sessionId: String, sdp: String) {
        val pc = peerConnection ?: return
        if (sessionId != currentSessionId) return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, answer)
    }

    @Synchronized
    fun addRemoteIceCandidate(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val pc = peerConnection ?: return
        if (sessionId != currentSessionId) return
        try {
            pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun switchCamera(targetFacing: String) {
        val capturer = videoCapturer ?: return
        if (targetFacing == currentFacing) return
        val enumerator = getCameraEnumerator()
        val targetDevice = findCameraDeviceName(enumerator, preferFront = targetFacing == "front") ?: return

        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                currentFacing = if (isFrontCamera) "front" else "back"
            }

            override fun onCameraSwitchError(errorDescription: String?) {}
        }, targetDevice)
    }

    @Synchronized
    fun setMicrophoneEnabled(enabled: Boolean) {
        currentMicEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    fun sendTelemetryOverDataChannel(statusJson: JSONObject) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val bytes = statusJson.toString().toByteArray(Charsets.UTF_8)
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stopStream() {
        val wasStreaming = isStreamingActive
        isStreamingActive = false
        currentSessionId = ""

        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            videoCapturer?.dispose()
        } catch (_: Exception) {
        }
        videoCapturer = null

        try {
            videoTrack?.dispose()
        } catch (_: Exception) {
        }
        videoTrack = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }
        videoSource = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null

        try {
            audioTrack?.dispose()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        audioSource = null

        try {
            dataChannel?.close()
        } catch (_: Exception) {
        }
        dataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        peerConnection = null

        if (wasStreaming) {
            onStreamStateChanged(false, null)
        }
    }

    @Synchronized
    fun releaseAll() {
        stopStream()
        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {
        }
        peerConnectionFactory = null

        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null
    }

    private fun getCameraEnumerator(): CameraEnumerator {
        return if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(true)
        }
    }

    private fun findCameraDeviceName(enumerator: CameraEnumerator, preferFront: Boolean): String? {
        val names = enumerator.deviceNames
        for (name in names) {
            if (preferFront && enumerator.isFrontFacing(name)) return name
            if (!preferFront && enumerator.isBackFacing(name)) return name
        }
        return names.firstOrNull()
    }

    private fun createCameraCapturer(facing: String): CameraVideoCapturer? {
        val enumerator = getCameraEnumerator()
        val deviceName = findCameraDeviceName(enumerator, preferFront = facing == "front") ?: return null
        return enumerator.createCapturer(deviceName, null)
    }
}
