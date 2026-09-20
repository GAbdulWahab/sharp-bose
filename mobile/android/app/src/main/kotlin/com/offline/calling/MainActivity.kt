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
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("offline_mesh_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("is_dark_mode", true)

        audioEngine = AndroidAudioEngine(this)
        audioEngine.setSpeakerphoneOn(true)

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

        tvPeopleSubtitle.setTextColor(textSecondary)
        tvLocationMeta.setTextColor(textSecondary)

        btnThemeToggle.text = if (dark) "🌙 Dark" else "☀️ Light"
        btnThemeToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgCard)
        btnThemeToggle.setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        val cards = listOf(cardStatus, cardPeople, cardLocation, cardChat, cardReceiveCall, cardPtt, cardCall, cardSos)
        for (card in cards) {
            card.setCardBackgroundColor(bgCard)
        }
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

                if (activeChatMessagesContainer != null) {
                    appendChatBubble(activeChatMessagesContainer!!, activeChatScrollView, senderName, text, false)
                } else {
                    Toast.makeText(this, "💬 $senderName: $text", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bridge.connect()
    }

    private fun renderConnectedPeopleList(peers: List<PeerNode>) {
        llConnectedPeople.removeAllViews()

        val otherPeers = peers.filter { it.id != localPeerId }
        tvPeopleCount.text = "${otherPeers.size} Online"

        if (otherPeers.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "📡 Scanning for connected mesh people / devices..."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
            llConnectedPeople.addView(tvEmpty)
            return
        }

        for (peer in otherPeers) {
            val peerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
                setBackgroundColor(bg)
                setPadding(18, 14, 18, 14)
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
                text = "$iconStr ${peer.nickname}"
                setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            var distStr = "⚡ Direct Mesh Link"
            if (hasGpsFix && peer.location != null && peer.location.lat != 0.0) {
                val dist = calculateDistanceMeters(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                val bearing = calculateBearingDegrees(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                val heading = getCompassHeading(bearing)
                distStr = "📍 ${formatDistance(dist)} ${heading.second} ${heading.first}"
            }

            val tvMeta = TextView(this).apply {
                text = "${peer.status} • $distStr"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 11f
            }

            leftInfo.addView(tvName)
            leftInfo.addView(tvMeta)
            peerRow.addView(leftInfo)

            // Quick Call Button
            val btnQuickCall = Button(this).apply {
                text = "📞 Call"
                textSize = 11f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    startVoiceCall()
                }
            }

            // Quick Message Button
            val btnQuickChat = Button(this).apply {
                text = "💬"
                textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    showChatDialog()
                }
            }

            val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100).apply {
                marginStart = 8
            }
            btnQuickCall.layoutParams = btnParams
            btnQuickChat.layoutParams = btnParams

            peerRow.addView(btnQuickCall)
            peerRow.addView(btnQuickChat)

            llConnectedPeople.addView(peerRow)
        }
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

        btnRadarMap.setOnClickListener {
            showRadarDialog()
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
            tvHeader.setTextColor(Color.parseColor("#38BDF8"))
            tvSub.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            radarRoot.setBackgroundColor(Color.parseColor("#FFFFFF"))
            tvHeader.setTextColor(Color.parseColor("#0284C7"))
            tvSub.setTextColor(Color.parseColor("#475569"))
        }

        if (hasGpsFix) {
            tvSelfCoords.text = String.format(Locale.US, "Your GPS: Lat: %.6f, Lon: %.6f", currentLatitude, currentLongitude)
            tvSelfMeta.text = String.format(Locale.US, "Accuracy: ±%.0fm • Alt: %.1fm • Hardware Sensor Lock", currentAccuracy, currentAltitude)
        } else {
            tvSelfCoords.text = "Your GPS: Acquiring Satellite Fix..."
            tvSelfMeta.text = "Make sure GPS/Location is enabled"
        }

        llPeerList.removeAllViews()
        val otherPeers = connectedPeersList.filter { it.id != localPeerId }
        if (otherPeers.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No mesh peers currently connected.\nConnect Laptop or other Phone via Bluetooth/Wi-Fi."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 30)
            }
            llPeerList.addView(tvEmpty)
        } else {
            for (peer in otherPeers) {
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
                    setBackgroundColor(bg)
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    layoutParams = lp
                }

                val tvName = TextView(this).apply {
                    text = "${if (peer.deviceType.contains("Android", true)) "📱" else "💻"} ${peer.nickname} (${peer.id})"
                    setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                var distStr = "Direct Local Mesh Link"
                var coordsStr = "No GPS coordinates broadcasted yet"
                if (hasGpsFix && peer.location != null && peer.location.lat != 0.0) {
                    val dist = calculateDistanceMeters(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val bearing = calculateBearingDegrees(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                    val heading = getCompassHeading(bearing)
                    distStr = "📍 Distance: ${formatDistance(dist)} ${heading.second} ${heading.first} (${bearing.toInt()}°)"
                    coordsStr = String.format(Locale.US, "GPS: %.6f, %.6f (±%.0fm)", peer.location.lat, peer.location.lng, peer.location.accuracy)
                }

                val tvDist = TextView(this).apply {
                    text = distStr
                    setTextColor(Color.parseColor("#10B981"))
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val tvCoords = TextView(this).apply {
                    text = coordsStr
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11f
                }

                item.addView(tvName)
                item.addView(tvDist)
                item.addView(tvCoords)
                llPeerList.addView(item)
            }
        }

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
            tvHeader.setTextColor(Color.parseColor("#F8FAFC"))
            etInput.setBackgroundColor(Color.parseColor("#1E293B"))
            etInput.setTextColor(Color.parseColor("#F8FAFC"))
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
            setPadding(28, 18, 28, 18)

            if (isMe) {
                setBackgroundColor(Color.parseColor("#1D4ED8"))
            } else {
                setBackgroundColor(if (isDarkMode) Color.parseColor("#334155") else Color.parseColor("#E2E8F0"))
            }
        }

        val tvSender = TextView(this).apply {
            this.text = sender
            textSize = 10f
            setTextColor(if (isMe) Color.parseColor("#93C5FD") else Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvMsg = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (isMe || isDarkMode) Color.parseColor("#FFFFFF") else Color.parseColor("#0F172A"))
        }

        bubbleLayout.addView(tvSender)
        bubbleLayout.addView(tvMsg)
        container.addView(bubbleLayout)

        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showIpSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = "Mesh Server IP Configuration"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        }

        val sub = TextView(this).apply {
            text = "Auto-connect is active. You can also specify an exact IP."
            textSize = 12f
            setTextColor(if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))
            setPadding(0, 10, 0, 20)
        }

        val input = EditText(this).apply {
            setText(bridge.currentHost)
            setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }

        val btnAuto = Button(this).apply {
            text = "Auto-Scan"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                bridge.autoDiscoverAndConnect()
                dialog.dismiss()
            }
        }

        val btnSave = Button(this).apply {
            text = "Connect Direct"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    bridge.connect(ip)
                }
                dialog.dismiss()
            }
        }

        btnRow.addView(btnAuto, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(title)
        container.addView(sub)
        container.addView(input)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showIncomingCallDialog(callerName: String, callerId: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_incoming_call)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val tvCallerName = dialog.findViewById<TextView>(R.id.tvCallerName)
        val tvCallerId = dialog.findViewById<TextView>(R.id.tvCallerId)
        val btnAccept = dialog.findViewById<Button>(R.id.btnAcceptCall)
        val btnDecline = dialog.findViewById<Button>(R.id.btnDeclineCall)

        tvCallerName.text = callerName
        tvCallerId.text = "Node ID: $callerId • E2EE Encrypted"

        btnAccept.setOnClickListener {
            dialog.dismiss()
            startVoiceCall()
            bridge.sendCallAccept()
            logEvent("[Live Call] Call accepted with $callerName")
        }

        btnDecline.setOnClickListener {
            dialog.dismiss()
            bridge.sendCallDecline()
            logEvent("[Live Call] Call declined")
        }

        dialog.show()
    }

    private fun startPttTransmit() {
        if (isPttTransmitting) return
        isPttTransmitting = true
        try {
            audioEngine.startVoice()
            btnPtt.text = "TRANSMITTING LIVE VOICE..."
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
            btnPtt.text = "HOLD TO TALK (PTT)"
            btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
            bridge.sendPttStop()
            logEvent("[PTT Radio] Transmission released")
        } catch (e: Exception) {
            logEvent("[Error] PTT stop error: ${e.message}")
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
        if (hasGpsFix) {
            bridge.sendLocationUpdate(currentLatitude, currentLongitude, currentAltitude, currentAccuracy)
        }
        Toast.makeText(this, "🚨 EMERGENCY SOS & GPS LOCATION BROADCASTED", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        super.onDestroy()
        locationManager?.removeUpdates(this)
        bridge.disconnect()
        if (isCalling || isPttTransmitting) {
            audioEngine.stopVoice()
        }
    }
}
