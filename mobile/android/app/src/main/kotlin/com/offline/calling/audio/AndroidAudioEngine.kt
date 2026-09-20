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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Studio-Grade Low-Latency Real-Time Audio Engine.
 * Features native Little-Endian ByteBuffer processing, asynchronous ring-buffered jitter playback,
 * hardware AEC/NS, and clean dynamic gain scaling.
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

        if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            try {
                android.media.audiofx.AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
            } catch (e: Exception) {
                Log.d("AudioEngine", "AGC setup: ${e.message}")
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
                    val sBuf = ByteBuffer.wrap(audioBuffer, 0, readBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                    var sum = 0L
                    val numSamples = sBuf.remaining()
                    for (i in 0 until numSamples) {
                        sum += Math.abs(sBuf.get(i).toLong())
                    }
                    val avg = sum / maxOf(1, numSamples)

                    // Squelch gate to avoid feedback whine
                    if (avg < 80) {
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

        val boosted = applyCleanGainAndLimiter(processedPcm, 1.2f)

        // Prevent playback buffer bloat
        while (playbackQueue.size > 8) {
            playbackQueue.poll()
        }

        playbackQueue.offer(boosted)
    }

    private fun applyCleanGainAndLimiter(input: ByteArray, gain: Float): ByteArray {
        val inBuf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val numSamples = inBuf.remaining()
        val output = ByteArray(input.size)
        val outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        
        for (i in 0 until numSamples) {
            val sample = inBuf.get(i).toFloat()
            val amplified = (sample * gain).coerceIn(-32767f, 32767f).toInt().toShort()
            outBuf.put(i, amplified)
        }
        return output
    }

    private fun resamplePcm16(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        val inBuf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inLen = inBuf.remaining()
        if (inLen == 0) return input
        
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = (inLen / ratio).toInt()
        val outputBytes = ByteArray(outLen * 2)
        val outBuf = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, inLen - 1)
            val i1 = minOf(i0 + 1, inLen - 1)
            val frac = srcPos - i0
            val s0 = inBuf.get(i0).toFloat()
            val s1 = inBuf.get(i1).toFloat()
            val interpolated = (s0 * (1.0f - frac) + s1 * frac).toInt().coerceIn(-32768, 32767).toShort()
            outBuf.put(i, interpolated)
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
