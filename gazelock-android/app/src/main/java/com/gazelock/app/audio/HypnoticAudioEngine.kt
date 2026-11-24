package com.gazelock.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.sin

/**
 * #8 & #17 Local Synthesized Hypnotic Binaural + Rhythmic Sub-Bass Pulse Engine:
 * - Supports live Overload Sprint mode (#17): boosts binaural beat (+5 Hz) & heartbeat pulse rate
 *   (68 BPM -> 118 BPM) seamlessly without restarting AudioTrack!
 */
class HypnoticAudioEngine {

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var overloadSprint = false

    @Volatile
    private var targetGain = 0.25f

    @Volatile
    private var beatFreqHz = 6.0

    private var workerThread: Thread? = null

    fun start(volumePct: Int, freqHz: Int) {
        if (running) {
            updateParams(volumePct, freqHz)
            return
        }
        updateParams(volumePct, freqHz)
        running = true
        paused = false
        overloadSprint = false

        val t = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Throwable) {}

            val sampleRate = 44_100
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufBytes * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()

                val framesPerChunk = 1024
                val pcm = ShortArray(framesPerChunk * 2)
                var phaseL = 0.0
                var phaseR = 0.0
                var phaseSubL = 0.0
                var phaseSubR = 0.0
                var phaseLfo = 0.0
                var currentGain = 0.0f
                val twoPi = 2.0 * PI

                while (running) {
                    val sprint = overloadSprint
                    val goalGain = if (paused) 0.0f else if (sprint) (targetGain * 1.25f).coerceAtMost(0.62f) else targetGain
                    val carrierBase = if (sprint) 186.0 else 168.0
                    val beat = beatFreqHz + if (sprint) 5.0 else 0.0
                    val lfoFreq = if (sprint) 1.96 else 1.13 // 118 BPM heart-racing sprint vs 68 BPM trance

                    val incL = twoPi * carrierBase / sampleRate
                    val incR = twoPi * (carrierBase + beat) / sampleRate
                    val incSubL = twoPi * (carrierBase * 0.5) / sampleRate
                    val incSubR = twoPi * (carrierBase * 0.5 + beat * 0.5) / sampleRate
                    val incLfo = twoPi * lfoFreq / sampleRate

                    var i = 0
                    while (i < framesPerChunk) {
                        currentGain += (goalGain - currentGain) * 0.0018f
                        val pulseDepth = if (sprint) 0.36 else 0.28
                        val pulse = (1.0 - pulseDepth) + pulseDepth * sin(phaseLfo)
                        val leftSample = (0.64 * sin(phaseL) + 0.36 * sin(phaseSubL)) * pulse * currentGain
                        val rightSample = (0.64 * sin(phaseR) + 0.36 * sin(phaseSubR)) * pulse * currentGain

                        pcm[i * 2] = (leftSample * 24_000.0).toInt().coerceIn(-32767, 32767).toShort()
                        pcm[i * 2 + 1] = (rightSample * 24_000.0).toInt().coerceIn(-32767, 32767).toShort()

                        phaseL += incL; if (phaseL >= twoPi) phaseL -= twoPi
                        phaseR += incR; if (phaseR >= twoPi) phaseR -= twoPi
                        phaseSubL += incSubL; if (phaseSubL >= twoPi) phaseSubL -= twoPi
                        phaseSubR += incSubR; if (phaseSubR >= twoPi) phaseSubR -= twoPi
                        phaseLfo += incLfo; if (phaseLfo >= twoPi) phaseLfo -= twoPi
                        i++
                    }
                    track.write(pcm, 0, pcm.size)
                }
            } catch (_: Throwable) {
            } finally {
                try { track?.pause() } catch (_: Throwable) {}
                try { track?.flush() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }
        t.name = "GazeLock-HypnoticAudio"
        t.isDaemon = true
        workerThread = t
        t.start()
    }

    fun updateParams(volumePct: Int, freqHz: Int) {
        targetGain = (volumePct.coerceIn(10, 80) / 100f) * 0.65f
        beatFreqHz = freqHz.coerceIn(4, 12).toDouble()
    }

    fun setOverloadSprint(active: Boolean) {
        overloadSprint = active
    }

    fun setPaused(isPaused: Boolean) {
        paused = isPaused
    }

    fun stop() {
        running = false
        try { workerThread?.join(450L) } catch (_: Throwable) {}
        workerThread = null
    }
}
