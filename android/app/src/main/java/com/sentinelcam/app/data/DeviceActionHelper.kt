package com.sentinelcam.app.data

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Hardware utility controller for:
 * 1. Rear LED Torch / Flashlight
 * 2. Find-My-Phone Ring Alert (audible even on vibrate)
 * 3. Text-to-Speech (TTS) Loudspeaker announcement
 */
object DeviceActionHelper {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var torchEnabled: Boolean = false
        private set

    @Volatile
    var ringingActive: Boolean = false
        private set

    private var ringPlayer: MediaPlayer? = null
    private var stopRingRunnable: Runnable? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun setTorch(context: Context, enable: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val cm = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var rearFlashId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearFlashId = id
                    break
                }
            }
            val targetId = rearFlashId ?: return false
            cm.setTorchMode(targetId, enable)
            torchEnabled = enable
            true
        } catch (_: Throwable) {
            // Camera2 hardware may hold the flash if rear camera is actively streaming
            false
        }
    }

    @Synchronized
    fun setRinging(context: Context, active: Boolean, durationSec: Int = 15, onChanged: () -> Unit = {}) {
        if (!active) {
            stopRingingInternal(onChanged)
            return
        }

        stopRingingInternal {}
        val appCtx = context.applicationContext
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val player = MediaPlayer().apply {
                setDataSource(appCtx, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            ringPlayer = player
            ringingActive = true
            onChanged()

            val timeout = Runnable {
                stopRingingInternal(onChanged)
            }
            stopRingRunnable = timeout
            mainHandler.postDelayed(timeout, durationSec.coerceIn(3, 45) * 1000L)
        } catch (_: Throwable) {
            ringingActive = false
            onChanged()
        }
    }

    @Synchronized
    private fun stopRingingInternal(onChanged: () -> Unit) {
        stopRingRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRingRunnable = null
        try {
            ringPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            ringPlayer?.release()
        } catch (_: Throwable) {
        }
        ringPlayer = null
        val was = ringingActive
        ringingActive = false
        if (was) onChanged()
    }

    @Synchronized
    fun speakText(context: Context, rawText: String) {
        val clean = rawText.trim().take(240)
        if (clean.isEmpty()) return
        val appCtx = context.applicationContext

        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } catch (_: Throwable) {
        }

        val currentTts = tts
        if (currentTts != null && ttsReady) {
            try {
                currentTts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sc_tts_${System.currentTimeMillis()}")
            } catch (_: Throwable) {
            }
            return
        }

        pendingSpeech = clean
        if (currentTts == null) {
            try {
                tts = TextToSpeech(appCtx) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts?.language = Locale.FRANCE
                        } catch (_: Throwable) {
                        }
                        ttsReady = true
                        val toSay = pendingSpeech
                        pendingSpeech = null
                        if (!toSay.isNullOrEmpty()) {
                            try {
                                tts?.speak(toSay, TextToSpeech.QUEUE_FLUSH, null, "sc_tts_init")
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun releaseAll(context: Context) {
        if (torchEnabled) setTorch(context, false)
        stopRingingInternal {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ttsReady = false
        pendingSpeech = null
    }
}
