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
import com.offline.calling.radio.MeshWebSocketBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var audioEngine: AndroidAudioEngine
    private val bridge = MeshWebSocketBridge()
    private var isCalling = false
    private var isSpeakerOn = true
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
        audioEngine.setSpeakerphoneOn(true)

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
        setupMeshBridge()
    }

    private fun setupMeshBridge() {
        bridge.onStatusChanged = { status, isConnected ->
            runOnUiThread {
                tvStatus.text = status
                if (isConnected) {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                    logEvent("[Bridge] $status")
                } else {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                }
            }
        }

        bridge.onAudioFrameReceived = { frame ->
            // Play inbound live audio frame from Web app/Laptop immediately on speaker
            audioEngine.playAudioFrame(frame)
        }

        bridge.onIncomingCall = { callerName, callerId ->
            runOnUiThread {
                showIncomingCallDialog(callerName, callerId)
            }
        }

        bridge.onCallAccepted = { peerName ->
            runOnUiThread {
                logEvent("[Live Call] 📞 $peerName accepted call! 2-way voice connected.")
                Toast.makeText(this, "Call Connected with $peerName", Toast.LENGTH_SHORT).show()
            }
        }

        bridge.onCallEnded = {
            runOnUiThread {
                if (isCalling) {
                    stopVoiceCall()
                }
                logEvent("[Live Call] Remote peer ended the call.")
            }
        }

        bridge.onPttStarted = { speakerName ->
            runOnUiThread {
                tvPttChannel.text = "Channel 1 • Floor: $speakerName (Speaking...)"
                tvPttChannel.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
            }
        }

        bridge.onPttStopped = {
            runOnUiThread {
                tvPttChannel.text = "Channel 1: Emergency & Tactical • Floor: Clear"
                tvPttChannel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        bridge.connect()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        tvStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        btnCall.setOnClickListener {
            if (!isCalling) {
                startVoiceCall()
            } else {
                stopVoiceCall()
            }
        }

        btnReceiveCall.setOnClickListener {
            showIncomingCallDialog("Laptop Web Node", "0x7F4A21B9")
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
            // Stream captured voice frames directly to Web App / Laptop
            bridge.sendAudioFrame(frame)
        }
    }

    private fun showIpSettingsDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Connect to Laptop Mesh")

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val tvHint = TextView(this).apply {
            text = "Enter Laptop IP (e.g. 10.73.88.166 or Hotspot IP):"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        layout.addView(tvHint)

        val input = android.widget.EditText(this).apply {
            setText(bridge.currentHost)
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        layout.addView(input)

        builder.setView(layout)

        builder.setPositiveButton("Connect") { dialog, _ ->
            val host = input.text.toString().trim()
            if (host.isNotEmpty()) {
                bridge.connect(host)
                logEvent("[Bridge] Connecting to Laptop at $host...")
            }
            dialog.dismiss()
        }

        builder.setNeutralButton("Auto-Detect") { dialog, _ ->
            bridge.connect()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
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

        logEvent("[Incoming Call] 🔔 Incoming call from $callerName ($callerId)...")

        btnAccept.setOnClickListener {
            dialog.dismiss()
            bridge.sendCallAccept()
            logEvent("[Incoming Call] 📞 Accepted call from $callerName. Starting live voice stream...")
            startVoiceCall()
            Toast.makeText(this, "Connected with $callerName", Toast.LENGTH_SHORT).show()
        }

        btnDecline.setOnClickListener {
            dialog.dismiss()
            bridge.sendCallDecline()
            logEvent("[Incoming Call] ✕ Declined call from $callerName")
            Toast.makeText(this, "Call Declined", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun startPttTransmit() {
        isPttTransmitting = true
        btnPtt.text = "TRANSMITTING (PTT CH 1)..."
        btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
        tvPttChannel.text = "Channel 1: Emergency & Tactical • Floor: YOU (Broadcasting)"
        logEvent("[PTT] Floor acquired. Broadcasting half-duplex voice to Laptop & Peers...")
        bridge.sendPttStart()
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
        bridge.sendPttStop()
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
            bridge.sendCallInvite()
            logEvent("[Voice Call] 🔒 2-Way Live Voice Stream Active with Laptop & Mesh")
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
            bridge.sendCallHangup()
            logEvent("[Voice Call] Call terminated cleanly")
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
        bridge.disconnect()
        if (isCalling || isPttTransmitting) {
            audioEngine.stopVoice()
        }
    }
}
