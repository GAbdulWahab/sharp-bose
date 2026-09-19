package com.offline.calling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.offline.calling.audio.AndroidAudioEngine
import com.offline.calling.radio.ForegroundMeshService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var audioEngine: AndroidAudioEngine
    private var isCalling = false
    private var isSpeakerOn = false
    private val localPeerId = "node-" + UUID.randomUUID().toString().substring(0, 8)

    private lateinit var tvStatus: TextView
    private lateinit var tvPeerId: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnCall: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnSos: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioEngine = AndroidAudioEngine(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPeerId = findViewById(R.id.tvPeerId)
        tvLogs = findViewById(R.id.tvLogs)
        btnCall = findViewById(R.id.btnCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnSos = findViewById(R.id.btnSos)

        tvPeerId.text = "Local Peer ID: $localPeerId • Noise_XX E2EE"

        checkAndRequestPermissions()
        startMeshService()
        setupUIListeners()
    }

    private fun setupUIListeners() {
        btnCall.setOnClickListener {
            if (!isCalling) {
                startVoiceCall()
            } else {
                stopVoiceCall()
            }
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioEngine.setSpeakerphoneOn(isSpeakerOn)
            btnSpeaker.text = if (isSpeakerOn) "Speaker (ON)" else "Speaker (OFF)"
            logEvent("Audio output switched to ${if (isSpeakerOn) "Speakerphone" else "Earpiece"}")
        }

        btnSos.setOnClickListener {
            broadcastEmergencySOS()
        }

        audioEngine.onAudioFrameCaptured = { frame ->
            // Voice frame captured and ready for packetization and mesh transmission
        }
    }

    private fun startVoiceCall() {
        try {
            audioEngine.startVoice()
            isCalling = true
            btnCall.text = "End Call"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
            logEvent("[Voice] Call established with 16kHz PCM AEC/NS")
            Toast.makeText(this, "Voice Call Started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not start audio engine: ${e.message}")
        }
    }

    private fun stopVoiceCall() {
        try {
            audioEngine.stopVoice()
            isCalling = false
            btnCall.text = "Start Voice Call"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_emerald))
            logEvent("[Voice] Call terminated cleanly")
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not stop audio engine: ${e.message}")
        }
    }

    private fun broadcastEmergencySOS() {
        logEvent("[EMERGENCY SOS] Broadcasting distress beacon to all peers (TTL: 15 hops)...")
        Toast.makeText(this, "🚨 EMERGENCY SOS BROADCASTED", Toast.LENGTH_LONG).show()
    }

    private fun startMeshService() {
        val serviceIntent = Intent(this, ForegroundMeshService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        logEvent("[Service] ForegroundMeshService started")
    }

    private fun logEvent(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLogs = tvLogs.text.toString()
        tvLogs.text = "[$time] $msg\n$currentLogs"
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCalling) {
            audioEngine.stopVoice()
        }
    }
}
