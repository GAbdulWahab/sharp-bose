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
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Studio-Grade Low-Latency Real-Time Audio Engine.
 * Features asynchronous ring-buffered jitter playback, hardware AEC/NS,
 * high-precision linear resampling, and clean dynamic gain scaling.
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
    private val isPlaying = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(40)

    var onAudioFrameCaptured: ((ByteArray) -> Unit)? = null

    fun startVoice() {
        if (isRecording.get()) return

        startPlaybackOnly()

        val inBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val actualInBufSize = maxOf(inBufferSize * 2, 4096)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfigIn,
            audioFormat,
            actualInBufSize
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val audioBuffer = ByteArray(640) // 20ms @ 16kHz mono (320 samples)
            while (isRecording.get()) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readBytes > 0) {
                    var sum = 0L
                    val numSamples = readBytes / 2
                    for (i in 0 until numSamples) {
                        val low = audioBuffer[i * 2].toInt() and 0xFF
                        val high = audioBuffer[i * 2 + 1].toInt()
                        val sample = ((high shl 8) or low).toShort()
                        sum += Math.abs(sample.toLong())
                    }
                    val avg = sum / maxOf(1, numSamples)

                    // Squelch gate: Ignore background noise to prevent acoustic feedback beeps
                    if (avg < 150) {
                        continue
                    }

                    val packet = ByteArray(4 + readBytes)
                    packet[0] = 0xAA.toByte()
                    packet[1] = 0x55.toByte()
                    packet[2] = ((sampleRate shr 8) and 0xFF).toByte()
                    packet[3] = (sampleRate and 0xFF).toByte()
                    System.arraycopy(audioBuffer, 0, packet, 4, readBytes)
                    onAudioFrameCaptured?.invoke(packet)
                }
            }
        }.apply {
            name = "AudioRecordThread"
            start()
        }
    }

    fun startPlaybackOnly() {
        if (audioTrack != null) return
        val outBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        val actualOutBufSize = maxOf(outBufferSize * 2, 4096)

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
            .setBufferSizeInBytes(actualOutBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying.set(true)

        playbackThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (isPlaying.get()) {
                try {
                    val chunk = playbackQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        audioTrack?.write(chunk, 0, chunk.size)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("AudioEngine", "Playback error: ${e.message}")
                }
            }
        }.apply {
            name = "AudioTrackPlaybackThread"
            start()
        }
    }

    fun playAudioFrame(frame: ByteArray) {
        if (audioTrack == null) {
            startPlaybackOnly()
        }

        var pcmBytes: ByteArray
        var senderRate = sampleRate

        if (frame.size >= 4 && (frame[0].toInt() and 0xFF) == 0xAA && (frame[1].toInt() and 0xFF) == 0x55) {
            senderRate = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            pcmBytes = frame.copyOfRange(4, frame.size)
        } else {
            pcmBytes = frame
        }

        if (pcmBytes.isEmpty()) return

        // Resample if sender rate differs (e.g. 48000 Hz from laptop down to 16000 Hz)
        val processedPcm = if (senderRate != sampleRate && senderRate > 0) {
            resamplePcm16(pcmBytes, senderRate, sampleRate)
        } else {
            pcmBytes
        }

        // Apply clean boost with soft ceiling to ensure loud clarity
        val boosted = applyCleanGainAndLimiter(processedPcm, 1.4f)

        // Drop oldest packets if queue starts lagging beyond 120ms
        while (playbackQueue.size > 8) {
            playbackQueue.poll()
        }

        playbackQueue.offer(boosted)
    }

    private fun applyCleanGainAndLimiter(input: ByteArray, gain: Float): ByteArray {
        val numSamples = input.size / 2
        val output = ByteArray(input.size)
        for (i in 0 until numSamples) {
            val low = input[i * 2].toInt() and 0xFF
            val high = input[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toFloat()
            val amplified = (sample * gain).coerceIn(-32767f, 32767f).toInt()
            output[i * 2] = (amplified and 0xFF).toByte()
            output[i * 2 + 1] = ((amplified shr 8) and 0xFF).toByte()
        }
        return output
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
        recordingThread?.join(300)
        recordingThread = null

        isPlaying.set(false)
        playbackThread?.interrupt()
        playbackThread?.join(300)
        playbackThread = null
        playbackQueue.clear()

        echoCanceler?.release()
        noiseSuppressor?.release()

        try { audioRecord?.stop() } catch(e: Exception){}
        try { audioRecord?.release() } catch(e: Exception){}
        audioRecord = null

        try { audioTrack?.stop() } catch(e: Exception){}
        try { audioTrack?.release() } catch(e: Exception){}
        audioTrack = null
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = enabled
    }
}
