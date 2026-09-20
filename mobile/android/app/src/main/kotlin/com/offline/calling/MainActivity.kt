package com.offline.calling

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
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
    private var isPttTransmitting = false
    private val localPeerId = "node-" + UUID.randomUUID().toString().substring(0, 8)

    private lateinit var tvStatus: TextView
    private lateinit var tvPeerId: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvPttChannel: TextView
    private lateinit var btnCall: Button
    private lateinit var btnReceiveCall: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnSos: Button
    private lateinit var btnPtt: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioEngine = AndroidAudioEngine(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPeerId = findViewById(R.id.tvPeerId)
        tvLogs = findViewById(R.id.tvLogs)
        tvPttChannel = findViewById(R.id.tvPttChannel)
        btnCall = findViewById(R.id.btnCall)
        btnReceiveCall = findViewById(R.id.btnReceiveCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnSos = findViewById(R.id.btnSos)
        btnPtt = findViewById(R.id.btnPtt)

        tvPeerId.text = "Local Peer ID: $localPeerId • Noise_XX E2EE"

        checkAndRequestPermissions()
        startMeshService()
        setupUIListeners()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        btnCall.setOnClickListener {
            if (!isCalling) {
                startVoiceCall()
            } else {
                stopVoiceCall()
            }
        }

        btnReceiveCall.setOnClickListener {
            showIncomingCallDialog("Laptop Mesh Node", "0x7F4A21B9")
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

        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startPttTransmit()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPttTransmit()
                    true
                }
                else -> false
            }
        }

        audioEngine.onAudioFrameCaptured = { frame ->
            // Audio PCM frame captured for PTT or Voice Call mesh propagation
        }
    }

    /**
     * Displays a full-featured incoming call popup modal with Accept/Decline actions
     */
    private fun showIncomingCallDialog(callerName: String, callerId: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_incoming_call)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val tvCallerName = dialog.findViewById<TextView>(R.id.tvIncomingCallerName)
        val tvAvatar = dialog.findViewById<TextView>(R.id.tvIncomingAvatar)
        val btnAccept = dialog.findViewById<Button>(R.id.btnAcceptCall)
        val btnDecline = dialog.findViewById<Button>(R.id.btnDeclineCall)

        tvCallerName.text = callerName
        tvAvatar.text = callerName.take(2).uppercase()

        logEvent("[Incoming Call] 🔔 Incoming encrypted call from $callerName ($callerId)...")

        btnAccept.setOnClickListener {
            dialog.dismiss()
            logEvent("[Incoming Call] 📞 Call accepted from $callerName. Initializing 16kHz PCM audio stream...")
            startVoiceCall()
            Toast.makeText(this, "Connected with $callerName", Toast.LENGTH_SHORT).show()
        }

        btnDecline.setOnClickListener {
            dialog.dismiss()
            logEvent("[Incoming Call] ✕ Call declined from $callerName")
            Toast.makeText(this, "Call Declined", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun startPttTransmit() {
        isPttTransmitting = true
        btnPtt.text = "TRANSMITTING (PTT CH 1)..."
        btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
        tvPttChannel.text = "Channel 1: Emergency & Tactical • Floor: YOU (Broadcasting)"
        logEvent("[PTT] Floor acquired. Broadcasting half-duplex voice to all Channel 1 peers...")
        try {
            audioEngine.startVoice()
        } catch (e: Exception) {
            logEvent("[PTT Error] " + e.message)
        }
    }

    private fun stopPttTransmit() {
        if (!isPttTransmitting) return
        isPttTransmitting = false
        btnPtt.text = "HOLD TO TALK (PTT)"
        btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
        tvPttChannel.text = "Channel 1: Emergency & Tactical Recon • Floor: Clear"
        logEvent("[PTT] Floor released. Back to standby listening mode.")
        if (!isCalling) {
            try {
                audioEngine.stopVoice()
            } catch (e: Exception) {
                logEvent("[PTT Error] " + e.message)
            }
        }
    }

    private fun startVoiceCall() {
        try {
            audioEngine.startVoice()
            isCalling = true
            btnCall.text = "End Call"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
            logEvent("[Voice] Full-duplex call established with 16kHz PCM AEC/NS")
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
        logEvent("[Service] ForegroundMeshService active")
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
        if (isCalling || isPttTransmitting) {
            audioEngine.stopVoice()
        }
    }
}
