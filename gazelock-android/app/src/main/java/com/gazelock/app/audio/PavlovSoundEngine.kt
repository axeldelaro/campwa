package com.gazelock.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * #36 Pavlovian Audio Conditioning Engine (100% On-Device Synthesized PCM):
 * - [playRewardChime]: Warm crystalline major triad bell (C6 -> E6 -> G6) played on 30s focus streaks,
 *   mini-game victories, No-Blink clear, and Final Boss victory!
 * - [playPunishmentTone]: Sharp dissonant low-mid tritone zap played on look-away penalties, blinks,
 *   app-switch tax, escape attempts, and mini-game mistakes!
 */
object PavlovSoundEngine {

    private const val SAMPLE_RATE = 44_100

    fun playRewardChime() {
        Thread {
            try {
                val durSec = 0.48
                val count = (SAMPLE_RATE * durSec).toInt()
                val pcm = ShortArray(count)
                val notes = doubleArrayOf(1046.50, 1318.51, 1567.98) // C6, E6, G6 crystalline arpeggio
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
                    // Sharp dissonant tritone buzz (165 Hz + 233 Hz + harsh 3rd harmonic)
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
