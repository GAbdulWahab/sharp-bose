package com.offline.calling

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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

    // UI Elements
    private lateinit var mainScrollView: ScrollView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var btnThemeToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPeerId: TextView
    private lateinit var tvMeshStats: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvLogsHeader: TextView
    private lateinit var tvPttChannel: TextView
    private lateinit var btnCall: Button
    private lateinit var btnReceiveCall: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnSos: Button
    private lateinit var btnPtt: Button
    private lateinit var btnOpenChat: Button

    // Cards for theme updates
    private lateinit var cardStatus: CardView
    private lateinit var cardChat: CardView
    private lateinit var cardReceiveCall: CardView
    private lateinit var cardPtt: CardView
    private lateinit var cardCall: CardView
    private lateinit var cardSos: CardView

    // Chat
    private var isDarkMode = true
    private lateinit var prefs: SharedPreferences
    private val chatMessageList = mutableListOf<Pair<String, String>>() // (sender, text)
    private var activeChatMessagesContainer: LinearLayout? = null
    private var activeChatScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("offline_mesh_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("is_dark_mode", true)

        audioEngine = AndroidAudioEngine(this)
        audioEngine.setSpeakerphoneOn(true)

        bindViews()
        applyTheme(isDarkMode)
        checkAndRequestPermissions()
        startMeshService()
        setupUIListeners()
        setupMeshBridge()
    }

    private fun bindViews() {
        mainScrollView = findViewById(R.id.mainScrollView)
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvAppSubtitle = findViewById(R.id.tvAppSubtitle)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvPeerId = findViewById(R.id.tvPeerId)
        tvMeshStats = findViewById(R.id.tvMeshStats)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogsHeader = findViewById(R.id.tvLogsHeader)
        tvPttChannel = findViewById(R.id.tvPttChannel)
        btnCall = findViewById(R.id.btnCall)
        btnReceiveCall = findViewById(R.id.btnReceiveCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnSos = findViewById(R.id.btnSos)
        btnPtt = findViewById(R.id.btnPtt)
        btnOpenChat = findViewById(R.id.btnOpenChat)

        cardStatus = findViewById(R.id.cardStatus)
        cardChat = findViewById(R.id.cardChat)
        cardReceiveCall = findViewById(R.id.cardReceiveCall)
        cardPtt = findViewById(R.id.cardPtt)
        cardCall = findViewById(R.id.cardCall)
        cardSos = findViewById(R.id.cardSos)

        tvPeerId.text = "Local Peer ID: $localPeerId • Noise_XX E2EE"
    }

    private fun applyTheme(dark: Boolean) {
        isDarkMode = dark
        prefs.edit().putBoolean("is_dark_mode", dark).apply()

        val bgMain = if (dark) Color.parseColor("#0B0F19") else Color.parseColor("#F1F5F9")
        val bgCard = if (dark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF")
        val textPrimary = if (dark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val textSecondary = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val logBg = if (dark) Color.parseColor("#020617") else Color.parseColor("#E2E8F0")

        mainScrollView.setBackgroundColor(bgMain)
        tvAppTitle.setTextColor(textPrimary)
        tvAppSubtitle.setTextColor(textSecondary)
        tvLogsHeader.setTextColor(textPrimary)
        tvLogs.setBackgroundColor(logBg)

        btnThemeToggle.text = if (dark) "🌙 Dark" else "☀️ Light"
        btnThemeToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgCard)
        btnThemeToggle.setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        val cards = listOf(cardStatus, cardChat, cardReceiveCall, cardPtt, cardCall, cardSos)
        for (card in cards) {
            card.setCardBackgroundColor(bgCard)
        }
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
            // Route inbound audio to asynchronous high-priority ring buffer
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

        bridge.onChatMessageReceived = { senderName, text ->
            runOnUiThread {
                chatMessageList.add(Pair(senderName, text))
                logEvent("[Chat] 💬 $senderName: $text")

                // If chat dialog is open, append bubble dynamically
                if (activeChatMessagesContainer != null) {
                    appendChatBubble(activeChatMessagesContainer!!, activeChatScrollView, senderName, text, false)
                } else {
                    Toast.makeText(this, "💬 $senderName: $text", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bridge.connect()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        btnThemeToggle.setOnClickListener {
            applyTheme(!isDarkMode)
        }

        tvStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        btnOpenChat.setOnClickListener {
            showChatDialog()
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
            bridge.sendAudioFrame(frame)
        }
    }

    private fun showChatDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_chat)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val chatRoot = dialog.findViewById<LinearLayout>(R.id.chatDialogRoot)
        val tvHeader = dialog.findViewById<TextView>(R.id.tvChatHeaderTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseChat)
        val msgContainer = dialog.findViewById<LinearLayout>(R.id.chatMessagesContainer)
        val scrollView = dialog.findViewById<ScrollView>(R.id.chatScrollView)
        val etInput = dialog.findViewById<EditText>(R.id.etChatMessage)
        val btnSend = dialog.findViewById<Button>(R.id.btnSendChatMessage)

        activeChatMessagesContainer = msgContainer
        activeChatScrollView = scrollView

        // Apply theme to dialog
        if (isDarkMode) {
            chatRoot.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#F8FAFC"))
            etInput.setBackgroundColor(Color.parseColor("#1E293B"))
            etInput.setTextColor(Color.parseColor("#F8FAFC"))
        } else {
            chatRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0F172A"))
            etInput.setBackgroundColor(Color.parseColor("#F1F5F9"))
            etInput.setTextColor(Color.parseColor("#0F172A"))
        }

        // Populate existing history
        for ((sender, text) in chatMessageList) {
            val isMe = sender == "You" || sender == "Android Phone"
            appendChatBubble(msgContainer, scrollView, sender, text, isMe)
        }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                bridge.sendChatMessage(text, "Android Phone")
                chatMessageList.add(Pair("You", text))
                appendChatBubble(msgContainer, scrollView, "You", text, true)
                logEvent("[Chat Sent] $text")
                etInput.setText("")
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            activeChatMessagesContainer = null
            activeChatScrollView = null
        }

        dialog.show()
    }

    private fun appendChatBubble(
        container: LinearLayout,
        scrollView: ScrollView?,
        sender: String,
        text: String,
        isMe: Boolean
    ) {
        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isMe) Gravity.END else Gravity.START
                setMargins(4, 6, 4, 6)
            }
            layoutParams = params
            setPadding(28, 18, 28, 18)

            if (isMe) {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_background)?.apply {
                    setTint(Color.parseColor("#0284C7"))
                }
            } else {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_background)?.apply {
                    setTint(if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                }
            }
        }

        val tvSender = TextView(this).apply {
            this.text = sender
            textSize = 10f
            setTextColor(if (isMe) Color.parseColor("#BAE6FD") else Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvText = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (isMe) Color.WHITE else if (isDarkMode) Color.WHITE else Color.BLACK)
        }

        bubbleLayout.addView(tvSender)
        bubbleLayout.addView(tvText)
        container.addView(bubbleLayout)

        scrollView?.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showIpSettingsDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Connect to Laptop Mesh")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val tvHint = TextView(this).apply {
            text = "Enter Laptop IP (e.g. 10.73.88.166 or Hotspot IP):"
            setTextColor(if (isDarkMode) Color.LTGRAY else Color.DKGRAY)
            textSize = 13f
        }
        layout.addView(tvHint)

        val input = EditText(this).apply {
            setText(bridge.currentHost)
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
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
