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
import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import com.offline.calling.audio.AndroidAudioEngine
import com.offline.calling.radio.ForegroundMeshService
import com.offline.calling.radio.MeshWebSocketBridge
import com.offline.calling.radio.PeerLocation
import com.offline.calling.radio.PeerNode
import com.offline.calling.radio.RadioTransportMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var audioEngine: AndroidAudioEngine
    private val bridge get() = ForegroundMeshService.getSharedBridge(this)
    private lateinit var callHistoryManager: CallHistoryManager
    private var isCalling = false
    private var isSpeakerOn = true
    private var isPttTransmitting = false
    private val localPeerId get() = bridge.localNodeId
    private var callStartTime: Long = 0L
    private var activeCallPeerName: String = "Mesh Peer"
    private var activeCallPeerId: String = ""
    private var incomingCallDialog: Dialog? = null

    // UI Root & Header
    private lateinit var mainRootLayout: LinearLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var btnToggleBluetooth: Button
    private lateinit var btnToggleWifi: Button
    private lateinit var btnThemeToggle: Button

    // 6 Dedicated Screens
    private lateinit var screenRoster: ScrollView
    private lateinit var screenComms: ScrollView
    private lateinit var screenChat: LinearLayout
    private lateinit var screenRadar: ScrollView
    private lateinit var screenChannels: ScrollView
    private lateinit var screenLogsSecurity: ScrollView

    // 6 Navigation Dock Tabs
    private lateinit var tabRoster: Button
    private lateinit var tabComms: Button
    private lateinit var tabChat: Button
    private lateinit var tabRadar: Button
    private lateinit var tabChannels: Button
    private lateinit var tabLogs: Button
    private var currentTabIndex: Int = 0

    // Screen 1: Roster UI
    private lateinit var cardStatus: CardView
    private lateinit var tvStatus: TextView
    private lateinit var tvPeerId: TextView
    private lateinit var tvMeshStats: TextView
    private lateinit var cardPeople: CardView
    private lateinit var tvPeopleTitle: TextView
    private lateinit var tvPeopleSubtitle: TextView
    private lateinit var tvPeopleCount: TextView
    private lateinit var llConnectedPeople: LinearLayout
    private lateinit var cardControls: CardView
    private lateinit var btnBtPower: Button
    private lateinit var btnWifiPower: Button
    private lateinit var btnCarrierBtOnly: Button
    private lateinit var btnCarrierWifiOnly: Button
    private lateinit var btnCarrierCombined: Button
    private lateinit var btnAutoScanMesh: Button
    private lateinit var btnConfigNodeIp: Button
    private var isBtRadioEnabled = true
    private var isWifiRadioEnabled = true

    // Screen 2: Comms UI
    private lateinit var cardCall: CardView
    private lateinit var btnCall: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnReceiveCall: Button
    private lateinit var cardPtt: CardView
    private lateinit var tvPttChannel: TextView
    private lateinit var btnPtt: Button
    private lateinit var cardSos: CardView
    private lateinit var btnSos: Button

    // Screen 3: Chat BBS UI
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var etChatMessage: EditText
    private lateinit var btnSendChatMessage: Button
    private val chatMessageList = mutableListOf<Pair<String, String>>() // (sender, text)

    // Screen 4: Radar UI
    private lateinit var tvRadarSelfCoords: TextView
    private lateinit var tvRadarSelfMeta: TextView
    private lateinit var btnRadarBroadcastNow: Button
    private lateinit var llRadarPeerList: LinearLayout

    // Screen 5: Channels UI
    private lateinit var cardNationwide: CardView
    private lateinit var tvNationwideTitle: TextView
    private lateinit var tvCurrentRoom: TextView
    private lateinit var tvNationwideMeta: TextView
    private lateinit var btnHubIndiaMain: Button
    private lateinit var btnHubDelhi: Button
    private lateinit var btnHubMumbai: Button
    private lateinit var btnHubBangalore: Button
    private lateinit var btnHubChennai: Button
    private lateinit var btnHubHyderabad: Button
    private lateinit var etCustomChannel: EditText
    private lateinit var btnJoinChannel: Button
    private var currentRoom: String = "INDIA-MAIN"

    // Screen 6: Logs & Security UI
    private lateinit var tvSafetyNumber: TextView
    private lateinit var tvRoutingHopStatus: TextView
    private lateinit var tvRoutingPath: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var llCallHistoryContainer: LinearLayout
    private lateinit var tvLogsHeader: TextView
    private lateinit var tvLogs: TextView

    // Location State
    private var locationManager: LocationManager? = null
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentAltitude: Double = 0.0
    private var currentAccuracy: Float = 0f
    private var hasGpsFix: Boolean = false

    // Peer Roster State
    private var connectedPeersList = mutableListOf<PeerNode>()

    // Theme State
    private var isDarkMode = true
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("MainActivity", "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
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
        switchScreen(0)
        checkAndRequestPermissions()
        startMeshService()
        initLocationEngine()
        setupUIListeners()
        setupMeshBridge()
        handleIncomingCallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        try {
            startMeshService()
            bridge.startBluetooth(this)
            bridge.autoDiscoverAndConnect()
            bridge.startBluetoothScan()
            renderConnectedPeopleList(connectedPeersList)
        } catch (e: Exception) {
            Log.w("MainActivity", "onResume auto-connect note: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_INCOMING_CALL", false) == true) {
            val callerName = intent.getStringExtra("EXTRA_CALLER_NAME") ?: "Mesh Peer"
            val callerId = intent.getStringExtra("EXTRA_CALLER_ID") ?: ""
            switchScreen(1)
            showIncomingCallDialog(callerName, callerId)
        }
    }

    private fun bindViews() {
        mainRootLayout = findViewById(R.id.mainRootLayout)
        headerLayout = findViewById(R.id.headerLayout)
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvAppSubtitle = findViewById(R.id.tvAppSubtitle)
        btnToggleBluetooth = findViewById(R.id.btnToggleBluetooth)
        btnToggleWifi = findViewById(R.id.btnToggleWifi)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)

        // 6 Dedicated Screen Containers
        screenRoster = findViewById(R.id.screenRoster)
        screenComms = findViewById(R.id.screenComms)
        screenChat = findViewById(R.id.screenChat)
        screenRadar = findViewById(R.id.screenRadar)
        screenChannels = findViewById(R.id.screenChannels)
        screenLogsSecurity = findViewById(R.id.screenLogsSecurity)

        // 6 Navigation Dock Tabs
        tabRoster = findViewById(R.id.tabRoster)
        tabComms = findViewById(R.id.tabComms)
        tabChat = findViewById(R.id.tabChat)
        tabRadar = findViewById(R.id.tabRadar)
        tabChannels = findViewById(R.id.tabChannels)
        tabLogs = findViewById(R.id.tabLogs)

        // Screen 1: Roster
        cardStatus = findViewById(R.id.cardStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvPeerId = findViewById(R.id.tvPeerId)
        tvMeshStats = findViewById(R.id.tvMeshStats)
        cardPeople = findViewById(R.id.cardPeople)
        tvPeopleTitle = findViewById(R.id.tvPeopleTitle)
        tvPeopleSubtitle = findViewById(R.id.tvPeopleSubtitle)
        tvPeopleCount = findViewById(R.id.tvPeopleCount)
        llConnectedPeople = findViewById(R.id.llConnectedPeople)
        cardControls = findViewById(R.id.cardControls)
        btnBtPower = findViewById(R.id.btnBtPower)
        btnWifiPower = findViewById(R.id.btnWifiPower)
        btnCarrierBtOnly = findViewById(R.id.btnCarrierBtOnly)
        btnCarrierWifiOnly = findViewById(R.id.btnCarrierWifiOnly)
        btnCarrierCombined = findViewById(R.id.btnCarrierCombined)
        btnAutoScanMesh = findViewById(R.id.btnAutoScanMesh)
        btnConfigNodeIp = findViewById(R.id.btnConfigNodeIp)

        // Screen 2: Comms
        cardCall = findViewById(R.id.cardCall)
        btnCall = findViewById(R.id.btnCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnReceiveCall = findViewById(R.id.btnReceiveCall)
        cardPtt = findViewById(R.id.cardPtt)
        tvPttChannel = findViewById(R.id.tvPttChannel)
        btnPtt = findViewById(R.id.btnPtt)
        cardSos = findViewById(R.id.cardSos)
        btnSos = findViewById(R.id.btnSos)

        // Screen 3: Chat BBS
        chatScrollView = findViewById(R.id.chatScrollView)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        etChatMessage = findViewById(R.id.etChatMessage)
        btnSendChatMessage = findViewById(R.id.btnSendChatMessage)

        // Screen 4: Radar
        tvRadarSelfCoords = findViewById(R.id.tvRadarSelfCoords)
        tvRadarSelfMeta = findViewById(R.id.tvRadarSelfMeta)
        btnRadarBroadcastNow = findViewById(R.id.btnRadarBroadcastNow)
        llRadarPeerList = findViewById(R.id.llRadarPeerList)

        // Screen 5: Channels
        cardNationwide = findViewById(R.id.cardNationwide)
        tvNationwideTitle = findViewById(R.id.tvNationwideTitle)
        tvCurrentRoom = findViewById(R.id.tvCurrentRoom)
        tvNationwideMeta = findViewById(R.id.tvNationwideMeta)
        btnHubIndiaMain = findViewById(R.id.btnHubIndiaMain)
        btnHubDelhi = findViewById(R.id.btnHubDelhi)
        btnHubMumbai = findViewById(R.id.btnHubMumbai)
        btnHubBangalore = findViewById(R.id.btnHubBangalore)
        btnHubChennai = findViewById(R.id.btnHubChennai)
        btnHubHyderabad = findViewById(R.id.btnHubHyderabad)
        etCustomChannel = findViewById(R.id.etCustomChannel)
        btnJoinChannel = findViewById(R.id.btnJoinChannel)

        // Screen 6: Logs & Security
        tvSafetyNumber = findViewById(R.id.tvSafetyNumber)
        tvRoutingHopStatus = findViewById(R.id.tvRoutingHopStatus)
        tvRoutingPath = findViewById(R.id.tvRoutingPath)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        llCallHistoryContainer = findViewById(R.id.llCallHistoryContainer)
        tvLogsHeader = findViewById(R.id.tvLogsHeader)
        tvLogs = findViewById(R.id.tvLogs)

        tvPeerId.text = "NODE ID: $localPeerId • CIPHER: NOISE_XX"
    }

    private fun switchScreen(tabIndex: Int) {
        currentTabIndex = tabIndex

        val screens = listOf(screenRoster, screenComms, screenChat, screenRadar, screenChannels, screenLogsSecurity)
        val tabs = listOf(tabRoster, tabComms, tabChat, tabRadar, tabChannels, tabLogs)

        for (i in screens.indices) {
            screens[i].visibility = if (i == tabIndex) View.VISIBLE else View.GONE
        }

        for (i in tabs.indices) {
            if (i == tabIndex) {
                tabs[i].setTextColor(Color.parseColor("#38BDF8"))
                tabs[i].backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F2744"))
            } else {
                tabs[i].setTextColor(Color.parseColor("#64748B"))
                tabs[i].backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0B0F19"))
            }
        }

        when (tabIndex) {
            0 -> renderConnectedPeopleList(connectedPeersList)
            3 -> updateRadarView()
            5 -> {
                updateSecurityView()
                renderCallHistoryView()
            }
        }
    }

    private fun applyTheme(dark: Boolean) {
        isDarkMode = dark
        prefs.edit().putBoolean("is_dark_mode", dark).apply()

        val bgMain = if (dark) Color.parseColor("#0B0F19") else Color.parseColor("#F1F5F9")
        val bgCard = if (dark) Color.parseColor("#131D31") else Color.parseColor("#FFFFFF")
        val textPrimary = if (dark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val textSecondary = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val logBg = if (dark) Color.parseColor("#070A13") else Color.parseColor("#E2E8F0")

        mainRootLayout.setBackgroundColor(bgMain)
        tvAppTitle.setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        tvAppSubtitle.setTextColor(if (dark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
        tvLogsHeader.setTextColor(textPrimary)
        tvLogs.setBackgroundColor(logBg)

        btnThemeToggle.text = if (dark) "🌙 Dark" else "☀️ Light"
        btnThemeToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgCard)
        btnThemeToggle.setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        val cards = listOf(cardStatus, cardNationwide, cardPeople, cardControls, cardCall, cardPtt, cardSos)
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

    override fun onLocationChanged(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentAltitude = location.altitude
        currentAccuracy = location.accuracy
        hasGpsFix = true
        updateRadarView()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun updateRadarView() {
        if (hasGpsFix) {
            tvRadarSelfCoords.typeface = android.graphics.Typeface.MONOSPACE
            tvRadarSelfCoords.text = String.format(Locale.US, "GPS: LAT %.6f // LON %.6f", currentLatitude, currentLongitude)
            tvRadarSelfMeta.typeface = android.graphics.Typeface.MONOSPACE
            tvRadarSelfMeta.text = String.format(Locale.US, "PRECISION: ±%.0fm // ALT: %.1fm // HARDWARE LOCK", currentAccuracy, currentAltitude)
        } else {
            tvRadarSelfCoords.typeface = android.graphics.Typeface.MONOSPACE
            tvRadarSelfCoords.text = "GPS: ACQUIRING SATELLITE FIX..."
            tvRadarSelfMeta.typeface = android.graphics.Typeface.MONOSPACE
            tvRadarSelfMeta.text = "Awaiting GNSS/GPS constellation lock"
        }

        llRadarPeerList.removeAllViews()
        val otherPeers = connectedPeersList.filter { it.id != localPeerId }
        if (otherPeers.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "NO MESH PEERS IN RANGE.\nLINK LAPTOP OR COMPANION PHONE VIA WI-FI / BLUETOOTH."
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 30)
            }
            llRadarPeerList.addView(tvEmpty)
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

                val cleanRawId = peer.id.replace("node-", "")
                val anonSuffix = if (cleanRawId.length >= 4) cleanRawId.takeLast(4).uppercase(Locale.ROOT) else cleanRawId.uppercase(Locale.ROOT)
                val anonId = "ANON-$anonSuffix"

                val tvName = TextView(this).apply {
                    text = "${if (peer.deviceType.contains("Android", true)) "📱" else "💻"} $anonId • [${peer.nickname.uppercase(Locale.ROOT)}]"
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
                llRadarPeerList.addView(item)
            }
        }
    }

    private fun updateSecurityView(targetPeerId: String = "node-peer") {
        val fingerprint = bridge.crypto.getSafetyFingerprint(localPeerId, targetPeerId)
        tvSafetyNumber.typeface = android.graphics.Typeface.MONOSPACE
        tvSafetyNumber.text = "[ $fingerprint ]"
        tvRoutingHopStatus.typeface = android.graphics.Typeface.MONOSPACE
        tvRoutingHopStatus.text = "HOP LIMIT: 15 MAX // MULTI-HOP RELAY ACTIVE"
        tvRoutingPath.typeface = android.graphics.Typeface.MONOSPACE
        tvRoutingPath.text = "TARGET: $targetPeerId // CIPHER: NOISE_XX + AES-256-GCM"
    }

    private fun renderCallHistoryView() {
        llCallHistoryContainer.removeAllViews()
        val records = callHistoryManager.getCallHistory()
        if (records.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "NO CDR TELEPHONY LOGS RECORDED.\nMAKE OR RECEIVE CALLS TO POPULATE AUDIT TRAIL."
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 30)
            }
            llCallHistoryContainer.addView(tvEmpty)
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

            val tvIcon = TextView(this).apply {
                text = icon
                textSize = 16f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor(typeColor))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 12, 0)
            }

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

            val btnCallAgain = Button(this).apply {
                text = "[ 📞 CALL ]"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 90)
                setOnClickListener {
                    switchScreen(1)
                    startVoiceCall(record.peerId, record.peerName)
                }
            }

            row.addView(tvIcon)
            row.addView(centerLayout)
            row.addView(btnCallAgain)
            llCallHistoryContainer.addView(row)
        }
    }

    private fun setupMeshBridge() {
        bridge.onAudioFrameReceived = { frame ->
            audioEngine.playAudioFrame(frame)
        }

        bridge.onLocationReceived = { senderId, senderName, location ->
            runOnUiThread {
                val existing = connectedPeersList.find { it.id == senderId }
                if (existing != null) {
                    val idx = connectedPeersList.indexOf(existing)
                    connectedPeersList[idx] = existing.copy(location = location)
                } else {
                    connectedPeersList.add(PeerNode(senderId, senderName, "Peer", location = location))
                }
                updateRadarView()
            }
        }

        bridge.onMeshStatusChanged = { status ->
            runOnUiThread {
                tvStatus.text = status
                tvPeerId.text = "NODE ID: $localPeerId • CIPHER: NOISE_XX"
                if (status.contains("Online", true) || status.contains("Connected", true) || status.contains("Active", true)) {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_emerald))
                } else {
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                }
                if (currentTabIndex == 0) {
                    renderConnectedPeopleList(connectedPeersList)
                }
            }
        }

        bridge.onPeersUpdated = { peers ->
            runOnUiThread {
                tvPeerId.text = "NODE ID: $localPeerId • CIPHER: NOISE_XX"
                connectedPeersList = peers.toMutableList()
                if (currentTabIndex == 0) {
                    renderConnectedPeopleList(connectedPeersList)
                }
                updateRadarView()
            }
        }

        bridge.onIncomingCall = { callerName, callerId ->
            runOnUiThread {
                showIncomingCallDialog(callerName, callerId)
            }
        }

        bridge.onCallAccepted = { peerId ->
            runOnUiThread {
                isCalling = true
                if (peerId.isNotEmpty()) {
                    activeCallPeerId = peerId
                }
                callStartTime = System.currentTimeMillis()
                btnCall.text = "[ 🔴 END CALL ]"
                btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
                try {
                    audioEngine.startVoice()
                    logEvent("[Live Call] Call connected with peer ($peerId)")
                    Toast.makeText(this, "Call Connected", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    logEvent("[Error] Failed to start audio: ${e.message}")
                }
            }
        }

        bridge.onCallDeclined = { peerId ->
            runOnUiThread {
                stopVoiceCall(recordHistory = true, notifyRemote = false)
                logEvent("[Live Call] Call was declined by $peerId")
                Toast.makeText(this, "Call Declined", Toast.LENGTH_SHORT).show()
            }
        }

        bridge.onCallTerminated = {
            runOnUiThread {
                stopVoiceCall(recordHistory = true, notifyRemote = false)
                logEvent("[Live Call] Remote peer ended the call")
            }
        }

        bridge.onPttStarted = { speakerName ->
            runOnUiThread {
                tvPttChannel.text = "FREQ 01 • FLOOR: $speakerName (SPEAKING...)"
                tvPttChannel.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
            }
        }

        bridge.onPttStopped = {
            runOnUiThread {
                tvPttChannel.text = "FREQ 01: EMERGENCY & TACTICAL • FLOOR: CLEAR"
                tvPttChannel.setTextColor(ContextCompat.getColor(this, R.color.neon_emerald))
            }
        }

        bridge.onChatMessageReceived = { senderName, text ->
            runOnUiThread {
                chatMessageList.add(Pair(senderName, text))
                logEvent("[Chat] 💬 $senderName: $text")
                appendChatBubble(chatMessagesContainer, chatScrollView, senderName, text, false)
            }
        }

        bridge.startBluetooth(this)
        bridge.connect(context = this)
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun renderConnectedPeopleList(peers: List<PeerNode>) {
        llConnectedPeople.removeAllViews()

        val otherPeers = peers.filter { 
            it.id.isNotEmpty() &&
            it.id != localPeerId && 
            !it.id.equals(localPeerId, true) && 
            !it.id.equals(bridge.localNodeId, true) &&
            !it.id.equals("node-local", true) &&
            !it.nickname.contains("(Host)", true) &&
            !it.nickname.contains("Desktop Local", true)
        }
        
        val singlePeer = otherPeers.firstOrNull()
        tvPeopleCount.text = if (singlePeer != null) "1 Connected" else "0 Connected"

        if (singlePeer == null) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#F1F5F9")
                setBackgroundColor(bg)
                setPadding(20, 18, 20, 18)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = params
            }

            val tvNotice = TextView(this).apply {
                val statusMsg = if (bridge.bluetoothMesh?.hasConnectedPeers() == true) {
                    "● Direct Hardware Bluetooth Active\nConnected peer-to-peer over Bluetooth RFCOMM."
                } else if (bridge.isConnected) {
                    val hostInfo = if (bridge.currentHost.isNotEmpty()) " (${bridge.currentHost})" else ""
                    "● Wi-Fi Mesh Connected$hostInfo\nMesh link active on local network."
                } else {
                    "○ Scanning Direct Radios...\nBluetooth & Wi-Fi auto-discovery active in background."
                }
                text = statusMsg
                setTextColor(if (isDarkMode) Color.parseColor("#38BDF8") else Color.parseColor("#64748B"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setLineSpacing(4f, 1.0f)
            }
            emptyCard.addView(tvNotice)
            llConnectedPeople.addView(emptyCard)
            return
        }

        val peer = singlePeer
        val peerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#F1F5F9")
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

            val leftInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val iconStr = if (peer.deviceType.contains("Android", true)) "📱" else "💻"
            val cleanRawId = peer.id.replace("node-", "")
            val anonSuffix = if (cleanRawId.length >= 4) cleanRawId.takeLast(4).uppercase(Locale.ROOT) else cleanRawId.uppercase(Locale.ROOT)
            val anonId = "ANON-$anonSuffix"

            val tvName = TextView(this).apply {
                text = "$iconStr $anonId • [${peer.nickname.uppercase(Locale.ROOT)}]"
                setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
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
                text = "STATUS: ${peer.status.uppercase(Locale.ROOT)} • ANON ID: $anonId • E2EE // $distStr"
                setTextColor(Color.parseColor("#10B981"))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
            }

            leftInfo.addView(tvName)
            leftInfo.addView(tvMeta)
            peerRow.addView(leftInfo)

            val btnSecurity = Button(this).apply {
                text = "[ 🔒 ]"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener {
                    switchScreen(5)
                    updateSecurityView(peer.id)
                }
            }

            val btnQuickCall = Button(this).apply {
                text = "[ 📞 CALL ]"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    switchScreen(1)
                    startVoiceCall(peer.id, peer.nickname)
                }
            }

            val btnQuickChat = Button(this).apply {
                text = "[ 💬 BBS ]"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    switchScreen(2)
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
                setBackgroundColor(Color.parseColor("#0F2744"))
            } else {
                setBackgroundColor(if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#E2E8F0"))
            }
        }

        val tvSender = TextView(this).apply {
            this.text = if (isMe) "[ LOCAL_NODE // YOU ]" else "[ PEER // ${sender.uppercase(Locale.ROOT)} ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isMe) Color.parseColor("#38BDF8") else Color.parseColor("#F59E0B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val tvMsg = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isMe || isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        }

        bubbleLayout.addView(tvSender)
        bubbleLayout.addView(tvMsg)
        container.addView(bubbleLayout)

        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        // Navigation Dock Tab Listeners
        tabRoster.setOnClickListener { switchScreen(0) }
        tabComms.setOnClickListener { switchScreen(1) }
        tabChat.setOnClickListener { switchScreen(2) }
        tabRadar.setOnClickListener { switchScreen(3) }
        tabChannels.setOnClickListener { switchScreen(4) }
        tabLogs.setOnClickListener { switchScreen(5) }

        // Radio Power Toggle Listeners
        btnToggleBluetooth.setOnClickListener { toggleBluetoothRadio() }
        btnBtPower.setOnClickListener { toggleBluetoothRadio() }
        btnToggleWifi.setOnClickListener { toggleWifiRadio() }
        btnWifiPower.setOnClickListener { toggleWifiRadio() }

        // Carrier Isolation Lock Listeners
        btnCarrierBtOnly.setOnClickListener { setCarrierMode(RadioTransportMode.BLUETOOTH_ONLY) }
        btnCarrierWifiOnly.setOnClickListener { setCarrierMode(RadioTransportMode.WIFI_ONLY) }
        btnCarrierCombined.setOnClickListener { setCarrierMode(RadioTransportMode.COMBINED) }

        btnThemeToggle.setOnClickListener {
            applyTheme(!isDarkMode)
        }

        tvPeerId.setOnClickListener {
            switchScreen(5)
        }

        tvStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        cardStatus.setOnClickListener {
            showIpSettingsDialog()
        }

        btnAutoScanMesh.setOnClickListener {
            bridge.autoDiscoverAndConnect()
            bridge.startBluetoothScan()
            Toast.makeText(this, "🔄 Manual scan triggered for Bluetooth PAN & Wi-Fi mesh nodes...", Toast.LENGTH_SHORT).show()
        }

        btnConfigNodeIp.setOnClickListener {
            showIpSettingsDialog()
        }

        updateRadioUiState()

        // Comms Screen Actions
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

        // Chat Screen Actions
        btnSendChatMessage.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                bridge.sendChatMessage(text, "Android Phone (${Build.MODEL})")
                chatMessageList.add(Pair("You", text))
                appendChatBubble(chatMessagesContainer, chatScrollView, "You", text, true)
                logEvent("[Chat Sent] $text")
                etChatMessage.setText("")
            }
        }

        // Radar Screen Actions
        btnRadarBroadcastNow.setOnClickListener {
            if (hasGpsFix) {
                bridge.sendLocationUpdate(currentLatitude, currentLongitude, currentAltitude, currentAccuracy)
                logEvent("[Location] 📍 Broadcasted GPS ($currentLatitude, $currentLongitude) to mesh")
                Toast.makeText(this, "📍 Location Broadcasted to Mesh!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Acquiring GPS fix... Please ensure Location is enabled", Toast.LENGTH_SHORT).show()
                initLocationEngine()
            }
        }

        // Channel Screen Actions
        val selectRoom: (String) -> Unit = { roomName ->
            currentRoom = roomName.uppercase(Locale.ROOT).trim()
            tvCurrentRoom.text = "🇮🇳 [ FREQ: $currentRoom ]"
            bridge.sendJoinRoom(currentRoom)
            logEvent("[Nationwide HD] Switched to All-India Hub: $currentRoom")
            Toast.makeText(this, "Joined Hub: $currentRoom", Toast.LENGTH_SHORT).show()
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

        // Logs Screen Actions
        btnClearHistory.setOnClickListener {
            callHistoryManager.clearHistory()
            renderCallHistoryView()
            Toast.makeText(this, "Call history cleared", Toast.LENGTH_SHORT).show()
        }

        audioEngine.onAudioFrameCaptured = { frame ->
            bridge.sendAudioFrame(frame)
        }
    }

    private fun updateRadioUiState() {
        // Bluetooth buttons
        val btText = if (isBtRadioEnabled) "⚡ BT: ON" else "⚡ BT: OFF"
        val btColor = if (isBtRadioEnabled) Color.parseColor("#00F0FF") else Color.parseColor("#94A3B8")
        btnToggleBluetooth.text = btText
        btnToggleBluetooth.setTextColor(btColor)
        btnBtPower.text = if (isBtRadioEnabled) "⚡ BT: ON (Tap to Disable)" else "⚡ BT: OFF (Tap to Enable)"
        btnBtPower.setTextColor(btColor)

        // Wi-Fi buttons
        val wifiText = if (isWifiRadioEnabled) "📶 Wi-Fi: ON" else "📶 Wi-Fi: OFF"
        val wifiColor = if (isWifiRadioEnabled) Color.parseColor("#00FF66") else Color.parseColor("#94A3B8")
        btnToggleWifi.text = wifiText
        btnToggleWifi.setTextColor(wifiColor)
        btnWifiPower.text = if (isWifiRadioEnabled) "📶 Wi-Fi: ON (Tap to Disable)" else "📶 Wi-Fi: OFF (Tap to Enable)"
        btnWifiPower.setTextColor(wifiColor)

        // Carrier buttons
        when (bridge.transportMode) {
            RadioTransportMode.BLUETOOTH_ONLY -> {
                btnCarrierBtOnly.setTextColor(Color.parseColor("#00F0FF"))
                btnCarrierWifiOnly.setTextColor(Color.parseColor("#64748B"))
                btnCarrierCombined.setTextColor(Color.parseColor("#64748B"))
            }
            RadioTransportMode.WIFI_ONLY -> {
                btnCarrierBtOnly.setTextColor(Color.parseColor("#64748B"))
                btnCarrierWifiOnly.setTextColor(Color.parseColor("#00FF66"))
                btnCarrierCombined.setTextColor(Color.parseColor("#64748B"))
            }
            RadioTransportMode.COMBINED, RadioTransportMode.MANUAL -> {
                btnCarrierBtOnly.setTextColor(Color.parseColor("#64748B"))
                btnCarrierWifiOnly.setTextColor(Color.parseColor("#64748B"))
                btnCarrierCombined.setTextColor(Color.parseColor("#38BDF8"))
            }
        }
    }

    private fun toggleBluetoothRadio() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (!isBtRadioEnabled) {
            isBtRadioEnabled = true
            bridge.startBluetooth(this)
            if (btAdapter != null && !btAdapter.isEnabled) {
                try {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivity(enableBtIntent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (ex: Exception) {}
                }
            }
            logEvent("[Radio] ⚡ Bluetooth radio enabled")
            Toast.makeText(this, "⚡ Bluetooth Radio Turned ON", Toast.LENGTH_SHORT).show()
        } else {
            isBtRadioEnabled = false
            bridge.bluetoothMesh?.stop()
            if (bridge.transportMode == RadioTransportMode.BLUETOOTH_ONLY) {
                bridge.transportMode = RadioTransportMode.WIFI_ONLY
            }
            logEvent("[Radio] ⚡ Bluetooth radio disabled")
            Toast.makeText(this, "⚡ Bluetooth Radio Turned OFF", Toast.LENGTH_SHORT).show()
        }
        updateRadioUiState()
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun toggleWifiRadio() {
        if (!isWifiRadioEnabled) {
            isWifiRadioEnabled = true
            if (bridge.transportMode == RadioTransportMode.BLUETOOTH_ONLY) {
                bridge.transportMode = RadioTransportMode.COMBINED
            }
            bridge.connect(context = this)
            bridge.autoDiscoverAndConnect()
            logEvent("[Radio] 📶 Wi-Fi radio link enabled")
            Toast.makeText(this, "📶 Wi-Fi Radio Turned ON", Toast.LENGTH_SHORT).show()
        } else {
            isWifiRadioEnabled = false
            bridge.disconnect()
            if (bridge.transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                bridge.transportMode = RadioTransportMode.BLUETOOTH_ONLY
            }
            logEvent("[Radio] 📶 Wi-Fi radio link disabled")
            Toast.makeText(this, "📶 Wi-Fi Radio Turned OFF", Toast.LENGTH_SHORT).show()
        }
        updateRadioUiState()
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun setCarrierMode(mode: RadioTransportMode) {
        bridge.transportMode = mode
        when (mode) {
            RadioTransportMode.BLUETOOTH_ONLY -> {
                isBtRadioEnabled = true
                bridge.disconnect()
                bridge.startBluetooth(this)
                logEvent("[Carrier] 🔒 Locked to Bluetooth Dedicated Mode")
                Toast.makeText(this, "🔵 Carrier Lock: Bluetooth Dedicated (Wi-Fi Auto-Discovery Disabled)", Toast.LENGTH_SHORT).show()
            }
            RadioTransportMode.WIFI_ONLY -> {
                isWifiRadioEnabled = true
                bridge.bluetoothMesh?.stop()
                bridge.connect(context = this)
                logEvent("[Carrier] 🔒 Locked to Wi-Fi Dedicated Mode")
                Toast.makeText(this, "🟢 Carrier Lock: Wi-Fi Dedicated (Bluetooth Mesh Disabled)", Toast.LENGTH_SHORT).show()
            }
            RadioTransportMode.COMBINED, RadioTransportMode.MANUAL -> {
                isBtRadioEnabled = true
                isWifiRadioEnabled = true
                bridge.startBluetooth(this)
                bridge.connect(context = this)
                logEvent("[Carrier] 🌐 Dual-Radio Combined Mode Active")
                Toast.makeText(this, "🌐 Multi-Radio Mode: Both Radios Enabled", Toast.LENGTH_SHORT).show()
            }
        }
        updateRadioUiState()
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun showIncomingCallDialog(callerName: String, callerId: String) {
        if (callerId == localPeerId || callerId == bridge.localNodeId || callerId.isEmpty()) {
            return
        }

        if (isCalling) {
            // Already in active call, ignore duplicate packet without declining
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
            switchScreen(1)
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
            btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_cyan))
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

    private fun stopVoiceCall(recordHistory: Boolean = true, notifyRemote: Boolean = true) {
        try {
            audioEngine.stopVoice()
            if (isCalling && recordHistory && activeCallPeerId.isNotEmpty()) {
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
            val wasCalling = isCalling
            val peerToNotify = activeCallPeerId.ifEmpty { "BROADCAST" }
            isCalling = false
            activeCallPeerId = ""
            activeCallPeerName = "Mesh Peer"
            btnCall.text = "[ 📞 START 2-WAY DUPLEX CALL ]"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_emerald))
            if (notifyRemote && wasCalling) {
                bridge.sendCallHangup(peerToNotify)
            }
            logEvent("[Voice Call] Call ended")
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not stop audio engine: ${e.message}")
        }
    }

    private fun showIpSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
            setBackgroundColor(bg)
        }

        // Header Title
        val tvTitle = TextView(this).apply {
            text = "⚙️ [ SYSTEM SETTINGS & CONTROLS ]"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (isDarkMode) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvTitle)

        // Section 1: Node Identity & Cryptography
        val tvIdentityHeader = TextView(this).apply {
            text = "🪪 [ NODE IDENTITY & CRYPTO ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvIdentityHeader)

        val inputNickname = EditText(this).apply {
            val savedNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})")
            setText(savedNick)
            hint = "Tactical Call-Sign / Nickname"
            typeface = android.graphics.Typeface.MONOSPACE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.BLACK)
            textSize = 12f
        }
        layout.addView(inputNickname)

        val btnSaveNick = Button(this).apply {
            text = "[ SAVE CALL-SIGN ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val newNick = inputNickname.text.toString().trim()
                if (newNick.isNotEmpty()) {
                    prefs.edit().putString("tactical_nickname", newNick).apply()
                    bridge.sendSetNickname(newNick)
                    Toast.makeText(this@MainActivity, "✅ Call-sign saved: $newNick", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(btnSaveNick)

        val btnCopyId = Button(this).apply {
            text = "[ 📋 COPY NODE ID: ${bridge.localNodeId} ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
            layoutParams = lp
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Node ID", bridge.localNodeId)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "📋 Node ID copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnCopyId)

        val btnRotateKeys = Button(this).apply {
            text = "[ 🔑 ROTATE NOISE-XX KEYS ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#F59E0B"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 2, 0, 10)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.crypto.rotateKeys()
                logEvent("[Crypto] 🔑 Noise-XX cryptographic session keys regenerated")
                Toast.makeText(this@MainActivity, "🔑 E2EE Session Keys Rotated", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnRotateKeys)

        // Section 2: Bluetooth Hardware Scanner & Direct Controls
        val tvBtHeader = TextView(this).apply {
            text = "📡 [ BLUETOOTH DIRECT HARDWARE MESH ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 10, 0, 4)
        }
        layout.addView(tvBtHeader)

        val btnBtScan = Button(this).apply {
            text = "[ 🔍 TRIGGER DEEP BLUETOOTH SCAN ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.startBluetooth(this@MainActivity)
                bridge.bluetoothMesh?.triggerImmediateScanAndConnect()
                Toast.makeText(this@MainActivity, "🔄 Scanning nearby Bluetooth peers (RFCOMM/SPP)...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnBtScan)

        // List Paired Bluetooth Devices for direct 1-tap connection
        val pairedDevices = bridge.getPairedBluetoothDevices()
        if (pairedDevices.isNotEmpty()) {
            val tvPairedHeader = TextView(this).apply {
                text = "PAIRED HARDWARE DEVICES (${pairedDevices.size}):"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 6, 0, 2)
            }
            layout.addView(tvPairedHeader)

            for (dev in pairedDevices) {
                val devRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 4, 8, 4)
                    background = ColorDrawable(Color.parseColor("#0F172A"))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 2, 0, 2)
                    }
                    layoutParams = lp
                }

                @SuppressLint("MissingPermission")
                val devName = try { dev.name ?: "Bluetooth Device" } catch (e: Exception) { "Bluetooth Device" }
                val devAddr = dev.address

                val tvDev = TextView(this).apply {
                    text = "📱 $devName\n   [$devAddr]"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#F8FAFC"))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }
                devRow.addView(tvDev)

                val btnConnDev = Button(this).apply {
                    text = "LINK"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        bridge.connectBluetoothDevice(devAddr)
                        Toast.makeText(this@MainActivity, "Connecting to $devName...", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                devRow.addView(btnConnDev)
                layout.addView(devRow)
            }
        }

        // Section 3: Wi-Fi, Hotspot & Subnet Discovery
        val tvNetHeader = TextView(this).apply {
            text = "🌐 [ WI-FI, HOTSPOT & SUBNET ROUTER ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 12, 0, 4)
        }
        layout.addView(tvNetHeader)

        val ifaces = bridge.getActiveNetworkInterfaces()
        val ifaceSummary = if (ifaces.isNotEmpty()) ifaces.joinToString(" // ") { "${it.first}: ${it.second}" } else "Local P2P Loopback"
        val tvCurrent = TextView(this).apply {
            val carrierStr = if (bridge.currentHost.isNotEmpty()) "${bridge.currentHost}:3000" else "P2P BLUETOOTH / DYNAMIC MESH"
            text = "CARRIER: $carrierStr\nACTIVE IPS: $ifaceSummary"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isDarkMode) Color.parseColor("#10B981") else Color.parseColor("#475569"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(tvCurrent)

        val btnSweep = Button(this).apply {
            text = "[ 🔄 SWEEP ACTIVE SUBNET (1..254) ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.autoDiscoverAndConnect()
                Toast.makeText(this@MainActivity, "Auto-sweeping subnet across 32 threads...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnSweep)

        // Preset IP Nodes
        val presetsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
            layoutParams = lp
        }

        val btnPresetHotspot = Button(this).apply {
            text = "🔥 HOTSPOT"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#F59E0B"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                bridge.connect("192.168.43.1", this@MainActivity)
                Toast.makeText(this@MainActivity, "Connecting to Hotspot Gateway 192.168.43.1...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        presetsLayout.addView(btnPresetHotspot)

        val btnPresetRouter = Button(this).apply {
            text = "🏠 ROUTER"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.connect("192.168.1.1", this@MainActivity)
                Toast.makeText(this@MainActivity, "Connecting to Router Gateway 192.168.1.1...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        presetsLayout.addView(btnPresetRouter)
        layout.addView(presetsLayout)

        val inputIp = EditText(this).apply {
            hint = "Custom Node IP (e.g. 192.168.1.50)"
            typeface = android.graphics.Typeface.MONOSPACE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.BLACK)
            textSize = 12f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 6, 0, 4)
            }
            layoutParams = lp
        }
        layout.addView(inputIp)

        val btnCustom = Button(this).apply {
            text = "[ LINK CUSTOM NODE IP ]"
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

        // Section 4: Audio Engine & Voice Controls
        val tvAudioHeader = TextView(this).apply {
            text = "🎙️ [ HD VOICE & AUDIO DSP ENGINE ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 14, 0, 4)
        }
        layout.addView(tvAudioHeader)

        val btnToggleSpkr = Button(this).apply {
            text = if (isSpeakerOn) "[ 🔊 DEFAULT ROUTE: SPEAKERPHONE ]" else "[ 🔈 DEFAULT ROUTE: EARPIECE ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#10B981"))
            setOnClickListener {
                isSpeakerOn = !isSpeakerOn
                audioEngine.setSpeakerphoneOn(isSpeakerOn)
                text = if (isSpeakerOn) "[ 🔊 DEFAULT ROUTE: SPEAKERPHONE ]" else "[ 🔈 DEFAULT ROUTE: EARPIECE ]"
                Toast.makeText(this@MainActivity, "Audio route: ${if (isSpeakerOn) "Speakerphone" else "Earpiece"}", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnToggleSpkr)

        // Section 5: Storage & History Reset
        val tvResetHeader = TextView(this).apply {
            text = "🧹 [ STORAGE, CACHE & SYSTEM RESET ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F43F5E"))
            setPadding(0, 14, 0, 4)
        }
        layout.addView(tvResetHeader)

        val btnClearChat = Button(this).apply {
            text = "[ 💬 CLEAR CHAT BBS HISTORY ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                chatMessageList.clear()
                chatMessagesContainer.removeAllViews()
                Toast.makeText(this@MainActivity, "Chat history cleared", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnClearChat)

        val btnClearCalls = Button(this).apply {
            text = "[ 📞 CLEAR CALL HISTORY ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#F43F5E"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                callHistoryManager.clearHistory()
                renderCallHistoryView()
                Toast.makeText(this@MainActivity, "Call records cleared", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnClearCalls)

        val btnExportLogs = Button(this).apply {
            text = "[ 📋 COPY SYSTEM AUDIT LOGS ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#10B981"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                val logsText = tvLogs.text.toString()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("System Logs", logsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "📋 System audit logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnExportLogs)

        scrollView.addView(layout)
        dialog.setContentView(scrollView)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.85).toInt())
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
            // Note: ForegroundMeshService keeps the shared bridge alive in the background
            if (isCalling || isPttTransmitting) {
                audioEngine.stopVoice()
            }
        } catch (e: Exception) {}
    }

    // Helper functions for GPS calculation
    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) - sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    private fun getCompassHeading(bearing: Double): Pair<String, String> {
        return when {
            bearing >= 337.5 || bearing < 22.5 -> Pair("N", "⬆️")
            bearing >= 22.5 && bearing < 67.5 -> Pair("NE", "↗️")
            bearing >= 67.5 && bearing < 112.5 -> Pair("E", "➡️")
            bearing >= 112.5 && bearing < 157.5 -> Pair("SE", "↘️")
            bearing >= 157.5 && bearing < 202.5 -> Pair("S", "⬇️")
            bearing >= 202.5 && bearing < 247.5 -> Pair("SW", "↙️")
            bearing >= 247.5 && bearing < 292.5 -> Pair("W", "⬅️")
            else -> Pair("NW", "↖️")
        }
    }

    private fun formatDistance(distMeters: Double): String {
        return if (distMeters >= 1000) {
            String.format(Locale.US, "%.2f km", distMeters / 1000)
        } else {
            String.format(Locale.US, "%.0f m", distMeters)
        }
    }
}
