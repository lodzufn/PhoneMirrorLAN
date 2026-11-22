package com.example.phonemirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class SoundPlayer {
    private val sampleRate = 44_100

    fun play(name: String) {
        val notes = when (name) {
            "beep" -> listOf(880 to 250)
            "alarm" -> listOf(660 to 200, 880 to 200, 660 to 200, 880 to 200)
            "chime" -> listOf(523 to 200, 659 to 200, 784 to 400)
            "siren" -> listOf(500 to 300, 900 to 300, 500 to 300, 900 to 300)
            else -> listOf(440 to 200)
        }
        CoroutineScope(Dispatchers.IO).launch { playNotes(notes) }
    }

    private fun playNotes(notes: List<Pair<Int, Int>>) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        for ((frequency, duration) in notes) {
            val frames = sampleRate * duration / 1000
            val pcm = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val value = (sin(2.0 * PI * frequency * i / sampleRate) * 12_000).toInt()
                    .toShort()
                pcm[i * 2] = value.toByte()
                pcm[i * 2 + 1] = (value.toInt() shr 8).toByte()
            }
            track.write(pcm, 0, pcm.size)
        }
        Thread.sleep(100)
        track.stop()
        track.release()
    }

    companion object {
        val NAMES = listOf("beep", "alarm", "chime", "siren")
    }
}
