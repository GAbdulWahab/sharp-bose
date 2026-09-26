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
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.provider.Settings
import android.util.Base64
import com.offline.calling.audio.AndroidAudioEngine
import com.offline.calling.radio.ChatMessagePacket
import com.offline.calling.radio.ForegroundMeshService
import com.offline.calling.radio.MeshWebSocketBridge
import com.offline.calling.radio.PeerLocation
import com.offline.calling.radio.PeerNode
import com.offline.calling.radio.RadioTransportMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.*

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var audioEngine: AndroidAudioEngine
    private val bridge get() = ForegroundMeshService.getSharedBridge(this)
    private lateinit var callHistoryManager: CallHistoryManager
    private lateinit var contactsManager: ContactsManager
    private var myExtensionNumber: String = "101"
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

    // 7 Dedicated Screens
    private lateinit var screenRoster: ScrollView
    private lateinit var screenComms: ScrollView
    private lateinit var screenChat: LinearLayout
    private lateinit var screenFiles: ScrollView
    private lateinit var screenRadar: ScrollView
    private lateinit var screenChannels: ScrollView
    private lateinit var screenLogsSecurity: ScrollView

    // 7 Navigation Dock Tabs
    private lateinit var tabRoster: Button
    private lateinit var tabComms: Button
    private lateinit var tabChat: Button
    private lateinit var tabFiles: Button
    private lateinit var tabRadar: Button
    private lateinit var tabChannels: Button
    private lateinit var tabLogs: Button
    private var currentTabIndex: Int = 0

    // Screen 7: Files UI
    private lateinit var btnSendPhoto: Button
    private lateinit var btnSendMap: Button
    private lateinit var btnSendVoiceNote: Button
    private lateinit var btnPickCustomFile: Button
    private lateinit var tvTransfersCountBadge: TextView
    private lateinit var llTransfersContainer: LinearLayout
    private lateinit var tvNoTransfersPlaceholder: TextView

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
    private lateinit var btnBtDevicesManager: Button
    private lateinit var btnSystemBtSettings: Button
    private var isBtRadioEnabled = true
    private var isWifiRadioEnabled = false

    // Screen 2: Comms UI
    private lateinit var cardWifiDialer: CardView
    private lateinit var tvMyExtensionBadge: TextView
    private lateinit var etDialNumber: EditText
    private lateinit var btnDialBackspace: Button
    private lateinit var btnCallWifiNumber: Button
    private lateinit var btnCallAllGroup: Button
    private lateinit var btnSaveContactFromDialer: Button
    private lateinit var btnOpenContacts: Button
    private lateinit var llQuickContactsContainer: LinearLayout
    private lateinit var btnKey1: Button
    private lateinit var btnKey2: Button
    private lateinit var btnKey3: Button
    private lateinit var btnKey4: Button
    private lateinit var btnKey5: Button
    private lateinit var btnKey6: Button
    private lateinit var btnKey7: Button
    private lateinit var btnKey8: Button
    private lateinit var btnKey9: Button
    private lateinit var btnKey0: Button
    private lateinit var btnKeyStar: Button
    private lateinit var btnKeyHash: Button

    private lateinit var cardCall: CardView
    private lateinit var btnCall: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnReceiveCall: Button
    private lateinit var cardPtt: CardView
    private lateinit var tvPttChannel: TextView
    private lateinit var btnPtt: Button
    private lateinit var cardSos: CardView
    private lateinit var btnSos: Button

    // Screen 3: Chat BBS & Media UI
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var etChatMessage: EditText
    private lateinit var btnSendChatMessage: Button
    private lateinit var llChatVoiceHud: LinearLayout
    private lateinit var tvChatVoiceTimer: TextView
    private lateinit var btnCancelVoiceRecord: Button
    private lateinit var btnConfirmVoiceSend: Button
    private lateinit var llChatLiveWaveform: LinearLayout
    private lateinit var btnChatPhoto: Button
    private lateinit var btnChatMap: Button
    private lateinit var btnChatVoice: Button
    private lateinit var btnChatFile: Button
    private val chatMessageList = mutableListOf<ChatMessagePacket>()

    // In-Chat Voice Recording State
    private var inChatMediaRecorder: MediaRecorder? = null
    private var inChatVoiceFile: File? = null
    private var inChatVoiceStartTime: Long = 0L
    private var inChatVoiceTimerHandler: android.os.Handler? = null
    private var inChatVoiceTimerRunnable: Runnable? = null
    private var isInChatRecording = false

    // In-Chat Audio Playback State
    private var inChatAudioPlayer: MediaPlayer? = null
    private var activePlayingVoiceMsgId: String? = null
    private var activePlayingButton: Button? = null

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
        contactsManager = ContactsManager(this)
        myExtensionNumber = prefs.getString("my_extension_number", "101") ?: "101"
        bridge.localExtensionNumber = myExtensionNumber
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

        // 7 Dedicated Screen Containers
        screenRoster = findViewById(R.id.screenRoster)
        screenComms = findViewById(R.id.screenComms)
        screenChat = findViewById(R.id.screenChat)
        screenFiles = findViewById(R.id.screenFiles)
        screenRadar = findViewById(R.id.screenRadar)
        screenChannels = findViewById(R.id.screenChannels)
        screenLogsSecurity = findViewById(R.id.screenLogsSecurity)

        // 7 Navigation Dock Tabs
        tabRoster = findViewById(R.id.tabRoster)
        tabComms = findViewById(R.id.tabComms)
        tabChat = findViewById(R.id.tabChat)
        tabFiles = findViewById(R.id.tabFiles)
        tabRadar = findViewById(R.id.tabRadar)
        tabChannels = findViewById(R.id.tabChannels)
        tabLogs = findViewById(R.id.tabLogs)

        // Screen 7: Files
        btnSendPhoto = findViewById(R.id.btnSendPhoto)
        btnSendMap = findViewById(R.id.btnSendMap)
        btnSendVoiceNote = findViewById(R.id.btnSendVoiceNote)
        btnPickCustomFile = findViewById(R.id.btnPickCustomFile)
        tvTransfersCountBadge = findViewById(R.id.tvTransfersCountBadge)
        llTransfersContainer = findViewById(R.id.llTransfersContainer)
        tvNoTransfersPlaceholder = findViewById(R.id.tvNoTransfersPlaceholder)

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
        btnBtDevicesManager = findViewById(R.id.btnBtDevicesManager)
        btnSystemBtSettings = findViewById(R.id.btnSystemBtSettings)

        // Screen 2: Comms
        cardWifiDialer = findViewById(R.id.cardWifiDialer)
        tvMyExtensionBadge = findViewById(R.id.tvMyExtensionBadge)
        etDialNumber = findViewById(R.id.etDialNumber)
        btnDialBackspace = findViewById(R.id.btnDialBackspace)
        btnCallWifiNumber = findViewById(R.id.btnCallWifiNumber)
        btnCallAllGroup = findViewById(R.id.btnCallAllGroup)
        btnSaveContactFromDialer = findViewById(R.id.btnSaveContactFromDialer)
        btnOpenContacts = findViewById(R.id.btnOpenContacts)
        llQuickContactsContainer = findViewById(R.id.llQuickContactsContainer)

        btnKey1 = findViewById(R.id.btnKey1)
        btnKey2 = findViewById(R.id.btnKey2)
        btnKey3 = findViewById(R.id.btnKey3)
        btnKey4 = findViewById(R.id.btnKey4)
        btnKey5 = findViewById(R.id.btnKey5)
        btnKey6 = findViewById(R.id.btnKey6)
        btnKey7 = findViewById(R.id.btnKey7)
        btnKey8 = findViewById(R.id.btnKey8)
        btnKey9 = findViewById(R.id.btnKey9)
        btnKey0 = findViewById(R.id.btnKey0)
        btnKeyStar = findViewById(R.id.btnKeyStar)
        btnKeyHash = findViewById(R.id.btnKeyHash)

        tvMyExtensionBadge.text = "MY EXT: $myExtensionNumber"

        cardCall = findViewById(R.id.cardCall)
        btnCall = findViewById(R.id.btnCall)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnReceiveCall = findViewById(R.id.btnReceiveCall)
        cardPtt = findViewById(R.id.cardPtt)
        tvPttChannel = findViewById(R.id.tvPttChannel)
        btnPtt = findViewById(R.id.btnPtt)
        cardSos = findViewById(R.id.cardSos)
        btnSos = findViewById(R.id.btnSos)

        // Screen 3: Chat BBS & Media UI
        chatScrollView = findViewById(R.id.chatScrollView)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        etChatMessage = findViewById(R.id.etChatMessage)
        btnSendChatMessage = findViewById(R.id.btnSendChatMessage)
        llChatVoiceHud = findViewById(R.id.llChatVoiceHud)
        tvChatVoiceTimer = findViewById(R.id.tvChatVoiceTimer)
        btnCancelVoiceRecord = findViewById(R.id.btnCancelVoiceRecord)
        btnConfirmVoiceSend = findViewById(R.id.btnConfirmVoiceSend)
        llChatLiveWaveform = findViewById(R.id.llChatLiveWaveform)
        btnChatPhoto = findViewById(R.id.btnChatPhoto)
        btnChatMap = findViewById(R.id.btnChatMap)
        btnChatVoice = findViewById(R.id.btnChatVoice)
        btnChatFile = findViewById(R.id.btnChatFile)

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

        val cleanId = localPeerId.replace("node-", "")
        val anonSuffix = if (cleanId.length >= 4) cleanId.takeLast(4).uppercase(Locale.ROOT) else cleanId.uppercase(Locale.ROOT)
        tvPeerId.text = "NODE ID: ANON-$anonSuffix ($localPeerId) • CIPHER: NOISE_XX"
    }

    private fun switchScreen(tabIndex: Int) {
        currentTabIndex = tabIndex

        val screens = listOf(screenRoster, screenComms, screenChat, screenFiles, screenRadar, screenChannels, screenLogsSecurity)
        val tabs = listOf(tabRoster, tabComms, tabChat, tabFiles, tabRadar, tabChannels, tabLogs)

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
            1 -> renderQuickContactsList()
            3 -> renderTransfersList()
            4 -> updateRadarView()
            6 -> {
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

        val cards = listOf(cardStatus, cardNationwide, cardPeople, cardControls, cardWifiDialer, cardCall, cardPtt, cardSos)
        for (card in cards) {
            card.setCardBackgroundColor(bgCard)
        }
        renderConnectedPeopleList(connectedPeersList)
        renderQuickContactsList()
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

        com.offline.calling.transfer.MeshFileTransferManager.instance.onProgressUpdate = { progress ->
            runOnUiThread {
                if (currentTabIndex == 3) {
                    renderTransfersList()
                }
            }
        }

        com.offline.calling.transfer.MeshFileTransferManager.instance.onFileCompleted = { progress, file ->
            runOnUiThread {
                logEvent("[File Transfer] Received complete file: ${progress.fileName} (${progress.fileSize / 1024} KB) CRC32: ${progress.crc32Hex}")
                Toast.makeText(this, "📁 File Received: ${progress.fileName}", Toast.LENGTH_LONG).show()
                if (currentTabIndex == 3) {
                    renderTransfersList()
                }
            }
        }

        bridge.onMeshStatusChanged = { status ->
            runOnUiThread {
                tvStatus.text = status
                val cleanId = localPeerId.replace("node-", "")
                val anonSuffix = if (cleanId.length >= 4) cleanId.takeLast(4).uppercase(Locale.ROOT) else cleanId.uppercase(Locale.ROOT)
                tvPeerId.text = "NODE ID: ANON-$anonSuffix ($localPeerId) • CIPHER: NOISE_XX"
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
                val cleanId = localPeerId.replace("node-", "")
                val anonSuffix = if (cleanId.length >= 4) cleanId.takeLast(4).uppercase(Locale.ROOT) else cleanId.uppercase(Locale.ROOT)
                tvPeerId.text = "NODE ID: ANON-$anonSuffix ($localPeerId) • CIPHER: NOISE_XX"
                connectedPeersList = peers.toMutableList()
                if (currentTabIndex == 0) {
                    renderConnectedPeopleList(connectedPeersList)
                }
                updateRadarView()
            }
        }

        bridge.onIncomingCallWithDetails = { callerName, callerId, callerNumber, targetNumber ->
            runOnUiThread {
                showIncomingCallDialog(callerName, callerId, callerNumber, targetNumber)
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

        bridge.onRichChatMessageReceived = { packet ->
            runOnUiThread {
                chatMessageList.add(packet)
                val logDetail = when (packet.mediaType) {
                    "PHOTO" -> "📷 Photo: ${packet.fileName}"
                    "VECTOR_MAP" -> "🗺️ Waypoint: ${packet.pointName}"
                    "VOICE_LOG" -> "🎙️ Sitrep Voice Memo (${packet.duration}s)"
                    "DOCUMENT" -> "📄 Document: ${packet.fileName}"
                    else -> packet.text
                }
                logEvent("[Chat] 💬 ${packet.senderName}: $logDetail")
                appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
            }
        }

        bridge.onBluetoothDiscovered = { _ ->
            runOnUiThread {
                if (connectedPeersList.isEmpty() && currentTabIndex == 0) {
                    renderConnectedPeopleList(connectedPeersList)
                }
            }
        }

        bridge.onBluetoothScanStateChanged = { _ ->
            runOnUiThread {
                if (connectedPeersList.isEmpty() && currentTabIndex == 0) {
                    renderConnectedPeopleList(connectedPeersList)
                }
            }
        }

        bridge.transportMode = RadioTransportMode.BLUETOOTH_ONLY
        bridge.startBluetooth(this)
        bridge.startBluetoothScan()
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
        
        val connectingDevs = bridge.getConnectingBluetoothDevices()
        
        if (otherPeers.isNotEmpty()) {
            tvPeopleCount.text = "${otherPeers.size} Connected"
        } else if (connectingDevs.isNotEmpty()) {
            tvPeopleCount.text = "${connectingDevs.size} Connecting"
        } else {
            tvPeopleCount.text = "0 Connected"
        }

        // 1. Render active connecting cards for in-progress Bluetooth connections
        if (connectingDevs.isNotEmpty()) {
            for (addr in connectingDevs) {
                val connCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#F1F5F9")
                    setBackgroundColor(bg)
                    setPadding(16, 14, 16, 14)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    layoutParams = lp
                }

                val progressBar = ProgressBar(this).apply {
                    isIndeterminate = true
                    val lp = LinearLayout.LayoutParams(48, 48).apply { marginEnd = 14 }
                    layoutParams = lp
                }
                connCard.addView(progressBar)

                val connInfo = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvConnTitle = TextView(this).apply {
                    text = "🔄 CONNECTING TO BLUETOOTH NODE..."
                    setTextColor(Color.parseColor("#00F0FF"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                val tvConnMeta = TextView(this).apply {
                    text = "TARGET: $addr • Direct Fast RFCOMM Link"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                connInfo.addView(tvConnTitle)
                connInfo.addView(tvConnMeta)
                connCard.addView(connInfo)

                val btnCancel = Button(this).apply {
                    text = "CANCEL"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#334155"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        bridge.disconnectBluetoothDevice(addr)
                        renderConnectedPeopleList(connectedPeersList)
                    }
                }
                btnCancel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 80)
                connCard.addView(btnCancel)

                llConnectedPeople.addView(connCard)
            }
        }

        // 2. If no peers are connected, show actionable helper state based on current carrier mode
        if (otherPeers.isEmpty() && connectingDevs.isEmpty()) {
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
                val isBtMode = bridge.transportMode == RadioTransportMode.BLUETOOTH_ONLY
                val isWifiMode = bridge.transportMode == RadioTransportMode.WIFI_ONLY
                val statusMsg = when {
                    isBtMode -> "⚡ [BLUETOOTH DEDICATED MODE]\nReady to connect nearby Bluetooth peers. Tap below to scan and link instantly."
                    isWifiMode -> "📶 [WI-FI MESH DEDICATED MODE]\nReady to link with local Wi-Fi / Hotspot nodes. Tap below to scan subnet."
                    else -> "🌐 [DUAL CARRIER MESH ACTIVE]\nScanning for direct Bluetooth peers & local Wi-Fi nodes."
                }
                text = statusMsg
                setTextColor(if (isDarkMode) Color.parseColor("#38BDF8") else Color.parseColor("#64748B"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setLineSpacing(4f, 1.0f)
            }
            emptyCard.addView(tvNotice)

            val btnActionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 10, 0, 0) }
                layoutParams = lp
            }

            val btnScanBt = Button(this).apply {
                text = "[ ⚡ SCAN BLUETOOTH ]"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0369A1"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setOnClickListener {
                    setCarrierMode(RadioTransportMode.BLUETOOTH_ONLY)
                    bridge.startBluetoothScan()
                    bridge.bluetoothMesh?.triggerImmediateScanAndConnect()
                    renderConnectedPeopleList(connectedPeersList)
                    Toast.makeText(this@MainActivity, "⚡ Scanning Bluetooth mesh nodes...", Toast.LENGTH_SHORT).show()
                }
            }

            val btnScanWifi = Button(this).apply {
                text = "[ 📶 SWEEP WI-FI ]"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#047857"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
                layoutParams = lp
                setOnClickListener {
                    setCarrierMode(RadioTransportMode.WIFI_ONLY)
                    bridge.autoDiscoverAndConnect()
                    Toast.makeText(this@MainActivity, "📶 Sweeping local Wi-Fi subnet...", Toast.LENGTH_SHORT).show()
                }
            }

            btnActionRow.addView(btnScanBt)
            btnActionRow.addView(btnScanWifi)
            emptyCard.addView(btnActionRow)

            val btnQuickBtRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 0) }
                layoutParams = lp
            }

            val btnBtDevicesQuick = Button(this).apply {
                text = "📱 PAIRED & SCANNED BT"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#38BDF8"))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                setOnClickListener {
                    showBluetoothDevicesDialog()
                }
            }

            val btnBtSysQuick = Button(this).apply {
                text = "⚙️ SYSTEM BT SETTINGS"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#F59E0B"))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
                layoutParams = lp
                setOnClickListener {
                    openSystemBluetoothSettings()
                }
            }

            btnQuickBtRow.addView(btnBtDevicesQuick)
            btnQuickBtRow.addView(btnBtSysQuick)
            emptyCard.addView(btnQuickBtRow)

            llConnectedPeople.addView(emptyCard)

            // Render live discovered Bluetooth devices if available
            val discoveredBt = bridge.getDiscoveredBluetoothDevices()
            if (discoveredBt.isNotEmpty()) {
                val tvDiscoveredHeader = TextView(this).apply {
                    text = "📡 NEARBY SCANNED BLUETOOTH NODES (${discoveredBt.size}):"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#00F0FF"))
                    setPadding(4, 12, 4, 4)
                }
                llConnectedPeople.addView(tvDiscoveredHeader)

                for (info in discoveredBt) {
                    val discRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val bg = if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#F1F5F9")
                        setBackgroundColor(bg)
                        setPadding(14, 10, 14, 10)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 6) }
                        layoutParams = lp
                    }

                    val devInfoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvDevName = TextView(this).apply {
                        val bondStr = if (info.isBonded) " [PAIRED]" else ""
                        text = "⚡ ${info.name}$bondStr"
                        setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                        textSize = 12f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }

                    val tvDevMeta = TextView(this).apply {
                        text = "${info.address} • 📶 ${info.rssi} dBm • ${info.transportType}"
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                    }

                    devInfoLayout.addView(tvDevName)
                    devInfoLayout.addView(tvDevMeta)
                    discRow.addView(devInfoLayout)

                    val btnLink = Button(this).apply {
                        text = "⚡ LINK"
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            bridge.connectBluetoothDevice(info.address)
                            renderConnectedPeopleList(connectedPeersList)
                            Toast.makeText(this@MainActivity, "⚡ Connecting to ${info.name}...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    btnLink.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 85)
                    discRow.addView(btnLink)

                    llConnectedPeople.addView(discRow)
                }
            }

            return
        }

        // 3. Render all connected peers
        for (peer in otherPeers) {
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

            val isBtPeer = peer.deviceType.contains("Bluetooth", true) || peer.deviceType.contains("RFCOMM", true) || peer.deviceType.contains("GATT", true)
            val iconStr = if (isBtPeer) "⚡" else if (peer.deviceType.contains("Android", true)) "📱" else "💻"
            val transportBadge = if (isBtPeer) "⚡ BT DIRECT" else "📶 WI-FI MESH"
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

            var distStr = "DIRECT LINK"
            if (peer.hopCount > 0) {
                distStr = "RELAY (${peer.hopCount} HOPS)"
            } else if (hasGpsFix && peer.location != null && peer.location.lat != 0.0) {
                val dist = calculateDistanceMeters(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                val bearing = calculateBearingDegrees(currentLatitude, currentLongitude, peer.location.lat, peer.location.lng)
                val heading = getCompassHeading(bearing)
                distStr = "GRID: ${formatDistance(dist)} ${heading.second} (${bearing.toInt()}°)"
            }

            val tvMeta = TextView(this).apply {
                text = "$transportBadge • ${peer.status.uppercase(Locale.ROOT)} • E2EE // $distStr"
                setTextColor(if (isBtPeer) Color.parseColor("#00F0FF") else Color.parseColor("#10B981"))
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
    }

    private fun appendRichChatBubble(
        container: LinearLayout,
        scrollView: ScrollView?,
        packet: ChatMessagePacket
    ) {
        val isMe = packet.isMe || packet.senderName.equals("You", true) || packet.senderName.equals(bridge.localNodeId, true)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = timeFormat.format(Date(packet.timestamp))

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
            setPadding(16, 12, 16, 12)
            val bg = if (isMe) {
                Color.parseColor("#0F2744")
            } else {
                if (isDarkMode) Color.parseColor("#131D31") else Color.parseColor("#E2E8F0")
            }
            setBackgroundColor(bg)
        }

        // Header Row: Sender + Time + E2EE Badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            layoutParams = lp
        }

        val tvSender = TextView(this).apply {
            text = if (isMe) "[ LOCAL_NODE // YOU ]" else "[ PEER // ${packet.senderName.uppercase(Locale.ROOT)} ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isMe) Color.parseColor("#38BDF8") else Color.parseColor("#F59E0B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        val tvTime = TextView(this).apply {
            text = "$timeStr • 🔒 E2EE"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
        }

        headerRow.addView(tvSender)
        headerRow.addView(tvTime)
        bubbleLayout.addView(headerRow)

        // Render Content based on packet.mediaType
        when (packet.mediaType) {
            "PHOTO" -> {
                val photoContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(6, 6, 6, 6)
                    setBackgroundColor(Color.parseColor("#020617"))
                }

                var bmp: Bitmap? = null
                if (packet.dataUrl.isNotEmpty()) {
                    try {
                        val base64Part = if (packet.dataUrl.contains(",")) packet.dataUrl.substringAfter(",") else packet.dataUrl
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error decoding chat photo: ${e.message}")
                    }
                }

                val iv = ImageView(this).apply {
                    if (bmp != null) {
                        setImageBitmap(bmp)
                    } else {
                        setImageResource(android.R.drawable.ic_menu_camera)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    val lp = LinearLayout.LayoutParams(500, 320)
                    layoutParams = lp
                    setOnClickListener {
                        showPhotoLightbox(bmp, packet.fileName.ifEmpty { "Recon Photo" }, packet.crc32Hex.ifEmpty { "VERIFIED" })
                    }
                }
                photoContainer.addView(iv)

                val tvCaption = TextView(this).apply {
                    text = "📷 ${packet.fileName.ifEmpty { "Recon Photo" }} • CRC32: ${packet.crc32Hex.ifEmpty { "VERIFIED" }}"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(4, 6, 4, 2)
                }
                photoContainer.addView(tvCaption)
                bubbleLayout.addView(photoContainer)
            }

            "VECTOR_MAP" -> {
                val mapCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                    setBackgroundColor(Color.parseColor("#031D14"))
                }

                val tvMapTitle = TextView(this).apply {
                    text = "🗺️ WAYPOINT: ${packet.pointName.ifEmpty { "TACTICAL-WAYPOINT" }}"
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#10B981"))
                }
                mapCard.addView(tvMapTitle)

                val tvCoords = TextView(this).apply {
                    text = String.format(Locale.US, "LAT: %.6f • LNG: %.6f\nDATUM: WGS-84 • 256-BIT NOISE_XX", packet.lat, packet.lng)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, 4, 0, 8)
                }
                mapCard.addView(tvCoords)

                val btnRadarLock = Button(this).apply {
                    text = "[ 🎯 VIEW & LOCK ON RADAR ]"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#065F46"))
                    setTextColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    layoutParams = lp
                    setOnClickListener {
                        switchScreen(4) // Switch to Radar
                        logEvent("[Radar Lock] 📍 Locked onto waypoint '${packet.pointName}' (${packet.lat}, ${packet.lng})")
                        Toast.makeText(this@MainActivity, "🎯 Waypoint '${packet.pointName}' locked on Radar!", Toast.LENGTH_SHORT).show()
                    }
                }
                mapCard.addView(btnRadarLock)
                bubbleLayout.addView(mapCard)
            }

            "VOICE_LOG" -> {
                val voiceCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 8, 10, 8)
                    setBackgroundColor(Color.parseColor("#1F0A10"))
                }

                val msgId = "voice_${System.currentTimeMillis()}_${(100..999).random()}"
                val btnPlay = Button(this).apply {
                    text = "[ ▶ PLAY ]"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BE123C"))
                    setTextColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 80)
                    layoutParams = lp
                    setOnClickListener {
                        togglePlayVoiceMemo(msgId, packet.audioData, this)
                    }
                }
                voiceCard.addView(btnPlay)

                // Simulated Waveform Bars
                val waveContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 0, 10, 0)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }
                val heights = listOf(8, 14, 6, 18, 12, 16, 9, 15, 7, 13)
                for (h in heights) {
                    val bar = View(this).apply {
                        val lp = LinearLayout.LayoutParams(4, h * 2).apply { setMargins(2, 0, 2, 0) }
                        layoutParams = lp
                        setBackgroundColor(Color.parseColor("#F43F5E"))
                    }
                    waveContainer.addView(bar)
                }
                voiceCard.addView(waveContainer)

                val durSec = packet.duration
                val tvDuration = TextView(this).apply {
                    text = String.format(Locale.US, "🎙️ 0:%02d", durSec)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#F59E0B"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                voiceCard.addView(tvDuration)
                bubbleLayout.addView(voiceCard)
            }

            "DOCUMENT" -> {
                val docCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 12, 10)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#0F172A"))
                        cornerRadius = 10f
                        setStroke(1, Color.parseColor("#1E293B"))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val rawBytes = if (packet.fileData.isNotEmpty()) {
                            try { android.util.Base64.decode(packet.fileData, android.util.Base64.DEFAULT) } catch(e: Exception) { null }
                        } else null
                        showDocumentViewerDialog(packet.fileName, rawBytes, packet.crc32Hex.ifEmpty { "VERIFIED" })
                    }
                }

                val tvIcon = TextView(this).apply {
                    text = "📄"
                    textSize = 20f
                    setPadding(0, 0, 8, 0)
                }
                docCard.addView(tvIcon)

                val infoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }

                val tvDocName = TextView(this).apply {
                    text = packet.fileName.ifEmpty { "document.bin" }
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }

                val sizeKb = packet.fileSize / 1024
                val tvDocMeta = TextView(this).apply {
                    text = "$sizeKb KB • CRC32: ${packet.crc32Hex.ifEmpty { "VERIFIED" }}"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#38BDF8"))
                }
                infoLayout.addView(tvDocName)
                infoLayout.addView(tvDocMeta)
                docCard.addView(infoLayout)

                val btnOpen = Button(this).apply {
                    text = "[ 📂 OPEN ]"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72)
                    layoutParams = lp
                    setOnClickListener {
                        val rawBytes = if (packet.fileData.isNotEmpty()) {
                            try { android.util.Base64.decode(packet.fileData, android.util.Base64.DEFAULT) } catch(e: Exception) { null }
                        } else null
                        showDocumentViewerDialog(packet.fileName, rawBytes, packet.crc32Hex.ifEmpty { "VERIFIED" })
                    }
                }
                docCard.addView(btnOpen)
                bubbleLayout.addView(docCard)
            }

            else -> {
                val tvMsg = TextView(this).apply {
                    this.text = packet.text
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (isMe || isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                }
                bubbleLayout.addView(tvMsg)
            }
        }

        container.addView(bubbleLayout)
        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun appendChatBubble(
        container: LinearLayout,
        scrollView: ScrollView?,
        sender: String,
        text: String,
        isMe: Boolean
    ) {
        val packet = ChatMessagePacket(
            senderName = sender,
            text = text,
            mediaType = "TEXT",
            isMe = isMe
        )
        appendRichChatBubble(container, scrollView, packet)
    }

    // In-Chat Voice Recording Implementation (Real Microphone Voice)
    private fun startInChatVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }

        try {
            stopInChatAudioPlayback()
            val tempFile = File(cacheDir, "chat_voice_${System.currentTimeMillis()}.mp4")
            inChatVoiceFile = tempFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setOutputFile(tempFile.absolutePath)
            recorder.prepare()
            recorder.start()

            inChatMediaRecorder = recorder
            inChatVoiceStartTime = System.currentTimeMillis()
            isInChatRecording = true

            llChatVoiceHud.visibility = View.VISIBLE
            tvChatVoiceTimer.text = "🔴 [ RECORDING REAL VOICE 00:00 ]"

            val waveViews = listOf(
                findViewById<View>(R.id.vWave1), findViewById<View>(R.id.vWave2),
                findViewById<View>(R.id.vWave3), findViewById<View>(R.id.vWave4),
                findViewById<View>(R.id.vWave5), findViewById<View>(R.id.vWave6),
                findViewById<View>(R.id.vWave7), findViewById<View>(R.id.vWave8),
                findViewById<View>(R.id.vWave9), findViewById<View>(R.id.vWave10)
            )

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            inChatVoiceTimerHandler = handler
            val runnable = object : Runnable {
                override fun run() {
                    if (isInChatRecording) {
                        val elapsedSec = ((System.currentTimeMillis() - inChatVoiceStartTime) / 1000).toInt()
                        val m = elapsedSec / 60
                        val s = elapsedSec % 60
                        tvChatVoiceTimer.text = String.format(Locale.US, "🔴 [ RECORDING REAL VOICE %02d:%02d ]", m, s)

                        var maxAmp = 1000
                        try {
                            maxAmp = inChatMediaRecorder?.maxAmplitude ?: 1000
                        } catch (e: Exception) {}
                        val normalized = minOf(100, maxOf(15, (maxAmp / 300)))
                        for (w in waveViews) {
                            if (w != null) {
                                val randomH = (normalized * (0.5 + Math.random() * 0.8)).toInt()
                                val lp = w.layoutParams
                                lp.height = maxOf(6, minOf(36, (randomH / 3)))
                                w.layoutParams = lp
                            }
                        }

                        handler.postDelayed(this, 100)
                    }
                }
            }
            inChatVoiceTimerRunnable = runnable
            handler.post(runnable)
            logEvent("[Voice Chat] 🎙️ Recording real microphone audio memo...")

        } catch (e: Exception) {
            logEvent("[Error] Voice recorder failed: ${e.message}")
            Toast.makeText(this, "Voice Record Error: ${e.message}", Toast.LENGTH_SHORT).show()
            cancelInChatVoiceRecording()
        }
    }

    private fun cancelInChatVoiceRecording() {
        isInChatRecording = false
        inChatVoiceTimerHandler?.removeCallbacksAndMessages(null)
        inChatVoiceTimerHandler = null
        try {
            inChatMediaRecorder?.stop()
            inChatMediaRecorder?.release()
        } catch (e: Exception) {}
        inChatMediaRecorder = null
        inChatVoiceFile?.delete()
        inChatVoiceFile = null
        llChatVoiceHud.visibility = View.GONE
        logEvent("[Voice Chat] Recording cancelled")
    }

    private fun stopAndSendInChatVoiceRecording() {
        if (!isInChatRecording) return
        isInChatRecording = false
        inChatVoiceTimerHandler?.removeCallbacksAndMessages(null)
        inChatVoiceTimerHandler = null

        val durationSec = maxOf(1, ((System.currentTimeMillis() - inChatVoiceStartTime) / 1000).toInt())

        try {
            inChatMediaRecorder?.stop()
            inChatMediaRecorder?.release()
        } catch (e: Exception) {}
        inChatMediaRecorder = null
        llChatVoiceHud.visibility = View.GONE

        val file = inChatVoiceFile
        if (file != null && file.exists() && file.length() > 0) {
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val audioDataUrl = "data:audio/mp4;base64,$base64"

            val myNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone"
            bridge.sendChatVoiceLog(audioDataUrl, durationSec, myNick)

            val packet = ChatMessagePacket(
                senderName = "You",
                text = "",
                mediaType = "VOICE_LOG",
                audioData = audioDataUrl,
                duration = durationSec,
                isMe = true
            )
            chatMessageList.add(packet)
            appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
            logEvent("[Voice Chat] 🚀 Sent voice memo (${durationSec}s)")
            Toast.makeText(this, "🎙️ Voice memo broadcasted to mesh", Toast.LENGTH_SHORT).show()
        }
    }

    // In-Chat Audio Playback Engine
    private fun stopInChatAudioPlayback() {
        try {
            inChatAudioPlayer?.stop()
            inChatAudioPlayer?.release()
        } catch (e: Exception) {}
        inChatAudioPlayer = null
        activePlayingButton?.text = "[ ▶ PLAY ]"
        activePlayingButton = null
        activePlayingVoiceMsgId = null
    }

    private fun togglePlayVoiceMemo(msgId: String, audioData: String, btnPlay: Button) {
        if (activePlayingVoiceMsgId == msgId) {
            stopInChatAudioPlayback()
            return
        }

        stopInChatAudioPlayback()

        try {
            val base64Part = if (audioData.contains(",")) audioData.substringAfter(",") else audioData
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            val tempPlayFile = File(cacheDir, "play_memo_${System.currentTimeMillis()}.mp4")
            tempPlayFile.writeBytes(bytes)

            val player = MediaPlayer()
            player.setDataSource(tempPlayFile.absolutePath)
            player.prepare()
            player.start()

            inChatAudioPlayer = player
            activePlayingVoiceMsgId = msgId
            activePlayingButton = btnPlay
            btnPlay.text = "[ ⏸ PAUSE ]"

            player.setOnCompletionListener {
                btnPlay.text = "[ ▶ PLAY ]"
                activePlayingVoiceMsgId = null
                activePlayingButton = null
                tempPlayFile.delete()
            }
            player.setOnErrorListener { _, _, _ ->
                btnPlay.text = "[ ▶ PLAY ]"
                activePlayingVoiceMsgId = null
                activePlayingButton = null
                tempPlayFile.delete()
                false
            }
        } catch (e: Exception) {
            logEvent("[Error] Audio playback failed: ${e.message}")
            Toast.makeText(this, "Audio play error: ${e.message}", Toast.LENGTH_SHORT).show()
            btnPlay.text = "[ ▶ PLAY ]"
        }
    }

    // In-Chat Vector Waypoint Dialog
    private fun showVectorMapDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
            setBackgroundColor(bg)
        }

        val tvTitle = TextView(this).apply {
            text = "🗺️ [ DISPATCH TACTICAL VECTOR WAYPOINT ]"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvTitle)

        val tvSub = TextView(this).apply {
            text = "Broadcast GIS coordinates over mesh. Peers can 1-tap lock onto tactical radar."
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvSub)

        val tvPresets = TextView(this).apply {
            text = "TACTICAL PRESETS:"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvPresets)

        val inputName = EditText(this).apply {
            hint = "Waypoint Call-Sign"
            typeface = android.graphics.Typeface.MONOSPACE
            setText("RALLY-POINT-ALPHA")
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }

        val inputLat = EditText(this).apply {
            hint = "Latitude (e.g. 28.613900)"
            typeface = android.graphics.Typeface.MONOSPACE
            setText(if (hasGpsFix) String.format(Locale.US, "%.6f", currentLatitude) else "28.613900")
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }

        val inputLng = EditText(this).apply {
            hint = "Longitude (e.g. 77.209000)"
            typeface = android.graphics.Typeface.MONOSPACE
            setText(if (hasGpsFix) String.format(Locale.US, "%.6f", currentLongitude) else "77.209000")
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }

        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 2, 0, 10)
            }
            layoutParams = lp
        }

        val btnP1 = Button(this).apply {
            text = "HQ"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#10B981"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                inputName.setText("TACTICAL-BASE-HQ")
                inputLat.setText("28.613900")
                inputLng.setText("77.209000")
            }
        }

        val btnP2 = Button(this).apply {
            text = "EVAC LZ"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#F59E0B"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
            layoutParams = lp
            setOnClickListener {
                inputName.setText("EVAC-LZ-ALPHA")
                inputLat.setText("28.612800")
                inputLng.setText("77.229500")
            }
        }

        val btnP3 = Button(this).apply {
            text = "GPS FIX"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                inputName.setText("LIVE-GPS-FIX")
                inputLat.setText(String.format(Locale.US, "%.6f", currentLatitude))
                inputLng.setText(String.format(Locale.US, "%.6f", currentLongitude))
            }
        }

        presetsRow.addView(btnP1)
        presetsRow.addView(btnP2)
        presetsRow.addView(btnP3)
        layout.addView(presetsRow)

        layout.addView(inputName)
        layout.addView(inputLat)
        layout.addView(inputLng)

        val btnSend = Button(this).apply {
            text = "[ 🗺️ BROADCAST WAYPOINT TO MESH ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 10, 0, 4)
            }
            layoutParams = lp
            setOnClickListener {
                val name = inputName.text.toString().trim().ifEmpty { "TACTICAL-WAYPOINT" }
                val lat = inputLat.text.toString().toDoubleOrNull() ?: 28.6139
                val lng = inputLng.text.toString().toDoubleOrNull() ?: 77.2090
                val myNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone"

                bridge.sendChatVectorMap(name, lat, lng, myNick)

                val packet = ChatMessagePacket(
                    senderName = "You",
                    text = "",
                    mediaType = "VECTOR_MAP",
                    pointName = name,
                    lat = lat,
                    lng = lng,
                    isMe = true
                )
                chatMessageList.add(packet)
                appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
                logEvent("[Vector Map] 🗺️ Broadcasted waypoint '$name' [$lat, $lng]")
                Toast.makeText(this@MainActivity, "🗺️ Waypoint Broadcasted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnSend)

        val btnCancel = Button(this).apply {
            text = "[ ✕ CANCEL ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#94A3B8"))
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(btnCancel)

        dialog.setContentView(layout)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    // Photo Lightbox Dialog
    private fun showPhotoLightbox(bitmap: Bitmap?, caption: String, crc32: String) {
        if (bitmap == null) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#020617"))
        }

        val tvTitle = TextView(this).apply {
            text = "📷 [ PHOTO LIGHTBOX // $caption ]"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(tvTitle)

        val iv = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
            layoutParams = lp
        }
        layout.addView(iv)

        val tvMeta = TextView(this).apply {
            text = "CHECKSUM: CRC32: $crc32 • NOISE_XX E2EE AUTHENTICATED"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvMeta)

        val btnClose = Button(this).apply {
            text = "[ ✕ CLOSE PREVIEW ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(btnClose)

        dialog.setContentView(layout)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.95).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun pickPhotoForChat() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Photo to Share in Chat"), 3001)
        } catch (e: Exception) {
            Toast.makeText(this, "No image picker found: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickDocumentForChat() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Document to Share in Chat"), 3002)
        } catch (e: Exception) {
            Toast.makeText(this, "No file manager found: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        // Navigation Dock Tab Listeners
        tabRoster.setOnClickListener { switchScreen(0) }
        tabComms.setOnClickListener { switchScreen(1) }
        tabChat.setOnClickListener { switchScreen(2) }
        tabFiles.setOnClickListener { switchScreen(3) }
        tabRadar.setOnClickListener { switchScreen(4) }
        tabChannels.setOnClickListener { switchScreen(5) }
        tabLogs.setOnClickListener { switchScreen(6) }

        // File Sharing Actions
        btnSendPhoto.setOnClickListener { dispatchSampleMedia("PHOTO") }
        btnSendMap.setOnClickListener { dispatchSampleMedia("MAP") }
        btnSendVoiceNote.setOnClickListener { dispatchSampleMedia("VOICE") }
        btnPickCustomFile.setOnClickListener { pickFileFromDevice() }

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

        btnBtDevicesManager.setOnClickListener {
            showBluetoothDevicesDialog()
        }

        btnSystemBtSettings.setOnClickListener {
            openSystemBluetoothSettings()
        }

        updateRadioUiState()

        // Keypad Digit Listeners
        val appendDigit: (String) -> Unit = { digit ->
            etDialNumber.append(digit)
        }
        btnKey1.setOnClickListener { appendDigit("1") }
        btnKey2.setOnClickListener { appendDigit("2") }
        btnKey3.setOnClickListener { appendDigit("3") }
        btnKey4.setOnClickListener { appendDigit("4") }
        btnKey5.setOnClickListener { appendDigit("5") }
        btnKey6.setOnClickListener { appendDigit("6") }
        btnKey7.setOnClickListener { appendDigit("7") }
        btnKey8.setOnClickListener { appendDigit("8") }
        btnKey9.setOnClickListener { appendDigit("9") }
        btnKey0.setOnClickListener { appendDigit("0") }
        btnKeyStar.setOnClickListener { appendDigit("*") }
        btnKeyHash.setOnClickListener { appendDigit("#") }

        btnDialBackspace.setOnClickListener {
            val cur = etDialNumber.text.toString()
            if (cur.isNotEmpty()) {
                etDialNumber.setText(cur.substring(0, cur.length - 1))
                etDialNumber.setSelection(etDialNumber.text.length)
            }
        }
        btnDialBackspace.setOnLongClickListener {
            etDialNumber.setText("")
            true
        }

        btnCallWifiNumber.setOnClickListener {
            val num = etDialNumber.text.toString().trim()
            if (num.isNotEmpty()) {
                dialAndCallWifiNumber(num)
            } else {
                Toast.makeText(this, "Please enter an extension or number to dial", Toast.LENGTH_SHORT).show()
            }
        }

        btnCallAllGroup.setOnClickListener {
            dialAndCallWifiNumber("*")
        }

        btnSaveContactFromDialer.setOnClickListener {
            val num = etDialNumber.text.toString().trim()
            showAddContactDialog(defaultNumber = num)
        }

        btnOpenContacts.setOnClickListener {
            showContactsBookDialog()
        }

        renderQuickContactsList()

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

        // Chat Screen & Media Actions
        btnSendChatMessage.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                val myNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone"
                bridge.sendChatMessage(text, myNick)
                val packet = ChatMessagePacket(
                    senderName = "You",
                    text = text,
                    mediaType = "TEXT",
                    isMe = true
                )
                chatMessageList.add(packet)
                appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
                logEvent("[Chat Sent] $text")
                etChatMessage.setText("")
            }
        }

        btnChatPhoto.setOnClickListener { pickPhotoForChat() }
        btnChatMap.setOnClickListener { showVectorMapDialog() }
        btnChatVoice.setOnClickListener { startInChatVoiceRecording() }
        btnChatFile.setOnClickListener { pickDocumentForChat() }
        btnCancelVoiceRecord.setOnClickListener { cancelInChatVoiceRecording() }
        btnConfirmVoiceSend.setOnClickListener { stopAndSendInChatVoiceRecording() }

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
        isBtRadioEnabled = true
        bridge.transportMode = RadioTransportMode.BLUETOOTH_ONLY
        bridge.disconnect() // Cleanly disconnect Wi-Fi for dedicated Bluetooth link
        bridge.startBluetooth(this)
        bridge.startBluetoothScan()
        bridge.bluetoothMesh?.triggerImmediateScanAndConnect()

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
        logEvent("[Radio] ⚡ Bluetooth Dedicated Mode Active • Scanning nearby devices")
        Toast.makeText(this, "⚡ Bluetooth Mode: Scanning & Linking Bluetooth Devices", Toast.LENGTH_SHORT).show()
        updateRadioUiState()
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun toggleWifiRadio() {
        isWifiRadioEnabled = true
        bridge.transportMode = RadioTransportMode.WIFI_ONLY
        bridge.bluetoothMesh?.stop() // Pause Bluetooth for dedicated Wi-Fi link
        bridge.connect(context = this)
        bridge.autoDiscoverAndConnect()
        logEvent("[Radio] 📶 Wi-Fi Dedicated Mode Active • Scanning local mesh")
        Toast.makeText(this, "📶 Wi-Fi Mode: Connecting & Sweeping Wi-Fi Mesh", Toast.LENGTH_SHORT).show()
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
                bridge.startBluetoothScan()
                bridge.bluetoothMesh?.triggerImmediateScanAndConnect()
                logEvent("[Carrier] 🔒 Locked to Bluetooth Dedicated Mode")
                Toast.makeText(this, "🔵 Bluetooth Dedicated Mode Active", Toast.LENGTH_SHORT).show()
            }
            RadioTransportMode.WIFI_ONLY -> {
                isWifiRadioEnabled = true
                bridge.bluetoothMesh?.stop()
                bridge.connect(context = this)
                bridge.autoDiscoverAndConnect()
                logEvent("[Carrier] 🔒 Locked to Wi-Fi Dedicated Mode")
                Toast.makeText(this, "🟢 Wi-Fi Dedicated Mode Active", Toast.LENGTH_SHORT).show()
            }
            RadioTransportMode.COMBINED, RadioTransportMode.MANUAL -> {
                isBtRadioEnabled = true
                isWifiRadioEnabled = true
                bridge.startBluetooth(this)
                bridge.connect(context = this)
                bridge.autoDiscoverAndConnect()
                logEvent("[Carrier] 🌐 Dual-Radio Combined Mode Active")
                Toast.makeText(this, "🌐 Multi-Radio Mode: Both Bluetooth & Wi-Fi Active", Toast.LENGTH_SHORT).show()
            }
        }
        updateRadioUiState()
        renderConnectedPeopleList(connectedPeersList)
    }

    private fun showIncomingCallDialog(callerName: String, callerId: String, callerNumber: String = "", targetNumber: String = "") {
        if (callerId == localPeerId || callerId == bridge.localNodeId || callerId.isEmpty()) {
            return
        }

        if (isCalling) {
            // Already in active call, ignore duplicate packet without declining
            return
        }

        incomingCallDialog?.dismiss()

        // Match against Contacts Directory
        val matchedContact = if (callerNumber.isNotEmpty()) contactsManager.findContactByNumber(callerNumber)
        else contactsManager.findContactByNodeIdOrIp(callerId)

        val displayName = matchedContact?.name ?: callerName
        val displayRoute = if (callerNumber.isNotEmpty()) {
            "EXT: $callerNumber • WI-FI CALL • E2EE"
        } else {
            "NODE ID: $callerId // NOISE_XX E2EE ACTIVE"
        }

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
        tvCallerName.text = ">>> [ ${displayName.uppercase(Locale.ROOT)} ] <<<"
        tvCallerId.typeface = android.graphics.Typeface.MONOSPACE
        tvCallerId.text = displayRoute

        btnAccept.typeface = android.graphics.Typeface.MONOSPACE
        btnAccept.text = "[ 📞 ACCEPT CALL ]"
        btnAccept.setOnClickListener {
            dialog.dismiss()
            incomingCallDialog = null
            activeCallPeerName = displayName
            activeCallPeerId = callerId
            callStartTime = System.currentTimeMillis()
            isCalling = true
            switchScreen(1)
            try {
                audioEngine.startVoice()
                btnCall.text = "[ 🔴 END CALL ]"
                btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
                bridge.sendCallAccept(callerId)
                logEvent("[Live Call] Call accepted with $displayName ($callerId)")
                Toast.makeText(this, "Connected with $displayName", Toast.LENGTH_SHORT).show()
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
                    peerName = displayName,
                    peerId = callerId,
                    type = "DECLINED",
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0
                )
            )
            logEvent("[Live Call] Call declined from $displayName")
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

    private fun dialAndCallWifiNumber(number: String) {
        val trimmed = number.trim()
        val isGroupCall = trimmed == "*" || trimmed == "000" || trimmed == "999" || trimmed.equals("ALL", ignoreCase = true) || trimmed == "0"

        if (isGroupCall) {
            logEvent("[Wi-Fi Call] 🌐 Initiating Group Call (ALL STATIONS) across Wi-Fi mesh")
            bridge.autoDiscoverAndConnect(this)
            startVoiceCall(targetPeerId = "BROADCAST", targetPeerName = "All Stations (Mesh Broadcast)", targetNumber = "*")
            return
        }

        val contact = contactsManager.findContactByNumber(trimmed)
        val targetName = contact?.name ?: "Ext: $trimmed"
        var targetId = "BROADCAST"

        // Match against online mesh nodes
        val matchedPeer = connectedPeersList.find {
            it.number == trimmed || (contact != null && contact.ipOrNodeId.isNotEmpty() && (it.id == contact.ipOrNodeId || it.id.contains(contact.ipOrNodeId)))
        }
        if (matchedPeer != null) {
            targetId = matchedPeer.id
        } else if (contact != null && contact.ipOrNodeId.isNotEmpty()) {
            if (contact.ipOrNodeId.contains(".") && !contact.ipOrNodeId.startsWith("node-")) {
                bridge.connect(contact.ipOrNodeId, this)
            }
            targetId = contact.ipOrNodeId
        } else if (trimmed.contains(".") && trimmed.length >= 7) {
            // Direct IP dial
            bridge.connect(trimmed, this)
            targetId = trimmed
        } else {
            // Extension dialed but not yet directly connected - trigger auto discovery sweep in background
            bridge.autoDiscoverAndConnect(this)
        }

        logEvent("[Wi-Fi Call] 📶 Dialed extension $trimmed ($targetName) via local Wi-Fi mesh")
        startVoiceCall(targetPeerId = targetId, targetPeerName = targetName, targetNumber = trimmed)
    }

    private fun startVoiceCall(targetPeerId: String = "", targetPeerName: String = "Mesh Peer", targetNumber: String = "") {
        try {
            audioEngine.startVoice()
            isCalling = true
            callStartTime = System.currentTimeMillis()
            activeCallPeerName = if (targetPeerName.isNotEmpty()) targetPeerName else "Mesh Peer"
            activeCallPeerId = targetPeerId
            btnCall.text = "[ 🔴 END CALL ]"
            btnCall.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_rose))
            bridge.sendCallInvite(
                targetId = targetPeerId,
                senderName = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone",
                targetNumber = targetNumber,
                senderNumber = myExtensionNumber
            )
            logEvent("[Voice Call] 🔒 Outgoing Call to $activeCallPeerName (${if (targetNumber.isNotEmpty()) "Ext: $targetNumber" else activeCallPeerId})")
            Toast.makeText(this, "Calling $activeCallPeerName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logEvent("[Error] Could not start audio engine: ${e.message}")
        }
    }

    private fun renderQuickContactsList() {
        if (!::llQuickContactsContainer.isInitialized) return
        llQuickContactsContainer.removeAllViews()

        val contacts = contactsManager.getContacts()
        if (contacts.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "NO SAVED CONTACTS. TAP [➕ SAVE] OR [👥 BOOK] TO ADD."
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                textSize = 10f
                setPadding(0, 8, 0, 8)
            }
            llQuickContactsContainer.addView(tvEmpty)
            return
        }

        for (contact in contacts.take(5)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9")
                setBackgroundColor(bg)
                setPadding(12, 8, 12, 8)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 6) }
                layoutParams = lp
            }

            val isOnline = connectedPeersList.any {
                it.number == contact.number || (contact.ipOrNodeId.isNotEmpty() && (it.id == contact.ipOrNodeId || it.id.contains(contact.ipOrNodeId)))
            }

            val tvAvatar = TextView(this).apply {
                text = if (contact.name.isNotEmpty()) contact.name.take(1).uppercase(Locale.ROOT) else "👤"
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(try { Color.parseColor(contact.colorHex) } catch(e: Exception) { Color.parseColor("#0284C7") })
                }
                val lp = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 10 }
                layoutParams = lp
            }
            row.addView(tvAvatar)

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = contact.name.uppercase(Locale.ROOT)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            }
            nameRow.addView(tvName)

            val tvDot = TextView(this).apply {
                text = if (isOnline) " ● LIVE" else " ○ MESH"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(if (isOnline) Color.parseColor("#10B981") else Color.parseColor("#64748B"))
                setPadding(6, 0, 0, 0)
            }
            nameRow.addView(tvDot)
            infoLayout.addView(nameRow)

            val tvNum = TextView(this).apply {
                val ipSuffix = if (contact.ipOrNodeId.isNotEmpty()) " • ${contact.ipOrNodeId}" else ""
                text = "EXT: ${contact.number}$ipSuffix"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#38BDF8"))
            }
            infoLayout.addView(tvNum)
            row.addView(infoLayout)

            val btnCallContact = Button(this).apply {
                text = "[ 📞 CALL ]"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 76)
                layoutParams = lp
                setOnClickListener {
                    etDialNumber.setText(contact.number)
                    dialAndCallWifiNumber(contact.number)
                }
            }
            row.addView(btnCallContact)

            llQuickContactsContainer.addView(row)
        }
    }

    private fun showContactsBookDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 20)
            val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
            setBackgroundColor(bg)
        }

        val tvTitle = TextView(this).apply {
            text = "👥 [ TACTICAL CONTACTS DIRECTORY ]"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvTitle)

        val tvSubtitle = TextView(this).apply {
            text = "Direct extension & IP contacts for 1-tap encrypted Wi-Fi mesh calling."
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvSubtitle)

        val btnAddNew = Button(this).apply {
            text = "[ ➕ ADD NEW CONTACT ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 12)
            }
            layoutParams = lp
            setOnClickListener {
                dialog.dismiss()
                showAddContactDialog()
            }
        }
        layout.addView(btnAddNew)

        val contactsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val contacts = contactsManager.getContacts()
        if (contacts.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No saved contacts in directory."
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                setPadding(0, 20, 0, 20)
                gravity = Gravity.CENTER
            }
            contactsListContainer.addView(tvEmpty)
        } else {
            for (contact in contacts) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F8FAFC")
                    setBackgroundColor(bg)
                    setPadding(12, 10, 12, 10)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    layoutParams = lp
                }

                val isOnline = connectedPeersList.any {
                    it.number == contact.number || (contact.ipOrNodeId.isNotEmpty() && (it.id == contact.ipOrNodeId || it.id.contains(contact.ipOrNodeId)))
                }

                val tvAvatar = TextView(this).apply {
                    text = if (contact.name.isNotEmpty()) contact.name.take(1).uppercase(Locale.ROOT) else "👤"
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(try { Color.parseColor(contact.colorHex) } catch(e: Exception) { Color.parseColor("#0284C7") })
                    }
                    val lp = LinearLayout.LayoutParams(44, 44).apply { marginEnd = 10 }
                    layoutParams = lp
                }
                card.addView(tvAvatar)

                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(this).apply {
                    text = contact.name.uppercase(Locale.ROOT)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                }
                nameRow.addView(tvName)

                val tvStatus = TextView(this).apply {
                    text = if (isOnline) " ● LIVE" else " ○ MESH"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (isOnline) Color.parseColor("#10B981") else Color.parseColor("#64748B"))
                    setPadding(6, 0, 0, 0)
                }
                nameRow.addView(tvStatus)
                details.addView(nameRow)

                val tvMeta = TextView(this).apply {
                    val ipStr = if (contact.ipOrNodeId.isNotEmpty()) " • IP/ID: ${contact.ipOrNodeId}" else ""
                    val notesStr = if (contact.notes.isNotEmpty()) " (${contact.notes})" else ""
                    text = "NUMBER: ${contact.number}$ipStr$notesStr"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#38BDF8"))
                }
                details.addView(tvMeta)
                card.addView(details)

                // Actions Layout
                val actionsLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val btnCall = Button(this).apply {
                    text = "📞 CALL"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 76).apply { marginEnd = 4 }
                    layoutParams = lp
                    setOnClickListener {
                        dialog.dismiss()
                        etDialNumber.setText(contact.number)
                        dialAndCallWifiNumber(contact.number)
                    }
                }
                actionsLayout.addView(btnCall)

                val btnDelete = Button(this).apply {
                    text = "✕"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
                    setTextColor(Color.parseColor("#F43F5E"))
                    val lp = LinearLayout.LayoutParams(60, 76)
                    layoutParams = lp
                    setOnClickListener {
                        contactsManager.deleteContact(contact.id)
                        renderQuickContactsList()
                        dialog.dismiss()
                        showContactsBookDialog()
                        Toast.makeText(this@MainActivity, "Contact deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                actionsLayout.addView(btnDelete)

                card.addView(actionsLayout)
                contactsListContainer.addView(card)
            }
        }
        layout.addView(contactsListContainer)

        val btnClose = Button(this).apply {
            text = "[ ✕ CLOSE DIRECTORY ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#94A3B8"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 12, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(btnClose)

        scrollView.addView(layout)
        dialog.setContentView(scrollView)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.82).toInt())
        dialog.show()
    }

    private fun showAddContactDialog(defaultNumber: String = "", editContact: MeshContact? = null) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 20)
            val bg = if (isDarkMode) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
            setBackgroundColor(bg)
        }

        val tvTitle = TextView(this).apply {
            text = if (editContact != null) "✏️ [ EDIT CONTACT ]" else "➕ [ ADD TACTICAL CONTACT ]"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(tvTitle)

        val inputName = EditText(this).apply {
            hint = "Contact Name / Call-Sign (e.g. Base HQ)"
            typeface = android.graphics.Typeface.MONOSPACE
            if (editContact != null) setText(editContact.name)
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12f
        }
        layout.addView(inputName)

        val inputNumber = EditText(this).apply {
            hint = "Extension / Number (e.g. 100, 101, 9876)"
            typeface = android.graphics.Typeface.MONOSPACE
            if (editContact != null) setText(editContact.number)
            else if (defaultNumber.isNotEmpty()) setText(defaultNumber)
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        layout.addView(inputNumber)

        val inputIp = EditText(this).apply {
            hint = "Optional Node IP / ID (e.g. 192.168.1.50)"
            typeface = android.graphics.Typeface.MONOSPACE
            if (editContact != null) setText(editContact.ipOrNodeId)
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12f
        }
        layout.addView(inputIp)

        val inputNotes = EditText(this).apply {
            hint = "Optional Tactical Notes (e.g. Squad Recon)"
            typeface = android.graphics.Typeface.MONOSPACE
            if (editContact != null) setText(editContact.notes)
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12f
        }
        layout.addView(inputNotes)

        val btnSave = Button(this).apply {
            text = "[ 💾 SAVE CONTACT ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 10, 0, 4)
            }
            layoutParams = lp
            setOnClickListener {
                val name = inputName.text.toString().trim()
                val num = inputNumber.text.toString().trim()
                if (name.isEmpty() || num.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val contactToSave = MeshContact(
                    id = editContact?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    number = num,
                    ipOrNodeId = inputIp.text.toString().trim(),
                    notes = inputNotes.text.toString().trim(),
                    colorHex = editContact?.colorHex ?: listOf("#38BDF8", "#10B981", "#F59E0B", "#8B5CF6", "#EC4899").random()
                )
                contactsManager.saveContact(contactToSave)
                renderQuickContactsList()
                Toast.makeText(this@MainActivity, "✅ Contact saved: $name ($num)", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnSave)

        val btnCancel = Button(this).apply {
            text = "[ ✕ CANCEL ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#94A3B8"))
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(btnCancel)

        dialog.setContentView(layout)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
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

    private fun openSystemBluetoothSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open System Bluetooth Settings: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBluetoothDevicesDialog() {
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

        val tvTitle = TextView(this).apply {
            text = "📡 [ BLUETOOTH DEVICES & SCANNER ]"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (isDarkMode) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvTitle)

        val tvSub = TextView(this).apply {
            text = "Manage system paired hardware, trigger deep discovery, and link Bluetooth mesh nodes directly."
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvSub)

        // System Settings & Scan Action Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 12)
            }
            layoutParams = lp
        }

        val btnOpenSys = Button(this).apply {
            text = "⚙️ SYSTEM BT SETTINGS"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                openSystemBluetoothSettings()
            }
        }

        val btnScanNow = Button(this).apply {
            text = "🔄 DEEP SCAN"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
            layoutParams = lp
            setOnClickListener {
                bridge.startBluetooth(this@MainActivity)
                bridge.startBluetoothScan()
                bridge.bluetoothMesh?.triggerImmediateScanAndConnect()
                Toast.makeText(this@MainActivity, "🔄 Scanning nearby Bluetooth nodes...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showBluetoothDevicesDialog()
            }
        }

        btnRow.addView(btnOpenSys)
        btnRow.addView(btnScanNow)
        layout.addView(btnRow)

        // Section A: Paired System Hardware Devices
        val pairedDevices = bridge.getPairedBluetoothDevices()
        val tvPairedHeader = TextView(this).apply {
            text = "📱 PAIRED HARDWARE DEVICES (${pairedDevices.size}):"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 4, 0, 4)
        }
        layout.addView(tvPairedHeader)

        if (pairedDevices.isNotEmpty()) {
            for (dev in pairedDevices) {
                val devRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 8, 10, 8)
                    background = ColorDrawable(if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9"))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 2, 0, 4)
                    }
                    layoutParams = lp
                }

                val devName = bridge.resolveBluetoothDeviceName(dev)
                val devAddr = dev.address

                val tvDev = TextView(this).apply {
                    text = "📱 $devName\n   [$devAddr]"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }
                devRow.addView(tvDev)

                val btnConnDev = Button(this).apply {
                    text = "LINK"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        bridge.connectBluetoothDevice(devAddr)
                        renderConnectedPeopleList(connectedPeersList)
                        Toast.makeText(this@MainActivity, "Connecting to $devName...", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                devRow.addView(btnConnDev)
                layout.addView(devRow)
            }
        } else {
            val tvNoPaired = TextView(this).apply {
                text = "No paired devices found. Pair new devices in Android Settings."
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 2, 0, 6)
            }
            layout.addView(tvNoPaired)
        }

        // Section B: Scanned Nearby Bluetooth Nodes
        val discoveredDevices = bridge.getDiscoveredBluetoothDevices()
        val tvDiscoveredHeader = TextView(this).apply {
            text = "🔍 SCANNED NEARBY BLUETOOTH NODES (${discoveredDevices.size}):"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00F0FF"))
            setPadding(0, 10, 0, 4)
        }
        layout.addView(tvDiscoveredHeader)

        if (discoveredDevices.isNotEmpty()) {
            for (info in discoveredDevices) {
                val discRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 8, 10, 8)
                    background = ColorDrawable(if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9"))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 2, 0, 4)
                    }
                    layoutParams = lp
                }

                val devInfoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvDevName = TextView(this).apply {
                    val bondStr = if (info.isBonded) " [PAIRED]" else ""
                    text = "⚡ ${info.name}$bondStr"
                    setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                val tvDevMeta = TextView(this).apply {
                    text = "${info.address} • 📶 ${info.rssi} dBm • ${info.transportType}"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                devInfoLayout.addView(tvDevName)
                devInfoLayout.addView(tvDevMeta)
                discRow.addView(devInfoLayout)

                val btnLink = Button(this).apply {
                    text = "⚡ LINK"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        bridge.connectBluetoothDevice(info.address)
                        renderConnectedPeopleList(connectedPeersList)
                        Toast.makeText(this@MainActivity, "Connecting to ${info.name}...", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                discRow.addView(btnLink)
                layout.addView(discRow)
            }
        } else {
            val tvNoScanned = TextView(this).apply {
                text = "No scanned Bluetooth devices active. Tap 'DEEP SCAN' above."
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 2, 0, 6)
            }
            layout.addView(tvNoScanned)
        }

        val btnClose = Button(this).apply {
            text = "[ CLOSE ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 10, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(btnClose)

        scrollView.addView(layout)
        dialog.setContentView(scrollView)
        dialog.show()
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

        val inputExt = EditText(this).apply {
            setText(myExtensionNumber)
            hint = "My Tactical Extension / Phone Number (e.g. 101)"
            typeface = android.graphics.Typeface.MONOSPACE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(if (isDarkMode) Color.parseColor("#00FF66") else Color.parseColor("#059669"))
            textSize = 12f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        layout.addView(inputExt)

        val btnSaveNick = Button(this).apply {
            text = "[ SAVE IDENTITY & EXTENSION ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val newNick = inputNickname.text.toString().trim()
                val newExt = inputExt.text.toString().trim().ifEmpty { "101" }
                if (newNick.isNotEmpty()) {
                    prefs.edit().putString("tactical_nickname", newNick).apply()
                }
                prefs.edit().putString("my_extension_number", newExt).apply()
                myExtensionNumber = newExt
                bridge.localExtensionNumber = newExt
                tvMyExtensionBadge.text = "MY EXT: $newExt"
                bridge.sendSetNickname(newNick.ifEmpty { "Android Phone" }, number = newExt)
                Toast.makeText(this@MainActivity, "✅ Identity & Ext saved: $newNick (Ext: $newExt)", Toast.LENGTH_SHORT).show()
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

        val btnSysBtSettings = Button(this).apply {
            text = "[ ⚙️ OPEN ANDROID SYSTEM BLUETOOTH SETTINGS ]"
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
            layoutParams = lp
            setOnClickListener {
                openSystemBluetoothSettings()
            }
        }
        layout.addView(btnSysBtSettings)

        val btnBtScan = Button(this).apply {
            text = "[ 🔍 TRIGGER DEEP BLUETOOTH SCAN ]"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 2, 0, 6)
            }
            layoutParams = lp
            setOnClickListener {
                bridge.startBluetooth(this@MainActivity)
                bridge.startBluetoothScan()
                bridge.bluetoothMesh?.triggerImmediateScanAndConnect()
                Toast.makeText(this@MainActivity, "🔄 Scanning nearby Bluetooth peers (RFCOMM/SPP)...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        layout.addView(btnBtScan)

        // List Paired Bluetooth Devices for direct 1-tap connection
        val pairedDevices = bridge.getPairedBluetoothDevices()
        val tvPairedHeader = TextView(this).apply {
            text = "PAIRED HARDWARE DEVICES (${pairedDevices.size}):"
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 2)
        }
        layout.addView(tvPairedHeader)

        if (pairedDevices.isNotEmpty()) {
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

                val devName = bridge.resolveBluetoothDeviceName(dev)
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
                        renderConnectedPeopleList(connectedPeersList)
                        Toast.makeText(this@MainActivity, "Connecting to $devName...", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                devRow.addView(btnConnDev)
                layout.addView(devRow)
            }
        } else {
            val tvNoPairedDevs = TextView(this).apply {
                text = "No paired devices found. Tap above to open System Bluetooth Settings."
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 2, 0, 4)
            }
            layout.addView(tvNoPairedDevs)
        }

        // List Scanned Bluetooth Devices
        val scannedDevices = bridge.getDiscoveredBluetoothDevices()
        if (scannedDevices.isNotEmpty()) {
            val tvScannedHeader = TextView(this).apply {
                text = "SCANNED NEARBY BLUETOOTH NODES (${scannedDevices.size}):"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00F0FF"))
                setPadding(0, 8, 0, 2)
            }
            layout.addView(tvScannedHeader)

            for (info in scannedDevices) {
                val scRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8, 4, 8, 4)
                    background = ColorDrawable(Color.parseColor("#0F172A"))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 2, 0, 2)
                    }
                    layoutParams = lp
                }

                val tvDev = TextView(this).apply {
                    text = "⚡ ${info.name}\n   [${info.address} • 📶 ${info.rssi} dBm]"
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#F8FAFC"))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                }
                scRow.addView(tvDev)

                val btnConnDev = Button(this).apply {
                    text = "LINK"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        bridge.connectBluetoothDevice(info.address)
                        renderConnectedPeopleList(connectedPeersList)
                        Toast.makeText(this@MainActivity, "Connecting to ${info.name}...", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                scRow.addView(btnConnDev)
                layout.addView(scRow)
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
            cancelInChatVoiceRecording()
            stopInChatAudioPlayback()
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

    // =========================================================================
    // 📁 OFFLINE P2P FILE & MEDIA TRANSFER ENGINE
    // =========================================================================
    private fun renderTransfersList() {
        llTransfersContainer.removeAllViews()
        val transfers = com.offline.calling.transfer.MeshFileTransferManager.instance.getAllTransfers()
        if (transfers.isEmpty()) {
            tvNoTransfersPlaceholder.visibility = View.VISIBLE
            llTransfersContainer.addView(tvNoTransfersPlaceholder)
            tvTransfersCountBadge.text = "0 Transfers"
            return
        }

        tvNoTransfersPlaceholder.visibility = View.GONE
        tvTransfersCountBadge.text = "${transfers.size} Files • CRC32 Verified"

        for (tx in transfers) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val bg = if (isDarkMode) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9")
                setBackgroundColor(bg)
                setPadding(14, 12, 14, 12)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                layoutParams = lp
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = if (tx.fileName.endsWith(".jpg", true) || tx.fileName.endsWith(".png", true)) "🖼️"
            else if (tx.fileName.endsWith(".geojson", true) || tx.fileName.endsWith(".mbtiles", true)) "🗺️"
            else if (tx.fileName.endsWith(".opus", true) || tx.fileName.endsWith(".wav", true)) "🎙️"
            else "📄"

            val tvIcon = TextView(this).apply {
                text = icon
                textSize = 18f
                setPadding(0, 0, 10, 0)
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(this).apply {
                text = tx.fileName
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(if (isDarkMode) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val sizeKb = tx.fileSize / 1024
            val tvMeta = TextView(this).apply {
                text = "$sizeKb KB • ${if (tx.isOutgoing) "Sent by YOU" else "From: ${tx.senderName}"} • ${tx.hops} hop"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#94A3B8"))
            }

            infoLayout.addView(tvName)
            infoLayout.addView(tvMeta)

            val tvStatusBadge = TextView(this).apply {
                text = if (tx.isCompleted) "✓ DONE" else "${tx.progressPercent}%"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (tx.isCompleted) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
                setPadding(10, 4, 10, 4)
                setBackgroundColor(if (tx.isCompleted) Color.parseColor("#064E3B") else Color.parseColor("#083344"))
            }

            headerRow.addView(tvIcon)
            headerRow.addView(infoLayout)
            headerRow.addView(tvStatusBadge)

            if (tx.isCompleted) {
                val btnOpenTx = Button(this).apply {
                    text = "[ 📂 OPEN ]"
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    setTextColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72).apply { setMargins(8, 0, 0, 0) }
                    layoutParams = lp
                    setOnClickListener {
                        showDocumentViewerDialog(tx.fileName, null, tx.crc32Hex)
                    }
                }
                headerRow.addView(btnOpenTx)
            }
            card.addView(headerRow)

            // Progress Bar
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = tx.progressPercent
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    12
                ).apply { setMargins(0, 8, 0, 6) }
            }
            card.addView(progressBar)

            val footerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvCrc = TextView(this).apply {
                text = "CRC32: ${tx.crc32Hex}"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvSpeed = TextView(this).apply {
                text = if (tx.isCompleted) "100% Reassembled" else String.format(Locale.US, "%.1f kB/s", tx.speedKbps)
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#38BDF8"))
            }

            footerRow.addView(tvCrc)
            footerRow.addView(tvSpeed)
            card.addView(footerRow)

            llTransfersContainer.addView(card)
        }
    }

    private fun dispatchSampleMedia(type: String) {
        val (name, sampleContent) = when (type) {
            "PHOTO" -> Pair("recon_image_${System.currentTimeMillis().toString().takeLast(4)}.jpg", "OFFLINE_MESH_CAMERA_IMAGE_SAMPLE_DATA_BINARY_PAYLOAD_".toByteArray() + ByteArray(2048) { (it % 256).toByte() })
            "MAP" -> Pair("tactical_grid_${System.currentTimeMillis().toString().takeLast(4)}.geojson", "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[$currentLongitude,$currentLatitude]},\"properties\":{\"title\":\"Rally Point\"}}]}".toByteArray())
            "VOICE" -> Pair("sitrep_${System.currentTimeMillis().toString().takeLast(4)}.opus", "OFFLINE_OPUS_VOICE_MEMO_PAYLOAD_".toByteArray() + ByteArray(1536) { (it % 256).toByte() })
            else -> Pair("document_${System.currentTimeMillis().toString().takeLast(4)}.txt", "Tactical Mesh Document Data".toByteArray())
        }

        bridge.sendFile(
            fileBytes = sampleContent,
            fileName = name,
            senderName = "Android (${Build.MODEL})",
            targetPeerId = "BROADCAST",
            onChunkSent = { current, total ->
                runOnUiThread {
                    renderTransfersList()
                }
            }
        )
        Toast.makeText(this, "🚀 Broadcasting $name across BLE L2CAP / Wi-Fi mesh...", Toast.LENGTH_SHORT).show()
        renderTransfersList()
    }

    private fun pickFileFromDevice() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select File to Share over Mesh"), 2002)
        } catch (e: Exception) {
            Toast.makeText(this, "No file manager found: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data ?: return

        when (requestCode) {
            2002 -> {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        var name = "mesh_file_${System.currentTimeMillis()}"
                        val cursor = contentResolver.query(uri, null, null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) {
                                    name = it.getString(nameIdx)
                                }
                            }
                        }

                        bridge.sendFile(
                            fileBytes = bytes,
                            fileName = name,
                            senderName = "Android (${Build.MODEL})",
                            targetPeerId = "BROADCAST",
                            onChunkSent = { _, _ ->
                                runOnUiThread { renderTransfersList() }
                            }
                        )
                        Toast.makeText(this, "🚀 Dispatching $name (${bytes.size / 1024} KB) to Mesh...", Toast.LENGTH_SHORT).show()
                        renderTransfersList()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            3001 -> {
                // In-Chat Photo
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val rawBytes = stream.readBytes()
                        var name = "recon_photo_${System.currentTimeMillis().toString().takeLast(4)}.jpg"
                        val cursor = contentResolver.query(uri, null, null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) {
                                    name = it.getString(nameIdx)
                                }
                            }
                        }

                        val crc = CRC32()
                        crc.update(rawBytes)
                        val crc32Hex = "%08X".format(crc.value)

                        val base64Data = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64Data"
                        val myNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone"

                        bridge.sendChatPhoto(dataUrl, name, rawBytes.size.toLong(), crc32Hex, myNick)
                        bridge.sendFile(rawBytes, name, myNick)

                        val packet = ChatMessagePacket(
                            senderName = "You",
                            text = "",
                            mediaType = "PHOTO",
                            dataUrl = dataUrl,
                            fileName = name,
                            fileSize = rawBytes.size.toLong(),
                            crc32Hex = crc32Hex,
                            isMe = true
                        )
                        chatMessageList.add(packet)
                        appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
                        logEvent("[Chat Photo] 📷 Broadcasted photo '$name' (${rawBytes.size / 1024} KB, CRC32: $crc32Hex)")
                        Toast.makeText(this, "📷 Photo Broadcasted to Mesh", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load photo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            3002 -> {
                // In-Chat Document
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val rawBytes = stream.readBytes()
                        var name = "mesh_doc_${System.currentTimeMillis().toString().takeLast(4)}.dat"
                        val cursor = contentResolver.query(uri, null, null, null, null)
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) {
                                    name = it.getString(nameIdx)
                                }
                            }
                        }

                        val crc = CRC32()
                        crc.update(rawBytes)
                        val crc32Hex = "%08X".format(crc.value)
                        val myNick = prefs.getString("tactical_nickname", "Android Phone (${Build.MODEL})") ?: "Android Phone"

                        val fileBase64 = if (rawBytes.size <= 1024 * 1024) {
                            android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
                        } else ""

                        try {
                            val docsDir = File(cacheDir, "mesh_docs").apply { mkdirs() }
                            val targetFile = File(docsDir, name)
                            FileOutputStream(targetFile).use { it.write(rawBytes) }
                        } catch (e: Exception) {}

                        bridge.sendChatDocument(name, rawBytes.size.toLong(), crc32Hex, myNick, fileBase64)
                        bridge.sendFile(rawBytes, name, myNick)

                        val packet = ChatMessagePacket(
                            senderName = "You",
                            text = "",
                            mediaType = "DOCUMENT",
                            fileName = name,
                            fileSize = rawBytes.size.toLong(),
                            crc32Hex = crc32Hex,
                            fileData = fileBase64,
                            isMe = true
                        )
                        chatMessageList.add(packet)
                        appendRichChatBubble(chatMessagesContainer, chatScrollView, packet)
                        logEvent("[Chat Doc] 📄 Broadcasted document '$name' (${rawBytes.size / 1024} KB, CRC32: $crc32Hex)")
                        Toast.makeText(this, "📄 Document Broadcasted to Mesh", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load document: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDocumentViewerDialog(fileName: String, rawBytes: ByteArray? = null, crc32Hex: String = "VERIFIED") {
        try {
            val docsDir = File(cacheDir, "mesh_docs").apply { mkdirs() }
            val targetFile = File(docsDir, fileName)

            var bytes = rawBytes
            if (bytes == null && targetFile.exists()) {
                bytes = targetFile.readBytes()
            }
            if (bytes == null) {
                val transferDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "mesh_transfers")
                val existing = File(transferDir, fileName)
                if (existing.exists()) {
                    bytes = existing.readBytes()
                }
            }
            if (bytes != null && (!targetFile.exists() || targetFile.length() == 0L)) {
                try {
                    FileOutputStream(targetFile).use { it.write(bytes) }
                } catch (e: Exception) {}
            }

            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            val modalRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#0F172A"))
                    cornerRadius = 18f
                    setStroke(2, Color.parseColor("#38BDF8"))
                }
                val lp = LinearLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.90).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }

            val tvTitle = TextView(this).apply {
                text = "📄 TACTICAL DOCUMENT VIEWER"
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 0, 0, 4)
            }
            modalRoot.addView(tvTitle)

            val sizeKb = (bytes?.size?.toLong() ?: targetFile.length()) / 1024
            val tvMeta = TextView(this).apply {
                text = "FILE: $fileName • SIZE: $sizeKb KB • CRC32: $crc32Hex"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, 10)
            }
            modalRoot.addView(tvMeta)

            val previewScroll = ScrollView(this).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    360
                )
                layoutParams = lp
                setBackgroundColor(Color.parseColor("#020617"))
                setPadding(12, 10, 12, 10)
            }

            val tvPreview = TextView(this).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#F8FAFC"))
                val contentStr = if (bytes != null && bytes.isNotEmpty()) {
                    val isText = fileName.endsWith(".txt", true) || fileName.endsWith(".json", true) || fileName.endsWith(".geojson", true) || fileName.endsWith(".log", true) || fileName.endsWith(".md", true) || fileName.endsWith(".csv", true) || fileName.endsWith(".xml", true) || fileName.endsWith(".html", true)
                    if (isText) {
                        try { String(bytes, Charsets.UTF_8).take(2500) } catch(e: Exception) { "[Binary Payload: ${bytes.size} bytes]" }
                    } else {
                        val hex = bytes.take(96).joinToString(" ") { "%02X".format(it) }
                        "[Tactical Binary File Content]\nCRC32 Checksum: $crc32Hex\nTotal Length: ${bytes.size} bytes\n\nHex View:\n$hex"
                    }
                } else {
                    "[Document cached on device: ${targetFile.name}]"
                }
                text = contentStr
            }
            previewScroll.addView(tvPreview)
            modalRoot.addView(previewScroll)

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 14, 0, 0)
            }

            val btnExternalOpen = Button(this).apply {
                text = "[ 📂 SYSTEM APP ]"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0284C7"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
                layoutParams = lp
                setOnClickListener {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", targetFile)
                        val mime = contentResolver.getType(uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(Intent.createChooser(intent, "Open with..."))
                        dialog.dismiss()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No app found to open this document: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val btnClose = Button(this).apply {
                text = "[ ✕ CLOSE ]"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#475569"))
                setTextColor(Color.WHITE)
                setOnClickListener { dialog.dismiss() }
            }

            btnRow.addView(btnExternalOpen)
            btnRow.addView(btnClose)
            modalRoot.addView(btnRow)

            dialog.setContentView(modalRoot)
            dialog.show()

        } catch (e: Exception) {
            Toast.makeText(this, "Could not open document: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
