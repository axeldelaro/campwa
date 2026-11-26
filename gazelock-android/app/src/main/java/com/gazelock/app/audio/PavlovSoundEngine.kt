package com.gazelock.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * #36 Pavlovian & TITAN Synthesized Audio Engine (100% On-Device PCM):
 * - [playRewardChime]: Crystalline C6-E6-G6 triad bell on streaks, victories & Boss clear.
 * - [playPunishmentTone]: Harsh dissonant tritone buzz on look-aways, blinks & mistakes.
 * - [playSirenAlert]: Urgent double-sweep alert siren for Camera Cover, Drowsiness & Time Bomb!
 * - [playSurgeWhoosh]: Rising harmonic sweep when a Pop-Up Storm SURGE wave strikes!
 */
object PavlovSoundEngine {

    private const val SAMPLE_RATE = 44_100

    fun playRewardChime() {
        Thread {
            try {
                val durSec = 0.48
                val count = (SAMPLE_RATE * durSec).toInt()
                val pcm = ShortArray(count)
                val notes = doubleArrayOf(1046.50, 1318.51, 1567.98)
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    var sample = 0.0
                    for (nIdx in notes.indices) {
                        val onset = nIdx * 0.075
                        if (t >= onset) {
                            val dt = t - onset
                            val env = exp(-dt * 9.5) * (1.0 - exp(-dt * 180.0))
                            val f = notes[nIdx]
                            sample += (0.72 * sin(2.0 * PI * f * dt) + 0.28 * sin(2.0 * PI * f * 2.0 * dt)) * env
                        }
                    }
                    pcm[i] = (sample * 11_500.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                playStaticPcm(pcm)
            } catch (_: Throwable) {}
        }.start()
    }

    fun playPunishmentTone() {
        Thread {
            try {
                val durSec = 0.24
                val count = (SAMPLE_RATE * durSec).toInt()
                val pcm = ShortArray(count)
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-t * 7.5) * (1.0 - exp(-t * 250.0))
                    val s1 = sin(2.0 * PI * 165.0 * t)
                    val s2 = sin(2.0 * PI * 233.0 * t)
                    val s3 = sin(2.0 * PI * 495.0 * t) * 0.45
                    val clipped = ((s1 + s2 + s3) * 0.55).coerceIn(-0.9, 0.9)
                    pcm[i] = (clipped * env * 18_500.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                playStaticPcm(pcm)
            } catch (_: Throwable) {}
        }.start()
    }

    /** Urgent double-sweep alarm for Camera Obstructed, Micro-Sleep Drowsiness, and Time Bomb! */
    fun playSirenAlert() {
        Thread {
            try {
                val durSec = 0.52
                val count = (SAMPLE_RATE * durSec).toInt()
                val pcm = ShortArray(count)
                var phase = 0.0
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val pulse = (t % 0.26) / 0.26
                    val freq = 520.0 + pulse * 620.0
                    phase += 2.0 * PI * freq / SAMPLE_RATE
                    val env = if (pulse < 0.85) 1.0 else (1.0 - (pulse - 0.85) / 0.15)
                    val sample = (sin(phase) * 0.72 + sin(phase * 2.0) * 0.28) * env
                    pcm[i] = (sample * 19_000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                playStaticPcm(pcm)
            } catch (_: Throwable) {}
        }.start()
    }

    /** Rising neon chord sweep when Pop-Up Storm enters a 3x SURGE WAVE! */
    fun playSurgeWhoosh() {
        Thread {
            try {
                val durSec = 0.42
                val count = (SAMPLE_RATE * durSec).toInt()
                val pcm = ShortArray(count)
                var phase1 = 0.0
                var phase2 = 0.0
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val frac = t / durSec
                    val f1 = 220.0 + frac * 660.0
                    val f2 = f1 * 1.5
                    phase1 += 2.0 * PI * f1 / SAMPLE_RATE
                    phase2 += 2.0 * PI * f2 / SAMPLE_RATE
                    val env = sin(PI * frac)
                    val s = (sin(phase1) * 0.65 + sin(phase2) * 0.35) * env
                    pcm[i] = (s * 14_500.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                playStaticPcm(pcm)
            } catch (_: Throwable) {}
        }.start()
    }

    private fun playStaticPcm(pcm: ShortArray) {
        val byteLen = pcm.size * 2
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteLen)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size * 1000L / SAMPLE_RATE) + 60L)
        } catch (_: Throwable) {
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
        }
    }
}
