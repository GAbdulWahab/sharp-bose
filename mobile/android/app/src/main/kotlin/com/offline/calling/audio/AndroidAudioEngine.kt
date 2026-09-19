package com.offline.calling.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Low-Latency Real-Time Audio Engine.
 * Uses VOICE_COMMUNICATION audio attributes for hardware Acoustic Echo Cancellation (AEC) and Noise Suppression.
 */
class AndroidAudioEngine(private val context: Context) {
    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    var onAudioFrameCaptured: ((ByteArray) -> Unit)? = null

    fun startVoice() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfigIn,
            audioFormat,
            bufferSize * 2
        )

        val audioSessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        audioRecord!!.startRecording()
        isRecording.set(true)

        recordingThread = Thread {
            val audioBuffer = ByteArray(640) // 20ms @ 16kHz 16-bit Mono (320 samples * 2 bytes)
            while (isRecording.get()) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readBytes > 0) {
                    onAudioFrameCaptured?.invoke(audioBuffer.copyOf(readBytes))
                }
            }
        }.apply { start() }
    }

    fun stopVoice() {
        isRecording.set(false)
        recordingThread?.join()
        recordingThread = null

        echoCanceler?.release()
        noiseSuppressor?.release()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = enabled
    }
}
