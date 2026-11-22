package com.example.phonemirror

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

class MicCapturer(private val onPcm: (ByteArray) -> Unit) {
    private val sampleRate = 16_000
    private val frameBytes = sampleRate * 100 / 1000 * 2 // 100 мс, 16-bit mono
    private var recorder: AudioRecord? = null
    @Volatile
    private var running = false

    fun start() {
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(min, frameBytes * 2)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Не удалось инициализировать микрофон")
        }
        running = true
        recorder!!.startRecording()

        Thread {
            val buffer = ByteArray(frameBytes)
            while (running) {
                val read = recorder!!.read(buffer, 0, buffer.size)
                if (read > 0) onPcm(buffer.copyOf(read))
            }
        }.apply {
            name = "microphone-capture"
            isDaemon = true
        }.start()
    }

    fun stop() {
        running = false
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}
