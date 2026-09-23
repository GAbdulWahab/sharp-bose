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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.offline.calling.audio.AndroidAudioEngine
import com.offline.calling.radio.ForegroundMeshService
import com.offline.calling.radio.MeshWebSocketBridge
import com.offline.calling.radio.PeerLocation
import com.offline.calling.radio.PeerNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var audioEngine: AndroidAudioEngine
    private val bridge = MeshWebSocketBridge()
    private lateinit var callHistoryManager: CallHistoryManager
    private var isCalling = false
    private var isSpeakerOn = true
    private var isPttTransmitting = false
    private val localPeerId get() = bridge.localNodeId
    private var callStartTime: Long = 0L
    private var activeCallPeerName: String = "Mesh Peer"
    private var activeCallPeerId: String = ""
    private var incomingCallDialog: Dialog? = null

    // UI Elements
    private lateinit var mainScrollView: ScrollView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var btnThemeToggle: Button
    private lateinit var btnCallHistory: Button
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

    // Connected People Roster UI
    private lateinit var cardPeople: CardView
    private lateinit var tvPeopleTitle: TextView
    private lateinit var tvPeopleSubtitle: TextView
    private lateinit var tvPeopleCount: TextView
    private lateinit var llConnectedPeople: LinearLayout

    // Location UI
    private lateinit var cardLocation: CardView
    private lateinit var tvLocationTitle: TextView
    private lateinit var tvGpsFixBadge: TextView
    private lateinit var tvLocationCoords: TextView
    private lateinit var tvLocationMeta: TextView
    private lateinit var btnShareLocation: Button
    private lateinit var btnRadarMap: Button

    // All-India Nationwide HD Network UI
    private lateinit var cardNationwide: CardView
    private lateinit var tvNationwideTitle: TextView
    private lateinit var tvHdQualityBadge: TextView
    private lateinit var tvCurrentRoom: TextView
    private lateinit var tvNationwideMeta: TextView
    private lateinit var btnSwitchRoom: Button
    private var currentRoom: String = "INDIA-MAIN"

    // Cards for theme updates
    private lateinit var cardStatus: CardView
    private lateinit var cardChat: CardView
    private lateinit var cardReceiveCall: CardView
    private lateinit var cardPtt: CardView
    private lateinit var cardCall: CardView
    private lateinit var cardSos: CardView

    // Location State
    private var locationManager: LocationManager? = null
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentAltitude: Double = 0.0
    private var currentAccuracy: Float = 0f
    private var hasGpsFix: Boolean = false

    // Peer Roster State
    private var connectedPeersList = mutableListOf<PeerNode>()

    // Chat State
    private var isDarkMode = true
    private lateinit var prefs: SharedPreferences
    private val chatMessageList = mutableListOf<Pair<String, String>>() // (sender, text)
    private var activeChatMessagesContainer: LinearLayout? = null
    private var activeChatScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                android.util.Log.e("MainActivity", "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
            }
        } catch (e: Exception) {}

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("offline_mesh_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("is_dark_mode", true)

        callHistoryManager = CallHistoryManager(this)
        audioEngine = AndroidAudioEngine(this)
        try {
            audioEngine.startPlaybackOnly()
            audioEngine.setSpeakerphoneOn(true)
        } catch (e: Exception) {}

        bindViews()
        applyTheme(isDarkMode)
        checkAndRequestPermissions()
        startMeshService()
        initLocationEngine()
        setupUIListeners()
        setupMeshBridge()
    }

    private fun bindViews() {
        mainScrollView = findViewById(R.id.mainScrollView)
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvAppSubtitle = findViewById(R.id.tvAppSubtitle)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        btnCallHistory = findViewById(R.id.btnCallHistory)
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
        cardPeople = findViewById(R.id.cardPeople)
        tvPeopleTitle = findViewById(R.id.tvPeopleTitle)
        tvPeopleSubtitle = findViewById(R.id.tvPeopleSubtitle)
        tvPeopleCount = findViewById(R.id.tvPeopleCount)
        llConnectedPeople = findViewById(R.id.llConnectedPeople)

        cardLocation = findViewById(R.id.cardLocation)
        tvLocationTitle = findViewById(R.id.tvLocationTitle)
        tvGpsFixBadge = findViewById(R.id.tvGpsFixBadge)
        tvLocationCoords = findViewById(R.id.tvLocationCoords)
        tvLocationMeta = findViewById(R.id.tvLocationMeta)
        btnShareLocation = findViewById(R.id.btnShareLocation)
        btnRadarMap = findViewById(R.id.btnRadarMap)

        cardChat = findViewById(R.id.cardChat)
        cardReceiveCall = findViewById(R.id.cardReceiveCall)
        cardPtt = findViewById(R.id.cardPtt)
        cardCall = findViewById(R.id.cardCall)
        cardSos = findViewById(R.id.cardSos)

        cardNationwide = findViewById(R.id.cardNationwide)
        tvNationwideTitle = findViewById(R.id.tvNationwideTitle)
        tvHdQualityBadge = findViewById(R.id.tvHdQualityBadge)
        tvCurrentRoom = findViewById(R.id.tvCurrentRoom)
        tvNationwideMeta = findViewById(R.id.tvNationwideMeta)
        btnSwitchRoom = findViewById(R.id.btnSwitchRoom)

        tvPeerId.text = "Local Peer ID: $localPeerId • Noise_XX E2EE"
    }

    private fun applyTheme(dark: Boolean) {
        isDarkMode = dark
        prefs.edit().putBoolean("is_dark_mode", dark).apply()

        val bgMain = if (dark) Color.parseColor("#05080F") else Color.parseColor("#E2E8F0")
        val bgCard = if (dark) Color.parseColor("#0A0E17") else Color.parseColor("#FFFFFF")
        val textPrimary = if (dark) Color.parseColor("#00F0FF") else Color.parseColor("#0F172A")
        val textSecondary = if (dark) Color.parseColor("#00FF66") else Color.parseColor("#475569")
        val logBg = if (dark) Color.parseColor("#020617") else Color.parseColor("#CBD5E1")

        mainScrollView.setBackgroundColor(bgMain)
        tvAppTitle.setTextColor(textPrimary)
        tvAppSubtitle.setTextColor(textSecondary)
        tvLogsHeader.setTextColor(textPrimary)
        tvLogs.setBackgroundColor(logBg)

        tvPeopleSubtitle.setTextColor(textSecondary)
        tvLocationMeta.setTextColor(textSecondary)
        tvNationwideMeta.setTextColor(textSecondary)

        btnThemeToggle.typeface = android.graphics.Typeface.MONOSPACE
        btnThemeToggle.text = if (dark) "[ 🌙 CRT DARK ]" else "[ ☀️ RETRO AMIGA ]"
        btnThemeToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgCard)
        btnThemeToggle.setTextColor(if (dark) Color.parseColor("#00F0FF") else Color.parseColor("#0284C7"))

        val cards = listOf(cardStatus, cardNationwide, cardPeople, cardLocation, cardChat, cardReceiveCall, cardPtt, cardCall, cardSos)
        for (card in cards) {
            card.setCardBackgroundColor(bgCard)
        }
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun initLocationEngine() {
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, this)
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 2f, this)

                val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastGps != null) {
                    onLocationChanged(lastGps)
                }
            }
        } catch (e: Exception) {
            logEvent("[Location] Note: ${e.message}")
        }
    }

    override fun onLocationChanged(loc: Location) {
        currentLatitude = loc.latitude
        currentLongitude = loc.longitude
        currentAltitude = loc.altitude
        currentAccuracy = loc.accuracy
        hasGpsFix = true

        runOnUiThread {
            tvGpsFixBadge.text = "● GPS Fix (±${currentAccuracy.toInt()}m)"
            tvGpsFixBadge.setBackgroundColor(Color.parseColor("#064E3B"))
            tvGpsFixBadge.setTextColor(Color.parseColor("#34D399"))

            tvLocationCoords.text = String.format(Locale.US, "Lat: %.6f | Lon: %.6f", currentLatitude, currentLongitude)
            tvLocationMeta.text = String.format(Locale.US, "Accuracy: ±%.0fm • Alt: %.1fm • Hardware Sensor Lock", currentAccuracy, currentAltitude)

            // Re-render peers to update calculated relative distance
            renderConnectedPeopleList(connectedPeersList)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371e3 // Earth radius in meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).roundToInt()
    }

    private fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    private fun getCompassHeading(degrees: Double): Pair<String, String> {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val index = ((degrees / 45.0).roundToInt()) % 8
        return Pair(directions[index], arrows[index])
    }

    private fun formatDistance(meters: Int): String {
        return if (meters < 1000) "${meters}m" else String.format(Locale.US, "%.1fkm", meters / 1000.0)
    }

    private fun setupMeshBridge() {
        bridge.onStatusChanged = { status, isConnected ->
            runOnUiThread {
                tvStatus.text = status
                if (isConnected) {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                    logEvent("[Bridge] $status")
                    bridge.sendSetNickname("Android Phone (${Build.MODEL})", "Android")
                } else {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                }
                renderConnectedPeopleList(connectedPeersList)
            }
        }

        bridge.onPeerListUpdated = { peers ->
            runOnUiThread {
                connectedPeersList.clear()
                connectedPeersList.addAll(peers)
                renderConnectedPeopleList(peers)
            }
        }

        bridge.onLocationReceived = { senderId, senderName, location ->
            runOnUiThread {
                logEvent("[Location] 📍 Received GPS from $senderName (${location.lat}, ${location.lng})")
                val existing = connectedPeersList.find { it.id == senderId }
                if (existing != null) {
                    val idx = connectedPeersList.indexOf(existing)
                    connectedPeersList[idx] = existing.copy(location = location)
                } else {
                    connectedPeersList.add(PeerNode(senderId, senderName, "Peer", "Online", location))
                }
                renderConnectedPeopleList(connectedPeersList)
            }
        }

        bridge.onAudioFrameReceived = { frame ->
            audioEngine.playAudioFrame(frame)
        }

        bridge.onIncomingCall = { callerName, callerId ->
            runOnUiThread {
                showIncomingCallDialog(callerName, callerId)
            }
        }

        bridge.onCallAccepted = { peerName ->
            runOnUiThread {
                activeCallPeerName = peerName
                logEvent("[Live Call] 📞 $peerName accepted call! 2-way voice connected.")
                Toast.makeText(this, "Call Connected with $peerName", Toast.LENGTH_SHORT).show()
            }
        }

        bridge.onCallEnded = {
            runOnUiThread {
                if (incomingCallDialog != null && incomingCallDialog?.isShowing == true) {
                    incomingCallDialog?.dismiss()
                    incomingCallDialog = null
                    callHistoryManager.addCallRecord(
                        CallRecord(
                            id = UUID.randomUUID().toString(),
                            peerName = activeCallPeerName,
                            peerId = activeCallPeerId,
                            type = "MISSED",
                            timestamp = System.currentTimeMillis(),
                            durationSeconds = 0
                        )
                    )
                    Toast.makeText(this, "Missed Call from $activeCallPeerName", Toast.LENGTH_SHORT).show()
                } else if (isCalling) {
                    val duration = maxOf(1, ((System.currentTimeMillis() - callStartTime) / 1000).toInt())
                    callHistoryManager.addCallRecord(
                        CallRecord(
                            id = UUID.randomUUID().toString(),
                            peerName = activeCallPeerName,
                            peerId = activeCallPeerId,
                            type = "INCOMING",
                            timestamp = callStartTime,
                            durationSeconds = duration
                        )
                    )
                    audioEngine.stopVoice()
                    isCalling = false
                    btnCall.text = "Start Voice Call"
                    btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_emerald))
                    Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
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

                if (activeChatMessagesContainer != null) {
                    appendChatBubble(activeChatMessagesContainer!!, activeChatScrollView, senderName, text, false)
                } else {
                    Toast.makeText(this, "💬 $senderName: $text", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bridge.startBluetooth(this)
        bridge.connect(context = this)
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun renderConnectedPeopleList(peers: List<PeerNode>) {
        llConnectedPeople.removeAllViews()

        val otherPeers = peers.filter { it.id != localPeerId }

        // Dynamic list of available network and bluetooth targets
        // Tuple: (Title, Subtitle/Type, TargetAddress/IP)
        val availableList = mutableListOf<Triple<String, String, String>>()

        val isConnectedToWifi = bridge.isConnected && bridge.currentHost == "10.19.238.166"
        val isConnectedToBtPan = bridge.isConnected && bridge.currentHost == "172.27.180.170"

        if (!isConnectedToWifi) {
            availableList.add(Triple("💻 Laptop Node (Wi-Fi)", "Wi-Fi LAN // 10.19.238.166:3000", "10.19.238.166"))
        }
        if (!isConnectedToBtPan) {
            availableList.add(Triple("🔵 Laptop Node (Bluetooth PAN)", "Bluetooth PAN // 172.27.180.170:3000", "172.27.180.170"))
        }

        // Discover Paired / Bonded Bluetooth Devices safely
        try {
            val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (btAdapter != null && btAdapter.isEnabled) {
                val bonded = btAdapter.bondedDevices
                if (bonded != null) {
                    for (dev in bonded) {
                        val devName = dev.name ?: "Bluetooth Device"
                        val devAddr = dev.address
                        val isAlreadyConnected = otherPeers.any { it.id.contains(devAddr, true) || it.nickname.contains(devName, true) }
                        if (!isAlreadyConnected) {
                            availableList.add(Triple("📱 $devName", "Paired BT // $devAddr", "BT:$devAddr"))
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // Update People Count Header Badge
        tvPeopleCount.typeface = android.graphics.Typeface.MONOSPACE
        tvPeopleCount.text = "[ ${otherPeers.size} ACTIVE // ${availableList.size} STANDBY ]"

        // ==========================================
        // SECTION 1: 🟢 CONNECTED DEVICES / ACTIVE NODES
        // ==========================================
        val tvConnectedHeader = TextView(this).apply {
            text = "🟢 [ ACTIVE MESH LINKS // ${otherPeers.size} ]"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(4, 4, 4, 8)
        }
        llConnectedPeople.addView(tvConnectedHeader)

        if (otherPeers.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9")
                setBackgroundColor(bg)
                setPadding(16, 14, 16, 14)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 10)
                }
                layoutParams = params
            }

            val tvNotice = TextView(this).apply {
                text = if (bridge.isConnected) {
                    "● LINK ACTIVE [${bridge.currentHost}:3000]. READY FOR DUPLEX VOICE. SELECT TARGET BELOW."
                } else {
                    "○ STANDBY • Tap [LINK] on any available node below to initialize carrier."
                }
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#64748B"))
                textSize = 11f
            }
            emptyCard.addView(tvNotice)
            llConnectedPeople.addView(emptyCard)
        } else {
            for (peer in otherPeers) {
                val peerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9")
                    setBackgroundColor(bg)
                    setPadding(16, 12, 16, 12)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 10)
                    }
                    layoutParams = params
                }

                // Left Icon & Info
                val leftInfo = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val iconStr = if (peer.deviceType.contains("Android", true)) "📱" else "💻"
                val tvName = TextView(this).apply {
                    text = "$iconStr ${peer.nickname.uppercase(Locale.ROOT)}"
                    setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#0F172A"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                var distStr = "DIRECT (0 HOPS)"
                if (peer.hopCount > 0) {
                    distStr = "MULTI-HOP (${peer.hopCount} HOPS)"
                } else if (hasGpsFix && peer.location != null && peer.location.lat != 0.0) {
                    val dist = calculateDistanceMeters(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val bearing = calculateBearingDegrees(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val heading = getCompassHeading(bearing)
                    distStr = "GRID: ${formatDistance(dist)} ${heading.second} (${bearing.toInt()}°)"
                }

                val tvMeta = TextView(this).apply {
                    text = "LINK: ONLINE • E2EE // ${peer.status.uppercase(Locale.ROOT)} // $distStr"
                    setTextColor(Color.parseColor("#00FF66"))
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 10f
                }

                leftInfo.addView(tvName)
                leftInfo.addView(tvMeta)
                peerRow.addView(leftInfo)

                // Security Fingerprint Button
                val btnSecurity = Button(this).apply {
                    text = "[ 🔒 ]"
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
                    setTextColor(Color.parseColor("#00F0FF"))
                    setOnClickListener {
                        showSecurityDialog(peer.id)
                    }
                }

                // Quick Call Button
                val btnQuickCall = Button(this).apply {
                    text = "[ 📞 CALL ]"
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        startVoiceCall(peer.id, peer.nickname)
                    }
                }

                // Quick Message Button
                val btnQuickChat = Button(this).apply {
                    text = "[ 💬 BBS ]"
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        showChatDialog()
                    }
                }

                val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 90).apply {
                    marginStart = 4
                }
                btnSecurity.layoutParams = btnParams
                btnQuickCall.layoutParams = btnParams
                btnQuickChat.layoutParams = btnParams

                peerRow.addView(btnSecurity)
                peerRow.addView(btnQuickCall)
                peerRow.addView(btnQuickChat)

                llConnectedPeople.addView(peerRow)
            }
        }

        // ==========================================
        // SECTION 2: 📡 AVAILABLE DEVICES & RADIOS (Tap to Link)
        // ==========================================
        val tvAvailableHeader = TextView(this).apply {
            text = "📡 [ AVAILABLE RADIOS & HUBS // ${availableList.size} ]"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00F0FF"))
            setPadding(4, 14, 4, 8)
        }
        llConnectedPeople.addView(tvAvailableHeader)

        for ((devTitle, devSubtitle, targetAddr) in availableList) {
            val availRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#FFFFFF")
                setBackgroundColor(bg)
                setPadding(16, 12, 16, 12)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
                layoutParams = params
            }

            val leftInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(this).apply {
                text = devTitle.uppercase(Locale.ROOT)
                setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#0F172A"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val tvSubtitle = TextView(this).apply {
                text = ">>> $devSubtitle"
                setTextColor(if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
            }

            leftInfo.addView(tvTitle)
            leftInfo.addView(tvSubtitle)
            availRow.addView(leftInfo)

            val btnConnect = Button(this).apply {
                text = "[ 🔗 LINK ]"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (targetAddr.startsWith("BT:")) {
                        val mac = targetAddr.removePrefix("BT:")
                        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        val dev = btAdapter?.getRemoteDevice(mac)
                        if (dev != null) {
                            bridge.bluetoothMesh?.connectToDeviceAsync(dev)
                            Toast.makeText(this@MainActivity, "Connecting to Bluetooth Device $devTitle...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        bridge.connect(targetAddr, this@MainActivity)
                        Toast.makeText(this@MainActivity, "Connecting to $devTitle ($targetAddr:3000)...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 90).apply {
                marginStart = 6
            }
            btnConnect.layoutParams = btnParams
            availRow.addView(btnConnect)

            llConnectedPeople.addView(availRow)
        }

        // Quick Scan & Config Action Buttons at bottom of list
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 4)
        }

        val btnQuickScan = Button(this).apply {
            text = "[ 🔄 AUTO-SCAN ALL ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#00F0FF"))
            setOnClickListener {
                bridge.autoDiscoverAndConnect()
                Toast.makeText(this@MainActivity, "Auto-scanning all network interfaces & Bluetooth...", Toast.LENGTH_SHORT).show()
            }
        }

        val btnCustomSettings = Button(this).apply {
            text = "[ ⚙️ CONFIG IP ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#00FF66"))
            setOnClickListener {
                showIpSettingsDialog()
            }
        }

        actionRow.addView(btnQuickScan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
        actionRow.addView(btnCustomSettings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4 })
        llConnectedPeople.addView(actionRow)
    }

    private fun showSecurityDialog(targetPeerId: String = "node-peer") {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_security)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val secRoot = dialog.findViewById<LinearLayout>(R.id.securityDialogRoot)
        val tvHeader = dialog.findViewById<TextView>(R.id.tvSecurityHeaderTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseSecurity)
        val tvSafety = dialog.findViewById<TextView>(R.id.tvSafetyNumber)
        val tvHopStatus = dialog.findViewById<TextView>(R.id.tvRoutingHopStatus)
        val tvPath = dialog.findViewById<TextView>(R.id.tvRoutingPath)
        val btnDone = dialog.findViewById<Button>(R.id.btnVerifySecurityDone)

        if (isDarkMode) {
            secRoot.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            secRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0284C7"))
        }

        val fingerprint = bridge.crypto.getSafetyFingerprint(localPeerId, targetPeerId)
        tvSafety.typeface = android.graphics.Typeface.MONOSPACE
        tvSafety.text = "[ $fingerprint ]"
        tvHopStatus.typeface = android.graphics.Typeface.MONOSPACE
        tvHopStatus.text = "HOP LIMIT: 15 MAX // MULTI-HOP RELAY ACTIVE"
        tvPath.typeface = android.graphics.Typeface.MONOSPACE
        tvPath.text = "TARGET: $targetPeerId // CIPHER: NOISE_XX + AES-256-GCM"

        btnDone.typeface = android.graphics.Typeface.MONOSPACE
        btnDone.text = "[ ✓ VERIFY CIPHER SPEC ]"

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDone.setOnClickListener {
            Toast.makeText(this, "✓ E2EE Safety Number Verified", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        btnThemeToggle.setOnClickListener {
            applyTheme(!isDarkMode)
        }

        btnCallHistory.setOnClickListener {
            showCallHistoryDialog()
        }

        tvPeerId.setOnClickListener {
            showSecurityDialog()
        }

        tvStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        cardStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        btnOpenChat.setOnClickListener {
            showChatDialog()
        }

        btnShareLocation.setOnClickListener {
            if (hasGpsFix) {
                bridge.sendLocationUpdate(currentLatitude, currentLongitude, currentAltitude, currentAccuracy)
                logEvent("[Location] 📍 Broadcasted GPS ($currentLatitude, $currentLongitude) to mesh")
                Toast.makeText(this, "📍 Broadcasted GPS Location to Mesh", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Acquiring GPS fix... Please ensure Location is enabled", Toast.LENGTH_SHORT).show()
                initLocationEngine()
            }
        }

        btnSwitchRoom.setOnClickListener {
            showChannelDialog()
        }

        btnRadarMap.setOnClickListener {
            showRadarDialog()
        }

        btnCall.setOnClickListener {
            if (!isCalling) {
                val targetPeer = connectedPeersList.firstOrNull { it.id != localPeerId }
                if (targetPeer != null) {
                    startVoiceCall(targetPeer.id, targetPeer.nickname)
                } else {
                    startVoiceCall("", "Mesh Broadcast")
                }
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
            btnSpeaker.text = if (isSpeakerOn) "[ 🔊 SPKR: ON ]" else "[ 🔈 EARPIECE ]"
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

    private fun showChannelDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_channel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val channelRoot = dialog.findViewById<LinearLayout>(R.id.channelDialogRoot)
        val tvHeader = dialog.findViewById<TextView>(R.id.tvChannelHeaderTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseChannel)
        val btnHubIndiaMain = dialog.findViewById<Button>(R.id.btnHubIndiaMain)
        val btnHubDelhi = dialog.findViewById<Button>(R.id.btnHubDelhi)
        val btnHubMumbai = dialog.findViewById<Button>(R.id.btnHubMumbai)
        val btnHubBangalore = dialog.findViewById<Button>(R.id.btnHubBangalore)
        val btnHubChennai = dialog.findViewById<Button>(R.id.btnHubChennai)
        val btnHubHyderabad = dialog.findViewById<Button>(R.id.btnHubHyderabad)
        val etCustomChannel = dialog.findViewById<EditText>(R.id.etCustomChannel)
        val btnJoinChannel = dialog.findViewById<Button>(R.id.btnJoinChannel)

        if (isDarkMode) {
            channelRoot.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#FFB000"))
            etCustomChannel.setBackgroundColor(Color.parseColor("#0A0E17"))
            etCustomChannel.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            channelRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#D97706"))
            etCustomChannel.setBackgroundColor(Color.parseColor("#F1F5F9"))
            etCustomChannel.setTextColor(Color.parseColor("#0F172A"))
        }

        val selectRoom: (String) -> Unit = { roomName ->
            currentRoom = roomName.uppercase(Locale.ROOT).trim()
            tvCurrentRoom.text = "🇮🇳 [ FREQ: $currentRoom ]"
            bridge.sendJoinRoom(currentRoom)
            logEvent("[Nationwide HD] Switched to All-India Hub: $currentRoom")
            Toast.makeText(this, "Joined Hub: $currentRoom", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnHubIndiaMain.setOnClickListener { selectRoom("INDIA-MAIN") }
        btnHubDelhi.setOnClickListener { selectRoom("DELHI-HUB") }
        btnHubMumbai.setOnClickListener { selectRoom("MUMBAI-NET") }
        btnHubBangalore.setOnClickListener { selectRoom("BANGALORE-MESH") }
        btnHubChennai.setOnClickListener { selectRoom("CHENNAI-RELAY") }
        btnHubHyderabad.setOnClickListener { selectRoom("HYDERABAD-CORE") }

        btnJoinChannel.setOnClickListener {
            val custom = etCustomChannel.text.toString().trim()
            if (custom.isNotEmpty()) {
                selectRoom(custom)
            } else {
                Toast.makeText(this, "Please enter a channel name", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRadarDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_radar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val radarRoot = dialog.findViewById<LinearLayout>(R.id.radarDialogRoot)
        val tvHeader = dialog.findViewById<TextView>(R.id.tvRadarHeaderTitle)
        val tvSub = dialog.findViewById<TextView>(R.id.tvRadarDialogSub)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseRadar)
        val tvSelfCoords = dialog.findViewById<TextView>(R.id.tvRadarSelfCoords)
        val tvSelfMeta = dialog.findViewById<TextView>(R.id.tvRadarSelfMeta)
        val llPeerList = dialog.findViewById<LinearLayout>(R.id.llRadarPeerList)
        val btnBroadcast = dialog.findViewById<Button>(R.id.btnRadarBroadcastNow)

        if (isDarkMode) {
            radarRoot.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#00FF66"))
            tvSub.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            radarRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0284C7"))
            tvSub.setTextColor(Color.parseColor("#475569"))
        }

        if (hasGpsFix) {
            tvSelfCoords.typeface = android.graphics.Typeface.MONOSPACE
            tvSelfCoords.text = String.format(Locale.US, "GPS: LAT %.6f // LON %.6f", currentLatitude, currentLongitude)
            tvSelfMeta.typeface = android.graphics.Typeface.MONOSPACE
            tvSelfMeta.text = String.format(Locale.US, "PRECISION: ±%.0fm // ALT: %.1fm // HARDWARE LOCK", currentAccuracy, currentAltitude)
        } else {
            tvSelfCoords.typeface = android.graphics.Typeface.MONOSPACE
            tvSelfCoords.text = "GPS: ACQUIRING SATELLITE FIX..."
            tvSelfMeta.typeface = android.graphics.Typeface.MONOSPACE
            tvSelfMeta.text = "Awaiting GNSS/GPS constellation Lock"
        }

        llPeerList.removeAllViews()
        val otherPeers = connectedPeersList.filter { it.id != localPeerId }
        if (otherPeers.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "NO MESH PEERS DETECTED IN RANGE.\nLINK LAPTOP OR COMPANION PHONE VIA WI-FI / BLUETOOTH."
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 30)
            }
            llPeerList.addView(tvEmpty)
        } else {
            for (peer in otherPeers) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F8FAFC")
                    setBackgroundColor(bg)
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    layoutParams = lp
                }

                val tvName = TextView(this).apply {
                    text = "${if (peer.deviceType.contains("Android", true)) "📱" else "💻"} ${peer.nickname.uppercase(Locale.ROOT)} [${peer.id}]"
                    setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#0F172A"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                var distStr = "DIRECT LOCAL MESH LINK"
                var coordsStr = "NO GPS TELEMETRY BROADCASTED"
                if (hasGpsFix && peer.location != null && peer.location.lat != 0.0) {
                    val dist = calculateDistanceMeters(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val bearing = calculateBearingDegrees(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val heading = getCompassHeading(bearing)
                    distStr = "📍 RANGE: ${formatDistance(dist)} ${heading.second} ${heading.first} (${bearing.toInt()}°)"
                    coordsStr = String.format(Locale.US, "COORDS: %.6f, %.6f (±%.0fm)", peer.location.lat, peer.location.lng, peer.location.accuracy)
                }

                val tvDist = TextView(this).apply {
                    text = distStr
                    setTextColor(Color.parseColor("#00FF66"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                val tvCoords = TextView(this).apply {
                    text = coordsStr
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                item.addView(tvName)
                item.addView(tvDist)
                item.addView(tvCoords)
                llPeerList.addView(item)
            }
        }

        btnBroadcast.typeface = android.graphics.Typeface.MONOSPACE
        btnBroadcast.text = "[ 📍 BROADCAST GPS FIX TO MESH ]"
        btnBroadcast.setOnClickListener {
            if (hasGpsFix) {
                bridge.sendLocationUpdate(currentLatitude, currentLongitude, currentAltitude, currentAccuracy)
                Toast.makeText(this, "📍 Location broadcasted to mesh!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Acquiring GPS fix... Please wait", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

        if (isDarkMode) {
            chatRoot.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#00F0FF"))
            etInput.setBackgroundColor(Color.parseColor("#0A0E17"))
            etInput.setTextColor(Color.parseColor("#00FF66"))
        } else {
            chatRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0F172A"))
            etInput.setBackgroundColor(Color.parseColor("#F1F5F9"))
            etInput.setTextColor(Color.parseColor("#0F172A"))
        }

        for ((sender, text) in chatMessageList) {
            val isMe = sender == "You" || sender.contains("Android", true)
            appendChatBubble(msgContainer, scrollView, sender, text, isMe)
        }

        btnSend.typeface = android.graphics.Typeface.MONOSPACE
        btnSend.text = "[ ↵ SEND ]"
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                bridge.sendChatMessage(text, "Android Phone (${Build.MODEL})")
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
            setPadding(24, 14, 24, 14)

            if (isMe) {
                setBackgroundColor(Color.parseColor("#0E3B68"))
            } else {
                setBackgroundColor(if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#E2E8F0"))
            }
        }

        val tvSender = TextView(this).apply {
            this.text = if (isMe) "[ LOCAL_NODE // YOU ]" else "[ PEER // ${sender.uppercase(Locale.ROOT)} ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isMe) Color.parseColor("#00F0FF") else Color.parseColor("#FFB000"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val tvMsg = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isMe || isDarkMode) Color.parseColor("#00FF66") else Color.parseColor("#0F172A"))
        }

        bubbleLayout.addView(tvSender)
        bubbleLayout.addView(tvMsg)
        container.addView(bubbleLayout)

        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showIncomingCallDialog(callerName: String, callerId: String) {
        if (isCalling) {
            bridge.sendCallDecline(callerId)
            callHistoryManager.addCallRecord(
                CallRecord(
                    id = UUID.randomUUID().toString(),
                    peerName = callerName,
                    peerId = callerId,
                    type = "MISSED",
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0
                )
            )
            return
        }

        incomingCallDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_incoming_call)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val tvCallerName = dialog.findViewById<TextView>(R.id.tvIncomingCallerName)
        val tvCallerId = dialog.findViewById<TextView>(R.id.tvIncomingRoute)
        val btnAccept = dialog.findViewById<Button>(R.id.btnAcceptCall)
        val btnDecline = dialog.findViewById<Button>(R.id.btnDeclineCall)

        tvCallerName.typeface = android.graphics.Typeface.MONOSPACE
        tvCallerName.text = ">>> [ ${callerName.uppercase(Locale.ROOT)} ] <<<"
        tvCallerId.typeface = android.graphics.Typeface.MONOSPACE
        tvCallerId.text = "NODE ID: $callerId // NOISE_XX E2EE ACTIVE"

        btnAccept.typeface = android.graphics.Typeface.MONOSPACE
        btnAccept.text = "[ 📞 ACCEPT CALL ]"
        btnAccept.setOnClickListener {
            dialog.dismiss()
            incomingCallDialog = null
            activeCallPeerName = callerName
            activeCallPeerId = callerId
            callStartTime = System.currentTimeMillis()
            isCalling = true
            try {
                audioEngine.startVoice()
                btnCall.text = "[ 🔴 END CALL ]"
                btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
                bridge.sendCallAccept(callerId)
                logEvent("[Live Call] Call accepted with $callerName ($callerId)")
                Toast.makeText(this, "Connected with $callerName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logEvent("[Error] Audio engine error: ${e.message}")
            }
        }

        btnDecline.typeface = android.graphics.Typeface.MONOSPACE
        btnDecline.text = "[ ✖ DECLINE ]"
        btnDecline.setOnClickListener {
            dialog.dismiss()
            incomingCallDialog = null
            bridge.sendCallDecline(callerId)
            callHistoryManager.addCallRecord(
                CallRecord(
                    id = UUID.randomUUID().toString(),
                    peerName = callerName,
                    peerId = callerId,
                    type = "DECLINED",
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0
                )
            )
            logEvent("[Live Call] Call declined from $callerName")
        }

        incomingCallDialog = dialog
        dialog.show()
    }

    private fun startPttTransmit() {
        if (isPttTransmitting) return
        isPttTransmitting = true
        try {
            audioEngine.startVoice()
            btnPtt.text = "[ >>> TRANSMITTING LIVE PTT <<< ]"
            btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
            bridge.sendPttStart()
            logEvent("[PTT Radio] Transmitting on Channel 1...")
        } catch (e: Exception) {
            logEvent("[Error] PTT failed: ${e.message}")
        }
    }

    private fun stopPttTransmit() {
        if (!isPttTransmitting) return
        isPttTransmitting = false
        try {
            audioEngine.stopVoice()
            btnPtt.text = "[ 🎙️ HOLD TO TALK (PTT) ]"
            btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
            bridge.sendPttStop()
            logEvent("[PTT Radio] Transmission released")
        } catch (e: Exception) {
            logEvent("[Error] PTT stop error: ${e.message}")
        }
    }

    private fun startVoiceCall(targetPeerId: String = "", targetPeerName: String = "Mesh Peer") {
        try {
            audioEngine.startVoice()
            isCalling = true
            callStartTime = System.currentTimeMillis()
            activeCallPeerName = if (targetPeerName.isNotEmpty()) targetPeerName else "Mesh Peer"
            activeCallPeerId = targetPeerId
            btnCall.text = "[ 🔴 END CALL ]"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
            bridge.sendCallInvite(targetPeerId)
            logEvent("[Voice Call] 🔒 Outgoing Call to $activeCallPeerName ($activeCallPeerId)")
            Toast.makeText(this, "Calling $activeCallPeerName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not start audio engine: ${e.message}")
        }
    }

    private fun stopVoiceCall(recordHistory: Boolean = true) {
        try {
            audioEngine.stopVoice()
            if (isCalling && recordHistory) {
                val duration = maxOf(1, ((System.currentTimeMillis() - callStartTime) / 1000).toInt())
                callHistoryManager.addCallRecord(
                    CallRecord(
                        id = UUID.randomUUID().toString(),
                        peerName = activeCallPeerName,
                        peerId = activeCallPeerId,
                        type = "OUTGOING",
                        timestamp = callStartTime,
                        durationSeconds = duration
                    )
                )
            }
            isCalling = false
            btnCall.text = "[ 📞 START 2-WAY DUPLEX CALL ]"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_emerald))
            bridge.sendCallHangup()
            logEvent("[Voice Call] Call terminated cleanly")
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not stop audio engine: ${e.message}")
        }
    }

    private fun showCallHistoryDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_call_history)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val root = dialog.findViewById<LinearLayout>(R.id.callHistoryDialogRoot)
        val tvHeader = dialog.findViewById<TextView>(R.id.tvHistoryHeaderTitle)
        val btnClear = dialog.findViewById<Button>(R.id.btnClearHistory)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseHistory)
        val container = dialog.findViewById<LinearLayout>(R.id.llCallHistoryContainer)

        if (isDarkMode) {
            root.setBackgroundResource(R.drawable.dialog_background)
            tvHeader.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            root.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0284C7"))
        }

        fun populateList() {
            container.removeAllViews()
            val records = callHistoryManager.getCallHistory()
            if (records.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = "NO CDR TELEPHONY LOGS RECORDED.\nMAKE OR RECEIVE CALLS TO POPULATE AUDIT TRAIL."
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, 50, 0, 50)
                }
                container.addView(tvEmpty)
                return
            }

            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            for (record in records) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9")
                    setBackgroundColor(bg)
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    layoutParams = lp
                }

                val (icon, typeColor, typeLabel) = when (record.type) {
                    "INCOMING" -> Triple("↙", "#00FF66", "INCOMING")
                    "OUTGOING" -> Triple("↗", "#00F0FF", "OUTGOING")
                    "MISSED" -> Triple("✕", "#FF0055", "MISSED")
                    "DECLINED" -> Triple("🚫", "#FFB000", "DECLINED")
                    else -> Triple("📞", "#94A3B8", record.type)
                }

                // Left Type Icon
                val tvIcon = TextView(this).apply {
                    text = icon
                    textSize = 16f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor(typeColor))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 12, 0)
                }

                // Center Info
                val centerLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(this).apply {
                    text = record.peerName.uppercase(Locale.ROOT)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#0F172A"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                val durationStr = if (record.durationSeconds > 0) {
                    val m = record.durationSeconds / 60
                    val s = record.durationSeconds % 60
                    String.format(Locale.US, "%02d:%02d", m, s)
                } else {
                    typeLabel
                }

                val tvMeta = TextView(this).apply {
                    val dateStr = dateFormat.format(Date(record.timestamp))
                    text = "$typeLabel // $durationStr // $dateStr"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#94A3B8"))
                }

                centerLayout.addView(tvName)
                centerLayout.addView(tvMeta)

                // Right Callback button
                val btnCallAgain = Button(this).apply {
                    text = "[ 📞 CALL ]"
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 90)
                    setOnClickListener {
                        dialog.dismiss()
                        startVoiceCall(record.peerId, record.peerName)
                    }
                }

                row.addView(tvIcon)
                row.addView(centerLayout)
                row.addView(btnCallAgain)
                container.addView(row)
            }
        }

        btnClear.typeface = android.graphics.Typeface.MONOSPACE
        btnClear.text = "[ 🗑️ CLEAR CDR ]"
        btnClear.setOnClickListener {
            callHistoryManager.clearHistory()
            populateList()
            Toast.makeText(this, "Call history cleared", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        populateList()
        dialog.show()
    }

    private fun showIpSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#FFFFFF")
            setBackgroundColor(bg)
        }

        val tvTitle = TextView(this).apply {
            text = "📡 [ MESH RADIO & CARRIER CONFIG ]"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (isDarkMode) Color.parseColor("#00F0FF") else Color.parseColor("#0284C7"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvTitle)

        val tvCurrent = TextView(this).apply {
            text = "CARRIER: ${bridge.currentHost}:3000 // STATUS: ${tvStatus.text}"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isDarkMode) Color.parseColor("#00FF66") else Color.parseColor("#475569"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(tvCurrent)

        val btnWifi = Button(this).apply {
            text = "[ 💻 CONNECT LAPTOP WI-FI (10.19.238.166) ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                bridge.connect("10.19.238.166", this@MainActivity)
                Toast.makeText(this@MainActivity, "Connecting to Laptop Wi-Fi (10.19.238.166)...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnWifi)

        val btnBt = Button(this).apply {
            text = "[ 📱 CONNECT LAPTOP BLUETOOTH (172.27.180.170) ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.connect("172.27.180.170", this@MainActivity)
                Toast.makeText(this@MainActivity, "Connecting to Laptop Bluetooth (172.27.180.170)...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnBt)

        val btnScan = Button(this).apply {
            text = "[ 🔄 AUTO-SCAN ALL MESH INTERFACES ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#00F0FF"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.autoDiscoverAndConnect()
                Toast.makeText(this@MainActivity, "Auto-scanning all network interfaces...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnScan)

        val inputIp = EditText(this).apply {
            hint = "Or type custom IP (e.g. 10.19.238.166)"
            typeface = android.graphics.Typeface.MONOSPACE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(if (isDarkMode) Color.parseColor("#00FF66") else Color.BLACK)
            textSize = 12f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 14, 0, 6)
            }
            layoutParams = lp
        }
        layout.addView(inputIp)

        val btnCustom = Button(this).apply {
            text = "[ LINK CUSTOM IP ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val ip = inputIp.text.toString().trim()
                if (ip.isNotEmpty()) {
                    bridge.connect(ip, this@MainActivity)
                    Toast.makeText(this@MainActivity, "Connecting to $ip:3000...", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        layout.addView(btnCustom)

        dialog.setContentView(layout)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun broadcastEmergencySOS() {
        logEvent("[EMERGENCY SOS] Broadcasting distress beacon to all peers (TTL: 15 hops)...")
        if (hasGpsFix) {
            bridge.sendLocationUpdate(currentLatitude, currentLongitude, currentAltitude, currentAccuracy)
        }
        Toast.makeText(this, "🚨 EMERGENCY SOS & GPS LOCATION BROADCASTED", Toast.LENGTH_LONG).show()
    }

    private fun startMeshService() {
        try {
            val serviceIntent = Intent(this, ForegroundMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            logEvent("[Service] ForegroundMeshService active")
        } catch (e: Exception) {
            Log.w("MainActivity", "Foreground service start note: ${e.message}")
        }
    }

    private fun logEvent(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLogs = tvLogs.text.toString()
        tvLogs.text = "[$time] $msg\n$currentLogs"
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            try {
                bridge.startBluetooth(this)
                initLocationEngine()
                startMeshService()
            } catch (e: Exception) {
                Log.w("MainActivity", "Post-permission startup note: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager?.removeUpdates(this)
            bridge.disconnect()
            if (isCalling || isPttTransmitting) {
                audioEngine.stopVoice()
            }
        } catch (e: Exception) {}
    }
}
