package com.offline.calling.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Low-Latency Real-Time Audio Engine.
 * Supports full-duplex AEC/NS recording and AudioTrack PCM playback.
 */
class AndroidAudioEngine(private val context: Context) {
    val sampleRate = 16000
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
        if (isRecording.get()) return

        startPlaybackOnly()

        val inBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfigIn,
            audioFormat,
            inBufferSize * 2
        )

        val audioSessionId = audioRecord?.audioSessionId ?: 0
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

        audioRecord?.startRecording()
        isRecording.set(true)

        recordingThread = Thread {
            val audioBuffer = ByteArray(640) // 20ms @ 16kHz
            while (isRecording.get()) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readBytes > 0) {
                    val packet = ByteArray(4 + readBytes)
                    packet[0] = 0xAA.toByte()
                    packet[1] = 0x55.toByte()
                    packet[2] = ((sampleRate shr 8) and 0xFF).toByte()
                    packet[3] = (sampleRate and 0xFF).toByte()
                    System.arraycopy(audioBuffer, 0, packet, 4, readBytes)
                    onAudioFrameCaptured?.invoke(packet)
                }
            }
        }.apply { start() }
    }

    fun startPlaybackOnly() {
        if (audioTrack != null) return
        val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(outBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun playAudioFrame(frame: ByteArray) {
        if (audioTrack == null) {
            startPlaybackOnly()
        }
        if (frame.size >= 4 && (frame[0].toInt() and 0xFF) == 0xAA && (frame[1].toInt() and 0xFF) == 0x55) {
            val senderRate = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            val pcmBytes = frame.copyOfRange(4, frame.size)
            if (senderRate != sampleRate && senderRate > 0) {
                val resampled = resamplePcm16(pcmBytes, senderRate, sampleRate)
                audioTrack?.write(resampled, 0, resampled.size)
            } else {
                audioTrack?.write(pcmBytes, 0, pcmBytes.size)
            }
        } else {
            audioTrack?.write(frame, 0, frame.size)
        }
    }

    private fun resamplePcm16(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        val inputSamples = ShortArray(input.size / 2)
        for (i in inputSamples.indices) {
            val low = input[i * 2].toInt() and 0xFF
            val high = input[i * 2 + 1].toInt()
            inputSamples[i] = ((high shl 8) or low).toShort()
        }
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLen = (inputSamples.size / ratio).toInt()
        val outputBytes = ByteArray(outputLen * 2)
        for (i in 0 until outputLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, inputSamples.size - 1)
            val frac = srcPos - i0
            val s0 = inputSamples[i0].toFloat()
            val s1 = inputSamples[i1].toFloat()
            val interpolated = (s0 * (1.0f - frac) + s1 * frac).toInt().coerceIn(-32768, 32767).toShort()
            outputBytes[i * 2] = (interpolated.toInt() and 0xFF).toByte()
            outputBytes[i * 2 + 1] = ((interpolated.toInt() shr 8) and 0xFF).toByte()
        }
        return outputBytes
    }

    fun stopVoice() {
        isRecording.set(false)
        recordingThread?.join(500)
        recordingThread = null

        echoCanceler?.release()
        noiseSuppressor?.release()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = enabled
    }
}
